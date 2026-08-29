package com.winlator.star.store

import android.content.Context
import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * In-game achievement pill producer for the SteamLite / RealSteam launch mode.
 *
 * In Goldberg mode an offline emulator writes unlocks into a local `achievements.json` that
 * [AchievementWatcher] diffs. In SteamLite mode the game runs against a genuine Steam client driven
 * by our headless agent (`steam.exe`), so unlocks go straight to the REAL Steam server — there is no
 * local achievements file to diff, and the gold pill never fired.
 *
 * The agent closes that gap: on EVERY unlock (a natural in-game one OR an on-demand fire) it writes
 * ONE file into the container's `C:\wn-achievement-events\` — in the Wine prefix:
 *   `<container>/.wine/drive_c/wn-achievement-events/<something>.txt`
 * Each file is a single TAB-separated line:
 *   `<appId>\t<apiName>\t<unixTimeSeconds>\t<source>`   (source = `fire` | `game`)
 * Files are CONSUMED ONCE: this reads it, pops the SAME gold pill the Goldberg path pops
 * ([SteamAchievementStore.surfaceUnlockPill]), then DELETES it. A burst = several files.
 *
 * MVP scope: pop-up ONLY. No record / pending-queue / launch-reconcile / on-connect sweep — those
 * are a deliberate later phase, so this class intentionally does not sweep files already present at
 * arm time either (fires are intentional; the observer catches every new one).
 *
 * Best-effort + fully guarded (logs to "BH_STEAM_ACHV"): nothing here may crash or block the game.
 * The FileObserver callback runs on the process-shared observer thread, so each file is handed to a
 * private single-thread executor (parse + DB lookup off the shared thread, and serialized so a
 * duplicate event for one file can't double-toast). Name/icon resolution + the toast are NOT
 * duplicated here — they live once in [SteamAchievementStore.surfaceUnlockPill].
 */
class SteamLiteAchievementWatcher {

    companion object {
        private const val TAG = "BH_STEAM_ACHV"
        // The agent writes with fopen/fclose (→ CLOSE_WRITE); MOVED_TO also covers a temp+rename
        // writer. Not a `const` — a bitwise-or of Java static fields, not a compile-time constant.
        private val MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

        /**
         * The SteamLite agent's event folder under [containerRootDir]'s Wine prefix — the sibling of
         * the Goldberg GSE store, `C:\wn-achievement-events\`. Single source of truth for the path.
         */
        @JvmStatic
        fun eventsDir(containerRootDir: File): File =
            File(containerRootDir, ".wine/drive_c/wn-achievement-events")
    }

    private var appContext: Context? = null
    private var appId: Int = 0
    private var dirPath: String = ""
    private var observer: FileObserver? = null
    private var executor: ExecutorService? = null
    @Volatile private var started = false

    /**
     * Begin watching the SteamLite event folder. Call once container + appId are known, only for a
     * genuine-Steam shortcut running with Goldberg OFF (real Steam). Idempotent-ish: a second call
     * restarts fresh. Creates the folder if absent (a FileObserver on a missing dir silently no-ops).
     * Never throws.
     */
    @Synchronized
    fun start(ctx: Context, appId: Int, containerRootDir: File?) {
        try {
            stop()
            if (appId <= 0 || containerRootDir == null) return
            this.appContext = ctx.applicationContext
            this.appId = appId

            val dir = eventsDir(containerRootDir)
            try { dir.mkdirs() } catch (_: Throwable) {} // FileObserver on a missing dir silently no-ops
            dirPath = dir.absolutePath

            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "BH-SteamLiteAchvWatch").apply { isDaemon = true }
            }
            val obs = object : FileObserver(dirPath, MASK) {
                override fun onEvent(event: Int, path: String?) {
                    val name = path ?: return
                    // Never let a callback crash the process-shared observer thread.
                    try { consume(File(dirPath, name)) } catch (_: Throwable) {}
                }
            }
            observer = obs
            started = true
            obs.startWatching()
            Log.i(TAG, "steamlite: watching appId=$appId under $dirPath")
        } catch (t: Throwable) {
            Log.w(TAG, "steamlite: start failed", t)
            try { stop() } catch (_: Throwable) {}
        }
    }

    /** Stop the observer + its executor and clear state. Safe to call repeatedly / when never started. */
    @Synchronized
    fun stop() {
        started = false
        try { observer?.stopWatching() } catch (_: Throwable) {}
        observer = null
        try { executor?.shutdownNow() } catch (_: Throwable) {}
        executor = null
        appContext = null
        appId = 0
        dirPath = ""
    }

    /**
     * Hand one event [file] to the private single-thread executor: read+parse its single TAB-separated
     * line, pop the gold pill, then DELETE the file (consume-once). Runs off the shared observer
     * thread and serialized, so a duplicate event for the same file finds it already gone and skips.
     * A malformed / partial line is logged, skipped, and still deleted so it can't linger or replay.
     */
    private fun consume(file: File) {
        val exec = executor ?: return
        try {
            exec.execute {
                if (!started) return@execute
                val ctx = appContext ?: return@execute
                try {
                    if (!file.isFile) return@execute // already consumed by a prior event, or gone
                    // TAB-separated: <appId>\t<apiName>\t<unixTimeSeconds>\t<source>. Only the first two
                    // are needed to pop the pill; the rest are informational (record is a later phase).
                    val line = file.readText().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    val parts = line.split('\t')
                    val apiName = parts.getOrNull(1)?.trim().orEmpty()
                    if (apiName.isEmpty()) {
                        Log.w(TAG, "steamlite: skip malformed event '${file.name}': '$line'")
                        return@execute
                    }
                    // Prefer the appId the agent stamped into the file; fall back to the launched appId.
                    val evAppId = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: appId
                    val name = SteamAchievementStore.surfaceUnlockPill(ctx, evAppId, apiName)
                    Log.i(TAG, "steamlite: unlocked $apiName ($name) appId=$evAppId")
                } catch (t: Throwable) {
                    Log.w(TAG, "steamlite: consume failed for ${file.name}", t)
                } finally {
                    // Consume-once: drop the file whether we toasted or skipped it, so a burst/duplicate
                    // event can't re-process it and the folder never accumulates.
                    try { file.delete() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) { /* executor shutting down */ }
    }
}
