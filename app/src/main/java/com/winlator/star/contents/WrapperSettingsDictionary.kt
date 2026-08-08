package com.winlator.star.contents

import android.content.Context
import androidx.annotation.StringRes
import com.winlator.star.R

/**
 * Layer 1 of the Smart Wrapper Manager (issue #132) — the maintained dictionary that turns a raw,
 * auto-detected env-var NAME (found by "strings"-scanning a wrapper's binaries; see
 * [WrapperManager.scanEnvKeys]) into a proper settings control (toggle / slider / dropdown / text)
 * with a human label, hint, default and value domain.
 *
 * This is OURS to grow: one [SettingDef] per common Winlator-family setting → every imported wrapper
 * that references that env var automatically gets a polished control, with ZERO cooperation from the
 * wrapper author. A detected key NOT in the table falls back to a generic [Type.TEXT] field whose
 * label is the raw env key (still usable by power users) via [defFor].
 *
 * The dialog stores each control's value in `graphicsDriverConfig` under the RAW ENV KEY, and XSDA
 * emits `KEY=value` generically at launch — so adding a row here is the ONLY step needed to support
 * a new setting end-to-end (no dialog or emission code changes).
 */
object WrapperSettingsDictionary {

    enum class Type { TOGGLE, SLIDER, DROPDOWN, TEXT }

    data class SettingDef(
        val key: String,
        val type: Type,
        @StringRes val labelRes: Int?,
        @StringRes val hintRes: Int? = null,
        val default: String = "",
        val choices: List<String> = emptyList(),
        val min: Float = 0f,
        val max: Float = 0f,
        val step: Float = 0f,
    )

    // Seeded with the known Winlator-family vocabulary. Keys that a curated control already exposes
    // (see WrapperManager.HANDLED_ENV_KEYS) are still listed for completeness, but the "Detected
    // settings" section filters them out so nothing is shown twice.
    private val DEFS: Map<String, SettingDef> = listOf(
        // --- WRAPPER_* (integrated ICD) ---
        SettingDef("WRAPPER_VK_VERSION", Type.TEXT, R.string.wrapper_setting_wrapper_vk_version, R.string.wrapper_hint_example_1_4, default = "1.4"),
        SettingDef("WRAPPER_EMULATE_BCN", Type.DROPDOWN, R.string.wrapper_setting_wrapper_emulate_bcn,
            R.string.wrapper_hint_bcn_levels, default = "3", choices = listOf("0", "1", "2", "3")),
        SettingDef("WRAPPER_BCN_ASTC", Type.TOGGLE, R.string.wrapper_setting_wrapper_bcn_astc),
        SettingDef("WRAPPER_EXTENSION_BLACKLIST", Type.TEXT, R.string.wrapper_setting_wrapper_extension_blacklist,
            R.string.wrapper_hint_comma_separated),
        SettingDef("WRAPPER_DRIVER_ID", Type.TEXT, R.string.wrapper_setting_wrapper_driver_id),
        SettingDef("WRAPPER_SAFE_CREATE_DEVICE", Type.TOGGLE, R.string.wrapper_setting_wrapper_safe_create_device),
        SettingDef("WRAPPER_DEVICE_NAME", Type.TEXT, R.string.wrapper_setting_wrapper_device_name),
        SettingDef("WRAPPER_DEVICE_ID", Type.TEXT, R.string.wrapper_setting_wrapper_device_id),
        SettingDef("WRAPPER_VENDOR_ID", Type.TEXT, R.string.wrapper_setting_wrapper_vendor_id),
        SettingDef("WRAPPER_VMEM_MAX_SIZE", Type.TEXT, R.string.wrapper_setting_wrapper_vmem_max_size, R.string.wrapper_hint_number_zero_unset),
        SettingDef("WRAPPER_DISABLE_PRESENT_WAIT", Type.TOGGLE, R.string.wrapper_setting_wrapper_disable_present_wait),
        SettingDef("WRAPPER_MAX_IMAGE_COUNT", Type.TEXT, R.string.wrapper_setting_wrapper_max_image_count, R.string.wrapper_hint_number),
        SettingDef("WRAPPER_ONE_BY_ONE", Type.TOGGLE, R.string.wrapper_setting_wrapper_one_by_one,
            R.string.wrapper_hint_one_by_one),
        SettingDef("WRAPPER_REDUCE_DEPTH_FORMAT", Type.TOGGLE, R.string.wrapper_setting_wrapper_reduce_depth_format,
            R.string.wrapper_hint_reduce_depth_format),
        SettingDef("WRAPPER_DISABLE_PLACED", Type.TOGGLE, R.string.wrapper_setting_wrapper_disable_placed, R.string.wrapper_hint_compatibility_toggle),
        // --- MESA_VK_* / GALLIUM_* ---
        SettingDef("MESA_VK_VERSION_OVERRIDE", Type.TEXT, R.string.wrapper_setting_mesa_vk_version_override,
            R.string.wrapper_hint_mesa_vk_version),
        SettingDef("MESA_VK_WSI_PRESENT_MODE", Type.DROPDOWN, R.string.wrapper_setting_mesa_vk_wsi_present_mode,
            default = "mailbox", choices = listOf("mailbox", "fifo", "immediate")),
        SettingDef("GALLIUM_DRIVER", Type.TEXT, R.string.wrapper_setting_gallium_driver, R.string.wrapper_hint_example_zink),
        // --- BCn layer (leegao standalone) ---
        SettingDef("ENABLE_BCN_COMPUTE", Type.TOGGLE, R.string.wrapper_setting_enable_bcn_compute),
        SettingDef("BCN_COMPUTE_AUTO", Type.TOGGLE, R.string.wrapper_setting_bcn_compute_auto),
        SettingDef("BCN_TRANSCODE_TO_ETC2", Type.TOGGLE, R.string.wrapper_setting_bcn_transcode_to_etc2),
        SettingDef("BCN_TRANSCODE_TO_ASTC", Type.TOGGLE, R.string.wrapper_setting_bcn_transcode_to_astc),
        SettingDef("BCN_COMPUTE_IMAGE_VIEW", Type.TOGGLE, R.string.wrapper_setting_bcn_compute_image_view),
        SettingDef("BCN_LAYER_LOG_LEVEL", Type.TEXT, R.string.wrapper_setting_bcn_layer_log_level, R.string.wrapper_hint_example_info_error),
        SettingDef("BCN_MAX_STAGING_CACHE_MB", Type.TEXT, R.string.wrapper_setting_bcn_max_staging_cache_mb, R.string.wrapper_hint_number),
        SettingDef("BCN_QUEUE_THROTTLE_LIMIT", Type.TEXT, R.string.wrapper_setting_bcn_queue_throttle_limit, R.string.wrapper_hint_number),
        SettingDef("BCN_ASTC_USE_LARGE_STEPS", Type.TOGGLE, R.string.wrapper_setting_bcn_astc_use_large_steps,
            R.string.wrapper_hint_astc_large_steps),
        // --- DXVK/Mali compat + DX12 ---
        SettingDef("ENABLE_DXVK_MALI_COMPAT_LAYER", Type.TOGGLE, R.string.wrapper_setting_enable_dxvk_mali_compat_layer),
        SettingDef("COMPAT_EMULATE_SPARSE_BINDING", Type.TOGGLE, R.string.wrapper_setting_compat_emulate_sparse_binding),
        SettingDef("COMPAT_FORCE_MASKING", Type.TOGGLE, R.string.wrapper_setting_compat_force_masking),
        SettingDef("COMPAT_EMULATE_PUSH_DESCRIPTORS", Type.TOGGLE, R.string.wrapper_setting_compat_emulate_push_descriptors,
            R.string.wrapper_hint_compat_push_descriptors),
        SettingDef("COMPAT_EMULATE_NULL_DESCRIPTORS", Type.TOGGLE, R.string.wrapper_setting_compat_emulate_null_descriptors,
            R.string.wrapper_hint_compat_null_descriptors),
        SettingDef("COMPAT_SPARSE_COMMIT_BUDGET", Type.TEXT, R.string.wrapper_setting_compat_sparse_commit_budget,
            R.string.wrapper_hint_compat_sparse_budget),
        // --- Added from a strings-scan of the WinlatorMali / WinNative / leegao / Fcharan fork binaries
        //     (#132 dictionary hardening). Debug/diag/profile keys these binaries also expose are left to
        //     WrapperManager.isDebugEnvKey (hidden from settings), not curated here.
        // BCn transcode / emulation (leegao + Fcharan BCn layer)
        SettingDef("DISABLE_BCN", Type.TOGGLE, R.string.wrapper_setting_disable_bcn,
            R.string.wrapper_hint_disable_bcn),
        SettingDef("FORCE_BCN_EMULATION", Type.TOGGLE, R.string.wrapper_setting_force_bcn_emulation,
            R.string.wrapper_hint_force_bcn_emulation),
        SettingDef("BCN_TRANSCODE_TO_ETC1", Type.TOGGLE, R.string.wrapper_setting_bcn_transcode_to_etc1),
        SettingDef("BCN_ASTC_TRY_2P", Type.TOGGLE, R.string.wrapper_setting_bcn_astc_try_2p,
            R.string.wrapper_hint_astc_try_2p),
        SettingDef("BCN_ASTC_ONLY_2P", Type.TOGGLE, R.string.wrapper_setting_bcn_astc_only_2p),
        SettingDef("ENABLE_COMPUTE_TRACKING", Type.TOGGLE, R.string.wrapper_setting_enable_compute_tracking),
        // Wrapper-side BCn acceleration (base ICD)
        SettingDef("WRAPPER_BCN_GPU", Type.TOGGLE, R.string.wrapper_setting_wrapper_bcn_gpu),
        SettingDef("WRAPPER_BCN_GPU_CAP_MB", Type.TEXT, R.string.wrapper_setting_wrapper_bcn_gpu_cap_mb, R.string.wrapper_hint_number_zero_unset),
        SettingDef("WRAPPER_USE_BCN_CACHE", Type.TOGGLE, R.string.wrapper_setting_wrapper_use_bcn_cache),
        SettingDef("WRAPPER_NO_BCN_THREAD", Type.TOGGLE, R.string.wrapper_setting_wrapper_no_bcn_thread,
            R.string.wrapper_hint_no_bcn_thread),
        SettingDef("WRAPPER_DMAHEAP_CACHED", Type.TOGGLE, R.string.wrapper_setting_wrapper_dmaheap_cached),
        // Shader-compat family (base ICD) — workarounds for SPIR-V/driver quirks
        SettingDef("DISABLE_CLIP_DISTANCE", Type.TOGGLE, R.string.wrapper_setting_disable_clip_distance),
        SettingDef("FORCE_CLIP_DISTANCE", Type.TOGGLE, R.string.wrapper_setting_force_clip_distance),
        SettingDef("WRAPPER_NO_REMOVE_CLIP_DISTANCE", Type.TOGGLE, R.string.wrapper_setting_wrapper_no_remove_clip_distance),
        SettingDef("DISABLE_OPTIMIZATION_BARRIERS", Type.TOGGLE, R.string.wrapper_setting_disable_optimization_barriers),
        SettingDef("FORCE_OPTIMIZATION_BARRIERS", Type.TOGGLE, R.string.wrapper_setting_force_optimization_barriers),
        SettingDef("DISABLE_SPEC_COMPOSITE_CONSTANTS", Type.TOGGLE, R.string.wrapper_setting_disable_spec_composite_constants),
        SettingDef("FORCE_SPEC_COMPOSITE_CONSTANTS", Type.TOGGLE, R.string.wrapper_setting_force_spec_composite_constants),
        SettingDef("WRAPPER_NO_PATCH_OPCONSTCOMP", Type.TOGGLE, R.string.wrapper_setting_wrapper_no_patch_opconstcomp),
        SettingDef("DISABLE_EXTERNAL_FD", Type.TOGGLE, R.string.wrapper_setting_disable_external_fd),
        // Paths / misc (base ICD)
        SettingDef("WRAPPER_CACHE_PATH", Type.TEXT, R.string.wrapper_setting_wrapper_cache_path, R.string.wrapper_hint_absolute_path),
        SettingDef("WRAPPER_RESOURCE_TYPE", Type.TEXT, R.string.wrapper_setting_wrapper_resource_type),
        // --- Confirmed by getenv() disassembly across the WinlatorMali/WinNative/GameNative/leegao/base
        //     binaries (#132). Types are what the binary actually does with the value (atoi→INT/TOGGLE,
        //     pointer→TEXT). System/driver/debug getenv reads (HOME, PREFIX, NIR_SKIP, HWCPIPE_*, …) are
        //     filtered by WrapperManager.isDriverInternalEnvKey / isDebugEnvKey, NOT curated here.
        SettingDef("WRAPPER_BLIT", Type.TOGGLE, R.string.wrapper_setting_wrapper_blit,
            R.string.wrapper_hint_wrapper_blit),
        SettingDef("WRAPPER_DEVICE_FAULT", Type.TOGGLE, R.string.wrapper_setting_wrapper_device_fault,
            R.string.wrapper_hint_wrapper_device_fault),
        SettingDef("WRAPPER_EMULATE_PUSH_DESCRIPTOR", Type.TOGGLE, R.string.wrapper_setting_wrapper_emulate_push_descriptor,
            R.string.wrapper_hint_wrapper_emulate_push_descriptor),
        SettingDef("WRAPPER_ASTC_BLOCK", Type.TEXT, R.string.wrapper_setting_wrapper_astc_block, R.string.wrapper_hint_number_zero_default),
        SettingDef("WRAPPER_LAYER_PATH", Type.TEXT, R.string.wrapper_setting_wrapper_layer_path,
            R.string.wrapper_hint_wrapper_layer_path),
        SettingDef("USE_IMAGE_VIEW", Type.TOGGLE, R.string.wrapper_setting_use_image_view,
            R.string.wrapper_hint_use_image_view),
    ).associateBy { it.key }

    /** Dictionary hit for [key], else a generic TEXT field whose label is the raw env key. */
    fun defFor(key: String): SettingDef = DEFS[key] ?: SettingDef(key, Type.TEXT, null)

    fun label(context: Context, def: SettingDef): String =
        def.labelRes?.let(context::getString) ?: def.key

    fun hint(context: Context, def: SettingDef): String =
        def.hintRes?.let(context::getString).orEmpty()
}
