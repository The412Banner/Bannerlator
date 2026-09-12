package com.winlator.star.ui.screens.contents

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import java.io.File

/**
 * The raw-archive library backing the "Keep raw archive" toggle and the My Files tab.
 *
 * Downloaded archives the user chose to keep are filed by component type under
 * `<base>/components/<Type>/`, sibling to Bannerlator's logs and saves. The base folder is either
 *  - the default public path `Download/bannerlator/` (direct File I/O — the app holds all-files
 *    access), or
 *  - a user-picked SAF tree (persisted uri), used through [DocumentFile].
 *
 * A persisted set of "saved keys" mirrors what's on disk so remote rows can be badged Saved without
 * a directory walk on every recomposition.
 */
class ComponentLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Keep-raw toggle ─────────────────────────────────────────────────────────
    fun keepRaw(): Boolean = prefs.getBoolean(KEY_KEEP_RAW, true)
    fun setKeepRaw(value: Boolean) = prefs.edit().putBoolean(KEY_KEEP_RAW, value).apply()

    // ── Base folder ─────────────────────────────────────────────────────────────
    /** True when a SAF tree uri is the active base. */
    private fun treeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** Absolute File base when not in SAF mode. Default: `<external>/Download/bannerlator/`. */
    private fun fileBase(): File {
        val stored = prefs.getString(KEY_FILE_BASE, null)
        if (stored != null) return File(stored)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "bannerlator")
    }

    /** Human-readable path shown in the UI, always ending with `components/`. */
    fun baseDisplay(): String {
        prefs.getString(KEY_TREE_LABEL, null)?.let { if (treeUri() != null) return "$it/components/" }
        val base = fileBase().absolutePath
        // Trim the external-storage prefix for readability (e.g. /storage/emulated/0/Download/... → Download/...)
        val trimmed = base.substringAfter("/emulated/0/").let { if (it == base) base else it }
        return "$trimmed/components/"
    }

    /** Switch to a plain File base (absolute path). Clears any SAF tree. */
    fun setFileBase(absolutePath: String) {
        prefs.edit()
            .putString(KEY_FILE_BASE, absolutePath.trimEnd('/'))
            .remove(KEY_TREE_URI)
            .remove(KEY_TREE_LABEL)
            .apply()
    }

    /** Reset to the built-in default (`Download/bannerlator/`). */
    fun setDefaultBase() {
        prefs.edit().remove(KEY_FILE_BASE).remove(KEY_TREE_URI).remove(KEY_TREE_LABEL).apply()
    }

    /** Switch to a user-picked SAF tree. The caller must have taken a persistable permission. */
    fun setTreeBase(uri: Uri, label: String) {
        prefs.edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putString(KEY_TREE_LABEL, label)
            .remove(KEY_FILE_BASE)
            .apply()
    }

    fun appPrivateBasePath(): String = File(appContext.getExternalFilesDir(null), "bannerlator").absolutePath

    /** Absolute path of the active File base, or null when a legacy SAF tree base is active. */
    fun currentFileBasePath(): String? = if (treeUri() != null) null else fileBase().absolutePath

    fun defaultBasePath(): String =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "bannerlator").absolutePath

    // ── Saved-key fast index ────────────────────────────────────────────────────
    fun savedKeys(): Set<String> {
        val raw = prefs.getString(KEY_SAVED, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw); (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeSavedKeys(keys: Set<String>) {
        prefs.edit().putString(KEY_SAVED, JSONArray(keys.toList()).toString()).apply()
    }

    private fun markSaved(key: String) = writeSavedKeys(savedKeys() + key)
    private fun unmarkSaved(key: String) = writeSavedKeys(savedKeys() - key)

    // ── "Saved only, not installed" mirror ──────────────────────────────────────
    // Subset of the saved keys that came in through the hub's "Save archive only" action and have not
    // been installed from My Files since. Lets My Files annotate those rows; a keep-raw-on-install copy
    // or a later Reinstall clears the flag.
    fun savedOnlyKeys(): Set<String> {
        val raw = prefs.getString(KEY_SAVED_ONLY, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw); (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeSavedOnlyKeys(keys: Set<String>) {
        prefs.edit().putString(KEY_SAVED_ONLY, JSONArray(keys.toList()).toString()).apply()
    }

    fun isSavedOnly(type: String, fileName: String): Boolean = keyFor(type, fileName) in savedOnlyKeys()

    /** Call after a successful install of a My Files archive: it is no longer "saved only". */
    fun markInstalledFromSaved(type: String, fileName: String) {
        val key = keyFor(type, fileName)
        val cur = savedOnlyKeys()
        if (key in cur) writeSavedOnlyKeys(cur - key)
    }

    /** Stable per-item key used for the Saved badge. */
    fun keyFor(type: String, fileName: String): String = "${ContentsTypes.normalize(type)}::${fileName.lowercase()}"

    // ── Save / list / delete ────────────────────────────────────────────────────

    /**
     * Files the raw [src] archive under `<base>/components/<type>/<fileName>`. Returns true on success.
     *
     * [saveOnly] = the hub's "Save archive only" action: the file is flagged "saved only, not installed"
     * for My Files, and — since nothing consumes [src] afterwards — it is MOVED into place when the base
     * is on the same filesystem (a copy is the fallback; a SAF tree is always a copy). A keep-raw copy
     * during an install leaves [src] untouched (the install still needs it) and clears any saved-only flag.
     */
    fun saveRaw(type: String, fileName: String, src: File, saveOnly: Boolean = false): Boolean {
        val safeName = fileName.substringAfterLast('/').ifBlank { "component.wcp" }
        val ok = runCatching {
            val tree = treeUri()
            if (tree != null) saveRawToTree(tree, type, safeName, src)
            else saveRawToFile(type, safeName, src, move = saveOnly)
        }.getOrDefault(false)
        if (ok) {
            val key = keyFor(type, safeName)
            markSaved(key)
            val only = savedOnlyKeys()
            if (saveOnly) { if (key !in only) writeSavedOnlyKeys(only + key) }
            else if (key in only) writeSavedOnlyKeys(only - key)
        }
        return ok
    }

    private fun saveRawToFile(type: String, fileName: String, src: File, move: Boolean): Boolean {
        val dir = File(File(fileBase(), "components"), type).apply { mkdirs() }
        val dst = File(dir, fileName)
        if (dst.exists()) dst.delete()
        // Same-filesystem rename is free; cache → external storage usually isn't, so fall back to a copy.
        if (move && src.renameTo(dst)) return dst.exists() && dst.length() > 0
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
        return dst.exists() && dst.length() > 0
    }

    private fun saveRawToTree(tree: Uri, type: String, fileName: String, src: File): Boolean {
        val root = DocumentFile.fromTreeUri(appContext, tree) ?: return false
        val components = root.findFile("components") ?: root.createDirectory("components") ?: return false
        val typeDir = components.findFile(type) ?: components.createDirectory(type) ?: return false
        typeDir.findFile(fileName)?.delete()
        val dst = typeDir.createFile("application/octet-stream", fileName) ?: return false
        appContext.contentResolver.openOutputStream(dst.uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
        return true
    }

    data class SavedFile(
        val type: String,
        val name: String,
        val sizeBytes: Long,
        val uri: Uri,
        val isDriver: Boolean = ContentsTypes.isDriver(type),
        // Came in via "Save archive only" and hasn't been installed from My Files since.
        val savedOnly: Boolean = false,
    )

    /** Lists kept archives grouped by type. Reads the active base (File or SAF tree). */
    fun listSaved(): Map<String, List<SavedFile>> {
        val tree = treeUri()
        val only = savedOnlyKeys()
        val out = LinkedHashMap<String, MutableList<SavedFile>>()
        if (tree != null) {
            val root = DocumentFile.fromTreeUri(appContext, tree)
            val components = root?.findFile("components")
            components?.listFiles()?.filter { it.isDirectory }?.forEach { typeDir ->
                val type = typeDir.name ?: return@forEach
                typeDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    val name = f.name ?: "?"
                    out.getOrPut(type) { mutableListOf() }.add(
                        SavedFile(type, name, f.length(), f.uri, savedOnly = keyFor(type, name) in only),
                    )
                }
            }
        } else {
            val componentsRoot = File(fileBase(), "components")
            componentsRoot.listFiles()?.filter { it.isDirectory }?.forEach { typeDir ->
                val type = typeDir.name
                typeDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    out.getOrPut(type) { mutableListOf() }.add(
                        SavedFile(type, f.name, f.length(), Uri.fromFile(f), savedOnly = keyFor(type, f.name) in only),
                    )
                }
            }
        }
        return out
    }

    fun delete(file: SavedFile): Boolean {
        val ok = runCatching {
            if (file.uri.scheme == "content") {
                DocumentFile.fromSingleUri(appContext, file.uri)?.delete() ?: false
            } else {
                file.uri.path?.let { File(it).delete() } ?: false
            }
        }.getOrDefault(false)
        if (ok) {
            unmarkSaved(keyFor(file.type, file.name))
            markInstalledFromSaved(file.type, file.name) // drop the saved-only flag with the file
        }
        return ok
    }

    companion object {
        private const val PREFS = "contents_library_prefs"
        private const val KEY_KEEP_RAW = "keep_raw"
        private const val KEY_FILE_BASE = "base_file_path"
        private const val KEY_TREE_URI = "base_tree_uri"
        private const val KEY_TREE_LABEL = "base_tree_label"
        private const val KEY_SAVED = "saved_keys"
        private const val KEY_SAVED_ONLY = "saved_only_keys"
    }
}
