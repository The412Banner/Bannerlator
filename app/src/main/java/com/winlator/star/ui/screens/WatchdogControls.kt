package com.winlator.star.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.perf.TempWatchdog
import com.winlator.star.perf.TempWatchdog.ThresholdMode
import com.winlator.star.widget.HudMetrics
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The Temperature Watchdog controls, shared verbatim by the App Settings Performance menu and the
 * in-game drawer so the two surfaces are identical and stay in sync (both bind to the one
 * [TempWatchdog] singleton). Device-wide.
 *
 *  - ON/OFF toggle with a DYNAMIC label showing the resolved ceiling + mode; turning it OFF requires
 *    the hard scroll+checkbox disclaimer, turning it ON does not.
 *  - Aggressiveness preset selector (Conservative / Balanced / Aggressive / Manual). Selecting one of
 *    the three presets shows an informational dialog (single "Got it") with the actual °C on THIS
 *    device. Manual reveals a slider (60–device top trip).
 */
@Composable
fun WatchdogSection() {
    val enabled by TempWatchdog.enabled.collectAsState()
    val mode by TempWatchdog.mode.collectAsState()
    val ceiling by TempWatchdog.ceilingC.collectAsState()

    var showOffDisclaimer by remember { mutableStateOf(false) }
    var showWhatsThis by remember { mutableStateOf(false) }
    var infoMode by remember { mutableStateOf<ThresholdMode?>(null) }

    // Live device temperatures — poll ~1.5s while this section is visible (same HudMetrics reads as
    // the in-game readouts).
    val context = LocalContext.current
    var cpuTemp by remember { mutableStateOf<Int?>(null) }
    var gpuTemp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val metrics = HudMetrics(context.applicationContext)
        while (true) {
            cpuTemp = metrics.cpuTempC
            gpuTemp = metrics.gpuTempC
            delay(1500)
        }
    }

    // Dynamic ON/OFF row with a "What's this?" info button.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.watchdog_auto_revert, ceiling, modeLabel(mode)),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { showWhatsThis = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Info, stringResource(R.string.watchdog_what_is_title),
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { on -> if (on) TempWatchdog.setWatchdogEnabled(true) else showOffDisclaimer = true }
        )
    }

    // Live temps.
    Text(
        stringResource(
            R.string.watchdog_live_temperatures,
            cpuTemp?.let { "$it °C" } ?: "—",
            gpuTemp?.let { "$it °C" } ?: "—",
        ),
        color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium
    )

    // Device default thermal points.
    val firstTripC = TempWatchdog.firstTripC
    val topTripC = TempWatchdog.topTripC
    if (firstTripC != null && topTripC != null) {
        Text(
            stringResource(R.string.watchdog_device_limits, firstTripC, topTripC),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
        )
    } else {
        Text(
            stringResource(R.string.watchdog_fallback_limit, TempWatchdog.FALLBACK_CEILING_C),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
        )
    }

    // Preset selector.
    Text(stringResource(R.string.watchdog_threshold), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    ThresholdMode.values().forEach { m ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    TempWatchdog.setMode(m)
                    if (m != ThresholdMode.MANUAL) infoMode = m // presets explain themselves
                }
        ) {
            RadioButton(selected = mode == m, onClick = {
                TempWatchdog.setMode(m)
                if (m != ThresholdMode.MANUAL) infoMode = m
            })
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.watchdog_mode_temperature, modeLabel(m), TempWatchdog.resolvedCeilingFor(m)),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        }
    }

    // Manual slider (only in MANUAL mode).
    if (mode == ThresholdMode.MANUAL) {
        val manual by TempWatchdog.manualCeilingC.collectAsState()
        val top = TempWatchdog.topTripC ?: TempWatchdog.MANUAL_MAX_C
        val sliderMax = minOf(TempWatchdog.MANUAL_MAX_C, top).coerceAtLeast(TempWatchdog.MANUAL_MIN_C + 1)
        var sliderVal by remember(manual) { mutableStateOf(manual.toFloat()) }
        val warn = sliderVal.roundToInt() > (top - 5)
        Text(
            if (warn) {
                stringResource(R.string.watchdog_manual_temperature_warning, sliderVal.roundToInt(), top)
            } else {
                stringResource(R.string.watchdog_manual_temperature, sliderVal.roundToInt())
            },
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp, fontWeight = FontWeight.Medium
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { TempWatchdog.setManualCeilingC(sliderVal.roundToInt()) },
            valueRange = TempWatchdog.MANUAL_MIN_C.toFloat()..sliderMax.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }

    // "What is the Temperature Watchdog?" explainer.
    if (showWhatsThis) {
        PerfInfoDialog(
            title = stringResource(R.string.watchdog_what_is_title),
            body = stringResource(R.string.watchdog_what_is_body),
            onDismiss = { showWhatsThis = false }
        )
    }

    // Info dialog for the selected preset.
    infoMode?.let { m ->
        PerfInfoDialog(
            title = stringResource(R.string.watchdog_mode_temperature, modeLabel(m), TempWatchdog.resolvedCeilingFor(m)),
            body = presetInfo(m),
            onDismiss = { infoMode = null }
        )
    }

    // Hard disclaimer to disarm the watchdog.
    if (showOffDisclaimer) {
        PerfDisclaimerDialog(
            title = stringResource(R.string.watchdog_disable_safety_title),
            body = stringResource(R.string.watchdog_off_risk),
            confirmLabel = stringResource(R.string.watchdog_turn_off),
            onDismiss = { showOffDisclaimer = false },
            onConfirm = { showOffDisclaimer = false; TempWatchdog.setWatchdogEnabled(false) }
        )
    }
}

@Composable
private fun modeLabel(mode: ThresholdMode): String = stringResource(when (mode) {
    ThresholdMode.CONSERVATIVE -> R.string.watchdog_mode_conservative
    ThresholdMode.BALANCED -> R.string.watchdog_mode_balanced
    ThresholdMode.AGGRESSIVE -> R.string.watchdog_mode_aggressive
    ThresholdMode.MANUAL -> R.string.watchdog_mode_manual
})

@Composable
private fun presetInfo(mode: ThresholdMode): String {
    val first = TempWatchdog.firstTripC ?: TempWatchdog.FALLBACK_CEILING_C
    val ceiling = TempWatchdog.resolvedCeilingFor(mode)
    return when (mode) {
        ThresholdMode.CONSERVATIVE -> stringResource(R.string.watchdog_info_conservative, first)
        ThresholdMode.BALANCED -> stringResource(R.string.watchdog_info_balanced, ceiling)
        ThresholdMode.AGGRESSIVE -> stringResource(R.string.watchdog_info_aggressive, ceiling)
        ThresholdMode.MANUAL -> stringResource(R.string.watchdog_info_manual)
    }
}
