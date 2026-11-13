package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Width to height of the cover above a game's name: Steam's 92:43 capsule, the shape most stores publish their landscape art in. */
internal const val STORE_COVER_ASPECT = 92f / 43f

/** The cover's sources in order of preference, without blanks and repeats. */
internal fun storeCoverUrls(sources: List<String?>): List<String> =
    sources.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()

/**
 * A game's landscape cover above its name, exactly as wide as the primary button below it: it lays out like the button row, the
 * cover in the button's weighted share with the same [gap], and empty room where the secondary button ([withSecondary]) and the
 * gear ([gearWidth], null when there is none) sit. The first of [urls] that loads is shown, sharp, in a box of fixed shape, so
 * the page does not move when the picture arrives; with no url, or when none loads, nothing is composed.
 */
@Composable
internal fun StoreCoverRow(
    urls: List<String>,
    shape: Shape,
    gap: Dp,
    modifier: Modifier = Modifier,
    withSecondary: Boolean = false,
    gearWidth: Dp? = null,
    bottomGap: Dp = 8.dp,
) {
    // Moves on to the next url when one fails; a new list starts over. No "loaded" flag: the image reports only what it shows.
    var attempt by remember(urls) { mutableIntStateOf(0) }
    if (attempt >= urls.size) return
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = bottomGap),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Box(
            Modifier
                .weight(1f)
                .aspectRatio(STORE_COVER_ASPECT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = urls[attempt],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { attempt += 1 },
            )
        }
        if (withSecondary) Spacer(Modifier.weight(1f))
        if (gearWidth != null) Spacer(Modifier.width(gearWidth))
    }
}
