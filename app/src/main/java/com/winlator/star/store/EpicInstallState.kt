package com.winlator.star.store

import android.content.Context

/**
 * Single owner of Epic's native install record (the `bh_epic_prefs` keys that encode "installed").
 * Epic has no DB — install-state lives entirely in these prefs, and THREE surfaces read them: the
 * store list ([EpicGamesActivity]), the detail page ([EpicGameDetailActivity.refreshActionState],
 * `epic_exe_ != null`) and the seed ([EpicLibrarySync]). So every uninstall — from the detail page
 * OR the cross-store Download Manager — must clear the SAME keys, or a surface goes stale. Direct
 * mirror of [AmazonInstallState] / [GogInstallState].
 *
 * Lives on the Epic side ON PURPOSE so the store-agnostic `download` package keeps importing zero
 * store engines — the Manager Activity / hooks call INTO this, never the reverse.
 */
object EpicInstallState {

    private const val PREFS_NAME = "bh_epic_prefs"

    /**
     * Remove every per-game install record for [appName]: the launch exe, the install dir, the
     * recorded manifest version, and the cached install size. Idempotent — removing an absent key
     * is a no-op — so it's safe from any uninstall path (or the seed self-heal). Deliberately
     * LEAVES the user's cloud-save folder (`epic_save_dir_`) alone: that's user data, not install
     * state.
     */
    fun purge(ctx: Context, appName: String) {
        ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("epic_exe_$appName")
            .remove("epic_dir_$appName")
            .remove("epic_manifest_version_$appName")
            .remove("epic_size_$appName")
            .apply()
    }

    /**
     * Record [manifestJson]'s versionId (the manifest API response) as [appName]'s installed version,
     * after an install, an update or a verify that matched the files. Nothing is written when the
     * response has no versionId.
     */
    fun rememberVersion(ctx: Context, appName: String, manifestJson: String?) {
        if (manifestJson.isNullOrEmpty()) return
        val vid = try {
            org.json.JSONObject(manifestJson).optString("versionId", "")
        } catch (_: Exception) {
            ""
        }
        if (vid.isEmpty()) return
        ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("epic_manifest_version_$appName", vid)
            .apply()
    }

    /**
     * Disk-truth "is this game installed?" — the SAME record the detail page reads
     * ([EpicGameDetailActivity.refreshActionState]: `epic_exe_ != null`). The in-memory
     * [EpicGame.isInstalled] is never re-derived from prefs on a COLD start (the install-complete
     * path only updates the live download map, not the cached game), so the list must ask disk
     * directly or an already-installed game shows "Install". [purge] clears the key, so an
     * uninstalled game flips back to false.
     */
    fun isInstalled(ctx: Context, appName: String): Boolean =
        ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("epic_exe_$appName", null) != null
}
