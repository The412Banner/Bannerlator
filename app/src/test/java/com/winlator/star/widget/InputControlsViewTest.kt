package com.winlator.star.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControlsViewTest {
    @Test
    fun mouseMoveTimerRunsOnlyWhileMovementIsActive() {
        assertFalse(InputControlsView.shouldRunMouseMoveTimer(0f, 0f, false))
        assertTrue(InputControlsView.shouldRunMouseMoveTimer(1f, 0f, false))
        assertTrue(InputControlsView.shouldRunMouseMoveTimer(0f, -1f, false))
        assertTrue(InputControlsView.shouldRunMouseMoveTimer(0f, 0f, true))
    }
}
