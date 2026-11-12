package com.winlator.star.ui.simple

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.container.Shortcut
import com.winlator.star.store.GogCloudSavePaths
import com.winlator.star.store.GogLibraryRepo
import com.winlator.star.store.SteamDatabase
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.StoreLang
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The games' names in the app's language, for show only: a game's files, folders and saved settings keep
 * its own name. A game gets its store's translated name when the store has one for the app's language
 * and the game still carries the store's English name (a name the user typed is left alone).
 * Steam: the library's saved names, else the store page (also for games matched to a Steam app for art).
 * Xbox: Microsoft's catalog. GOG: the synced library. Found names are remembered per language.
 */
internal object LabTitles {
    private const val PREFS = "lab_titles"
    private val shown = ConcurrentHashMap<String, String>()
    private val busy = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO)

    var tick by mutableIntStateOf(0)
        private set

    /** The name to show for [s]. */
    fun of(s: Shortcut): String {
        tick
        return shown[s.file.path] ?: s.name
    }

    private class Pair2(val english: String, val shown: String)

    /** Looks up the names of [shortcuts] off the main thread; the screens redraw when one changes. */
    fun refresh(context: Context, shortcuts: List<Shortcut>) {
        val app = context.applicationContext
        val steam = StoreLang.steam()
        val xbox = StoreLang.xbox()
        val gog = StoreLang.gog()
        if (steam == "english" && xbox.startsWith("en") && gog == "en-US") {
            if (shown.isNotEmpty()) { shown.clear(); tick++ }
            return
        }
        if (shortcuts.isEmpty() || !busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val xboxIds = shortcuts.mapNotNull { xboxId(it) }.distinct()
                if (!xbox.startsWith("en")) fetchXbox(prefs, xboxIds, xbox)
                var changed = false
                for (s in shortcuts) {
                    val pair = runCatching {
                        val x = xboxId(s)
                        when {
                            x != null -> if (xbox.startsWith("en")) null else saved(prefs, "xbox:$x:$xbox")
                            else -> steamPair(app, prefs, s, steam) ?: gogPair(app, s, gog)
                        }
                    }.getOrNull()
                    val title = pair?.takeIf { it.shown.isNotBlank() && it.shown != it.english && same(s.name, it.english) }?.shown
                    val path = s.file.path
                    if (title == null) { if (shown.remove(path) != null) changed = true }
                    else if (shown.put(path, title) != title) changed = true
                }
                if (changed) withContext(Dispatchers.Main) { tick++ }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun xboxId(s: Shortcut): String? =
        s.getExtra("xboxProductId", "").uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z0-9]{12}")) }

    /** Letters and digits only, so the file-safe name of a shortcut still equals the store's. */
    private fun same(a: String, b: String): Boolean {
        fun key(t: String) = t.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val k = key(a)
        return k.isNotEmpty() && k == key(b)
    }

    private fun saved(prefs: android.content.SharedPreferences, key: String): Pair2? {
        val v = prefs.getString(key, null) ?: return null
        val i = v.indexOf('\u0000')
        return if (i < 0) null else Pair2(v.substring(0, i), v.substring(i + 1))
    }

    private fun save(prefs: android.content.SharedPreferences, key: String, english: String, title: String) {
        prefs.edit().putString(key, english + '\u0000' + title).apply()
    }

    private suspend fun steamPair(app: Context, prefs: android.content.SharedPreferences, s: Shortcut, lang: String): Pair2? {
        if (lang == "english") return null
        val id = LabArt.appId(s) ?: return null
        runCatching { SteamDatabase.getInstance(app).namePair(id) }.getOrNull()?.let { row ->
            val title = StoreLang.steamName(row[1], row[0])
            if (title != row[0]) return Pair2(row[0], title)
        }
        val key = "steam:$id:$lang"
        saved(prefs, key)?.let { return it }
        val english = steamStoreName(id, "english") ?: return null
        delay(250)
        val title = steamStoreName(id, lang) ?: return null
        delay(250)
        save(prefs, key, english, title)
        return Pair2(english, title)
    }

    private fun steamStoreName(id: Int, lang: String): String? {
        val body = SteamStoreSearch.httpGet("https://store.steampowered.com/api/appdetails?appids=$id&filters=basic&l=$lang") ?: return null
        return runCatching {
            JSONObject(body).optJSONObject(id.toString())?.optJSONObject("data")?.optString("name", "")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun fetchXbox(prefs: android.content.SharedPreferences, ids: List<String>, lang: String) {
        val need = ids.filter { !prefs.contains("xbox:$it:$lang") }
        for (batch in need.chunked(20)) {
            val english = xboxTitles(batch, "en-us") ?: continue
            val titles = xboxTitles(batch, "$lang,en-us") ?: continue
            for (id in batch) {
                val e = english[id] ?: continue
                save(prefs, "xbox:$id:$lang", e, titles[id] ?: e)
            }
        }
    }

    private fun xboxTitles(ids: List<String>, languages: String): Map<String, String>? {
        val market = Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        val body = SteamStoreSearch.httpGet(
            "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${ids.joinToString(",")}&market=$market&languages=$languages,neutral",
        ) ?: return null
        return runCatching {
            val out = HashMap<String, String>()
            val products = JSONObject(body).optJSONArray("Products") ?: return@runCatching out
            for (i in 0 until products.length()) {
                val p = products.optJSONObject(i) ?: continue
                val title = p.optJSONArray("LocalizedProperties")?.optJSONObject(0)?.optString("ProductTitle", "").orEmpty()
                if (title.isNotBlank()) out[p.optString("ProductId").uppercase(Locale.ROOT)] = title
            }
            out
        }.getOrNull()
    }

    private fun gogPair(app: Context, s: Shortcut, locale: String): Pair2? {
        if (locale == "en-US") return null
        val id = GogCloudSavePaths.gameIdForExecPath(app, s.path) ?: return null
        val english = GogLibraryRepo.prefs(app).getString("gog_title_en_$id", null)?.takeIf { it.isNotEmpty() } ?: return null
        val title = GogLibraryRepo.cached(app).firstOrNull { it.gameId == id }?.title ?: return null
        return Pair2(english, title)
    }
}
