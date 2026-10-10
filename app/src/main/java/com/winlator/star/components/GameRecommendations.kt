package com.winlator.star.components

import com.winlator.star.core.GameIdentifier
import java.io.File

/**
 * The components a game needs, from everything that says so: the redistributables in its folder
 * and the runtimes it ships ([DependencyDetector]), and what Steam installs alongside it
 * ([SteamRedists], from the library's appmanifest). Folder findings first; one entry per component.
 * Filesystem only, never throws - call off the main thread.
 */
object GameRecommendations {
    fun detect(exeFile: File?, gameDir: File?): List<DependencyDetector.Recommendation> = runCatching {
        val folder = when {
            exeFile != null -> DependencyDetector.detectForExe(exeFile)
            gameDir != null -> DependencyDetector.detect(gameDir)
            else -> emptyList()
        }
        // What Steam installs alongside the game lives outside its folder: read Steam's list.
        val steam = (exeFile ?: gameDir)?.let { f ->
            val dir = SteamRedists.steamGameDir(f)
            val appId = exeFile?.let { GameIdentifier.identify(it).appId } ?: dir?.let { manifestAppId(it) }
            if (dir != null && appId != null) SteamRedists.detect(dir, appId) else emptyList()
        }.orEmpty()
        (folder + steam).distinctBy { it.componentName }
    }.getOrDefault(emptyList())

    /** The appId of the appmanifest in the library that installs into [dir]. */
    private fun manifestAppId(dir: File): Int? {
        val installDir = Regex("\"installdir\"\\s+\"${Regex.escape(dir.name)}\"", RegexOption.IGNORE_CASE)
        return dir.parentFile?.parentFile?.listFiles { m -> m.name.startsWith("appmanifest_") && m.name.endsWith(".acf") }
            ?.firstOrNull { m -> m.length() < 1L shl 20 && installDir.containsMatchIn(m.readText()) }
            ?.name?.removePrefix("appmanifest_")?.removeSuffix(".acf")?.toIntOrNull()
    }

    /** Why a recommendation is there, in a few words for its row. */
    fun reason(r: DependencyDetector.Recommendation): String = when (r.kind) {
        DependencyDetector.Kind.BUNDLED -> "The game ships ${r.label}"
        DependencyDetector.Kind.SHIPPED -> "The game has ${r.label} (often works as is)"
        DependencyDetector.Kind.STEAM -> "Steam installs ${r.label} with this game"
    }
}
