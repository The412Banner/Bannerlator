package com.winlator.star.store

import android.os.Build
import android.util.Log
import com.winlator.star.store.blsteam.BlSteamEngine
import com.winlator.star.store.blsteam.BlSteamSession
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The Steam Cloud (UFS) primitives every save move in [SteamCloudSaveManager] / [SaveSyncStore] is
 * built from, behind one engine-agnostic seam (Phase 3b-1 of docs/STEAM_RUST_ENGINE_PLAN.md):
 *
 *  - [JavaSteamCloudBackend] — the JavaSteam `SteamCloud` handler path, byte-for-byte the calls the
 *    manager made before this seam existed (flag OFF).
 *  - [BlCloudBackend] — the Rust engine's `ccloud` service calls (`GetAppFileChangelist`,
 *    `ClientFileDownload`, `BeginAppUploadBatch` / `ClientBeginFileUpload` / `ClientCommitFileUpload`
 *    / `CompleteAppUploadBatch`, `AppLaunchIntent` / `AppExitSyncDone`) on libblsteam.so (flag ON).
 *
 * Both speak the same remote-path convention (`pathPrefix + filename`, joined with a single '/'), so
 * the Library layout, the SHA-1 diff and the newest-wins compare above this seam are identical on
 * either engine. SAFETY is preserved by construction: neither implementation exposes a delete —
 * [beginBatch] hard-wires `filesToDelete` to an empty list, exactly like the manager always did.
 */
interface SteamCloudBackend {

    /** One entry of the app's cloud manifest. [timestampMs] is the cloud-side mtime. */
    class CloudFile(val remotePath: String, val sha: ByteArray, val timestampMs: Long, val size: Long)

    /** Which engine serves this backend — for log lines only. */
    val label: String

    /** The remote manifest. Throws on a CM failure / timeout (callers treat that as "cloud unknown"). */
    fun listFiles(appId: Int): List<CloudFile>

    /** GET one cloud file into [dest] (parents created, mtime = cloud timestamp). False on failure. */
    fun downloadOne(appId: Int, remotePath: String, dest: File): Boolean

    /** Open an upload batch for [filesToUpload]. Deletions are never requested. 0 on failure. */
    fun beginBatch(appId: Int, filesToUpload: List<String>): Long

    /** Upload one local file under [cloudPath] inside the open batch. True iff Steam committed it. */
    fun uploadOne(appId: Int, file: File, cloudPath: String, batchId: Long): Boolean

    /** Close the batch with the aggregate result. */
    fun completeBatch(appId: Int, batchId: Long, allOk: Boolean)

    /**
     * Tell Steam this client is about to run [appId] (`CCloud.AppLaunchIntent`) — the genuine client
     * does this before a launch so the server can report pending cloud operations from another
     * machine. Returns the pending-operation codes (empty = clear to launch), or null when the engine
     * has no such call. Best-effort; never blocks a launch.
     */
    fun signalAppLaunchIntent(appId: Int): List<Int>? = null

    /** Tell Steam the post-exit sync for [appId] is done (`CCloud.AppExitSyncDone`). Best-effort. */
    fun signalAppExitSyncDone(appId: Int, uploadsCompleted: Boolean, uploadsRequired: Boolean) {}

    companion object {
        private const val TAG = "BH_STEAM_CLOUD"

        /**
         * The backend for the live session: the Rust engine when the flag is ON (and it is logged on),
         * else the JavaSteam handler when bound. Null = not signed in / no session — the callers keep
         * their "Not signed in to Steam" behaviour.
         */
        @JvmStatic
        fun current(): SteamCloudBackend? {
            val repo = SteamRepository.getInstance()
            if (repo.isRustEngine) {
                val s = BlSteamEngine.session() ?: return null
                if (!BlSteamEngine.isLoggedOn()) return null
                return BlCloudBackend(s)
            }
            val sc = try { repo.steamCloud } catch (t: Throwable) { null } ?: return null
            return JavaSteamCloudBackend(sc)
        }

        /** Machine label Steam shows for this device's uploads ("saved on …"). Same on both engines. */
        fun machineName(): String = "Bannerlator (${Build.MODEL})"

        /** Join a manifest path prefix and a filename the way Steam stores them (single '/'). */
        fun joinRemote(prefix: String, filename: String): String = when {
            prefix.isEmpty() -> filename
            filename.isEmpty() -> prefix
            else -> prefix.trimEnd('/', '\\') + "/" + filename.trimStart('/', '\\')
        }

        fun sha1(file: File): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n >= 0) {
                    md.update(buf, 0, n)
                    n = input.read(buf)
                }
            }
            return md.digest()
        }

        fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0x0F])
            }
            return sb.toString()
        }

        fun unhex(s: String): ByteArray {
            val clean = s.trim()
            if (clean.length % 2 != 0) return ByteArray(0)
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return ByteArray(0)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}

/** JavaSteam `SteamCloud` handler path — the pre-seam code of [SteamCloudSaveManager], unchanged. */
class JavaSteamCloudBackend(private val sc: SteamCloud) : SteamCloudBackend {

    override val label: String get() = "javasteam"

    override fun listFiles(appId: Int): List<SteamCloudBackend.CloudFile> {
        val list: AppFileChangeList = sc.getAppFileListChange(appId).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        return list.files.map { f ->
            SteamCloudBackend.CloudFile(remotePathOf(f, list), f.shaFile, f.timestamp.time, f.rawFileSize.toLong())
        }
    }

    override fun downloadOne(appId: Int, remotePath: String, dest: File): Boolean {
        val info = sc.clientFileDownload(appId, remotePath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (info.urlHost.isEmpty()) {
            Log.w(TAG, "Empty CDN host for $remotePath")
            return false
        }
        val url = (if (info.useHttps) "https://" else "http://") + info.urlHost + info.urlPath
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        for (h in info.requestHeaders) conn.setRequestProperty(h.name, h.value)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code downloading $remotePath")
                return false
            }
            dest.parentFile?.mkdirs()
            // Steam serves the file zip-compressed when fileSize != rawFileSize (single entry).
            val compressed = info.fileSize != info.rawFileSize
            conn.inputStream.use { raw ->
                if (compressed) {
                    ZipInputStream(raw).use { zip ->
                        if (zip.nextEntry == null) {
                            Log.w(TAG, "Compressed cloud file $remotePath had no zip entry")
                            return false
                        }
                        dest.outputStream().use { out -> zip.copyTo(out) }
                    }
                } else {
                    dest.outputStream().use { out -> raw.copyTo(out) }
                }
            }
            try { dest.setLastModified(info.timestamp.time) } catch (_: Exception) {}
            return true
        } finally {
            conn.disconnect()
        }
    }

    override fun beginBatch(appId: Int, filesToUpload: List<String>): Long {
        // Signature (JavaSteam 1.8.0):
        //   beginAppUploadBatch(appId, machineName, filesToUpload, filesToDelete, clientId, appBuildId)
        // filesToDelete is ALWAYS empty; clientId/appBuildId are best-effort 0L (classic token logon
        // exposes no auth-session clientID, and we don't parse the installed build id).
        val batch = sc.beginAppUploadBatch(
            appId, SteamCloudBackend.machineName(), filesToUpload, emptyList(), 0L, 0L,
        ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        return batch.batchID
    }

    override fun uploadOne(appId: Int, file: File, cloudPath: String, batchId: Long): Boolean {
        val sha = SteamCloudBackend.sha1(file)
        val fileSize = file.length().toInt()
        val info = sc.beginFileUpload(
            appId = appId, fileSize = fileSize, rawFileSize = fileSize, fileSha = sha,
            timestamp = Date(file.lastModified()), filename = cloudPath, uploadBatchId = batchId,
        ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

        var ok = true
        RandomAccessFile(file, "r").use { raf ->
            for (block in info.blockRequests) {
                val len = block.blockLength
                val buf = ByteArray(len)
                raf.seek(block.blockOffset)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val url = (if (block.useHttps) "https://" else "http://") + block.urlHost + block.urlPath
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.doOutput = true
                conn.requestMethod = "PUT"
                for (h in block.requestHeaders) conn.setRequestProperty(h.name, h.value)
                try {
                    conn.setFixedLengthStreamingMode(read)
                    conn.outputStream.use { out -> out.write(buf, 0, read) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        Log.w(TAG, "HTTP $code uploading block of $cloudPath")
                        ok = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Block upload failed for $cloudPath: ${e.javaClass.simpleName}")
                    ok = false
                } finally {
                    conn.disconnect()
                }
            }
        }
        // Commit tells the CM whether the transfer for this file succeeded. This does not delete
        // anything; on failure the CM simply drops this file's pending upload.
        sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        return ok
    }

    override fun completeBatch(appId: Int, batchId: Long, allOk: Boolean) {
        sc.completeAppUploadBatch(appId, batchId, if (allOk) EResult.OK else EResult.Fail)
            .get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private fun remotePathOf(f: AppFileInfo, list: AppFileChangeList): String {
        val prefixes = list.pathPrefixes
        val idx = f.pathPrefixIndex
        val prefix = if (idx in prefixes.indices) prefixes[idx] else ""
        return SteamCloudBackend.joinRemote(prefix, f.filename)
    }

    private companion object {
        const val TAG = "BH_STEAM_CLOUD"
        const val FUTURE_TIMEOUT_SEC = 60L
        const val HTTP_TIMEOUT_MS = 60_000
    }
}

/**
 * Rust engine path: every primitive is a blocking `ccloud` call on [BlSteamSession] (bounded by the
 * native 30 s job timeout), the CDN GET/PUT legs run over HttpURLConnection like the JavaSteam path.
 * Every op writes a one-line summary into the engine log (never a path's content, never a token) so
 * the SteamLite bundle's CLOUD lines have something to read.
 */
class BlCloudBackend(private val s: BlSteamSession) : SteamCloudBackend {

    override val label: String get() = "rust"

    override fun listFiles(appId: Int): List<SteamCloudBackend.CloudFile> {
        val json = s.getCloudFileList(appId)
            ?: throw IllegalStateException("GetAppFileChangelist failed (engine not logged on / timeout)")
        val obj = JSONObject(json)
        val prefixes = obj.optJSONArray("pathPrefixes")
        val files = obj.optJSONArray("files")
        val out = ArrayList<SteamCloudBackend.CloudFile>(files?.length() ?: 0)
        if (files != null) for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val idx = f.optInt("pathPrefixIndex", -1)
            val prefix = if (prefixes != null && idx in 0 until prefixes.length()) prefixes.optString(idx, "") else ""
            val name = f.optString("fileName", "")
            if (name.isEmpty() && prefix.isEmpty()) continue
            out.add(SteamCloudBackend.CloudFile(
                SteamCloudBackend.joinRemote(prefix, name),
                SteamCloudBackend.unhex(f.optString("sha", "")),
                f.optLong("timestamp", 0L) * 1000L,
                f.optLong("size", 0L),
            ))
        }
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD", "changelist app=$appId files=${out.size}")
        return out
    }

    override fun downloadOne(appId: Int, remotePath: String, dest: File): Boolean {
        val dl = s.downloadCloudFileDetailed(appId, remotePath)
        if (dl == null) {
            Log.w(TAG, "engine cloud download failed for $remotePath")
            com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD", "download app=$appId FAILED (1 file)")
            return false
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { it.write(dl.bytes) }
        if (dl.timestampSec > 0L) try { dest.setLastModified(dl.timestampSec * 1000L) } catch (_: Exception) {}
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD", "download app=$appId ok (${dl.bytes.size} B)")
        return true
    }

    override fun beginBatch(appId: Int, filesToUpload: List<String>): Long {
        // filesToDelete is ALWAYS empty — the engine call accepts a list, this seam never passes one.
        val batch = s.beginCloudUploadBatch(appId, filesToUpload, emptyList(), 0L, SteamCloudBackend.machineName())
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD",
            "upload batch app=$appId files=${filesToUpload.size} → " + (if (batch == null) "REFUSED" else "batch ${batch.batchId}"))
        return batch?.batchId ?: 0L
    }

    override fun uploadOne(appId: Int, file: File, cloudPath: String, batchId: Long): Boolean {
        val bytes = file.readBytes()
        val shaHex = SteamCloudBackend.hex(SteamCloudBackend.sha1(file))
        val ok = s.uploadCloudFile(appId, cloudPath, bytes, shaHex, file.lastModified() / 1000L, batchId)
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD", "upload app=$appId ${bytes.size} B → " + (if (ok) "committed" else "FAILED"))
        return ok
    }

    override fun completeBatch(appId: Int, batchId: Long, allOk: Boolean) {
        // EResult.OK = 1, EResult.Fail = 2 — the same aggregate the JavaSteam path reports.
        val acked = s.completeCloudUploadBatch(appId, batchId, if (allOk) 1 else 2)
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD", "upload batch app=$appId complete allOk=$allOk acked=$acked")
    }

    override fun signalAppLaunchIntent(appId: Int): List<Int>? {
        val ops = try {
            s.signalAppLaunchIntent(appId, 0L, SteamCloudBackend.machineName(), false, OS_TYPE_WINDOWS)
        } catch (t: Throwable) { null }
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD",
            "app launch intent app=$appId → " + (ops?.let { if (it.isEmpty()) "clear" else "pending ops $it" } ?: "no reply"))
        return ops
    }

    override fun signalAppExitSyncDone(appId: Int, uploadsCompleted: Boolean, uploadsRequired: Boolean) {
        try { s.signalAppExitSyncDone(appId, 0L, uploadsCompleted, uploadsRequired) } catch (_: Throwable) {}
        com.winlator.star.store.blsteam.BlSteamEngineLog.log("CLOUD",
            "app exit sync done app=$appId uploadsCompleted=$uploadsCompleted uploadsRequired=$uploadsRequired")
    }

    private companion object {
        const val TAG = "BH_STEAM_CLOUD"
        /** EOSType Windows 10 — the OS the games run as inside the container (same as the presence report). */
        const val OS_TYPE_WINDOWS = 16
    }
}
