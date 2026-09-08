package com.winlator.star.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.contentdialog.ContentDialog
import com.winlator.star.core.AppUtils
import com.winlator.star.core.PresetScope
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.util.InAppFilePicker

/**
 * The preset action row — **add · edit · duplicate · delete · export · import** — as it appears
 * under a preset dropdown on the Container and game-shortcut screens.
 *
 * App Settings has had these six actions since the preset editor landed; this is the same set, in
 * the same order, so there is one thing to learn. It differs from Settings' row in two ways, both
 * deliberate:
 *  - **Labels under the icons.** These two screens are far busier than Settings and are reached
 *    while configuring one specific game, so the extra ~8 dp buys clarity where guessing an icon is
 *    most costly.
 *  - **[scope]** decides who an edit belongs to. On Settings that is always the shared preset; here
 *    it is this container or this game, and the values are stored on that owner
 *    ([com.winlator.star.core.PresetOverrides]).
 *
 * Add / duplicate / delete / export / import act on the shared preset LIST — that list is global by
 * nature, so those behave exactly as they do in Settings regardless of scope. Only **edit** (and the
 * Reset inside it) is scope-aware.
 *
 * @param onListChanged the preset list changed (added/removed/imported) — reload the dropdown.
 * @param onValuesChanged this preset's values changed at [scope] — re-read the badge and persist the
 *   owner ([Container.saveData] / [Shortcut.saveData]); the row never writes the file itself so a
 *   screen can batch one save.
 */
@Composable
internal fun PresetEditorRow(
    kind: PresetKind,
    selectedPresetId: String,
    scope: PresetScope,
    container: Container?,
    shortcut: Shortcut?,
    onSelect: (String) -> Unit,
    onListChanged: () -> Unit,
    onValuesChanged: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isFex = kind == PresetKind.FEXCORE
    val prefix = kind.prefix
    var editTarget by remember { mutableStateOf<PresetEditTarget?>(null) }

    fun importFromUri(uri: Uri) {
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (isFex) FEXCorePresetManager.importPreset(context, input)
                else Box64PresetManager.importPreset(prefix, context, input)
            }
            onListChanged()
        } catch (_: Exception) {
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFromUri(uri) }
    val importInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            InAppFilePicker.pickedUri(result.data)?.let { importFromUri(it) }
        }
    }

    val customPrefix = if (isFex) FEXCorePreset.CUSTOM else Box64Preset.CUSTOM
    val isCustom = selectedPresetId.startsWith(customPrefix)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PresetAction(Icons.Default.Add, R.string.preset_action_add) {
            editTarget = PresetEditTarget.New
        }
        PresetAction(Icons.Default.Edit, R.string.preset_action_edit) {
            editTarget = PresetEditTarget.Existing(selectedPresetId)
        }
        PresetAction(Icons.Default.ContentCopy, R.string.preset_action_duplicate) {
            ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_preset) {
                if (isFex) FEXCorePresetManager.duplicatePreset(context, selectedPresetId)
                else Box64PresetManager.duplicatePreset(prefix, context, selectedPresetId)
                onListChanged()
            }
        }
        PresetAction(
            Icons.Default.Delete, R.string.preset_action_delete,
            tint = MaterialTheme.colorScheme.error,
        ) {
            if (isCustom) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset) {
                    if (isFex) FEXCorePresetManager.removePreset(context, selectedPresetId)
                    else Box64PresetManager.removePreset(prefix, context, selectedPresetId)
                    onListChanged()
                }
            } else AppUtils.showToast(context, R.string.you_cannot_remove_this_preset)
        }
        PresetAction(Icons.Default.FileUpload, R.string.preset_action_export) {
            if (isCustom) {
                if (isFex) FEXCorePresetManager.exportPreset(context, selectedPresetId)
                else Box64PresetManager.exportPreset(prefix, context, selectedPresetId)
            } else AppUtils.showToast(context, "Cannot export this preset")
        }
        PresetImportAction(
            onInApp = {
                importInAppLauncher.launch(
                    InAppFilePicker.buildIntent(context, emptyArray(), "Select preset")
                )
            },
            onSystem = { importLauncher.launch(arrayOf("*/*")) },
        )
    }

    editTarget?.let { target ->
        PresetEditDialog(
            kind = kind,
            presetId = (target as? PresetEditTarget.Existing)?.id,
            scope = scope,
            container = container,
            shortcut = shortcut,
            onDismiss = { editTarget = null },
            onSaved = {
                // A NEW preset is a list change and should become the selection; editing an existing
                // one only changed values at this scope.
                if (target is PresetEditTarget.New) onListChanged() else onValuesChanged()
            },
            onCreated = { newId -> onListChanged(); onSelect(newId) },
        )
    }
}

/** One labelled action: icon above, short label under, sharing the row's width evenly. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PresetAction(
    icon: ImageVector,
    labelRes: Int,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val label = androidx.compose.ui.res.stringResource(labelRes)
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Import needs the in-app / system picker choice, so it wraps the shared source menu. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PresetImportAction(
    onInApp: () -> Unit,
    onSystem: () -> Unit,
) {
    val label = androidx.compose.ui.res.stringResource(R.string.preset_action_import)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
    ) {
        ImportSourceIconButton(
            icon = Icons.Default.FileDownload,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            onInApp = onInApp,
            onSystem = onSystem,
        )
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The "✎ CUSTOM" marker shown beside a preset that carries its own values at the scope being viewed
 * — on the closed field and beside the name in the open dropdown. Without it a customised preset is
 * indistinguishable from a stock one, and there would be no way to tell why one game behaves
 * differently from the next.
 */
@Composable
internal fun PresetCustomBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.preset_customised_badge),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}
