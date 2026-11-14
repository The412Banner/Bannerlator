package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Walks the rows the two registries declare through the XMB layouts (EditorLayout.apply): each row that applies to an editor
 * lands exactly once, on a tab that editor lists, except the documented hidden ones. Building the registries runs plain
 * constructors and string lookups, but it touches app classes whose static state may need Android; where the JVM cannot build
 * one, its test is skipped (JUnit reports it as skipped, not passed or failed). The tests that walk a representative list of
 * rows instead never skip, so the invariants are always checked on something.
 */
class RegistryLayoutWalkTest {

    private fun containerDefs(): List<SettingDef> {
        val built = runCatching { SettingsRegistry.defs }
        assumeTrue("SettingsRegistry cannot be built on the JVM: ${built.exceptionOrNull()}", built.isSuccess)
        return built.getOrThrow()
    }

    private fun appDefs(): List<SettingDef> {
        val built = runCatching { AppSettingsRegistry.defs }
        assumeTrue("AppSettingsRegistry cannot be built on the JVM: ${built.exceptionOrNull()}", built.isSuccess)
        return built.getOrThrow()
    }

    /** The row exists in an editor of [scope] (what the editor's source says: ContainerFormSource / GameSource.shows). */
    private fun shownIn(scope: EditorScope, d: SettingDef): Boolean = when (scope) {
        EditorScope.DEFAULTS -> d.vm != null && d.onDefaults
        EditorScope.CONTAINER -> d.vm != null
        EditorScope.GAME -> d.game != null || (d.vm != null && d.showInGame)
        EditorScope.APP -> d.app != null
    }

    private fun row(id: String, category: String, vm: Boolean = true, game: Boolean = false, worlds: Set<World> = WINE_ONLY) = SettingDef(
        id = id, category = category, group = "Group", label = id, level = SettingLevel.SIMPLE,
        control = { _, _ -> Control.Toggle },
        vm = if (vm) VmBinding(get = { _, _ -> "" }, set = { _, _, _ -> }) else null,
        game = if (game) GameBinding(listOf(id), read = { _ -> "" }, write = { _, _ -> }) else null,
        worlds = worlds,
    )

    /** Asserts that [layout] files every row of [expected] (of [all]) exactly once, on a tab it lists. */
    private fun assertWalk(label: String, layout: EditorLayout, all: List<SettingDef>, expected: List<SettingDef>) {
        val placed = layout.apply(all)
        val ids = placed.map { it.id }
        val twice = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        assertEquals("rows placed more than once ($label): $twice", emptyList<String>(), twice)
        val listed = layout.tabs.map { it.id }.toSet()
        val strays = placed.filter { it.category !in listed }.map { it.id }
        assertEquals("rows on a tab the editor does not list ($label): $strays", emptyList<String>(), strays)
        val missing = expected.map { it.id }.filter { it !in ids.toSet() }
        assertEquals("rows that apply but landed on no tab ($label): $missing", emptyList<String>(), missing)
    }

    @Test
    fun everyContainerRowHasATabExceptTheHiddenOnes() {
        val defs = containerDefs()
        assertTrue("the registry should declare rows", defs.size > 50)
        val declared = EditorTabs.CONTAINER_IDS + EditorTabs.GAME + EditorTabs.LINUX
        val orphans = defs.filter { d -> ContainerLayoutMap.tabFor(d.id, d.category) == null && d.id !in ContainerLayoutMap.HIDDEN }.map { it.id }
        assertEquals("rows with no tab and not documented as hidden: $orphans", emptyList<String>(), orphans)
        val strays = defs.filter { d -> ContainerLayoutMap.tabFor(d.id, d.category).let { it != null && it !in declared } }.map { it.id }
        assertEquals("rows on a tab that no container / game page lists: $strays", emptyList<String>(), strays)
    }

    @Test
    fun everyApplicableContainerRowLandsExactlyOnceOnAListedTabInEveryEditor() {
        val defs = containerDefs()
        for (scope in listOf(EditorScope.DEFAULTS, EditorScope.CONTAINER, EditorScope.GAME)) {
            for (world in World.values()) {
                // Only a game has another world than Wine.
                if (scope != EditorScope.GAME && world != World.WINE) continue
                val layout = ContainerLayout.forScope(scope, world)
                val expected = defs.filter { world in it.worlds && shownIn(scope, it) && it.id !in ContainerLayoutMap.HIDDEN }
                assertWalk("scope=$scope world=$world", layout, defs, expected)
            }
        }
    }

    @Test
    fun aRepresentativeSetOfRowsLandsExactlyOnceOnAListedTabInEveryEditor() {
        // Never skipped: one row per kind the registries declare (see ContainerLayoutMap), through every editor's layout.
        val everywhere = setOf(World.WINE, World.LINUX, World.ANDROID)
        val rows = listOf(
            row("windows.containerName", "windows"), row("windows.wineVersion", "windows"),
            row("display.resolution", "display"), row("display.renderScale", "display"), row("audio.driver", "audio", worlds = WINE_LINUX),
            row("graphics.dxWrapper", "graphics"), row("graphics.gdc.presentMode", "graphics"), row("visuals.vkbasalt", "visuals"),
            row("performance.fpsLimiter", "performance"), row("hud.show", "hud"), row("input.touchMode", "input", worlds = WINE_LINUX),
            row("windows.locale", "windows"), row("components.direct3d", SettingsRegistry.CAT_COMPONENTS),
            row("windows.codecBackend", SettingsRegistry.CAT_COMPONENTS), row("expert.env", "expert", worlds = WINE_LINUX),
            row("game.name", "game", vm = false, game = true, worlds = everywhere), row("launch.method", "launch", vm = false, game = true),
            row("linux.clientCores", "linux", vm = false, game = true, worlds = LINUX_ONLY),
        )
        val hidden = row("hud.enabled", "hud")
        for (scope in listOf(EditorScope.DEFAULTS, EditorScope.CONTAINER, EditorScope.GAME)) {
            for (world in World.values()) {
                if (scope != EditorScope.GAME && world != World.WINE) continue
                val layout = ContainerLayout.forScope(scope, world)
                val expected = rows.filter { world in it.worlds && shownIn(scope, it) }
                assertWalk("scope=$scope world=$world", layout, rows + hidden, expected)
                // The documented hidden row is the only one that lands nowhere.
                assertTrue("scope=$scope world=$world", layout.apply(listOf(hidden)).isEmpty())
            }
        }
    }

    @Test
    fun everyAppRowHasATabExceptTheHiddenOnes() {
        val defs = appDefs()
        val documentedHidden = setOf(
            "app.controllers.steamController", "app.controllers.trackpadMouse", "app.controllers.onScreenPriority", "app.controllers.autoHide",
            // The theme preset is a page item (ThemePresetPopup), not a row.
            "app.appearance.preset",
        )
        val orphans = defs.filter { AppSettingsLayout.tabFor(it.id) == null && it.id !in documentedHidden }.map { it.id }
        assertEquals("rows with no tab and not documented as hidden: $orphans", emptyList<String>(), orphans)
        val strays = defs.filter { d -> AppSettingsLayout.tabFor(d.id).let { it != null && it !in EditorTabs.APP_IDS } }.map { it.id }
        assertEquals("rows on a tab the Settings page does not list: $strays", emptyList<String>(), strays)
        // New containers are seeded from these three preferences: their rows must stay reachable.
        for (id in listOf("app.library.midiDefault", "app.performance.box64Preset", "app.performance.fexPreset")) {
            assertTrue("$id is declared", defs.any { it.id == id })
            assertEquals(id, EditorTabs.APP_ADVANCED, AppSettingsLayout.tabFor(id))
        }
    }

    @Test
    fun everyAppRowLandsExactlyOnceOnAListedTab() {
        val defs = appDefs()
        val layout = AppSettingsLayout.layout
        val expected = defs.filter { shownIn(EditorScope.APP, it) && AppSettingsLayout.tabFor(it.id) != null }
        assertWalk("app settings", layout, defs, expected)
    }

    @Test
    fun theXmbOnlyAppRowsSitOutsideTheCategoriesTheOtherStylesWalk() {
        // Lab and the plain Deck walk AppSettingsRegistry.categories and are given legacyDefs: the XMB-only rows (AppSettingsRows)
        // must not be in any of those categories nor in legacyDefs.
        val labCategories = runCatching { AppSettingsRegistry.categories.map { it.id }.toSet() }
        val xmbOnly = runCatching { AppSettingsRows.defs() }
        val legacy = runCatching { AppSettingsRegistry.legacyDefs }
        assumeTrue("the app registry cannot be built on the JVM", labCategories.isSuccess && xmbOnly.isSuccess && legacy.isSuccess)
        val inLab = xmbOnly.getOrThrow().filter { it.category in labCategories.getOrThrow() }.map { it.id }
        assertEquals("XMB-only rows Lab would list: $inLab", emptyList<String>(), inLab)
        val xmbIds = xmbOnly.getOrThrow().map { it.id }.toSet()
        val leaked = legacy.getOrThrow().filter { it.id in xmbIds }.map { it.id }
        assertEquals("XMB-only rows in the legacy list: $leaked", emptyList<String>(), leaked)
    }
}
