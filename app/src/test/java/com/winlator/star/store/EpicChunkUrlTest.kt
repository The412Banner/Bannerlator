package com.winlator.star.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chunk path / failure-reason helpers and the binary-manifest feature-level handling of
 * [EpicDownloadManager]. None of it touches android.util.Log, so it runs on the plain JVM. The
 * ChunksV5 expectations come from Legendary's formulas (struct '<Q' / '<IIII' +
 * base64.urlsafe_b64encode + strip('=')).
 */
class EpicChunkUrlTest {

    private val guid = intArrayOf(1, 2, 3, 4)
    private val hash = 0x0123456789ABCDEFL

    @Test
    fun chunkDirFollowsFeatureLevel() {
        assertEquals("Chunks", EpicDownloadManager.chunkDirForVersion(2))
        assertEquals("ChunksV2", EpicDownloadManager.chunkDirForVersion(3))
        assertEquals("ChunksV3", EpicDownloadManager.chunkDirForVersion(6))
        assertEquals("ChunksV4", EpicDownloadManager.chunkDirForVersion(15))
        assertEquals("ChunksV4", EpicDownloadManager.chunkDirForVersion(21))
        assertEquals("ChunksV5", EpicDownloadManager.chunkDirForVersion(22))
        assertEquals("ChunksV5", EpicDownloadManager.chunkDirForVersion(24))
    }

    @Test
    fun v4PathIsHexAndDecimalGroup() {
        val expected = "ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk"
        assertEquals(expected, EpicDownloadManager.buildChunkPath("ChunksV4", 5, hash, guid, null))
        // A secret GUID only matters from ChunksV5 on.
        assertEquals(
            expected,
            EpicDownloadManager.buildChunkPath("ChunksV4", 5, hash, guid, intArrayOf(9, 9, 9, 9))
        )
    }

    @Test
    fun v5PlainPathIsBase64() {
        val expected = "ChunksV5/plain/05/782riWdFIwE_AQAAAAIAAAADAAAABAAAAA.chunk"
        assertEquals(expected, EpicDownloadManager.buildChunkPath("ChunksV5", 5, hash, guid, null))
        assertEquals(
            expected,
            EpicDownloadManager.buildChunkPath("ChunksV5", 5, hash, guid, intArrayOf(0, 0, 0, 0))
        )
        // High bits set: URL-safe alphabet ('_' / '-'), no '=' padding.
        assertEquals(
            "ChunksV5/plain/123/_v________8_776t3v____8AAAAAAAAAgA.chunk",
            EpicDownloadManager.buildChunkPath(
                "ChunksV5", 123, -2L,
                intArrayOf(0xDEADBEEF.toInt(), -1, 0, Int.MIN_VALUE), null
            )
        )
    }

    @Test
    fun v5SecretPathUsesBase64SecretGuid() {
        val secret = intArrayOf(0xAABBCCDD.toInt(), 0x11223344, 0x55667788, 0x99000011.toInt())
        assertEquals(
            "ChunksV5/3cy7qkQzIhGId2ZVEQAAmQ/05/782riWdFIwE_AQAAAAIAAAADAAAABAAAAA.chunk",
            EpicDownloadManager.buildChunkPath("ChunksV5", 5, hash, guid, secret)
        )
    }

    @Test
    fun engineStatusIsExtracted() {
        val err = "epic app=X: item 9 failed after 5 attempts: unexpected HTTP status (404)"
        assertEquals(404, EpicDownloadManager.httpStatusFromEngineError(err))
        assertNull(EpicDownloadManager.httpStatusFromEngineError("connection reset"))
        assertNull(EpicDownloadManager.httpStatusFromEngineError(null))
    }

    @Test
    fun failureReasonNamesTheRealStatus() {
        assertEquals(
            "chunk download refused by CDN (HTTP 404, 403)",
            EpicDownloadManager.chunkFailureReason(listOf(404, 403), null, 3, 0)
        )
        // The status inside a native engine error counts too.
        assertEquals(
            "chunk download refused by CDN (HTTP 404)",
            EpicDownloadManager.chunkFailureReason(
                emptyList(), "item 9 failed after 5 attempts: unexpected HTTP status (404)", 1, 0
            )
        )
        assertEquals(
            "chunk download failed (native engine)",
            EpicDownloadManager.chunkFailureReason(emptyList(), "disk full", 1, 0)
        )
        assertEquals(
            "5 chunk(s) could not be downloaded from any CDN",
            EpicDownloadManager.chunkFailureReason(emptyList(), null, 5, 0)
        )
        // Encrypted chunks win over any refusal statuses seen along the way.
        assertEquals(
            "this game uses encrypted chunks, not supported yet",
            EpicDownloadManager.chunkFailureReason(listOf(404), null, 2, 2)
        )
    }

    // ── parseManifest: feature level, secret GUIDs, encrypted flag ────────────

    private val secretGuid = intArrayOf(0xAABBCCDD.toInt(), 0x11223344, 0x55667788, 0x99000011.toInt())

    @Test
    fun featureLevel22GivesChunksV5WithSecretGuids() {
        val m = parse(manifest(headerVersion = 22, featureLevel = 22, secrets = listOf(secretGuid, intArrayOf(0, 0, 0, 0))))
        assertEquals("ChunksV5", m.chunkDir)
        assertEquals(2, m.uniqueChunks.size)
        assertArrayEquals(secretGuid, m.uniqueChunks[0].secretGuid)
        assertTrue(m.uniqueChunks[0].getPath("ChunksV5").startsWith("ChunksV5/3cy7qkQzIhGId2ZVEQAAmQ/07/"))
        assertTrue(m.uniqueChunks[1].getPath("ChunksV5").startsWith("ChunksV5/plain/07/"))
        assertEquals(1L, m.uniqueChunks[0].hash)
        assertFalse(m.encrypted)
    }

    @Test
    fun featureLevelBelow22KeepsChunksV4AndNoSecrets() {
        val m = parse(manifest(headerVersion = 21, featureLevel = 21, secrets = null))
        assertEquals("ChunksV4", m.chunkDir)
        assertNull(m.uniqueChunks[0].secretGuid)
        assertTrue(m.uniqueChunks[0].getPath(m.chunkDir).startsWith("ChunksV4/07/0000000000000001_"))
    }

    @Test
    fun metaFeatureLevelWinsOverHeaderVersion() {
        // Like Legendary, the META feature level (not the header version) decides the chunk layout.
        val m = parse(manifest(headerVersion = 18, featureLevel = 22, secrets = listOf(secretGuid, secretGuid)))
        assertEquals("ChunksV5", m.chunkDir)
    }

    @Test
    fun encryptedFlagComesFromStoredAs() {
        val m = parse(manifest(headerVersion = 22, featureLevel = 24, secrets = listOf(secretGuid, secretGuid), storedAs = 2))
        assertTrue(m.encrypted)
    }

    private fun parse(bytes: ByteArray): EpicDownloadManager.EpicManifest.ParsedManifest {
        val m = EpicDownloadManager.parseManifest(bytes)
        assertNotNull(m)
        return m!!
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun fstring(s: String): ByteArray {
        val b = le(4 + s.length + 1)
        b.putInt(s.length + 1)
        b.put(s.toByteArray(Charsets.US_ASCII))
        b.put(0)
        return b.array()
    }

    /**
     * Minimal binary manifest (uncompressed body): META, a ChunkDataList with two chunks (hash 1 and
     * 2, group 7) and an empty FileManifestList. With [secrets] the ChunksV5 arrays are appended to the
     * ChunkDataList, in Legendary's order: secret GUIDs, window_size_compressed, GCM tags.
     */
    private fun manifest(
        headerVersion: Int,
        featureLevel: Int,
        secrets: List<IntArray>?,
        storedAs: Int = 0,
    ): ByteArray {
        val meta = ByteArrayOutputStream()
        val metaBody = ByteArrayOutputStream().apply {
            write(0)                                                    // data version
            write(le(4).putInt(featureLevel).array())
            write(0)                                                    // is file data
            write(le(4).putInt(0).array())                              // app id
            for (s in listOf("App", "1.0", "App.exe", "")) write(fstring(s))
            write(le(4).putInt(0).array())                              // prereq ids
            for (s in listOf("", "", "")) write(fstring(s))             // prereq name / path / args
        }.toByteArray()
        meta.write(le(4).putInt(4 + metaBody.size).array())
        meta.write(metaBody)

        val count = 2
        val cdlBody = ByteArrayOutputStream().apply {
            write(0)                                                    // version
            write(le(4).putInt(count).array())
            for (i in 1..count) write(le(16).putInt(i).putInt(0).putInt(0).putInt(0).array())  // guids
            for (i in 1..count) write(le(8).putLong(i.toLong()).array())                       // hashes
            write(ByteArray(20 * count))                                                       // sha1
            write(ByteArray(count) { 7 })                                                      // group
            write(ByteArray(4 * count))                                                        // window size
            write(ByteArray(8 * count))                                                        // file size
            if (secrets != null) {
                for (g in secrets) write(le(16).putInt(g[0]).putInt(g[1]).putInt(g[2]).putInt(g[3]).array())
                write(ByteArray(4 * count))                                                    // window_size_compressed
                write(ByteArray(16 * count))                                                   // GCM tags
            }
        }.toByteArray()
        val cdl = le(4).putInt(4 + cdlBody.size).array() + cdlBody

        val fml = le(9).putInt(9).put(0).putInt(0).array()

        val body = meta.toByteArray() + cdl + fml
        val v5Header = headerVersion >= 22
        val header = le(if (v5Header) 73 else 41)
        header.putInt(0x44BEC00C).putInt(header.capacity()).putInt(body.size).putInt(body.size)
        header.put(ByteArray(20)).put(storedAs.toByte()).putInt(headerVersion)
        return header.array() + body
    }
}
