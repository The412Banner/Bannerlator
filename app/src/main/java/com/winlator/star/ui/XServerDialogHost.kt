package com.winlator.star.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.net.LanChat
import com.winlator.star.net.LanChatOverlay
import com.winlator.star.ui.dialogs.ActiveWindowsDialog
import com.winlator.star.ui.dialogs.DebugDialogContent
import com.winlator.star.ui.dialogs.InputControlsDialog
import com.winlator.star.ui.dialogs.NewTaskDialog
import com.winlator.star.ui.dialogs.ScreenEffectsDialog
import com.winlator.star.ui.dialogs.VibrationDialog
import com.winlator.star.net.LanMultiplayerDialog
import com.winlator.star.ui.overlays.MagnifierOverlay
import com.winlator.star.ui.overlays.PauseBoxOverlay
import com.winlator.star.ui.theme.WinlatorTheme

fun setupDialogHost(view: ComposeView) {
    view.setContent {
        WinlatorTheme {
            XServerDialogHost()
        }
    }
}

@Composable
fun XServerDialogHost() {
    val state = XServerDialogState
    val activeDialog     by state.activeDialog.collectAsState()
    val magnifierVisible by state.magnifierVisible.collectAsState()
    val paused           by state.paused.collectAsState()

    // Auto-connect the chat socket whenever a LAN room is active (independent of the game/tunnel).
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { LanChat.ensureStarted(ctx) }
    when (activeDialog) {
        XServerDialogState.ActiveDialog.VIBRATION      -> VibrationDialog(state)
        XServerDialogState.ActiveDialog.DEBUG          -> DebugDialogContent(state)
        XServerDialogState.ActiveDialog.INPUT_CONTROLS -> InputControlsDialog(state)
        XServerDialogState.ActiveDialog.SCREEN_EFFECTS -> ScreenEffectsDialog(state)
        XServerDialogState.ActiveDialog.ACTIVE_WINDOWS -> ActiveWindowsDialog(state)
        XServerDialogState.ActiveDialog.NEW_TASK       -> NewTaskDialog(state)
        // Same shared host/join UI as the app, shown as a DIALOG so the running game isn't paused. Consent
        // is obtained via THIS ComposeView's Activity (XServerDisplayActivity) inside LanMultiplayerDialog.
        XServerDialogState.ActiveDialog.LAN            -> LanMultiplayerDialog(onDismiss = { state.dismiss() })
        XServerDialogState.ActiveDialog.NONE           -> Unit
    }

    if (magnifierVisible) MagnifierOverlay(state)

    // Centered pause indicator — above the game surface, shown whenever the guest is frozen
    // (ReShade freeze-frame preview OR a manual Pause). Tap to fully resume.
    if (paused) PauseBoxOverlay(state)

    // Floating, non-modal chat window over the running game (only its own box consumes touches).
    LanChatOverlay()
}
