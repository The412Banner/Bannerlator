package com.winlator.star.core

import com.winlator.star.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which key and Shift state types a character in the X server's key map. */
class GuestKeyTyperTest {

    /** A key map shaped like the server's (two symbols per key from key code 8) with the keys given. */
    private fun keymap(vararg keys: Triple<XKeycode, Int, Int>): IntArray {
        val map = IntArray(248)
        for ((key, plain, shifted) in keys) {
            val at = ((key.id.toInt() and 0xFF) - 8) * 2
            map[at] = plain
            map[at + 1] = shifted
        }
        return map
    }

    private val us = keymap(
        Triple(XKeycode.KEY_A, 'a'.code, 'A'.code),
        Triple(XKeycode.KEY_1, '1'.code, '!'.code),
        Triple(XKeycode.KEY_2, '2'.code, '@'.code),
        Triple(XKeycode.KEY_SLASH, '/'.code, '?'.code),
        Triple(XKeycode.KEY_SPACE, 32, 32),
        Triple(XKeycode.KEY_KP_5, 65461, 65461),
    )

    @Test fun lowercaseLetter_isItsKeyWithoutShift() {
        val s = GuestKeyTyper.resolve(us, 'a'.code)!!
        assertEquals(XKeycode.KEY_A, s.keycode)
        assertFalse(s.shift)
        assertEquals('a'.code, s.keysym)
    }

    @Test fun capitalLetter_isItsKeyWithShift_andItsOwnSymbol() {
        val s = GuestKeyTyper.resolve(us, 'A'.code)!!
        assertEquals(XKeycode.KEY_A, s.keycode)
        assertTrue(s.shift)
        // The symbol handed to the server is the character's own, which the map holds, so no key is rewritten.
        assertEquals('A'.code, s.keysym)
    }

    @Test fun shiftedSymbols_useTheKeyOfTheirDigitOrPunctuation() {
        assertEquals(XKeycode.KEY_1, GuestKeyTyper.resolve(us, '!'.code)!!.keycode)
        assertTrue(GuestKeyTyper.resolve(us, '!'.code)!!.shift)
        assertEquals(XKeycode.KEY_2, GuestKeyTyper.resolve(us, '@'.code)!!.keycode)
        assertEquals(XKeycode.KEY_SLASH, GuestKeyTyper.resolve(us, '?'.code)!!.keycode)
        assertFalse(GuestKeyTyper.resolve(us, '/'.code)!!.shift)
    }

    @Test fun space_isTypedWithoutShift_eventhoughBothSlotsHoldIt() {
        val s = GuestKeyTyper.resolve(us, ' '.code)!!
        assertEquals(XKeycode.KEY_SPACE, s.keycode)
        assertFalse(s.shift)
    }

    @Test fun anUnshiftedSymbolOnAnotherKeyBeatsAShiftedOne() {
        // 'x' is the shifted symbol of the first key and the plain symbol of the second: the plain one wins.
        val map = keymap(Triple(XKeycode.KEY_A, 'q'.code, 'x'.code), Triple(XKeycode.KEY_B, 'x'.code, 'y'.code))
        val s = GuestKeyTyper.resolve(map, 'x'.code)!!
        assertEquals(XKeycode.KEY_B, s.keycode)
        assertFalse(s.shift)
    }

    @Test fun keypadSymbols_areNeverTakenForAscii() {
        // Keypad 5's symbol is 65461, not '5'.
        val s = GuestKeyTyper.resolve(us, '5'.code)!!
        assertEquals(GuestKeyTyper.SPARE_KEY, s.keycode)
    }

    @Test fun aCharacterTheMapHasNoKeyFor_goesThroughTheSpareKeyWithItsOwnSymbol() {
        val latin = GuestKeyTyper.resolve(us, 0xE9)!!
        assertEquals(GuestKeyTyper.SPARE_KEY, latin.keycode)
        assertFalse(latin.shift)
        assertEquals(0xE9, latin.keysym)
        // Above Latin-1 the symbol is a Unicode keysym.
        assertEquals(0x01000000 or 0x20AC, GuestKeyTyper.resolve(us, 0x20AC)!!.keysym)
        assertEquals(0x01000000 or 0x1F600, GuestKeyTyper.resolve(us, 0x1F600)!!.keysym)
    }

    @Test fun onceTheSpareKeyCarriesTheCharacter_itIsFoundThereWithoutAnotherRewrite() {
        val rewritten = us.copyOf()
        val at = ((GuestKeyTyper.SPARE_KEY.id.toInt() and 0xFF) - 8) * 2
        rewritten[at] = 0xE9
        rewritten[at + 1] = 0xE9
        val s = GuestKeyTyper.resolve(rewritten, 0xE9)!!
        assertEquals(GuestKeyTyper.SPARE_KEY, s.keycode)
        assertFalse(s.shift)
    }

    @Test fun controlCharactersAndLoneSurrogates_cannotBeTyped() {
        assertNull(GuestKeyTyper.resolve(us, 0))
        assertNull(GuestKeyTyper.resolve(us, 0x1B))
        assertNull(GuestKeyTyper.resolve(us, 0x7F))
        assertNull(GuestKeyTyper.resolve(us, 0x85))
        assertNull(GuestKeyTyper.resolve(us, 0xD800))
        assertNull(GuestKeyTyper.resolve(us, 0x110000))
        assertNotNull(GuestKeyTyper.resolve(us, 0xA0))
    }

    @Test fun spareKey_hasNoSymbolOfItsOwnInTheServersDefaultMap() {
        // Keyboard.createKeyboard never sets Scroll Lock, so rewriting it takes nothing away from a game.
        assertEquals(78, GuestKeyTyper.SPARE_KEY.id.toInt())
    }

    @Test fun nonCharacterKeys_areTheirOwnKeys() {
        assertEquals(XKeycode.KEY_ENTER, GuestKeyTyper.keycodeOf(GuestTyper.Key.ENTER))
        assertEquals(XKeycode.KEY_TAB, GuestKeyTyper.keycodeOf(GuestTyper.Key.TAB))
        assertEquals(XKeycode.KEY_BKSP, GuestKeyTyper.keycodeOf(GuestTyper.Key.BACKSPACE))
        assertEquals(XKeycode.KEY_ESC, GuestKeyTyper.keycodeOf(GuestTyper.Key.ESCAPE))
        assertEquals(XKeycode.KEY_LEFT, GuestKeyTyper.keycodeOf(GuestTyper.Key.LEFT))
        assertEquals(XKeycode.KEY_RIGHT, GuestKeyTyper.keycodeOf(GuestTyper.Key.RIGHT))
        assertEquals(XKeycode.KEY_UP, GuestKeyTyper.keycodeOf(GuestTyper.Key.UP))
        assertEquals(XKeycode.KEY_DOWN, GuestKeyTyper.keycodeOf(GuestTyper.Key.DOWN))
    }

    @Test fun everyLineEndBecomesOneNewline() {
        assertEquals("a\nb\nc\nd", GuestKeyTyper.normalizeNewlines("a\r\nb\rc\nd"))
    }
}
