package com.winlator.star.store

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The **Friends** tab — a rehome, not a rewrite.
 *
 * The roster ([FriendsListBody]), the friend profile ([FriendProfileScreen]) and the chat
 * ([ChatScreen]) are the screens `SteamFriendsActivity` already ships; this tab composes those same
 * pieces under the storefront's chrome and adds the prototype's All / Online / In-Game chips, which
 * the standalone screen never had.
 *
 * Portrait mirrors the standalone flow: roster → profile → chat, each a full-screen push with back
 * stepping down one level.
 *
 * Landscape is the master-detail the requirement asks for: the roster docks to a ~30% left pane and
 * the selected friend's **profile** fills the right — a friend profile is a read, and reading it
 * shouldn't cost you the list. Tapping Message inside it swaps the right pane to the chat, and back
 * returns to the profile. (`SteamFriendsActivity`'s own [FriendsTwoPane] stays as it was, chat-first;
 * it is a chat screen, this is a browse screen.)
 */
@Composable
fun SteamFriendsTab(
    wide: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val socialEnabled by SteamFriendsStore.socialEnabled.collectAsState()
    val friends by SteamFriendsStore.friends.collectAsState()
    val available = rememberTabFriendsAvailable()

    var filter by remember { mutableStateOf(FriendPresenceFilter.ALL) }
    LaunchedEffect(filter) { StorefrontLog.i(StorefrontLog.FRIENDS, "presence filter -> ${filter.name}") }
    var selected by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    var chatting by remember { mutableStateOf(false) }

    // Pull a fresh roster whenever the session goes live AND the feature is on — the same trigger
    // SteamFriendsRoot uses, so opening this tab behaves exactly like opening the standalone screen.
    LaunchedEffect(available, socialEnabled) {
        StorefrontLog.i(
            StorefrontLog.FRIENDS,
            "state: socialEnabled=$socialEnabled sessionAvailable=$available roster=${friends.size}",
        )
        if (available && socialEnabled) SteamFriendsStore.refresh()
        else if (!socialEnabled) StorefrontLog.i(StorefrontLog.FRIENDS, "feature OFF (opt-in) — showing the off-state")
        else StorefrontLog.w(StorefrontLog.FRIENDS, "no live Steam session — roster cannot refresh")
    }

    // Feature is opt-in and OFF: the standalone off-state, minus its back arrow (the tab bar is the
    // way out here). Enabling flips the shared flow and live-swaps this for the roster.
    if (!socialEnabled) {
        FriendsOffState(onBack = {}, showBackButton = false)
        return
    }

    val closeChat = {
        SteamFriendsStore.closeChat()
        chatting = false
    }

    if (wide) {
        // Back closes the chat first (returning to the profile), then clears the selection.
        BackHandler(enabled = chatting || selected != null) {
            if (chatting) closeChat() else selected = null
        }
        Row(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier.weight(0.30f).fillMaxHeight().focusGroup(),
            ) {
                FriendsTabHeader(total = friends.size, filter = filter, onFilter = { filter = it })
                FriendsListBody(
                    available = available,
                    selectedFriendId = selected?.steamId,
                    showFilter = true,
                    onOpenChat = { selected = it; chatting = false },
                    onOpenProfile = { selected = it; chatting = false },
                    modifier = Modifier.fillMaxSize(),
                    presenceFilter = filter,
                )
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            Box(modifier = Modifier.weight(0.70f).fillMaxHeight().focusGroup()) {
                val friend = selected
                when {
                    friend == null -> EmptyDetailPane()
                    chatting -> ChatScreen(friend = friend, onBack = closeChat, showBackButton = true)
                    else -> FriendDetail(friend, onOpenChat = { chatting = true }, onMessage = onMessage)
                }
            }
        }
    } else {
        val friend = selected
        BackHandler(enabled = friend != null) {
            if (chatting) closeChat() else selected = null
        }
        Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                friend == null -> {
                    FriendsTabHeader(total = friends.size, filter = filter, onFilter = { filter = it })
                    FriendsListBody(
                        available = available,
                        selectedFriendId = null,
                        showFilter = false,
                        onOpenChat = { selected = it; chatting = true },
                        onOpenProfile = { selected = it; chatting = false },
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        presenceFilter = filter,
                    )
                }
                chatting -> ChatScreen(friend = friend, onBack = closeChat, showBackButton = true)
                else -> FriendDetail(
                    friend = friend,
                    onOpenChat = { chatting = true },
                    onMessage = onMessage,
                    onBack = { selected = null },
                )
            }
        }
    }
}

/**
 * The existing [FriendProfileScreen], wired to this tab's navigation. Its own back arrow is
 * repurposed: in landscape it clears the right pane's selection, in portrait it pops to the roster.
 */
@Composable
private fun FriendDetail(
    friend: SteamFriendsStore.SteamFriend,
    onOpenChat: () -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val ctx = LocalContext.current
    FriendProfileScreen(
        friend = friend,
        onBack = onBack,
        onMessage = onOpenChat,
        // Game/lobby invites still aren't wired to the CM — same honest stub the standalone
        // screen shows, routed through the storefront's message bar instead of a Toast.
        onInvite = { onMessage("Game invites are coming soon") },
        onRemove = { SteamFriendsStore.removeFriend(friend.steamId); onBack() },
        onViewOnSteam = {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://steamcommunity.com/profiles/${friend.steamId}")),
                )
            }
        },
    )
}

/** Title + total/online counts, with the prototype's All / Online / In-Game chips beneath. */
@Composable
private fun FriendsTabHeader(
    total: Int,
    filter: FriendPresenceFilter,
    onFilter: (FriendPresenceFilter) -> Unit,
) {
    val friends by SteamFriendsStore.friends.collectAsState()
    val online = friends.count { it.presence != SteamFriendsStore.Presence.OFFLINE }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Friends",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$total total · $online online",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FriendPresenceFilter.values().forEach { f ->
                PresenceFilterChip(
                    label = f.label,
                    selected = filter == f,
                    onClick = { onFilter(f) },
                )
            }
        }
        Spacer(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PresenceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .steamFocusRing(shape)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
    )
}

@Composable
private fun EmptyDetailPane() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Pick a friend to see their profile",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * Live "friends available" flag, re-evaluated on every repository session event — the same helper
 * `SteamFriendsActivity` uses, duplicated here only because that one is private to its file.
 */
@Composable
private fun rememberTabFriendsAvailable(): Boolean {
    var available by remember {
        mutableStateOf(runCatching { SteamFriendsStore.isAvailable() }.getOrDefault(false))
    }
    DisposableEffect(Unit) {
        val repo = runCatching { SteamRepository.getInstance() }.getOrNull()
        val l = SteamRepository.SteamEventListener {
            available = runCatching { SteamFriendsStore.isAvailable() }.getOrDefault(available)
        }
        repo?.addListener(l)
        available = runCatching { SteamFriendsStore.isAvailable() }.getOrDefault(available)
        onDispose { repo?.removeListener(l) }
    }
    return available
}
