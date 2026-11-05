package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Test

/** The preview strip's echo of what was typed into the game. */
class GuestEchoTest {

    @Test fun typing_appendsAndMovesTheCursor() {
        val echo = GuestEcho()
        echo.insert("ab")
        echo.insert("c")
        assertEquals("abc", echo.text)
        assertEquals(3, echo.cursor)
    }

    @Test fun backspace_removesTheCharacterBeforeTheCursor() {
        val echo = GuestEcho()
        echo.insert("abc")
        echo.backspace()
        assertEquals("ab", echo.text)
        assertEquals(2, echo.cursor)
    }

    @Test fun backspaceAtTheStart_doesNothing() {
        val echo = GuestEcho()
        echo.backspace()
        assertEquals("", echo.text)
        assertEquals(0, echo.cursor)
    }

    @Test fun cursorKeys_moveWithinTheText_andTypingInsertsThere() {
        val echo = GuestEcho()
        echo.insert("ac")
        echo.move(-1)
        echo.insert("b")
        assertEquals("abc", echo.text)
        assertEquals(2, echo.cursor)
        echo.move(10)
        assertEquals(3, echo.cursor)
        echo.move(-10)
        assertEquals(0, echo.cursor)
    }

    @Test fun backspaceOverACharacterOutsideTheBasicPlane_removesBothHalves() {
        val echo = GuestEcho()
        echo.insert("a😀")
        echo.backspace()
        assertEquals("a", echo.text)
        assertEquals(1, echo.cursor)
    }

    @Test fun onlyTheRecentEndIsKept() {
        val echo = GuestEcho(limit = 5)
        echo.insert("abcdefgh")
        assertEquals("defgh", echo.text)
        assertEquals(5, echo.cursor)
    }
}
