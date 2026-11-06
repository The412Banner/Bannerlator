package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.deck.deckShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckLine

// What the Deck settings pages share besides their rows: a section (a list, a picker, an order, an
// action) that a host screen adds to a category page, the action lines those sections show, and the
// hidden rows a section reads and writes its stored string through, so it lands in the editor's
// draft and "Unsaved changes" like any row.

/**
 * One part of the editor that is not a setting row (e.g. the drive list or the ReShade effects). The
 * host screen builds it once per editor, adds its [extras] to the category pages and draws its
 * [Dialogs] beside the editor; [onReturn] runs when the screen is shown again after another one.
 */
internal interface DeckSection {
    fun extras(): Map<String, CategoryExtra>

    @Composable
    fun Dialogs()

    fun onReturn() {}

    /**
     * True while an activity the section started (a file picker) is open. Coming back from it must not
     * reload the form, or the pick lands on a form that is thrown away. [onReturn] clears it.
     */
    val keepsForm: Boolean get() = false
}

/** Several sections' extras as one map: a category's items keep the order the sections are listed in. */
internal fun mergeExtras(parts: List<Map<String, CategoryExtra>>): Map<String, CategoryExtra> {
    val byCat = LinkedHashMap<String, MutableList<CategoryExtra>>()
    parts.forEach { m -> m.forEach { (cat, e) -> byCat.getOrPut(cat) { mutableListOf() } += e } }
    return byCat.mapValues { (_, list) ->
        if (list.size == 1) list[0]
        else {
            val search = list.firstOrNull { it.searchKey != null }
            CategoryExtra(
                top = { list.flatMap { it.top() } },
                bottom = { list.flatMap { it.bottom() } },
                searchKey = search?.searchKey,
                focusSearch = search?.focusSearch ?: {},
                level = list.minOf { it.level },
            )
        }
    }
}

/** One action of a section: the whole line is the focus target and A runs it. */
internal class DeckAction(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val hint: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** [actions] as page items in one card, under [label] when it is set. Nothing when there are none. */
internal fun actionItems(prefix: String, label: String?, actions: List<DeckAction>): List<PageExtra> {
    if (actions.isEmpty()) return emptyList()
    return listOfNotNull(label?.let { l -> PageExtra("$prefix-label") { GroupLabel(l) } }) +
        actions.mapIndexed { i, a ->
            PageExtra("$prefix:${a.key}") {
                GroupCard(first = i == 0, last = i == actions.lastIndex, highlight = false) { DeckActionRow(a) }
            }
        }
}

@Composable
internal fun DeckActionRow(action: DeckAction) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(deckShape(12.dp), scaleTo = 1f)
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Icon(action.icon, contentDescription = null, tint = if (action.enabled) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(action.label, color = if (action.enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(action.hint, color = cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/**
 * A line of a section's card: a title and hint on the left, [trailing] on the right. With [onClick]
 * the whole line is the focus target and A runs it; otherwise the trailing controls take the focus.
 */
@Composable
internal fun DeckLine(
    label: String,
    hint: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    error: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.deckFocusRing(deckShape(12.dp), scaleTo = 1f).clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = if (enabled) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = when {
                    error -> cs.error
                    enabled -> cs.onSurface
                    else -> cs.onSurface.copy(alpha = 0.5f)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            hint?.let { Text(it, color = if (error) cs.error else cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() }
        }
    }
}

/** A small outlined button for a section line (Remove, Move up, Browse…). */
@Composable
internal fun DeckSmallButton(label: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, deckLine().copy(alpha = 0.7f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = if (label.isEmpty()) null else label, tint = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (label.isNotEmpty()) {
            Text(label, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/**
 * A row that is never shown: a stored string a section edits as a whole (a JSON list, the env string).
 * Reading and writing it through the editor's source puts the section's edits in the same draft as
 * every row, so Save, Discard and the unsaved check cover them.
 */
internal fun hiddenDef(
    id: String,
    category: String,
    vm: VmBinding?,
    game: GameBinding?,
    worlds: Set<World> = WINE_ONLY,
    onDefaults: Boolean = true,
) = SettingDef(
    id = id, category = category, group = "", label = id, level = SettingLevel.EXPERT,
    control = { _, _ -> Control.TextField() },
    vm = vm, game = game, onDefaults = onDefaults, worlds = worlds,
    visible = { _, _ -> false },
)

/** A game's own value of one extra, never the container's: absent reads as "". */
internal fun ownExtra(key: String, emptyIsAbsent: Boolean = true) = GameBinding(
    keys = listOf(key),
    read = { e -> e.get(key) ?: "" },
    write = { e, v -> e.put(key, if (emptyIsAbsent && v.isEmpty()) null else v) },
    inherit = Inherit.NONE,
)

/** Reads and writes rows by id through the editor's source; "" for a row that doesn't apply there. */
internal class DefAccess(val source: SettingsSource) {
    private val byId by lazy { SettingsRegistry.defs.associateBy { it.id } }

    fun def(id: String): SettingDef? = byId[id]?.takeIf { source.shows(it) && source.env.world in it.worlds }
    fun value(id: String): String = def(id)?.let { source.value(it) } ?: ""
    fun set(id: String, v: String) { def(id)?.let { if (source.editable(it)) source.set(it, v) } }
    fun editable(id: String): Boolean = def(id)?.let { source.editable(it) } == true
}
