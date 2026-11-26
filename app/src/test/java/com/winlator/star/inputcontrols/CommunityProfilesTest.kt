package com.winlator.star.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityProfilesTest {
    private val index = """
        {"schemaVersion":1,"sections":[
          {"id":"original","title":"Original","items":[
            {"title":"Bioshock","file":"original__Bioshock.icp","author":"brunodev85","type":"on-screen"},
            {"title":"Mass Effect 2","file":"original__Mass_Effect_2.icp","author":"brunodev85","type":"both"}]},
          {"id":"discord","title":"Discord shared","items":[
            {"title":"Skyrim","file":"discord__Skyrim.icpx","author":"Sr.Ed_137","note":"3-finger HUD","type":"on-screen"},
            {"title":"Bad path","file":"../escape.icpx"},
            {"title":"No file"}]},
          {"id":"empty","title":"Empty","items":[]}]}
    """.trimIndent()

    @Test
    fun parsesSectionsAndItems() {
        val sections = CommunityProfiles.parseIndex(index)
        assertEquals(listOf("original", "discord"), sections.map { it.id })
        val skyrim = sections[1].items.single()
        assertEquals("Skyrim", skyrim.title)
        assertEquals(CommunityProfiles.RELEASE_URL + "discord__Skyrim.icpx", skyrim.url)
        assertEquals("Sr.Ed_137", skyrim.author)
        assertEquals("3-finger HUD", skyrim.note)
        assertFalse(skyrim.mapsController)
        assertTrue(sections[0].items[1].mapsController)
        assertNull(sections[0].items[0].note)
    }

    @Test
    fun winlatorListBecomesOneOriginalSection() {
        val sections = CommunityProfiles.parseWinlatorList("Bioshock.icp\n\nAlien Versus Predator.icp\n")
        assertEquals(1, sections.size)
        assertEquals("original", sections[0].id)
        assertEquals(listOf("Bioshock", "Alien Versus Predator"), sections[0].items.map { it.title })
        assertEquals(CommunityProfiles.WINLATOR_URL + "Bioshock.icp", sections[0].items[0].url)
        assertTrue(CommunityProfiles.parseWinlatorList("\n \n").isEmpty())
    }
}
