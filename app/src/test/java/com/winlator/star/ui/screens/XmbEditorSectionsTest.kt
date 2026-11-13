package com.winlator.star.ui.screens

import com.winlator.star.container.Container
import com.winlator.star.ui.deck.settings.Control
import com.winlator.star.ui.deck.settings.EditorTabs
import com.winlator.star.ui.deck.settings.GameBinding
import com.winlator.star.ui.deck.settings.GameExtras
import com.winlator.star.ui.deck.settings.Inherit
import com.winlator.star.ui.deck.settings.SettingDef
import com.winlator.star.ui.deck.settings.SettingLevel
import com.winlator.star.ui.deck.settings.SettingsEnv
import com.winlator.star.ui.deck.settings.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The XMB Home's game settings in the container editor's sections (XmbEditorSections.kt): which sections there are and in which order,
 * where each XMB row lands and in what order (read off ContainerLayout, so these tests build small registries of their own and check the
 * layout's rank and registry order carry over), the Game section's own rows, and the "changed for this game" count and reset. Pure:
 * no Compose and no Android.
 */
class XmbEditorSectionsTest {

    private fun row(
        id: String, category: String, group: String = "Group", game: GameBinding? = null, label: String = id, hint: String? = null,
    ) = SettingDef(
        id = id, category = category, group = group, label = label, hint = hint, level = SettingLevel.SIMPLE,
        control = { _, _ -> Control.Toggle },
        game = game,
    )

    /** A per-game extra the way the registry's plain rows keep it: absent or empty follows the container. */
    private fun own(key: String, inherit: Inherit = Inherit.CONTAINER) = GameBinding(
        keys = listOf(key),
        read = { e -> e.get(key)?.takeIf { it.isNotEmpty() } },
        write = { e, v -> e.put(key, v) },
        inherit = inherit,
    )

    private class FakeExtras(val values: MutableMap<String, String?> = HashMap()) : GameExtras {
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override val container: Container get() = throw UnsupportedOperationException("not read by these rows")
        override val env: SettingsEnv get() = throw UnsupportedOperationException("not read by these rows")
    }

    /** XMB rows as (key, key) pairs: the row itself does not matter to the arrangement. */
    private fun rows(vararg keys: String): List<Pair<String, String>> = keys.map { it to it }

    private fun tabKeys(arranged: List<XmbArranged<String>>, tab: String) = xmbRowsIn(arranged, tab).map { it.key }

    // ---- The sections ----

    @Test
    fun theSectionsAreTheEditorsGameTabsInTheEditorsOrder() {
        val tabs = xmbGameTabs()
        assertEquals(
            listOf(
                EditorTabs.GAME, EditorTabs.GENERAL, EditorTabs.GRAPHICS, EditorTabs.PERFORMANCE,
                EditorTabs.CONTROLS, EditorTabs.WINDOWS, EditorTabs.WINCOMP, EditorTabs.ADVANCED,
            ),
            tabs,
        )
        // The editor's own titles, so the XMB reads the same.
        assertEquals(
            listOf("Game", "General", "Graphics", "Performance", "Controls", "Windows", "Win components", "Advanced"),
            tabs.map { EditorTabs.tab(it).label },
        )
    }

    @Test
    fun aSectionWithNoRowIsSkippedButTheTwoPageSectionsStay() {
        val tabs = xmbGameTabs()
        assertEquals(
            listOf(EditorTabs.GENERAL, EditorTabs.GRAPHICS, EditorTabs.WINCOMP, EditorTabs.ADVANCED),
            xmbSectionTabs(tabs, setOf(EditorTabs.GENERAL, EditorTabs.GRAPHICS)),
        )
        // Rows in the wrong order of discovery change nothing: the editor's order is the tab list's.
        assertEquals(
            listOf(EditorTabs.GAME, EditorTabs.PERFORMANCE, EditorTabs.WINCOMP, EditorTabs.ADVANCED),
            xmbSectionTabs(tabs, setOf(EditorTabs.PERFORMANCE, EditorTabs.GAME)),
        )
        assertEquals(listOf(EditorTabs.WINCOMP, EditorTabs.ADVANCED), xmbSectionTabs(tabs, emptySet()))
    }

    // ---- Where the rows land, and in what order ----

    @Test
    fun rowsLandOnTheTabTheEditorFilesThemOn() {
        val slots = XmbEditorSlots.forDefs(
            listOf(
                row("display.resolution", "display"), row("audio.driver", "audio"), row("graphics.dxWrapper", "graphics"),
                row("performance.fpsLimiter", "performance"), row("input.dinputVisible", "input"), row("windows.locale", "windows"),
            ),
        )
        val arranged = xmbArrange(listOf(rows("screenSize", "audio", "dxWrapper", "fpsLimiter", "dinputVisible", "lcAll")), slots)
        assertEquals(listOf("screenSize", "audio"), tabKeys(arranged, EditorTabs.GENERAL))
        assertEquals(listOf("dxWrapper"), tabKeys(arranged, EditorTabs.GRAPHICS))
        assertEquals(listOf("fpsLimiter"), tabKeys(arranged, EditorTabs.PERFORMANCE))
        assertEquals(listOf("dinputVisible"), tabKeys(arranged, EditorTabs.CONTROLS))
        assertEquals(listOf("lcAll"), tabKeys(arranged, EditorTabs.WINDOWS))
    }

    @Test
    fun insideASectionTheEditorsRankComesFirstThenTheRegistryOrder() {
        // Registry order on purpose not the order the XMB builds its rows in.
        val slots = XmbEditorSlots.forDefs(
            listOf(
                row("visuals.vkbasalt", "visuals"),
                row("display.renderScale", "display"),
                row("graphics.driver", "graphics"),
                row("graphics.dxWrapper", "graphics"),
                row("audio.driver", "audio"),
                row("display.resolution", "display"),
            ),
        )
        val arranged = xmbArrange(listOf(rows("sharpEffect", "renderScale", "dxWrapper", "gfxDriver", "audio", "screenSize")), slots)
        // Graphics: the graphics rows (rank 0, registry order: driver before DX wrapper), then supersampling (rank 1), then the effects (rank 2).
        assertEquals(listOf("gfxDriver", "dxWrapper", "renderScale", "sharpEffect"), tabKeys(arranged, EditorTabs.GRAPHICS))
        // General: display (rank 1) before audio (rank 2), whatever their order of appearance.
        assertEquals(listOf("screenSize", "audio"), tabKeys(arranged, EditorTabs.GENERAL))
    }

    @Test
    fun rowsFromSeveralBuildersAreOrderedTogether() {
        val slots = XmbEditorSlots.forDefs(
            listOf(row("input.controlsProfile", "input"), row("input.dinputVisible", "input"), row("input.gyro.enabled", "input")),
        )
        // The controls profile comes from another builder than the controller rows, and still sorts first as in the editor.
        val arranged = xmbArrange(listOf(rows("gyro", "dinputVisible"), rows("controlsProfile")), slots)
        assertEquals(listOf("controlsProfile", "dinputVisible", "gyro"), tabKeys(arranged, EditorTabs.CONTROLS))
    }

    @Test
    fun aRowWithoutAnEntryStaysUnderTheRowBeforeIt() {
        val slots = XmbEditorSlots.forDefs(
            listOf(row("display.resolution", "display"), row("performance.syncMode", "performance"), row("audio.driver", "audio")),
        )
        // "syncHelp" and "customW" are no registry rows: each sits under its setting, in the setting's section.
        val arranged = xmbArrange(listOf(rows("syncMode", "syncHelp", "screenSize", "customW", "customH", "audio")), slots)
        assertEquals(listOf("syncMode", "syncHelp"), tabKeys(arranged, EditorTabs.PERFORMANCE))
        assertEquals(listOf("screenSize", "customW", "customH", "audio"), tabKeys(arranged, EditorTabs.GENERAL))
    }

    @Test
    fun twoRowsForOneEditorRowKeepTheirOrder() {
        val slots = XmbEditorSlots.forDefs(listOf(row("display.tv.launch", "display"), row("display.tv.mode", "display")))
        val arranged = xmbArrange(listOf(rows("tvModeId", "tvDisplay", "tvLaunch")), slots)
        // tvDisplay (an info line) and tvLaunch take the launch row's place; the mode row's comes after it.
        assertEquals(listOf("tvDisplay", "tvLaunch", "tvModeId"), tabKeys(arranged, EditorTabs.GENERAL))
    }

    @Test
    fun aLeadingRowWithNoSiblingGoesToGeneralLast() {
        val slots = XmbEditorSlots.forDefs(listOf(row("display.resolution", "display")))
        val arranged = xmbArrange(listOf(rows("mystery", "screenSize")), slots)
        assertEquals(listOf("screenSize", "mystery"), tabKeys(arranged, EditorTabs.GENERAL))
    }

    @Test
    fun theEditorsGroupHeadingsComeInOrderOfFirstAppearance() {
        val slots = XmbEditorSlots.forDefs(
            listOf(
                row("display.resolution", "display", group = "Screen"),
                row("display.refreshInGame", "display", group = "Refresh rate"),
                row("display.alignment", "display", group = "Screen"),
            ),
        )
        val arranged = xmbArrange(listOf(rows("refresh", "screenAlignment", "screenSize")), slots)
        val groups = xmbGroups(xmbRowsIn(arranged, EditorTabs.GENERAL))
        // The editor's page groups its rows the same way: a group where it first shows up, its rows together.
        assertEquals(listOf("Screen", "Refresh rate"), groups.map { it.first })
        assertEquals(listOf("screenSize", "screenAlignment"), groups[0].second.map { it.key })
        assertEquals(listOf("refresh"), groups[1].second.map { it.key })
    }

    @Test
    fun theSameLayoutRulesMoveSupersamplingAndHdrToGraphics() {
        val slots = XmbEditorSlots.forDefs(
            listOf(row("display.renderScale", "display", "Screen"), row("display.hdrOutput", "display", "HDR"), row("display.resolution", "display")),
        )
        assertEquals(EditorTabs.GRAPHICS, slots.tabOf("display.renderScale"))
        assertEquals(EditorTabs.GRAPHICS, slots.tabOf("display.hdrOutput"))
        assertEquals(EditorTabs.GENERAL, slots.tabOf("display.resolution"))
    }

    // ---- The Game section ----

    @Test
    fun theGameOnlyRowsLandInGameBeforeTheIconAndTheGameActions() {
        val slots = XmbEditorSlots.forDefs(
            listOf(
                row("game.name", "game", "Name"), row("game.arguments", "game", "Name"),
                row("launch.epicEos", "launch", "Epic Games"), row("launch.steamBigPicture", "launch", "Windows Steam"),
                row("display.resolution", "display"),
            ),
        )
        // The builder's order on purpose scrambled: the editor's is the registry's rows, the Icon section, then the Game actions.
        val arranged = xmbArrange(
            listOf(rows("container", "icon", "storage", "exe", "epicEos", "steamBigPicture", "execArgs", "name", "screenSize")),
            slots,
        )
        assertEquals(
            listOf("name", "execArgs", "epicEos", "steamBigPicture", "icon", "exe", "storage", "container"),
            tabKeys(arranged, EditorTabs.GAME),
        )
        assertEquals(listOf("screenSize"), tabKeys(arranged, EditorTabs.GENERAL))
        val groups = xmbGroups(xmbRowsIn(arranged, EditorTabs.GAME)).map { it.first }
        assertEquals(listOf("Name", "Epic Games", "Windows Steam", "Icon", EditorTabs.tab(EditorTabs.GAME).actionsLabel), groups)
    }

    @Test
    fun theGameSectionsOwnRowsHaveNoValueOfTheGameToBeChanged() {
        for (key in listOf("icon", "exe", "storage", "container")) {
            val ref = XmbEditorMap.refOf(key)
            assertNotNull(key, ref)
            assertTrue(key, ref!!.tracks.isEmpty())
        }
    }

    // ---- The table ----

    @Test
    fun aWinComponentsRowStandsForItsComponentRow() {
        assertEquals("components.direct3d", XmbEditorMap.refOf("wc:direct3d")?.slot)
        assertEquals("windows.codecBackend", XmbEditorMap.refOf("wc:builtinDecoder")?.slot)
        assertEquals("windows.softwareDecoding", XmbEditorMap.refOf("wc:softwareDecoding")?.slot)
        assertNull(XmbEditorMap.refOf("recFolder"))
        assertNull(XmbEditorMap.refOf("nothing-like-this"))
    }

    @Test
    fun aRowThatIsOneEditorRowTakesTheEditorsWordsAndALinkDoesNot() {
        val slots = XmbEditorSlots.forDefs(
            listOf(
                row("audio.driver", "audio", label = "Audio driver", hint = "How the game's sound reaches the speaker."),
                // The layout's own wording wins over the registry's (ContainerLayout.labelOf).
                row("graphics.gdc.presentMode", "graphics", label = "Driver present mode"),
            ),
        )
        assertEquals("Audio driver", slots.slotOf("audio.driver")?.label)
        assertEquals("How the game's sound reaches the speaker.", slots.slotOf("audio.driver")?.hint)
        assertEquals("Turnip driver present mode", slots.slotOf("graphics.gdc.presentMode")?.label)
        assertTrue(XmbEditorMap.refOf("audio")!!.sameRow)
        assertTrue(XmbEditorMap.refOf("wc:direct3d")!!.sameRow)
        // A link into a family of rows, a row that only takes a row's place, and the Game section's own rows keep their own words.
        for (key in listOf("dxConfig", "gfxConfig", "perf", "playerSlots", "tvDisplay", "icon", "exe", "storage", "container")) {
            assertTrue(key, !XmbEditorMap.refOf(key)!!.sameRow)
        }
    }

    @Test
    fun theTwoDriverPickersStandForTheirOwnEditorRow() {
        // The X11 picker writes the driver, the Wayland one the compositor driver: one dot each, not both.
        assertEquals("graphics.driver", XmbEditorMap.refOf("gfxDriver")?.slot)
        assertEquals("graphics.compositorDriver", XmbEditorMap.refOf("gfxCompositorDriver")?.slot)
    }

    @Test
    fun everyMappedRowIsOnARowTheEditorHasInASectionTheGameListsWhenTheRegistryCanBeBuilt() {
        val built = runCatching { SettingsRegistry.defs }
        assumeTrue("SettingsRegistry cannot be built on the JVM: ${built.exceptionOrNull()}", built.isSuccess)
        val slots = XmbEditorSlots.forDefs(built.getOrThrow())
        val tabs = xmbGameTabs().toSet()
        val missing = XmbEditorMap.all().map { it.slot }.filter { slots.slotOf(it) == null }
        assertEquals("table rows that name no editor row: $missing", emptyList<String>(), missing)
        val stray = XmbEditorMap.all().map { it.slot }.filter { slots.slotOf(it)?.tab !in tabs }
        assertEquals("table rows on a section the game does not list: $stray", emptyList<String>(), stray)
    }

    // ---- What this game changed ----

    @Test
    fun aRowIsChangedWhileTheGameHoldsAValueOfItsOwnAndNeverWithoutAContainerValue() {
        val extras = FakeExtras()
        val dx = row("graphics.dxWrapper", "graphics", game = own("dxwrapper"))
        val name = row("game.arguments", "game", game = own("execArgs", Inherit.NONE))
        val plain = row("graphics.renderer", "graphics")
        val defs = listOf(dx, name, plain)
        val overrides = XmbOverrides(defs, XmbEditorSlots.forDefs(defs), extras)
        assertTrue(!overrides.isChanged(dx))
        extras.values["dxwrapper"] = "dxvk"
        extras.values["execArgs"] = "-windowed"
        assertTrue(overrides.isChanged(dx))
        // The editor shows no "changed here" for a row that has no container value, nor for one with no game binding.
        assertTrue(!overrides.isChanged(name))
        assertTrue(!overrides.isChanged(plain))
        // Empty counts as absent, as the editor's plain rows read it.
        extras.values["dxwrapper"] = ""
        assertTrue(!overrides.isChanged(dx))
    }

    @Test
    fun aLinkIntoAFamilyOfRowsIsChangedWhenAnyRowOfTheFamilyIs() {
        val extras = FakeExtras()
        val a = row("graphics.dx.async", "graphics", game = own("dxwrapperConfig"))
        val b = row("graphics.wined3d.csmt", "graphics", game = own("wined3dCsmt"))
        val other = row("graphics.gdc.version", "graphics", game = own("graphicsDriverConfig"))
        val defs = listOf(a, b, other)
        val overrides = XmbOverrides(defs, XmbEditorSlots.forDefs(defs), extras)
        val link = XmbEditorMap.refOf("dxConfig")!!
        assertEquals(listOf("graphics.dx.async", "graphics.wined3d.csmt"), overrides.defsOf(link).map { it.id })
        assertTrue(overrides.changed(link).isEmpty())
        extras.values["wined3dCsmt"] = "0"
        assertEquals(listOf("graphics.wined3d.csmt"), overrides.changed(link).map { it.id })
        // Resetting the link resets what it stands for, and only that.
        extras.values["graphicsDriverConfig"] = "version=x"
        overrides.reset(overrides.changed(link))
        assertNull(extras.values["wined3dCsmt"])
        assertEquals("version=x", extras.values["graphicsDriverConfig"])
    }

    @Test
    fun resetUsesTheBindingsOwnResetWhenItHasOne() {
        val extras = FakeExtras(mutableMapOf("keepMe" to "1", "a" to "1", "b" to "2"))
        var ran = 0
        val custom = GameBinding(
            keys = listOf("a", "b"),
            read = { e -> e.get("a") },
            write = { _, _ -> },
            reset = { e -> ran++; e.put("a", null) },
        )
        val plain = own("c")
        val d1 = row("performance.syncMode", "performance", game = custom)
        val d2 = row("performance.fpsLimiter", "performance", game = plain)
        extras.values["c"] = "1"
        val defs = listOf(d1, d2)
        val overrides = XmbOverrides(defs, XmbEditorSlots.forDefs(defs), extras)
        overrides.reset(listOf(d1, d2))
        assertEquals(1, ran)
        // The custom reset only cleared "a"; the plain one removed every key it owns.
        assertNull(extras.values["a"])
        assertEquals("2", extras.values["b"])
        assertNull(extras.values["c"])
        assertEquals("1", extras.values["keepMe"])
    }

    @Test
    fun aSectionCountsTheRowsTheGameChangedThere() {
        val extras = FakeExtras()
        val defs = listOf(
            row("graphics.dxWrapper", "graphics", game = own("dxwrapper")),
            row("graphics.driver", "graphics", game = own("graphicsDriver")),
            row("display.renderScale", "display", game = own("renderScale")),
            row("performance.fpsLimiter", "performance", game = own("fpsLimiterEnabled")),
            row("components.direct3d", "components", game = own("wcDirect3d")),
            row("performance.cpuAffinity32", "performance", game = own("notInTheTable")),
        )
        val overrides = XmbOverrides(defs, XmbEditorSlots.forDefs(defs), extras)
        for (tab in xmbGameTabs()) assertEquals(tab, 0, overrides.countIn(tab))
        extras.values["dxwrapper"] = "dxvk"
        extras.values["graphicsDriver"] = "turnip"
        extras.values["renderScale"] = "1.5"
        extras.values["fpsLimiterEnabled"] = "1"
        extras.values["wcDirect3d"] = "1"
        // A row the XMB has no row for (the 32-bit CPU cores are not in the table) is not counted.
        extras.values["notInTheTable"] = "x"
        // Supersampling is a Graphics row in the editor, so it counts there with the driver and the DX wrapper.
        assertEquals(3, overrides.countIn(EditorTabs.GRAPHICS))
        assertEquals(1, overrides.countIn(EditorTabs.PERFORMANCE))
        assertEquals(1, overrides.countIn(EditorTabs.WINCOMP))
        assertEquals(0, overrides.countIn(EditorTabs.GENERAL))
        assertEquals(0, overrides.countIn(EditorTabs.GAME))
    }
}
