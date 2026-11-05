package com.winlator.star.core.unpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The FreeArc table-of-contents reader. Fixtures under resources/freearc:
 *  - builtin.arc: made by freearc-c's `arc a -m"rep:1mb+lzma:1mb"` (FreeArc's own LZMA for the footer
 *    and directory) - 5 entries, 7848 bytes.
 *  - layers.arc / encrypted.arc: written by BFE's test helper with a stored footer and an LZMA1
 *    directory (layers) or a cipher footer (encrypted); their data blocks are placeholders.
 *  - notarc.bin: random bytes.
 */
class FreeArcInfoTest {
    private fun res(name: String): File =
        File(requireNotNull(javaClass.classLoader?.getResource("freearc/$name")) { "missing fixture $name" }.toURI())

    @Test fun readsArchiveMadeByFreeArc() {
        val info = requireNotNull(FreeArcInfo.read(res("builtin.arc"))) { "builtin.arc not read" }
        assertFalse(info.headersEncrypted)
        assertEquals(1, info.blocks.size)
        assertEquals("rep:9kb+lzma:32kb:normal:32", info.blocks[0].compressor)
        assertEquals(5, info.totalFiles)
        assertEquals(7848L, info.totalBytes)
        assertEquals(listOf("rep", "lzma"), FreeArcInfo.methodNames(info.blocks[0].compressor))
    }

    @Test fun readsLayeredChainsFromEveryBlock() {
        val info = FreeArcInfo.read(res("layers.arc"))!!
        assertEquals(listOf("precomp047+srep:m3f+lzma:1mb", "lzma:1mb"), info.blocks.map { it.compressor })
        assertEquals(listOf("precomp047", "srep", "lzma"), FreeArcInfo.methodNames(info.blocks[0].compressor))
        assertEquals(2, info.totalFiles)
        assertEquals(510L, info.totalBytes)
    }

    @Test fun flagsPasswordProtectedHeaders() {
        val info = FreeArcInfo.read(res("encrypted.arc"))!!
        assertTrue(info.headersEncrypted)
        assertTrue(info.blocks.isEmpty())
    }

    @Test fun rejectsOtherFiles() {
        assertNull(FreeArcInfo.read(res("notarc.bin")))
        assertNull(FreeArcInfo.read(File("/nonexistent/Setup-1.bin")))
    }

    @Test fun rejectsDamagedDescriptor() {
        val src = res("builtin.arc").readBytes()
        val bad = src.copyOf().also { it[it.size - 6] = (it[it.size - 6].toInt() xor 0x40).toByte() }
        val f = File.createTempFile("bfe-freearc", ".bin").apply { deleteOnExit(); writeBytes(bad) }
        assertNull(FreeArcInfo.read(f))
    }

    @Test fun recognisesCipherLayers() {
        assertTrue(FreeArcInfo.isEncrypted("aes-256/ctr:n1000:r8f2a+lzma"))
        assertTrue(FreeArcInfo.isEncrypted("lzma+serpent-256/ctr:k"))
        assertFalse(FreeArcInfo.isEncrypted("precomp047+srep+lzma:96mb"))
    }
}
