/*
 * Epic Online Services (EOS) Friends Overlay provisioning for Bannerlator.
 *
 * Credits: The overlay provisioning MECHANISM — download Epic's real overlay
 * component ("EpicOnlineServicesOverlay") into the wine prefix and point the
 * bundled EOS SDK at it via a single HKCU registry value — is referenced from
 * The GameNative Team's work (https://github.com/utkarshdalal/GameNative), which
 * is in turn derived from Legendary's legendary/lfs/eos.py. The Epic app IDs, the
 * registry key/path, and the EOSOVH-Win64-Shipping.dll name are public facts from
 * Epic / Legendary. This is an original implementation — no GameNative Kotlin
 * structure or comments were copied. GPL-compatible.
 *
 * Reference: https://github.com/derrod/legendary — legendary/lfs/eos.py
 * Reference: https://github.com/utkarshdalal/GameNative
 *
 * Phase 3 of Bannerlator's Epic/EOS support: this is PROVISION-ONLY. We render
 * nothing and trigger nothing in software. We drop Epic's own signed overlay
 * component into the prefix and write ONE registry pointer; the game's bundled
 * EOS SDK loads it and owns the hotkey (default Shift+F3) against the user's real
 * Epic login. Not a bypass.
 */
package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import com.winlator.star.core.WineRegistryEditor;

import java.io.File;

/**
 * Installs the Epic EOS overlay component into a container's wine prefix and wires
 * the single HKCU pointer the bundled EOS SDK reads to find it.
 *
 * <ul>
 *   <li>{@link #ensureOverlayInstalled} — idempotent CDN download of the overlay
 *       component (skips when the DLL is already on disk). Reuses the existing Epic
 *       manifest/chunk machinery ({@link EpicApiClient#getManifestApiJson} +
 *       {@link EpicDownloadManager#install}).</li>
 *   <li>{@link #writeRegistry} — writes {@code HKCU\Software\Epic Games\EOS}
 *       {@code OverlayPath} pointing at the installed folder.</li>
 *   <li>{@link #stripRegistry} — removes that pointer when the per-shortcut toggle
 *       is off, so a disabled overlay leaves no stale key behind.</li>
 * </ul>
 *
 * All methods perform synchronous file / registry / network I/O and MUST be called
 * off the main thread — they run on the background launch worker in
 * {@code XServerDisplayActivity} (the same thread the Epic launch-arg fetch uses).
 */
public final class EpicOverlayManager {

    private static final String TAG = "BH_EPIC_OVERLAY";

    // ── Epic "EpicOnlineServicesOverlay" app identifiers (public facts, from Legendary) ──
    private static final String OVERLAY_APP           = "98bc04bc842e4906993fd6d6644ffb8d";
    private static final String OVERLAY_NAMESPACE     = "302e5ede476149b1bc3e4fe6ae45e50e";
    private static final String OVERLAY_CATALOG_ITEM  = "cc15684f44d849e89e9bf4cec0508b68";

    // ── On-disk / in-guest overlay location ──────────────────────────────────────
    /** Prefix-relative directory the overlay component installs into. */
    private static final String OVERLAY_SUBDIR =
            "drive_c/Program Files (x86)/Epic Games/Launcher/Portal/Extras/Overlay";
    /** The overlay DLL that must be present for the install to count as done. */
    private static final String OVERLAY_DLL = "EOSOVH-Win64-Shipping.dll";
    /** Windows-side path the EOS SDK is pointed at (dosdevices C: → drive_c). */
    private static final String OVERLAY_WIN_PATH =
            "C:\\Program Files (x86)\\Epic Games\\Launcher\\Portal\\Extras\\Overlay";

    // ── The ONE registry pointer the bundled EOS SDK reads (HKCU via user.reg) ────
    private static final String EOS_KEY        = "Software\\Epic Games\\EOS";
    private static final String OVERLAY_VALUE  = "OverlayPath";

    private EpicOverlayManager() {}

    /** Absolute directory the overlay component installs into for {@code prefixDir}. */
    private static File overlayDir(File prefixDir) {
        return new File(prefixDir, OVERLAY_SUBDIR);
    }

    /**
     * Ensure Epic's overlay component is present in {@code prefixDir}. If the overlay
     * DLL already exists (non-empty) this is a no-op that returns {@code true}. Otherwise
     * it fetches the overlay app's manifest from Epic's launcher assets API and
     * chunk-downloads it (Live CDN only — Epic's binaries are NOT bundled) into the
     * overlay folder.
     *
     * @return {@code true} when the overlay DLL is present after the call.
     */
    public static boolean ensureOverlayInstalled(Context ctx, File prefixDir) {
        if (ctx == null || prefixDir == null) return false;
        try {
            File dir = overlayDir(prefixDir);
            File dll = new File(dir, OVERLAY_DLL);
            if (dll.isFile() && dll.length() > 0) {
                Log.i(TAG, "overlay already installed: " + dll.getAbsolutePath());
                return true;
            }

            String token = EpicCredentialStore.getValidAccessToken(ctx);
            if (token == null || token.isEmpty()) {
                Log.w(TAG, "no valid Epic access token; cannot provision overlay (user not logged in)");
                return false;
            }

            String manifestApiJson = EpicApiClient.getManifestApiJson(
                    token, OVERLAY_NAMESPACE, OVERLAY_CATALOG_ITEM, OVERLAY_APP);
            if (manifestApiJson == null) {
                Log.w(TAG, "overlay manifest API fetch failed");
                return false;
            }

            dir.mkdirs();
            Log.i(TAG, "downloading Epic overlay component into " + dir.getAbsolutePath());
            // installTags == null → download the whole (small) overlay component. Reuses the same
            // manifest-parse + parallel chunk download + file-assemble path the Epic store uses for games.
            boolean ok = EpicDownloadManager.install(ctx, manifestApiJson, token, dir.getAbsolutePath(), null);

            boolean present = dll.isFile() && dll.length() > 0;
            if (!ok || !present) {
                Log.w(TAG, "overlay install did not produce " + OVERLAY_DLL + " (installOk=" + ok + ")");
                return false;
            }
            Log.i(TAG, "overlay component ready: " + dll.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ensureOverlayInstalled failed", t);
            return false;
        }
    }

    /**
     * Point the bundled EOS SDK at the installed overlay by writing
     * {@code HKCU\Software\Epic Games\EOS} {@code OverlayPath} in the prefix's
     * {@code user.reg}. This is the ONLY registry key involved. Idempotent.
     */
    public static void writeRegistry(File prefixDir) {
        if (prefixDir == null) return;
        try {
            File userRegFile = new File(prefixDir, "user.reg");
            try (WineRegistryEditor editor = new WineRegistryEditor(userRegFile)) {
                editor.setCreateKeyIfNotExist(true);
                editor.setStringValue(EOS_KEY, OVERLAY_VALUE, OVERLAY_WIN_PATH);
            }
            Log.i(TAG, "wrote HKCU\\" + EOS_KEY + " " + OVERLAY_VALUE + "=" + OVERLAY_WIN_PATH);
        } catch (Throwable t) {
            Log.w(TAG, "writeRegistry failed", t);
        }
    }

    /**
     * Remove the {@code OverlayPath} pointer so a disabled overlay leaves no stale key.
     * No-op when the value / prefix isn't there. Best-effort; never throws.
     */
    public static void stripRegistry(File prefixDir) {
        if (prefixDir == null) return;
        try {
            File userRegFile = new File(prefixDir, "user.reg");
            if (!userRegFile.isFile()) return;
            try (WineRegistryEditor editor = new WineRegistryEditor(userRegFile)) {
                editor.removeValue(EOS_KEY, OVERLAY_VALUE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stripRegistry failed", t);
        }
    }
}
