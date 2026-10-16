package com.winlator.star.winhandler

import org.junit.Assert.assertEquals
import org.junit.Test

class RegeditWatchTest {

    @Test fun doneOnceRegeditCameAndWent() {
        val w = LiveWineRegistry.RegeditWatch(0)
        assertEquals(LiveWineRegistry.RegeditWatch.State.WAITING, w.onRound(listOf("explorer.exe"), 100))
        assertEquals(LiveWineRegistry.RegeditWatch.State.WAITING, w.onRound(listOf("explorer.exe", "regedit.exe"), 200))
        assertEquals(LiveWineRegistry.RegeditWatch.State.DONE, w.onRound(listOf("explorer.exe"), 300))
    }

    @Test fun neverSeenCountsAsDoneAfterTheGrace() {
        val w = LiveWineRegistry.RegeditWatch(0)
        assertEquals(LiveWineRegistry.RegeditWatch.State.WAITING, w.onRound(listOf("game.exe"), 500))
        assertEquals(LiveWineRegistry.RegeditWatch.State.DONE, w.onRound(listOf("game.exe"), 600))
    }

    @Test fun stillRunningTimesOut() {
        val w = LiveWineRegistry.RegeditWatch(0)
        assertEquals(LiveWineRegistry.RegeditWatch.State.WAITING, w.onRound(listOf("REGEDIT.EXE"), 2999))
        assertEquals(LiveWineRegistry.RegeditWatch.State.TIMED_OUT, w.onRound(listOf("regedit.exe"), 3000))
    }
}
