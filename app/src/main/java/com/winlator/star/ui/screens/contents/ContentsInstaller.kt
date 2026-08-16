package com.winlator.star.ui.screens.contents

import android.content.Context
import android.net.Uri
import android.util.Log
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.store.download.DownloadForegroundService
import com.winlator.star.store.download.DownloadScope
import com.winlator.star.util.ImportEtaTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Download + install driver for the Contents hub. Mirrors [com.winlator.star.store.download]'s
 * process-lifetime approach (runs on [DownloadScope.io], bracketed by [DownloadForegroundService],
 * progress published to [ContentDownloadRegistry]) so an install survives backgrounding, and adds
 * two things the plain component downloader doesn't do:
 *   1. the optional "Keep raw archive" copy into [ComponentLibrary] BEFORE the archive is consumed;
 *   2. routing GPU-driver items through [AdrenotoolsManager] instead of the `.wcp` pipeline.
 */
object ContentsInstaller {

    private const val TAG = "ContentsInstaller"

    /** Stable registry key for one catalog item. */
    fun keyFor(type: String, sourceName: String, versionName: String): String =
        "contents::$type::$sourceName::$versionName"

    /**
     * Kicks off a download+install for a remote catalog item. Idempotent per key. [onChanged] is
     * invoked on the install thread after a successful install so the caller can refresh badges.
     */
    fun install(
        appContext: Context,
        type: String,
        sourceName: String,
        versionName: String,
        downloadUrl: String,
        keepRaw: Boolean,
        onChanged: () -> Unit = {},
    ) {
        val ctx = appContext.applicationContext
        val key = keyFor(type, sourceName, versionName)
        ContentDownloadRegistry.get(key)?.let { if (!it.terminal) return }

        val fileName = downloadUrl.substringAfterLast('/').substringBefore('?')
            .ifBlank { "${versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.wcp" }
        val isDriver = ContentsTypes.isDriver(type)

        ContentDownloadRegistry.put(
            ContentDownloadState(
                key = key,
                title = versionName,
                type = type,
                verName = versionName,
                phase = ContentDownloadPhase.DOWNLOADING,
                fraction = 0f,
            ),
        )
        DownloadForegroundService.start(ctx)
        DownloadForegroundService.setProgress(key, "$versionName — Downloading 0%")

        DownloadScope.io.launch {
            val repo = RemoteSourceRepository(ctx)
            val library = ComponentLibrary(ctx)
            var temp: File? = null
            try {
                // ── Download phase ────────────────────────────────────────────
                temp = repo.downloadToTemp(downloadUrl) { msg ->
                    val pct = Regex("(\\d+)%").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val frac = (pct ?: 0) / 100f
                    ContentDownloadRegistry.update(key) {
                        it.copy(phase = ContentDownloadPhase.DOWNLOADING, fraction = frac)
                    }
                    DownloadForegroundService.setProgress(key, "$versionName — $msg")
                }

                // ── Keep raw archive (before install consumes the file) ───────
                if (keepRaw) {
                    runCatching { library.saveRaw(type, fileName, temp!!) }
                        .onFailure { Log.w(TAG, "keep-raw failed for $key", it) }
                }

                // ── Install phase ─────────────────────────────────────────────
                ContentDownloadRegistry.update(key) {
                    it.copy(phase = ContentDownloadPhase.INSTALLING, fraction = 0f)
                }
                DownloadForegroundService.setProgress(key, "$versionName — Installing")

                val ok = if (isDriver) {
                    installDriverBlocking(ctx, Uri.fromFile(temp))
                } else {
                    installComponentBlocking(ctx, Uri.fromFile(temp)) { frac ->
                        ContentDownloadRegistry.update(key) {
                            it.copy(phase = ContentDownloadPhase.INSTALLING, fraction = maxOf(it.fraction, frac))
                        }
                        DownloadForegroundService.setProgress(key, "$versionName — Installing ${(frac * 100).toInt()}%")
                    }
                }

                ContentDownloadRegistry.update(key) {
                    if (ok) it.copy(phase = ContentDownloadPhase.DONE, fraction = 1f)
                    else it.copy(phase = ContentDownloadPhase.ERROR, error = "Install failed.")
                }
                if (ok) runCatching { onChanged() }
            } catch (e: Exception) {
                Log.w(TAG, "install failed for $key", e)
                ContentDownloadRegistry.update(key) {
                    it.copy(phase = ContentDownloadPhase.ERROR, error = "Install failed.")
                }
            } finally {
                temp?.let { runCatching { it.delete() } }
                DownloadForegroundService.finish(key)
                val terminal = ContentDownloadRegistry.get(key)?.phase
                val linger = if (terminal == ContentDownloadPhase.ERROR) 60_000L else 2_000L
                delay(linger)
                if (ContentDownloadRegistry.get(key)?.terminal == true) ContentDownloadRegistry.remove(key)
            }
        }
    }

    /** Installs an already-local archive (My Files "install offline" / install-from-file). */
    fun installFromFile(
        appContext: Context,
        type: String,
        displayName: String,
        source: Uri,
        onDone: (Boolean) -> Unit,
    ) {
        val ctx = appContext.applicationContext
        val key = keyFor(type, "file", displayName)
        ContentDownloadRegistry.get(key)?.let { if (!it.terminal) return }
        val isDriver = ContentsTypes.isDriver(type)

        ContentDownloadRegistry.put(
            ContentDownloadState(
                key = key, title = displayName, type = type, verName = displayName,
                phase = ContentDownloadPhase.INSTALLING, fraction = 0f,
            ),
        )
        DownloadForegroundService.start(ctx)
        DownloadForegroundService.setProgress(key, "$displayName — Installing")

        DownloadScope.io.launch {
            var ok = false
            try {
                ok = if (isDriver) {
                    installDriverBlocking(ctx, source)
                } else {
                    installComponentBlocking(ctx, source) { frac ->
                        ContentDownloadRegistry.update(key) {
                            it.copy(phase = ContentDownloadPhase.INSTALLING, fraction = maxOf(it.fraction, frac))
                        }
                    }
                }
                ContentDownloadRegistry.update(key) {
                    if (ok) it.copy(phase = ContentDownloadPhase.DONE, fraction = 1f)
                    else it.copy(phase = ContentDownloadPhase.ERROR, error = "Install failed.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "install-from-file failed for $key", e)
                ContentDownloadRegistry.update(key) { it.copy(phase = ContentDownloadPhase.ERROR, error = "Install failed.") }
            } finally {
                runCatching { onDone(ok) }
                DownloadForegroundService.finish(key)
                delay(2_000L)
                if (ContentDownloadRegistry.get(key)?.terminal == true) ContentDownloadRegistry.remove(key)
            }
        }
    }

    // ── Blocking installers (Activity-free; never touch runOnUiThread) ──────────

    private suspend fun installDriverBlocking(context: Context, uri: Uri): Boolean =
        runCatching { AdrenotoolsManager(context).installDriver(uri).isNotEmpty() }.getOrDefault(false)

    private suspend fun installComponentBlocking(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val cm = ContentsManager(context)
        val total = uri.path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
        val etaTracker = ImportEtaTracker()
        try {
            val progress = TarCompressorUtils.OnReadProgressListener { read, tot ->
                if (tot > 0) {
                    etaTracker.update(read, tot)
                    onProgress((read.toFloat() / tot).coerceIn(0f, 1f))
                }
            }
            cm.extraContentFile(uri, total, progress, object : ContentsManager.OnInstallFinishedCallback {
                var phase = 0
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                        onProgress(1f)
                        if (cont.isActive) cont.resume(true)
                        return
                    }
                    Log.w(TAG, "component install failed: $reason", e)
                    if (cont.isActive) cont.resume(false)
                }
                override fun onSucceed(profile: ContentProfile) {
                    try {
                        if (phase == 0) {
                            phase = 1
                            cm.finishInstallContent(profile, this)
                        } else {
                            cm.syncContents()
                            onProgress(1f)
                            if (cont.isActive) cont.resume(true)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "component finishInstall failed", e)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "component extract threw", e)
            if (cont.isActive) cont.resume(false)
        }
    }
}
