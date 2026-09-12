package com.winlator.star.store

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Uploads an image to Steam's community image host (the real endpoint the desktop Steam Chat uses) and
 * returns the resulting `images.steamusercontent.com/ugc/…` URL, which the receive side renders inline
 * ([SteamFriendsScreen.imageUrlOrNull]). This is NOT the game-save cloud (ISteamRemoteStorage) — it is
 * `steamcommunity.com/chat/{begin,commit}fileupload` plus a raw PUT of the bytes to the UGC host.
 *
 * Ported from WinNative `wn-steam-client/rust/src/chat_image.rs` (GPL-3.0), which the app's Steam
 * client already derives from; the endpoints/protocol are factual API. The three web calls are:
 *   1. begin  — POST beginfileupload, hands back the UGC target + signing headers (hmac/timestamp).
 *   2. PUT    — the raw bytes to the returned UGC host/path with the returned request_headers.
 *   3. commit — POST commitfileupload, which finalises and returns the stored file sha.
 *
 * [upload] is a pure, blocking function (no session/handler state) — the caller supplies the WEB-audience
 * access token, the two SteamIDs and the bytes. It never throws: any failure logs and returns null. Call
 * it OFF the main thread (it performs blocking network I/O). Uses the system trust store (Android ships a
 * current CA set), so no CA bundle is needed.
 */
object SteamChatImageUploader {

    private const val TAG = "BH_STEAM_IMGUP"

    /**
     * Reason for the most recent [upload] failure (release APKs strip Log.*, so the on-screen bubble is
     * our only telemetry). Set at every failure return; cleared to null on success. Also lets the caller
     * detect an auth failure (contains "401"/"403") and retry with a fresh token.
     */
    @Volatile var lastError: String? = null
        private set

    private const val COMMUNITY = "https://steamcommunity.com"
    private const val UGC_URL_BASE = "https://images.steamusercontent.com/ugc"
    private const val USER_AGENT = "Mozilla/5.0"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Upload [bytes] to Steam's chat UGC and return the shareable image URL, or null on any failure.
     * Blocking — run on a worker thread. [webToken] must be a web-audience access token (see
     * [SteamFriendsStore] token minting), NOT the raw refresh token.
     */
    fun upload(
        webToken: String,
        selfId: Long,
        friendId: Long,
        bytes: ByteArray,
        fileName: String,
    ): String? {
        if (bytes.isEmpty()) {
            lastError = "empty image"
            Log.w(TAG, "upload: empty image")
            return null
        }
        if (webToken.isBlank() || selfId == 0L) {
            lastError = "not signed in"
            Log.w(TAG, "upload: missing token/selfId")
            return null
        }
        return try {
            val sessionId = randomSessionId()
            val cookie = "sessionid=$sessionId; steamLoginSecure=$selfId%7C%7C$webToken"
            val sha = sha1Hex(bytes)
            val (width, height) = imageDimensions(bytes)
            val contentType = contentType(bytes)
            val size = bytes.size.toString()
            val name = fileName.ifBlank { "image.png" }

            // ── Step 1: begin ────────────────────────────────────────────────────────
            val beginForm = FormBody.Builder()
                .add("sessionid", sessionId)
                .add("l", "english")
                .add("file_size", size)
                .add("file_name", name)
                .add("file_sha", sha)
                .add("file_image_width", width.toString())
                .add("file_image_height", height.toString())
                .add("file_type", contentType)
                .build()
            val beginReq = Request.Builder()
                .url("$COMMUNITY/chat/beginfileupload/?l=english")
                .header("Cookie", cookie)
                .header("Referer", "$COMMUNITY/chat/")
                .header("Origin", COMMUNITY)
                .header("User-Agent", USER_AGENT)
                .post(beginForm)
                .build()

            val (beginCode, beginBody) = client.newCall(beginReq).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
            if (beginCode != 200) {
                // Include a snippet of Steam's response body — it carries the actual reason for a 400.
                val why = beginBody.trim().replace('\n', ' ').take(300)
                lastError = "begin $beginCode" + if (why.isNotEmpty()) ": $why" else ""
                Log.w(TAG, "begin http $beginCode: $beginBody")
                return null
            }
            val beginJson = JSONObject(beginBody)
            val payload = beginJson.optJSONObject("result") ?: beginJson
            val ugcid = jsonStr(payload, "ugcid")
            val urlHost = jsonStr(payload, "url_host")
            val urlPath = jsonStr(payload, "url_path")
            val useHttps = payload.optBoolean("use_https", true)
            // hmac/timestamp are top-level in Steam's response; fall back to the result node just in case.
            val hmac = jsonStr(beginJson, "hmac").ifEmpty { jsonStr(payload, "hmac") }
            val timestamp = jsonStr(beginJson, "timestamp").ifEmpty { jsonStr(payload, "timestamp") }
            if (ugcid.isEmpty() || urlHost.isEmpty()) {
                lastError = "begin: no ugcid/host"
                Log.w(TAG, "begin missing ugcid/url_host")
                return null
            }

            // ── Step 2: PUT the raw bytes to the UGC host ────────────────────────────
            val scheme = if (useHttps) "https" else "http"
            val putUrl = "$scheme://$urlHost$urlPath"
            // Null body media type + an explicit Content-Type header mirrors the reference client; any
            // Content-Type Steam supplies in request_headers then overrides it (header() replaces).
            val putBuilder = Request.Builder()
                .url(putUrl)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", contentType)
                .put(bytes.toRequestBody())
            payload.optJSONArray("request_headers")?.let { headers ->
                for (i in 0 until headers.length()) {
                    val h = headers.optJSONObject(i) ?: continue
                    val hName = jsonStr(h, "name")
                    val hValue = jsonStr(h, "value")
                    if (hName.isBlank()) continue
                    // OkHttp derives Host from the URL and Content-Length from the body — never override.
                    if (hName.equals("host", true) || hName.equals("content-length", true)) continue
                    putBuilder.header(hName, hValue)
                }
            }
            val (putCode, putOk) = client.newCall(putBuilder.build()).execute().use { resp ->
                resp.code to resp.isSuccessful
            }
            if (!putOk) {
                lastError = "put $putCode"
                Log.w(TAG, "ugc put http $putCode")
                return null
            }

            // ── Step 3: commit ───────────────────────────────────────────────────────
            val commitForm = FormBody.Builder()
                .add("sessionid", sessionId)
                .add("l", "english")
                .add("file_name", name)
                .add("file_sha", sha)
                .add("file_size", size)
                .add("file_image_width", width.toString())
                .add("file_image_height", height.toString())
                .add("file_type", contentType)
                .add("success", "1")
                .add("ugcid", ugcid)
                .add("timestamp", timestamp)
                .add("hmac", hmac)
                .add("friend_steamid", friendId.toString())
                .add("spoiler", "0")
                .build()
            val commitReq = Request.Builder()
                .url("$COMMUNITY/chat/commitfileupload/")
                .header("Cookie", cookie)
                .header("Referer", "$COMMUNITY/chat/")
                .header("Origin", COMMUNITY)
                .header("User-Agent", USER_AGENT)
                .post(commitForm)
                .build()

            val (commitCode, commitBody) = client.newCall(commitReq).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
            if (commitCode != 200) {
                lastError = "commit $commitCode"
                Log.w(TAG, "commit http $commitCode")
                return null
            }
            val commitJson = JSONObject(commitBody)
            val details = commitJson.optJSONObject("result")?.optJSONObject("details")
            val fileSha = jsonStr(details, "file_sha")
            val shaUpper = if (fileSha.isEmpty()) sha.uppercase() else fileSha.uppercase()

            lastError = null
            "$UGC_URL_BASE/$ugcid/$shaUpper/"
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.w(TAG, "upload failed", t)
            null
        }
    }

    // ── Helpers (ported from chat_image.rs) ─────────────────────────────────────────

    /** Read a String/Number/Boolean field as text ("" when absent or JSON null). */
    private fun jsonStr(o: JSONObject?, key: String): String {
        if (o == null) return ""
        val v = o.opt(key) ?: return ""
        if (v === JSONObject.NULL) return ""
        return when (v) {
            is String -> v
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> v.toString()
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return toHex(digest)
    }

    private val secureRandom by lazy { SecureRandom() }

    /** 12 random bytes as lowercase hex — the CSRF `sessionid` the community cookie pairs with. */
    private fun randomSessionId(): String {
        val b = ByteArray(12)
        secureRandom.nextBytes(b)
        return toHex(b)
    }

    private fun toHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    /** MIME type from the magic bytes; defaults to image/png (Steam still accepts it). */
    private fun contentType(bytes: ByteArray): String {
        return when {
            bytes.size > 8 && startsWith(bytes, PNG_MAGIC) -> "image/png"
            bytes.size > 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 -> "image/jpeg"
            bytes.size > 6 && (startsWithAscii(bytes, "GIF89a") || startsWithAscii(bytes, "GIF87a")) -> "image/gif"
            bytes.size > 12 && startsWithAscii(bytes, "RIFF") && asciiAt(bytes, 8, "WEBP") -> "image/webp"
            else -> "image/png"
        }
    }

    /**
     * Best-effort (width, height) for PNG/GIF/JPEG with no image library. Returns (0, 0) for anything
     * unknown — Steam accepts a zero size. PNG: IHDR big-endian @16/20. GIF: little-endian @6/8.
     * JPEG: scan 0xFF frame markers (0xC0–0xCF except C4/C8/CC), height @+5, width @+7, big-endian.
     */
    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size > 24 && startsWith(bytes, PNG_MAGIC)) {
            val w = beU32(bytes, 16)
            val h = beU32(bytes, 20)
            return w to h
        }
        if (bytes.size > 10 && (startsWithAscii(bytes, "GIF89a") || startsWithAscii(bytes, "GIF87a"))) {
            val w = u8(bytes, 6) or (u8(bytes, 7) shl 8)
            val h = u8(bytes, 8) or (u8(bytes, 9) shl 8)
            return w to h
        }
        if (bytes.size > 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (u8(bytes, i) != 0xFF) {
                    i += 1
                    continue
                }
                val marker = u8(bytes, i + 1)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = (u8(bytes, i + 5) shl 8) or u8(bytes, i + 6)
                    val w = (u8(bytes, i + 7) shl 8) or u8(bytes, i + 8)
                    return w to h
                }
                val len = (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)
                if (len < 2) break
                i += 2 + len
            }
        }
        return 0 to 0
    }

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun u8(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF

    private fun beU32(bytes: ByteArray, i: Int): Int =
        (u8(bytes, i) shl 24) or (u8(bytes, i + 1) shl 16) or (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }

    private fun startsWithAscii(bytes: ByteArray, ascii: String): Boolean = asciiAt(bytes, 0, ascii)

    private fun asciiAt(bytes: ByteArray, offset: Int, ascii: String): Boolean {
        if (bytes.size < offset + ascii.length) return false
        for (i in ascii.indices) if (u8(bytes, offset + i) != ascii[i].code) return false
        return true
    }
}
