package com.winlator.star.core;

import android.content.Context;

import com.winlator.star.contents.ContentsManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects whether a selected wine/Proton layer's unix winex11 driver was COMPILED WITH xrandr.
 *
 * The in-game refresh unlock (disabling Wine's win32u mode emulation) only produces discrete refresh
 * rates on a layer whose winex11.so links libXrandr; on an old layer it gives no benefit AND shrinks
 * the resolution list to the NoRes single mode (a mild regression). Both the launch path and the
 * container editor gate on this so we never regress old-layer users.
 *
 * Probe: scan winex11.so for the xrandr symbol markers (present in a compiled-in build) and for Wine's
 * "XRandR support not compiled in." notice (present only in the #else branch). Result cached per layer
 * identifier. Conservative: a missing file or read error counts as NOT capable.
 */
public final class WineRandrSupport {
    private WineRandrSupport() {}

    private static final Map<String, Boolean> cache = new HashMap<>();

    /** Capability for the layer described by an already-resolved {@link WineInfo}. */
    public static boolean isXrandrCapable(WineInfo wineInfo) {
        if (wineInfo == null || wineInfo.path == null) return false;
        return isXrandrCapable(wineInfo.identifier(), wineInfo.path);
    }

    /** Capability for a wine version identifier (resolves its install dir via WineInfo). */
    public static boolean isXrandrCapable(Context context, ContentsManager contentsManager, String identifier) {
        if (identifier == null || identifier.isEmpty()) return false;
        try {
            return isXrandrCapable(WineInfo.fromIdentifier(context, contentsManager, identifier));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isXrandrCapable(String cacheKey, String layerPath) {
        Boolean cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // arm64ec ships aarch64-unix; x86_64 ships x86_64-unix. Try both.
        File winex11 = new File(layerPath, "lib/wine/aarch64-unix/winex11.so");
        if (!winex11.isFile()) winex11 = new File(layerPath, "lib/wine/x86_64-unix/winex11.so");

        boolean capable = false;
        if (winex11.isFile()) {
            boolean hasXrandr = fileContainsAny(winex11, "libXrandr.so", "RRQueryVersion");
            boolean notCompiledIn = fileContainsAny(winex11, "XRandR support not compiled in.");
            capable = hasXrandr && !notCompiledIn;
        }
        cache.put(cacheKey, capable);
        return capable;
    }

    /**
     * Streaming ASCII substring search — true if the file contains ANY marker. Reads in chunks with a
     * small carry-over so a marker split across a chunk boundary is still found. Markers are plain
     * ASCII, so a byte-wise compare is exact.
     */
    private static boolean fileContainsAny(File file, String... markers) {
        byte[][] needles = new byte[markers.length][];
        int maxNeedle = 0;
        for (int i = 0; i < markers.length; i++) {
            needles[i] = markers[i].getBytes(StandardCharsets.US_ASCII);
            maxNeedle = Math.max(maxNeedle, needles[i].length);
        }
        final int chunk = 64 * 1024;
        byte[] buf = new byte[chunk + maxNeedle];
        try (FileInputStream in = new FileInputStream(file)) {
            int carry = 0;
            int read;
            while ((read = in.read(buf, carry, chunk)) != -1) {
                int len = carry + read;
                for (byte[] needle : needles) {
                    if (indexOf(buf, len, needle) != -1) return true;
                }
                // Retain the last (maxNeedle-1) bytes so a boundary-straddling marker survives.
                carry = Math.min(len, maxNeedle - 1);
                System.arraycopy(buf, len - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static int indexOf(byte[] haystack, int hayLen, byte[] needle) {
        if (needle.length == 0 || needle.length > hayLen) return -1;
        outer:
        for (int i = 0; i <= hayLen - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
