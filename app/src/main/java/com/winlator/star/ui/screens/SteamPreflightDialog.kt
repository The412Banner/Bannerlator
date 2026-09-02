package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.container.Shortcut
import com.winlator.star.store.SteamGameUpdater
import com.winlator.star.store.SteamSessionManager
import com.winlator.star.store.SteamSessionManager.Step
import com.winlator.star.store.SteamSessionManager.StepState

// Plain-terms help copy for the "?" bubbles (same idea as LaunchMethodSheet's: what it is, one breath).
private const val HELP_PREFLIGHT =
    "Three quick checks before the game opens, so it starts with a working Steam login, your latest " +
        "saves and the current build. Cancel stops it at any point."
private const val HELP_SESSION =
    "Makes sure you're signed in to Steam (and refreshes your login if it's about to expire) before " +
        "the game starts."
private const val HELP_CLOUD =
    "Pulls your newest cloud saves into the game folder first so you don't play on an old save. " +
        "A newer local save is never overwritten."
private const val HELP_UPDATE =
    "Checks whether Steam has a newer build of the game; you choose whether to update before playing."
private const val HELP_NEED_SIGN_IN =
    "Sign in — open the Steam login, then launch again.\n" +
        "Launch with Goldberg — play offline with the stand-in Steam (no online play, no VAC).\n" +
        "Cancel — go back without launching."
private const val HELP_OFFLINE =
    "Retry — try to reach Steam again.\n" +
        "Launch anyway — start the game and let its own Steam client try to sign in.\n" +
        "Goldberg — play offline with the stand-in Steam (no online play, no VAC).\n" +
        "Cancel — go back without launching."
private const val HELP_UPDATE_OFFER =
    "Update — download the newer build now; launch again when it's done.\n" +
        "Launch anyway — play the build you have (real Steam may refuse it online).\n" +
        "Cancel — go back without launching."

/**
 * The SteamLite launch pre-flight ("Getting Steam ready") — a small modal that runs
 * [SteamSessionManager.preflightAsync] for [shortcut] BEFORE the container opens: Steam sign-in →
 * cloud saves → update check, each as a live row with a status, plus a Cancel that always works.
 *
 * Outcomes:
 *  - all green → [onLaunch] (the caller starts `XServerDisplayActivity` with `preflightDone=1`);
 *  - unusable sign-in → "Steam isn't ready" with **Sign in** / **Launch with Goldberg (offline)** /
 *    **Cancel** ([onSignIn] / [onGoldberg] / [onDismiss]);
 *  - no session in time → **Retry** / **Launch anyway** / **Launch with Goldberg** / **Cancel**;
 *  - update available → **Update** ([onUpdate], the existing manual Update pass) / **Launch anyway**.
 *
 * Non-Steam / Goldberg / Raw launches never open this dialog — only the RealSteam pick and
 * remembered RealSteam launches route through it.
 */
@Composable
fun SteamPreflightDialog(
    shortcut: Shortcut,
    request: SteamSessionManager.PreflightRequest,
    onLaunch: () -> Unit,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onGoldberg: () -> Unit,
    onUpdate: (SteamGameUpdater.UpdateStatus) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val states = remember(shortcut) { mutableStateMapOf<Step, Pair<StepState, String>>() }
    // Terminal outcome awaiting a user choice (null = still running / launching).
    var needSignIn by remember(shortcut) { mutableStateOf<String?>(null) }
    var offline by remember(shortcut) { mutableStateOf<String?>(null) }
    var updateOffer by remember(shortcut) { mutableStateOf<SteamGameUpdater.UpdateStatus?>(null) }
    var launching by remember(shortcut) { mutableStateOf(false) }
    // Re-run generation: bumps when the user picks Retry / Launch anyway so a fresh pass starts.
    var run by remember(shortcut) { mutableStateOf(0) }
    var skipSession by remember(shortcut) { mutableStateOf(false) }
    var skipUpdate by remember(shortcut) { mutableStateOf(false) }
    // The active "?" help bubble (null = none). Tapping the same dot again closes it.
    var helpText by remember(shortcut) { mutableStateOf<String?>(null) }
    val toggleHelp: (String) -> Unit = { helpText = if (helpText == it) null else it }

    DisposableEffect(shortcut, run) {
        needSignIn = null; offline = null; updateOffer = null
        val handle = SteamSessionManager.preflightAsync(
            context, request,
            object : SteamSessionManager.PreflightListener {
                override fun onStep(step: Step, state: StepState, text: String) { states[step] = state to text }
                override fun onNeedSignIn(reason: String) { needSignIn = reason }
                override fun onOffline(reason: String) { offline = reason }
                override fun onUpdateAvailable(status: SteamGameUpdater.UpdateStatus) { updateOffer = status }
                override fun onReady() { launching = true; onLaunch() }
                override fun onCancelled() {}
            },
            skipSession = skipSession,
            skipUpdate = skipUpdate,
        )
        onDispose { handle.cancel() }
    }

    val blocked = needSignIn != null || offline != null || updateOffer != null
    OutlinedAlertDialog(
        onDismissRequest = { /* modal — Cancel / a choice closes it */ },
        containerColor = cs.surfaceContainerHigh,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        title = {
            // The title's "?" explains whatever the buttons currently offer (or the pre-flight as a
            // whole while it runs) — one dot instead of one per TextButton, which wouldn't fit.
            val titleHelp = when {
                needSignIn != null -> HELP_NEED_SIGN_IN
                offline != null -> HELP_OFFLINE
                updateOffer != null -> HELP_UPDATE_OFFER
                else -> HELP_PREFLIGHT
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        needSignIn != null -> "Steam isn't ready"
                        offline != null -> "Steam is unreachable"
                        updateOffer != null -> "Update available"
                        launching -> "Launching ${shortcut.name}…"
                        else -> "Getting Steam ready"
                    },
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PreflightHelpDot(highlighted = helpText == titleHelp) { toggleHelp(titleHelp) }
            }
        },
        text = {
            Column {
                Text(
                    shortcut.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                StepRow("Steam sign-in", states[Step.SESSION], helpText == HELP_SESSION) { toggleHelp(HELP_SESSION) }
                StepRow("Cloud saves", states[Step.CLOUD], helpText == HELP_CLOUD) { toggleHelp(HELP_CLOUD) }
                StepRow("Game files", states[Step.UPDATE], helpText == HELP_UPDATE) { toggleHelp(HELP_UPDATE) }
                helpText?.let { tip ->
                    Spacer(Modifier.height(8.dp))
                    PreflightHelpTip(tip) { helpText = null }
                }
                if (!blocked) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = cs.primary,
                        trackColor = cs.surface,
                    )
                }
                val reason = needSignIn ?: offline
                if (reason != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (needSignIn != null)
                            "$reason. Sign in again to play online with SteamLite, or launch offline with Goldberg."
                        else
                            "$reason. You can retry, launch anyway (the game's own Steam client will try to sign in), or launch offline with Goldberg.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                updateOffer?.let { st ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (st.installedBuild > 0L && st.liveBuild > 0L)
                            "Build ${st.installedBuild} → ${st.liveBuild}. Real Steam may refuse an out-of-date build online."
                        else "A newer build of ${shortcut.name} is available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                needSignIn != null -> Row {
                    TextButton(onClick = onGoldberg) { Text("Launch with Goldberg", color = cs.onSurfaceVariant) }
                    TextButton(onClick = onSignIn) { Text("Sign in", color = cs.primary) }
                }
                offline != null -> Row {
                    TextButton(onClick = onGoldberg) { Text("Goldberg", color = cs.onSurfaceVariant) }
                    TextButton(onClick = { skipSession = true; run++ }) { Text("Launch anyway", color = cs.onSurfaceVariant) }
                    TextButton(onClick = { skipSession = false; run++ }) { Text("Retry", color = cs.primary) }
                }
                updateOffer != null -> Row {
                    TextButton(onClick = { skipUpdate = true; skipSession = true; run++ }) { Text("Launch anyway", color = cs.onSurfaceVariant) }
                    TextButton(onClick = { onUpdate(updateOffer!!) }) { Text("Update", color = cs.primary) }
                }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = cs.onSurfaceVariant) }
        },
    )
}

@Composable
private fun StepRow(
    label: String,
    state: Pair<StepState, String>?,
    helpOpen: Boolean,
    onHelp: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state?.first) {
            StepState.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
            StepState.DONE -> Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
            StepState.WARN -> Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.error, modifier = Modifier.size(16.dp))
            StepState.SKIPPED -> Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            StepState.PENDING, null -> Spacer(Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                state?.second ?: "Waiting…",
                style = MaterialTheme.typography.labelSmall,
                color = if (state?.first == StepState.WARN) cs.error else cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        PreflightHelpDot(highlighted = helpOpen, onClick = onHelp)
    }
}

/** The launch popup's corner "?" (LaunchMethodSheet.HelpDot), on the dialog's primary accent. */
@Composable
private fun PreflightHelpDot(highlighted: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(16.dp).clip(CircleShape)
            .background(if (highlighted) cs.primary.copy(alpha = 0.16f) else cs.surfaceVariant)
            .border(1.dp, if (highlighted) cs.primary.copy(alpha = 0.55f) else cs.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "?",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = if (highlighted) cs.primary else cs.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Inline counterpart of the launch popup's floating help tip — sits under the rows; tap to dismiss. */
@Composable
private fun PreflightHelpTip(text: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
    }
}
