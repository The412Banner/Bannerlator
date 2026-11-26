#include "wfg_engine.h"

#include <android/log.h>

#include "../VulkanRendererContext.h"   // VkTable
#include "vk_dispatch.h"
#include "vkr_dis.h"

#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "wfg-native", __VA_ARGS__)

VkDispatch wfg_vkd = {};

namespace wfg {

bool Engine::init(const VkTable& table, VkPhysicalDevice phys, VkDevice dev) {
#define X(n) wfg_vkd.n = table.n;
    WFG_VK_FUNCS(X)
#undef X
#define X(n) if (!wfg_vkd.n) { WLOGE("dispatch is missing vk" #n); return false; }
    WFG_VK_FUNCS(X)
#undef X
    dev_ = dev;
    waitIdle_ = table.DeviceWaitIdle;
    dis_ = vkr_dis_create(dev, phys);
    if (!dis_) {
        WLOGE("vkr_dis_create failed");
        return false;
    }
    return true;
}

Engine::~Engine() {
    if (!dis_) return;
    if (waitIdle_ && dev_) waitIdle_(dev_);
    vkr_dis_destroy(dis_);
}

void Engine::configure(uint32_t flowMinSide, uint32_t targetFps, float refreshHz) {
    if (dis_) vkr_dis_configure(dis_, flowMinSide, targetFps, refreshHz);
}

bool Engine::prepare(uint32_t width, uint32_t height, VkFormat format) {
    if (!dis_ || unavailable_ || !width || !height) return false;
    const VkrDisContentRect content = {0, 0, width, height};
    if (chainOk_ && !vkr_dis_needs_rebuild(dis_, width, height, format, content)) return true;
    if (waitIdle_ && dev_) waitIdle_(dev_);
    vkr_dis_forget_targets(dis_);
    chainOk_ = vkr_dis_prepare(dis_, width, height, format, content);
    if (!chainOk_) {
        WLOGE("chain build failed for %ux%u format %d", width, height, (int)format);
        unavailable_ = true;
    }
    source_ = VK_NULL_HANDLE;
    return chainOk_;
}

uint32_t Engine::plan(uint32_t capacity, uint64_t sourceFrames) {
    return chainOk_ ? vkr_dis_plan(dis_, capacity, sourceFrames) : 0;
}

void Engine::process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                     uint32_t generations) {
    if (!chainOk_) return;
    source_ = source;
    vkr_dis_process(dis_, cmd, source, width, height, generations);
}

void Engine::generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t targetIndex,
                          VkImage targetImage, VkImageView targetView,
                          uint32_t width, uint32_t height) {
    if (!chainOk_ || source_ == VK_NULL_HANDLE) return;
    vkr_dis_generate_into(dis_, cmd, generation, targetIndex, targetImage, targetView,
                          width, height, source_);
}

void Engine::forgetTargets() {
    if (dis_) vkr_dis_forget_targets(dis_);
}

} // namespace wfg
