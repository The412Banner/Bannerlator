package com.winlator.star.store

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.winlator.star.BuildConfig
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
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
    // Per-depot completion / auto-resume tuning (Layer 1 + Layer 2)
    // -------------------------------------------------------------------------
    /** Auto-resume cap for a genuinely-SHORT selected depot — one that the per-depot manifest verify
     *  found incomplete even though the engine reported "download complete" (Dead Cells' 588651 stopping
     *  at ~50%). Distinct from MAX_SESSION_RETRIES (that recovers a lost CM session; this re-fetches
     *  missing depot chunks). Bounded + backoff + no-progress fail-fast below so it can never loop. */
    private const val MAX_DEPOT_RESUME_ATTEMPTS = 3
    /** Backoff before each short-depot auto-resume (ms), indexed by attempt; the last value repeats. */
    private val DEPOT_RESUME_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L)
    /** A short-depot resume that grows the on-disk footprint by less than this made no forward progress
     *  → the depot is genuinely unavailable (no key / dead CDN, like an unowned DLC depot) → fail fast. */
    private const val MIN_RESUME_PROGRESS_BYTES = 1_048_576L
    /** Engine manifest-relative depot completion (sizeDownloaded/completeDownloadSize, from
     *  onChunkCompleted) at/above which a depot's needed file set is considered fully delivered. */
    private const val DEPOT_PCT_COMPLETE = 0.999f
    /** On-disk-footprint vs manifest-true-size threshold (percent) for the verify/re-check and
     *  overlapping-depot completeness paths (matches the prior guard's 90%). */
    private const val COMPLETE_PCT = 90L

    /**
     * Overlapping-depot fix. A few Steam apps ship two+ content depots that carry the SAME file
     * PATHS but DIFFERENT content — one maintained, one a stale leftover. JavaSteam's DepotDownloader
     * de-dupes files by path across an app's depots; the first-processed depot wins, so a stale twin
     * can pre-empt the maintained file and land an OUTDATED copy on disk (the engine then reports
     * "downloaded 0 files" for the maintained depot, hiding it).
     *
     * Map of appId → depotIds to DROP from the download, so only the maintained depot is pulled
     * (mirrors what GameNative fetches). Verified case:
     *   993090 Lossless Scaling → drop 993092: its Lossless.dll lags depot 993091 by a build
     *   (993091 got the +1.99 MiB update in build 19476814 → 7.17 MiB; 993092 still ships the older
     *   5.18 MiB DLL). Keeping 993092 makes lsfg-vk run an outdated frame-gen DLL. 993091 alone is a
     *   complete install (315 MB), so dropping the twin loses nothing.
     */
    private val STALE_DUPLICATE_DEPOTS: Map<Int, Set<Int>> = mapOf(
        993090 to setOf(993092),
    )

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
     *   fed to [DownloadSpeedConfig] to derive maxDownloads/maxDecompress.
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
        // Layer 1 short-depot auto-resume state (separate failure domain from `attempt`/session-recovery):
        //   resumeAttempt   — how many short-depot resumes have already run for this install.
        //   resumeFloorBytes — on-disk footprint captured at the START of the previous resume, so the
        //                      tail can require forward progress and fail-fast on a stalled (dead) depot.
        resumeAttempt: Int = 0,
        resumeFloorBytes: Long = 0L,
    ) {
        // One gate for all verbose diagnostics: on in debug builds, or when the user ticked
        // "Log debug session" for this download. Off ⇒ steam_debug.txt is never created (no 4 MB
        // firehose) and the JavaSteam bridge isn't wired; logcat + the always-on steam_session.txt
        // still record enough that a failure is never totally silent (see dlogError/emitFailed).
        val verbose = BuildConfig.DEBUG || debugLog
        activeDownloads[appId] = Unit
        if (verbose) {
            // Truncate only on the very first attempt of a fresh install — a session-recovery retry
            // (attempt>0) OR a short-depot auto-resume (resumeAttempt>0) appends so the failure +
            // recovery/resume narrative survives in steam_debug.txt instead of being wiped.
            initDebugLog(ctx, truncate = attempt == 0 && resumeAttempt == 0)
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
        try {
            val dlcNames = db.getIncludedDlcNames(appId)
            if (dlcNames.isNotEmpty()) dlog("Including owned DLC: ${dlcNames.joinToString(", ")}")
        } catch (_: Throwable) {}

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
        // Exclusion-aware denominators: sum only the depots this download will actually pull (drop the
        // DLC the user opted out of) so the progress bar tracks the real, reduced download instead of
        // stalling at ~85% then jumping to done.
        val excludedForDenom = try { SteamPrefs.getExcludedDlc(appId) } catch (_: Throwable) { emptySet() }
        val keptDepotRows = try { db.getDepotManifests(appId).filter { it.depotId !in excludedForDenom } }
                            catch (_: Throwable) { emptyList() }
        val keptReal = if (keptDepotRows.isNotEmpty() && keptDepotRows.all { it.realSizeBytes > 0L })
                           keptDepotRows.sumOf { it.realSizeBytes } else 0L
        val keptPics = keptDepotRows.sumOf { it.sizeBytes }
        val realGameSize = try { db.getGameRealSize(appId) } catch (_: Throwable) { 0L }
        val hasPicsSize: Boolean
        val installTotal: Long = when {
            keptReal > 0L      -> { hasPicsSize = true;  keptReal }          // manifest-true, exclusion-aware
            keptPics > 0L      -> { hasPicsSize = true;  keptPics }          // PICS sum, exclusion-aware
            realGameSize  > 0L -> { hasPicsSize = true;  realGameSize }      // fallbacks (no depot rows)
            row.sizeBytes > 0L -> { hasPicsSize = true;  row.sizeBytes }
            else               -> { hasPicsSize = false; 1L }
        }
        // Compressed (network) total for the download bar: kept depots' resolved compressed sizes when
        // available (exclusion-aware), else the app-level PICS download cache, else the install total.
        val keptDownload = if (keptDepotRows.isNotEmpty() && keptDepotRows.all { it.realDownloadBytes > 0L })
                               keptDepotRows.sumOf { it.realDownloadBytes } else 0L
        val cachedDownload = if (keptDownload > 0L) keptDownload else repo.getSelectedDownloadSize(appId)
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
        // Highest engine-reported manifest-relative completion per depot (depotPercentComplete =
        // sizeDownloaded/completeDownloadSize). 1.0 == that depot's needed file set finished THIS
        // session. Kept as a running max (chunk callbacks can arrive out of order across worker
        // threads). Absent key ⇒ the depot transferred nothing this session (already on disk /
        // de-duped / skipped). Drives the per-depot completion verdict in onDownloadCompleted.
        val lastDepotPct = java.util.concurrent.ConcurrentHashMap<Int, Float>()

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

        // Build the progress event: install pair + download pair + ETA(sec) + speed(bytes/s).
        // etaSeconds < 0 = unknown (not yet measured / paused); speedBps 0 = unknown.
        fun emitProgress(iDone: Long, iTotal: Long, dDone: Long, dTotal: Long,
                         etaSeconds: Long = -1L, speedBps: Long = 0L) {
            repo.emit("DownloadProgress:$appId:$iDone:$iTotal:$dDone:$dTotal:$etaSeconds:$speedBps")
        }

        // Derive the two pipeline-stage caps from CPU cores × the selected tier's ratios
        // (see DownloadSpeedConfig). On the joshuatam fork engine, chunk buffers are disk-spooled
        // (temp files) rather than held in memory, so heap peak no longer scales with game size and
        // these are purely throughput knobs — the old maxFileWrites stage was removed upstream.
        val speedConfig   = DownloadSpeedConfig(speedTier)
        val cores         = speedConfig.cpuCores
        val maxDownloads  = speedConfig.maxDownloads
        val maxDecompress = speedConfig.maxDecompress
        dlog("Constructing DepotDownloader(tier=$speedTier, cores=$cores, maxDownloads=$maxDownloads, " +
                "maxDecompress=$maxDecompress, androidEmulation=true, skipLargeFileAllocation=true, debug=$verbose)")
        val downloader = try {
            // Named args: the fork removed the maxFileWrites ctor arg (it no longer has a separate
            // file-write stage — decompress+write are combined), so the positional slots shifted.
            DepotDownloader(
                steamClient = steamClient,
                licenses = licenses,
                debug = verbose,                 // gated: BuildConfig.DEBUG || user "Log debug session"
                useLanCache = false,
                maxDownloads = maxDownloads,      // cores × tier download ratio
                maxDecompress = maxDecompress,    // cores × tier decompress ratio
                androidEmulation = true,
                parentJob = null,
                // #408: the fork disk-spools chunks so heap no longer scales with game size; this
                // flag also skips the multi-GB per-file pre-allocation that HITMAN's large files tripped.
                skipLargeFileAllocation = true,
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
        // Layer 1 signal: set by onDownloadCompleted when the per-depot manifest verify finds a SELECTED
        // depot short (the engine reported "complete" but a depot's content is missing/truncated). The
        // tail (outside the try/finally) then decides bounded auto-resume vs fail — mirroring the
        // session-recovery retryAsResume path. onDiskAtCompletion carries the footprint at that verdict
        // so the tail's forward-progress check needs no extra directory walk.
        val depotResumeNeeded  = AtomicBoolean(false)
        val depotShortSummary  = AtomicReference("")
        val onDiskAtCompletion = AtomicLong(0L)
        // Throttle FGS notification updates to whole-percent changes (chunks fire far too often).
        val lastNotifiedPct = AtomicInteger(-1)

        // Smoothed download speed + ETA. Chunk callbacks fire many times/sec and can be concurrent,
        // so sample the compressed-byte rate at most once/sec under a lock and feed it through an EMA
        // (a raw remaining/instant-speed ETA flickers wildly). smoothedBps 0 / eta -1 = unknown.
        val rateLock = Any()
        var smoothedBps = 0.0
        var lastRateMs = System.currentTimeMillis()
        var lastRateBytes = downloadBase

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
                // Track the engine's manifest-relative completion for this depot (running max — callbacks
                // can arrive out of order). This is the primary per-depot completeness signal in the
                // false-complete verdict; it needs no separate manifest resolve and is never inflated by
                // a shared/redist depot's PICS size.
                lastDepotPct.merge(depotId, depotPercentComplete) { a, b -> maxOf(a, b) }

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

                // Smoothed speed + ETA from the compressed-byte rate (network is what "time left" means).
                var etaSeconds = -1L
                var speedBps = 0L
                synchronized(rateLock) {
                    val now = System.currentTimeMillis()
                    val dtMs = now - lastRateMs
                    if (dtMs >= 1000L) {
                        val delta = downloadDone - lastRateBytes
                        if (delta > 0L) {
                            val inst = delta * 1000.0 / dtMs
                            smoothedBps = if (smoothedBps <= 0.0) inst else 0.6 * smoothedBps + 0.4 * inst
                        }
                        lastRateMs = now
                        lastRateBytes = downloadDone
                    }
                    speedBps = smoothedBps.toLong()
                    if (smoothedBps > 0.0 && dTotal > downloadDone) {
                        etaSeconds = ((dTotal - downloadDone) / smoothedBps).toLong()
                    }
                }

                dlog("Chunk: depot=$depotId $pct% install=${fmtSize(installDone)}/${fmtSize(iTotal)} " +
                        "download=${fmtSize(downloadDone)}/${fmtSize(dTotal)}")
                emitProgress(installDone, iTotal, downloadDone, dTotal, etaSeconds, speedBps)
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
                        etaSeconds = etaSeconds,
                        speedBps = speedBps,
                    )
                }

                // FGS notification: "Downloading <game> — N% · <speed> · <eta>", throttled to
                // whole-percent changes so chunk spam doesn't thrash the notification. Reverted to the
                // connection status in the finally (repo.refreshFgsStatus()). No-op if the FGS is down.
                if (lastNotifiedPct.getAndSet(pct) != pct) {
                    val extra = buildString {
                        if (speedBps > 0L)    append(" · ${formatDownloadSpeed(speedBps)}")
                        if (etaSeconds >= 0L) append(" · ${formatEta(etaSeconds)}")
                    }
                    try { SteamForegroundService.setStatusText("Downloading ${row.name} — $pct%$extra") }
                    catch (_: Throwable) {}
                }
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                // The engine's per-depot args here are SESSION DELTAS and under-report: a depot already
                // on disk (resume/verify) or de-duped against a twin fires here with 0 bytes even though
                // its content is fully present. So this callback is NOT authoritative completion — the
                // old "Depot N complete: 0 KB" line was a desynced running-tally artefact. The real
                // per-depot verdict is computed in onDownloadCompleted from installByDepot +
                // depotPercentComplete + the on-disk manifest-true footprint. Log only a breadcrumb of
                // what THIS session actually delivered for the depot (from our accurate tracking).
                val delivered = maxOf(uncompressedBytes, installByDepot[depotId] ?: 0L)
                val pct = lastDepotPct[depotId]
                dlog("Depot $depotId processed: delivered ${fmtSize(delivered)} this session" +
                        (if (pct != null) " (engine ${"%.1f".format(pct * 100)}%)"
                         else " (no chunk activity — already on disk / de-duped / skipped)"))
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                dlog("=== Download complete: appId=${item.appId} ===")
                val iTotal = installTotalRunning.get()
                val dTotal = downloadTotalRunning.get()
                val finalInstall = maxOf(lastInstallDone.get(), installByDepot.values.sum())
                // Accurate grand total from our own tracking (the per-depot engine args under-report).
                dlog("Total downloaded: ${fmtSize(finalInstall)} uncompressed / " +
                        "${fmtSize(downloadByDepot.values.sum())} compressed across ${installByDepot.size} depot(s)")

                // ============================================================================
                // PER-DEPOT MANIFEST COMPLETION VERDICT  (Layer 2 — replaces the whole-app PICS gate)
                //
                // The old guard compared bytes-on-disk against the SUMMED whole-app size. That over-counts
                // SHARED/redist depots a game only partly pulls — e.g. Risk of Rain 2's Steamworks Common
                // Redistributables depot 228988 (from app 228980) declares GBs but RoR2 needs only ~57 MB,
                // inflating the whole-app estimate to ~5.6 GB — so a genuinely-complete 3.0 GB install was
                // rejected as "incomplete". A summed check also can't tell a truncated depot (Dead Cells'
                // 588651 stopping at ~50%) from a mere PICS over-report.
                //
                // Instead judge EACH selected depot against ITS OWN manifest, from three signals:
                //   1. depotPercentComplete (lastDepotPct) — the ENGINE's manifest-relative completion for
                //      the depot (sizeDownloaded/completeDownloadSize). ≥DEPOT_PCT_COMPLETE ⇒ that depot's
                //      needed file set finished THIS session. Primary, always available for any depot that
                //      transferred bytes; needs no separate resolve and is NEVER inflated by shared PICS.
                //      A depot the engine actively downloaded but left below 1.0 is genuinely SHORT.
                //   2. manifest-true uncompressed sizes (DepotSizeResolver → realSizeBytes) summed over the
                //      KEPT depots that resolved, vs the on-disk footprint — the authoritative "the whole
                //      install is already present" check for a verify/re-check pass (engine transfers
                //      nothing, so signal 1 is silent). Manifest-true, so NOT inflated by shared depots.
                //   3. overlapping-depot de-dup: two depots with identical files → the engine writes each
                //      unique file once, so a de-duped twin transfers 0 bytes yet the install is complete
                //      (on disk it reaches ~the largest single kept depot, with no zero-byte skip files).
                //
                // INSTALLED iff EVERY kept depot is complete — regardless of the inflated whole-app PICS
                // estimate. Any short depot ⇒ defer to bounded auto-resume (Layer 1) in the tail; never
                // mark installed while a selected depot is genuinely short. DLC opt-outs are honoured
                // (judge only the depots this download actually pulled).
                // ============================================================================
                val excluded = try { SteamPrefs.getExcludedDlc(appId) } catch (_: Throwable) { emptySet() }
                val keptRows = try { db.getDepotManifests(appId).filter { it.depotId !in excluded } }
                               catch (_: Throwable) { emptyList() }
                val onDisk      = dirSizeBytes(installDir)
                onDiskAtCompletion.set(onDisk)
                val hasEmpty    = hasZeroByteFile(installDir)
                // Manifest-true footprint yardstick — sum ONLY the kept depots that actually resolved.
                // Shared/redist depots that never resolve are simply excluded from the sum (their bytes
                // still count on disk), so this can never be inflated the way the whole-app PICS sum is.
                val manifestSum = keptRows.filter { it.realSizeBytes > 0L }.sumOf { it.realSizeBytes }
                // A complete install's on-disk file bytes ≈ the manifest uncompressed total. A complete
                // game may legitimately ship zero-byte files (e.g. CS:S), so this does NOT gate on hasEmpty.
                val onDiskCoversManifest = manifestSum > 0L && onDisk >= (manifestSum * COMPLETE_PCT / 100L)
                // Overlapping-depot yardstick: the largest single kept depot (real size if resolved, else
                // PICS). De-duped twins collapse to ~one copy on disk ≈ this value.
                val largestKept = keptRows.maxOfOrNull { maxOf(it.realSizeBytes, it.sizeBytes) } ?: 0L

                // Verdict for one depot → (complete, note). Order matters: engine-truth first, then the
                // footprint escape for verify/resume/de-dup passes, then genuine-short, then last-resort.
                fun verifyDepot(row: SteamDatabase.DepotManifestRow): Pair<Boolean, String> {
                    val d         = row.depotId
                    val pct       = lastDepotPct[d]                 // engine manifest %, or null (no transfer)
                    val delivered = installByDepot[d] ?: 0L         // uncompressed, THIS session
                    val expected  = row.realSizeBytes               // manifest-true uncompressed, 0=unresolved
                    val exp = if (expected > 0L) fmtSize(expected) else "?"
                    return when {
                        // 1. Engine says this depot's needed file set finished this session.
                        pct != null && pct >= DEPOT_PCT_COMPLETE ->
                            true to "engine ${"%.1f".format(pct * 100)}% · delivered ${fmtSize(delivered)}/$exp"
                        // 2. Whole install footprint already covers the manifest-true total (verify pass,
                        //    a finished resume, or de-dup) → every present depot's content is on disk.
                        onDiskCoversManifest ->
                            true to "no/partial transfer but on-disk ${fmtSize(onDisk)} covers manifest-true ${fmtSize(manifestSum)}"
                        // 3. Engine actively transferred this depot but left it below 1.0 → genuinely SHORT
                        //    (the Dead Cells case: 588651 stalled at ~50% of its own manifest).
                        pct != null -> {
                            val by = if (expected > 0L) " (short by ~${fmtSize((expected - delivered).coerceAtLeast(0L))})" else ""
                            false to "SHORT engine ${"%.1f".format(pct * 100)}% · delivered ${fmtSize(delivered)}/$exp$by"
                        }
                        // 4. No transfer this session AND footprint short of the manifest total:
                        //    (a) overlapping/de-duplicated twin — on disk ≈ largest single depot, no empties.
                        !hasEmpty && largestKept > 0L && onDisk >= (largestKept * COMPLETE_PCT / 100L) ->
                            true to "no transfer; on-disk ${fmtSize(onDisk)} ≥ largest depot ${fmtSize(largestKept)} — overlapping/de-duped"
                        //    (b) we have a manifest total for this depot but the footprint doesn't cover it
                        //        → content missing (a short sibling depot also drags the footprint down).
                        expected > 0L ->
                            false to "SHORT no transfer · on-disk ${fmtSize(onDisk)} < manifest-true ${fmtSize(manifestSum)} (depot expected $exp)"
                        //    (c) no manifest truth at all for this depot → engine-trust: only fail if it
                        //        delivered nothing (a hard skip); otherwise accept (never false-fail on the
                        //        unreliable PICS number, matching the prior relaxed behaviour).
                        delivered > 0L ->
                            true to "delivered ${fmtSize(delivered)} (no manifest total — trusting engine)"
                        else ->
                            false to "SHORT nothing delivered and no manifest total"
                    }
                }

                if (keptRows.isEmpty()) {
                    // No depot metadata (unresolved library row) — nothing to verify against. Preserve the
                    // prior lenient behaviour: trust the engine's completion rather than block the install.
                    dlog("Per-depot verify: no depot rows for appId=$appId — trusting engine completion " +
                            "(on-disk ${fmtSize(onDisk)})")
                } else {
                    val checks = keptRows.map { it.depotId to verifyDepot(it) }
                    // Layer 3 — per-depot diagnosis line for every selected depot.
                    checks.forEach { (id, cn) ->
                        dlog("Depot $id: ${if (cn.first) "COMPLETE" else "SHORT"} — ${cn.second}")
                    }
                    val shorts = checks.filter { !it.second.first }
                    if (shorts.isNotEmpty()) {
                        val summary = shorts.joinToString("; ") { "depot ${it.first} [${it.second.second}]" }
                        depotShortSummary.set(summary)
                        depotResumeNeeded.set(true)
                        dlog("INCOMPLETE (per-depot manifest verify): ${shorts.size}/${checks.size} selected " +
                                "depot(s) short — on-disk ${fmtSize(onDisk)}, manifest-true ${fmtSize(manifestSum)}. " +
                                "SHORT: $summary")
                        dlog("Deferring to bounded auto-resume (Layer 1) — NOT marking installed this pass.")
                        return   // do NOT markInstalled; the tail decides resume-vs-fail (bounded + backoff)
                    }
                    dlog("Per-depot verify PASS: all ${checks.size} selected depot(s) COMPLETE — on-disk " +
                            "${fmtSize(onDisk)}, manifest-true ${fmtSize(manifestSum)} → INSTALLED " +
                            "(whole-app PICS estimate ${fmtSize(iTotal)} ignored — it over-counts shared/redist depots)")
                }

                // Both bars reach 100% before switching to installed state.
                emitProgress(iTotal, iTotal, dTotal, dTotal)
                db.markInstalled(appId, installDir.absolutePath, if (finalInstall > 0L) finalInstall else iTotal)
                // Stamp the build we just installed so the RealSteam update-on-launch gate
                // (SteamGameUpdater) can cheaply detect this game is current next time — it compares
                // this marker against the live steam_branches build id. Resolved independently of the
                // download's selectedBranch (declared later in runInstall, out of this listener's scope).
                try {
                    val stampBranch = try { SteamPrefs.getSelectedBranch(appId) } catch (_: Throwable) { "public" }
                    SteamGameUpdater.recordInstalledBuild(ctx, appId, installDir, stampBranch)
                } catch (_: Throwable) {}
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

        // Beta-branch selector: the branch the user chose on the detail page (default "public") plus
        // the verified access code for a password-protected branch (null for public / unlocked-none).
        // Ported from GameNative (GPL-3.0): SteamService AppItem(branch, branchPassword) wiring.
        val selectedBranch = try { SteamPrefs.getSelectedBranch(appId) } catch (_: Throwable) { "public" }
        val branchPassword: String? = if (selectedBranch != "public") {
            try { db.getUnlockedBranchPassword(appId, selectedBranch) } catch (_: Throwable) { null }
        } else null

        // DLC picker: DLC the user opted out of (appId == depot id). When non-empty we hand the
        // engine an EXPLICIT depot list (our filtered selection minus the excluded DLC) instead of
        // letting it auto-resolve — so the unchecked DLC simply isn't downloaded. Default (nothing
        // excluded) → empty lists → engine auto-resolves exactly as before.
        val excludedDlc = try { SteamPrefs.getExcludedDlc(appId) } catch (_: Throwable) { emptySet() }
        // Also drop this app's known stale-duplicate depots (see STALE_DUPLICATE_DEPOTS) so the engine
        // can't de-dupe a maintained file down to its outdated twin (e.g. Lossless Scaling's 993092).
        val staleDupeDepots = STALE_DUPLICATE_DEPOTS[appId].orEmpty()
        val dropDepots = excludedDlc + staleDupeDepots
        val explicitDepots: List<Int>
        val explicitManifests: List<Long>
        // The explicit manifest gids come from our PUBLIC-branch DB rows, so they only apply to the
        // public branch. For any other branch, hand the engine EMPTY lists so it resolves that
        // branch's own manifests (given AppItem.branch/branchPassword). The DLC opt-out and the
        // stale-duplicate drop therefore only take effect on the public branch — matching where this
        // manifest data is valid (and the branch these overlapping depots occur on).
        if (dropDepots.isNotEmpty() && selectedBranch == "public") {
            val kept = try { db.getDepotManifests(appId).filter { it.depotId !in dropDepots && it.manifestId != 0L } }
                       catch (_: Throwable) { emptyList() }
            explicitDepots   = kept.map { it.depotId }
            explicitManifests = kept.map { it.manifestId }
            if (staleDupeDepots.isNotEmpty()) {
                dlog("Stale-duplicate depot drop for app $appId: excluding ${staleDupeDepots.joinToString(",")}" +
                     (if (excludedDlc.isNotEmpty()) " + DLC ${excludedDlc.joinToString(",")}" else "") +
                     " → downloading ${explicitDepots.size} depot(s) explicitly")
            } else {
                dlog("DLC opt-out: excluding ${excludedDlc.joinToString(",")} → downloading ${explicitDepots.size} depot(s) explicitly")
            }
        } else {
            explicitDepots = emptyList()
            explicitManifests = emptyList()
        }

        val item = AppItem(
            appId = appId,
            installDirectory = installDir.absolutePath,
            branch = selectedBranch,
            branchPassword = branchPassword,
            // Explicitly request Windows depots — don't let Util.getSteamOS() guess,
            // since androidEmulation only works if IS_OS_ANDROID is true at runtime.
            os = "windows",
            // Skip arch filtering — we always want the game's Windows depots regardless
            // of what os.arch returns on this Android device (arm64, aarch64, armv8l, etc.).
            // Wine/Box64 handles x86_64 translation; arch mismatch would filter all depots.
            downloadAllArchs = true,
            depot = explicitDepots,
            manifest = explicitManifests,
        )
        dlog("Adding AppItem: appId=${item.appId} branch=${item.branch}" +
             (if (branchPassword != null) " (pwd-protected)" else "") +
             " dir=${item.installDirectory}" +
             if (explicitDepots.isNotEmpty()) " depots=${explicitDepots.size}(explicit)" else "")
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
        // MAX_SESSION_RETRIES; re-enters as a resume so already-downloaded files are reused. The
        // short-depot resume counters ride through unchanged (this is a session-recovery retry, a
        // different failure domain).
        if (retryAsResume) {
            dlog("Retrying install for appId=$appId (attempt ${attempt + 1} → ${attempt + 2}) as resume")
            runInstall(appId, ctx, cancelled, paused, downloaderRef, speedTier, debugLog,
                    isResume = true, attempt = attempt + 1, control = control,
                    resumeAttempt = resumeAttempt, resumeFloorBytes = resumeFloorBytes)
            return
        }

        // Layer 1 — AUTO-RESUME a genuinely-short selected depot. The engine reported "download
        // complete" but the per-depot manifest verify (onDownloadCompleted) found a kept depot short
        // (Dead Cells' 588651 stopping at ~50%). Re-enter as a RESUME so the engine re-verifies files
        // already on disk and re-fetches only the missing chunks (over CDN HTTP). Bounded by
        // MAX_DEPOT_RESUME_ATTEMPTS, with backoff and a no-forward-progress fail-fast so an
        // unavailable depot (no key / dead CDN — e.g. an unowned depot) can never loop. Session
        // recovery (retryAsResume) takes precedence and already returned above.
        if (depotResumeNeeded.get() && !cancelled.get() && !paused.get()) {
            val curOnDisk  = onDiskAtCompletion.get().takeIf { it > 0L } ?: dirSizeBytes(installDir)
            val summary    = depotShortSummary.get()
            val progressed = curOnDisk > resumeFloorBytes + MIN_RESUME_PROGRESS_BYTES
            when {
                resumeAttempt >= MAX_DEPOT_RESUME_ATTEMPTS -> {
                    dlog("Auto-resume exhausted after $MAX_DEPOT_RESUME_ATTEMPTS attempt(s) — still SHORT: $summary")
                    emitFailed(appId, "Download incomplete after $MAX_DEPOT_RESUME_ATTEMPTS resume attempts — please retry")
                }
                resumeAttempt > 0 && !progressed -> {
                    // A resume that added no data means the short depot's chunks are unreachable (no key /
                    // CDN failure). Fail fast rather than burn the remaining attempts on a dead depot.
                    dlog("Auto-resume made NO forward progress (on-disk ${fmtSize(curOnDisk)} ≤ floor " +
                            "${fmtSize(resumeFloorBytes)} + ${fmtSize(MIN_RESUME_PROGRESS_BYTES)}) — a selected " +
                            "depot is unavailable (no key / dead CDN). Failing fast. SHORT: $summary")
                    emitFailed(appId, "Download stalled — a depot delivered no new data on resume; please retry")
                }
                else -> {
                    val backoff = DEPOT_RESUME_BACKOFF_MS[resumeAttempt.coerceAtMost(DEPOT_RESUME_BACKOFF_MS.size - 1)]
                    dlog("Auto-resume attempt ${resumeAttempt + 1}/$MAX_DEPOT_RESUME_ATTEMPTS in ${backoff}ms " +
                            "(on-disk floor ${fmtSize(curOnDisk)}) — re-fetching missing chunks for SHORT: $summary")
                    // Return the DB/registry to a resumable/downloading state — the engine flipped nothing
                    // (we returned before markInstalled), so just re-enter as a resume. Re-mark this appId
                    // active BEFORE the backoff sleep so the UI's stale-row detector (isDownloading) doesn't
                    // flag the row during the pause; the re-entered runInstall re-marks it anyway.
                    activeDownloads[appId] = Unit
                    try { db.markDownloadResuming(appId) } catch (_: Throwable) {}
                    DownloadRegistry.update(dmKey) { it.copy(state = DownloadState.DOWNLOADING) }
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    // attempt=0: a short-depot resume gets a fresh session-recovery budget. resumeFloorBytes
                    // = the footprint we just measured, so the next pass can prove forward progress.
                    runInstall(appId, ctx, cancelled, paused, downloaderRef, speedTier, debugLog,
                            isResume = true, attempt = 0, control = control,
                            resumeAttempt = resumeAttempt + 1, resumeFloorBytes = curOnDisk)
                }
            }
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

    /** Recursive on-disk byte total for a completed install (real footprint, dedup already applied
     *  by the filesystem — used by the false-complete guard's overlapping-depot branch). */
    private fun dirSizeBytes(f: File): Long =
        when {
            !f.exists() -> 0L
            f.isFile    -> f.length()
            else        -> f.listFiles()?.sumOf { dirSizeBytes(it) } ?: 0L
        }

    /** True if any regular file under [root] is zero-length — the signature of a pre-allocated but
     *  unfilled file left by a genuinely-skipped/truncated depot (distinguishes a real skip from a
     *  legitimately de-duplicated overlapping depot, whose files are all present and non-empty). */
    private fun hasZeroByteFile(root: File): Boolean =
        root.walkTopDown().any { it.isFile && it.length() == 0L }
}
