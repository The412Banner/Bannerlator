package com.winlator.star.core

import android.util.Log
import com.winlator.star.store.SteamStoreSearch
import java.io.File

/**
 * Scans a folder that holds many game folders and works out, for each one, which `.exe` is the
 * game — so a whole library can be imported in one pick instead of one file pick per title.
 *
 * [GameIdentifier] deliberately does not do this: it takes an exe the user already chose and
 * reasons *upward* from it. Here the exe is the unknown. A real install folder holds installers,
 * crash handlers, anti-cheat stubs and launchers alongside the game, and the game itself may sit
 * several levels down (`Game/Binaries/Win64/Game-Win64-Shipping.exe`), so candidates are collected
 * with a depth-bounded walk and ranked rather than guessed by position.
 */
object GameFolderScanner {
    private const val TAG = "GameFolderScanner"

    /** How far below a game folder to look for its exe. UE puts it 3 down; 4 covers repacks. */
    private const val MAX_DEPTH = 4

    /** Below this the pick is shown to the user as uncertain rather than trusted silently. */
    private const val CONFIDENT_SCORE = 60

    /** Two candidates within this margin means nothing clearly won — treat as uncertain. */
    private const val AMBIGUOUS_MARGIN = 15

    /** How many runners-up to keep for the manual "change exe" picker. */
    private const val MAX_ALTERNATIVES = 20

    /** Directories that never contain the game itself. */
    private val SKIP_DIRS = setOf(
        "_commonredist", "commonredist", "redist", "redistributable", "redistributables",
        "directx", "dotnet", "dotnetfx", "vcredist", "vc_redist", "prerequisites", "prereq",
        "installer", "installers", "support", "extras", "docs", "documentation", "manual",
        "soundtrack", "ost", "artbook", "bonus", "dlc", "mods", "saves", "savegames",
        "crashreportclient", "easyanticheat", "battleye", "punkbuster", "steamworks shared",
    )

    /** Exe names that are never the game. */
    private val JUNK_EXE_RE = Regex(
        """(?i)^(unins\w*|setup|install\w*|vc_?redist.*|vcredist.*|dxsetup|dxwebsetup|directx.*|""" +
            """oalinst|openal.*|dotnetfx.*|ndp\d.*|unitycrashhandler\d*|crashreport\w*|crashpad\w*|""" +
            """crashsender\w*|easyanticheat\w*|eac\w*|battleye\w*|beservice\w*|punkbuster\w*|""" +
            """steamerrorreporter\d*|gameoverlayui|touchup|cleanup|config|settings|benchmark|""" +
            """activation\w*|register\w*|readme|report\w*|.*_debug|.*-debug)$"""
    )

    /**
     * One game found under the scanned root.
     *
     * [coverUrl] is only the URL to *preview*; nothing is downloaded or written to the container
     * until the user confirms, at which point the normal import path resolves and saves the art.
     */
    data class Candidate(
        val folder: File,
        val exe: File,
        val name: String,
        val appId: Int?,
        val coverUrl: String?,
        val uncertain: Boolean,
        val alreadyAdded: Boolean,
        /**
         * Every other exe found in this folder, best-ranked first. Kept so the confirm screen can
         * offer a manual correction without rescanning — which matters because the common flagged
         * case is a folder that genuinely ships two plausible binaries (`dirt3.exe` vs
         * `dirt3_game.exe`, an x86 and an x64 `Hades.exe`), where only the user can settle it.
         */
        val alternatives: List<File>,
    )

    /**
     * Finds every game under [root].
     *
     * Each immediate subdirectory is treated as one game. When [root] has no subdirectories but
     * does contain exes, [root] itself is treated as a single game folder, so pointing this at one
     * game rather than a library still does something sensible.
     *
     * [existingExePaths] are canonical paths of exes already imported into the target container;
     * matches are returned flagged rather than dropped, so the user can see they were skipped.
     */
    fun scan(root: File, existingExePaths: Set<String> = emptySet()): List<Candidate> {
        if (!root.isDirectory) return emptyList()
        val subDirs = root.listFiles()?.filter { it.isDirectory && !isSkippedDir(it.name) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        val folders = if (subDirs.isNotEmpty()) subDirs else listOf(root)

        return folders.mapNotNull { folder ->
            runCatching { candidateFor(folder, existingExePaths) }
                .onFailure { Log.w(TAG, "scan failed for ${folder.name}", it) }
                .getOrNull()
        }
    }

    private fun candidateFor(folder: File, existingExePaths: Set<String>): Candidate? {
        val scored = collectExes(folder, depth = 0)
            .map { it to scoreExe(it, folder) }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return null

        val (bestExe, bestScore) = scored.first()
        val runnerUp = scored.getOrNull(1)?.second ?: Int.MIN_VALUE
        val uncertain = bestScore < CONFIDENT_SCORE ||
            (runnerUp != Int.MIN_VALUE && bestScore - runnerUp < AMBIGUOUS_MARGIN)

        // Reuse the single-exe identifier so a bulk import names games exactly as the "+" flow does.
        val identity = runCatching { GameIdentifier.identify(bestExe) }.getOrNull()
        val name = identity?.name?.takeIf { it.isNotBlank() }
            ?: GameIdentifier.normalizeName(folder.name).takeIf { it.isNotBlank() }
            ?: bestExe.nameWithoutExtension

        return Candidate(
            folder = folder,
            exe = bestExe,
            name = name,
            appId = identity?.appId,
            coverUrl = identity?.appId?.let { SteamStoreSearch.coverUrl(it) },
            uncertain = uncertain,
            alreadyAdded = canonical(bestExe) in existingExePaths,
            alternatives = scored.drop(1).take(MAX_ALTERNATIVES).map { it.first },
        )
    }

    /** Every plausible exe under [dir], skipping directories that never hold the game. */
    private fun collectExes(dir: File, depth: Int): List<File> {
        if (depth > MAX_DEPTH) return emptyList()
        val entries = dir.listFiles() ?: return emptyList()
        val here = entries.filter {
            it.isFile && it.name.endsWith(".exe", ignoreCase = true) &&
                !JUNK_EXE_RE.matches(it.nameWithoutExtension)
        }
        val below = entries.filter { it.isDirectory && !isSkippedDir(it.name) }
            .flatMap { collectExes(it, depth + 1) }
        return here + below
    }

    /**
     * How likely [exe] is to be the game in [folder]. Relative only — the numbers exist to rank
     * candidates against each other, and to decide whether anything won clearly enough to trust.
     */
    private fun scoreExe(exe: File, folder: File): Int {
        var score = 0
        val base = exe.nameWithoutExtension
        val exeKey = key(base)
        val folderKey = key(folder.name)

        // The exe named after its folder is the single strongest signal.
        if (exeKey.isNotEmpty() && folderKey.isNotEmpty()) {
            when {
                exeKey == folderKey -> score += 100
                folderKey.contains(exeKey) || exeKey.contains(folderKey) -> score += 60
            }
        }
        // Unreal ships as <Game>-Win64-Shipping.exe under Binaries/Win64.
        if (base.endsWith("-Win64-Shipping", true) || base.endsWith("-Win32-Shipping", true)) score += 70
        val path = exe.absolutePath.lowercase()
        if (path.contains("/binaries/win64") || path.contains("/binaries/win32")) score += 30
        if (path.contains("/bin/") || path.contains("/bin64/")) score += 10

        // A launcher is a real entry point, so it stays a candidate — just not the preferred one
        // when a non-launcher exists. GameIdentifier owns the launcher-name rules.
        if (GameIdentifier.isLauncherExeName(base)) score -= 40
        if (GameIdentifier.isJunkPeName(base)) score -= 30

        // Shallower is more likely to be the entry point; big binaries beat small helpers.
        score -= depthBelow(folder, exe) * 8
        score += (exe.length() / (8L * 1024 * 1024)).toInt().coerceAtMost(30)
        return score
    }

    private fun depthBelow(folder: File, exe: File): Int {
        val f = folder.absolutePath.trimEnd('/')
        val e = exe.parentFile?.absolutePath?.trimEnd('/') ?: return 0
        if (e == f) return 0
        return e.removePrefix("$f/").count { it == '/' } + 1
    }

    private fun isSkippedDir(name: String) = name.lowercase().trim() in SKIP_DIRS

    /** Comparison key: letters and digits only, so "GTA IV" and "gta-iv" match. */
    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun canonical(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
}
