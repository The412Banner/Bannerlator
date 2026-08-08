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
import androidx.core.content.ContextCompat
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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this        // publish before anything can call setStatusText
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this        // re-publish on every (re)start — START_STICKY may recreate us
        val strings = ContextCompat.getContextForLanguage(this)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(strings.getString(com.winlator.star.R.string.steam_status_connecting)),
        )
        Log.i(TAG, "Service started")

        SteamRepository.getInstance().initialize(this)

        // Cross-store Download Manager (Phase 2): bring up the store-agnostic registry and
        // seed it with the already-installed Steam library so its Library section is
        // populated on first open. Both are idempotent; the DB is ready post-initialize.
        DownloadRegistry.init(this)
        SteamLibrarySync.seed(this)

        SteamRepository.getInstance().connect()

        return START_STICKY   // restart if killed by OS
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed — disconnecting")
        if (instance === this) instance = null   // only clear if we're the current live instance
        SteamRepository.getInstance().disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val strings = ContextCompat.getContextForLanguage(this)

        val ch = NotificationChannel(
            CHANNEL_ID,
            strings.getString(com.winlator.star.R.string.steam_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = strings.getString(com.winlator.star.R.string.steam_notification_channel_description)
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
