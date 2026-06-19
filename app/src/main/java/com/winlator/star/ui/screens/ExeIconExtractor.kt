package com.winlator.star.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts the best-size icon out of a Windows PE/EXE so we can use it as a
 * shortcut cover when SteamGridDB has no match. Walks the PE resource tree
 * to RT_GROUP_ICON → RT_ICON, and decodes either PNG-embedded entries
 * (Vista+ ≥256px icons) or 32/24-bit BITMAPINFOHEADER DIB entries.
 *
 * Only modern colour depths (32 / 24 bit) are decoded — paletted (8/4/1-bit)
 * icons return null. That covers the vast majority of post-XP games; if SGDB
 * also misses for an old paletted EXE the caller falls back to no icon.
 */
internal object ExeIconExtractor {

    private const val TAG = "ExeIconExtractor"

    private const val DOS_MAGIC = 0x5A4D                  // "MZ"
    private const val PE_MAGIC = 0x00004550               // "PE\0\0"
    private const val OPT_PE32 = 0x10b
    private const val OPT_PE32_PLUS = 0x20b
    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val DATA_DIR_RESOURCE = 2

    /**
     * Returns the best Bitmap extractable from `exe`, preferring icons close
     * to `preferredSize`, or null if nothing decodable was found.
     */
    fun extract(exe: File, preferredSize: Int = 64): Bitmap? {
        if (!exe.isFile) return null
        return try {
            RandomAccessFile(exe, "r").use { raf ->
                ParsedPe(raf).readBestIcon(preferredSize)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PE icon extraction failed for ${exe.name}: ${e.message}")
            null
        }
    }

    // ── PE walker ──────────────────────────────────────────────────────────

    private class ParsedPe(private val raf: RandomAccessFile) {

        // Resource directory file offset and the RVA→file delta for icon data lookups.
        private val resourceDirOffset: Long
        private val resourceRvaDelta: Long

        init {
            // DOS header
            raf.seek(0)
            val mz = readU16LE()
            require(mz == DOS_MAGIC) { "Not a PE file (no MZ)" }
            raf.seek(0x3C)
            val peOffset = readU32LE()
            raf.seek(peOffset)
            require(readU32LE() == PE_MAGIC.toLong()) { "Not a PE file (no PE signature)" }

            // COFF header (20 bytes from the PE signature, but readU32LE already advanced 4)
            raf.skipBytes(2)                      // Machine
            val numSections = readU16LE()
            raf.skipBytes(12)                     // TimeDateStamp + SymbolTablePtr + NumOfSyms
            val sizeOptionalHeader = readU16LE()
            raf.skipBytes(2)                      // Characteristics

            // Optional header
            val optionalHeaderStart = raf.filePointer
            val magic = readU16LE()
            val isPePlus = magic == OPT_PE32_PLUS
            require(magic == OPT_PE32 || isPePlus) { "Unknown optional header magic: $magic" }

            // Skip the rest of the optional header up to NumberOfRvaAndSizes.
            // Layout difference: PE32 has BaseOfData (extra 4 bytes) before ImageBase;
            // ImageBase is 4 bytes for PE32 and 8 bytes for PE32+.
            val toNumRva = if (isPePlus) 110 else 94 // bytes from after magic to NumberOfRvaAndSizes
            raf.skipBytes(toNumRva)
            val numRva = readU32LE().toInt()
            require(DATA_DIR_RESOURCE < numRva) { "PE has no resource data directory" }

            // Skip to data directory entry 2 (Resource Table): each entry is 8 bytes.
            raf.skipBytes(DATA_DIR_RESOURCE * 8)
            val resourceRva = readU32LE()
            val resourceSize = readU32LE()
            require(resourceRva != 0L && resourceSize != 0L) { "PE resource directory empty" }

            // Section table starts immediately after the optional header.
            val sectionTableStart = optionalHeaderStart + sizeOptionalHeader
            raf.seek(sectionTableStart)

            var resSectionVa = -1L
            var resSectionFile = -1L
            for (i in 0 until numSections) {
                raf.skipBytes(8)                  // Name
                raf.skipBytes(4)                  // VirtualSize
                val virtualAddress = readU32LE()
                val sizeOfRawData = readU32LE()
                val pointerToRawData = readU32LE()
                raf.skipBytes(16)                 // remaining section header bytes
                if (resourceRva in virtualAddress until (virtualAddress + sizeOfRawData)) {
                    resSectionVa = virtualAddress
                    resSectionFile = pointerToRawData
                }
            }
            require(resSectionVa >= 0) { "Resource section not located" }
            resourceDirOffset = resSectionFile + (resourceRva - resSectionVa)
            resourceRvaDelta = resSectionFile - resSectionVa
        }

        fun readBestIcon(preferredSize: Int): Bitmap? {
            // Type-level entries
            val typeNode = readDirectory(resourceDirOffset)
            val groupIconNode = typeNode.children.firstOrNull { it.idOrNameId == RT_GROUP_ICON }
                ?: return null
            val iconNode = typeNode.children.firstOrNull { it.idOrNameId == RT_ICON }
                ?: return null

            // Build map: RT_ICON ID -> data entry (RVA + size)
            val iconDataById = mutableMapOf<Int, DataEntry>()
            for (nameEntry in readDirectory(resourceDirOffset + iconNode.offsetToData).children) {
                val langDir = readDirectory(resourceDirOffset + nameEntry.offsetToData)
                val firstLang = langDir.children.firstOrNull() ?: continue
                val data = readDataEntry(resourceDirOffset + firstLang.offsetToData)
                iconDataById[nameEntry.idOrNameId] = data
            }

            // Walk RT_GROUP_ICON entries
            data class Candidate(val width: Int, val height: Int, val bitCount: Int, val data: DataEntry)
            val candidates = mutableListOf<Candidate>()
            for (nameEntry in readDirectory(resourceDirOffset + groupIconNode.offsetToData).children) {
                val langDir = readDirectory(resourceDirOffset + nameEntry.offsetToData)
                val firstLang = langDir.children.firstOrNull() ?: continue
                val groupData = readDataEntry(resourceDirOffset + firstLang.offsetToData)
                val groupBytes = readBlob(groupData)
                parseGroupIcon(groupBytes).forEach { (w, h, bc, id) ->
                    iconDataById[id]?.let { candidates += Candidate(w, h, bc, it) }
                }
            }
            if (candidates.isEmpty()) return null

            // Prefer entries close to preferredSize; among ties, higher bit depth wins.
            val best = candidates.maxWithOrNull(compareBy(
                { -kotlin.math.abs(it.width - preferredSize) },
                { it.bitCount },
            )) ?: return null

            val raw = readBlob(best.data)
            return decodeIconImage(raw, best.width, best.height)
        }

        private fun readDirectory(offset: Long): DirectoryNode {
            raf.seek(offset)
            raf.skipBytes(12)                    // Characteristics + TimeDateStamp + Version
            val numNamed = readU16LE()
            val numId = readU16LE()
            val total = numNamed + numId
            val children = ArrayList<DirectoryEntry>(total)
            for (i in 0 until total) {
                val nameOrId = readU32LE()
                val rawOffset = readU32LE()
                val isSubDir = (rawOffset and 0x80000000L) != 0L
                val offsetToData = rawOffset and 0x7FFFFFFFL
                val idOrNameId = (nameOrId and 0x7FFFFFFFL).toInt()
                children += DirectoryEntry(idOrNameId, offsetToData, isSubDir)
            }
            return DirectoryNode(children)
        }

        private fun readDataEntry(offset: Long): DataEntry {
            raf.seek(offset)
            val rva = readU32LE()
            val size = readU32LE()
            // codePage + reserved follow but unused
            return DataEntry(rva, size)
        }

        private fun readBlob(data: DataEntry): ByteArray {
            raf.seek(data.rva + resourceRvaDelta)
            val out = ByteArray(data.size.toInt())
            raf.readFully(out)
            return out
        }

        private fun readU16LE(): Int {
            val b1 = raf.read(); val b2 = raf.read()
            if (b1 < 0 || b2 < 0) throw java.io.EOFException()
            return (b1 and 0xFF) or ((b2 and 0xFF) shl 8)
        }

        private fun readU32LE(): Long {
            val b1 = raf.read(); val b2 = raf.read(); val b3 = raf.read(); val b4 = raf.read()
            if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) throw java.io.EOFException()
            return ((b1 and 0xFF).toLong()) or
                    ((b2 and 0xFF).toLong() shl 8) or
                    ((b3 and 0xFF).toLong() shl 16) or
                    ((b4 and 0xFF).toLong() shl 24)
        }
    }

    private class DirectoryNode(val children: List<DirectoryEntry>)
    private class DirectoryEntry(val idOrNameId: Int, val offsetToData: Long, val isSubDir: Boolean)
    private class DataEntry(val rva: Long, val size: Long)

    /** Each ICONDIRENTRY in an RT_GROUP_ICON resource. */
    private data class GroupEntry(val width: Int, val height: Int, val bitCount: Int, val rtIconId: Int)

    private fun parseGroupIcon(bytes: ByteArray): List<GroupEntry> {
        if (bytes.size < 6) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(0)
        buf.short                                 // reserved
        val type = buf.short.toInt() and 0xFFFF
        if (type != 1) return emptyList()         // 1 = icon
        val count = buf.short.toInt() and 0xFFFF
        val out = ArrayList<GroupEntry>(count)
        repeat(count) {
            if (buf.remaining() < 14) return out
            val w = (buf.get().toInt() and 0xFF).let { if (it == 0) 256 else it }
            val h = (buf.get().toInt() and 0xFF).let { if (it == 0) 256 else it }
            buf.get()                             // ColorCount
            buf.get()                             // Reserved
            buf.short                             // Planes
            val bitCount = buf.short.toInt() and 0xFFFF
            buf.int                               // BytesInRes
            val id = buf.short.toInt() and 0xFFFF
            out += GroupEntry(w, h, bitCount, id)
        }
        return out
    }

    // ── Icon image decoding ───────────────────────────────────────────────

    private fun decodeIconImage(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        // PNG-embedded (Vista+ for ≥256px)
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return decodeDib(bytes, width, height)
    }

    private fun decodeDib(bytes: ByteArray, expectedW: Int, expectedH: Int): Bitmap? {
        if (bytes.size < 40) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.int
        if (headerSize < 40) return null
        // BITMAPINFOHEADER stores height = 2x icon height (XOR mask + AND mask).
        val storedW = buf.int
        val storedH = buf.int
        buf.short                                 // planes
        val bitCount = buf.short.toInt() and 0xFFFF
        val w = if (storedW > 0) storedW else expectedW
        val h = if (storedH > 0) storedH / 2 else expectedH
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null

        // Pixel data follows the header (skip remainder of the header + any colour table).
        // For 32/24-bit no colour table is present, so pixel data starts at headerSize.
        val pixelOffset = headerSize
        return when (bitCount) {
            32 -> decode32Bgra(bytes, pixelOffset, w, h)
            24 -> decode24Bgr(bytes, pixelOffset, w, h)
            else -> null
        }
    }

    private fun decode32Bgra(bytes: ByteArray, offset: Int, w: Int, h: Int): Bitmap? {
        val pixels = IntArray(w * h)
        val rowBytes = w * 4
        if (offset + rowBytes * h > bytes.size) return null
        for (row in 0 until h) {
            val srcRow = h - 1 - row              // DIB rows are bottom-up
            val rowStart = offset + srcRow * rowBytes
            for (col in 0 until w) {
                val i = rowStart + col * 4
                val b = bytes[i].toInt() and 0xFF
                val g = bytes[i + 1].toInt() and 0xFF
                val r = bytes[i + 2].toInt() and 0xFF
                val a = bytes[i + 3].toInt() and 0xFF
                pixels[row * w + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun decode24Bgr(bytes: ByteArray, offset: Int, w: Int, h: Int): Bitmap? {
        // 24-bit rows are padded to 4-byte boundaries.
        val rowStride = ((w * 3) + 3) and 3.inv()
        if (offset + rowStride * h > bytes.size) return null
        val pixels = IntArray(w * h)
        // AND mask (1 bit per pixel) follows the colour data.
        val maskOffset = offset + rowStride * h
        val maskRowStride = ((w + 31) / 32) * 4
        val haveMask = maskOffset + maskRowStride * h <= bytes.size

        for (row in 0 until h) {
            val srcRow = h - 1 - row
            val rowStart = offset + srcRow * rowStride
            val maskStart = if (haveMask) maskOffset + srcRow * maskRowStride else -1
            for (col in 0 until w) {
                val i = rowStart + col * 3
                val b = bytes[i].toInt() and 0xFF
                val g = bytes[i + 1].toInt() and 0xFF
                val r = bytes[i + 2].toInt() and 0xFF
                val alpha = if (haveMask) {
                    val byteIdx = maskStart + col / 8
                    val bit = (bytes[byteIdx].toInt() shr (7 - (col % 8))) and 1
                    if (bit == 0) 0xFF else 0    // 0 in mask = visible
                } else 0xFF
                pixels[row * w + col] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
