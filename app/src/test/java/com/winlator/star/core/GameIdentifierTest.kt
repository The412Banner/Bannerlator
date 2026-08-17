package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Signal-chain coverage for [GameIdentifier] (Smart Game Import, Phase 1.1). Pure JVM: builds
 * fake game layouts in a temp dir and asserts the right appId/name/source/confidence come back.
 * The PE version-resource path is exercised on-device (real .exe), not here.
 */
class GameIdentifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun exeIn(dir: File, name: String = "game.exe"): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(0x4D, 0x5A)) } // "MZ" — not a real PE

    @Test
    fun steamAppIdTxt_besideExe_givesHighConfidenceAppId() {
        val dir = tmp.newFolder("Palworld")
        File(dir, "steam_appid.txt").writeText("1623730\n")
        val id = GameIdentifier.identify(exeIn(dir))
        assertEquals(1623730, id.appId)
        assertEquals(GameIdentifier.Source.STEAM_APPID_TXT, id.source)
        assertEquals(GameIdentifier.Confidence.HIGH, id.confidence)
    }

    @Test
    fun steamAppIdTxt_inSteamSettingsSubdir_isFound() {
        val dir = tmp.newFolder("Game")
        File(dir, "steam_settings").mkdirs()
        File(dir, "steam_settings/steam_appid.txt").writeText("480")
        assertEquals(480, GameIdentifier.identify(exeIn(dir)).appId)
    }

    @Test
    fun coldClientLoaderIni_appIdKey_isParsed() {
        val dir = tmp.newFolder("Cracked")
        File(dir, "ColdClientLoader.ini").writeText("[SteamClient]\nExe=game.exe\nAppId=427520\n")
        val id = GameIdentifier.identify(exeIn(dir))
        assertEquals(427520, id.appId)
        assertEquals(GameIdentifier.Source.STEAM_EMU_INI, id.source)
    }

    @Test
    fun steamManifestAcf_matchedByInstalldir_givesAppIdAndName() {
        // steamapps/common/Palworld/Pal/Binaries/Win64/Palworld-Win64-Shipping.exe
        val steamapps = tmp.newFolder("steamapps")
        File(steamapps, "appmanifest_1623730.acf").writeText(
            """
            "AppState"
            {
                "appid"        "1623730"
                "name"         "Palworld"
                "installdir"   "Palworld"
            }
            """.trimIndent()
        )
        // a decoy manifest that must NOT win
        File(steamapps, "appmanifest_570.acf").writeText(
            """"AppState" { "appid" "570" "name" "Dota 2" "installdir" "dota 2 beta" }"""
        )
        val win64 = File(steamapps, "common/Palworld/Pal/Binaries/Win64").apply { mkdirs() }
        val id = GameIdentifier.identify(exeIn(win64, "Palworld-Win64-Shipping.exe"))
        assertEquals(1623730, id.appId)
        assertEquals("Palworld", id.name)
        assertEquals(GameIdentifier.Source.STEAM_MANIFEST_ACF, id.source)
    }

    @Test
    fun gogInfo_givesNameAndId_highConfidence() {
        val dir = tmp.newFolder("Witcher3")
        File(dir, "goggame-1207658691.info").writeText(
            """{"gameId":"1207658691","name":"The Witcher 3: Wild Hunt","version":1}"""
        )
        val id = GameIdentifier.identify(exeIn(dir))
        // normalizeName() deliberately converts ":" to " - " (a colon is illegal in a Windows
        // filename), so the identified name never carries one. The test asserted the raw GOG
        // string and had never passed.
        assertEquals("The Witcher 3 - Wild Hunt", id.name)
        assertEquals("1207658691", id.gogId)
        assertNull(id.appId)
        assertEquals(GameIdentifier.Confidence.HIGH, id.confidence)
    }

    @Test
    fun acfNamePreferredOverGogName_whenBothPresent() {
        val steamapps = tmp.newFolder("steamapps")
        File(steamapps, "appmanifest_100.acf").writeText(""""AppState"{"appid" "100" "name" "SteamName" "installdir" "MyGame"}""")
        val gameDir = File(steamapps, "common/MyGame").apply { mkdirs() }
        File(gameDir, "goggame-9.info").writeText("""{"name":"GogName"}""")
        val id = GameIdentifier.identify(exeIn(gameDir))
        assertEquals("SteamName", id.name)
        assertEquals(100, id.appId)
    }

    @Test
    fun noMarkers_fallsBackToCleanedFolderName_forEngineBinary() {
        // UE shipping binary → the folder name is the real title, exe basename is noise.
        val dir = tmp.newFolder("Hades")
        val id = GameIdentifier.identify(exeIn(dir, "Hades-Win64-Shipping.exe"))
        assertEquals("Hades", id.name)
        assertEquals(GameIdentifier.Source.FILENAME, id.source)
        assertEquals(GameIdentifier.Confidence.LOW, id.confidence)
    }

    @Test
    fun fallbackName_stripsRepackTagsAndVersionTokens() {
        val dir = tmp.newFolder("Stardew Valley [FitGirl Repack] v1.5.4")
        val id = GameIdentifier.identify(exeIn(dir, "Stardew.exe"))
        assertEquals("Stardew", id.name) // exe basename wins (not an engine binary); folder tags irrelevant here
        // and a repack-style exe basename is cleaned too:
        val id2 = GameIdentifier.identify(exeIn(tmp.newFolder("g2"), "Game.CODEX.exe"))
        assertEquals(GameIdentifier.Source.FILENAME, id2.source)
    }

    @Test
    fun fltIni_gameSettingsAppId_isParsed() {
        // Real God of War (FLT release) layout: flt.ini holds the appid under [GameSettings].
        val dir = tmp.newFolder("GodOfWar")
        File(dir, "flt.ini").writeText("[GameSettings]\nAppId=1593500\nUserName=X\nBuildId=7969425\n")
        File(dir, "settings.ini").writeText("[Settings]\nVideoDevice=Wrapper\nMonitor=0\n") // no AppId -> ignored
        val id = GameIdentifier.identify(exeIn(dir, "GoW.exe"))
        assertEquals(1593500, id.appId)
        assertEquals(GameIdentifier.Confidence.HIGH, id.confidence)
    }

    @Test
    fun genericIni_withAppId_isFoundByBroadScan() {
        val dir = tmp.newFolder("g")
        File(dir, "whatever_crack.ini").writeText("[cfg]\nappid = 220\n")
        assertEquals(220, GameIdentifier.identify(exeIn(dir)).appId)
    }

    @Test
    fun bestName_prefersFileDescriptionWhenProductNameIsAbbreviation() {
        // God of War ships ProductName "GoW" but FileDescription "God of War".
        assertEquals(
            "God of War",
            PeVersionInfo.bestName(mapOf("ProductName" to "GoW", "FileDescription" to "God of War")),
        )
        // Normal case: ProductName is the full title -> keep it.
        assertEquals(
            "Cyberpunk 2077",
            PeVersionInfo.bestName(mapOf("ProductName" to "Cyberpunk 2077", "FileDescription" to "Cyberpunk 2077")),
        )
        assertNull(PeVersionInfo.bestName(null))
    }

    @Test
    fun name_isNormalizedToBeFilesystemSafeAndTidy() {
        // Colon → " - " (would otherwise be sanitized to '_' by the shortcut writer).
        val d1 = tmp.newFolder("ds")
        File(d1, "goggame-1.info").writeText("""{"name":"Dark Souls: Remastered"}""")
        assertEquals("Dark Souls - Remastered", GameIdentifier.identify(exeIn(d1)).name)
        // Trademark marks (symbol and text forms) stripped.
        val d2 = tmp.newFolder("gow2")
        File(d2, "goggame-2.info").writeText("""{"name":"God of War™"}""")
        assertEquals("God of War", GameIdentifier.identify(exeIn(d2)).name)
        val d3 = tmp.newFolder("ds3")
        File(d3, "goggame-3.info").writeText("""{"name":"DARK SOULS(TM): REMASTERED"}""")
        assertEquals("DARK SOULS - REMASTERED", GameIdentifier.identify(exeIn(d3)).name)
        // Trailing PE descriptors dropped (DiRT 3's FileDescription is "DiRT 3 Executable").
        val d4 = tmp.newFolder("dirt")
        File(d4, "goggame-4.info").writeText("""{"name":"DiRT 3 Executable"}""")
        assertEquals("DiRT 3", GameIdentifier.identify(exeIn(d4)).name)
    }

    @Test
    fun launcherExe_prefersFolderName_overLauncher() {
        // GTA V Enhanced: user picks the Rockstar launcher PlayGTAV.exe (no Steam id, PE = junk on
        // a real exe). Fallback must use the folder title, not "PlayGTAV".
        val dir = tmp.newFolder("Grand Theft Auto V Enhanced")
        val id = GameIdentifier.identify(exeIn(dir, "PlayGTAV.exe"))
        assertEquals("Grand Theft Auto V Enhanced", id.name)
        assertEquals(GameIdentifier.Source.FILENAME, id.source)
    }

    @Test
    fun launcherExe_withSeparator_prefersFolder_andStripsSiteTag() {
        val dir = tmp.newFolder("Grand-Theft-Auto-IV-AnkerGames")
        val id = GameIdentifier.identify(exeIn(dir, "Play - GTA IV.exe"))
        assertEquals("Grand Theft Auto IV", id.name) // "AnkerGames" site tag stripped
    }

    @Test
    fun nonLauncherExe_startingWithPlay_keepsItsOwnName() {
        // "PlayerUnknown" must NOT be treated as a launcher (would wrongly use the folder name).
        val dir = tmp.newFolder("SomeInstallFolder")
        val id = GameIdentifier.identify(exeIn(dir, "PlayerUnknown.exe"))
        assertEquals("PlayerUnknown", id.name)
    }

    @Test
    fun isJunkPeName_flagsLaunchersNotGames() {
        for (junk in listOf("Rockstar Games Launcher Redirector", "GSE", "Steam", "Launcher", "Epic Games Launcher")) {
            assertTrue("expected junk: $junk", GameIdentifier.isJunkPeName(junk))
        }
        for (real in listOf("God of War", "Grand Theft Auto V", "DARK SOULS: REMASTERED", "Hades")) {
            assertFalse("expected real: $real", GameIdentifier.isJunkPeName(real))
        }
    }

    @Test
    fun emptyWhenNothingIdentifiable() {
        val dir = tmp.newFolder("x")
        val id = GameIdentifier.identify(exeIn(dir, "Game.exe")) // engine-ish base → folder "x"
        // "x" is a valid (if useless) fallback name; the point is it must not crash and appId is null.
        assertNull(id.appId)
        assertTrue(id.confidence == GameIdentifier.Confidence.LOW)
    }
}
