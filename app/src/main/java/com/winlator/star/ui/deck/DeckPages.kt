package com.winlator.star.ui.deck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.store.SaveManagerScreen
import com.winlator.star.ui.LocalTopBarActions
import com.winlator.star.ui.Screen
import com.winlator.star.ui.screens.AdrenoToolsScreen
import com.winlator.star.ui.screens.FileManagerScreen
import com.winlator.star.ui.screens.InputControlsScreen
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ui.screens.WrapperManagerScreen
import com.winlator.star.ui.screens.contents.ContentsHubScreen
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.theme.AppThemeState

/*
 * The Components, Controls and Tools tabs. Each is a Deck header and tab strip around the classic
 * screen's own content, so every install, profile and file action is the one the classic drawer runs.
 */

/**
 * A sub-tab of the Components page that another route asked for: the search (and the classic
 * Wrappers / Adrenotools routes) land on Components, and the page picks the tab up once.
 */
internal object DeckComponentsTabs {
    const val WRAPPERS = 4
    const val ADRENOTOOLS = 5

    /** The Components tab index waiting to be shown, or null. The page clears it once it has switched. */
    var requested by mutableStateOf<Int?>(null)

    /**
     * The Components tab a classic [route] stands for, or null when the route is not one of them. XMB only: the plain Deck has
     * no such tabs (Wrappers and Adrenotools are tiles on Tools > More there, as upstream), so it gets null for every route.
     */
    fun tabFor(route: String): Int? {
        if (!AppThemeState.deckXmbHome.value) return null
        return when (route) {
            Screen.Wrappers.route -> WRAPPERS
            Screen.AdrenoTools.route -> ADRENOTOOLS
            else -> null
        }
    }
}

/**
 * Components: Deck header, then Browse / Installed / Saved archives / Linux runtime on the Deck tab
 * strip (L2/R2). Each is the Contents hub's own tab content, hosted without its rail or TabRow, so
 * installs, the install-progress popup and the source manager are unchanged. XMB only: Wrappers and
 * Adrenotools follow as two more tabs that host the wrapper manager and the GPU driver manager screens
 * (the plain Deck reaches them from Tools > More, as it always has).
 */
@Composable
internal fun DeckComponentsPage() {
    val xmb = rememberXmbHome()
    val vm: ContentsHubViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val wanted = DeckComponentsTabs.requested
    LaunchedEffect(wanted) {
        if (wanted != null) {
            if (xmb) tab = wanted
            DeckComponentsTabs.requested = null
        }
    }
    val tabs = buildList {
        add(DeckTabItem("Browse", Icons.Filled.Download))
        add(DeckTabItem("Installed", Icons.Filled.CheckCircle))
        add(DeckTabItem("Saved archives", Icons.Filled.Folder))
        add(DeckTabItem("Linux runtime", Icons.Filled.DeveloperBoard))
        if (xmb) {
            add(DeckTabItem("Wrappers", Icons.Filled.Layers))
            add(DeckTabItem("Adrenotools", Icons.Filled.Memory))
        }
    }
    DeckHostedPage(
        icon = Icons.Filled.Extension,
        title = "Components",
        description = if (xmb) "Wine and Proton builds, graphics translation layers, wrappers, drivers and x86 translators. Install once, use in any container."
        else "Wine and Proton builds, graphics translation layers, drivers and x86 translators. Install once, use in any container.",
        tabs = tabs,
        selected = tab,
        onSelect = { tab = it },
        tabLabel = "View",
    ) {
        when {
            xmb && tab == DeckComponentsTabs.WRAPPERS -> WrapperManagerScreen()
            xmb && tab == DeckComponentsTabs.ADRENOTOOLS -> AdrenoToolsScreen()
            else -> ContentsHubScreen(vm = vm, hostTab = tab)
        }
    }
}

// Controls: the XMB page is DeckControlsPage in DeckControlsPage.kt (its own tab strip and layout, no header).

/**
 * Controls in the plain Deck (not the XMB interface): Deck header over the classic Input Controls screen (profiles, on-screen layout
 * editor, controller bindings and the controller test), hosted as it is. DeckControlsPage hands over to this outside the XMB.
 */
@Composable
internal fun DeckControlsPageHosted() {
    DeckHostedPage(
        icon = Icons.Filled.SportsEsports,
        title = "Controls",
        description = "On-screen control profiles, physical controller bindings and a controller test.",
        tabs = null,
        selected = 0,
        onSelect = {},
        tabLabel = "Tab",
    ) {
        InputControlsScreen()
    }
}

/** A Tools tab another page asked for (the search's "Logs" entry); the page picks it up once. */
internal object DeckToolsTabs {
    const val LOGS = 2

    /** The Tools tab index waiting to be shown, or null. The page clears it once it has switched. */
    var requested by mutableStateOf<Int?>(null)
}

/**
 * Tools: Files / Saves / Logs (plus More in the plain Deck). Files, Saves and Logs are the File Manager, Save Manager and Log
 * Manager screens hosted in place. In the XMB interface they have no page header of their own (just the Files / Saves / Logs
 * rail or strip), there is no More tab (Wrappers / Adrenotools are Components tabs, the rest lives in Settings), Files uses its
 * slim one-row toolbar, and Logs carries every recording option (a collapsible "Recording options" section) with nothing about
 * them in Settings. The plain Deck keeps the header, the full toolbar, the whole Logs screen and More, which holds the drawer's
 * leftovers and "Run setup again" ([DeckMoreTools]); its three callbacks are the shell's. Unpacking an archive starts from a
 * file's menu in the File Manager, since it needs a picked archive.
 */
@Composable
internal fun DeckToolsPage(
    onNavigate: (String) -> Unit = {},
    onMyAccount: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val xmb = rememberXmbHome()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val wanted = DeckToolsTabs.requested
    LaunchedEffect(wanted) {
        if (wanted != null) {
            tab = wanted
            DeckToolsTabs.requested = null
        }
    }
    val tabs = buildList {
        add(DeckTabItem("Files", Icons.Filled.FolderOpen))
        add(DeckTabItem("Saves", Icons.Filled.Save))
        add(DeckTabItem("Logs", Icons.Filled.Description))
        // Plain Deck only: the drawer's leftovers.
        if (!xmb) add(DeckTabItem("More", Icons.Filled.GridView))
    }
    DeckHostedPage(
        icon = Icons.Filled.Build,
        title = "Tools",
        description = "Files, saves, logs and archive unpacking — everything that used to live in separate drawer entries.",
        tabs = tabs,
        selected = tab,
        onSelect = { tab = it },
        tabLabel = "Tool",
        // Files and Saves bring their own side list, so the page tabs stay an icon rail on every Tools tab.
        compactTabs = true,
        // XMB only: no "Tools" title row (nor its info button), the tabs and the screen under them are all there is. The plain
        // Deck keeps its header.
        showHeader = !xmb,
    ) {
        when (tab) {
            0 -> FileManagerScreen(compactToolbar = xmb)
            1 -> SaveManagerScreen()
            // XMB: every recording option lives here (collapsible), in two-column tiles. Plain Deck: the whole screen as it always was.
            2 -> LogManagerScreen(onClose = { tab = 0 }, inDeckTools = xmb)
            else -> DeckMoreTools(onNavigate = onNavigate, onMyAccount = onMyAccount, onAbout = onAbout)
        }
    }
}

/**
 * The frame the three pages share: header, optional tab strip with the hosted screen's own top-bar
 * actions beside it, and the hosted content filling the remaining height. On a phone the description
 * is dropped so the hosted screen keeps its room. In landscape the shell draws the title line and the
 * tab rail ([compactTabs] = icon rail), so the content gets the whole area.
 */
@Composable
private fun DeckHostedPage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    tabs: List<DeckTabItem>?,
    selected: Int,
    onSelect: (Int) -> Unit,
    tabLabel: String,
    compactTabs: Boolean = false,
    showHeader: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (deckRails()) {
        if (showHeader) DeckPageTitle(title, description)
        if (tabs != null) DeckTabStrip(tabs = tabs, selected = selected, onSelect = onSelect, label = tabLabel, compactRail = compactTabs)
        Box(modifier = Modifier.fillMaxSize()) { content() }
        return
    }
    val gutter = deckGutter()
    val compact = deckCompact()
    val topActions = LocalTopBarActions.current.value
    Column(modifier = Modifier.fillMaxSize()) {
        val headerModifier = Modifier.padding(start = gutter, end = gutter, top = 16.dp)
        if (!showHeader) {
            // No header: the tab strip below is the top of the page.
            Spacer(Modifier.height(10.dp))
        } else if (tabs == null) {
            DeckPageHeader(icon, title, if (compact) null else description, headerModifier) { topActions(this) }
        } else {
            DeckPageHeader(icon, title, if (compact) null else description, headerModifier)
        }
        if (tabs != null) {
            if (showHeader) Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
            ) {
                DeckTabStrip(
                    tabs = tabs,
                    selected = selected,
                    onSelect = onSelect,
                    label = tabLabel,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) { topActions(this) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Bounded height: the hosted screens scroll their own lists.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
