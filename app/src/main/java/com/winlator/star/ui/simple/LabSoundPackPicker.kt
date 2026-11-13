package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.sound.DialogSound
import com.winlator.star.sound.UiSoundPacks
import com.winlator.star.sound.UiSounds
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.delay

private class AvailablePack(val id: String, val name: String, val author: String)

/**
 * The Sound pack list: installed packs first (A on one: Apply or Delete; a deleted pack goes back to the
 * available list), then every pack that can be downloaded (A downloads it and uses it; the list stays open and
 * shows the download on its row). Each heading shows how many packs it holds.
 */
@Composable
internal fun SoundPackPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val state by UiSoundPacks.state.collectAsState()
    val installed = state.packs
    val fromStore = installed.mapNotNull { it.storeId }.toSet()
    val available = buildList {
        if (!state.steamInstalled) add(AvailablePack(UiSoundPacks.STEAM_ID, L("Steam Big Picture"), "Valve"))
        state.store.filter { it.id !in fromStore }.forEach { add(AvailablePack("store-" + it.id, it.name, it.author)) }
    }
    var actionsFor by remember { mutableStateOf<UiSoundPacks.Installed?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(L("Sound pack"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                item { Heading(L("Installed"), installed.size) }
                items(installed, key = { "i-" + it.id }) { p ->
                    val isFirst = p === installed.firstOrNull()
                    PackLine(
                        name = p.name,
                        sub = listOf(p.author, p.version).filter { it.isNotBlank() }.joinToString(" · "),
                        current = p.id == state.selected,
                        mark = null,
                        accent = accent,
                        modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                    ) { actionsFor = p }
                }
                item { Heading(L("Available"), available.size) }
                items(available, key = { "a-" + it.id }) { a ->
                    val isFirst = installed.isEmpty() && a === available.firstOrNull()
                    val busy = state.busy == a.id
                    PackLine(
                        name = a.name,
                        sub = if (busy) L("Downloading %1\$s", "${(state.progress * 100).toInt()} %") else a.author,
                        current = false,
                        mark = if (busy) "…" else "↓",
                        accent = accent,
                        modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                    ) { UiSoundPacks.select(context, a.id) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SimpleGlyph("A"); Spacer(Modifier.width(4.dp)); Text(L("Select"), color = SimpleMuted, fontSize = 12.sp)
                Spacer(Modifier.width(14.dp))
                SimpleGlyph("B"); Spacer(Modifier.width(4.dp)); Text(L("Close"), color = SimpleMuted, fontSize = 12.sp)
            }
        }
    }

    actionsFor?.let { p ->
        SimpleActionSheet(
            title = p.name,
            actions = listOf(
                MenuAction(L("Apply"), { UiSoundPacks.select(context, p.id) }),
                MenuAction(L("Delete"), { UiSoundPacks.delete(context, p.id) }, danger = true),
            ),
            onDismiss = { actionsFor = null },
        ) { a ->
            actionsFor = null
            a.run()
        }
    }
}

@Composable
private fun Heading(title: String, count: Int) {
    Text(
        "$title · $count",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun PackLine(
    name: String,
    sub: String,
    current: Boolean,
    mark: String?,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .onFocusChanged {
                if (it.isFocused && !f) UiSounds.focusGained()
                f = it.isFocused
            }
            .then(if (f) Modifier.background(accent.copy(alpha = 0.3f), SimpleShape).drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = SimpleStrong, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = SimpleDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Text("✓", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        else if (mark != null) Text(mark, color = SimpleMuted, fontSize = 16.sp)
    }
}
