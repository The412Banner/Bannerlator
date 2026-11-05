package com.winlator.star.core.unpack

// THROWAWAY compile stand-in for the archive-browsing API (real one: feat/fm-revamp-unpack). Never merge.

import android.content.Context
import java.io.File

class ArchiveEntry(
    val path: String, val rawPath: String?, val isDir: Boolean, val size: Long, val packedSize: Long, modified: Long,
    val encrypted: Boolean, val explicit: Boolean, val method: String?, val crc: String?, val attributes: String?,
) {
    var modified: Long = modified
        internal set
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

class ArchiveTree {
    val fileCount = 0
    val dirCount = 0
    val totalSize = 0L
    val anyEncrypted = false
    val hasRenamed = false
    fun children(dir: String): List<ArchiveEntry> = emptyList()
    fun isDir(path: String): Boolean = false
    fun entry(path: String): ArchiveEntry? = null
    fun descendants(dir: String): Sequence<ArchiveEntry> = emptySequence()
    fun sizeOf(e: ArchiveEntry): Long = 0L
    fun needsPassword(e: ArchiveEntry): Boolean = false
    fun extractNames(selected: List<ArchiveEntry>): List<String>? = null
}

sealed class ArchiveException(message: String) : java.io.IOException(message) {
    class PasswordNeeded(val wrong: Boolean) : ArchiveException("password")
    class NotArchive(message: String) : ArchiveException(message)
    class NoSpace(message: String) : ArchiveException(message)
    class Failed(message: String) : ArchiveException(message)
}

object ArchivePasswords {
    fun get(archive: File): String? = null
    fun set(archive: File, password: String) {}
    fun forget(archive: File) {}
}

class ArchiveIndex(
    val source: File, val backing: File, val key: String, val type: String?, val outerType: String?,
    val props: Map<String, String>, val tree: ArchiveTree, val warning: String?,
) {
    val formatLabel: String get() = type ?: "archive"
}

object ArchiveIndexCache {
    suspend fun load(context: Context, archive: File): ArchiveIndex = loadBlocking(context, archive)
    fun loadBlocking(context: Context, archive: File): ArchiveIndex = throw ArchiveException.Failed("stub")
}

object ArchiveExtract {
    class Planned(val entry: ArchiveEntry, val targetName: String, val replace: Boolean)
    suspend fun toViewCache(context: Context, index: ArchiveIndex, entries: List<ArchiveEntry>, password: String, onPercent: (Int) -> Unit): List<File> = emptyList()
    suspend fun toFolder(context: Context, index: ArchiveIndex, items: List<Planned>, destDir: File, password: String, onStage: (String, Float?) -> Unit): Int = 0
}

object ArchiveBrowse {
    fun canBrowse(file: File): Boolean = false
}
