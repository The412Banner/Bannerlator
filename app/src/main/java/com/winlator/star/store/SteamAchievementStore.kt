package com.winlator.star.store

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.types.SteamID
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Facade for per-game Steam achievements.
 *
 * READ path (always available):
 *   [fetch]  — online: SteamUserStats.getUserStats → getExpandedAchievements (the fork already walks
 *              schemaKeyValues + merges the earned achievementBlocks for us), upsert into the
 *              steam_achievements table, cache both icons to disk, return the full list.
 *   [cached] — offline: read the table only, filling localIconPath from the on-disk cache.
 *   [lookup] — one cached achievement (the in-game unlock pill).
 *
 * WRITE path (SAFETY-GATED, default OFF — see [SteamPrefs.isAchievementSyncBackEnabled]):
 *   [queueUnlock]          — record a locally-earned unlock to a pending file. NO network.
 *   [flushPendingSyncBack] — push queued unlocks to the REAL profile via storeUserStats, but ONLY
 *                            when the user has explicitly enabled sync-back.
 *
 * EVERY public entry point is non-throwing at the boundary — these run on game launch/exit/detail-open
 * where a crash is unacceptable. [fetch]/[flushPendingSyncBack] BLOCK (CM round-trips) — call them off
 * the main thread AND off the CM pump thread (ensureLoggedIn blocks the caller while the pump runs).
 */
object SteamAchievementStore {

    private const val TAG = "BH_STEAM_ACHV"

    /** Steam community CDN base for achievement icons (both color + gray). */
    private const val ICON_CDN_BASE = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps"

    /** Bounded login wait before an online fetch (matches the depot-download ensureLoggedIn bound). */
    private const val LOGIN_TIMEOUT_MS = 8_000L

    /** Bound on the getUserStats / storeUserStats CM replies so a stalled job can't hang the caller. */
    private const val JOB_TIMEOUT_SEC = 20L

    /** Icon HTTP connect/read timeout — best-effort; a miss just leaves localIconPath null. */
    private const val ICON_HTTP_TIMEOUT_MS = 15_000

    /** Pending locally-earned unlocks, one `appId:apiName` per line. */
    private const val PENDING_FILE = "pending_achievement_sync.txt"

    /** Serializes reads/writes of the pending file (game-exit may queue from several threads). */
    private val pendingLock = Any()

    // ── READ ────────────────────────────────────────────────────────────────────

    /**
     * Online fetch. Blocks (ensureLoggedIn + a bounded getUserStats round-trip), upserts the
     * steam_achievements table, caches both icons per achievement, and returns the full list with
     * localIconPath/localIconGrayPath filled. Returns emptyList() on not-logged-in / no achievements /
     * ANY failure — never throws.
     */
    @JvmStatic
    fun fetch(ctx: Context, appId: Int): List<SteamAchievement> {
        return try {
            val repo = SteamRepository.getInstance()
            val stats = repo.steamUserStats ?: run {
                Log.i(TAG, "fetch($appId): SteamUserStats handler not bound (not connected)")
                return emptyList()
            }
            if (!repo.ensureLoggedIn(LOGIN_TIMEOUT_MS)) {
                Log.i(TAG, "fetch($appId): not logged in")
                return emptyList()
            }
            val steamId64 = repo.steamId64
            if (steamId64 == 0L) return emptyList()

            val cb = stats.getUserStats(appId, SteamID(steamId64))
                .toFuture().get(JOB_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (cb == null) return emptyList()
            if (cb.result != EResult.OK) {
                Log.i(TAG, "fetch($appId): getUserStats result=${cb.result}")
                // Not a hard failure for every app, but with no expanded list there's nothing to store.
            }

            // getExpandedAchievements() (english) already merges the schema (name/display/desc/icon/
            // icon_gray/hidden) with the earned achievementBlocks (isUnlocked/unlockTimestamp).
            // Explicit no-arg call (the method is overloaded: getExpandedAchievements(String)) — English.
            val expanded = try { cb.getExpandedAchievements() } catch (e: Exception) {
                Log.w(TAG, "fetch($appId): getExpandedAchievements failed", e); emptyList()
            }
            if (expanded.isEmpty()) return emptyList()

            val db = repo.database
            val rows = ArrayList<SteamDatabase.AchievementRow>(expanded.size)
            for (a in expanded) {
                val apiName = a.name ?: continue
                if (apiName.isEmpty()) continue
                rows.add(
                    SteamDatabase.AchievementRow(
                        appId,
                        apiName,
                        a.displayName ?: "",
                        a.description ?: "",
                        a.hidden,
                        a.icon ?: "",
                        a.iconGray ?: "",
                        a.isUnlocked,
                        a.unlockTimestamp.toLong().coerceAtLeast(0L),
                    )
                )
            }
            if (rows.isEmpty()) return emptyList()

            db.upsertAchievements(appId, rows)

            // Cache both icons per achievement (best-effort; skips ones already on disk), then build
            // the UI model with the resolved on-disk paths.
            val iconDir = iconDir(ctx, appId)
            iconDir.mkdirs()
            rows.map { r ->
                val colorPath = cacheIcon(iconDir, appId, r.icon)
                val grayPath = cacheIcon(iconDir, appId, r.iconGray)
                toModel(r, colorPath, grayPath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch($appId) failed", e)
            emptyList()
        }
    }

    /** Offline read from the steam_achievements table; localIconPath filled iff the file exists. */
    @JvmStatic
    fun cached(ctx: Context, appId: Int): List<SteamAchievement> {
        return try {
            val db = SteamRepository.getInstance().database
            val iconDir = iconDir(ctx, appId)
            db.getAchievements(appId).map { r ->
                toModel(r, existingIconPath(iconDir, r.icon), existingIconPath(iconDir, r.iconGray))
            }
        } catch (e: Exception) {
            Log.w(TAG, "cached($appId) failed", e)
            emptyList()
        }
    }

    /** One achievement from the cache (for the in-game unlock pill), or null. Never throws. */
    @JvmStatic
    fun lookup(ctx: Context, appId: Int, apiName: String): SteamAchievement? {
        return try {
            val row = SteamRepository.getInstance().database.getAchievement(appId, apiName) ?: return null
            val iconDir = iconDir(ctx, appId)
            toModel(row, existingIconPath(iconDir, row.icon), existingIconPath(iconDir, row.iconGray))
        } catch (e: Exception) {
            Log.w(TAG, "lookup($appId, $apiName) failed", e)
            null
        }
    }

    /** Percentage (0-100, rounded) of [list] that is unlocked. 0 for an empty list. */
    @JvmStatic
    fun percentUnlocked(list: List<SteamAchievement>): Int {
        if (list.isEmpty()) return 0
        val unlocked = list.count { it.unlocked }
        return Math.round(100.0 * unlocked / list.size).toInt()
    }

    // ── WRITE (safety-gated) ──────────────────────────────────────────────────────

    /**
     * Append a locally-earned unlock to the per-appId pending queue. NO network — this is safe to
     * call from a Goldberg/emulation-earned unlock at any time. Idempotent (deduped by appId:apiName).
     */
    @JvmStatic
    fun queueUnlock(ctx: Context, appId: Int, apiName: String) {
        if (apiName.isBlank()) return
        try {
            synchronized(pendingLock) {
                val line = "$appId:$apiName"
                val file = pendingFile(ctx)
                val existing = if (file.exists())
                    file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                else mutableSetOf()
                if (existing.add(line)) {
                    file.appendText(line + "\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queueUnlock($appId, $apiName) failed", e)
        }
    }

    /**
     * Push queued unlocks to the REAL Steam profile via storeUserStats — ONLY when the user has
     * explicitly enabled sync-back (default OFF). Best-effort, bounded, never throws. Synced lines are
     * removed; anything not pushed (still disabled, offline, per-app failure) stays queued for later.
     *
     * ⚠ BIT-MATH — UNPROVEN AGAINST A LIVE PROFILE. The standard SteamKit form is implemented here:
     * for each queued apiName we resolve its (statId, bitIndex) from schemaKeyValues (stats/<id>/bits/
     * <bit>/name == apiName), OR the bit into the stat's current value (from the fresh getStats()), and
     * storeUserStats the changed stats with the fresh crcStats. Because the gate is default-OFF, this
     * path has NEVER been exercised against a real account — treat the bit-math as unverified until a
     * live-account test with sync-back enabled confirms it. It is fully guarded and cannot crash.
     */
    @JvmStatic
    fun flushPendingSyncBack(ctx: Context) {
        try {
            // THE SAFETY GATE. Default OFF → return immediately; the queue is untouched, no network,
            // no write to the real profile. Test builds cannot mutate a live account without opt-in.
            if (!SteamPrefs.isAchievementSyncBackEnabled(ctx)) return

            val file = pendingFile(ctx)
            if (!file.exists()) return

            val lines = synchronized(pendingLock) {
                if (!file.exists()) return
                file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (lines.isEmpty()) return

            val repo = SteamRepository.getInstance()
            val stats = repo.steamUserStats ?: return
            if (!repo.ensureLoggedIn(LOGIN_TIMEOUT_MS)) return
            val steamId64 = repo.steamId64
            if (steamId64 == 0L) return
            val self = SteamID(steamId64)

            // Group queued apiNames by appId (preserve encountered lines for later removal on success).
            val byApp = LinkedHashMap<Int, MutableSet<String>>()
            for (line in lines) {
                val i = line.indexOf(':')
                if (i <= 0 || i >= line.length - 1) continue
                val appId = line.substring(0, i).toIntOrNull() ?: continue
                val apiName = line.substring(i + 1)
                byApp.getOrPut(appId) { LinkedHashSet() }.add(apiName)
            }

            val syncedLines = HashSet<String>()
            for ((appId, apiNames) in byApp) {
                try {
                    val cb = stats.getUserStats(appId, self)
                        .toFuture().get(JOB_TIMEOUT_SEC, TimeUnit.SECONDS) ?: continue

                    // apiName -> (statId, bitIndex) from schemaKeyValues.
                    val bitOf = HashMap<String, Pair<Int, Int>>()
                    val statsNode = cb.schemaKeyValues?.get("stats")
                    val statChildren = statsNode?.children
                    if (statChildren != null) {
                        for (stat in statChildren) {
                            val statId = stat.name?.toIntOrNull() ?: continue
                            val bits = stat.get("bits")
                            val bitChildren = bits?.children ?: continue
                            for (bit in bitChildren) {
                                val bitIndex = bit.name?.toIntOrNull() ?: continue
                                val name = bit.get("name")?.value ?: continue
                                if (name.isNotEmpty()) bitOf[name] = statId to bitIndex
                            }
                        }
                    }

                    // Current stat values (statId -> value).
                    val current = HashMap<Int, Int>()
                    for (s in cb.stats) current[s.statId] = s.statValue

                    // OR each queued achievement's bit into its stat's value.
                    val changed = HashMap<Int, Int>()
                    var matched = 0
                    for (apiName in apiNames) {
                        val loc = bitOf[apiName] ?: continue
                        val (statId, bitIndex) = loc
                        if (bitIndex < 0 || bitIndex > 31) continue
                        val base = changed[statId] ?: current[statId] ?: 0
                        changed[statId] = base or (1 shl bitIndex)
                        matched++
                    }

                    if (matched == 0 || changed.isEmpty()) {
                        // Nothing resolvable for this app (schema didn't map the names). Leave queued.
                        Log.i(TAG, "syncBack($appId): no queued achievements resolved in schema — leaving queued")
                        continue
                    }

                    val statList = changed.map { (id, v) -> Stats(id, v) }
                    val stored = stats.storeUserStats(appId, statList, self, self, cb.crcStats, false)
                        .toFuture().get(JOB_TIMEOUT_SEC, TimeUnit.SECONDS)

                    if (stored != null && stored.result == EResult.OK) {
                        // Success — mark every LINE for these apiNames as synced (only the ones we
                        // actually resolved+pushed; unresolved names stay queued).
                        for (apiName in apiNames) {
                            if (bitOf.containsKey(apiName)) syncedLines.add("$appId:$apiName")
                        }
                        Log.i(TAG, "syncBack($appId): stored $matched achievement(s)")
                    } else {
                        Log.i(TAG, "syncBack($appId): storeUserStats result=${stored?.result} — leaving queued")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "syncBack($appId) failed — leaving queued", e)
                }
            }

            // Rewrite the pending file with only the lines we did NOT sync (never lose a queued unlock).
            if (syncedLines.isNotEmpty()) {
                synchronized(pendingLock) {
                    val remaining = if (file.exists())
                        file.readLines().map { it.trim() }.filter { it.isNotEmpty() && it !in syncedLines }
                    else emptyList()
                    if (remaining.isEmpty()) file.delete()
                    else file.writeText(remaining.joinToString("\n") + "\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "flushPendingSyncBack failed", e)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private fun toModel(
        r: SteamDatabase.AchievementRow, colorPath: String?, grayPath: String?,
    ): SteamAchievement = SteamAchievement(
        apiName = r.apiName,
        displayName = r.displayName,
        description = r.description,
        hidden = r.hidden,
        iconUrl = iconUrl(r.appId, r.icon),
        iconGrayUrl = iconUrl(r.appId, r.iconGray),
        localIconPath = colorPath,
        localIconGrayPath = grayPath,
        unlocked = r.unlocked,
        unlockTimeSec = r.unlockTime,
    )

    private fun iconDir(ctx: Context, appId: Int): File =
        File(ctx.filesDir, "steam_achievements/$appId")

    /** Full CDN URL for an icon filename, or "" if the filename is blank. */
    private fun iconUrl(appId: Int, filename: String?): String {
        if (filename.isNullOrBlank()) return ""
        return "$ICON_CDN_BASE/$appId/$filename"
    }

    /** On-disk path for [filename] iff the cached file already exists (non-empty), else null. */
    private fun existingIconPath(iconDir: File, filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        val f = File(iconDir, filename)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    /**
     * Download [filename] into [iconDir] once (skips if already on disk). Best-effort — a failure
     * returns null (the UI falls back to the CDN url). Writes to a temp file then renames so a
     * partial download never leaves a truncated cached file.
     */
    private fun cacheIcon(iconDir: File, appId: Int, filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        val dest = File(iconDir, filename)
        if (dest.exists() && dest.length() > 0) return dest.absolutePath
        val url = iconUrl(appId, filename)
        if (url.isEmpty()) return null
        var conn: HttpURLConnection? = null
        val tmp = File(iconDir, "$filename.tmp")
        return try {
            dest.parentFile?.mkdirs()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = ICON_HTTP_TIMEOUT_MS
            conn.readTimeout = ICON_HTTP_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.i(TAG, "cacheIcon: HTTP $code for $filename")
                return null
            }
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (tmp.length() <= 0) { tmp.delete(); return null }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) dest.absolutePath
            else { tmp.delete(); null }
        } catch (e: Exception) {
            Log.i(TAG, "cacheIcon failed for $filename: ${e.javaClass.simpleName}")
            try { tmp.delete() } catch (_: Exception) {}
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun pendingFile(ctx: Context): File = File(ctx.filesDir, PENDING_FILE)
}
