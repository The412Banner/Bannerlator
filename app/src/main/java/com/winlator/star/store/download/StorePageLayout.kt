package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.star.store.storeFocus
import com.winlator.star.ui.deck.rememberScreenScale

/** How a game's page is laid out in a window of a given size. */
internal enum class StorePageLayout {
    /** The cover and the name fixed on the right, a column on the left that scrolls through the actions and details. */
    SIDE_BY_SIDE,

    /** One column that scrolls as a whole: the cover, the name, the actions, then the details. */
    STACKED,
}

/** A window is laid out side by side when it is at least this much wider than it is tall (the XMB search popup's rule). */
private const val SIDE_BY_SIDE_RATIO = 1.2f

/** [StorePageLayout.SIDE_BY_SIDE] when the window is [width] wide and [height] tall (any one unit), else [StorePageLayout.STACKED]. */
internal fun storePageLayoutFor(width: Float, height: Float): StorePageLayout =
    if (width >= height * SIDE_BY_SIDE_RATIO) StorePageLayout.SIDE_BY_SIDE else StorePageLayout.STACKED

/**
 * The measurements of a game's page in dp, kept apart from the composables so they can be tested.
 *
 * The page's action row is the primary button ([installWidth] wide) followed by the Settings and the Downloads button
 * ([buttonSize] square), a [gap] between them. The cover is as wide as the primary button, and everything else in the left
 * column (the tabs and their content) is no wider than the whole row ([leftWidth]).
 */
internal class StorePageGeometry private constructor(
    val layout: StorePageLayout,
    val installWidth: Float,
    val buttonSize: Float,
    val gap: Float,
    /** Stacked: the top of the page left to the game's art when there is no cover to show it. */
    val artReveal: Float,
) {
    /** The cover is exactly as wide as the primary button. */
    val coverWidth: Float get() = installWidth

    /** The primary button, the two square buttons and the gaps between them: the widest the left column's content gets. */
    val leftWidth: Float get() = installWidth + 2f * (buttonSize + gap)

    companion object {
        /** The square buttons' edge and the gap between buttons, on the 412 dp phone scale. */
        const val BUTTON_SIZE = 46f
        const val GAP = 8f

        /** Side by side: the primary button's share of the window's width, held between [INSTALL_MIN] and [INSTALL_MAX] (phone-scale dp). */
        const val INSTALL_SHARE = 0.28f
        const val INSTALL_MIN = 150f
        const val INSTALL_MAX = 340f

        /** Side by side: the least room kept free between the left column and the cover (phone-scale dp). */
        const val FREE_GAP_MIN = 24f

        /** Stacked: the widest the column gets, so a tablet in portrait does not stretch it (phone-scale dp). */
        const val STACKED_MAX = 520f

        /** Stacked without a cover: the share of the window's height left to the art above the name. */
        const val STACKED_ART_SHARE = 0.28f

        /**
         * The page in a window [pageWidth] by [pageHeight] dp. [scale] is the screen's size factor ([ScreenScale.factor]) the
         * phone-scale constants follow; [sideMargin] is the room from the window's edge to the action row (and to the cover).
         */
        fun of(pageWidth: Float, pageHeight: Float, scale: Float, sideMargin: Float): StorePageGeometry {
            val layout = storePageLayoutFor(pageWidth, pageHeight)
            val button = BUTTON_SIZE * scale
            val gap = GAP * scale
            val icons = 2f * (button + gap)
            val inner = pageWidth - 2f * sideMargin
            // Side by side: never so wide that the left column and the cover meet, even on a small window.
            val install = when (layout) {
                StorePageLayout.SIDE_BY_SIDE -> (pageWidth * INSTALL_SHARE)
                    .coerceIn(INSTALL_MIN * scale, INSTALL_MAX * scale)
                    .coerceAtMost((inner - icons - FREE_GAP_MIN * scale) / 2f)
                StorePageLayout.STACKED -> inner.coerceAtMost(STACKED_MAX * scale) - icons
            }.coerceAtLeast(0f)
            val reveal = if (layout == StorePageLayout.STACKED) pageHeight * STACKED_ART_SHARE else 0f
            return StorePageGeometry(layout, install, button, gap, reveal)
        }
    }
}

/** The room from the window's side to a game's page, the cover and the action row on it. */
internal val STORE_PAGE_EDGE = 12.dp

/** [StorePageGeometry] for a page [pageWidth] by [pageHeight], [sideMargin] in from the window's edge to the action row. */
@Composable
internal fun rememberStorePageGeometry(pageWidth: Dp, pageHeight: Dp, sideMargin: Dp): StorePageGeometry {
    val scale = rememberScreenScale().factor
    return remember(pageWidth, pageHeight, sideMargin, scale) {
        StorePageGeometry.of(pageWidth.value, pageHeight.value, scale, sideMargin.value)
    }
}

/**
 * The game's page every store shares, in two layouts ([StorePageGeometry.layout]).
 *
 * Side by side: the game's landscape cover with only the game's name under it is fixed on the right; the left column scrolls and
 * holds the [header], the action row, then [content]. Stacked: one column that scrolls as a whole, with the cover on top, the name
 * under it, the action row, then [content]. Without a cover ([coverUrls] empty, or none of them loads) the right side stays empty
 * and the name moves to the top of the left column.
 *
 * The action row is [primary], [settings] (null when the page has none) and [downloads], in that order: each is given the size it
 * must have (the primary button's width and the square buttons' edge). [header] and [content] get the whole column, [edge] in
 * from the window's sides; [inset] is the horizontal padding [content] already carries itself, so the row and the name line up
 * with it. The scroll area holds all of it, so the highlight's follow-the-focus scroll keeps its room (see xmbRevealRoom).
 */
@Composable
internal fun StorePage(
    geometry: StorePageGeometry,
    coverUrls: List<String>,
    coverShape: Shape,
    header: @Composable () -> Unit,
    name: @Composable () -> Unit,
    primary: @Composable (Modifier) -> Unit,
    settings: (@Composable (Modifier) -> Unit)?,
    downloads: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    edge: Dp = STORE_PAGE_EDGE,
    inset: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Moves on to the next url when one fails; a new list starts over. No "loaded" flag: the image reports only what it shows.
    var attempt by remember(coverUrls) { mutableIntStateOf(0) }
    val coverUrl = coverUrls.getOrNull(attempt)
    val coverWidth = geometry.coverWidth.dp
    val columnWidth = geometry.leftWidth.dp + inset * 2
    val sideBySide = geometry.layout == StorePageLayout.SIDE_BY_SIDE

    val actions: @Composable () -> Unit = {
        Row(
            Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(geometry.gap.dp),
        ) {
            primary(Modifier.width(geometry.installWidth.dp).height(geometry.buttonSize.dp))
            if (settings != null) settings(Modifier.size(geometry.buttonSize.dp))
            downloads(Modifier.size(geometry.buttonSize.dp))
        }
    }

    if (sideBySide) {
        Row(modifier.fillMaxSize().padding(start = edge, end = edge, top = 6.dp, bottom = 14.dp)) {
            Column(Modifier.width(columnWidth).fillMaxHeight().verticalScroll(rememberScrollState())) {
                header()
                if (coverUrl == null) InsetBox(inset, name)
                InsetBox(inset, actions)
                content()
            }
            Spacer(Modifier.weight(1f))
            if (coverUrl != null) {
                Column(Modifier.width(coverWidth)) {
                    StoreCover(coverUrl, coverWidth, coverShape, onError = { attempt += 1 })
                    Box(Modifier.padding(top = 8.dp)) { name() }
                }
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = edge, end = edge, top = 6.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.width(columnWidth)) {
                header()
                if (coverUrl != null) {
                    StoreCover(
                        coverUrl, coverWidth, coverShape, onError = { attempt += 1 },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    )
                    Box(Modifier.align(Alignment.CenterHorizontally).width(coverWidth).padding(top = 8.dp)) { name() }
                } else {
                    Spacer(Modifier.height(geometry.artReveal.dp))
                    InsetBox(inset, name)
                }
                InsetBox(inset, actions)
                content()
            }
        }
    }
}

/** [piece] [inset] in from the column's sides. */
@Composable
private fun InsetBox(inset: Dp, piece: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = inset)) { piece() }
}

/**
 * One small square button of a game's page, the Settings and the Downloads button alike: a [shape] filled with [fill] and
 * outlined with [line], on the XMB highlight like the rest of the page ([storeFocus]). [entry] marks the button the page
 * focuses on open.
 */
@Composable
internal fun StoreActionTile(
    onClick: () -> Unit,
    shape: Shape,
    fill: Color,
    line: Color,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .storeFocus(shape, onClick, entry = entry)
            .clip(shape)
            .background(fill)
            .border(1.dp, line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
