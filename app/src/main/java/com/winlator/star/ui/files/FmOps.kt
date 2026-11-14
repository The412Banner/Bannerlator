package com.winlator.star.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.winlator.star.UnpackArchiveActivity
import com.winlator.star.container.Container
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GameIdentifier
import com.winlator.star.core.StorageRoot
import com.winlator.star.core.unpack.ArchiveBrowse
import com.winlator.star.core.unpack.ArchiveExtract
import com.winlator.star.core.unpack.ArchivePasswords
import com.winlator.star.core.unpack.FastExtract
import com.winlator.star.core.unpack.SevenZip
import com.winlator.star.ui.screens.ExeQuickRun
import com.winlator.star.ui.screens.ExeShortcutImporter
import com.winlator.star.util.FavoritesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The file manager's operations and the state they work on: the clipboard, the one running operation (label,
 * progress, cancel), the dialogs an action asks for, and copy / move / delete / rename / new / compress / extract /
 * open / share / run / add to Games / favourites. Classic's [FileManagerRoot] and Lab's file manager share it and
 * each draws it in its own look.
 */
@Stable
internal class FmOps(
    private val context: Context,
    private val scope: CoroutineScope,
    private val containers: () -> List<Container>,
    /** Lists every pane that shows a folder again. */
    private val refreshAll: () -> Unit,
    /** A favourite was added or removed (Home's tiles change). */
    private val onFavoritesChanged: () -> Unit,
) {
    // ── Clipboard and running operation ──
    var clipboard by mutableStateOf<List<File>>(emptyList())
    var clipCut by mutableStateOf(false)
    var opLabel by mutableStateOf<String?>(null)
    var opProgress by mutableStateOf<Float?>(null)
    var opJob by mutableStateOf<Job?>(null)

    // ── Dialog state ──
    var newFolderIn by mutableStateOf<File?>(null)
    var newFileIn by mutableStateOf<File?>(null)
    var renameTarget by mutableStateOf<File?>(null)
    var deleteTargets by mutableStateOf<List<File>>(emptyList())
    var propsTarget by mutableStateOf<FmItem?>(null)
    var compressTargets by mutableStateOf<List<File>>(emptyList())
    var extractReq by mutableStateOf<Pair<FmPane, List<FmItem.Entry>>?>(null)
    var runChoice by mutableStateOf<File?>(null)
    var shortcutChoice by mutableStateOf<File?>(null)
    var conflict by mutableStateOf<Triple<String, Boolean, Boolean>?>(null)
    var conflictAnswer by mutableStateOf<CompletableDeferred<Pair<ConflictChoice?, Boolean>>?>(null)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

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

    /** The conflict dialog's answer: null = cancel; [all] = use it for the rest of the batch. */
    fun answerConflict(choice: ConflictChoice?, all: Boolean) {
        conflict = null
        conflictAnswer?.complete(choice to all)
        conflictAnswer = null
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
        val containers = containers()
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
        val containers = containers()
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
        onFavoritesChanged()
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

    /** A or a tap on [item]: folders open, a picker returns an accepted file through [onPick], files open by kind. */
    fun openItem(p: FmPane, item: FmItem, pick: FmPick?, onPick: ((File) -> Unit)?) {
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
        add(FmMenuEntry("Open", if (item.isDir) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew) { openItem(p, item, null, null) })
        add(FmMenuEntry("Extract…", Icons.Filled.Unarchive) { extractReq = p to listOf(item) })
        add(FmMenuEntry("Select", Icons.Filled.CheckBox) { p.startSelect(item) })
        add(FmMenuEntry("Properties", Icons.Filled.Info) { propsTarget = item })
    }

    fun newMenuEntries(dir: File): List<FmMenuEntry> = listOf(
        FmMenuEntry("New folder", Icons.Filled.CreateNewFolder) { newFolderIn = dir },
        FmMenuEntry("New text file", Icons.Filled.NoteAdd) { newFileIn = dir },
    )

    // ── Home ──

    fun homeSections(drives: List<StorageRoot>, imagefsDir: File, pick: FmPick?, driveCPath: File?): List<FmHomeSection> {
        val containers = containers()
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
}
