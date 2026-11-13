package com.winlator.star.components

import android.content.Context
import com.winlator.star.components.DependencyDetector.Kind
import com.winlator.star.components.DependencyDetector.Recommendation
import com.winlator.star.core.GameIdentifier
import com.winlator.star.store.SteamDatabase
import com.winlator.star.store.SteamRepository
import com.winlator.star.store.steamscript.InstallScriptExecutor
import java.io.File
import java.util.Locale

/**
 * The components a game needs, ranked by how sure we are (DroidDeck's per-source matching, plus
 * what the game's own files prove):
 *
 * 1. What its source lists: Steam's shared redistributables ([SteamRedists]: the appmanifest, or the
 *    library sync's app info), GOG's dependencies / Epic's prerequisite / Amazon's PostInstall
 *    ([StoreRedists]). A game added by hand takes Steam's list when its files name its appid
 *    (steam_appid.txt, an emulator ini, an appmanifest, its install script's `...\Steam\Apps\<appid>`).
 * 2. What its Steam install script runs ([InstallScriptRedists]; a DirectX setup by its cabs).
 * 3. What its exe loads ([PeImports]): VC++ runtimes, d3dx, OpenAL, XACT, .NET... that it does not
 *    ship beside itself.
 *
 * Those are [Group.RECOMMENDED]. A runtime DLL the game ships loose, and Games for Windows Live
 * hints, are [Group.OPTIONAL]. An installer that only sits in the game's folder (a repack's
 * `_Redist`, say) that nothing above confirms is [Group.FOLDER]: offered, folded away, never
 * recommended. One entry per component, the strongest evidence's.
 *
 * Filesystem and SharedPreferences only, bounded; never throws - call off the main thread.
 */
object GameRecommendations {
    /** Where a recommendation is listed. */
    enum class Group { RECOMMENDED, OPTIONAL, FOLDER }

    fun group(r: Recommendation): Group = when (r.kind) {
        Kind.STORE, Kind.STEAM, Kind.SCRIPT -> Group.RECOMMENDED
        Kind.IMPORT -> if (r.componentName == XLIVE) Group.OPTIONAL else Group.RECOMMENDED
        Kind.SHIPPED -> Group.OPTIONAL
        Kind.BUNDLED -> Group.FOLDER
    }

    /** Why a recommendation is there, in a few words for its row. */
    fun reason(r: Recommendation): String = when (r.kind) {
        Kind.SHIPPED -> if (r.componentName == XLIVE) "The game has Games for Windows Live files" else "The game ships ${r.label} (often works as is)"
        Kind.STEAM -> r.reason.ifEmpty { "Steam installs ${r.label} with this game" }
        else -> r.reason
    }

    /**
     * Everything known for the game whose exe is [exeFile] (or whose folder is [gameDir]): the
     * Recommended ones first (by evidence), then Optional, then the folder's. [context] reads what the
     * stores and the Steam library kept; without it only the files are read.
     */
    fun detect(exeFile: File?, gameDir: File?, context: Context? = null): List<Recommendation> = runCatching {
        val app = context?.applicationContext
        val files = fileEvidence(exeFile, gameDir)
        val out = ArrayList<Recommendation>()
        // 0. Games known to need a component that nothing below finds.
        knownGame(exeFile)?.let { out += it }
        // 1. The source's list.
        out += StoreRedists.detect(app, gameDir ?: files.root, exeFile)
        files.steamAppId(app)?.let { id -> out += SteamRedists.detect(app, files.steamDir ?: files.root, id) }
        // 2-3. The install script and the imports.
        out += files.script
        out += files.imports
        // .NET: a version the store, the script or the folder names beats the generic one.
        files.dotnet?.let { generic ->
            val named = (out + files.folder).firstOrNull { it.componentName.startsWith("dotnet") }
            out += if (named != null) named.copy(kind = Kind.IMPORT, reason = generic.reason) else generic
        }
        // Steam's own _CommonRedist (a Steam install) is what Steam installs; other folder finds stay as they are.
        out += files.folder.map { r ->
            if (r.kind == Kind.BUNDLED && files.steamInstall && r.reason.startsWith("_commonredist/", true))
                r.copy(kind = Kind.STEAM, reason = "Steam installs ${r.label} with this game")
            else if (r.kind == Kind.BUNDLED) r.copy(reason = files.folderReason)
            else r
        }
        out.groupBy { it.componentName }
            .map { (_, all) -> all.minByOrNull { rank(it.kind) }!! }
            .sortedWith(compareBy<Recommendation>({ group(it).ordinal }, { rank(it.kind) }, { DependencyDetector.orderOf(it.componentName) }))
    }.getOrDefault(emptyList())

    /**
     * Batman: Arkham Knight uses NVIDIA PhysX (umu-protonfixes installs it for Steam app 208650): recommend it
     * whatever the store or the game's files say.
     */
    private fun knownGame(exeFile: File?): Recommendation? =
        if (exeFile?.name.equals("BatmanAK.exe", ignoreCase = true))
            Recommendation("physx", DependencyDetector.labelOf("physx"), "Batman: Arkham Knight uses NVIDIA PhysX", Kind.STORE)
        else null

    /** Evidence strength, strongest first. A loose DLL (Optional) outranks an unconfirmed installer. */
    private fun rank(kind: Kind): Int = when (kind) {
        Kind.STORE -> 0
        Kind.STEAM -> 1
        Kind.SCRIPT -> 2
        Kind.IMPORT -> 3
        Kind.SHIPPED -> 4
        Kind.BUNDLED -> 5
    }

    // ---- the game's files, read once per change ----

    private const val XLIVE = "XLiveRedist"
    private const val MAX_EXES = 2
    private const val MAX_OWN_DLLS = 8
    private const val MAX_BESIDE_DLLS = 16

    /** What the files say, kept per game while they are unchanged. */
    private class FileEvidence(
        val root: File?,
        val steamDir: File?,
        val steamInstall: Boolean,
        val exe: File?,
        val script: List<Recommendation>,
        val scriptAppIds: List<Int>,
        val imports: List<Recommendation>,
        val dotnet: Recommendation?,
        val folder: List<Recommendation>,
        val folderReason: String,
    ) {
        /** The Steam app the game is: its files, Bannerlator's own Steam install, the appmanifest, the install script. */
        fun steamAppId(context: Context?): Int? {
            exe?.let { runCatching { GameIdentifier.identify(it).appId }.getOrNull() }?.let { return it }
            root?.let { r -> GameRecommendations.installedSteamApp(r, context) }?.let { return it }
            steamDir?.let { GameRecommendations.manifestAppId(it) }?.let { return it }
            return scriptAppIds.firstOrNull()
        }
    }

    private val cache = object : LinkedHashMap<String, Pair<String, FileEvidence>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, FileEvidence>>?) = size > 32
    }

    private fun fileEvidence(exeFile: File?, gameDir: File?): FileEvidence {
        val key = "${exeFile?.absolutePath}|${gameDir?.absolutePath}"
        val sig = listOf(exeFile?.lastModified(), exeFile?.length(), gameDir?.lastModified(), exeFile?.parentFile?.lastModified()).joinToString("/")
        synchronized(cache) { cache[key]?.takeIf { it.first == sig }?.let { return it.second } }
        val fresh = readFiles(exeFile, gameDir)
        synchronized(cache) { cache[key] = sig to fresh }
        return fresh
    }

    private fun readFiles(exeFile: File?, gameDir: File?): FileEvidence {
        val anchor = exeFile ?: gameDir
        val steamDir = anchor?.let { SteamRedists.steamGameDir(it) }
        val ownSteam = anchor?.let { runCatching { InstallScriptExecutor.locateInstallDir(it) }.getOrNull() }
        val exe = exeFile?.takeIf { it.isFile }
        // The game's folder: the Steam install's, else the one above its build folders, else what we were given.
        val root = steamDir ?: ownSteam ?: exe?.let { GameIdentifier.gameFolderOf(it) } ?: gameDir ?: exe?.parentFile

        // Install scripts: at the game's folder and, when the exe sits elsewhere, at its own folder.
        val scriptRoots = listOfNotNull(root, exe?.parentFile, gameDir).map { it.absoluteFile }.distinct().take(3)
        val scans = scriptRoots.map { InstallScriptRedists.scan(it) }
        val script = scans.flatMap { it.hits }.distinctBy { it.component }
            .map { Recommendation(it.component, DependencyDetector.labelOf(it.component), "Steam's install script runs it", Kind.SCRIPT) }

        val folder = when {
            exe != null -> DependencyDetector.detectForExe(exe)
            gameDir != null -> DependencyDetector.detect(gameDir)
            else -> emptyList()
        }
        val exes = exe?.let { listOf(it) } ?: pickExes(root)
        val (imports, dotnet) = importEvidence(exes)
        return FileEvidence(
            root, steamDir, steamDir != null || ownSteam != null, exe ?: exes.firstOrNull(),
            script, scans.flatMap { it.appIds }.distinct(), imports, dotnet, folder,
            if (steamDir == null && ownSteam == null && root != null && looksRepacked(root)) "The repack ships its installer; the game doesn't load it"
            else "Its installer is in the game's folder; nothing says the game needs it",
        )
    }

    /** The game's own exes when none was given: the biggest at the top of its folder, not installers. */
    private fun pickExes(root: File?): List<File> =
        root?.listFiles { f -> f.isFile && f.name.endsWith(".exe", true) && !NOT_THE_GAME.containsMatchIn(f.name) }
            ?.sortedByDescending { it.length() }?.take(MAX_EXES).orEmpty()

    private val NOT_THE_GAME = Regex("""(?i)^(unins|setup|install|redist|vc_?redist|dxsetup|dxwebsetup|oalinst|crash|ue\d?prereq)""")

    /** Folder names and files repackers leave beside a game. */
    private val REPACK_MARK = Regex("""(?i)^(_redist|redist|redistributables|_redistributables|_commonredist|elamigos.*|fitgirl.*|dodi.*|setup-.*\.bin|.*\.(nfo))$""")

    private fun looksRepacked(root: File): Boolean =
        root.listFiles()?.any { REPACK_MARK.matches(it.name) || it.name.contains("elamigos", true) || it.name.contains("fitgirl", true) } == true

    // ---- imports ----

    /**
     * The components [exes] (and the DLLs of their own they load, one level) import and do not
     * ship beside themselves, plus a .NET finding when one is a .NET program. The other DLLs beside
     * an exe count too: a game can load one at run time without importing it (DOOM (2016)'s
     * CChromaEditorLibrary.dll, which needs MFC).
     */
    private fun importEvidence(exes: List<File>): Pair<List<Recommendation>, Recommendation?> {
        val out = LinkedHashMap<String, Recommendation>()
        var dotnet: Recommendation? = null
        var scannedOwn = 0
        var scannedBeside = 0
        val besideRead = HashSet<String>()
        for (exe in exes) {
            val info = PeImports.read(exe) ?: continue
            val beside = exe.parentFile?.list()?.associateBy { it.lowercase(Locale.US) }.orEmpty()
            fun add(loader: String?, dll: String, is64: Boolean) {
                for (c in importComponents(dll, is64)) {
                    if (c in out) continue
                    val why = if (loader == null) "The game loads $dll" else "$loader loads $dll"
                    out[c] = Recommendation(c, DependencyDetector.labelOf(c), why, Kind.IMPORT)
                }
            }
            for (dll in info.imports) {
                val shipped = beside[dll]
                if (shipped == null) { add(null, dll, info.is64); continue }
                // A DLL of the game's own (UnityPlayer.dll, a game.dll): what it loads counts too.
                if (scannedOwn >= MAX_OWN_DLLS || importComponents(dll, info.is64).isNotEmpty()) continue
                scannedOwn++
                val own = PeImports.read(File(exe.parentFile, shipped)) ?: continue
                own.imports.filter { it !in beside }.forEach { add(shipped, it, own.is64) }
            }
            // Runtime DLLs the game ships beside itself (msvcp140.dll…) are skipped like imported ones.
            for ((lower, shipped) in beside.toSortedMap()) {
                if (scannedBeside >= MAX_BESIDE_DLLS) break
                if (!lower.endsWith(".dll") || lower in info.imports || importComponents(lower, info.is64).isNotEmpty()) continue
                val file = File(exe.parentFile, shipped)
                if (!besideRead.add(file.path)) continue
                scannedBeside++
                val own = PeImports.read(file) ?: continue
                own.imports.filter { it !in beside }.forEach { add(shipped, it, own.is64) }
            }
            if (dotnet == null && ("mscoree.dll" in info.imports || info.clrVersion != null)) {
                val clr = info.clrVersion.orEmpty()
                val v2 = clr.startsWith("v2") || clr.startsWith("v1")
                dotnet = Recommendation(if (v2) "dotnet35" else "dotnet48", DependencyDetector.labelOf(if (v2) "dotnet35" else "dotnet48"),
                    if (v2) "The game is a .NET 2.0-3.5 program" else "The game is a .NET program", Kind.IMPORT)
                if (info.usesXna) {
                    val xna = if (v2) "xna31" else "xna40"
                    if (xna !in out) out[xna] = Recommendation(xna, DependencyDetector.labelOf(xna), "The game uses XNA", Kind.IMPORT)
                }
            }
        }
        return out.values.toList() to dotnet
    }

    private val VC_BY_VERSION = mapOf(
        "80" to "vcredist2005", "90" to "vcredist2008", "100" to "vcredist2010",
        "110" to "vcredist2012", "120" to "vcredist2013", "140" to "vcredist2022",
    )
    /** XAudio 2.0-2.6 came with the XACT runtime; 2.8 and 2.9 are Windows' own. */
    private val XAUDIO_OLD = Regex("""^xaudio2_[0-6]\.dll$""")
    private val VC_RUNTIME = Regex("""^(?:msvc[rp]|mfc|mfcm|vcomp|concrt|vcamp|vccorlib)(\d{2,3})[a-z]?d?(?:_\d)?\.dll$""")

    /**
     * An imported [dll] to the components that provide it. XInput is never named (the app ships its
     * own for controller support); d3dx10 and the OS's own d3dcompiler_46/47 have no catalog
     * component; system DLLs map to nothing.
     */
    internal fun importComponents(dll: String, is64: Boolean): List<String> {
        val n = dll.lowercase(Locale.US)
        if (n == "mfc42.dll" || n == "mfc42u.dll") return listOf("mfc42")
        VC_RUNTIME.find(n)?.let { m -> return listOfNotNull(VC_BY_VERSION[m.groupValues[1]]) }
        return when {
            n.startsWith("vcruntime140") -> listOf("vcredist2022")
            n == "msvbvm60.dll" -> listOf("vbrun6")
            n.startsWith("d3dx9_") -> listOf("d3dx9")
            n.startsWith("d3dx11_") -> listOf("d3dx11")
            n == "d3dcompiler_43.dll" -> listOf("d3dcompiler_43")
            n == "d3dcompiler_42.dll" -> listOf("d3dcompiler_42")
            n == "xaudio2_7.dll" -> listOf("xaudio2.7")
            n.startsWith("x3daudio1_") || n.startsWith("xactengine") || n.startsWith("xapofx1_") || XAUDIO_OLD.matches(n) ->
                listOf(if (is64) "xact_x64" else "xact")
            n == "openal32.dll" || n == "wrap_oal.dll" -> listOf("oalinst")
            n == "physxloader.dll" || n == "physxcore.dll" || n == "physxcooking.dll" || n == "nxcooking.dll" -> listOf("physx")
            n == "xlive.dll" -> listOf(XLIVE)
            else -> emptyList()
        }
    }

    // ---- which Steam app ----

    /** The appId of Bannerlator's own Steam install in [dir] (the installed-games table). */
    private fun installedSteamApp(dir: File, context: Context?): Int? = runCatching {
        val want = dir.canonicalPath.trimEnd('/')
        val db = if (context != null) SteamDatabase.getInstance(context) else SteamRepository.getInstance().database
        db?.installedGames.orEmpty().firstOrNull { r ->
            r.installDir?.takeIf { it.isNotBlank() }?.let { File(it).canonicalPath.trimEnd('/') == want } == true
        }?.appId?.takeIf { it > 0 }
    }.getOrNull()

    /** The appId of the appmanifest in the library that installs into [dir]. */
    private fun manifestAppId(dir: File): Int? = runCatching {
        val installDir = Regex("\"installdir\"\\s+\"${Regex.escape(dir.name)}\"", RegexOption.IGNORE_CASE)
        dir.parentFile?.parentFile?.listFiles { m -> m.name.startsWith("appmanifest_") && m.name.endsWith(".acf") }
            ?.firstOrNull { m -> m.length() < 1L shl 20 && installDir.containsMatchIn(m.readText()) }
            ?.name?.removePrefix("appmanifest_")?.removeSuffix(".acf")?.toIntOrNull()
    }.getOrNull()
}
