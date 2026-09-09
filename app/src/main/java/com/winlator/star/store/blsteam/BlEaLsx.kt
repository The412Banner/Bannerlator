// JNI symbols depend on this package path and class name (see rust/src/jni.rs).
package com.winlator.star.store.blsteam

import android.util.Log
import java.io.Closeable

/**
 * The socket an EA game asks for permission on.
 *
 * An EA-published title does not phone EA when it starts. It looks for its launcher on a loopback
 * port named by the `EALsxPort` environment variable and asks there. This class stands up that
 * listener inside the app, so the answer can come from us instead of from EA Desktop running in the
 * container.
 *
 * ### Lifecycle
 * Start one just before a launch, put [port] into the game's environment, and [close] it when the
 * game exits. Nothing here throws: every failure surfaces as `port == 0` and a message in
 * [lastError], because the caller's response to any failure is identical — leave `EALsxPort` unset
 * and let EA Desktop take the launch, exactly as it does today.
 *
 * ### Two modes
 * [startServe] answers the game itself. [startCapture] passes everything through to a real EA
 * Desktop while writing down both sides, which is how we learn what our own titles actually say and
 * how a failure gets diagnosed without a second test run.
 */
class BlEaLsx private constructor(private var handle: Long) : Closeable {

    /** How to answer the one question that decides whether the game runs. */
    enum class Strategy(val code: Int) {
        /** Serve a held licence when we have one, otherwise an empty one. The default. */
        AUTO(0),

        /**
         * Always answer empty. An empty licence is what a launcher returns for an offline launch,
         * and for a title without Denuvo it may be the entire answer.
         */
        ALWAYS_EMPTY(1),

        /** Only answer with a real licence — used to prove a title genuinely needs one. */
        REQUIRE_TOKEN(2),
    }

    /** Bound port, or 0 when not running. This is the value that goes into `EALsxPort`. */
    val port: Int
        get() = if (handle == 0L) 0 else nativePort(handle)

    /** Last failure, or a summary of how far the last conversation got. */
    val lastError: String
        get() = if (handle == 0L) "closed" else nativeLastError(handle).orEmpty()

    /** Answer the game ourselves. Returns the port, or 0 on failure. */
    fun startServe(strategy: Strategy = Strategy.AUTO): Int {
        if (handle == 0L) return 0
        val p = nativeStartServe(handle, strategy.code)
        Log.i(TAG, if (p != 0) "serving LSX on 127.0.0.1:$p (${strategy.name})" else "serve failed: $lastError")
        return p
    }

    /**
     * Sit between the game and a real EA Desktop on [upstreamPort], recording both sides.
     *
     * The launch behaves exactly as it does today — EA Desktop still answers — so this costs nothing
     * but yields the transcript.
     */
    fun startCapture(upstreamPort: Int): Int {
        if (handle == 0L) return 0
        val p = nativeStartCapture(handle, upstreamPort)
        Log.i(TAG, if (p != 0) "capturing LSX on :$p -> :$upstreamPort" else "capture failed: $lastError")
        return p
    }

    /**
     * Record a licence for a title, keyed by EA's content id.
     *
     * An empty [token] forgets it, so a licence that turns out to be stale can be cleared without
     * restarting the listener.
     */
    fun putToken(contentId: String, token: String) {
        if (handle != 0L) nativePutToken(handle, contentId, token)
    }

    /**
     * How many times the game connected to us.
     *
     * Zero after a launch is the most useful thing this class can report: our licence answer was
     * never asked for, so it cannot be the reason the launch behaved as it did.
     */
    val connections: Long
        get() = if (handle == 0L) 0L else nativeConnections(handle)

    /** Everything recorded since the last call, then cleared. */
    fun drainTranscript(): String =
        if (handle == 0L) "" else nativeDrainTranscript(handle).orEmpty()

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    companion object {
        private const val TAG = "BL_EA_LSX"

        /**
         * Create a listener, or null if the native library is unavailable.
         *
         * Null is a normal outcome, not an error to report to the user: it means this build cannot
         * serve EA licences, and the launch proceeds through EA Desktop as before.
         */
        fun create(): BlEaLsx? = try {
            BlSteamClient.ensureLoaded()
            val h = nativeCreate()
            if (h == 0L) null else BlEaLsx(h)
        } catch (t: Throwable) {
            Log.w(TAG, "native EA LSX unavailable, EA Desktop will handle launches", t)
            null
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeStartServe(handle: Long, strategy: Int): Int
        @JvmStatic private external fun nativeStartCapture(handle: Long, upstreamPort: Int): Int
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativePort(handle: Long): Int
        @JvmStatic private external fun nativePutToken(handle: Long, contentId: String, token: String)
        @JvmStatic private external fun nativeConnections(handle: Long): Long
        @JvmStatic private external fun nativeLastError(handle: Long): String?
        @JvmStatic private external fun nativeDrainTranscript(handle: Long): String?
    }
}
