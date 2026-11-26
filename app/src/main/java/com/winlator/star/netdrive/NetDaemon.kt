package com.winlator.star.netdrive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One `rclone rcd` (the remote-control daemon every network-drive call goes through), started on first use and
 * stopped once nothing has needed it for [IDLE_STOP_MS]. It listens on 127.0.0.1 only, on a free port, behind a
 * random user/password passed through the environment (not the command line).
 *
 * Drives are given to rclone as on-the-fly remotes (":smb,host=…:"), so rclone keeps no config of its own: the
 * config file stays empty and the logins live only in [NetDriveStore] (Keystore-sealed).
 *
 * Ported from BFE (The412Banner/BFE, cloud/RcdProcess.kt + RcloneDaemon.kt), without the config encryption BFE
 * needs for its cloud sign-ins.
 */
object NetDaemon {
    private const val TAG = "BL_NETDRIVE"
    private const val IDLE_STOP_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val holders = mutableSetOf<String>()
    private var idleStop: Job? = null
    @Volatile private var process: Process? = null
    @Volatile private var client: RcClient? = null
    /** "Basic …" for the running daemon (network games' libnetfs.so reads files through its file server). */
    @Volatile private var basicAuth: String? = null
    private val tail = ArrayDeque<String>()

    fun retain(tag: String) = synchronized(holders) {
        holders += tag
        idleStop?.cancel()
        idleStop = null
    }

    fun release(tag: String) = synchronized(holders) {
        holders -= tag
        if (holders.isEmpty()) {
            idleStop?.cancel()
            idleStop = scope.launch {
                delay(IDLE_STOP_MS)
                val idle = synchronized(holders) { holders.isEmpty() }
                if (idle) startLock.withLock { stopLocked() }
            }
        }
    }

    /** The daemon's client, starting rclone first when it isn't running. Throws if it can't start. */
    suspend fun client(ctx: Context): RcClient = startLock.withLock {
        val p = process
        val c = client
        if (p != null && p.isAlive && c != null) return@withLock c
        val app = ctx.applicationContext
        if (!NetEngine.isInstalled(app)) throw IOException("The network engine isn't installed")
        withContext(Dispatchers.IO) { start(app) }
    }

    /** Port and "Basic …" auth of the running daemon, or null. */
    fun serveInfo(): Pair<Int, String>? {
        val c = client ?: return null
        val a = basicAuth ?: return null
        return if (process?.isAlive == true) c.port to a else null
    }

    fun stopNow() {
        scope.launch { startLock.withLock { stopLocked() } }
    }

    /** rclone's last log lines, for error details (passwords are never in them: they go in obscured and are cut out). */
    fun logTail(lines: Int = 12): String = synchronized(tail) { tail.toList().takeLast(lines).joinToString("\n") }

    private fun stopLocked() {
        val p = process ?: return
        val c = client
        process = null
        client = null
        runCatching { c?.call("core/quit", readTimeoutMs = 3_000) }
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        Log.i(TAG, "rcd stopped")
    }

    private fun start(app: Context): RcClient {
        val dir = NetEngine.dir(app).apply { mkdirs() }
        val config = File(dir, "rclone.conf").apply { if (!exists()) writeText("") }
        val cache = File(app.cacheDir, "netdrives/rclone").apply { mkdirs() }
        val tmp = File(app.cacheDir, "netdrives/tmp").apply { deleteRecursively(); mkdirs() }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val pass = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val user = "bl"
        val pb = ProcessBuilder(
            NetEngine.binary(app).absolutePath, "rcd",
            "--rc-addr", "127.0.0.1:$port",
            // GET /[remote]/path serves files (ranged): how a streamed network game reads its files.
            "--rc-serve",
            // Keep finished jobs around long enough for a slow poller to read their result.
            "--rc-job-expire-duration", "1h",
            "--config", config.absolutePath,
            "--cache-dir", cache.absolutePath,
            "--temp-dir", tmp.absolutePath,
            "--log-level", "NOTICE",
            "--ask-password=false",
            // A NAS that drops off the network should fail a call in seconds, not minutes.
            "--contimeout", "8s",
            "--timeout", "60s",
        )
        pb.redirectErrorStream(true)
        // A local path given as an fs ("/storage/…") is absolute; "/" keeps relative ones meaning the same.
        pb.directory(File("/"))
        pb.environment().apply {
            put("RCLONE_RC_USER", user)
            put("RCLONE_RC_PASS", pass)
            put("HOME", dir.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            // Go's TLS (WebDAV over https) uses Android's CA stores.
            val certs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts").filter { File(it).isDirectory }
            if (certs.isNotEmpty()) put("SSL_CERT_DIR", certs.joinToString(":"))
        }
        val proc = pb.start()
        proc.outputStream.close()
        synchronized(tail) { tail.clear() }
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { raw ->
                    val line = NetFs.scrub(raw)
                    synchronized(tail) {
                        tail.addLast(line)
                        while (tail.size > 200) tail.removeFirst()
                    }
                    Log.i(TAG, line)
                }
            }
        }.apply { name = "rclone-log"; isDaemon = true; start() }

        val rc = RcClient(port, user, pass)
        val deadline = System.currentTimeMillis() + 20_000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                Thread.sleep(150)
                throw IOException("The network engine stopped (exit ${proc.exitValue()}): ${logTail(4)}".trim())
            }
            try {
                rc.call("rc/noop", JSONObject(), readTimeoutMs = 2_000)
                process = proc
                client = rc
                basicAuth = "Basic " + android.util.Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                Log.i(TAG, "rcd running on ${rc.baseUrl}")
                return rc
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(120)
            }
        }
        proc.destroy()
        throw IOException("The network engine didn't start in time: ${lastError?.message ?: ""}".trim())
    }
}
