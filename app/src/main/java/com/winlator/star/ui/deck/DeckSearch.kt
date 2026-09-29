package com.winlator.star.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.screens.ShortcutsViewModel

/** A place the search can jump to: a Deck route, a store, or the About dialog. */
private data class SearchDestination(
    val title: String,
    val path: String,
    val icon: ImageVector,
    val keywords: String,
    val route: String? = null,
    val store: Screen? = null,
    val about: Boolean = false,
)

/** Everything the search lists besides games. Keywords widen the match beyond the title. */
private val DESTINATIONS = listOf(
    SearchDestination("Home", "Library", Icons.Filled.SportsEsports, "library games shelf continue playing", route = DeckRoutes.HOME),
    SearchDestination("All games", "Library › Classic list", Icons.Filled.SportsEsports, "shortcuts add game import folder", route = Screen.Games.route),
    SearchDestination("Stores", "Steam, Epic, GOG, Amazon", Icons.Filled.Storefront, "store sign in library download", route = DeckRoutes.STORES),
    SearchDestination("Steam", "Stores › Steam", Icons.Filled.Storefront, "steam valve store friends", store = Screen.Steam),
    SearchDestination("Epic Games", "Stores › Epic Games", Icons.Filled.Storefront, "epic store free games", store = Screen.Epic),
    SearchDestination("GOG", "Stores › GOG", Icons.Filled.Storefront, "gog galaxy store", store = Screen.Gog),
    SearchDestination("Amazon Games", "Stores › Amazon Games", Icons.Filled.Storefront, "amazon prime store", store = Screen.Amazon),
    SearchDestination("Containers", "Wine prefixes and their settings", Icons.Filled.Inventory2, "container wine prefix drive c new container", route = Screen.Containers.route),
    SearchDestination("Components", "Wine, Proton, DXVK, VKD3D, Box64, FEX, drivers", Icons.Filled.Extension, "components contents dxvk vkd3d proton wine box64 fex fexcore wowbox64 d7vk wcp driver turnip install", route = DeckRoutes.COMPONENTS),
    SearchDestination("Controls", "On-screen profiles, bindings, controller test", Icons.Filled.SportsEsports, "controls input controller gamepad bindings on-screen touch profile test", route = DeckRoutes.CONTROLS),
    SearchDestination("Tools", "Files, saves, logs", Icons.Filled.Build, "tools", route = DeckRoutes.TOOLS),
    SearchDestination("File Manager", "Tools › Files", Icons.Filled.FolderOpen, "files folders browse unpack archive 7z zip", route = Screen.FileManager.route),
    SearchDestination("Save Manager", "Tools › Saves", Icons.Filled.Save, "saves backup restore cloud sync", route = Screen.SaveManager.route),
    SearchDestination("Wrappers", "Tools › Graphics wrappers", Icons.Filled.Layers, "wrapper vulkan graphics", route = Screen.Wrappers.route),
    SearchDestination("Adrenotools", "Tools › GPU drivers", Icons.Filled.Memory, "gpu driver adreno turnip mesa", route = Screen.AdrenoTools.route),
    SearchDestination("Settings", "App settings", Icons.Filled.Settings, "settings preferences performance network storage logs advanced", route = DeckSettingsRoutes.APP),
    SearchDestination("Appearance", "Settings › Appearance", Icons.Filled.Palette, "appearance theme accent dark light interface style deck classic size font", route = Screen.Appearance.route),
    SearchDestination("Logs", "Tools › Logs", Icons.Filled.Description, "logs log manager debug wine dxvk", route = DeckRoutes.TOOLS),
    SearchDestination("About", "Version, updates and credits", Icons.Filled.Info, "about version update credits license", about = true),
)

/**
 * The Deck's search overlay (Y, or the magnifier in the top bar). Matches games by title and the
 * app's screens and tools by name and keywords; picking a game opens its game page.
 */
@Composable
internal fun DeckSearchOverlay(
    onDismiss: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
    onLaunchStore: (Screen) -> Unit,
    onAbout: () -> Unit,
    vm: ShortcutsViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val stats = remember(shortcuts) { readPlayStats(context, shortcuts) }
    val games = remember(q, shortcuts) {
        if (q.isEmpty()) continuePlayingOrder(shortcuts, stats).take(6)
        else shortcuts.filter { it.name.lowercase().contains(q) || storeLabelOf(it).lowercase().contains(q) }
            .sortedWith(compareBy<Shortcut> { !it.name.lowercase().startsWith(q) }.thenBy { it.name.lowercase() })
    }
    val places = remember(q) {
        if (q.isEmpty()) emptyList()
        else DESTINATIONS.filter { d -> q.split(' ').filter { it.isNotBlank() }.all { w -> d.title.lowercase().contains(w) || d.keywords.contains(w) } }
    }
    val fieldRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { fieldRequester.requestFocus() }
    }
    val compact = deckCompact()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss, indication = null, interactionSource = remember { MutableInteractionSource() })
                .padding(horizontal = if (compact) 12.dp else 48.dp, vertical = if (compact) 16.dp else 40.dp),
        ) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(cs.surfaceContainerHigh)
                    .border(1.dp, deckLine(), shape)
                    .clickable(onClick = {}, indication = null, interactionSource = remember { MutableInteractionSource() })
                    .padding(if (compact) 16.dp else 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Search everything",
                        color = cs.onSurface,
                        fontFamily = SoraFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 20.sp else 24.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .deckFocusRing(RoundedCornerShape(14.dp), scaleTo = 1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onDismiss)
                            .size(44.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurface)
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Games, screens and tools") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        games.firstOrNull()?.let { onOpenGame(it.file.path) } ?: places.firstOrNull()?.let { openDestination(it, onOpenRoute, onLaunchStore, onAbout) }
                    }),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = cs.primary, unfocusedBorderColor = deckLine()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                        .focusRequester(fieldRequester),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                    if (games.isNotEmpty()) {
                        item(key = "h-games") {
                            DeckSectionLabel(if (q.isEmpty()) "Recently played" else "Games · ${games.size}")
                        }
                        items(games, key = { "g:" + it.file.path }) { s ->
                            SearchResultRow(
                                title = s.name,
                                subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats[s.file.path]?.ms ?: 0L),
                                icon = Icons.Filled.SportsEsports,
                                cover = s,
                                onClick = { onOpenGame(s.file.path) },
                            )
                        }
                    }
                    if (places.isNotEmpty()) {
                        item(key = "h-places") { DeckSectionLabel("Screens and tools · ${places.size}", Modifier.padding(top = 8.dp)) }
                        items(places, key = { "d:" + it.title }) { d ->
                            SearchResultRow(d.title, d.path, d.icon, null) { openDestination(d, onOpenRoute, onLaunchStore, onAbout) }
                        }
                    }
                    if (q.isNotEmpty() && games.isEmpty() && places.isEmpty()) {
                        item(key = "none") {
                            Text(
                                "Nothing matches \"${query.trim()}\".",
                                color = cs.onSurfaceVariant,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openDestination(d: SearchDestination, onOpenRoute: (String) -> Unit, onLaunchStore: (Screen) -> Unit, onAbout: () -> Unit) {
    when {
        d.about -> onAbout()
        d.store != null -> onLaunchStore(d.store)
        d.route != null -> onOpenRoute(d.route)
    }
}

@Composable
private fun SearchResultRow(title: String, subtitle: String, icon: ImageVector, cover: Shortcut?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .then(if (focused) Modifier.background(cs.primary.copy(alpha = 0.14f)).border(2.dp, cs.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = 0.14f)),
        ) {
            val bmp = cover?.icon
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (focused) cs.primary else cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
