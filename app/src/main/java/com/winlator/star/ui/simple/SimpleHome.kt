package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.R
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.DeckGameMenuSheet
import com.winlator.star.ui.deck.DeckRoutes
import com.winlator.star.ui.deck.DeckStore
import com.winlator.star.ui.deck.LibrarySort
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.PlayStats
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.gameSortKeyOf
import com.winlator.star.ui.deck.isSignedIn
import com.winlator.star.ui.deck.playtimeLabel
import com.winlator.star.ui.deck.readPlayStats
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.deck.sortGames
import com.winlator.star.ui.deck.storeLabelOf
import com.winlator.star.ui.deck.storeRank
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.GamesScreenRequests
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.rememberShortcutLauncher
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** The Simple home's four sections, in L1/R1 order. */
internal val SIMPLE_SECTIONS = listOf("Library", "Stores", "Manage", "Settings")
internal const val SEC_LIBRARY = 0
internal const val SEC_STORES = 1
internal const val SEC_MANAGE = 2
internal const val SEC_SETTINGS = 3

private val LAYOUTS = listOf(AppThemeState.SIMPLE_LAYOUT_SHELF, AppThemeState.SIMPLE_LAYOUT_RAIL, AppThemeState.SIMPLE_LAYOUT_FOCUS)

internal fun layoutLabel(layout: String): String = when (layout) {
    AppThemeState.SIMPLE_LAYOUT_RAIL -> "Rail"
    AppThemeState.SIMPLE_LAYOUT_FOCUS -> "Focus"
    else -> "Shelf"
}

/** The Rail layout's library orders, cycled with Y. */
internal val RAIL_SORTS = listOf(LibrarySort.RECENT, LibrarySort.NAME, LibrarySort.PLAYTIME, LibrarySort.STORE)

internal fun wrapIndex(v: Int, n: Int): Int = if (n <= 0) 0 else ((v % n) + n) % n

/** One entry of Stores, Manage or Settings. A setting has a [value] that [cycle] steps; everything else [open]s. */
internal class SimpleItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val value: String? = null,
    val cycle: ((Int) -> Unit)? = null,
    val open: (() -> Unit)? = null,
    /** A store's logo (drawable) on its [brand] colours; [logoWhite] draws a one-colour logo white. */
    val logo: Int? = null,
    val brand: List<Color>? = null,
    val logoWhite: Boolean = false,
)

/** The stores' logos and brand colours, the same as the XMB store cards use. */
internal fun storeLogo(st: DeckStore): Triple<Int, List<Color>, Boolean> = when (st) {
    DeckStore.STEAM -> Triple(R.drawable.store_logo_steam, listOf(Color(0xFF171A21), Color(0xFF1B2838)), false)
    DeckStore.EPIC -> Triple(R.drawable.store_logo_epic, listOf(Color(0xFF121212), Color(0xFF202020)), false)
    DeckStore.GOG -> Triple(R.drawable.store_logo_gog, listOf(Color(0xFF5C2D91), Color(0xFF3B1C60)), false)
    DeckStore.AMAZON -> Triple(R.drawable.store_logo_amazon, listOf(Color(0xFF232F3E), Color(0xFF131A22)), false)
    DeckStore.XBOX -> Triple(R.drawable.store_logo_xbox, listOf(Color(0xFF107C10), Color(0xFF0B5A0B)), true)
}

/** Everything the layouts show and do, rebuilt on each composition of [SimpleHome]. */
internal class SimpleData(
    /** The Library as shown: the current filter, in the current order. */
    val games: List<Shortcut>,
    /** Every game, most recently played first (the wallpaper collage, the Focus strip's order source). */
    val allGames: List<Shortcut>,
    val filters: List<String>,
    val stats: Map<String, PlayStats>,
    val stores: List<SimpleItem>,
    val manage: List<SimpleItem>,
    val settings: List<SimpleItem>,
    val launch: (Shortcut) -> Unit,
    val gameSettings: (Shortcut) -> Unit,
    val gameMenu: (Shortcut) -> Unit,
    val addGame: () -> Unit,
)

internal enum class Zone { MAIN, RAIL, LIST, DETAIL, ACTS, STRIP }

/** A button the Rail detail pane or the Focus action row offers for what is focused. */
internal class SimpleAction(val label: String, val perform: () -> Unit, val primary: Boolean = false)

/**
 * What is focused and what each press does, for all three layouts. The layouts only draw this state; input never
 * goes through Compose focus, so nothing on the home steals the D-pad and every press is handled in one place.
 */
@Stable
internal class SimpleController {
    var section by mutableIntStateOf(SEC_LIBRARY)
    private val focusList = mutableStateListOf(0, 0, 0, 0)
    var filter by mutableIntStateOf(0)
    var sort by mutableIntStateOf(0)
    var zone by mutableStateOf(Zone.LIST)
    var sub by mutableIntStateOf(0)
    var menuOpen by mutableStateOf(false)
    var menuFocus by mutableIntStateOf(0)
    var helpOpen by mutableStateOf(false)
    var layout: String = AppThemeState.SIMPLE_LAYOUT_SHELF
    var data: SimpleData? = null

    fun focusOf(sec: Int): Int = focusList[sec]

    /** Library has one more tile than games: "Add a game" at the end. */
    fun count(sec: Int): Int {
        val d = data ?: return 0
        return when (sec) {
            SEC_LIBRARY -> d.games.size + 1
            SEC_STORES -> d.stores.size
            SEC_MANAGE -> d.manage.size
            else -> d.settings.size
        }
    }

    fun setFocus(sec: Int, v: Int) { focusList[sec] = v.coerceIn(0, max(0, count(sec) - 1)) }
    fun move(d: Int) = setFocus(section, focusOf(section) + d)

    fun onAddTile(): Boolean = section == SEC_LIBRARY && focusOf(SEC_LIBRARY) >= (data?.games?.size ?: 0)
    fun focusedGame(): Shortcut? = if (section == SEC_LIBRARY) data?.games?.getOrNull(focusOf(SEC_LIBRARY)) else null
    fun itemsOf(sec: Int): List<SimpleItem> = when (sec) {
        SEC_STORES -> data?.stores
        SEC_MANAGE -> data?.manage
        SEC_SETTINGS -> data?.settings
        else -> null
    }.orEmpty()
    fun focusedItem(): SimpleItem? = itemsOf(section).getOrNull(focusOf(section))

    fun goSection(sec: Int) {
        section = wrapIndex(sec, SIMPLE_SECTIONS.size)
        sub = 0
        if (zone == Zone.DETAIL) zone = Zone.LIST
        if (layout == AppThemeState.SIMPLE_LAYOUT_FOCUS) zone = Zone.ACTS
    }

    private fun cycleSetting(d: Int): Boolean {
        val it = focusedItem() ?: return false
        it.cycle?.invoke(d) ?: return false
        return true
    }

    private fun activateItem(it: SimpleItem?) {
        if (it == null) return
        if (it.cycle != null) it.cycle.invoke(1) else it.open?.invoke()
    }

    private fun activateMain() {
        val d = data ?: return
        if (section == SEC_LIBRARY) {
            val g = focusedGame()
            if (g != null) d.launch(g) else d.addGame()
        } else activateItem(focusedItem())
    }

    /** The Rail detail pane's buttons and the Focus action row, for what is focused. */
    fun actions(): List<SimpleAction> {
        val d = data ?: return emptyList()
        if (section == SEC_LIBRARY) {
            val g = focusedGame() ?: return listOf(SimpleAction("Add a game", d.addGame, primary = true))
            return listOf(
                SimpleAction("Play", { d.launch(g) }, primary = true),
                SimpleAction("Game settings", { d.gameSettings(g) }),
                SimpleAction("Game menu", { d.gameMenu(g) }),
            )
        }
        val it = focusedItem() ?: return emptyList()
        return if (it.cycle != null) emptyList() else listOf(SimpleAction("Open", { it.open?.invoke() }, primary = true))
    }

    /**
     * One press. True when it was used. Only Y (the shell's search when nothing here uses it) and X are ever handed on:
     * a direction or bumper that does nothing here must not move Compose focus off the home or let the Deck shell switch
     * to one of its own tabs, and B on the Library does nothing rather than close the app by accident.
     */
    fun input(a: SIn): Boolean {
        if (data == null) return false
        if (helpOpen) { helpOpen = false; return true }
        if (a == SIn.SELECT) { helpOpen = true; return true }
        val used = when (layout) {
            AppThemeState.SIMPLE_LAYOUT_RAIL -> rail(a)
            AppThemeState.SIMPLE_LAYOUT_FOCUS -> focusLayout(a)
            else -> shelf(a)
        }
        return used || (a != SIn.Y && a != SIn.X)
    }

    private fun shelf(a: SIn): Boolean {
        val d = data ?: return false
        when (a) {
            SIn.LB -> goSection(section - 1)
            SIn.RB -> goSection(section + 1)
            SIn.START -> helpOpen = true
            else -> if (section == SEC_SETTINGS) {
                when (a) {
                    SIn.UP -> move(-1)
                    SIn.DOWN -> move(1)
                    SIn.LEFT -> cycleSetting(-1)
                    SIn.RIGHT -> cycleSetting(1)
                    SIn.A -> activateItem(focusedItem())
                    SIn.B -> goSection(SEC_LIBRARY)
                    else -> return false
                }
            } else {
                when (a) {
                    SIn.LEFT -> move(-1)
                    SIn.RIGHT -> move(1)
                    SIn.LT, SIn.RT -> {
                        if (section != SEC_LIBRARY) return false
                        filter = wrapIndex(filter + if (a == SIn.LT) -1 else 1, d.filters.size)
                        setFocus(SEC_LIBRARY, 0)
                    }
                    SIn.A -> activateMain()
                    SIn.X -> d.gameSettings(focusedGame() ?: return false)
                    SIn.Y -> d.gameMenu(focusedGame() ?: return false)
                    SIn.B -> { if (section != SEC_LIBRARY) goSection(SEC_LIBRARY) else return false }
                    else -> return false
                }
            }
        }
        return true
    }

    private fun rail(a: SIn): Boolean {
        val d = data ?: return false
        when (a) {
            SIn.LB -> { goSection(section - 1); return true }
            SIn.RB -> { goSection(section + 1); return true }
            SIn.START -> { helpOpen = true; return true }
            else -> Unit
        }
        when (zone) {
            Zone.RAIL -> when (a) {
                SIn.UP -> goSection(section - 1)
                SIn.DOWN -> goSection(section + 1)
                SIn.RIGHT, SIn.A -> zone = Zone.LIST
                else -> return false
            }
            Zone.DETAIL -> {
                val acts = actions()
                when (a) {
                    SIn.UP -> sub = max(0, sub - 1)
                    SIn.DOWN -> sub = min(max(0, acts.size - 1), sub + 1)
                    SIn.A -> acts.getOrNull(sub)?.perform?.invoke()
                    SIn.LEFT, SIn.B -> zone = Zone.LIST
                    else -> return false
                }
            }
            else -> when (a) {
                SIn.UP -> move(-1)
                SIn.DOWN -> move(1)
                SIn.LT -> move(-6)
                SIn.RT -> move(6)
                SIn.LEFT -> { if (section == SEC_SETTINGS && focusedItem()?.cycle != null) cycleSetting(-1) else { zone = Zone.RAIL } }
                SIn.RIGHT -> when {
                    section == SEC_SETTINGS -> { if (!cycleSetting(1)) activateItem(focusedItem()) }
                    actions().isNotEmpty() -> { zone = Zone.DETAIL; sub = 0 }
                }
                SIn.A -> { if (section == SEC_SETTINGS) activateItem(focusedItem()) else activateMain() }
                SIn.X -> d.gameSettings(focusedGame() ?: return false)
                SIn.Y -> {
                    if (section != SEC_LIBRARY) return false
                    sort = wrapIndex(sort + 1, RAIL_SORTS.size)
                    setFocus(SEC_LIBRARY, 0)
                }
                SIn.B -> zone = Zone.RAIL
                else -> return false
            }
        }
        return true
    }

    private fun focusLayout(a: SIn): Boolean {
        val d = data ?: return false
        if (menuOpen) {
            when (a) {
                SIn.UP -> menuFocus = max(0, menuFocus - 1)
                SIn.DOWN -> menuFocus = min(SIMPLE_SECTIONS.size, menuFocus + 1)
                SIn.A -> {
                    menuOpen = false
                    if (menuFocus >= SIMPLE_SECTIONS.size) helpOpen = true else goSection(menuFocus)
                }
                SIn.B, SIn.START -> menuOpen = false
                else -> Unit
            }
            return true
        }
        when (a) {
            SIn.START -> { menuOpen = true; menuFocus = section }
            SIn.LB -> goSection(section - 1)
            SIn.RB -> goSection(section + 1)
            SIn.LT, SIn.RT -> {
                if (section != SEC_LIBRARY) return false
                filter = wrapIndex(filter + if (a == SIn.LT) -1 else 1, d.filters.size)
                setFocus(SEC_LIBRARY, 0)
            }
            else -> if (zone == Zone.STRIP) {
                when (a) {
                    SIn.LEFT -> move(-1)
                    SIn.RIGHT -> move(1)
                    SIn.UP, SIn.A, SIn.B -> { zone = Zone.ACTS; sub = 0 }
                    else -> return false
                }
            } else {
                val acts = actions()
                val setting = section == SEC_SETTINGS && focusedItem()?.cycle != null
                when (a) {
                    SIn.LEFT -> { if (setting) cycleSetting(-1) else { sub = max(0, sub - 1) } }
                    SIn.RIGHT -> { if (setting) cycleSetting(1) else { sub = min(max(0, acts.size - 1), sub + 1) } }
                    SIn.DOWN -> zone = Zone.STRIP
                    SIn.A -> { if (setting) cycleSetting(1) else acts.getOrNull(sub)?.perform?.invoke() }
                    SIn.X -> d.gameSettings(focusedGame() ?: return false)
                    SIn.Y -> d.gameMenu(focusedGame() ?: return false)
                    SIn.B -> { if (section != SEC_LIBRARY) goSection(SEC_LIBRARY) else { zone = Zone.STRIP } }
                    else -> return false
                }
            }
        }
        return true
    }
}

/**
 * The home's place (section, focus per section, filter, order) for the whole app session: Home leaves composition while a
 * Deck page is open, and B back must land where the user was.
 */
internal object SimpleSession {
    val controller = SimpleController()
    /** The library as last loaded: Home's first frames after it opens again (see SimpleHome). */
    var lastShortcuts: List<com.winlator.star.container.Shortcut> = emptyList()
}

/** Tap without making the target a focus stop: the Simple home keeps the controller focus to itself. */
internal fun Modifier.simpleTap(onTap: () -> Unit): Modifier = this.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }

/**
 * The Simple home: a full-screen launcher in one of three layouts over the game-art wallpaper. It is the Deck shell's
 * Home in the Simple style; the shell's rails, bars and legend step aside while it is on screen (fullscreen), and
 * stores, tools and settings open as the Deck pages, with B back here. Launching, game settings and the game menu are
 * the Deck ones (ShortcutLauncher, the Deck settings editor, DeckGameMenuSheet).
 */
@Composable
internal fun SimpleHome(
    onNavigate: (String) -> Unit,
    onOpenContainer: (Int) -> Unit,
    onLaunchStore: (Screen) -> Unit,
    onAbout: () -> Unit,
    vm: ShortcutsViewModel = viewModel(),
) {
    val context = LocalContext.current
    // The list arrives a moment after Home opens again: until then the last one, so the rows never start with only
    // "Add a game" (a row that starts like that keeps its scroll on that tile when the games arrive).
    val shortcuts by vm.shortcuts.collectAsState(initial = SimpleSession.lastShortcuts)
    LaunchedEffect(shortcuts) { if (shortcuts.isNotEmpty()) SimpleSession.lastShortcuts = shortcuts }
    val launcher = rememberShortcutLauncher()
    val actions = rememberShortcutActions(vm)
    val layout by AppThemeState.simpleLayout.collectAsState()
    val deckActions = LocalDeckActions.current
    val navigate by rememberUpdatedState(onNavigate)
    val launchStore by rememberUpdatedState(onLaunchStore)
    val about by rememberUpdatedState(onAbout)

    DisposableEffect(deckActions) {
        deckActions.fullscreen.value = true
        onDispose { deckActions.fullscreen.value = false }
    }
    // Same resume refresh as the Deck home, so playtime, covers and sign-ins show up after a game or a store.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { vm.refresh(); resumeTick++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ctl = remember { SimpleSession.controller }
    ctl.layout = layout
    val stats = remember(shortcuts) { readPlayStats(context, shortcuts) }
    val recent = remember(shortcuts, stats) { sortGames(shortcuts, LibrarySort.RECENT) { gameSortKeyOf(it, stats) } }
    val filters = remember(recent) { listOf("All") + recent.map { storeLabelOf(it) }.distinct().sortedBy { storeRank(it) } }
    val filter = ctl.filter.coerceIn(0, filters.size - 1)
    val sort = ctl.sort
    val library = remember(recent, filters, filter, sort, layout, stats) {
        val f = filters[filter]
        val base = if (f == "All") recent else recent.filter { storeLabelOf(it) == f }
        val order = if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) RAIL_SORTS[sort] else LibrarySort.RECENT
        if (order == LibrarySort.RECENT) base else sortGames(base, order) { gameSortKeyOf(it, stats) }
    }
    val signedIn = remember(resumeTick) { DeckStore.entries.associateWith { isSignedIn(context, it) } }
    val stores = remember(signedIn, shortcuts) {
        DeckStore.entries.map { st ->
            val owned = shortcuts.count(st.owns)
            val (logo, brand, white) = storeLogo(st)
            SimpleItem(
                key = st.name,
                title = st.label,
                subtitle = if (signedIn[st] == true) "Signed in · $owned ${if (owned == 1) "game" else "games"}" else "Not signed in",
                icon = Icons.Filled.Storefront,
                open = { launchStore(st.screen) },
                logo = logo,
                brand = brand,
                logoWhite = white,
            )
        }
    }
    val addGame: () -> Unit = { GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.ADD_GAME) }
    val manage = remember {
        listOf(
            SimpleItem("add", "Add a game", "An .exe from your files, or a store game", Icons.Filled.Add, open = addGame),
            SimpleItem("containers", "Containers", "Wine prefixes and their settings", Icons.Filled.Layers, open = { navigate(Screen.Containers.route) }),
            SimpleItem("components", "Components", "Wine and Proton, DXVK, VKD3D, FEX and Box64", Icons.Filled.Extension, open = { navigate(DeckRoutes.COMPONENTS) }),
            SimpleItem("drivers", "GPU drivers", "Turnip and Qualcomm drivers", Icons.Filled.Memory, open = { navigate(Screen.AdrenoTools.route) }),
            SimpleItem("controls", "Controls", "Controller and on-screen layouts", Icons.Filled.SportsEsports, open = { navigate(DeckRoutes.CONTROLS) }),
            SimpleItem("tools", "Files, saves and logs", "File manager, save backups and game logs", Icons.Filled.FolderOpen, open = { navigate(DeckRoutes.TOOLS) }),
        )
    }
    val settings = remember(layout) {
        listOf(
            SimpleItem(
                "layout", "Layout", "Shelf, Rail or Focus", Icons.Filled.Dashboard,
                value = layoutLabel(layout),
                cycle = { d -> AppThemeState.setSimpleLayout(LAYOUTS[wrapIndex(LAYOUTS.indexOf(layout) + d, LAYOUTS.size)]) },
            ),
            SimpleItem("appearance", "Appearance", "Interface style, theme and accent colour", Icons.Filled.Palette, open = { navigate(Screen.Appearance.route) }),
            SimpleItem("settings", "Settings", "Everything else about the app", Icons.Filled.Settings, open = { navigate(Screen.Settings.route) }),
            SimpleItem("defaults", "Container defaults", "What every new container starts with", Icons.Filled.Tune, open = { navigate(DeckSettingsRoutes.DEFAULTS) }),
            SimpleItem("about", "About", "Version and credits", Icons.Filled.Info, open = { about() }),
        )
    }
    var menuFor by remember { mutableStateOf<Shortcut?>(null) }
    // Passed to the layouts as a parameter as well: the controller's copy is a plain field Compose does not watch, so a
    // layout reading only that would keep showing the first (empty) list from before the games loaded.
    val data = SimpleData(
        games = library,
        allGames = recent,
        filters = filters,
        stats = stats,
        stores = stores,
        manage = manage,
        settings = settings,
        launch = { launcher.requestLaunch(it) },
        gameSettings = { actions.perform(GameMenuAction.SETTINGS, it) },
        gameMenu = { menuFor = it },
        addGame = addGame,
    )
    ctl.data = data

    // The home takes the controller whenever it is back on screen: after a game, a page, the game menu or a dialog.
    val focus = remember { FocusRequester() }
    val enterTick = deckActions.enterTick.intValue
    LaunchedEffect(resumeTick, menuFor, enterTick) {
        if (menuFor != null) return@LaunchedEffect
        delay(80)
        runCatching { focus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                ctl.input(a)
            },
    ) {
        val wallGame = if (ctl.section == SEC_LIBRARY) ctl.focusedGame() else null
        SimpleWallpaper(wallGame, recent, hero = layout == AppThemeState.SIMPLE_LAYOUT_FOCUS)
        when (layout) {
            AppThemeState.SIMPLE_LAYOUT_RAIL -> SimpleRailLayout(ctl, data)
            AppThemeState.SIMPLE_LAYOUT_FOCUS -> SimpleFocusLayout(ctl, data)
            else -> SimpleShelfLayout(ctl, data)
        }
        if (ctl.helpOpen) SimpleHelp(layout) { ctl.helpOpen = false }
    }

    menuFor?.let { s ->
        DeckGameMenuSheet(
            shortcut = s,
            subtitle = storeLabelOf(s) + " · " + playtimeLabel(stats[s.file.path]?.ms ?: 0L),
            onDismiss = { menuFor = null },
            onPlay = { menuFor = null; launcher.requestLaunch(s) },
            onAction = { menuFor = null; actions.perform(it, s) },
            onContainer = { menuFor = null; onOpenContainer(s.container.id) },
        )
    }
    ShortcutActionDialogs(actions)
    ShortcutLaunchDialogs(launcher, onRemove = { vm.remove(it, context) })
}

/** Select: what every button does in this layout. Any press or tap closes it. */
@Composable
private fun SimpleHelp(layout: String, onClose: () -> Unit) {
    val rows = buildList {
        add("↕↔" to "Move")
        add("A" to "Play or open")
        add("B" to "Back")
        add("X" to "Game settings")
        add("Y" to if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) "Game order (in the list), game menu" else "Game menu")
        add("L1" to "Previous section")
        add("R1" to "Next section")
        add("L2" to if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) "Page up" else "Previous store filter")
        add("R2" to if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) "Page down" else "Next store filter")
        add("☰" to if (layout == AppThemeState.SIMPLE_LAYOUT_FOCUS) "Sections menu" else "This help")
        add("⧉" to "This help")
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color(0xEB05070B)).simpleTap(onClose),
    ) {
        Column(
            modifier = Modifier
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .padding(horizontal = 34.dp, vertical = 26.dp),
        ) {
            Text("Controls · ${layoutLabel(layout)}", color = SimpleInk, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(16.dp))
            rows.forEach { (g, t) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) { SimpleGlyph(g) }
                    Text(t, color = SimpleInk.copy(alpha = 0.9f), fontSize = 14.sp)
                }
            }
        }
    }
}
