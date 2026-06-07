package com.winlator.star.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.ui.theme.Primary
import com.winlator.star.ui.theme.PrimaryVariant
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.delay

private data class TabData(
    val type: TabType,
    val iconRes: Int,
    val pauseIconRes: Int? = null,
)

private val allTabs = listOf(
    TabData(TabType.GRAPHICS, R.drawable.icon_settings),
    TabData(TabType.HUD, R.drawable.icon_debug),
    TabData(TabType.CONTROLS, R.drawable.icon_input_controls),
    TabData(TabType.TASK_MANAGER, R.drawable.icon_task_manager),
    TabData(TabType.ACTIVE_WINDOWS, R.drawable.icon_active_windows),
    TabData(TabType.KEYBOARD, R.drawable.icon_keyboard),
    TabData(TabType.MAGNIFIER, R.drawable.icon_magnifier),
    TabData(TabType.LOGS, R.drawable.icon_debug),
    TabData(TabType.PIP, R.drawable.ic_picture_in_picture_alt),
    TabData(TabType.PAUSE_RESUME, R.drawable.icon_pause, pauseIconRes = R.drawable.icon_play),
    TabData(TabType.EXIT, R.drawable.icon_exit),
)

private data class ContentTab(val type: TabType)
private val contentTabs = setOf(TabType.GRAPHICS, TabType.HUD, TabType.CONTROLS, TabType.TASK_MANAGER)

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

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .width(60.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            allTabs.forEach { tab ->
                val iconRes = if (tab.type == TabType.PAUSE_RESUME && isPaused) {
                    tab.pauseIconRes ?: tab.iconRes
                } else tab.iconRes

                TabIconButton(
                    iconRes = iconRes,
                    isSelected = selectedTab == tab.type,
                    onClick = { handleTabClick(tab.type, state) },
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            when (selectedTab) {
                TabType.GRAPHICS -> GraphicsContent(state)
                TabType.HUD -> HudContent(state)
                TabType.CONTROLS -> ControlsContent(state)
                TabType.TASK_MANAGER -> TaskManagerContent(state)
                else -> Unit
            }
        }
    }
}

private fun handleTabClick(tab: TabType, state: XServerDrawerState) {
    when (tab) {
        in contentTabs -> state.selectTab(tab)
        TabType.ACTIVE_WINDOWS -> {
            state.onClose?.run()
            state.onActiveWindows?.run()
        }
        TabType.KEYBOARD -> {
            state.onClose?.run()
            state.onKeyboard?.run()
        }
        TabType.MAGNIFIER -> {
            state.onClose?.run()
            state.onMagnifier?.run()
        }
        TabType.LOGS -> {
            state.onClose?.run()
            state.onLogs?.run()
        }
        TabType.PIP -> {
            state.onClose?.run()
            state.onPipMode?.run()
        }
        TabType.PAUSE_RESUME -> {
            state.onClose?.run()
            state.onPauseResume?.run()
        }
        TabType.EXIT -> {
            state.onExit?.run()
        }
    }
}

@Composable
private fun TabIconButton(iconRes: Int, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) Primary else Color.Transparent
    val borderColor = if (isSelected) Color.Transparent else Primary
    val tintColor = if (isSelected) Color.White else Primary

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ───── Graphics Tab ─────

@Composable
private fun GraphicsContent(state: XServerDrawerState) {
    val lsfgEnabled by state.lsfgEnabled.collectAsState()

    LaunchedEffect(Unit) {
        XServerDialogState.onInitGraphicsTab?.run()
    }

    Text("Graphics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))

    // Fullscreen toggle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { state.onToggleFullscreen?.run(); state.onClose?.run() }
    ) {
        Icon(
            painter = painterResource(R.drawable.icon_fullscreen),
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Toggle Fullscreen", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // FSR section (from FSROverlay)
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

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("FSR", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = fsrEnabled, onCheckedChange = { fsrEnabled = it; pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) })
    }

    ExposedDropdownMenuBox(expanded = modeDropdownExpanded, onExpandedChange = { if (fsrEnabled) modeDropdownExpanded = it }) {
        OutlinedTextField(
            value = modeNames.getOrElse(fsrMode) { modeNames[0] },
            onValueChange = {},
            readOnly = true,
            enabled = fsrEnabled,
            label = { Text("Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = modeDropdownExpanded, onDismissRequest = { modeDropdownExpanded = false }) {
            modeNames.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { fsrMode = i; modeDropdownExpanded = false; pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) })
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Text("Strength: ${"%.0f".format(fsrLevel)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    Slider(value = fsrLevel, onValueChange = { fsrLevel = it }, onValueChangeFinished = { pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) }, valueRange = 1f..5f, steps = 3, enabled = fsrEnabled, modifier = Modifier.fillMaxWidth())

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("HDR", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = hdrEnabled, onCheckedChange = { hdrEnabled = it; pushFsrUpdate(fsrEnabled, fsrMode, fsrLevel, hdrEnabled) })
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Screen Effects section (from ScreenEffectsDialog)
    Text("Screen Effects", style = MaterialTheme.typography.labelMedium, color = Primary)
    Spacer(Modifier.height(4.dp))

    val seBrightness by XServerDialogState.seBrightness.collectAsState()
    val seContrast by XServerDialogState.seContrast.collectAsState()
    val seGamma by XServerDialogState.seGamma.collectAsState()
    val seFxaa by XServerDialogState.seFxaa.collectAsState()
    val seCrt by XServerDialogState.seCrt.collectAsState()
    val seToon by XServerDialogState.seToon.collectAsState()
    val seNtsc by XServerDialogState.seNtsc.collectAsState()
    val seProfiles by XServerDialogState.seProfiles.collectAsState()
    val seSelectedProfile by XServerDialogState.seSelectedProfile.collectAsState()

    var localBrightness by remember(seBrightness) { mutableFloatStateOf(seBrightness) }
    var localContrast by remember(seContrast) { mutableFloatStateOf(seContrast) }
    var localGamma by remember(seGamma) { mutableFloatStateOf(seGamma) }
    var localFxaa by remember(seFxaa) { mutableStateOf(seFxaa) }
    var localCrt by remember(seCrt) { mutableStateOf(seCrt) }
    var localToon by remember(seToon) { mutableStateOf(seToon) }
    var localNtsc by remember(seNtsc) { mutableStateOf(seNtsc) }
    var localSelectedProfile by remember(seSelectedProfile) { mutableIntStateOf(seSelectedProfile) }

    fun applySe() {
        XServerDialogState.onScreenEffectsApply?.invoke(localBrightness, localContrast, localGamma, localFxaa, localCrt, localToon, localNtsc, localSelectedProfile)
    }

    SeSlider("Brightness", localBrightness, -100f..100f) { localBrightness = it; applySe() }
    SeSlider("Contrast", localContrast, -100f..100f) { localContrast = it; applySe() }
    SeSlider("Gamma", localGamma, 0.5f..3.0f) { localGamma = it; applySe() }
    Spacer(Modifier.height(4.dp))

    SeShaderToggle("FXAA", localFxaa) { localFxaa = it; applySe() }
    SeShaderToggle("CRT", localCrt) { localCrt = it; applySe() }
    SeShaderToggle("Toon", localToon) { localToon = it; applySe() }
    SeShaderToggle("NTSC", localNtsc) { localNtsc = it; applySe() }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Vegas FrameGen (from drawer)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { state.onLsfgToggle?.run() }) {
        Text("Vegas FrameGen", style = MaterialTheme.typography.bodyMedium, color = if (lsfgEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = lsfgEnabled, onCheckedChange = { state.onLsfgToggle?.run() })
    }

    if (lsfgEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LsfgInlineDropdown("Multiplier", listOf("2x", "3x", "4x", "5x", "6x", "7x", "8x", "9x", "10x"), "${state.getLsfgMultiplier()}x") { opt ->
                val num = opt.removeSuffix("x").toIntOrNull() ?: 2
                state.setLsfgMultiplier(num); state.onApplyLsfg?.run()
            }
            LsfgInlineDropdown("Quality", listOf("performance", "balanced", "quality"), state.getLsfgQuality()) { opt ->
                state.setLsfgQuality(opt); state.onApplyLsfg?.run()
            }
            Text("Flow Scale: ${state.getLsfgFlowScale()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = state.getLsfgFlowScale().toFloat(), onValueChange = { state.setLsfgFlowScale(it.toInt()) }, onValueChangeFinished = { state.onApplyLsfg?.run() }, valueRange = 50f..200f, steps = 14, modifier = Modifier.fillMaxWidth())

            Text("Max Input Latency: ${state.getLsfgMaxLatency()}ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = state.getLsfgMaxLatency().toFloat(), onValueChange = { state.setLsfgMaxLatency(it.toInt()) }, onValueChangeFinished = { state.onApplyLsfg?.run() }, valueRange = 0f..33f, steps = 32, modifier = Modifier.fillMaxWidth())

            Button(onClick = { state.onResetLsfg?.run() }, modifier = Modifier.fillMaxWidth()) { Text("Reset to GPU Defaults") }
        }
    }
}

private fun pushFsrUpdate(enabled: Boolean, mode: Int, level: Float, hdr: Boolean) {
    XServerDialogState.onFsrUpdate?.invoke(enabled, mode, level, hdr)
}

@Composable
private fun SeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Text("$label: ${"%.1f".format(value)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SeShaderToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LsfgInlineDropdown(label: String, options: List<String>, selectedOption: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedOption, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
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

    Text("HUD", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))

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

    val initialScale = cfg.getOrDefault("hudScale", "100")
    val initialTrans = cfg.getOrDefault("hudTransparency", "0")
    var selectedScale by remember { mutableStateOf("${initialScale}%") }
    var selectedTrans by remember { mutableStateOf("${initialTrans}%") }

    fun buildConfig(): String = listOf(
        "hudMode=$hudMode",
        "showFPS=${if (showFPS) "1" else "0"}",
        "showCPULoad=${if (showCPULoad) "1" else "0"}",
        "showGPULoad=${if (showGPULoad) "1" else "0"}",
        "showRAM=${if (showRAM) "1" else "0"}",
        "showRenderer=${if (showRenderer) "1" else "0"}",
        "showBatteryTemp=${if (showBatteryTemp) "1" else "0"}",
        "hudScale=${selectedScale.removeSuffix("%")}",
        "hudTransparency=${selectedTrans.removeSuffix("%")}",
    ).joinToString(",")

    HudDropdown("HUD Scale", listOf("50%", "75%", "100%", "125%", "150%"), selectedScale) { selectedScale = it; state.onFpsConfigApply?.invoke(buildConfig()) }
    HudDropdown("HUD Transparency", listOf("0%", "10%", "20%", "30%", "40%", "50%"), selectedTrans) { selectedTrans = it; state.onFpsConfigApply?.invoke(buildConfig()) }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    HudCheckItem("Show FPS", showFPS) { showFPS = !showFPS; state.onFpsConfigApply?.invoke(buildConfig()) }
    HudCheckItem("Show CPU Temp", showCPULoad) { showCPULoad = !showCPULoad; state.onFpsConfigApply?.invoke(buildConfig()) }
    HudCheckItem("Show GPU Load", showGPULoad) { showGPULoad = !showGPULoad; state.onFpsConfigApply?.invoke(buildConfig()) }
    HudCheckItem("Show RAM", showRAM) { showRAM = !showRAM; state.onFpsConfigApply?.invoke(buildConfig()) }
    HudCheckItem("Show Renderer", showRenderer) { showRenderer = !showRenderer; state.onFpsConfigApply?.invoke(buildConfig()) }
    HudCheckItem("Show Battery Temp", showBatteryTemp) { showBatteryTemp = !showBatteryTemp; state.onFpsConfigApply?.invoke(buildConfig()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudDropdown(label: String, options: List<String>, selectedOption: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedOption, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false }) }
        }
    }
}

@Composable
private fun HudCheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
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

    Text("Controls", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))

    // Input Controls section
    var selectedIdx by remember(initProfileIdx) { mutableIntStateOf(initProfileIdx) }
    var showTouchscreen by remember(initTouchscreen) { mutableStateOf(initTouchscreen) }
    var timeoutEnabled by remember(initTimeout) { mutableStateOf(initTimeout) }
    var hapticsEnabled by remember(initHaptics) { mutableStateOf(initHaptics) }
    val allItems = listOf("-- Disabled --") + profiles
    var dropdownExpanded by remember { mutableStateOf(false) }

    Text("Input Controls", style = MaterialTheme.typography.labelMedium, color = Primary)
    Spacer(Modifier.height(4.dp))

    ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = it }) {
        OutlinedTextField(
            value = allItems.getOrElse(selectedIdx) { "-- Disabled --" },
            onValueChange = {}, readOnly = true,
            label = { Text("Profile") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
            allItems.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { selectedIdx = i; dropdownExpanded = false })
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    ControlsCheckRow("Show Touchscreen Controls", showTouchscreen) { showTouchscreen = it }
    ControlsCheckRow("Enable Timeout", timeoutEnabled) { timeoutEnabled = it }
    ControlsCheckRow("Enable Haptics", hapticsEnabled) { hapticsEnabled = it }

    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = {
        XServerDialogState.onInputControlsConfirm?.invoke(selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled)
        XServerDialogState.onInputControlsSettings?.run()
    }, modifier = Modifier.fillMaxWidth()) { Text("Profile Settings\u2026") }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Mouse & Cursor section
    Text("Mouse & Cursor", style = MaterialTheme.typography.labelMedium, color = Primary)
    Spacer(Modifier.height(4.dp))

    ControlsMouseCheckItem("Move Cursor to Touchpoint", moveCursorToTouch) { state.onMoveCursorToTouchpoint?.run(); state.onClose?.run() }
    ControlsMouseCheckItem("Relative Mouse Movement", isRelativeMouse) { state.onRelativeMouseMovement?.run(); state.onClose?.run() }
    ControlsMouseCheckItem("Disable Mouse", isMouseDisabled) { state.onDisableMouse?.run(); state.onClose?.run() }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // Vibration section (from VibrationDialog)
    Text("Vibration", style = MaterialTheme.typography.labelMedium, color = Primary)
    Spacer(Modifier.height(4.dp))

    val vibrationSlots by XServerDialogState.vibrationSlots.collectAsState()
    vibrationSlots.forEachIndexed { index, slot ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(slot.first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(checked = slot.second, onCheckedChange = { XServerDialogState.onVibrationSlotChanged?.invoke(index, it) })
        }
    }
}

@Composable
private fun ControlsCheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ControlsMouseCheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
    }
}

// ───── Task Manager Tab ─────

@Composable
private fun TaskManagerContent(state: XServerDrawerState) {
    val processes by XServerDialogState.tmProcesses.collectAsState()
    val cpuCores by XServerDialogState.tmCpuCores.collectAsState()
    val cpuTitle by XServerDialogState.tmCpuTitle.collectAsState()
    val memTitle by XServerDialogState.tmMemTitle.collectAsState()
    val memInfo by XServerDialogState.tmMemInfo.collectAsState()
    val count by XServerDialogState.tmCount.collectAsState()
    val selectedTab by state.selectedTab.collectAsState()

    LaunchedEffect(selectedTab) {
        if (selectedTab == TabType.TASK_MANAGER) {
            while (true) {
                XServerDialogState.onTmRefresh?.run()
                delay(1000L)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { XServerDialogState.onTmDismissed?.run() }
    }

    Text("Task Manager", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    Text("Processes: $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    if (processes.isEmpty()) {
        Text("No processes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            items(processes, key = { it.pid }) { proc ->
                TmProcessRow(proc)
                HorizontalDivider()
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    Text(cpuTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    cpuCores.forEach { core -> Text(core, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    Spacer(Modifier.height(4.dp))
    Text(memTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(memInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = {
            XServerDialogState.onTmDismissed?.run()
            XServerDialogState.onTmNewTask?.run()
        }) { Text("New Task\u2026") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {
            XServerDialogState.onTmDismissed?.run()
            state.onClose?.run()
        }) { Text("Close") }
    }
}

@Composable
private fun TmProcessRow(proc: XServerDialogState.TmProcess) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (proc.icon != null) {
            Image(bitmap = proc.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            Icon(painter = painterResource(R.drawable.taskmgr_process), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = proc.name + if (proc.wow64) " *32" else "",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "PID ${proc.pid}  \u2022  ${proc.formattedMemory}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Primary)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
