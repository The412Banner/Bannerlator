package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Native (compositor-side) LSFG frame generation: shader-chain extraction and
 * on-device caching.
 *
 * <p>The 25 compute shaders that make up Lossless Scaling frame generation are
 * not redistributable. They live as resources inside the user's own paid copy
 * of {@code Lossless.dll} (Steam app 993090), so Bannerlator bundles nothing
 * and extracts them on device. The DLL is mapped read-only and parsed as data;
 * it is never loaded as a library and never executed.
 *
 * <p>Extraction is slow enough to be worth doing once — the DXBC chain has to
 * be translated to SPIR-V — so the result is cached, keyed on the DLL's size
 * and content hash. Call {@link #ensureCache} off the main thread.
 */
public final class LsfgNative {
    private static final String TAG = "LsfgNative";

    static { System.loadLibrary("vulkan_renderer"); }

    private LsfgNative() {}

    // Mirrors lsfg::DllStatus.
    public static final int STATUS_OK                      = 0;
    public static final int STATUS_NOT_INSTALLED           = 1;
    public static final int STATUS_UNREADABLE_FILE         = 2;
    public static final int STATUS_NOT_PORTABLE_EXECUTABLE = 3;
    public static final int STATUS_MISSING_SHADERS         = 4;
    public static final int STATUS_TRANSLATION_FAILED      = 5;
    public static final int STATUS_CACHE_UNUSABLE          = 6;

    // Mirrors lsfg::Variant.
    public static final int VARIANT_NONE            = 0;
    public static final int VARIANT_SPIRV_FP16      = 1;
    public static final int VARIANT_SPIRV_FP32      = 2;
    public static final int VARIANT_DXBC_TRANSLATED = 3;

    private static native int nativeValidateDll(String dllPath);
    private static native int nativeDllVariant(String dllPath, boolean preferFp16);
    private static native int nativeBuildCache(String dllPath, String cachePath, boolean preferFp16);
    private static native boolean nativeCacheMatchesSource(String cachePath, String dllPath);
    private static native int nativeCacheVariant(String cachePath);
    private static native String nativeStatusName(int status);
    private static native String nativeVariantName(int variant);

    /** Where the imported Lossless.dll lives — shared with the lsfg-vk engine. */
    public static File losslessDll(Context context) {
        return new File(context.getFilesDir(), "lsfg-vk/Lossless.dll");
    }

    /** Where the translated SPIR-V chain is cached. */
    public static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "lsfg-native/shaders.cache");
    }

    public static boolean isDllAvailable(Context context) {
        return losslessDll(context).isFile();
    }

    public static int validate(File dll) {
        if (dll == null || !dll.isFile()) return STATUS_NOT_INSTALLED;
        return nativeValidateDll(dll.getAbsolutePath());
    }

    public static String statusName(int status) { return nativeStatusName(status); }

    public static String variantName(int variant) { return nativeVariantName(variant); }

    /** Which producer an existing cache was built with, or VARIANT_NONE. */
    public static int cacheVariant(Context context) {
        File cache = cacheFile(context);
        if (!cache.isFile()) return VARIANT_NONE;
        return nativeCacheVariant(cache.getAbsolutePath());
    }

    /**
     * Make sure a shader cache exists and matches the current DLL, building it
     * if not. Slow on a cache miss (the DXBC chain is translated on device);
     * call it off the main thread.
     *
     * @return STATUS_OK when the cache is ready, otherwise the failure reason.
     */
    public static int ensureCache(Context context, boolean preferFp16) {
        File dll = losslessDll(context);
        if (!dll.isFile()) return STATUS_NOT_INSTALLED;

        File cache = cacheFile(context);
        if (cache.isFile()
                && nativeCacheMatchesSource(cache.getAbsolutePath(), dll.getAbsolutePath())) {
            return STATUS_OK;
        }

        File dir = cache.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "could not create cache directory " + dir);
            return STATUS_CACHE_UNUSABLE;
        }

        final long started = System.currentTimeMillis();
        int status = nativeBuildCache(dll.getAbsolutePath(), cache.getAbsolutePath(), preferFp16);
        if (status == STATUS_OK) {
            Log.i(TAG, "shader cache built in " + (System.currentTimeMillis() - started)
                    + " ms, variant=" + variantName(nativeCacheVariant(cache.getAbsolutePath())));
        } else {
            Log.e(TAG, "shader cache build failed: " + statusName(status));
        }
        return status;
    }

    /**
     * A user-facing explanation for a failed {@link #ensureCache}, in the terms
     * a player can act on rather than the terms the parser failed in.
     */
    public static String explain(int status) {
        switch (status) {
            case STATUS_OK:
                return "Ready";
            case STATUS_NOT_INSTALLED:
                return "Import your own Lossless.dll in Settings first";
            case STATUS_UNREADABLE_FILE:
                return "Lossless.dll could not be read";
            case STATUS_NOT_PORTABLE_EXECUTABLE:
                return "That file is not a Windows DLL";
            case STATUS_MISSING_SHADERS:
                return "This Lossless.dll does not contain the frame-generation shaders";
            case STATUS_TRANSLATION_FAILED:
                return "The frame-generation shaders could not be translated for this device";
            case STATUS_CACHE_UNUSABLE:
                return "The shader cache could not be written";
            default:
                return "Unknown error";
        }
    }
}
