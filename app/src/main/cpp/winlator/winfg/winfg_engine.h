#pragma once
// ============================================================================
// winfg_engine — win-fg (Bannerlator's own frame generation) running inside
// the compositor, behind the same contract the LSFG engine uses:
//
//   prepare(w, h, format)            build/rebuild the chain for this size
//   plan(capacity)                   how many frames to generate this time
//   process(cmd, source, w, h, gens) take frame N in (becomes "curr")
//   generateInto(cmd, k, n, ...)     synthesise generated frame k of n
//
// Why this exists: as a guest-side Vulkan layer, win-fg's generated frames
// had to cross Wine -> DRI3 -> our X server -> the compositor, where the
// per-window latest-wins overwrite dropped them before they were ever
// presented - the HUD counted 2x, the panel got 1x. Here the chain runs in the
// compositor's own command stream and its frames are presented directly, on
// the same multi-present path that is device-proven for LSFG Native.
//
// Unlike LSFG this needs nothing from the user: the ten shaders are ours
// (MIT, see LICENSE.win-fg / NOTICE_FIDELITYFX_OPTICALFLOW.md) and embedded.
//
// The chain (framegen.cpp) is used unmodified. Its inputs are two sampled
// rgba8 views (prev, curr) and its output a GENERAL storage view; the guest
// layer transitions curr to SHADER_READ_ONLY before record() and the output
// UNDEFINED -> GENERAL, so we do exactly the same around the same call.
// ============================================================================
#include <cstdint>
#include <vulkan/vulkan.h>
#include "framegen.hpp"
#include "vk_dispatch.hpp"

struct VkTable;

namespace winfg {

// Hard ceiling on generated frames per source frame (2x..4x -> 1..3), matching
// the LSFG engine and the drawer's buttons.
constexpr uint32_t kMaxGenerations = 3;

class Engine {
public:
    Engine();
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool init(const VkTable& table, VkPhysicalDevice phys, VkDevice dev,
              uint32_t queueFamily, VkQueue queue);
    bool valid() const { return fg_.valid() && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    // model 3/4, perf preset 0..2, flow scale - the same knobs the drawer
    // already exposes for win-fg; they map straight onto winfg::Config.
    void configure(uint32_t multiplier, int model, int perfPreset, float flowScale);

    bool prepare(uint32_t width, uint32_t height, VkFormat format);

    // 0 until there is a previous frame to interpolate from.
    uint32_t plan(uint32_t capacity) const;

    // Copy the composited frame into the input ring as "curr" (the previous
    // one becomes "prev"). `source` must be in GENERAL.
    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);

    // Synthesise generated frame `generation` of `count` into the storage view.
    // The target must be in GENERAL (the composite ring keeps it there).
    void generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t count,
                      VkImage targetImage, VkImageView targetView,
                      uint32_t width, uint32_t height);

    void reset();

private:
    struct Slot { VkImage img = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE;
                  VkImageView view = VK_NULL_HANDLE; bool valid = false; };
    bool  makeSlot(Slot& s, uint32_t w, uint32_t h, VkFormat fmt);
    void  destroySlot(Slot& s);
    void  destroyRing();
    uint32_t memType(uint32_t bits, VkMemoryPropertyFlags props) const;

    DeviceDispatch   dd_{};
    InstanceDispatch id_{};
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice         dev_  = VK_NULL_HANDLE;
    uint32_t         qf_   = 0;
    VkQueue          queue_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memProps_{};

    FrameGen fg_;
    Config   cfg_;
    bool     unavailable_ = false;

    Slot     ring_[2];
    int      curr_ = 0;          // index of "curr" in ring_; prev is 1 - curr_
    uint32_t history_ = 0;       // frames copied so far (need 2)
    uint32_t gmSlot_ = 0;        // rotates through FrameGen::kGmSlots
    uint32_t builtW_ = 0, builtH_ = 0;
    VkFormat builtFmt_ = VK_FORMAT_UNDEFINED;
    uint64_t frames_ = 0;
};

} // namespace winfg
