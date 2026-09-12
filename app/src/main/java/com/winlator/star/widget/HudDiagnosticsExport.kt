package com.winlator.star.widget

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap "Export HUD diagnostics" action, shared by both HUD config surfaces (the pre-launch
 * [com.winlator.star.ui.screens.FpsCounterConfigDialog] and the in-game XServerDrawer HUD pane).
 *
 * Runs [HudMetrics.buildDiagnosticsReport] on a background thread (it primes + samples the sysfs
 * readers, so it must never touch the main thread), writes the plain-text report to the public
 * Downloads folder, and toasts the saved filename. This is a manual, invoked-only action that saves
 * SILENTLY — no share sheet, no new Activity, no chooser — so it never interrupts gameplay; nothing
 * here runs on the HUD refresh path. The app already writes to public Downloads with the same File API
 * elsewhere (save export, community-config export) and declares MANAGE_EXTERNAL_STORAGE /
 * WRITE_EXTERNAL_STORAGE, so scoped storage doesn't block the write; failures are caught and reported
 * via Toast regardless.
 */
fun exportHudDiagnostics(context: Context) {
    val app = context.applicationContext
    Toast.makeText(app, "Saving HUD diagnostics…", Toast.LENGTH_SHORT).show()
    val main = Handler(Looper.getMainLooper())
    Thread {
        val result = runCatching {
            val report = HudMetrics(app).buildDiagnosticsReport(app)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads != null && !downloads.exists()) downloads.mkdirs()
            val out = File(downloads, "bannerlator-hud-diag-$ts.txt")
            out.writeText(report)
            out.setReadable(true, false)
            MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), null, null)
            out
        }
        main.post {
            result.onSuccess { out ->
                Toast.makeText(app, "Saved to Downloads › ${out.name}", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(app, "Couldn't export diagnostics.", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}
