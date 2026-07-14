package com.winlator.star.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The four states of the ONE LAN-over-internet session. There is only ever one (there is only one
 * [LanOverlayVpnService]), so this is a single sealed state, not a registry of many.
 *  - [Idle]     — nothing running; every surface shows fresh Host / Join controls.
 *  - [Creating] — the signaling worker call is in flight (Host pressed / Join submitted).
 *  - [Hosting]  — we created a room; [connected] flips true once the overlay tunnel is up.
 *  - [Joined]   — we joined a room as client; [connected] flips true once the tunnel is up.
 * `connected` today means "the overlay tunnel is established" (establish() + nativeStart succeeded).
 * A deeper peer-level "the other player actually connected" signal is a later phase.
 */
sealed interface LanSession {
    data object Idle : LanSession
    data object Creating : LanSession
    data class Hosting(val code: String, val connected: Boolean) : LanSession
    data class Joined(val code: String, val connected: Boolean) : LanSession
}

/**
 * Single source of truth for the LAN session, mirroring the [com.winlator.star.store.download.DownloadRegistry]
 * pattern: a process-wide singleton exposing a [StateFlow] that all three UI surfaces (nav-drawer dialog,
 * per-game long-press, in-game drawer dialog) collect — so they never drift. Because there is only one
 * VpnService, if a session is active every surface shows that session + Stop, never a fresh Host/Join.
 *
 * Who writes what:
 *  - The host/join UI publishes the OPTIMISTIC transitions it drives directly: [setCreating] while the
 *    signaling worker is being called, then [setPending] (connected=false) the instant a room is obtained
 *    and the overlay service is asked to start.
 *  - The running [LanOverlayVpnService] is the AUTHORITY for up/down: it calls [onOverlayUp] once
 *    establish() + nativeStart succeed (connected=true) and [onOverlayDown] when it tears down (user stop,
 *    or a start failure) — flipping every surface back to [LanSession.Idle].
 */
object LanSessionState {

    private val _session = MutableStateFlow<LanSession>(LanSession.Idle)
    val session: StateFlow<LanSession> = _session.asStateFlow()

    /** True whenever a session exists (Creating / Hosting / Joined). */
    val isActive: Boolean get() = _session.value !is LanSession.Idle

    /** The active room code, or null when Idle/Creating. */
    fun activeCode(): String? = when (val s = _session.value) {
        is LanSession.Hosting -> s.code
        is LanSession.Joined -> s.code
        else -> null
    }

    // ── UI-driven optimistic transitions ─────────────────────────────────────────────
    /** Signaling worker call in flight (Host pressed / Join submitted). */
    fun setCreating() { _session.value = LanSession.Creating }

    /** Room obtained, overlay service asked to start. Optimistic — connected=false until the service confirms. */
    fun setPending(room: LanRoom) {
        _session.value =
            if (room.role == LanOverlay.ROLE_CLIENT) LanSession.Joined(room.code, connected = false)
            else LanSession.Hosting(room.code, connected = false)
    }

    /** Back to the resting state (e.g. the worker failed before the service ever started). */
    fun setIdle() { _session.value = LanSession.Idle }

    // ── Service-authoritative transitions (called from LanOverlayVpnService, Java) ────
    /** Overlay tunnel established + native pump started. Flips connected=true, keeping the code. */
    @JvmStatic
    fun onOverlayUp(code: String, role: Int) {
        _session.value =
            if (role == LanOverlay.ROLE_CLIENT) LanSession.Joined(code, connected = true)
            else LanSession.Hosting(code, connected = true)
    }

    /** Overlay stopped or failed to start. Every surface returns to Idle. */
    @JvmStatic
    fun onOverlayDown() { _session.value = LanSession.Idle }
}
