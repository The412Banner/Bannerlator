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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.ui.theme.WinlatorTheme

/**
 * Standalone host for the Steam friends list + 1:1 text chat. A single, self-contained screen (its
 * own back-stack of "list" ↔ "chat") reachable from three different app surfaces, so it lives in its
 * own Activity rather than being wired into two separate Compose nav graphs. Reads/sends everything
 * through [SteamFriendsStore], which rides the live [SteamRepository] CM session.
 */
class SteamFriendsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)
        setContent {
            WinlatorTheme {
                SteamFriendsRoot(onClose = { finish() })
            }
        }
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
fun SteamFriendsRoot(onClose: () -> Unit) {
    var openChat by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    val available = rememberFriendsAvailable()

    // When the session becomes live (e.g. the user tapped reconnect), pull a fresh roster.
    LaunchedEffect(available) { if (available) SteamFriendsStore.refresh() }

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
    IconButton(onClick = {
        runCatching { ctx.startActivity(Intent(ctx, SteamFriendsActivity::class.java)) }
    }) {
        Icon(
            imageVector = Icons.Filled.People,
            contentDescription = "Steam friends",
            tint = tint,
        )
    }
}
