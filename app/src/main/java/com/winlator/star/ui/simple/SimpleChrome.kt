package com.winlator.star.ui.simple

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.theme.AppThemeState

// The Simple style's frame around every page other than Home (stores, containers, components, settings, a game's
// settings ...). The page itself is the Deck page, drawn with the Simple corners (deckShape); the shell swaps its own
// rails, bars and legend for these, in the shape of the chosen layout: Shelf and Focus get a line across the top,
// Rail keeps its icon rail on the left. L1/R1 on a page go back to the Simple home's previous / next section.

/**
 * Behind every page while Simple is on, edge to edge (under the bar areas too): the cover collage, or the game's own
 * art on a game's pages ([art]), a little darker than on Home so the page stays readable.
 */
@Composable
internal fun SimplePageBackdrop(
    modifier: Modifier = Modifier,
    art: com.winlator.star.container.Shortcut? = null,
    artPath: String? = null,
    vm: ShortcutsViewModel = viewModel(),
) {
    val shortcuts by vm.shortcuts.collectAsState(initial = SimpleWallpaperMemory.games)
    val focused = art ?: artPath?.takeIf { it.isNotEmpty() }?.let { p -> shortcuts.firstOrNull { it.file.path == p } }
    Box(modifier) {
        SimpleWallpaper(focused = focused, games = shortcuts, hero = false)
        Box(Modifier.fillMaxSize().background(Color(0x4D050709)))
    }
}

/** Shelf and Focus: the line over a page, with the sections to jump back to and the clock. */
@Composable
internal fun SimplePageHeader(onSection: (Int) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val layout by AppThemeState.simpleLayout.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val section = SimpleSession.controller.section
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 26.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.simpleTap(onBack).padding(end = 18.dp),
        ) {
            SimpleGlyph("B")
            Text(L("Back"), color = SimpleMuted, fontSize = 13.sp)
        }
        if (layout == AppThemeState.SIMPLE_LAYOUT_FOCUS) {
            // Focus: the section's name and its dots.
            Text(SIMPLE_SECTIONS[section], color = Color.White, fontWeight = FontWeight.Bold, fontFamily = SoraFamily, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            SIMPLE_SECTIONS.indices.forEach { i ->
                val w by animateDpAsState(if (i == section) 18.dp else 6.dp, tween(260), label = "simplePageDot")
                Box(
                    Modifier
                        .simpleTap { onSection(i) }
                        .padding(horizontal = 3.dp, vertical = 6.dp)
                        .width(w)
                        .height(6.dp)
                        .background(if (i == section) accent else Color(0x40FFFFFF), RoundedCornerShape(3.dp)),
                )
            }
        } else {
            // Shelf: the section tabs between the bumper glyphs.
            SimpleGlyph("L1")
            Spacer(Modifier.width(4.dp))
            SIMPLE_SECTIONS.forEachIndexed { i, name ->
                val on = i == section
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.simpleTap { onSection(i) }.padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(name, color = if (on) Color.White else SimpleMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = SoraFamily)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(if (on) 24.dp else 0.dp).height(3.dp).background(accent))
                }
            }
            Spacer(Modifier.width(4.dp))
            SimpleGlyph("R1")
        }
        Spacer(Modifier.weight(1f))
        SimpleStatus()
    }
}

/** Rail: the Simple icon rail beside a page, the page's section lit. A tap goes back to that section on Home. */
@Composable
internal fun SimplePageRail(onSection: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val section = SimpleSession.controller.section
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(68.dp)
            .fillMaxHeight()
            .simpleDepth(high = true, shape = RectangleShape)
            .background(SimplePanelBrush)
            .padding(vertical = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(30.dp).background(accent, SimpleShape).simpleTap { onSection(section) },
        ) { Text("B", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
        Spacer(Modifier.height(18.dp))
        SECTION_ICONS.forEachIndexed { i, icon ->
            val on = i == section
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (on) Modifier
                            .background(accent.copy(alpha = 0.22f), SimpleShape)
                            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                        else Modifier
                    )
                    .simpleTap { onSection(i) },
            ) {
                Icon(icon, contentDescription = SIMPLE_SECTIONS[i], tint = if (on) accent else SimpleMuted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        SimpleGlyph("L1")
        Spacer(Modifier.height(6.dp))
        SimpleGlyph("R1")
    }
}

/** The line of hints under a page: Back, the sections, the page's own tabs (L2/R2), its options (X) and Search (Y). */
@Composable
internal fun SimplePageLegend(subTabLabel: String?, optionsLabel: String?, modifier: Modifier = Modifier, own: List<Pair<String, String>>? = null) {
    SimpleLegend(
        own ?: listOfNotNull(
            "B" to L("Back"),
            "L1" to L("Sections"),
            subTabLabel?.let { "L2" to it },
            optionsLabel?.let { "X" to it },
            "Y" to L("Search"),
        ),
        modifier.padding(end = 26.dp, bottom = 12.dp, top = 6.dp),
    )
}
