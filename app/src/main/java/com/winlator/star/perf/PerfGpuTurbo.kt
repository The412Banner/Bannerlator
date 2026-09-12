package com.winlator.star.perf

import android.util.Log
import java.io.File

/**
 * NON-ROOT GPU max-clock pin, via adrenotools' KGSL turbo property
 * (`IOCTL_KGSL_SETPROPERTY(KGSL_PROP_PWRCTRL)` on `/dev/kgsl-3d0` — a node the app opens itself, so
 * no su is involved). This is the same mechanism Switch emulators expose as "Adreno turbo".
 *
 * It is the fallback half of [PerfRootApplier.applyGpuMaxClockLock]: with root granted we keep using
 * the sysfs pwrlevel pin (stronger, snapshot-reverted); without root we come here instead. Users
 * without root therefore get a working "Lock GPU to max clock" toggle for the first time.
 *
 * Limits worth knowing:
 *  - **Adreno/KGSL only.** No `/dev/kgsl-3d0` (Mali, Xclipse, PowerVR) => [isSupported] is false and
 *    every call is a no-op.
 *  - **No success signal.** `adrenotools_set_turbo` returns void — it cannot tell us whether the
 *    ioctl was accepted, so we track only what we *asked* for.
 *  - **The kernel still throttles.** This lifts the driver's power scaling, not the thermal limits.
 *
 * Revert: the property is device-global and may outlive our process, so we (a) clear it on the same
 * exit/background/crash paths as the root tier ([PerfRevertRegistry.revertAll]) and (b) clear it
 * unconditionally at app startup ([clearOnStartup]) rather than trusting it to have unwound itself.
 */
object PerfGpuTurbo {

    private const val TAG = "PerfGpuTurbo"
    private const val KGSL_NODE = "/dev/kgsl-3d0"

    // Same lib the rest of the winlator natives live in; adrenotools is already linked into it.
    // Failure-isolated: this object is touched from Application startup, so a missing/broken lib must
    // degrade to "unsupported", never take the app down.
    private val libLoaded: Boolean = try {
        System.loadLibrary("winlator")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "libwinlator not loadable — GPU turbo unavailable", t)
        false
    }

    /** True when this device exposes the Adreno KGSL node the turbo property lives on. */
    val isSupported: Boolean by lazy { libLoaded && File(KGSL_NODE).exists() }

    @Volatile
    private var applied = false

    /** True when we have asked the driver for turbo and not yet cleared it. */
    val isApplied: Boolean get() = applied

    /** Apply or clear the turbo property. No-op on non-Adreno devices. */
    @Synchronized
    fun apply(on: Boolean) {
        if (!isSupported) {
            Log.d(TAG, "skip: no $KGSL_NODE (not an Adreno/KGSL device)")
            return
        }
        if (applied == on) return
        try {
            nativeSetTurbo(on)
            applied = on
            Log.d(TAG, "turbo = $on")
        } catch (t: Throwable) {
            Log.w(TAG, "set turbo $on failed", t)
        }
    }

    /** Clear turbo if we set it. Called from the shared revert paths. */
    fun revert() {
        if (applied) apply(false)
    }

    /**
     * Clear turbo at process start regardless of what we think our state is — the KGSL property is
     * device-global and a hard kill can leave it set with no chance for us to unwind.
     */
    fun clearOnStartup() {
        if (!isSupported) return
        try {
            nativeSetTurbo(false)
            applied = false
            Log.d(TAG, "startup: cleared any stale turbo state")
        } catch (t: Throwable) {
            Log.w(TAG, "startup clear failed", t)
        }
    }

    private external fun nativeSetTurbo(on: Boolean)
}
