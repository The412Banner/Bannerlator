package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.core.GameMods
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.GameModsSetting
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.delay

/** The game's mods as the Classic Mods button shows them: the ones switched on, then the program started with the game. */
internal fun GameModsSetting.enabledNames(): String =
    (mods.filter { on[it.id] == true }.map { it.name } + listOfNotNull(startWith.trim().substringAfterLast('\\').ifEmpty { null })).joinToString()

/**
 * Lab's Mods dialog on the Classic GameModsSetting: a switch per mod this game's EXE can take (switching one on downloads
 * it, off removes the file placed in the game folder), Special K's DLL name, and the program started with the game.
 * [onChange] runs after every change, so the page saves it at once.
 */
@Composable
internal fun LabModsDialog(setting: GameModsSetting, onChange: () -> Unit, onDismiss: () -> Unit) {
    val filesDir = LocalContext.current.filesDir
    val first = remember { FocusRequester() }
    var editStart by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(L("Mods"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            setting.mods.forEachIndexed { i, mod ->
                val on = setting.on[mod.id] == true
                val state = setting.status[mod.id].orEmpty()
                ModRow(
                    mod.name,
                    when (state) { "ready" -> L("ready"); "download failed" -> L("download failed"); else -> state },
                    if (i == 0) first else null,
                    onClick = {
                        setting.on[mod.id] = !on
                        if (!on) setting.download(filesDir, mod) else setting.removeNow(mod)
                        onChange()
                    },
                    switch = on,
                )
                if (mod === GameMods.SPECIAL_K && on) {
                    val auto = GameMods.targetDll(mod, "", setting.imports, setting.machine)
                    ModRow(L("DLL"), if (setting.skDll.isEmpty()) L("%1\$s (auto)", auto) else setting.skDll, null, onClick = { setting.nextSkDll(); onChange() })
                }
            }
            ModRow(
                L("Start with the game"),
                setting.startWith.trim().ifEmpty { "—" },
                if (setting.mods.isEmpty()) first else null,
                onClick = { editStart = true },
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                LabButton(L("Done"), onClick = onDismiss)
            }
        }
    }

    if (editStart) {
        var v by remember { mutableStateOf(setting.startWith) }
        OutlinedAlertDialog(
            onDismissRequest = { editStart = false },
            confirmButton = { TextButton(onClick = { editStart = false; setting.startWith = v.trim(); onChange() }) { Text(L("Save")) } },
            dismissButton = { TextButton(onClick = { editStart = false }) { Text(L("Cancel")) } },
            title = { Text(L("Start with the game")) },
            text = {
                OutlinedTextField(
                    value = v, onValueChange = { v = it }, singleLine = true,
                    placeholder = { Text("F:\\Trainers\\Trainer.exe") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

/** One row of the Mods dialog: its label, its value on the right and, for a mod, its switch. */
@Composable
private fun ModRow(label: String, value: String?, requester: FocusRequester?, onClick: () -> Unit, switch: Boolean? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).labTile(onClick = onClick, requester = requester).padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, color = SimpleStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!value.isNullOrEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(value, color = SimpleMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        }
        if (switch != null) {
            Spacer(Modifier.width(12.dp))
            LabSwitch(switch)
        }
    }
}
