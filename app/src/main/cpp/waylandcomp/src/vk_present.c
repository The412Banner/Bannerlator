/* Android-surface render backend — see vk_present.h. Uses Turnip via vk_loader
 * (g_vk.*), not the process-default system Adreno driver. */
#define _POSIX_C_SOURCE 200809L
#include "vk_present.h"
#include "vk_loader.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "BannerWayland"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define MOD_INVALID 0x00ffffffffffffffULL

static ANativeWindow *g_window;
static char *g_driver_path, *g_library_name, *g_native_lib_dir;
static int g_inited; /* 0 = not yet, 1 = ok, -1 = failed */
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
static int g_first_frame_done; /* one-shot: fire banner_on_first_frame() on first present */

/* Implemented in waylandcomp_jni.c — notifies Java (dismiss launch overlay). */
extern void banner_on_first_frame(void);

static VkFormat drm_to_vk(uint32_t drm) { (void)drm; return VK_FORMAT_B8G8R8A8_UNORM; }

void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir) {
    free(g_driver_path); free(g_library_name); free(g_native_lib_dir);
    g_driver_path = driver_path ? strdup(driver_path) : NULL;
    g_library_name = library_name ? strdup(library_name) : NULL;
    g_native_lib_dir = native_lib_dir ? strdup(native_lib_dir) : NULL;
}

void vk_present_set_window(ANativeWindow *window) {
    g_window = window;
    if (!window && g_inited == 1) {
        g_vk.DeviceWaitIdle(g_dev);
        if (g_swapchain) g_vk.DestroySwapchainKHR(g_dev, g_swapchain, NULL);
        g_swapchain = VK_NULL_HANDLE;
        if (g_surface) g_vk.DestroySurfaceKHR(g_inst, g_surface, NULL);
        g_surface = VK_NULL_HANDLE;
        g_inited = 0; /* re-init swapchain on next window+commit */
    }
}

static int has_ext(VkExtensionProperties *e, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

static int ensure_init(void) {
    if (g_inited != 0) return g_inited == 1 ? 0 : -1;
    if (!g_window) return -1;

    /* Load Turnip (adrenotools) and its entry points — NOT the system driver. */
    if (vk_loader_open(g_driver_path, g_library_name, g_native_lib_dir) != 0) {
        LOGE("present: vk_loader_open failed"); g_inited = -1; return -1;
    }

    const char *inst_exts[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                               VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "banner-wayland-present",
                             .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                .pApplicationInfo = &app,
                                .enabledExtensionCount = 2,
                                .ppEnabledExtensionNames = inst_exts};
    if (g_vk.CreateInstance(&ici, NULL, &g_inst) != VK_SUCCESS) {
        LOGE("present: vkCreateInstance failed"); g_inited = -1; return -1;
    }
    vk_loader_load_instance(g_inst);

    VkAndroidSurfaceCreateInfoKHR aci = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = g_window};
    if (g_vk.CreateAndroidSurfaceKHR(g_inst, &aci, NULL, &g_surface) != VK_SUCCESS) {
        LOGE("present: create android surface failed"); g_inited = -1; return -1;
    }

    uint32_t npd = 0;
    g_vk.EnumeratePhysicalDevices(g_inst, &npd, NULL);
    if (!npd) { LOGE("present: no physical devices"); g_inited = -1; return -1; }
    VkPhysicalDevice pds[8]; if (npd > 8) npd = 8;
    g_vk.EnumeratePhysicalDevices(g_inst, &npd, pds);
    g_pd = VK_NULL_HANDLE;
    for (uint32_t i = 0; i < npd && g_pd == VK_NULL_HANDLE; i++) {
        uint32_t nq = 0;
        g_vk.GetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, NULL);
        VkQueueFamilyProperties qs[16]; if (nq > 16) nq = 16;
        g_vk.GetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, qs);
        for (uint32_t q = 0; q < nq; q++) {
            VkBool32 sup = VK_FALSE;
            g_vk.GetPhysicalDeviceSurfaceSupportKHR(pds[i], q, g_surface, &sup);
            if ((qs[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) && sup) { g_pd = pds[i]; g_qfam = q; break; }
        }
    }
    if (g_pd == VK_NULL_HANDLE) { LOGE("present: no gfx+present queue"); g_inited = -1; return -1; }
    {
        VkPhysicalDeviceProperties props;
        g_vk.GetPhysicalDeviceProperties(g_pd, &props);
        LOGI("present: GPU '%s'", props.deviceName);
    }

    /* Verify the dmabuf-import extensions are present, and log any that are missing. */
    const char *dev_exts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME, "VK_KHR_external_memory_fd",
                              "VK_EXT_external_memory_dma_buf", "VK_EXT_image_drm_format_modifier",
                              "VK_KHR_image_format_list"};
    uint32_t ne = 0;
    g_vk.EnumerateDeviceExtensionProperties(g_pd, NULL, &ne, NULL);
    VkExtensionProperties *exts = calloc(ne, sizeof(*exts));
    g_vk.EnumerateDeviceExtensionProperties(g_pd, NULL, &ne, exts);
    for (unsigned i = 0; i < 5; i++)
        if (!has_ext(exts, ne, dev_exts[i]))
            LOGE("present: driver MISSING %s (dmabuf import will fail)", dev_exts[i]);
    free(exts);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                   .queueFamilyIndex = g_qfam, .queueCount = 1, .pQueuePriorities = &prio};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                              .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci,
                              .enabledExtensionCount = 5, .ppEnabledExtensionNames = dev_exts};
    if (g_vk.CreateDevice(g_pd, &dci, NULL, &g_dev) != VK_SUCCESS) {
        LOGE("present: vkCreateDevice failed"); g_inited = -1; return -1;
    }
    vk_loader_load_device(g_dev);
    g_vk.GetDeviceQueue(g_dev, g_qfam, 0, &g_queue);

    VkSurfaceCapabilitiesKHR caps;
    g_vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(g_pd, g_surface, &caps);
    uint32_t nfmt = 0;
    g_vk.GetPhysicalDeviceSurfaceFormatsKHR(g_pd, g_surface, &nfmt, NULL);
    VkSurfaceFormatKHR fmts[32]; if (nfmt > 32) nfmt = 32;
    g_vk.GetPhysicalDeviceSurfaceFormatsKHR(g_pd, g_surface, &nfmt, fmts);
    VkSurfaceFormatKHR chosen = fmts[0];

    g_extent = caps.currentExtent;
    if (g_extent.width == 0xFFFFFFFF) {
        g_extent.width = ANativeWindow_getWidth(g_window);
        g_extent.height = ANativeWindow_getHeight(g_window);
    }
    uint32_t want = caps.minImageCount + 1;
    if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;

    /* Use IDENTITY preTransform when the surface supports it. Setting preTransform =
     * currentTransform tells the presentation engine our content is ALREADY pre-rotated by
     * that amount — but our blit doesn't rotate, so on a device whose surface reports a 90°
     * currentTransform the display then rotates our upright frame 90° (game shows sideways).
     * IDENTITY = "don't rotate what I present", which is what we want. */
    VkSurfaceTransformFlagBitsKHR pretrans =
        (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;

    VkSwapchainCreateInfoKHR sci = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = g_surface,
        .minImageCount = want, .imageFormat = chosen.format, .imageColorSpace = chosen.colorSpace,
        .imageExtent = g_extent, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE, .preTransform = pretrans,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE};
    if (g_vk.CreateSwapchainKHR(g_dev, &sci, NULL, &g_swapchain) != VK_SUCCESS) {
        LOGE("present: vkCreateSwapchainKHR failed"); g_inited = -1; return -1;
    }
    g_vk.GetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, NULL);
    free(g_images);
    g_images = calloc(g_nimg, sizeof(VkImage));
    g_vk.GetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, g_images);

    VkCommandPoolCreateInfo pci = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                   .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                   .queueFamilyIndex = g_qfam};
    g_vk.CreateCommandPool(g_dev, &pci, NULL, &g_pool);
    VkCommandBufferAllocateInfo cai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                       .commandPool = g_pool,
                                       .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    g_vk.AllocateCommandBuffers(g_dev, &cai, &g_cmd);
    VkSemaphoreCreateInfo semci = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    g_vk.CreateSemaphore(g_dev, &semci, NULL, &g_acq);
    g_vk.CreateSemaphore(g_dev, &semci, NULL, &g_rnd);
    VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    g_vk.CreateFence(g_dev, &fci, NULL, &g_fence);

    g_inited = 1;
    LOGI("present: swapchain up %ux%u, %u images", g_extent.width, g_extent.height, g_nimg);
    return 0;
}

static int import_image(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                        uint32_t stride, uint32_t offset, VkImage *out_img, VkDeviceMemory *out_mem) {
    VkSubresourceLayout plane = {.offset = offset, .rowPitch = stride};
    VkImageDrmFormatModifierExplicitCreateInfoEXT modInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
        .drmFormatModifier = modifier, .drmFormatModifierPlaneCount = 1, .pPlaneLayouts = &plane};
    VkExternalMemoryImageCreateInfo extImg = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .pNext = &modInfo,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &extImg,
        .imageType = VK_IMAGE_TYPE_2D, .format = drm_to_vk(drm_format), .extent = {w, h, 1},
        .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT, .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    if (g_vk.CreateImage(g_dev, &ici, NULL, out_img) != VK_SUCCESS) return -1;

    int dupfd = dup(fd);
    uint32_t allowed = 0xffffffff;
    if (g_vk.GetMemoryFdPropertiesKHR) {
        VkMemoryFdPropertiesKHR fp = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (g_vk.GetMemoryFdPropertiesKHR(g_dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                          dupfd, &fp) == VK_SUCCESS)
            allowed = fp.memoryTypeBits;
    }
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, *out_img, &req);
    uint32_t bits = req.memoryTypeBits & allowed;
    int idx = -1;
    for (int i = 0; i < 32; i++) if (bits & (1u << i)) { idx = i; break; }
    if (idx < 0) { g_vk.DestroyImage(g_dev, *out_img, NULL); close(dupfd); return -1; }

    VkImportMemoryFdInfoKHR imp = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
                                   .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                   .fd = dupfd};
    VkMemoryDedicatedAllocateInfo ded = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                                         .pNext = &imp, .image = *out_img};
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &ded,
                                .allocationSize = req.size, .memoryTypeIndex = (uint32_t)idx};
    if (g_vk.AllocateMemory(g_dev, &mai, NULL, out_mem) != VK_SUCCESS) {
        g_vk.DestroyImage(g_dev, *out_img, NULL); close(dupfd); return -1;
    }
    if (g_vk.BindImageMemory(g_dev, *out_img, *out_mem, 0) != VK_SUCCESS) {
        g_vk.FreeMemory(g_dev, *out_mem, NULL); g_vk.DestroyImage(g_dev, *out_img, NULL); return -1;
    }
    return 0;
}

int vk_present_commit_dmabuf(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                             uint32_t stride, uint32_t offset) {
    if (!g_window || modifier == MOD_INVALID) return -1;
    if (ensure_init() != 0) return -1;

    VkImage src; VkDeviceMemory srcMem;
    if (import_image(fd, drm_format, modifier, w, h, stride, offset, &src, &srcMem) != 0) {
        LOGE("present: dmabuf import failed"); return -1;
    }

    uint32_t img = 0;
    VkResult ar = g_vk.AcquireNextImageKHR(g_dev, g_swapchain, UINT64_MAX, g_acq, VK_NULL_HANDLE, &img);
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        g_vk.FreeMemory(g_dev, srcMem, NULL); g_vk.DestroyImage(g_dev, src, NULL); return -1;
    }

    g_vk.ResetCommandBuffer(g_cmd, 0);
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    g_vk.BeginCommandBuffer(g_cmd, &bi);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier b_src = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT, .dstQueueFamilyIndex = g_qfam,
        .image = src, .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
    VkImageMemoryBarrier b_dst = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = g_images[img], .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    VkImageMemoryBarrier pre[2] = {b_src, b_dst};
    g_vk.CmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            0, 0, NULL, 0, NULL, 2, pre);

    VkImageBlit blit = {.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                        .srcOffsets = {{0, 0, 0}, {w, h, 1}},
                        .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                        .dstOffsets = {{0, 0, 0}, {(int)g_extent.width, (int)g_extent.height, 1}}};
    g_vk.CmdBlitImage(g_cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g_images[img],
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

    VkImageMemoryBarrier b_present = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = g_images[img],
        .subresourceRange = range, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    g_vk.CmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                            0, 0, NULL, 0, NULL, 1, &b_present);
    g_vk.EndCommandBuffer(g_cmd);

    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = 1,
                       .pWaitSemaphores = &g_acq, .pWaitDstStageMask = &wait, .commandBufferCount = 1,
                       .pCommandBuffers = &g_cmd, .signalSemaphoreCount = 1, .pSignalSemaphores = &g_rnd};
    g_vk.ResetFences(g_dev, 1, &g_fence);
    g_vk.QueueSubmit(g_queue, 1, &si, g_fence);

    VkPresentInfoKHR pi = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1,
                           .pWaitSemaphores = &g_rnd, .swapchainCount = 1,
                           .pSwapchains = &g_swapchain, .pImageIndices = &img};
    g_vk.QueuePresentKHR(g_queue, &pi);
    g_vk.WaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
    g_vk.QueueWaitIdle(g_queue);

    g_vk.FreeMemory(g_dev, srcMem, NULL);
    g_vk.DestroyImage(g_dev, src, NULL);

    /* Signal the app once, on the first real client frame reaching the screen, so the
     * launch/preloader overlay can dismiss (wayland has no XServer window-content hook). */
    if (!g_first_frame_done) {
        g_first_frame_done = 1;
        banner_on_first_frame();
    }
    return 0;
}

/* Present one wl_shm (CPU) buffer. winewayland draws the Wine desktop and plain GDI windows
 * via wl_shm, not Vulkan, so without this the desktop is invisible (only dmabuf/Vulkan game
 * frames showed). Upload the pixels into a linear host-visible VkImage, then blit+present it
 * exactly like the dmabuf path. Expects BGRA/XRGB8888 bytes (winewayland's shm format). */
int vk_present_commit_shm(const void *data, int w, int h, int stride, uint32_t wl_format) {
    (void)wl_format; /* ARGB8888/XRGB8888 -> little-endian BGRA bytes == B8G8R8A8_UNORM */
    if (!g_window || !data || w <= 0 || h <= 0) return -1;
    if (ensure_init() != 0) return -1;

    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_B8G8R8A8_UNORM, .extent = {w, h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_PREINITIALIZED};
    VkImage src; VkDeviceMemory srcMem;
    if (g_vk.CreateImage(g_dev, &ici, NULL, &src) != VK_SUCCESS) return -1;

    VkMemoryRequirements req; g_vk.GetImageMemoryRequirements(g_dev, src, &req);
    VkPhysicalDeviceMemoryProperties mp; g_vk.GetPhysicalDeviceMemoryProperties(g_pd, &mp);
    int idx = -1;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((req.memoryTypeBits & (1u << i)) &&
            (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { idx = i; break; }
    if (idx < 0) { g_vk.DestroyImage(g_dev, src, NULL); return -1; }

    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                .allocationSize = req.size, .memoryTypeIndex = (uint32_t)idx};
    if (g_vk.AllocateMemory(g_dev, &mai, NULL, &srcMem) != VK_SUCCESS) {
        g_vk.DestroyImage(g_dev, src, NULL); return -1;
    }
    g_vk.BindImageMemory(g_dev, src, srcMem, 0);

    VkImageSubresource subr = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
    VkSubresourceLayout lay; g_vk.GetImageSubresourceLayout(g_dev, src, &subr, &lay);
    void *map = NULL;
    if (g_vk.MapMemory(g_dev, srcMem, 0, req.size, 0, &map) != VK_SUCCESS) {
        g_vk.FreeMemory(g_dev, srcMem, NULL); g_vk.DestroyImage(g_dev, src, NULL); return -1;
    }
    int rowbytes = w * 4; if (stride < rowbytes) rowbytes = stride;
    for (int y = 0; y < h; y++)
        memcpy((uint8_t *)map + lay.offset + (size_t)y * lay.rowPitch,
               (const uint8_t *)data + (size_t)y * stride, rowbytes);
    g_vk.UnmapMemory(g_dev, srcMem); /* coherent: visible to the queue at submit */

    uint32_t img = 0;
    VkResult ar = g_vk.AcquireNextImageKHR(g_dev, g_swapchain, UINT64_MAX, g_acq, VK_NULL_HANDLE, &img);
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        g_vk.FreeMemory(g_dev, srcMem, NULL); g_vk.DestroyImage(g_dev, src, NULL); return -1;
    }

    g_vk.ResetCommandBuffer(g_cmd, 0);
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    g_vk.BeginCommandBuffer(g_cmd, &bi);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier b_src = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_PREINITIALIZED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = src, .subresourceRange = range,
        .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
    VkImageMemoryBarrier b_dst = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = g_images[img], .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    VkImageMemoryBarrier pre[2] = {b_src, b_dst};
    g_vk.CmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 2, pre);

    VkImageBlit blit = {.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                        .srcOffsets = {{0, 0, 0}, {w, h, 1}},
                        .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                        .dstOffsets = {{0, 0, 0}, {(int)g_extent.width, (int)g_extent.height, 1}}};
    g_vk.CmdBlitImage(g_cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g_images[img],
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

    VkImageMemoryBarrier b_present = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = g_images[img],
        .subresourceRange = range, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    g_vk.CmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                            0, 0, NULL, 0, NULL, 1, &b_present);
    g_vk.EndCommandBuffer(g_cmd);

    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = 1,
                       .pWaitSemaphores = &g_acq, .pWaitDstStageMask = &wait, .commandBufferCount = 1,
                       .pCommandBuffers = &g_cmd, .signalSemaphoreCount = 1, .pSignalSemaphores = &g_rnd};
    g_vk.ResetFences(g_dev, 1, &g_fence);
    g_vk.QueueSubmit(g_queue, 1, &si, g_fence);

    VkPresentInfoKHR pi = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1,
                           .pWaitSemaphores = &g_rnd, .swapchainCount = 1,
                           .pSwapchains = &g_swapchain, .pImageIndices = &img};
    g_vk.QueuePresentKHR(g_queue, &pi);
    g_vk.WaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
    g_vk.QueueWaitIdle(g_queue);

    g_vk.FreeMemory(g_dev, srcMem, NULL);
    g_vk.DestroyImage(g_dev, src, NULL);

    if (!g_first_frame_done) { g_first_frame_done = 1; banner_on_first_frame(); }
    return 0;
}
