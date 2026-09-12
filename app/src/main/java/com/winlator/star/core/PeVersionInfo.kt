package com.winlator.star.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the StringFileInfo strings (ProductName, FileDescription, …) out of the
 * VS_VERSION_INFO resource embedded in a Windows PE executable (.exe).
 *
 * Walks the PE resource directory to the first RT_VERSION resource — the same
 * navigation [PeIconExtractor] uses for icons — then parses the alignment-heavy
 * VS_VERSIONINFO → StringFileInfo → StringTable → String tree by hand.
 *
 * Everything is best-effort: any malformed / oversized / unsupported input returns
 * null (or an empty map). No allocation beyond the single resource blob.
 */
object PeVersionInfo {

    private const val RT_VERSION = 16
    private const val MAX_RES_SECTION = 64 * 1024 * 1024 // don't slurp absurd resource sections

    /**
     * Returns the StringFileInfo key→value map (e.g. `"ProductName" -> "Palworld"`),
     * or null if the file has no readable version resource. Keys are the raw
     * VS_VERSIONINFO names; look up [productName]/[fileDescription] for the common ones.
     */
    fun read(file: File): Map<String, String>? = try {
        RandomAccessFile(file, "r").use { raf ->
            val blob = versionResourceBytes(raf) ?: return@use null
            parseStringFileInfo(blob).takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Best display name from a version map. ProductName is usually the cleanest title, but is
     * sometimes an internal abbreviation (e.g. God of War ships ProductName "GoW" while
     * FileDescription is "God of War"). When ProductName is a single spaceless token and
     * FileDescription is a normal multi-word title, prefer FileDescription. Trimmed; null if
     * neither is meaningful.
     */
    fun bestName(map: Map<String, String>?): String? {
        if (map == null) return null
        val product = map["ProductName"]?.trim()?.takeIf { it.isNotEmpty() }
        val desc = map["FileDescription"]?.trim()?.takeIf { it.isNotEmpty() }
        if (product != null && desc != null &&
            !product.contains(' ') && desc.contains(' ') && desc.length <= 60
        ) {
            return desc
        }
        return product ?: desc
    }

    // ── locate the RT_VERSION resource blob (mirrors PeIconExtractor's navigation) ──

    private fun versionResourceBytes(raf: RandomAccessFile): ByteArray? {
        val dos = read(raf, 0, 0x40) ?: return null
        if (u8(dos, 0) != 0x4D || u8(dos, 1) != 0x5A) return null // "MZ"
        val peOff = u32(dos, 0x3C).toInt()

        val coff = read(raf, peOff.toLong(), 24) ?: return null
        if (u8(coff, 0) != 0x50 || u8(coff, 1) != 0x45 || u8(coff, 2) != 0 || u8(coff, 3) != 0) return null // "PE\0\0"
        val numSections = u16(coff, 6)
        val sizeOpt = u16(coff, 20)
        if (numSections <= 0 || numSections > 96 || sizeOpt <= 0) return null

        val optOff = peOff + 24L
        val opt = read(raf, optOff, sizeOpt) ?: return null
        val magic = u16(opt, 0)
        val ddOff = if (magic == 0x20B) 112 else 96 // PE32+ vs PE32 data-directory offset
        val resDirEntry = ddOff + 2 * 8 // data directory index 2 = resource table
        if (resDirEntry + 8 > opt.size) return null
        val resRVA = u32(opt, resDirEntry).toInt()
        val resSize = u32(opt, resDirEntry + 4).toInt()
        if (resRVA == 0 || resSize == 0) return null

        val secTabOff = optOff + sizeOpt
        val secTab = read(raf, secTabOff, numSections * 40) ?: return null

        var secVA = 0
        var secPtr = 0L
        var secRaw = 0
        var found = false
        for (i in 0 until numSections) {
            val o = i * 40
            if (o + 40 > secTab.size) break
            val va = u32(secTab, o + 12).toInt()
            val vSize = u32(secTab, o + 8).toInt()
            val raw = u32(secTab, o + 16).toInt()
            val pRaw = u32(secTab, o + 20)
            val span = maxOf(vSize, raw)
            if (resRVA >= va && resRVA < va + span) {
                secVA = va; secPtr = pRaw; secRaw = raw; found = true; break
            }
        }
        if (!found || secRaw <= 0 || secRaw > MAX_RES_SECTION) return null

        val res = read(raf, secPtr, secRaw) ?: return null
        val resDirStart = resRVA - secVA
        if (resDirStart < 0 || resDirStart + 16 > res.size) return null

        val verTypeDir = findIdSubdir(res, resDirStart, RT_VERSION, resDirStart) ?: return null
        val verData = firstDataEntry(res, verTypeDir, resDirStart) ?: return null
        return dataEntryBytes(res, verData, secVA)
    }

    /** Resolve the sub-directory under [dirIdx] whose id matches [wantId]; null if absent or not a directory. */
    private fun findIdSubdir(res: ByteArray, dirIdx: Int, wantId: Int, resDirStart: Int): Int? {
        if (dirIdx + 16 > res.size) return null
        val total = u16(res, dirIdx + 12) + u16(res, dirIdx + 14)
        val start = dirIdx + 16
        for (i in 0 until total) {
            val e = start + i * 8
            if (e + 8 > res.size) break
            val id = u32(res, e)
            val off = u32(res, e + 4)
            if (id and 0x80000000L == 0L && id.toInt() == wantId) {
                if (off and 0x80000000L != 0L) return resDirStart + (off and 0x7FFFFFFFL).toInt()
                return null
            }
        }
        return null
    }

    /** Descend the first entry of each level until an IMAGE_RESOURCE_DATA_ENTRY; returns its index. */
    private fun firstDataEntry(res: ByteArray, dirIdx: Int, resDirStart: Int): Int? {
        if (dirIdx + 16 > res.size) return null
        if (u16(res, dirIdx + 12) + u16(res, dirIdx + 14) == 0) return null
        val e = dirIdx + 16
        if (e + 8 > res.size) return null
        val off = u32(res, e + 4)
        return if (off and 0x80000000L != 0L) {
            firstDataEntry(res, resDirStart + (off and 0x7FFFFFFFL).toInt(), resDirStart)
        } else {
            resDirStart + off.toInt()
        }
    }

    /** Read the bytes an IMAGE_RESOURCE_DATA_ENTRY points at (its OffsetToData is an RVA). */
    private fun dataEntryBytes(res: ByteArray, entryIdx: Int, secVA: Int): ByteArray? {
        if (entryIdx < 0 || entryIdx + 8 > res.size) return null
        val rva = u32(res, entryIdx).toInt()
        val size = u32(res, entryIdx + 4).toInt()
        val start = rva - secVA
        if (size <= 0 || start < 0 || start + size > res.size) return null
        return res.copyOfRange(start, start + size)
    }

    // ── VS_VERSIONINFO tree parse ──
    //
    // Every node is: WORD wLength, WORD wValueLength, WORD wType, WCHAR szKey[],
    // then (32-bit aligned) an optional Value, then (32-bit aligned) child nodes.
    // We only care about: VS_VERSIONINFO → StringFileInfo → StringTable → String.

    private fun parseStringFileInfo(b: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (b.size < 6) return out
        val rootLen = minOf(u16(b, 0), b.size)
        // VS_VERSION_INFO header: skip szKey "VS_VERSION_INFO" then the FIXEDFILEINFO value.
        val rootValLen = u16(b, 2)
        var pos = align4(6 + (utf16zLen(b, 6) + 1) * 2)
        pos = align4(pos + rootValLen)

        while (pos + 6 <= rootLen) {
            val childLen = u16(b, pos)
            if (childLen < 6 || pos + childLen > b.size) break
            val key = utf16z(b, pos + 6)
            if (key == "StringFileInfo") parseTables(b, pos, pos + childLen, out)
            pos = align4(pos + childLen)
            if (childLen == 0) break
        }
        return out
    }

    /** Iterate the StringTable children inside a StringFileInfo block [start, end). */
    private fun parseTables(b: ByteArray, start: Int, end: Int, out: MutableMap<String, String>) {
        var p = align4(start + 6 + (utf16zLen(b, start + 6) + 1) * 2)
        while (p + 6 <= end) {
            val tableLen = u16(b, p)
            if (tableLen < 6 || p + tableLen > end) break
            parseStrings(b, p, minOf(p + tableLen, end), out)
            p = align4(p + tableLen)
            if (tableLen == 0) break
        }
    }

    /** Iterate the String children inside a StringTable block [start, end). */
    private fun parseStrings(b: ByteArray, start: Int, end: Int, out: MutableMap<String, String>) {
        var q = align4(start + 6 + (utf16zLen(b, start + 6) + 1) * 2)
        while (q + 6 <= end) {
            val strLen = u16(b, q)
            if (strLen < 6 || q + strLen > end) break
            val key = utf16z(b, q + 6)
            val valOff = align4(q + 6 + (key.length + 1) * 2)
            if (key.isNotEmpty() && valOff < q + strLen) {
                val value = utf16z(b, valOff, q + strLen).trim()
                if (value.isNotEmpty()) out[key] = value
            }
            q = align4(q + strLen)
            if (strLen == 0) break
        }
    }

    // ── little-endian / UTF-16 readers ──

    private fun align4(x: Int): Int = (x + 3) and 3.inv()

    /** Number of UTF-16 code units before the null terminator starting at [off]. */
    private fun utf16zLen(b: ByteArray, off: Int): Int {
        var i = off
        while (i + 1 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) break
            i += 2
        }
        return (i - off) / 2
    }

    /** Read a null-terminated UTF-16LE string starting at [off], not reading past [limit]. */
    private fun utf16z(b: ByteArray, off: Int, limit: Int = b.size): String {
        val sb = StringBuilder()
        var i = off
        val end = minOf(limit, b.size)
        while (i + 1 < end) {
            val c = u8(b, i) or (u8(b, i + 1) shl 8)
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun u8(b: ByteArray, off: Int): Int = if (off < 0 || off >= b.size) 0 else b[off].toInt() and 0xFF
    private fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)
    private fun u32(b: ByteArray, off: Int): Long =
        u8(b, off).toLong() or (u8(b, off + 1).toLong() shl 8) or
            (u8(b, off + 2).toLong() shl 16) or (u8(b, off + 3).toLong() shl 24)

    private fun read(raf: RandomAccessFile, pos: Long, len: Int): ByteArray? {
        if (len <= 0 || pos < 0) return null
        val fileLen = raf.length()
        if (pos >= fileLen) return null
        val n = minOf(len.toLong(), fileLen - pos).toInt()
        if (n <= 0) return null
        val buf = ByteArray(n)
        raf.seek(pos)
        raf.readFully(buf)
        return buf
    }
}
