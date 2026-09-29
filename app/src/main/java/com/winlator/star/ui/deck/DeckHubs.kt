package com.winlator.star.ui.deck

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.HelpSupportDialog
import com.winlator.star.ui.Screen
import com.winlator.star.ui.StorageWidget
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ui.theme.AppThemeState

/** One tile on a Deck hub page. */
private data class HubTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Stores tab: the same four storefronts the drawer's Stores section opens, as big tiles. Each one
 * starts the store's own activity exactly as the drawer does. Honours Appearance → "Show game stores".
 */
@Composable
internal fun DeckStoresHub(onLaunchStore: (Screen) -> Unit, onOpenAppearance: () -> Unit) {
    val showStores by AppThemeState.showStores.collectAsState()
    val tiles = listOf(
        HubTile("Steam", "Your Steam library, downloads and cloud saves", Icons.Filled.Storefront) { onLaunchStore(Screen.Steam) },
        HubTile("Epic Games", "Your Epic library", Icons.Filled.Storefront) { onLaunchStore(Screen.Epic) },
        HubTile("GOG", "Your GOG library", Icons.Filled.Storefront) { onLaunchStore(Screen.Gog) },
        HubTile("Amazon Games", "Your Amazon library", Icons.Filled.Storefront) { onLaunchStore(Screen.Amazon) },
    )
    DeckHubGrid(
        title = "Stores",
        subtitle = if (showStores) "Sign in, browse and install. Installed games land on Home." else null,
    ) {
        if (showStores) {
            hubTiles(tiles, focusFirst = true)
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Game stores are hidden. Turn on \"Show game stores\" in Appearance to list them here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                    HubTileCard(
                        HubTile("Appearance", "Show or hide the game stores", Icons.Filled.Palette, onOpenAppearance),
                        focusFirst = true,
                    )
                }
            }
        }
    }
}

/**
 * Tools tab: file and save management, logs, wrappers, GPU drivers, plus everything the drawer kept
 * below its lists (account, About, Help and Support, storage cards). Log Manager opens the same full-
 * screen dialog Settings uses; About and Help are the drawer's own dialogs.
 */
@Composable
internal fun DeckToolsHub(
    onNavigate: (String) -> Unit,
    onMyAccount: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    var showLogManager by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val tools = listOf(
        HubTile("File Manager", "Browse drives and add games from folders", Icons.Filled.FolderOpen) { onNavigate(Screen.FileManager.route) },
        HubTile("Save Manager", "Back up, restore and sync game saves", Icons.Filled.Save) { onNavigate(Screen.SaveManager.route) },
        HubTile("Log Manager", "Collect and share logs for bug reports", Icons.Filled.Description) { showLogManager = true },
        HubTile("Wrappers", "Manage graphics wrappers", Icons.Filled.Layers) { onNavigate(Screen.Wrappers.route) },
        HubTile("Adrenotools", "GPU drivers for Adreno devices", Icons.Filled.Memory) { onNavigate(Screen.AdrenoTools.route) },
    )
    val more = listOf(
        HubTile("My account", "Sign in to manage your shared configs", Icons.Filled.AccountCircle, onMyAccount),
        HubTile("About", "Version, updates and credits", Icons.Filled.Info, onAbout),
        HubTile("Help and Support", "GitHub, issue tracker and Discord", Icons.Filled.HelpOutline) { showHelp = true },
    )
    DeckHubGrid(title = "Tools", subtitle = null) {
        hubTiles(tools, focusFirst = true)
        hubHeader("About and help")
        hubTiles(more, focusFirst = false)
        hubHeader("Storage")
        item(span = { GridItemSpan(maxLineSpan) }) {
            // The drawer's storage cards (honours the Appearance toggles). They carry their own inset.
            Column { StorageWidget() }
        }
    }

    if (showLogManager) {
        Dialog(
            onDismissRequest = { showLogManager = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LogManagerScreen(onClose = { showLogManager = false })
        }
    }
    if (showHelp) {
        HelpSupportDialog(
            onDismiss = { showHelp = false },
            onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        )
    }
}

@Composable
private fun DeckHubGrid(title: String, subtitle: String?, content: LazyGridScope.() -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < DECK_COMPACT_WIDTH_DP
    val gutter = if (compact) 16.dp else 24.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (compact) 260.dp else 280.dp),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 18.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp)
            }
        }
        content()
    }
}

private fun LazyGridScope.hubHeader(label: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

private fun LazyGridScope.hubTiles(tiles: List<HubTile>, focusFirst: Boolean) {
    tiles.forEachIndexed { i, tile ->
        item(key = tile.title) { HubTileCard(tile, focusFirst = focusFirst && i == 0) }
    }
}

@Composable
private fun HubTileCard(tile: HubTile, focusFirst: Boolean) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val requester = remember { FocusRequester() }
    if (focusFirst) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .deckFocusRing(shape, scaleTo = 1.03f)
            .clip(shape)
            .background(deckCardFill())
            .border(1.dp, deckLine(), shape)
            .clickable(onClick = tile.onClick)
            .heightIn(min = 76.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.primary.copy(alpha = 0.16f)),
        ) {
            Icon(tile.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(tile.title, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
            Text(
                tile.subtitle,
                color = cs.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
