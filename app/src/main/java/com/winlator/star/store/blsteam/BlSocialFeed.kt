package com.winlator.star.store.blsteam

import android.util.Log
import com.winlator.star.store.SteamFriendsStore
import org.json.JSONArray

/**
 * Social push decoder for the Rust engine (Phase 3a-2): taps [BlSteamEngine]'s inbound message
 * firehose and turns the CM pushes JavaSteam used to deliver as callbacks into calls on
 * [SteamFriendsStore]'s engine-side handlers:
 *
 *  - `CMsgClientFriendsList` (767)      → [SteamFriendsStore.rustOnFriendsList]   (FriendsListCallback)
 *  - `CMsgClientPersonaState` (766)     → [SteamFriendsStore.rustOnPersonaState]  (PersonaStateCallback)
 *  - `CMsgClientPlayerNicknameList` (5587) → [SteamFriendsStore.rustOnNicknameList] (NicknameListCallback)
 *  - `CMsgClientAddFriendResponse` (792) → [SteamFriendsStore.rustOnAddFriendResponse] (FriendAddedCallback)
 *  - `ServiceMethod` / `SendToClient` (146/152) → the engine queued any `FriendMessagesClient.
 *    IncomingMessage` (text or typing) → [SteamFriendsStore.rustDrainIncomingMessages]
 *    (FriendMsgCallback / FriendMsgEchoCallback)
 *
 * Runs on the native pump thread: decoding is a few microseconds; the store hops onto its own
 * executor for anything heavier. Installed once by the store's init (a no-op tap while JavaSteam
 * drives the session, since the engine then has no live session to fire it).
 */
object BlSocialFeed : BlSteamEngine.MessageTap {

    private const val TAG = "BL_STEAM_SOCIAL"

    const val EMSG_SERVICE_METHOD = 146
    const val EMSG_SERVICE_METHOD_SEND_TO_CLIENT = 152
    const val EMSG_CLIENT_PERSONA_STATE = 766
    const val EMSG_CLIENT_FRIENDS_LIST = 767
    const val EMSG_CLIENT_ADD_FRIEND_RESPONSE = 792
    const val EMSG_CLIENT_PLAYER_NICKNAME_LIST = 5587

    /** EFriendRelationship codes. */
    const val REL_NONE = 0
    const val REL_BLOCKED = 1
    const val REL_REQUEST_RECIPIENT = 2
    const val REL_FRIEND = 3
    const val REL_REQUEST_INITIATOR = 4

    /** One `CMsgClientPersonaState.Friend` entry; `has*` mirror the protobuf presence bits. */
    class Persona(val steamId: Long) {
        var personaState = 0
        var hasPersonaState = false
        var gameAppId = 0
        var hasGame = false
        var playerName = ""
        var avatarHash: String? = null
        var hasAvatar = false
        var gameName = ""
        var richPresence: MutableMap<String, String>? = null
        /** `game_lobby_id` (field 73, fixed64) — the lobby the friend is in; [hasLobby] mirrors the presence bit. */
        var gameLobbyId = 0L
        var hasLobby = false
    }

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        BlSteamEngine.addMessageTap(this)
    }

    override fun onMessage(emsg: Int, eresult: Int, body: ByteArray) {
        try {
            when (emsg) {
                EMSG_CLIENT_FRIENDS_LIST -> decodeFriendsList(body)
                EMSG_CLIENT_PERSONA_STATE -> decodePersonaState(body)
                EMSG_CLIENT_PLAYER_NICKNAME_LIST -> decodeNicknameList(body)
                EMSG_CLIENT_ADD_FRIEND_RESPONSE -> decodeAddFriendResponse(body)
                EMSG_SERVICE_METHOD, EMSG_SERVICE_METHOD_SEND_TO_CLIENT ->
                    SteamFriendsStore.rustDrainIncomingMessages()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed for emsg=$emsg", t)
        }
    }

    // ── CMsgClientFriendsList: bincremental (1), friends (2) { ulfriendid fixed64 (1), efriendrelationship (2) }
    private fun decodeFriendsList(body: ByteArray) {
        var incremental = false
        val entries = ArrayList<Pair<Long, Int>>()
        val r = BlProto(body)
        while (r.next()) {
            when (r.field) {
                1 -> if (r.wireType == 0) incremental = r.bool()
                2 -> if (r.wireType == 2) {
                    val e = r.sub()
                    var sid = 0L
                    var rel = 0
                    while (e.next()) {
                        when (e.field) {
                            1 -> if (e.wireType == 1) sid = e.fixed64
                            2 -> if (e.wireType == 0) rel = e.int()
                        }
                    }
                    if (sid != 0L) entries.add(sid to rel)
                }
            }
        }
        SteamFriendsStore.rustOnFriendsList(incremental, entries)
    }

    // ── CMsgClientPersonaState: friends (2) { friendid fixed64 (1), persona_state (2), game_played_app_id (3),
    //    player_name (15), avatar_hash (31), game_name (55), rich_presence (71) { key (1), value (2) },
    //    game_lobby_id fixed64 (73) }. Field 25 is steamid_source (fixed64) in the real proto; the
    //    submessage arm on 25 is kept only for the historical shape.
    private fun decodePersonaState(body: ByteArray) {
        val list = ArrayList<Persona>()
        val r = BlProto(body)
        while (r.next()) {
            if (r.field != 2 || r.wireType != 2) continue
            val f = r.sub()
            var p: Persona? = null
            var state = 0; var hasState = false
            var app = 0; var hasApp = false
            var name = ""
            var avatar: String? = null; var hasAvatar = false
            var gameName = ""
            var rp: MutableMap<String, String>? = null
            var sid = 0L
            var lobby = 0L; var hasLobby = false
            while (f.next()) {
                when (f.field) {
                    1 -> if (f.wireType == 1) sid = f.fixed64
                    2 -> if (f.wireType == 0) { state = f.int(); hasState = true }
                    3 -> if (f.wireType == 0) { app = f.int(); hasApp = true }
                    15 -> if (f.wireType == 2) name = f.string()
                    73 -> if (f.wireType == 1) { lobby = f.fixed64; hasLobby = true }
                    25, 71 -> if (f.wireType == 2) {
                        val kv = f.sub()
                        var k = ""; var v = ""
                        while (kv.next()) {
                            if (kv.wireType != 2) continue
                            when (kv.field) { 1 -> k = kv.string(); 2 -> v = kv.string() }
                        }
                        if (k.isNotEmpty()) (rp ?: LinkedHashMap<String, String>().also { rp = it })[k] = v
                    }
                    31 -> if (f.wireType == 2) { avatar = BlProto.hexOrNull(f.bytes); hasAvatar = f.bytes.isNotEmpty() }
                    55 -> if (f.wireType == 2) gameName = f.string()
                }
            }
            if (sid == 0L) continue
            p = Persona(sid)
            p.personaState = state; p.hasPersonaState = hasState
            p.gameAppId = app; p.hasGame = hasApp
            p.playerName = name
            p.avatarHash = avatar; p.hasAvatar = hasAvatar
            p.gameName = gameName
            p.richPresence = rp
            p.gameLobbyId = lobby; p.hasLobby = hasLobby
            list.add(p)
        }
        if (list.isNotEmpty()) SteamFriendsStore.rustOnPersonaState(list)
    }

    // ── CMsgClientPlayerNicknameList: removal (1), incremental (2), nicknames (3) { accountid (1), nickname (2) }
    private fun decodeNicknameList(body: ByteArray) {
        var removal = false
        var incremental = false
        val nicks = ArrayList<Pair<Int, String>>()
        val r = BlProto(body)
        while (r.next()) {
            when (r.field) {
                1 -> if (r.wireType == 0) removal = r.bool()
                2 -> if (r.wireType == 0) incremental = r.bool()
                3 -> if (r.wireType == 2) {
                    val n = r.sub()
                    var account = 0; var nick = ""
                    while (n.next()) {
                        when (n.field) {
                            1 -> if (n.wireType == 0) account = n.int()
                            2 -> if (n.wireType == 2) nick = n.string()
                        }
                    }
                    if (account != 0) nicks.add(account to nick)
                }
            }
        }
        SteamFriendsStore.rustOnNicknameList(removal, incremental, nicks)
    }

    // ── CMsgClientAddFriendResponse: eresult (1), steam_id_added fixed64 (2), persona_name_added (3)
    private fun decodeAddFriendResponse(body: ByteArray) {
        var eresult = 0
        var sid = 0L
        var name = ""
        val r = BlProto(body)
        while (r.next()) {
            when (r.field) {
                1 -> if (r.wireType == 0) eresult = r.int()
                2 -> if (r.wireType == 1) sid = r.fixed64
                3 -> if (r.wireType == 2) name = r.string()
            }
        }
        SteamFriendsStore.rustOnAddFriendResponse(eresult, sid, name)
    }

    /** Parse the engine's `getFriendRelationships()` JSON into (steamId, relationship) pairs. */
    fun parseRelationships(json: String): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optLong("sid", 0L)
                if (sid != 0L) out.add(sid to o.optInt("rel", 0))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "relationships parse failed", t)
        }
        return out
    }
}
