package com.winlator.star.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.winlator.star.R

/**
 * Opt-in confirmation shown when the user selects the experimental SurfaceFlinger (ASR) renderer.
 * ASR composites game frames straight through the display hardware; on some devices/GPUs that can
 * fault the display driver and reboot the device, so selecting it requires explicit confirmation.
 */
@Composable
fun SurfaceFlingerWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.surface_flinger_warning_title)) },
        text = {
            Text(stringResource(R.string.surface_flinger_warning_body))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.surface_flinger_warning_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
