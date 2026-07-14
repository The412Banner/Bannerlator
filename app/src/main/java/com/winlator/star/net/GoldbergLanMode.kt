package com.winlator.star.net

import android.content.Context
import android.os.Looper
import android.util.Log
import com.winlator.star.store.GoldbergMode
import com.winlator.star.store.GoldbergPatcher
import com.winlator.star.store.SteamPrefs
import java.io.File

/**
 * "Goldberg LAN mode" — pairs the shipped Goldberg (gbe_fork) Steam-emulator patch with the LAN
 * overlay for Steamworks-multiplayer games.
 *
 * When a Goldberg-patched game hosts/joins a LAN room, we drop a `custom_broadcasts.txt` (one IP
 * per line) into the game's Goldberg `steam_settings` folder(s) containing the PEER's deterministic
 * overlay vIP. Goldberg then sends its Steam-LAN discovery as directed UNICAST to that vIP (the
 * overlay's proven LSW_UNICAST path) instead of relying on flaky/silent broadcast.
 *
 * Overlay vIPs are fixed by role: host = 10.99.0.1, client = 10.99.0.2 (see [LanOverlay] /
 * LanOverlayVpnService). So the host writes the client's vIP and the client writes the host's.
 *
 * BEST-EFFORT by design: this only helps games whose multiplayer actually runs over Steam
 * networking (ISteamMatchmaking / ISteamNetworking) through the swapped steam_api. Patched does NOT
 * guarantee it works — the UI copy must say so.
 *
 * All entry points are idempotent, do their file I/O off the main thread, no-op cleanly when a game
 * isn't patched / dirs are missing, and NEVER throw to the caller.
 */
object GoldbergLanMode {

    private const val TAG = "BH_GOLDBERG_LAN"
    private const val BROADCASTS_FILE = "custom_broadcasts.txt"

    // Deterministic overlay vIPs — must match LanOverlayVpnService (10.99.0.1 host / 10.99.0.2 client).
    private const val HOST_VIP = "10.99.0.1"
    private const val CLIENT_VIP = "10.99.0.2"

    /** True when [appId] has been Goldberg-patched (any non-OFF tier) → the toggle is offered. */
    @JvmStatic
    fun isEligible(context: Context, appId: Int): Boolean {
        return try {
            SteamPrefs.init(context.applicationContext)
            SteamPrefs.getGoldbergMode(appId) != GoldbergMode.OFF
        } catch (e: Exception) {
            Log.w(TAG, "isEligible failed for appId=$appId", e)
            false
        }
    }

    /**
     * Writes `custom_broadcasts.txt` = the PEER's overlay vIP into every Goldberg `steam_settings`
     * folder for this game. [role] is [LanOverlay.ROLE_HOST] or [LanOverlay.ROLE_CLIENT]; host writes
     * the client vIP and vice-versa. Idempotent (only rewrites when content differs). No-op if the
     * game isn't patched or no target dirs resolve.
     */
    @JvmStatic
    fun enable(context: Context, appId: Int, installDir: String, gameName: String, role: Int) {
        runOffMain {
            try {
                SteamPrefs.init(context.applicationContext)
                val mode = SteamPrefs.getGoldbergMode(appId)
                if (mode == GoldbergMode.OFF) {
                    Log.i(TAG, "enable: appId=$appId not Goldberg-patched — no-op.")
                    return@runOffMain
                }
                val peerVip = if (role == LanOverlay.ROLE_HOST) CLIENT_VIP else HOST_VIP
                val content = "$peerVip\n"
                val dirs = broadcastDirs(installDir, gameName, mode)
                if (dirs.isEmpty()) {
                    Log.w(TAG, "enable: no steam_settings targets for appId=$appId at $installDir")
                    return@runOffMain
                }
                for (dir in dirs) {
                    try {
                        if (!dir.isDirectory && !dir.mkdirs()) {
                            Log.w(TAG, "enable: couldn't create ${dir.absolutePath}")
                            continue
                        }
                        val f = File(dir, BROADCASTS_FILE)
                        // Idempotent — leave the rest of steam_settings untouched.
                        if (!f.isFile || runCatching { f.readText() }.getOrNull() != content) {
                            f.writeText(content)
                        }
                        Log.i(TAG, "enable: $BROADCASTS_FILE=$peerVip → ${f.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "enable: failed writing into ${dir.absolutePath}", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "enable failed for appId=$appId", e)
            }
        }
    }

    /**
     * Removes the `custom_broadcasts.txt` files we wrote (leaving the rest of `steam_settings`
     * untouched). Idempotent; safe to call when nothing was ever written. Note: on teardown the game's
     * Goldberg mode may already be OFF, so we resolve dirs across all plausible modes.
     */
    @JvmStatic
    fun disable(context: Context, appId: Int, installDir: String, gameName: String) {
        runOffMain {
            try {
                // Resolve for COLDCLIENT too (superset of the dll-adjacent dirs) so we also catch the
                // exe-dir drop even if the persisted mode changed/cleared since enable().
                val dirs = broadcastDirs(installDir, gameName, GoldbergMode.COLDCLIENT)
                for (dir in dirs) {
                    try {
                        val f = File(dir, BROADCASTS_FILE)
                        if (f.isFile && f.delete()) Log.i(TAG, "disable: removed ${f.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "disable: failed removing in ${dir.absolutePath}", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "disable failed for appId=$appId", e)
            }
        }
    }

    /**
     * The `steam_settings` dirs to drop the file into: one beside each steam_api dll, plus — for
     * COLDCLIENT — one beside the game exe (loader lives there). Delegated to [GoldbergPatcher] so the
     * store-package resolution (analyze + choosePrimaryExe) stays in one place.
     *
     * TODO confirm gbe_fork read location per mode (wine-compat). We write to all plausible ones,
     * which is harmless if a location is never read.
     */
    private fun broadcastDirs(installDir: String, gameName: String, mode: GoldbergMode): List<File> {
        val root = File(installDir)
        if (!root.isDirectory) return emptyList()
        return GoldbergPatcher.steamSettingsDirs(root, gameName, mode)
    }

    /** Run [block] off the main thread; if already off-main, run inline. */
    private fun runOffMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread(block, "goldberg-lan").start()
        } else {
            block()
        }
    }
}
