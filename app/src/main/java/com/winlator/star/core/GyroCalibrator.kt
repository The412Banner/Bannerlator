package com.winlator.star.core

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Device-level gyroscope zero-rate calibration.
 *
 * Samples TYPE_GYROSCOPE while the device is held still, averages the residual rate and stores it as
 * a bias that WinHandler.updateGyroData() subtracts before anything else. Deliberately standalone
 * (no WinHandler, no X server): the out-of-game Input Controls screen has neither, and the in-game
 * drawer may want to call the same routine later.
 *
 * The stored bias is stamped with the device+sensor it was measured on and ignored on a mismatch, so
 * Android auto-backup can't restore one phone's bias onto another.
 */
object GyroCalibrator {
    const val PREF_BIAS_X = "gyro_bias_x"
    const val PREF_BIAS_Y = "gyro_bias_y"
    const val PREF_BIAS_CALIBRATED = "gyro_bias_calibrated"
    const val PREF_BIAS_DEVICE = "gyro_bias_device"

    // Sampling window. The delivery rate at SENSOR_DELAY_GAME is not contractual (50-200 Hz in the
    // wild), so the window is time-bounded and the sample count is only bounded, never assumed.
    private const val WINDOW_MS = 1500L
    private const val MAX_SAMPLES = 256
    private const val MIN_SAMPLES = 32

    // Motion rejection. FAST_ABORT_RATE ends the run the moment a single sample proves the device is
    // moving; the mean/stddev gates catch a slow drift or a hand-held wobble that stays under it.
    private const val FAST_ABORT_RATE = 0.35f   // rad/s, any single sample
    private const val MAX_MEAN_RATE = 0.20f     // rad/s, device genuinely rotating
    private const val MAX_STDDEV_RATE = 0.03f   // rad/s, device being handled

    /** Below this on both axes the platform already compensates and there is nothing to remove. */
    const val NEGLIGIBLE_BIAS = 0.005f

    sealed class Result {
        /** Bias measured and stored. [negligible] = under NEGLIGIBLE_BIAS on both axes. */
        data class Success(val biasX: Float, val biasY: Float, val negligible: Boolean) : Result()
        /** Device was moving — the previously stored bias is left untouched. */
        object Moved : Result()
        /** Not enough samples arrived inside the window. */
        object NotEnoughSamples : Result()
        /** No gyroscope, or the sensor refused the listener registration. */
        object Unavailable : Result()
    }

    /** The gyroscope, or null on a device without one. */
    @JvmStatic
    fun getSensor(context: Context): Sensor? {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    /** Identity of the hardware a stored bias was measured on. */
    @JvmStatic
    fun deviceStamp(sensor: Sensor): String = Build.MODEL + "|" + sensor.getName()

    /**
     * Fills out[0]/out[1] with the stored bias, or 0 when uncalibrated or when the stored stamp
     * doesn't match this device. Called at WinHandler construction and again when the game session
     * comes back to the foreground (a recalibration may have happened while away) — never on the
     * sample path.
     */
    @JvmStatic
    @JvmOverloads
    fun loadBias(context: Context, out: FloatArray,
                 preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)) {
        out[0] = 0.0f
        out[1] = 0.0f
        if (!preferences.getBoolean(PREF_BIAS_CALIBRATED, false)) return
        val sensor = getSensor(context) ?: return
        if (preferences.getString(PREF_BIAS_DEVICE, null) != deviceStamp(sensor)) return
        out[0] = preferences.getFloat(PREF_BIAS_X, 0.0f)
        out[1] = preferences.getFloat(PREF_BIAS_Y, 0.0f)
    }

    /**
     * True when a bias measured on THIS device is stored. A calibrated device can still have a
     * near-zero bias, so this is the only way to tell "calibrated" from "nothing to remove".
     */
    @JvmStatic
    fun isCalibrated(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!preferences.getBoolean(PREF_BIAS_CALIBRATED, false)) return false
        val sensor = getSensor(context) ?: return false
        return preferences.getString(PREF_BIAS_DEVICE, null) == deviceStamp(sensor)
    }

    /** Drops the stored bias back to zero — the escape hatch from a bad calibration. */
    @JvmStatic
    fun clearBias(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(PREF_BIAS_X)
            .remove(PREF_BIAS_Y)
            .remove(PREF_BIAS_CALIBRATED)
            .remove(PREF_BIAS_DEVICE)
            .apply()
    }

    /**
     * Runs one calibration pass. [onResult] fires on the main thread exactly once; the listener is
     * unregistered before it fires, on every path including cancel. Returns a handle whose cancel()
     * is safe to call at any time (including after completion), or null when nothing was started —
     * in which case onResult has already reported the failure.
     */
    @JvmStatic
    fun calibrate(context: Context, onResult: (Result) -> Unit): Run? {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (manager == null || sensor == null) {
            onResult(Result.Unavailable)
            return null
        }
        val run = Run(context.applicationContext, manager, sensor, onResult)
        return if (run.start()) run else null
    }

    /** One in-flight calibration pass. */
    class Run internal constructor(
        private val context: Context,
        private val manager: SensorManager,
        private val sensor: Sensor,
        private val onResult: (Result) -> Unit
    ) : SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private var finished = false

        // Primitive accumulators only — onSensorChanged must not allocate or log.
        private var count = 0
        private var sumX = 0.0
        private var sumY = 0.0
        private var sumSqX = 0.0
        private var sumSqY = 0.0

        private val timeout = Runnable { finish(evaluate()) }

        internal fun start(): Boolean {
            if (!manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
                onResult(Result.Unavailable)
                return false
            }
            handler.postDelayed(timeout, WINDOW_MS)
            return true
        }

        /** Abandons the pass without writing anything. Idempotent. */
        fun cancel() {
            if (finished) return
            finished = true
            stop()
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (finished || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val x = event.values[0]
            val y = event.values[1]
            // Tier 1: a single sample this large means the device is being moved — stop right now
            // rather than averaging motion into the bias.
            if (abs(x) > FAST_ABORT_RATE || abs(y) > FAST_ABORT_RATE) {
                finish(Result.Moved)
                return
            }
            count++
            sumX += x
            sumY += y
            sumSqX += x.toDouble() * x
            sumSqY += y.toDouble() * y
            if (count >= MAX_SAMPLES) finish(evaluate())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }

        /** Tier 2: judge the whole window, then store only if it passes. */
        private fun evaluate(): Result {
            if (count < MIN_SAMPLES) return Result.NotEnoughSamples

            val n = count.toDouble()
            val meanX = sumX / n
            val meanY = sumY / n
            // Population variance; clamped at 0 because the sum-of-squares form can go slightly
            // negative on near-constant input.
            val varX = (sumSqX / n) - (meanX * meanX)
            val varY = (sumSqY / n) - (meanY * meanY)
            val sdX = sqrt(if (varX > 0.0) varX else 0.0)
            val sdY = sqrt(if (varY > 0.0) varY else 0.0)

            if (abs(meanX) > MAX_MEAN_RATE || abs(meanY) > MAX_MEAN_RATE) return Result.Moved
            if (sdX > MAX_STDDEV_RATE || sdY > MAX_STDDEV_RATE) return Result.Moved

            val biasX = meanX.toFloat()
            val biasY = meanY.toFloat()
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putFloat(PREF_BIAS_X, biasX)
                .putFloat(PREF_BIAS_Y, biasY)
                .putBoolean(PREF_BIAS_CALIBRATED, true)
                .putString(PREF_BIAS_DEVICE, deviceStamp(sensor))
                .apply()

            val negligible = abs(biasX) < NEGLIGIBLE_BIAS && abs(biasY) < NEGLIGIBLE_BIAS
            return Result.Success(biasX, biasY, negligible)
        }

        /** Single exit point: unregister first, then report once. */
        private fun finish(result: Result) {
            if (finished) return
            finished = true
            stop()
            onResult(result)
        }

        private fun stop() {
            handler.removeCallbacks(timeout)
            manager.unregisterListener(this)
        }
    }
}
