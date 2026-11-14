package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.winlator.star.ui.screens.WrapperManagerDialog
import com.winlator.star.ui.screens.WrapperManagerScreen

/** Containers, wrappers, graphics tools and downloads (Lab). */
@Suppress("UNUSED_PARAMETER")
internal fun NavGraphBuilder.labContainerRoutes(open: (String) -> Unit, back: () -> Unit) {
    // Placeholder until the Lab wrapper manager exists: the Classic screen under the Lab route.
    composable(LabParityRoutes.WRAPPERS) { WrapperManagerScreen() }
}

/** The wrapper manager over a settings editor (Graphics › "Manage graphics wrappers"). */
@Composable
internal fun LabWrapperManagerDialog(onDismiss: () -> Unit) {
    WrapperManagerDialog(onDismiss = onDismiss)
}
