package com.winlator.star.ui.simple

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.ui.components.CONTAINER_GLOSSARY
import com.winlator.star.ui.deck.LocalDeckActions
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.settings.SettingDef
import com.winlator.star.ui.screens.htmlToAnnotated
import kotlinx.coroutines.delay

/**
 * Classic's "What is all this?" glossary (ContainerGlossarySheet) in Lab's look, for the container, game and
 * All-containers editors: the same entries, searchable, the D-pad moving through them. B closes.
 */
@Composable
internal fun LabGlossaryDialog(onDismiss: () -> Unit, initialQuery: String = "") {
    var query by remember { mutableStateOf(initialQuery) }
    val q = query.trim()
    val sections = remember(q) {
        if (q.isEmpty()) CONTAINER_GLOSSARY
        else CONTAINER_GLOSSARY.mapNotNull { section ->
            val hits = section.entries.filter { it.term.contains(q, ignoreCase = true) || it.definition.contains(q, ignoreCase = true) }
            if (hits.isEmpty()) null else section.copy(entries = hits)
        }
    }
    val deckActions = LocalDeckActions.current
    val legend = listOf("B" to L("Close"))
    DisposableEffect(deckActions) {
        deckActions.ownLegend.value = legend
        onDispose { if (deckActions.ownLegend.value === legend) deckActions.ownLegend.value = null }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    LabOverlay(onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
                .onPreviewKeyEvent { ev -> if (simpleInputOf(ev.nativeKeyEvent) == SIn.B) { onDismiss(); true } else false },
        ) {
            Text(L("Help").uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(L("What is all this?"), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                L("Plain-language explanations of the setup options. Tap a term to read what it does."),
                color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            val accent = MaterialTheme.colorScheme.primary
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(L("Search terms"), color = SimpleDim) },
                shape = SimpleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SimpleStrong,
                    unfocusedTextColor = SimpleInk,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = SimpleLine,
                    cursorColor = accent,
                ),
                modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().labPanel().padding(horizontal = 10.dp)) {
                var firstDone = false
                sections.forEach { section ->
                    item(key = "sec:${section.title}") {
                        Text(
                            L(section.title).uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    val takeFirst = !firstDone
                    firstDone = true
                    items(section.entries, key = { "${section.title}:${it.term}" }) { entry ->
                        val isFirst = takeFirst && entry === section.entries.first()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .labTile(onClick = {}, requester = if (isFirst) first else null)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(L(entry.term), color = SimpleStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(L(entry.definition), color = SimpleMuted, fontSize = 13.5.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                if (sections.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().focusRequester(first).focusable().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                            Text(L("No terms match \"%1\$s\".", q), color = SimpleMuted, fontSize = 13.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * Classic's "?" explanations (the editors' R.string.help_* texts) for Lab rows that have none of their own, by
 * setting id. Lab's help dialog shows a row's own help first.
 */
internal object LabFieldHelp {
    private val BY_ID: Map<String, Int> = mapOf(
        "graphics.dx.anisotropy" to R.string.help_anisotropic_filtering,
        "graphics.dx.lodBias" to R.string.help_texture_sharpness,
        "audio.driver" to R.string.help_audio_driver,
        "graphics.gdc.extensions" to R.string.help_available_extensions,
        "input.bannerinXInput" to R.string.help_bannerin_xinput,
        "input.bannerinXInputSwitching" to R.string.help_bannerin_xinput_switching,
        "graphics.gdc.bcnEmulation" to R.string.help_bcn_emulation,
        "graphics.gdc.bcnEmulationCache" to R.string.help_bcn_emulation_cache,
        "graphics.gdc.bcnEmulationType" to R.string.help_bcn_emulation_type,
        "graphics.gdc.bcnLayerFast" to R.string.help_bcn_layer_fast,
        "graphics.gdc.bcnEmulationAstc" to R.string.help_bcn_transcode_astc,
        "graphics.gdc.bcnTranscodeAstc" to R.string.help_bcn_transcode_astc,
        "input.controllerIdentity" to R.string.help_controller_identity,
        "input.dinputVibration" to R.string.help_dinput_vibration,
        "input.dinputVisible" to R.string.help_dinput_visible,
        "graphics.gdc.disablePresentWait" to R.string.help_disable_present_wait,
        "performance.fexPreset" to R.string.help_fexcore_preset,
        "performance.fexVersion" to R.string.help_fexcore_version,
        "performance.forceSsbs" to R.string.help_force_ssbs,
        "performance.fpsLimiter" to R.string.help_fps_limiter,
        "performance.frameGen" to R.string.help_frame_generation,
        "graphics.gdc.gpuName" to R.string.help_gpu_name,
        "graphics.driver" to R.string.help_graphics_driver,
        "graphics.gdc.version" to R.string.help_graphics_driver_version,
        "input.gyro.activation" to R.string.help_gyro_activation_mode,
        "input.gyro.activator" to R.string.help_gyro_activator,
        "input.gyro.mode" to R.string.help_gyro_mode,
        "input.gyro.sensitivity" to R.string.help_gyro_sensitivity,
        "input.gyro.target" to R.string.help_gyro_target,
        "graphics.gdc.maxDeviceMemory" to R.string.help_max_device_memory,
        "graphics.gdc.fdDevFeatures" to R.string.help_oneui_hyperos_fix,
        "graphics.fastOpenGL" to R.string.help_opengl_settings,
        "graphics.glFpsHud" to R.string.help_opengl_settings,
        "graphics.glVsyncOff" to R.string.help_opengl_settings,
        "graphics.renderer" to R.string.help_renderer,
        "graphics.swapColours" to R.string.help_renderer_colors,
        "graphics.rendererDriver" to R.string.help_renderer_driver,
        "graphics.nativeRenderer" to R.string.help_renderer_native,
        "graphics.nativeBackend" to R.string.help_renderer_native_backend,
        "graphics.sfColours" to R.string.help_renderer_sf_compat,
        "graphics.gdc.resourceType" to R.string.help_resource_type,
        "input.rumbleMotors" to R.string.help_rumble_motors,
        "display.resolution" to R.string.help_screen_size,
        "performance.startupServices" to R.string.help_startup_selection,
        "input.stickPacing" to R.string.help_stick_pacing,
        "graphics.gdc.syncFrame" to R.string.help_sync_every_frame,
        "performance.syncMode" to R.string.help_sync_mode,
        "graphics.gdc.tu.forcecb" to R.string.help_turnip_concurrent_binning,
        "graphics.gdc.tu.nocb" to R.string.help_turnip_concurrent_binning,
        "graphics.gdc.tu.deck_emu" to R.string.help_turnip_deck_emu,
        "graphics.gdc.turnipGmem" to R.string.help_turnip_gmem,
        "graphics.gdc.tu.sysmem" to R.string.help_turnip_sysmem,
        "input.vibrationIntensity" to R.string.help_vibration_intensity,
        "input.vibrationTarget" to R.string.help_vibration_mode,
        "graphics.gdc.vulkanVersion" to R.string.help_vulkan_version,
        "windows.wineVersion" to R.string.help_wine_version,
        "graphics.gdc.presentMode" to R.string.help_wrapper_present_modes,
    )

    /** True when [def] has an explanation to show (its own help or hint, or Classic's). */
    fun has(def: SettingDef): Boolean = def.help != null || def.hint != null || def.id in BY_ID

    /** [def]'s explanation: its own help, else Classic's text for it, else its hint. */
    fun text(context: Context, def: SettingDef): String? =
        def.help?.let { L(it) }
            ?: BY_ID[def.id]?.let { res -> runCatching { htmlToAnnotated(context.getString(res)).text }.getOrNull() }
            ?: def.hint?.let { L(it) }
}
