package com.winlator.star.perf

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for the GLOBAL DEFAULTS of the non-root performance toggles. Both perf
 * surfaces bind to these flows:
 *  - App Settings → Performance menu reads AND writes the global default here.
 *  - The in-game drawer reads the EFFECTIVE value (per-game override ?? this global default) and, for
 *    a game with no per-game store, writes the global default here too — so the two surfaces stay in
 *    two-way live sync via one store instead of independent copies.
 *
 * Resolution model is exactly two levels: **per-game shortcut override → this global default**. There
 * is no per-container level.
 *
 * SharedPreferences-backed (shared "perf_prefs" file with [TempWatchdog]); each toggle is a
 * StateFlow so Compose can observe it live. Device-wide items that already have their own singletons
 * ([TempWatchdog] enabled, [RootManager] grant state) are re-exposed here read-only so a settings
 * screen can bind everything from one place.
 */
object PerformanceSettings {

    private const val PREFS = "perf_prefs"
    private const val KEY_SUSTAINED = "global_sustainedPerfMode"
    private const val KEY_PRIORITY = "global_perfPriorityBoost"
    private const val KEY_BIG_CORES = "global_preferBigCores"
    // In-game Performance dialog translucency (see PerformanceDashboardDialog). Device-wide, not
    // per-game: it's a display preference, not a perf setting. Clamped to a readable floor.
    private const val KEY_DIALOG_OPACITY = "perf_dialog_opacity"
    const val OPACITY_MIN = 0.3f

    // The per-game override / extraData key names (NOT the "global_"-prefixed pref keys). Used by the
    // override-when-different + reset-to-global logic to iterate every perf toggle uniformly.
    val NON_ROOT_KEYS = listOf("sustainedPerfMode", "perfPriorityBoost", "preferBigCores")
    val ALL_PERF_KEYS: List<String> = NON_ROOT_KEYS + PerfRootApplier.ROOT_KEYS

    /** Current global default for any perf key (non-root three or root six), by its extraData name. */
    fun globalDefault(key: String): Boolean = when (key) {
        "sustainedPerfMode" -> _sustainedPerfMode.value
        "perfPriorityBoost" -> _perfPriorityBoost.value
        "preferBigCores" -> _preferBigCores.value
        else -> rootDefaultValue(key)
    }

    /** Set the global default for any perf key uniformly. */
    fun setGlobalDefault(key: String, v: Boolean) {
        when (key) {
            "sustainedPerfMode" -> setSustainedPerfMode(v)
            "perfPriorityBoost" -> setPerfPriorityBoost(v)
            "preferBigCores" -> setPreferBigCores(v)
            else -> setRootDefault(key, v)
        }
    }

    private var appContext: Context? = null

    private val _sustainedPerfMode = MutableStateFlow(false)
    val sustainedPerfMode: StateFlow<Boolean> = _sustainedPerfMode.asStateFlow()

    private val _perfPriorityBoost = MutableStateFlow(false)
    val perfPriorityBoost: StateFlow<Boolean> = _perfPriorityBoost.asStateFlow()

    private val _preferBigCores = MutableStateFlow(false)
    val preferBigCores: StateFlow<Boolean> = _preferBigCores.asStateFlow()

    private val _dialogOpacity = MutableStateFlow(1f)
    val dialogOpacity: StateFlow<Float> = _dialogOpacity.asStateFlow()
    fun setDialogOpacity(v: Float) {
        val c = v.coerceIn(OPACITY_MIN, 1f)
        _dialogOpacity.value = c
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putFloat(KEY_DIALOG_OPACITY, c)?.apply()
    }

    // ── Root-tier global defaults (one flow per PerfRootApplier.ROOT_KEYS entry), created eagerly in
    // init() so Compose has stable instances. Same two-level model: these are the global default, a
    // per-game shortcut extra can override, effective = override ?? global. Persisted as "global_<key>".
    private val rootDefaults = LinkedHashMap<String, MutableStateFlow<Boolean>>()

    // Device-wide state re-exposed read-only so one screen can bind the whole picture.
    val watchdogEnabled: StateFlow<Boolean> get() = TempWatchdog.enabled
    val rootState: StateFlow<RootManager.RootState> get() = RootManager.state
    val harnessProven: StateFlow<Boolean> get() = PerfRevertRegistry.harnessProven

    /** Load persisted global defaults. Call once from the Application, before either surface binds. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _sustainedPerfMode.value = prefs.getBoolean(KEY_SUSTAINED, false)
        _perfPriorityBoost.value = prefs.getBoolean(KEY_PRIORITY, false)
        _preferBigCores.value = prefs.getBoolean(KEY_BIG_CORES, false)
        _dialogOpacity.value = prefs.getFloat(KEY_DIALOG_OPACITY, 1f).coerceIn(OPACITY_MIN, 1f)
        for (key in PerfRootApplier.ROOT_KEYS) {
            rootDefaults[key] = MutableStateFlow(prefs.getBoolean("global_$key", false))
        }
    }

    fun setSustainedPerfMode(v: Boolean) = put(KEY_SUSTAINED, v, _sustainedPerfMode)
    fun setPerfPriorityBoost(v: Boolean) = put(KEY_PRIORITY, v, _perfPriorityBoost)
    fun setPreferBigCores(v: Boolean) = put(KEY_BIG_CORES, v, _preferBigCores)

    /** Stable global-default flow for a root toggle key (see PerfRootApplier.ROOT_KEYS). */
    fun rootDefaultFlow(key: String): StateFlow<Boolean> = flowFor(key)
    fun rootDefaultValue(key: String): Boolean = flowFor(key).value
    fun setRootDefault(key: String, v: Boolean) {
        flowFor(key).value = v
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("global_$key", v)?.apply()
    }

    private fun flowFor(key: String): MutableStateFlow<Boolean> =
        rootDefaults.getOrPut(key) {
            MutableStateFlow(
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    ?.getBoolean("global_$key", false) ?: false
            )
        }

    private fun put(key: String, v: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = v
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(key, v)?.apply()
    }
}
