package com.winlator.star.ui.screens

import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.CommunityConfigRef
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.communityconfigs.WorkerConfigEntry

// The non-UI rules of the community configs browser (the dialog, CommunityCatalogBrowser in ShortcutsScreen.kt). Kept free of Compose and
// Android so they can be unit-tested.

/** The old dialog's store filter: every game, the Steam ones (a numeric identity) or the rest (a "name:" title key). */
internal enum class CatalogStoreFilter { ALL, STEAM, TITLE }

/** How the games list is ordered while no search is typed: most configs first, A to Z, or most devices first (the dialog's third chip). */
internal enum class CatalogSort { CONFIGS, NAME, DEVICES }

/** A search shorter than this many characters is no search (the same floor [GameMatcher.search] holds). */
internal const val COMMUNITY_MIN_QUERY = 2

/** The most games a search returns. */
private const val COMMUNITY_SEARCH_LIMIT = 200

/** True when [query] is long enough to search by. */
internal fun communityIsSearch(query: String): Boolean = query.trim().length >= COMMUNITY_MIN_QUERY

/**
 * The games the browser lists: [games] searched by [query] (ranked by relevance, which a typed search keeps whatever [sort] says), then
 * kept when [keep] says so, then, with no search, ordered by [sort].
 */
internal fun communityVisibleGames(
    games: List<CanonicalGame>,
    query: String,
    sort: CatalogSort,
    keep: (CanonicalGame) -> Boolean,
): List<CanonicalGame> {
    val searching = communityIsSearch(query)
    val base = if (searching) GameMatcher.search(query, games, limit = COMMUNITY_SEARCH_LIMIT) else games
    val filtered = base.filter(keep)
    if (searching) return filtered
    return when (sort) {
        CatalogSort.CONFIGS -> filtered.sortedByDescending { it.configCount }
        CatalogSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        CatalogSort.DEVICES -> filtered.sortedByDescending { it.devices.size }
    }
}

/** The pick for one uploaded config of [game] found under the worker folder [folder]: the file the apply and details flows act on. */
internal fun communityFilePick(game: CanonicalGame, folder: String, entry: WorkerConfigEntry): CommunityPick.File =
    CommunityPick.File(
        game,
        CommunityConfigRef(game, folder, entry.filename, entry.sha.ifBlank { null }, ns = if (entry.appSource == "bannerlator") "bannerlator" else ""),
        entry,
    )
