package com.winlator.star.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.T
import com.winlator.star.widget.exportHudDiagnostics
import com.winlator.star.widget.fusionhud.FusionSize
import com.winlator.star.widget.perfhud.parseHudOutline
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Lab-drawn versions of the Classic drawer's frame generation section (FrameGenSection), HUD tab (HudContent,
// embedded) and TV tab (TvContent). Same state flows, setters and callbacks in the same order as Classic.

// ───────────────────────────── Frame generation ─────────────────────────────

/**
 * Lab replacement for the Classic FrameGenSection: one card, placed under the page's "Frame generation" heading.
 * The multiplier writes this session only; model, flow scale, capture resolution and performance mode the container.
 */
@Composable
internal fun LabFrameGenPanel(state: XServerDrawerState) {
    val cs = MaterialTheme.colorScheme
    val frameGenEnabled by state.frameGenEnabled.collectAsState()
    val initFgMult by state.frameGenMultiplier.collectAsState()
    val initFgFlow by state.frameGenFlowScale.collectAsState()
    val initFgModel by state.frameGenModel.collectAsState()
    val initFgPreset by state.frameGenPerfPreset.collectAsState()
    val engine by state.frameGenEngine.collectAsState()
    val layerActive by state.bionicFgActive.collectAsState()
    val initLsfgPerf by state.lsfgPerformanceMode.collectAsState()
    val winFgNative by state.winFgNative.collectAsState()
    val nativeFgLocks by state.nativeFgLocks.collectAsState()
    val fpsCap by state.fpsLimit.collectAsState()
    val displayTargetHz by state.displayTargetHz.collectAsState()
    val supportedRates by state.supportedRefreshRates.collectAsState()
    val liveRate by state.currentRefreshRate.collectAsState()
    val matchRefresh by state.matchRefreshRate.collectAsState()
    val vrrOk by state.vrrSupported.collectAsState()
    val fgUnavailable by state.fgUnavailableReason.collectAsState()
    val fgUnavailableDetail by state.fgUnavailableDetail.collectAsState()
    val isWaylandSession by state.isWaylandMode.collectAsState()
    val waylandFgOk by state.waylandFrameGenAvailable.collectAsState()
    val waylandFgBlocked = isWaylandSession && !waylandFgOk
    val readout by state.frameGenReadout.collectAsState()

    val engineLabel = when (engine) {
        "lsfg" -> "lsfg-vk"
        "lsfg-native" -> L("LSFG Native")
        "bionic" -> if (winFgNative) L("Win-FG Native") else "win-fg"
        "wfg" -> "WFG"
        else -> L("Off")
    }
    // Green dot = the engine is multiplying frames right now (frame gen starts at Off every launch).
    val isRunning = layerActive && engine != "off" && initFgMult > 0

    IgCard {
        IgRow(L("Engine"), hint = readout.ifEmpty { null }) {
            IgBadge(engineLabel, dot = if (isRunning) Color(0xFF4CAF50) else cs.onSurface.copy(alpha = 0.35f))
        }

        if (frameGenEnabled) {
            var fgMult by remember(initFgMult) { mutableIntStateOf(initFgMult) }
            var fgFlow by remember(initFgFlow) { mutableFloatStateOf(initFgFlow) }
            var fgModel by remember(initFgModel) { mutableIntStateOf(initFgModel) }
            var fgPreset by remember(initFgPreset) { mutableIntStateOf(initFgPreset) }
            fun applyFg() {
                state.setFrameGenMultiplier(fgMult)
                state.setFrameGenFlowScale(fgFlow)
                state.setFrameGenModel(fgModel)
                state.setFrameGenPerfPreset(fgPreset)
                state.onBionicFgConfigChange?.run()
            }
            val multEnabled = fgUnavailable.isEmpty() && !waylandFgBlocked

            IgDivider()
            if (waylandFgBlocked) IgNote(L("Not on Wayland in this build yet."), modifier = Modifier.padding(top = 8.dp))
            if (fgUnavailable.isNotEmpty()) {
                IgNote("⚠ $fgUnavailable", error = true, modifier = Modifier.padding(top = 8.dp))
                if (fgUnavailableDetail.isNotEmpty()) IgNote(L("Driver check: %1\$s", fgUnavailableDetail))
            }
            val multOptions = if (engine == "bionic") listOf(0 to L("Off"), 2 to L("On"))
            else listOf(0 to L("Off"), 2 to "2×", 3 to "3×", 4 to "4×")
            IgRow(
                L("Frame generation"),
                note = L("Starts off each launch."),
                scopes = listOf(SaveScope.SESSION),
                wideControl = true,
                enabled = multEnabled,
            ) {
                IgSegmented(multOptions, if (multEnabled) fgMult else null, enabled = multEnabled) { m ->
                    fgMult = m
                    applyFg()
                }
            }

            // Fit advice while LSFG Native / Win-FG Native generate (Classic FgFitAdvice under the multiplier).
            if (nativeFgLocks && !waylandFgBlocked) {
                LabPerfFgFitAdvice(
                    cap = fpsCap,
                    mult = fgMult,
                    screen = labPerfRememberFgScreen(displayTargetHz, supportedRates, liveRate),
                    supported = supportedRates,
                    autoOn = matchRefresh && vrrOk,
                    canChangeMult = engine == "lsfg-native",
                ) { fix ->
                    state.setFpsLimit(fix)
                    state.onFpsLimitChange?.run()
                }
            }

            // win-fg only, while on. Legacy stored models 0-2 show as Optical flow (3), as in Classic.
            if (engine == "bionic" && fgMult > 0 && !waylandFgBlocked) {
                IgDivider()
                IgRow(L("Model"), scopes = listOf(SaveScope.CONTAINER), wideControl = true) {
                    IgSegmented(
                        listOf(3 to L("Optical flow"), 4 to L("Bidirectional")),
                        if (fgModel < 3) 3 else fgModel,
                    ) { m ->
                        fgModel = m
                        applyFg()
                    }
                }
            }

            // WFG has no flow scale.
            if (fgMult > 0 && engine != "wfg" && !waylandFgBlocked) {
                IgDivider()
                IgSliderRow(
                    title = L("Flow scale"),
                    value = fgFlow,
                    range = 0.2f..1.0f,
                    step = 0.04f,
                    format = { "%.2f".format(it) },
                    onChange = { fgFlow = it },
                    onFinished = { applyFg() },
                    hint = L("Higher flow scale = smoother motion estimate, more GPU cost."),
                    scopes = listOf(SaveScope.CONTAINER),
                )
            }

            if (engine == "lsfg-native" && com.winlator.star.FeatureFlags.LSFG_NATIVE_EXPERIMENTS_ENABLED && !waylandFgBlocked) {
                val capture by state.fgCaptureResolution.collectAsState()
                val panelHeight by state.fgPanelHeight.collectAsState()
                IgDivider()
                LabPerfFgCaptureRow(capture, panelHeight) { newValue ->
                    state.setFgCaptureResolution(newValue)
                    applyFg()
                }
            }

            if (engine == "lsfg" && !waylandFgBlocked) {
                var lsfgPerf by remember(initLsfgPerf) { mutableStateOf(initLsfgPerf) }
                IgDivider()
                IgToggleRow(
                    L("Performance mode"),
                    lsfgPerf,
                    hint = L("Lower quality for higher FPS — helps on low-end devices."),
                    scopes = listOf(SaveScope.CONTAINER),
                ) {
                    lsfgPerf = it
                    state.setLsfgPerformanceMode(it)
                    applyFg()
                }
            }
        } else if (waylandFgBlocked) {
            IgDivider()
            IgNote(L("Not on Wayland in this build yet."), modifier = Modifier.padding(vertical = 6.dp))
        } else {
            IgDivider()
            IgNote(L("Off for this container. Turn it on in the container's settings to use it here."), modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

// What the display is really doing, for the fit advice (copy of XServerDrawer's private FgScreen / rememberFgScreen).
private data class LabPerfFgScreen(val hz: Int, val asked: Int, val held: Boolean)

@Composable
private fun labPerfRememberFgScreen(target: Int, supported: List<Int>, current: Int): LabPerfFgScreen {
    val asked = if (target > 0) target else supported.maxOrNull() ?: current
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(asked, current) {
        held = false
        if (asked > 0 && current in 1 until asked - 1) {
            delay(2500)
            held = true
        }
    }
    return LabPerfFgScreen(if (held) current else asked, asked, held)
}

/** Classic FgFitAdvice: over the display's rate the over-limit warning; under it with Auto on, an exact-fit cap. */
@Composable
private fun LabPerfFgFitAdvice(
    cap: Int,
    mult: Int,
    screen: LabPerfFgScreen,
    supported: List<Int>,
    autoOn: Boolean,
    canChangeMult: Boolean,
    onSetCap: (Int) -> Unit,
) {
    if (cap <= 0 || mult < 2 || screen.hz <= 0) return
    val made = cap * mult
    if (made > screen.hz) {
        LabPerfFgOverLimitWarning(cap, mult, screen, canChangeMult, onSetCap)
        return
    }
    if (!autoOn || screen.held || made == screen.hz) return
    val fits = supported.filter { it % mult == 0 && it / mult >= 10 }.map { (it / mult) to it }
    val lower = fits.lastOrNull { it.first <= cap }
    val higher = fits.firstOrNull { it.first > cap }
    val pick = lower ?: higher ?: return
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IgNote(
            L(
                "Screen set to %1\$s Hz, the closest speed above %2\$s × %3\$s = %4\$s. A few refreshes repeat a picture. For a perfect fit:",
                screen.hz, cap, mult, made,
            )
        )
        LabPerfFixLine(
            label = L("Set frame rate limit to %1\$s → %2\$s Hz", pick.first, pick.second),
            danger = false,
            alt = if (lower != null && higher != null) L("or %1\$s → %2\$s Hz if the game holds it", higher.first, higher.second) else null,
        ) { onSetCap(pick.first) }
    }
}

/** Classic FgOverLimitWarning: cap × multiplier is more than the screen shows. Win-FG Native can't change multiplier. */
@Composable
private fun LabPerfFgOverLimitWarning(
    cap: Int,
    mult: Int,
    screen: LabPerfFgScreen,
    canChangeMult: Boolean,
    onSetCap: (Int) -> Unit,
) {
    val fixCap = maxOf(10, screen.hz / mult)
    val fitMult = if (canChangeMult) (mult - 1 downTo 2).firstOrNull { cap * it <= screen.hz } else null
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IgNote(
            L(
                "⚠ %1\$s × %2\$s = %3\$s: more than your %4\$s Hz screen can show. The extra frames pile up, so expect stutter and laggy controls.",
                cap, mult, cap * mult, screen.hz,
            ),
            error = true,
        )
        if (screen.held) {
            IgNote(
                L(
                    "Your device is keeping the screen at %1\$s Hz (Bannerlator asked for %2\$s Hz). Battery saver or your device's own refresh-rate setting may be holding it.",
                    screen.hz, screen.asked,
                )
            )
        }
        LabPerfFixLine(
            label = L("Set frame rate limit to %1\$s", fixCap),
            danger = true,
            alt = fitMult?.let { L("or pick %1\$s×", it) },
        ) { onSetCap(fixCap) }
    }
}

/** A fix button (Classic FgFixButton) with its optional "or …" alternative beside it. */
@Composable
private fun LabPerfFixLine(label: String, danger: Boolean, alt: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        IgButton(label, null, onClick, danger = danger)
        if (alt != null) {
            Spacer(Modifier.width(10.dp))
            Text(alt, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * Classic FgCaptureChips: Panel, Game, then every distinct height of the container Screen Size list that the renderer
 * would not clamp ([panel/4, panel)). Stored value "panel" / "game" / a bare height.
 */
@Composable
private fun LabPerfFgCaptureRow(selected: String, panelHeight: Int, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val heights = remember(panelHeight) {
        val minH = maxOf(16, panelHeight / 4)
        context.resources.getStringArray(R.array.screen_size_entries)
            .filterNot { it.equals("custom", ignoreCase = true) }
            .map { Container.fgCaptureHeightFor(it.substringBefore(" ")) }
            .filter { it > 0 && (panelHeight <= 0 || (it >= minH && it < panelHeight)) }
            .distinct()
            .sorted()
    }
    val options = listOf(Container.FG_CAPTURE_PANEL to L("Panel"), Container.FG_CAPTURE_GAME to L("Game")) +
        heights.map { it.toString() to "${it}p" }
    // Panel / Game match by value; a height matches by its resolved height (so "1280x720" lights 720p).
    val selectedKey = if (selected == Container.FG_CAPTURE_PANEL || selected == Container.FG_CAPTURE_GAME) selected
    else Container.fgCaptureHeightFor(selected).toString()
    IgRow(
        L("Capture resolution"),
        hint = L("Height the frame-gen chain runs at (width follows the screen). Lower = far cheaper on the GPU, softer picture. Panel = full resolution."),
        note = L("Experimental"),
        scopes = listOf(SaveScope.CONTAINER),
        wideControl = true,
    ) {
        IgChips(options, selectedKey) { onSelect(it) }
    }
}

// ───────────────────────────── Performance overlay (HUD) ─────────────────────────────

/** Where the overlay settings save: the shortcut when the HUD config lives on it, else the container. */
@Composable
private fun labPerfHudScope(): SaveScope {
    val onShortcut by DeckInGameState.hudSavedToShortcut.collectAsState()
    return if (onShortcut) SaveScope.GAME else SaveScope.CONTAINER
}

/** Parses the HUD config string the way Classic HudContent's parseConfig does. */
private fun labPerfParseHudConfig(s: String): Map<String, String> {
    if (s.isEmpty()) return emptyMap()
    val map = mutableMapOf<String, String>()
    s.split(",").forEach { part ->
        val eq = part.indexOf('=')
        if (eq >= 0) map[part.substring(0, eq)] = part.substring(eq + 1)
    }
    return map
}

/**
 * Lab replacement for Classic `HudContent(state, embedded = true)`: the Show switch card, then (while it is on) Style &
 * size, Metrics, Appearance, Alerts & temperature and Tools, each under its own heading. Every change rebuilds the
 * full config with Classic's key set and order and sends it through onFpsConfigApply.
 */
@Composable
internal fun LabHudPanel(state: XServerDrawerState) {
    val fpsConfig by state.fpsConfig.collectAsState()
    // Classic re-reads the live display refresh rate whenever this tab opens.
    LaunchedEffect(Unit) { state.onRefreshRatePoll?.run() }
    val scopes = listOf(labPerfHudScope())

    val cfg = remember(fpsConfig) { labPerfParseHudConfig(fpsConfig) }
    fun b(k: String, fb: String, d: String) = (cfg[k] ?: cfg[fb] ?: d) == "1"

    val hudMode = remember(cfg) { cfg.getOrDefault("hudMode", "vertical") }
    var hudEnabled by remember(cfg) { mutableStateOf(b("hudEnabled", "hudEnabled", "1")) }
    val styles = listOf("classic", "gamehub", "gamenative", "fusion")
    var hudStyle by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudStyle", "fusion")) }
    val gameHub = hudStyle == "gamehub"
    val gameNative = hudStyle == "gamenative"
    val fusion = hudStyle == "fusion"
    val rich = gameHub || gameNative || fusion
    val fusionSizes = listOf("full", "tiles", "pill", "minimal", "mega")
    var fusionSize by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudSize", "pill")) }
    val fusionChips = FusionSize.from(fusionSize).supportedChips()
    val gpuModelDefault = if (cfg.getOrDefault("hudStyle", "fusion") == "fusion") "1" else "0"
    val clockDefault = if (cfg.getOrDefault("hudStyle", "fusion") == "fusion") "1" else "0"
    var showFPS by remember(cfg) { mutableStateOf(b("showFPS", "showFPS", "1")) }
    var showGraph by remember(cfg) { mutableStateOf(b("showFPSGraph", "showFPSGraph", "0")) }
    var showCPU by remember(cfg) { mutableStateOf(b("showCPUUsage", "showCPULoad", "1")) }
    var showGPU by remember(cfg) { mutableStateOf(b("showGPULoad", "showGPULoad", "1")) }
    var showRAM by remember(cfg) { mutableStateOf(b("showRAM", "showRAM", "1")) }
    var showPower by remember(cfg) { mutableStateOf(b("showPower", "showPower", "1")) }
    var showTemp by remember(cfg) { mutableStateOf(b("showTemp", "showBatteryTemp", "1")) }
    var showEngine by remember(cfg) { mutableStateOf(b("showEngine", "showRenderer", "1")) }
    var showGpuModel by remember(cfg) { mutableStateOf(b("showGpuModel", "showGpuModel", gpuModelDefault)) }
    var dualBattery by remember(cfg) { mutableStateOf(b("hudDualBattery", "hudDualBattery", "0")) }
    var showGpuTemp by remember(cfg) { mutableStateOf(b("showGpuTemp", "showGpuTemp", "0")) }
    var showBattery by remember(cfg) { mutableStateOf(b("showBattery", "showBattery", "0")) }
    var showRuntime by remember(cfg) { mutableStateOf(b("showRuntime", "showRuntime", "0")) }
    var showClock by remember(cfg) { mutableStateOf(b("showClock", "showClock", clockDefault)) }
    var showCpuGraph by remember(cfg) { mutableStateOf(b("showCPUGraph", "showCPUGraph", "0")) }
    var showGpuGraph by remember(cfg) { mutableStateOf(b("showGPUGraph", "showGPUGraph", "0")) }
    var showVram by remember(cfg) { mutableStateOf(b("showVram", "showVram", "1")) }
    var showLow001 by remember(cfg) { mutableStateOf(b("showLow001", "showLow001", "1")) }
    var fpsDecimal by remember(cfg) { mutableStateOf(b("fpsDecimal", "fpsDecimal", "1")) }
    var hudLocked by remember(cfg) { mutableStateOf(b("hudLocked", "hudLocked", "0")) }
    var showPerCore by remember(cfg) { mutableStateOf(b("showPerCore", "showPerCore", "1")) }
    var showSwap by remember(cfg) { mutableStateOf(b("showSwap", "showSwap", "1")) }
    var showNet by remember(cfg) { mutableStateOf(b("showNet", "showNet", "1")) }
    var showResolution by remember(cfg) { mutableStateOf(b("showResolution", "showResolution", "1")) }
    var showProton by remember(cfg) { mutableStateOf(b("showProton", "showProton", "1")) }
    var showWrapper by remember(cfg) { mutableStateOf(b("showWrapper", "showWrapper", "1")) }
    var showDxVer by remember(cfg) { mutableStateOf(b("showDxVer", "showDxVer", "1")) }
    var showSession by remember(cfg) { mutableStateOf(b("showSession", "showSession", "1")) }
    var tempUnitF by remember(cfg) { mutableStateOf(cfg.getOrDefault("tempUnit", "c").equals("f", true)) }
    var tempBands by remember(cfg) { mutableStateOf(cfg.getOrDefault("tempBands", "1") != "0") }
    var tempAuto by remember(cfg) { mutableStateOf(cfg.getOrDefault("tempAuto", "1") != "0") }
    var tempRedCpu by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("tempRedCpu", "90").toFloatOrNull() ?: 90f) }
    var tempRedGpu by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("tempRedGpu", "90").toFloatOrNull() ?: 90f) }
    var tempRedBat by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("tempRedBat", "48").toFloatOrNull() ?: 48f) }

    var scaleValue by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("hudScale", Container.DEFAULT_HUD_SCALE.toString()).toFloatOrNull() ?: Container.DEFAULT_HUD_SCALE.toFloat()) }
    var opacityValue by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("hudOpacity", "80").toFloatOrNull() ?: 80f) }
    var transValue by remember(cfg) { mutableFloatStateOf(cfg.getOrDefault("hudTransparency", "0").toFloatOrNull() ?: 0f) }

    val skins = listOf("classic", "neon", "mono")
    val colors = listOf("soft", "mid", "vivid")
    var skin by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudSkin", "classic")) }
    var color by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudColor", "mid")) }
    var outlineValue by remember(cfg) { mutableFloatStateOf(parseHudOutline(cfg.getOrDefault("hudOutline", "40")).toFloat()) }
    var outlineAccent by remember(cfg) { mutableStateOf(cfg.getOrDefault("hudOutlineAccent", "1") == "1") }

    fun i(v: Boolean) = if (v) "1" else "0"
    // Same key set and order as Classic HudContent.buildConfig (and the pre-launch FpsCounterConfigDialog).
    fun buildConfig(): String = listOf(
        "hudStyle=$hudStyle",
        "hudEnabled=${i(hudEnabled)}",
        "hudSize=$fusionSize",
        "hudLocked=${i(hudLocked)}",
        "showVram=${i(showVram)}",
        "showLow001=${i(showLow001)}",
        "fpsDecimal=${i(fpsDecimal)}",
        "showPerCore=${i(showPerCore)}",
        "showSwap=${i(showSwap)}",
        "showNet=${i(showNet)}",
        "showResolution=${i(showResolution)}",
        "showProton=${i(showProton)}",
        "showWrapper=${i(showWrapper)}",
        "showDxVer=${i(showDxVer)}",
        "showSession=${i(showSession)}",
        "hudMode=$hudMode",
        "showFPS=${i(showFPS)}",
        "showFPSGraph=${i(showGraph)}",
        "showCPUUsage=${i(showCPU)}",
        "showCPULoad=${i(showCPU)}",
        "showGPULoad=${i(showGPU)}",
        "showRAM=${i(showRAM)}",
        "showPower=${i(showPower)}",
        "showTemp=${i(showTemp)}",
        "showBatteryTemp=${i(showTemp)}",
        "showEngine=${i(showEngine)}",
        "showRenderer=${i(showEngine)}",
        "showGpuModel=${i(showGpuModel)}",
        "hudDualBattery=${i(dualBattery)}",
        "showGpuTemp=${i(showGpuTemp)}",
        "showBattery=${i(showBattery)}",
        "showRuntime=${i(showRuntime)}",
        "showClock=${i(showClock)}",
        "showCPUGraph=${i(showCpuGraph)}",
        "showGPUGraph=${i(showGpuGraph)}",
        "tempUnit=${if (tempUnitF) "f" else "c"}",
        "tempBands=${i(tempBands)}",
        "tempAuto=${i(tempAuto)}",
        "tempRedCpu=${tempRedCpu.toInt()}",
        "tempRedGpu=${tempRedGpu.toInt()}",
        "tempRedBat=${tempRedBat.toInt()}",
        "hudSkin=$skin",
        "hudColor=$color",
        "hudOutline=${outlineValue.toInt()}",
        "hudOutlineAccent=${if (outlineAccent) 1 else 0}",
        "hudScale=${scaleValue.toInt()}",
        "hudOpacity=${opacityValue.toInt()}",
        "hudTransparency=${transValue.toInt()}",
    ).joinToString(",")

    fun applyHud() { state.onFpsConfigApply?.invoke(buildConfig()) }

    IgCard {
        IgToggleRow(
            L("Show performance overlay"),
            hudEnabled,
            scopes = scopes,
            note = L("Turning the overlay on when it wasn't built this launch also switches it on in the container"),
        ) { hudEnabled = it; applyHud() }
    }

    if (!hudEnabled) return

    // ── Style & size ──
    IgSectionLabel(L("Style & size"))
    IgCard {
        val styleHint = when (hudStyle) {
            "gamehub" -> L("Rich overlay: skins, colored fields, live FPS graph. Style change applies on next launch.")
            "gamenative" -> L("GameNative-style overlay: compact pill or stacked list with live graphs. Style change applies on next launch.")
            "fusion" -> L("Fusion overlay: one color-coded look in 5 sizes with percentile lows, VRAM + a Mega everything-view. Tap the HUD to cycle size.")
            else -> L("Classic Bannerlator overlay.")
        }
        LabPerfHudChoiceRow(
            L("Overlay style"),
            listOf("Classic", "GameHub", "GameNative", "Fusion"),
            styles.indexOf(hudStyle).coerceAtLeast(0),
            scopes,
            hint = styleHint,
        ) { hudStyle = styles[it]; applyHud() }
        if (fusion) {
            IgDivider()
            LabPerfHudChoiceRow(
                L("Size"),
                listOf(L("Full"), L("Tiles"), L("Pill"), L("Minimal"), L("Mega")),
                fusionSizes.indexOf(fusionSize).coerceAtLeast(0),
                scopes,
            ) { fusionSize = fusionSizes[it]; applyHud() }
        }
    }

    // ── Metrics ── Chip ids are the English labels FusionSize.supportedChips() returns; only the display is translated.
    fun show(label: String, styleOk: Boolean): Boolean = if (fusion) label in fusionChips else styleOk
    val metricChips = buildList<Triple<String, Boolean, () -> Unit>> {
        if (show("FPS", true)) add(Triple(T("FPS"), showFPS) { showFPS = !showFPS; applyHud() })
        if (show("FPS graph", rich)) add(Triple(T("FPS graph"), showGraph) { showGraph = !showGraph; applyHud() })
        if (show("CPU", true)) add(Triple(T("CPU"), showCPU) { showCPU = !showCPU; applyHud() })
        if (!fusion && gameNative) add(Triple(T("CPU graph"), showCpuGraph) { showCpuGraph = !showCpuGraph; applyHud() })
        if (show("GPU", true)) add(Triple(T("GPU"), showGPU) { showGPU = !showGPU; applyHud() })
        if (!fusion && gameNative) add(Triple(T("GPU graph"), showGpuGraph) { showGpuGraph = !showGpuGraph; applyHud() })
        if (show("VRAM", false)) add(Triple(T("VRAM"), showVram) { showVram = !showVram; applyHud() })
        if (show("RAM", true)) add(Triple(T("RAM"), showRAM) { showRAM = !showRAM; applyHud() })
        if (show("Power", true)) add(Triple(T("Power"), showPower) { showPower = !showPower; applyHud() })
        if (show("Temp", true)) add(Triple(T("Temp"), showTemp) { showTemp = !showTemp; applyHud() })
        if (show("GPU temp", gameNative)) add(Triple(T("GPU temp"), showGpuTemp) { showGpuTemp = !showGpuTemp; applyHud() })
        if (show("Battery", gameNative)) add(Triple(T("Battery"), showBattery) { showBattery = !showBattery; applyHud() })
        if (!fusion && gameNative) add(Triple(T("Runtime"), showRuntime) { showRuntime = !showRuntime; applyHud() })
        if (show("0.01% low", false)) add(Triple(T("0.01% low"), showLow001) { showLow001 = !showLow001; applyHud() })
        if (show("FPS .1", false)) add(Triple(T("FPS .1"), fpsDecimal) { fpsDecimal = !fpsDecimal; applyHud() })
        if (show("Per-core", false)) add(Triple(T("Per-core"), showPerCore) { showPerCore = !showPerCore; applyHud() })
        if (show("Swap", false)) add(Triple(T("Swap"), showSwap) { showSwap = !showSwap; applyHud() })
        if (show("Network", false)) add(Triple(T("Network"), showNet) { showNet = !showNet; applyHud() })
        if (show("Resolution", false)) add(Triple(T("Resolution"), showResolution) { showResolution = !showResolution; applyHud() })
        if (show("Proton", false)) add(Triple(T("Proton"), showProton) { showProton = !showProton; applyHud() })
        if (show("Wrapper", false)) add(Triple(T("Wrapper"), showWrapper) { showWrapper = !showWrapper; applyHud() })
        if (show("DX ver", false)) add(Triple(T("DX ver"), showDxVer) { showDxVer = !showDxVer; applyHud() })
        if (show("Session", false)) add(Triple(T("Session"), showSession) { showSession = !showSession; applyHud() })
        if (show("Clock", gameNative)) add(Triple(T("Clock"), showClock) { showClock = !showClock; applyHud() })
        if (show("Engine", true)) add(Triple(T("Engine"), showEngine) { showEngine = !showEngine; applyHud() })
        if (show("GPU model", rich)) add(Triple(T("GPU model"), showGpuModel) { showGpuModel = !showGpuModel; applyHud() })
        if (!fusion && gameHub) add(Triple(T("Dual battery"), dualBattery) { dualBattery = !dualBattery; applyHud() })
        add(Triple(T("Lock in place"), hudLocked) { hudLocked = !hudLocked; applyHud() })
    }
    IgSectionLabel(L("Metrics"))
    IgCard {
        // Stacked even on wide windows: the chip grid needs the full card width.
        CompositionLocalProvider(LocalIgCompact provides true) {
            IgRow(L("On the overlay"), hint = L("%1\$s on", metricChips.count { it.second }), scopes = scopes) {
                LabPerfToggleChips(metricChips)
            }
        }
    }

    // ── Appearance ──
    IgSectionLabel(L("Appearance"))
    IgCard {
        IgSliderRow(
            title = L("Scale"), value = scaleValue, range = 50f..150f, step = 5f,
            format = { "${it.toInt()}%" }, onChange = { scaleValue = it }, onFinished = { applyHud() }, scopes = scopes,
        )
        IgDivider()
        if (rich) {
            IgSliderRow(
                title = L("Opacity"), value = opacityValue, range = 0f..100f, step = 5f,
                format = { "${it.toInt()}%" }, onChange = { opacityValue = it }, onFinished = { applyHud() }, scopes = scopes,
            )
        } else {
            IgSliderRow(
                title = L("Transparency"), value = transValue, range = 0f..50f, step = 2.5f,
                format = { "${it.toInt()}" }, onChange = { transValue = it }, onFinished = { applyHud() }, scopes = scopes,
            )
        }
        if (gameHub) {
            IgDivider()
            LabPerfHudChoiceRow(L("Skin"), listOf(L("Classic"), L("Neon"), L("Mono")), skins.indexOf(skin), scopes) { skin = skins[it]; applyHud() }
        }
        if (gameHub || gameNative || fusion) {
            IgDivider()
            LabPerfHudChoiceRow(L("Colour"), listOf(L("Soft"), L("Mid"), L("Vivid")), colors.indexOf(color), scopes) { color = colors[it]; applyHud() }
            IgDivider()
            IgSliderRow(
                title = L("Outline"), value = outlineValue, range = 0f..100f, step = 5f,
                format = { "${it.toInt()}" }, onChange = { outlineValue = it }, onFinished = { applyHud() }, scopes = scopes,
            )
            IgDivider()
            LabPerfHudChoiceRow(L("Outline colour"), listOf(L("Gray"), L("Accent")), if (outlineAccent) 1 else 0, scopes) { outlineAccent = it == 1; applyHud() }
        }
    }

    // ── Alerts & temperature ── only while a temperature is on screen.
    if (showTemp || ((gameNative || fusion) && (showGpuTemp || showBattery))) {
        IgSectionLabel(L("Alerts & temperature"))
        IgCard {
            LabPerfHudChoiceRow(L("Temperature unit"), listOf("°C", "°F"), if (tempUnitF) 1 else 0, scopes) { tempUnitF = it == 1; applyHud() }
            IgDivider()
            val bandMode = if (!tempBands) 0 else if (tempAuto) 1 else 2
            LabPerfHudChoiceRow(
                L("Danger colours"),
                listOf(L("Off"), L("Auto"), L("Manual")),
                bandMode,
                scopes,
                hint = when (bandMode) {
                    0 -> L("Temperatures use their normal color.")
                    1 -> L("Thresholds read from your device's own thermal trip points, falling back to safe defaults.")
                    else -> L("Set the red point per sensor; amber sits just below it.")
                },
            ) {
                tempBands = it != 0
                tempAuto = it != 2
                applyHud()
            }
            if (bandMode == 2) {
                IgDivider()
                IgSliderRow(
                    title = L("CPU red at"), value = tempRedCpu, range = 50f..110f, step = 3f,
                    format = { "${it.toInt()}°C" }, onChange = { tempRedCpu = it }, onFinished = { applyHud() }, scopes = scopes,
                )
                if ((gameNative || fusion) && showGpuTemp) {
                    IgDivider()
                    IgSliderRow(
                        title = L("GPU red at"), value = tempRedGpu, range = 50f..110f, step = 3f,
                        format = { "${it.toInt()}°C" }, onChange = { tempRedGpu = it }, onFinished = { applyHud() }, scopes = scopes,
                    )
                }
                IgDivider()
                IgSliderRow(
                    title = L("Battery red at"), value = tempRedBat, range = 35f..60f, step = 1.25f,
                    format = { "${it.toInt()}°C" }, onChange = { tempRedBat = it }, onFinished = { applyHud() }, scopes = scopes,
                )
            }
        }
    }

    // ── Tools ──
    IgSectionLabel(L("Tools"))
    IgCard {
        val diagContext = LocalContext.current
        IgRow(
            L("Export HUD diagnostics"),
            hint = L("Saves a sensor report (CPU/GPU/temp/VRAM…) straight to your Downloads folder."),
        ) { IgButton(L("Export"), Icons.Outlined.Description, { exportHudDiagnostics(diagContext) }) }
    }
}

/** A one-of-a-few choice row (Classic HudChipRow): [selected] is an index into [labels]; -1 lights none, as in Classic. */
@Composable
private fun LabPerfHudChoiceRow(
    title: String,
    labels: List<String>,
    selected: Int,
    scopes: List<SaveScope>,
    hint: String? = null,
    onSelect: (Int) -> Unit,
) {
    IgRow(title, hint = hint, scopes = scopes, wideControl = true) {
        IgChips(labels.mapIndexed { idx, l -> idx to l }, selected) { onSelect(it) }
    }
}

/** Independent on/off chips (Classic ModeChipGrid of metric toggles), drawn like IgChips; an on chip has the accent outline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabPerfToggleChips(items: List<Triple<String, Boolean, () -> Unit>>) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (label, on, onTap) ->
            val shape = deckShape(4.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .deckFocusRing(shape)
                    .clip(shape)
                    .background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHigh.copy(alpha = igRaised(1f)))
                    .border(if (on) 2.dp else 1.dp, if (on) cs.primary else deckLineAt(0.4f), shape)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { onTap() })
                    .heightIn(min = 34.dp)
                    .widthIn(min = 44.dp)
                    .padding(horizontal = 11.dp),
            ) {
                Text(
                    L(label),
                    color = if (on) cs.primary else cs.onSurface,
                    fontFamily = NunitoSansFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

// ───────────────────────────── TV ─────────────────────────────

/**
 * Lab replacement for Classic TvContent: cast, the wired display (while one is connected), reset audio, picture,
 * latency, frame generation, audio & power, advanced and the not-yet-available streaming block.
 */
@Composable
internal fun LabTvPanel(state: XServerDrawerState) {
    val tvConnected by state.tvConnected.collectAsState()
    val displayName by state.tvDisplayName.collectAsState()
    val playOnTv by state.tvPlayOnTv.collectAsState()
    val autoSwap by state.tvAutoSwap.collectAsState()
    val onExternal by state.tvGameOnExternal.collectAsState()
    val modes by state.tvModes.collectAsState()
    val currentModeId by state.tvCurrentModeId.collectAsState()
    val hdr by state.tvHdr.collectAsState()
    val rendererIsVulkan by state.rendererIsVulkan.collectAsState()
    val game = igGameScope()

    // ── Wireless cast ──
    IgSectionLabel(L("Cast to a TV (wireless)"))
    IgCard {
        IgNote(
            L("⚠ EXPERIMENTAL · VIDEO ONLY — wireless streaming is new: the picture runs a few seconds behind, there's no TV sound yet (game audio stays on this device), and it may take a moment to start or need a second try."),
            error = true,
            modifier = Modifier.padding(top = 8.dp),
        )
        IgRow(
            L("Cast to a TV"),
            hint = L("Stream the game's picture to a Google TV / Chromecast on your Wi-Fi — pick one in the app, nothing to install on the TV. Tap the “?” inside for how it works and the trade-offs."),
        ) { IgButton(L("Cast"), Icons.Outlined.Cast, { state.onOpenCastPicker?.run() }) }
        IgNote(L("For the lowest lag, a wired USB-C→HDMI cable is still best."), modifier = Modifier.padding(bottom = 8.dp))
    }

    // ── Wired / external display, only while one is connected ──
    if (tvConnected) {
        IgSectionLabel(L("TV / external display"))
        IgCard {
            IgRow(
                if (displayName.isNotBlank()) L("Connected: %1\$s", displayName) else L("External display connected"),
                hint = if (hdr.isNotBlank()) L("HDR supported by display: %1\$s", hdr) else null,
            )
            if (modes.isNotEmpty()) {
                val labels = modes.map { it.label }
                val selectedIdx = modes.indexOfFirst { it.id == currentModeId }.let { if (it >= 0) it else 0 }
                IgDivider()
                IgPickRow(
                    L("Display mode"),
                    labels,
                    selectedIdx,
                    hint = L("Resolution & refresh"),
                    scopes = listOf(SaveScope.SESSION),
                ) { i ->
                    val id = modes[i].id
                    state.setTvCurrentModeId(id)
                    state.onTvModeChange?.accept(id)
                }
            }
            IgDivider()
            IgToggleRow(L("Play on TV"), playOnTv, scopes = listOf(SaveScope.CONTAINER)) {
                state.setTvPlayOnTv(it)
                state.onTvPlayOnTvChange?.accept(it)
            }
            IgDivider()
            IgToggleRow(L("Auto-switch on connect"), autoSwap, scopes = listOf(SaveScope.CONTAINER), enabled = playOnTv) {
                state.setTvAutoSwap(it)
                state.onTvAutoSwapChange?.accept(it)
            }
            IgDivider()
            if (onExternal) {
                IgRow(L("Bring game back to handheld")) { IgButton(L("Bring back"), null, { state.onBringBackFromTv?.run() }) }
            } else {
                IgRow(L("Move game to TV")) { IgButton(L("Move"), Icons.Outlined.Tv, { state.onMoveToTv?.run() }) }
            }
        }
    }

    // ── Reset audio (sound can drop after backgrounding or an HDMI route change) ──
    Spacer(Modifier.height(10.dp))
    IgCard {
        IgRow(L("Reset audio"), hint = L("Fixes lost sound after switching apps.")) {
            IgButton(L("Reset"), null, { state.onResetAudio?.run() })
        }
    }

    // ── Picture ──
    IgSectionLabel(L("Picture"))
    IgCard {
        val fullscreenMode by state.fullscreenMode.collectAsState()
        IgRow(L("Aspect"), scopes = listOf(game), wideControl = true) {
            IgSegmented(
                listOf(
                    0 to stringResource(R.string.fullscreen_mode_off_short),
                    1 to stringResource(R.string.fullscreen_mode_fit_short),
                    2 to stringResource(R.string.fullscreen_mode_stretch_short),
                    3 to stringResource(R.string.fullscreen_mode_fill_short),
                    4 to stringResource(R.string.fullscreen_mode_integer_short),
                ),
                fullscreenMode,
            ) { mode ->
                state.setFullscreenMode(mode)
                state.onSetFullscreenMode?.accept(mode)
            }
        }
        IgDivider()
        val overscan by state.tvOverscan.collectAsState()
        var overscanVal by remember(overscan) { mutableIntStateOf(overscan) }
        IgSliderRow(
            title = L("Overscan / safe area"),
            value = overscanVal.toFloat(),
            range = 0f..8f,
            step = 1f,
            format = { "${it.roundToInt()}" },
            onChange = { overscanVal = it.roundToInt() },
            onFinished = {
                state.setTvOverscan(overscanVal)
                state.onTvOverscanChange?.accept(overscanVal)
            },
            hint = L("Shrinks the picture inward if your TV cuts off the edges."),
            scopes = listOf(SaveScope.CONTAINER),
        )
        IgDivider()
        if (rendererIsVulkan) {
            IgNote(L("Scaling filter is available on the OpenGL renderer."), modifier = Modifier.padding(vertical = 6.dp))
        } else {
            val initGlUpscalerMode by XServerDialogState.glUpscalerMode.collectAsState()
            var glUpscalerMode by remember(initGlUpscalerMode) { mutableIntStateOf(initGlUpscalerMode) }
            val compact = LocalIgCompact.current
            IgRow(L("Scaling filter"), scopes = listOf(game), wideControl = true) {
                Column(horizontalAlignment = if (compact) Alignment.Start else Alignment.End) {
                    IgChips(
                        listOf(
                            0 to L("None"), 1 to L("Linear"), 2 to L("Nearest"),
                            3 to "SGSR", 8 to "SGSR HQ", 4 to "FSR",
                            5 to "FSR (Fit)", 6 to L("Sharpen"), 7 to "NIS",
                        ),
                        glUpscalerMode,
                    ) {
                        glUpscalerMode = it
                        XServerDialogState.onGlUpscalerApply?.invoke(it)
                    }
                    Spacer(Modifier.height(8.dp))
                    IgHelpButton(L("Scaling filter"), R.string.help_scaling_mode)
                }
            }
        }
    }

    // ── Latency ──
    IgSectionLabel(L("Latency"))
    IgCard {
        if (rendererIsVulkan) {
            LabPerfPresentModeRow(state)
        } else {
            IgNote(L("Latency mode (V-Sync / Low latency) is available on the Vulkan renderer."), modifier = Modifier.padding(vertical = 6.dp))
        }
        IgDivider()
        val fpsLimiterEnabled by state.fpsLimiterEnabled.collectAsState()
        val fpsLimit by state.fpsLimit.collectAsState()
        val capValues = listOf(0, 30, 60, 90, 120)
        val capOptions = capValues.map { if (it == 0) L("Off") else L("%1\$s fps", it) }
        val capIdx = if (!fpsLimiterEnabled) 0 else capValues.indexOf(fpsLimit).let { if (it >= 0) it else 0 }
        IgPickRow(L("Frame cap"), capOptions, capIdx, scopes = listOf(game)) { i ->
            val v = capValues[i]
            state.setFpsLimiterEnabled(v > 0)
            if (v > 0) state.setFpsLimit(v)
            state.onFpsLimitChange?.run()
        }
    }

    // ── Frame generation ──
    IgSectionLabel(L("Frame generation"))
    LabFrameGenPanel(state)
    IgInfo(L("Tip: enable Game Mode on your TV for the lowest input lag."))

    // ── Audio & power ──
    IgSectionLabel(L("Audio & power"))
    IgCard {
        val audioOut by state.tvAudioOut.collectAsState()
        IgPickRow(
            L("Audio output"),
            listOf(L("Follow system"), L("TV / HDMI"), L("Handheld")),
            audioOut,
            hint = L("Experimental — the guest audio route may not always follow."),
            scopes = listOf(SaveScope.CONTAINER),
        ) { i ->
            state.setTvAudioOut(i)
            state.onTvAudioOutChange?.accept(i)
        }
        IgDivider()
        val dimHandheld by state.tvDimHandheld.collectAsState()
        IgToggleRow(
            L("Dim handheld while on TV"),
            dimHandheld,
            hint = L("Saves battery and heat by dimming the phone screen."),
            scopes = listOf(SaveScope.CONTAINER),
        ) {
            state.setTvDimHandheld(it)
            state.onTvDimHandheldChange?.accept(it)
        }
    }

    // ── Advanced ──
    IgSectionLabel(L("Advanced"))
    IgCard {
        val renderRes by state.tvRenderRes.collectAsState()
        IgPickRow(
            L("TV render resolution"),
            listOf(L("Match TV"), L("Match handheld"), "1080p", "1440p"),
            renderRes,
            hint = L("Applies on the next game launch (the render resolution is fixed at startup)."),
            scopes = listOf(SaveScope.CONTAINER),
        ) { i ->
            state.setTvRenderRes(i)
            state.onTvRenderResChange?.accept(i)
        }
    }

    // ── Streaming (not available yet) ──
    IgSectionLabel(L("Streaming"))
    IgCard {
        IgRow(
            L("Wireless streaming"),
            hint = L("Wireless streaming to a TV without a cable (bitrate, codec, transport) is coming in a future update. Wired HDMI / DeX casting works today via the controls above."),
            enabled = false,
        )
        listOf(T("Bitrate"), T("Codec (H.264 / HEVC)"), T("Transport (WebRTC / DLNA)"), T("Stream resolution & FPS")).forEach {
            IgDivider()
            IgRow(L(it), hint = L("Requires WiFi streaming"), enabled = false)
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Classic PresentModeSection (Vulkan only): while frame generation forces Mailbox the chips stay live, a FIFO /
 * Immediate pick is refused without touching the saved mode and the "Mailbox is required" note shows for 2 s.
 */
@Composable
private fun LabPerfPresentModeRow(state: XServerDrawerState) {
    val rendererIsVulkan by state.rendererIsVulkan.collectAsState()
    if (!rendererIsVulkan) return
    val presentMode by state.presentMode.collectAsState()
    val locked by state.presentModeLocked.collectAsState()
    val modes = listOf("fifo", "mailbox", "immediate")
    val labels = listOf(
        stringResource(R.string.renderer_present_mode_fifo),
        stringResource(R.string.renderer_present_mode_mailbox),
        stringResource(R.string.renderer_present_mode_immediate),
    )
    val selectedIdx = modes.indexOf(presentMode).coerceAtLeast(0)
    var blockedFlash by remember { mutableIntStateOf(0) }
    var showBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(blockedFlash) {
        if (blockedFlash > 0) {
            showBlocked = true
            delay(2000)
            showBlocked = false
        }
    }
    IgRow(
        stringResource(R.string.renderer_present_mode),
        hint = L("How finished frames reach the screen: V-Sync (FIFO) never tears, Smooth (Mailbox) adds less delay, Fastest (Immediate) is lowest latency but may tear."),
        note = if (showBlocked) stringResource(R.string.present_mode_fg_locked) else null,
        scopes = listOf(igGameScope()),
        wideControl = true,
    ) {
        IgSegmented(labels.mapIndexed { idx, l -> idx to l }, selectedIdx) { idx ->
            val mode = modes[idx]
            if (locked) {
                if (mode != "mailbox") blockedFlash++
            } else {
                state.onPresentModeChange?.accept(mode)
            }
        }
    }
}
