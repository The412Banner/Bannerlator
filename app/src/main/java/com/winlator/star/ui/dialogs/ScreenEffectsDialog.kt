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
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.outlinedMenuCard
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.winlator.star.R
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

    val defaultProfile = stringResource(R.string.screen_effects_default_profile)
    val profileItems = listOf(defaultProfile) + profiles

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
                Text(stringResource(R.string.screen_effects_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                // Profile selector
                ExposedDropdownMenuBox(
                    expanded = profileDropdownExpanded,
                    onExpandedChange = { profileDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = profileItems.getOrElse(profileIndex) { defaultProfile },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.screen_effects_profile)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = profileDropdownExpanded,
                        onDismissRequest = { profileDropdownExpanded = false },
                        modifier = Modifier.outlinedMenuCard()
                    ) {
                        profileItems.forEachIndexed { i, label ->
                            if (i > 0) MenuItemDivider()
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
                    ) { Text(stringResource(R.string.screen_effects_add)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { if (profileIndex > 0) showRemoveConfirm = true },
                        modifier = Modifier.weight(1f),
                        enabled = profileIndex > 0
                    ) { Text(stringResource(R.string.screen_effects_remove)) }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Color adjustment sliders
                Text(stringResource(R.string.screen_effects_color_adjustment), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))

                LabeledSlider(stringResource(R.string.screen_effects_brightness, brightness.toInt()), brightness, -100f..100f) { brightness = it }
                LabeledSlider(stringResource(R.string.screen_effects_contrast, contrast.toInt()), contrast, -100f..100f) { contrast = it }
                LabeledSlider(stringResource(R.string.screen_effects_gamma, gamma), gamma, 0.5f..3.0f) { gamma = it }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Shader toggles
                Text(stringResource(R.string.screen_effects_shaders), style = MaterialTheme.typography.labelMedium)
                SeCheckRow(stringResource(R.string.screen_effects_enable_fxaa), fxaa) { fxaa = it }
                SeCheckRow(stringResource(R.string.screen_effects_enable_crt), crt) { crt = it }
                SeCheckRow(stringResource(R.string.screen_effects_enable_toon), toon) { toon = it }
                SeCheckRow(stringResource(R.string.screen_effects_enable_ntsc), ntsc) { ntsc = it }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Action buttons
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { resetToDefault() }) { Text(stringResource(R.string.screen_effects_reset)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state.dismiss() }) { Text(stringResource(R.string.screen_effects_cancel)) }
                    TextButton(onClick = {
                        state.onScreenEffectsApply?.invoke(
                            brightness, contrast, gamma, fxaa, crt, toon, ntsc, profileIndex
                        )
                        state.dismiss()
                    }) { Text(stringResource(R.string.screen_effects_apply)) }
                }
            }
        }
    }

    // Add profile dialog
    if (showAddProfileDialog) {
        OutlinedAlertDialog(
            onDismissRequest = { showAddProfileDialog = false },
            title = { Text(stringResource(R.string.screen_effects_add_profile)) },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text(stringResource(R.string.screen_effects_profile_name)) },
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
                }) { Text(stringResource(R.string.screen_effects_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddProfileDialog = false; newProfileName = "" }) {
                    Text(stringResource(R.string.screen_effects_cancel))
                }
            }
        )
    }

    // Remove profile confirm
    if (showRemoveConfirm) {
        OutlinedAlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.screen_effects_remove_profile)) },
            text = { Text(stringResource(R.string.screen_effects_remove_profile_message, profileItems.getOrElse(profileIndex) { "" })) },
            confirmButton = {
                TextButton(onClick = {
                    val name = profiles.getOrNull(profileIndex - 1) ?: ""
                    state.onSeRemoveProfile?.invoke(name)
                    profileIndex = 0
                    showRemoveConfirm = false
                }) { Text(stringResource(R.string.screen_effects_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text(stringResource(R.string.screen_effects_cancel)) }
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
