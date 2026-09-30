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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.store.SaveManagerScreen
import com.winlator.star.ui.LocalTopBarActions
import com.winlator.star.ui.screens.FileManagerScreen
import com.winlator.star.ui.screens.InputControlsScreen
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ui.screens.contents.ContentsHubScreen
import com.winlator.star.ui.screens.contents.ContentsHubViewModel

/*
 * The Components, Controls and Tools tabs. Each is a Deck header and tab strip around the classic
 * screen's own content, so every install, profile and file action is the one the classic drawer runs.
 */

/**
 * Components: Deck header, then Browse / Installed / Saved archives / Linux runtime on the Deck tab
 * strip (L2/R2). Each tab is the Contents hub's own tab content, hosted without its rail or TabRow,
 * so installs, the install-progress popup and the source manager are unchanged.
 */
@Composable
internal fun DeckComponentsPage() {
    val vm: ContentsHubViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        DeckTabItem("Browse", Icons.Filled.Download),
        DeckTabItem("Installed", Icons.Filled.CheckCircle),
        DeckTabItem("Saved archives", Icons.Filled.Folder),
        DeckTabItem("Linux runtime", Icons.Filled.DeveloperBoard),
    )
    DeckHostedPage(
        icon = Icons.Filled.Extension,
        title = "Components",
        description = "Wine and Proton builds, graphics translation layers, drivers and x86 translators. Install once, use in any container.",
        tabs = tabs,
        selected = tab,
        onSelect = { tab = it },
        tabLabel = "View",
    ) {
        ContentsHubScreen(vm = vm, hostTab = tab)
    }
}

/**
 * Controls: Deck header over the classic Input Controls screen (profiles, on-screen layout editor,
 * controller bindings and the controller test), hosted as it is.
 */
@Composable
internal fun DeckControlsPage() {
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

/**
 * Tools: Files / Saves / Logs / More. Files, Saves and Logs are the File Manager, Save Manager and Log
 * Manager screens hosted in place; More holds the drawer's leftovers and "Run setup again". Unpacking
 * an archive starts from a file's menu in the File Manager, since it needs a picked archive.
 */
@Composable
internal fun DeckToolsPage(
    onNavigate: (String) -> Unit,
    onMyAccount: () -> Unit,
    onAbout: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        DeckTabItem("Files", Icons.Filled.FolderOpen),
        DeckTabItem("Saves", Icons.Filled.Save),
        DeckTabItem("Logs", Icons.Filled.Description),
        DeckTabItem("More", Icons.Filled.GridView),
    )
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
    ) {
        when (tab) {
            0 -> FileManagerScreen()
            1 -> SaveManagerScreen()
            2 -> LogManagerScreen(onClose = { tab = 0 })
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
    content: @Composable () -> Unit,
) {
    if (deckRails()) {
        DeckPageTitle(title, description)
        if (tabs != null) DeckTabStrip(tabs = tabs, selected = selected, onSelect = onSelect, label = tabLabel, compactRail = compactTabs)
        Box(modifier = Modifier.fillMaxSize()) { content() }
        return
    }
    val gutter = deckGutter()
    val compact = deckCompact()
    val topActions = LocalTopBarActions.current.value
    Column(modifier = Modifier.fillMaxSize()) {
        val headerModifier = Modifier.padding(start = gutter, end = gutter, top = 16.dp)
        if (tabs == null) {
            DeckPageHeader(icon, title, if (compact) null else description, headerModifier) { topActions(this) }
        } else {
            DeckPageHeader(icon, title, if (compact) null else description, headerModifier)
        }
        if (tabs != null) {
            Spacer(Modifier.height(14.dp))
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
