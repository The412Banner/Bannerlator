package com.winlator.star.ui.deck.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.SIn
import com.winlator.star.ui.simple.SimpleDim
import com.winlator.star.ui.simple.SimpleInk
import com.winlator.star.ui.simple.SimpleLine
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimplePanelBrush
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong
import com.winlator.star.ui.simple.keepInViewWhen
import com.winlator.star.ui.simple.keepVisible
import com.winlator.star.ui.simple.simpleDepth
import com.winlator.star.ui.simple.simpleInputOf
import com.winlator.star.ui.simple.simpleTap
import com.winlator.star.ui.simple.tabFill
import kotlinx.coroutines.delay
import kotlin.math.max

// Lab's app Settings, laid out like the Classic Settings screen: every setting in one scrolling list, the categories as
// section headings (landscape also lists them beside it to jump to). Logs is a tab of its own (the Log Manager).
// The rows, their storage and the hand-offs are the settings editor's (AppSource, AppSettingsRegistry): each change
// applies at once, as before.

/** App-settings sections that get a tab of their own beside Settings and Logs, in this order. */
private val TAB_CATEGORIES = listOf("app.performance", "app.saves")

private sealed interface OLine { val cat: SettingsCategory }
private class OHead(override val cat: SettingsCategory) : OLine
private class OSet(override val cat: SettingsCategory, val def: SettingDef) : OLine
private class OLink(override val cat: SettingsCategory, val h: Handoff) : OLine

@Composable
internal fun SimpleOneMenuSettings(
    header: EditorHeader,
    source: SettingsSource,
    categories: List<SettingsCategory>,
    defs: List<SettingDef>,
    handoffs: Map<String, List<Handoff>>,
    onHandoff: (Handoff) -> Unit,
) {
    val context = LocalContext.current
    val env = source.env
    val byId = remember(defs) { defs.associateBy { it.id } }
    val values: (String) -> String = { id -> byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: "" }
    fun applies(def: SettingDef) = source.shows(def) && env.world in def.worlds && def.visible(env, values)
    LaunchedEffect(env) { env.loadAsync() }

    // Tabs: Settings (every other section), then the sections that have a tab of their own, then Logs.
    val tabCats = remember(categories) { TAB_CATEGORIES.mapNotNull { id -> categories.firstOrNull { it.id == id } } }
    val tabCount = tabCats.size + 2
    val logsTab = tabCount - 1
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabCat = tabCats.getOrNull(tab - 1)
    val logsTarget = AppSettingsRegistry.TARGET_LOGS
    val lines: List<OLine> = buildList {
        val shown = when {
            tabCat != null -> listOf(tabCat)
            else -> categories.filter { c -> tabCats.none { it.id == c.id } }
        }
        for (c in shown) {
            val rows = defs.filter { it.category == c.id && applies(it) }
            // The Log Manager is the Logs tab now, not a row.
            val links = handoffs[c.id].orEmpty().filter { env.world in it.worlds && it.target != logsTarget }
            if (rows.isEmpty() && links.isEmpty()) continue
            add(OHead(c))
            rows.forEach { add(OSet(c, it)) }
            links.forEach { add(OLink(c, it)) }
        }
    }
    fun canFocus(l: OLine?) = l != null && l !is OHead
    var row by rememberSaveable { mutableIntStateOf(1) }
    // A new tab starts at its first setting.
    LaunchedEffect(tab) { row = lines.indexOfFirst { it !is OHead }.coerceAtLeast(0) }
    val rowNow = row.coerceIn(0, max(0, lines.size - 1)).let { r -> if (canFocus(lines.getOrNull(r))) r else lines.indexOfFirst { canFocus(it) }.coerceAtLeast(0) }
    var inSections by remember { mutableStateOf(false) }
    val catNow = lines.getOrNull(rowNow)?.cat
    val cats = remember(lines) { lines.filterIsInstance<OHead>().map { it.cat } }

    // Dialogs.
    var pickerFor by remember { mutableStateOf<SettingDef?>(null) }
    var textFor by remember { mutableStateOf<SettingDef?>(null) }
    var coresFor by remember { mutableStateOf<SettingDef?>(null) }
    var checklistFor by remember { mutableStateOf<SettingDef?>(null) }
    var customFor by remember { mutableStateOf<SettingDef?>(null) }
    var helpFor by remember { mutableStateOf<SettingDef?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<SettingDef, String>?>(null) }
    val dialogOpen = pickerFor != null || textFor != null || coresFor != null || checklistFor != null ||
        customFor != null || helpFor != null || pendingConfirm != null

    val pick: (SettingDef, String) -> Unit = { def, v ->
        val ask = def.confirm?.invoke(v)
        if (ask != null && source.value(def) != v) pendingConfirm = def to v else source.set(def, v)
    }
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
            is Control.Slider -> pick(def, c.encode(((v.toFloatOrNull() ?: c.min) + d * c.step).coerceIn(c.min, c.max)))
            else -> Unit
        }
    }
    fun activate(l: OLine) {
        when (l) {
            is OHead -> Unit
            is OLink -> onHandoff(l.h)
            is OSet -> {
                val def = l.def
                if (!source.editable(def) || def.disabled(env, values) != null) { helpFor = def; return }
                when (val c = def.control(env, values)) {
                    Control.Toggle -> change(def, 1)
                    is Control.Segmented, is Control.Chips -> { if (optionsOf(c).size > 3) pickerFor = def else change(def, 1) }
                    is Control.Select -> pickerFor = def
                    is Control.Slider -> change(def, 1)
                    is Control.TextField -> textFor = def
                    Control.Cores -> coresFor = def
                    is Control.Checklist -> { if (!c.items.isNullOrEmpty()) checklistFor = def }
                }
            }
        }
    }
    fun jumpTo(cat: SettingsCategory) {
        val i = lines.indexOfFirst { it.cat.id == cat.id && it !is OHead }
        if (i >= 0) row = i
    }
    fun stepSection(d: Int) {
        val i = cats.indexOfFirst { it.id == catNow?.id }
        cats.getOrNull((i + d).coerceIn(0, cats.size - 1))?.let { jumpTo(it) }
    }

    // L2/R2 switch Settings / Logs, through the shell as well (they then work from any focus).
    val deckActions = LocalDeckActions.current
    val tabNow by rememberUpdatedState(tab)
    DisposableEffect(deckActions, tabCount) {
        val step: (Int) -> Unit = { d -> tab = (tabNow + d + tabCount) % tabCount }
        deckActions.subTab.value = step
        deckActions.subTabLabel.value = L("Tab")
        onDispose { if (deckActions.subTab.value === step) deckActions.subTab.value = null }
    }
    val cur = lines.getOrNull(rowNow)
    val curDef = (cur as? OSet)?.def
    val legend = buildList {
        if (tab != logsTab) {
            if (curDef != null && isStepped(curDef.control(env, values)) && source.editable(curDef)) add("◀▶" to L("Change"))
            add("A" to L("Open"))
        }
        add("L2 R2" to L("Tab"))
    }
    DisposableEffect(deckActions, legend) {
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(tab, dialogOpen) {
        if (dialogOpen || tab == logsTab) return@LaunchedEffect
        delay(60)
        runCatching { focus.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxWidth >= 700.dp
        val onKey: (SIn) -> Boolean = onKey@{ a ->
            if (a == SIn.LT || a == SIn.RT) { tab = (tab + (if (a == SIn.LT) -1 else 1) + tabCount) % tabCount; return@onKey true }
            if (tab == logsTab) return@onKey false
            when {
                inSections -> when (a) {
                    SIn.UP -> stepSection(-1)
                    SIn.DOWN -> stepSection(1)
                    SIn.RIGHT, SIn.A -> inSections = false
                    SIn.B -> inSections = false
                    else -> return@onKey false
                }
                else -> when (a) {
                    SIn.UP -> { var i = rowNow - 1; while (i >= 0 && !canFocus(lines.getOrNull(i))) i--; if (i >= 0) row = i }
                    SIn.DOWN -> { var i = rowNow + 1; while (i < lines.size && !canFocus(lines.getOrNull(i))) i++; if (i < lines.size) row = i }
                    SIn.LEFT, SIn.RIGHT -> {
                        val d = if (a == SIn.LEFT) -1 else 1
                        if (curDef != null && source.editable(curDef) && curDef.disabled(env, values) == null && isStepped(curDef.control(env, values))) change(curDef, d)
                        else if (a == SIn.LEFT && landscape && tab == 0) inSections = true
                    }
                    SIn.A -> cur?.let { activate(it) }
                    SIn.SELECT -> { if (curDef != null && source.origin(curDef) == Origin.SET_HERE) source.reset(curDef) }
                    else -> return@onKey false
                }
            }
            true
        }
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (dialogOpen) return@onPreviewKeyEvent false
                    val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                    onKey(a)
                }
                .padding(start = if (landscape) 28.dp else 12.dp, end = if (landscape) 28.dp else 12.dp, top = 4.dp),
        ) {
            Text(L(header.overline).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
            Text(L(header.title), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                OneTab(L("Settings"), Icons.Filled.Settings, tab == 0) { tab = 0 }
                tabCats.forEachIndexed { i, c ->
                    Spacer(Modifier.width(6.dp))
                    OneTab(L(c.label), c.icon, tab == i + 1) { tab = i + 1 }
                }
                Spacer(Modifier.width(6.dp))
                OneTab(L("Logs"), Icons.Filled.Description, tab == logsTab) { tab = logsTab }
            }
            Spacer(Modifier.height(12.dp))
            if (tab == logsTab) {
                Box(Modifier.weight(1f).fillMaxWidth().simpleDepth(high = true).background(SimplePanelBrush, SimpleShape).border(1.dp, SimpleLine, SimpleShape)) {
                    LogManagerScreen(onClose = { tab = 0 })
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // The section list only on the long Settings tab; the other tabs are one section each.
                    if (landscape && tab == 0) {
                        SectionList(cats, catNow, active = inSections, modifier = Modifier.width(230.dp).fillMaxHeight()) { c -> inSections = false; jumpTo(c) }
                    }
                    OneList(lines, rowNow, active = !inSections, source = source, env = env, values = values, modifier = Modifier.weight(1f).fillMaxHeight()) { i ->
                        inSections = false
                        if (i == rowNow) lines.getOrNull(i)?.let { activate(it) } else row = i
                    }
                }
            }
        }
    }

    // ── Dialogs (as in the other Simple editors) ──
    pickerFor?.let { def ->
        val c = def.control(env, values)
        SimplePicker(
            title = def.label,
            options = optionsOf(c),
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
            confirmButton = { TextButton(onClick = { textFor = null }) { Text(L("Done")) } },
            title = { Text(L(def.label)) },
            text = { TextControl(control, source.value(def), enabled = true) { v -> pick(def, v) } },
        )
    }
    coresFor?.let { def ->
        OutlinedAlertDialog(
            onDismissRequest = { coresFor = null },
            confirmButton = { TextButton(onClick = { coresFor = null }) { Text(L("Done")) } },
            title = { Text(L(def.label)) },
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
            title = { Text(L(def.label)) },
            text = { Text(listOfNotNull(def.disabled(env, values), def.help ?: def.hint).map { L(it) }.joinToString("\n\n")) },
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
                confirmButton = { TextButton(onClick = { pendingConfirm = null; source.set(def, v) }) { Text(L(ask.confirmLabel)) } },
                dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text(L("Cancel")) } },
                title = { Text(L(ask.title)) },
                text = { Text(L(ask.body)) },
            )
        }
    }
}

@Composable
private fun OneTab(label: String, icon: ImageVector, on: Boolean, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .tabFill(on, accent)
            .simpleTap(onTap)
            .keepInViewWhen(on)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (on) SimpleStrong else SimpleMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Landscape: the sections beside the list; the current one lit, a tap (or Up/Down while here) jumps the list to it. */
@Composable
private fun SectionList(cats: List<SettingsCategory>, current: SettingsCategory?, active: Boolean, modifier: Modifier, onPick: (SettingsCategory) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val state = remember { LazyListState() }
    val idx = cats.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(idx) { state.keepVisible(idx) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(8.dp),
        modifier = modifier.simpleDepth(high = true, shape = RectangleShape).background(SimplePanelBrush, SimpleShape).border(1.dp, SimpleLine, SimpleShape),
    ) {
        itemsIndexed(cats, key = { _, c -> c.id }) { _, c ->
            val on = c.id == current?.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .then(
                        if (on) Modifier
                            .background(accent.copy(alpha = if (active) 0.3f else 0.14f), SimpleShape)
                            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                        else Modifier
                    )
                    .simpleTap { onPick(c) }
                    .padding(horizontal = 12.dp),
            ) {
                Icon(c.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(L(c.label), color = if (on) SimpleStrong else SimpleInk.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Every setting in one list, each section under its heading. */
@Composable
private fun OneList(
    lines: List<OLine>,
    row: Int,
    active: Boolean,
    source: SettingsSource,
    env: SettingsEnv,
    values: (String) -> String,
    modifier: Modifier,
    onTap: (Int) -> Unit,
) {
    val state = remember { LazyListState() }
    LaunchedEffect(row) { if (lines.isNotEmpty()) state.keepVisible(row) }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 40.dp),
        modifier = modifier.simpleDepth(high = true).background(SimplePanelBrush, SimpleShape).border(1.dp, SimpleLine, SimpleShape),
    ) {
        itemsIndexed(lines) { i, l ->
            when (l) {
                is OHead -> Column(Modifier.padding(start = 14.dp, top = if (i == 0) 8.dp else 20.dp, bottom = 4.dp)) {
                    Text(L(l.cat.label).uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                    Text(L(l.cat.desc), color = SimpleDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                is OSet -> SettingLine(l.def, source, env, values, focused = i == row, active = active) { onTap(i) }
                is OLink -> LinkLine(l.h.label, null, focused = i == row, active = active) { onTap(i) }
            }
        }
    }
}
