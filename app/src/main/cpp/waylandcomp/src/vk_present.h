#ifndef VK_PRESENT_H
#define VK_PRESENT_H
#include <stdint.h>
#include <android/native_window.h>
/*
 * Android-surface render backend for the embedded Wayland compositor.
 * Owns a Turnip VkDevice + a swapchain on the SurfaceView's ANativeWindow, and
 * composites each committed game frame (a dmabuf from winewayland's Vulkan WSI)
 * onto the screen: import dmabuf -> VkImage -> blit to the acquired swapchain
 * image -> present. Reuses the proven spike patterns (client_vk swapchain +
 * vk_import dmabuf import).
 */

// Set/replace the output window (from Surface via ANativeWindow_fromSurface).
// NULL tears the swapchain down (surface destroyed).
void vk_present_set_window(ANativeWindow *window);

// Composite one committed dmabuf frame to the screen. No-op (returns <0) if no
// window is set or init fails. Non-fatal — never aborts the compositor.
int vk_present_commit_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                             int w, int h, uint32_t stride, uint32_t offset);

#endif
