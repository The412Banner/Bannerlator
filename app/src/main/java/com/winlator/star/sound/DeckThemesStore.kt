package com.winlator.star.sound

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The DeckThemes sound pack store (api.deckthemes.com), the one Audio Loader (DeckThemes/SDH-AudioLoader,
 * MIT, Copyright (c) 2022 EMERALD0874) browses on a Steam Deck. Public, no sign-in. Blocking calls.
 */
object DeckThemesStore {
    const val API = "https://api.deckthemes.com"

    /** Sort orders the store accepts. */
    val ORDERS = listOf("Most Downloaded", "Most Stars", "Last Updated", "Alphabetical (A to Z)")

    class Item(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val manifestVersion: Int,
        val downloadId: String,
        val downloads: Int,
        val stars: Int,
        val imageId: String?,
    ) {
        val imageUrl: String? get() = imageId?.let { "$API/blobs/$it" }
        val downloadUrl: String get() = "$API/blobs/$downloadId"
    }

    class Page(val total: Int, val items: List<Item>)

    /** One page of sound packs (music packs are left out). */
    fun search(query: String, order: String, page: Int, perPage: Int = 30): Page = parse(searchJson(query, order, page, perPage))

    /** Every sound pack in one answer (the store has a few hundred), most downloaded first, as raw JSON for caching. */
    fun allJson(): String = searchJson("", ORDERS.first(), 1, 500)

    private fun searchJson(query: String, order: String, page: Int, perPage: Int): String {
        val q = buildString {
            append("$API/themes?filters=").append(enc("AUDIO.Audio"))
            append("&order=").append(enc(order))
            append("&page=").append(page).append("&perPage=").append(perPage)
            if (query.isNotBlank()) append("&search=").append(enc(query.trim()))
        }
        return get(q)
    }

    /** Reads a store answer ([search] / [allJson]). */
    fun parse(json: String): Page {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items")
        val items = ArrayList<Item>()
        if (arr != null) for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val dl = t.optJSONObject("download") ?: continue
            val dlId = dl.optString("id").takeIf { it.isNotBlank() } ?: continue
            val image = t.optJSONArray("images")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            items += Item(
                id = t.optString("id"),
                name = t.optString("displayName").ifBlank { t.optString("name") },
                author = t.optString("specifiedAuthor").ifBlank { t.optJSONObject("author")?.optString("username").orEmpty() },
                version = t.optString("version"),
                manifestVersion = t.optInt("manifestVersion", 1),
                downloadId = dlId,
                downloads = dl.optInt("downloadCount"),
                stars = t.optInt("starCount"),
                imageId = image,
            )
        }
        return Page(root.optInt("total", items.size), items)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Bannerlator")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) throw IOException("store answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
