package com.winlator.star.store

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.types.KeyValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Steam's launch entries (app-info `config/launch`) for games BL starts itself (Goldberg / raw / Headless
 * Steam). Steam runs each entry's executable with that entry's arguments — Counter-Strike (app 10) is
 * `hl.exe -game cstrike` — so a direct launch of the same exe needs the same arguments, or it starts
 * something else (Half-Life). SteamLite / Real Steam are not touched: there Steam picks the entry itself.
 *
 * Rules (GameNative #2408 + #2427): only Windows entries (oslist windows or empty); only entries the
 * installed branch gets (BetaKey); an entry's arguments go only to that entry's own exe; the user's own
 * arguments follow them and are never replaced, and nothing is added when the user's arguments already
 * carry them. The exe a shortcut starts is never changed.
 *
 * Entries are kept per app in prefs [PREFS] (written when app-info is read: shortcut add, launch, library
 * sync of an app already kept), so an offline launch still has them.
 */
object SteamLaunchEntries {
    private const val TAG = "BH_STEAM_LAUNCH"
    private const val PREFS = "steam_launch_entries"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(
        val index: Int,
        val executable: String,
        val arguments: String = "",
        val type: String = "",
        val osList: String = "",
        val betaKey: String = "",
    )

    // ── pure rules ───────────────────────────────────────────────────────────────────────────────

    /** oslist empty, or naming windows. */
    fun isWindows(e: Entry): Boolean =
        e.osList.isBlank() || e.osList.split(',').any { it.trim().equals("windows", ignoreCase = true) }

    /**
     * The entries Steam offers on [installedBranch] (null/blank = "public"): entries without a BetaKey plus
     * those whose BetaKey (comma list) names the branch. A list with no BetaKey at all, or one the filter
     * would empty, comes back unchanged.
     */
    fun forBranch(entries: List<Entry>, installedBranch: () -> String?): List<Entry> {
        if (entries.none { it.betaKey.isNotBlank() }) return entries
        val branch = installedBranch()?.takeIf { it.isNotBlank() } ?: "public"
        val kept = entries.filter { e ->
            e.betaKey.isBlank() || e.betaKey.split(',').any { it.trim().equals(branch, ignoreCase = true) }
        }
        return kept.ifEmpty { entries }
    }

    /** Windows entries with an executable, for the installed branch, in Steam's order. */
    fun usable(entries: List<Entry>, installedBranch: () -> String?): List<Entry> =
        forBranch(entries.filter { isWindows(it) && normalizeExe(it.executable).isNotEmpty() }, installedBranch)

    /** Slashes unified, outer spaces/slashes and a leading "./" removed. */
    fun normalizeExe(path: String): String {
        var p = path.replace('\\', '/').trim().trim('/')
        while (p.startsWith("./")) p = p.substring(2).trimStart('/')
        return p
    }

    /** The entry that runs [exeRelPath] (case-insensitive): a "default"/untyped one first, else the first. */
    fun entryFor(entries: List<Entry>, exeRelPath: String): Entry? {
        val exe = normalizeExe(exeRelPath)
        if (exe.isEmpty()) return null
        val hits = entries.filter { normalizeExe(it.executable).equals(exe, ignoreCase = true) }
        return hits.firstOrNull { it.type.isBlank() || it.type.equals("default", ignoreCase = true) } ?: hits.firstOrNull()
    }

    /**
     * The arguments to put before [userArgs] for [exeRelPath]: the matching entry's own arguments, or ""
     * when no entry runs that exe, it has none, or [userArgs] already contain them.
     */
    fun argumentsFor(entries: List<Entry>, exeRelPath: String, userArgs: String): String {
        val args = sanitize(entryFor(entries, exeRelPath)?.arguments.orEmpty())
        if (args.isEmpty()) return ""
        val have = " " + squash(userArgs) + " "
        return if (have.contains(" " + squash(args) + " ")) "" else args
    }

    /** [candidates] with the exes Steam's entries run moved to the front (entry order), the rest as they were. */
    fun preferEntryExes(entries: List<Entry>, relPathOf: (File) -> String?, candidates: List<File>): List<File> {
        if (entries.isEmpty() || candidates.size < 2) return candidates
        val front = ArrayList<File>()
        for (e in entries) {
            val exe = normalizeExe(e.executable)
            candidates.firstOrNull { c -> c !in front && relPathOf(c)?.let { normalizeExe(it).equals(exe, ignoreCase = true) } == true }
                ?.let { front.add(it) }
        }
        if (front.isEmpty()) return candidates
        return front + candidates.filter { it !in front }
    }

    /** [exe] relative to [installDir] with '/' separators, or null when it is not inside it. */
    fun relativePath(installDir: File, exe: File): String? {
        fun canon(f: File) = try { f.canonicalFile } catch (_: Throwable) { f.absoluteFile }
        val root = canon(installDir).path.trimEnd('/')
        val path = canon(exe).path
        if (root.isEmpty() || !path.startsWith("$root/")) return null
        return path.substring(root.length + 1)
    }

    fun encode(entries: List<Entry>, fetchedAt: Long): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("i", e.index).put("exe", e.executable).put("args", e.arguments)
                .put("type", e.type).put("os", e.osList).put("beta", e.betaKey))
        }
        return JSONObject().put("t", fetchedAt).put("e", arr).toString()
    }

    /** (fetchedAt, entries) from [encode]'s text, or null when unreadable. */
    fun decode(text: String?): Pair<Long, List<Entry>>? {
        if (text.isNullOrEmpty()) return null
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("e") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(e.optInt("i", i), e.optString("exe", ""), e.optString("args", ""),
                    e.optString("type", ""), e.optString("os", ""), e.optString("beta", ""))
            }
            o.optLong("t", 0L) to list
        } catch (_: Throwable) { null }
    }

    private fun sanitize(s: String): String = s.filter { it >= ' ' && it != '\u007f' }.trim()

    private fun squash(s: String): String = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    // ── app-info ─────────────────────────────────────────────────────────────────────────────────

    /** The launch entries in an app-info root (`appinfo` node), or null when it has no `config/launch`. */
    fun fromAppInfo(root: KeyValue?): List<Entry>? {
        val launch = root?.get("config")?.get("launch") ?: return null
        val children = launch.children
        if (children.isNullOrEmpty()) return null
        return children.mapIndexedNotNull { pos, c ->
            val exe = c.get("executable").value.orEmpty()
            if (exe.isBlank()) return@mapIndexedNotNull null
            val cfg = c.get("config")
            Entry(
                index = c.name?.trim()?.toIntOrNull() ?: pos,
                executable = exe,
                arguments = c.get("arguments").value.orEmpty(),
                type = c.get("type").value.orEmpty(),
                osList = cfg.get("oslist").value.orEmpty(),
                betaKey = cfg.get("betakey").value.orEmpty(),
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keeps [root]'s entries for [appId]; a full app-info with no launch block keeps an empty list. */
    fun remember(ctx: Context, appId: Int, root: KeyValue?) {
        if (appId <= 0 || root == null) return
        try {
            val entries = fromAppInfo(root)
                ?: if (root.get("common").children.isEmpty()) return else emptyList()
            prefs(ctx).edit().putString(appId.toString(), encode(entries, System.currentTimeMillis())).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "remember($appId) failed: ${t.message}")
        }
    }

    /** As [remember], only for an app already kept (library sync refresh; never grows the store). */
    @JvmStatic
    fun rememberIfKept(ctx: Context?, appId: Int, root: KeyValue?) {
        if (ctx == null || appId <= 0 || root == null) return
        try {
            if (!prefs(ctx).contains(appId.toString())) return
            remember(ctx, appId, root)
        } catch (_: Throwable) { }
    }

    /**
     * The kept entries for [appId]; read again from Steam (at most [timeoutMs]) when none are kept or they
     * are older than a week and the app's Steam session is signed in. Empty when nothing is known.
     * Blocks — worker threads only.
     */
    fun load(ctx: Context, appId: Int, timeoutMs: Long): List<Entry> {
        if (appId <= 0) return emptyList()
        val kept = decode(prefs(ctx).getString(appId.toString(), null))
        if (kept != null && System.currentTimeMillis() - kept.first < MAX_AGE_MS) return kept.second
        val repo = try { SteamRepository.getInstance() } catch (_: Throwable) { null }
        if (repo == null || !repo.isLoggedIn) return kept?.second ?: emptyList()
        val result = AtomicReference<KeyValue?>(null)
        val t = Thread({
            try {
                val kv = repo.fetchAppKeyValues(appId, timeoutMs)
                if (kv != null) { remember(ctx, appId, kv); result.set(kv) }
            } catch (e: Throwable) {
                Log.w(TAG, "app-info read for $appId failed: ${e.message}")
            }
        }, "SteamLaunchEntries")
        t.isDaemon = true
        t.start()
        try { t.join(timeoutMs) } catch (_: InterruptedException) { }
        val kv = result.get() ?: return kept?.second ?: emptyList()
        return fromAppInfo(kv) ?: emptyList()
    }

    private fun installedBranch(installDir: File, appId: Int): String? =
        try { SteamGameUpdater.readInstalledStamp(installDir)?.first } catch (_: Throwable) { null }
            ?: try { SteamPrefs.getSelectedBranch(appId) } catch (_: Throwable) { null }

    /** The Goldberg Cold Client loader stands in for the game exe named in its ini. */
    private fun gameExeOf(exe: File): File =
        if (exe.name.startsWith("steamclient_loader", ignoreCase = true)) GoldbergPatcher.gameExeFromLoaderIni(exe.parentFile) ?: exe
        else exe

    /**
     * Launch worker: the arguments a direct launch of [hostExe] (a Steam game in [installDir]) needs in
     * front of [userArgs], per the rules above; "" when none. Never throws.
     */
    @JvmStatic
    fun launchArguments(ctx: Context, appId: Int, installDir: String?, hostExe: File?, userArgs: String?): String {
        if (appId <= 0 || installDir.isNullOrEmpty() || hostExe == null) return ""
        return try {
            SteamPrefs.init(ctx)
            val dir = File(installDir)
            val rel = relativePath(dir, gameExeOf(hostExe)) ?: return ""
            val entries = usable(load(ctx, appId, 5_000L)) { installedBranch(dir, appId) }
            val args = argumentsFor(entries, rel, userArgs.orEmpty())
            Log.i(TAG, "appId=$appId exe=$rel entries=${entries.size} args='" + args + "'")
            args
        } catch (t: Throwable) {
            Log.w(TAG, "launchArguments($appId) failed: ${t.message}")
            ""
        }
    }

    /**
     * Shortcut add (worker thread): [candidates] (already ranked) with the exes Steam's entries run first.
     * Reads app-info when needed, which also keeps the entries for the launch.
     */
    fun rankCandidates(ctx: Context, appId: Int, installDir: File, candidates: List<File>): List<File> {
        if (appId <= 0 || candidates.isEmpty()) return candidates
        return try {
            SteamPrefs.init(ctx)
            val entries = usable(load(ctx, appId, 6_000L)) { installedBranch(installDir, appId) }
            preferEntryExes(entries, { relativePath(installDir, it) }, candidates)
        } catch (t: Throwable) {
            Log.w(TAG, "rankCandidates($appId) failed: ${t.message}")
            candidates
        }
    }
}
