package com.winlator.star.ui.deck.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.ui.deck.DECK_COMPACT_WIDTH_DP
import com.winlator.star.ui.deck.DeckGlyph
import com.winlator.star.ui.deck.GlyphKind
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.deckCardFill
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckGlass
import com.winlator.star.ui.deck.deckLine
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.UnsavedChangesDialog
import kotlin.math.roundToInt

/** One link of the "Inherits from" chain. A null [onClick] marks the level being edited. */
internal class ChainLink(val label: String, val icon: ImageVector, val onClick: (() -> Unit)?)

internal class EditorHeader(
    val overline: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val chain: List<ChainLink> = emptyList(),
    /** Small text at the end of the chain row, e.g. the container's Wine layer. */
    val meta: String? = null,
)

// The Simple / Advanced / Expert choice is remembered across editors.
private const val LEVEL_PREF = "deck_settings_level"

/**
 * The Deck settings editor: categories on the left, the chosen category's rows on the right (on a
 * phone the categories are a list that drills into one category). Every value goes through [source],
 * so the same screen edits a game, a container, All containers and the app's own settings. Edits
 * land in a draft with an "Unsaved changes" bar; leaving with unsaved changes asks first. Categories
 * whose rows are all handed off still list them, each opening the classic editor via [onHandoff].
 *
 * [onExit] is null when the editor is a tab's root page; Back then belongs to the Deck shell.
 */
@Composable
internal fun DeckSettingsEditor(
    header: EditorHeader,
    source: SettingsSource,
    categories: List<SettingsCategory>,
    defs: List<SettingDef>,
    handoffs: Map<String, List<Handoff>>,
    onExit: (() -> Unit)?,
    onHandoff: (Handoff) -> Unit,
    onChainLink: ((ChainLink) -> Unit)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val env = source.env
    val compact = LocalConfiguration.current.screenWidthDp < DECK_COMPACT_WIDTH_DP
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var level by remember {
        mutableStateOf(runCatching { SettingLevel.valueOf(prefs.getString(LEVEL_PREF, null) ?: "") }.getOrDefault(SettingLevel.SIMPLE))
    }
    val changeLevel: (SettingLevel) -> Unit = { l ->
        level = l
        prefs.edit().putString(LEVEL_PREF, l.name).apply()
    }

    val byId = remember(defs) { defs.associateBy { it.id } }
    val values: (String) -> String = { id -> byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: "" }
    fun applies(def: SettingDef) = source.shows(def) && def.visible(env, values)

    // Per category: every row that applies in this editor, and the ones shown at the chosen level.
    val rowsByCat = categories.associate { c -> c.id to defs.filter { it.category == c.id && applies(it) } }
    val shownByCat = rowsByCat.mapValues { (_, rows) -> rows.filter { it.level <= level } }
    val visibleCats = categories.filter { c ->
        shownByCat[c.id].orEmpty().isNotEmpty() || (level != SettingLevel.SIMPLE && handoffs[c.id].orEmpty().isNotEmpty())
    }
    val tracksOverrides = env.scope == EditorScope.GAME || env.scope == EditorScope.CONTAINER
    fun overrides(catId: String): List<SettingDef> =
        if (tracksOverrides) rowsByCat[catId].orEmpty().filter { source.editable(it) && source.origin(it) == Origin.SET_HERE } else emptyList()

    var selected by rememberSaveable { mutableStateOf(categories.firstOrNull()?.id ?: "") }
    val current = visibleCats.firstOrNull { it.id == selected } ?: visibleCats.firstOrNull()
    var phonePage by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }

    var helpFor by remember { mutableStateOf<SettingDef?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<SettingDef, String>?>(null) }
    var customFor by remember { mutableStateOf<SettingDef?>(null) }
    var afterUnsaved by remember { mutableStateOf<(() -> Unit)?>(null) }
    var saving by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }

    val pick: (SettingDef, String) -> Unit = { def, v ->
        val ask = def.confirm?.invoke(v)
        if (ask != null && source.value(def) != v) pendingConfirm = def to v else source.set(def, v)
    }

    fun save(after: (() -> Unit)? = null) {
        if (saving) return
        saving = true
        source.save { ok ->
            saving = false
            if (ok && env.scope != EditorScope.DEFAULTS) Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            if (ok) after?.invoke()
        }
    }

    // Anything that leaves this editor asks first while there are unsaved edits.
    val guard: (() -> Unit) -> Unit = { action -> if (source.dirty) afterUnsaved = action else action() }

    fun openCategory(id: String) {
        selected = id
        query = null
        phonePage = true
    }

    BackHandler(enabled = query != null || (compact && phonePage) || onExit != null) {
        when {
            query != null -> query = null
            compact && phonePage -> phonePage = false
            onExit != null -> guard(onExit)
        }
    }

    // Controller: X / Start saves while there is something to save, Y searches, L1 / R1 step through categories.
    val deckActions = LocalDeckActions.current
    val dirty = source.dirty
    val saveNow by rememberUpdatedState { save() }
    DisposableEffect(deckActions, dirty) {
        val act: () -> Unit = { saveNow() }
        if (dirty) {
            deckActions.options.value = act
            deckActions.optionsLabel.value = "Save"
        }
        onDispose { if (deckActions.options.value === act) deckActions.options.value = null }
    }
    val searchNow by rememberUpdatedState { query = query ?: "" }
    val stepNow by rememberUpdatedState { delta: Int ->
        if (visibleCats.isNotEmpty()) {
            val i = visibleCats.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
            selected = visibleCats[(i + delta + visibleCats.size) % visibleCats.size].id
            query = null
        }
    }
    DisposableEffect(deckActions) {
        val search: () -> Unit = { searchNow() }
        val step: (Int) -> Unit = { stepNow(it) }
        deckActions.search.value = search
        deckActions.subTab.value = step
        deckActions.subTabLabel.value = "Category"
        onDispose {
            if (deckActions.search.value === search) deckActions.search.value = null
            if (deckActions.subTab.value === step) deckActions.subTab.value = null
        }
    }

    // The row a search result jumps to glows briefly.
    LaunchedEffect(flash) {
        if (flash != null) {
            kotlinx.coroutines.delay(1600)
            flash = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EditorTop(
            header = header,
            level = level,
            onLevel = changeLevel,
            onSearch = { query = query ?: "" },
            onBack = onExit?.let { exit -> { guard(exit) } },
            onChainLink = { link -> onChainLink?.let { cb -> guard { cb(link) } } },
            compact = compact,
            extra = headerExtra,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val page: @Composable (Modifier) -> Unit = { mod ->
                val q = query
                if (q != null) {
                    SearchPane(
                        query = q,
                        onQuery = { query = it },
                        onClose = { query = null },
                        source = source,
                        values = values,
                        categories = categories,
                        defs = defs.filter { applies(it) },
                        handoffs = handoffs,
                        level = level,
                        compact = compact,
                        onPick = { def ->
                            if (def.level > level) changeLevel(def.level)
                            openCategory(def.category)
                            jumpTo = def.id
                            flash = def.id
                        },
                        onHandoff = { h -> guard { onHandoff(h) } },
                        modifier = mod,
                    )
                } else if (current != null) {
                    val over = overrides(current.id)
                    CategoryPage(
                        category = current,
                        rows = shownByCat[current.id].orEmpty(),
                        handoffs = handoffs[current.id].orEmpty(),
                        source = source,
                        values = values,
                        level = level,
                        overrideCount = over.size,
                        compact = compact,
                        jumpTo = jumpTo,
                        onJumpDone = { jumpTo = null },
                        flash = flash,
                        onPick = pick,
                        onCustom = { customFor = it },
                        onHelp = { helpFor = it },
                        onResetAll = { over.forEach { source.reset(it) } },
                        onHandoff = { h -> guard { onHandoff(h) } },
                        onBack = if (compact) ({ phonePage = false }) else null,
                        modifier = mod,
                    )
                }
            }
            if (compact) {
                if (query != null || (phonePage && current != null)) {
                    page(Modifier.fillMaxSize())
                } else {
                    CategoryList(
                        categories = visibleCats,
                        current = current?.id,
                        badge = { overrides(it).size },
                        compact = true,
                        onSelect = { openCategory(it.id) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                val listWidth = (LocalConfiguration.current.screenWidthDp * 0.28f).dp.coerceIn(210.dp, 300.dp)
                Row(modifier = Modifier.fillMaxSize()) {
                    CategoryList(
                        categories = visibleCats,
                        current = current?.id,
                        badge = { overrides(it).size },
                        compact = false,
                        onSelect = { selected = it.id; query = null },
                        modifier = Modifier.width(listWidth).fillMaxHeight(),
                    )
                    page(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        if (dirty) {
            UnsavedBar(saving = saving, compact = compact, onDiscard = { source.discard() }, onSave = { save() })
        }
    }

    helpFor?.let { def ->
        OutlinedAlertDialog(
            onDismissRequest = { helpFor = null },
            confirmButton = { TextButton(onClick = { helpFor = null }) { Text("OK") } },
            title = { Text(def.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(def.help ?: def.hint ?: "")
                    def.tech?.let {
                        Text("Also called: $it", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
    }

    pendingConfirm?.let { (def, v) ->
        val ask = def.confirm?.invoke(v)
        if (ask == null) {
            pendingConfirm = null
        } else {
            OutlinedAlertDialog(
                onDismissRequest = { pendingConfirm = null },
                confirmButton = { TextButton(onClick = { pendingConfirm = null; source.set(def, v) }) { Text(ask.confirmLabel) } },
                dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") } },
                title = { Text(ask.title) },
                text = { Text(ask.body) },
            )
        }
    }

    customFor?.let { def ->
        val control = def.control(env, values) as? Control.Select
        if (control == null) {
            customFor = null
        } else {
            CustomValueDialog(
                def = def,
                control = control,
                current = source.value(def),
                onDismiss = { customFor = null },
                onUse = { v -> customFor = null; pick(def, v) },
            )
        }
    }

    afterUnsaved?.let { action ->
        UnsavedChangesDialog(
            onSave = { afterUnsaved = null; save(after = action) },
            onDiscard = { afterUnsaved = null; source.discard(); action() },
            onKeepEditing = { afterUnsaved = null },
        )
    }
}

// ── Header ──────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorTop(
    header: EditorHeader,
    level: SettingLevel,
    onLevel: (SettingLevel) -> Unit,
    onSearch: () -> Unit,
    onBack: (() -> Unit)?,
    onChainLink: (ChainLink) -> Unit,
    compact: Boolean,
    extra: (@Composable () -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val gutter = if (compact) 16.dp else 24.dp
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = 14.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                SquareButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
            }
            IconTile(header.icon, if (compact) 44.dp else 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header.overline.uppercase(),
                    color = cs.primary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                )
                Text(
                    text = header.title,
                    color = cs.onSurface,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 22.sp else 26.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                header.subtitle?.let {
                    Text(it, color = cs.onSurfaceVariant, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!compact) {
                Spacer(Modifier.width(12.dp))
                LevelSwitch(level, onLevel)
                Spacer(Modifier.width(12.dp))
                SearchButton(onSearch)
            }
        }
        if (compact) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LevelSwitch(level, onLevel)
                SearchButton(onSearch)
            }
        }
        if (header.chain.isNotEmpty() || header.meta != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (header.chain.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(38.dp)) {
                        Icon(Icons.Filled.AccountTree, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Inherits from", color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                header.chain.forEachIndexed { i, link ->
                    if (i > 0) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(38.dp)) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    ChainChip(link, onClick = { onChainLink(link) })
                }
                header.meta?.let {
                    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.height(38.dp).padding(start = 8.dp)) {
                        Text(it, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
                    }
                }
            }
        }
        extra?.invoke()
    }
}

@Composable
private fun SquareButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(deckCardFill())
            .border(1.dp, deckLine(), shape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = cs.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun IconTile(icon: ImageVector, size: Dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(cs.primary.copy(alpha = 0.16f)),
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(size * 0.52f))
    }
}

/** The editor's segmented look, for host screens (e.g. the All-containers architecture switch). */
@Composable
internal fun DeckSegmented(options: List<Opt>, value: String, onPick: (String) -> Unit) {
    SegmentedPills(options, value, enabled = true, alignEnd = false, onPick = onPick)
}

@Composable
private fun LevelSwitch(level: SettingLevel, onLevel: (SettingLevel) -> Unit) {
    SegmentedPills(
        options = SettingLevel.entries.map { Opt(it.name, it.label) },
        value = level.name,
        enabled = true,
        alignEnd = false,
        onPick = { onLevel(SettingLevel.valueOf(it)) },
    )
}

@Composable
private fun SearchButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .border(1.dp, deckLine(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
        Text("Search", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        DeckGlyph("Y", GlyphKind.Y)
    }
}

@Composable
private fun ChainChip(link: ChainLink, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(19.dp)
    val here = link.onClick == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .height(38.dp)
            .then(if (here) Modifier else Modifier.deckFocusRing(shape, scaleTo = 1f))
            .clip(shape)
            .background(if (here) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
            .then(if (here) Modifier else Modifier.border(1.dp, deckLine(), shape).clickable(onClick = onClick))
            .padding(horizontal = 14.dp),
    ) {
        if (!here) Icon(link.icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
        Text(
            text = link.label,
            color = if (here) cs.primary else cs.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 14.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

// ── Category list ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryList(
    categories: List<SettingsCategory>,
    current: String?,
    badge: (String) -> Int,
    compact: Boolean,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier,
) {
    val requester = remember { FocusRequester() }
    // The controller starts on the list, on the category being shown.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = if (compact) 16.dp else 20.dp, end = if (compact) 16.dp else 8.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(categories, key = { it.id }) { c ->
            CategoryItem(
                category = c,
                selected = c.id == current,
                badge = badge(c.id),
                compact = compact,
                onClick = { onSelect(c) },
                // In the two-pane layout, moving the controller over a category shows it straight away.
                onFocused = if (compact) null else ({ if (c.id != current) onSelect(c) }),
                modifier = if (c.id == current) Modifier.focusRequester(requester) else Modifier,
            )
        }
    }
}

@Composable
private fun CategoryItem(
    category: SettingsCategory,
    selected: Boolean,
    badge: Int,
    compact: Boolean,
    onClick: () -> Unit,
    onFocused: (() -> Unit)?,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val accent = cs.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .deckFocusRing(shape, scaleTo = 1.02f, onFocused = onFocused)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .drawBehind {
                if (selected && !compact) {
                    val w = 4.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, size.height * 0.22f),
                        size = Size(w, size.height * 0.56f),
                        cornerRadius = CornerRadius(w / 2, w / 2),
                    )
                }
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Icon(category.icon, contentDescription = null, tint = if (selected) accent else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = category.label,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(22.dp).clip(CircleShape).background(accent),
            ) {
                Text("$badge", color = cs.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
            }
        }
        if (compact) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Category page ───────────────────────────────────────────────────────────────────────────────

private sealed interface PageItem {
    val key: String
}
private object HeaderItem : PageItem { override val key = "header" }
private class ResetAllItem(val count: Int) : PageItem { override val key = "resetAll" }
private class GroupItem(val label: String) : PageItem { override val key = "group:$label" }
private class RowItem(val def: SettingDef, val first: Boolean, val last: Boolean) : PageItem { override val key = "row:${def.id}" }
private class HandoffItem(val handoff: Handoff, val first: Boolean, val last: Boolean) : PageItem { override val key = "handoff:${handoff.label}" }

@Composable
private fun CategoryPage(
    category: SettingsCategory,
    rows: List<SettingDef>,
    handoffs: List<Handoff>,
    source: SettingsSource,
    values: (String) -> String,
    level: SettingLevel,
    overrideCount: Int,
    compact: Boolean,
    jumpTo: String?,
    onJumpDone: () -> Unit,
    flash: String?,
    onPick: (SettingDef, String) -> Unit,
    onCustom: (SettingDef) -> Unit,
    onHelp: (SettingDef) -> Unit,
    onResetAll: () -> Unit,
    onHandoff: (Handoff) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier,
) {
    val pageItems = buildList<PageItem> {
        add(HeaderItem)
        if (overrideCount > 0) add(ResetAllItem(overrideCount))
        rows.groupBy { it.group }.forEach { (group, defs) ->
            add(GroupItem(group))
            defs.forEachIndexed { i, d -> add(RowItem(d, first = i == 0, last = i == defs.lastIndex)) }
        }
        if (handoffs.isNotEmpty()) {
            add(GroupItem(if (rows.isEmpty()) "In the classic editor" else "More settings"))
            handoffs.forEachIndexed { i, h -> add(HandoffItem(h, first = i == 0, last = i == handoffs.lastIndex)) }
        }
    }
    val listState = remember(category.id) { LazyListState() }
    LaunchedEffect(jumpTo, category.id) {
        val id = jumpTo ?: return@LaunchedEffect
        val index = pageItems.indexOfFirst { it.key == "row:$id" }
        if (index >= 0) listState.animateScrollToItem(index)
        onJumpDone()
    }
    BoxWithConstraints(modifier = modifier) {
        val wideRows = maxWidth >= 560.dp
        val controlMax = maxWidth * 0.52f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = if (compact) 16.dp else 8.dp, end = if (compact) 16.dp else 24.dp, top = 4.dp, bottom = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pageItems, key = { it.key }) { item ->
                when (item) {
                    is HeaderItem -> PageHeader(category, onBack)
                    is ResetAllItem -> ResetAllButton(item.count, onResetAll)
                    is GroupItem -> GroupLabel(item.label)
                    is RowItem -> GroupCard(item.first, item.last, highlight = flash == item.def.id) {
                        SettingRow(
                            def = item.def,
                            source = source,
                            values = values,
                            level = level,
                            wide = wideRows,
                            controlMax = controlMax,
                            onPick = { v -> onPick(item.def, v) },
                            onCustom = { onCustom(item.def) },
                            onHelp = { onHelp(item.def) },
                            onReset = { source.reset(item.def) },
                        )
                    }
                    is HandoffItem -> GroupCard(item.first, item.last, highlight = false) {
                        HandoffRow(item.handoff, onHandoff)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(category: SettingsCategory, onBack: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        if (onBack != null) {
            SquareButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to categories", onBack)
            Spacer(Modifier.width(12.dp))
        }
        IconTile(category.icon, 50.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(category.label, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, maxLines = 1)
            Text(category.desc, color = cs.onSurfaceVariant, fontSize = 14.5.sp)
        }
    }
}

@Composable
private fun ResetAllButton(count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .deckFocusRing(shape, scaleTo = 1f)
                .clip(shape)
                .border(1.dp, deckLine(), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Filled.Restore, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
            Text(if (count == 1) "Reset 1 override" else "Reset $count overrides", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun GroupLabel(label: String) {
    Text(
        text = label.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.5.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** One slice of a rounded group card: rows stack into a single card, with a hairline between them. */
@Composable
private fun GroupCard(first: Boolean, last: Boolean, highlight: Boolean, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(deckCardFill())
            .background(if (highlight) cs.primary.copy(alpha = 0.12f) else Color.Transparent),
    ) {
        if (!first) HorizontalDivider(thickness = 1.dp, color = deckLine().copy(alpha = 0.6f))
        content()
    }
}

// ── A setting row ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingRow(
    def: SettingDef,
    source: SettingsSource,
    values: (String) -> String,
    level: SettingLevel,
    wide: Boolean,
    controlMax: Dp,
    onPick: (String) -> Unit,
    onCustom: () -> Unit,
    onHelp: () -> Unit,
    onReset: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val env = source.env
    val control = def.control(env, values)
    val value = source.value(def)
    val editable = source.editable(def)
    val disabledReason = if (editable) def.disabled(env, values) else null
    val enabled = editable && disabledReason == null
    val origin = source.origin(def)
    val baseline = source.baseline(def)?.let { labelFor(control, it) }
    // Toggles always sit on the right; wide rows put every compact control there too.
    val inline = control is Control.Toggle || (wide && control !is Control.Cores)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        if (inline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowInfo(def, level, origin, baseline, env.scope, onHelp, onReset, Modifier.weight(1f).padding(end = 16.dp))
                Box(modifier = Modifier.widthIn(max = if (control is Control.Toggle) 90.dp else controlMax)) {
                    ControlView(control, value, enabled, alignEnd = true, onPick = onPick, onCustom = onCustom)
                }
            }
        } else {
            RowInfo(def, level, origin, baseline, env.scope, onHelp, onReset, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            ControlView(control, value, enabled, alignEnd = false, onPick = onPick, onCustom = onCustom)
        }
        // Why a choice or the whole control is greyed out.
        val reasons = buildList {
            disabledReason?.let { add(it) }
            if (enabled) optionsOf(control).filter { it.disabledReason != null }.forEach { add("${it.label}: ${it.disabledReason}") }
        }
        reasons.forEach {
            Text(
                text = it,
                color = cs.onSurfaceVariant,
                fontSize = 12.5.sp,
                textAlign = if (inline) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun RowInfo(
    def: SettingDef,
    level: SettingLevel,
    origin: Origin,
    baselineLabel: String?,
    scope: EditorScope,
    onHelp: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.label, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.5.sp, modifier = Modifier.weight(1f, fill = false))
            if (def.help != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(30.dp)
                        .deckFocusRing(CircleShape, scaleTo = 1f)
                        .clip(CircleShape)
                        .clickable(onClick = onHelp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "What is this?", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        def.hint?.let {
            Text(it, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (level != SettingLevel.SIMPLE && def.tech != null) {
            Text(
                text = def.tech,
                color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        SourceLine(origin, baselineLabel, scope, onReset)
    }
}

@Composable
private fun SourceLine(origin: Origin, baselineLabel: String?, scope: EditorScope, onReset: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (origin) {
        Origin.NONE -> Unit
        Origin.DEFAULT -> SourcePill("Default")
        Origin.FROM_CONTAINER -> SourcePill("From container")
        Origin.FROM_APP -> SourcePill("App setting")
        Origin.CONTAINER_ONLY -> SourcePill("Container setting · change it in the container")
        Origin.SET_HERE -> Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        append(if (scope == EditorScope.GAME) "Set for this game" else "Set for this container")
                        if (baselineLabel != null) append(" · Default: ").append(baselineLabel)
                    },
                    color = cs.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            val shape = RoundedCornerShape(12.dp)
            Text(
                text = "Reset",
                color = cs.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .deckFocusRing(shape, scaleTo = 1f)
                    .clip(shape)
                    .border(1.5.dp, cs.primary, shape)
                    .clickable(onClick = onReset)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SourcePill(label: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = label,
        color = cs.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun optionsOf(control: Control): List<Opt> = when (control) {
    is Control.Segmented -> control.options
    is Control.Chips -> control.options
    is Control.Select -> control.options
    else -> emptyList()
}

/** A stored value as the row shows it: the option's label, On/Off, or the slider's display text. */
private fun labelFor(control: Control, v: String): String = when (control) {
    Control.Toggle -> if (v == "1") "On" else "Off"
    is Control.Segmented -> control.options.firstOrNull { it.value == v }?.label ?: v
    is Control.Chips -> control.options.firstOrNull { it.value == v }?.label ?: v
    is Control.Select -> control.options.firstOrNull { it.value == v }?.label ?: SettingsRegistry.prettyResolution(v)
    is Control.Slider -> v.toFloatOrNull()?.let(control.display) ?: v
    is Control.TextField -> v.ifEmpty { "empty" }
    Control.Cores -> if (v.isEmpty()) "all cores" else "cores $v"
}

// ── Controls ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlView(control: Control, value: String, enabled: Boolean, alignEnd: Boolean, onPick: (String) -> Unit, onCustom: () -> Unit) {
    when (control) {
        Control.Toggle -> Switch(
            checked = value == "1",
            onCheckedChange = { onPick(if (it) "1" else "0") },
            enabled = enabled,
            modifier = Modifier.deckFocusRing(RoundedCornerShape(20.dp), scaleTo = 1f),
        )
        is Control.Segmented -> SegmentedPills(control.options, value, enabled, alignEnd, onPick)
        is Control.Chips -> ChipPills(control.options, value, enabled, alignEnd, onPick)
        is Control.Select -> SelectBox(control, value, enabled, onPick, onCustom)
        is Control.Slider -> SliderControl(control, value, enabled, onPick)
        is Control.TextField -> TextControl(control, value, enabled, onPick)
        Control.Cores -> CoresControl(value, enabled, onPick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentedPills(options: List<Opt>, value: String, enabled: Boolean, alignEnd: Boolean, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val outer = RoundedCornerShape(14.dp)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(outer)
            .background(cs.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, deckLine().copy(alpha = 0.5f), outer)
            .padding(4.dp),
    ) {
        options.forEach { o ->
            val sel = o.value == value
            val ok = enabled && o.disabledReason == null
            val shape = RoundedCornerShape(11.dp)
            Text(
                text = o.label,
                color = when {
                    sel -> cs.onPrimary
                    ok -> cs.onSurfaceVariant
                    else -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 14.5.sp,
                maxLines = 1,
                modifier = Modifier
                    .deckFocusRing(shape, scaleTo = 1f)
                    .clip(shape)
                    .background(if (sel) cs.primary.copy(alpha = if (enabled) 1f else 0.5f) else Color.Transparent)
                    .clickable(enabled = ok && !sel) { onPick(o.value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipPills(options: List<Opt>, value: String, enabled: Boolean, alignEnd: Boolean, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val sel = o.value == value
            Pill(o.label, selected = sel, enabled = enabled && o.disabledReason == null) { if (!sel) onPick(o.value) }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Text(
        text = label,
        color = when {
            selected -> cs.primary
            enabled -> cs.onSurface
            else -> cs.onSurface.copy(alpha = 0.4f)
        },
        fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp,
        maxLines = 1,
        modifier = Modifier
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else cs.surfaceVariant.copy(alpha = 0.45f))
            .border(1.5.dp, if (selected) cs.primary else deckLine().copy(alpha = 0.6f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun SelectBox(control: Control.Select, value: String, enabled: Boolean, onPick: (String) -> Unit, onCustom: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    var open by remember { mutableStateOf(false) }
    val label = control.options.firstOrNull { it.value == value }?.label
        ?: value.ifEmpty { "—" }.let(SettingsRegistry::prettyResolution)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .deckFocusRing(shape, scaleTo = 1f)
                .clip(shape)
                .background(cs.surfaceVariant.copy(alpha = 0.55f))
                .border(1.dp, deckLine(), shape)
                .clickable(enabled = enabled) { open = true }
                .padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        ) {
            Text(
                text = label,
                color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.5f),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            control.options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label) },
                    enabled = o.disabledReason == null,
                    leadingIcon = {
                        if (o.value == value) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        if (o.value != value) onPick(o.value)
                    },
                )
            }
            control.customLabel?.let { custom ->
                DropdownMenuItem(
                    text = { Text(custom) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        open = false
                        onCustom()
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderControl(control: Control.Slider, value: String, enabled: Boolean, onPick: (String) -> Unit) {
    val committed = value.toFloatOrNull()?.coerceIn(control.min, control.max) ?: control.min
    var local by remember(value) { mutableFloatStateOf(committed) }
    fun snap(x: Float): Float {
        val n = ((x - control.min) / control.step).roundToInt()
        return (control.min + n * control.step).coerceIn(control.min, control.max)
    }
    fun commit(x: Float) {
        val s = snap(x)
        local = s
        val encoded = control.encode(s)
        if (encoded != value) onPick(encoded)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 260.dp)) {
        StepButton(Icons.Filled.Remove, "Less", enabled) { commit(local - control.step) }
        Slider(
            value = local,
            onValueChange = { local = snap(it) },
            onValueChangeFinished = { commit(local) },
            valueRange = control.min..control.max,
            enabled = enabled,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        StepButton(Icons.Filled.Add, "More", enabled) { commit(local + control.step) }
        Text(
            text = control.display(local),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 14.5.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 54.dp),
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .deckFocusRing(CircleShape, scaleTo = 1f)
            .clip(CircleShape)
            .background(cs.surfaceVariant.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TextControl(control: Control.TextField, value: String, enabled: Boolean, onPick: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    // Follow outside changes (Reset, Discard), but never overwrite what is being typed.
    LaunchedEffect(value) { if (value != text) text = value }
    val error = control.validate(text)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            if (control.validate(t) == null) onPick(t)
        },
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        placeholder = { Text(control.placeholder) },
        supportingText = if (error != null) { { Text(error) } } else null,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoresControl(value: String, enabled: Boolean, onPick: (String) -> Unit) {
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val on = value.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until cores) {
            val sel = i in on
            // The last core left can't be turned off: a game needs at least one.
            Pill("Core $i", selected = sel, enabled = enabled && !(sel && on.size == 1)) {
                val next = if (sel) on - i else on + i
                onPick(next.sorted().joinToString(","))
            }
        }
    }
}

// ── Hand-offs, search, the unsaved bar ──────────────────────────────────────────────────────────

@Composable
private fun HandoffRow(handoff: Handoff, onHandoff: (Handoff) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val opens = handoff.target != "none"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (opens) Modifier.deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f).clickable { onHandoff(handoff) } else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = if (opens) Icons.AutoMirrored.Filled.OpenInNew else Icons.Filled.Info,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(handoff.label, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(handoff.hint, color = cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (opens) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SearchPane(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    source: SettingsSource,
    values: (String) -> String,
    categories: List<SettingsCategory>,
    defs: List<SettingDef>,
    handoffs: Map<String, List<Handoff>>,
    level: SettingLevel,
    compact: Boolean,
    onPick: (SettingDef) -> Unit,
    onHandoff: (Handoff) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val catLabel = categories.associate { it.id to it.label }
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(vararg parts: String?): Boolean {
        val hay = parts.filterNotNull().joinToString(" ").lowercase()
        return words.isNotEmpty() && words.all { it in hay }
    }
    // Search covers every level; a result above the chosen level says so and switches level when opened.
    val results = defs.filter { matches(it.label, it.hint, it.tech, it.group, catLabel[it.category]) }
    val handoffResults = handoffs.flatMap { (cat, list) -> list.map { cat to it } }
        .filter { (cat, h) -> h.target != "none" && matches(h.label, h.hint, catLabel[cat]) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Column(modifier = modifier.padding(start = if (compact) 16.dp else 8.dp, end = if (compact) 16.dp else 24.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search every setting") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose),
                ) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
            },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().focusRequester(requester),
        )
        Spacer(Modifier.height(12.dp))
        when {
            words.isEmpty() -> Text("Type to search every setting, at every level.", color = cs.onSurfaceVariant, fontSize = 14.sp)
            results.isEmpty() && handoffResults.isEmpty() -> Text("Nothing matches \"$query\".", color = cs.onSurfaceVariant, fontSize = 14.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { def ->
                SearchResult(
                    title = def.label,
                    path = "${catLabel[def.category] ?: def.category} › ${def.group}",
                    value = labelFor(def.control(source.env, values), source.value(def)),
                    badge = if (def.level > level) def.level.label else null,
                    onClick = { onPick(def) },
                )
            }
            items(handoffResults, key = { "h:${it.first}:${it.second.label}" }) { (cat, h) ->
                SearchResult(title = h.label, path = "${catLabel[cat] ?: cat} › Classic editor", value = null, badge = null, onClick = { onHandoff(h) })
            }
        }
    }
}

@Composable
private fun SearchResult(title: String, path: String, value: String?, badge: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(shape, scaleTo = 1.01f)
            .clip(shape)
            .background(deckCardFill())
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f, fill = false))
                badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it,
                        color = cs.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(cs.primary.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(path, color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        value?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

@Composable
private fun UnsavedBar(saving: Boolean, compact: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val line = deckLine()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(deckGlass())
            .drawBehind { drawRect(line, topLeft = Offset.Zero, size = Size(size.width, 1f)) }
            .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(cs.primary))
        Spacer(Modifier.width(10.dp))
        Text("Unsaved changes", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDiscard, enabled = !saving) {
            Text("Discard", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSave,
            enabled = !saving,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary),
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Text("Save", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun CustomValueDialog(
    def: SettingDef,
    control: Control.Select,
    current: String,
    onDismiss: () -> Unit,
    onUse: (String) -> Unit,
) {
    var text by remember { mutableStateOf(if (control.options.none { it.value == current }) current else "") }
    val error = if (text.isBlank()) null else control.validateCustom?.invoke(text)
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onUse(control.normalizeCustom(text)) }, enabled = text.isNotBlank() && error == null) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(def.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                control.customHint?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    supportingText = if (error != null) { { Text(error) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
