package com.winlator.star.store.blsteam

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide owner of the native Steam CM session (Phase 0 of the Rust engine).
 *
 * Responsibilities in this phase are deliberately small: resolve a CM, connect, log on with the
 * saved refresh token, and report state transitions to the [Listener] (which `SteamRepository`
 * maps onto its status pill and `steam_prefs`). It is the seam the later phases grow into —
 * the single "Steam session manager" that owns the credential, refreshes it before launches
 * and coordinates the one-session-per-account rule with the in-container SteamLite agent
 * (the engine already carries `isPlayingBlocked` / `kickPlayingSession` for that).
 *
 * Threading: [start] and [stop] are safe from any thread; network I/O runs on a private
 * worker thread. Listener callbacks arrive on native worker threads.
 */
object BlSteamEngine {

    private const val TAG = "BL_STEAM_ENGINE"

    /** Native `ClientState` ordinals (see rust/src/cm_client.rs). */
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_CONNECTED = 2
    const val STATE_LOGGED_ON = 3

    interface Listener {
        /** Native session state changed. `steamId64` is non-zero once logged on. */
        fun onEngineState(state: Int, steamId64: Long)

        /** A fresh refresh token was obtained; the caller must persist it in `steam_prefs`. */
        fun onRefreshTokenRotated(refreshToken: String)

        /** Connect / logon could not even be attempted (no CM, TLS bundle missing, native refusal). */
        fun onEngineFailure(reason: String)
    }

    @Volatile private var session: BlSteamSession? = null
    @Volatile private var listener: Listener? = null
    private val starting = AtomicBoolean(false)

    // Credentials for the pending logon, captured at start() so the Connected callback can log on
    // without touching SharedPreferences from a native thread.
    @Volatile private var pendingToken: String = ""
    @Volatile private var pendingUser: String = ""
    @Volatile private var pendingSteamId: Long = 0L

    val isActive: Boolean get() = session != null

    fun state(): Int = session?.state() ?: STATE_DISCONNECTED

    fun steamId64(): Long = session?.steamId() ?: 0L

    /**
     * Connect to a Steam CM and log on with [refreshToken]. Idempotent while a session is up or a
     * start is in flight. All network work happens on a worker thread; the result is reported via
     * [Listener].
     */
    fun start(ctx: Context, username: String, refreshToken: String, steamId64: Long, l: Listener) {
        if (refreshToken.isEmpty()) {
            l.onEngineFailure("no refresh token")
            return
        }
        if (session != null) {
            Log.i(TAG, "start ignored — session already active (state=${state()})")
            return
        }
        if (!starting.compareAndSet(false, true)) {
            Log.i(TAG, "start ignored — already starting")
            return
        }
        listener = l
        pendingToken = refreshToken
        pendingUser = username
        pendingSteamId = steamId64
        val app = ctx.applicationContext
        Thread({
            try {
                connectAndLogon(app)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                teardown()
                l.onEngineFailure("${t.javaClass.simpleName}: ${t.message}")
            } finally {
                starting.set(false)
            }
        }, "BlSteamEngine-start").start()
    }

    private fun connectAndLogon(app: Context) {
        BlSteamClient.ensureLoaded()
        val caPath = CaBundleExtractor.ensureBundle(app)
        if (caPath.isEmpty()) {
            listener?.onEngineFailure("CA bundle unavailable")
            return
        }
        val cmUrl = BlSteamSession.pickCmUrl(caPath)
        if (cmUrl.isEmpty()) {
            listener?.onEngineFailure("no CM server resolved")
            return
        }
        Log.i(TAG, "CM picked: $cmUrl")

        val s = BlSteamSession()
        s.setCaBundlePath(caPath)
        // Phase 0: no post-logon PICS crawl — the library still comes from the JavaSteam path.
        s.setAutoPopulateLibrary(false)
        s.setStateObserver(object : BlSteamStateObserver {
            override fun onStateChanged(state: Int) {
                onNativeState(s, state)
            }
            override fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray) {
                // Firehose of inbound CM messages; nothing consumes it in Phase 0.
            }
        })
        session = s
        if (!s.connect(cmUrl)) {
            Log.w(TAG, "connect() refused by native")
            teardown()
            listener?.onEngineFailure("native connect refused")
        }
    }

    private fun onNativeState(s: BlSteamSession, state: Int) {
        if (session !== s) return   // stale callback from a session we already tore down
        val name = when (state) {
            STATE_DISCONNECTED -> "Disconnected"
            STATE_CONNECTING -> "Connecting"
            STATE_CONNECTED -> "Connected"
            STATE_LOGGED_ON -> "LoggedOn"
            else -> "?$state"
        }
        Log.i(TAG, "state -> $name")
        if (state == STATE_CONNECTED) {
            // Encrypted channel is up: queue the token logon. The LoggedOn transition (or a
            // disconnect on rejection) follows via this same observer.
            val ok = s.logonWithRefreshToken(pendingToken, pendingUser, pendingSteamId)
            if (!ok) {
                Log.w(TAG, "logonWithRefreshToken refused by native")
                listener?.onEngineFailure("native logon refused")
            }
        }
        val sid = if (state == STATE_LOGGED_ON) s.steamId() else 0L
        listener?.onEngineState(state, sid)
        if (state == STATE_DISCONNECTED) {
            // The native runtime is gone after a disconnect; drop the handle so the next start()
            // builds a fresh session rather than reusing a dead one.
            if (session === s) {
                session = null
                s.close()
            }
        }
    }

    /**
     * Ask Steam for a fresh refresh token for the current session (blocking, off the main
     * thread). On success the new token is handed to [Listener.onRefreshTokenRotated] and
     * returned; the caller persists it. Not invoked automatically in Phase 0.
     */
    fun renewRefreshToken(timeoutMs: Int = 15_000): String? {
        val s = session ?: return null
        val sid = s.steamId()
        if (sid == 0L || pendingToken.isEmpty()) return null
        val fresh = try {
            s.renewRefreshToken(pendingToken, sid, timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "renewRefreshToken threw", t)
            null
        }
        if (fresh.isNullOrEmpty()) return null
        pendingToken = fresh
        listener?.onRefreshTokenRotated(fresh)
        return fresh
    }

    /** Log off (best-effort flush) and release the native session. Idempotent. */
    fun stop() {
        val s = session ?: return
        session = null
        try {
            if (s.state() == STATE_LOGGED_ON) s.logOffAndDisconnect() else s.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "stop: disconnect threw", t)
        }
        try { s.setStateObserver(null) } catch (_: Throwable) {}
        s.close()
        Log.i(TAG, "stopped")
    }

    private fun teardown() {
        val s = session ?: return
        session = null
        try { s.setStateObserver(null) } catch (_: Throwable) {}
        try { s.disconnect() } catch (_: Throwable) {}
        s.close()
    }
}
