package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.delay

/**
 * A Lab page over the current one, for the tools the settings editors open in place (the wrapper manager, the VEGAS
 * config editor): full screen on Lab's wallpaper, with the page's button hints along the bottom. Back runs
 * [onDismiss] unless the page handles it itself. The editor's own hints, Section handler and legend come back when it closes.
 */
@Composable
internal fun LabOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val deckActions = LocalDeckActions.current
    val savedLegend = remember { deckActions.ownLegend.value }
    val savedTab = remember { deckActions.subTab.value }
    val savedTabLabel = remember { deckActions.subTabLabel.value }
    DisposableEffect(Unit) {
        onDispose {
            deckActions.ownLegend.value = savedLegend
            deckActions.subTab.value = savedTab
            deckActions.subTabLabel.value = savedTabLabel
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.winlator.star.sound.DialogSound()
        SimpleFlat {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                LabWallpaper()
                Column(Modifier.fillMaxSize().padding(top = 10.dp, bottom = 8.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    val legend = deckActions.ownLegend.value
                    if (!legend.isNullOrEmpty()) SimpleLegend(legend, Modifier.padding(start = 40.dp, top = 6.dp))
                }
            }
        }
    }
}

/** A text field in a Lab panel (a name, a value): [confirm] keeps it, Cancel or B leaves it. */
@Composable
internal fun LabFieldDialog(
    title: String,
    initial: String,
    hint: String? = null,
    multiline: Boolean = false,
    confirm: String = L("Save"),
    message: String? = null,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { field.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (!message.isNullOrBlank()) Text(message, color = SimpleMuted, fontSize = 14.sp)
            val accent = MaterialTheme.colorScheme.primary
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                placeholder = hint?.let { h -> { Text(h, color = SimpleDim) } },
                shape = SimpleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SimpleStrong,
                    unfocusedTextColor = SimpleInk,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = SimpleLine,
                    cursorColor = accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Cancel"), onClick = onDismiss)
                LabButton(confirm, primary = true, onClick = { onDone(v) })
            }
        }
    }
}

/** A Lab note with one OK (what Classic shows as an information dialog). */
@Composable
internal fun LabNote(title: String, body: String, onDismiss: () -> Unit) {
    SimpleActionSheet(title = title, actions = listOf(MenuAction(L("OK"), onDismiss)), onDismiss = onDismiss, message = body) { it.run() }
}
