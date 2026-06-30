package com.winlator.star.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.theme.Divider
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant

// Shared HSV color picker (preview swatch + hue/saturation/brightness gradient sliders + hex input).
// Extracted from AppearanceScreen so it can be reused by the app theme picker, the in-game drawer's
// Controls tab, and a ComposeView-hosted dialog in the legacy input-controls editor. Behavior is
// identical to the original AppearanceScreen.ColorPicker.
@Composable
fun ColorPicker(initialColor: Color, onColorChanged: (Color) -> Unit) {
    // Decompose initial color into HSV
    val hsv = remember(initialColor) {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), arr)
        arr
    }
    var hue        by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value      by remember { mutableFloatStateOf(hsv[2]) }
    var hexInput   by remember { mutableStateOf(initialColor.toHexString()) }
    var hexError   by remember { mutableStateOf(false) }

    fun currentColor() = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

    fun notifyChange() {
        val c = currentColor()
        hexInput = c.toHexString()
        hexError = false
        onColorChanged(c)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Preview swatch
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(currentColor())
                    .border(1.dp, Divider, CircleShape)
            )
            Column {
                Text("Preview", color = OnSurfaceVariant, fontSize = 12.sp)
                Text(hexInput, color = OnSurface, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }

        // Hue slider
        SliderRow(
            label = "Hue",
            value = hue,
            valueRange = 0f..360f,
            trackBrush = Brush.horizontalGradient(
                colors = (0..12).map { i ->
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 1f, 1f)))
                }
            ),
            onValueChange = { hue = it; notifyChange() }
        )

        // Saturation slider
        SliderRow(
            label = "Saturation",
            value = saturation,
            valueRange = 0f..1f,
            trackBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, value))),
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value)))
                )
            ),
            onValueChange = { saturation = it; notifyChange() }
        )

        // Brightness slider
        SliderRow(
            label = "Brightness",
            value = value,
            valueRange = 0f..1f,
            trackBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Black,
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, 1f)))
                )
            ),
            onValueChange = { value = it; notifyChange() }
        )

        // Hex input
        OutlinedTextField(
            value = hexInput,
            onValueChange = { raw ->
                hexInput = raw
                val clean = raw.trimStart('#')
                if (clean.length == 6) {
                    try {
                        val parsed = android.graphics.Color.parseColor("#$clean")
                        val arr = FloatArray(3)
                        android.graphics.Color.colorToHSV(parsed, arr)
                        hue = arr[0]; saturation = arr[1]; value = arr[2]
                        hexError = false
                        onColorChanged(Color(parsed))
                    } catch (_: Exception) { hexError = true }
                } else {
                    hexError = clean.length > 6
                }
            },
            label = { Text("Hex color (#RRGGBB)") },
            isError = hexError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = if (hexError) {{ Text("Enter a valid 6-digit hex color") }} else null
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    trackBrush: Brush,
    onValueChange: (Float) -> Unit
) {
    var sliderWidth by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = OnSurfaceVariant, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(trackBrush)
                .border(1.dp, Divider, RoundedCornerShape(14.dp))
                .onSizeChanged { sliderWidth = it.width }
                .pointerInput(valueRange) {
                    detectTapGestures { offset ->
                        if (sliderWidth > 0) {
                            val fraction = (offset.x / sliderWidth).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
                        }
                    }
                }
                .pointerInput(valueRange) {
                    detectHorizontalDragGestures { change, _ ->
                        if (sliderWidth > 0) {
                            val fraction = (change.position.x / sliderWidth).coerceIn(0f, 1f)
                            onValueChange(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
                        }
                    }
                }
        ) {
            // Thumb indicator
            val fraction = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f))
                            .border(2.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                    )
                }
            }
        }
    }
}

fun Color.toHexString(): String {
    val argb = this.toArgb()
    return "#%02X%02X%02X".format(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF
    )
}
