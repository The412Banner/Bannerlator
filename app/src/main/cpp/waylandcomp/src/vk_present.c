/* Android-surface render backend — see vk_present.h. */
#define _POSIX_C_SOURCE 200809L
#include "vk_present.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define TAG "BannerWayland"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define MOD_INVALID 0x00ffffffffffffffULL

static ANativeWindow *g_window;
static int g_inited;        /* 0 = not yet, 1 = ok, -1 = failed */
static VkInstance g_inst;
static VkPhysicalDevice g_pd;
static VkDevice g_dev;
static VkQueue g_queue;
static uint32_t g_qfam;
static VkSurfaceKHR g_surface;
static VkSwapchainKHR g_swapchain;
static VkImage *g_images;
static uint32_t g_nimg;
static VkExtent2D g_extent;
static VkCommandPool g_pool;
static VkCommandBuffer g_cmd;
static VkSemaphore g_acq, g_rnd;
static VkFence g_fence;
static PFN_vkGetMemoryFdPropertiesKHR p_getMemFdProps;

static VkFormat drm_to_vk(uint32_t drm) { return VK_FORMAT_B8G8R8A8_UNORM; }

void vk_present_set_window(ANativeWindow *window) {
    g_window = window;
    if (!window && g_inited == 1) {
        vkDeviceWaitIdle(g_dev);
        /* Leave device up; drop the swapchain so the next window re-inits it. */
        if (g_swapchain) vkDestroySwapchainKHR(g_dev, g_swapchain, NULL);
        g_swapchain = VK_NULL_HANDLE;
        if (g_surface) vkDestroySurfaceKHR(g_inst, g_surface, NULL);
        g_surface = VK_NULL_HANDLE;
        g_inited = 0; /* re-init on next window+commit */
    }
}

static int ensure_init(void) {
    if (g_inited != 0) return g_inited == 1 ? 0 : -1;
    if (!g_window) return -1;

    const char *inst_exts[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                               VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "banner-wayland-present",
                             .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                .pApplicationInfo = &app,
                                .enabledExtensionCount = 2,
                                .ppEnabledExtensionNames = inst_exts};
    if (vkCreateInstance(&ici, NULL, &g_inst) != VK_SUCCESS) {
        LOGE("present: vkCreateInstance failed"); g_inited = -1; return -1;
    }
    VkAndroidSurfaceCreateInfoKHR aci = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = g_window};
    if (vkCreateAndroidSurfaceKHR(g_inst, &aci, NULL, &g_surface) != VK_SUCCESS) {
        LOGE("present: create android surface failed"); g_inited = -1; return -1;
    }

    uint32_t npd = 0;
    vkEnumeratePhysicalDevices(g_inst, &npd, NULL);
    if (!npd) { g_inited = -1; return -1; }
    VkPhysicalDevice pds[8]; if (npd > 8) npd = 8;
    vkEnumeratePhysicalDevices(g_inst, &npd, pds);
    g_pd = VK_NULL_HANDLE;
    for (uint32_t i = 0; i < npd && g_pd == VK_NULL_HANDLE; i++) {
        uint32_t nq = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, NULL);
        VkQueueFamilyProperties qs[16]; if (nq > 16) nq = 16;
        vkGetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, qs);
        for (uint32_t q = 0; q < nq; q++) {
            VkBool32 sup = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(pds[i], q, g_surface, &sup);
            if ((qs[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) && sup) {
                g_pd = pds[i]; g_qfam = q; break;
            }
        }
    }
    if (g_pd == VK_NULL_HANDLE) { LOGE("present: no gfx+present queue"); g_inited = -1; return -1; }

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                   .queueFamilyIndex = g_qfam, .queueCount = 1,
                                   .pQueuePriorities = &prio};
    const char *dev_exts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                              "VK_KHR_external_memory_fd",
                              "VK_EXT_external_memory_dma_buf",
                              "VK_EXT_image_drm_format_modifier",
                              "VK_KHR_image_format_list"};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                              .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci,
                              .enabledExtensionCount = 5, .ppEnabledExtensionNames = dev_exts};
    if (vkCreateDevice(g_pd, &dci, NULL, &g_dev) != VK_SUCCESS) {
        LOGE("present: vkCreateDevice failed"); g_inited = -1; return -1;
    }
    vkGetDeviceQueue(g_dev, g_qfam, 0, &g_queue);
    p_getMemFdProps = (PFN_vkGetMemoryFdPropertiesKHR)
        vkGetDeviceProcAddr(g_dev, "vkGetMemoryFdPropertiesKHR");

    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g_pd, g_surface, &caps);
    uint32_t nfmt = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g_pd, g_surface, &nfmt, NULL);
    VkSurfaceFormatKHR fmts[32]; if (nfmt > 32) nfmt = 32;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g_pd, g_surface, &nfmt, fmts);
    VkSurfaceFormatKHR chosen = fmts[0];

    g_extent = caps.currentExtent;
    if (g_extent.width == 0xFFFFFFFF) {
        g_extent.width = ANativeWindow_getWidth(g_window);
        g_extent.height = ANativeWindow_getHeight(g_window);
    }
    uint32_t want = caps.minImageCount + 1;
    if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;

    VkSwapchainCreateInfoKHR sci = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = g_surface, .minImageCount = want,
        .imageFormat = chosen.format, .imageColorSpace = chosen.colorSpace,
        .imageExtent = g_extent, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE};
    if (vkCreateSwapchainKHR(g_dev, &sci, NULL, &g_swapchain) != VK_SUCCESS) {
        LOGE("present: vkCreateSwapchainKHR failed"); g_inited = -1; return -1;
    }
    vkGetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, NULL);
    free(g_images);
    g_images = calloc(g_nimg, sizeof(VkImage));
    vkGetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, g_images);

    VkCommandPoolCreateInfo pci = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                   .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                   .queueFamilyIndex = g_qfam};
    vkCreateCommandPool(g_dev, &pci, NULL, &g_pool);
    VkCommandBufferAllocateInfo cai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                       .commandPool = g_pool,
                                       .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                       .commandBufferCount = 1};
    vkAllocateCommandBuffers(g_dev, &cai, &g_cmd);
    VkSemaphoreCreateInfo semci = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    vkCreateSemaphore(g_dev, &semci, NULL, &g_acq);
    vkCreateSemaphore(g_dev, &semci, NULL, &g_rnd);
    VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vkCreateFence(g_dev, &fci, NULL, &g_fence);

    g_inited = 1;
    LOGI("present: swapchain up %ux%u, %u images", g_extent.width, g_extent.height, g_nimg);
    return 0;
}

/* Import the dmabuf as a VkImage bound to the imported fd (LINEAR modifier). */
static int import_image(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                        uint32_t stride, uint32_t offset, VkImage *out_img,
                        VkDeviceMemory *out_mem) {
    VkSubresourceLayout plane = {.offset = offset, .rowPitch = stride};
    VkImageDrmFormatModifierExplicitCreateInfoEXT modInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
        .drmFormatModifier = modifier, .drmFormatModifierPlaneCount = 1,
        .pPlaneLayouts = &plane};
    VkExternalMemoryImageCreateInfo extImg = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .pNext = &modInfo,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &extImg,
        .imageType = VK_IMAGE_TYPE_2D, .format = drm_to_vk(drm_format),
        .extent = {w, h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    if (vkCreateImage(g_dev, &ici, NULL, out_img) != VK_SUCCESS) return -1;

    int dupfd = dup(fd);
    uint32_t allowed = 0xffffffff;
    if (p_getMemFdProps) {
        VkMemoryFdPropertiesKHR fp = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (p_getMemFdProps(g_dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                            dupfd, &fp) == VK_SUCCESS)
            allowed = fp.memoryTypeBits;
    }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(g_dev, *out_img, &req);
    uint32_t bits = req.memoryTypeBits & allowed;
    int idx = -1;
    for (int i = 0; i < 32; i++) if (bits & (1u << i)) { idx = i; break; }
    if (idx < 0) { vkDestroyImage(g_dev, *out_img, NULL); close(dupfd); return -1; }

    VkImportMemoryFdInfoKHR imp = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
                                   .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                   .fd = dupfd};
    VkMemoryDedicatedAllocateInfo ded = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                                         .pNext = &imp, .image = *out_img};
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &ded,
                                .allocationSize = req.size, .memoryTypeIndex = (uint32_t)idx};
    if (vkAllocateMemory(g_dev, &mai, NULL, out_mem) != VK_SUCCESS) {
        vkDestroyImage(g_dev, *out_img, NULL); close(dupfd); return -1;
    }
    if (vkBindImageMemory(g_dev, *out_img, *out_mem, 0) != VK_SUCCESS) {
        vkFreeMemory(g_dev, *out_mem, NULL); vkDestroyImage(g_dev, *out_img, NULL); return -1;
    }
    return 0;
}

int vk_present_commit_dmabuf(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                             uint32_t stride, uint32_t offset) {
    if (!g_window || modifier == MOD_INVALID) return -1;
    if (ensure_init() != 0) return -1;

    VkImage src; VkDeviceMemory srcMem;
    if (import_image(fd, drm_format, modifier, w, h, stride, offset, &src, &srcMem) != 0)
        return -1;

    uint32_t img = 0;
    VkResult ar = vkAcquireNextImageKHR(g_dev, g_swapchain, UINT64_MAX, g_acq,
                                        VK_NULL_HANDLE, &img);
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        vkFreeMemory(g_dev, srcMem, NULL); vkDestroyImage(g_dev, src, NULL); return -1;
    }

    vkResetCommandBuffer(g_cmd, 0);
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vkBeginCommandBuffer(g_cmd, &bi);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    /* imported (foreign) -> TRANSFER_SRC on our queue */
    VkImageMemoryBarrier b_src = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT, .dstQueueFamilyIndex = g_qfam,
        .image = src, .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
    /* swapchain -> TRANSFER_DST */
    VkImageMemoryBarrier b_dst = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = g_images[img], .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    VkImageMemoryBarrier pre[2] = {b_src, b_dst};
    vkCmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, NULL, 0, NULL, 2, pre);

    VkImageBlit blit = {
        .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .srcOffsets = {{0, 0, 0}, {w, h, 1}},
        .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .dstOffsets = {{0, 0, 0}, {(int)g_extent.width, (int)g_extent.height, 1}}};
    vkCmdBlitImage(g_cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   g_images[img], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

    VkImageMemoryBarrier b_present = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = g_images[img], .subresourceRange = range, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    vkCmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, NULL, 0, NULL, 1, &b_present);
    vkEndCommandBuffer(g_cmd);

    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                       .waitSemaphoreCount = 1, .pWaitSemaphores = &g_acq, .pWaitDstStageMask = &wait,
                       .commandBufferCount = 1, .pCommandBuffers = &g_cmd,
                       .signalSemaphoreCount = 1, .pSignalSemaphores = &g_rnd};
    vkResetFences(g_dev, 1, &g_fence);
    vkQueueSubmit(g_queue, 1, &si, g_fence);

    VkPresentInfoKHR pi = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                           .waitSemaphoreCount = 1, .pWaitSemaphores = &g_rnd,
                           .swapchainCount = 1, .pSwapchains = &g_swapchain, .pImageIndices = &img};
    vkQueuePresentKHR(g_queue, &pi);
    vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
    vkQueueWaitIdle(g_queue);

    vkFreeMemory(g_dev, srcMem, NULL);
    vkDestroyImage(g_dev, src, NULL);
    return 0;
}
