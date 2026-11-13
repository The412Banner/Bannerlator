package com.winlator.star.ui.deck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB home's "press B twice to exit": the timing window and which key-ups count as presses. No Android classes involved. */
class XmbHomeExitTest {

    // ---- Double-press window ----

    @Test
    fun theFirstPressDoesNotExit() {
        assertFalse(XmbHomeExit().press(10_000L))
    }

    @Test
    fun aSecondPressInsideTheWindowExits() {
        val exit = XmbHomeExit()
        assertFalse(exit.press(10_000L))
        assertTrue(exit.press(10_000L + XMB_EXIT_WINDOW_MS))
    }

    @Test
    fun aSecondPressAfterTheWindowIsANewFirstPress() {
        val exit = XmbHomeExit()
        assertFalse(exit.press(10_000L))
        assertFalse(exit.press(10_000L + XMB_EXIT_WINDOW_MS + 1))
        // ...and that one started a new window.
        assertTrue(exit.press(10_000L + XMB_EXIT_WINDOW_MS + 500))
    }

    @Test
    fun exitingConsumesTheFirstPress() {
        val exit = XmbHomeExit()
        exit.press(0L)
        assertTrue(exit.press(100L))
        // A third press right after starts over instead of exiting again.
        assertFalse(exit.press(200L))
    }

    @Test
    fun resetForgetsTheFirstPress() {
        val exit = XmbHomeExit()
        exit.press(1_000L)
        exit.reset()
        assertFalse(exit.press(1_200L))
    }

    @Test
    fun aClockThatWentBackwardsStartsOver() {
        val exit = XmbHomeExit()
        exit.press(5_000L)
        assertFalse(exit.press(4_000L))
    }

    // ---- Armed-down key bookkeeping ----

    @Test
    fun aTapThatArrivedUnconsumedCounts() {
        val arm = XmbBackKeyArm()
        assertFalse(arm.onKey(down = true, repeatCount = 0, canceled = false))
        assertTrue(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun aKeyUpWhoseDownWasConsumedByTheViewDoesNotCount() {
        // The Home view closed a menu on the key-down, so the shell never saw it; the key-up still arrives.
        val arm = XmbBackKeyArm()
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun aKeyUpCountsOnlyOnce() {
        val arm = XmbBackKeyArm()
        arm.onKey(down = true, repeatCount = 0, canceled = false)
        assertTrue(arm.onKey(down = false, repeatCount = 0, canceled = false))
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun repeatsOfAHeldKeyNeverArm() {
        // The first down was consumed (a menu closed); the repeats that follow reach the shell, but they are not a fresh press.
        val arm = XmbBackKeyArm()
        assertFalse(arm.onKey(down = true, repeatCount = 1, canceled = false))
        assertFalse(arm.onKey(down = true, repeatCount = 2, canceled = false))
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun aHeldKeyIsOnePress() {
        val arm = XmbBackKeyArm()
        assertFalse(arm.onKey(down = true, repeatCount = 0, canceled = false))
        assertFalse(arm.onKey(down = true, repeatCount = 1, canceled = false))
        assertFalse(arm.onKey(down = true, repeatCount = 2, canceled = false))
        assertTrue(arm.onKey(down = false, repeatCount = 0, canceled = false))
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun aCancelledKeyUpDoesNotCountAndDisarms() {
        val arm = XmbBackKeyArm()
        arm.onKey(down = true, repeatCount = 0, canceled = false)
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = true))
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun resetDropsAWaitingDown() {
        val arm = XmbBackKeyArm()
        arm.onKey(down = true, repeatCount = 0, canceled = false)
        arm.reset()
        assertFalse(arm.onKey(down = false, repeatCount = 0, canceled = false))
    }

    @Test
    fun twoTapsInARowExitThroughBothPieces() {
        val arm = XmbBackKeyArm()
        val exit = XmbHomeExit()
        var now = 1_000L
        fun tap(): Boolean {
            arm.onKey(down = true, repeatCount = 0, canceled = false)
            return arm.onKey(down = false, repeatCount = 0, canceled = false) && exit.press(now)
        }
        assertFalse(tap())
        now += 600
        assertTrue(tap())
    }
}
