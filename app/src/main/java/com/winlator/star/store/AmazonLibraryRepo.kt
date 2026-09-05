package com.winlator.star.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.winlator.star.store.download.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Amazon Games library as the storefront host reads it: the `amazon_library_cache` JSON the
 * games screen writes, plus a sync that mirrors [AmazonGamesActivity]'s (entitlements → DLC map →
 * update check → cache) so the Library tab fills itself on first open.
 *
 * Adds one thing the games screen never had: a **poster** per title. Amazon only publishes a
 * square icon (`artUrl`) and a wide background (`heroUrl`), so the 2:3 tiles the other stores draw
 * would be the odd ones out. The sync backfills a SteamGridDB 600x900 grid per productId into
 * `amazon_vcover_<pid>` (once, bounded per pass) and [toCatalogItem] prefers it.
 *
 * Install state (`amazon_exe_` / `amazon_dir_`) is READ only — that stays with [AmazonInstallState].
 */
object AmazonLibraryRepo {

    private const val TAG = "AmazonLibrary"
    private const val PREFS = "bh_amazon_prefs"
    private const val CACHE_KEY = "amazon_library_cache"
    private const val LAST_SYNC_KEY = "amazon_library_synced_at"
    private const val THROTTLE_MS = 15L * 60L * 1000L
    private const val VCOVER_PREFIX = "amazon_vcover_"
    /** Poster lookups per sync pass — keeps a 300-game first sync from spending minutes on SGDB. */
    private const val POSTER_BUDGET = 40

    private val syncing = AtomicBoolean(false)

    sealed interface SyncResult {
        data class Ok(val games: List<AmazonGame>) : SyncResult
        data class Failed(val message: String) : SyncResult
        data object NotLoggedIn : SyncResult
        data object Busy : SyncResult
        data object Throttled : SyncResult
    }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, 0)

    fun cached(ctx: Context): List<AmazonGame> {
        val json = prefs(ctx).getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<AmazonGame>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val g = AmazonGame()
                g.productId = j.optString("productId", "")
                g.entitlementId = j.optString("entitlementId", "")
                g.title = j.optString("title", "")
                g.artUrl = j.optString("artUrl", "")
                g.heroUrl = j.optString("heroUrl", "")
                g.developer = j.optString("developer", "")
                g.publisher = j.optString("publisher", "")
                g.productSku = j.optString("productSku", "")
                g.isInstalled = j.optBoolean("isInstalled", false)
                g.installPath = j.optString("installPath", "")
                g.versionId = j.optString("versionId", "")
                g.downloadSize = j.optLong("downloadSize", 0L)
                g.installSize = j.optLong("installSize", 0L)
                if (g.productId.isNotEmpty()) out.add(g)
            }
            out.distinctBy { it.productId }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** productIds with a recorded launch exe — what the tiles call "installed". */
    fun installedIds(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val out = HashSet<String>()
        for (key in p.all.keys) if (key.startsWith("amazon_exe_")) out.add(key.removePrefix("amazon_exe_"))
        return out
    }

    /** productIds whose cached versionId carries the games screen's update marker. */
    fun updatableIds(games: List<AmazonGame>): Set<String> =
        games.filter { it.versionId.endsWith("_UPDATE_AVAILABLE") }.mapTo(HashSet()) { it.productId }

    fun poster(ctx: Context, productId: String): String? =
        prefs(ctx).getString("$VCOVER_PREFIX$productId", null)?.ifBlank { null }

    suspend fun sync(
        ctx: Context,
        force: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        if (!syncing.compareAndSet(false, true)) return@withContext SyncResult.Busy
        try {
            val appCtx = ctx.applicationContext
            val p = prefs(appCtx)
            val cachedList = cached(appCtx)
            val lastSync = p.getLong(LAST_SYNC_KEY, 0L)
            if (!force && cachedList.isNotEmpty() && System.currentTimeMillis() - lastSync < THROTTLE_MS) {
                backfillPosters(p, cachedList, onStatus)
                return@withContext SyncResult.Throttled
            }
            onStatus("Checking credentials…")
            val creds = AmazonCredentialStore.load(appCtx)
            if (creds == null || creds.accessToken == null) return@withContext SyncResult.NotLoggedIn
            val token = AmazonCredentialStore.getValidAccessToken(appCtx)
                ?: return@withContext SyncResult.Failed("Amazon session expired — please sign in again")

            onStatus("Fetching game list…")
            val all = AmazonApiClient.getEntitlements(token, creds.deviceSerial)
            if (all.isNullOrEmpty()) {
                return@withContext if (cachedList.isEmpty()) SyncResult.Failed("No games found in your Amazon library")
                else SyncResult.Ok(cachedList)
            }

            val games = ArrayList<AmazonGame>()
            val dlcMap = HashMap<String, JSONArray>()
            for (g in all) {
                if (g.isDLC && g.parentProductId.isNotEmpty()) {
                    val arr = dlcMap.getOrPut(g.parentProductId) { JSONArray() }
                    runCatching {
                        arr.put(JSONObject().put("eid", g.entitlementId).put("pid", g.productId).put("title", g.title))
                    }
                } else games.add(g)
            }
            val ed = p.edit()
            for ((key, arr) in dlcMap) ed.putString("amazon_dlcs_$key", arr.toString())
            ed.apply()

            val finalGames = (if (games.isEmpty()) all.toMutableList() else games)
            finalGames.sortWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            for (fresh in finalGames) {
                val old = cachedList.firstOrNull { it.productId == fresh.productId } ?: continue
                fresh.isInstalled = old.isInstalled
                fresh.installPath = old.installPath
                fresh.versionId = old.versionId
                fresh.downloadSize = old.downloadSize
                fresh.installSize = old.installSize
            }

            onStatus("Checking for updates…")
            for (game in finalGames) {
                if (p.getString("amazon_exe_${game.productId}", null) == null || game.productId.isEmpty()) continue
                runCatching {
                    val live = AmazonApiClient.getLiveVersionId(token, game.productId)
                    if (!live.isNullOrEmpty() && live != game.versionId) game.versionId = "${live}_UPDATE_AVAILABLE"
                }
            }

            saveCache(p, finalGames)
            p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            backfillPosters(p, finalGames, onStatus)
            Log.i(TAG, "sync: entitlements=${all.size} games=${finalGames.size} dlcBases=${dlcMap.size}")
            SyncResult.Ok(finalGames)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    /** SGDB posters for titles that have none yet, at most [POSTER_BUDGET] per pass, 4 in flight. */
    private fun backfillPosters(p: SharedPreferences, games: List<AmazonGame>, onStatus: (String) -> Unit) {
        val missing = games.filter { it.productId.isNotEmpty() && !p.contains("$VCOVER_PREFIX${it.productId}") }
            .take(POSTER_BUDGET)
        if (missing.isEmpty()) return
        onStatus("Fetching cover art…")
        val pool = Executors.newFixedThreadPool(4)
        val futures = missing.map { g ->
            pool.submit(Callable { g.productId to StoreNet.sgdbPoster(g.title) })
        }
        pool.shutdown()
        val ed = p.edit()
        for (f in futures) {
            val (pid, url) = runCatching { f.get() }.getOrNull() ?: continue
            // A miss is recorded as an empty string so it is not retried every pass.
            ed.putString("$VCOVER_PREFIX$pid", url)
        }
        ed.apply()
    }

    private fun saveCache(p: SharedPreferences, games: List<AmazonGame>) {
        runCatching {
            val arr = JSONArray()
            for (g in games) {
                val j = JSONObject()
                j.put("productId", g.productId)
                j.put("entitlementId", g.entitlementId)
                j.put("title", g.title)
                j.put("artUrl", g.artUrl)
                j.put("heroUrl", g.heroUrl)
                j.put("developer", g.developer)
                j.put("publisher", g.publisher)
                j.put("productSku", g.productSku)
                j.put("isInstalled", g.isInstalled)
                j.put("installPath", g.installPath)
                j.put("versionId", g.versionId)
                j.put("downloadSize", g.downloadSize)
                j.put("installSize", g.installSize)
                arr.put(j)
            }
            p.edit().putString(CACHE_KEY, arr.toString()).apply()
        }
    }

    /** A cached [AmazonGame] as a [CatalogItem]: wide = background, tall = SGDB poster → icon. */
    fun toCatalogItem(ctx: Context, g: AmazonGame): CatalogItem = CatalogItem(
        store = Store.AMAZON,
        id = g.productId,
        title = g.title.ifBlank { g.shortId() },
        imageUrl = g.heroUrl.ifBlank { null } ?: g.artUrl.ifBlank { null },
        tallImageUrl = poster(ctx, g.productId) ?: g.artUrl.ifBlank { null } ?: g.heroUrl.ifBlank { null },
        tags = listOfNotNull(g.developer.ifBlank { null }, g.publisher.ifBlank { null }.takeIf { it != g.developer }).joinToString(", "),
        developer = g.developer,
        extra = mapOf("entitlementId" to g.entitlementId, "sku" to g.productSku),
    )
}
