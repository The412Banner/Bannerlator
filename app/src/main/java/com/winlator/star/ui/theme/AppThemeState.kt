package com.winlator.star.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

object AppThemeState {
    private lateinit var themePrefs: SharedPreferences

    private val _presetIndex = MutableStateFlow(1)
    val presetIndex: StateFlow<Int> = _presetIndex

    private val _customAccent = MutableStateFlow(Color(0xFF0055FF))
    val customAccent: StateFlow<Color> = _customAccent

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    // The preset whose background/surface colors back the custom accent
    private val _customBaseIndex = MutableStateFlow(0)

    val colorScheme: kotlinx.coroutines.flow.Flow<ColorScheme> =
        combine(_presetIndex, _customAccent, _isDarkMode) { index, accent, dark ->
            val preset = if (index == CUSTOM_PRESET_INDEX)
                themePresets.getOrElse(_customBaseIndex.value) { themePresets.first() }
            else
                themePresets.getOrElse(index) { themePresets.first() }
            val override = if (index == CUSTOM_PRESET_INDEX) accent else null
            if (dark) preset.toColorScheme(accentOverride = override)
            else      preset.toLightColorScheme(accentOverride = override)
        }

    /** Dim accent paired with [colorScheme]. For real presets it's the preset's own
     *  accentDim (AMOLED = exact #002277 so the default is unchanged); for a custom accent
     *  it's derived from the live accent so dim fills/borders follow the picker too. */
    val accentDim: kotlinx.coroutines.flow.Flow<Color> =
        combine(_presetIndex, _customAccent) { index, accent ->
            if (index == CUSTOM_PRESET_INDEX) lerp(accent, Color(0xFF000000), 0.55f)
            else themePresets.getOrElse(index) { themePresets.first() }.accentDim
        }

    fun currentAccentDimSnapshot(): Color {
        val index = _presetIndex.value
        return if (index == CUSTOM_PRESET_INDEX) lerp(_customAccent.value, Color(0xFF000000), 0.55f)
               else themePresets.getOrElse(index) { themePresets.first() }.accentDim
    }

    fun init(context: Context) {
        themePrefs = context.getSharedPreferences("winlator_theme", Context.MODE_PRIVATE)

        _presetIndex.value = themePrefs.getInt("preset_index", 1).coerceIn(0, themePresets.size - 1)
        val savedAccent = themePrefs.getInt("custom_accent", Color(0xFF0055FF).toArgb())
        _customAccent.value = Color(savedAccent)
        _customBaseIndex.value = themePrefs.getInt("custom_base_index", 1).coerceIn(0, CUSTOM_PRESET_INDEX)
        _isDarkMode.value = true
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, themePresets.size - 1)
        themePrefs.edit().putInt("preset_index", _presetIndex.value).apply()
    }

    fun setCustomAccent(color: Color) {
        // Snapshot the current base only when leaving a real preset for custom mode
        if (_presetIndex.value != CUSTOM_PRESET_INDEX) {
            _customBaseIndex.value = _presetIndex.value
            themePrefs.edit().putInt("custom_base_index", _customBaseIndex.value).apply()
        }
        _customAccent.value = color
        _presetIndex.value = CUSTOM_PRESET_INDEX
        themePrefs.edit()
            .putInt("custom_accent", color.toArgb())
            .putInt("preset_index", CUSTOM_PRESET_INDEX)
            .apply()
    }

    fun currentColorSchemeSnapshot(): ColorScheme {
        val index = _presetIndex.value
        val preset = if (index == CUSTOM_PRESET_INDEX)
            themePresets.getOrElse(_customBaseIndex.value) { themePresets.first() }
        else
            themePresets.getOrElse(index) { themePresets.first() }
        val override = if (index == CUSTOM_PRESET_INDEX) _customAccent.value else null
        return if (_isDarkMode.value) preset.toColorScheme(accentOverride = override)
               else                   preset.toLightColorScheme(accentOverride = override)
    }

    /** Java-friendly entry point: returns the current accent (primary) color as an
     *  ARGB int. Used by legacy AndroidView widgets (CPUListView, EnvVarsView) so
     *  they can tint their CheckBox/ToggleButton drawables to match the Compose
     *  accent picker. */
    @JvmStatic
    fun getCurrentAccentArgb(): Int = currentColorSchemeSnapshot().primary.toArgb()
}
