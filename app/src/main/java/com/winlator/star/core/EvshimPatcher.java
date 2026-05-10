package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import java.io.File;

public final class EvshimPatcher {
    private EvshimPatcher() {}

    /**  arm64ec -> "aarch64-unix",  x86_64 -> "x86_64-unix"  */
    private static String archDir(boolean arm64ec) {
        return arm64ec ? "aarch64-unix" : "x86_64-unix";
    }

    /**
     * Copy the pre-patched winebus.so from imagefs into a Wine / Proton tree
     * the first time we see it.
     */
    public static void patchWineTree(Context ctx, File wineRoot, boolean arm64ec) {
        File dst = new File(wineRoot,
                "lib/wine/" + archDir(arm64ec) + "/winebus.so");

        // Already patched?
        if (dst.exists() && dst.length() > 0) return;

        File src = new File(ctx.getFilesDir(),
                "imagefs/opt/proton-9.0-" +
                        (arm64ec ? "arm64ec" : "x86_64") +
                        "/lib/wine/" + archDir(arm64ec) + "/winebus.so");

        if (!src.exists()) {
            Log.w("Evshim", "patch source missing: " + src);
            return;
        }

        dst.getParentFile().mkdirs();
        FileUtils.copy(src, dst);          // helper already in project
        FileUtils.chmod(dst, 0644);
        Log.i("Evshim", "Patched winebus into " + dst);
    }
}
