package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadScope
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreDownloadHooks
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pause / resume for Epic downloads, driven from the Download Manager (classic and XMB) or anywhere else that holds only an appName.
 *
 * Pause STOPS the transfer but KEEPS the progress: it flags the run and trips the run's own cancel flag, [EpicDownloadManager.install]
 * stops promptly with the chunk cache (`.chunks`) and partial files on disk, and the run's cancelled branch, seeing the flag
 * ([consumePause]), parks the row as PAUSED ([parkPaused]) instead of cancelling it. Resume ([resume]) re-runs the same
 * [EpicDownloadManager.install], whose delta / resume cache continues where it stopped. The run is headless (no Activity), so it works
 * after the screen that started the download is gone, or after the app was restarted.
 *
 * A paused download is remembered in a small prefs file (appName to install dir and where it stopped) so it is still PAUSED, with its
 * Resume button, on the next launch ([seedPaused], called by [EpicLibrarySync.seed]) when partial data is still on disk. Cancel of a
 * paused row keeps the existing keep / delete choice ([EpicCancelPolicy]).
 */
internal object EpicDownloadControl {

    private const val TAG = "EpicDlControl"
    private const val PREFS_PAUSED = "epic_paused_downloads"
    private const val PREFS_EPIC = "bh_epic_prefs"

    /** appNames a pause was asked for whose run has not parked yet (or has not noticed). */
    private val pausing = ConcurrentHashMap.newKeySet<String>()

    /** appNames with a headless resume run alive, so a second tap on Resume cannot start a second one. */
    private val resuming = ConcurrentHashMap.newKeySet<String>()

    private fun key(appName: String) = "${Store.EPIC}:$appName"

    private fun pausedPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_PAUSED, Context.MODE_PRIVATE)

    private fun epicPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_EPIC, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Pause
    // -------------------------------------------------------------------------

    /**
     * Ask the running download of [appName] to pause. Does nothing unless the registry shows it DOWNLOADING with a cancel handle. The
     * run answers by calling [consumePause] in its cancelled branch.
     */
    fun pause(appName: String) {
        val e = DownloadRegistry.get(key(appName)) ?: return
        if (e.state != DownloadState.DOWNLOADING) return
        val stop = e.cancel ?: return
        pausing.add(appName)
        stop()
    }

    /** For a run's cancelled branch: true (once) if the stop was a pause, so the run parks the download instead of cancelling it. */
    fun consumePause(appName: String): Boolean = pausing.remove(appName)

    /** A fresh start (an install tap on a screen) supersedes any pause: forget the flag and the remembered state. */
    fun onFreshStart(ctx: Context, appName: String) {
        pausing.remove(appName)
        forgetPaused(ctx, appName)
    }

    /**
     * Park [appName] as PAUSED once its transfer has stopped: the row keeps its bytes and percent, the shade line goes, the handles
     * become Resume / Cancel, and the state is remembered in prefs for the next launch. [installDir] is where the files are.
     */
    fun parkPaused(ctx: Context, appName: String, installDir: String?, name: String, cover: String?) {
        val app = ctx.applicationContext
        val k = key(appName)
        val cur = DownloadRegistry.get(k)
        val dir = installDir ?: epicPrefs(app).getString("epic_dir_$appName", null)
        if (dir != null) {
            val info = EpicPausedInfo(dir, cur?.pct ?: 0, cur?.installDone ?: 0L, cur?.installTotal ?: 0L)
            pausedPrefs(app).edit().putString(appName, encodeEpicPaused(info)).apply()
        }
        if (cur == null) {
            DownloadRegistry.upsert(DownloadEntry(store = Store.EPIC, id = appName, name = name, cover = cover,
                state = DownloadState.PAUSED, supportsPause = true))
        }
        StoreDownloadHooks.markPaused(Store.EPIC, appName, pause = { resume(appName) }, cancel = { cancel(appName) })
    }

    /** True if [appName] was paused in an earlier run of the app (see [seedPaused]). */
    fun isPersistedPaused(ctx: Context, appName: String): Boolean = pausedPrefs(ctx).contains(appName)

    private fun forgetPaused(ctx: Context, appName: String) {
        val p = pausedPrefs(ctx)
        if (p.contains(appName)) p.edit().remove(appName).apply()
    }

    // -------------------------------------------------------------------------
    // The card's Pause / Resume button and Cancel of a paused row
    // -------------------------------------------------------------------------

    /** The card's Pause / Resume button, deciding from the registry's CURRENT state. */
    fun pauseResume(entry: DownloadEntry) {
        when (pauseToggleFor(DownloadRegistry.get(entry.key)?.state ?: entry.state)) {
            PauseToggle.PAUSE -> pause(entry.id)
            PauseToggle.RESUME -> resume(entry.id)
            PauseToggle.NONE -> Unit
        }
    }

    /**
     * Cancel of a PAUSED row, by the choice [EpicCancelPolicy] holds (the keep / delete dialog sets it first): delete the partial files
     * and the install record, or keep them for a later install to build on. Any other state is a running download, whose own cancel
     * handle (the run's flag) is invoked. The paused row's cancel handle is this, so a stale reference to it stays correct after a resume.
     * A paused UPDATE of an installed game is never deleted: it goes back to installed whatever was chosen (see [settleCancelled]).
     */
    fun cancel(appName: String) {
        val ctx = DownloadRegistry.appContext() ?: return
        val k = key(appName)
        val cur = DownloadRegistry.get(k) ?: return
        if (cur.state != DownloadState.PAUSED) {
            cur.cancel?.invoke()
            return
        }
        val dir = decodeEpicPaused(pausedPrefs(ctx).getString(appName, null))?.installDir
            ?: epicPrefs(ctx).getString("epic_dir_$appName", null)
        settleCancelled(ctx, appName, dir, EpicCancelPolicy.consumeDeleteOnCancel(appName))
    }

    /**
     * Ends a cancelled download of [appName] whose files are in [dir], by [epicCancelOutcome]: an update of an installed game goes back
     * to INSTALLED (its files are the game, never deleted, and the row loses its Pause / Resume / Cancel handles as a Steam one does);
     * a fresh install deletes its partial files and install record when [deleteChosen], else keeps them for a later install.
     */
    private fun settleCancelled(ctx: Context, appName: String, dir: String?, deleteChosen: Boolean) {
        val installed = EpicInstallState.isInstalled(ctx, appName)
        forgetPaused(ctx, appName)
        pausing.remove(appName)
        val installPath = dir ?: epicPrefs(ctx).getString("epic_dir_$appName", null)
        when (epicCancelOutcome(installed, deleteChosen)) {
            EpicCancelOutcome.RESTORE_INSTALLED -> {
                if (installPath == null) {
                    StoreDownloadHooks.markCancelled(Store.EPIC, appName)
                } else {
                    StoreDownloadHooks.markInstalled(Store.EPIC, appName, installPath, epicPrefs(ctx).getLong("epic_size_$appName", 0L))
                    DownloadRegistry.update(key(appName)) {
                        it.copy(supportsPause = false, pause = null, cancel = null, speedBps = 0L, etaSeconds = -1L,
                            queuePosition = 0, error = null)
                    }
                }
            }
            EpicCancelOutcome.DELETE_PARTIAL -> {
                StoreDownloadHooks.markCancelled(Store.EPIC, appName)
                DownloadScope.io.launch {
                    try { if (dir != null) EpicDownloadManager.deleteDir(File(dir)) } catch (_: Exception) {}
                    EpicInstallState.purge(ctx, appName)
                }
            }
            EpicCancelOutcome.KEEP_PARTIAL -> StoreDownloadHooks.markCancelled(Store.EPIC, appName)
        }
    }

    // -------------------------------------------------------------------------
    // Resume
    // -------------------------------------------------------------------------

    /**
     * Restart the paused download of [appName] where it stopped: the same [EpicDownloadManager.install] call as the screens make, run
     * headless on [DownloadScope], whose chunk cache and file checks skip everything already fetched. No-op unless the row is PAUSED
     * (or absent) and no resume run is alive.
     */
    fun resume(appName: String) {
        val ctx = DownloadRegistry.appContext() ?: return
        val k = key(appName)
        val cur = DownloadRegistry.get(k)
        if (cur != null && cur.state != DownloadState.PAUSED) return
        if (!resuming.add(appName)) return

        val detail = EpicLibrarySync.cachedDetail(ctx, appName)
        val title = detail?.title?.takeIf { it.isNotEmpty() } ?: cur?.name?.takeIf { it.isNotEmpty() } ?: appName
        val cover = cur?.cover ?: detail?.artCover?.takeIf { it.isNotEmpty() }
        val saved = decodeEpicPaused(pausedPrefs(ctx).getString(appName, null))
        val dir = saved?.installDir
            ?: epicPrefs(ctx).getString("epic_dir_$appName", null)
            ?: File(File(ctx.filesDir, "imagefs/epic_games"), epicFolderName(title, appName)).absolutePath
        val installDir = File(dir)
        val ns = detail?.namespace.orEmpty()
        val cat = detail?.catalogItemId.orEmpty()

        EpicCancelPolicy.clear(appName)
        pausing.remove(appName)
        val cancelled = AtomicBoolean(false)
        StoreDownloadHooks.registerDownload(
            store = Store.EPIC,
            id = appName,
            name = title,
            cover = cover,
            supportsPause = true,
            installTotal = cur?.installTotal ?: saved?.total ?: 0L,
            pause = { pause(appName) },
            cancel = { cancelled.set(true) },
        )
        // The card keeps where it stopped until the first tick of the new run.
        DownloadRegistry.update(k) {
            it.copy(pct = cur?.pct ?: saved?.pct ?: 0, installDone = cur?.installDone ?: saved?.done ?: 0L)
        }

        DownloadScope.io.launch {
            // Until the transfer starts, a failure only refuses this Resume: the download stays paused (with Resume) and says why.
            var transferStarted = false
            try {
                if (ns.isEmpty() || cat.isEmpty()) {
                    failParked(ctx, appName, installDir, title, cover, "Open this game's page in the Epic store once, then resume")
                    return@launch
                }
                val token = EpicCredentialStore.getValidAccessToken(ctx)
                if (token == null) { failParked(ctx, appName, installDir, title, cover, "Login required"); return@launch }
                val manifestJson = EpicApiClient.getManifestApiJson(token, ns, cat, appName)
                if (manifestJson == null) { failParked(ctx, appName, installDir, title, cover, "Failed to fetch manifest"); return@launch }

                epicPrefs(ctx).edit().putString("epic_dir_$appName", installDir.absolutePath).apply()
                val installTags = EpicInstallTags.tagsForCurrentContainer(ctx)
                val tracker = EpicProgressTracker(appName)
                transferStarted = true
                val ok = EpicDownloadManager.install(
                    ctx, manifestJson, token, installDir.absolutePath, installTags, cancelled,
                    object : EpicDownloadManager.ProgressCallback {
                        override fun onBytes(bytesDone: Long, bytesTotal: Long, speedBps: Long, etaSeconds: Long) {
                            tracker.onBytes(bytesDone, bytesTotal, speedBps, etaSeconds)
                        }

                        override fun onProgress(message: String?, pct: Int) {
                            if (!cancelled.get()) tracker.tick(pct)
                        }
                    },
                )

                if (cancelled.get()) {
                    if (consumePause(appName)) {
                        parkPaused(ctx, appName, installDir.absolutePath, title, cover)
                        return@launch
                    }
                    settleCancelled(ctx, appName, installDir.absolutePath, EpicCancelPolicy.consumeDeleteOnCancel(appName))
                    return@launch
                }
                if (!ok) { fail(ctx, appName, EpicDownloadManager.withFailureReason("Download failed")); return@launch }

                EpicInstallState.rememberVersion(ctx, appName, manifestJson)
                val exeFiles = mutableListOf<File>()
                AmazonLaunchHelper.collectExe(installDir, exeFiles)
                if (exeFiles.isEmpty()) { fail(ctx, appName, "No executable found"); return@launch }
                val lowerTitle = title.lowercase()
                exeFiles.sortWith { a, b ->
                    AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
                }
                // An update keeps the exe the user chose; a fresh install records the best-scored one (as the screens do).
                val p = epicPrefs(ctx)
                if (p.getString("epic_exe_$appName", null) == null) {
                    p.edit().putString("epic_exe_$appName", exeFiles[0].absolutePath).apply()
                }
                EpicEosDetector.scanAsync(ctx, appName, installDir, null)
                forgetPaused(ctx, appName)
                StoreDownloadHooks.markInstalled(Store.EPIC, appName, installDir.absolutePath, p.getLong("epic_size_$appName", 0L))
            } catch (e: Exception) {
                Log.w(TAG, "resume($appName) failed: ${e.message}")
                val msg = e.message ?: "Unknown error"
                if (!transferStarted) failParked(ctx, appName, installDir, title, cover, msg)
                else if (!cancelled.get()) fail(ctx, appName, msg)
            } finally {
                resuming.remove(appName)
            }
        }
    }

    private fun fail(ctx: Context, appName: String, msg: String) {
        forgetPaused(ctx, appName)
        StoreDownloadHooks.markFailed(Store.EPIC, appName, msg)
    }

    /** A Resume that could not start the transfer: the download goes back to PAUSED (still resumable) and the card shows [msg]. */
    private fun failParked(ctx: Context, appName: String, installDir: File, title: String, cover: String?, msg: String) {
        parkPaused(ctx, appName, installDir.absolutePath, title, cover)
        DownloadRegistry.update(key(appName)) { it.copy(error = msg) }
    }

    /** The install folder name the Epic screens derive from a title. */
    private fun epicFolderName(title: String, appName: String): String {
        val s = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
        return if (s.isEmpty()) "epic_${appName.hashCode()}" else s
    }

    // -------------------------------------------------------------------------
    // After a restart
    // -------------------------------------------------------------------------

    /**
     * Brings back the downloads paused in an earlier run of the app as PAUSED rows with a Resume button, when partial data is still on
     * disk (a download whose files are gone is forgotten). Skips what this process already knows as active. Called by
     * [EpicLibrarySync.seed], after the installed games are seeded, so a paused update of an installed game shows as paused. Never throws.
     */
    fun seedPaused(ctx: Context) {
        try {
            val p = pausedPrefs(ctx)
            for ((appName, raw) in p.all) {
                val k = key(appName)
                if (DownloadRegistry.isActive(k)) continue
                val info = decodeEpicPaused(raw as? String)
                if (info == null || !epicHasPartialData(File(info.installDir))) {
                    p.edit().remove(appName).apply()
                    continue
                }
                val detail = EpicLibrarySync.cachedDetail(ctx, appName)
                DownloadRegistry.upsert(DownloadEntry(
                    store = Store.EPIC,
                    id = appName,
                    name = detail?.title?.takeIf { it.isNotEmpty() } ?: appName,
                    cover = detail?.artCover?.takeIf { it.isNotEmpty() },
                    state = DownloadState.PAUSED,
                    pct = info.pct,
                    installDone = info.done,
                    installTotal = info.total,
                    supportsPause = true,
                    pause = { resume(appName) },
                    cancel = { cancel(appName) },
                ))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "seedPaused skipped: ${t.message}")
        }
    }
}
