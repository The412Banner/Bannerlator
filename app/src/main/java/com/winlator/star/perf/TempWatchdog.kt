package com.winlator.star.perf

import android.content.Context
import android.util.Log
import com.winlator.star.widget.HudMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Thermal failsafe for the root perf tier. A background coroutine polls the HOTTEST watched CPU/GPU
 * zone and, above the resolved ceiling, force-restores thermal + governor state through
 * [PerfRevertRegistry.revertAll] REGARDLESS of any toggle — an overheating device wins over a user
 * override.
 *
 * The ceiling is anchored to the DEVICE'S OWN thermal trip points ([PerfNodeResolver.thermalTrips]),
 * not a guessed fixed number (a fixed 85°C is wrong on handhelds whose idle CPU cores already sit at
 * 77–88°C and whose real throttle trips are 95→…→125°C). The user picks an aggressiveness [mode];
 * MANUAL takes a user-set value. When the device exposes no usable trips we fall back to a fixed 85°C.
 *
 * The watchdog is a user toggle (default ON); turned OFF it stays OFF across restarts (never silently
 * re-armed). The auto-revert on exit/background/crash is separate and always on.
 */
object TempWatchdog {

    private const val TAG = "TempWatchdog"
    private const val PREFS = "perf_prefs"
    private const val KEY_ENABLED = "temp_watchdog_enabled"
    private const val KEY_MODE = "temp_watchdog_mode"
    private const val KEY_MANUAL = "temp_watchdog_manual_c"

    /** Safe fixed ceiling used only when the device exposes no usable thermal trips. */
    const val FALLBACK_CEILING_C = 85

    // Preset margins below the device's hard (top) trip.
    private const val BALANCED_MARGIN = 10
    private const val AGGRESSIVE_MARGIN = 3

    /** User-selectable manual range (upper end is additionally capped at the device top trip). */
    const val MANUAL_MIN_C = 60
    const val MANUAL_MAX_C = 125

    private const val POLL_MS = 3_000L
    // Require two consecutive over-ceiling reads before acting, to shrug off a single spurious spike.
    private const val TRIP_COUNT = 2

    enum class ThresholdMode { CONSERVATIVE, BALANCED, AGGRESSIVE, MANUAL }

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mode = MutableStateFlow(ThresholdMode.BALANCED)
    val mode: StateFlow<ThresholdMode> = _mode.asStateFlow()

    private val _manualCeilingC = MutableStateFlow(FALLBACK_CEILING_C)
    val manualCeilingC: StateFlow<Int> = _manualCeilingC.asStateFlow()

    /** The resolved effective ceiling in °C — recomputed on init and on any mode/manual change. */
    private val _ceilingC = MutableStateFlow(FALLBACK_CEILING_C)
    val ceilingC: StateFlow<Int> = _ceilingC.asStateFlow()

    /** Live temperature the watchdog last observed (°C); 0 when unknown / not polling. */
    private val _lastTempC = MutableStateFlow(0)
    val lastTempC: StateFlow<Int> = _lastTempC.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var appContext: Context? = null
    private var trips: PerfNodeResolver.ThermalTrips? = null

    val firstTripC: Int? get() = trips?.firstTripC
    val topTripC: Int? get() = trips?.topTripC
    /** True when we anchored to real device trips; false means the fixed fallback is in use. */
    val hasDeviceTrips: Boolean get() = trips?.topTripC != null

    /** Load persisted state + resolve the device trips. Call once from the Application before start(). */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, true) // default ON; a persisted false stays false
        _mode.value = try {
            ThresholdMode.valueOf(prefs.getString(KEY_MODE, ThresholdMode.BALANCED.name)!!)
        } catch (_: Exception) { ThresholdMode.BALANCED }

        trips = PerfNodeResolver.thermalTrips()

        // Seed the manual value to the Balanced-resolved ceiling the first time (no stored value yet).
        val seededManual = prefs.getInt(KEY_MANUAL, -1)
        _manualCeilingC.value = if (seededManual > 0) seededManual else resolvedCeilingFor(ThresholdMode.BALANCED)

        recomputeCeiling()
        Log.d(TAG, "init: trips first=${trips?.firstTripC} top=${trips?.topTripC} " +
            "mode=${_mode.value} ceiling=${_ceilingC.value} (deviceTrips=$hasDeviceTrips)")
    }

    fun setWatchdogEnabled(enabled: Boolean) {
        _enabled.value = enabled
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.d(TAG, "watchdog ${if (enabled) "armed" else "disarmed"}")
    }

    fun setMode(mode: ThresholdMode) {
        _mode.value = mode
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_MODE, mode.name)?.apply()
        recomputeCeiling()
        Log.d(TAG, "mode=$mode -> ceiling=${_ceilingC.value}°C")
    }

    fun setManualCeilingC(value: Int) {
        // Upper bound is the device top trip, but never below MANUAL_MIN_C (avoids min>max on odd devices).
        val upper = maxOf(MANUAL_MIN_C, topTripC ?: MANUAL_MAX_C)
        val capped = value.coerceIn(MANUAL_MIN_C, upper)
        _manualCeilingC.value = capped
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_MANUAL, capped)?.apply()
        if (_mode.value == ThresholdMode.MANUAL) recomputeCeiling()
    }

    /** Ceiling a given mode WOULD resolve to on this device (for labels / info copy), no state change. */
    fun resolvedCeilingFor(mode: ThresholdMode): Int {
        val first = trips?.firstTripC
        val top = trips?.topTripC
        return when (mode) {
            ThresholdMode.CONSERVATIVE -> first ?: FALLBACK_CEILING_C
            ThresholdMode.BALANCED -> top?.minus(BALANCED_MARGIN) ?: FALLBACK_CEILING_C
            ThresholdMode.AGGRESSIVE -> top?.minus(AGGRESSIVE_MARGIN) ?: FALLBACK_CEILING_C
            ThresholdMode.MANUAL -> _manualCeilingC.value.coerceIn(MANUAL_MIN_C, maxOf(MANUAL_MIN_C, top ?: MANUAL_MAX_C))
        }
    }

    private fun recomputeCeiling() { _ceilingC.value = resolvedCeilingFor(_mode.value) }

    /** Start polling for a game session. Idempotent. Ticks are cheap no-ops while disabled. */
    fun start(context: Context) {
        if (appContext == null) init(context)
        if (job?.isActive == true) return
        val metrics = HudMetrics(context.applicationContext)
        var over = 0
        job = scope.launch {
            Log.d(TAG, "started (ceiling ${_ceilingC.value}°C, mode ${_mode.value})")
            while (isActive) {
                if (_enabled.value) {
                    val temp = readHottestWatchedC(metrics)
                    if (temp != null) {
                        _lastTempC.value = temp
                        if (temp >= _ceilingC.value) {
                            over++
                            if (over >= TRIP_COUNT) {
                                Log.w(TAG, "temp ${temp}°C >= ${_ceilingC.value}°C — force-reverting perf state")
                                try { PerfRevertRegistry.revertAll() } catch (t: Throwable) {
                                    Log.w(TAG, "watchdog revert failed", t)
                                }
                                over = 0
                            }
                        } else {
                            over = 0
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _lastTempC.value = 0
    }

    /**
     * Hottest of the watched CPU/GPU zones (the same zones the trips came from), so the ceiling and
     * the sensor are per-zone consistent. Falls back to HudMetrics' aggregate when no watched zone is
     * readable (also covers the no-device-trips fallback path).
     */
    private fun readHottestWatchedC(metrics: HudMetrics): Int? {
        var hottest: Int? = null
        for (path in trips?.watchedTempPaths ?: emptyList()) {
            val raw = readIntFile(path) ?: continue
            val c = if (raw > 1000) (raw + 500) / 1000 else raw
            if (c in 1..200) hottest = maxOf(hottest ?: c, c)
        }
        if (hottest != null) return hottest
        // Fallback: HudMetrics' hottest of CPU/GPU.
        val cpu = metrics.cpuTempC
        val gpu = metrics.gpuTempC
        return when {
            cpu != null && gpu != null -> maxOf(cpu, gpu)
            cpu != null -> cpu
            gpu != null -> gpu
            else -> null
        }
    }

    private fun readIntFile(path: String): Int? = try {
        File(path).bufferedReader().use { it.readLine() }?.trim()?.toIntOrNull()
    } catch (_: Exception) { null }
}
