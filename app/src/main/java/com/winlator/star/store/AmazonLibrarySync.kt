package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.MediaImage
import com.winlator.star.store.download.MediaVideo
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreMedia
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Amazon-side seeding for the cross-store Download Manager (Phase A).
 *
 * Mirror of [SteamLibrarySync]: bridges the on-disk Amazon library into the
 * store-agnostic [DownloadRegistry] so the Library section isn't empty on first open.
 * Amazon has no DB — installed state lives entirely in the `bh_amazon_prefs` file, so
 * this walks the `amazon_dir_<productId>` keys, confirms each is really installed via
 * the engine's on-disk marker ([AmazonDownloadManager.isInstalled]) or a recorded
 * launch exe, and upserts an INSTALLED [DownloadEntry] per game (which the registry
 * persists to its durable library).
 *
 * Name / cover come from the `amazon_library_cache` JSON the games screen writes (same
 * prefs); missing metadata falls back to the productId so a row still renders.
 *
 * Lives on the Amazon side ON PURPOSE — the registry imports zero store types. Call once
 * early (see [AmazonGameDetailActivity.onCreate] / [AmazonGamesActivity.onCreate]), right
 * after [DownloadRegistry.init]. Idempotent; never throws into startup.
 */
object AmazonLibrarySync {

    private const val TAG = "AmazonLibrarySync"
    private const val PREFS_NAME = "bh_amazon_prefs"
    private const val CACHE_KEY = "amazon_library_cache"
    private const val DIR_PREFIX = "amazon_dir_"
    // Suffix AmazonGamesActivity.checkForUpdates stamps onto a cached versionId when a newer
    // build exists; the store list keys its amber "Update Available" text off the same suffix.
    private const val UPDATE_SUFFIX = "_UPDATE_AVAILABLE"

    /** Cached per-game metadata pulled from `amazon_library_cache` (best-effort). */
    private data class Meta(val title: String, val cover: String?, val update: Boolean)

    /**
     * Full detail-page extras for one game, hydrated from `amazon_library_cache`. Lets a caller
     * that only holds a productId (e.g. the cross-store Download Manager card) open
     * [AmazonGameDetailActivity] with the same extras [AmazonGamesActivity.openDetailScreen] uses.
     */
    data class DetailExtras(
        val entitlementId: String,
        val title: String,
        val developer: String,
        val publisher: String,
        val artUrl: String,
        val productSku: String,
    )

    /**
     * Look up a game's cached detail metadata by productId, or null if it isn't in the cache
     * (e.g. the Amazon store was never opened this install). Never throws — a parse failure
     * returns null so the caller can fall back to whatever it already holds.
     */
    fun cachedDetail(ctx: Context, productId: String): DetailExtras? {
        if (productId.isEmpty()) return null
        return runCatching {
            val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null) ?: return null
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                if (j.optString("productId", "") != productId) continue
                val art = j.optString("artUrl", "").ifEmpty { j.optString("heroUrl", "") }
                return DetailExtras(
                    entitlementId = j.optString("entitlementId", ""),
                    title = j.optString("title", ""),
                    developer = j.optString("developer", ""),
                    publisher = j.optString("publisher", ""),
                    artUrl = art,
                    productSku = j.optString("productSku", ""),
                )
            }
            null
        }.getOrNull()
    }

    // ── Media (screenshots + trailers) ─────────────────────────────────────────────────────
    // Amazon has no media endpoint: everything comes with GetEntitlements and lives in the
    // library cache, so the Media tab is offline and costs no request — but a cache written
    // before these keys existed has no media until the library re-syncs.

    private const val KEY_SHOTS = "screenshots"
    private const val KEY_VIDEOS = "videos"
    private const val KEY_TRAILER_IMG = "trailerImageUrl"

    /** Writes the media lists of [g] into a cache row; shared by every `amazon_library_cache` writer. */
    fun putMedia(j: JSONObject, g: AmazonGame) {
        j.put(KEY_SHOTS, JSONArray(g.screenshots))
        j.put(KEY_VIDEOS, JSONArray(g.videos))
        j.put(KEY_TRAILER_IMG, g.trailerImageUrl)
    }

    /** Reads the media lists of a cache row into [g]; missing keys leave them empty. */
    fun readMedia(j: JSONObject, g: AmazonGame) {
        g.screenshots = stringList(j.optJSONArray(KEY_SHOTS))
        g.videos = stringList(j.optJSONArray(KEY_VIDEOS))
        g.trailerImageUrl = j.optString(KEY_TRAILER_IMG, "")
    }

    private fun stringList(arr: JSONArray?): MutableList<String> {
        val out = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    /**
     * The cached media of one game as the shared [StoreMedia], or null when the cache has none
     * for it (never synced, or synced before media was recorded). Videos are direct mp4s; the
     * poster is Amazon's trailer image, else the first screenshot. Never throws.
     */
    fun cachedMedia(ctx: Context, productId: String): StoreMedia? {
        if (productId.isEmpty()) return null
        return runCatching {
            val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null) ?: return null
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                if (j.optString("productId", "") != productId) continue
                return mediaOf(j)
            }
            null
        }.getOrNull()
    }

    /** Pure mapping of one cache row to [StoreMedia] (null when it has no media). */
    fun mediaOf(j: JSONObject): StoreMedia? {
        val g = AmazonGame()
        readMedia(j, g)
        val shots = g.screenshots.take(24).map { MediaImage(thumb = it, full = it) }
        val poster = g.trailerImageUrl.ifBlank { null } ?: shots.firstOrNull()?.thumb
        val videos = g.videos.mapIndexed { i, url ->
            MediaVideo.Direct(url = url, poster = poster, title = if (g.videos.size == 1) "Trailer" else "Trailer ${i + 1}")
        }
        return StoreMedia(shots, videos).takeIf { !it.isEmpty }
    }

    private val seeded = AtomicBoolean(false)

    /**
     * One-time sync of the installed Amazon library into the registry. Safe to call
     * repeatedly; only the first call does work. Never throws — a seeding failure must
     * not take down store startup.
     */
    fun seed(ctx: Context) {
        if (!seeded.compareAndSet(false, true)) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // productId -> cached metadata from the games-screen cache (best-effort). Cover
            // mirrors the store list's `artUrl.ifEmpty { heroUrl }` so the Manager card shows
            // the same art. updateAvailable rides the cached versionId's "_UPDATE_AVAILABLE"
            // suffix (set by AmazonGamesActivity.checkForUpdates) — the exact signal the list uses.
            val meta = HashMap<String, Meta>()
            prefs.getString(CACHE_KEY, null)?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val j = arr.optJSONObject(i) ?: continue
                        val pid = j.optString("productId", "")
                        if (pid.isEmpty()) continue
                        val title = j.optString("title", "")
                        val cover = j.optString("artUrl", "")
                            .ifEmpty { j.optString("heroUrl", "") }
                            .ifEmpty { null }
                        val update = j.optString("versionId", "").endsWith(UPDATE_SUFFIX)
                        meta[pid] = Meta(title, cover, update)
                    }
                }
            }

            var added = 0
            var healed = 0
            // Snapshot the keys — walking prefs.all directly while upserting is fine (no
            // concurrent writer at startup), but a copy keeps it obviously safe.
            for ((k, v) in prefs.all) {
                if (!k.startsWith(DIR_PREFIX)) continue
                val productId = k.substring(DIR_PREFIX.length)
                if (productId.isEmpty()) continue
                val dir = (v as? String) ?: continue
                if (dir.isEmpty()) continue

                val installDir = File(dir)
                val key = "${Store.AMAZON}:$productId"

                // A live/queued download already owns this key and writes amazon_dir_ BEFORE the
                // completion marker exists — never seed a duplicate nor (below) self-heal it out
                // from under an in-flight install. Leave it entirely to the producer.
                if (DownloadRegistry.isActive(key)) continue

                // Truth = the install must still EXIST ON DISK. Previously `exe != null` alone
                // counted as installed, which resurrected zombies: an uninstall via the cross-
                // store Manager deleted the files but left these prefs, so the next cold start
                // seeded a phantom. Require the completion marker OR a launch exe that is really
                // still present.
                val exe = prefs.getString("amazon_exe_$productId", null)
                val installed = AmazonDownloadManager.isInstalled(installDir) ||
                    (exe != null && File(exe).exists())

                if (!installed) {
                    // Self-heal: the files are gone (uninstalled elsewhere, or a failed/partial
                    // install left orphan prefs). Purge the stale native record and drop any
                    // durable-library row so all surfaces agree the game is NOT installed.
                    AmazonInstallState.purge(ctx, productId)
                    DownloadRegistry.removeLibraryEntry(key)
                    healed++
                    continue
                }

                val m = meta[productId] ?: Meta(productId, null, false)
                val name = m.title.ifEmpty { productId }
                val bytes = prefs.getLong("amazon_size_$productId", 0L).let { if (it > 0L) it else 0L }

                DownloadRegistry.upsert(
                    DownloadEntry(
                        store = Store.AMAZON,
                        id = productId,
                        name = name,
                        cover = m.cover,
                        state = DownloadState.INSTALLED,
                        pct = 100,
                        installDone = bytes,
                        installTotal = bytes,
                        supportsPause = false,
                        installPath = installDir.absolutePath,
                        updateAvailable = m.update,
                    )
                )
                added++
            }
            Log.i(TAG, "Seeded $added installed Amazon game(s), self-healed $healed orphan(s)")
        } catch (t: Throwable) {
            // Reset so a later call (once prefs are ready) can retry.
            seeded.set(false)
            Log.w(TAG, "Library seed skipped: ${t.message}")
        }
    }
}
