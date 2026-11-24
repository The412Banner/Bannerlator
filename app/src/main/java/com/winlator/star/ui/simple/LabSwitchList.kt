package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.delay

/**
 * A Lab pop-up of on/off switches, one per item (the idle-thread throttle's APIs). [off] are the items switched off;
 * Save hands back the ones switched off then. The title row has a "?" for [help] and a ✕; [onUseContainer] (a game with
 * its own value) adds "Use container setting". Controller: A switches the row, X saves, Start opens the "?", B closes.
 */
@Composable
internal fun LabSwitchListDialog(
    title: String,
    items: List<String>,
    labels: Map<String, String>,
    off: Set<String>,
    help: String?,
    onUseContainer: (() -> Unit)?,
    onDismiss: () -> Unit,
    onDone: (Set<String>) -> Unit,
) {
    var picked by remember(items, off) { mutableStateOf(items.filter { it in off }.toSet()) }
    var helpOpen by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 520.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .onPreviewKeyEvent { ev ->
                    when (simpleInputOf(ev.nativeKeyEvent)) {
                        SIn.X -> { onDone(picked); true }
                        SIn.START -> { if (help != null) helpOpen = true; help != null }
                        else -> false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp, bottom = 2.dp),
                )
                if (help != null) {
                    // Touch and the controller (Up from the first switch) both reach it; Start opens it too.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).labTile(onClick = { helpOpen = true }),
                    ) { Text("?", color = SimpleInk, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
                LabCloseButton(onDismiss)
            }
            items.forEachIndexed { i, item ->
                LabToolRow(
                    label = labels[item] ?: item,
                    toggle = item !in picked,
                    requester = if (i == 0) first else null,
                ) { picked = if (item in picked) picked - item else picked + item }
            }
            if (onUseContainer != null) {
                LabButton(L("Use container setting"), modifier = Modifier.fillMaxWidth().padding(top = 4.dp), onClick = onUseContainer)
            }
            LabDialogButtons {
                LabButton(L("Cancel"), onClick = onDismiss)
                LabButton(L("Save"), primary = true, onClick = { onDone(picked) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                LabHint("A", L("Switch"))
                Spacer(Modifier.width(8.dp))
                LabHint("B", L("Close"))
            }
        }
    }
    if (helpOpen && help != null) LabNote(title = title, body = help, onDismiss = { helpOpen = false })
}
