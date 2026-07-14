package com.winlator.star.net

import android.annotation.SuppressLint
import android.content.Context
import com.winlator.star.communityconfigs.AccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Session-scoped source of truth for the in-game chat, mirroring [LanSessionState]: a process-wide
 * singleton exposing StateFlows every chat surface collects (the floating [LanChatOverlay] in-game and
 * pre-game, plus the "Chat" button + unread badge in [LanMultiplayerDialog]).
 *
 * It AUTO-CONNECTS off [LanSessionState]: the moment a room becomes active (Hosting/Joined) it opens a
 * [LanChatClient] to that code with the matching role and the signed-in username (if any); when the room
 * ends (Idle) it closes the socket and resets. Because the socket lives here — not in any composable —
 * chat runs in the BACKGROUND: unread accrues while no window is open, and the conversation survives
 * navigating away to launch the game (or the overlay tunnel dropping — the chat DO is independent of it).
 *
 * The floating window's three-state visibility (HIDDEN / BUBBLE / EXPANDED) is owned here too, so it is
 * NOT tied to the LAN dialog's lifecycle: dismissing the dialog, minimizing, or closing the window only
 * hides UI — they never leave the room or lose messages. Actually leaving = Stop hosting / Leave (which
 * moves [LanSessionState] to Idle and tears the socket down here).
 */
object LanChat : LanChatClient.Listener {

    /** One rendered chat line. [mine] == sent by this device (server echoes it back with our own role). */
    data class ChatMsg(val from: String, val name: String, val text: String, val ts: Long, val mine: Boolean)

    /** Floating-window visibility. HIDDEN = nothing on screen (chat still runs); BUBBLE = collapsed dot. */
    enum class WindowMode { HIDDEN, BUBBLE, EXPANDED }

    private const val MAX_MESSAGES = 200

    // ── Public state ────────────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _peerPresent = MutableStateFlow(false)
    val peerPresent: StateFlow<Boolean> = _peerPresent.asStateFlow()

    /** Transient "<name> joined / left" line from the last presence event, or null. */
    private val _presenceLine = MutableStateFlow<String?>(null)
    val presenceLine: StateFlow<String?> = _presenceLine.asStateFlow()

    private val _windowMode = MutableStateFlow(WindowMode.HIDDEN)
    val windowMode: StateFlow<WindowMode> = _windowMode.asStateFlow()

    /** True while the PEER is typing (auto-clears after a few seconds of silence). */
    private val _peerTyping = MutableStateFlow(false)
    val peerTyping: StateFlow<Boolean> = _peerTyping.asStateFlow()

    // ── Internals ───────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    // App context is fine to hold for a process-lifetime singleton; suppress the leak lint.
    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

    private var client: LanChatClient? = null
    private var connectedCode: String? = null
    private var myRole: String = "host"
    private var typingClearJob: Job? = null
    private var lastTypingSent = 0L

    /**
     * Idempotently begin observing [LanSessionState]. Called from every place the overlay is hosted
     * (the in-game dialog host + the main app shell); the first call wins and starts the single collector.
     */
    fun ensureStarted(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
            appContext = context.applicationContext
        }
        scope.launch {
            LanSessionState.session.collect { onSession(it) }
        }
    }

    private fun onSession(s: LanSession) {
        when (s) {
            is LanSession.Hosting -> connect(s.code, "host")
            is LanSession.Joined -> connect(s.code, "guest")
            LanSession.Idle, LanSession.Creating -> disconnect()
        }
    }

    private fun connect(code: String, role: String) {
        if (connectedCode == code && client != null) return   // already on this room
        disconnect()
        connectedCode = code
        myRole = role
        val name = appContext?.let { AccountManager.current(it)?.username }
        client = LanChatClient(code, role, name, this).also { it.connect() }
    }

    private fun disconnect() {
        client?.close()
        client = null
        connectedCode = null
        _connected.value = false
        _peerPresent.value = false
        _presenceLine.value = null
        _peerTyping.value = false
        _messages.value = emptyList()
        _unread.value = 0
        _windowMode.value = WindowMode.HIDDEN
    }

    // ── Window controls (main thread, from the UI) ────────────────────────────────────
    /** Chat button: open when hidden (and clear unread), close when showing. Never leaves the room. */
    fun toggleWindow() {
        if (_windowMode.value == WindowMode.HIDDEN) {
            _windowMode.value = WindowMode.EXPANDED
            _unread.value = 0
        } else {
            _windowMode.value = WindowMode.HIDDEN
        }
    }

    /** Bubble tapped / restored: show the full window and clear the unread badge. */
    fun expand() {
        _windowMode.value = WindowMode.EXPANDED
        _unread.value = 0
    }

    /** `_` minimize: collapse to the draggable bubble (still connected, unread keeps accruing). */
    fun minimize() { _windowMode.value = WindowMode.BUBBLE }

    /** `X` close: hide window + bubble entirely (chat keeps running in the background). */
    fun hide() { _windowMode.value = WindowMode.HIDDEN }

    /** Send a line to the room. No-op when not connected. */
    fun sendMessage(text: String) { client?.send(text) }

    /** Called on each keystroke; throttled to one "typing" ping every 2.5s so the peer sees a dots line. */
    fun notifyTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent >= 2500) {
            lastTypingSent = now
            client?.sendTyping()
        }
    }

    // ── LanChatClient.Listener (OkHttp socket thread) ─────────────────────────────────
    override fun onConnected() { _connected.value = true }

    override fun onDisconnected() { _connected.value = false }

    override fun onHistory(messages: List<LanChatClient.Incoming>) {
        // Replay replaces the visible log (it is the authoritative recent history, including ours).
        _messages.value = messages.map { it.toMsg() }
    }

    override fun onMessage(msg: LanChatClient.Incoming) {
        val m = msg.toMsg()
        _messages.update { (it + m).takeLast(MAX_MESSAGES) }
        if (!m.mine) {
            _peerTyping.value = false   // a message means they stopped typing
            // Only a PEER message bumps unread, and only while the full window isn't open.
            if (_windowMode.value != WindowMode.EXPANDED) _unread.update { it + 1 }
        }
    }

    override fun onTyping(role: String, name: String) {
        _peerTyping.value = true
        typingClearJob?.cancel()
        typingClearJob = scope.launch { delay(4000); _peerTyping.value = false }
    }

    override fun onPresence(event: String, role: String, name: String, count: Int) {
        _peerPresent.value = count >= 2
        val who = name.ifBlank { if (role == "host") "Host" else "Guest" }
        _presenceLine.value = when (event) {
            "join" -> "$who joined"
            "leave" -> "$who left"
            else -> null
        }
    }

    private fun LanChatClient.Incoming.toMsg() =
        ChatMsg(from = from, name = name, text = text, ts = ts, mine = from == myRole)
}
