package com.winlator.star.linux

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.xenvironment.ImageFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32

/**
 * The app's own games, put in the Linux Steam client's library as non-Steam shortcuts.
 *
 * Two sources. The Games tab: every shortcut the user made in a Wine container, found where its
 * container's drive map says its program is. And any Games folders chosen on the Steam (Linux)
 * entry, scanned one level deep: each subfolder is one game. Steam's own games are left out,
 * because the client already has those in its library as the real thing.
 *
 * The session hands the list to the runtime's shortcuts writer, which puts each game in the
 * client's shortcuts.vdf under the ARM64 Proton before the client starts. The appid is the one the
 * client itself derives for a shortcut, so the tile, its art and its Proton prefix stay the same
 * from one session to the next.
 *
 * A Games-tab game can also share its saves with its container: the writer links the save
 * folders of the game's Proton prefix to the same folders in the container, so progress made on
 * one side is there on the other. Only those folders are shared, never the container's prefix
 * itself - Proton upgrades a prefix it is handed on its own (seen on device, 2026-09-23: "Upgrading
 * prefix from GE-Proton11-7 to 11.0-100"), and the container has to keep working in the app.
 *
 * The design and the shortcuts writer come from The412Banner/SteamDeck (PR 9), where adding games
 * from folders is proven on device.
 */
object LinuxAppGames {
    private const val TAG = "LinuxAppGames"

    /** Where the chosen Games folders are bound inside the session, one each. */
    const val GUEST_GAMES_DIR = "/root/Games"

    /** Separates the chosen Games folders in the entry's extra; a desktop file keeps one line per value. */
    const val FOLDER_SEPARATOR = "|"

    class Game(
        val name: String,
        /** "app" for a Games-tab shortcut, "folder" for one found in a Games folder. */
        val source: String,
        val exe: File,
        val guestExe: String,
        val guestDir: String,
        val args: String,
        /** The client's 32-bit appid for this shortcut, as an unsigned value. */
        val appId: Long,
        /** The container's user folder whose save folders the game shares, or null for none. */
        val saves: String?,
        val portrait: File?,
        val header: File?,
        val icon: File?,
        /** When the Games-tab entry was last written; the newest wins when two point at the same game. */
        val modified: Long,
    )

    /** Everything the session needs: the list file for the script and the binds it relies on. */
    class Session(val listing: File, val binds: List<String>, val games: List<Game>)

    private class Root(val host: File, val guest: String)

    /** The chosen Games folders, in the order they were added. */
    fun folders(steamEntry: Shortcut?): List<String> {
        val raw = steamEntry?.getExtra(LinuxTuning.EXTRA_GAMES_FOLDERS, "") ?: ""
        return raw.split(FOLDER_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Each Games folder with its place in the session: /root/Games/<folder name>, or with a short
     * hash of the host path when two chosen folders share a name, so adding or removing one folder
     * never changes another's games' paths (and with them their appids).
     */
    private fun roots(steamEntry: Shortcut?): List<Root> {
        val hosts = folders(steamEntry).map { File(it) }
        val names = hosts.groupingBy { it.name.lowercase() }.eachCount()
        return hosts.map { host ->
            val name = host.name.ifEmpty { "games" }
            val guestName = if ((names[name.lowercase()] ?: 0) > 1)
                name + "-" + "%08x".format(CRC32().apply { update(host.path.toByteArray()) }.value).take(4)
            else name
            Root(host, "$GUEST_GAMES_DIR/$guestName")
        }
    }

    /**
     * Builds the list for this session and writes it where the session sees it. Always written,
     * even empty, so the writer removes the entries of games that are gone or were turned off.
     * Missing art is looked up on Steam's store in the background and used from the next session.
     */
    fun prepare(context: Context, containerManager: ContainerManager, steamEntry: Shortcut?): Session {
        val binds = LinkedHashSet<String>()
        val games = ArrayList<Game>()
        val rootList = roots(steamEntry)
        if (LinuxTuning.isOn(steamEntry, LinuxTuning.EXTRA_APP_GAMES)) {
            val share = LinuxTuning.isOn(steamEntry, LinuxTuning.EXTRA_SHARE_SAVES)
            try {
                games.addAll(fromGamesTab(context, containerManager, rootList, binds, share))
            } catch (t: Throwable) {
                Log.w(TAG, "could not read the Games tab", t)
            }
        }
        for (root in rootList) {
            if (!root.host.isDirectory) { Log.w(TAG, "games folder ${root.host} is not a folder this session; skipped"); continue }
            binds.add(root.host.absolutePath + ":" + root.guest)
            val steamInstalls = steamInstallDirs(root.host)
            for (folder in root.host.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) {
                if (folder.name.lowercase() in steamInstalls) continue
                fromFolder(context, folder, rootList, binds)?.let { games.add(it) }
            }
        }
        // Two entries for one program under one name are one tile in the client; the one edited last wins.
        val unique = games.groupBy { it.appId }.values.map { same -> same.maxByOrNull { it.modified }!! }
        val listing = writeListing(context, unique)
        Log.i(TAG, "${unique.size} game(s) for the client: " + unique.joinToString { "${it.name} [${it.source}]" })
        Thread({ runCatching { fetchMissingArt(context, unique) }.onFailure { Log.w(TAG, "art lookup failed", it) } }, "LinuxAppGamesArt").start()
        return Session(listing, binds.toList(), unique)
    }

    /**
     * The folders under a library's steamapps/common that a manifest beside it already claims.
     * Steam lists those itself, so a shortcut would only add a non-Steam copy of the game. Steam
     * may lowercase installdir on Android's case-insensitive storage, so names compare lowercased.
     * (From Droid-Deck/DroidDeck #138.)
     */
    internal fun steamInstallDirs(dir: File): Set<String> {
        val steamapps = dir.parentFile?.takeIf { dir.name == "common" && it.name == "steamapps" } ?: return emptySet()
        return steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
            .orEmpty()
            .mapNotNull { manifest ->
                runCatching { INSTALL_DIR.find(manifest.readText())?.groupValues?.get(1)?.trim()?.lowercase() }.getOrNull()
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private val INSTALL_DIR = Regex("\"installdir\"\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    private fun fromGamesTab(
        context: Context, containerManager: ContainerManager, rootList: List<Root>, binds: MutableSet<String>, share: Boolean,
    ): List<Game> {
        val out = ArrayList<Game>()
        val linuxId = runCatching { containerManager.getLinuxContainer().id }.getOrNull()
        for (shortcut in containerManager.loadShortcuts()) {
            val container = shortcut.container ?: continue
            if (container.id == linuxId || shortcut.path.startsWith("linux:")) continue
            // The client already has Steam's own games as the real thing; the other stores'
            // games need their store's sign-in, which only the app's own launch provides.
            val store = shortcut.getExtra("storeSource", "")
            if (store == "steam" || store == "epic" || store == "amazon") continue
            val exe = runCatching { WinePath.resolveAndroidPath(container, shortcut.path) }.getOrNull()
            if (exe == null || !exe.isFile) {
                Log.w(TAG, "${shortcut.name}: ${shortcut.path} is not a file this app can see; skipped")
                continue
            }
            val guestExe = guestPath(context, exe, rootList, binds)
            val guestDir = exe.parentFile?.let { guestPath(context, it, rootList, binds) }
            if (guestExe == null || guestDir == null) {
                Log.w(TAG, "${shortcut.name}: the session cannot see ${exe.path}; skipped")
                continue
            }
            val saves = if (share) File(container.rootDir, ".wine/drive_c/users/" + ImageFs.USER)
                .takeIf { it.isDirectory }?.let { guestPath(context, it, rootList, binds) } else null
            val appId = appId(guestExe, shortcut.name)
            val cover = coverArt(shortcut)
            val (portrait, header) = splitByShape(cover)
            out.add(Game(
                name = shortcut.name, source = "app", exe = exe, guestExe = guestExe, guestDir = guestDir,
                args = shortcut.getExtra("execArgs", "") ?: "", appId = appId, saves = saves,
                portrait = portrait ?: cached(context, appId, "p.jpg"),
                header = header ?: cached(context, appId, "header.jpg"),
                icon = shortcut.iconFile?.takeIf { it.isFile },
                modified = shortcut.file.lastModified(),
            ))
        }
        return out
    }

    /** The cover the Games tab shows: the one the user picked, else the one saved beside the container. */
    private fun coverArt(shortcut: Shortcut): File? {
        shortcut.customCoverArtPath?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        return File(shortcut.container.rootDir, "app_data/cover_arts/${shortcut.name}.png").takeIf { it.isFile }
    }

    /**
     * A cover goes where its shape fits in the client: a tall one is the portrait capsule, a wide
     * one the header. The client stretches whatever it is given to the slot's shape.
     */
    private fun splitByShape(image: File?): Pair<File?, File?> {
        if (image == null) return null to null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null to null
        return if (o.outHeight > o.outWidth) image to null else null to image
    }

    private val SKIP = Regex(
        "(?i)^(unins.*|setup.*|.*redist.*|vcredist.*|dxsetup.*|dxwebsetup.*|.*crash.*|.*report.*|dotnet.*|directx.*|.*prereq.*" +
            "|.*installer.*|.*uninstall.*|.*updater?.*|.*config(ur.*)?|.*settings.*|.*editor.*|.*server.*|.*benchmark.*|.*helper.*|.*eac.*|.*easyanticheat.*|.*battleye.*)\\.exe$",
    )

    /**
     * The .exe files a game folder offers, best first: one in the folder itself, then one named
     * after the folder, then the largest. Installers, redistributables and crash reporters never count.
     */
    private fun candidates(folder: File): List<File> {
        val exes = ArrayList<File>()
        val dirs = listOf(folder) + (folder.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList())
        for (dir in dirs) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".exe", ignoreCase = true) && !SKIP.matches(f.name) }?.let { exes.addAll(it) }
        }
        val key = folder.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return exes.sortedWith(
            compareByDescending<File> { it.parentFile == folder }
                .thenByDescending { it.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9]"), "").let { n -> n == key || key.startsWith(n) || n.startsWith(key) } }
                .thenByDescending { it.length() },
        )
    }

    private fun fromFolder(context: Context, folder: File, rootList: List<Root>, binds: MutableSet<String>): Game? {
        val exe = candidates(folder).firstOrNull() ?: return null
        val guestExe = guestPath(context, exe, rootList, binds) ?: return null
        val guestDir = guestPath(context, exe.parentFile ?: folder, rootList, binds) ?: return null
        val appId = appId(guestExe, folder.name)
        val (ownPortrait, ownHeader) = folderArt(folder)
        return Game(
            name = folder.name, source = "folder", exe = exe, guestExe = guestExe, guestDir = guestDir, args = "",
            appId = appId, saves = null,
            portrait = ownPortrait ?: cached(context, appId, "p.jpg"),
            header = ownHeader ?: cached(context, appId, "header.jpg"),
            icon = listOf("icon.png", "icon.ico").map { File(folder, it) }.firstOrNull { it.isFile },
            modified = folder.lastModified(),
        )
    }

    private val IMAGE = listOf("png", "jpg", "jpeg")

    /** Art the user put in the game's folder (or its art subfolder): a portrait and a header. */
    private fun folderArt(folder: File): Pair<File?, File?> {
        fun find(names: List<String>): File? {
            for (dir in listOf(folder, File(folder, "art"), File(folder, ".art")))
                for (name in names) for (ext in IMAGE) File(dir, "$name.$ext").takeIf { it.isFile }?.let { return it }
            return null
        }
        return find(listOf("cover", "poster", "grid", "boxart", "capsule", "library_600x900", "portrait", folder.name)) to
            find(listOf("header", "banner", "library_header", "wide"))
    }

    /**
     * Where a file on the device appears inside the session, or null when it does not.
     * The app's files and internal storage are bound at their own paths; another storage volume
     * (an SD card, a USB drive) is bound at its own path too, added to [binds] the first time a
     * game needs it; a Games folder is bound under /root/Games.
     */
    private fun guestPath(context: Context, file: File, rootList: List<Root>, binds: MutableSet<String>): String? {
        val pkg = context.packageName
        val path = file.absolutePath.replaceFirst("/data/data/$pkg/", "/data/user/0/$pkg/")
        for (root in rootList) {
            val dir = root.host.absolutePath
            if (path == dir) return root.guest
            if (path.startsWith("$dir/")) return root.guest + "/" + path.removePrefix("$dir/")
        }
        val files = context.filesDir.absolutePath
        if (path.startsWith("$files/")) return path
        val internal = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path.startsWith("$internal/")) return path
        val volume = WinePath.storageVolumeRootOf(path) ?: return null
        if (volume.startsWith("/storage/emulated")) return null
        if (!File(volume).canRead()) return null
        binds.add(volume)
        return path
    }

    /** The appid the client derives for a shortcut: crc32 of the quoted exe and the name, top bit set. */
    private fun appId(guestExe: String, name: String): Long =
        CRC32().apply { update(("\"$guestExe\"" + name).toByteArray()) }.value or 0x80000000L

    // ---- the list the runtime's shortcuts writer reads ----

    private fun writeListing(context: Context, games: List<Game>): File {
        val file = File(context.filesDir, "linux-session/app-games.json").apply { parentFile?.mkdirs() }
        val list = JSONArray()
        for (g in games) {
            val o = JSONObject()
                .put("name", g.name).put("exe", g.guestExe).put("dir", g.guestDir)
                .put("appid", g.appId).put("source", g.source)
            if (g.args.isNotEmpty()) o.put("args", g.args)
            if (g.saves != null) o.put("saves", g.saves)
            val art = JSONObject()
            // The art files live in the app's files or beside the game, both visible to the session at the same path.
            g.portrait?.let { art.put("p", artPath(context, it)) }
            g.header?.let { art.put("header", artPath(context, it)) }
            cached(context, g.appId, "hero.jpg")?.let { art.put("hero", it.absolutePath) }
            cached(context, g.appId, "logo.png")?.let { art.put("logo", it.absolutePath) }
            g.icon?.let { art.put("icon", artPath(context, it)) }
            if (art.length() > 0) o.put("art", art)
            list.put(o)
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(list.toString())
        if (!tmp.renameTo(file)) file.writeText(list.toString())
        return file
    }

    private fun artPath(context: Context, file: File): String {
        val pkg = context.packageName
        return file.absolutePath.replaceFirst("/data/data/$pkg/", "/data/user/0/$pkg/")
    }

    // ---- art from Steam's store, for games that brought none ----

    private const val STORE_SEARCH = "https://store.steampowered.com/api/storesearch/?l=english&cc=US&term="
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    private fun cacheDir(context: Context, appId: Long) = File(context.filesDir, "linux-app-games-art/$appId")

    private fun cached(context: Context, appId: Long, name: String): File? = File(cacheDir(context, appId), name).takeIf { it.isFile }

    /**
     * Looks each game up on Steam's store by name and fetches the pieces it is missing, once per
     * game; a miss is retried after a week. Runs off the main thread; what it fetches is used from
     * the next session, because this one's list is already written.
     */
    private fun fetchMissingArt(context: Context, games: List<Game>) {
        for (game in games) {
            val cache = cacheDir(context, game.appId)
            if (File(cache, "p.jpg").isFile && File(cache, "hero.jpg").isFile) continue
            val lookup = File(cache, "steam-appid")
            if (lookup.isFile) {
                val id = lookup.readText().trim()
                if (id == "none" && System.currentTimeMillis() - lookup.lastModified() < RETRY_AFTER_MS) continue
                if (id != "none") { download(id, cache); continue }
            }
            cache.mkdirs()
            val id = runCatching { search(game.name) }.onFailure { Log.w(TAG, "${game.name}: store search failed (${it.message})") }.getOrNull()
            if (id == null) { lookup.writeText("none"); continue }
            lookup.writeText(id)
            Log.i(TAG, "${game.name}: art from Steam app $id")
            download(id, cache)
        }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The Steam appid whose store name matches the game's name best, or null for no match. */
    private fun search(name: String): String? {
        val term = name.replace(Regex("[®™©]"), "")
            .replace(Regex("(?i)\\b(complete|definitive|legendary|goty|game of the year|deluxe|ultimate|remastered)( edition)?\\b"), " ")
            .trim().ifEmpty { name }
        val json = get(STORE_SEARCH + URLEncoder.encode(term, "UTF-8"))?.toString(Charsets.UTF_8) ?: return null
        val items = JSONObject(json).optJSONArray("items") ?: return null
        val want = normalize(name)
        val wantShort = normalize(term)
        var best: String? = null
        var bestScore = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val got = normalize(item.optString("name"))
            val score = when {
                got == want -> 4
                got == wantShort -> 3
                got.startsWith(wantShort) || wantShort.startsWith(got) -> 2
                i == 0 -> 1
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = item.optString("id") }
        }
        return best?.takeIf { it.isNotEmpty() && bestScore >= 1 }
    }

    private fun download(id: String, cache: File) {
        for ((remote, local) in listOf(
            "library_600x900.jpg" to "p.jpg", "header.jpg" to "header.jpg", "library_hero.jpg" to "hero.jpg", "logo.png" to "logo.png",
        )) {
            val dst = File(cache, local)
            if (dst.isFile) continue
            val bytes = get("$CDN$id/$remote") ?: continue
            val tmp = File(cache, "$local.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(dst)
        }
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "$url: ${e.message}"); null
        } finally {
            c.disconnect()
        }
    }
}
