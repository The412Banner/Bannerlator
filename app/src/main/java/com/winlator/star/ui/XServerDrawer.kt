package com.winlator.star.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.ui.theme.GlowPurple
import com.winlator.star.ui.theme.Primary
import com.winlator.star.ui.theme.PrimaryDim
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.delay

private val PureBlack = Color(0xFF000000)
private val DarkSurface = Color(0xFF0D0D0D)
private val DimWhite = Color(0xFFE8E8E8)
private val MutedWhite = Color(0xFF999999)
private val ToggleTrackOff = Color(0xFF333333)
private val ToggleThumbOff = Color(0xFF666666)

fun setupComposeView(view: ComposeView) {
    view.setContent {
        WinlatorTheme {
            XServerDrawer()
        }
    }
}

@Composable
fun XServerDrawer() {
    val state = XServerDrawerState
    val selectedTab by state.selectedTab.collectAsState()
    val isPaused by state.isPaused.collectAsState()
    val pauseIcon = if (isPaused) R.drawable.icon_play else R.drawable.icon_pause

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(PureBlack)
    ) {
        Column(
            modifier = Modifier
                .width(60.dp)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(PureBlack, DarkSurface, PureBlack),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            TabIconButton(R.drawable.icon_settings, selectedTab == TabType.GRAPHICS) {
                handleTabClick(TabType.GRAPHICS, state)
            }
            Spacer(Modifier.height(6.dp))
            FpsTabButton(isSelected = selectedTab == TabType.HUD) {
                handleTabClick(TabType.HUD, state)
            }
            Spacer(Modifier.height(6.dp))
            TabIconButton(R.drawable.icon_input_controls, selectedTab == TabType.CONTROLS) {
                handleTabClick(TabType.CONTROLS, state)
            }
            Spacer(Modifier.height(6.dp))
            TabIconButton(R.drawable.icon_debug, selectedTab == TabType.ADVANCED) {
                handleTabClick(TabType.ADVANCED, state)
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(2.dp)
                    .background(GlowPurple, RoundedCornerShape(1.dp))
            )

            Spacer(Modifier.height(10.dp))

            TabIconButton(R.drawable.icon_task_manager, selectedTab == TabType.TASK_MANAGER) {
                state.selectTab(TabType.TASK_MANAGER)
                state.onTaskManager?.run()
            }
            Spacer(Modifier.height(6.dp))
            TabIconButton(pauseIcon, isSelected = false) {
                state.onPauseResume?.run(); state.onClose?.run()
            }
            Spacer(Modifier.height(6.dp))
            TabIconButton(R.drawable.icon_exit, isSelected = false) {
                state.onExit?.run()
            }

            Spacer(Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            when (selectedTab) {
                TabType.GRAPHICS -> GraphicsContent(state)
                TabType.HUD -> HudContent(state)
                TabType.CONTROLS -> ControlsContent(state)
                TabType.ADVANCED -> AdvancedContent(state)
                TabType.TASK_MANAGER -> TmContent()
            }
        }
    }
}

private fun handleTabClick(tab: TabType, state: XServerDrawerState) {
    state.selectTab(tab)
}

// ───── Modern Tab Button ─────

@Composable
private fun TabIconButton(iconRes: Int, isSelected: Boolean, onClick: () -> Unit) {
    val bgBrush = if (isSelected)
        Brush.verticalGradient(listOf(PrimaryDim, Primary.copy(alpha = 0.3f)))
    else
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    val borderColor = if (isSelected) GlowPurple.copy(alpha = 0.6f) else Color(0xFF333333)
    val tintColor = if (isSelected) Color.White else MutedWhite

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(GlowPurple.copy(alpha = 0.25f), Color.Transparent),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun FpsTabButton(isSelected: Boolean, onClick: () -> Unit) {
    val bgBrush = if (isSelected)
        Brush.verticalGradient(listOf(PrimaryDim, Primary.copy(alpha = 0.3f)))
    else
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

    val borderColor = if (isSelected) GlowPurple.copy(alpha = 0.6f) else Color(0xFF333333)
    val textColor = if (isSelected) Color.White else Primary

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(GlowPurple.copy(alpha = 0.25f), Color.Transparent),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        Text(
            text = "FPS",
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ───── Section Header ─────

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = DimWhite,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(GlowPurple, GlowPurple.copy(alpha = 0.1f))),
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

// ───── Modern Toggle Row ─────

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DimWhite,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlowPurple,
                checkedTrackColor = PrimaryDim,
                uncheckedThumbColor = ToggleThumbOff,
                uncheckedTrackColor = ToggleTrackOff,
            )
        )
    }
}

// ───── Modern Slider Row ─────

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
    format: (Float) -> String = { "%.0f".format(it) }
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = DimWhite,
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodySmall,
                color = Primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished ?: {},
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = GlowPurple,
                activeTrackColor = Primary,
                inactiveTrackColor = ToggleTrackOff,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ───── Modern Accent Button ─────

@Composable
private fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryDim,
            contentColor = Color.White
        )
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

// ───── Graphics Tab ─────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphicsContent(state: XServerDrawerState) {
    val lsfgEnabled by state.lsfgEnabled.collectAsState()

    LaunchedEffect(Unit) {
        XServerDialogState.onInitGraphicsTab?.run()
    }

    SectionHeader("Graphics")

    ToggleRow("Fullscreen") {
        state.onToggleFullscreen?.run(); state.onClose?.run()
    }

    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))

    // FSR section
    val initFsrEnabled by XServerDialogState.fsrEnabled.collectAsState()
    val initFsrMode by XServerDialogState.fsrMode.collectAsState()
    val initFsrLevel by XServerDialogState.fsrLevel.collectAsState()
    val initHdrEnabled by XServerDialogState.hdrEnabled.collectAsState()
    var fsrEnabled by remember(initFsrEnabled) { mutableStateOf(initFsrEnabled) }
    var fsrMode by remember(initFsrMode) { mutableIntStateOf(initFsrMode) }
    var fsrLevel by remember(initFsrLevel) { mutableFloatStateOf(initFsrLevel) }
    var hdrEnabled by remember(initHdrEnabled) { mutableStateOf(initHdrEnabled) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }
    val modeNames = listOf("Super Resolution", "DLS (Color Boost)")

    ToggleRow("FSR") { fsrEnabled = it; pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) }

    if (fsrEnabled) {
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(expanded = modeDropdownExpanded, onExpandedChange = { modeDropdownExpanded = it }) {
            OutlinedTextField(
                value = modeNames.getOrElse(fsrMode) { modeNames[0] },
                onValueChange = {},
                readOnly = true,
                label = { Text("Mode", color = MutedWhite) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                singleLine = true,
            )
            ExposedDropdownMenu(expanded = modeDropdownExpanded, onDismissRequest = { modeDropdownExpanded = false }) {
                modeNames.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { fsrMode = i; modeDropdownExpanded = false; pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) })
                }
            }
        }

        LabeledSlider("Strength", fsrLevel, 1f..5f, { fsrLevel = it }, { pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) }, steps = 3)
    }

    ToggleRow("HDR") { hdrEnabled = it; pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) }

    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))

    Text("Screen Effects", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))

    val seBrightness by XServerDialogState.seBrightness.collectAsState()
    val seContrast by XServerDialogState.seContrast.collectAsState()
    val seGamma by XServerDialogState.seGamma.collectAsState()
    val seFxaa by XServerDialogState.seFxaa.collectAsState()
    val seCrt by XServerDialogState.seCrt.collectAsState()
    val seToon by XServerDialogState.seToon.collectAsState()
    val seNtsc by XServerDialogState.seNtsc.collectAsState()
    var localBrightness by remember(seBrightness) { mutableFloatStateOf(seBrightness) }
    var localContrast by remember(seContrast) { mutableFloatStateOf(seContrast) }
    var localGamma by remember(seGamma) { mutableFloatStateOf(seGamma) }
    var localFxaa by remember(seFxaa) { mutableStateOf(seFxaa) }
    var localCrt by remember(seCrt) { mutableStateOf(seCrt) }
    var localToon by remember(seToon) { mutableStateOf(seToon) }
    var localNtsc by remember(seNtsc) { mutableStateOf(seNtsc) }

    fun applySe() {
        XServerDialogState.onScreenEffectsApply?.invoke(localBrightness, localContrast, localGamma, localFxaa, localCrt, localToon, localNtsc, 0)
    }

    LabeledSlider("Brightness", localBrightness, -100f..100f, { localBrightness = it; applySe() })
    LabeledSlider("Contrast", localContrast, -100f..100f, { localContrast = it; applySe() })
    LabeledSlider("Gamma", localGamma, 0.5f..3.0f, { localGamma = it; applySe() }, format = { "%.2f".format(it) })

    SeShaderToggle("FXAA", localFxaa) { localFxaa = it; applySe() }
    SeShaderToggle("CRT", localCrt) { localCrt = it; applySe() }
    SeShaderToggle("Toon", localToon) { localToon = it; applySe() }
    SeShaderToggle("NTSC", localNtsc) { localNtsc = it; applySe() }

    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))

    ToggleRow("Vegas FrameGen") { state.onLsfgToggle?.run() }

    if (lsfgEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LsfgInlineDropdown("Multiplier", listOf("2x", "3x", "4x", "5x", "6x", "7x", "8x", "9x", "10x"), "${state.getLsfgMultiplier()}x") { opt ->
                val num = opt.removeSuffix("x").toIntOrNull() ?: 2
                state.setLsfgMultiplier(num); state.onApplyLsfg?.run()
            }
            LsfgInlineDropdown("Quality", listOf("performance", "balanced", "quality"), state.getLsfgQuality()) { opt ->
                state.setLsfgQuality(opt); state.onApplyLsfg?.run()
            }
            LabeledSlider("Flow Scale", state.getLsfgFlowScale().toFloat(), 50f..200f, { state.setLsfgFlowScale(it.toInt()) }, { state.onApplyLsfg?.run() }, steps = 14, format = { "${it.toInt()}%" })
            LabeledSlider("Max Input Latency", state.getLsfgMaxLatency().toFloat(), 0f..33f, { state.setLsfgMaxLatency(it.toInt()) }, { state.onApplyLsfg?.run() }, steps = 32, format = { "${it.toInt()}ms" })

            AccentButton("Reset to GPU Defaults") { state.onResetLsfg?.run() }
        }
    }
}

private fun pushFsrUpdate(enabled: Boolean, mode: Int, level: Float, hdr: Boolean) {
    XServerDialogState.onFsrUpdate?.invoke(enabled, mode, level, hdr)
}

@Composable
private fun SeShaderToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                uncheckedColor = ToggleThumbOff,
                checkmarkColor = Color.White
            )
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = DimWhite, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LsfgInlineDropdown(label: String, options: List<String>, selectedOption: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedOption, onValueChange = {}, readOnly = true,
            label = { Text(label, color = MutedWhite) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false }) }
        }
    }
}

// ───── HUD Tab ─────

@Composable
private fun HudContent(state: XServerDrawerState) {
    val fpsConfig by state.fpsConfig.collectAsState()

    SectionHeader("HUD")

    fun parseConfig(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        s.split(",").forEach { part ->
            val eq = part.indexOf('=')
            if (eq >= 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return map
    }

    val cfg = remember(fpsConfig) { parseConfig(fpsConfig) }
    val hudMode = remember { cfg.getOrDefault("hudMode", "horizontal") }

    var showFPS by remember { mutableStateOf(cfg.getOrDefault("showFPS", "1") == "1") }
    var showCPULoad by remember { mutableStateOf(cfg.getOrDefault("showCPULoad", "0") == "1") }
    var showGPULoad by remember { mutableStateOf(cfg.getOrDefault("showGPULoad", "0") == "1") }
    var showRAM by remember { mutableStateOf(cfg.getOrDefault("showRAM", "0") == "1") }
    var showRenderer by remember { mutableStateOf(cfg.getOrDefault("showRenderer", "0") == "1") }
    var showBatteryTemp by remember { mutableStateOf(cfg.getOrDefault("showBatteryTemp", "0") == "1") }

    val initScale = cfg.getOrDefault("hudScale", "100").toFloatOrNull() ?: 100f
    val initTrans = cfg.getOrDefault("hudTransparency", "0").toFloatOrNull() ?: 0f
    var scaleValue by remember { mutableFloatStateOf(initScale) }
    var transValue by remember { mutableFloatStateOf(initTrans) }

    fun buildConfig(): String = listOf(
        "hudMode=$hudMode",
        "showFPS=${if (showFPS) "1" else "0"}",
        "showCPULoad=${if (showCPULoad) "1" else "0"}",
        "showGPULoad=${if (showGPULoad) "1" else "0"}",
        "showRAM=${if (showRAM) "1" else "0"}",
        "showRenderer=${if (showRenderer) "1" else "0"}",
        "showBatteryTemp=${if (showBatteryTemp) "1" else "0"}",
        "hudScale=${scaleValue.toInt()}",
        "hudTransparency=${transValue.toInt()}",
    ).joinToString(",")

    // Size and Opacity sliders
    LabeledSlider("HUD Scale", scaleValue, 50f..200f, { scaleValue = it }, { state.onFpsConfigApply?.invoke(buildConfig()) }, format = { "${it.toInt()}%" })
    LabeledSlider("HUD Opacity", transValue, 0f..100f, { transValue = it }, { state.onFpsConfigApply?.invoke(buildConfig()) }, format = { "${it.toInt()}%" })

    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))

    ToggleRow("Show FPS", showFPS) { showFPS = !showFPS; state.onFpsConfigApply?.invoke(buildConfig()) }
    ToggleRow("Show CPU Load", showCPULoad) { showCPULoad = !showCPULoad; state.onFpsConfigApply?.invoke(buildConfig()) }
    ToggleRow("Show GPU Load", showGPULoad) { showGPULoad = !showGPULoad; state.onFpsConfigApply?.invoke(buildConfig()) }
    ToggleRow("Show RAM", showRAM) { showRAM = !showRAM; state.onFpsConfigApply?.invoke(buildConfig()) }
    ToggleRow("Show Renderer", showRenderer) { showRenderer = !showRenderer; state.onFpsConfigApply?.invoke(buildConfig()) }
    ToggleRow("Show Battery Temp", showBatteryTemp) { showBatteryTemp = !showBatteryTemp; state.onFpsConfigApply?.invoke(buildConfig()) }
}

// ───── Controls Tab ─────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsContent(state: XServerDrawerState) {
    val profiles by XServerDialogState.inputProfiles.collectAsState()
    val initProfileIdx by XServerDialogState.selectedProfileIdx.collectAsState()
    val initTouchscreen by XServerDialogState.showTouchscreen.collectAsState()
    val initTimeout by XServerDialogState.timeoutEnabled.collectAsState()
    val initHaptics by XServerDialogState.hapticsEnabled.collectAsState()

    val moveCursorToTouch by state.moveCursorToTouchpoint.collectAsState()
    val isRelativeMouse by state.isRelativeMouseMovement.collectAsState()
    val isMouseDisabled by state.isMouseDisabled.collectAsState()

    SectionHeader("Controls")

    // Input Controls section
    var selectedIdx by remember(initProfileIdx) { mutableIntStateOf(initProfileIdx) }
    var showTouchscreen by remember(initTouchscreen) { mutableStateOf(initTouchscreen) }
    var timeoutEnabled by remember(initTimeout) { mutableStateOf(initTimeout) }
    var hapticsEnabled by remember(initHaptics) { mutableStateOf(initHaptics) }
    val allItems = listOf("-- Disabled --") + profiles
    var dropdownExpanded by remember { mutableStateOf(false) }

    Text("Input Controls", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))

    ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = it }) {
        OutlinedTextField(
            value = allItems.getOrElse(selectedIdx) { "-- Disabled --" },
            onValueChange = {}, readOnly = true,
            label = { Text("Profile", color = MutedWhite) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
            allItems.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    selectedIdx = i
                    dropdownExpanded = false
                    XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
                })
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    ToggleRow("Show Touchscreen Controls", showTouchscreen) {
        showTouchscreen = it
        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
    }
    ToggleRow("Enable Timeout", timeoutEnabled) {
        timeoutEnabled = it
        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
    }
    ToggleRow("Enable Haptics", hapticsEnabled) {
        hapticsEnabled = it
        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
            XServerDialogState.onInputControlsSettings?.run()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) { Text("Profile Settings\u2026") }

    Spacer(Modifier.height(4.dp))

    AccentButton("Apply & Close") {
        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
        state.onClose?.run()
    }

    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))

    // Mouse & Cursor section
    Text("Mouse & Cursor", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))

    ToggleRow("Move Cursor to Touchpoint", moveCursorToTouch) {
        state.onMoveCursorToTouchpoint?.run(); state.onClose?.run()
    }
    ToggleRow("Relative Mouse Movement", isRelativeMouse) {
        state.onRelativeMouseMovement?.run(); state.onClose?.run()
    }
    ToggleRow("Disable Mouse", isMouseDisabled) {
        state.onDisableMouse?.run(); state.onClose?.run()
    }

    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))

    // Vibration section
    Text("Vibration", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))

    val vibrationSlots by XServerDialogState.vibrationSlots.collectAsState()
    vibrationSlots.forEachIndexed { index, slot ->
        ToggleRow(slot.first, slot.second) { XServerDialogState.onVibrationSlotChanged?.invoke(index, it) }
    }
}

// ───── Advanced Tab ─────

@Composable
private fun AdvancedContent(state: XServerDrawerState) {
    SectionHeader("Advanced")

    AdvancedActionRow("Magnifier", R.drawable.icon_magnifier) {
        state.onClose?.run(); state.onMagnifier?.run()
    }
    AdvancedActionRow("Active Windows", R.drawable.icon_active_windows) {
        state.onClose?.run(); state.onActiveWindows?.run()
    }
    AdvancedActionRow("Debug Logs", R.drawable.icon_debug) {
        state.onClose?.run(); state.onLogs?.run()
    }
    AdvancedActionRow("Picture-in-Picture", R.drawable.ic_picture_in_picture_alt) {
        state.onClose?.run(); state.onPipMode?.run()
    }
    AdvancedActionRow("Show Keyboard", R.drawable.icon_keyboard) {
        state.onClose?.run(); state.onKeyboard?.run()
    }
}

@Composable
private fun AdvancedActionRow(label: String, iconRes: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = DimWhite, modifier = Modifier.weight(1f))
    }
}

// ───── Task Manager Tab ─────

@Composable
private fun TmContent() {
    val processes by XServerDialogState.tmProcesses.collectAsState()
    val cpuCores by XServerDialogState.tmCpuCores.collectAsState()
    val cpuTitle by XServerDialogState.tmCpuTitle.collectAsState()
    val memTitle by XServerDialogState.tmMemTitle.collectAsState()
    val memInfo by XServerDialogState.tmMemInfo.collectAsState()
    val count by XServerDialogState.tmCount.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            XServerDialogState.onTmRefresh?.run()
            delay(1000L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { XServerDialogState.onTmDismissed?.run() }
    }

    SectionHeader("Task Manager")

    Text(
        text = "Processes: $count",
        color = MutedWhite,
        fontSize = 12.sp,
    )

    Spacer(Modifier.height(6.dp))

    if (processes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No processes", color = MutedWhite, fontSize = 13.sp)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
                .padding(4.dp)
        ) {
            processes.forEach { proc ->
                TmProcessRow(proc)
                if (proc != processes.last()) {
                    HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // CPU info
    Text(cpuTitle, color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(2.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .padding(10.dp)
    ) {
        cpuCores.forEach { core ->
            Text(core, color = MutedWhite, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
        }
    }

    Spacer(Modifier.height(8.dp))

    // Memory info
    Text(memTitle, color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.height(2.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .padding(10.dp)
    ) {
        Text(memInfo, color = MutedWhite, fontSize = 11.sp)
    }

    Spacer(Modifier.height(10.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = {
                XServerDialogState.onTmDismissed?.run()
                XServerDialogState.onTmNewTask?.run()
            }
        ) { Text("New Task\u2026", color = Primary) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { XServerDialogState.onTmDismissed?.run() }) { Text("Clear", color = MutedWhite) }
    }
}

@Composable
private fun TmProcessRow(proc: XServerDialogState.TmProcess) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (proc.icon != null) {
            Image(
                bitmap = proc.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.taskmgr_process),
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = proc.name + if (proc.wow64) " *32" else "",
                color = DimWhite,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "PID ${proc.pid}  \u2022  ${proc.formattedMemory}",
                color = MutedWhite,
                fontSize = 10.sp,
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = MutedWhite)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Bring to Front") },
                    onClick = { menuExpanded = false; XServerDialogState.onTmBringToFront?.invoke(proc.name) },
                )
                DropdownMenuItem(
                    text = { Text("End Process") },
                    onClick = { menuExpanded = false; XServerDialogState.onTmKillProcess?.invoke(proc.name) },
                )
            }
        }
    }
}
