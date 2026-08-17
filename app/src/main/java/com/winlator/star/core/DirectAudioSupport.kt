package com.winlator.star.core

/**
 * Which Wine/Proton layers can actually run DirectAudio (the Wine→AAudio native audio driver,
 * winedirectaudio.drv). The driver is an arm64ec PE built against a specific set of layers; on any
 * other compatibility layer selecting "directaudio" does nothing / breaks audio (no host audio
 * server and no ALSA/Pulse socket, and the .drv won't load). So the audio-driver selector greys the
 * option out off-list, and the launch path falls back to the default driver, whenever the selected
 * layer isn't one of these.
 *
 * SINGLE SOURCE OF TRUTH — shared with the DirectAudio driver-overlay gate. Keep it to EXACTLY these
 * four builds. Match is by the layer's version NAME containing one of the tokens, the same way the
 * in-game refresh unlock keys off "10.0-4" / "11.0-1" in the launch code (a layer's entry name always
 * carries its version, e.g. "GE-Proton 11.0-5 arm64ec").
 */
object DirectAudioSupport {
    /** The Proton/Wine builds (arm64ec) that ship a compatible winedirectaudio.drv. */
    @JvmField
    val SUPPORTED_BUILD_TOKENS = listOf("10.0-4", "11.0-1", "11.0-3", "11.0-5")

    /** Human-readable list for helper notes: "10.0-4 / 11.0-1 / 11.0-3 / 11.0-5". */
    const val SUPPORTED_LABEL = "10.0-4 / 11.0-1 / 11.0-3 / 11.0-5"

    /**
     * True when the selected Wine/Proton version name is one of the four supported builds. A blank/null
     * name (e.g. a brand-new container before a layer is chosen) is treated as UNSUPPORTED — the safe
     * default, since the app default is PulseAudio anyway and this re-evaluates once a layer is picked.
     */
    @JvmStatic
    fun isSupported(wineVersionName: String?): Boolean {
        if (wineVersionName.isNullOrBlank()) return false
        return SUPPORTED_BUILD_TOKENS.any { wineVersionName.contains(it) }
    }
}
