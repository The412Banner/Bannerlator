// Host-side LSFG bridge — the compositor-facing API for the ported WinNative
// PR #697 frame-generation chain. Deliberately does NOT include vk_dispatch.h:
// that header ends with `#define vkCreateImage vkd.CreateImage` style macros for
// the whole Vulkan API, which must never leak into the compositor translation
// unit (it drives Vulkan through its own VkTable). This header exposes only the
// thin `LsfgHost*` C entry points; the implementation (lsfg_host_bridge.cpp)
// includes vk_dispatch.h + vkr_lsfg.h privately.
#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LSFG_HOST_MAX_GENERATIONS 3u
#define LSFG_HOST_MAX_TARGETS 7u

typedef struct VkrLsfg VkrLsfg;  // opaque

// Populate the chain's private `vkd` dispatch table from the compositor's own
// loader handle + instance. Idempotent — safe to call once per device create.
bool LsfgHostInitDispatch(PFN_vkGetInstanceProcAddr gipa, VkInstance instance);

// Extract + translate the 25 LSFG shaders from Lossless.dll into a shader cache.
// Returns the LsfgStatus (0 == OK). Pure CPU work; does not touch Vulkan.
int LsfgHostBuildCache(const char* dll_path, const char* cache_path, bool prefer_fp16);

// Create/destroy the frame generator from a shader cache. Returns NULL if the
// cache is missing/invalid — caller must treat that as "frame gen off".
VkrLsfg* LsfgHostCreate(VkDevice device, VkPhysicalDevice physical_device, const char* cache_path);
void LsfgHostDestroy(VkrLsfg* lsfg);

void LsfgHostConfigure(VkrLsfg* lsfg, uint32_t multiplier, uint32_t target_rate, float flow_scale,
                       float refresh_rate);
void LsfgHostSetGuestExtent(VkrLsfg* lsfg, uint32_t width, uint32_t height);
void LsfgHostSetRefreshRate(VkrLsfg* lsfg, float refresh_rate);

bool LsfgHostNeedsRebuild(const VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format);
bool LsfgHostPrepare(VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format);

// Pacer decision: how many frames to generate this real frame (0 during warm-up
// / cold / when the pacer backs off). capacity = free swapchain images this frame.
uint32_t LsfgHostPlan(VkrLsfg* lsfg, uint32_t capacity, uint64_t source_frames);

// Copy the just-composited frame N (must be in VK_IMAGE_LAYOUT_GENERAL) into the
// input ring and dispatch the shared part of the chain.
void LsfgHostProcess(VkrLsfg* lsfg, VkCommandBuffer cmd, VkImage source, uint32_t width,
                     uint32_t height, uint32_t generations);

// Warp generated frame `generation` into a storage-capable target image/view.
void LsfgHostGenerateInto(VkrLsfg* lsfg, VkCommandBuffer cmd, uint32_t generation,
                          uint32_t target_index, VkImage target_image, VkImageView target_view,
                          uint32_t width, uint32_t height);

void LsfgHostForgetTargets(VkrLsfg* lsfg);
void LsfgHostReset(VkrLsfg* lsfg);

#ifdef __cplusplus
}
#endif
