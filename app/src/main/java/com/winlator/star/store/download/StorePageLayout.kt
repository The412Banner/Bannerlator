package com.winlator.star.store.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
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
import com.winlator.star.ui.deck.xmbFrame

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
 * The page opens with a header band the height of the back button ([bandHeight]); everything else starts [bandGap] under it.
 * The action row is the primary button ([installWidth] wide), then the Settings and the Downloads button ([buttonSize] square,
 * a [gap] between them; [buttons] of them, one when the page has no Settings). Side by side, the pair of buttons sits on the
 * window's horizontal centre and the cover ends the page's right side: it is as wide as the primary button, so the gap from the
 * primary button to the pair ([primaryGap]) and the gap from the pair to the cover are equal. The left column (everything under
 * the action row) runs from the page's edge to the pair's far side ([leftWidth]), so it never reaches the cover.
 */
internal class StorePageGeometry private constructor(
    val layout: StorePageLayout,
    /** The room from the window's side to the page: the camera cut-out's, the same on both sides, and the page margin. */
    val edge: Float,
    /** The room from the window's top to the header band: the page margin below the status bar and the cut-out. */
    val top: Float,
    /** The page margin, the room kept clear under the scrolling content. */
    val margin: Float,
    val bandHeight: Float,
    val bandGap: Float,
    val installWidth: Float,
    val buttonSize: Float,
    val gap: Float,
    val buttons: Int,
    /** The gap between the primary button and the first of the square buttons. */
    val primaryGap: Float,
    /** Stacked: the top of the page left to the game's art when there is no cover to show it. */
    val artReveal: Float,
) {
    /** The cover is exactly as wide as the primary button. */
    val coverWidth: Float get() = installWidth

    /** The square buttons with the gaps between them. */
    val pairWidth: Float get() = buttons * buttonSize + (buttons - 1) * gap

    /** The left column: the primary button, the gap, the square buttons. Stacked, it is the whole column. */
    val leftWidth: Float get() = installWidth + primaryGap + pairWidth

    companion object {
        /** The page margin, the back button's edge and the gap under the band, on the 412 dp phone scale. */
        const val MARGIN = 20f
        const val BACK_SIZE = 40f
        const val BAND_GAP = 8f

        /** The square buttons' edge and the gap between buttons, on the 412 dp phone scale. */
        const val BUTTON_SIZE = 46f
        const val GAP = 8f

        /** Side by side: the primary button's share of the window's width, held between [INSTALL_MIN] and [INSTALL_MAX] (phone-scale dp). */
        const val INSTALL_SHARE = 0.34f
        const val INSTALL_MIN = 150f
        const val INSTALL_MAX = 400f

        /** Side by side: the least room kept free on each side of the square buttons (phone-scale dp). */
        const val FREE_GAP_MIN = 16f

        /** Stacked: the widest the column gets, so a tablet in portrait does not stretch it (phone-scale dp). */
        const val STACKED_MAX = 520f

        /** Stacked without a cover: the share of the window's height left to the art above the name. */
        const val STACKED_ART_SHARE = 0.28f

        /**
         * The page in a window [pageWidth] by [pageHeight] dp. [scale] is the screen's size factor ([ScreenScale.factor]) the
         * phone-scale constants follow; [safeSide] is the room the camera cut-out takes on the wider of the window's two sides
         * and [safeTop] the room the system bars and the cut-out take at the top; [buttons] is how many square buttons the
         * action row has.
         */
        fun of(pageWidth: Float, pageHeight: Float, scale: Float, safeSide: Float, safeTop: Float, buttons: Int): StorePageGeometry {
            val layout = storePageLayoutFor(pageWidth, pageHeight)
            val margin = MARGIN * scale
            val edge = safeSide + margin
            val button = BUTTON_SIZE * scale
            val gap = GAP * scale
            val pair = buttons * button + (buttons - 1) * gap
            val install: Float
            val primaryGap: Float
            when (layout) {
                StorePageLayout.SIDE_BY_SIDE -> {
                    // The pair is centred on the window, so what is left of the window's half between the edge and the pair
                    // is shared by the primary button and its gap; the cover mirrors both on the other side.
                    val half = pageWidth / 2f - pair / 2f - edge
                    install = (pageWidth * INSTALL_SHARE)
                        .coerceIn(INSTALL_MIN * scale, INSTALL_MAX * scale)
                        .coerceAtMost(half - FREE_GAP_MIN * scale)
                        .coerceAtLeast(0f)
                    primaryGap = (half - install).coerceAtLeast(0f)
                }
                StorePageLayout.STACKED -> {
                    install = ((pageWidth - 2f * edge).coerceAtMost(STACKED_MAX * scale) - buttons * (button + gap)).coerceAtLeast(0f)
                    primaryGap = gap
                }
            }
            val reveal = if (layout == StorePageLayout.STACKED) pageHeight * STACKED_ART_SHARE else 0f
            return StorePageGeometry(
                layout, edge, safeTop + margin, margin, BACK_SIZE * scale, BAND_GAP * scale,
                install, button, gap, buttons, primaryGap, reveal,
            )
        }
    }
}

/**
 * [StorePageGeometry] for a page [pageWidth] by [pageHeight] whose action row has a Settings button when [hasSettings]. The page
 * keeps clear of the camera cut-out and the system bars the window lays itself under.
 */
@Composable
internal fun rememberStorePageGeometry(pageWidth: Dp, pageHeight: Dp, hasSettings: Boolean): StorePageGeometry {
    val scale = rememberScreenScale().factor
    val safeSide = xmbFrame().value
    val safeTop = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding().value
    return remember(pageWidth, pageHeight, hasSettings, scale, safeSide, safeTop) {
        StorePageGeometry.of(pageWidth.value, pageHeight.value, scale, safeSide, safeTop, if (hasSettings) 2 else 1)
    }
}

/**
 * The game's page every store shares, in two layouts ([StorePageGeometry.layout]).
 *
 * Both open with the header band: the [back] button at its start, the [badge] at its end. Side by side, the action row ([primary],
 * [settings] (null when the page has none) and [downloads]) sits under the band with the game's landscape cover fixed on the right
 * at its level and only the game's name under it; the column under the action row scrolls and holds [content]. Stacked: one column
 * under the band that scrolls as a whole, with the cover on top, the name under it, the action row, then [content]. Without a cover
 * ([coverUrls] empty, or none of them loads) the right side stays empty and the name moves above the action row.
 *
 * Each of the action row's buttons is given the size it must have (the primary button's width and the square buttons' edge).
 * [content] gets the column made [inset] wider than the left column on each side: the horizontal padding [content] already
 * carries itself, so its text lines up with the action row. The scroll area holds all of it, so the highlight's follow-the-focus
 * scroll keeps its room (see xmbRevealRoom).
 */
@Composable
internal fun StorePage(
    geometry: StorePageGeometry,
    coverUrls: List<String>,
    coverShape: Shape,
    back: @Composable (Modifier) -> Unit,
    name: @Composable () -> Unit,
    primary: @Composable (Modifier) -> Unit,
    settings: (@Composable (Modifier) -> Unit)?,
    downloads: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    badge: @Composable () -> Unit = {},
    inset: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Moves on to the next url when one fails; a new list starts over. No "loaded" flag: the image reports only what it shows.
    var attempt by remember(coverUrls) { mutableIntStateOf(0) }
    val coverUrl = coverUrls.getOrNull(attempt)
    val coverWidth = geometry.coverWidth.dp
    val columnWidth = geometry.leftWidth.dp + inset * 2
    val edge = geometry.edge.dp
    val bandGap = geometry.bandGap.dp

    val band: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(geometry.bandHeight.dp), verticalAlignment = Alignment.CenterVertically) {
            back(Modifier.size(geometry.bandHeight.dp))
            Spacer(Modifier.weight(1f))
            badge()
        }
    }
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            primary(Modifier.width(geometry.installWidth.dp).height(geometry.buttonSize.dp))
            Spacer(Modifier.width(geometry.primaryGap.dp))
            if (settings != null) {
                settings(Modifier.size(geometry.buttonSize.dp))
                Spacer(Modifier.width(geometry.gap.dp))
            }
            downloads(Modifier.size(geometry.buttonSize.dp))
        }
    }

    if (geometry.layout == StorePageLayout.SIDE_BY_SIDE) {
        Box(modifier.fillMaxSize().padding(start = edge, end = edge, top = geometry.top.dp)) {
            Column(Modifier.fillMaxSize()) {
                band()
                Spacer(Modifier.height(bandGap))
                if (coverUrl == null) {
                    Box(Modifier.width(geometry.leftWidth.dp).padding(bottom = bandGap)) { name() }
                }
                actions()
                Spacer(Modifier.height(bandGap))
                Column(Modifier.weight(1f).width(columnWidth).offset(x = -inset).verticalScroll(rememberScrollState())) {
                    content()
                    Spacer(Modifier.height(geometry.margin.dp))
                }
            }
            if (coverUrl != null) {
                Column(Modifier.align(Alignment.TopEnd).padding(top = geometry.bandHeight.dp + bandGap).width(coverWidth)) {
                    StoreCover(coverUrl, coverWidth, coverShape, onError = { attempt += 1 })
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) { name() }
                }
            }
        }
    } else {
        Column(modifier.fillMaxSize().padding(start = edge, end = edge, top = geometry.top.dp)) {
            band()
            Spacer(Modifier.height(bandGap))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.width(columnWidth)) {
                    Column(Modifier.padding(horizontal = inset), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (coverUrl != null) {
                            StoreCover(coverUrl, coverWidth, coverShape, onError = { attempt += 1 })
                            Box(Modifier.width(coverWidth).padding(top = 8.dp, bottom = bandGap), contentAlignment = Alignment.Center) { name() }
                        } else {
                            Spacer(Modifier.height(geometry.artReveal.dp))
                            Box(Modifier.fillMaxWidth().padding(bottom = bandGap)) { name() }
                        }
                        Box(Modifier.fillMaxWidth()) { actions() }
                        Spacer(Modifier.height(bandGap))
                    }
                    content()
                    Spacer(Modifier.height(geometry.margin.dp))
                }
            }
        }
    }
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

/**
 * The back button of a game's page, in the header band, drawn as the page's other square buttons are ([StoreActionTile]); [tint]
 * colours the arrow. [entry] marks the button the page focuses on open.
 */
@Composable
internal fun StoreBackTile(
    onBack: () -> Unit,
    shape: Shape,
    fill: Color,
    line: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
) {
    StoreActionTile(onClick = onBack, shape = shape, fill = fill, line = line, modifier = modifier, entry = entry) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = tint)
    }
}
