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

        try {
            // Restore-if-dirty + root probe + crash/shutdown safety nets, BEFORE anything touches a node.
            RootManager.onAppStartup(this)
            TempWatchdog.init(this)
            PerformanceSettings.init(this) // global defaults both perf surfaces bind to

            // App-level background => revert privileged writes (a single game Activity stopping is
            // handled in XServerDisplayActivity; this catches process-wide backgrounding).
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
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
