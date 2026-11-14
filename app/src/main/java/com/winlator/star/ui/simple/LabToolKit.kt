package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Lab's dialogs for the tools pages (file manager, logs, saves): a Lab panel with a title, Lab rows the controller can
// reach (A runs one, B closes), Lab text fields and Lab buttons.

/** A Lab panel in a dialog: the title, then [content] (scrolls when long). */
@Composable
internal fun LabDialog(title: String, onDismiss: () -> Unit, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = if (wide) 680.dp else 520.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            content()
        }
    }
}

/** A row of a Lab dialog: a label (and a second line), a check mark when [selected], a switch when [toggle] is set. */
@Composable
internal fun LabDialogRow(
    label: String,
    sub: String? = null,
    selected: Boolean = false,
    toggle: Boolean? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .labTile(onClick = { if (enabled) onClick() }, requester = requester)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else SimpleStrong, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, color = SimpleMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Text("✓", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (toggle != null) {
            Spacer(Modifier.width(10.dp))
            LabSwitch(toggle)
        }
    }
}

/** A small heading inside a Lab dialog. */
@Composable
internal fun LabDialogHeading(text: String) {
    Text(text.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

/** A label and a value on one line (properties). */
@Composable
internal fun LabPropLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Text(value, color = SimpleStrong, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** The buttons at the bottom of a Lab dialog, right-aligned. */
@Composable
internal fun LabDialogButtons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** Lab's text field: a Lab card with the accent outline while it has the focus. Done on the keyboard runs [onDone]. */
@Composable
internal fun LabField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    singleLine: Boolean = true,
    suffix: String? = null,
    requester: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(SimpleCard, SimpleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else SimpleLine, SimpleShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = TextStyle(color = SimpleStrong, fontSize = 15.sp),
            cursorBrush = SolidColor(accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = SimpleDim, fontSize = 15.sp)
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
        )
        if (suffix != null) Text(suffix, color = SimpleMuted, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Lab's one-line prompt (a name, a password, a search): the field takes the focus, Done on the keyboard or [confirm]
 * keeps it when [valid], Cancel or B leaves it. [onChange] sees every keystroke (a live search). [extra] goes under
 * the field (a format choice).
 */
@Composable
internal fun LabTextPrompt(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    message: String? = null,
    error: String? = null,
    password: Boolean = false,
    multiline: Boolean = false,
    placeholder: String? = null,
    suffix: String? = null,
    valid: (String) -> Boolean = { it.isNotBlank() },
    onChange: ((String) -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    onConfirm: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { field.requestFocus() }
    }
    val ok = valid(v)
    LabDialog(title, onDismiss) {
        if (!message.isNullOrBlank()) Text(message, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
        if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        LabField(
            value = v,
            onValueChange = { v = it; onChange?.invoke(it) },
            placeholder = placeholder,
            password = password,
            singleLine = !multiline,
            suffix = suffix,
            requester = field,
            onDone = { if (ok) onConfirm(if (password) v else v.trim()) },
        )
        extra?.invoke(this)
        LabDialogButtons {
            LabButton(L("Cancel"), onClick = onDismiss)
            LabButton(confirm, primary = true, modifier = Modifier.alpha(if (ok) 1f else 0.45f)) { if (ok) onConfirm(if (password) v else v.trim()) }
        }
    }
}

/** A name for a file or folder: not blank, no slash, not "." or "..". */
internal fun labValidName(name: String): Boolean = name.isNotBlank() && !name.contains('/') && name.trim() != "." && name.trim() != ".."

/**
 * A long text to read (help, an explanation, a report) in a Lab dialog; [mono] for a report. The text takes the focus
 * first: Up / Down scroll it, Down at its end steps onto Close. Close or B leaves it.
 */
@Composable
internal fun LabReadDialog(title: String, body: String, onDismiss: () -> Unit, intro: String? = null, mono: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    val textFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { textFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 680.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
            if (!intro.isNullOrBlank()) Text(intro, color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .border(1.dp, if (focused) accent.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent, SimpleShape)
                    .focusRequester(textFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        val k = ev.nativeKeyEvent
                        if (k.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (k.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (scroll.value < scroll.maxValue) { scope.launch { scroll.animateScrollBy(220f) }; true } else false
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> if (scroll.value > 0) { scope.launch { scroll.animateScrollBy(-220f) }; true } else false
                            else -> false
                        }
                    }
                    .focusable()
                    .verticalScroll(scroll)
                    .padding(4.dp),
            ) {
                Text(
                    body, color = SimpleInk, fontSize = if (mono) 11.sp else 14.sp, lineHeight = if (mono) 14.sp else 20.sp,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                )
            }
            LabDialogButtons { LabButton(L("Close"), primary = true, onClick = onDismiss) }
        }
    }
}
