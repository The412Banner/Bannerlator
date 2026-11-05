package com.winlator.star.core.unpack

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-tap "Fast Extract": classifies a file with the SAME routing the Unpack screen uses, then
 * starts [UnpackService] with sensible defaults (a new sibling folder, Auto power, 1 MB buffer) and
 * lets the app-wide progress pill take over — no full screen.
 *
 * "Fast" means fast-to-START (no folder picker / Power / buffer step), NOT a faster engine: the
 * throughput is identical to "Unpack Archive…" because it drives the very same engines
 * (7-Zip / innoextract / native unarc / the [ExtraFormats] ones) through the very same service.
 *
 * The caller drives the [Outcome]: a job either starts (pill appears) or the file needs the full
 * screen (container-only repack, or All Files Access) / isn't an archive at all — never a silent
 * no-op.
 */
object FastExtract {

    sealed interface Outcome {
        /** A job was started; [name] is what's being unpacked (for a confirming toast). */
        data class Started(val name: String) : Outcome
        /** Another unpack is already running (one at a time). */
        object Busy : Outcome
        /** Not a recognized archive/installer by content. */
        data class NotArchive(val name: String) : Outcome
        /** Fast Extract can't handle it silently — open the full screen (with an optional toast). */
        data class OpenScreen(val archivePath: String, val toast: String?) : Outcome
    }

    /**
     * @param destOverride when non-null (BFE dual-pane: the OTHER pane's directory), the extracted
     *   game/archive folder lands inside it instead of a sibling of the source; null keeps the
     *   engine's default (a new sibling folder next to the archive).
     */
    suspend fun start(context: Context, file: File, destOverride: File? = null): Outcome {
        if (UnpackManager.current.isRunning) return Outcome.Busy

        val innoTarget = SevenZip.resolveInnoTarget(file)
        val isInno = innoTarget != null
        val archive = innoTarget ?: file

        // All-Files-Access is needed for the direct writes Fast Extract's default (a /storage sibling)
        // will do; if it's off, hand off to the full screen where the grant card lives.
        val hasAllFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        if (isInno) {
            val cls = withContext(Dispatchers.IO) { SevenZip.classifyInno(context, archive) }
            val size = innoPayloadSize(archive)
            return when (cls.route) {
                SevenZip.InnoRoute.INNOEXTRACT -> {
                    if (!hasAllFiles) return Outcome.OpenScreen(file.absolutePath, null)
                    val dest = defaultDest(archive, isInno = true, overrideBase = destOverride)
                    // Auto-detect GOG DLC/extra setups in the same folder and extract them all into one
                    // merged game folder (base first, DLC overlaid on top). `size` already sums every
                    // .bin in the folder, so it doubles as the combined batch payload for the bar.
                    val installers = withContext(Dispatchers.IO) { SevenZip.siblingInnoInstallers(archive) }
                    if (installers.size > 1) {
                        UnpackService.startBatch(context, installers.map { it.absolutePath }, dest, size)
                        Outcome.Started("${archive.name} + ${installers.size - 1} DLC")
                    } else {
                        UnpackService.start(context, archive.absolutePath, dest, 1, ReadBuffer.MB1.bytes, true, size, "inno")
                        Outcome.Started(archive.name)
                    }
                }
                SevenZip.InnoRoute.FREEARC_NATIVE -> {
                    if (!hasAllFiles) return Outcome.OpenScreen(file.absolutePath, null)
                    val job = cls.freeArcArchive ?: archive
                    val dest = defaultDest(archive, isInno = true, overrideBase = destOverride)
                    UnpackService.start(context, job.absolutePath, dest, 1, ReadBuffer.MB1.bytes, true, size, "unarc")
                    Outcome.Started(job.name)
                }
                SevenZip.InnoRoute.FREEARC_LAYERS -> {
                    if (!hasAllFiles) return Outcome.OpenScreen(file.absolutePath, null)
                    val job = cls.freeArcArchive ?: archive
                    val dest = defaultDest(archive, isInno = true, overrideBase = destOverride)
                    UnpackService.start(context, job.absolutePath, dest, 1, ReadBuffer.MB1.bytes, true, size, Unarc.ENGINE_LAYERS)
                    Outcome.Started(job.name)
                }
                // A layer BFE can't decode, or no engine: the Unpack screen explains and offers Bannerlator.
                SevenZip.InnoRoute.CONTAINER_ONLY ->
                    Outcome.OpenScreen(file.absolutePath, "This repack has to run its own installer — opening Unpack")
            }
        }

        // lzip / lzop / LZ4 / ECM / ALZ / EGG / ACE / Amiga LZX / OTA payload — the ExtraFormats engine.
        // Anything it can't do (ACE 2.0, encrypted EGG, incremental OTA…) opens the screen, which says why.
        val extra = withContext(Dispatchers.IO) { ExtraFormats.probe(context, archive) }
        if (extra != null) {
            if (extra.problem != null || !hasAllFiles) return Outcome.OpenScreen(file.absolutePath, null)
            val dest = defaultDest(archive, isInno = false, overrideBase = destOverride)
            UnpackService.start(context, archive.absolutePath, dest, 1, ReadBuffer.MB1.bytes, false, archive.length(), ExtraFormats.ENGINE)
            return Outcome.Started(archive.name)
        }

        // Plain file: judge by content (7zz l), exactly like the screen's pre-flight.
        val info = withContext(Dispatchers.IO) { SevenZip.list(context, archive) }
        if (info?.type.isNullOrBlank()) return Outcome.NotArchive(file.name)
        if (!hasAllFiles) return Outcome.OpenScreen(file.absolutePath, null)

        val dest = defaultDest(archive, isInno = false, overrideBase = destOverride)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        UnpackService.start(context, archive.absolutePath, dest, UnpackManager.mmtFor(PowerMode.AUTO, cores), ReadBuffer.MB1.bytes, false, archive.length(), "7z")
        return Outcome.Started(archive.name)
    }

    /** The screen's default destination: a NEW sibling folder named for the game/archive, uniquified. */
    private fun defaultDest(archive: File, isInno: Boolean, overrideBase: File? = null): String {
        val name = if (isInno) (archive.parentFile?.name?.takeIf { it.isNotBlank() } ?: "game")
        else SevenZip.suggestedTargetName(archive)
        val base = overrideBase ?: (if (isInno) (archive.parentFile?.parentFile ?: archive.parentFile) else archive.parentFile)
        var candidate = File(base, name)
        var i = 1
        while (candidate.exists()) { candidate = File(base, "$name ($i)"); i++ }
        return candidate.absolutePath
    }

    /** For InnoSetup: the Setup-*.bin payload total (the data the engine reads), not the tiny .exe. */
    private fun innoPayloadSize(archive: File): Long =
        archive.parentFile?.listFiles()
            ?.filter { it.isFile && (it.extension.equals("bin", true) || it == archive) }
            ?.sumOf { it.length() } ?: archive.length()
}
