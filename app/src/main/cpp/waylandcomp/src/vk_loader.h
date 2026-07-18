#ifndef VK_LOADER_H
#define VK_LOADER_H
/*
 * Loads Turnip via adrenotools (the same path the app uses for the guest renderer)
 * and resolves every Vulkan entry point through its vkGetInstanceProcAddr — NOT the
 * process-default system Adreno driver, which lacks VK_EXT_image_drm_format_modifier /
 * dmabuf import. All compositor Vulkan calls go through g_vk.* .
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define VK_GLOBAL_FUNCS(X) X(CreateInstance)

#define VK_INSTANCE_FUNCS(X) \
    X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceSurfaceSupportKHR) \
    X(GetPhysicalDeviceSurfaceCapabilitiesKHR) X(GetPhysicalDeviceSurfaceFormatsKHR) \
    X(CreateAndroidSurfaceKHR) X(DestroySurfaceKHR) X(CreateDevice) \
    X(GetDeviceProcAddr) X(EnumerateDeviceExtensionProperties) \
    X(GetPhysicalDeviceMemoryProperties)

#define VK_DEVICE_FUNCS(X) \
    X(GetDeviceQueue) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) \
    X(CreateCommandPool) X(AllocateCommandBuffers) X(CreateSemaphore) X(CreateFence) \
    X(DeviceWaitIdle) X(CreateImage) X(GetImageMemoryRequirements) X(AllocateMemory) \
    X(BindImageMemory) X(DestroyImage) X(FreeMemory) X(AcquireNextImageKHR) \
    X(ResetCommandBuffer) X(BeginCommandBuffer) X(CmdPipelineBarrier) X(CmdBlitImage) \
    X(EndCommandBuffer) X(ResetFences) X(QueueSubmit) X(QueuePresentKHR) X(WaitForFences) \
    X(QueueWaitIdle) X(GetMemoryFdPropertiesKHR) \
    X(GetImageSubresourceLayout) X(MapMemory) X(UnmapMemory)

struct vk_api {
#define X(n) PFN_vk##n n;
    VK_GLOBAL_FUNCS(X) VK_INSTANCE_FUNCS(X) VK_DEVICE_FUNCS(X)
#undef X
};
extern struct vk_api g_vk;

/* Open Turnip via adrenotools (falls back to system libvulkan if any arg is NULL)
 * and load global entry points. Returns 0 on success. */
int vk_loader_open(const char *driver_path, const char *library_name,
                   const char *native_lib_dir);
void vk_loader_load_instance(VkInstance instance);
void vk_loader_load_device(VkDevice device);

#endif
