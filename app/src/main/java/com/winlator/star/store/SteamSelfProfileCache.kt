package com.winlator.star.store

import android.content.Context
import com.winlator.star.store.blsteam.BlPlayerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk mirror of the signed-in user's own [SteamFriendsStore.FriendProfile].
 *
 * ## Why
 * `SteamFriendsStore.profileCache` is memory-only with a 5-minute TTL, so a cold start always
 * showed the Profile tab blank while a multi-round-trip aggregate (level, badge, equipped items,
 * profile info, owned games, recently played) ran behind up to 8s of `ensureLoggedIn`. This mirror
 * lets the tab paint last-known data on the very first frame and revalidate behind it.
 *
 * ## 🔒 Privacy
 * `SteamFriendsStore.reset()` clears `profileCache` on sign-out/account switch precisely so one
 * account can never read another's cached profile. Persisting to disk must not defeat that, so
 * **every record is keyed by the owning SteamID64** (`p_<steamId>`) and is only ever read back for
 * that same id — the same per-account keying the chat-history mirror already uses
 * (`steam_chat_history` / `h_<steamId>`). [clearFor] additionally wipes one account's record
 * outright, and [clearAll] empties the store.
 *
 * ## Scope
 * This is a DISPLAY cache, not a complete record. It carries what the Profile tab renders; a
 * background refresh always replaces it with the full object from the engine. Decoding deliberately
 * reuses [BlPlayerProfile]'s own parsers by writing the engine's key names, so there is exactly one
 * decoder for badges and equipped items rather than a second one that could drift.
 *
 * Every entry point is best-effort and never throws.
 */
object SteamSelfProfileCache {

    private const val TAG = StorefrontLog.PROFILE
    private const val PREFS = "steam_self_profile"

    /** Read this account's mirrored profile, or null when there is none. */
    fun load(ctx: Context, steamId: Long): SteamFriendsStore.FriendProfile? {
        if (steamId == 0L) return null
        val json = try {
            prefs(ctx).getString(key(steamId), null)
        } catch (t: Throwable) {
            StorefrontLog.w(TAG, "self-profile mirror unreadable", t); null
        } ?: return null
        return try {
            decode(steamId, JSONObject(json))
        } catch (t: Throwable) {
            StorefrontLog.w(TAG, "self-profile mirror corrupt — discarding: ${t.javaClass.simpleName}")
            runCatching { prefs(ctx).edit().remove(key(steamId)).apply() }
            null
        }
    }

    /** Mirror [profile] for [steamId]. Call off the main thread. */
    fun save(ctx: Context, steamId: Long, profile: SteamFriendsStore.FriendProfile) {
        if (steamId == 0L) return
        try {
            prefs(ctx).edit().putString(key(steamId), encode(profile).toString()).apply()
        } catch (t: Throwable) {
            StorefrontLog.w(TAG, "could not write the self-profile mirror", t)
        }
    }

    /** Forget ONE account's mirrored profile. */
    fun clearFor(ctx: Context, steamId: Long) {
        if (steamId == 0L) return
        runCatching { prefs(ctx).edit().remove(key(steamId)).apply() }
    }

    /** Forget every mirrored profile (a hard privacy wipe). */
    fun clearAll(ctx: Context) {
        runCatching { prefs(ctx).edit().clear().apply() }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Per-account key — the whole privacy guarantee rests on this. */
    private fun key(steamId: Long) = "p_$steamId"

    // ── codec ─────────────────────────────────────────────────────────────────────────────────

    private fun encode(p: SteamFriendsStore.FriendProfile): JSONObject = JSONObject().apply {
        p.personaName.takeIf { it.isNotBlank() }?.let { put("personaName", it) }
        p.realName?.let { put("realName", it) }
        p.avatarUrl?.let { put("avatarUrl", it) }
        p.level?.let { put("level", it) }
        p.country?.let { put("country", it) }
        p.memberSince?.let { put("memberSince", it) }
        p.summary?.let { put("summary", it) }
        p.gamesCount?.let { put("gamesCount", it) }
        p.hoursTotal?.let { put("hoursTotal", it) }
        p.hoursTwoWeeks?.let { put("hoursTwoWeeks", it) }
        if (p.currentGameAppId != 0) put("currentGameAppId", p.currentGameAppId)
        p.currentGameName?.let { put("currentGameName", it) }

        if (p.recentGames.isNotEmpty()) {
            put(
                "recentGames",
                JSONArray().apply {
                    for (g in p.recentGames) {
                        put(
                            JSONObject()
                                .put("appId", g.appId)
                                .put("name", g.name)
                                .put("hours", g.hours)
                                .apply { g.coverUrl?.let { put("coverUrl", it) } },
                        )
                    }
                },
            )
        }
        // Written with the ENGINE's key names so BlPlayerProfile's own parsers can read them back.
        p.favoriteBadge?.let { put("favoriteBadge", encodeBadge(it)) }
        encodeEquipped(p.equipped)?.let { put("equipped", it) }
    }

    private fun encodeBadge(b: BlPlayerProfile.FavoriteBadge): JSONObject = JSONObject()
        .put("badgeId", b.badgeId)
        .put("itemType", b.itemType)
        .put("borderColor", b.borderColor)
        .put("appId", b.appId)
        .put("level", b.level)
        .apply { b.communityItemId?.let { put("communityItemId", it) } }

    private fun encodeEquipped(e: BlPlayerProfile.EquippedItems): JSONObject? {
        if (e.isEmpty) return null
        val o = JSONObject()
        encodeItem(e.avatarFrame)?.let { o.put("avatarFrame", it) }
        encodeItem(e.profileBackground)?.let { o.put("profileBackground", it) }
        encodeItem(e.miniProfileBackground)?.let { o.put("miniProfileBackground", it) }
        encodeItem(e.animatedAvatar)?.let { o.put("animatedAvatar", it) }
        encodeItem(e.profileModifier)?.let { o.put("profileModifier", it) }
        encodeItem(e.steamDeckKeyboardSkin)?.let { o.put("steamDeckKeyboardSkin", it) }
        return if (o.length() == 0) null else o
    }

    /** Only the fields the Profile tab draws — see the class note on this being a display cache. */
    private fun encodeItem(item: BlPlayerProfile.ProfileItem?): JSONObject? {
        item ?: return null
        return JSONObject()
            .put("appId", item.appId)
            .apply {
                item.communityItemId?.let { put("communityItemId", it) }
                item.imageSmall?.let { put("imageSmall", it) }
                item.imageLarge?.let { put("imageLarge", it) }
                item.name?.let { put("name", it) }
                item.itemTitle?.let { put("itemTitle", it) }
            }
    }

    private fun decode(steamId: Long, o: JSONObject): SteamFriendsStore.FriendProfile {
        val recent = ArrayList<SteamFriendsStore.RecentGame>()
        o.optJSONArray("recentGames")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val appId = g.optInt("appId", 0).takeIf { it > 0 } ?: continue
                recent.add(
                    SteamFriendsStore.RecentGame(
                        appId = appId,
                        name = g.optString("name", "").takeIf { it.isNotBlank() } ?: "App $appId",
                        hours = g.optDouble("hours", 0.0),
                        coverUrl = g.optString("coverUrl", "").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        return SteamFriendsStore.FriendProfile(
            steamId = steamId,
            personaName = o.optString("personaName", "").takeIf { it.isNotBlank() } ?: "Steam user",
            realName = str(o, "realName"),
            avatarUrl = str(o, "avatarUrl"),
            level = if (o.has("level") && !o.isNull("level")) o.optInt("level") else null,
            country = str(o, "country"),
            memberSince = str(o, "memberSince"),
            summary = str(o, "summary"),
            gamesCount = if (o.has("gamesCount")) o.optInt("gamesCount") else null,
            hoursTotal = if (o.has("hoursTotal")) o.optDouble("hoursTotal") else null,
            recentGames = recent,
            badges = null,
            mutualFriends = null,
            currentGameAppId = o.optInt("currentGameAppId", 0),
            currentGameName = str(o, "currentGameName"),
            // Reuses the engine's parsers rather than duplicating them.
            favoriteBadge = BlPlayerProfile.parseFavoriteBadgeJson(
                o.optJSONObject("favoriteBadge")?.toString(),
            ),
            equipped = BlPlayerProfile.parseEquippedJson(o.optJSONObject("equipped")?.toString()),
            hoursTwoWeeks = if (o.has("hoursTwoWeeks")) o.optDouble("hoursTwoWeeks") else null,
        )
    }

    private fun str(o: JSONObject, key: String): String? =
        o.optString(key, "").takeIf { it.isNotBlank() }
}
