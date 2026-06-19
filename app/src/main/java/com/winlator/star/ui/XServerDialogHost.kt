package com.winlator.star.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.winlator.star.ui.dialogs.ActiveWindowsDialog
import com.winlator.star.ui.dialogs.DebugDialogContent
import com.winlator.star.ui.dialogs.InputControlsDialog
import com.winlator.star.ui.dialogs.ScreenEffectsDialog
import com.winlator.star.ui.dialogs.VibrationDialog
import com.winlator.star.ui.overlays.MagnifierOverlay
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
    when (activeDialog) {
        XServerDialogState.ActiveDialog.VIBRATION      -> VibrationDialog(state)
        XServerDialogState.ActiveDialog.DEBUG          -> DebugDialogContent(state)
        XServerDialogState.ActiveDialog.INPUT_CONTROLS -> InputControlsDialog(state)
        XServerDialogState.ActiveDialog.SCREEN_EFFECTS -> ScreenEffectsDialog(state)
        XServerDialogState.ActiveDialog.ACTIVE_WINDOWS -> ActiveWindowsDialog(state)
        XServerDialogState.ActiveDialog.NONE           -> Unit
    }

    if (magnifierVisible) MagnifierOverlay(state)
}
