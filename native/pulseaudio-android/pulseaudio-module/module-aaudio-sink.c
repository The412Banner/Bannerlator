/***
  This file is part of PulseAudio.

  Copyright 2004-2008 Lennart Poettering

  PulseAudio is free software; you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published
  by the Free Software Foundation; either version 2.1 of the License,
  or (at your option) any later version.

  PulseAudio is distributed in the hope that it will be useful, but
  WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PulseAudio; if not, see <http://www.gnu.org/licenses/>.
***/

/*
 * Winlator AAudio sink — original by Tom Yan & BrunoSX (brunodev85/pulseaudio-android, LGPL-2.1).
 *
 * Bannerlator additions (LGPL-2.1, same license): ADAPTIVE, xrun-driven buffer sizing plus explicit
 * fine-tune modargs, to fix crackling (AAudio buffer underruns) while keeping latency as low as the
 * device can sustain. Crackle = the AAudio output buffer runs dry under guest CPU load (box64/FEX +
 * DXVK). Upstream sets a FIXED buffer at the chosen performance mode and never reacts to underruns.
 * We start at a small buffer (low latency) and, on detecting the stream's xrun counter rising, grow
 * the buffer by one burst at a time up to a cap/capacity — the canonical Google/Oboe adaptive pattern
 * — so it settles at the lowest crackle-free size for THIS device + game. Search "BANNERLATOR" below.
 *
 * New modargs (on top of upstream sink_name/sink_properties/rate/volume/performance_mode):
 *   adaptive=<0|1>            enable xrun-driven auto-grow (default 1)
 *   buffer_frames=<n>         initial buffer size in frames (0 = auto: framesPerBurst*2)
 *   max_buffer_frames=<n>     cap for adaptive growth (0 = device buffer capacity)
 */

#include <config.h>

#include <pulse/timeval.h>

#include <pulsecore/i18n.h>
#include <pulsecore/module.h>
#include <pulsecore/sink.h>
#include <pulsecore/thread.h>
#include <pulsecore/modargs.h>

#include <android/versioning.h>
#undef __INTRODUCED_IN
#define __INTRODUCED_IN(api_level)
#include <aaudio/AAudio.h>

PA_MODULE_AUTHOR("Tom Yan, BrunoSX; adaptive buffer by Bannerlator");
PA_MODULE_DESCRIPTION("Winlator AAudio sink (adaptive)");
PA_MODULE_VERSION(PACKAGE_VERSION);
PA_MODULE_LOAD_ONCE(false);
PA_MODULE_USAGE(
    "sink_name=<name for the sink> "
    "sink_properties=<properties for the sink> "
    "rate=<sampling rate> "
    "volume=<output volume>"
    "performance_mode=<performance mode: 0 (NONE), 1 (Low Latency), 2 (Power Saving)>"
    "adaptive=<auto-grow buffer on underruns: 0 or 1>"
    "buffer_frames=<initial buffer size in frames, 0=auto>"
    "max_buffer_frames=<cap for adaptive growth, 0=capacity>"
);

#define DEFAULT_SINK_NAME "AAudioSink"

enum {
    SINK_MESSAGE_RENDER = PA_SINK_MESSAGE_MAX,
};

struct userdata {
    pa_core *core;
    pa_module *module;
    pa_sink *sink;

    pa_thread *thread;
    pa_thread_mq thread_mq;
    pa_rtpoll *rtpoll;
	pa_rtpoll_item *rtpoll_item;
    pa_asyncmsgq *aaudio_msgq;

    pa_memchunk memchunk;
    size_t frame_size;

    AAudioStreamBuilder *builder;
    AAudioStream *stream;
	pa_sample_spec ss;

    float volume;
    int performance_mode;

    /* BANNERLATOR: adaptive buffer-sizing state */
    int      adaptive;             /* modarg: auto-grow on underruns */
    int32_t  req_buffer_frames;    /* modarg: initial buffer (0 = auto) */
    int32_t  max_buffer_frames;    /* modarg: growth cap (0 = capacity) */
    int32_t  frames_per_burst;     /* device burst granularity */
    int32_t  buffer_capacity;      /* device max buffer */
    int32_t  cur_buffer_size;      /* current buffer size in frames */
    int32_t  last_xrun;            /* last-seen underrun count */
};

static const char* const valid_modargs[] = {
    "sink_name",
    "sink_properties",
    "rate",
    "volume",
    "performance_mode",
    "adaptive",           /* BANNERLATOR */
    "buffer_frames",      /* BANNERLATOR */
    "max_buffer_frames",  /* BANNERLATOR */
    NULL
};

/* BANNERLATOR: on an underrun, grow the AAudio buffer by one burst, up to the cap/capacity. Never
 * shrinks, so it settles at the lowest crackle-free size. Cheap, non-blocking — safe in the callback. */
static void banner_adapt_buffer(struct userdata *u) {
    if (!u->adaptive || u->frames_per_burst <= 0 || u->cur_buffer_size <= 0) return;

    int32_t xruns = AAudioStream_getXRunCount(u->stream);
    if (xruns <= u->last_xrun) return;
    u->last_xrun = xruns;

    int32_t cap = (u->max_buffer_frames > 0 && u->max_buffer_frames < u->buffer_capacity)
                      ? u->max_buffer_frames : u->buffer_capacity;
    if (u->cur_buffer_size >= cap) return;

    int32_t want = u->cur_buffer_size + u->frames_per_burst;
    if (want > cap) want = cap;

    int32_t got = AAudioStream_setBufferSizeInFrames(u->stream, want);
    if (got > 0) {
        u->cur_buffer_size = got;
        pa_log_debug("aaudio-sink: grew buffer to %d frames after %d xruns", (int) got, (int) xruns);
    }
}

static aaudio_data_callback_result_t aaudio_data_callback(AAudioStream *stream, void *userdata, void *audioData, int32_t numFrames) {
    struct userdata* u = userdata;
    banner_adapt_buffer(u);   /* BANNERLATOR */
    return pa_asyncmsgq_send(u->aaudio_msgq, PA_MSGOBJECT(u->sink), SINK_MESSAGE_RENDER, audioData, numFrames, NULL);
}

static int pa_create_aaudio_stream(struct userdata *u) {
	aaudio_result_t res;

    res = AAudio_createStreamBuilder(&u->builder);
	if (res != AAUDIO_OK) {
		pa_log("AAudio_createStreamBuilder() failed.");
		return -1;
	}

    AAudioStreamBuilder_setPerformanceMode(u->builder, u->performance_mode);
	AAudioStreamBuilder_setDataCallback(u->builder, aaudio_data_callback, u);
    AAudioStreamBuilder_setFormat(u->builder, u->ss.format == PA_SAMPLE_FLOAT32LE ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(u->builder, u->ss.rate);
    AAudioStreamBuilder_setChannelCount(u->builder, u->ss.channels);

    res = AAudioStreamBuilder_openStream(u->builder, &u->stream);
	if (res != AAUDIO_OK) {
		pa_log("AAudioStreamBuilder_openStream() failed.");
		return -1;
	}

    res = AAudioStreamBuilder_delete(u->builder);
	if (res != AAUDIO_OK) {
		pa_log("AAudioStreamBuilder_delete() failed.");
		return -1;
	}

    u->frame_size = pa_frame_size(&u->ss);

    /* BANNERLATOR: set the initial buffer size + prime the adaptive state. */
    u->frames_per_burst = AAudioStream_getFramesPerBurst(u->stream);
    u->buffer_capacity  = AAudioStream_getBufferCapacityInFrames(u->stream);
    u->last_xrun        = 0;
    {
        int32_t init = u->req_buffer_frames > 0
                           ? u->req_buffer_frames
                           : (u->frames_per_burst > 0 ? u->frames_per_burst * 2 : 0);
        if (u->buffer_capacity > 0 && init > u->buffer_capacity) init = u->buffer_capacity;
        if (init > 0) {
            int32_t got = AAudioStream_setBufferSizeInFrames(u->stream, init);
            u->cur_buffer_size = got > 0 ? got : init;
        } else {
            int32_t got = AAudioStream_getBufferSizeInFrames(u->stream);
            u->cur_buffer_size = got > 0 ? got : 0;
        }
    }
    pa_log_debug("aaudio-sink: perf_mode=%d adaptive=%d burst=%d cap=%d init_buf=%d",
                 u->performance_mode, u->adaptive, (int) u->frames_per_burst,
                 (int) u->buffer_capacity, (int) u->cur_buffer_size);

    return 0;
}

static int sink_process_render(struct userdata *u, void *audioData, int64_t numFrames) {
    if (!PA_SINK_IS_LINKED(u->sink->thread_info.state)) return AAUDIO_CALLBACK_RESULT_STOP;

    u->memchunk.memblock = pa_memblock_new_fixed(u->core->mempool, audioData, u->frame_size * numFrames, false);
    u->memchunk.length = pa_memblock_get_length(u->memchunk.memblock);
    pa_sink_render_into_full(u->sink, &u->memchunk);
    pa_memblock_unref_fixed(u->memchunk.memblock);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static pa_usec_t sink_get_latency(struct userdata *u) {
	return PA_USEC_PER_SEC * AAudioStream_getBufferSizeInFrames(u->stream) / u->ss.rate / 2;
}

static int sink_process_msg(pa_msgobject *o, int code, void *data, int64_t offset, pa_memchunk *memchunk) {
    struct userdata* u = PA_SINK(o)->userdata;

	if (code == SINK_MESSAGE_RENDER) return sink_process_render(u, data, offset);
    return pa_sink_process_msg(o, code, data, offset, memchunk);
};

static int sink_set_state_io_thread(pa_sink *s, pa_sink_state_t state, pa_suspend_cause_t suspend_cause) {
    struct userdata *u = s->userdata;

    if (PA_SINK_IS_OPENED(s->thread_info.state) && (state == PA_SINK_SUSPENDED || state == PA_SINK_UNLINKED)) {
		AAudioStream_requestStop(u->stream);
    }
	else if ((s->thread_info.state == PA_SINK_SUSPENDED || (s->thread_info.state == PA_SINK_INIT && PA_SINK_IS_LINKED(state)))
			 && PA_SINK_IS_OPENED(state)) {
        AAudioStream_requestStart(u->stream);
    }

    return 0;
}

static void thread_func(void *userdata) {
    struct userdata *u = userdata;
    pa_thread_mq_install(&u->thread_mq);

    for (;;) {
        if (PA_UNLIKELY(u->sink->thread_info.rewind_requested)) pa_sink_process_rewind(u->sink, 0);

		int res = pa_rtpoll_run(u->rtpoll);
        if (res < 0) {
			goto error;
		}
		else if (res == 0) break;
    }

error:
    pa_asyncmsgq_post(u->thread_mq.outq, PA_MSGOBJECT(u->core), PA_CORE_MESSAGE_UNLOAD_MODULE, u->module, 0, NULL, NULL);
    pa_asyncmsgq_wait_for(u->thread_mq.inq, PA_MESSAGE_SHUTDOWN);
}

void pa__done(pa_module* m) {
    struct userdata *u;

    if (!(u = m->userdata)) return;

    if (u->sink) {
		pa_sink_unlink(u->sink);
		pa_sink_unref(u->sink);
	}

	if (u->stream) AAudioStream_close(u->stream);
    if (u->rtpoll_item) pa_rtpoll_item_free(u->rtpoll_item);
    if (u->aaudio_msgq) pa_asyncmsgq_unref(u->aaudio_msgq);

	pa_xfree(u);
}

int pa__init(pa_module* m) {
	struct userdata *u = NULL;
	pa_modargs *ma = NULL;

    if (!(ma = pa_modargs_new(m->argument, valid_modargs))) {
        pa_log("Failed to parse module arguments.");
		goto error;
    }

	m->userdata = u = pa_xnew0(struct userdata, 1);

    u->core = m->core;
    u->module = m;
    u->rtpoll = pa_rtpoll_new();

    if (pa_thread_mq_init(&u->thread_mq, m->core->mainloop, u->rtpoll) < 0) {
        pa_log("pa_thread_mq_init() failed.");
        goto error;
    }

    u->aaudio_msgq = pa_asyncmsgq_new(0);
    if (!u->aaudio_msgq) {
        pa_log("pa_asyncmsgq_new() failed.");
        goto error;
    }

	u->rtpoll_item = pa_rtpoll_item_new_asyncmsgq_read(u->rtpoll, PA_RTPOLL_EARLY-1, u->aaudio_msgq);

    u->ss = m->core->default_sample_spec;
    pa_channel_map map = m->core->default_channel_map;

    if (pa_modargs_get_sample_spec_and_channel_map(ma, &u->ss, &map, PA_CHANNEL_MAP_DEFAULT) < 0) {
        pa_log("pa_modargs_get_sample_spec_and_channel_map() failed.");
        goto error;
    }

	u->ss.channels = 2;
    u->ss.format = u->ss.format == PA_SAMPLE_FLOAT32LE || u->ss.format == PA_SAMPLE_FLOAT32BE ? PA_SAMPLE_FLOAT32LE : PA_SAMPLE_S16LE;

    u->volume = 1.0;
    u->performance_mode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;

    /* BANNERLATOR: adaptive defaults */
    u->adaptive = 1;
    u->req_buffer_frames = 0;
    u->max_buffer_frames = 0;

    // Default 1.0 (unity), NOT 0.0: pa_modargs_get_value_double returns 0 (success) and leaves `volume`
    // UNCHANGED when the arg is absent — with a 0.0 init that silently muted the sink. Start at 1.0 so
    // an absent volume arg keeps the sink at unity.
    double volume = 1.0;
    if (!pa_modargs_get_value_double(ma, "volume", &volume)) u->volume = volume;

    int performance_mode = 0;
    if (!pa_modargs_get_value_s32(ma, "performance_mode", &performance_mode)) {
        switch (performance_mode) {
            case 0:
                u->performance_mode = AAUDIO_PERFORMANCE_MODE_NONE;
                break;
            case 1:
                u->performance_mode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
                break;
            case 2:
                u->performance_mode = AAUDIO_PERFORMANCE_MODE_POWER_SAVING;
                break;
        }
    }

    /* BANNERLATOR: adaptive modargs */
    int adaptive = 1;
    if (!pa_modargs_get_value_s32(ma, "adaptive", &adaptive)) u->adaptive = adaptive ? 1 : 0;
    int32_t bf = 0;
    if (!pa_modargs_get_value_s32(ma, "buffer_frames", &bf) && bf > 0) u->req_buffer_frames = bf;
    int32_t mbf = 0;
    if (!pa_modargs_get_value_s32(ma, "max_buffer_frames", &mbf) && mbf > 0) u->max_buffer_frames = mbf;

    if (pa_create_aaudio_stream(u) < 0) goto error;

	pa_sink_new_data data;
    pa_sink_new_data_init(&data);
    data.driver = __FILE__;
    data.module = m;
    pa_sink_new_data_set_name(&data, pa_modargs_get_value(ma, "sink_name", DEFAULT_SINK_NAME));
    pa_sink_new_data_set_sample_spec(&data, &u->ss);
    pa_sink_new_data_set_alternate_sample_rate(&data, u->ss.rate);
    pa_sink_new_data_set_channel_map(&data, &map);

    if (u->volume != 1.0) {
        pa_cvolume cvol;
        cvol.channels = 2;
        cvol.values[0] = u->volume * PA_VOLUME_NORM;
        cvol.values[1] = u->volume * PA_VOLUME_NORM;

        pa_sink_new_data_set_volume(&data, &cvol);
    }

    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_DESCRIPTION, _("AAudio Output"));
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_CLASS, "abstract");

    if (pa_modargs_get_proplist(ma, "sink_properties", data.proplist, PA_UPDATE_REPLACE) < 0) {
        pa_log("pa_modargs_get_proplist() failed.");
        pa_sink_new_data_done(&data);
        goto error;
    }

    u->sink = pa_sink_new(m->core, &data, 0);
    pa_sink_new_data_done(&data);

    if (!u->sink) {
        pa_log("Failed to create sink object.");
        goto error;
    }

    u->sink->parent.process_msg = sink_process_msg;
    u->sink->set_state_in_io_thread = sink_set_state_io_thread;
    u->sink->userdata = u;

    pa_sink_set_asyncmsgq(u->sink, u->thread_mq.inq);
    pa_sink_set_rtpoll(u->sink, u->rtpoll);
    pa_sink_set_fixed_latency(u->sink, sink_get_latency(u));

    if (!(u->thread = pa_thread_new("aaudio-sink", thread_func, u))) {
        pa_log("Failed to create thread.");
        goto error;
    }

    pa_sink_put(u->sink);
    pa_modargs_free(ma);
	return 0;
error:
    if (ma) pa_modargs_free(ma);
	pa__done(m);
	return -1;
}
