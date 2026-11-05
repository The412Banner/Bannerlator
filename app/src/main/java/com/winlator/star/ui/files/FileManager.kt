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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

    // ── Clipboard and running operation ──
    var clipboard by remember { mutableStateOf<List<File>>(emptyList()) }
    var clipCut by remember { mutableStateOf(false) }
    var opLabel by remember { mutableStateOf<String?>(null) }
    var opProgress by remember { mutableStateOf<Float?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }

    // ── Dialog state ──
    var newFolderIn by remember { mutableStateOf<File?>(null) }
    var newFileIn by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTargets by remember { mutableStateOf<List<File>>(emptyList()) }
    var propsTarget by remember { mutableStateOf<FmItem?>(null) }
    var compressTargets by remember { mutableStateOf<List<File>>(emptyList()) }
    var sortFor by remember { mutableStateOf<FmPane?>(null) }
    var extractReq by remember { mutableStateOf<Pair<FmPane, List<FmItem.Entry>>?>(null) }
    var runChoice by remember { mutableStateOf<File?>(null) }
    var shortcutChoice by remember { mutableStateOf<File?>(null) }
    var conflict by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
    var conflictAnswer by remember { mutableStateOf<CompletableDeferred<Pair<ConflictChoice?, Boolean>>?>(null) }
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

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refreshAll() = panes.forEach { if (it.loc != FmLoc.Home) it.refresh() }

    /** Runs [block] as the visible operation (label + progress + cancel), one at a time. */
    fun runOp(label: String, block: suspend () -> Unit) {
        if (opJob?.isActive == true) { toast("Another operation is still running"); return }
        opLabel = label
        opProgress = null
        opJob = scope.launch {
            try { block() } finally {
                opLabel = null
                opProgress = null
                opJob = null
                refreshAll()
            }
        }
    }

    suspend fun askConflict(name: String, isDir: Boolean, batch: Boolean): Pair<ConflictChoice?, Boolean> {
        val d = CompletableDeferred<Pair<ConflictChoice?, Boolean>>()
        conflictAnswer = d
        conflict = Triple(name, isDir, batch)
        return d.await()
    }

    // ── File operations ──

    fun paste(sources: List<File>, dest: File, cut: Boolean) {
        if (sources.isEmpty()) return
        runOp(if (cut) "Moving…" else "Copying…") {
            var applyAll: ConflictChoice? = null
            var done = 0
            var failed = 0
            var skipped = 0
            for ((i, src) in sources.withIndex()) {
                if (src.isDirectory && isWithin(dest, src)) { failed++; continue }
                if (cut && src.parentFile?.absolutePath == dest.absolutePath) { skipped++; continue }
                var dst = File(dest, src.name)
                if (dst.exists()) {
                    val choice = applyAll ?: run {
                        val (c, all) = askConflict(src.name, src.isDirectory, sources.size > 1)
                        if (all && c != null) applyAll = c
                        c
                    }
                    when (choice) {
                        null, ConflictChoice.SKIP -> { skipped++; continue }
                        ConflictChoice.KEEP_BOTH -> dst = uniqueDestination(dest, src.name)
                        ConflictChoice.OVERWRITE, ConflictChoice.MERGE -> Unit
                    }
                }
                opLabel = buildString {
                    append(if (cut) "Moving" else "Copying")
                    if (sources.size > 1) append(" ${i + 1}/${sources.size}")
                    append(" — ").append(src.name)
                }
                opProgress = 0f
                val target = dst
                val cb = FileUtils.ProgressCallback { copied, total -> opProgress = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 1f }
                val ok = withContext(Dispatchers.IO) {
                    if (cut) FileUtils.moveWithProgress(src, target, cb) else FileUtils.copyWithProgress(src, target, cb)
                }
                if (ok) done++ else failed++
            }
            if (cut) clipboard = emptyList()
            val msg = when {
                failed > 0 -> "$done done, $failed failed"
                skipped > 0 -> "$done done, $skipped skipped"
                sources.size > 1 -> "$done items ${if (cut) "moved" else "copied"}"
                else -> null
            }
            msg?.let(::toast)
        }
    }

    fun delete(targets: List<File>) {
        runOp("Deleting…") {
            var failed = 0
            targets.forEachIndexed { i, f ->
                opLabel = "Deleting ${i + 1}/${targets.size} — ${f.name}"
                opProgress = i.toFloat() / targets.size
                if (!withContext(Dispatchers.IO) { FileUtils.delete(f) }) failed++
            }
            if (failed > 0) toast("$failed couldn't be deleted")
        }
    }

    fun rename(file: File, newName: String) {
        val target = File(file.parentFile, newName)
        if (target.exists()) { toast("\"$newName\" already exists"); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { file.renameTo(target) }
            if (!ok) toast("Rename failed")
            refreshAll()
        }
    }

    fun createFolder(parent: File, name: String) {
        val target = File(parent, name)
        if (target.exists()) { toast("\"$name\" already exists"); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { target.mkdirs() }
            if (!ok) toast("Couldn't create the folder")
            refreshAll()
        }
    }

    fun createFile(parent: File, name: String) {
        val target = File(parent, name)
        if (target.exists()) { toast("\"$name\" already exists"); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { target.createNewFile() }.getOrDefault(false) }
            refreshAll()
            if (!ok) toast("Couldn't create the file")
            else context.startActivity(TextEditorActivity.intent(context, target))
        }
    }

    fun compress(files: List<File>, dir: File, name: String, format: PackFormat) {
        val out = uniqueDestination(dir, "$name.${format.ext}")
        runOp("Compressing ${out.name}…") {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val args = listOf("a", "-t${format.sevenZipType}", "-bsp1", "-bso0", "-y", "--", out.absolutePath) + files.map { it.name }
                    val proc = SevenZip.newProcess(context, *args.toTypedArray()).directory(dir).redirectErrorStream(true).start()
                    try {
                        val reader = proc.inputStream.bufferedReader()
                        val buf = StringBuilder()
                        val pct = Regex("(\\d{1,3})%")
                        while (true) {
                            val c = reader.read()
                            if (c < 0) break
                            if (c == '\b'.code || c == '\r'.code || c == '\n'.code) {
                                pct.findAll(buf).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let { p ->
                                    withContext(Dispatchers.Main) { opProgress = p / 100f }
                                }
                                buf.setLength(0)
                            } else if (buf.length < 256) buf.append(c.toChar())
                        }
                        proc.waitFor() == 0
                    } finally {
                        proc.destroy()
                    }
                }.getOrDefault(false)
            }
            if (!ok) { out.delete(); toast("Couldn't create ${out.name}") } else toast("Created ${out.name}")
        }
    }

    fun extractEntries(srcPane: FmPane, entries: List<FmItem.Entry>, dest: File) {
        val idx = srcPane.index ?: return
        val archive = idx.source
        val list = if (entries.isEmpty()) idx.tree.children("").map { e -> FmItem.Entry(archive, e, idx.tree.sizeOf(e)) } else entries
        runOp("Extracting…") {
            withContext(Dispatchers.IO) { dest.mkdirs() }
            val planned = list.map { it ->
                val name = if (File(dest, it.entry.name).exists()) uniqueDestination(dest, it.entry.name).name else it.entry.name
                ArchiveExtract.Planned(it.entry, name, false)
            }
            val failed = runCatching {
                ArchiveExtract.toFolder(context, idx, planned, dest, ArchivePasswords.get(archive) ?: "") { label, f ->
                    opLabel = label
                    opProgress = f
                }
            }.getOrElse { t -> toast(t.message ?: "Extraction failed"); -1 }
            when {
                failed == 0 -> toast("Extracted to ${dest.name}")
                failed > 0 -> toast("$failed couldn't be extracted")
            }
        }
    }

    fun openWith(file: File) {
        val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".tileprovider", file) }.getOrNull()
        if (uri == null) { toast("Can't hand this file to another app"); return }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(view, file.name))
        } catch (_: ActivityNotFoundException) {
            toast("No app can open this file")
        }
    }

    fun share(files: List<File>) {
        val uris = files.mapNotNull { f -> runCatching { FileProvider.getUriForFile(context, context.packageName + ".tileprovider", f) }.getOrNull() }
        if (uris.isEmpty()) { toast("Can't share these files"); return }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        send.type = "*/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(Intent.createChooser(send, null)) } catch (_: ActivityNotFoundException) { toast("No app can share these files") }
    }

    fun copyPath(file: File) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
        toast("Path copied")
    }

    fun runIn(file: File, container: Container) {
        scope.launch {
            val shortcut = withContext(Dispatchers.IO) { runCatching { ExeQuickRun.writeRunShortcut(context, container, file) } }
            shortcut.onSuccess { context.startActivity(ExeQuickRun.sessionIntent(context, container, it)) }
                .onFailure { toast(ExeQuickRun.failureMessage(file, it)) }
        }
    }

    fun runFile(file: File) {
        when {
            containers.isEmpty() -> toast("No container yet: create one first")
            containers.size == 1 -> runIn(file, containers.first())
            else -> runChoice = file
        }
    }

    fun addShortcutIn(file: File, container: Container) {
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val id = runCatching { GameIdentifier.identify(file) }.getOrNull()
                    val display = id?.name?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                    ExeShortcutImporter.addToShortcuts(context, container, file, display, id?.appId).nameWithoutExtension
                }.getOrNull()
            }
            toast(if (name == null) "Couldn't add ${file.name} to Games" else "Added \"$name\" to Games")
        }
    }

    fun addShortcut(file: File) {
        when {
            containers.isEmpty() -> toast("No container yet: create one first")
            containers.size == 1 -> addShortcutIn(file, containers.first())
            else -> shortcutChoice = file
        }
    }

    fun fastExtract(file: File) {
        scope.launch {
            when (val o = FastExtract.start(context, file)) {
                is FastExtract.Outcome.Started -> toast("Unpacking ${o.name}…")
                FastExtract.Outcome.Busy -> toast("Another unpack is already running")
                is FastExtract.Outcome.NotArchive -> toast("Not an archive this can unpack")
                is FastExtract.Outcome.OpenScreen -> {
                    o.toast?.let(::toast)
                    context.startActivity(UnpackArchiveActivity.intent(context, o.archivePath))
                }
            }
        }
    }

    fun toggleFavorite(dir: File) {
        val now = FavoritesStore.toggle(context, dir.absolutePath)
        storageTick++
        toast(if (now) "Added to Favorites" else "Removed from Favorites")
    }

    /** Opens a file taken out of an archive (cached copy): text read-only, nested archives as folders, else another app. */
    fun openCached(p: FmPane, f: File, shownName: String) {
        when {
            isBrowsableArchive(f) -> p.open(FmLoc.InArchive(f, ""))
            isTextFile(f) -> context.startActivity(TextEditorActivity.intent(context, f, readOnly = true, title = shownName))
            else -> openWith(f)
        }
    }

    fun openItem(p: FmPane, item: FmItem) {
        when (item) {
            is FmItem.Disk -> {
                val f = item.file
                if (item.isDir) { p.openDir(f); return }
                if (pick != null) {
                    if (pick.accepts(f)) {
                        f.parentFile?.let { FmPrefs.setLastPickerDir(context, it) }
                        onPick?.invoke(f)
                    }
                    return
                }
                when {
                    isBrowsableArchive(f) -> p.open(FmLoc.InArchive(f, ""))
                    isRunnable(f.name) -> runFile(f)
                    isTextFile(f) -> context.startActivity(TextEditorActivity.intent(context, f))
                    else -> openWith(f)
                }
            }
            is FmItem.Entry -> {
                if (item.isDir) { p.open(FmLoc.InArchive(item.archive, item.entry.path)); return }
                val idx = p.index ?: return
                runOp("Opening ${item.name}…") {
                    val got = runCatching {
                        ArchiveExtract.toViewCache(context, idx, listOf(item.entry), ArchivePasswords.get(item.archive) ?: "") { pct -> opProgress = pct / 100f }
                    }
                    got.onSuccess { files -> files.firstOrNull()?.let { openCached(p, it, item.name) } }
                        .onFailure { toast(it.message ?: "Couldn't open ${item.name}") }
                }
            }
        }
    }

    // ── Menus ──

    fun diskMenu(p: FmPane, item: FmItem.Disk): List<FmMenuEntry> {
        val f = item.file
        val dir = item.isDir
        val isArchive = !dir && isBrowsableArchive(f)
        val inno = !dir && runCatching { SevenZip.isInnoSetup(f) }.getOrDefault(false)
        val unpackable = !dir && (inno || runCatching { ArchiveBrowse.canUnpack(f) || SevenZip.looksLikeArchive(f) }.getOrDefault(false))
        return buildList {
            if (dir) add(FmMenuEntry("Open", Icons.Filled.FolderOpen) { p.openDir(f) })
            if (isArchive) add(FmMenuEntry("Browse", Icons.Filled.FolderZip) { p.open(FmLoc.InArchive(f, "")) })
            if (!dir && isRunnable(f.name)) add(FmMenuEntry("Run", Icons.Filled.PlayArrow) { runFile(f) })
            if (!dir && f.extension.equals("exe", true)) add(FmMenuEntry("Add to Games", Icons.Filled.AddToHomeScreen) { addShortcut(f) })
            if (unpackable) {
                add(FmMenuEntry("Fast Extract", Icons.Filled.Bolt) { fastExtract(f) })
                add(FmMenuEntry(if (inno) "Unpack / Install…" else "Unpack…", Icons.Filled.Unarchive) {
                    context.startActivity(UnpackArchiveActivity.intent(context, f.absolutePath))
                })
            }
            if (!dir) {
                add(FmMenuEntry(if (isTextFile(f)) "Edit" else "Open as text", Icons.Filled.TextSnippet) {
                    context.startActivity(TextEditorActivity.intent(context, f))
                })
                add(FmMenuEntry("Open with…", Icons.AutoMirrored.Filled.OpenInNew) { openWith(f) })
            }
            add(FmMenuEntry("Select", Icons.Filled.CheckBox) { p.startSelect(item) })
            add(FmMenuEntry("Copy", Icons.Filled.ContentCopy) { clipboard = listOf(f); clipCut = false })
            add(FmMenuEntry("Cut", Icons.Filled.ContentCut) { clipboard = listOf(f); clipCut = true })
            add(FmMenuEntry("Rename", Icons.Filled.Edit) { renameTarget = f })
            add(FmMenuEntry("Compress", Icons.Filled.FolderZip) { compressTargets = listOf(f) })
            if (!dir) add(FmMenuEntry("Share", Icons.Filled.Share) { share(listOf(f)) })
            if (dir) {
                val fav = FavoritesStore.isFavorite(context, f.absolutePath)
                add(FmMenuEntry(if (fav) "Remove from Favorites" else "Add to Favorites", if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) { toggleFavorite(f) })
            }
            add(FmMenuEntry("Copy path", Icons.Filled.Link) { copyPath(f) })
            add(FmMenuEntry("Properties", Icons.Filled.Info) { propsTarget = item })
            add(FmMenuEntry("Delete", Icons.Filled.Delete, danger = true) { deleteTargets = listOf(f) })
        }
    }

    fun entryMenu(p: FmPane, item: FmItem.Entry): List<FmMenuEntry> = buildList {
        add(FmMenuEntry("Open", if (item.isDir) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew) { openItem(p, item) })
        add(FmMenuEntry("Extract…", Icons.Filled.Unarchive) { extractReq = p to listOf(item) })
        add(FmMenuEntry("Select", Icons.Filled.CheckBox) { p.startSelect(item) })
        add(FmMenuEntry("Properties", Icons.Filled.Info) { propsTarget = item })
    }

    fun menuFor(p: FmPane, item: FmItem): (() -> List<FmMenuEntry>)? = when {
        pick != null -> null
        item is FmItem.Disk -> { { diskMenu(p, item) } }
        item is FmItem.Entry -> { { entryMenu(p, item) } }
        else -> null
    }

    // ── Home ──

    fun homeSections(): List<FmHomeSection> {
        val internal = File("/storage/emulated/0")
        val storage = buildList {
            add(FmHomeTile("Internal", HomeIcons.internal, null, internal, usage = true))
            drives.filter { it.removable && it.readable }.forEach { d -> add(FmHomeTile(d.label, HomeIcons.sd, androidx.compose.ui.graphics.Color(0xFF3DB37A), d.dir, usage = true)) }
            if (imagefsDir.isDirectory && pick == null) add(FmHomeTile("System", HomeIcons.system, androidx.compose.ui.graphics.Color(0xFF8A7AD8), imagefsDir, subtitle = "Drive Z:"))
        }
        val places = buildList {
            listOf(
                Triple("Downloads", HomeIcons.downloads, File(internal, "Download")),
                Triple("Pictures", HomeIcons.pictures, File(internal, "Pictures")),
                Triple("Documents", HomeIcons.documents, File(internal, "Documents")),
                Triple("Games", HomeIcons.games, File(internal, "Winlator/Games")),
                Triple("Bannerlator", HomeIcons.folder, File(internal, "Bannerlator")),
            ).forEach { (t, icon, dir) ->
                if (dir.isDirectory) add(FmHomeTile(t, icon, androidx.compose.ui.graphics.Color(0xFF2FA7A0), dir, countItems = t == "Downloads", subtitle = if (t == "Downloads") null else ""))
            }
        }
        val drivesC = buildList {
            val seen = HashSet<String>()
            driveCPath?.takeIf { it.isDirectory }?.let { add(FmHomeTile("Drive C:", HomeIcons.container, androidx.compose.ui.graphics.Color(0xFFD9A030), it, subtitle = "This container")); seen.add(it.absolutePath) }
            containers.forEach { c ->
                val dc = File(c.rootDir, ".wine/drive_c")
                if (dc.isDirectory && seen.add(dc.absolutePath)) add(FmHomeTile(c.name, HomeIcons.container, androidx.compose.ui.graphics.Color(0xFFD9A030), dc, subtitle = "Drive C:"))
            }
        }
        val favorites = FavoritesStore.list(context).map(::File).filter { it.isDirectory }.map { d ->
            val loc = describeLocation(d, containers, imagefsDir)
            FmHomeTile(
                d.name.ifBlank { d.absolutePath }, HomeIcons.favorite, badgeColors(loc).first, d,
                subtitle = loc.containerName?.let { "$it · ${loc.displayPath}" } ?: loc.driveLabel,
                menu = if (pick == null) { { listOf(FmMenuEntry("Remove from Favorites", Icons.Filled.StarBorder) { toggleFavorite(d) }) } } else null,
            )
        }
        return listOf(
            FmHomeSection("Storage", storage),
            FmHomeSection("Places", places),
            FmHomeSection("Containers", drivesC),
            FmHomeSection("Favorites", favorites),
        )
    }

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
    DisposableEffect(deckActions) {
        val options: () -> Unit = { moreOpen = true }
        val search: () -> Unit = { searchOpen = true }
        deckActions.options.value = options
        deckActions.search.value = search
        onDispose {
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
        ConflictDialog(name, isDir, batch) { choice, all ->
            conflict = null
            conflictAnswer?.complete(choice to all)
            conflictAnswer = null
        }
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

    // ── Layout ──

    Column(Modifier.fillMaxSize().fmBackdrop()) {
        if (pick != null && !pickerTitle.isNullOrEmpty()) {
            Text(
                pickerTitle,
                color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
            )
        }

        // Toolbar: acts on the active pane.
        Row(
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
                    if (newOpen) FmMenu(true, { newOpen = false }, listOf(
                        FmMenuEntry("New folder", Icons.Filled.CreateNewFolder) { newFolderIn = dir },
                        FmMenuEntry("New text file", Icons.Filled.NoteAdd) { newFileIn = dir },
                    ))
                }
            }
            Box {
                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                if (moreOpen) {
                    val entries = buildList {
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
                    }
                    FmMenu(true, { moreOpen = false }, entries)
                }
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
        if (clipboard.isNotEmpty() && pick == null) {
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (dir != null) "Select \"${dir.name.ifBlank { dir.absolutePath }}\"" else "Open a folder to select it", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        // Path line.
        val loc = pane.loc
        Row(
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
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        state = pane.gridState,
                        contentPadding = PaddingValues(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (showParent) item(key = "..") { FmParentRow(compact = true) { pane.up() } }
                        items(shown, key = { it.key }) { item ->
                            FmTile(item, pane, isArc(item), menuFor(item), onOpen = { onOpen(item) }, onLongPress = longPress(item))
                        }
                    }
                } else {
                    val compact = pane.viewMode == FmViewMode.COMPACT
                    LazyColumn(state = pane.listState, contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
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
private fun crumbsFor(pane: FmPane, containers: List<Container>, imagefsDir: File, driveCPath: File?): List<Pair<String, () -> Unit>> {
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
