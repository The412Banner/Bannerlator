package com.winlator.star.ui.simple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The order a Steam game's art is tried in (Steam's own published URLs first), and what its found art is remembered under. */
class LabArtOrderTest {

    private val hashedHero = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/292030/abc123/library_hero.jpg"
    private val hashedHeader = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/292030/def456/header.jpg"
    private val hashedLibHeader = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/292030/789abc/library_header.jpg"
    private val hashedCapsule = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/292030/fed987/library_600x900.jpg"
    private val pics = LabSteamPics(hero = hashedHero, header = hashedHeader, libraryHeader = hashedLibHeader, capsule = hashedCapsule)
    private val root = "https://shared.steamstatic.com/store_item_assets/steam/apps/292030/"

    @Test fun background_publishedArtFirst_thenConstructedUrls_thenSteamGridDb() {
        assertEquals(
            listOf(hashedHero, root + "library_hero_2x.jpg", root + "library_hero.jpg", "https://sgdb/hero.png"),
            labSteamSources(292030, "bg", pics, "https://sgdb/hero.png"),
        )
    }

    @Test fun background_withoutPublishedArtKeepsTheConstructedOrder() {
        assertEquals(
            listOf(root + "library_hero_2x.jpg", root + "library_hero.jpg", "https://sgdb/hero.png"),
            labSteamSources(292030, "bg", null, "https://sgdb/hero.png"),
        )
    }

    @Test fun background_aGameNotLinkedToSteamHasOnlyItsFoundHero() {
        assertEquals(listOf("https://sgdb/hero.png"), labSteamSources(null, "bg", null, "https://sgdb/hero.png"))
        assertEquals(emptyList<String>(), labSteamSources(null, "bg", null, null))
        assertEquals(emptyList<String>(), labSteamSources(0, "bg", null, null))
    }

    @Test fun wide_libraryHeaderThenHeaderThenTheConstructedHeader() {
        assertEquals(listOf(hashedLibHeader, hashedHeader, root + "header.jpg"), labSteamSources(292030, "wide", pics, "https://sgdb/hero.png"))
        assertEquals(listOf(root + "header.jpg"), labSteamSources(292030, "wide", null, null))
    }

    @Test fun cover_publishedCapsuleFirst() {
        assertEquals(listOf(hashedCapsule, root + "library_600x900.jpg"), labSteamSources(292030, "cover", pics, null))
    }

    @Test fun eachUrlOnce_andBlankOnesDropped() {
        val same = LabSteamPics(hero = root + "library_hero.jpg", header = "", libraryHeader = null, capsule = null)
        assertEquals(listOf(root + "library_hero.jpg", root + "library_hero_2x.jpg"), labSteamSources(292030, "bg", same, null))
        assertEquals(listOf(root + "header.jpg"), labSteamSources(292030, "wide", LabSteamPics(null, "", " ", null), null))
    }

    @Test fun logoAndOtherSlots() {
        assertEquals(listOf(root + "logo.png"), labSteamSources(292030, "logo", pics, null))
        assertEquals(emptyList<String>(), labSteamSources(292030, "icon", pics, null))
    }

    @Test fun heroKey_isTheNameAloneWithoutAnAppAndNameAndAppWithOne() {
        assertEquals("Hades", labHeroKey("Hades", null))
        assertEquals("Hades", labHeroKey("Hades", 0))
        assertEquals("Hades#1145360", labHeroKey("Hades", 1145360))
        // The same name linked to another app is a different identity, so a relink does not keep the old game's art.
        assertNotEquals(labHeroKey("Prey", 480490), labHeroKey("Prey", 1000))
    }

    @Test fun picsKey_changesWhenTheTriedPicturesChange() {
        val before = labPicsKey(listOf(root + "library_hero.jpg", "cover"))
        assertEquals(before, labPicsKey(listOf(root + "library_hero.jpg", "cover")))
        // Published art arriving puts a new first picture in front.
        assertNotEquals(before, labPicsKey(listOf(hashedHero, root + "library_hero.jpg", "cover")))
        assertNotEquals(before, labPicsKey(emptyList()))
        // Order matters: it is the order the pictures are tried in.
        assertNotEquals(labPicsKey(listOf("a", "b")), labPicsKey(listOf("b", "a")))
    }
}
