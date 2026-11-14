package com.winlator.star.ui.simple

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.winlator.star.R
import com.winlator.star.container.Shortcut
import com.winlator.star.store.UbisoftInstallerDownload
import com.winlator.star.store.UbisoftSupport
import com.winlator.star.ui.screens.ShortcutLauncher

// The launch flow's EA and Ubisoft questions as Lab panels (ShortcutLaunchDialogs shows these in Lab): Classic's text,
// buttons and outcomes; the buttons run the same actions.

/** "Not supported: EA anti-cheat": the game can't run, so no EA setup is started. */
@Composable
internal fun LabEaUnsupportedDialog(name: String, onOk: () -> Unit) {
    LabPanelDialog(
        L("Not supported: EA anti-cheat"),
        onDismiss = onOk,
        buttons = listOf(LabDialogButton(L("OK"), onOk, primary = true)),
    ) {
        LabDialogText(
            L("\"%1\$s\" ships EA Javelin anti-cheat, which needs a Windows kernel driver. It cannot run under Wine on any Android emulator, so Bannerlator won't start the EA setup for it.", name),
        )
    }
}

/** "Set up EA Desktop": one setup session runs EA's installer ([onSetUp]); not closable while it starts. */
@Composable
internal fun LabEaSetupDialog(name: String, busy: Boolean, onSetUp: () -> Unit, onCancel: () -> Unit) {
    LabPanelDialog(
        L("Set up EA Desktop"),
        onDismiss = { if (!busy) onCancel() },
        dismissable = !busy,
        buttons = listOf(
            LabDialogButton(L("Cancel"), onCancel, enabled = !busy),
            LabDialogButton(if (busy) L("Starting…") else L("Set up"), onSetUp, primary = true, enabled = !busy),
        ),
    ) {
        LabDialogText(
            L("\"%1\$s\" is an EA title: it launches through EA Desktop, which isn't installed in this container yet. Bannerlator will open one setup session (wine-mono first if the container lacks it) and run EA's installer — follow its prompts when it shows them. The session closes by itself when the installer finishes and the app comes back. Then launch the game again and sign in to EA when it asks. This happens once per container.", name),
        )
    }
}

/**
 * "Set up Ubisoft Connect": the game's own installer, or the official one downloaded first (with its progress, and
 * Cancel stopping the download); Launch anyway skips it. [onConfirm] is the main button.
 */
@Composable
internal fun LabUbisoftSetupDialog(launcher: ShortcutLauncher, s: Shortcut, onConfirm: () -> Unit) {
    val download = launcher.ubisoftSetupDownload
    val busy = launcher.ubisoftSetupBusy
    val name = s.name ?: ""
    LabPanelDialog(
        stringResource(R.string.ubisoft_setup_title),
        onDismiss = { if (!busy) launcher.closeUbisoftSetup() },
        dismissable = !busy,
        buttons = listOf(
            LabDialogButton(stringResource(R.string.ubisoft_setup_launch_anyway), { launcher.ubisoftLaunchAnyway(s) }, enabled = !busy),
            // While the installer downloads, Cancel aborts the transfer; the dialog closes once it has stopped.
            LabDialogButton(
                stringResource(if (launcher.ubisoftSetupCancelling) R.string.ubisoft_download_cancelling else android.R.string.cancel),
                { if (busy) launcher.cancelUbisoftDownload() else launcher.closeUbisoftSetup() },
                enabled = !busy || (download && !launcher.ubisoftSetupCancelling),
            ),
            LabDialogButton(
                stringResource(
                    when {
                        !busy -> if (download) R.string.ubisoft_download_confirm else R.string.ubisoft_setup_confirm
                        download -> R.string.ubisoft_download_downloading
                        else -> R.string.ubisoft_setup_starting
                    },
                ),
                onConfirm, primary = true, enabled = !busy,
            ),
        ),
    ) {
        val decision = launcher.ubisoftSetupDecision
        LabDialogText(
            if (download) {
                when (decision?.reason) {
                    UbisoftSupport.EpicSetupReason.LEGACY_BUNDLED ->
                        stringResource(R.string.ubisoft_download_legacy_message, name, UbisoftInstallerDownload.APPROX_MB)
                    else -> stringResource(R.string.ubisoft_download_message, name, UbisoftInstallerDownload.APPROX_MB)
                }
            } else stringResource(R.string.ubisoft_setup_message, name),
        )
        if (download && busy) {
            val done = launcher.ubisoftDownloadDone
            val total = launcher.ubisoftDownloadTotal
            if (total > 0L) {
                LabBar((done.toFloat() / total).coerceIn(0f, 1f), Modifier.fillMaxWidth())
                LabDialogText(
                    stringResource(
                        R.string.ubisoft_download_progress,
                        (done / (1024 * 1024)).toInt(), (total / (1024 * 1024)).toInt(), (done * 100 / total).toInt(),
                    ),
                    muted = true,
                )
            } else LabBusyLine(stringResource(R.string.ubisoft_download_connecting))
        }
        launcher.ubisoftSetupError?.let { LabDialogText(it, error = true) }
    }
}
