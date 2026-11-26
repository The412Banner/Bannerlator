package com.winlator.star.ui.screens

import com.winlator.star.store.SteamFriendsStore
import com.winlator.star.store.SteamFriendsStore.Presence
import com.winlator.star.store.friendsOnlineCount
import com.winlator.star.store.steamEventAffectsFriendsAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB Home's Friends tab: the sections and rows of the roster, the cursor's moves in them, a friend's actions and the online count. */
class XmbFriendsLogicTest {

    private fun friend(id: Long, name: String, presence: Presence, game: Int = 0, nick: String? = null) =
        SteamFriendsStore.SteamFriend(id, name, nick, presence, "status", game, if (game != 0) "Game" else null, null)

    private val none = emptyList<SteamFriendsStore.SteamFriend>()

    private fun keys(rows: List<XmbFriendsRow>) = rows.map { it.key }

    @Test fun rows_sectionsInOrder_eachWithItsFriendsByName() {
        val rows = xmbFriendsRows(
            friends = listOf(
                friend(1, "zed", Presence.OFFLINE),
                friend(2, "Bob", Presence.ONLINE),
                friend(3, "amy", Presence.ONLINE),
                friend(4, "Cat", Presence.IN_GAME, game = 10),
                friend(5, "dan", Presence.AWAY),
            ),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        assertEquals(
            listOf(
                "toolbar",
                "hdr:IN_GAME", "f:4",
                "hdr:ONLINE", "f:3", "f:2",
                "hdr:AWAY", "f:5",
                "hdr:OFFLINE", "f:1",
            ),
            keys(rows),
        )
    }

    @Test fun rows_headerCarriesTheCountAndFoldState() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "a", Presence.OFFLINE), friend(2, "b", Presence.OFFLINE)),
            incoming = none, outgoing = none, collapsed = setOf(Presence.OFFLINE),
        )
        // A folded section keeps its heading and count but lists no friends.
        assertEquals(listOf("toolbar", "hdr:OFFLINE"), keys(rows))
        val header = rows[1] as XmbFriendsRow.Header
        assertEquals(2, header.count)
        assertTrue(header.collapsed)
    }

    @Test fun rows_emptySectionsAreLeftOut_unknownComesLast() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "a", Presence.UNKNOWN), friend(2, "b", Presence.ONLINE)),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        assertEquals(listOf("toolbar", "hdr:ONLINE", "f:2", "hdr:UNKNOWN", "f:1"), keys(rows))
    }

    @Test fun rows_requestsSitOnTopUnderTheirHeading_incomingBeforeOutgoing() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(9, "x", Presence.ONLINE)),
            incoming = listOf(friend(2, "Zoe", Presence.OFFLINE), friend(1, "al", Presence.OFFLINE)),
            outgoing = listOf(friend(3, "Meg", Presence.OFFLINE)),
            collapsed = emptySet(),
        )
        assertEquals(listOf("toolbar", "requests", "in:1", "in:2", "out:3", "hdr:ONLINE", "f:9"), keys(rows))
        assertEquals(3, (rows[1] as XmbFriendsRow.RequestsHeader).count)
    }

    @Test fun rows_aNicknameSortsAsTheNameShown() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "Alpha", Presence.ONLINE, nick = "zzz"), friend(2, "Beta", Presence.ONLINE)),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        assertEquals(listOf("toolbar", "hdr:ONLINE", "f:2", "f:1"), keys(rows))
    }

    @Test fun empty_onlyTheToolbarIsEmpty() {
        assertTrue(xmbFriendsEmpty(xmbFriendsRows(none, none, none, emptySet())))
        assertFalse(xmbFriendsEmpty(xmbFriendsRows(none, listOf(friend(1, "a", Presence.OFFLINE)), none, emptySet())))
        // Even a folded roster is not empty: its headings are there.
        assertFalse(xmbFriendsEmpty(xmbFriendsRows(listOf(friend(1, "a", Presence.OFFLINE)), none, none, setOf(Presence.OFFLINE))))
    }

    @Test fun resolve_findsTheKey_elseTheNearestSelectableAfterThenBefore() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "a", Presence.ONLINE), friend(2, "b", Presence.ONLINE), friend(3, "c", Presence.OFFLINE)),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        // toolbar, hdr:ONLINE, f:1, f:2, hdr:OFFLINE, f:3
        assertEquals(3, xmbFriendsResolve(rows, "f:2", 0))
        assertEquals(4, xmbFriendsResolve(rows, "f:gone", 4))
        assertEquals(5, xmbFriendsResolve(rows, "f:gone", 99))
        assertEquals(0, xmbFriendsResolve(rows, "f:gone", -3))
        assertEquals(0, xmbFriendsResolve(listOf(XmbFriendsRow.Toolbar), "x", 5))
    }

    // toolbar, then two sections of 3 and 2 friends:
    // 0 toolbar, 1 hdr:ONLINE, 2 f:1, 3 f:2, 4 f:3, 5 hdr:OFFLINE, 6 f:4, 7 f:5
    private fun roster() = xmbFriendsRows(
        friends = listOf(
            friend(1, "a", Presence.ONLINE), friend(2, "b", Presence.ONLINE), friend(3, "c", Presence.ONLINE),
            friend(4, "d", Presence.OFFLINE), friend(5, "e", Presence.OFFLINE),
        ),
        incoming = none, outgoing = none, collapsed = emptySet(),
    )

    @Test fun lines_headingsSpanTheWidth_friendsFillLinesOfTwo() {
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6, 7)),
            xmbFriendsLines(roster(), 2),
        )
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(7)),
            xmbFriendsLines(roster(), 1),
        )
    }

    @Test fun lines_requestsOfBothKindsShareTheirRuns() {
        val rows = xmbFriendsRows(
            friends = emptyList(),
            incoming = listOf(friend(1, "a", Presence.OFFLINE), friend(2, "b", Presence.OFFLINE)),
            outgoing = listOf(friend(3, "c", Presence.OFFLINE)),
            collapsed = emptySet(),
        )
        // toolbar(0), requests(1), in:1(2), in:2(3), out:3(4)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), xmbFriendsLines(rows, 2))
    }

    @Test fun lines_aFoldedSectionHasOnlyItsHeading() {
        val rows = xmbFriendsRows(listOf(friend(1, "a", Presence.OFFLINE)), none, none, setOf(Presence.OFFLINE))
        assertEquals(listOf(listOf(0), listOf(1)), xmbFriendsLines(rows, 2))
    }

    @Test fun move_downAndUpKeepTheWantedColumn_aShortLineClamps() {
        val rows = roster()
        val lines = xmbFriendsLines(rows, 2)
        // From the toolbar down onto the heading, then onto the first line of friends: column 0 or 1 as wanted.
        assertEquals(1, xmbFriendsVertical(rows, lines, 0, down = true, wantCol = 1))
        assertEquals(2, xmbFriendsVertical(rows, lines, 1, down = true, wantCol = 0))
        assertEquals(3, xmbFriendsVertical(rows, lines, 1, down = true, wantCol = 1))
        // Next line has one friend: clamps to it, from either column.
        assertEquals(4, xmbFriendsVertical(rows, lines, 2, down = true, wantCol = 0))
        assertEquals(4, xmbFriendsVertical(rows, lines, 3, down = true, wantCol = 1))
        // Back up from the lone friend: onto the wanted column of the full line.
        assertEquals(2, xmbFriendsVertical(rows, lines, 4, down = false, wantCol = 0))
        assertEquals(3, xmbFriendsVertical(rows, lines, 4, down = false, wantCol = 1))
        // Down from the last line stays.
        assertEquals(7, xmbFriendsVertical(rows, lines, 7, down = true, wantCol = 1))
    }

    @Test fun move_skipsTheRequestsHeading_upFromTheTopGoesToTheBar() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(9, "x", Presence.ONLINE)),
            incoming = listOf(friend(1, "a", Presence.OFFLINE)),
            outgoing = none, collapsed = emptySet(),
        )
        // toolbar(0), requests(1, not selectable), in:1(2), hdr:ONLINE(3), f:9(4)
        val lines = xmbFriendsLines(rows, 2)
        assertEquals(2, xmbFriendsVertical(rows, lines, 0, down = true, wantCol = 0))
        assertEquals(0, xmbFriendsVertical(rows, lines, 2, down = false, wantCol = 0))
        assertEquals(XMB_TWO_COL_TO_BAR, xmbFriendsVertical(rows, lines, 0, down = false, wantCol = 0))
        assertEquals(4, xmbFriendsVertical(rows, lines, 4, down = true, wantCol = 0))
    }

    @Test fun move_aStrayIndexIsSafe() {
        val rows = xmbFriendsRows(none, none, none, emptySet())
        val lines = xmbFriendsLines(rows, 2)
        assertEquals(XMB_TWO_COL_TO_BAR, xmbFriendsVertical(rows, lines, 7, down = false, wantCol = 0))
        assertEquals(0, xmbFriendsVertical(emptyList(), emptyList(), 0, down = true, wantCol = 0))
    }

    @Test fun side_leftRightGoBetweenTheColumnsOfALine() {
        val lines = xmbFriendsLines(roster(), 2)
        assertEquals(3, xmbTwoColSide(lines, 2, 1))
        assertEquals(2, xmbTwoColSide(lines, 3, -1))
        assertEquals(4, xmbTwoColSide(lines, 4, 1))
        // A heading is a line of one.
        assertEquals(1, xmbTwoColSide(lines, 1, 1))
    }

    @Test fun side_buttonsOnTheToolbarAlways_onACardOnceStepIn() {
        val f = friend(5, "a", Presence.IN_GAME, game = 1)
        assertTrue(xmbFriendsSideIsButtons(XmbFriendsRow.Toolbar, false))
        assertFalse(xmbFriendsSideIsButtons(XmbFriendsRow.Friend(f), false))
        assertTrue(xmbFriendsSideIsButtons(XmbFriendsRow.Friend(f), true))
        assertFalse(xmbFriendsSideIsButtons(XmbFriendsRow.Incoming(f), false))
        assertTrue(xmbFriendsSideIsButtons(XmbFriendsRow.Incoming(f), true))
        assertFalse(xmbFriendsSideIsButtons(XmbFriendsRow.Outgoing(f), true))
        assertFalse(xmbFriendsSideIsButtons(XmbFriendsRow.Header(Presence.ONLINE, 3, false), true))
    }

    @Test fun actions_gameOnlyWhileInAGame_removeIsLast() {
        assertEquals(
            listOf(XmbFriendAction.MESSAGE, XmbFriendAction.GAME, XmbFriendAction.PROFILE, XmbFriendAction.REMOVE),
            xmbFriendActions(friend(1, "a", Presence.IN_GAME, game = 440)),
        )
        assertEquals(
            listOf(XmbFriendAction.MESSAGE, XmbFriendAction.PROFILE, XmbFriendAction.REMOVE),
            xmbFriendActions(friend(1, "a", Presence.ONLINE)),
        )
        // In a game with no app id (a non-Steam game): nothing to open.
        assertEquals(
            listOf(XmbFriendAction.MESSAGE, XmbFriendAction.PROFILE, XmbFriendAction.REMOVE),
            xmbFriendActions(friend(1, "a", Presence.IN_GAME, game = 0)),
        )
    }

    @Test fun buttons_perRow() {
        val f = friend(5, "a", Presence.IN_GAME, game = 1)
        assertEquals(XMB_FRIENDS_TOOLBAR_BUTTONS, xmbFriendsButtonCount(XmbFriendsRow.Toolbar, false))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Incoming(f), false))
        assertEquals(2, xmbFriendsButtonCount(XmbFriendsRow.Incoming(f), true))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Outgoing(f), false))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Friend(f), false))
        assertEquals(4, xmbFriendsButtonCount(XmbFriendsRow.Friend(f), true))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Header(Presence.ONLINE, 3, false), false))
    }

    @Test fun online_countsReachableFriends_notOfflineOrUnknown() {
        val friends = listOf(
            friend(1, "a", Presence.IN_GAME, game = 1),
            friend(2, "b", Presence.ONLINE),
            friend(3, "c", Presence.AWAY),
            friend(4, "d", Presence.OFFLINE),
            friend(5, "e", Presence.UNKNOWN),
        )
        assertEquals(3, friendsOnlineCount(friends))
        assertEquals(0, friendsOnlineCount(emptyList()))
    }

    // --- the cursor after the roster changed under it ---

    @Test fun normalise_aRowThatWentStartsTheCursorAfreshOnTheRowItFellTo() {
        // The last friend was removed with Remove armed: only the toolbar is left, and the button must not stay on 2 (Settings is 1).
        val rows = xmbFriendsRows(none, none, none, emptySet())
        val stale = XmbFriendsCursor(key = "f:4", sub = 2, openKey = "f:4", confirmRemove = true)
        val index = xmbFriendsResolve(rows, stale.key, 3)
        assertEquals(XmbFriendsCursor(XmbFriendsRow.Toolbar.key, 0, null, false), xmbFriendsNormalise(rows, stale, index))
    }

    @Test fun normalise_aRequestAnsweredMovesToTheNextRowWithoutItsButtons() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(9, "z", Presence.ONLINE)),
            incoming = emptyList(), outgoing = none, collapsed = emptySet(),
        )
        val stale = XmbFriendsCursor(key = "in:1", sub = 1, openKey = "in:1", confirmRemove = false)
        val index = xmbFriendsResolve(rows, stale.key, 2)
        assertEquals(XmbFriendsCursor(rows[index].key, 0, null, false), xmbFriendsNormalise(rows, stale, index))
    }

    @Test fun normalise_aCursorOnItsRowIsLeftAlone() {
        val rows = roster()
        val toolbar = XmbFriendsCursor(XmbFriendsRow.Toolbar.key, 1, null, false)
        assertEquals(toolbar, xmbFriendsNormalise(rows, toolbar, 0))
        val open = XmbFriendsCursor("f:2", 2, "f:2", true)
        assertEquals(open, xmbFriendsNormalise(rows, open, 3))
    }

    @Test fun normalise_aFriendWithFewerActionsClampsTheButtonAndDropsTheWaitingRemove() {
        // Four buttons in a game (the cursor on Remove), three once the friend is out of it.
        val rows = xmbFriendsRows(listOf(friend(5, "a", Presence.ONLINE)), none, none, emptySet())
        val at = rows.indexOfFirst { it.key == "f:5" }
        val cursor = XmbFriendsCursor("f:5", 3, "f:5", true)
        assertEquals(XmbFriendsCursor("f:5", 2, "f:5", false), xmbFriendsNormalise(rows, cursor, at))
    }

    @Test fun normalise_aButtonOnAClosedCardOrAStaleOpenKeyIsReset() {
        val rows = roster()
        assertEquals(XmbFriendsCursor("f:2", 0, null, false), xmbFriendsNormalise(rows, XmbFriendsCursor("f:2", 3, null, false), 3))
        assertEquals(XmbFriendsCursor("f:2", 0, null, false), xmbFriendsNormalise(rows, XmbFriendsCursor("f:2", 0, "f:3", false), 3))
    }

    @Test fun normalise_anIndexOffTheListChangesNothing() {
        val cursor = XmbFriendsCursor("f:2", 1, "f:2", false)
        assertEquals(cursor, xmbFriendsNormalise(emptyList(), cursor, 0))
    }

    @Test fun availabilityIsReReadOnlyForSessionEvents() {
        for (ev in listOf("SteamStatus:ONLINE", "LoggedIn:76561198000000000", "LoggedIn", "LoggedOut", "SessionExpired", "Connected", "Disconnected", "AccountSwitched:76561198000000000")) {
            assertTrue(ev, steamEventAffectsFriendsAvailability(ev))
        }
        for (ev in listOf("LibraryProgress:2:5:10", "LibrarySynced:12", "DepotKeyReady:1", "LoginFailed:Timeout", "NoInternet", "")) {
            assertFalse(ev, steamEventAffectsFriendsAvailability(ev))
        }
    }
}
