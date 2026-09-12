package com.winlator.star.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One-shot, in-process signal for the component-install session-return flow (Tier 2). After an
 * installer-based component install finishes and we've navigated back to the Games screen,
 * [ComponentInstallResume] flips this so the Shortcuts screen re-opens the originating shortcut's
 * settings (which default to the Win Components tab, where the recommended-components chips live) —
 * letting the user install the game's remaining recommendations without hunting for it again.
 *
 * Mirrors [AccountUiBus.openMyAccountRequested]: an in-memory bus bridges the two composables that
 * live in the SAME process (the persistent [com.winlator.star.components.ComponentInstallReturn] store
 * is what bridges the app RESTART). Best-effort — if the Shortcuts screen can't resolve the target it
 * just clears the signal and stays on Games.
 */
object ComponentReturnBus {
    data class ShortcutTarget(val containerId: Int, val shortcutBase: String)

    /** Set by ComponentInstallResume; consumed (and cleared) by the Shortcuts screen. */
    var openShortcutSettings by mutableStateOf<ShortcutTarget?>(null)
}
