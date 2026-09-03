package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store

/**
 * Engine-agnostic, strictly one-at-a-time managed download queue.
 *
 * ## Why a queue (and not concurrency)
 * A single Steam download already saturates the connection (the engine fetches chunks with a
 * parallel pool), so running two at once buys no throughput — it only adds contention on the one
 * Steam CM connection, simultaneous disk thrash and device thread/memory pressure. So downloads run
 * one at a time; the rest wait as data (a QUEUED registry row — no worker thread, no engine work)
 * and auto-advance when the active slot frees. This replaces the old crude "park a worker thread on
 * a Semaphore + show 'Waiting to download…'" gate inside the Rust engine
 * ([BlDepotInstaller]); queued items no longer hold a live thread.
 *
 * ## Where it sits
 * [SteamDepotDownloader.installApp] / [SteamDepotDownloader.resumeApp] both funnel through
 * [enqueue] (via `buildControl`), BEFORE the Rust-vs-JavaSteam engine choice. When an appId reaches
 * the front, the coordinator calls back into [SteamDepotDownloader.startEngine], which launches
 * whichever engine is active — so the one-at-a-time rule governs BOTH engines consistently (JavaSteam
 * is the deprecated fallback but must not silently stay concurrent).
 *
 * ## The DownloadControl facade
 * [enqueue] returns a [SteamDepotDownloader.DownloadControl] synchronously even while the request is
 * only queued. That facade routes through the coordinator by appId:
 *  - while QUEUED: `cancel` removes the item from the queue (deletes the `steam_downloads` row +
 *    registry entry, no files); `pause` holds it out of line (marks it PAUSED — a plain paused
 *    download the user resumes later, which re-enqueues it).
 *  - once ACTIVE: `cancel` / `pause` delegate to the real engine control transparently.
 *
 * ## Auto-advance
 * The active download's engine calls [onActiveTerminal] at each TRUE terminal (INSTALLED / FAILED /
 * CANCELLED / PAUSED — never on a session-recovery retry or short-depot auto-resume, which re-enter
 * the same active download). The coordinator then promotes the head of the queue and starts it.
 *
 * ## Thread-safety
 * One [lock] guards all mutable state; the engine start (which spawns a worker thread) runs OUTSIDE
 * the lock. Claiming the active slot is atomic under the lock, so an enqueue that races a terminal
 * advance starts exactly one runner — no double-start, no lost wakeup.
 *
 * Steam-only for now (only [SteamDepotDownloader] routes through here); other stores keep their own
 * managers and never appear in [pending].
 */
internal object DownloadQueue {

    private const val TAG = "BL_DL_QUEUE"

    /** Everything the engine needs to actually start a download, captured at enqueue time. */
    class Request(
        val appId: Int,
        /** ALWAYS the application context (captured in `buildControl`) — safe to hold until dequeued. */
        val ctx: Context,
        val speedTier: Int,
        val debugLog: Boolean,
        val isResume: Boolean,
        val installRoot: String?,
        val verify: Boolean,
    )

    private class Pending(
        val request: Request,
        val control: SteamDepotDownloader.DownloadControl,
    )

    /** A pause/cancel that landed in the tiny window before the engine control was registered. */
    private enum class PendingAction { CANCEL, PAUSE }

    private val lock = Any()

    /** appId currently running on an engine, or -1 when the slot is free. */
    private var activeAppId: Int = -1

    /** The active download's real engine control (delegated to by the facade), or null. */
    private var activeControl: SteamDepotDownloader.DownloadControl? = null

    /** Action captured while [activeAppId] is set but [activeControl] hasn't been registered yet. */
    private var pendingActiveAction: PendingAction? = null

    /** FIFO of waiting requests, keyed by appId (insertion order == queue order). */
    private val pending = LinkedHashMap<Int, Pending>()

    // -------------------------------------------------------------------------
    // Public entry — called by SteamDepotDownloader.buildControl for BOTH engines
    // -------------------------------------------------------------------------

    /**
     * Enqueue (or immediately start, if the slot is free) a download. Returns a facade
     * [SteamDepotDownloader.DownloadControl] immediately — see the class doc for its semantics.
     * De-dupes: an appId already active or already queued is NOT enqueued twice.
     */
    fun enqueue(request: Request): SteamDepotDownloader.DownloadControl {
        val appId = request.appId
        val facade = facadeFor(appId)   // stateless; built before the lock so it's always assigned
        var toStart: Pending? = null
        synchronized(lock) {
            // De-dupe: already the active download → hand back a facade tied to it.
            if (appId == activeAppId) {
                Log.i(TAG, "enqueue($appId): already active — ignoring duplicate")
                return facade
            }
            // De-dupe: already waiting → hand back its existing facade.
            pending[appId]?.let {
                Log.i(TAG, "enqueue($appId): already queued (#${it.queuePos()}) — ignoring duplicate")
                return it.control
            }
            val p = Pending(request, facade)
            if (activeAppId == -1) {
                // Slot free → claim it atomically and start (outside the lock).
                activeAppId = appId
                activeControl = null
                pendingActiveAction = null
                toStart = p
                Log.i(TAG, "enqueue($appId): slot free — starting now")
            } else {
                // Slot busy → wait as data. No engine work, no thread.
                pending[appId] = p
                Log.i(TAG, "enqueue($appId): busy on $activeAppId — queued at #${pending.size}")
                seedQueuedRow(request, facade)
                refreshQueuePositionsLocked()
            }
        }
        toStart?.let { beginActive(it) }
        return facade
    }

    // -------------------------------------------------------------------------
    // Facade control — delegates by appId to the queue or the active engine
    // -------------------------------------------------------------------------

    private fun facadeFor(appId: Int) = SteamDepotDownloader.DownloadControl(
        cancel = Runnable { requestCancel(appId) },
        pause = Runnable { requestPause(appId) },
    )

    private fun requestCancel(appId: Int) {
        var realToCancel: SteamDepotDownloader.DownloadControl? = null
        var wasQueued = false
        synchronized(lock) {
            if (appId == activeAppId) {
                val real = activeControl
                if (real == null) pendingActiveAction = PendingAction.CANCEL   // apply once registered
                else realToCancel = real
            } else if (pending.remove(appId) != null) {
                wasQueued = true
                refreshQueuePositionsLocked()
            }
        }
        // Active: delegate — the engine's finally emits DownloadCancelled + calls onActiveTerminal,
        // which frees the slot and advances the queue.
        realToCancel?.cancel?.run()
        // Queued: no engine ran, so tidy up ourselves. Cancelling a queued item frees NO active slot,
        // so we do NOT advance.
        if (wasQueued) finishQueuedCancel(appId)
    }

    private fun requestPause(appId: Int) {
        var realToPause: SteamDepotDownloader.DownloadControl? = null
        var wasQueued = false
        synchronized(lock) {
            if (appId == activeAppId) {
                val real = activeControl
                if (real == null) pendingActiveAction = PendingAction.PAUSE
                else realToPause = real
            } else if (pending.remove(appId) != null) {
                wasQueued = true
                refreshQueuePositionsLocked()
            }
        }
        realToPause?.pause?.run()
        // Queued item paused = held out of line: mark it a plain PAUSED download (resume re-enqueues).
        if (wasQueued) finishQueuedPause(appId)
    }

    // -------------------------------------------------------------------------
    // Auto-advance — called by the engines at a TRUE terminal for the active download
    // -------------------------------------------------------------------------

    /**
     * The active download reached a terminal state (INSTALLED / FAILED / CANCELLED / PAUSED). Free
     * the slot and promote the head of the queue. No-op if [appId] isn't the active download (e.g. a
     * queued item that was cancelled/paused directly, or a duplicate terminal). Safe on any thread.
     */
    fun onActiveTerminal(appId: Int) {
        var next: Pending? = null
        synchronized(lock) {
            if (appId != activeAppId) return
            activeAppId = -1
            activeControl = null
            pendingActiveAction = null
            next = pollNextLocked()
            if (next != null) {
                activeAppId = next!!.request.appId
                activeControl = null
                pendingActiveAction = null
            }
        }
        next?.let {
            Log.i(TAG, "advance: $appId finished — promoting ${it.request.appId}")
            beginActive(it)
        }
    }

    // -------------------------------------------------------------------------
    // Reorder (Download Manager, Steam-only)
    // -------------------------------------------------------------------------

    /** Move a queued appId one place toward the front. No-op if not queued / already first. */
    fun moveUp(appId: Int) {
        synchronized(lock) {
            val order = pending.keys.toMutableList()
            val i = order.indexOf(appId)
            if (i <= 0) return
            order.removeAt(i); order.add(i - 1, appId)
            reorderLocked(order)
        }
    }

    /** Move a queued appId to the front so it starts next. No-op if not queued / already first. */
    fun moveToTop(appId: Int) {
        synchronized(lock) {
            val order = pending.keys.toMutableList()
            val i = order.indexOf(appId)
            if (i <= 0) return
            order.removeAt(i); order.add(0, appId)
            reorderLocked(order)
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Cancel [appId] wherever it is — delegates to the active engine if it's the live download, or
     * pulls it from the queue if it's waiting. For callers that hold no [DownloadControl] facade (e.g.
     * a detail page reopened onto a queued row). No-op if [appId] is neither active nor queued.
     */
    fun cancel(appId: Int) = requestCancel(appId)

    /** True if [appId] is waiting in the queue (NOT the active download). */
    fun isQueued(appId: Int): Boolean = synchronized(lock) { pending.containsKey(appId) }

    /** Number of downloads waiting behind the active one. */
    fun queuedCount(): Int = synchronized(lock) { pending.size }

    /** " · N queued" suffix for the FGS/notification while N > 0, else "". */
    fun fgsSuffix(): String {
        val n = queuedCount()
        return if (n > 0) " · $n queued" else ""
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Launch the active download's engine (outside [lock]) and register its real control. */
    private fun beginActive(p: Pending) {
        val appId = p.request.appId
        val key = "${Store.STEAM}:$appId"
        // A promoted item already has a QUEUED registry row — clear its stale position now that it's
        // becoming active. (A first-ever active item has no coordinator-seeded row; the engine seeds it.)
        DownloadRegistry.update(key) { it.copy(queuePosition = 0) }
        val real = try {
            SteamDepotDownloader.startEngine(p.request)
        } catch (t: Throwable) {
            Log.e(TAG, "startEngine($appId) threw", t)
            // Nothing is running — emitFailed marks it FAILED and (via its own onActiveTerminal hook)
            // frees the slot + starts the next item, so the queue can't wedge.
            SteamDepotDownloader.emitFailed(appId, "${t.javaClass.simpleName}: ${t.message}")
            return
        }
        var racedAction: PendingAction? = null
        synchronized(lock) {
            if (appId == activeAppId) {
                activeControl = real
                racedAction = pendingActiveAction
                pendingActiveAction = null
            } else {
                // The item terminated (cancelled) before the engine control arrived — nothing to hold.
                racedAction = null
            }
        }
        // Honour a cancel/pause that raced in before the engine control was registered.
        when (racedAction) {
            PendingAction.CANCEL -> real.cancel.run()
            PendingAction.PAUSE -> real.pause.run()
            null -> {}
        }
    }

    /** Remove and return the head of the queue (updating remaining positions). Caller holds [lock]. */
    private fun pollNextLocked(): Pending? {
        val it = pending.entries.iterator()
        if (!it.hasNext()) return null
        val head = it.next()
        it.remove()
        refreshQueuePositionsLocked()
        return head.value
    }

    /** Rebuild [pending] in [order] and refresh positions. Caller holds [lock]. */
    private fun reorderLocked(order: List<Int>) {
        val snapshot = HashMap(pending)
        pending.clear()
        for (id in order) snapshot[id]?.let { pending[id] = it }
        refreshQueuePositionsLocked()
    }

    /** Push the current 1-based queue positions into the registry rows. Caller holds [lock]. */
    private fun refreshQueuePositionsLocked() {
        var pos = 1
        for (appId in pending.keys) {
            val p = pos
            DownloadRegistry.update("${Store.STEAM}:$appId") {
                it.copy(state = DownloadState.QUEUED, queuePosition = p)
            }
            pos++
        }
    }

    private fun Pending.queuePos(): Int {
        var pos = 1
        for (id in pending.keys) { if (id == request.appId) return pos; pos++ }
        return 0
    }

    /** Seed a QUEUED registry row + a `queued` DB row (facade-wired) for a waiting request. */
    private fun seedQueuedRow(r: Request, facade: SteamDepotDownloader.DownloadControl) {
        val repo = SteamRepository.getInstance()
        val db = repo.database
        val row = try { db.getGame(r.appId) } catch (_: Throwable) { null }
        val name = row?.name?.takeIf { it.isNotBlank() } ?: "App ${r.appId}"
        val sizeHint = row?.sizeBytes ?: 0L
        // DB row so the detail page + a restart's stale-row cleanup can see it as queued. A resume
        // re-enqueue must keep its partial bytes + install dir (status-only flip); a fresh install
        // gets a clean row (bytes 0), replacing any stale complete/failed record for this appId.
        try {
            if (r.isResume && db.getDownload(r.appId) != null) db.markDownloadQueued(r.appId)
            else db.queueDownload(r.appId, sizeHint, "")
        } catch (_: Throwable) {}
        DownloadRegistry.upsert(
            DownloadEntry(
                store = Store.STEAM,
                id = r.appId.toString(),
                name = name,
                cover = r.appId.toString(),
                state = DownloadState.QUEUED,
                installTotal = sizeHint,     // size hint until the engine reports real totals
                supportsPause = true,
                pause = { facade.pause.run() },
                cancel = { facade.cancel.run() },
            ),
        )
        repo.emit("DownloadQueued:${r.appId}")
    }

    /** Cancel a still-queued item: delete its DB row + registry entry (no files), emit Cancelled. */
    private fun finishQueuedCancel(appId: Int) {
        Log.i(TAG, "cancel queued $appId — removing from queue")
        val repo = SteamRepository.getInstance()
        try { repo.database.deleteDownload(appId) } catch (_: Throwable) {}
        repo.emit("DownloadCancelled:$appId")
        val key = "${Store.STEAM}:$appId"
        DownloadRegistry.update(key) { it.copy(state = DownloadState.CANCELLED, queuePosition = 0) }
        DownloadRegistry.remove(key)
    }

    /** Hold a queued item out of line: mark it a plain PAUSED download (resume re-enqueues it). */
    private fun finishQueuedPause(appId: Int) {
        Log.i(TAG, "pause queued $appId — holding out of line")
        val repo = SteamRepository.getInstance()
        val done = try { repo.database.getDownload(appId)?.bytesDownloaded ?: 0L } catch (_: Throwable) { 0L }
        try { repo.database.markDownloadPaused(appId, done) } catch (_: Throwable) {}
        repo.emit("DownloadPaused:$appId")
        DownloadRegistry.update("${Store.STEAM}:$appId") {
            it.copy(state = DownloadState.PAUSED, queuePosition = 0)
        }
    }
}
