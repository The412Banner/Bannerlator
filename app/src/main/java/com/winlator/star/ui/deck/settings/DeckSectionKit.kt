package com.winlator.star.ui.deck.settings

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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.deck.DeckInk
import com.winlator.star.ui.deck.XmbGroupItem
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckFocusRingInk
import com.winlator.star.ui.deck.deckInset
import com.winlator.star.ui.deck.deckLineAt
import com.winlator.star.ui.deck.rememberXmbFocusGroup
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.deck.xmbInner

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
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .xmbRowInset()
                .deckFocusRingInk(RoundedCornerShape(12.dp), item, scaleTo = 1f)
                .clickable(enabled = action.enabled, onClick = action.onClick)
                .padding(horizontal = xmbRowPad(18.dp), vertical = 14.dp),
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
}

/**
 * In the XMB interface a row of a group card is inset a little, so the gliding outline sits inside the card's rounded corners and
 * clear of the hairlines between rows; elsewhere nothing changes. Pair it with [xmbRowPad] on the row's own padding.
 */
@Composable
internal fun Modifier.xmbRowInset(): Modifier = if (rememberXmbHome()) this.padding(horizontal = 6.dp, vertical = 3.dp) else this

/** [base] less the 6 dp [xmbRowInset] takes in the XMB interface, so the text stays where it was. */
@Composable
internal fun xmbRowPad(base: Dp): Dp = if (rememberXmbHome()) base - 6.dp else base

/**
 * A line of a section's card: a title and hint on the left, [trailing] on the right. With [onClick]
 * the whole line is the focus target and A runs it; otherwise the trailing controls take the focus.
 *
 * XMB interface: the line is always ONE stop of the D-pad. With [onClick] it is a plain stop (the trailing content is display
 * only there, taps still work); with [activate] A runs that (a switch in [trailing] that A flips) and the trailing content is
 * display only too; with neither, A enters the line and the buttons in [trailing] take the focus (B comes back). Outside XMB
 * [activate] is not used and nothing changes.
 */
@Composable
internal fun DeckLine(
    label: String,
    hint: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    error: Boolean = false,
    activate: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (rememberXmbHome()) {
        val shape = RoundedCornerShape(12.dp)
        val inset = Modifier.fillMaxWidth().xmbRowInset()
        when {
            onClick != null -> DeckInk { item, cs ->
                DeckLineBody(
                    cs, label, hint, icon, enabled, error, trailing, Modifier.focusProperties { canFocus = false },
                    inset.deckFocusRingInk(shape, item, scaleTo = 1f).clip(shape).clickable(enabled = enabled, onClick = onClick),
                )
            }
            activate != null -> XmbGroupItem(rememberXmbFocusGroup(), shape, inset, onActivate = { if (enabled) activate() }) {
                DeckLineBody(MaterialTheme.colorScheme, label, hint, icon, enabled, error, trailing, Modifier.focusProperties { canFocus = false }, Modifier.fillMaxWidth())
            }
            trailing != null -> XmbGroupItem(rememberXmbFocusGroup(), shape, inset) {
                DeckLineBody(MaterialTheme.colorScheme, label, hint, icon, enabled, error, trailing, Modifier.xmbInner(first = true), Modifier.fillMaxWidth())
            }
            else -> DeckLineBody(MaterialTheme.colorScheme, label, hint, icon, enabled, error, null, Modifier, inset)
        }
        return
    }
    DeckLineBody(
        MaterialTheme.colorScheme, label, hint, icon, enabled, error, trailing, Modifier,
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f).clickable(enabled = enabled, onClick = onClick)
                else Modifier
            ),
    )
}

/** The content of a [DeckLine]; [modifier] carries whatever makes it focusable, [trailingModifier] the trailing row's. */
@Composable
private fun DeckLineBody(
    cs: ColorScheme,
    label: String,
    hint: String?,
    icon: ImageVector?,
    enabled: Boolean,
    error: Boolean,
    trailing: (@Composable () -> Unit)?,
    trailingModifier: Modifier,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = xmbRowPad(18.dp), vertical = 12.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = trailingModifier) { it() }
        }
    }
}

/** A small outlined button for a section line (Remove, Move up, Browse…). Inside an XMB line it is one of its inner buttons. */
@Composable
internal fun DeckSmallButton(label: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .xmbInner()
                .clip(shape)
                .background(deckInset(0.45f))
                .border(1.dp, deckLineAt(0.7f), shape)
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
