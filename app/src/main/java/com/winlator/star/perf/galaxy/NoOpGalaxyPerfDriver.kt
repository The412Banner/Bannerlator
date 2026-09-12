package com.winlator.star.perf.galaxy

/** Fallback driver for non-Samsung devices or when the Galaxy Performance SDK is unavailable. */
class NoOpGalaxyPerfDriver : GalaxyPerfDriver {
    override val isSupported: Boolean = false
}
