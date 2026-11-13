package com.winlator.star.ui.screens

import com.winlator.star.core.FastOpenGL
import com.winlator.star.core.MediaDecoder
import com.winlator.star.core.UnrealHdr
import com.winlator.star.display.WaylandHdr
import com.winlator.star.store.WindowsSteamClient
import com.winlator.star.store.xbox.XboxRuntime
import com.winlator.star.ui.deck.settings.ContainerLayout
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.EditorTabs
import com.winlator.star.ui.deck.settings.GameExtras
import com.winlator.star.ui.deck.settings.Inherit
import com.winlator.star.ui.deck.settings.SettingDef
import com.winlator.star.ui.deck.settings.SettingsRegistry
import com.winlator.star.ui.deck.settings.WinComponentsLogic
import com.winlator.star.ui.deck.settings.World

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The XMB Home's game settings in the container editor's own sections (Game, General, Graphics, Performance, Controls, Windows,
// Win components, Advanced). This file is the PURE part, with no Compose and no Android in it, so it is unit-tested:
//
//   • [XmbEditorMap]   which editor row each XMB row (by its row key) stands for. THE ONLY hand-kept table. Where a row goes (its tab,
//                      its group, its place in the tab) is never kept here: it is read off the editor's own layout, ContainerLayout
//                      (tabFor / rankOf) applied to the registry, so the XMB follows the editor when that changes.
//   • [XmbEditorSlots] the editor's tab, group and place of every registry row, from that layout.
//   • [xmbArrange]     files rows under their sections in the editor's order, group by group.
//   • [XmbOverrides]   which rows this game changed from its container, with the editor's own rule (the game binding's read), and
//                      the editor's own reset.
// The Compose side is XmbGameSections.kt.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The editor row(s) an XMB row stands for. [slot] is the row that gives it its place (tab, group, order); [tracks] are the registry
 * ids whose game value the row's dot and Reset follow, [tracksPrefix] more of them by id prefix (a link that opens a whole family of
 * editor rows, like the DX wrapper configuration). [sameRow]: the XMB row IS that one editor row, so it takes the editor's label and hint.
 */
internal class XmbRef(
    val slot: String,
    val tracks: List<String> = listOf(slot),
    val tracksPrefix: List<String> = emptyList(),
    val sameRow: Boolean = false,
)

/**
 * Where the editor puts one row: its section ([tab]), the heading it sits under ([group]) and its place in the section ([order]), and the
 * words it uses for it ([label], [hint]; null for the Game section's own rows).
 */
internal class EditorSlot(val tab: String, val group: String, val order: Int, val label: String? = null, val hint: String? = null)

/** An XMB row filed in the editor's order: [slot] is where, [key] is the XMB row's key and [item] the row itself. */
internal class XmbArranged<T>(val slot: EditorSlot, val key: String, val item: T)

internal object XmbEditorMap {
    // The Game section's rows that are no registry row: the editor's Icon section and its "Game actions" hand-offs.
    const val GAME_ICON = "xmb.game.icon"
    const val GAME_STORAGE = "xmb.game.storage"
    const val GAME_EXE = "xmb.game.exe"
    const val GAME_CONTAINER = "xmb.game.container"

    /** An XMB row that is one editor row. */
    private fun id(registryId: String) = XmbRef(registryId, sameRow = true)

    /** Takes the place of [registryId] but follows nothing: the row has no value of the game's to be changed. */
    private fun at(registryId: String) = XmbRef(registryId, tracks = emptyList())

    /** A link into a family of editor rows: placed with [first], marked (and reset) while any row whose id starts with [prefixes] is. */
    private fun family(first: String, vararg prefixes: String) = XmbRef(first, tracks = listOf(first), tracksPrefix = prefixes.toList())

    /**
     * XMB row key -> editor row. Most XMB row keys are made up (they only need to be unique in their column), a few are the extra's own
     * name. A row that is not in the table (a "What is this?" link, the custom width and height, a hint) is filed right after the row
     * before it in the XMB's own list, as the nearest sibling ([xmbArrange]).
     */
    private val ROWS: Map<String, XmbRef> = mapOf(
        // Game: the name and arguments, the store rows, the editor's Icon section and its Game actions.
        "name" to id("game.name"),
        "execArgs" to id("game.arguments"),
        WindowsSteamClient.EXTRA_BIG_PICTURE to id("launch.steamBigPicture"),
        "epicEos" to id("launch.epicEos"),
        "epicOvt" to id("launch.epicOvtForce"),
        "epicOffline" to id("launch.epicOffline"),
        "epicOverlay" to id("launch.epicOverlay"),
        XboxRuntime.NEXUS_RUNTIME_EXTRA to id("launch.xboxNexusRuntime"),
        "icon" to at(GAME_ICON),
        "exe" to at(GAME_EXE),
        "storage" to at(GAME_STORAGE),
        "container" to at(GAME_CONTAINER),

        // General: display and sound.
        "displayBackend" to id("display.backend"),
        "screenSize" to id("display.resolution"),
        "screenAlignment" to id("display.alignment"),
        "fullscreen" to id("display.fullscreenMode"),
        "refresh" to id("display.refreshInGame"),
        "tvDisplay" to at("display.tv.launch"),
        "tvLaunch" to id("display.tv.launch"),
        "tvModeId" to id("display.tv.mode"),
        "tvMatchRes" to id("display.tv.matchRes"),
        "audio" to id("audio.driver"),
        "audioSettings" to id("audio.latencyPreset"),
        "mic" to id("audio.microphone"),
        "midi" to id("audio.midiSoundfont"),

        // Graphics.
        "dxWrapper" to id("graphics.dxWrapper"),
        "dxConfig" to family("graphics.dx.dxvkVersion", "graphics.dx.", "graphics.wined3d."),
        UnrealHdr.EXTRA to id("graphics.unrealHdr"),
        "gfxDriver" to id("graphics.driver"),
        "gfxCompositorDriver" to id("graphics.compositorDriver"),
        "waylandGameDriver" to id("graphics.wl.gameDriver"),
        "waylandDriverCfg" to family("graphics.wl.adapterProfile", "graphics.wl.adapter", "graphics.wl.bcn", "graphics.wl.mali", "graphics.wl.panvk"),
        "gfxConfig" to family("graphics.gdc.version", "graphics.gdc."),
        FastOpenGL.EXTRA to id("graphics.fastOpenGL"),
        "glFpsHud" to id("graphics.glFpsHud"),
        "glVsyncOff" to id("graphics.glVsyncOff"),
        "renderer" to id("graphics.renderer"),
        "sfCompat" to id("graphics.sfColours"),
        "vkNative" to id("graphics.nativeRenderer"),
        "vkColors" to id("graphics.swapColours"),
        "vkPresent" to id("graphics.presentMode"),
        "vkBackend" to id("graphics.nativeBackend"),
        "vkDriver" to id("graphics.rendererDriver"),
        "renderScale" to id("display.renderScale"),
        WaylandHdr.EXTRA to id("display.hdrOutput"),
        "sharpEffect" to id("visuals.vkbasalt"),
        "sharpLevel" to id("visuals.vkbasaltLevel"),
        "sharpDenoise" to id("visuals.vkbasaltDenoise"),
        "reshade" to family("visuals.reshade.loadout", "visuals.reshade."),

        // Performance.
        "fpsLimiter" to id("performance.fpsLimiter"),
        "frameGen" to id("performance.frameGen"),
        "syncMode" to id("performance.syncMode"),
        "emulator" to id("performance.translator"),
        "fexVersion" to id("performance.fexVersion"),
        "fexPreset" to id("performance.fexPreset"),
        "b64Version" to id("performance.box64Version"),
        "b64Preset" to id("performance.box64Preset"),
        "startup" to id("performance.startupServices"),
        "cpu" to id("performance.cpuAffinity"),
        "perf" to family("performance.power.sustained", "performance.power.", "performance.root."),

        // Controls.
        "controlsProfile" to id("input.controlsProfile"),
        "dinputVisible" to id("input.dinputVisible"),
        "dinputVibration" to id("input.dinputVibration"),
        "bannerinXInput" to id("input.bannerinXInput"),
        "bannerinXInputSwitching" to id("input.bannerinXInputSwitching"),
        "stickPacing" to id("input.stickPacing"),
        "controllerIdentity" to id("input.controllerIdentity"),
        "disableX" to id("input.disableXInput"),
        "numControllers" to id("input.numControllers"),
        "autoHide" to id("input.hideOnController"),
        // The editor draws the player slot pins as a section of the page, under "Player slots": same place here.
        "playerSlots" to XmbRef("input.onScreenPriority", tracks = listOf("input.slotOverrides")),
        "simTouch" to id("input.touchMode"),
        "vibrationMode" to id("input.vibrationTarget"),
        "vibrationIntensity" to id("input.vibrationIntensity"),
        "rumbleMotors" to id("input.rumbleMotors"),
        "gyro" to id("input.gyro.enabled"),
        "gyroMode" to id("input.gyro.mode"),
        "gyroTarget" to id("input.gyro.target"),
        "gyroActivator" to id("input.gyro.activator"),
        "gyroActMode" to id("input.gyro.activation"),
        "gyroSens" to id("input.gyro.sensitivity"),
        "gyroInvX" to id("input.gyro.invertX"),
        "gyroInvY" to id("input.gyro.invertY"),

        // Windows.
        "lcAll" to id("windows.locale"),
        "autoClose" to id("windows.closeOnExit"),
    )

    /** The prefix of the Win components menu's rows: "wc:<component key>". */
    const val WC_PREFIX = "wc:"

    /** The editor row an XMB row of the key [key] stands for, or null for a row that follows its sibling. */
    fun refOf(key: String): XmbRef? {
        ROWS[key]?.let { return it }
        if (!key.startsWith(WC_PREFIX)) return null
        // A Win components row is one key of the components string: a DLL row, or one of the two codec rows.
        return id(
            when (val component = key.removePrefix(WC_PREFIX)) {
                MediaDecoder.KEY_BACKEND -> "windows.codecBackend"
                MediaDecoder.KEY_SOFTWARE_DECODING -> "windows.softwareDecoding"
                else -> "components.$component"
            },
        )
    }

    /** Every row the table knows (what a section's change count is taken over). */
    fun all(): Collection<XmbRef> = ROWS.values
}

/**
 * The editor's section, group and place of every registry row of a Wine game, read off the editor's own layout:
 * ContainerLayout.forScope(GAME) files the registry's rows into its tabs by [ContainerLayout]'s tabFor / rankOf and keeps the registry
 * order inside a rank, which is the order the editor's page lists them in. [placed] is that result (rows in the editor's order).
 */
internal class XmbEditorSlots(placed: List<SettingDef>) {
    private val byId = HashMap<String, EditorSlot>()

    init {
        placed.forEachIndexed { i, d -> if (!byId.containsKey(d.id)) byId[d.id] = EditorSlot(d.category, d.group, i, d.label, d.hint) }
        // After the Game tab's registry rows: the editor's Icon section, then its "Game actions" (change the program, copy to drive C,
        // move to another container, in that order).
        val after = placed.size
        val actions = EditorTabs.tab(EditorTabs.GAME).actionsLabel ?: "Game actions"
        byId[XmbEditorMap.GAME_ICON] = EditorSlot(EditorTabs.GAME, "Icon", after)
        byId[XmbEditorMap.GAME_EXE] = EditorSlot(EditorTabs.GAME, actions, after + 1)
        byId[XmbEditorMap.GAME_STORAGE] = EditorSlot(EditorTabs.GAME, actions, after + 2)
        byId[XmbEditorMap.GAME_CONTAINER] = EditorSlot(EditorTabs.GAME, actions, after + 3)
    }

    fun slotOf(id: String): EditorSlot? = byId[id]

    fun tabOf(id: String): String? = byId[id]?.tab

    companion object {
        /** The slots of the registry the editor itself runs on, built once. */
        val game: XmbEditorSlots by lazy { forDefs(SettingsRegistry.defs) }

        fun forDefs(defs: List<SettingDef>): XmbEditorSlots =
            XmbEditorSlots(ContainerLayout.forScope(EditorScope.GAME, World.WINE).apply(defs))
    }
}

/** The sections of a game's XMB settings in the editor's order, as tab ids: Game, General, Graphics, Performance, Controls, Windows, Win components, Advanced. */
internal fun xmbGameTabs(): List<String> = EditorTabs.idsFor(EditorScope.GAME, World.WINE)

/** The tabs whose page is a menu of its own (the Win components list, the environment variables): present with or without rows from the table. */
internal val XMB_SETTINGS_PAGE_TABS: Set<String> = setOf(EditorTabs.WINCOMP, EditorTabs.ADVANCED)

/** The sections to list: each of [tabs] that has rows ([withRows]) or whose page is [pages], in the order of [tabs]. */
internal fun xmbSectionTabs(tabs: List<String>, withRows: Set<String>, pages: Set<String> = XMB_SETTINGS_PAGE_TABS): List<String> =
    tabs.filter { it in withRows || it in pages }

/**
 * Files rows under the editor's sections, in the editor's order. Each of [providers] is one list of (XMB row key, row) in the order its
 * builder made it. A row with an entry in [XmbEditorMap] takes the place of its editor row ([EditorSlot.order]: the editor's rank, then the
 * registry order inside a rank); a row without one takes the place of the row before it in its own list, as its nearest sibling (so a help
 * link stays under its setting); a list that starts with such a row files it under General, last. Rows with the same place keep the order
 * they came in (the sort is stable). Never drops a row.
 */
internal fun <T> xmbArrange(
    providers: List<List<Pair<String, T>>>,
    slots: XmbEditorSlots,
    refOf: (String) -> XmbRef? = XmbEditorMap::refOf,
): List<XmbArranged<T>> {
    val orphan = EditorSlot(EditorTabs.GENERAL, "", Int.MAX_VALUE)
    val placed = ArrayList<XmbArranged<T>>()
    for (provider in providers) {
        var previous: EditorSlot? = null
        for ((key, item) in provider) {
            val slot = refOf(key)?.let { slots.slotOf(it.slot) } ?: previous ?: orphan
            previous = slot
            placed += XmbArranged(slot, key, item)
        }
    }
    return placed.sortedBy { it.slot.order }
}

/** The rows of [arranged] in section [tab], in order. */
internal fun <T> xmbRowsIn(arranged: List<XmbArranged<T>>, tab: String): List<XmbArranged<T>> = arranged.filter { it.slot.tab == tab }

/** A section's rows by heading, headings in the order they first appear: the editor's page groups its rows the same way. */
internal fun <T> xmbGroups(rows: List<XmbArranged<T>>): List<Pair<String, List<XmbArranged<T>>>> =
    rows.groupBy { it.slot.group }.toList()

/**
 * What this game changed from its container, by the editor's own rule: a row is changed when its game binding holds a value of the
 * game's own ([Inherit.NONE] rows, which have no container value, never are) and Reset is the binding's own reset. [extras] reads
 * and writes the game's extras; the caller saves the shortcut after a reset.
 */
internal class XmbOverrides(
    private val defs: List<SettingDef>,
    private val slots: XmbEditorSlots,
    private val extras: GameExtras,
) {
    private val byId: Map<String, SettingDef> = defs.associateBy { it.id }
    private val tracked = HashMap<XmbRef, List<SettingDef>>()

    /** What the editor calls "changed here" (Origin.SET_HERE) in a game's editor. */
    fun isChanged(def: SettingDef): Boolean {
        val g = def.game ?: return false
        if (g.inherit == Inherit.NONE) return false
        return runCatching { g.read(extras) != null }.getOrDefault(false)
    }

    /** The registry rows [ref]'s dot and Reset follow. */
    fun defsOf(ref: XmbRef): List<SettingDef> = tracked.getOrPut(ref) {
        (ref.tracks.mapNotNull { byId[it] } + ref.tracksPrefix.flatMap { p -> defs.filter { it.id.startsWith(p) } }).distinctBy { it.id }
    }

    /** The rows of [ref] this game changed (empty: the row shows the container's value). */
    fun changed(ref: XmbRef): List<SettingDef> = defsOf(ref).filter { isChanged(it) }

    /** The editor's Reset for each of [list]: the game follows its container again. */
    fun reset(list: List<SettingDef>) {
        for (def in list) {
            val g = def.game ?: continue
            val own = g.reset
            runCatching { if (own != null) own(extras) else g.keys.forEach { extras.put(it, null) } }
        }
    }

    /** Every row the XMB table stands for in [tab], and for Win components every component row (the editor counts them all). */
    private val trackedByTab: Map<String, List<SettingDef>> by lazy {
        val all = XmbEditorMap.all().flatMap { defsOf(it) } + defs.filter { WinComponentsLogic.isComponentRow(it.id) }
        all.distinctBy { it.id }.groupBy { slots.tabOf(it.id) ?: "" }
    }

    /** How many rows of section [tab] this game changed: the "n changed" of its link. */
    fun countIn(tab: String): Int = trackedByTab[tab].orEmpty().count { isChanged(it) }
}
