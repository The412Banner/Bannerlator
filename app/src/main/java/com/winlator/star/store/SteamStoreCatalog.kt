package com.winlator.star.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The storefront's read-only catalog client: the three Store-tab rails and the priced search list.
 *
 * Two of Steam's **undocumented** public store endpoints back this:
 *  - `store.steampowered.com/api/featuredcategories/?cc=<CC>&l=english` — `specials`,
 *    `new_releases`, `top_sellers`, `coming_soon`, each a `{ items: [...] }` bag.
 *  - `store.steampowered.com/api/storesearch/?term=…&cc=<CC>` — the same endpoint
 *    [SteamStoreSearch.searchByName] already uses, read here for its `price` block too.
 *
 * Because they are undocumented they are treated as *decoration*, never as load-bearing data:
 * every call is best-effort, returns null / an empty list on any failure, and NEVER throws. The
 * Store tab is required to stay usable (retry affordance, Library still one tap away) when this
 * returns nothing — see `SteamStoreTab`.
 *
 * Region: `cc` comes from [SteamRegion.storeCountryCode] so prices are in the account's currency.
 * Results are cached in-process per `cc` for [FEATURED_TTL_MS] (rails) / [SEARCH_TTL_MS] (queries),
 * so tab switches, rotation and back-navigation never re-hit the network.
 *
 * House HTTP style: [SteamStoreSearch.httpGet] (plain HttpURLConnection + org.json, no new deps).
 * Every method is a `suspend` that hops to [Dispatchers.IO] itself, so callers stay non-blocking.
 */
object SteamStoreCatalog {

    private const val TAG = StorefrontLog.STORE

    private const val FEATURED_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 5 * 60 * 1000L

    /** Price unknown (the endpoint omitted the block) — the UI then renders no price row at all. */
    const val PRICE_UNKNOWN = -1

    /**
     * One catalog entry, whether it came from a rail or from search. [finalCents]/[originalCents]
     * are in the currency's minor unit as Steam reports them; [PRICE_UNKNOWN] when absent.
     */
    data class StoreItem(
        val appId: Int,
        val name: String,
        val currency: String,
        val finalCents: Int,
        val originalCents: Int,
        val discountPercent: Int,
        /** Free-to-play: a price block that exists and is zero. */
        val isFree: Boolean,
        /** Short "Genre · Genre" line for the search rows; blank when we never fetched details. */
        val tags: String = "",
        /**
         * The capsule image URL Steam's OWN response carried for this item, or null when the
         * response had none. Always preferred over building one from [appId]: the app-id route
         * hard-codes a CDN host, and the legacy host 404s for recent releases. See
         * `capsuleCandidates` for the full fallback order.
         */
        val artUrl: String? = null,
    ) {

        val hasPrice: Boolean get() = finalCents != PRICE_UNKNOWN
        val isDiscounted: Boolean get() = discountPercent > 0 && originalCents > finalCents
    }

    /**
     * The Store tab's three rails plus the hero pick. Any list may be empty — the Store tab hides
     * an empty rail rather than showing a hole.
     *
     * Rail sourcing (honest mapping, since `featuredcategories` has no "free" category):
     *  - [newReleases] ← `new_releases`
     *  - [topFree]     ← a dedicated `maxprice=free` store-search query ([freeGames]). Harvesting
     *                    zero-priced items out of the other categories was tried first and returned
     *                    exactly ONE title on device — `featuredcategories` has no free category
     *                    worth the name. The harvest survives only as the fallback.
     *  - [specials]    ← `specials`
     *  - [hero]        ← the deepest discount in `specials`, else the first new release.
     */
    data class Featured(
        val newReleases: List<StoreItem>,
        val topFree: List<StoreItem>,
        val specials: List<StoreItem>,
    ) {
        val hero: StoreItem?
            get() = specials.maxByOrNull { it.discountPercent } ?: newReleases.firstOrNull()

        val isEmpty: Boolean
            get() = newReleases.isEmpty() && topFree.isEmpty() && specials.isEmpty()
    }

    /**
     * Below this, the "Top Free Games" rail is hidden rather than shipped as a one- or two-item row.
     * The device build shipped a single-item rail; a rail that thin reads as broken, and an honest
     * absence is better than a token one.
     */
    private const val MIN_FREE_RAIL = 4

    private data class Cached<T>(val at: Long, val value: T)

    private val featuredCache = ConcurrentHashMap<String, Cached<Featured>>()
    private val searchCache = ConcurrentHashMap<String, Cached<List<StoreItem>>>()
    private val freeCache = ConcurrentHashMap<String, Cached<List<StoreItem>>>()

    /**
     * SteamGridDB last-resort capsule art, keyed by appId. The value is nullable ON PURPOSE: null is
     * a NEGATIVE cache entry ("SteamGridDB has nothing for this app"), which matters as much as a
     * hit here — the device run had 36 artless apps, and without negative caching every one of them
     * would re-hit SteamGridDB on each scroll and each recomposition.
     */
    private val sgdbCache = ConcurrentHashMap<Int, Cached<String?>>()

    /** A found URL is stable; re-checking it daily is plenty. */
    private const val SGDB_HIT_TTL_MS = 24 * 60 * 60 * 1000L

    /** A miss expires sooner: SteamGridDB is community-contributed, so art appears over time. */
    private const val SGDB_MISS_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * The three rails for [cc]. Served from the in-process cache while fresh; otherwise fetched.
     * Returns null when the endpoint could not be read at all AND nothing is cached — the caller
     * shows an inline retry. [force] bypasses the TTL (the retry button).
     */
    suspend fun featured(ctx: Context, force: Boolean = false): Featured? {
        val cc = SteamRegion.storeCountryCode(ctx)
        if (!force) {
            featuredCache[cc]?.let {
                if (System.currentTimeMillis() - it.at < FEATURED_TTL_MS) {
                    StorefrontLog.i(TAG, "featured($cc): cache hit")
                    return it.value
                }
            }
        }
        return withContext(Dispatchers.IO) {
            val url = "https://store.steampowered.com/api/featuredcategories/?cc=$cc&l=english"
            val t0 = System.currentTimeMillis()
            StorefrontLog.i(TAG, "featured($cc): GET $url force=$force")
            val json = SteamStoreSearch.httpGet(url)
            val ms = System.currentTimeMillis() - t0
            if (json == null) {
                // Undocumented endpoint: a null here is a non-2xx, a timeout, or a transport error
                // (SteamStoreSearch logs the HTTP code under its own tag). #1 suspect for a blank
                // Store tab — never let it pass silently.
                StorefrontLog.w(
                    TAG,
                    "featured($cc): NO RESPONSE after ${ms}ms — url=$url; " +
                        "falling back to cache (${StorefrontLog.has(featuredCache[cc])})",
                )
                return@withContext featuredCache[cc]?.value
            }
            if (json.isBlank()) {
                StorefrontLog.w(TAG, "featured($cc): EMPTY BODY after ${ms}ms — url=$url")
                return@withContext featuredCache[cc]?.value
            }
            try {
                val root = JSONObject(json)
                val newReleases = itemsOf(root, "new_releases")
                val specials = itemsOf(root, "specials")
                val topSellers = itemsOf(root, "top_sellers")
                // The real free-games source. Falls back to harvesting zero-priced items out of
                // the other categories, which is what produced the one-item rail on device.
                val queried = freeGames(cc)
                val harvested = (topSellers + newReleases + specials)
                    .filter { it.isFree }
                    .distinctBy { it.appId }
                val free = (queried + harvested).distinctBy { it.appId }
                if (free.size < MIN_FREE_RAIL) {
                    StorefrontLog.i(
                        TAG,
                        "featured($cc): only ${free.size} free title(s) " +
                            "(query=${queried.size}, harvest=${harvested.size}) — below the " +
                            "$MIN_FREE_RAIL minimum, hiding the Top Free rail rather than shipping a stub",
                    )
                }
                val result = Featured(
                    newReleases = newReleases.filterNot { it.isFree }.ifEmpty { newReleases },
                    topFree = if (free.size >= MIN_FREE_RAIL) free else emptyList(),
                    specials = specials.filter { it.isDiscounted },
                )
                // An all-empty parse means Steam answered with something we don't understand —
                // keep whatever we had rather than replacing a good cache with nothing.
                if (result.isEmpty) {
                    StorefrontLog.w(
                        TAG,
                        "featured($cc): PARSED EMPTY (${json.length} bytes, ${ms}ms) — Steam answered " +
                            "with a shape we don't understand; keeping cache (${StorefrontLog.has(featuredCache[cc])})",
                    )
                    return@withContext featuredCache[cc]?.value
                }
                featuredCache[cc] = Cached(System.currentTimeMillis(), result)
                StorefrontLog.i(
                    TAG,
                    "featured($cc): OK in ${ms}ms — ${result.newReleases.size} new, " +
                        "${result.topFree.size} free, ${result.specials.size} specials, " +
                        "hero=${result.hero?.appId ?: 0}",
                )
                result
            } catch (e: Exception) {
                StorefrontLog.w(TAG, "featured($cc): MALFORMED JSON (${json.length} bytes) — ${e.javaClass.simpleName}: ${e.message}")
                featuredCache[cc]?.value
            }
        }
    }

    /**
     * Priced storefront search for [query] — the vertical result list. Up to 20 hits, best match
     * first, empty on any failure or a blank term. Cached per (cc, term) for [SEARCH_TTL_MS] so
     * back-spacing through a query doesn't re-hit the endpoint for terms already typed.
     */
    suspend fun search(ctx: Context, query: String): List<StoreItem> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        val cc = SteamRegion.storeCountryCode(ctx)
        val key = "$cc ${term.lowercase(Locale.ROOT)}"
        searchCache[key]?.let {
            if (System.currentTimeMillis() - it.at < SEARCH_TTL_MS) {
                StorefrontLog.i(TAG, "search($cc, \"$term\"): cache hit, ${it.value.size} hit(s)")
                return it.value
            }
        }
        return withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = "https://store.steampowered.com/api/storesearch/?term=$encoded&l=english&cc=$cc"
            val t0 = System.currentTimeMillis()
            val json = SteamStoreSearch.httpGet(url)
            val ms = System.currentTimeMillis() - t0
            if (json.isNullOrBlank()) {
                StorefrontLog.w(TAG, "search($cc, \"$term\"): NO RESPONSE after ${ms}ms — url=$url")
                return@withContext emptyList()
            }
            try {
                val items = JSONObject(json).optJSONArray("items") ?: return@withContext emptyList()
                val out = ArrayList<StoreItem>(minOf(items.length(), 20))
                for (i in 0 until minOf(items.length(), 20)) {
                    parseSearchItem(items.optJSONObject(i) ?: continue)?.let(out::add)
                }
                searchCache[key] = Cached(System.currentTimeMillis(), out)
                StorefrontLog.i(TAG, "search($cc, \"$term\"): OK in ${ms}ms — ${out.size} hit(s)")
                out
            } catch (e: Exception) {
                StorefrontLog.w(TAG, "search($cc, \"$term\"): MALFORMED JSON (${json.length} bytes) — ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Top free-to-play games for [cc] via Steam's store-search backend with `maxprice=free`.
     *
     * `featuredcategories` genuinely has no free category — on device it yielded ONE title — so this
     * is the rail's real source. Undocumented like the rest, hence the same contract: empty list on
     * ANY failure, never an exception, and the Store tab stays usable without it.
     *
     * `category1=998` is Steam's "Games" category (excludes DLC/software/soundtracks). The response
     * items carry no explicit appId, but `logo` is always `…/apps/<appid>/<file>`, so the id comes
     * from there and doubles as proof of which CDN host serves that app's art.
     */
    private fun freeGames(cc: String): List<StoreItem> {
        freeCache[cc]?.let { if (System.currentTimeMillis() - it.at < FEATURED_TTL_MS) return it.value }
        val url = "https://store.steampowered.com/search/results/?query&start=0&count=30" +
            "&maxprice=free&category1=998&supportedlang=english&cc=$cc&l=english&json=1"
        val t0 = System.currentTimeMillis()
        val json = SteamStoreSearch.httpGet(url)
        val ms = System.currentTimeMillis() - t0
        if (json.isNullOrBlank()) {
            StorefrontLog.w(TAG, "freeGames($cc): NO RESPONSE after ${ms}ms — url=$url")
            return emptyList()
        }
        return try {
            val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
            val out = ArrayList<StoreItem>(items.length())
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                val logo = o.optString("logo", "")
                val appId = APP_ID_IN_URL.find(logo)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                out.add(
                    StoreItem(
                        appId = appId,
                        name = name,
                        currency = "",
                        // The query itself constrained this to free titles, so the price is known
                        // without trusting the response's price shape (which varies by region).
                        finalCents = 0,
                        originalCents = 0,
                        discountPercent = 0,
                        isFree = true,
                        artUrl = headerFromThumb(logo),
                    ),
                )
            }
            val deduped = out.distinctBy { it.appId }
            freeCache[cc] = Cached(System.currentTimeMillis(), deduped)
            StorefrontLog.i(TAG, "freeGames($cc): OK in ${ms}ms — ${deduped.size} free title(s)")
            deduped
        } catch (e: Exception) {
            StorefrontLog.w(TAG, "freeGames($cc): MALFORMED JSON (${json.length} bytes) — ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /** `…/apps/<appid>/…` — the only place a store-search result exposes its appId. */
    private val APP_ID_IN_URL = Regex("""/apps/(\d+)/""")

    /**
     * LAST-RESORT capsule art for [appId] from SteamGridDB, or null when it has none.
     *
     * Only ever called once every Steam CDN candidate has already failed — see `capsuleCandidates`
     * and `StoreCapsule`. It therefore adds NO latency to the common case: an app whose Steam art
     * resolves never reaches this function.
     *
     * Delegates to [StarLaunchBridge.sgdbFetchCapsuleBySteamAppId], which is the app's single
     * SteamGridDB HTTP path and holds the only copy of the key — nothing about the token crosses
     * into Kotlin. Both hits and misses are cached ([sgdbCache]); failure is always null, never an
     * exception, so the caller falls through to the themed placeholder exactly as before.
     */
    suspend fun sgdbCapsule(ctx: Context, appId: Int): String? {
        if (appId <= 0) return null
        sgdbCache[appId]?.let {
            val ttl = if (it.value != null) SGDB_HIT_TTL_MS else SGDB_MISS_TTL_MS
            if (System.currentTimeMillis() - it.at < ttl) return it.value
        }
        return withContext(Dispatchers.IO) {
            val url = try {
                StarLaunchBridge.sgdbFetchCapsuleBySteamAppId(ctx.applicationContext, appId)
                    ?.takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                StorefrontLog.w(TAG, "sgdbCapsule($appId) threw — treating as no art", t)
                null
            }
            sgdbCache[appId] = Cached(System.currentTimeMillis(), url)
            url
        }
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────────────

    /** `featuredcategories` puts each rail under `<key>.items`; a missing/odd shape yields []. */
    private fun itemsOf(root: JSONObject, key: String): List<StoreItem> {
        val arr: JSONArray = root.optJSONObject(key)?.optJSONArray("items")
            ?: root.optJSONArray(key)
            ?: return emptyList()
        val out = ArrayList<StoreItem>(arr.length())
        for (i in 0 until arr.length()) {
            parseFeaturedItem(arr.optJSONObject(i) ?: continue)?.let(out::add)
        }
        return out
    }

    /**
     * A `featuredcategories` item. Prices are flat keys here (`final_price`, `original_price`,
     * `discount_percent`, `currency`). A zero `final_price` with the key present means free-to-play;
     * an absent key means "we don't know", which renders as no price row rather than "$0.00".
     */
    private fun parseFeaturedItem(o: JSONObject): StoreItem? {
        val appId = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: return null
        val hasPrice = o.has("final_price")
        val finalCents = if (hasPrice) o.optInt("final_price", 0) else PRICE_UNKNOWN
        val originalCents = if (o.has("original_price")) o.optInt("original_price", finalCents) else finalCents
        // Steam hands us the art. Prefer the widest capsule (closest to the card's 92:43) and fall
        // back through the narrower ones before letting the UI construct anything from the appId.
        val art = sequenceOf("header_image", "large_capsule_image", "small_capsule_image", "capsule_image")
            .map { o.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
        return StoreItem(
            appId = appId,
            name = name,
            currency = o.optString("currency", "").ifBlank { "USD" },
            finalCents = finalCents,
            originalCents = originalCents,
            discountPercent = o.optInt("discount_percent", 0),
            isFree = hasPrice && finalCents == 0,
            artUrl = art,
        )
    }

    /**
     * A `storesearch` item. Here the price lives in a nested `price` object
     * (`{currency, initial, final}`) and the discount in a sibling `discount_percent`. Free titles
     * omit `price` entirely, but carry `"price": {"final": 0}` in some regions — both are handled.
     * `platforms.windows` gates the row: this is a Windows emulator, a Mac-only title is noise.
     */
    private fun parseSearchItem(o: JSONObject): StoreItem? {
        val appId = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: return null
        val price = o.optJSONObject("price")
        val hasPrice = price != null && price.has("final")
        val finalCents = if (hasPrice) price!!.optInt("final", 0) else PRICE_UNKNOWN
        val initialCents = if (hasPrice) price!!.optInt("initial", finalCents) else finalCents
        // storesearch reports discount as a percentage on the item, not inside `price`.
        val discount = o.optInt("discount_percent", 0).takeIf { it > 0 }
            ?: if (hasPrice && initialCents > finalCents && initialCents > 0)
                ((initialCents - finalCents) * 100.0 / initialCents).toInt()
            else 0
        // `price == null` on storesearch means free-to-play far more often than "unknown", so a
        // Windows title with no price block reads as Free — matching what the web store shows.
        val free = (hasPrice && finalCents == 0) || (price == null && o.has("platforms"))
        return StoreItem(
            appId = appId,
            name = name,
            currency = price?.optString("currency", "")?.ifBlank { "USD" } ?: "USD",
            finalCents = if (free) 0 else finalCents,
            originalCents = if (free) 0 else initialCents,
            discountPercent = discount,
            isFree = free,
            tags = platformTags(o),
            artUrl = headerFromThumb(o.optString("tiny_image", "")),
        )
    }

    /**
     * Turn a Steam thumbnail URL into the full-size `header.jpg` on the SAME host.
     *
     * Search responses only carry a small capsule (`tiny_image` / `capsule_sm_120.jpg`), which is
     * far too low-res for a 176dp card — but the URL proves which CDN host actually serves this
     * app's art, which is exactly the fact the app-id route gets wrong. Swapping the filename keeps
     * the good host. Null when the input doesn't look like a Steam app-art URL.
     */
    private fun headerFromThumb(thumb: String): String? {
        if (thumb.isBlank()) return null
        val base = thumb.substringBefore('?')
        val slash = base.lastIndexOf('/')
        if (slash <= 0) return null
        return base.substring(0, slash + 1) + "header.jpg"
    }

    /** "Windows · Controller"-style hint line for a search row; blank when nothing is known. */
    private fun platformTags(o: JSONObject): String {
        val parts = ArrayList<String>(3)
        o.optJSONObject("platforms")?.let { p ->
            if (p.optBoolean("windows", false)) parts.add("Windows")
        }
        o.optString("controller_support", "").takeIf { it.isNotBlank() }?.let {
            parts.add(if (it.equals("full", ignoreCase = true)) "Full controller" else "Partial controller")
        }
        o.optJSONObject("metascore")?.optInt("score", 0)?.takeIf { it in 1..100 }?.let { parts.add("Metacritic $it") }
        return parts.joinToString("  ·  ")
    }

    // ── formatting ────────────────────────────────────────────────────────────────────────────

    /**
     * Minor-unit [cents] in [currencyCode] as a localised price string ("$12.49", "¥1,980").
     * Falls back to a plain two-decimal render when the code is unknown to the JDK, so an odd
     * Steam currency can never crash a card.
     */
    fun formatPrice(cents: Int, currencyCode: String): String {
        if (cents == PRICE_UNKNOWN) return ""
        return try {
            val currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT))
            val digits = currency.defaultFractionDigits.coerceAtLeast(0)
            val fmt = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                this.currency = currency
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }
            fmt.format(cents / Math.pow(10.0, digits.toDouble()))
        } catch (_: Exception) {
            "%.2f %s".format(Locale.getDefault(), cents / 100.0, currencyCode)
        }
    }
}
