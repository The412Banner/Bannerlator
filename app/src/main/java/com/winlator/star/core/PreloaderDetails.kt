package com.winlator.star.core

/**
 * Accumulated per-game "details" (genres, release year, metacritic, short description) surfaced on
 * the right side of the launch overlay. Populated from a shortcut's Extra Data by
 * `com.winlator.star.ui.screens.buildLaunchDetails`; rendered by the PreloaderOverlay. Pure data (no
 * Android/Compose deps) so it can live in `core` and ride on [PreloaderUi] next to [PreloaderSpec].
 *
 * The cover art and title are already carried by [PreloaderUi] (coverArt/icon + title), so this only
 * holds the extra editorial fields. Everything is optional — an empty instance shows nothing.
 */
data class PreloaderDetails(
    val genres: List<String> = emptyList(),
    val releaseYear: String? = null,
    val metacritic: Int? = null,      // 1..100, null if unavailable
    val description: String? = null,
) {
    /** True when there's at least one field worth showing (gates the whole right-side panel). */
    val hasAny: Boolean
        get() = genres.isNotEmpty() ||
            !releaseYear.isNullOrBlank() ||
            metacritic != null ||
            !description.isNullOrBlank()
}
