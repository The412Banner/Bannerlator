package com.winlator.star.perf.galaxy

/**
 * Device-specific back end for Galaxy performance control.
 *
 * The only real implementation is [SamsungSPerfDriver], which talks to Samsung's Galaxy
 * Performance SDK; [NoOpGalaxyPerfDriver] is the fallback on every other device (or when the
 * SDK jar is absent). Kept as an interface so a future vendor path can slot in without
 * touching [GalaxyPerfManager].
 */
interface GalaxyPerfDriver {

    /** True when this driver can actually control the device. Gates all UI and calls. */
    val isSupported: Boolean

    /**
     * Prepare the driver for a session. For the Samsung SDK this is a no-op — control is
     * asserted by [apply]; released boosts are re-asserted by re-calling [apply].
     */
    fun start() {}

    /** Release every active boost, returning the device to its default governor. */
    fun stop() {}

    /**
     * Apply an entire profile in one shot (a single SDK round trip where possible).
     * @return true when the request was accepted.
     */
    fun apply(profile: GalaxyPowerProfile): Boolean = false
}
