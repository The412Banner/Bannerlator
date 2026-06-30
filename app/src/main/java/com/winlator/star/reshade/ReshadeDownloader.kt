package com.winlator.star.reshade

import android.content.Context
import android.util.Log
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a ReShade effect archive (<id>.tzst) from the catalog and installs it into the drop-in
 * folder (getExternalFilesDir/ReShade/<id>/), so ReshadeManager's scanner then picks it up exactly
 * like a hand-dropped effect. Reuses the same primitives the rest of the app uses for content:
 * Downloader.downloadFile (HTTP + progress) and TarCompressorUtils.extract (zstd tar), rather than
 * the heavyweight ContentProfile/DownloadCoordinator pipeline (these effect archives are small and
 * not versioned content the container tracks).
 */
object ReshadeDownloader {
    private const val TAG = "ReshadeDownloader"

    enum class Phase { DOWNLOAD, EXTRACT }

    /** Download + extract [entry] into the drop-in folder. progress(phase, 0..1). Returns true on
     *  success (the effect is then on disk under getReshadeDir/<id>/). Runs on the IO dispatcher. */
    suspend fun install(
        context: Context,
        entry: ReshadeCatalogEntry,
        progress: (Phase, Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "reshade_dl").apply { mkdirs() }
        val archive = File(cacheDir, "${safe(entry.id)}.tzst")
        val staging = File(cacheDir, "stage_${safe(entry.id)}").apply { deleteRecursively(); mkdirs() }
        try {
            val url = ReshadeCatalog.resolveUrl(entry)
            if (!Downloader.downloadFile(url, archive) { f -> progress(Phase.DOWNLOAD, f.coerceIn(0f, 1f)) }) {
                Log.w(TAG, "download failed: $url")
                return@withContext false
            }
            progress(Phase.EXTRACT, 0f)
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, staging)) {
                Log.w(TAG, "extract failed: $archive")
                return@withContext false
            }

            // Normalize layout: the archive may carry the effect files at its root OR wrapped in a
            // single <id>/ folder. Find the directory that actually holds a .fx and treat that as the
            // effect root, so either packing convention lands correctly under ReShade/<id>/.
            val effectRoot = findEffectRoot(staging)
            val target = File(ReshadeManager.getReshadeDir(context), entry.id).apply {
                deleteRecursively(); mkdirs()
            }
            effectRoot.listFiles()?.forEach { child ->
                child.copyRecursively(File(target, child.name), overwrite = true)
            }
            progress(Phase.EXTRACT, 1f)
            // Sanity: a usable effect must end up with at least one .fx.
            val ok = ReshadeManager.findFxFile(target) != null
            if (!ok) Log.w(TAG, "no .fx after install for ${entry.id}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "install failed for ${entry.id}", t)
            false
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun findEffectRoot(staging: File): File {
        fun hasFx(d: File) = d.listFiles()?.any { it.isFile && it.name.endsWith(".fx", true) } == true
        if (hasFx(staging)) return staging
        staging.listFiles()?.filter { it.isDirectory }?.firstOrNull { hasFx(it) }?.let { return it }
        return staging
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
