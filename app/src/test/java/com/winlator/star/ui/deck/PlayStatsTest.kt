package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recent order's key, and that adding a game does not make it look played. */
class PlayStatsTest {

    @Test fun recentKey_aGameAddedAfterTheLastLaunchGoesFirst() {
        val played = PlayStats(ms = 60_000L, count = 3, lastPlayed = 1_000L)
        val added = PlayStats(ms = 0L, count = 0, lastPlayed = 0L, addedAt = 2_000L)
        assertTrue(added.recentKey > played.recentKey)
    }

    @Test fun recentKey_playingMovesTheGameBackToTheTop() {
        val replayed = PlayStats(ms = 1L, count = 1, lastPlayed = 5_000L, addedAt = 2_000L)
        assertEquals(5_000L, replayed.recentKey)
    }

    @Test fun recentKey_olderShortcutsWithoutEitherValueSortLast() {
        assertEquals(0L, PlayStats(0L, 0, 0L).recentKey)
    }

    @Test fun addedAt_isNotALaunch() {
        // The "was it ever played" checks read lastPlayed, which adding a game leaves at 0.
        val added = PlayStats(ms = 0L, count = 0, lastPlayed = 0L, addedAt = 2_000L)
        assertEquals(0L, added.lastPlayed)
    }
}
