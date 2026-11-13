package com.winlator.star.ui.deck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.FeatureFlags
import com.winlator.star.store.InGameFriendsSource
import com.winlator.star.ui.FriendsContent
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.deck.DeckInGameState.MenuTab
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.LabInGameIcon
import com.winlator.star.ui.simple.LabPicture
import com.winlator.star.ui.simple.loadLabShortcut

// The XMB mode's in-game menu: Lab's one drawer (Quick first, then every page, picked from an icon strip, Pause and Exit in
// the footer) dressed in the XMB's glass look. XServerDisplayActivity's key plumbing and DeckInGameState's Lab state machine
// (labQuick, selectLabQuick / selectMenuTab, L1 / R1, X, B) drive it unchanged; DeckInGameOverlay shows it instead of the Deck's
// quick menu + game menu while DeckInGameState.xmbDrawer is on, and every page is the Deck / Lab page itself.

/** The drawer's width in dp on a screen [screenDp] wide: all of a compact (phone portrait) screen, else 46% of it, held to 420..600 dp (Lab's width). */
internal fun xmbInGameDrawerWidth(screenDp: Float, compact: Boolean): Float =
    if (compact) screenDp else (screenDp * 0.46f).coerceIn(minOf(420f, screenDp), 600f)

/** Under this width (dp) the pages stack a row's control under its text, as on a phone, instead of leaving it beside the text. */
internal const val XMB_DRAWER_STACK_BELOW_DP = 560f

internal fun xmbInGameDrawerStacksRows(drawerDp: Float): Boolean = drawerDp < XMB_DRAWER_STACK_BELOW_DP

/** Under this drawer height (dp) the chrome shrinks to its short-screen sizes. */
internal const val XMB_DRAWER_SHORT_BELOW_DP = 360f

/** Under this drawer width (dp) the footer buttons are icon-only; at or over it they keep their text. */
internal const val XMB_DRAWER_ICON_ONLY_BELOW_DP = 360f

/**
 * The drawer's chrome sizes in dp (the header, the page strip, the page title, the footer), so the page keeps the rest of the
 * height. Header, title and buttons are minimum heights: larger text or font scale grows them rather than clipping.
 */
internal class XmbDrawerMetrics(
    val headerDp: Float,
    val iconDp: Float,
    val nameSp: Float,
    val chipHeightDp: Float,
    val chipWidthDp: Float,
    val chipIconDp: Float,
    val stripPadDp: Float,
    val titleDp: Float,
    val titleSp: Float,
    val buttonDp: Float,
    val buttonSp: Float,
    val padDp: Float,
    val gapDp: Float,
    /** How far the scrolling page fades into the chrome above and below it; also the page's own padding at those edges (not chrome). */
    val fadeDp: Float,
    val footerTopDp: Float,
    val footerBottomDp: Float,
) {
    /** The pill's own height: its chips and the padding around them. */
    val stripDp: Float get() = chipHeightDp + 2f * stripPadDp

    /** Everything that is not the page, top to bottom. */
    val chromeDp: Float
        get() = headerDp + gapDp + stripDp + titleDp + footerTopDp + buttonDp + footerBottomDp
}

/** The chrome sizes for a drawer [heightDp] tall: slim from [XMB_DRAWER_SHORT_BELOW_DP] up (122 dp of chrome), slimmer under it (97 dp). */
internal fun xmbInGameDrawerMetrics(heightDp: Float): XmbDrawerMetrics =
    if (heightDp >= XMB_DRAWER_SHORT_BELOW_DP) {
        XmbDrawerMetrics(
            headerDp = 28f, iconDp = 24f, nameSp = 15f, chipHeightDp = 30f, chipWidthDp = 36f, chipIconDp = 18f, stripPadDp = 2f,
            titleDp = 20f, titleSp = 16f, buttonDp = 34f, buttonSp = 13f, padDp = 10f, gapDp = 0f, fadeDp = 18f,
            footerTopDp = 2f, footerBottomDp = 4f,
        )
    } else {
        XmbDrawerMetrics(
            headerDp = 24f, iconDp = 20f, nameSp = 13f, chipHeightDp = 24f, chipWidthDp = 32f, chipIconDp = 16f, stripPadDp = 1f,
            titleDp = 16f, titleSp = 14f, buttonDp = 28f, buttonSp = 12f, padDp = 8f, gapDp = 0f, fadeDp = 10f,
            footerTopDp = 1f, footerBottomDp = 2f,
        )
    }

/**
 * Fades the top and bottom [fadeDp] of the viewport out (an offscreen layer masked with a gradient). [top] and [bottom] are 0..1,
 * read while drawing, so the fade animates without recomposing; 0 leaves that edge untouched.
 */
private fun Modifier.xmbEdgeFade(fadeDp: Float, top: () -> Float, bottom: () -> Float): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val t = top()
            val b = bottom()
            val len = minOf(fadeDp.dp.toPx(), size.height / 2f)
            if ((t > 0f || b > 0f) && len > 0f) {
                val f = len / size.height
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 1f - t),
                        f to Color.Black,
                        1f - f to Color.Black,
                        1f to Color.Black.copy(alpha = 1f - b),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/** How much of the game's art shows through the glass (it is laid over the opaque glass base), and where the fade to the soft edge starts. */
private const val XMB_DRAWER_ART_ALPHA = 0.34f
private const val XMB_DRAWER_ART_FADE_FROM = 0.62f

/** The glass without art is a touch see-through, so the game just shows behind it. */
private const val XMB_DRAWER_GLASS_ALPHA = 0.95f

/**
 * The in-game drawer of the XMB mode, [widthDp] wide (see [xmbInGameDrawerWidth]). It brings its own glass theme and highlight
 * host, so the cards are glass and the focused control is marked by the one gliding accent outline, as on the XMB pages.
 */
@Composable
internal fun XmbInGameMenu(modifier: Modifier, widthDp: Float, onExit: () -> Unit) {
    XmbGlassTheme(enabled = true) {
        XmbHighlightHost(enabled = true, modifier = modifier) {
            XmbInGameMenuBody(widthDp, onExit)
        }
    }
}

@Composable
private fun BoxScope.XmbInGameMenuBody(widthDp: Float, onExit: () -> Unit) {
    val state = XServerDrawerState
    val tab by DeckInGameState.menuTab.collectAsState()
    val quick by DeckInGameState.labQuick.collectAsState()
    val panel by DeckInGameState.panel.collectAsState()
    val title by DeckInGameState.gameTitle.collectAsState()
    val isPaused by state.isPaused.collectAsState()
    val kind by DeckInGameState.sessionKind.collectAsState()
    val tvConnected by state.tvConnected.collectAsState()
    val castSupported by state.castSupported.collectAsState()
    val friendsSource by InGameFriendsSource.state.collectAsState()
    val mods by state.mods.collectAsState()
    val tabs = DeckInGameState.availableMenuTabs(kind, FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported), friendsSource.tabVisible, mods.isNotEmpty())
    val shown = if (tab in tabs) tab else tabs.first()
    val cs = MaterialTheme.colorScheme
    val glass = rememberXmbGlass()
    val stripFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    FocusOnRequest(panel, pageFocus, fallback = stripFocus)
    LaunchedEffect(Unit) { InGameFriendsSource.poke() }

    // The running game's pictures (Game details > Artwork > In-game menu, else the app pages'): its art faded behind the
    // glass, its icon and logo at the top in place of the name.
    val context = LocalContext.current
    val gamePath by DeckInGameState.gameShortcutPath.collectAsState()
    val game = remember(gamePath) { gamePath?.let { loadLabShortcut(context, it) } }
    LaunchedEffect(game) { game?.let { LabArt.resolve(context, it) } }
    val artTick = LabArt.tick
    val art = remember(game, artTick) { game?.let { LabArt.inGameBackground(it) }.orEmpty() }
    val logo = remember(game, artTick) {
        game?.let { g -> LabArt.inGameModels(g, "logo").map { LabArt.Pic(it) } }.orEmpty()
    }
    val hasArt = art.isNotEmpty()

    // The panel: frosted glass flush with the screen's left edge, rounded on the game side, with the glass cards' soft shadow
    // and the bright hairline along its top edge. Over the game's art the glass is its opaque base with the art laid under the
    // cards, fading out toward the right so the edge stays soft.
    val shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
    Box(Modifier.matchParentSize().xmbGlassCard(shape, fill = glass.base.copy(alpha = if (hasArt) 1f else XMB_DRAWER_GLASS_ALPHA))) {
        if (hasArt) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = XMB_DRAWER_ART_ALPHA
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Black, XMB_DRAWER_ART_FADE_FROM to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) { LabPicture(art, Modifier.fillMaxSize()) }
        }
    }

    // Chips and buttons are a little see-through, so the glass reads under them; a narrow drawer stacks each row's control under its text.
    val stack = xmbInGameDrawerStacksRows(widthDp)
    CompositionLocalProvider(
        LocalIgRaisedAlpha provides 0.6f,
        LocalIgCompact provides (LocalIgCompact.current || stack),
    ) {
        // The chrome (header, strip, title, footer) is sized from the real height, so the page keeps all the rest.
        BoxWithConstraints(Modifier.matchParentSize()) {
            val m = remember(maxHeight) { xmbInGameDrawerMetrics(maxHeight.value) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(start = xmbFrame()),
            ) {
                // One slim row: the game's icon, its logo (else its name) and the status at the right end.
                val hasIcon = game != null && LabArt.pick(game, "igicon") != "none"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = m.headerDp.dp).padding(start = m.padDp.dp, end = (m.padDp + 4f).dp),
                ) {
                    if (hasIcon && game != null) LabInGameIcon(game, Modifier.size(m.iconDp.dp))
                    val name: @Composable () -> Unit = {
                        Text(
                            title.ifEmpty { L("In game") }, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold,
                            fontSize = m.nameSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (logo.isEmpty()) name()
                    else LabPicture(
                        logo, Modifier.weight(1f).height(m.iconDp.dp),
                        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, onNone = name,
                    )
                    DeviceStatus()
                }
                Spacer(Modifier.height(m.gapDp.dp))

                // The pages: Quick, then the full menu's, as icons on a glass strip between the bumper glyphs (the open one tinted; its
                // name is the page title just below).
                val stripShape = RoundedCornerShape(14.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = m.padDp.dp),
                ) {
                    DeckGlyph("L1", GlyphKind.BUMPER, Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .xmbGlassCard(stripShape)
                            .border(1.dp, deckLine(), stripShape)
                            .horizontalScroll(rememberScrollState())
                            .padding(m.stripPadDp.dp),
                    ) {
                        XmbDrawerTabChip(Icons.Outlined.Bolt, L("Quick"), quick, stripFocus, pageFocus, m) { DeckInGameState.selectLabQuick() }
                        tabs.forEach { t ->
                            XmbDrawerTabChip(menuTabIcon(t), menuTabTitle(t), !quick && t == shown, stripFocus, pageFocus, m) { DeckInGameState.selectMenuTab(t) }
                        }
                    }
                    DeckGlyph("R1", GlyphKind.BUMPER, Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 16.dp))
                }

                // The open page's name alone; its description is gone to leave the options the room.
                val pageTitle = if (quick) L("Quick") else menuTabTitle(shown)
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = m.titleDp.dp).padding(horizontal = (m.padDp + 2f).dp),
                ) {
                    Text(
                        pageTitle, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold,
                        fontSize = m.titleSp.sp, lineHeight = (m.titleSp + 2f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!quick && shown == MenuTab.FRIENDS) {
                    // The roster owns its scrolling (a LazyColumn), so it gets the space without a scroll wrapper.
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = m.padDp.dp, vertical = 4.dp).focusRequester(pageFocus)) {
                        IgHostedCard { FriendsContent(state) }
                    }
                } else {
                    // Each page starts at its top rather than at the previous page's scroll offset.
                    val scroll = key(quick, shown) { rememberScrollState() }
                    // The page fades into the title above and the footer below, only on an edge with more page beyond it. The fade is
                    // on the viewport (before the scroll), and the padding at both ends is as long as the fade, so the first and
                    // last rows rest clear of it.
                    val topFade = animateFloatAsState(if (scroll.canScrollBackward) 1f else 0f, label = "xmbDrawerTopFade")
                    val bottomFade = animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, label = "xmbDrawerBottomFade")
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .xmbEdgeFade(m.fadeDp, { topFade.value }, { bottomFade.value })
                            .igPageScroll(scroll)
                            .padding(horizontal = m.padDp.dp, vertical = m.fadeDp.dp),
                    ) {
                        // Inside the scroll container, so the request lands on the page's first control, not the scroll group.
                        Column(Modifier.fillMaxWidth().focusRequester(pageFocus)) {
                            if (quick) QuickControls(kind)
                            else when (shown) {
                                MenuTab.SCREEN -> ScreenPage(state)
                                MenuTab.PERFORMANCE -> PerformancePage(state)
                                MenuTab.EFFECTS -> EffectsPage(state, kind)
                                MenuTab.OVERLAY -> OverlayPage(state)
                                MenuTab.CONTROLS -> ControlsPage(state, kind)
                                MenuTab.AUDIO -> AudioPage(state)
                                MenuTab.STEAM -> SteamPage(state)
                                MenuTab.TV -> TvPage(state)
                                MenuTab.MODS -> ModsPage(state, mods)
                                MenuTab.TOOLS -> ToolsPage(state, kind)
                                MenuTab.FRIENDS -> Unit
                            }
                        }
                    }
                }

                // Footer: Pause / Resume and Exit (which asks first) straight on the glass, no bar of their own. B closes the drawer
                // through XServerDisplayActivity's key routing and X flips Quick / pages through DeckInGameState, so no hints here.
                val labels = widthDp >= XMB_DRAWER_ICON_ONLY_BELOW_DP
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = m.padDp.dp, end = (m.padDp + 2f).dp, top = m.footerTopDp.dp, bottom = m.footerBottomDp.dp),
                ) {
                    XmbDrawerButton(
                        if (isPaused) L("Resume") else L("Pause"), if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        labels, m.buttonDp, m.buttonSp, ::pauseAndClose, Modifier.weight(1f),
                    )
                    XmbDrawerButton(L("Exit game"), Icons.AutoMirrored.Outlined.Logout, labels, m.buttonDp, m.buttonSp, onExit, Modifier.weight(1f), danger = true)
                }
            }
        }
    }

    // The glass edge over everything, so the art never covers it.
    Box(Modifier.matchParentSize().border(1.dp, glass.frost.line, shape))
}

/**
 * A slim footer button drawn straight on the drawer's glass (the same card fill and hairline as the page cards, so it blends in);
 * [danger] (Exit) keeps the error border and a faint error tint. Without [showLabel] it is the icon alone.
 */
@Composable
private fun XmbDrawerButton(
    label: String,
    icon: ImageVector,
    showLabel: Boolean,
    heightDp: Float,
    textSp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(14.dp)
    val ink = if (danger) cs.error else cs.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .deckFocusRing(shape, scaleTo = 1.04f)
            .xmbGlassCard(shape)
            .then(if (danger) Modifier.background(cs.error.copy(alpha = 0.10f)) else Modifier)
            .border(1.dp, if (danger) cs.error.copy(alpha = 0.7f) else deckLineAt(0.5f), shape)
            .clickable(onClick = onClick)
            .heightIn(min = heightDp.dp)
            .padding(horizontal = 10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(16.dp))
        if (showLabel) Text(label, color = ink, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = textSp.sp, maxLines = 1)
    }
}

/**
 * One page chip of the drawer's strip: the page's icon alone, tinted accent while it is the open page. Focus is the XMB's
 * gliding outline with the icon turning accent under it, and D-pad down goes into the page.
 */
@Composable
private fun XmbDrawerTabChip(
    icon: ImageVector,
    label: String,
    sel: Boolean,
    stripFocus: FocusRequester,
    pageFocus: FocusRequester,
    m: XmbDrawerMetrics,
    onPick: () -> Unit,
) {
    val shape = deckShape(10.dp)
    DeckInk { item, cs ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(if (sel) Modifier.focusRequester(stripFocus) else Modifier)
                .focusProperties { down = pageFocus }
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .clip(shape)
                .background(if (sel) cs.primary.copy(alpha = 0.22f) else Color.Transparent)
                .clickable(onClick = onPick)
                .size(width = m.chipWidthDp.dp, height = m.chipHeightDp.dp),
        ) {
            Icon(icon, contentDescription = label, tint = if (sel) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(m.chipIconDp.dp))
        }
    }
}
