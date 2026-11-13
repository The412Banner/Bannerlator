package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.Screen
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.screens.LogManagerScreen
import com.winlator.star.ui.simple.SimpleRoutes
import com.winlator.star.ui.theme.AppThemeState

// ── App settings ────────────────────────────────────────────────────────────────────────────────

/**
 * The Settings tab. XMB only: the six-tab editor of [AppSettingsLayout] (theme popup, Steam library locations first, no logs): the
 * registry's rows plus the XMB-only rows (AppSettingsRows), the page items of [AppSettingsExtras] (updates, folders, backup, sound
 * banks, Lossless.dll, frame-gen capture...) and no hand-off row at all. The plain Deck and Lab get the registry's categories and rows
 * exactly as they always did ([layout] null, no extras, hand-off rows that open the classic screens), so Lab's own Settings menu keeps
 * working. [onMyAccount] null hides the My account line of General > About and help.
 */
@Composable
internal fun DeckAppSettings(navController: NavHostController, onAbout: () -> Unit, onMyAccount: (() -> Unit)? = null) {
    val context = LocalContext.current
    val env = remember { SettingsEnv(context, EditorScope.APP, null, null) }
    val source = remember { AppSource(env) }
    val xmb = rememberXmbHome() && !AppThemeState.simpleLook
    var showLogs by remember { mutableStateOf(false) }
    val about by rememberUpdatedState(onAbout)
    val account by rememberUpdatedState(onMyAccount)
    val openAbout: () -> Unit = { about() }
    val openAccount: () -> Unit = { account?.invoke() }
    val tools = remember(xmb) {
        if (xmb) AppSettingsExtras(context, openAbout, if (onMyAccount != null) openAccount else null) else null
    }
    val extras = remember(tools) { tools?.extras() ?: emptyMap<String, CategoryExtra>() }
    DeckSettingsEditor(
        header = EditorHeader(
            overline = "Bannerlator",
            title = L("Settings"),
            icon = Icons.Filled.Settings,
            subtitle = L("Every app setting applies straight away."),
        ),
        source = source,
        categories = AppSettingsRegistry.categories,
        defs = if (xmb) AppSettingsRegistry.defs else AppSettingsRegistry.legacyDefs,
        handoffs = AppSettingsRegistry.handoffs,
        onExit = null,
        // The plain Deck and Lab reach the hand-offs (their Settings lists them); the XMB layout drops every one.
        onHandoff = { h ->
            when (h.target) {
                AppSettingsRegistry.TARGET_APPEARANCE -> navController.navigate(
                    if (AppThemeState.simpleLook) SimpleRoutes.APPEARANCE else Screen.Appearance.route,
                )
                AppSettingsRegistry.TARGET_LOGS -> showLogs = true
                AppSettingsRegistry.TARGET_ABOUT -> onAbout()
                AppSettingsRegistry.TARGET_INPUT_CONTROLS -> navController.navigate(
                    if (AppThemeState.simpleLook) SimpleRoutes.CONTROLS else Screen.InputControls.route,
                )
                AppSettingsRegistry.TARGET_CONTENTS -> navController.navigate(
                    if (AppThemeState.simpleLook) SimpleRoutes.DRIVERS else Screen.Contents.route,
                )
                AppSettingsRegistry.TARGET_DEFAULTS -> navController.navigate(DeckSettingsRoutes.DEFAULTS)
                else -> navController.navigate(Screen.Settings.route)
            }
        },
        extras = extras,
        layout = if (xmb) AppSettingsLayout.layout else null,
    )
    if (tools != null) tools.Dialogs()
    if (showLogs) {
        Dialog(onDismissRequest = { showLogs = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            DeckDialogBars()
            DeckDialogScaled {
                LogManagerScreen(onClose = { showLogs = false })
            }
        }
    }
}
