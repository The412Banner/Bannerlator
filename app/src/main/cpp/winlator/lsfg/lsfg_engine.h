#pragma once
// ============================================================================
// lsfg_engine — the compositor's handle on native LSFG frame generation.
//
// Owns the shader modules, the interpolation chain and the pacer, and exposes
// the small contract the render loop needs:
//
//   prepare(w, h, format)                 build/rebuild the chain for this size
//   plan(capacity, sourceFrames)          how many frames to generate this time
//   process(cmd, source, w, h, gens)      take frame N in, run the shared chain
//   generateInto(cmd, g, i, img, view)    synthesise generated frame g
//
// Ordering matters and is not obvious: interpolation produces frames that
// belong BETWEEN N-1 and N, so the generated frames are presented FIRST and
// the real frame N is held back one slot. That one output interval is the
// unavoidable cost of interpolating rather than extrapolating; no placement of
// this code changes it.
//
// Ported from WinNative's vkr_lsfg.cpp (GPL-3.0-or-later), LSFG port credited
// to Camille LaVey / the Eden Emulator Project, following upstream lsfg-vk.
// ============================================================================

#include <cstdint>
#include <memory>
#include <string>

#include <vulkan/vulkan.h>

#include "lsfg_common.hpp"
#include "lsfg_governor.h"
#include "lsfg_pacer.hpp"

namespace lsfg {

class LsfgShaders;
class LsfgChain;

// Hard ceiling on generated frames per source frame (2x..4x -> 1..3).
constexpr uint32_t kMaxGenerations = 3;

class Engine {
public:
    // Both the constructor and destructor are out-of-line ON PURPOSE. The
    // shader set and the chain are held by unique_ptr to forward-declared
    // types, and an inline constructor would make the compiler emit the
    // exception-cleanup path for those members at every construction site -
    // which needs the complete types there. Keeping both here confines that to
    // lsfg_engine.cpp, where the types are complete.
    Engine();
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    // Build the shader modules from a cache produced by lsfg_dll::buildCache.
    // Returns false if the cache does not yield all 25 modules.
    bool init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& cachePath);

    bool valid() const { return shaders_ != nullptr && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    void configure(uint32_t multiplier, uint32_t targetRate, float flowScale, float refreshRate);
    void setRefreshRate(float refreshRate);
    void setGuestExtent(uint32_t width, uint32_t height);

    bool needsRebuild(uint32_t width, uint32_t height, VkFormat format) const;
    bool prepare(uint32_t width, uint32_t height, VkFormat format);

    // How many frames to generate for this source frame. Returns 0 until the
    // history slots are valid and the rates have settled, so the first frames
    // after a start or a resize are never interpolated from garbage.
    uint32_t plan(uint32_t capacity, uint64_t sourceFrames);

    // Take the just-composited frame as LSFG input and run the shared part of
    // the chain (everything except `generate`). `source` must be in GENERAL.
    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);

    // Synthesise generated frame `generation` into the given storage image.
    void generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t targetIndex,
                      VkImage targetImage, VkImageView targetView,
                      uint32_t width, uint32_t height);

    // What the probe governor currently trusts, and the latest thermal
    // reading (-1 = no signal). Surfaced for the HUD and diagnostics.
    uint32_t acceptedGenerations() const { return governor_.accepted(); }
    int      thermalStatus() const { return governor_.thermalStatus(); }

    void forgetTargets();
    void reset();

private:
    float effectiveFlowScale(uint32_t width) const;

    Device      device_{};
    std::string cachePath_;
    std::unique_ptr<LsfgShaders> shaders_;
    std::unique_ptr<LsfgChain>   chain_;
    LsfgPacer      pacer_;
    ProbeGovernor  governor_;
    LsfgPlan    plan_{};

    VkExtent2D builtExtent_{};
    VkExtent2D peakGuestExtent_{};
    VkFormat   builtFormat_{VK_FORMAT_UNDEFINED};
    float      builtFlowScale_{};
    float      flowScale_{1.0f};

    uint64_t frameCount_{};
    uint64_t lastCount_{};
    size_t   lastGenerations_{};
    uint64_t planCalls_{};
    uint32_t warmStreak_{};
    bool     warm_{};
    bool     generating_{};
    bool     unavailable_{};
};

} // namespace lsfg
