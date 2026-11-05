package com.winlator.star.ui.files

import android.content.Context
import android.os.StatFs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.winlator.star.container.Container
import com.winlator.star.core.unpack.ArchiveBrowse
import com.winlator.star.core.unpack.ArchiveEntry
import java.io.File
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── What a pane shows ──

/** A place a pane can show: the Home tiles, a real folder, or a folder inside an archive. */
sealed class FmLoc {
    object Home : FmLoc()
    data class Dir(val dir: File) : FmLoc()
    /** [inner] = "" for the archive's root; "a/b" for a folder inside it. */
    data class InArchive(val archive: File, val inner: String) : FmLoc()
}

/** One row of a listing: a real file or folder, or an entry inside an archive. */
sealed class FmItem {
    abstract val name: String
    abstract val isDir: Boolean
    abstract val size: Long
    abstract val modified: Long
    abstract val key: String

    class Disk(val file: File, override val isDir: Boolean, override val size: Long, override val modified: Long) : FmItem() {
        override val name: String get() = file.name
        override val key: String get() = file.absolutePath
    }

    class Entry(val archive: File, val entry: ArchiveEntry, val totalSize: Long) : FmItem() {
        override val name: String get() = entry.name
        override val isDir: Boolean get() = entry.isDir
        override val size: Long get() = totalSize
        override val modified: Long get() = entry.modified
        override val key: String get() = archive.absolutePath + "!/" + entry.path
    }

    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

internal fun diskItem(f: File): FmItem.Disk {
    val dir = f.isDirectory
    return FmItem.Disk(f, dir, if (dir) 0L else f.length(), f.lastModified())
}

// ── Sorting ──

enum class FmSortKey { NAME, DATE, SIZE, TYPE }
enum class FmViewMode { LIST, COMPACT, GRID }

/** Folders and files are sorted separately; folders always come first. */
data class FmSort(val folders: FmSortKey, val foldersDesc: Boolean, val files: FmSortKey, val filesDesc: Boolean)

private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

/** "file2" before "file10": digit runs compare by value, the rest by the locale's collator, case-insensitive. */
internal val naturalOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return@Comparator na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return@Comparator c
            i = ei
            j = ej
        } else {
            var ei = i
            while (ei < a.length && !a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && !b[ej].isDigit()) ej++
            val c = collator.compare(a.substring(i, ei), b.substring(j, ej))
            if (c != 0) return@Comparator c
            i = ei
            j = ej
        }
    }
    (a.length - i) - (b.length - j)
}

private fun keyComparator(key: FmSortKey): Comparator<FmItem> = when (key) {
    FmSortKey.NAME -> Comparator { x, y -> naturalOrder.compare(x.name, y.name) }
    FmSortKey.DATE -> compareBy<FmItem> { it.modified }.thenComparator { x, y -> naturalOrder.compare(x.name, y.name) }
    FmSortKey.SIZE -> compareBy<FmItem> { it.size }.thenComparator { x, y -> naturalOrder.compare(x.name, y.name) }
    FmSortKey.TYPE -> compareBy<FmItem> { it.extension }.thenComparator { x, y -> naturalOrder.compare(x.name, y.name) }
}

internal fun List<FmItem>.sortedFor(sort: FmSort): List<FmItem> {
    val dirs = filter { it.isDir }.sortedWith(keyComparator(sort.folders).let { if (sort.foldersDesc) it.reversed() else it })
    val files = filter { !it.isDir }.sortedWith(keyComparator(sort.files).let { if (sort.filesDesc) it.reversed() else it })
    return dirs + files
}

internal fun FmSortKey.label(): String = when (this) {
    FmSortKey.NAME -> "Name"
    FmSortKey.DATE -> "Date"
    FmSortKey.SIZE -> "Size"
    FmSortKey.TYPE -> "Type"
}

/** Browse settings kept between visits (the old file manager's keys where they still mean the same). */
internal object FmPrefs {
    private fun p(ctx: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)

    fun sort(ctx: Context): FmSort {
        val p = p(ctx)
        fun key(name: String?, def: FmSortKey) = runCatching { FmSortKey.valueOf(name ?: "") }.getOrDefault(def)
        return FmSort(
            folders = key(p.getString("fmFolderSort", null), FmSortKey.NAME),
            foldersDesc = p.getBoolean("fmFolderSortDesc", false),
            files = key(p.getString("fmFileSort", null), FmSortKey.NAME),
            filesDesc = p.getBoolean("fmFileSortDesc", false),
        )
    }

    fun setSort(ctx: Context, s: FmSort) = p(ctx).edit()
        .putString("fmFolderSort", s.folders.name).putBoolean("fmFolderSortDesc", s.foldersDesc)
        .putString("fmFileSort", s.files.name).putBoolean("fmFileSortDesc", s.filesDesc).apply()

    fun viewMode(ctx: Context): FmViewMode {
        val p = p(ctx)
        p.getString("fmViewMode", null)?.let { v -> runCatching { return FmViewMode.valueOf(v) } }
        // The old file manager's grid/list switch and compact rows.
        return when {
            p.getBoolean("fmGridView", false) -> FmViewMode.GRID
            p.getBoolean("fmCompactRows", false) -> FmViewMode.COMPACT
            else -> FmViewMode.LIST
        }
    }

    fun setViewMode(ctx: Context, m: FmViewMode) = p(ctx).edit().putString("fmViewMode", m.name).apply()

    fun showHidden(ctx: Context): Boolean = p(ctx).getBoolean("fmShowHidden", true)
    fun setShowHidden(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fmShowHidden", v).apply()

    fun dualPane(ctx: Context): Boolean = p(ctx).getBoolean("fmDualPane", false)
    fun setDualPane(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fmDualPane", v).apply()

    fun lastPickerDir(ctx: Context): File? =
        p(ctx).getString("lastFilePickerDir", null)?.let(::File)?.takeIf { it.isDirectory }

    fun setLastPickerDir(ctx: Context, dir: File) = p(ctx).edit().putString("lastFilePickerDir", dir.absolutePath).apply()
}

// ── File kinds ──

internal val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "ico")
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "wmv", "flv", "3gp", "ts")
private val AUDIO_EXTS = setOf("mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "wma", "mid", "midi", "sf2")
private val RUN_EXTS = setOf("exe", "bat", "msi", "sh")
private val CODE_EXTS = setOf("sh", "bat", "cmd", "ps1", "py", "lua", "js", "json", "xml", "yml", "yaml", "reg")
private val CONFIG_EXTS = setOf("ini", "cfg", "conf", "config", "inf", "properties", "toml", "vdf", "acf", "desktop")

internal fun isRunnable(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in RUN_EXTS
internal fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS

internal fun iconFor(item: FmItem, isArchive: Boolean): ImageVector {
    if (item.isDir) return Icons.Filled.Folder
    val ext = item.extension
    return when {
        isArchive -> Icons.Filled.FolderZip
        ext == "exe" || ext == "msi" -> Icons.Filled.SportsEsports
        ext == "apk" || ext == "xapk" || ext == "apks" -> Icons.Filled.Android
        ext in IMAGE_EXTS -> Icons.Filled.Image
        ext in VIDEO_EXTS -> Icons.Filled.Movie
        ext in AUDIO_EXTS -> Icons.Filled.AudioFile
        ext == "sh" || ext == "bat" || ext == "cmd" || ext == "ps1" -> Icons.Filled.Terminal
        ext in CONFIG_EXTS -> Icons.Filled.Settings
        ext in CODE_EXTS -> Icons.Filled.Code
        ext == "log" || ext == "txt" || ext == "md" || ext == "nfo" -> Icons.Filled.TextSnippet
        ext == "pdf" || ext == "doc" || ext == "docx" -> Icons.Filled.Description
        ext == "wcp" || ext == "tzst" || ext == "zst" -> Icons.Filled.Archive
        else -> Icons.Filled.InsertDriveFile
    }
}

/** A colour per kind so a listing reads at a glance; folders take the theme colour (null). */
internal fun tintFor(item: FmItem, isArchive: Boolean): Color? {
    if (item.isDir) return null
    val ext = item.extension
    return when {
        isArchive -> Color(0xFFE0A030)
        ext == "exe" || ext == "msi" -> Color(0xFF4FA3E0)
        ext in IMAGE_EXTS -> Color(0xFF3DB37A)
        ext in VIDEO_EXTS -> Color(0xFFE05A5A)
        ext in AUDIO_EXTS -> Color(0xFFB070E0)
        ext in CONFIG_EXTS || ext in CODE_EXTS -> Color(0xFF8AA0B8)
        else -> Color(0xFF9AA0A6)
    }
}

internal fun isBrowsableArchive(file: File): Boolean = !file.isDirectory && runCatching { ArchiveBrowse.canBrowse(file) }.getOrDefault(false)

// ── Formatting ──

private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

internal fun fmDate(ms: Long): String = if (ms <= 0) "" else dateFmt.get()!!.format(Date(ms))
internal fun fmTime(ms: Long): String = if (ms <= 0) "" else timeFmt.get()!!.format(Date(ms))
internal fun fmDateTime(ms: Long): String = if (ms <= 0) "—" else "${fmDate(ms)} ${fmTime(ms)}"

internal fun itemCountLabel(n: Int): String = if (n == 1) "1 item" else "$n items"

/** Shortens a path from the left, keeping whole segments (the deepest part says where you are). */
internal fun elidePathStart(path: String, max: Int): String {
    if (path.length <= max) return path
    val parts = path.split('/').filter { it.isNotEmpty() }
    val out = StringBuilder()
    for (part in parts.asReversed()) {
        if (out.length + part.length + 1 > max - 2) break
        out.insert(0, "/$part")
    }
    return if (out.isEmpty()) "…" + path.takeLast(max - 1) else "…$out"
}

/** True when [child] is [ancestor] itself or lives anywhere inside it. */
internal fun isWithin(child: File, ancestor: File): Boolean {
    val c = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
    val a = runCatching { ancestor.canonicalPath }.getOrDefault(ancestor.absolutePath)
    return c == a || c.startsWith(a + File.separator)
}

/** foo.txt -> "foo (1).txt" when foo.txt exists in [dir]. */
internal fun uniqueDestination(dir: File, name: String): File {
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

/** Used / total bytes of the volume holding [dir], or null. */
internal fun volumeUsage(dir: File): Pair<Long, Long>? = runCatching {
    val st = StatFs(dir.absolutePath)
    val total = st.totalBytes
    if (total <= 0) null else (total - st.availableBytes) to total
}.getOrNull()

/** Size and file count of everything under [dir], stopping early when [cancelled]. */
internal fun treeSize(dir: File, cancelled: () -> Boolean = { false }): Pair<Long, Int> {
    var bytes = 0L
    var files = 0
    val stack = ArrayDeque<File>().apply { add(dir) }
    var n = 0
    while (stack.isNotEmpty()) {
        if (++n and 255 == 0 && cancelled()) break
        val d = stack.removeLast()
        val kids = d.listFiles() ?: continue
        for (k in kids) {
            // Never follow a link out of the tree (container dosdevices point at /).
            val link = runCatching { java.nio.file.Files.isSymbolicLink(k.toPath()) }.getOrDefault(false)
            if (link) continue
            if (k.isDirectory) stack.add(k) else { bytes += k.length(); files++ }
        }
    }
    return bytes to files
}

// ── Where a path lives (drive labels for containers, favourites) ──

enum class FavStorage { INTERNAL, SD, CONTAINER, OTHER }

data class FavLocation(
    val storage: FavStorage,
    val containerName: String?,
    val driveLabel: String,
    val displayPath: String,
)

/** Resolves where [file] lives (storage, drive and a Windows-style path inside a container). */
fun describeLocation(file: File, containers: List<Container>, imagefsDir: File): FavLocation {
    val abs = file.absolutePath
    for (container in containers) {
        val driveC = File(container.rootDir, ".wine/drive_c").absolutePath
        if (abs == driveC || abs.startsWith("$driveC/")) {
            val remainder = abs.removePrefix(driveC).replace('/', '\\')
            return FavLocation(FavStorage.CONTAINER, container.name, "Drive C:", "C:$remainder")
        }
    }
    val imagefs = imagefsDir.absolutePath
    if (abs == imagefs || abs.startsWith("$imagefs/")) {
        val remainder = abs.removePrefix(imagefs).replace('/', '\\')
        return FavLocation(FavStorage.CONTAINER, null, "Drive Z:", "Z:$remainder")
    }
    val internal = "/storage/emulated/0"
    if (abs == internal || abs.startsWith("$internal/")) return FavLocation(FavStorage.INTERNAL, null, "Internal", abs)
    if (abs.startsWith("/storage/")) {
        val name = abs.removePrefix("/storage/").substringBefore('/')
        if (name.isNotEmpty() && name != "emulated" && name != "self") return FavLocation(FavStorage.SD, null, "SD card", abs)
    }
    return FavLocation(FavStorage.OTHER, null, "Storage", abs)
}

/** Badge colours per storage (identity colours, not the theme). */
internal fun badgeColors(loc: FavLocation): Pair<Color, Color> {
    val white = Color(0xFFFFFFFF)
    return when {
        loc.storage == FavStorage.INTERNAL -> Color(0xFF2E5FB0) to white
        loc.storage == FavStorage.SD -> Color(0xFF2E7D32) to white
        loc.driveLabel == "Drive Z:" -> Color(0xFF6A3FB0) to white
        loc.storage == FavStorage.CONTAINER -> Color(0xFF8F6A00) to white
        else -> Color(0xFF555555) to white
    }
}

/** The volume root a folder belongs to (internal storage, an SD card), or null for app/other paths. */
internal fun volumeRootOf(dir: File): File? {
    val abs = dir.absolutePath
    val internal = "/storage/emulated/0"
    return when {
        abs == internal || abs.startsWith("$internal/") -> File(internal)
        abs.startsWith("/storage/") -> {
            val name = abs.removePrefix("/storage/").substringBefore('/')
            if (name.isNotEmpty() && name != "emulated" && name != "self") File("/storage/$name") else null
        }
        else -> null
    }
}

// ── DOS file attributes (Read-only / Hidden), as the in-container file manager shows them ──

private const val FILE_ATTRIBUTE_HIDDEN = 0x2
private const val DOSATTRIB_XATTR = "user.DOSATTRIB"

internal data class FileAttrState(val readOnly: Boolean, val hidden: Boolean, val hiddenSupported: Boolean)

private fun parseDosAttrib(raw: ByteArray): Int {
    val s = String(raw, Charsets.US_ASCII).trim()
    val hex = if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2) else s
    return hex.takeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.toIntOrNull(16) ?: 0
}

internal fun readFileAttrs(file: File): FileAttrState {
    val path = file.absolutePath
    val readOnly = runCatching {
        (android.system.Os.stat(path).st_mode and android.system.OsConstants.S_IWUSR) == 0
    }.getOrDefault(!file.canWrite())
    var hidden = false
    var supported = true
    try {
        hidden = (parseDosAttrib(android.system.Os.getxattr(path, DOSATTRIB_XATTR)) and FILE_ATTRIBUTE_HIDDEN) != 0
    } catch (e: android.system.ErrnoException) {
        supported = e.errno == android.system.OsConstants.ENODATA
    } catch (e: Exception) {
        supported = false
    }
    return FileAttrState(readOnly, hidden, supported)
}

internal fun setReadOnly(file: File, readOnly: Boolean): Boolean = runCatching {
    val path = file.absolutePath
    val mode = android.system.Os.stat(path).st_mode
    val writeBits = android.system.OsConstants.S_IWUSR or android.system.OsConstants.S_IWGRP or android.system.OsConstants.S_IWOTH
    android.system.Os.chmod(path, if (readOnly) mode and writeBits.inv() else mode or android.system.OsConstants.S_IWUSR)
    true
}.getOrElse { file.setWritable(!readOnly, true) }

internal fun setHidden(file: File, hidden: Boolean): Boolean = try {
    val path = file.absolutePath
    var attr = try {
        parseDosAttrib(android.system.Os.getxattr(path, DOSATTRIB_XATTR))
    } catch (e: android.system.ErrnoException) {
        if (e.errno == android.system.OsConstants.ENODATA) 0 else throw e
    }
    attr = if (hidden) attr or FILE_ATTRIBUTE_HIDDEN else attr and FILE_ATTRIBUTE_HIDDEN.inv()
    android.system.Os.setxattr(path, DOSATTRIB_XATTR, "0x%x".format(attr).toByteArray(Charsets.US_ASCII), 0)
    true
} catch (e: Exception) {
    false
}
