package com.winlator.star.ui.simple

import androidx.compose.animation.core.Spring
import com.winlator.star.ui.deck.rememberXmbLogo
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.lastPlayedLabel
import com.winlator.star.ui.deck.playtimeLabel
import com.winlator.star.ui.deck.setupSummary
import com.winlator.star.ui.deck.storeLabelOf
import kotlin.math.max

internal val SECTION_ICONS = listOf(Icons.Filled.GridView, Icons.Filled.Storefront, Icons.Filled.Build, Icons.Filled.Settings)

/** "Steam · 14.2 h played · Today": the line under a game's title. */
@Composable
private fun gameMeta(d: SimpleData, s: Shortcut): String {
    val context = LocalContext.current
    val st = d.stats[s.file.path]
    return listOfNotNull(
        storeLabelOf(s),
        playtimeLabel(st?.ms ?: 0L),
        lastPlayedLabel(context, st?.lastPlayed ?: 0L),
    ).joinToString(" · ")
}

/** The setup at a glance as chips: container, GPU driver, DX wrapper, X11 or Wayland. */
private fun setupChips(s: Shortcut): List<String> =
    runCatching { setupSummary(s).split(" · ").filter { it.isNotBlank() } }.getOrDefault(emptyList()).take(4)

/** A title a little smaller the longer it is, so long names still fit a line or two. */
private fun titleSize(name: String, base: Int) = (if (name.length > 28) base - 8 else if (name.length > 18) base - 4 else base).sp

// ───────────────────────────── shared pieces ─────────────────────────────

/** A focusable-looking row of a list or menu: lifted with an accent bar and tint when [focused]. */
@Composable
private fun SimpleRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    trailing: String? = null,
    focused: Boolean,
    active: Boolean = true,
    height: Dp = 52.dp,
    logoItem: SimpleItem? = null,
    onTap: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shift by animateFloatAsState(if (focused && active) 4f else 0f, tween(180), label = "simpleRowShift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { translationX = shift * density }
            .then(
                if (focused) Modifier
                    .simpleDepth()
                    .background(
                        if (active) Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.16f)))
                        else Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x14FFFFFF))),
                        SimpleShape,
                    )
                    .drawBehind { if (active) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                else Modifier
            )
            .simpleTap(onTap)
            .padding(horizontal = 16.dp),
    ) {
        if (logoItem?.logo != null) {
            SimpleItemArt(logoItem, Modifier.size(width = 44.dp, height = 30.dp), iconSize = 18.dp, label = false)
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (focused && active) accent else SimpleMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (focused) Color.White else SimpleInk.copy(alpha = 0.88f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = SimpleDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text("‹", color = if (focused) accent else Color.Transparent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.widthIn(min = 90.dp).padding(horizontal = 10.dp))
            Text("›", color = if (focused) accent else Color.Transparent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else if (trailing != null) {
            Text(trailing, color = SimpleMuted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** A tile on a shelf or strip: grows, brightens and gets the accent edge when focused. */
@Composable
private fun SimpleTile(focused: Boolean, width: Dp, height: Dp, grow: Float, onTap: () -> Unit, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) grow else 1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "simpleTileScale")
    val alpha by animateFloatAsState(if (focused) 1f else 0.7f, tween(220), label = "simpleTileAlpha")
    Box(
        modifier = Modifier
            .zIndex(if (focused) 1f else 0f)
            .size(width, height)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .simpleDepth(high = focused)
            .then(if (focused) Modifier.border(2.dp, accent, SimpleShape) else Modifier)
            .simpleTap(onTap),
    ) { content() }
}

/** The tile of a store or tool: a store's logo on its brand colours, else the icon on a colour pair; the name at the bottom. */
@Composable
private fun SimpleItemArt(item: SimpleItem, modifier: Modifier, iconSize: Dp = 48.dp, label: Boolean = true) {
    val brand = item.brand
    Box(modifier = modifier.clip(SimpleShape).background(if (brand != null) Brush.linearGradient(brand) else fallbackBrush(item.title + "#"))) {
        val logo = item.logo
        val bmp = if (logo != null) rememberXmbLogo(logo) else null
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                colorFilter = if (item.logoWhite) ColorFilter.tint(Color.White) else null,
                modifier = Modifier.fillMaxSize(0.62f).align(Alignment.Center).padding(bottom = if (label) 14.dp else 0.dp),
            )
        } else {
            Icon(item.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(iconSize).align(Alignment.Center))
        }
        if (label) {
            Text(
                item.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}

/** The "Add a game" tile at the end of the Library. */
@Composable
private fun AddGameArt(modifier: Modifier, label: Boolean = true) {
    Box(modifier = modifier.clip(SimpleShape).background(Color(0xCC12151C)).border(1.dp, SimpleLine, SimpleShape)) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = SimpleInk.copy(alpha = 0.8f), modifier = Modifier.size(44.dp).align(Alignment.Center))
        if (label) Text("Add a game", color = SimpleInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

/** Shelf/Focus: L1  Library  Stores  Manage  Settings  R1, the current one bright with an accent underline. */
@Composable
private fun SectionTabs(ctl: SimpleController) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SimpleGlyph("L1")
        Spacer(Modifier.width(6.dp))
        SIMPLE_SECTIONS.forEachIndexed { i, name ->
            val on = i == ctl.section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.simpleTap { ctl.goSection(i) }.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(name, color = if (on) Color.White else SimpleMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = SoraFamily)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(if (on) 28.dp else 0.dp).height(3.dp).background(accent, RoundedCornerShape(1.dp)))
            }
        }
        Spacer(Modifier.width(6.dp))
        SimpleGlyph("R1")
    }
}

/** The Library's store filters: L2  All  Steam  GOG …  R2. */
@Composable
private fun FilterChips(ctl: SimpleController, d: SimpleData) {
    val accent = MaterialTheme.colorScheme.primary
    val current = ctl.filter.coerceIn(0, d.filters.size - 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimpleGlyph("L2")
        d.filters.forEachIndexed { i, f ->
            val on = i == current
            Box(
                modifier = Modifier
                    .simpleDepth(shape = SimpleChipShape)
                    .background(if (on) accent.copy(alpha = 0.22f) else Color(0x990C0E13), SimpleChipShape)
                    .border(1.dp, if (on) accent.copy(alpha = 0.8f) else Color.Transparent, SimpleChipShape)
                    .simpleTap { ctl.filter = i; ctl.setFocus(SEC_LIBRARY, 0) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(f, color = if (on) Color.White else SimpleMuted, fontSize = 12.sp) }
        }
        SimpleGlyph("R2")
    }
}

/** A legend for what the focused thing allows. */
private fun mainLegend(ctl: SimpleController): List<Pair<String, String>> = when {
    ctl.section == SEC_SETTINGS -> listOf("↕" to "Move", "↔" to "Change", "A" to "Select", "B" to "Back")
    ctl.section == SEC_LIBRARY && ctl.focusedGame() != null -> listOf("A" to "Play", "X" to "Game settings", "Y" to "Game menu", "⧉" to "Help")
    ctl.section == SEC_LIBRARY -> listOf("A" to "Add a game", "⧉" to "Help")
    else -> listOf("A" to "Open", "↔" to "Browse", "B" to "Back")
}

// ───────────────────────────── Shelf ─────────────────────────────

/** Shelf: one row of covers, the focused one large, its facts underneath. The default Simple layout. */
@Composable
internal fun SimpleShelfLayout(ctl: SimpleController, d: SimpleData) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = maxWidth
        val portrait = maxHeight > maxWidth
        val pad = if (portrait) 20.dp else 56.dp
        val tileW = (if (portrait) maxWidth * 0.36f else maxWidth * 0.14f).coerceIn(112.dp, 196.dp)
        val tileH = tileW * 4f / 3f
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x8C05070B), Color.Transparent)))
                    .padding(start = pad - 8.dp, end = pad, top = 14.dp, bottom = 6.dp),
            ) {
                SectionTabs(ctl)
                Spacer(Modifier.weight(1f))
                if (!portrait) SimpleStatus()
            }
            if (ctl.section == SEC_LIBRARY) {
                Box(Modifier.padding(start = pad - 4.dp, top = 6.dp)) { FilterChips(ctl, d) }
            } else Spacer(Modifier.height(36.dp))

            if (ctl.section == SEC_SETTINGS) {
                Column(
                    modifier = Modifier
                        .padding(start = pad, top = 18.dp)
                        .widthIn(max = 620.dp)
                        .fillMaxWidth(if (portrait) 1f else 0.55f)
                        .simpleDepth(high = true)
                        .background(SimplePanelBrush, SimpleShape)
                        .border(1.dp, SimpleLine, SimpleShape)
                        .padding(12.dp),
                ) {
                    Text("Settings", color = SimpleInk, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp, top = 4.dp))
                    d.settings.forEachIndexed { i, it ->
                        SimpleRow(it.icon, it.title, it.subtitle, value = it.value, trailing = if (it.value == null) "›" else null, focused = i == ctl.focusOf(SEC_SETTINGS)) {
                            if (i == ctl.focusOf(SEC_SETTINGS)) ctl.input(SIn.A) else ctl.setFocus(SEC_SETTINGS, i)
                        }
                    }
                }
            } else {
                val f = ctl.focusOf(ctl.section)
                val state = remember(ctl.section, ctl.filter) { LazyListState() }
                // Also when the row's length changes (the games arriving): scroll back to the focused tile.
                LaunchedEffect(f, ctl.section, ctl.filter, d.games.size) { state.animateScrollToItem(max(0, f)) }
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(start = pad, end = screenW),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(tileH * 1.2f).padding(top = 6.dp),
                ) {
                    val tap: (Int) -> Unit = { i -> if (i == ctl.focusOf(ctl.section)) ctl.input(SIn.A) else ctl.setFocus(ctl.section, i) }
                    when (ctl.section) {
                        SEC_LIBRARY -> {
                            itemsIndexed(d.games, key = { _, s -> s.file.path }) { i, s ->
                                SimpleTile(i == f, tileW, tileH, 1.12f, { tap(i) }) { SimpleCover(s, Modifier.fillMaxSize()) }
                            }
                            item(key = "add-game") {
                                val i = d.games.size
                                SimpleTile(i == f, tileW, tileH, 1.12f, { tap(i) }) { AddGameArt(Modifier.fillMaxSize()) }
                            }
                        }
                        else -> itemsIndexed(ctl.itemsOf(ctl.section), key = { _, it -> it.key }) { i, it ->
                            SimpleTile(i == f, tileW, tileH, 1.12f, { tap(i) }) { SimpleItemArt(it, Modifier.fillMaxSize()) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.padding(horizontal = pad).fillMaxWidth(if (portrait) 1f else 0.62f)) {
                    val g = ctl.focusedGame()
                    val item = ctl.focusedItem()
                    when {
                        g != null -> {
                            Text(g.name, color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = titleSize(g.name, 32), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(gameMeta(d, g), color = SimpleMuted, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { setupChips(g).forEach { SimpleChip(it) } }
                        }
                        ctl.section == SEC_LIBRARY -> {
                            Text(if (d.games.isEmpty()) "No games yet" else "Add a game", color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("An .exe from your files, or a game from your stores.", color = SimpleMuted, fontSize = 14.sp)
                        }
                        item != null -> {
                            Text(item.title, color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(item.subtitle, color = SimpleMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            SimpleLegend(mainLegend(ctl), Modifier.align(Alignment.End).padding(end = pad, bottom = 16.dp))
        }
    }
}

// ───────────────────────────── Rail ─────────────────────────────

/** Rail: an icon rail that opens when focused, a compact list, and the focused entry's details beside it. */
@Composable
internal fun SimpleRailLayout(ctl: SimpleController, d: SimpleData) {
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = maxWidth
        val portrait = maxHeight > maxWidth
        val railOpen = ctl.zone == Zone.RAIL
        val railW by animateDpAsState(if (railOpen) 214.dp else 68.dp, tween(260), label = "simpleRailW")
        Row(Modifier.fillMaxSize()) {
            // The rail.
            Column(
                modifier = Modifier
                    .zIndex(2f)
                    .width(railW)
                    .fillMaxHeight()
                    .simpleDepth(high = true, shape = RectangleShape)
                    .background(SimplePanelBrush)
                    .padding(vertical = 16.dp, horizontal = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 18.dp).height(32.dp)) {
                    Box(Modifier.size(30.dp).background(accent, SimpleShape), contentAlignment = Alignment.Center) {
                        Text("B", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                    if (railW > 140.dp) {
                        Spacer(Modifier.width(10.dp))
                        Text("Bannerlator", color = SimpleInk, fontWeight = FontWeight.Bold, fontFamily = SoraFamily, fontSize = 15.sp, maxLines = 1)
                    }
                }
                SIMPLE_SECTIONS.forEachIndexed { i, name ->
                    val on = i == ctl.section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .then(if (on && railOpen) Modifier.background(accent.copy(alpha = 0.22f), SimpleShape).drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                            .simpleTap { ctl.goSection(i); ctl.zone = Zone.LIST }
                            .padding(start = 14.dp),
                    ) {
                        Icon(SECTION_ICONS[i], contentDescription = name, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(22.dp))
                        if (railW > 140.dp) {
                            Spacer(Modifier.width(14.dp))
                            Text(name, color = if (on) Color.White else SimpleMuted, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (railW > 140.dp) SimpleStatus(Modifier.padding(start = 12.dp))
            }

            val showList = !(portrait && ctl.zone == Zone.DETAIL)
            val showDetail = !portrait || ctl.zone == Zone.DETAIL
            if (showList) {
                Column(
                    modifier = Modifier
                        .then(if (portrait) Modifier.weight(1f) else Modifier.width((screenW * 0.36f).coerceIn(300.dp, 440.dp)))
                        .fillMaxHeight()
                        .background(Color(0xA8090B0F))
                        .padding(top = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 22.dp, end = 18.dp, bottom = 10.dp)) {
                        Text(SIMPLE_SECTIONS[ctl.section], color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("${if (ctl.section == SEC_LIBRARY) d.games.size else ctl.itemsOf(ctl.section).size}", color = SimpleDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                        Spacer(Modifier.weight(1f))
                        if (ctl.section == SEC_LIBRARY) {
                            Text(RAIL_SORTS[ctl.sort.coerceIn(0, RAIL_SORTS.size - 1)].label, color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp, bottom = 3.dp))
                            SimpleGlyph("Y")
                        }
                    }
                    val f = ctl.focusOf(ctl.section)
                    val state = remember(ctl.section, ctl.sort) { LazyListState() }
                    LaunchedEffect(f, ctl.section, ctl.sort, d.games.size) { state.animateScrollToItem(max(0, f - 3)) }
                    val listActive = ctl.zone == Zone.LIST
                    LazyColumn(state = state, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 70.dp), modifier = Modifier.fillMaxSize()) {
                        val tap: (Int) -> Unit = { i ->
                            ctl.zone = Zone.LIST
                            if (i == ctl.focusOf(ctl.section)) ctl.input(SIn.A) else ctl.setFocus(ctl.section, i)
                        }
                        when (ctl.section) {
                            SEC_LIBRARY -> {
                                itemsIndexed(d.games, key = { _, s -> s.file.path }) { i, s ->
                                    RailGameRow(d, s, i == f, listActive) { tap(i) }
                                }
                                item(key = "add-game") {
                                    SimpleRow(Icons.Filled.Add, "Add a game", "An .exe or a store game", focused = f == d.games.size, active = listActive, height = 56.dp) { tap(d.games.size) }
                                }
                            }
                            else -> itemsIndexed(ctl.itemsOf(ctl.section), key = { _, it -> it.key }) { i, it ->
                                SimpleRow(it.icon, it.title, logoItem = it, subtitle = it.subtitle, value = it.value, focused = i == f, active = listActive, height = 56.dp) { tap(i) }
                            }
                        }
                    }
                }
            }
            if (showDetail) {
                RailDetail(ctl, d, Modifier.weight(1f).fillMaxHeight())
            }
        }
        val legend = when (ctl.zone) {
            Zone.RAIL -> listOf("↕" to "Section", "A" to "Open", "R1" to "Next")
            Zone.DETAIL -> listOf("↕" to "Move", "A" to "Select", "B" to "Back")
            else -> when {
                ctl.section == SEC_SETTINGS -> listOf("↔" to "Change", "A" to "Select", "B" to "Menu")
                ctl.section == SEC_LIBRARY && ctl.focusedGame() != null -> listOf("A" to "Play", "→" to "Details", "X" to "Game settings", "Y" to "Order", "⧉" to "Help")
                else -> listOf("A" to "Open", "→" to "Details", "B" to "Menu")
            }
        }
        SimpleLegend(legend, Modifier.align(Alignment.BottomEnd).padding(end = 26.dp, bottom = 16.dp))
    }
}

@Composable
private fun RailGameRow(d: SimpleData, s: Shortcut, focused: Boolean, active: Boolean, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val st = d.stats[s.file.path]
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .then(
                if (focused) Modifier
                    .simpleDepth()
                    .background(
                        if (active) Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.16f)))
                        else Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x14FFFFFF))),
                        SimpleShape,
                    )
                    .drawBehind { if (active) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                else Modifier
            )
            .simpleTap(onTap)
            .padding(horizontal = 12.dp),
    ) {
        SimpleCover(s, Modifier.size(width = 34.dp, height = 45.dp), title = false, monoSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = if (focused) Color.White else SimpleInk.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(storeLabelOf(s), lastPlayedLabel(context, st?.lastPlayed ?: 0L)).joinToString(" · "),
                color = SimpleDim, fontSize = 12.sp, maxLines = 1,
            )
        }
        val ms = st?.ms ?: 0L
        if (ms > 0L) Text(if (ms < 3_600_000L) "<1 h" else "%.0f h".format(ms / 3_600_000.0), color = SimpleMuted, fontSize = 12.sp)
    }
}

@Composable
private fun RailDetail(ctl: SimpleController, d: SimpleData, modifier: Modifier) {
    val g = ctl.focusedGame()
    val item = ctl.focusedItem()
    val acts = ctl.actions()
    val detailActive = ctl.zone == Zone.DETAIL
    Column(modifier = modifier.padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 60.dp)) {
        // Banner: the cover, cropped wide.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.4f)
                .heightIn(max = 230.dp)
                .simpleDepth(high = true),
        ) {
            when {
                g != null -> SimpleCover(g, Modifier.fillMaxSize(), title = false, monoSize = 96.sp)
                item != null -> SimpleItemArt(item, Modifier.fillMaxSize(), iconSize = 86.dp, label = false)
                else -> AddGameArt(Modifier.fillMaxSize(), label = false)
            }
        }
        Spacer(Modifier.height(16.dp))
        val title = g?.name ?: item?.title ?: "Add a game"
        Text(title, color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = titleSize(title, 26), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                g != null -> gameMeta(d, g)
                item != null -> item.subtitle
                else -> "An .exe from your files, or a game from your stores."
            },
            color = SimpleMuted, fontSize = 14.sp,
        )
        if (g != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { setupChips(g).forEach { SimpleChip(it) } }
        }
        Spacer(Modifier.height(16.dp))
        acts.forEachIndexed { i, a ->
            SimpleRow(
                if (a.primary && g != null) Icons.Filled.PlayArrow else null,
                a.label,
                focused = detailActive && i == ctl.sub,
                trailing = "›",
                height = 46.dp,
            ) { ctl.zone = Zone.DETAIL; ctl.sub = i; a.perform() }
        }
    }
}

// ───────────────────────────── Focus ─────────────────────────────

/** Focus: the focused game fills the screen; its title, buttons and quick facts on the left, a strip to browse below. */
@Composable
internal fun SimpleFocusLayout(ctl: SimpleController, d: SimpleData) {
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val pad = if (portrait) 22.dp else 56.dp
        val thumbW = (if (portrait) maxWidth * 0.36f else maxWidth * 0.13f).coerceIn(120.dp, 180.dp)
        val thumbH = thumbW * 9f / 16f
        // Top line: section, its dots, status and the menu glyph.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = pad, end = pad - 16.dp, top = 18.dp)) {
            Text(SIMPLE_SECTIONS[ctl.section], color = Color.White, fontWeight = FontWeight.Bold, fontFamily = SoraFamily, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            SIMPLE_SECTIONS.indices.forEach { i ->
                val w by animateDpAsState(if (i == ctl.section) 18.dp else 6.dp, tween(260), label = "simpleDot")
                Box(Modifier.padding(horizontal = 3.dp).width(w).height(6.dp).background(if (i == ctl.section) accent else Color(0x40FFFFFF), RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.weight(1f))
            if (!portrait) SimpleStatus()
            Spacer(Modifier.width(12.dp))
            SimpleGlyph("☰")
        }

        // Hero text and the action row.
        val g = ctl.focusedGame()
        val item = ctl.focusedItem()
        val actsActive = ctl.zone != Zone.STRIP
        Column(Modifier.padding(start = pad, end = pad, top = if (portrait) 90.dp else 96.dp).fillMaxWidth(if (portrait) 1f else 0.66f)) {
            val kicker = when {
                g != null -> storeLabelOf(g)
                ctl.section == SEC_LIBRARY -> "Library"
                else -> SIMPLE_SECTIONS[ctl.section]
            }
            Text(kicker.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            val title = g?.name ?: item?.title ?: if (d.games.isEmpty()) "No games yet" else "Add a game"
            Text(title, color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = titleSize(title, 46), lineHeight = titleSize(title, 50), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    g != null -> gameMeta(d, g)
                    item != null -> item.subtitle
                    else -> "An .exe from your files, or a game from your stores."
                },
                color = SimpleInk.copy(alpha = 0.82f), fontSize = 15.sp,
            )
            if (g != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { setupChips(g).forEach { SimpleChip(it) } }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item?.cycle != null) {
                    FocusButton("‹  ${item.value}  ›", primary = true, focused = actsActive) { item.cycle.invoke(1) }
                } else {
                    ctl.actions().forEachIndexed { i, a ->
                        FocusButton(a.label, primary = a.primary, focused = actsActive && i == ctl.sub, icon = if (a.primary && g != null) Icons.Filled.PlayArrow else null) {
                            ctl.zone = Zone.ACTS; ctl.sub = i; a.perform()
                        }
                    }
                }
            }
        }

        // The strip.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 56.dp)) {
            Text(SIMPLE_SECTIONS[ctl.section].uppercase(), color = SimpleMuted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = pad, bottom = 10.dp))
            val f = ctl.focusOf(ctl.section)
            val state = remember(ctl.section, ctl.filter) { LazyListState() }
            LaunchedEffect(f, ctl.section, ctl.filter, d.games.size) { state.animateScrollToItem(max(0, f - 2)) }
            val stripActive = ctl.zone == Zone.STRIP
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = pad, end = pad),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(thumbH * 1.22f),
            ) {
                val tap: (Int) -> Unit = { i ->
                    if (i == ctl.focusOf(ctl.section)) { ctl.zone = Zone.ACTS; ctl.sub = 0 } else { ctl.zone = Zone.STRIP; ctl.setFocus(ctl.section, i) }
                }
                when (ctl.section) {
                    SEC_LIBRARY -> {
                        itemsIndexed(d.games, key = { _, s -> s.file.path }) { i, s ->
                            StripThumb(i == f, stripActive, thumbW, thumbH, { tap(i) }) { SimpleCover(s, Modifier.fillMaxSize(), title = false, monoSize = 26.sp) }
                        }
                        item(key = "add-game") {
                            val i = d.games.size
                            StripThumb(i == f, stripActive, thumbW, thumbH, { tap(i) }) { AddGameArt(Modifier.fillMaxSize(), label = false) }
                        }
                    }
                    else -> itemsIndexed(ctl.itemsOf(ctl.section), key = { _, it -> it.key }) { i, it ->
                        StripThumb(i == f, stripActive, thumbW, thumbH, { tap(i) }) { SimpleItemArt(it, Modifier.fillMaxSize(), iconSize = 34.dp, label = false) }
                    }
                }
            }
        }

        val legend = when {
            ctl.menuOpen -> listOf("↕" to "Move", "A" to "Open", "B" to "Close")
            ctl.zone == Zone.STRIP -> listOf("↔" to "Browse", "A" to "Select", "L1" to "Sections", "☰" to "Menu")
            ctl.section == SEC_LIBRARY && g != null -> listOf("↔" to "Move", "A" to "Select", "↓" to "Library", "X" to "Game settings", "☰" to "Menu")
            ctl.section == SEC_SETTINGS -> listOf("↔" to "Change", "↓" to "Browse", "☰" to "Menu")
            else -> listOf("A" to "Select", "↓" to "Browse", "☰" to "Menu")
        }
        SimpleLegend(legend, Modifier.align(Alignment.BottomEnd).padding(end = 26.dp, bottom = 16.dp))

        if (ctl.menuOpen) FocusMenu(ctl)
    }
}

@Composable
private fun FocusButton(label: String, primary: Boolean, focused: Boolean, icon: ImageVector? = null, onTap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val lift by animateFloatAsState(if (focused) -3f else 0f, tween(180), label = "simpleBtnLift")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { translationY = lift * density }
            .simpleDepth(high = focused)
            .background(
                when {
                    focused && primary -> accent
                    focused -> accent.copy(alpha = 0.26f)
                    primary -> accent.copy(alpha = 0.55f)
                    else -> Color(0xA30C0E13)
                },
                SimpleShape,
            )
            .border(1.dp, if (focused) accent else Color(0x14FFFFFF), SimpleShape)
            .simpleTap(onTap)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = Color.White, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium, fontSize = if (primary) 16.sp else 15.sp, maxLines = 1)
    }
}

@Composable
private fun StripThumb(focused: Boolean, active: Boolean, w: Dp, h: Dp, onTap: () -> Unit, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused && active) 1.1f else 1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "simpleThumbScale")
    val alpha by animateFloatAsState(if (focused) 1f else 0.6f, tween(220), label = "simpleThumbAlpha")
    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .size(w, h)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .simpleDepth(high = focused && active)
            .then(if (focused && active) Modifier.border(2.dp, accent, SimpleShape) else Modifier)
            .simpleTap(onTap),
    ) { content() }
}

/** Focus's Start menu: the sections and the controls help, as a lifted panel on the left. */
@Composable
private fun FocusMenu(ctl: SimpleController) {
    val accent = MaterialTheme.colorScheme.primary
    val entries = SIMPLE_SECTIONS + "Controls help"
    Box(Modifier.fillMaxSize().background(Color(0xD105070B)).simpleTap { ctl.menuOpen = false }, contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .padding(start = 80.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 34.dp, end = 60.dp, top = 22.dp, bottom = 22.dp),
        ) {
            entries.forEachIndexed { i, name ->
                val on = i == ctl.menuFocus
                val shift by animateFloatAsState(if (on) 12f else 0f, tween(200), label = "simpleMenuShift")
                Text(
                    name,
                    color = if (on) Color.White else Color(0x52FFFFFF),
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (i < SIMPLE_SECTIONS.size) 34.sp else 22.sp,
                    modifier = Modifier
                        .graphicsLayer { translationX = shift * density }
                        .padding(vertical = 6.dp)
                        .simpleTap { ctl.menuFocus = i; ctl.input(SIn.A) },
                )
            }
        }
    }
}
