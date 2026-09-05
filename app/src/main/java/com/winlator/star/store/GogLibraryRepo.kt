package com.winlator.star.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The GOG library as the storefront host reads it: the `gog_library_cache` JSON the games screen
 * writes, plus a sync that mirrors [GogGamesActivity]'s incremental owned-id diff so the Library
 * tab fills itself on first open instead of waiting for the full games screen to be visited.
 *
 * Writes the SAME cache, DLC buffer (`gog_dlcs_<baseId>`) and per-id prefs (`gog_gen_`,
 * `gog_release_`, `gog_rating_`, `gog_size_`, `gog_vcover_`) the games screen writes, so the two
 * surfaces never disagree. Install state (`gog_exe_` / `gog_dir_`) is READ only — that stays owned
 * by [GogInstallState] and the download engine.
 */
object GogLibraryRepo {

    private const val TAG = "GogLibrary"
    private const val PREFS = "bh_gog_prefs"
    private const val CACHE_KEY = "gog_library_cache"
    private const val LAST_SYNC_KEY = "gog_library_synced_at"
    private const val THROTTLE_MS = 15L * 60L * 1000L
    private const val VCOVER_PREFIX = "gog_vcover_"
    private const val SGDB_KEY = "cf89227f12c773bb1117b6b109ae1659"

    private val syncing = AtomicBoolean(false)

    sealed interface SyncResult {
        data class Ok(val games: List<GogGame>, val fetched: Int) : SyncResult
        data class Failed(val message: String) : SyncResult
        data object NotLoggedIn : SyncResult
        data object Busy : SyncResult
    }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, 0)

    fun isLoggedIn(ctx: Context): Boolean = prefs(ctx).getString("access_token", null) != null

    /** The cached library, alphabetical. Empty until the first sync. */
    fun cached(ctx: Context): List<GogGame> {
        val json = prefs(ctx).getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                GogGame(
                    o.optString("gameId", ""),
                    o.optString("title", ""),
                    o.optString("imageUrl", ""),
                    o.optString("description", ""),
                    o.optString("developer", ""),
                    o.optString("category", ""),
                    o.optInt("generation", 1),
                    o.optString("verticalCover", "").ifEmpty { null },
                )
            }.filter { it.gameId.isNotEmpty() }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** Games with a recorded launch exe AND install dir — what the tiles call "installed". */
    fun installedIds(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val out = HashSet<String>()
        for (key in p.all.keys) {
            if (!key.startsWith("gog_exe_")) continue
            val id = key.removePrefix("gog_exe_")
            if (p.getString("gog_dir_$id", null) != null) out.add(id)
        }
        return out
    }

    /**
     * A usable access token, refreshing through [GogTokenRefresh] when the recorded login time says
     * it has expired. Null when signed out or the refresh failed.
     */
    fun validToken(ctx: Context): String? {
        val p = prefs(ctx)
        var token = p.getString("access_token", null) ?: return null
        val loginTime = p.getInt("bh_gog_login_time", 0)
        val expiresIn = p.getInt("bh_gog_expires_in", 3600)
        val nowSec = System.currentTimeMillis() / 1000L
        if (loginTime == 0 || nowSec >= loginTime + expiresIn) {
            token = GogTokenRefresh.refresh(ctx) ?: return null
        }
        return token
    }

    /**
     * Sync the library: owned-id diff against the cache, fetch only what is new (or everything
     * when [force] / empty cache / 15 min stale — the games screen's exact policy).
     */
    suspend fun sync(
        ctx: Context,
        force: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        if (!syncing.compareAndSet(false, true)) return@withContext SyncResult.Busy
        try {
            val appCtx = ctx.applicationContext
            val p = prefs(appCtx)
            val token = validToken(appCtx) ?: return@withContext SyncResult.NotLoggedIn

            onStatus("Fetching game list…")
            val gamesJson = StoreNet.get("https://embed.gog.com/user/data/games", bearer = token, userAgent = "GOG Galaxy")
                ?: return@withContext SyncResult.Failed("Couldn't reach GOG")
            val ids = ArrayList<String>()
            val parsed = runCatching {
                val owned = JSONObject(gamesJson).optJSONArray("owned")
                if (owned != null) for (i in 0 until owned.length()) {
                    val id = owned.optLong(i).toString()
                    if (id != "1801418160" && id != "0") ids.add(id)
                }
            }.isSuccess
            if (!parsed) return@withContext SyncResult.Failed("Error parsing library")

            val ownedSet = ids.toHashSet()
            val cachedList = cached(appCtx)
            val lastSync = p.getLong(LAST_SYNC_KEY, 0L)
            val stale = System.currentTimeMillis() - lastSync >= THROTTLE_MS
            val heavy = force || cachedList.isEmpty() || stale
            val cachedIds = cachedList.mapTo(HashSet()) { it.gameId }
            val idsToFetch = if (heavy) ids else ids.filter { it !in cachedIds }
            val fetchSet = idsToFetch.toHashSet()

            val merged = LinkedHashMap<String, GogGame>()
            for (g in cachedList) if (g.gameId in ownedSet && g.gameId !in fetchSet) merged[g.gameId] = g

            if (idsToFetch.isEmpty()) {
                val list = merged.values.toList()
                saveCache(p, list)
                return@withContext SyncResult.Ok(list.sortedBy { it.title.lowercase() }, 0)
            }

            onStatus("Syncing ${idsToFetch.size} game${if (idsToFetch.size == 1) "" else "s"}…")
            val dlcBuffer = HashMap<String, MutableList<Pair<String, String>>>()
            val pool = Executors.newFixedThreadPool(5)
            val futures = idsToFetch.map { id ->
                pool.submit(Callable<GogGame?> { fetchGame(p, id, token, dlcBuffer) })
            }
            pool.shutdown()
            var fetched = 0
            for ((idx, f) in futures.withIndex()) {
                val g = runCatching { f.get() }.getOrNull()
                if (g != null) { merged[g.gameId] = g; fetched++ }
                if ((idx + 1) % 5 == 0) onStatus("Syncing… ${idx + 1}/${futures.size}")
            }
            saveDlcBuffer(p, dlcBuffer)
            val finalList = merged.values.toList()
            saveCache(p, finalList)
            if (heavy) p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            Log.i(TAG, "sync: owned=${ids.size} fetched=$fetched cached=${finalList.size} heavy=$heavy")
            SyncResult.Ok(finalList.sortedBy { it.title.lowercase() }, fetched)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    // ── Per-game fetch (mirror of GogGamesActivity.fetchGame) ─────────────────────────────────

    private fun httpGet(url: String, token: String?): String? = StoreNet.get(url, bearer = token)

    private fun fetchGame(
        prefs: SharedPreferences,
        id: String,
        token: String,
        dlcBuffer: MutableMap<String, MutableList<Pair<String, String>>>,
    ): GogGame? {
        try {
            val productJson = httpGet("https://api.gog.com/products/$id?expand=downloads,description", token)
                ?: return null
            val prod = JSONObject(productJson)
            if (prod.optBoolean("is_secret", false)) return null
            val gameType = prod.optString("game_type", "")
            if ("dlc" == gameType) {
                storeDlc(id, prod, dlcBuffer)
                return null
            }
            if (gameType.isNotEmpty() && gameType != "game" && gameType != "pack") return null

            val titleObj = prod.optJSONObject("title")
            var titleStr = titleObj?.optString("*")
            if (titleStr.isNullOrEmpty()) titleStr = prod.optString("title")
            if (titleStr.isNullOrEmpty()) return null

            var imageUrl = sgdbFetchCover(titleStr)
            if (imageUrl.isEmpty()) {
                val images = prod.optJSONObject("images")
                imageUrl = images?.optString("icon", "") ?: ""
                if (imageUrl.isEmpty()) imageUrl = images?.optString("background", "") ?: ""
            }

            val desc = prod.optJSONObject("description")?.optString("lead", "") ?: ""
            val company = prod.optJSONObject("developers")
            val developer = company?.optString("name", "") ?: prod.optString("developer", "")
            val genres = prod.optJSONArray("genres")
            val category = if (genres != null && genres.length() > 0) {
                genres.optJSONObject(0)?.optString("name", "") ?: ""
            } else ""

            var generation = 1
            var hasWindowsBuild = false
            try {
                val buildsJson = httpGet(
                    "https://content-system.gog.com/products/$id/os/windows/builds?generation=2", token,
                )
                if (buildsJson != null) {
                    val bitems = JSONObject(buildsJson).optJSONArray("items")
                    if (bitems != null && bitems.length() > 0) {
                        hasWindowsBuild = true
                        var maxGen = 0
                        for (bi in 0 until bitems.length()) {
                            val g = bitems.optJSONObject(bi)?.optInt("generation", 0) ?: 0
                            if (g > maxGen) maxGen = g
                        }
                        if (maxGen > 0) generation = maxGen
                    }
                }
            } catch (_: Exception) {}
            prefs.edit().putInt("gog_gen_$id", generation).apply()

            val releaseDate = prod.optString("release_date", "")
            if (releaseDate.isNotEmpty()) prefs.edit().putString("gog_release_$id", releaseDate).apply()
            val rating = prod.optInt("rating", -1)
            if (rating >= 0) prefs.edit().putInt("gog_rating_$id", rating).apply()

            if (prefs.getLong("gog_size_$id", -1) <= 0) {
                val size = GogDownloadManager.fetchInstallSizeBytes(id, token)
                if (size > 0) prefs.edit().putLong("gog_size_$id", size).apply()
            }

            val downloads = prod.optJSONObject("downloads")
            var hasWindowsInstaller = false
            val installersArr = downloads?.optJSONArray("installers")
            if (installersArr != null) for (di in 0 until installersArr.length()) {
                if (installersArr.optJSONObject(di)?.optString("os", "") == "windows") {
                    hasWindowsInstaller = true; break
                }
            }
            if (!hasWindowsBuild && !hasWindowsInstaller) return null

            var verticalCover: String? = prefs.getString("$VCOVER_PREFIX$id", null)
            if (verticalCover.isNullOrEmpty()) {
                verticalCover = fetchVerticalCover(id)
                if (!verticalCover.isNullOrEmpty()) prefs.edit().putString("$VCOVER_PREFIX$id", verticalCover).apply()
            }

            return GogGame(id, titleStr, imageUrl, desc, developer, category, generation, verticalCover)
        } catch (_: Exception) {
            return null
        }
    }

    private fun sgdbFetchCover(title: String): String = try {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val searchJson = httpGet("https://www.steamgriddb.com/api/v2/search/autocomplete/$encoded", SGDB_KEY)
        val results = searchJson?.let { JSONObject(it).optJSONArray("data") }
        if (results == null || results.length() == 0) "" else {
            val gameId = results.getJSONObject(0).getInt("id")
            val gridsJson = httpGet(
                "https://www.steamgriddb.com/api/v2/grids/game/$gameId?dimensions=600x900&mimes=image/jpeg,image/png&limit=1",
                SGDB_KEY,
            )
            val grids = gridsJson?.let { JSONObject(it).optJSONArray("data") }
            if (grids == null || grids.length() == 0) "" else grids.getJSONObject(0).optString("url", "")
        }
    } catch (_: Exception) { "" }

    private fun fetchVerticalCover(productId: String): String? = try {
        val extJson = httpGet("https://gamesdb.gog.com/platforms/gog/external_releases/$productId", null)
        val gameId = extJson?.let { JSONObject(it).optString("game_id", "") }.orEmpty()
        if (gameId.isEmpty()) null else {
            val gameJson = httpGet("https://gamesdb.gog.com/games/$gameId", null)
            val vc = gameJson?.let { JSONObject(it).optJSONObject("vertical_cover") }
            val fmt = vc?.optString("url_format", "").orEmpty()
            if (fmt.isEmpty()) null else fmt.replace("{formatter}", "").replace("{ext}", "webp")
        }
    } catch (_: Exception) { null }

    private fun storeDlc(dlcId: String, prod: JSONObject, buffer: MutableMap<String, MutableList<Pair<String, String>>>) {
        try {
            var dlcTitle = prod.optJSONObject("title")?.optString("*", "").orEmpty()
            if (dlcTitle.isEmpty()) dlcTitle = prod.optString("title", "")
            if (dlcTitle.isEmpty()) dlcTitle = "Unknown DLC"
            var baseId = prod.optJSONObject("required_game")?.optString("id", "").orEmpty()
            if (baseId.isEmpty()) {
                val reqArr = prod.optJSONArray("requiredGames")
                if (reqArr != null && reqArr.length() > 0) baseId = reqArr.optString(0, "")
            }
            if (baseId.isEmpty()) return
            synchronized(buffer) {
                buffer.getOrPut(baseId) { ArrayList() }.add(dlcId to dlcTitle)
            }
        } catch (_: Exception) {}
    }

    private fun saveDlcBuffer(prefs: SharedPreferences, buffer: Map<String, List<Pair<String, String>>>) {
        for ((baseId, dlcs) in buffer) {
            runCatching {
                val arr = JSONArray()
                for ((id, title) in dlcs) arr.put(JSONObject().put("id", id).put("title", title))
                prefs.edit().putString("gog_dlcs_$baseId", arr.toString()).apply()
            }
        }
    }

    private fun saveCache(prefs: SharedPreferences, games: List<GogGame>) {
        runCatching {
            val arr = JSONArray()
            for (g in games) {
                val o = JSONObject()
                o.put("gameId", g.gameId)
                o.put("title", g.title)
                o.put("imageUrl", g.imageUrl)
                o.put("description", g.description)
                o.put("developer", g.developer)
                o.put("category", g.category)
                o.put("generation", g.generation)
                if (!g.verticalCover.isNullOrEmpty()) o.put("verticalCover", g.verticalCover)
                arr.put(o)
            }
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply()
        }
    }

    /** A cached [GogGame] as a [CatalogItem] for the shared tiles. */
    fun toCatalogItem(g: GogGame): CatalogItem = CatalogItem(
        store = com.winlator.star.store.download.Store.GOG,
        id = g.gameId,
        title = g.title,
        imageUrl = GogStoreCatalog.absolutize(g.imageUrl).ifBlank { null },
        tallImageUrl = GogStoreCatalog.absolutize(g.verticalCover ?: g.imageUrl).ifBlank { null },
        tags = g.category,
        developer = g.developer,
        description = g.description,
    )
}
