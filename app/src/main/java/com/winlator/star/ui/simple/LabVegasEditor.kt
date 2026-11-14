package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import com.winlator.star.ui.screens.DxvkConfigDialog
import java.io.File

/** The VEGAS config editor of Lab's editors (Graphics › "Edit the VEGAS config file"). Same steps as DxvkConfigDialog. */
@Composable
internal fun LabVegasConfigEditor(
    isArm64EC: Boolean,
    containerRootDir: File?,
    refreshKey: Int,
    initialConfig: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownloadDxvk: () -> Unit,
    onDownloadVkd3d: () -> Unit,
    onDownloadD7vk: () -> Unit,
    relaxDxvkFilter: Boolean,
    onOpenConfigDownload: () -> Unit,
) {
    DxvkConfigDialog(
        isArm64EC = isArm64EC,
        isVegas = true,
        containerRootDir = containerRootDir,
        refreshKey = refreshKey,
        initialConfig = initialConfig,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        onDownloadDxvk = onDownloadDxvk,
        onDownloadVkd3d = onDownloadVkd3d,
        onDownloadD7vk = onDownloadD7vk,
        relaxDxvkFilter = relaxDxvkFilter,
        onOpenConfigDownload = onOpenConfigDownload,
    )
}
