package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Steam look of the XMB Home: the art order of the wide card, the row's scrolling and windowing, and the layout. */
class XmbSteamLogicTest {

    private val delta = 0.001f

    // A screen 800 wide with a 20 margin; a wide first card (200) and portrait cards (100), 10 apart, no focus growth unless asked.
    private fun row(count: Int, focus: Float = 1f, viewW: Float = 800f) =
        XmbSteamRow(count, firstW = 200f, otherW = 100f, gap = 10f, viewW = viewW, edge = 20f, focus = focus)

    @Test fun wideOrder_landscapeArtFirstThenBackground_eachOnce() {
        assertEquals(listOf("pick", "header", "hero2x", "hero"), xmbSteamWideOrder(listOf("pick", "header"), listOf("pick", "hero2x", "hero")))
        assertEquals(emptyList<String>(), xmbSteamWideOrder(emptyList<String>(), emptyList()))
    }

    @Test fun scale_isASubtleLiftOnTheCursorOnly() {
        assertEquals(XMB_STEAM_FOCUS_SCALE, xmbSteamScale(2, 2f), delta)
        assertEquals(1f, xmbSteamScale(0, 2f), delta)
        assertEquals(1f, xmbSteamScale(5, 2f), delta)
        assertTrue(XMB_STEAM_FOCUS_SCALE in 1.05f..1.08f)
    }

    @Test fun scale_glidesBetweenTwoCards() {
        val mid = (1f + XMB_STEAM_FOCUS_SCALE) / 2f
        assertEquals(mid, xmbSteamScale(1, 1.5f), delta)
        assertEquals(mid, xmbSteamScale(2, 1.5f), delta)
    }

    @Test fun row_wideCapsuleHasSteamsShape() {
        assertEquals(460f / 215f, XMB_STEAM_WIDE_RATIO, delta)
    }

    @Test fun x_longRowAtTheStart_isPinnedToTheLeftMargin() {
        val r = row(30)
        assertEquals(20f, r.x(0, 0f), delta)
        assertEquals(20f + 210f, r.x(1, 0f), delta)
        // Cards 1 and 2 are still too near the start to be centred.
        assertEquals(20f, r.x(0, 1f), delta)
        assertEquals(20f, r.x(0, 2f), delta)
    }

    @Test fun x_longRowInTheMiddle_centresTheFocusedCard() {
        val r = row(30)
        for (p in listOf(8, 12, 20)) assertEquals(400f, r.x(p, p.toFloat()) + 50f, delta)
        // The card before it sits one pitch to the left.
        assertEquals(400f - 50f - 110f, r.x(11, 12f), delta)
    }

    @Test fun x_longRowAtTheEnd_isPinnedToTheRightMargin() {
        val r = row(30)
        assertEquals(800f - 20f, r.x(29, 29f) + 100f, delta)
        assertEquals(800f - 20f, r.x(29, 28f) + 100f, delta)
        assertEquals(800f - 20f, r.x(29, 27f) + 100f, delta)
    }

    @Test fun x_rowThatFits_doesNotScroll() {
        val r = row(4)
        for (p in 0..3) {
            assertEquals(20f, r.x(0, p.toFloat()), delta)
            assertEquals(230f, r.x(1, p.toFloat()), delta)
        }
    }

    @Test fun x_glidesWithoutJumps() {
        val r = row(30)
        var last = r.x(10, 0f)
        var p = 0f
        while (p <= 29f) {
            val x = r.x(10, p)
            assertTrue("jump at $p: $last -> $x", kotlin.math.abs(x - last) < 6.5f)
            last = x
            p += 0.05f
        }
    }

    @Test fun x_focusGrowthKeepsTheNeighboursApart() {
        val r = row(30, focus = 1.1f)
        // Card 12 is 10% wider while focused (110) and its left neighbour ends one gap before it.
        assertEquals(r.x(12, 12f) - 10f, r.x(11, 12f) + 100f, delta)
        assertEquals(r.x(12, 12f) + 110f + 10f, r.x(13, 12f), delta)
    }

    @Test fun x_overshootPastTheEndsStaysPinned() {
        val r = row(30)
        assertEquals(20f, r.x(0, -0.4f), delta)
    }

    @Test fun x_emptyAndSingleRows() {
        assertEquals(20f, row(0).x(0, 0f), delta)
        assertEquals(20f, row(1).x(0, 0f), delta)
    }

    @Test fun window_aroundTheCursorOnly() {
        // 800 wide, 110 apart: 8 cards across, plus 2 spare, each side.
        assertEquals(40..60, xmbSteamWindow(50f, 300, 800f, 110f))
        val w = xmbSteamWindow(150f, 300, 800f, 110f)
        assertEquals(140, w.first)
        assertEquals(160, w.last)
        assertTrue(w.count() < 40)
    }

    @Test fun window_isClampedToTheLibrary() {
        assertEquals(0..10, xmbSteamWindow(0f, 300, 800f, 110f))
        assertEquals(290..299, xmbSteamWindow(299f, 300, 800f, 110f))
        assertTrue(xmbSteamWindow(0f, 0, 800f, 110f).isEmpty())
    }

    @Test fun layout_coversKeepTheirTopsAndReachDownToTheTitle() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        // The tops sit in the middle of the screen's height (a short screen puts them above half of it), below the floating tab bar.
        assertTrue(l.cardTop in 393f * 0.35f..393f * 0.50f)
        assertTrue(l.cardTop >= 50f + 8f)
        // Their bottom edge now reaches down to just above the title, so they are taller than that third.
        assertTrue(l.fH > 393f * 0.32f + 4f)
        assertEquals(l.cardTop + l.fH, l.cardBottom, delta)
        assertEquals(l.cardBottom + XMB_STEAM_TITLE_GAP, l.titleY, delta)
        assertEquals(l.fH * 2f / 3f, l.fW, delta)
        assertEquals(6f, l.gap, delta)
        assertEquals(l.fH * XMB_STEAM_WIDE_RATIO, l.wideW, delta)
    }

    @Test fun layout_tallScreenKeepsTheCoverTopsAtHalfTheScreen() {
        val l = XmbSteamLayout(w = 1280f, h = 800f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(400f, l.cardTop, delta)
        // From there down to the title block, which ends 4 dp above the tabs' text.
        val titleBottom = 800f * 0.92f - XMB_STEAM_TAB_TEXT_H / 2f - XMB_STEAM_TABS_GAP
        assertEquals(titleBottom - XMB_STEAM_TITLE_H - XMB_STEAM_TITLE_GAP - 400f, l.fH, delta)
        assertTrue(l.fH > 800f * 0.32f)
        // The title is just under the covers and the tabs well below it.
        assertEquals(l.cardBottom + XMB_STEAM_TITLE_GAP, l.titleY, delta)
        assertEquals(800f * 0.92f, l.tabsCentre, delta)
    }

    @Test fun layout_titleJustUnderTheCardsAndTabsBelowIt() {
        for (h in listOf(393f, 800f)) {
            val l = XmbSteamLayout(w = 851f, h = h, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
            assertEquals(l.cardBottom + XMB_STEAM_TITLE_GAP, l.titleY, delta)
            // The title and the subtitle under it end above the tabs' text.
            assertTrue(l.titleY + XMB_STEAM_TITLE_H <= l.tabsCentre - XMB_STEAM_TAB_TEXT_H / 2f - XMB_STEAM_TABS_GAP + delta)
            assertTrue(l.tabsTop + XMB_STEAM_TABS_H <= h)
            // The covers keep clear of the floating tab bar, even lifted and larger.
            assertTrue(l.cardTop - l.lift - l.fH * (XMB_STEAM_OPTS_SCALE - 1f) >= 50f - delta)
        }
    }

    @Test fun layout_narrowScreenKeepsTheTabsAboveTheHints() {
        val l = XmbSteamLayout(w = 600f, h = 360f, clipTop = 50f, bottomClear = 100f, k = 1f, rowH = 40f, extra = 6f)
        assertTrue(l.tabsTop + XMB_STEAM_TABS_H <= 360f - 100f + delta)
        assertTrue(l.titleY + XMB_STEAM_TITLE_H <= l.tabsCentre - XMB_STEAM_TAB_TEXT_H / 2f - XMB_STEAM_TABS_GAP + delta)
    }

    @Test fun layout_rowMarginIsAbout56DpOnAPhoneInLandscape() {
        val l = XmbSteamLayout(w = 700f, h = 360f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertTrue(l.edge in 56f..60f)
        // The row starts at it and its last card ends at the same margin from the right.
        val r = l.row(30)
        assertEquals(l.edge, r.x(0, 0f), delta)
        assertEquals(700f - l.edge, r.x(29, 29f) + l.fW * XMB_STEAM_FOCUS_SCALE, delta)
    }

    @Test fun layout_optionsColumnOpensUnderTheLiftedCard() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(l.cardBottom - l.lift + 12f, l.colTop, delta)
        assertTrue(l.nVis in 2..8)
        // The gaps scale with the screen like every other dp of the layout.
        val big = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1.5f, rowH = 40f, extra = 6f)
        assertEquals(9f, big.gap, delta)
        assertEquals(big.cardBottom - big.lift + 18f, big.colTop, delta)
        // The column and the card above it start at the same left margin.
        assertEquals(l.edge, l.row(30).xOpts(5, 5f, 5, XMB_STEAM_OPTS_SCALE, 1f), delta)
        assertTrue(XMB_STEAM_OPTS_SCALE > XMB_STEAM_FOCUS_SCALE)
    }

    @Test fun xOpts_atRestIsTheRow() {
        val r = row(30)
        for (i in listOf(0, 7, 12, 29)) assertEquals(r.x(i, 12f), r.xOpts(i, 12f, 12, 1.2f, 0f), delta)
    }

    @Test fun xOpts_liftedCardSitsAtTheMarginAndTheOthersFollowToItsRight() {
        val r = row(30)
        // A portrait card (100 wide), drawn 1.2 times larger: the next card starts one gap after its drawn width.
        assertEquals(20f, r.xOpts(12, 12f, 12, 1.2f, 1f), delta)
        assertEquals(20f + 120f + 10f, r.xOpts(13, 12f, 12, 1.2f, 1f), delta)
        assertEquals(20f + 120f + 10f + 110f, r.xOpts(14, 12f, 12, 1.2f, 1f), delta)
        // The earlier ones run off to the left, one pitch apart.
        assertEquals(20f - 110f, r.xOpts(11, 12f, 12, 1.2f, 1f), delta)
    }

    @Test fun xOpts_theWideFirstCardAlreadySitsAtTheMargin() {
        val r = row(30)
        assertEquals(r.x(0, 0f), r.xOpts(0, 0f, 0, 1.2f, 1f), delta)
        // Its next neighbour follows the larger drawn width (200 * 1.2) and a gap.
        assertEquals(20f + 240f + 10f, r.xOpts(1, 0f, 0, 1.2f, 1f), delta)
    }

    @Test fun xOpts_glidesLinearlyBetweenTheRestAndTheLiftedRow() {
        val r = row(30)
        val rest = r.x(13, 12f)
        val lifted = r.xOpts(13, 12f, 12, 1.2f, 1f)
        assertEquals((rest + lifted) / 2f, r.xOpts(13, 12f, 12, 1.2f, 0.5f), delta)
        // Past the ends of the animation it stays at them.
        assertEquals(lifted, r.xOpts(13, 12f, 12, 1.2f, 1.7f), delta)
        assertEquals(rest, r.xOpts(13, 12f, 12, 1.2f, -0.3f), delta)
    }

    @Test fun xOpts_keepsItsAnchorWhileTheCursorMovesOn() {
        // Going back after the cursor has moved from card 12 to 13 (which closes the options): the lifted layout stays around card 12.
        val r = row(30)
        assertEquals(20f, r.xOpts(12, 13f, 12, 1.2f, 1f), delta)
        assertEquals(20f + 120f + 10f, r.xOpts(13, 13f, 12, 1.2f, 1f), delta)
    }

    @Test fun xOpts_withTheFocusLiftNeighboursStayAGapApart() {
        val r = row(30, focus = XMB_STEAM_FOCUS_SCALE)
        val s = XMB_STEAM_OPTS_SCALE
        assertEquals(r.xOpts(5, 5f, 5, s, 1f) + 100f * s + 10f, r.xOpts(6, 5f, 5, s, 1f), delta)
        assertEquals(20f, r.xOpts(5, 5f, 5, s, 1f), delta)
    }

    @Test fun xOpts_emptyRow() {
        assertEquals(20f, row(0).xOpts(0, 0f, 0, 1.2f, 1f), delta)
    }

    @Test fun layout_onlyTheFirstGamesCardIsWide() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        assertEquals(l.wideW, l.cardW(0, 3), delta)
        assertEquals(l.fW, l.cardW(1, 3), delta)
        assertEquals(l.fW, l.cardW(0, 0), delta)
        assertEquals(l.wideW, l.row(3).firstW, delta)
        assertEquals(3, l.row(3).count)
    }

    @Test fun layout_titleStaysOnTheScreenWhenTheRowIsPinnedRight() {
        val l = XmbSteamLayout(w = 851f, h = 393f, clipTop = 50f, bottomClear = 46f, k = 1f, rowH = 40f, extra = 6f)
        val row = l.row(30)
        assertTrue(l.titleX(row, 29, 29f) + l.titleW <= 851f - l.edge + delta)
        assertEquals(row.x(0, 0f), l.titleX(row, 0, 0f), delta)
    }

    @Test fun wideCover_neverWhileLandscapeArtMayStillArrive() {
        // Loading: nothing failed yet. Everything failed but the lookup of more art is still running. Neither shows the cropped cover.
        assertEquals(false, xmbWideCoverShows(allFailed = false, resolved = false, latched = false))
        assertEquals(false, xmbWideCoverShows(allFailed = false, resolved = true, latched = false))
        assertEquals(false, xmbWideCoverShows(allFailed = true, resolved = false, latched = false))
    }

    @Test fun wideCover_onlyWhenEverySourceFailedAndTheLookupIsDone_thenForGood() {
        assertEquals(true, xmbWideCoverShows(allFailed = true, resolved = true, latched = false))
        // Once decided for the game it stays decided, even while a new source list is being tried (failed is reset by it).
        assertEquals(true, xmbWideCoverShows(allFailed = false, resolved = true, latched = true))
    }

    @Test fun backdropBlur_followsTheOptionsGlideAndIsClamped() {
        assertEquals(0f, xmbSteamBackdropBlur(0f, 24f), delta)
        assertEquals(12f, xmbSteamBackdropBlur(0.5f, 24f), delta)
        assertEquals(24f, xmbSteamBackdropBlur(1f, 24f), delta)
        assertEquals(24f, xmbSteamBackdropBlur(1.4f, 24f), delta)
        assertEquals(0f, xmbSteamBackdropBlur(-0.2f, 24f), delta)
    }

    @Test fun backdropDim_isLighterBesideABlurAndStrongerWithout() {
        assertEquals(0f, xmbSteamBackdropDim(0f, true), delta)
        assertEquals(0.35f, xmbSteamBackdropDim(1f, true), delta)
        assertEquals(0.55f, xmbSteamBackdropDim(1f, false), delta)
        assertEquals(0.275f, xmbSteamBackdropDim(0.5f, false), delta)
    }

    @Test fun layout_portraitScreenKeepsTheWideCardOnScreen() {
        val l = XmbSteamLayout(w = 393f, h = 851f, clipTop = 50f, bottomClear = 46f, k = 0.95f, rowH = 46f, extra = 6f)
        assertTrue(l.wideW + 2 * l.edge <= 393f)
        // Lifted and larger at the left margin, it still fits.
        assertTrue(l.wideW * XMB_STEAM_OPTS_SCALE + l.edge <= 393f)
    }

    // ── Touch ──

    @Test fun vSwipe_needsTheThresholdEitherWay() {
        assertEquals(XmbVSwipe.NONE, xmbVSwipe(0f, 28f))
        assertEquals(XmbVSwipe.NONE, xmbVSwipe(27f, 28f))
        assertEquals(XmbVSwipe.NONE, xmbVSwipe(-27f, 28f))
        assertEquals(XmbVSwipe.DOWN, xmbVSwipe(28f, 28f))
        assertEquals(XmbVSwipe.UP, xmbVSwipe(-28f, 28f))
        assertEquals(XmbVSwipe.UP, xmbVSwipe(-90f, 28f))
    }

    @Test fun homeSwipe_upOpensTheTabsPageFromTheGamesOnly() {
        assertEquals(XmbSwipeAction.OPEN_TABS, xmbHomeSwipeAction(XmbVSwipe.UP, optsOpen = false, tabsAvailable = true))
        assertEquals(XmbSwipeAction.NONE, xmbHomeSwipeAction(XmbVSwipe.UP, optsOpen = false, tabsAvailable = false))
        // With the options open an upward swipe does nothing (it never opens the page over them).
        assertEquals(XmbSwipeAction.NONE, xmbHomeSwipeAction(XmbVSwipe.UP, optsOpen = true, tabsAvailable = true))
    }

    @Test fun homeSwipe_downClosesTheOptionsAndDoesNothingOnTheGames() {
        assertEquals(XmbSwipeAction.CLOSE_OPTIONS, xmbHomeSwipeAction(XmbVSwipe.DOWN, optsOpen = true, tabsAvailable = true))
        assertEquals(XmbSwipeAction.CLOSE_OPTIONS, xmbHomeSwipeAction(XmbVSwipe.DOWN, optsOpen = true, tabsAvailable = false))
        assertEquals(XmbSwipeAction.NONE, xmbHomeSwipeAction(XmbVSwipe.DOWN, optsOpen = false, tabsAvailable = true))
        assertEquals(XmbSwipeAction.NONE, xmbHomeSwipeAction(XmbVSwipe.NONE, optsOpen = true, tabsAvailable = true))
    }

    @Test fun tapWithOptionsOpen_closesThemOnlyAboveTheColumnsTopEdge() {
        assertTrue(xmbTapClosesOptions(tapY = 120f, optionsTop = 300f))
        assertTrue(xmbTapClosesOptions(tapY = 299.5f, optionsTop = 300f))
        // At the column's height or lower: left alone.
        assertTrue(!xmbTapClosesOptions(tapY = 300f, optionsTop = 300f))
        assertTrue(!xmbTapClosesOptions(tapY = 640f, optionsTop = 300f))
    }

    @Test fun tapGate_dropsABounceAndAcceptsTheNextRealTap() {
        assertTrue(!xmbTapGateOpen(lastMs = 1000L, nowMs = 1100L))
        assertTrue(xmbTapGateOpen(lastMs = 1000L, nowMs = 1000L + XMB_TAP_GAP_MS))
        assertTrue(xmbTapGateOpen(lastMs = 0L, nowMs = 50_000L))
        // A clock that went backwards does not lock taps out.
        assertTrue(xmbTapGateOpen(lastMs = 5000L, nowMs = 4000L))
    }

    @Test fun layout_aTouchSizedRowStillLeavesRoomForTheOptions() {
        val l = XmbSteamLayout(w = 800f, h = 360f, clipTop = 50f, bottomClear = 46f, k = 0.95f, rowH = XMB_STEAM_MIN_ROW, extra = 6f)
        assertTrue(l.nVis >= 2)
        assertTrue(XMB_STEAM_MIN_ROW >= 48f)
    }

    // ── The details panel ──

    // The heights xmbInfoPlan charges at the default font scale: facts 6 + 17, header 19, dots 5 + 14, description 5 + 15 a line.
    private fun used(p: XmbInfoPlan, headerLines: Int = 1, dotsLines: Int = 1, factsLines: Int = 1): Float {
        var h = 0f
        if (p.facts) h += 6f + 17f * factsLines + 4f * (factsLines - 1)
        if (p.header) h += 19f * headerLines + 4f * (headerLines - 1)
        if (p.dots) h += 5f + 14f * dotsLines + 4f * (dotsLines - 1)
        if (p.descLines > 0) h += 5f + 15f * p.descLines
        return h
    }

    @Test fun infoPlan_roomyShowsEverythingWithTheWantedDescription() {
        val p = xmbInfoPlan(availH = 200f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 3)
        assertTrue(p.header && p.dots && p.facts)
        assertEquals(3, p.descLines)
    }

    @Test fun infoPlan_theDescriptionShrinksFirst() {
        // Header, dots and facts take 61; 96 leaves 35 for the description: 5 for its gap and two lines.
        val p = xmbInfoPlan(availH = 96f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 5)
        assertTrue(p.header && p.dots && p.facts)
        assertEquals(2, p.descLines)
    }

    @Test fun infoPlan_thenTheDotsLine_thenThePillsLine_theFactsRowLast() {
        val noDesc = xmbInfoPlan(availH = 61f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 3)
        assertTrue(noDesc.header && noDesc.dots && noDesc.facts)
        assertEquals(0, noDesc.descLines)
        val noDots = xmbInfoPlan(availH = 50f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 3)
        assertTrue(noDots.header && noDots.facts && !noDots.dots)
        assertEquals(0, noDots.descLines)
        val factsOnly = xmbInfoPlan(availH = 30f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 3)
        assertTrue(factsOnly.facts && !factsOnly.header && !factsOnly.dots)
        assertEquals(0, factsOnly.descLines)
        val nothing = xmbInfoPlan(availH = 20f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 3)
        assertTrue(!nothing.facts && !nothing.header && !nothing.dots)
    }

    @Test fun infoPlan_neverUsesMoreThanTheRoom_andMoreRoomNeverHidesABlock() {
        var prev: XmbInfoPlan? = null
        for (hh in 0..260) {
            val h = hh.toFloat()
            val p = xmbInfoPlan(h, 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 4)
            assertTrue("$h used ${used(p)}", used(p) <= h + 0.001f)
            val q = prev
            if (q != null) {
                assertTrue(!q.facts || p.facts)
                assertTrue(!q.header || p.header)
                assertTrue(!q.dots || p.dots)
                assertTrue(p.descLines >= q.descLines)
            }
            prev = p
        }
    }

    @Test fun infoPlan_wrappedBlocksCostTheirLines() {
        // Two header lines (19 + 4 + 19) and two facts lines (6 + 17 + 4 + 17): 42 + 44 = 86, no dots wanted.
        val tight = xmbInfoPlan(availH = 85f, fontScale = 1f, headerLines = 2, dotsLines = 0, factsLines = 2, descWanted = 2)
        assertTrue(tight.facts && !tight.header)
        val fits = xmbInfoPlan(availH = 86f, fontScale = 1f, headerLines = 2, dotsLines = 0, factsLines = 2, descWanted = 2)
        assertTrue(fits.facts && fits.header)
        assertEquals(0, fits.descLines)
    }

    @Test fun infoPlan_aBiggerFontNeedsMoreRoom() {
        val normal = xmbInfoPlan(availH = 61f, fontScale = 1f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 0)
        assertTrue(normal.header && normal.dots && normal.facts)
        val big = xmbInfoPlan(availH = 61f, fontScale = 1.5f, headerLines = 1, dotsLines = 1, factsLines = 1, descWanted = 0)
        assertTrue(big.facts && big.header && !big.dots)
    }

    @Test fun infoPlan_noDescriptionOrNoSpecsLeavesJustTheRest() {
        // An Android entry: no pills and no dots beyond its store tag; a game with no description.
        val p = xmbInfoPlan(availH = 120f, fontScale = 1f, headerLines = 1, dotsLines = 0, factsLines = 1, descWanted = 0)
        assertTrue(p.header && p.facts && !p.dots)
        assertEquals(0, p.descLines)
    }

    @Test fun flowLines_wrapWhenAnItemDoesNotFit() {
        assertEquals(0, xmbFlowLines(emptyList(), 10f, 100f))
        assertEquals(1, xmbFlowLines(listOf(50f, 50f), 10f, 110f))
        assertEquals(2, xmbFlowLines(listOf(50f, 50f, 50f), 10f, 120f))
        assertEquals(1, xmbFlowLines(listOf(300f), 10f, 100f))
        assertEquals(2, xmbFlowLines(listOf(300f, 20f), 10f, 100f))
    }
}
