package com.winlator.star.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.ui.XServerDrawerState

private val qualityNames = listOf("performance", "balanced", "quality")
private val gpuArchNames = listOf("auto", "mali", "adreno")

@Composable
fun LsfgSettingsScreen() {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var multiplier by remember { mutableIntStateOf(prefs.getInt("lsfg_default_multiplier", 2)) }
    var quality by remember { mutableIntStateOf(prefs.getInt("lsfg_default_quality", 1)) }
    var flowScale by remember { mutableIntStateOf(prefs.getInt("lsfg_default_flow_scale", 100)) }
    var maxLatency by remember { mutableIntStateOf(prefs.getInt("lsfg_default_max_latency", 16)) }
    var gpuArch by remember { mutableIntStateOf(prefs.getInt("lsfg_default_gpu_arch", 0)) }
    var showSavedNote by remember { mutableStateOf(false) }

    val qualityLabels = listOf(
        context.getString(R.string.lsfg_quality_performance),
        context.getString(R.string.lsfg_quality_balanced),
        context.getString(R.string.lsfg_quality_quality)
    )
    val gpuArchLabels = listOf(
        context.getString(R.string.lsfg_gpu_arch_auto),
        context.getString(R.string.lsfg_gpu_arch_mali),
        context.getString(R.string.lsfg_gpu_arch_adreno)
    )

    fun applyGpuDefaults() {
        val defaults = Container.getLsfgDefaults()
        multiplier = defaults.multiplier
        quality = qualityNames.indexOf(defaults.quality).coerceAtLeast(0)
        flowScale = defaults.flowScale
        maxLatency = defaults.maxLatency
        gpuArch = gpuArchNames.indexOf(defaults.gpuArch).coerceAtLeast(0)
    }

    fun pushToXServer() {
        try {
            val state = XServerDrawerState
            state.setLsfgMultiplier(multiplier)
            state.setLsfgQuality(qualityNames.getOrElse(quality) { "balanced" })
            state.setLsfgFlowScale(flowScale)
            state.setLsfgMaxLatency(maxLatency)
            state.setLsfgGpuArch(gpuArchNames.getOrElse(gpuArch) { "auto" })
            state.onApplyLsfg?.run()
        } catch (_: Exception) {}
    }

    fun saveDefaults() {
        prefs.edit()
            .putInt("lsfg_default_multiplier", multiplier)
            .putInt("lsfg_default_quality", quality)
            .putInt("lsfg_default_flow_scale", flowScale)
            .putInt("lsfg_default_max_latency", maxLatency)
            .putInt("lsfg_default_gpu_arch", gpuArch)
            .apply()
        showSavedNote = true
        pushToXServer()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = context.getString(R.string.lsfg_settings),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Lossless Scaling Frame Generation (LSFG) settings and tuning.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        SectionLabel(context.getString(R.string.lsfg_multiplier))
        DescriptionText(context.getString(R.string.lsfg_multiplier_desc))
        Spacer(Modifier.height(4.dp))
        MultiToggle(
            labels = listOf("2x", "3x", "4x"),
            selectedIndex = multiplier - 2,
            onSelect = { multiplier = it + 2 }
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel(context.getString(R.string.lsfg_quality))
        DescriptionText(context.getString(R.string.lsfg_quality_desc))
        Spacer(Modifier.height(4.dp))
        MultiToggle(
            labels = qualityLabels,
            selectedIndex = quality,
            onSelect = { quality = it }
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel(context.getString(R.string.lsfg_flow_scale))
        DescriptionText(context.getString(R.string.lsfg_flow_scale_desc))
        Slider(
            value = flowScale.toFloat(),
            onValueChange = { flowScale = it.toInt() },
            valueRange = 50f..200f,
            steps = 14,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${flowScale}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel(context.getString(R.string.lsfg_max_latency))
        DescriptionText(context.getString(R.string.lsfg_max_latency_desc))
        Spacer(Modifier.height(4.dp))
        Slider(
            value = maxLatency.toFloat(),
            onValueChange = { maxLatency = it.toInt() },
            valueRange = 0f..33f,
            steps = 32,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${maxLatency}ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel(context.getString(R.string.lsfg_gpu_arch))
        DescriptionText(context.getString(R.string.lsfg_gpu_arch_desc))
        Spacer(Modifier.height(4.dp))
        MultiToggle(
            labels = gpuArchLabels,
            selectedIndex = gpuArch,
            onSelect = { gpuArch = it }
        )
        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { applyGpuDefaults() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset to GPU Defaults")
        }
        Spacer(Modifier.height(8.dp))

        if (showSavedNote) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = "Saved \u2014 overrides any live XServer adjustments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Button(
            onClick = { saveDefaults() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save as Defaults")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Saving will override any live XServer adjustments.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun DescriptionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MultiToggle(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
            ) {
                if (index == selectedIndex) {
                    androidx.compose.material3.Button(
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, maxLines = 1)
                    }
                } else {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}
