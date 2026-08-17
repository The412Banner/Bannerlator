package com.winlator.star.ui

sealed class Screen(val route: String, val label: String, val iconName: String) {
    object Containers    : Screen("containers",     "Containers",             "folder")
    object Games         : Screen("games",           "Games",                  "shortcut")
    object Contents      : Screen("contents",       "Contents",               "inventory_2")
    object InputControls : Screen("input_controls", "Input Controls",         "sports_esports")
    object AdrenoTools   : Screen("adreno_tools",   "Adrenotools GPU Drivers","memory")
    object Saves         : Screen("saves",          "Saves",                  "save")
    // Route objects only — restored BigPictureScreen.kt (upstream) references these;
    // neither route is registered in this fork's AppNavGraph (subsystem not ported).
    object Wrappers      : Screen("wrapper_manager","Manage Wrappers",        "layers")
    object BigPicture    : Screen("big_picture",    "Big Picture",            "sports_esports")
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
            // Upstream (The412Banner) moved to: Games, Containers, FileManager, Settings,
            // Appearance, InputControls, Contents, Saves — and dropped AdrenoTools on the
            // way. Our fork's AdrenoTools screen is a live route with an AppDrawer icon
            // mapping and no alternate entry point (upstream's ☁-button goes to its own
            // Wrappers screen, which this fork doesn't have), so it stays in the drawer.
            listOf(Games, Containers, FileManager, Settings, Appearance, InputControls, AdrenoTools, Contents, Saves)
        }
        val storeItems by lazy {
            listOf(Gog, Epic, Amazon, Steam)
        }
    }
}
