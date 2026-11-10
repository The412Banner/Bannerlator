package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.StringUtils
import com.winlator.star.core.VdfParser
import com.winlator.star.core.WinePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Lists the app's games in the Windows Steam client as non-Steam games, before the client starts.
 *
 * Steam keeps non-Steam games in `userdata/<account>/config/shortcuts.vdf` (binary VDF), reads it when it starts and
 * writes it back when it exits, so the file is written here, while the client is not running. Every Wine game shortcut
 * of every container is listed, except the Steam clients themselves and Xbox Cloud entries. Entries the user added in
 * Steam are kept as they are; the app's own carry the tag [TAG_NAME] and are rewritten every time, so a game removed
 * from the app leaves Steam too (an entry Steam already knows keeps its play time and the user's switches). Each game's
 * cover goes to `config/grid/<appid>p.png`.
 *
 * A game in another container is reached through the Steam container's drives: an existing drive, or (for a game on
 * internal storage or an SD card) a new drive for that storage, as the "+" import does. A game in another container's
 * own C: drive cannot be reached and is left out. A game started from Steam runs in the Steam session, with the Steam
 * container's settings, not the game's own.
 *
 * Nothing here throws.
 */
object SteamShortcutsSync {
    private const val TAG = "SteamShortcutsSync"
    const val TAG_NAME = "Bannerlator"

    /** Writes the lists for the client whose steam.exe is in [steamRoot]; returns one line for the launch log. */
    @JvmStatic
    fun sync(ctx: Context, steamContainer: Container, steamRoot: File): String {
        try {
            val accounts = File(steamRoot, "userdata").listFiles { f ->
                f.isDirectory && f.name.all { it.isDigit() } && f.name != "0"
            }.orEmpty()
            if (accounts.isEmpty()) return "Non-Steam games: no signed-in account yet (listed on the next start)"

            val games = ArrayList<Game>()
            var unreachable = 0
            // Steam store games this client already lists as installed (the app's store put them in one of its
            // library folders) are not added a second time as non-Steam games.
            val installed = installedAppIds(steamContainer, steamRoot)
            var inSteam = 0
            val manager = ContainerManager(ctx)
            for (container in manager.containers) {
                val files = container.desktopDir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") } ?: continue
                for (file in files.sortedBy { it.name.lowercase() }) {
                    val entry = readDesktop(file) ?: continue
                    if (!isListable(entry)) continue
                    if (entry.extras["steamAppId"]?.toIntOrNull()?.let { it in installed } == true) { inSteam++; continue }
                    val exe = steamSidePath(steamContainer, container, entry.winPath)
                    if (exe == null) { unreachable++; continue }
                    games.add(Game(entry, container, exe))
                }
            }

            for (account in accounts) writeAccount(account, games)
            return "Non-Steam games: ${games.size} listed" +
                (if (inSteam > 0) ", $inSteam already installed in Steam" else "") +
                (if (unreachable > 0) ", $unreachable left out (not reachable from this container)" else "") +
                " for account " + accounts.joinToString(", ") { it.name }
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed", t)
            return "Non-Steam games: not updated (${t.message ?: t.javaClass.simpleName})"
        }
    }

    /**
     * App ids with an installed appmanifest in [steamRoot]'s own library and in the library folders its
     * libraryfolders.vdf lists (reached through [steamContainer]'s drives).
     */
    private fun installedAppIds(steamContainer: Container, steamRoot: File): Set<Int> {
        val roots = LinkedHashSet<File>()
        roots.add(steamRoot)
        try {
            val vdf = File(steamRoot, "steamapps/libraryfolders.vdf")
            if (vdf.isFile) {
                val folders = VdfParser.parse(vdf.readText()).node("libraryfolders")
                for ((_, node) in folders?.childNodes().orEmpty()) {
                    val path = node.string("path") ?: continue
                    WinePath.resolveAndroidPath(steamContainer, path)?.let { roots.add(it) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "libraryfolders", t)
        }
        val ids = HashSet<Int>()
        for (root in roots) {
            val steamapps = File(root, "steamapps")
            for (acf in steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }.orEmpty()) {
                val info = SteamAppManifest.readInfo(acf) ?: continue
                if (info.isInstalled) ids.add(info.appId)
            }
        }
        return ids
    }

    private class Entry(val file: File, val name: String, val winPath: String, val extras: Map<String, String>)
    private class Game(val entry: Entry, val container: Container, val exeWin: String)

    private fun isListable(e: Entry): Boolean {
        val source = e.extras["storeSource"].orEmpty()
        if (source == WindowsSteamClient.STORE_SOURCE || source == "android") return false
        if (e.extras[Container.EXTRA_RUNTIME] == Container.RUNTIME_GAMESCOPE) return false
        if (e.extras["launchMode"] == "Stream") return false
        if (WindowsSteamClient.isSteamExe(e.winPath)) return false
        return e.winPath.isNotBlank()
    }

    /** A shortcut's name (its file name, as the library shows it), Windows path and Extra Data, without loading its cover. */
    private fun readDesktop(file: File): Entry? {
        return try {
            var section = ""
            var exec: String? = null
            val extras = HashMap<String, String>()
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line
                    return@forEachLine
                }
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachLine
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                when (section) {
                    "[Desktop Entry]" -> if (key == "Exec") exec = value
                    "[Extra Data]" -> extras[key] = value
                }
            }
            val e = exec ?: return null
            val at = e.lastIndexOf("wine ")
            if (at < 0) return null
            Entry(file, file.nameWithoutExtension, StringUtils.unescape(e.substring(at + 4)), extras)
        } catch (t: Throwable) {
            null
        }
    }

    /** [winPath] of a game in [gameContainer] as a path the Steam container reaches, or null. */
    private fun steamSidePath(steamContainer: Container, gameContainer: Container, winPath: String): String? {
        val file = try { WinePath.resolveAndroidPath(gameContainer, winPath) } catch (t: Throwable) { null } ?: return null
        if (!file.exists()) return null
        if (gameContainer.id == steamContainer.id) return winPath
        val abs = file.absolutePath
        val driveC = File(steamContainer.rootDir, ".wine/drive_c").absolutePath.trimEnd('/')
        if (abs.startsWith("$driveC/")) return "C:\\" + abs.removePrefix("$driveC/").replace('/', '\\')
        existingDrivePath(steamContainer, abs)?.let { return it }
        // Another container's C: drive or the app's own files: no drive for those.
        if (WinePath.storageVolumeRootOf(abs) == null) return null
        return try { WinePath.resolveWindowsPath(steamContainer, abs) } catch (t: Throwable) { null }
    }

    private fun existingDrivePath(container: Container, hostPath: String): String? {
        var best: Pair<String, String>? = null
        for (d in container.drivesIterator()) {
            val letter = d[0].uppercase()
            if (letter == "C" || letter == "Z") continue
            val mount = d[1].trimEnd('/')
            if (mount.isEmpty()) continue
            if (hostPath == mount || hostPath.startsWith("$mount/")) {
                if (best == null || mount.length > best.second.length) best = letter to mount
            }
        }
        val (letter, mount) = best ?: return null
        val rel = hostPath.removePrefix(mount).trimStart('/').replace('/', '\\')
        return "$letter:\\$rel"
    }

    private fun writeAccount(account: File, games: List<Game>) {
        val config = File(account, "config")
        if (!config.isDirectory && !config.mkdirs()) return
        val vdf = File(config, "shortcuts.vdf")
        val root = if (vdf.isFile) BinaryVdf.parse(vdf.readBytes()) else BNode()
        if (root == null) {
            Log.w(TAG, "${vdf.absolutePath}: not a shortcuts file this reader understands; left alone")
            return
        }
        val list = root.node("shortcuts") ?: BNode().also { root.set("shortcuts", it) }

        val kept = ArrayList<BNode>()
        val ours = HashMap<Int, BNode>()
        for ((_, value) in list.entries) {
            val node = value as? BNode ?: continue
            if (isOurs(node)) (node.get("appid") as? Int)?.let { ours[it] = node } else kept.add(node)
        }

        val written = ArrayList<BNode>()
        val grid = File(config, "grid")
        for (g in games) {
            val exeQuoted = "\"${g.exeWin}\""
            val startDir = "\"${g.exeWin.substringBeforeLast('\\')}\\\""
            val appId = appIdOf(exeQuoted, g.entry.name)
            val node = ours[appId] ?: newEntry(appId)
            node.set("appid", appId)
            node.set("AppName", g.entry.name)
            node.set("Exe", exeQuoted)
            node.set("StartDir", startDir)
            node.set("LaunchOptions", g.entry.extras["execArgs"].orEmpty())
            written.add(node)
            copyCover(g, grid, appId)
        }

        list.entries.clear()
        (kept + written).forEachIndexed { i, node -> list.entries.add(i.toString() to node) }
        val tmp = File(config, "shortcuts.vdf.bl-tmp")
        tmp.writeBytes(BinaryVdf.toBytes(root))
        if (!tmp.renameTo(vdf)) {
            tmp.delete()
            Log.w(TAG, "could not write ${vdf.absolutePath}")
            return
        }
        Log.i(TAG, "${vdf.absolutePath}: ${written.size} app games, ${kept.size} of the user's kept")
    }

    private fun isOurs(node: BNode): Boolean =
        (node.node("tags")?.entries ?: emptyList()).any { it.second == TAG_NAME }

    private fun newEntry(appId: Int): BNode = BNode().apply {
        set("appid", appId)
        set("AppName", "")
        set("Exe", "")
        set("StartDir", "")
        set("icon", "")
        set("ShortcutPath", "")
        set("LaunchOptions", "")
        set("IsHidden", 0)
        set("AllowDesktopConfig", 1)
        set("AllowOverlay", 1)
        set("OpenVR", 0)
        set("Devkit", 0)
        set("DevkitGameID", "")
        set("DevkitOverrideAppID", 0)
        set("LastPlayTime", 0)
        set("FlatpakAppID", "")
        set("tags", BNode().apply { set("0", TAG_NAME) })
    }

    /** Steam's id for a non-Steam game: CRC-32 of the quoted exe and the name, with the top bit set. */
    internal fun appIdOf(exeQuoted: String, name: String): Int {
        val crc = CRC32()
        crc.update((exeQuoted + name).toByteArray(Charsets.UTF_8))
        return (crc.value or 0x80000000L).toInt()
    }

    private fun copyCover(g: Game, grid: File, appId: Int) {
        try {
            val custom = g.entry.extras["customCoverArtPath"]?.takeIf { it.isNotBlank() }?.let { File(it) }
            val source = custom?.takeIf { it.isFile }
                ?: File(g.container.rootDir, "app_data/cover_arts/${g.entry.name}.png").takeIf { it.isFile }
                ?: return
            val target = File(grid, "${appId.toLong() and 0xffffffffL}p.png")
            if (target.isFile && target.length() == source.length() && target.lastModified() >= source.lastModified()) return
            if (!grid.isDirectory && !grid.mkdirs()) return
            source.copyTo(target, overwrite = true)
        } catch (t: Throwable) {
            Log.w(TAG, "cover for ${g.entry.name}", t)
        }
    }
}

/** A binary VDF map: ordered entries whose values are [BNode], [String], [Int] (int32) or [BRaw] (kept as read). */
internal class BNode {
    val entries: MutableList<Pair<String, Any>> = ArrayList()

    fun get(key: String): Any? = entries.firstOrNull { it.first.equals(key, true) }?.second
    fun node(key: String): BNode? = get(key) as? BNode

    /** Replaces the value of [key] (matched without case) in place, or adds it at the end. */
    fun set(key: String, value: Any) {
        val i = entries.indexOfFirst { it.first.equals(key, true) }
        if (i >= 0) entries[i] = entries[i].first to value else entries.add(key to value)
    }
}

/** A value of a type this reader does not interpret, written back byte for byte. */
internal class BRaw(val type: Int, val bytes: ByteArray)

/** Steam's binary VDF (shortcuts.vdf): 0x00 map, 0x01 string, 0x02 int32, 0x08 end of map; other types kept raw. */
internal object BinaryVdf {
    fun parse(bytes: ByteArray): BNode? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val root = BNode()
        return if (readMap(b, root)) root else null
    }

    private fun readMap(b: ByteBuffer, node: BNode): Boolean {
        while (b.hasRemaining()) {
            val type = b.get().toInt() and 0xff
            if (type == 0x08) return true
            val key = readString(b) ?: return false
            when (type) {
                0x00 -> {
                    val child = BNode()
                    if (!readMap(b, child)) return false
                    node.entries.add(key to child)
                }
                0x01 -> node.entries.add(key to (readString(b) ?: return false))
                0x02 -> {
                    if (b.remaining() < 4) return false
                    node.entries.add(key to b.int)
                }
                0x03, 0x04, 0x06 -> node.entries.add(key to BRaw(type, take(b, 4) ?: return false))
                0x07, 0x0A -> node.entries.add(key to BRaw(type, take(b, 8) ?: return false))
                0x05 -> {
                    // UTF-16 string up to and including its two-byte terminator.
                    val start = b.position()
                    var end = -1
                    while (b.remaining() >= 2) {
                        if (b.short.toInt() == 0) { end = b.position(); break }
                    }
                    if (end < 0) return false
                    val raw = ByteArray(end - start)
                    b.position(start)
                    b.get(raw)
                    node.entries.add(key to BRaw(type, raw))
                }
                else -> return false
            }
        }
        // A file may end without the final end-of-map byte.
        return true
    }

    private fun take(b: ByteBuffer, n: Int): ByteArray? {
        if (b.remaining() < n) return null
        return ByteArray(n).also { b.get(it) }
    }

    private fun readString(b: ByteBuffer): String? {
        val start = b.position()
        while (b.hasRemaining()) {
            if (b.get().toInt() == 0) {
                val len = b.position() - start - 1
                val raw = ByteArray(len)
                b.position(start)
                b.get(raw)
                b.get() // terminator
                return String(raw, Charsets.UTF_8)
            }
        }
        return null
    }

    fun toBytes(root: BNode): ByteArray {
        val out = ByteArrayOutputStream()
        writeMap(root, out)
        out.write(0x08)
        return out.toByteArray()
    }

    private fun writeMap(node: BNode, out: ByteArrayOutputStream) {
        for ((key, value) in node.entries) {
            when (value) {
                is BNode -> {
                    out.write(0x00); writeString(key, out)
                    writeMap(value, out)
                    out.write(0x08)
                }
                is String -> { out.write(0x01); writeString(key, out); writeString(value, out) }
                is Int -> {
                    out.write(0x02); writeString(key, out)
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
                }
                is BRaw -> { out.write(value.type); writeString(key, out); out.write(value.bytes) }
            }
        }
    }

    private fun writeString(s: String, out: ByteArrayOutputStream) {
        out.write(s.toByteArray(Charsets.UTF_8))
        out.write(0)
    }
}
