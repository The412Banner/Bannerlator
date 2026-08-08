package com.winlator.star.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.R

// ─────────────────────────────────────────────────────────────────────────────
// Container glossary — a plain-language "what is all this?" compendium for the
// container editor. Newcomer-facing: short, friendly definitions of the terms a
// first-time user meets on the setup screen (wrappers, DXVK/VKD3D, Mali/Adreno,
// BCn, colours, glibc/bionic, …). Searchable. Self-contained so the shortcut
// editor (or a future setup-wizard tutorial) can reuse it verbatim.
// ─────────────────────────────────────────────────────────────────────────────

internal data class GlossaryEntry(
    val id: String,
    @StringRes val termRes: Int,
    @StringRes val definitionRes: Int,
)

internal data class GlossarySection(
    val id: String,
    @StringRes val titleRes: Int,
    val entries: List<GlossaryEntry>,
)

private data class LocalizedGlossaryEntry(
    val id: String,
    val term: String,
    val definition: String,
)

private data class LocalizedGlossarySection(
    val id: String,
    val title: String,
    val entries: List<LocalizedGlossaryEntry>,
)

internal val CONTAINER_GLOSSARY: List<GlossarySection> = listOf(
    GlossarySection(
        id = "graphics_translation",
        titleRes = R.string.glossary_section_graphics_translation,
        entries = listOf(
            GlossaryEntry("dxvk", R.string.glossary_term_dxvk, R.string.glossary_definition_dxvk),
            GlossaryEntry("vkd3d", R.string.glossary_term_vkd3d, R.string.glossary_definition_vkd3d),
            GlossaryEntry("wined3d", R.string.glossary_term_wined3d, R.string.glossary_definition_wined3d),
            GlossaryEntry("vegas", R.string.glossary_term_vegas, R.string.glossary_definition_vegas),
            GlossaryEntry("directx", R.string.glossary_term_directx, R.string.glossary_definition_directx),
            GlossaryEntry("opengl", R.string.glossary_term_opengl, R.string.glossary_definition_opengl),
            GlossaryEntry("zink", R.string.glossary_term_zink, R.string.glossary_definition_zink),
        ),
    ),
    GlossarySection(
        id = "windows_layer",
        titleRes = R.string.glossary_section_windows_layer,
        entries = listOf(
            GlossaryEntry("wine", R.string.glossary_term_wine, R.string.glossary_definition_wine),
            GlossaryEntry("proton", R.string.glossary_term_proton, R.string.glossary_definition_proton),
            GlossaryEntry("container", R.string.glossary_term_container, R.string.glossary_definition_container),
            GlossaryEntry("wrapper_graphics_driver", R.string.glossary_term_wrapper_graphics_driver, R.string.glossary_definition_wrapper_graphics_driver),
        ),
    ),
    GlossarySection(
        id = "x86_on_arm",
        titleRes = R.string.glossary_section_x86_on_arm,
        entries = listOf(
            GlossaryEntry("fexcore", R.string.glossary_term_fexcore, R.string.glossary_definition_fexcore),
            GlossaryEntry("box64", R.string.glossary_term_box64, R.string.glossary_definition_box64),
            GlossaryEntry("arm64ec", R.string.glossary_term_arm64ec, R.string.glossary_definition_arm64ec),
            GlossaryEntry("fexcore_preset", R.string.glossary_term_fexcore_preset, R.string.glossary_definition_fexcore_preset),
        ),
    ),
    GlossarySection(
        id = "gpu_rendering",
        titleRes = R.string.glossary_section_gpu_rendering,
        entries = listOf(
            GlossaryEntry("vulkan", R.string.glossary_term_vulkan, R.string.glossary_definition_vulkan),
            GlossaryEntry("mali_vs_adreno", R.string.glossary_term_mali_vs_adreno, R.string.glossary_definition_mali_vs_adreno),
            GlossaryEntry("turnip", R.string.glossary_term_turnip, R.string.glossary_definition_turnip),
            GlossaryEntry("bcn_emulation", R.string.glossary_term_bcn_emulation, R.string.glossary_definition_bcn_emulation),
            GlossaryEntry("astc_etc2", R.string.glossary_term_astc_etc2, R.string.glossary_definition_astc_etc2),
            GlossaryEntry("adrenotools", R.string.glossary_term_adrenotools, R.string.glossary_definition_adrenotools),
            GlossaryEntry("vulkan_version", R.string.glossary_term_vulkan_version, R.string.glossary_definition_vulkan_version),
            GlossaryEntry("gpu_spoofing", R.string.glossary_term_gpu_spoofing, R.string.glossary_definition_gpu_spoofing),
            GlossaryEntry("vram_cap", R.string.glossary_term_vram_cap, R.string.glossary_definition_vram_cap),
        ),
    ),
    GlossarySection(
        id = "turnip_tuning",
        titleRes = R.string.glossary_section_turnip_tuning,
        entries = listOf(
            GlossaryEntry("gmem", R.string.glossary_term_gmem, R.string.glossary_definition_gmem),
            GlossaryEntry("sysmem", R.string.glossary_term_sysmem, R.string.glossary_definition_sysmem),
            GlossaryEntry("sync_every_frame", R.string.glossary_term_sync_every_frame, R.string.glossary_definition_sync_every_frame),
            GlossaryEntry("concurrent_binning", R.string.glossary_term_concurrent_binning, R.string.glossary_definition_concurrent_binning),
            GlossaryEntry("deck_emu", R.string.glossary_term_deck_emu, R.string.glossary_definition_deck_emu),
            GlossaryEntry("khr_present_wait", R.string.glossary_term_khr_present_wait, R.string.glossary_definition_khr_present_wait),
            GlossaryEntry("bcn_transcode_astc", R.string.glossary_term_bcn_transcode_astc, R.string.glossary_definition_bcn_transcode_astc),
        ),
    ),
    GlossarySection(
        id = "picture_settings",
        titleRes = R.string.glossary_section_picture_settings,
        entries = listOf(
            GlossaryEntry("colors_rgba_bgra", R.string.glossary_term_colors_rgba_bgra, R.string.glossary_definition_colors_rgba_bgra),
            GlossaryEntry("render_scale", R.string.glossary_term_render_scale, R.string.glossary_definition_render_scale),
            GlossaryEntry("frame_generation", R.string.glossary_term_frame_generation, R.string.glossary_definition_frame_generation),
            GlossaryEntry("fps_limiter", R.string.glossary_term_fps_limiter, R.string.glossary_definition_fps_limiter),
            GlossaryEntry("native_rendering", R.string.glossary_term_native_rendering, R.string.glossary_definition_native_rendering),
            GlossaryEntry("frame_gen_fps_numbers", R.string.glossary_term_frame_gen_fps_numbers, R.string.glossary_definition_frame_gen_fps_numbers),
            GlossaryEntry("dxvk_hud", R.string.glossary_term_dxvk_hud, R.string.glossary_definition_dxvk_hud),
            GlossaryEntry("present_mode", R.string.glossary_term_present_mode, R.string.glossary_definition_present_mode),
            GlossaryEntry("fifo", R.string.glossary_term_fifo, R.string.glossary_definition_fifo),
            GlossaryEntry("mailbox", R.string.glossary_term_mailbox, R.string.glossary_definition_mailbox),
            GlossaryEntry("immediate", R.string.glossary_term_immediate, R.string.glossary_definition_immediate),
            GlossaryEntry("surfaceflinger", R.string.glossary_term_surfaceflinger, R.string.glossary_definition_surfaceflinger),
            GlossaryEntry("shader_cache", R.string.glossary_term_shader_cache, R.string.glossary_definition_shader_cache),
        ),
    ),
    GlossarySection(
        id = "under_the_hood",
        titleRes = R.string.glossary_section_under_the_hood,
        entries = listOf(
            GlossaryEntry("glibc_bionic", R.string.glossary_term_glibc_bionic, R.string.glossary_definition_glibc_bionic),
            GlossaryEntry("environment_variables", R.string.glossary_term_environment_variables, R.string.glossary_definition_environment_variables),
            GlossaryEntry("esync_fsync", R.string.glossary_term_esync_fsync, R.string.glossary_definition_esync_fsync),
            GlossaryEntry("dll_overrides", R.string.glossary_term_dll_overrides, R.string.glossary_definition_dll_overrides),
            GlossaryEntry("components", R.string.glossary_term_components, R.string.glossary_definition_components),
        ),
    ),
    GlossarySection(
        id = "controls_audio",
        titleRes = R.string.glossary_section_controls_audio,
        entries = listOf(
            GlossaryEntry("xinput_dinput", R.string.glossary_term_xinput_dinput, R.string.glossary_definition_xinput_dinput),
            GlossaryEntry("exclusive_input", R.string.glossary_term_exclusive_input, R.string.glossary_definition_exclusive_input),
            GlossaryEntry("midi_soundfont", R.string.glossary_term_midi_soundfont, R.string.glossary_definition_midi_soundfont),
            GlossaryEntry("gyro", R.string.glossary_term_gyro, R.string.glossary_definition_gyro),
            GlossaryEntry("audio_driver", R.string.glossary_term_audio_driver, R.string.glossary_definition_audio_driver),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerGlossarySheet(onDismiss: () -> Unit, initialQuery: String = "") {
    val context = LocalContext.current
    val localeTag = context.resources.configuration.locales[0].toLanguageTag()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    // A per-field help icon opens the sheet pre-filtered to that term (initialQuery); the general
    // "What is all this?" button opens it unfiltered (initialQuery == "").
    var query by remember { mutableStateOf(initialQuery) }

    val localizedGlossary = remember(localeTag) {
        CONTAINER_GLOSSARY.map { section ->
            LocalizedGlossarySection(
                id = section.id,
                title = context.getString(section.titleRes),
                entries = section.entries.map { entry ->
                    LocalizedGlossaryEntry(
                        id = entry.id,
                        term = context.getString(entry.termRes),
                        definition = context.getString(entry.definitionRes),
                    )
                },
            )
        }
    }

    // Flatten + filter. A section is shown only if it has any matching entries.
    val q = query.trim()
    val sections = remember(q, localizedGlossary) {
        if (q.isEmpty()) localizedGlossary
        else localizedGlossary.mapNotNull { section ->
            val hits = section.entries.filter {
                it.term.contains(q, ignoreCase = true) || it.definition.contains(q, ignoreCase = true)
            }
            if (hits.isEmpty()) null else section.copy(entries = hits)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.glossary_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.glossary_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.glossary_search_terms)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.padding(top = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sections.forEach { section ->
                    item(key = "sec:${section.id}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(section.entries, key = { "${section.id}:${it.id}" }) { entry ->
                        Surface(
                            color = cs.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(entry.term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    entry.definition,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
                if (sections.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.glossary_no_terms_match, q),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                item { Spacer(Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}
