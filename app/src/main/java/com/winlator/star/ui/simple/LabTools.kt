package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.winlator.star.core.LogInventory
import com.winlator.star.ui.screens.LogViewerScreen

/** File manager, file picker, logs and saves (Lab). */
@Suppress("UNUSED_PARAMETER")
internal fun NavGraphBuilder.labToolsRoutes(open: (String) -> Unit, back: () -> Unit) {
}

/**
 * Lab's viewer for one log entry (the Log Manager's logs, a game's "View logs"). Placeholder: the Classic
 * viewer until the Lab one exists; callers keep this signature.
 */
@Composable
internal fun LabLogViewer(entry: LogInventory.Entry, onClose: () -> Unit) {
    LogViewerScreen(entry = entry, onClose = onClose)
}
