package com.winlator.star.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.DeckStore
import com.winlator.star.ui.deck.LibrarySort
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.gameSortKeyOf
import com.winlator.star.ui.deck.isSignedIn
import com.winlator.star.ui.deck.playtimeLabel
import com.winlator.star.ui.deck.readPlayStats
import com.winlator.star.ui.deck.sortGames
import com.winlator.star.ui.deck.storeLabelOf
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.GamesScreenRequests
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.rememberShortcutLauncher
import kotlinx.coroutines.delay

/** Lab's Library: every game as a cover, most recently played first. A opens its page, X its menu. */
@Composable
internal fun LabLibraryPage(onNavigate: (String) -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    val shortcuts by vm.shortcuts.collectAsState(initial = SimpleSession.lastShortcuts)
    val launcher = rememberShortcutLauncher()
    val actions = rememberShortcutActions(vm)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { vm.refresh(); resumeTick++ } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val stats = remember(shortcuts, resumeTick) { readPlayStats(context, shortcuts) }
    val games = remember(shortcuts, stats) { sortGames(shortcuts, LibrarySort.RECENT) { gameSortKeyOf(it, stats) } }
    var menuFor by remember { mutableStateOf<Shortcut?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(games.isNotEmpty()) { delay(120); runCatching { first.requestFocus() } }
    LaunchedEffect(games) {
        if (games.none { it.file.path == LabArt.selected?.file?.path }) LabArt.selected = games.firstOrNull()
        games.forEach { LabArt.resolve(context, it) }
    }
    val deckActions = LocalDeckActions.current
    DisposableEffect(deckActions) {
        val legend = listOf("B" to L("Back"), "A" to L("Open"), "X" to L("Game menu"), "L1 R1" to L("Section"))
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text(L("Library"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(L("%1\$s games · last played first", games.size), color = SimpleMuted, fontSize = 13.sp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(labCardMin(LabArt.cards, LabArt.cardSize)),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(games, key = { it.file.path }) { s ->
                val i = games.indexOf(s)
                // The cover the controller rests on is the game whose art shows behind the page.
                LabGridCard(
                    s,
                    Modifier.fillMaxWidth()
                        .labTile(onClick = { onNavigate(LabRoutes.game(s.file.path)) }, onMenu = { menuFor = s }, requester = if (i == 0) first else null, onFocus = { LabArt.selected = s }),
                )
            }
            item(key = "add") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().aspectRatio(if (LabArt.cards == LabArt.WIDE) 460f / 215f else if (LabArt.cards == LabArt.ICONS) 1f else 3f / 4f).labPanel()
                        .labTile(onClick = { GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.ADD_GAME) }, requester = if (games.isEmpty()) first else null),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Text(L("Add a game"), color = SimpleMuted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    menuFor?.let { s ->
        LabGameMenu(
            shortcut = s,
            subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats[s.file.path]?.ms ?: 0L),
            onDismiss = { menuFor = null },
            onPlay = { menuFor = null; launcher.requestLaunch(s) },
            onAction = { menuFor = null; actions.perform(it, s) },
            onContainer = { menuFor = null; onNavigate(DeckSettingsRoutes.container(s.container.id)) },
            onDetails = { tab -> menuFor = null; onNavigate(LabRoutes.details(s.file.path, tab)) },
        )
    }
    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher, onRemove = { vm.remove(it, context) })
}

/** Lab's Stores: each store with its logo, whether you are signed in and how many of its games you have. A opens it. */
@Composable
internal fun LabStoresPage(onLaunchStore: (Screen) -> Unit, onBack: () -> Unit, vm: ShortcutsViewModel = viewModel()) {
    val context = LocalContext.current
    val shortcuts by vm.shortcuts.collectAsState(initial = SimpleSession.lastShortcuts)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val signedIn = remember(resumeTick) { DeckStore.entries.associateWith { isSignedIn(context, it) } }
    val entries = DeckStore.entries.map { st ->
        val owned = shortcuts.count(st.owns)
        val (logo, brand, white) = storeLogo(st)
        MenuEntry(
            key = st.name,
            title = st.label,
            subtitle = if (signedIn[st] == true) (if (owned == 1) L("Signed in · 1 game") else L("Signed in · %1\$s games", owned)) else L("Not signed in"),
            trailing = if (signedIn[st] == true) L("Open ›") else L("Sign in ›"),
            icon = Icons.Filled.Storefront,
            logo = logo,
            brand = brand,
            logoWhite = white,
            onA = { onLaunchStore(st.screen) },
        )
    }
    SimpleMenuPage(
        overline = L("Stores"),
        title = L("Your stores"),
        sections = listOf(MenuSection("stores", L("Stores"), Icons.Filled.Storefront, entries)),
        onBack = onBack,
    )
}
