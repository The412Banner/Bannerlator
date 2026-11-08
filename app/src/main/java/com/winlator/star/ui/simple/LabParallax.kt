package com.winlator.star.ui.simple

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlin.math.exp
import kotlin.math.sin

/**
 * 3D art: how far the device is tilted from where it was held, -1..1 on each axis. The art layers shift and turn
 * against it (LabArtBackdrop, Home's big tile), so the art looks like a window. Read inside graphicsLayer blocks, so
 * a tilt only redraws those layers.
 */
internal object LabParallax {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    /** Where the sensor says the art should be; [x] and [y] glide towards it every frame. */
    @Volatile var tx = 0f
    @Volatile var ty = 0f

    fun offset(on: Boolean): Pair<Float, Float> = if (on) x to y else 0f to 0f
}

/**
 * While [enabled]: the device's tilt from the game rotation vector (gyro + accelerometer, no compass), measured from a
 * rest position that slowly follows the hand, so any way of holding it is "straight". The sensor only sets where the
 * art should go; a per-frame glide (about 0.1 s to settle) moves it there, so it stays smooth at any refresh rate
 * however unevenly the readings come. Without such a sensor the art sways slowly on its own.
 */
@Composable
internal fun LabParallaxSensor(enabled: Boolean) {
    val context = LocalContext.current
    val sm = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(sm) { sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    DisposableEffect(enabled, sensor) {
        if (!enabled || sm == null || sensor == null) return@DisposableEffect onDispose {}
        val rot = FloatArray(9)
        val out = FloatArray(9)
        val ori = FloatArray(3)
        var base: FloatArray? = null
        var lastNs = 0L
        @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                @Suppress("DEPRECATION")
                when (wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
                    else -> System.arraycopy(rot, 0, out, 0, 9)
                }
                SensorManager.getOrientation(out, ori)
                val pitch = ori[1]
                val roll = ori[2]
                val b = base ?: floatArrayOf(pitch, roll).also { base = it }
                // The rest position follows the hand over about 4 s, measured in time, not in readings.
                val dt = if (lastNs == 0L) 0f else ((e.timestamp - lastNs) / 1e9f).coerceIn(0f, 0.1f)
                lastNs = e.timestamp
                val follow = 1f - exp(-dt / 4f)
                b[0] += (pitch - b[0]) * follow
                b[1] += (roll - b[1]) * follow
                // About 16° of tilt reaches the full shift.
                LabParallax.tx = ((roll - b[1]) / 0.28f).coerceIn(-1f, 1f)
                LabParallax.ty = (-(pitch - b[0]) / 0.28f).coerceIn(-1f, 1f)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sm.unregisterListener(listener)
            LabParallax.tx = 0f
            LabParallax.ty = 0f
        }
    }
    LaunchedEffect(enabled, sensor == null) {
        if (!enabled) {
            LabParallax.x = 0f
            LabParallax.y = 0f
            return@LaunchedEffect
        }
        var last = 0L
        try {
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                    if (sensor == null) {
                        val t = now / 1e9f
                        LabParallax.x = sin(t * 0.35f) * 0.55f
                        LabParallax.y = sin(t * 0.27f) * 0.4f
                    } else {
                        val k = 1f - exp(-dt / 0.09f)
                        LabParallax.x += (LabParallax.tx - LabParallax.x) * k
                        LabParallax.y += (LabParallax.ty - LabParallax.y) * k
                    }
                }
            }
        } finally {
            LabParallax.x = 0f
            LabParallax.y = 0f
        }
    }
}
