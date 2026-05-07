package com.winlator.cmod.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

data class ThemePreset(
    val name: String,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val onSurface: Color = Color(0xFFE0E0E0),
    val onSurfaceVariant: Color = Color(0xFFAAAAAA),
    val onBackground: Color = Color(0xFFFFFFFF),
    val onPrimary: Color = Color(0xFFFFFFFF),
    val divider: Color = Color(0xFF404040),
    val error: Color = Color(0xFFCF6679),
) {
    fun toColorScheme(accentOverride: Color? = null): androidx.compose.material3.ColorScheme {
        val accent = accentOverride ?: primary
        return darkColorScheme(
            primary              = accent,
            onPrimary            = onPrimary,
            secondary            = accent,
            onSecondary          = onPrimary,
            secondaryContainer   = accent.copy(alpha = 0.30f),
            onSecondaryContainer = onSurface,
            background           = background,
            onBackground         = onBackground,
            surface              = surface,
            onSurface            = onSurface,
            surfaceVariant       = surfaceVariant,
            onSurfaceVariant     = onSurfaceVariant,
            error                = error,
        )
    }

    fun toLightColorScheme(accentOverride: Color? = null): androidx.compose.material3.ColorScheme {
        val accent = accentOverride ?: primary
        return lightColorScheme(
            primary              = accent,
            onPrimary            = Color(0xFFFFFFFF),
            secondary            = accent,
            onSecondary          = Color(0xFFFFFFFF),
            secondaryContainer   = accent.copy(alpha = 0.20f),
            onSecondaryContainer = Color(0xFF1A1A1A),
            background           = Color(0xFFF5F5F5),
            onBackground         = Color(0xFF1A1A1A),
            surface              = Color(0xFFFFFFFF),
            onSurface            = Color(0xFF1A1A1A),
            surfaceVariant       = Color(0xFFEAEAEA),
            onSurfaceVariant     = Color(0xFF555555),
            error                = Color(0xFFB00020),
        )
    }
}

val themePresets: List<ThemePreset> = listOf(
    ThemePreset(
        name          = "Classic Dark",
        background    = Color(0xFF1A1A1A),
        surface       = Color(0xFF2A2A2A),
        surfaceVariant= Color(0xFF333333),
        primary       = Color(0xFF8B6BE0),
    ),
    ThemePreset(
        name          = "AMOLED",
        background    = Color(0xFF000000),
        surface       = Color(0xFF0D0D0D),
        surfaceVariant= Color(0xFF181818),
        primary       = Color(0xFFBB86FC),
    ),
    ThemePreset(
        name          = "Ocean",
        background    = Color(0xFF0D1B2A),
        surface       = Color(0xFF162435),
        surfaceVariant= Color(0xFF1E3045),
        primary       = Color(0xFF0EA5E9),
    ),
    ThemePreset(
        name          = "Forest",
        background    = Color(0xFF0D1A12),
        surface       = Color(0xFF142010),
        surfaceVariant= Color(0xFF1C2E1A),
        primary       = Color(0xFF22C55E),
    ),
    ThemePreset(
        name          = "Sunset",
        background    = Color(0xFF1A0D0D),
        surface       = Color(0xFF251515),
        surfaceVariant= Color(0xFF301C1C),
        primary       = Color(0xFFF97316),
    ),
    ThemePreset(
        name          = "Rose",
        background    = Color(0xFF1A0D14),
        surface       = Color(0xFF25151E),
        surfaceVariant= Color(0xFF301C28),
        primary       = Color(0xFFEC4899),
    ),
    ThemePreset(
        name          = "Steel",
        background    = Color(0xFF131419),
        surface       = Color(0xFF1C1D25),
        surfaceVariant= Color(0xFF252630),
        primary       = Color(0xFF64748B),
    ),
    ThemePreset(
        name          = "Custom",
        background    = Color(0xFF121212),
        surface       = Color(0xFF1E1E1E),
        surfaceVariant= Color(0xFF2A2A2A),
        primary       = Color(0xFF8B6BE0),
    ),
)

val CUSTOM_PRESET_INDEX = themePresets.size - 1
