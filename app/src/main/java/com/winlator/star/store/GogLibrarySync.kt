package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GOG-side seeding for the cross-store Download Manager (Phase B). Direct mirror of
 * [AmazonLibrarySync].
 *
 * Bridges the on-disk GOG library into the store-agnostic [DownloadRegistry] so the Library
 * section isn't empty on first open. GOG has no DB — installed state lives entirely in the
 * `bh_gog_prefs` file, so this walks the `gog_dir_<gameId>` keys, confirms each is really
 * installed ON DISK (resolved install dir exists AND the recorded launch exe still exists),
 * and upserts an INSTALLED [DownloadEntry] per game (which the registry persists to its durable
 * library). Orphans — files deleted elsewhere but prefs left behind — are self-healed: their
 * native record is purged and any durable-library row dropped, so no zombie is resurrected.
 *
 * Name / cover come from the `gog_library_cache` JSON the games screen writes (same prefs);
 * cover is normalized (`//host/...` → `https://host/...`, matching the detail page's
 * `loadHeaderImage`). Missing metadata falls back to the gameId so a row still renders.
 *
 * Lives on the GOG side ON PURPOSE — the registry imports zero store types. Call once early
 * (see [GogGameDetailActivity.onCreate] / [GogGamesActivity.onCreate]), right after
 * [DownloadRegistry.init]. Idempotent; never throws into startup.
 */
object GogLibrarySync {

    private const val TAG = "GogLibrarySync"
    private const val PREFS_NAME = "bh_gog_prefs"
    private const val CACHE_KEY = "gog_library_cache"
    private const val DIR_PREFIX = "gog_dir_"

    /** Cached per-game metadata pulled from `gog_library_cache` (best-effort). */
    private data class Meta(val title: String, val cover: String?)

    /**
     * Full detail-page extras for one game, hydrated from `gog_library_cache`. Lets a caller that
     * only holds a gameId (e.g. the cross-store Download Manager card) open [GogGameDetailActivity]
     * with the same extras [GogGamesActivity.openDetailScreen] uses.
     */
    data class DetailExtras(
        val title: String,
        val imageUrl: String,
        val description: String,
        val developer: String,
        val category: String,
        val generation: Int,
        // gap #8: additive, defaulted → every existing construction/read is unaffected. Lets the
        // Save Manager / Download Manager optionally prefer the GOG 2:3 vertical cover when present.
        val verticalCover: String? = null,
    )

    /** `//host/img.jpg` → `https://host/img.jpg`; null/blank → null; already-absolute → unchanged. */
    private fun normalizeCover(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    /**
     * Look up a game's cached detail metadata by gameId, or null if it isn't in the cache (e.g.
     * the GOG store was never opened this install). Never throws — a parse failure returns null so
     * the caller can fall back to whatever it already holds. Cover is normalized.
     */
    fun cachedDetail(ctx: Context, gameId: String): DetailExtras? {
        if (gameId.isEmpty()) return null
        return runCatching {
            val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null) ?: return null
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                if (j.optString("gameId", "") != gameId) continue
                return DetailExtras(
                    title = j.optString("title", ""),
                    imageUrl = normalizeCover(j.optString("imageUrl", "")) ?: "",
                    description = j.optString("description", ""),
                    developer = j.optString("developer", ""),
                    category = j.optString("category", ""),
                    generation = j.optInt("generation", 0),
                    verticalCover = normalizeCover(j.optString("verticalCover", "")),
                )
            }
            null
        }.getOrNull()
    }

    private val seeded = AtomicBoolean(false)

    /**
     * One-time sync of the installed GOG library into the registry. Safe to call repeatedly; only
     * the first call does work. Never throws — a seeding failure must not take down store startup.
     */
    fun seed(ctx: Context) {
        if (!seeded.compareAndSet(false, true)) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // gameId -> cached metadata from the games-screen cache (best-effort). Cover normalized
            // so the Manager card shows the same art the detail page loads.
            val meta = HashMap<String, Meta>()
            prefs.getString(CACHE_KEY, null)?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val j = arr.optJSONObject(i) ?: continue
                        val id = j.optString("gameId", "")
                        if (id.isEmpty()) continue
                        meta[id] = Meta(j.optString("title", ""), normalizeCover(j.optString("imageUrl", "")))
                    }
                }
            }

            var added = 0
            var healed = 0
            for ((k, v) in prefs.all) {
                if (!k.startsWith(DIR_PREFIX)) continue
                val gameId = k.substring(DIR_PREFIX.length)
                if (gameId.isEmpty()) continue
                val dirName = (v as? String) ?: continue
                if (dirName.isEmpty()) continue

                val key = "${Store.GOG}:$gameId"

                // A live/queued download already owns this key and writes gog_dir_ before the exe is
                // recorded — never seed a duplicate nor self-heal it out from under an in-flight
                // install. Leave it entirely to the producer. (Guard BEFORE the disk-truth check.)
                if (DownloadRegistry.isActive(key)) continue

                // Truth = the install must still EXIST ON DISK: resolved install dir present AND the
                // recorded launch exe present. Prevents zombie rows after an uninstall that deleted
                // files but left prefs.
                val installPath = GogInstallPath.getInstallDir(ctx, dirName)
                val exe = prefs.getString("gog_exe_$gameId", null)
                val installed = installPath.exists() && exe != null && File(exe).exists()

                if (!installed) {
                    // Self-heal: purge the stale native record + drop any durable-library row.
                    GogInstallState.purge(ctx, gameId)
                    DownloadRegistry.removeLibraryEntry(key)
                    healed++
                    continue
                }

                val m = meta[gameId] ?: Meta(gameId, null)
                val name = m.title.ifEmpty { gameId }
                val bytes = prefs.getLong("gog_size_$gameId", 0L).let { if (it > 0L) it else 0L }

                DownloadRegistry.upsert(
                    DownloadEntry(
                        store = Store.GOG,
                        id = gameId,
                        name = name,
                        cover = m.cover,
                        state = DownloadState.INSTALLED,
                        pct = 100,
                        installDone = bytes,
                        installTotal = bytes,
                        supportsPause = false,
                        installPath = installPath.absolutePath,
                        // GOG has no offline "newer build" signal (its update check is an on-demand
                        // network call), so leave update-available false at seed time.
                        updateAvailable = false,
                    )
                )
                added++
            }
            Log.i(TAG, "Seeded $added installed GOG game(s), self-healed $healed orphan(s)")
        } catch (t: Throwable) {
            seeded.set(false)   // reset so a later call (once prefs are ready) can retry
            Log.w(TAG, "Library seed skipped: ${t.message}")
        }
    }
}
