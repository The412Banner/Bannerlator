package com.winlator.star.store

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Posts Steam friend-chat messages to the system shade, as a chat-style heads-up notification per
 * conversation, and clears them in lock-step with the in-app unread indicator. Driven entirely by
 * [SteamFriendsStore] (post on an incoming message when that friend's chat isn't open; cancel when it
 * is opened; cancel-all on logout), so the shade and the in-app badge never disagree.
 *
 * Everything here is best-effort and non-throwing: [notify] is invoked from the CM pump thread where a
 * crash is unacceptable, so the actual work (avatar fetch + build + post) is bounced onto a private
 * background executor and wrapped so it can never propagate back to the caller. A denied
 * POST_NOTIFICATIONS permission just means the framework drops the post — the message still lands in
 * the app.
 */
object SteamChatNotifier {

    private const val TAG = "BH_STEAM_CHATNOTIF"

    /** Dedicated "Steam Messages" channel — HIGH so a new message heads-up like a normal chat app. */
    private const val CHANNEL_ID = "steam_chat_messages"

    /** Group + summary so several friends' notifications bundle instead of scattering the shade. */
    private const val GROUP_KEY = "steam_chat"
    /** Fixed id for the group-summary notification (kept clear of any per-conversation id). */
    private const val SUMMARY_ID = 0x51_00_00_01

    /** Off-caller work (avatar fetch + notification build). Single daemon thread, like the store's io. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SteamChatNotifier").apply { isDaemon = true }
    }

    private data class Line(val text: String, val timeMs: Long)

    /** Recent incoming lines per conversation, so the shade renders a short chat thread (capped). */
    private val convos = ConcurrentHashMap<Long, ArrayDeque<Line>>()
    /** Notification ids we currently have posted (so [cancelAll] can clear exactly ours). */
    private val activeIds: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    /** avatarUrl -> decoded bitmap cache (Steam avatars are tiny + stable). null = fetched, no image. */
    private val avatarCache = ConcurrentHashMap<String, Bitmap>()

    private const val MAX_LINES = 6

    /** Stable per-conversation id: same friend's notification updates in place instead of stacking. */
    private fun notifIdFor(steamId: Long): Int = (steamId and 0x7FFFFFFF).toInt()

    /**
     * Post (or update) the notification for [steamId]'s conversation with [messageText] from
     * [senderName]. Loads [avatarUrl] to a Person icon best-effort off the caller thread. Never throws.
     */
    fun notify(
        context: Context,
        steamId: Long,
        senderName: String,
        avatarUrl: String?,
        messageText: String,
    ) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        worker.execute {
            try {
                ensureChannel(app)

                val deque = convos.getOrPut(steamId) { ArrayDeque() }
                synchronized(deque) {
                    deque.addLast(Line(messageText, now))
                    while (deque.size > MAX_LINES) deque.removeFirst()
                }

                val avatar = avatarUrl?.let { loadAvatar(it) }
                val sender = Person.Builder()
                    .setName(senderName.ifBlank { "Steam friend" })
                    .apply { avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
                    .build()
                val self = Person.Builder().setName("You").build()

                val style = NotificationCompat.MessagingStyle(self)
                val snapshot = synchronized(deque) { deque.toList() }
                for (l in snapshot) style.addMessage(l.text, l.timeMs, sender)

                val notifId = notifIdFor(steamId)
                val n = NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setStyle(style)
                    .setContentIntent(contentIntent(app, steamId, notifId))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setGroup(GROUP_KEY)
                    .setWhen(now)
                    .setShowWhen(true)
                    .build()

                activeIds.add(notifId)
                val nm = NotificationManagerCompat.from(app)
                nm.notify(notifId, n)
                nm.notify(SUMMARY_ID, buildSummary(app))
            } catch (t: Throwable) {
                Log.w(TAG, "notify failed", t)
            }
        }
    }

    /** Dismiss [steamId]'s conversation notification (its chat was opened / marked read). */
    fun cancel(context: Context, steamId: Long) {
        try {
            val notifId = notifIdFor(steamId)
            convos.remove(steamId)
            activeIds.remove(notifId)
            val nm = NotificationManagerCompat.from(context.applicationContext)
            nm.cancel(notifId)
            if (activeIds.isEmpty()) nm.cancel(SUMMARY_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed", t)
        }
    }

    /** Dismiss every Steam chat notification we posted (logout / privacy clear). */
    fun cancelAll(context: Context) {
        try {
            val nm = NotificationManagerCompat.from(context.applicationContext)
            for (id in activeIds.toList()) nm.cancel(id)
            nm.cancel(SUMMARY_ID)
            activeIds.clear()
            convos.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "cancelAll failed", t)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** Deep-link the tap into that friend's chat; opening it clears unread + cancels this notification. */
    private fun contentIntent(context: Context, steamId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, SteamFriendsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SteamFriendsActivity.EXTRA_OPEN_FRIEND, steamId)
        // Unique request code per conversation so the extras aren't collapsed into one PendingIntent.
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildSummary(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Steam messages")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

    /** Create the channel once; idempotent (mirrors the FGS channel pattern). */
    private fun ensureChannel(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Steam Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Chat messages from your Steam friends"
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureChannel failed", t)
        }
    }

    /** Best-effort avatar fetch → Bitmap (cached). Never throws; returns null on any failure. */
    private fun loadAvatar(url: String): Bitmap? {
        avatarCache[url]?.let { return it }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Bannerlator")
                connectTimeout = 8000
                readTimeout = 8000
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also { avatarCache[url] = it }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadAvatar failed", t)
            null
        }
    }
}
