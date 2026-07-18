package com.winlator.star.wayland;

/**
 * Embedded Wayland compositor (experimental parallel display runtime).
 *
 * Brings up a libwayland-server compositor in-process so games launched through
 * Wine's winewayland.drv can present into it (companion to the winewayland Proton
 * build). The render-to-Surface backend, input, and launch wiring land in the M4
 * phase; this is the JNI bring-up. Not on the default X11 path.
 */
public final class WaylandCompositor {
    static {
        System.loadLibrary("bannerwayland");
    }

    private WaylandCompositor() {}

    /** Start the compositor on its own native thread. XDG_RUNTIME_DIR = an
     *  app-writable dir for the wayland socket (e.g. context.getFilesDir()). */
    public static native void nativeStart(String xdgRuntimeDir);
}
