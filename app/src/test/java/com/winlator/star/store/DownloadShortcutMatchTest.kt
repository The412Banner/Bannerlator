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
        assertTrue(DownloadShortcutMatch.belongs(Store.STEAM, "220", null, root, "A:\\elsewhere\\game.exe") { extras[it] ?: "" })
        assertFalse(DownloadShortcutMatch.belongs(Store.STEAM, "221", null, root, "A:\\elsewhere\\game.exe") { extras[it] ?: "" })
    }

    @Test fun epic_matchedByItsAppNameOnlyWhenTaggedEpic() {
        val epic = mapOf("storeSource" to "epic", "epicAppName" to "Sugar")
        val other = mapOf("storeSource" to "gog", "epicAppName" to "Sugar")
        assertTrue(DownloadShortcutMatch.belongs(Store.EPIC, "Sugar", null, root, null) { epic[it] ?: "" })
        assertFalse(DownloadShortcutMatch.belongs(Store.EPIC, "Sugar", null, root, null) { other[it] ?: "" })
    }

    @Test fun exeInsideTheInstallFolder_belongs_whateverTheStore() {
        val install = "$root/steam_games/Half-Life 2"
        assertTrue(DownloadShortcutMatch.belongs(Store.GOG, "1", install, root, "A:\\steam_games\\Half-Life 2\\bin\\hl2.exe", none))
        assertTrue(DownloadShortcutMatch.belongs(Store.AMAZON, "x", install, root, "$install/hl2.exe", none))
    }

    @Test fun exeOutsideTheInstallFolder_doesNotBelong() {
        val install = "$root/steam_games/Half-Life 2"
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, root, "A:\\steam_games\\Half-Life 2 Episode One\\ep1.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder(install, root, "C:\\Program Files\\Other\\game.exe"))
    }

    @Test fun missingOrShortFolder_neverMatches() {
        assertFalse(DownloadShortcutMatch.insideInstallFolder(null, root, "A:\\games\\x.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder("", root, "A:\\games\\x.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder("/c", root, "C:\\c\\x.exe"))
        assertFalse(DownloadShortcutMatch.insideInstallFolder("$root/steam_games/G", root, null))
    }
}
