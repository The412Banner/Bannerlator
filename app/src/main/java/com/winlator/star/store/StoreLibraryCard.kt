package com.winlator.star.store

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.INSTALLED_GREEN
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.labTile

/** How every store's library draws its games. One choice for all stores, kept across launches. */
enum class StoreView(val label: String) { POSTERS("Posters"), WIDE("Wide"), LIST("List") }

object StoreViewMode {
    private const val PREFS = "store_ui"
    private const val KEY = "library_view"
    private var loaded = false
    var view by mutableStateOf(StoreView.POSTERS)
        private set

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val saved = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        view = StoreView.entries.firstOrNull { it.name == saved } ?: StoreView.POSTERS
    }

    fun next(ctx: Context) {
        view = StoreView.entries[(view.ordinal + 1) % StoreView.entries.size]
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, view.name).apply()
    }

    /** The library grid's minimum cell width for the current view. */
    fun cell(wide: Boolean): Dp = when (view) {
        StoreView.POSTERS -> if (wide) 92.dp else 82.dp
        StoreView.WIDE -> if (wide) 156.dp else 138.dp
        StoreView.LIST -> 300.dp
    }
}

/** The button that steps through the library views; it shows the current one. */
@Composable
fun StoreViewButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    StoreViewMode.load(ctx)
    val cs = MaterialTheme.colorScheme
    Text(
        text = StoreViewMode.view.label,
        fontSize = 12.sp,
        color = cs.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(SimpleShape)
            .background(cs.onBackground.copy(alpha = 0.05f))
            .labTile(onClick = { StoreViewMode.next(ctx) })
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** "37% · 24.6 MB/s · ~12 min left" for a download in any store, or its queued / paused state. */
fun storeDownloadLine(e: DownloadEntry): String = when (e.state) {
    DownloadState.QUEUED -> "Queued"
    DownloadState.PAUSED -> "Paused · ${e.pct}%"
    else -> listOf("${e.pct}%", formatDownloadSpeed(e.speedBps), formatEta(e.etaSeconds)).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * The one game card every store's library uses, in the current [StoreView]: the art, the name and one status line
 * (the download's progress, speed and time left; "Installed"; or [badge]). A tap or A opens the game's page; the card
 * has no buttons of its own.
 */
@Composable
fun StoreLibraryCard(
    item: CatalogItem,
    installed: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    StoreViewMode.load(LocalContext.current)
    val cs = MaterialTheme.colorScheme
    val all by DownloadRegistry.entries.collectAsState()
    val key = "${item.store}:${item.id}"
    val download = all.firstOrNull { it.key == key && it.isActive }
    val status = download?.let { storeDownloadLine(it) } ?: listOfNotNull(if (installed) "Installed" else null, badge).joinToString(" · ")
    val statusColor = when {
        download != null -> cs.primary
        installed -> INSTALLED_GREEN
        else -> cs.onSurfaceVariant
    }
    val bar: @Composable () -> Unit = {
        if (download != null) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(cs.onBackground.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((download.pct / 100f).coerceIn(0f, 1f)).background(cs.primary))
            }
        }
    }
    val base = modifier
        .fillMaxWidth()
        .clip(SimpleShape)
        .background(cs.onBackground.copy(alpha = 0.05f))
        .labTile(onClick = onOpen)

    if (StoreViewMode.view == StoreView.LIST) {
        Row(base.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogArt(candidates = item.tallArt(), title = item.title, modifier = Modifier.width(30.dp), shape = SimpleShape, aspectRatio = 2f / 3f)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 13.sp, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (status.isNotEmpty()) Text(status, fontSize = 11.sp, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                bar()
            }
        }
        return
    }
    val wide = StoreViewMode.view == StoreView.WIDE
    Column(base) {
        CatalogArt(
            candidates = if (wide) item.wideArt() else item.tallArt(),
            title = item.title,
            modifier = Modifier.fillMaxWidth(),
            aspectRatio = if (wide) 92f / 43f else 2f / 3f,
        )
        bar()
        Text(item.title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp, end = 5.dp, top = 4.dp, bottom = if (status.isEmpty()) 4.dp else 0.dp))
        if (status.isNotEmpty()) Text(status, fontSize = 10.sp, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp, end = 5.dp, bottom = 4.dp))
    }
}
