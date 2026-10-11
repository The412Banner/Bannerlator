package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One image a game can use: a small [thumb] for the list and the [url] that is saved. */
data class ArtChoice(val thumb: String, val url: String, val caption: String? = null)

/** The images one source offers for a game, shown as one row of the picker. */
data class ArtSource(val title: String, val note: String, val choices: List<ArtChoice>)

/**
 * Every image the app can find for a game, by where it comes from: Steam's own library art for
 * its linked app, SteamGridDB's covers (exact game by Steam app, else by name), and the Steam
 * store's screenshots. Sources with nothing are left out. BLOCKING network - call off the main thread.
 */
object GameArtSources {
    private const val STEAM_ASSETS = "https://shared.steamstatic.com/store_item_assets/steam/apps"

    suspend fun load(context: Context, steamAppId: Int?, name: String): List<ArtSource> = coroutineScope {
        val steam = async(Dispatchers.IO) { steamArt(steamAppId) }
        val grid = async(Dispatchers.IO) { gridArt(context, steamAppId, name) }
        val shots = async(Dispatchers.IO) { screenshots(steamAppId) }
        listOf(steam, grid, shots).awaitAll().filterNotNull().filter { it.choices.isNotEmpty() }
    }

    /** Steam's art for the linked app, by the files Steam itself lists (only ones that exist). */
    private fun steamArt(appId: Int?): ArtSource? {
        appId ?: return null
        val listed = runCatching { SteamStoreSearch.libraryAssets(appId) }.getOrDefault(LinkedHashMap())
        val choices = if (listed.isNotEmpty()) {
            listed.map { (label, url) -> ArtChoice(url, url, label) }
        } else {
            // The store's list is unavailable: the usual file names, some of which a game may lack.
            val base = "$STEAM_ASSETS/$appId"
            listOf(
                ArtChoice(SteamStoreSearch.coverUrl(appId), SteamStoreSearch.coverUrl(appId), "Cover"),
                ArtChoice(SteamStoreSearch.headerUrl(appId), SteamStoreSearch.headerUrl(appId), "Header"),
                ArtChoice("$base/capsule_616x353.jpg", "$base/capsule_616x353.jpg", "Capsule"),
            )
        }
        return ArtSource("Steam", "Library and store art for Steam app $appId", choices)
    }

    private fun gridArt(context: Context, appId: Int?, name: String): ArtSource? {
        val byApp = appId != null && appId > 0
        val json = runCatching {
            if (byApp) StarLaunchBridge.sgdbFetchGridsJsonBySteamAppId(context, appId!!).takeIf { it != "[]" }
                ?: StarLaunchBridge.sgdbFetchGridsJson(name)
            else StarLaunchBridge.sgdbFetchGridsJson(name)
        }.getOrNull() ?: return null
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val choices = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            ArtChoice(o.optString("thumb").ifEmpty { url }, url)
        }.take(40)
        return ArtSource("SteamGridDB", "Community covers" + if (byApp) " for Steam app $appId" else " for \"$name\"", choices)
    }

    private fun screenshots(appId: Int?): ArtSource? {
        appId ?: return null
        val media = runCatching { SteamStoreSearch.fetchMedia(appId, "US") }.getOrNull() ?: return null
        val choices = media.screenshots.map { ArtChoice(it.thumb.ifEmpty { it.full }, it.full) }.take(20)
        return ArtSource("Steam screenshots", "From the game's store page", choices)
    }
}

/**
 * The game-art picker: one row per source, each a strip of images to tap, plus the user's own
 * files. [onPick] gets the chosen image's URL; [onPickFile] opens the in-app file manager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameArtPickerSheet(
    steamAppId: Int?,
    name: String,
    onPick: (String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    var loading by remember { mutableStateOf(true) }
    var sources by remember { mutableStateOf<List<ArtSource>>(emptyList()) }

    LaunchedEffect(steamAppId, name) {
        loading = true
        sources = runCatching { withContext(Dispatchers.IO) { GameArtSources.load(context, steamAppId, name) } }.getOrDefault(emptyList())
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = cs.surface, contentColor = cs.onSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(bottom = 8.dp)) {
            Text(
                "Game art",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp),
            )
            Text(
                "Tap an image to use it for $name",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                item(key = "files") {
                    SourceHeader("Your files", "Any image on this device")
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = 90.dp, height = 135.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceVariant)
                                .border(1.dp, cs.outline, RoundedCornerShape(8.dp))
                                .clickable { onPickFile() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Choose\nimage…", fontSize = 12.sp, color = cs.primary, textAlign = TextAlign.Center)
                        }
                    }
                }
                if (loading) {
                    item(key = "loading") {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("  Looking online…", color = OnSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                } else if (sources.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (steamAppId == null) "Nothing found online. Link the game to Steam (search by name) for more art."
                            else "Nothing found online for this game.",
                            color = OnSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                items(sources, key = { it.title }) { source ->
                    SourceHeader("${source.title} (${source.choices.size})", source.note)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(source.choices, key = { it.url }) { choice ->
                            ArtTile(choice) { onPick(choice.url) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SourceHeader(title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(note, fontSize = 11.sp, color = OnSurfaceVariant)
    }
}

@Composable
private fun ArtTile(choice: ArtChoice, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(choice.thumb).crossfade(true).build(),
            contentDescription = choice.caption,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 90.dp, height = 135.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant)
                .clickable(onClick = onClick),
            loading = { Box(Modifier.fillMaxSize().background(SurfaceVariant)) },
            // An image the source does not have (not every app has every Steam asset) stays a
            // plain tile rather than a broken one.
            error = {
                Box(Modifier.fillMaxSize().background(SurfaceVariant), contentAlignment = Alignment.Center) {
                    Text("—", color = OnSurfaceVariant)
                }
            },
        )
        choice.caption?.let { Text(it, fontSize = 10.sp, color = OnSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
    }
}
