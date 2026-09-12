// JNI symbols depend on this package path and class name (see rust/src/store_dl/gog/jni.rs).
package com.winlator.star.store.blsteam

import android.util.Log

/**
 * Callbacks from the native GOG download engine. Every method runs on a native worker /
 * process-pool thread — never touch Views directly.
 *
 * The Java manager ([com.winlator.star.store.GogDownloadManager]) turns these into exactly the
 * same `Callback.onProgress` strings / percentages, registry ticks and debug-log lines its own
 * thread-pool loop produced (see `docs/RUST_GOG_PARITY.md`).
 */
interface BlGogDownloadListener {
    /**
     * One file reached its final state. [verified] = resume-skip (existing file passed size+MD5;
     * no bytes credited — Java's "Verified…" branch); otherwise a freshly assembled, size+MD5
     * verified and renamed file ([fileBytes] = its decompressed size — Java's "Downloading: …"
     * branch). [filesDone] counts both; [bytesDone] counts assembled bytes only.
     */
    fun onProgress(
        bytesDone: Long,
        bytesTotal: Long,
        filesDone: Int,
        filesTotal: Int,
        file: String,
        fileBytes: Long,
        verified: Boolean,
    )

    /** Engine diagnostics (already written to logcat under `BL_GOG_DL`). */
    fun onLog(line: String)

    /**
     * Fired exactly once per [BlGogDownload.start] that returned a non-zero handle.
     * [linkExpiry] = the run died on an HTTP 401/403/404/500 — the codes Java's chunk loop
     * treats as an expired secure link; the manager refreshes the link and re-runs.
     */
    fun onComplete(
        success: Boolean,
        cancelled: Boolean,
        linkExpiry: Boolean,
        error: String,
        bytesWritten: Long,
        filesDone: Int,
    )
}

/**
 * JVM-side facade of the GOG gen2 chunk engine inside `libblsteam.so`
 * (`rust/src/store_dl/gog/`). One [start] = one Java pool loop (base install, DLC install, or a
 * dependency-redist assembly); the manager owns everything before and after it.
 *
 * Symbol guard: [isAvailable] loads the library through [BlSteamClient.ensureLoaded] and resolves
 * one export, so a packaging/symbol regression degrades to the Java loop instead of throwing
 * [UnsatisfiedLinkError] mid-download.
 */
object BlGogDownload {

    private const val TAG = "BL_GOG_DL"

    /** gen2: depot manifests → chunk fetch + inflate + MD5 (base install, DLC, dependencies). */
    const val KIND_GEN2_CHUNKS = 0

    /** gen1: build manifest → per-file HTTP Range GET streamed to disk (`runGen1`). */
    const val KIND_GEN1_RANGES = 1

    @Volatile
    private var available: Boolean? = null

    /** True when `libblsteam.so` loads and the GOG JNI exports bind. Cached after the first call. */
    @JvmStatic
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            BlSteamClient.ensureLoaded()
            nativeProbe() == 1
        } catch (t: Throwable) {
            Log.w(TAG, "native GOG engine unavailable — ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        available = ok
        return ok
    }

    /**
     * Starts one download loop on a native thread and returns its handle (0 = not started; the
     * listener then receives NO callbacks). Inputs are what the manager already holds:
     * @param kind [KIND_GEN2_CHUNKS] or [KIND_GEN1_RANGES].
     * @param depotManifests gen2: inflated depot-manifest JSON strings, in fetch order, already
     *   filtered by product + language in Java; gen1: the inflated build manifest.
     * @param cdnBase gen2: the resolved secure-link base (`parseCdnUrl`, query string intact) or
     *   the unauthenticated dependency store base; gen1: "" (file URLs are in the manifest).
     * @param skipPaths files already completed by an earlier run of this same download (secure-link
     *   refresh re-run): counted done without re-hashing, no progress event.
     * @param maxWorkers Java pool size for this loop (`resolveDownloadThreads`, 8, or 1).
     * @param processWorkers inflate/hash/write threads (Java did that on the pool threads → same N).
     * @param sortLargestFirst base install = true (LPT order); DLC / dependency = false.
     */
    @JvmStatic
    fun start(
        kind: Int,
        depotManifests: Array<String>,
        cdnBase: String,
        installDir: String,
        skipPaths: Array<String>,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        sortLargestFirst: Boolean,
        label: String,
        listener: BlGogDownloadListener,
    ): Long {
        if (!isAvailable()) return 0L
        return try {
            nativeStart(
                kind, depotManifests, cdnBase, installDir, skipPaths, caBundlePath,
                maxWorkers, processWorkers, sortLargestFirst, label, listener,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart threw — ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /** Requests cancellation; `onComplete(cancelled = true)` follows. Idempotent, 0 is a no-op. */
    @JvmStatic
    fun cancel(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeCancel(handle) }
    }

    /** Releases the handle. Call once after `onComplete` (the native run keeps itself alive). */
    @JvmStatic
    fun release(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeRelease(handle) }
    }

    @JvmStatic
    private external fun nativeProbe(): Int

    @JvmStatic
    private external fun nativeStart(
        kind: Int,
        depotManifests: Array<String>,
        cdnBase: String,
        installDir: String,
        skipPaths: Array<String>,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        sortLargestFirst: Boolean,
        label: String,
        listener: BlGogDownloadListener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
