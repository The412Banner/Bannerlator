package com.winlator.star.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.winlator.star.R

private val DefaultColorScheme = darkColorScheme(
    primary          = Primary,
    onPrimary        = OnPrimary,
    background       = Background,
    onBackground     = OnBackground,
    surface          = Surface,
    onSurface        = OnSurface,
    surfaceVariant   = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    error            = Error,
)

private val BricolageFontFamily = FontFamily(
    Font(R.font.bricolage_grotesque),
)

private val Default = Typography()

private val AppTypography = Typography(
    displayLarge   = Default.displayLarge.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    displayMedium  = Default.displayMedium.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    displaySmall   = Default.displaySmall.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    headlineLarge  = Default.headlineLarge.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    headlineMedium = Default.headlineMedium.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    headlineSmall  = Default.headlineSmall.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    titleLarge     = Default.titleLarge.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    titleMedium    = Default.titleMedium.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    titleSmall     = Default.titleSmall.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    bodyLarge      = Default.bodyLarge.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    bodyMedium     = Default.bodyMedium.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    bodySmall      = Default.bodySmall.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    labelLarge     = Default.labelLarge.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    labelMedium    = Default.labelMedium.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
    labelSmall     = Default.labelSmall.copy(fontFamily = BricolageFontFamily, fontWeight = FontWeight(600)),
)

@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val colorScheme by AppThemeState.colorScheme.collectAsState(initial = AppThemeState.currentColorSchemeSnapshot())
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
