package com.winlator.star.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Opt-in confirmation shown when the user selects the experimental SurfaceFlinger (ASR) renderer.
 * ASR composites game frames straight through the display hardware; on some devices/GPUs that can
 * fault the display driver and reboot the device, so selecting it requires explicit confirmation.
 */
@Composable
fun SurfaceFlingerWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SurfaceFlinger renderer — Experimental") },
        text = {
            Text(
                "The SurfaceFlinger renderer composites game frames directly through your device's " +
                "display hardware, bypassing the GL/Vulkan compositor.\n\n" +
                "On some devices and SoCs this can fault the display/GPU driver and REBOOT your device — " +
                "possibly losing unsaved game progress or corrupting the container. It is validated only " +
                "on recent Adreno GPUs.\n\n" +
                "Use at your own risk."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("I understand, use it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
