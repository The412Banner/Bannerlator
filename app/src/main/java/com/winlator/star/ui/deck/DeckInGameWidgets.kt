package com.winlator.star.ui.deck

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// Building blocks for the Deck in-game panels (quick menu + full game menu). Colours come only from MaterialTheme, so
// the user's preset and custom accent carry over, the same rule DeckTheme follows.

/** True on a narrow (portrait phone) window: rows stack their control under the text and the rail becomes a strip. */
internal val LocalIgCompact = compositionLocalOf { false }

/**
 * Where a change is written, shown on every live setting. [GAME] only applies when the game was launched from a
 * shortcut; without one the same code writes the container, so pages pick between them with [igGameScope].
 */
internal data class SaveScope(val label: String, val icon: ImageVector) {
    companion object {
        val GAME = SaveScope("Saved for this game", Icons.Outlined.SportsEsports)
        val CONTAINER = SaveScope("Saved for this container", Icons.Outlined.Inventory2)
        val ALL = SaveScope("Saved for all games", Icons.Outlined.Public)
        val SESSION = SaveScope("This session only", Icons.Outlined.Schedule)
        val LEGEND = listOf(GAME, CONTAINER, ALL, SESSION)

        /** A scope the four standard ones don't cover (a controls profile, a Proton install). */
        fun other(label: String) = SaveScope(label, Icons.Outlined.Save)
    }
}

/** "Saved for this game" when this session has a shortcut to write to, else "Saved for this container". */
@Composable
internal fun igGameScope(): SaveScope {
    val fromShortcut by DeckInGameState.launchedFromShortcut.collectAsState()
    return if (fromShortcut) SaveScope.GAME else SaveScope.CONTAINER
}

@Composable
internal fun IgScopeChip(scope: SaveScope) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHigh.copy(alpha = 0.8f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Icon(scope.icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(scope.label, color = cs.onSurfaceVariant, fontSize = 11.5.sp, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IgScopeChips(scopes: List<SaveScope>, modifier: Modifier = Modifier) {
    if (scopes.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        scopes.forEach { IgScopeChip(it) }
    }
}

/** Uppercase, letter-spaced group heading ("FRAME RATE"). */
@Composable
internal fun IgSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = NunitoSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A rounded card that groups rows, like the Deck home's shelves. */
@Composable
internal fun IgCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(deckCardFill())
            .border(1.dp, deckLine().copy(alpha = 0.5f), shape),
        content = content,
    )
}

/** A card that hosts one of the Classic drawer's own sections unchanged, with padding that suits the Deck spacing. */
@Composable
internal fun IgHostedCard(content: @Composable ColumnScope.() -> Unit) {
    IgCard { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), content = content) }
}

@Composable
internal fun IgDivider() = HorizontalDivider(color = deckLine().copy(alpha = 0.35f))

/**
 * One setting: title, a plain-language hint, where it saves, and its control. Wide windows put the control on the
 * right; compact ones stack it underneath. [wideControl] gives chip groups more room than a switch or a button needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IgRow(
    title: String,
    hint: String? = null,
    scopes: List<SaveScope> = emptyList(),
    note: String? = null,
    wideControl: Boolean = false,
    enabled: Boolean = true,
    control: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalIgCompact.current
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m.alpha(if (enabled) 1f else 0.55f)) {
            Text(title, color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (!hint.isNullOrEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(hint, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
            if (!note.isNullOrEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(note, color = cs.primary, fontFamily = NunitoSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 16.sp)
            }
            if (scopes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                IgScopeChips(scopes)
            }
        }
    }
    if (compact || control == null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            text(Modifier.fillMaxWidth())
            if (control != null) {
                Spacer(Modifier.height(10.dp))
                control()
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            text(Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = if (wideControl) Modifier.weight(1.25f) else Modifier,
            ) { control() }
        }
    }
}

/** Pill chips for a value picked from a short list (frame limit, overlay style). The chosen one gets an accent outline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> IgChips(
    options: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean = true,
    isEnabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalIgCompact.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (compact) Alignment.Start else Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (v, label) ->
            val sel = v == selected
            val on = enabled && isEnabled(v)
            val shape = RoundedCornerShape(50)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .deckFocusRing(shape)
                    .clip(shape)
                    .background(if (sel) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHigh)
                    .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else deckLine().copy(alpha = 0.4f), shape)
                    .clickable(enabled = on, role = Role.RadioButton) { onSelect(v) }
                    .heightIn(min = 40.dp)
                    .widthIn(min = 52.dp)
                    .padding(horizontal = 15.dp)
                    .alpha(if (on) 1f else 0.45f),
            ) {
                Text(label, color = if (sel) cs.primary else cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, maxLines = 1)
            }
        }
    }
}

/** A segmented control (Off / Fit / Stretch …): one track, the chosen option filled with the accent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> IgSegmented(
    options: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean = true,
    isEnabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val track = RoundedCornerShape(16.dp)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(track)
            .background(cs.surfaceContainerHigh.copy(alpha = 0.7f))
            .border(1.dp, deckLine().copy(alpha = 0.35f), track)
            .padding(5.dp),
    ) {
        options.forEach { (v, label) ->
            val sel = v == selected
            val on = enabled && isEnabled(v)
            val shape = RoundedCornerShape(12.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .deckFocusRing(shape)
                    .clip(shape)
                    .background(if (sel) cs.primary else Color.Transparent)
                    .clickable(enabled = on, role = Role.RadioButton) { onSelect(v) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 16.dp)
                    .alpha(if (on) 1f else 0.45f),
            ) {
                Text(label, color = if (sel) cs.onPrimary else cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, maxLines = 1)
            }
        }
    }
}

/** The Deck switch: a rounded track with a sliding knob, focusable and toggled with A. */
@Composable
internal fun IgSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val knobX by animateDpAsState(if (checked) 24.dp else 3.dp, label = "igSwitchKnob")
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .deckFocusRing(shape)
            .clip(shape)
            .background(if (checked) cs.primary else cs.surfaceContainerHighest)
            .border(1.dp, if (checked) cs.primary else deckLine().copy(alpha = 0.5f), shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .size(width = 52.dp, height = 30.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Box(
            Modifier
                .offset(x = knobX)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) cs.onPrimary else cs.onSurfaceVariant)
        )
    }
}

/** A Deck button with an optional leading icon and a trailing controller glyph. [danger] tints it with the error colour. */
@Composable
internal fun IgButton(
    label: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    glyph: String? = null,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val ink = when {
        primary -> cs.onPrimary
        danger -> cs.error
        else -> cs.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .deckFocusRing(shape, scaleTo = 1.04f)
            .clip(shape)
            .background(if (primary) cs.primary else cs.surfaceContainerHigh.copy(alpha = 0.85f))
            .then(if (primary) Modifier else Modifier.border(1.dp, if (danger) cs.error.copy(alpha = 0.7f) else deckLine().copy(alpha = 0.5f), shape))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = if (label == null) 13.dp else 18.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        if (icon != null) Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(20.dp))
        if (label != null) {
            Text(label, color = ink, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
        }
        if (glyph != null) DeckGlyph(glyph, GlyphKind.X)
    }
}

/**
 * A slider that a controller can drive too: with it focused, d-pad left/right steps it by [step] and commits at once.
 * Touch drags call [onChange] live and [onFinished] on release, the same split the Classic drawer's sliders use.
 */
@Composable
internal fun IgSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean = true,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 200.dp)) {
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = cs.primary,
                activeTrackColor = cs.primary,
                inactiveTrackColor = cs.surfaceContainerHighest,
            ),
            modifier = Modifier
                .weight(1f)
                .deckFocusRing(RoundedCornerShape(50), scaleTo = 1.02f)
                .onPreviewKeyEvent { ev ->
                    val dir = when (ev.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    if (dir == 0 || !enabled) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyDown) {
                        onChange((value + dir * step).coerceIn(range.start, range.endInclusive))
                        onFinished()
                    }
                    true
                },
        )
        Spacer(Modifier.width(12.dp))
        Text(format(value), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.widthIn(min = 44.dp))
    }
}

/** A read-only line of text inside a card (engine readouts, how-to hints). */
@Composable
internal fun IgInfo(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = NunitoSansFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier.padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/**
 * The Deck confirm card for destructive actions (exit the game, end a process). It is its own window, so the controller
 * reaches it directly; focus starts on the safe choice, and B or a tap outside dismisses it.
 */
@Composable
internal fun IgConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmIcon: ImageVector?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckTheme {
            val shape = RoundedCornerShape(24.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .padding(24.dp)
                    .clip(shape)
                    .background(cs.surface)
                    .border(1.dp, deckLine().copy(alpha = 0.5f), shape)
                    .padding(horizontal = 28.dp, vertical = 26.dp),
            ) {
                Text(title, color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                Text(body, color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    IgButton("Cancel", null, onDismiss, modifier = Modifier.weight(1f).focusRequester(cancelFocus))
                    IgButton(confirmLabel, confirmIcon, onConfirm, modifier = Modifier.weight(1f), danger = true)
                }
            }
            LaunchedEffect(Unit) {
                delay(80)
                runCatching { cancelFocus.requestFocus() }
            }
        }
    }
}
