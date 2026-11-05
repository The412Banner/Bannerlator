package com.winlator.star.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recent order: a game added now counts as the latest played until it is really played. */
class ShortcutOrderTest {

    @Test fun recentKey_aNewGameBeatsOlderLaunches() {
        val playedYesterday = ShortcutOrder.recentKey(1_000L, 0L)
        val addedToday = ShortcutOrder.recentKey(0L, 2_000L)
        assertTrue(addedToday > playedYesterday)
    }

    @Test fun recentKey_playingAGameMovesItAboveItsOwnAddedTime() {
        assertEquals(3_000L, ShortcutOrder.recentKey(3_000L, 2_000L))
        assertEquals(2_000L, ShortcutOrder.recentKey(1_000L, 2_000L))
    }

    @Test fun recentKey_olderShortcutsKeepTheirOrder() {
        // Neither value: sorts last, as before.
        assertEquals(0L, ShortcutOrder.recentKey(0L, 0L))
        assertEquals(5L, ShortcutOrder.recentKey(5L, 0L))
        assertEquals(0L, ShortcutOrder.recentKey(-1L, -1L))
    }

    @Test fun parse_readsMillisAndShrugsOffJunk() {
        assertEquals(1_700_000_000_000L, ShortcutOrder.parse("1700000000000"))
        assertEquals(42L, ShortcutOrder.parse(" 42 "))
        assertEquals(0L, ShortcutOrder.parse(null))
        assertEquals(0L, ShortcutOrder.parse(""))
        assertEquals(0L, ShortcutOrder.parse("yesterday"))
        assertEquals(0L, ShortcutOrder.parse("-5"))
    }

    @Test fun addedAtLine_isOneExtraDataLine() {
        assertEquals("addedAt=123\n", ShortcutOrder.addedAtLine(123L))
    }

    @Test fun withAddedAt_addsTheKeyUnderTheExistingSection() {
        val text = "[Desktop Entry]\nName=Game\nExec=wine C:\\\\game.exe\n\n[Extra Data]\ncontainer=1\n"
        val out = ShortcutOrder.withAddedAt(text, 99L)
        assertEquals(
            "[Desktop Entry]\nName=Game\nExec=wine C:\\\\game.exe\n\n[Extra Data]\naddedAt=99\ncontainer=1\n",
            out,
        )
    }

    @Test fun withAddedAt_replacesAnEarlierValueInsteadOfDoubling() {
        val text = "[Desktop Entry]\nName=Game\n\n[Extra Data]\naddedAt=5\nlastPlayed=7\n"
        val out = ShortcutOrder.withAddedAt(text, 99L)
        assertEquals(1, out.lines().count { it.startsWith("addedAt=") })
        assertTrue(out.contains("\naddedAt=99\n"))
        assertTrue(out.contains("\nlastPlayed=7\n"))
        assertFalse(out.contains("addedAt=5"))
    }

    @Test fun withAddedAt_addsTheSectionWhenTheFileHasNone() {
        val out = ShortcutOrder.withAddedAt("[Desktop Entry]\nName=Game\nExec=wine x.exe", 99L)
        assertEquals("[Desktop Entry]\nName=Game\nExec=wine x.exe\n\n[Extra Data]\naddedAt=99\n", out)
    }

    @Test fun withAddedAt_keepsWindowsLineEndingsOnTheOtherLines() {
        val out = ShortcutOrder.withAddedAt("[Desktop Entry]\r\nName=Game\r\n\r\n[Extra Data]\r\nuuid=abc\r\n", 99L)
        assertTrue(out.contains("Name=Game\r\n"))
        assertTrue(out.contains("uuid=abc\r\n"))
        assertTrue(out.contains("\naddedAt=99\n"))
    }
}
