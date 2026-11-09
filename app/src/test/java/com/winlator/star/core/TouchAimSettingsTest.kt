package com.winlator.star.core

import com.winlator.star.core.TouchAimSettings.AUTO
import com.winlator.star.core.TouchAimSettings.DIRECT
import com.winlator.star.core.TouchAimSettings.OFF
import com.winlator.star.core.TouchAimSettings.ON
import com.winlator.star.core.TouchAimSettings.TOUCHPAD
import com.winlator.star.core.TouchAimSettings.TOUCHSCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.function.Function

class TouchAimSettingsTest {
    private fun extras(vararg kv: Pair<String, String>): Function<String, String> {
        val m = mapOf(*kv)
        return Function { k -> m[k] ?: "" }
    }

    @Test
    fun appTouchCarriesOverCursorToTouchAndTheOldTouchscreenMode() {
        assertEquals(TOUCHPAD, TouchAimSettings.appTouch(null, false, false))
        assertEquals(DIRECT, TouchAimSettings.appTouch(null, true, false))
        assertEquals(DIRECT, TouchAimSettings.appTouch(null, false, true))
        assertEquals(TOUCHPAD, TouchAimSettings.appTouch(TOUCHPAD, true, true))
        assertEquals("touchscreen is per game only", TOUCHPAD, TouchAimSettings.appTouch(TOUCHSCREEN, false, false))
    }

    @Test
    fun appAimDefaultsToAuto() {
        assertEquals(AUTO, TouchAimSettings.appAimOf(null))
        assertEquals(AUTO, TouchAimSettings.appAimOf("1"))
        assertEquals(OFF, TouchAimSettings.appAimOf(OFF))
    }

    @Test
    fun oldRelativeMouseIsAimOn() {
        assertEquals(ON, TouchAimSettings.ownAim(extras("relativeMouse" to "1")))
        assertNull("off follows the app", TouchAimSettings.ownAim(extras("relativeMouse" to "0")))
        assertNull(TouchAimSettings.ownAim(extras()))
        assertEquals("aimMode wins", OFF, TouchAimSettings.ownAim(extras("aimMode" to OFF, "relativeMouse" to "1")))
    }

    @Test
    fun aimFollowsGameThenContainerThenApp() {
        assertEquals(OFF, TouchAimSettings.resolveAim(extras("aimMode" to OFF), extras("relativeMouse" to "1"), AUTO))
        assertEquals(ON, TouchAimSettings.resolveAim(extras(), extras("relativeMouse" to "1"), AUTO))
        assertEquals(AUTO, TouchAimSettings.resolveAim(extras("relativeMouse" to "0"), extras(), AUTO))
        assertEquals(ON, TouchAimSettings.resolveAim(null, extras("aimMode" to ON), OFF))
        assertEquals(OFF, TouchAimSettings.resolveAim(null, null, OFF))
    }

    @Test
    fun touchscreenIsTheOldSimTouchScreen() {
        assertEquals(TOUCHSCREEN, TouchAimSettings.gameTouch(extras("simTouchScreen" to "1", "touchMode" to DIRECT)))
        assertEquals(DIRECT, TouchAimSettings.gameTouch(extras("simTouchScreen" to "0", "touchMode" to DIRECT)))
        assertNull(TouchAimSettings.gameTouch(extras("simTouchScreen" to "0")))
        assertNull(TouchAimSettings.gameTouch(extras("touchMode" to "bogus")))
    }

    @Test
    fun touchFollowsGameThenContainerThenApp() {
        assertEquals(TOUCHSCREEN, TouchAimSettings.resolveTouch(extras("simTouchScreen" to "1"), extras("touchMode" to DIRECT), TOUCHPAD))
        assertEquals(DIRECT, TouchAimSettings.resolveTouch(extras(), extras("touchMode" to DIRECT), TOUCHPAD))
        assertEquals(TOUCHPAD, TouchAimSettings.resolveTouch(null, null, TOUCHPAD))
    }
}
