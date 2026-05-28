package com.winlator.star.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.core.KeyValueSet
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.widget.FrameRating

@Composable
fun FpsCounterConfigDialog(state: XServerDialogState) {
    val initialConfig by state.fpsCounterConfig.collectAsState()
    val parsed = remember(initialConfig) { parseFpsConfig(initialConfig) }

    var horizontalMode    by remember(parsed) { mutableStateOf(parsed["hudMode"] == "horizontal") }
    var showFps           by remember(parsed) { mutableStateOf(parsed.getOrDefault("showFPS", "1") == "1") }
    var showCpuTemp       by remember(parsed) { mutableStateOf(parsed["showCPULoad"] == "1") }
    var showGpuLoad       by remember(parsed) { mutableStateOf(parsed["showGPULoad"] == "1") }
    var showRam           by remember(parsed) { mutableStateOf(parsed["showRAM"] == "1") }
    var showRenderer      by remember(parsed) { mutableStateOf(parsed["showRenderer"] == "1") }
    var showBatteryTemp   by remember(parsed) { mutableStateOf(parsed["showBatteryTemp"] == "1") }
    var showBatteryVolt   by remember(parsed) { mutableStateOf(parsed["showBatteryVoltage"] == "1") }
    var hudScale          by remember(parsed) { mutableFloatStateOf(parsed["hudScale"]?.toFloatOrNull() ?: 100f) }
    var hudTransparency   by remember(parsed) { mutableFloatStateOf(parsed["hudTransparency"]?.toFloatOrNull() ?: 0f) }

    fun buildConfigString(): String = buildFpsConfig(
        horizontalMode, showFps, showCpuTemp, showGpuLoad, showRam,
        showRenderer, showBatteryTemp, showBatteryVolt, hudScale, hudTransparency
    )

    Dialog(
        onDismissRequest = { state.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(modifier = Modifier.width(360.dp)) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("FPS Counter Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                FpsCheckRow(
                    label = "Enable Horizontal HUD Mode",
                    checked = horizontalMode,
                    bold = true,
                    onCheckedChange = { horizontalMode = it }
                )

                Column(modifier = Modifier.alpha(if (horizontalMode) 0.4f else 1.0f)) {
                    FpsCheckRow("Show FPS",                  showFps,        enabled = !horizontalMode) { showFps = it }
                    FpsCheckRow("Show CPU Temperature",      showCpuTemp,    enabled = !horizontalMode) { showCpuTemp = it }
                    FpsCheckRow("Show GPU Load",             showGpuLoad,    enabled = !horizontalMode) { showGpuLoad = it }
                    FpsCheckRow("Show RAM Usage",            showRam,        enabled = !horizontalMode) { showRam = it }
                    FpsCheckRow("Show Renderer/GPU Name",    showRenderer,   enabled = !horizontalMode) { showRenderer = it }
                    FpsCheckRow("Show Battery Temperature",  showBatteryTemp, enabled = !horizontalMode) { showBatteryTemp = it }
                    FpsCheckRow("Show Battery Wattage",      showBatteryVolt, enabled = !horizontalMode) { showBatteryVolt = it }
                }

                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    label = "HUD Scale",
                    valueText = "${hudScale.toInt().coerceAtLeast(50)}%",
                    value = hudScale,
                    range = 50f..150f,
                    onValueChange = { hudScale = it }
                )

                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    label = "HUD Transparency",
                    valueText = hudTransparency.toInt().toString(),
                    value = hudTransparency,
                    range = 0f..50f,
                    onValueChange = { hudTransparency = it }
                )

                Spacer(Modifier.height(16.dp))
                Text("Live Preview", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))

                FpsHudPreview(
                    horizontalMode = horizontalMode,
                    configString = buildConfigString()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state.dismiss() }) { Text("Cancel") }
                    TextButton(onClick = {
                        state.onFpsCounterConfigApply?.invoke(buildConfigString())
                        state.dismiss()
                    }) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun FpsHudPreview(horizontalMode: Boolean, configString: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(4.dp)
    ) {
        if (horizontalMode) {
            // FrameRatingHorizontal does not yet exist in star (only the vertical FrameRating
            // is ported). Once com.winlator.star.widget.FrameRatingHorizontal is added,
            // swap the placeholder below for an AndroidView wrapping it (same applyConfig API).
            Text(
                text = "Horizontal HUD preview unavailable until FrameRatingHorizontal is ported",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    FrameRating(ctx, HashMap<String, Any?>()).apply {
                        applyConfig(configString)
                    }
                },
                update = { it.applyConfig(configString) }
            )
        }
    }
}

@Composable
private fun FpsCheckRow(
    label: String,
    checked: Boolean,
    bold: Boolean = false,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.bodyMedium,
            color = if (bold) Color(0xFF0277BD) else Color.Unspecified
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun parseFpsConfig(configString: String): Map<String, String> {
    if (configString.isEmpty()) return emptyMap()
    val out = HashMap<String, String>()
    for (entry in KeyValueSet(configString)) out[entry[0]] = entry[1]
    return out
}

private fun buildFpsConfig(
    horizontalMode: Boolean,
    showFps: Boolean, showCpuTemp: Boolean, showGpuLoad: Boolean, showRam: Boolean,
    showRenderer: Boolean, showBatteryTemp: Boolean, showBatteryVolt: Boolean,
    hudScale: Float, hudTransparency: Float
): String {
    val kv = KeyValueSet()
    kv.put("hudMode",            if (horizontalMode) "horizontal" else "vertical")
    kv.put("showFPS",            if (showFps) "1" else "0")
    kv.put("showCPULoad",        if (showCpuTemp) "1" else "0")
    kv.put("showGPULoad",        if (showGpuLoad) "1" else "0")
    kv.put("showRAM",            if (showRam) "1" else "0")
    kv.put("showRenderer",       if (showRenderer) "1" else "0")
    kv.put("showBatteryTemp",    if (showBatteryTemp) "1" else "0")
    kv.put("showBatteryVoltage", if (showBatteryVolt) "1" else "0")
    kv.put("hudScale",           hudScale.toInt().coerceAtLeast(50).toString())
    kv.put("hudTransparency",    hudTransparency.toInt().toString())
    return kv.toString()
}

