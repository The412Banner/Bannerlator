package com.winlator.star.widget.gpu

import android.util.Log

/**
 * GPU clock from the GPU's own cycle counter, for Adreno devices whose kgsl clock nodes are SELinux-blocked for
 * apps (app/src/main/cpp/gpuclock/kgsl_clock.c). Ported from DroidDeck. Each [mhz] call runs one tiny GPU job, so callers sample only
 * while a HUD is shown.
 */
object KgslClock {
    private const val TAG = "HudGpu"

    @JvmStatic private external fun nativeOpen(error: IntArray): Long
    @JvmStatic private external fun nativeSample(handle: Long): Int
    @JvmStatic private external fun nativeClose(handle: Long)

    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("gpuclock") }.onFailure { Log.w(TAG, "hud: gpuclock library: $it") }.isSuccess
    }

    class Sampler internal constructor(private var handle: Long) : AutoCloseable {
        /** MHz, or null when this sample was refused or failed its checks. */
        @Synchronized fun mhz(): Int? = sample().takeIf { it > 0 }

        /** MHz, or a negative native code: -errno from kgsl or [RANGE_CHECK_FAILED]. */
        @Synchronized fun sample(): Int = if (handle == 0L) CLOSED else nativeSample(handle)

        @Synchronized override fun close() {
            if (handle != 0L) nativeClose(handle)
            handle = 0L
        }
    }

    /** -EBADF, what the native side also returns for a closed handle. */
    private const val CLOSED = -9

    /** The native sample code for a reading that failed its range checks (kgsl_clock.c sampler_mhz). */
    const val RANGE_CHECK_FAILED = -1000

    /** What the last [open] found, for the HUD diagnostics export. */
    @Volatile var status = "not tried"

    /** A sampler that already produced one good reading, or null (the reason goes to the log and [status]). */
    fun open(): Sampler? {
        if (!loaded) {
            status = "gpuclock library did not load"
            return null
        }
        val error = IntArray(1)
        val handle = runCatching { nativeOpen(error) }.getOrDefault(0L)
        if (handle == 0L) {
            status = "open failed: ${KgslErrors.describe(error[0])}"
            Log.i(TAG, "hud: no GPU clock from the cycle counter (open error ${error[0]})")
            return null
        }
        val sampler = Sampler(handle)
        var code = 0
        val first = (0 until 3).firstNotNullOfOrNull {
            code = sampler.sample()
            code.takeIf { it > 0 }
        }
        if (first == null) {
            status = "no usable reading: ${KgslErrors.describe(code)}"
            Log.i(TAG, "hud: GPU cycle counter gave no usable reading ($code)")
            sampler.close()
            return null
        }
        status = "working ($first MHz at start)"
        Log.i(TAG, "hud: GPU clock from the cycle counter ($first MHz now)")
        return sampler
    }
}
