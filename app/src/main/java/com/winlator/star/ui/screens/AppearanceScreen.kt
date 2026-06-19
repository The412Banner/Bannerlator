package com.winlator.star.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.CUSTOM_PRESET_INDEX
import com.winlator.star.ui.theme.Divider
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.themePresets

@Composable
fun AppearanceScreen() {
    val selectedIndex by AppThemeState.presetIndex.collectAsState()
    val customAccent by AppThemeState.customAccent.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Preset themes ────────────────────────────────────────────────
        SectionLabel("Theme Presets")

        val rows = themePresets.chunked(4)
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { preset ->
                    val index = themePresets.indexOf(preset)
                    val isSelected = selectedIndex == index
                    PresetSwatch(
                        preset = preset,
                        isSelected = isSelected,
                        isCustomSlot = index == CUSTOM_PRESET_INDEX,
                        customAccent = customAccent,
                        onClick = { AppThemeState.setPreset(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // pad last row if uneven
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))

        // ── Custom accent picker ─────────────────────────────────────────
        SectionLabel("Custom Accent Color")
        Text(
            text = "Selecting a color below switches to Custom preset",
            color = OnSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(4.dp))
        ColorPicker(
            initialColor = customAccent,
            onColorChanged = { AppThemeState.setCustomAccent(it) }
        )

        Spacer(Modifier.height(16.dp))
    }
}

// ─── Preset swatch ───────────────────────────────────────────────────────────

@Composable
private fun PresetSwatch(
    preset: com.winlator.star.ui.theme.ThemePreset,
    isSelected: Boolean,
    isCustomSlot: Boolean,
    customAccent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isCustomSlot) customAccent else preset.primary
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(preset.background)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Divider,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Mini UI mockup inside the swatch
            Column(
                modifier = Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp)).background(preset.surface))
                Box(Modifier.width(28.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(preset.surfaceVariant))
                Box(Modifier.width(20.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(preset.surfaceVariant))
            }
            if (isSelected) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Text(
            text = preset.name,
            color = if (isSelected) MaterialTheme.colorScheme.primary else OnSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ─── Color picker ────────────────────────────────────────────────────────────

@Composable
private fun ColorPicker(initialColor: Color, onColorChanged: (Color) -> Unit) {
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = OnSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
}

private fun Color.toHexString(): String {
    val argb = this.toArgb()
    return "#%02X%02X%02X".format(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF
    )
}
