package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.perf.PerfGpuTurbo
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import kotlinx.coroutines.launch

/**
 * App Settings → Performance menu. Binds the GLOBAL DEFAULTS (non-root three + root six) to the same
 * [PerformanceSettings] flows the in-game drawer reads, so a change here is reflected live in the
 * other surface (two-way sync via one store).
 *
 * Root tier: a real grant gate (scroll-to-bottom + accept disclaimer -> [RootManager.requestGrant]),
 * then the root toggles applied live through [PerfRootApplier] (snapshot-before-write; reverted on
 * exit/background/crash). The two dangerous toggles (thermal disable, fan max) stay disabled until the
 * safety harness is proven. The temperature watchdog is device-wide; turning it OFF requires its own
 * hard disclaimer.
 */
@Composable
fun PerformanceSettingsScreen(onClose: () -> Unit) {
    val sustained by PerformanceSettings.sustainedPerfMode.collectAsState()
    val priority by PerformanceSettings.perfPriorityBoost.collectAsState()
    val bigCores by PerformanceSettings.preferBigCores.collectAsState()
    val rootState by PerformanceSettings.rootState.collectAsState()
    val harnessProven by PerformanceSettings.harnessProven.collectAsState()
    // "Auto deep-clean on launch" (Tier 2) global default — persisted via the same root-default store.

    val scope = rememberCoroutineScope()
    var showRootDisclaimer by remember { mutableStateOf(false) }
    // Per-toggle "?" info dialog: (title, body). And the consolidated "Explain toggles" sheet.
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showExplainAll by remember { mutableStateOf(false) }

    val granted = rootState == RootManager.RootState.GRANTED
    val sustainedLabel = stringResource(R.string.perf_sustained_mode)
    val priorityLabel = stringResource(R.string.perf_thread_priority_boost)
    val bigCoresLabel = stringResource(R.string.perf_prefer_big_cores)
    val gpuClockLabel = stringResource(R.string.perf_lock_gpu_max)
    val cpuGovernorLabel = stringResource(R.string.perf_cpu_governor)
    val cpuFrequencyLabel = stringResource(R.string.perf_lock_cpu_max)
    val coresOnlineLabel = stringResource(R.string.perf_keep_cores_online)
    val thermalLabel = stringResource(R.string.perf_disable_thermal)
    val fanLabel = stringResource(R.string.perf_fan_max)
    val freeMemoryLabel = stringResource(R.string.perf_free_memory)
    val sustainedInfo = stringResource(R.string.perf_info_sustained)
    val priorityInfo = stringResource(R.string.perf_info_priority)
    val bigCoresInfo = stringResource(R.string.perf_info_big_cores)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.perf_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.perf_close), tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            Text(
                stringResource(R.string.perf_global_defaults_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
            )

            // ── Non-root global defaults (always editable) ──
            PerfCard(title = stringResource(R.string.perf_global_defaults)) {
                PerfToggle(sustainedLabel, sustained,
                    onInfo = { info = sustainedLabel to sustainedInfo }) { PerformanceSettings.setSustainedPerfMode(it) }
                PerfToggle(priorityLabel, priority,
                    onInfo = { info = priorityLabel to priorityInfo }) { PerformanceSettings.setPerfPriorityBoost(it) }
                PerfToggle(bigCoresLabel, bigCores,
                    onInfo = { info = bigCoresLabel to bigCoresInfo }) { PerformanceSettings.setPreferBigCores(it) }
                // GPU pin lives here (not in the root card) because it now has a non-root path on
                // Adreno. It still upgrades itself to the stronger sysfs pin when root is granted.
                val gpuClockInfo = stringResource(R.string.perf_info_gpu_clock)
                RootToggle(PerfRootApplier.KEY_GPU_CLOCK_LOCK, gpuClockLabel, granted, harnessProven,
                    onInfo = { info = gpuClockLabel to gpuClockInfo })
                if (!granted && !PerfGpuTurbo.isSupported) {
                    Text(stringResource(R.string.perf_gpu_requires_adreno_or_root),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }

            // ── Root tier ──
            PerfCard(title = stringResource(R.string.perf_root_controls)) {
                Text(stringResource(R.string.perf_root_status, rootStateLabel(rootState)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                when (rootState) {
                    RootManager.RootState.UNAVAILABLE -> {
                        Text(stringResource(R.string.perf_no_root_manager),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    RootManager.RootState.GRANTED -> { /* toggles below are enabled */ }
                    else -> {
                        // AVAILABLE_NOT_GRANTED or DENIED -> offer (or re-offer) the grant.
                        val label = stringResource(if (rootState == RootManager.RootState.DENIED) R.string.perf_grant_root_retry else R.string.perf_grant_root)
                        Button(onClick = { showRootDisclaimer = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                val cpuGovernorInfo = stringResource(R.string.perf_info_cpu_governor)
                val cpuFrequencyInfo = stringResource(R.string.perf_info_cpu_frequency)
                val coresOnlineInfo = stringResource(R.string.perf_info_cores_online)
                val thermalInfo = stringResource(R.string.perf_info_thermal)
                val fanInfo = stringResource(R.string.perf_info_fan)
                RootToggle(PerfRootApplier.KEY_CPU_GOVERNOR, cpuGovernorLabel, granted, harnessProven,
                    onInfo = { info = cpuGovernorLabel to cpuGovernorInfo })
                RootToggle(PerfRootApplier.KEY_CPU_FREQ_LOCK, cpuFrequencyLabel, granted, harnessProven,
                    onInfo = { info = cpuFrequencyLabel to cpuFrequencyInfo })
                RootToggle(PerfRootApplier.KEY_CORES_ONLINE, coresOnlineLabel, granted, harnessProven,
                    onInfo = { info = coresOnlineLabel to coresOnlineInfo })
                RootToggle(PerfRootApplier.KEY_THERMAL_DISABLE, thermalLabel, granted, harnessProven,
                    onInfo = { info = thermalLabel to thermalInfo })
                RootToggle(PerfRootApplier.KEY_FAN_MAX, fanLabel, granted, harnessProven,
                    onInfo = { info = fanLabel to fanInfo })

                if (granted && !harnessProven) {
                    Text(stringResource(R.string.perf_safety_controls_locked),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }

                // ── Free memory (dual tier). Section "?" explains both tiers + the auto toggle. ──
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(freeMemoryLabel, color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val freeMemoryInfo = stringResource(R.string.perf_info_free_memory)
                    InfoButton { info = freeMemoryLabel to freeMemoryInfo }
                }

                // TIER 1 — drop file caches (light; near-invisible RAM by design). Root-gated as before.
                Button(
                    onClick = { PerfRootApplier.freeMemoryNow() },
                    enabled = granted,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.perf_drop_file_caches), color = MaterialTheme.colorScheme.onPrimary) }
                Text(stringResource(R.string.perf_drop_file_caches_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }

            // ── Temperature watchdog (device-wide; not root-gated). Shared control block, identical
            // and synced with the in-game surface (both bind the one TempWatchdog singleton). ──
            PerfCard(title = stringResource(R.string.perf_temperature_watchdog)) {
                Text(
                    stringResource(R.string.perf_watchdog_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
                WatchdogSection()
            }

            Text(
                stringResource(R.string.perf_auto_revert_always_on),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
            )

            Button(onClick = { showExplainAll = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.perf_explain_toggles), color = MaterialTheme.colorScheme.onPrimary)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    info?.let { (title, body) ->
        PerfInfoDialog(title = title, body = body, onDismiss = { info = null })
    }

    if (showExplainAll) {
        PerfInfoDialog(title = stringResource(R.string.perf_what_toggles_do), body = stringResource(R.string.perf_explain_all_body), onDismiss = { showExplainAll = false })
    }

    if (showRootDisclaimer) {
        PerfDisclaimerDialog(
            title = stringResource(R.string.perf_root_disclaimer_title),
            body = stringResource(R.string.perf_root_risk),
            confirmLabel = stringResource(R.string.perf_grant_root),
            onDismiss = { showRootDisclaimer = false },
            onConfirm = {
                showRootDisclaimer = false
                scope.launch { RootManager.requestGrant() } // fires the su prompt; state updates live
            }
        )
    }
}

/** A PerfRootApplier-owned toggle bound to its global default; applies live via PerfRootApplier. */
@Composable
private fun RootToggle(key: String, label: String, granted: Boolean, harnessProven: Boolean, onInfo: () -> Unit) {
    val checked by PerformanceSettings.rootDefaultFlow(key).collectAsState()
    val gated = PerfRootApplier.isHarnessGated(key) && !harnessProven
    // The GPU pin is usable without root on Adreno (KGSL turbo); everything else needs the grant.
    val usableWithoutRoot = key == PerfRootApplier.KEY_GPU_CLOCK_LOCK && PerfGpuTurbo.isSupported
    val enabled = (granted || usableWithoutRoot) && !gated
    PerfToggle(label, checked, enabled = enabled, onInfo = onInfo) { on ->
        PerformanceSettings.setRootDefault(key, on)
        PerfRootApplier.apply(key, on)
    }
}

@Composable
private fun rootStateLabel(state: RootManager.RootState): String = stringResource(when (state) {
    RootManager.RootState.UNKNOWN -> R.string.perf_root_checking
    RootManager.RootState.UNAVAILABLE -> R.string.perf_root_unavailable
    RootManager.RootState.AVAILABLE_NOT_GRANTED -> R.string.perf_root_available_not_granted
    RootManager.RootState.GRANTED -> R.string.perf_root_granted
    RootManager.RootState.DENIED -> R.string.perf_root_denied
})

@Composable
private fun PerfCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun PerfToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onInfo: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Only the label + switch dim/gate on `enabled`; the "?" stays live so a locked toggle is
        // still explainable.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
        if (onInfo != null) InfoButton(onInfo)
        Spacer(Modifier.width(4.dp))
        Row(modifier = if (enabled) Modifier else Modifier.alpha(0.4f)) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/** Small "?" affordance that opens a soft info dialog. Always live (even for disabled toggles). */
@Composable
private fun InfoButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Outlined.HelpOutline, stringResource(R.string.perf_whats_this),
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}
