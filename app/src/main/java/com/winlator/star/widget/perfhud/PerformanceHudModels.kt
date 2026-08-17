package com.winlator.star.widget.perfhud

/**
 * Model types for [PerformanceHudView] (GameNative-style HUD), ported from GameNative's
 * PerformanceHudModels.kt. Battery is collected through the shared
 * [com.winlator.star.widget.HudMetrics] collector, so GameNative's BatterySnapshot is not duplicated
 * here.
 */
internal enum class MetricId {
    ENGINE,
    GPU_MODEL,
    FPS,
    CPU,
    GPU,
    RAM,
    BATTERY,
    POWER,
    RUNTIME,
    BATTERY_TEMP,
    CLOCK,
    CPU_TEMP,
    GPU_TEMP,
}

internal enum class GraphScaleMode {
    FPS_DYNAMIC,
    PERCENT_100,
}

internal enum class HudSize {
    SMALL,
    MEDIUM,
    LARGE,
}

internal data class HudSnapshot(
    val fpsValue: Float,
    val cpuValue: Float?,
    val gpuValue: Float?,
    val fps: String,
    val cpu: String?,
    val gpu: String?,
    val ram: String,
    val battery: String?,
    val power: String?,
    val runtime: String?,
    val batteryTemp: String?,
    val clock: String,
    val cpuTemp: String?,
    val gpuTemp: String?,
    // Raw °C alongside the formatted strings: the band colour is computed from the reading, and the
    // string may already be in °F. Thresholds are always °C.
    val cpuTempC: Float? = null,
    val gpuTempC: Float? = null,
    val batteryTempC: Float? = null,
)

internal data class HudAppearance(
    val textSizeSp: Float,
    val containerHorizontalPaddingDp: Int,
    val containerVerticalPaddingDp: Int,
    val rowVerticalPaddingDp: Int,
    val rowSpacingDp: Int,
    val columnSpacingDp: Int,
    val cornerRadiusDp: Int,
    val strokeWidthDp: Int,
    val stackedGraphWidthDp: Int,
    val stackedGraphHeightDp: Int,
    val compactGraphWidthDp: Int,
    val compactGraphHeightDp: Int,
    val stackedGraphTopMarginDp: Int,
    val stackedGraphBottomMarginDp: Int,
    val compactGraphStartMarginDp: Int,
)

internal data class MetricSignature(
    val id: MetricId,
    val showText: Boolean,
    val showGraph: Boolean,
)
