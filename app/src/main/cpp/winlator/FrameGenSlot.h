#pragma once
// ============================================================================
// FrameGenSlot — engine-agnostic compositor-side frame-generation slot.
//
// Problem it solves: a guest-side frame-gen layer (win-fg today) generates an
// interpolated frame inside the guest and presents it as its OWN X11 Present /
// DRI3 pixmap. Each present becomes a DISTINCT AHardwareBuffer delivery to the
// host compositor (VulkanRendererContext::updateWindowContentAHB), but they are
// all keyed to the same window-content id. The event-driven compositor keeps a
// single latest-wins texture per window (texMap[id]), so a generated frame that
// arrives micro-seconds before the next real frame is OVERWRITTEN before the
// render thread ever presents it: the in-game HUD counts 2x but SurfaceFlinger
// posts the base rate (the generated frame never reaches the panel).
//
// The slot fixes this WITHOUT changing the guest layer: it de-coalesces the
// distinct AHB deliveries into a small bounded per-window queue and lets the
// existing FIFO present loop drain them one-per-vblank, so every distinct guest
// frame reaches its own QueuePresentKHR on its own refresh interval.
//
// Engine-agnostic: the slot is a PACER + a per-window PRESENTABLE-FRAME source.
//   * win-fg (this task): the presentable frames arrive pre-composited as AHBs;
//     the per-window delivery queue IS the producer (see FrameProducer docs).
//   * a native LSFG engine (future): the compositor would instead GENERATE frame
//     k of N by dispatching interpolation shaders into an acquired swapchain
//     image. That plugs in at the produce(k,N) seam documented below — do NOT
//     implement it here.
//
// Pacing algorithm shape follows the GameNative / lsfg-vk present pacer (the
// same lineage credited by PresentExtension's IdleNotify limiter); this is an
// original, self-contained implementation.
// ============================================================================

#include <cstdint>
#include <chrono>
#include <algorithm>
#include <cmath>

namespace framegen {

// Hard ceiling on presentable frames (base + generated) per source frame. Keeps
// the swapchain-image / queue footprint bounded for 2x..4x.
static constexpr int FGS_MAX_PRESENTABLE = 4;

using FgClock = std::chrono::steady_clock;

// ---------------------------------------------------------------------------
// Pacer: tracks the guest SOURCE rate (real presents delivered) against the
// compositor LOOP rate and reports how many frames may be shown per source
// interval without over-committing the panel (headroom).
// ---------------------------------------------------------------------------
class Pacer {
public:
    // multiplier: guest frame-gen factor (2..4). refreshHz: panel refresh (<=0 = unknown).
    void configure(int multiplier, float refreshHz) {
        multiplier_ = multiplier;
        refreshHz_  = refreshHz;
    }

    void reset() {
        haveSource_ = haveLoop_ = false;
        sourceInterval_ = loopInterval_ = 0.0f;
        sourceSamples_ = loopSamples_ = 0;
    }

    // One real guest frame was delivered.
    void onSourceFrame(FgClock::time_point now) {
        if (haveSource_) {
            const float dt = std::chrono::duration<float>(now - lastSource_).count();
            if (dt > 0.0f && dt < kDiscontinuity) {
                sourceInterval_ = ema(sourceInterval_, dt, kSmoothing);
                if (sourceSamples_ < kMinSamples) ++sourceSamples_;
            } else if (dt >= kDiscontinuity) {
                // Long gap (paused / stalled): restart tracking, don't skew the average.
                sourceSamples_ = 0; sourceInterval_ = 0.0f;
            }
        }
        haveSource_ = true;
        lastSource_ = now;
    }

    // One compositor present happened.
    void onLoopTick(FgClock::time_point now) {
        if (haveLoop_) {
            const float dt = std::chrono::duration<float>(now - lastLoop_).count();
            if (dt > 0.0f && dt < kDiscontinuity) {
                loopInterval_ = ema(loopInterval_, dt, kSmoothing);
                if (loopSamples_ < kMinSamples) ++loopSamples_;
            }
        }
        haveLoop_ = true;
        lastLoop_ = now;
    }

    // Max presentable frames (base + generated) this source interval, clamped to
    // [1, FGS_MAX_PRESENTABLE]. Returns 1 (= no generation shown, i.e. today's
    // behaviour) whenever the multiplier is off or the panel has no headroom for
    // an extra frame — so a low-refresh panel self-limits with zero added latency.
    int budget() const {
        if (multiplier_ < 2) return 1;
        int cap = std::min(multiplier_, FGS_MAX_PRESENTABLE);
        if (refreshHz_ > 0.0f && sourceInterval_ > 0.0f && sourceSamples_ >= kMinSamples) {
            // vblanks available per source frame = refresh * source_interval.
            const int headroom = (int)std::floor(refreshHz_ * sourceInterval_ + 0.5f);
            cap = std::min(cap, headroom);
        }
        return std::max(1, cap);
    }

    bool active() const { return multiplier_ >= 2; }
    float sourceRate() const { return sourceInterval_ > 0.0f ? 1.0f / sourceInterval_ : 0.0f; }
    float loopRate()   const { return loopInterval_   > 0.0f ? 1.0f / loopInterval_   : 0.0f; }
    float refreshHz()  const { return refreshHz_; }
    int   multiplier() const { return multiplier_; }

private:
    static constexpr float kSmoothing     = 0.15f;
    static constexpr float kDiscontinuity = 0.25f;   // >250 ms gap = stall/pause
    static constexpr int   kMinSamples    = 8;

    static float ema(float cur, float sample, float a) {
        return cur > 0.0f ? cur + (sample - cur) * a : sample;
    }

    int   multiplier_ = 0;
    float refreshHz_  = 0.0f;
    bool  haveSource_ = false, haveLoop_ = false;
    FgClock::time_point lastSource_{}, lastLoop_{};
    float sourceInterval_ = 0.0f, loopInterval_ = 0.0f;
    int   sourceSamples_ = 0, loopSamples_ = 0;
};

// ---------------------------------------------------------------------------
// FrameProducer — the seam a frame-gen ENGINE implements to feed the slot.
//
// The slot presents `budget()` frames per source interval, in temporal order.
// For each, it asks the producer to make frame `k` of `n` presentable in the
// target it is about to composite+present.
//
//   * win-fg producer (this task): NOT a subclass — the presentable frames are
//     already pre-composited images that arrive as distinct AHBs. The slot's
//     per-window delivery queue (VulkanRendererContext::frameGenQueue_) holds
//     them; the render loop pops one per present. produce() is implicit ("use
//     the k-th queued AHB").
//
//   * LSFG producer (future, DO NOT implement here): would subclass this and,
//     in produce(k, n), dispatch the interpolation compute chain to synthesise
//     frame k into the acquired swapchain image, then return true. The rest of
//     the slot (pacer, multi-vblank present cadence, resize/OUT_OF_DATE guard)
//     is reused unchanged.
// ---------------------------------------------------------------------------
struct FrameProducer {
    virtual ~FrameProducer() = default;
    // Make frame k (0-based, k < n) presentable. Return false to stop early.
    virtual bool produce(uint32_t k, uint32_t n) = 0;
};

} // namespace framegen
