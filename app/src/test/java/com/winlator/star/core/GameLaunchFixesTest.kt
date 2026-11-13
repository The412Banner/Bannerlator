package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLaunchFixesTest {
    @Test
    fun exeNameFromWindowsPath() {
        assertEquals("batmanac.exe", GameLaunchFixes.exeName("D:\\Batman Arkham City GOTY\\Binaries\\Win32\\BatmanAC.exe"))
    }

    @Test
    fun exeNameDropsQuotesAndArguments() {
        assertEquals("batmanac.exe", GameLaunchFixes.exeName("\"Z:\\games\\Binaries\\Win32\\BatmanAC.exe\" -nolauncher"))
    }

    @Test
    fun exeNameFromUnixPath() {
        assertEquals("batmanac.exe", GameLaunchFixes.exeName("/data/games/Batman/BatmanAC.exe"))
    }

    @Test
    fun exeNameOfNothing() {
        assertEquals("", GameLaunchFixes.exeName(null))
        assertEquals("", GameLaunchFixes.exeName("  "))
    }
}
