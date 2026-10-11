package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Shared outlined-card look for the app's popup menus, matching the FileManager / CommunityCard
// idiom (surfaceContainer fill, 1dp outline, rounded 10dp). Applied to a DropdownMenu's modifier so
// the popup reads as an outlined card. Keep all three menu call-sites on this so they stay identical.
@Composable
internal fun Modifier.outlinedMenuCard(): Modifier {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    return this
        .clip(shape)
        .background(cs.surfaceContainer)
        .border(1.dp, cs.outline, shape)
}

// The thin low-alpha separator that sits between items inside an outlined menu card.
@Composable
internal fun MenuItemDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}

// A long item menu (a game's or a container's options) is capped at six and a half items: the half
// item at the bottom, a scrollbar that stays visible while there is more, and a fade along the
// bottom edge all say the list goes on. 48dp items + 1dp dividers + the menu's 8dp top/bottom padding.
private val MENU_MAX_HEIGHT = (6.5f * 49f + 16f).dp

@Composable
internal fun ScrollingDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val thumb = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val fade = MaterialTheme.colorScheme.surfaceContainer
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .outlinedMenuCard()
            .heightIn(max = MENU_MAX_HEIGHT)
            .drawWithContent {
                drawContent()
                if (scroll.maxValue <= 0) return@drawWithContent
                val w = 3.dp.toPx()
                val inset = 4.dp.toPx()
                val x = size.width - w - inset
                val trackTop = inset
                val trackH = size.height - inset * 2
                // More below: fade the bottom edge into the menu's own fill.
                if (scroll.value < scroll.maxValue) {
                    val h = 28.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, fade), startY = size.height - h, endY = size.height),
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h),
                    )
                }
                drawRoundRect(track, Offset(x, trackTop), Size(w, trackH), CornerRadius(w / 2))
                val content = size.height + scroll.maxValue
                val thumbH = (trackH * size.height / content).coerceAtLeast(24.dp.toPx())
                val thumbY = trackTop + (trackH - thumbH) * scroll.value / scroll.maxValue
                drawRoundRect(thumb, Offset(x, thumbY), Size(w, thumbH), CornerRadius(w / 2))
            },
        scrollState = scroll,
        content = content,
    )
}
