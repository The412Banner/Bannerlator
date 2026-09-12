package com.winlator.star.store.blsteam

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed decode of [BlSteamSession.getPlayerProfile] (and its single-slice siblings
 * [BlSteamSession.getSteamLevels] / [BlSteamSession.getFavoriteBadge] /
 * [BlSteamSession.getEquippedProfileItems] / [BlSteamSession.getRecentlyPlayedGames]).
 *
 * **Every section fails independently to null.** Steam's privacy model is not all-or-nothing:
 *
 *  - `level`, `favoriteBadge`, `equipped` — profile *decoration*, always public. Present even for
 *    a limited/private profile.
 *  - `profileInfo` — needs a public profile. A limited profile answers with **empty strings**, not
 *    an error, so blank fields here are normal and must simply not render.
 *  - `ownedGameCount` / `playtimeForeverMinutes` / `playtimeTwoWeeksMinutes` / `recentlyPlayed` —
 *    need *game details* public. [gamesPublic] says which side of that line the account is on.
 *
 * ⚠️ [gamesPublic] is false BOTH for a private account and for an account that genuinely owns
 * nothing, and the two are indistinguishable over the wire. Never render "this profile is private"
 * off [gamesPublic] alone.
 *
 * The whole native surface is new and not compile-verified against a running engine, so every
 * accessor here tolerates a null/short/garbled payload and yields nulls rather than throwing.
 */
data class BlPlayerProfile(
    val steamId: Long,
    val accountId: Int,
    val isSelf: Boolean,
    /** Steam level, or null when the CM declined it (NOT 0 — 0 is a real level). */
    val level: Int?,
    /** The single showcased badge, or null when none is set. There is no badge *collection* RPC. */
    val favoriteBadge: FavoriteBadge?,
    val equipped: EquippedItems,
    /** Public-profile fields; null when the profile is limited or the section failed. */
    val profileInfo: ProfileInfo?,
    /** Whether the account's game details are public — see the class-level caveat. */
    val gamesPublic: Boolean,
    val ownedGameCount: Int?,
    val playtimeForeverMinutes: Long?,
    val playtimeTwoWeeksMinutes: Long?,
    val recentlyPlayed: List<RecentlyPlayed>,
) {
    /** Total hours across the library, or null when game details are private. */
    val hoursForever: Double? get() = playtimeForeverMinutes?.let { it / 60.0 }

    /** Hours in the last two weeks, or null when game details are private. */
    val hoursTwoWeeks: Double? get() = playtimeTwoWeeksMinutes?.let { it / 60.0 }

    data class ProfileInfo(
        val eresult: Int,
        val timeCreated: Long,
        val realName: String?,
        val cityName: String?,
        val stateName: String?,
        val countryName: String?,
        val headline: String?,
        val summary: String?,
    ) {
        /** True when a limited profile answered with the empty-string placeholder set. */
        val isBlank: Boolean
            get() = realName.isNullOrBlank() && countryName.isNullOrBlank() &&
                headline.isNullOrBlank() && summary.isNullOrBlank() && timeCreated <= 0L
    }

    data class FavoriteBadge(
        val badgeId: Int,
        /** u64 as a STRING — it exceeds JSON's Long-safe range. Never parse it to Long. */
        val communityItemId: String?,
        val itemType: Int,
        val borderColor: Int,
        val appId: Int,
        val level: Int,
    )

    /** One equipped decoration slot. [communityItemId] stays a String for the same reason. */
    data class ProfileItem(
        val communityItemId: String?,
        val imageSmall: String?,
        val imageLarge: String?,
        val name: String?,
        val itemTitle: String?,
        val itemDescription: String?,
        val appId: Int,
        val itemType: Int,
        val itemClass: Int,
        val movieWebm: String?,
        val movieMp4: String?,
        val movieWebmSmall: String?,
        val movieMp4Small: String?,
        val equippedFlags: Int,
        val tiled: Boolean,
        val profileColors: List<ProfileColor>,
    ) {
        /** Steam serves item art relative to the community CDN; absolute URLs pass through. */
        fun imageUrl(large: Boolean = false): String? {
            val path = (if (large) imageLarge else imageSmall)?.takeIf { it.isNotBlank() } ?: return null
            return if (path.startsWith("http")) path else "$COMMUNITY_ASSET_BASE$path"
        }
    }

    data class ProfileColor(val styleName: String, val color: String)

    data class EquippedItems(
        val avatarFrame: ProfileItem?,
        val profileBackground: ProfileItem?,
        val miniProfileBackground: ProfileItem?,
        val animatedAvatar: ProfileItem?,
        val profileModifier: ProfileItem?,
        val steamDeckKeyboardSkin: ProfileItem?,
    ) {
        val isEmpty: Boolean
            get() = avatarFrame == null && profileBackground == null && miniProfileBackground == null &&
                animatedAvatar == null && profileModifier == null && steamDeckKeyboardSkin == null

        companion object {
            val NONE = EquippedItems(null, null, null, null, null, null)
        }
    }

    /** Same element shape `nativeGetOwnedGames` emits. */
    data class RecentlyPlayed(
        val appId: Int,
        val name: String,
        val playtimeTwoWeeks: Long,
        val playtimeForever: Long,
        val imgIconUrl: String?,
        val sortAs: String?,
        val rtimeLastPlayed: Long,
    ) {
        val headerUrl: String
            get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg"
    }

    companion object {
        private const val TAG = "BlPlayerProfile"
        // Not private: the nested ProfileItem reads it. Companion-private would very likely
        // work here, but this costs nothing and removes the visibility question entirely.
        internal const val COMMUNITY_ASSET_BASE = "https://cdn.fastly.steamstatic.com/steamcommunity/public/images/items/"

        /** Decode `nativeGetPlayerProfile`'s payload. Null in → null out; garbage in → null out. */
        fun parse(json: String?): BlPlayerProfile? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                BlPlayerProfile(
                    steamId = o.optLong("steamId", 0L),
                    accountId = o.optInt("accountId", 0),
                    isSelf = o.optBoolean("isSelf", false),
                    // `level` absent == unknown. Only an explicitly present key is a real level.
                    level = if (o.has("level") && !o.isNull("level")) o.optInt("level") else null,
                    favoriteBadge = parseFavoriteBadge(o.optJSONObject("favoriteBadge")),
                    equipped = parseEquipped(o.optJSONObject("equipped")),
                    profileInfo = parseProfileInfo(o.optJSONObject("profileInfo")),
                    gamesPublic = o.optBoolean("gamesPublic", false),
                    ownedGameCount = optIntOrNull(o, "ownedGameCount"),
                    playtimeForeverMinutes = optLongOrNull(o, "playtimeForeverMinutes"),
                    playtimeTwoWeeksMinutes = optLongOrNull(o, "playtimeTwoWeeksMinutes"),
                    recentlyPlayed = parseRecentlyPlayed(o.optJSONArray("recentlyPlayed")),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "player profile parse failed: ${t.message}")
                null
            }
        }

        /**
         * Decode `nativeGetSteamLevels` into steamId -> level. Ids the CM declined are ABSENT from
         * the map, which is the whole point: absent means unknown, never level 0.
         */
        fun parseLevels(json: String?): Map<Long, Int> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val arr = JSONArray(json)
                val out = HashMap<Long, Int>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optLong("steamId", 0L).takeIf { it != 0L } ?: continue
                    if (!o.has("level") || o.isNull("level")) continue
                    out[id] = o.optInt("level")
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "steam levels parse failed: ${t.message}")
                emptyMap()
            }
        }

        fun parseFavoriteBadgeJson(json: String?): FavoriteBadge? =
            try { parseFavoriteBadge(json?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }) }
            catch (_: Throwable) { null }

        fun parseEquippedJson(json: String?): EquippedItems =
            try { parseEquipped(json?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }) }
            catch (_: Throwable) { EquippedItems.NONE }

        fun parseRecentlyPlayedJson(json: String?): List<RecentlyPlayed> =
            try { parseRecentlyPlayed(json?.takeIf { it.isNotBlank() }?.let { JSONArray(it) }) }
            catch (_: Throwable) { emptyList() }

        // ── section decoders ──────────────────────────────────────────────────────────────────

        private fun parseFavoriteBadge(o: JSONObject?): FavoriteBadge? {
            o ?: return null
            return FavoriteBadge(
                badgeId = o.optInt("badgeId", 0),
                communityItemId = o.optString("communityItemId", "").takeIf { it.isNotBlank() },
                itemType = o.optInt("itemType", 0),
                borderColor = o.optInt("borderColor", 0),
                appId = o.optInt("appId", 0),
                level = o.optInt("level", 0),
            )
        }

        private fun parseEquipped(o: JSONObject?): EquippedItems {
            o ?: return EquippedItems.NONE
            return EquippedItems(
                avatarFrame = parseItem(o.optJSONObject("avatarFrame")),
                profileBackground = parseItem(o.optJSONObject("profileBackground")),
                miniProfileBackground = parseItem(o.optJSONObject("miniProfileBackground")),
                animatedAvatar = parseItem(o.optJSONObject("animatedAvatar")),
                profileModifier = parseItem(o.optJSONObject("profileModifier")),
                steamDeckKeyboardSkin = parseItem(o.optJSONObject("steamDeckKeyboardSkin")),
            )
        }

        private fun parseItem(o: JSONObject?): ProfileItem? {
            o ?: return null
            val colors = ArrayList<ProfileColor>()
            o.optJSONArray("profileColors")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val style = c.optString("styleName", "")
                    val color = c.optString("color", "")
                    if (style.isNotBlank() || color.isNotBlank()) colors.add(ProfileColor(style, color))
                }
            }
            return ProfileItem(
                // Deliberately a String: u64 community item ids overflow JSON's Long-safe range.
                communityItemId = o.optString("communityItemId", "").takeIf { it.isNotBlank() },
                imageSmall = o.optString("imageSmall", "").takeIf { it.isNotBlank() },
                imageLarge = o.optString("imageLarge", "").takeIf { it.isNotBlank() },
                name = o.optString("name", "").takeIf { it.isNotBlank() },
                itemTitle = o.optString("itemTitle", "").takeIf { it.isNotBlank() },
                itemDescription = o.optString("itemDescription", "").takeIf { it.isNotBlank() },
                appId = o.optInt("appId", 0),
                itemType = o.optInt("itemType", 0),
                itemClass = o.optInt("itemClass", 0),
                movieWebm = o.optString("movieWebm", "").takeIf { it.isNotBlank() },
                movieMp4 = o.optString("movieMp4", "").takeIf { it.isNotBlank() },
                movieWebmSmall = o.optString("movieWebmSmall", "").takeIf { it.isNotBlank() },
                movieMp4Small = o.optString("movieMp4Small", "").takeIf { it.isNotBlank() },
                equippedFlags = o.optInt("equippedFlags", 0),
                tiled = o.optBoolean("tiled", false),
                profileColors = colors,
            )
        }

        private fun parseProfileInfo(o: JSONObject?): ProfileInfo? {
            o ?: return null
            return ProfileInfo(
                eresult = o.optInt("eresult", 0),
                timeCreated = o.optLong("timeCreated", 0L),
                realName = o.optString("realName", "").takeIf { it.isNotBlank() },
                cityName = o.optString("cityName", "").takeIf { it.isNotBlank() },
                stateName = o.optString("stateName", "").takeIf { it.isNotBlank() },
                countryName = o.optString("countryName", "").takeIf { it.isNotBlank() },
                headline = o.optString("headline", "").takeIf { it.isNotBlank() },
                summary = o.optString("summary", "").takeIf { it.isNotBlank() },
            )
        }

        private fun parseRecentlyPlayed(arr: JSONArray?): List<RecentlyPlayed> {
            arr ?: return emptyList()
            val out = ArrayList<RecentlyPlayed>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val appId = o.optInt("appId", 0).takeIf { it > 0 } ?: continue
                out.add(
                    RecentlyPlayed(
                        appId = appId,
                        name = o.optString("name", "").takeIf { it.isNotBlank() } ?: "App $appId",
                        playtimeTwoWeeks = o.optLong("playtimeTwoWeeks", 0L),
                        playtimeForever = o.optLong("playtimeForever", 0L),
                        imgIconUrl = o.optString("imgIconUrl", "").takeIf { it.isNotBlank() },
                        sortAs = o.optString("sortAs", "").takeIf { it.isNotBlank() },
                        rtimeLastPlayed = o.optLong("rtimeLastPlayed", 0L),
                    ),
                )
            }
            return out
        }

        private fun optIntOrNull(o: JSONObject, key: String): Int? =
            if (o.has(key) && !o.isNull(key)) o.optInt(key) else null

        private fun optLongOrNull(o: JSONObject, key: String): Long? =
            if (o.has(key) && !o.isNull(key)) o.optLong(key) else null
    }
}
