#pragma once
//
// FramePacer — GameScope-style frame-generation even pacer.
//
// Ported from libGameScopeV2.so @0x1b185c: an ABSOLUTE, drift-free deadline pacer with a coarse
// nanosleep followed by a sched_yield busy-spin, run ON the present thread immediately before the
// real present/commit, with NO downstream re-timer. The busy-spin is the load-bearing part — plain
// nanosleep's 1-4ms kernel slack randomly shoves a present across a 6.94ms vblank boundary, which is
// exactly the 1-vsync/4-vsync bunching we measured; spinning the last <0.5ms lands it on the grid.
//
// Two additions over the raw GameScope loop:
//   * SELF-CALIBRATION: the target interval is an EWMA (alpha≈0.1) of the TRUE per-frame arrival
//     interval, timestamped on entry BEFORE the spin. Because the FG layer presents mult× frames, the
//     mean arrival interval already equals 1/(real_rate*mult) — the even target — with no need to know
//     mult here. When a real FPS cap is set the caller seeds the EWMA from 1e9/(cap*mult) to skip
//     warmup jitter; uncapped, it converges within a few frames. (Caveat: once engaged the measured
//     interval reflects our own paced drain, so the seed/cap case is authoritative; the uncapped case
//     tracks rate CHANGES via the resync path rather than discovering a faster-than-drain true rate.)
//   * HEADROOM GUARD: pacing only helps when the real present rate has slack under the panel refresh.
//     If the target interval is already <= one vsync (no headroom) OR a resynced deadline lands in the
//     past (arrivals at/above target), the spin DISENGAGES and the frame passes straight through — so
//     uncapped high-fps games get zero added latency.
//
// Header-only: each present context/library owns its own instance. Only one host renderer is active
// per session, so exactly one instance ever paces. Mutex-guarded state so >1 present thread is safe
// (the createSwapchain path was observed on two threads); the sleep+spin runs outside the lock.
//
#include <atomic>
#include <mutex>
#include <cstdint>
#include <ctime>
#include <sched.h>
#include <android/log.h>

class FramePacer {
public:
    // enabled: FG-on + even-pace toggle. seedIntervalNs: 1e9/(cap*mult) when a cap is set, else 0
    // (warm up from measurement). vsyncNs: 1e9/panelHz (0 = unknown). Idempotent; re-arms + resyncs.
    void configure(bool enabled, int64_t seedIntervalNs, int64_t vsyncNs) {
        std::lock_guard<std::mutex> lk(mutex_);
        vsyncNs_ = vsyncNs > 0 ? vsyncNs : 0;
        if (!enabled) { armed_.store(false, std::memory_order_relaxed); return; }
        if (seedIntervalNs > 0) ewmaNs_ = seedIntervalNs;   // seed to avoid warmup jitter
        nextTargetNs_  = 0;                                  // resync the deadline
        lastArrivalNs_ = 0;
        armed_.store(true, std::memory_order_relaxed);
    }

    bool armed() const { return armed_.load(std::memory_order_relaxed); }

    // Call on the present thread immediately before the present/commit. Returns the deadline it paced
    // to (so callers can also set a consistent ASurfaceTransaction_setDesiredPresentTime hint), or 0
    // when it passed through / is disarmed. No-op cost when disarmed.
    int64_t waitForNextDeadline(const char* tag) {
        if (!armed_.load(std::memory_order_relaxed)) return 0;
        const int64_t entry = nowNs();      // arrival timestamp — sampled BEFORE the spin
        int64_t deadline = 0, interval = 0;
        bool engaged = false;
        {
            std::lock_guard<std::mutex> lk(mutex_);
            // 1) Self-calibrate: EWMA (alpha≈0.1, integer form) of the true arrival interval.
            if (lastArrivalNs_ != 0) {
                int64_t d = entry - lastArrivalNs_;
                if (d > 0) ewmaNs_ = (ewmaNs_ == 0) ? d : ewmaNs_ + (d - ewmaNs_) / 10;
            }
            lastArrivalNs_ = entry;
            interval = ewmaNs_;
            if (interval > 0) {
                if (interval < kMinIntervalNs) interval = kMinIntervalNs;  // reject outliers (>240fps)
                if (interval > kMaxIntervalNs) interval = kMaxIntervalNs;  // reject outliers (<10fps)
                // 2) Headroom guard: only pace when the target interval has slack over one vsync.
                if (vsyncNs_ <= 0 || interval > vsyncNs_) {
                    // Next deadline = previous deadline + interval (absolute, drift-free), where
                    // nextTargetNs_ holds the LAST deadline we paced to. The deadline must be one
                    // interval AHEAD (like GameScope seeds last_target then presents at last_target+
                    // interval) — the earlier code used the base itself, which equals `now` on the first
                    // frame, so `deadline > now` was never true and it never engaged. First frame / fell
                    // behind (deadline in the past after a pause or rate change) -> reschedule one
                    // interval out; raced >2 intervals ahead (measured interval a touch too large) ->
                    // clamp so added latency can't run away. Always engages when there's headroom.
                    int64_t target = (nextTargetNs_ == 0) ? (entry + interval)
                                                          : (nextTargetNs_ + interval);
                    if (target <= entry)                 target = entry + interval;    // behind -> resync
                    if (target > entry + 2 * interval)   target = entry + interval;    // ahead  -> clamp
                    nextTargetNs_ = target;
                    deadline = target;
                    engaged  = true;
                } else {
                    nextTargetNs_ = 0;                           // no headroom: pass through
                }
            }
        }
        int64_t after = entry;
        if (engaged) {
            const int64_t remain = deadline - nowNs();
            if (remain >= kSpinMarginNs) {                       // coarse nanosleep, leaving the margin
                const int64_t s = remain - kSpinMarginNs;
                struct timespec ts; ts.tv_sec = s / 1000000000LL; ts.tv_nsec = s % 1000000000LL;
                nanosleep(&ts, nullptr);
            }
            while ((after = nowNs()) < deadline) sched_yield();  // busy-spin the last <0.5ms to the grid
        }
        if ((logCtr_.fetch_add(1, std::memory_order_relaxed) % 60) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "BFGPace",
                "%s armed=1 engaged=%d interval=%lldus vsync=%lldus miss=%lldus",
                tag ? tag : "?", engaged ? 1 : 0, (long long)(interval / 1000),
                (long long)(vsyncNs_ / 1000), (long long)(engaged ? (after - deadline) / 1000 : 0));
        }
        return engaged ? deadline : 0;
    }

private:
    static int64_t nowNs() {
        struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
        return (int64_t) t.tv_sec * 1000000000LL + t.tv_nsec;
    }
    static constexpr int64_t kSpinMarginNs  = 500000;              // 500us busy-spin tail
    static constexpr int64_t kMinIntervalNs = 1000000000LL / 240;  // clamp: reject >240fps outliers
    static constexpr int64_t kMaxIntervalNs = 1000000000LL / 10;   // clamp: reject <10fps outliers

    std::atomic<bool>     armed_{false};
    std::mutex            mutex_;
    int64_t               ewmaNs_        = 0;
    int64_t               nextTargetNs_  = 0;
    int64_t               lastArrivalNs_ = 0;
    int64_t               vsyncNs_       = 0;
    std::atomic<uint32_t> logCtr_{0};
};
