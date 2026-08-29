package com.winlator.star.store

import android.util.Log
import `in`.dragonbra.javasteam.enums.EChatEntryType
import `in`.dragonbra.javasteam.enums.EClientPersonaStateFlag
import `in`.dragonbra.javasteam.enums.EFriendRelationship
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient
import `in`.dragonbra.javasteam.rpc.service.Authentication
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
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
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** Pending friend-request invites: incoming (they requested us) and outgoing (we requested them). */
    private val incomingIds: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val outgoingIds: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val _incomingRequests = MutableStateFlow<List<SteamFriend>>(emptyList())
    val incomingRequests: StateFlow<List<SteamFriend>> = _incomingRequests.asStateFlow()
    private val _outgoingRequests = MutableStateFlow<List<SteamFriend>>(emptyList())
    val outgoingRequests: StateFlow<List<SteamFriend>> = _outgoingRequests.asStateFlow()

    /** steamId -> local nickname (from NicknameListCallback). */
    private val nicknames = ConcurrentHashMap<Long, String>()

    /** steamId -> ordered message history for that conversation. */
    private val histories = ConcurrentHashMap<Long, MutableList<ChatMessage>>()

    @Volatile private var activeChatId = 0L

    private val _friends = MutableStateFlow<List<SteamFriend>>(emptyList())
    val friends: StateFlow<List<SteamFriend>> = _friends.asStateFlow()

    /** The local user's own persona (name + avatar) — used to render our own chat bubbles. */
    private val _self = MutableStateFlow<SteamFriend?>(null)
    val self: StateFlow<SteamFriend?> = _self.asStateFlow()

    // One-shot user feedback for the Add-a-friend flow (snackbar text); UI clears it after showing.
    private val _addFeedback = MutableStateFlow<String?>(null)
    val addFeedback: StateFlow<String?> = _addFeedback.asStateFlow()
    fun clearAddFeedback() { _addFeedback.value = null }

    private val _chat = MutableStateFlow(ChatSession(0L, emptyList()))
    val chat: StateFlow<ChatSession> = _chat.asStateFlow()

    /** steamId -> count of unread incoming messages; cleared when that friend's chat is opened. */
    private val _unread = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val unread: StateFlow<Map<Long, Int>> = _unread.asStateFlow()

    /** steamId -> epoch-ms at which the friend's "typing" state expires (typing while now < value). */
    private val _typing = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val typing: StateFlow<Map<Long, Long>> = _typing.asStateFlow()
    private val lastTypingSent = ConcurrentHashMap<Long, Long>()

    // Persona fields to (re)request so status/game/rich-presence repopulate immediately on refresh.
    // The default requestFriendInfo doesn't force Steam to re-send status, so presence sat empty
    // ("everyone offline") after a reconnect / friends-screen re-open until it slowly trickled in.
    private val PERSONA_INFO_FLAGS = EClientPersonaStateFlag.code(
        EnumSet.of(
            EClientPersonaStateFlag.Status,
            EClientPersonaStateFlag.PlayerName,
            EClientPersonaStateFlag.Presence,
            EClientPersonaStateFlag.GameExtraInfo,
            EClientPersonaStateFlag.GameDataBlob,
            EClientPersonaStateFlag.RichPresence,
        ),
    )

    // True once we've announced online + fetched all statuses for the current live session. Re-doing
    // that on every screen open churned presence to Offline; a fresh session re-arms it (listener below).
    @Volatile private var syncedThisSession = false

    /** Presence sections the user has collapsed/hidden — persisted so it's remembered across opens. */
    private val _collapsedSections = MutableStateFlow<Set<Presence>>(emptySet())
    val collapsedSections: StateFlow<Set<Presence>> = _collapsedSections.asStateFlow()

    @Volatile private var appContext: android.content.Context? = null
    @Volatile private var loadedForAccount: Long = 0L

    // Short-lived WEB-audience access token for the community image host (see sendImage). Steam issues
    // these ~1h; we cache one per account and re-mint after the TTL. NOT the raw refresh token.
    private const val WEB_TOKEN_TTL_MS = 50 * 60 * 1000L
    @Volatile private var cachedWebToken: String? = null
    @Volatile private var cachedWebTokenAt: Long = 0L
    @Volatile private var cachedWebTokenAccount: Long = 0L

    // De-dupes a double-delivered image pick (signature -> last-send epoch millis). See sendImage.
    private val imageSendGuard = HashMap<String, Long>()

    private const val STEAMID64_BASE = 76561197960265728L

    init {
        // Clear cross-account state on sign-out / session end so a different login never inherits the
        // previous user's friends. Rides the existing repository event bus (no new plumbing).
        try {
            repo.addListener(SteamRepository.SteamEventListener { ev ->
                // Only a full sign-out / account switch wipes cached chat; a transient SessionExpired
                // (e.g. app backgrounded) keeps the conversation so it's still there on foreground.
                if (ev == "LoggedOut") reset()
                // Any session drop/change re-arms the one-time online sync so presence re-fetches once the
                // session is live again. A plain screen re-open must NOT re-sync (that churned presence).
                if (ev == "LoggedOut" || ev == "SessionExpired" ||
                    ev.startsWith("SteamStatus:CONNECTING") || ev.startsWith("SteamStatus:OFFLINE")
                ) {
                    syncedThisSession = false
                }
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
        // Keep the friend roster + presence across transient logoffs/reconnects so re-entering the
        // friends screen (or a brief session bump) never wipes everyone to Offline — that was the bug.
        // A different account's full FriendsListCallback rebuilds friendIds, so a stale roster is never
        // shown; live changes still arrive via onPersonaState. Only session/chat state is cleared here.
        histories.clear()       // privacy: don't let a different account read cached chat from memory
        loadedForAccount = 0L
        cachedWebToken = null    // a different account must not reuse the previous user's upload token
        cachedWebTokenAt = 0L
        cachedWebTokenAccount = 0L
        syncedThisSession = false
        activeChatId = 0L
        _self.value = null
        _chat.value = ChatSession(0L, emptyList())
        _unread.value = emptyMap()
        _typing.value = emptyMap()
        lastTypingSent.clear()
    }

    // ── Persistence (chat history survives backgrounding / process death) ────────────

    /** Wire an application context so chat history can be saved to disk. Idempotent. */
    fun init(context: android.content.Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            loadCollapsedSections()
        }
    }

    /** Persist a section's collapsed/hidden state (remembered across app opens). */
    fun setSectionCollapsed(p: Presence, collapsed: Boolean) {
        val cur = _collapsedSections.value
        val next = if (collapsed) cur + p else cur - p
        if (next == cur) return
        _collapsedSections.value = next
        try {
            appContext?.getSharedPreferences("steam_friends_ui", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putStringSet("collapsed_sections", next.map { it.name }.toSet())?.apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadCollapsedSections() {
        val saved = try {
            appContext?.getSharedPreferences("steam_friends_ui", android.content.Context.MODE_PRIVATE)
                ?.getStringSet("collapsed_sections", null)
        } catch (_: Throwable) { null } ?: return
        _collapsedSections.value = saved.mapNotNull { runCatching { Presence.valueOf(it) }.getOrNull() }.toSet()
    }

    /** Load this account's saved conversations into memory (once per account). */
    private fun loadHistoriesFor(selfId: Long) {
        if (selfId == 0L || loadedForAccount == selfId) return
        loadedForAccount = selfId
        val json = try {
            appContext?.getSharedPreferences("steam_chat_history", android.content.Context.MODE_PRIVATE)
                ?.getString("h_$selfId", null)
        } catch (_: Throwable) { null } ?: return
        try {
            val convos = org.json.JSONObject(json).optJSONObject("c") ?: return
            val keys = convos.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toLongOrNull() ?: continue
                val arr = convos.optJSONArray(key) ?: continue
                val list = ArrayList<ChatMessage>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(ChatMessage(o.optBoolean("s"), o.optString("t"), o.optLong("ts")))
                }
                if (list.isNotEmpty()) histories[id] = list
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadHistories failed", t)
        }
    }

    /** Write all conversations for the current account to disk (call on the io executor). */
    private fun persistHistories() {
        val ctx = appContext ?: return
        val selfId = try { repo.steamId64 } catch (_: Throwable) { 0L }
        if (selfId == 0L) return
        try {
            val convos = org.json.JSONObject()
            for ((id, list) in histories) {
                val snapshot = synchronized(list) { list.toList() }
                if (snapshot.isEmpty()) continue
                val trimmed = if (snapshot.size > 200) snapshot.subList(snapshot.size - 200, snapshot.size) else snapshot
                val arr = org.json.JSONArray()
                for (m in trimmed) {
                    arr.put(org.json.JSONObject().put("s", m.fromSelf).put("t", m.text).put("ts", m.timestampSec))
                }
                convos.put(id.toString(), arr)
            }
            val root = org.json.JSONObject().put("c", convos)
            ctx.getSharedPreferences("steam_chat_history", android.content.Context.MODE_PRIVATE)
                .edit().putString("h_$selfId", root.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "persistHistories failed", t)
        }
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
                    val id = sid.convertToUInt64()
                    // Bucket by relationship: friends vs incoming/outgoing pending requests. Only build a
                    // persona we don't already have (existing presence is owned by onPersonaState).
                    when (relationshipOf(sf, sid)) {
                        EFriendRelationship.Friend -> {
                            friendIds.add(id)
                            if (friendMap[id] == null) friendMap[id] = buildFromHandler(sf, sid)
                        }
                        EFriendRelationship.RequestRecipient -> {
                            incomingIds.add(id)
                            if (friendMap[id] == null) friendMap[id] = buildFromHandler(sf, sid)
                        }
                        EFriendRelationship.RequestInitiator -> {
                            outgoingIds.add(id)
                            if (friendMap[id] == null) friendMap[id] = buildFromHandler(sf, sid)
                        }
                        else -> {}
                    }
                }
                publish()
                // Our own persona (name + avatar) for our own chat bubbles.
                val selfId = try { repo.steamId64 } catch (_: Throwable) { 0L }
                loadHistoriesFor(selfId) // restore this account's saved conversations
                if (selfId != 0L) {
                    val selfName = try { sf.getPersonaName() } catch (_: Throwable) { null }
                    val selfHash = try { hex(sf.getFriendAvatar(SteamID(selfId))) } catch (_: Throwable) { null }
                    _self.value = SteamFriend(
                        selfId, selfName?.takeIf { it.isNotBlank() } ?: "You", null,
                        Presence.ONLINE, "", 0, null, selfHash,
                    )
                }
                // Appear online so Steam pushes live friend PersonaStateCallbacks, then pull a fresh
                // snapshot for everyone (fills persona name / avatar / rich game state).
                // Announce online + fetch everyone's status ONCE per live session. Doing this on every
                // screen open churned presence (it resets the persona cache + triggers an Offline burst);
                // plain navigation now just shows the retained roster. A fresh session re-arms it.
                if (!syncedThisSession) {
                    syncedThisSession = true
                    try { sf.setPersonaState(EPersonaState.Online) } catch (_: Throwable) {}
                    if (ids.isNotEmpty()) try { sf.requestFriendInfo(ids, PERSONA_INFO_FLAGS) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed", t)
            }
        }
    }

    /** Send a friend request. [input] is a SteamID64 (numeric) or an account name / vanity. */
    fun addFriend(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        io.execute {
            try {
                val sf = repo.steamFriends ?: run { _addFeedback.value = "Not connected to Steam"; return@execute }
                val id64 = q.toLongOrNull()
                if (id64 != null && id64 > STEAMID64_BASE) {
                    sf.addFriend(SteamID(id64)); _addFeedback.value = "Friend request sent"
                } else {
                    sf.addFriend(q); _addFeedback.value = "Looking up “$q”…"
                }
            } catch (t: Throwable) {
                Log.w(TAG, "addFriend failed", t)
                _addFeedback.value = "Couldn't send request"
            }
        }
    }

    /** Add/invite a friend by their numeric Steam Friend Code (the account-ID number). */
    fun addByFriendCode(code: String) {
        val accountId = code.filter { it.isDigit() }.toLongOrNull()
        if (accountId == null || accountId <= 0L) { _addFeedback.value = "Enter a valid Friend Code"; return }
        addFriendById(STEAMID64_BASE + accountId)
    }

    /** Send a friend request straight to a resolved SteamID64 (friend code / user-search result). */
    fun addFriendById(id64: Long) {
        if (id64 <= STEAMID64_BASE) return
        io.execute {
            try {
                repo.steamFriends?.addFriend(SteamID(id64)) ?: run { _addFeedback.value = "Not connected to Steam"; return@execute }
                _addFeedback.value = "Friend request sent"
            } catch (t: Throwable) {
                Log.w(TAG, "addFriendById failed", t)
                _addFeedback.value = "Couldn't send request"
            }
        }
    }

    /** This account's Steam Friend Code (the SteamID account number), or null if unknown. */
    fun selfFriendCode(): String? {
        val id = try { repo.steamId64 } catch (_: Throwable) { 0L }
        return if (id > STEAMID64_BASE) (id - STEAMID64_BASE).toString() else null
    }

    /** Internal accessors for the community user-search helper (SteamUserSearch). */
    internal fun selfSteamId64(): Long = try { repo.steamId64 } catch (_: Throwable) { 0L }
    internal fun webAuthToken(): String? = mintWebToken()

    /** Accept an incoming friend request (adds them as a friend). */
    fun acceptRequest(id: Long) {
        incomingIds.remove(id) // optimistic; the FriendsListCallback confirms + moves to Friends
        publish()
        io.execute { try { repo.steamFriends?.addFriend(SteamID(id)) } catch (t: Throwable) { Log.w(TAG, "acceptRequest failed", t) } }
    }

    /** Decline an incoming friend request. */
    fun declineRequest(id: Long) {
        incomingIds.remove(id)
        publish()
        io.execute { try { repo.steamFriends?.ignoreFriend(SteamID(id)) } catch (t: Throwable) { Log.w(TAG, "declineRequest failed", t) } }
    }

    /** Cancel an outgoing (pending) friend request we sent. */
    fun cancelRequest(id: Long) {
        outgoingIds.remove(id)
        publish()
        io.execute { try { repo.steamFriends?.removeFriend(SteamID(id)) } catch (t: Throwable) { Log.w(TAG, "cancelRequest failed", t) } }
    }

    /** Remove an existing friend. */
    fun removeFriend(id: Long) {
        friendIds.remove(id); friendMap.remove(id)
        publish()
        io.execute { try { repo.steamFriends?.removeFriend(SteamID(id)) } catch (t: Throwable) { Log.w(TAG, "removeFriend failed", t) } }
    }

    /** FriendsListCallback: full or incremental roster. Populates [friendIds] + placeholder entries. */
    fun onFriendsList(cb: FriendsListCallback) {
        try {
            val sf = repo.steamFriends
            if (!cb.isIncremental) { friendIds.clear(); incomingIds.clear(); outgoingIds.clear() }
            val newlyKnown = ArrayList<SteamID>()
            for (f in cb.friendList.orEmpty()) {
                val sid = f.steamID ?: continue
                val id = sid.convertToUInt64()
                when (f.relationship) {
                    EFriendRelationship.Friend -> {
                        friendIds.add(id); incomingIds.remove(id); outgoingIds.remove(id)
                        if (friendMap[id] == null) {
                            friendMap[id] = if (sf != null) buildFromHandler(sf, sid)
                            else placeholder(id)
                        }
                        newlyKnown.add(sid)
                    }
                    EFriendRelationship.RequestRecipient -> { // they sent US a friend request
                        incomingIds.add(id); outgoingIds.remove(id); friendIds.remove(id)
                        if (friendMap[id] == null) friendMap[id] = if (sf != null) buildFromHandler(sf, sid) else placeholder(id)
                        newlyKnown.add(sid)
                    }
                    EFriendRelationship.RequestInitiator -> { // WE requested them (pending)
                        outgoingIds.add(id); incomingIds.remove(id); friendIds.remove(id)
                        if (friendMap[id] == null) friendMap[id] = if (sf != null) buildFromHandler(sf, sid) else placeholder(id)
                        newlyKnown.add(sid)
                    }
                    else -> { // None / Blocked / Ignored — drop from every bucket
                        friendIds.remove(id); incomingIds.remove(id); outgoingIds.remove(id)
                        friendMap.remove(id)
                    }
                }
            }
            publish()
            if (sf != null && newlyKnown.isNotEmpty()) {
                io.execute { try { sf.requestFriendInfo(newlyKnown, PERSONA_INFO_FLAGS) } catch (_: Throwable) {} }
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
            if (id !in friendIds && id !in incomingIds && id !in outgoingIds) {
                // Admit it only if it really is a friend (persona updates also arrive for group
                // members, lobby peers, and self). Request users are already in their own buckets.
                if (relationshipOf(repo.steamFriends, sid) != EFriendRelationship.Friend) return
                friendIds.add(id)
            }
            val prev = friendMap[id]
            val sf = repo.steamFriends
            // This javasteam fork does not expose name/state/gameAppID on the callback in a way
            // Kotlin can reference (neither as property nor getter). Read the friend's fresh persona
            // from the handler cache instead — the same reliable accessors buildFromHandler() uses;
            // the handler is already updated by the time this callback fires.
            val name = (try { sf?.getFriendPersonaName(sid) } catch (_: Throwable) { null })
                ?.takeIf { it.isNotBlank() } ?: prev?.personaName ?: ""
            val state = (try { sf?.getFriendPersonaState(sid) } catch (_: Throwable) { null })
                ?: EPersonaState.Offline
            val gameAppId = try { sf?.getFriendGameAppId(sid) ?: 0 } catch (_: Throwable) { 0 }
            val avatar = hex(cb.avatarHash) ?: prev?.avatarHash
            val gameName = cb.gameName?.takeIf { it.isNotBlank() }
            val (presence, statusText) = classify(state, gameAppId, gameName)
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
            io.execute { try { sf.requestFriendInfo(sid, PERSONA_INFO_FLAGS) } catch (_: Throwable) {} }
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
        clearUnread(steamId) // opening the chat marks it read
        loadHistoriesFor(try { repo.steamId64 } catch (_: Throwable) { 0L }) // restore saved history first
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

    /**
     * Upload [bytes] to Steam's community image host and, on success, send the resulting image URL to
     * [steamId] as a normal chat message — the receive side renders that URL inline. A placeholder
     * "sending" bubble shows while the (blocking) upload runs on the [io] executor; it is removed on
     * success (the real URL message replaces it via the normal send path) or turned into a short
     * failure note. Non-throwing; a failure just logs. [fileName] is best-effort metadata for Steam.
     */
    fun sendImage(steamId: Long, bytes: ByteArray, fileName: String) {
        if (bytes.isEmpty()) return
        // Guard against a double-delivered pick (the file-picker result arriving twice would otherwise
        // upload + send the same image twice). Ignore an identical image to the same friend within a
        // short window.
        val sig = "$steamId:${bytes.size}:$fileName"
        val now = System.currentTimeMillis()
        synchronized(imageSendGuard) {
            val last = imageSendGuard[sig] ?: 0L
            if (now - last < 3000L) return
            imageSendGuard[sig] = now
        }
        val placeholder = ChatMessage(true, "📷 Sending image…", nowSec())
        appendMessage(steamId, placeholder)
        io.execute {
            try {
                val token = mintWebToken()
                if (token == null) {
                    replaceMessageText(steamId, placeholder, "⚠️ Couldn't send image (not signed in)")
                    return@execute
                }
                val selfId = try { repo.steamId64 } catch (_: Throwable) { 0L }
                val url = SteamChatImageUploader.upload(token, selfId, steamId, bytes, fileName)
                if (url != null) {
                    // Drop the placeholder, then send the URL through the normal path (optimistic append
                    // + sendChatMessage) so it renders as an image bubble on both ends.
                    removeMessage(steamId, placeholder)
                    sendMessage(steamId, url)
                } else {
                    replaceMessageText(steamId, placeholder, "⚠️ Couldn't send image")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendImage failed", t)
                replaceMessageText(steamId, placeholder, "⚠️ Couldn't send image")
            }
        }
    }

    /**
     * Mint (or return a cached) WEB-audience access token for the community image host. Steam's
     * `steamLoginSecure` cookie needs a web-audience JWT, not the raw refresh token, so we exchange the
     * refresh token via the Authentication unified RPC (the same call the desktop client makes before a
     * chat upload). Cached ~[WEB_TOKEN_TTL_MS] per account. BLOCKING — call on the [io] executor only.
     * Returns null when not logged in / no token / the RPC fails; never throws.
     */
    private fun mintWebToken(): String? {
        val selfId = try { repo.steamId64 } catch (_: Throwable) { 0L }
        if (selfId == 0L) return null
        val now = System.currentTimeMillis()
        val cached = cachedWebToken
        if (cached != null && cachedWebTokenAccount == selfId && now - cachedWebTokenAt < WEB_TOKEN_TTL_MS) {
            return cached
        }
        return try {
            if (!repo.ensureLoggedIn(8_000L)) return null
            val client = repo.steamClient ?: return null
            val refresh = try { repo.refreshToken } catch (_: Throwable) { null }
            if (refresh.isNullOrEmpty()) return null
            val unified = client.getHandler(SteamUnifiedMessages::class.java) ?: return null
            val auth: Authentication = unified.createService(Authentication::class.java)
            val req = SteammessagesAuthSteamclient.CAuthentication_AccessToken_GenerateForApp_Request
                .newBuilder()
                .setRefreshToken(refresh)
                .setSteamid(selfId)
                .build()
            val resp = auth.generateAccessTokenForApp(req)
                .toFuture().get(20L, TimeUnit.SECONDS) ?: return null
            val token = resp.body?.accessToken?.takeIf { it.isNotBlank() } ?: return null
            cachedWebToken = token
            cachedWebTokenAt = now
            cachedWebTokenAccount = selfId
            token
        } catch (t: Throwable) {
            Log.w(TAG, "mintWebToken failed", t)
            null
        }
    }

    /** FriendMsgCallback: an incoming chat message OR a typing notification from a friend. */
    fun onFriendMsg(cb: FriendMsgCallback) {
        try {
            val id = cb.sender?.convertToUInt64() ?: return
            when (cb.entryType) {
                EChatEntryType.Typing -> markTyping(id)
                EChatEntryType.ChatMsg -> {
                    val body = cb.message ?: return
                    if (body.isEmpty()) return
                    clearTyping(id) // a real message ends the "typing" state
                    appendMessage(id, ChatMessage(false, body, nowSec()))
                    if (id != activeChatId) bumpUnread(id) // unread unless its chat is open
                }
                else -> {} // ignore Entered / LeftConversation / etc.
            }
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
                val serverSelfTexts = server.filter { it.fromSelf }.map { it.text }.toHashSet()
                // Keep an optimistic self-message only if it's newer than everything on the server AND
                // the server doesn't already have that text (stops the just-sent line from doubling).
                val keepLocal = list.filter {
                    it.fromSelf && it.timestampSec > maxServerTs && it.text !in serverSelfTexts
                }
                list.clear()
                list.addAll(server)
                list.addAll(keepLocal)
            }
            if (id == activeChatId) _chat.value = ChatSession(id, synchronized(list) { list.toList() })
            io.execute { persistHistories() }
        } catch (t: Throwable) {
            Log.w(TAG, "onFriendMsgHistory failed", t)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun appendMessage(id: Long, msg: ChatMessage) {
        val list = histories.getOrPut(id) { mutableListOf() }
        synchronized(list) { list.add(msg) }
        if (id == activeChatId) _chat.value = ChatSession(id, synchronized(list) { list.toList() })
        io.execute { persistHistories() }
    }

    /** Remove a previously-appended (optimistic) message by value — used by the image-send flow. */
    private fun removeMessage(id: Long, msg: ChatMessage) {
        val list = histories[id] ?: return
        synchronized(list) { list.remove(msg) }
        if (id == activeChatId) _chat.value = ChatSession(id, synchronized(list) { list.toList() })
        io.execute { persistHistories() }
    }

    /** Rewrite an existing (optimistic) message's text in place — used for the image-send status note. */
    private fun replaceMessageText(id: Long, old: ChatMessage, newText: String) {
        val list = histories[id] ?: return
        synchronized(list) {
            val i = list.indexOf(old)
            if (i >= 0) list[i] = old.copy(text = newText)
        }
        if (id == activeChatId) _chat.value = ChatSession(id, synchronized(list) { list.toList() })
        io.execute { persistHistories() }
    }

    private fun bumpUnread(id: Long) {
        _unread.value = _unread.value.toMutableMap().apply { this[id] = (this[id] ?: 0) + 1 }
    }

    private fun clearUnread(id: Long) {
        if (_unread.value.containsKey(id)) {
            _unread.value = _unread.value.toMutableMap().apply { remove(id) }
        }
    }

    private fun markTyping(id: Long) {
        _typing.value = _typing.value.toMutableMap().apply { this[id] = System.currentTimeMillis() + 10_000L }
    }

    private fun clearTyping(id: Long) {
        if (_typing.value.containsKey(id)) {
            _typing.value = _typing.value.toMutableMap().apply { remove(id) }
        }
    }

    /** Send a throttled "typing" notification to [steamId] (at most once per ~4s while the user types). */
    fun sendTyping(steamId: Long) {
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[steamId] ?: 0L) < 4000L) return
        lastTypingSent[steamId] = now
        io.execute {
            try {
                repo.steamFriends?.sendChatMessage(SteamID(steamId), EChatEntryType.Typing, "")
            } catch (_: Throwable) {
            }
        }
    }

    /** Rebuild + push the sorted, nickname-injected friends list. */
    private fun publish() {
        val list = friendIds.mapNotNull { id ->
            friendMap[id]?.copy(nickname = nicknames[id])
        }.sortedWith(compareBy({ it.presence.ordinal }, { it.displayName.lowercase() }))
        _friends.value = list
        _incomingRequests.value = incomingIds.mapNotNull { friendMap[it]?.copy(nickname = nicknames[it]) }
            .sortedBy { it.displayName.lowercase() }
        _outgoingRequests.value = outgoingIds.mapNotNull { friendMap[it]?.copy(nickname = nicknames[it]) }
            .sortedBy { it.displayName.lowercase() }
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
