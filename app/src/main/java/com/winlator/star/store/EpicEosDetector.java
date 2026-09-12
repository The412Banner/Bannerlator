/*
 * EOS-marker detection for the Epic library "EOS" badge.
 *
 * Companion to EpicLaunchArgs / EpicSidecar — those provide the auth
 * functionality, this provides the visibility UX so users can see at a
 * glance which Epic games will use Bannerlator's EOS auth integration.
 *
 * Credits: this file's marker list (EOSSDK-Win64-Shipping.dll,
 * EOSOVH-Win64-Shipping.dll, EOSBootstrapper.exe) follows the same set
 * The GameNative Team uses to identify EOS-integrated games. See PR #1286
 * (https://github.com/utkarshdalal/GameNative/pull/1286).
 */
package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.star.container.Shortcut;

import java.io.File;

/**
 * Detects whether an installed Epic game ships Epic Online Services SDK files.
 * Used by the library list and detail page to render an "EOS" badge so users
 * can see at a glance which games will use Bannerlator's EOS auth integration.
 *
 * Detection runs at install completion and lazily on first detail-page open for
 * games installed before this feature existed (so upgraders don't have to
 * reinstall).
 *
 * Scan root = the install dir stored at {@code epic_dir_<appName>} in bh_epic_prefs.
 *
 * Storage in {@code bh_epic_prefs}:
 *   {@code epic_uses_eos_<appName>} (boolean) — true if EOS markers found
 *   {@code epic_eos_scanned_<appName>} (boolean) — true once scan has been run
 */
public final class EpicEosDetector {

    private static final String TAG = "BH_EPIC_EOS";

    /**
     * Files whose presence definitively indicates the game uses EOS. Any one of
     * these in the install dir tree is enough to mark the game.
     */
    private static final String[] EOS_MARKERS = {
            "EOSSDK-Win64-Shipping.dll",
            "EOSSDK-Win32-Shipping.dll",
            "EOSOVH-Win64-Shipping.dll",
            "EOSBootstrapper.exe",
    };

    /** Limits the recursive walk so a giant install can't stall a scan. */
    private static final int MAX_FILES_SCANNED = 50_000;

    private EpicEosDetector() {}

    /**
     * Has this appName been scanned at least once?
     */
    public static boolean hasBeenScanned(Context ctx, String appName) {
        if (appName == null || appName.isEmpty()) return false;
        return ctx.getSharedPreferences("bh_epic_prefs", 0)
                .getBoolean("epic_eos_scanned_" + appName, false);
    }

    /**
     * Read cached EOS flag. Returns false if never scanned, or scanned and no
     * markers found. Callers should use {@link #hasBeenScanned} to distinguish
     * "definitely not EOS" from "haven't checked yet".
     */
    public static boolean isEosCached(Context ctx, String appName) {
        if (appName == null || appName.isEmpty()) return false;
        return ctx.getSharedPreferences("bh_epic_prefs", 0)
                .getBoolean("epic_uses_eos_" + appName, false);
    }

    /**
     * Synchronously walk the install dir, write the result. Runs the scan even
     * if a previous result is cached (use {@link #scanIfNeeded} for the lazy
     * path). Bounded by {@link #MAX_FILES_SCANNED}; falls back to false if hit.
     */
    public static boolean scan(Context ctx, String appName, File installDir) {
        if (ctx == null || appName == null || appName.isEmpty()) return false;
        boolean usesEos = false;
        if (installDir != null && installDir.isDirectory()) {
            int[] count = {0};
            usesEos = walk(installDir, count);
        }
        SharedPreferences sp = ctx.getSharedPreferences("bh_epic_prefs", 0);
        sp.edit()
                .putBoolean("epic_uses_eos_" + appName, usesEos)
                .putBoolean("epic_eos_scanned_" + appName, true)
                .apply();
        Log.i(TAG, "scan(" + appName + ") at " + (installDir == null ? "<null>" : installDir.getAbsolutePath())
                + " → usesEos=" + usesEos);
        return usesEos;
    }

    /**
     * Convenience: resolve the install dir from {@code epic_dir_<appName>} and scan.
     */
    public static boolean scanFromPrefs(Context ctx, String appName) {
        if (ctx == null || appName == null || appName.isEmpty()) return false;
        String dir = ctx.getSharedPreferences("bh_epic_prefs", 0)
                .getString("epic_dir_" + appName, null);
        return scan(ctx, appName, dir != null && !dir.isEmpty() ? new File(dir) : null);
    }

    /**
     * Run the scan asynchronously. Useful from UI threads where an Activity
     * doesn't want to block on filesystem walk.
     */
    public static void scanAsync(Context ctx, String appName, File installDir, Runnable onDone) {
        new Thread(() -> {
            try {
                scan(ctx, appName, installDir);
            } catch (Throwable t) {
                Log.w(TAG, "scanAsync failed for " + appName, t);
            }
            if (onDone != null) {
                try { onDone.run(); } catch (Throwable ignored) {}
            }
        }, "bh-eos-scan-" + appName).start();
    }

    /**
     * Lazy-scan: only runs if no scan has been recorded yet for this appName.
     * Returns immediately on cache hit. Use this from detail-page open to give
     * upgraders' previously-installed games a flag without reinstalling.
     */
    public static void scanIfNeeded(Context ctx, String appName, File installDir, Runnable onDone) {
        if (hasBeenScanned(ctx, appName)) {
            if (onDone != null) onDone.run();
            return;
        }
        scanAsync(ctx, appName, installDir, onDone);
    }

    /** Depth cap for the generalized shortcut scan (game dir → subfolders). */
    private static final int SHORTCUT_SCAN_DEPTH = 3;

    /**
     * Generalized, source-agnostic EOS detection for a shortcut of ANY store origin
     * (custom / Steam / GOG / Amazon / Epic). Resolves the game directory from the
     * shortcut's exe path ({@code shortcut.path}), scans its parent tree (bounded depth
     * {@link #SHORTCUT_SCAN_DEPTH}) for the EOS markers, and caches the result on the
     * shortcut as the {@code eos} extra (1/0) via putExtra+saveData.
     *
     * Computed ONCE: if the {@code eos} extra already exists, this returns immediately
     * without touching the filesystem (call {@code onResolved} synchronously). Otherwise
     * the scan + persist run on a background thread and {@code onResolved} fires when done.
     *
     * This is IDENTIFICATION only — the badge shows for any source; the Epic auth-arg
     * injection stays gated on {@code storeSource=epic && epicEos!=0}.
     */
    public static void scanShortcutIfNeeded(Shortcut shortcut, Runnable onResolved) {
        if (shortcut == null) { if (onResolved != null) onResolved.run(); return; }
        if (shortcut.hasExtra("eos")) { if (onResolved != null) onResolved.run(); return; }
        final String exePath = shortcut.path;
        new Thread(() -> {
            boolean usesEos = false;
            try {
                if (exePath != null && !exePath.isEmpty()) {
                    File root = new File(exePath).getParentFile();
                    if (root != null && root.isDirectory()) {
                        int[] count = {0};
                        usesEos = walkDepth(root, count, SHORTCUT_SCAN_DEPTH);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "scanShortcut walk failed for " + exePath, t);
            }
            try {
                shortcut.putExtra("eos", usesEos ? "1" : "0");
                shortcut.saveData();
            } catch (Throwable t) {
                Log.w(TAG, "persist eos extra failed for " + exePath, t);
            }
            if (onResolved != null) {
                try { onResolved.run(); } catch (Throwable ignored) {}
            }
        }, "eos-shortcut-scan").start();
    }

    private static boolean walkDepth(File dir, int[] count, int depth) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File f : entries) {
            if (count[0]++ > MAX_FILES_SCANNED) return false;
            if (f.isDirectory()) {
                if (depth > 0 && walkDepth(f, count, depth - 1)) return true;
            } else {
                String name = f.getName();
                for (String marker : EOS_MARKERS) {
                    if (marker.equalsIgnoreCase(name)) return true;
                }
            }
        }
        return false;
    }

    private static boolean walk(File dir, int[] count) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File f : entries) {
            if (count[0]++ > MAX_FILES_SCANNED) return false;
            if (f.isDirectory()) {
                if (walk(f, count)) return true;
            } else {
                String name = f.getName();
                for (String marker : EOS_MARKERS) {
                    if (marker.equalsIgnoreCase(name)) return true;
                }
            }
        }
        return false;
    }
}
