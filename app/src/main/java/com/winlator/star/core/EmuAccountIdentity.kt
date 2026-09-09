package com.winlator.star.core

import java.io.File

/**
 * The Steam account identity a cracked game's emulator keeps INSIDE the Wine prefix.
 *
 * Why this exists: a cracked game does not sign in to Steam, so its emulator invents an account id
 * and stores it in the prefix. Games that key their saves off that id — God of War writes
 * "Saved Games/God of War/<accountId>/game.sav", and stamps the owning id INTO the save (uint32 LE
 * at offset 0x20) so it refuses a save whose embedded id doesn't match the running one — become
 * unreadable the moment the id changes. And it does change: every emulator here generates a fresh
 * RANDOM id whenever its file is missing, which is exactly what a rebuilt prefix looks like.
 *
 * So a save backup that carries only the save files is half a backup. Restoring it into a rebuilt
 * prefix drops the save next to a *different* account id and the game shows "New Game" — the save is
 * there, byte-perfect, and unreachable. [GameSaveBackup] therefore sweeps these roots into every
 * scoped backup and puts them back on restore.
 *
 * All paths are relative to the Wine user profile (".wine/drive_c/users/xuser"), '/'-separated.
 * Both entries below were confirmed present in live containers; add a new emulator by appending to
 * [EMULATORS] — everything else keys off that list.
 */
object EmuAccountIdentity {

    /**
     * One emulator's identity store.
     *
     * [idFiles] are tried in order and the first readable id wins, because these emulators accept
     * several spellings of the same setting and a prefix can carry more than one. A ".ini" entry is
     * parsed for [iniKey]; anything else is read as a bare id on the first line.
     */
    data class Emu(
        val label: String,
        val root: String,
        val idFiles: List<String>,
        val iniKey: String? = null,
    )

    /** SteamID64 = account id + this. The account id is what names the save folder. */
    const val STEAM_ID64_BASE = 76561197960265728L

    val EMULATORS = listOf(
        // FLT ("FairLight") — one file, one line, no newline. Regenerated random when absent.
        Emu(
            label = "FLT",
            root = "AppData/Roaming/FLT",
            idFiles = listOf("steam_id.txt"),
        ),
        // Goldberg / gbe_fork. Only the "settings" subdir is identity — the "GSE Saves" parent also
        // holds per-appid save data and achievements for OTHER games, which a per-game backup has no
        // business dragging in.
        Emu(
            label = "Goldberg",
            root = "AppData/Roaming/GSE Saves/settings",
            idFiles = listOf("force_account_steamid.txt", "user_steam_id.txt", "configs.user.ini"),
            iniKey = "account_steamid",
        ),
    )

    /** Profile-relative dirs swept into every scoped backup. */
    val BACKUP_ROOTS: List<String> = EMULATORS.map { it.root }

    /** The emulator owning [relPath] (a profile-relative, '/'-separated path), or null. */
    fun emuFor(relPath: String): Emu? {
        val p = relPath.replace('\\', '/').trimStart('/')
        return EMULATORS.firstOrNull {
            p.equals(it.root, ignoreCase = true) ||
                p.startsWith(it.root + "/", ignoreCase = true)
        }
    }

    /** True when [relPath] is part of some emulator's identity store rather than save data. */
    fun isIdentityPath(relPath: String): Boolean = emuFor(relPath) != null

    /** The emulator whose store is rooted exactly at [root]. */
    fun emuForRoot(root: String): Emu? = EMULATORS.firstOrNull { it.root.equals(root, ignoreCase = true) }

    /**
     * The account id [emu] is pinned to under [profile] (a Wine user profile dir, or a staging dir
     * mirroring one), or null when nothing readable is stored there.
     */
    fun readId(profile: File, emu: Emu): String? {
        for (name in emu.idFiles) {
            val f = File(File(profile, emu.root), name)
            if (!f.isFile) continue
            val id = try {
                if (name.endsWith(".ini", ignoreCase = true)) readIniId(f, emu.iniKey) else readBareId(f)
            } catch (e: Exception) {
                null
            }
            if (id != null) return id
        }
        return null
    }

    /** The save-folder name a SteamID64 maps to (God of War et al. name the folder by account id). */
    fun accountFolderId(steamId64: String): String? {
        val v = steamId64.toLongOrNull() ?: return null
        val account = v - STEAM_ID64_BASE
        return if (account in 1..0xFFFFFFFFL) account.toString() else null
    }

    private fun readBareId(f: File): String? =
        f.readText().trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** Pulls "<key>=<digits>" out of an emulator .ini, ignoring ";"/"#" comment lines. */
    private fun readIniId(f: File, key: String?): String? {
        if (key == null) return null
        return f.readLines().asSequence()
            .map { it.trim() }
            .filter { !it.startsWith(";") && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0 && line.substring(0, i).trim().equals(key, ignoreCase = true)) {
                    line.substring(i + 1).trim()
                } else null
            }
            .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
