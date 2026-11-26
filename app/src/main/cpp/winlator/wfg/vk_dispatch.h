// Bannerlator's stand-in for WinNative's winlator/vk/vk_dispatch.h, so vkr_dis.c builds
// unmodified. Only the entry points WFG calls. wfg_engine.cpp fills the table from the
// renderer's VkTable before the engine is created; each library that links WFG has its own copy.
#pragma once

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

#define WFG_VK_FUNCS(X)                                                                      \
    X(GetPhysicalDeviceProperties) X(GetPhysicalDeviceMemoryProperties)                      \
    X(GetPhysicalDeviceFormatProperties) X(GetDeviceProcAddr)                                \
    X(AllocateDescriptorSets) X(AllocateMemory) X(BindImageMemory) X(CmdBindDescriptorSets)  \
    X(CmdBindPipeline) X(CmdBlitImage) X(CmdDispatch) X(CmdPipelineBarrier)                  \
    X(CmdPushConstants) X(CreateComputePipelines) X(CreateDescriptorPool)                    \
    X(CreateDescriptorSetLayout) X(CreateImage) X(CreateImageView) X(CreatePipelineLayout)   \
    X(CreateSampler) X(CreateShaderModule) X(DestroyDescriptorPool)                          \
    X(DestroyDescriptorSetLayout) X(DestroyImage) X(DestroyImageView) X(DestroyPipeline)     \
    X(DestroyPipelineLayout) X(DestroySampler) X(DestroyShaderModule) X(FreeMemory)          \
    X(GetImageMemoryRequirements) X(UpdateDescriptorSets)

typedef struct VkDispatch {
#define X(n) PFN_vk##n n;
    WFG_VK_FUNCS(X)
#undef X
} VkDispatch;

#define vkd wfg_vkd
extern VkDispatch wfg_vkd __attribute__((visibility("hidden")));

#ifdef __cplusplus
}
#endif
