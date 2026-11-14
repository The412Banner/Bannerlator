package com.winlator.star.ui.deck

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.MenuAction
import com.winlator.star.ui.simple.SimpleActionSheet
import com.winlator.star.ui.simple.SimpleLine
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimplePanelBrush
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong
import com.winlator.star.ui.simple.simpleDepth
import kotlinx.coroutines.delay

// Lab's in-game kit on top of the Deck in-game widgets (DeckInGameWidgets.kt): the pieces the Lab-drawn panels and
// pop-ups need that the Deck pages never had (a list pick, a help sheet, a text field, a pop-up frame). Only the Lab
// in-game menu and the Lab pop-ups use them; the plain Deck and XMB looks keep their own.

/**
 * The Lab look for a window of its own (the in-game pop-ups the dialog host shows): the Lab dark or light colours over
 * the app theme, the Deck type and Lab's language direction, the same wrapping the in-game menu's view gets.
 */
@Composable
internal fun LabInGameTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = if (com.winlator.star.ui.theme.AppThemeState.labLight) com.winlator.star.ui.simple.labLightScheme(base) else base
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        DeckTheme { com.winlator.star.ui.simple.LabDirection(content) }
    }
}

/**
 * A Lab pop-up over the game: a Lab panel with its title, an accent bar and a close button, B or a tap outside closes
 * it. [scroll] wraps [content] in a vertical scroll; leave it off for content with its own lazy list, which then gets
 * the panel's height ([tall]). Focus starts on the first control inside [content], else on the close button.
 */
@Composable
internal fun LabInGameDialog(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    maxWidth: Dp = 560.dp,
    tall: Boolean = false,
    scroll: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val contentFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.winlator.star.sound.DialogSound()
        DeckTheme {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth(0.94f)
                    .then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier.heightIn(max = 640.dp))
                    .simpleDepth(high = true)
                    .background(SimplePanelBrush, SimpleShape)
                    .border(1.dp, SimpleLine, SimpleShape)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (actions != null) {
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                    }
                    Spacer(Modifier.width(8.dp))
                    IgButton(null, Icons.Outlined.Close, onDismiss, modifier = Modifier.focusRequester(closeFocus))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(30.dp).height(3.dp).background(accent))
                Spacer(Modifier.height(10.dp))
                val body = Modifier.fillMaxWidth().then(if (tall || !scroll) Modifier.weight(1f, fill = tall) else Modifier.weight(1f, fill = false))
                if (scroll) {
                    Column(body.verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().focusRequester(contentFocus), content = content)
                    }
                } else {
                    Column(body.focusRequester(contentFocus), content = content)
                }
                if (footer != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        content = footer,
                    )
                }
            }
            LaunchedEffect(Unit) {
                delay(80)
                runCatching { closeFocus.requestFocus() }
                runCatching { contentFocus.requestFocus() }
            }
        }
    }
}

/** A read-only message in a Lab sheet with one Close (a help text, a "how it works"). */
@Composable
internal fun LabInfoSheet(title: String, message: String, onDismiss: () -> Unit) {
    SimpleActionSheet(title = title, actions = listOf(MenuAction(L("Close"), onDismiss)), onDismiss = onDismiss, message = message) { it.run() }
}

/** A small "?" that opens [text] in a Lab sheet (the Classic drawer's help dialogs). */
@Composable
internal fun IgHelpButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IgButton(null, Icons.AutoMirrored.Outlined.HelpOutline, { open = true })
    if (open) LabInfoSheet(title, text) { open = false }
}

/** [IgHelpButton] with the help text from a string resource (help_scaling_mode, help_rumble_motors …). */
@Composable
internal fun IgHelpButton(title: String, @StringRes textRes: Int) = IgHelpButton(title, stringResource(textRes))

/**
 * A choice from a list as one button (the Classic drawer's dropdown fields): it shows the current choice, and A or a
 * tap opens the list in a Lab sheet with the current one ticked; picking one runs [onPick] with its index.
 */
@Composable
internal fun IgPickButton(
    title: String,
    options: List<String>,
    selected: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.getOrNull(selected) ?: L("Choose…")
    IgButton("$label  ▾", null, { open = true }, modifier = modifier, enabled = enabled)
    if (open) {
        SimpleActionSheet(
            title = title,
            actions = options.mapIndexed { i, o -> MenuAction(if (i == selected) "$o  ✓" else o, { onPick(i) }) },
            onDismiss = { open = false },
        ) { a ->
            open = false
            a.run()
        }
    }
}

/** A setting row whose control is an [IgPickButton]. */
@Composable
internal fun IgPickRow(
    title: String,
    options: List<String>,
    selected: Int,
    hint: String? = null,
    scopes: List<SaveScope> = emptyList(),
    note: String? = null,
    enabled: Boolean = true,
    onPick: (Int) -> Unit,
) {
    IgRow(title, hint = hint, scopes = scopes, note = note, enabled = enabled) {
        IgPickButton(title, options, selected, enabled = enabled, onPick = onPick)
    }
}

/** An on/off setting: [IgRow] with an [IgSwitch]. */
@Composable
internal fun IgToggleRow(
    title: String,
    checked: Boolean,
    hint: String? = null,
    scopes: List<SaveScope> = emptyList(),
    note: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    IgRow(title, hint = hint, scopes = scopes, note = note, enabled = enabled) { IgSwitch(checked, enabled = enabled, onChange = onChange) }
}

/** A slider setting: [IgRow] with an [IgSlider] (d-pad left / right steps it by [step] and commits). */
@Composable
internal fun IgSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    hint: String? = null,
    scopes: List<SaveScope> = emptyList(),
    note: String? = null,
    enabled: Boolean = true,
) {
    IgRow(title, hint = hint, scopes = scopes, note = note, enabled = enabled, wideControl = true) {
        IgSlider(value = value, range = range, step = step, enabled = enabled, format = format, onChange = onChange, onFinished = onFinished)
    }
}

/** A short line inside a card under a row (a reason, a status, a warning when [error]). */
@Composable
internal fun IgNote(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        color = if (error) cs.error else cs.onSurfaceVariant,
        fontFamily = NunitoSansFamily,
        fontWeight = if (error) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/** A small status label with an optional coloured dot (the frame generation engine, a renderer). */
@Composable
internal fun IgBadge(text: String, dot: Color? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(cs.surfaceContainerHigh.copy(alpha = igRaised(0.8f)), deckShape(3.dp))
            .border(1.dp, deckLineAt(0.4f), deckShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = cs.onSurface, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1)
    }
}

/** A Lab text field: the Lab card fill, an accent outline while it has focus, [placeholder] while empty. */
@Composable
internal fun IgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    monospace: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(cs.primary),
        textStyle = TextStyle(
            color = SimpleStrong,
            fontSize = 15.sp,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else NunitoSansFamily,
        ),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(com.winlator.star.ui.simple.SimpleCard, SimpleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) cs.primary else SimpleLine, SimpleShape)
            .heightIn(min = 42.dp)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, color = SimpleMuted, fontSize = 15.sp, maxLines = 1)
                inner()
            }
        },
    )
}
