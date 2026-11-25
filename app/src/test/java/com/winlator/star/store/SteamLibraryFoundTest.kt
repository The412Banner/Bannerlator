package com.winlator.star.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLibraryFoundTest {

    @Test
    fun valveToolsAreNotGames() {
        // Known component app ids, whatever the folder is called.
        assertFalse(SteamLibraryFound.isGame(228980, "Steamworks Shared"))
        assertFalse(SteamLibraryFound.isGame(1070560, "SteamLinuxRuntime_scout"))
        assertFalse(SteamLibraryFound.isGame(1391110, "SteamLinuxRuntime_soldier"))
        assertFalse(SteamLibraryFound.isGame(1628350, "SteamLinuxRuntime_sniper"))
        // The offline fallback: Valve's own folder names, for the ids we do not know yet.
        assertFalse(SteamLibraryFound.isGame(0, "SteamLinuxRuntime_medic"))
        assertFalse(SteamLibraryFound.isGame(0, "Steamworks Shared"))
        assertFalse(SteamLibraryFound.isGame(0, "Proton - Experimental"))
        assertFalse(SteamLibraryFound.isGame(0, "Proton 11.0(Arm64)"))
        assertFalse(SteamLibraryFound.isGame(0, "Fex"))
        // An absolute host path is judged by its last segment: the library prefix cannot hide a tool.
        assertFalse(
            SteamLibraryFound.isGame(
                0,
                "/storage/emulated/0/SteamLibrary/steamapps/common/SteamLinuxRuntime_sniper",
            ),
        )
        assertFalse(
            SteamLibraryFound.isGame(
                0,
                "/storage/emulated/0/SteamLibrary/steamapps/common/Proton 11.0(Arm64)",
            ),
        )
        // The owned path knows only the app's name; a tool listed there is caught by the name.
        assertFalse(SteamLibraryFound.isGame(0, "Fex-Emu", "Fex"))
    }

    @Test
    fun realGamesAreKept() {
        for ((id, dir) in listOf(
            570 to "Portal 2",
            1245620 to "ELDEN RING",
            1091500 to "Cyberpunk 2077",
            // A game whose folder starts with the word is not a Proton build.
            485000 to "Proton Pulse",
            // An absolute host path is judged by its last segment, which here names an ordinary game.
            0 to "/storage/emulated/0/SteamLibrary/steamapps/common/Portal 2",
        )) {
            assertTrue(dir, SteamLibraryFound.isGame(id, dir))
        }
    }
}
