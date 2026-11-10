package com.winlator.star.ui.simple

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.winlator.star.ui.theme.AppThemeState
import kotlin.math.roundToInt

/** Classic's interface and text sizes: 50–150 % in 5 % steps, plus [current] when Classic's slider left it between two. */
internal fun labScaleSteps(current: Float): List<Float> {
    val steps = (10..30).map { it * 5 / 100f }
    val p = (current * 100).roundToInt()
    return if (steps.any { (it * 100).roundToInt() == p }) steps else (steps + current).sorted()
}

/** The storage bars in Lab's side menu (Classic's Appearance › Side Menu switches). */
@Composable
internal fun labDrawerEntries(): List<MenuEntry> {
    val showInternal by AppThemeState.showInternalStorage.collectAsState()
    val showSd by AppThemeState.showSdStorage.collectAsState()
    return listOf(
        MenuEntry(
            "drawer-internal", L("Show internal storage"), icon = Icons.Filled.Storage, group = L("Side menu"),
            toggle = showInternal, onA = { AppThemeState.setShowInternalStorage(!showInternal) },
        ),
        MenuEntry(
            "drawer-sd", L("Show SD card storage"), icon = Icons.Filled.SdCard, group = L("Side menu"),
            toggle = showSd, onA = { AppThemeState.setShowSdStorage(!showSd) },
        ),
    )
}
