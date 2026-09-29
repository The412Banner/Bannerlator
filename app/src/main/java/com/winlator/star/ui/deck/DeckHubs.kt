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
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.HelpSupportDialog
import com.winlator.star.ui.Screen
import com.winlator.star.ui.StorageWidget

/** One tile on a Deck tile grid. */
internal data class HubTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Tools → More: everything the drawer kept below its lists (wrappers, GPU drivers, account, About,
 * Help and Support, storage cards) plus "Run setup again" for the Deck first-run. About and Help are
 * the drawer's own dialogs.
 */
@Composable
internal fun DeckMoreTools(
    onNavigate: (String) -> Unit,
    onMyAccount: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }
    val tools = listOf(
        HubTile("Wrappers", "Manage graphics wrappers", Icons.Filled.Layers) { onNavigate(Screen.Wrappers.route) },
        HubTile("Adrenotools", "GPU drivers for Adreno devices", Icons.Filled.Memory) { onNavigate(Screen.AdrenoTools.route) },
        HubTile("Run setup again", "Walk through the Deck first-run: permissions, theme, stores", Icons.Filled.RestartAlt) { DeckSetup.requestRerun() },
    )
    val more = listOf(
        HubTile("My account", "Sign in to manage your shared configs", Icons.Filled.AccountCircle, onMyAccount),
        HubTile("About", "Version, updates and credits", Icons.Filled.Info, onAbout),
        HubTile("Help and Support", "GitHub, issue tracker and Discord", Icons.Filled.HelpOutline) { showHelp = true },
    )
    DeckTileGrid {
        hubTiles(tools, focusFirst = true)
        hubHeader("About and help")
        hubTiles(more, focusFirst = false)
        hubHeader("Storage")
        item(span = { GridItemSpan(maxLineSpan) }) {
            // The drawer's storage cards (honours the Appearance toggles). They carry their own inset.
            Column { StorageWidget() }
        }
    }
    if (showHelp) {
        HelpSupportDialog(
            onDismiss = { showHelp = false },
            onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        )
    }
}

/** An adaptive grid of [HubTileCard]s with the page gutter. */
@Composable
internal fun DeckTileGrid(content: LazyGridScope.() -> Unit) {
    val compact = deckCompact()
    val gutter = deckGutter()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (compact) 260.dp else 280.dp),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 14.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

internal fun LazyGridScope.hubHeader(label: String) {
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

internal fun LazyGridScope.hubTiles(tiles: List<HubTile>, focusFirst: Boolean) {
    tiles.forEachIndexed { i, tile ->
        item(key = tile.title) { HubTileCard(tile, focusFirst = focusFirst && i == 0) }
    }
}

/** A big icon + title + subtitle tile. [focusFirst] takes the initial focus once composed. */
@Composable
internal fun HubTileCard(tile: HubTile, focusFirst: Boolean) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val requester = remember { FocusRequester() }
    if (focusFirst) DeckEntryFocus { requester.requestFocus() }
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
