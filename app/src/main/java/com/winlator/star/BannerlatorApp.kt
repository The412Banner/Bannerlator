package com.winlator.star

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.winlator.star.perf.PerfRevertRegistry
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.perf.TempWatchdog
import com.winlator.star.store.SteamPrefs
import com.winlator.star.store.SteamRepository

/**
 * The app's Application. Its sole current job is standing up the power-user performance safety core
 * as early as possible: probe root, repair any sysfs snapshot a hard kill left dirty last session,
 * and revert privileged writes when the WHOLE app (not just one activity) goes to background.
 *
 * All of this is wrapped so a failure here can never take down app startup — the perf tier is
 * strictly additive.
 */
class BannerlatorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Crash reporting first and on its own: it must be installed before anything below can throw,
        // and it must not be inside the try/catch that swallows perf-init failures. It chains to the
        // handler already in place, so the perf safety nets installed just after still run.
        try {
            if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("enable_crash_reports", true)) {
                com.winlator.star.core.CrashReporter.install(this)
            }
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "crash reporter not installed", t)
        }

        // Exit-reason auto-save (opt-in, off by default): if the previous process died — including a
        // NATIVE crash that never reached logcat or the Java crash reporter — file a report now while
        // the system still has the record. Off the main thread; a failure here must never block start.
        try {
            if (com.winlator.star.core.ExitReasonReporter.isSupported() &&
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(com.winlator.star.core.ExitReasonReporter.PREF_AUTOSAVE, false)) {
                Thread {
                    try { com.winlator.star.core.ExitReasonReporter.captureToFile(this) }
                    catch (t: Throwable) { Log.w("BannerlatorApp", "exit-reason autosave failed", t) }
                }.start()
            }
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "exit-reason autosave not scheduled", t)
        }

        // Reclaim stale update installers from the external cache. On a fresh cold start there is
        // never a legitimate in-flight download, so clearing here is safe; it also recovers space
        // left by pre-prune builds (their update never cleaned up) and sweeps OS-killed partials.
        // Off the main thread; a failure here must never block start.
        try {
            Thread {
                try { com.winlator.star.core.UpdateManager.pruneUpdateCacheAtStartup(this) }
                catch (t: Throwable) { Log.w("BannerlatorApp", "update-cache prune failed", t) }
            }.start()
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "update-cache prune not scheduled", t)
        }

        // Steam: make the connection status live app-wide. Init the session prefs synchronously (cheap;
        // the status pill in the top bar + drawer reads SteamPrefs.isLoggedIn on the first frame), then
        // OFF the main thread build the repository and — if the user has ever signed in — auto-connect,
        // so the pill is live at launch instead of only after the store is first opened (this is also
        // why the game-launch screen used to report "not logged into Steam"). Best-effort: every step is
        // wrapped so it can never block or crash startup.
        try {
            com.winlator.star.store.SteamPrefs.init(this)
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "steam prefs init failed", t)
        }
        try {
            Thread {
                try {
                    // Native Rust Steam engine (Phase 0): load libblsteam.so and bind one JNI export
                    // so a packaging/symbol regression is one loud logcat line ("BL_STEAM: ...") at
                    // boot. With the hidden use_rust_steam_engine flag off this is its ONLY effect.
                    com.winlator.star.store.blsteam.BlSteamClient.probe()
                    val repo = com.winlator.star.store.SteamRepository.getInstance()
                    repo.initialize(this)                 // sets appContext + prefs (idempotent, synchronized)
                    if (com.winlator.star.store.SteamPrefs.isLoggedIn) repo.reconnectNow()
                } catch (t: Throwable) {
                    Log.w("BannerlatorApp", "steam auto-connect failed", t)
                }
            }.start()
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "steam auto-connect not scheduled", t)
        }

        try {
            // Restore-if-dirty + root probe + crash/shutdown safety nets, BEFORE anything touches a node.
            RootManager.onAppStartup(this)
            TempWatchdog.init(this)
            PerformanceSettings.init(this) // global defaults both perf surfaces bind to
            // No-root Samsung Galaxy Performance SDK path (dormant off Samsung / without the SDK jar).
            com.winlator.star.perf.galaxy.GalaxyPerfManager.initialize(this)

            // App-level background => revert privileged writes (a single game Activity stopping is
            // handled in XServerDisplayActivity; this catches process-wide backgrounding).
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // Reconnect Steam the moment the app returns to the foreground if the session dropped
                    // while backgrounded (auto-reconnect gives up after a few tries, so friends/chat would
                    // otherwise sit disconnected until something else pokes it). Guarded to OFFLINE only, so
                    // a SteamLite game holding the session (SIGNED_IN_ELSEWHERE) is never tugged.
                    try {
                        if (SteamPrefs.isLoggedIn) {
                            val repo = SteamRepository.getInstance()
                            if (repo.status == SteamRepository.SteamStatus.OFFLINE) repo.reconnectNow()
                        }
                    } catch (t: Throwable) {
                        Log.w("BannerlatorApp", "foreground reconnect failed", t)
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    try { PerfRevertRegistry.revertAll() } catch (t: Throwable) {
                        Log.w("BannerlatorApp", "background revert failed", t)
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w("BannerlatorApp", "perf safety-core init failed", t)
        }
    }
}
