package com.winlator.star.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The signed-in Epic account and its friends, from the public account / friends services the
 * launcher itself uses:
 *
 *   GET https://account-public-service-prod03.ol.epicgames.com/account/api/public/account/{id}
 *   GET https://friends-public-service-prod.ol.epicgames.com/friends/api/v1/{id}/summary
 *   GET https://account-public-service-prod03.ol.epicgames.com/account/api/public/account?accountId=…
 *
 * Everything degrades: no friends service → the roster is empty with a notice, never a crash. The
 * last good payload is mirrored to `bh_epic_prefs` so the tabs paint instantly next open. Epic's
 * friends summary carries no presence either (that lives on the XMPP/EOS side), so the roster is
 * shown as a roster.
 */
object EpicUserData {

    private const val TAG = "EpicUser"
    private const val PREFS = "bh_epic_prefs"
    private const val KEY_CACHE = "epic_userdata_cache"
    private const val ACCOUNT = "https://account-public-service-prod03.ol.epicgames.com/account/api/public/account"
    private const val FRIENDS = "https://friends-public-service-prod.ol.epicgames.com/friends/api/v1"

    data class Friend(val accountId: String, val displayName: String, val since: String)

    data class Profile(
        val accountId: String,
        val displayName: String,
        val country: String,
        val friends: List<Friend>,
        val friendsAvailable: Boolean,
        val fetchedAt: Long,
    )

    fun cached(ctx: Context): Profile? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CACHE, null) ?: return null
        fromJson(JSONObject(s))
    }.getOrNull()

    suspend fun fetch(ctx: Context): Profile? = withContext(Dispatchers.IO) {
        val creds = EpicCredentialStore.load(ctx) ?: return@withContext null
        val accountId = creds.accountId.orEmpty()
        if (accountId.isBlank()) return@withContext null
        val token = EpicCredentialStore.getValidAccessToken(ctx) ?: return@withContext null

        var displayName = creds.displayName.orEmpty()
        var country = ""
        StoreNet.get("$ACCOUNT/$accountId", bearer = token)?.let { body ->
            runCatching {
                val o = JSONObject(body)
                o.optString("displayName", "").takeIf { it.isNotBlank() }?.let { displayName = it }
                country = o.optString("country", "")
            }
        }

        var friendsAvailable = false
        val friends = ArrayList<Friend>()
        StoreNet.get("$FRIENDS/$accountId/summary", bearer = token)?.let { body ->
            runCatching {
                friendsAvailable = true
                val arr = JSONObject(body).optJSONArray("friends") ?: JSONArray()
                val ids = ArrayList<String>()
                val since = HashMap<String, String>()
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val id = f.optString("accountId", "")
                    if (id.isBlank()) continue
                    ids.add(id)
                    since[id] = f.optString("created", "")
                }
                val names = resolveNames(ids, token)
                for (id in ids) friends.add(Friend(id, names[id] ?: id.take(8), since[id].orEmpty()))
            }.onFailure { Log.w(TAG, "friends parse failed: ${it.message}") }
        }

        val profile = Profile(
            accountId = accountId,
            displayName = displayName.ifBlank { "Epic account" },
            country = country,
            friends = friends.sortedBy { it.displayName.lowercase() },
            friendsAvailable = friendsAvailable,
            fetchedAt = System.currentTimeMillis(),
        )
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_CACHE, toJson(profile).toString()).apply()
        Log.i(TAG, "profile: friends=${friends.size} (service ${if (friendsAvailable) "ok" else "unavailable"})")
        profile
    }

    /** Display names for up to 100 account ids per request. */
    private fun resolveNames(ids: List<String>, token: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in ids.chunked(100)) {
            val url = "$ACCOUNT?" + chunk.joinToString("&") { "accountId=$it" }
            val body = StoreNet.get(url, bearer = token) ?: continue
            runCatching {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    val name = o.optString("displayName", "")
                    if (id.isNotBlank() && name.isNotBlank()) out[id] = name
                }
            }
        }
        return out
    }

    private fun toJson(p: Profile): JSONObject = JSONObject().apply {
        put("accountId", p.accountId); put("displayName", p.displayName); put("country", p.country)
        put("friendsAvailable", p.friendsAvailable); put("at", p.fetchedAt)
        put("friends", JSONArray().apply {
            p.friends.forEach { put(JSONObject().put("id", it.accountId).put("name", it.displayName).put("since", it.since)) }
        })
    }

    private fun fromJson(o: JSONObject): Profile {
        val friends = ArrayList<Friend>()
        val arr = o.optJSONArray("friends")
        if (arr != null) for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            friends.add(Friend(f.optString("id"), f.optString("name"), f.optString("since")))
        }
        return Profile(
            accountId = o.optString("accountId"),
            displayName = o.optString("displayName"),
            country = o.optString("country"),
            friends = friends,
            friendsAvailable = o.optBoolean("friendsAvailable", false),
            fetchedAt = o.optLong("at", 0L),
        )
    }
}
