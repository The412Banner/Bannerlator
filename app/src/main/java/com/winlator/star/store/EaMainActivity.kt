package com.winlator.star.store

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.core.AppOrientation
import com.winlator.star.ui.theme.WinlatorTheme

/**
 * The EA section.
 *
 * Unlike the other stores this is not a shop — EA games arrive through Steam, and nothing is bought
 * or downloaded here. What EA controls is *permission*: whether a copy of an EA-published game
 * sitting in a Steam library is allowed to start. So this screen shows the two things that decide
 * that and are otherwise invisible:
 *
 *  - whether an EA account is signed in and linked to Steam, and
 *  - for each EA title in the Steam library, whether it can currently be launched.
 *
 * The cog mirrors Steam's: link accounts, choose how launches are handled, sign out.
 */
class EaMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppOrientation.apply(this)
        setContent { WinlatorTheme { EaScreen(onBack = { finish() }) } }
    }
}

/** One EA title in the Steam library, with the only status that matters: can it start. */
data class EaTitle(
    val name: String,
    val steamAppId: Int,
    val state: LaunchState,
    val detail: String,
)

enum class LaunchState { READY, NEEDS_SIGN_IN, BLOCKED }

@Composable
private fun EaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val creds = remember { EaCredentialStore.load(ctx) }
    var menuOpen by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(EaLaunchSession.mode(ctx)) }

    val titles = remember { scanEaTitles(ctx, creds.isSignedIn) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("EA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "EA settings",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(if (creds.isLinked) "Linked to Steam" else "Link Steam account")
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        enabled = creds.isSignedIn,
                        onClick = { menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("How launches are handled") },
                        leadingIcon = {
                            Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = { menuOpen = false; showModes = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh licences") },
                        leadingIcon = {
                            Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        enabled = creds.isSignedIn,
                        onClick = { menuOpen = false },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                        enabled = creds.isSignedIn,
                        onClick = { menuOpen = false; EaCredentialStore.clear(ctx) },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (!creds.isSignedIn) SignedOutBanner()

        Text(
            "EA GAMES IN YOUR STEAM LIBRARY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
        )

        if (titles.isEmpty()) {
            Text(
                "No EA-published games found. They appear here once an EA title is installed from your Steam library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(titles) { title -> TitleRow(title) }
            }
        }
    }

    if (showModes) {
        ModeDialog(
            current = mode,
            onPick = { picked ->
                mode = picked
                EaLaunchSession.setMode(ctx, picked)
                showModes = false
            },
            onDismiss = { showModes = false },
        )
    }
}

@Composable
private fun SignedOutBanner() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text("Not signed in to EA", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Signing in lets Bannerlator see which EA games you own and link them to Steam. " +
                "Games that do not need a licence can still launch without it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { }, enabled = false) { Text("Sign in to EA") }
    }
}

@Composable
private fun TitleRow(title: EaTitle) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title.name, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                title.detail,
                style = MaterialTheme.typography.bodySmall,
                color = when (title.state) {
                    LaunchState.BLOCKED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            when (title.state) {
                LaunchState.READY -> "Ready"
                LaunchState.NEEDS_SIGN_IN -> "Sign in"
                LaunchState.BLOCKED -> "Won't run"
            },
            style = MaterialTheme.typography.labelMedium,
            color = when (title.state) {
                LaunchState.BLOCKED -> MaterialTheme.colorScheme.error
                LaunchState.READY -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ModeDialog(
    current: EaLaunchSession.Mode,
    onPick: (EaLaunchSession.Mode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            EaLaunchSession.Mode.SERVE,
            "Handle it ourselves",
            "Bannerlator answers the game directly, so EA Desktop never has to start.",
        ),
        Triple(
            EaLaunchSession.Mode.CAPTURE,
            "Watch and record",
            "EA Desktop still handles the launch. Bannerlator records the exchange so a failure can be explained.",
        ),
        Triple(
            EaLaunchSession.Mode.OFF,
            "Stay out of it",
            "Launch exactly the way it worked before this feature existed.",
        ),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How launches are handled") },
        text = {
            Column {
                options.forEach { (value, label, blurb) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = current == value, onClick = { onPick(value) })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * The EA titles currently visible to us.
 *
 * Reads the shortcuts the launcher already tags as EA rather than asking EA anything, so the list is
 * right with no account and no network — which matters, because the licence path this screen
 * describes is meant to work without either.
 *
 * Titles carrying Javelin anti-cheat are shown as blocked. That is not our licence problem and no
 * amount of signing in changes it: the anti-cheat needs a Windows kernel driver, which cannot exist
 * here. Saying so on this screen is kinder than letting someone discover it at launch.
 */
private fun scanEaTitles(ctx: android.content.Context, signedIn: Boolean): List<EaTitle> = try {
    val manager = com.winlator.star.container.ContainerManager(ctx)
    manager.loadShortcuts()
        .filter { EaSupport.isTagged(it) }
        .map { shortcut ->
            val javelin = shortcut.getExtra(EaSupport.EXTRA_JAVELIN, "") == "1"
            val appId = runCatching {
                EaSupport.resolveSteamAppId(shortcut, EaSupport.installDirOf(shortcut))
            }.getOrDefault(0)
            when {
                javelin -> EaTitle(
                    shortcut.name, appId, LaunchState.BLOCKED,
                    "Javelin anti-cheat — cannot run under this runtime",
                )
                else -> EaTitle(
                    shortcut.name, appId, LaunchState.READY,
                    if (signedIn) "Ready to launch" else "Ready to launch — no EA sign-in needed",
                )
            }
        }
        .sortedBy { it.name.lowercase() }
} catch (t: Throwable) {
    android.util.Log.w("BL_EA", "could not list EA titles", t)
    emptyList()
}
