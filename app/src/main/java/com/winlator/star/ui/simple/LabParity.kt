package com.winlator.star.ui.simple

import androidx.navigation.NavGraphBuilder

/**
 * Lab pages that stand in for Classic screens. Each area registers its own pages in its own file:
 * settings (LabSettingsPages.kt), game actions (LabGameActions.kt), community configs, game logs and
 * launch pop-ups (LabCommunity.kt), containers, wrappers and downloads (LabWrappers.kt), contents and
 * controls (LabContentsExtra.kt), files, logs and saves (LabTools.kt).
 */
internal object LabParityRoutes {
    /** Lab's wrapper manager (Tools › Wrappers, Search › Wrappers, the editors' "Manage graphics wrappers"). */
    const val WRAPPERS = "lab_wrappers"
}

internal fun NavGraphBuilder.labParityRoutes(open: (String) -> Unit, back: () -> Unit) {
    labSettingsRoutes(open, back)
    labGameRoutes(open, back)
    labCommunityRoutes(open, back)
    labContainerRoutes(open, back)
    labContentsRoutes(open, back)
    labToolsRoutes(open, back)
}
