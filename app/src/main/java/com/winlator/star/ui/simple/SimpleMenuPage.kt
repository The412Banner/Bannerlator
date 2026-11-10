package com.winlator.star.ui.simple

import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.rememberXmbLogo
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

// The Simple style's list pages (Containers, Components, GPU drivers, Controls, Tools, Stores): sections of entries over
// the existing data and actions, drawn per layout like the Simple settings menus: Shelf = section tabs over one panel,
// Rail = a section list beside the entries, Focus = the section's title large with a section strip underneath.
// A opens an entry, X its actions, Y the page's main action, L2/R2 the sections, B back.

/** One thing an entry can do from its X menu. [confirm] asks first (removing, overwriting). */
internal class MenuAction(val label: String, val run: () -> Unit, val danger: Boolean = false, val confirm: String? = null)

internal class MenuEntry(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    /** Short text on the right: a version, "Default", "Installed", a size. */
    val trailing: String? = null,
    val icon: ImageVector? = null,
    val logo: Int? = null,
    val brand: List<Color>? = null,
    val logoWhite: Boolean = false,
    /** A colour square in front of the title (theme presets). */
    val swatch: Color? = null,
    /** 0..1 while something runs for this entry (a download), null otherwise. */
    val progress: Float? = null,
    /** Highlighted as the current choice (the default driver, the selected profile). */
    val current: Boolean = false,
    val onA: (() -> Unit)? = null,
    val actions: List<MenuAction> = emptyList(),
    /** The section's main action shown as a row (New container, Install from a file …). */
    val primary: Boolean = false,
    /** Start it straight away (a container's Windows desktop): a ▶ button on the row, Start on a controller. */
    val play: (() -> Unit)? = null,
    /** The ▶ button's icon and name when it is not Launch (Download, Reinstall). */
    val playIcon: ImageVector? = null,
    val playLabel: String = L("Launch"),
    /** Small labels on the right (the component types a download source offers). */
    val pills: List<String> = emptyList(),
    /** A heading over the first row of each run of rows with the same group (GPU drivers, DXVK …). */
    val group: String? = null,
    /** An on/off row: a switch on the right (A or a tap runs [onA], which flips it). */
    val toggle: Boolean? = null,
    /** A picture in front of the title (a game's cover, logo, background …), in place of the icon. */
    val thumb: (@Composable () -> Unit)? = null,
)

internal class MenuSection(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val entries: List<MenuEntry>,
    val empty: String = L("Nothing here yet."),
    /** Y on this section, e.g. "New container" or "Install from a file". */
    val primary: MenuAction? = null,
    /** A screen of its own in place of the rows (the Linux runtime); the D-pad then moves through its buttons. */
    val content: (@Composable () -> Unit)? = null,
)

private enum class PZone { ROWS, SECS }

@Composable
internal fun SimpleMenuPage(
    overline: String,
    title: String,
    sections: List<MenuSection>,
    onBack: () -> Unit,
    /** Extra lines over the menu (a status, a running download, a notice). */
    banner: String? = null,
    /** The section open first (its key); the first one when null. */
    startSection: String? = null,
) {
    SimpleFlat { SimpleMenuPageBody(overline, title, sections, onBack, banner, startSection) }
}

@Composable
private fun SimpleMenuPageBody(
    overline: String,
    title: String,
    sections: List<MenuSection>,
    onBack: () -> Unit,
    banner: String?,
    startSection: String? = null,
) {
    val layout by AppThemeState.simpleLayout.collectAsState()
    var secKey by rememberSaveable { mutableStateOf(startSection ?: sections.firstOrNull()?.key ?: "") }
    val sec = sections.firstOrNull { it.key == secKey } ?: sections.firstOrNull()
    val secIndex = sections.indexOfFirst { it.key == sec?.key }.coerceAtLeast(0)
    var row by rememberSaveable { mutableIntStateOf(0) }
    var zone by remember { mutableStateOf(PZone.ROWS) }
    var actionsFor by remember { mutableStateOf<MenuEntry?>(null) }
    var confirmFor by remember { mutableStateOf<MenuAction?>(null) }
    val dialogOpen = actionsFor != null || confirmFor != null
    val realEntries = sec?.entries.orEmpty()
    // The section's main action (New container, Install from a file, New profile …) as the last row, so touch and the
    // D-pad reach it too; Y still runs it from anywhere on the page.
    val entries = realEntries + listOfNotNull(
        sec?.primary?.let { p ->
            MenuEntry(key = "__primary__", title = p.label, icon = Icons.Filled.Add, primary = true, onA = { if (p.confirm != null) confirmFor = p else p.run() })
        },
    )
    val rowNow = row.coerceIn(0, max(0, entries.size - 1))
    // Right / Left on a row step onto its buttons: 0 = the row, 1 = its ▶ (Launch), 2 = its ⋯ (Options).
    var col by remember { mutableIntStateOf(0) }
    LaunchedEffect(rowNow, secKey) { col = 0 }
    fun colsOf(e: MenuEntry?): List<Int> = buildList { add(0); if (e?.play != null) add(1); if (e?.actions?.isNotEmpty() == true) add(2) }

    fun stepSection(d: Int) {
        if (sections.isEmpty()) return
        secKey = sections[(secIndex + d + sections.size) % sections.size].key
        row = 0
    }
    fun run(a: MenuAction) { if (a.confirm != null) confirmFor = a else a.run() }

    BackHandler { onBack() }
    val deckActions = LocalDeckActions.current

    // L2/R2 through the shell as well: they then work wherever the controller focus is (the Lab rail, a tile, a dialog).
    val stepTabsNow = androidx.compose.runtime.rememberUpdatedState<(Int) -> Unit> { d -> stepSection(d) }
    DisposableEffect(deckActions, sections.size) {
        if (sections.size < 2) return@DisposableEffect onDispose {}
        val step: (Int) -> Unit = { stepTabsNow.value(it) }
        deckActions.subTab.value = step
        deckActions.subTabLabel.value = L("Section")
        onDispose { if (deckActions.subTab.value === step) deckActions.subTab.value = null }
    }
    val legend = buildList {
        add("B" to L("Back"))
        add("A" to L("Open"))
        entries.getOrNull(rowNow)?.takeIf { it.play != null }?.let { add("Start" to it.playLabel) }
        if (colsOf(entries.getOrNull(rowNow)).size > 1) add("◀▶" to L("Buttons"))
        if (entries.getOrNull(rowNow)?.actions?.isNotEmpty() == true) add("X" to L("Options"))
        sec?.primary?.let { add("Y" to it.label) }
        if (sections.size > 1) add("L2" to L("Section"))
    }
    DisposableEffect(deckActions, legend) {
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(dialogOpen) {
        if (dialogOpen) return@LaunchedEffect
        delay(60)
        runCatching { focus.requestFocus() }
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var selfFocused by remember { mutableStateOf(false) }
    val onKey: (SIn) -> Boolean = onKey@{ a ->
        val cur = entries.getOrNull(rowNow)
        // A section with a screen of its own: the D-pad and A go to its buttons (from the page itself, Down steps in).
        if (sec?.content != null && a != SIn.B && a != SIn.LT && a != SIn.RT) {
            if (selfFocused && (a == SIn.DOWN || a == SIn.RIGHT)) {
                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
                return@onKey true
            }
            return@onKey false
        }
        when {
            a == SIn.B -> onBack()
            a == SIn.LT -> stepSection(-1)
            a == SIn.RT -> stepSection(1)
            a == SIn.Y -> sec?.primary?.let { run(it) }
            a == SIn.X -> { if (cur != null && cur.actions.isNotEmpty()) actionsFor = cur }
            zone == PZone.SECS -> when (a) {
                SIn.UP, SIn.LEFT -> stepSection(-1)
                SIn.DOWN -> stepSection(1)
                SIn.RIGHT -> { if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL) zone = PZone.ROWS else stepSection(1) }
                SIn.A -> zone = PZone.ROWS
                else -> return@onKey a == SIn.START || a == SIn.SELECT
            }
            else -> when (a) {
                SIn.UP -> { if (rowNow > 0) row = rowNow - 1 }
                SIn.DOWN -> {
                    if (rowNow >= entries.size - 1 && layout == AppThemeState.SIMPLE_LAYOUT_FOCUS && sections.size > 1) zone = PZone.SECS
                    else row = min(max(0, entries.size - 1), rowNow + 1)
                }
                SIn.LEFT -> {
                    val cols = colsOf(cur)
                    val i = cols.indexOf(col)
                    if (i > 0) col = cols[i - 1]
                    else if (layout == AppThemeState.SIMPLE_LAYOUT_RAIL && sections.size > 1) zone = PZone.SECS
                    else return@onKey false   // the Lab rail on the left takes it
                }
                SIn.RIGHT -> {
                    val cols = colsOf(cur)
                    val i = cols.indexOf(col)
                    if (i >= 0 && i < cols.size - 1) col = cols[i + 1]
                }
                SIn.A -> when (col) {
                    1 -> cur?.play?.invoke()
                    2 -> { if (cur != null && cur.actions.isNotEmpty()) actionsFor = cur }
                    else -> cur?.onA?.invoke() ?: run { if (cur != null && cur.actions.isNotEmpty()) actionsFor = cur }
                }
                SIn.START -> { cur?.play?.invoke() }
                else -> return@onKey a == SIn.SELECT
            }
        }
        true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onFocusChanged { selfFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (dialogOpen) return@onPreviewKeyEvent false
                val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                if (a == SIn.LB || a == SIn.RB) return@onPreviewKeyEvent false
                onKey(a)
            },
    ) {
        val narrow = maxWidth < 700.dp
        val list: @Composable (Modifier) -> Unit = { mod ->
            EntryPanel(
                sec, entries, rowNow, active = zone == PZone.ROWS, modifier = mod, col = col,
                emptyText = if (realEntries.isEmpty()) sec?.empty else null,
                onMenu = { i -> entries.getOrNull(i)?.takeIf { it.actions.isNotEmpty() }?.let { actionsFor = it } },
            ) { i ->
                zone = PZone.ROWS
                if (i == rowNow) entries.getOrNull(i)?.let { e -> e.onA?.invoke() ?: run { if (e.actions.isNotEmpty()) actionsFor = e } } else row = i
            }
        }
        when (layout) {
            AppThemeState.SIMPLE_LAYOUT_RAIL -> Row(Modifier.fillMaxSize()) {
                if (sections.size > 1) {
                    SectionList(sections, sec, active = zone == PZone.SECS, modifier = Modifier.width(if (narrow) 190.dp else 240.dp).fillMaxHeight()) { k -> secKey = k; row = 0 }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                    PageTitle(overline, title, if (sections.size > 1) sec?.title else null, banner, big = false)
                    Spacer(Modifier.height(12.dp))
                    list(Modifier.weight(1f).fillMaxWidth())
                }
            }
            AppThemeState.SIMPLE_LAYOUT_FOCUS -> Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 10.dp)) {
                PageTitle(overline, if (sections.size > 1) sec?.title ?: title else title, if (sections.size > 1) title else null, banner, big = true)
                Spacer(Modifier.height(14.dp))
                list(Modifier.weight(1f).fillMaxWidth(if (narrow) 1f else 0.72f))
                if (sections.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    SectionStrip(sections, sec, active = zone == PZone.SECS) { k -> secKey = k; row = 0 }
                }
                Spacer(Modifier.height(6.dp))
            }
            else -> Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 6.dp)) {
                PageTitle(overline, title, null, banner, big = false)
                if (sections.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    SectionTabs(sections, sec, compact = narrow) { k -> secKey = k; row = 0 }
                }
                Spacer(Modifier.height(12.dp))
                list(Modifier.weight(1f, fill = false).fillMaxWidth().widthIn(max = 1100.dp).align(Alignment.CenterHorizontally))
            }
        }
    }

    actionsFor?.let { e ->
        SimpleActionSheet(e.title, e.actions, onDismiss = { actionsFor = null }) { a -> actionsFor = null; run(a) }
    }
    confirmFor?.let { a ->
        SimpleConfirm(a.label, a.confirm ?: "", danger = a.danger, onDismiss = { confirmFor = null }) { confirmFor = null; a.run() }
    }
}

@Composable
private fun PageTitle(overline: String, title: String, sub: String?, banner: String?, big: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Text(overline.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = if (big) 38.sp else 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrBlank()) Text(sub, color = SimpleMuted, fontSize = 13.sp, maxLines = 1)
        if (!banner.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(banner, color = SimpleInk, fontSize = 13.sp, modifier = Modifier.background(accent.copy(alpha = 0.18f), SimpleShape).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun SectionTabs(sections: List<MenuSection>, sec: MenuSection?, compact: Boolean = false, onPick: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (!compact) {
            SimpleGlyph("L2")
            Spacer(Modifier.width(8.dp))
        }
        sections.forEach { s ->
            val on = s.key == sec?.key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .then(Modifier.tabFill(on, accent))
                    .simpleTap { onPick(s.key) }
                    .keepInViewWhen(on)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(s.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.title, color = if (on) SimpleStrong else SimpleMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text("${s.entries.size}", color = SimpleDim, fontSize = 11.sp)
            }
        }
        if (!compact) {
            Spacer(Modifier.width(2.dp))
            SimpleGlyph("R2")
        }
    }
}

@Composable
private fun SectionList(sections: List<MenuSection>, sec: MenuSection?, active: Boolean, modifier: Modifier, onPick: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.simpleDepth(high = true, shape = RectangleShape).background(SimplePanelBrush).padding(10.dp)) {
        sections.forEach { s ->
            val on = s.key == sec?.key
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
                    .simpleTap { onPick(s.key) }
                    .padding(horizontal = 14.dp),
            ) {
                Icon(s.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(s.title, color = if (on) SimpleStrong else SimpleInk.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("${s.entries.size}", color = SimpleDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SectionStrip(sections: List<MenuSection>, sec: MenuSection?, active: Boolean, onPick: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(80.dp)) {
        itemsIndexed(sections, key = { _, s -> s.key }) { _, s ->
            val on = s.key == sec?.key
            val scale by animateFloatAsState(if (on && active) 1.08f else 1f, tween(200), label = "simpleSecTile")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(124.dp)
                    .fillMaxHeight()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .simpleDepth(high = on)
                    .background(if (on) accent.copy(alpha = 0.3f) else Color(0xB30C0E13), SimpleShape)
                    .border(if (on && active) 2.dp else 1.dp, if (on) accent else SimpleLine, SimpleShape)
                    .simpleTap { onPick(s.key) }
                    .padding(8.dp),
            ) {
                Icon(s.icon, contentDescription = null, tint = if (on) SimpleStrong else SimpleMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text(s.title, color = if (on) SimpleStrong else SimpleMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EntryPanel(
    sec: MenuSection?,
    entries: List<MenuEntry>,
    row: Int,
    active: Boolean,
    modifier: Modifier,
    emptyText: String?,
    onMenu: (Int) -> Unit,
    col: Int = 0,
    onTap: (Int) -> Unit,
) {
    val state = remember(sec?.key) { LazyListState() }
    LaunchedEffect(row, sec?.key) { if (entries.isNotEmpty()) state.keepVisible(row) }
    Box(
        modifier = modifier
            .simpleDepth(high = true)
            .background(SimplePanelBrush, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape),
    ) {
        val content = sec?.content
        if (content != null) {
            Box(Modifier.fillMaxSize()) { content() }
        } else if (entries.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text(sec?.empty ?: L("Nothing here yet."), color = SimpleMuted, fontSize = 14.sp)
                sec?.primary?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { SimpleGlyph("Y"); Spacer(Modifier.width(6.dp)); Text(it.label, color = SimpleInk, fontSize = 14.sp) }
                }
            }
        } else {
            Column {
                if (emptyText != null) Text(emptyText, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp))
                LazyColumn(state = state, contentPadding = PaddingValues(10.dp)) {
                    itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
                        val g = e.group
                        if (g != null && (i == 0 || entries[i - 1].group != g)) {
                            Text(
                                g.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp,
                                modifier = Modifier.padding(start = 14.dp, top = if (i == 0) 4.dp else 14.dp, bottom = 4.dp),
                            )
                        }
                        // A faint line between two entries of one group (none under a group title).
                        else if (i > 0) SimpleRowDivider()
                        EntryLine(e, focused = i == row, active = active, onMenu = { onMenu(i) }, col = if (i == row && active) col else 0) { onTap(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryLine(e: MenuEntry, focused: Boolean, active: Boolean, onMenu: () -> Unit = {}, col: Int = 0, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shift by animateFloatAsState(if (focused && active) 4f else 0f, tween(160), label = "simpleEntryShift")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shift * density }
            .simpleRowFocus(focused, active)
            .pointerInput(onTap, e.actions.isNotEmpty()) {
                // Tap opens it, a long press its options (as on a game).
                detectTapGestures(onTap = { onTap() }, onLongPress = if (e.actions.isNotEmpty()) ({ _ -> onMenu() }) else null)
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
            val logo = e.logo
            val bmp = if (logo != null) rememberXmbLogo(logo) else null
            when {
                bmp != null -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(width = 46.dp, height = 32.dp).clip(SimpleShape).background(Brush.linearGradient(e.brand ?: listOf(Color(0xFF20242E), Color(0xFF12151C)))),
                ) {
                    Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = if (e.logoWhite) ColorFilter.tint(Color.White) else null, modifier = Modifier.size(width = 34.dp, height = 22.dp))
                }
                e.thumb != null -> Box(Modifier.width(112.dp), contentAlignment = Alignment.Center) { e.thumb.invoke() }
                e.swatch != null -> Box(Modifier.size(22.dp).simpleDepth().background(e.swatch, SimpleShape).border(1.dp, Color(0x40FFFFFF), SimpleShape))
                e.icon != null -> Icon(e.icon, contentDescription = null, tint = if (e.primary || (focused && active)) accent else SimpleMuted, modifier = Modifier.size(22.dp))
            }
            if (bmp != null || e.icon != null || e.swatch != null || e.thumb != null) Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, color = if (e.primary) accent else if (focused) SimpleStrong else SimpleInk.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (e.current) {
                        Spacer(Modifier.width(8.dp))
                        Text("✓", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (!e.subtitle.isNullOrBlank()) Text(e.subtitle, color = SimpleDim, fontSize = 12.sp, maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.pills.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.widthIn(max = 380.dp),
                ) {
                    e.pills.forEach { t ->
                        Text(
                            t, color = SimpleMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.background(SimpleCard, SimpleShape).border(1.dp, SimpleLine, SimpleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (!e.trailing.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(e.trailing, color = SimpleMuted, fontSize = 12.sp, maxLines = 1)
            }
            e.toggle?.let { on ->
                Spacer(Modifier.width(10.dp))
                LabSwitch(on)
            }
            // The item's options: a ⋯ to tap (X on a controller does the same).
            val play = e.play
            if (play != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .graphicsLayer { val k = if (col == 1) 1.08f else 1f; scaleX = k; scaleY = k }
                        .size(width = 52.dp, height = 36.dp)
                        .background(accent, SimpleShape)
                        .then(if (col == 1) Modifier.border(3.dp, SimpleStrong, SimpleShape) else Modifier)
                        .simpleTap(play),
                ) { Icon(e.playIcon ?: Icons.Filled.PlayArrow, contentDescription = e.playLabel, tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
            if (e.actions.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .background(if (col == 2) accent.copy(alpha = 0.3f) else if (focused) SimpleCard else Color.Transparent, SimpleShape)
                        .then(if (col == 2) Modifier.border(2.dp, accent, SimpleShape) else Modifier)
                        .simpleTap(onMenu),
                ) { Icon(Icons.Filled.MoreHoriz, contentDescription = L("Options"), tint = if (focused) SimpleStrong else SimpleMuted, modifier = Modifier.size(20.dp)) }
            }
        }
        val p = e.progress
        if (p != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x26FFFFFF))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(accent))
            }
        }
    }
}

/** X on an entry: what it can do, as a Simple list. Focus starts on the first; A runs, B closes. */
@Composable
internal fun SimpleActionSheet(title: String, actions: List<MenuAction>, onDismiss: () -> Unit, message: String? = null, onRun: (MenuAction) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val requesters = remember(actions.size) { List(actions.size) { FocusRequester() } }
    // A choice list starts on the current choice (the pages mark it "label  ✓"), else on the first entry.
    val start = remember(actions) { actions.indexOfFirst { it.label.trimEnd().endsWith("✓") }.coerceAtLeast(0) }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { requesters.getOrNull(start)?.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 520.dp)
                // A long list (the languages) scrolls; the controller's row is kept in view.
                .heightIn(max = 620.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            if (!message.isNullOrBlank()) Text(message, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp))
            actions.forEachIndexed { i, a ->
                var f by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .focusRequester(requesters[i])
                        .onFocusChanged { f = it.isFocused }
                        .then(if (f) Modifier.background(accent.copy(alpha = 0.3f), SimpleShape).drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                        .clickable { onRun(a) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(a.label, color = if (a.danger) MaterialTheme.colorScheme.error else SimpleStrong, fontSize = 15.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SimpleGlyph("A"); Spacer(Modifier.width(4.dp)); Text(L("Select"), color = SimpleMuted, fontSize = 12.sp)
                Spacer(Modifier.width(14.dp))
                SimpleGlyph("B"); Spacer(Modifier.width(4.dp)); Text(L("Close"), color = SimpleMuted, fontSize = 12.sp)
            }
        }
    }
}

/** A Simple yes/no: the question, then the action (red when it removes something) and Cancel. */
@Composable
internal fun SimpleConfirm(title: String, body: String, danger: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SimpleActionSheet(
        title = title,
        actions = listOf(MenuAction(title, onConfirm, danger = danger), MenuAction(L("Cancel"), onDismiss)),
        onDismiss = onDismiss,
        message = body,
    ) { it.run() }
}

/** Lab's switch: the accent track with the knob right when on. */
@Composable
internal fun LabSwitch(on: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val x by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "labSwitch")
    Box(Modifier.size(width = 44.dp, height = 24.dp).background(if (on) accent else SimpleLine, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(3.dp)) {
        Box(Modifier.graphicsLayer { translationX = x * 20.dp.toPx() }.size(18.dp).background(if (on) Color.White else SimpleMuted, androidx.compose.foundation.shape.CircleShape))
    }
}
