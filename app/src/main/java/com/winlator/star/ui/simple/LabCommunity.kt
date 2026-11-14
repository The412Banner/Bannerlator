package com.winlator.star.ui.simple

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.winlator.star.container.Shortcut
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.store.SaveManagerScreen
import com.winlator.star.ui.screens.ShortcutActions
import com.winlator.star.ui.theme.AppThemeState

/** Lab's pages for community configs, a game's logs and a game's cloud saves. */
internal object LabCommunityRoutes {
    /** The community catalog (the top line's and the drawer's Community configs). */
    const val BROWSER = "lab_community"
    /** One catalog game's configs. */
    const val GAME = "lab_community_game?id={id}"
    fun game(identity: String) = "lab_community_game?id=" + Uri.encode(identity)
    /** A game's own community configs (its game menu): the match, share, import, upload. Filed under Library. */
    const val FOR = "lab_game_community?path={path}"
    fun forGame(path: String) = "lab_game_community?path=" + Uri.encode(path)
    /** One config's details: the pick in [LabCommunity.detail]. */
    const val CONFIG = "lab_community_config"
    const val UPLOADS = "lab_community_uploads"
    const val ACCOUNT = "lab_account"
    /** A game's logs (its game menu's View logs). */
    const val LOGS = "lab_game_logs?path={path}"
    fun logs(path: String) = "lab_game_logs?path=" + Uri.encode(path)
    /** Lab's Save Manager on one game (a game's Cloud saves); filed under Save Manager. */
    const val SAVES = "simple_saves_game?app={app}"
    fun saves(appId: Int) = "simple_saves_game?app=$appId"
}

/**
 * Opening a Lab page from code that has no page's `open` (the game actions, the Save Manager launch): the shell's open,
 * kept when the Lab routes are registered, while the Lab frame is on screen.
 */
internal object LabNav {
    @Volatile
    var open: ((String) -> Unit)? = null
    private var frames by mutableIntStateOf(0)

    /** The Lab frame (its top line) is shown / gone. */
    fun frameShown() { frames++ }
    fun frameGone() { frames = (frames - 1).coerceAtLeast(0) }

    /** Lab is the look and its frame and pages are up. */
    val ready: Boolean get() = AppThemeState.simpleLook && frames > 0 && open != null

    /** Opens [route] when [ready]; false when it could not (the caller keeps its Classic way). */
    fun go(route: String): Boolean {
        val o = open
        if (!ready || o == null) return false
        o(route)
        return true
    }
}

/** Community configs, game logs and cloud saves (Lab). The launch pop-ups' Lab variants are in LabLaunchDialogs.kt. */
internal fun NavGraphBuilder.labCommunityRoutes(open: (String) -> Unit, back: () -> Unit) {
    LabNav.open = open
    composable(LabCommunityRoutes.BROWSER) { LabCommunityBrowserPage(open, back) }
    composable(LabCommunityRoutes.GAME, arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })) { e ->
        LabCommunityGamePage(e.arguments?.getString("id").orEmpty(), back)
    }
    composable(LabCommunityRoutes.FOR, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { e ->
        LabGameCommunityPage(e.arguments?.getString("path").orEmpty(), open, back)
    }
    composable(LabCommunityRoutes.CONFIG) { LabConfigDetailsPage(back) }
    composable(LabCommunityRoutes.UPLOADS) { LabMyUploadsPage(back) }
    composable(LabCommunityRoutes.ACCOUNT) { LabMyAccountPage(open, back) }
    composable(LabCommunityRoutes.LOGS, arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })) { e ->
        LabGameLogsPage(e.arguments?.getString("path").orEmpty(), back)
    }
    composable(LabCommunityRoutes.SAVES, arguments = listOf(navArgument("app") { type = NavType.IntType; defaultValue = 0 })) { e ->
        SaveManagerScreen(focusAppId = e.arguments?.getInt("app") ?: 0, onBack = back, lab = true)
    }
}

/**
 * Lab's way for a game's Community configs, View logs, Game details and My account (ShortcutActions, from the game menu,
 * the editors' hand-offs and the account button): its own pages instead of Classic's dialogs. ShortcutActionDialogs
 * places this in Lab and skips those dialogs.
 */
@Composable
internal fun LabActionHandoffs(actions: ShortcutActions) {
    val target = actions.communityTarget
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        actions.communityTarget = null
        actions.communityResult = null
        actions.communityLoading = false
        LabNav.go(LabCommunityRoutes.forGame(target.file.path))
    }
    val browser = actions.showCommunityBrowser
    LaunchedEffect(browser) {
        if (!browser) return@LaunchedEffect
        actions.showCommunityBrowser = false
        LabNav.go(LabCommunityRoutes.BROWSER)
    }
    val account = actions.showMyAccount
    LaunchedEffect(account) {
        if (!account) return@LaunchedEffect
        actions.showMyAccount = false
        LabNav.go(LabCommunityRoutes.ACCOUNT)
    }
    // Game details: Lab's own page (it follows a rename itself).
    val details = actions.gameDetailsShortcut
    LaunchedEffect(details) {
        if (details == null) return@LaunchedEffect
        actions.gameDetailsShortcut = null
        LabNav.go(LabRoutes.details(details.file.path))
    }
    actions.logsShortcut?.let { s -> LabGameLogsHandoff(s) { actions.logsShortcut = null } }
}

/**
 * View logs: the game's logs on their own page, or Classic's two "No logs" answers (per-game folders are off, or nothing
 * was captured yet) as a Lab message.
 */
@Composable
private fun LabGameLogsHandoff(s: Shortcut, onDone: () -> Unit) {
    val context = LocalContext.current
    val entry = remember(s.name) { LogInventory.forGame(context, s.name) }
    if (entry != null) {
        LaunchedEffect(s.file.path) {
            onDone()
            LabNav.go(LabCommunityRoutes.logs(s.file.path))
        }
        return
    }
    val perGameOff = remember { !LogLocation.isPerGameEnabled(context) }
    SimpleActionSheet(
        L("No logs for %1\$s", s.name),
        listOf(MenuAction(L("OK"), {})),
        onDismiss = onDone,
        message = if (perGameOff) {
            L("Per-game log folders are turned off, so everything is written to one shared folder and logs can't be traced back to a single game. Turn them on in Settings › Logs, then play %1\$s once.", s.name)
        } else {
            L("Nothing has been captured for %1\$s yet. Logs are written while a game runs — play it once, then check back here.", s.name)
        },
    ) { onDone() }
}

/** A game's logs in Lab's log viewer. */
@Composable
private fun LabGameLogsPage(path: String, back: () -> Unit) {
    val context = LocalContext.current
    val s = remember(path) { loadLabShortcut(context, path) }
    LaunchedEffect(s?.file?.path) { if (s != null) LabArt.selected = s }
    val entry = remember(s?.name) { s?.let { LogInventory.forGame(context, it.name) } }
    if (entry == null) {
        BackHandler { back() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (s == null) L("This game is no longer in your library.") else L("No logs for %1\$s", s.name), color = SimpleMuted)
        }
        return
    }
    LabLogViewer(entry = entry, onClose = back)
}
