package com.winlator.star.ui.deck

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the XMB highlight and the two-level focus group: which item is hot, when the outline glides or snaps,
 * the colour the ink takes, and what the group does with A / B. No Compose runtime or Android classes involved.
 */
class XmbHighlightTest {

    // ---- Which focused item is hot ----

    @Test
    fun theInnermostFocusedItemIsHot() {
        // A card (big) holds focus because one of its buttons (small) does: the button is hot.
        assertEquals(1, xmbPickHot(floatArrayOf(40_000f, 2_000f)))
        // Claim order does not matter, size does.
        assertEquals(0, xmbPickHot(floatArrayOf(2_000f, 40_000f)))
        // Three levels (a page card, a row in it, a button in the row): the button.
        assertEquals(2, xmbPickHot(floatArrayOf(900_000f, 40_000f, 2_000f)))
    }

    @Test
    fun equalSizesGoToTheLaterClaim() {
        assertEquals(1, xmbPickHot(floatArrayOf(5_000f, 5_000f)))
        assertEquals(2, xmbPickHot(floatArrayOf(9_000f, 5_000f, 5_000f)))
    }

    @Test
    fun nothingFocusedMeansNoHotItem() {
        assertEquals(-1, xmbPickHot(floatArrayOf()))
        // An item that has not been laid out yet has the biggest possible area, so a laid-out one beats it.
        assertEquals(1, xmbPickHot(floatArrayOf(Float.MAX_VALUE, 100f)))
    }

    // ---- Glide or snap ----

    @Test
    fun theOutlineAppearsInPlaceAndGlidesBetweenItems() {
        // Hidden: it shows where the item is, whatever else is going on.
        assertEquals(XmbHighlightMove.SNAP, xmbMoveFor(wasHidden = true, itemChanged = true, animating = false))
        assertEquals(XmbHighlightMove.SNAP, xmbMoveFor(wasHidden = true, itemChanged = false, animating = true))
        // Another item: glide.
        assertEquals(XmbHighlightMove.GLIDE, xmbMoveFor(wasHidden = false, itemChanged = true, animating = false))
        // The same item moved because the list scrolled: follow without animating.
        assertEquals(XmbHighlightMove.SNAP, xmbMoveFor(wasHidden = false, itemChanged = false, animating = false))
        // The same item moved while a glide is still running: retarget the glide, do not jump.
        assertEquals(XmbHighlightMove.GLIDE, xmbMoveFor(wasHidden = false, itemChanged = false, animating = true))
    }

    @Test
    fun cornersStayInsideTheBox() {
        assertEquals(12f, xmbCornerClamp(12f, 100f, 60f), 0f)
        assertEquals(30f, xmbCornerClamp(80f, 100f, 60f), 0f)
        assertEquals(0f, xmbCornerClamp(-4f, 100f, 60f), 0f)
        assertEquals(0f, xmbCornerClamp(10f, 0f, 60f), 0f)
    }

    // ---- Colour ----

    @Test
    fun mixIsExactAtTheEnds() {
        val a = Color(0xFF204060)
        val b = Color(0xFFE0A82E)
        assertEquals(a, xmbMix(a, b, 0f))
        assertEquals(a, xmbMix(a, b, -1f))
        assertEquals(b, xmbMix(a, b, 1f))
        assertEquals(b, xmbMix(a, b, 2f))
        val mid = xmbMix(a, b, 0.5f)
        // A Color keeps 8 bits per channel.
        assertEquals((a.red + b.red) / 2f, mid.red, 0.005f)
        assertEquals((a.blue + b.blue) / 2f, mid.blue, 0.005f)
    }

    private fun contrastOn(text: Color, ground: Color): Double =
        xmbContrast(xmbLuminance(text.red, text.green, text.blue), xmbLuminance(ground.red, ground.green, ground.blue))

    @Test
    fun aReadableAccentIsTheInkAsItIs() {
        val accent = Color(0xFF8AB4FF) // Charcoal's soft blue
        val ink = xmbInkColor(accent, Color(0xFFE8E8E8), Color(0xFF2A2A2A))
        assertEquals(accent, ink)
    }

    @Test
    fun aDeepAccentIsLiftedJustEnoughToRead() {
        val accent = Color(0xFF0055FF) // Classic Dark's blue: ~2.5:1 on the glass, unreadable as text
        val text = Color(0xFFE8E8E8)
        val ground = Color(0xFF2A2A2A)
        val ink = xmbInkColor(accent, text, ground)
        assertTrue("ink $ink", ink != accent)
        assertTrue("ink $ink: ${contrastOn(ink, ground)}", contrastOn(ink, ground) >= XMB_INK_MIN_CONTRAST)
        // Not further than it had to: it is the first step towards the text that reads.
        val first = (0..10).first { contrastOn(xmbMix(accent, text, it / 10f), ground) >= XMB_INK_MIN_CONTRAST }
        assertTrue("step $first", first in 1..9)
        assertEquals(xmbMix(accent, text, first / 10f), ink)
    }

    @Test
    fun anAccentThatCannotReadFallsBackToTheText() {
        val ground = Color(0xFF808080)
        val text = Color(0xFF909090)
        assertEquals(text, xmbInkColor(Color(0xFF858585), text, ground))
    }

    // ---- The group's keys ----

    @Test
    fun aOnTheItemEntersOrRuns() {
        // No action: A enters.
        assertEquals(XmbGroupAct.ENTER, xmbGroupKeyAct(XmbGroupKey.ACTIVATE, entered = false, onOuter = true, hasAction = false))
        // With an action (a toggle row): A runs it.
        assertEquals(XmbGroupAct.RUN, xmbGroupKeyAct(XmbGroupKey.ACTIVATE, entered = false, onOuter = true, hasAction = true))
        // An entry that did not take (entered but focus is still on the item): A tries again.
        assertEquals(XmbGroupAct.ENTER, xmbGroupKeyAct(XmbGroupKey.ACTIVATE, entered = true, onOuter = true, hasAction = false))
    }

    @Test
    fun aOnAnInnerButtonIsTheButtonsOwn() {
        assertEquals(XmbGroupAct.PASS, xmbGroupKeyAct(XmbGroupKey.ACTIVATE, entered = true, onOuter = false, hasAction = false))
        assertEquals(XmbGroupAct.PASS, xmbGroupKeyAct(XmbGroupKey.ACTIVATE, entered = true, onOuter = false, hasAction = true))
    }

    @Test
    fun bLeavesAnEnteredItemOnlyFromInside() {
        assertEquals(XmbGroupAct.EXIT, xmbGroupKeyAct(XmbGroupKey.BACK, entered = true, onOuter = false, hasAction = false))
        // On the item itself B is the shell's (back to the previous page).
        assertEquals(XmbGroupAct.PASS, xmbGroupKeyAct(XmbGroupKey.BACK, entered = false, onOuter = true, hasAction = false))
        assertEquals(XmbGroupAct.PASS, xmbGroupKeyAct(XmbGroupKey.BACK, entered = true, onOuter = true, hasAction = false))
        // Focus somewhere inside that is not an entered group (a field the item holds): not ours.
        assertEquals(XmbGroupAct.PASS, xmbGroupKeyAct(XmbGroupKey.BACK, entered = false, onOuter = false, hasAction = false))
    }

    @Test
    fun aFullVisitWalksTheTwoLevels() {
        // The state machine as the item drives it: the key decision, then what the decision does to the state.
        var entered = false
        var onOuter = true
        fun press(key: XmbGroupKey, hasAction: Boolean = false): XmbGroupAct {
            val act = xmbGroupKeyAct(key, entered, onOuter, hasAction)
            when (act) {
                XmbGroupAct.ENTER -> { entered = true; onOuter = false } // focus moves to the first inner button
                XmbGroupAct.EXIT -> { entered = false; onOuter = true } // focus comes back to the item
                else -> Unit
            }
            return act
        }
        assertEquals(XmbGroupAct.ENTER, press(XmbGroupKey.ACTIVATE))
        assertTrue(entered && !onOuter)
        // A on the inner button is the button's; B exits.
        assertEquals(XmbGroupAct.PASS, press(XmbGroupKey.ACTIVATE))
        assertEquals(XmbGroupAct.EXIT, press(XmbGroupKey.BACK))
        assertTrue(!entered && onOuter)
        // B again goes to the shell.
        assertEquals(XmbGroupAct.PASS, press(XmbGroupKey.BACK))
        // And the item can be entered again.
        assertEquals(XmbGroupAct.ENTER, press(XmbGroupKey.ACTIVATE))
        // Losing focus from the group resets it (what XmbFocusGroup.onFocus does): the next visit starts on the item.
        entered = false
        onOuter = true
        assertEquals(XmbGroupAct.RUN, press(XmbGroupKey.ACTIVATE, hasAction = true))
    }

    // ---- The outline on a group card's slice ----

    @Test
    fun aRowInsetFromItsSliceIsTheSlice() {
        val slice = Rect(0f, 100f, 1000f, 260f)
        // The row's own inset: 6 dp each side, 3 dp (+1 for the hairline) top and bottom, at density 1.
        assertTrue(xmbFitsSlice(Rect(6f, 104f, 994f, 257f), slice, 8f, 6f))
        // Exactly the slice, or half a pixel out of it (rounding).
        assertTrue(xmbFitsSlice(slice, slice, 8f, 6f))
        assertTrue(xmbFitsSlice(Rect(-0.4f, 99.6f, 1000.4f, 260.4f), slice, 8f, 6f))
    }

    @Test
    fun aButtonInsideTheRowKeepsItsOwnBounds() {
        val slice = Rect(0f, 100f, 1000f, 260f)
        // A "?" button, a pill row, a switch: well inside the row's padding.
        assertTrue(!xmbFitsSlice(Rect(500f, 120f, 530f, 150f), slice, 8f, 6f))
        assertTrue(!xmbFitsSlice(Rect(600f, 114f, 988f, 246f), slice, 8f, 6f))
        // Wider than the slice (not inside it): not the slice either.
        assertTrue(!xmbFitsSlice(Rect(-20f, 100f, 1020f, 260f), slice, 8f, 6f))
    }

    @Test
    fun eachCornerIsHeldToTheBox() {
        val c = xmbCornersClamp(18f, 18f, 0f, 0f, 400f, 30f)
        assertEquals(15f, c.tl, 0f)
        assertEquals(15f, c.tr, 0f)
        assertEquals(0f, c.br, 0f)
        assertEquals(0f, c.bl, 0f)
        assertEquals(XmbCorners.None, xmbCornersClamp(-1f, 0f, 0f, -5f, 100f, 100f))
    }
}
