package com.winlator.star.core

/**
 * Per-game Turnip driconf options, set as environment variables (Mesa reads any driconf option from an
 * env var of the same name). Turnip only acts on these where they apply (the SSBO/texel-buffer
 * emulation is A7xx only); other drivers ignore unknown variables. A value the user already set wins.
 */
object TurnipGameOptions {

    private val ssboEmulation = listOf("tu_enable_ssbo_emulation" to "true")

    /** Lowercase exe basename -> driconf options. */
    private val byExe: Map<String, List<Pair<String, String>>> = mapOf(
        // MIO: Memories in Orbit keeps a 650 MB mesh buffer read through 512 MiB + 138 MiB views;
        // A7xx Turnip limits SSBO ranges to 128 MiB without this option, so most level geometry is missing.
        "mio.exe" to ssboEmulation,
        // UNCHARTED: Legacy of Thieves Collection (both games and their -l builds) needs the same option on A7xx.
        "u4.exe" to ssboEmulation,
        "tll.exe" to ssboEmulation,
        "u4-l.exe" to ssboEmulation,
        "tll-l.exe" to ssboEmulation,
    )

    fun forExe(exeBasename: String?): List<Pair<String, String>> {
        if (exeBasename.isNullOrBlank()) return emptyList()
        return byExe[exeBasename.trim().lowercase()] ?: emptyList()
    }

    /** Adds the options for [exeBasename] that [env] does not already define; returns the ones added. */
    @JvmStatic
    fun applyToEnv(env: EnvVars, exeBasename: String?): List<String> {
        val added = ArrayList<String>()
        for ((name, value) in forExe(exeBasename)) {
            if (env.has(name)) continue
            env.put(name, value)
            added.add("$name=$value")
        }
        return added
    }
}
