package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id -> tab rules of the XMB / Deck Settings tab (AppSettingsLayout.kt): the legacy registry rows and the XMB-only rows
 * (AppSettingsRows) land on the six app tabs, and the rows replaced elsewhere are hidden. Pure strings: no Compose, no icons.
 */
class AppSettingsLayoutTest {

    private fun tab(id: String) = AppSettingsLayout.tabFor(id)

    @Test
    fun generalHoldsTheStartUpScreenAndTheUpdateSwitches() {
        for (id in listOf("app.general.landing", "app.general.updateNotify", "app.general.updatePrerelease")) {
            assertEquals(id, EditorTabs.APP_GENERAL, tab(id))
        }
    }

    @Test
    fun theThemePresetIsAPageItemNotARow() {
        // The Theme preset opens a popup grid of themes (ThemePresetPopup), so the XMB layout does not file its row anywhere.
        assertNull(tab("app.appearance.preset"))
    }

    @Test
    fun appearanceHoldsTheNewAppearanceRows() {
        for (id in listOf("app.appearance.style", "app.appearance.labLook", "app.appearance.orientation", "app.appearance.uiScale", "app.appearance.fontScale", "app.appearance.showStores", "app.appearance.showInternal", "app.appearance.showSd")) {
            assertEquals(id, EditorTabs.APP_APPEARANCE, tab(id))
        }
    }

    @Test
    fun libraryHoldsCoverArtSteamFriendsTheSteamRegionAndComponentDownloads() {
        for (id in listOf("app.library.steamGridKey", "app.steam.social", "app.steam.chatNotifications", "app.steam.chatSound", "app.steam.offlinePresence", "app.steam.region", "app.contents.keepRaw", "app.contents.showOfficial")) {
            assertEquals(id, EditorTabs.APP_LIBRARY, tab(id))
        }
    }

    @Test
    fun performanceHoldsThePowerDefaultsAndTheFpsHistory() {
        for (id in listOf("app.performance.sustained", "app.performance.threadBoost", "app.performance.bigCores", "app.performance.gpuClockLock", "app.performance.saveSessionFps")) {
            assertEquals(id, EditorTabs.APP_PERFORMANCE, tab(id))
        }
    }

    @Test
    fun storageHoldsTheDownloadEnginesAndTheSaves() {
        for (id in listOf("app.downloads.steamEngine", "app.downloads.epicEngine", "app.downloads.gogEngine", "app.downloads.amazonEngine", "app.downloads.speedTier")) {
            assertEquals(id, EditorTabs.APP_STORAGE, tab(id))
        }
        for (id in listOf("app.saves.steamCollect", "app.saves.steamUpload", "app.saves.steamDownload", "app.saves.goldbergCloud", "app.saves.gogUpload", "app.saves.gogDownload", "app.saves.customBackup", "app.saves.achievementSync")) {
            assertEquals(id, EditorTabs.APP_STORAGE, tab(id))
        }
    }

    @Test
    fun noLogRowIsInSettings() {
        // Everything about logs (what is recorded, where, how many) is in Tools > Logs.
        for (id in listOf("app.logs.location", "app.logs.perGame", "app.logs.wineDebug", "app.logs.box64", "app.logs.dxvk", "app.logs.controller", "app.logs.steamLite", "app.logs.headlessSteam", "app.logs.ubisoftReport", "app.logs.ubisoftDeep", "app.logs.logcat", "app.logs.crashReports", "app.logs.exitReasons", "app.logs.keepLast")) {
            assertNull(id, tab(id))
        }
    }

    @Test
    fun advancedHoldsTheExperimentalSwitchesAndTheMouseAndKeyboardRowsOfWineGames() {
        for (id in listOf("app.advanced.fileProvider", "app.advanced.androidBrowser", "app.advanced.clipboard", "app.advanced.contentsUrl", "app.advanced.dri3", "app.advanced.xr")) {
            assertEquals(id, EditorTabs.APP_ADVANCED, tab(id))
        }
        // The three controller rows that have no other home are re-homed here, not deleted.
        for (id in listOf("app.controllers.cursorSpeed", "app.controllers.trueMouse", "app.controllers.disableXinput", "app.controllers.touch", "app.controllers.aim")) {
            assertEquals(id, EditorTabs.APP_ADVANCED, tab(id))
        }
    }

    @Test
    fun theSeedsOfNewContainersStayEditableInAdvanced() {
        // New containers are still seeded from these three app preferences (ContainerDetailViewModel), so their rows are not hidden.
        for (id in listOf("app.library.midiDefault", "app.performance.box64Preset", "app.performance.fexPreset")) {
            assertEquals(id, EditorTabs.APP_ADVANCED, tab(id))
        }
        // After the experimental switches and the mouse and keyboard rows.
        assertTrue(AppSettingsLayout.rankOf("app.controllers.cursorSpeed") < AppSettingsLayout.rankOf("app.performance.box64Preset"))
        assertTrue(AppSettingsLayout.rankOf("app.advanced.xr") < AppSettingsLayout.rankOf("app.library.midiDefault"))
    }

    @Test
    fun rowsThatLiveElsewhereNowAreHidden() {
        // The same switches are in Controls > Device.
        assertNull(tab("app.controllers.steamController"))
        assertNull(tab("app.controllers.trackpadMouse"))
        assertNull(tab("app.controllers.onScreenPriority"))
        assertNull(tab("app.controllers.autoHide"))
    }

    @Test
    fun anUnknownIdIsHidden() {
        assertNull(tab("app.nothing.here"))
        assertNull(tab("graphics.dxWrapper"))
        assertNull(tab(""))
    }

    @Test
    fun everyTabARowCanLandOnIsOneOfTheSixAppTabs() {
        val ids = listOf(
            "app.general.landing", "app.appearance.style", "app.library.steamGridKey", "app.steam.region", "app.performance.sustained",
            "app.downloads.speedTier", "app.saves.steamCollect", "app.advanced.xr", "app.controllers.trueMouse", "app.contents.keepRaw",
        )
        for (id in ids) assertTrue(id, tab(id) in EditorTabs.APP_IDS)
        assertEquals(6, EditorTabs.APP_IDS.size)
    }

    @Test
    fun rowsKeepTheirOrderWithinATabByRank() {
        // Library: cover art (0), Steam friends (1), component downloads (2), then the Steam region (3).
        assertTrue(AppSettingsLayout.rankOf("app.library.steamGridKey") < AppSettingsLayout.rankOf("app.steam.social"))
        assertTrue(AppSettingsLayout.rankOf("app.steam.social") < AppSettingsLayout.rankOf("app.contents.keepRaw"))
        assertTrue(AppSettingsLayout.rankOf("app.contents.keepRaw") < AppSettingsLayout.rankOf("app.steam.region"))
        // Storage: download engines, then saves.
        assertTrue(AppSettingsLayout.rankOf("app.downloads.speedTier") < AppSettingsLayout.rankOf("app.saves.steamCollect"))
        // Advanced: the experimental switches, then mouse and keyboard.
        assertTrue(AppSettingsLayout.rankOf("app.advanced.xr") < AppSettingsLayout.rankOf("app.controllers.cursorSpeed"))
    }
}
