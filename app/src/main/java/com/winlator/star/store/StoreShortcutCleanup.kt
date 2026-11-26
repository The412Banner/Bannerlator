package com.winlator.star.store

import android.content.Context
import com.winlator.star.store.download.Store
import java.io.File

/**
 * Removes an uninstalled game's shortcuts (the ones [DownloadShortcutMatch] ties to it) from every container, off the UI thread, the
 * same way the game's own Remove does (the .desktop file, its .lnk, the launcher pin), then lets every game list reload. Every
 * uninstall uses it: the Download Manager's and each store's own (11-15: uninstalling from a store page left the game's shortcut in
 * Games, pointing at deleted files).
 */
internal object StoreShortcutCleanup {
    /** [mode] [ShortcutRemoval.BY_ID_ONLY] matches by the game's store id alone (its folder still stands), ALL also by the install folder. */
    fun remove(ctx: Context, store: Store, id: String, installPath: String?, mode: ShortcutRemoval) {
        if (mode == ShortcutRemoval.NONE) return
        val app = ctx.applicationContext
        Thread {
            val removed = runCatching {
                // Both sides as real paths: a shortcut may reach the folder another way (/data/user/0 vs /data/data).
                val folder = installPath?.let(::canon)
                var count = 0
                for (s in com.winlator.star.container.ContainerManager(app).loadShortcuts()) {
                    val mine = if (mode == ShortcutRemoval.BY_ID_ONLY) {
                        DownloadShortcutMatch.belongsById(store, id) { s.getExtra(it) }
                    } else {
                        // The exe as an Android path: a shortcut may hold a drive path (A:\..., Z:\...) that its container maps somewhere.
                        val exeAndroid = if (s.path.startsWith("/")) s.path
                        else runCatching { com.winlator.star.core.CopyGameToDriveC.parse(s).exeAndroid?.absolutePath }.getOrNull()
                        DownloadShortcutMatch.belongs(store, id, folder, exeAndroid?.let(::canon)) { s.getExtra(it) }
                    }
                    if (!mine) continue
                    val lnk = File(s.file.path.substringBeforeLast('.') + ".lnk")
                    if (s.file.delete()) {
                        if (lnk.exists()) lnk.delete()
                        com.winlator.star.ui.screens.ShortcutsViewModel.disableOnScreen(app, s)
                        count++
                    }
                }
                count
            }.getOrDefault(0)
            if (removed > 0) com.winlator.star.ui.screens.ShortcutsViewModel.notifyContainersChanged()
        }.start()
    }

    /**
     * For a store screen's uninstall that deleted the game folder with [StoreInstallLocations.safeDelete]: [gone] = the folder is gone;
     * [refused] = it was kept on purpose (not a game folder of that store, e.g. the user's own imported folder), so the shortcuts whose
     * exes are in it stay. A failed delete removes none.
     */
    fun afterDelete(ctx: Context, store: Store, id: String, installPath: String?, gone: Boolean, refused: Boolean) =
        remove(ctx, store, id, installPath, shortcutRemovalFor(gone || refused, refused, imported = false))

    /** True when [dir] would be kept by [StoreInstallLocations.safeDelete] (it exists and is not a game folder of [store]). */
    fun keptBySafeDelete(ctx: Context, store: StoreInstallLocations.Store, dir: File?): Boolean =
        dir != null && dir.exists() && !StoreInstallLocations.isGameFolder(ctx, store, dir)

    private fun canon(p: String): String = runCatching { File(p).canonicalPath }.getOrDefault(p)
}
