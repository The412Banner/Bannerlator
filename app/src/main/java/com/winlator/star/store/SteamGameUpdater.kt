package com.winlator.star.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Update-on-launch for the RealSteam (SteamLite / VAC) launch path.
 *
 * ── Why ──────────────────────────────────────────────────────────────────────
 * Our lightweight SteamLite agent logs into real Steam and `LaunchApp`s the game, but — unlike the
 * genuine Steam client — it does NOT run Steam's pre-launch content update. So a game whose installed
 * files are an OLDER build than the current public build launches stale. Latest-only online titles
 * (device-observed: Brawlhalla 291550 → "INCORRECT VERSION — Restart Steam to get the latest version")
 * then block multiplayer. [ensureCurrentBuild] closes that gap: before a RealSteam launch it makes sure
 * the game is on the current public build, running the existing depot downloader (delta-only, in place)
 * when it isn't.
 *
 * ── Staleness detection ──────────────────────────────────────────────────────
 * There is no per-install build column in [SteamDatabase] (`markInstalled` records only is_installed /
 * install_dir / size_bytes, and `depot_manifests` is re-upserted from PICS on every library sync so it
 * tracks the LIVE target, not what's on disk). So we record the build we installed ourselves in a small
 * marker file ([BUILD_MARKER_REL]) inside the game's install dir — written both here after a successful
 * update AND by [SteamDepotDownloader] on every completed install (so normal store installs also stamp
 * their build and get the cheap compare going forward).
 *
 *   live build  = the selected branch's `buildid` in the `steam_branches` table (parsed from PICS at
 *                 library-sync / add time). Accessor: [SteamRepository.getBranches] → BranchRow.buildId.
 *   installed    = the branch|buildId recorded in the marker (absent for pre-feature installs → 0).
 *
 * Fast path (NO network): recorded build == live build AND the game is installed on disk → done(CURRENT)
 * with ~no delay. Otherwise (behind, or install/live build unknown) we run a delta update pass. A
 * pre-feature install with no marker (the device Brawlhalla case) has installedBuild==0, so it runs the
 * delta pass once, self-heals, and stamps the marker — every later launch is then the fast no-op.
 *
 * ── Robustness ───────────────────────────────────────────────────────────────
 * We never hard-block a launch. If the live build can't be determined (no branch data) or Steam is
 * offline / the session can't be restored, we return [Result.OFFLINE_UNKNOWN] and let the caller offer
 * "launch anyway (stale)" vs. abort. A cheap session check up front keeps an offline launch snappy
 * instead of hanging behind the depot downloader's ~60s session-recovery retries.
 *
 * Only the RealSteam path calls this — Goldberg and Raw launches are untouched.
 */
object SteamGameUpdater {

    private const val TAG = "SteamGameUpdater"

    /** Records `<branch>|<buildId>` of the copy on disk, inside the game's install dir. */
    private const val BUILD_MARKER_REL = ".bannerlator_build"

    /** How long to wait for a token re-logon when the session isn't live before the update pass. */
    private const val SESSION_WAIT_MS = 10_000L

    /** Terminal outcome of an [ensureCurrentBuild] check. */
    enum class Result {
        /** Already on the live build (or nothing to check) — launch immediately. */
        CURRENT,
        /** Was behind/incomplete; the delta update finished — launch. */
        UPDATED,
        /** Couldn't verify the latest build (no branch data / Steam offline) — caller should offer
         *  "launch anyway (may be stale)" rather than hard-blocking. */
        OFFLINE_UNKNOWN,
        /** The update pass failed (see message) — caller should offer "launch anyway" or abort. */
        FAILED,
        /** The user cancelled the check/update — stay put, do not launch. */
        CANCELLED,
    }

    /** Progress on the main thread. [fraction] < 0 = indeterminate ("checking"); 0..1 while updating. */
    fun interface ProgressCallback {
        fun onProgress(fraction: Float, label: String)
    }

    /** Terminal result on the main thread. */
    fun interface DoneCallback {
        fun onDone(result: Result, message: String)
    }

    /**
     * Cancels an in-flight check/update. Safe to call any time and from any thread; a cancel that lands
     * before the download starts is honoured at the next checkpoint, and one that lands after is
     * propagated to the running [SteamDepotDownloader.DownloadControl].
     */
    class UpdateHandle internal constructor() {
        private val cancelled = AtomicBoolean(false)
        internal val controlRef = AtomicReference<SteamDepotDownloader.DownloadControl?>(null)
        val isCancelled: Boolean get() = cancelled.get()
        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                controlRef.get()?.let { try { it.cancel.run() } catch (_: Throwable) {} }
            }
        }
    }

    /**
     * Ensure [appId] is on the current build for its selected branch before a RealSteam launch.
     * Runs entirely off the main thread; [progress]/[done] are posted back to the main thread.
     * Returns immediately with an [UpdateHandle] for cancellation.
     */
    fun ensureCurrentBuild(
        context: Context,
        appId: Int,
        progress: ProgressCallback,
        done: DoneCallback,
    ): UpdateHandle {
        val appCtx = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val handle = UpdateHandle()
        val postProgress: (Float, String) -> Unit = { f, l -> main.post { progress.onProgress(f, l) } }
        val postDone: (Result, String) -> Unit = { r, m -> main.post { done.onDone(r, m) } }

        Thread({
            try {
                runCheck(appCtx, appId, handle, postProgress, postDone)
            } catch (t: Throwable) {
                Log.w(TAG, "ensureCurrentBuild($appId) failed", t)
                postDone(Result.FAILED, t.message ?: "Update check failed")
            }
        }, "steam-update-$appId").start()

        return handle
    }

    // -------------------------------------------------------------------------
    // Decision logic (worker thread)
    // -------------------------------------------------------------------------

    private fun runCheck(
        ctx: Context,
        appId: Int,
        handle: UpdateHandle,
        postProgress: (Float, String) -> Unit,
        postDone: (Result, String) -> Unit,
    ) {
        // Not a resolvable Steam appId (custom import etc.) — nothing to check, let it launch.
        if (appId <= 0) { postDone(Result.CURRENT, ""); return }

        postProgress(-1f, "Checking for updates…")

        val repo = SteamRepository.getInstance()
        val db = repo.database
        val row = try { db.getGame(appId) } catch (_: Throwable) { null }
        if (row == null) {
            // We don't have this game in the DB — can't verify a build. Launch as-is.
            Log.i(TAG, "app $appId not in DB — skipping update check")
            postDone(Result.CURRENT, ""); return
        }

        val branch = try { SteamPrefs.getSelectedBranch(appId) } catch (_: Throwable) { "public" }
        val liveBuild = liveBuildId(repo, appId, branch)
        val installDir = installDirOf(ctx, row)
        val installedBuild = readInstalledBuild(installDir, branch)
        val filesPresent = installDir.isDirectory && (installDir.listFiles()?.isNotEmpty() == true)

        // Fast no-op (no network): recorded build matches the live build and the files are on disk.
        if (liveBuild > 0L && installedBuild > 0L && installedBuild == liveBuild &&
            row.isInstalled && filesPresent) {
            Log.i(TAG, "app $appId already on live build $liveBuild ($branch) — no update needed")
            postDone(Result.CURRENT, "Already up to date"); return
        }

        if (handle.isCancelled) { postDone(Result.CANCELLED, "Cancelled"); return }

        // Can't determine the live build (branch data never synced). Don't run stale silently, but don't
        // hard-block — hand it back so the UI can offer launch-anyway.
        if (liveBuild <= 0L) {
            Log.i(TAG, "app $appId live build unknown (no branch data) — can't verify")
            postDone(Result.OFFLINE_UNKNOWN,
                "Couldn't check for the latest version (no Steam data for this game yet)."); return
        }

        // We know the live build and are NOT confirmed current → a delta update is required. That needs a
        // live session; check cheaply first so an offline launch fails fast instead of hanging on the
        // downloader's session-recovery retries behind the modal.
        postProgress(-1f, "Connecting to Steam…")
        val online = repo.isLoggedIn || repo.ensureLoggedIn(SESSION_WAIT_MS)
        if (!online) {
            Log.i(TAG, "app $appId behind/incomplete but Steam offline — deferring to user")
            postDone(Result.OFFLINE_UNKNOWN,
                "Steam is offline — couldn't update ${row.name} to the latest version."); return
        }
        if (handle.isCancelled) { postDone(Result.CANCELLED, "Cancelled"); return }

        Log.i(TAG, "app $appId updating: installedBuild=$installedBuild → liveBuild=$liveBuild ($branch)")
        runUpdatePass(ctx, appId, row.name, branch, installDir, handle, postProgress, postDone)
    }

    /**
     * Run [SteamDepotDownloader.installApp] as an in-place delta update to the current build, listening
     * on the [SteamRepository] event stream for its terminal event. `installApp` re-resolves the branch's
     * current manifests from the CM and pulls only the changed/missing chunks against the existing install
     * dir, so an already-current game completes fast and a behind game downloads just the delta.
     */
    private fun runUpdatePass(
        ctx: Context,
        appId: Int,
        gameName: String,
        branch: String,
        installDir: File,
        handle: UpdateHandle,
        postProgress: (Float, String) -> Unit,
        postDone: (Result, String) -> Unit,
    ) {
        val repo = SteamRepository.getInstance()
        val finished = AtomicBoolean(false)
        val listenerRef = AtomicReference<SteamRepository.SteamEventListener?>(null)

        postProgress(0f, "Updating $gameName…")

        val listener = SteamRepository.SteamEventListener { event ->
            try {
                if (event.startsWith("DownloadProgress:")) {
                    val parts = event.split(":")
                    if (parts.getOrNull(1)?.toIntOrNull() == appId) {
                        val iDone = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        val iTotal = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                        val frac = if (iTotal > 0L) (iDone.toDouble() / iTotal).toFloat().coerceIn(0f, 1f) else 0f
                        postProgress(frac, "Updating $gameName… ${(frac * 100).toInt()}%")
                    }
                } else if (event.startsWith("DownloadComplete:")) {
                    if (event.substringAfter("DownloadComplete:").toIntOrNull() == appId &&
                        finished.compareAndSet(false, true)) {
                        listenerRef.get()?.let { repo.removeListener(it) }
                        recordInstalledBuild(ctx, appId, installDir, branch)  // stamp the now-current build
                        postDone(Result.UPDATED, "Updated to the latest version")
                    }
                } else if (event.startsWith("DownloadFailed:")) {
                    val parts = event.split(":")
                    if (parts.getOrNull(1)?.toIntOrNull() == appId &&
                        finished.compareAndSet(false, true)) {
                        listenerRef.get()?.let { repo.removeListener(it) }
                        postDone(Result.FAILED, parts.drop(2).joinToString(":").ifBlank { "Update failed" })
                    }
                } else if (event.startsWith("DownloadCancelled:")) {
                    if (event.substringAfter("DownloadCancelled:").toIntOrNull() == appId &&
                        finished.compareAndSet(false, true)) {
                        listenerRef.get()?.let { repo.removeListener(it) }
                        postDone(Result.CANCELLED, "Update cancelled")
                    }
                }
            } catch (_: Throwable) { /* one bad event line must never kill the listener */ }
        }
        listenerRef.set(listener)
        repo.addListener(listener)

        // Honour a cancel that arrived during the session-check window before we start the download.
        if (handle.isCancelled) {
            if (finished.compareAndSet(false, true)) {
                repo.removeListener(listener); postDone(Result.CANCELLED, "Cancelled")
            }
            return
        }

        val control = try {
            SteamDepotDownloader.installApp(appId, ctx, DownloadSpeedConfig.DEFAULT_TIER, false)
        } catch (t: Throwable) {
            if (finished.compareAndSet(false, true)) {
                repo.removeListener(listener)
                postDone(Result.FAILED, t.message ?: "Couldn't start the update")
            }
            return
        }
        handle.controlRef.set(control)
        // A cancel racing the installApp call: propagate to the freshly-created download.
        if (handle.isCancelled) { try { control.cancel.run() } catch (_: Throwable) {} }
    }

    // -------------------------------------------------------------------------
    // Build-id marker + live-build accessor
    // -------------------------------------------------------------------------

    /**
     * Live build id of [appId]'s [branch] from the `steam_branches` table (public-branch fallback).
     * 0 = unknown (no branch data parsed yet). This is the exact accessor the update check compares
     * against: [SteamRepository.getBranches] → the matching [SteamDatabase.BranchRow.buildId].
     */
    private fun liveBuildId(repo: SteamRepository, appId: Int, branch: String): Long {
        return try {
            val branches = repo.getBranches(appId)
            branches.firstOrNull { it.branchName == branch }?.buildId
                ?: branches.firstOrNull { it.branchName == "public" }?.buildId
                ?: 0L
        } catch (_: Throwable) { 0L }
    }

    /** Read the recorded install build for [branch], or 0 if absent / for a different branch. */
    private fun readInstalledBuild(installDir: File, branch: String): Long {
        return try {
            val f = File(installDir, BUILD_MARKER_REL)
            if (!f.isFile) return 0L
            val txt = f.readText().trim()
            val sep = txt.indexOf('|')
            if (sep <= 0) return 0L
            if (txt.substring(0, sep) != branch) return 0L   // stamped on another branch → not comparable
            txt.substring(sep + 1).trim().toLongOrNull() ?: 0L
        } catch (_: Throwable) { 0L }
    }

    /**
     * Stamp the install dir with the branch's current live build. Called after any completed install
     * (here after an update pass, and from [SteamDepotDownloader] after a normal install) so the cheap
     * build-id compare works on the next launch. Best-effort; a failure just means the next launch
     * re-verifies. No-op when the live build is unknown (nothing meaningful to record).
     */
    @JvmStatic
    fun recordInstalledBuild(context: Context, appId: Int, installDir: File, branch: String) {
        try {
            val build = liveBuildId(SteamRepository.getInstance(), appId, branch)
            if (build <= 0L) return
            if (!installDir.isDirectory) return
            File(installDir, BUILD_MARKER_REL).writeText("$branch|$build")
            Log.i(TAG, "recorded installed build $build ($branch) for app $appId")
        } catch (t: Throwable) {
            Log.w(TAG, "recordInstalledBuild($appId) failed: ${t.message}")
        }
    }

    /** Resolve the on-disk install dir: the stored install_dir when set, else the default derived path. */
    private fun installDirOf(ctx: Context, row: SteamDatabase.GameRow): File {
        val stored = row.installDir
        if (!stored.isNullOrBlank()) return File(stored)
        val safe = row.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return File(File(ctx.filesDir, "imagefs/steam_games"), safe)
    }
}
