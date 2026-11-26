package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.Store

/**
 * Downloads & Library keeps its own saved list of installed games. A game uninstalled by a path that did not clear
 * that list (older builds' Steam page uninstall) stayed listed as "Installed". [dropStale] removes the rows whose
 * store says the game is not installed any more. Xbox rows are left alone.
 */
object StoreLibraryReconcile {
    private const val TAG = "StoreLibraryReconcile"

    /** Blocking (database and preference reads): call off the main thread. */
    fun dropStale(ctx: Context) {
        val app = ctx.applicationContext
        val rows = DownloadRegistry.installedNow()
        if (rows.isEmpty()) return
        val amazon = app.getSharedPreferences("bh_amazon_prefs", Context.MODE_PRIVATE)
        var dropped = 0
        for (row in rows) {
            val installed = try {
                when (row.store) {
                    Store.STEAM -> row.id.toIntOrNull()?.let { SteamDatabase.getInstance(app).getGame(it)?.isInstalled } ?: true
                    Store.EPIC -> EpicInstallState.isInstalled(app, row.id)
                    Store.GOG -> GogInstallState.isInstalled(app, row.id)
                    Store.AMAZON -> !amazon.getString("amazon_exe_${row.id}", null).isNullOrBlank()
                    Store.XBOX -> true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "install check failed for ${row.key}", t)
                true
            }
            if (!installed) {
                DownloadRegistry.removeLibraryEntry(row.key)
                dropped++
                Log.i(TAG, "dropped ${row.key} (${row.name}): not installed in its store")
            }
        }
        if (dropped > 0) Log.i(TAG, "dropped $dropped stale Installed row(s)")
    }
}
