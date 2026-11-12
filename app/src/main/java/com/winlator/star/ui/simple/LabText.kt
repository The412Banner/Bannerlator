package com.winlator.star.ui.simple

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.preference.PreferenceManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Lab's own language: the language picked in Lab's Appearance page (or the phone's), for Lab's text.
 *
 * Lab's text stays English in the code, wrapped in [L]. Each English string has a resource named after it
 * ("lab_" + its CRC-32 in hex, see [keyOf]) in res/values/strings_lab.xml, written by tools/i18n/lab_strings.py from
 * the [L] calls; each language has its own res/values-xx/strings_lab.xml. [L] looks the text up in the picked
 * language and falls back to the English when a string has no translation yet. The lookup goes through Resources of
 * our own (not the activity's), so it works the same on every Android version and in every activity, including the
 * game's.
 */
object LabLang {
    const val PREF = "lab_language"

    /** Every language Lab can show (tag, name in that language); "" = the phone's language. Matches DroidDeck's. */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "" to "System default",
        "en" to "English",
        "ar" to "العربية",
        "bg" to "Български",
        "cs" to "Čeština",
        "da" to "Dansk",
        "de" to "Deutsch",
        "el" to "Ελληνικά",
        "es" to "Español",
        "es-419" to "Español (Latinoamérica)",
        "fi" to "Suomi",
        "fr" to "Français",
        "hi" to "हिन्दी",
        "hu" to "Magyar",
        "in" to "Bahasa Indonesia",
        "it" to "Italiano",
        "ja" to "日本語",
        "ko" to "한국어",
        "ms" to "Bahasa Melayu",
        "nb" to "Norsk bokmål",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "pt" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "ro" to "Română",
        "ru" to "Русский",
        "sv" to "Svenska",
        "th" to "ไทย",
        "tr" to "Türkçe",
        "uk" to "Українська",
        "vi" to "Tiếng Việt",
        "zh-CN" to "简体中文",
        "zh-HK" to "繁體中文（香港）",
        "zh-TW" to "繁體中文（台灣）",
    )

    @Volatile private var res: Resources? = null
    @Volatile private var pkg: String = ""
    @Volatile private var locale: Locale = Locale.getDefault()
    private val ids = ConcurrentHashMap<String, Int>()

    /** The language in use: the picked one, else the phone's. */
    val current: Locale
        get() = locale

    /** The picked language's tag; "" = the phone's. */
    fun tag(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, "") ?: ""

    /** The picked language's name as the list shows it. */
    fun name(context: Context): String = LANGUAGES.firstOrNull { it.first == tag(context) }?.second ?: LANGUAGES.first().second

    /** Reads the picked language; the app calls it once at start, [set] after a change. */
    fun init(context: Context) {
        val app = context.applicationContext
        pkg = app.packageName
        val t = tag(app)
        if (t.isEmpty()) {
            locale = Locale.getDefault()
            res = app.resources
        } else {
            locale = Locale.forLanguageTag(t)
            val conf = Configuration(app.resources.configuration)
            conf.setLocale(locale)
            conf.setLayoutDirection(locale)
            res = app.createConfigurationContext(conf).resources
        }
        ids.clear()
    }

    /**
     * Picks a language ("" = the phone's) and restarts the app in it: lists built once at start (the sections, the
     * settings) are made again in the new language, not only the screen on show.
     */
    fun set(activity: Activity, tag: String) {
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(PREF, tag).commit()
        val start = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (start == null) {
            init(activity)
            activity.recreate()
            return
        }
        activity.startActivity(start)
        activity.finishAffinity()
        // The new task starts in a fresh process once this one is gone.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 150)
    }

    /** Right-to-left (Arabic): Lab's screens lay out from the right. */
    val isRtl: Boolean
        get() = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL

    /** The resource name of an English string. tools/i18n/lab_strings.py makes the same names (zlib.crc32). */
    fun keyOf(english: String): String {
        val c = CRC32()
        c.update(english.toByteArray(Charsets.UTF_8))
        return "lab_" + java.lang.Long.toHexString(c.value).padStart(8, '0')
    }

    @SuppressLint("DiscouragedApi")
    internal fun lookup(english: String, args: Array<out Any?>): String {
        val r = res ?: return if (args.isEmpty()) english else runCatching { String.format(english, *args) }.getOrDefault(english)
        val id = ids.getOrPut(english) { r.getIdentifier(keyOf(english), "string", pkg) }
        if (id == 0) return if (args.isEmpty()) english else runCatching { String.format(locale, english, *args) }.getOrDefault(english)
        return runCatching { if (args.isEmpty()) r.getString(id) else r.getString(id, *args) }.getOrDefault(english)
    }
}

/**
 * Lab text in the picked language. [english] is the English text, with %1$s, %2$d … where [args] go
 * ("Removed %1$s", name). Missing translations show the English.
 */
fun L(english: String, vararg args: Any?): String = LabLang.lookup(english, args)

/**
 * Marks English that is kept as English where it is defined (a list built once, a setting's label) and translated
 * where it is drawn, with L(text). tools/i18n/lab_strings.py collects T("...") as well as L("...").
 */
fun T(english: String): String = english

/** Lab's screens laid out in the picked language's direction (right to left for Arabic). */
@Composable
fun LabDirection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides if (LabLang.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
}
