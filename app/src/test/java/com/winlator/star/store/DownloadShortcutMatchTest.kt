package com.winlator.star.store

import com.winlator.star.store.download.Store
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which shortcuts go when a game is uninstalled from the Download Manager. */
class DownloadShortcutMatchTest {

    private val root = "/data/user/0/com.winlator.star/files/imagefs"
    private val none: (String) -> String = { "" }

    @Test fun steam_matchedByItsAppIdExtra() {
        val extras = mapOf("steamAppId" to "220")
        assertTrue(DownloadShortcutMatch.belongs(Store.STEAM, "220", null, "/elsewhere/game.exe") { extras[it] ?: "" })
        assertFalse(DownloadShortcutMatch.belongs(Store.STEAM, "221", null, "/elsewhere/game.exe") { extras[it] ?: "" })
    }

    @Test fun epic_matchedByItsAppNameOnlyWhenTaggedEpic() {
        val epic = mapOf("storeSource" to "epic", "epicAppName" to "Sugar")
        val other = mapOf("storeSource" to "gog", "epicAppName" to "Sugar")
        assertTrue(DownloadShortcutMatch.belongs(Store.EPIC, "Sugar", null, null) { epic[it] ?: "" })
        assertFalse(DownloadShortcutMatch.belongs(Store.EPIC, "Sugar", null, null) { other[it] ?: "" })
    }

    @Test fun exeInsideTheInstallFolder_belongs_whateverTheStore() {
        val install = "$root/steam_games/Half-Life 2"
        assertTrue(DownloadShortcutMatch.belongs(Store.GOG, "1", install, "$install/bin/hl2.exe", none))
        assertTrue(DownloadShortcutMatch.belongs(Store.AMAZON, "x", install, "$install/hl2.exe", none))
    }

    @Test fun exeInsideTheInstallFolder_isCaseInsensitive() {
        val install = "$root/steam_games/Half-Life 2"
        assertTrue(DownloadShortcutMatch.insideInstallFolder(install, "$root/STEAM_GAMES/half-life 2/HL2.EXE"))
        assertTrue(DownloadShortcutMatch.insideInstallFolder("$install/", "$install/hl2.exe"))
    }

    @Test fun exeOutsideTheInstallFolder_doesNotBelong() {
        val install = "$root/steam_games/Half-Life 2"
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, "$root/steam_games/Other/game.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, "$root/drive_c/Program Files/Other/game.exe"))
    }

    @Test fun siblingWithTheSamePrefix_doesNotBelong() {
        val install = "$root/steam_games/Half-Life 2"
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, "$root/steam_games/Half-Life 2 Episode One/ep1.exe"))
    }

    @Test fun backupCopyOfTheFolderName_doesNotBelong() {
        val install = "$root/steam_games/Half-Life 2"
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, "$root/backup/steam_games/Half-Life 2/hl2.exe"))
    }

    @Test fun sameFolderNameOnAnotherVolume_doesNotBelong() {
        val install = "$root/steam_games/Half-Life 2"
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, "/storage/1234-5678/steam_games/Half-Life 2/hl2.exe"))
    }

    @Test fun theFolderItself_isNotInsideItself() {
        val install = "$root/steam_games/Half-Life 2"
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, install))
    }

    @Test fun missingOrShortFolder_neverMatches() {
        assertFalse(DownloadShortcutMatch.insideInstallFolder(null, "/games/x.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder("", "/games/x.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder("/c", "/c/x.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder("$root/steam_games/G", null))
    }
}
