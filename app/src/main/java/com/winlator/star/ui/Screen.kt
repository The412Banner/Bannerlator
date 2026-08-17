package com.winlator.star.ui

sealed class Screen(val route: String, val label: String, val iconName: String) {
    object Containers    : Screen("containers",     "Containers",             "folder")
    object Games         : Screen("games",           "Games",                  "shortcut")
    object Contents      : Screen("contents",       "Contents",               "inventory_2")
    object InputControls : Screen("input_controls", "Input Controls",         "sports_esports")
    object AdrenoTools   : Screen("adreno_tools",   "Adrenotools GPU Drivers","memory")
    object Saves         : Screen("saves",          "Saves",                  "save")
    object FileManager   : Screen("file_manager",   "File Manager",           "folder_open")
    object Settings      : Screen("settings",       "Settings",               "settings")
    object Appearance    : Screen("appearance",     "Appearance",             "palette")

    object Gog    : Screen("gog",    "GOG",          "storefront")
    object Epic   : Screen("epic",   "Epic Games",   "storefront")
    object Amazon : Screen("amazon", "Amazon Games", "storefront")
    object Steam  : Screen("steam",  "Steam",        "storefront")

    object ContainerDetail : Screen("container_detail?id={id}", "Container", "")

    companion object {
        val drawerItems by lazy {
            listOf(Games, Containers, FileManager, Settings, Appearance, InputControls, AdrenoTools, Saves)
        }
        val storeItems by lazy {
            listOf(Gog, Epic, Amazon, Steam)
        }
    }
}
