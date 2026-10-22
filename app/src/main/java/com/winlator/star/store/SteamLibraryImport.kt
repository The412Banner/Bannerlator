package com.winlator.star.store

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Picks up games that other Steam clients already installed into one of the user's libraries.
 *
 * For every configured library the `<steamapps>/appmanifest_*.acf` files are read; an app whose manifest
 * says it is installed (StateFlags bit 4), whose `common/<installdir>` folder exists, and which is in the
 * games database (owned by the active account) but not yet marked installed is marked installed at that
 * real folder — the same [SteamDatabase.markInstalled] call a download makes — so it shows as installed
 * and launches from where it is, with no re-download.
 *
 * Nothing is created for an app the database does not know, nothing already installed is touched, and
 * no existing file is modified (the only file written is the app's own build stamp, and only when the
 * folder has none, so the update check has something to compare). Cheap by construction: the app id comes
 * from the manifest's file name, so only manifests that can matter are opened. Runs off the main thread.
 */
object SteamLibraryImport {
    private const val TAG = "SteamLibraryImport"

    private val MANIFEST_NAME = Regex("appmanifest_(\\d+)\\.acf", RegexOption.IGNORE_CASE)
    private const val BUILD_MARKER = ".bannerlator_build"

    private val running = AtomicBoolean(false)

    private const val IGNORE_PREFS = "steam_library_import_ignore"
    private const val IGNORE_KEY = "app_ids"
    private const val IMPORTED_KEY = "imported_app_ids"

    /**
     * Stops the import from marking [appId] installed again. Called when the user cancels and deletes a
     * download of a game whose folder another Steam client owns: the folder and its manifest stay on disk, and
     * without this the next Library visit would resurrect the game. Cleared by [clearIgnore] when the user
     * installs it again on purpose. Never throws.
     */
    @JvmStatic
    fun ignore(ctx: Context, appId: Int) = editIgnored(ctx) { it.add(appId.toString()) }

    /** Lets the import see [appId] again (a fresh install of it was started). Never throws. */
    @JvmStatic
    fun clearIgnore(ctx: Context, appId: Int) = editIgnored(ctx) { it.remove(appId.toString()) }

    /**
     * True when [appId] was marked installed by this import — its files are another Steam client's, not a
     * download made here — so uninstalling it must leave the folder and the appmanifest alone. Never throws.
     */
    @JvmStatic
    fun isImported(ctx: Context, appId: Int): Boolean = idSet(ctx, IMPORTED_KEY).contains(appId.toString())

    /**
     * Uninstall bookkeeping for an imported game: forgets that it was imported and keeps the import from
     * marking it installed again (the folder stays on disk). The caller still marks it uninstalled. Never throws.
     */
    @JvmStatic
    fun releaseImported(ctx: Context, appId: Int) {
        editSet(ctx, IMPORTED_KEY) { it.remove(appId.toString()) }
        ignore(ctx, appId)
    }

    private fun ignoredIds(ctx: Context): Set<String> = idSet(ctx, IGNORE_KEY)

    private fun editIgnored(ctx: Context, change: (MutableSet<String>) -> Unit) = editSet(ctx, IGNORE_KEY, change)

    private fun idSet(ctx: Context, key: String): Set<String> = try {
        ctx.applicationContext.getSharedPreferences(IGNORE_PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet()) ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun editSet(ctx: Context, key: String, change: (MutableSet<String>) -> Unit) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(IGNORE_PREFS, Context.MODE_PRIVATE)
            // A StringSet returned by getStringSet must be copied before it is changed.
            val set = HashSet(sp.getStringSet(key, emptySet()) ?: emptySet())
            val before = set.size
            change(set)
            if (set.size != before) sp.edit().putStringSet(key, set).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "import list update failed ($key): ${t.message}")
        }
    }

    /**
     * Scans every configured library and marks the games found installed. Returns how many games were
     * newly marked. Blocking (file and database reads) — call it off the main thread. A concurrent call
     * returns 0 immediately instead of scanning twice. Never throws.
     */
    @JvmStatic
    fun importInstalled(ctx: Context): Int {
        if (!running.compareAndSet(false, true)) return 0
        try {
            return scan(ctx.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "library import failed: ${t.message}")
            return 0
        } finally {
            running.set(false)
        }
    }

    /** [importInstalled] on a short-lived worker thread; [onDone] (worker thread) gets the count when it is > 0. */
    @JvmStatic
    fun importInstalledAsync(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread({
            val n = importInstalled(app)
            if (n > 0) onDone?.invoke(n)
        }, "SteamLibraryImport").apply { isDaemon = true }.start()
    }

    private fun scan(app: Context): Int {
        val libs = SteamLibraryLocations.libraries(app)
        if (libs.isEmpty()) return 0
        val db = SteamDatabase.getInstance(app)
        val installedIds = HashSet<Int>()
        for (row in db.getInstalledGames()) installedIds.add(row.appId)
        val knownIds = HashSet<Int>(db.getAllAppIds())
        // Games the downloader owns right now (running, queued or paused) are not "installed" yet, and games
        // the user cancelled on a folder another client owns must not come back (see [ignore]).
        val busyIds = HashSet<Int>()
        for (row in db.getActiveDownloads()) busyIds.add(row.appId)
        busyIds.addAll(SteamDepotDownloader.activeDownloads.keys)
        for (s in ignoredIds(app)) s.toIntOrNull()?.let { busyIds.add(it) }

        var imported = 0
        for (lib in libs) {
            val root = File(lib.path)
            if (!root.isDirectory) continue
            val steamapps = SteamInstallPaths.steamappsDirOf(root)
            val manifests = steamapps.listFiles { f -> f.isFile && MANIFEST_NAME.matches(f.name) } ?: continue
            for (manifest in manifests) {
                val appId = MANIFEST_NAME.matchEntire(manifest.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (appId in installedIds || appId !in knownIds) continue
                if (appId in busyIds || DownloadQueue.isQueued(appId)) continue
                try {
                    if (importOne(app, db, steamapps, manifest, appId)) {
                        installedIds.add(appId)
                        imported++
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "could not import ${manifest.name}: ${t.message}")
                }
            }
        }
        if (imported > 0) {
            try { SteamRepository.getInstance().invalidateGameCache() } catch (_: Throwable) {}
            Log.i(TAG, "marked $imported game(s) found in the libraries as installed")
        }
        return imported
    }

    private fun importOne(app: Context, db: SteamDatabase, steamapps: File, manifest: File, appId: Int): Boolean {
        val info = SteamAppManifest.readInfo(manifest) ?: return false
        if (info.appId != 0 && info.appId != appId) return false
        if (!info.isInstalled) return false
        val folder = File(File(steamapps, SteamInstallPaths.COMMON), info.installDir)
        if (!folder.isDirectory) return false
        val row = db.getGame(appId) ?: return false
        if (row.isInstalled) return false

        // size_bytes means the Steam manifest install size everywhere else: keep the synced one.
        val size = if (row.sizeBytes > 0L) row.sizeBytes else info.sizeOnDisk
        db.markInstalled(appId, folder.absolutePath, size)
        editSet(app, IMPORTED_KEY) { it.add(appId.toString()) }
        // The update check compares the folder's build stamp with the live build; stamp it from the
        // manifest, but only when the folder has no stamp of its own.
        if (info.buildId > 0L && !File(folder, BUILD_MARKER).exists()) {
            SteamGameUpdater.recordKnownBuild(folder, info.branch, info.buildId)
        }
        Log.i(TAG, "imported '${row.name}' ($appId) from ${folder.absolutePath}")
        return true
    }
}
