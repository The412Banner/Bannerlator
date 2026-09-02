package com.winlator.star.store

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Friends / chat while a SteamLite game runs (Phase 3b-5 of docs/STEAM_RUST_ENGINE_PLAN.md).
 *
 * The app's own Steam session is `PAUSED_FOR_GAME` for the whole game (one session per account);
 * the genuine client inside the container holds the account and — with `BL_AGENT_FRIENDS=1` — the
 * agent (`steam.exe`, p3) relays that client's friends list, persona changes and 1:1 chat over the
 * agent channel. This object is the app side of that relay: it consumes the `friends` / `persona` /
 * `chat_in` / `chat_typing` / `chat_sent` events, feeds [SteamFriendsStore] (same StateFlows, same
 * notifier), and sends `chat_send` / `friends_refresh` back.
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
    }

    /** Unbind (channel closed / game exited). The store falls back to the live engine. */
    fun detach(ch: SteamAgentChannel?) {
        if (ch == null || channel.compareAndSet(ch, null)) {
            if (ch == null) channel.set(null)
            rosterSeen = false
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
                    SteamFriendsStore.agentOnFriends(entries, self?.optString("name", "") ?: "", self?.optInt("state", 0) ?: 0)
                    Log.i(TAG, "roster from the in-game client: ${entries.size} friend(s)")
                    true
                }
                "persona" -> {
                    obj.optJSONObject("friend")?.let { entryOf(it) }?.let { SteamFriendsStore.agentOnPersona(it); relayed++ }
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

    /** Ask the agent to relay a 1:1 message through the in-game client. False = relay not live. */
    fun sendChat(steamId: Long, text: String): Boolean {
        val ch = channel.get() ?: return false
        if (!isLive() || steamId == 0L || text.isEmpty()) return false
        val cmd = JSONObject().put("cmd", "chat_send").put("sid", steamId.toString()).put("text", text)
        return ch.send(cmd.toString())
    }

    /** Ask for a fresh roster snapshot (the agent also refreshes on its own every minute). */
    fun requestRoster(): Boolean {
        val ch = channel.get() ?: return false
        return ch.send("{\"cmd\":\"friends_refresh\"}")
    }

    /** One relayed friend: SteamID64, persona name, raw EPersonaState, EFriendRelationship, app id. */
    class Entry(val steamId: Long, val name: String, val state: Int, val relationship: Int, val appId: Int)

    private fun entryOf(o: JSONObject): Entry? {
        val sid = o.optString("sid", "").toLongOrNull() ?: return null
        if (sid == 0L) return null
        return Entry(sid, o.optString("name", ""), o.optInt("state", 0), o.optInt("rel", 0), o.optInt("app", 0))
    }
}
