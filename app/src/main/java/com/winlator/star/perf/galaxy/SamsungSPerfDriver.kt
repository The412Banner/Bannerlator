package com.winlator.star.perf.galaxy

import android.content.Context
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Drives Samsung's Galaxy Performance SDK (`com.samsung.sdk.sperf`) entirely through
 * reflection, so the app has **no compile-time dependency** on the proprietary SDK jar.
 *
 * Why reflection:
 *  - The build stays green with the jar absent (it is not committed to this repo for
 *    licensing reasons — see app/libs/GalaxyPerf/README). When the jar is not present the
 *    classes fail to resolve and this driver reports [isSupported] = false, so the whole
 *    feature is simply dormant.
 *  - When the official Samsung jar is dropped in, the classes resolve and control lights up.
 *
 * The SDK jar is a thin client that marshals requests over a local socket to an on-device
 * Samsung daemon (hence the INTERNET permission). The daemon tracks boosts per client PID and
 * releases them automatically if this process dies, so — unlike a root/sysfs approach — there
 * is nothing to restore on a crash.
 *
 * All calls come in serialized on [GalaxyPerfManager]'s single worker thread; this class holds
 * no locks of its own.
 *
 * API surface (verified against perfsdk-v1.0.0):
 *   SPerf.setDebugModeEnabled(boolean)          static
 *   SPerf.initialize(Context): boolean          static
 *   PerformanceManager.getInstance()            static -> PerformanceManager
 *   PerformanceManager.start(CustomParams): int instance
 *   PerformanceManager.stop(): int              instance
 *   new CustomParams()                          ctor
 *   CustomParams.add(int type, int level, int timeoutMs): int
 */
class SamsungSPerfDriver(context: Context) : GalaxyPerfDriver {

    private var performanceManager: Any? = null
    private var customParamsCtor: Constructor<*>? = null
    private var addMethod: Method? = null
    private var startMethod: Method? = null
    private var stopMethod: Method? = null

    private val available: Boolean

    init {
        available = runCatching {
            val appContext = context.applicationContext
            val sperfClass = Class.forName(SPERF_CLASS)
            val pmClass = Class.forName(PERFORMANCE_MANAGER_CLASS)
            val cpClass = Class.forName(CUSTOM_PARAMS_CLASS)

            // Quiet the SDK's own logcat spam.
            runCatching {
                sperfClass.getMethod("setDebugModeEnabled", java.lang.Boolean.TYPE)
                    .invoke(null, false)
            }

            val initialized = sperfClass.getMethod("initialize", Context::class.java)
                .invoke(null, appContext) as? Boolean ?: false
            if (!initialized) {
                Log.w(TAG, "Galaxy Performance SDK present but initialize() returned false")
                return@runCatching false
            }

            val pm = pmClass.getMethod("getInstance").invoke(null)
            if (pm == null) {
                Log.w(TAG, "Galaxy Performance SDK returned a null PerformanceManager")
                return@runCatching false
            }

            performanceManager = pm
            customParamsCtor = cpClass.getDeclaredConstructor().apply { isAccessible = true }
            addMethod = cpClass.getMethod(
                "add",
                Integer.TYPE, Integer.TYPE, Integer.TYPE,
            )
            startMethod = pmClass.getMethod("start", cpClass)
            stopMethod = pmClass.getMethod("stop")

            Log.i(TAG, "Galaxy Performance SDK initialized")
            true
        }.getOrElse { e ->
            // ClassNotFoundException here is the normal "jar not bundled" path.
            Log.i(TAG, "Galaxy Performance SDK unavailable: ${e.message}")
            false
        }
    }

    override val isSupported: Boolean
        get() = available

    override fun start() {
        // No-op: the SDK asserts control through apply(); nothing to warm up.
    }

    override fun stop() {
        if (!available) return
        runCatching { stopMethod?.invoke(performanceManager) }
            .onFailure { Log.w(TAG, "Failed to stop Galaxy performance boosts: ${it.message}") }
    }

    override fun apply(profile: GalaxyPowerProfile): Boolean {
        if (!available) return false
        val p = profile.sanitized()
        return runCatching {
            val params = customParamsCtor!!.newInstance()
            val add = addMethod!!
            // Batch every domain into one CustomParams -> a single round trip to the daemon.
            add.invoke(params, TYPE_CPU_MIN, p.cpuMin, PERSIST)
            add.invoke(params, TYPE_CPU_MAX, p.cpuMax, PERSIST)
            add.invoke(params, TYPE_GPU_MIN, p.gpuMin, PERSIST)
            add.invoke(params, TYPE_GPU_MAX, p.gpuMax, PERSIST)
            add.invoke(params, TYPE_BUS_MIN, p.busMin, PERSIST)
            add.invoke(params, TYPE_BUS_MAX, p.busMax, PERSIST)
            startMethod!!.invoke(performanceManager, params)
            Log.d(TAG, "Applied Galaxy profile '${p.name}' cpu=${p.cpuMin}-${p.cpuMax} gpu=${p.gpuMin}-${p.gpuMax} bus=${p.busMin}-${p.busMax}")
            true
        }.getOrElse {
            Log.w(TAG, "Failed to apply Galaxy profile: ${it.message}")
            false
        }
    }

    companion object {
        private const val TAG = "GalaxyPerf"

        private const val SPERF_CLASS = "com.samsung.sdk.sperf.SPerf"
        private const val PERFORMANCE_MANAGER_CLASS = "com.samsung.sdk.sperf.PerformanceManager"
        private const val CUSTOM_PARAMS_CLASS = "com.samsung.sdk.sperf.CustomParams"

        // CustomParams.TYPE_* resource identifiers (verified against perfsdk-v1.0.0).
        private const val TYPE_CPU_MIN = 0
        private const val TYPE_CPU_MAX = 1
        private const val TYPE_GPU_MIN = 2
        private const val TYPE_GPU_MAX = 3
        private const val TYPE_BUS_MIN = 4
        private const val TYPE_BUS_MAX = 5

        /** timeoutMs = 0 asks the daemon to hold the boost until an explicit stop(). */
        private const val PERSIST = 0

        /** Cheap manufacturer gate so non-Samsung devices never touch reflection. */
        fun isSamsungDevice(): Boolean =
            android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }
}
