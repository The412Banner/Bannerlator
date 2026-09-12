package com.winlator.star.store

import android.util.Log
import com.winlator.star.store.blsteam.BlSocialFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Friends / chat while a SteamLite game runs (Phase 3b-5 of docs/STEAM_RUST_ENGINE_PLAN.md).
 *
 * The app's own Steam session is `PAUSED_FOR_GAME` for the whole game (one session per account);
 * the genuine client inside the container holds the account and — with `BL_AGENT_FRIENDS=1` — the
 * agent (`steam.exe`, p3c) relays that client's friends list, persona changes and 1:1 chat over the
 * agent channel. This object is the app side of that relay: it consumes the `friends` / `persona` /
 * `chat_in` / `chat_typing` / `chat_sent` events, feeds [SteamFriendsStore] (same StateFlows, same
 * notifier), and sends `chat_send` / `friends_refresh` back.
 *
 * Presence (agent p3c/p3d): a headless client answers Offline for every friend until the CM streams
 * their presence, and the CM only does that once the session has announced a persona state — agent
 * p3d announces Online (ISteamFriends002::SetPersonaState) when the relay arms, then confirms each
 * friend from a PersonaStateChange_t / a non-Offline read (p3c's RequestUserInformation "already
 * cached" answer only covered the NAME and produced 19 confirmed-Offline persona events on device).
 * Entries report whether the presence is CONFIRMED (`k:1` on roster entries; every `persona` event is
 * confirmed). The store merges by SteamID and never downgrades a friend on an unconfirmed Offline;
 * [presence] tells the in-game tab how many of the relayed friends are confirmed so it can show a
 * "presence: N of M known" hint until the roster is complete.
 *
 * Source switch: [SteamFriendsStore] routes a send here whenever the app session is suspended for
 * a real-Steam game AND the relay is live ([isLive]); otherwise the engine / JavaSteam path is used
 * as before. When the game exits the channel closes, the app session resumes and the engine's
 * refresh overrides the relayed roster.
 *
 * Privacy: chat bodies never reach logcat or the SteamLite bundle — [SteamAgentChannel] hands these
 * events here without logging them, and only a count is recorded.
 */
object SteamAgentFriendsBridge {

    private const val TAG = "BH_STEAM_AGENT_FRIENDS"

    private val channel = AtomicReference<SteamAgentChannel?>(null)

    @Volatile private var rosterSeen = false
    @Volatile private var relayed = 0

    /** Friends (relationship = friend) the relay has listed, and the subset whose presence is confirmed. */
    private val rosterIds: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val knownIds: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** How far the in-game client has got with presence: [known] of [total] relayed friends confirmed. */
    data class Presence(val known: Int, val total: Int) {
        val complete: Boolean get() = total == 0 || known >= total
    }

    private val _presence = MutableStateFlow(Presence(0, 0))
    val presence: StateFlow<Presence> = _presence.asStateFlow()

    /** True while an agent with the friends relay is connected and has sent a roster. */
    fun isLive(): Boolean {
        val ch = channel.get() ?: return false
        return rosterSeen && ch.isConnected
    }

    /** How many chat/persona events this launch relayed (diagnostics only). */
    fun relayedCount(): Int = relayed

    /** Bind the launch's channel (called by [SteamSessionManager.openAgentChannel]). */
    fun attach(ch: SteamAgentChannel) {
        channel.set(ch)
        rosterSeen = false
        relayed = 0
        rosterIds.clear()
        knownIds.clear()
        _presence.value = Presence(0, 0)
    }

    /** Unbind (channel closed / game exited). The store falls back to the live engine. */
    fun detach(ch: SteamAgentChannel?) {
        if (ch == null || channel.compareAndSet(ch, null)) {
            if (ch == null) channel.set(null)
            rosterSeen = false
            rosterIds.clear()
            knownIds.clear()
            _presence.value = Presence(0, 0)
            try { SteamFriendsStore.agentDetached() } catch (t: Throwable) { Log.w(TAG, "detach hook failed", t) }
        }
    }

    /** Event hook from [SteamAgentChannel] (reader thread). Returns true when the event was ours. */
    fun onEvent(ev: String, obj: JSONObject): Boolean {
        return try {
            when (ev) {
                "friends" -> {
                    val list = obj.optJSONArray("list")
                    val entries = ArrayList<Entry>(list?.length() ?: 0)
                    if (list != null) for (i in 0 until list.length()) {
                        list.optJSONObject(i)?.let { entryOf(it) }?.let { entries.add(it) }
                    }
                    val self = obj.optJSONObject("self")
                    rosterSeen = true
                    for (e in entries) if (e.relationship == BlSocialFeed.REL_FRIEND) {
                        rosterIds.add(e.steamId)
                        if (e.known) knownIds.add(e.steamId)
                    }
                    publishPresence()
                    SteamFriendsStore.agentOnFriends(entries, self?.optString("name", "") ?: "", self?.optInt("state", 0) ?: 0)
                    Log.i(TAG, "roster from the in-game client: ${entries.size} friend(s), ${_presence.value.known} of ${_presence.value.total} with known presence")
                    true
                }
                "persona" -> {
                    // p3c sends the fields flat; p3/p3b nested them under `friend`.
                    val e = (obj.optJSONObject("friend") ?: obj).let { entryOf(it, confirmed = true) }
                    if (e != null) {
                        relayed++
                        if (e.relationship == BlSocialFeed.REL_FRIEND) { rosterIds.add(e.steamId); knownIds.add(e.steamId); publishPresence() }
                        SteamFriendsStore.agentOnPersona(e)
                        Log.i(TAG, "persona: state=${e.state} app=${e.appId} (${_presence.value.known} of ${_presence.value.total} known)")
                    }
                    true
                }
                "chat_in" -> {
                    val sid = obj.optString("sid", "").toLongOrNull() ?: return true
                    val text = obj.optString("text", "")
                    if (sid != 0L && text.isNotEmpty()) {
                        relayed++
                        SteamFriendsStore.agentOnChatIn(sid, text, obj.optLong("ts", 0L))
                    }
                    true
                }
                "chat_typing" -> {
                    obj.optString("sid", "").toLongOrNull()?.let { if (it != 0L) SteamFriendsStore.agentOnTyping(it) }
                    true
                }
                "chat_sent" -> {
                    val sid = obj.optString("sid", "").toLongOrNull() ?: 0L
                    val ok = obj.optBoolean("ok", false)
                    if (!ok) Log.w(TAG, "in-game client refused a chat send")
                    SteamFriendsStore.agentOnChatSent(sid, ok)
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "event $ev failed", t)
            true
        }
    }

    private fun publishPresence() {
        val total = rosterIds.size
        val known = knownIds.count { it in rosterIds }
        _presence.value = Presence(known, total)
    }

    /** Ask the agent to relay a 1:1 message through the in-game client. False = relay not live. */
    fun sendChat(steamId: Long, text: String): Boolean {
        val ch = channel.get() ?: return false
        if (!isLive() || steamId == 0L || text.isEmpty()) return false
        val cmd = JSONObject().put("cmd", "chat_send").put("sid", steamId.toString()).put("text", text)
        return ch.send(cmd.toString())
    }

    /** Ask for a fresh roster snapshot (the agent also refreshes on its own every 30 s). */
    fun requestRoster(): Boolean {
        val ch = channel.get() ?: return false
        return ch.send("{\"cmd\":\"friends_refresh\"}")
    }

    /**
     * One relayed friend: SteamID64, persona name, raw EPersonaState, EFriendRelationship, app id,
     * whether the in-game client has CONFIRMED the presence ([known]; false = its post-logon Offline
     * default, not to be trusted), and the friend's rich-presence `status` line when in a game.
     */
    class Entry(
        val steamId: Long,
        val name: String,
        val state: Int,
        val relationship: Int,
        val appId: Int,
        val known: Boolean,
        val richStatus: String?,
    )

    private fun entryOf(o: JSONObject, confirmed: Boolean = false): Entry? {
        val sid = o.optString("sid", "").toLongOrNull() ?: return null
        if (sid == 0L) return null
        return Entry(
            sid, o.optString("name", ""), o.optInt("state", 0), o.optInt("rel", 0), o.optInt("app", 0),
            confirmed || o.optInt("k", 0) != 0,
            o.optString("rp", "").takeIf { it.isNotBlank() },
        )
    }
}
