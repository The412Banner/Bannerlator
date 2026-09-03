package com.winlator.star.store

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.winlator.star.store.download.DownloadRegistry

/**
 * Foreground service that keeps the Steam CM connection alive while downloading
 * or staying logged in.
 *
 * Started by SteamMainActivity; stopped when the user logs out or closes the app.
 *
 * Lifecycle:
 *   startService(Intent(ctx, SteamForegroundService::class.java))
 *   → onStartCommand → startForeground → SteamRepository.connect()
 *
 *   stopService(Intent(ctx, SteamForegroundService::class.java))
 *   → onDestroy → SteamRepository.disconnect()
 */
class SteamForegroundService : Service() {

    companion object {
        private const val TAG             = "SteamService"
        private const val CHANNEL_ID      = "steam_connection_channel"
        private const val NOTIFICATION_ID = 9001

        // Process-static handle to the live service so other classes (SteamRepository status
        // transitions, SteamDepotDownloader progress) can push the notification text without a
        // Context or a bind. Null whenever the FGS isn't running, which makes setStatusText a
        // safe no-op — callers never need to know whether the service is up. @JvmStatic so the
        // Java SteamRepository can invoke it as SteamForegroundService.setStatusText(...).
        @Volatile private var instance: SteamForegroundService? = null

        /**
         * Push notification text to the running FGS. No-op (returns immediately) when the service
         * isn't up — safe to call from any thread, any class, at any lifecycle point.
         *
         * FOLLOW-UP: if the partial wakelock proves insufficient against the OEM killer, the
         * heavyweight alternative is a single-owner dedicated ':steam' process for this service +
         * SteamRepository so the CM session lives in its own process the launcher won't churn.
         * Deliberately NOT done here (it's a much larger refactor of the repository singleton).
         */
        @JvmStatic
        fun setStatusText(text: String) {
            instance?.updateNotification(text)
        }

        /** Start the service from any Context. */
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, SteamForegroundService::class.java))
        }

        /** Stop the service from any Context. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SteamForegroundService::class.java))
        }

        /**
         * Stop unless a download still needs the CM session. Nothing in the app used to call
         * stop() at all, so the service — started unconditionally by SteamMainActivity, before the
         * sign-in check — became permanent the moment the store was opened once.
         */
        @JvmStatic
        fun stopIfIdle(ctx: Context) {
            val busy = try { DownloadRegistry.activeCount.value > 0 } catch (t: Throwable) { false }
            if (busy) {
                Log.i(TAG, "stopIfIdle skipped — download in flight")
                return
            }
            stop(ctx)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this        // publish before anything can call setStatusText
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this        // re-publish on every (re)start
        // Seed from the LIVE connection state, never a constant. setStatus() only pushes text on a
        // TRANSITION, so a hardcoded "Connecting to Steam…" here survived forever on every restart
        // that produced none — a process restart, or the extra start() calls from
        // SteamGameDetailActivity / SteamSaveManagerActivity / SteamSessionManager on a session
        // that is already up (connect() below then early-returns without touching the status).
        startForeground(NOTIFICATION_ID, buildNotification(currentStatusText()))
        Log.i(TAG, "Service started")

        SteamRepository.getInstance().initialize(this)

        // Cross-store Download Manager (Phase 2): bring up the store-agnostic registry and
        // seed it with the already-installed Steam library so its Library section is
        // populated on first open. Both are idempotent; the DB is ready post-initialize.
        DownloadRegistry.init(this)
        SteamLibrarySync.seed(this)

        SteamRepository.getInstance().connect()
        // connect() may have short-circuited (already connected / already logged on) without a
        // transition — re-assert the real state so the seeded text can never be left stale.
        SteamRepository.getInstance().refreshFgsStatus()

        // NOT START_STICKY: a Steam CM session is not worth resurrecting behind the user's back.
        // Sticky restarts were re-running this method (and re-showing the notification) after the
        // app was gone, which is half of why the notification looked impossible to get rid of.
        return START_NOT_STICKY
    }

    /**
     * Recents-swipe. A started foreground service is not bound to the task, and the manifest entry
     * sets no android:stopWithTask, so without this the service and its ongoing notification
     * outlived the app until a force-stop. Stay up only while a download is actually using the CM
     * session this service owns — onDestroy() disconnects it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (hasActiveDownload()) {
            Log.i(TAG, "Task removed — staying up for an active download")
            return
        }
        Log.i(TAG, "Task removed — stopping")
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed — disconnecting")
        if (instance === this) instance = null   // only clear if we're the current live instance
        SteamRepository.getInstance().disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The live connection state as a notification line; falls back if the repository isn't up. */
    private fun currentStatusText(): String =
        try { SteamRepository.getInstance().currentFgsText() } catch (t: Throwable) { "Offline" }

    private fun hasActiveDownload(): Boolean =
        try { DownloadRegistry.activeCount.value > 0 } catch (t: Throwable) { false }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            "Steam Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Steam connection alive while browsing or downloading games"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SteamMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Steam")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    /** Update notification text — called from outside (e.g., during downloads). */
    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
