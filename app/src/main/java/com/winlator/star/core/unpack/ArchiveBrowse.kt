package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import com.winlator.star.core.FileUtils
import com.winlator.star.core.StringUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

// Ported from BFE (storage/ArchiveBackend.kt) without its Loc/StorageBackend layer: listing, caching
// and extracting entries of an archive with the bundled 7zz, for the file manager.

/** A failure opening or extracting an archive, worded for a snackbar. */
sealed class ArchiveException(message: String) : java.io.IOException(message) {
    /** The archive needs a password: none was given yet, or [wrong] = the one given didn't work. */
    class PasswordNeeded(val wrong: Boolean) :
        ArchiveException(if (wrong) "Wrong password" else "This archive is password-protected")
    class NotArchive(message: String) : ArchiveException(message)
    class NoSpace(message: String) : ArchiveException(message)
    class Failed(message: String) : ArchiveException(message)
}

/** Passwords typed for an archive, remembered for the app session only (never written to disk). */
object ArchivePasswords {
    private val map = HashMap<String, String>()
    @Synchronized fun get(archive: File): String? = map[ArchiveIndexCache.keyOf(archive)]
    @Synchronized fun set(archive: File, password: String) { map[ArchiveIndexCache.keyOf(archive)] = password }
    @Synchronized fun forget(archive: File) { map.remove(ArchiveIndexCache.keyOf(archive)) }
}

/**
 * Everything BFE knows about one opened archive. [source] is the file the user opened; [backing] is
 * what 7zz actually reads — the same file, or for a compressed tar (`.tar.gz`, `.tar.xz`, `.wcp`, …)
 * the inner `.tar` unpacked once into the cache, because 7zz can't open a tar nested inside a stream
 * compressor (`-ttar.gzip` → E_NOTIMPL, device-verified on 7-Zip 24.08).
 */
class ArchiveIndex(
    val source: File,
    val backing: File,
    val key: String,
    /** The format being browsed ("zip", "7z", "tar", …). */
    val type: String?,
    /** The stream compressor around [backing] when it is an unpacked inner tar ("gzip", "zstd", …). */
    val outerType: String?,
    /** The archive's own `7zz l -slt` block (Physical Size, Method, Solid, …). */
    val props: Map<String, String>,
    val tree: ArchiveTree,
    /** 7zz's complaint when it could still list entries (e.g. "Unexpected end of archive"). */
    val warning: String?,
) {
    /** "zip", or "tar in zstd" for an unpacked compressed tar. */
    val formatLabel: String get() = listOfNotNull(type, outerType?.let { "in $it" }).joinToString(" ").ifEmpty { "archive" }
}

/** Runs the bundled 7zz for browsing: cancellable from a coroutine, stdin closed so it never prompts. */
internal object ArchiveTool {
    fun process(context: Context, args: List<String>): ProcessBuilder = SevenZip.newProcess(context, *args.toTypedArray())

    /**
     * Starts [pb] and runs [body] on the IO dispatcher. If the calling coroutine is cancelled, 7zz is
     * destroyed, which unblocks [body]'s reads, and the cancellation is rethrown.
     */
    suspend fun <T> run(pb: ProcessBuilder, body: (Process) -> T): T = coroutineScope {
        val proc = withContext(Dispatchers.IO) { pb.start() }
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { proc.destroy() }
        }
        try {
            withContext(Dispatchers.IO) {
                runCatching { proc.outputStream.close() }
                body(proc)
            }
        } finally {
            watchdog.cancel()
        }
    }

    class Listed(val listing: SltListing, val exit: Int, val stderr: String)

    /** `7zz l -slt` parsed as it streams (a 100k-entry listing is ~29 MB of text; it is never held). */
    fun readListing(proc: Process, archiveName: String): Listed {
        val err = StringBuilder()
        val errThread = SevenZip.collectStderr(proc.errorStream, err)
        val listing = proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { SltParser.parse(it.iterator(), archiveName) }
        val exit = proc.waitFor()
        runCatching { errThread.join(1000) }
        return Listed(listing, exit, synchronized(err) { err.toString() })
    }

    fun listArgs(archive: File, password: String) =
        listOf("l", "-slt", "-sccUTF-8", "-p$password", "--", archive.absolutePath)
}

/**
 * Process-wide cache of opened archives, keyed on path + size + mtime (so a changed archive is
 * re-read). Bounded by total entry count — a 100k-entry index is roughly 20 MB of heap.
 */
object ArchiveIndexCache {
    private const val TAG = "ArchiveIndexCache"
    private const val MAX_ENTRIES = 400_000
    private const val MAX_ARCHIVES = 6
    private val lru = LinkedHashMap<String, ArchiveIndex>(8, 0.75f, true)
    private val loadLock = Mutex()

    // Single-stream compressors (7zz `Type =`) whose only entry may be a tar worth descending into.
    private val COMPRESSORS = setOf("gzip", "bzip2", "xz", "zstd", "lzma", "lzma86", "z", "lz", "lzip", "lz4")
    private val TAR_SHORTHANDS = listOf(".tgz", ".tbz", ".tbz2", ".txz", ".tzst", ".taz", ".tpz", ".tlz", ".wcp")
    private val CONTENT_SIZE = Regex("""content-size-total:(\d+)""")

    fun keyOf(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    /** Short stable id of [f]'s current content, for cache folder names. */
    fun hashOf(f: File): String =
        MessageDigest.getInstance("SHA-1").digest(keyOf(f).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    @Synchronized fun peek(f: File): ArchiveIndex? = lru[keyOf(f)]

    @Synchronized internal fun isBacking(dir: File): Boolean {
        val prefix = dir.absolutePath + File.separator
        return lru.values.any { it.source.absolutePath.startsWith(prefix) || it.backing.absolutePath.startsWith(prefix) }
    }

    @Synchronized private fun put(i: ArchiveIndex) {
        lru[i.key] = i
        val it = lru.entries.iterator()
        while (lru.size > 1 && (lru.size > MAX_ARCHIVES || lru.values.sumOf { v -> v.tree.fileCount + v.tree.dirCount } > MAX_ENTRIES)) {
            val eldest = it.next()
            if (eldest.key == i.key) break
            it.remove()
        }
    }

    /** What an open is doing right now, for the pane's loading state ([percent] -1 = indeterminate). */
    fun interface Stage { fun update(label: String, percent: Int) }

    /**
     * Returns the index for [archive], listing it (and unpacking a compressed tar's inner tar) when it
     * isn't cached. Uses the session password from [ArchivePasswords]. Cancelling the caller kills 7zz.
     * @throws ArchiveException
     */
    suspend fun load(context: Context, archive: File, stage: Stage = Stage { _, _ -> }): ArchiveIndex {
        peek(archive)?.let { return it }
        // Built on IO: the tree of a 100k-entry archive takes ~50-100 ms, too long for the main thread.
        return loadLock.withLock { peek(archive) ?: withContext(Dispatchers.IO) { build(context, archive, stage) }.also(::put) }
    }

    /** Blocking [load]; run it off the main thread. */
    fun loadBlocking(context: Context, archive: File): ArchiveIndex = peek(archive) ?: runBlocking { load(context, archive) }

    private suspend fun list(context: Context, archive: File, name: String, password: String): ArchiveTool.Listed =
        ArchiveTool.run(ArchiveTool.process(context, ArchiveTool.listArgs(archive, password))) { ArchiveTool.readListing(it, name) }

    private suspend fun build(context: Context, archive: File, stage: Stage): ArchiveIndex {
        if (!archive.isFile) throw ArchiveException.Failed("${archive.name} is no longer there")
        if (!SevenZip.isAvailable(context)) throw ArchiveException.Failed("The bundled 7-Zip engine is missing from this install")
        val password = ArchivePasswords.get(archive) ?: ""
        stage.update("Reading ${archive.name}…", -1)
        val l = list(context, archive, archive.name, password)
        val warning = when (val o = SltOutcome.of(l.exit, l.stderr, l.listing)) {
            is SltOutcome.NeedsPassword -> throw ArchiveException.PasswordNeeded(wrong = password.isNotEmpty())
            is SltOutcome.NotArchive -> throw ArchiveException.NotArchive(o.message)
            is SltOutcome.Failed -> throw ArchiveException.Failed(o.message)
            is SltOutcome.Ok -> o.warning
        }
        val tree = ArchiveTree.build(l.listing.entries)
        val plain = ArchiveIndex(archive, archive, keyOf(archive), l.listing.type, null, l.listing.archiveProps, tree, warning)

        // A .tar.gz / .tar.xz / .tar.zst / .wcp lists as ONE inner tar: unpack it once and browse that.
        val single = tree.children("").singleOrNull()
        val type = l.listing.type?.lowercase()
        if (COMPRESSORS.contains(type ?: "") && single != null && !single.isDir &&
            (single.name.endsWith(".tar", ignoreCase = true) || TAR_SHORTHANDS.any { archive.name.lowercase().endsWith(it) })
        ) {
            val tar = unpackInnerTar(context, archive, plain, single, stage) ?: return plain
            val tl = list(context, tar, tar.name, "")
            val ok = SltOutcome.of(tl.exit, tl.stderr, tl.listing)
            if (ok is SltOutcome.Ok && tl.listing.type != null) {
                return ArchiveIndex(
                    archive, tar, keyOf(archive), tl.listing.type, l.listing.type, tl.listing.archiveProps,
                    ArchiveTree.build(tl.listing.entries), ok.warning,
                )
            }
            Log.i(TAG, "${single.name} in ${archive.name} isn't a tar after all; showing it as a single file")
            tar.parentFile?.deleteRecursively()
        }
        return plain
    }

    /**
     * Unpacks the inner tar of a compressed tar into `cache/archive-tar/<hash>/inner.tar` (reused
     * across sessions; a `.complete` marker guards against a half-written one). Null when 7zz
     * produced something other than one file.
     */
    private suspend fun unpackInnerTar(context: Context, archive: File, outer: ArchiveIndex, entry: ArchiveEntry, stage: Stage): File? {
        val dir = File(ArchiveCache.tarRoot(context), hashOf(archive))
        val tar = File(dir, "inner.tar")
        val marker = File(dir, ".complete")
        if (tar.isFile && marker.isFile) { dir.setLastModified(System.currentTimeMillis()); return tar }
        dir.deleteRecursively()
        dir.mkdirs()
        ArchiveCache.trimTars(context, keep = dir)
        val need = entry.size.takeIf { it > 0 }
            ?: outer.props["Method"]?.let { CONTENT_SIZE.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        if (need > 0 && need + ArchiveCache.SPACE_MARGIN > context.cacheDir.usableSpace) {
            dir.deleteRecursively()
            throw ArchiveException.NoSpace("Opening ${archive.name} needs ${StringUtils.formatBytes(need)} free for its inner ${entry.name}")
        }
        val label = "Unpacking ${entry.name}…"
        stage.update(label, 0)
        try {
            val tmp = File(dir, "x")
            ArchiveExtract.run(context, outer.backing, null, tmp, ArchivePasswords.get(archive) ?: "") { stage.update(label, it) }
            val produced = tmp.listFiles()?.singleOrNull { it.isFile }
            if (produced == null || !produced.renameTo(tar)) { dir.deleteRecursively(); return null }
            tmp.deleteRecursively()
            marker.createNewFile()
            return tar
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }
}

/** Cache folders for archive browsing and their housekeeping. */
internal object ArchiveCache {
    /** Head-room kept free in the cache volume on top of what an operation needs. */
    const val SPACE_MARGIN = 64L * 1024 * 1024
    private const val VIEW_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Entries extracted for viewing / sharing / browsing a nested archive, one folder per archive. */
    fun viewRoot(context: Context) = File(context.cacheDir, "archive-view")
    /** Inner tars of opened compressed tars. */
    fun tarRoot(context: Context) = File(context.cacheDir, "archive-tar")
    fun viewDir(context: Context, archive: File) = File(viewRoot(context), ArchiveIndexCache.hashOf(archive))

    /** Inner tars can be gigabytes: keep only the two most recently used, plus [keep]. */
    fun trimTars(context: Context, keep: File) {
        val dirs = tarRoot(context).listFiles()?.filter { it.isDirectory && it != keep } ?: return
        dirs.sortedByDescending { it.lastModified() }.drop(2)
            .filter { !ArchiveIndexCache.isBacking(it) }
            .forEach { it.deleteRecursively() }
    }

    /** Copies older than a day go (a fresh one may still be open in another app). */
    fun trimViews(context: Context) {
        val now = System.currentTimeMillis()
        viewRoot(context).listFiles()?.filter { it.isDirectory && now - it.lastModified() > VIEW_MAX_AGE_MS && !ArchiveIndexCache.isBacking(it) }
            ?.forEach { it.deleteRecursively() }
    }

    fun ensureSpace(dir: File, need: Long, what: String) {
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        if (need > 0 && free > 0 && need + SPACE_MARGIN > free) {
            throw ArchiveException.NoSpace("Not enough space for $what (${StringUtils.formatBytes(need)} needed, ${StringUtils.formatBytes(free)} free)")
        }
    }
}

/** Extracting entries out of an archive with the bundled 7zz. */
object ArchiveExtract {
    // Above this many characters of names, they go through a list file instead of argv.
    private const val ARGV_NAMES_MAX = 96 * 1024

    /**
     * `7zz x` of [names] (null = everything) from [archive] into [outDir], keeping inner paths.
     * Folder names extract their whole subtree. Suspends until done; cancelling kills 7zz.
     * @throws ArchiveException.PasswordNeeded when the password is missing or wrong.
     */
    suspend fun run(context: Context, archive: File, names: List<String>?, outDir: File, password: String, onPercent: (Int) -> Unit) = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val args = mutableListOf("x", "-o${outDir.absolutePath}", "-y", "-p$password", "-spd", "-sccUTF-8", "-scsUTF-8", "-bsp1")
        var listFile: File? = null
        when {
            names == null -> args += archive.absolutePath
            // argv keeps names exactly; 7-Zip's list-file reader trims spaces and quotes off each line.
            names.sumOf { it.length + 1 } <= ARGV_NAMES_MAX || names.any { it != it.trim() || it.startsWith('"') } -> {
                args += "--"; args += archive.absolutePath; args += names
            }
            else -> {
                val f = File(context.cacheDir, "arc-names-${System.nanoTime()}.txt")
                f.writeText(names.joinToString("\n"), Charsets.UTF_8)
                listFile = f
                args += archive.absolutePath; args += "@${f.absolutePath}"
            }
        }
        val err = StringBuilder()
        val exit = try {
            ArchiveTool.run(ArchiveTool.process(context, args)) { proc ->
                val errThread = SevenZip.collectStderr(proc.errorStream, err)
                SevenZip.pumpProgress(proc.inputStream, 64 * 1024, object : SevenZip.Listener {
                    override fun onProgress(percent: Int, currentFile: String?) = onPercent(percent)
                    override fun onFile(name: String) {}
                })
                val code = proc.waitFor()
                runCatching { errThread.join(1000) }
                code
            }
        } finally {
            listFile?.delete()
        }
        val text = synchronized(err) { err.toString() }
        if (text.contains("wrong password", ignoreCase = true)) throw ArchiveException.PasswordNeeded(wrong = password.isNotEmpty())
        if (exit >= 2) throw ArchiveException.Failed(SltOutcome.lastMessage(text) ?: "7-Zip stopped with exit code $exit")
        Unit
    }

    /** Where [e] landed under [outDir] after [run] + [normaliseLayout]. */
    fun produced(outDir: File, e: ArchiveEntry): File? =
        File(outDir, e.path).takeIf { it.exists() }
            ?: e.rawPath?.let { File(outDir, it) }?.takeIf { it.exists() }
            ?: if (e.rawPath == null) outDir.listFiles()?.singleOrNull { it.isFile } else null

    /**
     * 7zz writes an entry under the name IT knows: on Android a Windows zip's `Win\Dir\a.txt` becomes
     * one file literally named so. Move such outputs to the folders the file manager showed, so [outDir] mirrors
     * the browsed tree. [entries] = everything that was extracted.
     */
    fun normaliseLayout(outDir: File, entries: Sequence<ArchiveEntry>) {
        val renamed = entries.filter { it.renamed && it.rawPath != null }.toList()
        // Files first, then folders deepest-first (a literal "Win\" folder is merged and dropped last).
        for (e in renamed.sortedWith(compareBy<ArchiveEntry>({ it.isDir }, { -it.path.length }))) {
            val from = File(outDir, e.rawPath!!)
            if (!from.exists()) continue
            val to = File(outDir, e.path)
            if (runCatching { from.canonicalPath == to.canonicalPath }.getOrDefault(false)) continue
            to.parentFile?.mkdirs()
            if (!to.exists() && from.renameTo(to)) continue
            FileUtils.moveWithProgress(from, to, null)
        }
    }

    /** Explicit entries at and below [selected] (what one `7zz x` of their names writes). */
    fun subtree(tree: ArchiveTree, selected: List<ArchiveEntry>): Sequence<ArchiveEntry> =
        selected.asSequence().flatMap { e -> if (e.isDir) sequenceOf(e) + tree.descendants(e.path) else sequenceOf(e) }

    /**
     * Extracts [entries] (files) into the per-archive view cache and returns the cached files, reusing
     * an earlier copy of the same size. This is what a tap on a file inside an archive opens, shares or
     * (for a nested archive) browses.
     */
    suspend fun toViewCache(context: Context, index: ArchiveIndex, entries: List<ArchiveEntry>, password: String, onPercent: (Int) -> Unit): List<File> = withContext(Dispatchers.IO) {
        ArchiveCache.trimViews(context)
        val viewDir = ArchiveCache.viewDir(context, index.source).apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
        val result = arrayOfNulls<File>(entries.size)
        val todo = entries.indices.filter { i ->
            val want = File(viewDir, entries[i].path)
            if (want.isFile && want.length() == entries[i].size) { result[i] = want; false } else true
        }
        if (todo.isNotEmpty()) {
            val pick = todo.map { entries[it] }
            ArchiveCache.ensureSpace(context.cacheDir, pick.sumOf { it.size }, "the app cache")
            val tmp = File(viewDir, ".x${System.nanoTime()}")
            try {
                run(context, index.backing, index.tree.extractNames(pick), tmp, password, onPercent)
                normaliseLayout(tmp, pick.asSequence())
                for (i in todo) {
                    val e = entries[i]
                    val got = produced(tmp, e) ?: throw ArchiveException.Failed("7-Zip didn't produce ${e.name}")
                    val want = File(viewDir, e.path)
                    want.parentFile?.mkdirs()
                    want.delete()
                    if (!got.renameTo(want)) throw ArchiveException.Failed("Couldn't cache ${e.name}")
                    result[i] = want
                }
            } finally {
                tmp.deleteRecursively()
            }
        }
        result.map { it!! }
    }

    /** One entry to extract into a destination folder, under [targetName]; [replace] = overwrite/merge. */
    class Planned(val entry: ArchiveEntry, val targetName: String, val replace: Boolean)

    /**
     * Extracts [items] into the folder [destDir], keeping each item's subtree.
     *
     * A staging folder is made INSIDE [destDir] (same filesystem, so the final placement is a rename);
     * entries at the archive root with their own names go straight in. [onStage] gets a label and a
     * 0..1 fraction (null = indeterminate). Returns how many items failed.
     */
    suspend fun toFolder(
        context: Context,
        index: ArchiveIndex,
        items: List<Planned>,
        destDir: File,
        password: String,
        onStage: (String, Float?) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        val tree = index.tree
        val entries = items.map { it.entry }
        val need = entries.sumOf { tree.sizeOf(it) }
        destDir.mkdirs()
        val direct = !tree.hasRenamed && items.all { it.entry.parent.isEmpty() && it.targetName == it.entry.name }
        val stage = if (direct) destDir else File(destDir, ".bl-extract-${System.currentTimeMillis()}")
        ArchiveCache.ensureSpace(destDir, need, destDir.name.ifBlank { "the destination" })
        val label = if (items.size == 1) "Extracting ${items[0].entry.name}" else "Extracting ${items.size} items"
        onStage(label, 0f)
        var failed = 0
        try {
            run(context, index.backing, tree.extractNames(entries), stage, password) { onStage(label, it / 100f) }
            if (direct) return@withContext 0
            normaliseLayout(stage, subtree(tree, entries))
            for (p in items) {
                val got = produced(stage, p.entry)
                if (got == null) { failed++; continue }
                val target = File(destDir, p.targetName)
                if (p.replace && target.isFile && got.isFile) target.delete()
                if (!FileUtils.moveWithProgress(got, target, null)) failed++
            }
        } finally {
            if (!direct) withContext(kotlinx.coroutines.NonCancellable) { stage.deleteRecursively() }
        }
        failed
    }

    /**
     * A stream of one file entry straight out of 7zz (`x -so`), or null for a folder. Closing it
     * kills 7zz. Blocking: call off the main thread.
     */
    fun openEntryStream(context: Context, index: ArchiveIndex, entry: ArchiveEntry): InputStream? {
        if (entry.isDir) return null
        val names = index.tree.extractNames(listOf(entry))
        val pw = ArchivePasswords.get(index.source) ?: ""
        val args = listOf("x", "-so", "-y", "-p$pw", "-spd", "-sccUTF-8", "-bd") +
            (if (names == null) listOf(index.backing.absolutePath) else listOf("--", index.backing.absolutePath) + names)
        val proc = ArchiveTool.process(context, args).start()
        runCatching { proc.outputStream.close() }
        SevenZip.collectStderr(proc.errorStream, StringBuilder())
        return object : FilterInputStream(proc.inputStream) {
            override fun close() { runCatching { super.close() }; proc.destroy() }
        }
    }
}

/** Which files the file manager opens like a folder. */
object ArchiveBrowse {
    /**
     * True when [file] is a 7-Zip-listable archive by its extension and not an Inno Setup / GOG
     * installer (those go to the Unpack screen). BFE's rule (`SevenZip.isSupported && !isInnoSetup`)
     * minus the [ExtraFormats] extensions (lzip, lzop, LZ4, ECM, ALZ, EGG, ACE, LZX), which 7-Zip
     * can't list — send those to the Unpack screen. By extension only: opening can still throw
     * [ArchiveException.NotArchive] (a raw `.bin` / `.img`), so fall back to the Unpack screen then.
     * The Inno check may list the parent folder, so call it off the main thread for big folders.
     */
    fun canBrowse(file: File): Boolean =
        file.isFile && SevenZip.isSupported(file) && !ExtraFormats.hasExtension(file.name) && !SevenZip.isInnoSetup(file)

    /** True when the Unpack screen can be offered for [file] (any engine, by extension or header). */
    fun canUnpack(file: File): Boolean =
        file.isFile && (SevenZip.looksLikeArchive(file) || SevenZip.isInnoSetup(file) || ExtraFormats.detect(file) != null)

    /** Drops every cached listing and view copy of [archive] (after it changed on disk or was deleted). */
    fun forget(context: Context, archive: File) {
        ArchivePasswords.forget(archive)
        runCatching { ArchiveCache.viewDir(context, archive).deleteRecursively() }
    }
}
