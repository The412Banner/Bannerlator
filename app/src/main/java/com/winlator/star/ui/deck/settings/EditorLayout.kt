package com.winlator.star.ui.deck.settings

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material.icons.filled.Widgets
import com.winlator.star.BuildConfig
import com.winlator.star.ui.simple.T

/*
 * The tab layer of the XMB settings editor. XMB ONLY: the plain Deck, Lab and Classic show the registries' own categories
 * exactly as before and never see this file.
 *
 * The registries (SettingsRegistry, AppSettingsRegistry and the Deck*Settings sections) keep declaring rows in
 * their own categories ("graphics", "app.saves"...): the Classic screens, the plain Deck and the Lab / Simple style read those
 * directly. The XMB editor passes an [EditorLayout] to DeckSettingsEditor (`layout = ...`), which re-files the same rows,
 * hand-offs and page extras into the layout's own tabs.
 *
 * PUBLIC SURFACE (all `internal`, package com.winlator.star.ui.deck.settings)
 * ---------------------------------------------------------------------------
 *
 * 1. Declaring a tab. A tab is a plain [SettingsCategory] with a NEW id ("tab.graphics", "app.t.storage"...).
 *    The shared ids and their label / hint / icon live in [EditorTabs]:
 *      EditorTabs.GENERAL, GRAPHICS, PERFORMANCE, CONTROLS, WINDOWS, WINCOMP, ADVANCED   container pages ("tab.general"...)
 *      EditorTabs.GAME ("tab.game"), EditorTabs.LINUX ("tab.linux")             game pages only
 *      EditorTabs.APP_GENERAL, APP_APPEARANCE, APP_LIBRARY, APP_PERFORMANCE, APP_STORAGE, APP_ADVANCED   ("app.t.*")
 *      EditorTabs.tab(id: String): SettingsCategory                              one declared tab (error on an unknown id)
 *    A new tab only needs a new `when` branch in [EditorTabs.tab] and an id in the list [EditorTabs.idsFor] returns.
 *
 * 2. Scope-specific tab lists (ordered, what [EditorLayout.tabs] should be):
 *      EditorTabs.idsFor(scope: EditorScope, world: World = World.WINE): List<String>
 *      EditorTabs.tabsFor(scope: EditorScope, world: World = World.WINE): List<SettingsCategory>
 *    APP: the six app tabs. DEFAULTS and CONTAINER: the same seven container tabs, in this order: General,
 *    Graphics, Performance, Controls, Windows, Win components, Advanced.
 *    That is also the list for a NEW container
 *    (CONTAINER with `createMode = true`): create mode never changes the tabs. GAME: Game first, then the same ones;
 *    the Linux Steam client entry (World.LINUX) also gets "tab.linux" (before Advanced); an Android game only "tab.game".
 *    A tab whose rows, extras and hand-offs are all out of the editor's level or world is not listed at all
 *    (DeckSettingsEditor.visibleCats), so listing a tab for a world that has no rows in it is harmless.
 *
 * 3. Assigning rows to tabs, by registry id. The layout owns one function:
 *      tabOf: (SettingDef) -> String?     the tab id for a registry row, or null = hidden in this layout
 *    A row is identified by `def.id` (and its old `def.category`); the mapping is a pure function of those two
 *    strings, so it can be a table of `id.startsWith(...)` rules and be unit-tested without Compose. Rows
 *    mapped to null stay in the registry (Lab / Classic still use them) and are only dropped here. Hidden rows
 *    still answer `values(id)` for the rows that read them (the editor looks values up in the full list).
 *    Optional hooks, all defaulted:
 *      rank:    (SettingDef) -> Int           order of the old categories inside a tab (stable sort, so rows keep
 *                                             their registry order within the same rank)
 *      groupOf: (SettingDef) -> String        rename a group header ("Overlay" -> "Performance overlay")
 *      levelOf: (SettingDef) -> SettingLevel  force a level (Wine version -> SIMPLE); EXPERT reads as ADVANCED
 *      labelOf / hintOf                       rewrite a row's label / hint for this layout only (the registry keeps its own text)
 *      unlisted: (SettingDef) -> Boolean      a row of the tab that a page extra draws instead of the list (still counted for
 *                                             the tab's overrides / Reset and found by the editor's search)
 *
 * 4. Hand-offs and page extras (the non-row items of a page), by OLD category id (the key the registry or the
 *    section returns):
 *      handoffTab:    (oldCategory: String, Handoff) -> String?   tab the hand-off row goes to, null = dropped
 *      extraTargets:  (oldCategory: String) -> Set<String>        tabs an old category's extras may feed
 *      extraTab:      (oldCategory: String, PageExtra.key: String) -> String?   tab of ONE extra item, by its key;
 *                                                                 null = the only tab in extraTargets (dropped if
 *                                                                 there are several), so only a category split over
 *                                                                 several tabs needs it (windows: keys starting
 *                                                                 "windows-reset" -> Advanced, the rest -> Windows)
 *      extraRank:     (oldCategory: String) -> Int                order of the merged sources inside a tab
 *      extraLevel:    (oldCategory: String, tab: String) -> SettingLevel?   level at which that old category's
 *                                                                 extras make the tab appear (default: the
 *                                                                 extra's own level); use ADVANCED for a source
 *                                                                 that only feeds a tab with Advanced content
 *    Merging never changes what a section returns, so SimpleClassicEditor and the other readers of
 *    `extras["windows"]`, `extras["graphics"]`... are unaffected.
 *
 * 5. The "Get more" link. On a registry row: `getMore = "<kind>"` (an extra last named argument of SettingDef(...)).
 *    The host screen sets the handler on the editor's env, BEFORE the editor draws (same `remember` block that
 *    creates the env):
 *      env.getMoreOpen = { kind: String -> ... }        // type ((String) -> Unit)?; a lambda that ends in a
 *                                                         // Boolean is fine (the result is dropped)
 *    The row draws a small "Get more" button next to its "?" only while the row has `getMore` AND the env has the
 *    handler, and the button runs `env.getMoreOpen(kind)`. It is part of the row's focus group (reached with Right
 *    from the row on XMB, like "?" and Reset).
 *
 * 6. Create mode. DeckSettingsEditor(createMode = true) turns the Deck editor into the New container form:
 *    a persistent "Create container" bar, no Discard, Create on the controller's Start / X, a "Wine version
 *    installed" check before creating, and `onExit` called after a successful create. The caller (DeckContainerSettings)
 *    must pass: `createMode = (id == DeckSettingsRoutes.NEW_CONTAINER_ID)`, an `onExit` that closes the page
 *    (popBackStack), a source built from the template container (`ContainerFormSource` with `defaults == null`),
 *    and `layout = ...` for CONTAINER scope. Nothing else is needed from the caller.
 *
 * 7. Passing the layout. `DeckSettingsEditor(..., layout = myLayout)` (last parameter, default null = the old
 *    behaviour). The caller must `remember` the layout (a new instance per recomposition re-runs [EditorLayout.apply]).
 *    The Lab / Simple style ignores it. Keep passing the registry's own `categories`, `defs`, `handoffs` and `extras`:
 *    the editor re-files them.
 */

/** How the XMB editor groups the registries' rows into its tabs. The plain Deck / Lab / Simple / Classic never see it. */
internal class EditorLayout(
    /** The tabs, in order, with the NEW ids (see [EditorTabs]). */
    val tabs: List<SettingsCategory>,
    /** The tab id of a registry row, or null to hide the row in this layout. */
    val tabOf: (SettingDef) -> String?,
    /** Order of the old categories inside a tab; rows with the same rank keep their registry order. */
    val rank: (SettingDef) -> Int = { 0 },
    /** The group header a row sits under in its tab. */
    val groupOf: (SettingDef) -> String = { it.group },
    /** The level a row is shown from. */
    val levelOf: (SettingDef) -> SettingLevel = { it.level },
    /** The label a row has in this layout. */
    val labelOf: (SettingDef) -> String = { it.label },
    /** The hint a row has in this layout. */
    val hintOf: (SettingDef) -> String? = { it.hint },
    /**
     * True for a row that belongs to its tab (it counts in the tab's override badge, Reset and the editor's search) but is not
     * drawn as a list row, because a page extra of the tab draws it (the Win components page).
     */
    val unlisted: (SettingDef) -> Boolean = { false },
    /** (old category, hand-off) to the tab it is shown on, or null to drop it. */
    val handoffTab: (String, Handoff) -> String? = { _, _ -> null },
    /** The tabs the extras of an old category may feed. */
    val extraTargets: (String) -> Set<String> = { emptySet() },
    /** (old category, [PageExtra.key]) to the tab of that one extra item; null = the only tab in [extraTargets]. */
    val extraTab: (String, String) -> String? = { _, _ -> null },
    /** Order of the merged extra sources inside a tab. */
    val extraRank: (String) -> Int = { 0 },
    /** (old category, tab) to the level at which that source's extras show the tab; null = the extra's own level. */
    val extraLevel: (String, String) -> SettingLevel? = { _, _ -> null },
) {
    private val tabIds: Set<String> = tabs.mapTo(HashSet()) { it.id }

    /**
     * The rows in their tabs: hidden rows dropped, the others copied into their tab (category), group and level,
     * ordered by [rank] (stable). Rows mapped to a tab that is not in [tabs] are dropped too (a layout mistake,
     * logged in a debug build).
     */
    fun apply(defs: List<SettingDef>): List<SettingDef> {
        val placed = ArrayList<Pair<Int, SettingDef>>(defs.size)
        val hidden = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (d in defs) {
            val tab = tabOf(d)
            if (tab == null) {
                hidden += d.id
                continue
            }
            if (tab !in tabIds) {
                unknown += d.id
                continue
            }
            val group = groupOf(d)
            val level = levelOf(d).effective
            val label = labelOf(d)
            val hint = hintOf(d)
            val moved = if (tab == d.category && group == d.group && level == d.level && label == d.label && hint == d.hint) d
            else d.copyWith(category = tab, group = group, level = level, label = label, hint = hint)
            placed += rank(d) to moved
        }
        if (hidden.isNotEmpty()) debugLog("hidden by the layout (${hidden.size}): ${hidden.joinToString(", ")}")
        if (unknown.isNotEmpty()) debugLog("mapped to a tab this layout does not have (${unknown.size}): ${unknown.joinToString(", ")}")
        return placed.sortedBy { it.first }.map { it.second }
    }

    /** The hand-offs by tab id, in the registry's order. A hand-off [handoffTab] gives no tab is dropped. */
    fun rekeyHandoffs(src: Map<String, List<Handoff>>): Map<String, List<Handoff>> {
        val out = LinkedHashMap<String, MutableList<Handoff>>()
        for ((old, list) in src) {
            for (h in list) {
                val tab = handoffTab(old, h)
                if (tab != null && tab in tabIds) out.getOrPut(tab) { ArrayList() }.add(h)
            }
        }
        return out
    }

    /** The tab one extra item of the old category [old] belongs to (see [extraTab]). */
    fun extraTabOf(old: String, key: String): String? = extraTab(old, key) ?: extraTargets(old).singleOrNull()

    /**
     * The extras by tab id: one [CategoryExtra] per tab, merging every old category that feeds it (ordered by
     * [extraRank]), each item routed by [extraTabOf]. The sources are still read lazily, while the page draws.
     * Items whose key repeats on one tab are shown once (a repeated key would crash the page's list).
     */
    fun rekeyExtras(src: Map<String, CategoryExtra>): Map<String, CategoryExtra> {
        val sources = src.entries.sortedBy { extraRank(it.key) }
        val targets = LinkedHashSet<String>()
        for (s in sources) targets += extraTargets(s.key).filter { it in tabIds }
        val out = LinkedHashMap<String, CategoryExtra>()
        for (tab in targets) {
            val feeding = sources.filter { tab in extraTargets(it.key) }
            if (feeding.isEmpty()) continue
            val search = feeding.firstOrNull { it.value.searchKey != null }?.value
            out[tab] = CategoryExtra(
                top = { collect(feeding, tab) { it.top() } },
                bottom = { collect(feeding, tab) { it.bottom() } },
                searchKey = search?.searchKey,
                focusSearch = search?.focusSearch ?: {},
                level = feeding.minOf { s -> (extraLevel(s.key, tab) ?: s.value.level).effective },
            )
        }
        return out
    }

    private fun collect(feeding: List<Map.Entry<String, CategoryExtra>>, tab: String, items: (CategoryExtra) -> List<PageExtra>): List<PageExtra> {
        val seen = HashSet<String>()
        val out = ArrayList<PageExtra>()
        for (s in feeding) {
            for (x in items(s.value)) {
                if (extraTabOf(s.key, x.key) != tab) continue
                if (seen.add(x.key)) out += x else debugLog("extra key repeated on $tab: ${x.key} (from ${s.key})")
            }
        }
        return out
    }

    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            // runCatching: a JVM unit test has no android.util.Log.
            runCatching { Log.d(TAG, msg) }
        }
    }

    private companion object {
        const val TAG = "EditorLayout"
    }
}

/**
 * The tabs the XMB / Deck editor knows, by id, and which of them each editor scope lists (see the surface
 * notes at the top of this file). Building a tab (its icon) only happens in [tab], so [idsFor] is plain strings.
 */
internal object EditorTabs {
    // Container pages (New container, Defaults for new containers, Edit container, a game's own container rows).
    const val GENERAL = "tab.general"
    const val GRAPHICS = "tab.graphics"
    const val PERFORMANCE = "tab.performance"
    const val CONTROLS = "tab.controls"
    const val WINDOWS = "tab.windows"
    const val WINCOMP = "tab.wincomp"
    const val ADVANCED = "tab.advanced"

    // A game's own pages.
    const val GAME = "tab.game"
    const val LINUX = "tab.linux"

    // App settings.
    const val APP_GENERAL = "app.t.general"
    const val APP_APPEARANCE = "app.t.appearance"
    const val APP_LIBRARY = "app.t.library"
    const val APP_PERFORMANCE = "app.t.performance"
    const val APP_STORAGE = "app.t.storage"
    const val APP_ADVANCED = "app.t.advanced"

    /** The container tabs. */
    val CONTAINER_IDS: List<String> = listOf(GENERAL, GRAPHICS, PERFORMANCE, CONTROLS, WINDOWS, WINCOMP, ADVANCED)
    val APP_IDS: List<String> = listOf(APP_GENERAL, APP_APPEARANCE, APP_LIBRARY, APP_PERFORMANCE, APP_STORAGE, APP_ADVANCED)

    /** The ordered tab ids an editor of [scope] lists. A new container is CONTAINER: create mode adds nothing. */
    fun idsFor(scope: EditorScope, world: World = World.WINE): List<String> = when (scope) {
        EditorScope.APP -> APP_IDS
        EditorScope.DEFAULTS, EditorScope.CONTAINER -> CONTAINER_IDS
        EditorScope.GAME -> when (world) {
            World.ANDROID -> listOf(GAME)
            World.LINUX -> listOf(GAME, GENERAL, GRAPHICS, PERFORMANCE, CONTROLS, WINDOWS, WINCOMP, LINUX, ADVANCED)
            World.WINE -> listOf(GAME) + CONTAINER_IDS
        }
    }

    /** [idsFor] as the declared tabs. */
    fun tabsFor(scope: EditorScope, world: World = World.WINE): List<SettingsCategory> = idsFor(scope, world).map { tab(it) }

    /** The declared tab of [id]. */
    fun tab(id: String): SettingsCategory = when (id) {
        GENERAL -> SettingsCategory(id, T("General"), T("Name, Wine version, display, sound and TV."), Icons.Filled.Tune)
        GRAPHICS -> SettingsCategory(id, T("Graphics"), T("DirectX translation, GPU driver, resolution, HDR and visual effects."), Icons.Filled.Layers)
        PERFORMANCE -> SettingsCategory(id, T("Performance"), T("Frame limit and generation, sync, x86 translators, CPU cores, services and the overlay."), Icons.Filled.Speed)
        CONTROLS -> SettingsCategory(id, T("Controls"), T("Controllers, on-screen controls, mouse and touch, rumble and motion aim."), Icons.Filled.Gamepad)
        WINDOWS -> SettingsCategory(id, T("Windows"), T("Language, session, desktop, wallpaper and drives."), Icons.Filled.WebAsset)
        WINCOMP -> SettingsCategory(id, T("Win components"), T("Builtin or native DLLs, media codecs and the runtimes installed into the Windows prefix."), Icons.Filled.Widgets)
        ADVANCED -> SettingsCategory(id, T("Advanced"), T("Environment variables and going back to the app defaults."), Icons.Filled.Build)
        GAME -> SettingsCategory(id, T("Game"), T("Name, launch options and what happens when this game starts."), Icons.Filled.SportsEsports, actionsLabel = T("Game actions"))
        LINUX -> SettingsCategory(id, T("Linux runtime"), T("Only for the Linux Steam client entry: its drivers, Steam client options and speed tweaks."), Icons.Filled.Terminal)
        APP_GENERAL -> SettingsCategory(id, T("General"), T("Start-up screen, updates, about and help."), Icons.Filled.Tune)
        APP_APPEARANCE -> SettingsCategory(id, T("Appearance"), T("Interface style, theme, accent colour, size and orientation."), Icons.Filled.Palette)
        APP_LIBRARY -> SettingsCategory(id, T("Games & library"), T("Cover art, Steam friends and chat, the Steam connection and component downloads."), Icons.AutoMirrored.Filled.LibraryBooks)
        APP_PERFORMANCE -> SettingsCategory(id, T("Performance"), T("Power defaults for every game, root controls and Lossless.dll."), Icons.Filled.Speed)
        APP_STORAGE -> SettingsCategory(id, T("Storage & data"), T("Folders, sound banks, backups, download engines and cloud saves."), Icons.Filled.Storage)
        APP_ADVANCED -> SettingsCategory(id, T("Advanced"), T("Mouse and keyboard for Wine games, display server internals and experimental switches."), Icons.Filled.Science)
        else -> error("Unknown editor tab: $id")
    }
}
