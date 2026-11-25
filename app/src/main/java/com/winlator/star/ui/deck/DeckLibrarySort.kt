package com.winlator.star.ui.deck

import com.winlator.star.container.Shortcut
import com.winlator.star.container.ShortcutOrder

// How the library is ordered. The Deck filter dialog's sort chips and the XMB All Games page share this, so the two can't
// drift; the comparisons work on plain keys, so they don't need a Shortcut.

internal enum class LibrarySort(val label: String) {
    NAME("Name A–Z"),
    NAME_DESC("Name Z–A"),
    RECENT("Last played"),
    PLAYTIME("Most played"),
    CONTAINER("Container"),
    STORE("Store"),
}

/** What a sort compares for one game. Read once per game, so ordering a big library does no lookups inside the comparator. */
internal class GameSortKey(
    name: String,
    /** The "recent" order's value: the later of the last launch and the time the game was added (the pinned client: the last launch only). */
    val recent: Long,
    val playMs: Long,
    container: String,
    val store: String,
    /** Listed before everything else, except in Last played (the Windows Steam client). */
    val pinned: Boolean = false,
) {
    val nameKey: String = name.lowercase()
    val containerKey: String = container.lowercase()
}

// The Stores tab's order, then the entries no store owns. The labels are the ones storeLabelOf returns.
private val STORE_ORDER = listOf("Windows Steam", "Steam", "Epic Games", "GOG", "Amazon Games", "Xbox", "Android", "Linux", "Added by me")

/** Where a [storeLabelOf] label sorts; a label nobody knows goes last. */
internal fun storeRank(label: String): Int = STORE_ORDER.indexOf(label).let { if (it < 0) STORE_ORDER.size else it }

/** [list] ordered by [sort]; every order falls back to the name, so equal keys keep a stable, readable order. */
internal fun <T> sortGames(list: List<T>, sort: LibrarySort, key: (T) -> GameSortKey): List<T> {
    val keyed = list.map { it to key(it) }
    val order: Comparator<Pair<T, GameSortKey>> = when (sort) {
        LibrarySort.NAME -> compareBy<Pair<T, GameSortKey>> { it.second.nameKey }
        LibrarySort.NAME_DESC -> compareByDescending<Pair<T, GameSortKey>> { it.second.nameKey }
        LibrarySort.RECENT -> compareByDescending<Pair<T, GameSortKey>> { it.second.recent }.thenBy { it.second.nameKey }
        LibrarySort.PLAYTIME -> compareByDescending<Pair<T, GameSortKey>> { it.second.playMs }.thenBy { it.second.nameKey }
        LibrarySort.CONTAINER -> compareBy<Pair<T, GameSortKey>> { it.second.containerKey }.thenBy { it.second.nameKey }
        LibrarySort.STORE -> compareBy<Pair<T, GameSortKey>> { storeRank(it.second.store) }.thenBy { it.second.nameKey }
    }
    // The pinned launcher leads every order except "Last played": there it would sit ahead of whatever the user actually
    // played last, so the Home's wide first card could never move.
    val comparator = if (sort == LibrarySort.RECENT) order
    else compareByDescending<Pair<T, GameSortKey>> { it.second.pinned }.then(order)
    return keyed.sortedWith(comparator).map { it.first }
}

/**
 * The recent-order value. The pinned Windows Steam client counts only when it was really played: its addedAt is
 * stamped on creation, so it would otherwise lead before anything is launched.
 */
internal fun recentKeyFor(pinned: Boolean, lastPlayed: Long, addedAt: Long): Long =
    if (pinned) ShortcutOrder.recentKey(lastPlayed, 0L) else ShortcutOrder.recentKey(lastPlayed, addedAt)

/** A shortcut's sort key, from the play stats read for the library ([readPlayStats]). */
internal fun gameSortKeyOf(s: Shortcut, stats: Map<String, PlayStats>): GameSortKey {
    val st = stats[s.file.path]
    val pinned = com.winlator.star.store.WindowsSteamClient.isWindowsSteamShortcut(s)
    return GameSortKey(
        name = s.name,
        recent = recentKeyFor(pinned, st?.lastPlayed ?: lastPlayedOf(s), st?.addedAt ?: addedAtOf(s)),
        playMs = st?.ms ?: 0L,
        container = s.container?.name.orEmpty(),
        store = storeLabelOf(s),
        pinned = pinned,
    )
}
