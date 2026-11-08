package com.winlator.star.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The Games screen's per-game ⋮ menu, in the order the classic menu lists it, plus the + button. */
enum class GameMenuAction(val label: String) {
    SETTINGS("Settings"),
    REMOVE("Remove"),
    CLONE("Clone to container"),
    MOVE_TO_CONTAINER("Move to another container"),
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
    SHARE_LOGS("Share logs"),
    PROPERTIES("Properties"),
    ADD_GAME("Add a game"),
}

/**
 * One-shot request for a game action whose dialogs live in [GameActionsHost]: "Add a game" (the Games
 * screen's + button, the Deck's Add games) and the game actions the Deck settings pages hand over.
 * Set [pending]; the host on screen (the Games screen's, or the one the Deck shell mounts over every
 * page) opens the matching dialog and clears it. Nothing needs to navigate anywhere first.
 */
object GamesScreenRequests {
    data class Request(val action: GameMenuAction, val shortcutPath: String? = null)

    var pending by mutableStateOf<Request?>(null)
}
