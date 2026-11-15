package com.winlator.star.netdrive

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the file manager does on a network drive, through the rclone daemon ([NetDaemon]): list a folder,
 * make one, delete, rename, and copy or move between a drive and this device (or two drives), with progress.
 *
 * A drive reaches rclone as an on-the-fly remote string (":smb,host="…",user="…",pass="…":share/folder). The
 * password goes in obscured (rclone's reversible "obscure", asked from rclone itself); every password, obscured
 * password and remote string is registered with [scrub], which cuts them out of every error and log line shown.
 */
object NetFs {
    data class Entry(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    /** One end of a copy: a real file or folder, or a path on a drive. */
    sealed class Side {
        data class Local(val file: File) : Side()
        data class Remote(val drive: NetDrive, val path: String) : Side()
    }

    /** rclone's default time (2000-01-01T00:00:00Z), reported when a server has no date. */
    private const val RCLONE_DEFAULT_TIME_MS = 946_684_800_000L

    private val secrets = ConcurrentHashMap.newKeySet<String>()
    private val obscured = ConcurrentHashMap<String, String>()

    /** [s] with every known password and drive remote string replaced. */
    fun scrub(s: String): String {
        var out = s
        // Longest first: a remote string contains its obscured password.
        secrets.sortedByDescending { it.length }.forEach { if (it.length >= 3) out = out.replace(it, "***") }
        return out.replace(Regex("""pass=("[^"]*"|'[^']*'|[^,:\s]*)"""), "pass=***")
    }

    private fun q(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

    private suspend fun obscure(rc: RcClient, plain: String): String {
        obscured[plain]?.let { return it }
        val o = withContext(Dispatchers.IO) { rc.call("core/obscure", JSONObject().put("clear", plain), readTimeoutMs = 10_000) }.getString("obscured")
        secrets.add(plain)
        secrets.add(o)
        obscured[plain] = o
        return o
    }

    /** The remote string for the drive's own folder (where it opens). */
    private suspend fun root(rc: RcClient, d: NetDrive): String {
        val pass = d.password.takeIf { it.isNotEmpty() }?.let { obscure(rc, it) }
        val folder = d.folder.trim('/')
        val s = when (d.kind) {
            NetKind.SMB -> buildString {
                append(":smb,host=").append(q(d.host)).append(",port=").append(d.realPort)
                append(",user=").append(q(d.user.ifBlank { "guest" }))
                pass?.let { append(",pass=").append(q(it)) }
                append(':').append(folder)
            }
            NetKind.SFTP -> buildString {
                append(":sftp,host=").append(q(d.host)).append(",port=").append(d.realPort)
                append(",user=").append(q(d.user))
                pass?.let { append(",pass=").append(q(it)) }
                // No remote shell commands (hash checks): plain file reads and writes only.
                append(",shell_type=none")
                append(':').append(folder)
            }
            NetKind.WEBDAV -> buildString {
                val scheme = if (d.https) "https" else "http"
                val defPort = if (d.https) 443 else 80
                val url = "$scheme://${d.host}" + (if (d.realPort != defPort) ":${d.realPort}" else "") + "/" + folder
                append(":webdav,url=").append(q(url)).append(",vendor=other")
                if (d.user.isNotBlank()) append(",user=").append(q(d.user))
                pass?.let { append(",pass=").append(q(it)) }
                append(':')
            }
            NetKind.FTP -> buildString {
                append(":ftp,host=").append(q(d.host)).append(",port=").append(d.realPort)
                append(",user=").append(q(d.user.ifBlank { "anonymous" }))
                append(",pass=").append(q(pass ?: obscure(rc, "anonymous")))
                append(':').append(folder)
            }
        }
        secrets.add(s)
        return s
    }

    /** The remote string for [path] inside the drive (a folder given to a whole-folder copy). */
    private suspend fun fsAt(rc: RcClient, d: NetDrive, path: String): String {
        val r = root(rc, d)
        val p = path.trim('/')
        if (p.isEmpty()) return r
        return if (r.endsWith(":")) r + p else "$r/$p"
    }

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else dir.trimEnd('/') + "/" + name
    fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    fun nameOf(path: String): String = path.substringAfterLast('/')

    /** Runs [block] with the daemon kept alive for it. */
    private suspend fun <T> withRc(ctx: Context, block: suspend (RcClient) -> T): T {
        val tag = "call-" + UUID.randomUUID()
        NetDaemon.retain(tag)
        try {
            val rc = NetDaemon.client(ctx)
            return block(rc)
        } catch (e: RcException) {
            throw IOException(friendly(e.message))
        } finally {
            NetDaemon.release(tag)
        }
    }

    suspend fun list(ctx: Context, d: NetDrive, path: String): List<Entry> = withRc(ctx) { rc ->
        val fs = root(rc, d)
        val params = JSONObject().put("fs", fs).put("remote", path.trim('/'))
        val out = withContext(Dispatchers.IO) {
            try {
                rc.call("operations/list", params)
            } catch (e: RcException) {
                // A NAS closes idle connections: drop rclone's cached ones and try once more.
                if (!isDroppedConnection(e.message)) throw e
                runCatching { rc.call("fscache/clear", readTimeoutMs = 15_000) }
                delay(1_000)
                rc.call("operations/list", params)
            }
        }
        val arr = out.optJSONArray("list") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseEntry) }
    }

    /** Null when the drive answers (its top folder lists), else what went wrong in plain words. */
    suspend fun test(ctx: Context, d: NetDrive): String? = try {
        list(ctx, d, "")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Couldn't connect"
    }

    /** Everything under [path] (folders and files, every level), paths relative to [path]. */
    suspend fun listAll(ctx: Context, d: NetDrive, path: String): List<Entry> = withRc(ctx) { rc ->
        val o = withContext(Dispatchers.IO) {
            rc.call("operations/list", JSONObject().put("fs", fsAt(rc, d, path)).put("remote", "")
                .put("opt", JSONObject().put("recurse", true)), readTimeoutMs = 600_000)
        }
        val arr = o.optJSONArray("list") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseEntry) }
    }

    /** The daemon's URL path for [path] on the drive ("/%5B<remote>%5D/"), for its file server. */
    suspend fun servePrefix(ctx: Context, d: NetDrive, path: String): String = withRc(ctx) { rc ->
        val fs = fsAt(rc, d, path)
        "/%5B" + java.net.URLEncoder.encode(fs, "UTF-8").replace("+", "%20") + "%5D/"
    }

    /** Bytes and file count of everything under [path] (a folder walk on the server). */
    suspend fun size(ctx: Context, d: NetDrive, path: String): Pair<Long, Long> = withRc(ctx) { rc ->
        val o = withContext(Dispatchers.IO) { rc.call("operations/size", JSONObject().put("fs", fsAt(rc, d, path)), readTimeoutMs = 300_000) }
        o.optLong("bytes", 0L) to o.optLong("count", 0L)
    }

    suspend fun mkdir(ctx: Context, d: NetDrive, path: String) = withRc(ctx) { rc ->
        val fs = root(rc, d)
        withContext(Dispatchers.IO) { rc.call("operations/mkdir", JSONObject().put("fs", fs).put("remote", path.trim('/'))) }
        Unit
    }

    suspend fun delete(ctx: Context, d: NetDrive, path: String, isDir: Boolean) = withRc(ctx) { rc ->
        val fs = root(rc, d)
        val p = JSONObject().put("fs", fs).put("remote", path.trim('/'))
        withContext(Dispatchers.IO) {
            if (isDir) rc.call("operations/purge", p, readTimeoutMs = 600_000) else rc.call("operations/deletefile", p)
        }
        Unit
    }

    suspend fun rename(ctx: Context, d: NetDrive, path: String, isDir: Boolean, newName: String) = withRc(ctx) { rc ->
        val target = join(parentOf(path.trim('/')), newName)
        if (isDir) {
            runJob(rc, "sync/move", JSONObject()
                .put("srcFs", fsAt(rc, d, path)).put("dstFs", fsAt(rc, d, target))
                .put("createEmptySrcDirs", true).put("deleteEmptySrcDirs", true)) { _, _ -> }
        } else {
            val fs = root(rc, d)
            withContext(Dispatchers.IO) {
                rc.call("operations/movefile", JSONObject().put("srcFs", fs).put("srcRemote", path.trim('/')).put("dstFs", fs).put("dstRemote", target), readTimeoutMs = 600_000)
            }
        }
        Unit
    }

    /**
     * Copies (or moves, [move]) [src] to [dst] — a file to a file, a folder into a folder of that name — with
     * [onProgress] (bytes done, bytes known so far). Cancelling the coroutine stops the rclone job. A folder copy
     * skips files already there with the same size and date, so running it again only copies what is missing.
     */
    suspend fun transfer(ctx: Context, src: Side, dst: Side, isDir: Boolean, move: Boolean, onProgress: (Long, Long) -> Unit) = withRc(ctx) { rc ->
        suspend fun dirFs(s: Side): String = when (s) {
            is Side.Local -> s.file.absolutePath
            is Side.Remote -> fsAt(rc, s.drive, s.path)
        }
        suspend fun fileParts(s: Side): Pair<String, String> = when (s) {
            is Side.Local -> "/" to s.file.absolutePath.trimStart('/')
            is Side.Remote -> root(rc, s.drive) to s.path.trim('/')
        }
        if (isDir) {
            val params = JSONObject().put("srcFs", dirFs(src)).put("dstFs", dirFs(dst)).put("createEmptySrcDirs", true)
            if (move) params.put("deleteEmptySrcDirs", true)
            runJob(rc, if (move) "sync/move" else "sync/copy", params, onProgress)
        } else {
            val (sf, sr) = fileParts(src)
            val (df, dr) = fileParts(dst)
            runJob(rc, if (move) "operations/movefile" else "operations/copyfile",
                JSONObject().put("srcFs", sf).put("srcRemote", sr).put("dstFs", df).put("dstRemote", dr), onProgress)
        }
    }

    /** Runs one rclone call as a job, reporting its bytes; throws its error; stops it when cancelled. */
    private suspend fun runJob(rc: RcClient, path: String, params: JSONObject, onProgress: (Long, Long) -> Unit) {
        // Wi-Fi blips and a NAS waking up are normal: retry a dropped connection many times before failing a file.
        params.put("_config", JSONObject().put("LowLevelRetries", 20))
        val id = withContext(Dispatchers.IO) { rc.startJob(path, params) }
        val group = "job/$id"
        try {
            while (true) {
                delay(400)
                val st = withContext(Dispatchers.IO) { rc.jobStatus(id) }
                runCatching {
                    val s = withContext(Dispatchers.IO) { rc.stats(group) }
                    onProgress(s.optLong("bytes", 0L), s.optLong("totalBytes", 0L))
                }
                if (st.finished) {
                    if (!st.success) throw IOException(friendly(st.error))
                    return
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { rc.stopJob(id) }
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { rc.deleteStats(group) }
        }
    }

    private fun parseEntry(o: JSONObject): Entry {
        val path = o.optString("Path")
        val mod = runCatching { OffsetDateTime.parse(o.optString("ModTime")).toInstant().toEpochMilli() }.getOrDefault(0L)
        return Entry(
            path = path,
            name = o.optString("Name").ifEmpty { nameOf(path) },
            isDir = o.optBoolean("IsDir", false),
            size = o.optLong("Size", -1L).coerceAtLeast(0L),
            modified = if (mod == RCLONE_DEFAULT_TIME_MS) 0L else mod,
        )
    }

    private fun isDroppedConnection(message: String?): Boolean {
        val m = message.orEmpty()
        return m.contains("connection error:") || m.contains("unexpected EOF") || m.contains("connection reset") ||
            m.contains("connection abort") || m.contains("broken pipe") || m.contains("use of closed network connection") ||
            m.trimEnd().endsWith(": EOF")
    }

    /** rclone's error in plain words (and never with a password or remote string in it). */
    fun friendly(raw: String?): String {
        val m = raw.orEmpty()
        val l = m.lowercase()
        return when {
            "connection refused" in l || "no route to host" in l || "i/o timeout" in l || "network is unreachable" in l ||
                "host is down" in l || "no such host" in l || "dial tcp" in l -> "Can't reach the drive. Is it on and on this network?"
            "logon_failure" in l || "unable to authenticate" in l || "login incorrect" in l || "530 " in l ||
                "401" in l || "authentication" in l || "wrong password" in l -> "Wrong user name or password"
            "bad_network_name" in l -> "That share doesn't exist on this server"
            // Something answered on that port, but not the kind of server picked (SMB on the SFTP port, …).
            "invalid transport format" in l || "ssh: handshake failed" in l || "overflow reading version string" in l ||
                "malformed http response" in l || "short response" in l ->
                "The server on that port isn't this kind of drive. Check the type and the port"
            "access_denied" in l || "permission denied" in l || "403" in l -> "No permission for this"
            "object not found" in l || "directory not found" in l || "object_name_not_found" in l || "object_path_not_found" in l ||
                "no such file" in l -> "Not found (it may have been moved or deleted)"
            "context canceled" in l -> "Cancelled"
            "no space left" in l || "disk_full" in l -> "Not enough free space"
            else -> scrub(m).lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "Something went wrong"
        }
    }
}
