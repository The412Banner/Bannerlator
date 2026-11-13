package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.store.storeFocus

/** One tab of a game page's chip row: an [icon], a short [label] and, after it, a small [badge] (a count) when there is one. */
internal class StoreTab(val icon: ImageVector, val label: String, val badge: String? = null)

/** What a chip is drawn with in one state (selected or not); [focusTint] is the highlight's colour on a filled chip. */
internal class StoreChipStyle(val fill: Color, val line: Color, val ink: Color, val focusTint: Color = Color.Unspecified)

/** The icon of the tab called [label]: the tabs the stores share have their own, any other gets the info icon. */
internal fun storeTabIcon(label: String): ImageVector = when (label) {
    "Achievements" -> Icons.Filled.EmojiEvents
    "DLC" -> Icons.Filled.Extension
    "Cloud", "Cloud saves" -> Icons.Filled.Cloud
    "Media" -> Icons.Filled.PhotoLibrary
    else -> Icons.Filled.Info
}

/**
 * The tab row of a game's page: small chips, an icon and a short label each, all in the width it is given. They stay on one row
 * while they fit and wrap onto the next one when they do not, so none is ever cut off. Every chip is a stop of the D-pad; the
 * chip at [selected] is drawn in [active], the others in [idle].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoreTabChips(
    tabs: List<StoreTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    shape: Shape,
    idle: StoreChipStyle,
    active: StoreChipStyle,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val style = if (index == selected) active else idle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .storeFocus(shape, { onSelect(index) }, tint = style.focusTint)
                    .clip(shape)
                    .background(style.fill)
                    .border(1.dp, style.line, shape)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(tab.icon, contentDescription = null, tint = style.ink, modifier = Modifier.size(14.dp))
                Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = style.ink, maxLines = 1)
                if (tab.badge != null) {
                    Text(tab.badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = style.ink.copy(alpha = 0.75f), maxLines = 1)
                }
            }
        }
    }
}
