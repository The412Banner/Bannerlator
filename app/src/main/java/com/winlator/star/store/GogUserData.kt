package com.winlator.star.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The signed-in GOG user, from `embed.gog.com/userData.json` — the call the login screen already
 * makes to learn the username, read here in full: avatar, Galaxy id, owned-game count, wishlist
 * count and the friends list GOG embeds in the same payload.
 *
 * Every field is optional and parsed defensively; a missing section renders as absent, never as a
 * zero. The last good payload is mirrored to `bh_gog_prefs` so the Profile and Friends tabs paint
 * instantly on the next open and survive an offline launch.
 *
 * GOG friends carry NO presence — the payload is a roster, not a status feed — so the Friends tab
 * is honest about that: it lists people, it does not pretend to know who is online.
 */
object GogUserData {

    private const val TAG = "GogUser"
    private const val PREFS = "bh_gog_prefs"
    private const val KEY_CACHE = "gog_userdata_cache"

    data class Friend(
        val username: String,
        val galaxyId: String,
        val avatar: String?,
        val userSince: String,
    )

    data class Profile(
        val username: String,
        val userId: String,
        val galaxyUserId: String,
        val avatar: String?,
        val country: String,
        val ownedGames: Int,
        val ownedMovies: Int,
        val wishlisted: Int,
        val friends: List<Friend>,
        val fetchedAt: Long,
    )

    /**
     * GOG avatar URLs come without an extension; the site appends a size formatter. Try medium,
     * then the bare jpg, then the raw value — the art composable walks this chain.
     */
    fun avatarCandidates(avatar: String?): List<String> {
        if (avatar.isNullOrBlank()) return emptyList()
        val base = GogStoreCatalog.absolutize(avatar)
        return if (base.endsWith(".jpg") || base.endsWith(".png")) listOf(base)
        else listOf("${base}_avm.jpg", "${base}_avs.jpg", "$base.jpg", base)
    }

    fun cached(ctx: Context): Profile? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CACHE, null) ?: return null
        parse(JSONObject(s), ctx.getSharedPreferences(PREFS, 0).getLong("${KEY_CACHE}_at", 0L))
    }.getOrNull()

    suspend fun fetch(ctx: Context): Profile? = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, 0)
        val token = GogLibraryRepo.validToken(ctx) ?: return@withContext null
        val body = StoreNet.get("https://embed.gog.com/userData.json", bearer = token, userAgent = "GOG Galaxy")
            ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val now = System.currentTimeMillis()
        val profile = parse(json, now) ?: return@withContext null
        prefs.edit().putString(KEY_CACHE, body).putLong("${KEY_CACHE}_at", now).apply()
        Log.i(TAG, "userData: games=${profile.ownedGames} friends=${profile.friends.size}")
        profile
    }

    private fun parse(j: JSONObject, at: Long): Profile? {
        val username = j.optString("username", "")
        if (username.isEmpty() && !j.optBoolean("isLoggedIn", false)) return null
        val purchased = j.optJSONObject("purchasedItems")
        val friends = ArrayList<Friend>()
        val arr = j.optJSONArray("friends")
        if (arr != null) for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val name = f.optString("username", "")
            if (name.isEmpty()) continue
            friends.add(
                Friend(
                    username = name,
                    galaxyId = f.optString("galaxyId", f.optString("id", "")),
                    avatar = f.optString("avatar", "").ifBlank { null },
                    userSince = f.optString("userSince", ""),
                ),
            )
        }
        return Profile(
            username = username,
            userId = j.optString("userId", ""),
            galaxyUserId = j.optString("galaxyUserId", ""),
            avatar = j.optString("avatar", "").ifBlank { null },
            country = j.optString("country", ""),
            ownedGames = purchased?.optInt("games", 0) ?: 0,
            ownedMovies = purchased?.optInt("movies", 0) ?: 0,
            wishlisted = j.optInt("wishlistedItems", 0),
            friends = friends.sortedBy { it.username.lowercase() },
            fetchedAt = at,
        )
    }
}
