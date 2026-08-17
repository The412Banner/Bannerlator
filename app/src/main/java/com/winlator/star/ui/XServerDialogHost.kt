package com.winlator.star.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.winlator.star.ui.dialogs.ActiveWindowsDialog
import com.winlator.star.ui.dialogs.CastDialog
import com.winlator.star.ui.dialogs.DebugDialogContent
import com.winlator.star.ui.dialogs.InputControlsDialog
import com.winlator.star.ui.dialogs.NewTaskDialog
import com.winlator.star.ui.dialogs.ScreenEffectsDialog
import com.winlator.star.ui.dialogs.VibrationDialog
import com.winlator.star.ui.overlays.ControllerToastOverlay
import com.winlator.star.ui.overlays.ExternalModeOverlay
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
    val controllerToast  by state.controllerToast.collectAsState()
    val playingOnExternal by state.playingOnExternal.collectAsState()
    val menuOpen by state.menuOpen.collectAsState()
    when (activeDialog) {
        XServerDialogState.ActiveDialog.VIBRATION      -> VibrationDialog(state)
        XServerDialogState.ActiveDialog.DEBUG          -> DebugDialogContent(state)
        XServerDialogState.ActiveDialog.INPUT_CONTROLS -> InputControlsDialog(state)
        XServerDialogState.ActiveDialog.SCREEN_EFFECTS -> ScreenEffectsDialog(state)
        XServerDialogState.ActiveDialog.ACTIVE_WINDOWS -> ActiveWindowsDialog(state)
        XServerDialogState.ActiveDialog.NEW_TASK       -> NewTaskDialog(state)
        XServerDialogState.ActiveDialog.CAST           -> CastDialog(state)
        XServerDialogState.ActiveDialog.NONE           -> Unit
    }

    // On-handheld "game is on the TV" indicator (the phone would otherwise be a black screen).
    // Hidden while the side menu is open so it doesn't overlap the drawer.
    if (playingOnExternal && !menuOpen) ExternalModeOverlay(state)

    if (magnifierVisible) MagnifierOverlay(state)

    // Centered pause indicator — above the game surface, shown whenever the guest is frozen
    // (ReShade freeze-frame preview OR a manual Pause). Tap to fully resume.
    if (paused) PauseBoxOverlay(state)

    // Controller-status toast (P5b): top-right, below the Fusion HUD; non-interactive, auto-dismissing.
    if (controllerToast != null) ControllerToastOverlay(state)
}
