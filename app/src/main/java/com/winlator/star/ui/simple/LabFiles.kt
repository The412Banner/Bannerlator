package com.winlator.star.ui.simple

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.MainActivity
import com.winlator.star.UnpackArchiveActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.StorageRoots
import com.winlator.star.core.StringUtils
import com.winlator.star.core.unpack.ArchivePasswords
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.files.ConflictChoice
import com.winlator.star.ui.files.ExtractTarget
import com.winlator.star.ui.files.FavStorage
import com.winlator.star.ui.files.FileAttrState
import com.winlator.star.ui.files.FmHomeTile
import com.winlator.star.ui.files.FmItem
import com.winlator.star.ui.files.FmItemIcon
import com.winlator.star.ui.files.FmLoc
import com.winlator.star.ui.files.FmOps
import com.winlator.star.ui.files.FmPane
import com.winlator.star.ui.files.FmPick
import com.winlator.star.ui.files.FmPrefs
import com.winlator.star.ui.files.FmSort
import com.winlator.star.ui.files.FmSortKey
import com.winlator.star.ui.files.FmViewMode
import com.winlator.star.ui.files.PackFormat
import com.winlator.star.ui.files.crumbsFor
import com.winlator.star.ui.files.describeLocation
import com.winlator.star.ui.files.detailFor
import com.winlator.star.ui.files.diskItem
import com.winlator.star.ui.files.fmDate
import com.winlator.star.ui.files.fmDateTime
import com.winlator.star.ui.files.isBrowsableArchive
import com.winlator.star.ui.files.itemCountLabel
import com.winlator.star.ui.files.label
import com.winlator.star.ui.files.readFileAttrs
import com.winlator.star.ui.files.setHidden
import com.winlator.star.ui.files.setReadOnly
import com.winlator.star.ui.files.treeSize
import com.winlator.star.ui.files.uniqueDestination
import com.winlator.star.ui.files.volumeUsage
import com.winlator.star.util.FavoritesStore
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.math.max

// Lab's file manager (Tools › File manager, the Log Manager's Browse) and Lab's file picker (every "from a file" in Lab):
// Classic's file manager (ui/files) drawn as Lab lists, on the same panes (FmPane) and operations (FmOps). Home lists
// storage, places, every container's drive C and the favourites; a folder lists its files ("..", then folders, then
// files) as rows, compact rows or a grid. A opens, X the item's options (or the selection's), Y the folder menu (new,
// paste, search, view, sort, hidden files, select all, refresh, two panes, favourites, properties, extract all),
// Select ticks a row, Start pastes (picker: chooses the folder), B goes back, then up, then leaves. Two panes:
// L2 / R2 (or Left / Right at the edge) switch the active one.

private sealed interface LRow { val key: String; val group: String? }
private class LTile(val tile: FmHomeTile, override val group: String) : LRow {
    override val key: String = "t:$group:${tile.dir.absolutePath}:${tile.title}"
}
private object LParent : LRow {
    override val key: String = ".."
    override val group: String? = null
}
private class LItem(val item: FmItem, val sub: String?) : LRow {
    override val key: String = item.key
    override val group: String? = null
}

/** Lab's file manager page; [initialDir] opens there (the Log Manager's Browse), else Home. */
@Composable
internal fun LabFileManagerPage(onBack: () -> Unit, initialDir: File? = null) {
    LabFileManager(onBack = onBack, initialDir = initialDir)
}

/**
 * Lab's file picker, for [com.winlator.star.FilePickerActivity] in Lab: the Lab file manager in pick mode over Lab's
 * wallpaper, with its own button hints (no Lab shell around it). Same choices as Classic's picker.
 */
@Composable
internal fun LabFilePicker(
    pickDirMode: Boolean,
    pickExtensions: List<String>,
    initialDir: File?,
    pickerTitle: String?,
    driveCPath: File?,
    onPick: (File) -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LabWallpaper()
        LabFileManager(
            onBack = onCancel,
            initialDir = initialDir,
            pickMode = true,
            pickDirMode = pickDirMode,
            pickExtensions = pickExtensions,
            pickerTitle = pickerTitle,
            driveCPath = driveCPath,
            onPick = onPick,
            standalone = true,
        )
    }
}

@Composable
internal fun LabFileManager(
    onBack: () -> Unit,
    initialDir: File? = null,
    pickMode: Boolean = false,
    pickDirMode: Boolean = false,
    pickExtensions: List<String> = emptyList(),
    pickerTitle: String? = null,
    driveCPath: File? = null,
    onPick: ((File) -> Unit)? = null,
    /** No Lab shell around it (the picker activity): the page draws its own button hints. */
    standalone: Boolean = false,
) {
    SimpleFlat { LabFileManagerBody(onBack, initialDir, pickMode, pickDirMode, pickExtensions, pickerTitle, driveCPath, onPick, standalone) }
}

@Composable
private fun LabFileManagerBody(
    onBack: () -> Unit,
    initialDir: File?,
    pickMode: Boolean,
    pickDirMode: Boolean,
    pickExtensions: List<String>,
    pickerTitle: String?,
    driveCPath: File?,
    onPick: ((File) -> Unit)?,
    standalone: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val mainActivity = context as? MainActivity
    val imagefsDir = remember { File(context.filesDir, "imagefs") }
    val pick: FmPick? = remember(pickMode, pickDirMode, pickExtensions) {
        if (pickMode || pickDirMode) FmPick(pickDirMode, pickExtensions.map { it.lowercase() }) else null
    }

    // As Classic: containers from the main screen's manager, or read once here (the picker has its own activity).
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

    val panes = remember { listOf(FmPane(context, scope, pick) { floors() }, FmPane(context, scope, pick) { floors() }) }
    var active by remember { mutableIntStateOf(0) }
    var dualPref by remember { mutableStateOf(FmPrefs.dualPane(context)) }
    val ops = remember { FmOps(context, scope, { containers }, { panes.forEach { if (it.loc != FmLoc.Home) it.refresh() } }, { storageTick++ }) }

    LaunchedEffect(Unit) {
        val start = initialDir?.takeIf { it.isDirectory } ?: if (pick != null) FmPrefs.lastPickerDir(context) else null
        if (start != null) panes[0].openDir(start)
    }

    // As Classic: lists refresh in place when you come back to the app (a game or another app may have changed them).
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

    // ── Lab's own sheets ──
    var pageMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    var searchFor by remember { mutableStateOf<FmPane?>(null) }
    var itemMenu by remember { mutableStateOf<Pair<FmPane, FmItem>?>(null) }
    var tileMenu by remember { mutableStateOf<FmHomeTile?>(null) }

    // Where "Choose a folder…" sends things once the folder picker answers (as Classic).
    var pendingChoose by remember { mutableStateOf<((File) -> Unit)?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pendingChoose
        pendingChoose = null
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data)?.let { p -> cb?.invoke(File(p)) }
    }
    fun chooseFolder(title: String, then: (File) -> Unit) {
        pendingChoose = then
        folderPicker.launch(InAppFilePicker.buildDirIntent(context, title = title, initialDir = panes[active].currentDir?.absolutePath))
    }

    // Where each pane's controller row is, per place (going back lands on the row you left).
    val rowAt = remember { List(2) { mutableStateMapOf<FmLoc, Int>() } }
    var col by remember { mutableIntStateOf(0) }

    fun rowsOf(p: FmPane): List<LRow> = if (p.loc == FmLoc.Home) {
        ops.homeSections(drives, imagefsDir, pick, driveCPath).filter { it.tiles.isNotEmpty() }.flatMap { s -> s.tiles.map { LTile(it, s.title) } }
    } else buildList {
        val root = p.currentDir
        if (p.query.isBlank() && p.deepResults == null) add(LParent)
        p.shown().forEach { item ->
            // Subfolder search results say where they were found.
            val sub = if (p.deepResults != null && item is FmItem.Disk && root != null)
                item.file.parentFile?.absolutePath?.removePrefix(root.absolutePath)?.trimStart('/')?.ifBlank { "." }
            else null
            add(LItem(item, sub))
        }
    }

    val dialogOpen = pageMenu || viewMenu || sortOpen || selMenu || searchFor != null || itemMenu != null || tileMenu != null ||
        ops.newFolderIn != null || ops.newFileIn != null || ops.renameTarget != null || ops.deleteTargets.isNotEmpty() ||
        ops.propsTarget != null || ops.compressTargets.isNotEmpty() || ops.extractReq != null || ops.runChoice != null ||
        ops.shortcutChoice != null || ops.conflict != null || panes.any { it.passwordFor != null }

    val deckActions = LocalDeckActions.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(dialogOpen) {
        if (dialogOpen) return@LaunchedEffect
        delay(60)
        runCatching { focus.requestFocus() }
    }

    /** B / Back as Classic's back: the selection, then the search, then the last place, then up, then leave. */
    fun back() {
        val p = panes[active]
        when {
            p.selectionMode -> p.clearSelection()
            p.query.isNotBlank() || p.deepResults != null || p.deepSearching -> p.clearSearch()
            p.canGoBack -> p.goBack()
            p.loc != FmLoc.Home -> p.up()
            else -> onBack()
        }
    }
    BackHandler { back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 700.dp
        val dual = dualPref && pick == null && maxWidth >= 640.dp
        LaunchedEffect(dual) { if (!dual) active = 0 }
        val hPad = if (narrow || standalone) 16.dp else 40.dp
        // Grid columns: one cell about 112 dp wide in each pane.
        val paneWidth = (maxWidth - hPad * 2) / (if (dual) 2 else 1)
        val cols = max(2, (paneWidth / 112.dp).toInt())
        val rows = listOf(rowsOf(panes[0]), if (dual) rowsOf(panes[1]) else emptyList())
        fun rowOf(i: Int): Int = (rowAt[i][panes[i].loc] ?: 0).coerceIn(0, max(0, rows[i].size - 1))
        fun setRow(i: Int, r: Int) { rowAt[i][panes[i].loc] = r }
        val pane = panes[active]
        val rowNow = rowOf(active)
        val cur = rows[active].getOrNull(rowNow)
        fun gridOf(p: FmPane) = p.loc != FmLoc.Home && p.viewMode == FmViewMode.GRID
        fun hasMenu(r: LRow?): Boolean = when (r) {
            is LItem -> pick == null && !panes[active].selectionMode
            is LTile -> r.tile.menu != null
            else -> false
        }
        LaunchedEffect(rowNow, active, pane.loc) { col = 0 }
        com.winlator.star.sound.MoveSound(listOf(active, pane.loc, rowNow, col))

        fun activate(p: FmPane, r: LRow?) {
            when (r) {
                is LTile -> p.openDir(r.tile.dir)
                LParent -> p.up()
                is LItem -> if (p.selectionMode) p.toggleSelect(r.item) else ops.openItem(p, r.item, pick, onPick)
                null -> Unit
            }
        }
        fun openMenu(p: FmPane, r: LRow?) {
            when (r) {
                is LItem -> if (pick == null) { if (p.selectionMode) selMenu = true else itemMenu = p to r.item }
                is LTile -> if (r.tile.menu != null) tileMenu = r.tile
                else -> Unit
            }
        }
        fun pasteHere(): Boolean {
            val dir = panes[active].currentDir ?: return false
            if (pick != null || ops.clipboard.isEmpty()) return false
            ops.paste(ops.clipboard, dir, ops.clipCut)
            return true
        }
        fun chooseThisFolder() {
            val dir = panes[active].currentDir ?: return
            FmPrefs.setLastPickerDir(context, dir)
            onPick?.invoke(dir)
        }
        fun edge() = com.winlator.star.sound.UiSounds.cue(com.winlator.star.sound.UiCue.NAVIGATE_EDGE)
        fun step(d: Int) {
            val n = rows[active].size
            val to = (rowNow + d).coerceIn(0, max(0, n - 1))
            if (to == rowNow) edge() else setRow(active, to)
        }

        // L2 / R2 through the shell as well (they then work wherever the controller focus is).
        DisposableEffect(deckActions, dual) {
            if (!dual) return@DisposableEffect onDispose {}
            val switch: (Int) -> Unit = { active = 1 - active }
            deckActions.subTab.value = switch
            deckActions.subTabLabel.value = L("Pane")
            onDispose { if (deckActions.subTab.value === switch) deckActions.subTab.value = null }
        }
        val legend = buildList {
            add("B" to L("Back"))
            add("A" to (if (pane.selectionMode) L("Tick") else L("Open")))
            if (pick == null && (cur is LItem || (cur is LTile && cur.tile.menu != null))) add("X" to (if (pane.selectionMode) L("Selection") else L("Options")))
            add("Y" to L("Menu"))
            if (pick == null && pane.loc != FmLoc.Home) add("Select" to L("Tick"))
            if (pick?.dirsOnly == true && pane.currentDir != null) add("Start" to L("Choose this folder"))
            else if (pick == null && ops.clipboard.isNotEmpty() && pane.currentDir != null) add("Start" to (if (ops.clipCut) L("Move here") else L("Paste here")))
            if (dual) add("L2" to L("Pane"))
        }
        if (!standalone) DisposableEffect(deckActions, legend) {
            deckActions.ownLegend.value = legend
            onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
        }

        val onKey: (SIn) -> Boolean = onKey@{ a ->
            val grid = gridOf(pane)
            when (a) {
                SIn.B -> back()
                SIn.UP -> step(if (grid) -cols else -1)
                SIn.DOWN -> step(if (grid) cols else 1)
                SIn.LEFT -> when {
                    grid && rowNow % cols > 0 -> setRow(active, rowNow - 1)
                    !grid && col > 0 -> { col = 0 }
                    dual && active == 1 -> { active = 0 }
                    else -> return@onKey false   // the Lab rail on the left takes it
                }
                SIn.RIGHT -> when {
                    grid && rowNow % cols < cols - 1 && rowNow < rows[active].size - 1 -> setRow(active, rowNow + 1)
                    !grid && col == 0 && hasMenu(cur) -> { col = 1 }
                    dual && active == 0 -> { active = 1 }
                    else -> edge()
                }
                SIn.A -> if (col == 1) openMenu(pane, cur) else activate(pane, cur)
                SIn.X -> { if (pick == null && pane.selectionMode) selMenu = true else openMenu(pane, cur) }
                SIn.Y -> { pageMenu = true }
                SIn.SELECT -> (cur as? LItem)?.let { if (pick == null) { if (pane.selectionMode) pane.toggleSelect(it.item) else pane.startSelect(it.item) } }
                SIn.START -> { if (pick?.dirsOnly == true) chooseThisFolder() else if (!pasteHere()) edge() }
                SIn.LT, SIn.RT -> { if (dual) active = 1 - active else return@onKey false }
                else -> return@onKey false
            }
            true
        }

        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (dialogOpen) return@onPreviewKeyEvent false
                    val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                    if (a == SIn.LB || a == SIn.RB) return@onPreviewKeyEvent false
                    onKey(a)
                }
                .padding(start = hPad, end = hPad, top = if (standalone) 12.dp else 6.dp),
        ) {
            // ── Title ──
            val dir = pane.currentDir
            val free = remember(dir?.absolutePath, pane.listingTick) { if (dir == null) 0L else runCatching { dir.usableSpace }.getOrDefault(0L) }
            val sizeText: String? = when {
                dir != null && free > 0 -> L("%1\$s free", StringUtils.formatBytes(free))
                pane.loc is FmLoc.InArchive -> pane.index?.formatLabel
                else -> null
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    val overline = when {
                        pick == null -> L("Tools")
                        pick.dirsOnly -> L("Choose a folder")
                        else -> L("Choose a file")
                    }
                    Text(overline.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
                    Text(
                        if (pick == null) L("File manager") else pickerTitle?.takeIf { it.isNotBlank() } ?: overline,
                        color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = if (pick == null) 26.sp else 22.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!dual && sizeText != null) Text(sizeText, color = SimpleMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            Spacer(Modifier.height(8.dp))

            // ── Toolbar (touch; the controller has all of it in Y's menu) ──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                LabToolButton(Icons.Filled.Home, L("Home"), pane.loc != FmLoc.Home, small = narrow) { pane.clearSelection(); pane.open(FmLoc.Home) }
                LabToolButton(Icons.AutoMirrored.Filled.ArrowBack, L("Back"), pane.canGoBack, small = narrow) { pane.goBack() }
                LabToolButton(Icons.Filled.ArrowUpward, L("Up"), pane.loc != FmLoc.Home, small = narrow) { pane.up() }
                if (!dual && !narrow) {
                    LabCrumbs(crumbsFor(pane, containers, imagefsDir, driveCPath), Modifier.weight(1f).padding(horizontal = 6.dp))
                } else Spacer(Modifier.weight(1f))
                LabToolButton(Icons.Filled.Search, L("Search"), pane.loc != FmLoc.Home, small = narrow) { searchFor = pane }
                LabToolButton(
                    when (pane.viewMode) { FmViewMode.LIST -> Icons.Filled.ViewAgenda; FmViewMode.COMPACT -> Icons.Filled.ViewList; FmViewMode.GRID -> Icons.Filled.GridView },
                    L("View"), true, small = narrow,
                ) { viewMenu = true }
                LabToolButton(Icons.Filled.Sort, L("Sort"), true, small = narrow) { sortOpen = true }
                if (pick == null && dir != null) LabToolButton(Icons.Filled.Add, L("New"), true, small = narrow) { pageMenu = true }
                LabToolButton(Icons.Filled.MoreHoriz, L("Menu"), true, small = narrow) { pageMenu = true }
            }
            if (!dual && narrow) LabCrumbs(crumbsFor(pane, containers, imagefsDir, driveCPath), Modifier.fillMaxWidth().padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))

            // ── The pane(s) ──
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val shown = if (dual) panes else panes.take(1)
                shown.forEachIndexed { i, p ->
                    if (i > 0) Spacer(Modifier.width(10.dp))
                    LabFmPanel(
                        pane = p,
                        rows = rows[i],
                        row = rowOf(i),
                        col = if (i == active) col else 0,
                        active = i == active,
                        dual = dual,
                        cols = cols,
                        crumbs = if (dual) crumbsFor(p, containers, imagefsDir, driveCPath) else null,
                        pick = pick,
                        storageTick = storageTick,
                        onActivate = { active = i },
                        onTap = { r ->
                            active = i
                            if (r == rowOf(i)) activate(p, rows[i].getOrNull(r)) else setRow(i, r)
                        },
                        onLong = { r ->
                            active = i
                            setRow(i, r)
                            val row = rows[i].getOrNull(r)
                            // As Classic: a long press starts the selection on a row; on a grid tile it opens the tile's menu.
                            if (row is LItem && pick == null) {
                                if (p.selectionMode) p.toggleSelect(row.item)
                                else if (gridOf(p)) itemMenu = p to row.item else p.startSelect(row.item)
                            } else openMenu(p, row)
                        },
                        onMenu = { r -> active = i; setRow(i, r); openMenu(p, rows[i].getOrNull(r)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            // ── What is going on: search, clipboard, the running operation, the selection, the folder pick ──
            val q = pane.query
            if (q.isNotBlank() || pane.deepResults != null) {
                val n = pane.shown().size
                LabStatusLine(
                    text = (if (pane.deepSearching) L("Searching subfolders for \"%1\$s\"…", q) else L("Search \"%1\$s\" · %2\$s found", q, n)),
                    actions = buildList {
                        if (pane.currentDir != null && pane.deepResults == null && q.isNotBlank()) add(L("In subfolders") to { pane.searchSubfolders() })
                        add(L("Change") to { searchFor = pane })
                        add(L("Clear") to { pane.clearSearch() })
                    },
                )
            }
            if (ops.clipboard.isNotEmpty() && pick == null) {
                val what = if (ops.clipboard.size == 1) ops.clipboard[0].name else L("%1\$s items", ops.clipboard.size)
                LabStatusLine(
                    text = (if (ops.clipCut) L("Move: %1\$s", what) else L("Copy: %1\$s", what)),
                    icon = Icons.Filled.ContentPaste,
                    actions = buildList {
                        if (pane.currentDir != null) add(L("Paste here") to { pasteHere(); Unit })
                        add(L("Clear") to { ops.clipboard = emptyList() })
                    },
                )
            }
            ops.opLabel?.let { label ->
                LabStatusLine(
                    text = label + (ops.opProgress?.let { "  ${(it * 100).toInt()}%" } ?: ""),
                    progress = ops.opProgress ?: -1f,
                    actions = listOf(L("Cancel") to { ops.opJob?.cancel(); Unit }),
                )
            }
            if (pane.selectionMode && pick == null) {
                LabStatusLine(
                    text = L("%1\$s selected", pane.selected.size),
                    icon = Icons.Filled.Check,
                    actions = selectionActions(pane, dual, panes, ops, ::chooseFolder).map { it.label to it.run } + (L("Done") to { pane.clearSelection() }),
                )
            }
            if (pick?.dirsOnly == true) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    LabButton(
                        if (dir != null) L("Choose \"%1\$s\"", dir.name.ifBlank { dir.absolutePath }) else L("Open a folder to choose it"),
                        primary = dir != null,
                        modifier = Modifier.alpha(if (dir != null) 1f else 0.5f),
                    ) { chooseThisFolder() }
                }
            }
            if (standalone) {
                Spacer(Modifier.height(8.dp))
                SimpleLegend(legend, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(if (standalone) 10.dp else 6.dp))
        }

        // ── The folder menu (Y) ──
        if (pageMenu) {
            val p = pane
            val d = p.currentDir
            val l = p.loc
            val actions = buildList {
                if (pick == null && d != null) {
                    add(MenuAction(L("New folder"), { ops.newFolderIn = d }))
                    add(MenuAction(L("New text file"), { ops.newFileIn = d }))
                }
                if (pick == null && d != null && ops.clipboard.isNotEmpty()) add(MenuAction(if (ops.clipCut) L("Move here") else L("Paste here"), { pasteHere() }))
                if (pick == null && ops.clipboard.isNotEmpty()) add(MenuAction(L("Clear clipboard"), { ops.clipboard = emptyList() }))
                if (pick?.dirsOnly == true && d != null) add(MenuAction(L("Choose this folder"), { chooseThisFolder() }))
                if (l != FmLoc.Home) add(MenuAction(L("Search this folder"), { searchFor = p }))
                if (p.query.isNotBlank() && d != null && p.deepResults == null) add(MenuAction(L("Search in subfolders"), { p.searchSubfolders() }))
                if (p.query.isNotBlank() || p.deepResults != null) add(MenuAction(L("Clear the search"), { p.clearSearch() }))
                add(MenuAction(L("View: %1\$s", viewLabel(p.viewMode)), { viewMenu = true }))
                add(MenuAction(L("Sort"), { sortOpen = true }))
                add(MenuAction(if (p.showHidden) L("Hide hidden files") else L("Show hidden files"), {
                    val v = !p.showHidden
                    panes.forEach { it.applyShowHidden(v) }
                }))
                if (l != FmLoc.Home && pick == null) add(MenuAction(L("Select all"), { p.selectAll() }))
                add(MenuAction(L("Refresh"), { storageTick++; p.refresh() }))
                if (pick == null) add(MenuAction(if (dualPref) L("One pane") else L("Two panes"), {
                    dualPref = !dualPref
                    FmPrefs.setDualPane(context, dualPref)
                    if (!dualPref) active = 0
                }))
                if (pick == null && d != null) {
                    val fav = FavoritesStore.isFavorite(context, d.absolutePath)
                    add(MenuAction(if (fav) L("Remove folder from Favorites") else L("Add folder to Favorites"), { ops.toggleFavorite(d) }))
                    add(MenuAction(L("Folder properties"), { ops.propsTarget = diskItem(d) }))
                }
                if (pick == null && l is FmLoc.InArchive) add(MenuAction(L("Extract all…"), { ops.extractReq = p to emptyList() }))
                if (l != FmLoc.Home) add(MenuAction(L("Home"), { p.clearSelection(); p.open(FmLoc.Home) }))
                if (p.canGoBack) add(MenuAction(L("Back"), { p.goBack() }))
                if (l != FmLoc.Home) add(MenuAction(L("Up one level"), { p.up() }))
            }
            SimpleActionSheet(d?.let { it.name.ifBlank { it.absolutePath } } ?: if (l is FmLoc.InArchive) l.archive.name else L("File manager"), actions, onDismiss = { pageMenu = false }) { a -> pageMenu = false; a.run() }
        }
        if (viewMenu) {
            SimpleActionSheet(
                L("View"),
                FmViewMode.entries.map { m -> MenuAction(if (m == pane.viewMode) viewLabel(m) + "  ✓" else viewLabel(m), { panes.forEach { it.applyViewMode(m) } }) },
                onDismiss = { viewMenu = false },
            ) { a -> viewMenu = false; a.run() }
        }
        if (sortOpen) LabSortDialog(pane.sort, onDismiss = { sortOpen = false }) { s -> sortOpen = false; panes.forEach { it.applySort(s) } }
        if (selMenu) {
            SimpleActionSheet(
                L("%1\$s selected", pane.selected.size),
                selectionActions(pane, dual, panes, ops, ::chooseFolder) + MenuAction(L("Clear the selection"), { pane.clearSelection() }),
                onDismiss = { selMenu = false },
            ) { a -> selMenu = false; a.run() }
        }
        searchFor?.let { p ->
            LabTextPrompt(
                title = L("Search this folder"),
                initial = p.query,
                confirm = L("Search"),
                placeholder = L("Name contains…"),
                valid = { true },
                onChange = { p.query = it },
                onDismiss = { searchFor = null },
                extra = {
                    if (p.currentDir != null) LabDialogRow(L("Search in subfolders too"), onClick = { searchFor = null; if (p.query.isNotBlank()) p.searchSubfolders() })
                },
            ) { v -> searchFor = null; p.query = v }
        }
        itemMenu?.let { (p, item) ->
            val entries = when (item) {
                is FmItem.Disk -> ops.diskMenu(p, item)
                is FmItem.Entry -> ops.entryMenu(p, item)
            }
            SimpleActionSheet(item.name, entries.map { MenuAction(L(it.label), it.onClick, danger = it.danger) }, onDismiss = { itemMenu = null }) { a -> itemMenu = null; a.run() }
        }
        tileMenu?.let { t ->
            SimpleActionSheet(t.title, t.menu?.invoke().orEmpty().map { MenuAction(L(it.label), it.onClick, danger = it.danger) }, onDismiss = { tileMenu = null }) { a -> tileMenu = null; a.run() }
        }
    }

    // ── The operations' dialogs (FmOps), Lab-drawn ──
    ops.newFolderIn?.let { d ->
        LabTextPrompt(L("New folder"), "", L("Create"), onDismiss = { ops.newFolderIn = null }, valid = ::labValidName) { ops.newFolderIn = null; ops.createFolder(d, it) }
    }
    ops.newFileIn?.let { d ->
        LabTextPrompt(L("New file"), "new.txt", L("Create"), onDismiss = { ops.newFileIn = null }, valid = ::labValidName) { ops.newFileIn = null; ops.createFile(d, it) }
    }
    ops.renameTarget?.let { f ->
        LabTextPrompt(L("Rename"), f.name, L("Rename"), onDismiss = { ops.renameTarget = null }, valid = ::labValidName) { ops.renameTarget = null; ops.rename(f, it) }
    }
    if (ops.deleteTargets.isNotEmpty()) {
        val victims = ops.deleteTargets
        val names = victims.map { it.name }
        val body = buildString {
            append(L("This can't be undone."))
            if (names.size > 1) {
                names.take(5).forEach { append("\n• ").append(it) }
                if (names.size > 5) append("\n").append(L("…and %1\$s more", names.size - 5))
            }
        }
        SimpleActionSheet(
            if (names.size == 1) L("Delete \"%1\$s\"?", names[0]) else L("Delete %1\$s items?", names.size),
            listOf(
                MenuAction(L("Delete"), {
                    ops.deleteTargets = emptyList()
                    panes.forEach { it.clearSelection() }
                    ops.delete(victims)
                }, danger = true),
                MenuAction(L("Cancel"), { ops.deleteTargets = emptyList() }),
            ),
            onDismiss = { ops.deleteTargets = emptyList() },
            message = body,
        ) { it.run() }
    }
    if (ops.compressTargets.isNotEmpty()) {
        val targets = ops.compressTargets
        val dir = targets.first().parentFile
        val name = if (targets.size == 1) targets[0].nameWithoutExtension.ifBlank { targets[0].name } else (dir?.name ?: "Archive")
        LabCompressDialog(name, onDismiss = { ops.compressTargets = emptyList() }) { n, fmt ->
            ops.compressTargets = emptyList()
            panes.forEach { it.clearSelection() }
            if (dir != null) ops.compress(targets, dir, n, fmt)
        }
    }
    ops.propsTarget?.let { item ->
        when (item) {
            is FmItem.Disk -> LabDiskProperties(item.file, containers, File(context.filesDir, "imagefs"), onDismiss = { ops.propsTarget = null }) {
                panes.forEach { if (it.loc != FmLoc.Home) it.refresh() }
            }
            is FmItem.Entry -> LabEntryProperties(item) { ops.propsTarget = null }
        }
    }
    ops.conflict?.let { (name, isDir, batch) ->
        LabConflictDialog(name, isDir, batch) { choice, all -> ops.answerConflict(choice, all) }
    }
    ops.runChoice?.let { f ->
        SimpleActionSheet(L("Run in which container?"), containers.map { c -> MenuAction(c.name, { ops.runIn(f, c) }) }, onDismiss = { ops.runChoice = null }) { a ->
            ops.runChoice = null; a.run()
        }
    }
    ops.shortcutChoice?.let { f ->
        SimpleActionSheet(L("Add to which container?"), containers.map { c -> MenuAction(c.name, { ops.addShortcutIn(f, c) }) }, onDismiss = { ops.shortcutChoice = null }) { a ->
            ops.shortcutChoice = null; a.run()
        }
    }
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
            LabTextPrompt(
                title = arc.name, initial = "", confirm = L("Open"), password = true,
                error = if (p.passwordWrong) L("Wrong password") else null,
                placeholder = L("Password"),
                valid = { it.isNotEmpty() },
                onDismiss = { p.passwordFor = null; if (p.loc is FmLoc.InArchive && p.index == null) p.up() },
            ) { pw ->
                ArchivePasswords.set(arc, pw)
                p.retryWithPassword()
            }
        }
    }
    ops.extractReq?.let { (srcPane, entries) ->
        val archive = (srcPane.loc as? FmLoc.InArchive)?.archive
        val dualNow = dualPref && pick == null
        val other = panes.getOrNull(if (panes.indexOf(srcPane) == 0) 1 else 0)?.currentDir
        val showOther = dualNow && other != null
        if (archive == null) ops.extractReq = null else {
            val parent = archive.parentFile
            val folderName = archive.name.substringBeforeLast('.').ifBlank { archive.name }
            fun go(t: ExtractTarget) {
                ops.extractReq = null
                srcPane.clearSelection()
                val par = parent ?: return
                when (t) {
                    ExtractTarget.NEXT_TO_ARCHIVE -> ops.extractEntries(srcPane, entries, par)
                    ExtractTarget.NEW_FOLDER -> ops.extractEntries(srcPane, entries, uniqueDestination(par, folderName))
                    ExtractTarget.OTHER_PANE -> other?.let { ops.extractEntries(srcPane, entries, it) }
                    ExtractTarget.CHOOSE -> chooseFolder(L("Extract to")) { dest -> ops.extractEntries(srcPane, entries, dest) }
                }
            }
            SimpleActionSheet(
                if (entries.isEmpty()) L("Extract all") else if (entries.size == 1) L("Extract 1 item") else L("Extract %1\$s items", entries.size),
                buildList {
                    add(MenuAction(L("Here, next to %1\$s", archive.name), { go(ExtractTarget.NEXT_TO_ARCHIVE) }))
                    add(MenuAction(L("Into a new folder \"%1\$s\"", folderName), { go(ExtractTarget.NEW_FOLDER) }))
                    if (showOther && other != null) add(MenuAction(L("To the other pane (%1\$s)", other.name.ifBlank { other.absolutePath }), { go(ExtractTarget.OTHER_PANE) }))
                    add(MenuAction(L("Choose a folder…"), { go(ExtractTarget.CHOOSE) }))
                },
                onDismiss = { ops.extractReq = null },
            ) { it.run() }
        }
    }
}

/** Home's fixed tile names and lines in Lab's language; a container's or a folder's own name stays as it is. */
private val FIXED_TILE_TEXT = setOf("Internal", "System", "Drive Z:", "Downloads", "Pictures", "Documents", "Games", "Drive C:", "This container")
private fun labFixed(text: String): String = if (text in FIXED_TILE_TEXT) L(text) else text

private fun viewLabel(m: FmViewMode): String = when (m) {
    FmViewMode.LIST -> L("List")
    FmViewMode.COMPACT -> L("Compact list")
    FmViewMode.GRID -> L("Grid")
}

/** What the selection can do, as Classic's selection bar (disabled ones left out). */
private fun selectionActions(pane: FmPane, dual: Boolean, panes: List<FmPane>, ops: FmOps, chooseFolder: (String, (File) -> Unit) -> Unit): List<MenuAction> {
    val items = pane.selectedItems()
    val disk = items.filterIsInstance<FmItem.Disk>().map { it.file }
    val entries = items.filterIsInstance<FmItem.Entry>()
    if (pane.inArchive) return buildList {
        if (entries.isNotEmpty()) add(MenuAction(L("Extract"), { ops.extractReq = pane to entries }))
        add(MenuAction(L("Select all"), { pane.selectAll() }))
    }
    if (disk.isEmpty()) return listOf(MenuAction(L("Select all"), { pane.selectAll() }))
    val otherDir = if (dual) panes[if (panes.indexOf(pane) == 0) 1 else 0].currentDir else null
    return buildList {
        add(MenuAction(L("Copy"), { ops.clipboard = disk; ops.clipCut = false; pane.clearSelection() }))
        add(MenuAction(L("Cut"), { ops.clipboard = disk; ops.clipCut = true; pane.clearSelection() }))
        if (otherDir != null) {
            add(MenuAction(L("Copy to the other pane"), { pane.clearSelection(); ops.paste(disk, otherDir, false) }))
            add(MenuAction(L("Move to the other pane"), { pane.clearSelection(); ops.paste(disk, otherDir, true) }))
        } else {
            add(MenuAction(L("Copy to…"), { pane.clearSelection(); chooseFolder(L("Copy to")) { ops.paste(disk, it, false) } }))
            add(MenuAction(L("Move to…"), { pane.clearSelection(); chooseFolder(L("Move to")) { ops.paste(disk, it, true) } }))
        }
        add(MenuAction(L("Compress"), { ops.compressTargets = disk }))
        if (disk.none { it.isDirectory }) add(MenuAction(L("Share"), { ops.share(disk) }))
        if (disk.size == 1) add(MenuAction(L("Rename"), { ops.renameTarget = disk[0]; pane.clearSelection() }))
        add(MenuAction(L("Select all"), { pane.selectAll() }))
        add(MenuAction(L("Delete"), { ops.deleteTargets = disk }, danger = true))
    }
}

// ── Pane ──

@Composable
private fun LabFmPanel(
    pane: FmPane,
    rows: List<LRow>,
    row: Int,
    col: Int,
    active: Boolean,
    dual: Boolean,
    cols: Int,
    crumbs: List<Pair<String, () -> Unit>>?,
    pick: FmPick?,
    storageTick: Int,
    onActivate: () -> Unit,
    onTap: (Int) -> Unit,
    onLong: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = pane.loc != FmLoc.Home && pane.viewMode == FmViewMode.GRID
    LaunchedEffect(row, pane.loc, grid) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (grid) pane.gridState.keepVisibleCell(row) else pane.listState.keepVisible(row)
    }
    Column(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onActivate()
            }
        },
    ) {
        if (crumbs != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                LabCrumbs(crumbs, Modifier.weight(1f))
                val dir = pane.currentDir
                val free = remember(dir?.absolutePath, pane.listingTick) { if (dir == null) 0L else runCatching { dir.usableSpace }.getOrDefault(0L) }
                if (free > 0) Text(L("%1\$s free", StringUtils.formatBytes(free)), color = SimpleMuted, fontSize = 11.sp, maxLines = 1)
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(if (dual && active) 2.dp else 1.dp, if (dual && active) accent else SimpleLine, SimpleShape),
        ) {
            if (grid) {
                LazyVerticalGrid(columns = GridCells.Fixed(cols), state = pane.gridState, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        LabFmCell(r, pane, focused = i == row, active = active, onTap = { onTap(i) }, onLong = { onLong(i) })
                    }
                }
            } else {
                val compact = pane.viewMode == FmViewMode.COMPACT && pane.loc != FmLoc.Home
                LazyColumn(state = pane.listState, contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        val g = r.group
                        if (g != null && (i == 0 || rows[i - 1].group != g)) {
                            Text(
                                L(g).uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp,
                                modifier = Modifier.padding(start = 14.dp, top = if (i == 0) 4.dp else 14.dp, bottom = 4.dp),
                            )
                        } else if (i > 0) SimpleRowDivider()
                        LabFmLine(
                            r, pane, compact = compact, focused = i == row, active = active, col = if (i == row && active) col else 0,
                            menu = when (r) { is LItem -> pick == null && !pane.selectionMode; is LTile -> r.tile.menu != null; else -> false },
                            storageTick = storageTick,
                            onTap = { onTap(i) }, onLong = { onLong(i) }, onMenu = { onMenu(i) },
                        )
                    }
                }
            }
            val shown = rows.count { it !is LParent }
            val err = pane.error
            val msg = when {
                pane.loading -> L("Loading…")
                shown == 0 && err != null -> err
                shown == 0 && pane.deepSearching -> L("Searching…")
                shown == 0 && (pane.query.isNotBlank() || pane.deepResults != null) -> L("Nothing matches")
                shown == 0 && pane.loc != FmLoc.Home -> L("Empty folder")
                else -> null
            }
            if (msg != null) Text(msg, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}

/** Keeps grid cell [index] on screen, scrolling only when it would leave the visible part. */
private suspend fun LazyGridState.keepVisibleCell(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) { scrollToItem(index); return }
    val viewEnd = layoutInfo.viewportEndOffset
    val first = visible.first().index
    val lastFull = visible.lastOrNull { it.offset.y + it.size.height <= viewEnd }?.index ?: visible.last().index
    if (index < first || index > lastFull) animateScrollToItem(index)
}

@Composable
private fun LabFmLine(
    r: LRow,
    pane: FmPane,
    compact: Boolean,
    focused: Boolean,
    active: Boolean,
    col: Int,
    menu: Boolean,
    storageTick: Int,
    onTap: () -> Unit,
    onLong: () -> Unit,
    onMenu: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val lit = focused && active
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .simpleRowFocus(focused, active)
            .pointerInput(r.key) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) }
            .padding(horizontal = 14.dp, vertical = if (compact) 4.dp else 8.dp),
    ) {
        var usage: Float? = null
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = if (compact) 30.dp else 40.dp)) {
            when (r) {
                LParent -> {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = if (lit) accent else SimpleMuted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("..", color = if (focused) SimpleStrong else SimpleInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (!compact) Text(L("Up one level"), color = SimpleDim, fontSize = 12.sp)
                    }
                }
                is LTile -> {
                    val t = r.tile
                    val tint = t.tint ?: accent
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(tint.copy(alpha = 0.18f), SimpleShape)) {
                        Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    val (line, frac) = labTileLine(t, storageTick)
                    usage = frac
                    Column(Modifier.weight(1f)) {
                        Text(labFixed(t.title), color = if (focused) SimpleStrong else SimpleInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (line.isNotBlank()) Text(line, color = SimpleDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                is LItem -> {
                    val item = r.item
                    if (pane.selectionMode) {
                        LabTick(item.key in pane.selected)
                        Spacer(Modifier.width(12.dp))
                    }
                    val isArc = remember(item.key) { item is FmItem.Disk && !item.isDir && isBrowsableArchive(item.file) }
                    FmItemIcon(item, isArc, if (compact) 26.dp else 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = if (focused) SimpleStrong else SimpleInk, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if (!compact) {
                            Text(detailFor(item, pane), color = SimpleDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.sub != null) Text(r.sub, color = SimpleDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (compact) {
                        Spacer(Modifier.width(10.dp))
                        Text(if (item.isDir) fmDate(item.modified) else StringUtils.formatBytes(item.size), color = SimpleMuted, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            if (menu) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .background(if (col == 1) accent.copy(alpha = 0.3f) else if (focused) SimpleCard else Color.Transparent, SimpleShape)
                        .then(if (col == 1) Modifier.border(2.dp, accent, SimpleShape) else Modifier)
                        .simpleTap(onMenu),
                ) { Icon(Icons.Filled.MoreHoriz, contentDescription = L("Options"), tint = if (focused) SimpleStrong else SimpleMuted, modifier = Modifier.size(20.dp)) }
            }
        }
        usage?.let { f ->
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(SimpleLine)) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(3.dp).background((r as? LTile)?.tile?.tint ?: accent))
            }
        }
    }
}

/** A Home tile's second line (used / total, or size and item count, off the main thread) and its used share, as Classic's tile. */
@Composable
private fun labTileLine(tile: FmHomeTile, refreshTick: Int): Pair<String, Float?> {
    var usage by remember(tile.dir) { mutableStateOf<Pair<Long, Long>?>(null) }
    var count by remember(tile.dir) { mutableStateOf<Pair<Long, Int>?>(null) }
    if (tile.usage) {
        LaunchedEffect(tile.dir, refreshTick) { usage = withContext(Dispatchers.IO) { volumeUsage(tile.dir) } }
    }
    if (tile.countItems) {
        LaunchedEffect(tile.dir, refreshTick) {
            count = withContext(Dispatchers.IO) {
                val n = tile.dir.list()?.size ?: 0
                val (bytes, _) = treeSize(tile.dir) { !isActive }
                bytes to n
            }
        }
    }
    val u = usage
    val c = count
    return when {
        tile.usage && u != null -> L("%1\$s of %2\$s used", StringUtils.formatBytes(u.first), StringUtils.formatBytes(u.second)) to (if (u.second > 0) u.first.toFloat() / u.second else null)
        tile.countItems && c != null -> "${StringUtils.formatBytes(c.first)} · ${itemCountLabel(c.second)}" to null
        else -> (tile.subtitle?.let(::labFixed) ?: "") to null
    }
}

@Composable
private fun LabFmCell(r: LRow, pane: FmPane, focused: Boolean, active: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val lit = focused && active
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .graphicsLayer { val k = if (lit) 1.04f else 1f; scaleX = k; scaleY = k }
            .background(if (lit) accent.copy(alpha = 0.18f) else if (focused) SimpleCard else Color.Transparent, SimpleShape)
            .border(if (lit) 2.dp else 1.dp, if (lit) accent else Color.Transparent, SimpleShape)
            .pointerInput(r.key) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) }
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        when (r) {
            is LItem -> {
                val item = r.item
                Box {
                    val isArc = remember(item.key) { item is FmItem.Disk && !item.isDir && isBrowsableArchive(item.file) }
                    FmItemIcon(item, isArc, 52.dp)
                    if (pane.selectionMode) Box(Modifier.align(Alignment.TopEnd)) { LabTick(item.key in pane.selected) }
                }
                Spacer(Modifier.height(6.dp))
                Text(item.name, color = if (focused) SimpleStrong else SimpleInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.height(32.dp))
                Text(if (item.isDir) fmDate(item.modified) else StringUtils.formatBytes(item.size), color = SimpleDim, fontSize = 10.sp, maxLines = 1)
            }
            else -> {
                Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = if (lit) accent else SimpleMuted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(6.dp))
                Text("..", color = if (focused) SimpleStrong else SimpleInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A selection tick: an accent square with a check when on, an outline when off. */
@Composable
private fun LabTick(on: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .background(if (on) accent else Color.Transparent, SimpleShape)
            .border(2.dp, if (on) accent else SimpleMuted, SimpleShape),
    ) { if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
}

/** The path as tappable segments; the last one is where the pane is. Scrolls to the end as the path changes. */
@Composable
private fun LabCrumbs(segments: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val scroll = rememberScrollState()
    LaunchedEffect(segments.size, segments.lastOrNull()?.first) { scroll.scrollTo(scroll.maxValue) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.horizontalScroll(scroll)) {
        segments.forEachIndexed { i, (label, go) ->
            val last = i == segments.lastIndex
            Text(
                if (i == 0) L(label) else label,
                color = if (last) SimpleStrong else accent,
                fontSize = 13.sp, fontWeight = if (last) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.then(if (last) Modifier else Modifier.simpleTap(go)).padding(horizontal = 5.dp, vertical = 6.dp),
            )
            if (!last) Text("›", color = SimpleDim, fontSize = 14.sp)
        }
    }
}

/** A toolbar button for touch (the controller reaches the same through Y's menu). */
@Composable
private fun LabToolButton(icon: ImageVector, label: String, enabled: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = if (small) 4.dp else 6.dp)
            .size(if (small) 34.dp else 38.dp)
            .background(SimpleCard, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .then(if (enabled) Modifier.simpleTap(onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
    ) { Icon(icon, contentDescription = label, tint = SimpleInk, modifier = Modifier.size(20.dp)) }
}

/**
 * A status line under the panes (the search, the clipboard, the running operation, the selection): its text, a thin
 * progress bar when [progress] is set (below 0 = busy without a figure), and buttons to tap.
 */
@Composable
private fun LabStatusLine(text: String, icon: ImageVector? = null, progress: Float? = null, actions: List<Pair<String, () -> Unit>> = emptyList()) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .labPanel()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = SimpleStrong, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                actions.forEach { (label, run) ->
                    Text(
                        label, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp).background(SimpleCard, SimpleShape).simpleTap(run).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(SimpleLine)) {
                if (progress >= 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(accent))
            }
        }
    }
}

// ── Dialogs ──

/** Classic's Sort dialog in Lab's look: folders and files each have their order and a reverse switch; Apply keeps it. */
@Composable
private fun LabSortDialog(sort: FmSort, onDismiss: () -> Unit, onApply: (FmSort) -> Unit) {
    var s by remember { mutableStateOf(sort) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LabDialog(L("Sort"), onDismiss) {
        LabDialogHeading(L("Folders"))
        listOf(FmSortKey.NAME, FmSortKey.DATE).forEachIndexed { i, k ->
            LabDialogRow(L(k.label()), selected = s.folders == k, requester = if (i == 0) first else null) { s = s.copy(folders = k) }
        }
        LabDialogRow(L("Reverse"), toggle = s.foldersDesc) { s = s.copy(foldersDesc = !s.foldersDesc) }
        LabDialogHeading(L("Files"))
        FmSortKey.entries.forEach { k -> LabDialogRow(L(k.label()), selected = s.files == k) { s = s.copy(files = k) } }
        LabDialogRow(L("Reverse"), toggle = s.filesDesc) { s = s.copy(filesDesc = !s.filesDesc) }
        LabDialogButtons {
            LabButton(L("Cancel"), onClick = onDismiss)
            LabButton(L("Apply"), primary = true) { onApply(s) }
        }
    }
}

/** Classic's Compress dialog in Lab's look: the archive's name and its format (ZIP or 7z). */
@Composable
private fun LabCompressDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (String, PackFormat) -> Unit) {
    var format by remember { mutableStateOf(PackFormat.ZIP) }
    LabTextPrompt(
        title = L("Compress"),
        initial = defaultName,
        confirm = L("Compress"),
        suffix = ".${format.ext}",
        valid = { it.isNotBlank() && !it.contains('/') },
        onDismiss = onDismiss,
        extra = {
            LabDialogHeading(L("Format"))
            PackFormat.entries.filter { it != PackFormat.TAR_ZST }.forEach { f ->
                LabDialogRow(f.label, selected = format == f) { format = f }
            }
        },
    ) { name -> onConfirm(name, format) }
}

/** Asks what to do with one item that already exists; [batch] offers "Apply to all". Cancel or B = cancel. */
@Composable
private fun LabConflictDialog(name: String, isDir: Boolean, batch: Boolean, onAnswer: (ConflictChoice?, Boolean) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LabDialog(L("\"%1\$s\" already exists", name), onDismiss = { onAnswer(null, false) }) {
        LabDialogRow(if (isDir) L("Merge") else L("Overwrite"), requester = first) { onAnswer(if (isDir) ConflictChoice.MERGE else ConflictChoice.OVERWRITE, all) }
        LabDialogRow(L("Keep both")) { onAnswer(ConflictChoice.KEEP_BOTH, all) }
        LabDialogRow(L("Skip")) { onAnswer(ConflictChoice.SKIP, all) }
        if (batch) LabDialogRow(L("Apply to all"), toggle = all) { all = !all }
        LabDialogButtons { LabButton(L("Cancel")) { onAnswer(null, false) } }
    }
}

/** Classic's file / folder Properties in Lab's look: sizes, dates, access, and the Windows Read-only / Hidden flags. */
@Composable
private fun LabDiskProperties(file: File, containers: List<Container>, imagefsDir: File, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val location = remember(file.absolutePath, containers) { describeLocation(file, containers, imagefsDir) }
    var attrs by remember(file.absolutePath) { mutableStateOf<FileAttrState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tree by remember(file.absolutePath) { mutableStateOf<Pair<Long, Int>?>(null) }
    var created by remember(file.absolutePath) { mutableStateOf(0L) }
    val done = remember { FocusRequester() }
    LaunchedEffect(file.absolutePath) {
        launch { delay(80); runCatching { done.requestFocus() } }
        attrs = withContext(Dispatchers.IO) { readFileAttrs(file) }
        created = withContext(Dispatchers.IO) {
            runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis() }.getOrDefault(0L)
        }
        if (file.isDirectory) tree = withContext(Dispatchers.IO) { treeSize(file) { !isActive } }
    }
    LabDialog(file.name.ifBlank { file.absolutePath }, onDismiss) {
        LabPropLine(L("Location"), file.parent ?: "—")
        if (location.storage == FavStorage.CONTAINER) LabPropLine(L("In Windows"), (location.containerName?.let { "$it · " } ?: "") + location.displayPath)
        LabPropLine(L("Type"), if (file.isDirectory) L("Folder") else file.extension.uppercase().ifBlank { "" }.let { if (it.isEmpty()) L("File") else L("%1\$s file", it) })
        if (file.isDirectory) {
            val t = tree
            LabPropLine(L("Size"), if (t == null) L("Counting…") else L("%1\$s · %2\$s files", StringUtils.formatBytes(t.first), t.second))
        } else {
            LabPropLine(L("Size"), "${StringUtils.formatBytes(file.length())} (${"%,d".format(file.length())} ${L("bytes")})")
        }
        LabPropLine(L("Modified"), fmDateTime(file.lastModified()))
        if (created > 0 && created != file.lastModified()) LabPropLine(L("Created"), fmDateTime(created))
        LabPropLine(L("Access"), listOf(if (file.canRead()) L("Read") else L("No read"), if (file.canWrite()) L("write") else L("no write")).joinToString(", "))
        Spacer(Modifier.height(2.dp))
        SimpleRowDivider()
        val st = attrs
        LabDialogRow(L("Read-only"), toggle = st?.readOnly == true, enabled = st != null && !busy) {
            val want = !(st?.readOnly ?: false)
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { setReadOnly(file, want) }
                busy = false
                if (ok) { attrs = attrs?.copy(readOnly = want); onChanged() } else toast(context, L("Couldn't change Read-only"))
            }
        }
        LabDialogRow(L("Hidden"), toggle = st?.hidden == true, enabled = st != null && st.hiddenSupported && !busy) {
            val want = !(st?.hidden ?: false)
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { setHidden(file, want) }
                busy = false
                if (ok) { attrs = attrs?.copy(hidden = want); onChanged() }
                else {
                    attrs = attrs?.copy(hiddenSupported = false)
                    toast(context, L("This storage can't keep the Hidden flag"))
                }
            }
        }
        LabDialogButtons { LabButton(L("Done"), primary = true, requester = done, onClick = onDismiss) }
    }
}

/** Properties of an entry inside an archive, in Lab's look. */
@Composable
private fun LabEntryProperties(item: FmItem.Entry, onDismiss: () -> Unit) {
    val e = item.entry
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { done.requestFocus() } }
    LabDialog(e.name, onDismiss) {
        LabPropLine(L("Archive"), item.archive.name)
        LabPropLine(L("Path"), e.path)
        LabPropLine(L("Type"), if (e.isDir) L("Folder") else e.name.substringAfterLast('.', "").uppercase().ifBlank { L("File") })
        LabPropLine(L("Size"), StringUtils.formatBytes(item.size))
        if (e.packedSize >= 0 && !e.isDir) LabPropLine(L("Packed"), StringUtils.formatBytes(e.packedSize))
        LabPropLine(L("Modified"), fmDateTime(e.modified))
        e.method?.let { LabPropLine(L("Method"), it) }
        e.crc?.let { LabPropLine(L("CRC"), it, mono = true) }
        if (e.encrypted) LabPropLine(L("Encrypted"), L("Yes"))
        LabDialogButtons { LabButton(L("Done"), primary = true, requester = done, onClick = onDismiss) }
    }
}
