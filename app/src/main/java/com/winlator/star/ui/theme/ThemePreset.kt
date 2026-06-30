package com.winlator.star.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

data class ThemePreset(
    val name: String,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    // Dim accent used for low-emphasis fills/borders/tracks (selected-tab gradient base,
    // unselected chip outlines, switch-on track, accent-button bg). Defaults to a darkened
    // primary so it follows the theme; AMOLED overrides it to the exact legacy #002277 to
    // keep the default look byte-identical.
    val accentDim: Color = lerp(primary, Color(0xFF000000), 0.55f),
    val onSurface: Color = Color(0xFFE0E0E0),
    val onSurfaceVariant: Color = Color(0xFFAAAAAA),
    val onBackground: Color = Color(0xFFFFFFFF),
    val onPrimary: Color = Color(0xFFFFFFFF),
    val divider: Color = Color(0xFF404040),
    val error: Color = Color(0xFFCF6679),
    // Elevated "container" surfaces (Material3's surfaceContainer family) for raised cards,
    // dialogs and buttons-on-cards. Derived defaults lerp `surface` toward `onSurface` so every
    // preset gets a tasteful, automatically-recoloring elevation ramp (low → high → highest).
    // AMOLED overrides these to the rebuilt blue-on-black depth so the default card/dialog look
    // keeps its raised feel instead of flattening into the near-black surface.
    val surfaceContainer: Color = lerp(surface, onSurface, 0.05f),
    val surfaceContainerHigh: Color = lerp(surface, onSurface, 0.09f),
    val surfaceContainerHighest: Color = lerp(surface, onSurface, 0.14f),
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
            // Elevated container ramp — raised cards/dialogs/buttons read above the flat surface.
            surfaceContainerLowest  = surface,
            surfaceContainerLow     = surfaceContainer,
            surfaceContainer        = surfaceContainer,
            surfaceContainerHigh    = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            // Themed divider/hairline token. Previously unset -> fell through to Material's
            // light-mauve default; now follows the preset's `divider` so drawer dividers stay
            // near-black on AMOLED (matches the legacy look) and recolor with other presets.
            outline              = divider,
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
            surfaceContainerLowest  = Color(0xFFFFFFFF),
            surfaceContainerLow     = Color(0xFFF5F5F5),
            surfaceContainer        = Color(0xFFF0F0F0),
            surfaceContainerHigh    = Color(0xFFE8E8E8),
            surfaceContainerHighest = Color(0xFFE0E0E0),
            outline              = Color(0xFFCCCCCC),
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
        primary       = Color(0xFF0055FF),
    ),
    ThemePreset(
        name          = "AMOLED",
        background    = Color(0xFF000000),
        surface       = Color(0xFF000000),
        surfaceVariant= Color(0xFF050505),
        primary       = Color(0xFF0055FF),
        accentDim     = Color(0xFF002277),  // exact legacy value → default stays byte-identical
        onSurface     = Color(0xFFEEEEEE),
        divider       = Color(0xFF111111),
        // Restore the rebuilt blue-on-black card/dialog depth at the default so raised surfaces
        // don't flatten into pure black: low card ≈ legacy 1A1A2E, dialogs/buttons ≈ 2A2A38,
        // buttons-on-cards / tracks ≈ 38383F.
        surfaceContainer        = Color(0xFF1A1A2E),
        surfaceContainerHigh    = Color(0xFF2A2A38),
        surfaceContainerHighest = Color(0xFF38383F),
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
        primary       = Color(0xFF0055FF),
    ),
)

val CUSTOM_PRESET_INDEX = themePresets.size - 1
