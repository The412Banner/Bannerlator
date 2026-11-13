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

    /**
     * True when [shortcutPath] lies inside [installPath]. [shortcutPath] is an Android path (callers resolve a Windows path through the
     * shortcut's container drives first) or a Windows path on Z:, the imagefs root at [imageFsRoot]; any other drive letter is not
     * known here and never matches. The match is anchored: the exe must sit under the folder itself, not under a folder that merely
     * contains its name (a backup copy, the same folder name on another drive).
     */
    fun insideInstallFolder(installPath: String?, imageFsRoot: String, shortcutPath: String?): Boolean {
        if (installPath.isNullOrBlank() || shortcutPath.isNullOrBlank()) return false
        val root = flat(imageFsRoot)
        val folder = flat(installPath)
        // Too short (or the imagefs root itself): it could swallow unrelated games.
        val rel = if (root.isNotEmpty() && folder.startsWith(root)) folder.substring(root.length) else folder
        if (("/" + rel.trim('/') + "/").length < MIN_KEY_LENGTH) return false
        var exec = shortcutPath.replace('\\', '/').lowercase().trim()
        val drive = Regex("^([a-z]):").find(exec)
        if (drive != null) {
            if (drive.groupValues[1] != "z" || root.isEmpty()) return false
            exec = root + "/" + exec.substring(2).trimStart('/')
        }
        return exec.startsWith("$folder/")
    }

    private fun flat(p: String): String = p.replace('\\', '/').lowercase().trimEnd('/')
}
