package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.MainActivity
import com.winlator.star.R
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.core.FileUtils
import com.winlator.star.core.PeIconExtractor
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WinePath
import com.winlator.star.util.FavoritesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FileTypeIcon: Map<String, ImageVector> = mapOf(
    "folder" to Icons.Filled.Folder,
)

// Image extensions that get a real thumbnail (via Coil) instead of the generic file icon.
private val IMAGE_THUMB_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

// Color-only sweep: the former card-fill / card-stroke / divider / icon-blue / icon-white
// constants were rerouted onto MaterialTheme.colorScheme tokens (surface / outline / primary /
// onSurface) at their use sites so a theme preset/accent recolors them.

// True when [child] is [ancestor] itself or lives anywhere inside it.
private fun isWithin(child: File, ancestor: File): Boolean {
    val c = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
    val a = runCatching { ancestor.canonicalPath }.getOrDefault(ancestor.absolutePath)
    return c == a || c.startsWith(a + File.separator)
}

// ── Favorites: origin resolution ──

enum class FavStorage { INTERNAL, SD, CONTAINER, OTHER }

data class FavLocation(
    val storage: FavStorage,
    val containerName: String?,   // non-null only for a container's Drive C:
    val driveLabel: String,       // "Drive C:", "Drive Z:", "Internal", "SD card", or "Storage"
    val displayPath: String       // Wine drives: "C:\\...", "Z:\\..."; storage: the unix absolute path
)

// Resolve where [file] lives (storage source + drive + a friendly path) by prefix-matching
// its absolute path. Robust to a missing/renamed container — falls through to OTHER.
fun describeLocation(file: File, containers: List<Container>, imagefsDir: File): FavLocation {
    val abs = file.absolutePath

    for (container in containers) {
        val driveC = File(container.rootDir, ".wine/drive_c").absolutePath
        if (abs == driveC || abs.startsWith("$driveC/")) {
            val remainder = abs.removePrefix(driveC).replace('/', '\\')
            return FavLocation(
                storage = FavStorage.CONTAINER,
                containerName = container.name,
                driveLabel = "Drive C:",
                displayPath = "C:$remainder",
            )
        }
    }

    val imagefs = imagefsDir.absolutePath
    if (abs == imagefs || abs.startsWith("$imagefs/")) {
        val remainder = abs.removePrefix(imagefs).replace('/', '\\')
        // Z:/imagefs is shared across containers — no single owning container.
        return FavLocation(
            storage = FavStorage.CONTAINER,
            containerName = null,
            driveLabel = "Drive Z:",
            displayPath = "Z:$remainder",
        )
    }

    val internal = "/storage/emulated/0"
    if (abs == internal || abs.startsWith("$internal/")) {
        return FavLocation(FavStorage.INTERNAL, null, "Internal", abs)
    }

    if (abs.startsWith("/storage/")) {
        val name = abs.removePrefix("/storage/").substringBefore('/')
        if (name.isNotEmpty() && name != "emulated" && name != "self") {
            return FavLocation(FavStorage.SD, null, "SD card", abs)
        }
    }

    return FavLocation(FavStorage.OTHER, null, "Storage", abs)
}

// Semantic identity colours for the favourite-card drive badge. Intentionally NOT theme
// accent colours — they identify the storage source at a glance. Returns (background, foreground).
private fun badgeColors(loc: FavLocation): Pair<Color, Color> {
    val white = Color(0xFFFFFFFF)
    return when {
        loc.storage == FavStorage.INTERNAL -> Color(0xFF2E5FB0) to white   // blue
        loc.storage == FavStorage.SD -> Color(0xFF2E7D32) to white         // green
        loc.driveLabel == "Drive Z:" -> Color(0xFF6A3FB0) to white         // purple
        loc.storage == FavStorage.CONTAINER -> Color(0xFF8F6A00) to white  // amber (Drive C:)
        else -> Color(0xFF555555) to white                                 // grey
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    // Pick mode (issue #73): reuse this File Manager as a themed file picker. When on, editing/run
    // features are gated off and tapping a matching file returns it via [onPick]. Defaults keep the
    // full-featured File Manager nav destination unchanged.
    pickMode: Boolean = false,
    // Directory-pick mode (issue #70): only folders are listed, files are hidden, and a
    // "Select this folder" action returns the current directory via [onPick]. Implies pickMode.
    pickDirMode: Boolean = false,
    pickExtensions: List<String> = emptyList(),
    initialDir: File? = null,
    pickerTitle: String? = null,
    onPick: ((File) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainActivity = context as? MainActivity
    val scope = rememberCoroutineScope()

    val containerManager = mainActivity?.containerManager
    val containers = remember { containerManager?.getContainers()?.toList() ?: emptyList<Container>() }
    val imagefsDir = remember { File(context.filesDir, "imagefs") }

    // Only matching files are shown in pick mode (directories are always shown). Empty = all files.
    val lowerExts = remember(pickExtensions) { pickExtensions.map { it.lowercase() } }
    fun matchesPickExt(file: File): Boolean {
        if (lowerExts.isEmpty()) return true
        val name = file.name.lowercase()
        return lowerExts.any { name.endsWith(".$it") }
    }

    val pickPrefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    val rootDir = remember {
        if (pickMode) {
            val remembered = pickPrefs.getString("lastFilePickerDir", null)?.let { File(it) }?.takeIf { it.isDirectory }
            initialDir?.takeIf { it.isDirectory }
                ?: remembered
                ?: File("/sdcard/Download/").takeIf { it.isDirectory }
                ?: File("/storage/emulated/0")
        } else File("/storage/emulated/0")
    }

    var currentDir by remember { mutableStateOf(rootDir) }
    var currentRoot by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf(listOf<File>()) }
    var selectedEntry by remember { mutableStateOf<File?>(null) }
    var showMenuFor by remember { mutableStateOf<File?>(null) }
    var clipboardFile by remember { mutableStateOf<File?>(null) }
    var isCutOperation by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var pendingRun by remember { mutableStateOf<File?>(null) }
    var isOperationRunning by remember { mutableStateOf(false) }
    var operationLabel by remember { mutableStateOf("") }
    var operationDeterminate by remember { mutableStateOf(false) }
    var operationProgress by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // Favorites view: when on, a dedicated bookmarks list replaces the file list.
    // favTick is bumped on any add/remove/toggle so the favorites view + per-row star recompute.
    var showFavorites by remember { mutableStateOf(false) }
    var favTick by remember { mutableIntStateOf(0) }

    // resetScroll: jump to the top of the list (true for navigation; false for in-place reloads
    // after delete/paste/rename/refresh so the user keeps their scroll position).
    fun loadDirectory(dir: File, resetScroll: Boolean = true) {
        currentDir = dir
        // Remember the browsed directory so the next pick resumes here.
        if (pickMode) pickPrefs.edit().putString("lastFilePickerDir", dir.absolutePath).apply()
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles()?.toList()
                    // Dir-pick mode: folders only. File-pick: folders + matching files. Else: all.
                    ?.filter { if (pickDirMode) it.isDirectory else !pickMode || it.isDirectory || matchesPickExt(it) }
                    ?.sortedWith(
                        compareBy<File> { if (it.isDirectory) 0 else 1 }.thenBy { it.name.lowercase() }
                    ) ?: emptyList()
            }
            entries = list
            if (resetScroll) listState.scrollToItem(0)
        }
    }

    // Pull-to-refresh: re-list the current directory, keeping scroll position.
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            loadDirectory(currentDir, resetScroll = false)
            pullState.endRefresh()
        }
    }

    // Jump to a drive's root; pins the Back boundary so we don't climb above it.
    fun openDrive(dir: File) {
        currentRoot = dir
        loadDirectory(dir)
    }

    LaunchedEffect(Unit) { openDrive(rootDir) }

    // System/gesture Back: while the Favorites view is open it closes that first; otherwise
    // it goes up one directory. Only at the current drive's root with Favorites closed is it
    // disabled, letting Back propagate to close the File Manager.
    BackHandler(enabled = showFavorites || currentDir != currentRoot) {
        if (showFavorites) {
            showFavorites = false
            return@BackHandler
        }
        val parent = currentDir.parentFile
        if (parent != null && parent.exists()) loadDirectory(parent)
    }

    fun canRun(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".msi") || name.endsWith(".sh")
    }

    // Launch [file] inside [container] the same way the Games importer does: map the
    // file's folder to a Wine drive letter (persisted on the container), then hand
    // XServerDisplayActivity a shortcut whose Exec is `wine <X:\path>`. We don't add a
    // permanent Games entry — the .desktop lives in app storage, not the container's
    // desktop dir, so it never shows up in the Shortcuts list.
    fun runFileInContainer(file: File, container: Container) {
        scope.launch {
            val shortcutFile = withContext(Dispatchers.IO) {
                runCatching {
                    val winPath = WinePath.resolveWindowsPath(container, file.absolutePath)
                    val escaped = WinePath.escapeForExec(winPath)
                    val desktopDir = File(context.filesDir, "desktops").apply { mkdirs() }
                    val safeName = file.nameWithoutExtension
                        .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "run" }
                    File(desktopDir, "$safeName.desktop").apply {
                        writeText(
                            buildString {
                                append("[Desktop Entry]\n")
                                append("Name=").append(file.nameWithoutExtension).append("\n")
                                append("Exec=wine ").append(escaped).append("\n")
                                append("Icon=").append(safeName).append("\n")
                                append("Type=Application\n")
                                append("StartupWMClass=explorer\n")
                                append("\ncontainer_id:").append(container.id).append("\n")
                                append("\n[Extra Data]\n")
                            }
                        )
                    }
                }.getOrNull()
            }
            if (shortcutFile == null) {
                Toast.makeText(context, "Couldn't prepare ${file.name} to run", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(context, XServerDisplayActivity::class.java)
            intent.putExtra("container_id", container.id)
            intent.putExtra("shortcut_path", shortcutFile.absolutePath)
            intent.putExtra("shortcut_name", shortcutFile.nameWithoutExtension)
            context.startActivity(intent)
        }
    }

    fun runFile(file: File) {
        containerManager ?: return
        when {
            containers.isEmpty() ->
                Toast.makeText(context, "No container available — create one first", Toast.LENGTH_SHORT).show()
            containers.size == 1 -> runFileInContainer(file, containers.first())
            else -> pendingRun = file   // ask which container
        }
    }

    // Resolve a non-colliding destination in [dir] for [name] (foo.txt -> "foo (1).txt").
    fun uniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        do {
            candidate = File(dir, "$base ($i)$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    fun performDelete(file: File) {
        scope.launch {
            isOperationRunning = true
            operationLabel = "Deleting..."
            val ok = withContext(Dispatchers.IO) { FileUtils.delete(file) }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun performPaste() {
        val src = clipboardFile ?: return
        val dstDir = currentDir
        val cut = isCutOperation
        // Pasting a folder into itself or its own subtree would recurse forever — reject it.
        if (src.isDirectory && isWithin(dstDir, src)) {
            Toast.makeText(context, "Can't paste a folder into itself", Toast.LENGTH_SHORT).show()
            clipboardFile = null
            isCutOperation = false
            return
        }
        val sameFolder = src.parentFile?.absolutePath == dstDir.absolutePath
        // Moving into the same folder is a no-op; never copy a file onto itself.
        if (sameFolder && cut) {
            clipboardFile = null
            isCutOperation = false
            return
        }
        // Don't overwrite an existing entry (or self when copying in-place) — pick a free name.
        val dst = uniqueDestination(dstDir, src.name)
        scope.launch {
            operationProgress = 0f
            operationDeterminate = true
            operationLabel = if (cut) "Moving..." else "Copying..."
            isOperationRunning = true
            // Throttle UI updates to whole-percent changes to avoid flooding recomposition.
            var lastPct = -1
            val onProgress = FileUtils.ProgressCallback { copied, total ->
                val pct = if (total > 0) ((copied * 100) / total).toInt() else 100
                if (pct != lastPct) {
                    lastPct = pct
                    operationProgress = pct / 100f
                }
            }
            val ok = withContext(Dispatchers.IO) {
                val copied = FileUtils.copyWithProgress(src, dst, onProgress)
                if (copied && cut) FileUtils.delete(src)
                copied
            }
            isOperationRunning = false
            operationDeterminate = false
            clipboardFile = null
            isCutOperation = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, "Paste failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun performRename(file: File, newName: String) {
        val target = File(file.parentFile, newName)
        if (target.exists()) {
            Toast.makeText(context, "\"$newName\" already exists", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isOperationRunning = true
            operationLabel = "Renaming..."
            val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun createFolder(parent: File, name: String) {
        val target = File(parent, name)
        if (target.exists()) {
            Toast.makeText(context, "\"$name\" already exists", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isOperationRunning = true
            operationLabel = "Creating folder..."
            val ok = withContext(Dispatchers.IO) { target.mkdirs() }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) Toast.makeText(context, "Could not create folder", Toast.LENGTH_SHORT).show()
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
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showContainerPicker = false
                                    openDrive(File(container.rootDir, ".wine/drive_c"))
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (pendingRun != null) {
        val file = pendingRun
        AlertDialog(
            onDismissRequest = { pendingRun = null },
            title = { Text("Run in which container?") },
            text = {
                Column {
                    containers.forEach { container ->
                        Text(
                            text = container.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingRun = null
                                    if (file != null) runFileInContainer(file, container)
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingRun = null }) { Text("Cancel") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Pick-mode title ──
        if (pickMode && !pickerTitle.isNullOrEmpty()) {
            Text(
                text = pickerTitle,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        // ── Dir-pick action bar: confirm the currently-browsed folder ──
        if (pickDirMode) {
            Button(
                onClick = { onPick?.invoke(currentDir) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select this folder")
            }
        }
        // ── Path bar ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = {
                val parent = currentDir.parentFile
                // Don't climb above the current drive's root.
                if (currentDir != currentRoot && parent != null && parent.exists()) loadDirectory(parent)
            }, enabled = currentDir != currentRoot) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
            }

            val currentDriveLabel = describeLocation(currentDir, containers, imagefsDir).driveLabel
            // Dim the drive chip while the Favorites list is open (it's not the active context).
            val driveChipAlpha = if (showFavorites) 0.45f else 1f
            Box {
                Text(
                    text = "  $currentDriveLabel  ",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = driveChipAlpha),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { showDriveMenu = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                DropdownMenu(
                    expanded = showDriveMenu,
                    onDismissRequest = { showDriveMenu = false },
                    modifier = Modifier.outlinedMenuCard(),
                ) {
                    DropdownMenuItem(
                        text = { Text("Drive C:") },
                        leadingIcon = {
                            Icon(Icons.Filled.SdStorage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showDriveMenu = false
                            if (containers.size == 1) {
                                openDrive(File(containers.first().rootDir, ".wine/drive_c"))
                            }
                            else if (containers.size > 1) {
                                showContainerPicker = true
                            }
                        },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text("Drive Z:") },
                        leadingIcon = {
                            Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showDriveMenu = false
                            openDrive(imagefsDir)
                        },
                    )
                    drives.forEach { (label, dir) ->
                        MenuItemDivider()
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showDriveMenu = false
                                openDrive(dir)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            if (showFavorites) {
                Text(
                    text = "★ Favorites",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = currentDir.absolutePath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // Star toggle: open/close the dedicated Favorites list.
            IconButton(onClick = { showFavorites = !showFavorites }) {
                if (showFavorites) {
                    Icon(Icons.Filled.Star, "Hide favorites", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Filled.StarBorder, "Show favorites", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ── Paste banner ──
        if (clipboardFile != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    .clickable { performPaste() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.ContentPaste, "Paste", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Paste ${clipboardFile?.name}${if (isCutOperation) " (move)" else ""} here",
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboardFile = null; isCutOperation = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        // ── Progress overlay ──
        if (isOperationRunning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val pctText = if (operationDeterminate) "  ${(operationProgress * 100).toInt()}%" else ""
                Text("$operationLabel$pctText", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                if (operationDeterminate) {
                    LinearProgressIndicator(
                        progress = { operationProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // ── Favorites list OR file list ──
        if (showFavorites) {
            FavoritesList(
                containers = containers,
                imagefsDir = imagefsDir,
                currentDir = currentDir,
                favTick = favTick,
                onPinCurrent = {
                    FavoritesStore.add(context, currentDir.absolutePath)
                    favTick++
                    Toast.makeText(context, "Added \"${currentDir.name}\" to Favorites", Toast.LENGTH_SHORT).show()
                },
                onJump = { dir ->
                    showFavorites = false
                    openDrive(dir)
                },
                onUnpin = { dir ->
                    FavoritesStore.remove(context, dir.absolutePath)
                    favTick++
                    Toast.makeText(context, "Removed \"${dir.name}\" from Favorites", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
        // ── File list (pull down to refresh) ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Empty directory", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(entries, key = { it.absolutePath }) { file ->
                        val isFav = remember(file.absolutePath, favTick) {
                            FavoritesStore.isFavorite(context, file.absolutePath)
                        }
                        FileItemRow(
                            file = file,
                            showActions = !pickMode,
                            onTap = {
                                if (file.isDirectory) loadDirectory(file)
                                else if (pickMode) {
                                    if (matchesPickExt(file)) {
                                        pickPrefs.edit().putString("lastFilePickerDir", currentDir.absolutePath).apply()
                                        onPick?.invoke(file)
                                    }
                                }
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
                            isFavorite = isFav,
                            onToggleFavorite = {
                                val nowFav = FavoritesStore.toggle(context, file.absolutePath)
                                favTick++
                                showMenuFor = null
                                Toast.makeText(
                                    context,
                                    if (nowFav) "Added \"${file.name}\" to Favorites"
                                    else "Removed \"${file.name}\" from Favorites",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            }
            // material3 1.2.0's PullToRefreshContainer draws its indicator even at rest;
            // only show it while the user is actively pulling or a refresh is running.
            if (pullState.verticalOffset > 0.5f || pullState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        }

        // ── FAB area ── (creation is gated off in pick mode)
        if (!pickMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    onClick = { showNewFolderDialog = true },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("New Folder", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

@Composable
private fun FileItemRow(
    file: File,
    showActions: Boolean = true,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isDir = file.isDirectory
    val canRun = isDir || file.name.lowercase().let { it.endsWith(".exe") || it.endsWith(".bat") || it.endsWith(".msi") || it.endsWith(".sh") }
    val isExe = !isDir && file.name.lowercase().let { it.endsWith(".exe") || it.endsWith(".bat") || it.endsWith(".msi") || it.endsWith(".sh") }
    // Image files show a real thumbnail instead of the generic file icon (handy when picking a
    // wallpaper/icon). Coil sizes the decode to the 36dp slot and caches it, so scrolling stays smooth.
    val isImage = !isDir && file.extension.lowercase() in IMAGE_THUMB_EXTS

    // For real PE executables, try to pull out the embedded application icon (async, off the main thread).
    var exeIcon by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    if (!isDir && file.name.lowercase().endsWith(".exe")) {
        LaunchedEffect(file.absolutePath) {
            val bmp = withContext(Dispatchers.IO) { PeIconExtractor.extract(file) }
            if (bmp != null) exeIcon = bmp.asImageBitmap()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when {
                // Show the executable's own embedded icon when we managed to extract one.
                exeIcon != null -> Image(
                    bitmap = exeIcon!!,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                isExe -> Icon(
                    painter = painterResource(R.drawable.icon_menu_container),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(36.dp),
                )
                isDir -> Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                // Real image preview. Falls back to the generic file icon while loading or on decode failure.
                isImage -> AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = rememberVectorPainter(Icons.Filled.InsertDriveFile),
                    error = rememberVectorPainter(Icons.Filled.InsertDriveFile),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                else -> Icon(
                    imageVector = Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = MaterialTheme.colorScheme.onBackground,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (showActions) Box {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.MoreVert, "Actions", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    // Favorites are directories — only folders get the pin toggle.
                    if (isDir) {
                        DropdownMenuItem(
                            text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                            leadingIcon = {
                                Icon(
                                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = { onToggleFavorite() },
                        )
                    }
                    if (canRun) {
                        DropdownMenuItem(
                            text = { Text("Run") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { onDismissMenu(); onRun() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Filled.FileCopy, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Cut") },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onCut() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onDelete() },
                    )
                }
            }
        }
    }
}

// Dedicated Favorites list that replaces the file list while the star toggle is on.
// Reads the store keyed on [favTick] so it recomposes after any pin/unpin.
@Composable
private fun FavoritesList(
    containers: List<Container>,
    imagefsDir: File,
    currentDir: File,
    favTick: Int,
    onPinCurrent: () -> Unit,
    onJump: (File) -> Unit,
    onUnpin: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val favorites = remember(favTick) {
        FavoritesStore.list(context).map(::File).filter { it.exists() }
    }
    val currentAlreadyPinned = remember(favTick, currentDir.absolutePath) {
        FavoritesStore.isFavorite(context, currentDir.absolutePath)
    }

    LazyColumn(modifier = modifier) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Favorites",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onPinCurrent,
                    enabled = !currentAlreadyPinned,
                ) {
                    Icon(Icons.Filled.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Pin current folder",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (favorites.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No favorites yet — pin a folder with its ⋮ menu to jump back here fast.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            items(favorites, key = { it.absolutePath }) { file ->
                val loc = remember(file.absolutePath, containers) {
                    describeLocation(file, containers, imagefsDir)
                }
                FavoriteCard(
                    file = file,
                    loc = loc,
                    onJump = { onJump(file) },
                    onUnpin = { onUnpin(file) },
                )
            }
        }
    }
}

// A single favourite — matches the FileItemRow card style (surfaceContainer + outline +
// RoundedCornerShape(10.dp)). Shows the folder name, a coloured drive badge + origin text,
// and the full display path; tapping jumps into it, the filled star unpins.
@Composable
private fun FavoriteCard(
    file: File,
    loc: FavLocation,
    onJump: () -> Unit,
    onUnpin: () -> Unit,
) {
    val (badgeBg, badgeFg) = badgeColors(loc)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onJump),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // Origin line: coloured drive badge + source description.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = loc.driveLabel,
                        color = badgeFg,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    when {
                        loc.containerName != null -> Row {
                            Text(
                                text = "Container: ",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            Text(
                                text = "\"${loc.containerName}\"",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> Text(
                            text = when (loc.storage) {
                                FavStorage.INTERNAL -> "Internal storage"
                                FavStorage.SD -> "SD card"
                                FavStorage.CONTAINER -> "System files (shared)"  // Drive Z:
                                FavStorage.OTHER -> "Storage"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = loc.displayPath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onUnpin) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Remove from favorites",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
