package com.winlator.star.store

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.focusGroup
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
 * **Tap opens the chat; the profile is opt-in from the long-press menu.** Messaging is the primary
 * thing anyone comes to a friends list to do, so it gets the tap; reading a profile is the rarer
 * act and costs a long-press → View profile.
 *
 * Portrait pushes that detail full-screen, so back returns to the roster you came from.
 *
 * Landscape is master-detail: the roster docks to a ~32% left pane and the detail fills the right.
 * That pane has exactly **two** states — [FriendPane.CHAT] (default) and [FriendPane.PROFILE] — and
 * never a third column. Backing out of the profile returns to that friend's chat rather than
 * dropping the selection, so the profile can't strand you away from the conversation; backing out
 * of the chat leaves the detail entirely. The embedded [FriendProfileScreen] is passed
 * `forceSingleColumn` because it lives in a pane, not on a device — a screen must lay itself out
 * from the space it was *given*, never from the device orientation, or it re-splits an already
 * split pane. (`SteamFriendsActivity`'s own [FriendsTwoPane] is unchanged.)
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

    // The right pane (landscape) / the pushed screen (portrait) has exactly TWO states, never three
    // columns: CHAT is the default because messaging is the primary action, and PROFILE is opt-in
    // from the long-press menu. `selected == null` means neither is showing.
    var selected by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    var pane by remember { mutableStateOf(FriendPane.CHAT) }

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

    // TAP = chat. This is the whole point of the tab: FriendRow's onClick already routes to
    // onOpenChat and its long-press to the actions menu (which offers View profile), so the roster
    // needed no change — only these two handlers, which previously both landed on the profile.
    val openChat: (SteamFriendsStore.SteamFriend) -> Unit = {
        selected = it
        pane = FriendPane.CHAT
        StorefrontLog.i(StorefrontLog.FRIENDS, "open CHAT with ${StorefrontLog.sid(it.steamId)}")
    }
    val openProfile: (SteamFriendsStore.SteamFriend) -> Unit = {
        selected = it
        pane = FriendPane.PROFILE
        // The chat stops being visible, so let unread counting resume for this friend.
        SteamFriendsStore.closeChat()
        StorefrontLog.i(StorefrontLog.FRIENDS, "open PROFILE of ${StorefrontLog.sid(it.steamId)}")
    }
    /** Leave the detail entirely (clears the roster selection and the active chat). */
    val clearSelection = {
        SteamFriendsStore.closeChat()
        selected = null
        pane = FriendPane.CHAT
    }
    /** From the profile, back to that friend's chat — the "clear way back" in the same pane. */
    val backToChat = { pane = FriendPane.CHAT }

    if (wide) {
        // PROFILE steps back to CHAT (same pane); CHAT steps out of the detail altogether.
        BackHandler(enabled = selected != null) {
            if (pane == FriendPane.PROFILE) backToChat() else clearSelection()
        }
        Row(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                modifier = Modifier.weight(0.32f).fillMaxHeight().focusGroup(),
            ) {
                FriendsTabHeader(total = friends.size, filter = filter, onFilter = { filter = it })
                FriendsListBody(
                    available = available,
                    selectedFriendId = selected?.steamId,
                    showFilter = true,
                    onOpenChat = openChat,
                    onOpenProfile = openProfile,
                    modifier = Modifier.fillMaxSize(),
                    presenceFilter = filter,
                )
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            Box(modifier = Modifier.weight(0.68f).fillMaxHeight().focusGroup()) {
                val friend = selected
                when {
                    friend == null -> EmptyDetailPane()
                    pane == FriendPane.PROFILE -> FriendDetail(
                        friend = friend,
                        // Both the back arrow and Message return to the chat, so the profile can
                        // never strand the user away from the conversation.
                        onBack = backToChat,
                        onOpenChat = backToChat,
                        onMessage = onMessage,
                    )
                    else -> ChatScreen(friend = friend, onBack = clearSelection, showBackButton = true)
                }
            }
        }
    } else {
        val friend = selected
        // Portrait pushes full-screen, so back from EITHER state returns to the roster.
        BackHandler(enabled = friend != null) { clearSelection() }
        Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                friend == null -> {
                    FriendsTabHeader(total = friends.size, filter = filter, onFilter = { filter = it })
                    FriendsListBody(
                        available = available,
                        selectedFriendId = null,
                        showFilter = false,
                        onOpenChat = openChat,
                        onOpenProfile = openProfile,
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        presenceFilter = filter,
                    )
                }
                pane == FriendPane.PROFILE -> FriendDetail(
                    friend = friend,
                    onBack = clearSelection,
                    onOpenChat = backToChat,
                    onMessage = onMessage,
                )
                else -> ChatScreen(friend = friend, onBack = clearSelection, showBackButton = true)
            }
        }
    }
}

/** The two states the friend detail can be in. Deliberately not a third column — see [SteamFriendsTab]. */
private enum class FriendPane { CHAT, PROFILE }

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
        // We are ALREADY inside a pane. Without this the screen reads the device orientation, still
        // sees "landscape", and splits itself again — three columns and a dead half-panel.
        forceSingleColumn = true,
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
                StoreFilterChip(
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
private fun EmptyDetailPane() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Pick a friend to start chatting",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Long-press a friend for their profile, invites and more.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
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
