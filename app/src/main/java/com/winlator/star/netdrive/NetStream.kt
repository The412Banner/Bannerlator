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
     * installers under redist folders. Everything else streams while playing (11-15: fetching every file up to
     * 4 MB took Bayonetta 709 files / 544 MB, one at a time, which looked like a copy).
     */
    private val PREFETCH_EXTS = setOf("exe", "dll")
    private val PREFETCH_NAMES = setOf("steam_appid.txt", "microsoftgame.config", "appxmanifest.xml")
    private val SKIP_DIRS = setOf("_commonredist", "commonredist", "redist", "redistributable", "redistributables",
        "directx", "dotnet", "dotnetfx", "vcredist", "vc_redist", "prerequisites", "prereq", "installer", "installers", "__installer")
    private const val PREFETCH_PARALLEL = 4

    private fun prefetchable(e: NetFs.Entry): Boolean {
        if (e.isDir || e.size <= 0) return false
        val dirs = e.path.split('/').dropLast(1).map { it.lowercase() }
        if (dirs.any { it in SKIP_DIRS }) return false
        val name = e.name.lowercase()
        return name.substringAfterLast('.', "") in PREFETCH_EXTS || name in PREFETCH_NAMES ||
            (name.startsWith("goggame-") && name.endsWith(".info") && e.size < 1L shl 20)
    }

    /** One streamed game: where its stand-in is, and which folder of which drive it stands in for. */
    class Game(val dir: File, val driveId: String, val remotePath: String) {
        fun toJson(): JSONObject = JSONObject().put("dir", dir.absolutePath).put("drive", driveId).put("path", remotePath)
    }

    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "netgames").apply { mkdirs() }.canonicalFile

    private fun registryFile(ctx: Context) = File(NetEngine.dir(ctx).apply { mkdirs() }, "streams.json")
    private val lock = Any()

    fun games(ctx: Context): List<Game> = synchronized(lock) {
        val f = registryFile(ctx)
        if (!f.isFile) return emptyList()
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o -> Game(File(o.getString("dir")), o.getString("drive"), o.optString("path")) }
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
    suspend fun create(ctx: Context, drive: NetDrive, remotePath: String, dir: File, onProgress: (String, Float?) -> Unit) {
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
        val game = Game(dir.canonicalFile, drive.id, remotePath)
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
            conf.writeText("port=$port\nprefix=$prefix\nauth=$auth\n")
            conf.setReadable(false, false); conf.setReadable(true, true)
            Log.i(TAG, "streaming ${game.dir.name} from ${drive.name}")
            mapOf("BLNETFS_ROOT" to game.dir.absolutePath, "BLNETFS_CONF" to conf.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "stream setup failed: ${NetFs.scrub(t.message ?: t.toString())}")
            null
        }
    }
}
