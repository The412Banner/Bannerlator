package com.winlator.star.netdrive

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * The network engine behind Network drives: rclone, NOT bundled in the APK (about 15 MB to download, 48 MB
 * unpacked). It is downloaded on the user's tap from BFE's GitHub release (the same build BFE uses, made by
 * BFE's build-rclone.yml), checked against the SHA-256 pinned here, unpacked to filesDir/netdrives/rclone and
 * run from there (exec from app storage is allowed at targetSdk 28).
 *
 * Offline / testing: a copy of [ASSET_NAME] in the Download folder is used instead of the network.
 *
 * Ported from BFE (The412Banner/BFE, cloud/RcloneManager.kt). rclone is MIT-licensed (NOTICE_RCLONE.txt).
 */
object NetEngine {
    private const val TAG = "BL_NETDRIVE"

    const val VERSION = "v1.75.2"
    const val RELEASE_TAG = "rclone-v1.75.2-android-arm64"
    const val ASSET_NAME = "rclone-v1.75.2-android-arm64.gz"
    const val DOWNLOAD_URL = "https://github.com/The412Banner/BFE/releases/download/$RELEASE_TAG/$ASSET_NAME"
    const val GZ_SHA256 = "259868b5842e39200828cd853d7948339ab989aeb47381ee37dc360f7ceed34b"
    const val GZ_SIZE = 14_998_988L
    const val BIN_SHA256 = "190151335ba679eda9b67bd17099e9af9fb6162a9f5bdd9a52c099b52ca2c6c2"
    const val BIN_SIZE = 47_616_352L

    sealed class State {
        object Unknown : State()
        object NotInstalled : State()
        data class Downloading(val bytes: Long, val total: Long) : State()
        object Verifying : State()
        object Installed : State()
        data class Failed(val reason: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Compose state: the Network section redraws when it changes. */
    var state by mutableStateOf<State>(State.Unknown)
        private set
    private var job: Job? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "netdrives")
    fun binary(ctx: Context): File = File(dir(ctx), "rclone")
    private fun marker(ctx: Context): File = File(dir(ctx), "rclone.sha256")

    fun isInstalled(ctx: Context): Boolean {
        val bin = binary(ctx)
        return bin.isFile && bin.canExecute() && bin.length() == BIN_SIZE &&
            runCatching { marker(ctx).readText().trim() }.getOrNull() == BIN_SHA256
    }

    /** Re-reads the install state (a size check and a 64-byte marker). */
    fun refresh(ctx: Context) {
        val s = state
        if (s is State.Downloading || s is State.Verifying) return
        state = if (isInstalled(ctx)) State.Installed else if (s is State.Failed) s else State.NotInstalled
    }

    private fun localCopy(): File? = runCatching {
        @Suppress("DEPRECATION")
        File(Environment.getExternalStorageDirectory(), "Download/$ASSET_NAME").takeIf { it.isFile && it.length() == GZ_SIZE }
    }.getOrNull()

    fun install(ctx: Context) {
        val s = state
        if (s is State.Downloading || s is State.Verifying) return
        val app = ctx.applicationContext
        state = State.Downloading(0L, GZ_SIZE)
        job = scope.launch {
            val dir = dir(app).apply { mkdirs() }
            val gzPart = File(dir, "rclone.gz.part")
            val binPart = File(dir, "rclone.part")
            try {
                val need = GZ_SIZE + BIN_SIZE + 8L * 1024 * 1024
                val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
                if (free < need) throw UserFacing("Not enough free space: about ${mb(need)} MB is needed while installing")
                val local = localCopy()
                val gzSha = if (local != null) local.inputStream().use { copyHashing(it, gzPart, GZ_SIZE, true) } else download(gzPart)
                state = State.Verifying
                if (gzSha != GZ_SHA256) {
                    Log.w(TAG, "download sha256 $gzSha != $GZ_SHA256")
                    throw UserFacing("The download didn't match its checksum. Try again")
                }
                val binSha = GZIPInputStream(gzPart.inputStream().buffered(256 * 1024)).use { copyHashing(it, binPart, BIN_SIZE, false) }
                gzPart.delete()
                if (binSha != BIN_SHA256) throw UserFacing("The unpacked engine didn't match its checksum")
                val bin = binary(app)
                bin.delete()
                if (!binPart.renameTo(bin)) throw IOException("rename failed")
                bin.setExecutable(true, true)
                bin.setReadable(true, true)
                selfTest(bin)
                marker(app).writeText(BIN_SHA256)
                Log.i(TAG, "rclone $VERSION installed (${bin.length()} bytes)")
                state = State.Installed
            } catch (e: kotlinx.coroutines.CancellationException) {
                gzPart.delete(); binPart.delete()
                state = State.NotInstalled
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                gzPart.delete(); binPart.delete()
                // Never leave a half-checked engine behind (it is only renamed into place once verified).
                runCatching { binary(app).delete(); marker(app).delete() }
                state = State.Failed(
                    when (e) {
                        is UserFacing -> e.message ?: "Install failed"
                        is HttpStatus -> "The download failed (HTTP ${e.code}). Try again"
                        else -> "The download didn't finish. Check your connection and try again"
                    },
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Deletes the engine; saved drives stay. */
    fun uninstall(ctx: Context) {
        NetDaemon.stopNow()
        binary(ctx).delete()
        marker(ctx).delete()
        state = State.NotInstalled
    }

    private class UserFacing(msg: String) : Exception(msg)
    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun mb(bytes: Long) = (bytes + 1024 * 1024 - 1) / (1024 * 1024)

    private suspend fun download(dest: File): String {
        var target = URL(DOWNLOAD_URL)
        var conn: HttpURLConnection? = null
        // GitHub answers with a redirect to its object store; follow a few hops by hand.
        for (hop in 0 until 6) {
            val c = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Bannerlator/${BuildConfig.VERSION_NAME} (Android)")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect()
                target = URL(target, loc)
                continue
            }
            if (code !in 200..299) { c.disconnect(); throw HttpStatus(code) }
            conn = c
            break
        }
        val c = conn ?: throw IOException("too many redirects")
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: GZ_SIZE
            return c.inputStream.use { copyHashing(it, dest, total, true) }
        } finally {
            c.disconnect()
        }
    }

    /** Copies [input] to [dest] while hashing; reports progress when [report] (the download phase). */
    private suspend fun copyHashing(input: InputStream, dest: File, total: Long, report: Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastEmit = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                done += n
                if (report) {
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 200) {
                        lastEmit = now
                        state = State.Downloading(done, total)
                    }
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Runs `rclone version` once so a binary this device can't execute fails here, not later. */
    private fun selfTest(bin: File) {
        val p = ProcessBuilder(bin.absolutePath, "version").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw UserFacing("The network engine didn't start on this device")
        }
        if (p.exitValue() != 0 || !out.contains(VERSION)) {
            Log.w(TAG, "self-test failed (${p.exitValue()}): $out")
            throw UserFacing("The network engine can't run on this device: ${out.lineSequence().firstOrNull().orEmpty().take(120)}")
        }
    }
}
