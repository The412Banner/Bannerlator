package com.winlator.star.ui.deck.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckShape
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.CUSTOM_PRESET_INDEX
import com.winlator.star.ui.theme.ThemePreset
import com.winlator.star.ui.theme.themePresets

// The XMB / Deck Settings > Appearance "Theme preset": a line that shows the current theme's name and colour squares and opens a
// popup grid of every theme (each a card with its name and its colour squares, the current one highlighted). Picking one applies it
// at once (AppThemeState.setPreset, what the Classic Appearance screen's swatch does) and closes the popup. The squares are the
// preset's own colours: background, surface, surface variant and the accent. The Custom slot shows the custom accent colour, as the
// Classic swatch does. The line and the popup read AppThemeState's flows, so they follow a pick (or a custom accent) at once.

/** The colour squares of [preset]: its background, surface, surface variant and accent ([customAccent] stands in for the Custom slot). */
@Composable
internal fun ThemeSwatches(preset: ThemePreset, isCustomSlot: Boolean, customAccent: Color, square: Dp) {
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val shape = RoundedCornerShape(square / 5)
    val colors = listOf(
        preset.background,
        preset.surface,
        preset.surfaceVariant,
        if (isCustomSlot) customAccent else preset.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(square)
                    .clip(shape)
                    .background(c)
                    .border(1.dp, edge, shape),
            )
        }
    }
}

/** The Theme preset line of the Appearance tab: the current theme's name and colour squares; A or a tap runs [onOpen]. */
@Composable
internal fun ThemePresetLine(onOpen: () -> Unit) {
    val index by AppThemeState.presetIndex.collectAsState()
    val accent by AppThemeState.customAccent.collectAsState()
    val cs = MaterialTheme.colorScheme
    val preset = themePresets.getOrElse(index) { themePresets.first() }
    DeckLine(
        label = L("Theme preset"),
        hint = L("The colours of every screen. Pick a theme from the list; a Custom accent colour below switches to the Custom theme."),
        onClick = onOpen,
        trailing = {
            Text(preset.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ThemeSwatches(preset, index == CUSTOM_PRESET_INDEX, accent, 18.dp)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        },
    )
}

/** The popup: a grid of the themes, the current one highlighted and focused. Back (B) or Close dismisses it with no change. */
@Composable
internal fun ThemePresetDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val index by AppThemeState.presetIndex.collectAsState()
    val accent by AppThemeState.customAccent.collectAsState()
    val gridState = rememberLazyGridState()
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        // A grid only composes what is on screen: bring a far-down current theme into view before asking for its focus.
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index.coerceAtLeast(0))
        withFrameNanos { }
        runCatching { current.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckDialogBars()
        DeckDialogScaled {
            Surface(shape = deckShape(22.dp), color = cs.surface, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L("Theme preset"), color = cs.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        DeckSmallButton(L("Close"), Icons.Filled.Close) { onDismiss() }
                    }
                    Text(
                        L("Each theme shows its background, surface, panel and accent colours. Picking one applies it at once."),
                        color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        state = gridState,
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(themePresets.size) { i ->
                            val selected = i == index
                            ThemeCard(
                                preset = themePresets[i],
                                selected = selected,
                                isCustomSlot = i == CUSTOM_PRESET_INDEX,
                                customAccent = accent,
                                modifier = if (selected) Modifier.focusRequester(current) else Modifier,
                            ) {
                                AppThemeState.setPreset(i)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One theme of the popup: its name over its colour squares; the current one has an accent border and a tick. */
@Composable
private fun ThemeCard(
    preset: ThemePreset,
    selected: Boolean,
    isCustomSlot: Boolean,
    customAccent: Color,
    modifier: Modifier,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else cs.onSurface.copy(alpha = 0.06f))
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.onSurface.copy(alpha = 0.16f), shape)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.name, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        ThemeSwatches(preset, isCustomSlot, customAccent, 28.dp)
    }
}
