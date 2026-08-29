package com.winlator.star.store

import android.util.Log
import `in`.dragonbra.javasteam.enums.EChatEntryType
import `in`.dragonbra.javasteam.enums.EFriendRelationship
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendAddedCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgEchoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendMsgHistoryCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.NicknameListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.types.SteamID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Facade for the Steam friends list + 1:1 text chat, riding the live [SteamRepository] CM session —
 * the same "read/act through the shared handler" pattern as [SteamAchievementStore], but PUSH: the
 * friend/message callbacks are subscribed on the repository's existing CallbackManager pump (see
 * SteamRepository.registerCallbacks) and forwarded here on the pump thread. This object owns no
 * session lifecycle; if the session isn't live ([isAvailable] == false) every entry point is a no-op
 * and the UI shows a "connect to see friends" state.
 *
 * All mutation happens on the pump thread (callbacks) or the private single-thread [io] executor
 * (the outbound handler calls), and the two exposed [StateFlow]s are the only thing the Compose UI
 * touches — so the screen never blocks on the CM and never races the pump. Every public entry point
 * is guarded and non-throwing: these run from UI callbacks and the CM pump where a crash is
 * unacceptable.
 */
object SteamFriendsStore {

    private const val TAG = "BH_STEAM_FRIENDS"

    /** Steam avatar CDN. The 20-byte avatar SHA-1 (hex) keys the image; blank/zero hash => no avatar. */
    private const val AVATAR_CDN_BASE = "https://avatars.steamstatic.com"

    private val repo get() = SteamRepository.getInstance()

    /** Outbound handler calls (requestFriendInfo / requestMessageHistory / sendChatMessage / persona).
     *  Kept off the main thread AND off the pump — matches how library sync calls SteamApps off-pump. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SteamFriendsIO").apply { isDaemon = true }
    }

    // ── Data model ────────────────────────────────────────────────────────────────

    /** Coarse presence bucket — drives both the grouping/sort order and the status-dot colour. */
    enum class Presence { IN_GAME, ONLINE, AWAY, OFFLINE }

    data class SteamFriend(
        val steamId: Long,
        val personaName: String,
        /** Friend nickname the local user set (overrides [personaName] for display), or null. */
        val nickname: String?,
        val presence: Presence,
        /** One-line status: the game name when in-game, else "Online"/"Away"/"Offline". */
        val statusText: String,
        val gameAppId: Int,
        val gameName: String?,
        /** 40-char lowercase avatar hash, or null when the friend has no avatar loaded. */
        val avatarHash: String?,
    ) {
        val displayName: String
            get() = nickname?.takeIf { it.isNotBlank() }
                ?: personaName.takeIf { it.isNotBlank() }
                ?: "Friend $steamId"

        /** Full-size avatar URL, or null (UI falls back to an initials chip). */
        val avatarUrl: String?
            get() = avatarHash?.let { "$AVATAR_CDN_BASE/${it}_full.jpg" }
    }

    data class ChatMessage(val fromSelf: Boolean, val text: String, val timestampSec: Long)

    /** The currently-open conversation (steamId 0 == none open). */
    data class ChatSession(val steamId: Long, val messages: List<ChatMessage>)

    // ── State ─────────────────────────────────────────────────────────────────────

    /** Presence details per friend (personaName/state/game/avatar). Nickname is injected at publish. */
    private val friendMap = ConcurrentHashMap<Long, SteamFriend>()

    /** Membership: which steamIds are actually mutual friends (relationship == Friend). */
    private val friendIds: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** steamId -> local nickname (from NicknameListCallback). */
    private val nicknames = ConcurrentHashMap<Long, String>()

    /** steamId -> ordered message history for that conversation. */
    private val histories = ConcurrentHashMap<Long, MutableList<ChatMessage>>()

    @Volatile private var activeChatId = 0L

    private val _friends = MutableStateFlow<List<SteamFriend>>(emptyList())
    val friends: StateFlow<List<SteamFriend>> = _friends.asStateFlow()

    private val _chat = MutableStateFlow(ChatSession(0L, emptyList()))
    val chat: StateFlow<ChatSession> = _chat.asStateFlow()

    init {
        // Clear cross-account state on sign-out / session end so a different login never inherits the
        // previous user's friends. Rides the existing repository event bus (no new plumbing).
        try {
            repo.addListener(SteamRepository.SteamEventListener { ev ->
                if (ev == "LoggedOut" || ev == "SessionExpired") reset()
            })
        } catch (t: Throwable) {
            Log.w(TAG, "listener registration failed", t)
        }
    }

    // ── Availability ──────────────────────────────────────────────────────────────

    /** True when the live CM session can serve friends (logged in AND handler bound). */
    fun isAvailable(): Boolean = try {
        repo.isLoggedIn && repo.steamFriends != null
    } catch (t: Throwable) {
        false
    }

    /** Wipe all cached friend/chat state (sign-out / account switch). */
    fun reset() {
        friendMap.clear()
        friendIds.clear()
        nicknames.clear()
        histories.clear()
        activeChatId = 0L
        _friends.value = emptyList()
        _chat.value = ChatSession(0L, emptyList())
    }

    // ── Friends list ──────────────────────────────────────────────────────────────

    /**
     * Seed the list from whatever the handler already cached, then go online + request fresh info so
     * live persona/avatars/game state stream in via [onPersonaState]. Safe to call repeatedly (e.g.
     * every time the screen opens). No-op when the session isn't live.
     */
    fun refresh() {
        io.execute {
            try {
                val sf = repo.steamFriends ?: return@execute
                if (!repo.isLoggedIn) return@execute
                val ids: List<SteamID> = try { sf.friendsList } catch (t: Throwable) { emptyList() }
                for (sid in ids) {
                    if (relationshipOf(sf, sid) != EFriendRelationship.Friend) continue
                    val id = sid.convertToUInt64()
                    friendIds.add(id)
                    friendMap[id] = buildFromHandler(sf, sid)
                }
                publish()
                // Appear online so Steam pushes live friend PersonaStateCallbacks, then pull a fresh
                // snapshot for everyone (fills persona name / avatar / rich game state).
                try { sf.setPersonaState(EPersonaState.Online) } catch (_: Throwable) {}
                if (ids.isNotEmpty()) try { sf.requestFriendInfo(ids) } catch (_: Throwable) {}
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed", t)
            }
        }
    }

    /** FriendsListCallback: full or incremental roster. Populates [friendIds] + placeholder entries. */
    fun onFriendsList(cb: FriendsListCallback) {
        try {
            val sf = repo.steamFriends
            if (!cb.isIncremental) friendIds.clear()
            val newlyKnown = ArrayList<SteamID>()
            for (f in cb.friendList.orEmpty()) {
                val sid = f.steamID ?: continue
                val id = sid.convertToUInt64()
                when (f.relationship) {
                    EFriendRelationship.Friend -> {
                        friendIds.add(id)
                        if (friendMap[id] == null) {
                            friendMap[id] = if (sf != null) buildFromHandler(sf, sid)
                            else placeholder(id)
                        }
                        newlyKnown.add(sid)
                    }
                    EFriendRelationship.None -> { // unfriended / removed
                        friendIds.remove(id)
                        friendMap.remove(id)
                    }
                    else -> { /* pending in/out request — not shown in v1 */ }
                }
            }
            publish()
            if (sf != null && newlyKnown.isNotEmpty()) {
                io.execute { try { sf.requestFriendInfo(newlyKnown) } catch (_: Throwable) {} }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onFriendsList failed", t)
        }
    }

    /** PersonaStateCallback: the live presence update for one friend (name/state/game/avatar). */
    fun onPersonaState(cb: PersonaStateCallback) {
        try {
            val sid = cb.friendId ?: return
            val id = sid.convertToUInt64()
            if (id !in friendIds) {
                // Admit it only if it really is a friend (persona updates also arrive for group
                // members, lobby peers, and self).
                if (relationshipOf(repo.steamFriends, sid) != EFriendRelationship.Friend) return
                friendIds.add(id)
            }
            val prev = friendMap[id]
            val name = cb.name?.takeIf { it.isNotBlank() } ?: prev?.personaName ?: ""
            val avatar = hex(cb.avatarHash) ?: prev?.avatarHash
            val gameAppId = cb.gameAppID
            val gameName = cb.gameName?.takeIf { it.isNotBlank() }
            val (presence, statusText) = classify(cb.state, gameAppId, gameName)
            friendMap[id] = SteamFriend(id, name, null, presence, statusText, gameAppId, gameName, avatar)
            publish()
        } catch (t: Throwable) {
            Log.w(TAG, "onPersonaState failed", t)
        }
    }

    /** NicknameListCallback: the full set of nicknames the local user has assigned to friends. */
    fun onNicknameList(cb: NicknameListCallback) {
        try {
            nicknames.clear()
            for (n in cb.nicknames.orEmpty()) {
                val id = n.steamID?.convertToUInt64() ?: continue
                val nick = n.nickname
                if (!nick.isNullOrBlank()) nicknames[id] = nick
            }
            publish()
        } catch (t: Throwable) {
            Log.w(TAG, "onNicknameList failed", t)
        }
    }

    /** FriendAddedCallback: a friend request was accepted/added — fold them into the roster. */
    fun onFriendAdded(cb: FriendAddedCallback) {
        try {
            if (cb.result != EResult.OK) return
            val sid = cb.steamID ?: return
            val id = sid.convertToUInt64()
            friendIds.add(id)
            if (friendMap[id] == null) {
                friendMap[id] = SteamFriend(
                    id, cb.personaName ?: "", null, Presence.OFFLINE, "Offline", 0, null, null,
                )
            }
            publish()
            val sf = repo.steamFriends ?: return
            io.execute { try { sf.requestFriendInfo(sid) } catch (_: Throwable) {} }
        } catch (t: Throwable) {
            Log.w(TAG, "onFriendAdded failed", t)
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────────

    /**
     * Open the conversation with [steamId]: publish whatever history we already hold immediately,
     * then request the recent server history (arrives via [onFriendMsgHistory]). Returns the shared
     * [chat] flow the screen collects.
     */
    fun openChat(steamId: Long): StateFlow<ChatSession> {
        activeChatId = steamId
        val existing = histories[steamId]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        _chat.value = ChatSession(steamId, existing)
        io.execute {
            try { repo.steamFriends?.requestMessageHistory(SteamID(steamId)) } catch (_: Throwable) {}
        }
        return chat
    }

    /** Leave the current conversation (stops incoming messages from updating the [chat] flow). */
    fun closeChat() {
        activeChatId = 0L
        _chat.value = ChatSession(0L, emptyList())
    }

    /**
     * Send [text] to [steamId] and optimistically append it (the sender's own device receives no
     * echo, so there is no duplicate with [onFriendMsgEcho], which is for the user's OTHER devices).
     */
    fun sendMessage(steamId: Long, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        appendMessage(steamId, ChatMessage(true, body, nowSec()))
        io.execute {
            try {
                repo.steamFriends?.sendChatMessage(SteamID(steamId), EChatEntryType.ChatMsg, body)
            } catch (t: Throwable) {
                Log.w(TAG, "sendMessage failed", t)
            }
        }
    }

    /** FriendMsgCallback: an incoming message from a friend. */
    fun onFriendMsg(cb: FriendMsgCallback) {
        try {
            if (cb.entryType != EChatEntryType.ChatMsg) return // ignore Typing / etc.
            val id = cb.sender?.convertToUInt64() ?: return
            val body = cb.message ?: return
            if (body.isEmpty()) return
            appendMessage(id, ChatMessage(false, body, nowSec()))
        } catch (t: Throwable) {
            Log.w(TAG, "onFriendMsg failed", t)
        }
    }

    /** FriendMsgEchoCallback: a message the user sent from ANOTHER device (multi-device echo). */
    fun onFriendMsgEcho(cb: FriendMsgEchoCallback) {
        try {
            if (cb.entryType != EChatEntryType.ChatMsg) return
            val id = cb.recipient?.convertToUInt64() ?: return
            val body = cb.message ?: return
            if (body.isEmpty()) return
            val ts = cb.rTime32ServerTimestamp.toLong().let { if (it > 0) it else nowSec() }
            appendMessage(id, ChatMessage(true, body, ts))
        } catch (t: Throwable) {
            Log.w(TAG, "onFriendMsgEcho failed", t)
        }
    }

    /** FriendMsgHistoryCallback: the recent server-side history for one conversation. */
    fun onFriendMsgHistory(cb: FriendMsgHistoryCallback) {
        try {
            val id = cb.steamID?.convertToUInt64() ?: return
            val self = repo.steamId64
            val server = cb.messages.orEmpty().map { m ->
                val sender = m.steamID?.convertToUInt64() ?: 0L
                ChatMessage(
                    fromSelf = sender == self,
                    text = m.message ?: "",
                    timestampSec = (m.timestamp?.time ?: 0L) / 1000L,
                )
            }.sortedBy { it.timestampSec }

            val list = histories.getOrPut(id) { mutableListOf() }
            synchronized(list) {
                // Keep any just-sent optimistic message newer than the newest server row so a send
                // made before history lands isn't dropped when we adopt the authoritative list.
                val maxServerTs = server.maxOfOrNull { it.timestampSec } ?: 0L
                val keepLocal = list.filter { it.fromSelf && it.timestampSec > maxServerTs }
                list.clear()
                list.addAll(server)
                list.addAll(keepLocal)
            }
            if (id == activeChatId) _chat.value = ChatSession(id, synchronized(list) { list.toList() })
        } catch (t: Throwable) {
            Log.w(TAG, "onFriendMsgHistory failed", t)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun appendMessage(id: Long, msg: ChatMessage) {
        val list = histories.getOrPut(id) { mutableListOf() }
        synchronized(list) { list.add(msg) }
        if (id == activeChatId) _chat.value = ChatSession(id, synchronized(list) { list.toList() })
    }

    /** Rebuild + push the sorted, nickname-injected friends list. */
    private fun publish() {
        val list = friendIds.mapNotNull { id ->
            friendMap[id]?.copy(nickname = nicknames[id])
        }.sortedWith(compareBy({ it.presence.ordinal }, { it.displayName.lowercase() }))
        _friends.value = list
    }

    private fun buildFromHandler(sf: SteamFriends, sid: SteamID): SteamFriend {
        val id = sid.convertToUInt64()
        val name = try { sf.getFriendPersonaName(sid) } catch (_: Throwable) { null } ?: ""
        val state = try { sf.getFriendPersonaState(sid) } catch (_: Throwable) { null } ?: EPersonaState.Offline
        val gameAppId = try { sf.getFriendGameAppId(sid) } catch (_: Throwable) { 0 }
        val gameName = try { sf.getFriendGamePlayedName(sid) } catch (_: Throwable) { null }
            ?.takeIf { it.isNotBlank() }
        val avatar = try { hex(sf.getFriendAvatar(sid)) } catch (_: Throwable) { null }
        val (presence, statusText) = classify(state, gameAppId, gameName)
        return SteamFriend(id, name, null, presence, statusText, gameAppId, gameName, avatar)
    }

    private fun placeholder(id: Long) =
        SteamFriend(id, "", null, Presence.OFFLINE, "Offline", 0, null, null)

    private fun relationshipOf(sf: SteamFriends?, sid: SteamID): EFriendRelationship? =
        try { sf?.getFriendRelationship(sid) } catch (_: Throwable) { null }

    /** Map (state, game) into the coarse [Presence] bucket + a one-line status label. */
    private fun classify(
        state: EPersonaState?,
        gameAppId: Int,
        gameName: String?,
    ): Pair<Presence, String> {
        val s = state ?: EPersonaState.Offline
        if (gameAppId != 0 && s != EPersonaState.Offline) {
            return Presence.IN_GAME to (gameName?.takeIf { it.isNotBlank() } ?: "In game")
        }
        return when (s) {
            EPersonaState.Online, EPersonaState.LookingToPlay, EPersonaState.LookingToTrade ->
                Presence.ONLINE to "Online"
            EPersonaState.Busy -> Presence.AWAY to "Busy"
            EPersonaState.Away -> Presence.AWAY to "Away"
            EPersonaState.Snooze -> Presence.AWAY to "Snooze"
            else -> Presence.OFFLINE to "Offline"
        }
    }

    private fun hex(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        if (bytes.all { it.toInt() == 0 }) return null // unset avatar -> initials fallback
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L
}
