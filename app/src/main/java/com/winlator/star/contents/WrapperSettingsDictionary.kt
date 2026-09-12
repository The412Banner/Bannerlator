package com.winlator.star.contents

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
        val label: String,
        val hint: String = "",
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
        SettingDef("WRAPPER_VK_VERSION", Type.TEXT, "Vulkan version", "e.g. 1.4", default = "1.4"),
        SettingDef("WRAPPER_EMULATE_BCN", Type.DROPDOWN, "BCn emulation",
            "0 off · 1 basic · 2 full · 3 auto", default = "3", choices = listOf("0", "1", "2", "3")),
        SettingDef("WRAPPER_BCN_ASTC", Type.TOGGLE, "BCn → ASTC transcode"),
        SettingDef("WRAPPER_EXTENSION_BLACKLIST", Type.TEXT, "Blacklisted Vulkan extensions",
            "comma-separated"),
        SettingDef("WRAPPER_DRIVER_ID", Type.TEXT, "Driver ID override"),
        SettingDef("WRAPPER_SAFE_CREATE_DEVICE", Type.TOGGLE, "Safe create device"),
        SettingDef("WRAPPER_DEVICE_NAME", Type.TEXT, "Spoof GPU name"),
        SettingDef("WRAPPER_DEVICE_ID", Type.TEXT, "Spoof device ID"),
        SettingDef("WRAPPER_VENDOR_ID", Type.TEXT, "Spoof vendor ID"),
        SettingDef("WRAPPER_VMEM_MAX_SIZE", Type.TEXT, "Max device memory (MB)", "number, 0 = unset"),
        SettingDef("WRAPPER_DISABLE_PRESENT_WAIT", Type.TOGGLE, "Disable present wait"),
        SettingDef("WRAPPER_MAX_IMAGE_COUNT", Type.TEXT, "Max swapchain images", "number"),
        SettingDef("WRAPPER_ONE_BY_ONE", Type.TOGGLE, "Submit commands one-by-one",
            "Compatibility/stability; can be slower"),
        SettingDef("WRAPPER_REDUCE_DEPTH_FORMAT", Type.TOGGLE, "Reduce depth buffer format",
            "Compat for GPUs that struggle with some depth formats"),
        SettingDef("WRAPPER_DISABLE_PLACED", Type.TOGGLE, "Disable placed memory", "Compatibility toggle"),
        // --- MESA_VK_* / GALLIUM_* ---
        SettingDef("MESA_VK_VERSION_OVERRIDE", Type.TEXT, "Force Vulkan version (Mesa)",
            "e.g. 1.3 — overrides the reported Vulkan version"),
        SettingDef("MESA_VK_WSI_PRESENT_MODE", Type.DROPDOWN, "WSI present mode",
            default = "mailbox", choices = listOf("mailbox", "fifo", "immediate")),
        SettingDef("GALLIUM_DRIVER", Type.TEXT, "Gallium driver", "e.g. zink"),
        // --- BCn layer (leegao standalone) ---
        SettingDef("ENABLE_BCN_COMPUTE", Type.TOGGLE, "Enable BCn compute"),
        SettingDef("BCN_COMPUTE_AUTO", Type.TOGGLE, "BCn compute auto-detect"),
        SettingDef("BCN_TRANSCODE_TO_ETC2", Type.TOGGLE, "Transcode BCn → ETC2"),
        SettingDef("BCN_TRANSCODE_TO_ASTC", Type.TOGGLE, "Transcode BCn → ASTC"),
        SettingDef("BCN_COMPUTE_IMAGE_VIEW", Type.TOGGLE, "Storage image path"),
        SettingDef("BCN_LAYER_LOG_LEVEL", Type.TEXT, "BCn log level", "e.g. info,error"),
        SettingDef("BCN_MAX_STAGING_CACHE_MB", Type.TEXT, "BCn staging cache (MB)", "number"),
        SettingDef("BCN_QUEUE_THROTTLE_LIMIT", Type.TEXT, "BCn queue throttle limit", "number"),
        SettingDef("BCN_ASTC_USE_LARGE_STEPS", Type.TOGGLE, "ASTC: use large steps",
            "Fcharan BCn fork — faster ASTC transcode, lower quality"),
        // --- DXVK/Mali compat + DX12 ---
        SettingDef("ENABLE_DXVK_MALI_COMPAT_LAYER", Type.TOGGLE, "Enable DXVK Mali compat layer"),
        SettingDef("COMPAT_EMULATE_SPARSE_BINDING", Type.TOGGLE, "Emulate sparse binding"),
        SettingDef("COMPAT_FORCE_MASKING", Type.TOGGLE, "Force masking"),
        SettingDef("COMPAT_EMULATE_PUSH_DESCRIPTORS", Type.TOGGLE, "Emulate push descriptors (DX12)",
            "Compat layer — leave on for DX12 titles that use push descriptors"),
        SettingDef("COMPAT_EMULATE_NULL_DESCRIPTORS", Type.TOGGLE, "Emulate null descriptors (DX12)",
            "Compat layer — DX12 null-descriptor support"),
        SettingDef("COMPAT_SPARSE_COMMIT_BUDGET", Type.TEXT, "Sparse commit budget (MB)",
            "Compat layer — memory budget for D3D12 tiled/sparse resources"),
        // --- Added from a strings-scan of the WinlatorMali / WinNative / leegao / Fcharan fork binaries
        //     (#132 dictionary hardening). Debug/diag/profile keys these binaries also expose are left to
        //     WrapperManager.isDebugEnvKey (hidden from settings), not curated here.
        // BCn transcode / emulation (leegao + Fcharan BCn layer)
        SettingDef("DISABLE_BCN", Type.TOGGLE, "Disable BCn transcode",
            "Skip the BCn→ASTC/ETC path entirely"),
        SettingDef("FORCE_BCN_EMULATION", Type.TOGGLE, "Force BCn emulation",
            "Emulate BCn even when the GPU claims native support"),
        SettingDef("BCN_TRANSCODE_TO_ETC1", Type.TOGGLE, "Transcode BCn → ETC1"),
        SettingDef("BCN_ASTC_TRY_2P", Type.TOGGLE, "ASTC: try 2-partition blocks",
            "Higher quality, slower transcode"),
        SettingDef("BCN_ASTC_ONLY_2P", Type.TOGGLE, "ASTC: 2-partition only"),
        SettingDef("ENABLE_COMPUTE_TRACKING", Type.TOGGLE, "Track compute resources"),
        // Wrapper-side BCn acceleration (base ICD)
        SettingDef("WRAPPER_BCN_GPU", Type.TOGGLE, "GPU-accelerated BCn decode"),
        SettingDef("WRAPPER_BCN_GPU_CAP_MB", Type.TEXT, "BCn GPU memory cap (MB)", "number, 0 = unset"),
        SettingDef("WRAPPER_USE_BCN_CACHE", Type.TOGGLE, "Cache transcoded BCn textures"),
        SettingDef("WRAPPER_NO_BCN_THREAD", Type.TOGGLE, "Disable BCn worker thread",
            "Transcode inline instead of on a background thread"),
        SettingDef("WRAPPER_DMAHEAP_CACHED", Type.TOGGLE, "Cached DMA-heap allocations"),
        // Shader-compat family (base ICD) — workarounds for SPIR-V/driver quirks
        SettingDef("DISABLE_CLIP_DISTANCE", Type.TOGGLE, "Disable gl_ClipDistance"),
        SettingDef("FORCE_CLIP_DISTANCE", Type.TOGGLE, "Force gl_ClipDistance"),
        SettingDef("WRAPPER_NO_REMOVE_CLIP_DISTANCE", Type.TOGGLE, "Keep clip-distance in shaders"),
        SettingDef("DISABLE_OPTIMIZATION_BARRIERS", Type.TOGGLE, "Disable optimization barriers"),
        SettingDef("FORCE_OPTIMIZATION_BARRIERS", Type.TOGGLE, "Force optimization barriers"),
        SettingDef("DISABLE_SPEC_COMPOSITE_CONSTANTS", Type.TOGGLE, "Disable spec composite constants"),
        SettingDef("FORCE_SPEC_COMPOSITE_CONSTANTS", Type.TOGGLE, "Force spec composite constants"),
        SettingDef("WRAPPER_NO_PATCH_OPCONSTCOMP", Type.TOGGLE, "Don't patch OpConstantComposite"),
        SettingDef("DISABLE_EXTERNAL_FD", Type.TOGGLE, "Disable external FD memory"),
        // Paths / misc (base ICD)
        SettingDef("WRAPPER_CACHE_PATH", Type.TEXT, "Wrapper cache path", "absolute path"),
        SettingDef("WRAPPER_RESOURCE_TYPE", Type.TEXT, "Resource type override"),
        // --- Confirmed by getenv() disassembly across the WinlatorMali/WinNative/GameNative/leegao/base
        //     binaries (#132). Types are what the binary actually does with the value (atoi→INT/TOGGLE,
        //     pointer→TEXT). System/driver/debug getenv reads (HOME, PREFIX, NIR_SKIP, HWCPIPE_*, …) are
        //     filtered by WrapperManager.isDriverInternalEnvKey / isDebugEnvKey, NOT curated here.
        SettingDef("WRAPPER_BLIT", Type.TOGGLE, "Force swapchain blit",
            "Route presents through a blit — compatibility for some swapchains"),
        SettingDef("WRAPPER_DEVICE_FAULT", Type.TOGGLE, "Device-fault reporting",
            "Enable VK_EXT_device_fault diagnostics on GPU hangs"),
        SettingDef("WRAPPER_EMULATE_PUSH_DESCRIPTOR", Type.TOGGLE, "Emulate push descriptors",
            "ICD-side push-descriptor emulation"),
        SettingDef("WRAPPER_ASTC_BLOCK", Type.TEXT, "ASTC block size", "number, 0 = default"),
        SettingDef("WRAPPER_LAYER_PATH", Type.TEXT, "Vulkan layer path (advanced)",
            "absolute path — usually leave blank"),
        SettingDef("USE_IMAGE_VIEW", Type.TOGGLE, "BCn: use storage image view",
            "leegao BCn — storage-image transcode path"),
    ).associateBy { it.key }

    /** Dictionary hit for [key], else a generic TEXT field whose label is the raw env key. */
    fun defFor(key: String): SettingDef = DEFS[key] ?: SettingDef(key, Type.TEXT, key)
}
