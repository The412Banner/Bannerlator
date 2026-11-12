package com.winlator.star.store

import java.util.Locale

/**
 * The language a Steam game downloads and runs in. By default the app's language when the game has it,
 * else English; the game's page can pick another one (kept per game in [SteamPrefs], like the branch).
 * It decides which per-language depots are downloaded (library sync / install / update) and what the
 * Steam emulator and the appmanifest report to the game.
 *
 * Saved per app in steam_games.languages as "supported|packaged|selected": the languages Steam lists,
 * the ones with a depot of their own, and the language pack the last depot selection took.
 *
 * Safety rule (2026-11-12: a real Steam client deleted all of Brawlhalla when its appmanifest named a
 * language whose depots were not installed): what a real Steam client is told - appmanifest language and
 * installed depots - must describe exactly what is on disk. So an installed game keeps the pack it has
 * until an install/update/verify run of our own downloader starts ([beginInstall]); the pack on disk is
 * recorded only when such a run finished ([recordInstalled]).
 */
object SteamGameLanguage {
    const val ENGLISH = "english"

    /** Steam language -> a locale tag, for the name shown in the picker. */
    private val TAGS = mapOf(
        "arabic" to "ar", "bulgarian" to "bg", "schinese" to "zh-Hans", "tchinese" to "zh-Hant", "czech" to "cs",
        "danish" to "da", "dutch" to "nl", "english" to "en", "finnish" to "fi", "french" to "fr", "german" to "de",
        "greek" to "el", "hungarian" to "hu", "indonesian" to "id", "italian" to "it", "japanese" to "ja",
        "koreana" to "ko", "korean" to "ko", "norwegian" to "nb", "polish" to "pl", "portuguese" to "pt-PT",
        "brazilian" to "pt-BR", "romanian" to "ro", "russian" to "ru", "spanish" to "es-ES", "latam" to "es-419",
        "swedish" to "sv", "thai" to "th", "turkish" to "tr", "ukrainian" to "uk", "vietnamese" to "vi",
    )

    /** The language's own name ("Deutsch", "简体中文"); the Steam code for one we do not know. */
    fun label(code: String): String {
        val tag = TAGS[code.lowercase(Locale.ROOT)] ?: return code
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }.ifBlank { code }
    }

    // ── What the last library sync saved ─────────────────────────────────────

    private fun saved(appId: Int): List<String> =
        runCatching { SteamDatabase.getInstance().getLanguages(appId) }.getOrDefault("").split('|')

    /** The languages Steam lists for [appId]; empty when not known yet. */
    @JvmStatic
    fun supported(appId: Int): List<String> = parse(saved(appId).getOrNull(0))

    /** The languages [appId] ships as a depot of their own. */
    @JvmStatic
    fun packaged(appId: Int): List<String> = parse(saved(appId).getOrNull(1))

    /** True when the saved entry is from this format (it says which languages are packaged apart). */
    private fun knowsPacks(appId: Int): Boolean = saved(appId).size >= 3

    /** The language pack the last depot selection took for [appId]. */
    @JvmStatic
    fun selectedPack(appId: Int): String =
        saved(appId).getOrNull(2)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: ENGLISH

    @JvmStatic
    fun encode(supported: List<String>, packaged: List<String>, selected: String): String =
        supported.joinToString(",") + "|" + packaged.joinToString(",") + "|" + selected

    @JvmStatic
    fun parse(csv: String?): List<String> =
        csv.orEmpty().split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()

    // ── The wanted language ──────────────────────────────────────────────────

    /** The user's pick for [appId]; "" = follow the app's language. */
    @JvmStatic
    fun picked(appId: Int): String = runCatching { SteamPrefs.getGameLanguage(appId) }.getOrDefault("")

    /** The language [appId] is wanted in. */
    @JvmStatic
    fun of(appId: Int): String = resolve(picked(appId), supported(appId))

    /**
     * [picked] when the game has it (or its languages are not known), else the app's language when the
     * game has it, else English. Without a pick and without a language list it is English, as before.
     */
    @JvmStatic
    fun resolve(picked: String, supported: List<String>): String {
        val pick = picked.trim().lowercase(Locale.ROOT)
        if (pick.isNotEmpty() && (supported.isEmpty() || pick in supported)) return pick
        val app = runCatching { StoreLang.steam() }.getOrDefault(ENGLISH)
        return if (app in supported) app else ENGLISH
    }

    /** The language pack a download in [language] takes: its own when the game packages one, else English. */
    @JvmStatic
    fun packOf(language: String, packaged: List<String>): String = if (language in packaged) language else ENGLISH

    // ── The depot selection (library sync) ───────────────────────────────────

    /**
     * The language pack the depot selection takes for [appId]: [wantedPack] for a game that is not
     * installed, or when an install/update/verify run of our downloader has started ([beginInstall]);
     * otherwise the pack the installed copy has, so the saved depot list keeps describing the disk.
     */
    @JvmStatic
    fun selection(appId: Int, installed: Boolean, wantedPack: String): String {
        if (!installed) return wantedPack
        if (runCatching { SteamPrefs.isLanguageSwitchPending(appId) }.getOrDefault(false)) return wantedPack
        return installedPack(appId)
    }

    /** An install, update or verify run of [appId] starts: its depot selection may take the wanted pack. */
    @JvmStatic
    fun beginInstall(appId: Int) {
        runCatching { SteamPrefs.setLanguageSwitchPending(appId, true) }
    }

    /** The run finished: the pack it downloaded (the saved selection it used) is now the one on disk. */
    @JvmStatic
    fun recordInstalled(appId: Int) {
        runCatching {
            SteamPrefs.setInstalledLanguage(appId, selectedPack(appId))
            SteamPrefs.setLanguageSwitchPending(appId, false)
        }
    }

    /** [appId] was uninstalled. */
    @JvmStatic
    fun clearInstalled(appId: Int) {
        runCatching { SteamPrefs.clearInstalledLanguage(appId) }
    }

    /** The pack the installed copy has; English for an install made before packs were recorded. */
    private fun installedPack(appId: Int): String =
        runCatching { SteamPrefs.getInstalledLanguage(appId) }.getOrDefault("").ifEmpty { ENGLISH }

    // ── Language files of an installed game ──────────────────────────────────

    /**
     * The language pack an installed [appId] wants but does not have (its pick, or the app's language, asks
     * for other files than the installed ones), or null. Known once a library sync saved the game's packs.
     */
    @JvmStatic
    fun missingPack(appId: Int, installed: Boolean): String? {
        if (!installed || !knowsPacks(appId)) return null
        val want = packOf(of(appId), packaged(appId))
        return if (want == installedPack(appId)) null else want
    }

    /** True while a language download of [appId] has started and not finished. */
    @JvmStatic
    fun isSwitchPending(appId: Int): Boolean =
        runCatching { SteamPrefs.isLanguageSwitchPending(appId) }.getOrDefault(false)

    /** Ends an unfinished language download: the selection goes back to the installed pack. True when one was pending. */
    @JvmStatic
    fun abandonSwitch(appId: Int): Boolean {
        if (!isSwitchPending(appId)) return false
        runCatching { SteamPrefs.setLanguageSwitchPending(appId, false) }
        return true
    }

    /**
     * WN_STEAM_DEPOTS for a real Steam launch: [savedCsv] (depot:manifest:size from the database), except after
     * a language download that did not finish (app closed during it): the saved list then names a pack that
     * may not be on disk, so the downloader's own record of the installed depots is used (sizes from
     * [savedCsv] where known). Also ends that switch, so the next sync saves the installed pack again.
     */
    @JvmStatic
    fun depotsForLaunch(appId: Int, installDir: String?, savedCsv: String?): String {
        val saved = savedCsv.orEmpty()
        if (SteamDepotDownloader.isDownloading(appId) || !abandonSwitch(appId)) return saved
        val dir = installDir?.takeIf { it.isNotEmpty() } ?: return saved
        val journal = runCatching { BlDepotInstaller.journalInstalledManifests(java.io.File(dir)) }.getOrDefault(emptyMap())
        if (journal.isEmpty()) return saved
        val sizes = HashMap<Int, Long>()
        for (entry in saved.split(',')) {
            val p = entry.split(':')
            val depot = p.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            sizes[depot] = p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
        }
        return journal.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}:${sizes[it.key] ?: 0L}" }
    }

    // ── What the game is told ────────────────────────────────────────────────

    /**
     * The language for the Steam emulator (Goldberg): the wanted one, except when it is a pack of its own
     * that the installed copy does not have - then the installed pack's language.
     */
    @JvmStatic
    fun runtime(appId: Int): String {
        val want = of(appId)
        if (want !in packaged(appId)) return want
        val installed = installedPack(appId)
        return if (installed == want) want else installed
    }

    /**
     * The language for a real Steam client (appmanifest, SteamLite agent). The client keeps only the depots
     * of that language and deletes the rest of the install, so the wanted language is given only to a game
     * known to have no language-tagged depot at all; any other game gets the language of its installed pack.
     */
    @JvmStatic
    fun forSteamClient(appId: Int): String {
        if (knowsPacks(appId) && packaged(appId).isEmpty() && supported(appId).isNotEmpty()) return of(appId)
        return installedPack(appId)
    }
}
