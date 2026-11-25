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
 * [SteamLibraryImport] marks the owned games installed in the Steam DB, which is what the Steam Library tab reads. The Downloads
 * rows are this object's instead: every configured library's `<steamapps>/appmanifest_*.acf` is read ([SteamAppManifest.readInfo]), and
 * an app whose manifest says it is installed, whose `common/<installdir>` exists and whose folder is not this app's own (no
 * download or adopted marker, and no installed DB row pointing at another folder) is remembered here and seeded into
 * [DownloadRegistry] as an INSTALLED row with [DownloadEntry.found] set — owned by the account or not. It then shows in Downloads
 * exactly like a downloaded game — its Launch writes the shortcut from the folder. Only an owned app gets its `steam_games` row
 * pointed at the found folder ([adoptInDb]); a game the account does not own has no row, and nothing here creates one.
 *
 * The list is a cache of what is on disk: [scan] rewrites it from the libraries, [seed] drops a record whose folder or manifest
 * has gone. A download of the same app takes the row over by its installed DB row (the marker is not always there: a pre-existing
 * folder a download claims stays unmarked, [SteamInstallPaths.claimIfNew]) or, when it created the folder itself, by the owned
 * marker, so the next scan drops the record. A game the user removed is hidden again unless the "show games uninstalled from
 * Bannerlator" setting lists it; the setting only decides the Downloads card. A DB row and import mark are cleared by the game's
 * own uninstall, or released when a legacy installed row's found record drops ([releaseFoundDbRow]) — never merely by enabling or
 * disabling the setting. Nothing here modifies or deletes game files.
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
     * Valve's own folders and names for its tools: [isComponent] / [isGame] are the offline folder/name rules the library seeding
     * ([SteamLibrarySync.seedInstalled], which passes the bare folder name) applies, while [isGameBySteam] asks Steam or the DB
     * for the app's type. A game whose folder happens to start with the word (Proton Pulse) is kept, because a version follows
     * the name in a Proton build.
     */
    private fun isComponent(dir: String, name: String): Boolean {
        val d = dir.trim().replace('\\', '/').substringAfterLast('/')
        val n = name.trim()
        return n.equals("Fex", ignoreCase = true) || n.startsWith("Fex ", ignoreCase = true) ||
            d.equals("Fex", ignoreCase = true) ||
            d.startsWith("SteamLinuxRuntime", ignoreCase = true) ||
            d.startsWith("Steamworks Shared", ignoreCase = true) ||
            (d.startsWith("Proton ", ignoreCase = true) &&
                (d.getOrNull(7)?.isDigit() == true || d.startsWith("Proton -", ignoreCase = true)))
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
     * What Steam or the DB says [appId] is: true for a game, false for a tool/component, null when neither has answered (no
     * remembered or DB type and Pics returned nothing — not logged on, or no type yet). No local folder/name guess ([isGame]
     * is the seeding's own rule). A null caller keeps the app's existing record and adds no new one, so a found card survives
     * a scan made offline.
     */
    internal fun isGameBySteam(ctx: Context, appId: Int): Boolean? {
        if (appId in COMPONENT_IDS) return false
        cachedType(ctx, appId)?.let { return it }
        dbTypeGame(ctx, appId)?.let { return it }
        val fromSteam = picsType(appId)
        if (fromSteam != null) {
            rememberType(ctx, appId, fromSteam)
            return fromSteam
        }
        return null
    }

    private val running = AtomicBoolean(false)

    /**
     * Set when a scan is asked for while one runs: the running scan starts one more when it finishes, so a settings toggle's
     * rescan is not lost.
     */
    private val rescanQueued = AtomicBoolean(false)

    /**
     * Guards the read-modify-write of the record list ([scan], [seed], [forget]). [seed] holds it across its DB/card write and
     * its ignore re-check, never across a library or folder scan; the uninstall paths in other files do not take it.
     */
    private val recordsLock = Any()

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
     * main thread. A concurrent call queues one more scan for when this one finishes instead of scanning twice at once.
     */
    private fun scan(ctx: Context) {
        if (!running.compareAndSet(false, true)) {
            rescanQueued.set(true)
            return
        }
        val app = ctx.applicationContext
        try {
            // A scan started from Settings may be the first registry touch in the process: load the persisted found cards, or
            // the cleanup below and dropHiddenCards would see an empty registry and never remove a stale one.
            DownloadRegistry.init(app)
            // Owned games another client installed are only marked installed by the library import, which otherwise runs on the
            // Steam Library tab alone: a Downloads open must finish them too, or the Steam tab keeps them uninstalled and they
            // never launch from where they are.
            SteamLibraryImport.importInstalled(app)
            // The seeding runs once per process, right after startup: re-run it on the DB as it stands, so a game installed since
            // then gets its card now. The rows an older build left for a tool go with it.
            SteamLibrarySync.seedInstalled(app)
            // Read after both: a game the import or a download has just installed is in the DB now, and scanLibraries/cleanup
            // must see it as this app's.
            val ourDirs = ourInstallDirs(app)
            // Where the DB says each installed game is. A game installed elsewhere for Bannerlator (a download into internal
            // storage, say) must not treat another client's library copy as a found game — see scanLibraries.
            val installedAt = installedDirs(app)
            // A game downloading or queued right now is not a found game yet: it is left out of this scan (see scanLibraries).
            val busyIds = activeDownloadIds(app)
            val old = games(app).associateBy { it.appId }
            val found = scanLibraries(app, ourDirs, installedAt, busyIds, old.values)
            synchronized(recordsLock) { save(app, found.values.toList()) }
            Log.i(TAG, "${found.size} game(s) found in the configured libraries")
            seed(app)
            // A seed that read the record just before an uninstall could have written the card back: sweep once more after it.
            dropHiddenCards(app)
            // A game that left the list loses the row its record had put in Downloads — unless its folder has become this app's own
            // (a download or an adopted folder, or the DB row of a download into a pre-existing folder that has no marker): then
            // the normal seeding owns that row.
            for (appId in old.keys - found.keys) {
                val entry = DownloadRegistry.get("${Store.STEAM}:$appId")
                val recordDir = old[appId]?.installDir
                // A found card must not outlive its record. The found flag is persisted now, but a card written by an older build
                // was loaded with found = false: a card still pointing at the remembered found folder is treated as found too.
                val cardIsFound = entry?.found == true ||
                    (entry != null && recordDir != null && normPath(entry.installPath ?: "") == normPath(recordDir))
                // A normal INSTALLED card (not found) means the DB seeding owns the key now — possibly at a new folder after a
                // download moved the game, so the old record's dir alone cannot tell.
                val normalCard = entry?.state == DownloadState.INSTALLED && !cardIsFound
                val dir = old[appId]?.installDir?.let(::File)
                val ours = normalCard || dir != null &&
                    (File(dir, SteamInstallPaths.OWNED_MARKER).exists() ||
                        File(dir, SteamInstallPaths.ADOPTED_MARKER).exists() ||
                        normPath(dir.absolutePath) in ourDirs)
                if (!ours) {
                    dropRowIfUnclaimed(appId)
                    // The scan imported the record this row came from: its DB row must not stay installed once the record is
                    // gone (the other client uninstalled, the library was removed), or the game stays a ghost in the Steam tab.
                    releaseFoundDbRow(app, appId, old[appId]!!)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "library scan failed: ${t.message}")
        } finally {
            lastScanMs = System.currentTimeMillis()
            running.set(false)
            // A scan asked for while this one ran (a settings toggle) starts now. The flag is cleared first, so the new scan
            // cannot re-queue itself in a loop.
            if (rescanQueued.getAndSet(false)) scanAsync(app)
        }
    }

    /**
     * [scan] on a short-lived worker thread. A call while a scan runs queues one more scan after it: a settings toggle's rescan
     * must not be lost because a scan another screen started was still running.
     */
    @JvmStatic
    fun scanAsync(ctx: Context) {
        val app = ctx.applicationContext
        if (running.get()) {
            rescanQueued.set(true)
            return
        }
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
    internal fun afterLibraryAdded(ctx: Context): Int {
        val app = ctx.applicationContext
        val imported = SteamLibraryImport.importInstalled(app)
        scan(app)
        return imported
    }

    /** How [seed] finished with one record: kept and written, dropped as hidden, kept untouched (busy), or record dropped. */
    private enum class SeedWrite { WRITTEN, HIDDEN, BUSY, ADOPT_REFUSED }

    /**
     * Upserts the remembered games as INSTALLED Downloads rows ([DownloadEntry.found]) and forgets the ones whose folder or
     * manifest has gone. Skips a key a live download owns, hides a game the user removed unless the show setting lists it again,
     * and any app not known to be a game: a tool's card is dropped, an app of unknown type keeps its record. Blocking: call off
     * the main thread.
     */
    private fun seed(ctx: Context) {
        val app = ctx.applicationContext
        try {
            // A seed started from Settings may be the first registry touch in the process: load the persisted found cards, or
            // dropHiddenCards (and the DB row releases below) would see an empty registry.
            DownloadRegistry.init(app)
            // A scan that snapshotted a record before the uninstall ran can still seed it once: sweep the hidden cards even
            // when no record is remembered any more.
            dropHiddenCards(app)
            val remembered = games(app)
            if (remembered.isEmpty()) return
            val keep = ArrayList<Game>(remembered.size)
            for (g in remembered) {
                // The user removed this game: unless the show setting lists it again, its card goes and no record is kept.
                // The DB row the scan imported goes too, or the Steam tab keeps a ghost installed game.
                if (SteamLibraryImport.isHiddenFromDownloads(app, g.appId)) {
                    dropRowIfUnclaimed(g.appId)
                    releaseFoundDbRow(app, g.appId, g)
                    continue
                }
                val key = "${Store.STEAM}:${g.appId}"
                // A failed download/update keeps its error: never overwrite that row with INSTALLED. The record stays too —
                // the Windows-shortcut filter and the uninstall classifiers still need it while the download row exists.
                val current = DownloadRegistry.get(key)
                if (current != null && current.state != DownloadState.INSTALLED) {
                    keep += g
                    continue
                }
                // Older records may still hold a runtime or a redistributable: never seed those. Steam's remembered type and the
                // DB's decide; an app neither types yet is left for the next scan to ask Steam about.
                val typeGame = cachedType(app, g.appId) ?: dbTypeGame(app, g.appId)
                if (typeGame == false) {
                    // A card an older build left for a tool goes with its record, and so does a DB row the scan imported.
                    dropRowIfUnclaimed(g.appId)
                    releaseFoundDbRow(app, g.appId, g)
                    continue
                }
                if (typeGame == null) {
                    keep += g
                    continue
                }
                val dir = File(g.installDir)
                val steamapps = SteamInstallPaths.steamappsOfInstallDir(dir)
                val present = steamapps != null && dir.isDirectory &&
                    SteamAppManifest.manifestFile(steamapps, g.appId).isFile
                if (!present) {
                    // A game on an unmounted library is not gone: its record and card wait until the folder is back.
                    if (underUnmountedLibrary(app, dir)) {
                        keep += g
                        continue
                    }
                    // Gone from disk: the row it left in Downloads goes too, unless a live download owns the key — and so does
                    // the DB row the scan imported for it, or the Steam tab keeps it installed with no files.
                    dropRowIfUnclaimed(g.appId)
                    releaseFoundDbRow(app, g.appId, g)
                    continue
                }
                // Don't clobber a live/queued download that may already own this key: its row and record are the download's.
                if (DownloadRegistry.isActive(key) || DownloadQueue.isQueued(g.appId)) {
                    keep += g
                    continue
                }
                // The DB's row is what the launch pre-flight and the RealSteam launch read, and the import may not have written it
                // (it can be busy, or skip a queued app). A card this scan keeps must not sit beside a row that says uninstalled, or
                // the update check reports no files and the launch starts the game without Steam. An ignored game that the show
                // setting lists again is display only: its DB row and import mark stay untouched.
                // Best effort against a concurrent uninstall: the hidden/busy re-checks, the DB write and the card write run under
                // recordsLock so a user uninstall that lands between them cannot be overwritten by this seed. Limitation: the
                // uninstall paths in other files do not take recordsLock, so a few milliseconds remain between the hidden check
                // and the DB write, and an uninstall landing exactly there can still be undone.
                val written = synchronized(recordsLock) {
                    when {
                        // Re-check under the lock: an uninstall that landed after the check above must still drop the card.
                        SteamLibraryImport.isHiddenFromDownloads(app, g.appId) -> SeedWrite.HIDDEN
                        // The busy re-check under the lock closes the gap where a download starts after the outer check and
                        // would have this seed write an INSTALLED card over it.
                        DownloadRegistry.isActive(key) || DownloadQueue.isQueued(g.appId) -> SeedWrite.BUSY
                        // Ignored but shown: display only. Never write the DB row or the import mark here, or switching the setting
                        // off would leave the removed game installed in the DB (and the Steam tab).
                        SteamLibraryImport.isIgnored(app, g.appId) -> {
                            upsertFoundCard(g)
                            SeedWrite.WRITTEN
                        }
                        // A DB row installed at another folder that still exists is this app's own download: never move it to the
                        // library copy. Leave its card alone and drop only the found record.
                        !adoptInDb(app, g) -> SeedWrite.ADOPT_REFUSED
                        else -> {
                            upsertFoundCard(g)
                            SeedWrite.WRITTEN
                        }
                    }
                }
                when (written) {
                    // A download owns the key now: the record stays and its card is left untouched.
                    SeedWrite.BUSY -> keep += g
                    SeedWrite.ADOPT_REFUSED -> continue
                    SeedWrite.HIDDEN -> {
                        dropRowIfUnclaimed(g.appId)
                        releaseFoundDbRow(app, g.appId, g)
                        continue
                    }
                    SeedWrite.WRITTEN -> keep += g
                }
            }
            // Only the records this seed dropped are removed from the current list: a scan or forget that ran meanwhile is kept.
            val keptIds = keep.mapTo(HashSet()) { it.appId }
            val droppedIds = remembered.filter { it.appId !in keptIds }.mapTo(HashSet()) { it.appId }
            if (droppedIds.isNotEmpty()) synchronized(recordsLock) { save(app, games(app).filterNot { it.appId in droppedIds }) }
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

    /** Writes [g]'s INSTALLED found card: the display row that lasts until a real download takes the key over. */
    private fun upsertFoundCard(g: Game) {
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

    /**
     * Points the Steam DB at [g]'s folder when its row does not already say installed there. That row is what the launch
     * pre-flight ([SteamGameUpdater]) and the RealSteam launch resolve the game with, and the import does not always write it (it
     * can be busy, or skip a queued app), so a found card must not sit beside a row that still says uninstalled. A game the
     * account does not own has no row to update and is left alone.
     *
     * Returns false — without writing anything — when the row is installed at another folder that still exists or lies under a
     * configured library that is not mounted right now: that is a game this app downloaded elsewhere (internal storage, say), and
     * another client's library copy must never take its DB row over. Returns true otherwise (no row, the same folder, a row not
     * installed, or the old folder is gone) after adopting as above. Never throws.
     */
    private fun adoptInDb(ctx: Context, g: Game): Boolean {
        try {
            val repo = SteamRepository.getInstance()
            val row = repo.database.getGame(g.appId) ?: return true
            val rowDir = row.installDir.orEmpty()
            if (row.isInstalled && normPath(rowDir) == normPath(g.installDir)) return true
            if (row.isInstalled &&
                (File(rowDir).isDirectory || underUnmountedLibrary(ctx, File(rowDir)))
            ) return false
            repo.database.markInstalled(g.appId, g.installDir, g.sizeOnDisk)
            SteamLibraryImport.markImported(ctx, g.appId)
            repo.invalidateGameCache()
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "could not point the DB at ${g.installDir}: ${t.message}")
            return true
        }
    }

    private fun scanLibraries(
        app: Context,
        ourDirs: Set<String>,
        installedDirs: Map<Int, String>,
        busyIds: Set<Int>,
        remembered: Collection<Game>,
    ): Map<Int, Game> {
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
                if (SteamLibraryImport.isHiddenFromDownloads(app, appId)) continue
                // A game being downloaded or queued is not a found game yet: its download owns the card until it is installed.
                if (appId in busyIds || DownloadRegistry.isActive("${Store.STEAM}:$appId") || DownloadQueue.isQueued(appId)) {
                    // An update/verify must keep the record: without it isFoundInstallDir becomes false and the Windows Steam
                    // client's Home shortcut comes back until the next scan. A fresh download has no record and stays out.
                    remembered.firstOrNull { it.appId == appId }?.let { out[appId] = it }
                    continue
                }
                try {
                    val info = SteamAppManifest.readInfo(manifest) ?: continue
                    if (info.appId != 0 && info.appId != appId) continue
                    if (!info.isInstalled) continue
                    val folder = File(File(steamapps, SteamInstallPaths.COMMON), info.installDir)
                    if (!folder.isDirectory) continue
                    // Positive proof this app is already installed elsewhere for Bannerlator: the maintainer case is a download
                    // into internal storage beside another client's copy of the same game in a library. That copy is not a found
                    // game — treating it as one would move the installed DB row here and replace the download's card. A folder
                    // under a configured library that is not mounted right now counts as live too: the SD card is only away, not
                    // gone. An owned found game is unaffected: its import marks it installed at the found folder itself, so the
                    // paths are equal.
                    val installedAt = installedDirs[appId]
                    if (installedAt != null && normPath(installedAt) != normPath(folder.absolutePath) &&
                        (File(installedAt).isDirectory || underUnmountedLibrary(app, File(installedAt)))
                    ) continue
                    // The app's own files (a download, or a folder the user brought) are the normal seeding's: they keep their card
                    // and their own uninstall. Everything else in a library is a found game, owned or not.
                    if (File(folder, SteamInstallPaths.OWNED_MARKER).exists() ||
                        File(folder, SteamInstallPaths.ADOPTED_MARKER).exists()
                    ) continue
                    // A game this app downloaded into a pre-existing folder has no marker ([SteamInstallPaths.claimIfNew] leaves a
                    // foreign folder unmarked): its DB row is the only proof that the folder here is ours, not a found game.
                    if (normPath(folder.absolutePath) in ourDirs) continue
                    // Runtimes, redistributables and tools have appmanifests too: only an app known to be a game is kept. An app
                    // whose type neither Steam nor the DB knows (offline, no remembered type) keeps the record it already had and
                    // adds no new one, so an existing found card does not vanish because Pics could not answer.
                    when (isGameBySteam(app, appId)) {
                        false -> Log.i(TAG, "left out, not known to be a game: '${info.name}' ($appId)")
                        null -> remembered.firstOrNull { it.appId == appId }?.let { out[appId] = it }
                        true -> out[appId] = Game(
                            appId = appId,
                            name = info.name.ifBlank { info.installDir },
                            installDir = folder.absolutePath,
                            sizeOnDisk = info.sizeOnDisk,
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "could not read ${manifest.name}: ${t.message}")
                }
            }
        }
        // A library whose root is not mounted (an SD card taken out) has not lost its games: the ones remembered under it stay
        // until its folder is back. A game a mounted library found this scan keeps its fresh record.
        for (g in remembered) {
            if (g.appId !in out && underUnmountedLibrary(app, File(g.installDir))) out[g.appId] = g
        }
        return out
    }

    /** True when [dir] lies under a configured library whose root is not a folder right now. Never throws. */
    private fun underUnmountedLibrary(ctx: Context, dir: File): Boolean {
        val path = normPath(dir.absolutePath)
        return try {
            SteamLibraryLocations.libraries(ctx).any { lib ->
                !File(lib.path).isDirectory && path.startsWith(normPath(lib.path) + "/")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "unmounted-library check for $path failed: ${t.message}")
            false
        }
    }

    private fun normPath(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase()

    /**
     * The install dirs of games this app downloaded or adopted itself: a pre-existing (foreign) folder a download claimed has no
     * marker ([SteamInstallPaths.claimIfNew]), so its DB row is the only proof the folder here is ours, not a found game.
     */
    private fun ourInstallDirs(ctx: Context): Set<String> = try {
        SteamDatabase.getInstance(ctx).getInstalledGames()
            .filterNot { SteamLibraryImport.isImported(ctx, it.appId) }
            .mapNotNullTo(HashSet<String>()) { row ->
                row.installDir?.takeIf { it.isNotBlank() }?.let { normPath(File(it).absolutePath) }
            }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read the installed games: ${t.message}")
        emptySet()
    }

    /**
     * The install dir of every game Bannerlator's DB says is installed, keyed by app id, in the DB's original path (not
     * normalized). [scanLibraries] compares it with normPath. Never throws.
     */
    private fun installedDirs(ctx: Context): Map<Int, String> = try {
        val out = HashMap<Int, String>()
        for (row in SteamDatabase.getInstance(ctx).getInstalledGames()) {
            row.installDir?.takeIf { it.isNotBlank() }?.let { out[row.appId] = it }
        }
        out
    } catch (t: Throwable) {
        Log.w(TAG, "could not read the installed games: ${t.message}")
        emptyMap()
    }

    /** The app ids with a download row still active (running, queued or paused): not found games yet. Never throws. */
    private fun activeDownloadIds(ctx: Context): Set<Int> = try {
        SteamDatabase.getInstance(ctx).getActiveDownloads().mapTo(HashSet<Int>()) { it.appId }
    } catch (t: Throwable) {
        Log.w(TAG, "could not read the active downloads: ${t.message}")
        emptySet()
    }

    /**
     * Drops [appId]'s terminal Downloads card — INSTALLED, or a failed/cancelled attempt — unless a live download or the queue
     * still owns the key. Used when a found record is hidden, its app turns out to be a tool, or the record is gone.
     */
    private fun dropRowIfUnclaimed(appId: Int) {
        val key = "${Store.STEAM}:$appId"
        if (!DownloadRegistry.isActive(key) && !DownloadQueue.isQueued(appId)) DownloadRegistry.removeTerminal(key)
    }

    /**
     * Releases the installed DB row a found record left behind. A record the scan itself imported must not keep a ghost
     * installed DB row (and import mark) when the other Steam client uninstalled the game, the library location was removed,
     * or the show setting hides a legacy row. Only that narrow case is touched: a live/queued download owning the key, an app
     * that was not imported, or a row that is not installed at exactly this record's folder (a move or a download took it
     * over) is left alone, and a folder still inside a configured library ([isLibraryGameFolder]) with its manifest present is
     * kept so the game stays launchable. Never throws.
     */
    private fun releaseFoundDbRow(ctx: Context, appId: Int, record: Game) {
        try {
            val key = "${Store.STEAM}:$appId"
            if (DownloadRegistry.isActive(key) || DownloadQueue.isQueued(appId)) return
            if (!SteamLibraryImport.isImported(ctx, appId)) return
            val row = SteamRepository.getInstance().database.getGame(appId) ?: return
            if (!row.isInstalled) return
            if (normPath(row.installDir.orEmpty()) != normPath(record.installDir)) return
            val dir = File(record.installDir)
            val steamapps = SteamInstallPaths.steamappsOfInstallDir(dir)
            val present = steamapps != null && dir.isDirectory &&
                SteamAppManifest.manifestFile(steamapps, appId).isFile
            val managed = isLibraryGameFolder(ctx, dir)
            val hidden = SteamLibraryImport.isHiddenFromDownloads(ctx, appId)
            if (present && managed && !hidden) return
            runCatching { SteamRepository.getInstance().database.markUninstalled(appId) }
            SteamLibraryImport.clearImported(ctx, appId)
        } catch (t: Throwable) {
            Log.w(TAG, "could not release the DB row of $appId: ${t.message}")
        }
    }

    /**
     * Drops a card that must not show, including a failed/cancelled attempt whose found flag is gone, when the game is hidden
     * ([SteamLibraryImport.isHiddenFromDownloads]) — including a card a concurrent scan seeded after the uninstall removed it:
     * [scanLibraries] stops new records for a hidden app, but a [seed] that snapshotted the record before [forget] can still
     * write the row once.
     */
    private fun dropHiddenCards(ctx: Context) {
        for (entry in DownloadRegistry.entries.value) {
            if (entry.store != Store.STEAM) continue
            val appId = entry.id.toIntOrNull() ?: continue
            if (!SteamLibraryImport.isHiddenFromDownloads(ctx, appId)) continue
            if (!DownloadRegistry.isActive(entry.key) && !DownloadQueue.isQueued(appId)) {
                DownloadRegistry.removeTerminal(entry.key)
            }
        }
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

    /**
     * True when [dir] is the install folder of a game [games] currently lists. ContainerManager uses this to hide an untagged
     * Windows-Steam-client shortcut only while Downloads actually lists that game; a shortcut for a folder that is not listed
     * (an unregistered library, an old Add-EXE into an unlisted folder) stays visible. The dirs come from a cached parse
     * ([foundInstallDirs]) because ContainerManager asks this once per shortcut. Never throws.
     */
    @JvmStatic
    fun isFoundInstallDir(ctx: Context, dir: File?): Boolean {
        if (dir == null) return false
        val path = normPath(dir.absolutePath)
        return try {
            foundInstallDirs(ctx).contains(path)
        } catch (t: Throwable) {
            Log.w(TAG, "found-folder check for ${dir.absolutePath} failed: ${t.message}")
            false
        }
    }

    /** [foundInstallDirs]'s last parse: the raw pref text and the normalized dirs it held. Volatile: read from any thread. */
    @Volatile private var foundDirsRaw: String? = null
    @Volatile private var foundDirs: Set<String> = emptySet()

    /** Paired update of the two cache fields, so a reader never sees one value's text beside another value's set. */
    private val foundDirsLock = Any()

    /**
     * The normalized install dirs [games] lists. ContainerManager calls [isFoundInstallDir] once per shortcut, so the JSON is
     * parsed once per pref value and the set reused, instead of re-parsing it for every entry.
     */
    private fun foundInstallDirs(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        if (foundDirsRaw == raw) return foundDirs
        synchronized(foundDirsLock) {
            if (foundDirsRaw == raw) return foundDirs
            val parsed = try {
                val out = HashSet<String>()
                if (raw.isNotBlank()) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("id", 0)
                        val dir = o.optString("dir", "")
                        if (id != 0 && dir.isNotBlank()) out.add(normPath(dir))
                    }
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "found list unreadable: ${t.message}")
                HashSet()
            }
            foundDirs = parsed
            foundDirsRaw = raw
            return parsed
        }
    }

    /** Drops [appId]'s record, so the game is not listed (nor re-seeded) again until another scan finds it. Never throws. */
    @JvmStatic
    fun forget(ctx: Context, appId: Int) {
        try {
            val app = ctx.applicationContext
            synchronized(recordsLock) {
                val all = games(app)
                val rest = all.filterNot { it.appId == appId }
                if (rest.size != all.size) save(app, rest)
            }
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
            if (p.getString(KEY, "") != text) {
                p.edit().putString(KEY, text).apply()
                // The Home page's Windows-Steam shortcut filter reads these records, and a page over Home does not resume it:
                // refresh the live shortcut models after a scan/forget changed the list.
                try {
                    com.winlator.star.ui.screens.ShortcutsViewModel.notifyContainersChanged()
                } catch (t: Throwable) {
                    Log.w(TAG, "could not refresh the shortcuts after the found list changed: ${t.message}")
                }
            }
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
    private fun isLibraryGameFolder(ctx: Context, dir: File): Boolean {
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
     * ([SteamLibraryImport] marks those installed), so its row can look like any other installed game's. This is only a
     * classifier for the uninstall path: uninstall keeps another client's files, so nothing here deletes anything.
     *
     * [dir] has to be a game folder of a configured library ([isLibraryGameFolder]) and must not be a folder this app created
     * ([SteamInstallPaths.OWNED_MARKER], written when a download starts in a fresh folder), so a game the app downloaded itself
     * always keeps its own uninstall path. What is left is a game with no completed download here:
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

    /**
     * THE uninstall rule for every screen (Downloads, the XMB Downloads page, the Steam library tab, the game page): a game another
     * Steam client installed keeps its folder and its appmanifest; Bannerlator removes only its own record (card, found record,
     * import mark, DB row). That holds for an imported or owned library game ([SteamLibraryImport.isImported]) and for a found game
     * ([isForeignInstall] on [dir]). An app-managed folder ([SteamInstallPaths.OWNED_MARKER], e.g. after a move) always takes the
     * normal uninstall path, even if it was once imported: the files are this app's now. Every screen asks this function and says
     * so in its label. Never throws: the checks don't.
     */
    @JvmStatic
    fun keepsClientFiles(ctx: Context, appId: Int, dir: File?): Boolean {
        if (dir != null && File(dir, SteamInstallPaths.OWNED_MARKER).exists()) return false
        return SteamLibraryImport.isImported(ctx, appId) || isForeignInstall(ctx, appId, dir)
    }
}
