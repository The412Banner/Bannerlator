// JNI symbols depend on this package path and class name.
package com.winlator.star.store.blsteam

import java.util.concurrent.atomic.AtomicLong

// Production handle for the native Steam CM client and auth sessions.
class BlSteamSession : AutoCloseable {

    private val nativeHandle: AtomicLong

    init {
        BlSteamClient.ensureLoaded()
        val h = nativeCreate()
        require(h != 0L) { "blsteam: nativeCreate returned 0" }
        nativeHandle = AtomicLong(h)
    }

    fun setCaBundlePath(path: String) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSetCaBundlePath(h, path)
    }

    fun setStateObserver(observer: BlSteamStateObserver?) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSetStateObserver(h, observer)
    }

    // Toggle the post-logon library PICS crawl before [logonWithRefreshToken].
    fun setAutoPopulateLibrary(enabled: Boolean) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSetAutoPopulateLibrary(h, enabled)
    }

    /**
     * Whether the runtime announces the persona as Online right after logon. JavaSteam never
     * announces on its own (the app does it when friends/chat are enabled), so the engine
     * mirrors that: OFF leaves no social footprint until [setPersonaState] is called. Safe
     * before and after [connect]; takes effect at the next logon.
     */
    fun setAutoPersonaOnline(enabled: Boolean) {
        val h = nativeHandle.get(); if (h == 0L) return
        try { nativeSetAutoPersonaOnline(h, enabled) } catch (_: UnsatisfiedLinkError) {}
    }

    fun connect(url: String): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return nativeConnect(h, url)
    }

    fun disconnect() {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeDisconnect(h)
    }

    fun logOffAndDisconnect(flushMs: Int = 500) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeLogOffAndDisconnect(h, flushMs)
    }

    fun renewRefreshToken(
        currentToken: String,
        steamId64: Long,
        timeoutMs: Int = 15_000,
    ): String? {
        if (currentToken.isEmpty() || steamId64 == 0L) return null
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeRenewRefreshToken(h, currentToken, steamId64, timeoutMs)
    }

    fun startLoginWithCredentials(
        username: String,
        password: String,
        persistentSession: Boolean,
        authenticator: BlAuthenticator,
        callback: BlAuthCallback,
    ) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeStartLoginWithCredentials(h, username, password, persistentSession,
            authenticator, callback)
    }

    // [resultCallback] may get a remote-approval update before the final token-bearing success when Steam Guard approves the QR.
    fun startLoginWithQr(
        qrCallback: BlQrCallback,
        resultCallback: BlAuthCallback,
    ) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeStartLoginWithQr(h, qrCallback, resultCallback)
    }

    fun cancelLogin() {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeCancelLogin(h)
    }

    // Log on with refresh token; [accountName] preferred when Steam provides it but auth polling can omit it after device confirmation.
    fun logonWithRefreshToken(refreshToken: String, accountName: String, steamId: Long = 0L): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return nativeLogonWithRefreshToken(h, refreshToken, accountName, steamId)
    }

    // Pre-warm app and DLC PICS metadata before launching a game.
    fun prepareApp(appId: Int, dlcAppIds: IntArray, callback: BlPrepareAppCallback) {
        val h = nativeHandle.get()
        if (h == 0L) {
            callback.onPrepareResult(false, "session closed")
            return
        }
        nativePrepareApp(h, appId, dlcAppIds, callback)
    }

    // Start an async native depot download; [listener] runs on a worker thread.
    // [maxWorkers] sizes the network fetch pool, [decompressWorkers] the decrypt+decompress+write
    // pool (the tier's maxDownloads / maxDecompress) — see the engine's decoupled 2-stage pipeline.
    fun downloadApp(
        appId: Int,
        depotIds: IntArray,
        manifestIds: LongArray,
        branch: String,
        installDir: String,
        fresh: Boolean,
        caBundlePath: String,
        maxWorkers: Int,
        decompressWorkers: Int,
        listener: BlDownloadListener,
    ) {
        require(depotIds.size == manifestIds.size) {
            "blsteam: depotIds/manifestIds size mismatch"
        }
        val h = nativeHandle.get()
        if (h == 0L) {
            listener.onComplete(false, "session closed", 0L, 0, 0)
            return
        }
        nativeDownloadApp(h, appId, depotIds, manifestIds, branch, installDir,
                          fresh, caBundlePath, maxWorkers, decompressWorkers, listener)
    }

    // Abort the current depot download.
    fun cancelDownload() {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeCancelDownload(h)
    }

    /** Result of [checkAppBetaPassword]: the CM's EResult + branch name → hex AES-256 manifest key. */
    class BetaPasswordResult(val eresult: Int, val branchKeys: Map<String, String>)

    /**
     * Blocking `ClientCheckAppBetaPassword`: every password-protected branch of [appId] the access
     * code unlocks, with the key that decrypts its `encryptedmanifests/<branch>/gid`. Null when the
     * session is not logged on or Steam did not answer. The password is never logged.
     */
    fun checkAppBetaPassword(appId: Int, password: String): BetaPasswordResult? {
        if (password.isEmpty()) return null
        val h = nativeHandle.get(); if (h == 0L) return null
        val json = try { nativeCheckAppBetaPassword(h, appId, password) } catch (_: UnsatisfiedLinkError) { null }
            ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            val keys = LinkedHashMap<String, String>()
            obj.optJSONObject("betas")?.let { b -> for (k in b.keys()) keys[k] = b.optString(k, "") }
            BetaPasswordResult(obj.optInt("eresult", 0), keys)
        } catch (_: Exception) { null }
    }

    /** Declared totals of one depot manifest (metadata-only fetch — no chunk data, no depot key). */
    class ManifestSizes(val uncompressed: Long, val compressed: Long, val disk: Long, val files: Int)

    /** Blocking manifest-only size resolve for one depot; null on any failure. Off the main thread. */
    fun fetchManifestSizes(appId: Int, depotId: Int, manifestId: Long, branch: String, caBundlePath: String): ManifestSizes? {
        val h = nativeHandle.get(); if (h == 0L) return null
        val json = try { nativeFetchManifestSizes(h, appId, depotId, manifestId, branch, caBundlePath) }
                   catch (_: UnsatisfiedLinkError) { null } ?: return null
        return try {
            val o = org.json.JSONObject(json)
            ManifestSizes(o.optLong("uncompressed", 0L), o.optLong("compressed", 0L), o.optLong("disk", 0L), o.optInt("files", 0))
        } catch (_: Exception) { null }
    }

    /**
     * Region preference for depot downloads: the Steam cell id sent with the CDN pool request
     * (0 = Steam picks by the connection's location) and the datacenter code whose
     * `cache<N>-<dc>.steamcontent.com` hosts are moved to the front of the pool ("" = directory
     * order). Read at the start of each [downloadApp] / [downloadWorkshopItem].
     */
    fun setCdnPreference(cellId: Int, preferDc: String) {
        val h = nativeHandle.get(); if (h == 0L) return
        try { nativeSetCdnPreference(h, cellId, preferDc) } catch (_: UnsatisfiedLinkError) {}
    }

    fun startWineBridge(steam3Port: Int = 0, clientServicePort: Int = 0): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return nativeStartWineBridge(h, steam3Port, clientServicePort)
    }

    fun stopWineBridge() {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeStopWineBridge(h)
    }

    fun wineBridgeLastError(): String {
        val h = nativeHandle.get(); if (h == 0L) return ""
        return nativeWineBridgeLastError(h)
    }

    // Return a cached app ownership ticket, or null if not pre-warmed.
    fun getAppOwnershipTicket(appId: Int): ByteArray? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetAppOwnershipTicket(h, appId)
    }

    // Blocking encrypted-app-ticket request; returns null on failure.
    fun requestEncryptedAppTicket(appId: Int): ByteArray? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeRequestEncryptedAppTicket(h, appId)
    }

    // Blocking user-stats schema request; call off the main thread.
    fun getUserStatsSchema(appId: Int): ByteArray? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetUserStatsSchema(h, appId)
    }

    // Blocking full user-stats request for achievement write-back.
    fun getUserStatsFull(appId: Int): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetUserStatsFull(h, appId)
    }

    /**
     * Blocking `ClientGetUserStats` decoded for the achievement store: `{eresult, crcStats,
     * schema:{stats:{<id>:{type,bits:{<bit>:{name,display:{name,desc,hidden,icon,icon_gray}}}}},
     * stats:[{id,value}], achievementBlocks:[{achievementId,unlockTimes:[…]}]}`. Null when the
     * session is not logged on or Steam did not answer. Call off the main thread.
     */
    fun getUserStatsJson(appId: Int): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetUserStatsJson(h, appId) } catch (_: UnsatisfiedLinkError) { null }
    }

    /**
     * Blocking `ClientStoreUserStats2` awaiting the server's `ClientStoreUserStatsResponse`.
     * Returns the EResult (1 = OK); 0 when nothing came back within [timeoutMs].
     */
    fun storeUserStatsBlocking(
        appId: Int,
        steamId: Long,
        crcStats: Int,
        statIds: IntArray,
        statValues: IntArray,
        timeoutMs: Int = 20_000,
    ): Int {
        val h = nativeHandle.get(); if (h == 0L) return 0
        return try { nativeStoreUserStatsBlocking(h, appId, steamId, crcStats, statIds, statValues, timeoutMs) }
               catch (_: UnsatisfiedLinkError) { 0 }
    }

    // Blocking Steam Inventory item-definition archive fetch.
    fun getItemDefArchive(appId: Int, caBundlePath: String): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        val bytes = nativeGetItemDefArchive(h, appId, caBundlePath) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    // Blocking subscribed-Workshop-items fetch; returns "[]" when empty.
    fun getSubscribedWorkshopItems(appId: Int): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetSubscribedWorkshopItems(h, appId)
    }

    // Blocking Workshop item depot download; returns bytes written or -1.
    fun downloadWorkshopItem(
        appId: Int,
        manifestId: Long,
        installDir: String,
        caBundlePath: String,
        maxWorkers: Int = 8,
        decompressWorkers: Int = maxWorkers,
    ): Long {
        val h = nativeHandle.get(); if (h == 0L) return -1L
        return nativeDownloadWorkshopItem(h, appId, manifestId, installDir, caBundlePath, maxWorkers, decompressWorkers)
    }

    // Fire-and-forget stat or achievement write-back.
    fun storeUserStats(
        appId: Int,
        steamId: Long,
        crcStats: Int,
        statIds: IntArray,
        statValues: IntArray,
    ) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeStoreUserStats(h, appId, steamId, crcStats, statIds, statValues)
    }

    // Blocking Steam Cloud changelist fetch.
    fun getCloudFileList(appId: Int): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetCloudFileList(h, appId)
    }

    fun getCloudUserQuota(): LongArray? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetCloudUserQuota(h) } catch (_: UnsatisfiedLinkError) { null }
    }

    fun getCloudDownloadInfo(appId: Int, filename: String): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetCloudDownloadInfo(h, appId, filename)
    }

    /** One downloaded Steam Cloud file: its decoded bytes + the cloud-side timestamp (unix seconds). */
    class CloudDownload(val bytes: ByteArray, val timestampSec: Long)

    // Blocking Steam Cloud download: request `identity`, then auto-detect/decode the stored wrapper and validate against rawFileSize.
    fun downloadCloudFile(appId: Int, filename: String): ByteArray? =
        downloadCloudFileDetailed(appId, filename)?.bytes

    /** [downloadCloudFile] plus the file's cloud timestamp (so a caller can preserve the mtime). */
    fun downloadCloudFileDetailed(appId: Int, filename: String): CloudDownload? {
        val infoJson = getCloudDownloadInfo(appId, filename) ?: return null
        return try {
            val obj = org.json.JSONObject(infoJson)
            val host = obj.optString("urlHost")
            if (host.isEmpty()) return null
            val rawFileSize = obj.optInt("rawFileSize", 0)
            val encrypted = obj.optBoolean("encrypted", false)
            val timestampSec = obj.optLong("timestamp", 0L)
            // Force https on Android (cleartext is blocked by default; the CDN serves both).
            val url = java.net.URL("https://$host${obj.optString("urlPath")}")

            // Up to 3 attempts so a transient HTTP/network blip can't strand the save.
            var raw: ByteArray? = null
            var lastCode = -1
            for (attempt in 0 until 3) {
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    // Get the exact stored bytes (we decode below); stops the HTTP stack from transparently gunzipping.
                    setRequestProperty("Accept-Encoding", "identity")
                    obj.optJSONArray("headers")?.let { headers ->
                        for (i in 0 until headers.length()) {
                            val hd = headers.getJSONObject(i)
                            setRequestProperty(hd.optString("name"), hd.optString("value"))
                        }
                    }
                }
                try {
                    lastCode = conn.responseCode
                    if (lastCode == 200) {
                        raw = conn.inputStream.use { it.readBytes() }
                        break
                    }
                    android.util.Log.w("BlSteamSession", "cloud GET $filename → HTTP $lastCode (attempt ${attempt + 1})")
                } catch (e: Exception) {
                    android.util.Log.w("BlSteamSession", "cloud GET $filename failed (attempt ${attempt + 1})", e)
                } finally {
                    conn.disconnect()
                }
                if (attempt < 2) Thread.sleep(400L * (attempt + 1))
            }
            if (raw == null) {
                android.util.Log.w("BlSteamSession", "cloud file $filename: download failed (last HTTP $lastCode)")
                return null
            }

            val body = decodeCloudPayload(raw, rawFileSize)
            if (body == null || body.size != rawFileSize) {
                android.util.Log.w(
                    "BlSteamSession",
                    "cloud file $filename: could not decode to expected size " +
                        "(got ${body?.size ?: -1}, expected $rawFileSize, encrypted=$encrypted) — rejecting",
                )
                return null
            }
            CloudDownload(body, timestampSec)
        } catch (e: Exception) {
            android.util.Log.w("BlSteamSession", "cloud file download failed: $filename", e)
            null
        }
    }

    /** Decode a Steam Cloud payload, trying raw/PKZip/gzip/zlib/raw-DEFLATE and accepting the first match for [rawFileSize]. */
    private fun decodeCloudPayload(raw: ByteArray, rawFileSize: Int): ByteArray? {
        if (raw.isEmpty()) return if (rawFileSize == 0) raw else null
        // Already the raw file (uncompressed, or the HTTP stack decoded transport compression).
        if (raw.size == rawFileSize) return raw

        // PKZip single-entry wrapper ("PK").
        if (raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()) {
            runCatching {
                java.util.zip.ZipInputStream(raw.inputStream()).use { zin ->
                    if (zin.nextEntry == null) null else zin.readBytes()
                }
            }.getOrNull()?.let { if (it.size == rawFileSize) return it }
        }
        // gzip (0x1f 0x8b).
        if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            runCatching {
                java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            }.getOrNull()?.let { if (it.size == rawFileSize) return it }
        }
        // zlib-wrapped DEFLATE (RFC 1950) then raw DEFLATE (RFC 1951).
        for (nowrap in booleanArrayOf(false, true)) {
            runCatching { inflateBytes(raw, nowrap, rawFileSize) }
                .getOrNull()
                ?.let { if (it.size == rawFileSize) return it }
        }
        return null
    }

    private fun inflateBytes(data: ByteArray, nowrap: Boolean, sizeHint: Int): ByteArray {
        val inflater = java.util.zip.Inflater(nowrap)
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(if (sizeHint > 0) sizeHint else data.size * 2)
        val buf = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary() || inflater.needsInput()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    // Result of [beginCloudUploadBatch].
    data class CloudUploadBatch(val batchId: Long, val appChangeNumber: Long)

    // Blocking Steam Cloud upload-batch opener.
    fun beginCloudUploadBatch(
        appId: Int,
        fileNames: List<String>,
        filesToDelete: List<String>,
        clientId: Long,
        machineName: String = "",
    ): CloudUploadBatch? {
        val h = nativeHandle.get(); if (h == 0L) return null
        val json = nativeCloudBeginUploadBatch(
            h, appId, fileNames.joinToString("\n"), filesToDelete.joinToString("\n"), clientId, machineName,
        ) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            val batchId = obj.optLong("batchId", 0L)
            if (batchId == 0L) null
            else CloudUploadBatch(batchId, obj.optLong("appChangeNumber", 0L))
        } catch (e: Exception) {
            null
        }
    }

    // Blocking file upload within an open Steam Cloud batch.
    fun uploadCloudFile(
        appId: Int,
        filename: String,
        fileBytes: ByteArray,
        fileShaHex: String,
        timestamp: Long,
        batchId: Long,
    ): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        // Cloud uploads are not compressed.
        val beginJson = nativeCloudBeginFileUpload(
            h, appId, filename, fileBytes.size, fileBytes.size, fileShaHex, timestamp, batchId,
        ) ?: return false
        try {
            val blocks0 = org.json.JSONObject(beginJson).optJSONArray("blocks")
            if (blocks0 == null || blocks0.length() == 0) {
                android.util.Log.i(
                    "BlSteamSession",
                    "cloud upload short-circuit: blocks=0 for $filename — file already in cloud, treating as success"
                )
                nativeCloudCommitFileUpload(h, true, appId, fileShaHex, filename)
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("BlSteamSession", "uploadCloudFile early-parse failed: $filename", e)
        }
        var allOk = true
        try {
            val blocks = org.json.JSONObject(beginJson).optJSONArray("blocks")
            if (blocks != null) {
                for (i in 0 until blocks.length()) {
                    val blk = blocks.getJSONObject(i)
                    val host = blk.optString("urlHost")
                    val off = blk.optLong("blockOffset", 0L).toInt()
                    val len = blk.optInt("blockLength", 0)
                    if (host.isEmpty() || off < 0 || len < 0 || off.toLong() + len > fileBytes.size) {
                        allOk = false
                        continue
                    }
                    val url = java.net.URL("https://$host${blk.optString("urlPath")}")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "PUT"
                        doOutput = true
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setFixedLengthStreamingMode(len)
                        blk.optJSONArray("headers")?.let { hs ->
                            for (k in 0 until hs.length()) {
                                val hd = hs.getJSONObject(k)
                                setRequestProperty(hd.optString("name"), hd.optString("value"))
                            }
                        }
                        setRequestProperty("User-Agent", "Valve/Steam HTTP Client 1.0")
                    }
                    try {
                        conn.outputStream.use { it.write(fileBytes, off, len) }
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            allOk = false
                            android.util.Log.w("BlSteamSession", "cloud PUT block $i → HTTP $code")
                        }
                    } catch (e: Exception) {
                        allOk = false
                        android.util.Log.w("BlSteamSession", "cloud PUT block $i failed", e)
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BlSteamSession", "uploadCloudFile parse failed: $filename", e)
            allOk = false
        }
        return nativeCloudCommitFileUpload(h, allOk, appId, fileShaHex, filename)
    }

    // Blocking Steam Cloud upload-batch completion.
    fun completeCloudUploadBatch(appId: Int, batchId: Long, batchEresult: Int): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return nativeCloudCompleteUploadBatch(h, appId, batchId, batchEresult)
    }

    // Blocking PICS change poll since [sinceChangeNumber].
    fun getPicsChangesSince(sinceChangeNumber: Long): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetPicsChangesSince(h, sinceChangeNumber)
    }

    // Blocking PICS app-info fetch; [accessToken] is 0 for public appinfo.
    fun getPicsAppInfo(appId: Int, accessToken: Long = 0L): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetPicsAppInfo(h, appId, accessToken)
    }

    // Blocking PICS access-token request.
    fun getPicsAccessTokens(appIds: List<Int>, packageIds: List<Int>): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetPicsAccessTokens(
            h, appIds.joinToString("\n"), packageIds.joinToString("\n"),
        )
    }

    // Blocking batch PICS app product-info fetch.
    fun getPicsAppProductInfo(appIds: List<Int>, tokens: List<Long>): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetPicsAppProductInfo(
            h, appIds.joinToString("\n"), tokens.joinToString("\n"),
        )
    }

    // Blocking batch PICS package product-info fetch.
    fun getPicsPackageInfo(packageIds: List<Int>, tokens: List<Long>): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetPicsPackageInfo(
            h, packageIds.joinToString("\n"), tokens.joinToString("\n"),
        )
    }

    // Fire-and-forget running-games presence report.
    fun notifyGamesPlayed(gamesJson: String, clientOsType: Int) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeNotifyGamesPlayed(h, gamesJson, clientOsType)
    }

    /**
     * Blocking `Player.SetRichPresence` for [appId] (an empty map clears it). Call off the main
     * thread. True when Steam acknowledged the call.
     */
    fun setRichPresence(appId: Int, kv: Map<String, String>): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        val obj = org.json.JSONObject()
        for ((k, v) in kv) if (k.isNotEmpty()) obj.put(k, v)
        return try { nativeSetRichPresence(h, appId, obj.toString()) } catch (_: UnsatisfiedLinkError) { false }
    }

    // Fire-and-forget request to release another active playing session.
    fun kickPlayingSession(onlyStopGame: Boolean = false) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeKickPlayingSession(h, onlyStopGame)
    }

    // Cached playing-blocked state for this account.
    fun isPlayingBlocked(): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return nativeIsPlayingBlocked(h)
    }

    // Mark playing blocked before waiting for a post-kick server push.
    fun markPlayingBlocked() {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeMarkPlayingBlocked(h)
    }

    // Fire-and-forget Steam persona state update.
    fun setPersonaState(personaState: Int) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSetPersonaState(h, personaState)
    }

    fun setPersonaName(name: String, personaState: Int = 1) {
        if (name.isEmpty()) return
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSetPersonaName(h, name, personaState)
    }

    fun requestUserPersona() {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeRequestUserPersona(h)
    }

    fun requestFriendPersonas(
        steamIds: LongArray,
        personaStateRequested: Int = 1,
    ) {
        val h = nativeHandle.get(); if (h == 0L) return
        if (steamIds.isEmpty()) return
        try { nativeRequestFriendPersonas(h, steamIds, personaStateRequested) }
        catch (_: UnsatisfiedLinkError) {}
    }

    fun getSelfPersona(): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetSelfPersona(h)
    }

    // Blocking Steam Family group lookup.
    fun getFamilyGroup(familyGroupId: Long): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetFamilyGroup(h, familyGroupId)
    }

    // Cached license list JSON; empty until the post-logon push arrives.
    fun getLicenseList(): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetLicenseList(h)
    }

    fun getFriendsList(): LongArray {
        val h = nativeHandle.get(); if (h == 0L) return LongArray(0)
        return try { nativeGetFriendsList(h) } catch (_: UnsatisfiedLinkError) { LongArray(0) }
    }

    fun getFriendPersonas(): String {
        val h = nativeHandle.get(); if (h == 0L) return "[]"
        return try { nativeGetFriendPersonas(h) ?: "[]" }
               catch (_: UnsatisfiedLinkError) { "[]" }
    }

    /** Every known relationship as JSON `[{"sid","rel"}]` (EFriendRelationship codes). Cached, no network. */
    fun getFriendRelationships(): String {
        val h = nativeHandle.get(); if (h == 0L) return "[]"
        return try { nativeGetFriendRelationships(h) ?: "[]" }
               catch (_: UnsatisfiedLinkError) { "[]" }
    }

    // Blocking: "typing…" notification to a friend.
    fun sendFriendTyping(steamId: Long): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return try { nativeSendFriendTyping(h, steamId) } catch (_: UnsatisfiedLinkError) { false }
    }

    /** Fire-and-forget friend request: by SteamID64 ([steamId] != 0) or by account name / e-mail. */
    fun addFriend(steamId: Long, accountName: String = ""): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return try { nativeAddFriend(h, steamId, accountName) } catch (_: UnsatisfiedLinkError) { false }
    }

    /** Fire-and-forget remove friend / decline incoming invite / cancel outgoing invite. */
    fun removeFriend(steamId: Long): Boolean {
        val h = nativeHandle.get(); if (h == 0L) return false
        return try { nativeRemoveFriend(h, steamId) } catch (_: UnsatisfiedLinkError) { false }
    }

    // Blocking friend profile info (real name / location / summary / account age); JSON or null.
    fun getFriendProfileInfo(steamId: Long): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetFriendProfileInfo(h, steamId) } catch (_: UnsatisfiedLinkError) { null }
    }

    // Blocking quick-invite token mint (UserAccount.CreateFriendInviteToken); the raw token or null.
    fun createFriendInviteToken(): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeCreateFriendInviteToken(h) } catch (_: UnsatisfiedLinkError) { null }
    }

    // Blocking web-audience access token for the community steamLoginSecure cookie; null on failure.
    fun generateWebAccessToken(refreshToken: String, steamId: Long): String? {
        if (refreshToken.isEmpty()) return null
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGenerateWebAccessToken(h, refreshToken, steamId) } catch (_: UnsatisfiedLinkError) { null }
    }

    // Blocking: send a 1-to-1 friend message; returns response JSON or null.
    fun sendFriendMessage(steamId: Long, message: String): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeSendFriendMessage(h, steamId, message) }
               catch (_: UnsatisfiedLinkError) { null }
    }

    // Blocking: recent message history for a conversation; JSON array.
    fun getRecentMessages(steamId: Long, count: Int = 50): String {
        val h = nativeHandle.get(); if (h == 0L) return "[]"
        return try { nativeGetRecentMessages(h, steamId, count) ?: "[]" }
               catch (_: UnsatisfiedLinkError) { "[]" }
    }

    // Drains queued incoming-message notifications; JSON array.
    fun drainFriendMessages(): String {
        val h = nativeHandle.get(); if (h == 0L) return "[]"
        return try { nativeDrainFriendMessages(h) ?: "[]" }
               catch (_: UnsatisfiedLinkError) { "[]" }
    }

    // Blocking: upload an image to Steam chat UGC and send it to a friend; returns the URL or null.
    fun sendChatImage(steamId: Long, refreshToken: String, bytes: ByteArray, fileName: String): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeSendChatImage(h, steamId, refreshToken, bytes, fileName) }
               catch (_: UnsatisfiedLinkError) { null }
    }

    fun getOwnedGames(steamId: Long): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return nativeGetOwnedGames(h, steamId)
    }

    /**
     * Ask Steam to grant free licenses for [appIds] (a BATCH — the wire message takes a list).
     *
     * BLOCKING. Returns null ONLY when there is no live session; every other outcome — including a
     * refusal — comes back as the JSON documented on [com.winlator.star.store.SteamFreeLicense],
     * which owns the decode and the user-facing mapping.
     *
     * The native side also waits (bounded) for the CM to push the updated license list and reports
     * that as `libraryUpdated`. That is a PACKAGE-level fact: turning the granted packages into
     * downloadable apps is a PICS crawl driven from Kotlin, so a caller must still kick a library
     * sync on success.
     */
    fun requestFreeLicense(appIds: IntArray): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        if (appIds.isEmpty()) return null
        return try { nativeRequestFreeLicense(h, appIds) } catch (_: UnsatisfiedLinkError) { null }
    }

    // ── Player profile (level / showcased badge / equipped items / recently played) ────────────
    // Every one of these is BLOCKING and returns null when there is no logged-on session OR the
    // request never landed. They are also newer than the rest of this file, so each is wrapped in
    // the same UnsatisfiedLinkError guard the other late additions use — an app built against a
    // .so that predates them must degrade, not die.

    /**
     * Steam levels for [steamIds] as `[{"steamId","accountId","level"}]`.
     *
     * NOTE: ids the CM declines are **omitted from the array** — a missing id means "unknown",
     * NOT level 0. Callers must key off presence, not off a defaulted zero.
     */
    fun getSteamLevels(steamIds: LongArray): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        if (steamIds.isEmpty()) return "[]"
        return try { nativeGetSteamLevels(h, steamIds) } catch (_: UnsatisfiedLinkError) { null }
    }

    /**
     * Equipped profile decoration for [steamId], keyed `avatarFrame` / `profileBackground` /
     * `miniProfileBackground` / `animatedAvatar` / `profileModifier` / `steamDeckKeyboardSkin`;
     * each slot is null or an item object. Public data — available even for a limited profile.
     * [language] null = "english".
     */
    fun getEquippedProfileItems(steamId: Long, language: String? = null): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetEquippedProfileItems(h, steamId, language) } catch (_: UnsatisfiedLinkError) { null }
    }

    /** The single showcased ("favorite") badge for [steamId], or null when none is set. */
    fun getFavoriteBadge(steamId: Long): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetFavoriteBadge(h, steamId) } catch (_: UnsatisfiedLinkError) { null }
    }

    /**
     * Recently-played games for [steamId], same element shape as [getOwnedGames].
     * [maxCount] <= 0 means unlimited. Requires the account's *game details* to be public.
     */
    fun getRecentlyPlayedGames(steamId: Long, maxCount: Int = 0): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetRecentlyPlayedGames(h, steamId, maxCount) } catch (_: UnsatisfiedLinkError) { null }
    }

    /**
     * One-call profile read for [steamId] (self or a friend) — see [BlPlayerProfile] for the
     * decoded shape and its privacy semantics. [maxRecent] <= 0 means unlimited.
     */
    fun getPlayerProfile(steamId: Long, language: String? = null, maxRecent: Int = 0): String? {
        val h = nativeHandle.get(); if (h == 0L) return null
        return try { nativeGetPlayerProfile(h, steamId, language, maxRecent) } catch (_: UnsatisfiedLinkError) { null }
    }

    // Blocking launch-intent signal; empty pending-op list means clear to launch.
    fun signalAppLaunchIntent(
        appId: Int,
        clientId: Long,
        machineName: String,
        ignorePending: Boolean,
        osType: Int,
    ): List<Int>? {
        val h = nativeHandle.get(); if (h == 0L) return null
        val json = nativeSignalAppLaunchIntent(h, appId, clientId, machineName, ignorePending, osType)
            ?: return null
        return try {
            val arr = org.json.JSONObject(json).optJSONArray("pendingOps")
            (0 until (arr?.length() ?: 0)).map { arr!!.getInt(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun signalAppExitSyncDone(
        appId: Int,
        clientId: Long,
        uploadsCompleted: Boolean,
        uploadsRequired: Boolean,
    ) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSignalAppExitSyncDone(h, appId, clientId, uploadsCompleted, uploadsRequired)
    }

    // Fresh JSON snapshot of the native library store.
    fun getLibrarySnapshotJson(): String {
        val h = nativeHandle.get(); if (h == 0L) return "{}"
        return nativeGetLibrarySnapshot(h)
    }

    // Install or clear the native library-store observer.
    fun setLibraryObserver(observer: BlLibraryObserver?) {
        val h = nativeHandle.get(); if (h == 0L) return
        nativeSetLibraryObserver(h, observer)
    }

    fun state(): Int {
        val h = nativeHandle.get(); if (h == 0L) return 0
        return nativeState(h)
    }

    fun steamId(): Long {
        val h = nativeHandle.get(); if (h == 0L) return 0L
        return nativeSteamId(h)
    }

    // Steam Family group id from logon, or 0.
    fun familyGroupId(): Long {
        val h = nativeHandle.get(); if (h == 0L) return 0L
        return nativeFamilyGroupId(h)
    }

    override fun close() {
        val h = nativeHandle.getAndSet(0L)
        if (h != 0L) nativeDestroy(h)
    }

    @Suppress("ProtectedInFinal", "unused")
    protected fun finalize() { close() }

    companion object {
        // Blocking Steam CM WSS resolver; returns empty string on failure.
        fun pickCmUrl(caBundlePath: String): String {
            BlSteamClient.ensureLoaded()
            return nativePickCmUrl(caBundlePath)
        }

        @JvmStatic private external fun nativePickCmUrl(caBundlePath: String): String

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSetCaBundlePath(handle: Long, path: String)
        @JvmStatic private external fun nativeSetStateObserver(handle: Long, observer: BlSteamStateObserver?)
        @JvmStatic private external fun nativeSetAutoPopulateLibrary(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetAutoPersonaOnline(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeConnect(handle: Long, url: String): Boolean
        @JvmStatic private external fun nativeDisconnect(handle: Long)
        @JvmStatic private external fun nativeLogOffAndDisconnect(handle: Long, flushMs: Int)
        @JvmStatic private external fun nativeRenewRefreshToken(
            handle: Long,
            currentToken: String,
            steamId64: Long,
            timeoutMs: Int,
        ): String?
        @JvmStatic private external fun nativeStartLoginWithCredentials(
            handle: Long,
            username: String,
            password: String,
            persistentSession: Boolean,
            authenticator: BlAuthenticator,
            callback: BlAuthCallback,
        )
        @JvmStatic private external fun nativeStartLoginWithQr(
            handle: Long,
            qrCallback: BlQrCallback,
            resultCallback: BlAuthCallback,
        )
        @JvmStatic private external fun nativeCancelLogin(handle: Long)
        @JvmStatic private external fun nativeLogonWithRefreshToken(
            handle: Long,
            refreshToken: String,
            accountName: String,
            steamId: Long,
        ): Boolean
        @JvmStatic private external fun nativePrepareApp(
            handle: Long,
            appId: Int,
            dlcAppIds: IntArray,
            callback: BlPrepareAppCallback,
        )
        @JvmStatic private external fun nativeDownloadApp(
            handle: Long,
            appId: Int,
            depotIds: IntArray,
            manifestIds: LongArray,
            branch: String,
            installDir: String,
            fresh: Boolean,
            caBundlePath: String,
            maxWorkers: Int,
            decompressWorkers: Int,
            listener: BlDownloadListener,
        )
        @JvmStatic private external fun nativeCancelDownload(handle: Long)
        @JvmStatic private external fun nativeSetCdnPreference(handle: Long, cellId: Int, preferDc: String)
        @JvmStatic private external fun nativeCheckAppBetaPassword(handle: Long, appId: Int, password: String): String?
        @JvmStatic private external fun nativeFetchManifestSizes(
            handle: Long, appId: Int, depotId: Int, manifestId: Long, branch: String, caBundlePath: String): String?
        @JvmStatic private external fun nativeStartWineBridge(
            handle: Long, steam3Port: Int, clientServicePort: Int): Boolean
        @JvmStatic private external fun nativeStopWineBridge(handle: Long)
        @JvmStatic private external fun nativeWineBridgeLastError(handle: Long): String
        @JvmStatic private external fun nativeGetAppOwnershipTicket(handle: Long, appId: Int): ByteArray?
        @JvmStatic private external fun nativeRequestEncryptedAppTicket(handle: Long, appId: Int): ByteArray?
        @JvmStatic private external fun nativeGetUserStatsSchema(handle: Long, appId: Int): ByteArray?
        @JvmStatic private external fun nativeGetUserStatsFull(handle: Long, appId: Int): String?
        @JvmStatic private external fun nativeGetUserStatsJson(handle: Long, appId: Int): String?
        @JvmStatic private external fun nativeStoreUserStatsBlocking(
            handle: Long, appId: Int, steamId: Long, crcStats: Int,
            statIds: IntArray, statValues: IntArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeGetItemDefArchive(
            handle: Long,
            appId: Int,
            caBundlePath: String,
        ): ByteArray?
        @JvmStatic private external fun nativeGetSubscribedWorkshopItems(
            handle: Long,
            appId: Int,
        ): String?
        @JvmStatic private external fun nativeDownloadWorkshopItem(
            handle: Long,
            appId: Int,
            manifestId: Long,
            installDir: String,
            caBundlePath: String,
            maxWorkers: Int,
            decompressWorkers: Int,
        ): Long
        @JvmStatic private external fun nativeStoreUserStats(
            handle: Long, appId: Int, steamId: Long, crcStats: Int,
            statIds: IntArray, statValues: IntArray)
        @JvmStatic private external fun nativeGetCloudFileList(handle: Long, appId: Int): String?
        @JvmStatic private external fun nativeGetCloudUserQuota(handle: Long): LongArray?
        @JvmStatic private external fun nativeGetCloudDownloadInfo(handle: Long, appId: Int, filename: String): String?
        @JvmStatic private external fun nativeCloudBeginUploadBatch(handle: Long, appId: Int, files: String, filesToDelete: String, clientId: Long, machineName: String): String?
        @JvmStatic private external fun nativeCloudBeginFileUpload(handle: Long, appId: Int, filename: String, fileSize: Int, rawFileSize: Int, shaHex: String, timestamp: Long, batchId: Long): String?
        @JvmStatic private external fun nativeCloudCommitFileUpload(handle: Long, transferSucceeded: Boolean, appId: Int, shaHex: String, filename: String): Boolean
        @JvmStatic private external fun nativeCloudCompleteUploadBatch(handle: Long, appId: Int, batchId: Long, batchEresult: Int): Boolean
        @JvmStatic private external fun nativeGetPicsChangesSince(handle: Long, sinceChangeNumber: Long): String?
        @JvmStatic private external fun nativeGetPicsAppInfo(handle: Long, appId: Int, accessToken: Long): String?
        @JvmStatic private external fun nativeGetPicsAccessTokens(handle: Long, appIds: String, packageIds: String): String?
        @JvmStatic private external fun nativeGetPicsAppProductInfo(handle: Long, appIds: String, tokens: String): String?
        @JvmStatic private external fun nativeGetPicsPackageInfo(handle: Long, packageIds: String, tokens: String): String?
        @JvmStatic private external fun nativeNotifyGamesPlayed(handle: Long, gamesJson: String, clientOsType: Int)
        @JvmStatic private external fun nativeKickPlayingSession(handle: Long, onlyStopGame: Boolean)
        @JvmStatic private external fun nativeSetRichPresence(handle: Long, appId: Int, kvJson: String): Boolean
        @JvmStatic private external fun nativeIsPlayingBlocked(handle: Long): Boolean
        @JvmStatic private external fun nativeMarkPlayingBlocked(handle: Long)
        @JvmStatic private external fun nativeSetPersonaState(handle: Long, personaState: Int)
        @JvmStatic private external fun nativeSetPersonaName(handle: Long, name: String, personaState: Int)
        @JvmStatic private external fun nativeRequestUserPersona(handle: Long)
        @JvmStatic private external fun nativeRequestFriendPersonas(
            handle: Long, steamIds: LongArray, flags: Int)
        @JvmStatic private external fun nativeGetSelfPersona(handle: Long): String?
        @JvmStatic private external fun nativeGetFamilyGroup(
            handle: Long, familyGroupId: Long): String?
        @JvmStatic private external fun nativeGetLicenseList(handle: Long): String?
        @JvmStatic private external fun nativeGetFriendsList(handle: Long): LongArray
        @JvmStatic private external fun nativeGetFriendPersonas(handle: Long): String?
        @JvmStatic private external fun nativeGetFriendRelationships(handle: Long): String?
        @JvmStatic private external fun nativeSendFriendTyping(handle: Long, steamId: Long): Boolean
        @JvmStatic private external fun nativeAddFriend(handle: Long, steamId: Long, accountName: String): Boolean
        @JvmStatic private external fun nativeRemoveFriend(handle: Long, steamId: Long): Boolean
        @JvmStatic private external fun nativeGetFriendProfileInfo(handle: Long, steamId: Long): String?
        @JvmStatic private external fun nativeCreateFriendInviteToken(handle: Long): String?
        @JvmStatic private external fun nativeGenerateWebAccessToken(handle: Long, refreshToken: String, steamId: Long): String?
        @JvmStatic private external fun nativeSendFriendMessage(handle: Long, steamId: Long, message: String): String?
        @JvmStatic private external fun nativeGetRecentMessages(handle: Long, steamId: Long, count: Int): String?
        @JvmStatic private external fun nativeDrainFriendMessages(handle: Long): String?
        @JvmStatic private external fun nativeSendChatImage(handle: Long, steamId: Long, refreshToken: String, image: ByteArray, fileName: String): String?
        @JvmStatic private external fun nativeGetOwnedGames(
            handle: Long, steamId: Long): String?
        @JvmStatic private external fun nativeRequestFreeLicense(handle: Long, appIds: IntArray): String?
        @JvmStatic private external fun nativeGetSteamLevels(handle: Long, steamIds: LongArray): String?
        @JvmStatic private external fun nativeGetEquippedProfileItems(
            handle: Long, steamId: Long, language: String?): String?
        @JvmStatic private external fun nativeGetFavoriteBadge(handle: Long, steamId: Long): String?
        @JvmStatic private external fun nativeGetRecentlyPlayedGames(
            handle: Long, steamId: Long, maxCount: Int): String?
        @JvmStatic private external fun nativeGetPlayerProfile(
            handle: Long, steamId: Long, language: String?, maxRecent: Int): String?
        @JvmStatic private external fun nativeSignalAppLaunchIntent(handle: Long, appId: Int, clientId: Long, machineName: String, ignorePending: Boolean, osType: Int): String?
        @JvmStatic private external fun nativeSignalAppExitSyncDone(handle: Long, appId: Int, clientId: Long, uploadsCompleted: Boolean, uploadsRequired: Boolean)
        @JvmStatic private external fun nativeGetLibrarySnapshot(handle: Long): String
        @JvmStatic private external fun nativeSetLibraryObserver(
            handle: Long,
            observer: BlLibraryObserver?,
        )
        @JvmStatic private external fun nativeState(handle: Long): Int
        @JvmStatic private external fun nativeSteamId(handle: Long): Long
        @JvmStatic private external fun nativeFamilyGroupId(handle: Long): Long
    }
}
