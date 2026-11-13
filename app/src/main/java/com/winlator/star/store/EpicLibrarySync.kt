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
 * Epic-side seeding for the cross-store Download Manager (Phase C). Direct mirror of
 * [AmazonLibrarySync] / [GogLibrarySync].
 *
 * Bridges the on-disk Epic library into the store-agnostic [DownloadRegistry] so the Library
 * section isn't empty on first open. Epic has no DB — installed state lives entirely in the
 * `bh_epic_prefs` file, so this walks the `epic_dir_<appName>` keys, confirms each is really
 * installed ON DISK (install dir exists AND the recorded launch exe still exists), and upserts an
 * INSTALLED [DownloadEntry] per game (which the registry persists to its durable library). Orphans
 * — files deleted elsewhere but prefs left behind — are self-healed: their native record is purged
 * and any durable-library row dropped, so no zombie is resurrected.
 *
 * Name / cover come from the `epic_cache` JSON the games screen writes (same prefs); cover is the
 * `artCover` (falling back to `artSquare`). Missing metadata falls back to the appName so a row
 * still renders.
 *
 * Lives on the Epic side ON PURPOSE — the registry imports zero store types. Call once early (see
 * [EpicGameDetailActivity.onCreate] / [EpicGamesActivity.onCreate]), right after
 * [DownloadRegistry.init]. Idempotent; never throws into startup.
 */
object EpicLibrarySync {

    private const val TAG = "EpicLibrarySync"
    private const val PREFS_NAME = "bh_epic_prefs"
    private const val CACHE_KEY = "epic_cache"
    private const val DIR_PREFIX = "epic_dir_"

    /** Cached per-game metadata pulled from `epic_cache` (best-effort). */
    private data class Meta(val title: String, val cover: String?)

    /**
     * Full detail-page extras for one game, hydrated from `epic_cache`. Lets a caller that only
     * holds an appName (e.g. the cross-store Download Manager card) open [EpicGameDetailActivity]
     * with the same extras [EpicGamesActivity.openDetailScreen] uses.
     */
    data class DetailExtras(
        val title: String,
        val description: String,
        val developer: String,
        val artCover: String,
        val namespace: String,
        val catalogItemId: String,
    )

    /** artCover, or artSquare fallback; blank → null. */
    private fun coverOf(j: org.json.JSONObject): String? =
        j.optString("artCover", "").ifEmpty { j.optString("artSquare", "") }.ifEmpty { null }

    /**
     * Look up a game's cached detail metadata by appName, or null if it isn't in the cache (e.g.
     * the Epic store was never opened this install). Never throws — a parse failure returns null so
     * the caller can fall back to whatever it already holds.
     */
    fun cachedDetail(ctx: Context, appName: String): DetailExtras? {
        if (appName.isEmpty()) return null
        return runCatching {
            val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null) ?: return null
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                if (j.optString("appName", "") != appName) continue
                return DetailExtras(
                    title = j.optString("title", ""),
                    description = j.optString("description", ""),
                    developer = j.optString("developer", ""),
                    artCover = j.optString("artCover", "").ifEmpty { j.optString("artSquare", "") },
                    namespace = j.optString("namespace", ""),
                    catalogItemId = j.optString("catalogItemId", ""),
                )
            }
            null
        }.getOrNull()
    }

    private val seeded = AtomicBoolean(false)

    /**
     * One-time sync of the installed Epic library into the registry. Safe to call repeatedly; only
     * the first call does work. Never throws — a seeding failure must not take down store startup.
     */
    fun seed(ctx: Context) {
        if (!seeded.compareAndSet(false, true)) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // appName -> cached metadata from the games-screen cache (best-effort).
            val meta = HashMap<String, Meta>()
            prefs.getString(CACHE_KEY, null)?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val j = arr.optJSONObject(i) ?: continue
                        val an = j.optString("appName", "")
                        if (an.isEmpty()) continue
                        meta[an] = Meta(j.optString("title", ""), coverOf(j))
                    }
                }
            }

            var added = 0
            var healed = 0
            for ((k, v) in prefs.all) {
                if (!k.startsWith(DIR_PREFIX)) continue
                val appName = k.substring(DIR_PREFIX.length)
                if (appName.isEmpty()) continue
                val dirPath = (v as? String) ?: continue
                if (dirPath.isEmpty()) continue

                val key = "${Store.EPIC}:$appName"

                // A live/queued download already owns this key and writes epic_dir_ before the exe
                // is recorded — never seed a duplicate nor self-heal it out from under an in-flight
                // install. Leave it entirely to the producer. (Guard BEFORE the disk-truth check.)
                if (DownloadRegistry.isActive(key)) continue

                // A download the user paused (kept in EpicDownloadControl's prefs) has its dir but no exe yet: it is not an orphan,
                // and its record must survive for the resume. Shown as a paused row by seedPaused below.
                if (EpicDownloadControl.isPersistedPaused(ctx, appName)) continue

                // Truth = the install must still EXIST ON DISK: dir present AND the recorded launch
                // exe present. Prevents zombie rows after an uninstall that deleted files but left
                // prefs. (epic_dir_ is stored as an absolute path.)
                val installDir = File(dirPath)
                val exe = prefs.getString("epic_exe_$appName", null)
                val installed = installDir.exists() && exe != null && File(exe).exists()

                if (!installed) {
                    // Self-heal: purge the stale native record + drop any durable-library row.
                    EpicInstallState.purge(ctx, appName)
                    DownloadRegistry.removeLibraryEntry(key)
                    healed++
                    continue
                }

                val m = meta[appName] ?: Meta(appName, null)
                val name = m.title.ifEmpty { appName }
                val bytes = prefs.getLong("epic_size_$appName", 0L).let { if (it > 0L) it else 0L }

                DownloadRegistry.upsert(
                    DownloadEntry(
                        store = Store.EPIC,
                        id = appName,
                        name = name,
                        cover = m.cover,
                        state = DownloadState.INSTALLED,
                        pct = 100,
                        installDone = bytes,
                        installTotal = bytes,
                        supportsPause = false,
                        installPath = installDir.absolutePath,
                        // Epic's update check is an on-demand network call (no offline "newer
                        // version" signal the list flags), so leave update-available false at seed.
                        updateAvailable = false,
                    )
                )
                added++
            }
            Log.i(TAG, "Seeded $added installed Epic game(s), self-healed $healed orphan(s)")
            // Downloads paused in an earlier run of the app come back as paused rows with a Resume button.
            EpicDownloadControl.seedPaused(ctx)
        } catch (t: Throwable) {
            seeded.set(false)   // reset so a later call (once prefs are ready) can retry
            Log.w(TAG, "Library seed skipped: ${t.message}")
        }
    }
}
