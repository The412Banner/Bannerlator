package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.EaSupport

/**
 * XMB options column = a card's ⋮ menu, reordered for a D-pad (common first, Remove last). Items with
 * a `menu` open as nested XMB columns (XmbGameSettings.kt / XmbSettingsPhase2*.kt / XmbGameTools*.kt)
 * instead of pop-ups; the ones that hand off to Android or a full screen keep their handlers.
 * Shared by the Games screen's XMB view and the Deck home's XMB view.
 *
 * [openSettings] opens a game's settings outside the XMB: always for the Linux Steam entry (the XMB
 * settings columns are Wine rows), and for every game when [settingsAsColumns] is false (the Deck,
 * whose own settings editor is the one place a game's settings live).
 */
internal fun xmbGameActions(
    context: Context,
    shortcut: Shortcut,
    host: XmbGameHost,
    openSettings: (Shortcut) -> Unit,
    settingsAsColumns: Boolean = true,
): List<XmbAction> = buildList {
    // An Android game has no Wine side: no settings, saves, configs, logs or container moves.
    if (AndroidGames.isAndroidEntry(shortcut)) {
        add(XmbAction("Game Details", Icons.Filled.Edit, "Name, genres, year, description", menu = { xmbGameDetailsMenu(it, shortcut) }))
        add(XmbAction("Scrape cover", Icons.Filled.Search, menu = { xmbScrapeCoverMenu(it, shortcut) }))
        add(XmbAction("Add to home screen", Icons.Filled.AddToHomeScreen, "Android's pin prompt") { addToHomeScreen(context, shortcut) })
        add(XmbAction("Remove", Icons.Filled.Delete, "Asks to confirm first", danger = true, menu = { xmbRemoveMenu(it, shortcut, host) }))
        return@buildList
    }
    // The Linux Steam entry: its settings are the Linux-side ones only, which the pop-up editor
    // already knows (the XMB settings columns are Wine rows), and none of the Wine actions apply.
    if (LinuxShortcuts.isLinuxEntry(shortcut)) {
        add(XmbAction("Settings", Icons.Filled.Settings, "Steam (Linux) display, driver, controls…") { openSettings(shortcut) })
        add(XmbAction("Game Details", Icons.Filled.Edit, "Name, genres, year, description", menu = { xmbGameDetailsMenu(it, shortcut) }))
        add(XmbAction("Scrape cover", Icons.Filled.Search, menu = { xmbScrapeCoverMenu(it, shortcut) }))
        add(XmbAction("Add to home screen", Icons.Filled.AddToHomeScreen, "Android's pin prompt") { addToHomeScreen(context, shortcut) })
        add(XmbAction("Properties", Icons.Filled.Info, "Times played, playtime", menu = { xmbPropertiesMenu(it, shortcut) }))
        add(XmbAction("Remove", Icons.Filled.Delete, "Asks to confirm first", danger = true, menu = { xmbRemoveMenu(it, shortcut, host) }))
        return@buildList
    }
    if (settingsAsColumns) {
        add(XmbAction("Settings", Icons.Filled.Settings, "Display, graphics, controller…", menu = { xmbSettingsMenu(it, shortcut, host) }))
    } else {
        add(XmbAction("Settings", Icons.Filled.Settings, "Display, graphics, controller…") { openSettings(shortcut) })
    }
    add(XmbAction("Game Details", Icons.Filled.Edit, "Name, genres, year, description", menu = { xmbGameDetailsMenu(it, shortcut) }))
    if (isSteamOriginShortcut(shortcut)) {
        add(XmbAction("Cloud Saves", Icons.Filled.CloudSync, "Steam Cloud sync — opens its own screen") { launchSaveManager(context, steamAppIdOf(shortcut)) })
    }
    if (isCustomShortcut(shortcut)) {
        add(XmbAction("Back up saves", Icons.Filled.Archive, menu = { xmbBackupSavesMenu(it, shortcut) }))
        add(XmbAction("Restore saves", Icons.Filled.Unarchive, menu = { xmbRestoreSavesMenu(it, shortcut) }))
    }
    add(XmbAction("Community configs", Icons.Filled.Public, "Shared settings for this game", menu = { xmbCommunityConfigsMenu(it, shortcut) }))
    add(XmbAction("Scrape cover", Icons.Filled.Search, menu = { xmbScrapeCoverMenu(it, shortcut) }))
    add(XmbAction("View logs", Icons.Filled.Description, menu = { xmbLogsMenu(it, shortcut) }))
    add(XmbAction("Clone to container", Icons.Filled.ContentCopy, menu = { xmbCloneMenu(it, shortcut, host) }))
    add(XmbAction("Copy to Drive C…", Icons.Filled.DriveFileMove, menu = { xmbCopyToDriveCMenu(it, shortcut) }))
    add(XmbAction("Change executable…", Icons.Filled.SwapHoriz, menu = { xmbChangeExeMenu(it, shortcut) }))
    add(XmbAction("Add to home screen", Icons.Filled.AddToHomeScreen, "Android's pin prompt") { addToHomeScreen(context, shortcut) })
    add(XmbAction("Export", Icons.Filled.Upload) { exportShortcut(context, shortcut) })
    add(XmbAction("Properties", Icons.Filled.Info, "Times played, playtime", menu = { xmbPropertiesMenu(it, shortcut) }))
    add(XmbAction("Remove", Icons.Filled.Delete, "Asks to confirm first", danger = true, menu = { xmbRemoveMenu(it, shortcut, host) }))
}

/** The store pills over an XMB cover (the same set the Games grid draws). */
@Composable
internal fun XmbStoreBadges(shortcut: Shortcut) {
    ShortcutBadgeOverlay(
        showSteam = remember(shortcut) { isSteamOriginShortcut(shortcut) },
        showEa = remember(shortcut) { EaSupport.isTagged(shortcut) },
        showEpic = remember(shortcut) { shortcut.getExtra("storeSource") == "epic" },
        showEos = rememberEosBadge(shortcut),
        showGog = remember(shortcut) { isGogShortcut(shortcut) },
        showAmazon = remember(shortcut) { isAmazonShortcut(shortcut) },
        showXbox = remember(shortcut) { isXboxShortcut(shortcut) },
        showCustom = remember(shortcut) { isCustomOriginShortcut(shortcut) },
        showLinux = remember(shortcut) { LinuxShortcuts.isLinuxEntry(shortcut) },
        showAndroid = remember(shortcut) { AndroidGames.isAndroidEntry(shortcut) },
    )
}

/** The SD-card mark beside an XMB title, for a game installed on removable storage. */
@Composable
internal fun XmbSdBadge(shortcut: Shortcut) {
    if (remember(shortcut) { WinePath.isOnRemovableStorage(shortcut.container, shortcut.path) }) {
        SdCardBadge(Modifier.padding(start = 6.dp))
    }
}
