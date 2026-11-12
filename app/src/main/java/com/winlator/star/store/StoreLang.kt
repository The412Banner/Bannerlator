package com.winlator.star.store

import com.winlator.star.ui.simple.LabLang
import java.util.Locale
import org.json.JSONObject

/**
 * The language the stores are asked in: the app's language (Lab's pick, else the phone's). Each store
 * has its own codes; a language a store does not know is asked in English. Only what is shown is
 * translated: install folders and art look-ups keep the stores' English names.
 */
object StoreLang {
    private fun locale(): Locale = LabLang.current

    private fun lang(l: Locale): String = when (val c = l.language.lowercase(Locale.ROOT)) {
        "id" -> "in"
        "no" -> "nb"
        else -> c
    }

    private fun traditionalChinese(l: Locale): Boolean =
        l.script.equals("Hant", true) || (l.script.isEmpty() && l.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO"))

    /** Steam's API language name ("english", "schinese", ...). */
    fun steam(l: Locale = locale()): String = when (lang(l)) {
        "ar" -> "arabic"
        "bg" -> "bulgarian"
        "cs" -> "czech"
        "da" -> "danish"
        "de" -> "german"
        "el" -> "greek"
        "es" -> if (l.country.isEmpty() || l.country.equals("ES", true)) "spanish" else "latam"
        "fi" -> "finnish"
        "fr" -> "french"
        "hu" -> "hungarian"
        "in" -> "indonesian"
        "it" -> "italian"
        "ja" -> "japanese"
        "ko" -> "koreana"
        "nb" -> "norwegian"
        "nl" -> "dutch"
        "pl" -> "polish"
        "pt" -> if (l.country.equals("PT", true)) "portuguese" else "brazilian"
        "ro" -> "romanian"
        "ru" -> "russian"
        "sv" -> "swedish"
        "th" -> "thai"
        "tr" -> "turkish"
        "uk" -> "ukrainian"
        "vi" -> "vietnamese"
        "zh" -> if (traditionalChinese(l)) "tchinese" else "schinese"
        else -> "english"
    }

    /** GOG's locale. Its catalog answers with an empty list for locales it does not know, so only tested ones. */
    fun gog(l: Locale = locale()): String = when (lang(l)) {
        "de" -> "de-DE"
        "es" -> "es-ES"
        "fr" -> "fr-FR"
        "ja" -> "ja-JP"
        "pl" -> "pl-PL"
        "ru" -> "ru-RU"
        "zh" -> "zh-Hans"
        else -> "en-US"
    }

    /** Microsoft's language tag, lower case ("ja", "zh-tw"); its catalog falls back by itself. */
    fun xbox(l: Locale = locale()): String {
        val c = lang(l).let { if (it == "in") "id" else it }
        if (c.isEmpty() || c == "und") return "en-us"
        val region = if (c == "es" && l.country == "419") "419" else l.country.takeIf { it.matches(Regex("[A-Za-z]{2}")) }
        return (if (region.isNullOrEmpty()) c else "$c-$region").lowercase(Locale.ROOT)
    }

    /**
     * [xbox] with a region always ("ja-jp", "de-de"): the Microsoft Store's search and featured lists answer
     * HTTP 403 to a bare language. A language without a usual region is asked in English.
     */
    fun xboxStore(l: Locale = locale()): String {
        val tag = xbox(l)
        if ('-' in tag) return if (tag == "es-419") "es-mx" else tag
        val region = mapOf(
            "ar" to "sa", "bg" to "bg", "cs" to "cz", "da" to "dk", "de" to "de", "el" to "gr", "en" to "us", "es" to "es",
            "fi" to "fi", "fr" to "fr", "hi" to "in", "hu" to "hu", "id" to "id", "it" to "it", "ja" to "jp", "ko" to "kr",
            "ms" to "my", "nb" to "no", "nl" to "nl", "pl" to "pl", "pt" to "br", "ro" to "ro", "ru" to "ru", "sv" to "se",
            "th" to "th", "tr" to "tr", "uk" to "ua", "vi" to "vn", "zh" to "cn",
        )[tag] ?: return "en-us"
        return "$tag-$region"
    }

    /** The name in Steam's `common/name_localized` (saved as a JSON object) for the app's language, else [name]. */
    @JvmStatic
    fun steamName(localized: String?, name: String?): String {
        val plain = name ?: ""
        if (localized.isNullOrEmpty()) return plain
        val want = steam()
        if (want == "english") return plain
        return runCatching { JSONObject(localized).optString(want, "") }.getOrDefault("").ifBlank { plain }
    }
}
