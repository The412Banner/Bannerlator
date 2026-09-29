package com.winlator.star.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Asked when an editor is left with edits that were never saved (system back, tap outside, ✕ or Cancel).
// Tapping outside the dialog or pressing back again means "Keep editing", so nothing is ever lost by accident.
@Composable
internal fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    OutlinedAlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Save changes?") },
        text = { Text("You changed settings that haven't been saved yet.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = Color(0xFFE57373)) } // intentional: destructive-action red
                TextButton(onClick = onKeepEditing) { Text("Keep editing") }
            }
        },
    )
}
