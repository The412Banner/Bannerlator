package com.winlator.star.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalTopBarActions = compositionLocalOf<MutableState<@Composable RowScope.() -> Unit>> {
    mutableStateOf({})
}

fun topBarActionsState(): MutableState<@Composable RowScope.() -> Unit> = mutableStateOf({})

/**
 * Set by a screen that wants the shared top bar see-through (the Games tab's XMB view, which draws
 * its own backdrop). MainActivity then paints the bar transparent and lays the screen out UNDER it.
 * Only honoured on the Games route, so no other screen can inherit it.
 */
val LocalTopBarTransparent = compositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/** Height of the see-through top bar the current screen is drawn under; 0.dp while the bar is opaque. */
val LocalTopBarOverlayInset = compositionLocalOf<Dp> { 0.dp }
