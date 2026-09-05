package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The Epic Games Store catalog, through the same GraphQL endpoint the store website uses:
 *
 *   POST https://store.epicgames.com/graphql   (needs a browser User-Agent — the bare Java UA is refused)
 *   Catalog.searchStore(keywords, category:"games/edition/base", count, country, locale, sortBy,
 *                       sortDir, onSale, freeGame, releaseDate, allowCountries)
 *   Catalog.catalogOffer(id, namespace, locale)
 *
 * Verified live (2026-09-04): `graphql.epicgames.com` is GONE (404), `www.epicgames.com/graphql`
 * redirects into a captcha wall; only the store host answers. Elements carry `id` (the OFFER id),
 * `namespace`, `items[{id}]` (the CATALOG item ids — what the library API records, so ownership is
 * matched on those), `keyImages[{type,url}]` (OfferImageWide / OfferImageTall / Thumbnail),
 * `catalogNs.mappings[pageSlug]` for the product page, and `price.totalPrice` with cents plus
 * `fmtPrice` strings. Screenshots are NOT in either query; they come from the product-page content
 * API when the slug is known (fail-soft).
 *
 * "Free this week" comes from the static `freeGamesPromotions` feed the app already used.
 */
object EpicStoreCatalog {

    private const val TAG = "EpicStore"
    private const val GRAPHQL = "https://store.epicgames.com/graphql"
    private const val PROMOS = "https://store-site-backend-static-ipv4.ak.epicgames.com/freeGamesPromotions"
    private const val FEATURED_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 5 * 60 * 1000L
    private const val DETAIL_TTL_MS = 60 * 60 * 1000L
    private const val PREFS = "epic_store_cache"
    private const val KEY_FEATURED = "featured_json"

    private const val ELEMENT_FIELDS =
        "title id namespace description effectiveDate releaseDate offerType productSlug urlSlug " +
            "items{id namespace} keyImages{type url} seller{name} tags{name} " +
            "catalogNs{mappings(pageType:\"productHome\"){pageSlug pageType}} " +
            "price(country:\$country){totalPrice{discountPrice originalPrice discount currencyCode " +
            "fmtPrice(locale:\$locale){originalPrice discountPrice}}}"

    class Featured(
        val hero: CatalogItem?,
        val freeNow: List<CatalogItem>,
        val onSale: List<CatalogItem>,
        val newReleases: List<CatalogItem>,
        val freeToPlay: List<CatalogItem>,
    ) {
        val isEmpty: Boolean
            get() = hero == null && freeNow.isEmpty() && onSale.isEmpty() && newReleases.isEmpty() && freeToPlay.isEmpty()
    }

    class OfferDetail(
        val description: String,
        val longDescription: String,
        val screenshots: List<String>,
        val releaseDate: String,
        val wideImage: String?,
        val developer: String,
        val publisher: String,
        val tags: List<String>,
    )

    private class Cached<T>(val value: T, val at: Long)

    private var featuredCache: Cached<Featured>? = null
    private val searchCache = ConcurrentHashMap<String, Cached<List<CatalogItem>>>()
    private val detailCache = ConcurrentHashMap<String, Cached<OfferDetail?>>()

    private fun country(): String =
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"

    private fun nowIso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    // ── GraphQL ───────────────────────────────────────────────────────────────────────────────

    private fun searchStore(
        count: Int,
        keywords: String? = null,
        sortBy: String = "relevancy",
        sortDir: String = "DESC",
        onSale: Boolean? = null,
        freeGame: Boolean? = null,
        releasedOnly: Boolean = false,
    ): List<CatalogItem> {
        val args = StringBuilder()
        args.append("category:\"games/edition/base\",count:\$count,country:\$country,locale:\$locale,")
        args.append("sortBy:\$sortBy,sortDir:\$sortDir,allowCountries:\$country")
        if (keywords != null) args.append(",keywords:\$keywords")
        if (onSale != null) args.append(",onSale:$onSale")
        if (freeGame != null) args.append(",freeGame:$freeGame")
        if (releasedOnly) args.append(",releaseDate:\"[,${nowIso()}]\"")
        val query = "query q(\$count:Int,\$country:String!,\$locale:String,\$sortBy:String,\$sortDir:String" +
            (if (keywords != null) ",\$keywords:String" else "") +
            "){Catalog{searchStore($args){elements{$ELEMENT_FIELDS} paging{total}}}}"
        val vars = JSONObject()
            .put("count", count).put("country", country()).put("locale", "en-US")
            .put("sortBy", sortBy).put("sortDir", sortDir)
        if (keywords != null) vars.put("keywords", keywords)
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val resp = StoreNet.postJson(GRAPHQL, body) ?: return emptyList()
        return runCatching {
            val elements = JSONObject(resp).optJSONObject("data")?.optJSONObject("Catalog")
                ?.optJSONObject("searchStore")?.optJSONArray("elements") ?: JSONArray()
            parseElements(elements)
        }.onFailure { Log.w(TAG, "searchStore parse failed: ${it.message}") }.getOrDefault(emptyList())
    }

    private fun parseElements(arr: JSONArray): List<CatalogItem> {
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            parseElement(e)?.let { out.add(it) }
        }
        return out.distinctBy { it.id }
    }

    fun parseElement(e: JSONObject): CatalogItem? {
        val id = e.optString("id", "")
        val title = e.optString("title", "")
        if (id.isEmpty() || title.isEmpty()) return null
        val ns = e.optString("namespace", "")
        var wide: String? = null
        var tall: String? = null
        var thumb: String? = null
        val keyImages = e.optJSONArray("keyImages")
        if (keyImages != null) for (k in 0 until keyImages.length()) {
            val img = keyImages.optJSONObject(k) ?: continue
            val url = img.optString("url", "")
            if (url.isBlank()) continue
            when (img.optString("type", "")) {
                "OfferImageWide", "DieselStoreFrontWide" -> if (wide == null) wide = url
                "OfferImageTall", "DieselStoreFrontTall", "DieselGameBoxTall" -> if (tall == null) tall = url
                "Thumbnail" -> if (thumb == null) thumb = url
            }
        }
        val price = e.optJSONObject("price")?.optJSONObject("totalPrice")
        val original = price?.optLong("originalPrice", -1L) ?: -1L
        val discounted = price?.optLong("discountPrice", -1L) ?: -1L
        val fmt = price?.optJSONObject("fmtPrice")
        val fmtOriginal = fmt?.optString("originalPrice", "").orEmpty()
        val fmtDiscount = fmt?.optString("discountPrice", "").orEmpty()
        val hasPrice = original >= 0 && discounted >= 0
        val isFree = hasPrice && discounted == 0L
        val discountPct = if (hasPrice && original > 0 && discounted < original)
            ((original - discounted) * 100 / original).toInt() else 0
        val finalPrice = when {
            isFree -> "Free"
            fmtDiscount.isNotBlank() && fmtDiscount != "0" -> fmtDiscount
            else -> fmtOriginal
        }
        val tags = e.optJSONArray("tags")
        val tagLine = buildList {
            if (tags != null) for (t in 0 until tags.length()) {
                val name = tags.optJSONObject(t)?.optString("name").orEmpty()
                if (name.isNotBlank() && name != "Windows" && size < 3) add(name)
            }
        }.joinToString(", ")
        val itemIds = buildList {
            val items = e.optJSONArray("items")
            if (items != null) for (j in 0 until items.length()) {
                items.optJSONObject(j)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        val pageSlug = pageSlugOf(e)
        val storeUrl = if (pageSlug.isNotBlank()) "https://store.epicgames.com/en-US/p/$pageSlug"
        else "https://store.epicgames.com/en-US/browse?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
        return CatalogItem(
            store = Store.EPIC,
            id = id,
            title = title,
            imageUrl = wide ?: thumb,
            tallImageUrl = tall ?: thumb,
            tags = tagLine,
            isFree = isFree,
            hasPrice = hasPrice,
            finalPrice = finalPrice,
            originalPrice = if (discountPct > 0) fmtOriginal else "",
            discountPercent = discountPct,
            storeUrl = storeUrl,
            developer = e.optJSONObject("seller")?.optString("name", "").orEmpty(),
            releaseDate = e.optString("releaseDate", e.optString("effectiveDate", "")),
            description = e.optString("description", ""),
            extra = buildMap {
                put("namespace", ns)
                if (itemIds.isNotEmpty()) put("items", itemIds.joinToString(","))
                if (pageSlug.isNotBlank()) put("slug", pageSlug)
            },
        )
    }

    /** The product-page slug, from `catalogNs.mappings` first, then `productSlug`. */
    private fun pageSlugOf(e: JSONObject): String {
        val mappings = e.optJSONObject("catalogNs")?.optJSONArray("mappings")
        if (mappings != null) for (m in 0 until mappings.length()) {
            val map = mappings.optJSONObject(m) ?: continue
            if (map.optString("pageType") == "productHome") {
                val s = map.optString("pageSlug", "")
                if (s.isNotBlank()) return s
            }
        }
        var slug = e.optString("productSlug", "")
        if (slug == "null") slug = ""
        if (slug.endsWith("/home")) slug = slug.removeSuffix("/home")
        return slug
    }

    // ── Rails ─────────────────────────────────────────────────────────────────────────────────

    suspend fun featured(ctx: Context, force: Boolean = false): Featured? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        featuredCache?.let { if (!force && now - it.at < FEATURED_TTL_MS) return@withContext it.value }

        val freeNow = fetchPromos()
        val onSale = searchStore(24, sortBy = "currentPrice", sortDir = "ASC", onSale = true)
            .filter { it.discountPercent > 0 }
        val newest = searchStore(24, sortBy = "releaseDate", sortDir = "DESC", releasedOnly = true)
        val freeToPlay = searchStore(24, sortBy = "relevancy", sortDir = "DESC", freeGame = true)

        val hero = freeNow.firstOrNull { !it.imageUrl.isNullOrBlank() }
            ?: onSale.firstOrNull { !it.imageUrl.isNullOrBlank() }
            ?: newest.firstOrNull()
        val result = Featured(
            hero = hero,
            freeNow = freeNow,
            onSale = onSale.filter { it.id != hero?.id },
            newReleases = newest.filter { it.id != hero?.id },
            freeToPlay = freeToPlay,
        )
        if (!result.isEmpty) {
            featuredCache = Cached(result, now)
            persistFeatured(ctx, result)
            Log.i(TAG, "rails: freeNow=${freeNow.size} sale=${onSale.size} new=${newest.size} f2p=${freeToPlay.size}")
            return@withContext result
        }
        Log.w(TAG, "every store feed came back empty — falling back to the on-disk mirror")
        loadPersistedFeatured(ctx)
    }

    fun cachedFeatured(ctx: Context): Featured? = featuredCache?.value ?: loadPersistedFeatured(ctx)

    suspend fun search(ctx: Context, query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.length < 2) return@withContext emptyList()
        val key = term.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.at < SEARCH_TTL_MS) return@withContext it.value }
        val results = searchStore(30, keywords = term)
        searchCache[key] = Cached(results, now)
        Log.i(TAG, "search(\"$term\") -> ${results.size} result(s)")
        results
    }

    /** This week's giveaway titles (100 % off right now), via the static promotions feed. */
    private fun fetchPromos(): List<CatalogItem> {
        val body = StoreNet.get("$PROMOS?locale=en-US&country=${country()}&allowCountries=${country()}")
            ?: return emptyList()
        return runCatching {
            val elements = JSONObject(body).optJSONObject("data")?.optJSONObject("Catalog")
                ?.optJSONObject("searchStore")?.optJSONArray("elements") ?: JSONArray()
            val out = ArrayList<CatalogItem>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val promos = el.optJSONObject("promotions") ?: continue
                val current = promos.optJSONArray("promotionalOffers")
                if (current == null || current.length() == 0) continue
                val inner = current.optJSONObject(0)?.optJSONArray("promotionalOffers") ?: continue
                if (inner.length() == 0) continue
                val discount = inner.optJSONObject(0)?.optJSONObject("discountSetting")
                if (discount == null || discount.optInt("discountPercentage", -1) != 0) continue
                val end = inner.optJSONObject(0)?.optString("endDate", "").orEmpty()
                val base = parseElement(el) ?: continue
                out.add(
                    base.copy(
                        isFree = true,
                        hasPrice = true,
                        finalPrice = "Free",
                        originalPrice = base.originalPrice.ifBlank {
                            el.optJSONObject("price")?.optJSONObject("totalPrice")?.optJSONObject("fmtPrice")
                                ?.optString("originalPrice", "").orEmpty()
                        },
                        discountPercent = 100,
                        tags = if (end.length >= 10) "Free until ${end.take(10)}" else base.tags,
                    ),
                )
            }
            out.distinctBy { it.id }
        }.onFailure { Log.w(TAG, "promos parse failed: ${it.message}") }.getOrDefault(emptyList())
    }

    // ── Offer detail ──────────────────────────────────────────────────────────────────────────

    suspend fun offer(namespace: String, offerId: String): OfferDetail? = withContext(Dispatchers.IO) {
        val key = "$namespace/$offerId"
        val now = System.currentTimeMillis()
        detailCache[key]?.let { if (now - it.at < DETAIL_TTL_MS) return@withContext it.value }
        val query = "query q(\$country:String!,\$locale:String){Catalog{catalogOffer(id:\"$offerId\"," +
            "namespace:\"$namespace\",locale:\$locale){title description longDescription effectiveDate " +
            "releaseDate developerDisplayName publisherDisplayName productSlug urlSlug tags{name} keyImages{type url} " +
            "catalogNs{mappings(pageType:\"productHome\"){pageSlug pageType}} " +
            "price(country:\$country){totalPrice{discountPrice originalPrice}}}}}"
        val body = JSONObject().put("query", query)
            .put("variables", JSONObject().put("country", country()).put("locale", "en-US")).toString()
        val resp = StoreNet.postJson(GRAPHQL, body)
        val detail = resp?.let { r ->
            runCatching {
                val o = JSONObject(r).optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("catalogOffer")
                    ?: return@runCatching null
                var wide: String? = null
                val shots = ArrayList<String>()
                val keyImages = o.optJSONArray("keyImages")
                if (keyImages != null) for (k in 0 until keyImages.length()) {
                    val img = keyImages.optJSONObject(k) ?: continue
                    val url = img.optString("url", "")
                    val type = img.optString("type", "")
                    if (url.isBlank()) continue
                    if (type == "OfferImageWide" || type == "DieselStoreFrontWide") { if (wide == null) wide = url }
                    if (type.contains("Screenshot", ignoreCase = true) || type == "featuredMedia") shots.add(url)
                }
                val slug = pageSlugOf(o)
                if (shots.isEmpty() && slug.isNotBlank()) shots.addAll(productPageScreenshots(slug))
                val tags = buildList {
                    val arr = o.optJSONArray("tags")
                    if (arr != null) for (t in 0 until arr.length()) {
                        arr.optJSONObject(t)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                OfferDetail(
                    description = o.optString("description", ""),
                    longDescription = o.optString("longDescription", ""),
                    screenshots = shots.distinct(),
                    releaseDate = o.optString("releaseDate", o.optString("effectiveDate", "")),
                    wideImage = wide,
                    developer = o.optString("developerDisplayName", ""),
                    publisher = o.optString("publisherDisplayName", ""),
                    tags = tags,
                )
            }.getOrNull()
        }
        detailCache[key] = Cached(detail, now)
        detail
    }

    /**
     * Screenshots from the product-page content API (`…/content/products/<slug>` →
     * `pages[].data.gallery.galleryImages[].src`). Undocumented and edge-guarded; empty on any miss.
     */
    private fun productPageScreenshots(slug: String): List<String> {
        val body = StoreNet.get("https://store-content-ipv4.ak.epicgames.com/api/en-US/content/products/$slug")
            ?: return emptyList()
        return runCatching {
            val pages = JSONObject(body).optJSONArray("pages") ?: return emptyList()
            val out = ArrayList<String>()
            for (p in 0 until pages.length()) {
                val gallery = pages.optJSONObject(p)?.optJSONObject("data")?.optJSONObject("gallery")
                    ?.optJSONArray("galleryImages") ?: continue
                for (g in 0 until gallery.length()) {
                    gallery.optJSONObject(g)?.optString("src")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    // ── Disk mirror ───────────────────────────────────────────────────────────────────────────

    private fun itemToJson(i: CatalogItem): JSONObject = JSONObject().apply {
        put("id", i.id); put("title", i.title)
        put("image", i.imageUrl ?: ""); put("tall", i.tallImageUrl ?: "")
        put("tags", i.tags); put("free", i.isFree); put("hasPrice", i.hasPrice)
        put("final", i.finalPrice); put("orig", i.originalPrice); put("disc", i.discountPercent)
        put("url", i.storeUrl); put("dev", i.developer); put("rel", i.releaseDate); put("desc", i.description)
        put("extra", JSONObject(i.extra as Map<*, *>))
    }

    private fun itemFromJson(o: JSONObject): CatalogItem {
        val extra = HashMap<String, String>()
        o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
        return CatalogItem(
            store = Store.EPIC,
            id = o.optString("id"), title = o.optString("title"),
            imageUrl = o.optString("image").ifBlank { null }, tallImageUrl = o.optString("tall").ifBlank { null },
            tags = o.optString("tags"), isFree = o.optBoolean("free"), hasPrice = o.optBoolean("hasPrice"),
            finalPrice = o.optString("final"), originalPrice = o.optString("orig"), discountPercent = o.optInt("disc"),
            storeUrl = o.optString("url"), developer = o.optString("dev"), releaseDate = o.optString("rel"),
            description = o.optString("desc"), extra = extra,
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
            o.put("freeNow", listToJson(f.freeNow))
            o.put("sale", listToJson(f.onSale))
            o.put("new", listToJson(f.newReleases))
            o.put("f2p", listToJson(f.freeToPlay))
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_FEATURED, o.toString()).apply()
        }
    }

    private fun loadPersistedFeatured(ctx: Context): Featured? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_FEATURED, null) ?: return null
        val o = JSONObject(s)
        Featured(
            hero = o.optJSONObject("hero")?.let { itemFromJson(it) },
            freeNow = listFromJson(o.optJSONArray("freeNow")),
            onSale = listFromJson(o.optJSONArray("sale")),
            newReleases = listFromJson(o.optJSONArray("new")),
            freeToPlay = listFromJson(o.optJSONArray("f2p")),
        ).takeIf { !it.isEmpty }
    }.getOrNull()
}
