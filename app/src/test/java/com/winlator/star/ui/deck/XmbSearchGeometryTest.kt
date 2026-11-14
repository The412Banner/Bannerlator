package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB search popup's layout rules: side by side or stacked, and a size that always fits the window and stays above the keyboard. */
class XmbSearchGeometryTest {

    @Test fun landscapeWindowsGoSideBySide() {
        // A phone in landscape, in either direction (the window is the same size), and a handheld.
        assertTrue(XmbSearchGeometry.sideBySide(1143f, 514f))
        assertTrue(XmbSearchGeometry.sideBySide(800f, 450f))
    }

    @Test fun portraitAndNearlySquareWindowsStack() {
        assertFalse(XmbSearchGeometry.sideBySide(514f, 1112f))
        assertFalse(XmbSearchGeometry.sideBySide(600f, 600f))
        // A little under the ratio stacks, a little over goes side by side.
        assertFalse(XmbSearchGeometry.sideBySide(119f, 100f))
        assertTrue(XmbSearchGeometry.sideBySide(121f, 100f))
    }

    @Test fun panelWidth_marginsOnBothSidesUpToTheMax() {
        assertEquals(486f, XmbSearchGeometry.panelWidth(windowWidth = 514f, margin = 14f, max = 820f), 0.001f)
        assertEquals(820f, XmbSearchGeometry.panelWidth(windowWidth = 1143f, margin = 14f, max = 820f), 0.001f)
        assertEquals(0f, XmbSearchGeometry.panelWidth(windowWidth = 20f, margin = 14f, max = 820f), 0f)
    }

    @Test fun panelHeight_fillsTheWindowWhenNoKeyboardIsUp() {
        assertEquals(486f, XmbSearchGeometry.panelHeight(windowHeight = 514f, margin = 14f, keyboardUp = false), 0.001f)
    }

    @Test fun panelHeight_staysAboveTheKeyboardPanel() {
        val window = 514f
        val margin = 14f
        val height = XmbSearchGeometry.panelHeight(window, margin, keyboardUp = true)
        // The popup, with its margin above it, ends before the keyboard's panel begins.
        val keyboardTop = window * (1f - XmbKeyboardGeometry.PANEL_HEIGHT_SHARE)
        assertTrue(margin + height <= keyboardTop)
        assertTrue(height < XmbSearchGeometry.panelHeight(window, margin, keyboardUp = false))
    }

    @Test fun panelHeight_neverNegative() {
        assertEquals(0f, XmbSearchGeometry.panelHeight(windowHeight = 20f, margin = 14f, keyboardUp = false), 0f)
        assertEquals(0f, XmbSearchGeometry.panelHeight(windowHeight = 40f, margin = 14f, keyboardUp = true), 0f)
    }

    @Test fun fieldColumnWidth_isAShareHeldBetweenTheLimits() {
        assertEquals(380f, XmbSearchGeometry.fieldColumnWidth(innerWidth = 1000f, min = 200f, max = 400f), 0.001f)
        assertEquals(400f, XmbSearchGeometry.fieldColumnWidth(innerWidth = 2000f, min = 200f, max = 400f), 0.001f)
        assertEquals(200f, XmbSearchGeometry.fieldColumnWidth(innerWidth = 300f, min = 200f, max = 400f), 0.001f)
    }
}
