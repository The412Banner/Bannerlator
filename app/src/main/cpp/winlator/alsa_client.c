#include <aaudio/AAudio.h>
#include <jni.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

// ALSA-path AAudio player (guest winealsa -> aserver -> here). Blocking-write stream.
//
// Bannerlator adaptive rework (mirrors the PulseAudio module): the stream used to open at a FIXED
// LOW_LATENCY buffer and do nothing on underruns or route changes, so it crackled under box64/FEX+DXVK
// load and went silent when headphones/BT (dis)connected. Now the stream is wrapped in AlsaStream and
// write() (a) GROWS the buffer one burst at a time on underruns (xrun-driven, up to capacity), and
// (b) REOPENS the stream on a route disconnect (AAUDIO_ERROR_DISCONNECTED) so audio follows to the new
// device. streamPtr stays an opaque handle to Java (ALSAClient).
//
// The engine is driven by a process-global config (perf mode / adaptive / buffer target / max) pushed
// from Java via nativeSetAudioConfig — the same presets/fine-tune knobs the PulseAudio path exposes,
// wired to the shared "banner_audio" prefs so the one in-game/container/shortcut UI drives both engines.

#define TAG "alsa_client"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define WAIT_COMPLETION_TIMEOUT 100 * 1000000L

enum Format {U8, S16LE, S16BE, FLOATLE, FLOATBE};

typedef struct {
    AAudioStream *stream;
    int32_t format;
    int8_t  channelCount;
    int32_t sampleRate;
    int32_t curBufferSize;   // frames; grows on underrun
    int32_t framesPerBurst;  // device burst granularity
    int32_t capacity;        // device max buffer (frames)
    int32_t lastXrun;        // last-seen underrun count
    int      started;        // was requestStart called (so a reopen can re-start)
    int64_t  writes;         // write() call count (for the measurement heartbeat)
    int64_t  lastReopenNs;   // monotonic time of last reopen (throttle a disconnect storm)
    int      gen;            // config generation this stream opened with (live re-apply on change)
} AlsaStream;

// Process-global adaptive-audio config, mirroring the PulseAudio module's presets/fine-tune knobs.
// Set from Java (ALSAClient.nativeSetAudioConfig) at launch and on every in-game "apply"; read at
// stream open. Single writer (Java main thread) / many readers (per-stream audio threads): plain int
// read/write is atomic on arm64, and a stale read only defers a config pickup by one buffer. A bumped
// g_configGen makes each live stream reopen on its next write() so changes apply without a relaunch.
// Defaults = today's device-proven ALSA behavior (NONE + adaptive on, auto buffer) so an unconfigured
// container behaves exactly as the proven build until the user picks a preset.
static volatile int g_perfMode  = 0;   // 0=NONE (proven-clean), 1=LOW_LATENCY, 2=POWER_SAVING
static volatile int g_adaptive  = 1;   // grow the buffer on underruns
static volatile int g_bufTarget = 0;   // frames; 0 = guest-requested (winealsa) size
static volatile int g_maxBuf    = 0;   // frames; 0 = device capacity (else clamp capacity down)
static volatile int g_configGen = 0;   // bumped on each config change -> live reopen

static int64_t nowNs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000000LL + t.tv_nsec;
}

static aaudio_performance_mode_t toAAudioPerfMode(int m) {
    switch (m) {
        case 1:  return AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
        case 2:  return AAUDIO_PERFORMANCE_MODE_POWER_SAVING;
        default: return AAUDIO_PERFORMANCE_MODE_NONE;
    }
}

static aaudio_format_t toAAudioFormat(int format) {
    switch (format) {
        case FLOATLE:
        case FLOATBE:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:
            return AAUDIO_FORMAT_UNSPECIFIED;
        case S16LE:
        case S16BE:
        default:
            return AAUDIO_FORMAT_PCM_I16;
    }
}

// Open (or reopen) the underlying AAudio stream on the CURRENT default route, applying curBufferSize.
static int openStream(AlsaStream *s) {
    AAudioStreamBuilder *builder;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return -1;

    // Performance mode from the shared config (default NONE). NONE, not LOW_LATENCY, is the proven
    // default: LOW_LATENCY caps the device buffer capacity to a tiny FAST buffer (measured ~3844 frames
    // @48k) — far below what winealsa requests (~13454, ~280ms) — which starves the stream and dripped
    // underruns. NONE gives a capacity large enough to honor the guest's buffer. The user can still pick
    // LOW_LATENCY via the "Low latency" preset (lowest delay, may crackle) — that's their explicit call.
    AAudioStreamBuilder_setPerformanceMode(builder, toAAudioPerfMode(g_perfMode));
    AAudioStreamBuilder_setFormat(builder, toAAudioFormat(s->format));
    AAudioStreamBuilder_setChannelCount(builder, s->channelCount);
    AAudioStreamBuilder_setSampleRate(builder, s->sampleRate);

    if (AAudioStreamBuilder_openStream(builder, &s->stream) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        s->stream = NULL;
        return -1;
    }
    AAudioStreamBuilder_delete(builder);

    s->framesPerBurst = AAudioStream_getFramesPerBurst(s->stream);
    s->capacity       = AAudioStream_getBufferCapacityInFrames(s->stream);
    // Optional user cap on the buffer (g_maxBuf): clamp the effective capacity down so the adaptive
    // ceiling and the target below never exceed it — this is the "max buffer / max latency" knob.
    if (g_maxBuf > 0 && g_maxBuf < s->capacity) s->capacity = g_maxBuf;
    // Buffer target: g_bufTarget when set (the "buffer frames" knob), else the guest-requested size
    // (winealsa), else two bursts. Clamp to the effective capacity, then capture what the device
    // ACTUALLY gave us (setBufferSizeInFrames silently caps). The old code stored the requested value,
    // which made the heartbeat report buf>cap and made adaptBuffer's "curBufferSize >= capacity" guard
    // fire immediately — so the buffer never grew despite live underruns. curBufferSize must be truth.
    int32_t want = g_bufTarget > 0 ? g_bufTarget : s->curBufferSize;
    if (want <= 0) want = s->framesPerBurst > 0 ? s->framesPerBurst * 2 : 0;
    if (s->capacity > 0 && want > s->capacity) want = s->capacity;
    if (want > 0) {
        int32_t got = AAudioStream_setBufferSizeInFrames(s->stream, want);
        if (got > 0) s->curBufferSize = got;
    }
    s->lastXrun       = 0;
    s->gen            = g_configGen;   // remember which config this stream is running
    LOGI("open: perf=%d adaptive=%d fmt=%d ch=%d rate=%d buf=%d burst=%d cap=%d gen=%d",
         g_perfMode, g_adaptive, s->format, (int) s->channelCount, s->sampleRate,
         (int) s->curBufferSize, (int) s->framesPerBurst, (int) s->capacity, s->gen);
    return 0;
}

// Grow the buffer by one burst on a fresh underrun (monotonic, capped at capacity). Cheap; safe here.
static void adaptBuffer(AlsaStream *s) {
    if (!g_adaptive) return;   // adaptive safety net disabled by the user (e.g. "Low latency" preset)
    if (s->framesPerBurst <= 0 || s->curBufferSize <= 0 || s->capacity <= 0) return;
    int32_t x = AAudioStream_getXRunCount(s->stream);
    if (x <= s->lastXrun) return;
    s->lastXrun = x;
    if (s->curBufferSize >= s->capacity) return;
    int32_t want = s->curBufferSize + s->framesPerBurst;
    if (want > s->capacity) want = s->capacity;
    int32_t got = AAudioStream_setBufferSizeInFrames(s->stream, want);
    if (got > 0) {
        LOGI("grow: xruns=%d buf %d->%d (cap %d)", (int) x, (int) s->curBufferSize, (int) got, (int) s->capacity);
        s->curBufferSize = got;
    }
}

// Reopen on the current route after a disconnect (headset/BT/HDMI change), preserving state.
static int reopen(AlsaStream *s) {
    if (s->stream) { AAudioStream_close(s->stream); s->stream = NULL; }
    if (openStream(s) < 0) return -1;
    if (s->started) {
        AAudioStream_requestStart(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
    LOGW("reopened AAudio stream on route change (buf=%d frames)", (int) s->curBufferSize);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj, jint format,
                                               jbyte channelCount, jint sampleRate, jint bufferSize) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) calloc(1, sizeof(AlsaStream));
    if (!s) return 0;
    s->format = format;
    s->channelCount = channelCount;
    s->sampleRate = sampleRate;
    s->curBufferSize = bufferSize;
    if (openStream(s) < 0) { free(s); return 0; }
    return (jlong) s;
}

// Static config setter (ALSAClient.nativeSetAudioConfig). Called from Java at launch (resolved from
// the container/shortcut env + banner_audio prefs) and on every in-game "apply". Bumping g_configGen
// makes all live streams reopen on their next write() so the change applies without a relaunch.
JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_nativeSetAudioConfig(JNIEnv *env, jclass clazz,
                                               jint perfMode, jint adaptive, jint bufTarget, jint maxBuf) {
    (void) env; (void) clazz;
    g_perfMode  = perfMode;
    g_adaptive  = adaptive ? 1 : 0;
    g_bufTarget = bufTarget > 0 ? bufTarget : 0;
    g_maxBuf    = maxBuf > 0 ? maxBuf : 0;
    g_configGen++;
    LOGI("config set: perf=%d adaptive=%d bufTarget=%d maxBuf=%d gen=%d",
         g_perfMode, g_adaptive, g_bufTarget, g_maxBuf, g_configGen);
}

JNIEXPORT jint JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                              jint numFrames) {
    (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (!s || !s->stream) return -1;
    void *buf = (*env)->GetDirectBufferAddress(env, buffer);

    // Live config apply: the in-game AUDIO tab bumps g_configGen; reopen so the new perf mode / adaptive
    // / buffer settings take effect this session without a relaunch (throttled to avoid a churn storm).
    if (s->gen != g_configGen && (nowNs() - s->lastReopenNs) >= 200000000LL) {
        s->lastReopenNs = nowNs();
        LOGI("config changed (gen %d->%d) — reopening", s->gen, g_configGen);
        reopen(s);
        if (!s->stream) return -1;   // reopen failed (route gone); skip this buffer, retry next write
    }

    adaptBuffer(s);   // grow the buffer if we've been underrunning
    // Measurement heartbeat (~every 1000 writes ≈ 20s): current underruns + buffer vs capacity.
    if ((++s->writes % 1000) == 0)
        LOGI("hb: writes=%lld xruns=%d buf=%d cap=%d", (long long) s->writes,
             (int) AAudioStream_getXRunCount(s->stream), (int) s->curBufferSize, (int) s->capacity);
    aaudio_result_t n = AAudioStream_write(s->stream, buf, numFrames, WAIT_COMPLETION_TIMEOUT);
    if (n < 0) {
        // Route change (disconnect) or other stream error → reopen on the current device and retry once.
        // Throttle: if we reopened <200ms ago, drop this buffer instead of reopening again — otherwise a
        // device that stays disconnected would thrash close/open every write() and stall the aserver.
        int64_t now = nowNs();
        if (now - s->lastReopenNs < 200000000LL) return (jint) n;
        s->lastReopenNs = now;
        LOGW("AAudioStream_write err=%d — reopening", (int) n);
        if (reopen(s) == 0)
            n = AAudioStream_write(s->stream, buf, numFrames, WAIT_COMPLETION_TIMEOUT);
    }
    return (jint) n;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        s->started = 1;
        AAudioStream_requestStart(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        s->started = 0;
        AAudioStream_requestStop(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STOPPING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        s->started = 0;
        AAudioStream_requestPause(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_PAUSING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_flush(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (s && s->stream) {
        AAudioStream_requestFlush(s->stream);
        AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_FLUSHING, NULL, WAIT_COMPLETION_TIMEOUT);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj, jlong streamPtr) {
    (void) env; (void) obj;
    AlsaStream *s = (AlsaStream *) streamPtr;
    if (!s) return;
    if (s->stream) AAudioStream_close(s->stream);
    free(s);
}
