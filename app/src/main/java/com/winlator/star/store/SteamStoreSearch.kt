package com.winlator.star.store

import android.util.Log
import com.winlator.star.store.download.MediaImage
import com.winlator.star.store.download.MediaVideo
import com.winlator.star.store.download.StoreMedia
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin helper over Steam's public store endpoints, used by Smart Game Import to (a) turn a
 * best-effort search term into a list of candidate games the user can confirm, and (b) resolve
 * a Steam appId to its authoritative store name — so a shortcut identified only by a launcher
 * PE name ("Rockstar Games Launcher", a Goldberg "GSE" loader) can be corrected to the real
 * title, and the SGDB/Steam-CDN cover art matches the game rather than the launcher.
 *
 * Trimmed port of BannersComponentInjector's `SteamRepository` (search-by-name, cover-by-appId,
 * name-by-appId). Matches the house HTTP style ([StarLaunchBridge.httpGet]/`downloadBitmap`):
 * plain [HttpURLConnection] + org.json, no extra deps. Every method is BLOCKING network I/O and
 * MUST be called off the main thread; all failures degrade to null / an empty list, never throw.
 */
object SteamStoreSearch {

    private const val TAG = "SteamStoreSearch"

    /** A single Steam store search hit: the appId (master key) and its store name. */
    data class SteamSuggestion(val appId: Int, val name: String)

    /** Official 600x900 portrait "library" cover for [appId] (best for the Shortcuts grid tile). */
    fun coverUrl(appId: Int): String =
        "https://cdn.akamai.steamstatic.com/steam/apps/$appId/library_600x900.jpg"

    /** Landscape header image for [appId] — fallback thumbnail when the portrait cover 404s. */
    fun headerUrl(appId: Int): String =
        "https://cdn.akamai.steamstatic.com/steam/apps/$appId/header.jpg"

    /**
     * Searches the Steam store for [query]; returns up to 12 candidates (appId + name), best
     * match first. Empty on any failure or blank query. BLOCKING — call off the main thread.
     */
    fun searchByName(query: String): List<SteamSuggestion> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(term, "UTF-8")
        val json = httpGet(
            "https://store.steampowered.com/api/storesearch/?term=$encoded&l=english&cc=US",
        ) ?: return emptyList()
        return try {
            val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until minOf(items.length(), 12)) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optInt("id", -1).takeIf { it > 0 } ?: continue
                    val name = item.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                    add(SteamSuggestion(id, name))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchByName failed for \"$term\": ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolves [appId] to its authoritative Steam store name via the appdetails endpoint, or null
     * if the app is unknown / delisted / the request fails. BLOCKING — call off the main thread.
     */
    fun resolveName(appId: Int): String? {
        if (appId <= 0) return null
        val json = httpGet(
            "https://store.steampowered.com/api/appdetails?appids=$appId&filters=basic",
        ) ?: return null
        return try {
            val entry = JSONObject(json).optJSONObject(appId.toString()) ?: return null
            if (!entry.optBoolean("success", false)) return null
            entry.optJSONObject("data")?.optString("name", "")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "resolveName failed for $appId: ${e.message}")
            null
        }
    }

    /** Full store details for the Game Details page. */
    data class SteamGameDetails(
        val appId: Int,
        val name: String,
        val genres: List<String>,
        val releaseYear: String?,
        val metacritic: Int?,          // 1..100, null if unavailable
        val shortDescription: String?,
    )

    /**
     * Full store details for [appId] (name, genres, release year, metacritic, short description) via
     * the appdetails endpoint. null if unknown/delisted/failed. BLOCKING — call off the main thread.
     * Ported from BannersComponentInjector's `SteamRepository.fetch`.
     */
    fun fetchDetails(appId: Int): SteamGameDetails? {
        if (appId <= 0) return null
        val json = httpGet(
            "https://store.steampowered.com/api/appdetails?appids=$appId&filters=basic,genres,metacritic,release_date",
        ) ?: return null
        return try {
            val entry = JSONObject(json).optJSONObject(appId.toString()) ?: return null
            if (!entry.optBoolean("success", false)) return null
            val data = entry.optJSONObject("data") ?: return null
            val name = data.optString("name", "").takeIf { it.isNotBlank() } ?: return null
            val genres = buildList {
                data.optJSONArray("genres")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("description")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
            val releaseYear = data.optJSONObject("release_date")?.optString("date", "")
                ?.let { Regex("""\b(?:19|20)\d{2}\b""").find(it)?.value }
            val metacritic = data.optJSONObject("metacritic")?.optInt("score", -1)?.takeIf { it in 1..100 }
            // Steam's short_description carries HTML entities/tags (&amp;, &#39;, <br>) — decode to plain text.
            val shortDesc = data.optString("short_description", "").takeIf { it.isNotBlank() }?.let(::decodeHtml)
            SteamGameDetails(appId, name, genres, releaseYear, metacritic, shortDesc)
        } catch (e: Exception) {
            Log.w(TAG, "fetchDetails failed for $appId: ${e.message}")
            null
        }
    }

    /**
     * Screenshots + trailers for [appId] from the appdetails endpoint (`cc` = [cc], english) — the
     * Media tab's source. Three distinct answers, because the storefront rate limit (~200 requests
     * per 5 minutes) must not be mistaken for "no media":
     *  - a NON-EMPTY [StoreMedia] when Steam published any;
     *  - [StoreMedia.EMPTY] when the app genuinely has none (or is unknown / delisted, `success:false`);
     *  - null when the fetch could NOT tell — HTTP 429, any other non-2xx, a transport failure or a
     *    malformed body. Callers cache null only briefly and retry on the next open.
     * BLOCKING — call off the main thread.
     */
    fun fetchMedia(appId: Int, cc: String): StoreMedia? {
        if (appId <= 0) return StoreMedia.EMPTY
        val ccParam = cc.takeIf { it.length == 2 }?.uppercase() ?: "US"
        val url = "https://store.steampowered.com/api/appdetails?appids=$appId&cc=$ccParam&l=english" +
            "&filters=screenshots,movies"
        return when (val out = httpGetDetailed(url)) {
            is HttpOutcome.Ok -> parseMedia(out.body, appId)
            is HttpOutcome.HttpError -> {
                Log.w(TAG, "fetchMedia($appId): HTTP ${out.code}" + if (out.code == 429) " (rate limited)" else "")
                null
            }
            is HttpOutcome.Transport -> null
            HttpOutcome.EmptyBody -> null
        }
    }

    /**
     * Pure parse of an appdetails body for [appId] (no I/O, no logging — unit-tested). null =
     * malformed body, treated like a transport failure by [fetchMedia]. Screenshots: `path_thumbnail`
     * for the strip, `path_full` for the viewer, capped at [StoreMedia.MAX_SCREENSHOTS]. Movies: the
     * mp4 480p rendition by default (max / webm as fallbacks — see [pickMovieUrl]); every URL is
     * forced to https because the CDN hands out `http://video.akamai…` and the app permits cleartext
     * only to Steam CONTENT hosts.
     */
    internal fun parseMedia(body: String, appId: Int): StoreMedia? {
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val entry = root.optJSONObject(appId.toString()) ?: return null
        if (!entry.optBoolean("success", false)) return StoreMedia.EMPTY
        // With `filters=` Steam answers `"data": []` (an ARRAY) when nothing matched the filter.
        val data = entry.optJSONObject("data") ?: return StoreMedia.EMPTY
        val shots = ArrayList<MediaImage>()
        data.optJSONArray("screenshots")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (shots.size >= StoreMedia.MAX_SCREENSHOTS) break
                val s = arr.optJSONObject(i) ?: continue
                val full = httpsOf(s.optString("path_full"))
                val thumb = httpsOf(s.optString("path_thumbnail")).ifBlank { full }
                if (full.isNotBlank()) shots.add(MediaImage(thumb, full))
            }
        }
        val videos = ArrayList<MediaVideo>()
        data.optJSONArray("movies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val url = pickMovieUrl(m) ?: continue
                val name = m.optString("name").trim().ifBlank { "Trailer ${videos.size + 1}" }
                videos.add(MediaVideo.Direct(url, httpsOf(m.optString("thumbnail")).ifBlank { null }, name))
            }
        }
        return StoreMedia(shots, videos)
    }

    /** mp4 480 (default, light on mobile data) → mp4 max → webm 480 → webm max. */
    private fun pickMovieUrl(m: JSONObject): String? {
        val mp4 = m.optJSONObject("mp4")
        val webm = m.optJSONObject("webm")
        return listOf(mp4?.optString("480"), mp4?.optString("max"), webm?.optString("480"), webm?.optString("max"))
            .firstOrNull { !it.isNullOrBlank() }
            ?.let(::httpsOf)
    }

    private fun httpsOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
    }

    /** Decode HTML entities/tags to plain text (Steam descriptions contain &amp;, &#39;, <br>, …). */
    private fun decodeHtml(s: String): String =
        try {
            android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } catch (_: Throwable) {
            s
        }

    /**
     * Why a request didn't produce a body. A caller that only wants the body uses [httpGet]; a
     * caller that must tell a rate limit from a timeout from an empty answer uses
     * [httpGetDetailed]. There is still exactly ONE request implementation underneath.
     */
    internal sealed interface HttpOutcome {
        data class Ok(val body: String) : HttpOutcome

        /** A completed request with a non-2xx status. [code] 429 is Steam's rate limiter. */
        data class HttpError(val code: Int) : HttpOutcome

        /** The request never completed — timeout, DNS, TLS, no route. [kind] is the exception class. */
        data class Transport(val kind: String, val message: String?) : HttpOutcome

        /** 2xx with an empty body. */
        data object EmptyBody : HttpOutcome
    }

    /** GET [urlStr] as a UTF-8 string, or null on any non-2xx / failure.
     *  `internal` so [SteamStoreCatalog] — the storefront's rails + priced search — rides the exact
     *  same timeouts/headers/failure semantics instead of growing a second HTTP style. */
    internal fun httpGet(urlStr: String): String? =
        (httpGetDetailed(urlStr) as? HttpOutcome.Ok)?.body

    /**
     * The same request as [httpGet], reporting the OUTCOME rather than collapsing every failure to
     * null. Added because the storefront's `appdetails` rung could not tell "Steam rate-limited us"
     * from "this app genuinely has no art" — four very different findings that all printed the same
     * line. Callers that don't care keep using [httpGet].
     */
    internal fun httpGetDetailed(urlStr: String): HttpOutcome {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                conn.disconnect()
                Log.w(TAG, "HTTP $code for $urlStr")
                return HttpOutcome.HttpError(code)
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            if (text.isBlank()) HttpOutcome.EmptyBody else HttpOutcome.Ok(text)
        } catch (e: Exception) {
            Log.w(TAG, "httpGet failed: ${e.message}")
            HttpOutcome.Transport(e.javaClass.simpleName, e.message)
        }
    }
}
