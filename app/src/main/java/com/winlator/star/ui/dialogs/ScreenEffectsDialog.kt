package com.winlator.star.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.winlator.star.ui.XServerDialogState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenEffectsDialog(state: XServerDialogState) {
    val profiles       by state.seProfiles.collectAsState()
    val initProfile    by state.seSelectedProfile.collectAsState()
    val initBrightness by state.seBrightness.collectAsState()
    val initContrast   by state.seContrast.collectAsState()
    val initGamma      by state.seGamma.collectAsState()
    val initFxaa       by state.seFxaa.collectAsState()
    val initCrt        by state.seCrt.collectAsState()
    val initToon       by state.seToon.collectAsState()
    val initNtsc       by state.seNtsc.collectAsState()

    var profileIndex    by remember(initProfile)    { mutableIntStateOf(initProfile) }
    var brightness      by remember(initBrightness) { mutableFloatStateOf(initBrightness) }
    var contrast        by remember(initContrast)   { mutableFloatStateOf(initContrast) }
    var gamma           by remember(initGamma)      { mutableFloatStateOf(initGamma) }
    var fxaa            by remember(initFxaa)       { mutableStateOf(initFxaa) }
    var crt             by remember(initCrt)        { mutableStateOf(initCrt) }
    var toon            by remember(initToon)       { mutableStateOf(initToon) }
    var ntsc            by remember(initNtsc)       { mutableStateOf(initNtsc) }

    var profileDropdownExpanded by remember { mutableStateOf(false) }
    var showAddProfileDialog    by remember { mutableStateOf(false) }
    var showRemoveConfirm       by remember { mutableStateOf(false) }
    var newProfileName          by remember { mutableStateOf("") }

    val profileItems = listOf("-- Default --") + profiles

    fun resetToDefault() {
        brightness = 0f; contrast = 0f; gamma = 1.0f
        fxaa = false; crt = false; toon = false; ntsc = false
    }

    Dialog(
        onDismissRequest = { state.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Screen Effects", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                // Profile selector
                ExposedDropdownMenuBox(
                    expanded = profileDropdownExpanded,
                    onExpandedChange = { profileDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = profileItems.getOrElse(profileIndex) { "-- Default --" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Profile") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = profileDropdownExpanded,
                        onDismissRequest = { profileDropdownExpanded = false }
                    ) {
                        profileItems.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { profileIndex = i; profileDropdownExpanded = false }
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showAddProfileDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Add") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { if (profileIndex > 0) showRemoveConfirm = true },
                        modifier = Modifier.weight(1f),
                        enabled = profileIndex > 0
                    ) { Text("Remove") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Color adjustment sliders
                Text("Color Adjustment", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))

                LabeledSlider("Brightness: ${brightness.toInt()}", brightness, -100f..100f) { brightness = it }
                LabeledSlider("Contrast: ${contrast.toInt()}",     contrast,   -100f..100f) { contrast   = it }
                LabeledSlider("Gamma: ${"%.2f".format(gamma)}",    gamma,      0.5f..3.0f)  { gamma      = it }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Shader toggles
                Text("Shaders", style = MaterialTheme.typography.labelMedium)
                SeCheckRow("Enable FXAA",        fxaa) { fxaa = it }
                SeCheckRow("Enable CRT Shader",  crt)  { crt  = it }
                SeCheckRow("Enable Toon Shader", toon) { toon = it }
                SeCheckRow("Enable NTSC Effect", ntsc) { ntsc = it }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Action buttons
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { resetToDefault() }) { Text("Reset") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state.dismiss() }) { Text("Cancel") }
                    TextButton(onClick = {
                        state.onScreenEffectsApply?.invoke(
                            brightness, contrast, gamma, fxaa, crt, toon, ntsc, profileIndex
                        )
                        state.dismiss()
                    }) { Text("Apply") }
                }
            }
        }
    }

    // Add profile dialog
    if (showAddProfileDialog) {
        AlertDialog(
            onDismissRequest = { showAddProfileDialog = false },
            title = { Text("Add Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newProfileName.isNotBlank()) {
                        state.onSeAddProfile?.invoke(newProfileName.trim())
                        newProfileName = ""
                    }
                    showAddProfileDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddProfileDialog = false; newProfileName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Remove profile confirm
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Profile") },
            text = { Text("Remove '${profileItems.getOrElse(profileIndex) { "" }}'?") },
            confirmButton = {
                TextButton(onClick = {
                    val name = profiles.getOrNull(profileIndex - 1) ?: ""
                    state.onSeRemoveProfile?.invoke(name)
                    profileIndex = 0
                    showRemoveConfirm = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    )
}

@Composable
private fun SeCheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
