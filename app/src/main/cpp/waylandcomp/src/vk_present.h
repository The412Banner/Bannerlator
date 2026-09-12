#ifndef VK_PRESENT_H
#define VK_PRESENT_H
#include <stdint.h>
#include <android/native_window.h>
/*
 * Android-surface render backend for the embedded Wayland compositor.
 * Owns a Turnip VkDevice + a swapchain on the SurfaceView's ANativeWindow. Each
 * client buffer becomes an image (a dmabuf from winewayland's Vulkan WSI is
 * imported zero-copy; a wl_shm buffer is copied into a host-visible image), and
 * every frame blits the whole scene — desktop, windows, subsurfaces — in order.
 */

// Set the Turnip driver to load (adrenotools). Call before the first frame.
// NULL args -> the backend falls back to the system libvulkan (dmabuf import will
// likely fail — Adreno lacks drm_format_modifier). driver_path ends with '/'.
void vk_present_set_driver(const char *driver_path, const char *library_name,
                           const char *native_lib_dir);

// Set/replace the output window (from Surface via ANativeWindow_fromSurface).
// NULL tears the swapchain down (surface destroyed); images and the device survive.
void vk_present_set_window(ANativeWindow *window);

struct vkp_image;

// Import a dmabuf (single plane). NULL on failure. The image aliases the buffer, so
// later client frames rendered into the same buffer show up without re-importing.
struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                        int w, int h, uint32_t stride, uint32_t offset);

// Create a host-visible image and copy BGRA/XRGB8888 pixels into it. NULL on failure.
struct vkp_image *vkp_image_create_shm(int w, int h);
void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride);

int vkp_image_width(const struct vkp_image *img);
int vkp_image_height(const struct vkp_image *img);
void vkp_image_destroy(struct vkp_image *img);

// One scene draw: the src rectangle of an image (image pixels) scaled into the dst
// rectangle (scene pixels). The scene is stretched to fill the output window.
struct vkp_draw {
    struct vkp_image *img;
    float sx, sy, sw, sh;
    int dx, dy, dw, dh;
};

// 0 if the renderer can create images (device up), -1 otherwise.
int vkp_ready(void);

// Clear to black, blit the draws in order (first = bottom) and present.
// Returns 0 on success, -1 if nothing could be presented (no window yet, etc.).
int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n);

// Session log (compositor.c): one line to Download/Wayland-logs and logcat.
void banner_log(const char *tag, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

#endif
