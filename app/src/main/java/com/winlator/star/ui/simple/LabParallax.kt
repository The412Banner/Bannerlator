package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.sin

/**
 * 3D art: a slow drift of the art in depth, -1..1 on each axis, as in the preview. The art layers shift and turn with
 * it (LabArtBackdrop, Home's big tile) and the logo less, so the art reads as a scene behind the menus. Read inside
 * graphicsLayer blocks, so the drift only redraws those layers.
 */
internal object LabParallax {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    /** The drift, or none when off; never NaN (a NaN offset breaks the layout of anything placed under the layer). */
    fun offset(on: Boolean): Pair<Float, Float> = if (on && x.isFinite() && y.isFinite()) x to y else 0f to 0f
}

/**
 * While [enabled]: the slow drift, about a 18 s sideways and 23 s up-and-down sway, updated at most 60 times a
 * second (the art behind is large; a 144 Hz screen gains nothing from redrawing it more often for this).
 */
@Composable
internal fun LabParallaxSensor(enabled: Boolean) {
    LaunchedEffect(enabled) {
        if (!enabled) {
            LabParallax.x = 0f
            LabParallax.y = 0f
            return@LaunchedEffect
        }
        var last = 0L
        try {
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= 16_000_000L) {
                        last = now
                        val t = now / 1e9f
                        LabParallax.x = sin(t * 0.35f) * 0.55f
                        LabParallax.y = sin(t * 0.27f) * 0.4f
                    }
                }
            }
        } finally {
            LabParallax.x = 0f
            LabParallax.y = 0f
        }
    }
}
