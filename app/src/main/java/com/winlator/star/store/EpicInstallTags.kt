package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.container.ContainerManager
import java.util.Locale

/**
 * Resolves the Epic manifest "install tags" to download for a game, based on the container's
 * language, so an install pulls required(base) files + the user's language only — instead of every
 * language + all optional data (Feature #2).
 *
 * The language→tags map and the required+language selection strategy are ported from GameNative's
 * service/epic/EpicConstants.kt (CONTAINER_LANGUAGE_TO_EPIC_INSTALL_TAGS), which is derived from
 * Legendary. GameNative and Legendary are GPL-3.0; this ported table carries that attribution.
 * Credits: The GameNative Team — https://github.com/utkarshdalal/GameNative
 *
 * How the language is derived (v1, no new UI):
 *   Epic downloads in this app are container-agnostic — the container is only chosen later, at
 *   launch (StarLaunchBridge). So there is no single "this game's container" at download time. We
 *   therefore derive language from the FIRST Wine container that has a non-empty [lc_all] (the
 *   common single-container case), then fall back to the Android device locale, then to English.
 *   We never return an empty list from [tagsForCurrentContainer] — like Legendary/GameNative, an
 *   unknown language falls back to English tags so the game is still runnable. (A per-install
 *   language / component picker is a deliberate later follow-up.)
 */
object EpicInstallTags {

    private const val TAG = "EpicInstallTags"

    /** Container language name → Epic install-tag names/codes (Legendary/GameNative table). */
    private val LANGUAGE_TO_TAGS: Map<String, List<String>> = mapOf(
        "arabic" to listOf("Arabic", "ar"),
        "bulgarian" to listOf("Bulgarian", "bg-BG", "bg"),
        "schinese" to listOf("Chinese", "ChineseSimplified", "zh-Hans", "zh_Hans", "zh"),
        "tchinese" to listOf("ChineseTraditional", "zh-Hant", "zh_Hant"),
        "czech" to listOf("Czech", "cs-CZ", "cs"),
        "danish" to listOf("Danish", "da-DK", "da"),
        "dutch" to listOf("Dutch", "nl-NL", "nl"),
        "english" to listOf("English", "en-US", "en"),
        "finnish" to listOf("Finnish", "fi-FI", "fi"),
        "french" to listOf("French", "fr-FR", "fr"),
        "german" to listOf("German", "de-DE", "de"),
        "greek" to listOf("Greek", "el-GR", "el"),
        "hungarian" to listOf("Hungarian", "hu-HU", "hu"),
        "italian" to listOf("Italian", "it-IT", "it"),
        "japanese" to listOf("Japanese", "ja-JP", "ja"),
        "koreana" to listOf("Korean", "ko-KR", "ko"),
        "norwegian" to listOf("Norwegian", "nb-NO", "no"),
        "polish" to listOf("Polish", "pl-PL", "pl"),
        "portuguese" to listOf("Portuguese", "pt-PT", "pt"),
        "brazilian" to listOf("PortugueseBrazilian", "Brazilian", "pt-BR", "br"),
        "romanian" to listOf("Romanian", "ro-RO", "ro"),
        "russian" to listOf("Russian", "ru-RU", "ru"),
        "spanish" to listOf("Spanish", "es-ES", "es"),
        "latam" to listOf("SpanishLatinAmerica", "Latam", "es-MX", "es_mx"),
        "swedish" to listOf("Swedish", "sv-SE", "sv"),
        "thai" to listOf("Thai", "th-TH", "th"),
        "turkish" to listOf("Turkish", "tr-TR", "tr"),
        "ukrainian" to listOf("Ukrainian", "uk-UA", "uk"),
        "vietnamese" to listOf("Vietnamese", "vi-VN", "vi"),
    )

    private const val FALLBACK = "english"

    /**
     * Map an ISO-639 language code (+ optional region, e.g. from an lc_all like "de_DE" or "zh_TW")
     * to the container-language key used in [LANGUAGE_TO_TAGS]. Region only matters where Epic ships
     * distinct tag sets (zh Simplified/Traditional, pt Portugal/Brazil, es Spain/LatAm).
     */
    private fun keyForLangRegion(lang: String, region: String): String? {
        val l = lang.lowercase(Locale.ROOT)
        val r = region.uppercase(Locale.ROOT)
        return when (l) {
            "ar" -> "arabic"
            "bg" -> "bulgarian"
            "zh" -> if (r == "TW" || r == "HK" || r == "MO") "tchinese" else "schinese"
            "cs" -> "czech"
            "da" -> "danish"
            "nl" -> "dutch"
            "en" -> "english"
            "fi" -> "finnish"
            "fr" -> "french"
            "de" -> "german"
            "el" -> "greek"
            "hu" -> "hungarian"
            "it" -> "italian"
            "ja" -> "japanese"
            "ko" -> "koreana"
            "nb", "nn", "no" -> "norwegian"
            "pl" -> "polish"
            "pt" -> if (r == "BR") "brazilian" else "portuguese"
            "ro" -> "romanian"
            "ru" -> "russian"
            "es" -> if (r == "ES") "spanish" else "latam" // MX/AR/CL/... → LatAm
            "sv" -> "swedish"
            "th" -> "thai"
            "tr" -> "turkish"
            "uk" -> "ukrainian"
            "vi" -> "vietnamese"
            else -> null
        }
    }

    /**
     * Tags for an lc_all / locale string like "de_DE", "en_US.UTF-8", "zh_TW", or "pt-BR".
     * Returns null when the string is blank or unrecognized (so callers can fall through). Never
     * throws.
     */
    fun tagsForLocale(lcAll: String?): List<String>? {
        if (lcAll.isNullOrBlank()) return null
        // Strip charset ("en_US.UTF-8" → "en_US"), then split lang/region on _ or -.
        val base = lcAll.substringBefore('.').trim()
        val parts = base.split('_', '-')
        val lang = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val region = parts.getOrNull(1) ?: ""
        val key = keyForLangRegion(lang, region) ?: return null
        return LANGUAGE_TO_TAGS[key]
    }

    /**
     * The install tags to use for a base-game download right now. Derivation order:
     *   1. First Wine container with a non-empty lc_all → its language.
     *   2. Android device default locale.
     *   3. English (never empty), so an unrecognized language still yields a runnable install.
     * Runs a light disk walk (ContainerManager) — call it off the main thread.
     */
    fun tagsForCurrentContainer(context: Context): List<String> {
        // 1. container lc_all
        try {
            val containers = ContainerManager(context).containers
            for (c in containers) {
                val lcAll = c.getLC_ALL()
                val tags = tagsForLocale(lcAll)
                if (tags != null) {
                    Log.i(TAG, "Epic install language from container ${c.id} lc_all='$lcAll' -> $tags")
                    return tags
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read container lc_all: ${t.message}")
        }
        // 2. device locale
        tagsForLocale(deviceLocaleTag())?.let {
            Log.i(TAG, "Epic install language from device locale -> $it")
            return it
        }
        // 3. English fallback (Legendary/GameNative parity)
        Log.i(TAG, "Epic install language falling back to English")
        return LANGUAGE_TO_TAGS.getValue(FALLBACK)
    }

    private fun deviceLocaleTag(): String {
        val loc = Locale.getDefault()
        val country = loc.country
        return if (country.isNotEmpty()) "${loc.language}_$country" else loc.language
    }

    /**
     * The 2-letter ISO-639 language code to hand Epic as {@code -epiclocale} for a launch, derived
     * the same way as [tagsForCurrentContainer]:
     *   1. First Wine container with a non-empty lc_all → its language part.
     *   2. Android device default locale language.
     *   3. "en" (never empty).
     * Only recognized languages (see [keyForLangRegion]) are accepted; anything else falls through.
     * Runs a light disk walk (ContainerManager) — call it off the main thread.
     */
    fun localeCodeForCurrentContainer(context: Context): String {
        try {
            for (c in ContainerManager(context).containers) {
                val code = localeCodeForLcAll(c.getLC_ALL())
                if (code != null) {
                    Log.i(TAG, "Epic launch locale from container ${c.id} -> $code")
                    return code
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read container lc_all for locale: ${t.message}")
        }
        localeCodeForLcAll(deviceLocaleTag())?.let {
            Log.i(TAG, "Epic launch locale from device locale -> $it")
            return it
        }
        Log.i(TAG, "Epic launch locale falling back to en")
        return "en"
    }

    /**
     * Extract a recognized 2-letter language code from an lc_all / locale string (e.g. "de_DE" ->
     * "de", "en_US.UTF-8" -> "en"). Returns null when blank, malformed, or an unrecognized language.
     */
    private fun localeCodeForLcAll(lcAll: String?): String? {
        if (lcAll.isNullOrBlank()) return null
        val base = lcAll.substringBefore('.').trim()
        val lang = base.split('_', '-').getOrNull(0)?.lowercase(Locale.ROOT)
            ?.takeIf { it.length == 2 } ?: return null
        // Validate against the languages we actually know (region irrelevant for the 2-letter code).
        return if (keyForLangRegion(lang, "") != null) lang else null
    }
}
