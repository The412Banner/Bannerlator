package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
