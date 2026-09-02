// JNI symbols depend on this package path and class name (see rust/src/jni.rs).
package com.winlator.star.store.blsteam

import android.util.Log

/**
 * JVM-side entry point to the native Steam CM engine, `libblsteam.so`.
 *
 * Derived from WinNative's `WnSteamClient` (GPL-3.0-or-later); see
 * `app/src/main/cpp/bl-steam-client/NOTICE.md`.
 */
object BlSteamClient {

    private const val TAG = "BL_STEAM"
    private const val LIB = "blsteam"

    @Volatile
    private var loaded: Boolean = false

    /** Non-null once a load attempt has failed; the message is what the boot log line reports. */
    @Volatile
    var loadError: String? = null
        private set

    /** Load `libblsteam.so` once. Throws on failure (callers on the OFF path use [probe]). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary(LIB)
            loaded = true
            Log.i(TAG, "lib$LIB.so loaded, version=${nativeVersion()}")
        }
    }

    /** Native library semver string. */
    fun version(): String {
        ensureLoaded()
        return nativeVersion()
    }

    /**
     * Boot-time assertion for the flag-OFF path: load the library AND resolve one JNI export
     * (`nativeVersion`) so a packaging or symbol-name regression is loud in logcat instead of
     * surfacing as an [UnsatisfiedLinkError] the first time the engine is actually switched on.
     * Never throws.
     *
     * @return the native version, or null when the library is missing or the symbol did not bind.
     */
    fun probe(): String? {
        return try {
            ensureLoaded()
            nativeVersion()
        } catch (t: Throwable) {
            // UnsatisfiedLinkError covers both "library not packaged" and "JNI symbol not found".
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            loadError = msg
            Log.e(TAG, "lib$LIB.so NOT usable — $msg")
            null
        }
    }

    @JvmStatic
    private external fun nativeVersion(): String
}
