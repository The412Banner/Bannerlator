package com.winlator.star.perf.galaxy

import org.json.JSONObject

/**
 * A power profile for the Samsung Galaxy Performance SDK.
 *
 * Every field is a Samsung "performance level" index in the range [LEVEL_MIN]..[LEVEL_MAX],
 * where a higher value requests more performance (and more heat / power draw). The `min`
 * fields are the floor the SoC is asked not to drop below; the `max` fields are the ceiling
 * it is allowed to reach. The levels are device-defined indices into each domain's frequency
 * table; we clamp to a conservative 0..4 window that every supported Galaxy device exposes.
 *
 * This is intentionally independent of the app's existing root-based perf tier
 * (com.winlator.star.perf.*): the Galaxy SDK path is the no-root alternative used only on
 * Samsung devices.
 */
data class GalaxyPowerProfile(
    val name: String,
    val cpuMin: Int,
    val cpuMax: Int,
    val gpuMin: Int,
    val gpuMax: Int,
    val busMin: Int,
    val busMax: Int,
) {
    /** Returns a copy with every level clamped into the valid window. */
    fun sanitized(): GalaxyPowerProfile = copy(
        cpuMin = cpuMin.coerceIn(LEVEL_MIN, LEVEL_MAX),
        cpuMax = cpuMax.coerceIn(LEVEL_MIN, LEVEL_MAX),
        gpuMin = gpuMin.coerceIn(LEVEL_MIN, LEVEL_MAX),
        gpuMax = gpuMax.coerceIn(LEVEL_MIN, LEVEL_MAX),
        busMin = busMin.coerceIn(LEVEL_MIN, LEVEL_MAX),
        busMax = busMax.coerceIn(LEVEL_MIN, LEVEL_MAX),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_NAME, name)
        put(KEY_CPU_MIN, cpuMin)
        put(KEY_CPU_MAX, cpuMax)
        put(KEY_GPU_MIN, gpuMin)
        put(KEY_GPU_MAX, gpuMax)
        put(KEY_BUS_MIN, busMin)
        put(KEY_BUS_MAX, busMax)
    }

    companion object {
        const val LEVEL_MIN = 0
        const val LEVEL_MAX = 4

        private const val KEY_NAME = "name"
        private const val KEY_CPU_MIN = "cpuMin"
        private const val KEY_CPU_MAX = "cpuMax"
        private const val KEY_GPU_MIN = "gpuMin"
        private const val KEY_GPU_MAX = "gpuMax"
        private const val KEY_BUS_MIN = "busMin"
        private const val KEY_BUS_MAX = "busMax"

        /**
         * Preset: keep peak clocks capped for a cooler, longer-lasting session.
         */
        val POWER_SAVE = GalaxyPowerProfile(
            name = "Power Save",
            cpuMin = 0, cpuMax = 2,
            gpuMin = 0, gpuMax = 2,
            busMin = 0, busMax = 2,
        )

        /**
         * Preset: full range, let the SoC scale on demand. This is the default and mirrors
         * the SDK's own "let the platform decide" behaviour while still explicitly opening
         * the ceiling so a heavy game can reach top clocks.
         */
        val BALANCED = GalaxyPowerProfile(
            name = "Balanced",
            cpuMin = 0, cpuMax = 4,
            gpuMin = 0, gpuMax = 4,
            busMin = 0, busMax = 4,
        )

        /**
         * Preset: hold the fast clocks. Maximum performance, most heat and power draw.
         */
        val PERFORMANCE = GalaxyPowerProfile(
            name = "Performance",
            cpuMin = 3, cpuMax = 4,
            gpuMin = 3, gpuMax = 4,
            busMin = 3, busMax = 4,
        )

        /** The presets a user can pick, in display order. */
        val PRESETS: List<GalaxyPowerProfile> = listOf(POWER_SAVE, BALANCED, PERFORMANCE)

        val DEFAULT: GalaxyPowerProfile = BALANCED

        fun fromJson(json: JSONObject): GalaxyPowerProfile = GalaxyPowerProfile(
            name = json.optString(KEY_NAME, DEFAULT.name),
            cpuMin = json.optInt(KEY_CPU_MIN, DEFAULT.cpuMin),
            cpuMax = json.optInt(KEY_CPU_MAX, DEFAULT.cpuMax),
            gpuMin = json.optInt(KEY_GPU_MIN, DEFAULT.gpuMin),
            gpuMax = json.optInt(KEY_GPU_MAX, DEFAULT.gpuMax),
            busMin = json.optInt(KEY_BUS_MIN, DEFAULT.busMin),
            busMax = json.optInt(KEY_BUS_MAX, DEFAULT.busMax),
        ).sanitized()

        /** Parses a stored JSON string, falling back to [DEFAULT] on any error. */
        fun fromJsonString(raw: String?): GalaxyPowerProfile {
            if (raw.isNullOrBlank()) return DEFAULT
            return try {
                fromJson(JSONObject(raw))
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}
