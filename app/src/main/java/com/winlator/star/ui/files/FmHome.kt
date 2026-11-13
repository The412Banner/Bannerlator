package com.winlator.star.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.core.StringUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** One Home tile. [stats] fills the line under the icon (usage, item count) off the main thread. */
internal class FmHomeTile(
    val title: String,
    val icon: ImageVector,
    val tint: Color?,
    val dir: File,
    val subtitle: String? = null,
    val usage: Boolean = false,
    val countItems: Boolean = false,
    val menu: (() -> List<FmMenuEntry>)? = null,
)

internal class FmHomeSection(val title: String, val tiles: List<FmHomeTile>)

/** The Home page: storage, places, containers' drives and favourites as tiles. */
@Composable
internal fun FmHome(sections: List<FmHomeSection>, onOpen: (File) -> Unit, refreshTick: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val state = rememberLazyGridState()
    val dense = LocalFmDense.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (dense) 120.dp else 150.dp),
        state = state,
        contentPadding = PaddingValues(if (dense) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        sections.filter { it.tiles.isNotEmpty() }.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:" + section.title) {
                Text(
                    section.title,
                    color = cs.onSurfaceVariant,
                    fontSize = if (dense) 12.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = if (dense) 2.dp else 6.dp),
                )
            }
            items(section.tiles, key = { section.title + ":" + it.dir.absolutePath + ":" + it.title }) { tile ->
                HomeTile(tile, refreshTick, onOpen)
            }
        }
    }
}

@Composable
private fun HomeTile(tile: FmHomeTile, refreshTick: Int, onOpen: (File) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var usage by remember(tile.dir) { mutableStateOf<Pair<Long, Long>?>(null) }
    var count by remember(tile.dir) { mutableStateOf<Pair<Long, Int>?>(null) }
    if (tile.usage) {
        LaunchedEffect(tile.dir, refreshTick) { usage = withContext(Dispatchers.IO) { volumeUsage(tile.dir) } }
    }
    if (tile.countItems) {
        LaunchedEffect(tile.dir, refreshTick) {
            count = withContext(Dispatchers.IO) {
                val n = tile.dir.list()?.size ?: 0
                val (bytes, _) = treeSize(tile.dir) { !isActive }
                bytes to n
            }
        }
    }
    val tint = tile.tint ?: cs.primary
    val dense = LocalFmDense.current
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (dense) 88.dp else 132.dp)
                .fmGlass(RoundedCornerShape(if (dense) 14.dp else 18.dp), focused = focused)
                .onFocusChanged { focused = it.isFocused }
                .clickable { onOpen(tile.dir) }
                .padding(horizontal = 10.dp, vertical = if (dense) 7.dp else 12.dp),
        ) {
            Text(
                tile.title, color = cs.onSurface, fontSize = if (dense) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (dense) 5.dp else 10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(if (dense) 34.dp else 48.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
            ) {
                Icon(tile.icon, null, tint = tint, modifier = Modifier.size(if (dense) 20.dp else 26.dp))
            }
            Spacer(Modifier.height(if (dense) 5.dp else 10.dp))
            val u = usage
            val c = count
            val line = when {
                tile.usage && u != null -> "${StringUtils.formatBytes(u.first)} / ${StringUtils.formatBytes(u.second)}"
                tile.countItems && c != null -> "${StringUtils.formatBytes(c.first)} · ${itemCountLabel(c.second)}"
                else -> tile.subtitle ?: ""
            }
            Text(line, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (tile.usage && u != null && u.second > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (u.first.toFloat() / u.second).coerceIn(0f, 1f) },
                    color = tint,
                    trackColor = cs.outline.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                )
            }
        }
        val menu = tile.menu
        if (menu != null) {
            Row(Modifier.align(Alignment.TopEnd)) {
                androidx.compose.material3.IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, "More", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                if (menuOpen) FmMenu(expanded = true, onDismiss = { menuOpen = false }, entries = menu())
            }
        }
    }
}

internal object HomeIcons {
    val internal = Icons.Filled.Smartphone
    val sd = Icons.Filled.SdCard
    val system = Icons.Filled.Storage
    val downloads = Icons.Filled.Download
    val pictures = Icons.Filled.Image
    val documents = Icons.Filled.Description
    val games = Icons.Filled.SportsEsports
    val folder = Icons.Filled.Folder
    val container = Icons.Filled.Computer
    val favorite = Icons.Filled.Star
}
