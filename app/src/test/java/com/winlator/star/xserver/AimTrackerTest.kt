package com.winlator.star.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AimTrackerTest {
    private fun tracker(events: MutableList<Boolean> = mutableListOf()) =
        AimTracker().apply { setListener { events.add(it) } }

    @Test
    fun threeWarpsWithinASecondStartIt() {
        val events = mutableListOf<Boolean>()
        val t = tracker(events)
        t.onWarp(0); t.onWarp(16)
        assertFalse(t.isActive)
        t.onWarp(33)
        assertTrue(t.isActive)
        assertEquals(listOf(true), events)
    }

    @Test
    fun spreadOutWarpsDoNot() {
        val t = tracker()
        t.onWarp(0); t.onWarp(600); t.onWarp(1200); t.onWarp(1800)
        assertFalse(t.isActive)
    }

    @Test
    fun aSingleCentringDoesNot() {
        val t = tracker()
        t.onWarp(100)
        t.onMotion(500, 0, 150)
        assertFalse(t.isActive)
    }

    @Test
    fun idleKeepsItOn() {
        val t = tracker()
        t.onWarp(0); t.onWarp(16); t.onWarp(33)
        t.onMotion(0, 0, 60_000)
        assertTrue(t.isActive)
    }

    @Test
    fun motionWhileTheGameKeepsWarpingKeepsItOn() {
        val t = tracker()
        var now = 0L
        repeat(3) { t.onWarp(now); now += 16 }
        repeat(200) {
            t.onMotion(20, 5, now)
            now += 8
            if (it % 2 == 0) t.onWarp(now)
        }
        assertTrue(t.isActive)
    }

    @Test
    fun motionWithNoWarpBackEndsIt() {
        val events = mutableListOf<Boolean>()
        val t = tracker(events)
        t.onWarp(0); t.onWarp(16); t.onWarp(33)
        t.onMotion(30, 0, 200)
        assertTrue("too soon after the last warp", t.isActive)
        t.onMotion(30, 0, 500)
        assertFalse(t.isActive)
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun smallMotionDoesNotEndIt() {
        val t = tracker()
        t.onWarp(0); t.onWarp(16); t.onWarp(33)
        t.onMotion(10, 10, 2000)
        assertTrue(t.isActive)
    }

    @Test
    fun startsAgainAfterEnding() {
        val t = tracker()
        t.onWarp(0); t.onWarp(16); t.onWarp(33)
        t.onMotion(100, 0, 1000)
        assertFalse(t.isActive)
        t.onWarp(1100)
        assertFalse("old warps were forgotten", t.isActive)
        t.onWarp(1116); t.onWarp(1133)
        assertTrue(t.isActive)
    }

    @Test
    fun onAndOffNeverDetect() {
        for (mode in listOf(AimTracker.MODE_ON, AimTracker.MODE_OFF)) {
            val t = tracker()
            t.setMode(mode)
            t.onWarp(0); t.onWarp(16); t.onWarp(33); t.onWarp(50)
            assertFalse(t.isActive)
        }
    }

    @Test
    fun leavingAutoWhileActiveReportsOff() {
        val events = mutableListOf<Boolean>()
        val t = tracker(events)
        t.onWarp(0); t.onWarp(16); t.onWarp(33)
        t.setMode(AimTracker.MODE_OFF)
        assertFalse(t.isActive)
        assertEquals(listOf(true, false), events)
    }
}
