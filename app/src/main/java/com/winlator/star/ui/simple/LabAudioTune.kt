package com.winlator.star.ui.simple

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.ui.components.ANDROID_OUTPUT_MS
import com.winlator.star.ui.components.AUDIO_PRESETS
import com.winlator.star.ui.components.AudioConfig
import com.winlator.star.ui.components.AudioPreset
import com.winlator.star.ui.components.PRESET_CUSTOM
import com.winlator.star.ui.components.effectiveConfigFor
import com.winlator.star.ui.components.latencyIsDirectBuffer
import com.winlator.star.ui.components.latencyUsedBy
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.htmlToAnnotated
import kotlinx.coroutines.delay

/**
 * Classic's audio fine-tune (AudioSettingsDialog) as a Lab page: the trade-off meter, the five presets, and Custom's
 * output mode, adaptive buffer, buffer and the two sink buffer steppers, each with its "?". Same rules and values as
 * the Classic dialog (a preset on DirectAudio keeps its buffer, the greyed rows show what the engine applies). A
 * picks, ◀▶ change a value, Start or "?" explains, X saves, B cancels.
 */
@Composable
internal fun LabAudioTune(
    initial: AudioConfig,
    scopeLabel: String,
    latencyLive: Boolean,
    driverLabel: String,
    driverId: String,
    onDismiss: () -> Unit,
    onSave: (AudioConfig) -> Unit,
) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(initial) }
    val custom = cfg.preset == PRESET_CUSTOM
    val curPreset = AUDIO_PRESETS.firstOrNull { it.id == cfg.preset } ?: AUDIO_PRESETS.first()
    val directBuffer = latencyIsDirectBuffer(driverId)
    val latencyUsed = latencyUsedBy(driverId)
    var help by remember { mutableStateOf<Pair<String, Int>?>(null) }
    fun applyPreset(p: AudioPreset) {
        cfg = if (directBuffer) p.cfg.copy(latencyMsec = cfg.latencyMsec) else p.cfg
    }
    val shown = effectiveConfigFor(cfg, driverId)
    // DirectAudio cannot offer Power saving (its stream churns, see AudioSettingsDialog).
    val perfOpts = if (driverId == "directaudio") listOf(L("None") to 0, L("Low latency") to 1)
    else listOf(L("None") to 0, L("Low latency") to 1, L("Power saving") to 2)
    fun stepPerf(d: Int) {
        if (!custom) return
        val i = perfOpts.indexOfFirst { it.second == cfg.perfMode }.coerceAtLeast(0)
        cfg = cfg.copy(perfMode = perfOpts[(i + d + perfOpts.size) % perfOpts.size].second)
    }
    fun stepLatency(d: Int) {
        if (!custom || !latencyUsed) return
        cfg = if (directBuffer) cfg.copy(latencyMsec = ((cfg.latencyMsec / 4) * 4 + d * 4).coerceIn(4, 120))
        else cfg.copy(latencyMsec = ((cfg.latencyMsec / 10) * 10 + d * 10).coerceIn(20, 200))
    }
    fun stepFrames(max: Boolean, d: Int) {
        if (!custom) return
        cfg = if (max) cfg.copy(maxBufferFrames = (cfg.maxBufferFrames + d * 256).coerceAtLeast(0))
        else cfg.copy(bufferFrames = (cfg.bufferFrames + d * 256).coerceAtLeast(0))
    }

    val deckActions = LocalDeckActions.current
    val legend = listOf("B" to L("Cancel"), "A" to L("Select"), "◀▶" to L("Change"), "Start" to "?", "X" to L("Save"))
    DisposableEffect(deckActions) {
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    // Touch: the "X Save" and "Start ?" hints work with no row focused (Start explains the chosen preset).
    LabHintFallback(KeyEvent.KEYCODE_BUTTON_X) { onSave(cfg) }
    LabHintFallback(KeyEvent.KEYCODE_BUTTON_START) {
        AUDIO_PRESETS.firstOrNull { it.id == cfg.preset }?.let { p -> help = L(p.name) to p.helpRes }
    }

    LabOverlay(onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
                .onPreviewKeyEvent { ev ->
                    when (simpleInputOf(ev.nativeKeyEvent)) {
                        SIn.B -> { onDismiss(); true }
                        SIn.X -> { onSave(cfg); true }
                        else -> false
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L("Audio").uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(L("Audio settings"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                if (driverLabel.isNotBlank()) SimpleChip(driverLabel)
            }
            Text(L("Balance crackle-free vs low delay") + "  ·  " + scopeLabel, color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            val accent = MaterialTheme.colorScheme.primary
            LazyColumn(Modifier.weight(1f).fillMaxWidth().widthIn(max = 900.dp).labPanel().padding(horizontal = 10.dp)) {
                // The trade-off meter.
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("◀ " + L("Lower delay"), color = SimpleMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text(L("Fewer crackles") + " ▶", color = SimpleMuted, fontSize = 11.sp)
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).background(SimpleLine, SimpleShape)) {
                            Box(Modifier.fillMaxWidth(curPreset.pos).height(6.dp).background(accent, SimpleShape))
                        }
                        Text(L(curPreset.note), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item { Heading(L("Preset")) }
                AUDIO_PRESETS.forEachIndexed { i, p ->
                    item(key = "preset:${p.id}") {
                        val sel = p.id == cfg.preset
                        TuneRow(
                            title = "${p.emoji}  " + L(p.name),
                            sub = L(p.desc),
                            badge = p.badge?.let { L(it) },
                            enabled = true,
                            requester = if (sel || (i == 0 && AUDIO_PRESETS.none { it.id == cfg.preset })) first else null,
                            onA = { applyPreset(p) },
                            onHelp = { help = L(p.name) to p.helpRes },
                        ) { if (sel) Text("✓", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                item { Heading(if (custom) L("Fine-tune") else L("Fine-tune — what this preset applies (Custom to edit)")) }
                item(key = "perf") {
                    TuneRow(
                        title = L("Output mode"), sub = L("AAudio performance mode"), enabled = custom,
                        onA = { stepPerf(1) }, onStep = ::stepPerf,
                        onHelp = { help = L("Output mode") to R.string.help_audio_perf_mode },
                    ) { Value(perfOpts.firstOrNull { it.second == shown.perfMode }?.first ?: shown.perfMode.toString(), custom) }
                }
                item(key = "adaptive") {
                    TuneRow(
                        title = L("Adaptive buffer"), sub = L("Auto-grow only if it hears crackle"), enabled = custom,
                        onA = { if (custom) cfg = cfg.copy(adaptive = !cfg.adaptive) },
                        onHelp = { help = L("Adaptive buffer") to R.string.help_audio_adaptive },
                    ) { LabSwitch(shown.adaptive) }
                }
                item(key = "latency") {
                    val name = if (directBuffer) L("Audio buffer") else L("Guest buffer")
                    TuneRow(
                        title = name,
                        sub = if (!latencyUsed) L("not used by ALSA — set the buffer rows below")
                        else (if (directBuffer) L("≈%1\$s ms total · %2\$s ms buffer", shown.latencyMsec + ANDROID_OUTPUT_MS, shown.latencyMsec)
                        else L("winepulse · %1\$s ms", shown.latencyMsec)) + if (!latencyLive) "  · " + L("next launch") else "",
                        enabled = custom && latencyUsed,
                        onA = {}, onStep = ::stepLatency,
                        onHelp = {
                            help = name to when {
                                !latencyUsed -> R.string.help_audio_latency_unused
                                directBuffer -> R.string.help_audio_direct_buffer
                                else -> R.string.help_audio_guest_buffer
                            }
                        },
                    ) { Value("${shown.latencyMsec} ms", custom && latencyUsed, onStep = ::stepLatency) }
                }
                item(key = "bf") {
                    TuneRow(
                        title = L("Initial sink buffer"), sub = L("frames · 0 = auto"), enabled = custom,
                        onA = {}, onStep = { d -> stepFrames(false, d) },
                        onHelp = { help = L("Initial sink buffer") to R.string.help_audio_buffer_frames },
                    ) { Value(if (shown.bufferFrames > 0) "${shown.bufferFrames}" else L("auto"), custom, onStep = { d -> stepFrames(false, d) }) }
                }
                item(key = "mbf") {
                    TuneRow(
                        title = L("Max sink buffer"), sub = L("growth cap · 0 = device max"), enabled = custom,
                        onA = {}, onStep = { d -> stepFrames(true, d) },
                        onHelp = { help = L("Max sink buffer") to R.string.help_audio_max_buffer_frames },
                    ) { Value(if (shown.maxBufferFrames > 0) "${shown.maxBufferFrames}" else L("device max"), custom, onStep = { d -> stepFrames(true, d) }) }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        LabButton(L("Cancel"), onClick = onDismiss)
                        LabButton(L("Save"), primary = true, onClick = { onSave(cfg) })
                    }
                }
            }
        }
    }
    help?.let { (title, res) ->
        LabNote(title = title, body = remember(res) { runCatching { htmlToAnnotated(context.getString(res)).text }.getOrDefault("") }, onDismiss = { help = null })
    }
}

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
}

/** One line: A runs [onA], ◀▶ run [onStep] when given, Start (or the "?") runs [onHelp]. Greyed when not [enabled]; it still explains. */
@Composable
private fun TuneRow(
    title: String,
    sub: String?,
    enabled: Boolean,
    onA: () -> Unit,
    onHelp: () -> Unit,
    badge: String? = null,
    requester: FocusRequester? = null,
    onStep: ((Int) -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                if (k.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (k.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onStep?.invoke(-1); onStep != null }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onStep?.invoke(1); onStep != null }
                    KeyEvent.KEYCODE_BUTTON_START -> { if (k.repeatCount == 0) onHelp(); true }
                    else -> false
                }
            }
            .labTile(onClick = onA, requester = requester)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = SimpleStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    SimpleChip(badge.uppercase())
                }
            }
            if (!sub.isNullOrBlank()) Text(sub, color = SimpleMuted, fontSize = 12.5.sp)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp).size(22.dp).clip(CircleShape).border(1.dp, SimpleMuted.copy(alpha = 0.7f), CircleShape).clickable(onClick = onHelp),
        ) { Text("?", color = SimpleMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Box(Modifier.alpha(if (enabled) 1f else 0.5f)) { trailing() }
    }
}

/** A value with ‹ › to step it by touch (the controller uses ◀▶ on the row). */
@Composable
private fun Value(text: String, enabled: Boolean, onStep: ((Int) -> Unit)? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onStep != null) Text("‹", color = if (enabled) accent else SimpleDim, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = enabled) { onStep(-1) }.padding(horizontal = 8.dp))
        Text(text, color = if (enabled) SimpleStrong else SimpleMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = 70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (onStep != null) Text("›", color = if (enabled) accent else SimpleDim, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = enabled) { onStep(1) }.padding(horizontal = 8.dp))
    }
}
