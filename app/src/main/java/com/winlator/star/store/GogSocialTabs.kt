package com.winlator.star.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.deck.XmbGroupItem
import com.winlator.star.ui.deck.rememberXmbFocusGroup
import com.winlator.star.ui.deck.xmbCardFill
import com.winlator.star.ui.deck.xmbCardLine
import com.winlator.star.ui.deck.xmbGlassCard
import com.winlator.star.ui.deck.xmbInner
import com.winlator.star.ui.deck.xmbOnGlass
import coil.compose.AsyncImage

/**
 * The GOG **Profile** tab, fed by one [GogUserData] fetch. GOG friends are a Galaxy-only roster with
 * no presence and, for nearly everyone, empty — so there is no Friends tab; the count and a small
 * avatar row live here instead.
 */

/** Round avatar with a candidate chain and an initial-letter placeholder. */
@Composable
fun StoreAvatar(candidates: List<String>, name: String, size: androidx.compose.ui.unit.Dp) {
    val urls = remember(candidates) { candidates.filter { it.isNotBlank() }.distinct() }
    var attempt by remember(urls) { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, xmbCardLine(MaterialTheme.colorScheme.outline), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (attempt < urls.size) {
            AsyncImage(
                model = urls[attempt],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { attempt += 1 },
            )
        }
    }
}

@Composable
private fun FriendRow(f: GogUserData.Friend, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .steamFocusRing(shape)
            .xmbGlassCard(shape, xmbCardFill(MaterialTheme.colorScheme.surfaceContainer))
            .border(1.dp, xmbCardLine(MaterialTheme.colorScheme.outline), shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StoreAvatar(GogUserData.avatarCandidates(f.avatar), f.username, 44.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = f.username,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (f.userSince.isNotBlank()) {
                Text(
                    text = "On GOG since ${f.userSince.take(10)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun GogProfileTab(
    profile: GogUserData.Profile?,
    loading: Boolean,
    wide: Boolean,
    username: String,
    libraryCount: Int,
    installedCount: Int,
    onOpenWeb: (url: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = profile?.username?.ifBlank { null } ?: username.ifBlank { "GOG user" }
    val identity: @Composable () -> Unit = {
        // XMB: the whole card is one stop of the D-pad; A enters its two buttons, B comes back to the card.
        val group = rememberXmbFocusGroup()
        XmbGroupItem(group, RoundedCornerShape(12.dp), Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .xmbGlassCard(RoundedCornerShape(12.dp), xmbCardFill(MaterialTheme.colorScheme.surfaceContainer))
                    .border(1.dp, xmbCardLine(MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StoreAvatar(GogUserData.avatarCandidates(profile?.avatar), name, 84.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildList {
                    profile?.country?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                    profile?.galaxyUserId?.takeIf { it.isNotBlank() }?.let { add("Galaxy ID $it") }
                }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoreFilterChip("Account on GOG.com", false, Modifier.xmbInner(group, first = true)) {
                        onOpenWeb("https://www.gog.com/account", "GOG account")
                    }
                    StoreFilterChip(if (loading) "Refreshing…" else "Refresh", false, Modifier.xmbInner(group), onRefresh)
                }
            }
        }
    }
    val stats: @Composable () -> Unit = {
        val tiles = buildList {
            add("Games" to (profile?.ownedGames?.takeIf { it > 0 } ?: libraryCount))
            add("In library here" to libraryCount)
            add("Installed" to installedCount)
            profile?.let { add("Friends" to it.friends.size) }
            profile?.wishlisted?.takeIf { it > 0 }?.let { add("Wishlisted" to it) }
            profile?.ownedMovies?.takeIf { it > 0 }?.let { add("Movies" to it) }
        }
        StatTileGrid(tiles, if (wide) 3 else 2)
    }
    val friendsPeek: @Composable () -> Unit = {
        val friends = profile?.friends.orEmpty()
        if (friends.isNotEmpty()) {
            StoreSectionHeader("GOG friends", "${friends.size} · no presence feed")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                friends.take(if (wide) 8 else 5).forEach { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        StoreAvatar(GogUserData.avatarCandidates(f.avatar), f.username, 48.dp)
                        Text(
                            text = f.username,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (wide) {
        Row(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
            ) { identity() }
            Column(
                modifier = Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
            ) {
                Box(Modifier.padding(horizontal = 14.dp)) { stats() }
                Spacer(Modifier.height(8.dp))
                friendsPeek()
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.padding(14.dp)) { identity() }
            Box(Modifier.padding(horizontal = 14.dp)) { stats() }
            Spacer(Modifier.height(8.dp))
            friendsPeek()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A stat tile: big number over a muted label. Shared by the GOG, Epic and Amazon profile tabs. */
@Composable
fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .xmbGlassCard(shape, xmbCardFill(MaterialTheme.colorScheme.surfaceContainer))
            .border(1.dp, xmbCardLine(MaterialTheme.colorScheme.outline), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "%,d".format(value),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The profile tabs' stat tiles in rows of [cols], the last row padded so the tiles keep one width. */
@Composable
fun StatTileGrid(tiles: List<Pair<String, Int>>, cols: Int) {
    // On the glass the tiles cast a shadow, so they sit further apart.
    val gap = if (xmbOnGlass()) 14.dp else 8.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        tiles.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { (label, n) -> StatTile(label, n, Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
