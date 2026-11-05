package com.winlator.star.ui.deck

import android.view.KeyEvent
import com.winlator.star.ui.deck.GuestKeyboardCombo.Verdict.CONSUME
import com.winlator.star.ui.deck.GuestKeyboardCombo.Verdict.PASS
import com.winlator.star.ui.deck.GuestKeyboardCombo.Verdict.TOGGLE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Select + X on a pad: what takes part in the combo and what goes through untouched. */
class GuestKeyboardComboTest {

    private val combo = GuestKeyboardCombo()
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP
    private val select = KeyEvent.KEYCODE_BUTTON_SELECT
    private val x = KeyEvent.KEYCODE_BUTTON_X

    private fun key(code: Int, action: Int, repeat: Int = 0, pad: Boolean = true, madeFromB: Boolean = false, armed: Boolean = true) =
        combo.onKey(code, action, repeat, pad, madeFromB, armed)

    @Test fun xWhileSelectIsHeld_toggles() {
        assertEquals(PASS, key(select, down))
        assertEquals(TOGGLE, key(x, down))
    }

    @Test fun theComboXRelease_isTakenToo() {
        key(select, down)
        key(x, down)
        assertEquals(CONSUME, key(x, up))
        assertEquals(PASS, key(select, up))
    }

    @Test fun theComboXRepeats_areTaken() {
        key(select, down)
        key(x, down)
        assertEquals(CONSUME, key(x, down, repeat = 1))
        assertEquals(CONSUME, key(x, down, repeat = 2))
        assertEquals(CONSUME, key(x, up))
    }

    @Test fun selectAlone_passesBothWays() {
        assertEquals(PASS, key(select, down))
        assertEquals(PASS, key(select, down, repeat = 1))
        assertEquals(PASS, key(select, up))
    }

    @Test fun xAlone_passes() {
        assertEquals(PASS, key(x, down))
        assertEquals(PASS, key(x, down, repeat = 1))
        assertEquals(PASS, key(x, up))
    }

    @Test fun xThenSelect_isNotACombo() {
        assertEquals(PASS, key(x, down))
        assertEquals(PASS, key(select, down))
        assertEquals(PASS, key(x, down, repeat = 1))
        assertEquals(PASS, key(x, up))
    }

    @Test fun releaseOrder_selectFirst_keepsTheXTakenUntilItsRelease() {
        key(select, down)
        key(x, down)
        assertEquals(PASS, key(select, up))
        assertEquals(CONSUME, key(x, down, repeat = 1))
        assertEquals(CONSUME, key(x, up))
        assertEquals(PASS, key(x, down))
    }

    @Test fun releaseOrder_xFirst_thenSelectPasses() {
        key(select, down)
        key(x, down)
        assertEquals(CONSUME, key(x, up))
        assertEquals(PASS, key(select, up))
        assertEquals(PASS, key(x, down))
    }

    @Test fun selectReleased_xDoesNotToggleAnymore() {
        key(select, down)
        key(select, up)
        assertEquals(PASS, key(x, down))
    }

    @Test fun secondComboAfterTheFirst_toggles() {
        key(select, down)
        key(x, down)
        key(x, up)
        assertEquals(TOGGLE, key(x, down))
        assertEquals(CONSUME, key(x, up))
    }

    @Test fun aRepeatedXWithSelectHeld_doesNotToggle() {
        key(select, down)
        assertEquals(PASS, key(x, down, repeat = 1))
    }

    @Test fun thePadsBack_countsAsSelect() {
        assertEquals(PASS, key(KeyEvent.KEYCODE_BACK, down))
        assertEquals(TOGGLE, key(x, down))
        key(x, up)
        assertEquals(PASS, key(KeyEvent.KEYCODE_BACK, up))
    }

    @Test fun thePhonesBack_isNotSelect() {
        assertEquals(PASS, key(KeyEvent.KEYCODE_BACK, down, pad = false))
        assertEquals(PASS, key(x, down))
    }

    @Test fun aPadsBackMadeFromB_isNotSelect() {
        assertEquals(PASS, key(KeyEvent.KEYCODE_BACK, down, madeFromB = true))
        assertEquals(PASS, key(x, down))
    }

    @Test fun anXFromAnotherDevice_isNotTaken() {
        key(select, down)
        assertEquals(PASS, key(x, down, pad = false))
        assertEquals(PASS, key(x, up, pad = false))
    }

    @Test fun otherKeys_passAndKeepTheState() {
        key(select, down)
        assertEquals(PASS, key(KeyEvent.KEYCODE_BUTTON_A, down))
        assertEquals(PASS, key(KeyEvent.KEYCODE_BUTTON_Y, down))
        assertEquals(TOGGLE, key(x, down))
    }

    @Test fun whenNotArmed_xPassesButSelectIsStillFollowed() {
        key(select, down, armed = false)
        assertEquals(PASS, key(x, down, armed = false))
        assertEquals(PASS, key(x, up, armed = false))
        assertEquals(TOGGLE, key(x, down))
    }

    @Test fun anXAlreadyTaken_isFinishedEvenWhenTheMenuOpensMeanwhile() {
        key(select, down)
        key(x, down)
        assertEquals(CONSUME, key(x, up, armed = false))
    }

    @Test fun reset_forgetsAHeldSelect() {
        key(select, down)
        combo.reset()
        assertEquals(PASS, key(x, down))
    }

    @Test fun reset_forgetsATakenX() {
        key(select, down)
        key(x, down)
        combo.reset()
        assertEquals(PASS, key(x, up))
    }

    @Test fun otherActions_passOn() {
        key(select, down)
        assertEquals(PASS, key(x, KeyEvent.ACTION_MULTIPLE))
    }
}
