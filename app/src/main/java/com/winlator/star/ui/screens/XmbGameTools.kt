package com.winlator.star.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.CopyGameToDriveC
import com.winlator.star.core.CustomSaveVault
import com.winlator.star.core.FileUtils
import com.winlator.star.core.GameSaveBackup
import com.winlator.star.core.StorageRoots
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.ui.findActivity
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Game tools as nested XMB columns (Phase 3). The ⋮-menu tools that used to open dialogs — Copy to
// Drive C, Change executable, Scrape cover, Back up / Restore saves — plus the folder browser they
// pick with. Logs live in XmbLogsMenu.kt, community configs in XmbCommunityMenu.kt.
//
// Each flow drives the same lower-level helpers as its ShortcutsScreen dialog and keeps its toasts.
// Layout rule for choice columns: the safe choice first (highlighted), the other choices under it,
// then the message text. Rows are single-line and the highlight sits high in the column, so text
// placed under the choices stays on screen.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Shared helpers for the tool columns. */
internal object XmbTools {
    /** An empty icon, so the continuation lines of a message keep the icon column without a glyph. */
    val blankIcon: ImageVector by lazy {
        ImageVector.Builder(
            name = "XmbBlank",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).build()
    }

    /**
     * About how many characters fit on one row label on this screen. Mirrors XmbNestedLayer's column
     * width for a column two levels deep (label = column minus icon box, gaps and padding).
     */
    fun lineChars(context: Context): Int {
        val cfg = context.resources.configuration
        val w = cfg.screenWidthDp.toFloat()
        val port = cfg.screenHeightDp > cfg.screenWidthDp * 1.05f
        val x0 = 16f + 2 * 46f + 8f
        val colW = maxOf(160f, minOf(if (port) w - x0 - 12f else 560f, w - x0 - 16f))
        val perChar = 7.6f * cfg.fontScale.coerceAtLeast(0.5f)
        return ((colW - 72f) / perChar).toInt().coerceIn(22, 72)
    }

    /** Greedy word wrap; words longer than a line (paths) are hard-split. Blank lines are dropped. */
    fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isBlank()) continue
            val line = StringBuilder()
            for (w0 in para.trim().split(Regex("\\s+"))) {
                var word = w0
                while (word.length > width) {
                    if (line.isNotEmpty()) { out.add(line.toString()); line.setLength(0) }
                    out.add(word.take(width))
                    word = word.drop(width)
                }
                if (word.isEmpty()) continue
                when {
                    line.isEmpty() -> line.append(word)
                    line.length + 1 + word.length <= width -> line.append(' ').append(word)
                    else -> { out.add(line.toString()); line.setLength(0); line.append(word) }
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    /** [text] as consecutive Info rows keyed "$key.0", "$key.1", …; only the first carries [icon]/[subtitle]. */
    fun para(key: String, text: String, icon: ImageVector, context: Context, subtitle: String? = null): List<XmbRow> =
        wrap(text, lineChars(context)).mapIndexed { i, line ->
            XmbRow.Info(
                key = "$key.$i",
                label = line,
                icon = if (i == 0) icon else blankIcon,
                subtitle = if (i == 0) subtitle else null,
            )
        }

    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun samePath(a: File, b: File): Boolean = a.absolutePath.trimEnd('/') == b.absolutePath.trimEnd('/')

    /** The container's C: drive folder, when it exists. */
    fun driveC(container: Container?): File? =
        container?.let { File(it.rootDir, ".wine/drive_c") }?.takeIf { it.isDirectory }

    /** Drive C (when given), then every readable storage volume — the places the in-app picker offers. */
    fun storageRoots(context: Context, driveC: File?): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        if (driveC != null && driveC.isDirectory) out.add("Drive C:" to driveC)
        runCatching { StorageRoots.list(context) }.getOrNull().orEmpty()
            .filter { it.readable }
            .forEach { out.add(it.label to it.dir) }
        return out
    }

    /** startActivity that also works from a non-Activity context. */
    fun start(context: Context, intent: Intent) {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Whether a column we built is still on the stack; the column's onClose must set [isOpen] = false.
 * Async work uses it to close only its own column, and never one the user has already left.
 */
internal class XmbOpen {
    var isOpen = true

    /** Pops the column if it's still open. Only call when nothing else is stacked above it. */
    fun close(xmb: XmbScope) {
        if (isOpen) {
            isOpen = false
            xmb.pop()
        }
    }
}

private const val KEY_LOADING = "loading"

// ── Folder browser ───────────────────────────────────────────────────────────────────────────────

/**
 * Reusable XMB folder browser: folders open deeper columns, files matching [accept] are pickable.
 * [roots] are quick jumps shown at the top of every column (e.g. "Drive C:" → …/drive_c); "Up one
 * folder" climbs until a root or a volume root. Hidden files follow the File Manager's setting.
 *
 * On a pick the browser collapses to ONE column (the folder the file came from), then calls
 * [onPick]. The caller closes it with a single xmb.pop() when done — or leaves it open after a
 * failure so the user can pick again. However deep the user browsed, one pop is always enough.
 */
internal fun xmbFileBrowserMenu(
    xmb: XmbScope, title: String, startDir: java.io.File,
    roots: List<Pair<String, java.io.File>>,   // quick-jump entries shown at the top, e.g. "Drive C" → …/drive_c
    accept: (java.io.File) -> Boolean,
    onPick: (java.io.File) -> Unit,
): XmbMenu = xmbBrowserColumn(
    XmbBrowse(xmb, title, roots, accept, pickDir = false, onPick = onPick),
    startDir, depth = 1, focus = null,
)

/** One browse session (shared by all its columns). [pickDir] = choose a folder instead of a file. */
private class XmbBrowse(
    val xmb: XmbScope,
    val title: String,
    val roots: List<Pair<String, File>>,
    val accept: (File) -> Boolean,
    val pickDir: Boolean,
    val onPick: (File) -> Unit,
) {
    val showHidden: Boolean = runCatching {
        PreferenceManager.getDefaultSharedPreferences(xmb.context).getBoolean("fmShowHidden", true)
    }.getOrDefault(true)

    /** The single browser column left open by the last pick (see xmbFileBrowserMenu). */
    var picked: XmbOpen? = null

    fun label(dir: File): String =
        roots.firstOrNull { XmbTools.samePath(it.second, dir) }?.first
            ?: if (dir.absolutePath == INTERNAL_ROOT) "Internal" else dir.name.ifEmpty { dir.absolutePath }

    /** Where "Up one folder" goes, or null at a jump target / volume root / unreadable parent. */
    fun parentOf(dir: File): File? {
        if (roots.any { XmbTools.samePath(it.second, dir) }) return null
        val p = dir.parentFile ?: return null
        if (p.absolutePath.trimEnd('/').ifEmpty { "/" } in NO_UP) return null
        return p.takeIf { it.isDirectory && it.canRead() }
    }

    companion object {
        const val INTERNAL_ROOT = "/storage/emulated/0"
        private val NO_UP = setOf("/", "/storage", "/storage/emulated", "/mnt", "/mnt/media_rw", "/data", "/data/user")
    }
}

private fun xmbBrowserKey(f: File, isDir: Boolean): String = if (isDir) "d:${f.name}" else "f:${f.name}"

private fun xmbBrowserRootIcon(root: File): ImageVector = when {
    root.absolutePath.endsWith("/drive_c") -> Icons.Filled.Storage
    root.absolutePath == XmbBrowse.INTERNAL_ROOT -> Icons.Filled.Smartphone
    root.absolutePath.startsWith("/storage/") -> Icons.Filled.SdStorage
    else -> Icons.Filled.FolderOpen
}

private val XMB_IMAGE_EXTS = InAppFilePicker.IMAGES.toSet()

/**
 * One browser column for [dir]. [depth] = how many browser columns are on the stack including this
 * one (Up and root jumps replace the column, so they keep it). [focus] is highlighted once listed.
 */
private fun xmbBrowserColumn(b: XmbBrowse, dir: File, depth: Int, focus: File?, me: XmbOpen = XmbOpen()): XmbMenu {
    val xmb = b.xmb
    val ctx = xmb.context
    var content by mutableStateOf<List<XmbRow>?>(null)   // null = still listing
    var job: Job? = null
    val parent = b.parentOf(dir)

    fun replaceWith(target: File, focusOn: File?) {
        xmb.pop()
        xmb.push(xmbBrowserColumn(b, target, depth, focusOn))
    }
    // Collapse to one column showing this folder (a no-op when we're the only one), then hand over.
    fun pick(f: File) {
        if (depth > 1) {
            repeat(depth) { xmb.pop() }
            val single = XmbOpen()
            xmb.push(xmbBrowserColumn(b, dir, 1, f, single))
            b.picked = single
        } else {
            b.picked = me
        }
        b.onPick(f)
    }

    val menu = XmbMenu(
        title = b.label(dir),
        icon = Icons.Filled.Folder,
        initialKey = KEY_LOADING,
        onClose = { me.isOpen = false; job?.cancel() },
    ) {
        buildList<XmbRow> {
            add(XmbRow.Header("title", b.title))
            b.roots.forEachIndexed { i, (name, root) ->
                if (!XmbTools.samePath(root, dir)) {
                    add(XmbRow.Action(key = "root$i", label = name, icon = xmbBrowserRootIcon(root)) { replaceWith(root, null) })
                }
            }
            if (parent != null) {
                add(XmbRow.Action(key = "up", label = "Up one folder", icon = Icons.Filled.ArrowUpward, value = b.label(parent)) {
                    replaceWith(parent, dir)
                })
            }
            if (b.pickDir) {
                add(XmbRow.Action(key = "pick", label = "Select this folder", icon = Icons.Filled.Check, subtitle = dir.absolutePath) { pick(dir) })
            }
            val rows = content
            if (rows == null) add(XmbRow.Info(KEY_LOADING, "Loading…", Icons.Filled.HourglassEmpty)) else addAll(rows)
        }
    }

    job = xmb.scope.launch {
        // List, sort and build the rows off the main thread; the rows lambda then just returns them.
        val (rows, firstKey, focusKey) = withContext(Dispatchers.IO) {
            val listed = dir.listFiles()?.mapNotNull { f ->
                if (!b.showHidden && f.name.startsWith(".")) return@mapNotNull null
                val isDir = f.isDirectory
                val ok = if (b.pickDir) isDir else isDir || runCatching { b.accept(f) }.getOrDefault(false)
                if (ok) f to isDir else null
            }?.sortedWith(compareBy<Pair<File, Boolean>>({ !it.second }, { it.first.name.lowercase() }))
            val out = ArrayList<XmbRow>()
            when {
                listed == null -> out.add(XmbRow.Info("unreadable", "Can't read this folder", Icons.Filled.Warning))
                listed.isEmpty() -> out.add(XmbRow.Info("empty", if (b.pickDir) "No folders here" else "Nothing to pick here", Icons.Filled.Info))
                else -> listed.forEach { (f, isDir) ->
                    out.add(
                        if (isDir) {
                            XmbRow.Link(key = xmbBrowserKey(f, true), label = f.name, icon = Icons.Filled.Folder) {
                                xmbBrowserColumn(b, f, depth + 1, null)
                            }
                        } else {
                            val ext = f.extension.lowercase()
                            XmbRow.Action(
                                key = xmbBrowserKey(f, false), label = f.name,
                                icon = if (ext in XMB_IMAGE_EXTS) Icons.Filled.Image else Icons.Filled.InsertDriveFile,
                                value = XmbTools.size(ctx, f.length()),
                                subtitle = DateUtils.formatDateTime(
                                    ctx, f.lastModified(),
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                                ),
                            ) { pick(f) }
                        }
                    )
                }
            }
            val first = listed?.firstOrNull()?.let { xmbBrowserKey(it.first, it.second) }
            val focused = focus?.let { fo -> listed?.firstOrNull { it.first.name == fo.name } }?.let { xmbBrowserKey(it.first, it.second) }
            Triple(out, first, focused)
        }
        content = rows
        // Land on the focus file, "Select this folder", or the first entry — unless the user moved.
        if (menu.selKey == KEY_LOADING) {
            menu.selKey = focusKey ?: (if (b.pickDir) "pick" else firstKey)
        }
    }
    return menu
}

// ── Change executable ────────────────────────────────────────────────────────────────────────────

/**
 * "Change executable…": the XMB twin of ChangeExecutableCoordinator. Browses from the current exe's
 * folder (Drive C and storage volumes as jumps) for a .exe/.lnk/.desktop, asks keep/clear when the
 * shortcut has launch arguments, then rewrites Exec through the shared CopyGameToDriveC.setShortcutExe.
 */
internal fun xmbChangeExeMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val info = CopyGameToDriveC.parse(shortcut)
    val driveC = XmbTools.driveC(shortcut.container)
    val start = info.exeAndroid?.parentFile?.takeIf { it.isDirectory } ?: driveC ?: File(XmbBrowse.INTERNAL_ROOT)

    // Extensions accepted as a launch target (matches the InAppFilePicker.SHORTCUT filter).
    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("exe", "lnk", "desktop")

    // argsSuffix "" clears the launch args; a leading-space suffix keeps them.
    fun applyExe(exe: File, argsSuffix: String) {
        val win = CopyGameToDriveC.setShortcutExe(shortcut, exe, argsSuffix)
        if (win == null) {
            xmb.toast(
                "That file isn't on a drive this container can reach. Keep the game on storage the " +
                    "container maps (internal/SD, or its C: drive)."
            )
        } else {
            xmb.toast("\"${shortcut.name}\" now launches $win")
            // Like the original (the editor closed): the open columns hold the old exe path.
            xmb.popToRoot()
        }
        xmb.reloadGames()
    }

    // Keep-or-clear the launch arguments, over the browser. Backing out returns to the browser
    // (pick another file); nothing is written until Keep or Clear.
    fun argsMenu(exe: File, browser: XmbOpen?): XmbMenu {
        val me = XmbOpen()
        fun choose(argsSuffix: String) {
            me.close(xmb)
            browser?.close(xmb)
            applyExe(exe, argsSuffix)
        }
        return XmbMenu(title = "Launch arguments", icon = Icons.Filled.Tune, initialKey = "keep", onClose = { me.isOpen = false }) {
            listOf<XmbRow>(
                XmbRow.Action(key = "keep", label = "Keep arguments", icon = Icons.Filled.Check, subtitle = "Launch the new .exe with the same arguments") {
                    choose(info.argsSuffix)
                },
                XmbRow.Action(key = "clear", label = "Clear arguments", icon = Icons.Filled.Close, subtitle = "Launch the new .exe with no arguments") {
                    choose("")
                },
                XmbRow.Info("head", "This shortcut launches with arguments:", Icons.Filled.Info),
            ) + XmbTools.para("args", info.argsSuffix.trim(), XmbTools.blankIcon, ctx)
        }
    }

    lateinit var browse: XmbBrowse
    browse = XmbBrowse(
        xmb, "Select the game's .exe", XmbTools.storageRoots(ctx, driveC),
        accept = { it.isFile && isSupported(it.name) },
        pickDir = false,
        onPick = { file ->
            val browser = browse.picked
            if (!file.isFile || !isSupported(file.name)) {
                xmb.toast("Pick a .exe, .lnk, or .desktop file.")
            } else if (info.argsSuffix.isBlank()) {
                browser?.close(xmb)
                applyExe(file, "")
            } else {
                // Ask about launch args only when there are some to keep.
                xmb.push(argsMenu(file, browser))
            }
        },
    )
    return xmbBrowserColumn(browse, start, depth = 1, focus = info.exeAndroid)
}

// ── Copy to Drive C ──────────────────────────────────────────────────────────────────────────────

/**
 * "Copy to Drive C…": the XMB twin of CopyToDriveCCoordinator (CONFIRM → OVERWRITE → COPYING →
 * DELETE_ORIGINAL), on the same CopyGameToDriveC helpers. Backing out of the progress column cancels
 * the copy like the dialog's Cancel; the shortcut is repointed only after a complete copy.
 */
internal fun xmbCopyToDriveCMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val name = shortcut.name
    val info = CopyGameToDriveC.parse(shortcut)
    val exe = info.exeAndroid
    if (info.onDriveC || exe == null) {
        // The dialog's short-circuit: toast and stop. The column repeats it so it isn't empty.
        val msg = if (info.onDriveC) "\"$name\" already runs from Drive C." else "Couldn't locate this game's files on disk."
        xmb.toast(msg)
        return XmbMenu(title = "Copy to Drive C", icon = Icons.Filled.DriveFileMove) {
            XmbTools.para("msg", msg, Icons.Filled.Info, ctx)
        }
    }
    val container = shortcut.container
    var sourceRoot by mutableStateOf(CopyGameToDriveC.defaultSourceRoot(exe))
    var sourceSize by mutableStateOf<Long?>(null)   // null = still sizing
    var validationError by mutableStateOf<String?>(null)
    var copying by mutableStateOf(false)
    var copied by mutableStateOf(false)
    var sizeJob: Job? = null
    val main = XmbOpen()
    val notAncestor = "That folder doesn't contain this game's .exe. Pick the folder the game runs from."

    fun fmt(bytes: Long) = XmbTools.size(ctx, bytes)

    // Size the source folder (background) and re-validate ancestry whenever the root changes.
    fun measure() {
        val root = sourceRoot
        validationError = if (!CopyGameToDriveC.isAncestor(root, exe)) notAncestor else null
        sourceSize = null
        sizeJob?.cancel()
        sizeJob = xmb.scope.launch {
            val size = withContext(Dispatchers.IO) { CopyGameToDriveC.folderSize(root) { !isActive } }
            if (root == sourceRoot) sourceSize = size
        }
    }

    // Last step: delete the original folder (Move) or keep it (Copy). [overMain] = pushed straight
    // over the Copy column, which then closes with it.
    fun deleteOriginalMenu(src: File, overMain: Boolean): XmbMenu {
        val me = XmbOpen()
        var decided = false
        var deleting by mutableStateOf(false)
        fun finish() {
            me.close(xmb)
            if (overMain) main.close(xmb)
            // The game now lives on Drive C: every open column still points at the old path.
            xmb.popToRoot()
            xmb.reloadGames()
        }
        return XmbMenu(
            title = "Copied to Drive C",
            icon = Icons.Filled.CheckCircle,
            initialKey = "keep",
            onClose = {
                me.isOpen = false
                if (!decided) {
                    // Backing out = the dialog's dismiss: the original stays and the flow is over.
                    decided = true
                    xmb.toast("\"$name\" now runs from Drive C.")
                    xmb.scope.launch {
                        if (overMain) main.close(xmb)
                        xmb.reloadGames()
                    }
                }
            },
        ) {
            if (deleting) {
                listOf(XmbRow.Info("deleting", "Deleting the original folder…", Icons.Filled.HourglassEmpty))
            } else {
                listOf<XmbRow>(
                    XmbRow.Action(key = "keep", label = "Keep original", icon = Icons.Filled.Check) {
                        if (!decided) {
                            decided = true
                            xmb.toast("Copied to Drive C — original kept.")
                            finish()
                        }
                    },
                    XmbRow.Action(key = "delete", label = "Delete original", icon = Icons.Filled.Delete, danger = true) {
                        if (!decided) {
                            decided = true
                            deleting = true
                            xmb.scope.launch {
                                withContext(Dispatchers.IO) { runCatching { src.deleteRecursively() } }
                                xmb.toast("Moved to Drive C — original deleted.")
                                finish()
                            }
                        }
                    },
                ) + XmbTools.para(
                    "msg",
                    "\"$name\" now runs from Drive C. Delete the original folder to free " +
                        "${sourceSize?.let { fmt(it) } ?: "space"}, or keep it as a backup?",
                    Icons.Filled.Info, ctx,
                )
            }
        }
    }

    // Background copy of the chosen root into [dest]; [cleanFirst] wipes an existing dest (overwrite).
    fun startCopy(dest: File, cleanFirst: Boolean) {
        if (copying) return
        copying = true
        val cancel = AtomicBoolean(false)
        val total = sourceSize ?: 0L
        val src = sourceRoot
        var progress by mutableStateOf(CopyGameToDriveC.Progress(0, total, ""))
        val prog = XmbOpen()
        xmb.push(
            XmbMenu(
                title = "Copying",
                icon = Icons.Filled.DriveFileMove,
                initialKey = "cancel",
                // Backing out cancels, like the dialog's Cancel: the partial copy is deleted.
                onClose = { prog.isOpen = false; cancel.set(true) },
            ) {
                val p = progress
                buildList<XmbRow> {
                    add(XmbRow.Action(key = "cancel", label = "Cancel", icon = Icons.Filled.Close) { cancel.set(true) })
                    val pct = if (p.totalBytes > 0L) "${(p.copiedBytes * 100L / p.totalBytes).coerceIn(0L, 100L)}%" else ""
                    add(XmbRow.Info("progress", "Copying to Drive C…", Icons.Filled.DriveFileMove, value = pct))
                    if (p.totalBytes > 0L) add(XmbRow.Info("bytes", "${fmt(p.copiedBytes)} / ${fmt(p.totalBytes)}", Icons.Filled.Storage))
                    if (p.currentFile.isNotEmpty()) add(XmbRow.Info("file", p.currentFile, Icons.Filled.InsertDriveFile))
                }
            }
        )
        xmb.scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (cleanFirst) dest.deleteRecursively()
                    CopyGameToDriveC.copyTree(src, dest, total, { cancel.get() }) { p -> progress = p }
                    CopyGameToDriveC.repoint(shortcut, src, dest)
                        ?: throw IOException("Couldn't repoint the shortcut to the copied files.")
                }
            }
            copying = false
            val onTop = prog.isOpen
            outcome.fold(
                onSuccess = {
                    copied = true
                    prog.close(xmb)
                    xmb.push(deleteOriginalMenu(src, overMain = onTop))
                },
                onFailure = { e ->
                    if (e is CopyGameToDriveC.CancelledException) {
                        xmb.toast("Copy cancelled — nothing was changed.")
                    } else {
                        Log.e("CopyToDriveC", "copy/repoint failed for $name", e)
                        xmb.toast("Copy failed: ${e.message ?: "unknown error"}. Nothing was changed.")
                    }
                    // The dialog ended the whole flow here; do the same unless the user already backed out.
                    if (onTop) {
                        prog.close(xmb)
                        main.close(xmb)
                    }
                },
            )
        }
    }

    // A folder of that name is already on C: — overwrite it or copy next to it.
    fun overwriteMenu(): XmbMenu {
        val me = XmbOpen()
        return XmbMenu(title = "Already on Drive C", icon = Icons.Filled.Warning, initialKey = "both", onClose = { me.isOpen = false }) {
            listOf<XmbRow>(
                XmbRow.Action(key = "both", label = "Keep both (new folder)", icon = Icons.Filled.ContentCopy) {
                    me.close(xmb)
                    startCopy(CopyGameToDriveC.autoRenamedDest(CopyGameToDriveC.destRootFor(container, sourceRoot.name)), cleanFirst = false)
                },
                XmbRow.Action(key = "overwrite", label = "Overwrite", icon = Icons.Filled.Delete, danger = true) {
                    me.close(xmb)
                    startCopy(CopyGameToDriveC.destRootFor(container, sourceRoot.name), cleanFirst = true)
                },
            ) + XmbTools.para(
                "msg",
                "A folder named \"${sourceRoot.name}\" already exists under C:\\${CopyGameToDriveC.GAMES_SUBDIR}. " +
                    "Overwrite it, or keep both by copying to a new folder?",
                Icons.Filled.Info, ctx,
            )
        }
    }

    // Validate ancestry + free space, resolve a destination collision, then start.
    fun confirmAndCopy() {
        if (!CopyGameToDriveC.isAncestor(sourceRoot, exe)) {
            validationError = notAncestor
            return
        }
        val size = sourceSize ?: return
        if (!CopyGameToDriveC.hasRoomFor(ctx, size)) {
            validationError = "Not enough space on Drive C. Needs ${fmt(size)} plus headroom, but only " +
                "${fmt(CopyGameToDriveC.freeBytes(ctx))} is free."
            return
        }
        val dest = CopyGameToDriveC.destRootFor(container, sourceRoot.name)
        if (dest.exists()) xmb.push(overwriteMenu()) else startCopy(dest, cleanFirst = false)
    }

    measure()
    return XmbMenu(
        title = "Copy to Drive C",
        icon = Icons.Filled.DriveFileMove,
        initialKey = "copy",
        onClose = { main.isOpen = false; sizeJob?.cancel() },
    ) {
        if (copied) {
            XmbTools.para("done", "\"$name\" now runs from Drive C.", Icons.Filled.CheckCircle, ctx)
        } else {
            val err = validationError
            val size = sourceSize
            val blocked = when {
                copying -> "Copying…"
                err != null -> err
                size == null -> "Calculating size…"
                else -> null
            }
            buildList<XmbRow> {
                add(
                    XmbRow.Action(
                        key = "copy", label = "Copy", icon = Icons.Filled.DriveFileMove,
                        subtitle = "To C:\\${CopyGameToDriveC.GAMES_SUBDIR}\\${sourceRoot.name}",
                        disabledReason = blocked,
                    ) { confirmAndCopy() }
                )
                add(XmbRow.Info("folder", "Folder to copy", Icons.Filled.Folder, value = sourceRoot.name, subtitle = sourceRoot.absolutePath))
                add(
                    XmbRow.Link(
                        key = "change", label = "Change folder…", icon = Icons.Filled.FolderOpen,
                        disabledReason = if (copying) "Copying…" else null,
                    ) {
                        val roots = XmbTools.storageRoots(ctx, XmbTools.driveC(container))
                        xmbBrowserColumn(
                            XmbBrowse(
                                xmb, "Select the game's folder", roots,
                                accept = { false },
                                pickDir = true,
                                onPick = { dir ->
                                    xmb.pop()   // the browser's one remaining column
                                    sourceRoot = dir
                                    measure()
                                },
                            ),
                            sourceRoot, depth = 1, focus = null,
                        )
                    }
                )
                add(
                    XmbRow.Info(
                        "size",
                        when {
                            err != null -> "Size —"
                            size == null -> "Calculating size…"
                            else -> "Size: ${fmt(size)}"
                        },
                        Icons.Filled.Storage,
                    )
                )
                if (err != null) addAll(XmbTools.para("error", err, Icons.Filled.Warning, ctx))
                addAll(
                    XmbTools.para(
                        "about",
                        "Copies the game onto this container's C: drive (native app storage) and points " +
                            "the shortcut there. Fixes games that stall streaming from shared storage.",
                        Icons.Filled.Info, ctx,
                    )
                )
                addAll(XmbTools.para("hint", "Pick the folder that holds everything the game needs, not just the .exe.", Icons.Filled.Info, ctx))
            }
        }
    }
}

// ── Scrape cover ─────────────────────────────────────────────────────────────────────────────────

/**
 * "Scrape cover": SteamGridDB grids for the game's name (StarLaunchBridge.sgdbFetchGridsJson), one
 * row per cover with its thumbnail. Picking one downloads the full image and saves it exactly as the
 * dialog did — custom cover art plus the 64px icon PNG — then reloads the games bar.
 */
internal fun xmbScrapeCoverMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val covers = mutableStateListOf<Pair<ImageBitmap, String>>()   // thumbnail, full-size URL
    var searching by mutableStateOf(true)
    var saving by mutableStateOf(false)
    val me = XmbOpen()

    // Thumbnails appear as they arrive rather than all at the end.
    val job = xmb.scope.launch {
        val json = withContext(Dispatchers.IO) { StarLaunchBridge.sgdbFetchGridsJson(shortcut.name) }
        val arr = try { JSONArray(json) } catch (_: Exception) { null }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val thumbUrl = obj.optString("thumb", "")
                val fullUrl = obj.optString("url", "")
                if (thumbUrl.isEmpty() || fullUrl.isEmpty()) continue
                val bmp = withContext(Dispatchers.IO) { xmbDownloadBitmap(thumbUrl, 10000, 10000) }
                if (bmp != null && covers.none { it.second == fullUrl }) covers.add(bmp.asImageBitmap() to fullUrl)
            }
        }
        searching = false
    }

    fun useCover(url: String) {
        if (saving) return
        saving = true
        xmb.scope.launch {
            val full = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = xmbDownloadBitmap(url, 15000, 20000)
                    if (bmp != null) {
                        shortcut.saveCustomCoverArt(bmp)
                        val iconsDir = shortcut.container.getIconsDir(64)
                        if (iconsDir != null) {
                            if (!iconsDir.exists()) iconsDir.mkdirs()
                            val iconName = runCatching {
                                shortcut.file.readLines().firstOrNull { it.startsWith("Icon=") }?.substringAfter("Icon=")?.trim()
                            }.getOrNull() ?: shortcut.name
                            FileUtils.saveBitmapToFile(bmp, File(iconsDir, "$iconName.png"))
                        }
                    }
                    bmp
                }.getOrNull()
            }
            saving = false
            if (full != null) {
                shortcut.icon = full
                me.close(xmb)
                xmb.reloadGames()
                xmb.toast("Cover saved.")
            } else {
                xmb.toast("Couldn't download that cover.")
            }
        }
    }

    return XmbMenu(title = "Scrape cover", icon = Icons.Filled.Image, onClose = { me.isOpen = false; job.cancel() }) {
        buildList<XmbRow> {
            if (saving) add(XmbRow.Info("saving", "Saving cover…", Icons.Filled.HourglassEmpty))
            covers.forEachIndexed { i, (thumb, url) ->
                add(
                    XmbRow.Action(
                        key = "c:$url", label = "Cover ${i + 1}", icon = Icons.Filled.Image, thumbnail = thumb,
                        disabledReason = if (saving) "Saving cover…" else null,
                    ) { useCover(url) }
                )
            }
            if (searching) {
                add(XmbRow.Info("searching", "Searching SteamGridDB...", Icons.Filled.HourglassEmpty))
            } else if (covers.isEmpty()) {
                add(XmbRow.Info("none", "No covers found.", Icons.Filled.Info))
            }
        }
    }
}

/** GET [url] and decode it; null on any failure. Blocking. */
private fun xmbDownloadBitmap(url: String, connectTimeout: Int, readTimeout: Int): Bitmap? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = connectTimeout
    conn.readTimeout = readTimeout
    try {
        BitmapFactory.decodeStream(conn.inputStream)
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) {
    null
}

// ── Back up saves ────────────────────────────────────────────────────────────────────────────────

/**
 * "Back up saves" (custom games): pick the archive layout (Winlator vs GameHub, the dialog's two
 * options), then CustomSaveVault.manualBackup into the shared per-game folder. Same toasts.
 */
internal fun xmbBackupSavesMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val name = shortcut.name
    var running by mutableStateOf(false)
    val me = XmbOpen()

    fun backUp(layout: GameSaveBackup.BackupLayout) {
        if (running) return
        running = true
        xmb.toast("Backing up saves for \"$name\"…")
        CustomSaveVault.manualBackup(ctx, shortcut.container, shortcut, layout) { r ->
            running = false
            if (r.wholeContainer && r.ok) {
                xmb.toast("No per-game saves detected — backed up the whole container.")
            }
            xmb.toast(
                if (r.ok) "Backed up ${r.fileCount} files → ${r.path?.substringAfterLast('/')}"
                else "Backup failed: ${r.error ?: "unknown error"}"
            )
            me.close(xmb)
        }
    }

    return XmbMenu(title = "Back up saves", icon = Icons.Filled.Archive, onClose = { me.isOpen = false }) {
        if (running) {
            listOf(XmbRow.Info("busy", "Backing up saves for \"$name\"…", Icons.Filled.HourglassEmpty))
        } else {
            listOf<XmbRow>(
                XmbRow.Header("prompt", ctx.getString(R.string.save_backup_format_prompt)),
                XmbRow.Action(
                    key = "winlator", label = ctx.getString(R.string.save_backup_format_winlator), icon = Icons.Filled.Archive,
                    subtitle = ctx.getString(R.string.save_backup_format_winlator_sub),
                ) { backUp(GameSaveBackup.BackupLayout.WINLATOR) },
                XmbRow.Action(
                    key = "gamehub", label = ctx.getString(R.string.save_backup_format_gamehub), icon = Icons.Filled.Archive,
                    subtitle = ctx.getString(R.string.save_backup_format_gamehub_sub),
                ) { backUp(GameSaveBackup.BackupLayout.GAMEHUB) },
            )
        }
    }
}

// ── Restore saves ────────────────────────────────────────────────────────────────────────────────

/** CustomSaveVault's auto-on-exit snapshot name (private there). */
private const val XMB_AUTO_BACKUP = "auto-latest.zip"

/**
 * "Restore saves" (custom games): the game's backups from its per-game folder (newest first), or
 * browse for any save archive — the dialog's picker — then the target container, a confirm, and
 * GameSaveBackup.restore. Emulator-account conflicts are asked afterwards, one column each.
 */
internal fun xmbRestoreSavesMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val name = shortcut.name
    val dir = CustomSaveVault.perGameDir(name)
    val exts = InAppFilePicker.SAVE.toSet()
    var backups by mutableStateOf<List<File>?>(null)
    val me = XmbOpen()

    val job = xmb.scope.launch {
        backups = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }
    }

    fun chooseContainer(zip: File) = xmb.push(xmbRestoreContainerMenu(xmb, zip, me))

    fun browse(): XmbMenu {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val start = dir.takeIf { it.isDirectory } ?: dir.parentFile?.takeIf { it.isDirectory } ?: downloads
        return xmbFileBrowserMenu(
            xmb, "Select a save .zip", start, XmbTools.storageRoots(ctx, XmbTools.driveC(shortcut.container)),
            accept = { it.isFile && it.extension.lowercase() in exts },
            onPick = { f ->
                xmb.pop()   // the browser's one remaining column
                chooseContainer(f)
            },
        )
    }

    return XmbMenu(
        title = "Restore saves", icon = Icons.Filled.Unarchive, initialKey = KEY_LOADING,
        onClose = { me.isOpen = false; job.cancel() },
    ) {
        buildList<XmbRow> {
            val list = backups
            when {
                list == null -> add(XmbRow.Info(KEY_LOADING, "Looking for backups…", Icons.Filled.HourglassEmpty))
                list.isEmpty() -> addAll(XmbTools.para("none", "No backups for \"$name\" in Download/Bannerlator/game saves yet.", Icons.Filled.Info, ctx))
                else -> {
                    add(XmbRow.Header("saved", "Backups"))
                    list.forEach { f ->
                        add(
                            XmbRow.Action(
                                key = "b:${f.name}", label = xmbBackupLabel(ctx, f), icon = Icons.Filled.History,
                                value = XmbTools.size(ctx, f.length()), subtitle = f.name,
                            ) { chooseContainer(f) }
                        )
                    }
                }
            }
            add(XmbRow.Link(key = "browse", label = "Browse for a save file…", icon = Icons.Filled.FolderOpen) { browse() })
        }
    }
}

// "11 Sep 2026, 14:03" from the manual backup's <Game>_<epochMillis>.zip, a label for the auto
// snapshot, else the bare file name.
private fun xmbBackupLabel(context: Context, f: File): String {
    if (f.name == XMB_AUTO_BACKUP) return "Automatic backup (on exit)"
    val stamp = Regex("_(\\d{10,})$").find(f.nameWithoutExtension)?.groupValues?.getOrNull(1)?.toLongOrNull()
    return if (stamp != null) {
        DateUtils.formatDateTime(
            context, stamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )
    } else {
        f.nameWithoutExtension
    }
}

private fun xmbContainerLabel(c: Container): String = c.name?.takeIf { it.isNotEmpty() } ?: "Container ${c.id}"

/** The dialog's container picker as a column; [parent] (the Restore column) closes with it on success. */
private fun xmbRestoreContainerMenu(xmb: XmbScope, zip: File, parent: XmbOpen): XmbMenu {
    val ctx = xmb.context
    var containers by mutableStateOf<List<Container>?>(null)
    var restoringInto by mutableStateOf<String?>(null)
    val me = XmbOpen()

    val job = xmb.scope.launch {
        containers = withContext(Dispatchers.IO) {
            runCatching { ContainerManager(ctx).containers.filter { it.configFile.isFile } }.getOrDefault(emptyList())
        }
    }

    fun restore(c: Container) {
        if (restoringInto != null) return
        val cname = xmbContainerLabel(c)
        restoringInto = cname
        xmb.toast("Restoring saves into \"$cname\"…")
        GameSaveBackup.restore(ctx, Uri.fromFile(zip), c) { r ->
            restoringInto = null
            xmb.toast(
                if (r.ok) "Restored ${r.filesWritten} files to \"$cname\""
                else "Restore failed: ${r.error ?: "unknown error"}"
            )
            if (me.isOpen) {
                me.close(xmb)
                parent.close(xmb)
            }
            if (r.emuConflicts.isNotEmpty()) xmbEmuConflicts(xmb, r.emuConflicts)
        }
    }

    return XmbMenu(
        title = "Restore to container", icon = Icons.Filled.Widgets, initialKey = KEY_LOADING,
        onClose = { me.isOpen = false; job.cancel() },
    ) {
        val list = containers
        val busy = restoringInto
        when {
            busy != null -> listOf(XmbRow.Info("busy", "Restoring saves into \"$busy\"…", Icons.Filled.HourglassEmpty))
            list == null -> listOf(XmbRow.Info(KEY_LOADING, "Loading…", Icons.Filled.HourglassEmpty))
            list.isEmpty() -> XmbTools.para("none", "No containers yet — create one in the Containers screen first.", Icons.Filled.Info, ctx)
            else -> buildList<XmbRow> {
                add(XmbRow.Info("from", zip.name, Icons.Filled.Unarchive, subtitle = zip.parent))
                list.forEach { c ->
                    val cname = xmbContainerLabel(c)
                    val sub = listOf(c.wineVersion.orEmpty(), c.screenSize.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
                    add(
                        XmbRow.Action(key = "c:${c.id}", label = cname, icon = Icons.Filled.Widgets, subtitle = sub.ifEmpty { null }) {
                            xmb.confirm(
                                XmbConfirm(
                                    title = "Restore saves",
                                    message = "Restore ${zip.name} into \"$cname\"? Save files already there with the same names are replaced.",
                                    okLabel = "Restore",
                                )
                            ) { restore(c) }
                        }
                    )
                }
            }
        }
    }
}

/**
 * EmuAccountConflictDialog as columns, one per conflict: use the backup's emulator account or keep
 * the container's. Backing out keeps the current one (the dialog's dismiss), then the next is asked.
 */
private fun xmbEmuConflicts(xmb: XmbScope, conflicts: List<GameSaveBackup.EmuIdConflict>) {
    val ctx = xmb.context
    var applied = 0

    fun show(i: Int) {
        if (i >= conflicts.size) {
            if (applied > 0) xmb.toast("Emulator account switched to the backup's — relaunch the game")
            return
        }
        val c = conflicts[i]
        val me = XmbOpen()
        var decided = false
        fun decide(useBackup: Boolean) {
            if (decided) return
            decided = true
            xmb.scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    if (useBackup) {
                        GameSaveBackup.applyEmuIdentity(c)
                    } else {
                        GameSaveBackup.discardEmuIdentity(c)
                        false
                    }
                }
                if (ok) applied++
                me.close(xmb)
                show(i + 1)
            }
        }
        fun account(labelRes: Int, id: String, folder: String?): String {
            val label = ctx.getString(labelRes)
            return if (folder != null) ctx.getString(R.string.emu_id_conflict_account_with_folder, label, id, folder)
            else ctx.getString(R.string.emu_id_conflict_account, label, id)
        }
        xmb.push(
            XmbMenu(
                title = "Emulator account", icon = Icons.Filled.AccountCircle, initialKey = "keep",
                onClose = { me.isOpen = false; decide(false) },
            ) {
                listOf<XmbRow>(
                    XmbRow.Action(key = "keep", label = ctx.getString(R.string.emu_id_conflict_keep), icon = Icons.Filled.Check) { decide(false) },
                    XmbRow.Action(key = "apply", label = ctx.getString(R.string.emu_id_conflict_apply), icon = Icons.Filled.AccountCircle) { decide(true) },
                ) +
                    XmbTools.para("title", ctx.getString(R.string.emu_id_conflict_title, c.label), Icons.Filled.Warning, ctx) +
                    XmbTools.para("body", ctx.getString(R.string.emu_id_conflict_body), XmbTools.blankIcon, ctx) +
                    XmbTools.para("backup", account(R.string.emu_id_conflict_from_backup, c.backupId, c.backupSaveFolder), Icons.Filled.Unarchive, ctx) +
                    XmbTools.para("current", account(R.string.emu_id_conflict_in_container, c.currentId, c.currentSaveFolder), Icons.Filled.Widgets, ctx) +
                    XmbTools.para("warn", ctx.getString(R.string.emu_id_conflict_warning), Icons.Filled.Info, ctx)
            }
        )
    }
    show(0)
}
