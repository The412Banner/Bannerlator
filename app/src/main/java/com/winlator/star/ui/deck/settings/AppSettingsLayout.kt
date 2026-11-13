package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.T

// The XMB Settings tab: six real tabs (EditorTabs.APP_*) over the registry's rows. XMB ONLY: the plain Deck and Lab show the
// registry's own categories, and the Classic shell reads them too. The legacy rows keep their categories
// ("app.general", "app.saves"...); this layout files them, and the XMB-only rows of
// AppSettingsRows, into the tabs by id. Every legacy hand-off is dropped (each is a real row or action here, or removed), and
// the page items of AppSettingsExtras are keyed by tab id.
//
//   app.t.general      General           landing, updateNotify, updatePrerelease + (extras) Updates block, About / Help / Account / Run setup again
//   app.t.appearance   Appearance        style, labLook, orientation, uiScale, fontScale, showStores / Internal / Sd + (extras) theme preset line + popup (first), accent colour picker
//   app.t.library      Games & library   steamGridKey, steam.* (4), contents.* (2), steam.region + (extras) Steam library locations (first), region / network tests
//   app.t.performance  Performance       sustained, threadBoost, bigCores, gpuClockLock, saveSessionFps + (extras) Root controls dialog, Lossless.dll
//   app.t.storage      Storage & data    downloads.* (5), saves.* (8) + (extras) space used, folders, sound banks, backup / restore
//   app.t.advanced     Advanced          advanced.* (6), controllers.cursorSpeed / trueMouse / disableXinput / touch / aim, library.midiDefault, performance.box64Preset / fexPreset (the seeds of new containers) + (extras) frame-gen training capture
//
// Hidden here (still in the registry for Lab / Classic / the plain Deck): controllers.steamController / trackpadMouse /
// onScreenPriority / autoHide (Controls > Device). library.midiDefault, performance.box64Preset / fexPreset are NOT hidden: new
// containers are still seeded from those three app preferences (see ContainerDetailViewModel), so their rows are in Advanced
// (after the experimental switches and the mouse and keyboard rows). Logs are not in Settings at all (what is recorded, where it
// goes and how long it is kept live in Tools > Logs; no app.logs.* id maps to a tab), and the theme preset is not a row but a
// page item (ThemePresetPopup) that opens a grid of themes.

internal object AppSettingsLayout {

    private val tabIds: Set<String> = EditorTabs.APP_IDS.toSet()

    /** The tab id of an app row, by its id, or null to hide it in this layout. A pure function of the id (unit-testable). */
    fun tabFor(id: String): String? = when {
        // The Theme preset is the page item of AppSettingsExtras (a popup with a swatch grid), not a row.
        id == "app.appearance.preset" -> null
        // New containers are seeded from these three app preferences (ContainerDetailViewModel), so they stay editable: in Advanced.
        id == "app.library.midiDefault" || id == "app.performance.box64Preset" || id == "app.performance.fexPreset" -> EditorTabs.APP_ADVANCED
        id == "app.controllers.cursorSpeed" || id == "app.controllers.trueMouse" || id == "app.controllers.disableXinput" ||
            id == "app.controllers.touch" || id == "app.controllers.aim" -> EditorTabs.APP_ADVANCED
        id.startsWith("app.controllers.") -> null
        id.startsWith("app.general.") -> EditorTabs.APP_GENERAL
        id.startsWith("app.appearance.") -> EditorTabs.APP_APPEARANCE
        id.startsWith("app.library.") -> EditorTabs.APP_LIBRARY
        id.startsWith("app.steam.") -> EditorTabs.APP_LIBRARY
        id.startsWith("app.contents.") -> EditorTabs.APP_LIBRARY
        id.startsWith("app.performance.") -> EditorTabs.APP_PERFORMANCE
        id.startsWith("app.downloads.") -> EditorTabs.APP_STORAGE
        id.startsWith("app.saves.") -> EditorTabs.APP_STORAGE
        id.startsWith("app.advanced.") -> EditorTabs.APP_ADVANCED
        else -> null
    }

    /** The order of rows inside their tab (stable: rows of one rank keep the registry's order). */
    fun rankOf(id: String): Int = when {
        // Library: cover art, friends, component downloads, then the Steam region (its test actions come right after it).
        id == "app.steam.region" -> 3
        id.startsWith("app.contents.") -> 2
        id.startsWith("app.steam.") -> 1
        // Storage: download engines, then saves and cloud.
        id.startsWith("app.saves.") -> 1
        // Advanced: the experimental / X11 switches, then mouse and keyboard, then the seeds of new containers.
        id.startsWith("app.controllers.") -> 1
        id == "app.library.midiDefault" || id == "app.performance.box64Preset" || id == "app.performance.fexPreset" -> 2
        else -> 0
    }

    private fun groupOf(def: SettingDef): String =
        if (def.id == "app.contents.keepRaw") T("Component downloads") else def.group

    /** The layout DeckAppSettings passes to the editor (one instance: the editor needs it remembered). */
    val layout: EditorLayout by lazy {
        EditorLayout(
            tabs = EditorTabs.tabsFor(EditorScope.APP),
            tabOf = { def -> tabFor(def.id) },
            rank = { def -> rankOf(def.id) },
            groupOf = { def -> groupOf(def) },
            // AppSettingsExtras keys its page items by tab id, so a source feeds exactly the tab of its own key.
            extraTargets = { old -> if (old in tabIds) setOf(old) else emptySet() },
        )
    }
}
