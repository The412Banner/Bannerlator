package com.winlator.star.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

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

@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val colorScheme by AppThemeState.colorScheme.collectAsState(initial = AppThemeState.currentColorSchemeSnapshot())
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
