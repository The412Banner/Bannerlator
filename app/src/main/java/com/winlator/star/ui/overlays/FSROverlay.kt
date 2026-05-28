package com.winlator.star.ui.overlays

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDrawerState
import kotlin.math.roundToInt
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FSROverlay(state: XServerDialogState) {
    val initEnabled by state.fsrEnabled.collectAsState()
    val initMode    by state.fsrMode.collectAsState()
    val initLevel   by state.fsrLevel.collectAsState()
    val initHdr     by state.hdrEnabled.collectAsState()

    var fsrEnabled by remember(initEnabled) { mutableStateOf(initEnabled) }
    var fsrMode    by remember(initMode)    { mutableIntStateOf(initMode) }
    var fsrLevel   by remember(initLevel)   { mutableFloatStateOf(initLevel) }
    var hdrEnabled by remember(initHdr)     { mutableStateOf(initHdr) }

    var offsetX by remember { mutableFloatStateOf(100f) }
    var offsetY by remember { mutableFloatStateOf(100f) }

    var modeDropdownExpanded by remember { mutableStateOf(false) }
    val modeNames = listOf("Super Resolution", "DLS (Color Boost)")

    fun pushUpdate() {
        state.onFsrUpdate?.invoke(fsrEnabled, fsrMode, fsrLevel, hdrEnabled)
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                setGravity(Gravity.TOP or Gravity.START)
                val attrs = attributes
                attrs.x = offsetX.roundToInt()
                attrs.y = offsetY.roundToInt()
                attributes = attrs
            }
        }

        Column(
            modifier = Modifier
                .wrapContentSize()
                .width(260.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            // Drag handle / title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    "Graphics Engine",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            // FSR toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("FSR", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = fsrEnabled,
                    onCheckedChange = { fsrEnabled = it; pushUpdate() }
                )
            }

            // Mode dropdown
            ExposedDropdownMenuBox(
                expanded = modeDropdownExpanded,
                onExpandedChange = { if (fsrEnabled) modeDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = modeNames.getOrElse(fsrMode) { modeNames[0] },
                    onValueChange = {},
                    readOnly = true,
                    enabled = fsrEnabled,
                    label = { Text("Mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = modeDropdownExpanded,
                    onDismissRequest = { modeDropdownExpanded = false }
                ) {
                    modeNames.forEachIndexed { i, name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { fsrMode = i; modeDropdownExpanded = false; pushUpdate() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Level slider
            Text(
                "Strength: ${"%.0f".format(fsrLevel)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = fsrLevel,
                onValueChange = { fsrLevel = it },
                onValueChangeFinished = { pushUpdate() },
                valueRange = 1f..5f,
                steps = 3,
                enabled = fsrEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            // HDR toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("HDR", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = hdrEnabled,
                    onCheckedChange = { hdrEnabled = it; pushUpdate() }
                )
            }

            Spacer(Modifier.height(8.dp))

            // LSFG settings — synced with XServerDrawerState
            var lsfgExpanded by remember { mutableStateOf(false) }
            val drawerState = XServerDrawerState

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { lsfgExpanded = !lsfgExpanded }
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    "Lossless Scaling FG",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (lsfgExpanded) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = lsfgExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Multiplier
                    var multiplierExpanded by remember { mutableStateOf(false) }
                    val multiplierOptions = listOf("2x", "3x", "4x", "5x", "6x", "7x", "8x", "9x", "10x")
                    ExposedDropdownMenuBox(
                        expanded = multiplierExpanded,
                        onExpandedChange = { multiplierExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${drawerState.getLsfgMultiplier()}x",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Multiplier") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = multiplierExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = multiplierExpanded,
                            onDismissRequest = { multiplierExpanded = false }
                        ) {
                            multiplierOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        val num = opt.removeSuffix("x").toIntOrNull() ?: 2
                                        drawerState.setLsfgMultiplier(num)
                                        drawerState.onApplyLsfg?.run()
                                        multiplierExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Quality
                    var qualityExpanded by remember { mutableStateOf(false) }
                    val qualityOptions = listOf("performance", "balanced", "quality")
                    ExposedDropdownMenuBox(
                        expanded = qualityExpanded,
                        onExpandedChange = { qualityExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = drawerState.getLsfgQuality(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Quality") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = qualityExpanded,
                            onDismissRequest = { qualityExpanded = false }
                        ) {
                            qualityOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        drawerState.setLsfgQuality(opt)
                                        drawerState.onApplyLsfg?.run()
                                        qualityExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Flow Scale
                    Text(
                        "Flow Scale: ${drawerState.getLsfgFlowScale()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = drawerState.getLsfgFlowScale().toFloat(),
                        onValueChange = { drawerState.setLsfgFlowScale(it.toInt()) },
                        onValueChangeFinished = { drawerState.onApplyLsfg?.run() },
                        valueRange = 50f..200f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Max Latency
                    Text(
                        "Max Input Latency: ${drawerState.getLsfgMaxLatency()}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = drawerState.getLsfgMaxLatency().toFloat(),
                        onValueChange = { drawerState.setLsfgMaxLatency(it.toInt()) },
                        onValueChangeFinished = { drawerState.onApplyLsfg?.run() },
                        valueRange = 0f..33f,
                        steps = 32,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Reset
                    Button(
                        onClick = { drawerState.onResetLsfg?.run() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset to GPU Defaults")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { state.setFsrVisible(false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    }
}
