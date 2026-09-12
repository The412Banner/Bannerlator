// JNI symbols depend on this package path and class name (see rust/src/store_dl/epic/jni.rs).
package com.winlator.star.store.blsteam

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM facade over the native Epic chunk fetcher in `libblsteam.so` (`store_dl/epic`).
 *
 * Replaces exactly one thing in `EpicDownloadManager.install`: the fixed 8-thread chunk pool
 * that fills `<installDir>/.chunks/<GUID>` with verified, decompressed chunks. Everything
 * around it (manifest fetch/parse, install-tag selection, delta/verify, file assembly,
 * post-install) stays in Java. See docs/RUST_EPIC_PARITY.md.
 *
 * [run] is BLOCKING (like the Java pool it replaces) and polls [AtomicBoolean] `cancel` every
 * 250 ms exactly as the Java `awaitTermination(250ms)` loop did: on cancel it flips the native
 * flag, waits up to 5 s for the run to wind down, then returns `cancelled = true`.
 */
object BlEpicDownload {

    private const val TAG = "BL_EPIC_DL"

    /** Listener for the native run; every method is called on a native thread. */
    interface Listener {
        /** Once, before any fetch: the plan the engine derived (Java cross-checks it). */
        fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String)

        /** Per accounted chunk (cached-skip or fetched) — the Java pool's per-task cadence. */
        fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int)

        /** Engine log line (already written to logcat under [TAG]). */
        fun onLog(line: String)

        /** Terminal. */
        fun onComplete(success: Boolean, error: String, bytesCredited: Long)
    }

    /**
     * Outcome of [run]. `started == false` means the engine never fetched anything (library
     * missing, plan cross-check failed, …) and the caller must run its Java pool instead.
     */
    class Result(
        @JvmField val started: Boolean,
        @JvmField val success: Boolean,
        @JvmField val cancelled: Boolean,
        @JvmField val error: String,
        @JvmField val bytesCredited: Long,
        @JvmField val chunksDone: Int,
        @JvmField val chunksTotal: Int,
    )

    private class Completion(val success: Boolean, val error: String, val bytes: Long)

    /**
     * Run the chunk fetch for `pendingFileIdx` (indices into the manifest's file list) and
     * block until it completes, fails or is cancelled.
     *
     * @param expectedChunks Java's `neededChunks.size()` (-1 = no cross-check)
     * @param expectedBytes  Java's `Σ max(fileSize, 1)` (-1 = no cross-check)
     * @param cancel         the Java manager's cancel flag; null = never cancel (legacy / DLC path)
     */
    @JvmStatic
    fun run(
        manifest: ByteArray,
        installDir: String,
        cdnPrefixes: Array<String>,
        pendingFileIdx: IntArray,
        expectedChunks: Int,
        expectedBytes: Long,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        cancel: AtomicBoolean?,
        listener: Listener,
    ): Result {
        try {
            BlSteamClient.ensureLoaded()
        } catch (t: Throwable) {
            val msg = "lib: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "engine unavailable — $msg")
            return Result(false, false, false, msg, 0L, 0, 0)
        }

        val latch = CountDownLatch(1)
        val completion = AtomicReference<Completion?>(null)
        val doneRef = AtomicReference(0)
        val totalRef = AtomicReference(0)
        val inner = object : Listener {
            override fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String) {
                totalRef.set(chunksTotal)
                listener.onPlan(chunksTotal, bytesTotal, chunkDir)
            }

            override fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int) {
                doneRef.set(chunksDone)
                listener.onProgress(bytesDone, bytesTotal, chunksDone, chunksTotal)
            }

            override fun onLog(line: String) = listener.onLog(line)

            override fun onComplete(success: Boolean, error: String, bytesCredited: Long) {
                completion.set(Completion(success, error, bytesCredited))
                latch.countDown()
                try {
                    listener.onComplete(success, error, bytesCredited)
                } catch (_: Throwable) {
                }
            }
        }

        val handle: Long = try {
            nativeStart(
                manifest, installDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes,
                caBundlePath, maxWorkers, processWorkers, inner,
            )
        } catch (t: Throwable) {
            val msg = "nativeStart: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "engine unavailable — $msg")
            return Result(false, false, false, msg, 0L, 0, 0)
        }
        if (handle == 0L) {
            val c = completion.get()
            return Result(false, false, false, c?.error ?: "not started", 0L, 0, totalRef.get())
        }

        var cancelSent = false
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.get()) {
                    nativeCancel(handle)
                    cancelSent = true
                    // Java: pool.shutdownNow() + awaitTermination(5 s) before returning cancelled.
                    latch.await(5, TimeUnit.SECONDS)
                    break
                }
            }
        } finally {
            nativeRelease(handle)
        }

        val c = completion.get()
        val success = !cancelSent && c != null && c.success
        val error = when {
            cancelSent -> "cancelled"
            c == null -> "no completion"
            else -> c.error
        }
        return Result(
            true, success, cancelSent, error,
            c?.bytes ?: 0L, doneRef.get(), totalRef.get(),
        )
    }

    @JvmStatic
    private external fun nativeStart(
        manifest: ByteArray,
        installDir: String,
        cdnPrefixes: Array<String>,
        pendingFileIdx: IntArray,
        expectedChunks: Int,
        expectedBytes: Long,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
