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
 * seven builds. Match is by the layer's version NAME containing one of the tokens, the same way the
 * in-game refresh unlock keys off "10.0-4" / "11.0-1" in the launch code (a layer's entry name always
 * carries its version, e.g. "GE-Proton 11.0-5 arm64ec").
 */
object DirectAudioSupport {
    /** The Proton/Wine builds (arm64ec) that ship a compatible winedirectaudio.drv. */
    @JvmField
    val SUPPORTED_BUILD_TOKENS = listOf("10.0-4", "10.0-34", "11.0-1", "11.0-2", "11.0-3", "11.0-5", "11.0-6")

    /** Human-readable list for helper notes: "10.0-4 / 10.0-34 / 11.0-1 / 11.0-2 / 11.0-3 / 11.0-5 / 11.0-6". */
    const val SUPPORTED_LABEL = "10.0-4 / 10.0-34 / 11.0-1 / 11.0-2 / 11.0-3 / 11.0-5 / 11.0-6"

    /**
     * True when the selected Wine/Proton version name is one of the seven supported builds. A blank/null
     * name (e.g. a brand-new container before a layer is chosen) is treated as UNSUPPORTED — the safe
     * default, since the app default is PulseAudio anyway and this re-evaluates once a layer is picked.
     */
    @JvmStatic
    fun isSupported(wineVersionName: String?): Boolean {
        if (wineVersionName.isNullOrBlank()) return false
        return SUPPORTED_BUILD_TOKENS.any { wineVersionName.contains(it) }
    }

    /**
     * The launch-env flag the DirectAudio driver's unixlib reads to open an AAudio INPUT (mic) stream,
     * exactly like the other BANNER_AUDIO_DIRECT_* knobs it reads at stream open. Default UNSET = no mic
     * (today's playback-only behaviour). The app never records — it only sets this flag; the driver owns
     * capture. Lives in the per-scope env string (container DEFAULT_ENV_VARS / shortcut envVars), so it
     * round-trips per scope and reaches the launch env through the same merge the cog keys use;
     * applyDirectAudioConfig never touches _MIC, so it survives to the guest untouched.
     */
    const val MIC_ENV_KEY = "BANNER_AUDIO_DIRECT_MIC"
    private const val MIC_ENV_ON = "$MIC_ENV_KEY=1"

    /** True when the per-scope env string carries the mic-enable flag. */
    @JvmStatic
    fun isMicEnabledInEnv(env: String?): Boolean =
        env != null && env.split(" ").any { it == MIC_ENV_ON }

    /**
     * Add or remove the mic-enable flag in a space-joined env string. ON appends BANNER_AUDIO_DIRECT_MIC=1;
     * OFF removes the key entirely (absent, never "=0") — matching how the flag is contracted with the
     * driver. Every other token is preserved.
     */
    @JvmStatic
    fun withMicEnabled(env: String?, enabled: Boolean): String {
        val kept = (env ?: "").split(" ").filter { it.isNotBlank() && !it.startsWith("$MIC_ENV_KEY=") }
        val out = if (enabled) kept + MIC_ENV_ON else kept
        return out.joinToString(" ")
    }
}
