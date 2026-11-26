package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.deck.DeckFloatingHint
import com.winlator.star.ui.deck.GlyphKind
import com.winlator.star.ui.deck.NunitoSansFamily
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.XmbInk
import com.winlator.star.ui.deck.XmbPanelTheme
import com.winlator.star.ui.deck.rememberXmbGlass
import com.winlator.star.ui.deck.rememberXmbHighlightItem
import com.winlator.star.ui.deck.xmbHighlight
import com.winlator.star.ui.deck.xmbPanel
import com.winlator.star.ui.deck.xmbPopCard
import com.winlator.star.ui.deck.xmbPopFill
import kotlin.math.roundToInt

// A small kit of XMB dialogs: a frosted-glass frame sized for a landscape phone, rows that are XMB cards (each one a stop of the
// gliding highlight), big footer buttons and a progress card. Built for the XMB interface ONLY: callers branch on
// `rememberXmbHome()` and keep their stock AlertDialog for Classic, Lab and plain Deck.
//
//   XmbDialogFrame(title = "Select container", subtitle = "...", onDismiss = ::close,
//       footer = { XmbDialogButton("Cancel", ::close); XmbDialogButton("Add", ::add, primary = true) },
//   ) {
//       XmbOptionRow("Name", onClick = ..., subtitle = "...", icon = Icons.Filled.Folder, modifier = Modifier.xmbInitialFocus())
//       XmbCheckRow("Game", checked = on, onToggle = { on = !on })
//   }
//
// What the frame guarantees (the proportions the XMB dialogs share):
//  * never taller than 88% of the screen nor wider than 92% of it (640 dp, 760 dp for a `wide` list dialog), measured with a
//    BoxWithConstraints in the density the dialog is drawn at (the Deck draws its windows smaller), see [xmbDialogSize];
//  * a FIXED header (title, one-line subtitle) and a FIXED footer, only the body scrolls;
//  * on a short screen ([XmbDialogSize.compact]) the paddings shrink and the subtitle is dropped, rather than clipping;
//  * D-pad: Up / Down / Left / Right move between the rows and the footer buttons (one highlight stop each), A activates,
//    B closes (as Back does), all with the one gliding accent outline of the window.
// The window keeps calling DeckDialogBars() and DeckDialogScaled(), so the navigation bar stays hidden and the XMB keyboard
// works in a text field inside it.

// ---------------------------------------------------------------------------------------------------------------------
// Pure logic (unit-tested).
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The room a dialog may take, in dp of the density the dialog is drawn at: [width] of the panel, [maxHeight] it may grow to, and
 * [compact] for a short screen, where the frame tightens its paddings and drops the subtitle.
 */
internal data class XmbDialogSize(val width: Float, val maxHeight: Float, val compact: Boolean)

/** A dialog is never taller than this share of the screen... */
internal const val XMB_DIALOG_HEIGHT_SHARE = 0.88f

/** ...nor wider than this share of it... */
internal const val XMB_DIALOG_WIDTH_SHARE = 0.92f

/** ...and never wider than this many dp (a `wide` list / review dialog may use [XMB_DIALOG_MAX_WIDTH_WIDE]). */
internal const val XMB_DIALOG_MAX_WIDTH = 640f
internal const val XMB_DIALOG_MAX_WIDTH_WIDE = 760f

/**
 * Under this many dp of screen height the dialog is compact. The Deck draws its windows at 0.8 of the app's density, so 480 of
 * these dp are ~384 real dp: a phone in landscape (360 to 412 dp tall) is compact or about to be.
 */
internal const val XMB_DIALOG_COMPACT_BELOW = 480f

/** A dialog at least this wide (dp) lays its options out in two columns instead of one. */
internal const val XMB_OPTION_GRID_MIN_WIDTH = 480f

/** A dialog at least this wide (dp) shows the "B Back" hint in its footer (a narrower one needs the room for its buttons). */
internal const val XMB_BACK_HINT_MIN_WIDTH = 520f

/**
 * The size of a dialog on a [screenW] x [screenH] screen (dp). [wide]: a list / review dialog, which may use more of a wide
 * screen. The height is only a ceiling: the frame is as tall as its content, up to it.
 */
internal fun xmbDialogSize(screenW: Float, screenH: Float, wide: Boolean): XmbDialogSize {
    val cap = if (wide) XMB_DIALOG_MAX_WIDTH_WIDE else XMB_DIALOG_MAX_WIDTH
    val width = (screenW * XMB_DIALOG_WIDTH_SHARE).coerceAtMost(cap).coerceAtLeast(0f)
    val height = (screenH * XMB_DIALOG_HEIGHT_SHARE).coerceAtLeast(0f)
    return XmbDialogSize(width, height, screenH < XMB_DIALOG_COMPACT_BELOW)
}

/** How many columns a set of [count] options gets in a dialog [contentWidth] dp wide: two columns when there is room for them. */
internal fun xmbOptionColumns(contentWidth: Float, count: Int): Int =
    if (count >= 2 && contentWidth >= XMB_OPTION_GRID_MIN_WIDTH) 2 else 1

/** Whether the footer shows the "B Back" hint: only when the dialog can be closed, and is wide enough to spare the room. */
internal fun xmbShowBackHint(width: Float, dismissible: Boolean): Boolean = dismissible && width >= XMB_BACK_HINT_MIN_WIDTH

/** done / total as 0..1, or null (an indeterminate bar) while the total is not known. */
internal fun xmbProgressFraction(done: Int, total: Int): Float? =
    if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)

/** [fraction] (0..1) as a whole percent. */
internal fun xmbPercent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).roundToInt()

/** "1 game" / "3 games". */
internal fun xmbCountLabel(n: Int, noun: String): String = "$n $noun${if (n == 1) "" else "s"}"

/** The confirm button of a bulk add: "Add", "Add 3", or "Adding…" while the write runs. */
internal fun xmbAddLabel(count: Int, running: Boolean): String = when {
    running -> "Adding…"
    count <= 0 -> "Add"
    else -> "Add $count"
}

// ---------------------------------------------------------------------------------------------------------------------
// The frame.
// ---------------------------------------------------------------------------------------------------------------------

/** The size of the dialog frame around the composition (rows read [XmbDialogSize.compact] from it). Defaults to a roomy dialog. */
internal val LocalXmbDialogSize = staticCompositionLocalOf { XmbDialogSize(XMB_DIALOG_MAX_WIDTH, 400f, false) }

/** The Back / B key of a controller: closes the dialog. Key-down and key-up are both consumed; it acts on key-up. */
private fun Modifier.xmbDialogBack(dismissible: Boolean, onDismiss: () -> Unit): Modifier = this.onKeyEvent { k ->
    if (k.key != Key.ButtonB) {
        false
    } else {
        if (dismissible && k.type == KeyEventType.KeyUp) onDismiss()
        true
    }
}

/**
 * An XMB dialog: a glass panel in a window of its own, with a fixed header ([title], one-line [subtitle], dropped on a short
 * screen), a body that scrolls and a fixed footer ([footer]: [XmbDialogButton]s, right aligned).
 *
 * @param onDismiss Back, B and a tap outside call it (unless [dismissible] is false).
 * @param wide a list / review dialog: may be [XMB_DIALOG_MAX_WIDTH_WIDE] wide instead of [XMB_DIALOG_MAX_WIDTH].
 * @param dismissible false while something runs that must not be interrupted: Back, B and a tap outside do nothing.
 * @param dismissOnClickOutside false: a tap outside does nothing, though Back and B still close a [dismissible] dialog (a
 *   pre-flight that a stray tap must not cancel). Default true.
 * @param keepSubtitleCompact true: the [subtitle] stays on a short screen too (it carries something the dialog needs, not a
 *   hint). Default false: it is dropped there.
 * @param scrollBody true (the default): [body] is a column the frame scrolls. false: the body scrolls itself (a LazyColumn
 *   with `Modifier.weight(1f, fill = false)`), the frame only hands it the room that is left between header and footer.
 * @param footer the buttons; null for a dialog with none (a progress card that cannot be cancelled).
 * @param body the content. Read [LocalXmbDialogSize] for the measured size.
 */
@Composable
internal fun XmbDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    wide: Boolean = false,
    dismissible: Boolean = true,
    scrollBody: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    keepSubtitleCompact: Boolean = false,
    footer: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val onDismissNow by rememberUpdatedState(onDismiss)
    Dialog(
        onDismissRequest = { if (dismissible) onDismissNow() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible && dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        DeckDialogBars()
        DeckDialogScaled {
            XmbPanelTheme {
                BoxWithConstraints(contentAlignment = Alignment.Center) {
                    // Captured once, here: the measured screen the dialog is drawn in (the Deck draws a dialog's window smaller
                    // than the app, so these are dp of that density).
                    val screenW = if (maxWidth.value.isFinite()) maxWidth.value else XMB_DIALOG_MAX_WIDTH
                    val screenH = if (maxHeight.value.isFinite()) maxHeight.value else 400f
                    val size = remember(screenW, screenH, wide) { xmbDialogSize(screenW, screenH, wide) }
                    XmbDialogPanel(
                        size = size,
                        title = title,
                        subtitle = subtitle,
                        dismissible = dismissible,
                        scrollBody = scrollBody,
                        keepSubtitleCompact = keepSubtitleCompact,
                        onDismiss = { onDismissNow() },
                        modifier = modifier,
                        footer = footer,
                        body = body,
                    )
                }
            }
        }
    }
}

@Composable
private fun XmbDialogPanel(
    size: XmbDialogSize,
    title: String,
    subtitle: String?,
    dismissible: Boolean,
    scrollBody: Boolean,
    keepSubtitleCompact: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier,
    footer: (@Composable RowScope.() -> Unit)?,
    body: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val glass = rememberXmbGlass()
    val compact = size.compact
    val pad = if (compact) 14.dp else 20.dp
    val shape = RoundedCornerShape(if (compact) 20.dp else 26.dp)
    val showHint = xmbShowBackHint(size.width, dismissible)
    CompositionLocalProvider(LocalXmbDialogSize provides size) {
        Column(
            modifier
                .width(size.width.dp)
                .heightIn(max = size.maxHeight.dp)
                .xmbPanel(shape, cs.surfaceContainerHigh)
                .xmbDialogBack(dismissible, onDismiss),
        ) {
            // Fixed header.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = pad, end = pad, top = if (compact) 10.dp else 16.dp, bottom = if (compact) 8.dp else 10.dp),
            ) {
                Text(
                    title,
                    color = cs.onSurface,
                    fontFamily = SoraFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 18.sp else 21.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null && (!compact || keepSubtitleCompact)) {
                    Text(
                        subtitle,
                        color = cs.onSurfaceVariant,
                        fontFamily = NunitoSansFamily,
                        fontSize = 13.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(glass.frost.line))
            // The body: the only part that scrolls.
            val bodyBase = Modifier.weight(1f, fill = false).fillMaxWidth()
            Column(
                (if (scrollBody) bodyBase.verticalScroll(rememberScrollState()) else bodyBase)
                    .padding(horizontal = pad, vertical = if (compact) 6.dp else 10.dp),
                content = body,
            )
            // Fixed footer.
            if (footer != null || showHint) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(glass.frost.line))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pad, vertical = if (compact) 8.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    if (showHint) {
                        DeckFloatingHint("B", GlyphKind.Y, "Back", onDismiss, compact = true)
                        Spacer(Modifier.weight(1f))
                    }
                    footer?.invoke(this)
                }
            }
        }
    }
}

/**
 * Put it on the stop that should hold the controller's focus when its dialog opens (a row or a button; it goes BEFORE the
 * clickable, so pass it as the `modifier` of the kit's rows and buttons). Without any, the first D-pad press picks one.
 */
@Composable
internal fun Modifier.xmbInitialFocus(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        // After the first layout, when the target is attached; a stop that cannot take focus (disabled) is ignored.
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return this.focusRequester(requester)
}

// ---------------------------------------------------------------------------------------------------------------------
// Text.
// ---------------------------------------------------------------------------------------------------------------------

/** A row's title: bold, the theme's text colour (it turns accent while the row holds the highlight, see [XmbRowCard]). */
@Composable
internal fun XmbRowTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = NunitoSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.5.sp,
        lineHeight = 19.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A row's secondary line: muted (the glass's own secondary colour, readable on it), never below 12 sp. */
@Composable
internal fun XmbRowSubtitle(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    color: Color = Color.Unspecified,
) {
    Text(
        text,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        fontFamily = NunitoSansFamily,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A paragraph in a dialog body ([muted]: the secondary colour, [small]: 13.5 sp instead of 15). */
@Composable
internal fun XmbDialogText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, small: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        color = if (muted) cs.onSurfaceVariant else cs.onSurface,
        fontFamily = NunitoSansFamily,
        fontSize = if (small) 13.5.sp else 15.sp,
        lineHeight = if (small) 18.sp else 21.sp,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Rows.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One row: a glass card (the popup's raised fill, the bright top edge) that is ONE highlight stop of the window: the gliding
 * outline lands on it and its text turns accent while it holds it. [selected] tints it with the accent. Tap or A calls
 * [onClick]. A disabled row is dimmed and not focusable. [content] is laid out in a Row (12 dp apart) inside the card; its
 * texts should read `MaterialTheme.colorScheme` themselves (inside [XmbInk]) rather than capture a colour outside.
 *
 * [modifier] goes in front of everything (a `weight`, a size, [xmbInitialFocus]).
 */
@Composable
internal fun XmbRowCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalXmbDialogSize.current.compact
    val shape = RoundedCornerShape(14.dp)
    val item = rememberXmbHighlightItem()
    // Read outside the ink: the ink recolours the text this fill is checked against.
    val fill = xmbPopFill(tint = if (selected) cs.primary else null, tintAlpha = if (selected) 0.16f else 0f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .xmbHighlight(shape, item)
            .xmbPopCard(shape, fill, shadow = false)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        XmbInk(item) { content(this) }
    }
}

/** A rounded accent-tinted tile with an [icon] in it (the leading mark of an option row). */
@Composable
internal fun XmbIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(primary.copy(alpha = 0.18f)),
    ) {
        Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
    }
}

/**
 * An option to pick (the method picker, a container): an optional accent icon tile, a bold [title] over a muted [subtitle] (two
 * lines at most) and an optional [note] in the tertiary colour.
 */
@Composable
internal fun XmbOptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    note: String? = null,
) {
    XmbRowCard(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (icon != null) XmbIconTile(icon)
        Column(Modifier.weight(1f)) {
            XmbRowTitle(title)
            if (subtitle != null) XmbRowSubtitle(subtitle, maxLines = 2)
            if (note != null) XmbRowSubtitle(note, maxLines = 2, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/**
 * [count] options as a grid of [columns] columns, every row as tall as its tallest cell ([xmbOptionColumns] picks the columns
 * from the dialog's width: two in landscape, one in portrait). [cell] draws option `index` with the modifier it must take
 * (weight and height): pass it on to the [XmbOptionRow].
 */
@Composable
internal fun XmbOptionGrid(
    columns: Int,
    count: Int,
    modifier: Modifier = Modifier,
    cell: @Composable (index: Int, cellModifier: Modifier) -> Unit,
) {
    val cols = columns.coerceAtLeast(1)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowStart in 0 until count step cols) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (c in 0 until cols) {
                    val i = rowStart + c
                    if (i < count) cell(i, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The tick box (rounded square) or the radio ring of a [XmbCheckRow] / [XmbRadioRow], drawn in the accent. */
@Composable
internal fun XmbIndicator(checked: Boolean, radio: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = if (radio) CircleShape else RoundedCornerShape(7.dp)
    val on = cs.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .clip(shape)
            .background(if (checked && !radio) on else Color.Transparent)
            .border(2.dp, if (checked) on else cs.onSurfaceVariant, shape),
    ) {
        if (checked) {
            if (radio) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(on))
            } else {
                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun XmbToggleRow(
    radio: Boolean,
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    subtitle: String?,
    note: String?,
    noteColor: Color,
    enabled: Boolean,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
    below: (@Composable ColumnScope.() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            XmbRowCard(onClick = onToggle, modifier = Modifier.weight(1f).then(modifier), enabled = enabled, selected = checked) {
                XmbIndicator(checked, radio)
                leading?.invoke()
                Column(Modifier.weight(1f)) {
                    XmbRowTitle(title)
                    if (subtitle != null) XmbRowSubtitle(subtitle, maxLines = 1)
                    if (note != null) XmbRowSubtitle(note, maxLines = 2, color = noteColor)
                }
            }
            trailing?.invoke(this)
        }
        below?.invoke(this)
    }
}

/**
 * A row you tick: the check box, an optional [leading] picture (a cover, an app icon), the [title] with a [subtitle] and a third
 * [note] line ([noteColor]: a warning colour, say). A tick tints the card with the accent. [trailing] is placed beside the card
 * (a separate stop: a "Change" button), [below] under it (an expander). [modifier] reaches the card, the row's focus stop.
 */
@Composable
internal fun XmbCheckRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) = XmbToggleRow(false, title, checked, onToggle, modifier, subtitle, note, noteColor, enabled, leading, trailing, below)

/** [XmbCheckRow] for one-of-many choices: the radio ring, [selected] tints the card. A is "select this one". */
@Composable
internal fun XmbRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) = XmbToggleRow(true, title, selected, onSelect, modifier, subtitle, note, noteColor, enabled, leading, trailing, below)

// ---------------------------------------------------------------------------------------------------------------------
// Buttons.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A footer button, 48 dp tall and one highlight stop: [primary] is the accent-filled main action (Add 3, Save, Download), the
 * others are glass (Cancel, Skip, Browse). [danger] tints the text with the error colour. A disabled button is dimmed and not
 * focusable. [modifier] goes in front (a weight, [xmbInitialFocus]).
 */
@Composable
internal fun XmbDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val item = rememberXmbHighlightItem()
    val glassFill = xmbPopFill(small = true)
    // The outline of a filled primary button is drawn in the colour of its label: accent on accent would not show.
    val outline = if (primary) cs.onPrimary else Color.Unspecified
    val look = if (primary) Modifier.clip(shape).background(cs.primary) else Modifier.xmbPopCard(shape, glassFill, shadow = false)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .defaultMinSize(minWidth = 104.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .xmbHighlight(shape, item, tint = outline)
            .then(look)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        XmbInk(item) {
            val ink = when {
                primary -> MaterialTheme.colorScheme.onPrimary
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Text(text, color = ink, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** A choice chip (Games / All apps): a glass pill, accent-tinted while [selected], one highlight stop, 48 dp tall. */
@Composable
internal fun XmbChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val item = rememberXmbHighlightItem()
    val fill = xmbPopFill(small = true, tint = if (selected) cs.primary else null, tintAlpha = if (selected) 0.22f else 0f)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .xmbHighlight(shape, item)
            .xmbPopCard(shape, fill, shadow = false)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        XmbInk(item) {
            val scheme = MaterialTheme.colorScheme
            Text(
                text,
                color = if (selected) scheme.primary else scheme.onSurface,
                fontFamily = NunitoSansFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Progress.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A progress card for the body of a dialog: a bold [title], the bar and a muted [detail] line (a folder, where it goes).
 * [progress] 0..1 draws a determinate bar with the percent beside the title; null an indeterminate one. [trailing] replaces the
 * percent (a count: "3 / 12").
 */
@Composable
internal fun XmbProgressCard(
    title: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    detail: String? = null,
    trailing: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val fill = xmbPopFill()
    Column(
        modifier
            .fillMaxWidth()
            .xmbPopCard(shape, fill, shadow = false)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            XmbRowTitle(title, Modifier.weight(1f), maxLines = 1)
            val right = trailing ?: progress?.let { "${xmbPercent(it)}%" }
            if (right != null) {
                Text(right, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            }
        }
        val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        val track = cs.onSurface.copy(alpha = 0.14f)
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = barModifier, color = cs.primary, trackColor = track)
        } else {
            LinearProgressIndicator(modifier = barModifier, color = cs.primary, trackColor = track)
        }
        if (detail != null) XmbRowSubtitle(detail, maxLines = 2)
    }
}
