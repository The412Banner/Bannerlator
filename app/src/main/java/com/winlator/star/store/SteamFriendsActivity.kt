package com.winlator.star.store

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.theme.WinlatorTheme

/**
 * Standalone host for the Steam friends list + 1:1 text chat. A single, self-contained screen (its
 * own back-stack of "list" ↔ "chat") reachable from three different app surfaces, so it lives in its
 * own Activity rather than being wired into two separate Compose nav graphs. Reads/sends everything
 * through [SteamFriendsStore], which rides the live [SteamRepository] CM session.
 */
class SteamFriendsActivity : ComponentActivity() {

    companion object {
        /** Intent extra (SteamID64 Long): open straight into this friend's chat. 0 / absent = list. */
        const val EXTRA_OPEN_FRIEND = "open_friend_steam_id"
    }

    // The friend to deep-link into, as Compose state so a chat-notification tap that re-delivers via
    // onNewIntent (this Activity is reused, not recreated) re-navigates the running composition.
    private val openFriendId = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)
        SteamFriendsStore.init(this)
        openFriendId.value = intent?.getLongExtra(EXTRA_OPEN_FRIEND, 0L) ?: 0L
        setContent {
            WinlatorTheme {
                SteamFriendsRoot(
                    onClose = { finish() },
                    openFriendId = openFriendId.value,
                    onFriendOpened = { openFriendId.value = 0L },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(EXTRA_OPEN_FRIEND, 0L)
        if (id != 0L) openFriendId.value = id
        SteamFriendsStore.refresh()
    }

    override fun onStart() {
        super.onStart()
        // Seed the list + go online + request fresh presence whenever the screen is shown. No-op when
        // the session isn't live (e.g. a SteamLite game is holding it) — the UI then shows the
        // connect state.
        SteamFriendsStore.refresh()
    }
}

/**
 * Two-pane root: the friends list, or (when a friend is tapped) that friend's chat. Kept as simple
 * local state so there is no navigation dependency to thread through the host Activity.
 */
@Composable
fun SteamFriendsRoot(
    onClose: () -> Unit,
    openFriendId: Long = 0L,
    onFriendOpened: () -> Unit = {},
) {
    var openChat by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    val available = rememberFriendsAvailable()

    // When the session becomes live (e.g. the user tapped reconnect), pull a fresh roster.
    LaunchedEffect(available) { if (available) SteamFriendsStore.refresh() }

    // Deep-link: a chat-notification tap arrives as a SteamID; open that friend's chat directly. This
    // runs the existing openChat → clearUnread → SteamChatNotifier.cancel path, so the tapped
    // notification auto-clears. Resolve once per id (friendById falls back to a placeholder if the
    // roster hasn't published yet); onFriendOpened resets the id so back-navigation isn't hijacked.
    LaunchedEffect(openFriendId) {
        if (openFriendId != 0L) {
            openChat = SteamFriendsStore.friendById(openFriendId)
            onFriendOpened()
        }
    }

    val friend = openChat
    if (friend == null) {
        BackHandler(enabled = true) { onClose() }
        FriendsListScreen(
            available = available,
            onBack = onClose,
            onOpenChat = { openChat = it },
        )
    } else {
        BackHandler(enabled = true) {
            SteamFriendsStore.closeChat()
            openChat = null
        }
        ChatScreen(
            friend = friend,
            onBack = {
                SteamFriendsStore.closeChat()
                openChat = null
            },
        )
    }
}

/**
 * Live "friends available" flag, re-evaluated on every repository session event so the connect/empty
 * state and the roster appear/disappear without a screen restart.
 */
@Composable
private fun rememberFriendsAvailable(): Boolean {
    var available by remember { mutableStateOf(runCatching { SteamFriendsStore.isAvailable() }.getOrDefault(false)) }
    DisposableEffect(Unit) {
        val repo = SteamRepository.getInstance()
        val l = SteamRepository.SteamEventListener {
            available = runCatching { SteamFriendsStore.isAvailable() }.getOrDefault(available)
        }
        repo.addListener(l)
        available = runCatching { SteamFriendsStore.isAvailable() }.getOrDefault(available)
        onDispose { repo.removeListener(l) }
    }
    return available
}

/**
 * Login-gated entry-point icon for the Steam friends screen. Shown ONLY when the user has a Steam
 * session ([SteamPrefs.isLoggedIn]) — the same gate the connection pill uses — and re-evaluates on
 * the repository's event bus so it appears/disappears without an app restart. Dropped into three
 * headers (Steam Library, Games, Containers); [tint] lets each header match its own icon colour.
 */
@Composable
fun SteamFriendsAction(tint: Color = Color.White) {
    val ctx = LocalContext.current
    var loggedIn by remember { mutableStateOf(runCatching { SteamPrefs.isLoggedIn }.getOrDefault(false)) }
    DisposableEffect(Unit) {
        val repo = SteamRepository.getInstance()
        val l = SteamRepository.SteamEventListener { ev ->
            if (ev.startsWith("SteamStatus:") || ev.startsWith("LoggedIn") ||
                ev == "LoggedOut" || ev == "SessionExpired"
            ) {
                loggedIn = runCatching { SteamPrefs.isLoggedIn }.getOrDefault(loggedIn)
            }
        }
        repo.addListener(l)
        loggedIn = runCatching { SteamPrefs.isLoggedIn }.getOrDefault(loggedIn)
        onDispose { repo.removeListener(l) }
    }
    if (!loggedIn) return
    val unread by SteamFriendsStore.unread.collectAsState()
    val incoming by SteamFriendsStore.incomingRequests.collectAsState()
    // The badge covers both unread chats and pending incoming friend requests.
    val total = unread.values.sum() + incoming.size
    Box {
        IconButton(onClick = {
            runCatching { ctx.startActivity(Intent(ctx, SteamFriendsActivity::class.java)) }
        }) {
            Icon(
                imageVector = Icons.Filled.People,
                contentDescription = "Steam friends",
                tint = tint,
            )
        }
        if (total > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .widthIn(min = 16.dp)
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (total > 9) "9+" else total.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
