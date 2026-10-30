package com.winlator.star.ui.deck

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.LocalDeckDensity
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The XMB on-screen keyboard (controller first, touch too), laid out like the Steam Deck's:
// full-width flat keys, shifted symbols small above the main one, no separate symbols page.
//
//   X Backspace (held repeats)   Y Space   L1/R1 cursor left/right   L2 held = Shift   L3 = Caps toggle
//   R2 or Start = the field's action (Search, Done, Go, Send, Next, or Enter in a multi-line field)
//   D-pad moves over the keys, A types the focused key, B closes
//
// The panel always sits on the bottom edge and is PANEL_HEIGHT of the screen tall, with the same margin on both sides,
// whatever page or field it opened from: its window covers the whole screen and the panel is placed in it by share.
// The buttons are read from the dialog's own key events (a separate window, so DeckInput never sees them). L2 and R2
// also arrive as analog axes on pads that report only that; those come through the window's motion dispatch (pressed
// past 0.6, released below 0.3, the same hysteresis as DeckInput.onMotion).

private const val PANEL_HEIGHT = 0.5f
private const val PANEL_SIDE_MARGIN = 0.055f
private const val PREVIEW_SHARE = 0.14f
private const val PREVIEW_BEFORE = 40
private const val PREVIEW_SPAN = 90
private const val TRIGGER_DOWN = 0.6f
private const val TRIGGER_UP = 0.3f
private const val REPEAT_DELAY_MS = 380L
private const val REPEAT_EVERY_MS = 55L
private const val KEYS_PER_ROW = 15f

/** What a key does. */
private enum class Role {
    CHAR, TAB, BACKSPACE, CAPS, ACTION, SHIFT, SPACE, LEFT, RIGHT, PASTE, SYSTEM, CLOSE, BLANK
}

/** One key: [base] is typed unshifted, [shifted] with Shift (or the capital for a [letter]). */
@Immutable
private class Cap(
    val role: Role,
    val weight: Float = 1f,
    val base: String = "",
    val shifted: String = base,
    val letter: Boolean = false,
    val firstFocus: Boolean = false,
)

private fun sym(base: String, shifted: String, weight: Float = 1f) = Cap(Role.CHAR, weight, base, shifted)

private fun letters(chars: String, focusOn: Char? = null): List<Cap> = chars.map {
    Cap(Role.CHAR, 1f, it.toString(), it.uppercase(), letter = true, firstFocus = it == focusOn)
}

// Row weights add up to 15 so the rows end on the same edge.
private val QWERTY_ROWS: List<List<Cap>> = listOf(
    listOf(
        sym("`", "~"), sym("1", "!"), sym("2", "@"), sym("3", "#"), sym("4", "$"), sym("5", "%"), sym("6", "^"),
        sym("7", "&"), sym("8", "*"), sym("9", "("), sym("0", ")"), sym("-", "_"), sym("=", "+"),
        Cap(Role.BACKSPACE, 2f),
    ),
    listOf(Cap(Role.TAB, 1.5f)) + letters("qwertyuiop") + listOf(sym("[", "{"), sym("]", "}"), sym("\\", "|", 1.5f)),
    listOf(Cap(Role.CAPS, 1.75f)) + letters("asdfghjkl", focusOn = 'g') +
        listOf(sym(";", ":"), sym("'", "\""), Cap(Role.ACTION, 2.25f)),
    listOf(Cap(Role.SHIFT, 2.5f)) + letters("zxcvbnm") + listOf(sym(",", "<"), sym(".", ">"), sym("/", "?"), Cap(Role.SHIFT, 2.5f)),
    listOf(
        Cap(Role.SYSTEM, 1.6f), Cap(Role.CLOSE, 1.4f), Cap(Role.LEFT, 1.3f), Cap(Role.SPACE, 7f),
        Cap(Role.RIGHT, 1.3f), Cap(Role.PASTE, 2.4f),
    ),
)

/** Digit pad for number, phone and decimal fields; [extra] is the "." or "+" key (a blank when null). */
private fun numberRows(extra: String?): List<List<Cap>> = listOf(
    listOf(sym("1", "1"), sym("2", "2"), sym("3", "3"), Cap(Role.BACKSPACE)),
    listOf(sym("4", "4"), Cap(Role.CHAR, 1f, "5", "5", firstFocus = true), sym("6", "6"), Cap(Role.LEFT)),
    listOf(sym("7", "7"), sym("8", "8"), sym("9", "9"), Cap(Role.RIGHT)),
    listOf(if (extra != null) sym(extra, extra) else Cap(Role.BLANK), sym("0", "0"), Cap(Role.SYSTEM), Cap(Role.ACTION)),
)

/** Modifier and caps state, shared between the keys and the window's key dispatch. */
@Stable
private class KeyboardState {
    var shiftKey by mutableStateOf(false)
    var shiftAxis by mutableStateOf(false)
    var shiftOnce by mutableStateOf(false)
    var caps by mutableStateOf(false)
    var rightTriggerDown = false

    val shift: Boolean get() = shiftKey || shiftAxis || shiftOnce

    /** Letters come out capital when Shift and Caps differ. */
    val upper: Boolean get() = shift != caps
}

/** What every key needs to draw itself, rebuilt when the modifiers change. [unit] is the size text and icons are measured in. */
@Immutable
private class KeyContext(
    val shift: Boolean,
    val upper: Boolean,
    val caps: Boolean,
    val roomy: Boolean,
    val actionLabel: String,
    val tabEnabled: Boolean,
    val scale: ScreenScale,
    val unit: Dp,
)

private fun capText(cap: Cap, state: KeyboardState): String =
    if (cap.letter) (if (state.upper) cap.shifted else cap.base) else (if (state.shift) cap.shifted else cap.base)

/**
 * The XMB keyboard: a bottom panel in its own dialog window, so it gets the controller's keys and the page behind it
 * stays visible. Everything typed goes through [session]; [onClose] hides the panel (B, tapping outside, the hide key,
 * or after the field's action), [onSystemKeyboard] is the key that hands typing over to Android's own keyboard.
 */
@Composable
internal fun XmbKeyboardPanel(
    session: XmbKeyboardSession,
    onClose: () -> Unit,
    onSystemKeyboard: () -> Unit,
) {
    val type = session.imeOptions.keyboardType
    val numeric = type == KeyboardType.Number || type == KeyboardType.Phone ||
        type == KeyboardType.Decimal || type == KeyboardType.NumberPassword
    val state = remember { KeyboardState() }
    val clipboard = LocalClipboardManager.current
    val singleLine = session.imeOptions.singleLine
    val imeAction = session.imeOptions.imeAction
    val newlineAction = !singleLine && (imeAction == ImeAction.Default || imeAction == ImeAction.None)

    // Drawn at the Deck style's size on every page, so the panel looks the same wherever it opens from.
    val host = LocalDensity.current
    val density = LocalDeckDensity.current ?: Density(host.density * DECK_UI_SCALE, host.fontScale)

    val currentClose by rememberUpdatedState(onClose)
    val currentSystem by rememberUpdatedState(onSystemKeyboard)

    val perform: (Role, Cap?) -> Unit = { role, cap ->
        when (role) {
            Role.CHAR -> if (cap != null) {
                session.commit(capText(cap, state))
                state.shiftOnce = false
            }
            Role.SPACE -> {
                session.commit(" ")
                state.shiftOnce = false
            }
            Role.TAB -> if (!singleLine) session.commit("\t")
            Role.BACKSPACE -> session.backspace()
            Role.CAPS -> state.caps = !state.caps
            Role.SHIFT -> state.shiftOnce = !state.shiftOnce
            Role.LEFT -> session.moveCursor(-1)
            Role.RIGHT -> session.moveCursor(1)
            Role.ACTION -> if (newlineAction) {
                session.commit("\n")
            } else {
                session.performAction()
                if (imeAction != ImeAction.Next && imeAction != ImeAction.Previous) currentClose()
            }
            Role.PASTE -> clipboard.getText()?.text?.let { pasted ->
                session.commit(if (singleLine) pasted.replace("\r", "").replace('\n', ' ') else pasted)
            }
            Role.SYSTEM -> currentSystem()
            Role.CLOSE -> currentClose()
            Role.BLANK -> Unit
        }
        Unit
    }
    val latestPerform by rememberUpdatedState(perform)

    /** The controller's own buttons; true when the key is taken (also its release, so no half-handled press leaks out). */
    fun handleButton(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        return when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { state.shiftKey = down; true }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> { if (first) state.caps = !state.caps; true }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> true
            KeyEvent.KEYCODE_BUTTON_X -> { if (down) latestPerform(Role.BACKSPACE, null); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { if (first) latestPerform(Role.SPACE, null); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (down) latestPerform(Role.LEFT, null); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (down) latestPerform(Role.RIGHT, null); true }
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START -> { if (first) latestPerform(Role.ACTION, null); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                if (e.action == KeyEvent.ACTION_UP) latestPerform(Role.CLOSE, null)
                true
            }
            else -> false
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        DeckDialogBars()
        // The window covers the whole screen, edge to edge and clear of insets, with no dim: the panel is placed by share.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        // The analog triggers (pads with no L2/R2 buttons) only arrive as motion events on the window.
        DisposableEffect(dialogWindow) {
            val w = dialogWindow
            val original = w?.callback
            if (w == null || original == null) return@DisposableEffect onDispose {}
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setGravity(android.view.Gravity.BOTTOM)
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            val wrapper = object : Window.Callback by original {
                override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                    handleTriggers(event, state) { latestPerform(Role.ACTION, null) }
                    return original.dispatchGenericMotionEvent(event)
                }
            }
            w.callback = wrapper
            onDispose {
                w.callback = original
                // An analog trigger still held as the panel closes (R2 pressed for Done) must not act on the page behind.
                if (state.shiftAxis || state.rightTriggerDown) XmbTriggerGuard.arm()
            }
        }
        CompositionLocalProvider(LocalDensity provides density, LocalDeckDensity provides density) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { handleButton(it.nativeKeyEvent) }
                    .pointerInput(Unit) { detectTapGestures { currentClose() } },
            ) {
                val scale = remember(maxWidth, maxHeight) { ScreenScale(maxWidth.value, maxHeight.value) }
                val cs = MaterialTheme.colorScheme
                val roomy = maxWidth >= 560.dp
                val rows = remember(numeric, type) {
                    if (numeric) numberRows(when (type) { KeyboardType.Decimal -> "."; KeyboardType.Phone -> "+"; else -> null })
                    else QWERTY_ROWS
                }
                val panelWidth = maxWidth * (1f - 2f * PANEL_SIDE_MARGIN)
                val pad = scale.dp(8f)
                val gap = scale.dp(4f)
                val inner = maxHeight * PANEL_HEIGHT - pad * 2
                val previewHeight = inner * PREVIEW_SHARE
                val keyHeight = (inner - previewHeight - gap) / rows.size
                // Text and icons follow the smaller of the key's height and its share of the row's width.
                val unit = min(keyHeight.value, (panelWidth.value - pad.value * 2) / KEYS_PER_ROW * 1.35f).dp
                val context = KeyContext(
                    shift = state.shift,
                    upper = state.upper,
                    caps = state.caps,
                    roomy = roomy,
                    actionLabel = actionLabel(imeAction, newlineAction),
                    tabEnabled = !singleLine,
                    scale = scale,
                    unit = unit,
                )
                val shape = RoundedCornerShape(topStart = scale.dp(18f), topEnd = scale.dp(18f))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .width(panelWidth)
                        .height(maxHeight * PANEL_HEIGHT)
                        .clip(shape)
                        .background(cs.background.copy(alpha = 0.97f))
                        .border(1.dp, deckLine(), shape)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(pad),
                ) {
                    PreviewStrip(session, scale, previewHeight, unit)
                    Spacer(Modifier.height(gap))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(if (numeric) Modifier.widthIn(max = scale.w(0.6f, max = 460.dp)) else Modifier),
                    ) {
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                row.forEach { cap ->
                                    KeyCap(
                                        cap = cap,
                                        context = context,
                                        requester = if (cap.firstFocus) firstFocus else null,
                                        onPress = { latestPerform(cap.role, cap) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tracks the analog triggers: L2 held is Shift, R2 pressed is the field's action. */
private fun handleTriggers(ev: MotionEvent, state: KeyboardState, onAction: () -> Unit) {
    if (ev.action != MotionEvent.ACTION_MOVE) return
    if ((ev.source and android.view.InputDevice.SOURCE_JOYSTICK) != android.view.InputDevice.SOURCE_JOYSTICK) return
    val left = max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
    val right = max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
    state.shiftAxis = if (state.shiftAxis) left > TRIGGER_UP else left > TRIGGER_DOWN
    val rightNow = if (state.rightTriggerDown) right > TRIGGER_UP else right > TRIGGER_DOWN
    if (rightNow && !state.rightTriggerDown) onAction()
    state.rightTriggerDown = rightNow
}

private fun actionLabel(action: ImeAction, newline: Boolean): String = when {
    newline -> "Enter"
    action == ImeAction.Search -> "Search"
    action == ImeAction.Go -> "Go"
    action == ImeAction.Send -> "Send"
    action == ImeAction.Next -> "Next"
    action == ImeAction.Previous -> "Prev"
    else -> "Done"
}

/** Key text in one family and weight everywhere, with no font padding so it centres exactly where it is put. */
@Composable
private fun KeyText(text: String, size: TextUnit, color: Color, weight: FontWeight, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontFamily = NunitoSansFamily,
        fontWeight = weight,
        fontSize = size,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
        style = TextStyle(
            lineHeight = size,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

/** [unit] (a dp size) as sp, so text sized from a key follows the same density as the key. */
@Composable
private fun Dp.asSp(factor: Float): TextUnit = with(LocalDensity.current) { (this@asSp * factor).toSp() }

/** The text being typed with its cursor or selection, so it stays visible even when the field is behind the panel. */
@Composable
private fun PreviewStrip(session: XmbKeyboardSession, scale: ScreenScale, height: Dp, unit: Dp) {
    val cs = MaterialTheme.colorScheme
    val value = session.value
    val type = session.imeOptions.keyboardType
    val masked = type == KeyboardType.Password || type == KeyboardType.NumberPassword
    val shown = remember(value.text, masked) {
        if (masked) "•".repeat(value.text.length) else value.text.replace('\n', '↵').replace('\t', '→')
    }
    val selStart = value.selection.min.coerceIn(0, shown.length)
    val selEnd = value.selection.max.coerceIn(0, shown.length)
    val from = (selEnd - PREVIEW_BEFORE).coerceAtLeast(0)
    val to = (from + PREVIEW_SPAN).coerceAtMost(shown.length)
    val text = buildAnnotatedString {
        if (from > 0) append("…")
        val selFrom = max(selStart, from)
        append(shown.substring(from, selFrom))
        if (selStart == selEnd) {
            withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold)) { append("|") }
        } else {
            withStyle(SpanStyle(background = cs.primary.copy(alpha = 0.35f))) { append(shown.substring(selFrom, selEnd)) }
        }
        append(shown.substring(selEnd, to))
        if (to < shown.length) append("…")
    }
    val shape = RoundedCornerShape(scale.dp(8f))
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, deckLine(), shape)
            .padding(horizontal = scale.dp(12f)),
    ) {
        Text(
            text = text,
            color = cs.onSurface,
            fontFamily = NunitoSansFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (height * 0.45f).asSp(1f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun RowScope.KeyCap(
    cap: Cap,
    context: KeyContext,
    requester: FocusRequester?,
    onPress: () -> Unit,
) {
    if (cap.role == Role.BLANK) {
        Spacer(Modifier.weight(cap.weight))
        return
    }
    val cs = MaterialTheme.colorScheme
    val scale = context.scale
    val shape = RoundedCornerShape(scale.dp(7f))
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val currentPress by rememberUpdatedState(onPress)
    val repeats = cap.role == Role.BACKSPACE || cap.role == Role.LEFT || cap.role == Role.RIGHT
    val active = (cap.role == Role.CAPS && context.caps) || (cap.role == Role.SHIFT && context.shift)
    val plain = cap.role == Role.CHAR || cap.role == Role.SPACE
    val fill = when {
        cap.role == Role.ACTION -> cs.primary
        pressed -> cs.primary.copy(alpha = 0.45f)
        active -> cs.primary.copy(alpha = 0.28f)
        plain -> cs.surfaceContainerHigh
        else -> cs.surfaceContainerHighest
    }
    val ink = when {
        cap.role == Role.ACTION -> cs.onPrimary
        pressed || active -> cs.primary
        cap.role == Role.TAB && !context.tabEnabled -> cs.onSurface.copy(alpha = 0.4f)
        else -> cs.onSurface
    }
    Box(Modifier.weight(cap.weight).fillMaxHeight().zIndex(if (focused) 2f else 0f).padding(scale.dp(2.5f))) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { focused = it.isFocused }
                .deckFocusRing(shape, scaleTo = 1.1f)
                .onKeyEvent { event ->
                    val confirm = event.key == Key.ButtonA || event.key == Key.Enter ||
                        event.key == Key.NumPadEnter || event.key == Key.DirectionCenter
                    if (confirm && event.type == KeyEventType.KeyDown) {
                        if (repeats || event.nativeKeyEvent.repeatCount == 0) currentPress()
                    }
                    confirm
                }
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .focusable()
                .clip(shape)
                .background(fill, shape)
                .pointerInput(cap.role) {
                    detectTapGestures(onPress = {
                        pressed = true
                        currentPress()
                        if (repeats) {
                            coroutineScope {
                                val repeater = launch {
                                    delay(REPEAT_DELAY_MS)
                                    while (true) {
                                        currentPress()
                                        delay(REPEAT_EVERY_MS)
                                    }
                                }
                                tryAwaitRelease()
                                repeater.cancel()
                            }
                        } else {
                            tryAwaitRelease()
                        }
                        pressed = false
                    })
                },
        ) {
            KeyLabel(cap, context, ink)
        }
    }
}

@Composable
private fun KeyLabel(cap: Cap, context: KeyContext, ink: Color) {
    val glyphs = context.roomy
    when (cap.role) {
        Role.CHAR -> CharLabel(cap, context, ink)
        Role.BACKSPACE -> IconLabel(Icons.AutoMirrored.Filled.Backspace, "Backspace", null, "X", GlyphKind.X, glyphs, ink, context)
        Role.TAB -> IconLabel(null, null, "Tab", null, GlyphKind.PLAIN, false, ink, context)
        Role.CAPS -> IconLabel(null, null, "Caps", "L3", GlyphKind.PLAIN, glyphs, ink, context)
        Role.ACTION -> IconLabel(null, null, context.actionLabel, "R2", GlyphKind.BUMPER, glyphs, ink, context)
        Role.SHIFT -> IconLabel(Icons.Filled.ArrowUpward, "Shift", if (glyphs) "Shift" else null, "L2", GlyphKind.BUMPER, glyphs, ink, context)
        Role.SPACE -> IconLabel(Icons.Filled.SpaceBar, "Space", null, "Y", GlyphKind.Y, glyphs, ink, context)
        Role.LEFT -> IconLabel(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Cursor left", null, "L1", GlyphKind.BUMPER, glyphs, ink, context)
        Role.RIGHT -> IconLabel(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Cursor right", null, "R1", GlyphKind.BUMPER, glyphs, ink, context)
        Role.PASTE -> IconLabel(Icons.Filled.ContentPaste, "Paste", if (glyphs) "Paste" else null, null, GlyphKind.PLAIN, false, ink, context)
        Role.SYSTEM -> IconLabel(Icons.Filled.Keyboard, "Android keyboard", null, null, GlyphKind.PLAIN, false, ink, context)
        Role.CLOSE -> IconLabel(Icons.Filled.KeyboardArrowDown, "Hide keyboard", null, null, GlyphKind.PLAIN, false, ink, context)
        Role.BLANK -> Unit
    }
}

/**
 * A character key: the main character centred, its other case or shifted symbol small above it. Both sit at the same
 * place on every key, set from the key's size, so nothing touches the key's edge or changes with the page.
 */
@Composable
private fun CharLabel(cap: Cap, context: KeyContext, ink: Color) {
    val unit = context.unit
    val main = if (cap.letter) (if (context.upper) cap.shifted else cap.base) else (if (context.shift) cap.shifted else cap.base)
    val hint = if (cap.letter || cap.shifted == cap.base) null else (if (context.shift) cap.base else cap.shifted)
    Box(Modifier.fillMaxSize()) {
        if (hint != null) {
            KeyText(hint, unit.asSp(0.24f), ink.copy(alpha = 0.55f), FontWeight.Normal, Modifier.align(Alignment.TopCenter).padding(top = unit * 0.14f))
        }
        KeyText(
            main, unit.asSp(if (hint != null) 0.4f else 0.5f), ink, FontWeight.Normal,
            Modifier.align(Alignment.Center).padding(top = if (hint != null) unit * 0.2f else 0.dp),
        )
    }
}

/** An icon and/or a word with an optional controller glyph beside it. */
@Composable
private fun IconLabel(
    icon: ImageVector?,
    description: String?,
    label: String?,
    glyph: String?,
    glyphKind: GlyphKind,
    showGlyph: Boolean,
    ink: Color,
    context: KeyContext,
) {
    val unit = context.unit
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, contentDescription = description, tint = ink, modifier = Modifier.size(unit * 0.5f))
        if (icon != null && label != null) Spacer(Modifier.size(unit * 0.08f))
        if (label != null) KeyText(label, unit.asSp(0.3f), ink, FontWeight.Bold)
        if (glyph != null && showGlyph) {
            Spacer(Modifier.size(unit * 0.1f))
            KeyGlyph(glyph, glyphKind, unit)
        }
    }
}

/** A controller button glyph sized from the key, so it always fits inside it. */
@Composable
private fun KeyGlyph(text: String, kind: GlyphKind, unit: Dp) {
    val cs = MaterialTheme.colorScheme
    val round = kind == GlyphKind.X || kind == GlyphKind.Y
    val side = unit * 0.5f
    val shape = if (round) CircleShape else RoundedCornerShape(side * 0.28f)
    val fill = when (kind) {
        GlyphKind.BUMPER -> cs.surfaceContainerHigh.copy(alpha = 0.7f)
        GlyphKind.X, GlyphKind.Y -> cs.surfaceVariant
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(side)
            .widthIn(min = if (round) side else side * 1.3f)
            .background(fill, shape)
            .border(1.dp, cs.onSurfaceVariant.copy(alpha = 0.6f), shape)
            .padding(horizontal = side * 0.18f),
    ) {
        KeyText(text, unit.asSp(0.26f), cs.onSurface, FontWeight.ExtraBold)
    }
}

/**
 * After the panel closes with an analog trigger still held (R2 pressed for Done, or L2 held as Shift), the page behind it
 * would see that trigger cross its press threshold and act on it as a fresh press (switch tab). Until the triggers are let
 * go, the pages' trigger readers ([DeckInput.onMotion], StoreXmbInput.onMotion) skip them.
 */
internal object XmbTriggerGuard {
    private const val EXPIRES_MS = 3000L

    @Volatile
    private var armedAt = 0L

    fun arm() {
        armedAt = android.os.SystemClock.uptimeMillis()
    }

    /** True while a trigger held when the panel closed is still down: the page must ignore this motion event's triggers. */
    fun holding(left: Float, right: Float): Boolean {
        if (armedAt == 0L) return false
        if (android.os.SystemClock.uptimeMillis() - armedAt > EXPIRES_MS || (left <= TRIGGER_UP && right <= TRIGGER_UP)) {
            armedAt = 0L
            return false
        }
        return true
    }
}
