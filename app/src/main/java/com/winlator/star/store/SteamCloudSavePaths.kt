package com.winlator.star.store

import android.content.Context
import android.os.Environment
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.SaveLocator
import java.io.File

/**
 * Path translation for the three-tier Steam Cloud save model
 * (Cloud ⇄ **Library** ⇄ **Container**).
 *
 * The Library stores each file under its Steam UFS "cloud path layout": every path begins with a
 * `%Root%` placeholder segment kept verbatim as a literal folder name (see
 * [SteamCloudSaveManager.sanitizeRelative] / [SteamCloudSaveManager.remotePathOf]). This object is
 * the single place that maps that leading `%Root%` token to a concrete directory inside a Wine
 * container (Apply) and back (Collect).
 *
 * The `%Root%` → path table is derived from GameNative's `PathType.toAbsPath` (the authoritative
 * JavaSteam UFS root spellings). Everything Windows-side hangs off the container's Wine user
 * profile, which is exactly [SaveLocator.profileDir] — reused verbatim so a taught save folder and a
 * cloud-mapped one resolve to the identical base.
 *
 * SAFETY: every translation rejects `..`/escape. [toContainerPath] refuses any result that would
 * canonicalize outside its mapped root; [toLibraryRel] only ever returns a path strictly under a
 * known root. Both return null (⇒ caller skips the file) rather than guessing.
 */
object SteamCloudSavePaths {

    private const val TAG = "BH_STEAM_CLOUD"

    // ── One UFS root: its `%Token%` placeholder + how to resolve its base dir in a container. ──
    // `install` = the game's shared Steam install dir (SteamCloudSaveManager passes it through).
    private class Root(val token: String, val baseDir: (Container, String) -> File)

    /**
     * The UFS `%Root%` table, ordered MOST-SPECIFIC → LEAST-SPECIFIC. Order matters only for
     * [toLibraryRel]: the profile sub-roots (Documents, AppData subdirs, Saved Games) are nested under the
     * profile itself, so `%Root%` (the bare profile) MUST be matched last, and `AppData/LocalLow`
     * before `AppData/Local` for good measure. [GameInstall] / [WinProgramData] live outside the
     * profile entirely.
     */
    private val ROOTS: List<Root> = listOf(
        // Shared Steam install dir — OUTSIDE the profile.
        Root("%GameInstall%") { _, install -> File(install) },
        // ProgramData — OUTSIDE the profile (drive_c/ProgramData).
        Root("%WinProgramData%") { c, _ -> File(c.rootDir, ".wine/drive_c/ProgramData") },
        // Profile-relative roots.
        Root("%WinSavedGames%") { c, _ -> File(SaveLocator.profileDir(c), "Saved Games") },
        Root("%WinAppDataLocalLow%") { c, _ -> File(SaveLocator.profileDir(c), "AppData/LocalLow") },
        Root("%WinAppDataLocal%") { c, _ -> File(SaveLocator.profileDir(c), "AppData/Local") },
        Root("%WinAppDataRoaming%") { c, _ -> File(SaveLocator.profileDir(c), "AppData/Roaming") },
        Root("%WinMyDocuments%") { c, _ -> File(SaveLocator.profileDir(c), "Documents") },
        // The Wine user profile itself — LAST (ancestor of every Win* root above).
        Root("%Root%") { c, _ -> SaveLocator.profileDir(c) },
    )

    /**
     * Extra spellings the CM may send for a root, keyed by the PERCENT-STRIPPED lowercased token,
     * valued by the percent-stripped canonical token in [ROOTS]. [lookupRoot] strips percents before
     * consulting this, so a token resolves whether it arrives as `%root_mod%`, `ROOT_MOD`, or
     * `root_mod`. Note: SteamUserData (`SteamUserBaseStorage` — the userdata/remote store) is NOT
     * mapped (needs the account id we don't have here), so those files are skipped, never guessed.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "winappdata" to "winappdataroaming",       // legacy short form of Roaming
        "windowshome" to "root",                   // GameNative alias for the profile root
        "root_mod" to "root",                      // GameNative alias for the profile root
        "steamclouddocuments" to "winmydocuments", // GameNative maps this to Documents
    )

    // ── Public API ───────────────────────────────────────────────────────────────

    /** Managed local Library folder for this game (canonical local copy). */
    fun libraryDir(ctx: Context, appId: Int): File =
        File(Environment.getExternalStorageDirectory(), "Bannerlator/SteamCloudSaves/$appId")

    /**
     * The game's launch container — the container of the shortcut whose exec target sits under
     * [installDir]. Enumerates every container's `.desktop` shortcut via
     * [ContainerManager.loadShortcuts]. Returns null if no shortcut points into the game's install
     * dir (⇒ game not set up in a container yet).
     */
    fun resolveContainer(ctx: Context, appId: Int, installDir: String): Container? {
        if (installDir.isBlank()) return null

        val manager = ContainerManager(ctx)
        val shortcuts = try { manager.loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }

        // Build the comparison keys: the install dir made relative to the imagefs root (what a
        // Winlator "Z:\…" exec path maps to) plus the absolute install path, both '/'-normalized,
        // lowercased, and fenced with '/' so "…/Game/" never matches "…/Game2/".
        val imageFsRoot = File(ctx.filesDir, "imagefs").absolutePath.replace('\\', '/').trimEnd('/')
        val instAbs = installDir.replace('\\', '/').trimEnd('/')
        val instRel = if (instAbs.lowercase().startsWith(imageFsRoot.lowercase()))
            instAbs.substring(imageFsRoot.length).trimStart('/') else instAbs.trimStart('/')
        val keys = listOf("/${instRel.lowercase()}/", "/${instAbs.trimStart('/').lowercase()}/")

        for (sc in shortcuts) {
            val raw = sc.path ?: continue
            // Normalize the Winlator exec target: '\'→'/', lowercase, drop a leading drive letter
            // ("z:"), fence with a leading '/' so the key's boundary matches.
            var exec = raw.replace('\\', '/').lowercase().trim()
            exec = exec.replaceFirst(Regex("^[a-z]:"), "")
            if (!exec.startsWith("/")) exec = "/$exec"
            if (keys.any { it.length > 2 && exec.contains(it) }) return sc.container
        }
        return null
    }

    /** Human label for dialogs, e.g. "Container 2 — Default". */
    fun containerLabel(container: Container): String {
        val name = container.name
        return if (!name.isNullOrBlank()) "Container ${container.id} — $name"
        else "Container ${container.id}"
    }

    /**
     * Library-relative `"%Root%/rest"` path → absolute [File] in [container]. Null if the leading
     * root token is unknown/unsupported, if the remainder is unsafe (`..`), or if the result would
     * escape its mapped root.
     */
    fun toContainerPath(libraryRel: String, container: Container, installDir: String): File? {
        val (root, remainder) = parseRoot(libraryRel) ?: return null
        val base = root.baseDir(container, installDir)
        val dest = if (remainder.isEmpty()) base else File(base, remainder.joinToString("/"))

        // Escape guard: the canonicalized destination must be the root itself or strictly under it.
        val baseCanon = try { base.canonicalPath } catch (e: Exception) { return null }
        val destCanon = try { dest.canonicalPath } catch (e: Exception) { return null }
        if (destCanon != baseCanon && !destCanon.startsWith(baseCanon + File.separator)) {
            Log.w(TAG, "Rejecting container path escaping ${root.token}: $libraryRel")
            return null
        }
        return dest
    }

    /**
     * Absolute file under [container] → `"%Root%/rest"` library-relative path. Null if [abs] is
     * under no known root. Roots are tested most-specific first so a file in Documents maps to
     * `%WinMyDocuments%`, never `%Root%/Documents/…`.
     */
    fun toLibraryRel(abs: File, container: Container, installDir: String): String? {
        val target = try { abs.canonicalPath } catch (e: Exception) { return null }
        for (root in ROOTS) {
            val base = try {
                root.baseDir(container, installDir).canonicalPath
            } catch (e: Exception) { continue }
            if (target == base) return root.token // the root dir itself
            if (target.startsWith(base + File.separator)) {
                val rel = target.substring(base.length + 1).replace(File.separatorChar, '/')
                if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
                // Real Steam data fuses %GameInstall% straight onto the path (e.g. "%GameInstall%hl2/
                // save/…", no separator — confirmed from HL2 cloud manifests). Emit that same shape so
                // an uploaded path round-trips; other roots keep the "%Token%/rest" form. [parseRoot]
                // accepts both regardless.
                return if (root.token == "%GameInstall%") "${root.token}$rel" else "${root.token}/$rel"
            }
        }
        return null
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /** Loose files with no recognizable root token fall back here (game-install-relative). */
    private val DEFAULT_ROOT: Root get() = ROOTS.first { it.token == "%GameInstall%" }

    /**
     * Split a library-relative path into its UFS root + safe remainder segments. Real Steam cloud
     * paths arrive in three shapes — all handled here (HL2 uses all three):
     *   - `%GameInstall%hl2/save/x.sav` — root fused onto the path (leading `%…%`, NO separator)
     *   - `ROOT_MOD/cfg/config.cfg`     — root as a bare leading segment (no percents)
     *   - `cfg/config.cfg`              — no root token at all → [DEFAULT_ROOT]
     * (Steam's own client sometimes embeds `%GameInstall%` in the filename instead of splitting it —
     * see GameNative SteamAutoCloud's identical work-around.) Rejects any `..` in the remainder.
     * Null ⇒ empty/unsafe, or a `%…%` token we don't map (⇒ caller skips + logs).
     */
    private fun parseRoot(path: String): Pair<Root, List<String>>? {
        val norm = path.replace('\\', '/').trimStart('/')
        if (norm.isEmpty()) return null

        val root: Root
        val rest: String
        val leadingPct = Regex("^%[^%]+%").find(norm)   // fused OR standalone %Token%
        if (leadingPct != null) {
            root = lookupRoot(leadingPct.value) ?: run {
                Log.w(TAG, "Unknown UFS root token '${leadingPct.value}' in: $path"); return null
            }
            rest = norm.substring(leadingPct.value.length).trimStart('/')
        } else {
            val firstSeg = norm.substringBefore('/')
            val bareRoot = lookupRoot(firstSeg)
            if (bareRoot != null) {
                root = bareRoot
                rest = norm.substringAfter('/', "")
            } else {
                root = DEFAULT_ROOT                     // no recognizable root token
                rest = norm
                Log.w(TAG, "No UFS root token in '$path' → defaulting to %GameInstall%")
            }
        }

        val parts = rest.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return root to parts
    }

    /** Resolve a root token — with or without surrounding `%…%`, case-insensitively, aliases applied. */
    private fun lookupRoot(token: String): Root? {
        val bare = token.lowercase().trim('%')
        val canonical = ALIASES[bare] ?: bare
        return ROOTS.firstOrNull { it.token.lowercase().trim('%') == canonical }
    }
}
