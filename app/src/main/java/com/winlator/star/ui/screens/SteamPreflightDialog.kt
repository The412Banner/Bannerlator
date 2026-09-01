package com.winlator.star.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.container.Shortcut
import com.winlator.star.store.SteamGameUpdater
import com.winlator.star.store.SteamSessionManager
import com.winlator.star.store.SteamSessionManager.Step
import com.winlator.star.store.SteamSessionManager.StepState

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
            Text(
                when {
                    needSignIn != null -> "Steam isn't ready"
                    offline != null -> "Steam is unreachable"
                    updateOffer != null -> "Update available"
                    launching -> "Launching ${shortcut.name}…"
                    else -> "Getting Steam ready"
                },
                color = cs.onSurface,
            )
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
                StepRow("Steam sign-in", states[Step.SESSION])
                StepRow("Cloud saves", states[Step.CLOUD])
                StepRow("Game files", states[Step.UPDATE])
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
private fun StepRow(label: String, state: Pair<StepState, String>?) {
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
    }
}
