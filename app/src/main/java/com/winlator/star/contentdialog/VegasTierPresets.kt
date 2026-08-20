package com.winlator.star.contentdialog

import java.io.File

/**
 * VEGAS tier presets — canonical data mirroring the VEGAS FAQ (docs/vegas_faq.html,
 * FAQ #4 "Which GPUs are supported?" and #11 "What is the Tier system?"):
 * Adreno models per tier, the per-tier auto-tuning parameters, and GPU-name → tier
 * detection. Tier numbering is the FAQ's own: 1 = entry, 2 = mid, 3 = high-end;
 * vegas.forceTier = 0 means auto-detection (the default).
 *
 * The parameter table below is the FAQ's published data — never invented tuning.
 * The tier recommendation writes the documented override key (vegas.forceTier)
 * through the normal active-config write path; the runtime keeps auto-tuning.
 */
object VegasTierPresets {

    data class Tier(
        val number: Int,
        val label: String,
        /** Short device-class description shown in the tier card's defs rows. */
        val devices: String,
        /** Adreno model tokens used for detection (FAQ model lists). */
        val models: List<String>
    )

    val TIERS = listOf(
        Tier(1, "Tier 1", "Entry — 5xx / 6xx entry (506–620) · GTX 1050 Ti-class",
            listOf("506", "508", "509", "512", "610", "615", "616", "618", "619", "620")),
        Tier(2, "Tier 2", "Mid — 6xx mid (630–690, 642L) · GTX 1070-class",
            listOf("630", "640", "642", "650", "660", "680", "690")),
        Tier(3, "Tier 3", "High — 7xx / 8xx (830, 840) · RTX 3060-class",
            listOf("730", "740", "750", "830", "840"))
    )

    data class Params(
        val drawThreshold: Int,      // D3D11 base threshold
        val drawThresholdD3D9: Int,  // D3D9 base threshold
        val haaePacing: Int,
        val governorCap: String,     // "2.0×" / "1.7×"
        val shaderZeroInit: String,  // "ON" / "OFF"
        val frameGen: String         // "Disabled" / "≤ 29ms" / "≤ 33ms"
    )

    val PARAMS = mapOf(
        1 to Params(100, 300, 50, "2.0×", "ON", "Disabled"),
        2 to Params(200, 500, 100, "2.0×", "ON", "≤ 29ms"),
        3 to Params(350, 800, 150, "1.7×", "OFF", "≤ 33ms")
    )

    /**
     * Reads the KGSL GPU model node (world-readable on Adreno devices — the same
     * sysfs family the runtime itself reads, per the FAQ). Null when unavailable
     * (non-Adreno, blocked, or path differs) — the caller then stays manual-only.
     */
    fun readGpuModel(): String? = runCatching {
        val f = File("/sys/class/kgsl/kgsl-3d0/gpu_model")
        if (f.isFile) f.readText().trim().ifEmpty { null } else null
    }.getOrNull()

    /**
     * Classifies an Adreno model string ("Adreno 660", "Adreno (TM) 750", …) into a
     * tier. Non-Adreno or unparseable input returns null (no suggestion). Numeric
     * fallback outside the FAQ lists: <560 → 1, 600–629 → 1, ≥700 → 3, else 2.
     */
    fun classifyModel(model: String?): Int? {
        if (model == null) return null
        val s = model.replace("(TM)", "").replace("(R)", "")
            .replace(Regex("\\s+"), " ").trim()
        if (!s.contains("adreno", ignoreCase = true)) return null
        val num = Regex("(\\d+)").find(s)?.groupValues?.get(1) ?: return null
        val v = num.toIntOrNull() ?: return null
        if (v >= 700) return 3
        TIERS.firstOrNull { t -> t.models.any { m -> num.startsWith(m) } }?.let { return it.number }
        return when {
            v <= 560 -> 1
            v in 600..629 -> 1
            else -> 2
        }
    }

    /**
     * Bundled "What's new" seed per build — the offline fallback for the release-notes
     * chip when the GitHub feed is unreachable. Verbatim user-supplied notes describing
     * each build; the live release body always wins when it can be fetched.
     */
    val BUNDLED_NOTES: Map<String, List<String>> = mapOf(
        "2.7.3" to listOf(
            "Stable · frame pacing fixes",
            "dxvk.enableStarProfile, vegas.enableUpscaler ship commented"
        ),
        "2.7.4-beta" to listOf(
            "GPL dither warm-up",
            "Adds vegas.telemetry"
        ),
        "2.8.0" to listOf(
            "TBDR batch tuning · tile-reuse pass",
            "vegas.telemetry now stock (was 2.7.4-beta only)"
        )
    )
}