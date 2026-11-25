package com.winlator.star.store.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The durable-library row format of [DownloadRegistry]: name / store / installPath / cover / found, plus the legacy 3- and 4-field rows. */
class DownloadRegistryCodecTest {

    @Test
    fun roundTripsAFoundSteamEntry() {
        val entry = DownloadEntry(
            store = Store.STEAM,
            id = "220",
            name = "Half-Life 2",
            cover = null,
            state = DownloadState.INSTALLED,
            pct = 100,
            installPath = "/games/hl2",
            found = true,
        )
        assertEquals(entry, DownloadRegistry.decodeLibraryEntry("STEAM:220", DownloadRegistry.encodeLibraryEntry(entry)))
    }

    @Test
    fun roundTripsFoundFalseWithCover() {
        val entry = DownloadEntry(
            store = Store.GOG,
            id = "1234",
            name = "A Game",
            cover = "https://art",
            state = DownloadState.INSTALLED,
            pct = 100,
            installPath = "/games/a",
            found = false,
        )
        assertEquals(entry, DownloadRegistry.decodeLibraryEntry("GOG:1234", DownloadRegistry.encodeLibraryEntry(entry)))
    }

    @Test
    fun legacyThreeFieldRowLoadsNoCoverAndNotFound() {
        val entry = DownloadRegistry.decodeLibraryEntry("STEAM:220", "Half-Life 2\nSTEAM\n/games/hl2")
        assertEquals(Store.STEAM, entry?.store)
        assertEquals("220", entry?.id)
        assertEquals("Half-Life 2", entry?.name)
        assertEquals("/games/hl2", entry?.installPath)
        assertEquals(DownloadState.INSTALLED, entry?.state)
        assertEquals(100, entry?.pct)
        assertNull(entry?.cover)
        assertEquals(false, entry?.found)
    }

    @Test
    fun legacyFourFieldRowKeepsCoverAndLoadsNotFound() {
        val entry = DownloadRegistry.decodeLibraryEntry("GOG:1234", "A Game\nGOG\n/games/a\nhttps://art")
        assertEquals("https://art", entry?.cover)
        assertEquals(false, entry?.found)
    }

    @Test
    fun unknownStoreIsDropped() {
        assertNull(DownloadRegistry.decodeLibraryEntry("STEAM:220", "Half-Life 2\nNOPE\n/games/hl2"))
    }
}
