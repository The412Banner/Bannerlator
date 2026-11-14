package com.winlator.star.ui.files

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.MainActivity
import com.winlator.star.UnpackArchiveActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GameIdentifier
import com.winlator.star.core.StorageRoots
import com.winlator.star.core.StringUtils
import com.winlator.star.core.unpack.ArchiveBrowse
import com.winlator.star.core.unpack.ArchiveExtract
import com.winlator.star.core.unpack.ArchivePasswords
import com.winlator.star.core.unpack.FastExtract
import com.winlator.star.core.unpack.SevenZip
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.XmbTapToEdit
import com.winlator.star.ui.deck.xmbDoneActions
import com.winlator.star.ui.screens.ExeQuickRun
import com.winlator.star.ui.screens.ExeShortcutImporter
import com.winlator.star.util.FavoritesStore
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bannerlator's file manager: Home tiles, one or two panes, archives opened like folders, a text editor,
 * copy / move / compress / extract, and the Bannerlator actions (run a program in a container, add it to
 * Games). The same screen is the in-app picker ([pickMode] / [pickDirMode]): then it only browses and
 * returns the chosen file or folder through [onPick].
 *
 * [compactToolbar] (the Deck shell's Tools > Files) swaps the tall toolbar pill, the paste capsule and the
 * path line for one slim row: back, up, the path, then the actions. The clipboard then shows as a floating paste button
 * (with a small clear button) at the bottom right, above the shell's hint row, only while something is copied or cut.
 *
 * [dense] (the XMB mode's picker) tightens everything further: a slimmer toolbar row with smaller icons, rows about 40 dp tall with
 * smaller text, smaller crumbs and Home tiles, a slim title and Select bar. See [LocalFmDense]. Behaviour is unchanged.
 */
@Composable
fun FileManagerRoot(
    pickMode: Boolean = false,
    pickDirMode: Boolean = false,
    pickExtensions: List<String> = emptyList(),
    initialDir: File? = null,
    pickerTitle: String? = null,
    driveCPath: File? = null,
    onPick: ((File) -> Unit)? = null,
    compactToolbar: Boolean = false,
    dense: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val mainActivity = context as? MainActivity
    val imagefsDir = remember { File(context.filesDir, "imagefs") }
    val pick: FmPick? = remember(pickMode, pickDirMode, pickExtensions) {
        if (pickMode || pickDirMode) FmPick(pickDirMode, pickExtensions.map { it.lowercase() }) else null
    }

    // Containers: from the main screen's manager, or read once here (the picker has its own activity).
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    LaunchedEffect(Unit) {
        containers = withContext(Dispatchers.IO) {
            runCatching { (mainActivity?.containerManager ?: ContainerManager(context)).containers.toList() }.getOrDefault(emptyList())
        }
    }
    var storageTick by remember { mutableIntStateOf(0) }
    val drives = remember(storageTick) { runCatching { StorageRoots.list(context) }.getOrDefault(emptyList()) }
    val drivesNow = rememberUpdatedState(drives)

    fun floors(): List<File> = buildList {
        add(File("/storage/emulated/0"))
        drivesNow.value.forEach { add(it.dir) }
        containers.forEach { add(File(it.rootDir, ".wine/drive_c")) }
        add(imagefsDir)
        driveCPath?.let { add(it) }
    }

    val panes = remember {
        listOf(
            FmPane(context, scope, pick) { floors() },
            FmPane(context, scope, pick) { floors() },
        )
    }
    var active by remember { mutableIntStateOf(0) }
    var dualPref by remember { mutableStateOf(FmPrefs.dualPane(context)) }
    val pane = panes[active]

    LaunchedEffect(Unit) {
        val start = initialDir?.takeIf { it.isDirectory }
            ?: if (pick != null) FmPrefs.lastPickerDir(context) else null
        if (start != null) panes[0].openDir(start)
    }

    // Lists refresh in place when you come back to the app (a game or another app may have changed them).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else {
                    storageTick++
                    panes.forEach { if (it.loc != FmLoc.Home) it.refresh() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The operations and the state they work on (clipboard, running operation, dialogs) live in FmOps, which Lab's file
    // manager shares; these locals read and write the same state.
    val ops = remember { FmOps(context, scope, { containers }, { panes.forEach { if (it.loc != FmLoc.Home) it.refresh() } }, { storageTick++ }) }

    // ── Clipboard and running operation ──
    var clipboard by ops::clipboard
    var clipCut by ops::clipCut
    val opLabel by ops::opLabel
    val opProgress by ops::opProgress
    val opJob by ops::opJob

    // ── Dialog state ──
    var newFolderIn by ops::newFolderIn
    var newFileIn by ops::newFileIn
    var renameTarget by ops::renameTarget
    var deleteTargets by ops::deleteTargets
    var propsTarget by ops::propsTarget
    var compressTargets by ops::compressTargets
    var sortFor by remember { mutableStateOf<FmPane?>(null) }
    var extractReq by ops::extractReq
    var runChoice by ops::runChoice
    var shortcutChoice by ops::shortcutChoice
    val conflict by ops::conflict
    var searchOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    // Where "Choose a folder…" should send things once the folder picker answers.
    var pendingChoose by remember { mutableStateOf<((File) -> Unit)?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pendingChoose
        pendingChoose = null
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data)?.let { p -> cb?.invoke(File(p)) }
    }
    fun chooseFolder(title: String, then: (File) -> Unit) {
        pendingChoose = then
        folderPicker.launch(InAppFilePicker.buildDirIntent(context, title = title, initialDir = pane.currentDir?.absolutePath))
    }

    fun refreshAll() = panes.forEach { if (it.loc != FmLoc.Home) it.refresh() }

    // ── File operations (FmOps) ──

    fun paste(sources: List<File>, dest: File, cut: Boolean) = ops.paste(sources, dest, cut)
    fun delete(targets: List<File>) = ops.delete(targets)
    fun rename(file: File, newName: String) = ops.rename(file, newName)
    fun createFolder(parent: File, name: String) = ops.createFolder(parent, name)
    fun createFile(parent: File, name: String) = ops.createFile(parent, name)
    fun compress(files: List<File>, dir: File, name: String, format: PackFormat) = ops.compress(files, dir, name, format)
    fun extractEntries(srcPane: FmPane, entries: List<FmItem.Entry>, dest: File) = ops.extractEntries(srcPane, entries, dest)
    fun share(files: List<File>) = ops.share(files)
    fun runIn(file: File, container: Container) = ops.runIn(file, container)
    fun addShortcutIn(file: File, container: Container) = ops.addShortcutIn(file, container)
    fun toggleFavorite(dir: File) = ops.toggleFavorite(dir)
    fun openItem(p: FmPane, item: FmItem) = ops.openItem(p, item, pick, onPick)

    // ── Menus ──

    fun menuFor(p: FmPane, item: FmItem): (() -> List<FmMenuEntry>)? = when {
        pick != null -> null
        item is FmItem.Disk -> { { ops.diskMenu(p, item) } }
        item is FmItem.Entry -> { { ops.entryMenu(p, item) } }
        else -> null
    }

    // ── Home ──

    fun homeSections(): List<FmHomeSection> = ops.homeSections(drives, imagefsDir, pick, driveCPath)

    // ── Back ──

    BackHandler(enabled = searchOpen || pane.selectionMode || pane.loc != FmLoc.Home || pane.canGoBack) {
        when {
            pane.selectionMode -> pane.clearSelection()
            searchOpen -> { searchOpen = false; pane.clearSearch() }
            pane.canGoBack -> pane.goBack()
            else -> pane.up()
        }
    }

    // Deck / XMB controller: X opens the toolbar menu, Y the search.
    val deckActions = LocalDeckActions.current
    DisposableEffect(deckActions, compactToolbar) {
        val options: () -> Unit = { moreOpen = true }
        val search: () -> Unit = { searchOpen = true }
        deckActions.options.value = options
        deckActions.search.value = search
        // XMB: the shell's hint row then also shows "X Options" (Files only; the picker has no shell around it).
        if (compactToolbar) deckActions.xmbOptionsHint.value = true
        onDispose {
            if (compactToolbar) deckActions.xmbOptionsHint.value = false
            if (deckActions.options.value === options) deckActions.options.value = null
            if (deckActions.search.value === search) deckActions.search.value = null
        }
    }

    // ── Dialogs ──

    newFolderIn?.let { dir -> NameDialog("New folder", "", "Create", { newFolderIn = null }) { newFolderIn = null; createFolder(dir, it) } }
    newFileIn?.let { dir -> NameDialog("New file", "new.txt", "Create", { newFileIn = null }) { newFileIn = null; createFile(dir, it) } }
    renameTarget?.let { f -> NameDialog("Rename", f.name, "Rename", { renameTarget = null }) { renameTarget = null; rename(f, it) } }
    if (deleteTargets.isNotEmpty()) {
        val victims = deleteTargets
        DeleteDialog(victims.map { it.name }, { deleteTargets = emptyList() }) {
            deleteTargets = emptyList()
            panes.forEach { it.clearSelection() }
            delete(victims)
        }
    }
    if (compressTargets.isNotEmpty()) {
        val targets = compressTargets
        val dir = targets.first().parentFile
        val name = if (targets.size == 1) targets[0].nameWithoutExtension.ifBlank { targets[0].name } else (dir?.name ?: "Archive")
        CompressDialog(name, { compressTargets = emptyList() }) { n, fmt ->
            compressTargets = emptyList()
            panes.forEach { it.clearSelection() }
            if (dir != null) compress(targets, dir, n, fmt)
        }
    }
    propsTarget?.let { item ->
        when (item) {
            is FmItem.Disk -> DiskPropertiesDialog(item.file, describeLocation(item.file, containers, imagefsDir), { propsTarget = null }) { refreshAll() }
            is FmItem.Entry -> EntryPropertiesDialog(item) { propsTarget = null }
        }
    }
    sortFor?.let { p -> SortDialog(p.sort, { sortFor = null }) { s -> sortFor = null; panes.forEach { it.applySort(s) } } }
    conflict?.let { (name, isDir, batch) ->
        ConflictDialog(name, isDir, batch) { choice, all -> ops.answerConflict(choice, all) }
    }
    runChoice?.let { f -> ContainerChoiceDialog("Run in which container?", containers, { runChoice = null }) { c -> runChoice = null; runIn(f, c) } }
    shortcutChoice?.let { f -> ContainerChoiceDialog("Add to which container?", containers, { shortcutChoice = null }) { c -> shortcutChoice = null; addShortcutIn(f, c) } }
    panes.forEach { p ->
        val unlistable = p.unlistable
        LaunchedEffect(unlistable) {
            if (unlistable != null) {
                p.unlistable = null
                if (p.loc is FmLoc.InArchive) p.goBack() || p.up()
                context.startActivity(UnpackArchiveActivity.intent(context, unlistable.absolutePath))
            }
        }
        p.passwordFor?.let { arc ->
            PasswordDialog(arc.name, p.passwordWrong, { p.passwordFor = null; if (p.loc is FmLoc.InArchive && p.index == null) p.up() }) { pw ->
                ArchivePasswords.set(arc, pw)
                p.retryWithPassword()
            }
        }
    }
    extractReq?.let { (srcPane, entries) ->
        val archive = (srcPane.loc as? FmLoc.InArchive)?.archive
        val other = panes.getOrNull(if (panes.indexOf(srcPane) == 0) 1 else 0)?.currentDir
        val showOther = dualPref && other != null
        if (archive == null) extractReq = null else {
            ExtractDialog(archive.name, entries.size, if (showOther) other else null, { extractReq = null }) { target ->
                extractReq = null
                srcPane.clearSelection()
                val parent = archive.parentFile ?: return@ExtractDialog
                when (target) {
                    ExtractTarget.NEXT_TO_ARCHIVE -> extractEntries(srcPane, entries, parent)
                    ExtractTarget.NEW_FOLDER -> extractEntries(srcPane, entries, uniqueDestination(parent, archive.name.substringBeforeLast('.').ifBlank { archive.name }))
                    ExtractTarget.OTHER_PANE -> other?.let { extractEntries(srcPane, entries, it) }
                    ExtractTarget.CHOOSE -> chooseFolder("Extract to") { dest -> extractEntries(srcPane, entries, dest) }
                }
            }
        }
    }

    // ── Toolbar menus (the + menu and the ⋮ menu, shared by both toolbar layouts) ──

    fun newMenuEntries(dir: File): List<FmMenuEntry> = ops.newMenuEntries(dir)

    /** [note] is an inert first line (the compact toolbar puts the free space there when the row is too narrow for it). */
    fun moreMenuEntries(dir: File?, note: String?): List<FmMenuEntry> = buildList {
        if (note != null) add(FmMenuEntry(note, Icons.Filled.Info) { })
        add(FmMenuEntry(if (pane.showHidden) "Hide hidden files" else "Show hidden files", if (pane.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) {
            val v = !pane.showHidden
            panes.forEach { it.applyShowHidden(v) }
        })
        if (pane.loc != FmLoc.Home) add(FmMenuEntry("Select all", Icons.Filled.SelectAll) { pane.selectAll() })
        add(FmMenuEntry("Refresh", Icons.Filled.Refresh) { storageTick++; pane.refresh() })
        if (pick == null) add(FmMenuEntry(if (dualPref) "One pane" else "Two panes", Icons.Filled.ViewColumn) {
            dualPref = !dualPref
            FmPrefs.setDualPane(context, dualPref)
            if (!dualPref) active = 0
        })
        if (pick == null && dir != null) {
            val fav = FavoritesStore.isFavorite(context, dir.absolutePath)
            add(FmMenuEntry(if (fav) "Remove folder from Favorites" else "Add folder to Favorites", if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { toggleFavorite(dir) })
            add(FmMenuEntry("Folder properties", Icons.Filled.Info) { propsTarget = diskItem(dir) })
        }
        val l = pane.loc
        if (pick == null && l is FmLoc.InArchive) add(FmMenuEntry("Extract all…", Icons.Filled.Unarchive) { extractReq = pane to emptyList() })
        // The compact toolbar has no paste capsule: the controller reaches the clipboard's clear action from here too.
        if (compactToolbar && pick == null && clipboard.isNotEmpty()) add(FmMenuEntry("Clear clipboard", Icons.Filled.Close) { clipboard = emptyList() })
    }

    // ── Layout ──

    // The Box lets the compact (XMB) layout float the paste button over the page.
    CompositionLocalProvider(LocalFmDense provides dense) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().fmBackdrop()) {
            if (pick != null && !pickerTitle.isNullOrEmpty()) {
                Text(
                    pickerTitle,
                    color = cs.onSurface, fontSize = if (dense) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = if (dense) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = if (dense) 12.dp else 16.dp, end = if (dense) 12.dp else 16.dp, top = if (dense) 4.dp else 10.dp, bottom = if (dense) 0.dp else 2.dp),
                )
            }

            // Toolbar: acts on the active pane.
            if (compactToolbar) {
                // One slim row (40 dp buttons): back, up, the path, then search / view / sort / new / more. The clipboard is the
                // floating paste button at the bottom right (FmPasteFab), not a chip in this row.
                // Two panes keep their own path line over each pane, so the row then has no path of its own.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow = maxWidth < 560.dp
                    // The slim toolbar row: 40 dp buttons, or 32 dp in the dense (XMB picker) look.
                    val tb = if (dense) 32.dp else 40.dp
                    val tbIcon = if (dense) 19.dp else 22.dp
                    val dualBar = dualPref && pick == null && maxWidth >= 640.dp
                    val barDir = pane.currentDir
                    val barLoc = pane.loc
                    val barFree = remember(barDir?.absolutePath, pane.listingTick) {
                        if (barDir == null) 0L else runCatching { barDir.usableSpace }.getOrDefault(0L)
                    }
                    val sizeText: String? = when {
                        barDir != null && barFree > 0 -> "${StringUtils.formatBytes(barFree)} free"
                        barLoc is FmLoc.InArchive -> pane.index?.formatLabel
                        else -> null
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).height(tb).fmGlass(RoundedCornerShape(20.dp)).padding(horizontal = 2.dp),
                        ) {
                            IconButton(onClick = { pane.goBack() }, enabled = pane.canGoBack, modifier = Modifier.size(tb)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (pane.canGoBack) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(tbIcon))
                            }
                            IconButton(onClick = { pane.up() }, enabled = pane.loc != FmLoc.Home, modifier = Modifier.size(tb)) {
                                Icon(Icons.Filled.ArrowUpward, "Up", tint = if (pane.loc != FmLoc.Home) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(tbIcon))
                            }
                            if (dualBar) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                                    FmCrumbs(crumbsFor(pane, containers, imagefsDir, driveCPath), Modifier.weight(1f), vPad = if (dense) 5.dp else 9.dp)
                                    if (!narrow && sizeText != null) {
                                        Text(sizeText, color = cs.onSurfaceVariant, fontSize = if (dense) 10.sp else 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
                                    }
                                }
                            }
                            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) pane.clearSearch() }, enabled = pane.loc != FmLoc.Home, modifier = Modifier.size(tb)) {
                                Icon(Icons.Filled.Search, "Search", modifier = Modifier.size(tbIcon))
                            }
                            IconButton(onClick = {
                                val next = when (pane.viewMode) { FmViewMode.LIST -> FmViewMode.COMPACT; FmViewMode.COMPACT -> FmViewMode.GRID; FmViewMode.GRID -> FmViewMode.LIST }
                                panes.forEach { it.applyViewMode(next) }
                            }, modifier = Modifier.size(tb)) {
                                Icon(
                                    when (pane.viewMode) { FmViewMode.LIST -> Icons.Filled.ViewAgenda; FmViewMode.COMPACT -> Icons.Filled.ViewList; FmViewMode.GRID -> Icons.Filled.GridView },
                                    "View",
                                    modifier = Modifier.size(tbIcon),
                                )
                            }
                            IconButton(onClick = { sortFor = pane }, modifier = Modifier.size(tb)) { Icon(Icons.Filled.Sort, "Sort", modifier = Modifier.size(tbIcon)) }
                            if (pick == null && barDir != null) {
                                Box {
                                    IconButton(onClick = { newOpen = true }, modifier = Modifier.size(tb)) { Icon(Icons.Filled.Add, "New", tint = cs.primary, modifier = Modifier.size(tbIcon)) }
                                    if (newOpen) FmMenu(true, { newOpen = false }, newMenuEntries(barDir))
                                }
                            }
                            Box {
                                IconButton(onClick = { moreOpen = true }, modifier = Modifier.size(tb)) { Icon(Icons.Filled.MoreVert, "More", modifier = Modifier.size(tbIcon)) }
                                if (moreOpen) FmMenu(true, { moreOpen = false }, moreMenuEntries(barDir, if (narrow) sizeText else null))
                            }
                        }
                    }
                }
            } else Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).fmGlass(RoundedCornerShape(28.dp)).padding(horizontal = 4.dp),
            ) {
                IconButton(onClick = { pane.clearSelection(); pane.open(FmLoc.Home) }, enabled = pane.loc != FmLoc.Home) {
                    Icon(Icons.Filled.Home, "Home", tint = if (pane.loc != FmLoc.Home) cs.primary else cs.onSurfaceVariant)
                }
                IconButton(onClick = { pane.goBack() }, enabled = pane.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (pane.canGoBack) cs.primary else cs.onSurfaceVariant)
                }
                IconButton(onClick = { pane.up() }, enabled = pane.loc != FmLoc.Home) {
                    Icon(Icons.Filled.ArrowUpward, "Up", tint = if (pane.loc != FmLoc.Home) cs.primary else cs.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) pane.clearSearch() }, enabled = pane.loc != FmLoc.Home) {
                    Icon(Icons.Filled.Search, "Search")
                }
                IconButton(onClick = {
                    val next = when (pane.viewMode) { FmViewMode.LIST -> FmViewMode.COMPACT; FmViewMode.COMPACT -> FmViewMode.GRID; FmViewMode.GRID -> FmViewMode.LIST }
                    panes.forEach { it.applyViewMode(next) }
                }) {
                    Icon(
                        when (pane.viewMode) { FmViewMode.LIST -> Icons.Filled.ViewAgenda; FmViewMode.COMPACT -> Icons.Filled.ViewList; FmViewMode.GRID -> Icons.Filled.GridView },
                        "View",
                    )
                }
                IconButton(onClick = { sortFor = pane }) { Icon(Icons.Filled.Sort, "Sort") }
                val dir = pane.currentDir
                if (pick == null && dir != null) {
                    Box {
                        IconButton(onClick = { newOpen = true }) { Icon(Icons.Filled.Add, "New", tint = cs.primary) }
                        if (newOpen) FmMenu(true, { newOpen = false }, newMenuEntries(dir))
                    }
                }
                Box {
                    IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    if (moreOpen) FmMenu(true, { moreOpen = false }, moreMenuEntries(dir, null))
                }
            }

            if (searchOpen && pane.loc != FmLoc.Home) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
                    XmbTapToEdit(Modifier.weight(1f)) { fieldModifier, stopEditing ->
                        OutlinedTextField(
                            value = pane.query,
                            onValueChange = { pane.query = it },
                            singleLine = true,
                            placeholder = { Text("Search this folder", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            keyboardActions = xmbDoneActions(stopEditing),
                            modifier = fieldModifier,
                            shape = RoundedCornerShape(24.dp),
                        )
                    }
                    if (pane.currentDir != null) {
                        TextButton(onClick = { pane.searchSubfolders() }, enabled = pane.query.isNotBlank() && !pane.deepSearching) {
                            Text("In subfolders")
                        }
                    }
                    IconButton(onClick = { searchOpen = false; pane.clearSearch() }) { Icon(Icons.Filled.Close, "Close search") }
                }
            }

            // Paste capsule: what's on the clipboard and a one-tap paste into the active pane's folder.
            if (clipboard.isNotEmpty() && pick == null && !compactToolbar) {
                val dest = pane.currentDir
                val what = if (clipboard.size == 1) clipboard[0].name else "${clipboard.size} items"
                FmCapsule(
                    icon = Icons.Filled.ContentPaste,
                    text = (if (clipCut) "Move " else "Copy ") + what,
                    actionLabel = if (dest != null) "Paste here" else null,
                    onAction = if (dest != null) ({ paste(clipboard, dest, clipCut) }) else null,
                    onClose = { clipboard = emptyList() },
                )
            }
            opLabel?.let { label ->
                FmCapsule(
                    icon = Icons.Filled.DriveFileMove,
                    text = label + (opProgress?.let { "  ${(it * 100).toInt()}%" } ?: ""),
                    actionLabel = "Cancel",
                    onAction = { opJob?.cancel() },
                    progress = opProgress,
                    showProgress = true,
                )
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val dual = dualPref && pick == null && maxWidth >= 640.dp
                LaunchedEffect(dual) { if (!dual) active = 0 }
                Row(Modifier.fillMaxSize()) {
                    val shown = if (dual) panes else panes.take(1)
                    shown.forEachIndexed { i, p ->
                        if (i > 0) Spacer(Modifier.width(2.dp))
                        PaneView(
                            pane = p,
                            active = !dual || i == active,
                            dual = dual,
                            onActivate = { active = i },
                            home = { homeSections() },
                            storageTick = storageTick,
                            containers = containers,
                            imagefsDir = imagefsDir,
                            driveCPath = driveCPath,
                            pickActive = pick != null,
                            showPath = dual || !compactToolbar,
                            menuFor = { item -> menuFor(p, item) },
                            onOpen = { item -> active = i; openItem(p, item) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }

            // Selection bar.
            if (pane.selectionMode && pick == null) {
                val items = pane.selectedItems()
                val disk = items.filterIsInstance<FmItem.Disk>().map { it.file }
                val entries = items.filterIsInstance<FmItem.Entry>()
                val otherDir = if (dualPref) panes[if (active == 0) 1 else 0].currentDir else null
                val actions = if (pane.inArchive) listOf(
                    FmSelAction("Extract", Icons.Filled.Unarchive, entries.isNotEmpty()) { extractReq = pane to entries },
                    FmSelAction("All", Icons.Filled.SelectAll) { pane.selectAll() },
                ) else buildList {
                    add(FmSelAction("Copy", Icons.Filled.ContentCopy, disk.isNotEmpty()) { clipboard = disk; clipCut = false; pane.clearSelection() })
                    add(FmSelAction("Cut", Icons.Filled.ContentCut, disk.isNotEmpty()) { clipboard = disk; clipCut = true; pane.clearSelection() })
                    if (otherDir != null) {
                        add(FmSelAction("Copy over", Icons.Filled.ContentCopy, disk.isNotEmpty()) { pane.clearSelection(); paste(disk, otherDir, false) })
                        add(FmSelAction("Move over", Icons.Filled.DriveFileMove, disk.isNotEmpty()) { pane.clearSelection(); paste(disk, otherDir, true) })
                    } else {
                        add(FmSelAction("Copy to", Icons.Filled.Folder, disk.isNotEmpty()) { val d = disk; pane.clearSelection(); chooseFolder("Copy to") { paste(d, it, false) } })
                        add(FmSelAction("Move to", Icons.Filled.DriveFileMove, disk.isNotEmpty()) { val d = disk; pane.clearSelection(); chooseFolder("Move to") { paste(d, it, true) } })
                    }
                    add(FmSelAction("Compress", Icons.Filled.FolderZip, disk.isNotEmpty()) { compressTargets = disk })
                    add(FmSelAction("Share", Icons.Filled.Share, disk.isNotEmpty() && disk.none { it.isDirectory }) { share(disk) })
                    if (disk.size == 1) add(FmSelAction("Rename", Icons.Filled.Edit) { renameTarget = disk[0]; pane.clearSelection() })
                    add(FmSelAction("All", Icons.Filled.SelectAll) { pane.selectAll() })
                    add(FmSelAction("Delete", Icons.Filled.Delete, disk.isNotEmpty(), danger = true) { deleteTargets = disk })
                }
                FmSelectionBar(pane.selected.size, actions) { pane.clearSelection() }
            }

            // Folder picker: confirm the folder on screen.
            if (pick?.dirsOnly == true) {
                val dir = pane.currentDir
                Button(
                    onClick = { dir?.let { FmPrefs.setLastPickerDir(context, it); onPick?.invoke(it) } },
                    enabled = dir != null,
                    // Dense (the XMB picker): a slim bar, 36 dp tall instead of 40 + 16 of padding.
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (dense) 10.dp else 16.dp, vertical = if (dense) 3.dp else 8.dp)
                        .then(if (dense) Modifier.height(36.dp) else Modifier),
                    contentPadding = if (dense) PaddingValues(horizontal = 12.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(if (dense) 16.dp else 18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (dir != null) "Select \"${dir.name.ifBlank { dir.absolutePath }}\"" else "Open a folder to select it",
                        fontSize = if (dense) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // XMB (compact toolbar): the clipboard is a round paste button above the hint row, shown only while something is
        // copied or cut. It carries no name, only what it does (paste / move) for accessibility.
        if (compactToolbar && pick == null && clipboard.isNotEmpty()) {
            val dest = pane.currentDir
            FmPasteFab(
                cut = clipCut,
                onPaste = if (dest != null) ({ paste(clipboard, dest, clipCut) }) else null,
                onClear = { clipboard = emptyList() },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
    }
}

/** One pane: its path, then Home tiles or the listing. */
@Composable
private fun PaneView(
    pane: FmPane,
    active: Boolean,
    dual: Boolean,
    onActivate: () -> Unit,
    home: () -> List<FmHomeSection>,
    storageTick: Int,
    containers: List<Container>,
    imagefsDir: File,
    driveCPath: File?,
    pickActive: Boolean,
    menuFor: (FmItem) -> (() -> List<FmMenuEntry>)?,
    onOpen: (FmItem) -> Unit,
    modifier: Modifier = Modifier,
    showPath: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onActivate()
            }
        },
    ) {
        // Path line (the compact toolbar carries the path itself when there is a single pane).
        val loc = pane.loc
        if (showPath) Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .then(if (dual) Modifier.fmGlass(RoundedCornerShape(12.dp), focused = active) else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            FmCrumbs(crumbsFor(pane, containers, imagefsDir, driveCPath), Modifier.weight(1f))
            val dir = pane.currentDir
            if (dir != null) {
                val free = remember(dir.absolutePath, pane.listingTick) { runCatching { dir.usableSpace }.getOrDefault(0L) }
                if (free > 0) Text("${StringUtils.formatBytes(free)} free", color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
            } else if (loc is FmLoc.InArchive) {
                pane.index?.let { Text(it.formatLabel, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loc == FmLoc.Home) {
                FmHome(home(), onOpen = { dir -> pane.openDir(dir) }, refreshTick = storageTick)
            } else {
                val shown = pane.shown()
                val isArchiveCache = remember(shown) { HashMap<String, Boolean>() }
                fun isArc(item: FmItem): Boolean = item is FmItem.Disk && !item.isDir &&
                    isArchiveCache.getOrPut(item.key) { isBrowsableArchive(item.file) }
                val showParent = pane.query.isBlank() && pane.deepResults == null
                val longPress: (FmItem) -> (() -> Unit)? = { item -> if (pickActive) null else ({ pane.startSelect(item) }) }
                val rootDir = pane.currentDir
                if (pane.viewMode == FmViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (LocalFmDense.current) 84.dp else 104.dp),
                        state = pane.gridState,
                        contentPadding = PaddingValues(if (LocalFmDense.current) 3.dp else 6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (showParent) item(key = "..") { FmParentRow(compact = true) { pane.up() } }
                        items(shown, key = { it.key }) { item ->
                            FmTile(item, pane, isArc(item), menuFor(item), onOpen = { onOpen(item) }, onLongPress = longPress(item))
                        }
                    }
                } else {
                    val compact = pane.viewMode == FmViewMode.COMPACT
                    LazyColumn(state = pane.listState, contentPadding = PaddingValues(vertical = if (LocalFmDense.current) 2.dp else 4.dp), modifier = Modifier.fillMaxSize()) {
                        if (showParent) item(key = "..") { FmParentRow(compact) { pane.up() } }
                        items(shown, key = { it.key }) { item ->
                            // Subfolder search results say where they were found.
                            val sub = if (pane.deepResults != null && item is FmItem.Disk && rootDir != null)
                                item.file.parentFile?.absolutePath?.removePrefix(rootDir.absolutePath)?.trimStart('/')?.ifBlank { "." }
                            else null
                            FmRow(item, pane, compact, isArc(item), sub, menuFor(item), onOpen = { onOpen(item) }, onLongPress = longPress(item))
                        }
                    }
                }
                val err = pane.error
                when {
                    pane.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    shown.isEmpty() && err != null -> FmEmpty(err)
                    shown.isEmpty() && pane.deepSearching -> FmEmpty("Searching…")
                    shown.isEmpty() && (pane.query.isNotBlank() || pane.deepResults != null) -> FmEmpty("Nothing matches")
                    shown.isEmpty() -> FmEmpty("Empty folder")
                }
            }
        }
    }
}

/** Path segments for a pane: storage or container name first, then the folders, all clickable. */
internal fun crumbsFor(pane: FmPane, containers: List<Container>, imagefsDir: File, driveCPath: File?): List<Pair<String, () -> Unit>> {
    val out = ArrayList<Pair<String, () -> Unit>>()
    out.add("Home" to { pane.open(FmLoc.Home) })
    fun dirCrumbs(dir: File) {
        val roots = buildList {
            add(File("/storage/emulated/0") to "Internal")
            containers.forEach { add(File(it.rootDir, ".wine/drive_c") to "${it.name} C:") }
            driveCPath?.let { add(it to "Drive C:") }
            add(imagefsDir to "System")
            volumeRootOf(dir)?.let { r -> if (r.absolutePath != "/storage/emulated/0") add(r to "SD card") }
        }
        val abs = dir.absolutePath
        val root = roots.filter { (r, _) -> abs == r.absolutePath || abs.startsWith(r.absolutePath + "/") }.maxByOrNull { it.first.absolutePath.length }
        val base = root?.first ?: File("/")
        out.add((root?.second ?: "/") to { pane.openDir(base) })
        val rel = abs.removePrefix(base.absolutePath).trim('/')
        if (rel.isNotEmpty()) {
            var cur = base
            rel.split('/').forEach { seg ->
                cur = File(cur, seg)
                val target = cur
                out.add(seg to { pane.openDir(target) })
            }
        }
    }
    when (val l = pane.loc) {
        FmLoc.Home -> Unit
        is FmLoc.Dir -> dirCrumbs(l.dir)
        is FmLoc.InArchive -> {
            l.archive.parentFile?.let { dirCrumbs(it) }
            out.add(l.archive.name to { pane.open(FmLoc.InArchive(l.archive, "")) })
            if (l.inner.isNotEmpty()) {
                var cur = ""
                l.inner.split('/').forEach { seg ->
                    cur = if (cur.isEmpty()) seg else "$cur/$seg"
                    val target = cur
                    out.add(seg to { pane.open(FmLoc.InArchive(l.archive, target)) })
                }
            }
        }
    }
    return out
}
