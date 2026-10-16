package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickPacingTest {

    @Test fun parseAndFormat() {
        assertEquals("auto:60", StickPacing.parse("auto:60")!!.format())
        assertEquals("auto:60", StickPacing.parse("auto")!!.format())
        assertEquals("fixed:125", StickPacing.parse("fixed")!!.format())
        assertEquals("fixed:90", StickPacing.parse(" Fixed:90 ")!!.format())
        assertEquals("off", StickPacing.parse("off")!!.format())
        assertNull(StickPacing.parse(null))
        assertNull(StickPacing.parse(""))
        assertNull(StickPacing.parse("auto:x"))
        assertNull(StickPacing.parse("sometimes"))
    }

    @Test fun clamps() {
        assertEquals(240, StickPacing.parse("auto:999")!!.hz)
        assertEquals(20, StickPacing.parse("auto:1")!!.hz)
        assertEquals(1000, StickPacing.parse("fixed:5000")!!.hz)
        assertEquals(30, StickPacing.parse("fixed:2")!!.hz)
        assertEquals(StickPacing.DEFAULT, StickPacing.parse("auto:0"))
    }
}
