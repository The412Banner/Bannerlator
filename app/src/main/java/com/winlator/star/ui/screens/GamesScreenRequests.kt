package com.winlator.star.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The Games screen's per-game ⋮ menu, in the order the classic menu lists it, plus the + button. */
enum class GameMenuAction(val label: String) {
    SETTINGS("Settings"),
    REMOVE("Remove"),
    CLONE("Clone to container"),
    COPY_TO_DRIVE_C("Copy to Drive C…"),
    CHANGE_EXE("Change executable…"),
    ADD_TO_HOME("Add to home screen"),
    EXPORT("Export"),
    GAME_DETAILS("Game Details"),
    CLOUD_SAVES("Cloud Saves"),
    BACKUP_SAVES("Back up saves"),
    RESTORE_SAVES("Restore saves"),
    SCRAPE_COVER("Scrape cover"),
    COMMUNITY_CONFIGS("Community configs"),
    VIEW_LOGS("View logs"),
    PROPERTIES("Properties"),
    ADD_GAME("Add a game"),
}

/**
 * One-shot hand-off into the Games screen (ShortcutsScreen). The Deck home routes the menu actions
 * whose dialogs live inside that screen through here: it sets [pending], navigates to the Games route,
 * and the screen opens the matching dialog once its list has loaded, then clears the request.
 * Nothing sets it in the classic shell, so the Games screen behaves exactly as before there.
 */
object GamesScreenRequests {
    data class Request(val action: GameMenuAction, val shortcutPath: String? = null)

    var pending by mutableStateOf<Request?>(null)
}
