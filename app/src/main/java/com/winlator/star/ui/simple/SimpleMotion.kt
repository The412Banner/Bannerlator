package com.winlator.star.ui.simple

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import kotlin.math.max

/**
 * No press or focus shading. The Simple menus draw their own focus (the lit row, the orange frame); the
 * default indication would add a grey box inside it on rows that come from the Deck pages.
 */
private object SimpleNoIndication : Indication {
    private object Instance : IndicationInstance {
        override fun ContentDrawScope.drawIndication() { drawContent() }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance = Instance
}

/** Runs [content] without the default press / focus shading (see [SimpleNoIndication]). */
@Composable
internal fun SimpleFlat(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides SimpleNoIndication, content = content)
}

/**
 * Keeps item [index] on screen with a row of room above and below, scrolling only when it would leave
 * the visible part. The list stays still while the focus moves inside it.
 */
internal suspend fun LazyListState.keepVisible(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(max(0, index - 1))
        return
    }
    val first = visible.first().index
    val last = visible.last().index
    val viewEnd = layoutInfo.viewportEndOffset
    val lastFull = visible.lastOrNull { it.offset + it.size <= viewEnd }?.index ?: last
    when {
        index <= first -> animateScrollToItem(max(0, index - 1))
        index >= lastFull -> animateScrollToItem(max(0, first + (index - lastFull) + 1))
    }
}
