package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [SteamInstallPaths.installDirOf]: the game folder cut from a raw exec path, in both layouts. */
class SteamInstallPathsTest {

    @Test
    fun windowsSteamappsPathKeepsItsSeparatorsAndCasing() {
        assertEquals(
            "E:\\Games\\steamapps\\common\\DARK SOULS III",
            SteamInstallPaths.installDirOf("E:\\Games\\steamapps\\common\\DARK SOULS III\\Game\\DarkSoulsIII.exe"),
        )
    }

    @Test
    fun hostSteamappsPathCutsAtTheGameFolder() {
        assertEquals(
            "/storage/emulated/0/Games/steamapps/common/DARK SOULS III",
            SteamInstallPaths.installDirOf("/storage/emulated/0/Games/steamapps/common/DARK SOULS III/Game/DarkSoulsIII.exe"),
        )
    }

    @Test
    fun legacySteamGamesRootWorksForBothPathForms() {
        assertEquals(
            "Z:\\steam_games\\Half-Life 2",
            SteamInstallPaths.installDirOf("Z:\\steam_games\\Half-Life 2\\hl2.exe"),
        )
        assertEquals(
            "/data/steam_games/Half-Life 2",
            SteamInstallPaths.installDirOf("/data/steam_games/Half-Life 2/bin/hl2.exe"),
        )
    }

    @Test
    fun pathEndingAtTheGameFolderIsKeptWhole() {
        assertEquals(
            "E:\\Games\\steamapps\\common\\DARK SOULS III",
            SteamInstallPaths.installDirOf("E:\\Games\\steamapps\\common\\DARK SOULS III"),
        )
    }

    @Test
    fun rootNamesMustBeWholeSegments() {
        // A bare "steamapps" is not the layout; only "steamapps/common" is.
        assertNull(SteamInstallPaths.installDirOf("E:\\Games\\steamapps\\DARK SOULS III\\Game\\DarkSoulsIII.exe"))
        assertNull(SteamInstallPaths.installDirOf("E:\\Games\\mysteamapps\\common\\DARK SOULS III\\Game\\DarkSoulsIII.exe"))
    }

    @Test
    fun noRootOrNoFolderAfterItIsNull() {
        assertNull(SteamInstallPaths.installDirOf(null))
        assertNull(SteamInstallPaths.installDirOf(""))
        assertNull(SteamInstallPaths.installDirOf("E:\\Games\\DARK SOULS III\\Game\\DarkSoulsIII.exe"))
        assertNull(SteamInstallPaths.installDirOf("E:\\Games\\steamapps\\common"))
        assertNull(SteamInstallPaths.installDirOf("E:\\Games\\steam_games\\"))
    }
}
