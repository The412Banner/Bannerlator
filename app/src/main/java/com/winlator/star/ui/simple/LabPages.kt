package com.winlator.star.ui.simple

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewStream
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

/**
 * Lab's Library: every game as a card, last played first or as picked (Name A–Z / Z–A, Container), one store or all,
 * in the card style of Appearance. A opens a game's page, X its menu. Select picks games to remove together.
 */
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
    remember { LabLibrary.init(context) }
    val sort = LabLibrary.sort
    // A store with no games left is not offered; a remembered one shows every game until it has some again.
    val stores = remember(shortcuts) { LabLibrary.FILTERS.filter { f -> shortcuts.any(f.third) }.map { it.first } }
    val filter = LabLibrary.filter.takeIf { it in stores } ?: ""
    val stats = remember(shortcuts, resumeTick) { readPlayStats(context, shortcuts) }
    LaunchedEffect(shortcuts) { LabTitles.refresh(context, shortcuts) }
    val games = remember(shortcuts, stats, sort, filter) {
        val shown = LabLibrary.filterOf(filter)?.let { f -> shortcuts.filter(f) } ?: shortcuts
        sortGames(shown, sort) { gameSortKeyOf(it, stats) }
    }
    var menuFor by remember { mutableStateOf<Shortcut?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    // Picking games to remove: keyed by file path, as Classic's (a refresh makes new Shortcut objects).
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    fun endPicking() { picking = false; picked = emptySet() }
    BackHandler(enabled = picking) { endPicking() }
    val first = remember { FocusRequester() }
    LaunchedEffect(games.isNotEmpty()) { delay(120); runCatching { first.requestFocus() } }
    LaunchedEffect(games) {
        if (games.none { it.file.path == LabArt.selected?.file?.path }) LabArt.selected = games.firstOrNull()
        games.forEach { LabArt.resolve(context, it) }
        // Games that left the library (removed elsewhere) drop out of the pick.
        if (picked.isNotEmpty()) picked = picked.filter { p -> shortcuts.any { it.file.path == p } }.toSet()
    }
    val deckActions = LocalDeckActions.current
    DisposableEffect(deckActions, picking) {
        val legend = if (picking) listOf("B" to L("Done"), "A" to L("Pick"), "X" to L("Game menu"))
        else listOf("B" to L("Back"), "A" to L("Open"), "X" to L("Game menu"), "L1 R1" to L("Section"))
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val title: @Composable () -> Unit = {
                Column {
                    Text(L("Library"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text(
                        if (picking) L("%1\$s picked", picked.size) else if (games.size == 1) L("1 game") else L("%1\$s games", games.size),
                        color = SimpleMuted, fontSize = 13.sp,
                    )
                }
            }
            val buttons: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (picking) {
                        LabButton(L("Remove"), icon = Icons.Filled.Delete, danger = true) {
                            if (picked.isEmpty()) toast(context, L("Pick the games to remove first")) else confirmRemove = true
                        }
                        LabButton(L("Done"), icon = Icons.Filled.Close) { endPicking() }
                    } else {
                        LabButton(LabLibrary.sortLabel(sort), icon = Icons.Filled.SwapVert) { sheet = "sort" }
                        LabButton(LabLibrary.filterLabel(filter), icon = Icons.Filled.FilterList) { sheet = "filter" }
                        LabButton("", icon = when (LabArt.cards) { LabArt.WIDE -> Icons.Filled.ViewStream; LabArt.ICONS -> Icons.Filled.Apps; else -> Icons.Filled.GridView }) { sheet = "view" }
                        LabButton(L("Select"), icon = Icons.Filled.Checklist) { picking = true }
                    }
                }
            }
            // Wide: the buttons beside the title; narrow: under it.
            if (maxWidth >= 560.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { title() }
                    buttons()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { title(); buttons() }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(labCardMin(LabArt.cards, LabArt.cardSize)),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(games, key = { it.file.path }) { s ->
                val i = games.indexOf(s)
                val path = s.file.path
                // The cover the controller rests on is the game whose art shows behind the page.
                LabGridCard(
                    s,
                    Modifier.fillMaxWidth()
                        .labTile(
                            onClick = {
                                if (picking) picked = if (path in picked) picked - path else picked + path
                                else onNavigate(LabRoutes.game(path))
                            },
                            onMenu = { menuFor = s },
                            requester = if (i == 0) first else null,
                            onFocus = { LabArt.selected = s },
                        ),
                    line = labCardLine(s),
                    picked = if (picking) path in picked else null,
                )
            }
            if (!picking) item(key = "add") {
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
    fun mark(label: String, on: Boolean) = if (on) "$label  ✓" else label
    when (sheet) {
        "sort" -> SimpleActionSheet(
            L("Sort"),
            LabLibrary.SORTS.map { o -> MenuAction(mark(LabLibrary.sortLabel(o), o == sort), { LabLibrary.chooseSort(o) }) },
            onDismiss = { sheet = null },
        ) { a -> sheet = null; a.run() }
        "filter" -> SimpleActionSheet(
            L("Store"),
            listOf(MenuAction(mark(L("All"), filter.isEmpty()), { LabLibrary.chooseFilter("") })) +
                stores.map { v -> MenuAction(mark(LabLibrary.filterLabel(v), v == filter), { LabLibrary.chooseFilter(v) }) },
            onDismiss = { sheet = null },
        ) { a -> sheet = null; a.run() }
        // Appearance › Library cards and Card size, one press away.
        "view" -> SimpleActionSheet(
            L("Library cards"),
            LabArt.CARDS.map { (v, l) -> MenuAction(mark(L(l), LabArt.cards == v), { LabArt.chooseCards(v) }) } +
                LabArt.SIZES.map { (v, l) -> MenuAction(mark(L("Card size · %1\$s", L(l)), LabArt.cardSize == v), { LabArt.chooseCardSize(v) }) },
            onDismiss = { sheet = null },
        ) { a -> sheet = null; a.run() }
    }
    if (confirmRemove) {
        // As Classic's Remove selected: one question for all of them, then vm.remove for each (only the shortcuts go).
        val targets = shortcuts.filter { it.file.path in picked }
        SimpleActionSheet(
            if (targets.size == 1) L("Remove 1 game?") else L("Remove %1\$s games?", targets.size),
            listOf(
                MenuAction(L("Remove"), {
                    val ok = targets.count { vm.remove(it, context) }
                    toast(
                        context,
                        when {
                            ok != targets.size -> L("Removed %1\$s of %2\$s; the rest could not be deleted", ok, targets.size)
                            ok == 1 -> L("Removed 1 game")
                            else -> L("Removed %1\$s games", ok)
                        },
                    )
                    endPicking()
                }, danger = true),
                MenuAction(L("Cancel"), {}),
            ),
            onDismiss = { confirmRemove = false },
            message = if (targets.size == 1) LabTitles.of(targets.first()) else L("Only the shortcuts go; the games' files stay."),
        ) { a -> confirmRemove = false; a.run() }
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
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { vm.refresh(); resumeTick++ } }
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
