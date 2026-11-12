package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.preference.PreferenceManager
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.UnsavedChangesDialog
import com.winlator.star.ui.simple.SIn
import com.winlator.star.ui.simple.SimpleDim
import com.winlator.star.ui.simple.SimpleGlyph
import com.winlator.star.ui.simple.SimpleInk
import com.winlator.star.ui.simple.SimpleLine
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimplePanelBrush
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong
import com.winlator.star.ui.simple.SimpleFlat
import com.winlator.star.ui.simple.keepVisible
import com.winlator.star.ui.simple.keepInViewWhen
import com.winlator.star.ui.simple.simpleDepth
import com.winlator.star.ui.simple.simpleRowFocus
import com.winlator.star.ui.simple.simpleInputOf
import com.winlator.star.ui.simple.simpleTap
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

// The Simple style's settings menus: the same editor as DeckSettingsEditor (same source, categories, rows, hand-offs and
// extra pages, so every value is stored exactly as before), drawn as a Simple menu in the shape of the chosen layout:
// Shelf = category tabs across the top over one panel of rows; Rail = a category list beside the rows; Focus = the
// category's title large, its rows, and the categories as a strip underneath. All input is handled here (like the
// Simple home): Up/Down move, Left/Right change a value, A changes or opens, X saves, Y shows more or fewer settings,
// L2/R2 change category, Select resets the focused setting, B goes back (asking first with unsaved changes).

private const val SIMPLE_LEVEL_PREF = "deck_settings_level"

/** One focusable line of a category's menu. */
private sealed interface MenuItem {
    class Setting(val def: SettingDef) : MenuItem
    class Link(val handoff: Handoff) : MenuItem
    /** Enter the category's own lists and tools (its CategoryExtra), drawn under the rows. */
    data object Extras : MenuItem
}

private enum class EZone { ROWS, CATS, EXTRAS }

@Composable
internal fun SimpleSettingsEditor(
    header: EditorHeader,
    source: SettingsSource,
    categories: List<SettingsCategory>,
    defs: List<SettingDef>,
    handoffs: Map<String, List<Handoff>>,
    onExit: (() -> Unit)?,
    onHandoff: (Handoff) -> Unit,
    onChainLink: ((ChainLink) -> Unit)?,
    headerExtra: (@Composable () -> Unit)?,
    extras: Map<String, CategoryExtra>,
) {
    val context = LocalContext.current
    val env = source.env
    val layout by AppThemeState.simpleLayout.collectAsState()
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    // Simple shows every setting: no Simple / Advanced / Expert.
    var level by remember { mutableStateOf(SettingLevel.EXPERT) }
    val changeLevel: (SettingLevel) -> Unit = { l ->
        level = l
        prefs.edit().putString(SIMPLE_LEVEL_PREF, l.name).apply()
    }

    // The same row selection as the Deck editor.
    val byId = remember(defs) { defs.associateBy { it.id } }
    val values: (String) -> String = { id -> byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: "" }
    fun applies(def: SettingDef) = source.shows(def) && env.world in def.worlds && def.visible(env, values)
    val worldHandoffs = remember(handoffs, env) {
        handoffs.mapValues { (_, list) -> list.filter { env.world in it.worlds } }.filterValues { it.isNotEmpty() }
    }
    LaunchedEffect(env) { env.loadAsync() }
    val rowsByCat = categories.associate { c -> c.id to defs.filter { it.category == c.id && applies(it) } }
    val shownByCat = rowsByCat.mapValues { (_, rows) -> rows.filter { it.level <= level } }
    val visibleCats = categories.filter { c ->
        shownByCat[c.id].orEmpty().isNotEmpty() || extras[c.id]?.let { it.level <= level } == true ||
            (level != SettingLevel.SIMPLE && worldHandoffs[c.id].orEmpty().isNotEmpty())
    }
    val tracksOverrides = env.scope == EditorScope.GAME || env.scope == EditorScope.CONTAINER
    fun overrides(catId: String): Int =
        if (tracksOverrides) rowsByCat[catId].orEmpty().count { source.editable(it) && source.origin(it) == Origin.SET_HERE } else 0

    var selected by rememberSaveable { mutableStateOf(categories.firstOrNull()?.id ?: "") }
    val current = visibleCats.firstOrNull { it.id == selected } ?: visibleCats.firstOrNull()
    val catIndex = visibleCats.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    var row by rememberSaveable { mutableIntStateOf(0) }
    var zone by remember { mutableStateOf(EZone.ROWS) }

    // The current category's menu: its rows, its hand-offs from Advanced up, and its lists and tools when it has them.
    val extra = current?.let { extras[it.id] }
    val hasExtras = extra != null && (extra.top().isNotEmpty() || extra.bottom().isNotEmpty())
    val items: List<MenuItem> = buildList {
        val cid = current?.id ?: return@buildList
        shownByCat[cid].orEmpty().forEach { add(MenuItem.Setting(it)) }
        if (level != SettingLevel.SIMPLE || shownByCat[cid].orEmpty().isEmpty()) worldHandoffs[cid].orEmpty().forEach { add(MenuItem.Link(it)) }
        if (hasExtras) add(MenuItem.Extras)
    }
    val rowNow = row.coerceIn(0, max(0, items.size - 1))

    // Dialogs and pickers.
    var pickerFor by remember { mutableStateOf<SettingDef?>(null) }
    var textFor by remember { mutableStateOf<SettingDef?>(null) }
    var coresFor by remember { mutableStateOf<SettingDef?>(null) }
    var checklistFor by remember { mutableStateOf<SettingDef?>(null) }
    var customFor by remember { mutableStateOf<SettingDef?>(null) }
    var helpFor by remember { mutableStateOf<SettingDef?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<SettingDef, String>?>(null) }
    var afterUnsaved by remember { mutableStateOf<(() -> Unit)?>(null) }
    var saving by remember { mutableStateOf(false) }
    val dialogOpen = pickerFor != null || textFor != null || coresFor != null || checklistFor != null ||
        customFor != null || helpFor != null || pendingConfirm != null || afterUnsaved != null

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
    val guard: (() -> Unit) -> Unit = { action -> if (source.dirty) afterUnsaved = action else action() }
    fun openCategory(id: String) {
        selected = id
        row = 0
        zone = EZone.ROWS
    }
    val handoff: (Handoff) -> Unit = { h ->
        if (h.target.startsWith(SettingsRegistry.TARGET_CATEGORY)) {
            val id = h.target.removePrefix(SettingsRegistry.TARGET_CATEGORY)
            if (visibleCats.none { it.id == id } && level == SettingLevel.SIMPLE) changeLevel(SettingLevel.ADVANCED)
            openCategory(id)
        } else guard { onHandoff(h) }
    }
    fun stepCategory(d: Int) {
        if (visibleCats.isEmpty()) return
        openCategory(visibleCats[(catIndex + d + visibleCats.size) % visibleCats.size].id)
    }

    /** Left/Right on a setting: the next or previous value it can take. */
    fun change(def: SettingDef, d: Int) {
        if (!source.editable(def) || def.disabled(env, values) != null) return
        val v = source.value(def)
        when (val c = def.control(env, values)) {
            Control.Toggle -> pick(def, if (v == "1") "0" else "1")
            is Control.Segmented, is Control.Chips, is Control.Select -> {
                val opts = optionsOf(c).filter { it.disabledReason == null }
                if (opts.isEmpty()) return
                val i = opts.indexOfFirst { it.value == v }
                val next = if (i < 0) (if (d > 0) 0 else opts.size - 1) else (i + d + opts.size) % opts.size
                pick(def, opts[next].value)
            }
            is Control.Slider -> {
                val f = v.toFloatOrNull() ?: c.min
                val n = (f + d * c.step).coerceIn(c.min, c.max)
                pick(def, c.encode(n))
            }
            else -> Unit
        }
    }
    /** A on a setting: a picker for long lists, a dialog for text, cores and checklists, a flip for the rest. */
    fun activate(def: SettingDef) {
        if (!source.editable(def) || def.disabled(env, values) != null) { helpFor = def; return }
        when (val c = def.control(env, values)) {
            Control.Toggle -> change(def, 1)
            is Control.Segmented, is Control.Chips -> { if (optionsOf(c).size > 3) { pickerFor = def } else change(def, 1) }
            is Control.Select -> pickerFor = def
            is Control.Slider -> change(def, 1)
            is Control.TextField -> textFor = def
            Control.Cores -> coresFor = def
            is Control.Checklist -> { if (!c.items.isNullOrEmpty()) checklistFor = def }
        }
    }
    fun activateItem(it: MenuItem) {
        when (it) {
            is MenuItem.Setting -> activate(it.def)
            is MenuItem.Link -> handoff(it.handoff)
            MenuItem.Extras -> zone = EZone.EXTRAS
        }
    }

    BackHandler(enabled = onExit != null) { onExit?.let { guard(it) } }

    // The shell: X saves (its legend says so too), L2/R2 step categories; this page's own hints go in the Simple frame.
    val deckActions = LocalDeckActions.current
    val dirty = source.dirty
    val stepNow by rememberUpdatedState { d: Int -> stepCategory(d) }
    DisposableEffect(deckActions) {
        val step: (Int) -> Unit = { stepNow(it) }
        deckActions.subTab.value = step
        deckActions.subTabLabel.value = "Category"
        onDispose { if (deckActions.subTab.value === step) deckActions.subTab.value = null }
    }
    val legend = buildList {
        add("B" to "Back")
        add("↔" to "Change")
        add("A" to "Open")
        if (dirty) add("X" to "Save")
        add("L2" to "Category")
        add("⧉" to "Reset")
    }
    DisposableEffect(deckActions, legend) {
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }

    // All input here, except while a dialog has the controller or the extra lists do.
    val focus = remember { FocusRequester() }
    val extrasFocus = remember { FocusRequester() }
    LaunchedEffect(zone, dialogOpen) {
        if (dialogOpen) return@LaunchedEffect
        delay(if (zone == EZone.EXTRAS) 160 else 60)
        runCatching { if (zone == EZone.EXTRAS) extrasFocus.requestFocus() else focus.requestFocus() }
    }
    val onKey: (SIn) -> Boolean = onKey@{ a ->
        val cur = items.getOrNull(rowNow)
        val def = (cur as? MenuItem.Setting)?.def
        when {
            a == SIn.B && zone == EZone.EXTRAS -> zone = EZone.ROWS
            zone == EZone.EXTRAS -> return@onKey false
            a == SIn.B -> onExit?.let { guard(it) }
            a == SIn.X -> if (dirty) save()
            a == SIn.Y -> Unit
            a == SIn.LT -> stepCategory(-1)
            a == SIn.RT -> stepCategory(1)
            a == SIn.SELECT -> if (def != null && source.origin(def) == Origin.SET_HERE) source.reset(def)
            zone == EZone.CATS -> when (a) {
                SIn.UP, SIn.LEFT -> stepCategory(-1)
                SIn.DOWN, SIn.RIGHT -> {
                    if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL && a == SIn.RIGHT) zone = EZone.ROWS else stepCategory(1)
                }
                SIn.A -> zone = EZone.ROWS
                else -> return@onKey a == SIn.START
            }
            else -> when (a) {
                SIn.UP -> {
                    if (rowNow == 0 && layout != AppThemeState.SIMPLE_LAYOUT_RAIL) Unit else row = max(0, rowNow - 1)
                }
                SIn.DOWN -> {
                    if (rowNow >= items.size - 1 && layout == AppThemeState.SIMPLE_LAYOUT_FOCUS) zone = EZone.CATS
                    else row = min(max(0, items.size - 1), rowNow + 1)
                }
                SIn.LEFT -> {
                    if (def != null && source.editable(def) && def.disabled(env, values) == null && isStepped(def.control(env, values))) change(def, -1)
                    else if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) zone = EZone.CATS
                }
                SIn.RIGHT -> if (def != null) change(def, 1)
                SIn.A -> cur?.let { m -> activateItem(m) }
                else -> return@onKey a == SIn.START
            }
        }
        true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (dialogOpen) return@onPreviewKeyEvent false
                val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                // L1/R1 belong to the shell (the Simple sections).
                if (a == SIn.LB || a == SIn.RB) return@onPreviewKeyEvent false
                onKey(a)
            },
    ) {
        val narrow = maxWidth < 700.dp
        val menu: @Composable (Modifier) -> Unit = { mod ->
            MenuPanel(
                items = items,
                row = rowNow,
                active = zone == EZone.ROWS,
                source = source,
                env = env,
                values = values,
                extra = extra,
                extrasZone = zone == EZone.EXTRAS,
                extrasFocus = extrasFocus,
                modifier = mod,
                onTap = { i -> zone = EZone.ROWS; if (i == rowNow) items.getOrNull(i)?.let { activateItem(it) } else row = i },
            )
        }
        when (layout) {
            AppThemeState.SIMPLE_LAYOUT_RAIL -> Row(Modifier.fillMaxSize()) {
                CategoryList(
                    cats = visibleCats, current = current, active = zone == EZone.CATS, overrides = { overrides(it) },
                    modifier = Modifier.width(if (narrow) 200.dp else 260.dp).fillMaxHeight(),
                    onPick = { openCategory(it) },
                )
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                    EditorTitle(header, current, level, dirty, onChainLink, big = false)
                    headerExtra?.invoke()
                    Spacer(Modifier.height(12.dp))
                    menu(Modifier.weight(1f).fillMaxWidth())
                }
            }
            AppThemeState.SIMPLE_LAYOUT_FOCUS -> Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 10.dp)) {
                EditorTitle(header, current, level, dirty, onChainLink, big = true)
                headerExtra?.invoke()
                Spacer(Modifier.height(14.dp))
                menu(Modifier.weight(1f).fillMaxWidth(if (narrow) 1f else 0.72f))
                Spacer(Modifier.height(10.dp))
                CategoryStrip(visibleCats, current, active = zone == EZone.CATS, overrides = { overrides(it) }, onPick = { openCategory(it) })
                Spacer(Modifier.height(6.dp))
            }
            else -> Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 6.dp)) {
                EditorTitle(header, current, level, dirty, onChainLink, big = false)
                headerExtra?.invoke()
                Spacer(Modifier.height(10.dp))
                CategoryTabs(visibleCats, current, overrides = { overrides(it) }, onPick = { openCategory(it) })
                Spacer(Modifier.height(12.dp))
                menu(Modifier.weight(1f).fillMaxWidth().widthIn(max = 1100.dp).align(Alignment.CenterHorizontally))
            }
        }
    }

    // ── Dialogs ──
    pickerFor?.let { def ->
        val c = def.control(env, values)
        val opts = optionsOf(c)
        SimplePicker(
            title = def.label,
            options = opts,
            current = source.value(def),
            customLabel = (c as? Control.Select)?.customLabel,
            onDismiss = { pickerFor = null },
            onPick = { v -> pickerFor = null; pick(def, v) },
            onCustom = { pickerFor = null; customFor = def },
        )
    }
    customFor?.let { def ->
        val control = def.control(env, values) as? Control.Select
        if (control == null) customFor = null
        else CustomValueDialog(def = def, control = control, current = source.value(def), onDismiss = { customFor = null }, onUse = { v -> customFor = null; pick(def, v) })
    }
    textFor?.let { def ->
        val control = def.control(env, values) as? Control.TextField
        if (control == null) textFor = null
        else OutlinedAlertDialog(
            onDismissRequest = { textFor = null },
            confirmButton = { TextButton(onClick = { textFor = null }) { Text("Done") } },
            title = { Text(def.label) },
            text = { TextControl(control, source.value(def), enabled = true) { v -> pick(def, v) } },
        )
    }
    coresFor?.let { def ->
        OutlinedAlertDialog(
            onDismissRequest = { coresFor = null },
            confirmButton = { TextButton(onClick = { coresFor = null }) { Text("Done") } },
            title = { Text(def.label) },
            text = { CoresControl(source.value(def), enabled = true) { v -> pick(def, v) } },
        )
    }
    checklistFor?.let { def ->
        val control = def.control(env, values) as? Control.Checklist
        val items = control?.items
        if (control == null || items == null) checklistFor = null
        else {
            val value = source.value(def)
            ChecklistDialog(
                title = control.title,
                all = items,
                off = value.split(',').filter { it.isNotEmpty() }.toSet(),
                onDismiss = { checklistFor = null },
                onDone = { picked ->
                    checklistFor = null
                    val next = items.filter { it in picked }.joinToString(",")
                    if (next != value) pick(def, next)
                },
            )
        }
    }
    helpFor?.let { def ->
        OutlinedAlertDialog(
            onDismissRequest = { helpFor = null },
            confirmButton = { TextButton(onClick = { helpFor = null }) { Text("OK") } },
            title = { Text(def.label) },
            text = {
                Text(
                    listOfNotNull(
                        def.disabled(env, values),
                        if (!source.editable(def)) "This is a container setting; change it in the container's settings." else null,
                        def.help ?: def.hint,
                    ).joinToString("\n\n"),
                )
            },
        )
    }
    pendingConfirm?.let { (def, v) ->
        val ask = def.confirm?.invoke(v)
        if (ask == null) {
            pendingConfirm = null
            source.set(def, v)
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
    afterUnsaved?.let { action ->
        UnsavedChangesDialog(
            onSave = { afterUnsaved = null; save(after = action) },
            onDiscard = { afterUnsaved = null; source.discard(); action() },
            onKeepEditing = { afterUnsaved = null },
        )
    }
}

/** Left/Right change these in place; the rest open a dialog with A. */
internal fun isStepped(c: Control): Boolean =
    c == Control.Toggle || c is Control.Segmented || c is Control.Chips || c is Control.Select || c is Control.Slider

private fun originLabel(o: Origin, scope: EditorScope): String? = when (o) {
    Origin.SET_HERE -> if (scope == EditorScope.GAME) "Set for this game" else "Changed"
    Origin.FROM_CONTAINER -> "From the container"
    Origin.FROM_APP -> "From the app"
    Origin.CONTAINER_ONLY -> "Container setting"
    else -> null
}

// ───────────────────────────── pieces ─────────────────────────────

/** The editor's title: what is being edited, the category, how many settings are shown, unsaved changes. */
@Composable
private fun EditorTitle(
    header: EditorHeader,
    current: SettingsCategory?,
    level: SettingLevel,
    dirty: Boolean,
    onChainLink: ((ChainLink) -> Unit)?,
    big: Boolean,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(header.overline.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
            if (header.chain.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                header.chain.forEachIndexed { i, link ->
                    if (i > 0) Text("  ›  ", color = SimpleDim, fontSize = 11.sp)
                    Text(
                        link.label,
                        color = if (link.onClick == null) SimpleInk else SimpleMuted,
                        fontSize = 11.sp,
                        modifier = if (link.onClick != null) Modifier.simpleTap { onChainLink?.invoke(link) ?: link.onClick.invoke() } else Modifier,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        // A fixed height: the Unsaved tag appearing must not move the page.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(if (big) 50.dp else 36.dp)) {
            Text(
                if (big) current?.label ?: header.title else header.title,
                color = SimpleStrong,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (big) 38.sp else 26.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (dirty) {
                Spacer(Modifier.width(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.background(accent.copy(alpha = 0.2f), SimpleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text("Unsaved", color = SimpleStrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    SimpleGlyph("X")
                }
            }
        }
        val sub = if (big) header.title else current?.desc
        if (!sub.isNullOrBlank()) Text(sub, color = SimpleMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Shelf: the categories as tabs between L2 and R2, with how many settings each has changed. */
@Composable
private fun CategoryTabs(cats: List<SettingsCategory>, current: SettingsCategory?, overrides: (String) -> Int, onPick: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        SimpleGlyph("L2")
        Spacer(Modifier.width(8.dp))
        cats.forEach { c ->
            val on = c.id == current?.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .then(if (on) Modifier.simpleDepth().background(accent.copy(alpha = 0.24f), SimpleShape) else Modifier)
                    .border(1.dp, if (on) accent else Color.Transparent, SimpleShape)
                    .simpleTap { onPick(c.id) }
                    .keepInViewWhen(on)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(c.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(c.label, color = if (on) SimpleStrong else SimpleMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val n = overrides(c.id)
                if (n > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(accent, SimpleShape).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
        }
        Spacer(Modifier.width(2.dp))
        SimpleGlyph("R2")
    }
}

/** Rail: the categories down the left, lit while the controller is on them. */
@Composable
private fun CategoryList(
    cats: List<SettingsCategory>,
    current: SettingsCategory?,
    active: Boolean,
    overrides: (String) -> Int,
    modifier: Modifier,
    onPick: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val state = remember { LazyListState() }
    val idx = cats.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(idx) { state.keepVisible(idx) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(10.dp),
        modifier = modifier.simpleDepth(high = true, shape = RectangleShape).background(SimplePanelBrush),
    ) {
        itemsIndexed(cats, key = { _, c -> c.id }) { _, c ->
            val on = c.id == current?.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(
                        if (on) Modifier
                            .background(if (active) accent.copy(alpha = 0.3f) else accent.copy(alpha = 0.16f), SimpleShape)
                            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                        else Modifier
                    )
                    .simpleTap { onPick(c.id) }
                    .padding(horizontal = 14.dp),
            ) {
                Icon(c.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(c.label, color = if (on) SimpleStrong else SimpleInk.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val n = overrides(c.id)
                if (n > 0) Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(accent, SimpleShape).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

/** Focus: the categories as a strip of tiles under the menu. */
@Composable
private fun CategoryStrip(cats: List<SettingsCategory>, current: SettingsCategory?, active: Boolean, overrides: (String) -> Int, onPick: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val state = remember { LazyListState() }
    val idx = cats.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(idx) { state.keepVisible(idx) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(86.dp)) {
        itemsIndexed(cats, key = { _, c -> c.id }) { _, c ->
            val on = c.id == current?.id
            val scale by animateFloatAsState(if (on && active) 1.08f else 1f, tween(200), label = "simpleCatTile")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(118.dp)
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .simpleDepth(high = on)
                    .background(if (on) accent.copy(alpha = 0.3f) else Color(0xB30C0E13), SimpleShape)
                    .border(if (on && active) 2.dp else 1.dp, if (on) accent else SimpleLine, SimpleShape)
                    .simpleTap { onPick(c.id) }
                    .padding(8.dp),
            ) {
                Icon(c.icon, contentDescription = null, tint = if (on) SimpleStrong else SimpleMuted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Text(c.label, color = if (on) SimpleStrong else SimpleMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = overrides(c.id)
                if (n > 0) Text("$n changed", color = accent, fontSize = 10.sp)
            }
        }
    }
}

/** The category's menu: its settings (grouped), hand-offs, and an entry into its own lists and tools, which follow below. */
@Composable
private fun MenuPanel(
    items: List<MenuItem>,
    row: Int,
    active: Boolean,
    source: SettingsSource,
    env: SettingsEnv,
    values: (String) -> String,
    extra: CategoryExtra?,
    extrasZone: Boolean,
    extrasFocus: FocusRequester,
    modifier: Modifier,
    onTap: (Int) -> Unit,
) {
    val state = remember(items.size) { LazyListState() }
    // The list index of each row: a group title sits in the list before the first row of its group.
    val listIndex = remember(items) {
        var last: String? = null
        var n = 0
        items.map { m ->
            val g = (m as? MenuItem.Setting)?.def?.group
            if (g != null && g != last && g.isNotBlank()) n++
            if (g != null) last = g
            n++
        }
    }
    LaunchedEffect(row, items.size) { if (items.isNotEmpty() && !extrasZone) state.keepVisible(listIndex.getOrElse(row) { row }) }
    // Entering the lists and tools: bring them on screen so they can take the focus.
    LaunchedEffect(extrasZone) { if (extrasZone && extra != null) state.scrollToItem(max(0, state.layoutInfo.totalItemsCount - 1)) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 40.dp),
        modifier = modifier
            .simpleDepth(high = true)
            .background(SimplePanelBrush, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape),
    ) {
        var lastGroup: String? = null
        items.forEachIndexed { i, it ->
            val group = (it as? MenuItem.Setting)?.def?.group
            if (group != null && group != lastGroup && group.isNotBlank()) {
                val g = group
                item(key = "group-$i-$g") {
                    Text(g.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                }
            }
            if (group != null) lastGroup = group
            item(key = "row-$i") {
                when (it) {
                    is MenuItem.Setting -> SettingLine(it.def, source, env, values, focused = i == row, active = active) { onTap(i) }
                    is MenuItem.Link -> LinkLine(it.handoff.label, it.handoff.hint, focused = i == row, active = active) { onTap(i) }
                    MenuItem.Extras -> LinkLine("Lists and tools", "Below this menu · B comes back here", focused = i == row, active = active) { onTap(i) }
                }
            }
        }
        if (extra != null) {
            item(key = "extras") {
                val top = extra.top()
                val bottom = extra.bottom()
                if (top.isNotEmpty() || bottom.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .then(if (extrasZone) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), SimpleShape) else Modifier)
                            .padding(8.dp)
                            .focusRequester(extrasFocus)
                            .focusable(),
                    ) {
                        (top + bottom).forEach { e -> Box(Modifier.fillMaxWidth()) { e.content() } }
                    }
                }
            }
        }
    }
}

/** One setting: its name, where its value comes from, and the value with ‹ › when Left/Right change it. */
@Composable
internal fun SettingLine(
    def: SettingDef,
    source: SettingsSource,
    env: SettingsEnv,
    values: (String) -> String,
    focused: Boolean,
    active: Boolean,
    onTap: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val control = def.control(env, values)
    val value = source.value(def)
    val disabled = def.disabled(env, values)
    val editable = source.editable(def) && disabled == null
    val origin = originLabel(source.origin(def), env.scope)
    val note = def.note(env, values)
    val label = runCatching { labelFor(control, value) }.getOrDefault(value)
    val shift by animateFloatAsState(if (focused && active) 4f else 0f, tween(160), label = "simpleSettingShift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .graphicsLayer { translationX = shift * density }
            .simpleRowFocus(focused, active)
            .simpleTap(onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(L(def.label), color = if (editable) (if (focused) SimpleStrong else SimpleInk.copy(alpha = 0.9f)) else SimpleDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                disabled != null -> L(disabled)
                focused && note != null -> L(note)
                focused && def.hint != null -> L(def.hint)
                else -> origin
            }
            if (sub != null) {
                Text(
                    sub,
                    color = if (source.origin(def) == Origin.SET_HERE && sub == origin) accent else SimpleDim,
                    fontSize = 12.sp,
                    maxLines = if (focused) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        val stepped = isStepped(control) && editable
        if (stepped) Text("‹", color = if (focused) accent else Color.Transparent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            L(label),
            color = if (editable) SimpleStrong else SimpleMuted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 90.dp, max = 300.dp).padding(horizontal = 10.dp),
        )
        if (stepped) Text("›", color = if (focused) accent else Color.Transparent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        else if (editable) Text("›", color = SimpleMuted, fontSize = 16.sp)
    }
}

@Composable
internal fun LinkLine(title: String, subtitle: String?, focused: Boolean, active: Boolean, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .simpleRowFocus(focused, active)
            .simpleTap(onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(L(title), color = if (focused) SimpleStrong else SimpleInk.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(L(subtitle), color = SimpleDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = if (focused) accent else SimpleMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** A Simple list of a setting's choices; the current one is focused first. A picks, B closes. */
@Composable
internal fun SimplePicker(
    title: String,
    options: List<Opt>,
    current: String,
    customLabel: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCustom: () -> Unit,
    /** Shown as the last line, "+ Download from source", when set. */
    onDownload: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val start = options.indexOfFirst { it.value == current }.coerceAtLeast(0)
    val requesters = remember(options.size) { List(options.size + 2) { FocusRequester() } }
    // No options: the line under them takes the focus.
    val first = if (options.isNotEmpty()) start else if (customLabel != null) options.size else options.size + 1
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { requesters[first].requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(L(title), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            val state = remember { LazyListState(firstVisibleItemIndex = max(0, start - 3)) }
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(options) { i, o ->
                    var f by remember { mutableStateOf(false) }
                    val enabled = o.disabledReason == null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .focusRequester(requesters[i])
                            .onFocusChanged { f = it.isFocused }
                            .then(if (f) Modifier.background(accent.copy(alpha = 0.3f), SimpleShape).drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                            .clickable(enabled = enabled) { onPick(o.value) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(L(o.label), color = if (enabled) SimpleStrong else SimpleDim, fontSize = 15.sp)
                            if (o.disabledReason != null) Text(L(o.disabledReason), color = SimpleDim, fontSize = 12.sp)
                        }
                        if (o.value == current) Text("✓", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (customLabel != null) {
                    item {
                        var f by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                                .focusRequester(requesters[options.size])
                                .onFocusChanged { f = it.isFocused }
                                .then(if (f) Modifier.background(accent.copy(alpha = 0.3f), SimpleShape) else Modifier)
                                .clickable { onCustom() }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text(customLabel, color = SimpleInk, fontSize = 15.sp) }
                    }
                }
                if (onDownload != null) {
                    item {
                        var f by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                                .focusRequester(requesters[options.size + 1])
                                .onFocusChanged { f = it.isFocused }
                                .then(if (f) Modifier.background(accent.copy(alpha = 0.3f), SimpleShape).drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                                .clickable { onDownload() }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text(L("+ Download from source"), color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SimpleGlyph("A"); Spacer(Modifier.width(4.dp)); Text("Pick", color = SimpleMuted, fontSize = 12.sp)
                Spacer(Modifier.width(14.dp))
                SimpleGlyph("B"); Spacer(Modifier.width(4.dp)); Text("Close", color = SimpleMuted, fontSize = 12.sp)
            }
        }
    }
}
