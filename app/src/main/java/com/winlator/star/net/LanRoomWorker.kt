package com.winlator.star.net

import com.winlator.star.core.HttpUtils
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/** A room handed back by the LAN signaling worker: where to connect and what role we play. */
data class LanRoom(
    val code: String,   // 6-char room code (host shares this)
    val relay: String,  // relay host/IP
    val port: Int,      // relay UDP port
    val room: String,   // room key used with the relay (== code)
    val role: Int,      // LanOverlay.ROLE_HOST or ROLE_CLIENT
)

/**
 * Thin client for the LAN room-signaling endpoints on the SAME first-party worker the community-config
 * feature already uses. All calls BLOCK (bridged off [HttpUtils]' async executor via a latch, exactly
 * like [com.winlator.star.communityconfigs.CommunityConfigWorker]) — invoke from a background thread.
 * Failures degrade to null, never throw.
 */
object LanRoomWorker {
    private const val BASE = "https://bannerhub-configs-worker.the412banner.workers.dev"

    /** POST /lan/host -> a new room we host (role 1). null on failure. */
    fun host(): LanRoom? = parse(post("$BASE/lan/host", "{}"))

    /** POST /lan/join {code} -> the room to join as client (role 2). null if not found / full / error. */
    fun join(code: String): LanRoom? {
        val body = JSONObject().put("code", code.trim().uppercase()).toString()
        return parse(post("$BASE/lan/join", body))
    }

    /** POST /lan/leave {code} — best-effort teardown. */
    fun leave(code: String) {
        post("$BASE/lan/leave", JSONObject().put("code", code.trim().uppercase()).toString())
    }

    private fun parse(resp: String?): LanRoom? {
        if (resp == null) return null
        return try {
            val o = JSONObject(resp)
            if (o.has("error")) return null
            val room = o.optString("room", o.optString("code", ""))
            if (room.isEmpty()) return null
            LanRoom(
                code = o.optString("code", room),
                relay = o.optString("relay", ""),
                port = o.optInt("port", 48800),
                room = room,
                role = o.optInt("role", 1),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun post(url: String, jsonBody: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        HttpUtils.post(url, jsonBody) { body ->
            holder[0] = body
            latch.countDown()
        }
        return try {
            latch.await()
            holder[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
