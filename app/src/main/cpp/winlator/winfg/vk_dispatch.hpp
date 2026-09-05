// win-fg — minimal Vulkan layer dispatch tables.
// Structure follows the public Khronos loader/layer interface and the
// renderdoc "Vulkan layer guide" skeleton (learning references, Apache-2.0 /
// CC-BY). No code from bionic-fg / lsfg / GameScope.
#pragma once
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <cstring>

namespace winfg {

// The loader sets the first pointer-sized word of a dispatchable handle to the
// dispatch table pointer; layers key their own maps on that value so that all
// handles sharing a chain resolve to the same table.
static inline void* dispatch_key(void* handle) { return *reinterpret_cast<void**>(handle); }

// Instance-level functions we call from the layer.
struct InstanceDispatch {
    PFN_vkGetInstanceProcAddr          GetInstanceProcAddr = nullptr;
    PFN_vkDestroyInstance              DestroyInstance = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties = nullptr;
    // Device-context logging (affected-device fingerprint). Properties2 is core 1.1;
    // may be null on a 1.0 instance — always null-check before calling.
    PFN_vkGetPhysicalDeviceProperties  GetPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 GetPhysicalDeviceProperties2 = nullptr;
    // Surface caps — used to clamp the extra-image-headroom request to the surface's
    // maxImageCount so the bump never exceeds what the driver can grant.
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
};

// Device-level functions we call. Populated by walking vkGetDeviceProcAddr on
// the next layer down at device-create time.
struct DeviceDispatch {
    PFN_vkGetDeviceProcAddr            GetDeviceProcAddr = nullptr;
    PFN_vkDestroyDevice                DestroyDevice = nullptr;
    PFN_vkGetDeviceQueue               GetDeviceQueue = nullptr;
    PFN_vkQueueSubmit                  QueueSubmit = nullptr;
    // Synchronization2 submit path (core 1.3 + KHR alias). DXVK may use either the
    // legacy QueueSubmit or QueueSubmit2/2KHR; both are hooked for the guest-submit
    // diagnostic. May be null if the device did not enable them — always null-check.
    PFN_vkQueueSubmit2                 QueueSubmit2 = nullptr;
    PFN_vkQueueSubmit2KHR              QueueSubmit2KHR = nullptr;
    PFN_vkQueueWaitIdle                QueueWaitIdle = nullptr;
    PFN_vkDeviceWaitIdle               DeviceWaitIdle = nullptr;

    // swapchain / present
    PFN_vkCreateSwapchainKHR           CreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR          DestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR        GetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR          AcquireNextImageKHR = nullptr;
    // Alternate acquire entry (VK_KHR_swapchain 1.1+ / device-group). DXVK may use
    // either AcquireNextImageKHR or AcquireNextImage2KHR — both hooked for the
    // guest-acquire diagnostic. May be null — always null-check.
    PFN_vkAcquireNextImage2KHR         AcquireNextImage2KHR = nullptr;
    PFN_vkQueuePresentKHR              QueuePresentKHR = nullptr;

    // resources
    PFN_vkCreateImage                  CreateImage = nullptr;
    PFN_vkDestroyImage                 DestroyImage = nullptr;
    PFN_vkCreateImageView              CreateImageView = nullptr;
    PFN_vkDestroyImageView             DestroyImageView = nullptr;
    PFN_vkAllocateMemory               AllocateMemory = nullptr;
    PFN_vkFreeMemory                   FreeMemory = nullptr;
    PFN_vkBindImageMemory              BindImageMemory = nullptr;
    PFN_vkGetImageMemoryRequirements   GetImageMemoryRequirements = nullptr;
    PFN_vkCreateBuffer                 CreateBuffer = nullptr;
    PFN_vkDestroyBuffer                DestroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements  GetBufferMemoryRequirements = nullptr;
    PFN_vkBindBufferMemory             BindBufferMemory = nullptr;
    PFN_vkMapMemory                    MapMemory = nullptr;
    PFN_vkUnmapMemory                  UnmapMemory = nullptr;
    PFN_vkCreateSampler                CreateSampler = nullptr;
    PFN_vkDestroySampler               DestroySampler = nullptr;

    // pipeline / descriptors
    PFN_vkCreateShaderModule           CreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule          DestroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout    CreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout   DestroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout         CreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout        DestroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines       CreateComputePipelines = nullptr;
    PFN_vkDestroyPipeline              DestroyPipeline = nullptr;
    PFN_vkCreateDescriptorPool         CreateDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool        DestroyDescriptorPool = nullptr;
    // Frees every set in the pool at once — used by FrameGen::configure() to reclaim
    // the old scratch descriptor sets when a perf_preset change forces a live rebuild
    // (otherwise repeated preset switches would exhaust the pool). May be null in
    // theory; the rebuild path null-checks before calling.
    PFN_vkResetDescriptorPool          ResetDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets       AllocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets         UpdateDescriptorSets = nullptr;

    // command recording
    PFN_vkCreateCommandPool            CreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool           DestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers       AllocateCommandBuffers = nullptr;
    PFN_vkFreeCommandBuffers           FreeCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer           BeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer             EndCommandBuffer = nullptr;
    PFN_vkCmdBindPipeline              CmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets        CmdBindDescriptorSets = nullptr;
    PFN_vkCmdDispatch                  CmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier           CmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage                 CmdCopyImage = nullptr;
    PFN_vkCmdCopyImageToBuffer         CmdCopyImageToBuffer = nullptr;  // capture readback
    PFN_vkCmdBlitImage                 CmdBlitImage = nullptr;
    PFN_vkCmdClearColorImage           CmdClearColorImage = nullptr;
    PFN_vkCreateFence                  CreateFence = nullptr;
    PFN_vkDestroyFence                 DestroyFence = nullptr;
    PFN_vkWaitForFences                WaitForFences = nullptr;
    PFN_vkResetFences                  ResetFences = nullptr;
    PFN_vkCreateSemaphore              CreateSemaphore = nullptr;
    PFN_vkDestroySemaphore             DestroySemaphore = nullptr;
};

template <typename PFN>
static inline void load(PFN& slot, PFN_vkGetDeviceProcAddr gdpa, VkDevice dev, const char* name) {
    slot = reinterpret_cast<PFN>(gdpa(dev, name));
}

} // namespace winfg
