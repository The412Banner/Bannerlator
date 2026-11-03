package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReFrameworkTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun supportedExesFromWindowsAndUnixPaths() {
        assertTrue(ReFramework.isSupportedExe("F:\\winlator\\games\\RESIDENT EVIL 2 DX12\\re2.exe"))
        assertTrue(ReFramework.isSupportedExe("/storage/x/games/DMC5/DevilMayCry5.exe"))
        assertTrue(ReFramework.isSupportedExe("C:\\Games\\SF6\\StreetFighter6.EXE"))
        assertTrue(ReFramework.isSupportedExe("monster_hunter_stories_3_twisted_reflection.exe"))
        assertFalse(ReFramework.isSupportedExe("C:\\Games\\DOOM\\DOOMx64vk.exe"))
        assertFalse(ReFramework.isSupportedExe("fire2.exe"))
        assertFalse(ReFramework.isSupportedExe("re2"))
        assertFalse(ReFramework.isSupportedExe(null))
    }

    @Test fun overridesAddDinput8UnlessTheUserNamesIt() {
        assertEquals("dinput8=n,b", ReFramework.overrides(""))
        assertEquals("dinput8=n,b", ReFramework.overrides(null))
        assertEquals("xinput1_3=n,b;dinput8=n,b", ReFramework.overrides("xinput1_3=n,b"))
        assertEquals("dinput8=b", ReFramework.overrides("dinput8=b"))
        assertEquals("version,dinput8.dll=n,b", ReFramework.overrides("version,dinput8.dll=n,b"))
    }

    @Test fun menuStartsClosedAppendsOrRewritesTheTwoKeys() {
        assertEquals("REFrameworkConfig_RememberMenuState=true\r\nREFrameworkConfig_MenuOpen=false\r\n",
            ReFramework.menuStartsClosed(""))
        val cfg = "Camera_Enabled=false\r\nREFrameworkConfig_MenuOpen=true\r\nREFrameworkConfig_RememberMenuState=false\r\n"
        assertEquals("Camera_Enabled=false\r\nREFrameworkConfig_MenuOpen=false\r\nREFrameworkConfig_RememberMenuState=true\r\n",
            ReFramework.menuStartsClosed(cfg))
        val unix = "Camera_Enabled=false\n"
        assertEquals("Camera_Enabled=false\nREFrameworkConfig_RememberMenuState=true\nREFrameworkConfig_MenuOpen=false\n",
            ReFramework.menuStartsClosed(unix))
    }

    @Test fun applyMenuStartsClosedWritesOnlyWhenNeeded() {
        val dir = tmp.newFolder("game")
        assertTrue(ReFramework.applyMenuStartsClosed(dir))
        assertFalse(ReFramework.applyMenuStartsClosed(dir))
    }

    private fun bundled(content: String): Pair<File, String> {
        val f = File(tmp.root, "bundled-${content.hashCode()}.dll").apply { writeText(content) }
        return f to GameXInputDlls.sha256(f)
    }

    @Test fun installPlacesOursAndRemoveTakesItBack() {
        val dir = tmp.newFolder("game")
        val (dll, sha) = bundled("ours v1")
        val r = ReFramework.install(dir, dll, sha)
        assertTrue(r.loaded)
        assertEquals("ours v1", File(dir, "dinput8.dll").readText())
        assertEquals(sha, File(dir, ReFramework.MARKER).readText())
        assertEquals("dinput8.dll already in place", ReFramework.install(dir, dll, sha).note)
        assertEquals("removed our dinput8.dll", ReFramework.remove(dir))
        assertFalse(File(dir, "dinput8.dll").exists())
        assertFalse(File(dir, ReFramework.MARKER).exists())
        assertNull(ReFramework.remove(dir))
    }

    @Test fun olderOursIsUpdated() {
        val dir = tmp.newFolder("game")
        val (v1, sha1) = bundled("ours v1")
        val (v2, sha2) = bundled("ours v2")
        ReFramework.install(dir, v1, sha1)
        assertEquals("dinput8.dll updated", ReFramework.install(dir, v2, sha2).note)
        assertEquals("ours v2", File(dir, "dinput8.dll").readText())
        assertEquals(sha2, File(dir, ReFramework.MARKER).readText())
    }

    @Test fun theGamesOwnDinput8IsNeverTouched() {
        val dir = tmp.newFolder("game")
        File(dir, "DINPUT8.dll").writeText("someone else's mod")
        val (dll, sha) = bundled("ours v1")
        val r = ReFramework.install(dir, dll, sha)
        assertTrue(r.loaded)
        assertTrue(r.note.contains("its own"))
        assertEquals("someone else's mod", File(dir, "DINPUT8.dll").readText())
        assertFalse(File(dir, ReFramework.MARKER).exists())
        assertNull(ReFramework.remove(dir))
        assertEquals("someone else's mod", File(dir, "DINPUT8.dll").readText())
    }

    @Test fun aReplacedFileIsNotRemoved() {
        val dir = tmp.newFolder("game")
        val (dll, sha) = bundled("ours v1")
        ReFramework.install(dir, dll, sha)
        File(dir, "dinput8.dll").writeText("user swapped in another build")
        assertEquals("the game folder's own dinput8.dll left as it is", ReFramework.remove(dir))
        assertTrue(File(dir, "dinput8.dll").exists())
        assertFalse(File(dir, ReFramework.MARKER).exists())
    }
}
