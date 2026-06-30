package com.winlator.star.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown on app startup when an installer-based component install is mid-flight (its previous
 * installer session ran and the app restarted). Lets the user finish — which applies the remaining
 * registry/override steps and launches the next installer if the component has one — or discard it.
 */
@Composable
fun ComponentInstallResume() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var pending by remember { mutableStateOf(ComponentExecInstaller.pendingComponentName(context)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = cs.surfaceContainerHigh,
            text = { Text(m, color = cs.onSurface) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    val name = pending ?: return

    AlertDialog(
        onDismissRequest = { /* keep until the user chooses */ },
        containerColor = cs.surfaceContainerHigh,
        title = { Text("Finish installing $name", color = cs.onSurface) },
        text = {
            Column {
                Text(
                    if (busy) "Working on $name…"
                    else "The $name installer ran. Finish setting it up in the container? " +
                        "If it needs another installer, the container will open again.",
                    color = cs.onSurface,
                )
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        // intentional: status color (install-in-progress cyan, distinct from action accent)
                        color = Color(0xFF4FC3F7), trackColor = cs.surfaceContainerHighest,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ComponentExecInstaller.resume(context) { f -> activity?.runOnUiThread { progress = f } }
                    }
                    busy = false
                    pending = null
                    when (res) {
                        is ComponentExecInstaller.Result.Launched -> { /* heading into the next session */ }
                        is ComponentExecInstaller.Result.Done -> message = "$name installed."
                        is ComponentExecInstaller.Result.Error -> message = "Couldn't finish $name: ${res.message}"
                        null -> {}
                    }
                }
            }) { Text("Finish") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                ComponentExecInstaller.clearPlan(context)
                pending = null
            }) { Text("Discard", color = Color(0xFFE57373)) } // intentional: destructive-action red
        },
    )
}
