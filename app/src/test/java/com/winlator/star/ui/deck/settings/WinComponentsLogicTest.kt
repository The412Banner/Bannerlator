package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the Win components page (WinComponentsPage.kt): which components show first, what is left for the popup, search. */
class WinComponentsLogicTest {

    private val nine = listOf(
        "components.direct3d", "components.directsound", "components.directmusic", "components.directshow", "components.directplay",
        "components.xaudio", "components.vcrun2010", "windows.codecBackend", "windows.softwareDecoding",
    )

    // ---- The first five ----

    @Test
    fun withNothingChangedItIsTheTopFiveOfTheOrder() {
        assertEquals(nine.take(5), WinComponentsLogic.shownIds(nine, emptySet()))
        assertEquals(nine.drop(5), WinComponentsLogic.restIds(nine, WinComponentsLogic.shownIds(nine, emptySet())))
    }

    @Test
    fun aChangedOneIsAlwaysShownAndTakesTheLastSlot() {
        // The codec backend is the 8th: it must not hide in "Show more"; the 5th of the top is pushed out for it.
        val shown = WinComponentsLogic.shownIds(nine, setOf("windows.codecBackend"))
        assertEquals(5, shown.size)
        assertTrue("windows.codecBackend" in shown)
        assertEquals(nine.take(4) + "windows.codecBackend", shown)
        assertFalse("components.directplay" in shown)
    }

    @Test
    fun theShownOnesKeepTheOrderOfTheListSoNothingJumpsWhenToggled() {
        val shown = WinComponentsLogic.shownIds(nine, setOf("components.xaudio", "components.direct3d"))
        // direct3d is first anyway; xaudio (6th) comes in and pushes directplay out; the list stays in registry order.
        assertEquals(
            listOf("components.direct3d", "components.directsound", "components.directmusic", "components.directshow", "components.xaudio"),
            shown,
        )
    }

    @Test
    fun moreThanFiveChangedAreAllShownAndNothingElse() {
        val changed = nine.drop(2).take(6).toSet()
        val shown = WinComponentsLogic.shownIds(nine, changed)
        assertEquals(nine.drop(2).take(6), shown)
        assertEquals(listOf(nine[0], nine[1], nine[8]), WinComponentsLogic.restIds(nine, shown))
    }

    @Test
    fun aShortListIsShownWholeWithNothingLeft() {
        val few = nine.take(3)
        assertEquals(few, WinComponentsLogic.shownIds(few, emptySet()))
        assertTrue(WinComponentsLogic.restIds(few, WinComponentsLogic.shownIds(few, setOf(few[2]))).isEmpty())
        assertTrue(WinComponentsLogic.shownIds(emptyList(), setOf("x")).isEmpty())
    }

    @Test
    fun theCountCanBeChanged() {
        assertEquals(nine.take(2), WinComponentsLogic.shownIds(nine, emptySet(), count = 2))
        assertEquals(WinComponentsLogic.FIRST, 5)
    }

    @Test
    fun anIdInChangedThatIsNotInTheListIsIgnored() {
        assertEquals(nine.take(5), WinComponentsLogic.shownIds(nine, setOf("nope")))
    }

    @Test
    fun shownAndRestPartitionTheList() {
        val shown = WinComponentsLogic.shownIds(nine, setOf("windows.softwareDecoding", "components.xaudio"))
        val rest = WinComponentsLogic.restIds(nine, shown)
        assertEquals(nine.size, shown.size + rest.size)
        assertEquals(nine.toSet(), (shown + rest).toSet())
        assertTrue(shown.none { it in rest })
    }

    // ---- Search ----

    @Test
    fun aBlankQueryMatchesEverything() {
        assertTrue(WinComponentsLogic.matches("", "Direct3D"))
        assertTrue(WinComponentsLogic.matches("   ", null))
    }

    @Test
    fun everyWordHasToBeInOnePartOrAnother() {
        assertTrue(WinComponentsLogic.matches("direct3d", "Direct3D", null, "DirectX"))
        assertTrue(WinComponentsLogic.matches("DIRECTX 3d", "Direct3D", "builtin or native", "DirectX"))
        assertTrue(WinComponentsLogic.matches("native", "Direct3D", "Builtin or native"))
        assertFalse(WinComponentsLogic.matches("vulkan", "Direct3D", "Builtin or native", "DirectX"))
        assertFalse(WinComponentsLogic.matches("direct3d vulkan", "Direct3D", "DirectX"))
    }

    // ---- Which rows the page draws ----

    @Test
    fun theDllAndCodecRowsAreTheOnesThePageDraws() {
        assertTrue(nine.all { WinComponentsLogic.isComponentRow(it) })
        assertFalse(WinComponentsLogic.isComponentRow("windows.wineVersion"))
        assertFalse(WinComponentsLogic.isComponentRow("windows.locale"))
        assertFalse(WinComponentsLogic.isComponentRow("graphics.dx.dxvkVersion"))
    }
}
