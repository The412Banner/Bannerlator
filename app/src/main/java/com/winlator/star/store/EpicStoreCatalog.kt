package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.MediaImage
import com.winlator.star.store.download.MediaVideo
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreMedia
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
        namespace: String? = null,
    ): List<CatalogItem> = parseElements(
        searchStoreRaw(count, keywords, sortBy, sortDir, onSale, freeGame, releasedOnly, namespace),
    )

    /**
     * The raw `elements` of one `searchStore` call. [namespace] narrows the search to a single
     * product sandbox (how a library game finds its offer); the other filters are unchanged.
     */
    private fun searchStoreRaw(
        count: Int,
        keywords: String? = null,
        sortBy: String = "relevancy",
        sortDir: String = "DESC",
        onSale: Boolean? = null,
        freeGame: Boolean? = null,
        releasedOnly: Boolean = false,
        namespace: String? = null,
    ): JSONArray {
        val args = StringBuilder()
        args.append("category:\"games/edition/base\",count:\$count,country:\$country,locale:\$locale,")
        args.append("sortBy:\$sortBy,sortDir:\$sortDir,allowCountries:\$country")
        if (keywords != null) args.append(",keywords:\$keywords")
        if (namespace != null) args.append(",namespace:\$namespace")
        if (onSale != null) args.append(",onSale:$onSale")
        if (freeGame != null) args.append(",freeGame:$freeGame")
        if (releasedOnly) args.append(",releaseDate:\"[,${nowIso()}]\"")
        val query = "query q(\$count:Int,\$country:String!,\$locale:String,\$sortBy:String,\$sortDir:String" +
            (if (keywords != null) ",\$keywords:String" else "") +
            (if (namespace != null) ",\$namespace:String" else "") +
            "){Catalog{searchStore($args){elements{$ELEMENT_FIELDS} paging{total}}}}"
        val vars = JSONObject()
            .put("count", count).put("country", country()).put("locale", "en-US")
            .put("sortBy", sortBy).put("sortDir", sortDir)
        if (keywords != null) vars.put("keywords", keywords)
        if (namespace != null) vars.put("namespace", namespace)
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val resp = StoreNet.postJson(GRAPHQL, body) ?: return JSONArray()
        return runCatching {
            JSONObject(resp).optJSONObject("data")?.optJSONObject("Catalog")
                ?.optJSONObject("searchStore")?.optJSONArray("elements") ?: JSONArray()
        }.onFailure { Log.w(TAG, "searchStore parse failed: ${it.message}") }.getOrDefault(JSONArray())
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
        val body = StoreNet.get(CONTENT_API + slug) ?: return emptyList()
        return runCatching { parseProductPage(body).screenshots.map { it.full } }.getOrDefault(emptyList())
    }

    // ── Media tab (screenshots + trailers) ────────────────────────────────────────────────────

    private const val CONTENT_API = "https://store-content-ipv4.ak.epicgames.com/api/en-US/content/products/"
    private const val MAX_SHOTS = 24
    private const val MAX_VIDEOS = 4
    /** Thumbnail variant both Epic image CDNs serve (a 3840x2160 gallery source drops to ~50 KB). */
    private const val THUMB_SUFFIX = "?resize=1&w=640"
    private const val VIDEO_SCHEME = "com.epicgames.video://"
    private const val VIDEO_SCHEME_QS = "com.epicgames.video.qs://"
    private val ID_CHARS = Regex("^[0-9a-fA-F-]{8,64}$")
    /** Rendition preference: 480p first (phone bandwidth — the shared MediaTab default), then up. */
    private val OUTPUT_ORDER = listOf("low", "medium", "high")

    /**
     * A trailer before its playable URL is known. Exactly one of [mediaRefId] (legacy CMS
     * carousel, `Media.getMediaRef`) or [videoId] (modern `heroCarouselVideo`,
     * `Video.fetchVideoByLocale`) is set.
     */
    internal class VideoRef(val mediaRefId: String?, val videoId: String?, val poster: String?, val title: String)

    /** One page's media with its trailers still unresolved. */
    internal class PageMedia(val screenshots: List<MediaImage>, val videos: List<VideoRef>) {
        val isEmpty: Boolean get() = screenshots.isEmpty() && videos.isEmpty()
    }

    private fun thumbOf(url: String): String = if (url.contains('?')) url else url + THUMB_SUFFIX

    /**
     * The store offer a LIBRARY game (namespace + catalogItemId) belongs to, or null. Matches on
     * `items[].id == catalogItemId` (the rule the store tab uses for ownership), else the first
     * base-game offer in that namespace. The raw element is returned because the media fields
     * (`featuredMedia` / `heroCarouselVideo` keyImages, `productSlug`) are not on [CatalogItem].
     */
    fun offerForLibraryGame(namespace: String, catalogItemId: String): JSONObject? {
        if (namespace.isBlank()) return null
        val elements = searchStoreRaw(5, namespace = namespace)
        var first: JSONObject? = null
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            if (first == null) first = e
            val items = e.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                if (items.optJSONObject(j)?.optString("id") == catalogItemId) return e
            }
        }
        return first
    }

    /**
     * Screenshots + trailers for a library game. [StoreMedia.EMPTY] when the offer was found but
     * publishes nothing (cache it as a genuine "none"); null when the offer lookup itself failed —
     * offline, a store hiccup, or a title not on the store — which the caller should cache only
     * briefly (`StoreMediaCache.put(miss = true)`). Never throws. Blocking; IO thread only.
     * Legacy offers (`productSlug` set) prefer the richer CMS page, modern ones their `keyImages`;
     * each falls back to the other. 1 GraphQL search + at most one content GET + one resolve POST.
     */
    fun libraryGameMedia(namespace: String, catalogItemId: String): StoreMedia? = runCatching {
        val offer = offerForLibraryGame(namespace, catalogItemId) ?: return@runCatching null
        val productSlug = offer.optString("productSlug", "")
        val legacy = productSlug.isNotBlank() && productSlug != "null"
        val slug = pageSlugOf(offer)
        val fromPage = { if (slug.isNotBlank()) productPageMedia(slug) else null }
        val fromOffer = { parseOfferMedia(offer.optJSONArray("keyImages")).takeIf { !it.isEmpty }?.let { resolve(it) } }
        val media = (if (legacy) fromPage() ?: fromOffer() else fromOffer() ?: fromPage()) ?: StoreMedia.EMPTY
        Log.i(TAG, "media ns=$namespace slug=$slug legacy=$legacy -> shots=${media.screenshots.size} videos=${media.videos.size}")
        media
    }.onFailure { Log.w(TAG, "libraryGameMedia failed: ${it.message}") }.getOrNull()

    /**
     * Gallery + carousel trailers of a product page on the content API, or null when the page is
     * unknown (modern offers) or has nothing. Blocking; IO thread only.
     */
    fun productPageMedia(slug: String): StoreMedia? {
        val body = StoreNet.get(CONTENT_API + slug) ?: return null
        val page = runCatching { parseProductPage(body) }
            .onFailure { Log.w(TAG, "product page parse failed: ${it.message}") }.getOrNull() ?: return null
        if (page.isEmpty) return null
        return resolve(page)
    }

    /**
     * Pure parser for the content API body: the `home` page (or the first one with anything)
     * → `data.gallery.galleryImages[].src` and `data.carousel.items[].video.recipes` (JSON string:
     * locale → [{recipe, mediaRefId}]; "en-US" else the first locale; `video-fmp4` else
     * `video-webm`, never HLS — VideoView can't play it).
     */
    internal fun parseProductPage(body: String): PageMedia {
        val pages = JSONObject(body).optJSONArray("pages") ?: return PageMedia(emptyList(), emptyList())
        var chosen: PageMedia? = null
        for (p in 0 until pages.length()) {
            val page = pages.optJSONObject(p) ?: continue
            val data = page.optJSONObject("data") ?: continue
            val shots = ArrayList<MediaImage>()
            val gallery = data.optJSONObject("gallery")?.optJSONArray("galleryImages")
            if (gallery != null) for (g in 0 until gallery.length()) {
                val src = gallery.optJSONObject(g)?.optString("src").orEmpty()
                if (src.isNotBlank() && shots.size < MAX_SHOTS) shots.add(MediaImage(thumbOf(src), src))
            }
            val videos = ArrayList<VideoRef>()
            val items = data.optJSONObject("carousel")?.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val video = item.optJSONObject("video") ?: continue
                val recipes = video.optString("recipes", "")
                if (recipes.isBlank()) continue
                val refId = pickRecipe(recipes) ?: continue
                val poster = item.optJSONObject("image")?.optString("src").orEmpty().ifBlank { null }
                val title = video.optString("title", "").trim().ifBlank { "Trailer ${videos.size + 1}" }
                if (videos.size < MAX_VIDEOS) videos.add(VideoRef(refId, null, poster, title))
            }
            val media = PageMedia(shots, videos)
            if (page.optString("_slug") == "home" && !media.isEmpty) return media
            if (chosen == null && !media.isEmpty) chosen = media
        }
        return chosen ?: PageMedia(emptyList(), emptyList())
    }

    /** The `mediaRefId` to resolve out of a carousel `recipes` string, or null. */
    private fun pickRecipe(recipes: String): String? {
        val byLocale = runCatching { JSONObject(recipes) }.getOrNull() ?: return null
        val arr = byLocale.optJSONArray("en-US")
            ?: byLocale.keys().asSequence().firstOrNull()?.let { byLocale.optJSONArray(it) }
            ?: return null
        var webm: String? = null
        for (r in 0 until arr.length()) {
            val rec = arr.optJSONObject(r) ?: continue
            val id = rec.optString("mediaRefId", "")
            if (id.isBlank()) continue
            when (rec.optString("recipe")) {
                "video-fmp4" -> return id
                "video-webm" -> if (webm == null) webm = id
            }
        }
        return webm
    }

    /**
     * Pure parser for an offer's `keyImages`: `featuredMedia` / `*Screenshot*` = screenshots,
     * `heroCarouselVideo` = `com.epicgames.video://<uuid>?cover=<poster>` trailers. The
     * `com.epicgames.video.qs://` form is not resolvable through the store GraphQL and is skipped.
     */
    internal fun parseOfferMedia(keyImages: JSONArray?): PageMedia {
        val shots = ArrayList<MediaImage>()
        val videos = ArrayList<VideoRef>()
        if (keyImages != null) for (k in 0 until keyImages.length()) {
            val img = keyImages.optJSONObject(k) ?: continue
            val url = img.optString("url", "")
            if (url.isBlank()) continue
            val type = img.optString("type", "")
            when {
                type == "featuredMedia" || type.contains("Screenshot", ignoreCase = true) ->
                    if (shots.size < MAX_SHOTS) shots.add(MediaImage(thumbOf(url), url))
                type == "heroCarouselVideo" && url.startsWith(VIDEO_SCHEME) -> {
                    val rest = url.removePrefix(VIDEO_SCHEME)
                    val id = rest.substringBefore('?')
                    if (!ID_CHARS.matches(id)) continue
                    val cover = rest.substringAfter("cover=", "").substringBefore('&')
                        .takeIf { it.isNotBlank() }?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    if (videos.size < MAX_VIDEOS) videos.add(VideoRef(null, id, cover, "Trailer ${videos.size + 1}"))
                }
                type == "heroCarouselVideo" && url.startsWith(VIDEO_SCHEME_QS) -> { /* unresolvable, see header */ }
            }
        }
        return PageMedia(shots, videos)
    }

    /** Turns the page's [VideoRef]s into playable [MediaVideo]s with ONE aliased GraphQL POST. */
    private fun resolve(page: PageMedia): StoreMedia {
        if (page.videos.isEmpty()) return StoreMedia(page.screenshots, emptyList())
        val q = StringBuilder("query q{")
        page.videos.forEachIndexed { i, v ->
            val ref = v.mediaRefId
            val vid = v.videoId
            when {
                ref != null && ID_CHARS.matches(ref) ->
                    q.append("v$i:Media{getMediaRef(mediaRefId:\"$ref\"){outputs{key url contentType}}} ")
                vid != null && ID_CHARS.matches(vid) ->
                    q.append("v$i:Video{fetchVideoByLocale(videoId:\"$vid\",locale:\"en-US\"){recipe mediaRef{outputs{key url contentType}}}} ")
            }
        }
        q.append("}")
        val resp = StoreNet.postJson(GRAPHQL, JSONObject().put("query", q.toString()).toString())
        val videos = resp?.let { parseResolvedVideos(it, page.videos) }.orEmpty()
        return StoreMedia(page.screenshots, videos)
    }

    /**
     * Pure parser for the batched resolve response: alias `v<i>` ↔ `refs[i]`; a failed alias
     * (null data + an `errors[]` entry) just drops that trailer.
     */
    internal fun parseResolvedVideos(body: String, refs: List<VideoRef>): List<MediaVideo> {
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<MediaVideo>()
        refs.forEachIndexed { i, ref ->
            val node = data.optJSONObject("v$i") ?: return@forEachIndexed
            val outputs: JSONArray? = when {
                ref.mediaRefId != null -> node.optJSONObject("getMediaRef")?.optJSONArray("outputs")
                else -> {
                    val list = node.optJSONArray("fetchVideoByLocale")
                    var chosen: JSONObject? = null
                    if (list != null) for (r in 0 until list.length()) {
                        val entry = list.optJSONObject(r) ?: continue
                        when (entry.optString("recipe")) {
                            "video-fmp4" -> { chosen = entry; break }
                            "video-webm" -> if (chosen == null) chosen = entry
                        }
                    }
                    chosen?.optJSONObject("mediaRef")?.optJSONArray("outputs")
                }
            }
            val (url, thumb) = pickOutput(outputs) ?: return@forEachIndexed
            out.add(MediaVideo.Direct(url = url, poster = ref.poster ?: thumb, title = ref.title))
        }
        return out
    }

    /** (playable url, thumbnail url) from a media-service `outputs[]`, by [OUTPUT_ORDER]. */
    private fun pickOutput(outputs: JSONArray?): Pair<String, String?>? {
        if (outputs == null) return null
        val byKey = HashMap<String, String>()
        var thumb: String? = null
        for (o in 0 until outputs.length()) {
            val out = outputs.optJSONObject(o) ?: continue
            val url = out.optString("url", "")
            if (url.isBlank()) continue
            val key = out.optString("key", "")
            val type = out.optString("contentType", "")
            if (key == "thumbnail") thumb = url
            else if (type.startsWith("video/")) byKey[key] = url
        }
        val url = OUTPUT_ORDER.firstNotNullOfOrNull { byKey[it] } ?: byKey.values.firstOrNull() ?: return null
        return url to thumb
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
