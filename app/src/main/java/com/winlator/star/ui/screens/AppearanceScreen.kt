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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.core.AppOrientation
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import kotlin.math.roundToInt
import com.winlator.star.ui.components.ColorPicker
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.CUSTOM_PRESET_INDEX
import com.winlator.star.ui.theme.Divider
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.themePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen() {
    val selectedIndex by AppThemeState.presetIndex.collectAsState()
    val customAccent by AppThemeState.customAccent.collectAsState()
    val context = LocalContext.current

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

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))

        // ── Side menu ────────────────────────────────────────────────────
        SectionLabel("Side Menu")
        val showStores by AppThemeState.showStores.collectAsState()
        val showInternal by AppThemeState.showInternalStorage.collectAsState()
        val showSd by AppThemeState.showSdStorage.collectAsState()

        DrawerToggleRow(
            title = "Show game stores",
            subtitle = "The GOG, Epic, Amazon and Steam shortcuts in the drawer",
            checked = showStores,
            onCheckedChange = { AppThemeState.setShowStores(it) },
        )
        DrawerToggleRow(
            title = "Show internal storage",
            subtitle = "The storage bar at the bottom of the drawer",
            checked = showInternal,
            onCheckedChange = { AppThemeState.setShowInternalStorage(it) },
        )
        DrawerToggleRow(
            title = "Show SD card storage",
            subtitle = "Only appears when a card is in the device",
            checked = showSd,
            onCheckedChange = { AppThemeState.setShowSdStorage(it) },
        )

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))

        // ── App orientation ──────────────────────────────────────────────
        SectionLabel("App Orientation")
        Text(
            text = "Applies to the app's own screens only — games keep their own orientation.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        var orientationMode by remember { mutableStateOf(AppOrientation.mode(context)) }
        val orientationOptions = listOf(
            AppOrientation.AUTO to "Auto",
            AppOrientation.PORTRAIT to "Portrait",
            AppOrientation.LANDSCAPE to "Landscape",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            orientationOptions.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = orientationMode == mode,
                    onClick = {
                        orientationMode = mode
                        AppOrientation.setMode(context, mode)
                        (context as? android.app.Activity)?.let { AppOrientation.apply(it) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, orientationOptions.size),
                ) { Text(label) }
            }
        }
        Text(
            text = "Auto follows device rotation (default).",
            color = OnSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))

        // ── Interface scale ──────────────────────────────────────────────
        SectionLabel("Interface Scale")
        Text(
            text = "Scales the app's own UI — games keep their own size.",
            color = OnSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        val uiScale by AppThemeState.uiScale.collectAsState()
        val fontScale by AppThemeState.fontScale.collectAsState()
        ScaleSlider(
            label = "UI Scale",
            value = uiScale,
            onValueChange = { AppThemeState.setUiScale(it) },
        )
        ScaleSlider(
            label = "Font Size",
            value = fontScale,
            onValueChange = { AppThemeState.setFontScale(it) },
        )

        Spacer(Modifier.height(16.dp))
    }
}

/** Percentage slider (0.5×–1.5×) for the interface-scale prefs, flanked by −/+ steppers
 *  that nudge one 5% tick at a time. Kept local since AppearanceScreen has no shared slider. */
@Composable
private fun ScaleSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = OnSurfaceVariant,
                fontSize = 14.sp,
            )
            Text(
                text = "${(value * 100).roundToInt()}%",
                color = OnSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onValueChange((value - SCALE_STEP).coerceIn(SCALE_MIN, SCALE_MAX)) },
                enabled = value > SCALE_MIN,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label", tint = OnSurface)
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = SCALE_MIN..SCALE_MAX,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onValueChange((value + SCALE_STEP).coerceIn(SCALE_MIN, SCALE_MAX)) },
                enabled = value < SCALE_MAX,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label", tint = OnSurface)
            }
        }
    }
}

private const val SCALE_MIN = 0.5f
private const val SCALE_MAX = 1.5f
private const val SCALE_STEP = 0.05f

/** One labelled switch. Three of these now, so they share a shape rather than drift apart. */
@Composable
private fun DrawerToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            )
            Text(
                text = subtitle,
                color = OnSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

// ─── Section label ───────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = OnSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
}
