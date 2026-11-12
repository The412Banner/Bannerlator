package com.winlator.star.store

import java.util.Locale

/**
 * The language a Steam game downloads and runs in. By default the app's language when the game has it,
 * else English; the game's page can pick another one (kept per game in [SteamPrefs], like the branch).
 * It decides which per-language depots are downloaded (library sync / install / update) and what the
 * Steam emulator and the appmanifest report to the game.
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

    private fun saved(appId: Int): List<String> =
        runCatching { SteamDatabase.getInstance().getLanguages(appId) }.getOrDefault("").split('|')

    /** The languages Steam lists for [appId] (saved at the last library sync); empty when not known yet. */
    @JvmStatic
    fun supported(appId: Int): List<String> = parse(saved(appId).getOrNull(0))

    /** The languages [appId] ships as a separate download (a depot tagged with the language). */
    @JvmStatic
    fun packaged(appId: Int): List<String> = parse(saved(appId).getOrNull(1))

    /** What is saved for an app: its languages, then the separately packaged ones. */
    @JvmStatic
    fun encode(supported: List<String>, packaged: List<String>): String =
        supported.joinToString(",") + "|" + packaged.joinToString(",")

    /** The language pack a download in [language] takes: its own when the game packages one, else English. */
    @JvmStatic
    fun packOf(language: String, packaged: List<String>): String = if (language in packaged) language else ENGLISH

    /**
     * The language the game is told at launch (emulator, appmanifest, SteamLite agent). [of], except when
     * that language is a separate download the installed game does not have yet (picked after the install):
     * then the installed one, so the game and the Steam client never see a language whose files are missing.
     */
    @JvmStatic
    fun runtime(appId: Int): String {
        val want = of(appId)
        if (want !in packaged(appId)) return want
        val installed = runCatching { SteamPrefs.getInstalledLanguage(appId) }.getOrDefault("").ifEmpty { ENGLISH }
        return if (installed == want) want else installed
    }

    /** Called when an install, update or verify of [appId] finished: remembers the language pack it took. */
    @JvmStatic
    fun recordInstalled(appId: Int) {
        runCatching { SteamPrefs.setInstalledLanguage(appId, packOf(of(appId), packaged(appId))) }
    }

    @JvmStatic
    fun parse(csv: String?): List<String> =
        csv.orEmpty().split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()

    /** The user's pick for [appId]; "" = follow the app's language. */
    @JvmStatic
    fun picked(appId: Int): String = runCatching { SteamPrefs.getGameLanguage(appId) }.getOrDefault("")

    /** The language [appId] downloads and runs in. */
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
}
