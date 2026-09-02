package com.winlator.star.store.blsteam

import android.content.Context
import android.util.Log
import com.winlator.star.store.SteamLogRedactor
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Rust engine's own diagnostic record (Phase 3b-4 of docs/STEAM_RUST_ENGINE_PLAN.md): one
 * bounded in-memory ring + an append-only `steam_engine.txt` next to `steam_debug.txt` /
 * `steam_session.txt` in the app's external files dir. The SteamLite log collector reads it to
 * derive the AUTH / SESSION / CLOUD / ACHIEVEMENTS diagnostics when the engine drives the session,
 * and includes it as a raw section of `steamlite.txt`.
 *
 * Every line is passed through [SteamLogRedactor.redact] before it is kept or written, and callers
 * only ever hand this class summaries (EResults, counts, app ids, states) — never a token, an
 * account name or a chat body. Lines are tagged with an area so the collector can bucket them:
 * `AUTH`, `SESSION`, `CLOUD`, `ACHV`, `DL`, `SOCIAL`, `LIB`.
 *
 * Thread-safe; file I/O failures are swallowed (a log must never break the feature it describes).
 */
object BlSteamEngineLog {

    private const val TAG = "BL_STEAM_ENGINE"
    const val FILE_NAME = "steam_engine.txt"
    private const val MAX_LINES = 800
    private const val MAX_FILE_BYTES = 512L * 1024

    private val ring = ArrayDeque<String>()
    @Volatile private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Bind the file location once (idempotent). Without it the ring still works. */
    @JvmStatic
    fun init(ctx: Context) {
        if (file != null) return
        try {
            val dir = ctx.applicationContext.getExternalFilesDir(null) ?: return
            file = File(dir, FILE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "init failed: ${t.message}")
        }
    }

    /** Append one redacted, area-tagged line. Safe from any thread. */
    @JvmStatic
    fun log(area: String, msg: String) {
        val line = "[" + stamp.format(Date()) + "] " + area + ": " + SteamLogRedactor.redact(msg)
        Log.i(TAG, "$area: ${SteamLogRedactor.redact(msg)}")
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > MAX_LINES) ring.removeFirst()
        }
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                // Keep the most recent half so the file never grows without bound.
                val tail = f.readLines().takeLast(MAX_LINES / 2)
                f.writeText(tail.joinToString("\n") + "\n")
            }
            BufferedWriter(FileWriter(f, true)).use { w -> w.write(line); w.write("\n") }
        } catch (_: Throwable) {
        }
    }

    /** The in-memory lines, oldest first. */
    @JvmStatic
    fun lines(): List<String> = synchronized(ring) { ring.toList() }

    /** Lines whose timestamp is at/after [sinceMs] (0 = everything). */
    @JvmStatic
    fun linesSince(sinceMs: Long): List<String> {
        if (sinceMs <= 0L) return lines()
        val cut = "[" + stamp.format(Date(sinceMs)) + "]"
        val all = lines()
        val idx = all.indexOfFirst { it.substring(0, minOf(it.length, cut.length)) >= cut }
        return if (idx < 0) emptyList() else all.subList(idx, all.size)
    }

    /** The on-disk file (may not exist yet). */
    @JvmStatic
    fun file(): File? = file
}
