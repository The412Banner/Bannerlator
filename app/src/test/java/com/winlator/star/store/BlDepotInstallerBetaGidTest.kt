package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected beta branches on the Rust engine (Phase 3b-3): the `encryptedmanifests/<branch>/gid`
 * blob is AES-256-ECB/PKCS7 of the little-endian manifest id under the branch key Steam hands back
 * from `ClientCheckAppBetaPassword` (the DepotDownloader scheme).
 */
class BlDepotInstallerBetaGidTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    private fun encrypt(manifestId: Long): String {
        val plain = ByteArray(8)
        var v = manifestId
        for (i in 0 until 8) { plain[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return SteamCloudBackend.hex(c.doFinal(plain))
    }

    @Test
    fun decryptsManifestIdWithBranchKey() {
        val id = 0x1234_5678_9ABC_DEF0L
        assertEquals(id, BlDepotInstaller.decryptBetaGid(encrypt(id), key))
        // The blob is one padded block: 16 bytes = 32 hex chars.
        assertEquals(32, encrypt(id).length)
    }

    @Test
    fun wrongKeyOrGarbageYieldsNull() {
        val other = ByteArray(32) { 0x55 }
        assertNull(BlDepotInstaller.decryptBetaGid(encrypt(42L), other))
        assertNull(BlDepotInstaller.decryptBetaGid("zz", key))
        assertNull(BlDepotInstaller.decryptBetaGid("00", key))
    }
}
