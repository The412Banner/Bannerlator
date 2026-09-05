package com.winlator.star.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * The GOG **Friends** and **Profile** tabs, both fed by one [GogUserData] fetch.
 *
 * GOG's payload is a roster with no presence, so the Friends tab lists people (avatar, name,
 * member-since) and says so plainly instead of inventing an "offline" state for everyone.
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
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
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
fun GogFriendsTab(
    profile: GogUserData.Profile?,
    loading: Boolean,
    wide: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val friends = profile?.friends.orEmpty()
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StoreSectionHeader(
            title = "Friends",
            sub = when {
                loading && profile == null -> "Loading…"
                friends.isEmpty() -> null
                else -> "${friends.size} on GOG · no presence feed"
            },
        )
        when {
            profile == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(title = "Loading your friends", body = "Asking GOG for your friends list…")
            }
            profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(
                    title = "Friends unavailable",
                    body = "GOG didn't answer. Check your connection or sign in again.",
                    actionLabel = "Retry",
                    onAction = onRefresh,
                )
            }
            friends.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(
                    title = "No friends yet",
                    body = "Add friends on GOG.com and they will show up here.",
                    actionLabel = "Refresh",
                    onAction = onRefresh,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (wide) {
                    items(friends.chunked(2), key = { it.first().username }) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { f -> FriendRow(f, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(friends, key = { it.username }) { f -> FriendRow(f, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(f: GogUserData.Friend, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .steamFocusRing(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
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
    onOpenFriends: () -> Unit,
    onOpenWeb: (url: String, title: String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = profile?.username?.ifBlank { null } ?: username.ifBlank { "GOG user" }
    val identity: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
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
                StoreFilterChip("Account on GOG.com", false) { onOpenWeb("https://www.gog.com/account", "GOG account") }
                StoreFilterChip(if (loading) "Refreshing…" else "Refresh", false, onRefresh)
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
        val cols = if (wide) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, n) -> StatTile(label, n, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val friendsPeek: @Composable () -> Unit = {
        val friends = profile?.friends.orEmpty()
        if (friends.isNotEmpty()) {
            StoreSectionHeader("Friends", "${friends.size}")
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
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.padding(horizontal = 14.dp)) {
                StoreFilterChip("See all friends", false, onOpenFriends)
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

/** A stat tile: big number over a muted label. Shared by the GOG and Epic profile tabs. */
@Composable
fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
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
