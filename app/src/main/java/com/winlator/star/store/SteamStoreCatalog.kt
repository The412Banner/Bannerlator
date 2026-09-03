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
    ) {
        /** 460x215 store capsule — the 92:43 art the cards are laid out around. */
        val capsuleUrl: String get() = SteamStoreSearch.headerUrl(appId)

        val hasPrice: Boolean get() = finalCents != PRICE_UNKNOWN
        val isDiscounted: Boolean get() = discountPercent > 0 && originalCents > finalCents
    }

    /**
     * The Store tab's three rails plus the hero pick. Any list may be empty — the Store tab hides
     * an empty rail rather than showing a hole.
     *
     * Rail sourcing (honest mapping, since `featuredcategories` has no "free" category):
     *  - [newReleases] ← `new_releases`
     *  - [topFree]     ← every zero-priced item across `top_sellers` + `new_releases` + `specials`
     *                    (CS2, Dota 2, Apex … are perennial top sellers), de-duplicated. Empty is
     *                    a normal outcome and simply hides the rail.
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

    private data class Cached<T>(val at: Long, val value: T)

    private val featuredCache = ConcurrentHashMap<String, Cached<Featured>>()
    private val searchCache = ConcurrentHashMap<String, Cached<List<StoreItem>>>()

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
                val free = (topSellers + newReleases + specials)
                    .filter { it.isFree }
                    .distinctBy { it.appId }
                val result = Featured(
                    newReleases = newReleases.filterNot { it.isFree }.ifEmpty { newReleases },
                    topFree = free,
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
        return StoreItem(
            appId = appId,
            name = name,
            currency = o.optString("currency", "").ifBlank { "USD" },
            finalCents = finalCents,
            originalCents = originalCents,
            discountPercent = o.optInt("discount_percent", 0),
            isFree = hasPrice && finalCents == 0,
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
        )
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
