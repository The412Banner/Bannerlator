package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnipGameOptionsTest {

    @Test fun mio_getsSsboEmulation() {
        val env = EnvVars("TU_DEBUG=noconform,sysmem")
        val added = TurnipGameOptions.applyToEnv(env, "mio.exe")
        assertEquals(listOf("tu_enable_ssbo_emulation=true"), added)
        assertEquals("true", env.get("tu_enable_ssbo_emulation"))
        assertEquals("noconform,sysmem", env.get("TU_DEBUG"))
    }

    @Test fun uncharted_allFourExes_getSsboEmulation() {
        for (exe in listOf("u4.exe", "tll.exe", "u4-l.exe", "tll-l.exe")) {
            assertEquals(exe, listOf("tu_enable_ssbo_emulation" to "true"), TurnipGameOptions.forExe(exe))
        }
    }

    @Test fun exeMatch_ignoresCase() {
        assertEquals(1, TurnipGameOptions.forExe("MIO.EXE").size)
    }

    @Test fun userValue_wins() {
        val env = EnvVars("tu_enable_ssbo_emulation=false")
        assertTrue(TurnipGameOptions.applyToEnv(env, "mio.exe").isEmpty())
        assertEquals("false", env.get("tu_enable_ssbo_emulation"))
    }

    @Test fun otherGames_unchanged() {
        val env = EnvVars("DXVK_HUD=fps")
        assertTrue(TurnipGameOptions.applyToEnv(env, "re2.exe").isEmpty())
        assertTrue(TurnipGameOptions.applyToEnv(env, null).isEmpty())
        assertEquals("DXVK_HUD=fps", env.toString())
    }
}
