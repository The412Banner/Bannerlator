package com.winlator.star.store.xbox

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Cloud saves on session exit. Xodus Nexus' xgameruntime starts a helper
 * (`rundll32.exe xgameruntime.dll,XodusSaveFilesMonitor`) that uploads the game's saves once the game process
 * has exited, through our bridge and account service. Bannerlator's teardown stops every Wine process at once,
 * which would kill that helper before it uploads; so for an Xbox session with cloud saves on, the shutdown
 * screen first ends only the game, then waits for the helper to finish (bounded), and only then tears down.
 */
object XboxSaveSync {
    private const val HELPER = "XodusSaveFilesMonitor"

    private fun commandLines(): List<Pair<Int, String>> =
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == android.os.Process.myPid()) return@mapNotNull null
            val cmd = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@mapNotNull null
            if (cmd.isEmpty()) null else pid to String(cmd).replace('\u0000', ' ')
        }

    /** Ends the game ([exeName]), then waits up to [timeoutMs] for the save helper to finish its final upload. */
    @JvmStatic fun finish(app: Context, exeName: String, timeoutMs: Long) {
        val helpers = commandLines().filter { it.second.contains(HELPER) }.map { it.first }
        if (helpers.isEmpty()) {
            XboxDiag.log(app, "save sync on exit: no save helper running")
            return
        }
        val games = commandLines().filter { (_, cmd) -> !cmd.contains(HELPER) && cmd.contains(exeName, ignoreCase = true) && !cmd.contains("winhandler", true) && !cmd.contains("explorer", true) }
        games.forEach { (pid, _) -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
        val start = System.currentTimeMillis()
        while (helpers.any { File("/proc/$it").exists() } && System.currentTimeMillis() - start < timeoutMs) Thread.sleep(250)
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        if (helpers.any { File("/proc/$it").exists() })
            XboxDiag.log(app, "save sync on exit: helper still running after ${"%.1f".format(seconds)} s; local saves upload at the next launch")
        else XboxDiag.log(app, "save sync on exit: helper finished in ${"%.1f".format(seconds)} s (ended ${games.size} game process(es))")
    }
}
