package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.core.FileUtils
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.theme.OnSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FileTypeIcon: Map<String, ImageVector> = mapOf(
    "folder" to Icons.Filled.Folder,
)

private val CardFill = Color(0xFF0A0A0A)
private val CardStroke = Color(0xFF242424)
private val DividerColor = Color(0xFF333333)
private val IconBlue = Color(0xFF1C85FE)

@Composable
fun FileManagerScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainActivity = context as? MainActivity
    val scope = rememberCoroutineScope()

    val containerManager = mainActivity?.containerManager
    val containers = remember { containerManager?.getContainers()?.toList() ?: emptyList<Container>() }

    val rootDir = File("/storage/emulated/0")
    val winlatorDir = File("/storage/emulated/0/Winlator")

    var currentDir by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf(listOf<File>()) }
    var selectedEntry by remember { mutableStateOf<File?>(null) }
    var showMenuFor by remember { mutableStateOf<File?>(null) }
    var clipboardFile by remember { mutableStateOf<File?>(null) }
    var isCutOperation by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var isOperationRunning by remember { mutableStateOf(false) }
    var operationLabel by remember { mutableStateOf("") }

    fun loadDirectory(dir: File) {
        val list = dir.listFiles()?.toList()?.sortedWith(
            compareBy<File> { if (it.isDirectory) 0 else 1 }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        currentDir = dir
        entries = list
    }

    LaunchedEffect(Unit) { loadDirectory(rootDir) }

    fun canRun(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".msi") || name.endsWith(".sh")
    }

    fun runFile(file: File) {
        val container = containerManager?.let { mgr ->
            if (containers.size == 1) containers.first()
            else return
        } ?: return

        val desktopDir = File(context.filesDir, "desktops")
        desktopDir.mkdirs()
        val desktopFile = File(desktopDir, "opencode_file_${file.name}.desktop")
        PrintWriter(desktopFile).use { pw ->
            pw.println("[Desktop Entry]")
            pw.println("Name=${file.nameWithoutExtension}")
            pw.println("Exec=${file.absolutePath}")
            pw.println("Icon=wine")
            pw.println("Path=")
            pw.println("Terminal=false")
            pw.println("Type=Application")
            pw.println("StartupNotify=true")
        }
        val intent = Intent(context, XServerDisplayActivity::class.java)
        intent.putExtra("container_id", container.id)
        intent.putExtra("desktop_file", desktopFile.absolutePath)
        context.startActivity(intent)
    }

    fun performDelete(file: File) {
        scope.launch {
            isOperationRunning = true
            operationLabel = "Deleting..."
            withContext(Dispatchers.IO) { FileUtils.delete(file) }
            isOperationRunning = false
            loadDirectory(currentDir)
        }
    }

    fun performCopy(src: File, dstDir: File) {
        scope.launch {
            isOperationRunning = true
            operationLabel = "Copying..."
            withContext(Dispatchers.IO) { FileUtils.copy(src, File(dstDir, src.name)) }
            isOperationRunning = false
            loadDirectory(currentDir)
            Toast.makeText(context, "Copied ${src.name}", Toast.LENGTH_SHORT).show()
        }
    }

    fun performPaste() {
        val src = clipboardFile ?: return
        val dstDir = currentDir
        scope.launch {
            isOperationRunning = true
            operationLabel = if (isCutOperation) "Moving..." else "Copying..."
            withContext(Dispatchers.IO) {
                FileUtils.copy(src, File(dstDir, src.name))
                if (isCutOperation) FileUtils.delete(src)
            }
            isOperationRunning = false
            clipboardFile = null
            isCutOperation = false
            loadDirectory(currentDir)
        }
    }

    fun performRename(file: File, newName: String) {
        scope.launch {
            isOperationRunning = true
            operationLabel = "Renaming..."
            withContext(Dispatchers.IO) { file.renameTo(File(file.parentFile, newName)) }
            isOperationRunning = false
            loadDirectory(currentDir)
        }
    }

    fun createFolder(parent: File, name: String) {
        scope.launch {
            isOperationRunning = true
            operationLabel = "Creating folder..."
            withContext(Dispatchers.IO) { File(parent, name).mkdirs() }
            isOperationRunning = false
            loadDirectory(currentDir)
        }
    }

    var showDriveMenu by remember { mutableStateOf(false) }
    var showContainerPicker by remember { mutableStateOf(false) }
    val drives = remember {
        buildList {
            add("Internal" to File("/storage/emulated/0"))
            val external = File("/storage")
            if (external.exists()) {
                external.listFiles()?.forEach { child ->
                    if (child.isDirectory && child.name != "emulated" && child.name != "self") {
                        add(child.name to child)
                    }
                }
            }
        }.filter { (_, f) -> f.exists() }
    }

    // ── Dialogs ──

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewFolderDialog = false
                    if (folderName.isNotBlank()) createFolder(currentDir, folderName)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") } },
        )
    }

    if (renameTarget != null) {
        var newName by remember(renameTarget) { mutableStateOf(renameTarget?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = renameTarget
                    renameTarget = null
                    if (file != null && newName.isNotBlank()) performRename(file, newName)
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    if (selectedEntry != null && selectedEntry != showMenuFor) {
        val file = selectedEntry ?: return
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text("Delete?") },
            text = { Text("Delete \"${file.name}\" permanently?") },
            confirmButton = {
                TextButton(onClick = {
                    selectedEntry = null
                    performDelete(file)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { selectedEntry = null }) { Text("Cancel") } },
        )
    }

    if (showContainerPicker) {
        AlertDialog(
            onDismissRequest = { showContainerPicker = false },
            title = { Text("Choose container") },
            text = {
                Column {
                    containers.forEach { container ->
                        Text(
                            text = container.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showContainerPicker = false
                                    loadDirectory(File(container.rootDir, ".wine/drive_c"))
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Path bar ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = {
                val parent = currentDir.parentFile
                if (parent != null && parent.exists()) loadDirectory(parent)
            }, enabled = currentDir != rootDir) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = IconBlue)
            }

            val imagefsDir = File(context.filesDir, "imagefs")
            val currentDriveLabel = when {
                containers.any { currentDir.absolutePath.startsWith(File(it.rootDir, ".wine/drive_c").absolutePath) } -> "Drive C:"
                currentDir.absolutePath.startsWith(imagefsDir.absolutePath) -> "Drive Z:"
                else -> drives.firstOrNull { (_, d) -> currentDir.absolutePath.startsWith(d.absolutePath) }?.first ?: "Storage"
            }
            Box {
                Text(
                    text = "  $currentDriveLabel  ",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0A0A0A))
                        .clickable { showDriveMenu = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                DropdownMenu(
                    expanded = showDriveMenu,
                    onDismissRequest = { showDriveMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Drive C:") },
                        leadingIcon = {
                            Icon(Icons.Filled.SdStorage, null, tint = IconBlue, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showDriveMenu = false
                            if (containers.size == 1) {
                                loadDirectory(File(containers.first().rootDir, ".wine/drive_c"))
                            }
                            else if (containers.size > 1) {
                                showContainerPicker = true
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Drive Z:") },
                        leadingIcon = {
                            Icon(Icons.Filled.Storage, null, tint = IconBlue, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showDriveMenu = false
                            loadDirectory(imagefsDir)
                        },
                    )
                    drives.forEach { (label, dir) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                Icon(Icons.Filled.Storage, null, tint = IconBlue, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showDriveMenu = false
                                loadDirectory(dir)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            Text(
                text = currentDir.absolutePath,
                color = OnSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = DividerColor)

        // ── Paste banner ──
        if (clipboardFile != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardStroke.copy(alpha = 0.1f))
                    .clickable { performPaste() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.ContentPaste, "Paste", tint = IconBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Paste ${clipboardFile?.name}${if (isCutOperation) " (move)" else ""} here",
                    color = CardStroke, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboardFile = null; isCutOperation = false }) {
                    Text("Cancel", color = OnSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        // ── Progress overlay ──
        if (isOperationRunning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(operationLabel, color = OnSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = CardStroke,
                    trackColor = DividerColor,
                )
            }
        }

        // ── File list ──
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Empty directory", color = OnSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.absolutePath }) { file ->
                    FileItemRow(
                        file = file,
                        onTap = {
                            if (file.isDirectory) loadDirectory(file)
                            else if (canRun(file)) runFile(file)
                        },
                        onMenu = { showMenuFor = file },
                        menuExpanded = showMenuFor == file,
                        onDismissMenu = { showMenuFor = null },
                        onRun = { runFile(file) },
                        onCopy = { clipboardFile = file; isCutOperation = false; showMenuFor = null },
                        onCut = { clipboardFile = file; isCutOperation = true; showMenuFor = null },
                        onDelete = { selectedEntry = file; showMenuFor = null },
                        onRename = { renameTarget = file; showMenuFor = null },
                    )
                }
            }
        }

        // ── FAB area ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = { showNewFolderDialog = true },
                border = BorderStroke(1.dp, CardStroke),
            ) {
                Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(18.dp), tint = IconBlue)
                Spacer(Modifier.width(6.dp))
                Text("New Folder", color = Color.White)
            }
        }
    }
}

@Composable
private fun FileItemRow(
    file: File,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isDir = file.isDirectory
    val canRun = isDir || file.name.lowercase().let { it.endsWith(".exe") || it.endsWith(".bat") || it.endsWith(".msi") || it.endsWith(".sh") }
    val isExe = !isDir && file.name.lowercase().let { it.endsWith(".exe") || it.endsWith(".bat") || it.endsWith(".msi") || it.endsWith(".sh") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardFill),
        border = BorderStroke(1.dp, CardStroke),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (isExe) {
                Icon(
                    painter = painterResource(R.drawable.icon_menu_container),
                    contentDescription = null,
                    tint = IconBlue,
                    modifier = Modifier.size(36.dp),
                )
            }
            else {
                Icon(
                    imageVector = if (isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = IconBlue,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (!isDir) append(StringUtils.formatBytes(file.length())).append("  \u2022  ")
                        append(dateFormat.format(Date(file.lastModified())))
                    },
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Box {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.MoreVert, "Actions", tint = IconBlue, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    if (canRun) {
                        DropdownMenuItem(
                            text = { Text("Run") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = IconBlue) },
                            onClick = { onDismissMenu(); onRun() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, tint = IconBlue) },
                        onClick = { onDismissMenu(); onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Filled.FileCopy, null, tint = IconBlue) },
                        onClick = { onDismissMenu(); onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Cut") },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, null, tint = IconBlue) },
                        onClick = { onDismissMenu(); onCut() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = IconBlue) },
                        onClick = { onDismissMenu(); onDelete() },
                    )
                }
            }
        }
    }
}
