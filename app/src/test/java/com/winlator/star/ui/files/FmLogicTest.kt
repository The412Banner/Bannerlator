package com.winlator.star.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FmLogicTest {
    @Test fun naturalOrderComparesNumbersByValue() {
        val names = listOf("file10.txt", "File2.txt", "file1.txt", "a", "B", "file02b")
        assertEquals(listOf("a", "B", "file1.txt", "File2.txt", "file02b", "file10.txt"), names.sortedWith(naturalOrder))
    }

    @Test fun foldersFirstEachWithItsOwnOrder() {
        fun d(n: String, t: Long) = FmItem.Disk(File("/x/$n"), true, 0L, t)
        fun f(n: String, t: Long, s: Long) = FmItem.Disk(File("/x/$n"), false, s, t)
        val items = listOf(f("old.log", 1, 5), d("b", 9), f("new.log", 3, 1), d("a", 1))
        val sorted = items.sortedFor(FmSort(FmSortKey.NAME, false, FmSortKey.DATE, true))
        assertEquals(listOf("a", "b", "new.log", "old.log"), sorted.map { it.name })
        val bySize = items.sortedFor(FmSort(FmSortKey.NAME, true, FmSortKey.SIZE, false))
        assertEquals(listOf("b", "a", "new.log", "old.log"), bySize.map { it.name })
    }

    @Test fun uniqueDestinationKeepsTheExtension() {
        val dir = createTempDir()
        try {
            File(dir, "a.txt").writeText("x")
            File(dir, "a (1).txt").writeText("x")
            assertEquals("a (2).txt", uniqueDestination(dir, "a.txt").name)
            assertEquals("b.txt", uniqueDestination(dir, "b.txt").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun textEncodingDetection() {
        assertEquals(TextEncoding.UTF8_BOM, TextDoc.detect(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte())))
        assertEquals(TextEncoding.UTF16LE, TextDoc.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0)))
        // A Windows .ini written as UTF-16 LE without a BOM.
        assertEquals(TextEncoding.UTF16LE_NOBOM, TextDoc.detect("[Display]\r\nWidth=1920\r\n".toByteArray(Charsets.UTF_16LE)))
        assertEquals(TextEncoding.UTF8, TextDoc.detect("café=1\n".toByteArray(Charsets.UTF_8)))
        assertEquals(TextEncoding.LATIN1, TextDoc.detect(byteArrayOf('c'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())))
    }

    @Test fun textDocKeepsLinesAndCrlf() {
        val doc = TextDoc.of("[a]\r\nk=v\r\n\r\nlast".toByteArray())
        assertEquals(4, doc.lineCount)
        assertEquals("k=v", doc.row(1))
        assertEquals("last", doc.row(3))
        assertTrue(!doc.binary)
    }
}
