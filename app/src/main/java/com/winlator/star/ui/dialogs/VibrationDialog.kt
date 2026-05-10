package com.winlator.star.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.XServerDialogState

@Composable
fun VibrationDialog(state: XServerDialogState) {
    val slots by state.vibrationSlots.collectAsState()
    val checked = remember { mutableStateListOf<Boolean>() }

    LaunchedEffect(slots) {
        checked.clear()
        checked.addAll(slots.map { it.second })
    }

    AlertDialog(
        onDismissRequest = { state.dismiss() },
        title = { Text("Vibration") },
        text = {
            Column {
                slots.forEachIndexed { i, (name, _) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = if (i < checked.size) checked[i] else false,
                            onCheckedChange = { isChecked ->
                                if (i < checked.size) {
                                    checked[i] = isChecked
                                    state.onVibrationSlotChanged?.invoke(i, isChecked)
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.dismiss() }) { Text("OK") }
        }
    )
}
