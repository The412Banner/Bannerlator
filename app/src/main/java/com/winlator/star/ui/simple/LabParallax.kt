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
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sin

/**
 * 3D art: how far the device is tilted from where it was held, -1..1 on each axis. The art layers shift and turn
 * against it (LabArtBackdrop, Home's big tile), so the art looks like a window. Read inside graphicsLayer blocks, so
 * a tilt only redraws those layers.
 */
internal object LabParallax {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    fun offset(on: Boolean): Pair<Float, Float> = if (on) x to y else 0f to 0f
}

/**
 * While [enabled]: the device's tilt from the game rotation vector (gyro + accelerometer, no compass), measured from a
 * rest position that slowly follows the hand, so any way of holding it is "straight". Without such a sensor the art
 * sways slowly on its own.
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
        @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                @Suppress("DEPRECATION")
                val r = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                when (r) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
                    else -> System.arraycopy(rot, 0, out, 0, 9)
                }
                SensorManager.getOrientation(out, ori)
                val pitch = ori[1]
                val roll = ori[2]
                val b = base ?: floatArrayOf(pitch, roll).also { base = it }
                b[0] += (pitch - b[0]) * 0.01f
                b[1] += (roll - b[1]) * 0.01f
                // About 16° of tilt reaches the full shift.
                val tx = ((roll - b[1]) / 0.28f).coerceIn(-1f, 1f)
                val ty = (-(pitch - b[0]) / 0.28f).coerceIn(-1f, 1f)
                LabParallax.x += (tx - LabParallax.x) * 0.15f
                LabParallax.y += (ty - LabParallax.y) * 0.15f
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sm.unregisterListener(listener)
            LabParallax.x = 0f
            LabParallax.y = 0f
        }
    }
    LaunchedEffect(enabled && sensor == null) {
        if (!enabled || sensor != null) return@LaunchedEffect
        try {
            while (true) {
                withFrameMillis { t ->
                    LabParallax.x = sin(t / 1000f * 0.35f) * 0.55f
                    LabParallax.y = sin(t / 1000f * 0.27f) * 0.4f
                }
            }
        } finally {
            LabParallax.x = 0f
            LabParallax.y = 0f
        }
    }
}
