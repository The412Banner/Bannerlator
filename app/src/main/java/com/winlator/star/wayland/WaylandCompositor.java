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

    private static volatile Runnable firstFrameListener;

    /** Register a callback fired once, when the compositor presents the first client
     *  frame to the output Surface. Used to dismiss the launch overlay in wayland mode
     *  (there is no XServer window-content hook). Runs on the compositor thread — the
     *  listener must marshal to the UI thread itself. */
    public static void setFirstFrameListener(Runnable r) { firstFrameListener = r; }

    /** Invoked from native (banner_on_first_frame) on the first present. */
    @SuppressWarnings("unused")
    static void onFirstFramePresented() {
        Runnable r = firstFrameListener;
        if (r != null) r.run();
    }

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
