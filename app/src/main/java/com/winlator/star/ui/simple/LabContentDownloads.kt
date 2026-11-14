package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import com.winlator.star.contents.ContentProfile
import com.winlator.star.ui.screens.ContentDownloadSheet
import com.winlator.star.ui.screens.StockConfigDownloadSheet
import com.winlator.star.ui.screens.VegasDownloadSheet
import com.winlator.star.ui.screens.adrenodownload.AdrenoDriverDownloadSheet

// Lab's download pickers for the editors' download actions (Wine / Proton, DXVK, VKD3D, D7VK, Box64 / WOWBox64,
// FEXCore, Box64EC, VEGAS builds, stock VEGAS configs, Turnip for Wayland). Same signatures as the Classic sheets.

@Composable
internal fun LabContentDownloadSheet(
    contentTypes: List<ContentProfile.ContentType>,
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
    inUseKey: String? = null,
) {
    ContentDownloadSheet(contentTypes, onDismiss, onContentChanged, inUseKey)
}

@Composable
internal fun LabVegasDownloadSheet(onDismiss: () -> Unit, onContentChanged: () -> Unit) {
    VegasDownloadSheet(onDismiss = onDismiss, onContentChanged = onContentChanged)
}

@Composable
internal fun LabStockConfigDownloadSheet(onDismiss: () -> Unit) {
    StockConfigDownloadSheet(onDismiss = onDismiss)
}

@Composable
internal fun LabTurnipDownloadSheet(onDismiss: () -> Unit, onDriverInstalled: (String) -> Unit) {
    AdrenoDriverDownloadSheet(onDismiss = onDismiss, onDriverInstalled = onDriverInstalled)
}
