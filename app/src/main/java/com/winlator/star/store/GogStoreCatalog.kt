package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * GOG's PUBLIC catalog — the same unauthenticated service the gog.com store page and Heroic use:
 *
 *   GET https://catalog.gog.com/v1/catalog?limit=…&order=desc:trending&productType=in:game,pack
 *       &page=1&countryCode=US&locale=en-US&currencyCode=USD
 *       [&query=like:<term>] [&discounted=eq:true] [&price=between:0,0]
 *
 * Verified live (2026-09-04): `products[]` carry `id`, `title`, `slug`, `coverHorizontal`,
 * `coverVertical`, `developers[]`, `genres[{name}]`, `operatingSystems[]`, `releaseDate`
 * ("YYYY.MM.DD"), `storeLink`, `reviewsRating` and a `price` object with PRE-FORMATTED `final` /
 * `base` strings plus `finalMoney.amount` / `baseMoney.amount` and `discount` ("-85%"). Products
 * that are not for sale have `price == null`.
 *
 * Product detail (description / screenshots) comes from the equally public
 * `api.gog.com/products/{id}?expand=description,screenshots`.
 *
 * Everything is cached in-process (rails 30 min, searches 5 min) and the last good rails are
 * mirrored to SharedPreferences so the Store tab paints instantly — and offline — on the next open.
 * Undocumented endpoints: cache hard, degrade gracefully, never hard-fail the screen.
 */
object GogStoreCatalog {

    private const val TAG = "GogStore"
    private const val CATALOG = "https://catalog.gog.com/v1/catalog"
    private const val FEATURED_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 5 * 60 * 1000L
    private const val DETAIL_TTL_MS = 60 * 60 * 1000L
    private const val PREFS = "gog_store_cache"
    private const val KEY_FEATURED = "featured_json"
    private const val KEY_FEATURED_AT = "featured_at"

    private val supportedCurrencies = setOf(
        "USD", "EUR", "GBP", "AUD", "CAD", "CHF", "DKK", "NOK", "PLN", "SEK", "BRL", "CNY",
        "HKD", "ILS", "JPY", "KRW", "MXN", "NZD", "SGD", "TRY", "UAH", "ZAR", "INR", "RUB",
    )

    class Featured(
        val hero: CatalogItem?,
        val trending: List<CatalogItem>,
        val newReleases: List<CatalogItem>,
        val deals: List<CatalogItem>,
        val free: List<CatalogItem>,
    ) {
        val isEmpty: Boolean
            get() = hero == null && trending.isEmpty() && newReleases.isEmpty() && deals.isEmpty() && free.isEmpty()
    }

    class ProductDetail(
        val lead: String,
        val full: String,
        val screenshots: List<String>,
        val releaseDate: String,
        val background: String?,
        val logo: String?,
    )

    private class Cached<T>(val value: T, val at: Long)

    private var featuredCache: Cached<Featured>? = null
    private val searchCache = ConcurrentHashMap<String, Cached<List<CatalogItem>>>()
    private val detailCache = ConcurrentHashMap<String, Cached<ProductDetail?>>()

    // ── Region ────────────────────────────────────────────────────────────────────────────────

    private fun country(): String =
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"

    private fun currency(): String {
        val cc = runCatching { Currency.getInstance(Locale("", country())).currencyCode }.getOrNull()
        return if (cc != null && cc in supportedCurrencies) cc else "USD"
    }

    private fun baseQuery(limit: Int, order: String, extra: String = ""): String =
        "$CATALOG?limit=$limit&order=$order&productType=in:game,pack&page=1" +
            "&countryCode=${country()}&locale=en-US&currencyCode=${currency()}$extra"

    /** GET the catalog; on a refusal of the locale currency, retry as US / USD. */
    private fun fetchProducts(url: String): List<CatalogItem> {
        var body = StoreNet.get(url)
        if (body == null && !url.contains("countryCode=US&locale=en-US&currencyCode=USD")) {
            val fallback = url
                .replace(Regex("countryCode=[A-Z]{2}"), "countryCode=US")
                .replace(Regex("currencyCode=[A-Z]{3}"), "currencyCode=USD")
            body = StoreNet.get(fallback)
        }
        if (body == null) return emptyList()
        return parseProducts(body)
    }

    // ── Rails ─────────────────────────────────────────────────────────────────────────────────

    /** The Store tab's rails. Null only when EVERY feed failed AND nothing is cached. */
    suspend fun featured(ctx: Context, force: Boolean = false): Featured? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        featuredCache?.let { if (!force && now - it.at < FEATURED_TTL_MS) return@withContext it.value }

        val trending = fetchProducts(baseQuery(24, "desc:trending"))
        val newest = fetchProducts(baseQuery(40, "desc:releaseDate")).filter { !isFuture(it.releaseDate) }.take(24)
        val deals = fetchProducts(baseQuery(24, "desc:discount", "&discounted=eq:true"))
        val free = fetchProducts(baseQuery(24, "desc:trending", "&price=between:0,0"))

        val hero = trending.firstOrNull { !it.imageUrl.isNullOrBlank() } ?: deals.firstOrNull()
        val result = Featured(
            hero = hero,
            trending = trending.filter { it.id != hero?.id },
            newReleases = newest,
            deals = deals,
            free = free,
        )
        if (!result.isEmpty) {
            featuredCache = Cached(result, now)
            persistFeatured(ctx, result)
            Log.i(TAG, "rails: trending=${trending.size} new=${newest.size} deals=${deals.size} free=${free.size}")
            return@withContext result
        }
        Log.w(TAG, "every catalog feed came back empty — falling back to the on-disk mirror")
        loadPersistedFeatured(ctx)
    }

    /** Instant paint before the network answers: the last good rails from disk, if any. */
    fun cachedFeatured(ctx: Context): Featured? = featuredCache?.value ?: loadPersistedFeatured(ctx)

    suspend fun search(ctx: Context, query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.length < 2) return@withContext emptyList()
        val key = term.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.at < SEARCH_TTL_MS) return@withContext it.value }
        val enc = URLEncoder.encode(term, "UTF-8")
        val results = fetchProducts(baseQuery(40, "desc:score", "&query=like:$enc"))
        searchCache[key] = Cached(results, now)
        Log.i(TAG, "search(\"$term\") -> ${results.size} result(s)")
        results
    }

    /** Description + screenshots for one product; null when GOG has no page for it. */
    suspend fun product(id: String): ProductDetail? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        detailCache[id]?.let { if (now - it.at < DETAIL_TTL_MS) return@withContext it.value }
        val body = StoreNet.get("https://api.gog.com/products/$id?expand=description,screenshots")
        val detail = body?.let { runCatching { parseDetail(JSONObject(it)) }.getOrNull() }
        detailCache[id] = Cached(detail, now)
        detail
    }

    // ── Parsing ───────────────────────────────────────────────────────────────────────────────

    private fun parseProducts(body: String): List<CatalogItem> = try {
        val arr = JSONObject(body).optJSONArray("products") ?: JSONArray()
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            parseProduct(p)?.let { out.add(it) }
        }
        out.distinctBy { it.id }
    } catch (e: Exception) {
        Log.w(TAG, "catalog parse failed: ${e.message}")
        emptyList()
    }

    fun parseProduct(p: JSONObject): CatalogItem? {
        val id = p.optString("id", "")
        val title = p.optString("title", "")
        if (id.isEmpty() || title.isEmpty()) return null
        // Windows only — the app cannot run anything else.
        val os = p.optJSONArray("operatingSystems")
        if (os != null && os.length() > 0) {
            var windows = false
            for (i in 0 until os.length()) if (os.optString(i).equals("windows", true)) windows = true
            if (!windows) return null
        }
        val price = p.optJSONObject("price")
        val finalAmount = price?.optJSONObject("finalMoney")?.optString("amount")?.toDoubleOrNull()
        val finalStr = price?.optString("final", "").orEmpty()
        val baseStr = price?.optString("base", "").orEmpty()
        val discountStr = price?.optString("discount", "").orEmpty()
        val discount = discountStr.filter { it.isDigit() }.toIntOrNull() ?: 0
        val isFree = price != null && (finalAmount == 0.0 || finalStr == "$0.00" || finalStr == "0")
        val genres = p.optJSONArray("genres")
        val tags = buildList {
            if (genres != null) for (i in 0 until minOf(3, genres.length())) {
                genres.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString(", ")
        val devs = p.optJSONArray("developers")
        val developer = devs?.optString(0, "").orEmpty()
        val slug = p.optString("slug", "")
        val storeLink = p.optString("storeLink", "").ifBlank {
            if (slug.isNotBlank()) "https://www.gog.com/en/game/$slug" else ""
        }
        val rating = p.optInt("reviewsRating", 0)
        return CatalogItem(
            store = Store.GOG,
            id = id,
            title = title,
            imageUrl = p.optString("coverHorizontal", "").ifBlank { null },
            tallImageUrl = p.optString("coverVertical", "").ifBlank { null },
            tags = tags,
            isFree = isFree,
            hasPrice = price != null && finalStr.isNotBlank(),
            finalPrice = finalStr,
            originalPrice = if (discount > 0) baseStr else "",
            discountPercent = if (discount > 0 && baseStr.isNotBlank()) discount else 0,
            storeUrl = storeLink,
            developer = developer,
            releaseDate = p.optString("releaseDate", ""),
            extra = buildMap {
                if (slug.isNotBlank()) put("slug", slug)
                if (rating > 0) put("rating", rating.toString())
                p.optString("productType", "").takeIf { it.isNotBlank() }?.let { put("type", it) }
            },
        )
    }

    private fun parseDetail(o: JSONObject): ProductDetail {
        val desc = o.optJSONObject("description")
        val shots = ArrayList<String>()
        val arr = o.optJSONArray("screenshots")
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            // Prefer the medium jpg; fall back to the template with a known formatter.
            var url = ""
            val formatted = s.optJSONArray("formatted_images")
            if (formatted != null) for (j in 0 until formatted.length()) {
                val f = formatted.optJSONObject(j) ?: continue
                if (f.optString("formatter_name") == "ggvgm") { url = f.optString("image_url"); break }
            }
            if (url.isBlank()) {
                val tpl = s.optString("formatter_template_url", "")
                if (tpl.contains("{formatter}")) url = tpl.replace("{formatter}", "ggvgm").replace(".png", ".jpg")
            }
            if (url.isNotBlank()) shots.add(url)
        }
        val images = o.optJSONObject("images")
        return ProductDetail(
            lead = desc?.optString("lead", "").orEmpty(),
            full = desc?.optString("full", "").orEmpty(),
            screenshots = shots,
            releaseDate = o.optString("release_date", ""),
            background = images?.optString("background", "")?.let(::absolutize)?.ifBlank { null },
            logo = images?.optString("logo2x", "")?.let(::absolutize)?.ifBlank { null },
        )
    }

    /** GOG returns protocol-relative `//images…` URLs in a few places. */
    fun absolutize(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** "YYYY.MM.DD" later than today → an unreleased pre-order, kept out of "New releases". */
    private fun isFuture(date: String): Boolean {
        if (date.length < 10) return false
        val parts = date.substring(0, 10).split('.', '-')
        if (parts.size != 3) return false
        val y = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        val d = parts[2].toIntOrNull() ?: return false
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        return y * 10000 + m * 100 + d > today
    }

    // ── Disk mirror ───────────────────────────────────────────────────────────────────────────

    private fun itemToJson(i: CatalogItem): JSONObject = JSONObject().apply {
        put("id", i.id); put("title", i.title)
        put("image", i.imageUrl ?: ""); put("tall", i.tallImageUrl ?: "")
        put("tags", i.tags); put("free", i.isFree); put("hasPrice", i.hasPrice)
        put("final", i.finalPrice); put("orig", i.originalPrice); put("disc", i.discountPercent)
        put("url", i.storeUrl); put("dev", i.developer); put("rel", i.releaseDate)
        put("extra", JSONObject(i.extra as Map<*, *>))
    }

    private fun itemFromJson(o: JSONObject): CatalogItem {
        val extra = HashMap<String, String>()
        o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
        return CatalogItem(
            store = Store.GOG,
            id = o.optString("id"), title = o.optString("title"),
            imageUrl = o.optString("image").ifBlank { null }, tallImageUrl = o.optString("tall").ifBlank { null },
            tags = o.optString("tags"), isFree = o.optBoolean("free"), hasPrice = o.optBoolean("hasPrice"),
            finalPrice = o.optString("final"), originalPrice = o.optString("orig"), discountPercent = o.optInt("disc"),
            storeUrl = o.optString("url"), developer = o.optString("dev"), releaseDate = o.optString("rel"),
            extra = extra,
        )
    }

    private fun listToJson(l: List<CatalogItem>) = JSONArray().apply { l.forEach { put(itemToJson(it)) } }
    private fun listFromJson(a: JSONArray?): List<CatalogItem> {
        if (a == null) return emptyList()
        val out = ArrayList<CatalogItem>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(itemFromJson(it)) }
        return out
    }

    private fun persistFeatured(ctx: Context, f: Featured) {
        runCatching {
            val o = JSONObject()
            f.hero?.let { o.put("hero", itemToJson(it)) }
            o.put("trending", listToJson(f.trending))
            o.put("new", listToJson(f.newReleases))
            o.put("deals", listToJson(f.deals))
            o.put("free", listToJson(f.free))
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_FEATURED, o.toString())
                .putLong(KEY_FEATURED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    private fun loadPersistedFeatured(ctx: Context): Featured? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_FEATURED, null) ?: return null
        val o = JSONObject(s)
        Featured(
            hero = o.optJSONObject("hero")?.let { itemFromJson(it) },
            trending = listFromJson(o.optJSONArray("trending")),
            newReleases = listFromJson(o.optJSONArray("new")),
            deals = listFromJson(o.optJSONArray("deals")),
            free = listFromJson(o.optJSONArray("free")),
        ).takeIf { !it.isEmpty }
    }.getOrNull()
}
