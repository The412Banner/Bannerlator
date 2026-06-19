package com.winlator.star.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

val LocalTopBarActions = compositionLocalOf<MutableState<@Composable RowScope.() -> Unit>> {
    mutableStateOf({})
}

fun topBarActionsState(): MutableState<@Composable RowScope.() -> Unit> = mutableStateOf({})
