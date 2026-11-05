package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which store mark a game wears: the table of [storeLabelOf] labels against the mark drawn on its cover. */
class StoreMarkTest {

    @Test fun everyStoreLabel_hasItsOwnMark() {
        val table = mapOf(
            "Steam" to StoreMark.STEAM,
            "Epic Games" to StoreMark.EPIC,
            "GOG" to StoreMark.GOG,
            "Amazon Games" to StoreMark.AMAZON,
            "Xbox" to StoreMark.XBOX,
            "Android" to StoreMark.ANDROID,
            "Added by me" to StoreMark.WINDOWS,
        )
        table.forEach { (label, mark) -> assertEquals(label, mark, storeMarkOfLabel(label)) }
    }

    @Test fun linuxEntry_wearsTheSteamMark() {
        // The Linux entry is Valve's own Steam client.
        assertEquals(StoreMark.STEAM, storeMarkOfLabel("Linux"))
    }

    @Test fun unknownLabel_isAPlainWindowsExe() {
        assertEquals(StoreMark.WINDOWS, storeMarkOfLabel(""))
        assertEquals(StoreMark.WINDOWS, storeMarkOfLabel("Itch.io"))
    }

    @Test fun storeOrder_ranksTheLabelsTheMarksAreMadeFor() {
        val labels = listOf("Steam", "Epic Games", "GOG", "Amazon Games", "Xbox", "Android", "Linux", "Added by me")
        assertEquals(labels.indices.toList(), labels.map { storeRank(it) })
        assertEquals(labels.size, storeRank("Itch.io"))
    }

    @Test fun epicFolder_isRecognisedWithoutAStoreTag() {
        assertEquals(true, isInEpicFolder("Z:\\epic_games\\Assassins Creed Syndicate\\ACS.exe"))
        assertEquals(true, isInEpicFolder("/data/user/0/app/files/imagefs/epic_games/Some Game/Game.exe"))
        assertEquals(true, isInEpicFolder("Z:\\EPIC_GAMES\\Game\\g.exe"))
        assertEquals(false, isInEpicFolder("Z:\\epic_games"))
        assertEquals(false, isInEpicFolder("Z:\\epic_games_old\\Game\\g.exe"))
        assertEquals(false, isInEpicFolder("Z:\\steam_games\\Game\\g.exe"))
        assertEquals(false, isInEpicFolder(null))
    }
}
