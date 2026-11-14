package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import com.winlator.star.contents.ContentProfile
import com.winlator.star.ui.deck.settings.DeckGraphicsState
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.GraphicsRows
import com.winlator.star.ui.deck.settings.SettingsRegistry

/**
 * The Graphics tools of Lab's container, game and All-containers editors (DeckGraphicsDialogs in Lab): the same
 * state and steps as the Deck editors, each one drawn by Lab (download pickers, the wrapper manager, the VEGAS config editor).
 */
@Composable
internal fun LabGraphicsDialogs(state: DeckGraphicsState) {
    val env = state.env
    val source = state.source
    state.sheet?.let { type ->
        LabContentDownloadSheet(
            contentTypes = listOf(type),
            onDismiss = { state.sheet = null; state.closedSheet() },
            onContentChanged = { state.reloadContents() },
            inUseKey = when (type) {
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> state.value(GraphicsRows.ID_VKD3D)
                ContentProfile.ContentType.CONTENT_TYPE_DXVK -> state.value(GraphicsRows.ID_DXVK)
                else -> null
            },
        )
    }
    if (state.vegasSheet) {
        LabVegasDownloadSheet(
            onDismiss = { state.vegasSheet = false; state.closedSheet() },
            onContentChanged = { state.reloadContents() },
        )
    }
    if (state.stockSheet) {
        LabStockConfigDownloadSheet(onDismiss = { state.stockSheet = false; state.closedSheet() })
    }
    if (state.turnipSheet) {
        LabTurnipDownloadSheet(
            onDismiss = { state.turnipSheet = false },
            onDriverInstalled = { state.turnipInstalled() },
        )
    }
    if (state.wrappers) {
        LabWrapperManagerDialog(onDismiss = { state.wrappersClosed() })
    }
    if (state.vegasEditor) {
        val raw = SettingsRegistry.defs.firstOrNull { it.id == GraphicsRows.ID_DX_CONFIG }
        if (raw == null) {
            state.vegasEditor = false
        } else {
            LabVegasConfigEditor(
                isArm64EC = env.arm64ec,
                // The container's own copies of stock configs live in it; a game's and the defaults' in the app's.
                containerRootDir = if (env.scope == EditorScope.CONTAINER) env.vm?.container?.rootDir else null,
                refreshKey = state.refreshKey,
                initialConfig = source.value(raw),
                onConfirm = { config ->
                    state.vegasEditor = false
                    source.set(raw, config)
                },
                onDismiss = { state.vegasEditor = false },
                onDownloadDxvk = { state.fromEditor { state.vegasSheet = true } },
                onDownloadVkd3d = { state.fromEditor { state.sheet = ContentProfile.ContentType.CONTENT_TYPE_VKD3D } },
                onDownloadD7vk = { state.fromEditor { state.sheet = ContentProfile.ContentType.CONTENT_TYPE_D7VK } },
                relaxDxvkFilter = state.value(GraphicsRows.ID_GFX_DRIVER) == "wrapper-compat-bcn",
                onOpenConfigDownload = { state.fromEditor { state.stockSheet = true } },
            )
        }
    }
}
