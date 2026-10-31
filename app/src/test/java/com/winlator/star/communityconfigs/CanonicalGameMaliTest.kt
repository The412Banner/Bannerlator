package com.winlator.star.communityconfigs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mali count on the community lists: shown only to Mali users, unchanged text for everyone else. */
class CanonicalGameMaliTest {

    private val game = CanonicalGame(
        identity = "2096610",
        name = "Test Game",
        folders = listOf("a", "b"),
        devices = listOf(
            CanonicalDevice("Poco X6 Pro", "Mali-G615 MC6", "Dimensity 8300"),
            CanonicalDevice("Galaxy S24", "", "Xclipse 940"),
            CanonicalDevice("Vivo X200", "Immortalis-G925", ""),
            CanonicalDevice("OnePlus 12", "Adreno (TM) 750", "SM8650"),
        ),
        configCount = 5,
    )

    @Test
    fun maliDeviceCount_countsMaliAndImmortalis() {
        assertEquals(2, game.maliDeviceCount)
    }

    @Test
    fun countsLine_adrenoUser_unchanged() {
        assertEquals("5 configs · 4 devices", CanonicalGame.countsLine(game, "Adreno (TM) 750"))
        assertEquals("5 configs · 4 devices", CanonicalGame.countsLine(game, null))
    }

    @Test
    fun countsLine_maliUser_addsMaliCount() {
        assertEquals("5 configs · 4 devices · 2 Mali", CanonicalGame.countsLine(game, "Mali-G615 MC6"))
    }

    @Test
    fun countsLine_maliUser_noMaliDevices_unchanged() {
        val adrenoOnly = game.copy(devices = listOf(CanonicalDevice("OnePlus 12", "Adreno (TM) 750", "")), configCount = 1)
        assertEquals("1 config · 1 device", CanonicalGame.countsLine(adrenoOnly, "Mali-G710"))
    }

    @Test
    fun isMaliName() {
        assertTrue(CanonicalGame.isMaliName("ARM Mali-G57"))
        assertTrue(CanonicalGame.isMaliName("Immortalis-G720 MC12"))
        assertFalse(CanonicalGame.isMaliName("Adreno (TM) 740"))
        assertFalse(CanonicalGame.isMaliName(null))
    }
}
