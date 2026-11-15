package com.winlator.star.netdrive

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.core.WinePath
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Network games that stream (test): a game folder on a network drive is played without copying it.
 *
 * [create] builds a STAND-IN of the folder in app storage (files/netgames/<Game>): the real folders, and every file at
 * its real size as a sparse file (no space used), with its date. The exes, dlls and small files are fetched whole
 * right away, so Bannerlator's own reads (the game's name and icon, the exe checks before a launch) see real bytes.
 * At launch ([launchEnv]) the game runs with libnetfs.so preloaded, which fetches the rest of each file from the
 * drive the moment the game reads it (see app/src/main/cpp/winlator/netfs.c). Fetched parts stay on the device.
 *
 * The stand-ins live on internal storage on purpose: sparse files need it (the SD card's exFAT has no holes).
 * Each container reaches files/netgames through a drive letter added the first time ([WinePath.ensureDrive]).
 */
object NetStream {
    private const val TAG = "BL_NETDRIVE"
    /**
     * Fetched whole when the stand-in is made, because Bannerlator itself reads them (the exe's name and icon, the
     * checks before a launch): the game's exes and dlls, and the small files that say which game it is. Not the
     * installers under redist folders, but their install scripts (*.vdf), which InstallScriptRedists reads before a
     * launch to install the game's runtimes (11-15 GTA V: read empty under _CommonRedist). Everything else streams
     * while playing (11-15: fetching every file up to 4 MB took Bayonetta 709 files / 544 MB, one at a time).
     */
    private val PREFETCH_EXTS = setOf("exe", "dll")
    private val PREFETCH_NAMES = setOf("steam_appid.txt", "microsoftgame.config", "appxmanifest.xml")
    private val SKIP_DIRS = setOf("_commonredist", "commonredist", "redist", "redistributable", "redistributables",
        "directx", "dotnet", "dotnetfx", "vcredist", "vc_redist", "prerequisites", "prereq", "installer", "installers", "__installer")
    private const val PREFETCH_PARALLEL = 4
    /** InstallScriptRedists reads *.vdf up to this size. */
    private const val SCRIPT_MAX = 512L * 1024

    private fun isScript(name: String) = name.endsWith(".vdf")

    /** Installers and uninstallers: never what Bannerlator or the game needs up front (11-15: GTA V's two Social Club
     *  setups were 129 MB of the stand-in). They still stream if something runs them. */
    private val INSTALLER_RE = Regex("""(?i)setup|install|uninst|redist""")
    private fun isInstaller(name: String): Boolean {
        val base = name.substringBeforeLast('.')
        return com.winlator.star.core.GameFolderScanner.isJunkExeName(base) || INSTALLER_RE.containsMatchIn(base)
    }

    private fun prefetchable(e: NetFs.Entry): Boolean {
        if (e.isDir || e.size <= 0) return false
        if (isScript(e.name.lowercase()) && e.size <= SCRIPT_MAX) return true
        val dirs = e.path.split('/').dropLast(1).map { it.lowercase() }
        if (dirs.any { it in SKIP_DIRS }) return false
        val name = e.name.lowercase()
        if (name.endsWith(".exe") && isInstaller(name)) return false
        return name.substringAfterLast('.', "") in PREFETCH_EXTS || name in PREFETCH_NAMES ||
            (name.startsWith("goggame-") && name.endsWith(".info") && e.size < 1L shl 20)
    }

    /** What a streamed game keeps on the device between sessions. */
    enum class Mode(val key: String, val label: String) {
        /** What it has loaded stays (up to the size limit; the games played longest ago are emptied first). */
        KEEP("keep", "Keep what's loaded"),
        /** Emptied again when the game closes: nothing stays between sessions. */
        STREAM("stream", "Stream only"),
        /** Everything fetched and kept; never emptied by the limit. */
        FULL("full", "Keep a full copy");

        companion object { fun of(key: String?) = values().firstOrNull { it.key == key } ?: KEEP }
    }

    /** One streamed game: where its stand-in is, which folder of which drive it stands in for, and how it is kept. */
    class Game(val dir: File, val driveId: String, val remotePath: String, val mode: Mode = Mode.KEEP, val lastPlayed: Long = 0L) {
        val name: String get() = dir.name
        fun toJson(): JSONObject = JSONObject().put("dir", dir.absolutePath).put("drive", driveId).put("path", remotePath)
            .put("mode", mode.key).put("lastPlayed", lastPlayed)
        fun with(mode: Mode = this.mode, lastPlayed: Long = this.lastPlayed) = Game(dir, driveId, remotePath, mode, lastPlayed)
    }

    /** Where libnetfs.so writes what the game is waiting for (NetStreamTag shows it). */
    @JvmStatic
    fun statusFile(ctx: Context): File = File(NetEngine.dir(ctx.applicationContext), "stream.status")

    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "netgames").apply { mkdirs() }.canonicalFile

    private fun registryFile(ctx: Context) = File(NetEngine.dir(ctx).apply { mkdirs() }, "streams.json")
    private val lock = Any()

    fun games(ctx: Context): List<Game> = synchronized(lock) {
        val f = registryFile(ctx)
        if (!f.isFile) return emptyList()
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    Game(File(o.getString("dir")), o.getString("drive"), o.optString("path"), Mode.of(o.optString("mode")), o.optLong("lastPlayed", 0L))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveGames(ctx: Context, list: List<Game>) = synchronized(lock) {
        val f = registryFile(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(f)
    }

    /** The streamed game [file] belongs to, or null. */
    fun gameOf(ctx: Context, file: File): Game? {
        val p = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return games(ctx).firstOrNull { g -> p == g.dir.absolutePath || p.startsWith(g.dir.absolutePath + "/") }
    }

    /** A free folder name under [root] for [name]. */
    fun newDir(ctx: Context, name: String): File {
        val base = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "game" }
        var d = File(root(ctx), base)
        var n = 2
        while (d.exists()) d = File(root(ctx), "$base ($n)").also { n++ }
        return d
    }

    /**
     * Builds the stand-in of [remotePath] on [drive] in [dir] and registers it. [onProgress] gets a line and a
     * share (null = unknown). Throws with the reason when the drive can't be listed.
     */
    suspend fun create(ctx: Context, drive: NetDrive, remotePath: String, dir: File, mode: Mode, onProgress: (String, Float?) -> Unit) {
        val app = ctx.applicationContext
        onProgress("Listing the game's files", null)
        val all = NetFs.listAll(app, drive, remotePath)
        dir.mkdirs()
        var made = 0
        for (e in all) {
            val f = File(dir, e.path)
            if (e.isDir) {
                f.mkdirs()
            } else {
                f.parentFile?.mkdirs()
                RandomAccessFile(f, "rw").use { it.setLength(e.size) }
                if (e.modified > 0) f.setLastModified(e.modified)
            }
            if (++made % 50 == 0) onProgress("Making the folder (${made}/${all.size})", made.toFloat() / all.size.coerceAtLeast(1))
        }
        for (e in all) if (e.isDir && e.modified > 0) File(dir, e.path).setLastModified(e.modified)
        val whole = all.filter(::prefetchable)
        val total = whole.sumOf { it.size }.coerceAtLeast(1)
        val doneBytes = java.util.concurrent.atomic.AtomicLong(0)
        val doneFiles = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress("Fetching the game's exe and dll files (0/${whole.size})", 0f)
        // A few at a time: one rclone job per file has a fixed cost, several in flight hide it.
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(PREFETCH_PARALLEL)
            whole.map { e ->
                launch {
                    gate.acquire()
                    try {
                        val f = File(dir, e.path)
                        // The stand-in has the real size and date, so rclone would call it "unchanged" and skip it
                        // (11-15: every prefetch was a no-op). Remove it first; the date is put back after.
                        f.delete()
                        NetFs.transfer(app, NetFs.Side.Remote(drive, NetFs.join(remotePath, e.path)), NetFs.Side.Local(f), isDir = false, move = false) { _, _ -> }
                        if (f.length() != e.size) throw java.io.IOException("${e.name} didn't arrive whole")
                        if (e.modified > 0) f.setLastModified(e.modified)
                        val n = doneFiles.incrementAndGet()
                        onProgress("Fetching the game's exe and dll files ($n/${whole.size})", doneBytes.addAndGet(e.size).toFloat() / total)
                    } finally {
                        gate.release()
                    }
                }
            }
        }
        val game = Game(dir.canonicalFile, drive.id, remotePath, mode)
        writeManifest(app, game, all.filter { !it.isDir })
        saveGames(app, games(app).filter { it.dir != game.dir } + game)
        Log.i(TAG, "stand-in ready: ${dir.name}, ${all.size} entries, ${whole.size} files fetched whole")
    }

    /** Makes files/netgames reachable in [container] (a drive letter, once). */
    fun ensureDrive(ctx: Context, container: Container): Boolean = WinePath.ensureDrive(container, root(ctx).absolutePath)

    /**
     * For a launch of [winPath] in [container]: the env that streams it (BLNETFS_ROOT + BLNETFS_CONF), or null when it
     * isn't a streamed game. Starts the rclone daemon (and keeps it running) and writes the private config file.
     * Blocking; called by GuestProgramLauncherComponent on the launch thread.
     */
    @JvmStatic
    fun launchEnv(ctx: Context, container: Container, winPath: String?): Map<String, String>? {
        if (winPath.isNullOrBlank()) return null
        val app = ctx.applicationContext
        return try {
            val exe = WinePath.resolveAndroidPath(container, winPath) ?: return null
            val game = gameOf(app, exe) ?: return null
            val drive = NetDriveStore.list(app).firstOrNull { it.id == game.driveId }
                ?: run { Log.w(TAG, "stream ${game.dir.name}: its drive was removed"); return null }
            if (!NetEngine.isInstalled(app)) { Log.w(TAG, "stream ${game.dir.name}: network engine not installed"); return null }
            NetDaemon.retain("netgame")
            val prefix = runBlocking { withTimeoutOrNull(20_000) { NetFs.servePrefix(app, drive, game.remotePath) } }
                ?: run { Log.w(TAG, "stream ${game.dir.name}: the network engine didn't start"); return null }
            val (port, auth) = NetDaemon.serveInfo() ?: return null
            val conf = File(NetEngine.dir(app), "stream.conf")
            val status = statusFile(app).apply { delete() }
            conf.writeText("port=$port\nprefix=$prefix\nauth=$auth\nstatus=${status.absolutePath}\n")
            conf.setReadable(false, false); conf.setReadable(true, true)
            Log.i(TAG, "streaming ${game.dir.name} from ${drive.name}")
            update(app, game.with(lastPlayed = System.currentTimeMillis()))
            // Over the limit: the other games played longest ago are emptied, off the launch thread.
            Thread({ runCatching { enforceLimit(app, except = game.dir) } }, "netstream-limit").start()
            mapOf("BLNETFS_ROOT" to game.dir.absolutePath, "BLNETFS_CONF" to conf.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "stream setup failed: ${NetFs.scrub(t.message ?: t.toString())}")
            null
        }
    }

    // ── What stays on the device ──

    private fun prefs(ctx: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    /** The most all streamed games may keep, in bytes; 0 = no limit. */
    fun limit(ctx: Context): Long = prefs(ctx).getLong("netStreamLimit", 20L shl 30)
    fun setLimit(ctx: Context, bytes: Long) { prefs(ctx).edit().putLong("netStreamLimit", bytes).apply() }

    private fun update(ctx: Context, game: Game) {
        synchronized(lock) { saveGames(ctx, games(ctx).map { if (it.dir == game.dir) game else it }) }
    }

    fun setMode(ctx: Context, game: Game, mode: Mode) = update(ctx, games(ctx).firstOrNull { it.dir == game.dir }?.with(mode = mode) ?: game.with(mode = mode))

    private fun manifestFile(ctx: Context, game: Game) = File(NetEngine.dir(ctx), "streams/" + game.dir.name + ".json")

    /** Every file of the game as it came from the drive: its path, size and date (see [clear]). */
    private fun writeManifest(ctx: Context, game: Game, files: List<NetFs.Entry>) {
        val f = manifestFile(ctx, game).apply { parentFile?.mkdirs() }
        val a = JSONArray()
        files.forEach { a.put(JSONArray().put(it.path).put(it.size).put(it.modified)) }
        f.writeText(a.toString())
    }

    private class Item(val path: String, val size: Long, val modified: Long)

    private fun manifest(ctx: Context, game: Game): List<Item>? = runCatching {
        val a = JSONArray(manifestFile(ctx, game).readText())
        (0 until a.length()).map { i -> a.getJSONArray(i).let { Item(it.getString(0), it.getLong(1), it.getLong(2)) } }
    }.getOrNull()

    /**
     * Disk space [f] takes. A small file that is all hole still counts one 4 KB block on f2fs (st_blocks 8), which put
     * a few MB of nothing in the total of a game with many small files: a file of one block or less counts only when
     * it holds data (lseek SEEK_DATA finds some).
     */
    private fun allocated(f: File): Long = runCatching {
        val blocks = android.system.Os.stat(f.path).st_blocks * 512
        if (blocks > 4096) return@runCatching blocks
        val fd = android.system.Os.open(f.path, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.lseek(fd, 0L, SEEK_DATA)
            blocks
        } catch (e: android.system.ErrnoException) {
            if (e.errno == android.system.OsConstants.ENXIO) 0L else blocks   // ENXIO: no data at all
        } finally {
            android.system.Os.close(fd)
        }
    }.getOrDefault(0L)

    /** Linux's lseek whence for "the next data" (not in OsConstants). */
    private const val SEEK_DATA = 3

    /** Bytes the game's stand-in takes on the device now. */
    fun cachedBytes(game: Game): Long {
        var total = 0L
        game.dir.walkTopDown().forEach { if (it.isFile) total += allocated(it) }
        return total
    }

    /** Files Bannerlator itself reads (the exe checks, the name and icon): never emptied. */
    private fun keptAlways(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return name.substringAfterLast('.', "") in PREFETCH_EXTS || name in PREFETCH_NAMES || isScript(name) ||
            (name.startsWith("goggame-") && name.endsWith(".info"))
    }

    /**
     * Empties what the game has loaded: every file that is still exactly as it came from the drive (same size, same
     * date; libnetfs.so keeps the date when it fills a file in) goes back to an empty stand-in. A file the game wrote
     * to (a save or a setting kept in its folder) has another date and is left alone, and so are files it made.
     * Returns the bytes freed. A game made by an older build has no file list and is left alone.
     */
    fun clear(ctx: Context, game: Game): Long {
        val items = manifest(ctx, game) ?: return 0L
        var freed = 0L
        for (it in items) {
            if (keptAlways(it.path)) continue
            val f = File(game.dir, it.path)
            if (!f.isFile || f.length() != it.size) continue
            if (it.modified > 0 && kotlin.math.abs(f.lastModified() - it.modified) > 2_000) continue
            val before = allocated(f)
            if (before == 0L) continue
            runCatching {
                RandomAccessFile(f, "rw").use { r -> r.setLength(0); r.setLength(it.size) }
                if (it.modified > 0) f.setLastModified(it.modified)
                freed += before - allocated(f)
            }
        }
        Log.i(TAG, "emptied ${game.name}: ${freed shr 20} MB freed")
        return freed
    }

    /** Over the limit: empties games kept as "Keep what's loaded", the one played longest ago first, not [except]. */
    fun enforceLimit(ctx: Context, except: File? = null) {
        val max = limit(ctx)
        if (max <= 0) return
        val list = games(ctx)
        var total = list.sumOf { cachedBytes(it) }
        if (total <= max) return
        for (g in list.filter { it.mode == Mode.KEEP && it.dir != except }.sortedBy { it.lastPlayed }) {
            total -= clear(ctx, g)
            if (total <= max) break
        }
    }

    /** Empties every game that isn't kept as a full copy. */
    fun freeUp(ctx: Context): Long = games(ctx).filter { it.mode != Mode.FULL }.sumOf { clear(ctx, it) }

    /** Fetches every part of the game still missing ("Keep a full copy"); [onProgress] gets bytes done / to do. */
    suspend fun fetchAll(ctx: Context, game: Game, onProgress: (Long, Long) -> Unit) {
        val app = ctx.applicationContext
        val drive = NetDriveStore.list(app).firstOrNull { it.id == game.driveId } ?: throw java.io.IOException("Its network drive was removed")
        val items = manifest(app, game) ?: NetFs.listAll(app, drive, game.remotePath).filter { !it.isDir }.map { Item(it.path, it.size, it.modified) }
        val missing = items.filter { val f = File(game.dir, it.path); f.isFile && f.length() == it.size && allocated(f) < it.size }
        val total = missing.sumOf { it.size }.coerceAtLeast(1)
        val done = java.util.concurrent.atomic.AtomicLong(0)
        onProgress(0, total)
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(PREFETCH_PARALLEL)
            missing.map { it ->
                launch {
                    gate.acquire()
                    try {
                        val f = File(game.dir, it.path)
                        f.delete()
                        NetFs.transfer(app, NetFs.Side.Remote(drive, NetFs.join(game.remotePath, it.path)), NetFs.Side.Local(f), isDir = false, move = false) { _, _ -> }
                        if (it.modified > 0) f.setLastModified(it.modified)
                        onProgress(done.addAndGet(it.size), total)
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }

    /**
     * Removes a streamed game: its stand-in, its file list and its Games entries (shortcuts whose exe is in the stand-in).
     */
    fun remove(ctx: Context, game: Game) {
        val app = ctx.applicationContext
        runCatching {
            for (c in com.winlator.star.container.ContainerManager(app).containers) {
                c.getDesktopDir().listFiles { f -> f.name.endsWith(".desktop") }?.forEach { d ->
                    val sc = runCatching { com.winlator.star.container.Shortcut(c, d) }.getOrNull() ?: return@forEach
                    val exe = runCatching { WinePath.resolveAndroidPath(c, sc.path) }.getOrNull() ?: return@forEach
                    val p = runCatching { exe.canonicalPath }.getOrDefault(exe.absolutePath)
                    if (p.startsWith(game.dir.absolutePath + "/")) d.delete()
                }
            }
        }
        game.dir.deleteRecursively()
        manifestFile(app, game).delete()
        synchronized(lock) { saveGames(app, games(app).filter { it.dir != game.dir }) }
    }

    /** The game behind a launch of [winPath] closed: a "Stream only" game is emptied again. */
    @JvmStatic
    fun onGameExit(ctx: Context, container: Container?, winPath: String?) {
        if (container == null || winPath.isNullOrBlank()) return
        val app = ctx.applicationContext
        Thread({
            runCatching {
                val exe = WinePath.resolveAndroidPath(container, winPath) ?: return@runCatching
                val game = gameOf(app, exe) ?: return@runCatching
                statusFile(app).delete()
                if (game.mode == Mode.STREAM) clear(app, game)
            }
        }, "netstream-exit").start()
    }
}
