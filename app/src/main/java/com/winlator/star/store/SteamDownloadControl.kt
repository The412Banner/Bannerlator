package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import java.io.File

/**
 * Pause / resume / cancel of Steam downloads for any UI that has no Activity-scoped handle: the Download Manager, classic and XMB.
 *
 * The Steam screens drive a download through the [SteamDepotDownloader.DownloadControl] they got from `installApp` / `resumeApp`, held
 * in their own Activity. A registry row's `pause` / `cancel` lambdas were that same control, so they died with the run: a paused
 * row's `pause` was a no-op (the flag it sets is already set) and there was no way to resume at all, a resumed row still carried the
 * dead run's handles, a row seeded for an installed game (an update / verify of it) had none, and a download left paused by an app
 * restart had no row. Everything here is keyed by appId instead: the live download and the waiting ones are reached through
 * [DownloadQueue] (which owns the active engine's real control), a paused one is restarted with [SteamDepotDownloader.resumeApp], so it
 * works whichever screen started the download, or none (the app was restarted since). The Steam screens keep their own handles and
 * behave as before.
 */
internal object SteamDownloadControl {

    private const val TAG = "SteamDlControl"

    private fun key(appId: Int) = "${Store.STEAM}:$appId"

    /**
     * [e] with its transient handles (and `supportsPause`) wired to this seam, for a registry row the engines keep updating: the handles
     * stay valid whatever run is current, unlike the real control they replace.
     */
    fun attach(e: DownloadEntry): DownloadEntry {
        val appId = e.id.toIntOrNull() ?: return e
        return e.copy(
            supportsPause = true,
            pause = { DownloadRegistry.appContext()?.let { pauseResume(it, appId) } },
            cancel = { DownloadRegistry.appContext()?.let { cancel(it, appId) } },
        )
    }

    /** The card's Pause / Resume button: pause what runs or waits, resume what is paused. */
    fun pauseResume(ctx: Context, entry: DownloadEntry) {
        entry.id.toIntOrNull()?.let { pauseResume(ctx, it) }
    }

    /** Pause / resume [appId], deciding from the registry's CURRENT state (the card a tap came from may be a frame old). */
    fun pauseResume(ctx: Context, appId: Int) {
        val state = DownloadRegistry.get(key(appId))?.state ?: return
        when (pauseToggleFor(state)) {
            PauseToggle.PAUSE -> pause(appId)
            PauseToggle.RESUME -> resume(ctx, appId)
            PauseToggle.NONE -> Unit
        }
    }

    private fun pause(appId: Int) {
        if (DownloadQueue.isActiveOrQueued(appId)) {
            DownloadQueue.pause(appId)
            return
        }
        // The registry says it runs but no run of it exists in this process (the worker died without a terminal): settle it as a
        // plain paused download so the card offers Resume instead of sitting on a dead "Downloading".
        try {
            val db = SteamRepository.getInstance().database
            val done = db.getDownload(appId)?.bytesDownloaded ?: 0L
            db.markDownloadPaused(appId, done)
            SteamRepository.getInstance().emit("DownloadPaused:$appId")
        } catch (t: Throwable) {
            Log.w(TAG, "pause($appId): settle failed: ${t.message}")
        }
        DownloadRegistry.update(key(appId)) {
            attach(it.copy(state = DownloadState.PAUSED, queuePosition = 0, speedBps = 0L, etaSeconds = -1L))
        }
    }

    /**
     * Restart a paused download where it stopped (a fresh `resumeApp`, as the detail page's Resume does). Brings the Steam service up
     * first, as the detail page does when it opens: the engine needs that session, and after an app restart nothing else has started it.
     */
    fun resume(ctx: Context, appId: Int) {
        if (DownloadQueue.isActiveOrQueued(appId)) return   // already running, or waiting its turn
        val app = ctx.applicationContext
        try {
            if (SteamPrefs.isLoggedIn) SteamForegroundService.start(app)
        } catch (t: Throwable) {
            Log.w(TAG, "resume($appId): Steam service start failed: ${t.message}")
        }
        val tier = DownloadQueue.speedTierOf(appId, DownloadSpeedConfig.DEFAULT_TIER)
        try {
            SteamDepotDownloader.resumeApp(appId, app, tier)
        } catch (t: Throwable) {
            Log.w(TAG, "resume($appId) failed to start: ${t.message}")
            return
        }
        // The engine only touches the row once its session and product info are ready (seconds, on a cold start); show the restart now.
        // A resume that had to wait behind another download has already turned the row QUEUED, which is left alone.
        DownloadRegistry.update(key(appId)) {
            if (it.state == DownloadState.PAUSED) {
                attach(it.copy(state = DownloadState.DOWNLOADING, speedBps = 0L, etaSeconds = -1L))
            } else {
                it
            }
        }
    }

    /** The card's Cancel button. */
    fun cancel(ctx: Context, entry: DownloadEntry) {
        entry.id.toIntOrNull()?.let { cancel(ctx, it) }
    }

    /**
     * Cancel [appId] as the detail page's "Cancel and delete" does for a game that is not installed: stop the run (or take it out of the
     * queue, or, when nothing runs because it was paused, drop its row ourselves), forget the resume state and wipe the partial files.
     * An installed game whose update / verify is cancelled stays installed, its files untouched.
     */
    fun cancel(ctx: Context, appId: Int) {
        val app = ctx.applicationContext
        val repo = SteamRepository.getInstance()
        val db = repo.database
        val k = key(appId)
        val live = DownloadQueue.isActiveOrQueued(appId)
        val installed = try { db.getGame(appId)?.isInstalled == true } catch (_: Throwable) { false }
        // Read before the row goes: the download row is the only record of where a not yet installed game's partial files are.
        val partialDir = if (installed) null else try {
            db.getDownload(appId)?.installDir?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }

        if (live) {
            // The engine's own terminal (or the queue, for a waiting one) drops the DB row and the registry row.
            DownloadQueue.cancel(appId)
        } else {
            // Paused (or interrupted): nothing runs that could do it.
            try { db.deleteDownload(appId) } catch (_: Throwable) {}
            repo.emit("DownloadCancelled:$appId")
            val back = installed && !DownloadRegistry.get(k)?.installPath.isNullOrEmpty()
            if (back) {
                DownloadRegistry.update(k) {
                    it.copy(state = DownloadState.INSTALLED, pct = 100, installDone = it.installTotal, speedBps = 0L,
                        etaSeconds = -1L, queuePosition = 0, error = null)
                }
            } else {
                DownloadRegistry.update(k) { it.copy(state = DownloadState.CANCELLED, queuePosition = 0) }
                DownloadRegistry.remove(k)
            }
        }
        if (!installed) {
            // A folder another Steam client owns keeps its files; without this the library import would mark the game installed again.
            SteamLibraryImport.ignore(app, appId)
            partialDir?.let { wipePartial(app, appId, it) }
        }
    }

    /**
     * Deletes the partial files of a cancelled fresh install, off the UI thread, by the same rules as the detail page (a folder this
     * app did not create, or one another game also uses, only loses this app's own bookkeeping). Two sweeps, a moment apart: a
     * still-closing engine can write a few more files after the first.
     */
    private fun wipePartial(ctx: Context, appId: Int, dir: String) {
        Thread {
            val d = File(dir)
            val keepFiles = !SteamInstallPaths.mayDeleteWholeFolder(d) || SteamInstallPaths.folderSharedWithOtherGame(ctx, d, appId)
            fun sweep() {
                try {
                    if (!d.exists()) return
                    if (keepFiles) {
                        SteamInstallPaths.removeBookkeeping(d)
                    } else {
                        d.deleteRecursively()
                        SteamAppManifest.removeFor(d.absolutePath, appId)
                    }
                } catch (_: Throwable) {}
            }
            sweep()
            try { Thread.sleep(1500L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            sweep()
        }.apply { isDaemon = true; name = "SteamDmCancelWipe" }.start()
    }

    /**
     * Gives a registry row to every download the app was interrupted in (a restart, or a kill, between sessions): the registry is
     * memory only, so without this a paused or half-done download is not in the Download Manager at all and cannot be resumed from it.
     * They come back PAUSED, as the detail page treats them. Skips what this process already knows (a live run, a queued one, any row)
     * and a game that is installed (its leftover row is stale, the detail page clears it). Idempotent; never throws.
     */
    fun seedInterrupted() {
        try {
            val db = SteamRepository.getInstance().database
            for (row in db.getActiveDownloads()) {
                val appId = row.appId
                if (DownloadRegistry.get(key(appId)) != null) continue
                if (DownloadQueue.isActiveOrQueued(appId) || SteamDepotDownloader.isDownloading(appId)) continue
                val game = db.getGame(appId) ?: continue
                if (game.isInstalled) continue
                DownloadRegistry.upsert(attach(DownloadEntry(
                    store = Store.STEAM,
                    id = appId.toString(),
                    name = game.name?.takeIf { it.isNotBlank() } ?: "App $appId",
                    cover = appId.toString(),
                    state = DownloadState.PAUSED,
                    pct = downloadPercent(row.bytesDownloaded, row.bytesTotal),
                    installDone = row.bytesDownloaded,
                    installTotal = row.bytesTotal,
                )))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "seedInterrupted skipped: ${t.message}")
        }
    }
}
