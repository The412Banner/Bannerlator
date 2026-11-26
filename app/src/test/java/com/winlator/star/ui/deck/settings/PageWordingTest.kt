package com.winlator.star.ui.deck.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The texts a game's settings page says differently from the container's (PageWording.kt), and that, in the XMB, the game page is
 * the container page: the same tabs in the same order with the same names, and the same rows with the same labels, hints, groups
 * and levels (ContainerLayout.forScope), under a game tab of its own.
 */
class PageWordingTest {

    @Test
    fun theXmbSaysShortcutSettingsForEveryKindOfGame() {
        for (world in World.entries) assertEquals(world.name, "Shortcut settings", PageWording.shortcutKicker(xmb = true, world = world))
    }

    @Test
    fun theOtherInterfacesKeepOneKickerPerKindOfGame() {
        assertEquals("Game settings", PageWording.shortcutKicker(xmb = false, world = World.WINE))
        assertEquals("Linux Steam client", PageWording.shortcutKicker(xmb = false, world = World.LINUX))
        assertEquals("Android game", PageWording.shortcutKicker(xmb = false, world = World.ANDROID))
    }

    @Test
    fun theContainerKickerIsUnchanged() {
        assertEquals("Container settings", PageWording.containerKicker())
        assertNotEquals(PageWording.containerKicker(), PageWording.shortcutKicker(xmb = true, world = World.WINE))
    }

    @Test
    fun aRowTheGameSetsItselfResetsToWhatItFollows() {
        assertEquals("Reset to container", PageWording.resetLabel(Inherit.CONTAINER))
        assertEquals("Reset to container", PageWording.resetLabel(null))
        assertEquals("Reset to app setting", PageWording.resetLabel(Inherit.APP))
        assertEquals("Reset to default", PageWording.resetLabel(Inherit.BUILT_IN))
    }

    @Test
    fun theNoteBesideTheDotNamesTheValueTheGameFollows() {
        assertEquals("Container: Off", PageWording.followsNote(Inherit.CONTAINER, "Off"))
        assertEquals("App setting: On", PageWording.followsNote(Inherit.APP, "On"))
        assertEquals("Default: 60", PageWording.followsNote(Inherit.BUILT_IN, "60"))
    }

    // ---- The game page is the container page ----

    private fun tabsOf(scope: EditorScope, world: World = World.WINE) = ContainerLayout.forScope(scope, world).tabs

    @Test
    fun theGameTabsAreTheGameTabThenTheContainersInTheSameOrderWithTheSameNames() {
        val container = tabsOf(EditorScope.CONTAINER)
        val game = tabsOf(EditorScope.GAME)
        assertEquals(EditorTabs.GAME, game.first().id)
        assertEquals(container.map { it.id }, game.drop(1).map { it.id })
        assertEquals(container.map { it.label }, game.drop(1).map { it.label })
        assertEquals(container.map { it.desc }, game.drop(1).map { it.desc })
        assertEquals(listOf("General", "Graphics", "Performance", "Controls", "Windows", "Win components", "Advanced"), game.drop(1).map { it.label })
    }

    @Test
    fun theLinuxEntryAddsItsRuntimeTabAndAnAndroidGameHasOnlyTheGameTab() {
        val linux = tabsOf(EditorScope.GAME, World.LINUX).map { it.id }
        assertEquals(EditorTabs.GAME, linux.first())
        assertEquals(EditorTabs.CONTAINER_IDS, linux.filter { it != EditorTabs.GAME && it != EditorTabs.LINUX })
        assertEquals(EditorTabs.ADVANCED, linux.last())
        assertEquals(listOf(EditorTabs.GAME), tabsOf(EditorScope.GAME, World.ANDROID).map { it.id })
    }

    private fun row(id: String, category: String, group: String, label: String, hint: String?, level: SettingLevel) = SettingDef(
        id = id, category = category, group = group, label = label, hint = hint, level = level,
        control = { _, _ -> Control.Toggle },
    )

    @Test
    fun aSharedRowReadsTheSameOnTheGamePageAndTheContainerPage() {
        val defs = listOf(
            row("windows.wineVersion", "windows", "Wine", "Wine / Proton version", "Download more below.", SettingLevel.ADVANCED),
            row("graphics.gdc.presentMode", "graphics", "Driver tuning", "Driver present mode", "H", SettingLevel.EXPERT),
            row("graphics.dxWrapper", "graphics", "DirectX", "DirectX translation", "Hint", SettingLevel.SIMPLE),
            row("hud.show", "hud", "Overlay", "Performance overlay", null, SettingLevel.SIMPLE),
            row("display.hdrOutput", "display", "Display", "HDR output", "Hint", SettingLevel.ADVANCED),
            row("input.numControllers", "input", "Controllers", "Number of controllers", "Hint", SettingLevel.ADVANCED),
        )
        fun placed(scope: EditorScope) = ContainerLayout.forScope(scope).apply(defs)
            .map { listOf(it.id, it.category, it.group, it.label, it.hint, it.level) }
        assertEquals(placed(EditorScope.CONTAINER), placed(EditorScope.GAME))
    }
}
