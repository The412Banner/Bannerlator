package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB keyboard's key measurements: the keys and the lines between and around them must add up to the grid. */
class XmbKeyboardGeometryTest {

    @Test fun keyHeight_rowsAndLinesFillTheGrid() {
        val key = XmbKeyboardGeometry.keyHeight(gridHeight = 106f, rows = 5, line = 1f)
        assertEquals(20f, key, 0.001f)
        // 5 keys plus the 6 lines (between the rows and above and below them).
        assertEquals(106f, key * 5 + 1f * 6, 0.001f)
    }

    @Test fun keyHeight_neverNegative() {
        assertEquals(0f, XmbKeyboardGeometry.keyHeight(gridHeight = 3f, rows = 5, line = 1f), 0f)
        assertEquals(0f, XmbKeyboardGeometry.keyHeight(gridHeight = 100f, rows = 0, line = 200f), 0f)
    }

    @Test fun unitKeyWidth_weightsAndLinesFillTheRow() {
        val unit = XmbKeyboardGeometry.unitKeyWidth(innerWidth = 150f, units = 15f, lines = 15, line = 1f)
        assertEquals(9f, unit, 0.001f)
        assertEquals(150f, unit * 15f + 1f * 15, 0.001f)
    }

    @Test fun unitKeyWidth_neverNegative() {
        assertEquals(0f, XmbKeyboardGeometry.unitKeyWidth(innerWidth = 5f, units = 15f, lines = 16, line = 1f), 0f)
    }

    @Test fun labelUnit_followsTheSmallerSideAndGrowsWithTheKey() {
        // Tall and narrow: the width share decides. Short and wide: the height decides.
        assertEquals(10f * 1.35f * 1.15f, XmbKeyboardGeometry.labelUnit(keyHeight = 100f, unitKeyWidth = 10f), 0.001f)
        assertEquals(20f * 1.15f, XmbKeyboardGeometry.labelUnit(keyHeight = 20f, unitKeyWidth = 100f), 0.001f)
        // A bigger key never gets smaller text.
        assertTrue(XmbKeyboardGeometry.labelUnit(30f, 30f) > XmbKeyboardGeometry.labelUnit(25f, 30f))
        assertTrue(XmbKeyboardGeometry.labelUnit(30f, 30f) > XmbKeyboardGeometry.labelUnit(30f, 20f))
    }
}
