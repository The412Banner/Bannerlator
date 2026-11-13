package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.settings.EditorScope
import com.winlator.star.ui.deck.settings.EditorTabs
import com.winlator.star.ui.deck.settings.GameExtras
import com.winlator.star.ui.deck.settings.SettingDef
import com.winlator.star.ui.deck.settings.SettingsCategory
import com.winlator.star.ui.deck.settings.SettingsEnv
import com.winlator.star.ui.deck.settings.SettingsRegistry

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The XMB Home's game Settings, laid out like the container editor: the editor's own sections in its own order (Game, General,
// Graphics, Performance, Controls, Windows, Win components, Advanced), each a column of XMB rows under the editor's group headings.
// The rows are the ones the older sections build (XmbGameSettings.kt, XmbSettingsPhase2*.kt) and write the same extras the same way;
// only where they sit and what they are called change, by the editor's own layout (XmbEditorSections.kt).
//
// What the game changed from its container shows as one small dot on the row, X (or "Reset" beside the highlighted row) puts the
// container's value back, and a section's link says "n changed" while it has any. The rule for "changed" and the reset are the editor's
// (GameBinding.read / reset), not a second copy.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** A game's extras as the editor's bindings read and write them, straight on the shortcut (the caller saves it after a write). */
private class XmbGameExtras(private val shortcut: Shortcut, private val context: Context) : GameExtras {
    override val env: SettingsEnv by lazy { SettingsEnv(context, EditorScope.GAME, null, shortcut) }
    override fun get(key: String): String? = if (shortcut.hasExtra(key)) shortcut.getExtra(key) else null
    override fun put(key: String, value: String?) { shortcut.putExtra(key, value) }
    override val container: Container get() = shortcut.container
}

/** [this] without its headings (the XMB Home groups rows by the editor's headings), keyed by row key. */
private fun List<XmbRow>.withoutHeaders(): List<Pair<String, XmbRow>> = filter { it !is XmbRow.Header }.map { it.key to it }

/** A section whose page is a menu of its own (the Win components list, the environment variables), under the editor's title and icon. */
private fun xmbPageSection(category: SettingsCategory, page: XmbMenu, mark: (XmbRow) -> XmbRow): XmbMenu =
    XmbMenu(category.label, category.icon, panel = page.panel, onClose = page.onClose) { page.rows(this).map(mark) }

internal fun xmbEditorSettingsMenu(xmb: XmbScope, p: XmbPrefs, host: XmbGameHost): XmbMenu {
    val s = p.shortcut
    val slots = XmbEditorSlots.game
    val overrides = XmbOverrides(SettingsRegistry.defs, slots, XmbGameExtras(s, xmb.context))
    // The Box64 / FEXCore / services / sharpness rows read versions and presets off the main thread: made when a section first needs them.
    val advanced = lazy(LazyThreadSafetyMode.NONE) { XmbAdvancedRows(xmb, s) }

    fun resetChanged(changed: List<SettingDef>) {
        overrides.reset(changed)
        s.saveData()
        xmb.saved()
        xmb.refresh()
    }

    // A row that is one editor row speaks with the editor's words (its label, and its hint where the row has no subtitle of its own); a
    // row this game changed gets its dot and its reset. Every other row is left as built.
    fun mark(row: XmbRow): XmbRow {
        val ref = XmbEditorMap.refOf(row.key) ?: return row
        if (ref.sameRow) slots.slotOf(ref.slot)?.let { row.editorLabel = it.label; row.editorHint = it.hint }
        val changed = overrides.changed(ref)
        if (changed.isNotEmpty()) row.overridden = XmbOverride { resetChanged(changed) }
        return row
    }

    // The rows the older sections build, in their own order: the editor's sections are cut out of these.
    fun providers(): List<List<Pair<String, XmbRow>>> = listOf(
        generalRows(xmb, p, host).withoutHeaders(),
        controllerRows(xmb, p).withoutHeaders(),
        advanced.value.let { if (it.loaded) it.rows().withoutHeaders() else emptyList<Pair<String, XmbRow>>() },
    )

    fun tabRows(tab: String): List<XmbRow> {
        val rows = ArrayList<XmbRow>()
        val arranged = xmbArrange(providers(), slots).distinctBy { it.key }
        for ((group, items) in xmbGroups(xmbRowsIn(arranged, tab))) {
            if (group.isNotBlank()) rows += XmbRow.Header("g:$group", group)
            for (a in items) rows += mark(a.item)
        }
        return rows
    }

    // The sections are fixed when Settings opens: the editor's tabs that have a row for this game, and the two with a page of their own.
    val withRows = xmbArrange(
        listOf(generalRows(xmb, p, host).withoutHeaders(), controllerRows(xmb, p).withoutHeaders()), slots,
    ).map { it.slot.tab }.toSet()
    val sections = xmbSectionTabs(xmbGameTabs(), withRows)

    fun section(n: Int): XmbMenu {
        val tab = sections[n]
        val category = EditorTabs.tab(tab)
        val menu = when (tab) {
            EditorTabs.WINCOMP -> xmbPageSection(category, xmbWinComponentsMenu(xmb, s), ::mark)
            EditorTabs.ADVANCED -> xmbPageSection(category, xmbEnvVarsMenu(xmb, s), ::mark)
            else -> XmbMenu(category.label, category.icon) { tabRows(tab) }
        }
        menu.siblings = XmbSiblings(n, sections.size, ::section) { i -> "sec$i" }
        return menu
    }

    return XmbMenu("Settings", Icons.Filled.Settings) {
        sections.mapIndexed { n, tab ->
            val category = EditorTabs.tab(tab)
            val changed = overrides.countIn(tab)
            XmbRow.Link("sec$n", category.label, category.icon, value = if (changed > 0) "$changed changed" else null, subtitle = category.desc) { section(n) }
        }
    }
}
