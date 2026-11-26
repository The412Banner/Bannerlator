package com.winlator.star.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * GOG titles in the app's language. The library call stays in English (downloads, art look-ups and
 * install folders use that name); when GOG knows the app's language a second, small call asks for the
 * translated title, which is what the library shows. The English one is kept per game for folders.
 */
object GogTitles {
    private const val PREFS = "bh_gog_prefs"
    private const val EN_PREFIX = "gog_title_en_"

    /**
     * The title to show for product [id] whose English title is [english]; remembers the English one
     * when they differ. BLOCKING. Any failure answers [english].
     */
    @JvmStatic
    fun localized(prefs: SharedPreferences, id: String, english: String, token: String?): String {
        val locale = StoreLang.gog()
        if (locale == "en-US") {
            if (prefs.contains(EN_PREFIX + id)) prefs.edit().remove(EN_PREFIX + id).apply()
            return english
        }
        val body = StoreNet.get("https://api.gog.com/products/$id?locale=$locale", bearer = token) ?: return english
        val title = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("title")?.optString("*")?.takeIf { it.isNotEmpty() } ?: o.optString("title")
        }.getOrNull()?.trim().orEmpty()
        if (title.isEmpty() || title == english) {
            if (prefs.contains(EN_PREFIX + id)) prefs.edit().remove(EN_PREFIX + id).apply()
            return english
        }
        prefs.edit().putString(EN_PREFIX + id, english).apply()
        return title
    }

    /** The English title of [game] (its install folder's fallback name), whatever language its title is in. */
    @JvmStatic
    fun english(ctx: Context, game: GogGame): String =
        ctx.getSharedPreferences(PREFS, 0).getString(EN_PREFIX + game.gameId, null)?.takeIf { it.isNotEmpty() } ?: game.title
}
