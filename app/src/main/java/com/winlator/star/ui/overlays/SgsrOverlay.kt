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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.foundation.clickable
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SgsrOverlay(state: XServerDialogState) {
    val initEnabled   by state.sgsrEnabled.collectAsState()
    val initSharpness by state.sgsrSharpness.collectAsState()
    val initHdr       by state.hdrEnabled.collectAsState()

    var sgsrEnabled   by remember(initEnabled)   { mutableStateOf(initEnabled) }
    var sgsrSharpness by remember(initSharpness)  { mutableIntStateOf(initSharpness) }
    var hdrEnabled    by remember(initHdr)        { mutableStateOf(initHdr) }

    var offsetX by remember { mutableFloatStateOf(100f) }
    var offsetY by remember { mutableFloatStateOf(100f) }

    fun pushUpdate() {
        state.onSgsrUpdate?.invoke(sgsrEnabled, sgsrSharpness, hdrEnabled)
    }

    Dialog(
        onDismissRequest = { state.setSgsrVisible(false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                setGravity(Gravity.TOP or Gravity.START)
                val attrs = attributes
                attrs.x = offsetX.roundToInt()
                attrs.y = offsetY.roundToInt()
                attributes = attrs
            }
        }

        Column(
            modifier = Modifier
                .width(260.dp)
                .heightIn(max = 520.dp)
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

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                // SGSR toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SGSR", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Switch(
                        checked = sgsrEnabled,
                        onCheckedChange = { sgsrEnabled = it; pushUpdate() }
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Sharpness slider
                Text(
                    "Sharpness: $sgsrSharpness",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = sgsrSharpness.toFloat(),
                    onValueChange = { sgsrSharpness = it.roundToInt() },
                    onValueChangeFinished = { pushUpdate() },
                    valueRange = 0f..100f,
                    steps = 99,
                    enabled = sgsrEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

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

                // LSFG settings
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
                        "Vegas FrameGen",
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

                        Button(
                            onClick = { drawerState.onResetLsfg?.run() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset to GPU Defaults")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { state.setSgsrVisible(false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close")
            }
        }
    }
}
