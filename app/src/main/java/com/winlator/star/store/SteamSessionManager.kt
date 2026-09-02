package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.winlator.star.store.blsteam.BlSteamEngine
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Steam session "doorman" for game launches (Phase 1-B of docs/STEAM_RUST_ENGINE_PLAN.md).
 *
 * Single owner of the session-readiness questions a SteamLite (real-Steam) launch has to answer
 * BEFORE `XServerDisplayActivity` opens the container, so the answers land in the launch popup
 * rather than over the game art:
 *
 *  1. **Session** — is there a live, logged-on CM session? If not, re-drive the saved-token logon
 *     (the Rust engine or JavaSteam, whichever the flag selects) and wait, bounded. A rejected
 *     token / no saved session is reported as [SessionResult.NeedsSignIn]. On the Rust engine an
 *     expiring refresh token is renewed here ([maybeRenewRefreshToken]).
 *  2. **Cloud saves** — the pre-launch Steam Cloud pull (moved here from the activity; the
 *     activity skips it when the launch intent carries `preflightDone`).
 *  3. **Network shape** — the [NetworkProbe] STUN verdict (open / strict NAT / UDP blocked),
 *     informational only: it never blocks, it tells the user up front whether a game's own
 *     online mode is likely to connect on this network.
 *  4. **Update check** — the cheap, non-downloading [SteamGameUpdater.checkForUpdate] probe. Never
 *     auto-applies; an available update is offered and the caller runs the existing Update pass.
 *
 * Everything is best-effort and bounded; nothing here can hang the popup. Backed by
 * [BlSteamEngine] when the flag is ON and by [SteamRepository]'s JavaSteam session when OFF, so the
 * pre-flight ships to everyone now. All blocking work runs on a private worker thread; listener
 * callbacks are posted to the main thread.
 */
object SteamSessionManager {

    private const val TAG = "BH_STEAM_DOORMAN"

    /** Launch-intent extra: the pre-flight already pulled cloud saves; the activity must not repeat it. */
    const val EXTRA_PREFLIGHT_DONE = "preflightDone"

    private const val SESSION_WAIT_MS = 20_000L
    private const val CLOUD_WAIT_MS = 35_000L
    private const val UPDATE_CHECK_WAIT_MS = 10_000L
    /** STUN budget for the network-shape row (NetworkProbe); the row waits at most this + 500 ms. */
    private const val NETWORK_PROBE_MS = 2_500
    private const val RENEW_TOKEN_WITHIN_MS = 14L * 24 * 60 * 60 * 1000
    private const val RENEW_WAIT_MS = 15_000

    // ── Session ───────────────────────────────────────────────────────────────────────────────

    sealed class SessionResult {
        object Ok : SessionResult()
        /** The saved sign-in is missing or Steam rejected it — the user must sign in again. */
        data class NeedsSignIn(val reason: String) : SessionResult()
        /** No session could be established in time (network / Steam down) — token still valid. */
        data class Offline(val reason: String) : SessionResult()
    }

    /** True when either engine has a live, logged-on CM session right now. */
    fun isLoggedOn(): Boolean = SteamRepository.getInstance().isSessionLoggedOn

    /**
     * Ensure a logged-on session, re-driving the saved-token logon if needed and blocking the
     * CALLING thread (never the UI thread) up to [timeoutMs]. [progress] receives short status
     * strings for the UI. Never throws.
     */
    fun ensureSession(ctx: Context, timeoutMs: Long = SESSION_WAIT_MS, progress: (String) -> Unit = {}): SessionResult {
        val repo = SteamRepository.getInstance()
        try {
            repo.initialize(ctx.applicationContext)
            if (repo.isSessionLoggedOn) return SessionResult.Ok
            SteamPrefs.init(ctx)
            if (!SteamPrefs.isLoggedIn) return SessionResult.NeedsSignIn("Not signed in to Steam")
            if (repo.isSuspendedForRealSteam) {
                // A previous real-Steam game still holds the account (or its release is in flight).
                progress("Releasing the previous game's Steam session…")
                repo.resumeAfterRealSteam(0L)
            }
            when (repo.status) {
                SteamRepository.SteamStatus.SIGNED_OUT ->
                    return SessionResult.NeedsSignIn("Steam rejected the saved sign-in")
                else -> {}
            }
            progress("Connecting to Steam…")
            // Bring the service up (initialises + connects if it isn't running) and re-drive the
            // token logon through the same entry point the status pill uses. Both are safe from a
            // worker thread — CM I/O is posted to the pump / engine thread.
            try { SteamForegroundService.start(ctx.applicationContext) } catch (t: Throwable) { Log.w(TAG, "FGS start failed", t) }
            repo.reconnectNow()
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastStatus: SteamRepository.SteamStatus? = null
            while (System.currentTimeMillis() < deadline) {
                if (repo.isSessionLoggedOn) return SessionResult.Ok
                val st = repo.status
                if (st != lastStatus) {
                    lastStatus = st
                    progress(
                        when (st) {
                            SteamRepository.SteamStatus.CONNECTING -> "Signing in to Steam…"
                            SteamRepository.SteamStatus.OFFLINE -> "Waiting for a Steam connection…"
                            SteamRepository.SteamStatus.SIGNED_IN_ELSEWHERE -> "Account is signed in elsewhere…"
                            else -> "Connecting to Steam…"
                        },
                    )
                }
                if (st == SteamRepository.SteamStatus.SIGNED_OUT) {
                    return SessionResult.NeedsSignIn("Steam rejected the saved sign-in (${repo.lastSessionStatus})")
                }
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            return SessionResult.Offline("Couldn't reach Steam in ${timeoutMs / 1000}s (${repo.status})")
        } catch (t: Throwable) {
            Log.w(TAG, "ensureSession errored", t)
            return SessionResult.Offline("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Rust engine only: renew the refresh token when it expires within two weeks (Steam refresh
     * tokens are JWTs; the `exp` claim is read locally, the token itself is never logged). The
     * rotated token is persisted by the repository's engine listener. Bounded; never throws.
     * @return true if a fresh token was obtained.
     */
    fun maybeRenewRefreshToken(ctx: Context): Boolean {
        val repo = SteamRepository.getInstance()
        if (!repo.isRustEngine || !BlSteamEngine.isLoggedOn()) return false
        return try {
            val token = repo.refreshToken ?: return false
            val exp = jwtExpiryMs(token)
            val now = System.currentTimeMillis()
            if (exp > 0L && exp - now > RENEW_TOKEN_WITHIN_MS) {
                Log.i(TAG, "refresh token valid for ${(exp - now) / 86_400_000L} more days — no renewal")
                return false
            }
            Log.i(TAG, "refresh token " + (if (exp > 0L) "expires in ${(exp - now) / 3_600_000L}h" else "has no readable expiry") + " — renewing")
            val fresh = BlSteamEngine.renewRefreshToken(RENEW_WAIT_MS)
            Log.i(TAG, "refresh token renewal " + (if (fresh.isNullOrEmpty()) "did not land" else "OK (len=${fresh.length})"))
            !fresh.isNullOrEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "renewRefreshToken errored", t)
            false
        }
    }

    /** `exp` claim (ms since epoch) of a JWT, or 0 when unreadable. Reads only the payload segment. */
    internal fun jwtExpiryMs(token: String): Long = jwtPayload(token)?.optLong("exp", 0L)?.times(1000L) ?: 0L

    /**
     * SteamID64 from a Steam refresh token's JWT `sub` claim, or 0 when unreadable. The QR auth
     * flow never learns the SteamID from Steam directly (JavaSteam derives it the same way); the
     * token itself is never logged.
     */
    @JvmStatic
    fun jwtSteamId64(token: String): Long {
        val sub = jwtPayload(token)?.optString("sub", "") ?: return 0L
        val id = sub.toLongOrNull() ?: return 0L
        return if (id > 76561197960265728L) id else 0L
    }

    private fun jwtPayload(token: String): JSONObject? {
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(payload)
        } catch (_: Throwable) {
            null
        }
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────────────────────

    enum class Step { SESSION, CLOUD, NETWORK, UPDATE, CLIENT }
    /** NOTICE = done, but worth a look (amber): informational, never blocks — the network verdict. */
    enum class StepState { PENDING, RUNNING, DONE, SKIPPED, WARN, NOTICE }

    /** What the pre-flight should do for one launch. */
    data class PreflightRequest(
        val appId: Int,
        val installDir: String,
        val gameName: String,
        val pullCloudSaves: Boolean = true,
        val checkForUpdates: Boolean = true,
    )

    /** Callbacks on the MAIN thread. */
    interface PreflightListener {
        fun onStep(step: Step, state: StepState, text: String)
        /** The saved sign-in is unusable; offer Sign in / Goldberg / Cancel. */
        fun onNeedSignIn(reason: String)
        /** No session in time; offer Retry / Launch anyway (offline) / Goldberg / Cancel. */
        fun onOffline(reason: String)
        /** Game files are behind (or unknown); offer Update / Launch anyway. */
        fun onUpdateAvailable(status: SteamGameUpdater.UpdateStatus)
        /**
         * The catalog has a newer SteamLite package; offer Update / Launch anyway (the latter only
         * when [SteamLiteComponent.UpdateCheck.required] is false). Terminal: every other step is
         * already done, so the caller launches directly once it has its answer.
         */
        fun onClientUpdateAvailable(check: SteamLiteComponent.UpdateCheck)
        /** Every step is done — start the activity. */
        fun onReady()
        fun onCancelled()
    }

    class PreflightHandle internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        val isCancelled: Boolean get() = cancelled.get()
        fun cancel() { cancelled.set(true) }
    }

    /**
     * Run the launch pre-flight on a worker thread. Returns immediately with a cancel handle. The
     * flow stops at the first blocking outcome ([PreflightListener.onNeedSignIn] /
     * [PreflightListener.onOffline] / [PreflightListener.onUpdateAvailable] /
     * [PreflightListener.onClientUpdateAvailable]) and it is the caller's job to continue (e.g.
     * re-run with `skipSession`/`skipUpdate` after "Launch anyway"; the client offer is the last
     * step, so the caller launches directly after it).
     */
    fun preflightAsync(
        ctx: Context,
        req: PreflightRequest,
        listener: PreflightListener,
        skipSession: Boolean = false,
        skipUpdate: Boolean = false,
    ): PreflightHandle {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val handle = PreflightHandle()
        fun post(r: () -> Unit) { main.post { if (!handle.isCancelled) r() } }
        fun step(s: Step, st: StepState, text: String) { post { listener.onStep(s, st, text) } }

        Thread({
            try {
                // Network shape (NetworkProbe) runs beside the session step so its ~1-2.5 s hides
                // behind the sign-in wait; the row is reported in order, after cloud saves. Cached
                // for 10 min, so a Retry / Launch-anyway re-run doesn't probe again.
                val netLatch = CountDownLatch(1)
                var net: NetworkProbe.Result? = null
                Thread({
                    try { net = NetworkProbe.probeCachedOrFresh(NETWORK_PROBE_MS) } catch (t: Throwable) { Log.w(TAG, "network probe errored", t) }
                    finally { netLatch.countDown() }
                }, "steam-preflight-net").apply { isDaemon = true }.start()

                // 1. Session -------------------------------------------------------------------
                if (skipSession) {
                    step(Step.SESSION, StepState.SKIPPED, "Launching without a live Steam session")
                } else {
                    step(Step.SESSION, StepState.RUNNING, "Checking Steam sign-in…")
                    val r = ensureSession(app) { msg -> step(Step.SESSION, StepState.RUNNING, msg) }
                    if (handle.isCancelled) { post { listener.onCancelled() }; return@Thread }
                    when (r) {
                        is SessionResult.Ok -> {
                            val renewed = maybeRenewRefreshToken(app)
                            step(Step.SESSION, StepState.DONE, if (renewed) "Signed in (sign-in refreshed)" else "Signed in")
                        }
                        is SessionResult.NeedsSignIn -> {
                            step(Step.SESSION, StepState.WARN, r.reason)
                            post { listener.onNeedSignIn(r.reason) }
                            return@Thread
                        }
                        is SessionResult.Offline -> {
                            step(Step.SESSION, StepState.WARN, r.reason)
                            post { listener.onOffline(r.reason) }
                            return@Thread
                        }
                    }
                }

                // 2. Cloud saves ---------------------------------------------------------------
                if (!req.pullCloudSaves || req.appId <= 0 || req.installDir.isEmpty()) {
                    step(Step.CLOUD, StepState.SKIPPED, if (req.appId <= 0) "Cloud saves: game not resolved" else "Cloud saves: off")
                } else if (skipSession || !isLoggedOn()) {
                    step(Step.CLOUD, StepState.SKIPPED, "Cloud saves: skipped (no Steam session)")
                } else {
                    // Engine-agnostic since Phase 3b-1: the same newest-wins pull runs on the
                    // JavaSteam handler or the Rust engine's ccloud calls (SteamCloudBackend).
                    step(Step.CLOUD, StepState.RUNNING, "Syncing cloud saves…")
                    val summary = runBounded(CLOUD_WAIT_MS) {
                        SteamCloudSaveManager.syncFromCloudNewestWins(app, req.appId, req.installDir)
                    } ?: "Cloud saves: still syncing — launching with local saves"
                    Log.i(TAG, "cloud pull (appId ${req.appId}): $summary")
                    step(Step.CLOUD, StepState.DONE, summary)
                }
                if (handle.isCancelled) { post { listener.onCancelled() }; return@Thread }

                // 3. Network shape (informational — NEVER blocks the launch) -------------------------
                // Steam sign-in and downloads work on any NAT; this is for games with their own
                // servers / P2P (Brawlhalla "Incorrect Version" on a hotspot or VPN), so the user
                // sees the network verdict up front instead of guessing.
                step(Step.NETWORK, StepState.RUNNING, "Checking network…")
                netLatch.await(NETWORK_PROBE_MS + 500L, TimeUnit.MILLISECONDS)
                val verdict = net
                if (verdict == null) {
                    step(Step.NETWORK, StepState.DONE, "Couldn't check")
                } else {
                    Log.i(TAG, "network: ${verdict.logLine()}")
                    step(Step.NETWORK, if (verdict.isWarning) StepState.NOTICE else StepState.DONE, verdict.verdict())
                }
                if (handle.isCancelled) { post { listener.onCancelled() }; return@Thread }

                // 4. Update check ----------------------------------------------------------------
                if (skipUpdate || !req.checkForUpdates || req.appId <= 0) {
                    step(Step.UPDATE, StepState.SKIPPED, if (skipUpdate) "Launching this build" else "Update check: off")
                } else {
                    step(Step.UPDATE, StepState.RUNNING, "Checking for updates…")
                    val status = checkForUpdateBlocking(app, req.appId)
                    if (handle.isCancelled) { post { listener.onCancelled() }; return@Thread }
                    when (status?.state) {
                        SteamGameUpdater.State.UP_TO_DATE -> {
                            val b = if (status.installedBuild > 0L) " (build ${status.installedBuild})" else ""
                            step(Step.UPDATE, StepState.DONE, "Up to date$b")
                        }
                        SteamGameUpdater.State.NOT_INSTALLED ->
                            step(Step.UPDATE, StepState.WARN, "Game files not found on disk")
                        SteamGameUpdater.State.UPDATE_AVAILABLE -> {
                            step(Step.UPDATE, StepState.WARN,
                                if (status.installedBuild > 0L && status.liveBuild > 0L)
                                    "Update available: build ${status.installedBuild} → ${status.liveBuild}"
                                else "A newer build is available")
                            post { listener.onUpdateAvailable(status) }
                            return@Thread
                        }
                        SteamGameUpdater.State.UNKNOWN, null ->
                            step(Step.UPDATE, StepState.DONE, "Couldn't check for updates — launching this build")
                    }
                }

                // 5. SteamLite client package --------------------------------------------------------
                // Safety net for remembered launches that skip the popup (which has its own
                // "Update & Launch" gate). Bounded catalog fetch; offline / a failed fetch never
                // blocks — the installed package launches (even below MIN_AGENT_VERSION: the agent
                // is backwards compatible, newer app features just stay off).
                if (!SteamLiteComponent.isInstalled(app)) {
                    step(Step.CLIENT, StepState.SKIPPED, "SteamLite client: downloaded by the launch")
                } else {
                    step(Step.CLIENT, StepState.RUNNING, "Checking for SteamLite updates…")
                    val check = SteamLiteComponent.checkUpdateBlocking(app)
                    if (handle.isCancelled) { post { listener.onCancelled() }; return@Thread }
                    val installed = SteamLiteComponent.versionLabel(check.installed)
                    when {
                        !check.checked ->
                            step(Step.CLIENT, StepState.DONE, "Couldn't check for SteamLite updates — using installed $installed")
                        check.available -> {
                            step(Step.CLIENT, StepState.WARN,
                                (if (check.required) "Update required" else "Update available") +
                                    " ($installed → v${check.latestVersion})")
                            post { listener.onClientUpdateAvailable(check) }
                            return@Thread
                        }
                        else -> step(Step.CLIENT, StepState.DONE, "Up to date ($installed)")
                    }
                }
                post { listener.onReady() }
            } catch (t: Throwable) {
                Log.w(TAG, "preflight errored", t)
                post { listener.onReady() }   // never strand the launch on a pre-flight bug
            }
        }, "steam-preflight-${req.appId}").apply { isDaemon = true }.start()
        return handle
    }

    // ── Live agent channel (Phase 1-C) ──────────────────────────────────────────────────────

    @Volatile private var agentChannel: SteamAgentChannel? = null

    /** The channel of the launch in flight (null when none / the agent never connected). */
    fun agentChannel(): SteamAgentChannel? = agentChannel

    /**
     * Open the loopback listener the in-container agent will connect to. Called by the launch
     * activity BEFORE the guest boots; the returned port goes into the plan env as `BL_AGENT_PORT`.
     * Any previous channel is closed first (one launch at a time). 0 when it could not be opened.
     */
    fun openAgentChannel(listener: SteamAgentChannel.Listener?): Int {
        closeAgentChannel()
        val ch = SteamAgentChannel.open(listener) ?: return 0
        agentChannel = ch
        // Friends/chat relay (agent p3): the bridge consumes the channel's friends events while the
        // app session is paused for the game.
        SteamAgentFriendsBridge.attach(ch)
        return ch.port
    }

    fun closeAgentChannel() {
        val ch = agentChannel ?: return
        agentChannel = null
        SteamAgentFriendsBridge.detach(ch)
        ch.close()
    }

    /** The raw event lines of the current/last launch (for the SteamLite log collector). */
    fun agentEventLines(): List<String> = agentChannel?.eventLines() ?: emptyList()

    // ── Pending relaunch (fail-card "Retry" / "Launch with Goldberg") ────────────────────────
    // The launch activity can't re-run the library's launch flow itself (its exit path restarts
    // the process), so it records the wish here and the library screen consumes it on its next
    // composition: Retry re-opens the SteamLite pre-flight, Goldberg runs the Goldberg launch.

    private const val RELAUNCH_PREFS = "steam_relaunch"
    private const val RELAUNCH_TTL_MS = 3L * 60 * 1000

    enum class RelaunchMode { STEAMLITE, GOLDBERG }

    data class PendingRelaunch(val shortcutPath: String, val containerId: Int, val mode: RelaunchMode)

    fun setPendingRelaunch(ctx: Context, shortcutPath: String, containerId: Int, mode: RelaunchMode) {
        try {
            ctx.applicationContext.getSharedPreferences(RELAUNCH_PREFS, Context.MODE_PRIVATE).edit()
                .putString("path", shortcutPath)
                .putInt("container", containerId)
                .putString("mode", mode.name)
                .putLong("at", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "pending relaunch recorded: $mode for $shortcutPath")
        } catch (t: Throwable) {
            Log.w(TAG, "setPendingRelaunch failed", t)
        }
    }

    /** Consume (and clear) a pending relaunch recorded within the last few minutes, if any. */
    fun takePendingRelaunch(ctx: Context): PendingRelaunch? {
        return try {
            val p = ctx.applicationContext.getSharedPreferences(RELAUNCH_PREFS, Context.MODE_PRIVATE)
            val path = p.getString("path", null) ?: return null
            val at = p.getLong("at", 0L)
            val mode = try { RelaunchMode.valueOf(p.getString("mode", "") ?: "") } catch (_: Throwable) { null }
            p.edit().clear().apply()
            if (mode == null || System.currentTimeMillis() - at > RELAUNCH_TTL_MS) null
            else PendingRelaunch(path, p.getInt("container", 0), mode)
        } catch (t: Throwable) {
            Log.w(TAG, "takePendingRelaunch failed", t)
            null
        }
    }

    private fun checkForUpdateBlocking(ctx: Context, appId: Int): SteamGameUpdater.UpdateStatus? {
        val latch = CountDownLatch(1)
        var out: SteamGameUpdater.UpdateStatus? = null
        try {
            SteamGameUpdater.checkForUpdate(ctx, appId) { s -> out = s; latch.countDown() }
            latch.await(UPDATE_CHECK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "checkForUpdate errored", t)
        }
        return out
    }

    /** Run [block] on a helper thread and wait up to [boundMs]; null when it did not finish in time. */
    private fun <T> runBounded(boundMs: Long, block: () -> T): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        Thread({
            try { result = block() } catch (t: Throwable) { Log.w(TAG, "bounded task errored", t) }
            finally { latch.countDown() }
        }, "steam-preflight-task").apply { isDaemon = true }.start()
        return if (latch.await(boundMs, TimeUnit.MILLISECONDS)) result else null
    }
}
