package com.winlator.star.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.contents.DriverInstaller.Place
import com.winlator.star.contents.DriverPartsPrompt
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.LabButton
import com.winlator.star.ui.simple.LabDialog
import com.winlator.star.ui.simple.LabDialogButtons
import com.winlator.star.ui.simple.LabToolRow
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.delay

/**
 * Asks which drivers of a zip that holds several (DroidDeck's Android + Linux) to install, for [DriverPartsPrompt].
 * Lab's look in Lab, Classic's everywhere else. Android starts ticked, the rest not.
 */
@Composable
fun DriverPartsPromptHost() {
    DisposableEffect(Unit) {
        DriverPartsPrompt.hostShown()
        onDispose { DriverPartsPrompt.hostGone() }
    }
    val request by DriverPartsPrompt.request.collectAsState()
    val r = request ?: return
    key(r) {
        var picked by remember { mutableStateOf(DriverPartsPrompt.defaultParts(r.parts)) }
        var answered by remember { mutableStateOf(false) }
        val toggle = { p: Place -> picked = if (p in picked) picked - p else picked + p }
        val install = {
            if (picked.isNotEmpty()) {
                answered = true
                DriverPartsPrompt.answer(r, picked)
            }
        }
        val cancel = {
            answered = true
            DriverPartsPrompt.answer(r, null)
        }
        if (!answered) {
            if (AppThemeState.simpleLook) LabParts(r, picked, toggle, install, cancel)
            else ClassicParts(r, picked, toggle, install, cancel)
        }
    }
}

private fun partLabel(p: Place): String = when (p) {
    Place.ANDROID -> L("Android driver")
    Place.LINUX -> L("Linux driver")
    Place.WAYLAND -> L("Wayland game drivers")
}

private fun partList(p: Place): String = when (p) {
    Place.ANDROID -> L("Graphics Driver")
    Place.LINUX -> L("Linux runtime drivers")
    Place.WAYLAND -> L("Wayland game drivers")
}

@Composable
private fun LabParts(r: DriverPartsPrompt.Request, picked: Set<Place>, toggle: (Place) -> Unit, install: () -> Unit, cancel: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(r) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    com.winlator.star.ui.deck.LabInGameTheme {
        LabDialog(r.name, onDismiss = cancel) {
            Text(L("Choose what to install"), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
            r.parts.forEachIndexed { i, p ->
                LabToolRow(partLabel(p), sub = partList(p), toggle = p in picked, requester = if (i == 0) first else null) { toggle(p) }
            }
            LabDialogButtons {
                LabButton(L("Cancel"), onClick = cancel)
                LabButton(L("Install"), primary = true, modifier = Modifier.alpha(if (picked.isNotEmpty()) 1f else 0.45f)) { install() }
            }
        }
    }
}

@Composable
private fun ClassicParts(r: DriverPartsPrompt.Request, picked: Set<Place>, toggle: (Place) -> Unit, install: () -> Unit, cancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(r.name) },
        text = {
            Column {
                Text(L("Choose what to install"), style = MaterialTheme.typography.bodyMedium)
                r.parts.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { toggle(p) }.padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = p in picked, onCheckedChange = { toggle(p) })
                        Column {
                            Text(partLabel(p), style = MaterialTheme.typography.bodyLarge)
                            Text(partList(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = install, enabled = picked.isNotEmpty()) { Text(L("Install")) } },
        dismissButton = { TextButton(onClick = cancel) { Text(L("Cancel")) } },
    )
}
