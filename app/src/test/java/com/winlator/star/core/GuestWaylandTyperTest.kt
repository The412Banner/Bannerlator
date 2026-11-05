package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The US-layout key table the Wayland typer uses when no program in the guest takes text. */
class GuestWaylandTyperTest {

    @Test fun letters_areOnTheirEvdevKeys() {
        assertEquals(30, GuestWaylandTyper.resolve('a'.code)!!.evdev)
        assertEquals(16, GuestWaylandTyper.resolve('q'.code)!!.evdev)
        assertEquals(44, GuestWaylandTyper.resolve('z'.code)!!.evdev)
        assertEquals(50, GuestWaylandTyper.resolve('m'.code)!!.evdev)
        assertFalse(GuestWaylandTyper.resolve('a'.code)!!.shift)
    }

    @Test fun capitals_areTheSameKeyWithShift() {
        val s = GuestWaylandTyper.resolve('A'.code)!!
        assertEquals(30, s.evdev)
        assertTrue(s.shift)
    }

    @Test fun digitsAndTheirSymbols() {
        assertEquals(2, GuestWaylandTyper.resolve('1'.code)!!.evdev)
        assertEquals(11, GuestWaylandTyper.resolve('0'.code)!!.evdev)
        assertEquals(2, GuestWaylandTyper.resolve('!'.code)!!.evdev)
        assertTrue(GuestWaylandTyper.resolve('!'.code)!!.shift)
        assertEquals(3, GuestWaylandTyper.resolve('@'.code)!!.evdev)
        assertEquals(10, GuestWaylandTyper.resolve('('.code)!!.evdev)
        assertEquals(11, GuestWaylandTyper.resolve(')'.code)!!.evdev)
    }

    @Test fun punctuation() {
        assertEquals(12, GuestWaylandTyper.resolve('-'.code)!!.evdev)
        assertEquals(13, GuestWaylandTyper.resolve('='.code)!!.evdev)
        assertEquals(13, GuestWaylandTyper.resolve('+'.code)!!.evdev)
        assertEquals(26, GuestWaylandTyper.resolve('['.code)!!.evdev)
        assertEquals(27, GuestWaylandTyper.resolve('}'.code)!!.evdev)
        assertEquals(39, GuestWaylandTyper.resolve(';'.code)!!.evdev)
        assertEquals(39, GuestWaylandTyper.resolve(':'.code)!!.evdev)
        assertEquals(40, GuestWaylandTyper.resolve('\''.code)!!.evdev)
        assertEquals(40, GuestWaylandTyper.resolve('"'.code)!!.evdev)
        assertEquals(41, GuestWaylandTyper.resolve('`'.code)!!.evdev)
        assertEquals(41, GuestWaylandTyper.resolve('~'.code)!!.evdev)
        assertEquals(43, GuestWaylandTyper.resolve('\\'.code)!!.evdev)
        assertEquals(43, GuestWaylandTyper.resolve('|'.code)!!.evdev)
        assertEquals(51, GuestWaylandTyper.resolve(','.code)!!.evdev)
        assertEquals(52, GuestWaylandTyper.resolve('.'.code)!!.evdev)
        assertEquals(53, GuestWaylandTyper.resolve('/'.code)!!.evdev)
        assertEquals(53, GuestWaylandTyper.resolve('?'.code)!!.evdev)
        assertEquals(57, GuestWaylandTyper.resolve(' '.code)!!.evdev)
        assertFalse(GuestWaylandTyper.resolve(' '.code)!!.shift)
    }

    @Test fun everyPrintableAscii_hasAKey_andNothingElseDoes() {
        for (c in 0x20..0x7E) assertNotNull("char $c", GuestWaylandTyper.resolve(c))
        assertNull(GuestWaylandTyper.resolve(0xE9))
        assertNull(GuestWaylandTyper.resolve(0x1F))
        assertNull(GuestWaylandTyper.resolve(0x7F))
        assertNull(GuestWaylandTyper.resolve(-1))
    }

    @Test fun nonCharacterKeys() {
        assertEquals(28, GuestWaylandTyper.evdevOf(GuestTyper.Key.ENTER))
        assertEquals(15, GuestWaylandTyper.evdevOf(GuestTyper.Key.TAB))
        assertEquals(14, GuestWaylandTyper.evdevOf(GuestTyper.Key.BACKSPACE))
        assertEquals(1, GuestWaylandTyper.evdevOf(GuestTyper.Key.ESCAPE))
        assertEquals(105, GuestWaylandTyper.evdevOf(GuestTyper.Key.LEFT))
        assertEquals(106, GuestWaylandTyper.evdevOf(GuestTyper.Key.RIGHT))
        assertEquals(103, GuestWaylandTyper.evdevOf(GuestTyper.Key.UP))
        assertEquals(108, GuestWaylandTyper.evdevOf(GuestTyper.Key.DOWN))
    }
}
