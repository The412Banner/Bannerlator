#pragma once
// ============================================================================
// lsfg_governor — decides whether an extra generated frame is actually WORTH
// it on this device, right now.
//
// The ported pacer answers "how many frames fit in the panel's budget". That
// is necessary but not sufficient on a handheld: the LSFG chain competes with
// the game for the same GPU, so a naive fixed 3x on a GPU-bound title lowers
// the real frame rate by more than the generated frames add. The user sees a
// higher HUD number and a worse-looking game.
//
// So the multiplier is treated as a ceiling to earn, not a setting to obey.
// The governor starts at 0 generations, periodically PROBES one more, and
// keeps it only if the probe measurably paid off:
//
//   * total output rate must improve by at least 1.15x, and
//   * the real (source) frame rate must not collapse below 0.70x baseline.
//
// A failed probe reverts immediately and backs off 5s, 15s, 30s, then 60s, so
// a device that cannot afford another generation stops being asked.
//
// Thermals are the second input a phone needs and a desktop does not.
// Sustained frame generation is a thermal decision as much as a performance
// one, so the governor gives back generations as the device heats and refuses
// to probe while it is throttling.
// ============================================================================

#include <chrono>
#include <cstdint>

namespace lsfg {

class ProbeGovernor {
public:
    void configure(uint32_t maxGenerations);

    // Cap the pacer's answer. Called once per source frame with the measured
    // rates; returns the number of generations actually permitted.
    uint32_t cap(uint32_t requested, float sourceRate, float loopRate);

    void reset();

    uint32_t accepted() const { return accepted_; }
    bool     probing()  const { return phase_ == Phase::Probing; }
    // Whether the chain's input ring needs to be kept fed. Seeding it is a
    // full-resolution copy every frame, so it is skipped during the long idle
    // stretches - a backoff, or a thermal block - and resumed during Baseline,
    // which gives it a couple of seconds to fill before a probe can start.
    bool     wantsHistory() const {
        return !thermalBlocked_ && phase_ != Phase::Backoff;
    }
    // Latest thermal reading, 0 (none) .. 6 (shutdown); -1 when unavailable.
    int      thermalStatus() const { return thermal_; }

private:
    using Clock = std::chrono::steady_clock;

    enum class Phase { Baseline, Probing, Backoff };

    void enterBaseline(Clock::time_point now);
    void enterBackoff(Clock::time_point now);
    void pollThermal(Clock::time_point now);

    uint32_t maxGenerations_ = 0;
    uint32_t accepted_       = 0;
    Phase    phase_          = Phase::Baseline;

    Clock::time_point phaseStart_{};
    bool  havePhaseStart_ = false;

    // Rolling means over the current phase window.
    float  sourceAccum_ = 0.0f, loopAccum_ = 0.0f;
    uint32_t samples_   = 0;

    // What the accepted level achieves, measured during Baseline.
    float baselineSource_ = 0.0f, baselineLoop_ = 0.0f;
    bool  haveBaseline_   = false;

    uint32_t backoffStep_ = 0;

    int  thermal_ = -1;
    Clock::time_point lastThermalDecay_{};
    bool haveThermalDecay_ = false;
    bool thermalBlocked_ = false;
    uint32_t refusalLog_ = 0;
    Clock::time_point lastThermalPoll_{};
    bool haveThermalPoll_ = false;
};

} // namespace lsfg
