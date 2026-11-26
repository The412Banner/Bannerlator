package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.core.GameIdentifier
import java.io.File

/**
 * "+ Import game library": game folders the user already has (copied from a PC, another app, a backup) become
 * installed Steam games without a full download.
 *
 * [scan] matches the folders inside a picked folder to owned, not-installed games: a `steam_appid.txt` /
 * emulator ini app id first, then Steam's own folder name for the game (PICS installdir), then the game's name.
 * [adopt] points the game's install at the folder and starts the normal install there, which re-hashes every
 * chunk already on disk, keeps the ones that match and downloads only what is missing or broken; the game is
 * marked installed when that finishes.
 *
 * The folder stays the user's: [SteamInstallPaths.ADOPTED_MARKER] in it makes "Cancel & delete download" keep its
 * files, and the import flag ([SteamLibraryImport.markImported]) makes uninstall keep the folder. Files the game's
 * manifest does not list are never touched.
 */
object SteamFolderAdopt {
    private const val TAG = "SteamFolderAdopt"

    class Game(val appId: Int, val name: String)

    /** A folder and the game it was matched to (null = not matched; the user can pick one or skip it). */
    class Found(val folder: File, val game: Game?, val how: String?)

    class Result(val found: List<Found>, val games: List<Game>)

    /** Owned (visible to the active account), not installed, not downloading Steam games and demos, by name. */
    fun candidateGames(ctx: Context): List<Game> {
        val app = ctx.applicationContext
        val rows = try { SteamRepository.getInstance().cachedGameRows } catch (_: Throwable) { emptyList() }
        val visible = try { SteamAccountStore.visibleAppIds(app) } catch (_: Throwable) { null }
        val db = SteamDatabase.getInstance(app)
        val busy = HashSet<Int>()
        try { for (d in db.activeDownloads) busy.add(d.appId) } catch (_: Throwable) {}
        busy.addAll(SteamDepotDownloader.activeDownloads.keys)
        return rows.asSequence()
            .filter { !it.isInstalled }
            .filter { it.type == "game" || it.type == "demo" || it.appId in SteamRepository.LIBRARY_ALLOWLIST }
            .filter { visible == null || it.appId in visible }
            .filter { it.appId !in busy && !DownloadQueue.isQueued(it.appId) }
            .map { Game(it.appId, it.name.ifBlank { it.englishName }) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    /** Folders to look at for a picked folder: a Steam library or its steamapps/common is searched inside. */
    private fun folderRoot(picked: File): File {
        val common = when {
            picked.name.equals(SteamInstallPaths.COMMON, true) -> picked
            picked.name.equals(SteamInstallPaths.STEAMAPPS, true) -> File(picked, SteamInstallPaths.COMMON)
            else -> File(File(picked, SteamInstallPaths.STEAMAPPS), SteamInstallPaths.COMMON)
        }
        return if (common.isDirectory) common else picked
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Matches the folders in [picked] (and [picked] itself when it is one game's folder). Blocking. */
    fun scan(ctx: Context, picked: File): Result {
        val games = candidateGames(ctx)
        val byId = games.associateBy { it.appId }
        // Folder-name keys → games; a key two games share matches neither.
        val byKey = HashMap<String, MutableSet<Int>>()
        fun key(k: String, appId: Int) {
            val n = norm(k)
            if (n.length >= 2) byKey.getOrPut(n) { HashSet() }.add(appId)
        }
        for (g in games) {
            key(SteamPrefs.getPicsInstallDir(g.appId), g.appId)
            key(g.name, g.appId)
            key(SteamInstallPaths.safeGameFolderName(g.name, g.appId), g.appId)
        }
        val installedDirs = try {
            SteamDatabase.getInstance(ctx.applicationContext).installedGames
                .mapNotNull { it.installDir.takeIf { d -> d.isNotBlank() }?.let { d -> File(d).absolutePath } }.toSet()
        } catch (_: Throwable) { emptySet() }

        fun match(dir: File): Found? {
            if (dir.absolutePath in installedDirs) return null
            GameIdentifier.appIdInFolder(dir)?.let { id -> byId[id]?.let { return Found(dir, it, "steam_appid") } }
            val ids = byKey[norm(dir.name)]
            if (ids != null && ids.size == 1) byId[ids.first()]?.let { return Found(dir, it, "name") }
            return Found(dir, null, null)
        }

        val root = folderRoot(picked)
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val found = ArrayList<Found>()
        // The picked folder may itself be one game's folder.
        val self = match(picked)
        if (self?.game != null) found.add(self)
        else for (d in dirs) match(d)?.let { found.add(it) }
        // One folder per game: a game two folders matched keeps the first and the other becomes "not matched".
        val used = HashSet<Int>()
        val unique = found.map { f ->
            val g = f.game
            if (g == null || used.add(g.appId)) f else Found(f.folder, null, null)
        }
        Log.i(TAG, "scan ${picked.path}: ${unique.count { it.game != null }} matched, ${unique.count { it.game == null }} not matched")
        return Result(unique, games)
    }

    /** Why [folder] cannot hold a game, or null when it can. */
    fun refuseReason(folder: File): String? {
        if (!folder.isDirectory) return "That folder is gone."
        if (SteamInstallPaths.isLibraryContainer(folder)) return "Pick the game's own folder, not the Steam library."
        val path = folder.absolutePath.trimEnd('/')
        if (path == "/storage/emulated/0" || path.matches(Regex("/storage/[0-9A-F]{4}-[0-9A-F]{4}"))) {
            return "Pick the game's own folder, not the whole storage."
        }
        val probe = File(folder, ".bannerlator_write_probe")
        val writable = try { probe.writeText("1"); true } catch (_: Throwable) { false } finally { probe.delete() }
        if (!writable) return "Bannerlator can't write to that folder."
        return null
    }

    /** True when [folder]'s name or app id file points at game [appId]. */
    fun looksLike(ctx: Context, folder: File, appId: Int): Boolean {
        GameIdentifier.appIdInFolder(folder)?.let { return it == appId }
        val row = try { SteamDatabase.getInstance(ctx.applicationContext).getGame(appId) } catch (_: Throwable) { null }
        val n = norm(folder.name)
        val keys = listOfNotNull(SteamPrefs.getPicsInstallDir(appId), row?.name, row?.englishName).map(::norm).filter { it.isNotEmpty() }
        return n in keys
    }

    /**
     * Uses [folder] as game [appId]'s install folder and queues the normal install there (existing chunks are
     * kept, missing or broken ones downloaded). Returns an error message, or null when the install was queued.
     */
    fun adopt(ctx: Context, appId: Int, folder: File): String? {
        val app = ctx.applicationContext
        refuseReason(folder)?.let { return it }
        val db = SteamDatabase.getInstance(app)
        val row = db.getGame(appId) ?: return "That game isn't in your Steam library."
        if (row.isInstalled) return "${row.name} is already installed."
        return try {
            // Marker first: from here on a cancel keeps the folder's files.
            File(folder, SteamInstallPaths.ADOPTED_MARKER).writeText("$appId\n")
            SteamLibraryImport.markImported(app, appId)
            db.setInstallDir(appId, folder.absolutePath)
            SteamDepotDownloader.installApp(appId, app, verify = true)
            Log.i(TAG, "adopt $appId (${row.name}) at ${folder.path}: install queued")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "adopt $appId at ${folder.path} failed", t)
            t.message ?: "Could not start the install."
        }
    }
}
