package com.winlator.star.store

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.star.container.Container
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Per-game Steam save STATUS + persistence layer that backs the Save Manager screen.
 *
 * HYBRID model (per the feature contract):
 *   - Instant, NO-network status is rendered from a persisted per-game record
 *     ([listStatuses] / [statusOf]) plus a cheap local filesystem scan
 *     ([SteamCloudSaveManager.staleness]).
 *   - Pull-to-refresh does a live cloud manifest diff to surface "cloud ahead"
 *     ([refreshFromCloud], BLOCKING — callers thread it).
 *
 * The record sidecar is `Bannerlator/SteamCloudSaves/_status.json` (mirrors
 * [com.winlator.star.core.SaveLocator]'s JSON sidecar style — one flat JSON object keyed by appId).
 * It is written by the four [recordAfter*] hooks that [SteamCloudSaveManager]'s moves call on success.
 *
 * This object never deletes cloud data and never launches network I/O from the instant paths — the
 * only place a CM future is awaited is [refreshFromCloud] and the [observeCloud] baseline capture the
 * download/upload hooks run (already on the move's background thread).
 */
/** One game's cloud-save status for the Save Manager. Top-level so the UI can name it directly. */
enum class SaveState { IN_SYNC, LOCAL_ONLY, LOCAL_AHEAD, CLOUD_AHEAD, NEVER_SYNCED, NO_CLOUD_SAVES, NOT_SET_UP, UNKNOWN }

data class SaveStatus(
    val appId: Int,
    val gameName: String,
    val state: SaveState,
    val lastDownloadAt: Long,    // epoch millis, 0 if never
    val lastUploadAt: Long,      // epoch millis, 0 if never
    val containerLabel: String?, // null if not set up
    val libraryFileCount: Int,
    val cloudFileCount: Int,     // last-known; may be stale until refresh
)

object SaveSyncStore {

    private const val TAG = "BH_SAVE_SYNC"

    /** Blocking timeout for the one JavaSteam CM future we await (manifest fetch). */
    private const val FUTURE_TIMEOUT_SEC = 60L

    /** Sentinel for "no container" persisted in the record. */
    private const val NO_CONTAINER = -1

    /** Serializes read-modify-write of the shared sidecar across the four move threads + refresh. */
    private val lock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Instant list (NO network) for the manager. The game set is the union of:
     *   1. every appId with a persisted sidecar record,
     *   2. every on-disk Library folder under `Bannerlator/SteamCloudSaves/<appId>` that holds files,
     *   3. installed Steam games (`steam_games.is_installed = 1`) that are already known to have cloud
     *      saves (last-known `cloudFileCount > 0` from their record).
     * Scope = has-cloud-saves (last-known) OR has-local-Library — kept short + relevant.
     * Sorted needs-attention-first (NOT_SET_UP / CLOUD_AHEAD / LOCAL_AHEAD / NEVER_SYNCED before
     * IN_SYNC / NO_CLOUD_SAVES / UNKNOWN), then by game name.
     */
    fun listStatuses(ctx: Context): List<SaveStatus> {
        val root = loadRoot()

        val candidates = LinkedHashSet<Int>()
        // (1) records
        root.keys().forEach { key -> key.toIntOrNull()?.let { candidates.add(it) } }
        // (2) on-disk Library folders with files
        libraryFolderAppIds().forEach { candidates.add(it) }
        // (3) installed Steam games (only kept below if they carry cloud saves / a Library)
        try {
            SteamRepository.getInstance().getDatabase().getInstalledGames().forEach { candidates.add(it.appId) }
        } catch (t: Throwable) {
            Log.w(TAG, "installed-games enumeration failed: ${t.message}")
        }

        val out = ArrayList<SaveStatus>()
        for (appId in candidates) {
            val status = statusOf(ctx, appId)
            // Scope gate: keep games with a local Library, known cloud saves, OR local (in-container)
            // saves that need attention (LOCAL_ONLY = played-but-never-backed-up; LOCAL_AHEAD =
            // synced-then-changed). The discovery scan already ran in statusOf, so this is free.
            val keep = status.libraryFileCount > 0 || status.cloudFileCount > 0 ||
                status.state == SaveState.LOCAL_ONLY || status.state == SaveState.LOCAL_AHEAD
            if (keep) out.add(status)
        }
        return out.sortedWith(compareBy({ attentionRank(it.state) }, { it.gameName.lowercase() }))
    }

    /** Instant status for one game (record + local staleness scan, no network). */
    fun statusOf(ctx: Context, appId: Int): SaveStatus {
        val rec = getRecord(loadRoot(), appId)
        val game = try { SteamRepository.getInstance().getDatabase().getGame(appId) } catch (t: Throwable) { null }
        val installDir = game?.installDir ?: ""
        val gameName = game?.name?.takeIf { it.isNotBlank() }
            ?: rec?.optString("gameName")?.takeIf { it.isNotBlank() }
            ?: "App $appId"

        // Local freshness (filesystem only) — reuse the move layer's scoped scan.
        val staleness = try {
            SteamCloudSaveManager.staleness(ctx, appId, installDir)
        } catch (t: Throwable) {
            Log.w(TAG, "staleness scan failed for $appId: ${t.message}")
            SteamCloudSaveManager.Staleness(0L, 0L, 0, 0)
        }

        // Live container resolution (falls back to the last-recorded label if the game isn't
        // installed in a container right now).
        val container = if (installDir.isBlank()) null
        else try { SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) } catch (t: Throwable) { null }
        val containerLabel = container?.let { SteamCloudSavePaths.containerLabel(it) }
            ?: rec?.optString("containerLabel")?.takeIf { it.isNotBlank() }

        val cloudCount = rec?.optInt("cloudFileCount", 0) ?: 0
        val lastDownloadAt = rec?.optLong("lastDownloadAt", 0L) ?: 0L
        val lastUploadAt = rec?.optLong("lastUploadAt", 0L) ?: 0L
        val cloudKnown = !(rec?.optString("cloudManifestHash").isNullOrEmpty())

        val state = computeState(
            hasContainer = container != null,
            libraryFileCount = staleness.libraryFileCount,
            containerFileCount = staleness.containerFileCount,
            newestLocalMtime = maxOf(staleness.libraryNewestMtime, staleness.containerNewestMtime),
            lastUploadAt = lastUploadAt,
            lastDownloadAt = lastDownloadAt,
            cloudFileCount = cloudCount,
            cloudKnown = cloudKnown,
            refreshCloudAhead = false,
        )

        return SaveStatus(
            appId = appId,
            gameName = gameName,
            state = state,
            lastDownloadAt = lastDownloadAt,
            lastUploadAt = lastUploadAt,
            containerLabel = containerLabel,
            libraryFileCount = staleness.libraryFileCount,
            cloudFileCount = cloudCount,
        )
    }

    /**
     * Live: fetch the cloud manifest, diff its hash vs the stored baseline, UPDATE the record and
     * return the fresh status. BLOCKING (awaits one CM future) — callers run it off the main thread.
     *
     * CLOUD_AHEAD = the freshly fetched manifest hash differs from the stored baseline
     * ([recordAfterDownload]/[recordAfterUpload] set the baseline to the cloud state our Library is in
     * sync with). When that happens we bump the observed `cloudFileCount` but DELIBERATELY keep the
     * stored `cloudManifestHash` baseline, so the game stays flagged CLOUD_AHEAD on every refresh until
     * the user reconciles by downloading (which resets the baseline). If the fetch fails we fall back
     * to the instant status.
     */
    fun refreshFromCloud(ctx: Context, appId: Int): SaveStatus {
        val observed = observeCloud(appId)
        if (observed == null) {
            Log.w(TAG, "refreshFromCloud: manifest fetch unavailable for $appId; returning instant status")
            return statusOf(ctx, appId)
        }
        val (freshCount, freshHash) = observed

        val storedHash: String
        synchronized(lock) {
            val root = loadRoot()
            val rec = getRecord(root, appId) ?: newRecord(appId)
            storedHash = rec.optString("cloudManifestHash", "")
            val cloudAhead = storedHash.isNotEmpty() && freshHash != storedHash

            rec.put("appId", appId)
            rec.put("cloudFileCount", freshCount)
            // First sighting (no baseline yet) OR cloud matches our baseline → adopt the fresh hash.
            // Cloud diverged from our baseline → keep the baseline so it stays flagged until download.
            if (!cloudAhead) rec.put("cloudManifestHash", freshHash)
            refreshCommonFields(ctx, appId, rec)
            root.put(appId.toString(), rec)
            saveRoot(root)
        }

        // Recompute against the just-written record, forcing the cloud-ahead verdict when detected.
        val base = statusOf(ctx, appId)
        val cloudAhead = storedHash.isNotEmpty() && freshHash != storedHash
        return if (cloudAhead) base.copy(state = SaveState.CLOUD_AHEAD, cloudFileCount = freshCount)
        else base.copy(cloudFileCount = freshCount)
    }

    // ── Record hooks (called by SteamCloudSaveManager after each successful move) ──

    /**
     * Cloud → Library succeeded. Two-phase so the row updates instantly:
     *  1. FAST/LOCAL (synchronous): stamp lastDownloadAt + refresh the local common fields and PERSIST
     *     now — so lastDownloadAt is on disk before the move's onDone fires the UI's per-row statusOf.
     *  2. SLOW/NETWORK (off-thread): re-baseline the cloud manifest as a second persist. Never delays
     *     onDone or the immediate refresh.
     */
    fun recordAfterDownload(ctx: Context, appId: Int) {
        writeHook(ctx, appId) { rec -> rec.put("lastDownloadAt", System.currentTimeMillis()) }
        rebaselineCloudAsync(ctx, appId)
    }

    /** Library → Cloud succeeded. Same two-phase split as [recordAfterDownload]: stamp lastUploadAt
     *  locally + persist synchronously first, then re-baseline the cloud manifest off-thread. */
    fun recordAfterUpload(ctx: Context, appId: Int) {
        writeHook(ctx, appId) { rec -> rec.put("lastUploadAt", System.currentTimeMillis()) }
        rebaselineCloudAsync(ctx, appId)
    }

    /**
     * Phase 2 of the download/upload hooks: fetch the cloud manifest and persist the cloud baseline
     * (cloudFileCount / cloudManifestHash) as a SECOND write. Runs on its own thread so the network
     * round-trip never delays the move's onDone or the UI's post-move status refresh — the local
     * timestamp is already on disk by then. A null/failed fetch leaves the existing baseline intact
     * (we never overwrite a good baseline with "cloud unknown").
     */
    private fun rebaselineCloudAsync(ctx: Context, appId: Int) {
        Thread({
            val cloud = observeCloud(appId) ?: return@Thread   // network fetch OUTSIDE the persistence lock
            writeHook(ctx, appId) { rec ->
                rec.put("cloudFileCount", cloud.first)
                rec.put("cloudManifestHash", cloud.second)
            }
        }, "save-cloud-rebaseline-$appId").start()
    }

    /** Container → Library succeeded: Library now holds fresh saves not yet uploaded (→ LOCAL_AHEAD).
     *  Only the library snapshot/counts + container fields are refreshed (done by refreshCommonFields);
     *  timestamps + cloud baseline are intentionally left untouched so the row reads LOCAL_AHEAD. */
    fun recordAfterCollect(ctx: Context, appId: Int) = writeHook(ctx, appId) { /* common refresh only */ }

    /** Library → Container succeeded: no Library or cloud change; just refresh container/label + snapshot. */
    fun recordAfterApply(ctx: Context, appId: Int) = writeHook(ctx, appId) { /* common refresh only */ }

    /**
     * Persist that this game's Steam Cloud does NOT retain uploads — i.e. the client commit
     * "succeeds" but Steam keeps nothing (old titles like FlatOut 2). Discovered by
     * [SteamCloudSaveManager]'s post-upload emptiness check and read back by its [hasCloudSupport] /
     * upload short-circuit so we never re-attempt and never again falsely claim "Uploaded N". The
     * local Library backup is unaffected. Not auto-cleared here.
     */
    fun markNoSteamCloud(ctx: Context, appId: Int) {
        writeHook(ctx, appId) { rec -> rec.put("noSteamCloud", true) }
    }

    /** True if [appId] has been marked as not retaining Steam Cloud uploads (see [markNoSteamCloud]). */
    fun isMarkedNoSteamCloud(appId: Int): Boolean {
        val rec = getRecord(loadRoot(), appId) ?: return false
        return rec.optBoolean("noSteamCloud", false)
    }

    // ── State machine ─────────────────────────────────────────────────────────

    private fun computeState(
        hasContainer: Boolean,
        libraryFileCount: Int,
        containerFileCount: Int,
        newestLocalMtime: Long,
        lastUploadAt: Long,
        lastDownloadAt: Long,
        cloudFileCount: Int,
        cloudKnown: Boolean,
        refreshCloudAhead: Boolean,
    ): SaveState {
        // Cloud strictly ahead — only ever true on the refresh path.
        if (refreshCloudAhead) return SaveState.CLOUD_AHEAD

        val hasLibrary = libraryFileCount > 0
        // "In sync as of" the last time Library and Cloud were reconciled (download OR upload).
        val lastSync = maxOf(lastUploadAt, lastDownloadAt)

        // Cloud has saves but nothing local yet (never downloaded).
        if (cloudFileCount > 0 && !hasLibrary && lastDownloadAt == 0L) return SaveState.NEVER_SYNCED

        // Local saves exist but were NEVER backed up (no prior sync, cloud empty/unknown). This is a
        // played-but-never-synced game — surfaced so the manager can nag you to back it up.
        if ((hasLibrary || containerFileCount > 0) && lastSync == 0L && cloudFileCount == 0) {
            return SaveState.LOCAL_ONLY
        }

        // Local side (Library or container) changed since the last reconcile → needs uploading.
        if ((hasLibrary || containerFileCount > 0) && newestLocalMtime > lastSync) return SaveState.LOCAL_AHEAD

        // Nothing local is ahead — if there's no container to Apply into, that's the actionable gap.
        if (!hasContainer) return SaveState.NOT_SET_UP

        // We've observed the cloud and it holds nothing.
        if (cloudKnown && cloudFileCount == 0) return SaveState.NO_CLOUD_SAVES

        // Reconciled: cloud observed and/or a Library present, nothing pending.
        if (cloudKnown || hasLibrary) return SaveState.IN_SYNC

        return SaveState.UNKNOWN
    }

    /** Needs-attention-first ordering rank for [listStatuses]. */
    private fun attentionRank(state: SaveState): Int = when (state) {
        SaveState.NOT_SET_UP    -> 0
        SaveState.CLOUD_AHEAD   -> 1
        SaveState.LOCAL_ONLY    -> 2   // never backed up — most worth surfacing
        SaveState.LOCAL_AHEAD   -> 3
        SaveState.NEVER_SYNCED  -> 4
        SaveState.IN_SYNC       -> 5
        SaveState.NO_CLOUD_SAVES -> 6
        SaveState.UNKNOWN       -> 7
    }

    // ── Record persistence ────────────────────────────────────────────────────

    private fun statusJsonFile(): File =
        File(Environment.getExternalStorageDirectory(), "Bannerlator/SteamCloudSaves/_status.json")

    /** Root of the Library tree (parent of the per-appId folders + the sidecar). */
    private fun cloudSavesRoot(): File =
        File(Environment.getExternalStorageDirectory(), "Bannerlator/SteamCloudSaves")

    private fun loadRoot(): JSONObject {
        val f = statusJsonFile()
        if (!f.isFile) return JSONObject()
        return try { JSONObject(f.readText()) } catch (e: Exception) {
            Log.w(TAG, "corrupt _status.json, starting fresh: ${e.message}"); JSONObject()
        }
    }

    private fun saveRoot(root: JSONObject) {
        val f = statusJsonFile()
        try {
            f.parentFile?.mkdirs()
            f.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "failed to persist _status.json: ${e.message}")
        }
    }

    private fun getRecord(root: JSONObject, appId: Int): JSONObject? = root.optJSONObject(appId.toString())

    private fun newRecord(appId: Int): JSONObject = JSONObject().apply {
        put("appId", appId)
        put("gameName", "")
        put("lastDownloadAt", 0L)
        put("lastUploadAt", 0L)
        put("libraryFileCount", 0)
        put("librarySnapshotHash", "")
        put("cloudFileCount", 0)
        put("cloudManifestHash", "")
        put("containerId", NO_CONTAINER)
        put("containerLabel", "")
        put("noSteamCloud", false)
    }

    /** Load → get-or-create record → apply the hook body → refresh common fields → persist. */
    private inline fun writeHook(ctx: Context, appId: Int, body: (JSONObject) -> Unit) {
        synchronized(lock) {
            val root = loadRoot()
            val rec = getRecord(root, appId) ?: newRecord(appId)
            body(rec)
            refreshCommonFields(ctx, appId, rec)
            root.put(appId.toString(), rec)
            saveRoot(root)
        }
    }

    /**
     * Recompute the fields that every write should keep current: appId, gameName, the live container
     * id/label, and the Library snapshot (file count + cheap (relPath+size+mtime) hash). Never clears
     * a previously-known gameName/container label if we can't resolve one now.
     */
    private fun refreshCommonFields(ctx: Context, appId: Int, rec: JSONObject) {
        rec.put("appId", appId)

        val game = try { SteamRepository.getInstance().getDatabase().getGame(appId) } catch (t: Throwable) { null }
        val name = game?.name?.takeIf { it.isNotBlank() }
        if (name != null) rec.put("gameName", name)
        else if (rec.optString("gameName").isEmpty()) rec.put("gameName", "App $appId")

        val installDir = game?.installDir ?: ""
        val container: Container? = if (installDir.isBlank()) null
        else try { SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) } catch (t: Throwable) { null }
        if (container != null) {
            rec.put("containerId", container.id)
            rec.put("containerLabel", SteamCloudSavePaths.containerLabel(container))
        }

        val (count, hash) = librarySnapshot(SteamCloudSavePaths.libraryDir(ctx, appId))
        rec.put("libraryFileCount", count)
        rec.put("librarySnapshotHash", hash)
    }

    // ── Hashing / scanning helpers ────────────────────────────────────────────

    /** appIds of on-disk Library folders (`.../SteamCloudSaves/<appId>`) that contain at least one file. */
    private fun libraryFolderAppIds(): List<Int> {
        val root = cloudSavesRoot()
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<Int>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            val id = d.name.toIntOrNull() ?: continue
            val hasFile = d.walkTopDown().any { it.isFile }
            if (hasFile) out.add(id)
        }
        return out
    }

    /** (fileCount, hash) over the Library folder. Hash = sha1 of sorted "relPath|size|mtime" lines. */
    private fun librarySnapshot(root: File): Pair<Int, String> {
        if (!root.isDirectory) return 0 to ""
        val base = root.absolutePath.trimEnd('/')
        val lines = ArrayList<String>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isNotEmpty()) lines.add("$rel|${f.length()}|${f.lastModified()}")
        }
        if (lines.isEmpty()) return 0 to ""
        lines.sort()
        return lines.size to sha1Hex(lines.joinToString("\n"))
    }

    /**
     * Fetch the cloud manifest for [appId] and reduce it to (fileCount, manifestHash). manifestHash =
     * sha1 of sorted "remotePath|shaHex" lines over the cloud file set. Returns null if there's no live
     * SteamCloud handle (not signed in) or the CM future fails/times out — callers treat null as
     * "cloud unknown" and never overwrite a good baseline with it. BLOCKING; only called from
     * background threads ([refreshFromCloud] + the download/upload hooks, which already run off-main).
     */
    private fun observeCloud(appId: Int): Pair<Int, String>? {
        val cloud = try { SteamRepository.getInstance().getSteamCloud() } catch (t: Throwable) { null } ?: return null
        return try {
            val list: AppFileChangeList = cloud.getAppFileListChange(appId).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
            val lines = ArrayList<String>(list.files.size)
            for (f in list.files) {
                lines.add("${remotePathOf(f, list)}|${toHex(f.shaFile)}")
            }
            lines.sort()
            list.files.size to (if (lines.isEmpty()) sha1Hex("") else sha1Hex(lines.joinToString("\n")))
        } catch (e: Exception) {
            Log.w(TAG, "observeCloud failed for $appId: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Remote (Steam-side) path of a manifest entry = its prefix + filename. Mirrors
     *  [SteamCloudSaveManager]'s private remotePathOf so the hash key is stable across observations. */
    private fun remotePathOf(f: AppFileInfo, list: AppFileChangeList): String {
        val prefixes = list.pathPrefixes
        val idx = f.pathPrefixIndex
        val prefix = if (idx in prefixes.indices) prefixes[idx] else ""
        return when {
            prefix.isEmpty() -> f.filename
            f.filename.isEmpty() -> prefix
            else -> prefix.trimEnd('/', '\\') + "/" + f.filename.trimStart('/', '\\')
        }
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return toHex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
