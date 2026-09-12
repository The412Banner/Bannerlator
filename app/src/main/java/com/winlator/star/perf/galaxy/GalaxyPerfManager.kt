package com.winlator.star.perf.galaxy

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Entry point for Samsung Galaxy Performance SDK control.
 *
 * Named [GalaxyPerfManager] (not "PowerManager") to avoid colliding with android.os.PowerManager
 * in the Java call sites.
 *
 * Lifecycle, mirrored onto the app's existing perf-tier seams:
 *  - [initialize] once from BannerlatorApp.onCreate (alongside PerformanceSettings.init).
 *  - [start] when a game's environment finishes starting (XServerDisplayActivity, next to
 *    TempWatchdog.start) — loads that container's saved profile and applies it.
 *  - [pause] / [resume] from the in-game activity's onPause/onResume — releases the boost while
 *    backgrounded (the guest is frozen anyway) and re-applies on return.
 *  - [stop] on environment teardown (onDestroy, next to PerfRevertRegistry.revertAll).
 *
 * Every SDK call is funnelled through a single worker thread: it keeps the socket work off the
 * main thread and serialises requests so two profile applies can never race.
 *
 * The feature is entirely dormant unless the device is a Samsung Galaxy AND the Galaxy
 * Performance SDK jar is present in the build (see app/libs/GalaxyPerf/README).
 */
object GalaxyPerfManager {

    private const val TAG = "GalaxyPerf"
    private const val PROFILE_FILE_NAME = "galaxy_power.json"
    private const val PREFS_NAME = "galaxy_perf"
    private const val KEY_ENABLED = "enabled"

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "galaxy-perf").apply { isDaemon = true }
    }

    @Volatile
    private var driver: GalaxyPerfDriver? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var containerDir: File? = null

    /**
     * User master switch (persisted, default OFF). Nothing is applied to the SoC until the user
     * turns this on — matching the app's opt-in perf convention. Independent of [isSupported].
     */
    @Volatile
    private var enabled: Boolean = false

    /** True from [start] until [stop]; guards [pause]/[resume] like the existing perf hooks. */
    @Volatile
    var isGameStarted: Boolean = false
        private set

    /** The profile currently in effect for the running session (null before the first apply). */
    @Volatile
    var currentProfile: GalaxyPowerProfile? = null
        private set

    /**
     * Pick a driver once, at app startup. Idempotent. The Samsung driver is only constructed on
     * Samsung hardware; construction (which does the SDK's socket handshake) runs on the worker
     * thread so onCreate is never blocked.
     */
    @JvmStatic
    fun initialize(context: Context) {
        if (driver != null) return
        appContext = context.applicationContext
        enabled = try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        } catch (e: Exception) {
            false
        }
        worker.execute {
            if (driver != null) return@execute
            driver = if (SamsungSPerfDriver.isSamsungDevice()) {
                val samsung = SamsungSPerfDriver(context.applicationContext)
                if (samsung.isSupported) {
                    Log.i(TAG, "Using Samsung Galaxy Performance driver")
                    samsung
                } else {
                    Log.i(TAG, "Samsung device without a usable Galaxy Performance SDK; feature dormant")
                    NoOpGalaxyPerfDriver()
                }
            } else {
                NoOpGalaxyPerfDriver()
            }
        }
    }

    private fun driverOrNoOp(): GalaxyPerfDriver = driver ?: NoOpGalaxyPerfDriver()

    /**
     * Whether Galaxy performance control is actually usable on this device/build. Drives whether
     * the UI shows the Galaxy section at all. Safe to call from any thread; returns false until
     * the async [initialize] has settled.
     */
    @JvmStatic
    fun isSupported(): Boolean = driver?.isSupported == true

    /** User master switch state (persisted, default OFF). */
    @JvmStatic
    fun isEnabled(): Boolean = enabled

    /**
     * Flip the master switch. Persists, then either applies the active profile (on, if a game is
     * running) or releases the boost (off). Does nothing to the SoC when unsupported.
     */
    @JvmStatic
    fun setEnabled(on: Boolean) {
        enabled = on
        val ctx = appContext
        worker.execute {
            if (ctx != null) {
                try {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_ENABLED, on).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist enabled flag: ${e.message}")
                }
            }
            val d = driverOrNoOp()
            if (!d.isSupported) return@execute
            if (on) {
                if (isGameStarted) {
                    val p = currentProfile ?: GalaxyPowerProfile.DEFAULT
                    d.start()
                    d.apply(p)
                }
            } else {
                d.stop()
            }
        }
    }

    /**
     * Begin a session for [containerRootDir]. Loads that container's saved profile (or the
     * default) and applies it.
     */
    @JvmStatic
    fun start(containerRootDir: File?) {
        containerDir = containerRootDir
        isGameStarted = true
        worker.execute {
            val d = driverOrNoOp()
            if (!d.isSupported) return@execute
            val profile = loadFromDir(containerRootDir)
            currentProfile = profile
            // Load the profile regardless so a mid-session toggle-on can apply it, but only touch
            // the SoC when the user has opted in.
            if (enabled) {
                d.start()
                d.apply(profile)
            }
        }
    }

    /** End the session and release every boost. */
    @JvmStatic
    fun stop() {
        isGameStarted = false
        containerDir = null
        worker.execute {
            val d = driverOrNoOp()
            if (d.isSupported) d.stop()
        }
    }

    /** Backgrounded: drop the boost so we are not holding clocks high while the guest is frozen. */
    @JvmStatic
    fun pause() {
        if (!isGameStarted) return
        worker.execute {
            val d = driverOrNoOp()
            if (d.isSupported) d.stop()
        }
    }

    /** Foregrounded: re-assert the active profile. */
    @JvmStatic
    fun resume() {
        if (!isGameStarted) return
        worker.execute {
            val d = driverOrNoOp()
            val profile = currentProfile ?: return@execute
            if (!d.isSupported || !enabled) return@execute
            d.start()
            d.apply(profile)
        }
    }

    /**
     * Change the active profile from the in-game UI: persist it to the running container and
     * apply it immediately.
     */
    @JvmStatic
    fun setProfile(profile: GalaxyPowerProfile) {
        val p = profile.sanitized()
        currentProfile = p
        val dir = containerDir
        worker.execute {
            if (dir != null) writeToDir(dir, p)
            val d = driverOrNoOp()
            // Persist the choice always; only push it to the SoC when enabled + in a session.
            if (isGameStarted && enabled && d.isSupported) d.apply(p)
        }
    }

    /**
     * Persist a chosen profile for a container without a live apply — used by the per-game
     * settings/editor before a game is launched.
     */
    @JvmStatic
    fun saveProfile(containerRootDir: File, profile: GalaxyPowerProfile) {
        val p = profile.sanitized()
        worker.execute { writeToDir(containerRootDir, p) }
    }

    /** Read the saved profile for a container, or [GalaxyPowerProfile.DEFAULT] if none/invalid. */
    @JvmStatic
    fun loadProfile(containerRootDir: File?): GalaxyPowerProfile = loadFromDir(containerRootDir)

    // ---- persistence -------------------------------------------------------

    private fun profileFile(dir: File): File = File(dir, PROFILE_FILE_NAME)

    private fun loadFromDir(dir: File?): GalaxyPowerProfile {
        if (dir == null) return GalaxyPowerProfile.DEFAULT
        return try {
            val f = profileFile(dir)
            if (!f.exists()) GalaxyPowerProfile.DEFAULT
            else GalaxyPowerProfile.fromJsonString(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Galaxy profile: ${e.message}")
            GalaxyPowerProfile.DEFAULT
        }
    }

    /** Temp-then-rename so a crash mid-write can never leave a torn JSON file behind. */
    private fun writeToDir(dir: File, profile: GalaxyPowerProfile) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val target = profileFile(dir)
            val tmp = File(dir, "$PROFILE_FILE_NAME.tmp")
            tmp.writeText(profile.toJson().toString())
            if (!tmp.renameTo(target)) {
                // renameTo can fail across some filesystems; fall back to a direct write.
                target.writeText(profile.toJson().toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write Galaxy profile: ${e.message}")
        }
    }
}
