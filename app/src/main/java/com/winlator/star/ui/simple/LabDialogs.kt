package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.delay

/** One button along the bottom of a [LabPanelDialog]. A disabled one stays in place, dimmed. */
internal class LabDialogButton(
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    /** Moves the controller focus here when asked (the recommended choice once it appears). */
    val requester: FocusRequester? = null,
)

/**
 * A Lab pop-up: the Lab panel with a title, a body that scrolls when it is long and the buttons along the bottom. The
 * controller moves between the buttons (and anything focusable in the body); A runs, B closes when [dismissable].
 * [initialFocus] takes the focus first when given, else the first enabled button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabPanelDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: List<LabDialogButton>,
    dismissable: Boolean = true,
    wide: Boolean = false,
    initialFocus: FocusRequester? = null,
    /** A tap outside closes it too (when [dismissable]). */
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { (initialFocus ?: first).requestFocus() }
    }
    Dialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissable, dismissOnClickOutside = dismissable && dismissOnOutside, usePlatformDefaultWidth = !wide),
    ) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .then(if (wide) Modifier.fillMaxWidth(0.92f).widthIn(max = 860.dp) else Modifier.widthIn(min = 300.dp, max = 560.dp))
                .heightIn(max = 660.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(16.dp),
        ) {
            Text(
                title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                content = content,
            )
            if (buttons.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                val firstEnabled = buttons.indexOfFirst { it.enabled }.coerceAtLeast(0)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    buttons.forEachIndexed { i, b -> LabDialogButtonView(b, b.requester ?: if (i == firstEnabled) first else null) }
                }
            }
        }
    }
}

@Composable
private fun LabDialogButtonView(b: LabDialogButton, requester: FocusRequester?) {
    val accent = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .background(if (b.primary) accent else SimpleCard, SimpleShape)
            .labTile(onClick = { if (b.enabled) b.onClick() }, requester = requester, onAccent = b.primary)
            .alpha(if (b.enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(b.label, color = if (b.primary) Color.White else if (b.danger) red else SimpleStrong, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Body text of a Lab pop-up. */
@Composable
internal fun LabDialogText(text: String, muted: Boolean = false, error: Boolean = false) {
    Text(
        text,
        color = when { error -> MaterialTheme.colorScheme.error; muted -> SimpleMuted; else -> SimpleInk },
        fontSize = if (muted) 13.sp else 14.sp,
    )
}

/** A small heading inside a Lab pop-up's body ("Changed", "Needs a component"). */
@Composable
internal fun LabDialogHeading(text: String) {
    Text(text.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 4.dp))
}

/** A busy line: the spinner and what is going on. */
@Composable
internal fun LabBusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, color = SimpleMuted, fontSize = 13.sp)
    }
}

/** A text field in a Lab pop-up (the Material field the other Lab text dialogs use). */
@Composable
internal fun LabTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    requester: FocusRequester? = null,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    placeholder: String? = null,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { p -> { Text(p) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 8,
        enabled = enabled,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
    )
}

/**
 * One text to type in a Lab pop-up: [confirm] keeps it (not while blank unless [allowBlank]), Cancel or B leaves it.
 * [maxLength] caps it (with the count under the field, as Classic's 500-character fields show it).
 */
@Composable
internal fun LabInputDialog(
    title: String,
    initial: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    multiline: Boolean = false,
    maxLength: Int = 0,
    placeholder: String? = null,
    allowBlank: Boolean = false,
    message: String? = null,
    onDone: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val ok = allowBlank || v.isNotBlank()
    LabPanelDialog(
        title = title,
        onDismiss = onDismiss,
        initialFocus = field,
        buttons = listOf(
            LabDialogButton(L("Cancel"), onDismiss),
            LabDialogButton(confirm, { if (ok) onDone(v) }, primary = true, enabled = ok),
        ),
    ) {
        if (!message.isNullOrBlank()) LabDialogText(message, muted = true)
        LabTextField(
            value = v,
            onValueChange = { if (maxLength <= 0 || it.length <= maxLength) v = it },
            label = label,
            requester = field,
            singleLine = !multiline,
            minLines = 3,
            placeholder = placeholder,
        )
        if (maxLength > 0) Text("${v.length}/$maxLength", color = SimpleDim, fontSize = 11.sp)
    }
}
