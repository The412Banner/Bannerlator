package com.winlator.star.ui.deck

import com.winlator.star.ui.deck.GuestKeyboardAction.HIDE
import com.winlator.star.ui.deck.GuestKeyboardAction.NONE
import com.winlator.star.ui.deck.GuestKeyboardAction.SHOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the in-game XMB keyboard shows and hides: only on the user's say, and never together with a menu. */
class GuestKeyboardPolicyTest {

    private val policy = GuestKeyboardPolicy()

    @Test fun startsHidden() {
        assertFalse(policy.visible)
    }

    @Test fun toggle_opensThePanel() {
        assertEquals(SHOW, policy.onUserToggle())
        assertTrue(policy.visible)
    }

    @Test fun toggleAgain_closesIt() {
        policy.onUserToggle()
        assertEquals(HIDE, policy.onUserToggle())
        assertFalse(policy.visible)
    }

    @Test fun close_hidesAnOpenPanel() {
        policy.onUserToggle()
        assertEquals(HIDE, policy.close())
        assertFalse(policy.visible)
    }

    @Test fun close_whenHidden_doesNothing() {
        assertEquals(NONE, policy.close())
    }

    @Test fun aMenuOpening_takesThePanelDown() {
        policy.onUserToggle()
        assertEquals(HIDE, policy.onMenuOpen(true))
        assertFalse(policy.visible)
    }

    @Test fun aMenuOpening_whenHidden_doesNothing() {
        assertEquals(NONE, policy.onMenuOpen(true))
    }

    @Test fun thePanelCannotOpenOverAMenu() {
        policy.onMenuOpen(true)
        assertEquals(NONE, policy.onUserToggle())
        assertFalse(policy.visible)
    }

    @Test fun theMenuClosing_doesNotBringThePanelBack() {
        policy.onUserToggle()
        policy.onMenuOpen(true)
        assertEquals(NONE, policy.onMenuOpen(false))
        assertFalse(policy.visible)
    }

    @Test fun afterTheMenuCloses_thePanelOpensAgain() {
        policy.onMenuOpen(true)
        policy.onMenuOpen(false)
        assertEquals(SHOW, policy.onUserToggle())
    }

    @Test fun closedThenToggled_opensAgain() {
        policy.onUserToggle()
        policy.close()
        assertEquals(SHOW, policy.onUserToggle())
    }
}
