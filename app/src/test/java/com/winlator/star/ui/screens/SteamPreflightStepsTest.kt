package com.winlator.star.ui.screens

import com.winlator.star.store.SteamSessionManager.Step
import com.winlator.star.store.SteamSessionManager.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The step list of the XMB "Getting Steam ready" dialog and the sizes it derives from the dialog's room. */
class SteamPreflightStepsTest {

    private fun rows(
        states: Map<Step, Pair<StepState, String>> = emptyMap(),
        launching: Boolean = false,
        appSteam: Boolean = false,
    ) = preflightRows(states, launching, appSteam, "Brawlhalla")

    private fun List<PreflightRow>.of(id: PreflightRowId) = first { it.id == id }

    @Test
    fun theListIsInTheOrderThePreflightRunsAndEndsWithTheLaunch() {
        assertEquals(
            listOf(
                PreflightRowId.SIGN_IN, PreflightRowId.NETWORK, PreflightRowId.CLOUD,
                PreflightRowId.UPDATE, PreflightRowId.CLIENT, PreflightRowId.LAUNCH,
            ),
            rows().map { it.id },
        )
    }

    @Test
    fun beforeAnyReportEverythingIsPendingAndTheFirstRowIsCurrent() {
        val list = rows()
        assertTrue(list.all { it.mark == PreflightMark.PENDING })
        assertTrue(list.all { it.detail.isEmpty() })
        assertEquals(listOf(PreflightRowId.SIGN_IN), list.filter { it.current }.map { it.id })
    }

    @Test
    fun aRunningStepIsCurrentAndFinishedOnesKeepTheirText() {
        val list = rows(
            mapOf(
                Step.SESSION to (StepState.DONE to "Signed in"),
                Step.NETWORK to (StepState.RUNNING to "Checking network…"),
            ),
        )
        assertEquals(PreflightMark.DONE, list.of(PreflightRowId.SIGN_IN).mark)
        assertEquals("Signed in", list.of(PreflightRowId.SIGN_IN).detail)
        assertEquals(PreflightMark.RUNNING, list.of(PreflightRowId.NETWORK).mark)
        assertEquals(listOf(PreflightRowId.NETWORK), list.filter { it.current }.map { it.id })
    }

    @Test
    fun betweenTwoStepsTheNextPendingRowIsCurrent() {
        val list = rows(mapOf(Step.SESSION to (StepState.DONE to "Signed in")))
        assertEquals(listOf(PreflightRowId.NETWORK), list.filter { it.current }.map { it.id })
    }

    @Test
    fun theStepThatStoppedThePreflightIsCurrentAndWarns() {
        val list = rows(
            mapOf(
                Step.SESSION to (StepState.DONE to "Signed in"),
                Step.NETWORK to (StepState.NOTICE to "Strict NAT"),
                Step.CLOUD to (StepState.SKIPPED to "Cloud saves: off"),
                Step.UPDATE to (StepState.WARN to "Update available: build 1 → 2"),
            ),
        )
        assertEquals(PreflightMark.NOTICE, list.of(PreflightRowId.NETWORK).mark)
        assertEquals(PreflightMark.SKIPPED, list.of(PreflightRowId.CLOUD).mark)
        assertEquals(PreflightMark.WARN, list.of(PreflightRowId.UPDATE).mark)
        assertEquals(listOf(PreflightRowId.UPDATE), list.filter { it.current }.map { it.id })
    }

    @Test
    fun launchingRunsTheLastRowWithTheGameName() {
        val done = Step.values().associateWith { StepState.DONE to "ok" }
        val list = rows(done, launching = true)
        val launch = list.of(PreflightRowId.LAUNCH)
        assertEquals(PreflightMark.RUNNING, launch.mark)
        assertEquals("Launching Brawlhalla…", launch.detail)
        assertTrue(launch.current)
        assertEquals(1, list.count { it.current })
    }

    @Test
    fun aStepNeverReportedIsNotWaitedForOnceLaunching() {
        val list = rows(mapOf(Step.SESSION to (StepState.DONE to "Signed in")), launching = true)
        assertEquals(PreflightMark.SKIPPED, list.of(PreflightRowId.CLOUD).mark)
        assertEquals(PreflightMark.DONE, list.of(PreflightRowId.SIGN_IN).mark)
    }

    @Test
    fun theClientRowNamesTheClientPackage() {
        assertEquals("SteamLite client", rows(appSteam = false).of(PreflightRowId.CLIENT).name)
        assertEquals("Valve Steam client", rows(appSteam = true).of(PreflightRowId.CLIENT).name)
    }

    @Test
    fun aRetryReplaysFromTheTopWhenTheStatesAreCleared() {
        val list = rows(emptyMap())
        assertTrue(list.none { it.mark == PreflightMark.WARN || it.mark == PreflightMark.DONE })
        assertEquals(PreflightRowId.SIGN_IN, list.first { it.current }.id)
    }

    @Test
    fun theSplitLayoutNeedsRoomForThePhotoBesideTheList() {
        assertTrue(xmbPreflightSplit(XMB_PREFLIGHT_SPLIT_MIN_WIDTH))
        assertFalse(xmbPreflightSplit(XMB_PREFLIGHT_SPLIT_MIN_WIDTH - 1f))
        // A phone in landscape (851 x 393 dp, the Deck draws it at 0.8) splits, in portrait (393 x 851) it stacks.
        assertTrue(xmbPreflightSplit(xmbDialogSize(851f / 0.8f, 393f / 0.8f, wide = true).width))
        assertFalse(xmbPreflightSplit(xmbDialogSize(393f / 0.8f, 851f / 0.8f, wide = true).width))
    }

    @Test
    fun theBodyRoomLeavesWhatTheChromeTakes() {
        assertEquals(260f, xmbPreflightBodyRoom(432f, compact = false), 0.001f)
        assertEquals(208f, xmbPreflightBodyRoom(352f, compact = true), 0.001f)
        assertEquals(120f, xmbPreflightBodyRoom(100f, compact = true), 0.001f)
    }

    @Test
    fun thePhotoIsNeverTallerThanTheBodyAndStaysSmallWhenStacked() {
        val room = xmbPreflightBodyRoom(432f, compact = false)
        assertTrue(xmbPreflightPhotoMaxHeight(room, split = true) + 66f <= room + 0.001f)
        assertEquals(80f, xmbPreflightPhotoMaxHeight(120f, split = true), 0.001f)
        assertEquals(220f, xmbPreflightPhotoMaxHeight(900f, split = true), 0.001f)
        assertEquals(132f, xmbPreflightPhotoMaxHeight(900f, split = false), 0.001f)
    }

    @Test
    fun failButtonsGoInTwoColumnsOnlyWhenTheCardIsWideEnough() {
        assertEquals(2, xmbPreflightActionColumns(760f, split = true, count = 3))
        assertEquals(1, xmbPreflightActionColumns(640f, split = true, count = 3))
        assertEquals(1, xmbPreflightActionColumns(760f, split = true, count = 1))
        assertEquals(1, xmbPreflightActionColumns(760f, split = false, count = 3))
    }

    @Test
    fun theLaunchRowIsCurrentWhileEveryStepIsDoneButTheLaunchHasNotStarted() {
        val done = Step.values().associateWith { StepState.DONE to "ok" }
        val list = rows(done, launching = false)
        // The launch row is still pending: it is the current one until onReady flips launching.
        assertEquals(listOf(PreflightRowId.LAUNCH), list.filter { it.current }.map { it.id })
        assertNull(list.firstOrNull { it.mark == PreflightMark.WARN })
    }
}
