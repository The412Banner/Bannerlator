package com.winlator.star.ui.deck

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import com.winlator.star.container.Shortcut
import com.winlator.star.store.SteamFriendsAction
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.rememberShortcutLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How the Deck library lays out games. Wall and console live in Big Picture, which the toolbar links to. */
private enum class LibraryMode(val label: String, val icon: ImageVector) {
    SHELF("Shelf", Icons.Filled.ViewDay),
    GRID("Grid", Icons.Filled.GridView),
    COMPACT("Compact", Icons.Filled.GridOn),
    LIST("List", Icons.AutoMirrored.Filled.List),
}

private enum class LibrarySort(val label: String) {
    NAME("Name A–Z"),
    NAME_DESC("Name Z–A"),
    RECENT("Last played"),
    PLAYTIME("Most played"),
    CONTAINER("Container"),
}

private enum class PlayedFilter(val label: String) { ANY("Any"), PLAYED("Played"), NEVER("Never played") }

private const val PREF_MODE = "deck_library_mode"
private const val PREF_SORT = "deck_library_sort"

/** Filter & sort state. Mode and sort persist; the filters last for the session. */
private data class LibraryFilter(
    val stores: Set<String> = emptySet(),
    val played: PlayedFilter = PlayedFilter.ANY,
    val sort: LibrarySort = LibrarySort.NAME,
) {
    /** Filters in use (sort doesn't count), for the "Filters · N" label. */
    val activeCount: Int get() = (if (stores.isNotEmpty()) 1 else 0) + (if (played != PlayedFilter.ANY) 1 else 0)
}

private fun applyFilter(list: List<Shortcut>, f: LibraryFilter, stats: Map<String, PlayStats>): List<Shortcut> {
    val filtered = list.filter { s ->
        (f.stores.isEmpty() || storeLabelOf(s) in f.stores) &&
            when (f.played) {
                PlayedFilter.ANY -> true
                PlayedFilter.PLAYED -> (stats[s.file.path]?.ms ?: 0L) > 0L || (stats[s.file.path]?.lastPlayed ?: 0L) > 0L
                PlayedFilter.NEVER -> (stats[s.file.path]?.ms ?: 0L) <= 0L && (stats[s.file.path]?.lastPlayed ?: 0L) <= 0L
            }
    }
    return when (f.sort) {
        LibrarySort.NAME -> filtered.sortedBy { it.name.lowercase() }
        LibrarySort.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
        LibrarySort.RECENT -> filtered.sortedWith(compareByDescending<Shortcut> { stats[it.file.path]?.lastPlayed ?: 0L }.thenBy { it.name.lowercase() })
        LibrarySort.PLAYTIME -> filtered.sortedWith(compareByDescending<Shortcut> { stats[it.file.path]?.ms ?: 0L }.thenBy { it.name.lowercase() })
        LibrarySort.CONTAINER -> filtered.sortedWith(compareBy<Shortcut> { it.container.name.lowercase() }.thenBy { it.name.lowercase() })
    }
}

/**
 * Two backdrop tints from a cover: the average of its top-right and bottom-left quarters, pushed to a
 * readable saturation and brightness. Runs off the main thread; null when the bitmap can't be read.
 */
internal fun coverTints(bmp: Bitmap): Pair<Color, Color>? = runCatching {
    val small = Bitmap.createScaledBitmap(bmp, 12, 12, true)
    fun avg(x0: Int, y0: Int): Color {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (x in x0 until x0 + 6) for (y in y0 until y0 + 6) {
            val p = small.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r / n, g / n, b / n, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = 0.55f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    val pair = avg(6, 0) to avg(0, 6)
    if (small !== bmp) small.recycle()
    pair
}.getOrNull()

/**
 * Deck home and library: a hero for the focused game plus shelves, or the whole library as a grid, a
 * compact grid or a list. Games come from the same ShortcutsViewModel the Games screen uses. A on a
 * card opens the game page; Play goes through the shared ShortcutLauncher; the game menu (X, or a long
 * press) runs the classic ⋮ actions in place through ShortcutActions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeckHome(
    onOpenGame: (Shortcut) -> Unit,
    onAddGame: () -> Unit,
    onBigPicture: () -> Unit,
    onOpenLibrary: () -> Unit,
    vm: ShortcutsViewModel = viewModel(),
) {
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val compact = cfg.screenWidthDp < DECK_COMPACT_WIDTH_DP
    val launcher = rememberShortcutLauncher()
    val actions = rememberShortcutActions(vm)
    // A SteamLite launch that failed inside the container asks for a relaunch.
    // The Deck home is the library this shell lands on, so it picks that up the same way the Games screen does.
    LaunchedEffect(shortcuts) { launcher.resumePendingRelaunch(shortcuts) }

    // Refresh whenever the app resumes, as the Games screen does, so playtime and new games show up.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var mode by remember {
        mutableStateOf(runCatching { LibraryMode.valueOf(prefs.getString(PREF_MODE, null) ?: "") }.getOrDefault(LibraryMode.SHELF))
    }
    var filter by remember {
        mutableStateOf(LibraryFilter(sort = runCatching { LibrarySort.valueOf(prefs.getString(PREF_SORT, null) ?: "") }.getOrDefault(LibrarySort.NAME)))
    }
    var filterOpen by remember { mutableStateOf(false) }

    val stats = remember(shortcuts) { readPlayStats(context, shortcuts) }
    val continuePlaying = remember(shortcuts, stats) { continuePlayingOrder(shortcuts, stats).take(12) }
    val recentlyAdded = remember(shortcuts) {
        shortcuts.sortedByDescending { runCatching { it.file.lastModified() }.getOrDefault(0L) }.take(12)
    }
    val visible = remember(shortcuts, stats, filter) { applyFilter(shortcuts, filter, stats) }
    val storeShelves = remember(visible) {
        STORE_SHELVES.map { (label, test) -> label to visible.filter(test) }
            .filter { (_, list) -> list.isNotEmpty() && list.size < visible.size }
    }

    var focusedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val focused = shortcuts.firstOrNull { it.file.path == focusedPath }
        ?: continuePlaying.firstOrNull()
        ?: shortcuts.firstOrNull()
    var menuFor by remember { mutableStateOf<Shortcut?>(null) }

    // X / Start on this page = the focused game's menu.
    val deckActions = LocalDeckActions.current
    val focusedNow by rememberUpdatedState(focused)
    DisposableEffect(deckActions) {
        val open: () -> Unit = { focusedNow?.let { menuFor = it } }
        deckActions.options.value = open
        deckActions.optionsLabel.value = "Game options"
        onDispose { if (deckActions.options.value === open) deckActions.options.value = null }
    }

    // Backdrop tint follows the focused game's cover.
    val ambient = LocalDeckAmbient.current
    val tints by produceState<Pair<Color, Color>?>(null, focused?.file?.path, focused?.icon) {
        val bmp = focused?.icon
        value = if (bmp == null) null else withContext(Dispatchers.Default) { coverTints(bmp) }
    }
    LaunchedEffect(tints) { ambient.value = tints }
    DisposableEffect(ambient) { onDispose { ambient.value = null } }

    val gutter = if (compact) 16.dp else 24.dp
    val playRequester = remember { FocusRequester() }
    val addRequester = remember { FocusRequester() }
    val hasGames = shortcuts.isNotEmpty()
    // Initial focus: the hero's Play button (or "Add a game" when the library is empty), once it exists.
    DeckEntryFocus(hasGames, mode) {
        if (hasGames && mode == LibraryMode.SHELF) playRequester.requestFocus() else if (!hasGames) addRequester.requestFocus()
    }

    val onFocus: (Shortcut) -> Unit = { focusedPath = it.file.path }
    val onMenu: (Shortcut) -> Unit = { menuFor = it }
    val setMode: (LibraryMode) -> Unit = { m ->
        mode = m
        prefs.edit().putString(PREF_MODE, m.name).apply()
    }
    val toolbar: @Composable () -> Unit = {
        LibraryToolbar(
            mode = mode,
            filterCount = filter.activeCount,
            onMode = setMode,
            onBigPicture = onBigPicture,
            onFilter = { filterOpen = true },
            onAddGame = onAddGame,
            onCommunity = { actions.showCommunityBrowser = true },
        )
    }
    val emptyFiltered: @Composable () -> Unit = {
        FilteredEmpty(onClear = { filter = LibraryFilter(sort = filter.sort) }, onAddGame = onAddGame)
    }

    if (!hasGames) {
        DeckEmptyLibrary(
            modifier = Modifier.fillMaxSize().padding(gutter),
            addRequester = addRequester,
            onAddGame = onAddGame,
        )
    } else when (mode) {
        LibraryMode.SHELF -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = 16.dp),
        ) {
            val heroHeight = (cfg.screenHeightDp * 0.44f).coerceIn(210f, 300f).dp
            focused?.let { s ->
                val st = stats[s.file.path] ?: PlayStats(0L, 0, 0L)
                val last = lastPlayedLabel(context, st.lastPlayed)
                DeckHero(
                    shortcut = s,
                    eyebrow = when {
                        continuePlaying.firstOrNull() == s && (st.lastPlayed > 0L || st.ms > 0L) -> "Continue playing"
                        st.ms > 0L || st.lastPlayed > 0L -> "In your library"
                        else -> "Ready to play"
                    },
                    meta = buildList {
                        add(Icons.Filled.Storefront to storeLabelOf(s))
                        add(Icons.Filled.Schedule to playtimeLabel(st.ms))
                        if (last != null) add(Icons.Filled.CalendarMonth to "Last played ${last.lowercase().takeIf { last == "Today" || last == "Yesterday" } ?: last}")
                        s.container?.name?.takeIf { it.isNotEmpty() }?.let { add(Icons.Filled.Inventory2 to it) }
                    },
                    height = heroHeight,
                    compact = compact,
                    playRequester = playRequester,
                    onPlay = { launcher.requestLaunch(s) },
                    onGamePage = { onOpenGame(s) },
                    onSettings = { actions.perform(GameMenuAction.SETTINGS, s) },
                    onOptions = { menuFor = s },
                )
            }
            Spacer(Modifier.height(14.dp))
            toolbar()
            if (visible.isEmpty()) {
                emptyFiltered()
            } else {
                if (continuePlaying.isNotEmpty() && filter.activeCount == 0) {
                    DeckShelf("Continue playing", "continue", continuePlaying, wide = true, onFocus = onFocus, onOpen = onOpenGame, onMenu = onMenu) { s ->
                        val st = stats[s.file.path]
                        lastPlayedLabel(context, st?.lastPlayed ?: 0L)?.let { "Last played " + (if (it == "Today" || it == "Yesterday") it.lowercase() else it) }
                            ?: playtimeLabel(st?.ms ?: 0L)
                    }
                }
                DeckShelf("Installed", "installed", visible, onFocus = onFocus, onOpen = onOpenGame, onMenu = onMenu) { playtimeLabel(stats[it.file.path]?.ms ?: 0L) }
                storeShelves.forEach { (label, list) ->
                    DeckShelf(label, "store-$label", list, onFocus = onFocus, onOpen = onOpenGame, onMenu = onMenu) { playtimeLabel(stats[it.file.path]?.ms ?: 0L) }
                }
                if (shortcuts.size > 1 && filter.activeCount == 0) {
                    DeckShelf("Recently added", "recent", recentlyAdded, onFocus = onFocus, onOpen = onOpenGame, onMenu = onMenu) { storeLabelOf(it) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        LibraryMode.GRID, LibraryMode.COMPACT -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (mode == LibraryMode.COMPACT) (if (compact) 96.dp else 128.dp) else (if (compact) 140.dp else 180.dp)),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(if (mode == LibraryMode.COMPACT) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (mode == LibraryMode.COMPACT) 14.dp else 18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { toolbar() }
            if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { emptyFiltered() }
            gridItems(visible, key = { "grid:" + it.file.path }) { s ->
                DeckGameCard(
                    shortcut = s,
                    subtitle = if (mode == LibraryMode.COMPACT) null else playtimeLabel(stats[s.file.path]?.ms ?: 0L),
                    width = null,
                    onFocus = { onFocus(s) },
                    onClick = { onOpenGame(s) },
                    onLongClick = { onMenu(s) },
                )
            }
        }
        LibraryMode.LIST -> LazyColumn(
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "toolbar") { toolbar() }
            if (visible.isEmpty()) item(key = "empty") { emptyFiltered() }
            items(visible, key = { "list:" + it.file.path }) { s ->
                val st = stats[s.file.path] ?: PlayStats(0L, 0, 0L)
                DeckListRow(
                    shortcut = s,
                    subtitle = listOfNotNull(storeLabelOf(s), playtimeLabel(st.ms), lastPlayedLabel(context, st.lastPlayed)).joinToString(" · "),
                    compact = compact,
                    onFocus = { onFocus(s) },
                    onOpen = { onOpenGame(s) },
                    onPlay = { launcher.requestLaunch(s) },
                    onMenu = { onMenu(s) },
                )
            }
        }
    }

    if (filterOpen) {
        LibraryFilterDialog(
            filter = filter,
            shown = visible.size,
            total = shortcuts.size,
            onChange = { f ->
                filter = f
                prefs.edit().putString(PREF_SORT, f.sort.name).apply()
            },
            onDismiss = { filterOpen = false },
        )
    }

    menuFor?.let { s ->
        DeckGameMenuSheet(
            shortcut = s,
            subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats[s.file.path]?.ms ?: 0L),
            onDismiss = { menuFor = null },
            onPlay = { menuFor = null; launcher.requestLaunch(s) },
            onAction = { a -> menuFor = null; actions.perform(a, s) },
            // Container settings go through the game page, which knows how to reach the container editor.
            onContainer = { menuFor = null; onOpenGame(s) },
        )
    }

    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher, onRemove = { vm.remove(it, context) })
}

@Composable
private fun LibraryToolbar(
    mode: LibraryMode,
    filterCount: Int,
    onMode: (LibraryMode) -> Unit,
    onBigPicture: () -> Unit,
    onFilter: () -> Unit,
    onAddGame: () -> Unit,
    onCommunity: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // Padding inside the scroll keeps the focus glow from being clipped.
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        // View modes. Wall and console are Big Picture's, so those two open it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(deckCardFill())
                .border(1.dp, deckLine(), RoundedCornerShape(18.dp))
                .padding(5.dp),
        ) {
            LibraryMode.entries.forEach { m -> ModeButton(m.label, m.icon, selected = m == mode) { onMode(m) } }
            ModeButton("Wall", Icons.Filled.ViewCarousel, selected = false, onClick = onBigPicture)
            ModeButton("Console", Icons.Filled.Tv, selected = false, onClick = onBigPicture)
        }
        Spacer(Modifier.width(8.dp))
        DeckButton(if (filterCount > 0) "Filters · $filterCount" else "Filter & sort", Icons.Filled.FilterList, onFilter)
        DeckButton("Add games", Icons.Filled.Add, onAddGame)
        IconTile(Icons.Filled.Public, "Community configs", onCommunity)
        // Steam friends and chat; renders only while signed in to Steam.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).border(1.dp, deckLine(), RoundedCornerShape(12.dp)),
        ) {
            SteamFriendsAction(tint = cs.onSurface)
        }
        IconTile(Icons.Filled.Tv, "Big Picture", onBigPicture)
    }
}

@Composable
private fun ModeButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(if (selected) cs.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = if (selected) 14.dp else 11.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        if (selected) Text(label, color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .deckFocusRing(shape, scaleTo = 1.04f)
            .clip(shape)
            .background(cs.surfaceContainerHigh)
            .border(1.dp, deckLine(), shape)
            .clickable(onClick = onClick)
            .size(44.dp),
    ) {
        Icon(icon, contentDescription = label, tint = cs.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FilteredEmpty(onClear: () -> Unit, onAddGame: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    ) {
        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary, modifier = Modifier.size(40.dp))
        Text("No games match these filters", color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text("Clear the filters to see your whole library.", color = cs.onSurfaceVariant, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeckButton("Clear filters", Icons.Filled.Close, onClear, primary = true)
            DeckButton("Add games", Icons.Filled.Add, onAddGame)
        }
    }
}

/** Filter & sort, as a centred Deck dialog of chip rows. Changes apply as they are made. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterDialog(
    filter: LibraryFilter,
    shown: Int,
    total: Int,
    onChange: (LibraryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .border(1.dp, deckLine(), shape)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter & sort", color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text("Showing $shown of $total", color = cs.onSurfaceVariant, fontSize = 13.5.sp)
            }
            DeckSectionLabel("Sort by", Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySort.entries.forEachIndexed { i, s ->
                    DeckChoiceChip(
                        s.label,
                        selected = filter.sort == s,
                        modifier = if (i == 0) Modifier.focusRequester(firstRequester) else Modifier,
                    ) { onChange(filter.copy(sort = s)) }
                }
            }
            DeckSectionLabel("Store", Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (STORE_SHELVES.map { it.first } + "Linux").forEach { label ->
                    val on = label in filter.stores
                    DeckChoiceChip(label, selected = on) {
                        onChange(filter.copy(stores = if (on) filter.stores - label else filter.stores + label))
                    }
                }
            }
            DeckSectionLabel("Played", Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayedFilter.entries.forEach { p ->
                    DeckChoiceChip(p.label, selected = filter.played == p) { onChange(filter.copy(played = p)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                DeckButton("Clear filters", Icons.Filled.Close, { onChange(LibraryFilter(sort = filter.sort)) })
                Spacer(Modifier.weight(1f))
                DeckButton("Done", null, onDismiss, primary = true, glyph = "B")
            }
        }
    }
}

@Composable
private fun DeckHero(
    shortcut: Shortcut,
    eyebrow: String,
    meta: List<Pair<ImageVector, String>>,
    height: Dp,
    compact: Boolean,
    playRequester: FocusRequester,
    onPlay: () -> Unit,
    onGamePage: () -> Unit,
    onSettings: () -> Unit,
    onOptions: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .background(cs.surface)
            .border(1.dp, deckLine(), shape),
    ) {
        // Wide cover on the right, fading in from the left so the title always sits on plain surface.
        Crossfade(
            targetState = shortcut.icon,
            label = "deckHeroArt",
            // matchParentSize: the art follows the hero's height instead of setting it.
            modifier = Modifier.matchParentSize(),
        ) { bmp ->
            if (bmp != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (compact) 1f else 0.7f)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = if (compact) Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)
                                        else Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(if (compact) 1f else 0.62f)
                .widthIn(max = 620.dp)
                .padding(horizontal = if (compact) 18.dp else 26.dp, vertical = if (compact) 18.dp else 24.dp),
        ) {
            Text(
                text = eyebrow.uppercase(),
                color = cs.primary,
                fontFamily = NunitoSansFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = shortcut.name,
                color = cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 26.sp else 36.sp,
                lineHeight = if (compact) 28.sp else 38.sp,
                letterSpacing = (-0.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                meta.forEach { (icon, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState())
                    // Room for the focus glow, which a scroll container would otherwise clip.
                    .padding(6.dp),
            ) {
                DeckButton("Play", Icons.Filled.PlayArrow, onPlay, primary = true, big = true, modifier = Modifier.focusRequester(playRequester))
                DeckButton("Game page", Icons.Filled.Info, onGamePage)
                DeckButton("Settings", Icons.Filled.Tune, onSettings)
                DeckButton("Options", Icons.Filled.MoreHoriz, onOptions, glyph = "X")
            }
        }
    }
}

@Composable
private fun DeckShelf(
    title: String,
    key: String,
    games: List<Shortcut>,
    wide: Boolean = false,
    onFocus: (Shortcut) -> Unit,
    onOpen: (Shortcut) -> Unit,
    onMenu: (Shortcut) -> Unit,
    subtitle: (Shortcut) -> String,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(title, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.width(10.dp))
            Text("${games.size}", color = cs.onSurfaceVariant, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(games, key = { "$key:${it.file.path}" }) { s ->
                DeckGameCard(
                    shortcut = s,
                    subtitle = subtitle(s),
                    width = if (wide) 250.dp else 128.dp,
                    aspect = if (wide) 16f / 10f else 2f / 3f,
                    onFocus = { onFocus(s) },
                    onClick = { onOpen(s) },
                    onLongClick = { onMenu(s) },
                )
            }
        }
    }
}

/** A cover card. [width] null = fill the grid cell. [subtitle] null = cover only (compact grid). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckGameCard(
    shortcut: Shortcut,
    subtitle: String?,
    width: Dp?,
    aspect: Float = 2f / 3f,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "deckCardScale")
    Column(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocus()
                focused = it.isFocused
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .then(
                    if (focused) Modifier
                        .shadow(18.dp, shape, ambientColor = cs.primary, spotColor = cs.primary)
                        .border(3.dp, cs.primary, shape)
                    else Modifier.shadow(6.dp, shape)
                )
                .clip(shape)
                .background(cs.surfaceVariant),
        ) {
            val bmp = shortcut.icon
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, contentDescription = shortcut.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
                    Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary, modifier = Modifier.size(36.dp))
                    if (subtitle == null) {
                        Text(shortcut.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (subtitle != null) {
            Text(
                text = shortcut.name,
                color = if (focused) cs.primary else cs.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
            Text(
                text = subtitle,
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** A list-mode row: cover, title, facts and setup pills, then Play and the game menu. A on the row opens the game page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckListRow(
    shortcut: Shortcut,
    subtitle: String,
    compact: Boolean,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(deckCardFill())
            .border(1.dp, deckLine(), shape)
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .deckFocusRing(RoundedCornerShape(14.dp), scaleTo = 1f, onFocused = onFocus)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpen)
                .padding(4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant),
            ) {
                val bmp = shortcut.icon
                if (bmp != null) {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(shortcut.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        setupSummary(shortcut).split(" · ").drop(1).forEach { DeckPill(it) }
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (compact) {
            IconTile(Icons.Filled.PlayArrow, "Play", onPlay)
        } else {
            DeckButton("Play", Icons.Filled.PlayArrow, onPlay, primary = true)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .deckFocusRing(CircleShape, scaleTo = 1f)
                .clip(CircleShape)
                .clickable(onClick = onMenu)
                .size(44.dp),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Game options", tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeckEmptyLibrary(modifier: Modifier, addRequester: FocusRequester, onAddGame: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(deckCardFill())
                .border(1.dp, deckLine(), shape)
                .padding(28.dp),
        ) {
            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary, modifier = Modifier.size(48.dp))
            Text("Your library is empty", color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "Add a game you already have on this device, or install one from a store on the Stores tab.",
                color = cs.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            DeckButton("Add a game", Icons.Filled.Add, onAddGame, primary = true, big = true, modifier = Modifier.focusRequester(addRequester))
        }
    }
}

/** A Deck button: rounded, bold label, optional leading icon and trailing controller glyph. */
@Composable
internal fun DeckButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    big: Boolean = false,
    glyph: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val ink = if (primary) cs.onPrimary else cs.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .deckFocusRing(shape, scaleTo = 1.04f)
            .clip(shape)
            .background(if (primary) cs.primary else cs.surfaceContainerHigh)
            .then(if (primary) Modifier else Modifier.border(1.dp, deckLine(), shape))
            .clickable(onClick = onClick)
            .heightIn(min = if (big) 48.dp else 40.dp)
            .padding(horizontal = if (big) 22.dp else 15.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (big) 22.dp else 18.dp))
        Text(
            text = label,
            color = ink,
            fontFamily = NunitoSansFamily,
            fontWeight = if (big) FontWeight.ExtraBold else FontWeight.Bold,
            fontSize = if (big) 16.sp else 13.5.sp,
            maxLines = 1,
        )
        if (glyph != null) {
            DeckGlyph(
                glyph,
                when (glyph) {
                    "A" -> GlyphKind.A
                    "B" -> GlyphKind.B
                    "Y" -> GlyphKind.Y
                    else -> GlyphKind.X
                },
            )
        }
    }
}
