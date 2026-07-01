package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import java.io.File
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.winlator.star.ui.LocalTopBarActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.R
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.XrActivity
import com.winlator.star.container.Container
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.core.FileUtils
import com.winlator.star.core.StringUtils
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menuExpanded = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuExpanded = false; onRemove() },
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        leadingIcon = { Icon(Icons.Filled.FileUpload, null) },
                        onClick = { menuExpanded = false; onExport() },
                    )
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
