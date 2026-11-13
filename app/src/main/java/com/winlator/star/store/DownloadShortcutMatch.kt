package com.winlator.star.store

import com.winlator.star.store.download.Store

/**
 * Which shortcuts belong to a game that is being uninstalled from the Download Manager: the ones the store tagged with the game's id
 * (Steam's `steamAppId`, Epic's `epicAppName`), and any whose exe lies inside the game's install folder (how the other stores' shortcuts,
 * and ones added by hand, are tied to it). Pure, so it can be tested.
 */
internal object DownloadShortcutMatch {
    /** A folder shorter than this (as a path key) is never used for matching: it could swallow unrelated games. */
    private const val MIN_KEY_LENGTH = 8

    /**
     * True when the shortcut whose exe is [shortcutPath] and whose extras [extra] reads (empty string when absent) belongs to the
     * game [id] of [store], installed in [installPath] (inside the container drive at [imageFsRoot]).
     */
    fun belongs(
        store: Store,
        id: String,
        installPath: String?,
        imageFsRoot: String,
        shortcutPath: String?,
        extra: (String) -> String,
    ): Boolean {
        if (id.isNotEmpty()) {
            when (store) {
                Store.STEAM -> if (extra("steamAppId") == id) return true
                Store.EPIC -> if (extra("storeSource") == "epic" && extra("epicAppName") == id) return true
                else -> Unit
            }
        }
        return insideInstallFolder(installPath, imageFsRoot, shortcutPath)
    }

    /** True when [shortcutPath] (a Windows or Android path) lies inside [installPath]. */
    fun insideInstallFolder(installPath: String?, imageFsRoot: String, shortcutPath: String?): Boolean {
        if (installPath.isNullOrBlank() || shortcutPath.isNullOrBlank()) return false
        val root = flat(imageFsRoot)
        val abs = flat(installPath)
        val rel = if (root.isNotEmpty() && abs.startsWith(root)) abs.substring(root.length) else abs
        val keys = listOf(rel, abs).map { "/" + it.trim('/') + "/" }.filter { it.length >= MIN_KEY_LENGTH }.distinct()
        var exec = shortcutPath.replace('\\', '/').lowercase().trim().replaceFirst(Regex("^[a-z]:"), "")
        if (!exec.startsWith("/")) exec = "/$exec"
        return keys.any { exec.contains(it) }
    }

    private fun flat(p: String): String = p.replace('\\', '/').lowercase().trimEnd('/')
}
