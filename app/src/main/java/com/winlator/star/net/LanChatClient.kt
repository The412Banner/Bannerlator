package com.winlator.star.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OkHttp [WebSocket] wrapper for the dedicated LAN-chat worker (a Cloudflare Durable Object keyed by the
 * 6-char room code — the SAME code the overlay uses). One client == one live socket to one room.
 *
 * Independent of the overlay tunnel by design: the chat DO+WS keeps working pre-game, mid-game, and even
 * after the tunnel drops (exactly when the peers need to say "you dropped, rejoin"). Auto-reconnects with
 * capped exponential backoff so a brief blip doesn't kill the conversation; the DO replays recent history
 * on every (re)connect so nothing is lost.
 *
 * Wire format (JSON text frames):
 *   server->client on connect : {"t":"history","messages":[{from,name,text,ts},...]}
 *   server->client broadcast   : {"t":"presence","event":"join|leave","role","name","count"}
 *   server->client broadcast   : {"t":"msg","from":"host|guest","name","text","ts"}  (incl. echo to sender)
 *   client->server to send     : {"t":"msg","text":"..."}   (server trims / caps at 500 chars)
 * Auth = the code is the gate; role/name are self-declared.
 *
 * All [Listener] callbacks fire on OkHttp's socket thread. [LanChat] (the only caller) forwards them into
 * thread-safe StateFlows, so no extra marshalling is needed here.
 */
class LanChatClient(
    private val code: String,
    private val role: String,      // "host" | "guest"
    private val displayName: String?,  // signed-in username, or null -> server defaults to Host/Guest
    private val listener: Listener,
) {
    /** One incoming chat line (history item or a live broadcast). */
    data class Incoming(val from: String, val name: String, val text: String, val ts: Long)

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onHistory(messages: List<Incoming>)
        fun onMessage(msg: Incoming)
        fun onPresence(event: String, role: String, name: String, count: Int)
        fun onTyping(role: String, name: String)
    }

    // A tiny dedicated client — HttpUtils' client is tuned for one-shot REST calls; a WS wants its own
    // ping keepalive and no read timeout. pingInterval keeps the DO's hibernated socket warm + detects
    // dead links quickly so the backoff reconnect kicks in.
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ws: WebSocket? = null
    private val closed = AtomicBoolean(false)
    private var attempt = 0

    fun connect() {
        if (closed.get()) return
        open()
    }

    /** Send a chat line. Empty/blank is ignored; the server trims + caps at 500 chars, we cap locally too. */
    fun send(text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        ws?.send(JSONObject().put("t", "msg").put("text", t).toString())
    }

    /** Notify the peer we're typing (relayed, not stored). Caller throttles. */
    fun sendTyping() {
        ws?.send(JSONObject().put("t", "typing").toString())
    }

    /** Permanent close — stops reconnect and tears down the socket + scope. */
    fun close() {
        if (closed.getAndSet(true)) return
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        scope.cancel()
    }

    private fun url(): String {
        val sb = StringBuilder(BASE).append("/chat/").append(code).append("?role=").append(role)
        if (!displayName.isNullOrBlank()) sb.append("&name=").append(URLEncoder.encode(displayName, "UTF-8"))
        return sb.toString()
    }

    private fun open() {
        ws = http.newWebSocket(Request.Builder().url(url()).build(), socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            try { webSocket.close(1000, null) } catch (_: Exception) {}
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener.onDisconnected()
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener.onDisconnected()
            scheduleReconnect()
        }
    }

    private fun handle(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("t")) {
                "history" -> {
                    val arr = o.optJSONArray("messages") ?: JSONArray()
                    val out = ArrayList<Incoming>(arr.length())
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it.toIncoming()) }
                    listener.onHistory(out)
                }
                "msg" -> listener.onMessage(o.toIncoming())
                "typing" -> listener.onTyping(o.optString("from"), o.optString("name"))
                "presence" -> listener.onPresence(
                    o.optString("event"),
                    o.optString("role"),
                    o.optString("name"),
                    o.optInt("count", 1),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "dropping malformed chat frame", e)
        }
    }

    private fun JSONObject.toIncoming() = Incoming(
        from = optString("from"),
        name = optString("name"),
        text = optString("text"),
        ts = optLong("ts", System.currentTimeMillis()),
    )

    // Capped exponential backoff: 1,2,4,8,16,30,30... seconds. attempt resets to 0 on a clean onOpen.
    private fun scheduleReconnect() {
        if (closed.get()) return
        val n = ++attempt
        val backoff = minOf(30_000L, 1_000L * (1L shl minOf(n - 1, 5)))
        scope.launch {
            delay(backoff)
            if (!closed.get()) open()
        }
    }

    companion object {
        private const val TAG = "LanChat"
        private const val BASE = "wss://bannerlator-lan-chat.the412banner.workers.dev"
    }
}
