package com.winlator.star.store.download

/**
 * Thin, store-agnostic write shim over [DownloadRegistry] for the Java-fed store
 * Activities (Amazon now; GOG / Epic later).
 *
 * Steam wires the registry directly from Kotlin ([com.winlator.star.store.SteamDepotDownloader]),
 * so it doesn't need this. The other stores drive their downloads from a Java engine
 * (`AmazonDownloadManager` etc.) with only an Activity-side callback seam to hook — and
 * three of them would otherwise copy-paste the same `"$store:$id"` key construction and
 * the same `upsert`/`update` boilerplate. This object centralises exactly that:
 *   - one place builds the store-qualified key (must match [DownloadEntry.key]);
 *   - one place maps a store's simple "pct + aggregate bytes" callbacks into the
 *     normalized [DownloadEntry] the shared registry + Compose screen expect.
 *
 * All calls are no-ops-safe: [DownloadRegistry.update] silently ignores an absent key,
 * so an out-of-order progress/complete tick can never NPE. ARCHITECTURE: this stays in
 * the `download` package and imports zero store engines — the store Activities import it,
 * never the reverse.
 *
 * As well as feeding the in-app Download Manager, every call here also drives the shared
 * [DownloadForegroundService] so all non-Steam stores get a shade notification + a process
 * kept alive across backgrounding, for free: [registerDownload]/[tick] push the notification
 * line, and the terminal transitions ([markInstalled]/[markCancelled]/[markFailed]) drop it.
 */
object StoreDownloadHooks {

    private class Rate(var at: Long, var bytes: Long, var bps: Double = 0.0)
    private val rates = java.util.concurrent.ConcurrentHashMap<String, Rate>()

    /** Store-qualified registry key — MUST match [DownloadEntry.key] ("AMAZON:$id"). */
    private fun key(store: Store, id: String): String = "$store:$id"

    /**
     * Build the shade line from the entry's current state and push it to the foreground
     * service. Reads the entry back from the registry (populated by the caller immediately
     * before) so name + pct + bytes are always in sync with what the Manager card shows.
     * No-op until [DownloadRegistry.init] has captured an application Context.
     */
    private fun pushNotification(store: Store, id: String) {
        val k = key(store, id)
        val e = DownloadRegistry.get(k) ?: return
        val line = buildString {
            append(e.name.ifEmpty { "${e.store} ${e.id}" })
            append(" — ").append(e.pct).append('%')
            if (e.installTotal > 0L) {
                append("  (")
                append(formatDownloadSize(e.installDone)).append(" / ")
                append(formatDownloadSize(e.installTotal)).append(')')
            }
            // Epic's downloader now feeds a smoothed speed; the other stores' shade lines stay as they were.
            if (e.store == Store.EPIC && e.speedBps > 0L) append("  ·  ").append(formatDownloadSpeed(e.speedBps))
        }
        DownloadForegroundService.setProgress(k, line)
    }

    /**
     * Publish a freshly-started download as a live DOWNLOADING entry. Wires the store's
     * cancel (and optional pause) handles onto the entry so the Download Manager screen can
     * drive them. [installTotal] is the manifest install size if known up front (0 = fill it
     * in from the first [tick]). Stores with no separate compressed stage leave the download
     * byte pair at 0 (one honest bar). Uses `upsert` so a re-download replaces any stale row.
     */
    fun registerDownload(
        store: Store,
        id: String,
        name: String,
        cover: String? = null,
        supportsPause: Boolean = false,
        installTotal: Long = 0L,
        pause: (() -> Unit)? = null,
        cancel: () -> Unit,
    ) {
        DownloadRegistry.upsert(
            DownloadEntry(
                store = store,
                id = id,
                name = name,
                cover = cover,
                state = DownloadState.DOWNLOADING,
                installTotal = installTotal,
                supportsPause = supportsPause,
                pause = pause,
                cancel = cancel,
            )
        )
        // Bring up the shared FGS (keeps the process alive past Activity death) and post the
        // first shade line. start() is idempotent; both no-op cleanly before init() ran.
        DownloadRegistry.appContext()?.let { DownloadForegroundService.start(it) }
        pushNotification(store, id)
    }

    /**
     * Progress tick. [pct] is the overall percentage; the byte pairs mirror the two-bar
     * model ([installDone]/[installTotal] = uncompressed-on-disk, [downloadDone]/[downloadTotal]
     * = compressed-over-network). Stores that expose only aggregate download bytes map them
     * into the install pair and leave the download pair at 0. No-op if the entry is gone.
     */
    fun tick(
        store: Store,
        id: String,
        pct: Int,
        installDone: Long = 0L,
        installTotal: Long = 0L,
        downloadDone: Long = 0L,
        downloadTotal: Long = 0L,
        /** Network speed in bytes/s and seconds left, when the store's downloader reports them (0 / -1 = unknown). */
        speedBps: Long = 0L,
        etaSeconds: Long = -1L,
    ) {
        // A paused row is never revived by a late tick of the run that was stopped; a resume registers the download afresh.
        if (DownloadRegistry.get(key(store, id))?.state == DownloadState.PAUSED) return
        // A downloader that reports no rate still gets one, measured from the bytes between ticks.
        var bps = speedBps
        var eta = etaSeconds
        if (bps <= 0L) {
            val done = if (downloadTotal > 0L) downloadDone else installDone
            val total = if (downloadTotal > 0L) downloadTotal else installTotal
            if (total > 0L) {
                val now = android.os.SystemClock.elapsedRealtime()
                val r = rates.getOrPut(key(store, id)) { Rate(now, done) }
                synchronized(r) {
                    val dt = now - r.at
                    if (dt >= 1000L) {
                        val inst = (done - r.bytes) * 1000.0 / dt
                        if (inst >= 0.0) r.bps = if (r.bps <= 0.0) inst else r.bps * 0.7 + inst * 0.3
                        r.at = now
                        r.bytes = done
                    }
                    bps = r.bps.toLong()
                }
                if (bps > 0L && eta < 0L && total > done) eta = (total - done) / bps
            } else if (eta < 0L && pct in 1..99) {
                // Only a percentage (Epic): no speed, but the time left follows from how fast the percentage moves.
                val now = android.os.SystemClock.elapsedRealtime()
                val r = rates.getOrPut(key(store, id)) { Rate(now, pct.toLong()) }
                synchronized(r) {
                    val dt = now - r.at
                    if (dt >= 3000L && pct > r.bytes) {
                        val inst = (pct - r.bytes) * 1000.0 / dt
                        r.bps = if (r.bps <= 0.0) inst else r.bps * 0.7 + inst * 0.3
                        r.at = now
                        r.bytes = pct.toLong()
                    }
                    if (r.bps > 0.0) eta = ((100 - pct) / r.bps).toLong()
                }
            }
        }
        DownloadRegistry.update(key(store, id)) {
            it.copy(
                state = DownloadState.DOWNLOADING,
                pct = pct,
                installDone = installDone,
                installTotal = installTotal,
                downloadDone = downloadDone,
                downloadTotal = downloadTotal,
                speedBps = bps,
                etaSeconds = eta,
            )
        }
        pushNotification(store, id)
    }

    /**
     * The user paused a download whose transfer has stopped (files and progress kept): the row stays, as PAUSED, with its bytes and
     * percent as they were, no speed / ETA. [pause] / [cancel] are the handles of a paused row (Resume / Cancel). Drops the shade line
     * (a paused download needs no foreground service). Only for stores that support pause.
     */
    fun markPaused(store: Store, id: String, pause: (() -> Unit)?, cancel: (() -> Unit)?) {
        val k = key(store, id)
        rates.remove(k)
        DownloadRegistry.update(k) {
            it.copy(
                state = DownloadState.PAUSED,
                speedBps = 0L,
                etaSeconds = -1L,
                queuePosition = 0,
                supportsPause = true,
                pause = pause,
                cancel = cancel,
            )
        }
        DownloadForegroundService.finish(k)
    }

    /**
     * Terminal success → INSTALLED (the only state the registry persists to the durable
     * library, so the game survives process death in the Library section). [bytes] fills the
     * bar to 100%; 0 keeps whatever totals the last [tick] set.
     */
    fun markInstalled(store: Store, id: String, installPath: String, bytes: Long = 0L) {
        rates.remove(key(store, id))
        DownloadRegistry.update(key(store, id)) {
            it.copy(
                state = DownloadState.INSTALLED,
                pct = 100,
                installPath = installPath,
                installDone = if (bytes > 0L) bytes else it.installTotal,
                installTotal = if (bytes > 0L) bytes else it.installTotal,
                updateAvailable = false,   // a just-installed / just-updated game is up to date
            )
        }
        DownloadForegroundService.finish(key(store, id))   // drop shade line; FGS self-stops when empty
    }

    /** Terminal failure. Kept in-memory for the session so the UI can offer retry/dismiss. */
    fun markFailed(store: Store, id: String, error: String) {
        rates.remove(key(store, id))
        DownloadRegistry.update(key(store, id)) {
            it.copy(state = DownloadState.FAILED, error = error)
        }
        DownloadForegroundService.finish(key(store, id))
    }

    /**
     * User-cancelled a download. Mark CANCELLED first (so a live collector sees the
     * transition), then drop the row — the files + install dir are gone, mirroring how Steam's
     * cancel path clears its registry entry.
     */
    fun markCancelled(store: Store, id: String) {
        val k = key(store, id)
        rates.remove(k)
        DownloadRegistry.update(k) { it.copy(state = DownloadState.CANCELLED) }
        DownloadRegistry.remove(k)
        DownloadForegroundService.finish(k)
    }

    /**
     * Uninstall: drop the INSTALLED library row (and its durable-pref entry) so the Library
     * section clears immediately — same effect as Steam's uninstall clearing its library entry.
     */
    fun markUninstalled(store: Store, id: String) {
        DownloadRegistry.removeLibraryEntry(key(store, id))
    }
}
