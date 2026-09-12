// JNI symbols depend on this package path and class name
// (see rust/src/store_dl/amazon/jni.rs).
package com.winlator.star.store.blsteam

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native Amazon download callbacks; every method runs on a native worker thread.
 */
interface BlAmazonDownloadListener {

    /**
     * Fired after each committed file (and once up front with the resume-skipped credit).
     * [bytesDone] includes skipped files, like the Java manager's aggregate counter.
     */
    fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long)

    /** One diagnostic line (`engine=rust …`, `fetch-window …`, `summary …`). */
    fun onLog(line: String)

    /** Fired exactly once when the run succeeds, fails or is cancelled. */
    fun onComplete(success: Boolean, error: String, bytesWritten: Long)
}

/** Java-friendly cancel probe (the manager's `CancelChecker`). */
fun interface BlAmazonCancelCheck {
    fun isCancelled(): Boolean
}

/**
 * JVM-side facade over the Rust Amazon download adapter in `libblsteam.so`.
 *
 * Replaces ONLY the file-fetch pool of `AmazonDownloadManager.install()`; the manager keeps
 * manifest/auth/markers/post-install. [runBlocking] mirrors the Java loop's blocking shape:
 * it returns when the run is over and polls the caller's cancel flag meanwhile.
 */
object BlAmazonDownload {

    const val TAG = "BL_AMAZON_DL"

    /** Cancel-poll period while a run is in flight (Java checked per 64 KiB read). */
    private const val CANCEL_POLL_MS = 100L

    class RunResult(
        @JvmField val success: Boolean,
        @JvmField val cancelled: Boolean,
        @JvmField val error: String,
        @JvmField val bytesWritten: Long,
    )

    /** True when `libblsteam.so` loads and binds (same probe the Steam engine uses). */
    @JvmStatic
    fun isAvailable(): Boolean = BlSteamClient.probe() != null

    /**
     * Run one download to completion. [isCancelled] is polled every [CANCEL_POLL_MS]; the first
     * true flips the native cancel flag and the result reports `cancelled = true`.
     *
     * @param planJson `[{relPath, url, size, sha256hex}]` — every manifest file (the native
     *   side applies the size-based resume-skip itself, exactly like the Java loop).
     * @param maxWorkers window size (Java `MAX_PARALLEL` = 8); `<= 0` = native default (8).
     * @param processWorkers sync verify+write threads.
     */
    @JvmStatic
    fun runBlocking(
        planJson: String,
        installDir: String,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        isCancelled: BlAmazonCancelCheck?,
        listener: BlAmazonDownloadListener,
    ): RunResult {
        BlSteamClient.ensureLoaded()
        val latch = CountDownLatch(1)
        var outcome: RunResult? = null
        val bridge = object : BlAmazonDownloadListener {
            override fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long) {
                listener.onProgress(bytesDone, bytesTotal, filesDone, filesTotal)
            }

            override fun onLog(line: String) {
                listener.onLog(line)
            }

            override fun onComplete(success: Boolean, error: String, bytesWritten: Long) {
                outcome = RunResult(success, false, error, bytesWritten)
                listener.onComplete(success, error, bytesWritten)
                latch.countDown()
            }
        }
        val handle = nativeStart(planJson, installDir, caBundlePath, maxWorkers, processWorkers, bridge)
        if (handle == 0L) {
            // nativeStart already fired onComplete(false, …) for unusable inputs.
            return outcome ?: RunResult(false, false, "native start failed", 0L)
        }
        var cancelSent = false
        try {
            while (!latch.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!cancelSent && isCancelled?.isCancelled() == true) {
                    Log.i(TAG, "cancel requested")
                    nativeCancel(handle)
                    cancelSent = true
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!cancelSent) {
                nativeCancel(handle)
                cancelSent = true
            }
            // Let the native run wind down before releasing the handle.
            try { latch.await(30, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        } finally {
            nativeRelease(handle)
        }
        val r = outcome ?: RunResult(false, cancelSent, "no completion", 0L)
        return RunResult(r.success && !cancelSent, cancelSent, r.error, r.bytesWritten)
    }

    @JvmStatic
    private external fun nativeStart(
        planJson: String,
        installDir: String,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        listener: BlAmazonDownloadListener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
