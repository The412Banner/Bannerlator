package com.winlator.star.ui.deck.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.ui.deck.XmbTapToEdit
import com.winlator.star.ui.deck.deckShape
import com.winlator.star.ui.deck.rememberXmbEdit
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.simple.L

// The "Win components" tab's own page of the XMB / Deck container editor: the builtin / native DLL rows
// (components.*) and the two media codec rows (windows.codecBackend, windows.softwareDecoding), which the
// registry declares as ordinary rows in the category CAT_COMPONENTS. The layout (ContainerLayout) hides those rows
// from the editor's own row list and this section draws them instead, with the same row composable (SettingRow),
// so a control behaves exactly as it did on the Windows tab:
//   - a search bar on top, which filters all of them (the ones in the popup too);
//   - the first FIRST of them: what the user changed from the default always first, then filled from the top of the
//     registry order (WinComponentsLogic.shownIds);
//   - "Show more (N)": a dialog with the rest, its own search bar and the same controls, so nothing needs a trip back.
// The install lists around them (This game needs, All components) are DeckComponentsState's and come from the
// old "components" category; this page is the "wincomp" source of the same tab and sorts above them.
// Shown from the Advanced level up, as the DLL rows always were (the editor keeps the level in its preference).

/** The rules of the page, pure (no Compose, no Android), so they are unit-tested. */
internal object WinComponentsLogic {
    /** How many components the page shows before "Show more". */
    const val FIRST = 5

    /** The key of this page's extras: a pseudo "old category" that ContainerLayout feeds into the Win components tab. */
    const val EXTRAS_KEY = "wincomp"

    /** A registry row this page draws: a DLL row, or one of the two codec rows (declared in the Windows components category). */
    fun isComponentRow(id: String): Boolean =
        id.startsWith("components.") || id == "windows.codecBackend" || id == "windows.softwareDecoding"

    /**
     * Which of [order] (the ids, in display order) the page shows: everything in [changed] (changed from the default, so
     * it never hides behind "Show more"), then ids from the top of [order] until [count] are shown. More than [count]
     * changed ones are all shown. The result keeps the order of [order], so a row does not jump when it is toggled.
     */
    fun shownIds(order: List<String>, changed: Set<String>, count: Int = FIRST): List<String> {
        val must = order.filter { it in changed }
        val room = (count - must.size).coerceAtLeast(0)
        val fill = order.filter { it !in changed }.take(room)
        val chosen = HashSet<String>(must)
        chosen.addAll(fill)
        return order.filter { it in chosen }
    }

    /** What is left for the popup: [order] without [shown], in order. */
    fun restIds(order: List<String>, shown: List<String>): List<String> {
        val have = shown.toHashSet()
        return order.filter { it !in have }
    }

    /** A search match: every word of [query] is in the text of one of [parts]. A blank query matches everything. */
    fun matches(query: String, vararg parts: String?): Boolean {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = parts.filterNotNull().joinToString(" ").lowercase()
        return words.all { it in hay }
    }
}

/**
 * The page's state and items. One per editor, owned by [DeckWindowsSection] (which every host already builds and draws the
 * dialogs of), so no host screen has to know about it.
 */
internal class WinComponentsSection(
    private val env: SettingsEnv,
    val source: SettingsSource,
) {
    /** The page's search bar. */
    var query by mutableStateOf("")
    /** The ids the "Show more" popup lists (the rest when it was opened); null while it is closed. */
    var popupIds by mutableStateOf<List<String>?>(null)
    var helpFor by mutableStateOf<SettingDef?>(null)
    /** What Y on the page runs (the editor's [CategoryExtra.focusSearch]): focus the search bar and start typing. Set by the bar itself. */
    private var openSearch: () -> Unit = {}

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(env.context) }
    private val all: List<SettingDef> by lazy { SettingsRegistry.defs.filter { WinComponentsLogic.isComponentRow(it.id) } }
    private val byId: Map<String, SettingDef> by lazy { SettingsRegistry.defs.associateBy { it.id } }

    /** Another row's current value by id, as the editor reads it ("" for a row that does not apply). */
    val values: (String) -> String = { id -> byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: "" }

    private fun applies(d: SettingDef): Boolean = source.shows(d) && env.world in d.worlds && d.visible(env, values)

    /** A component row by id while it applies in this editor. */
    fun defOf(id: String): SettingDef? = all.firstOrNull { it.id == id }?.takeIf { applies(it) }

    // The editor's level preference (DeckSettingsEditor's LEVEL_PREF); read only to hide the page at Simple.
    private fun advancedNow(): Boolean = SettingLevel.parse(prefs.getString(LEVEL_PREF, null)) == SettingLevel.ADVANCED

    /** The page's extras for the editor: a Wine container, All containers or Wine game with a container form. */
    fun extras(): Map<String, CategoryExtra> {
        if (env.vm == null || env.world != World.WINE) return emptyMap()
        // searchKey / focusSearch: Y on this tab goes to this page's search bar, not to the install lists' search (DeckComponentsState).
        return mapOf(
            WinComponentsLogic.EXTRAS_KEY to CategoryExtra(
                top = ::topItems,
                searchKey = SEARCH_ITEM_KEY,
                focusSearch = { openSearch() },
                level = SettingLevel.ADVANCED,
            ),
        )
    }

    private fun topItems(): List<PageExtra> {
        if (!advancedNow()) return emptyList()
        val rows = all.filter { applies(it) }
        if (rows.isEmpty()) return emptyList()
        val q = query
        val matching = rows.filter { WinComponentsLogic.matches(q, it.label, it.hint, it.tech, it.group) }
        val changed = matching.filter { source.editable(it) && source.origin(it) == Origin.SET_HERE }.mapTo(HashSet()) { it.id }
        val order = matching.map { it.id }
        val shown = WinComponentsLogic.shownIds(order, changed)
        val rest = WinComponentsLogic.restIds(order, shown)
        val byShownId = matching.associateBy { it.id }
        return buildList {
            add(PageExtra("wincomp-label") { GroupLabel(L("Windows components"), top = 4.dp) })
            add(PageExtra(SEARCH_ITEM_KEY) {
                WinComponentsSearchBar(
                    query = query,
                    onQuery = { query = it },
                    placeholder = L("Search components"),
                    modifier = Modifier.padding(bottom = 10.dp),
                    onOpenSearch = { openSearch = it },
                )
            })
            if (matching.isEmpty()) {
                add(PageExtra("wincomp-none") {
                    Text(L("Nothing matches \"%1\$s\".", q), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp))
                })
            }
            shown.forEachIndexed { i, id ->
                val def = byShownId[id] ?: return@forEachIndexed
                add(PageExtra("wincomp-row:$id") {
                    GroupCard(first = i == 0, last = i == shown.lastIndex, highlight = false) { WinComponentRow(this@WinComponentsSection, def) }
                })
            }
            if (rest.isNotEmpty()) {
                add(PageExtra("wincomp-more") {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        GroupCard(first = true, last = true, highlight = false) {
                            DeckActionRow(DeckAction("more", Icons.Filled.MoreHoriz, L("Show more (%1\$s)", rest.size),
                                L("The other components, with their own search.")) { popupIds = rest })
                        }
                    }
                })
            }
        }
    }

    /** The popup of "Show more" and the help of a row. Always composed by the host, so they outlive the page's scrolling. */
    @Composable
    fun Dialogs() {
        val ids = popupIds
        if (ids != null) WinComponentsDialog(this, ids)
        helpFor?.let { def ->
            OutlinedAlertDialog(
                onDismissRequest = { helpFor = null },
                confirmButton = { TextButton(onClick = { helpFor = null }) { Text(L("OK")) } },
                title = { Text(def.label) },
                text = { Text(def.help ?: def.hint ?: "") },
            )
        }
    }

    companion object {
        const val SEARCH_ITEM_KEY = "wincomp-search"
    }
}

/** One component row: the editor's own row composable, so a three-state / choice control behaves as before. */
@Composable
private fun WinComponentRow(section: WinComponentsSection, def: SettingDef) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            def = def,
            source = section.source,
            values = section.values,
            level = SettingLevel.ADVANCED,
            wide = maxWidth >= 560.dp,
            controlMax = maxWidth * 0.52f,
            onPick = { v -> section.source.set(def, v) },
            onCustom = {},
            onHelp = { section.helpFor = def },
            onReset = { section.source.reset(def) },
        )
    }
}

/**
 * "Show more": the components the page did not show when it was opened, with a search bar of its own and the same rows.
 * The list is fixed at opening (a row toggled in here does not leave it), the rows read their values live. It is a dialog
 * of the app's dialog host, so the XMB highlight and keyboard work in it, and B closes it.
 */
@Composable
private fun WinComponentsDialog(section: WinComponentsSection, ids: List<String>) {
    var query by remember { mutableStateOf("") }
    val rows = ids.mapNotNull { section.defOf(it) }.filter { WinComponentsLogic.matches(query, it.label, it.hint, it.tech, it.group) }
    OutlinedAlertDialog(
        onDismissRequest = { section.popupIds = null },
        confirmButton = { TextButton(onClick = { section.popupIds = null }) { Text(L("Done")) } },
        title = { Text(L("More components (%1\$s)", ids.size)) },
        text = {
            Column {
                WinComponentsSearchBar(
                    query = query,
                    onQuery = { query = it },
                    placeholder = L("Search these components"),
                    autoFocus = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (rows.isEmpty()) {
                    Text(L("Nothing matches \"%1\$s\".", query), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    // The group cards cast a shadow round them, which the list would clip at its edge: it keeps some room.
                    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(rows, key = { _, d -> d.id }) { i, d ->
                            GroupCard(first = i == 0, last = i == rows.lastIndex, highlight = false) { WinComponentRow(section, d) }
                        }
                    }
                }
            }
        },
    )
}

/**
 * A search bar in the Deck editor's convention (as the component catalog's own): in the XMB home the pad only highlights it and
 * A starts typing (the XMB keyboard), B or the keyboard's own action ends it; elsewhere it is an ordinary field.
 * [autoFocus] puts the XMB highlight on it when it appears (the popup).
 */
@Composable
private fun WinComponentsSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    /** Hands the page what Y runs to open this bar (the page, not the popup, passes it). */
    onOpenSearch: ((() -> Unit) -> Unit)? = null,
) {
    val xmbHome = rememberXmbHome()
    // Only the XMB home has a typing state; the other Deck modes keep the plain focus.
    val edit = if (xmbHome) rememberXmbEdit() else null
    // Y opens the bar: it takes focus and starts typing, as A on it would.
    if (onOpenSearch != null) {
        SideEffect { onOpenSearch(if (edit != null) ({ runCatching { edit.outer.requestFocus() }; edit.start() }) else ({})) }
    }
    if (autoFocus && edit != null) {
        LaunchedEffect(edit) {
            withFrameNanos { }
            runCatching { edit.outer.requestFocus() }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        XmbTapToEdit(Modifier.weight(1f), deckShape(16.dp), edit) { fieldModifier, stopEditing ->
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onQuery("") },
                    ) { Icon(Icons.Filled.Close, contentDescription = L("Clear search")) }
                }),
                shape = deckShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = if (xmbHome) KeyboardActions(onSearch = { stopEditing() }) else KeyboardActions.Default,
                modifier = fieldModifier,
            )
        }
    }
}
