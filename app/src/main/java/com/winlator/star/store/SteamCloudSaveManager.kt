package com.winlator.star.store

import android.content.Context
import android.os.Build
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.SaveLocator
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Steam Cloud (UFS) per-game save up/download manager.
 *
 * Mirrors the shape of [GogCloudSaveManager]: a stateless helper with two directional static-style
 * entry points ([downloadSaves] cloud -> local, [uploadSaves] local -> cloud) plus a [Callback].
 * Each op runs on its own background thread and reports progress via the callback.
 *
 * Uses JavaSteam's [SteamCloud] handler (obtained from [SteamRepository.getSteamCloud]) which speaks
 * the real Steam UFS protocol:
 *   - [SteamCloud.getAppFileListChange] -> the remote file manifest for an app
 *   - [SteamCloud.clientFileDownload]   -> a signed CDN URL to GET one cloud file
 *   - [SteamCloud.beginAppUploadBatch] / [SteamCloud.beginFileUpload] /
 *     [SteamCloud.commitFileUpload] / [SteamCloud.completeAppUploadBatch] -> the upload handshake
 *
 * SAFETY — cloud saves can never be deleted by this class. See [uploadSaves]: `filesToDelete` is
 * hard-wired to an empty list on every code path, and an empty local folder is refused before any
 * batch is opened. There is no code path that computes or sends a deletion to the cloud.
 */
object SteamCloudSaveManager {

    private const val TAG = "BH_STEAM_CLOUD"

    /** Blocking timeout for each JavaSteam CM future (list / begin / commit / complete). */
    private const val FUTURE_TIMEOUT_SEC = 60L

    /** HTTP connect/read timeout for CDN block GET/PUT. */
    private const val HTTP_TIMEOUT_MS = 60_000

    /** Length in bytes of a SHA-1 digest — Steam UFS's per-file [AppFileInfo.getShaFile] and our
     *  local [sha1] both produce this. A cloud entry whose sha isn't exactly this long is treated as
     *  "no usable SHA" and its path is always re-uploaded (never skipped). */
    private const val SHA1_LEN = 20

    /** How many files transfer concurrently in [downloadSaves]/[uploadSaves]. Each transfer keeps
     *  its OWN independent [HttpURLConnection] (separate signed CDN URL per file) and its own
     *  jobid-keyed CM future, so per-file parallelism is safe — this is NOT single-connection HTTP/2
     *  multiplexing (the imagefs-truncation footgun was range streams of ONE file over one conn).
     *  Bounded low so we never hammer the CDN or the CM job dispatcher; tune here if needed. */
    private const val TRANSFER_CONCURRENCY = 4

    /** Honest message shown when a game has no Steam Cloud store (or an upload didn't persist).
     *  The saves are never lost — they stay in the local Library — we just don't lie about the cloud. */
    private const val NO_CLOUD_MESSAGE =
        "This game doesn't support Steam Cloud — your saves are backed up locally in the Library."

    /** Honest message when a game ACKS the upload commit but Steam retains nothing (an empty manifest
     *  right after a committed N>0 upload — old titles like FlatOut 2). Saves are safe in the Library. */
    private const val NO_RETENTION_MESSAGE =
        "This game doesn't keep Steam Cloud saves — your saves are backed up locally in the Library."

    /** Per-app cloud-support cache (PICS `ufs/savefiles` is stable per app). Only DEFINITIVE
     *  true/false is cached; an "unknown" (null) is never stored so it can be retried later. */
    private val cloudSupportCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    interface Callback {
        fun onStatus(message: String)
        fun onDone(summary: String)
        fun onError(message: String)
    }

    // ── Cloud -> local ────────────────────────────────────────────────────────
    // Only ever writes to the local filesystem. It reads the remote manifest and GETs files.
    // It is STRUCTURALLY incapable of modifying the cloud: no upload/commit/delete calls are made.

    /** Download every cloud save file for [appId] into [localFolder], preserving the cloud path
     *  layout. Overwrites local copies; never touches the cloud. */
    fun downloadSaves(ctx: Context, appId: Int, localFolder: File, cb: Callback) {
        Thread({
            try {
                val steamCloud = requireCloud() ?: run { cb.onError("Not signed in to Steam"); return@Thread }

                cb.onStatus("Fetching cloud file list…")
                val fileList: AppFileChangeList =
                    steamCloud.getAppFileListChange(appId).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

                val files = fileList.files
                if (files.isEmpty()) { cb.onDone("No cloud saves found for this game"); return@Thread }

                if (!localFolder.exists()) localFolder.mkdirs()

                // Resolve the safe work set first (unsafe cloud paths are skipped, counted below),
                // then GET the files CONCURRENTLY via a bounded pool. Each task runs the existing
                // per-file downloadOne (its own clientFileDownload + its own HttpURLConnection); no
                // shared connection, no HTTP/2 multiplexing.
                var skipped = 0
                val work = ArrayList<Triple<String, String, File>>() // (displayName, remotePath, dest)
                for (f in files) {
                    val remotePath = remotePathOf(f, fileList)
                    val relLocal = sanitizeRelative(remotePath)
                    if (relLocal == null) {
                        Log.w(TAG, "Skipping unsafe cloud path: $remotePath")
                        skipped++
                        continue
                    }
                    work.add(Triple(f.filename, remotePath, File(localFolder, relLocal)))
                }

                val downloaded = AtomicInteger(0)
                val failures = ConcurrentLinkedQueue<String>()   // filenames that failed/threw
                runConcurrently(work, "steam-cloud-dl-$appId") { (name, remotePath, dest) ->
                    try {
                        cb.onStatus("Downloading: $name")
                        if (downloadOne(steamCloud, appId, remotePath, dest)) {
                            downloaded.incrementAndGet()
                        } else {
                            failures.add(name)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Download task failed for $name", e)
                        failures.add(name)
                    }
                }

                if (failures.isNotEmpty()) {
                    val first = failures.peek()
                    val more = if (failures.size > 1) " (+${failures.size - 1} more)" else ""
                    cb.onError("Download failed for: $first$more")
                    return@Thread
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Downloaded ${downloaded.get()} file${plural(downloaded.get())}$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "downloadSaves failed", e)
                cb.onError("Download error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-download-$appId").start()
    }

    // ── Local -> cloud ────────────────────────────────────────────────────────
    // STRICTLY ADDITIVE. Enumerates local files and uploads/overwrites them. `filesToDelete` is
    // ALWAYS an empty list, so the cloud can only gain/refresh files — never lose them. An empty
    // local folder is refused up-front, so we never even open a batch for a wipe.

    /** Upload files under [localFolder] to [appId]'s Steam Cloud — INCREMENTALLY and additively.
     *  Only NEW or CHANGED files are sent: the cloud manifest is fetched first and each local file's
     *  SHA-1 is diffed against the cloud file at the same remote path. A file is skipped iff it
     *  already exists in the cloud with a byte-identical SHA-1; otherwise it is uploaded. Never
     *  deletes anything from the cloud (`filesToDelete` is always empty). Refuses (no-op) if the
     *  local folder has no files. If a cloud SHA is unavailable (manifest fetch failed, or the
     *  entry carries no usable sha), that file is uploaded — correctness over efficiency; we never
     *  skip a file we can't prove is identical. */
    fun uploadSaves(ctx: Context, appId: Int, localFolder: File, cb: Callback) {
        Thread({
            try {
                val steamCloud = requireCloud() ?: run { cb.onError("Not signed in to Steam"); return@Thread }

                cb.onStatus("Scanning local saves…")
                val localFiles = enumerateLocal(localFolder)   // List<Pair<File, cloudRelPath>>

                // ── BELT-AND-SUSPENDERS GUARD (unchanged) ──────────────────────────────
                // If there is nothing local to upload we return immediately and NEVER call
                // beginAppUploadBatch. This makes an empty/absent save folder a pure no-op and
                // removes any chance of an "upload nothing" turning into a cloud wipe.
                if (localFiles.isEmpty()) {
                    cb.onDone("No local save files found — nothing was sent to the cloud")
                    return@Thread
                }

                // ── HONESTY GUARD 0: already proven to not retain cloud uploads? ───────
                // If a prior upload committed files but Steam kept nothing, we marked this game.
                // Short-circuit before opening any batch — no work, honest message, no false success.
                if (SaveSyncStore.isMarkedNoSteamCloud(appId)) {
                    cb.onError(NO_RETENTION_MESSAGE)
                    return@Thread
                }

                // ── HONESTY GUARD 1: does this game even support Steam Cloud? ───────────
                // Old titles declare NO UFS save-file patterns → no cloud store. The upload handshake
                // would "succeed" but nothing persists, so we'd FALSELY report "Uploaded N". Read the
                // app's declared UFS config from PICS; if it says "no cloud", stop here and tell the
                // truth. NULL = couldn't determine → proceed + post-upload emptiness check catches it.
                // Reported via onError (not onDone) so the SaveSyncStore.recordAfterUpload hook does
                // NOT fire — we must not stamp a lastUploadAt "cloud sync" that never happened.
                val support: Boolean? = hasCloudSupport(ctx, appId)
                if (support == false) {
                    cb.onError(NO_CLOUD_MESSAGE)
                    return@Thread
                }

                // ── INCREMENTAL DIFF (new — still strictly additive) ───────────────────
                // Fetch the cloud manifest and map each cloud file's normalized remote path to its
                // SHA-1 (AppFileInfo.shaFile — a 20-byte digest). A failed fetch, or an entry with a
                // missing/short sha, simply leaves that path OUT of the map, so it counts as
                // "changed" and gets uploaded. We never skip a file we can't prove is byte-identical.
                cb.onStatus("Comparing with cloud…")
                val cloudShaByPath: Map<String, ByteArray> = try {
                    val fileList = steamCloud.getAppFileListChange(appId)
                        .get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
                    val map = HashMap<String, ByteArray>()
                    for (f in fileList.files) {
                        val sha = f.shaFile
                        if (sha.size != SHA1_LEN) {
                            Log.w(TAG, "Cloud file has no usable SHA (${sha.size}B), will re-upload: ${f.filename}")
                            continue
                        }
                        val key = sanitizeRelative(remotePathOf(f, fileList)) ?: continue
                        map[key] = sha
                    }
                    map
                } catch (e: Exception) {
                    // Can't diff → fall back to uploading everything (today's behavior). Never a wipe.
                    Log.w(TAG, "Cloud manifest fetch failed; uploading all local files", e)
                    emptyMap()
                }

                // Include a local file iff it is new, changed, or its cloud sha is unavailable.
                val toUpload = ArrayList<Pair<File, String>>()
                for (entry in localFiles) {
                    val (file, cloudPath) = entry
                    val key = sanitizeRelative(cloudPath)
                    val cloudSha = if (key != null) cloudShaByPath[key] else null
                    if (cloudSha != null && sha1(file).contentEquals(cloudSha)) {
                        continue // an identical copy is already in the cloud — skip
                    }
                    toUpload.add(entry)
                }

                val upToDate = localFiles.size - toUpload.size

                // ── NOTHING-TO-UPLOAD GUARD ────────────────────────────────────────────
                // Same belt-and-suspenders as the empty-folder guard: if the diff found no new/
                // changed files we return WITHOUT ever opening a batch.
                if (toUpload.isEmpty()) {
                    cb.onDone("Uploaded 0 changed, $upToDate already up-to-date")
                    return@Thread
                }

                val filesToUpload: List<String> = toUpload.map { it.second }

                // filesToDelete is ALWAYS empty. This is the single source of truth for the
                // "never delete from cloud" guarantee — it is a literal emptyList() and is never
                // populated from local/remote diffs anywhere in this class. The incremental diff
                // only ever SHRINKS the upload set; it never produces a deletion.
                val filesToDelete: List<String> = emptyList()

                cb.onStatus("Opening cloud upload batch…")
                // Signature (JavaSteam 1.8.0):
                //   beginAppUploadBatch(appId, machineName, filesToUpload, filesToDelete, clientId, appBuildId)
                // clientId/appBuildId are best-effort 0L (classic token logon exposes no auth-session
                // clientID, and we don't parse the installed build id). See report notes.
                val batch = steamCloud.beginAppUploadBatch(
                    appId,
                    machineName(),
                    filesToUpload,
                    filesToDelete,   // ← always empty
                    0L,              // clientId (unknown under classic logon)
                    0L,              // appBuildId (not tracked)
                ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

                // Upload the batch's files CONCURRENTLY (bounded pool) — WITHIN the single open
                // batch. Only the per-file uploadOne is parallelized (its own beginFileUpload +
                // block PUTs over its own HttpURLConnection(s) + commitFileUpload, all jobid-keyed);
                // the batch begin/complete calls stay single and sequential around this loop.
                val batchId = batch.batchID
                val uploaded = AtomicInteger(0)
                val allOk = AtomicBoolean(true)
                runConcurrently(toUpload, "steam-cloud-ul-$appId") { (file, cloudPath) ->
                    try {
                        cb.onStatus("Uploading: ${file.name}")
                        if (uploadOne(steamCloud, appId, file, cloudPath, batchId)) {
                            uploaded.incrementAndGet()
                        } else {
                            allOk.set(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Upload task failed for ${file.name}", e)
                        allOk.set(false)
                    }
                }

                // Close the batch with the aggregate result (OK only if every file committed).
                steamCloud.completeAppUploadBatch(
                    appId,
                    batchId,
                    if (allOk.get()) EResult.OK else EResult.Fail,
                ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

                if (allOk.get()) {
                    // ── HONESTY GUARD 2: did the cloud actually KEEP the committed files? ──
                    // We committed N>0 files. Re-fetch the manifest SYNCHRONOUSLY: a COMPLETELY EMPTY
                    // manifest right after a committed upload is the no-retention signature (old games
                    // like FlatOut 2 ack the commit but store nothing). This runs regardless of the
                    // PICS verdict — FlatOut 2 fooled the PICS check by declaring UFS savefiles. It is
                    // safe vs HL2: a retaining game returns a NON-EMPTY manifest, so it never
                    // false-triggers. If the re-fetch itself fails we keep the optimistic result (the
                    // async rebaseline will still correct the pill) and do NOT mark.
                    val emptyAfterUpload = if (uploaded.get() > 0) isCloudManifestEmpty(steamCloud, appId) else null
                    if (emptyAfterUpload == true) {
                        SaveSyncStore.markNoSteamCloud(ctx, appId)   // remember → short-circuit next time
                        cb.onError(NO_RETENTION_MESSAGE)             // onError: no false success, no lastUploadAt stamp
                    } else {
                        cb.onDone("Uploaded ${uploaded.get()} changed, $upToDate already up-to-date")
                    }
                } else {
                    cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadSaves failed", e)
                cb.onError("Upload error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-upload-$appId").start()
    }

    // ── Three-tier moves (Cloud ⇄ Library ⇄ Container) ───────────────────────────
    // Library = the managed local folder [SteamCloudSavePaths.libraryDir]; canonical local copy.
    // These wrap the primitives above / add container-side copies. SAFETY is unchanged: Download and
    // Upload delegate to [downloadSaves]/[uploadSaves] (Upload stays additive, filesToDelete empty,
    // empty-Library refused). Apply/Collect are pure copies — they may overwrite the DESTINATION
    // side only and NEVER delete the source and NEVER touch the cloud.

    /** Cloud → Library. Thin wrapper over [downloadSaves] with the Library dir resolved internally. */
    fun downloadToLibrary(ctx: Context, appId: Int, cb: Callback) {
        downloadSaves(ctx, appId, SteamCloudSavePaths.libraryDir(ctx, appId),
            hooked(cb) { SaveSyncStore.recordAfterDownload(ctx, appId) })
    }

    /** Library → Cloud, strictly additive. Thin wrapper over [uploadSaves] (filesToDelete stays
     *  emptyList(); an empty Library is refused before any batch is opened). */
    fun uploadFromLibrary(ctx: Context, appId: Int, cb: Callback) {
        uploadSaves(ctx, appId, SteamCloudSavePaths.libraryDir(ctx, appId),
            hooked(cb) { SaveSyncStore.recordAfterUpload(ctx, appId) })
    }

    // ── Cloud-support detection (UFS config from PICS product info) ───────────────

    /**
     * Whether [appId] actually supports Steam Cloud, read from the app's DECLARED UFS config in PICS
     * product info (`appinfo → ufs → savefiles`) — the same source GameNative uses. A game with NO
     * usable save-file patterns has no cloud store; uploading to it "succeeds" but persists nothing.
     *
     * Returns:
     *  - `true`  — the app declares ≥1 usable UFS save-file pattern (e.g. Half-Life 2).
     *  - `false` — the app's product info is populated but declares no save files (e.g. FlatOut 2).
     *  - `null`  — couldn't determine (not signed in, PICS query failed/timed out, or metadata-only
     *              product info with no populated KeyValues). Callers should NOT treat null as "no
     *              cloud"; upload falls back to a post-upload persistence check instead.
     *
     * Definitive results are cached per app (the UFS config is stable); `null` is never cached.
     */
    fun hasCloudSupport(ctx: Context, appId: Int): Boolean? {
        // A game we already proved doesn't retain uploads is definitively "no cloud" — this wins over
        // both the cache and PICS (FlatOut 2 declares UFS savefiles yet keeps nothing).
        if (SaveSyncStore.isMarkedNoSteamCloud(appId)) return false

        cloudSupportCache[appId]?.let { return it }

        val steamApps = SteamRepository.getInstance().steamApps ?: return null
        return try {
            val resultSet = steamApps.picsGetProductInfo(PICSRequest(appId))
                .toFuture().get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

            var appKeyValues: KeyValue? = null
            for (callback in resultSet.results) {
                val info = callback.apps[appId]
                if (info != null) { appKeyValues = info.keyValues; break }
            }

            // No populated KeyValues (metadata-only / missing token) → genuinely unknown.
            if (appKeyValues == null || appKeyValues.children.isEmpty()) {
                null
            } else {
                val supported = hasUsableSaveFiles(appKeyValues)
                cloudSupportCache[appId] = supported
                supported
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasCloudSupport: PICS product-info query failed for appId=$appId", e)
            null
        }
    }

    /** True if the app's PICS KeyValues declare at least one usable UFS save-file pattern. Navigates
     *  `ufs/savefiles` (case-insensitive; [KeyValue.get] returns the INVALID sentinel — never null —
     *  when a key is absent, so missing sections yield an empty child list ⇒ false). A pattern counts
     *  as usable if it carries a non-blank `root` or `pattern`. */
    private fun hasUsableSaveFiles(appKeyValues: KeyValue): Boolean {
        val saveFiles = appKeyValues.get("ufs").get("savefiles").children
        return saveFiles.any { entry ->
            !entry.get("root").value.isNullOrBlank() || !entry.get("pattern").value.isNullOrBlank()
        }
    }

    /**
     * Post-upload retention check. Re-fetches the cloud manifest and reports whether it is COMPLETELY
     * EMPTY (0 files). Returns true = empty (the just-committed upload was NOT retained → no cloud),
     * false = non-empty (retained, e.g. HL2), or null if the manifest re-fetch itself failed (⇒ can't
     * tell → caller keeps the optimistic success and does not mark).
     */
    private fun isCloudManifestEmpty(sc: SteamCloud, appId: Int): Boolean? {
        return try {
            val fresh = sc.getAppFileListChange(appId).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
            fresh.files.isEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "post-upload emptiness check failed for appId=$appId", e)
            null
        }
    }

    // ── Combo actions (Cloud ⇄ Container in one press) ───────────────────────────
    // Each combo chains two of the moves above via callbacks: phase-1's onDone triggers phase 2.
    // onStatus from BOTH phases is forwarded to the caller; a phase-1 onError stops and propagates;
    // the final onDone composes both phases' summaries. Both require the game to be set up in a
    // container (checked up front via [SteamCloudSavePaths.resolveContainer]) — otherwise they do NO
    // work and report a single, clear error. The [SaveSyncStore] record hooks fire INSIDE the
    // underlying wrapper moves (download/apply/collect/upload); the combos add none, so nothing is
    // double-recorded. Upload stays additive (unchanged).

    /** ⬇ Sync from Cloud — Download (Cloud→Library) THEN Apply (Library→Container). No work + a
     *  single [Callback.onError] if the game isn't in a container yet. */
    fun syncFromCloud(ctx: Context, appId: Int, installDir: String, cb: Callback) {
        if (SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) == null) {
            cb.onError("This game needs to be added to a container first.")
            return
        }
        // Phase 1: Download. Its onDone chains into phase 2; its onError stops the whole combo.
        downloadToLibrary(ctx, appId, object : Callback {
            override fun onStatus(message: String) = cb.onStatus(message)
            override fun onError(message: String) = cb.onError(message)
            override fun onDone(downloadSummary: String) {
                // Phase 2: Apply. Its onDone composes the final combo summary.
                applyToContainer(ctx, appId, installDir, object : Callback {
                    override fun onStatus(message: String) = cb.onStatus(message)
                    override fun onError(message: String) = cb.onError(message)
                    override fun onDone(applySummary: String) {
                        cb.onDone("Synced from cloud ($downloadSummary; $applySummary)")
                    }
                })
            }
        })
    }

    /** ⬆ Sync to Cloud — Collect (Container→Library) THEN Upload (Library→Cloud, strictly additive).
     *  No work + a single [Callback.onError] if the game isn't in a container yet. */
    fun syncToCloud(ctx: Context, appId: Int, installDir: String, cb: Callback) {
        if (SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) == null) {
            cb.onError("This game needs to be added to a container first.")
            return
        }
        // Phase 1: Collect. Its onDone chains into phase 2; its onError stops the whole combo.
        collectFromContainer(ctx, appId, installDir, object : Callback {
            override fun onStatus(message: String) = cb.onStatus(message)
            override fun onError(message: String) = cb.onError(message)
            override fun onDone(collectSummary: String) {
                // Phase 2: Upload — additive (filesToDelete stays emptyList()); composes the summary.
                uploadFromLibrary(ctx, appId, object : Callback {
                    override fun onStatus(message: String) = cb.onStatus(message)
                    override fun onError(message: String) = cb.onError(message)
                    override fun onDone(uploadSummary: String) {
                        cb.onDone("Synced to cloud ($collectSummary; $uploadSummary)")
                    }
                })
            }
        })
    }

    // ── Blocking / gated variants (Java-friendly, for game launch/exit hooks) ─────
    // The async combos above report via a Callback on their own worker thread. The launch/exit paths
    // want a single blocking call that returns a summary String. These drive the existing combos to
    // completion with a CountDownLatch, bounded so a stalled CM/upload can't hang the game exit. Every
    // one is non-throwing at the boundary — it returns an error/summary String instead.

    /** Overall bound for a blocking cloud op (Collect+Upload / Download+Apply). Keeps game-exit snappy;
     *  on timeout the underlying additive transfer keeps running in the background and simply isn't
     *  awaited (a later sync reconciles — uploads are additive, downloads overwrite the Library). */
    private const val BLOCKING_BOUND_MS = 15_000L

    /** Result of a blocking drive of one [Callback]-based move. */
    private data class BlockingResult(val ok: Boolean, val summary: String)

    /** Start a [Callback]-based move and block up to [boundMs] for its terminal onDone/onError. */
    private fun runBlockingMove(boundMs: Long, start: (Callback) -> Unit): BlockingResult {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<BlockingResult>()
        try {
            start(object : Callback {
                override fun onStatus(message: String) {}
                override fun onDone(summary: String) {
                    holder.compareAndSet(null, BlockingResult(true, summary)); latch.countDown()
                }
                override fun onError(message: String) {
                    holder.compareAndSet(null, BlockingResult(false, message)); latch.countDown()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "runBlockingMove start failed", e)
            return BlockingResult(false, "Sync error: ${e.message ?: e.javaClass.simpleName}")
        }
        val done = try { latch.await(boundMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!done) return BlockingResult(false, "Still syncing in the background (didn't finish within ${boundMs / 1000}s)")
        return holder.get() ?: BlockingResult(false, "No result")
    }

    /**
     * [hasCloudSupport] wrapped with a persisted [SteamPrefs] cache so a launch/exit path doesn't hit
     * PICS every time. Returns the persisted verdict if known; otherwise probes once and persists a
     * DEFINITIVE result. `null` (unknown) is never cached — it can be retried later.
     */
    @JvmStatic
    fun hasCloudSupportCached(ctx: Context, appId: Int): Boolean? {
        SteamPrefs.getCloudSupportCached(ctx, appId)?.let { return it }
        val resolved = hasCloudSupport(ctx, appId)
        if (resolved != null) SteamPrefs.setCloudSupportCached(ctx, appId, resolved)
        return resolved
    }

    /**
     * Blocking Collect→Upload (additive). If [hasCloudSupportCached] is `false`, does the local Collect
     * (Container→Library) ONLY and returns a "local only" summary — no cloud batch is opened. Never
     * throws; returns an error/summary String.
     */
    @JvmStatic
    fun syncToCloudBlocking(ctx: Context, appId: Int, installDir: String): String {
        return try {
            // No-cloud games: still preserve the save locally (Collect into the Library), just don't
            // pretend to upload. Same honest posture as uploadSaves' NO_CLOUD path.
            if (hasCloudSupportCached(ctx, appId) == false) {
                val r = runBlockingMove(BLOCKING_BOUND_MS) { cb -> collectFromContainer(ctx, appId, installDir, cb) }
                return "No Steam Cloud support — saved locally only (${r.summary})"
            }
            runBlockingMove(BLOCKING_BOUND_MS) { cb -> syncToCloud(ctx, appId, installDir, cb) }.summary
        } catch (e: Exception) {
            Log.e(TAG, "syncToCloudBlocking failed", e)
            "Sync error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Blocking Download→Apply with NEWEST-WINS. Downloads the cloud saves into the Library first (that
     * never touches the container), then compares the cloud copy's newest mtime (now reflected in the
     * Library) against the container's newest save mtime via [staleness]. If the container copy is
     * newer-or-equal, the Apply is SKIPPED (local kept) and that is reported. Never throws.
     */
    @JvmStatic
    fun syncFromCloudNewestWins(ctx: Context, appId: Int, installDir: String): String {
        return try {
            if (SteamCloudSavePaths.resolveContainer(ctx, appId, installDir) == null) {
                return "This game needs to be added to a container first."
            }
            // Phase 1: Download cloud → Library. Populates the Library with the cloud copy (mtimes
            // preserved from the cloud timestamps), leaving the container untouched.
            val dl = runBlockingMove(BLOCKING_BOUND_MS) { cb -> downloadToLibrary(ctx, appId, cb) }
            if (!dl.ok) return dl.summary   // e.g. not signed in / download failed — nothing to apply

            // Phase 2: newest-wins. libraryNewest now == the cloud copy's newest mtime; compare to the
            // container's newest save. staleness() scopes the container side to THIS game's saves.
            val st = staleness(ctx, appId, installDir)
            if (st.libraryFileCount == 0) {
                return "Nothing in the cloud to apply (${dl.summary})"
            }
            if (st.containerFileCount > 0 && st.containerNewestMtime >= st.libraryNewestMtime) {
                return "Kept your local save — the container copy is newer than (or the same age as) " +
                    "the cloud. Cloud copy downloaded to your Library (${dl.summary})."
            }
            // Cloud is newer (or the container has no save yet) → Apply Library → Container.
            val ap = runBlockingMove(BLOCKING_BOUND_MS) { cb -> applyToContainer(ctx, appId, installDir, cb) }
            "Synced from cloud (${dl.summary}; ${ap.summary})"
        } catch (e: Exception) {
            Log.e(TAG, "syncFromCloudNewestWins failed", e)
            "Sync error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Wrap a caller [Callback] so [onSuccess] runs after a successful move (on the move's own worker
     * thread, right after the UI is notified via [Callback.onDone]). Used to fire the
     * [SaveSyncStore] record hooks without changing any move's behavior; a hook failure is swallowed
     * so it can never turn a successful move into a reported error.
     */
    private fun hooked(delegate: Callback, onSuccess: () -> Unit): Callback = object : Callback {
        override fun onStatus(message: String) = delegate.onStatus(message)
        override fun onDone(summary: String) {
            // Run the record hook's FAST/LOCAL write BEFORE delivering onDone, so the UI's post-move
            // per-row statusOf reads the freshly-stamped lastDownloadAt/lastUploadAt (was stale when
            // onDone fired first). The hook's SLOW cloud re-baseline is dispatched to its own thread
            // inside SaveSyncStore, so this does not delay onDone.
            try { onSuccess() } catch (e: Exception) { Log.w(TAG, "save-status record hook failed", e) }
            delegate.onDone(summary)
        }
        override fun onError(message: String) = delegate.onError(message)
    }

    /** Library → Container. For every file in the Library, translate its `%Root%/rest` layout to an
     *  absolute container path via [SteamCloudSavePaths.toContainerPath] and copy it in (mkdirs,
     *  mtime preserved). Overwrites the container's copies; never deletes anything, never touches the
     *  cloud. Files whose leading root token is unknown/unsafe are skipped (logged), never guessed. */
    fun applyToContainer(ctx: Context, appId: Int, installDir: String, outerCb: Callback) {
        val cb = hooked(outerCb) { SaveSyncStore.recordAfterApply(ctx, appId) }
        Thread({
            try {
                val library = SteamCloudSavePaths.libraryDir(ctx, appId)
                val container = SteamCloudSavePaths.resolveContainer(ctx, appId, installDir)
                    ?: run { cb.onError("This game isn't set up in a container yet"); return@Thread }

                cb.onStatus("Scanning Library…")
                val files = enumerateLocal(library)
                if (files.isEmpty()) {
                    cb.onDone("Library is empty — download from the cloud first")
                    return@Thread
                }

                var applied = 0
                var skipped = 0
                for ((file, rel) in files) {
                    val dest = SteamCloudSavePaths.toContainerPath(rel, container, installDir)
                    if (dest == null) {
                        Log.w(TAG, "Apply: skipping unmapped/unsafe path: $rel")
                        skipped++
                        continue
                    }
                    cb.onStatus("Applying: ${file.name}")
                    copyPreserving(file, dest)
                    applied++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Applied $applied file${plural(applied)} to " +
                    "${SteamCloudSavePaths.containerLabel(container)}$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "applyToContainer failed", e)
                cb.onError("Apply error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-apply-$appId").start()
    }

    /** Container → Library. Walks the container's save scopes for this game (see
     *  [enumerateContainerSaves]: the directories the Library already established, PLUS a name-match
     *  discovery fallback so a never-downloaded game with an empty Library can still be collected),
     *  maps each file back to its `%Root%/rest` layout via [SteamCloudSavePaths.toLibraryRel], and
     *  copies it into the Library (mkdirs, mtime preserved). Overwrites the Library's copies; never
     *  deletes anything from the container, never touches the cloud. */
    fun collectFromContainer(ctx: Context, appId: Int, installDir: String, outerCb: Callback) {
        val cb = hooked(outerCb) { SaveSyncStore.recordAfterCollect(ctx, appId) }
        Thread({
            try {
                val library = SteamCloudSavePaths.libraryDir(ctx, appId)
                val shortcut = resolveShortcut(ctx, installDir)
                    ?: run { cb.onError("This game isn't set up in a container yet"); return@Thread }

                cb.onStatus("Scanning container saves…")
                val saves = enumerateContainerSaves(ctx, appId, shortcut, installDir)
                if (saves.isEmpty()) {
                    cb.onDone("No saves found in " +
                        "${SteamCloudSavePaths.containerLabel(shortcut.container)} to collect")
                    return@Thread
                }

                var collected = 0
                var skipped = 0
                for ((file, libraryRel) in saves) {
                    val relLocal = sanitizeRelative(libraryRel)
                    if (relLocal == null) {
                        Log.w(TAG, "Collect: skipping unsafe library path: $libraryRel")
                        skipped++
                        continue
                    }
                    cb.onStatus("Collecting: ${file.name}")
                    copyPreserving(file, File(library, relLocal))
                    collected++
                }

                val suffix = if (skipped > 0) " ($skipped skipped)" else ""
                cb.onDone("Collected $collected file${plural(collected)} into your Library$suffix")
            } catch (e: Exception) {
                Log.e(TAG, "collectFromContainer failed", e)
                cb.onError("Collect error: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "steam-cloud-collect-$appId").start()
    }

    /** Freshness snapshot for the staleness guard the UI shows before Apply/Upload. Synchronous
     *  filesystem scan — the caller runs it off the main thread. Container side is scoped to this
     *  game's save directories (the same set [collectFromContainer] would collect), so it never
     *  reflects other games' data. */
    data class Staleness(
        val libraryNewestMtime: Long,   // 0 if Library empty/absent
        val containerNewestMtime: Long, // 0 if container absent or no save files
        val libraryFileCount: Int,
        val containerFileCount: Int,
    )

    fun staleness(ctx: Context, appId: Int, installDir: String): Staleness {
        val library = SteamCloudSavePaths.libraryDir(ctx, appId)
        val libraryFiles = enumerateLocal(library)
        val libraryNewest = libraryFiles.maxOfOrNull { it.first.lastModified() } ?: 0L

        val shortcut = resolveShortcut(ctx, installDir)
        val containerSaves = if (shortcut == null) emptyList() else try {
            enumerateContainerSaves(ctx, appId, shortcut, installDir)
        } catch (e: Exception) {
            Log.w(TAG, "staleness: container scan failed", e); emptyList()
        }
        val containerNewest = containerSaves.maxOfOrNull { it.first.lastModified() } ?: 0L

        return Staleness(libraryNewest, containerNewest, libraryFiles.size, containerSaves.size)
    }

    /**
     * The container-side save files for this game, paired with their `%Root%/rest` library-relative
     * paths. Two passes, de-duped by canonical file path:
     *
     *  1. **Primary** — the container directories the Library already established as this game's save
     *     locations (each tracked Library file's container-parent), walked for their current
     *     contents. Discovers NEW sibling files in the real save folder without sweeping a whole
     *     shared root. Contributes nothing until the Library has been populated once.
     *  2. **Discovery fallback** — OUR [SaveLocator.discover] heuristic name-matches candidate save
     *     folders inside the container's Wine profile from the game's shortcut ([Shortcut.name] /
     *     [Shortcut.path] / [Shortcut.wmClass]). Each candidate's [SaveLocator.Candidate.relPath] is
     *     profile-relative; we resolve it under [SaveLocator.profileDir] and walk it. This is what
     *     lets a brand-new game with an EMPTY Library still be Collected → Uploaded.
     *
     * Every file is mapped through [SteamCloudSavePaths.toLibraryRel], which rejects escapes and
     * unknown roots (null ⇒ skip), so both passes stay safety-fenced and never emit files outside a
     * known UFS root.
     */
    private fun enumerateContainerSaves(
        ctx: Context, appId: Int, shortcut: Shortcut, installDir: String,
    ): List<Pair<File, String>> {
        val container: Container = shortcut.container
        val library = SteamCloudSavePaths.libraryDir(ctx, appId)

        val out = ArrayList<Pair<File, String>>()
        val seen = HashSet<String>()

        // Map one container file → its %Root%/rest path and record it once (de-dupe across passes).
        fun consider(f: File) {
            if (!f.isFile) return
            val libraryRel = SteamCloudSavePaths.toLibraryRel(f, container, installDir) ?: return
            val canon = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
            if (seen.add(canon)) out.add(f to libraryRel)
        }

        // ── Pass 1: directories the Library already established for this game. ──
        val scopeDirs = LinkedHashSet<File>()
        for ((_, rel) in enumerateLocal(library)) {
            val dest = SteamCloudSavePaths.toContainerPath(rel, container, installDir) ?: continue
            val parent = dest.parentFile ?: continue
            if (parent.isDirectory) {
                try { scopeDirs.add(parent.canonicalFile) } catch (_: Exception) {}
            }
        }
        for (dir in scopeDirs) {
            dir.walkTopDown().filter { it.isFile }.forEach { consider(it) }
        }

        // ── Pass 2: name-match discovery fallback (works with an empty Library). ──
        try {
            val profile = SaveLocator.profileDir(container)
            val candidates = SaveLocator.discover(
                container,
                shortcut.name ?: "",
                shortcut.path ?: "",
                shortcut.wmClass ?: "",
            )
            for (c in candidates) {
                val dir = File(profile, c.relPath)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile }.forEach { consider(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Collect discovery pass failed", e)
        }

        return out
    }

    /**
     * The game's launch shortcut — the `.desktop` whose exec target sits under [installDir] — or
     * null if the game isn't set up in a container. Same matching rule as
     * [SteamCloudSavePaths.resolveContainer], but returns the whole [Shortcut] so Collect can read
     * its name/path/wmClass for the [SaveLocator.discover] pass (and reach its container).
     */
    private fun resolveShortcut(ctx: Context, installDir: String): Shortcut? {
        if (installDir.isBlank()) return null

        val manager = ContainerManager(ctx)
        val shortcuts = try { manager.loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }

        val imageFsRoot = File(ctx.filesDir, "imagefs").absolutePath.replace('\\', '/').trimEnd('/')
        val instAbs = installDir.replace('\\', '/').trimEnd('/')
        val instRel = if (instAbs.lowercase().startsWith(imageFsRoot.lowercase()))
            instAbs.substring(imageFsRoot.length).trimStart('/') else instAbs.trimStart('/')
        val keys = listOf("/${instRel.lowercase()}/", "/${instAbs.trimStart('/').lowercase()}/")

        for (sc in shortcuts) {
            val raw = sc.path ?: continue
            var exec = raw.replace('\\', '/').lowercase().trim()
            exec = exec.replaceFirst(Regex("^[a-z]:"), "")
            if (!exec.startsWith("/")) exec = "/$exec"
            if (keys.any { it.length > 2 && exec.contains(it) }) return sc
        }
        return null
    }

    /** Copy [src] onto [dst] (creating parents), preserving the modified time. Overwrites [dst]. */
    private fun copyPreserving(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
        try { dst.setLastModified(src.lastModified()) } catch (_: Exception) {}
    }

    /**
     * Run [action] over [items] on a fixed-size pool of at most [TRANSFER_CONCURRENCY] daemon
     * workers and block until every item is done. [action] is expected to swallow its own failures
     * (record them in a thread-safe accumulator) — this helper only logs anything that still escapes
     * so one bad task can't strand the others. The pool is always shut down before returning.
     */
    private fun <T> runConcurrently(items: List<T>, threadLabel: String, action: (T) -> Unit) {
        if (items.isEmpty()) return
        val workers = minOf(TRANSFER_CONCURRENCY, items.size)
        val seq = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "$threadLabel-${seq.incrementAndGet()}").apply { isDaemon = true }
        }
        try {
            val futures = items.map { item -> pool.submit { action(item) } }
            for (f in futures) {
                try { f.get() } catch (e: Exception) { Log.w(TAG, "$threadLabel worker error", e) }
            }
        } finally {
            pool.shutdown()
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun requireCloud(): SteamCloud? = SteamRepository.getInstance().steamCloud

    /** The remote (Steam-side) path for a file = its path prefix + filename, as Steam stores it.
     *  This exact string is what [SteamCloud.clientFileDownload] and the upload calls key on. */
    private fun remotePathOf(f: AppFileInfo, list: AppFileChangeList): String {
        val prefixes = list.pathPrefixes
        val idx = f.pathPrefixIndex
        val prefix = if (idx in prefixes.indices) prefixes[idx] else ""
        return when {
            prefix.isEmpty() -> f.filename
            f.filename.isEmpty() -> prefix
            // Join with a single '/', matching GameNative's Paths.get(prefix, filename) on unix.
            else -> prefix.trimEnd('/', '\\') + "/" + f.filename.trimStart('/', '\\')
        }
    }

    /** Convert a Steam cloud path into a SAFE relative filesystem path under the local folder.
     *  Normalizes '\' -> '/', strips leading slashes, and REJECTS any '..' traversal (returns null).
     *  Placeholder folders like %WinMyDocuments% are kept verbatim as literal directory names, so a
     *  later upload reconstructs the identical cloud path from the local layout. */
    private fun sanitizeRelative(path: String): String? {
        val norm = path.replace('\\', '/').trimStart('/')
        if (norm.isEmpty()) return null
        val parts = norm.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** GET one cloud file to [dest]. Returns true on success. Only writes locally. */
    private fun downloadOne(sc: SteamCloud, appId: Int, remotePath: String, dest: File): Boolean {
        val info = sc.clientFileDownload(appId, remotePath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (info.urlHost.isEmpty()) {
            Log.w(TAG, "Empty CDN host for $remotePath")
            return false
        }
        val url = (if (info.useHttps) "https://" else "http://") + info.urlHost + info.urlPath
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        for (h in info.requestHeaders) conn.setRequestProperty(h.name, h.value)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code downloading $remotePath")
                return false
            }
            dest.parentFile?.mkdirs()
            // Steam serves the file zip-compressed when fileSize != rawFileSize (single entry).
            val compressed = info.fileSize != info.rawFileSize
            conn.inputStream.use { raw ->
                if (compressed) {
                    ZipInputStream(raw).use { zip ->
                        if (zip.nextEntry == null) {
                            Log.w(TAG, "Compressed cloud file $remotePath had no zip entry")
                            return false
                        }
                        dest.outputStream().use { out -> zip.copyTo(out) }
                    }
                } else {
                    dest.outputStream().use { out -> raw.copyTo(out) }
                }
            }
            try { dest.setLastModified(info.timestamp.time) } catch (_: Exception) {}
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** Upload a single local file to the cloud under [cloudPath]. Additive — no deletion involved. */
    private fun uploadOne(sc: SteamCloud, appId: Int, file: File, cloudPath: String, batchId: Long): Boolean {
        val sha = sha1(file)
        val fileSize = file.length().toInt()

        // beginFileUpload (JavaSteam 1.8.0): the remaining params (platformsToSync, cellId,
        // canEncrypt, isSharedFile, deprecatedRealm, parentScope) carry Kotlin defaults.
        val info = sc.beginFileUpload(
            appId = appId,
            fileSize = fileSize,
            rawFileSize = fileSize,
            fileSha = sha,
            timestamp = Date(file.lastModified()),
            filename = cloudPath,
            uploadBatchId = batchId,
        ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)

        var ok = true
        RandomAccessFile(file, "r").use { raf ->
            for (block in info.blockRequests) {
                val len = block.blockLength
                val buf = ByteArray(len)
                raf.seek(block.blockOffset)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val url = (if (block.useHttps) "https://" else "http://") + block.urlHost + block.urlPath
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.doOutput = true
                conn.requestMethod = "PUT"
                for (h in block.requestHeaders) conn.setRequestProperty(h.name, h.value)
                try {
                    conn.setFixedLengthStreamingMode(read)
                    conn.outputStream.use { out -> out.write(buf, 0, read) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        Log.w(TAG, "HTTP $code uploading block of $cloudPath")
                        ok = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Block upload failed for $cloudPath: ${e.javaClass.simpleName}")
                    ok = false
                } finally {
                    conn.disconnect()
                }
            }
        }

        // Commit tells the CM whether the transfer for this file succeeded. This does not delete
        // anything; on failure the CM simply drops this file's pending upload.
        sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        return ok
    }

    /** Recursively list files under [root], each paired with its cloud path (relative to root,
     *  '/'-separated). Empty list if the folder is absent/empty (upload then refuses). */
    private fun enumerateLocal(root: File): List<Pair<File, String>> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val base = root.absolutePath.trimEnd('/')
        val out = ArrayList<Pair<File, String>>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isNotEmpty()) out.add(f to rel)
        }
        return out
    }

    private fun sha1(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n >= 0) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest()
    }

    private fun machineName(): String = "Bannerlator (${Build.MODEL})"

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
