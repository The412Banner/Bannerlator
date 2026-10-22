package com.winlator.star.store

/**
 * Lightweight in-memory model for a Steam app entry.
 *
 * Populated from PICS data during library sync (Phase 4).
 * Persisted to SQLite in Phase 3 for offline use.
 *
 * Install state is derived at runtime from the presence of
 * SteamGame.installMarkerFile(ctx) on disk.
 */
data class SteamGame(
    val appId: Int,
    val name: String,

    /** Folder name under steamapps/common/ — from PICS config.installdir. */
    val installDir: String,

    /** Icon hash hex string — used to build steamcdn URL. */
    val iconHash: String = "",

    /** Estimated total install size in bytes (sum of depot manifest sizes). */
    val sizeBytes: Long = 0L,

    /** Depots to download for this app (resolved during library sync). */
    val depotIds: List<Int> = emptyList(),

    /** App type string from PICS common.type — "game", "dlc", "tool", etc. */
    val type: String = "game",

    /** True if the game has been fully downloaded (marker file present). */
    val isInstalled: Boolean = false,

    /** Developer name from PICS common.developer. Empty if not available. */
    val developer: String = "",

    /** Metacritic score 0-100 from PICS common.metacritic.score. 0 = not rated. */
    val metacriticScore: Int = 0,

    /** Comma-separated genre names from PICS common.genres. Empty if not available. */
    val genres: String = "",

    /**
     * Steam USER-review percent positive, 0-100, from the store's appreviews summary
     * ([SteamReviewScores]). -1 = no rating (not fetched yet, or too few reviews to rate).
     */
    val reviewPercent: Int = -1,

    /** Positive + negative review votes behind [reviewPercent]; 0 when unrated. */
    val reviewTotal: Int = 0,

    /** Steam's own label for the score ("Very Positive"). Empty when unrated or unknown. */
    val reviewDesc: String = "",

    /** Epoch seconds of the last review fetch; 0 = never fetched. */
    val reviewUpdated: Long = 0L,
) {
    /** Store header image URL — available for all apps, no hash needed. */
    val headerUrl: String
        get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg"

    /** Old-style CDN icon URL — requires iconHash from PICS. */
    val iconUrl: String?
        get() = if (iconHash.isNotEmpty())
            "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$appId/$iconHash.jpg"
        else null

    companion object {
        /** Convert a Java GameRow (from SteamDatabase) to a SteamGame. */
        fun fromGameRow(row: SteamDatabase.GameRow): SteamGame {
            val depotIds = if (row.depotIds.isBlank()) emptyList()
                else row.depotIds.split(",").mapNotNull { it.trim().toIntOrNull() }
            return SteamGame(
                appId          = row.appId,
                name           = row.name,
                installDir     = row.installDir,
                iconHash       = row.iconHash,
                sizeBytes      = row.sizeBytes,
                depotIds       = depotIds,
                type           = row.type,
                isInstalled    = row.isInstalled,
                developer      = row.developer,
                metacriticScore = row.metacriticScore,
                genres         = row.genres,
            )
        }
    }
}
