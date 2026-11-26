package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of XmbDialogKit: how big a dialog may be, how many columns its options get, and the progress / label helpers. */
class XmbDialogKitTest {

    @Test
    fun dialogNeverTakesMoreThanNinetyTwoPercentOfTheWidthOrEightyEightOfTheHeight() {
        val size = xmbDialogSize(screenW = 500f, screenH = 400f, wide = false)
        assertEquals(460f, size.width, 0.001f)
        assertEquals(352f, size.maxHeight, 0.001f)
    }

    @Test
    fun dialogWidthIsCappedOnAWideScreen() {
        assertEquals(XMB_DIALOG_MAX_WIDTH, xmbDialogSize(1200f, 700f, wide = false).width, 0.001f)
        assertEquals(XMB_DIALOG_MAX_WIDTH_WIDE, xmbDialogSize(1200f, 700f, wide = true).width, 0.001f)
    }

    @Test
    fun aWideDialogOnASmallScreenIsStillWithinTheScreen() {
        val size = xmbDialogSize(screenW = 400f, screenH = 300f, wide = true)
        assertTrue(size.width <= 400f * XMB_DIALOG_WIDTH_SHARE + 0.001f)
        assertTrue(size.maxHeight <= 300f * XMB_DIALOG_HEIGHT_SHARE + 0.001f)
    }

    @Test
    fun compactBelowTheThreshold() {
        assertTrue(xmbDialogSize(900f, XMB_DIALOG_COMPACT_BELOW - 1f, false).compact)
        assertFalse(xmbDialogSize(900f, XMB_DIALOG_COMPACT_BELOW, false).compact)
        assertFalse(xmbDialogSize(400f, 900f, false).compact)
    }

    @Test
    fun aDegenerateScreenGivesNoNegativeSize() {
        val size = xmbDialogSize(0f, 0f, false)
        assertEquals(0f, size.width, 0.001f)
        assertEquals(0f, size.maxHeight, 0.001f)
        assertTrue(size.compact)
    }

    @Test
    fun optionsUseTwoColumnsOnlyWhenWideEnoughAndThereIsMoreThanOne() {
        assertEquals(2, xmbOptionColumns(640f, 4))
        assertEquals(2, xmbOptionColumns(XMB_OPTION_GRID_MIN_WIDTH, 2))
        assertEquals(1, xmbOptionColumns(XMB_OPTION_GRID_MIN_WIDTH - 1f, 4))
        assertEquals(1, xmbOptionColumns(640f, 1))
        assertEquals(1, xmbOptionColumns(640f, 0))
    }

    @Test
    fun theLandscapeMethodPickerIsATwoByTwoGridAndThePortraitOneAList() {
        // A phone in landscape (800 x 360 dp, drawn at the Deck's 0.8: 1000 x 450) and in portrait (360 x 800: 450 x 1000).
        val landscape = xmbDialogSize(1000f, 450f, wide = false)
        val portrait = xmbDialogSize(450f, 1000f, wide = false)
        assertEquals(2, xmbOptionColumns(landscape.width, 4))
        assertEquals(1, xmbOptionColumns(portrait.width, 4))
        assertTrue(landscape.compact)
    }

    @Test
    fun backHintOnlyWhenClosableAndWideEnough() {
        assertTrue(xmbShowBackHint(XMB_BACK_HINT_MIN_WIDTH, dismissible = true))
        assertFalse(xmbShowBackHint(XMB_BACK_HINT_MIN_WIDTH - 1f, dismissible = true))
        assertFalse(xmbShowBackHint(900f, dismissible = false))
    }

    @Test
    fun progressFractionIsNullWithoutATotalAndClamped() {
        assertNull(xmbProgressFraction(3, 0))
        assertNull(xmbProgressFraction(0, -1))
        assertEquals(0.25f, xmbProgressFraction(1, 4)!!, 0.0001f)
        assertEquals(1f, xmbProgressFraction(9, 4)!!, 0.0001f)
        assertEquals(0f, xmbProgressFraction(-2, 4)!!, 0.0001f)
    }

    @Test
    fun percentRoundsAndClamps() {
        assertEquals(0, xmbPercent(0f))
        assertEquals(43, xmbPercent(0.426f))
        assertEquals(100, xmbPercent(1f))
        assertEquals(100, xmbPercent(1.7f))
        assertEquals(0, xmbPercent(-0.3f))
    }

    @Test
    fun countLabelPluralises() {
        assertEquals("1 game", xmbCountLabel(1, "game"))
        assertEquals("0 games", xmbCountLabel(0, "game"))
        assertEquals("12 apps", xmbCountLabel(12, "app"))
    }

    @Test
    fun addLabelFollowsTheCountAndTheRunningState() {
        assertEquals("Add", xmbAddLabel(0, running = false))
        assertEquals("Add 3", xmbAddLabel(3, running = false))
        assertEquals("Adding…", xmbAddLabel(3, running = true))
        assertEquals("Adding…", xmbAddLabel(0, running = true))
    }
}
