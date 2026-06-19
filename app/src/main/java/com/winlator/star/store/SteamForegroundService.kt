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
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to Steam…"))
        Log.i(TAG, "Service started")

        SteamRepository.getInstance().initialize(this)
        SteamRepository.getInstance().connect()

        return START_STICKY   // restart if killed by OS
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed — disconnecting")
        SteamRepository.getInstance().disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
