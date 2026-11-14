package com.winlator.star.ui.deck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure parts of the XMB rows in the Deck kit: where A lands in a row of choices, the slider's nudge, the card slices' shadow. */
class DeckKitXmbTest {

    // ---- Which choice A on a row lands on ----

    @Test
    fun landsOnTheSelectedChoiceWhenItCanTakeFocus() {
        assertEquals(1, xmbLandingIndex(listOf(false, true, false), listOf(true, true, true)))
    }

    @Test
    fun landsOnTheFirstFocusableWhenTheSelectedOneCannot() {
        assertEquals(0, xmbLandingIndex(listOf(false, true, false), listOf(true, false, true)))
        assertEquals(2, xmbLandingIndex(listOf(false, true, false), listOf(false, false, true)))
    }

    @Test
    fun landsOnTheFirstFocusableWhenNothingIsSelected() {
        assertEquals(1, xmbLandingIndex(listOf(false, false, false), listOf(false, true, true)))
    }

    @Test
    fun landsNowhereWhenNothingCanTakeFocus() {
        assertEquals(-1, xmbLandingIndex(listOf(true, false), listOf(false, false)))
        assertEquals(-1, xmbLandingIndex(emptyList(), emptyList()))
    }

    // ---- The slider's key nudge ----

    @Test
    fun nudgeMovesOneStepEitherWay() {
        assertEquals(6f, xmbSliderNudge(5f, 1, 1f, 0f, 10f), 0f)
        assertEquals(4.5f, xmbSliderNudge(5f, -1, 0.5f, 0f, 10f), 0f)
    }

    @Test
    fun nudgeStopsAtTheEnds() {
        assertEquals(10f, xmbSliderNudge(10f, 1, 1f, 0f, 10f), 0f)
        assertEquals(0f, xmbSliderNudge(0.5f, -1, 1f, 0f, 10f), 0f)
    }

    // ---- The shadow of a slice of a grouped card ----

    @Test
    fun aSingleSliceCardGetsTheWholeShadow() {
        val b = xmbSliceLayerBounds(w = 300f, h = 80f, spread = 10f, dy = 4f, first = true, last = true, overshoot = 64f)
        assertArrayEquals(floatArrayOf(-10f, -6f, 310f, 94f), b, 0f)
    }

    @Test
    fun aMiddleSliceShadesOnlyTheSidesAndRunsOnIntoItsNeighbours() {
        val b = xmbSliceLayerBounds(w = 300f, h = 80f, spread = 10f, dy = 4f, first = false, last = false, overshoot = 64f)
        assertArrayEquals(floatArrayOf(-10f, -64f, 310f, 144f), b, 0f)
        val clip = xmbSliceClip(300f, 80f, first = false, last = false, reach = 64f)
        assertArrayEquals(floatArrayOf(-64f, 0f, 364f, 80f), clip, 0f)
    }

    @Test
    fun theFirstSliceAddsTheTopAndTheLastTheBottom() {
        val first = xmbSliceClip(300f, 80f, first = true, last = false, reach = 64f)
        assertArrayEquals(floatArrayOf(-64f, -64f, 364f, 80f), first, 0f)
        val last = xmbSliceClip(300f, 80f, first = false, last = true, reach = 64f)
        assertArrayEquals(floatArrayOf(-64f, 0f, 364f, 144f), last, 0f)
    }
}
