package com.winlator.star.store

import android.content.Context

/**
 * Single owner of GOG's native install record (the `bh_gog_prefs` keys that encode "installed").
 * GOG has no DB — install-state lives entirely in these prefs, and THREE surfaces read them: the
 * store list ([GogGamesActivity]), the detail page ([GogGameDetailActivity.refreshActionState],
 * `gog_exe_ != null && gog_dir_ != null`) and the seed ([GogLibrarySync]). So every uninstall —
 * from the store list, the detail page, OR the cross-store Download Manager — must clear the SAME
 * keys, or a surface goes stale. Direct mirror of [AmazonInstallState] (the Amazon-side analogue
 * of Steam clearing its DB via `SteamRepository.markUninstalled`).
 *
 * Lives on the GOG side ON PURPOSE so the store-agnostic `download` package keeps importing zero
 * store engines — the Manager Activity / hooks call INTO this, never the reverse.
 */
object GogInstallState {

    private const val PREFS_NAME = "bh_gog_prefs"

    /**
     * Remove every per-game install record for [gameId]: the launch exe, the install dir, the
     * cached install size, the installed build id, and the (legacy) cover key. Idempotent —
     * removing an absent key is a no-op — so it's safe from any uninstall path (or the seed
     * self-heal). Deliberately LEAVES the user's cloud-save folder (`gog_save_dir_`) and owned-DLC
     * list (`gog_dlcs_`) alone: those are user data / entitlement, not install state. The
     * installed-DLC records of the game (`gog_dlc_installed_`, `gog_dlc_files_`) go with it.
     */
    fun purge(ctx: Context, gameId: String) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .remove("gog_exe_$gameId")
            .remove("gog_dir_$gameId")
            .remove("gog_size_$gameId")
            .remove("gog_build_$gameId")
            .remove("gog_cover_$gameId")
        for (k in prefs.all.keys) {
            if (k.startsWith("gog_dlc_installed_${gameId}_") || k.startsWith("gog_dlc_files_${gameId}_")) editor.remove(k)
        }
        editor.apply()
    }

    /**
     * Disk-truth "is this game installed?" — the SAME record the detail page reads
     * ([GogGameDetailActivity.refreshActionState]: `gog_exe_ != null && gog_dir_ != null`). Lets the
     * store list resolve install-state on a COLD start (no live download this session), instead of
     * only trusting the in-memory download map. [purge] clears both keys, so an uninstalled game
     * flips back to false.
     */
    fun isInstalled(ctx: Context, gameId: String): Boolean {
        val prefs = ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("gog_exe_$gameId", null) != null &&
            prefs.getString("gog_dir_$gameId", null) != null
    }
}
