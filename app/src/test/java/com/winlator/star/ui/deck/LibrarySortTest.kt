package com.winlator.star.ui.deck

import org.junit.Assert.assertEquals
import org.junit.Test

/** The library orders shared by the Deck filter dialog and the XMB All Games page. */
class LibrarySortTest {

    private fun key(name: String, recent: Long = 0L, playMs: Long = 0L, container: String = "", store: String = "Steam") =
        GameSortKey(name, recent, playMs, container, store)

    private val games = listOf(
        "b-game" to key("b-game", recent = 20, playMs = 5, container = "Wine B", store = "GOG"),
        "A-game" to key("A-game", recent = 0, playMs = 90, container = "wine a", store = "Steam"),
        "c-game" to key("c-game", recent = 30, playMs = 5, container = "Wine B", store = "Added by me"),
        "d-game" to key("d-game", recent = 20, playMs = 0, container = "Wine A", store = "Epic Games"),
    )

    private fun order(sort: LibrarySort) = sortGames(games, sort) { it.second }.map { it.first }

    @Test fun name_ignoresCase() {
        assertEquals(listOf("A-game", "b-game", "c-game", "d-game"), order(LibrarySort.NAME))
        assertEquals(listOf("d-game", "c-game", "b-game", "A-game"), order(LibrarySort.NAME_DESC))
    }

    @Test fun recent_newestFirst_neverPlayedLast_tiesByName() {
        // b and d tie on 20, so the name decides.
        assertEquals(listOf("c-game", "b-game", "d-game", "A-game"), order(LibrarySort.RECENT))
    }

    @Test fun playtime_mostFirst_tiesByName() {
        assertEquals(listOf("A-game", "b-game", "c-game", "d-game"), order(LibrarySort.PLAYTIME))
    }

    @Test fun container_ignoresCase_tiesByName() {
        assertEquals(listOf("A-game", "d-game", "b-game", "c-game"), order(LibrarySort.CONTAINER))
    }

    @Test fun store_followsTheStoresTabOrder_tiesByName() {
        assertEquals(listOf("A-game", "d-game", "b-game", "c-game"), order(LibrarySort.STORE))
    }

    @Test fun pinned_firstInEveryOrder() {
        val withSteam = games + ("z-steam" to GameSortKey("z-steam", 0L, 0L, "Wine Z", "Windows Steam", pinned = true))
        for (sort in LibrarySort.entries) {
            assertEquals(sort.name, "z-steam", sortGames(withSteam, sort) { it.second }.first().first)
        }
    }

    @Test fun emptyList_staysEmpty() {
        assertEquals(emptyList<String>(), sortGames(emptyList<String>(), LibrarySort.RECENT) { key(it) })
    }
}
