package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.saves.CustomFilePickerActivity
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.saves.Save
import java.io.File
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.Surface

@Composable
fun SavesScreen(vm: SavesViewModel = viewModel()) {
    val saves by vm.saves.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showNewSaveDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Save?>(null) }
    var transferTarget by remember { mutableStateOf<Save?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    // System SAF picker (secondary).
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importUri = uri
    }
    // Built-in in-app file picker (primary).
    val importInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            InAppFilePicker.pickedUri(result.data)?.let { importUri = it }
        }
    }
    var showImportMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (saves.isEmpty() && !isLoading) {
            Text(
                text = "No saves yet. Tap + to add one.",
                color = OnSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(saves, key = { it.file.absolutePath }) { save ->
                    SaveItem(
                        save = save,
                        onEdit = { editTarget = save },
                        onTransfer = { transferTarget = save },
                        onExport = { vm.exportSave(context, save, false) },
                        onShare = { vm.exportSave(context, save, true) },
                        onUnregister = { vm.removeSave(save) },
                    )
                    Divider(color = DividerColor)
                }
            }
        }

        // FAB row: Import (secondary) + Add (primary)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                FloatingActionButton(
                    onClick = { showImportMenu = true },
                    containerColor = Surface,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = "Import save", tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                    DropdownMenuItem(text = { Text("Browse files") }, onClick = {
                        showImportMenu = false
                        importInAppLauncher.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.SAVE, "Select save archive"))
                    })
                    DropdownMenuItem(text = { Text("Pick via system…") }, onClick = {
                        showImportMenu = false
                        importLauncher.launch("*/*")
                    })
                }
            }
            FloatingActionButton(
                onClick = { showNewSaveDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add save", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showNewSaveDialog) {
        NewSaveDialog(
            containers = vm.containers,
            onDismiss = { showNewSaveDialog = false },
            onConfirm = { title, path, container ->
                showNewSaveDialog = false
                vm.addSave(title, path, container) { ok, msg ->
                    if (!ok) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    editTarget?.let { save ->
        EditSaveDialog(
            save = save,
            onDismiss = { editTarget = null },
            onConfirm = { newTitle ->
                editTarget = null
                vm.updateSave(save, newTitle) { ok, msg ->
                    if (!ok) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    transferTarget?.let { save ->
        TransferSaveDialog(
            save = save,
            containers = vm.containers,
            onDismiss = { transferTarget = null },
            onConfirm = { container ->
                transferTarget = null
                vm.transferSave(save, container) { ok, msg ->
                    Toast.makeText(context, if (ok) "Transfer complete" else msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    importUri?.let { uri ->
        ImportContainerSelectDialog(
            containers = vm.containers,
            onDismiss = { importUri = null },
            onConfirm = { container ->
                val capturedUri = uri
                importUri = null
                vm.importSave(context, capturedUri, container) { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun SaveItem(
    save: Save,
    onEdit: () -> Unit,
    onTransfer: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onUnregister: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.icon_save),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = save.getTitle(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = OnSurface,
            )
            Text(
                text = save.container?.getName() ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Options", tint = OnSurfaceVariant)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menuExpanded = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Transfer Container") },
                    leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                    onClick = { menuExpanded = false; onTransfer() },
                )
                Divider(color = DividerColor)
                DropdownMenuItem(
                    text = { Text("Export") },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                    onClick = { menuExpanded = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = { menuExpanded = false; onShare() },
                )
                Divider(color = DividerColor)
                DropdownMenuItem(
                    text = { Text("Unregister") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menuExpanded = false; onUnregister() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSaveDialog(
    containers: List<Container>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Container) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    var selectedPath by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val selectedContainer = containers.getOrNull(selectedIndex)

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("selectedDirectory")?.let { selectedPath = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Save") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Save Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedContainer?.getName() ?: if (containers.isEmpty()) "No containers" else "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Container") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        containers.forEachIndexed { index, c ->
                            DropdownMenuItem(
                                text = { Text(c.getName()) },
                                onClick = { selectedIndex = index; dropdownExpanded = false },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val c = selectedContainer
                        if (c != null) {
                            val dynamicPath = File(c.getRootDir(), ".wine/drive_c/").absolutePath
                            val intent = Intent(context, CustomFilePickerActivity::class.java).apply {
                                putExtra("initialDirectory", dynamicPath)
                                putExtra("isEditing", false)
                                putExtra("editingPath", dynamicPath)
                            }
                            filePickerLauncher.launch(intent)
                        } else {
                            Toast.makeText(context, "Select a container first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Select Save Path", color = MaterialTheme.colorScheme.primary)
                }
                if (selectedPath.isNotEmpty()) {
                    Text(text = selectedPath, fontSize = 11.sp, color = OnSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank() && selectedPath.isNotEmpty() && selectedContainer != null) {
                    onConfirm(title.trim(), selectedPath, selectedContainer)
                } else {
                    Toast.makeText(context, "Fill all fields", Toast.LENGTH_SHORT).show()
                }
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditSaveDialog(
    save: Save,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(save) { mutableStateOf(save.getTitle()) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Save") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Save Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Original Path", fontSize = 12.sp, color = OnSurfaceVariant)
                Text(save.path, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    onConfirm(title.trim())
                } else {
                    Toast.makeText(context, "Name is required", Toast.LENGTH_SHORT).show()
                }
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSaveDialog(
    save: Save,
    containers: List<Container>,
    onDismiss: () -> Unit,
    onConfirm: (Container) -> Unit,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer Container") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Transfer \"${save.getTitle()}\" to:",
                    fontSize = 13.sp,
                    color = OnSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = containers.getOrNull(selectedIndex)?.getName() ?: "No containers",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Container") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        containers.forEachIndexed { index, c ->
                            DropdownMenuItem(
                                text = { Text(c.getName()) },
                                onClick = { selectedIndex = index; dropdownExpanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                containers.getOrNull(selectedIndex)?.let { onConfirm(it) }
            }) { Text("Transfer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportContainerSelectDialog(
    containers: List<Container>,
    onDismiss: () -> Unit,
    onConfirm: (Container) -> Unit,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Save") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Select target container:",
                    fontSize = 13.sp,
                    color = OnSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = containers.getOrNull(selectedIndex)?.getName() ?: "No containers",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Container") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        containers.forEachIndexed { index, c ->
                            DropdownMenuItem(
                                text = { Text(c.getName()) },
                                onClick = { selectedIndex = index; dropdownExpanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                containers.getOrNull(selectedIndex)?.let { onConfirm(it) }
            }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
