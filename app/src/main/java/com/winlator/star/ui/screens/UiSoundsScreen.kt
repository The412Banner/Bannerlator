package com.winlator.star.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.star.sound.DeckThemesStore
import com.winlator.star.sound.UiCue
import com.winlator.star.sound.UiSoundPacks
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckShape
import com.winlator.star.ui.simple.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sound packs: the installed ones (pick, preview, remove), the DeckThemes store (search, install) and
 * installing an Audio Loader pack zip from storage.
 */
@Composable
fun UiSoundsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val state by UiSoundPacks.state.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var order by remember { mutableStateOf(DeckThemesStore.ORDERS.first()) }
    val results = remember { mutableStateListOf<DeckThemesStore.Item>() }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var storeError by remember { mutableStateOf<String?>(null) }
    var searchTick by remember { mutableIntStateOf(0) }

    fun load(next: Int) {
        if (loading) return
        loading = true
        storeError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { DeckThemesStore.search(query, order, next) } }
            r.onSuccess { p ->
                if (next == 1) results.clear()
                results.addAll(p.items)
                total = p.total
                page = next
            }.onFailure { storeError = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }
    LaunchedEffect(searchTick, order) { load(1) }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) UiSoundPacks.importZip(context, uri)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Sound packs"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.deckFocusRing(deckShape(20.dp))) {
                        Icon(Icons.Filled.Close, contentDescription = L("Close"))
                    }
                }
            }
            if (state.busy != null) {
                item {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }
            state.error?.let { e -> item { Text(e, color = cs.error, fontSize = 13.sp) } }

            item { SectionTitle(L("Installed")) }
            val steamRow = state.packs.firstOrNull { it.id == UiSoundPacks.STEAM_ID }
            item {
                PackRow(
                    name = L("Steam Big Picture"),
                    detail = if (steamRow == null) L("Not downloaded") else null,
                    selected = state.selected == UiSoundPacks.STEAM_ID,
                    onSelect = { UiSoundPacks.select(context, UiSoundPacks.STEAM_ID) },
                    onPreview = if (steamRow != null) ({ previewPack(context, UiSoundPacks.STEAM_ID) }) else null,
                    onDelete = if (steamRow != null) ({ UiSoundPacks.delete(context, UiSoundPacks.STEAM_ID) }) else null,
                    onDownload = if (steamRow == null && state.busy == null) ({ UiSoundPacks.installSteam(context) }) else null,
                )
            }
            items(state.packs.filter { it.id != UiSoundPacks.STEAM_ID }, key = { it.id }) { p ->
                PackRow(
                    name = p.name,
                    detail = listOf(p.author, p.version).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    selected = state.selected == p.id,
                    onSelect = { UiSoundPacks.select(context, p.id) },
                    onPreview = { previewPack(context, p.id) },
                    onDelete = { UiSoundPacks.delete(context, p.id) },
                    onDownload = null,
                )
            }
            item {
                OutlinedButton(
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = state.busy == null,
                    modifier = Modifier.padding(vertical = 4.dp).deckFocusRing(deckShape(20.dp)),
                ) { Text(L("Install a pack zip")) }
            }

            item { SectionTitle("DeckThemes") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(L("Search")) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchTick++ }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { searchTick++ }, modifier = Modifier.deckFocusRing(deckShape(20.dp))) {
                        Icon(Icons.Filled.Search, contentDescription = L("Search"))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    DeckThemesStore.ORDERS.forEach { o ->
                        val on = o == order
                        Text(
                            o,
                            fontSize = 12.sp,
                            color = if (on) cs.onPrimary else cs.onSurface,
                            modifier = Modifier
                                .deckFocusRing(deckShape(14.dp))
                                .clip(deckShape(14.dp))
                                .background(if (on) cs.primary else cs.surfaceVariant)
                                .clickable { if (!on) order = o }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            storeError?.let { e -> item { Text(e, color = cs.error, fontSize = 13.sp) } }
            items(results, key = { "store-" + it.id }) { item ->
                val installed = state.packs.any { it.storeId == item.id }
                StoreRow(item, installed, busy = state.busy == "store-" + item.id) {
                    if (installed) UiSoundPacks.select(context, "store-" + item.id)
                    else if (state.busy == null) UiSoundPacks.installStore(context, item)
                }
            }
            if (results.size < total) {
                item {
                    OutlinedButton(
                        onClick = { load(page + 1) },
                        enabled = !loading,
                        modifier = Modifier.padding(vertical = 6.dp).deckFocusRing(deckShape(20.dp)),
                    ) { Text(L("More")) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun previewPack(context: android.content.Context, id: String) {
    if (UiSoundPacks.state.value.selected != id) UiSoundPacks.select(context, id)
    UiSoundPacks.preview(UiCue.SELECT)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun PackRow(
    name: String,
    detail: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDownload: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(deckShape(12.dp), scaleTo = 1.01f)
            .clip(deckShape(12.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else cs.surface)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = cs.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload, modifier = Modifier.deckFocusRing(deckShape(20.dp))) {
                Icon(Icons.Filled.Download, contentDescription = L("Download"))
            }
        }
        if (onPreview != null) {
            IconButton(onClick = onPreview, modifier = Modifier.deckFocusRing(deckShape(20.dp))) {
                Icon(Icons.Filled.PlayArrow, contentDescription = L("Preview"))
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.deckFocusRing(deckShape(20.dp))) {
                Icon(Icons.Filled.Delete, contentDescription = L("Remove"))
            }
        }
    }
}

@Composable
private fun StoreRow(item: DeckThemesStore.Item, installed: Boolean, busy: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(deckShape(12.dp), scaleTo = 1.01f)
            .clip(deckShape(12.dp))
            .background(cs.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(deckShape(8.dp)).background(cs.surfaceVariant),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = cs.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.author, "${item.downloads} ↓", "★ ${item.stars}").filter { it.isNotBlank() }.joinToString(" · "),
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            busy -> Text("…", color = cs.primary)
            installed -> Icon(Icons.Filled.Check, contentDescription = L("Installed"), tint = cs.primary)
            else -> Icon(Icons.Filled.Download, contentDescription = L("Install"), tint = cs.onSurfaceVariant)
        }
    }
}
