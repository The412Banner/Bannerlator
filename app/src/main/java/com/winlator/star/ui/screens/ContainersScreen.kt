package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.widget.Toast
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import java.io.File
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.winlator.star.ui.LocalTopBarActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import com.winlator.star.R
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.XrActivity
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GameSaveBackup
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.core.SaveLocator
import com.winlator.star.core.StringUtils
import com.winlator.star.store.UninstallResultBar
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.SurfaceVariant as SurfaceVariantColor
import com.winlator.star.xenvironment.ImageFs

@Composable
fun ContainersScreen(
    onNavigateToDetail: (containerId: Int?) -> Unit,
    vm: ContainersViewModel = viewModel(),
) {
    val containers by vm.containers.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity

    // Surface one-shot VM messages (e.g. duplicate success/failure) as a Toast.
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.messageShown()
        }
    }

    // Refresh list whenever this screen resumes (e.g. returning from ContainerDetail)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Confirm-dialog state
    var confirmDialog by remember { mutableStateOf<ConfirmAction?>(null) }
    var storageInfoContainer by remember { mutableStateOf<Container?>(null) }
    var showImportPicker by remember { mutableStateOf(false) }

    // Backup / Restore game save flow (see SaveFlow). The engine posts its result on the main
    // thread, so we just flip these bits of Compose state as the flow advances.
    var saveFlow by remember { mutableStateOf<SaveFlow?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestoreContainer by remember { mutableStateOf<Container?>(null) }

    // Restore a GameHub backup .zip. Shared for both the in-app picker (file:// path) and SAF.
    fun handleRestoreUri(uri: Uri?) {
        val target = pendingRestoreContainer
        pendingRestoreContainer = null
        if (uri != null && target != null) {
            saveFlow = SaveFlow.Confirm(target, uri, GameSaveBackup.gameNameFromUri(context, uri))
        }
    }

    // System SAF picker (secondary).
    val restorePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> handleRestoreUri(uri) }

    // Built-in in-app file picker (primary).
    val restoreInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleRestoreUri(
            if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data) else null
        )
    }

    val topBarActions = LocalTopBarActions.current
    // LaunchedEffect — not SideEffect — so this runs in the same dispatcher queue as
    // MainActivity's route-change clear (parent enqueues first, we enqueue second and
    // run after). A SideEffect would set during commit and the parent's post-commit
    // clear would steamroll it on first navigation to this screen.
    LaunchedEffect(Unit) {
        topBarActions.value = {
            IconButton(onClick = { showImportPicker = true }) {
                Icon(Icons.Filled.FileDownload, contentDescription = "Import container", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (containers.isEmpty() && !isLoading) {
            Text(
                text = "No containers yet. Tap + to create one.",
                color = OnSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(containers, key = { it.id }) { container ->
                    ContainerItem(
                        container = container,
                        onRun = {
                            if (!XrActivity.isEnabled(context)) {
                                val intent = Intent(context, XServerDisplayActivity::class.java)
                                intent.putExtra("container_id", container.id)
                                context.startActivity(intent)
                            } else {
                                XrActivity.openIntent(activity, container.id, null)
                            }
                        },
                        onEdit = { onNavigateToDetail(container.id) },
                        onDuplicate = {
                            confirmDialog = ConfirmAction.Duplicate(container)
                        },
                        onRemove = {
                            confirmDialog = ConfirmAction.Remove(container)
                        },
                        onExport = {
                            vm.exportContainer(container) { path ->
                                val msg = if (path != null)
                                    "Exported to $path"
                                else
                                    "Export failed or already exists"
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        onInfo = { storageInfoContainer = container },
                        onBackupRestore = { saveFlow = SaveFlow.Fork(container) },
                    )
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = {
                if (ImageFs.find(context).isValid()) onNavigateToDetail(null)
            },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Add container", tint = MaterialTheme.colorScheme.onPrimary)
        }

        // Loading overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // Themed, auto-dismissing result bar for the backup/restore flow (avoids the
        // black-box system Toast on this ROM). Lives inside this full-screen Box so it
        // overlays the list at the bottom.
        resultMessage?.let { msg ->
            UninstallResultBar(message = msg, onTimeout = { resultMessage = null })
        }
        } // end inner Box(weight)
    } // end Column

    // Import picker dialog
    if (showImportPicker) {
        val backups = remember { vm.availableBackups() }
        AlertDialog(
            onDismissRequest = { showImportPicker = false },
            title = { Text("Import Container") },
            text = {
                if (backups.isEmpty()) {
                    Text("No exported containers found in Downloads/Winlator/Backups/Containers/.")
                } else {
                    androidx.compose.foundation.layout.Column {
                        backups.forEach { dir ->
                            TextButton(
                                onClick = {
                                    showImportPicker = false
                                    vm.importContainer(dir) {
                                        Toast.makeText(context, "Container imported: ${dir.name}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(dir.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportPicker = false }) { Text("Cancel") }
            },
        )
    }

    // Confirm dialogs
    confirmDialog?.let { action ->
        when (action) {
            is ConfirmAction.Duplicate -> {
                AlertDialog(
                    onDismissRequest = { confirmDialog = null },
                    title = { Text("Duplicate container?") },
                    text = { Text("Duplicate \"${action.container.name}\"?") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDialog = null
                            vm.duplicate(action.container) {}
                        }) { Text("Duplicate") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDialog = null }) { Text("Cancel") }
                    },
                )
            }
            is ConfirmAction.Remove -> {
                AlertDialog(
                    onDismissRequest = { confirmDialog = null },
                    title = { Text("Remove container?") },
                    text = { Text("Remove \"${action.container.name}\" permanently?") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDialog = null
                            vm.remove(action.container, context) {}
                        }) { Text("Remove") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDialog = null }) { Text("Cancel") }
                    },
                )
            }
        }
    }

    // Storage info dialog
    storageInfoContainer?.let { container ->
        StorageInfoDialog(container = container, onDismiss = { storageInfoContainer = null })
    }

    // ---- Backup / Restore game save flow ----
    when (val flow = saveFlow) {
        null -> {}
        is SaveFlow.Fork -> AlertDialog(
            onDismissRequest = { saveFlow = null },
            title = { Text("Game saves") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(flow.container.name, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    SaveFlowCaution()
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { saveFlow = SaveFlow.BackupScope(flow.container) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back up this save", modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = { saveFlow = SaveFlow.RestoreSource(flow.container) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore a save", modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { saveFlow = null }) { Text("Cancel") } },
        )
        is SaveFlow.RestoreSource -> AlertDialog(
            onDismissRequest = { saveFlow = null },
            title = { Text("Restore a save") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Choose the backup source.", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = {
                            pendingRestoreContainer = flow.container
                            saveFlow = null
                            restoreInAppLauncher.launch(
                                InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, "Select backup (.zip)")
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("GameHub backup (.zip)", modifier = Modifier.weight(1f)) }
                    TextButton(
                        onClick = {
                            pendingRestoreContainer = flow.container
                            saveFlow = null
                            restorePickerLauncher.launch("application/zip")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pick via system…", modifier = Modifier.weight(1f)) }
                }
            },
            confirmButton = { TextButton(onClick = { saveFlow = SaveFlow.Fork(flow.container) }) { Text("Back") } },
        )
        is SaveFlow.Confirm -> AlertDialog(
            onDismissRequest = { saveFlow = null },
            title = { Text("Restore game save?") },
            text = {
                Text(
                    "Restore the GameHub backup of \"${flow.gameName}\" into container " +
                        "\"${flow.container.name}\"?\n\nThis may overwrite existing save data."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = flow.container
                    val uri = flow.uri
                    val name = flow.gameName
                    saveFlow = null
                    busyMessage = "Restoring $name…"
                    GameSaveBackup.restore(context, uri, c) { r ->
                        busyMessage = null
                        resultMessage = if (r.ok) "Restored ${r.filesWritten} files to \"${c.name}\""
                        else "Restore failed: ${r.error ?: "unknown error"}"
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { saveFlow = null }) { Text("Cancel") } },
        )
        is SaveFlow.BackupScope -> AlertDialog(
            onDismissRequest = { saveFlow = null },
            title = { Text(stringResource(R.string.save_backup_scope_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.save_backup_scope_prompt), color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = { saveFlow = SaveFlow.BackupFormat(flow.container, null, flow.container.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.save_backup_scope_whole), modifier = Modifier.weight(1f)) }
                    TextButton(
                        onClick = { saveFlow = SaveFlow.GamePicker(flow.container) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${stringResource(R.string.save_backup_scope_game)} ▸", modifier = Modifier.weight(1f)) }
                }
            },
            confirmButton = { TextButton(onClick = { saveFlow = SaveFlow.Fork(flow.container) }) { Text("Back") } },
        )
        is SaveFlow.GamePicker -> {
            val shortcuts = remember(flow.container.id) { vm.shortcutsFor(flow.container) }
            AlertDialog(
                onDismissRequest = { saveFlow = null },
                title = { Text(stringResource(R.string.save_backup_pick_game_title)) },
                text = {
                    if (shortcuts.isEmpty()) {
                        Text(stringResource(R.string.save_backup_pick_game_empty), color = OnSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(shortcuts, key = { it.file.path }) { sc ->
                                GamePickerRow(sc) {
                                    saveFlow = SaveFlow.GameSaves(
                                        container = flow.container,
                                        gameName = sc.name,
                                        gameKey = SaveLocator.gameKey(sc.wmClass, sc.file.name),
                                        exePath = sc.path ?: "",
                                        wmClass = sc.wmClass ?: "",
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { saveFlow = SaveFlow.BackupScope(flow.container) }) { Text("Back") } },
            )
        }
        is SaveFlow.GameSaves -> GameSavesDialog(
            container = flow.container,
            gameName = flow.gameName,
            gameKey = flow.gameKey,
            exePath = flow.exePath,
            wmClass = flow.wmClass,
            onBack = { saveFlow = SaveFlow.GamePicker(flow.container) },
            onCancel = { saveFlow = null },
            onNext = { roots ->
                saveFlow = SaveFlow.BackupFormat(flow.container, roots, flow.gameName)
            },
        )
        is SaveFlow.BackupFormat -> AlertDialog(
            onDismissRequest = { saveFlow = null },
            title = { Text(stringResource(R.string.save_backup_format_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.save_backup_format_prompt), color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    BackupFormatOption(
                        title = stringResource(R.string.save_backup_format_gamehub),
                        subtitle = stringResource(R.string.save_backup_format_gamehub_sub),
                    ) { runScopedBackup(context, flow, GameSaveBackup.BackupLayout.GAMEHUB, { saveFlow = null }, { busyMessage = it }, { resultMessage = it }) }
                    BackupFormatOption(
                        title = stringResource(R.string.save_backup_format_winlator),
                        subtitle = stringResource(R.string.save_backup_format_winlator_sub),
                    ) { runScopedBackup(context, flow, GameSaveBackup.BackupLayout.WINLATOR, { saveFlow = null }, { busyMessage = it }, { resultMessage = it }) }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveFlow = SaveFlow.BackupScope(flow.container) }) { Text("Back") }
            },
        )
    }

    busyMessage?.let { SaveFlowProgressDialog(message = it) }
}

@Composable
private fun ContainerItem(
    container: Container,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onExport: () -> Unit,
    onInfo: () -> Unit,
    onBackupRestore: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // Resolved component metadata (same theme as the Shortcuts game cards).
    val (dxvkVersion, vkd3dVersion) = parseDxwrapperConfig(container.getDXWrapperConfig())
    val driverCfg = container.getGraphicsDriverConfig()
    val driverLabel = if (driverCfg.isNotEmpty()) GraphicsDriverConfigDialog.getVersion(driverCfg) else ""
    val rendererLabel = rendererLabelOf(container.renderer)
    val frameGenLabel = frameGenLabelOf(container.frameGenEngine)
    val backendLabel = run {
        val id = container.emulator
        LocalContext.current.resources.getStringArray(R.array.emulator_entries)
            .firstOrNull { StringUtils.parseIdentifier(it) == id } ?: ""
    }
    val subtitle = listOf(container.wineVersion, container.screenSize)
        .filter { it.isNotEmpty() }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // Poster tile (matches the Shortcuts cards); containers have no art, so the
            // container glyph is centered in the framed tile.
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceVariantColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.icon_menu_container),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))

            // Info column: name, wineVersion · resolution subtitle, then the shared spec rows.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = container.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SpecChipRows(
                    rendererLabel = rendererLabel,
                    dxvkVersion = dxvkVersion,
                    frameGenLabel = frameGenLabel,
                    driverLabel = driverLabel,
                    vkd3dVersion = vkd3dVersion,
                    backendLabel = backendLabel,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Play button (moved before settings)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onRun),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.icon_popup_menu_run),
                    contentDescription = "Run",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Settings button
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = OnSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.outlinedMenuCard(),
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menuExpanded = false; onDuplicate() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuExpanded = false; onRemove() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text("Export") },
                        leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                        onClick = { menuExpanded = false; onExport() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text("Backup / Restore save") },
                        leadingIcon = { Icon(Icons.Filled.SettingsBackupRestore, null) },
                        onClick = { menuExpanded = false; onBackupRestore() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text("Info") },
                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { menuExpanded = false; onInfo() },
                    )
                }
            }
        }
    }
}

private sealed class ConfirmAction {
    data class Duplicate(val container: Container) : ConfirmAction()
    data class Remove(val container: Container) : ConfirmAction()
}

/** Steps of the Backup / Restore game-save flow launched from a container's overflow menu. */
private sealed class SaveFlow {
    /** Direction picker: back up vs restore. */
    data class Fork(val container: Container) : SaveFlow()
    /** Back up → scope picker: whole container vs a specific game. */
    data class BackupScope(val container: Container) : SaveFlow()
    /** Back up → pick which installed game to scope to. */
    data class GamePicker(val container: Container) : SaveFlow()
    /** Back up → discover + confirm the chosen game's save folders. */
    data class GameSaves(
        val container: Container,
        val gameName: String,
        val gameKey: String,
        val exePath: String,
        val wmClass: String,
    ) : SaveFlow()
    /** Restore → choose a backup source (GameHub backup today; more later). */
    data class RestoreSource(val container: Container) : SaveFlow()
    /** Restore → a .zip has been picked; confirm before overwriting. */
    data class Confirm(val container: Container, val uri: android.net.Uri, val gameName: String) : SaveFlow()
    /**
     * Back up → choose the output layout. [roots] == null is a whole-container backup; otherwise
     * the profile-relative subtrees to include. [gameName] names the resulting zip.
     */
    data class BackupFormat(
        val container: Container,
        val roots: List<String>?,
        val gameName: String?,
    ) : SaveFlow()
}

/** Fires a scoped (or whole-container) backup off the UI thread and routes the result to the bars. */
private fun runScopedBackup(
    context: android.content.Context,
    flow: SaveFlow.BackupFormat,
    layout: GameSaveBackup.BackupLayout,
    dismiss: () -> Unit,
    setBusy: (String?) -> Unit,
    setResult: (String?) -> Unit,
) {
    val name = flow.gameName ?: flow.container.name
    dismiss()
    setBusy("Backing up $name…")
    GameSaveBackup.backup(context, flow.container, flow.roots, flow.gameName, layout) { r ->
        setBusy(null)
        setResult(
            if (r.ok) "Saved ${r.fileCount} files → ${r.path?.substringAfterLast('/')}"
            else "Backup failed: ${r.error ?: "unknown error"}"
        )
    }
}

/** One selectable row in the game picker: cover art (fallback icon) + game name. */
@Composable
private fun GamePickerRow(shortcut: Shortcut, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = shortcut.coverArt ?: shortcut.icon
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceVariantColor),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.icon_wine),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            shortcut.name,
            color = OnSurface,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A two-line format option in the BackupFormat dialog. */
@Composable
private fun BackupFormatOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Discover → confirm for a single game. On enter it either restores the remembered folder set from
 * the sidecar (pre-ticked, skip discovery) or scans the container for candidates. The user ticks
 * folders, can add one manually (browser rooted at the xuser profile, escape-guarded), and on Next
 * the selection is persisted to the sidecar and handed to the format step.
 */
@Composable
private fun GameSavesDialog(
    container: Container,
    gameName: String,
    gameKey: String,
    exePath: String,
    wmClass: String,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onNext: (List<String>) -> Unit,
) {
    val rows = remember(gameKey) { mutableStateListOf<SaveLocator.Candidate>() }
    val checked = remember(gameKey) { mutableStateListOf<String>() }
    var loading by remember(gameKey) { mutableStateOf(true) }
    var fromSidecar by remember(gameKey) { mutableStateOf(false) }
    var rescan by remember(gameKey) { mutableStateOf(0) }
    var showManualAdd by remember(gameKey) { mutableStateOf(false) }
    var escapeError by remember(gameKey) { mutableStateOf(false) }

    LaunchedEffect(gameKey, rescan) {
        loading = true
        val (list, sidecar) = withContext(Dispatchers.IO) {
            val saved = if (rescan == 0) SaveLocator.loadMap(container, gameKey) else null
            if (saved != null && saved.roots.isNotEmpty()) {
                saved.roots.map { rel ->
                    SaveLocator.Candidate(rel, rel.substringAfterLast('/'), 100, SaveLocator.sizeOf(container, rel))
                } to true
            } else {
                SaveLocator.discover(container, gameName, exePath, wmClass) to false
            }
        }
        rows.clear(); rows.addAll(list)
        checked.clear(); checked.addAll(list.map { it.relPath })
        fromSidecar = sidecar
        loading = false
    }

    if (loading) {
        SaveFlowProgressDialog(message = stringResource(R.string.save_backup_discovering))
        return
    }

    if (showManualAdd) {
        ManualAddDialog(
            container = container,
            onCancel = { showManualAdd = false },
            onPick = { dir ->
                showManualAdd = false
                val rel = SaveLocator.relativizeUnderProfile(container, dir)
                if (rel == null) {
                    escapeError = true
                } else {
                    escapeError = false
                    if (rows.none { it.relPath == rel }) {
                        rows.add(SaveLocator.Candidate(rel, dir.name, 100, SaveLocator.sizeOf(container, rel)))
                    }
                    if (!checked.contains(rel)) checked.add(rel)
                }
            },
        )
    }

    // Warn if any two ticked folders are nested (manual add can create that; shared subtree copied once).
    val overlap = checked.any { a -> checked.any { b -> a != b && (a.startsWith("$b/")) } }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(gameName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        when {
                            rows.isEmpty() -> R.string.save_backup_confirm_none
                            fromSidecar -> R.string.save_backup_confirm_remembered
                            else -> R.string.save_backup_confirm_prompt
                        }
                    ),
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(4.dp))
                rows.forEach { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked.contains(c.relPath)) checked.remove(c.relPath) else checked.add(c.relPath)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked.contains(c.relPath),
                            onCheckedChange = {
                                if (it) { if (!checked.contains(c.relPath)) checked.add(c.relPath) }
                                else checked.remove(c.relPath)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.relPath, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                            Text(StringUtils.formatBytes(c.sizeBytes), color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (escapeError) {
                    Text(stringResource(R.string.save_backup_add_folder_escape), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (overlap) {
                    Text(stringResource(R.string.save_backup_overlap_note), color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(4.dp))
                Row {
                    TextButton(onClick = { showManualAdd = true }) { Text(stringResource(R.string.save_backup_add_folder)) }
                    if (fromSidecar) {
                        TextButton(onClick = { rescan += 1 }) { Text(stringResource(R.string.save_backup_rescan)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = checked.isNotEmpty(),
                onClick = {
                    val roots = checked.toList()
                    // Persist the taught mapping off the UI thread, then advance to the format step.
                    Thread { SaveLocator.saveMap(container, gameKey, gameName, roots) }.start()
                    onNext(roots)
                },
            ) { Text(stringResource(R.string.save_backup_next)) }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}

/**
 * Minimal in-app folder browser rooted at the container's xuser profile. Used for "add folder…"
 * because SAF can't enumerate the app-private internal storage where container profiles live; the
 * root confinement makes an escape impossible by construction (and the caller re-checks anyway).
 */
@Composable
private fun ManualAddDialog(container: Container, onPick: (File) -> Unit, onCancel: () -> Unit) {
    val root = remember(container.id) { SaveLocator.profileDir(container) }
    var cur by remember(container.id) { mutableStateOf(root) }
    val subDirs = remember(cur.path) {
        cur.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.save_backup_add_folder_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                Text(
                    "…/" + (if (cur.path == root.path) "" else cur.relativeTo(root).path.replace(File.separatorChar, '/')),
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (cur.path != root.path) {
                        item {
                            TextButton(onClick = { cur.parentFile?.let { cur = it } }, modifier = Modifier.fillMaxWidth()) {
                                Text("..", modifier = Modifier.weight(1f), color = OnSurface)
                            }
                        }
                    }
                    items(subDirs, key = { it.path }) { d ->
                        TextButton(onClick = { cur = d }, modifier = Modifier.fillMaxWidth()) {
                            Text(d.name, modifier = Modifier.weight(1f), color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = cur.path != root.path, onClick = { onPick(cur) }) {
                Text(stringResource(R.string.save_backup_add_folder_here))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Caution shown on the Backup/Restore fork: save handling is not exhaustive (some games write
 * saves outside the folders we scan) so users should verify a backup before wiping originals.
 */
@Composable
private fun SaveFlowCaution() {
    // Translucent accent tint (not an opaque fill) so it composits over the dialog's own
    // surface and reads as part of the theme instead of a black slab.
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.40f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "⚠  " + stringResource(R.string.save_flow_caution_title),
                color = OnSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.save_flow_caution_body),
                color = OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Blocking, non-dismissable spinner shown while a backup/restore runs off the UI thread. */
@Composable
private fun SaveFlowProgressDialog(message: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun StorageInfoDialog(container: Container, onDismiss: () -> Unit) {
    var driveCSize by remember { mutableLongStateOf(0L) }
    var cacheSize  by remember { mutableLongStateOf(0L) }
    var totalSize  by remember { mutableLongStateOf(0L) }
    val internalStorageSize = remember { FileUtils.getInternalStorageSize() }
    val progress = if (internalStorageSize > 0)
        ((totalSize.toFloat() / internalStorageSize) * 100f).coerceIn(0f, 100f)
    else 0f

    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    LaunchedEffect(container) {
        val rootDir   = container.getRootDir()
        val driveCDir = File(rootDir, ".wine/drive_c")
        val cacheDir  = File(rootDir, ".cache")
        launch(Dispatchers.IO) {
            FileUtils.getSizeAsync(driveCDir) { size ->
                handler.post { driveCSize += size; totalSize += size }
            }
        }
        launch(Dispatchers.IO) {
            FileUtils.getSizeAsync(cacheDir) { size ->
                handler.post { cacheSize += size; totalSize += size }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage Info") },
        text = {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
                // Left column — Drive C / Cache / Total sizes
                Column(
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Drive C", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(StringUtils.formatBytes(driveCSize), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = androidx.compose.ui.Modifier.size(6.dp))
                    Text("Cache", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(StringUtils.formatBytes(cacheSize), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = androidx.compose.ui.Modifier.size(6.dp))
                    Text("Total", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(StringUtils.formatBytes(totalSize), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                // Right column — circular progress + label
                Column(
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress / 100f },
                            modifier = androidx.compose.ui.Modifier.size(100.dp),
                            strokeWidth = 10.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text("${progress.toInt()}%", fontSize = 16.sp)
                    }
                    Spacer(modifier = androidx.compose.ui.Modifier.size(6.dp))
                    Text(
                        "Estimated used space",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                FileUtils.clear(File(container.getRootDir(), ".cache"))
                container.putExtra("desktopTheme", null)
                container.saveData()
                onDismiss()
            }) { Text("Clear Cache") }
        },
    )
}
