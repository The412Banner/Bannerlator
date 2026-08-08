package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SportsEsports
import com.winlator.star.ui.components.CollapsibleRail
import com.winlator.star.ui.components.RailItem
import com.winlator.star.ui.components.RailSection
import com.winlator.star.ui.components.rememberRailState
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.winlator.star.core.StorageRoots
import com.winlator.star.core.StringUtils
import com.winlator.star.core.GameIdentifier
import com.winlator.star.core.WinePath
import com.winlator.star.util.FavoritesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What to do when a pasted item already exists at the destination.
 *
 * OVERWRITE and MERGE resolve to the same call — `copyWithProgress` recurses into an existing
 * directory and truncates existing files — but they mean different things to the user, so both are
 * offered and the wording is chosen per item type (files overwrite, folders merge).
 */
enum class ConflictChoice { OVERWRITE, MERGE, KEEP_BOTH, SKIP }

/**
 * Ordering for the file list. Folders always lead regardless of direction — a descending sort that
 * buries every folder under the files is never what someone means by "Z to A".
 */
/**
 * Shortens a path from the LEFT, keeping whole segments.
 *
 * Compose's TextOverflow can only ellipsise the tail, which for a path throws away the part that
 * matters — `/storage/emulated/0/Winlator/Game…` tells you nothing about where you are.
 */
private fun elidePathStart(path: String, max: Int): String {
    if (path.length <= max) return path
    val parts = path.split('/').filter { it.isNotEmpty() }
    val out = StringBuilder()
    for (part in parts.asReversed()) {
        if (out.length + part.length + 1 > max - 2) break
        out.insert(0, "/$part")
    }
    return if (out.isEmpty()) "…" + path.takeLast(max - 1) else "…$out"
}

private fun comparatorFor(sortBy: String, desc: Boolean): Comparator<File> {
    val inner: Comparator<File> = when (sortBy) {
        "date" -> compareBy { it.lastModified() }
        // Directory length() is meaningless, so folders sort by name within the size ordering
        // instead of pretending to have one.
        "size" -> compareBy { if (it.isDirectory) -1L else it.length() }
        else -> compareBy { it.name.lowercase() }
    }
    val directed = if (desc) inner.reversed() else inner
    return compareBy<File> { if (it.isDirectory) 0 else 1 }.then(directed)
}

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
fun describeLocation(
    context: Context,
    file: File,
    containers: List<Container>,
    imagefsDir: File,
): FavLocation {
    val abs = file.absolutePath

    for (container in containers) {
        val driveC = File(container.rootDir, ".wine/drive_c").absolutePath
        if (abs == driveC || abs.startsWith("$driveC/")) {
            val remainder = abs.removePrefix(driveC).replace('/', '\\')
            return FavLocation(
                storage = FavStorage.CONTAINER,
                containerName = container.name,
                driveLabel = context.getString(R.string.compose_files_drive_c),
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
            driveLabel = context.getString(R.string.compose_files_drive_z),
            displayPath = "Z:$remainder",
        )
    }

    val internal = "/storage/emulated/0"
    if (abs == internal || abs.startsWith("$internal/")) {
        return FavLocation(
            FavStorage.INTERNAL,
            null,
            context.getString(R.string.compose_files_internal),
            abs,
        )
    }

    if (abs.startsWith("/storage/")) {
        val name = abs.removePrefix("/storage/").substringBefore('/')
        if (name.isNotEmpty() && name != "emulated" && name != "self") {
            return FavLocation(
                FavStorage.SD,
                null,
                context.getString(R.string.compose_files_sd_card),
                abs,
            )
        }
    }

    return FavLocation(
        FavStorage.OTHER,
        null,
        context.getString(R.string.compose_files_storage),
        abs,
    )
}

// Semantic identity colours for the favourite-card drive badge. Intentionally NOT theme
// accent colours — they identify the storage source at a glance. Returns (background, foreground).
private fun badgeColors(loc: FavLocation): Pair<Color, Color> {
    val white = Color(0xFFFFFFFF)
    return when {
        loc.storage == FavStorage.INTERNAL -> Color(0xFF2E5FB0) to white   // blue
        loc.storage == FavStorage.SD -> Color(0xFF2E7D32) to white         // green
        loc.storage == FavStorage.CONTAINER && loc.containerName == null ->
            Color(0xFF6A3FB0) to white                                    // purple (Drive Z:)
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
    val browsePrefs = pickPrefs
    val rootDir = remember {
        if (pickMode) {
            val remembered = pickPrefs.getString("lastFilePickerDir", null)?.let { File(it) }?.takeIf { it.isDirectory }
            initialDir?.takeIf { it.isDirectory }
                ?: remembered
                ?: File("/sdcard/Download/").takeIf { it.isDirectory }
                ?: File("/storage/emulated/0")
        } else {
            // Browse mode used to ignore initialDir entirely and always open at internal storage.
            // The Log Manager passes a game's log folder here, so honour it in both modes; falling
            // back to internal storage keeps the plain File Manager destination unchanged.
            initialDir?.takeIf { it.isDirectory } ?: File("/storage/emulated/0")
        }
    }

    var currentDir by remember { mutableStateOf(rootDir) }
    var currentRoot by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf(listOf<File>()) }
    var selectedEntry by remember { mutableStateOf<File?>(null) }
    var showMenuFor by remember { mutableStateOf<File?>(null) }
    // Clipboard holds a LIST so one paste can carry a whole selection. Cut/copy semantics are a
    // flag on the batch rather than per item — mixing the two in one clipboard has no sane meaning.
    var clipboardFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isCutOperation by remember { mutableStateOf(false) }
    // Multi-select. Keyed by absolute path rather than File so a directory reload (which builds
    // fresh File objects) doesn't silently drop the selection.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Paste conflict resolution, surfaced from the IO coroutine and answered by the dialog.
    var pendingConflict by remember { mutableStateOf<File?>(null) }
    var conflictChoice by remember { mutableStateOf<ConflictChoice?>(null) }
    var conflictApplyToAll by remember { mutableStateOf(false) }
    // Set while a copy/move runs so the progress UI can offer a cancel.
    var operationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<List<File>>(emptyList()) }
    // Browse controls. Persisted so the list doesn't reset its order every time you open a folder.
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(browsePrefs.getString("fmSortBy", "name") ?: "name") }
    var sortDesc by remember { mutableStateOf(browsePrefs.getBoolean("fmSortDesc", false)) }
    var showHidden by remember { mutableStateOf(browsePrefs.getBoolean("fmShowHidden", true)) }
    var showSortMenu by remember { mutableStateOf(false) }
    // View mode: list of cards (default) or a thumbnail grid. Density applies to the list only —
    // a grid tile has no second line to compact.
    // The grid/list toggle is the SOURCE OF TRUTH in BOTH orientations (it drives the view and its
    // choice persists across rotation). Grid is the default — most useful in landscape, and in
    // portrait GridCells.Adaptive naturally renders fewer columns (~2). Do NOT force portrait to list:
    // that broke the toggle on-device (tapping it did nothing in portrait).
    var gridView by remember { mutableStateOf(browsePrefs.getBoolean("fmGridView", true)) }
    val showGrid = gridView
    var compactRows by remember { mutableStateOf(browsePrefs.getBoolean("fmCompactRows", false)) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var pendingRun by remember { mutableStateOf<File?>(null) }
    var pendingAddShortcut by remember { mutableStateOf<File?>(null) }
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
                    // Dotfiles are noise in a storage root (.aya, .$recycle_bin$) but occasionally
                    // the thing you came for, so it's a toggle rather than a permanent filter.
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.sortedWith(comparatorFor(sortBy, sortDesc)) ?: emptyList()
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
                }
            }.getOrElse { failure ->
                val message = if (failure is WinePath.NoFreeDriveLetterException) {
                    context.getString(R.string.compose_files_no_free_drive_letters)
                } else {
                    context.getString(R.string.compose_files_prepare_to_run_failed, file.name)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_no_container),
                    Toast.LENGTH_SHORT,
                ).show()
            containers.size == 1 -> runFileInContainer(file, containers.first())
            else -> pendingRun = file   // ask which container
        }
    }

    // Create a PERMANENT Games/Shortcuts tile for [file] in [container] — same result as the
    // Games-tab "+" button (ExeShortcutImporter writes into the container's desktop dir, so it
    // shows up in the Shortcuts list and picks up cover art). Unlike runFile's throwaway desktop.
    //
    // Identify the game first, exactly as the "+" flow does. Passing the raw exe basename and no
    // appId quietly cost most of the importer's work: without an appId it skips Steam's own 600x900
    // portrait and the SGDB-by-appId lookup, and never applies Steam's authoritative name — leaving
    // only an SGDB-by-name search running on a string like "dirt3_game", which finds nothing. Same
    // importer, far worse result, purely because the call site under-fed it.
    fun addShortcutInContainer(file: File, container: Container) {
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = runCatching { GameIdentifier.identify(file) }.getOrNull()
                    val displayName = identity?.name?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension
                    ExeShortcutImporter.addToShortcuts(
                        context, container, file, displayName, identity?.appId,
                    ).nameWithoutExtension
                }.getOrNull()
            }
            if (name == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_add_shortcut_failed, file.name),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_shortcut_added, name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun addToShortcuts(file: File) {
        containerManager ?: return
        when {
            containers.isEmpty() ->
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_no_container),
                    Toast.LENGTH_SHORT,
                ).show()
            containers.size == 1 -> addShortcutInContainer(file, containers.first())
            else -> pendingAddShortcut = file   // ask which container
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
            operationLabel = context.getString(R.string.compose_files_deleting)
            val ok = withContext(Dispatchers.IO) { FileUtils.delete(file) }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) {
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_delete_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Waits for the user to answer the conflict dialog for [file]; null if they cancelled it. */
    suspend fun askConflict(file: File): ConflictChoice? {
        pendingConflict = file
        conflictChoice = null
        // Poll rather than plumb a CompletableDeferred through Compose state — the dialog answers
        // by setting conflictChoice, and this coroutine is already off the critical path.
        while (pendingConflict != null && conflictChoice == null) kotlinx.coroutines.delay(50)
        return conflictChoice
    }

    fun performPaste() {
        val sources = clipboardFiles
        if (sources.isEmpty()) return
        val dstDir = currentDir
        val cut = isCutOperation

        operationJob = scope.launch {
            operationProgress = 0f
            operationDeterminate = true
            operationLabel = context.getString(
                if (cut) R.string.compose_files_moving else R.string.compose_files_copying
            )
            isOperationRunning = true

            var applyToAll: ConflictChoice? = null
            var failed = 0
            var skipped = 0
            var done = 0

            for (src in sources) {
                // Pasting a folder into itself or its own subtree would recurse forever.
                if (src.isDirectory && isWithin(dstDir, src)) {
                    failed++
                    continue
                }
                // Moving into the folder it already sits in is a no-op.
                if (cut && src.parentFile?.absolutePath == dstDir.absolutePath) {
                    skipped++
                    continue
                }

                var dst = File(dstDir, src.name)
                if (dst.exists()) {
                    val choice = applyToAll ?: askConflict(src)?.also {
                        if (conflictApplyToAll) applyToAll = it
                    } ?: run { skipped++; null } ?: continue
                    when (choice) {
                        // Overwrite and Merge both paste onto the real destination: copyWithProgress
                        // recurses into an existing directory and truncates existing files, so the
                        // two differ only in what the user expects, not in what we call.
                        ConflictChoice.OVERWRITE, ConflictChoice.MERGE -> Unit
                        ConflictChoice.KEEP_BOTH -> dst = uniqueDestination(dstDir, src.name)
                        ConflictChoice.SKIP -> { skipped++; continue }
                    }
                }

                // Progress is per item; with a batch the label carries the overall position.
                operationLabel = if (sources.size > 1) {
                    context.getString(
                        if (cut) {
                            R.string.compose_files_moving_item
                        } else {
                            R.string.compose_files_copying_item
                        },
                        done + 1,
                        sources.size,
                        src.name,
                    )
                } else {
                    context.getString(
                        if (cut) {
                            R.string.compose_files_moving_file
                        } else {
                            R.string.compose_files_copying_file
                        },
                        src.name,
                    )
                }
                var lastPct = -1
                val onProgress = FileUtils.ProgressCallback { copied, total ->
                    val pct = if (total > 0) ((copied * 100) / total).toInt() else 100
                    if (pct != lastPct) {
                        lastPct = pct
                        operationProgress = pct / 100f
                    }
                }
                val target = dst
                val ok = withContext(Dispatchers.IO) {
                    if (cut) FileUtils.moveWithProgress(src, target, onProgress)
                    else FileUtils.copyWithProgress(src, target, onProgress)
                }
                if (ok) done++ else failed++
            }

            isOperationRunning = false
            operationDeterminate = false
            operationJob = null
            clipboardFiles = emptyList()
            isCutOperation = false
            selectionMode = false
            selectedPaths = emptySet()
            loadDirectory(currentDir, resetScroll = false)

            val message = when {
                failed > 0 -> context.resources.getQuantityString(
                    R.plurals.compose_files_operation_failed_summary,
                    failed,
                    done,
                    failed,
                )
                skipped > 0 -> context.resources.getQuantityString(
                    R.plurals.compose_files_operation_skipped_summary,
                    skipped,
                    done,
                    skipped,
                )
                sources.size > 1 -> context.resources.getQuantityString(
                    if (cut) {
                        R.plurals.compose_files_items_moved
                    } else {
                        R.plurals.compose_files_items_copied
                    },
                    done,
                    done,
                )
                else -> null
            }
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun performRename(file: File, newName: String) {
        val target = File(file.parentFile, newName)
        if (target.exists()) {
            Toast.makeText(
                context,
                context.getString(R.string.compose_files_name_exists, newName),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        scope.launch {
            isOperationRunning = true
            operationLabel = context.getString(R.string.compose_files_renaming)
            val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) {
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_rename_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun createFolder(parent: File, name: String) {
        val target = File(parent, name)
        if (target.exists()) {
            Toast.makeText(
                context,
                context.getString(R.string.compose_files_name_exists, name),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        scope.launch {
            isOperationRunning = true
            operationLabel = context.getString(R.string.compose_files_creating_folder)
            val ok = withContext(Dispatchers.IO) { target.mkdirs() }
            isOperationRunning = false
            loadDirectory(currentDir, resetScroll = false)
            if (!ok) {
                Toast.makeText(
                    context,
                    context.getString(R.string.compose_files_create_folder_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    var showDriveMenu by remember { mutableStateOf(false) }
    var showContainerPicker by remember { mutableStateOf(false) }
    // Re-enumerated whenever we come back to the screen: returning from a container can leave this
    // process on a stale storage view, and the volume set has to be re-read rather than cached.
    var storageTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val drives = remember(storageTick) { StorageRoots.list(context) }
    val internalStorageLabel = stringResource(R.string.compose_files_internal)
    val sdCardLabel = stringResource(R.string.compose_files_sd_card)
    val genericStorageLabel = stringResource(R.string.compose_files_storage)
    fun localizedStorageLabel(label: String): String = when {
        label == "Internal" -> internalStorageLabel
        label == "SD card" -> sdCardLabel
        label.startsWith("SD card (") -> sdCardLabel + label.removePrefix("SD card")
        label == "Storage" -> genericStorageLabel
        else -> label
    }

    // ── Dialogs ──

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        OutlinedAlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.compose_files_new_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.compose_files_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewFolderDialog = false
                    if (folderName.isNotBlank()) createFolder(currentDir, folderName)
                }) { Text(stringResource(R.string.compose_files_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
        )
    }

    if (renameTarget != null) {
        var newName by remember(renameTarget) { mutableStateOf(renameTarget?.name ?: "") }
        OutlinedAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.compose_files_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.compose_files_new_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = renameTarget
                    renameTarget = null
                    if (file != null && newName.isNotBlank()) performRename(file, newName)
                }) { Text(stringResource(R.string.compose_files_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
        )
    }

    if (selectedEntry != null && selectedEntry != showMenuFor) {
        val file = selectedEntry ?: return
        OutlinedAlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(stringResource(R.string.compose_files_delete_question)) },
            text = {
                Text(
                    stringResource(
                        R.string.compose_files_delete_file_permanently,
                        file.name,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedEntry = null
                    performDelete(file)
                }) { Text(stringResource(R.string.compose_files_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntry = null }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
        )
    }

    if (pendingBulkDelete.isNotEmpty()) {
        val victims = pendingBulkDelete
        OutlinedAlertDialog(
            onDismissRequest = { pendingBulkDelete = emptyList() },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.compose_files_delete_items_question,
                        victims.size,
                        victims.size,
                    )
                )
            },
            text = {
                Column {
                    Text(stringResource(R.string.compose_files_cannot_undo))
                    Spacer(Modifier.height(6.dp))
                    // Name a few so an accidental Select-All is obvious before it's too late.
                    victims.take(5).forEach {
                        Text(
                            stringResource(R.string.compose_files_bullet_item, it.name),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    if (victims.size > 5) {
                        val remaining = victims.size - 5
                        Text(
                            pluralStringResource(
                                R.plurals.compose_files_more_items,
                                remaining,
                                remaining,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulkDelete = emptyList()
                    selectionMode = false
                    selectedPaths = emptySet()
                    operationJob = scope.launch {
                        isOperationRunning = true
                        var failed = 0
                        victims.forEachIndexed { i, f ->
                            operationLabel = context.getString(
                                R.string.compose_files_deleting_item,
                                i + 1,
                                victims.size,
                                f.name,
                            )
                            if (!withContext(Dispatchers.IO) { FileUtils.delete(f) }) failed++
                        }
                        isOperationRunning = false
                        operationJob = null
                        loadDirectory(currentDir, resetScroll = false)
                        if (failed > 0) {
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.compose_files_could_not_delete,
                                    failed,
                                    failed,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.compose_files_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = emptyList() }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
        )
    }

    // Paste conflict — one per colliding item, with "apply to all" for a long batch.
    pendingConflict?.let { conflict ->
        val isDir = conflict.isDirectory
        val primaryConflictLabel = stringResource(
            if (isDir) R.string.compose_files_merge else R.string.compose_files_overwrite
        )
        val keepBothLabel = stringResource(R.string.compose_files_keep_both)
        val skipLabel = stringResource(R.string.compose_files_skip)
        OutlinedAlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = {
                Text(stringResource(R.string.compose_files_conflict_title, conflict.name))
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (isDir) {
                                R.string.compose_files_merge_description
                            } else {
                                R.string.compose_files_overwrite_description
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    if (clipboardFiles.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = conflictApplyToAll,
                                onCheckedChange = { conflictApplyToAll = it },
                            )
                            Text(
                                stringResource(R.string.compose_files_apply_all_conflicts),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    listOf(
                        (if (isDir) ConflictChoice.MERGE else ConflictChoice.OVERWRITE) to
                            primaryConflictLabel,
                        ConflictChoice.KEEP_BOTH to keepBothLabel,
                        ConflictChoice.SKIP to skipLabel,
                    ).forEach { (choice, label) ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { conflictChoice = choice; pendingConflict = null },
                        ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { conflictChoice = ConflictChoice.SKIP; pendingConflict = null }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
        )
    }

    if (showContainerPicker) {
        OutlinedAlertDialog(
            onDismissRequest = { showContainerPicker = false },
            title = { Text(stringResource(R.string.compose_files_choose_container)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
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
        OutlinedAlertDialog(
            onDismissRequest = { pendingRun = null },
            title = { Text(stringResource(R.string.compose_files_run_which_container)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
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
            dismissButton = {
                TextButton(onClick = { pendingRun = null }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
        )
    }

    if (pendingAddShortcut != null) {
        val file = pendingAddShortcut
        OutlinedAlertDialog(
            onDismissRequest = { pendingAddShortcut = null },
            title = { Text(stringResource(R.string.compose_files_add_which_container)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    containers.forEach { container ->
                        Text(
                            text = container.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingAddShortcut = null
                                    if (file != null) addShortcutInContainer(file, container)
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingAddShortcut = null }) {
                    Text(stringResource(R.string.compose_files_cancel))
                }
            },
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
                Text(stringResource(R.string.compose_files_select_folder))
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
                Icon(
                    Icons.Filled.ArrowBack,
                    stringResource(R.string.compose_files_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            val currentDriveLabel =
                describeLocation(context, currentDir, containers, imagefsDir).driveLabel
            // Dim the drive chip while the Favorites list is open (it's not the active context).
            val driveChipAlpha = if (showFavorites) 0.45f else 1f
            Box {
                // The drive/location selector opens the drive dropdown, so give it the same outlined
                // look as the "New Folder" button + the rail location items — it reads as a button, not
                // plain text. Border uses the theme accent token; behaviour unchanged.
                val driveChipShape = RoundedCornerShape(8.dp)
                Text(
                    text = stringResource(
                        R.string.compose_files_drive_selector,
                        currentDriveLabel,
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = driveChipAlpha),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(driveChipShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), driveChipShape)
                        .clickable { showDriveMenu = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                DropdownMenu(
                    expanded = showDriveMenu,
                    onDismissRequest = { showDriveMenu = false },
                    modifier = Modifier.outlinedMenuCard(),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_files_drive_c)) },
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
                        text = { Text(stringResource(R.string.compose_files_drive_z)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            showDriveMenu = false
                            openDrive(imagefsDir)
                        },
                    )
                    drives.forEach { drive ->
                        MenuItemDivider()
                        DropdownMenuItem(
                            text = { Text(localizedStorageLabel(drive.label)) },
                            leadingIcon = {
                                Icon(
                                    if (drive.removable) Icons.Filled.SdStorage else Icons.Filled.Storage,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                showDriveMenu = false
                                if (drive.readable) {
                                    openDrive(drive.dir)
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.compose_files_drive_unreadable,
                                            localizedStorageLabel(drive.label),
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            if (showFavorites) {
                Text(
                    text = stringResource(R.string.compose_files_star_favorites),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // PORTRAIT: hide the current-folder name — it's redundant with the path bar directly
                // below (which shows the full path). The spacer keeps the action icons right-aligned.
                Spacer(Modifier.weight(1f))
            } else {
                // LANDSCAPE: the CURRENT FOLDER, not the full path. A path ellipsised on the right
                // hides its tail — the only part that says where you are ("…/Winlator/Game…"). The
                // full path moves to the line below, where it has room.
                Text(
                    text = currentDir.name.ifBlank { currentDir.absolutePath },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!showFavorites) {
                // New Folder moved off a bottom bar into the toolbar (next to the grid/list toggle),
                // as a compact outlined button, so the file list reclaims that bottom strip.
                if (!pickMode) {
                    OutlinedButton(
                        onClick = { showNewFolderDialog = true },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            stringResource(R.string.compose_files_new_folder),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 12.sp,
                        )
                    }
                }
                IconButton(onClick = {
                    gridView = !gridView
                    browsePrefs.edit().putBoolean("fmGridView", gridView).apply()
                }) {
                    Icon(
                        if (gridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                        stringResource(
                            if (gridView) {
                                R.string.compose_files_list_view
                            } else {
                                R.string.compose_files_grid_view
                            }
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                    Icon(
                        Icons.Filled.Search,
                        stringResource(R.string.compose_files_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            Icons.Filled.Sort,
                            stringResource(R.string.compose_files_sort),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        listOf(
                            "name" to stringResource(R.string.compose_files_sort_name),
                            "date" to stringResource(R.string.compose_files_sort_date),
                            "size" to stringResource(R.string.compose_files_sort_size),
                        )
                            .forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (sortBy == key) {
                                                stringResource(
                                                    R.string.compose_files_sort_selected,
                                                    label,
                                                    if (sortDesc) "↓" else "↑",
                                                )
                                            } else {
                                                label
                                            }
                                        )
                                    },
                                    onClick = {
                                        // Tapping the active field flips direction; a different
                                        // field switches to it ascending.
                                        if (sortBy == key) sortDesc = !sortDesc else { sortBy = key; sortDesc = false }
                                        browsePrefs.edit().putString("fmSortBy", sortBy)
                                            .putBoolean("fmSortDesc", sortDesc).apply()
                                        showSortMenu = false
                                        loadDirectory(currentDir, resetScroll = false)
                                    },
                                )
                            }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (compactRows) {
                                            R.string.compose_files_comfortable_rows
                                        } else {
                                            R.string.compose_files_compact_rows
                                        }
                                    )
                                )
                            },
                            onClick = {
                                compactRows = !compactRows
                                browsePrefs.edit().putBoolean("fmCompactRows", compactRows).apply()
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (showHidden) {
                                            R.string.compose_files_hide_hidden
                                        } else {
                                            R.string.compose_files_show_hidden
                                        }
                                    )
                                )
                            },
                            onClick = {
                                showHidden = !showHidden
                                browsePrefs.edit().putBoolean("fmShowHidden", showHidden).apply()
                                showSortMenu = false
                                loadDirectory(currentDir, resetScroll = false)
                            },
                        )
                    }
                }
            }

            // Star toggle: open/close the dedicated Favorites list.
            IconButton(onClick = { showFavorites = !showFavorites }) {
                if (showFavorites) {
                    Icon(
                        Icons.Filled.Star,
                        stringResource(R.string.compose_files_hide_favorites),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.StarBorder,
                        stringResource(R.string.compose_files_show_favorites),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Search field ── filters the current folder only; it is not a recursive search.
        if (showSearch && !showFavorites) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = {
                    Text(
                        stringResource(R.string.compose_files_filter_folder),
                        fontSize = 13.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // Free space on the volume being browsed — worth knowing before starting a 60 GB copy.
        val freeSpace = remember(currentDir.absolutePath, entries) {
            runCatching { currentDir.usableSpace }.getOrDefault(0L)
        }
        if (!showFavorites) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Text(
                    // Elided from the LEFT: the deepest part of a path is the informative part, so
                    // when it doesn't fit we drop the /storage/emulated/0 prefix, not the tail.
                    text = elidePathStart(currentDir.absolutePath, 52),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    // fill = true: the path takes all remaining width, so the free-space figure
                    // is pinned to the right edge instead of sliding around with the path length.
                    modifier = Modifier.weight(1f),
                )
                if (freeSpace > 0) {
                    Text(
                        stringResource(
                            R.string.compose_files_free_space,
                            StringUtils.formatBytes(freeSpace),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ── Selection bar ── replaces the paste banner while picking items.
        if (selectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.compose_files_selected,
                        selectedPaths.size,
                        selectedPaths.size,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selectedPaths = if (selectedPaths.size == entries.size) emptySet()
                    else entries.map { it.absolutePath }.toSet()
                }) {
                    Text(
                        stringResource(
                            if (selectedPaths.size == entries.size) {
                                R.string.compose_files_none
                            } else {
                                R.string.compose_files_all
                            }
                        ),
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = {
                        clipboardFiles = entries.filter { it.absolutePath in selectedPaths }
                        isCutOperation = false
                        selectionMode = false
                        selectedPaths = emptySet()
                    },
                ) { Text(stringResource(R.string.compose_files_copy), fontSize = 12.sp) }
                TextButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = {
                        clipboardFiles = entries.filter { it.absolutePath in selectedPaths }
                        isCutOperation = true
                        selectionMode = false
                        selectedPaths = emptySet()
                    },
                ) { Text(stringResource(R.string.compose_files_cut), fontSize = 12.sp) }
                TextButton(
                    enabled = selectedPaths.isNotEmpty(),
                    onClick = { pendingBulkDelete = entries.filter { it.absolutePath in selectedPaths } },
                ) {
                    Text(
                        stringResource(R.string.compose_files_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { selectionMode = false; selectedPaths = emptySet() }) {
                    Text(stringResource(R.string.compose_files_done_action), fontSize = 12.sp)
                }
            }
        }

        // ── Paste banner ──
        if (clipboardFiles.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    .clickable { performPaste() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.ContentPaste,
                    stringResource(R.string.compose_files_paste),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                val what = if (clipboardFiles.size == 1) clipboardFiles.first().name
                else pluralStringResource(
                    R.plurals.compose_files_items,
                    clipboardFiles.size,
                    clipboardFiles.size,
                )
                Text(
                    stringResource(
                        if (isCutOperation) {
                            R.string.compose_files_move_here
                        } else {
                            R.string.compose_files_paste_here
                        },
                        what,
                    ),
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboardFiles = emptyList(); isCutOperation = false }) {
                    Text(
                        stringResource(R.string.compose_files_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (operationDeterminate) {
                            stringResource(
                                R.string.compose_files_operation_percent,
                                operationLabel,
                                (operationProgress * 100).toInt(),
                            )
                        } else {
                            operationLabel
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // A multi-gigabyte copy onto a slow card is exactly when you discover you
                    // picked the wrong folder; without this the only way out was killing the app.
                    if (operationJob != null) {
                        TextButton(onClick = {
                            operationJob?.cancel()
                            operationJob = null
                            isOperationRunning = false
                            operationDeterminate = false
                            loadDirectory(currentDir, resetScroll = false)
                        }) {
                            Text(
                                stringResource(R.string.compose_files_cancel),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
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

        // ── Left locations rail (mockup Option 2) + content ──
        // Shared collapsible rail: landscape expanded by default, portrait collapsed icon-only. Not
        // shown in pick mode (the themed picker keeps its slim layout). Built each recompose (cheap)
        // so it tracks the current drive/favourites without stale click lambdas.
        val fmRailState = rememberRailState("filemanager")
        fun locItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, dir: File) =
            RailItem(label, icon, !showFavorites && currentRoot.absolutePath == dir.absolutePath) {
                showFavorites = false; openDrive(dir)
        }
        val downloadsLabel = stringResource(R.string.compose_files_downloads)
        val gamesLabel = stringResource(R.string.compose_files_games)
        val picturesLabel = stringResource(R.string.compose_files_pictures)
        val storageSectionLabel = stringResource(R.string.compose_files_storage_section)
        val quickSectionLabel = stringResource(R.string.compose_files_quick_section)
        val favoritesSectionLabel = stringResource(R.string.compose_files_star_favorites)
        val storageItems = buildList {
            add(
                locItem(
                    internalStorageLabel,
                    Icons.Filled.Smartphone,
                    File("/storage/emulated/0"),
                )
            )
            drives.filter { it.removable }.forEach { d ->
                add(RailItem(localizedStorageLabel(d.label), Icons.Filled.SdStorage, !showFavorites && currentRoot.absolutePath == d.dir.absolutePath) {
                    showFavorites = false; if (d.readable) openDrive(d.dir)
                })
            }
        }
        val quickItems = buildList {
            File("/storage/emulated/0/Download").takeIf { it.isDirectory }?.let {
                add(
                    locItem(
                        downloadsLabel,
                        Icons.Filled.Download,
                        it,
                    )
                )
            }
            File("/storage/emulated/0/Winlator/Games").takeIf { it.isDirectory }?.let {
                add(
                    locItem(
                        gamesLabel,
                        Icons.Filled.SportsEsports,
                        it,
                    )
                )
            }
            File("/storage/emulated/0/Pictures").takeIf { it.isDirectory }?.let {
                add(
                    locItem(
                        picturesLabel,
                        Icons.Filled.Image,
                        it,
                    )
                )
            }
        }
        val favItems = remember(favTick) { FavoritesStore.list(context).map(::File).filter { it.exists() } }
            .map { d -> RailItem(d.name, Icons.Filled.Star, false) { showFavorites = false; openDrive(d) } }
        val locationSections = buildList {
            add(RailSection(storageSectionLabel, storageItems))
            if (quickItems.isNotEmpty()) {
                add(RailSection(quickSectionLabel, quickItems))
            }
            if (favItems.isNotEmpty()) {
                add(RailSection(favoritesSectionLabel, favItems))
            }
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!pickMode) {
                CollapsibleRail(
                    state = fmRailState,
                    title = stringResource(R.string.compose_files_files),
                    sections = locationSections,
                    outlinedItems = true,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
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
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.compose_files_favorite_added,
                            currentDir.name,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onJump = { dir ->
                    showFavorites = false
                    openDrive(dir)
                },
                onUnpin = { dir ->
                    FavoritesStore.remove(context, dir.absolutePath)
                    favTick++
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.compose_files_favorite_removed,
                            dir.name,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        // ── File list (pull down to refresh) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            val shownEntries = if (searchQuery.isBlank()) entries
            else entries.filter { it.name.contains(searchQuery, ignoreCase = true) }

            if (showGrid && entries.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(shownEntries, key = { it.absolutePath }) { file ->
                        FileGridTile(
                            file = file,
                            selectionMode = selectionMode,
                            selected = file.absolutePath in selectedPaths,
                            onLongPress = {
                                if (!pickMode) {
                                    selectionMode = true
                                    selectedPaths = selectedPaths + file.absolutePath
                                }
                            },
                            onToggleSelect = {
                                selectedPaths = if (file.absolutePath in selectedPaths)
                                    selectedPaths - file.absolutePath
                                else selectedPaths + file.absolutePath
                            },
                            onTap = {
                                if (file.isDirectory) loadDirectory(file)
                                else if (pickMode) {
                                    if (matchesPickExt(file)) {
                                        pickPrefs.edit().putString("lastFilePickerDir", currentDir.absolutePath).apply()
                                        onPick?.invoke(file)
                                    }
                                } else if (canRun(file)) runFile(file)
                            },
                            onMenu = { showMenuFor = file },
                        )
                    }
                }
            } else
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.compose_files_empty_directory),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val shown = shownEntries
                    items(shown, key = { it.absolutePath }) { file ->
                        val isFav = remember(file.absolutePath, favTick) {
                            FavoritesStore.isFavorite(context, file.absolutePath)
                        }
                        FileItemRow(
                            file = file,
                            showActions = !pickMode,
                            compact = compactRows,
                            selectionMode = selectionMode,
                            selected = file.absolutePath in selectedPaths,
                            onLongPress = {
                                // Long-press is the only entry point into selection mode, matching
                                // how every Android file manager behaves.
                                if (!pickMode) {
                                    selectionMode = true
                                    selectedPaths = selectedPaths + file.absolutePath
                                }
                            },
                            onToggleSelect = {
                                selectedPaths = if (file.absolutePath in selectedPaths)
                                    selectedPaths - file.absolutePath
                                else selectedPaths + file.absolutePath
                            },
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
                            onAddToShortcuts = { addToShortcuts(file) },
                            onCopy = { clipboardFiles = listOf(file); isCutOperation = false; showMenuFor = null },
                            onCut = { clipboardFiles = listOf(file); isCutOperation = true; showMenuFor = null },
                            onDelete = { selectedEntry = file; showMenuFor = null },
                            onRename = { renameTarget = file; showMenuFor = null },
                            onFastExtract = {
                                showMenuFor = null
                                scope.launch {
                                    when (val o = com.winlator.star.core.unpack.FastExtract.start(context, file)) {
                                        is com.winlator.star.core.unpack.FastExtract.Outcome.Started ->
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.compose_files_unpacking,
                                                    o.name,
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        com.winlator.star.core.unpack.FastExtract.Outcome.Busy ->
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.compose_files_unpack_busy),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        is com.winlator.star.core.unpack.FastExtract.Outcome.NotArchive ->
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.compose_files_not_archive),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        is com.winlator.star.core.unpack.FastExtract.Outcome.OpenScreen -> {
                                            o.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                            context.startActivity(
                                                com.winlator.star.UnpackArchiveActivity.intent(context, o.archivePath)
                                            )
                                        }
                                    }
                                }
                            },
                            onUnpack = {
                                showMenuFor = null
                                context.startActivity(
                                    com.winlator.star.UnpackArchiveActivity.intent(context, file.absolutePath)
                                )
                            },
                            isFavorite = isFav,
                            onToggleFavorite = {
                                val nowFav = FavoritesStore.toggle(context, file.absolutePath)
                                favTick++
                                showMenuFor = null
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (nowFav) {
                                            R.string.compose_files_favorite_added
                                        } else {
                                            R.string.compose_files_favorite_removed
                                        },
                                        file.name,
                                    ),
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
            } // end content Box (beside the rail)
        } // end rail + content Row
        // (New Folder moved into the top toolbar; the bottom bar was removed to reclaim its strip.)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileItemRow(
    file: File,
    showActions: Boolean = true,
    compact: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    onTap: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRun: () -> Unit,
    onAddToShortcuts: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onUnpack: () -> Unit = {},
    onFastExtract: () -> Unit = {},
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
            .combinedClickable(
                // In selection mode a tap toggles instead of opening, so you can rattle through a
                // folder without long-pressing every single row.
                onClick = { if (selectionMode) onToggleSelect() else onTap() },
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 8.dp),
        ) {
            if (selectionMode) {
                androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Spacer(Modifier.width(4.dp))
            }
            when {
                // Show the executable's own embedded icon when we managed to extract one.
                exeIcon != null -> Image(
                    bitmap = exeIcon!!,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 24.dp else 36.dp),
                )
                isExe -> Icon(
                    painter = painterResource(R.drawable.icon_menu_container),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(if (compact) 24.dp else 36.dp),
                )
                isDir -> Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (compact) 24.dp else 36.dp),
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
                    modifier = Modifier.size(if (compact) 24.dp else 36.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                val detailSeparator = stringResource(R.string.compose_files_detail_separator)
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
                        if (!isDir) {
                            append(StringUtils.formatBytes(file.length()))
                                .append(detailSeparator)
                        }
                        append(dateFormat.format(Date(file.lastModified())))
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (showActions) Box {
                IconButton(onClick = onMenu) {
                    Icon(
                        Icons.Filled.MoreVert,
                        stringResource(R.string.compose_files_actions),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onDismissMenu,
                    modifier = Modifier.outlinedMenuCard(),
                ) {
                    // Favorites are directories — only folders get the pin toggle.
                    if (isDir) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isFavorite) {
                                            R.string.compose_files_remove_favorite
                                        } else {
                                            R.string.compose_files_add_favorite
                                        }
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = { onToggleFavorite() },
                        )
                        MenuItemDivider()
                    }
                    if (canRun) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.compose_files_run)) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { onDismissMenu(); onRun() },
                        )
                        MenuItemDivider()
                    }
                    // Only real PE executables can become a permanent Games tile — this reuses the
                    // same importer the Games-tab "+" button uses (Exec=wine <path>), which is only
                    // correct for .exe, so we don't offer it for .bat/.sh/.msi.
                    if (!isDir && file.name.lowercase().endsWith(".exe")) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.compose_files_add_shortcuts))
                            },
                            leadingIcon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { onDismissMenu(); onAddToShortcuts() },
                        )
                        MenuItemDivider()
                    }
                    // The SINGLE extraction action: the bundled 7-Zip engine handles a strict superset
                    // of everything the old in-app extractor did (zip, 7z, tar, gzip, bzip2, xz, zstd)
                    // PLUS disc images (ISO/UDF), RAR, cab, wim, split volumes and 80 GB+ single files.
                    // For an InnoSetup repack (Setup.exe + Setup-*.bin) it becomes "Unpack / Install…",
                    // where the screen decides between 7-Zip payload extraction and running Setup.exe in
                    // a container (FreeArc repacks). The screen also content-sniffs (`7zz l`) so a file
                    // is judged by content, not extension.
                    val isInno = com.winlator.star.core.unpack.SevenZip.isInnoSetup(file)
                    // Content-aware: extension OR a cheap magic-byte sniff, so a .wcp/.bin/renamed
                    // archive with an unlisted extension still gets the option (menu opens per row,
                    // so this reads only a few header bytes on demand — never `7zz l` per entry).
                    if (isInno || com.winlator.star.core.unpack.SevenZip.looksLikeArchive(file)) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (isInno) {
                                            R.string.compose_files_unpack_install
                                        } else {
                                            R.string.compose_files_unpack_archive_action
                                        }
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Unarchive, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { onDismissMenu(); onUnpack() },
                        )
                        MenuItemDivider()
                        // Convenience: one tap, no screen — pre-fill defaults (new sibling folder, Auto
                        // power) and start straight into the progress pill. Same engines/throughput.
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.compose_files_fast_extract))
                            },
                            leadingIcon = { Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { onDismissMenu(); onFastExtract() },
                        )
                        MenuItemDivider()
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_files_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onRename() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_files_copy)) },
                        leadingIcon = { Icon(Icons.Filled.FileCopy, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onCopy() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_files_cut)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { onDismissMenu(); onCut() },
                    )
                    MenuItemDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_files_delete)) },
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
                    text = stringResource(R.string.compose_files_favorites),
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
                        text = stringResource(R.string.compose_files_pin_current_folder),
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
                        text = stringResource(R.string.compose_files_no_favorites),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            items(favorites, key = { it.absolutePath }) { file ->
                val loc = remember(file.absolutePath, containers) {
                    describeLocation(context, file, containers, imagefsDir)
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
                                text = stringResource(R.string.compose_files_container_prefix),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            Text(
                                text = stringResource(
                                    R.string.compose_files_quoted_name,
                                    loc.containerName,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> Text(
                            text = stringResource(
                                when (loc.storage) {
                                    FavStorage.INTERNAL -> R.string.compose_files_internal_storage
                                    FavStorage.SD -> R.string.compose_files_sd_card
                                    FavStorage.CONTAINER ->
                                        R.string.compose_files_system_files_shared // Drive Z:
                                    FavStorage.OTHER -> R.string.compose_files_storage
                                }
                            ),
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
                    contentDescription = stringResource(
                        R.string.compose_files_remove_favorites_description
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * One entry in the File Manager's grid view: a big thumbnail with the name under it.
 *
 * Deliberately drops size and date — at this width they truncate to noise. The grid is for
 * recognising things by sight (screenshots, covers, game folders); the list stays the view for
 * reading details.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridTile(
    file: File,
    selectionMode: Boolean,
    selected: Boolean,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onTap: () -> Unit,
    onMenu: () -> Unit,
) {
    val isDir = file.isDirectory
    val isImage = !isDir && file.extension.lowercase() in IMAGE_THUMB_EXTS
    var exeIcon by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    if (!isDir && file.name.lowercase().endsWith(".exe")) {
        LaunchedEffect(file.absolutePath) {
            val bmp = withContext(Dispatchers.IO) { PeIconExtractor.extract(file) }
            if (bmp != null) exeIcon = bmp.asImageBitmap()
        }
    }
    Card(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onTap() },
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                when {
                    exeIcon != null -> Image(bitmap = exeIcon!!, contentDescription = null, modifier = Modifier.size(48.dp))
                    isDir -> Icon(
                        Icons.Filled.Folder, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    isImage -> AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholder = rememberVectorPainter(Icons.Filled.InsertDriveFile),
                        error = rememberVectorPainter(Icons.Filled.InsertDriveFile),
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    else -> Icon(
                        Icons.Filled.InsertDriveFile, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp),
                    )
                }
                if (selectionMode) {
                    androidx.compose.material3.Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                file.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
