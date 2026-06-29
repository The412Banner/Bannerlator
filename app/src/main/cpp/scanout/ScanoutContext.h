#pragma once

// Renderer-neutral direct-scanout implementation (Android SurfaceControl /
// SurfaceFlinger). Pure NDK: dlsym'd ASurfaceControl_* / ASurfaceTransaction_*
// plus AHardwareBuffer_*. It contains ZERO Vulkan/GL/EGL calls and ZERO
// render-thread coupling: the owning renderer drives it through the public
// surface below and decides WHEN the deferred cursor transaction is applied
// (the Vulkan path defers to its render loop; a GL path would apply inline).
//
// Extracted verbatim from VulkanRendererContext / VulkanRendererScanout.cpp
// (P0 of the GL direct-scanout plan). The only intended semantic change vs the
// old code is that the cursor render-thread signalling (needsRender / dirtyCV)
// stays in the owner: setCursorImage()/setCursorPos() return whether an apply
// became pending so the owner can signal its loop exactly as before.

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/rect.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <cstdint>

#ifndef SCANOUT_LOG
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Scanout",__VA_ARGS__)
#endif

class ScanoutContext {
public:
    // --- API load / lifecycle ---
    bool loadApi();
    // Fallback path: creates the child SurfaceControls under the owner's own
    // ANativeWindow. Passing a window updates the stored fallback window.
    void initFromWindow(ANativeWindow* win = nullptr);
    // Primary path: wraps the two sibling game/cursor windows (handed in by the
    // owner) as ASurfaceControls. Takes ownership and releases both windows.
    void initFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void destroy();

    // --- per-frame game buffer (applies its transaction INLINE) ---
    void setBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd = -1);

    // --- cursor: pure state-setters. They mutate state only and DO NOT signal
    //     any render thread. Each returns true if a deferred apply is now
    //     pending (i.e. the owner should arrange for applyPendingCursor()).
    bool setCursorImage(void* pixels, short w, short h, short stride);
    bool setCursorPos(short x, short y, short hotX, short hotY);
    // Applies the pending cursor image/position transaction. Safe to call from
    // any thread (a SurfaceControl transaction apply is thread-safe). The Vulkan
    // owner calls this from its render loop; an inline owner may call it directly.
    void applyPendingCursor();

    // --- geometry / sizing fed by the owner ---
    void setDst(int x, int y, int w, int h);
    void setContainerSize(int w, int h) { containerWidth = w; containerHeight = h; }
    void setSurfaceSize(int w, int h)   { surfaceWidth = w; surfaceHeight = h; }
    void setFallbackWindow(ANativeWindow* win) { window = win; }
    void setVerboseLog(bool v) { verboseLog = v; }

    // --- queries ---
    bool isActive() const { return scanoutActive.load(); }
    bool isGameFrameDelivered() const {
        return gameFrameDelivered.load(std::memory_order_acquire);
    }
    void* debugGameSC() const { return scanoutGameSC; }

    // Public so the owner can expose them through its existing ABI without a
    // second source of truth (Vulkan binds references to these).
    std::atomic<bool> scanoutActive{false};
    std::atomic<bool> gameFrameDelivered{false};

private:
    ANativeWindow* window = nullptr;   // owner's own window, for the fallback path
    bool verboseLog = true;

    int surfaceWidth = 0, surfaceHeight = 0, containerWidth = 0, containerHeight = 0;

    void*   scanoutGameSC     = nullptr;
    void*   scanoutCursorSC   = nullptr;
    void*   scanoutCursorBuf  = nullptr;
    int32_t scanoutCursorBufW = 0;
    int32_t scanoutCursorBufH = 0;

    void*   scanoutTx         = nullptr;
    void*   scanoutGameTx     = nullptr;

    ARect   scanoutLastSrc{}, scanoutLastDst{};
    bool    scanoutGeoDirty   = true;
    bool    scanoutVisShown   = false;
    bool    scanoutApiLoaded  = false;

    void*   fnSCCreateFromWin = nullptr;
    void*   fnSCRelease       = nullptr;
    void*   fnSTCreate        = nullptr;
    void*   fnSTDelete        = nullptr;
    void*   fnSTApply         = nullptr;
    void*   fnSTSetBuffer     = nullptr;
    void*   fnSTSetZOrder     = nullptr;
    void*   fnSTSetVisibility = nullptr;
    void*   fnSTSetGeometry   = nullptr;

    int32_t scanoutDstX = 0, scanoutDstY = 0, scanoutDstW = 0, scanoutDstH = 0;
    bool    gameScVisible = false;

    std::mutex scanoutMutex;

    short   pendingCursorX = 0, pendingCursorY = 0, pendingCursorHotX = 0, pendingCursorHotY = 0;
    bool    cursorPosDirty = false;
    bool    cursorImageDirty = false;
};
