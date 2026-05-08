package com.winlator.cmod.ui.screens

import android.app.Application
import android.content.Context
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.store.StarLaunchBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections

enum class ShortcutSortOrder { NAME_ASC, NAME_DESC, CONTAINER }

sealed class ImportResult {
    object Success : ImportResult()
    data class Error(val message: String) : ImportResult()
}

class ShortcutsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("shortcuts_prefs", Context.MODE_PRIVATE)

    private val _shortcuts = MutableStateFlow<List<Shortcut>>(emptyList())

    private val _sortOrder = MutableStateFlow(
        ShortcutSortOrder.entries[
            prefs.getInt("sort_order", ShortcutSortOrder.NAME_ASC.ordinal)
                .coerceIn(0, ShortcutSortOrder.entries.size - 1)
        ]
    )
    val sortOrder: StateFlow<ShortcutSortOrder> = _sortOrder

    private val _isGridView = MutableStateFlow(prefs.getBoolean("is_grid_view", false))
    val isGridView: StateFlow<Boolean> = _isGridView

    val shortcuts: kotlinx.coroutines.flow.Flow<List<Shortcut>> =
        combine(_shortcuts, _sortOrder) { list, order ->
            when (order) {
                ShortcutSortOrder.NAME_ASC   -> list.sortedBy { it.name.lowercase() }
                ShortcutSortOrder.NAME_DESC  -> list.sortedByDescending { it.name.lowercase() }
                ShortcutSortOrder.CONTAINER  -> list.sortedBy { (it.container?.name ?: "").lowercase() }
            }
        }

    private val manager = ContainerManager(app)

    init {
        refresh()
    }

    fun setSortOrder(order: ShortcutSortOrder) {
        _sortOrder.value = order
        prefs.edit().putInt("sort_order", order.ordinal).apply()
    }

    fun setGridView(grid: Boolean) {
        _isGridView.value = grid
        prefs.edit().putBoolean("is_grid_view", grid).apply()
    }

    fun importShortcut(containerIndex: Int, uri: Uri, context: Context): ImportResult {
        val containers = manager.getContainers()
        if (containerIndex < 0 || containerIndex >= containers.size) {
            return ImportResult.Error("Invalid container.")
        }
        val container = containers[containerIndex]

        val sourceName = DocumentFile.fromSingleUri(context, uri)?.name
            ?: return ImportResult.Error("Could not read picked file.")
        val ext = sourceName.substringAfterLast('.', "").lowercase()

        return when (ext) {
            "exe" -> importExe(container, uri, sourceName, context)
            "desktop", "lnk" -> importShortcutFile(container, uri, sourceName, ext, context)
            else -> ImportResult.Error("Unsupported file type: .$ext (pick a .exe, .desktop, or .lnk).")
        }
    }

    private fun importExe(container: Container, uri: Uri, sourceName: String, context: Context): ImportResult {
        val realPath = resolveLocalPath(context, uri)
            ?: return ImportResult.Error("EXE must be on local storage. Cloud / SAF locations aren't supported.")
        val exeFile = File(realPath)
        if (!exeFile.isFile) {
            return ImportResult.Error("Could not access EXE on disk: $realPath")
        }
        val displayName = sourceName.substringBeforeLast('.', sourceName)
        return try {
            val shortcutFile = writeExeShortcut(container, exeFile, displayName)
            refresh()
            // Cover art on a background thread — SteamGridDB lookup involves network I/O.
            // Fallback chain: store URL (none here) → SGDB → PE icon extraction from the EXE.
            val safeName = shortcutFile.nameWithoutExtension
            val appCtx = context.applicationContext
            Thread({
                try {
                    StarLaunchBridge.saveCoverArt(appCtx, container, shortcutFile, safeName, null)
                    val iconFile = container.getIconsDir(64)?.let { File(it, "$safeName.png") }
                    if (iconFile == null || !iconFile.exists()) {
                        // SGDB miss — try extracting an icon from the EXE itself.
                        ExeIconExtractor.extract(exeFile)?.let { bmp ->
                            container.getIconsDir(64)?.let { iconsDir ->
                                if (!iconsDir.exists()) iconsDir.mkdirs()
                                FileUtils.saveBitmapToFile(bmp, File(iconsDir, "$safeName.png"))
                            }
                            try {
                                Shortcut(container, shortcutFile).saveCustomCoverArt(bmp)
                            } catch (e: Exception) {
                                Log.w(TAG, "saveCustomCoverArt failed for $safeName", e)
                            }
                            Log.d(TAG, "PE icon extraction succeeded for $safeName")
                        }
                    }
                    refresh()
                } catch (e: Exception) {
                    Log.w(TAG, "Cover art lookup failed for $safeName", e)
                }
            }, "exe-import-cover-art").start()
            ImportResult.Success
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write EXE shortcut", e)
            ImportResult.Error("Failed to write shortcut: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun importShortcutFile(
        container: Container,
        uri: Uri,
        sourceName: String,
        ext: String,
        context: Context,
    ): ImportResult {
        val destDir = container.getDesktopDir()
        if (!destDir.exists()) destDir.mkdirs()
        val dest = File(destDir, sourceName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return ImportResult.Error("Could not open picked file.")
            if (ext == "desktop") {
                val lines = dest.readLines().map { line ->
                    if (line.startsWith("container_id:")) "container_id:${container.id}" else line
                }
                dest.writeText(lines.joinToString("\n") + "\n")
            }
            refresh()
            ImportResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import shortcut file", e)
            ImportResult.Error("Failed to import: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun writeExeShortcut(container: Container, exeFile: File, displayName: String): File {
        val desktopDir = container.getDesktopDir()
        if (!desktopDir.exists()) desktopDir.mkdirs()

        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "game" }
        val shortcutFile = File(desktopDir, "$safeName.desktop")

        // Resolve to a Wine drive letter against the container's mount map. Z: would
        // map to imagefs root (chroot view) and not reach external storage, so we use
        // F:/D:/etc. as defined in container.drives. If no existing drive contains the
        // EXE path we add and persist a new letter pointing at the parent directory.
        val winPath = resolveWindowsPath(container, exeFile.absolutePath)
        // 4-backslash separators per Winlator's two-pass StringUtils.unescape().
        val escaped = winPath.replace("\\", "\\\\\\\\")
        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(displayName).append("\n")
            append("Exec=wine ").append(escaped).append("\n")
            append("Icon=").append(safeName).append("\n")
            append("Type=Application\n")
            append("StartupWMClass=explorer\n")
            append("\n")
            append("[Extra Data]\n")
        }
        shortcutFile.writeText(content)
        Log.d(TAG, "Wrote EXE shortcut: ${shortcutFile.path} -> $winPath ($exeFile)")
        return shortcutFile
    }

    /**
     * Builds a Wine-side Windows path for `exePath` using the container's drive map.
     * If no existing drive contains the EXE, a new letter is allocated to the EXE's
     * parent folder and persisted on the container.
     */
    private fun resolveWindowsPath(container: Container, exePath: String): String {
        val match = bestDriveMatch(container, exePath)
        if (match != null) {
            val (letter, mountPath) = match
            val rel = exePath.removePrefix(mountPath).removePrefix("/").replace("/", "\\")
            return "$letter:\\$rel"
        }
        // No existing drive — allocate one for the parent folder and persist.
        val parent = File(exePath).parentFile?.absolutePath ?: "/"
        val letter = allocateDriveLetter(container)
            ?: throw IOException("No free drive letter available to map $parent")
        val newDrives = container.drives + "$letter:$parent"
        container.drives = newDrives
        try {
            container.saveData()
            Log.d(TAG, "Auto-added drive $letter: -> $parent (container ${container.id})")
        } catch (e: Exception) {
            Log.w(TAG, "Drive persist failed (continuing with in-memory mapping)", e)
        }
        val fileName = File(exePath).name.replace("/", "\\")
        return "$letter:\\$fileName"
    }

    /** Returns (letter, mountPath) of the longest-matching drive prefix, or null. */
    private fun bestDriveMatch(container: Container, exePath: String): Pair<String, String>? {
        var best: Pair<String, String>? = null
        for (entry in container.drivesIterator()) {
            val letter = entry[0]
            val mountPath = entry[1].trimEnd('/')
            if (mountPath.isEmpty()) continue
            val matches = exePath == mountPath ||
                    exePath.startsWith(if (mountPath.endsWith("/")) mountPath else "$mountPath/")
            if (matches && (best == null || mountPath.length > best!!.second.length)) {
                best = letter to mountPath
            }
        }
        return best
    }

    /** Picks the next free drive letter (skips C: and Z:, plus anything already mapped). */
    private fun allocateDriveLetter(container: Container): String? {
        val used = mutableSetOf("C", "Z")
        for (entry in container.drivesIterator()) used += entry[0].uppercase()
        // Try G..Y first to avoid stomping on common user-set letters (D/E/F).
        val order = ('G'..'Y').map { it.toString() } +
                listOf("A", "B", "D", "E", "F")
        return order.firstOrNull { it !in used }
    }

    private fun resolveLocalPath(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return try {
            if (!DocumentsContract.isDocumentUri(ctx, uri)) return null
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":", limit = 2)
            val type = split[0]
            val rel = if (split.size > 1) split[1] else ""
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    if ("primary".equals(type, ignoreCase = true)) {
                        "${Environment.getExternalStorageDirectory()}/$rel"
                    } else {
                        "/storage/$type/$rel"
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "URI path resolution failed for $uri", e)
            null
        }
    }

    fun refresh() {
        val raw = manager.loadShortcuts()
        // filter out corrupted entries (matches original Fragment logic)
        _shortcuts.value = raw.filter { it != null && it.file != null && it.file.name.isNotEmpty() }
    }

    fun remove(shortcut: Shortcut, context: Context): Boolean {
        val deleted = shortcut.file.delete()
        val lnkPath = shortcut.file.path.substringBeforeLast('.') + ".lnk"
        val lnk = File(lnkPath)
        if (lnk.exists()) lnk.delete()
        if (deleted) {
            disableOnScreen(context, shortcut)
            refresh()
        }
        return deleted
    }

    fun cloneToContainer(shortcut: Shortcut, containerIndex: Int): Boolean {
        val containers = manager.getContainers()
        if (containerIndex >= containers.size) return false
        val result = shortcut.cloneToContainer(containers[containerIndex])
        if (result) refresh()
        return result
    }

    fun containers() = manager.getContainers()

    companion object {
        private const val TAG = "ShortcutsImport"

        fun disableOnScreen(context: Context, shortcut: Shortcut) {
            try {
                val sm = ContextCompat.getSystemService(context, ShortcutManager::class.java)
                sm?.disableShortcuts(
                    Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(com.winlator.cmod.R.string.shortcut_not_available),
                )
            } catch (_: Exception) {}
        }
    }
}
