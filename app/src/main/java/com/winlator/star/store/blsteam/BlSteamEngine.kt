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

        /**
         * Steam answered the token logon with a non-OK EResult (5 InvalidPassword, 15 AccessDenied,
         * 65 AccountLogonDenied, …) or logged the session off ([emsg] 751 = ClientLogonResponse,
         * 757 = ClientLoggedOff). The engine does NOT retry a rejected token; the owner decides
         * (re-auth vs transient).
         */
        fun onLogonResult(emsg: Int, eresult: Int) {}
    }

    /** EMsg ids we decode on the state observer's message firehose (rust/src/emsg.rs). */
    private const val EMSG_CLIENT_LOGON_RESPONSE = 751
    private const val EMSG_CLIENT_LOGGED_OFF = 757

    /** Steam EResults that mean the saved refresh token is dead — never auto-retry these. */
    private val REJECTED_ERESULTS = setOf(5, 15, 65, 68, 87)

    /** Re-logon storm guard: at most this many token logons per window before the engine backs off. */
    private const val MAX_LOGONS_PER_WINDOW = 3
    private const val LOGON_WINDOW_MS = 60_000L

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

    fun isLoggedOn(): Boolean = state() == STATE_LOGGED_ON

    fun steamId64(): Long = session?.steamId() ?: 0L

    /**
     * The live native session, or null when none is up. Phase 1 surfaces (library crawl, PICS
     * lookups, session pre-flight) run their blocking calls against it from worker threads; the
     * handle may be closed underneath a long call, in which case the native side returns null/false.
     */
    fun session(): BlSteamSession? = session

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
        logonRejected = false
        logonStamps.clear()
        s.setStateObserver(object : BlSteamStateObserver {
            override fun onStateChanged(state: Int) {
                onNativeState(s, state)
            }
            override fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray) {
                // Firehose of inbound CM messages. We decode exactly two: the logon response and a
                // server-side logoff, whose EResult (protobuf field 1) tells us whether the saved
                // token is dead — the native runtime reports both as a plain "Connected" state.
                if (emsg == EMSG_CLIENT_LOGON_RESPONSE || emsg == EMSG_CLIENT_LOGGED_OFF) {
                    onLogonMessage(s, emsg, firstVarintField(body, 1, eresult))
                }
            }
        })
        session = s
        if (!s.connect(cmUrl)) {
            Log.w(TAG, "connect() refused by native")
            teardown()
            listener?.onEngineFailure("native connect refused")
        }
    }

    /** True once Steam rejected the token this session — no further automatic logons. */
    @Volatile private var logonRejected = false
    /** Wall-clock stamps of the token logons queued in this session (storm guard). */
    private val logonStamps = ArrayDeque<Long>()

    private fun onLogonMessage(s: BlSteamSession, emsg: Int, eresult: Int) {
        if (session !== s) return
        if (emsg == EMSG_CLIENT_LOGON_RESPONSE && eresult == 1) return   // the LoggedOn state covers it
        Log.w(TAG, "logon message emsg=$emsg eresult=$eresult")
        if (emsg == EMSG_CLIENT_LOGON_RESPONSE && eresult in REJECTED_ERESULTS) {
            logonRejected = true
        } else if (emsg == EMSG_CLIENT_LOGGED_OFF && (eresult == 34 || eresult == 43)) {
            // LoggedInElsewhere / LogonSessionReplaced: another client owns the account. Do NOT
            // auto-relogon (that is a logon tug-of-war); the user re-drives it from the pill.
            logonRejected = true
        }
        listener?.onLogonResult(emsg, eresult)
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
            // Encrypted channel is up (or Steam just answered a logon / logged us off — the runtime
            // re-reports Connected for both): queue the token logon unless the token was rejected
            // or we are looping. The LoggedOn transition follows via this same observer.
            if (logonRejected) {
                Log.w(TAG, "token rejected by Steam — not re-logging on; tearing down")
                teardownAsync("refresh token rejected")
                return
            }
            val now = System.currentTimeMillis()
            val storm = synchronized(logonStamps) {
                while (logonStamps.isNotEmpty() && now - logonStamps.first() > LOGON_WINDOW_MS) logonStamps.removeFirst()
                if (logonStamps.size >= MAX_LOGONS_PER_WINDOW) true
                else { logonStamps.addLast(now); false }
            }
            if (storm) {
                Log.w(TAG, "logon storm guard: $MAX_LOGONS_PER_WINDOW logons in ${LOGON_WINDOW_MS / 1000}s — backing off")
                teardownAsync("logon loop (backing off)")
                return
            }
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

    /**
     * Minimal protobuf scan: the varint value of [field] (wire type 0) in [body], or [fallback].
     * Both CMsgClientLogonResponse and CMsgClientLoggedOff carry `eresult` as field 1, so this
     * avoids a full decoder on the Kotlin side. Stops at the first non-varint / unknown field.
     */
    private fun firstVarintField(body: ByteArray, field: Int, fallback: Int): Int {
        var i = 0
        fun readVarint(): Long? {
            var shift = 0
            var v = 0L
            while (i < body.size && shift < 64) {
                val b = body[i++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
            return null
        }
        while (i < body.size) {
            val tag = readVarint() ?: return fallback
            val f = (tag shr 3).toInt()
            val wt = (tag and 7).toInt()
            when (wt) {
                0 -> { val v = readVarint() ?: return fallback; if (f == field) return v.toInt() }
                1 -> i += 8
                2 -> { val len = readVarint() ?: return fallback; i += len.toInt() }
                5 -> i += 4
                else -> return fallback
            }
            if (i < 0) return fallback
        }
        return fallback
    }

    /**
     * Tear the session down OFF the native callback thread (disconnect may join the runtime that is
     * dispatching to us) and report it as a failure + Disconnected to the listener.
     */
    private fun teardownAsync(reason: String) {
        val l = listener
        Thread({
            teardown()
            l?.onEngineFailure(reason)
            l?.onEngineState(STATE_DISCONNECTED, 0L)
        }, "BlSteamEngine-teardown").start()
    }

    private fun teardown() {
        val s = session ?: return
        session = null
        try { s.setStateObserver(null) } catch (_: Throwable) {}
        try { s.disconnect() } catch (_: Throwable) {}
        s.close()
    }
}
