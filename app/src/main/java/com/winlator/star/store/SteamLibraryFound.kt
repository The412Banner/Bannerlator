package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import com.winlator.star.store.blsteam.BlSteamEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Games installed into one of the user's Steam library folders that this app did not download.
 *
 * [SteamLibraryImport] marks the owned games installed in the Steam DB, which is what the Steam Library tab reads. Downloads is
 * this object's instead: every configured library's `<steamapps>/appmanifest_*.acf` is read ([SteamAppManifest.readInfo]), and
 * an app whose manifest says it is installed, whose `common/<installdir>` exists and whose folder is not this app's own (no
 * download or adopted marker) is remembered here and seeded into [DownloadRegistry] as an INSTALLED row with
 * [DownloadEntry.found] set — owned by the account or not. It then shows in Downloads exactly like a downloaded game — its Launch
 * writes the shortcut from the folder — while nothing here touches `steam_games` or the Steam Library tab.
 *
 * The list is a cache of what is on disk: [scan] rewrites it from the libraries, [seed] drops a record whose folder or manifest
 * has gone. A download of the same app takes the row over (it is the same registry key, and its folder gains the owned marker),
 * so the next scan drops the record. Nothing here touches the files: only [deleteInstall], the user's Uninstall, does, and only
 * inside a configured library.
 *
 * Blocking everywhere (manifest, folder and database reads): call off the main thread — [scanAsync] / [seedAsync].
 */
object SteamLibraryFound {
    private const val TAG = "SteamLibraryFound"

    private const val PREFS = "steam_library_found"
    private const val KEY = "games"
    private const val TYPES_KEY = "types"

    private val MANIFEST_NAME = Regex("appmanifest_(\\d+)\\.acf", RegexOption.IGNORE_CASE)

    /** Components Steam puts beside a game, not games: the shared redistributables and the Linux runtimes (scout/soldier/sniper). */
    private val COMPONENT_IDS = setOf(228980, 1070560, 1391110, 1628350)

    /**
     * Valve's own folders and names for its tools: the offline list [isGame] falls back on, which the library seeding
     * ([SteamLibrarySync.seedInstalled]) still uses. A game whose folder happens to start with the word (Proton Pulse) is kept,
     * because a version follows the name in a Proton build.
     */
    private fun isComponent(dir: String, name: String): Boolean {
        val d = dir.trim()
        val n = name.trim()
        return n.equals("Fex", ignoreCase = true) || n.startsWith("Fex ", ignoreCase = true) ||
            d.equals("Fex", ignoreCase = true) ||
            d.startsWith("SteamLinuxRuntime", ignoreCase = true) ||
            d.startsWith("Steamworks Shared", ignoreCase = true) ||
            (d.startsWith("Proton ", ignoreCase = true) && (d.getOrNull(7)?.isDigit() == true || d.startsWith("Proton -", ignoreCase = true)))
    }

    /** True when an app is a game rather than a tool, judged without the network: the component ids, folders and the few names. */
    internal fun isGame(appId: Int, installDir: String, name: String = ""): Boolean =
        appId !in COMPONENT_IDS && !isComponent(installDir, name)

    /**
     * Steam's own answer for [appId]: true for Game/Demo, false for Tool/Application, null when Steam is not logged on or the app
     * has no type yet. The authoritative check — no local guesswork.
     */
    private fun picsType(appId: Int): Boolean? {
        if (!BlSteamEngine.isLoggedOn()) return null
        val session = BlSteamEngine.session() ?: return null
        val json = runCatching { session.getPicsAppInfo(appId) }.getOrNull() ?: return null
        return runCatching {
            val info = JSONObject(json).optJSONObject("appinfo") ?: return@runCatching null
            val type = info.optJSONObject("common")?.optString("type").orEmpty()
            when {
                type.isBlank() -> null
                type.equals("Game", true) || type.equals("Demo", true) -> true
                else -> false
            }
        }.getOrNull()
    }

    /** Steam's type for [appId], remembered so a library scan asks once. SharedPreferences under this object's own file. */
    private fun cachedType(ctx: Context, appId: Int): Boolean? {
        val raw = prefs(ctx).getString(TYPES_KEY, "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (!o.has(appId.toString())) null else o.optBoolean(appId.toString())
        } catch (t: Throwable) {
            null
        }
    }

    private fun rememberType(ctx: Context, appId: Int, isGame: Boolean) {
        try {
            val p = prefs(ctx)
            val o = p.getString(TYPES_KEY, "")?.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            o.put(appId.toString(), isGame)
            p.edit().putString(TYPES_KEY, o.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "could not remember the type of $appId: ${t.message}")
        }
    }

    /** The type the library sync stored for an owned app, or null when there is no row/type. */
    private fun dbTypeGame(ctx: Context, appId: Int): Boolean? {
        val type = try {
            SteamDatabase.getInstance(ctx).getGame(appId)?.type?.trim()?.lowercase().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the type of $appId: ${t.message}")
            return null
        }
        return when {
            type.isBlank() -> null
            type == "game" || type == "demo" || appId in SteamRepository.LIBRARY_ALLOWLIST -> true
            else -> false
        }
    }

    /**
     * True when [appId] is known to be a game: the type remembered from Steam, the type the library sync stored (an owned game
     * stays visible offline), or Steam's live answer. False means either "not a game" or "not known yet" — the caller leaves the
     * app out of this scan, and the next scan asks again.
     */
    internal fun isGameBySteam(ctx: Context, appId: Int, installDir: String, name: String): Boolean {
        if (appId in COMPONENT_IDS) return false
        cachedType(ctx, appId)?.let { return it }
        dbTypeGame(ctx, appId)?.let { return it }
        val fromSteam = picsType(appId)
        if (fromSteam != null) {
            rememberType(ctx, appId, fromSteam)
            return fromSteam
        }
        return false
    }

    private val running = AtomicBoolean(false)

    /** When [scan] last ran: [scanIfStaleAsync] skips a non-forced call that comes within a minute of it. */
    @Volatile private var lastScanMs = 0L

    /** One game found in a library: [installDir] is its folder (`…/steamapps/common/<name>`), absolute. */
    data class Game(
        val appId: Int,
        val name: String,
        val installDir: String,
        val sizeOnDisk: Long,
    )

    // ── Scanning ─────────────────────────────────────────────────────────────────────────────

    /**
     * Reads every configured library, remembers the games found in them and seeds their Downloads rows. Blocking: call off the
     * main thread. A concurrent call returns at once instead of scanning twice.
     */
    @JvmStatic
    fun scan(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        try {
            // Owned games another client installed are only marked installed by the library import, which otherwise runs on the
            // Steam Library tab alone: a Downloads open must finish them too, or the Steam tab keeps them uninstalled and they
            // never launch from where they are.
            SteamLibraryImport.importInstalled(app)
            // The seeding runs once per process, right after startup: re-run it on the DB as it stands, so a game installed since
            // then gets its card now. The rows an older build left for a tool or an imported game go with it.
            SteamLibrarySync.seedInstalled(app)
            val old = games(app).associateBy { it.appId }
            val found = scanLibraries(app)
            save(app, found.values.toList())
            Log.i(TAG, "${found.size} game(s) found in the configured libraries")
            seed(app)
            // A game that left the list loses the row its record had put in Downloads — unless its folder has become this app's own
            // (a download or an adopted folder): then the normal seeding owns that row.
            for (appId in old.keys - found.keys) {
                val dir = old[appId]?.installDir?.let(::File)
                val ours = dir != null &&
                    (File(dir, SteamInstallPaths.OWNED_MARKER).exists() || File(dir, SteamInstallPaths.ADOPTED_MARKER).exists())
                if (!ours) dropRowIfUnclaimed(appId)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "library scan failed: ${t.message}")
        } finally {
            lastScanMs = System.currentTimeMillis()
            running.set(false)
        }
    }

    /** [scan] on a short-lived worker thread. */
    @JvmStatic
    fun scanAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ scan(app) }, "SteamLibraryFound").apply { isDaemon = true }.start()
    }

    /**
     * [scan] on a short-lived worker thread, at most once a minute unless [force]. The throttle suits a hook that may run on every
     * page open; the Downloads page passes [force] = true, because a game installed a moment ago must not be hidden by a scan that
     * ran just before it. Seeding alone would leave a library that was configured before this feature (or without a Steam sign-in,
     * so [SteamForegroundService] never scans) with no records to seed.
     */
    @JvmStatic
    fun scanIfStaleAsync(ctx: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastScanMs < 60_000L) return
        val app = ctx.applicationContext
        Thread({
            if (!force && System.currentTimeMillis() - lastScanMs < 60_000L) return@Thread
            scan(app)
        }, "SteamLibraryFound").apply { isDaemon = true }.start()
    }

    /**
     * What both add-library flows do once a folder is registered: the games another Steam client installed in it are imported
     * ([SteamLibraryImport.importInstalled]), then the libraries are scanned ([scan]) so the games in the new folder reach
     * Downloads from the found list. Returns how many owned games the import marked installed, for the flow's message.
     * Blocking: call off the main thread.
     */
    @JvmStatic
    internal fun afterLibraryAdded(ctx: Context): Int {
        val app = ctx.applicationContext
        val imported = SteamLibraryImport.importInstalled(app)
        scan(app)
        return imported
    }

    /**
     * Upserts the remembered games as INSTALLED Downloads rows ([DownloadEntry.found]) and forgets the ones whose folder or
     * manifest has gone. Skips a key a live download owns, and any app not known to be a game: a tool's card is dropped, an app
     * of unknown type waits for the next scan. Blocking: call off the main thread.
     */
    @JvmStatic
    fun seed(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val remembered = games(app)
            if (remembered.isEmpty()) return
            val keep = ArrayList<Game>(remembered.size)
            for (g in remembered) {
                val key = "${Store.STEAM}:${g.appId}"
                // Older records may still hold a runtime or a redistributable: never seed those. Steam's remembered type and the
                // DB's decide; an app neither types yet is left for the next scan to ask Steam about.
                val typeGame = cachedType(app, g.appId) ?: dbTypeGame(app, g.appId)
                if (typeGame == false) {
                    // A card an older build left for a tool goes with its record.
                    dropRowIfUnclaimed(g.appId)
                    continue
                }
                if (typeGame == null) continue
                val dir = File(g.installDir)
                val steamapps = SteamInstallPaths.steamappsOfInstallDir(dir)
                val present = steamapps != null && dir.isDirectory &&
                    SteamAppManifest.manifestFile(steamapps, g.appId).isFile
                if (!present) {
                    // Gone from disk: the row it left in Downloads goes too, unless a live download owns the key.
                    dropRowIfUnclaimed(g.appId)
                    continue
                }
                // The DB's row is what the launch pre-flight and the RealSteam launch read, and the import may have skipped this
                // app (an uninstall leaves it on the import's ignore list). A card this scan keeps must not sit beside a row that
                // says uninstalled, or the update check reports no files and the launch starts the game without Steam.
                adoptInDb(app, g)
                keep += g
                // Don't clobber a live/queued download that may already own this key.
                if (DownloadRegistry.isActive(key) || DownloadQueue.isQueued(g.appId)) continue
                DownloadRegistry.upsert(
                    DownloadEntry(
                        store = Store.STEAM,
                        id = g.appId.toString(),
                        name = g.name,
                        cover = SteamStoreSearch.coverUrl(g.appId),
                        state = DownloadState.INSTALLED,
                        pct = 100,
                        installDone = g.sizeOnDisk,
                        installTotal = g.sizeOnDisk,
                        installPath = g.installDir,
                        found = true,
                    ),
                )
            }
            if (keep.size != remembered.size) save(app, keep)
        } catch (t: Throwable) {
            Log.w(TAG, "seeding the found games failed: ${t.message}")
        }
    }

    /** [seed] on a short-lived worker thread — Clear ✓'s rescan, so no disk work runs on the main thread. */
    @JvmStatic
    fun seedAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ seed(app) }, "SteamLibraryFoundSeed").apply { isDaemon = true }.start()
    }

    /**
     * Points the Steam DB at [g]'s folder when its row does not already say installed there. That row is what the launch
     * pre-flight ([SteamGameUpdater]) and the RealSteam launch resolve the game with, and the import does not always write it: an
     * uninstall leaves the app on the import's ignore list, so a game reinstalled elsewhere comes back as a card with a row that
     * still says uninstalled. A game the account does not own has no row to update and is left alone. Never throws.
     */
    private fun adoptInDb(ctx: Context, g: Game) {
        try {
            val repo = SteamRepository.getInstance()
            val row = repo.database.getGame(g.appId) ?: return
            if (row.isInstalled && row.installDir == g.installDir) return
            // The folder is installed on disk, so the ignore entry has outlived its reason: let the import see the app again.
            SteamLibraryImport.clearIgnore(ctx, g.appId)
            repo.database.markInstalled(g.appId, g.installDir, g.sizeOnDisk)
            SteamLibraryImport.markImported(ctx, g.appId)
            repo.invalidateGameCache()
        } catch (t: Throwable) {
            Log.w(TAG, "could not point the DB at ${g.installDir}: ${t.message}")
        }
    }

    private fun scanLibraries(app: Context): Map<Int, Game> {
        val out = LinkedHashMap<Int, Game>()
        val libs = SteamLibraryLocations.libraries(app)
        if (libs.isEmpty()) return out
        for (lib in libs) {
            val root = File(lib.path)
            if (!root.isDirectory) continue
            val steamapps = SteamInstallPaths.steamappsDirOf(root)
            val manifests = steamapps.listFiles { f -> f.isFile && MANIFEST_NAME.matches(f.name) } ?: continue
            for (manifest in manifests) {
                val appId = MANIFEST_NAME.matchEntire(manifest.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (appId in out) continue
                try {
                    val info = SteamAppManifest.readInfo(manifest) ?: continue
                    if (info.appId != 0 && info.appId != appId) continue
                    if (!info.isInstalled) continue
                    val folder = File(File(steamapps, SteamInstallPaths.COMMON), info.installDir)
                    if (!folder.isDirectory) continue
                    // The app's own files (a download, or a folder the user brought) are the normal seeding's: they keep their card
                    // and their own uninstall. Everything else in a library is a found game, owned or not.
                    if (File(folder, SteamInstallPaths.OWNED_MARKER).exists() ||
                        File(folder, SteamInstallPaths.ADOPTED_MARKER).exists()
                    ) continue
                    // Runtimes, redistributables and tools have appmanifests too: only an app known to be a game is kept, one of
                    // unknown type is left out and retried on the next scan.
                    if (!isGameBySteam(app, appId, info.installDir, info.name)) {
                        Log.i(TAG, "left out, not known to be a game: '${info.name}' ($appId)")
                        continue
                    }
                    out[appId] = Game(
                        appId = appId,
                        name = info.name.ifBlank { info.installDir },
                        installDir = folder.absolutePath,
                        sizeOnDisk = info.sizeOnDisk,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "could not read ${manifest.name}: ${t.message}")
                }
            }
        }
        return out
    }

    /** Drops [appId]'s Downloads row unless a live download or the queue still owns it. */
    private fun dropRowIfUnclaimed(appId: Int) {
        val key = "${Store.STEAM}:$appId"
        if (!DownloadRegistry.isActive(key) && !DownloadQueue.isQueued(appId)) DownloadRegistry.removeLibraryEntry(key)
    }

    // ── Records ──────────────────────────────────────────────────────────────────────────────

    /** The remembered games, as last scanned or seeded. Never throws (an unreadable list is empty). */
    @JvmStatic
    fun games(ctx: Context): List<Game> = try {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) {
            emptyList()
        } else {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt("id", 0)
                val dir = o.optString("dir", "")
                if (id == 0 || dir.isBlank()) null
                else Game(id, o.optString("name", ""), dir, o.optLong("size", 0L))
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "found list unreadable: ${t.message}")
        emptyList()
    }

    /** [appId]'s record, or null when it is not a found game. */
    @JvmStatic
    fun record(ctx: Context, appId: Int): Game? = games(ctx).firstOrNull { it.appId == appId }

    /** Drops [appId]'s record, so the game is not listed (nor re-seeded) again until another scan finds it. Never throws. */
    @JvmStatic
    fun forget(ctx: Context, appId: Int) {
        try {
            val app = ctx.applicationContext
            val all = games(app)
            val rest = all.filterNot { it.appId == appId }
            if (rest.size != all.size) save(app, rest)
        } catch (t: Throwable) {
            Log.w(TAG, "forget($appId) failed: ${t.message}")
        }
    }

    private fun save(ctx: Context, list: List<Game>) {
        try {
            val arr = JSONArray()
            for (g in list) {
                arr.put(
                    JSONObject().put("id", g.appId).put("name", g.name).put("dir", g.installDir)
                        .put("size", g.sizeOnDisk),
                )
            }
            val text = arr.toString()
            val p = prefs(ctx)
            // A page open rescans and seeds every time: write only when the list really changed.
            if (p.getString(KEY, "") != text) p.edit().putString(KEY, text).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "could not save the found list: ${t.message}")
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Uninstall ────────────────────────────────────────────────────────────────────────────

    /**
     * True when [dir] is a game folder inside a configured library's `common` — the only place an uninstall may delete. A
     * library container (`steamapps`, `common`, a legacy `steam_games` root) is never one. Blocking (preference reads).
     */
    @JvmStatic
    fun isLibraryGameFolder(ctx: Context, dir: File): Boolean {
        if (!dir.isAbsolute || SteamInstallPaths.isLibraryContainer(dir)) return false
        val path = dir.absolutePath.trimEnd('/')
        return try {
            SteamLibraryLocations.libraries(ctx).any { lib ->
                SteamLibraryLocations.isUnder(path, SteamLibraryLocations.commonOf(lib.path))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "library check for $path failed: ${t.message}")
            false
        }
    }

    /**
     * True when the files in [dir] are NOT a download this app made — a game that came with one of the user's library folders
     * (another Steam client installed it), whatever [DownloadEntry.found] says. The account may well own the game
     * ([SteamLibraryImport] marks those installed), so its row can look like any other installed game's, and uninstall must still
     * delete the folder and the appmanifest.
     *
     * [dir] has to be a game folder of a configured library ([isLibraryGameFolder]: nowhere else is ever deleted) and must not be
     * a folder this app created ([SteamInstallPaths.OWNED_MARKER], written when a download starts in a fresh folder), so a game
     * the app downloaded itself always keeps its own uninstall path. What is left is a game with no completed download here:
     * [SteamLibraryImport.isImported] (the owned import, or a folder the user brought) or a [record] from the scan. Blocking
     * (preference reads and one folder check), small enough for the uninstall tap to ask on the UI thread, as it already asked
     * [isLibraryGameFolder] there.
     */
    @JvmStatic
    fun isForeignInstall(ctx: Context, appId: Int, dir: File?): Boolean {
        if (appId <= 0 || dir == null) return false
        if (!isLibraryGameFolder(ctx, dir)) return false
        if (File(dir, SteamInstallPaths.OWNED_MARKER).exists()) return false
        return SteamLibraryImport.isImported(ctx, appId) || record(ctx, appId) != null
    }

    /** What [deleteInstall] did with a game this app did not download. */
    enum class DeleteResult {
        /** The folder and its `appmanifest_<id>.acf` are gone. */
        DELETED,

        /**
         * The files were deliberately kept: not a game folder of a configured library (the library may have been removed since the
         * scan), a folder the user brought ([SteamInstallPaths.ADOPTED_MARKER]), or one another game's install uses too.
         */
        KEPT,

        /** Nothing was removed: the manifest does not name the folder, or the delete did not finish. */
        FAILED,
    }

    /**
     * Uninstall of a game this app did not download ([isForeignInstall]): deletes its folder and its `appmanifest_<id>.acf` — the
     * files the user confirmed on the card. Refuses a folder that is not a game folder of a configured library, and keeps the
     * folder when the user brought it or another game's manifest names it too (only this game's manifest goes, as for a
     * downloaded game). Files only: the store side is the caller's. Blocking: call off the main thread.
     */
    @JvmStatic
    fun deleteInstall(ctx: Context, appId: Int, dir: File): DeleteResult {
        val app = ctx.applicationContext
        if (!isLibraryGameFolder(app, dir)) {
            Log.i(TAG, "kept ${dir.absolutePath} ($appId): not a game folder of a configured library")
            return DeleteResult.KEPT
        }
        // A folder the user brought is the user's: an uninstall removes the app's record of it, never its files.
        if (File(dir, SteamInstallPaths.ADOPTED_MARKER).exists()) {
            Log.i(TAG, "kept ${dir.absolutePath} ($appId): the user's own folder")
            return DeleteResult.KEPT
        }
        return try {
            // The record can be stale (the game moved since the scan): delete only when its own manifest still names this folder,
            // so a folder that now belongs to something else is never removed.
            val steamapps = SteamInstallPaths.steamappsOfInstallDir(dir)
            val manifest = steamapps?.let { SteamAppManifest.manifestFile(it, appId) }
            val namesThisFolder = manifest != null &&
                SteamAppManifest.readInfo(manifest)?.installDir?.equals(dir.name, ignoreCase = true) == true
            if (!namesThisFolder) {
                Log.w(TAG, "not deleting ${dir.absolutePath}: appmanifest_$appId does not name it")
                return DeleteResult.FAILED
            }
            val shared = SteamInstallPaths.folderSharedWithOtherGame(app, dir, appId)
            SteamAppManifest.removeFor(dir.absolutePath, appId)
            if (shared) {
                Log.i(TAG, "kept ${dir.absolutePath}: another game uses it; only the manifest of $appId went")
                DeleteResult.KEPT
            } else {
                dir.deleteRecursively()
                val gone = !dir.exists()
                Log.i(TAG, "deleted ${dir.absolutePath} ($appId): ${if (gone) "gone" else "some files remain"}")
                if (gone) DeleteResult.DELETED else DeleteResult.FAILED
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not delete ${dir.absolutePath}: ${t.message}")
            DeleteResult.FAILED
        }
    }
}
