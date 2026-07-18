package com.winlator.star.wayland;

import android.view.Surface;

/**
 * Embedded Wayland compositor (experimental parallel display runtime).
 *
 * Brings up a libwayland-server compositor in-process so games launched through
 * Wine's winewayland.drv can present into it (companion to the winewayland Proton
 * build). Committed frames (dmabufs from winewayland's Vulkan WSI) are composited
 * onto the given Surface via the native Vulkan present backend. Not on the default
 * X11 path.
 */
public final class WaylandCompositor {
    static {
        System.loadLibrary("bannerwayland");
    }

    private WaylandCompositor() {}

    /** Start the compositor headless (no output window) — bring-up tests only. */
    public static native void nativeStart(String xdgRuntimeDir);

    /** Start the compositor rendering to {@code surface}. XDG_RUNTIME_DIR = an
     *  app-writable dir for the wayland socket (e.g. context.getFilesDir()).
     *  driverPath/libraryName/nativeLibDir select the Turnip driver via adrenotools
     *  (all null -> system libvulkan, which can't do dmabuf import). */
    public static native void nativeStartWithSurface(Surface surface, String xdgRuntimeDir,
                                                     String driverPath, String libraryName,
                                                     String nativeLibDir);

    /** Replace/clear the output window when the SurfaceView is (re)created/destroyed. */
    public static native void nativeSetSurface(Surface surface);
}
