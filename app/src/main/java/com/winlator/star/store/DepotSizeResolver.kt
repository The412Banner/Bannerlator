package com.winlator.star.store

import android.util.Log
import `in`.dragonbra.javasteam.steam.cdn.Client as CdnClient
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves the TRUE install/download size of an owned Steam app by fetching each SELECTED depot's
 * MANIFEST (metadata only — NO chunk data) and reading the manifest's declared totals. This replaces
 * the unreliable PICS `manifests/public/size` estimate that Bannerlator sums at library sync, which
 * both over- and under-reports real depot content.
 *
 * WHY IT EXISTS (the install-blocker): SteamDepotDownloader's false-complete guard compares the bytes
 * actually written to disk against the expected total. Using the PICS estimate as that total makes a
 * fully-downloaded but PICS-OVER-reported game (e.g. appId 313830: 130 MB real vs 181 MB PICS) get
 * rejected as "incomplete". The manifest's totalUncompressedSize is the same number the official Steam
 * client shows as "size on disk", so comparing against it fixes the false failure while still catching
 * a genuine truncation (a skipped depot → far below the real total).
 *
 * MANIFEST-ONLY SEAM (base JavaSteam, public API — NOT the DepotDownloader engine, whose manifest path
 * is private):
 *   - [SteamContent.getManifestRequestCode] (depotId, appId, manifestId, branch) → request code
 *   - [SteamContent.getServersForSteamPipe] → a CDN [Server]
 *   - [CdnClient.downloadManifestFuture] (depotId, manifestId, requestCode, server) → DepotManifest
 * The manifest's totalUncompressedSize / totalCompressedSize come from the ContentManifestMetadata
 * protobuf section, populated during deserialize INDEPENDENT of filename decryption — so NO depot key
 * and NO CDN auth token are needed for the sizes. downloadManifest fetches metadata only; it never
 * touches downloadDepotChunk, so this cannot kick off a chunk download.
 *
 * HARD CONSTRAINTS (mirroring the download path; violating these risks the 0%/stall CM-pump bug class):
 *   - Never on the UI thread — resolve() hops to Dispatchers.IO and runs the CM work on the single
 *     library/sync worker (serialized with PICS sync).
 *   - Never while a download is active — [SteamRepository.isDownloadActive] gates it; if a download
 *     owns the CM, serve cached()/estimate and defer.
 *   - Raise the AsyncJob timeouts — a watchdog polls [SteamRepository.bumpPendingJobTimeouts] for the
 *     duration (manifest-code / server-list jobs default to a hard-coded 10 s).
 *   - One depot at a time (bounded, serial).
 *   - Same depot selection as sync (depot_manifests only holds the SELECTED windows/english depots).
 *   - Degrade, never throw: not-logged-in / no-server / CM timeout → keep the estimate for that depot,
 *     mark the result complete=false, cache only fully-resolved depots, swallow ALL exceptions.
 */
object DepotSizeResolver {

    private const val TAG = "DepotSizeResolver"

    /** Bumped AsyncJob timeout for the resolver's CM jobs (matches the download watchdog's 60 s). */
    private const val JOB_TIMEOUT_MS      = 60_000L
    /** Per-CM-call ceilings — generous (jobs are timeout-raised) but always bounded so we never hang. */
    private const val SERVER_TIMEOUT_MS   = 20_000L
    private const val CODE_TIMEOUT_MS     = 20_000L
    private const val MANIFEST_TIMEOUT_MS = 30_000L
    /** Whole-resolve budget for the suspend entrypoint (covers several serial depots). */
    private const val RESOLVE_BUDGET_MS   = 120_000L

    /** Sizes(realInstallBytes, realDownloadBytes, complete). complete=true only when EVERY selected
     *  depot resolved; a partial result still reports the best sum but complete=false. */
    data class Sizes(val realInstallBytes: Long, val realDownloadBytes: Long, val complete: Boolean)

    // -------------------------------------------------------------------------
    // Instant, DB-only read — safe from ANY thread (pure SQLite, no CM).
    // -------------------------------------------------------------------------

    /**
     * The already-resolved sizes for an app, straight from the DB. Returns null when NO depot has ever
     * been resolved (caller should show the PICS `~estimate`). When some-but-not-all depots resolved,
     * returns a partial sum with complete=false (real where known, PICS estimate for the rest).
     */
    @JvmStatic
    fun cached(appId: Int): Sizes? {
        return try {
            val rows = SteamRepository.getInstance().database.getDepotManifests(appId)
            if (rows.isEmpty()) return null
            if (rows.none { it.realSizeBytes > 0L }) return null   // never resolved
            var install = 0L
            var download = 0L
            var complete = true
            for (r in rows) {
                if (r.realSizeBytes > 0L) {
                    install  += r.realSizeBytes
                    download += r.realDownloadBytes
                } else {
                    complete = false
                    install  += r.sizeBytes            // PICS fallback for the unresolved depot
                }
            }
            Sizes(install, download, complete)
        } catch (t: Throwable) {
            Log.w(TAG, "cached($appId) failed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** PICS-estimate Sizes (complete=false) for callers when nothing is resolved. Never null. */
    private fun estimate(appId: Int): Sizes {
        return try {
            val db = SteamRepository.getInstance().database
            val install = db.getDepotManifests(appId).sumOf { it.sizeBytes }
            val download = SteamRepository.getInstance().getSelectedDownloadSize(appId)
            Sizes(install, download, complete = false)
        } catch (t: Throwable) {
            Sizes(0L, 0L, complete = false)
        }
    }

    // -------------------------------------------------------------------------
    // Resolve — fetches only the depots missing a real size. Degrades to estimate.
    // -------------------------------------------------------------------------

    /** suspend entrypoint (safe to call from the UI's coroutine scope — hops off the main thread). */
    suspend fun resolve(appId: Int): Sizes = withContext(Dispatchers.IO) { resolveBlocking(appId) }

    /**
     * Blocking entrypoint for the Activity's plain worker threads. MUST NOT be called on the UI thread
     * or from within the library worker (it submits to and waits on that worker). Never throws.
     */
    @JvmStatic
    fun resolveBlocking(appId: Int): Sizes {
        val repo = SteamRepository.getInstance()
        val fallback = cached(appId) ?: estimate(appId)
        // Gate: not signed in, or a download owns the CM → serve cached/estimate, don't touch the CM.
        if (!repo.isLoggedIn || repo.isDownloadActive()) return fallback
        if (fallback.complete) return fallback   // already fully resolved — nothing to fetch

        val future = CompletableFuture<Sizes>()
        try {
            repo.submitLibraryWork {
                val result = try { resolveOnWorker(appId, repo, fallback) }
                             catch (t: Throwable) {
                                 Log.w(TAG, "resolveOnWorker($appId) failed: ${t.javaClass.simpleName}")
                                 fallback
                             }
                future.complete(result)
            }
        } catch (t: Throwable) {
            return fallback   // couldn't even submit — degrade
        }
        return try {
            future.get(RESOLVE_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            cached(appId) ?: fallback   // timed out waiting — return whatever landed so far
        }
    }

    /**
     * Runs ON the single library worker thread (blocking is fine here — it's a dedicated daemon and
     * this serializes with PICS sync). Fetches each unresolved SELECTED depot's manifest, persists the
     * real sizes, and caches the app-level total when the whole app resolves.
     */
    private fun resolveOnWorker(appId: Int, repo: SteamRepository, fallback: Sizes): Sizes {
        val db = repo.database
        val rows = db.getDepotManifests(appId).filter { it.manifestId != 0L }
        if (rows.isEmpty()) return fallback

        val steamClient = repo.steamClient ?: return fallback
        val content = repo.getSteamContent() ?: return fallback

        // Raise the 10 s AsyncJob timeout on the resolver's CM jobs for the whole batch.
        val watchdog = AtomicBoolean(true)
        Thread({
            while (watchdog.get()) {
                try { repo.bumpPendingJobTimeouts(JOB_TIMEOUT_MS) } catch (_: Throwable) {}
                try { Thread.sleep(1_000L) } catch (_: InterruptedException) { break }
            }
        }, "DepotSizeResolverWatchdog").apply { isDaemon = true; start() }

        val scope = CoroutineScope(Dispatchers.IO)
        val cdn = CdnClient(steamClient)
        try {
            // One CDN server for the whole batch (degrade if none).
            val servers: List<Server>? = runBlocking {
                withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                    content.getServersForSteamPipe(null, null, scope).await()
                }
            }
            val server = servers?.firstOrNull() ?: run {
                Log.w(TAG, "resolve($appId): no CDN server — keeping estimate")
                return fallback
            }

            for (r in rows) {
                if (r.realSizeBytes > 0L) continue                 // already resolved
                if (repo.isDownloadActive()) break                 // a download started mid-resolve — yield
                if (!repo.isLoggedIn) break
                resolveOneDepot(cdn, content, scope, appId, r, server, db)  // best-effort; failures degrade
            }

            val result = cached(appId) ?: fallback
            if (result.complete && result.realInstallBytes > 0L) {
                try { db.setGameRealSize(appId, result.realInstallBytes) } catch (_: Throwable) {}
            }
            return result
        } finally {
            watchdog.set(false)
            // NOTE: do NOT close `cdn` here. CdnClient(steamClient) wraps the SteamClient's
            // app-wide shared OkHttpClient; closing it shuts down that dispatcher's ExecutorService,
            // which the real depot downloader also uses — poisoning every later manifest/chunk fetch
            // with "executor rejected" (Brawlhalla stuck-at-0% regression). The downloader engine
            // never closes the shared client; neither must the resolver. cdn owns nothing to free.
            try { scope.cancel() } catch (_: Throwable) {}
        }
    }

    /** Fetch one depot's manifest and persist its true sizes. Returns silently on any failure. */
    private fun resolveOneDepot(
        cdn: CdnClient,
        content: SteamContent,
        scope: CoroutineScope,
        appId: Int,
        row: SteamDatabase.DepotManifestRow,
        server: Server,
        db: SteamDatabase,
    ) {
        try {
            // Manifest request code (required to authenticate the CDN manifest fetch since ~2022).
            val requestCode: Long = runBlocking {
                withTimeoutOrNull(CODE_TIMEOUT_MS) {
                    content.getManifestRequestCode(row.depotId, appId, row.manifestId, "public", null, scope).await()
                }
            } ?: run {
                Log.w(TAG, "resolve($appId): depot ${row.depotId} request-code timeout — keeping estimate")
                return
            }

            // Metadata-only manifest fetch — no depot key, no CDN token, no chunk download.
            val manifest = try {
                cdn.downloadManifestFuture(row.depotId, row.manifestId, requestCode, server)
                    .get(MANIFEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "resolve($appId): depot ${row.depotId} manifest fetch failed: ${t.javaClass.simpleName}")
                return
            }

            val realUncompressed = manifest.totalUncompressedSize
            val realCompressed   = manifest.totalCompressedSize
            if (realUncompressed <= 0L) {
                Log.w(TAG, "resolve($appId): depot ${row.depotId} manifest had no size — keeping estimate")
                return
            }
            db.updateDepotRealSize(appId, row.depotId, row.manifestId, realUncompressed, realCompressed)
            Log.i(TAG, "resolve($appId): depot ${row.depotId} real=${realUncompressed}B " +
                    "download=${realCompressed}B (PICS was ${row.sizeBytes}B)")
        } catch (t: Throwable) {
            Log.w(TAG, "resolve($appId): depot ${row.depotId} unexpected ${t.javaClass.simpleName} — keeping estimate")
        }
    }
}
