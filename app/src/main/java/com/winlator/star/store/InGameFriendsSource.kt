package com.winlator.star.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which live Steam session feeds the in-game drawer's Friends tab, and whether the tab is shown at all.
 *
 * The tab renders straight off [SteamFriendsStore]'s flows (roster / unread / typing / chat) — the
 * store already routes a send through the agent relay while the app session is paused for a real-Steam
 * game, and through the engine otherwise. This object only answers the gate question the UI shouldn't
 * have to: is there a live source right now, and which one?
 *
 *  - [Kind.AGENT_RELAY]: SteamLite / RealSteam launch — the genuine client inside the container holds
 *    the account; agent p3b relays its friends + chat over [SteamAgentChannel] into
 *    [SteamAgentFriendsBridge]. Live once the bridge has a roster and the channel is up (the agent arms
 *    the relay ~5 s after the game is running, so the tab appears a few seconds after boot).
 *  - [Kind.APP_SESSION]: every other launch (Goldberg / Raw / plain exe) — the app's own CM session is
 *    not paused, so [SteamFriendsStore.isAvailable] is the whole test.
 *  - [Kind.RELAY_STOPPED]: the relay WAS live and then went away (`friends_relay off` or the channel
 *    dropped) — the tab stays, showing one "relay stopped" line instead of a stale/empty roster.
 *  - [Kind.NONE]: no tab. Friends/chat not opted in at launch ([SteamPrefs.isSocialEnabled], read once
 *    in [arm]), or no live source.
 *
 * Lifecycle is owned by XServerDisplayActivity: [arm] at launch, [setRealSteamLaunch] once the
 * RealSteam plan is (or isn't) armed, [onRelayVerdict] / [onRelayDropped] from the agent channel, and
 * [disarm] at teardown. Everything else recomputes itself from the store/bridge/repository state on any
 * roster push or repository event — every entry point is non-throwing and callable from any thread.
 */
object InGameFriendsSource {

    private const val TAG = "BH_INGAME_FRIENDS"

    enum class Kind { NONE, AGENT_RELAY, APP_SESSION, RELAY_STOPPED }

    data class State(val kind: Kind) {
        /** Show the Friends tab in the drawer rail at all. */
        val tabVisible: Boolean get() = kind != Kind.NONE
        /** A source is live: roster + chat are real. */
        val live: Boolean get() = kind == Kind.AGENT_RELAY || kind == Kind.APP_SESSION
    }

    /** One-line note shown in place of the list once the relay has gone away. */
    const val RELAY_STOPPED_TEXT = "Friends unavailable — Steam relay stopped"

    private val _state = MutableStateFlow(State(Kind.NONE))
    val state: StateFlow<State> = _state.asStateFlow()

    /** The friend whose thread the in-game tab has open (0 = the list). Survives the drawer closing. */
    private val _selectedFriendId = MutableStateFlow(0L)
    val selectedFriendId: StateFlow<Long> = _selectedFriendId.asStateFlow()
    fun selectFriend(id: Long) { _selectedFriendId.value = id }

    @Volatile private var armed = false
    @Volatile private var optedIn = false
    @Volatile private var realSteam = false
    @Volatile private var relayEverLive = false
    @Volatile private var relayVerdictOff = false
    @Volatile private var appSessionRefreshed = false

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watchJob: Job? = null
    private val repoListener = SteamRepository.SteamEventListener { poke() }

    /**
     * Start of a game launch. Reads the friends/chat opt-in ONCE (a mid-game flip in another surface
     * doesn't add/remove the tab), binds the store to a context (history persistence + notifier), and
     * begins watching for a source. [realSteamHint] = the shortcut asks for a RealSteam launch; it keeps
     * the app-session source from flashing up before the plan is armed and the session suspended, and
     * is confirmed or withdrawn by [setRealSteamLaunch] once staging has run.
     */
    fun arm(context: Context, realSteamHint: Boolean) {
        try {
            SteamFriendsStore.init(context)
            optedIn = try { SteamPrefs.isSocialEnabled(context) } catch (_: Throwable) { false }
            realSteam = realSteamHint
            relayEverLive = false
            relayVerdictOff = false
            appSessionRefreshed = false
            _selectedFriendId.value = 0L
            armed = true
            watchJob?.cancel()
            watchJob = scope.launch { SteamFriendsStore.friends.collect { poke() } }
            try { SteamRepository.getInstance().addListener(repoListener) } catch (_: Throwable) {}
            poke()
            Log.i(TAG, "armed (optedIn=$optedIn, realSteamHint=$realSteamHint)")
        } catch (t: Throwable) {
            Log.w(TAG, "arm failed", t)
        }
    }

    /** RealSteam staging finished: true = the plan is armed (relay source), false = fell back to a normal launch. */
    fun setRealSteamLaunch(v: Boolean) {
        realSteam = v
        poke()
    }

    /** Agent p3b `friends_relay{state}` verdict. */
    fun onRelayVerdict(live: Boolean) {
        relayVerdictOff = !live
        poke()
        // The roster snapshot lands around the same time as the verdict and may equal what the app
        // session already had (StateFlow dedups → no push to react to), so re-check a few times.
        if (live) scope.launch { repeat(4) { delay(2000); poke() } }
    }

    /** The agent channel went away (game exited / agent crashed). */
    fun onRelayDropped() {
        poke()
    }

    /** Game teardown: hide the tab, stop watching, leave any in-game thread. */
    fun disarm() {
        if (!armed) return
        armed = false
        watchJob?.cancel(); watchJob = null
        try { SteamRepository.getInstance().removeListener(repoListener) } catch (_: Throwable) {}
        if (_selectedFriendId.value != 0L) try { SteamFriendsStore.closeChat() } catch (_: Throwable) {}
        _selectedFriendId.value = 0L
        _state.value = State(Kind.NONE)
        Log.i(TAG, "disarmed")
    }

    /** Recompute the source from the live store / bridge / repository state. Safe from any thread. */
    fun poke() {
        try {
            val next = synchronized(this) { compute() }
            if (_state.value.kind != next) {
                _state.value = State(next)
                Log.i(TAG, "source -> $next")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "poke failed", t)
        }
    }

    private fun compute(): Kind {
        if (!armed || !optedIn) return Kind.NONE
        val repo = SteamRepository.getInstance()
        if (realSteam) {
            val relayUp = try { repo.isSuspendedForRealSteam && SteamAgentFriendsBridge.isLive() } catch (_: Throwable) { false }
            if (relayUp && !relayVerdictOff) { relayEverLive = true; return Kind.AGENT_RELAY }
            return if (relayEverLive) Kind.RELAY_STOPPED else Kind.NONE
        }
        if (try { !repo.isSuspendedForRealSteam && SteamFriendsStore.isAvailable() } catch (_: Throwable) { false }) {
            // First time the app session shows up as the source: pull the roster once, the same way the
            // full Friends screen does on open (guarded inside — opt-in + one online announce per session).
            if (!appSessionRefreshed) { appSessionRefreshed = true; SteamFriendsStore.refresh() }
            return Kind.APP_SESSION
        }
        return Kind.NONE
    }
}
