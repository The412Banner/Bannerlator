package com.winlator.star.core

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * Turns the Uri another app hands "Open with → Bannerlator" into the REAL file on storage.
 *
 * Wine has to run the exe from its own folder (the game's DLLs and data sit beside it), so a copy is
 * useless: either the Uri leads back to a real path the app can read (it holds MANAGE_EXTERNAL_STORAGE),
 * or the open fails with an honest message. Every guess is checked: a candidate counts only when it
 * exists, is readable and, when the sender told us the size, has that size.
 */
object ExternalExeResolver {
    private const val TAG = "ExternalExeResolver"

    private const val EXTERNAL_STORAGE_DOCS = "com.android.externalstorage.documents"
    private const val DOWNLOADS_DOCS = "com.android.providers.downloads.documents"
    private const val MEDIA_DOCS = "com.android.providers.media.documents"
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** What the sender says about the file, read through the Uri itself. */
    data class UriInfo(val displayName: String?, val size: Long?)

    fun uriInfo(context: Context, uri: Uri): UriInfo {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = uri.path?.let { File(it) }
            return UriInfo(f?.name, f?.takeIf { it.isFile }?.length())
        }
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it > 0 }
                    }
                }
        }
        return UriInfo(name, size)
    }

    /**
     * True when [input] starts like a Windows PE program: the "MZ" DOS header, and the "PE\0\0"
     * signature where e_lfanew points (when it points inside the first 4 KiB, as it does for every
     * real linker output). A bare DOS program has no PE header and Wine can't run it either.
     */
    fun looksLikePe(input: InputStream): Boolean {
        val head = ByteArray(4096)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < 64 || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte()) return false
        val peOffset = (head[0x3C].toInt() and 0xFF) or
            ((head[0x3D].toInt() and 0xFF) shl 8) or
            ((head[0x3E].toInt() and 0xFF) shl 16) or
            ((head[0x3F].toInt() and 0xFF) shl 24)
        if (peOffset < 0 || peOffset + 4 > read) return true   // header beyond what we read: trust MZ
        return head[peOffset] == 'P'.code.toByte() && head[peOffset + 1] == 'E'.code.toByte() &&
            head[peOffset + 2] == 0.toByte() && head[peOffset + 3] == 0.toByte()
    }

    fun looksLikePe(file: File): Boolean = runCatching { file.inputStream().use { looksLikePe(it) } }.getOrDefault(false)

    /**
     * The real, readable file behind [uri], or null when none of the known shapes leads to one.
     * BLOCKING (provider queries + stat calls) - call off the main thread.
     */
    fun resolve(context: Context, uri: Uri): File? {
        val info = uriInfo(context, uri)
        for ((path, weak) in candidates(context, uri, info).distinctBy { it.path }) {
            // A guess (a shortened path, a same-named file in Download/) must also match the size the
            // sender reported; without a size it could be some other file of that name, so skip it.
            if (weak && info.size == null) continue
            val f = File(path)
            if (!f.isFile) continue
            if (!f.canRead()) {
                // Phase 2 (root, not built yet): a path that exists but this app can't read (another
                // app's private dir, /data) is where an su-backed read/bind-mount would go, for
                // rooted file managers in root mode. Without root it is not reachable - skip it.
                Log.i(TAG, "candidate $path exists but is not readable")
                continue
            }
            if (info.size != null && f.length() != info.size) continue
            Log.i(TAG, "resolved $uri -> $path")
            return f
        }
        Log.w(TAG, "could not resolve $uri (name=${info.displayName}, size=${info.size})")
        return null
    }

    /** A path [uri] might stand for; [weak] = a guess that only counts when the size matches too. */
    private data class Candidate(val path: String, val weak: Boolean = false)

    /** Every real path [uri] might stand for, most trustworthy first. */
    private fun candidates(context: Context, uri: Uri, info: UriInfo): List<Candidate> {
        val out = ArrayList<Candidate>()
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { out += Candidate(it) }
            return out
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return out

        val docId = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                // A tree Uri with a document under it (some pickers share these as-is).
                uri.pathSegments.firstOrNull() == "tree" -> DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull()

        when (uri.authority) {
            EXTERNAL_STORAGE_DOCS -> if (docId != null) out += externalStorageDocPaths(docId).map { Candidate(it) }
            DOWNLOADS_DOCS -> if (docId != null) out += downloadsDocPaths(context, docId, info)
            MEDIA_DOCS -> if (docId != null) mediaDocPath(context, docId)?.let { out += Candidate(it) }
        }

        // MediaStore Uris, and every file manager whose provider answers the old _data column.
        queryDataColumn(context, uri)?.let { out += Candidate(it) }

        // FileProvider-style Uris from file managers: the real path is usually inside the Uri's path,
        // behind a root name the provider made up (/root/..., /external_files/..., /storage_root/...),
        // or wrapped as an encoded file:// Uri (Material Files, Files by Google).
        out += pathShapeGuesses(uri)
        return out
    }

    /** `primary:Games/x.exe` → internal storage; `1234-ABCD:Games/x.exe` → that SD card / USB volume. */
    private fun externalStorageDocPaths(docId: String): List<String> {
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        return when {
            volume.equals("primary", ignoreCase = true) -> listOf("$PRIMARY_ROOT/$rel")
            volume.equals("home", ignoreCase = true) -> listOf("$PRIMARY_ROOT/Documents/$rel")
            volume.isNotEmpty() -> listOf("/storage/$volume/$rel", "/mnt/media_rw/$volume/$rel")
            else -> emptyList()
        }.map { it.trimEnd('/') }
    }

    /**
     * Downloads provider ids: `raw:/storage/...` is the path itself; `msf:<id>` is a MediaStore row; a
     * bare number is a DownloadManager row. Last resort: a file of the same name (and size, checked
     * by the caller) straight in Download/.
     */
    private fun downloadsDocPaths(context: Context, docId: String, info: UriInfo): List<Candidate> {
        val out = ArrayList<Candidate>()
        when {
            docId.startsWith("raw:") -> out += Candidate(docId.removePrefix("raw:"))
            docId.startsWith("msf:") -> docId.removePrefix("msf:").toLongOrNull()?.let { id ->
                queryDataColumn(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
                    ?.let { out += Candidate(it) }
            }
            else -> docId.toLongOrNull()?.let { id ->
                queryDataColumn(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))
                    ?.let { out += Candidate(it) }
            }
        }
        info.displayName?.let { out += Candidate("$PRIMARY_ROOT/Download/$it", weak = true) }
        return out
    }

    /**
     * The folder a tree Uri picked through Android's Downloads entry stands for: `downloads` is the
     * Download folder, `raw:<path>` the path itself, `msd:<id>` a MediaStore row of a folder in it.
     * Null for other providers and for ids of any other shape.
     */
    @JvmStatic
    fun downloadsFolderPath(context: Context?, uri: Uri, treeDocId: String): String? {
        if (uri.authority != DOWNLOADS_DOCS) return null
        return when {
            treeDocId == "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            treeDocId.startsWith("raw:") -> treeDocId.removePrefix("raw:").takeIf { it.startsWith("/") }
            context != null && treeDocId.startsWith("msd:") -> treeDocId.removePrefix("msd:").toLongOrNull()?.let { id ->
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    queryDataColumn(context, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL, id)) else null)
                    ?: queryDataColumn(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
            }
            else -> null
        }
    }

    /** Media provider ids look like `image:42` / `video:42` / `document:42`; the row is in MediaStore.Files. */
    private fun mediaDocPath(context: Context, docId: String): String? {
        val id = docId.substringAfter(':', "").toLongOrNull() ?: return null
        return queryDataColumn(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
    }

    @Suppress("DEPRECATION")   // MediaStore.MediaColumns.DATA: still filled in, and the only path column
    private fun queryDataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            val i = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getString(i)?.takeIf { it.startsWith("/") } else null
        }
    }.getOrNull()

    /**
     * Paths read straight out of the Uri's own path. Each suffix of the segment list is tried both as an
     * absolute path (`/root/storage/emulated/0/x.exe` → `/storage/emulated/0/x.exe`) and under internal
     * storage (`/external_files/Games/x.exe` → `/storage/emulated/0/Games/x.exe`); `sdcard` stands for
     * internal storage. Nothing here is trusted until [resolve] has checked it on disk.
     */
    private fun pathShapeGuesses(uri: Uri): List<Candidate> {
        val out = ArrayList<Candidate>()
        val decoded = uri.path ?: return out
        // An encoded file:// Uri inside the path, e.g. content://<fm>.file_provider/file%3A%2F%2F%2Fstorage%2F...
        val fileIdx = decoded.indexOf("file://")
        if (fileIdx >= 0) Uri.parse(decoded.substring(fileIdx)).path?.let { out += Candidate(it) }

        val segments = decoded.split('/').filter { it.isNotEmpty() }
        for (i in segments.indices) {
            val rest = segments.subList(i, segments.size)
            val joined = rest.joinToString("/")
            when (rest.first()) {
                // A real absolute path behind the root name: as strong as a path gets.
                "storage", "mnt" -> out += Candidate("/$joined")
                "sdcard" -> out += Candidate("$PRIMARY_ROOT/" + rest.drop(1).joinToString("/"))
                // Past the provider's root name (the first segment is never a real folder): the whole
                // remainder under internal storage is a fair reading; anything shorter is a guess.
                else -> if (i > 0) out += Candidate("$PRIMARY_ROOT/$joined", weak = i > 1)
            }
        }
        return out
    }
}
