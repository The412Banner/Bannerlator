package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.BuildConfig
import com.winlator.star.store.blsteam.BlDownloadListener
import com.winlator.star.store.blsteam.BlLibraryCrawler
import com.winlator.star.store.blsteam.BlSteamEngine
import com.winlator.star.store.blsteam.BlSteamEngineLog
import com.winlator.star.store.blsteam.BlSteamSession
import com.winlator.star.store.blsteam.CaBundleExtractor
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Steam depot install / resume / update / verify on the native Rust engine (`libblsteam.so`) —
 * Phase 2-A of docs/STEAM_RUST_ENGINE_PLAN.md. Selected by [SteamDepotDownloader.installApp] /
 * [SteamDepotDownloader.resumeApp] when `use_rust_steam_engine` is ON; the JavaSteam engine path
 * in [SteamDepotDownloader] is untouched when OFF.
 *
 * Same public contract the UI drives today:
 *  - [SteamDepotDownloader.DownloadControl] (cancel / pause), the `DownloadProgress:` /
 *    `DownloadComplete:` / `DownloadFailed:` / `DownloadPaused:` / `DownloadCancelled:` events, the
 *    [DownloadRegistry] row (bytes / percent / speed / ETA), the `steam_downloads` DB row and, on
 *    completion, `markInstalled` + the `.bannerlator_build` marker.
 *  - Install location resolves exactly like the JavaSteam path (resume → the in-progress row's dir;
 *    update / verify → the installed row's dir; fresh → `installRoot` (the "Install to SD card"
 *    toggle) or the internal `imagefs/steam_games` default).
 *  - Depot selection = the `depot_manifests` rows the library sync wrote (Windows/English content
 *    depots incl. the DLC depots grouped positionally in Phase 1-A) minus the DLC the user opted out
 *    of and the app's known stale-duplicate depots.
 *
 * ## Engine semantics (why the false-complete bug class cannot exist here)
 * The engine ([BlSteamSession.downloadApp]) resolves depot keys + manifest request codes over the
 * CM, fetches each manifest from the CDN (server rotation + backoff), then writes the depot chunk
 * by chunk: a chunk already on disk with the right Adler hash is kept (reported as `verifying`),
 * anything else is fetched, decrypted, decompressed and written. A depot is recorded as installed
 * at its manifest id in the journal only after every chunk passed and every file was truncated to
 * its manifest size. Completion is therefore decided by reading that journal back
 * ([journalInstalledManifests]): the install is COMPLETE iff every selected depot is recorded at
 * exactly the manifest id we asked for. Bytes transferred this pass never enter the verdict.
 *
 * ## Journal + resume
 * `<installDir>/.bl_depot/` (see the crate's `depot_downloader::CONFIG_DIR_NAME`): `depot.config`
 * (installed manifest per depot; an in-progress sentinel while a depot is being written), cached
 * raw manifests, clean-pause markers, `denied.depots`. A pause / cancel-by-network / process death
 * leaves the files + journal in place; the next pass (resume, or a plain install of the same app)
 * skips depots recorded at the same manifest, re-validates every chunk of the others and fetches
 * only the missing/corrupt ones. A depot whose manifest moved (game update) is simply not recorded
 * at the new id → delta by chunk hash. "Verify integrity" = `fresh = true`: forget the journal for
 * these depots so every chunk of every depot is re-validated against the live manifest.
 *
 * One native download runs at a time (the engine's cancel flag is per session). Serialization is
 * enforced ABOVE this class by the engine-agnostic [DownloadQueue]: a worker is spawned only when
 * its appId owns the queue slot, so there is no in-worker gate/park here — queued requests are data
 * (a QUEUED registry row), not parked threads. Blocking work runs on a worker thread; listener
 * callbacks arrive on native threads.
 */
internal object BlDepotInstaller {

    private const val TAG = "BL_STEAM_DL"

    /** Journal directory name — must match the crate's `depot_downloader::CONFIG_DIR_NAME`. */
    const val JOURNAL_DIR = ".bl_depot"

    /** Session-recovery retries (network drop / CM logoff mid-download) before failing to the user. */
    private const val MAX_SESSION_RETRIES = 2
    private val RETRY_BACKOFF_MS = longArrayOf(3_000L, 8_000L)
    /**
     * Layer 1 (parity with the JavaSteam path's MAX_DEPOT_RESUME_ATTEMPTS): the engine can
     * report the pass a success while the journal still shows a selected depot SHORT of its manifest
     * — a dropped CDN chunk stream that the pass gave up on (Dead Cells' 588651 stalling at ~50%).
     * Re-enter as a RESUME: the journal + per-chunk Adler re-check means only the missing chunks are
     * fetched. Bounded, with a no-forward-progress fail-fast so a depot with no reachable chunks
     * (dead CDN / no key) can never loop.
     */
    private const val MAX_DEPOT_RESUME_ATTEMPTS = 3
    private val DEPOT_RESUME_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L)
    private const val MIN_RESUME_PROGRESS_BYTES = 1_048_576L

    private const val SESSION_WAIT_MS = 30_000L
    private const val PICS_REFRESH_MS = 15_000L

    /** The appId whose download currently owns the engine session (and therefore its cancel flag);
     *  -1 when idle. Set by [run] once it owns the queue slot; only one is ever non-idle at a time
     *  because [DownloadQueue] serializes starts. Used by [cancelNativeIfOwner]. */
    @Volatile private var activeAppId: Int = -1

    /** Cancel the native download only if it is OURS; a queued request just sets its flags. */
    private fun cancelNativeIfOwner(appId: Int) {
        if (activeAppId == appId) try { BlSteamEngine.session()?.cancelDownload() } catch (_: Throwable) {}
    }

    fun start(
        appId: Int,
        ctx: Context,
        speedTier: Int,
        debugLog: Boolean,
        isResume: Boolean,
        installRoot: String?,
        verify: Boolean,
    ): SteamDepotDownloader.DownloadControl {
        val cancelled = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        val control = SteamDepotDownloader.DownloadControl(
            cancel = Runnable {
                if (cancelled.compareAndSet(false, true)) {
                    paused.set(false)
                    dlog("Cancel requested for appId=$appId")
                    cancelNativeIfOwner(appId)
                }
            },
            pause = Runnable {
                if (!cancelled.get() && paused.compareAndSet(false, true)) {
                    dlog("Pause requested for appId=$appId")
                    cancelNativeIfOwner(appId)
                }
            },
        )
        Thread({
            try {
                run(appId, ctx.applicationContext, cancelled, paused, speedTier, debugLog, isResume,
                    installRoot, verify, control)
            } catch (t: Throwable) {
                Log.e(TAG, "install worker crashed", t)
                SteamDepotDownloader.emitFailed(appId, "${t.javaClass.simpleName}: ${t.message}")
                SteamDepotDownloader.activeDownloads.remove(appId)
            }
        }, "bl-depot-$appId").start()
        return control
    }

    // ── worker ────────────────────────────────────────────────────────────────────────────────

    private fun run(
        appId: Int,
        ctx: Context,
        cancelled: AtomicBoolean,
        paused: AtomicBoolean,
        speedTier: Int,
        debugLog: Boolean,
        isResume: Boolean,
        installRoot: String?,
        verify: Boolean,
        control: SteamDepotDownloader.DownloadControl,
        attempt: Int = 0,
        resumeAttempt: Int = 0,
        resumeFloorBytes: Long = 0L,
    ) {
        val verbose = BuildConfig.DEBUG || debugLog
        SteamDepotDownloader.activeDownloads[appId] = Unit
        if (verbose) SteamDepotDownloader.initDebugLog(ctx, truncate = attempt == 0, engine = "Rust engine (libblsteam.so)")
        dlog("=== Starting ${if (verify) "verify" else if (isResume) "resume" else "install"}: appId=$appId " +
                "(engine=rust attempt=${attempt + 1} verbose=$verbose) ===")
        BlSteamEngineLog.log("DL", "${if (verify) "verify" else if (isResume) "resume" else "install"} started app=$appId attempt=${attempt + 1}")

        val repo = SteamRepository.getInstance()
        val db = repo.database
        val row = db.getGame(appId)
        if (row == null) {
            dlog("FAIL: appId=$appId not found in database")
            fail(appId, "Game not found in database"); return
        }
        dlog("Game: name='${row.name}' type=${row.type} sizeBytes=${row.sizeBytes}")

        // ── Session: a live logged-on engine session, or re-drive the saved-token logon ─────────
        val session = ensureSession(ctx)
        if (session == null) {
            dlog("Session status at failure: ${repo.lastSessionStatus}")
            fail(appId, "Steam session not ready — sign in again or retry in a moment"); return
        }
        if (cancelled.get()) { finishCancelled(appId, db); return }

        // ── Install dir (same rules as the JavaSteam path) ────────────────────────────────────
        val safeName = row.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val internalBase = File(ctx.filesDir, "imagefs/steam_games")
        val resumeDir = if (isResume) db.getDownload(appId)?.installDir?.takeIf { it.isNotBlank() } else null
        val existingDir = row.installDir.takeIf { it.isNotBlank() }
        val installDir = when {
            resumeDir != null -> File(resumeDir)
            existingDir != null -> File(existingDir)
            installRoot != null -> File(File(installRoot), safeName)
            else -> File(internalBase, safeName)
        }
        val isFreshSdInstall = installRoot != null && resumeDir == null && existingDir == null
        val onInternal = installDir.absolutePath.startsWith(ctx.filesDir.absolutePath.trimEnd('/') + "/")
        dlog("Install dir: ${installDir.absolutePath}${if (onInternal) "" else " (external/SD)"} journal=$JOURNAL_DIR/")

        // ── Fresh product info: live manifest ids + branch build for THIS pass (delta by manifest) ─
        if (attempt == 0) {
            val refreshed = runBounded(PICS_REFRESH_MS) { repo.refreshAppProductInfo(appId, PICS_REFRESH_MS) }
            dlog("Single-app PICS refresh → ${refreshed ?: "timed out (using synced rows)"}")
        }
        if (cancelled.get()) { finishCancelled(appId, db); return }

        // ── Depot selection ───────────────────────────────────────────────────────────────────
        val selectedBranch = try { SteamPrefs.getSelectedBranch(appId) } catch (_: Throwable) { "public" }
        val excludedDlc = try { SteamPrefs.getExcludedDlc(appId) } catch (_: Throwable) { emptySet() }
        val staleDupes = SteamDepotDownloader.staleDuplicateDepots(appId)
        val allRows = try { db.getDepotManifests(appId) } catch (_: Throwable) { emptyList() }
        val keptRows = allRows.filter { it.depotId !in excludedDlc && it.depotId !in staleDupes }
        if (keptRows.isEmpty()) {
            dlog("FAIL: no depot rows for appId=$appId (library not synced for this app?)")
            fail(appId, "No downloadable depots known for this game yet — open its store page to refresh, then retry")
            return
        }
        if (excludedDlc.isNotEmpty() || staleDupes.isNotEmpty()) {
            dlog("Depot filter: excluded DLC=${excludedDlc.joinToString(",")} stale-dupes=${staleDupes.joinToString(",")}")
        }
        val specs: List<Pair<Int, Long>> = if (selectedBranch == "public") {
            keptRows.filter { it.manifestId != 0L }.map { it.depotId to it.manifestId }
        } else {
            val r = resolveBranchManifests(session, appId, selectedBranch, keptRows.map { it.depotId }, db)
            if (r == null) {
                fail(appId, "Beta branch \"$selectedBranch\" could not be resolved — if it is " +
                        "password-protected, enter its access code on the game page, then retry")
                return
            }
            r
        }
        if (specs.isEmpty()) {
            fail(appId, "No manifests resolved for branch \"$selectedBranch\""); return
        }
        dlog("Depots (${specs.size}, branch=$selectedBranch): " + specs.joinToString(" ") { "${it.first}@${it.second}" })

        // ── Denominators (exclusion-aware, manifest-true when resolved) ────────────────────────
        val keptForDenom = keptRows.filter { r -> specs.any { it.first == r.depotId } }
        val keptReal = if (keptForDenom.isNotEmpty() && keptForDenom.all { it.realSizeBytes > 0L })
            keptForDenom.sumOf { it.realSizeBytes } else 0L
        val keptPics = keptForDenom.sumOf { it.sizeBytes }
        val realGameSize = try { db.getGameRealSize(appId) } catch (_: Throwable) { 0L }
        val installTotalSeed = when {
            keptReal > 0L -> keptReal
            keptPics > 0L -> keptPics
            realGameSize > 0L -> realGameSize
            row.sizeBytes > 0L -> row.sizeBytes
            else -> 1L
        }
        val keptDownload = if (keptForDenom.isNotEmpty() && keptForDenom.all { it.realDownloadBytes > 0L })
            keptForDenom.sumOf { it.realDownloadBytes } else 0L
        val cachedDownload = if (keptDownload > 0L) keptDownload else repo.getSelectedDownloadSize(appId)
        val downloadTotalSeed = if (cachedDownload > 0L) cachedDownload else installTotalSeed
        dlog("Denominators: install=${fmt(installTotalSeed)} download=${fmt(downloadTotalSeed)}")

        if (isFreshSdInstall && installTotalSeed > 1L) {
            var probe = installDir
            while (!probe.exists() && probe.parentFile != null) probe = probe.parentFile!!
            val free = SteamSdInstall.freeBytes(probe)
            if (free < installTotalSeed + SteamSdInstall.FREE_SPACE_MARGIN) {
                dlog("SD free-space guard FAILED: need ${fmt(installTotalSeed)} + margin, ${fmt(free)} free")
                fail(appId, "Not enough space on the SD card — need about ${fmt(installTotalSeed)}, " +
                        "${fmt(free)} free. Free some space or install to internal storage.")
                return
            }
        }

        // ── DB + registry rows ────────────────────────────────────────────────────────────────
        if (isResume || attempt > 0) db.markDownloadResuming(appId)
        else db.queueDownload(appId, installTotalSeed, installDir.absolutePath)
        val persistedInstall = if (isResume || attempt > 0) (db.getDownload(appId)?.bytesDownloaded ?: 0L) else 0L
        val installBase = if (verify) 0L else persistedInstall
        val dmKey = "${Store.STEAM}:$appId"
        if (attempt == 0 && DownloadRegistry.get(dmKey) == null) {
            DownloadRegistry.upsert(DownloadEntry(
                store = Store.STEAM, id = appId.toString(), name = row.name, cover = appId.toString(),
                state = DownloadState.DOWNLOADING,
                installDone = installBase, installTotal = installTotalSeed,
                downloadDone = 0L, downloadTotal = downloadTotalSeed,
                supportsPause = true,
                pause = { control.pause.run() }, cancel = { control.cancel.run() },
            ))
        } else {
            DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.DOWNLOADING) }
        }
        fun emitProgress(iDone: Long, iTotal: Long, dDone: Long, dTotal: Long, eta: Long = -1L, bps: Long = 0L) {
            repo.emit("DownloadProgress:$appId:$iDone:$iTotal:$dDone:$dTotal:$eta:$bps")
        }
        emitProgress(installBase, installTotalSeed, 0L, downloadTotalSeed)

        // ── One native download at a time ─────────────────────────────────────────────────────
        // DownloadQueue already guarantees this worker only runs when it owns the slot, so there is
        // no gate/park here (queued requests never reach run()). Just claim engine-session ownership.
        activeAppId = appId
        // A pause/cancel that landed between enqueue and here: honour it before touching the engine.
        if (cancelled.get()) { activeAppId = -1; finishCancelled(appId, db); return }
        if (paused.get()) { activeAppId = -1; finishPaused(appId, db, installBase); return }
        val caPath = CaBundleExtractor.ensureBundle(ctx)
        val speedConfig = DownloadSpeedConfig(speedTier)
        // B2b: the engine's fetch side is now an async adaptive-concurrency window, so this value is
        // the tier's MAX in-flight-request CEILING (not an OS-thread count). The engine bootstraps
        // low and ramps toward it only while throughput rises with a clean error/latency signal,
        // clamped to distinct-hosts × per-host-cap; a weak connection settles far below it.
        val maxWorkers = speedConfig.maxNetworkWindow.coerceIn(1, 128)
        // Process pool = maxDecompress (decrypt+decompress+write) — the DECODE-worker count, kept as
        // before. The engine runs a decoupled fetch→process pipeline so the socket never idles while
        // a worker decompresses; only the fetch side became async in B2b.
        val maxDecompress = speedConfig.maxDecompress.coerceIn(1, 32)
        SteamDepotDownloader.acquireDownloadWakelock(ctx)
        repo.setDownloadActive(true)

        // Depots the engine will skip this pass (journal already at the requested manifest, non-verify
        // pass) never report progress — count their size as done from the start so a resume's bar
        // starts where the install really is instead of at zero.
        val journalBefore: Map<Int, Long> = if (verify) emptyMap() else journalInstalledManifests(installDir)
        val skippedBase = keptForDenom
            .filter { r -> specs.any { it.first == r.depotId && journalBefore[r.depotId] == it.second } }
            .sumOf { maxOf(it.realSizeBytes, it.sizeBytes) }
        if (skippedBase > 0L) dlog("Journal: ${journalBefore.size} depot(s) recorded; ~${fmt(skippedBase)} already installed at the requested manifests")

        // Per-depot (done,total) as reported by the engine; skipped depots never report.
        val depotDone = ConcurrentHashMap<Int, Long>()
        val depotTotal = ConcurrentHashMap<Int, Long>()
        val installTotalRunning = AtomicLong(installTotalSeed)
        val downloadTotalRunning = AtomicLong(downloadTotalSeed)
        val lastInstallDone = AtomicLong(installBase)
        val lastNotifiedPct = AtomicInteger(-1)
        val sawVerifying = AtomicBoolean(false)
        val rateLock = Any()
        var smoothedBps = 0.0
        var lastRateMs = System.currentTimeMillis()
        var lastRateBytes = 0L
        val done = CountDownLatch(1)
        var success = false
        var error = ""
        var bytesWritten = 0L
        var depotsCompleted = 0
        var depotsSkipped = 0

        val listener = object : BlDownloadListener {
            override fun onProgress(depotId: Int, depotDoneB: Long, depotTotalB: Long, depotsDone: Int, depotsTotal: Int, verifying: Boolean) {
                if (verifying) sawVerifying.set(true)
                depotDone[depotId] = maxOf(depotDone[depotId] ?: 0L, depotDoneB)
                depotTotal[depotId] = maxOf(depotTotal[depotId] ?: 0L, depotTotalB)
                val sessionSum = depotDone.values.sum()
                // Verified-on-disk chunks count as done, so a resumed depot's bar catches up fast;
                // the persisted floor only holds until the engine reports its first bytes.
                val installDone = maxOf(skippedBase + sessionSum, if (sessionSum == 0L) installBase else 0L)
                lastInstallDone.set(installDone)
                var iTotal = installTotalRunning.get()
                val engineTotal = depotTotal.values.sum()
                if (engineTotal > iTotal && depotsDone + 1 >= depotsTotal) { installTotalRunning.set(engineTotal); iTotal = engineTotal }
                if (installDone > iTotal) { installTotalRunning.set(installDone); iTotal = installDone }
                var dTotal = downloadTotalRunning.get()
                // The engine reports uncompressed bytes only; the network bar follows the install fraction.
                val downloadDone = if (iTotal > 0L) (installDone.toDouble() / iTotal * dTotal).toLong() else 0L
                if (downloadDone > dTotal) { downloadTotalRunning.set(downloadDone); dTotal = downloadDone }
                val pct = if (iTotal > 0L) minOf((installDone * 100 / iTotal).toInt(), 99) else 0
                var eta = -1L
                var bps = 0L
                synchronized(rateLock) {
                    val now = System.currentTimeMillis()
                    val dt = now - lastRateMs
                    if (dt >= 1000L) {
                        val delta = downloadDone - lastRateBytes
                        if (delta > 0L) {
                            val inst = delta * 1000.0 / dt
                            smoothedBps = if (smoothedBps <= 0.0) inst else 0.6 * smoothedBps + 0.4 * inst
                        }
                        lastRateMs = now; lastRateBytes = downloadDone
                    }
                    bps = smoothedBps.toLong()
                    if (smoothedBps > 0.0 && dTotal > downloadDone) eta = ((dTotal - downloadDone) / smoothedBps).toLong()
                }
                if (lastNotifiedPct.getAndSet(pct) != pct) {
                    dlog("Progress: depot=$depotId ${depotsDone + 1}/$depotsTotal ${if (verifying) "verify" else "fetch"} " +
                            "$pct% install=${fmt(installDone)}/${fmt(iTotal)}")
                    val extra = buildString {
                        if (bps > 0L) append(" · ${formatDownloadSpeed(bps)}")
                        if (eta >= 0L) append(" · ${formatEta(eta)}")
                    }
                    val verb = if (verify) "Verifying" else "Downloading"
                    try { SteamForegroundService.setStatusText("$verb ${row.name} — $pct%$extra${DownloadQueue.fgsSuffix()}") } catch (_: Throwable) {}
                }
                emitProgress(installDone, iTotal, downloadDone, dTotal, eta, bps)
                db.updateDownloadProgress(appId, installDone)
                DownloadRegistry.update(dmKey) {
                    it.copy(state = DownloadState.DOWNLOADING, pct = pct,
                        installDone = installDone, installTotal = iTotal,
                        downloadDone = downloadDone, downloadTotal = dTotal,
                        etaSeconds = eta, speedBps = bps)
                }
            }

            override fun onComplete(ok: Boolean, err: String, written: Long, completed: Int, skipped: Int) {
                success = ok; error = err; bytesWritten = written; depotsCompleted = completed; depotsSkipped = skipped
                done.countDown()
            }
        }

        dlog("downloadApp(appId=$appId depots=${specs.size} branch=$selectedBranch fresh=$verify workers=$maxWorkers decompress=$maxDecompress)")
        var completedNormally = false
        var retryAsResume = false
        // Layer 1 bookkeeping: a genuinely-short (not Steam-denied) depot asks for a bounded resume.
        var depotResumeNeeded = false
        var shortSummary = ""
        var onDiskAtCompletion = 0L
        try {
            session.downloadApp(
                appId, specs.map { it.first }.toIntArray(), specs.map { it.second }.toLongArray(),
                selectedBranch, installDir.absolutePath, verify, caPath, maxWorkers, maxDecompress, listener,
            )
            done.await()
            dlog("downloadApp finished: success=$success error='${error}' written=${fmt(bytesWritten)} " +
                    "depots completed=$depotsCompleted skipped=$depotsSkipped verified-chunks=${sawVerifying.get()}")
            if (success) {
                // ── Completion verdict: journal says every selected depot is at its manifest ──
                val journal = journalInstalledManifests(installDir)
                val denied = deniedDepots(installDir)
                val short = specs.filter { (d, m) -> journal[d] != m }
                // Depots the account can NEVER download: Steam refused the depot key
                // (`.bl_depot/denied.depots`). A denied key never becomes a completed depot on any
                // retry, so these must NOT block the verdict — whether or not the depot is a
                // recognized DLC. (Hades' soundtrack depot 1145362, real_size=0 / not entitled, is
                // the device-confirmed case: the owned depots were complete but this one denial
                // failed the whole install.)
                val deniedSkipped = short.filter { it.first in denied }
                val completed = specs.filter { (d, m) -> journal[d] == m }
                specs.forEach { (d, m) ->
                    val st = when {
                        journal[d] == m -> "COMPLETE (journal manifest $m)"
                        d in denied -> "DENIED by Steam — skipped (not owned on this account)"
                        else -> "SHORT (journal ${journal[d] ?: "absent"} ≠ $m)"
                    }
                    dlog("Depot $d: $st")
                }
                // Only a genuinely-SHORT (NOT denied) depot blocks — that is the auto-resume/fail
                // domain (Layer 1). Denied depots are tolerated in the branches below.
                val blocking = short.filter { it.first !in denied }
                if (blocking.isNotEmpty()) {
                    completedNormally = true
                    // Every blocking depot here is non-denied (Steam granted the key but the chunk
                    // stream fell short), which is exactly what a bounded auto-resume fixes (Layer 1).
                    val msg = "Download incomplete — ${blocking.size} depot(s) not validated " +
                            "(${blocking.joinToString(",") { it.first.toString() }})"
                    dlog("INCOMPLETE: $msg")
                    if (!cancelled.get() && !paused.get()) {
                        depotResumeNeeded = true
                        shortSummary = blocking.joinToString(", ") { (d, m) ->
                            "depot $d (journal ${journal[d] ?: "absent"} != $m)"
                        }
                        onDiskAtCompletion = SteamDepotDownloader.dirSizeBytes(installDir)
                    } else {
                        fail(appId, msg)
                    }
                } else if (completed.isEmpty()) {
                    // No genuinely-short depot remains, but NOTHING completed either — every selected
                    // depot was denied. The account owns none of this game: fail honestly rather than
                    // let "tolerate denied" turn a fully-unowned game into a false success.
                    completedNormally = true
                    val msg = "Steam denied access to every depot of this game (not owned on this account?)"
                    dlog("NOT OWNED: all ${specs.size} selected depot(s) denied — $msg")
                    BlSteamEngineLog.log("DL", "not-owned app=$appId all=${specs.size} denied")
                    fail(appId, msg)
                } else {
                    // At least one selected depot completed; any remaining depot was denied (not owned).
                    // Remember ALL denied depots as excluded so a later re-download/update never
                    // re-selects and re-denies them, then mark the game installed.
                    if (deniedSkipped.isNotEmpty()) {
                        val deniedIds = deniedSkipped.map { it.first }
                        deniedIds.forEach { dlog("Depot $it: skipped — not owned on this account (Steam denied the depot key)") }
                        try { SteamPrefs.setExcludedDlc(appId, excludedDlc + deniedIds) } catch (_: Throwable) {}
                        dlog("Recorded ${deniedIds.size} denied depot(s) as excluded so a re-download won't re-select them: ${deniedIds.joinToString(",")}")
                    }
                    completedNormally = true
                    val iTotal = installTotalRunning.get()
                    val dTotal = downloadTotalRunning.get()
                    val onDisk = SteamDepotDownloader.dirSizeBytes(installDir)
                    val finalInstall = maxOf(lastInstallDone.get(), onDisk)
                    dlog("=== Download complete: appId=$appId — ${completed.size} owned depot(s) validated " +
                            "against their manifests" +
                            (if (deniedSkipped.isNotEmpty()) ", ${deniedSkipped.size} denied depot(s) skipped" else "") +
                            "; on-disk ${fmt(onDisk)} ===")
                    BlSteamEngineLog.log("DL", "complete app=$appId depots=${completed.size} skipped=${deniedSkipped.size} onDisk=${fmt(onDisk)}")
                    emitProgress(iTotal, iTotal, dTotal, dTotal)
                    db.markInstalled(appId, installDir.absolutePath, if (finalInstall > 0L) finalInstall else iTotal)
                    // Success → the download is done; clear its steam_downloads row so nothing keeps
                    // rendering a stale "downloading" state. The game now lives in steam_games
                    // (is_installed=1) and is represented by the INSTALLED DownloadRegistry entry +
                    // the Library section below. Only clear on SUCCESS — paused/failed/cancelled keep
                    // their row (finishPaused/fail/finishCancelled) so Resume/retry still works.
                    db.deleteDownload(appId)
                    try { SteamGameUpdater.recordInstalledBuild(ctx, appId, installDir, selectedBranch) } catch (_: Throwable) {}
                    repo.emit("DownloadComplete:$appId")
                    DownloadRegistry.update(dmKey) {
                        it.copy(state = DownloadState.INSTALLED, pct = 100, installPath = installDir.absolutePath,
                            installDone = if (finalInstall > 0L) finalInstall else iTotal, installTotal = iTotal)
                    }
                    // Terminal success — the queue is advanced at the END of run() (after teardown).
                }
            }
        } catch (t: Throwable) {
            dlog("downloadApp threw: ${t.javaClass.simpleName}: ${t.message}")
            error = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            repo.setDownloadActive(false)
            SteamDepotDownloader.releaseDownloadWakelock()
            activeAppId = -1
            try { repo.refreshFgsStatus() } catch (_: Throwable) {}
            SteamDepotDownloader.activeDownloads.remove(appId)
            if (!completedNormally) {
                when {
                    paused.get() -> finishPaused(appId, db, lastInstallDone.get())
                    cancelled.get() -> finishCancelled(appId, db)
                    else -> {
                        if (attempt < MAX_SESSION_RETRIES && isRecoverable(error)) {
                            dlog("finally: recoverable failure on attempt ${attempt + 1} ('$error') — awaiting session recovery")
                            val backoff = RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.size - 1)]
                            try { Thread.sleep(backoff) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                            val ok = ensureSession(ctx) != null
                            dlog("finally: session recovery → $ok")
                            if (ok && !cancelled.get() && !paused.get()) retryAsResume = true
                        }
                        if (!retryAsResume) fail(appId, humanError(error))
                    }
                }
            }
            dlog("=== run() finished (appId=$appId) ===")
        }
        if (retryAsResume) {
            dlog("Retrying appId=$appId as resume (attempt ${attempt + 2})")
            SteamDepotDownloader.activeDownloads[appId] = Unit
            DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.DOWNLOADING) }
            run(appId, ctx, cancelled, paused, speedTier, debugLog, isResume = true, installRoot = null,
                verify = false, control = control, attempt = attempt + 1)
            return
        }

        // ── Layer 1 — AUTO-RESUME a genuinely-short depot (see MAX_DEPOT_RESUME_ATTEMPTS) ──
        // The engine said the pass succeeded but the journal disagrees, so nothing was marked
        // installed; re-enter as a resume and let the chunk-level re-check fetch what is missing.
        // Session recovery above takes precedence (different failure domain).
        if (depotResumeNeeded && !cancelled.get() && !paused.get()) {
            val curOnDisk = onDiskAtCompletion.takeIf { it > 0L } ?: SteamDepotDownloader.dirSizeBytes(installDir)
            val progressed = curOnDisk > resumeFloorBytes + MIN_RESUME_PROGRESS_BYTES
            when {
                resumeAttempt >= MAX_DEPOT_RESUME_ATTEMPTS -> {
                    dlog("Auto-resume exhausted after $MAX_DEPOT_RESUME_ATTEMPTS attempt(s) — still SHORT: $shortSummary")
                    fail(appId, "Download incomplete after $MAX_DEPOT_RESUME_ATTEMPTS resume attempts — please retry")
                }
                resumeAttempt > 0 && !progressed -> {
                    dlog("Auto-resume made NO forward progress (on-disk ${fmt(curOnDisk)} <= floor " +
                            "${fmt(resumeFloorBytes)} + ${fmt(MIN_RESUME_PROGRESS_BYTES)}) — a selected depot is " +
                            "unavailable (no key / dead CDN). Failing fast. SHORT: $shortSummary")
                    fail(appId, "Download stalled — a depot delivered no new data on resume; please retry")
                }
                else -> {
                    val backoff = DEPOT_RESUME_BACKOFF_MS[resumeAttempt.coerceAtMost(DEPOT_RESUME_BACKOFF_MS.size - 1)]
                    dlog("Auto-resume attempt ${resumeAttempt + 1}/$MAX_DEPOT_RESUME_ATTEMPTS in ${backoff}ms " +
                            "(on-disk floor ${fmt(curOnDisk)}) — re-fetching missing chunks for SHORT: $shortSummary")
                    // Nothing was marked installed, so just re-enter. Re-mark the appId active BEFORE
                    // the backoff sleep so the UI's stale-row detector doesn't flag the row mid-pause.
                    SteamDepotDownloader.activeDownloads[appId] = Unit
                    try { repo.database.markDownloadResuming(appId) } catch (_: Throwable) {}
                    DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.DOWNLOADING) }
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    // attempt=0: a short-depot resume gets a fresh session-recovery budget.
                    run(appId, ctx, cancelled, paused, speedTier, debugLog, isResume = true, installRoot = null,
                        verify = false, control = control, attempt = 0,
                        resumeAttempt = resumeAttempt + 1, resumeFloorBytes = curOnDisk)
                }
            }
        }

        // Worker done for good → free the queue slot and start the next queued download. Placed HERE,
        // after the finally's setDownloadActive(false)/wakelock teardown, so the next download never
        // overlaps this one's teardown. Idempotent — onActiveTerminal no-ops when this appId is no
        // longer the active slot (early-return terminals already advanced via finishPaused /
        // finishCancelled / emitFailed). The re-entry paths never reach here: retryAsResume returns
        // above, and a depot-resume re-enters run() synchronously (that inner pass advances; this
        // outer call then no-ops).
        DownloadQueue.onActiveTerminal(appId)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** A logged-on engine session, re-driving the saved-token logon (bounded) when needed. */
    private fun ensureSession(ctx: Context): BlSteamSession? {
        if (!BlSteamEngine.isLoggedOn()) {
            dlog("Engine not logged on — waiting for the session (re-logon from saved token)…")
            val r = SteamSessionManager.ensureSession(ctx, SESSION_WAIT_MS) { msg -> dlog("session: $msg") }
            dlog("ensureSession → $r")
        }
        val s = BlSteamEngine.session()
        return if (s != null && BlSteamEngine.isLoggedOn()) s else null
    }

    /**
     * `depots/<id>/manifests/<branch>/gid` from a fresh engine PICS read, for a non-public branch.
     * A password-protected branch carries `depots/<id>/encryptedmanifests/<branch>/gid` instead: the
     * access code the user verified on the game page (persisted by `checkBranchPassword`) is checked
     * again with Steam (`ClientCheckAppBetaPassword`) to obtain the branch's AES-256 key, and the gid
     * is decrypted with it (AES-256-ECB, PKCS7 — the DepotDownloader/GameNative scheme). Null when a
     * protected branch has no verified code or nothing resolved — the caller fails honestly instead of
     * silently downloading the public build.
     */
    private fun resolveBranchManifests(
        session: BlSteamSession, appId: Int, branch: String, depotIds: List<Int>, db: SteamDatabase,
    ): List<Pair<Int, Long>>? {
        val info = try { BlLibraryCrawler(session).fetchApps(listOf(appId)).firstOrNull()?.second } catch (t: Throwable) { null }
            ?: return null
        val depots = info.optJSONObject("depots") ?: return null
        val out = ArrayList<Pair<Int, Long>>()
        var branchKey: ByteArray? = null
        var keyResolved = false
        for (d in depotIds) {
            val depot = depots.optJSONObject(d.toString()) ?: continue
            val gid = depot.optJSONObject("manifests")?.optJSONObject(branch)?.let { m ->
                val g = m.optString("gid", ""); if (g.isEmpty()) m.optString("value", "") else g
            } ?: depot.optJSONObject("manifests")?.optString(branch, "")
            if (!gid.isNullOrEmpty()) {
                gid.toULongOrNull()?.toLong()?.let { out.add(d to it) }
                continue
            }
            val enc = depot.optJSONObject("encryptedmanifests")?.optJSONObject(branch) ?: continue
            val encGidHex = enc.optString("gid", "").ifEmpty { enc.optString("encrypted_gid_2", "") }
            if (encGidHex.isEmpty()) continue
            if (!keyResolved) {
                keyResolved = true
                branchKey = resolveBranchKey(session, appId, branch, db)
            }
            val key = branchKey ?: run {
                dlog("Branch '$branch' is password-protected and no verified access code unlocks it")
                return null
            }
            val manifestId = decryptBetaGid(encGidHex, key)
            if (manifestId == null) {
                dlog("Branch '$branch': could not decrypt the manifest gid for depot $d")
                continue
            }
            dlog("Branch '$branch': depot $d encrypted gid → manifest $manifestId")
            out.add(d to manifestId)
        }
        return out
    }

    /** The AES-256 key for [branch] from the persisted access code, re-verified with Steam. */
    private fun resolveBranchKey(session: BlSteamSession, appId: Int, branch: String, db: SteamDatabase): ByteArray? {
        val password = try { db.getUnlockedBranchPassword(appId, branch) } catch (_: Throwable) { null }
        if (password.isNullOrEmpty()) return null
        val r = session.checkAppBetaPassword(appId, password)
        if (r == null || r.eresult != 1) {
            dlog("CheckAppBetaPassword for branch '$branch' → " + (r?.eresult?.let { "eresult $it" } ?: "no reply"))
            return null
        }
        val hex = r.branchKeys[branch] ?: r.branchKeys.entries.firstOrNull { it.key.equals(branch, ignoreCase = true) }?.value
        if (hex.isNullOrEmpty()) { dlog("Access code does not unlock branch '$branch' (unlocks ${r.branchKeys.keys})"); return null }
        val key = SteamCloudBackend.unhex(hex)
        return if (key.size == 32) key else { dlog("Branch key for '$branch' has ${key.size} bytes, expected 32"); null }
    }

    /**
     * `encryptedmanifests/<branch>/gid` → manifest id: AES-256-ECB/PKCS7 decrypt of the hex blob with
     * the branch key, first 8 bytes little-endian (DepotDownloader `SymmetricDecryptECB` +
     * `BitConverter.ToUInt64`). Null on a padding/format error (wrong key).
     */
    internal fun decryptBetaGid(encGidHex: String, key: ByteArray): Long? {
        return try {
            val input = SteamCloudBackend.unhex(encGidHex)
            if (input.isEmpty() || input.size % 16 != 0) return null
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
            val plain = cipher.doFinal(input)
            if (plain.size < 8) return null
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (plain[i].toLong() and 0xFF)
            if (v == 0L) null else v
        } catch (_: Exception) {
            null
        }
    }

    /** depotId → manifest id recorded as installed in `<installDir>/.bl_depot/depot.config`. */
    internal fun journalInstalledManifests(installDir: File): Map<Int, Long> {
        val f = File(File(installDir, JOURNAL_DIR), "depot.config")
        if (!f.isFile) return emptyMap()
        return try {
            val ids = JSONObject(f.readText()).optJSONObject("installedManifestIDs") ?: return emptyMap()
            val out = HashMap<Int, Long>()
            for (k in ids.keys()) {
                val d = k.toIntOrNull() ?: continue
                val m = ids.optString(k, "").toULongOrNull()?.toLong() ?: continue
                out[d] = m
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "journal parse failed: ${t.message}")
            emptyMap()
        }
    }

    private fun deniedDepots(installDir: File): Set<Int> {
        val f = File(File(installDir, JOURNAL_DIR), "denied.depots")
        if (!f.isFile) return emptySet()
        return try { f.readLines().mapNotNull { it.trim().toIntOrNull() }.toSet() } catch (_: Throwable) { emptySet() }
    }

    private fun isRecoverable(error: String): Boolean {
        val e = error.lowercase()
        if (e == "cancelled") return false
        if (e.contains("depot key unavailable") && !e.contains("request code")) return true
        return e.contains("not connected") || e.contains("session closed") || e.contains("cdn server request failed") ||
                e.contains("manifest fetch failed") || e.contains("write failed") || e.contains("request code unavailable") ||
                e.contains("manifest parse failed") || e.contains("job timeout")
    }

    private fun humanError(error: String): String = when {
        error.isBlank() -> "Unknown error"
        error.contains("no entitled depots") -> "Steam denied access to every depot of this game (not owned on this account?)"
        error.contains("CDN server request failed") -> "Couldn't get a Steam content server — check the connection and retry"
        error.contains("not connected") || error.contains("session closed") -> "Steam session lost — retry in a moment"
        else -> error.removePrefix("download: ")
    }

    private fun fail(appId: Int, reason: String) {
        BlSteamEngineLog.log("DL", "FAILED app=$appId: $reason")
        SteamDepotDownloader.emitFailed(appId, reason)
        SteamDepotDownloader.activeDownloads.remove(appId)
    }

    private fun finishPaused(appId: Int, db: SteamDatabase, installDone: Long) {
        dlog("finally: paused — marking DL_PAUSED")
        db.markDownloadPaused(appId, installDone)
        SteamRepository.getInstance().emit("DownloadPaused:$appId")
        DownloadRegistry.update("${Store.STEAM}:$appId") { it.copy(state = DownloadState.PAUSED) }
        // Pausing the active download frees the slot → advance the queue.
        DownloadQueue.onActiveTerminal(appId)
    }

    private fun finishCancelled(appId: Int, db: SteamDatabase) {
        dlog("finally: cancelled — DownloadCancelled")
        db.deleteDownload(appId)
        SteamRepository.getInstance().emit("DownloadCancelled:$appId")
        val key = "${Store.STEAM}:$appId"
        DownloadRegistry.update(key) { it.copy(state = DownloadState.CANCELLED) }
        DownloadRegistry.remove(key)
        SteamDepotDownloader.activeDownloads.remove(appId)
        // Active slot freed → advance the queue.
        DownloadQueue.onActiveTerminal(appId)
    }

    private fun <T> runBounded(boundMs: Long, block: () -> T): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        Thread({
            try { result = block() } catch (t: Throwable) { Log.w(TAG, "bounded task errored: ${t.message}") }
            finally { latch.countDown() }
        }, "bl-depot-bounded").apply { isDaemon = true }.start()
        return if (latch.await(boundMs, TimeUnit.MILLISECONDS)) result else null
    }

    private fun fmt(bytes: Long) = SteamDepotDownloader.fmtSize(bytes)

    private fun dlog(msg: String) = SteamDepotDownloader.dlog(TAG, msg)
}
