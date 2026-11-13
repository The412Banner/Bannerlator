package com.winlator.star.ui.screens

import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB Home's Downloads page: how the entries group into sections, where the cursor goes, the buttons of a card and the progress text. */
class XmbDownloadsLogicTest {

    private fun entry(
        id: String,
        state: DownloadState,
        name: String = id,
        store: Store = Store.STEAM,
        queuePosition: Int = 0,
        updateAvailable: Boolean = false,
        supportsPause: Boolean = true,
    ) = DownloadEntry(
        store = store, id = id, name = name, state = state, queuePosition = queuePosition,
        updateAvailable = updateAvailable, supportsPause = supportsPause,
    )

    private fun names(g: XmbDlGroup) = g.entries.map { it.id }

    @Test fun groups_emptyListHasNoSections() {
        assertTrue(xmbDlGroups(emptyList()).isEmpty())
    }

    @Test fun groups_sectionsInOrder_emptyOnesLeftOut() {
        val groups = xmbDlGroups(
            listOf(
                entry("i", DownloadState.INSTALLED),
                entry("q", DownloadState.QUEUED, queuePosition = 1),
                entry("f", DownloadState.FAILED),
                entry("d", DownloadState.DOWNLOADING),
            ),
        )
        assertEquals(
            listOf(XmbDlSection.ACTIVE, XmbDlSection.QUEUED, XmbDlSection.STOPPED, XmbDlSection.INSTALLED),
            groups.map { it.section },
        )
        assertTrue(xmbDlGroups(listOf(entry("i", DownloadState.INSTALLED))).map { it.section } == listOf(XmbDlSection.INSTALLED))
    }

    @Test fun groups_runningBeforePaused_thenByName() {
        val groups = xmbDlGroups(
            listOf(
                entry("a", DownloadState.PAUSED, name = "Alpha"),
                entry("b", DownloadState.DOWNLOADING, name = "Zulu"),
                entry("c", DownloadState.PAUSED, name = "bravo"),
            ),
        )
        assertEquals(listOf("b", "a", "c"), names(groups.single()))
    }

    @Test fun groups_queueFollowsItsPositionNotTheName() {
        val groups = xmbDlGroups(
            listOf(
                entry("a", DownloadState.QUEUED, name = "Alpha", queuePosition = 3),
                entry("b", DownloadState.QUEUED, name = "Zulu", queuePosition = 1),
                entry("c", DownloadState.QUEUED, name = "Mike", queuePosition = 2),
            ),
        )
        assertEquals(listOf("b", "c", "a"), names(groups.single()))
    }

    @Test fun groups_failuresBeforeCancellations() {
        val groups = xmbDlGroups(
            listOf(
                entry("a", DownloadState.CANCELLED, name = "Alpha"),
                entry("b", DownloadState.FAILED, name = "Zulu"),
            ),
        )
        assertEquals(XmbDlSection.STOPPED, groups.single().section)
        assertEquals(listOf("b", "a"), names(groups.single()))
    }

    @Test fun groups_installedWithAnUpdateFirst_thenByNameIgnoringCase() {
        val groups = xmbDlGroups(
            listOf(
                entry("a", DownloadState.INSTALLED, name = "alpha"),
                entry("b", DownloadState.INSTALLED, name = "Zulu", updateAvailable = true),
                entry("c", DownloadState.INSTALLED, name = "Bravo"),
            ),
        )
        assertEquals(listOf("b", "a", "c"), names(groups.single()))
    }

    @Test fun cards_runTopToBottomAcrossSections() {
        val groups = xmbDlGroups(
            listOf(entry("i", DownloadState.INSTALLED), entry("d", DownloadState.DOWNLOADING), entry("q", DownloadState.QUEUED)),
        )
        assertEquals(listOf("d", "q", "i"), xmbDlCards(groups).map { it.id })
    }

    // Sections of 3 (d1..d3 downloading) and 2 (q1, q2 queued): cards 0..2 and 3..4.
    private fun twoSections(): List<XmbDlGroup> = xmbDlGroups(
        listOf(
            entry("d1", DownloadState.DOWNLOADING, name = "a"),
            entry("d2", DownloadState.DOWNLOADING, name = "b"),
            entry("d3", DownloadState.DOWNLOADING, name = "c"),
            entry("q1", DownloadState.QUEUED, queuePosition = 1),
            entry("q2", DownloadState.QUEUED, queuePosition = 2),
        ),
    )

    @Test fun lines_cardsFillLinesOfTwo_eachSectionStartsANewLine() {
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), xmbDlLines(twoSections(), 2))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4)), xmbDlLines(twoSections(), 1))
        assertTrue(xmbDlLines(emptyList(), 2).isEmpty())
    }

    @Test fun slot_countsTheHeadingBeforeEachSection_andTheLinesOfCards() {
        val groups = twoSections()
        // Items with two columns: 0 heading, 1 line [d1 d2], 2 line [d3], 3 heading, 4 line [q1 q2].
        val d1 = xmbDlSlot(groups, 0, 2)!!
        assertEquals(0, d1.header); assertEquals(1, d1.item); assertTrue(d1.first)
        val d2 = xmbDlSlot(groups, 1, 2)!!
        assertEquals(0, d2.header); assertEquals(1, d2.item); assertTrue(d2.first)
        val d3 = xmbDlSlot(groups, 2, 2)!!
        assertEquals(0, d3.header); assertEquals(2, d3.item); assertFalse(d3.first)
        val q1 = xmbDlSlot(groups, 3, 2)!!
        assertEquals(3, q1.header); assertEquals(4, q1.item); assertTrue(q1.first)
        val q2 = xmbDlSlot(groups, 4, 2)!!
        assertEquals(3, q2.header); assertEquals(4, q2.item); assertTrue(q2.first)
    }

    @Test fun slot_oneColumnIsOneItemPerCard() {
        val groups = twoSections()
        // Items: 0 heading, 1 d1, 2 d2, 3 d3, 4 heading, 5 q1, 6 q2.
        val d3 = xmbDlSlot(groups, 2, 1)!!
        assertEquals(0, d3.header); assertEquals(3, d3.item); assertFalse(d3.first)
        val q2 = xmbDlSlot(groups, 4, 1)!!
        assertEquals(4, q2.header); assertEquals(6, q2.item); assertFalse(q2.first)
    }

    @Test fun slot_offTheListIsNull() {
        val groups = xmbDlGroups(listOf(entry("d", DownloadState.DOWNLOADING)))
        assertNull(xmbDlSlot(groups, -1, 2))
        assertNull(xmbDlSlot(groups, 1, 2))
        assertNull(xmbDlSlot(emptyList(), 0, 2))
    }

    @Test fun move_leftRightBetweenTheColumns_stopAtTheLineEnds() {
        val lines = xmbDlLines(twoSections(), 2)
        assertEquals(1, xmbTwoColSide(lines, 0, 1))
        assertEquals(0, xmbTwoColSide(lines, 1, -1))
        assertEquals(1, xmbTwoColSide(lines, 1, 1))
        assertEquals(0, xmbTwoColSide(lines, 0, -1))
        // A line of one card has nowhere to go sideways.
        assertEquals(2, xmbTwoColSide(lines, 2, 1))
        assertEquals(2, xmbTwoColSide(lines, 2, -1))
    }

    @Test fun move_downAndUpCrossTheSectionsOnTheWantedColumn_aShortLineClamps() {
        val lines = xmbDlLines(twoSections(), 2)
        // [0 1] [2] [3 4]
        assertEquals(2, xmbTwoColVertical(lines, 1, down = true, wantCol = 1))
        assertEquals(4, xmbTwoColVertical(lines, 2, down = true, wantCol = 1))
        assertEquals(3, xmbTwoColVertical(lines, 2, down = true, wantCol = 0))
        assertEquals(2, xmbTwoColVertical(lines, 4, down = false, wantCol = 1))
        assertEquals(1, xmbTwoColVertical(lines, 2, down = false, wantCol = 1))
        assertEquals(0, xmbTwoColVertical(lines, 2, down = false, wantCol = 0))
    }

    @Test fun move_stopsAtTheLastLine_upFromTheFirstGoesToTheBar() {
        val lines = xmbDlLines(twoSections(), 2)
        assertEquals(4, xmbTwoColVertical(lines, 4, down = true, wantCol = 1))
        assertEquals(3, xmbTwoColVertical(lines, 3, down = true, wantCol = 0))
        assertEquals(XMB_TWO_COL_TO_BAR, xmbTwoColVertical(lines, 0, down = false, wantCol = 0))
        assertEquals(XMB_TWO_COL_TO_BAR, xmbTwoColVertical(lines, 1, down = false, wantCol = 1))
        // One column: along the cards as before.
        val one = xmbDlLines(twoSections(), 1)
        assertEquals(1, xmbTwoColVertical(one, 0, down = true, wantCol = 0))
        assertEquals(XMB_TWO_COL_TO_BAR, xmbTwoColVertical(one, 0, down = false, wantCol = 0))
    }

    @Test fun cursor_staysOnItsCardWhenTheListMoves() {
        assertEquals(2, xmbDlCursor(listOf("a", "b", "c"), "c", 0))
        assertEquals(0, xmbDlCursor(listOf("c", "a", "b"), "c", 2))
    }

    @Test fun cursor_goesToTheNearestIndexWhenItsCardIsGone() {
        assertEquals(1, xmbDlCursor(listOf("a", "c"), "b", 1))
        assertEquals(1, xmbDlCursor(listOf("a", "c"), "b", 5))
        assertEquals(0, xmbDlCursor(listOf("a", "c"), null, -3))
    }

    @Test fun cursor_noCardsIsMinusOne() {
        assertEquals(-1, xmbDlCursor(emptyList(), "a", 0))
    }

    @Test fun actions_downloadingCanPauseAndCancel() {
        val e = entry("d", DownloadState.DOWNLOADING)
        assertEquals(listOf(XmbDlAction.PAUSE, XmbDlAction.CANCEL, XmbDlAction.DETAILS), xmbDlActions(e, canMoveUp = false))
        assertEquals(XmbDlAction.PAUSE, xmbDlQuickAction(e, false))
    }

    @Test fun actions_pausedOffersResume() {
        val e = entry("p", DownloadState.PAUSED)
        assertEquals(XmbDlAction.RESUME, xmbDlActions(e, false).first())
        assertEquals(XmbDlAction.RESUME, xmbDlQuickAction(e, false))
    }

    @Test fun actions_aStoreThatCannotPauseHasNoPauseNorQuickAction() {
        val e = entry("d", DownloadState.DOWNLOADING, supportsPause = false)
        assertEquals(listOf(XmbDlAction.CANCEL, XmbDlAction.DETAILS), xmbDlActions(e, false))
        assertNull(xmbDlQuickAction(e, false))
    }

    @Test fun actions_queuedStartNextOnlyWhenNotAlreadyNext() {
        val e = entry("q", DownloadState.QUEUED, queuePosition = 2)
        assertEquals(listOf(XmbDlAction.START_NEXT, XmbDlAction.CANCEL, XmbDlAction.DETAILS), xmbDlActions(e, canMoveUp = true))
        assertEquals(XmbDlAction.START_NEXT, xmbDlQuickAction(e, true))
        assertEquals(listOf(XmbDlAction.CANCEL, XmbDlAction.DETAILS), xmbDlActions(e, canMoveUp = false))
        assertNull(xmbDlQuickAction(e, false))
    }

    @Test fun actions_installedLaunchesFirst_uninstallLast() {
        val e = entry("i", DownloadState.INSTALLED)
        assertEquals(listOf(XmbDlAction.LAUNCH, XmbDlAction.DETAILS, XmbDlAction.UNINSTALL), xmbDlActions(e, false))
        assertEquals(XmbDlAction.LAUNCH, xmbDlQuickAction(e, false))
    }

    @Test fun actions_stoppedCanBeOpenedOrDismissed_xDismisses() {
        for (state in listOf(DownloadState.FAILED, DownloadState.CANCELLED)) {
            val e = entry("f", state)
            assertEquals(listOf(XmbDlAction.DETAILS, XmbDlAction.DISMISS), xmbDlActions(e, false))
            assertEquals(XmbDlAction.DISMISS, xmbDlQuickAction(e, false))
        }
    }

    @Test fun confirm_uninstallAndCancelAskTwice_epicCancelHasItsOwnDialog() {
        assertTrue(xmbDlNeedsConfirm(entry("i", DownloadState.INSTALLED), XmbDlAction.UNINSTALL))
        assertTrue(xmbDlNeedsConfirm(entry("d", DownloadState.DOWNLOADING), XmbDlAction.CANCEL))
        assertFalse(xmbDlNeedsConfirm(entry("d", DownloadState.DOWNLOADING, store = Store.EPIC), XmbDlAction.CANCEL))
        assertFalse(xmbDlNeedsConfirm(entry("d", DownloadState.DOWNLOADING), XmbDlAction.PAUSE))
        assertFalse(xmbDlNeedsConfirm(entry("f", DownloadState.FAILED), XmbDlAction.DISMISS))
    }

    @Test fun progress_byteDrivenWhenBytesAreFed() {
        val p = xmbDlProgress(entry("d", DownloadState.DOWNLOADING).copy(installDone = 250, installTotal = 1000, pct = 90))
        assertEquals(0.25f, p.fraction, 0.001f)
        assertEquals(25, p.percent)
        assertTrue(p.hasBytes)
    }

    @Test fun progress_percentageOnlyStoreUsesItsPercentage() {
        // GOG: a total is known but nothing is reported done in bytes.
        val p = xmbDlProgress(entry("g", DownloadState.DOWNLOADING, store = Store.GOG).copy(installDone = 0, installTotal = 1000, pct = 40))
        assertEquals(0.4f, p.fraction, 0.001f)
        assertEquals(40, p.percent)
        assertFalse(p.hasBytes)
        assertEquals("", xmbDlSizeText(entry("g", DownloadState.DOWNLOADING).copy(installTotal = 1000, pct = 40)))
    }

    @Test fun innerClamp_keepsAButtonThatStillExists_dropsOneThatWentWithItsCard() {
        // Downloading (pause, cancel, details) -> failed (details, dismiss): the cursor on Details (2) lands on the last button.
        assertEquals(1, xmbDlInnerClamp(2, 2))
        assertEquals(1, xmbDlInnerClamp(1, 3))
        assertEquals(0, xmbDlInnerClamp(0, 1))
        // Not in the buttons stays out of them; a card with none lets go.
        assertEquals(-1, xmbDlInnerClamp(-1, 3))
        assertEquals(-1, xmbDlInnerClamp(2, 0))
    }

    @Test fun progress_clampedToTheBar() {
        assertEquals(1f, xmbDlProgress(entry("d", DownloadState.DOWNLOADING).copy(installDone = 2000, installTotal = 1000)).fraction, 0.001f)
        assertEquals(0f, xmbDlProgress(entry("d", DownloadState.DOWNLOADING).copy(pct = -5)).fraction, 0.001f)
    }

    @Test fun sizeText_showsDoneOverTotalForByteStores() {
        val t = xmbDlSizeText(entry("d", DownloadState.DOWNLOADING).copy(installDone = 5L * 1_048_576, installTotal = 10L * 1_048_576))
        assertTrue(t, t.contains(" / ") && t.contains("MB"))
    }

    @Test fun speedText_joinsWhatIsKnown() {
        val none = entry("d", DownloadState.DOWNLOADING)
        assertEquals("", xmbDlSpeedText(none))
        assertEquals("<1 min left", xmbDlSpeedText(none.copy(etaSeconds = 30)))
        val both = xmbDlSpeedText(none.copy(speedBps = 2L * 1_048_576, etaSeconds = 600))
        assertTrue(both, both.contains("/s") && both.contains(" · ") && both.endsWith("~10 min left"))
        assertNotNull(both)
    }

    @Test fun ring_noActiveDownloadIsNull() {
        assertNull(xmbRingOf(emptyList()))
        assertNull(
            xmbRingOf(
                listOf(
                    entry("i", DownloadState.INSTALLED),
                    entry("f", DownloadState.FAILED),
                    entry("c", DownloadState.CANCELLED),
                ),
            ),
        )
    }

    @Test fun ring_runningWinsOverPausedAndQueued() {
        val ring = xmbRingOf(
            listOf(
                entry("p", DownloadState.PAUSED, name = "A").copy(pct = 80),
                entry("q", DownloadState.QUEUED, queuePosition = 1).copy(pct = 70),
                entry("d", DownloadState.DOWNLOADING, name = "Z").copy(pct = 25),
            ),
        )
        assertEquals(XmbDownloadRing(0.25f, running = true), ring)
    }

    @Test fun ring_severalRunning_theTopOfThePagesListWins() {
        val ring = xmbRingOf(
            listOf(
                entry("b", DownloadState.DOWNLOADING, name = "Bravo").copy(pct = 90),
                entry("a", DownloadState.DOWNLOADING, name = "Alpha").copy(pct = 10),
            ),
        )
        assertEquals(XmbDownloadRing(0.1f, running = true), ring)
    }

    @Test fun ring_nothingRunning_theTopPausedShowsTheWaitingSymbol() {
        val ring = xmbRingOf(
            listOf(
                entry("q", DownloadState.QUEUED, queuePosition = 1).copy(pct = 70),
                entry("z", DownloadState.PAUSED, name = "Zulu").copy(pct = 40),
                entry("a", DownloadState.PAUSED, name = "Alpha").copy(pct = 60),
            ),
        )
        assertEquals(XmbDownloadRing(0.6f, running = false), ring)
    }

    @Test fun ring_onlyQueued_theFirstInTheQueue() {
        val ring = xmbRingOf(
            listOf(
                entry("second", DownloadState.QUEUED, queuePosition = 2).copy(pct = 50),
                entry("first", DownloadState.QUEUED, queuePosition = 1).copy(pct = 5),
            ),
        )
        assertEquals(XmbDownloadRing(0.05f, running = false), ring)
    }

    @Test fun ring_progressFromBytes_elseTheRoundedPercent_clampedAndEmptyWhenUnknown() {
        val bytes = entry("d", DownloadState.DOWNLOADING).copy(installDone = 250, installTotal = 1000, pct = 99)
        assertEquals(0.25f, xmbRingOf(listOf(bytes))!!.progress, 0.001f)
        assertEquals(0.4f, xmbRingOf(listOf(entry("d", DownloadState.DOWNLOADING).copy(pct = 40)))!!.progress, 0.001f)
        assertEquals(1f, xmbRingOf(listOf(entry("d", DownloadState.DOWNLOADING).copy(installDone = 2000, installTotal = 1000)))!!.progress, 0.001f)
        assertEquals(0f, xmbRingOf(listOf(entry("d", DownloadState.DOWNLOADING).copy(pct = -5)))!!.progress, 0.001f)
        assertEquals(0f, xmbRingOf(listOf(entry("d", DownloadState.DOWNLOADING)))!!.progress, 0.001f)
    }

    @Test fun ring_failedAndInstalledDoNotShadowAPausedOne() {
        val ring = xmbRingOf(
            listOf(
                entry("f", DownloadState.FAILED).copy(pct = 90),
                entry("i", DownloadState.INSTALLED),
                entry("p", DownloadState.PAUSED).copy(pct = 30),
            ),
        )
        assertEquals(XmbDownloadRing(0.3f, running = false), ring)
    }
}
