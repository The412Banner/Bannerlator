package com.winlator.star.ui.simple

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.saves.Save
import com.winlator.star.ui.screens.SavesViewModel
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.delay
import java.io.File

/**
 * Winlator's own save slots (Classic's Saves screen) as a Lab page: every save with Edit, Transfer container, Export,
 * Share and Unregister on A / X; Y adds one (a title, its container and the folder inside that container's drive C);
 * Import brings an exported save archive in (Lab's file picker or Android's). Same view model and steps as Classic.
 */
@Composable
internal fun LabWinlatorSavesPage(onBack: () -> Unit, vm: SavesViewModel = viewModel()) {
    val saves by vm.saves.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val context = LocalContext.current
    OnResume { vm.refresh() }

    var newOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Save?>(null) }
    var transferTarget by remember { mutableStateOf<Save?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) importUri = uri }
    val importInAppLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { importUri = it }
    }

    val rows = saves.map { save ->
        MenuEntry(
            key = save.file.absolutePath,
            title = save.getTitle(),
            subtitle = save.container?.getName() ?: "",
            icon = Icons.Filled.Save,
            actions = listOf(
                MenuAction(L("Edit"), { editTarget = save }),
                MenuAction(L("Transfer to another container"), { transferTarget = save }),
                MenuAction(L("Export"), { vm.exportSave(context, save, false) }),
                MenuAction(L("Share"), { vm.exportSave(context, save, true) }),
                MenuAction(L("Unregister"), { vm.removeSave(save) }, danger = true),
            ),
        )
    }
    val import = listOf(
        MenuEntry("browse", L("Browse files"), L("A save archive exported before"), icon = Icons.Filled.FileDownload, onA = {
            importInAppLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, L("Select save archive")))
        }),
        MenuEntry("system", L("Pick via system…"), icon = Icons.Filled.FolderOpen, onA = { importLauncher.launch("*/*") }),
    )
    SimpleMenuPage(
        overline = L("Tools"),
        title = L("Winlator saves"),
        sections = listOf(
            MenuSection("saves", L("Saves"), Icons.Filled.Save, rows, empty = L("No saves yet."), primary = MenuAction(L("New save"), { newOpen = true })),
            MenuSection("import", L("Import"), Icons.Filled.FileDownload, import),
        ),
        onBack = onBack,
        banner = if (isLoading) L("Working…") else null,
    )

    if (newOpen) {
        LabNewSaveDialog(vm, onDismiss = { newOpen = false }) { title, path, container ->
            newOpen = false
            vm.addSave(title, path, container) { ok, msg -> if (!ok) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
    }
    editTarget?.let { save ->
        LabTextPrompt(
            title = L("Edit save"), initial = save.getTitle(), confirm = L("OK"),
            message = L("Original path") + "\n" + save.path,
            onDismiss = { editTarget = null },
        ) { newTitle ->
            editTarget = null
            vm.updateSave(save, newTitle) { ok, msg -> if (!ok) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
    }
    transferTarget?.let { save ->
        val containers = remember { vm.containers }
        SimpleActionSheet(
            L("Transfer \"%1\$s\" to", save.getTitle()),
            containers.map { c ->
                MenuAction(c.getName(), {
                    vm.transferSave(save, c) { ok, msg -> Toast.makeText(context, if (ok) "Transfer complete" else msg, Toast.LENGTH_SHORT).show() }
                })
            }.ifEmpty { listOf(MenuAction(L("No containers"), {})) },
            onDismiss = { transferTarget = null },
        ) { a -> transferTarget = null; a.run() }
    }
    importUri?.let { uri ->
        val containers = remember { vm.containers }
        SimpleActionSheet(
            L("Import save"),
            containers.map { c ->
                MenuAction(c.getName(), {
                    vm.importSave(context, uri, c) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                })
            }.ifEmpty { listOf(MenuAction(L("No containers"), {})) },
            onDismiss = { importUri = null },
            message = L("Select target container:"),
        ) { a -> importUri = null; a.run() }
    }
}

/**
 * Classic's New Save in Lab's look: the title, the container (a Lab list), then the save's folder, picked with Lab's
 * folder picker inside that container's drive C. OK needs all three (Classic's "Fill all fields").
 */
@Composable
private fun LabNewSaveDialog(vm: SavesViewModel, onDismiss: () -> Unit, onConfirm: (String, String, com.winlator.star.container.Container) -> Unit) {
    val context = LocalContext.current
    val containers = remember { vm.containers }
    var title by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var selectedPath by remember { mutableStateOf("") }
    var pickContainer by remember { mutableStateOf(false) }
    val selectedContainer = containers.getOrNull(selectedIndex)
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data)?.let { selectedPath = it }
    }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
    LabDialog(L("New save"), onDismiss) {
        LabDialogHeading(L("Save title"))
        LabField(title, { title = it }, requester = field)
        LabDialogRow(L("Container"), sub = selectedContainer?.getName() ?: L("No containers")) { if (containers.isNotEmpty()) pickContainer = true }
        LabDialogRow(L("Select save path"), sub = selectedPath.ifEmpty { L("Not chosen yet") }) {
            val c = selectedContainer
            if (c != null) {
                val driveC = File(c.getRootDir(), ".wine/drive_c/").absolutePath
                folderPicker.launch(InAppFilePicker.buildDirIntent(context, title = L("Select save path"), initialDir = driveC, driveCPath = driveC))
            } else Toast.makeText(context, "Select a container first", Toast.LENGTH_SHORT).show()
        }
        LabDialogButtons {
            LabButton(L("Cancel"), onClick = onDismiss)
            LabButton(L("OK"), primary = true) {
                val c = selectedContainer
                if (title.isNotBlank() && selectedPath.isNotEmpty() && c != null) onConfirm(title.trim(), selectedPath, c)
                else Toast.makeText(context, "Fill all fields", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (pickContainer) {
        SimpleActionSheet(
            L("Container"),
            containers.mapIndexed { i, c -> MenuAction(if (i == selectedIndex) c.getName() + "  ✓" else c.getName(), { selectedIndex = i }) },
            onDismiss = { pickContainer = false },
        ) { a -> pickContainer = false; a.run() }
    }
}
