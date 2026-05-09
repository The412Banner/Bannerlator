package com.winlator.cmod.ui.screens.adrenodownload

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RemoteDriverRepository(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, "adreno_driver_dl").apply { if (!exists()) mkdirs() }
    }

    suspend fun fetchEntries(source: RemoteDriverSource): Result<List<RemoteDriverEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val body = httpGetText(source.jsonUrl)
                val arr = JSONArray(body)
                val out = ArrayList<RemoteDriverEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("remoteUrl", "")
                    if (!url.endsWith(".zip", ignoreCase = true)) continue
                    val verName = o.optString("verName", "").ifBlank {
                        url.substringAfterLast('/').removeSuffix(".zip")
                    }
                    out.add(
                        RemoteDriverEntry(
                            source = source.name,
                            displayName = verName,
                            downloadUrl = url,
                        )
                    )
                }
                Result.success(out)
            } catch (t: Throwable) {
                Log.w(TAG, "fetchEntries failed for ${source.name}", t)
                Result.failure(t)
            }
        }

    /** Downloads the .zip to internal cache. Returns the local file. progress(0..100). */
    suspend fun downloadEntry(
        entry: RemoteDriverEntry,
        progress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val safeName = entry.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zip"
            val outFile = File(cacheDir, "${System.currentTimeMillis()}_$safeName")
            val conn = (URL(entry.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "star-android")
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(RuntimeException("HTTP ${conn.responseCode}"))
                }
                val totalBytes = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            written += n
                            if (totalBytes > 0) {
                                val pct = ((written * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress(pct)
                                }
                            }
                        }
                    }
                }
                progress(100)
                Result.success(outFile)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "downloadEntry failed for ${entry.displayName}", t)
            Result.failure(t)
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "star-android")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "RemoteDriverRepo"
    }
}
