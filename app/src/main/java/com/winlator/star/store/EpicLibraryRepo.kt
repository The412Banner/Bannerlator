package com.winlator.star.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.winlator.star.store.download.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Epic library as the storefront host reads it: the `epic_cache` JSON the games screen writes,
 * plus a sync that mirrors [EpicGamesActivity]'s (library API → per-title catalog enrich → cache),
 * so the Library tab fills itself on first open.
 *
 * Writes the SAME cache, DLC map (`epic_dlcs_<baseCatalogItemId>`), `epic_release_<appName>` and
 * the cloud-save folder prefs the games screen writes, so the two surfaces never disagree. Install
 * state (`epic_exe_` / `epic_dir_`) is READ only — that stays with [EpicInstallState].
 */
object EpicLibraryRepo {

    private const val TAG = "EpicLibrary"
    private const val PREFS = "bh_epic_prefs"
    private const val CACHE_KEY = "epic_cache"
    private const val LAST_SYNC_KEY = "epic_library_synced_at"
    private const val THROTTLE_MS = 15L * 60L * 1000L

    private val syncing = AtomicBoolean(false)

    sealed interface SyncResult {
        data class Ok(val games: List<EpicGame>, val fetched: Int) : SyncResult
        data class Failed(val message: String) : SyncResult
        data object NotLoggedIn : SyncResult
        data object Busy : SyncResult
        data object Throttled : SyncResult
    }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, 0)

    fun cached(ctx: Context): List<EpicGame> {
        val json = prefs(ctx).getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val list = ArrayList<EpicGame>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val g = EpicGame()
                g.appName = j.optString("appName", "")
                g.namespace = j.optString("namespace", "")
                g.catalogItemId = j.optString("catalogItemId", "")
                g.title = j.optString("title", "")
                g.artCover = j.optString("artCover", "")
                g.artSquare = j.optString("artSquare", "")
                g.developer = j.optString("developer", "")
                g.description = j.optString("description", "")
                g.version = j.optString("version", "")
                g.isInstalled = j.optBoolean("isInstalled", false)
                g.installPath = j.optString("installPath", "")
                val cachedSize = j.optLong("installSize", 0L)
                g.installSize = if (cachedSize > 1_099_511_627_776L) 0L else cachedSize
                g.canRunOffline = j.optBoolean("canRunOffline", true)
                g.cloudSaveEnabled = j.optBoolean("cloudSaveEnabled", false)
                g.cloudSaveFolder = j.optString("cloudSaveFolder", "")
                if (g.appName.isNotEmpty()) list.add(g)
            }
            list.distinctBy { it.appName }.sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** appNames with a recorded launch exe — what the tiles call "installed". */
    fun installedIds(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val out = HashSet<String>()
        for (key in p.all.keys) if (key.startsWith("epic_exe_")) out.add(key.removePrefix("epic_exe_"))
        return out
    }

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
                return@withContext SyncResult.Throttled
            }
            onStatus("Checking credentials…")
            val token = EpicCredentialStore.getValidAccessToken(appCtx) ?: return@withContext SyncResult.NotLoggedIn

            onStatus("Fetching game list…")
            val rawGames = EpicApiClient.getLibraryItems(token)
            if (rawGames.isNullOrEmpty()) {
                return@withContext if (cachedList.isEmpty()) SyncResult.Failed("No games found in your Epic library")
                else SyncResult.Ok(cachedList, 0)
            }

            val total = rawGames.size
            var done = 0
            for (game in rawGames) {
                EpicApiClient.enrichFromCatalog(token, game)
                if (game.releaseDate.isNotEmpty()) {
                    p.edit().putString("epic_release_${game.appName}", game.releaseDate).apply()
                }
                done++
                if (done % 5 == 0) onStatus("Loading game details… ($done/$total)")
            }

            val mainGames = ArrayList<EpicGame>()
            val dlcMap = HashMap<String, JSONArray>()
            for (g in rawGames) {
                if (!g.isDLC) mainGames.add(g)
                else if (g.baseGameCatalogItemId.isNotEmpty()) {
                    val arr = dlcMap.getOrPut(g.baseGameCatalogItemId) { JSONArray() }
                    runCatching {
                        arr.put(JSONObject().put("app", g.appName).put("ns", g.namespace).put("cat", g.catalogItemId).put("title", g.title))
                    }
                }
            }
            val ed = p.edit()
            for ((key, value) in dlcMap) ed.putString("epic_dlcs_$key", value.toString())
            ed.apply()

            val display = (if (mainGames.isEmpty()) rawGames else mainGames).distinctBy { it.appName }.toMutableList()
            display.sortWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            for (fresh in display) {
                val old = cachedList.firstOrNull { it.appName == fresh.appName } ?: continue
                fresh.isInstalled = old.isInstalled
                fresh.installPath = old.installPath
                fresh.version = old.version
                fresh.installSize = old.installSize
            }
            saveCache(p, display)
            p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
            Log.i(TAG, "sync: raw=${rawGames.size} shown=${display.size} dlcBases=${dlcMap.size}")
            SyncResult.Ok(display, display.size)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            SyncResult.Failed(e.message ?: "Sync failed")
        } finally {
            syncing.set(false)
        }
    }

    private fun saveCache(prefs: SharedPreferences, games: List<EpicGame>) {
        runCatching {
            val arr = JSONArray()
            for (g in games) {
                val j = JSONObject()
                j.put("appName", g.appName)
                j.put("namespace", g.namespace)
                j.put("catalogItemId", g.catalogItemId)
                j.put("title", g.title)
                j.put("artCover", g.artCover)
                j.put("artSquare", g.artSquare)
                j.put("developer", g.developer)
                j.put("description", g.description)
                j.put("version", g.version)
                j.put("isInstalled", g.isInstalled)
                j.put("installPath", g.installPath)
                j.put("installSize", g.installSize)
                j.put("canRunOffline", g.canRunOffline)
                j.put("cloudSaveEnabled", g.cloudSaveEnabled)
                j.put("cloudSaveFolder", g.cloudSaveFolder)
                arr.put(j)
            }
            val ed = prefs.edit().putString(CACHE_KEY, arr.toString())
            for (g in games) {
                if (g.appName.isEmpty()) continue
                ed.putBoolean("epic_cloud_checked_${g.appName}", true)
                if (g.cloudSaveFolder.isNotEmpty()) ed.putString("epic_save_folder_${g.appName}", g.cloudSaveFolder)
                else ed.remove("epic_save_folder_${g.appName}")
            }
            ed.apply()
        }
    }

    /** A cached [EpicGame] as a [CatalogItem] for the shared tiles. */
    fun toCatalogItem(g: EpicGame): CatalogItem = CatalogItem(
        store = Store.EPIC,
        id = g.appName,
        title = g.title.ifBlank { g.appName },
        imageUrl = g.artSquare.ifBlank { null } ?: g.artCover.ifBlank { null },
        tallImageUrl = g.artCover.ifBlank { null } ?: g.artSquare.ifBlank { null },
        developer = g.developer,
        description = g.description,
        extra = mapOf("namespace" to g.namespace, "catalogItemId" to g.catalogItemId),
    )
}
