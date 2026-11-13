package com.winlator.star.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of XmbCardKit: the room a list leaves for its cards' shadow, and which key closes a popup menu. */
class XmbCardKitTest {

    @Test
    fun roomAtLeastRaisesASmallerValue() {
        assertEquals(8.dp, xmbRoomAtLeast(0.dp, XMB_LIST_ROOM_TOP))
        assertEquals(16.dp, xmbRoomAtLeast(4.dp, XMB_LIST_ROOM_BOTTOM))
    }

    @Test
    fun roomAtLeastKeepsALargerValue() {
        assertEquals(24.dp, xmbRoomAtLeast(24.dp, XMB_LIST_ROOM_BOTTOM))
        assertEquals(8.dp, xmbRoomAtLeast(8.dp, XMB_LIST_ROOM_TOP))
    }

    @Test
    fun listRoomCoversTheShadowReach() {
        // The shadow reaches 6 dp above a card and 14 dp below it (see xmbShadowLayers).
        assertTrue(XMB_LIST_ROOM_TOP >= 6.dp)
        assertTrue(XMB_LIST_ROOM_BOTTOM >= 14.dp)
    }

    @Test
    fun cardMarginsLeaveTwelveDpBetweenCards() {
        // Lists put at least 10 dp between cards (a Spacer) or give them 6 dp each side: with the card margin that is >= 12 dp.
        assertTrue(XMB_CARD_GAP * 2 + 10.dp >= 12.dp)
    }

    @Test
    fun onlyTheControllerBClosesAMenuBeyondWhatThePopupDoes() {
        assertTrue(xmbMenuBackKey(Key.ButtonB))
        // Back and Escape dismiss a Compose popup themselves; A and the D-pad are the menu's own business.
        assertFalse(xmbMenuBackKey(Key.Back))
        assertFalse(xmbMenuBackKey(Key.Escape))
        assertFalse(xmbMenuBackKey(Key.ButtonA))
        assertFalse(xmbMenuBackKey(Key.DirectionDown))
    }
}
