package com.winlator.star.ui.deck

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.R
import com.winlator.star.perf.PerfGpuTurbo
import com.winlator.star.perf.PerfRevertRegistry
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.perf.TempWatchdog
import com.winlator.star.perf.TempWatchdog.ThresholdMode
import com.winlator.star.perf.galaxy.GalaxyPerfManager
import com.winlator.star.perf.galaxy.GalaxyPowerProfile
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.components.ANDROID_OUTPUT_MS
import com.winlator.star.ui.components.AUDIO_PRESETS
import com.winlator.star.ui.components.AudioConfig
import com.winlator.star.ui.components.AudioPreset
import com.winlator.star.ui.components.PRESET_CUSTOM
import com.winlator.star.ui.components.effectiveConfigFor
import com.winlator.star.ui.components.latencyIsDirectBuffer
import com.winlator.star.ui.components.latencyUsedBy
import com.winlator.star.ui.screens.PerfDisclaimerCopy
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.MenuAction
import com.winlator.star.ui.simple.SimpleActionSheet
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimpleStrong
import com.winlator.star.widget.HudMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Lab-drawn in-game Performance dashboard and Audio settings: the same flows, setters and callbacks as the Classic
// PerformanceDashboardDialog (ui/PerformanceDashboardDialog.kt) and AudioSettingsDialog (ui/components/AudioSettingsDialog.kt),
// drawn with the Lab in-game kit.

/** A dangerous toggle waiting on the user's acknowledgement before it flips on. */
private class LabDashPendingDanger(val label: String, val enable: () -> Unit)

/**
 * Lab variant of [com.winlator.star.ui.PerformanceDashboardDialog]: live gauges, power profiles, Galaxy Performance,
 * root status, the no-root and root toggles with their per-game override lines, free memory, the temperature
 * watchdog and the panel opacity, with the same grant / danger / watchdog-off gates.
 */
@Composable
internal fun LabPerformanceDashboardDialog(state: XServerDrawerState, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val opacity by PerformanceSettings.dialogOpacity.collectAsState()

    val rootState by RootManager.state.collectAsState()
    val granted = rootState == RootManager.RootState.GRANTED
    val harnessProven by PerfRevertRegistry.harnessProven.collectAsState()
    val overridden by state.overriddenKeys.collectAsState()
    val toggles by state.rootToggles.collectAsState()
    val readouts by state.rootReadouts.collectAsState()
    val ceilingC by TempWatchdog.ceilingC.collectAsState()

    val sustained by state.sustainedPerfMode.collectAsState()
    val priority by state.perfPriorityBoost.collectAsState()
    val bigCores by state.preferBigCores.collectAsState()

    // Same save note the Lab Performance page shows for the dashboard.
    val fromShortcut by DeckInGameState.launchedFromShortcut.collectAsState()
    val perfScopes = if (fromShortcut) listOf(SaveScope.GAME, SaveScope.ALL) else listOf(SaveScope.ALL)

    var pendingDanger by remember { mutableStateOf<LabDashPendingDanger?>(null) }
    var showGrantGate by remember { mutableStateOf(false) }
    var showGalaxyOffPrompt by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<String?>(null) }

    // Poll the cheap sysfs/HudMetrics readouts while the dialog is open (same cadence as Classic).
    LaunchedEffect(Unit) {
        while (true) {
            state.onRootReadoutPoll?.run()
            delay(1500)
        }
    }

    fun setRoot(key: String, on: Boolean) {
        state.setRootToggle(key, on)
        state.onRootToggleChange?.accept(key, on)
    }
    fun setSustained(on: Boolean) { state.setSustainedPerfMode(on); state.onSustainedPerfModeChange?.run() }
    fun setPriority(on: Boolean) { state.setPerfPriorityBoost(on); state.onPerfPriorityBoostChange?.run() }
    fun setBig(on: Boolean) { state.setPreferBigCores(on); state.onPreferBigCoresChange?.run() }

    // Power profiles: the same macro over the existing toggles as Classic. Thermal / fan are never auto-enabled.
    fun applyProfile(p: String) {
        profile = p
        when (p) {
            "Off" -> {
                setSustained(false); setPriority(false); setBig(false)
                setRoot(PerfRootApplier.KEY_GPU_CLOCK_LOCK, false)
                if (granted) {
                    setRoot(PerfRootApplier.KEY_CPU_GOVERNOR, false)
                    setRoot(PerfRootApplier.KEY_CPU_FREQ_LOCK, false)
                    setRoot(PerfRootApplier.KEY_CORES_ONLINE, false)
                }
            }
            "Battery" -> {
                setSustained(false); setPriority(false); setBig(false)
                setRoot(PerfRootApplier.KEY_GPU_CLOCK_LOCK, false)
                if (granted) {
                    setRoot(PerfRootApplier.KEY_CPU_GOVERNOR, false)
                    setRoot(PerfRootApplier.KEY_CPU_FREQ_LOCK, false)
                    setRoot(PerfRootApplier.KEY_CORES_ONLINE, false)
                }
            }
            "Balanced" -> {
                setSustained(true); setPriority(false); setBig(false)
                setRoot(PerfRootApplier.KEY_GPU_CLOCK_LOCK, false)
            }
            "Performance", "Turbo" -> {
                setSustained(true); setPriority(true); setBig(true)
                setRoot(PerfRootApplier.KEY_GPU_CLOCK_LOCK, true)
                if (granted) {
                    setRoot(PerfRootApplier.KEY_CPU_GOVERNOR, true)
                    setRoot(PerfRootApplier.KEY_CPU_FREQ_LOCK, true)
                    setRoot(PerfRootApplier.KEY_CORES_ONLINE, true)
                }
            }
        }
    }

    LabInGameDialog(
        title = L("Performance"),
        onDismiss = onDismiss,
        subtitle = L("Applies live to this session · auto-reverts on exit"),
        maxWidth = 900.dp,
        tall = true,
        // Panel opacity stays on screen (outside the scroll), as in Classic.
        footer = {
            Text(L("Panel opacity"), color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Box(Modifier.weight(1f)) {
                IgSlider(
                    value = opacity,
                    range = PerformanceSettings.OPACITY_MIN..1f,
                    step = 0.05f,
                    format = { "${(it * 100).toInt()}%" },
                    onChange = { PerformanceSettings.setDialogOpacity(it) },
                    onFinished = {},
                )
            }
        },
    ) {
        LabDashWindowAlpha()

        // ── Gauges ──
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val gpu = labDashLeadingInt(readouts["gpuMhz"])
            val temp = labDashLeadingInt(readouts["socTemp"])
            LabDashGauge(Modifier.weight(1f), L("GPU clock"), readouts["gpuMhz"] ?: "—", "MHz",
                frac = gpu?.let { (it / 1000f).coerceIn(0f, 1f) } ?: 0f, hot = false)
            LabDashGauge(Modifier.weight(1f), L("SoC temp"), readouts["socTemp"] ?: "—", "°C",
                frac = temp?.let { ((it - 30f) / ((ceilingC - 30).coerceAtLeast(1))).coerceIn(0f, 1f) } ?: 0f,
                hot = temp != null && temp >= ceilingC)
            LabDashGauge(Modifier.weight(1f), L("Governor"), readouts["governor"] ?: "—", "",
                frac = 0f, hot = false, textOnly = true)
        }

        // ── Power profile ──
        IgSectionLabel(L("Power profile"))
        IgCard {
            IgRow(L("Profile"), scopes = perfScopes, wideControl = true) {
                IgChips(
                    options = listOf(
                        "Off" to L("Off"),
                        "Battery" to L("Battery"),
                        "Balanced" to L("Balanced"),
                        "Performance" to L("Performance"),
                        "Turbo" to L("Turbo"),
                    ),
                    selected = profile,
                ) { applyProfile(it) }
            }
        }

        // ── Galaxy Performance (Samsung, no root): only on Galaxy devices with the SDK, else hidden ──
        val galaxySupported = remember { GalaxyPerfManager.isSupported() }
        var galaxyEnabled by remember { mutableStateOf(GalaxyPerfManager.isEnabled()) }
        var galaxyProfileName by remember {
            mutableStateOf(GalaxyPerfManager.currentProfile?.name ?: GalaxyPowerProfile.DEFAULT.name)
        }
        if (galaxySupported) {
            IgSectionLabel(L("Galaxy Performance · Samsung"))
            IgCard {
                // The switch persists device-wide (GalaxyPerfManager prefs); the preset goes to the running container.
                IgToggleRow(
                    L("Enable Galaxy Performance"),
                    checked = galaxyEnabled,
                    hint = L("No-root CPU/GPU/RAM control via Samsung's SDK. Off by default."),
                    scopes = listOf(SaveScope.ALL),
                ) {
                    galaxyEnabled = it
                    GalaxyPerfManager.setEnabled(it)
                    // Recommend handing clocks to Galaxy alone, unless already Off.
                    if (it && profile != "Off") showGalaxyOffPrompt = true
                }
                if (galaxyEnabled) {
                    IgDivider()
                    IgRow(
                        L("Galaxy preset"),
                        hint = L("Tip: set the Power profile above to \"Off\" so Galaxy alone controls the clocks (they otherwise conflict)."),
                        scopes = listOf(SaveScope.CONTAINER),
                        wideControl = true,
                    ) {
                        IgChips(
                            options = GalaxyPowerProfile.PRESETS.map { it.name to L(it.name) },
                            selected = galaxyProfileName,
                        ) { name ->
                            val preset = GalaxyPowerProfile.PRESETS.firstOrNull { it.name == name }
                            if (preset != null) {
                                galaxyProfileName = preset.name
                                GalaxyPerfManager.setProfile(preset)
                            }
                        }
                    }
                }
            }
        }

        // ── Root status ──
        Spacer(Modifier.height(12.dp))
        IgCard {
            IgRow(
                if (granted) L("Root granted") else L("No root"),
                hint = if (granted) L("Full CPU/GPU control unlocked.") else L("No-root controls still available."),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            if (granted) Color(0xFF37C26A) else cs.onSurface.copy(alpha = 0.35f),
                            CircleShape,
                        )
                    )
                    if (!granted && rootState != RootManager.RootState.UNAVAILABLE) {
                        IgButton(
                            if (rootState == RootManager.RootState.DENIED) L("Grant Root (retry)") else L("Grant Root"),
                            null,
                            { showGrantGate = true },
                            primary = true,
                        )
                    }
                }
            }
        }

        // ── Quick controls (no root) ──
        IgSectionLabel(L("Quick controls · no root"))
        IgCard {
            LabDashToggleRow(
                L("Sustained Performance Mode"),
                L("Holds a stable clock to avoid thermal throttling."),
                info = L("Holds a stable clock so the SoC doesn't spike-and-throttle. No root required."),
                checked = sustained, enabled = true,
                overridden = "sustainedPerfMode" in overridden,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept("sustainedPerfMode") },
            ) { setSustained(it) }
            IgDivider()
            LabDashToggleRow(
                L("Thread Priority Boost"),
                L("Raises emulator thread priority so the scheduler favours the game."),
                info = L("Raises the emulator's thread priority so the CPU scheduler favours the game over background work."),
                checked = priority, enabled = true,
                overridden = "perfPriorityBoost" in overridden,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept("perfPriorityBoost") },
            ) { setPriority(it) }
            IgDivider()
            LabDashToggleRow(
                L("Prefer Big Cores"),
                L("Routes emulator threads onto the big / prime cluster."),
                info = L("Pins the emulator's hot threads onto the big / prime CPU cluster instead of the efficiency cores."),
                checked = bigCores, enabled = true,
                overridden = "preferBigCores" in overridden,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept("preferBigCores") },
            ) { setBig(it) }
            IgDivider()
            // GPU pin: usable without root on Adreno (KGSL turbo), upgrades to sysfs on root.
            val gpuKey = PerfRootApplier.KEY_GPU_CLOCK_LOCK
            val gpuEnabled = granted || PerfGpuTurbo.isSupported
            LabDashToggleRow(
                L("Lock GPU to max clock"),
                if (gpuEnabled) L("Adreno: pinned via /dev/kgsl-3d0 — works without root.")
                else L("Needs an Adreno GPU, or root on other GPUs."),
                info = L("Pins the GPU to its top power level. On Adreno this uses the app-owned KGSL turbo property (no root); other GPUs use the root sysfs pin."),
                checked = toggles[gpuKey] ?: false, enabled = gpuEnabled,
                overridden = gpuKey in overridden,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept(gpuKey) },
            ) { setRoot(gpuKey, it) }
        }

        // ── Root & danger ──
        IgSectionLabel(L("Root & danger"))
        IgCard {
            if (!granted) {
                IgNote(
                    "🔒 " + when (rootState) {
                        RootManager.RootState.UNAVAILABLE -> L("No root manager detected (Magisk / KernelSU / APatch).")
                        else -> L("Grant root above to unlock CPU governor, frequency, cores, thermal & fan.")
                    },
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            LabDashRootRow(
                L("CPU governor → performance"), PerfRootApplier.KEY_CPU_GOVERNOR,
                info = L("Forces every CPU cluster onto the performance governor for the session."),
                toggles = toggles, overridden = overridden, granted = granted, harnessProven = harnessProven,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept(PerfRootApplier.KEY_CPU_GOVERNOR) },
                onEnableDanger = null, setRoot = { k, v -> setRoot(k, v) },
            )
            IgDivider()
            LabDashRootRow(
                L("Lock CPU frequency to max"), PerfRootApplier.KEY_CPU_FREQ_LOCK,
                info = L("Sets scaling_min_freq = scaling_max_freq on every cluster."),
                toggles = toggles, overridden = overridden, granted = granted, harnessProven = harnessProven,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept(PerfRootApplier.KEY_CPU_FREQ_LOCK) },
                onEnableDanger = null, setRoot = { k, v -> setRoot(k, v) },
            )
            IgDivider()
            LabDashRootRow(
                L("Keep all cores online"), PerfRootApplier.KEY_CORES_ONLINE,
                info = L("Prevents the kernel from hot-plugging CPU cores offline."),
                toggles = toggles, overridden = overridden, granted = granted, harnessProven = harnessProven,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept(PerfRootApplier.KEY_CORES_ONLINE) },
                onEnableDanger = null, setRoot = { k, v -> setRoot(k, v) },
            )
            IgDivider()
            val thermalLabel = L("Disable thermal throttling")
            LabDashRootRow(
                thermalLabel, PerfRootApplier.KEY_THERMAL_DISABLE,
                info = L("Stops the thermal engine from clamping clocks. The temperature watchdog stays armed and can revert it."),
                toggles = toggles, overridden = overridden, granted = granted, harnessProven = harnessProven,
                danger = true,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept(PerfRootApplier.KEY_THERMAL_DISABLE) },
                onEnableDanger = { pendingDanger = LabDashPendingDanger(thermalLabel) { setRoot(PerfRootApplier.KEY_THERMAL_DISABLE, true) } },
                setRoot = { k, v -> setRoot(k, v) },
            )
            IgDivider()
            val fanLabel = L("Fan to maximum")
            LabDashRootRow(
                fanLabel, PerfRootApplier.KEY_FAN_MAX,
                info = L("Forces active cooling to full speed for the session."),
                toggles = toggles, overridden = overridden, granted = granted, harnessProven = harnessProven,
                danger = true,
                scopes = perfScopes,
                onReset = { state.onResetPerfKey?.accept(PerfRootApplier.KEY_FAN_MAX) },
                onEnableDanger = { pendingDanger = LabDashPendingDanger(fanLabel) { setRoot(PerfRootApplier.KEY_FAN_MAX, true) } },
                setRoot = { k, v -> setRoot(k, v) },
            )
            if (granted && !harnessProven) {
                IgNote(
                    L("Thermal / fan stay locked until safety-revert is verified on this device."),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // ── Free memory (root) ──
        if (granted) {
            IgSectionLabel(L("Free memory"))
            IgCard {
                IgRow(L("Drop file caches"), hint = L("Frees cached files. The system reclaims cache automatically.")) {
                    IgButton(L("Run"), null, { state.onFreeMemory?.run() }, primary = true)
                }
                IgDivider()
                IgRow(L("Deep clean (free app memory)"), hint = L("Force-closes background apps. Won't touch your game or system.")) {
                    IgButton(L("Run"), null, { state.onDeepClean?.run() }, primary = true)
                }
            }
        }

        // ── Temperature watchdog (device-wide, the same TempWatchdog singleton App Settings uses) ──
        IgSectionLabel(L("Temperature watchdog"))
        LabDashWatchdog()

        // ── Always-on auto-revert note ──
        Spacer(Modifier.height(10.dp))
        IgCard {
            IgInfo("🛡 " + L("Auto-revert on exit / background / crash — always on, can't be disabled."))
        }

        // ── Reset all per-game overrides ──
        if (overridden.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            IgButton(L("↺ Reset all game overrides to global"), null, { state.onResetAllPerf?.run() })
        }
        Spacer(Modifier.height(6.dp))
    }

    // ── acknowledgement dialogs ──
    pendingDanger?.let { pd ->
        LabDashDisclaimerDialog(
            title = pd.label,
            body = PerfDisclaimerCopy.ROOT_RISK,
            confirmLabel = L("Enable"),
            onConfirm = { pd.enable(); pendingDanger = null },
            onDismiss = { pendingDanger = null },
        )
    }
    if (showGrantGate) {
        LabDashDisclaimerDialog(
            title = L("Power-user performance — read first"),
            body = PerfDisclaimerCopy.ROOT_RISK,
            confirmLabel = L("Grant Root"),
            onConfirm = { showGrantGate = false; scope.launch { RootManager.requestGrant() } },
            onDismiss = { showGrantGate = false },
        )
    }

    if (showGalaxyOffPrompt) {
        SimpleActionSheet(
            title = L("Let Galaxy control performance?"),
            actions = listOf(
                MenuAction(L("Set Power profile to Off"), { applyProfile("Off"); showGalaxyOffPrompt = false }),
                MenuAction(L("Keep both"), { showGalaxyOffPrompt = false }),
            ),
            onDismiss = { showGalaxyOffPrompt = false },
            message = L("Galaxy Performance and the Power profile above both drive the CPU/GPU clocks, by different mechanisms (Samsung's SDK vs. the root perf tier). Running both at once lets them fight, so clock behaviour can be inconsistent.") +
                "\n\n" +
                L("Recommended: set the Power profile to \"Off\" so the Samsung SDK alone manages the clocks. Keep both only if you know what you're doing."),
        ) { it.run() }
    }
}

/**
 * The dashboard's window: no platform scrim and the whole panel at the panel-opacity value, so the slider shows the
 * game behind it (Classic does the same with setDimAmount(0) and a graphicsLayer alpha on its panel). Its own
 * composable so only it recomposes while the slider moves.
 */
@Composable
private fun LabDashWindowAlpha() {
    val opacity by PerformanceSettings.dialogOpacity.collectAsState()
    val view = LocalView.current
    val alpha = opacity
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        view.alpha = alpha
    }
}

/** One live readout tile: label, value + unit, and a fill bar (accent, error colour when [hot]). */
@Composable
private fun LabDashGauge(
    modifier: Modifier, label: String, value: String, unit: String,
    frac: Float, hot: Boolean, textOnly: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val bar = if (hot) cs.error else cs.primary
    IgCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label.uppercase(), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value, color = if (hot) cs.error else cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold,
                    fontSize = if (textOnly) 15.sp else 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unit.isNotEmpty() && value != "—") {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            if (!textOnly) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(deckShape(5.dp)).background(cs.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(5.dp).clip(deckShape(5.dp)).background(bar))
                }
            }
        }
    }
}

/**
 * One perf toggle: label (⚠ when [danger]), hint, the per-game override line, "Reset to global" while overridden, the
 * "?" explainer and the switch. Disabled rows grey out and drop the override line, as in Classic.
 */
@Composable
private fun LabDashToggleRow(
    label: String, sub: String, info: String, checked: Boolean, enabled: Boolean,
    overridden: Boolean, danger: Boolean = false, scopes: List<SaveScope>,
    onReset: () -> Unit, onToggle: (Boolean) -> Unit,
) {
    IgRow(
        if (danger) "⚠ $label" else label,
        hint = sub,
        note = when {
            !enabled -> null
            overridden -> L("● Per-game override")
            else -> L("○ Using global default")
        },
        scopes = scopes,
        enabled = enabled,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (enabled && overridden) IgButton(L("Reset to global"), null, onReset)
            IgHelpButton(label, info)
            IgSwitch(checked, enabled = enabled, onChange = onToggle)
        }
    }
}

/** Root-tier row: gated on grant + (for thermal / fan) the revert harness; dangerous keys enable through [onEnableDanger]. */
@Composable
private fun LabDashRootRow(
    label: String, key: String, info: String,
    toggles: Map<String, Boolean>, overridden: Set<String>,
    granted: Boolean, harnessProven: Boolean, danger: Boolean = false,
    scopes: List<SaveScope>,
    onReset: () -> Unit,
    onEnableDanger: (() -> Unit)?, setRoot: (String, Boolean) -> Unit,
) {
    val gated = PerfRootApplier.isHarnessGated(key) && !harnessProven
    val enabled = granted && !gated
    LabDashToggleRow(
        label = label,
        sub = if (danger) L("Root · dangerous — auto-reverts on exit.") else L("Root control."),
        info = info,
        checked = toggles[key] ?: false, enabled = enabled,
        overridden = key in overridden, danger = danger,
        scopes = scopes,
        onReset = onReset,
    ) { on ->
        if (on && onEnableDanger != null) onEnableDanger() // acknowledgement gate for thermal/fan
        else setRoot(key, on)
    }
}

/**
 * Lab variant of [com.winlator.star.ui.screens.WatchdogSection]: the same TempWatchdog flows and setters, the live
 * CPU / GPU temperatures, the device trip points, the threshold presets (each explains itself), the Manual slider and
 * the hard gate before the watchdog can be turned off.
 */
@Composable
private fun LabDashWatchdog() {
    val enabled by TempWatchdog.enabled.collectAsState()
    val mode by TempWatchdog.mode.collectAsState()
    val ceiling by TempWatchdog.ceilingC.collectAsState()

    var showOffDisclaimer by remember { mutableStateOf(false) }
    var infoMode by remember { mutableStateOf<ThresholdMode?>(null) }

    // Live device temperatures, polled ~1.5 s while visible (same HudMetrics reads as Classic).
    val context = LocalContext.current
    var cpuTemp by remember { mutableStateOf<Int?>(null) }
    var gpuTemp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val metrics = HudMetrics(context.applicationContext)
        while (true) {
            cpuTemp = metrics.cpuTempC
            gpuTemp = metrics.gpuTempC
            delay(1500)
        }
    }

    IgCard {
        IgRow(
            L("Thermal auto-revert (~%1\$s °C · %2\$s)", ceiling, labDashModeLabel(mode)),
            hint = if (TempWatchdog.hasDeviceTrips)
                L("Your device: throttles ~%1\$s °C · hard limit %2\$s °C", TempWatchdog.firstTripC, TempWatchdog.topTripC)
            else
                L("Your device didn't expose thermal limits — using a safe %1\$s °C fallback.", TempWatchdog.FALLBACK_CEILING_C),
            note = L("CPU %1\$s · GPU %2\$s", cpuTemp?.let { "$it °C" } ?: "—", gpuTemp?.let { "$it °C" } ?: "—"),
            scopes = listOf(SaveScope.ALL),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IgHelpButton(L("What is the Temperature Watchdog?"), com.winlator.star.ui.screens.WATCHDOG_WHATS_THIS)
                IgSwitch(enabled) { on -> if (on) TempWatchdog.setWatchdogEnabled(true) else showOffDisclaimer = true }
            }
        }
        IgDivider()
        IgRow(L("Threshold"), scopes = listOf(SaveScope.ALL), wideControl = true) {
            IgChips(
                options = ThresholdMode.values().map { m ->
                    m to L("%1\$s · ~%2\$s °C", labDashModeLabel(m), TempWatchdog.resolvedCeilingFor(m))
                },
                selected = mode,
            ) { m ->
                TempWatchdog.setMode(m)
                if (m != ThresholdMode.MANUAL) infoMode = m // presets explain themselves
            }
        }
        // Manual slider (only in MANUAL mode).
        if (mode == ThresholdMode.MANUAL) {
            val manual by TempWatchdog.manualCeilingC.collectAsState()
            val top = TempWatchdog.topTripC ?: TempWatchdog.MANUAL_MAX_C
            val sliderMax = minOf(TempWatchdog.MANUAL_MAX_C, top).coerceAtLeast(TempWatchdog.MANUAL_MIN_C + 1)
            var sliderVal by remember(manual) { mutableStateOf(manual.toFloat()) }
            val warn = sliderVal.roundToInt() > (top - 5)
            IgDivider()
            IgSliderRow(
                title = L("Manual: %1\$s °C", sliderVal.roundToInt()),
                value = sliderVal,
                range = TempWatchdog.MANUAL_MIN_C.toFloat()..sliderMax.toFloat(),
                step = 1f,
                format = { L("%1\$s °C", it.roundToInt()) },
                onChange = { sliderVal = it },
                onFinished = { TempWatchdog.setManualCeilingC(sliderVal.roundToInt()) },
                scopes = listOf(SaveScope.ALL),
            )
            if (warn) {
                IgNote(L("⚠ close to the hard limit (%1\$s °C)", top), error = true, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }

    // Info sheet for the picked preset.
    infoMode?.let { m ->
        LabInfoSheet(
            L("%1\$s · ~%2\$s °C", labDashModeLabel(m), TempWatchdog.resolvedCeilingFor(m)),
            com.winlator.star.ui.screens.presetInfo(m),
        ) { infoMode = null }
    }

    // Hard disclaimer to disarm the watchdog.
    if (showOffDisclaimer) {
        LabDashDisclaimerDialog(
            title = L("Disable thermal safety?"),
            body = PerfDisclaimerCopy.WATCHDOG_OFF,
            confirmLabel = L("Turn watchdog OFF"),
            onDismiss = { showOffDisclaimer = false },
            onConfirm = { showOffDisclaimer = false; TempWatchdog.setWatchdogEnabled(false) },
        )
    }
}

private fun labDashModeLabel(mode: ThresholdMode): String = when (mode) {
    ThresholdMode.CONSERVATIVE -> L("Conservative")
    ThresholdMode.BALANCED -> L("Balanced")
    ThresholdMode.AGGRESSIVE -> L("Aggressive")
    ThresholdMode.MANUAL -> L("Manual")
}

/**
 * Lab variant of [com.winlator.star.ui.screens.PerfDisclaimerDialog]: the risk text must be scrolled to its end AND
 * "I understand and accept all risk" switched on before [confirmLabel] works. With a controller the text box takes
 * focus and d-pad up / down scrolls it; at the end, down moves on to the switch.
 */
@Composable
private fun LabDashDisclaimerDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var accepted by remember { mutableStateOf(false) }
    // Content that fits without scrolling (maxValue 0) counts as seen in full; otherwise the scroll must reach the end.
    val reachedBottom by remember {
        derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 }
    }
    val canConfirm = reachedBottom && accepted

    LabInGameDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 560.dp,
        scroll = false,
        footer = {
            IgButton(L("Cancel"), null, onDismiss)
            IgButton(confirmLabel, null, { if (canConfirm) onConfirm() }, danger = true, enabled = canConfirm)
        },
    ) {
        val shape = deckShape(10.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> if (scroll.value < scroll.maxValue) {
                            scope.launch { scroll.animateScrollTo(scroll.value + 160) }
                            true
                        } else false
                        Key.DirectionUp -> if (scroll.value > 0) {
                            scope.launch { scroll.animateScrollTo((scroll.value - 160).coerceAtLeast(0)) }
                            true
                        } else false
                        else -> false
                    }
                }
                .deckFocusRing(shape, scaleTo = 1f)
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(body, color = SimpleStrong, fontFamily = NunitoSansFamily, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (!reachedBottom) {
            IgNote(L("Scroll to the end to continue."), modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        IgCard {
            IgRow(L("I understand and accept all risk"), enabled = reachedBottom) {
                IgSwitch(accepted, enabled = reachedBottom) { accepted = it }
            }
        }
    }
}

/** Parse a leading integer out of a readout like "650", "650 MHz", "47°C". Null when none. */
private fun labDashLeadingInt(s: String?): Int? =
    s?.trim()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

/** A Classic "?" help string (small HTML: <b>, <br/>) as plain text for a Lab help sheet. */
@Composable
private fun labDashHelpText(@StringRes res: Int): String {
    val ctx = LocalContext.current
    return remember(res) { com.winlator.star.ui.screens.htmlToAnnotated(ctx.getString(res)).text }
}

/* ═══════════════════ Audio ═══════════════════ */

/**
 * Lab variant of [com.winlator.star.ui.components.AudioSettingsDialog], same parameters and semantics: the tradeoff
 * meter, the five presets (each with its "?"), the fine-tune rows (editable on Custom, otherwise greyed and showing
 * what the engine really applies), the "next launch" tag on the latency row, and Save → [onSave] with the edited config.
 */
@Composable
internal fun LabAudioSettingsDialog(
    initial: AudioConfig,
    scopeLabel: String,
    latencyLive: Boolean,
    driverLabel: String = "",
    driverId: String = "",
    onDismiss: () -> Unit,
    onSave: (AudioConfig) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var cfg by remember { mutableStateOf(initial) }
    val custom = cfg.preset == PRESET_CUSTOM
    val curPreset = AUDIO_PRESETS.first { it.id == cfg.preset }
    // On DirectAudio the latency field sets the driver's buffer, so a preset must not stamp its Pulse-shaped value over it.
    val directBuffer = latencyIsDirectBuffer(driverId)
    fun applyPreset(p: AudioPreset) {
        cfg = if (directBuffer) p.cfg.copy(latencyMsec = cfg.latencyMsec) else p.cfg
    }

    LabInGameDialog(
        title = L("Audio settings"),
        onDismiss = onDismiss,
        subtitle = L("Balance crackle-free vs low delay") + "  ·  " + scopeLabel,
        maxWidth = 680.dp,
        tall = true,
        // Engine badge: which audio engine these settings hit.
        actions = { if (driverLabel.isNotBlank()) IgBadge("🔊 $driverLabel") },
        footer = {
            IgButton(L("Cancel"), null, onDismiss)
            IgButton(L("Save"), null, { onSave(cfg) }, primary = true)
        },
    ) {
        // ── Tradeoff meter ──
        IgCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(L("◀ Lower delay"), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(L("Fewer crackles ▶"), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(deckShape(6.dp)).background(cs.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(curPreset.pos).height(8.dp).clip(deckShape(6.dp)).background(cs.primary))
                }
                Text(L(curPreset.note), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, modifier = Modifier.padding(top = 9.dp))
            }
        }

        // ── Presets ──
        IgSectionLabel(L("Preset"))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AUDIO_PRESETS.forEach { p ->
                LabAudioPresetTile(p, selected = p.id == cfg.preset, help = labDashHelpText(p.helpRes)) { applyPreset(p) }
            }
        }

        // ── Fine-tune ──
        IgSectionLabel(if (custom) L("Fine-tune") else L("Fine-tune — what this preset applies (Custom to edit)"))
        // Greyed rows show what the engine will really use (effectiveConfigFor); edits still go to cfg, and the two are
        // equal whenever the rows are editable.
        val shown = effectiveConfigFor(cfg, driverId)
        IgCard {
            // Output mode. DirectAudio offers no Power: its AAudio power-saving stream reopens ~1/s under box64/FEX.
            val perfOpts = if (driverId == "directaudio")
                listOf(0 to L("None"), 1 to L("Low lat."))
            else listOf(0 to L("None"), 1 to L("Low lat."), 2 to L("Power"))
            IgRow(L("Output mode"), hint = L("AAudio performance mode"), enabled = custom, wideControl = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IgHelpButton(L("Output mode"), labDashHelpText(R.string.help_audio_perf_mode))
                    IgSegmented(perfOpts, shown.perfMode, enabled = custom) { v -> cfg = cfg.copy(perfMode = v) }
                }
            }
            IgDivider()
            IgRow(L("Adaptive buffer"), hint = L("Auto-grow only if it hears crackle"), enabled = custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IgHelpButton(L("Adaptive buffer"), labDashHelpText(R.string.help_audio_adaptive))
                    IgSwitch(shown.adaptive, enabled = custom) { cfg = cfg.copy(adaptive = it) }
                }
            }
            IgDivider()
            // DirectAudio: the driver's own buffer (reaches the one-burst floor, label states the total with Android's
            // ~21 ms). PulseAudio: the guest winepulse buffer. ALSA: not used.
            val latencyUsed = latencyUsedBy(driverId)
            val latTitle = if (directBuffer) L("Audio buffer") else L("Guest buffer")
            val latHint = if (!latencyUsed) L("not used by ALSA — set the buffer rows below")
            else (if (directBuffer) L("≈%1\$s ms total · %2\$s ms buffer", shown.latencyMsec + ANDROID_OUTPUT_MS, shown.latencyMsec)
                  else L("winepulse · %1\$s ms", shown.latencyMsec)) +
                if (!latencyLive) "  " + L("· next launch") else ""
            val latHelp = when {
                !latencyUsed -> R.string.help_audio_latency_unused
                directBuffer -> R.string.help_audio_direct_buffer
                else -> R.string.help_audio_guest_buffer
            }
            IgRow(latTitle, hint = latHint, enabled = custom, wideControl = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IgHelpButton(latTitle, labDashHelpText(latHelp))
                    Box(Modifier.weight(1f)) {
                        if (directBuffer) {
                            IgSlider(
                                value = shown.latencyMsec.toFloat(),
                                range = 4f..120f,
                                step = 4f,
                                enabled = custom && latencyUsed,
                                format = { L("%1\$s ms", it.toInt()) },
                                onChange = { cfg = cfg.copy(latencyMsec = (it / 4).toInt() * 4) },
                                onFinished = {},
                            )
                        } else {
                            IgSlider(
                                value = shown.latencyMsec.toFloat(),
                                range = 20f..200f,
                                step = 10f,
                                enabled = custom && latencyUsed,
                                format = { L("%1\$s ms", it.toInt()) },
                                onChange = { cfg = cfg.copy(latencyMsec = (it / 10).toInt() * 10) },
                                onFinished = {},
                            )
                        }
                    }
                }
            }
            IgDivider()
            LabAudioStepperRow(
                L("Initial sink buffer"), L("frames · 0 = auto"),
                if (shown.bufferFrames > 0) "${shown.bufferFrames}" else L("auto"), custom,
                help = labDashHelpText(R.string.help_audio_buffer_frames),
            ) { cfg = cfg.copy(bufferFrames = (cfg.bufferFrames + it * 256).coerceAtLeast(0)) }
            IgDivider()
            LabAudioStepperRow(
                L("Max sink buffer"), L("growth cap · 0 = device max"),
                if (shown.maxBufferFrames > 0) "${shown.maxBufferFrames}" else L("device max"), custom,
                help = labDashHelpText(R.string.help_audio_max_buffer_frames),
            ) { cfg = cfg.copy(maxBufferFrames = (cfg.maxBufferFrames + it * 256).coerceAtLeast(0)) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * One preset: a radio mark, the emoji, name + badge, the one-line description, and its own "?" (which explains the
 * preset without picking it). A or a tap on the tile picks it. The "?" sits beside the pickable part, not inside it,
 * so the d-pad reaches it (right from the tile).
 */
@Composable
private fun LabAudioPresetTile(p: AudioPreset, selected: Boolean, help: String, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else cs.surfaceContainerHigh.copy(alpha = igRaised(0.85f)))
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else deckLineAt(0.4f), shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .deckFocusRing(shape, scaleTo = 1f)
                .clickable(role = Role.RadioButton, onClick = onPick)
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                Modifier.size(18.dp).border(2.dp, if (selected) cs.primary else cs.onSurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(9.dp).background(cs.primary, CircleShape))
            }
            Spacer(Modifier.width(10.dp))
            Text(p.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L(p.name), color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p.badge?.let {
                        Spacer(Modifier.width(8.dp))
                        IgBadge(L(it).uppercase())
                    }
                }
                Text(L(p.desc), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp)
            }
        }
        Box(Modifier.padding(end = 10.dp)) { IgHelpButton(L(p.name), help) }
    }
}

/** A frames row with − / value / + (256-frame steps); the "?" stays usable while the row is greyed. */
@Composable
private fun LabAudioStepperRow(
    name: String, hint: String, value: String, enabled: Boolean, help: String,
    onStep: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    IgRow(name, hint = hint, enabled = enabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IgHelpButton(name, help)
            IgButton("−", null, { onStep(-1) }, enabled = enabled)
            Text(
                value, color = cs.primary, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.widthIn(min = 72.dp),
            )
            IgButton("+", null, { onStep(1) }, enabled = enabled)
        }
    }
}
