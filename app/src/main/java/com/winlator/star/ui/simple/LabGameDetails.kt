package com.winlator.star.ui.simple

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.GameDetails
import com.winlator.star.container.Shortcut
import com.winlator.star.store.SteamLibraryArt
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.download.MediaVideo
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.CustomCover
import com.winlator.star.ui.screens.ExeShortcutImporter
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.applySteamCover
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A shortcut by its .desktop path, from any container (and the Linux / Android homes), or null. */
internal fun loadLabShortcut(context: Context, path: String): Shortcut? = runCatching {
    val manager = ContainerManager(context)
    (manager.containers + listOfNotNull(runCatching { manager.linuxContainer }.getOrNull(), runCatching { manager.androidGamesContainer }.getOrNull())).firstNotNullOfOrNull { c ->
        val file = File(path)
        if (file.isFile && file.parentFile?.canonicalPath == c.desktopDir.canonicalPath) Shortcut(c, file) else null
    }
}.getOrNull()

/** The picture slots of a game (DroidDeck's Artwork: cover, wide cover, background, logo, icon). */
internal val LAB_SLOTS = listOf("cover" to T("Cover"), "wide" to T("Wide cover"), "bg" to T("Background"), "logo" to T("Logo"), "icon" to T("Icon"))

/** Adjust logo's spots (Steam's names); "" = Default, the spot Steam publishes for the game. */
internal val LAB_LOGO_POS = listOf("" to T("Default"), "UpperLeft" to T("Top left"), "UpperCenter" to T("Top center"), "CenterCenter" to T("Center"), "BottomLeft" to T("Bottom left"), "BottomCenter" to T("Bottom center"))

/** The spot the logo sits in over the art: the game's pick, else Steam's published one, else bottom left. */
internal fun labLogoPin(s: Shortcut): String = s.getExtra(LabArt.K_LOGO_POS, "").ifBlank {
    LabArt.appId(s)?.let { SteamLibraryArt.of(it)?.logoPosition?.pinnedPosition } ?: "BottomLeft"
}

/** The logo over the art in a box (Home's big tile with previews, Adjust logo's preview), at its spot and size. */
@Composable
internal fun BoxScope.LabHeroLogo(s: Shortcut, pos: String? = null, size: String? = null) {
    val tick = LabArt.tick
    val pics = remember(s.file.path, tick) { LabArt.models(s, "logo").map { LabArt.Pic(it) } }
    // pos: null = the game's saved spot, "" = Default (Steam's published spot), else that spot.
    val saved = pos ?: s.getExtra(LabArt.K_LOGO_POS, "")
    val pin = saved.ifBlank { LabArt.appId(s)?.let { SteamLibraryArt.of(it)?.logoPosition?.pinnedPosition } ?: "BottomLeft" }
    val scale = when (size ?: s.getExtra(LabArt.K_LOGO_SIZE, "")) { "s" -> 0.75f; "l" -> 1.3f; else -> 1f }
    val align = when (pin) {
        "UpperLeft" -> Alignment.TopStart
        "UpperCenter" -> Alignment.TopCenter
        "CenterCenter" -> Alignment.Center
        "BottomCenter" -> Alignment.BottomCenter
        else -> Alignment.BottomStart
    }
    val name: @Composable () -> Unit = {
        Text(s.name, color = Color.White, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(align))
    }
    if (pics.isEmpty()) { name(); return }
    LabPicture(
        pics,
        Modifier.align(align).fillMaxWidth((0.45f * scale).coerceAtMost(0.9f)).fillMaxHeight(),
        contentScale = ContentScale.Fit,
        alignment = align,
        onNone = name,
    )
}

/** One picture to choose: a URL (or file path) and a small one to show. */
private class ArtChoice(val url: String, val thumb: String, val group: String, val label: String? = null)

private const val SGDB_BUILTIN_KEY = "cf89227f12c773bb1117b6b109ae1659"

private fun httpText(url: String, bearer: String? = null): String? = runCatching {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 15_000
    c.setRequestProperty("User-Agent", "Bannerlator")
    bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
    try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
}.getOrNull()

/** SteamGridDB's pictures for [slot] (the key from Settings › Games & library, else the app's own). BLOCKING. */
private fun sgdbChoices(context: Context, s: Shortcut, slot: String): List<ArtChoice> {
    val p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    val key = if (p.getBoolean("enable_custom_api_key", false)) p.getString("custom_api_key", "").orEmpty().ifBlank { SGDB_BUILTIN_KEY } else SGDB_BUILTIN_KEY
    val base = "https://www.steamgriddb.com/api/v2"
    val gameId = LabArt.appId(s)?.let { id ->
        httpText("$base/games/steam/$id", key)?.let { runCatching { JSONObject(it).optJSONObject("data")?.optInt("id", 0) }.getOrNull() }
    }?.takeIf { it > 0 } ?: httpText("$base/search/autocomplete/" + URLEncoder.encode(s.name, "UTF-8"), key)?.let {
        runCatching { JSONObject(it).optJSONArray("data")?.optJSONObject(0)?.optInt("id", 0) }.getOrNull()
    }?.takeIf { it > 0 } ?: return emptyList()
    val path = when (slot) {
        "cover" -> "grids/game/$gameId?dimensions=600x900"
        "wide" -> "grids/game/$gameId?dimensions=460x215,920x430"
        "bg" -> "heroes/game/$gameId"
        "logo" -> "logos/game/$gameId"
        "icon" -> "icons/game/$gameId"
        else -> return emptyList()
    }
    val body = httpText("$base/$path", key) ?: return emptyList()
    return runCatching {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), 12)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArtChoice(url, o.optString("thumb").ifBlank { url }, "SteamGridDB")
        }
    }.getOrDefault(emptyList())
}

/**
 * An answer from [url]: the body of a 2xx, null when the server says there is nothing there (404 and the like).
 * Throws when there is no answer: no network, a timeout, a refused key, rate limiting, a server error.
 */
private fun httpAnswer(url: String, bearer: String? = null): String? {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 15_000
    c.setRequestProperty("User-Agent", "Bannerlator")
    bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
    try {
        val code = c.responseCode
        if (code in 200..299) return c.inputStream.bufferedReader().readText()
        if (code in 400..499 && code !in setOf(401, 403, 408, 429)) return null
        throw java.io.IOException("HTTP $code from $url")
    } finally { c.disconnect() }
}

/**
 * SteamGridDB's sharpest background for a game: by its Steam app when known, else a game titled like [name] (the
 * store's strict title match). 3840x1240 first, then any size; never the blurred style. Null when SteamGridDB has
 * none; throws when it could not be asked (so the miss is not remembered). BLOCKING.
 */
internal fun labSgdbHero(context: Context, steamId: Int?, name: String): String? {
    val p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    val key = if (p.getBoolean("enable_custom_api_key", false)) p.getString("custom_api_key", "").orEmpty().ifBlank { SGDB_BUILTIN_KEY } else SGDB_BUILTIN_KEY
    val base = "https://www.steamgriddb.com/api/v2"
    // A body that is not JSON (a Wi-Fi sign-in page) throws too.
    val bySteam = steamId?.let { sid ->
        httpAnswer("$base/games/steam/$sid", key)?.let { JSONObject(it).optJSONObject("data")?.optInt("id", 0) }
    }?.takeIf { it > 0 }
    val id = bySteam ?: run {
        val hits = httpAnswer("$base/search/autocomplete/" + URLEncoder.encode(name, "UTF-8"), key)?.let { JSONObject(it).optJSONArray("data") } ?: return null
        (0 until minOf(hits.length(), 5)).mapNotNull { hits.optJSONObject(it) }
            .firstOrNull { SteamStoreSearch.nameMatch(name, it.optString("name")) >= 2 }?.optInt("id", 0)?.takeIf { it > 0 }
    } ?: return null
    fun first(query: String): String? = httpAnswer("$base/heroes/game/$id$query", key)?.let { body ->
        JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
    }
    return first("?dimensions=3840x1240&styles=alternate,material") ?: first("?styles=alternate,material")
}

/** Steam's pictures for [slot] of the linked app. BLOCKING. */
private fun steamChoices(appId: Int, slot: String): List<ArtChoice> {
    val out = LinkedHashMap<String, ArtChoice>()
    fun add(url: String?, label: String? = null) { if (!url.isNullOrBlank() && url !in out) out[url] = ArtChoice(url, url, "Steam", label) }
    val assets = runCatching { SteamStoreSearch.libraryAssets(appId) }.getOrDefault(LinkedHashMap())
    when (slot) {
        "cover" -> { add(LabArt.steamUrl(appId, "cover")); add(assets["Cover"]); add(assets["Cover 2x"]); add(assets["Store cover"]) }
        "wide" -> { add(assets["Header"] ?: LabArt.steamUrl(appId, "wide")); add(assets["Capsule"]) }
        "bg" -> {
            add(assets["Hero"] ?: LabArt.steamUrl(appId, "bg")); add(assets["Hero 2x"])
            runCatching { SteamStoreSearch.fetchMedia(appId, "US") }.getOrNull()?.screenshots?.take(6)?.forEach { add(it.full, L("Screenshot")) }
        }
        "logo" -> add(LabArt.steamUrl(appId, "logo"))
    }
    return out.values.toList()
}

/** The game's Steam trailers (HLS), as the Preview clip choices; null when Steam could not be asked. BLOCKING. */
internal fun labSteamTrailers(appId: Int): List<MediaVideo.Direct>? =
    runCatching { SteamStoreSearch.fetchMedia(appId, "US") }.getOrNull()?.videos?.filterIsInstance<MediaVideo.Direct>()

/** A picture from the web in a file of the app's cache, for CustomCover to read. BLOCKING. */
private fun downloadToCache(context: Context, url: String): File? = runCatching {
    val out = File(context.cacheDir, "lab_cover_${System.currentTimeMillis()}.img")
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 20_000
    c.setRequestProperty("User-Agent", "Bannerlator")
    try {
        if (c.responseCode !in 200..299) return@runCatching null
        c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
    } finally { c.disconnect() }
    out
}.getOrNull()

/** The Steam / SteamGridDB picture (its URL) or file the cover was last picked from in Lab, for the chooser's outline and the Artwork row. */
private const val K_COVER_PICK = "labCoverPick"

/**
 * Makes [src] (a URL or a file) the game's cover the way Classic's "Add your own cover" does (CustomCover): cut to the
 * cover shape, written as the cover and the 64 px icon the XMB and the Classic list draw, and marked as the user's
 * (coverSource=user), so a later Steam link or Scrape keeps it. False when the picture can't be read. BLOCKING.
 */
private fun labPickCover(context: Context, s: Shortcut, src: String): Boolean {
    val web = src.startsWith("http")
    val file = if (web) downloadToCache(context, src) ?: return false else File(src)
    val before = s.getExtra(K_COVER_PICK, "").ifBlank { null }
    // Saved with the cover by CustomCover's own write.
    s.putExtra(K_COVER_PICK, src)
    return try {
        val ok = runCatching { CustomCover.apply(s, file) }.getOrNull() != null
        if (!ok) s.putExtra(K_COVER_PICK, before)
        ok
    } finally { if (web) file.delete() }
}

/**
 * Reset to automatic. A cover the user picked goes and what the game showed before comes back (Classic's Remove of a
 * custom cover). Any other cover is the automatic one already: a game linked to Steam gets Steam's again (as Classic's
 * Game Details "use Steam's art"), any other keeps it. BLOCKING.
 */
private fun labResetCover(context: Context, s: Shortcut): Boolean {
    if (CustomCover.isCustom(s)) {
        s.putExtra(K_COVER_PICK, null)
        return CustomCover.remove(context, s)
    }
    val id = s.getExtra("steamAppId", "").toIntOrNull()?.takeIf { it > 0 } ?: return true
    runCatching { applySteamCover(s.container, s.name, id) }
    return true
}

/** Copies a picked file into the app's own art folder, so the pick survives the original moving. */
private fun keepFile(context: Context, src: String, s: Shortcut, slot: String): String? = runCatching {
    val dir = File(context.filesDir, "lab_art").apply { mkdirs() }
    val ext = src.substringAfterLast('.', "img").take(5)
    val safe = s.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(40)
    val out = File(dir, "$safe-$slot-${System.currentTimeMillis()}.$ext")
    File(src).copyTo(out, overwrite = true)
    out.path
}.getOrNull()

private fun slotLabel(slot: String): String =
    if (LabArt.isIgSlot(slot)) L("In-game menu · %1\$s", slotLabel(LabArt.igBase(slot)))
    else L(LAB_SLOTS.firstOrNull { it.first == slot }?.second ?: slot)

/** Where a slot's picture comes from, as Artwork shows it. */
private fun slotSource(s: Shortcut, slot: String): String {
    val p = LabArt.pick(s, slot)
    if (LabArt.isIgSlot(slot)) return when {
        p == null -> L("Same as app pages")
        p == "none" -> if (slot == "iglogo") L("None · the name shows") else L("None")
        p.contains("steamgriddb", ignoreCase = true) -> "SteamGridDB"
        p.startsWith("http") -> "Steam"
        else -> L("Your file")
    }
    val steam = LabArt.appId(s) != null && slot != "icon"
    return when {
        slot == "cover" -> {
            // Only a cover marked as the user's (CustomCover) is a pick; one Steam or the store put there is automatic.
            val c = s.getExtra(K_COVER_PICK, "")
            when {
                !CustomCover.isCustom(s) -> L("Automatic")
                c.contains("steamgriddb", ignoreCase = true) -> "SteamGridDB"
                c.startsWith("http") -> "Steam"
                c.isNotBlank() -> L("Your file")
                else -> L("Your cover")
            }
        }
        p == "none" -> if (slot == "logo") L("None · the name shows") else L("None")
        p != null && p.contains("steamgriddb", ignoreCase = true) -> "SteamGridDB"
        p != null && p.startsWith("http") -> "Steam"
        p != null -> L("Your file")
        steam -> L("Automatic · Steam")
        slot == "logo" -> L("None found · the name shows")
        slot == "bg" -> L("Automatic · the cover, blurred")
        else -> L("Automatic · from the cover")
    }
}

/**
 * Lab's Game details: the game's details (name, Steam link, genres, year, Metacritic, description), its pictures
 * (cover, wide cover, background, logo, icon, preview clip, the logo's spot) and its own background choices.
 * Tabs: L2/R2. Every change is saved straight away.
 */
@Composable
internal fun LabGameDetailsPage(path: String, tab: Int, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var shortcut by remember(path) { mutableStateOf(loadLabShortcut(context, path)) }
    val s = shortcut ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(L("This game is no longer in your library."), color = SimpleMuted) }
        return
    }
    LaunchedEffect(s.file.path) { LabArt.selected = s }
    val tick = LabArt.tick
    var details by remember(s.file.path, tick) { mutableStateOf(GameDetails.from(s)) }
    val scope = rememberCoroutineScope()
    var textFor by remember { mutableStateOf<String?>(null) }
    var slotFor by remember { mutableStateOf<String?>(null) }
    var adjustOpen by remember { mutableStateOf(false) }
    var steamHits by remember { mutableStateOf<List<SteamStoreSearch.SteamSuggestion>?>(null) }

    fun saveDetails(d: GameDetails) {
        runCatching { d.writeTo(s) }
        details = d
        LabArt.changed()
    }
    fun rename(newName: String) {
        val base = newName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (base.isBlank() || base == s.name) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ExeShortcutImporter.renameShortcutFiles(s.container, s.name, base) }.getOrDefault(false) }
            if (!ok) { toast(context, L("Could not rename it")); return@launch }
            val file = File(s.container.desktopDir, "$base.desktop")
            shortcut = Shortcut(s.container, file)
            LabArt.selected = shortcut
            LabArt.changed()
            ShortcutsViewModel.notifyContainersChanged()
        }
    }
    fun linkSteam(appId: Int) {
        scope.launch {
            val before = details.steamAppId
            val info = withContext(Dispatchers.IO) { SteamStoreSearch.fetchDetails(appId) }
            saveDetails(
                GameDetails(
                    appId,
                    info?.genres?.takeIf { it.isNotEmpty() } ?: details.genres,
                    info?.shortDescription ?: details.description,
                    info?.releaseYear ?: details.releaseYear,
                    info?.metacritic ?: details.metacritic,
                ),
            )
            // Steam's cover, as Classic's Game Details saves a link: written after the details (it reads the game's
            // file again), and not over a cover the user picked unless it is a different Steam app now.
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    if (CustomCover.isCustom(s) && appId != before) { s.putExtra(CustomCover.EXTRA_SOURCE, null); s.saveData() }
                    applySteamCover(s.container, s.name, appId)
                }
                runCatching { Shortcut(s.container, s.file) }.getOrNull()
            }
            if (fresh != null) { shortcut = fresh; LabArt.selected = fresh }
            ShortcutsViewModel.notifyContainersChanged()
            LabArt.changed()
            toast(context, L("Linked to Steam app %1\$s", appId))
            // As Classic's Game Details does when a result is picked: the game takes Steam's title (its cover and icons move with it).
            info?.name?.takeIf { it.isNotBlank() }?.let { rename(it) }
        }
    }
    fun searchSteam(query: String) {
        val q = query.trim().ifBlank { return }
        scope.launch {
            val hits = withContext(Dispatchers.IO) { SteamStoreSearch.searchByName(q) }
            if (hits.isEmpty()) toast(context, L("Nothing found on Steam for %1\$s", q)) else steamHits = hits
        }
    }
    // A game linked to Steam with none of its details yet gets them from the store when the page opens (as Classic's
    // Game Details fills them in); the name stays.
    LaunchedEffect(s.file.path) {
        val d = GameDetails.from(s)
        val id = d.steamAppId ?: return@LaunchedEffect
        if (d.hasDisplayableDetails()) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { SteamStoreSearch.fetchDetails(id) } ?: return@LaunchedEffect
        saveDetails(GameDetails(id, info.genres, info.shortDescription, info.releaseYear, info.metacritic))
    }
    fun setGame(key: String, v: String?) = LabArt.putGame(s, key, v)

    val notSet = L("Not set")
    val detailsRows = listOf(
        MenuEntry("d-name", L("Name"), s.name, icon = Icons.Filled.TextFields, onA = { textFor = "name" }),
        MenuEntry(
            "d-steam", L("Steam"), details.steamAppId?.let { L("Steam app %1\$s", it) } ?: L("Not linked to Steam"), icon = Icons.Filled.Link,
            actions = listOfNotNull(
                MenuAction(L("Search Steam by name"), { textFor = "steam" }),
                details.steamAppId?.let { MenuAction(L("Unlink"), { saveDetails(details.copy(steamAppId = null)) }) },
            ),
        ),
        MenuEntry("d-genres", L("Genres"), details.genres.joinToString(", ").ifBlank { notSet }, icon = Icons.Filled.Tag, onA = { textFor = "genres" }),
        MenuEntry("d-year", L("Release year"), details.releaseYear ?: notSet, icon = Icons.Filled.CalendarMonth, onA = { textFor = "year" }),
        MenuEntry("d-meta", L("Metacritic"), details.metacritic?.toString() ?: notSet, icon = Icons.Filled.Star, onA = { textFor = "meta" }),
        MenuEntry("d-desc", L("Description"), details.description ?: notSet, icon = Icons.Filled.Description, onA = { textFor = "desc" }),
    )
    val clipPick = s.getExtra(LabArt.K_CLIP, "")
    val appGroup = L("App pages")
    val artRows = LAB_SLOTS.map { (slot, label) ->
        MenuEntry("art-$slot", L(label), slotSource(s, slot), group = appGroup, thumb = { SlotThumb(s, slot) }, onA = { slotFor = slot })
    } + listOf(
        MenuEntry(
            "art-clip", L("Preview clip"),
            when {
                clipPick == "none" -> L("None")
                clipPick.startsWith("http") -> L("Steam trailer")
                clipPick.isNotBlank() -> L("Your video")
                LabArt.appId(s) != null -> L("Automatic · a Steam trailer")
                else -> L("None found · not linked to Steam")
            },
            icon = Icons.Filled.Wallpaper, group = appGroup, onA = { slotFor = "clip" },
        ),
        MenuEntry(
            "art-adjust", L("Adjust logo"),
            L(LAB_LOGO_POS.firstOrNull { it.first == s.getExtra(LabArt.K_LOGO_POS, "") }?.second ?: "Default") + " · " + LabArt.label(LabArt.SIZES, s.getExtra(LabArt.K_LOGO_SIZE, "").ifBlank { "m" }),
            icon = Icons.Filled.Tune, group = appGroup, onA = { adjustOpen = true },
        ),
    ) + LabArt.IG_SLOTS.map { (slot, base) ->
        // Lab's in-game menu: its own picture for each, or the app pages' until one is picked.
        MenuEntry("art-$slot", L(LAB_SLOTS.first { it.first == base }.second), slotSource(s, slot), group = L("In-game menu"), thumb = { SlotThumb(s, slot) }, onA = { slotFor = slot })
    }
    fun appSetting(v: String) = L("Use app setting (%1\$s)", v)
    val mode = s.getExtra(LabArt.K_MODE, "")
    val pic = s.getExtra(LabArt.K_PICTURE, "")
    val pan = s.getExtra(LabArt.K_PANELS, "")
    val grd = s.getExtra(LabArt.K_GRID, "")
    fun mark(label: String, on: Boolean) = if (on) "$label  ✓" else label
    val pictures = listOf("" to T("Automatic"), "bg" to T("Background picture"), "wide" to T("Wide cover"), "blur" to T("Cover, blurred"), "lab" to T("Lab wallpaper"))
    val bgRows = listOf(
        MenuEntry(
            "b-mode", L("Game art background"), null, icon = Icons.Filled.Image,
            trailing = if (mode.isBlank()) appSetting(LabArt.label(LabArt.MODES, LabArt.mode)) else LabArt.label(LabArt.MODES, mode),
            actions = listOf(MenuAction(mark(appSetting(LabArt.label(LabArt.MODES, LabArt.mode)), mode.isBlank()), { setGame(LabArt.K_MODE, null) })) +
                LabArt.MODES.map { (v, l) -> MenuAction(mark(L(l), mode == v), { setGame(LabArt.K_MODE, v) }) },
        ),
        MenuEntry(
            "b-pic", L("Picture"), null, icon = Icons.Filled.Wallpaper, trailing = L(pictures.firstOrNull { it.first == pic }?.second ?: "Automatic"),
            actions = pictures.map { (v, l) -> MenuAction(mark(L(l), pic == v), { setGame(LabArt.K_PICTURE, v.ifBlank { null }) }) },
        ),
        MenuEntry(
            "b-panels", L("Panels over art"), null, icon = Icons.Filled.Layers,
            trailing = if (pan.isBlank()) appSetting(LabArt.label(LabArt.PANELS, LabArt.panels)) else LabArt.label(LabArt.PANELS, pan),
            actions = listOf(MenuAction(mark(appSetting(LabArt.label(LabArt.PANELS, LabArt.panels)), pan.isBlank()), { setGame(LabArt.K_PANELS, null) })) +
                LabArt.PANELS.map { (v, l) -> MenuAction(mark(L(l), pan == v), { setGame(LabArt.K_PANELS, v) }) },
        ),
        MenuEntry(
            "b-grid", L("Grid over art"), null, icon = Icons.Filled.GridOn,
            trailing = when (grd) { "on" -> L("On"); "off" -> L("Off"); else -> appSetting(if (LabArt.grid) L("On") else L("Off")) },
            actions = listOf(
                MenuAction(mark(appSetting(if (LabArt.grid) L("On") else L("Off")), grd.isBlank()), { setGame(LabArt.K_GRID, null) }),
                MenuAction(mark(L("On"), grd == "on"), { setGame(LabArt.K_GRID, "on") }),
                MenuAction(mark(L("Off"), grd == "off"), { setGame(LabArt.K_GRID, "off") }),
            ),
        ),
    )
    SimpleMenuPage(
        overline = L("Game details"),
        title = LabTitles.of(s),
        sections = listOf(
            MenuSection("details", L("Details"), Icons.Filled.Info, detailsRows),
            MenuSection("artwork", L("Artwork"), Icons.Filled.Image, artRows),
            MenuSection("background", L("Background"), Icons.Filled.Layers, bgRows),
        ),
        onBack = onBack,
        startSection = listOf("details", "artwork", "background").getOrElse(tab) { "details" },
    )

    textFor?.let { f ->
        val (title, start) = when (f) {
            "name" -> L("Name") to s.name
            "genres" -> L("Genres") to details.genres.joinToString(", ")
            "year" -> L("Release year") to (details.releaseYear ?: "")
            "meta" -> L("Metacritic") to (details.metacritic?.toString() ?: "")
            "steam" -> L("Search Steam") to s.name
            else -> L("Description") to (details.description ?: "")
        }
        LabTextDialog(
            title, start, hint = when (f) { "genres" -> L("Action, RPG, Strategy"); "meta" -> "1–100"; else -> null }, multiline = f == "desc",
            confirm = if (f == "steam") L("Search") else L("Save"), onDismiss = { textFor = null },
        ) { v ->
            textFor = null
            when (f) {
                "name" -> rename(v)
                "steam" -> searchSteam(v)
                "genres" -> saveDetails(details.copy(genres = v.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                "year" -> saveDetails(details.copy(releaseYear = v.trim().ifBlank { null }))
                "meta" -> saveDetails(details.copy(metacritic = v.trim().toIntOrNull()?.takeIf { it in 1..100 }))
                else -> saveDetails(details.copy(description = v.trim().ifBlank { null }))
            }
        }
    }
    steamHits?.let { hits ->
        SimpleActionSheet(L("Steam"), hits.map { h -> MenuAction("${h.name}  ·  ${h.appId}", { linkSteam(h.appId) }) }, onDismiss = { steamHits = null }) { a -> steamHits = null; a.run() }
    }
    slotFor?.let { slot ->
        if (slot == "clip") LabClipChooser(s, onDismiss = { slotFor = null })
        else LabArtChooser(s, slot, onDismiss = { slotFor = null }, onReload = { fresh -> shortcut = fresh; LabArt.selected = fresh })
    }
    if (adjustOpen) LabAdjustLogo(s, onDismiss = { adjustOpen = false })
}

/** A slot's picture at the size Artwork shows it. */
@Composable
private fun SlotThumb(s: Shortcut, slot: String) {
    val tick = LabArt.tick
    val (w, h) = when (LabArt.igBase(slot)) { "cover" -> 40.dp to 60.dp; "wide" -> 96.dp to 45.dp; "bg" -> 96.dp to 54.dp; "logo" -> 110.dp to 44.dp; else -> 48.dp to 48.dp }
    Box(Modifier.size(w, h).clip(SimpleShape).background(Color(0xFF1A1D24)).border(1.dp, SimpleLine, SimpleShape)) {
        when (slot) {
            "igicon" -> if (LabArt.pick(s, slot) != "none") LabInGameIcon(s, Modifier.fillMaxSize())
            "igbg", "iglogo" -> {
                val pics = remember(s.file.path, tick, slot) {
                    if (slot == "igbg") LabArt.inGameBackground(s) else LabArt.inGameModels(s, "logo").map { LabArt.Pic(it) }
                }
                LabPicture(pics, Modifier.fillMaxSize(), contentScale = if (slot == "iglogo") ContentScale.Fit else ContentScale.Crop, onNone = {
                    Text(if (slot == "iglogo") s.name else L("None"), color = SimpleMuted, fontSize = 10.sp, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(4.dp))
                })
            }
            "cover" -> SimpleCover(s, Modifier.fillMaxSize(), title = false)
            "icon" -> LabIcon(s, Modifier.fillMaxSize())
            "wide" -> LabWideCard(s, Modifier.fillMaxSize(), title = false)
            else -> {
                val pics = remember(s.file.path, tick, slot) {
                    if (slot == "bg") LabArt.models(s, "bg").map { LabArt.Pic(it) } + listOfNotNull(LabArt.coverModel(s)?.let { LabArt.Pic(it, blur = true) })
                    else LabArt.models(s, slot).map { LabArt.Pic(it) }
                }
                LabPicture(pics, Modifier.fillMaxSize(), contentScale = if (slot == "logo") ContentScale.Fit else ContentScale.Crop, onNone = {
                    if (slot == "logo") Text(s.name, color = SimpleMuted, fontSize = 10.sp, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(4.dp))
                })
            }
        }
    }
}

/** A text field in a Lab dialog: Save keeps it, Cancel or B leaves it. */
@Composable
private fun LabTextDialog(title: String, initial: String, hint: String?, multiline: Boolean, confirm: String = L("Save"), onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(v) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel")) } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { v = it }, singleLine = !multiline, minLines = if (multiline) 3 else 1,
                placeholder = hint?.let { h -> { Text(h) } }, modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** The frame of the Lab pickers: a panel in a dialog with a title and a scrolling body. */
@Composable
private fun LabPickerFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(min = 320.dp, max = 620.dp).heightIn(max = 640.dp)
                .simpleDepth(high = true).background(SimplePanelBrush, SimpleShape).border(1.dp, SimpleLine, SimpleShape)
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            content()
        }
    }
}

@Composable
private fun PickerRow(label: String, requester: FocusRequester? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).labTile(onClick = onClick, requester = requester).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(label, color = SimpleStrong, fontSize = 15.sp) }
}

@Composable
private fun GroupLabel(text: String) = Text(text, color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))

/**
 * Choosing one picture (DroidDeck's art chooser): Steam's and SteamGridDB's as thumbnails, Pick image…, and Reset to
 * automatic (plus "Show the name instead" for the logo). The cover goes through Classic's custom cover (CustomCover);
 * the others are kept in the game's Extra Data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabArtChooser(s: Shortcut, slot: String, onDismiss: () -> Unit, onReload: (Shortcut) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<ArtChoice>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val base = LabArt.igBase(slot)
    LaunchedEffect(slot) {
        choices = withContext(Dispatchers.IO) {
            (LabArt.appId(s)?.let { steamChoices(it, base) }.orEmpty()) + sgdbChoices(context, s, base)
        }
    }
    fun apply(url: String?) {
        if (busy) return
        busy = true
        scope.launch {
            if (slot == "cover") {
                val ok = withContext(Dispatchers.IO) { if (url == null) labResetCover(context, s) else labPickCover(context, s, url) }
                if (!ok) toast(context, if (url == null) L("Could not reset the cover") else L("Could not load that picture"))
                // A Steam cover is written to the game's file by applySteamCover: the page reads it again.
                withContext(Dispatchers.IO) { runCatching { Shortcut(s.container, s.file) }.getOrNull() }?.let(onReload)
                ShortcutsViewModel.notifyContainersChanged()
                LabArt.changed()
            } else LabArt.putGame(s, LabArt.slotKey(slot), url)
            busy = false
            onDismiss()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { picked ->
            if (slot == "cover") apply(picked) else keepFile(context, picked, s, slot)?.let { apply(it) } ?: toast(context, L("Could not use that file"))
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(choices != null) { delay(80); runCatching { first.requestFocus() } }
    LabPickerFrame(slotLabel(slot), onDismiss) {
        val list = choices
        if (list == null) Text(L("Looking for pictures…"), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        else {
            // The cover's pick is CustomCover's; the other slots keep theirs in the game's Extra Data.
            val current = if (slot == "cover") s.getExtra(K_COVER_PICK, "").takeIf { CustomCover.isCustom(s) } else LabArt.pick(s, slot)
            var given = false
            for (group in listOf("Steam", "SteamGridDB")) {
                val inGroup = list.filter { it.group == group }
                if (inGroup.isEmpty()) continue
                GroupLabel(group)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    inGroup.forEach { c ->
                        val (w, h) = when (base) { "cover" -> 84.dp to 126.dp; "icon" -> 72.dp to 72.dp; "logo" -> 160.dp to 64.dp; else -> 160.dp to 75.dp }
                        val req = if (!given) first.also { given = true } else null
                        Box(
                            Modifier.size(w, h).clip(SimpleShape).background(Color(0xFF1A1D24))
                                .then(if (current == c.url) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, SimpleShape) else Modifier)
                                .labTile(onClick = { apply(c.url) }, requester = req),
                        ) {
                            AsyncImage(model = c.thumb, contentDescription = c.label, contentScale = if (base == "logo") ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (list.isEmpty()) Text(L("Nothing found for this game. Pick an image, or link it to Steam under Details."), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        PickerRow(L("Pick image…"), requester = if (choices?.isEmpty() == true) first else null) { picker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.IMAGES, L("Pick an image: %1\$s", slotLabel(slot)))) }
        if (base == "logo") PickerRow(L("Show the name instead")) { apply("none") }
        if (LabArt.isIgSlot(slot)) {
            if (base != "logo") PickerRow(L("None")) { apply("none") }
            PickerRow(L("Same as app pages")) { apply(null) }
        } else PickerRow(L("Reset to automatic")) { apply(null) }
    }
}

/** Preview clip: the game's Steam trailers, a video file, none, or automatic (a gameplay trailer when there is one). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabClipChooser(s: Shortcut, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var trailers by remember { mutableStateOf<List<MediaVideo.Direct>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val id = LabArt.appId(s)
        val got = withContext(Dispatchers.IO) { id?.let { labSteamTrailers(it) } }
        unreachable = id != null && got == null
        trailers = got.orEmpty()
    }
    fun apply(v: String?) { LabArt.putGame(s, LabArt.K_CLIP, v); onDismiss() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) InAppFilePicker.pickedPath(r.data)?.let { picked ->
            keepFile(context, picked, s, "clip")?.let { apply(it) } ?: toast(context, L("Could not use that file"))
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(trailers != null) { delay(80); runCatching { first.requestFocus() } }
    LabPickerFrame(L("Preview clip"), onDismiss) {
        val list = trailers
        val current = s.getExtra(LabArt.K_CLIP, "")
        when {
            list == null -> Text(L("Looking for trailers…"), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            list.isEmpty() -> Text(
                when {
                    LabArt.appId(s) == null -> L("Not linked to Steam: link it under Details to find trailers.")
                    unreachable -> L("Could not reach Steam.")
                    else -> L("Steam has no trailers for this game.")
                },
                color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
            )
            else -> {
                GroupLabel(L("Steam trailers"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    list.forEachIndexed { i, t ->
                        Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(160.dp, 90.dp).clip(SimpleShape).background(Color(0xFF1A1D24))
                                    .then(if (current == t.url) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, SimpleShape) else Modifier)
                                    .labTile(onClick = { apply(t.url) }, requester = if (i == 0) first else null),
                            ) { AsyncImage(model = t.poster, contentDescription = t.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Text(t.title, color = SimpleMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        PickerRow(L("Pick a video…"), requester = if (list?.isEmpty() == true) first else null) {
            picker.launch(InAppFilePicker.buildIntent(context, arrayOf("mp4", "webm", "mkv", "m4v"), L("Preview clip")))
        }
        PickerRow(L("No preview clip")) { apply("none") }
        PickerRow(L("Reset to automatic")) { apply(null) }
    }
}

/** Adjust logo (DroidDeck's): its spot over the art in Home's big tile and its size, with a preview. */
@Composable
private fun LabAdjustLogo(s: Shortcut, onDismiss: () -> Unit) {
    var pos by remember { mutableStateOf(s.getExtra(LabArt.K_LOGO_POS, "")) }
    var size by remember { mutableStateOf(s.getExtra(LabArt.K_LOGO_SIZE, "")) }
    val tick = LabArt.tick
    val bg = remember(s.file.path, tick) { LabArt.models(s, "bg").map { LabArt.Pic(it) } + listOfNotNull(LabArt.coverModel(s)?.let { LabArt.Pic(it, blur = true) }) }
    val steamPin = LabArt.appId(s)?.let { SteamLibraryArt.of(it)?.logoPosition?.pinnedPosition }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LabPickerFrame(L("Adjust logo"), onDismiss) {
        Box(Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 8.dp).clip(SimpleShape).background(Color(0xFF1A1D24))) {
            LabPicture(bg, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 36.dp)) { LabHeroLogo(s, pos, size.ifBlank { "m" }) }
            if (!LabArt.hasLogo(s)) Text(L("This game has no logo yet. Choose one under Logo to see it here."), color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        GroupLabel(L("Position"))
        ChipRow(LAB_LOGO_POS.map { (v, l) -> v to (if (v.isBlank() && steamPin != null) L("Default (Steam: %1\$s)", L(LAB_LOGO_POS.firstOrNull { it.first == steamPin }?.second ?: "Bottom left")) else L(l)) }, pos, first) { pos = it }
        GroupLabel(L("Size"))
        ChipRow(LabArt.SIZES.map { (v, l) -> v to L(l) }, size.ifBlank { "m" }, null) { size = it }
        Text(L("Shows over the art in Home's big tile when previews are on. On the game page the logo takes the name's place."), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        PickerRow(L("Save")) {
            LabArt.putGame(s, LabArt.K_LOGO_POS, pos.ifBlank { null })
            LabArt.putGame(s, LabArt.K_LOGO_SIZE, size.takeIf { it.isNotBlank() && it != "m" })
            onDismiss()
        }
        PickerRow(L("Reset to default")) { pos = ""; size = "" }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(items: List<Pair<String, String>>, current: String, requester: FocusRequester?, onPick: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
        items.forEachIndexed { i, (v, l) ->
            val on = v == current
            Text(
                l, color = if (on) SimpleStrong else SimpleMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(if (on) accent.copy(alpha = 0.22f) else SimpleCard, SimpleShape)
                    .border(1.dp, if (on) accent else SimpleLine, SimpleShape)
                    .labTile(onClick = { onPick(v) }, requester = if (on || (i == 0 && items.none { it.first == current })) requester else null)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
