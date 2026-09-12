package com.winlator.star.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.container.Shortcut
import com.winlator.star.perf.PerfNodeResolver
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.ui.components.PlayerSlotEditorRow
import com.winlator.star.ui.components.buildPlayerSlotEditorRows
import com.winlator.star.widget.HudMetrics
import com.winlator.star.winhandler.WinHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Performance (PerformanceDashboardDialogPerGame) ─────────────────────────────────────────────────
// Per-game overrides of the App Settings > Performance defaults, stored override-when-different exactly
// as the editor's save(): perfExtraOrNull writes the key only when it differs from the global default,
// otherwise clears it so the game re-inherits. Toggling a row back to the global value is its Reset.

private val P2_PERF_NO_ROOT = listOf("sustainedPerfMode", "perfPriorityBoost", "preferBigCores")
private val P2_PERF_KEYS = P2_PERF_NO_ROOT + PerfRootApplier.ROOT_KEYS
private val P2_PROFILES = listOf("Off", "Battery", "Balanced", "Performance", "Turbo")

private fun p2PerfGlobal(key: String): Boolean = when (key) {
    "sustainedPerfMode" -> PerformanceSettings.sustainedPerfMode.value
    "perfPriorityBoost" -> PerformanceSettings.perfPriorityBoost.value
    "preferBigCores" -> PerformanceSettings.preferBigCores.value
    else -> PerformanceSettings.rootDefaultValue(key)
}

/** Effective seed = the per-game override if present, else the global default (no container level). */
private fun p2PerfValue(s: Shortcut, key: String): Boolean = s.p2Ex(key, if (p2PerfGlobal(key)) "1" else "0") == "1"

private fun p2ReadGovernor(): String? {
    for (core in PerfNodeResolver.cpuCores()) {
        val g = core.governor ?: continue
        val v = runCatching { java.io.File(g).readText().trim() }.getOrNull()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}

internal fun xmbPerformanceMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val jobs = P2Jobs(xmb)
    var profile by mutableStateOf<String?>(null)
    var readouts by mutableStateOf<Map<String, String>>(emptyMap())
    var rootState by mutableStateOf(RootManager.state.value)

    // Current device state for the gauges (the dialog polls HudMetrics every ~1.5 s while open).
    jobs.scope.launch {
        val hud = HudMetrics(xmb.context)
        while (true) {
            val m = withContext(Dispatchers.IO) {
                val out = HashMap<String, String>()
                runCatching {
                    out["gpuMhz"] = hud.getGpuClockMhz()?.let { "$it MHz" } ?: "—"
                    val cpuT = hud.getCpuTempC()
                    val gpuT = hud.getGpuTempC()
                    val soc = if (cpuT != null && gpuT != null) maxOf(cpuT, gpuT) else (cpuT ?: gpuT)
                    out["socTemp"] = soc?.let { "$it °C" } ?: "—"
                    out["governor"] = p2ReadGovernor() ?: "—"
                }
                out
            }
            readouts = m
            rootState = RootManager.state.value
            delay(1500)
        }
    }

    fun write(values: List<Pair<String, Boolean>>) {
        p2PutAll(xmb, shortcut, values.map { (k, v) -> k to perfExtraOrNull(v, p2PerfGlobal(k)) })
    }
    fun setOne(key: String, v: Boolean) = write(listOf(key to v))

    fun applyProfile(p: String) {
        profile = p
        val off = listOf(
            PerfRootApplier.KEY_GPU_CLOCK_LOCK to false, PerfRootApplier.KEY_CPU_GOVERNOR to false,
            PerfRootApplier.KEY_CPU_FREQ_LOCK to false, PerfRootApplier.KEY_CORES_ONLINE to false,
        )
        when (p) {
            // Off neutralises the root tier so Galaxy Performance can own the clocks; Battery matches it.
            "Off", "Battery" -> write(listOf("sustainedPerfMode" to false, "perfPriorityBoost" to false, "preferBigCores" to false) + off)
            "Balanced" -> write(listOf("sustainedPerfMode" to true, "perfPriorityBoost" to false, "preferBigCores" to false,
                PerfRootApplier.KEY_GPU_CLOCK_LOCK to false))
            else -> write(listOf("sustainedPerfMode" to true, "perfPriorityBoost" to true, "preferBigCores" to true,
                PerfRootApplier.KEY_GPU_CLOCK_LOCK to true, PerfRootApplier.KEY_CPU_GOVERNOR to true,
                PerfRootApplier.KEY_CPU_FREQ_LOCK to true, PerfRootApplier.KEY_CORES_ONLINE to true))
        }
    }

    return XmbMenu("Performance", Icons.Filled.Speed, onClose = { jobs.cancel() }) {
        val rows = mutableListOf<XmbRow>()
        val granted = rootState == RootManager.RootState.GRANTED

        fun toggle(key: String, label: String, sub: String, danger: Boolean = false) {
            val v = p2PerfValue(shortcut, key)
            val global = p2PerfGlobal(key)
            rows += XmbRow.Toggle("perf:$key", label, if (danger) Icons.Filled.Warning else Icons.Filled.Bolt, v,
                subtitle = if (v != global) "Overridden for this game · global is ${if (global) "on" else "off"}" else sub) { on ->
                profile = null
                if (on && danger) {
                    xmb.confirm(XmbConfirm(label, "Use at your own risk — writes system clock/thermal/fan files; can overheat or damage the device.", "Enable", danger = true)) {
                        setOne(key, true)
                    }
                } else setOne(key, on)
            }
        }

        rows += XmbRow.Header("hNow", "This device now")
        rows += XmbRow.Info("gpuClock", "GPU clock", Icons.Filled.Memory, readouts["gpuMhz"] ?: "…")
        rows += XmbRow.Info("socTemp", "SoC temp", Icons.Filled.Speed, readouts["socTemp"] ?: "…")
        rows += XmbRow.Info("governor", "Governor", Icons.Filled.Tune, readouts["governor"] ?: "…")

        rows += XmbRow.Header("hProfile", "Power profile")
        val profOpts = if (profile == null) listOf("Custom") + P2_PROFILES else P2_PROFILES
        rows += XmbRow.Choice("profile", "Power profile", Icons.Filled.Speed, profOpts, profile ?: "Custom",
            subtitle = "Sets the toggles below in one go", disabledOptions = setOf("Custom")) { v -> applyProfile(v) }

        rows += XmbRow.Header("hQuick", "Quick controls · no root")
        toggle("sustainedPerfMode", "Sustained Performance Mode", "Holds a stable clock to avoid thermal throttling")
        toggle("perfPriorityBoost", "Thread Priority Boost", "Raises emulator thread priority so the scheduler favours the game")
        toggle("preferBigCores", "Prefer Big Cores", "Routes emulator threads onto the big / prime cluster")
        toggle(PerfRootApplier.KEY_GPU_CLOCK_LOCK, "Lock GPU to max clock", "Adreno: no-root KGSL turbo; sysfs pin with root")

        rows += XmbRow.Header("hRoot", "Root & danger")
        rows += XmbRow.Info("rootState", if (granted) "Root granted" else "Saved for this game · applied at launch when root is available",
            if (granted) Icons.Filled.Info else Icons.Filled.Warning)
        if (!granted) {
            rows += XmbRow.Action("grantRoot", "Grant root", Icons.Filled.Warning, subtitle = "Power-user controls — read the warning first") {
                xmb.confirm(XmbConfirm("Power-user performance", "Use at your own risk — these controls write system files and can overheat or damage the device.", "Grant Root", danger = true)) {
                    xmb.scope.launch {
                        RootManager.requestGrant()
                        rootState = RootManager.state.value
                    }
                }
            }
        }
        toggle(PerfRootApplier.KEY_CPU_GOVERNOR, "CPU governor → performance", "Forces every cluster to the performance governor")
        toggle(PerfRootApplier.KEY_CPU_FREQ_LOCK, "Lock CPU frequency to max", "scaling_min_freq = scaling_max_freq on every cluster")
        toggle(PerfRootApplier.KEY_CORES_ONLINE, "Keep all cores online", "Prevents the kernel from hot-plugging cores offline")
        toggle(PerfRootApplier.KEY_THERMAL_DISABLE, "Disable thermal throttling", "Runtime watchdog still guards", danger = true)
        toggle(PerfRootApplier.KEY_FAN_MAX, "Fan to maximum", "Forces active cooling to full at launch", danger = true)

        val anyOverride = P2_PERF_KEYS.any { p2PerfValue(shortcut, it) != p2PerfGlobal(it) }
        if (anyOverride) {
            rows += XmbRow.Action("resetAll", "Reset all to global", Icons.Filled.RestartAlt, subtitle = "This game follows App Settings again") {
                profile = null
                write(P2_PERF_KEYS.map { it to p2PerfGlobal(it) })
            }
        }
        rows
    }
}

// ── Player slots (PlayerSlotsEditor, per-game) ──────────────────────────────────────────────────────
// "controllerSlotOverrides" holds the same JSON the container editor and the in-game Players tab use,
// only ever parsed/built by WinHandler. Empty = inherit the container's Player Slots.

private val P2_SLOT_LABELS = listOf("Auto", "Player 1", "Player 2", "Player 3", "Player 4", "Ignore")
private fun p2SlotValue(label: String): Int = when (label) {
    "Player 1" -> 0
    "Player 2" -> 1
    "Player 3" -> 2
    "Player 4" -> 3
    "Ignore" -> WinHandler.SLOT_IGNORE
    else -> -1
}
private fun p2SlotLabel(v: Int): String = when {
    v == WinHandler.SLOT_IGNORE -> "Ignore"
    v in 0..3 -> "Player ${v + 1}"
    else -> "Auto"
}

internal fun xmbPlayerSlotsMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    fun saved(): String = shortcut.p2Ex("controllerSlotOverrides", "")
    fun build(): List<PlayerSlotEditorRow> = runCatching { buildPlayerSlotEditorRows(saved().ifEmpty { "{}" }) }.getOrDefault(emptyList())
    // Enumerating input devices isn't free: rebuilt on open, after each change and on Refresh.
    var slotRows by mutableStateOf(build())

    fun pin(descriptor: String, value: Int) {
        val map = WinHandler.parseSlotOverridesJson(saved().ifEmpty { "{}" }).toMutableMap()
        if (value == -1) map.remove(descriptor) else map[descriptor] = value
        p2Put(xmb, shortcut, "controllerSlotOverrides", WinHandler.buildSlotOverridesJson(map).ifEmpty { null })
        slotRows = build()
    }

    return XmbMenu("Player slots", Icons.Filled.Gamepad) {
        val rows = mutableListOf<XmbRow>()
        val json = saved()
        if (json.isEmpty()) {
            rows += XmbRow.Info("inherit", "Inheriting the container's Player Slots. Pin a controller below to set a per-game override.", Icons.Filled.Info)
        } else {
            rows += XmbRow.Action("useDefault", "Use container default", Icons.Filled.RestartAlt, subtitle = "Drop this game's pins") {
                p2Put(xmb, shortcut, "controllerSlotOverrides", null)
                slotRows = build()
            }
        }
        rows += XmbRow.Info("about", "Pin a controller (or the on-screen pad) to a player slot. Applied on launch.", Icons.Filled.Info)
        rows += XmbRow.Action("refresh", "Refresh controllers", Icons.Filled.Refresh, subtitle = "Re-scan connected pads") { slotRows = build() }
        if (slotRows.isEmpty()) {
            rows += XmbRow.Info("none", "No controllers detected. Connect one to assign it.", Icons.Filled.Info)
        }
        val seen = HashSet<String>()
        slotRows.forEach { r ->
            if (!seen.add(r.descriptor)) return@forEach
            val status = when {
                r.override == WinHandler.SLOT_IGNORE -> "Ignored"
                r.override >= 0 -> "Pinned to Player ${r.override + 1}"
                else -> "Auto (assigned by launch order)"
            }
            val suffix = when {
                r.isOnScreen -> " · on-screen controls"
                !r.connected -> " · not connected (saved pin)"
                else -> ""
            }
            rows += XmbRow.Choice("slot:${r.descriptor}", r.displayName, if (r.isOnScreen) Icons.Filled.TouchApp else Icons.Filled.Gamepad,
                P2_SLOT_LABELS, p2SlotLabel(r.override), subtitle = status + suffix) { v ->
                pin(r.descriptor, p2SlotValue(v))
            }
        }
        rows
    }
}
