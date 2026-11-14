package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.BuildConfig
import com.winlator.star.R
import com.winlator.star.core.UpdateManager
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The pop-ups of Lab's settings pages (LabSettingsPages.kt, LabPerformancePages.kt): an explanation, a read-and-accept gate,
// a busy notice, a text field and the About box, all on Lab's panel.

/** The activity behind [this] context (a page's context can be a wrapper around it). */
internal fun Context.labSetActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** "label  ✓" for the current choice in an action list (the sheet starts on it). */
internal fun labSetMark(label: String, on: Boolean) = if (on) "$label  ✓" else label

/** A Lab pop-up's panel. */
@Composable
internal fun LabSetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .widthIn(min = 300.dp, max = 540.dp)
            .simpleDepth(high = true)
            .background(SimplePanelBrush, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun LabDialogTitle(text: String, danger: Boolean = false) {
    Text(
        text,
        color = if (danger) MaterialTheme.colorScheme.error else SimpleStrong,
        fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp,
    )
}

/**
 * Up / Down on the controller scroll [state] while this text has the focus; at either end the press moves on to the
 * next control as usual. The focused text gets a faint outline.
 */
@Composable
internal fun Modifier.labSetScrollKeys(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { ev ->
            val k = ev.nativeKeyEvent
            val down = k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            val up = k.keyCode == KeyEvent.KEYCODE_DPAD_UP
            when {
                !down && !up -> false
                down && state.value >= state.maxValue -> false
                up && state.value <= 0 -> false
                else -> {
                    if (k.action == KeyEvent.ACTION_DOWN) scope.launch { state.animateScrollBy(if (down) 180f else -180f) }
                    true
                }
            }
        }
        .focusable()
        .then(if (focused) Modifier.border(1.dp, accent.copy(alpha = 0.6f), SimpleShape) else Modifier)
}

/** An explanation (a "?" of Classic's): the text scrolls, A or B closes. */
@Composable
internal fun LabSetInfo(title: String, body: String, onDismiss: () -> Unit) {
    val ok = remember { FocusRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { ok.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        LabSetPanel {
            LabDialogTitle(title)
            Text(
                body, color = SimpleInk, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).labSetScrollKeys(scroll).verticalScroll(scroll).padding(4.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LabButton(L("Got it"), primary = true, requester = ok, onClick = onDismiss)
            }
        }
    }
}

/**
 * A read-and-accept gate (Classic's PerfDisclaimerDialog and the capture consent): [confirmLabel] only works once the
 * box is ticked, and with [requireScroll] once the text was scrolled to its end.
 */
@Composable
internal fun LabSetGate(
    title: String,
    body: String,
    checkLabel: String,
    confirmLabel: String,
    requireScroll: Boolean,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    var accepted by remember { mutableStateOf(false) }
    // Text that fits without scrolling counts as read in full.
    val reached by remember { derivedStateOf { !requireScroll || scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 } }
    val can = reached && accepted
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { start.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        LabSetPanel {
            LabDialogTitle(title, danger)
            Text(
                body, color = SimpleInk, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).focusRequester(start).labSetScrollKeys(scroll).verticalScroll(scroll).padding(4.dp),
            )
            if (!reached) Text(L("Scroll to the end to continue."), color = SimpleMuted, fontSize = 12.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (reached) 1f else 0.45f)
                    .labTile(onClick = { if (reached) accepted = !accepted })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                LabSwitch(accepted)
                Spacer(Modifier.width(12.dp))
                Text(checkLabel, color = SimpleStrong, fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Cancel"), onClick = onDismiss)
                LabButton(
                    confirmLabel, primary = can, danger = danger && !can,
                    modifier = Modifier.alpha(if (can) 1f else 0.45f),
                    onClick = { if (can) onConfirm() },
                )
            }
        }
    }
}

/** Something running that the page waits for (a backup, a sound bank install). Back does not close it. */
@Composable
internal fun LabSetBusy(title: String, text: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        LabSetPanel {
            LabDialogTitle(title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(text, color = SimpleInk, fontSize = 14.sp)
            }
        }
    }
}

/** A line of text to type (a preset's name, a number). Done keeps it, B or Cancel drops it. */
@Composable
internal fun LabSetText(title: String, initial: String, number: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(L("Done")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Classic's About box in Lab's look: the logo, the version (and Update now when one is out), what powers it, credits. */
@Composable
internal fun LabAboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var update by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info -> main.post { update = info } }
    }
    val close = remember { FocusRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { close.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        LabSetPanel {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).labSetScrollKeys(scroll).verticalScroll(scroll).padding(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                Text("Bannerlator Bionic", color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                val newer = update?.takeIf { it.isNewer }
                Text(
                    "V ${BuildConfig.VERSION_NAME}" + (newer?.let { " · " + L("latest") + " V " + it.versionName } ?: ""),
                    color = SimpleMuted, fontSize = 13.sp,
                )
                SimpleRowDivider()
                AboutBlock(L("Powered by"), listOf(
                    "Wine" to L("Windows compatibility layer"),
                    "Box64" to L("x86_64 emulation on ARM"),
                    "FEX-Emu" to L("Fast x86 emulator"),
                    "Turnip" to L("Open-source Vulkan driver"),
                ))
                SimpleRowDivider()
                AboutBlock(L("Credits"), listOf(
                    "brunodev85" to L("Winlator — original project"),
                    "MishaMixXx" to "Winlator Bionic",
                    "The412Banner" to "Bannerlator",
                    "ptitSeb" to "Box64",
                    "WineHQ" to L("Wine project"),
                    "Mesa / Freedreno" to L("Turnip Vulkan driver"),
                ))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                val newer = update?.takeIf { it.isNewer }
                if (newer != null) {
                    LabButton(L("Update now"), onClick = { context.labSetActivity()?.let { UpdateManager.downloadAndInstall(it, newer) {} } })
                }
                LabButton(L("Close"), primary = true, requester = close, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AboutBlock(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
        rows.forEach { (name, what) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = SimpleStrong, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(what, color = SimpleMuted, fontSize = 12.sp)
            }
        }
    }
}
