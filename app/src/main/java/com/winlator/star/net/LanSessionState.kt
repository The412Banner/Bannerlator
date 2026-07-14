package com.winlator.star.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How far along the connection is. Drives both the dialog status text and the top-bar pill colour. */
enum class LanPhase {
    CONNECTING,  // session started, overlay tunnel not up yet
    WAITING,     // overlay up, only 1 player in the room
    CONNECTED,   // both players present (members >= 2 / status == "ready")
}

/**
 * The four states of the ONE LAN-over-internet session. There is only ever one (there is only one
 * [LanOverlayVpnService]), so this is a single sealed state, not a registry of many.
 */
sealed interface LanSession {
    data object Idle : LanSession
    data object Creating : LanSession
    data class Hosting(val code: String, val phase: LanPhase) : LanSession
    // hostName: OPTIONAL "Hosted by <username>" flavor, present only when the host was signed in. null = anon.
    data class Joined(val code: String, val phase: LanPhase, val hostName: String? = null) : LanSession
}

/** The phase to render, or null when there's nothing to show (Idle). Creating counts as CONNECTING. */
fun LanSession.phaseOrNull(): LanPhase? = when (this) {
    LanSession.Idle -> null
    LanSession.Creating -> LanPhase.CONNECTING
    is LanSession.Hosting -> phase
    is LanSession.Joined -> phase
}

/**
 * Single source of truth for the LAN session, mirroring the [com.winlator.star.store.download.DownloadRegistry]
 * pattern: a process-wide singleton exposing a [StateFlow] that all UI surfaces (nav-drawer dialog, per-game
 * long-press, in-game drawer dialog, and the Games top-bar pill) collect — so they never drift.
 *
 * Ownership of the /lan/status POLL LOOP lives HERE, tied to the SESSION lifetime — not to any composable —
 * because the session outlives the screen (the user hosts, then navigates away to launch the game). It
 * starts when a session gets a code ([setPending]/[onOverlayUp]) and stops on Idle. Both host and joiner
 * poll (both know the code), so both light up "connected".
 *
 * Who writes what:
 *  - The host/join UI publishes the optimistic transitions it drives: [setCreating] while the signaling
 *    worker is called, then [setPending] the instant a room is obtained and the overlay service is asked
 *    to start.
 *  - The running [LanOverlayVpnService] is authoritative for the tunnel: [onOverlayUp] (tunnel established)
 *    and [onOverlayDown] (stopped / failed).
 *  - The internal poll loop feeds the real member count in, flipping WAITING -> CONNECTED, and ends the
 *    session on a 404 (room expired).
 */
object LanSessionState {

    private val _session = MutableStateFlow<LanSession>(LanSession.Idle)
    val session: StateFlow<LanSession> = _session.asStateFlow()

    // Raw fields the emitted LanSession is derived from. Guarded by the object monitor (@Synchronized) since
    // the UI (main thread) and the poll loop (IO) both mutate them.
    private var creating = false
    private var code: String? = null
    private var role = LanOverlay.ROLE_HOST
    private var hostName: String? = null
    private var overlayUp = false
    private var members = 1

    // Session-scoped poll loop. Own scope so it survives the screen; a single job, restarted per code.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var pollCode: String? = null
    private const val POLL_INTERVAL_MS = 3_500L

    /** True whenever a session exists (Creating / Hosting / Joined). */
    val isActive: Boolean get() = _session.value !is LanSession.Idle

    /** The active room code, or null when Idle/Creating. */
    fun activeCode(): String? = when (val s = _session.value) {
        is LanSession.Hosting -> s.code
        is LanSession.Joined -> s.code
        else -> null
    }

    private fun recompute() {
        val c = code
        _session.value = when {
            c == null -> if (creating) LanSession.Creating else LanSession.Idle
            else -> {
                val phase = when {
                    !overlayUp -> LanPhase.CONNECTING
                    members >= 2 -> LanPhase.CONNECTED
                    else -> LanPhase.WAITING
                }
                if (role == LanOverlay.ROLE_CLIENT) LanSession.Joined(c, phase, hostName)
                else LanSession.Hosting(c, phase)
            }
        }
    }

    // ── UI-driven optimistic transitions ─────────────────────────────────────────────
    /** Signaling worker call in flight (Host pressed / Join submitted). */
    @Synchronized
    fun setCreating() {
        creating = true; code = null; hostName = null; overlayUp = false; members = 1
        stopPolling()
        recompute()
    }

    /** Room obtained, overlay service asked to start. Optimistic — CONNECTING until the tunnel + poll confirm. */
    @Synchronized
    fun setPending(room: LanRoom) {
        creating = false
        code = room.code
        role = room.role
        hostName = room.hostName
        overlayUp = false
        members = 1
        recompute()
        startPolling(room.code)
    }

    /** Back to the resting state (e.g. the worker failed before the service ever started). */
    @Synchronized
    fun setIdle() {
        creating = false; code = null; hostName = null; overlayUp = false; members = 1
        stopPolling()
        recompute()
    }

    // ── Service-authoritative transitions (called from LanOverlayVpnService, Java) ────
    /** Overlay tunnel established + native pump started. Moves CONNECTING -> WAITING (poll then finds peers). */
    @JvmStatic
    @Synchronized
    fun onOverlayUp(code: String, role: Int) {
        // The service is the authority; adopt its code/role. Drop a stale hostName if the code changed.
        if (this.code != code) hostName = null
        this.code = code
        this.role = role
        this.creating = false
        this.overlayUp = true
        recompute()
        startPolling(code)
    }

    /** Overlay stopped or failed to start. Every surface returns to Idle. */
    @JvmStatic
    @Synchronized
    fun onOverlayDown() { setIdle() }

    // ── Poll loop ─────────────────────────────────────────────────────────────────────
    private fun startPolling(c: String) {
        if (pollJob?.isActive == true && pollCode == c) return
        pollJob?.cancel()
        pollCode = c
        pollJob = scope.launch {
            while (isActive) {
                when (val st = LanRoomWorker.status(c)) {
                    is LanStatus.Ok -> onStatus(c, st.members, st.ready)
                    LanStatus.Gone -> { onRoomGone(c); return@launch }
                    LanStatus.Unknown -> { /* transient — keep the session, retry next tick */ }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pollCode = null
    }

    @Synchronized
    private fun onStatus(forCode: String, members: Int, ready: Boolean) {
        if (code != forCode) return   // a newer session started; ignore a straggler poll
        this.members = if (ready) maxOf(members, 2) else members.coerceAtLeast(1)
        recompute()
    }

    @Synchronized
    private fun onRoomGone(forCode: String) {
        if (code != forCode) return
        setIdle()
    }
}
