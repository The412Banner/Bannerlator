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
    // OPTIONAL host identity: present only when the HOST was signed into their Bannerlator account
    // (they passed a session to /lan/host and the worker stamped host_uid). Anonymous hosts => null.
    val hostUid: String? = null,   // the host's account user-id, if any
    val hostName: String? = null,  // the host's username, if any (drives "Hosted by <name>")
)

/**
 * Thin client for the LAN room-signaling endpoints on the SAME first-party worker the community-config
 * feature already uses. All calls BLOCK (bridged off [HttpUtils]' async executor via a latch, exactly
 * like [com.winlator.star.communityconfigs.CommunityConfigWorker]) — invoke from a background thread.
 * Failures degrade to null, never throw.
 */
object LanRoomWorker {
    private const val BASE = "https://bannerhub-configs-worker.the412banner.workers.dev"

    /**
     * POST /lan/host -> a new room we host (role 1). null on failure.
     *
     * [session] is OPTIONAL account flavor: when the user is signed into their Bannerlator account we pass
     * their session token so the worker stamps host_uid (joiners then see "Hosted by <username>"). Anonymous
     * hosts pass null and nothing changes — sign-in is NEVER required to host or join.
     */
    fun host(session: String? = null): LanRoom? {
        val body = JSONObject()
            .also { if (!session.isNullOrBlank()) it.put("session", session) }
            .toString()
        return parse(post("$BASE/lan/host", body))
    }

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
                // Optional host identity (present when the host was signed in). Tolerate a few key spellings.
                hostUid = o.optString("host_uid", o.optString("hostUid", "")).ifBlank { null },
                hostName = o.optString("host_username", o.optString("hostUsername", o.optString("host_name", "")))
                    .ifBlank { null },
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
