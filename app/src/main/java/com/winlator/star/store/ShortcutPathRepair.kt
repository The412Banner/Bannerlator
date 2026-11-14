package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import java.io.File

/**
 * A home-screen shortcut whose game was reinstalled or imported somewhere else still names the old game file.
 * At launch, [repairIfMoved] looks for the same file in the folder the game's store now records (Epic and Steam:
 * the shortcut's own game; GOG and Amazon shortcuts carry no game id, so only when exactly one installed game of
 * that store has the file) and points the shortcut there. Nothing changes when the old file still exists, the
 * store has no folder for the game, or the match is not unique.
 */
object ShortcutPathRepair {
    private const val TAG = "ShortcutPathRepair"

    /** [shortcut], or a reloaded copy pointing at the game's current folder. Never throws. */
    @JvmStatic
    fun repairIfMoved(ctx: Context, container: Container, shortcut: Shortcut): Shortcut =
        try {
            repair(ctx.applicationContext, container, shortcut) ?: shortcut
        } catch (t: Throwable) {
            Log.w(TAG, "repair skipped for ${shortcut.name}", t)
            shortcut
        }

    private fun repair(app: Context, container: Container, shortcut: Shortcut): Shortcut? {
        val winPath = shortcut.path?.trim().orEmpty()
        if (winPath.isEmpty()) return null
        val old = WinePath.resolveAndroidPath(container, winPath)
        if (old != null && old.exists()) return null
        // Drive first ("F:"), then folders, then the file name.
        val parts = winPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        val source = shortcut.getExtra("storeSource", "").trim().lowercase()
        val oneGame = source == "epic" || source == "steam"
        val dirs = gameDirs(app, source, shortcut).filter { it.isDirectory }
        val hits = dirs.mapNotNull { find(it, parts) }.distinctBy { it.absolutePath }
        if (hits.isEmpty() || (!oneGame && hits.size != 1)) {
            Log.i(TAG, "${shortcut.name}: game file missing ($winPath), ${hits.size} match(es) in $source folders: left as is")
            return null
        }
        val newExe = hits.first()

        val imageFsRoot = File(app.filesDir, "imagefs").absolutePath
        // Same Exec format as StarLaunchBridge: Z: for imagefs, the container's drive map elsewhere.
        val exec = if (newExe.absolutePath.startsWith(imageFsRoot)) {
            "Z:\\\\\\\\" + newExe.absolutePath.substring(imageFsRoot.length).trimStart('/').replace("/", "\\\\\\\\")
        } else {
            WinePath.escapeForExec(WinePath.resolveWindowsPath(container, newExe.absolutePath))
        }
        var replaced = false
        val lines = shortcut.file.readLines().map { line ->
            if (!replaced && line.trimStart().startsWith("Exec=")) {
                replaced = true
                "Exec=wine $exec"
            } else line
        }
        if (!replaced) return null
        shortcut.file.writeText(lines.joinToString("\n", postfix = "\n"))
        Log.i(TAG, "${shortcut.name}: game moved, shortcut now points to ${newExe.absolutePath} (was $winPath)")
        return Shortcut(container, shortcut.file)
    }

    /** Folders the shortcut's store records for its game (Epic, Steam) or for every installed game (GOG, Amazon). */
    private fun gameDirs(app: Context, source: String, shortcut: Shortcut): List<File> = when (source) {
        "epic" -> listOfNotNull(
            shortcut.getExtra("epicAppName", "").takeIf { it.isNotEmpty() }
                ?.let { app.getSharedPreferences("bh_epic_prefs", Context.MODE_PRIVATE).getString("epic_dir_$it", null) }
                ?.takeIf { it.isNotBlank() }?.let(::File),
        )
        "steam" -> listOfNotNull(
            shortcut.getExtra("steamAppId", "").toIntOrNull()?.takeIf { it > 0 }
                ?.let { SteamDatabase.getInstance(app).getGame(it) }
                ?.takeIf { it.isInstalled && !it.installDir.isNullOrBlank() }?.let { File(it.installDir) },
        )
        "gog" -> app.getSharedPreferences("bh_gog_prefs", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("gog_dir_") }.values
            .mapNotNull { (it as? String)?.takeIf { d -> d.isNotBlank() }?.let { d -> GogInstallPath.getInstallDir(app, d) } }
        "amazon" -> app.getSharedPreferences("bh_amazon_prefs", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("amazon_dir_") }.values
            .mapNotNull { (it as? String)?.takeIf { d -> d.isNotBlank() }?.let(::File) }
        else -> emptyList()
    }

    /** The longest tail of [parts] (never the drive, at least the file name) that is a file inside [dir]. */
    private fun find(dir: File, parts: List<String>): File? {
        for (start in 1 until parts.size) {
            val f = File(dir, parts.subList(start, parts.size).joinToString("/"))
            if (f.isFile) return f
        }
        return null
    }
}
