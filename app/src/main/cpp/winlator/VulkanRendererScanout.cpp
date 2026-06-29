// Thin forwarders from the Vulkan renderer's scanout JNI surface to the
// renderer-neutral ScanoutContext (scanout/ScanoutContext.{h,cpp}). The actual
// SurfaceControl / AHardwareBuffer implementation lives there now (P0 extract).
//
// The ONLY logic kept here is the cursor render-thread signalling: the
// ScanoutContext setters mutate state and report whether a deferred apply is
// pending; this layer signals the Vulkan render loop (needsRender / dirtyCV /
// cursorMoved) exactly as the old inlined code did, preserving Vulkan behavior.

#include "VulkanRendererContext.h"

void VulkanRendererContext::initScanout() {
    scanout.initFromWindow(window);
}

void VulkanRendererContext::initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin) {
    scanout.initFromWindows(gameWin, cursorWin);
}

void VulkanRendererContext::destroyScanout() {
    scanout.destroy();
}

void VulkanRendererContext::applyScanoutBuffer() {
    scanout.applyPendingCursor();
}

void VulkanRendererContext::scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd) {
    scanout.setBuffer(ahb, x, y, w, h, fenceFd);
}

void VulkanRendererContext::scanoutSetDst(int x, int y, int w, int h) {
    scanout.setDst(x, y, w, h);
}

void VulkanRendererContext::scanoutSetCursorImage(void* pixels, short w, short h, short stride) {
    if (scanout.setCursorImage(pixels, w, h, stride)) {
        needsRender.store(true, std::memory_order_release);
        dirtyCV.notify_one();
    }
}

void VulkanRendererContext::scanoutSetCursorPos(short x, short y, short hotX, short hotY) {
    if (scanout.setCursorPos(x, y, hotX, hotY)) {
        cursorMoved.store(true, std::memory_order_relaxed);
        dirtyCV.notify_one();
    }
}
