package com.winlator.star.container

/**
 * Per-shortcut "Game Details" — the editorial metadata (linked Steam appId, genres, short
 * description, release year, metacritic) shown on the Game Details editor and mirrored onto the
 * launch overlay. Persisted on the shortcut's `[Extra Data]` block (see [Shortcut.putExtra] /
 * [Shortcut.getExtra]) so it rides with the `.desktop` through renames — exactly like the existing
 * `steamAppId` / `customCoverArtPath` extras.
 *
 * Key choice: the linked appId reuses the EXISTING `steamAppId` extra (the one
 * `ShortcutsScreen.recordSteamAppId` / `applySteamCover` write and redist detection reads) rather
 * than a separate `steam_app_id` key, so the cover-art path, redist detection and this editor can
 * never disagree about which game a shortcut is linked to. The four new editorial fields get their
 * own keys.
 */
data class GameDetails(
    val steamAppId: Int? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val releaseYear: String? = null,
    val metacritic: Int? = null,      // 1..100, null if unavailable
) {
    /** True when at least one displayable editorial field is present (gates the launch-screen panel). */
    fun hasDisplayableDetails(): Boolean =
        genres.isNotEmpty() ||
            !description.isNullOrBlank() ||
            !releaseYear.isNullOrBlank() ||
            metacritic != null

    /**
     * Write these details onto [shortcut]'s Extra Data and persist. Blank/empty fields are removed
     * (putExtra(key, null) deletes the key), so clearing a field or Unlinking (steamAppId == null)
     * cleanly drops the extra. Best-effort — swallows nothing here, callers guard against I/O.
     */
    fun writeTo(shortcut: Shortcut) {
        shortcut.putExtra(KEY_APP_ID, steamAppId?.takeIf { it > 0 }?.toString())
        shortcut.putExtra(KEY_GENRES, genres.joinToString(",").takeIf { it.isNotEmpty() })
        shortcut.putExtra(KEY_DESCRIPTION, description?.takeIf { it.isNotBlank() })
        shortcut.putExtra(KEY_RELEASE_YEAR, releaseYear?.takeIf { it.isNotBlank() })
        shortcut.putExtra(KEY_METACRITIC, metacritic?.takeIf { it in 1..100 }?.toString())
        shortcut.saveData()
    }

    companion object {
        const val KEY_APP_ID = "steamAppId"          // reused existing extra (cover + redist)
        const val KEY_GENRES = "genres"
        const val KEY_DESCRIPTION = "description"
        const val KEY_RELEASE_YEAR = "release_year"
        const val KEY_METACRITIC = "metacritic"

        /** Read the accumulated details off [shortcut]'s Extra Data. Never throws; missing == empty. */
        fun from(shortcut: Shortcut): GameDetails {
            val appId = shortcut.getExtra(KEY_APP_ID, "").toIntOrNull()?.takeIf { it > 0 }
            val genres = shortcut.getExtra(KEY_GENRES, "")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
            val description = shortcut.getExtra(KEY_DESCRIPTION, "").takeIf { it.isNotBlank() }
            val releaseYear = shortcut.getExtra(KEY_RELEASE_YEAR, "").takeIf { it.isNotBlank() }
            val metacritic = shortcut.getExtra(KEY_METACRITIC, "").toIntOrNull()?.takeIf { it in 1..100 }
            return GameDetails(appId, genres, description, releaseYear, metacritic)
        }
    }
}
