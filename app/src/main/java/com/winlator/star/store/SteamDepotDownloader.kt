package com.winlator.star.store

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.winlator.star.BuildConfig
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Steam depot download engine — uses JavaSteam's built-in DepotDownloader.
 *
 * Replaces the hand-rolled HTTP approach. DepotDownloader handles:
 *   - manifest request codes (CM connection)
 *   - CDN auth tokens (CM connection)
 *   - depot key requests (CM connection)
 *   - chunk downloading via Ktor CIO HTTP
 *   - AES-ECB decryption + VZip/LZMA decompression
 */
object SteamDepotDownloader {

    private const val TAG = "SteamDepot"

    /** How many times a failed download will silently recover the Steam session and retry
     *  (as a resume) before the failure is surfaced to the user. Covers the ~1h CM-logoff case. */
    private const val MAX_SESSION_RETRIES = 2

    // -------------------------------------------------------------------------
    // Active download tracking — used by UI to detect stale DL_DOWNLOADING rows
    // -------------------------------------------------------------------------

    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Int, Unit>()

    /** True if a download for this appId is currently running in this process. */
    @JvmStatic fun isDownloading(appId: Int): Boolean = activeDownloads.containsKey(appId)

    // -------------------------------------------------------------------------
    // Partial wakelock — keeps the process/CPU alive for the duration of a download
    // -------------------------------------------------------------------------
    // ROOT-CAUSE churn fix: on this OEM device's aggressive task-killer the app process was killed
    // and restarted repeatedly mid-download (4 PIDs in 10 min); a restarted process re-logs-in while
    // the old process's Steam session is still briefly alive → Steam kicks one → LogonSessionReplaced
    // → download stuck at 0%. A PARTIAL_WAKE_LOCK held ONLY while a download is active keeps the CPU
    // (and thus the process) alive so the killer is far less likely to fire mid-download. One shared,
    // reference-counted lock so overlapping downloads acquire/release safely; acquired with a 6h
    // safety cap so a crash can never pin it forever.
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    /** Lazily create (once) and acquire the shared partial wakelock. Null/exception-safe: a device
     *  without POWER_SERVICE (universal in practice) must not break the download. */
    @Synchronized
    private fun acquireDownloadWakelock(ctx: Context) {
        try {
            var wl = wakeLock
            if (wl == null) {
                val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm == null) { dlog("WAKELOCK: POWER_SERVICE unavailable — continuing without it"); return }
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bannerlator:steam-download")
                wl.setReferenceCounted(true)   // multiple concurrent downloads acquire/release safely
                wakeLock = wl
            }
            wl.acquire(6L * 60L * 60L * 1000L)   // 6h cap — a crash can't pin the lock forever
            dlog("WAKELOCK: acquired (partial, held=${wl.isHeld})")
        } catch (t: Throwable) {
            dlog("WAKELOCK: acquire failed (${t.message}) — continuing without it")
        }
    }

    /** Release one acquire of the shared wakelock. Guarded: a reference-counted release throws if the
     *  count already hit zero, which is benign — we just want it dropped on every terminal path. */
    private fun releaseDownloadWakelock() {
        try {
            val wl = wakeLock ?: return
            if (wl.isHeld) { wl.release(); dlog("WAKELOCK: released (held=${wl.isHeld})") }
        } catch (t: Throwable) {
            dlog("WAKELOCK: release skipped (${t.message})")
        }
    }

    // -------------------------------------------------------------------------
    // Debug log — written to getExternalFilesDir/steam_debug.txt
    // -------------------------------------------------------------------------

    private var debugLogFile: File? = null
    val debugLogPath: String get() = debugLogFile?.absolutePath ?: "(not initialized)"

    private fun initDebugLog(ctx: Context, truncate: Boolean = true) {
        try {
            val dir = ctx.getExternalFilesDir(null)
            if (dir != null) {
                debugLogFile = File(dir, "steam_debug.txt")
                // On a session-recovery retry (truncate=false) keep the prior attempt's log so the
                // failure + recovery narrative survives instead of being wiped by the resume.
                BufferedWriter(FileWriter(debugLogFile!!, !truncate)).use { w ->
                    val hdr = if (truncate) "=== Steam DepotDownloader Debug Log (JavaSteam native) ==="
                              else "=== Retry attempt (session recovery) ==="
                    w.write("$hdr\n")
                    w.write("Engine: JavaSteam DepotDownloader (Ktor CIO)\n")
                    w.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
                }
                dlog("Debug log: ${debugLogFile!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create debug log: ${e.message}")
        }
    }

    /**
     * Append a session-state line (from SteamRepository's indicator) into the ACTIVE download log,
     * so a download's steam_debug.txt carries the connect/login/replaced context inline. No-op when
     * no download log is currently open — the persistent steam_session.txt is the always-on record.
     */
    @JvmStatic fun mirrorSessionLine(msg: String) {
        if (debugLogFile != null) dlog(msg)
    }

    private fun dlog(msg: String) {
        // Scrub username/email/token from EVERY line (incl. JavaSteam-bridge + stack traces, which
        // all funnel through here) — these files are shared for support and must never carry secrets.
        val safe = SteamLogRedactor.redact(msg)
        Log.i(TAG, safe)
        debugLogFile ?: return
        try {
            BufferedWriter(FileWriter(debugLogFile!!, true)).use { w ->
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                w.write("[$ts] $safe\n")
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // JavaSteam internal log bridge
    // -------------------------------------------------------------------------
    // JavaSteam routes ALL of its internal logging (TcpConnection send/recv,
    // SteamClient.postCallback, SteamApps.handleMsg PICS parse, AsyncJobManager
    // timeouts, SteamContent manifest-request-code, CDN lookups) through
    // LogManager.LOG_LISTENERS. The app never registered a listener, so every one
    // of those lines is silently discarded — which is why steam_debug.txt shows a
    // 10s gap with "no CM traffic" between onDownloadStarted and the AsyncJob
    // CancellationException. Wire a listener so the NEXT capture reveals whether the
    // CM request is actually written to the socket, whether any inbound frame is
    // read on the TcpConnection thread, and exactly where the manifest/app-info job
    // stalls. Installed once, forwards into the same steam_debug.txt the UI shares.
    private val jsLogWired = AtomicBoolean(false)

    private fun wireJavaSteamLog() {
        if (!jsLogWired.compareAndSet(false, true)) return
        LogManager.addListener(object : LogListener {
            override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
                dlog("[JS/${clazz.simpleName}] ${message ?: ""}")
                if (throwable != null) dlog("[JS/${clazz.simpleName}] ex: ${throwable.message}")
            }
            override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
                dlog("[JS-ERR/${clazz.simpleName}] ${message ?: ""}")
                if (throwable != null) dlogError("[JS-ERR/${clazz.simpleName}]", throwable)
            }
        })
    }

    private fun dlogError(msg: String, t: Throwable) {
        // Always-on breadcrumb: surface the error summary at WARN level regardless of verbose, so a
        // failed download is never totally silent even when the steam_debug.txt firehose is off.
        // (dlog re-redacts + writes the full stack to file only when verbose opened the log.)
        Log.w(TAG, "${SteamLogRedactor.redact("$msg: ${t.message}")}")
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        dlog("$msg: ${t.message}")
        dlog("Stack: $sw")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returned by installApp() / resumeApp() — provides independent cancel and pause controls. */
    class DownloadControl(val cancel: Runnable, val pause: Runnable)

    /** Java-compatible singleton accessor. */
    @JvmStatic fun getInstance(): SteamDepotDownloader = this

    /**
     * Start a fresh install. Returns a DownloadControl with cancel + pause Runnables.
     * @param speedTier download-speed tier key (8=Slow / 16=Medium / 24=Fast / 32=Blazing);
     *   fed to [DownloadSpeedConfig] to derive maxDownloads/maxDecompress/maxFileWrites.
     * @param debugLog when true (or in a debug build) writes the verbose steam_debug.txt firehose +
     *   JavaSteam-internal bridge for this download. Off = logcat-only; failures still leave a trace.
     */
    fun installApp(
        appId: Int,
        ctx: Context,
        speedTier: Int = DownloadSpeedConfig.DEFAULT_TIER,
        debugLog: Boolean = false,
    ): DownloadControl =
        buildControl(appId, ctx, speedTier, debugLog, isResume = false)

    /**
     * Resume a previously paused install. Keeps the existing DB row (bytes intact).
     * DepotDownloader will re-verify and skip already-written chunks where possible.
     */
    fun resumeApp(
        appId: Int,
        ctx: Context,
        speedTier: Int = DownloadSpeedConfig.DEFAULT_TIER,
        debugLog: Boolean = false,
    ): DownloadControl =
        buildControl(appId, ctx, speedTier, debugLog, isResume = true)

    private fun buildControl(appId: Int, ctx: Context, speedTier: Int, debugLog: Boolean, isResume: Boolean): DownloadControl {
        val cancelled     = AtomicBoolean(false)
        val paused        = AtomicBoolean(false)
        val downloaderRef = AtomicReference<DepotDownloader?>(null)

        // Built BEFORE the launch so the Download Manager registry entry (created inside
        // runInstall, once the game's name is loaded) can wire its pause/cancel handles
        // straight to these Runnables — the same controls the Steam detail page uses.
        val control = DownloadControl(
            cancel = Runnable {
                if (cancelled.compareAndSet(false, true)) {
                    paused.set(false)  // cancel overrides pause
                    dlog("Cancel requested for appId=$appId")
                    downloaderRef.get()?.let { try { it.close() } catch (_: Exception) {} }
                }
            },
            pause = Runnable {
                if (!cancelled.get() && paused.compareAndSet(false, true)) {
                    dlog("Pause requested for appId=$appId")
                    downloaderRef.get()?.let { try { it.close() } catch (_: Exception) {} }
                }
            }
        )

        CoroutineScope(Dispatchers.IO).launch {
            runInstall(appId, ctx, cancelled, paused, downloaderRef, speedTier, debugLog, isResume, control = control)
        }

        return control
    }

    // -------------------------------------------------------------------------
    // Core install logic
    // -------------------------------------------------------------------------

    private fun runInstall(
        appId: Int,
        ctx: Context,
        cancelled: AtomicBoolean,
        paused: AtomicBoolean,
        downloaderRef: AtomicReference<DepotDownloader?>,
        speedTier: Int = DownloadSpeedConfig.DEFAULT_TIER,
        debugLog: Boolean = false,
        isResume: Boolean = false,
        attempt: Int = 0,
        control: DownloadControl? = null,
    ) {
        // One gate for all verbose diagnostics: on in debug builds, or when the user ticked
        // "Log debug session" for this download. Off ⇒ steam_debug.txt is never created (no 4 MB
        // firehose) and the JavaSteam bridge isn't wired; logcat + the always-on steam_session.txt
        // still record enough that a failure is never totally silent (see dlogError/emitFailed).
        val verbose = BuildConfig.DEBUG || debugLog
        activeDownloads[appId] = Unit
        if (verbose) {
            initDebugLog(ctx, truncate = attempt == 0)
            wireJavaSteamLog()   // surface JavaSteam CM/CDN internals into steam_debug.txt
        }
        dlog("=== Starting install: appId=$appId (verbose=$verbose) ===")

        val repo = SteamRepository.getInstance()
        val steamClient = repo.steamClient
        if (steamClient == null) {
            dlog("FAIL: SteamClient is null — not connected to Steam")
            emitFailed(appId, "Not connected to Steam")
            return
        }
        dlog("SteamClient: connected=${repo.isConnected}, loggedIn=${repo.isLoggedIn}")

        // Steam connections cycle, and the re-logon after a reconnect is async — so we can land
        // here connected but not yet logged in (the cached license list hides it). Starting a
        // depot download without a live session makes the manifest job time out → the user sees a
        // bogus "Unknown error". Wait for the session to come back (re-logging-on if needed).
        if (!repo.isLoggedIn) {
            dlog("Not logged in — waiting for session (re-logging-on from saved token if available)…")
            val ok = repo.ensureLoggedIn(15_000L)
            dlog("ensureLoggedIn → $ok (loggedIn=${repo.isLoggedIn})")
            if (!ok) {
                // Surface WHY into the debug file the UI points the user at — otherwise the only
                // record of the logon/logoff EResult (e.g. LogonSessionReplaced, InvalidPassword)
                // is in logcat, which the user can't reach.
                dlog("Session status at failure: ${repo.lastSessionStatus}")
                emitFailed(appId, "Steam session not ready — sign in again or retry in a moment")
                return
            }
        }

        val licenses = repo.getLicenses()
        dlog("Licenses: ${licenses.size} entries")
        if (licenses.isEmpty()) {
            dlog("WARNING: license list is empty — DepotDownloader may not find any depots")
        }

        val db = repo.database
        val row = db.getGame(appId)
        if (row == null) {
            dlog("FAIL: appId=$appId not found in database")
            emitFailed(appId, "Game not found in database")
            return
        }
        dlog("Game: name='${row.name}' type=${row.type} sizeBytes=${row.sizeBytes}")

        // Sanitise game name for directory usage
        val safeName = row.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val installDir = File(File(ctx.filesDir, "imagefs/steam_games"), safeName)
        dlog("Install dir: ${installDir.absolutePath}")

        // Denominators come from the SELECTED-depot sums computed at library sync
        // (SteamRepository now filters out other-OS / non-english / undownloadable depots):
        //   installTotal  = uncompressed bytes written to disk        (row.sizeBytes)
        //   downloadTotal = compressed bytes fetched over the network (repo in-memory cache)
        // Prefer the manifest-TRUE install size (DepotSizeResolver) when it's been resolved — it's the
        // real "size on disk" total, so the progress bar denominator is correct from the first chunk
        // (the b8e9e5b grow-the-denominator band-aid then only ever acts as a backstop). Fall back to
        // the PICS estimate, then to the per-depot PICS sum. A resolved real size counts as hasPicsSize
        // (a valid known total → no need to back-calculate from chunk fractions).
        val realGameSize = try { db.getGameRealSize(appId) } catch (_: Throwable) { 0L }
        val hasPicsSize: Boolean
        val installTotal: Long = when {
            realGameSize  > 0L -> { hasPicsSize = true;  realGameSize }
            row.sizeBytes > 0L -> { hasPicsSize = true;  row.sizeBytes }
            else               -> { hasPicsSize = false
                db.getDepotManifests(appId).sumOf { it.sizeBytes }.let { if (it > 0L) it else 1L } }
        }
        // Compressed (network) total for the download bar. In-memory cache populated by
        // library sync; on a cache miss (resume in a fresh process without a re-sync) fall
        // back to the install total so the bar still has a sane denominator.
        val cachedDownload = repo.getSelectedDownloadSize(appId)
        val downloadTotalSeed: Long = if (cachedDownload > 0L) cachedDownload else installTotal
        dlog("Denominators: install=${fmtSize(installTotal)} download=${fmtSize(downloadTotalSeed)} " +
                "(hasPicsSize=$hasPicsSize, cachedDownload=$cachedDownload)")

        // Queue in DB so UI shows progress (skip reset on resume — keep existing bytes).
        // The DB tracks the INSTALL (uncompressed) bytes/total; compressed is UI-only.
        if (isResume) {
            db.markDownloadResuming(appId)
        } else {
            db.queueDownload(appId, installTotal, installDir.absolutePath)
        }

        // Per-depot cumulative accumulators. DepotDownloader reports cumulative bytes PER
        // DEPOT; a multi-depot game needs these SUMMED or the bar tracks only the largest
        // single depot and stalls partway. installByDepot=uncompressed, downloadByDepot=compressed.
        val installByDepot  = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val downloadByDepot = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        // Resume seeding: the DB persists only install bytes. Seed both bars so neither
        // restarts at 0. The download (compressed) seed is derived from the install fraction
        // since compressed progress isn't persisted — a reasonable approximation.
        val persistedInstall = if (isResume) (db.getDownload(appId)?.bytesDownloaded ?: 0L) else 0L
        val installBase  = persistedInstall
        val downloadBase = if (isResume && installTotal > 0L)
            (persistedInstall.toDouble() / installTotal * downloadTotalSeed).toLong() else 0L
        if (isResume) dlog("Resume seed: install=${fmtSize(installBase)} " +
                "download=${fmtSize(downloadBase)} (download from install-fraction)")

        // Latest aggregate install bytes — read by the pause/complete paths in finally.
        val lastInstallDone = AtomicLong(installBase)
        // Running denominators — grown from chunk data if the seed was too low.
        val installTotalRunning  = AtomicLong(installTotal)
        val downloadTotalRunning = AtomicLong(downloadTotalSeed)

        // Cross-store Download Manager: publish this download as a live entry into the
        // store-agnostic registry (Phase 2). Purely additive — the DownloadProgress:/
        // DownloadComplete: emits below are untouched and still drive the detail page.
        // pause/cancel ride the same DownloadControl the detail page uses; cover is the
        // appId (GameCoverArt resolves Steam art by appId). On a session-recovery retry
        // (attempt>0) the entry already exists, so just flip it back to DOWNLOADING
        // rather than resetting its byte counters to the seed.
        val dmKey = "${Store.STEAM}:$appId"
        if (attempt == 0 && DownloadRegistry.get(dmKey) == null) {
            DownloadRegistry.upsert(DownloadEntry(
                store = Store.STEAM,
                id = appId.toString(),
                name = row.name,
                cover = appId.toString(),
                state = DownloadState.DOWNLOADING,
                installDone = installBase,
                installTotal = installTotal,
                downloadDone = downloadBase,
                downloadTotal = downloadTotalSeed,
                supportsPause = true,
                pause = control?.let { c -> { c.pause.run() } },
                cancel = control?.let { c -> { c.cancel.run() } },
            ))
        } else {
            DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.DOWNLOADING) }
        }

        // Build the 4-field progress event: install pair + download pair.
        fun emitProgress(iDone: Long, iTotal: Long, dDone: Long, dTotal: Long) {
            repo.emit("DownloadProgress:$appId:$iDone:$iTotal:$dDone:$dTotal")
        }

        // Derive the three pipeline-stage caps from CPU cores × the selected tier's ratios
        // (see DownloadSpeedConfig, a faithful port of GameNative on this same engine). The 7th
        // ctor arg is maxFileWrites (NOT progressUpdateInterval — that's a hardcoded 500L inside
        // the engine); passing a large value there let up to ~100 chunks hold multi-MB decompressed
        // buffers at once and OOM'd the 256 MB heap. maxDownloads stays high (network parallelism is
        // cheap on heap); decompress + file-write are the heap drivers and are kept bounded.
        val speedConfig   = DownloadSpeedConfig(speedTier)
        val cores         = speedConfig.cpuCores
        val maxDownloads  = speedConfig.maxDownloads
        val maxDecompress = speedConfig.maxDecompress
        val maxFileWrites = speedConfig.maxFileWrites
        dlog("Constructing DepotDownloader(tier=$speedTier, cores=$cores, maxDownloads=$maxDownloads, " +
                "maxDecompress=$maxDecompress, maxFileWrites=$maxFileWrites, androidEmulation=true, debug=$verbose)")
        val downloader = try {
            DepotDownloader(
                steamClient,
                licenses,
                verbose,       // debug (gated: BuildConfig.DEBUG || user "Log debug session")
                false,         // useLanCache
                maxDownloads,  // maxDownloads (cores × tier download ratio)
                maxDecompress, // maxDecompress (cores × tier decompress ratio — bounds the big buffers)
                maxFileWrites, // maxFileWrites (tied to maxDecompress; was 100, mislabeled "progressUpdateInterval")
                true,          // androidEmulation
                null,          // parentJob
            )
        } catch (e: Exception) {
            dlog("FAIL: DepotDownloader constructor threw")
            dlogError("DepotDownloader()", e)
            emitFailed(appId, "DepotDownloader init failed: ${e.message}")
            return
        }
        dlog("DepotDownloader constructed OK")
        downloaderRef.set(downloader)

        // Captured by onDownloadFailed; the terminal decision (retry vs surface to user) is made
        // in the finally block so a mid-download CM session loss can be recovered transparently.
        var failure: Throwable? = null

        // "We got real depot data" signal — set once the first chunk/file lands (i.e. we're past the
        // appInfo/manifest/depot-key CM stage). Drives the finally's retry strategy: NO progress ==
        // the 0%/60s appinfo-no-reply signature, which means the session is likely ONLINE-but-stale,
        // so the retry must force a genuinely FRESH session (reconnectAndRelogin) rather than a
        // plain ensureLoggedIn that would short-circuit true on the same dead session.
        val gotDepotKeyOrChunk = AtomicBoolean(false)
        // Throttle FGS notification updates to whole-percent changes (chunks fire far too often).
        val lastNotifiedPct = AtomicInteger(-1)

        downloader.addListener(object : IDownloadListener {
            override fun onDownloadStarted(item: DownloadItem) {
                dlog("onDownloadStarted: appId=${item.appId}")
                emitProgress(installBase, installTotalRunning.get(), downloadBase, downloadTotalRunning.get())
            }

            override fun onStatusUpdate(message: String) {
                dlog("Status: $message")
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                gotDepotKeyOrChunk.set(true)   // a completed file means we're well past the appInfo stage
                val pct = (depotPercentComplete * 100).toInt()
                dlog("File done: depot=$depotId pct=$pct% file=$fileName")
            }

            override fun onChunkCompleted(
                depotId: Int,
                depotPercentComplete: Float,
                compressedBytes: Long,
                uncompressedBytes: Long,
            ) {
                gotDepotKeyOrChunk.set(true)   // first chunk = past appInfo/manifest/depot-key; NOT a 0% stall
                // Both params are cumulative PER DEPOT — keep the latest (monotonic) per
                // depot, then SUM across depots so multi-depot games climb to the true total.
                if (uncompressedBytes > (installByDepot[depotId] ?: 0L)) installByDepot[depotId] = uncompressedBytes
                if (compressedBytes   > (downloadByDepot[depotId] ?: 0L)) downloadByDepot[depotId] = compressedBytes

                // maxOf(base, sessionSum): fresh downloads use the sum directly (base=0);
                // resumes never drop below the persisted floor.
                val installDone  = maxOf(installBase,  installByDepot.values.sum())
                val downloadDone = maxOf(downloadBase, downloadByDepot.values.sum())
                lastInstallDone.set(installDone)

                // Only back-calculate the install denominator when PICS gave no valid size —
                // avoids spiking to inflated values on large depots that sit near 0% for a while.
                if (!hasPicsSize && depotPercentComplete > 0.05f && installDone > 0L) {
                    val implied = (installDone.toDouble() / depotPercentComplete).toLong()
                    if (implied > installTotalRunning.get()) installTotalRunning.set(implied)
                }
                var iTotal = installTotalRunning.get()
                var dTotal = downloadTotalRunning.get()
                // Never let either bar exceed 100% — grow the denominator if the size
                // estimate was low (PICS can under-report real install size; cache miss on resume).
                if (installDone  > iTotal) { installTotalRunning.set(installDone);  iTotal = installDone }
                if (downloadDone > dTotal) { downloadTotalRunning.set(downloadDone); dTotal = downloadDone }

                // Overall % is the aggregate install fraction (what's actually on disk),
                // clamped to 99 — 100% is reserved for onDownloadCompleted.
                val pct = if (iTotal > 0L) minOf((installDone * 100 / iTotal).toInt(), 99) else 0
                dlog("Chunk: depot=$depotId $pct% install=${fmtSize(installDone)}/${fmtSize(iTotal)} " +
                        "download=${fmtSize(downloadDone)}/${fmtSize(dTotal)}")
                emitProgress(installDone, iTotal, downloadDone, dTotal)
                db.updateDownloadProgress(appId, installDone)
                // Mirror the exact same figures into the Download Manager registry (no recompute).
                DownloadRegistry.update(dmKey) {
                    it.copy(
                        state = DownloadState.DOWNLOADING,
                        pct = pct,
                        installDone = installDone,
                        installTotal = iTotal,
                        downloadDone = downloadDone,
                        downloadTotal = dTotal,
                    )
                }

                // FGS notification: honest "Downloading <game> — N%", throttled to whole-percent
                // changes so chunk spam doesn't thrash the notification. Reverted to the connection
                // status in the finally (repo.refreshFgsStatus()). Static no-op if the FGS is down.
                if (lastNotifiedPct.getAndSet(pct) != pct) {
                    try { SteamForegroundService.setStatusText("Downloading ${row.name} — $pct%") }
                    catch (_: Throwable) {}
                }
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                // The engine's per-depot byte args here UNDER-report (observed: a ~575 MB depot logged
                // as 47.5 MB). Prefer our own cumulative per-depot tracking from onChunkCompleted, which
                // is accurate (it drives the progress bar + DB); fall back to the engine arg only if we
                // tracked nothing for this depot.
                val u = maxOf(uncompressedBytes, installByDepot[depotId] ?: 0L)
                val c = maxOf(compressedBytes,   downloadByDepot[depotId] ?: 0L)
                dlog("Depot $depotId complete: ${fmtSize(u)} uncompressed / ${fmtSize(c)} compressed")
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                dlog("=== Download complete: appId=${item.appId} ===")
                val iTotal = installTotalRunning.get()
                val dTotal = downloadTotalRunning.get()
                val finalInstall = maxOf(lastInstallDone.get(), installByDepot.values.sum())
                // Accurate grand total from our own tracking (the per-depot engine args under-report).
                dlog("Total downloaded: ${fmtSize(finalInstall)} uncompressed / " +
                        "${fmtSize(downloadByDepot.values.sum())} compressed across ${installByDepot.size} depot(s)")

                // FALSE-COMPLETE GUARD: after an interrupted/polluted install, DepotDownloader can
                // declare "complete" having written almost nothing — leftover partial files + stale
                // .DepotDownloader state make it skip the main depot (proven: HL2 marked Installed at
                // 405MB of 8.4GB). If <90% of the EXPECTED size landed on disk, this is NOT a genuine
                // completion: refuse to markInstalled and surface a retryable failure.
                //
                // EXPECTED SIZE = the sum of the SELECTED depots' MANIFEST-declared uncompressed sizes
                // (DepotSizeResolver), NOT the PICS estimate. PICS both over-reports (appId 313830:
                // 181 MB PICS vs 130 MB real content → a complete install was wrongly rejected as
                // "incomplete") and under-reports. cached() is a pure DB read (no CM traffic here). We
                // fall back to the PICS estimate only when the real size is UNRESOLVED — and even then
                // we relax, never tighten: if every selected depot delivered bytes (no skipped depot),
                // trust the engine's completion rather than reject on the unreliable PICS number.
                val realExpected = try {
                    DepotSizeResolver.cached(appId)?.takeIf { it.complete && it.realInstallBytes > 0L }?.realInstallBytes
                } catch (_: Throwable) { null }

                if (realExpected != null) {
                    // True size known → authoritative 90% check. 313830 → 130/130 passes; a real skip
                    // (HL2 405 MB of 10.66 GB) still fails.
                    if (finalInstall < (realExpected * 90L / 100L)) {
                        dlog("INCOMPLETE: only ${fmtSize(finalInstall)} of ${fmtSize(realExpected)} " +
                                "(manifest-true) on disk (<90%) — refusing to mark installed")
                        emitFailed(appId, "Download incomplete (${fmtSize(finalInstall)}/${fmtSize(realExpected)}) — please retry")
                        return
                    }
                    dlog("Complete: ${fmtSize(finalInstall)} of ${fmtSize(realExpected)} manifest-true (≥90%)")
                } else if (iTotal > 0L && finalInstall < (iTotal * 90L / 100L)) {
                    // Real size unresolved → today's PICS guard, RELAXED (never stricter): a genuine
                    // truncation skips a whole depot, which shows up as a SELECTED depot with zero bytes
                    // delivered. If every selected depot delivered something, the shortfall vs the PICS
                    // estimate is a PICS over-report, not a skip — trust the engine's completion.
                    val selectedDepots: List<Int> = try { db.getDepotManifests(appId).map { it.depotId } }
                                         catch (_: Throwable) { emptyList() }
                    val everyDepotDelivered = selectedDepots.isNotEmpty() &&
                            selectedDepots.all { (installByDepot[it] ?: 0L) > 0L }
                    if (!everyDepotDelivered) {
                        dlog("INCOMPLETE: only ${fmtSize(finalInstall)} of ${fmtSize(iTotal)} PICS-est on disk " +
                                "(<90%) and a selected depot delivered nothing — refusing to mark installed")
                        emitFailed(appId, "Download incomplete (${fmtSize(finalInstall)}/${fmtSize(iTotal)}) — please retry")
                        return
                    }
                    dlog("Complete: ${fmtSize(finalInstall)} < 90% of PICS-est ${fmtSize(iTotal)} but all " +
                            "${selectedDepots.size} selected depot(s) delivered — trusting completion (PICS over-report)")
                }

                // Both bars reach 100% before switching to installed state.
                emitProgress(iTotal, iTotal, dTotal, dTotal)
                db.markInstalled(appId, installDir.absolutePath, if (finalInstall > 0L) finalInstall else iTotal)
                repo.emit("DownloadComplete:$appId")
                // Terminal success → INSTALLED. The registry persists INSTALLED rows to the
                // durable library, so this game survives process death in the Library section.
                DownloadRegistry.update(dmKey) {
                    it.copy(
                        state = DownloadState.INSTALLED,
                        pct = 100,
                        installPath = installDir.absolutePath,
                        installDone = if (finalInstall > 0L) finalInstall else iTotal,
                        installTotal = iTotal,
                    )
                }
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                if (cancelled.get()) {
                    // Cancel path: finally block guarantees DownloadCancelled is emitted.
                    dlog("=== Download cancelled by user: appId=${item.appId} ===")
                } else {
                    dlog("=== Download FAILED: appId=${item.appId} ===")
                    dlogError("onDownloadFailed", error)
                    // Defer to finally: if the CM session was lost mid-download (the QR ~1h
                    // logoff case), we recover it and retry once as a resume instead of
                    // surfacing a bogus failure to the user.
                    failure = error
                }
            }
        })

        val item = AppItem(
            appId = appId,
            installDirectory = installDir.absolutePath,
            branch = "public",
            // Explicitly request Windows depots — don't let Util.getSteamOS() guess,
            // since androidEmulation only works if IS_OS_ANDROID is true at runtime.
            os = "windows",
            // Skip arch filtering — we always want the game's Windows depots regardless
            // of what os.arch returns on this Android device (arm64, aarch64, armv8l, etc.).
            // Wine/Box64 handles x86_64 translation; arch mismatch would filter all depots.
            downloadAllArchs = true,
        )
        dlog("Adding AppItem: appId=${item.appId} branch=${item.branch} dir=${item.installDirectory}")
        downloader.add(item)
        downloader.finishAdding()

        dlog("Items added, download auto-starts via getCompletion()")

        // Pause the background library PICS sync while THIS download owns the CM connection. A
        // full-library appinfo request (~372 apps in one shot) monopolises the shared TcpConnection
        // and starves this download's own appinfo AsyncJob → 60s CancellationException @0%. The
        // in-flight library batch (≤25 apps) drains fast; SteamRepository resumes the sync from our
        // finally block. Cleared for every terminal path (success / fail / cancel / exception).
        repo.setDownloadActive(true)

        // Hold a partial wakelock for the CM+download work so the OEM task-killer can't kill the
        // process mid-download (the LogonSessionReplaced churn root cause). Released in the finally
        // next to setDownloadActive(false) — covers every terminal path (success/fail/cancel/exception).
        acquireDownloadWakelock(ctx)

        // --- CM AsyncJob timeout watchdog (10s default -> 60s) --------------------------------
        // The download's internal CM jobs (appinfo/manifest/depot-key/CDN-auth) time out at the
        // hard-coded 10s AsyncJob default with no exposed knob; the only reachable lever is the live
        // job map (see SteamRepository.bumpPendingJobTimeouts). Poll every 1s (matching AsyncJobManager's
        // own timeout tick) so each newly-registered job is stretched before the 10s deadline. This lets
        // a merely-LATE reply (transient netThread head-of-line block) still land; the JavaSteam
        // LogListener then shows whether the reply arrives late (HOL) or never (real no-reply @60s).
        val jobWatchdog = AtomicBoolean(true)
        Thread({
            while (jobWatchdog.get()) {
                try { repo.bumpPendingJobTimeouts(60_000L) } catch (_: Throwable) {}
                try { Thread.sleep(1_000L) } catch (_: InterruptedException) { break }
            }
        }, "SteamJobTimeoutWatchdog").apply { isDaemon = true; start() }

        dlog("Blocking on getCompletion().get()...")
        var completedNormally = false
        var retryAsResume = false
        try {
            downloader.getCompletion().get()
            completedNormally = true
            dlog("getCompletion() returned — download finished")
        } catch (e: ExecutionException) {
            dlog("getCompletion() ExecutionException: ${e.cause?.message ?: e.message}")
            dlogError("ExecutionException.cause", e.cause ?: e)
        } catch (e: InterruptedException) {
            dlog("getCompletion() interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            dlog("getCompletion() unexpected exception: ${e.message}")
            dlogError("getCompletion unexpected", e)
        } finally {
            jobWatchdog.set(false)   // stop the AsyncJob-timeout watchdog for this download
            repo.setDownloadActive(false)   // release the CM; resumes any parked library PICS sync
            releaseDownloadWakelock()       // drop the partial wakelock on EVERY terminal path
            try { repo.refreshFgsStatus() } catch (_: Throwable) {}  // revert "Downloading … N%" → connection status
            activeDownloads.remove(appId)
            dlog("Closing DepotDownloader")
            try { downloader.close() } catch (_: Exception) {}
            downloaderRef.set(null)
            if (!completedNormally) {
                when {
                    paused.get() -> {
                        // Pause path: keep files + DB row, just mark paused
                        dlog("finally: paused=true — marking DL_PAUSED")
                        db.markDownloadPaused(appId, lastInstallDone.get())
                        repo.emit("DownloadPaused:$appId")
                        DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.PAUSED) }
                    }
                    cancelled.get() -> {
                        // Cancel path: delete files + row
                        dlog("finally: cancelled=true — ensuring DownloadCancelled emitted")
                        db.deleteDownload(appId)
                        repo.emit("DownloadCancelled:$appId")
                        // Files + DB row are gone; drop the registry row too (mark then remove so
                        // any collector sees the CANCELLED transition before it disappears).
                        DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.CANCELLED) }
                        DownloadRegistry.remove(dmKey)
                    }
                    else -> {
                        // Genuine failure. Before surfacing it, give the session a chance to come back
                        // and retry this download once as a resume (so the in-flight download is not aborted).
                        if (attempt < MAX_SESSION_RETRIES) {
                            val progressed = gotDepotKeyOrChunk.get()
                            // Force a genuinely FRESH session (tear down + relogin) when either:
                            //   (a) we made NO depot progress — the 0%/60s appinfo-no-reply signature, meaning
                            //       the session is likely ONLINE-but-stale; a plain ensureLoggedIn would
                            //       short-circuit true on that SAME dead session and fail again; or
                            //   (b) this is already a retry (attempt > 0) — the earlier lightweight recovery
                            //       didn't stick, so escalate to a full reconnect.
                            // Otherwise (real bytes downloaded, THEN the session was lost — the ~1h involuntary
                            // CM-logoff case) keep the existing lightweight ensureLoggedIn recovery that works.
                            val forceFresh = !progressed || attempt > 0
                            dlog("finally: failure on attempt ${attempt + 1} " +
                                    "(progressed=$progressed, forceFresh=$forceFresh) — awaiting session recovery")
                            val ok = if (forceFresh) repo.reconnectAndRelogin(30_000L)
                                     else repo.ensureLoggedIn(30_000L)
                            dlog("finally: post-failure ${if (forceFresh) "reconnectAndRelogin" else "ensureLoggedIn"}" +
                                    " → $ok (loggedIn=${repo.isLoggedIn})")
                            if (ok && !cancelled.get() && !paused.get()) {
                                dlog("finally: session recovered — will retry as resume")
                                retryAsResume = true
                            }
                        }
                        if (!retryAsResume) {
                            emitFailed(appId, failure?.message ?: "Unknown error")
                        }
                    }
                }
            }
            dlog("=== runInstall() finished ===")
        }

        // Outside the try/finally so the failed attempt is fully torn down first. Bounded by
        // MAX_SESSION_RETRIES; re-enters as a resume so already-downloaded files are reused.
        if (retryAsResume) {
            dlog("Retrying install for appId=$appId (attempt ${attempt + 1} → ${attempt + 2}) as resume")
            runInstall(appId, ctx, cancelled, paused, downloaderRef, speedTier, debugLog,
                    isResume = true, attempt = attempt + 1, control = control)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun emitFailed(appId: Int, reason: String) {
        SteamRepository.getInstance().database.markDownloadFailed(appId, reason)
        SteamRepository.getInstance().emit("DownloadFailed:$appId:$reason")
        Log.e(TAG, "DownloadFailed $appId: $reason")
        // Central failure sink — covers the pre-flight checks, the false-complete guard, and
        // the finally's genuine-failure path. No-op if no registry entry exists yet (early
        // pre-flight failures fire before the entry is upserted).
        DownloadRegistry.update("${Store.STEAM}:$appId") { it.copy(state = DownloadState.FAILED, error = reason) }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }
}
