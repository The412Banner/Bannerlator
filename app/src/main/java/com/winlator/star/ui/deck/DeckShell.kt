package com.winlator.star.ui.deck

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.core.UpdateManager
import com.winlator.star.ui.AccountAvatar
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.AppNavGraph
import com.winlator.star.ui.ComponentReturnBus
import com.winlator.star.ui.LocalTopBarActions
import com.winlator.star.ui.LocalTopBarTransparent
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.settings.BindDeckSettingsBus
import com.winlator.star.ui.deck.settings.DeckSettingsRoutes
import com.winlator.star.ui.deck.settings.deckSettingsRoutes
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.GameMenuAction
import com.winlator.star.ui.screens.DeckGameActionsHost
import com.winlator.star.ui.screens.GamesScreenRequests
import com.winlator.star.core.AppOrientation
import com.winlator.star.ui.findActivity
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.topBarActionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.ui.DeckSideLists
import com.winlator.star.ui.DeckWindow
import com.winlator.star.ui.LocalDeckSideLists
import com.winlator.star.ui.LocalHostedInDeck

/** Below this width (dp) a portrait Deck shell uses the phone layout: bottom bar, no legend. */
internal const val DECK_COMPACT_WIDTH_DP = 720

/** Below this width (dp) a landscape Deck shell uses the phone layout too; above it, rails and legend (see isDeckCompact). */
internal const val DECK_RAILS_MIN_WIDTH_DP = 560

/** Below this width (dp) only the selected tab keeps its label in the top strip. */
private const val DECK_WIDE_TABS_DP = 1100

/** The seven Deck destinations, in L1/R1 order. [root] is the route the tab lands on. */
internal enum class DeckTab(val label: String, val icon: ImageVector, val root: String) {
    HOME("Home", Icons.Filled.Home, DeckRoutes.HOME),
    STORES("Stores", Icons.Filled.Storefront, DeckRoutes.STORES),
    CONTAINERS("Containers", Icons.Filled.Inventory2, Screen.Containers.route),
    COMPONENTS("Components", Icons.Filled.Extension, DeckRoutes.COMPONENTS),
    CONTROLS("Controls", Icons.Filled.SportsEsports, DeckRoutes.CONTROLS),
    TOOLS("Tools", Icons.Filled.Build, DeckRoutes.TOOLS),
    SETTINGS("Settings", Icons.Filled.Settings, DeckSettingsRoutes.APP),
}

/** Phone bottom bar: these five, and "More" for the rest. */
private val PORTRAIT_MAIN = listOf(DeckTab.HOME, DeckTab.STORES, DeckTab.CONTAINERS, DeckTab.TOOLS, DeckTab.SETTINGS)

/** Which tab a route belongs to. Sub-pages (container editor, File Manager, ...) sit under their tab. */
internal fun deckTabOf(route: String?): DeckTab = when {
    route == null -> DeckTab.HOME
    route == DeckRoutes.STORES -> DeckTab.STORES
    route == Screen.Containers.route || route.startsWith("container_detail") || DeckSettingsRoutes.isContainerEditor(route) -> DeckTab.CONTAINERS
    route == DeckRoutes.COMPONENTS || route == Screen.Contents.route -> DeckTab.COMPONENTS
    route == DeckRoutes.CONTROLS || route == Screen.InputControls.route -> DeckTab.CONTROLS
    route == DeckRoutes.TOOLS || route == Screen.FileManager.route || route == Screen.SaveManager.route ||
        route == Screen.Saves.route || route == Screen.Wrappers.route || route == Screen.AdrenoTools.route -> DeckTab.TOOLS
    route == DeckSettingsRoutes.APP || route == Screen.Settings.route || route == Screen.Appearance.route -> DeckTab.SETTINGS
    else -> DeckTab.HOME
}

/** Classic routes that have a Deck page of their own: opening one lands on the Deck page instead. */
private val DECK_ALIASES = mapOf(
    Screen.Contents.route to DeckRoutes.COMPONENTS,
    Screen.InputControls.route to DeckRoutes.CONTROLS,
)

/** Routes the shell draws itself (no page bar above them). */
private fun isDeckPage(route: String): Boolean = route.startsWith("deck_")

/**
 * The Deck interface: a console-style shell around the same AppNavGraph routes the classic drawer uses,
 * plus the Deck home, game page and hub pages. In landscape the tabs run down a side rail (L1/R1) with the page's own tabs on a
 * second rail (L2/R2), a slim title line and a thin button legend, and Android's navigation bar is hidden. Portrait keeps the tabs
 * across the top with a legend along the bottom, and a phone a bottom bar instead. Colours all come from the user's theme.
 */
@Composable
internal fun DeckShell(
    startRoute: String,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit,
    onAbout: () -> Unit,
    onLaunchStore: (Screen) -> Unit,
) {
    // The XMB mode can be switched on and off while the shell is up, so the keyboard host follows the live setting.
    val xmbHome by AppThemeState.deckXmbHome.collectAsState()
    DeckTheme {
        XmbKeyboardHost(xmbHome) {
            DeckShellContent(startRoute, pendingRoute, onPendingRouteConsumed, onAbout, onLaunchStore)
        }
    }
}

@Composable
private fun DeckShellContent(
    startRoute: String,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit,
    onAbout: () -> Unit,
    onLaunchStore: (Screen) -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cfg = LocalConfiguration.current
    val compact = isDeckCompact(cfg)
    // Landscape: side rails instead of the top bar (deckRails).
    val rails = deckRails()
    val railsNow by rememberUpdatedState(rails)
    // XMB home: the main tabs run across a top bar in landscape too, instead of down a side rail. The page's own tab rail stays.
    val xmbHome by AppThemeState.deckXmbHome.collectAsState()
    val topTabs = rails && xmbHome
    val xmbHomeNow by rememberUpdatedState(xmbHome)
    val sideLists = remember { DeckSideLists() }
    val topBarActionsState = remember { topBarActionsState() }
    val topBarTransparentState = remember { mutableStateOf(false) }
    val deckActions = remember { DeckActions() }
    val ambient = remember { mutableStateOf<Pair<Color, Color>?>(null) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route ?: DeckRoutes.HOME
    val tab = deckTabOf(currentRoute)

    val nav = remember(navController) { DeckNav(navController) }
    // XMB mode: no Stores tab; the stores are cards in Home's upper row. Its route stays for search and the other modes.
    val tabs = remember(xmbHome) { if (xmbHome) DeckTab.entries - DeckTab.STORES else DeckTab.entries }
    SideEffect { nav.tabs = tabs }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val tabRequesters = remember { DeckTab.entries.associateWith { FocusRequester() } }
    val tabsFocused = remember { mutableStateOf(false) }
    // L1/R1: next menu, with focus on its tab, so the bumpers keep moving across menus until Down (Right on the side rail) enters one.
    // No top bar or rail (phone layout): the requester is not attached, so focus stays where it is.
    // XMB home: the tabs take no D-pad focus at all, so the new page takes its own first focus straight away.
    val switchMenu: (Int) -> Unit = { delta ->
        val t = nav.cycle(delta)
        if (xmbHomeNow) {
            deckActions.navHold.value = false
            deckActions.enterTick.intValue++
        } else {
            deckActions.navHold.value = true
            if (runCatching { tabRequesters.getValue(t).requestFocus() }.isFailure) deckActions.navHold.value = false
        }
    }
    // Down from the top bar (Right from the side rail): the page takes its own first focus; a page without one (a classic screen) gets the nearest control below (to the right).
    val enterContent: () -> Unit = {
        val into = if (railsNow) FocusDirection.Right else FocusDirection.Down
        deckActions.navHold.value = false
        deckActions.enterTick.intValue++
        scope.launch {
            repeat(4) { withFrameNanos { } }
            if (tabsFocused.value) focusManager.moveFocus(into)
            // The page's own tabs move with L2/R2 from anywhere, so entering goes past them into the page.
            if (deckActions.stripFocused.value) {
                withFrameNanos { }
                focusManager.moveFocus(into)
            }
        }
    }
    var searchOpen by remember { mutableStateOf(false) }
    val openSearch: () -> Unit = { searchOpen = true }
    // Settings from any game menu (Home, game page, All games) opens the Deck editor while the shell is up.
    BindDeckSettingsBus(navController)

    // Same as the classic shell: clear the previous screen's top-bar actions on navigation and re-read the signed-in account.
    // Screens re-set their actions from a LaunchedEffect that runs after this one.
    LaunchedEffect(currentRoute) {
        topBarActionsState.value = {}
        AccountUiBus.refresh(context)
    }
    val account = AccountUiBus.account

    // In-app update pill, as in Classic: a newer release exists, notify is on, and this version was not
    // dismissed this session. View slides it out and opens the highlights; Update now runs the download card.
    var pillUpdate by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var pillVisible by remember { mutableStateOf(false) }
    var highlightsFor by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info ->
            (context as? MainActivity)?.runOnUiThread {
                if (info != null && info.isNewer &&
                    UpdateManager.isNotifyEnabled(context) &&
                    info.versionCode != UpdateManager.dismissedThisSession
                ) {
                    pillUpdate = info
                    pillVisible = true
                }
            }
        }
    }

    // Launch-time target: EXTRA_OPEN_SCREEN, the default landing screen, or Appearance right after switching styles.
    // The graph always starts at the Deck home so Back from any tab lands there.
    LaunchedEffect(Unit) {
        if (startRoute != Screen.Games.route && startRoute != Screen.BigPicture.route && startRoute != DeckRoutes.HOME) {
            nav.open(startRoute)
        }
    }
    // A route requested by a relaunch intent or the component-install resume.
    // "Games" means the Deck home unless the Games screen has work waiting: the component-install return reopening a game's settings, or a queued request such as the first-run's "Add a game".
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            // The shell's own DeckGameActionsHost runs any queued request in place.
            val target = if (pendingRoute == Screen.Games.route) DeckRoutes.HOME else pendingRoute
            nav.open(target)
            onPendingRouteConsumed()
        }
    }

    // Registered before the NavHost, so a screen's own BackHandler (unsaved changes, nested menus) still wins.
    // At a sub-page pop it; at a tab root go Home; at Home this is off (system default).
    BackHandler(enabled = currentRoute != DeckRoutes.HOME) { nav.back() }

    // Landscape hides Android's navigation bar; a swipe in from the edge shows it for a moment, as in a game.
    // It comes back on leaving the Deck style or turning to portrait. Games and stores are other activities with their own windows,
    // and it is hidden again whenever this one resumes. The status bar stays (except in the XMB mode, below).
    val activity = context as? android.app.Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    // The XMB mode locks the app to its own orientation (AppOrientation.deckMode). Turning it off, or switching back to
    // Classic, disposes this after the pref has changed, so applying again hands the window back to the Appearance setting.
    DisposableEffect(activity, xmbHome) {
        if (!xmbHome || activity == null) return@DisposableEffect onDispose {}
        AppOrientation.apply(activity)
        onDispose { AppOrientation.apply(activity) }
    }
    // Re-run when the full-screen XMB closes: it shows the system bars again on its way out.
    val xmbShown = deckActions.fullscreen.value
    // XMB mode: the status bar goes too, on every tab and in both orientations, like a console.
    DisposableEffect(rails, xmbHome, activity, lifecycleOwner, xmbShown) {
        val window = activity?.window
        if (!(rails || xmbHome) || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val bars = if (xmbHome) WindowInsetsCompat.Type.systemBars() else WindowInsetsCompat.Type.navigationBars()
        val hide = {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
            DeckWindow.setNavBarHidden(activity)
            DeckWindow.setStatusBarHidden(xmbHome)
        }
        hide()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hide() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            DeckWindow.setNavBarHidden(null)
            DeckWindow.setStatusBarHidden(false)
            controller.show(bars)
        }
    }
    // XMB mode: the shell lays itself out edge to edge (the window no longer pads for the bars) and reaches under the
    // camera hole on every tab, so Android never adds its own black strip and switching tabs doesn't resize the screen.
    // Only Home draws its content into the hole; every other page keeps clear of it through the insets below.
    DisposableEffect(xmbHome, activity) {
        val window = activity?.window
        if (!xmbHome || window == null) return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val cutoutBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = cutoutBefore }
            }
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }
    val homeBleed = xmbHome && currentRoute == DeckRoutes.HOME && !compact
    // XMB mode: Home's floating tab bar on every tab, except the phone layout, too narrow for it (it keeps its bars).
    val floatTabs = xmbHome && !compact

    // Settings and Appearance are the Settings tab's two pages: in landscape they sit on the page-tab rail, and L2/R2 swap them.
    val settingsPages = rails && (currentRoute == Screen.Settings.route || currentRoute == Screen.Appearance.route)
    DisposableEffect(settingsPages, deckActions, nav) {
        if (!settingsPages) return@DisposableEffect onDispose {}
        val swap: (Int) -> Unit = {
            nav.open(if (nav.currentRoute() == Screen.Settings.route) Screen.Appearance.route else Screen.Settings.route)
        }
        deckActions.subTab.value = swap
        deckActions.subTabLabel.value = "Page"
        onDispose { if (deckActions.subTab.value === swap) deckActions.subTab.value = null }
    }

    // Controller shortcuts.
    // MainActivity only offers keys its view tree left unhandled, so a screen that uses L1/R1 or B itself keeps them.
    // None of this runs while a game is up, because the game is another activity.
    DisposableEffect(nav, backDispatcher, deckActions) {
        val handler: (KeyEvent) -> Boolean = handler@{ ev ->
            val first = ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0
            when (ev.keyCode) {
                // L1/R1 always move the top-level tabs; L2/R2 move a page's own tabs (a tab strip, the settings editor's categories), else the top-level tabs.
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_LEFT_BRACKET -> { if (first) switchMenu(-1); true }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_RIGHT_BRACKET -> { if (first) switchMenu(1); true }
                KeyEvent.KEYCODE_BUTTON_L2 -> { if (first) deckActions.subTab.value?.invoke(-1) ?: switchMenu(-1); true }
                KeyEvent.KEYCODE_BUTTON_R2 -> { if (first) deckActions.subTab.value?.invoke(1) ?: switchMenu(1); true }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (nav.currentRoute() == DeckRoutes.HOME) return@handler false
                    if (ev.action == KeyEvent.ACTION_UP && !ev.isCanceled) {
                        if (backDispatcher != null) backDispatcher.onBackPressed() else nav.back()
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (first) (deckActions.search.value ?: openSearch)()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_START -> {
                    val options = deckActions.options.value ?: return@handler false
                    if (first) options()
                    true
                }
                else -> false
            }
        }
        DeckInput.handler = handler
        onDispose { if (DeckInput.handler === handler) DeckInput.handler = null }
    }

    // Backdrop: the theme background, washed with two soft glows.
    // On Home they follow the focused game's cover; elsewhere they are the accent.
    val tints = ambient.value
    val glow1 by animateColorAsState(tints?.first ?: cs.primary, tween(900), label = "deckGlow1")
    val glow2 by animateColorAsState(tints?.second ?: lerp(cs.primary, cs.background, 0.35f), tween(900), label = "deckGlow2")

    CompositionLocalProvider(
        LocalTopBarActions provides topBarActionsState,
        LocalTopBarTransparent provides topBarTransparentState,
        LocalDeckActions provides deckActions,
        LocalDeckAmbient provides ambient,
        LocalContentColor provides cs.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.background)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(glow1.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(size.width * 0.88f, -size.height * 0.10f),
                            radius = size.maxDimension * 0.75f,
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(glow2.copy(alpha = 0.20f), Color.Transparent),
                            center = Offset(-size.width * 0.05f, size.height * 1.05f),
                            radius = size.maxDimension * 0.65f,
                        )
                    )
                },
        ) {
            // Landscape: a camera hole on a short edge is not left as an empty strip. The side rail's glass runs under it with its
            // icons clear of it; on the other side the page background runs under it and only the page, not the legend, keeps clear.
            val dir = LocalLayoutDirection.current
            val cutout = WindowInsets.displayCutout.asPaddingValues()
            // XMB home, landscape, any tab but Home: the page sits in an even frame, as wide as the camera strip on both
            // sides, filled with the backdrop, so the strip reads as a margin rather than a wall beside the panels.
            val framed = topTabs && !homeBleed
            val frame = if (framed) maxOf(cutout.calculateStartPadding(dir), cutout.calculateEndPadding(dir)) else 0.dp
            val cutoutStart = if (rails && !framed) cutout.calculateStartPadding(dir) else 0.dp
            val cutoutEnd = if (rails && !framed) cutout.calculateEndPadding(dir) else 0.dp
            val baseInsets = if (rails) WindowInsets.systemBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Vertical))
                else WindowInsets.systemBars.union(WindowInsets.displayCutout)
            // Edge to edge (XMB home) the window no longer makes room for the keyboard, so the pages do.
            val insets = if (xmbHome) baseInsets.union(WindowInsets.ime) else baseInsets
            // Home's XMB takes the whole screen, under the camera hole too; the shell floats its tabs and Search over it.
            Column(modifier = Modifier.fillMaxSize().then(if (homeBleed) Modifier else Modifier.windowInsetsPadding(insets))) {
                // The My-account sheet opens over the current page (DeckGameActionsHost).
                val onAccount = { AccountUiBus.requestMyAccount() }
                val onTabsFocus: (Boolean) -> Unit = { focused ->
                    tabsFocused.value = focused
                    deckActions.navHold.value = focused
                }
                val goBack = { if (backDispatcher != null) backDispatcher.onBackPressed() else nav.back() }
                // Home's XMB view takes the whole screen; the XMB has its own hints, menus and game row.
                val full = deckActions.fullscreen.value && currentRoute == DeckRoutes.HOME
                // XMB mode, every tab but Home (which floats it over the XMB) and the phone layout: the same floating bar as
                // Home, at the same place, with the page starting right under it.
                if (floatTabs && !full && !homeBleed) {
                    DeckFloatingBar(
                        current = tab,
                        tabs = tabs,
                        avatarUrl = account?.displayAvatarUrl,
                        portrait = !rails,
                        startInset = if (rails) cutout.calculateStartPadding(dir) else 0.dp,
                        endInset = if (rails) cutout.calculateEndPadding(dir) else 0.dp,
                        onTab = { nav.goTab(it) },
                        onCycle = { switchMenu(it) },
                        onAccount = onAccount,
                    )
                }
                // Landscape is rails | content (in the XMB mode: the page's own tab rail | content, under the floating tab bar);
                // portrait keeps the top bar over the content. The NavHost stays in one slot either way.
                // Framed (XMB mode, tabs other than Home), only this middle part sits inside the even camera frame.
                Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = frame)) {
                    if (rails && !full) {
                        if (!topTabs) {
                            DeckSideRail(
                                cutoutStart = cutoutStart,
                                current = tab,
                                tabRequesters = tabRequesters,
                                avatarUrl = account?.displayAvatarUrl,
                                onTab = { nav.goTab(it) },
                                onCycle = { nav.cycle(it) },
                                onTabsFocus = onTabsFocus,
                                onEnterContent = enterContent,
                                onAccount = onAccount,
                                onSearch = openSearch,
                            )
                        }
                        val pageTabs = deckActions.pageTabs.value
                        if (settingsPages) {
                            val pages = listOf(Screen.Settings.route to DeckTabItem("Settings", Icons.Filled.Settings), Screen.Appearance.route to DeckTabItem("Appearance", Icons.Filled.Palette))
                            DeckPageTabRail(
                                tabs = pages.map { it.second },
                                selected = pages.indexOfFirst { it.first == currentRoute }.coerceAtLeast(0),
                                compact = false,
                                onSelect = { i -> if (pages[i].first != currentRoute) nav.open(pages[i].first) },
                                onFocus = { deckActions.stripFocused.value = it },
                                glass = !framed,
                            )
                        } else if (pageTabs != null && isDeckPage(currentRoute) && pageTabs.tabs.isNotEmpty()) {
                            DeckPageTabRail(
                                tabs = pageTabs.tabs,
                                selected = pageTabs.selected,
                                compact = pageTabs.compact || sideLists.count.intValue > 0,
                                onSelect = { i -> pageTabs.onSelect(i) },
                                onFocus = { deckActions.stripFocused.value = it },
                                glass = !framed,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (full || homeBleed || floatTabs) {
                            // No top bar here: none over the XMB, and the XMB home floats its own (above, or over Home).
                        } else if (compact) {
                            DeckCompactTopBar(tab = tab, avatarUrl = account?.displayAvatarUrl, onAccount = onAccount, onSearch = openSearch, dpad = !xmbHome)
                        } else if (!rails) {
                            DeckTopBar(
                                current = tab,
                                wide = cfg.screenWidthDp >= DECK_WIDE_TABS_DP,
                                tabRequesters = tabRequesters,
                                avatarUrl = account?.displayAvatarUrl,
                                onTab = { nav.goTab(it) },
                                onCycle = { nav.cycle(it) },
                                onTabsFocus = onTabsFocus,
                                onEnterContent = enterContent,
                                onAccount = onAccount,
                                onSearch = openSearch,
                            )
                        }

                        val pageTitle = deckActions.pageInfo.value
                        if (full) {
                            // No title line over the XMB.
                        } else if (rails) {
                            // One slim line: the page's title (description behind the info button) and the screen's own actions.
                            // Home and the game page draw their own hero instead.
                            if (!isDeckPage(currentRoute)) {
                                DeckTitleLine(
                                    endInset = cutoutEnd,
                                    title = deckPageTitle(context, currentRoute, backstackEntry?.arguments?.getInt("id") ?: -1),
                                    description = null,
                                    showBack = currentRoute != tab.root && !settingsPages,
                                    onBack = goBack,
                                    actions = topBarActionsState.value,
                                )
                            } else if (pageTitle != null && currentRoute != DeckRoutes.HOME && !currentRoute.startsWith("deck_game")) {
                                DeckTitleLine(
                                    endInset = cutoutEnd,
                                    title = pageTitle.title,
                                    description = pageTitle.description,
                                    showBack = false,
                                    onBack = goBack,
                                    actions = topBarActionsState.value,
                                )
                            }
                        } else if (!isDeckPage(currentRoute)) {
                            // Deck pages draw their own header.
                            DeckPageBar(
                                title = deckPageTitle(context, currentRoute, backstackEntry?.arguments?.getInt("id") ?: -1),
                                showBack = currentRoute != tab.root,
                                onBack = goBack,
                                settingsSubTabs = tab == DeckTab.SETTINGS,
                                onSettingsSubTab = { route -> nav.open(route) },
                                currentRoute = currentRoute,
                                actions = topBarActionsState.value,
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(end = if (homeBleed) 0.dp else cutoutEnd)) {
                            CompositionLocalProvider(
                                LocalHostedInDeck provides rails,
                                LocalDeckSideLists provides sideLists,
                            ) {
                                AppNavGraph(
                                    navController = navController,
                                    startRoute = DeckRoutes.HOME,
                                    modifier = Modifier.fillMaxSize(),
                                    containerEditorRoute = DeckSettingsRoutes::containerEditorFor,
                                    instantPages = xmbHome,
                                    extraRoutes = {
                                        composable(DeckRoutes.HOME) {
                                            DeckHome(
                                                onOpenGame = { shortcut -> nav.open(DeckRoutes.game(shortcut.file.path)) },
                                                onOpenContainer = { id -> nav.open(DeckSettingsRoutes.containerEditorFor(id) ?: "container_detail?id=$id") },
                                                // "Add games" opens over Home (DeckGameActionsHost).
                                                onAddGame = {
                                                    GamesScreenRequests.pending = GamesScreenRequests.Request(GameMenuAction.ADD_GAME)
                                                },
                                                onBigPicture = { nav.open(Screen.BigPicture.route) },
                                                onLaunchStore = onLaunchStore,
                                            )
                                        }
                                        composable(
                                            route = DeckRoutes.GAME,
                                            arguments = listOf(navArgument(DeckRoutes.GAME_ARG) { type = NavType.StringType; defaultValue = "" }),
                                        ) { entry ->
                                            DeckGamePage(
                                                shortcutPath = entry.arguments?.getString(DeckRoutes.GAME_ARG).orEmpty(),
                                                onBack = { nav.back() },
                                                onOpenContainer = { id -> nav.open(DeckSettingsRoutes.containerEditorFor(id) ?: "container_detail?id=$id") },
                                            )
                                        }
                                        composable(DeckRoutes.STORES) {
                                            DeckStoresPage(
                                                onLaunchStore = onLaunchStore,
                                                onOpenAppearance = { nav.open(Screen.Appearance.route) },
                                                onOpenGame = { path -> nav.open(DeckRoutes.game(path)) },
                                            )
                                        }
                                        composable(DeckRoutes.COMPONENTS) { DeckComponentsPage() }
                                        composable(DeckRoutes.CONTROLS) { DeckControlsPage() }
                                        deckSettingsRoutes(navController, onAbout)
                                        composable(DeckRoutes.TOOLS) {
                                            DeckToolsPage(
                                                onNavigate = { route -> nav.open(route) },
                                                onMyAccount = onAccount,
                                                onAbout = onAbout,
                                            )
                                        }
                                    },
                                )
                            }
                            // Add games, My account and the game actions the settings pages hand over: dialogs over whatever page is up.
                            DeckGameActionsHost()
                            // Update pill: over the content, under the title line / top bar (Classic's place relative to its top bar).
                            val upd = pillUpdate
                            if (upd != null && !searchOpen) {
                                com.winlator.star.ui.UpdatePill(
                                    info = upd,
                                    visible = pillVisible,
                                    onDismiss = {
                                        pillVisible = false
                                        UpdateManager.dismissedThisSession = upd.versionCode
                                    },
                                    onView = {
                                        pillVisible = false
                                        UpdateManager.dismissedThisSession = upd.versionCode
                                        scope.launch {
                                            kotlinx.coroutines.delay(com.winlator.star.ui.UPDATE_PILL_EXIT_MS)
                                            highlightsFor = upd
                                        }
                                    },
                                    // On the XMB home it sits under the floating status line instead of on it.
                                    modifier = Modifier.align(Alignment.TopEnd).padding(top = if (homeBleed) 52.dp else 8.dp, end = 12.dp),
                                )
                            }
                        }
                        // App-wide minimized progress pill for a running archive unpack (renders nothing when idle).
                        com.winlator.star.ui.UnpackProgressPill()

                        // XMB mode: no legend on any tab, only the floating Search below.
                        if (!compact && !full && !xmbHome) {
                            DeckLegend(
                                thin = rails,
                                endInset = cutoutEnd,
                                atHome = currentRoute == DeckRoutes.HOME,
                                subTabLabel = if (deckActions.subTab.value != null) deckActions.subTabLabel.value else null,
                                optionsLabel = if (deckActions.options.value != null) deckActions.optionsLabel.value else null,
                                onCycle = { switchMenu(1) },
                                onSubCycle = { deckActions.subTab.value?.invoke(1) },
                                onBack = goBack,
                                onOptions = { deckActions.options.value?.invoke() },
                                onSearch = { (deckActions.search.value ?: openSearch)() },
                            )
                        }
                    }
                }
                if (compact && !full) {
                    DeckBottomBar(current = tab, tabs = tabs, onTab = { nav.goTab(it) }, dpad = !xmbHome)
                }
            }

            // XMB home: the tabs and status float over the XMB's own space above its game row, clear of the camera hole.
            // No backgrounds, so the XMB's backdrop shows through.
            if (homeBleed) {
                DeckFloatingBar(
                    current = tab,
                    tabs = tabs,
                    avatarUrl = account?.displayAvatarUrl,
                    portrait = !rails,
                    startInset = cutout.calculateStartPadding(dir),
                    endInset = cutout.calculateEndPadding(dir),
                    onTab = { nav.goTab(it) },
                    onCycle = { switchMenu(it) },
                    onAccount = { AccountUiBus.requestMyAccount() },
                    modifier = Modifier.align(Alignment.TopStart).padding(top = cutout.calculateTopPadding()),
                )
            }
            // XMB home, every tab but the phone layout (which has its bars): the only hint is a muted Search bottom right,
            // clear of the camera frame. It may sit over a page's corner.
            if (xmbHome && !compact) {
                val ss = rememberScreenScale()
                DeckFloatingSearch(
                    onSearch = { (deckActions.search.value ?: openSearch)() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = ss.dp(16f) + maxOf(frame, cutout.calculateEndPadding(dir)),
                            bottom = ss.dp(12f) + cutout.calculateBottomPadding(),
                        ),
                )
            }

            if (searchOpen) {
                DeckSearchOverlay(
                    onDismiss = { searchOpen = false },
                    onOpenGame = { path -> searchOpen = false; nav.open(DeckRoutes.game(path)) },
                    onOpenRoute = { route -> searchOpen = false; nav.open(route) },
                    onLaunchStore = { screen -> searchOpen = false; onLaunchStore(screen) },
                    onAbout = { searchOpen = false; onAbout() },
                )
            }

            highlightsFor?.let { info ->
                com.winlator.star.ui.UpdateHighlightsDialog(
                    info = info,
                    onLater = { highlightsFor = null },
                    onUpdate = {
                        highlightsFor = null
                        (context as? MainActivity)?.let { UpdateManager.downloadAndInstall(it, info) {} }
                    },
                )
            }
            // The download card, whichever button started the download (pill, highlights, Settings, About).
            com.winlator.star.ui.UpdateHost()
        }
    }
}

/** Tab switching and back rules, shared by the tab strip, bottom bar, controller keys and BackHandler. */
private class DeckNav(private val navController: NavHostController) {
    fun currentRoute(): String = navController.currentBackStackEntry?.destination?.route ?: DeckRoutes.HOME

    /** Switch tab the way the classic drawer switches screens: one entry per tab, state kept. */
    fun goTab(t: DeckTab) {
        val route = currentRoute()
        if (deckTabOf(route) == t) {
            // Re-selecting the current tab returns to its root.
            if (route != t.root) navController.popBackStack(t.root, inclusive = false)
            return
        }
        navController.navigate(t.root) {
            popUpTo(DeckRoutes.HOME) { saveState = true }
            launchSingleTop = true
            // Home is never restored: a non-inclusive popUpTo files the tab being left under Home's id too.
            restoreState = t != DeckTab.HOME
        }
    }

    /** The tabs L1/R1 move through (the XMB mode has no Stores tab: its stores are cards on Home). */
    var tabs: List<DeckTab> = DeckTab.entries

    /**
     * Move one tab along ([delta] is 1 or -1), wrapping; returns the tab now shown. Steps through the full tab order and
     * skips any tab not in [tabs], so it also works from a tab the bar doesn't list (Stores, reached from search).
     */
    fun cycle(delta: Int): DeckTab {
        val all = DeckTab.entries
        var i = all.indexOf(deckTabOf(currentRoute()))
        do { i = (i + delta + all.size) % all.size } while (all[i] !in tabs)
        val t = all[i]
        goTab(t)
        return t
    }

    /** Open any route: switch to its tab first, then push it when it is a sub-page of that tab. */
    fun open(requested: String) {
        val route = DECK_ALIASES[requested] ?: requested
        val t = deckTabOf(route)
        goTab(t)
        if (route != t.root && currentRoute() != route) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    fun back() {
        val route = currentRoute()
        val t = deckTabOf(route)
        if (route != t.root && navController.previousBackStackEntry != null) navController.popBackStack()
        else if (route != DeckRoutes.HOME) goTab(DeckTab.HOME)
    }
}

private fun deckPageTitle(context: Context, route: String, id: Int): String = when {
    route.startsWith("container_detail") -> when {
        id == ContainerDetailViewModel.EDIT_DEFAULTS_ID -> context.getString(R.string.new_container_defaults)
        id > 0 -> context.getString(R.string.edit_container)
        else -> context.getString(R.string.new_container)
    }
    route == Screen.Games.route -> "All games"
    route == Screen.Contents.route -> "Components"
    route == Screen.InputControls.route -> "Controls"
    else -> Screen.drawerItems.firstOrNull { it.route == route }?.label
        ?: listOf(Screen.SaveManager, Screen.Wrappers, Screen.AdrenoTools).firstOrNull { it.route == route }?.label
        ?: "Bannerlator"
}

// ── Top strip ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckTopBar(
    current: DeckTab,
    wide: Boolean,
    tabRequesters: Map<DeckTab, FocusRequester>,
    avatarUrl: String?,
    onTab: (DeckTab) -> Unit,
    onCycle: (Int) -> Unit,
    onTabsFocus: (Boolean) -> Unit,
    onEnterContent: () -> Unit,
    onAccount: () -> Unit,
    onSearch: () -> Unit,
) {
    val line = deckLine()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(deckGlass())
            .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f)) }
            .padding(horizontal = 16.dp),
    ) {
        DeckMark()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f),
        ) {
            DeckBumperButton("L1") { onCycle(-1) }
            Spacer(Modifier.width(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .onFocusChanged { onTabsFocus(it.hasFocus) }
                    // Down leaves the tabs for the page itself, not whichever control happens to sit below the tab.
                    .onPreviewKeyEvent { e ->
                        if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) { onEnterContent(); true } else false
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                DeckTab.entries.forEach { t ->
                    DeckTabButton(
                        t,
                        selected = t == current,
                        showLabel = wide || t == current,
                        modifier = Modifier.focusRequester(tabRequesters.getValue(t)),
                    ) { onTab(t) }
                }
            }
            Spacer(Modifier.width(4.dp))
            DeckBumperButton("R1") { onCycle(1) }
        }
        DeckSearchButton(onSearch)
        DeckStatus(avatarUrl = avatarUrl, showBattery = true, onAccount = onAccount)
    }
}

/** XMB home: the tab bars take touch and L1/R1 only, never D-pad focus. */
private fun Modifier.dpadFocus(enabled: Boolean): Modifier = if (enabled) this else focusProperties { canFocus = false }

@Composable
private fun DeckCompactTopBar(tab: DeckTab, avatarUrl: String?, onAccount: () -> Unit, onSearch: () -> Unit, dpad: Boolean = true) {
    val line = deckLine()
    val showBattery = LocalConfiguration.current.screenWidthDp >= 420
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(deckGlass())
            .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f)) }
            .padding(horizontal = 16.dp),
    ) {
        DeckMark()
        Text(
            text = tab.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DeckSearchButton(onSearch, dpad)
        DeckRotateButton()
        DeckStatus(avatarUrl = avatarUrl, showBattery = showBattery, onAccount = onAccount, dpad = dpad)
    }
}

/** The top bar's magnifier: opens the same search overlay as Y. */
@Composable
private fun DeckSearchButton(onSearch: () -> Unit, dpad: Boolean = true) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .deckFocusRing(CircleShape, scaleTo = 1f)
            .clip(CircleShape)
            .dpadFocus(dpad)
            .clickable(onClick = onSearch)
            .size(40.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
    }
}

/**
 * Flips the XMB home's orientation lock (landscape by default, portrait after a tap) and applies it at once. Shown only
 * in that mode; the others rotate with the device as set in Appearance. Touch only: it never takes D-pad focus, on any
 * bar. [tint] and [size] let the XMB home's floating bar mute it.
 */
@Composable
private fun DeckRotateButton(tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 40.dp, iconSize: Dp = 24.dp) {
    val xmbHome by AppThemeState.deckXmbHome.collectAsState()
    if (!xmbHome) return
    val context = LocalContext.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .dpadFocus(false)
            .clickable { context.findActivity()?.let { AppOrientation.toggleDeck(it) } }
            .size(size),
    ) {
        Icon(Icons.Filled.ScreenRotation, contentDescription = "Rotate screen", tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun DeckTabButton(tab: DeckTab, selected: Boolean, showLabel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val accent = cs.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .drawBehind {
                if (selected) {
                    val barH = 3.dp.toPx()
                    val inset = 14.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(inset, size.height - barH),
                        size = Size((size.width - inset * 2).coerceAtLeast(barH), barH),
                        cornerRadius = CornerRadius(barH, barH),
                    )
                }
            }
            .padding(horizontal = if (showLabel) 13.dp else 10.dp, vertical = 9.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) accent else cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        if (showLabel) {
            Text(
                text = tab.label,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}

/** The Deck brand mark: an accent keycap with a play notch, drawn from the theme accent. */
@Composable
private fun DeckMark() {
    val accent = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    Canvas(modifier = Modifier.size(28.dp)) {
        val r = 9.dp.toPx()
        drawRoundRect(
            brush = Brush.sweepGradient(listOf(accent, lerp(accent, Color.White, 0.45f), accent)),
            cornerRadius = CornerRadius(r, r),
        )
        val w = size.width
        val h = size.height
        val play = Path().apply {
            moveTo(w * 0.36f, h * 0.27f)
            lineTo(w * 0.74f, h * 0.5f)
            lineTo(w * 0.36f, h * 0.73f)
            close()
        }
        drawPath(play, bg)
    }
}

@Composable
private fun DeckStatus(avatarUrl: String?, showBattery: Boolean, onAccount: () -> Unit, dpad: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val clock = rememberDeckClock()
    val battery = rememberDeckBattery()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Steam connection pill (self-gates to signed-in users; tap when offline to retry).
        com.winlator.star.store.SteamConnectionPill()
        Text(
            text = clock,
            color = cs.onSurface,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
        )
        if (showBattery && battery >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (battery <= 20) Icons.Filled.BatteryAlert else Icons.Filled.BatteryFull,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text("$battery%", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
        }
        Box(
            modifier = Modifier
                .deckFocusRing(CircleShape, scaleTo = 1f)
                .clip(CircleShape)
                .dpadFocus(dpad)
                .clickable(onClick = onAccount)
                .padding(2.dp),
        ) {
            AccountAvatar(avatarUrl = avatarUrl, size = 30.dp, fallbackTint = cs.primary)
        }
    }
}

// ── Floating bar (XMB home) ──────────────────────────────────────────────────────────────────────

/**
 * The XMB home's tab line, floating over the XMB in the space it leaves above its game row, like the old XMB's clock
 * line: L1, the tab icons (the current one with its label and a thin accent underline), R1, and on the right the Steam
 * pill, clock, battery and account, small and muted. No background. Touch and L1/R1 only: none of it takes D-pad focus.
 * The other tabs show the same bar at the same place, with their page starting right under it.
 */
@Composable
private fun DeckFloatingBar(
    current: DeckTab,
    tabs: List<DeckTab>,
    avatarUrl: String?,
    portrait: Boolean,
    startInset: Dp,
    endInset: Dp,
    onTab: (DeckTab) -> Unit,
    onCycle: (Int) -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val clock = rememberDeckClock()
    val battery = rememberDeckBattery()
    // Sized with the screen, as the XMB's own clear space above its game row is (its peek clip is 50 of the same units):
    // portrait leaves 34 there, landscape a good deal more.
    val ss = rememberScreenScale()
    val k = if (portrait) 0.8f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ss.dp(6f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ss.dp(16f) + startInset, end = ss.dp(16f) + endInset, top = ss.dp(if (portrait) 2f else 6f))
            .height(ss.dp(40f * k)),
    ) {
        FloatingBumper("L1") { onCycle(-1) }
        tabs.forEach { t ->
            val selected = t == current
            val ink = if (selected) Color.White else Color.White.copy(alpha = 0.55f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ss.dp(6f)),
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .dpadFocus(false)
                    .clickable { onTab(t) }
                    .drawBehind {
                        if (selected) {
                            val barH = 2.dp.toPx()
                            val inset = 8.dp.toPx()
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset(inset, size.height - barH - 2.dp.toPx()),
                                size = Size((size.width - inset * 2).coerceAtLeast(barH), barH),
                                cornerRadius = CornerRadius(barH, barH),
                            )
                        }
                    }
                    .padding(horizontal = ss.dp(8f)),
            ) {
                Icon(t.icon, contentDescription = t.label, tint = ink, modifier = Modifier.size(ss.dp(18f * k)))
                if (selected) {
                    Text(t.label, color = ink, fontSize = ss.sp(15f * k), fontWeight = FontWeight.Light, maxLines = 1)
                }
            }
        }
        FloatingBumper("R1") { onCycle(1) }
        Spacer(Modifier.weight(1f))
        DeckRotateButton(tint = Color(0xFFAAAAAA), size = ss.dp(34f * k), iconSize = ss.dp(18f * k))
        Box(modifier = Modifier.alpha(0.75f)) { com.winlator.star.store.SteamConnectionPill(compact = true) }
        Text(clock, color = Color(0xFFDDDDDD), fontSize = ss.sp(15f * k), fontWeight = FontWeight.Light, maxLines = 1)
        if (battery >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (battery <= 20) Icons.Filled.BatteryAlert else Icons.Filled.BatteryFull,
                    contentDescription = null,
                    tint = Color(0xFFAAAAAA),
                    modifier = Modifier.size(ss.dp(14f)),
                )
                Text("$battery%", color = Color(0xFFAAAAAA), fontSize = ss.sp(13f * k), fontWeight = FontWeight.Light, maxLines = 1)
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .dpadFocus(false)
                .clickable(onClick = onAccount)
                .padding(2.dp)
                .alpha(0.85f),
        ) {
            AccountAvatar(avatarUrl = avatarUrl, size = ss.dp(26f * k), fallbackTint = Color(0xFFDDDDDD))
        }
    }
}

/** An L1/R1 glyph on the floating bar: tapping it moves the tabs as the bumpers do. */
@Composable
private fun FloatingBumper(text: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .dpadFocus(false)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .alpha(0.6f),
    ) {
        DeckGlyph(text, GlyphKind.BUMPER)
    }
}

/** The XMB home's only hint, on every tab: a muted "Y Search" in the bottom-right corner, tappable like the button. */
@Composable
private fun DeckFloatingSearch(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val ss = rememberScreenScale()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ss.dp(6f)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .dpadFocus(false)
            .clickable(onClick = onSearch)
            .padding(horizontal = ss.dp(8f), vertical = ss.dp(4f))
            .alpha(0.75f),
    ) {
        DeckGlyph("Y", GlyphKind.Y)
        Text("Search", color = Color(0xFFDDDDDD), fontSize = ss.sp(14f), fontWeight = FontWeight.Light, maxLines = 1)
    }
}

/** The clock, refreshed every 20 s. */
@Composable
private fun rememberDeckClock(): String {
    val context = LocalContext.current
    val clock by produceState(initialValue = timeNow(context)) {
        while (true) {
            value = timeNow(context)
            delay(20_000L)
        }
    }
    return clock
}

/** Battery percent (-1 when unknown), refreshed every minute. */
@Composable
private fun rememberDeckBattery(): Int {
    val context = LocalContext.current
    val battery by produceState(initialValue = batteryPercent(context)) {
        while (true) {
            value = batteryPercent(context)
            delay(60_000L)
        }
    }
    return battery
}

private fun timeNow(context: Context): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())

private fun batteryPercent(context: Context): Int = runCatching {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}.getOrDefault(-1).let { if (it in 0..100) it else -1 }

// ── Side rails (landscape) ───────────────────────────────────────────────────────────────────────

/** Width of the main side rail. */
private val DECK_RAIL_WIDTH = 68.dp

/**
 * The landscape main navigation: brand mark, L1, the seven tabs, R1, and at the foot Search, the Steam
 * pill, clock, battery and (when there is room) the account. L1/R1 move along it with focus on the tab;
 * Right enters the page. On a short screen (under [DECK_RAIL_SHORT]) the mark, the L1/R1 glyphs (the
 * legend shows them) and the battery go, and the tabs share the height left. The tab list scrolls only
 * when even that does not fit (a very large font scale).
 */
@Composable
private fun DeckSideRail(
    cutoutStart: Dp,
    current: DeckTab,
    tabRequesters: Map<DeckTab, FocusRequester>,
    avatarUrl: String?,
    onTab: (DeckTab) -> Unit,
    onCycle: (Int) -> Unit,
    onTabsFocus: (Boolean) -> Unit,
    onEnterContent: () -> Unit,
    onAccount: () -> Unit,
    onSearch: () -> Unit,
) {
    val line = deckLine()
    BoxWithConstraints(
        modifier = Modifier
            .width(DECK_RAIL_WIDTH + cutoutStart)
            .fillMaxHeight()
            .background(deckGlass())
            .drawBehind { drawRect(line, topLeft = Offset(size.width - 1f, 0f), size = Size(1f, size.height)) }
            .padding(start = cutoutStart),
    ) {
        val roomy = maxHeight >= 560.dp
        val short = maxHeight < DECK_RAIL_SHORT
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
        ) {
            // What the tab list has left after the foot below; on a short screen the tabs are sized from it.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val tabHeight = if (short) ((maxHeight - 12.dp) / DeckTab.entries.size).coerceIn(40.dp, 46.dp) else Dp.Unspecified
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    if (!short) {
                        DeckMark()
                        DeckBumperButton("L1") { onCycle(-1) }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .onFocusChanged { onTabsFocus(it.hasFocus) }
                            // Right leaves the rail for the page itself, not whichever control happens to sit beside the tab.
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown) { onEnterContent(); true } else false
                            },
                    ) {
                        DeckTab.entries.forEach { t ->
                            DeckRailButton(
                                t,
                                selected = t == current,
                                fixedHeight = tabHeight,
                                modifier = Modifier.focusRequester(tabRequesters.getValue(t)),
                            ) { onTab(t) }
                        }
                    }
                    if (!short) DeckBumperButton("R1") { onCycle(1) }
                }
            }
            Spacer(Modifier.height(6.dp))
            DeckSearchButton(onSearch)
            DeckRailStatus(avatarUrl = avatarUrl, showAvatar = roomy, showBattery = !short, onAccount = onAccount)
        }
    }
}

/** Below this rail height the rail drops its mark, L1/R1 and battery, and sizes the tabs to fit. */
private val DECK_RAIL_SHORT = 520.dp

/** An L1/R1 glyph that cycles the tabs when tapped, in a 40dp box so the small glyph is easy to hit. */
@Composable
private fun DeckBumperButton(text: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        DeckGlyph(text, GlyphKind.BUMPER)
    }
}

/**
 * A main-rail tab: icon over a short label; the current one gets the accent fill and an inset bar on its left edge.
 * [fixedHeight] fixes its height (the short rail's share of the space); unspecified, it is at least 44dp.
 */
@Composable
private fun DeckRailButton(tab: DeckTab, selected: Boolean, fixedHeight: Dp = Dp.Unspecified, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val accent = cs.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        modifier = modifier
            .width(60.dp)
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .then(if (fixedHeight != Dp.Unspecified) Modifier.height(fixedHeight) else Modifier.heightIn(min = 44.dp))
            .drawBehind {
                if (selected) {
                    val barW = 3.dp.toPx()
                    val inset = 10.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, inset),
                        size = Size(barW, (size.height - inset * 2).coerceAtLeast(barW)),
                        cornerRadius = CornerRadius(barW, barW),
                    )
                }
            }
            .padding(vertical = if (fixedHeight != Dp.Unspecified) 2.dp else 5.dp, horizontal = 2.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) accent else cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = tab.label,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            fontFamily = NunitoSansFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The rail's foot: Steam pill (signed-in users only), clock, battery (not on a short rail) and, when there is room, the account. */
@Composable
private fun DeckRailStatus(avatarUrl: String?, showAvatar: Boolean, showBattery: Boolean, onAccount: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clock = rememberDeckClock()
    val battery = rememberDeckBattery()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(top = 4.dp, start = 3.dp, end = 3.dp),
    ) {
        com.winlator.star.store.SteamConnectionPill(compact = true)
        Text(clock, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1)
        if (showBattery && battery >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (battery <= 20) Icons.Filled.BatteryAlert else Icons.Filled.BatteryFull,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text("$battery%", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, maxLines = 1)
            }
        }
        if (showAvatar) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .deckFocusRing(CircleShape, scaleTo = 1f)
                    .clip(CircleShape)
                    .clickable(onClick = onAccount)
                    .padding(2.dp),
            ) {
                AccountAvatar(avatarUrl = avatarUrl, size = 28.dp, fallbackTint = cs.primary)
            }
        }
    }
}

/**
 * The page's own tabs as a rail beside the content (Browse / Installed..., Files / Saves...), moved with
 * L2/R2. [compact] is the icon rail with a tiny label, for pages whose screen has its own side list.
 */
@Composable
private fun DeckPageTabRail(
    tabs: List<DeckTabItem>,
    selected: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
    onFocus: (Boolean) -> Unit,
    // false: no fill of its own, so only its divider line separates it from the page (the framed XMB-home layout).
    glass: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val line = deckLine()
    val count = tabs.size
    val width by animateDpAsState(if (compact) 60.dp else 150.dp, label = "deckPageRail")
    Column(
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .then(if (glass) Modifier.background(deckGlass().copy(alpha = 0.45f)) else Modifier)
            .drawBehind { drawRect(line, topLeft = Offset(size.width - 1f, 0f), size = Size(1f, size.height)) }
            .onFocusChanged { onFocus(it.hasFocus) }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 10.dp),
    ) {
        val l2: @Composable () -> Unit = {
            DeckGlyph("L2", GlyphKind.BUMPER, Modifier.clip(RoundedCornerShape(9.dp)).clickable { if (count > 0) onSelect((selected - 1 + count) % count) })
        }
        val r2: @Composable () -> Unit = {
            DeckGlyph("R2", GlyphKind.BUMPER, Modifier.clip(RoundedCornerShape(9.dp)).clickable { if (count > 0) onSelect((selected + 1) % count) })
        }
        if (compact) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) { l2(); r2() }
        } else {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            ) { l2(); r2() }
        }
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            val shape = RoundedCornerShape(if (compact) 12.dp else 10.dp)
            val itemModifier = Modifier
                .deckFocusRing(shape, scaleTo = 1f)
                .clip(shape)
                .background(if (sel) cs.primary else Color.Transparent)
                .clickable { if (!sel) onSelect(i) }
            val tint = if (sel) cs.onPrimary else cs.onSurfaceVariant
            if (compact) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    modifier = itemModifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 2.dp, vertical = 5.dp),
                ) {
                    if (t.icon != null) Icon(t.icon, contentDescription = t.label, tint = tint, modifier = Modifier.size(20.dp))
                    Text(t.label, color = tint, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = itemModifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 12.dp),
                ) {
                    if (t.icon != null) Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Text(
                        text = t.label,
                        color = tint,
                        fontFamily = SoraFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (t.badge != null && t.badge > 0) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(20.dp).background(if (sel) cs.onPrimary else cs.primary, CircleShape),
                        ) {
                            Text("${t.badge}", color = if (sel) cs.primary else cs.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Landscape page header: one slim line with the title, an info button that shows the page's description
 * inline, and the screen's own top-bar actions on the right. A back arrow on sub-pages.
 */
@Composable
private fun DeckTitleLine(
    endInset: Dp,
    title: String,
    description: String?,
    showBack: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val line = deckLine()
    var infoOpen by remember(title) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f)) }
            .padding(start = if (showBack) 4.dp else 18.dp, end = 8.dp + endInset),
    ) {
        if (showBack) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .deckFocusRing(CircleShape, scaleTo = 1f)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title,
            color = cs.onSurface,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .deckFocusRing(CircleShape, scaleTo = 1f)
                    .clip(CircleShape)
                    .background(if (infoOpen) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { infoOpen = !infoOpen }
                    .size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = if (infoOpen) "Hide description" else "About this page",
                    tint = if (infoOpen) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            if (infoOpen) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = description,
                    color = cs.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

// ── Page bar (hosted screens) ────────────────────────────────────────────────────────────────────

/**
 * Title row above a hosted screen: back arrow on sub-pages, the Settings / Appearance switch on the
 * Settings tab, and the screen's own top-bar actions (what the classic top bar shows on the right).
 */
@Composable
private fun DeckPageBar(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    settingsSubTabs: Boolean,
    onSettingsSubTab: (String) -> Unit,
    currentRoute: String,
    actions: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = if (showBack) 6.dp else 20.dp, end = 8.dp),
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
            }
        }
        if (settingsSubTabs) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // "Settings" is the Deck settings editor; the classic Settings screen it hands off to counts as it.
                listOf(DeckSettingsRoutes.APP to "Settings", Screen.Appearance.route to "Appearance").forEach { (route, label) ->
                    val selected = currentRoute == route || (route == DeckSettingsRoutes.APP && currentRoute == Screen.Settings.route)
                    val shape = RoundedCornerShape(12.dp)
                    Text(
                        text = label,
                        color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                        fontFamily = SoraFamily,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = if (selected) 20.sp else 16.sp,
                        modifier = Modifier
                            .deckFocusRing(shape, scaleTo = 1f)
                            .clip(shape)
                            .background(if (selected) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { if (!selected) onSettingsSubTab(route) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        } else {
            Text(
                text = title,
                color = cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

// ── Legend (landscape) and bottom bar (phone) ────────────────────────────────────────────────────

@Composable
private fun DeckLegend(
    thin: Boolean,
    endInset: Dp,
    atHome: Boolean,
    subTabLabel: String?,
    optionsLabel: String?,
    onCycle: () -> Unit,
    onSubCycle: () -> Unit,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    onSearch: () -> Unit,
) {
    val line = deckLine()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        modifier = Modifier
            .fillMaxWidth()
            // Landscape keeps it thin, under the content only.
            .height(if (thin) 40.dp else 42.dp)
            .background(deckGlass())
            .drawBehind { drawRect(line, topLeft = Offset.Zero, size = Size(size.width, 1f)) }
            .padding(start = if (thin) 12.dp else 16.dp, end = (if (thin) 12.dp else 16.dp) + endInset),
    ) {
        LegendItem(listOf("L1" to GlyphKind.BUMPER, "R1" to GlyphKind.BUMPER), "Menu", thin, onCycle)
        if (subTabLabel != null) LegendItem(listOf("L2" to GlyphKind.BUMPER, "R2" to GlyphKind.BUMPER), subTabLabel, thin, onSubCycle)
        LegendItem(listOf("A" to GlyphKind.A), "Select", thin, null)
        if (!atHome) LegendItem(listOf("B" to GlyphKind.B), "Back", thin, onBack)
        if (optionsLabel != null) LegendItem(listOf("X" to GlyphKind.X), optionsLabel, thin, onOptions)
        LegendItem(listOf("Y" to GlyphKind.Y), "Search", thin, onSearch)
    }
}

@Composable
private fun LegendItem(glyphs: List<Pair<String, GlyphKind>>, label: String, thin: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (thin) 5.dp else 6.dp),
        modifier = Modifier
            // A tappable item takes the bar's full height (at least 40dp) to hit; the glyphs and label stay as they are.
            .then(if (onClick != null) Modifier.fillMaxHeight().heightIn(min = 40.dp) else Modifier)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (thin) 6.dp else 8.dp, vertical = if (thin) 3.dp else 5.dp),
    ) {
        glyphs.forEach { (g, kind) -> DeckGlyph(g, kind) }
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = if (thin) 11.5.sp else 12.5.sp, maxLines = 1)
    }
}

@Composable
private fun DeckBottomBar(current: DeckTab, tabs: List<DeckTab>, onTab: (DeckTab) -> Unit, dpad: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val line = deckLine()
    var moreOpen by remember { mutableStateOf(false) }
    val mainTabs = PORTRAIT_MAIN.filter { it in tabs }
    val moreTabs = tabs.filter { it !in PORTRAIT_MAIN }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(deckGlass())
            .drawBehind { drawRect(line, topLeft = Offset.Zero, size = Size(size.width, 1f)) }
            .padding(horizontal = 6.dp),
    ) {
        mainTabs.forEach { t ->
            BottomBarItem(t.icon, t.label, selected = t == current, modifier = Modifier.weight(1f), dpad = dpad) { onTab(t) }
        }
        Box(modifier = Modifier.weight(1f)) {
            BottomBarItem(Icons.Filled.GridView, "More", selected = current in moreTabs, modifier = Modifier.fillMaxWidth(), dpad = dpad) {
                moreOpen = true
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                moreTabs.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label, color = if (t == current) cs.primary else cs.onSurface) },
                        leadingIcon = { Icon(t.icon, contentDescription = null, tint = cs.primary) },
                        onClick = { moreOpen = false; onTab(t) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBarItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, dpad: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .dpadFocus(dpad)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
