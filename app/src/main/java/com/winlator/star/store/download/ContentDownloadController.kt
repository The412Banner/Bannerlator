package com.winlator.star.store.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.util.ImportEtaTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Process-lifetime download+install driver for compatibility-layer components
 * (Wine / Proton / DXVK / Box64 / FEXCore / rootfs) picked from [ContentDownloadSheet].
 *
 * ── The bug this fixes (issue #122) ──────────────────────────────────────────
 * The sheet used to run the whole `downloadToCache → installContent` sequence on a
 * `rememberCoroutineScope()` — a coroutine scoped to the Compose composition. Minimising
 * the app, locking the screen, or the sheet leaving composition cancelled that scope and
 * killed the in-flight download (and, worse, a half-way extraction). Store downloads
 * (Steam / Amazon / GOG / Epic) already solved this with [DownloadScope] +
 * [DownloadForegroundService]; component downloads now reuse exactly that infra:
 *   - the job runs on [DownloadScope.io] (lives as long as the process, not the screen);
 *   - it is bracketed by [DownloadForegroundService.start]/[finish] so the FGS keeps the
 *     process alive across background/minimise/screen-off for BOTH phases (a half-extracted
 *     component is worse than a half-downloaded one — the bracket covers install too);
 *   - progress is published to [ContentDownloadRegistry] (a process-lifetime StateFlow),
 *     NEVER to Compose state / `Activity.runOnUiThread`, so it keeps advancing while
 *     backgrounded and the sheet re-attaches to it on reopen.
 *
 * This mirrors the [DownloadRegistry] / [StoreDownloadHooks] conventions; it just installs
 * into the imagefs via [ContentsManager] instead of persisting a library row.
 */

/** Phase of a component download, as reflected in the sheet's card + the shade notification. */
enum class ContentDownloadPhase { DOWNLOADING, INSTALLING, DONE, ERROR }

/**
 * Snapshot of one in-flight (or just-finished) component download. Carries the Content-Info
 * fields so a reopened sheet can rebuild its progress card without re-fetching the catalog.
 */
data class ContentDownloadState(
    val key: String,
    val title: String,
    val type: String? = null,
    val verName: String? = null,
    val verCode: String? = null,
    val desc: String? = null,
    val phase: ContentDownloadPhase = ContentDownloadPhase.DOWNLOADING,
    val fraction: Float = 0f,
    val error: String? = null,
) {
    val terminal: Boolean get() = phase == ContentDownloadPhase.DONE || phase == ContentDownloadPhase.ERROR
}

/**
 * Observable, process-lifetime store of component-download state, keyed by the content's
 * entry name ([ContentsManager.getEntryName]). The Kotlin/StateFlow analogue of
 * [DownloadRegistry] for the compatibility-layer menu. Thread-safe: the background job
 * mutates via [MutableStateFlow.update]'s atomic CAS; the sheet just `collectAsState()`s it.
 */
object ContentDownloadRegistry {
    private val _states = MutableStateFlow<Map<String, ContentDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ContentDownloadState>> = _states.asStateFlow()

    fun get(key: String): ContentDownloadState? = _states.value[key]

    fun put(state: ContentDownloadState) = _states.update { it + (state.key to state) }

    /** Atomically mutate an existing entry (no-op if absent). */
    fun update(key: String, transform: (ContentDownloadState) -> ContentDownloadState) =
        _states.update { m -> m[key]?.let { m + (key to transform(it)) } ?: m }

    fun remove(key: String) = _states.update { it - key }
}

private const val TAG = "ContentDownload"

/**
 * Launch a component download+install on the process-lifetime [DownloadScope.io], bracketed by
 * the shared [DownloadForegroundService] so it survives backgrounding. Idempotent per key: if a
 * non-terminal download for the same component is already running, this is a no-op.
 *
 * [appContext] MUST be an application context (never an Activity) — the job outlives the screen.
 */
fun startContentDownload(appContext: Context, profile: ContentProfile) {
    val ctx = appContext.applicationContext
    val key = ContentsManager.getEntryName(profile)

    // Don't double-launch a component that's already downloading/installing.
    ContentDownloadRegistry.get(key)?.let { if (!it.terminal) return }

    val title = profile.verName ?: key
    ContentDownloadRegistry.put(
        ContentDownloadState(
            key = key,
            title = title,
            type = profile.type?.toString(),
            verName = profile.verName,
            verCode = profile.verCode.toString(),
            desc = profile.desc,
            phase = ContentDownloadPhase.DOWNLOADING,
            fraction = 0f,
        )
    )
    // Bring up the shared FGS (keeps the process alive past the sheet/Activity) and post the
    // first shade line. start() is idempotent and uses the application context.
    DownloadForegroundService.start(ctx)
    DownloadForegroundService.setProgress(key, "$title — Downloading 0%")

    DownloadScope.io.launch {
        try {
            // ── Download phase ────────────────────────────────────────────────
            val uri = com.winlator.star.ui.screens.downloadToCache(ctx, profile) { frac ->
                val f = frac.coerceIn(0f, 1f)
                ContentDownloadRegistry.update(key) {
                    it.copy(phase = ContentDownloadPhase.DOWNLOADING, fraction = f)
                }
                DownloadForegroundService.setProgress(key, "$title — Downloading ${(f * 100).toInt()}%")
            }
            if (uri == null) {
                ContentDownloadRegistry.update(key) {
                    it.copy(phase = ContentDownloadPhase.ERROR, error = "Download failed.")
                }
                return@launch
            }

            // ── Install phase (covered by the SAME FGS bracket) ───────────────
            ContentDownloadRegistry.update(key) {
                it.copy(phase = ContentDownloadPhase.INSTALLING, fraction = 0f)
            }
            DownloadForegroundService.setProgress(key, "$title — Installing 0%")

            val installed = installContentBlocking(ctx, uri) { frac ->
                // Monotonic: ignore the brief XZ-probe reset before the ZSTD pass.
                ContentDownloadRegistry.update(key) {
                    val next = maxOf(it.fraction, frac.coerceIn(0f, 1f))
                    it.copy(phase = ContentDownloadPhase.INSTALLING, fraction = next)
                }
                DownloadForegroundService.setProgress(key, "$title — Installing ${(frac * 100).toInt()}%")
            }

            // Tidy the resumable temp file now that install has consumed it.
            uri.path?.let { runCatching { File(it).delete() } }

            ContentDownloadRegistry.update(key) {
                if (installed) it.copy(phase = ContentDownloadPhase.DONE, fraction = 1f)
                else it.copy(phase = ContentDownloadPhase.ERROR, error = "Install failed.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Component download/install failed for $key", e)
            ContentDownloadRegistry.update(key) {
                it.copy(phase = ContentDownloadPhase.ERROR, error = "Install failed.")
            }
        } finally {
            // Drop the shade line (FGS self-stops when no downloads remain) for success,
            // failure OR cancellation — the bracket must always close.
            DownloadForegroundService.finish(key)
            // Leave a terminal registry entry briefly so an open sheet can render DONE/ERROR,
            // then clear it so the registry doesn't leak when the sheet is closed.
            val terminal = ContentDownloadRegistry.get(key)?.phase
            val linger = if (terminal == ContentDownloadPhase.ERROR) 60_000L else 2_000L
            delay(linger)
            if (ContentDownloadRegistry.get(key)?.terminal == true) ContentDownloadRegistry.remove(key)
        }
    }
}

/**
 * Background-safe, Activity-free install of a downloaded component archive into the imagefs.
 * A suspend wrapper around [ContentsManager.extraContentFile] (which runs synchronously and
 * invokes its callbacks inline). Unlike the sheet's Activity-bound `installContent`, this never
 * touches `runOnUiThread` — progress + completion go straight to the caller's lambda, which
 * updates the thread-safe [ContentDownloadRegistry].
 */
private suspend fun installContentBlocking(
    context: Context,
    uri: Uri,
    onProgress: (Float) -> Unit,
): Boolean = suspendCancellableCoroutine { cont ->
    val cm = ContentsManager(context)
    // Byte-accurate denominator = the compressed source size (file uris only).
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
                // A component that's already installed is NOT a failure — mirror the sheet's
                // Activity-bound installContent so the community inline installer's semantics hold.
                if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                    Log.i(TAG, "Component already installed (ERROR_EXIST) — treating as success")
                    onProgress(1f)
                    if (cont.isActive) cont.resume(true)
                    return
                }
                Log.w(TAG, "Component install failed: $reason", e)
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
                    Log.w(TAG, "Component finishInstall failed", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
        })
    } catch (e: Exception) {
        Log.w(TAG, "Component extract threw", e)
        if (cont.isActive) cont.resume(false)
    }
}
