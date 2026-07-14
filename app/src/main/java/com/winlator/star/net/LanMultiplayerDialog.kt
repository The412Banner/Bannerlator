package com.winlator.star.net

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.store.SteamPrefs
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The one polished, reusable host/join UI for LAN-over-internet play (P1.6). Rendered as a DIALOG so it
 * can be shown identically from all three surfaces — the nav-drawer, a game's long-press menu, and the
 * in-game side menu — without launching an Activity (which would pause a running game).
 *
 * Everything is self-contained: it collects [LanSessionState] (so all surfaces stay in lockstep), calls
 * [LanRoomWorker] off the main thread, obtains the one-time system VPN consent via the hosting Activity's
 * ActivityResultRegistry (works from both the main Compose activity and the in-game dialog-host ComposeView),
 * and starts/stops [LanOverlayVpnService]. The running service publishes "up"/"down" back into
 * [LanSessionState], so the code below only ever drives the optimistic Creating / pending transitions.
 *
 * OPTIONAL game context ([gameAppId] / [gameInstallDir] / [gameName]) is supplied only by the per-game
 * long-press surface. When present AND the game is Goldberg-patched ([GoldbergLanMode.isEligible]) a
 * "Goldberg LAN mode" toggle appears; with it on, host/join writes the peer overlay vIP into the game's
 * Goldberg steam_settings so its Steam-LAN discovery goes as directed unicast over the overlay. The
 * nav-drawer and in-game surfaces pass no context (all null) → no toggle, identical behaviour as before.
 */
@Composable
fun LanMultiplayerDialog(
    onDismiss: () -> Unit,
    gameAppId: Int? = null,
    gameInstallDir: String? = null,
    gameName: String? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val session by LanSessionState.session.collectAsState()

    var joinCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Goldberg LAN mode — offered only for a patched game passed in via long-press. Keyed on the appId
    // so the eligibility check + persisted toggle re-seed if this composable is reused for another game.
    val goldbergEligible = remember(gameAppId, gameInstallDir) {
        gameAppId != null && !gameInstallDir.isNullOrBlank() && GoldbergLanMode.isEligible(context, gameAppId)
    }
    var goldbergOn by remember(gameAppId) {
        val initial = if (goldbergEligible && gameAppId != null) {
            SteamPrefs.init(context.applicationContext)
            SteamPrefs.getGoldbergLanEnabled(gameAppId)
        } else {
            false
        }
        mutableStateOf(initial)
    }

    // Drop / remove the peer-vIP custom_broadcasts.txt for the patched game. Both are off-main + no-op
    // safe inside GoldbergLanMode, so we can call them freely from the UI thread.
    fun maybeEnableGoldberg(room: LanRoom) {
        if (goldbergOn && goldbergEligible && gameAppId != null && !gameInstallDir.isNullOrBlank()) {
            GoldbergLanMode.enable(context, gameAppId, gameInstallDir, gameName ?: "", room.role)
        }
    }
    fun maybeDisableGoldberg() {
        if (goldbergEligible && gameAppId != null && !gameInstallDir.isNullOrBlank()) {
            GoldbergLanMode.disable(context, gameAppId, gameInstallDir, gameName ?: "")
        }
    }

    // VPN consent. We register directly on the Activity's ActivityResultRegistry (unique key, unregistered
    // on dispose) rather than rememberLauncherForActivityResult, so this works even in the in-game
    // dialog-host ComposeView where the ActivityResultRegistryOwner composition-local may not be provided.
    // A pending room rides the consent round-trip.
    var pendingRoom by remember { mutableStateOf<LanRoom?>(null) }
    val consentKey = remember { "lan_vpn_consent_${UUID.randomUUID()}" }
    val consentLauncher = remember(activity, consentKey) {
        activity?.activityResultRegistry?.register(
            consentKey,
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val room = pendingRoom
            pendingRoom = null
            if (result.resultCode == Activity.RESULT_OK && room != null) {
                startOverlay(context, room)
            } else {
                LanSessionState.setIdle()
                error = "VPN permission is required to start the LAN overlay."
            }
        }
    }
    DisposableEffect(consentLauncher) {
        onDispose { consentLauncher?.unregister() }
    }
    // If the dialog is dismissed mid-"Creating" (worker call in flight on this dialog's scope, which then
    // cancels), don't leave the shared state stuck spinning — return it to Idle. Hosting/Joined survive.
    DisposableEffect(Unit) {
        onDispose {
            if (LanSessionState.session.value is LanSession.Creating) LanSessionState.setIdle()
        }
    }

    // Ask for VPN consent if needed, then start the overlay. The service flips us to connected=true.
    fun beginOverlay(room: LanRoom) {
        error = null
        LanSessionState.setPending(room)
        val prep = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prep == null) {
            startOverlay(context, room)
            return
        }
        if (consentLauncher == null) {
            LanSessionState.setIdle()
            error = "Can't request VPN permission from here."
            return
        }
        pendingRoom = room
        consentLauncher.launch(prep)
    }

    fun host() {
        error = null
        LanSessionState.setCreating()
        scope.launch {
            // Optional flavor: if signed in, pass the account session so joiners see "Hosted by <username>".
            // Anonymous (no account) => null, unchanged. Sign-in is never required.
            val room = withContext(Dispatchers.IO) {
                val session = com.winlator.star.communityconfigs.AccountManager.current(context)?.session
                LanRoomWorker.host(session)
            }
            if (room == null || room.relay.isBlank()) {
                LanSessionState.setIdle()
                error = "Couldn't create a room. Check your connection and try again."
            } else {
                // Host-before-launch: drop the peer-vIP file now, before the game starts (Goldberg
                // reads custom_broadcasts.txt at startup).
                maybeEnableGoldberg(room)
                beginOverlay(room)
            }
        }
    }

    fun join() {
        val code = joinCode.trim().uppercase()
        if (code.length != 6) { error = "Enter the 6-character code."; return }
        error = null
        LanSessionState.setCreating()
        scope.launch {
            val room = withContext(Dispatchers.IO) { LanRoomWorker.join(code) }
            if (room == null || room.relay.isBlank()) {
                LanSessionState.setIdle()
                error = "Room $code wasn't found — it may be full or expired."
            } else {
                maybeEnableGoldberg(room)
                beginOverlay(room)
            }
        }
    }

    fun stop() {
        val code = LanSessionState.activeCode()
        maybeDisableGoldberg()                     // remove the peer-vIP file we dropped (overlay teardown)
        stopOverlay(context)                       // service.onDestroy → LanSessionState.onOverlayDown()
        LanSessionState.setIdle()                  // also clear an optimistic pending state immediately
        if (code != null) scope.launch { withContext(Dispatchers.IO) { LanRoomWorker.leave(code) } }
        joinCode = ""
        error = null
    }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("LAN Multiplayer")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                }
                // Goldberg LAN mode toggle — only for a patched game, only before hosting (Goldberg reads
                // the file at startup, so the choice must be made pre-launch).
                if (goldbergEligible && session is LanSession.Idle) {
                    GoldbergLanModeToggle(
                        checked = goldbergOn,
                        onChange = { on ->
                            goldbergOn = on
                            gameAppId?.let {
                                SteamPrefs.init(context.applicationContext)
                                SteamPrefs.setGoldbergLanEnabled(it, on)
                            }
                        },
                    )
                }
                when (val s = session) {
                    LanSession.Idle -> IdleContent(
                        joinCode = joinCode,
                        onJoinCodeChange = { joinCode = it.uppercase().take(6); error = null },
                        onHost = ::host,
                        onJoin = ::join,
                    )
                    LanSession.Creating -> BusyContent("Setting up your room…")
                    is LanSession.Hosting -> HostingContent(
                        code = s.code,
                        phase = s.phase,
                        onCopy = {
                            clipboard.setText(AnnotatedString(s.code))
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        },
                        onShare = { shareCode(context, s.code) },
                        onStop = ::stop,
                    )
                    is LanSession.Joined -> JoinedContent(
                        code = s.code,
                        phase = s.phase,
                        hostName = s.hostName,
                        onLeave = ::stop,
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Chat toggles the floating window (open when hidden, close when showing). It carries its own
                // unread badge and NEVER leaves the room — closing the window just hides the UI.
                val chatUnread by LanChat.unread.collectAsState()
                TextButton(onClick = { LanChat.toggleWindow() }) {
                    Text("Chat")
                    if (chatUnread > 0) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            Modifier.size(18.dp).background(Color(0xFFE5484D), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (chatUnread > 9) "9+" else chatUnread.toString(),
                                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun IdleContent(
    joinCode: String,
    onJoinCodeChange: (String) -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
) {
    Text(
        "One of you hosts and shares the code; the other joins with it. Then launch the same game and " +
            "open its LAN / Multiplayer menu.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    AccentButton(text = "Host a game", onClick = onHost)

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Text("  or join  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = joinCode,
        onValueChange = onJoinCodeChange,
        singleLine = true,
        label = { Text("6-character code") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onJoin,
        enabled = joinCode.length == 6,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text("Join game", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GoldbergLanModeToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Goldberg LAN mode",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "For games whose multiplayer uses Steam networking. Turn this on before launching the game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun BusyContent(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(14.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HostingContent(
    code: String,
    phase: LanPhase,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onStop: () -> Unit,
) {
    Text(
        "Share this code with your friend:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    CodeCard(code)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Copy")
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share")
        }
    }
    Spacer(Modifier.height(14.dp))
    StatusLine(phase = phase)
    Spacer(Modifier.height(14.dp))
    StopButton(text = "Stop hosting", onClick = onStop)
}

@Composable
private fun JoinedContent(code: String, phase: LanPhase, hostName: String?, onLeave: () -> Unit) {
    Text(
        "Joined room",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    CodeCard(code)
    // "Hosted by <username>" only when the host was signed in; anonymous hosts show nothing.
    if (!hostName.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Hosted by $hostName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(14.dp))
    StatusLine(phase = phase)
    Spacer(Modifier.height(14.dp))
    StopButton(text = "Leave game", onClick = onLeave)
}

@Composable
private fun CodeCard(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 8.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusLine(phase: LanPhase) {
    val connected = phase == LanPhase.CONNECTED
    val text = when (phase) {
        LanPhase.CONNECTING -> "Starting the overlay…"
        LanPhase.WAITING -> "Waiting for player…"
        LanPhase.CONNECTED -> "✓ Player connected — launch your game and open its LAN / Multiplayer menu."
    }
    val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!connected) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Compact status pill for the Games top app bar. Hidden entirely when Idle; otherwise a small coloured
 * chip — blue "connecting", amber "waiting", green "connected" — that opens [LanMultiplayerDialog] on tap.
 * Reads [LanSessionState] so it stays in lockstep with every other surface.
 */
@Composable
fun LanStatusPill(onClick: () -> Unit) {
    val session by LanSessionState.session.collectAsState()
    val phase = session.phaseOrNull() ?: return
    val (color, label) = when (phase) {
        LanPhase.CONNECTING -> Color(0xFF3B82F6) to "LAN: connecting"
        LanPhase.WAITING -> Color(0xFFF59E0B) to "LAN: waiting"
        LanPhase.CONNECTED -> Color(0xFF22C55E) to "LAN: connected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AccentButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StopButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

// ── plumbing ──────────────────────────────────────────────────────────────────────────

private fun startOverlay(context: Context, room: LanRoom) {
    val i = Intent(context, LanOverlayVpnService::class.java).apply {
        action = "START"
        putExtra(LanOverlayVpnService.EXTRA_RELAY, room.relay)
        putExtra(LanOverlayVpnService.EXTRA_PORT, room.port)
        putExtra(LanOverlayVpnService.EXTRA_ROOM, room.room)
        putExtra(LanOverlayVpnService.EXTRA_ROLE, room.role)
    }
    context.startService(i)
}

private fun stopOverlay(context: Context) {
    context.startService(Intent(context, LanOverlayVpnService::class.java).setAction("STOP"))
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join my Bannerlator LAN game")
        putExtra(Intent.EXTRA_TEXT, "Join my Bannerlator LAN game — enter code $code in LAN Multiplayer.")
    }
    context.startActivity(Intent.createChooser(send, "Share code"))
}

/** Unwrap the (possibly themed) Compose context to the hosting ComponentActivity. */
private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}
