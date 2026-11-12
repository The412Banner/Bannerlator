#pragma once
// WFG (WinNative/Winlator Frame Generator by qwertypower, DEVAR Entertainment LLC - MIT, see
// LICENSE.wfg) behind the same contract as the LSFG and win-fg engines: prepare / plan /
// process / generateInto on the compositor's own command stream. vkr_dis.c, vkr_dis.h and
// shaders/ are WinNative's files unmodified (WFG_UPSTREAM in CMakeLists.txt names the commit).
#include <cstdint>
#include <vulkan/vulkan.h>

struct VkTable;
struct VkrDis;

namespace wfg {

// Flow resolution: pixels on the frame's shorter side (WinNative's Fast / Balance).
constexpr uint32_t kMinSideFast = 180;
constexpr uint32_t kMinSideBalance = 252;

class Engine {
public:
    Engine() = default;
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool init(const VkTable& table, VkPhysicalDevice phys, VkDevice dev);
    bool valid() const { return dis_ != nullptr && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    // WFG paces itself: it generates up to targetFps (0 = the display rate), never past
    // refreshHz. The 2x/3x/4x multiplier is applied by the caller as the capacity of plan().
    void configure(uint32_t flowMinSide, uint32_t targetFps, float refreshHz);

    bool prepare(uint32_t width, uint32_t height, VkFormat format);
    uint32_t plan(uint32_t capacity, uint64_t sourceFrames);

    // `source` and the targets are in GENERAL, as for the other engines.
    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);
    void generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t targetIndex,
                      VkImage targetImage, VkImageView targetView,
                      uint32_t width, uint32_t height);
    void forgetTargets();

private:
    VkrDis*  dis_ = nullptr;
    VkDevice dev_ = VK_NULL_HANDLE;
    PFN_vkDeviceWaitIdle waitIdle_ = nullptr;
    VkImage  source_ = VK_NULL_HANDLE;
    bool     chainOk_ = false;
    bool     unavailable_ = false;
};

} // namespace wfg
