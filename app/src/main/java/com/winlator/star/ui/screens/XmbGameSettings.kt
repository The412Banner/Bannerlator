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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.GameDetails
import com.winlator.star.container.Shortcut
import com.winlator.star.core.MoveGameToContainer
import com.winlator.star.store.SteamStoreSearch
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
        // Next to the cover a game gets from Scrape cover: the user's own image, and the way back from it.
        val coverCtx = xmb.context
        rows += XmbRow.Link("coverAdd", coverCtx.getString(R.string.xmb_cover_add), Icons.Filled.Image,
            subtitle = coverCtx.getString(R.string.xmb_cover_add_hint)) { coverPickerMenu(xmb, s) }
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

private fun xmbMoveGame(
    xmb: XmbScope, s: Shortcut, c: Container, keepCopy: Boolean,
    options: MoveGameToContainer.Options = MoveGameToContainer.Options(), plan: MoveGameToContainer.Plan? = null,
) {
    xmb.toast(if (keepCopy) "Copying \"${s.name}\"…" else "Moving \"${s.name}\"…")
    xmb.scope.launch {
        val r = withContext(Dispatchers.IO) { MoveGameToContainer.run(xmb.context, s, c, keepCopy, options, plan) }
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
