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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.ui.LocalXmbHintInset
import com.winlator.star.ui.deck.DeckInk
import com.winlator.star.ui.deck.LocalXmbGroup
import com.winlator.star.ui.deck.ScreenScale
import com.winlator.star.ui.deck.XmbEditState
import com.winlator.star.ui.deck.XmbEditTapCatcher
import com.winlator.star.ui.deck.XmbGroupItem
import com.winlator.star.ui.deck.XmbRailState
import com.winlator.star.ui.deck.deckFocusRingInk
import com.winlator.star.ui.deck.deckInset
import com.winlator.star.ui.deck.deckLineAt
import com.winlator.star.ui.deck.isDeckCompact
import com.winlator.star.ui.deck.DeckGlyph
import com.winlator.star.ui.deck.GlyphKind
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.DeckEntryFocus
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.deckCardFill
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckGlass
import com.winlator.star.ui.deck.deckLine
import com.winlator.star.ui.deck.rememberScreenScale
import com.winlator.star.ui.deck.rememberXmbEdit
import com.winlator.star.ui.deck.rememberXmbFocusGroup
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.deck.rememberXmbRailState
import com.winlator.star.ui.deck.xmbDoneActions
import com.winlator.star.ui.deck.xmbEditField
import com.winlator.star.ui.deck.xmbGlassCard
import com.winlator.star.ui.deck.xmbGlassSlice
import com.winlator.star.ui.deck.xmbInner
import com.winlator.star.ui.deck.xmbLandingIndex
import com.winlator.star.ui.deck.xmbPageFrost
import com.winlator.star.ui.deck.xmbRailGroup
import com.winlator.star.ui.deck.xmbRailItem
import com.winlator.star.ui.deck.xmbSelectedInk
import com.winlator.star.ui.deck.xmbSkipFocus
import com.winlator.star.ui.deck.xmbSliderNudge
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

/** One item a category page shows that is not a setting row. [key] is unique on the page. */
internal class PageExtra(val key: String, val content: @Composable () -> Unit)

/**
 * What a category shows besides its setting rows: items above them ([top]) and below them ([bottom]),
 * built while the page draws so they follow the state they read. A category with an extra is listed
 * from [level] up (every level by default), as well as wherever its rows are. With [searchKey] set, Y
 * on that page scrolls to that item and calls [focusSearch] instead of searching every setting.
 */
internal class CategoryExtra(
    val top: () -> List<PageExtra> = { emptyList() },
    val bottom: () -> List<PageExtra> = { emptyList() },
    val searchKey: String? = null,
    val focusSearch: () -> Unit = {},
    val level: SettingLevel = SettingLevel.SIMPLE,
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
 * [extras] adds content that is not a setting to a category's page (the Windows components lists).
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
    extras: Map<String, CategoryExtra> = emptyMap(),
) {
    val context = LocalContext.current
    val env = source.env
    val compact = isDeckCompact(LocalConfiguration.current)
    // XMB two-pane layout: no title block, the mode switch and the section header share one slim line (see SlimEditorTop / SlimPageLine).
    // Classic, the plain Deck and the XMB phone layout keep the big header.
    val slim = rememberXmbHome() && !compact
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
    // The worlds never cross: a row or hand-off of another world is not offered here at all.
    fun applies(def: SettingDef) = source.shows(def) && env.world in def.worlds && def.visible(env, values)
    val worldHandoffs = remember(handoffs, env) {
        handoffs.mapValues { (_, list) -> list.filter { env.world in it.worlds } }.filterValues { it.isNotEmpty() }
    }
    LaunchedEffect(env) { env.loadAsync() }

    // Per category: every row that applies in this editor, and the ones shown at the chosen level.
    val rowsByCat = categories.associate { c -> c.id to defs.filter { it.category == c.id && applies(it) } }
    val shownByCat = rowsByCat.mapValues { (_, rows) -> rows.filter { it.level <= level } }
    val visibleCats = categories.filter { c ->
        shownByCat[c.id].orEmpty().isNotEmpty() || extras[c.id]?.let { it.level <= level } == true ||
            (level != SettingLevel.SIMPLE && worldHandoffs[c.id].orEmpty().isNotEmpty())
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
    // The category whose own search field Y asked for (its CategoryExtra.searchKey).
    var extraSearchFor by remember { mutableStateOf<String?>(null) }
    // Bumped when a hand-off opens another category, so the controller lands on it in the list.
    var listFocus by remember { mutableStateOf(0) }

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

    // A hand-off to another category of this editor opens it here (raising the level when it has nothing at this one).
    val handoff: (Handoff) -> Unit = { h ->
        if (h.target.startsWith(SettingsRegistry.TARGET_CATEGORY)) {
            val id = h.target.removePrefix(SettingsRegistry.TARGET_CATEGORY)
            if (visibleCats.none { it.id == id } && level == SettingLevel.SIMPLE) changeLevel(SettingLevel.ADVANCED)
            openCategory(id)
            listFocus++
        } else {
            guard { onHandoff(h) }
        }
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
    val searchNow by rememberUpdatedState {
        // A page with a search of its own (Windows components) takes Y; everywhere else it searches every setting.
        val own = current?.takeIf { query == null && (!compact || phonePage) && extras[it.id]?.searchKey != null }
        if (own != null) extraSearchFor = own.id else query = query ?: ""
    }
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
        if (slim) {
            // Search is Y (and the shell's floating Search hint): both go through `deckActions.search` above, so the top line has no button.
            SlimEditorTop(
                header = header,
                onBack = onExit?.let { exit -> { guard(exit) } },
                onChainLink = { link -> onChainLink?.let { cb -> guard { cb(link) } } },
                extra = headerExtra,
            )
        } else {
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
        }
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
                        handoffs = worldHandoffs,
                        level = level,
                        compact = compact,
                        onPick = { def ->
                            if (def.level > level) changeLevel(def.level)
                            openCategory(def.category)
                            jumpTo = def.id
                            flash = def.id
                        },
                        onHandoff = handoff,
                        modifier = mod,
                    )
                } else if (current != null) {
                    val over = overrides(current.id)
                    CategoryPage(
                        category = current,
                        rows = shownByCat[current.id].orEmpty(),
                        handoffs = worldHandoffs[current.id].orEmpty(),
                        source = source,
                        values = values,
                        level = level,
                        overrideCount = over.size,
                        compact = compact,
                        slim = slim,
                        jumpTo = jumpTo,
                        onJumpDone = { jumpTo = null },
                        flash = flash,
                        onPick = pick,
                        onCustom = { customFor = it },
                        onHelp = { helpFor = it },
                        onResetAll = { over.forEach { source.reset(it) } },
                        onHandoff = handoff,
                        onBack = if (compact) ({ phonePage = false }) else null,
                        extra = extras[current.id],
                        searchRequested = extraSearchFor == current.id,
                        onSearchDone = { extraSearchFor = null },
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
                val listWidth = (LocalConfiguration.current.screenWidthDp * 0.28f).dp.coerceIn(180.dp, 300.dp)
                Row(modifier = Modifier.fillMaxSize()) {
                    CategoryList(
                        categories = visibleCats,
                        current = current?.id,
                        badge = { overrides(it).size },
                        compact = false,
                        onSelect = { selected = it.id; query = null },
                        focusKey = listFocus,
                        modifier = Modifier.width(listWidth).fillMaxHeight(),
                    )
                    if (slim) {
                        // XMB: the section's own line (icon, name, hint, Reset, the mode switch) is pinned above its rows.
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val shown = current
                            if (query == null && shown != null) {
                                val over = overrides(shown.id)
                                SlimPageLine(
                                    category = shown,
                                    overrideCount = over.size,
                                    onResetAll = { over.forEach { source.reset(it) } },
                                    level = level,
                                    onLevel = changeLevel,
                                )
                            }
                            page(Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        page(Modifier.weight(1f).fillMaxHeight())
                    }
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
private fun SquareButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 46.dp, iconSize: Dp = 22.dp) {
    val shape = RoundedCornerShape(if (size < 40.dp) 11.dp else 14.dp)
    DeckInk { item, cs ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .clip(shape)
                .background(deckCardFill())
                .border(1.dp, deckLine(), shape)
                .clickable(onClick = onClick),
        ) {
            Icon(icon, contentDescription = description, tint = cs.onSurface, modifier = Modifier.size(iconSize))
        }
    }
}

/** What the slim XMB lines grow with on a bigger screen: the base size, times the screen factor when that is above 1 (never below the base). */
private fun ScreenScale.up(base: Float): Float = base * maxOf(factor, 1f)

/**
 * The XMB two-pane layout's top line: the page's own name and where it inherits from, in one slim wrapping row (back button, name
 * with its small kicker, "Inherits from" chain, the Wine layer, a host's [extra]). The app Settings page has none of those (it is a
 * tab's root, no back, no chain), so it has no top line at all and the panes start straight under the shell's bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlimEditorTop(
    header: EditorHeader,
    onBack: (() -> Unit)?,
    onChainLink: (ChainLink) -> Unit,
    extra: (@Composable () -> Unit)?,
) {
    val named = onBack != null
    if (!named && header.chain.isEmpty() && header.meta == null && extra == null) return
    val cs = MaterialTheme.colorScheme
    val ss = rememberScreenScale()
    val rowH = ss.up(34f).dp
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 2.dp),
    ) {
        if (onBack != null) {
            SquareButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = rowH, iconSize = 20.dp)
        }
        if (named) {
            Column(modifier = Modifier.height(rowH).widthIn(max = 300.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    text = header.overline.uppercase(),
                    color = cs.primary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = ss.up(9.5f).sp,
                    lineHeight = ss.up(11f).sp,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = header.title,
                    color = cs.onSurface,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = ss.up(17f).sp,
                    lineHeight = ss.up(20f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (header.chain.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(rowH).padding(start = if (named) 8.dp else 0.dp)) {
                Icon(Icons.Filled.AccountTree, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Inherits from", color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = ss.up(12.5f).sp, maxLines = 1)
            }
        }
        header.chain.forEachIndexed { i, link ->
            if (i > 0) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(rowH)) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(rowH)) {
                ChainChip(link, onClick = { onChainLink(link) }, slim = true)
            }
        }
        header.meta?.let {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.height(rowH).padding(start = 6.dp)) {
                Text(it, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = ss.up(12.5f).sp, maxLines = 1)
            }
        }
        extra?.let { content ->
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = rowH)) { content() }
        }
    }
}

/**
 * The XMB two-pane layout's section line, pinned above the section's rows: a small icon, the name, its hint (one line, cut with an
 * ellipsis where it does not fit), the small Reset pill when the section has overrides, and the compact Simple / Advanced / Expert
 * switch at the right. It replaces the big icon tile and title and the old header row, so the rows get the room.
 */
@Composable
private fun SlimPageLine(
    category: SettingsCategory,
    overrideCount: Int,
    onResetAll: () -> Unit,
    level: SettingLevel,
    onLevel: (SettingLevel) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ss = rememberScreenScale()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ss.up(40f).dp)
            .padding(start = 18.dp, end = 24.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Icon(category.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(ss.up(20f).dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = category.label,
            color = cs.onSurface,
            fontFamily = SoraFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = ss.up(16f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = category.desc,
            color = cs.onSurfaceVariant,
            fontSize = ss.up(12.5f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (overrideCount > 0) {
            Spacer(Modifier.width(8.dp))
            ResetAllButton(overrideCount, onResetAll, slim = true)
        }
        Spacer(Modifier.width(10.dp))
        LevelSwitch(level, onLevel, slim = true)
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
internal fun DeckSegmented(options: List<Opt>, value: String, onPick: (String) -> Unit, slim: Boolean = false) {
    SegmentedPills(options, value, enabled = true, alignEnd = false, onPick = onPick, slim = slim)
}

@Composable
private fun LevelSwitch(level: SettingLevel, onLevel: (SettingLevel) -> Unit, slim: Boolean = false) {
    SegmentedPills(
        options = SettingLevel.entries.map { Opt(it.name, it.label) },
        value = level.name,
        enabled = true,
        alignEnd = false,
        onPick = { onLevel(SettingLevel.valueOf(it)) },
        slim = slim,
    )
}

@Composable
private fun SearchButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .deckFocusRingInk(shape, item, scaleTo = 1f)
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
}

@Composable
private fun ChainChip(link: ChainLink, onClick: () -> Unit, slim: Boolean = false) {
    val chipH = if (slim) 30.dp else 38.dp
    val shape = RoundedCornerShape(chipH / 2)
    val here = link.onClick == null
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (slim) 6.dp else 8.dp),
            modifier = Modifier
                .height(chipH)
                .then(if (here) Modifier else Modifier.deckFocusRingInk(shape, item, scaleTo = 1f))
                .clip(shape)
                .background(if (here) cs.primary.copy(alpha = 0.16f) else Color.Transparent)
                .then(if (here) Modifier else Modifier.border(1.dp, deckLine(), shape).clickable(onClick = onClick))
                .padding(horizontal = if (slim) 11.dp else 14.dp),
        ) {
            if (!here) Icon(link.icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(if (slim) 16.dp else 18.dp))
            Text(
                text = link.label,
                color = if (here) cs.primary else cs.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = if (slim) 13.sp else 14.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
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
    focusKey: Int = 0,
    modifier: Modifier,
) {
    // XMB two-pane layout: Up / Down on the list shows the category it lands on, and Left back from the page lands on the shown one
    // (XmbRail.kt); the shown category then carries the rail's requester instead of its own.
    val xmbRail: XmbRailState? = if (!compact && rememberXmbHome()) rememberXmbRailState() else null
    val requester = xmbRail?.selectedRequester ?: remember { FocusRequester() }
    // The controller starts on the list, on the category being shown (and comes back to it when [focusKey] changes).
    DeckEntryFocus(focusKey) { requester.requestFocus() }
    LazyColumn(
        modifier = if (xmbRail != null) modifier.xmbRailGroup(xmbRail) else modifier,
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
                // In the two-pane layout, moving the controller over a category shows it straight away. XMB does it through the
                // rail (only a move inside the list selects, entering it from the page does not).
                onFocused = if (compact || xmbRail != null) null else ({ if (c.id != current) onSelect(c) }),
                rail = xmbRail,
                modifier = if (c.id == current && xmbRail == null) Modifier.focusRequester(requester) else Modifier,
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
    rail: XmbRailState? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    // XMB: the shown category's text keeps its accent when the outline moves on into the page.
    val shownInk = xmbSelectedInk()
    DeckInk { item, cs ->
        val accent = cs.primary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .xmbRailItem(rail, selected, onClick)
                .deckFocusRingInk(shape, item, scaleTo = 1.02f, onFocused = onFocused)
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
                color = if (selected) (shownInk ?: cs.onSurface) else cs.onSurfaceVariant,
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
}

// ── Category page ───────────────────────────────────────────────────────────────────────────────

private sealed interface PageItem {
    val key: String
}
private object HeaderItem : PageItem { override val key = "header" }
private class ResetAllItem(val count: Int) : PageItem { override val key = "resetAll" }
private class GroupItem(val label: String, val tight: Boolean = false) : PageItem { override val key = "group:$label" }
private class RowItem(val def: SettingDef, val first: Boolean, val last: Boolean) : PageItem { override val key = "row:${def.id}" }
private class HandoffItem(val handoff: Handoff, val first: Boolean, val last: Boolean) : PageItem { override val key = "handoff:${handoff.label}" }
private class ExtraItem(val extra: PageExtra) : PageItem { override val key = "extra:${extra.key}" }

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
    slim: Boolean,
    jumpTo: String?,
    onJumpDone: () -> Unit,
    flash: String?,
    onPick: (SettingDef, String) -> Unit,
    onCustom: (SettingDef) -> Unit,
    onHelp: (SettingDef) -> Unit,
    onResetAll: () -> Unit,
    onHandoff: (Handoff) -> Unit,
    onBack: (() -> Unit)?,
    extra: CategoryExtra?,
    searchRequested: Boolean,
    onSearchDone: () -> Unit,
    modifier: Modifier,
) {
    val pageItems = buildList<PageItem> {
        // XMB two-pane: the header and the Reset button are the pinned slim line above this list (SlimPageLine), not items of it.
        if (!slim) {
            add(HeaderItem)
            if (overrideCount > 0) add(ResetAllItem(overrideCount))
        }
        extra?.top?.invoke()?.forEach { add(ExtraItem(it)) }
        rows.groupBy { it.group }.forEach { (group, defs) ->
            add(GroupItem(group, tight = slim && isEmpty()))
            defs.forEachIndexed { i, d -> add(RowItem(d, first = i == 0, last = i == defs.lastIndex)) }
        }
        extra?.bottom?.invoke()?.forEach { add(ExtraItem(it)) }
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
    // Y on a page with its own search: bring the field into view, then focus it.
    LaunchedEffect(searchRequested, category.id) {
        if (!searchRequested) return@LaunchedEffect
        val key = extra?.searchKey
        val index = if (key == null) -1 else pageItems.indexOfFirst { it.key == "extra:$key" }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { extra?.focusSearch?.invoke() }
        }
        onSearchDone()
    }
    // XMB: the group cards cast a shadow ~10 dp to the sides, which the list clips at its edge, so it keeps 12 dp there.
    val sideRoom = if (rememberXmbHome()) 12.dp else 8.dp
    BoxWithConstraints(modifier = modifier) {
        val wideRows = maxWidth >= 560.dp
        val controlMax = maxWidth * 0.52f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = if (compact) 16.dp else sideRoom, end = if (compact) 16.dp else 24.dp, top = 4.dp, bottom = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pageItems, key = { it.key }) { item ->
                when (item) {
                    is HeaderItem -> PageHeader(category, onBack)
                    is ResetAllItem -> ResetAllButton(item.count, onResetAll)
                    is GroupItem -> GroupLabel(item.label, top = if (item.tight) 4.dp else 16.dp)
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
                    is ExtraItem -> item.extra.content()
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
private fun ResetAllButton(count: Int, onClick: () -> Unit, slim: Boolean = false) {
    val shape = RoundedCornerShape(if (slim) 12.dp else 14.dp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = if (slim) 0.dp else 6.dp)) {
        DeckInk { item, cs ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (slim) 6.dp else 10.dp),
                modifier = Modifier
                    .deckFocusRingInk(shape, item, scaleTo = 1f)
                    .clip(shape)
                    .border(1.dp, deckLine(), shape)
                    .clickable(onClick = onClick)
                    .padding(horizontal = if (slim) 10.dp else 16.dp, vertical = if (slim) 5.dp else 10.dp),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(if (slim) 15.dp else 20.dp))
                Text(
                    text = if (count == 1) "Reset 1 override" else "Reset $count overrides",
                    color = cs.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (slim) 12.sp else 15.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun GroupLabel(label: String, top: Dp = 16.dp) {
    Text(
        text = label.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.5.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 6.dp, top = top, bottom = 8.dp),
    )
}

/** One slice of a rounded group card: rows stack into a single card, with a hairline between them. */
@Composable
internal fun GroupCard(first: Boolean, last: Boolean, highlight: Boolean, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The glass card, a slice at a time: its sheen and edge light are on the top of the whole group card, so only its
            // first slice carries them; every slice paints its part of the shadow round the card.
            .xmbGlassSlice(shape, first, last)
            .background(if (highlight) cs.primary.copy(alpha = 0.12f) else Color.Transparent),
    ) {
        if (!first) HorizontalDivider(thickness = 1.dp, color = deckLineAt(0.6f))
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

    // XMB interface: the whole row is ONE stop of the D-pad and the controls inside it are reached through it (see
    // XmbFocusGroup.kt). A on the row: flips a toggle, opens a select's menu or a checklist's dialog, starts typing in a text
    // field; with pills and sliders it enters the row (Left / Right move inside, A picks, B comes back out). Right on the row goes
    // to its "?" and Reset buttons. Everywhere else the row is the plain column of controls it always was.
    val xmb = rememberXmbHome()
    val group = rememberXmbFocusGroup()
    val selectOpen = remember { mutableStateOf(false) }
    val checklistOpen = remember { mutableStateOf(false) }
    val edit = if (xmb && control is Control.TextField) rememberXmbEdit() else null
    val hasUtility = def.help != null || origin == Origin.SET_HERE
    val utility = remember { FocusRequester() }
    var utilityTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(group, utilityTick) {
        if (utilityTick > 0) {
            // The same one-frame wait as the group's own entering: the buttons' canFocus has to be applied first.
            withFrameNanos { }
            if (runCatching { utility.requestFocus() }.isFailure) group.entered = false
        }
    }
    val checklistItems = (control as? Control.Checklist)?.items
    val onActivate: (() -> Unit)? = when {
        !enabled -> ({})
        control == Control.Toggle -> ({ onPick(if (value == "1") "0" else "1") })
        control is Control.Select -> ({ selectOpen.value = true })
        control is Control.Checklist -> ({ if (!checklistItems.isNullOrEmpty()) checklistOpen.value = true })
        edit != null -> ({ edit.start() })
        else -> null
    }
    // A reset takes its button with it: focus goes back to the row before the button leaves.
    val resetNow: () -> Unit = {
        onReset()
        if (xmb && group.entered) group.exit()
    }
    // The scheme outside the row's accent ink, for what pops out of the row (the select's menu).
    val plainScheme = MaterialTheme.colorScheme

    val body: @Composable () -> Unit = {
        val cs = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (xmb) 12.dp else 18.dp, vertical = if (xmb) 11.dp else 14.dp),
        ) {
            if (inline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowInfo(def, level, origin, baseline, env.scope, onHelp, resetNow, utility, Modifier.weight(1f).padding(end = 16.dp))
                    Box(modifier = Modifier.widthIn(max = if (control is Control.Toggle) 90.dp else controlMax)) {
                        ControlView(control, value, enabled, alignEnd = true, onPick = onPick, onCustom = onCustom, selectOpen = selectOpen, checklistOpen = checklistOpen, edit = edit, menuScheme = plainScheme)
                    }
                }
            } else {
                RowInfo(def, level, origin, baseline, env.scope, onHelp, resetNow, utility, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                ControlView(control, value, enabled, alignEnd = false, onPick = onPick, onCustom = onCustom, selectOpen = selectOpen, checklistOpen = checklistOpen, edit = edit, menuScheme = plainScheme)
            }
            // Why a choice or the whole control is greyed out, and anything worth saying about the value.
            val reasons = buildList {
                disabledReason?.let { add(it) }
                def.note(env, values)?.let { add(it) }
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

    if (xmb) {
        val rowKeys = Modifier
            .then(if (edit != null) Modifier.focusRequester(edit.outer) else Modifier)
            .onPreviewKeyEvent { k ->
                when {
                    // B while typing ends it and leaves the row highlighted (the XMB keyboard window ends typing itself).
                    edit != null && edit.editing && (k.key == Key.ButtonB || k.key == Key.Back || k.key == Key.Escape) -> {
                        if (k.type == KeyEventType.KeyUp) edit.stop()
                        true
                    }
                    // Right on the row: its "?" and Reset buttons.
                    hasUtility && group.onOuter && !group.entered && k.key == Key.DirectionRight -> {
                        if (k.type == KeyEventType.KeyDown && k.nativeKeyEvent.repeatCount == 0) {
                            group.entered = true
                            utilityTick++
                        }
                        true
                    }
                    else -> false
                }
            }
        XmbGroupItem(
            group,
            RoundedCornerShape(14.dp),
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).then(rowKeys),
            onActivate,
        ) { body() }
    } else {
        body()
    }

    // The checklist's dialog is a window of its own: composed here, outside the row, so it neither takes the row's accent ink
    // nor finds the row's inner buttons around it.
    val items = checklistItems
    if (checklistOpen.value && control is Control.Checklist && items != null) {
        ChecklistDialog(
            title = control.title,
            all = items,
            off = value.split(',').filter { it.isNotEmpty() }.toSet(),
            onDismiss = { checklistOpen.value = false },
            // Only the listed items are kept, as the classic picker does: one no longer offered is dropped.
            onDone = { picked ->
                checklistOpen.value = false
                val next = items.filter { it in picked }.joinToString(",")
                if (next != value) onPick(next)
            },
        )
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
    utility: FocusRequester,
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
                        // XMB: reached with Right from the row (an inner button of its group); the "?" is the first of them.
                        .xmbInner()
                        .focusRequester(utility)
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
        // The requester lands Right-from-the-row on the "?" when there is one, else on Reset (never both: one request, one target).
        SourceLine(origin, baselineLabel, scope, onReset, if (def.help == null) utility else null)
    }
}

@Composable
private fun SourceLine(origin: Origin, baselineLabel: String?, scope: EditorScope, onReset: () -> Unit, utility: FocusRequester?) {
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
                    .xmbInner()
                    .then(if (utility != null) Modifier.focusRequester(utility) else Modifier)
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
            .background(deckInset(0.6f))
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
    is Control.Checklist -> v.split(',').count { it.isNotEmpty() }.let { if (it == 0) "all on" else "$it off" }
}

// ── Controls ────────────────────────────────────────────────────────────────────────────────────

/**
 * The focus ring of a control inside a setting row. In the XMB interface the row is the stop (see SettingRow), so a control that
 * needs no D-pad focus of its own is skipped by it (taps still work); everywhere else it carries the usual ring.
 */
@Composable
private fun Modifier.controlRing(shape: Shape): Modifier =
    if (LocalXmbGroup.current != null) this.xmbSkipFocus() else this.deckFocusRing(shape, scaleTo = 1f)

/**
 * The switch on the XMB glass: its off state is the stock one (an `outline` thumb on a `surfaceContainerHighest` track), and on the
 * glass those are a near-black thumb on a frosted track, which almost vanishes. There the off thumb is the secondary text colour on
 * an inset track with the soft line. Elsewhere the stock colours, as before.
 */
@Composable
private fun deckSwitchColors(): SwitchColors {
    if (xmbPageFrost() == null) return SwitchDefaults.colors()
    return SwitchDefaults.colors(
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = deckInset(),
        uncheckedBorderColor = deckLine(),
    )
}

/** The outlined text fields on the XMB glass: an inset fill and a visible resting border (the stock `outline` is as dark as the card). */
@Composable
private fun deckFieldColors(): TextFieldColors {
    if (xmbPageFrost() == null) return OutlinedTextFieldDefaults.colors()
    val fill = deckInset()
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill,
        unfocusedContainerColor = fill,
        disabledContainerColor = fill,
        unfocusedBorderColor = deckLine(),
    )
}

@Composable
private fun ControlView(
    control: Control,
    value: String,
    enabled: Boolean,
    alignEnd: Boolean,
    onPick: (String) -> Unit,
    onCustom: () -> Unit,
    selectOpen: MutableState<Boolean>,
    checklistOpen: MutableState<Boolean>,
    edit: XmbEditState?,
    menuScheme: ColorScheme,
) {
    when (control) {
        Control.Toggle -> Switch(
            checked = value == "1",
            onCheckedChange = { onPick(if (it) "1" else "0") },
            enabled = enabled,
            colors = deckSwitchColors(),
            modifier = Modifier.controlRing(RoundedCornerShape(20.dp)),
        )
        is Control.Segmented -> SegmentedPills(control.options, value, enabled, alignEnd, onPick)
        is Control.Chips -> ChipPills(control.options, value, enabled, alignEnd, onPick)
        is Control.Select -> SelectBox(control, value, enabled, selectOpen, onPick, onCustom, menuScheme)
        is Control.Slider -> SliderControl(control, value, enabled, onPick)
        is Control.TextField -> TextControl(control, value, enabled, onPick, edit)
        Control.Cores -> CoresControl(value, enabled, onPick)
        is Control.Checklist -> ChecklistControl(control, value, enabled, checklistOpen)
    }
}

/**
 * A segmented choice. The track is an inset (see [deckInset]: on the XMB glass a darker translucent groove, not the near-white the
 * frost surface turned into when its alpha was replaced) with a soft line; the selected pill keeps the accent. [slim] is the compact
 * form of the XMB top lines: smaller type and padding, one row that never wraps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentedPills(options: List<Opt>, value: String, enabled: Boolean, alignEnd: Boolean, onPick: (String) -> Unit, slim: Boolean = false) {
    val outer = RoundedCornerShape(if (slim) 11.dp else 14.dp)
    val ss = rememberScreenScale()
    val trackFill = deckInset()
    val trackLine = deckLineAt(0.5f)
    // Inside an XMB row the pills are its inner buttons: A on the row lands on `landing`, and the selected pill is a stop too
    // (so the pad can rest on the current value and walk away from it).
    val inRow = LocalXmbGroup.current != null
    val landing = xmbLandingIndex(options.map { it.value == value }, options.map { enabled && it.disabledReason == null })
    val track = Modifier
        .clip(outer)
        .background(trackFill)
        .border(1.dp, trackLine, outer)
        .padding(if (slim) 2.dp else 4.dp)
    val pills: @Composable () -> Unit = {
        options.forEachIndexed { i, o ->
            val sel = o.value == value
            val ok = enabled && o.disabledReason == null
            val shape = RoundedCornerShape(if (slim) 9.dp else 11.dp)
            DeckInk { item, ink ->
                Text(
                    text = o.label,
                    color = when {
                        sel -> ink.onPrimary
                        ok -> ink.onSurfaceVariant
                        else -> ink.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = if (slim) ss.up(12f).sp else 14.5.sp,
                    lineHeight = if (slim) ss.up(16f).sp else TextUnit.Unspecified,
                    maxLines = 1,
                    modifier = Modifier
                        .deckFocusRingInk(shape, item, scaleTo = 1f)
                        .xmbInner(first = i == landing)
                        .clip(shape)
                        .background(if (sel) ink.primary.copy(alpha = if (enabled) 1f else 0.5f) else Color.Transparent)
                        .clickable(enabled = ok && (!sel || inRow)) { if (!sel) onPick(o.value) }
                        .padding(horizontal = if (slim) 9.dp else 14.dp, vertical = if (slim) 6.dp else 8.dp),
                )
            }
        }
    }
    if (slim) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = track) { pills() }
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp, if (alignEnd) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = track,
        ) { pills() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipPills(options: List<Opt>, value: String, enabled: Boolean, alignEnd: Boolean, onPick: (String) -> Unit) {
    val landing = xmbLandingIndex(options.map { it.value == value }, options.map { enabled && it.disabledReason == null })
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, o ->
            val sel = o.value == value
            Pill(o.label, selected = sel, enabled = enabled && o.disabledReason == null, first = i == landing) { if (!sel) onPick(o.value) }
        }
    }
}

/** A rounded choice. [first]: in an XMB row, the one A on the row lands on (see [xmbLandingIndex]). */
@Composable
private fun Pill(label: String, selected: Boolean, enabled: Boolean, first: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    DeckInk { item, cs ->
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
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .xmbInner(first = first)
                .clip(shape)
                .background(if (selected) cs.primary.copy(alpha = 0.14f) else deckInset(0.45f))
                .border(1.5.dp, if (selected) cs.primary else deckLineAt(0.6f), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SelectBox(
    control: Control.Select,
    value: String,
    enabled: Boolean,
    openState: MutableState<Boolean>,
    onPick: (String) -> Unit,
    onCustom: () -> Unit,
    menuScheme: ColorScheme,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    // Lifted to the row: A on an XMB row opens the menu.
    var open by openState
    // The menu is a popup of its own; inside an XMB row it would otherwise carry the row's accent ink.
    val inRow = LocalXmbGroup.current != null
    val label = control.options.firstOrNull { it.value == value }?.label
        ?: value.ifEmpty { "—" }.let(SettingsRegistry::prettyResolution)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .controlRing(shape)
                .clip(shape)
                .background(deckInset())
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
        val items: @Composable () -> Unit = {
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            if (inRow) {
                MaterialTheme(colorScheme = menuScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) { items() }
            } else {
                items()
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
    // Inside an XMB row the slider is its one inner stop: A on the row enters it, Left / Right nudge it by a step, B leaves; the
    // step buttons are tap-only there.
    val inRow = LocalXmbGroup.current != null
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 260.dp)) {
        val sliderPad = Modifier.weight(1f).padding(horizontal = 4.dp)
        StepButton(Icons.Filled.Remove, "Less", enabled) { commit(local - control.step) }
        Slider(
            value = local,
            onValueChange = { local = snap(it) },
            onValueChangeFinished = { commit(local) },
            valueRange = control.min..control.max,
            enabled = enabled,
            modifier = if (!inRow) sliderPad else sliderPad
                .deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f)
                .xmbInner(first = true)
                .onPreviewKeyEvent { k ->
                    val dir = when (k.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    if (dir == 0 || !enabled) {
                        false
                    } else {
                        // Every press and repeat moves it a step; the key-up is eaten too.
                        if (k.type == KeyEventType.KeyDown) commit(xmbSliderNudge(local, dir, control.step, control.min, control.max))
                        true
                    }
                },
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
            .controlRing(CircleShape)
            .clip(CircleShape)
            .background(deckInset())
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TextControl(control: Control.TextField, value: String, enabled: Boolean, onPick: (String) -> Unit, edit: XmbEditState?) {
    var text by remember { mutableStateOf(value) }
    // Follow outside changes (Reset, Discard), but never overwrite what is being typed.
    LaunchedEffect(value) { if (value != text) text = value }
    val error = control.validate(text)
    // XMB: the row is the stop and A on it starts typing (the row's `edit`); the field takes focus only while typing and a
    // tap on it starts typing too. Elsewhere it is the plain field.
    val field: @Composable (Modifier) -> Unit = { m ->
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
            colors = deckFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = if (edit != null) xmbDoneActions { edit.stop() } else KeyboardActions.Default,
            modifier = m,
        )
    }
    if (edit == null) {
        field(Modifier.fillMaxWidth())
    } else {
        Box(Modifier.fillMaxWidth()) {
            field(Modifier.fillMaxWidth().xmbEditField(edit))
            XmbEditTapCatcher(edit)
        }
    }
}

/**
 * A Checklist row: how many items are on, and A opens the list to switch them. The dialog itself is composed by the row
 * ([SettingRow]), outside its accent ink and its group; [open] is the row's.
 */
@Composable
private fun ChecklistControl(control: Control.Checklist, value: String, enabled: Boolean, open: MutableState<Boolean>) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val items = control.items
    val off = value.split(',').filter { it.isNotEmpty() }.toSet()
    val usable = enabled && !items.isNullOrEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .controlRing(shape)
            .clip(shape)
            .background(deckInset())
            .border(1.dp, deckLine(), shape)
            .clickable(enabled = usable) { open.value = true }
            .padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Text(
            text = when {
                items == null -> control.waiting
                items.isEmpty() -> control.empty
                else -> "${items.count { it !in off }} of ${items.size} on"
            },
            color = if (usable) cs.onSurface else cs.onSurface.copy(alpha = 0.5f),
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Every item with a tick; the whole line is the focus target and A switches it. OK hands back the ones switched off. */
@Composable
private fun ChecklistDialog(title: String, all: List<String>, off: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var picked by remember(all, off) { mutableStateOf(all.filter { it in off }.toSet()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "${all.size - picked.size} of ${all.size} on. Games don't see an extension that is off.",
                    color = cs.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item(key = "all") {
                        ChecklistLine("All on", checked = picked.isEmpty(), modifier = Modifier.focusRequester(first)) { picked = emptySet() }
                    }
                    items(all, key = { it }) { ext ->
                        ChecklistLine(ext, checked = ext !in picked) { picked = if (ext in picked) picked - ext else picked + ext }
                    }
                }
            }
        },
    )
}

@Composable
private fun ChecklistLine(label: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .clickable(onClick = onToggle)
            .padding(end = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoresControl(value: String, enabled: Boolean, onPick: (String) -> Unit) {
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val on = value.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    // The last core left can't be turned off: a game needs at least one.
    val landing = xmbLandingIndex((0 until cores).map { it in on }, (0 until cores).map { enabled && !(it in on && on.size == 1) })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until cores) {
            val sel = i in on
            Pill("Core $i", selected = sel, enabled = enabled && !(sel && on.size == 1), first = i == landing) {
                val next = if (sel) on - i else on + i
                onPick(next.sorted().joinToString(","))
            }
        }
    }
}

// ── Hand-offs, search, the unsaved bar ──────────────────────────────────────────────────────────

@Composable
private fun HandoffRow(handoff: Handoff, onHandoff: (Handoff) -> Unit) {
    val opens = handoff.target != "none"
    val here = handoff.target.startsWith(SettingsRegistry.TARGET_CATEGORY)
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (opens) Modifier.deckFocusRingInk(RoundedCornerShape(12.dp), item, scaleTo = 1f).clickable { onHandoff(handoff) } else Modifier)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = when {
                    here -> Icons.AutoMirrored.Filled.ArrowForward
                    opens -> Icons.AutoMirrored.Filled.OpenInNew
                    else -> Icons.Filled.Info
                },
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
            colors = deckFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().focusRequester(requester),
        )
        Spacer(Modifier.height(12.dp))
        when {
            words.isEmpty() -> Text("Type to search every setting, at every level.", color = cs.onSurfaceVariant, fontSize = 14.sp)
            results.isEmpty() && handoffResults.isEmpty() -> Text("Nothing matches \"$query\".", color = cs.onSurfaceVariant, fontSize = 14.sp)
        }
        // XMB: the result cards cast a shadow ~10 dp round them, so they sit 12 dp apart and inside the list's edges.
        val xmbList = rememberXmbHome()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(if (xmbList) 12.dp else 8.dp),
            contentPadding = PaddingValues(start = if (xmbList) 12.dp else 0.dp, end = if (xmbList) 12.dp else 0.dp, top = if (xmbList) 8.dp else 0.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
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
                val where = if (h.target.startsWith(SettingsRegistry.TARGET_CATEGORY)) "Opens its page" else "Classic editor"
                SearchResult(title = h.label, path = "${catLabel[cat] ?: cat} › $where", value = null, badge = null, onClick = { onHandoff(h) })
            }
        }
    }
}

@Composable
private fun SearchResult(title: String, path: String, value: String?, badge: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    DeckInk { item, cs ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .deckFocusRingInk(shape, item, scaleTo = 1.01f)
                .xmbGlassCard(shape)
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
}

@Composable
private fun UnsavedBar(saving: Boolean, compact: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val line = deckLine()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // XMB mode: the bar sits above the floating Back / Search hints that take the screen's bottom edge (0 elsewhere).
            .padding(bottom = LocalXmbHintInset.current)
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
