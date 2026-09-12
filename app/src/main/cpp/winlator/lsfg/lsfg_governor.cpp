// See lsfg_governor.h.

#include "lsfg_governor.h"

#include <algorithm>

#include <android/log.h>
#include <dlfcn.h>

#define GOV_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LsfgGovernor", __VA_ARGS__)

namespace lsfg {
namespace {

// A probe has to run long enough to survive a single unlucky frame, and the
// baseline long enough to be a fair comparison.
constexpr float kBaselineSeconds = 2.0f;
constexpr float kProbeSeconds    = 2.0f;
constexpr uint32_t kMinSamples   = 30;

// Keep the extra generation only if it actually paid off.
constexpr float kOutputGainRequired = 1.15f;
constexpr float kSourceFloor        = 0.70f;

constexpr float kBackoffSeconds[] = {5.0f, 15.0f, 30.0f, 60.0f};
constexpr uint32_t kBackoffSteps  = 4;

constexpr float kThermalPollSeconds = 2.0f;
constexpr float kThermalDecaySeconds = 1.0f;

// ADEVICE_THERMAL_STATUS_* from <android/thermal.h>, which is API 30. Resolved
// by dlsym so the build keeps working below that and on devices that do not
// ship the service; -1 simply means "no thermal signal", never "hot".
// Android reports a STATUS, not a temperature - NONE 0, LIGHT 1, MODERATE 2,
// SEVERE 3, CRITICAL 4, EMERGENCY 5, SHUTDOWN 6 - so there is no degree value
// here to raise. The equivalent of "let it run hotter" is to act on a higher
// status, which is what these now do.
//
// A handheld under a real 3D load sits at SEVERE routinely; blocking there
// made the governor refuse on a device that was working exactly as intended.
// It now only stops GROWING at SEVERE and only gives frames back at CRITICAL,
// leaving the device's own thermal management as the authority.
constexpr int kThermalBlock    = 4;  // CRITICAL: give generations back
constexpr int kThermalNoGrowth = 3;  // SEVERE: keep what we have, stop probing

using PFN_AThermal_acquireManager = void* (*)();
using PFN_AThermal_getCurrentThermalStatus = int (*)(void*);

void* thermalManager() {
    static void* manager = [] () -> void* {
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return nullptr;
        auto acquire = (PFN_AThermal_acquireManager)dlsym(lib, "AThermal_acquireManager");
        return acquire ? acquire() : nullptr;
    }();
    return manager;
}

int readThermalStatus() {
    void* manager = thermalManager();
    if (!manager) return -1;
    static auto get = [] () -> PFN_AThermal_getCurrentThermalStatus {
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        return lib ? (PFN_AThermal_getCurrentThermalStatus)
                     dlsym(lib, "AThermal_getCurrentThermalStatus") : nullptr;
    }();
    return get ? get(manager) : -1;
}

float phaseSeconds(std::chrono::steady_clock::time_point a,
                   std::chrono::steady_clock::time_point b) {
    return std::chrono::duration<float>(b - a).count();
}

} // namespace

void ProbeGovernor::configure(uint32_t maxGenerations) {
    if (maxGenerations_ == maxGenerations) return;
    // Raising the ceiling must NOT throw away what we already know works -
    // going 2x -> 4x used to reset the accepted level to zero and restart the
    // whole baseline/probe cycle, so a user raising the multiplier got FEWER
    // frames. Only clamp down to a ceiling that has actually been lowered.
    maxGenerations_ = maxGenerations;
    if (accepted_ > maxGenerations_) accepted_ = maxGenerations_;
    // Re-measure against the new ceiling, but keep the accepted level.
    phase_ = Phase::Baseline;
    havePhaseStart_ = false;
    sourceAccum_ = loopAccum_ = 0.0f;
    samples_ = 0;
    haveBaseline_ = false;
}

void ProbeGovernor::reset() {
    accepted_ = 0;
    phase_ = Phase::Baseline;
    havePhaseStart_ = false;
    sourceAccum_ = loopAccum_ = 0.0f;
    samples_ = 0;
    baselineSource_ = baselineLoop_ = 0.0f;
    haveBaseline_ = false;
    backoffStep_ = 0;
}

void ProbeGovernor::enterBaseline(Clock::time_point now) {
    phase_ = Phase::Baseline;
    phaseStart_ = now;
    havePhaseStart_ = true;
    sourceAccum_ = loopAccum_ = 0.0f;
    samples_ = 0;
}

void ProbeGovernor::enterBackoff(Clock::time_point now) {
    phase_ = Phase::Backoff;
    phaseStart_ = now;
    havePhaseStart_ = true;
    sourceAccum_ = loopAccum_ = 0.0f;
    samples_ = 0;
    if (backoffStep_ < kBackoffSteps - 1) backoffStep_++;
}

void ProbeGovernor::pollThermal(Clock::time_point now) {
    if (haveThermalPoll_ && phaseSeconds(lastThermalPoll_, now) < kThermalPollSeconds) return;
    lastThermalPoll_ = now;
    haveThermalPoll_ = true;
    thermal_ = readThermalStatus();
}

uint32_t ProbeGovernor::cap(uint32_t requested, float sourceRate, float loopRate) {
    if (maxGenerations_ == 0 || requested == 0) return 0;

    const Clock::time_point now = Clock::now();
    if (!havePhaseStart_) enterBaseline(now);
    pollThermal(now);

    // Thermal override: at SEVERE and above we stop asking for more and give
    // generations back, but SLOWLY.
    //
    // This runs once per source frame - about forty times a second - so the
    // previous version's unconditional accepted_-- was not "give one back", it
    // was "slam to zero in three frames", and its unconditional enterBackoff()
    // reset the backoff clock every single call, so the backoff could never
    // expire and the governor could never measure or recover. One decay per
    // second, and no churn at all once there is nothing left to give back.
    thermalBlocked_ = thermal_ >= kThermalBlock;
    if (thermal_ >= kThermalBlock) {
        // Logged, sparingly. This is a REFUSAL TO EVEN MEASURE, and it was
        // silent: on device it looked identical to "measured and declined".
        if ((refusalLog_++ % 600u) == 0u)
            GOV_LOGI("thermal status %d - not probing (accepted=%u)", thermal_, accepted_);
        const bool decayDue = !haveThermalDecay_
            || phaseSeconds(lastThermalDecay_, now) >= kThermalDecaySeconds;
        if (accepted_ > 0 && decayDue) {
            GOV_LOGI("thermal status %d - dropping to %u generations", thermal_, accepted_ - 1);
            accepted_--;
            lastThermalDecay_ = now;
            haveThermalDecay_ = true;
            enterBackoff(now);
        }
        return std::min(requested, accepted_);
    }
    haveThermalDecay_ = false;

    if (sourceRate > 0.0f && loopRate > 0.0f) {
        sourceAccum_ += sourceRate;
        loopAccum_   += loopRate;
        samples_++;
    }

    const float elapsed = phaseSeconds(phaseStart_, now);

    switch (phase_) {
    case Phase::Baseline:
        if (elapsed >= kBaselineSeconds && samples_ >= kMinSamples) {
            baselineSource_ = sourceAccum_ / (float)samples_;
            baselineLoop_   = loopAccum_   / (float)samples_;
            haveBaseline_   = true;

            const bool roomToGrow = accepted_ < maxGenerations_ && requested > accepted_;
            const bool thermallyFree = thermal_ < kThermalNoGrowth || thermal_ < 0;
            if (roomToGrow && thermallyFree) {
                phase_ = Phase::Probing;
                phaseStart_ = now;
                sourceAccum_ = loopAccum_ = 0.0f;
                samples_ = 0;
            } else {
                // Also previously silent. "Baseline completed but we did not
                // probe" is a decision worth seeing in a log.
                if ((refusalLog_++ % 600u) == 0u)
                    GOV_LOGI("not probing: accepted=%u max=%u requested=%u thermal=%d",
                             accepted_, maxGenerations_, requested, thermal_);
                enterBaseline(now);   // nothing to try; measure again later
            }
        }
        break;

    case Phase::Probing:
        if (elapsed >= kProbeSeconds && samples_ >= kMinSamples && haveBaseline_) {
            const float probeSource = sourceAccum_ / (float)samples_;
            const float probeLoop   = loopAccum_   / (float)samples_;

            const bool outputImproved =
                baselineLoop_ > 0.0f && probeLoop >= baselineLoop_ * kOutputGainRequired;
            const bool sourceHeld =
                baselineSource_ <= 0.0f || probeSource >= baselineSource_ * kSourceFloor;

            if (outputImproved && sourceHeld) {
                accepted_ = std::min(accepted_ + 1, maxGenerations_);
                backoffStep_ = 0;
                GOV_LOGI("probe kept: %u generations (output %.1f -> %.1f, source %.1f -> %.1f)",
                         accepted_, (double)baselineLoop_, (double)probeLoop,
                         (double)baselineSource_, (double)probeSource);
                enterBaseline(now);
            } else {
                GOV_LOGI("probe rejected at %u+1 (output %.1f -> %.1f, source %.1f -> %.1f) - "
                         "backing off %.0fs", accepted_,
                         (double)baselineLoop_, (double)probeLoop,
                         (double)baselineSource_, (double)probeSource,
                         (double)kBackoffSeconds[backoffStep_]);
                enterBackoff(now);
            }
        }
        break;

    case Phase::Backoff:
        if (elapsed >= kBackoffSeconds[backoffStep_]) enterBaseline(now);
        break;
    }

    // While probing we allow one more than is currently accepted; otherwise the
    // accepted level is the ceiling, and the pacer's own answer still applies.
    const uint32_t allowed = phase_ == Phase::Probing
        ? std::min(accepted_ + 1, maxGenerations_)
        : accepted_;
    return std::min(requested, allowed);
}

} // namespace lsfg
