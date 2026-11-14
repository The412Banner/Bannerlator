package com.winlator.star.ui.simple

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.winlator.star.ui.screens.WrapperManagerScreen

/** Containers, wrappers, graphics tools and downloads (Lab). */
@Suppress("UNUSED_PARAMETER")
internal fun NavGraphBuilder.labContainerRoutes(open: (String) -> Unit, back: () -> Unit) {
    // Placeholder until the Lab wrapper manager exists: the Classic screen under the Lab route.
    composable(LabParityRoutes.WRAPPERS) { WrapperManagerScreen() }
}
