package com.winlator.star.store.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.winlator.star.store.DownloadManagerActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Store-agnostic foreground service that keeps the process alive while ANY non-Steam store
 * download is running, and surfaces a live progress notification in the system shade.
 *
 * This is the direct analogue of [com.winlator.star.store.SteamForegroundService], but for
 * the cross-store Download Manager: Steam already had its own FGS (which also keeps the CM
 * connection up); Amazon (and, later, GOG / Epic) downloads had NO foreground service, so
 * backgrounding the app or destroying the store's detail Activity could kill them. Every
 * store that routes through [StoreDownloadHooks] now inherits this for free.
 *
 * Design (mirrors SteamForegroundService):
 *   - process-static [instance] handle so [StoreDownloadHooks] can push updates without a bind;
 *   - a process-static [active] map (key → latest line) that is the source of truth for the
 *     notification. It lives in the companion, not the instance, so a progress tick that
 *     arrives a hair before onStartCommand publishes [instance] is never lost — onStartCommand
 *     rebuilds the notification straight from the map.
 *
 * Lifecycle: [StoreDownloadHooks.registerDownload] calls [start] + [setProgress]; each tick
 * calls [setProgress]; each terminal transition calls [finish]. When the last active download
 * finishes, the service removes its notification and stops itself.
 *
 * NOTE (POST_NOTIFICATIONS): the runtime notification permission is already requested globally
 * by MainActivity / SteamMainActivity, so no per-download prompt is needed here — if the user
 * denied it the notification is silently dropped by the framework but the download (kept alive
 * by the FGS + [DownloadScope]) still completes.
 */
class DownloadForegroundService : Service() {

    companion object {
        private const val TAG             = "DownloadService"
        private const val CHANNEL_ID      = "downloads_channel"
        // Distinct from SteamForegroundService's 9001 so the two ongoing notifications coexist.
        private const val NOTIFICATION_ID = 9002

        /** One active download's latest shade line, plus a monotonic seq to pick "most recent". */
        private data class Active(val text: String, val seq: Long)

        // Process-static so updates are never lost across the (async) service start. Thread-safe:
        // ticks arrive off background download threads.
        private val active = ConcurrentHashMap<String, Active>()
        private val seqGen = AtomicLong(0L)

        @Volatile private var instance: DownloadForegroundService? = null

        /** Start the FGS from any Context (idempotent). Uses the application context. */
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            app.startService(Intent(app, DownloadForegroundService::class.java))
        }

        /**
         * Publish/refresh a download's notification line ([text] is the fully-formatted body,
         * e.g. "Half-Life 2 — 20% (810.2 MB / 3.9 GB)"). Safe from any thread. If the service
         * isn't up yet the line is stored and rendered by onStartCommand; if it is up, the
         * ongoing notification is refreshed immediately.
         */
        fun setProgress(key: String, text: String) {
            active[key] = Active(text, seqGen.incrementAndGet())
            instance?.refreshNotification()
        }

        /**
         * Mark a download terminal (installed / cancelled / failed): drop its line. When the
         * active set becomes empty the service tears down its notification and stops. Safe from
         * any thread; a no-op if the key was never registered.
         */
        fun finish(key: String) {
            active.remove(key)
            val inst = instance ?: return
            if (active.isEmpty()) inst.stopNow() else inst.refreshNotification()
        }

        private const val FINISHED_TAG = "download-finished"

        /**
         * A dismissible "<game> / Download complete" line for a finished download, separate from the
         * ongoing one so it stays after the service stops. Tapping it opens the Download Manager.
         * Skipped when notifications are off for the app. Safe from any thread.
         */
        fun postFinished(ctx: Context, key: String, name: String) {
            try {
                val app = ctx.applicationContext
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.areNotificationsEnabled()) return
                ensureChannel(app)
                val tap = PendingIntent.getActivity(
                    app, key.hashCode(),
                    Intent(app, DownloadManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(name)
                    .setContentText("Download complete")
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build()
                nm.notify(FINISHED_TAG, key.hashCode(), n)
            } catch (t: Throwable) {
                Log.w(TAG, "finished notification failed for $key: ${t.message}")
            }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,   // quiet: no sound/heads-up for a progress bar
            ).apply {
                description = "Shows game download progress and keeps downloads running in the background"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this               // publish before anything can call setProgress/finish
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this               // re-publish — START_STICKY may recreate us
        startForegroundCompat(buildNotification())
        Log.i(TAG, "Service started (${active.size} active)")

        // Sticky restart after a process kill re-delivers a null intent with nothing to do
        // (the coroutine that was downloading died with the process). Don't linger as a zombie
        // FGS — drop straight back out.
        if (active.isEmpty()) {
            stopNow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null   // only clear if we're the current live instance
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() = ensureChannel(this)

    /**
     * Build the ongoing notification from the [active] map. One active download → its line as
     * the body; multiple → an "N downloads" title with the most-recently-updated line as the
     * body (so the shade always shows live movement).
     */
    private fun buildNotification(): Notification {
        val count = active.size
        val recent = active.values.maxByOrNull { it.seq }?.text ?: "Preparing…"
        val title = if (count > 1) "$count downloads" else "Downloading"

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DownloadManagerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(recent)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    /** startForeground with the dataSync type on API 29+ (matches the manifest declaration). */
    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    /** Remove the notification and stop the service (called when no downloads remain). */
    private fun stopNow() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
