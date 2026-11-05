package com.winlator.star.core.unpack

import java.time.LocalDateTime
import java.time.ZoneId

// Pure-Kotlin half of archive browsing: parsing `7zz l -slt` output and turning the flat entry list
// into a folder tree. Nothing here touches Android, so it is unit-tested on the JVM against real
// output captured from the bundled 7zz (see app/src/test). The process side lives in ArchiveBrowse.kt.
// Ported from BFE (storage/ArchiveListing.kt).

/**
 * One entry of an archive as the file manager shows it.
 *
 * [path] is the NORMALISED inner path ('/'-separated, no leading slash, no "." segments, Windows `\`
 * separators turned into folders) that the tree uses. [rawPath] is exactly
 * what 7zz printed — the name 7zz must be handed back to extract this entry. It is the same String
 * instance as [path] when nothing was normalised, and null for the nameless single stream of a
 * compressor (a `.wcp`/`.zst` has no stored name; 7zz invents one from the archive's file name).
 */
class ArchiveEntry(
    val path: String,
    val rawPath: String?,
    val isDir: Boolean,
    val size: Long,
    /** -1 when the format doesn't report it (solid 7z blocks, directories). */
    val packedSize: Long,
    /** Epoch millis; 0 when unknown. A synthesised folder takes its newest child's time. */
    modified: Long,
    val encrypted: Boolean,
    /** False for a parent folder BFE synthesised because the archive has no entry of its own for it. */
    val explicit: Boolean,
    val method: String?,
    val crc: String?,
    val attributes: String?,
) {
    var modified: Long = modified
        internal set
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
    /** True when 7zz knows this entry under a different name than BFE shows ([path]). */
    val renamed: Boolean get() = rawPath !== path && rawPath != path
}

/** What a `7zz l -slt` run produced, before it is turned into a tree. */
class SltListing(
    /** The archive's own property block(s) — Type, Physical Size, Method, Solid, … (last value wins). */
    val archiveProps: Map<String, String>,
    /** Every `Type =` seen in the archive blocks, outermost first. */
    val types: List<String>,
    /** ERRORS: / WARNINGS: lines 7zz printed inside the archive block ("Unexpected end of archive"). */
    val headerMessages: List<String>,
    val entries: List<ArchiveEntry>,
) {
    /** The format 7zz opened the file as ("zip", "7z", "tar", "gzip", …), or null. */
    val type: String? get() = archiveProps["Type"]
}

/**
 * Streaming parser for `7zz l -slt` (with or without `-ba`). Output shape, from the bundled 7-Zip
 * 24.08 on device:
 * ```
 * <banner, "Scanning the drive…", "Listing archive: …">
 * --
 * Path = /sdcard/Download/x.zip          <- the archive's own block
 * Type = zip
 * ERRORS:                                 <- optional, non "key = value" lines
 * Unexpected end of archive
 * Physical Size = 1000
 *
 * ----------
 * Path = Game/readme.txt                  <- one block per entry, blank-line separated
 * Folder = -
 * Size = 6
 * …
 * ```
 * Format quirks handled: `-ba` drops everything up to the first entry; 7z has no `Folder` line (a
 * directory shows `Attributes = D drwxr-xr-x`), tar uses `Mode = drwxr-xr-x`, zip prints
 * `Attributes =  -rw-r--r--`; 7z times carry 7 fractional digits; a zstd stream's only entry has no
 * `Path` line at all; Windows-made zips list `Win\Dir\file.txt` with `Host OS = FAT`.
 */
object SltParser {
    private val WINDOWS_HOSTS = setOf("FAT", "NTFS", "VFAT", "HPFS", "Windows", "OS/2", "MS-DOS")
    private val KEY = Regex("""[A-Za-z][A-Za-z0-9 /_.-]*""")

    /**
     * Parses [lines]. [archiveName] names the nameless stream of a compressor; [zone] interprets the
     * printed times (7zz prints local time). Keeps no per-line state beyond the current block, so a
     * 100k-entry listing streams through without holding the text.
     */
    fun parse(lines: Iterator<String>, archiveName: String, zone: ZoneId = ZoneId.systemDefault()): SltListing {
        val archiveProps = LinkedHashMap<String, String>()
        val types = ArrayList<String>()
        val messages = ArrayList<String>()
        val entries = ArrayList<ArchiveEntry>()
        val intern = HashMap<String, String>()
        val block = HashMap<String, String>()

        // 0 = preamble (before "--"), 1 = archive block(s), 2 = entries
        var state = 0

        fun flush() {
            if (block.isEmpty()) return
            toEntry(block, archiveName, zone, intern)?.let(entries::add)
            block.clear()
        }

        while (lines.hasNext()) {
            val line = lines.next().removeSuffix("\r")
            when (state) {
                0 -> when {
                    line == "--" -> state = 1
                    // -ba: no banner and no archive block, entries start straight away.
                    line.startsWith("Path = ") -> { state = 2; keyValue(line)?.let { (k, v) -> block[k] = v } }
                }
                1 -> {
                    if (line == "----------") { state = 2; continue }
                    val kv = keyValue(line)
                    if (kv != null) {
                        archiveProps[kv.first] = kv.second.trim()
                        if (kv.first == "Type") types.add(kv.second.trim())
                    } else if (line.isNotBlank() && line != "ERRORS:" && line != "WARNINGS:") {
                        messages.add(line.trim())
                    }
                }
                else -> {
                    if (line.isEmpty()) { flush(); continue }
                    val kv = keyValue(line) ?: continue   // summary lines, multi-line comments
                    if (kv.first == "Path" && block.containsKey("Path")) flush()
                    block[kv.first] = kv.second
                }
            }
        }
        flush()
        return SltListing(archiveProps, types, messages, entries)
    }

    /** "Key = Value" (or "Key =" with an empty value) → pair; null for any other line. */
    private fun keyValue(line: String): Pair<String, String>? {
        val i = line.indexOf(" = ")
        val (k, v) = when {
            i > 0 -> line.substring(0, i) to line.substring(i + 3)
            line.endsWith(" =") -> line.dropLast(2) to ""
            else -> return null
        }
        return if (KEY.matches(k)) k to v else null
    }

    private fun toEntry(b: Map<String, String>, archiveName: String, zone: ZoneId, intern: HashMap<String, String>): ArchiveEntry? {
        val raw = b["Path"]
        var p = raw ?: streamName(archiveName)
        if ('\\' in p && WINDOWS_HOSTS.contains(b["Host OS"]?.trim() ?: "")) p = p.replace('\\', '/')
        val norm = if (p.startsWith('/') || p.endsWith('/') || "//" in p || p == "." || p.startsWith("./") || "/./" in p || p.endsWith("/.") || ".." in p) {
            p.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
        } else p
        if (norm.isEmpty()) return null

        val attrs = b["Attributes"]?.trim()?.ifEmpty { null }
        val mode = b["Mode"]?.trim()?.ifEmpty { null }
        val isDir = when (b["Folder"]?.trim()) {
            "+" -> true
            "-" -> false
            else -> attrsSayDir(attrs) || mode?.startsWith('d') == true || raw?.endsWith('/') == true
        }
        fun interned(s: String?) = s?.trim()?.ifEmpty { null }?.let { intern.getOrPut(it) { it } }
        return ArchiveEntry(
            path = if (raw != null && norm == raw) raw else norm,
            rawPath = raw,
            isDir = isDir,
            size = if (isDir) 0L else b["Size"]?.trim()?.toLongOrNull() ?: 0L,
            packedSize = b["Packed Size"]?.trim()?.toLongOrNull() ?: -1L,
            modified = parseTime(b["Modified"], zone),
            encrypted = b["Encrypted"]?.trim() == "+",
            explicit = true,
            method = interned(b["Method"]),
            crc = b["CRC"]?.trim()?.ifEmpty { null },
            attributes = interned(attrs ?: mode),
        )
    }

    /**
     * The name 7zz gives a compressor's nameless stream: the archive name minus its last extension
     * ("game.wcp" → "game", "x.tar.zst" → "x.tar"), as observed on device.
     */
    fun streamName(archiveName: String): String {
        val dot = archiveName.lastIndexOf('.')
        return if (dot > 0) archiveName.substring(0, dot) else "$archiveName~"
    }

    /** "D drwxr-xr-x", "D", "DA", " drwxr-xr-x" → directory; "A -rw-r--r--", " -rw-r--r--" → not. */
    private fun attrsSayDir(attrs: String?): Boolean {
        if (attrs == null) return false
        for (t in attrs.split(' ')) {
            if (t.isEmpty()) continue
            if (t.length == 10 && t[0] == 'd' && t.substring(1).all { it in "rwxsStT-" }) return true
            if (t.all { it.isUpperCase() || it == '.' } && 'D' in t) return true
        }
        return false
    }

    /** "2026-10-16 17:19:10" or "2026-10-16 17:19:10.0000000" (local time) → epoch millis; 0 if absent. */
    fun parseTime(s: String?, zone: ZoneId = ZoneId.systemDefault()): Long {
        val t = s?.trim() ?: return 0L
        if (t.length < 19 || t[4] != '-' || t[7] != '-' || t[10] != ' ' || t[13] != ':' || t[16] != ':') return 0L
        return runCatching {
            LocalDateTime.of(
                t.substring(0, 4).toInt(), t.substring(5, 7).toInt(), t.substring(8, 10).toInt(),
                t.substring(11, 13).toInt(), t.substring(14, 16).toInt(), t.substring(17, 19).toInt(),
            ).atZone(zone).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }
}

/**
 * The folder tree over an archive's entries. Archives often lack explicit directory entries (`zip -D`,
 * many tools), so every missing parent is synthesised; duplicate paths keep the last entry (tar
 * appends), and a path that is both a file and a folder shows as the folder (keys must stay unique).
 */
class ArchiveTree private constructor(
    private val dirs: Map<String, ArchiveEntry>,
    private val kids: Map<String, List<ArchiveEntry>>,
    val fileCount: Int,
    val totalSize: Long,
    val anyEncrypted: Boolean,
    /** True when some entry's 7zz name differs from the name BFE shows (backslash zips, "./" tars). */
    val hasRenamed: Boolean,
) {
    val dirCount: Int get() = dirs.size

    /** Direct children of the folder [dir] ("" = the archive's root). */
    fun children(dir: String): List<ArchiveEntry> = kids[dir] ?: emptyList()

    fun isDir(path: String): Boolean = path.isEmpty() || dirs.containsKey(path)

    /** The entry at [path] (a folder or a file), or null. */
    fun entry(path: String): ArchiveEntry? =
        dirs[path] ?: kids[path.substringBeforeLast('/', "")]?.firstOrNull { it.path == path }

    /** Every entry below [dir] (depth-first), not including [dir] itself. */
    fun descendants(dir: String): Sequence<ArchiveEntry> = sequence {
        val stack = ArrayDeque<String>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            for (c in children(stack.removeLast())) {
                yield(c)
                if (c.isDir) stack.add(c.path)
            }
        }
    }

    /** Total uncompressed size of [e] (a file's own size; everything below a folder). */
    fun sizeOf(e: ArchiveEntry): Long = if (!e.isDir) e.size else descendants(e.path).sumOf { if (it.isDir) 0L else it.size }

    /** True when [e] (or anything below it) is encrypted, i.e. extracting it needs a password. */
    fun needsPassword(e: ArchiveEntry): Boolean = e.encrypted || (e.isDir && descendants(e.path).any { it.encrypted })

    /**
     * The names to hand `7zz x` (with `-spd`) so it extracts exactly [selected] and everything under
     * the folders among them, or null when the whole archive must be extracted (the nameless stream
     * of a compressor). 7zz matches a folder name as a path PREFIX even when the archive has no entry
     * for that folder (device-verified), so a folder normally costs one name. Only when 7zz knows the
     * entries under a different name ([hasRenamed]) do we list every explicit entry below it.
     */
    fun extractNames(selected: List<ArchiveEntry>): List<String>? {
        val out = LinkedHashSet<String>()
        for (e in selected) {
            if (!e.isDir) {
                out.add(e.rawPath ?: return null)
                continue
            }
            val subtree = if (hasRenamed) descendants(e.path).filter { it.explicit }.toList() else emptyList()
            if (subtree.any { it.renamed || it.rawPath == null }) {
                if (e.explicit) e.rawPath?.let(out::add)
                for (d in subtree) out.add(d.rawPath ?: return null)
            } else {
                out.add(if (e.explicit) e.rawPath ?: e.path else e.path)
            }
        }
        return out.toList()
    }

    companion object {
        fun build(entries: List<ArchiveEntry>): ArchiveTree {
            val dirs = HashMap<String, ArchiveEntry>()
            val files = LinkedHashMap<String, ArchiveEntry>()
            var anyEncrypted = false
            var renamed = false
            for (e in entries) {
                if (e.encrypted) anyEncrypted = true
                if (e.renamed || e.rawPath == null) renamed = true
                if (e.isDir) dirs[e.path] = e else files[e.path] = e
            }
            // Synthesise every missing parent folder.
            val synthesized = ArrayList<ArchiveEntry>()
            fun ensureParents(path: String) {
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty() && !dirs.containsKey(parent)) {
                    val d = ArchiveEntry(parent, parent, true, 0L, -1L, 0L, false, false, null, null, null)
                    dirs[parent] = d
                    synthesized.add(d)
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            for (k in dirs.keys.toList()) ensureParents(k)
            for (k in files.keys) ensureParents(k)
            // A path that is both a file and a folder: the folder wins (list keys must be unique).
            files.keys.removeAll(dirs.keys)

            val kids = HashMap<String, ArrayList<ArchiveEntry>>()
            var total = 0L
            for (d in dirs.values) kids.getOrPut(d.parent) { ArrayList() }.add(d)
            for (f in files.values) { kids.getOrPut(f.parent) { ArrayList() }.add(f); total += f.size }

            // A synthesised folder shows its newest child's time (deepest first, so it bubbles up).
            synthesized.sortByDescending { it.path.count { c -> c == '/' } }
            for (d in synthesized) d.modified = kids[d.path]?.maxOfOrNull { it.modified } ?: 0L

            return ArchiveTree(dirs, kids, files.size, total, anyEncrypted, renamed)
        }
    }
}

/** Why a listing failed, judged from 7zz's exit code, stderr and what was parsed. */
sealed class SltOutcome {
    /** Listed; [warning] carries 7zz's complaint when it still produced entries (a damaged tail). */
    class Ok(val warning: String?) : SltOutcome()
    /** The listing itself is encrypted (7z with encrypted headers) or the password was wrong. */
    object NeedsPassword : SltOutcome()
    class NotArchive(val message: String) : SltOutcome()
    class Failed(val message: String) : SltOutcome()

    companion object {
        /** Formats that are a program, not a container (mirrors SevenZip.EXECUTABLE_TYPES). */
        private val PROGRAM_TYPES = setOf("PE", "ELF", "MachO", "COFF", "TE", "Mub")

        fun of(exitCode: Int, stderr: String, listing: SltListing): SltOutcome {
            val err = stderr.lowercase()
            val empty = listing.entries.isEmpty()
            if (empty && ("wrong password" in err || "cannot open encrypted archive" in err || "enter password" in err)) return NeedsPassword
            if (PROGRAM_TYPES.contains(listing.type ?: "")) return NotArchive("This is a program (${listing.type}), not an archive")
            if (empty && exitCode != 0) {
                if ("cannot open the file as" in err || "is not archive" in err) return NotArchive("Not an archive 7-Zip can open")
                return Failed(lastMessage(stderr) ?: listing.headerMessages.lastOrNull() ?: "7-Zip couldn't list it (exit $exitCode)")
            }
            // Only a failing exit is worth a warning (a damaged tail); informational header notes on a
            // clean listing (data after the end of an APK's zip, …) stay quiet.
            if (exitCode == 0) return Ok(null)
            val warn = (listing.headerMessages + listOfNotNull(lastMessage(stderr)))
                .distinct().joinToString("; ").ifEmpty { null }
            return Ok(warn)
        }

        /** The last meaningful stderr line, minus 7zz's "ERROR: <archive path> :" prefix. */
        fun lastMessage(stderr: String): String? = stderr.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "ERRORS:" && it != "WARNINGS:" && it != "System ERROR:" }
            .lastOrNull()
            ?.let {
                val rest = it.removePrefix("ERROR:").trim()
                if (rest.startsWith("/") && " : " in rest) rest.substringAfter(" : ").trim() else rest
            }
    }
}
