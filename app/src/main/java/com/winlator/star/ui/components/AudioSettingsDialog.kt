package com.winlator.star.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Shared audio config used by the editor cog (per-container / per-game) and the in-game side menu.
 * Fixes crackling (AAudio buffer underruns) with an adaptive, self-sizing buffer, plus manual presets
 * and fine-tuning. Sink-side fields (perfMode/adaptive/buffer) apply LIVE in-game via sink recreate;
 * latencyMsec is the guest winepulse buffer, fixed at connect → applies next launch.
 */
data class AudioConfig(
    val preset: String = PRESET_AUTO,
    val perfMode: Int = 1,          // 0=NONE, 1=LOW_LATENCY, 2=POWER_SAVING
    val adaptive: Boolean = true,
    val bufferFrames: Int = 0,      // 0 = auto (framesPerBurst*2)
    val maxBufferFrames: Int = 0,   // 0 = device capacity
    val latencyMsec: Int = 100      // guest winepulse buffer
) {
    /** module-aaudio-sink argument string (matches PulseAudioComponent.resolveSinkArgs). */
    fun toSinkArgs(): String {
        val sb = StringBuilder("performance_mode=$perfMode adaptive=${if (adaptive) 1 else 0}")
        if (bufferFrames > 0) sb.append(" buffer_frames=$bufferFrames")
        if (maxBufferFrames > 0) sb.append(" max_buffer_frames=$maxBufferFrames")
        return sb.toString()
    }
}

const val PRESET_AUTO = "auto"
const val PRESET_LOW = "low"
const val PRESET_BALANCED = "balanced"
const val PRESET_STABLE = "stable"
const val PRESET_CUSTOM = "custom"
const val AUDIO_PREFS = "banner_audio"

data class AudioPreset(
    val id: String, val emoji: String, val name: String, val badge: String?, val desc: String,
    val cfg: AudioConfig, val pos: Float, val note: String
)

val AUDIO_PRESETS = listOf(
    AudioPreset(PRESET_AUTO, "✨", "Auto / Smart", "Recommended",
        "Starts low-latency, grows the buffer only if it hears crackle.",
        AudioConfig(PRESET_AUTO, 1, true, 0, 0, 100), 0.46f,
        "Aims for the lowest delay your device can hold without crackling."),
    AudioPreset(PRESET_LOW, "⚡", "Low latency", null,
        "Tightest sync. May crackle under heavy load.",
        AudioConfig(PRESET_LOW, 1, false, 0, 0, 40), 0.10f,
        "Minimal delay; switch to Auto if heavy scenes crackle."),
    AudioPreset(PRESET_BALANCED, "⚖️", "Balanced", null,
        "Calmer buffer with an adaptive safety net.",
        AudioConfig(PRESET_BALANCED, 2, true, 0, 0, 100), 0.60f,
        "Smooth all-rounder for most games."),
    AudioPreset(PRESET_STABLE, "🛡️", "Stable (no crackle)", null,
        "Biggest safety margin for weak devices / demanding games.",
        AudioConfig(PRESET_STABLE, 0, true, 0, 0, 144), 0.90f,
        "Maximum crackle protection; most audio delay."),
    AudioPreset(PRESET_CUSTOM, "🎛️", "Custom", "Fine-tune",
        "Set every knob yourself.",
        AudioConfig(PRESET_CUSTOM, 1, true, 0, 0, 100), 0.46f,
        "Manual control of every buffer knob.")
)

/* ---- persistence: session prefs (in-game / effective) ---- */
fun loadAudioConfig(ctx: Context): AudioConfig {
    val p = ctx.getSharedPreferences(AUDIO_PREFS, Context.MODE_PRIVATE)
    return AudioConfig(
        preset = p.getString("preset", PRESET_AUTO) ?: PRESET_AUTO,
        perfMode = p.getInt("perf_mode", 1),
        adaptive = p.getBoolean("adaptive", true),
        bufferFrames = p.getInt("buffer_frames", 0),
        maxBufferFrames = p.getInt("max_buffer_frames", 0),
        latencyMsec = p.getInt("latency_msec", 100)
    )
}

fun saveAudioConfig(ctx: Context, c: AudioConfig) {
    ctx.getSharedPreferences(AUDIO_PREFS, Context.MODE_PRIVATE).edit()
        .putString("preset", c.preset).putInt("perf_mode", c.perfMode)
        .putBoolean("adaptive", c.adaptive).putInt("buffer_frames", c.bufferFrames)
        .putInt("max_buffer_frames", c.maxBufferFrames).putInt("latency_msec", c.latencyMsec)
        .apply()
}

/* ---- persistence: container/shortcut env-var string (per-container / per-game) ---- */
fun audioConfigToEnv(existingEnv: String, c: AudioConfig): String {
    val keep = existingEnv.split(" ").filter { it.isNotBlank() && !it.startsWith("BANNER_AUDIO_") }
    val add = mutableListOf(
        "BANNER_AUDIO_PRESET=${c.preset}", "BANNER_AUDIO_PERF=${c.perfMode}",
        "BANNER_AUDIO_ADAPTIVE=${if (c.adaptive) 1 else 0}", "BANNER_AUDIO_LAT=${c.latencyMsec}"
    )
    if (c.bufferFrames > 0) add.add("BANNER_AUDIO_BF=${c.bufferFrames}")
    if (c.maxBufferFrames > 0) add.add("BANNER_AUDIO_MBF=${c.maxBufferFrames}")
    return (keep + add).joinToString(" ")
}

fun audioConfigFromEnv(env: String): AudioConfig {
    val m = env.split(" ").filter { it.contains("=") }.associate {
        val i = it.indexOf('='); it.substring(0, i) to it.substring(i + 1)
    }
    val def = AudioConfig()
    return AudioConfig(
        preset = m["BANNER_AUDIO_PRESET"] ?: def.preset,
        perfMode = m["BANNER_AUDIO_PERF"]?.toIntOrNull() ?: def.perfMode,
        adaptive = (m["BANNER_AUDIO_ADAPTIVE"]?.toIntOrNull() ?: 1) != 0,
        bufferFrames = m["BANNER_AUDIO_BF"]?.toIntOrNull() ?: 0,
        maxBufferFrames = m["BANNER_AUDIO_MBF"]?.toIntOrNull() ?: 0,
        latencyMsec = m["BANNER_AUDIO_LAT"]?.toIntOrNull() ?: def.latencyMsec
    )
}

/**
 * The audio popup. `scopeLabel` names the target ("this container" / "this game" / "live · this
 * session"). `latencyLive`=false shows the guest-buffer row tagged "next launch" (in-game).
 */
@Composable
fun AudioSettingsDialog(
    initial: AudioConfig,
    scopeLabel: String,
    latencyLive: Boolean,
    onDismiss: () -> Unit,
    onSave: (AudioConfig) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var cfg by remember { mutableStateOf(initial) }
    val custom = cfg.preset == PRESET_CUSTOM
    val curPreset = AUDIO_PRESETS.first { it.id == cfg.preset }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = cs.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxSize()) {
                // header
                Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("🎧 Audio settings", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = cs.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("✕", color = cs.onSurface, fontSize = 16.sp) }
                }
                Text("Balance crackle-free vs low delay  ·  $scopeLabel",
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 10.dp))

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    // tradeoff meter
                    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text("◀ Lower delay", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text("Fewer crackles ▶", color = cs.onSurfaceVariant, fontSize = 11.sp)
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp)
                                .background(cs.surface, RoundedCornerShape(6.dp))) {
                                Box(Modifier.fillMaxWidth(curPreset.pos).height(8.dp)
                                    .background(cs.primary, RoundedCornerShape(6.dp)))
                            }
                            Text(curPreset.note, color = cs.onSurfaceVariant, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 9.dp))
                        }
                    }

                    Text("PRESET", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(4.dp, 14.dp, 0.dp, 8.dp))
                    AUDIO_PRESETS.forEach { p ->
                        val sel = p.id == cfg.preset
                        Surface(shape = RoundedCornerShape(16.dp),
                            color = if (sel) cs.primary.copy(alpha = 0.14f) else cs.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .border(1.5.dp, if (sel) cs.primary else Color.Transparent, RoundedCornerShape(16.dp))
                                .clickable { cfg = p.cfg }) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                                RadioButton(selected = sel, onClick = { cfg = p.cfg })
                                Text(p.emoji, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(p.name, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                                        p.badge?.let {
                                            Spacer(Modifier.width(8.dp))
                                            Text(it.uppercase(), color = cs.primary, fontSize = 10.sp,
                                                modifier = Modifier.border(1.dp, cs.primary, RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 7.dp, vertical = 1.dp))
                                        }
                                    }
                                    Text(p.desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Text(if (custom) "FINE-TUNE" else "FINE-TUNE — (Custom only)",
                        color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(4.dp, 16.dp, 0.dp, 8.dp))
                    val ftAlpha = if (custom) 1f else 0.4f
                    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp).alpha(ftAlpha)) {
                            // Output mode
                            FtRow("Output mode", "AAudio performance mode", cs) {
                                Row {
                                    listOf("None" to 0, "Low lat." to 1, "Power" to 2).forEach { (lbl, v) ->
                                        val on = cfg.perfMode == v
                                        Text(lbl, color = if (on) cs.onPrimary else cs.onSurfaceVariant, fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 3.dp)
                                                .background(if (on) cs.primary else cs.surface, RoundedCornerShape(9.dp))
                                                .clickable(enabled = custom) { cfg = cfg.copy(perfMode = v) }
                                                .padding(horizontal = 10.dp, vertical = 7.dp))
                                    }
                                }
                            }
                            FtRow("Adaptive buffer", "Auto-grow only if it hears crackle", cs) {
                                Switch(checked = cfg.adaptive, enabled = custom,
                                    onCheckedChange = { cfg = cfg.copy(adaptive = it) })
                            }
                            FtRow("Guest buffer",
                                "winepulse · ${cfg.latencyMsec} ms" + if (!latencyLive) "  · next launch" else "", cs) {
                                Slider(value = cfg.latencyMsec.toFloat(), valueRange = 20f..200f, steps = 17,
                                    enabled = custom, onValueChange = { cfg = cfg.copy(latencyMsec = (it / 10).toInt() * 10) },
                                    modifier = Modifier.width(140.dp))
                            }
                            FtStepper("Initial sink buffer", "frames · 0 = auto",
                                if (cfg.bufferFrames > 0) "${cfg.bufferFrames}" else "auto", custom, cs) {
                                cfg = cfg.copy(bufferFrames = (cfg.bufferFrames + it * 256).coerceAtLeast(0))
                            }
                            FtStepper("Max sink buffer", "growth cap · 0 = device max",
                                if (cfg.maxBufferFrames > 0) "${cfg.maxBufferFrames}" else "device max", custom, cs) {
                                cfg = cfg.copy(maxBufferFrames = (cfg.maxBufferFrames + it * 256).coerceAtLeast(0))
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                Row(Modifier.fillMaxWidth().padding(14.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { onSave(cfg) }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun FtRow(name: String, hint: String, cs: ColorScheme, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(hint, color = cs.onSurfaceVariant, fontSize = 11.5.sp)
        }
        control()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(cs.surface))
}

@Composable
private fun FtStepper(name: String, hint: String, value: String, enabled: Boolean, cs: ColorScheme, onStep: (Int) -> Unit) {
    FtRow(name, hint, cs) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onStep(-1) }, enabled = enabled,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(34.dp)) { Text("−") }
            Text(value, color = cs.primary, fontSize = 13.sp,
                modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 6.dp))
            OutlinedButton(onClick = { onStep(1) }, enabled = enabled,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.size(34.dp)) { Text("+") }
        }
    }
}
