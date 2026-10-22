package com.winlator.star.components

import android.content.Context
import com.winlator.star.store.GogRedistInstaller
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What the store a game came from says it needs (DroidDeck's WinCompSources, per source):
 *
 * - **GOG**: the build's `dependencies` ("MSVC2017_x64", "DirectX", "DOTNET48"...), kept at download
 *   by GogDownloadManager (`gog_deps_<gameId>`); the game is found by its install folder.
 * - **Epic Games**: the manifest's prerequisite (usually Unreal's prerequisite setup: VC++ and the
 *   DirectX June 2010 runtime), kept at install by EpicDownloadManager ([rememberEpicPrereq]).
 * - **Amazon Games**: fuel.json's PostInstall installers, read from the game's folder.
 * - **Xbox**: MicrosoftGame.config's KnownDependency entries (VC14), see XboxDependencies.
 *
 * Steam's own list is [SteamRedists]. Each store entry maps to catalog names here; one that maps to
 * nothing is left out. Never throws; filesystem and SharedPreferences only.
 */
object StoreRedists {
    private const val GOG_PREFS = "bh_gog_prefs"
    private const val PREFS = "component_store_lists"
    private const val MAX_UP = 6

    private val VC_YEAR = mapOf(
        "2005" to "vcredist2005", "2008" to "vcredist2008", "2010" to "vcredist2010", "2012" to "vcredist2012",
        "2013" to "vcredist2013", "2015" to "vcredist2015", "2017" to "vcredist2015", "2019" to "vcredist2019", "2022" to "vcredist2022",
    )
    private val DOTNET = mapOf(
        "35" to "dotnet35", "40" to "dotnet40", "45" to "dotnet45", "451" to "dotnet452", "452" to "dotnet452",
        "46" to "dotnet46", "461" to "dotnet461", "462" to "dotnet462", "47" to "dotnet472", "471" to "dotnet472",
        "472" to "dotnet472", "48" to "dotnet48", "481" to "dotnet48",
    )
    private val UE_PREREQ = Regex("""(?i)ue\d?prereqsetup""")

    /** A GOG dependency id to its components; empty for one that is none of ours (ISI scripts, GOG's own helpers). */
    fun gog(id: String): List<String> {
        val key = id.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        Regex("""^(?:MSVC|VCREDIST|VC)(\d{4})""").find(key)?.let { m -> return listOfNotNull(VC_YEAR[m.groupValues[1]]) }
        Regex("""^(?:DOTNET|NETFRAMEWORK|DOTNETFRAMEWORK)(\d{2,3})""").find(key)?.let { m -> return listOfNotNull(DOTNET[m.groupValues[1]]) }
        return when {
            key.startsWith("DIRECTX") || key.startsWith("DX9") || key == "DXSETUP" || key.startsWith("D3DX") -> listOf("d3dx9")
            key.startsWith("PHYSX") -> listOf("physx")
            key.startsWith("OPENAL") -> listOf("oalinst")
            key == "XNA31" || key == "XNA3" -> listOf("xna31")
            key.startsWith("XNA") -> listOf("xna40")
            key.startsWith("XLIVE") || key.startsWith("GFWL") -> listOf("XLiveRedist")
            key.startsWith("XACT") -> listOf("xact")
            else -> emptyList()
        }
    }

    /** An installer a store runs ([path], or only its [name]) to its components. */
    fun installer(path: String, name: String = ""): List<String> = when {
        UE_PREREQ.containsMatchIn(path) || UE_PREREQ.containsMatchIn(name.replace(" ", "")) ||
            name.contains("UE4 Prerequisites", true) || name.contains("UE5 Prerequisites", true) ||
            name.contains("Unreal Engine Prerequisites", true) -> listOf("vcredist2022", "d3dx9")
        else -> DependencyDetector.componentsFor(path).ifEmpty { if (path.isEmpty()) gog(name) else emptyList() }
    }

    /** Keeps an Epic game's manifest prerequisite by its install folder (EpicDownloadManager, at install). */
    @JvmStatic
    fun rememberEpicPrereq(context: Context?, installDir: File?, name: String?, path: String?) {
        if (context == null || installDir == null) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("epic:" + key(installDir), JSONObject().put("name", name.orEmpty()).put("path", path.orEmpty()).toString())
                .apply()
        }
    }

    /** What the store the game in [folder] (or above [exe]) came from lists; empty when it isn't a store install we know. */
    fun detect(context: Context?, folder: File?, exe: File?): List<DependencyDetector.Recommendation> = try {
        val dirs = ancestors(exe?.parentFile ?: folder)
        gogList(context, dirs).ifEmpty { epicList(context, dirs) }.ifEmpty { amazonList(dirs) }.ifEmpty { xboxList(dirs) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun gogList(context: Context?, dirs: List<File>): List<DependencyDetector.Recommendation> {
        context ?: return emptyList()
        val prefs = context.getSharedPreferences(GOG_PREFS, Context.MODE_PRIVATE)
        val keys = dirs.map { key(it) }.toSet()
        val gameId = prefs.all.entries.firstOrNull { (k, v) -> k.startsWith("gog_dir_") && (v as? String)?.let { key(File(it)) in keys } == true }
            ?.key?.removePrefix("gog_dir_")
            ?: dirs.firstNotNullOfOrNull { d -> d.listFiles { f -> f.name.startsWith("goggame-") && f.name.endsWith(".info") }?.firstOrNull() }
                ?.name?.removePrefix("goggame-")?.removeSuffix(".info")
            ?: return emptyList()
        val deps = GogRedistInstaller.readGameDeps(context, gameId)?.deps ?: return emptyList()
        return deps.flatMap { id -> gog(id).map { rec(it, "GOG installs $id with this game") } }
    }

    private fun epicList(context: Context?, dirs: List<File>): List<DependencyDetector.Recommendation> {
        context ?: return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = dirs.firstNotNullOfOrNull { prefs.getString("epic:" + key(it), null) } ?: return emptyList()
        val o = JSONObject(raw)
        val path = o.optString("path")
        val name = o.optString("name")
        if (path.isEmpty() && name.isEmpty()) return emptyList()
        val what = name.ifEmpty { path.replace('\\', '/').substringAfterLast('/') }
        return installer(path, name).map { rec(it, "Epic installs $what with this game") }
    }

    private fun amazonList(dirs: List<File>): List<DependencyDetector.Recommendation> {
        val fuel = dirs.map { File(it, "fuel.json") }.firstOrNull { it.isFile && it.length() < 1L shl 20 } ?: return emptyList()
        val arr = JSONObject(fuel.readText()).optJSONArray("PostInstall") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("Command")?.takeIf { c -> c.isNotEmpty() } }
            .flatMap { command ->
                val what = command.replace('\\', '/').substringAfterLast('/')
                installer(command).map { rec(it, "Amazon installs $what with this game") }
            }
    }

    private fun xboxList(dirs: List<File>): List<DependencyDetector.Recommendation> {
        val game = dirs.firstOrNull { com.winlator.star.store.xbox.XboxDependencies.configFile(it) != null } ?: return emptyList()
        return com.winlator.star.store.xbox.XboxDependencies.components(game)
            .map { (dependency, component) -> rec(component, "The Microsoft Store installs $dependency with this game") }
    }

    private fun rec(component: String, reason: String) =
        DependencyDetector.Recommendation(component, DependencyDetector.labelOf(component), reason, DependencyDetector.Kind.STORE)

    private fun ancestors(start: File?): List<File> {
        val out = ArrayList<File>()
        var d = start?.absoluteFile
        while (d != null && out.size < MAX_UP) { out += d; d = d.parentFile }
        return out
    }

    /** A folder's spelling for matching: canonical, so /data/data and /data/user/0 agree. */
    private fun key(f: File): String = (runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath).trimEnd('/')
}
