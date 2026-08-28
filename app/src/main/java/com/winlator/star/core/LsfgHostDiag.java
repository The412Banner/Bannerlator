package com.winlator.star.core;

import android.util.Log;

import java.io.File;

/**
 * Host-side LSFG de-risk + cache-build entry point.
 *
 * <p>When the "lsfg" frame-gen engine is selected at launch we run the host
 * device-feature probe and the DXBC-&gt;SPIR-V translation of the user's
 * Lossless.dll here, on a background thread, before the compositor's frame
 * generator is created. The native call logs one line under tag {@code LSFG-HOST}
 * (captured by {@link WinFgDiag}'s diagnostic log) reporting the probe result and
 * whether all 25 shaders translated. It also writes the shader cache the
 * compositor's host frame generator consumes.
 *
 * <p>This is the escape hatch from the guest {@code lsfg-vk} Vulkan layer, which
 * won't load inside Wine on some SoCs (Adreno 840). No guest layer is armed on
 * this path — everything runs natively in the compositor's own Vulkan device.
 */
public final class LsfgHostDiag {
    private static final String TAG = "LSFG-HOST";

    static {
        // The native symbols live in libvulkan_renderer.so (compiled alongside the
        // compositor). Loading an already-loaded library is a no-op.
        try {
            System.loadLibrary("vulkan_renderer");
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary(vulkan_renderer) failed", t);
        }
    }

    private LsfgHostDiag() {}

    /**
     * Run the probe + translate the shaders into {@code cachePath}, logging the
     * one-line verdict. Returns the verdict string (also logged natively).
     */
    public static native String nativeRunHostDiag(String dllPath, String cachePath);

    /** Path where the host LSFG shader cache is written (per app files dir). */
    public static File cacheFile(File filesDir) {
        return new File(filesDir, "lsfg-vk/lsfg_host_cache.bin");
    }

    /**
     * Fire-and-forget: on a background thread, run the probe + build the shader
     * cache from {@code losslessDll} into {@link #cacheFile}. Safe to call every
     * launch; the native side is idempotent and cheap.
     */
    public static void runAsync(final File losslessDll, final File filesDir) {
        new Thread(() -> {
            try {
                final File cache = cacheFile(filesDir);
                File parent = cache.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                final String verdict = nativeRunHostDiag(
                        losslessDll != null ? losslessDll.getAbsolutePath() : "",
                        cache.getAbsolutePath());
                Log.i(TAG, "diag: " + verdict);
            } catch (Throwable t) {
                Log.w(TAG, "host diag failed", t);
            }
        }, "lsfg-host-diag").start();
    }
}
