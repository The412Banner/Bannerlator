/* Android-surface render backend — see vk_present.h. Uses Turnip via vk_loader
 * (g_vk.*), not the process-default system Adreno driver. */
#define _POSIX_C_SOURCE 200809L
#include "vk_present.h"
#include "vk_loader.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "BannerWayland"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) banner_log("error", __VA_ARGS__)
#define MOD_INVALID 0x00ffffffffffffffULL

struct vkp_image {
    VkImage image;
    VkDeviceMemory mem;
    int w, h;
    int dmabuf;               /* imported from a client; owned by the foreign queue family */
    void *map;                /* shm images: persistently mapped linear memory */
    VkDeviceSize offset, row_pitch;
    int in_general;           /* shm images: moved from PREINITIALIZED to GENERAL */
};

static ANativeWindow *g_window;
static char *g_driver_path, *g_library_name, *g_native_lib_dir;
static int g_dev_state;       /* 0 = not yet, 1 = ok, -1 = failed */
static VkInstance g_inst;
static VkPhysicalDevice g_pd;
static VkDevice g_dev;
static VkQueue g_queue;
static uint32_t g_qfam;
static VkCommandPool g_pool;
static VkCommandBuffer g_cmd;
static VkSemaphore g_acq, g_rnd;
static VkFence g_fence;
static VkPhysicalDeviceMemoryProperties g_memprops;

static VkSurfaceKHR g_surface;
static VkSwapchainKHR g_swapchain;
static VkImage *g_images;
static uint32_t g_nimg;
static VkExtent2D g_extent;

static int g_first_frame_done; /* one-shot: fire banner_on_first_frame() on first present */
/* The Android surface is replaced from the UI thread while the compositor thread renders:
 * frames and window changes are serialized so a frame never presents to a dead surface. */
static pthread_mutex_t g_swap_lock = PTHREAD_MUTEX_INITIALIZER;

/* Implemented in waylandcomp_jni.c — notifies Java (dismiss launch overlay). */
extern void banner_on_first_frame(void);

static char g_gpu_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
const char *vkp_gpu_name(void) { return g_gpu_name; }

/* DRM fourccs name the channels of a little-endian 32-bit word: XRGB8888 is B,G,R,X in memory
 * (= VK B8G8R8A8) and XBGR8888 is R,G,B,X (= VK R8G8B8A8). Turnip's Wayland WSI sends XB24 for
 * R8G8B8A8 swapchains, so reading everything as BGRA swaps red and blue. */
static VkFormat drm_to_vk(uint32_t drm) {
    switch (drm) {
    case 0x34324241: /* AB24 */
    case 0x34324258: /* XB24 */
        return VK_FORMAT_R8G8B8A8_UNORM;
    case 0x30334241: /* AB30 */
    case 0x30334258: /* XB30 */
        return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    case 0x30335241: /* AR30 */
    case 0x30335258: /* XR30 */
        return VK_FORMAT_A2R10G10B10_UNORM_PACK32;
    case 0x48344241: /* AB4H */
    case 0x48344258: /* XB4H */
        return VK_FORMAT_R16G16B16A16_SFLOAT;
    default: /* AR24 / XR24 */
        return VK_FORMAT_B8G8R8A8_UNORM;
    }
}

void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir) {
    free(g_driver_path); free(g_library_name); free(g_native_lib_dir);
    g_driver_path = driver_path ? strdup(driver_path) : NULL;
    g_library_name = library_name ? strdup(library_name) : NULL;
    g_native_lib_dir = native_lib_dir ? strdup(native_lib_dir) : NULL;
}

static void destroy_swapchain(void) {
    if (g_dev_state != 1) return;
    g_vk.DeviceWaitIdle(g_dev);
    if (g_swapchain) g_vk.DestroySwapchainKHR(g_dev, g_swapchain, NULL);
    g_swapchain = VK_NULL_HANDLE;
    if (g_surface) g_vk.DestroySurfaceKHR(g_inst, g_surface, NULL);
    g_surface = VK_NULL_HANDLE;
    free(g_images);
    g_images = NULL;
    g_nimg = 0;
}

void vk_present_set_window(ANativeWindow *window) {
    pthread_mutex_lock(&g_swap_lock);
    destroy_swapchain(); /* recreated against the new window on the next frame */
    g_window = window;
    pthread_mutex_unlock(&g_swap_lock);
}

static int has_ext(VkExtensionProperties *e, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

/* Instance + device + command objects. Doesn't need the window. */
static int dev_init(void) {
    if (g_dev_state != 0) return g_dev_state == 1 ? 0 : -1;

    /* Load Turnip (adrenotools) and its entry points — NOT the system driver. */
    if (vk_loader_open(g_driver_path, g_library_name, g_native_lib_dir) != 0) {
        LOGE("present: vk_loader_open failed"); g_dev_state = -1; return -1;
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
        LOGE("present: vkCreateInstance failed"); g_dev_state = -1; return -1;
    }
    vk_loader_load_instance(g_inst);

    uint32_t npd = 0;
    g_vk.EnumeratePhysicalDevices(g_inst, &npd, NULL);
    if (!npd) { LOGE("present: no physical devices"); g_dev_state = -1; return -1; }
    VkPhysicalDevice pds[8]; if (npd > 8) npd = 8;
    g_vk.EnumeratePhysicalDevices(g_inst, &npd, pds);
    g_pd = VK_NULL_HANDLE;
    for (uint32_t i = 0; i < npd && g_pd == VK_NULL_HANDLE; i++) {
        uint32_t nq = 0;
        g_vk.GetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, NULL);
        VkQueueFamilyProperties qs[16]; if (nq > 16) nq = 16;
        g_vk.GetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, qs);
        for (uint32_t q = 0; q < nq; q++)
            if (qs[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) { g_pd = pds[i]; g_qfam = q; break; }
    }
    if (g_pd == VK_NULL_HANDLE) { LOGE("present: no graphics queue"); g_dev_state = -1; return -1; }
    {
        VkPhysicalDeviceProperties props;
        g_vk.GetPhysicalDeviceProperties(g_pd, &props);
        snprintf(g_gpu_name, sizeof(g_gpu_name), "%s", props.deviceName);
        banner_log("gpu", "compositor renders on %s with %s", props.deviceName,
                   g_library_name ? g_library_name : "the system Vulkan driver");
        if (g_driver_path) banner_log("gpu", "driver folder %s", g_driver_path);
    }
    g_vk.GetPhysicalDeviceMemoryProperties(g_pd, &g_memprops);

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
        LOGE("present: vkCreateDevice failed"); g_dev_state = -1; return -1;
    }
    vk_loader_load_device(g_dev);
    g_vk.GetDeviceQueue(g_dev, g_qfam, 0, &g_queue);

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

    g_dev_state = 1;
    return 0;
}

int vkp_ready(void) { return dev_init(); }

static int swap_init(void) {
    if (!g_window) return -1;

    VkAndroidSurfaceCreateInfoKHR aci = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = g_window};
    if (g_vk.CreateAndroidSurfaceKHR(g_inst, &aci, NULL, &g_surface) != VK_SUCCESS) {
        LOGE("present: create android surface failed"); return -1;
    }
    VkBool32 sup = VK_FALSE;
    g_vk.GetPhysicalDeviceSurfaceSupportKHR(g_pd, g_qfam, g_surface, &sup);
    if (!sup) { LOGE("present: queue can't present to the window"); destroy_swapchain(); return -1; }

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
        LOGE("present: vkCreateSwapchainKHR failed"); destroy_swapchain(); return -1;
    }
    g_vk.GetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, NULL);
    g_images = calloc(g_nimg, sizeof(VkImage));
    g_vk.GetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, g_images);

    banner_log("gpu", "screen output %ux%u, %u buffers, vsync", g_extent.width, g_extent.height, g_nimg);
    return 0;
}

static int memory_type(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_memprops.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_memprops.memoryTypes[i].propertyFlags & want) == want)
            return (int)i;
    return -1;
}

struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                                        uint32_t stride, uint32_t offset) {
    if (modifier == MOD_INVALID || w <= 0 || h <= 0) return NULL;
    if (dev_init() != 0) return NULL;

    struct vkp_image *img = calloc(1, sizeof(*img));
    if (!img) return NULL;
    img->w = w; img->h = h; img->dmabuf = 1;

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
    if (g_vk.CreateImage(g_dev, &ici, NULL, &img->image) != VK_SUCCESS) { free(img); return NULL; }

    int dupfd = dup(fd);
    uint32_t allowed = 0xffffffff;
    if (g_vk.GetMemoryFdPropertiesKHR) {
        VkMemoryFdPropertiesKHR fp = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (g_vk.GetMemoryFdPropertiesKHR(g_dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                          dupfd, &fp) == VK_SUCCESS)
            allowed = fp.memoryTypeBits;
    }
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, img->image, &req);
    uint32_t bits = req.memoryTypeBits & allowed;
    int idx = -1;
    for (int i = 0; i < 32; i++) if (bits & (1u << i)) { idx = i; break; }
    if (idx < 0) { g_vk.DestroyImage(g_dev, img->image, NULL); close(dupfd); free(img); return NULL; }

    VkImportMemoryFdInfoKHR imp = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
                                   .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                   .fd = dupfd};
    VkMemoryDedicatedAllocateInfo ded = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                                         .pNext = &imp, .image = img->image};
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &ded,
                                .allocationSize = req.size, .memoryTypeIndex = (uint32_t)idx};
    if (g_vk.AllocateMemory(g_dev, &mai, NULL, &img->mem) != VK_SUCCESS) {
        g_vk.DestroyImage(g_dev, img->image, NULL); close(dupfd); free(img); return NULL;
    }
    if (g_vk.BindImageMemory(g_dev, img->image, img->mem, 0) != VK_SUCCESS) {
        g_vk.FreeMemory(g_dev, img->mem, NULL); g_vk.DestroyImage(g_dev, img->image, NULL);
        free(img); return NULL;
    }
    return img;
}

struct vkp_image *vkp_image_create_shm(int w, int h) {
    if (w <= 0 || h <= 0 || dev_init() != 0) return NULL;

    struct vkp_image *img = calloc(1, sizeof(*img));
    if (!img) return NULL;
    img->w = w; img->h = h;

    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_B8G8R8A8_UNORM, .extent = {w, h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_PREINITIALIZED};
    if (g_vk.CreateImage(g_dev, &ici, NULL, &img->image) != VK_SUCCESS) { free(img); return NULL; }

    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, img->image, &req);
    int idx = memory_type(req.memoryTypeBits,
                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                .allocationSize = req.size, .memoryTypeIndex = (uint32_t)idx};
    if (idx < 0 || g_vk.AllocateMemory(g_dev, &mai, NULL, &img->mem) != VK_SUCCESS) {
        g_vk.DestroyImage(g_dev, img->image, NULL); free(img); return NULL;
    }
    g_vk.BindImageMemory(g_dev, img->image, img->mem, 0);

    VkImageSubresource subr = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
    VkSubresourceLayout lay;
    g_vk.GetImageSubresourceLayout(g_dev, img->image, &subr, &lay);
    img->offset = lay.offset;
    img->row_pitch = lay.rowPitch;
    if (g_vk.MapMemory(g_dev, img->mem, 0, req.size, 0, &img->map) != VK_SUCCESS) {
        vkp_image_destroy(img); return NULL;
    }
    return img;
}

/* Renders are synchronous (we wait for the frame's fence), so writing between frames
 * never races the GPU. */
void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride) {
    if (!img || !img->map || !data) return;
    size_t rowbytes = (size_t)img->w * 4;
    if ((size_t)stride < rowbytes) rowbytes = (size_t)stride;
    for (int y = 0; y < img->h; y++)
        memcpy((uint8_t *)img->map + img->offset + (size_t)y * img->row_pitch,
               (const uint8_t *)data + (size_t)y * stride, rowbytes);
}

int vkp_image_width(const struct vkp_image *img) { return img ? img->w : 0; }
int vkp_image_height(const struct vkp_image *img) { return img ? img->h : 0; }

void vkp_image_destroy(struct vkp_image *img) {
    if (!img) return;
    if (g_dev_state == 1) {
        if (img->map) g_vk.UnmapMemory(g_dev, img->mem);
        if (img->image) g_vk.DestroyImage(g_dev, img->image, NULL);
        if (img->mem) g_vk.FreeMemory(g_dev, img->mem, NULL);
    }
    free(img);
}

/* Map a draw into swapchain pixels, clipping the destination to the window and
 * trimming the source to match. Returns 0 if nothing is left to draw. */
static int draw_to_blit(const struct vkp_draw *d, float kx, float ky, VkImageBlit *blit) {
    float x0 = d->dx * kx, y0 = d->dy * ky;
    float x1 = (d->dx + d->dw) * kx, y1 = (d->dy + d->dh) * ky;
    float sx0 = d->sx, sy0 = d->sy, sx1 = d->sx + d->sw, sy1 = d->sy + d->sh;
    float W = (float)g_extent.width, H = (float)g_extent.height;

    if (x1 <= x0 || y1 <= y0 || sx1 <= sx0 || sy1 <= sy0) return 0;
    if (x0 < 0) { sx0 += (0 - x0) / (x1 - x0) * (sx1 - sx0); x0 = 0; }
    if (y0 < 0) { sy0 += (0 - y0) / (y1 - y0) * (sy1 - sy0); y0 = 0; }
    if (x1 > W) { sx1 -= (x1 - W) / (x1 - x0) * (sx1 - sx0); x1 = W; }
    if (y1 > H) { sy1 -= (y1 - H) / (y1 - y0) * (sy1 - sy0); y1 = H; }

    int ix0 = (int)(x0 + 0.5f), iy0 = (int)(y0 + 0.5f), ix1 = (int)(x1 + 0.5f), iy1 = (int)(y1 + 0.5f);
    int isx0 = (int)sx0, isy0 = (int)sy0, isx1 = (int)(sx1 + 0.5f), isy1 = (int)(sy1 + 0.5f);
    if (isx0 < 0) isx0 = 0;
    if (isy0 < 0) isy0 = 0;
    if (isx1 > d->img->w) isx1 = d->img->w;
    if (isy1 > d->img->h) isy1 = d->img->h;
    if (ix1 <= ix0 || iy1 <= iy0 || isx1 <= isx0 || isy1 <= isy0) return 0;

    *blit = (VkImageBlit){.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                          .srcOffsets = {{isx0, isy0, 0}, {isx1, isy1, 1}},
                          .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                          .dstOffsets = {{ix0, iy0, 0}, {ix1, iy1, 1}}};
    return 1;
}

static int render_locked(int scene_w, int scene_h, const struct vkp_draw *draws, int n);

int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n) {
    pthread_mutex_lock(&g_swap_lock);
    int ret = render_locked(scene_w, scene_h, draws, n);
    pthread_mutex_unlock(&g_swap_lock);
    return ret;
}

static int render_locked(int scene_w, int scene_h, const struct vkp_draw *draws, int n) {
    if (dev_init() != 0 || !g_window || scene_w <= 0 || scene_h <= 0) return -1;
    if (!g_swapchain && swap_init() != 0) return -1;

    uint32_t img = 0;
    VkResult ar = g_vk.AcquireNextImageKHR(g_dev, g_swapchain, UINT64_MAX, g_acq, VK_NULL_HANDLE, &img);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) { destroy_swapchain(); return -1; }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) return -1;

    g_vk.ResetCommandBuffer(g_cmd, 0);
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    g_vk.BeginCommandBuffer(g_cmd, &bi);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    /* Source images: take dmabufs from the client's queue family, move shm images to
     * GENERAL once (host writes stay visible across frames: memory is coherent and each
     * frame is a new submission). One barrier per distinct image. */
    VkImageMemoryBarrier *bars = calloc((size_t)n + 1, sizeof(*bars));
    int nb = 0;
    bars[nb++] = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = g_images[img], .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    for (int i = 0; i < n; i++) {
        struct vkp_image *im = draws[i].img;
        int seen = 0;
        for (int j = 0; j < i; j++) if (draws[j].img == im) { seen = 1; break; }
        if (seen || !im) continue;
        if (im->dmabuf) {
            bars[nb++] = (VkImageMemoryBarrier){
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT, .dstQueueFamilyIndex = g_qfam,
                .image = im->image, .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
        } else if (!im->in_general) {
            bars[nb++] = (VkImageMemoryBarrier){
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_PREINITIALIZED,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = im->image, .subresourceRange = range,
                .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
            im->in_general = 1;
        }
    }
    g_vk.CmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, (uint32_t)nb, bars);
    free(bars);

    VkClearColorValue black = {.float32 = {0.0f, 0.0f, 0.0f, 1.0f}};
    g_vk.CmdClearColorImage(g_cmd, g_images[img], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
    {
        VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                              .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                              .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
        g_vk.CmdPipelineBarrier(g_cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                0, 1, &mb, 0, NULL, 0, NULL);
    }

    float kx = (float)g_extent.width / scene_w, ky = (float)g_extent.height / scene_h;
    int drawn = 0;
    for (int i = 0; i < n; i++) {
        VkImageBlit blit;
        if (!draws[i].img || !draw_to_blit(&draws[i], kx, ky, &blit)) continue;
        g_vk.CmdBlitImage(g_cmd, draws[i].img->image,
                          draws[i].img->dmabuf ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL,
                          g_images[img], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
        drawn++;
    }

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
    if (g_vk.QueueSubmit(g_queue, 1, &si, g_fence) != VK_SUCCESS) { LOGE("present: submit failed"); return -1; }

    VkPresentInfoKHR pi = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1,
                           .pWaitSemaphores = &g_rnd, .swapchainCount = 1,
                           .pSwapchains = &g_swapchain, .pImageIndices = &img};
    VkResult pr = g_vk.QueuePresentKHR(g_queue, &pi);
    g_vk.WaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR) destroy_swapchain();

    /* Signal the app once, on the first real client frame reaching the screen, so the
     * launch/preloader overlay can dismiss (wayland has no XServer window-content hook). */
    if (drawn && !g_first_frame_done) {
        g_first_frame_done = 1;
        banner_on_first_frame();
    }
    return 0;
}
