package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage

/** Width to height of a game's cover: Steam's 92:43 capsule, the shape most stores publish their landscape art in. */
internal const val STORE_COVER_ASPECT = 92f / 43f

/** The cover's sources in order of preference, without blanks and repeats. */
internal fun storeCoverUrls(sources: List<String?>): List<String> =
    sources.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()

/**
 * A game's landscape cover, [width] wide in a box of fixed shape so the page does not move when the picture arrives. [onError]
 * runs when [url] cannot be loaded, for the page to try the next source.
 */
@Composable
internal fun StoreCover(
    url: String,
    width: Dp,
    shape: Shape,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .aspectRatio(STORE_COVER_ASPECT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { onError() },
        )
    }
}
