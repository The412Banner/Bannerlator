package com.winlator.star.store

import com.winlator.star.store.download.Store

/**
 * Which shortcuts belong to a game that is being uninstalled from the Download Manager: the ones the store tagged with the game's id
 * (Steam's `steamAppId`, Epic's `epicAppName`), and any whose exe, resolved to an Android path, lies inside the game's install folder
 * (how the other stores' shortcuts, and ones added by hand, are tied to it). Only a real path prefix counts: another folder that
 * merely shares the name (a backup copy, the same name on another volume, a longer sibling name) is not the game. Pure, so it can be
 * tested.
 */
internal object DownloadShortcutMatch {
    /** A folder shorter than this (as a path) is never used for matching: it could swallow unrelated games. */
    private const val MIN_KEY_LENGTH = 8

    /**
     * True when the shortcut whose extras [extra] reads (empty string when absent) and whose exe resolves to the Android path
     * [exeAndroid] (null when it could not be resolved) belongs to the game [id] of [store], installed in [installPath].
     */
    fun belongs(
        store: Store,
        id: String,
        installPath: String?,
        exeAndroid: String?,
        extra: (String) -> String,
    ): Boolean {
        if (id.isNotEmpty()) {
            when (store) {
                Store.STEAM -> if (extra("steamAppId") == id) return true
                Store.EPIC -> if (extra("storeSource") == "epic" && extra("epicAppName") == id) return true
                else -> Unit
            }
        }
        return insideInstallFolder(installPath, exeAndroid)
    }

    /** True when the Android path [exeAndroid] lies inside [installPath] (the folder itself is not "inside"). */
    fun insideInstallFolder(installPath: String?, exeAndroid: String?): Boolean {
        if (installPath.isNullOrBlank() || exeAndroid.isNullOrBlank()) return false
        val dir = flat(installPath)
        if (dir.length < MIN_KEY_LENGTH) return false
        return flat(exeAndroid).startsWith("$dir/")
    }

    private fun flat(p: String): String = p.trim().trim('"').replace('\\', '/').lowercase().trimEnd('/')
}
