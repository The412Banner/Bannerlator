"""Host test for drawing new frames on arrival (compositor.c: arrivals_draw, on_arrival_idle,
schedule_render, on_vsync). The four functions are taken from the real source and compiled with
stand-ins for the rest of the compositor. CI does not run it; run it by hand:

    python3 app/src/main/cpp/waylandcomp/tests/test_draw_on_arrival.py
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'src' / 'compositor.c'


def extract_function(source, name):
    match = re.search(r'^static (?:void|int) ' + re.escape(name) + r'\([^)]*\) \{', source, re.M)
    if not match:
        raise AssertionError(f'{name} not found in compositor.c')
    depth = 0
    for i in range(match.start(), len(source)):
        if source[i] == '{':
            depth += 1
        elif source[i] == '}':
            depth -= 1
            if depth == 0:
                return source[match.start():i + 1] + '\n'
    raise AssertionError(f'{name} has no end')


PREAMBLE = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>

struct wl_event_source { int unused; };
static struct wl_event_source idle_source, timer_source;
static void (*idle_func)(void *);
static int idles_added, timers_armed, renders, window_requests, effects_changes, framegen;
static int64_t clock_ns;
static int g_display_storage;
static void *g_display = &g_display_storage;
static struct wl_event_source *g_render_idle, *g_fallback_timer;
static int g_fallback_armed, g_dirty;
static int64_t g_last_vsync_ns, g_refresh_ns = 16666667;
volatile int g_draw_on_arrival = 1;
volatile int g_fps_limit;
static int64_t g_tick_ns, g_drawn_tick_ns, g_dirty_since_ns, g_hold_missed_ns;
static int g_tick_held;
#define HOLD_RETRY_NS 1000000000LL
static struct { unsigned ticks, ticks_late_2ms, held; int64_t late_ns, late_max_ns;
                unsigned drawn, on_arrival; int64_t wait_ns, wait_max_ns; } g_pace;
static struct { unsigned ticks; } g_perf;
static int64_t now_ns(void) { return clock_ns; }
static void *wl_display_get_event_loop(void *display) { return display; }
static struct wl_event_source *wl_event_loop_add_idle(void *loop, void (*func)(void *), void *data) {
    idles_added++; idle_func = func; return &idle_source;
}
static struct wl_event_source *wl_event_loop_add_timer(void *loop, int (*func)(void *), void *data) { return &timer_source; }
static int wl_event_source_timer_update(struct wl_event_source *source, int ms) { timers_armed++; return 0; }
static int vkp_framegen_active(void) { return framegen; }
static int frames_waited;
static int frames_awaited(void) { return frames_waited; }
static int vkp_apply_window_request(void) { return window_requests; }
static int vkp_effects_sync(void) { return effects_changes; }
static void retire_frames(int wait_all) {}
/* What the real render_scene does with the pacing state (asserted against the source below). */
static void render_scene(void) {
    renders++;
    if (g_dirty_since_ns) { g_pace.drawn++; g_dirty_since_ns = 0; }
    g_drawn_tick_ns = g_tick_ns; g_tick_held = 0; g_dirty = 0;
}
static int on_fallback_timer(void *data) { return 0; }
'''

CHECKS = r'''
static void run_idle(void) {
    void (*func)(void *) = idle_func;
    idle_func = NULL;
    if (func) func(NULL);
}

static void tick(int64_t t) { clock_ns = t; on_vsync(t); }
static void tick_late(int64_t t, int64_t late) { clock_ns = t + late; on_vsync(t); }
static void arrive(int64_t after_ns) { clock_ns += after_ns; schedule_render(); }

int main(void) {
    const int64_t refresh = 6944444, ms = 1000000;
    int64_t t = 1000000000LL;
    int r0;
    unsigned a0;
    g_refresh_ns = refresh;
    tick(t);
    assert(renders == 0);

    /* a new frame is drawn when it arrives */
    arrive(2 * ms);
    assert(idles_added == 1 && renders == 0);
    run_idle();
    assert(renders == 1 && g_pace.on_arrival == 1 && g_render_idle == NULL);

    /* a second frame in the same refresh waits for the next arrival, which is drawn when it arrives */
    arrive(1 * ms);
    assert(idles_added == 1);
    tick(t += refresh);
    assert(renders == 1 && g_tick_held == 1 && g_pace.held == 1);
    arrive(2 * ms);
    assert(idles_added == 2);
    run_idle();
    assert(renders == 2 && g_pace.on_arrival == 2 && g_tick_held == 0);

    /* a fast game (a frame every 0.2 ms) gets exactly one scene per refresh, every one on arrival */
    r0 = renders; a0 = g_pace.on_arrival;
    for (int i = 0; i < 20; i++) {
        tick(t += refresh);
        for (int f = 0; f < 34; f++) { arrive(ms / 5); run_idle(); }
    }
    assert(renders - r0 == 20 && g_pace.on_arrival - a0 == 20);

    /* a frame no other frame follows is drawn at the tick after next */
    tick(t += refresh);
    arrive(1 * ms);
    run_idle();
    r0 = renders;
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == r0);
    tick(t += refresh);
    assert(renders == r0 + 1);

    /* after that the tick draws every frame for a second, then holds one again */
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == r0 + 2 && g_tick_held == 0);
    t += HOLD_RETRY_NS;
    tick(t);
    arrive(1 * ms);
    run_idle();
    arrive(1 * ms);
    tick(t += refresh);
    assert(g_tick_held == 1);

    /* a client that draws again only after its frame callback (sent when the scene is drawn) keeps one
       frame per refresh: two held frames in 200 refreshes */
    arrive(2 * ms);
    run_idle();
    r0 = renders; a0 = g_pace.on_arrival;
    {
        int seen = renders - 1;
        for (int i = 0; i < 200; i++) {
            if (renders != seen) { seen = renders; arrive(1 * ms); run_idle(); }
            tick(t += refresh);
        }
    }
    assert(renders - r0 >= 197 && g_pace.on_arrival == a0);

    /* a window or effect change at the tick draws the waiting frame now */
    t += HOLD_RETRY_NS;
    tick(t);
    arrive(1 * ms);
    run_idle();
    r0 = renders;
    arrive(1 * ms);
    window_requests = 1;
    tick(t += refresh);
    window_requests = 0;
    assert(renders == r0 + 1 && g_tick_held == 0);
    arrive(1 * ms);
    effects_changes = 1;
    tick(t += refresh);
    effects_changes = 0;
    assert(renders == r0 + 2 && g_tick_held == 0);

    /* a draw queued before the tick still draws the frame once */
    tick(t += refresh);
    r0 = renders; a0 = g_pace.on_arrival;
    arrive(1 * ms);
    tick(t += refresh);
    assert(renders == r0);
    run_idle();
    assert(renders == r0 + 1 && g_pace.on_arrival == a0 + 1);
    tick(t += refresh);
    assert(renders == r0 + 1);

    /* a client waiting for its frame callback (a FIFO swapchain) is never held: each refresh draws its frame */
    tick(t += refresh);
    arrive(1 * ms);
    run_idle();
    frames_waited = 1;
    r0 = renders;
    for (int i = 0; i < 50; i++) {
        arrive(1 * ms);
        tick(t += refresh);
        assert(g_tick_held == 0);
    }
    assert(renders - r0 == 50);
    frames_waited = 0;

    /* an FPS limit, frame generation and the switch off keep drawing on the tick, nothing held */
    const int idles = idles_added;
    r0 = renders;
    for (int c = 0; c < 3; c++) {
        tick(t += refresh);
        if (c == 0) g_fps_limit = 60;
        if (c == 1) framegen = 1;
        if (c == 2) g_draw_on_arrival = 0;
        arrive(1 * ms);
        assert(idles_added == idles);
        tick(t += refresh);
        assert(g_tick_held == 0);
        g_fps_limit = 0; framegen = 0; g_draw_on_arrival = 1;
        assert(renders == r0 + c + 1);
    }

    /* a tick with no frame time still counts as a refresh of its own */
    clock_ns = t += refresh;
    on_vsync(0);
    assert(g_tick_ns == t);
    arrive(1 * ms);
    run_idle();
    assert(g_drawn_tick_ns == t);

    /* lateness: a tick handled 3 ms after its frame time counts as over 2 ms late */
    {
        unsigned late0 = g_pace.ticks_late_2ms;
        tick_late(t += refresh, 3 * ms);
        assert(g_pace.ticks_late_2ms == late0 + 1 && g_pace.late_max_ns >= 3 * ms);
    }

    /* without screen ticks the fallback timer still draws */
    clock_ns += 200000000LL;
    arrive(1 * ms);
    assert(idles_added == idles + 1 && g_fallback_armed == 1 && timers_armed == 1);
    puts("PASS");
    return 0;
}
'''


@unittest.skipUnless(sys.platform.startswith('linux'), 'needs a Linux host compiler')
class DrawOnArrivalTest(unittest.TestCase):
    def test_a_new_frame_is_drawn_on_arrival_at_most_once_per_refresh(self):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            self.skipTest('a host C compiler is required')
        source = SOURCE.read_text()
        render = extract_function(source, 'render_scene')
        for line in ('g_drawn_tick_ns = g_tick_ns;', 'g_tick_held = 0;', 'g_dirty_since_ns = 0;'):
            self.assertIn(line, render)
        functions = ('arrivals_draw', 'on_arrival_idle', 'schedule_render', 'on_vsync')
        harness = PREAMBLE + '\n'.join(extract_function(source, name) for name in functions) + CHECKS
        with tempfile.TemporaryDirectory(prefix='bannerlator-draw-on-arrival-') as folder:
            path = Path(folder)
            (path / 'arrival.c').write_text(harness)
            binary = path / 'arrival-test'
            build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                    '-Wno-unused-parameter', '-Wno-unused-function', '-O2',
                                    str(path / 'arrival.c'), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('PASS', result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
