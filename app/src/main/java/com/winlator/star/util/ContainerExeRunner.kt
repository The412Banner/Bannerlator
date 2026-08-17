package com.winlator.star.util

import android.content.Context
import android.content.Intent
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.WinePath
import java.io.File

/**
 * Launches a Windows executable inside a Winlator container, the same way the File Manager's "Run"
 * action does: map the exe's folder to a Wine drive letter (persisted on the container), write a
 * throwaway `.desktop` whose Exec is `wine <X:\path>`, and hand it to [XServerDisplayActivity].
 *
 * Extracted into a util so the standalone [com.winlator.star.UnpackArchiveActivity] can offer
 * "run Setup.exe in a container" for InnoSetup repacks that 7-Zip can't unpack, without depending on
 * MainActivity's ContainerManager instance.
 */
object ContainerExeRunner {

    /** All containers, or empty if none/unavailable. Cheap enough to call from a composable. */
    fun containers(context: Context): List<Container> =
        runCatching { ContainerManager(context).getContainers().toList() }.getOrDefault(emptyList())

    /**
     * Launches [exe] in [container]. Returns null on success, or a user-facing error message
     * (e.g. no free drive letters) on failure.
     */
    fun run(context: Context, container: Container, exe: File): String? {
        val shortcut = runCatching {
            val winPath = WinePath.resolveWindowsPath(container, exe.absolutePath)
            val escaped = WinePath.escapeForExec(winPath)
            val desktopDir = File(context.filesDir, "desktops").apply { mkdirs() }
            val safeName = exe.nameWithoutExtension
                .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "setup" }
            File(desktopDir, "$safeName.desktop").apply {
                writeText(
                    buildString {
                        append("[Desktop Entry]\n")
                        append("Name=").append(exe.nameWithoutExtension).append("\n")
                        append("Exec=wine ").append(escaped).append("\n")
                        append("Icon=").append(safeName).append("\n")
                        append("Type=Application\n")
                        append("StartupWMClass=explorer\n")
                        append("\ncontainer_id:").append(container.id).append("\n")
                        append("\n[Extra Data]\n")
                    }
                )
            }
        }.getOrElse { failure ->
            return if (failure is WinePath.NoFreeDriveLetterException) {
                "No free drive letters left in this container — remove one in its Drives tab"
            } else {
                "Couldn't prepare ${exe.name} to run"
            }
        }
        context.startActivity(
            Intent(context, XServerDisplayActivity::class.java)
                .putExtra("container_id", container.id)
                .putExtra("shortcut_path", shortcut.absolutePath)
                .putExtra("shortcut_name", shortcut.nameWithoutExtension)
        )
        return null
    }
}
