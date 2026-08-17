package com.winlator.star.widget.fusionhud

/**
 * Size modes for the Fusion HUD (the 4th selectable in-game overlay style). One shared color-coded
 * visual language, four amounts of detail — see the approved mockup. The config value is the lower-case
 * token stored under the `hudSize` key; the label is the user-facing string.
 */
enum class FusionSize(val token: String, val label: String) {
    FULL("full", "Full"),
    TILES("tiles", "Tiles"),
    PILL("pill", "Pill"),
    MINIMAL("minimal", "Minimal"),
    MEGA("mega", "Mega");

    /** Next size in the tap-cycle: Full → Tiles → Pill → Minimal → Mega → Full. */
    fun next(): FusionSize {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    /**
     * The metric toggle chips this size actually renders — the SINGLE source of truth queried by BOTH
     * config surfaces ({@code FpsCounterConfigDialog} + in-game {@code XServerDrawer} HudContent) so the
     * chip list can't drift from what the view draws. Chip ids are the chip LABELS used in the UI.
     * (Hiding a chip is purely UI; buildConfig() still emits every key — strip-invariant.)
     */
    fun supportedChips(): Set<String> = when (this) {
        MINIMAL -> MINIMAL_CHIPS
        PILL -> PILL_CHIPS
        FULL, TILES -> STANDARD_CHIPS
        MEGA -> MEGA_CHIPS
    }

    companion object {
        fun from(token: String?): FusionSize =
            values().firstOrNull { it.token.equals(token, ignoreCase = true) } ?: FULL

        // Chip-label ids (must match the labels the config UIs build). "Clock" is a small subtle
        // corner readout available in EVERY size, so it's in all sets.
        // The API/engine label ("Engine" chip) shows on the FPS row in EVERY size, so it's in all sets.
        // "Wrapper" and "DX ver" are Mega-only: the wrapper appears solely below Mega's frametime graph,
        // and "DX ver" gates the DX-version suffix on Mega's FPS-row engine label.
        val MINIMAL_CHIPS = setOf("FPS", "FPS graph", "0.01% low", "FPS .1", "Engine", "Clock")
        val PILL_CHIPS = setOf("FPS", "FPS graph", "GPU", "CPU", "VRAM", "RAM", "Battery", "GPU model", "FPS .1", "Engine", "Clock")
        val STANDARD_CHIPS = setOf(
            "FPS", "FPS graph", "GPU", "GPU temp", "CPU", "VRAM", "RAM", "Power", "Temp",
            "Battery", "GPU model", "Engine", "0.01% low", "FPS .1", "Clock",
        )
        val MEGA_ONLY = setOf(
            "Per-core", "Swap", "Network", "Resolution", "Proton", "Wrapper", "DX ver", "Session",
        )
        val MEGA_CHIPS = STANDARD_CHIPS + MEGA_ONLY
    }
}
