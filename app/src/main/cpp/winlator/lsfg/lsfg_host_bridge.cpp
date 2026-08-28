// Host-side LSFG bridge implementation. Ported wiring around WinNative PR #697
// (GPL-3.0-or-later). Includes vk_dispatch.h + vkr_lsfg.h privately so their
// bare-`vk*` dispatch macros stay out of the compositor's translation unit.
#include "lsfg_host_bridge.h"

#include <android/log.h>
#include <atomic>

#include "vk_dispatch.h"
#include "vkr_lsfg.h"
#include "lsfg_dll.h"

#define LOG_TAG "LSFG-HOST"
#define BRIDGE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define BRIDGE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_dispatch_ready{false};

bool LsfgHostInitDispatch(PFN_vkGetInstanceProcAddr gipa, VkInstance instance) {
    if (g_dispatch_ready.load(std::memory_order_acquire)) return true;
    if (!gipa || instance == VK_NULL_HANDLE) return false;
    if (!vkd_init_from_gipa(gipa)) {
        BRIDGE_LOGW("vkd_init_from_gipa failed");
        return false;
    }
    if (!vkd_load_instance(instance)) {
        BRIDGE_LOGW("vkd_load_instance failed (missing core entry points)");
        return false;
    }
    if (!vkd.CreateComputePipelines || !vkd.CmdDispatch) {
        BRIDGE_LOGW("driver lacks compute dispatch entry points; frame gen unavailable");
        return false;
    }
    g_dispatch_ready.store(true, std::memory_order_release);
    BRIDGE_LOGI("dispatch table populated (compute pipelines ready)");
    return true;
}

int LsfgHostBuildCache(const char* dll_path, const char* cache_path, bool prefer_fp16) {
    return (int)lsfg_build_cache(dll_path, cache_path, prefer_fp16);
}

VkrLsfg* LsfgHostCreate(VkDevice device, VkPhysicalDevice physical_device, const char* cache_path) {
    if (!g_dispatch_ready.load(std::memory_order_acquire)) {
        BRIDGE_LOGW("LsfgHostCreate before dispatch init");
        return nullptr;
    }
    return vkr_lsfg_create(device, physical_device, cache_path);
}

void LsfgHostDestroy(VkrLsfg* lsfg) { vkr_lsfg_destroy(lsfg); }

void LsfgHostConfigure(VkrLsfg* lsfg, uint32_t multiplier, uint32_t target_rate, float flow_scale,
                       float refresh_rate) {
    vkr_lsfg_configure(lsfg, multiplier, target_rate, flow_scale, refresh_rate);
}

void LsfgHostSetGuestExtent(VkrLsfg* lsfg, uint32_t width, uint32_t height) {
    vkr_lsfg_set_guest_extent(lsfg, width, height);
}

void LsfgHostSetRefreshRate(VkrLsfg* lsfg, float refresh_rate) {
    vkr_lsfg_set_refresh_rate(lsfg, refresh_rate);
}

bool LsfgHostNeedsRebuild(const VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format) {
    return vkr_lsfg_needs_rebuild(lsfg, width, height, format);
}

bool LsfgHostPrepare(VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format) {
    return vkr_lsfg_prepare(lsfg, width, height, format);
}

uint32_t LsfgHostPlan(VkrLsfg* lsfg, uint32_t capacity, uint64_t source_frames) {
    return vkr_lsfg_plan(lsfg, capacity, source_frames);
}

void LsfgHostProcess(VkrLsfg* lsfg, VkCommandBuffer cmd, VkImage source, uint32_t width,
                     uint32_t height, uint32_t generations) {
    vkr_lsfg_process(lsfg, cmd, source, width, height, generations);
}

void LsfgHostGenerateInto(VkrLsfg* lsfg, VkCommandBuffer cmd, uint32_t generation,
                          uint32_t target_index, VkImage target_image, VkImageView target_view,
                          uint32_t width, uint32_t height) {
    vkr_lsfg_generate_into(lsfg, cmd, generation, target_index, target_image, target_view, width,
                           height);
}

void LsfgHostForgetTargets(VkrLsfg* lsfg) { vkr_lsfg_forget_targets(lsfg); }

void LsfgHostReset(VkrLsfg* lsfg) { vkr_lsfg_reset(lsfg); }
