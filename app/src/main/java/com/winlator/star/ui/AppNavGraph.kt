package com.winlator.star.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument


import com.winlator.star.ui.screens.AdrenoToolsScreen
import com.winlator.star.ui.screens.AppearanceScreen
import com.winlator.star.ui.screens.ShortcutsScreen
import com.winlator.star.ui.screens.ContainerDetailScreen
import com.winlator.star.ui.screens.ContainersScreen
import com.winlator.star.ui.screens.contents.ContentsHubScreen
import com.winlator.star.ui.screens.FileManagerScreen
import com.winlator.star.ui.screens.FragmentScreen
import com.winlator.star.ui.screens.SavesScreen
import com.winlator.star.ui.screens.InputControlsScreen
import com.winlator.star.ui.screens.GamesWallScreen
import com.winlator.star.ui.screens.SettingsScreen
import com.winlator.star.ui.screens.WrapperManagerScreen
import com.winlator.star.store.SaveManagerScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startRoute: String = Screen.Games.route,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as FragmentActivity

    NavHost(navController = navController, startDestination = startRoute, modifier = modifier) {


        composable(Screen.Containers.route) {
            ContainersScreen(
                onNavigateToDetail = { containerId ->
                    val route = if (containerId != null) {
                        "container_detail?id=$containerId"
                    } else {
                        "container_detail?id=-1"
                    }
                    navController.navigate(route)
                },
            )
        }

        composable(
            route = "container_detail?id={id}",
            arguments = listOf(
                navArgument("id") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getInt("id") ?: -1
            ContainerDetailScreen(
                containerId = id,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Games.route) {
            // The main library is the phone-grid ShortcutsScreen again. The landscape couch "games wall"
            // (GamesWallScreen) is opt-in behind enable_big_picture_mode and renders on the BigPicture route.
            ShortcutsScreen()
        }

        composable(Screen.Contents.route) {
            ContentsHubScreen()
        }

        composable(Screen.BigPicture.route) {
            // Big Picture couch mode now renders the games wall. The old BigPictureScreen composable is
            // retired (no longer routed); GamesWallScreen still reuses its loadCover/launchShortcut helpers.
            GamesWallScreen(navController = navController)
        }

        composable(Screen.InputControls.route) {
            InputControlsScreen()
        }

        composable(Screen.AdrenoTools.route) {
            AdrenoToolsScreen()
        }

        composable(Screen.Wrappers.route) {
            WrapperManagerScreen()
        }

        composable(Screen.FileManager.route) {
            FileManagerScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onSaved = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Games.route) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(Screen.Appearance.route) {
            AppearanceScreen()
        }

        composable(Screen.Saves.route) {
            SavesScreen()
        }

        composable(Screen.SaveManager.route) {
            SaveManagerScreen()
        }
    }
}
