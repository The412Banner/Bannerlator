package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Rust engine's `ClientGetUserStats` JSON → expanded achievement list (Phase 3b-2). Mirrors what
 * JavaSteam's `getExpandedAchievements()` produces from the same schema: type-4 stats × bits, joined
 * with the per-block unlock timestamps.
 */
class SteamAchievementDecodeTest {

    private val json = """
        {"eresult":1,"crcStats":77,
         "schema":{"version":"5","stats":{
            "1":{"type":"1","name":"kills"},
            "32":{"type":"4","bits":{
               "0":{"name":"ACH_FIRST","display":{"name":{"english":"First!"},"desc":{"english":"Do a thing"},"hidden":"0","icon":"a.jpg","icon_gray":"a_g.jpg"}},
               "1":{"name":"ACH_HIDDEN","display":{"name":"Plain name","desc":"Plain desc","hidden":"1"}},
               "5":{"name":"ACH_LATER","display":{"name":{"english":"Later"}}}
            }},
            "33":{"bits":{"0":{"name":"ACH_NO_TYPE","display":{"name":{"german":"Nur Deutsch"}}}}}
         }},
         "stats":[{"id":32,"value":3}],
         "achievementBlocks":[{"achievementId":32,"unlockTimes":[1700000000,1700000001,0,0,0,0]}]}
    """.trimIndent()

    @Test
    fun expandsTypeFourBitsWithUnlockTimes() {
        val list = SteamAchievementStore.decodeRustUserStatsForTest(json)
        val byName = list.associateBy { it.first }
        assertEquals(setOf("ACH_FIRST", "ACH_HIDDEN", "ACH_LATER", "ACH_NO_TYPE"), byName.keys)
        assertTrue(byName.getValue("ACH_FIRST").second)
        assertEquals(1700000000L, byName.getValue("ACH_FIRST").third)
        assertTrue(byName.getValue("ACH_HIDDEN").second)
        // Bit 5 has a 0 timestamp → locked; block 33 has no timestamps at all → locked.
        assertFalse(byName.getValue("ACH_LATER").second)
        assertFalse(byName.getValue("ACH_NO_TYPE").second)
    }

    @Test
    fun nonAchievementStatsAreSkipped() {
        val list = SteamAchievementStore.decodeRustUserStatsForTest(json)
        assertTrue(list.none { it.first == "kills" })
    }
}
