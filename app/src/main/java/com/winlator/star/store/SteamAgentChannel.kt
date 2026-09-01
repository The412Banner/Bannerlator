package com.winlator.star.store

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App side of the live SteamLite agent channel (Phase 1-C of docs/STEAM_RUST_ENGINE_PLAN.md).
 *
 * The in-container agent (`steam.exe`, built from `bl-wt-steam-vac/agent-src`) connects to
 * `127.0.0.1:<BL_AGENT_PORT>` — Wine loopback reaches the app process — and streams
 * newline-delimited JSON events; the app can send `{"cmd":"status"}` / `{"cmd":"logoff"}` back.
 * Schema (agent-src/AGENT_CHANNEL.md):
 *
 *  agent → app: `started{pid,appid,agent}`, `logged_in{steamid(masked),ms}`,
 *  `login_failed{eresult,reason}`, `appinfo{state}`, `launch_accepted`, `launch_refused{error,reason}`,
 *  `direct_exe{exe}`, `insecure_fallback{exe,reason}`, `game_spawned{exe,pid,secure}`,
 *  `session_lost`, `achievement{api}`, `game_exited{code,ms}`, `status{...}`, `shutdown{reason,code}`.
 *
 * One channel per launch: [open] binds an ephemeral loopback port BEFORE the guest boots, the agent
 * connects once, [close] tears it down. Everything is best-effort — a shipped agent without the
 * feature simply never connects and the launch is unchanged (the activity then falls back to the
 * log-file inference it used before). The socket never carries the token; the agent masks the
 * SteamID. Every received line is also kept (bounded) for the SteamLite log collector.
 */
class SteamAgentChannel private constructor(private val server: ServerSocket) {

    /** Callbacks on the channel's reader thread — marshal to the UI yourself. */
    interface Listener {
        fun onAgentEvent(ev: String, obj: JSONObject)
        /** The agent connected (once) / went away (EOF, error or [close]). */
        fun onAgentConnected() {}
        fun onAgentDisconnected() {}
    }

    val port: Int get() = server.localPort

    @Volatile private var listener: Listener? = null
    @Volatile private var client: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    private val closed = AtomicBoolean(false)
    private val connectedOnce = AtomicBoolean(false)

    /** Last state seen on the wire (for the overlay + diagnostics). */
    @Volatile var loggedIn: Boolean = false; private set
    @Volatile var launchAccepted: Boolean = false; private set
    @Volatile var gameSpawned: Boolean = false; private set
    @Volatile var secure: Boolean? = null; private set
    @Volatile var lastFailure: String? = null; private set
    val isConnected: Boolean get() = connectedOnce.get() && client != null

    /** Bounded copy of every event line received this launch, oldest first. */
    private val eventLog = ArrayDeque<String>()

    fun setListener(l: Listener?) { listener = l }

    fun eventLines(): List<String> = synchronized(eventLog) { eventLog.toList() }

    /** Send one command line to the agent. False when the agent isn't connected / write failed. */
    fun send(cmdJson: String): Boolean {
        val w = writer ?: return false
        return try {
            synchronized(w) { w.write(cmdJson); w.write("\n"); w.flush() }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.message}")
            false
        }
    }

    fun requestStatus(): Boolean = send("{\"cmd\":\"status\"}")

    /** Ask the agent to log the in-game Steam session off (never kills the game). */
    fun requestLogoff(): Boolean = send("{\"cmd\":\"logoff\"}")

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { client?.close() } catch (_: Throwable) {}
        try { server.close() } catch (_: Throwable) {}
        Log.i(TAG, "closed (port $port)")
    }

    private fun acceptLoop() {
        try {
            val s = server.accept()
            if (closed.get()) { s.close(); return }
            s.tcpNoDelay = true
            s.soTimeout = 0
            client = s
            writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            connectedOnce.set(true)
            Log.i(TAG, "agent connected on port $port")
            try { listener?.onAgentConnected() } catch (t: Throwable) { Log.w(TAG, "listener threw", t) }
            BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).use { r ->
                while (!closed.get()) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    handleLine(line.trim())
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) Log.w(TAG, "accept/read ended: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            val hadClient = client != null
            client = null
            writer = null
            if (hadClient) {
                Log.i(TAG, "agent disconnected")
                try { listener?.onAgentDisconnected() } catch (t: Throwable) { Log.w(TAG, "listener threw", t) }
            }
            close()
        }
    }

    private fun handleLine(line: String) {
        synchronized(eventLog) {
            eventLog.addLast(line)
            while (eventLog.size > MAX_EVENTS) eventLog.removeFirst()
        }
        val obj = try { JSONObject(line) } catch (_: Throwable) {
            Log.w(TAG, "unparseable line (${line.length} chars)"); return
        }
        val ev = obj.optString("ev", "")
        if (ev.isEmpty()) return
        when (ev) {
            "logged_in" -> loggedIn = true
            "session_lost" -> loggedIn = false
            "login_failed" -> lastFailure = "login_failed eresult=${obj.optInt("eresult", 0)} ${obj.optString("reason", "")}"
            "launch_accepted" -> launchAccepted = true
            "launch_refused" -> lastFailure = "launch_refused error=${obj.optInt("error", -1)} ${obj.optString("reason", "")}"
            "insecure_fallback" -> { secure = false; lastFailure = "insecure_fallback ${obj.optString("reason", "")}" }
            "direct_exe" -> secure = false
            "game_spawned" -> { gameSpawned = true; secure = obj.optBoolean("secure", false) }
            "game_exited" -> gameSpawned = false
        }
        // The line never carries a token; the SteamID is already masked by the agent.
        Log.i(TAG, "agent: $line")
        try { listener?.onAgentEvent(ev, obj) } catch (t: Throwable) { Log.w(TAG, "listener threw", t) }
    }

    companion object {
        private const val TAG = "BH_STEAM_AGENT"
        private const val MAX_EVENTS = 400

        /** Bind an ephemeral loopback port and start accepting (one client). Null on failure. */
        fun open(listener: Listener?): SteamAgentChannel? {
            return try {
                val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                val ch = SteamAgentChannel(server)
                ch.listener = listener
                Thread({ ch.acceptLoop() }, "steam-agent-channel").apply { isDaemon = true }.start()
                Log.i(TAG, "listening on 127.0.0.1:${ch.port}")
                ch
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                null
            }
        }
    }
}
