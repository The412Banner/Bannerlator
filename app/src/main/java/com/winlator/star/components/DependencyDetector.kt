package com.winlator.star.components

import java.io.File
import java.util.Locale

/**
 * Best-effort detection of the Windows redistributables a game bundles, by scanning its install
 * folder for the redist installers Steam/Epic/GOG ship (`_CommonRedist/`, `_Redist/`,
 * `Redistributables/`, loose `vcredist_x64.exe`, …) and mapping them to entries in our component
 * catalog ([ComponentCatalog], `name` keys). The result drives a per-game "recommended components"
 * offer — the user still one-tap installs each via the existing ComponentsSheet.
 *
 * Wine-aware: keeps the redists that actually matter under Wine/DXVK (VC++ runtimes, .NET, PhysX,
 * OpenAL, XACT, XNA, GFWL) and SKIPS the ones DXVK/VKD3D already provide (DX12 Agility SDK) or that
 * aren't our components (anti-cheat, Rockstar/Social-Club/launcher setups). Never throws; returns an
 * empty list on anything unexpected. Pure filesystem — no network, no Android deps (unit-testable).
 */
object DependencyDetector {

    /** How a recommendation was found — drives the UI's "recommended vs optional" split. */
    enum class Kind {
        /** The game bundles the redist installer (in _CommonRedist etc.) — meant to be installed. */
        BUNDLED,
        /** A runtime DLL the game ships loose — usually already works from the game folder; install
         *  only if the game misbehaves. */
        SHIPPED,
    }

    /** A recommended component: the catalog [componentName] to install, a display [label], the on-disk
     *  redist that triggered it ([reason]), and [kind] (bundled installer vs shipped DLL). */
    data class Recommendation(val componentName: String, val label: String, val reason: String, val kind: Kind = Kind.BUNDLED)

    private const val MAX_DEPTH = 5
    private const val MAX_ENTRIES = 8000 // cost guard against giant game trees

    /** A loose VC++ installer with no year in its path — resolved to "latest" only if no specific
     *  vcredistYYYY was found anywhere (else it's just a duplicate of the versioned one). */
    private const val VCREDIST_LOOSE = "__vcredist_loose"

    /** Directory names that are redist containers worth descending into. */
    private val REDIST_DIR_RE = Regex(
        """(?i)^(_?commonredist|_?redist(ributables)?|__?installer|vcredist|vc_redist|directx|direct_x|physx|openal|dotnet(fx)?|\.net|xna|xact|redist|support)$"""
    )

    /** Skip whole subtrees that are never our components. */
    private val SKIP_DIR_RE = Regex("""(?i)^(d3d12.*|.*agility.*|dxvk|vkd3d|battleye|easyanticheat|eac|anticheat)$""")

    private val YEAR_TO_VCREDIST = mapOf(
        "2005" to "vcredist2005", "2008" to "vcredist2008", "2010" to "vcredist2010",
        "2012" to "vcredist2012", "2013" to "vcredist2013", "2015" to "vcredist2015",
        "2017" to "vcredist2015", "2019" to "vcredist2019", "2022" to "vcredist2022",
    )

    private val LABELS = mapOf(
        "vcredist2005" to "Visual C++ 2005", "vcredist2008" to "Visual C++ 2008",
        "vcredist2010" to "Visual C++ 2010", "vcredist2012" to "Visual C++ 2012",
        "vcredist2013" to "Visual C++ 2013", "vcredist2015" to "Visual C++ 2015-2017",
        "vcredist2019" to "Visual C++ 2019", "vcredist2022" to "Visual C++ 2015-2022",
        "physx" to "NVIDIA PhysX", "oalinst" to "OpenAL", "xact" to "DirectX XACT audio",
        "xact_x64" to "DirectX XACT audio (x64)", "xna40" to "XNA Framework 4.0",
        "xna31" to "XNA Framework 3.1", "d3dx9" to "DirectX 9 (d3dx9)", "d3dx11" to "DirectX 11 (d3dx11)",
        "XLiveRedist" to "Games for Windows Live", "dotnet40" to ".NET Framework 4.0",
        "dotnet45" to ".NET Framework 4.5", "dotnet452" to ".NET Framework 4.5.2",
        "dotnet46" to ".NET Framework 4.6", "dotnet472" to ".NET Framework 4.7.2",
        "dotnet48" to ".NET Framework 4.8", "dotnet35" to ".NET Framework 3.5",
        "dotnetcoredesktop6" to ".NET Desktop 6", "dotnetcoredesktop7" to ".NET Desktop 7",
        "dotnetcoredesktop8" to ".NET Desktop 8",
    )

    /** Preferred install order (runtimes others may depend on first). */
    private val INSTALL_ORDER = listOf(
        "vcredist2005", "vcredist2008", "vcredist2010", "vcredist2012", "vcredist2013",
        "vcredist2015", "vcredist2019", "vcredist2022",
        "dotnet35", "dotnet40", "dotnet45", "dotnet452", "dotnet46", "dotnet472", "dotnet48",
        "dotnetcoredesktop6", "dotnetcoredesktop7", "dotnetcoredesktop8",
        "d3dx9", "d3dx11", "xact", "xact_x64", "xna31", "xna40", "physx", "oalinst", "XLiveRedist",
    )

    /**
     * Detect from the .exe the user picked, resolving the game root first: walk up from the exe's
     * folder (bounded) to the nearest ancestor that holds a redist container (`_CommonRedist`, …) at
     * its top level, and scan from there — so a nested exe (`…/Game/bin/x.exe`) still finds a root-level
     * `_CommonRedist`, without over-reaching into a shared `steamapps/common` (which has no redist
     * container of its own). Falls back to scanning the exe's own folder.
     */
    fun detectForExe(exeFile: File): List<Recommendation> {
        val start = exeFile.absoluteFile.parentFile ?: return emptyList()
        var d: File? = start
        var depth = 0
        while (d != null && depth < 4) {
            val hasRedistContainer = try {
                d.listFiles()?.any { it.isDirectory && REDIST_DIR_RE.matches(it.name.lowercase(Locale.US)) } == true
            } catch (_: Throwable) { false }
            if (hasRedistContainer) return detect(d)
            d = d.parentFile
            depth++
        }
        return detect(start)
    }

    /**
     * Scan [gameDir] and return the recommended components, de-duplicated (first reason wins), in a
     * stable install-friendly order. Empty when nothing is detected or [gameDir] isn't a directory.
     */
    fun detect(gameDir: File): List<Recommendation> {
        if (!gameDir.isDirectory) return emptyList()
        val hits = LinkedHashMap<String, Recommendation>() // componentName -> first recommendation
        var visited = 0

        fun consider(f: File, isDir: Boolean, insideRedist: Boolean) {
            val name = f.name.lowercase(Locale.US)
            val path = f.path.replace('\\', '/').lowercase(Locale.US)
            // Bundled = in a redist container or an installer (exe/msi/cab); shipped = a loose runtime DLL.
            val kind = if (insideRedist || isDir || name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".cab"))
                Kind.BUNDLED else Kind.SHIPPED
            for (comp in classify(path, name, isDir)) {
                val existing = hits[comp]
                if (existing == null) {
                    hits[comp] = Recommendation(comp, LABELS[comp] ?: comp, relativeReason(gameDir, f), kind)
                } else if (existing.kind == Kind.SHIPPED && kind == Kind.BUNDLED) {
                    hits[comp] = existing.copy(kind = Kind.BUNDLED) // a bundled installer outranks a shipped DLL
                }
            }
        }

        fun walk(dir: File, depth: Int, insideRedist: Boolean) {
            if (depth > MAX_DEPTH || visited > MAX_ENTRIES) return
            val entries = dir.listFiles() ?: return
            for (f in entries) {
                if (visited++ > MAX_ENTRIES) return
                val lower = f.name.lowercase(Locale.US)
                if (f.isDirectory) {
                    if (SKIP_DIR_RE.matches(lower)) continue
                    val isRedistDir = insideRedist || REDIST_DIR_RE.matches(lower)
                    if (isRedistDir) consider(f, isDir = true, insideRedist = true)
                    // Aggressive coverage: descend redist containers fully, and otherwise sweep the game
                    // tree a few levels deep so runtime libraries the game ships (in bin/, etc.) are seen.
                    if (isRedistDir || depth < 3) walk(f, depth + 1, isRedistDir)
                } else {
                    // Classify installers + runtime libraries (.dll/.exe/.msi) anywhere in the scanned
                    // tree; other files are ignored. Recommendations are advisory, so cast a wide net.
                    val ext = lower.substringAfterLast('.', "")
                    if (insideRedist || ext == "dll" || ext == "exe" || ext == "msi") consider(f, isDir = false, insideRedist = insideRedist)
                }
            }
        }

        return try {
            walk(gameDir, 0, false)
            resolveLooseVcredist(hits)
            hits.values.sortedBy { INSTALL_ORDER.indexOf(it.componentName).let { i -> if (i < 0) 999 else i } }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** A loose VC++ installer resolves to the latest 14.x only if no specific vcredistYYYY was found. */
    private fun resolveLooseVcredist(hits: LinkedHashMap<String, Recommendation>) {
        val loose = hits.remove(VCREDIST_LOOSE) ?: return
        val hasSpecific = hits.keys.any { it in YEAR_TO_VCREDIST.values }
        if (!hasSpecific) hits["vcredist2022"] = loose.copy(componentName = "vcredist2022", label = LABELS["vcredist2022"]!!)
    }

    private fun relativeReason(gameDir: File, f: File): String {
        val g = gameDir.path.replace('\\', '/')
        val p = f.path.replace('\\', '/')
        return if (p.startsWith("$g/")) p.substring(g.length + 1) else f.name
    }

    /**
     * Map a redist file/dir (its full lowercased [path], leaf [name], and whether it [isDir]) to
     * catalog component name(s). Empty for things we deliberately skip.
     */
    private fun classify(path: String, name: String, isDir: Boolean): List<String> {
        // Hard skips first — DX12 is provided by DXVK/VKD3D; these aren't our components.
        if (path.contains("d3d12") || path.contains("agility") ||
            path.contains("battleye") || path.contains("anticheat") || path.contains("easyanticheat") ||
            name.contains("socialclub") || name.contains("social-club") ||
            name.contains("rockstar") || name == "setup.exe"
        ) return emptyList()

        // Visual C++ runtimes — most important. Year comes from the path context.
        if (path.contains("vcredist") || path.contains("vc_redist") || name.startsWith("vcredist") || name.startsWith("vc_redist")) {
            val year = YEAR_TO_VCREDIST.keys.firstOrNull { path.contains(it) }
            return when {
                year != null -> listOf(YEAR_TO_VCREDIST.getValue(year))
                isDir -> emptyList()              // bare "vcredist" container — wait for the year subfolder / installer
                else -> listOf(VCREDIST_LOOSE)    // loose installer, no year → resolved post-walk
            }
        }
        // VC++ runtime DLL the game ships (msvcp140.dll → 2015-2022, msvcp120.dll → 2013, …).
        VCRUNTIME_DLL.entries.firstOrNull { name.startsWith(it.key) }?.let { return listOf(it.value) }
        if (path.contains("openal") || name.startsWith("oalinst") || name == "openal32.dll" || name == "wrap_oal.dll") return listOf("oalinst")
        if (path.contains("physx") || name.startsWith("physx")) return listOf("physx")
        if (path.contains("xact") || name.startsWith("xactengine") || name.startsWith("x3daudio")) return listOf("xact", "xact_x64")
        // Games for Windows Live — the redist folder, OR a GFWL library the game ships (xlive.dll,
        // Codemasters' dbxLive32/64, gfwl*): GFWL titles need XLiveRedist to run even when they don't
        // bundle the redist itself.
        if (path.contains("xliveredist") || name.contains("xlive") || name.startsWith("gfwl") || name.startsWith("dbxlive")) {
            return listOf("XLiveRedist")
        }
        if (path.contains("xna")) return listOf(if (path.contains("3.1") || path.contains("3_1") || path.contains("v31")) "xna31" else "xna40")
        // .NET — Framework and Core/Desktop runtimes.
        if (path.contains("dotnet") || path.contains(".net") || name.startsWith("ndp") || name.startsWith("dotnetfx") || name.contains("windowsdesktop-runtime")) {
            val comp = dotnetComponent("$path $name")
            return when {
                comp != null -> listOf(comp)
                isDir -> emptyList()          // bare ".NET" container — wait for the versioned installer
                else -> listOf("dotnet48")    // unlabeled .NET Framework installer file → sensible default
            }
        }
        // DirectX 11 helper DLLs the game ships (d3dx11_43.dll).
        if (D3DX11_RE.matches(name)) return listOf("d3dx11")
        // DirectX 9 helper DLLs / d3dcompiler / June-2010 DX redist (DXVK provides core d3d9 but NOT
        // these helper DLLs).
        if (name.startsWith("dxsetup") || name.startsWith("dxwebsetup") ||
            D3DX9_RE.matches(name) || D3DCOMPILER_RE.matches(name) ||
            (path.contains("directx") && name.endsWith(".exe"))
        ) return listOf("d3dx9")
        return emptyList()
    }

    /** Runtime DLL prefix → the VC++ redistributable that provides it (checked with startsWith). */
    private val VCRUNTIME_DLL = linkedMapOf(
        "vcruntime140" to "vcredist2022", "msvcp140" to "vcredist2022", "msvcr140" to "vcredist2022", "concrt140" to "vcredist2022",
        "msvcp120" to "vcredist2013", "msvcr120" to "vcredist2013",
        "msvcp110" to "vcredist2012", "msvcr110" to "vcredist2012",
        "msvcp100" to "vcredist2010", "msvcr100" to "vcredist2010",
        "msvcp90" to "vcredist2008", "msvcr90" to "vcredist2008",
        "msvcp80" to "vcredist2005", "msvcr80" to "vcredist2005",
    )
    private val D3DX9_RE = Regex("""d3dx9_\d+\.dll""")
    private val D3DX11_RE = Regex("""d3dx11_\d+\.dll""")
    private val D3DCOMPILER_RE = Regex("""d3dcompiler_\d+\.dll""")

    /** Resolve a .NET component from installer/path text, or null when no version is discernible. */
    private fun dotnetComponent(s: String): String? = when {
        s.contains("desktop-runtime-8") || Regex("""\b8\.\d""").containsMatchIn(s) -> "dotnetcoredesktop8"
        s.contains("desktop-runtime-7") || Regex("""\b7\.\d""").containsMatchIn(s) -> "dotnetcoredesktop7"
        s.contains("desktop-runtime-6") || Regex("""\b6\.\d""").containsMatchIn(s) -> "dotnetcoredesktop6"
        s.contains("ndp48") || s.contains("fx48") || s.contains("4.8") -> "dotnet48"
        s.contains("ndp472") || s.contains("4.7.2") -> "dotnet472"
        s.contains("ndp46") || s.contains("4.6") -> "dotnet46"
        s.contains("ndp452") || s.contains("4.5.2") -> "dotnet452"
        s.contains("dotnetfx45") || s.contains("ndp45") || s.contains("4.5") -> "dotnet45"
        s.contains("dotnetfx40") || s.contains("ndp40") || s.contains("4.0") -> "dotnet40"
        s.contains("dotnetfx35") || s.contains("3.5") -> "dotnet35"
        else -> null // no version signal (e.g. a bare "DotNet" container dir)
    }
}
