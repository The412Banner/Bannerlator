package com.winlator.star.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import com.winlator.star.ui.screens.MenuItemDivider
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.outlinedMenuCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.ui.XServerDialogState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputControlsDialog(state: XServerDialogState) {
    val profiles         by state.inputProfiles.collectAsState()
    val initProfileIdx   by state.selectedProfileIdx.collectAsState()
    val initTouchscreen  by state.showTouchscreen.collectAsState()
    val initTimeout      by state.timeoutEnabled.collectAsState()
    val initHaptics      by state.hapticsEnabled.collectAsState()

    var selectedIdx      by remember(initProfileIdx)  { mutableIntStateOf(initProfileIdx) }
    var showTouchscreen  by remember(initTouchscreen)  { mutableStateOf(initTouchscreen) }
    var timeoutEnabled   by remember(initTimeout)      { mutableStateOf(initTimeout) }
    var hapticsEnabled   by remember(initHaptics)      { mutableStateOf(initHaptics) }

    val disabledLabel = stringResource(R.string.compose_input_disabled)
    val allItems = listOf(disabledLabel) + profiles
    var dropdownExpanded by remember { mutableStateOf(false) }

    OutlinedAlertDialog(
        onDismissRequest = { state.dismiss() },
        title = { Text(stringResource(R.string.compose_input_controls_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Profile dropdown
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = allItems.getOrElse(selectedIdx) { disabledLabel },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.compose_input_profile)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.outlinedMenuCard()
                    ) {
                        allItems.forEachIndexed { i, label ->
                            if (i > 0) MenuItemDivider()
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedIdx = i
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                CheckRow(stringResource(R.string.compose_input_show_touchscreen_controls), showTouchscreen) {
                    showTouchscreen = it
                }
                CheckRow(stringResource(R.string.compose_input_enable_timeout), timeoutEnabled) {
                    timeoutEnabled = it
                }
                CheckRow(stringResource(R.string.compose_input_enable_haptics), hapticsEnabled) {
                    hapticsEnabled = it
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        state.onInputControlsConfirm?.invoke(
                            selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled
                        )
                        state.onInputControlsSettings?.invoke(selectedIdx)
                    },
                    enabled = selectedIdx > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.compose_input_profile_settings))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.setSelectedProfileIdx(selectedIdx)
                state.onInputControlsConfirm?.invoke(
                    selectedIdx, showTouchscreen, timeoutEnabled, hapticsEnabled
                )
                state.dismiss()
            }) { Text(stringResource(R.string.compose_input_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { state.dismiss() }) {
                Text(stringResource(R.string.compose_input_cancel))
            }
        }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
