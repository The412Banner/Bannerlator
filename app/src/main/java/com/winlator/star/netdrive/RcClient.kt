package com.winlator.star.netdrive

// Ported from BFE (The412Banner/BFE, cloud/RcClient.kt), same author and licence (GPL-3.0).

import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** An error answered by rclone's remote-control server: [message] is rclone's own error text. */
class RcException(val path: String, val status: Int, message: String) : IOException(message)

/**
 * Minimal client for rclone's remote-control API (`rclone rcd`): every call is an HTTP POST of a JSON
 * object to `http://127.0.0.1:<port>/<path>` with basic auth, answered with a JSON object. Errors come
 * back as a non-2xx status with `{"error": "..."}`, surfaced as [RcException].
 *
 * Blocking and thread-safe (one connection per call); callers run it on Dispatchers.IO. Plain JVM
 * (HttpURLConnection + org.json) so it can be exercised against a real rcd outside Android.
 *
 * Long operations run as rclone jobs: [startJob] adds `_async` and returns the job id; progress is
 * read with [jobStatus] and [stats] (rclone groups an async job's transfer stats under "job/<id>")
 * and [stopJob] cancels it (the job then finishes with the error "context canceled").
 */
class RcClient(val port: Int, user: String, pass: String) {
    val baseUrl = "http://127.0.0.1:$port/"
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    data class JobStatus(val finished: Boolean, val success: Boolean, val error: String, val output: JSONObject?)

    fun call(path: String, params: JSONObject = JSONObject(), readTimeoutMs: Int = 120_000): JSONObject {
        val c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            connectTimeout = 5_000
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", auth)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = params.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            val json = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrNull()
            if (code !in 200..299) {
                val msg = json?.optString("error", "")?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw RcException(path, code, msg)
            }
            return json ?: throw RcException(path, code, "Unreadable reply from rclone")
        } finally {
            c.disconnect()
        }
    }

    /** Runs [path] as a background job and returns its id. */
    fun startJob(path: String, params: JSONObject): Long =
        call(path, JSONObject(params.toString()).put("_async", true)).getLong("jobid")

    fun jobStatus(id: Long): JobStatus {
        val o = call("job/status", JSONObject().put("jobid", id), readTimeoutMs = 30_000)
        return JobStatus(
            finished = o.optBoolean("finished", false),
            success = o.optBoolean("success", false),
            error = o.optString("error", ""),
            output = o.optJSONObject("output"),
        )
    }

    /** Asks a job to stop; harmless if it already finished. */
    fun stopJob(id: Long) {
        runCatching { call("job/stop", JSONObject().put("jobid", id), readTimeoutMs = 15_000) }
    }

    /** Transfer stats for one group (an async job's group is "job/<id>"). */
    fun stats(group: String): JSONObject = call("core/stats", JSONObject().put("group", group), readTimeoutMs = 15_000)

    /** Drops a finished group's stats so they don't pile up in the daemon. */
    fun deleteStats(group: String) {
        runCatching { call("core/stats-delete", JSONObject().put("group", group), readTimeoutMs = 15_000) }
    }

    /**
     * Streams one remote file (`rcd --rc-serve` serves `GET /[remote:]/path`), or just [range] of it.
     * Closing the stream closes the connection. Building block for reading a cloud file without staging it first.
     */
    fun openRead(fs: String, path: String, range: LongRange? = null): InputStream {
        val url = baseUrl + "[" + encode(fs) + "]/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 120_000
            setRequestProperty("Authorization", auth)
            // Only part of the file (a server that ignores it sends all of it; callers stop reading).
            range?.let { setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.let { runCatching { JSONObject(it).optString("error", "") }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            c.disconnect()
            throw RcException("serve", code, msg)
        }
        return object : FilterInputStream(c.inputStream) {
            override fun close() {
                try { super.close() } finally { c.disconnect() }
            }
        }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
