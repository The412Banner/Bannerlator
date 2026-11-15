package com.winlator.star.ui.screens

import android.content.Context
import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.GameDetails
import com.winlator.star.container.Shortcut
import com.winlator.star.core.MoveGameToContainer
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.ui.simple.ArtChoice
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.keepFile
import com.winlator.star.ui.simple.sgdbChoices
import com.winlator.star.ui.simple.steamChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Game Details, Properties, Clone to container and Remove, as nested XMB columns. A game's Settings
// is its own page (the Shortcut Settings page), not a menu here.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Things only the Games screen can do (its ViewModel / legacy dialogs). */
internal class XmbGameHost(
    val remove: (Shortcut) -> Boolean,
    val containers: () -> List<Container>,
)

/** A typed game name as a file base name: characters Windows forbids in file names become "_". */
private fun xmbSafeName(typed: String): String = typed.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()

/** Rename = the Game Details path (moves .desktop/.lnk + icon + cover). The shortcut object is stale
 *  afterwards, so go back to the games bar and reload the list. */
private fun renameAndReturn(xmb: XmbScope, s: Shortcut, newBase: String) {
    val container = s.container
    val oldBase = s.name
    xmb.scope.launch {
        val ok = withContext(Dispatchers.IO) { runCatching { ExeShortcutImporter.renameShortcutFiles(container, oldBase, newBase) }.getOrDefault(false) }
        xmb.toast(if (ok) "Renamed to “$newBase”" else "Couldn't rename — a game with that name may already exist")
        if (ok) { xmb.popToRoot(); xmb.reloadGames() }
    }
}

/** The file browser on the usual picture folders, showing images only; [onPick] gets the chosen file. */
private fun imageBrowserMenu(xmb: XmbScope, title: String, onPick: (File) -> Unit): XmbMenu {
    val start = File(Environment.getExternalStorageDirectory(), "Pictures").takeIf { it.isDirectory }
        ?: Environment.getExternalStorageDirectory()
    val roots = listOf(
        "Pictures" to File(Environment.getExternalStorageDirectory(), "Pictures"),
        "Download" to File(Environment.getExternalStorageDirectory(), "Download"),
        "Internal storage" to Environment.getExternalStorageDirectory(),
    ).filter { it.second.isDirectory }
    val imageExt = setOf("png", "jpg", "jpeg", "webp", "bmp")
    return xmbFileBrowserMenu(xmb, title, start, roots, { it.extension.lowercase() in imageExt }, onPick)
}

/**
 * "Add your own cover": the picked image becomes the game's cover everywhere (CustomCover cuts it to the
 * 2:3 cover shape and writes both the full cover and the XMB icon). A bad pick leaves the browser open.
 */
private fun coverPickerMenu(xmb: XmbScope, s: Shortcut): XmbMenu {
    val ctx = xmb.context
    return imageBrowserMenu(xmb, ctx.getString(R.string.xmb_cover_browser_title)) { f ->
        xmb.scope.launch {
            val ok = withContext(Dispatchers.IO) { CustomCover.apply(s, f) != null }
            xmb.toast(ctx.getString(if (ok) R.string.xmb_cover_applied else R.string.xmb_cover_unreadable))
            if (ok) { xmb.pop(); xmb.saved(); xmb.refresh(); xmb.reloadGames() }
        }
    }
}

/** "Remove your cover": the cover the game had before comes back (CustomCover.remove). */
private fun removeCustomCover(xmb: XmbScope, s: Shortcut) {
    val ctx = xmb.context
    xmb.scope.launch {
        val ok = withContext(Dispatchers.IO) { CustomCover.remove(ctx, s) }
        xmb.toast(ctx.getString(if (ok) R.string.xmb_cover_removed else R.string.xmb_cover_remove_failed))
        xmb.refresh()
        if (ok) { xmb.saved(); xmb.reloadGames() }
    }
}

/** Where one of the game's art slots (the wide cover or the background) comes from, short enough for a row's value. */
private fun artSource(context: Context, v: String): String = when {
    v.contains("steamgriddb", ignoreCase = true) -> "SteamGridDB"
    v.startsWith("http") -> "Steam"
    else -> context.getString(R.string.xmb_backdrop_yours)
}

/**
 * One art picker: the art slot it writes ("bg" for the background, "wide" for the wide cover) and the few words that
 * differ between the two rows. The lookups, the thumbnails, the file browser and the reset are the same for both, so
 * the picker itself is written once.
 */
private class XmbArtPicker(
    val slot: String,
    val icon: ImageVector,
    val title: String,
    val inUse: String,
    val loading: String,
    val automatic: String,
    val browserTitle: String,
)

/** The wide hero the Home screen draws behind the game, in the slot Lab's Background uses. */
private fun backgroundArt(context: Context) = XmbArtPicker(
    slot = "bg", icon = Icons.Filled.Wallpaper, title = "Background",
    inUse = "Background in use", loading = "Looking for backgrounds…",
    automatic = "Steam's hero, or the cover blurred",
    browserTitle = context.getString(R.string.xmb_backdrop_browser_title),
)

/** The wide card the Home screen draws for the game, in the slot Lab's Wide cover uses. */
private fun wideCoverArt(context: Context) = XmbArtPicker(
    slot = "wide", icon = Icons.Filled.ViewCarousel, title = "Wide cover",
    inUse = "Wide cover in use", loading = "Looking for wide covers…",
    automatic = "Steam's header, or the background behind it",
    browserTitle = context.getString(R.string.xmb_wide_browser_title),
)

/**
 * "Add your own background" and "Add your own wide cover": the art the Home screen draws for this game — Steam's for
 * the linked app, SteamGridDB's, or a picture from storage — saved in the slot [picker] names, the same slots Lab's
 * Background and Wide cover write, so both show it. The pick is a URL or a copy in the app's own art folder
 * ([keepFile]), never a path that could move.
 */
private fun artPickerMenu(xmb: XmbScope, s: Shortcut, picker: XmbArtPicker): XmbMenu {
    val ctx = xmb.context
    val slot = picker.slot
    val thumbs = mutableStateMapOf<String, ImageBitmap>()   // the pictures' thumbnails, added as they arrive
    var choices by mutableStateOf<List<ArtChoice>>(emptyList())
    var loading by mutableStateOf(true)
    var saving by mutableStateOf(false)
    val me = XmbOpen()

    // Both lookups are network calls: off the composition thread, thumbnails one by one rather than all at the end.
    val job = xmb.scope.launch {
        val found = withContext(Dispatchers.IO) {
            val steam = LabArt.appId(s)?.let { id -> runCatching { steamChoices(id, slot) }.getOrDefault(emptyList()) }.orEmpty()
            steam + runCatching { sgdbChoices(ctx, s, slot) }.getOrDefault(emptyList())
        }
        choices = found.distinctBy { it.url }
        loading = false
        for (c in choices) {
            val bmp = withContext(Dispatchers.IO) { xmbDownloadBitmap(c.thumb, 10000, 10000) } ?: continue
            thumbs[c.url] = bmp.asImageBitmap()
        }
    }

    /** Saves [value] (null = automatic) and goes back to Game Details, which reads the new value. */
    fun save(value: String?) {
        LabArt.putGame(s, LabArt.slotKey(slot), value)
        me.close(xmb)
        // The list is re-read from the file, as the cover rows do: the art behind the XMB is drawn from the game's
        // extra data as the list holds it (LabArt.tick then drops the art it had memoised for this game).
        xmb.saved(); xmb.refresh(); xmb.reloadGames()
    }

    fun pickFile() {
        if (saving) return
        xmb.push(imageBrowserMenu(xmb, picker.browserTitle) { f ->
            if (!saving) {
                saving = true
                // The browser collapses to its one remaining column: closing it now is exact, and the copy runs with the
                // picker (and its "Saving…") back on top.
                xmb.pop()
                xmb.scope.launch {
                    val kept = withContext(Dispatchers.IO) { keepFile(ctx, f.path, s, slot) }
                    saving = false
                    if (kept == null) {
                        xmb.toast(ctx.getString(R.string.xmb_backdrop_failed))
                    } else {
                        LabArt.putGame(s, LabArt.slotKey(slot), kept)
                        me.close(xmb)
                        xmb.saved(); xmb.refresh(); xmb.reloadGames()
                    }
                }
            }
        })
    }

    return XmbMenu(picker.title, picker.icon, onClose = { me.isOpen = false; job.cancel() }) {
        val current = LabArt.pick(s, slot)
        buildList<XmbRow> {
            if (current != null) add(XmbRow.Info("current", picker.inUse, picker.icon, value = artSource(ctx, current)))
            if (loading) add(XmbRow.Info("loading", picker.loading, Icons.Filled.HourglassEmpty))
            listOf("Steam", "SteamGridDB").forEach { group ->
                val inGroup = choices.filter { it.group == group }
                if (inGroup.isEmpty()) return@forEach
                add(XmbRow.Header("h:$group", group))
                inGroup.forEachIndexed { i, c ->
                    add(
                        XmbRow.Action(
                            key = "c:${c.url}", label = c.label ?: "$group ${i + 1}", icon = picker.icon,
                            value = if (c.url == current) "✓" else null,
                            disabledReason = if (saving) "Saving…" else null, thumbnail = thumbs[c.url],
                        ) { save(c.url) }
                    )
                }
            }
            if (!loading && choices.isEmpty()) add(XmbRow.Info("none", "No Steam or SteamGridDB art found for this game.", Icons.Filled.Info))
            add(XmbRow.Action("file", "Pick from storage…", Icons.Filled.Folder, subtitle = "An image from your device") { pickFile() })
            add(XmbRow.Action("auto", "Use automatic art", Icons.Filled.Refresh, subtitle = picker.automatic) { save(null) })
        }
    }
}

// ── Game Details ────────────────────────────────────────────────────────────────────────────────────

internal fun xmbGameDetailsMenu(xmb: XmbScope, s: Shortcut): XmbMenu {
    var results by mutableStateOf<List<SteamStoreSearch.SteamSuggestion>>(emptyList())
    var searching by mutableStateOf(false)
    fun details(): GameDetails = GameDetails.from(s)
    fun write(d: GameDetails) { runCatching { d.writeTo(s) }; xmb.saved(); xmb.refresh() }
    // Opens on the name, not on "Unlink from Steam" (a stray A must not unlink).
    return XmbMenu("Game Details", Icons.Filled.Edit, initialKey = "name") {
        val d = details()
        val rows = mutableListOf<XmbRow>()
        d.steamAppId?.takeIf { it > 0 }?.let { id ->
            rows += XmbRow.Info("app", "Steam App ID", Icons.Filled.Star, id.toString())
            rows += XmbRow.Action("unlink", "Unlink from Steam", Icons.Filled.LinkOff) { write(d.copy(steamAppId = null)) }
        }
        rows += XmbRow.Text("name", "Game name", Icons.Filled.ShortText, s.name) { v ->
            val nb = xmbSafeName(v)
            if (nb.isNotEmpty() && nb != s.name) renameAndReturn(xmb, s, nb)
        }
        // The art rows: the cover the user picked, then the wide cover and the background, then the way back from the cover.
        val coverCtx = xmb.context
        rows += XmbRow.Link("coverAdd", coverCtx.getString(R.string.xmb_cover_add), Icons.Filled.Image,
            subtitle = coverCtx.getString(R.string.xmb_cover_add_hint)) { coverPickerMenu(xmb, s) }
        // The wide card the Home screen draws for the game, in its own art slot (the background is its fallback).
        val wide = LabArt.pick(s, "wide")
        rows += XmbRow.Link("wide", coverCtx.getString(R.string.xmb_wide_add), Icons.Filled.ViewCarousel,
            value = wide?.let { artSource(coverCtx, it) },
            subtitle = coverCtx.getString(R.string.xmb_wide_add_hint)) { artPickerMenu(xmb, s, wideCoverArt(coverCtx)) }
        // The wide hero the Home screen draws behind the game, from the same art slot Lab's Background uses.
        val backdrop = LabArt.pick(s, "bg")
        rows += XmbRow.Link("backdrop", coverCtx.getString(R.string.xmb_backdrop_add), Icons.Filled.Wallpaper,
            value = backdrop?.let { artSource(coverCtx, it) },
            subtitle = coverCtx.getString(R.string.xmb_backdrop_add_hint)) { artPickerMenu(xmb, s, backgroundArt(coverCtx)) }
        if (CustomCover.isCustom(s)) {
            rows += XmbRow.Action("coverRemove", coverCtx.getString(R.string.xmb_cover_remove), Icons.Filled.Delete,
                subtitle = coverCtx.getString(R.string.xmb_cover_remove_hint)) { removeCustomCover(xmb, s) }
        }
        rows += XmbRow.Link("search", "Search Steam", Icons.Filled.Search, subtitle = "Pick a result to fill every field") {
            results = emptyList(); searching = true
            val q = s.name
            xmb.scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { SteamStoreSearch.searchByName(q) }.getOrDefault(emptyList()) }
                results = r; searching = false
            }
            XmbMenu("Search Steam", Icons.Filled.Search) {
                when {
                    searching -> listOf(XmbRow.Info("busy", "Searching Steam…", Icons.Filled.Search))
                    results.isEmpty() -> listOf(XmbRow.Info("none", "No results found for “$q”", Icons.Filled.Search))
                    else -> results.mapIndexed { i, r ->
                        XmbRow.Action("r$i", r.name, Icons.Filled.Search, subtitle = "App ID: ${r.appId}") {
                            xmb.scope.launch {
                                xmb.toast("Filling from Steam…")
                                val info = withContext(Dispatchers.IO) { runCatching { SteamStoreSearch.fetchDetails(r.appId) }.getOrNull() }
                                val base = details()
                                val nd = if (info != null) GameDetails(
                                    steamAppId = r.appId, genres = info.genres, description = info.shortDescription,
                                    releaseYear = info.releaseYear, metacritic = info.metacritic,
                                ) else base.copy(steamAppId = r.appId)
                                withContext(Dispatchers.IO) { runCatching { nd.writeTo(s) }; runCatching { applySteamCover(s.container, s.name, r.appId) } }
                                xmb.saved(); xmb.pop(); xmb.reloadGames()
                                if (info != null && info.name.isNotBlank() && info.name != s.name) {
                                    val nb = xmbSafeName(info.name)
                                    if (nb.isNotEmpty()) renameAndReturn(xmb, s, nb)
                                }
                            }
                        }
                    }
                }
            }
        }
        rows += XmbRow.Text("genres", "Genres", Icons.Filled.Label, d.genres.joinToString(", "), subtitle = "Comma-separated", placeholder = "e.g. Action, RPG, Strategy") { v ->
            write(d.copy(genres = v.split(",").map { it.trim() }.filter { it.isNotBlank() }))
        }
        rows += XmbRow.Text("desc", "Description", Icons.Filled.Description, d.description ?: "", subtitle = "Shown on the launch screen") { v ->
            write(d.copy(description = v.trim().takeIf { it.isNotBlank() }))
        }
        rows += XmbRow.Text("year", "Release year", Icons.Filled.Event, d.releaseYear ?: "", numeric = true, placeholder = "e.g. 2023") { v ->
            write(d.copy(releaseYear = v.trim().take(4).takeIf { it.isNotBlank() }))
        }
        rows += XmbRow.Text("mc", "Metacritic", Icons.Filled.Star, d.metacritic?.toString() ?: "", subtitle = "1–100, leave blank to hide", numeric = true) { v ->
            write(d.copy(metacritic = v.trim().toIntOrNull()?.takeIf { it in 1..100 }))
        }
        rows
    }
}

// ── Properties ──────────────────────────────────────────────────────────────────────────────────────

// Opens on the play count, not on "Reset properties".
internal fun xmbPropertiesMenu(xmb: XmbScope, s: Shortcut): XmbMenu = XmbMenu("Properties", Icons.Filled.Info, initialKey = "plays") {
    val prefs = xmb.context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
    val tKey = "${s.name}_playtime"
    val cKey = "${s.name}_play_count"
    val totalMs = prefs.getLong(tKey, 0L)
    val sec = (totalMs / 1000) % 60
    val min = (totalMs / 60000) % 60
    val hr = (totalMs / 3600000) % 24
    val days = totalMs / 86400000
    listOf(
        XmbRow.Info("plays", "Number of times played", Icons.Filled.Star, prefs.getInt(cKey, 0).toString()),
        XmbRow.Info("time", "Playtime", Icons.Filled.Timer, String.format("%dd %02dh %02dm %02ds", days, hr, min, sec)),
        XmbRow.Action("reset", "Reset properties", Icons.Filled.Refresh, danger = true) {
            xmb.confirm(XmbConfirm("Reset properties", "Clear times played and playtime for “${s.name}”?", "Reset", danger = true)) {
                prefs.edit().remove(tKey).remove(cKey).apply()
                xmb.saved(); xmb.refresh()
            }
        },
    )
}

// ── Clone / Remove ──────────────────────────────────────────────────────────────────────────────────

internal fun xmbCloneMenu(xmb: XmbScope, s: Shortcut, host: XmbGameHost): XmbMenu = XmbMenu("Select container", Icons.Filled.ContentCopy) {
    val others = host.containers().filter { it.id != s.container.id }
    if (others.isEmpty()) XmbTools.para("none", NO_OTHER_CONTAINER_XMB, Icons.Filled.Info, context)
    else others.mapIndexed { i, c ->
        XmbRow.Action("c${c.id}_$i", c.name, Icons.Filled.Folder) { xmbMoveGame(xmb, s, c, keepCopy = true) }
    }
}

private const val NO_OTHER_CONTAINER_XMB = "There's no other container. Create one in the Containers screen first."

private fun xmbMoveGame(xmb: XmbScope, s: Shortcut, c: Container, keepCopy: Boolean) {
    xmb.toast(if (keepCopy) "Copying \"${s.name}\"…" else "Moving \"${s.name}\"…")
    xmb.scope.launch {
        val r = withContext(Dispatchers.IO) { MoveGameToContainer.run(xmb.context, s, c, keepCopy) }
        xmb.toast(MoveGameToContainer.message(r, s.name, c.name, keepCopy))
        if (r is MoveGameToContainer.Result.Done) {
            xmb.popToRoot()
            xmb.reloadGames()
            if (r.emuConflicts.isNotEmpty()) xmbEmuConflicts(xmb, r.emuConflicts)
        }
    }
}

internal fun xmbRemoveMenu(xmb: XmbScope, s: Shortcut, host: XmbGameHost): XmbMenu =
    XmbMenu("Remove shortcut?", Icons.Filled.Delete, initialKey = "cancel") {
        listOf(
            XmbRow.Info("msg", "Remove “${s.name}”?", Icons.Filled.Info),
            XmbRow.Action("remove", "Remove", Icons.Filled.Delete, danger = true) {
                val ok = host.remove(s)
                xmb.toast(if (ok) "Shortcut removed." else "Failed to remove shortcut.")
                xmb.popToRoot()
                xmb.reloadGames()
            },
            XmbRow.Action("cancel", "Cancel", Icons.Filled.Close) { xmb.pop() },
        )
    }
