package com.winlator.star.store

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny blocking HTTP helper for the GOG / Epic storefront and profile fetches. Every call is
 * fail-soft (null on any non-2xx or exception) — the storefront degrades, it never crashes.
 *
 * Call from a worker thread / `Dispatchers.IO` only.
 */
internal object StoreNet {

    private const val TAG = "StoreNet"

    /** A desktop-browser UA: GOG's catalog and Epic's store GraphQL both answer this; Epic's
     *  edge refuses the bare Java UA. */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun get(
        url: String,
        bearer: String? = null,
        userAgent: String = BROWSER_UA,
        timeoutMs: Int = 20_000,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $code ${url.substringBefore('?')}")
                null
            } else readAll(conn)
        } catch (e: Exception) {
            Log.w(TAG, "GET failed ${url.substringBefore('?')}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun postJson(
        url: String,
        body: String,
        bearer: String? = null,
        userAgent: String = BROWSER_UA,
        timeoutMs: Int = 25_000,
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "POST $code ${url.substringBefore('?')}")
                null
            } else readAll(conn)
        } catch (e: Exception) {
            Log.w(TAG, "POST failed ${url.substringBefore('?')}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readAll(conn: HttpURLConnection): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { br ->
            val buf = CharArray(8192)
            var n: Int
            while (br.read(buf).also { n = it } > 0) sb.append(buf, 0, n)
        }
        return sb.toString()
    }
}
