package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.core.FileUtils
import com.winlator.star.core.VdfParser
import com.winlator.star.core.WinePath
import java.io.File
import java.nio.file.Files

/**
 * Lists every game of the app's Steam store as installed in the Windows Steam client, wherever it lives.
 *
 * The store keeps its games in `imagefs/steam_games`, `<sd>/bannerlator/steam_games` or a library folder, which
 * the client does not read. This keeps a Steam library in the client's container, `C:\BannerlatorSteamLibrary`
 * (on the app's own storage, where links work): `steamapps/common/<folder>` is a link to the game's real folder and
 * `steamapps/appmanifest_<appid>.acf` is written by [SteamAppManifest.recordInstall] from the store's records and
 * the game's download journal, so the client lists it as installed without a download. The library goes into the
 * client's libraryfolders.vdf. Nothing is copied: what the client changes there (an update, a verify, an uninstall)
 * changes the store's own copy.
 *
 * Left out: games already in a library the client lists (e.g. the "Windows Steam" location), games with the
 * Goldberg emulator on (their Steam DLLs are swapped; a verify would undo it) and games whose folder is gone.
 * Links and manifests of games no longer installed are removed; only entries this class made (listed in
 * `steamapps/bannerlator-links.txt`) are ever touched. Run while the client is not running. Nothing here throws.
 */
object SteamLibraryMirror {
    private const val TAG = "SteamLibraryMirror"
    private const val DIR = "BannerlatorSteamLibrary"
    private const val WIN_PATH = "C:\\$DIR"
    private const val LIST = "bannerlator-links.txt"

    /** The library folder in [container]'s C: drive. */
    @JvmStatic
    fun libraryDir(container: Container): File = File(container.rootDir, ".wine/drive_c/$DIR")

    /** Brings the library in [container] up to date for the client in [steamRoot]; returns a line for the launch log. */
    @JvmStatic
    fun sync(ctx: Context, container: Container, steamRoot: File): String {
        try {
            val lib = libraryDir(container)
            val steamapps = File(lib, "steamapps")
            val common = File(steamapps, "common")
            if (!common.isDirectory && !common.mkdirs()) return "Store games in Steam: could not create $WIN_PATH"

            val listed = listedLibraries(container, steamRoot)
            // Games the client already has in one of its own libraries (downloaded by the client itself, or
            // in the "Windows Steam" location) are not listed a second time.
            val inClient = HashSet<Int>()
            for (root in listed) {
                File(root, "steamapps").listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
                    ?.forEach { acf -> SteamAppManifest.readInfo(acf)?.takeIf { it.isInstalled }?.let { inClient.add(it.appId) } }
            }
            val wanted = LinkedHashMap<Int, File>() // appId -> real game folder
            var goldberg = 0
            val db = SteamDatabase.getInstance(ctx)
            for (row in db.installedGames) {
                val dir = row.installDir?.takeIf { it.isNotBlank() }?.let { File(it) } ?: continue
                if (!dir.isDirectory) continue
                val real = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                if (listed.any { real == it || real.startsWith(it + File.separator) }) continue
                if (row.appId in inClient) continue
                if (runCatching { SteamPrefs.getGoldbergMode(row.appId) }.getOrNull().let { it != null && it != GoldbergMode.OFF }) {
                    goldberg++
                    continue
                }
                wanted[row.appId] = dir
            }

            // Remove what this class made before and is no longer wanted.
            val listFile = File(steamapps, LIST)
            val before = readList(listFile)
            for ((appId, folder) in before) {
                if (wanted[appId]?.name == folder) continue
                val link = File(common, folder)
                if (FileUtils.isSymlink(link)) link.delete()
                File(steamapps, "appmanifest_$appId.acf").delete()
            }

            // Link and record every wanted game.
            val made = LinkedHashMap<Int, String>()
            for ((appId, dir) in wanted) {
                val link = File(common, dir.name)
                val target = dir.absolutePath
                if (FileUtils.isSymlink(link)) {
                    val now = runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull()
                    if (now != target) {
                        link.delete()
                        FileUtils.symlink(target, link.absolutePath)
                    }
                } else if (link.exists()) {
                    continue // a real folder of that name: not ours
                } else {
                    FileUtils.symlink(target, link.absolutePath)
                }
                if (!FileUtils.isSymlink(link)) continue
                val size = db.getGame(appId)?.sizeBytes ?: 0L
                SteamAppManifest.recordInstall(ctx, appId, link, size, null)
                made[appId] = dir.name
            }
            writeList(listFile, made)
            val inList = WindowsSteamClient.addLibraryFolder(steamRoot, WIN_PATH, lib)

            return "Store games in Steam: ${made.size} linked in $WIN_PATH" +
                (if (goldberg > 0) ", $goldberg left out (Goldberg on)" else "") +
                (if (!inList) ", but the library is not in Steam's libraryfolders.vdf" else "")
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed", t)
            return "Store games in Steam: not updated (${t.message ?: t.javaClass.simpleName})"
        }
    }

    /** Canonical folders of the libraries the client in [steamRoot] already lists, except this one. */
    private fun listedLibraries(container: Container, steamRoot: File): List<String> {
        val out = ArrayList<String>()
        out.add(runCatching { steamRoot.canonicalPath }.getOrDefault(steamRoot.absolutePath))
        try {
            val vdf = File(steamRoot, "steamapps/libraryfolders.vdf")
            if (!vdf.isFile) return out
            val folders = VdfParser.parse(vdf.readText()).node("libraryfolders") ?: return out
            for ((_, node) in folders.childNodes()) {
                val path = node.string("path") ?: continue
                if (path.trimEnd('\\').equals(WIN_PATH, ignoreCase = true)) continue
                val dir = WinePath.resolveAndroidPath(container, path) ?: continue
                out.add(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "libraryfolders", t)
        }
        return out
    }

    private fun readList(file: File): Map<Int, String> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        runCatching {
            file.readLines().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim().toIntOrNull()?.let { out[it] = line.substring(eq + 1) }
            }
        }
        return out
    }

    private fun writeList(file: File, entries: Map<Int, String>) {
        runCatching { file.writeText(entries.entries.joinToString("") { "${it.key}=${it.value}\n" }) }
    }
}
