package com.winlator.star.store

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight rolling debug log for friend-chat + image diagnostics. Release APKs strip logcat, so a
 * on-device chat problem (double sends, history wipes, image-upload failures) is otherwise invisible.
 * Writes timestamped lines to `Download/steam_chat_debug.txt` (grab-able like the download engine's
 * `steam_debug.txt`), size-capped + rolled. Every call is best-effort and never throws.
 *
 * Enabled by default so a user hitting a chat bug already has a log to send; delete the file to reset.
 */
object SteamChatDebug {

    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val CAP_BYTES = 400_000L

    /** Point the log at Download/, falling back to the app's external files dir if that isn't writable. */
    fun init(ctx: Context) {
        if (file != null) return
        file = try {
            val dl = File("/storage/emulated/0/Download/steam_chat_debug.txt")
            if (dl.parentFile?.canWrite() == true || dl.exists()) dl
            else File(ctx.getExternalFilesDir(null), "steam_chat_debug.txt")
        } catch (_: Throwable) {
            try { File(ctx.getExternalFilesDir(null), "steam_chat_debug.txt") } catch (_: Throwable) { null }
        }
        log("=== chat debug log opened ===")
    }

    fun log(line: String) {
        val f = file ?: return
        try {
            if (f.length() > CAP_BYTES) {
                val txt = f.readText()
                f.writeText(txt.substring(maxOf(0, txt.length - CAP_BYTES.toInt() / 2)))
            }
            f.appendText("[${fmt.format(Date())}] $line\n")
        } catch (_: Throwable) {}
    }

    /** Trim a message body for the log (avoid dumping full image URLs / long text). */
    fun snip(s: String?): String {
        val t = s ?: return "<null>"
        return if (t.length > 70) t.substring(0, 70) + "…" else t
    }
}
