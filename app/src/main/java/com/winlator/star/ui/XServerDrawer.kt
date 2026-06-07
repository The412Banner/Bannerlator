package com.winlator.star.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import com.winlator.star.R
import com.winlator.star.ui.theme.WinlatorTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement

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

    val isPaused            by state.isPaused.collectAsState()
    val isRelativeMouse     by state.isRelativeMouseMovement.collectAsState()
    val isMouseDisabled     by state.isMouseDisabled.collectAsState()
    val moveCursorToTouch   by state.moveCursorToTouchpoint.collectAsState()
    val showLogs            by state.showLogs.collectAsState()
    val lsfgEnabled         by state.lsfgEnabled.collectAsState()
    val showMagnifier       by state.showMagnifier.collectAsState()
    val cursorExpanded      by state.cursorExpanded.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Star Bionic",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        DrawerMenuItem(
            iconRes = R.drawable.icon_keyboard,
            label = "Keyboard",
            onClick = { state.onKeyboard?.run(); state.onClose?.run() },
        )

        DrawerMenuItem(
            iconRes = R.drawable.icon_input_controls,
            label = "Input Controls",
            onClick = { state.onInputControls?.run(); state.onClose?.run() },
        )

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.toggleCursorExpanded() }
                .padding(horizontal = 20.dp, vertical = 13.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.cursor),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Mouse and Cursor",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (cursorExpanded) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = cursorExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                DrawerCheckItem(
                    label = "Move Cursor to Touchpoint",
                    checked = moveCursorToTouch,
                    onClick = { state.onMoveCursorToTouchpoint?.run(); state.onClose?.run() },
                )
                DrawerCheckItem(
                    label = "Relative Mouse Movement",
                    checked = isRelativeMouse,
                    onClick = { state.onRelativeMouseMovement?.run(); state.onClose?.run() },
                )
                DrawerCheckItem(
                    label = "Disable Mouse",
                    checked = isMouseDisabled,
                    onClick = { state.onDisableMouse?.run(); state.onClose?.run() },
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        DrawerMenuItem(
            iconRes = R.drawable.icon_screen_effect,
            label = "Screen Effects",
            onClick = { state.onScreenEffects?.run(); state.onClose?.run() },
        )

        DrawerMenuItem(
            iconRes = R.drawable.icon_settings,
            label = "Graphic Engine",
            onClick = { state.onGraphicEngine?.run(); state.onClose?.run() },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.onLsfgToggle?.run() }
                .padding(start = 20.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
        ) {
            Text(
                text = "Vegas FrameGen",
                style = MaterialTheme.typography.bodyMedium,
                color = if (lsfgEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = lsfgEnabled,
                onCheckedChange = { state.onLsfgToggle?.run() },
            )
        }

        AnimatedVisibility(
            visible = lsfgEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LsfgDropdown(
                    label = "Multiplier",
                    options = listOf("2x", "3x", "4x", "5x", "6x", "7x", "8x", "9x", "10x"),
                    selectedOption = "${state.getLsfgMultiplier()}x",
                    onSelect = { opt ->
                        val num = opt.removeSuffix("x").toIntOrNull() ?: 2
                        state.setLsfgMultiplier(num)
                        state.onApplyLsfg?.run()
                    },
                )

                val qualityOptions = listOf("performance", "balanced", "quality")
                LsfgDropdown(
                    label = "Quality",
                    options = qualityOptions,
                    selectedOption = state.getLsfgQuality(),
                    onSelect = { opt ->
                        state.setLsfgQuality(opt)
                        state.onApplyLsfg?.run()
                    },
                )

                Text(
                    "Flow Scale: ${state.getLsfgFlowScale()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Controls the density of motion vectors. Higher values capture more detail but may increase artifacts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Slider(
                    value = state.getLsfgFlowScale().toFloat(),
                    onValueChange = { state.setLsfgFlowScale(it.toInt()) },
                    onValueChangeFinished = { state.onApplyLsfg?.run() },
                    valueRange = 50f..200f,
                    steps = 14,
                )

                Text(
                    "Max Input Latency: ${state.getLsfgMaxLatency()}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Maximum number of frames the system can hold for processing. Lower values reduce input lag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Slider(
                    value = state.getLsfgMaxLatency().toFloat(),
                    onValueChange = { state.setLsfgMaxLatency(it.toInt()) },
                    onValueChangeFinished = { state.onApplyLsfg?.run() },
                    valueRange = 0f..33f,
                    steps = 32,
                )

                Button(
                    onClick = { state.onResetLsfg?.run() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset to GPU Defaults")
                }
            }
        }

        DrawerMenuItem(
            iconRes = R.drawable.icon_input_controls,
            label = "Vibration",
            onClick = { state.onVibration?.run(); state.onClose?.run() },
        )

        FpsCounterSection(state)

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        DrawerMenuItem(
            iconRes = R.drawable.icon_fullscreen,
            label = "Toggle Fullscreen",
            onClick = { state.onToggleFullscreen?.run(); state.onClose?.run() },
        )

        DrawerMenuItem(
            iconRes = if (isPaused) R.drawable.icon_play else R.drawable.icon_pause,
            label = if (isPaused) "Resume" else "Pause",
            onClick = { state.onPauseResume?.run(); state.onClose?.run() },
        )

        DrawerMenuItem(
            iconRes = R.drawable.ic_picture_in_picture_alt,
            label = "Picture in Picture",
            onClick = { state.onPipMode?.run(); state.onClose?.run() },
        )

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        DrawerMenuItem(
            iconRes = R.drawable.icon_active_windows,
            label = "Active Windows",
            onClick = { state.onActiveWindows?.run(); state.onClose?.run() },
        )

        DrawerMenuItem(
            iconRes = R.drawable.icon_task_manager,
            label = "Task Manager",
            onClick = { state.onTaskManager?.run(); state.onClose?.run() },
        )

        if (showMagnifier) {
            DrawerMenuItem(
                iconRes = R.drawable.icon_magnifier,
                label = "Magnifier",
                onClick = { state.onMagnifier?.run(); state.onClose?.run() },
            )
        }

        if (showLogs) {
            DrawerMenuItem(
                iconRes = R.drawable.icon_debug,
                label = "Logs",
                onClick = { state.onLogs?.run(); state.onClose?.run() },
            )
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        DrawerMenuItem(
            iconRes = R.drawable.icon_exit,
            label = "Exit",
            onClick = { state.onExit?.run() },
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerMenuItem(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DrawerCheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 56.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LsfgDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FpsCounterSection(state: XServerDrawerState) {
    val fpsExpanded by state.fpsExpanded.collectAsState()
    val fpsConfig by state.fpsConfig.collectAsState()

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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { state.toggleFpsExpanded() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.icon_debug),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "FPS Counter",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (fpsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }

    AnimatedVisibility(
        visible = fpsExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FpsDropdown(
                label = "HUD Scale",
                options = listOf("50%", "75%", "100%", "125%", "150%"),
                selectedOption = selectedScale,
                onSelect = { selectedScale = it; state.onFpsConfigApply?.invoke(buildConfig()) },
            )
            FpsDropdown(
                label = "HUD Transparency",
                options = listOf("0%", "10%", "20%", "30%", "40%", "50%"),
                selectedOption = selectedTrans,
                onSelect = { selectedTrans = it; state.onFpsConfigApply?.invoke(buildConfig()) },
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            FpsCheckItem("Show FPS", showFPS) { showFPS = !showFPS; state.onFpsConfigApply?.invoke(buildConfig()) }
            FpsCheckItem("Show CPU Temp", showCPULoad) { showCPULoad = !showCPULoad; state.onFpsConfigApply?.invoke(buildConfig()) }
            FpsCheckItem("Show GPU Load", showGPULoad) { showGPULoad = !showGPULoad; state.onFpsConfigApply?.invoke(buildConfig()) }
            FpsCheckItem("Show RAM", showRAM) { showRAM = !showRAM; state.onFpsConfigApply?.invoke(buildConfig()) }
            FpsCheckItem("Show Renderer", showRenderer) { showRenderer = !showRenderer; state.onFpsConfigApply?.invoke(buildConfig()) }
            FpsCheckItem("Show Battery Temp", showBatteryTemp) { showBatteryTemp = !showBatteryTemp; state.onFpsConfigApply?.invoke(buildConfig()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FpsDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun FpsCheckItem(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
