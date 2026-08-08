package com.winlator.star.ui

import androidx.annotation.StringRes
import com.winlator.star.R

sealed class Screen(val route: String, @StringRes val labelRes: Int, val iconName: String) {
    object Containers    : Screen("containers",      R.string.screen_containers,     "folder")
    object Games         : Screen("games",           R.string.screen_games,          "shortcut")
    object Contents      : Screen("contents",        R.string.screen_contents,       "inventory_2")
    object InputControls : Screen("input_controls",  R.string.screen_input_controls, "sports_esports")
    object AdrenoTools   : Screen("adreno_tools",    R.string.screen_adreno_tools,   "memory")
    object Wrappers      : Screen("wrapper_manager", R.string.screen_manage_wrappers,"layers")
    object Saves         : Screen("saves",           R.string.screen_saves,          "save")
    object SaveManager   : Screen("save_manager",    R.string.screen_save_manager,   "save")
    object FileManager   : Screen("file_manager",    R.string.screen_file_manager,   "folder_open")
    object Settings      : Screen("settings",        R.string.screen_settings,       "settings")
    object Appearance    : Screen("appearance",      R.string.screen_appearance,     "palette")

    object Gog    : Screen("gog",    R.string.screen_gog,    "storefront")
    object Epic   : Screen("epic",   R.string.screen_epic,   "storefront")
    object Amazon : Screen("amazon", R.string.screen_amazon, "storefront")
    object Steam  : Screen("steam",  R.string.screen_steam,  "storefront")

    object ContainerDetail : Screen("container_detail?id={id}", R.string.screen_container, "")

    // Couch/TV launcher shown at startup instead of the normal UI when enable_big_picture_mode is on.
    // Registered as a route (see AppNavGraph) but intentionally NOT listed in the drawer.
    object BigPicture : Screen("big_picture", R.string.screen_big_picture, "sports_esports")

    companion object {
        val drawerItems by lazy {
            // Screen.Wrappers stays registered as a route (the wrapper manager is now reached via the
            // ☁ cloud button in container/game settings) but is intentionally NOT listed in the drawer.
            listOf(Games, Containers, FileManager, SaveManager, Settings, Appearance, InputControls, AdrenoTools)
        }
        val storeItems by lazy {
            listOf(Gog, Epic, Amazon, Steam)
        }
    }
}
