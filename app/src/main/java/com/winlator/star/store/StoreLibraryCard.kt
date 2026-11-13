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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.labCardMin
import com.winlator.star.ui.simple.labTile

/**
 * How every store's library draws its games: the Lab Library's own card style and size (Covers / Wide / Icons,
 * small / medium / large), so a game looks the same in a store as in the app's Library. Changing it here changes it there.
 */
object StoreViewMode {
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        LabArt.init(ctx.applicationContext)
    }

    val label: String get() = when (LabArt.cards) { LabArt.WIDE -> "Wide"; LabArt.ICONS -> "Icons"; else -> "Covers" }

    fun next(ctx: Context) {
        load(ctx)
        LabArt.chooseCards(when (LabArt.cards) { LabArt.COVERS -> LabArt.WIDE; LabArt.WIDE -> LabArt.ICONS; else -> LabArt.COVERS })
    }

    /** The library grid's minimum cell width: the Lab Library's. */
    @Suppress("UNUSED_PARAMETER")
    fun cell(wide: Boolean): Dp = labCardMin(LabArt.cards, LabArt.cardSize)
}

/** The button that steps through the card styles; it shows the current one. */
@Composable
fun StoreViewButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    StoreViewMode.load(ctx)
    val cs = MaterialTheme.colorScheme
    Text(
        text = StoreViewMode.label,
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
 * The one game card every store's library uses, in the Lab Library's current card style: the art, the name and one status line
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
    val shown = download != null
    val line = status.takeIf { it.isNotEmpty() }
    val lineColor = if (shown) cs.primary else if (installed) INSTALLED_GREEN else Color.White.copy(alpha = 0.7f)
    // The Lab Library's cards: the same shapes, the name over the bottom of the picture.
    val caption: @Composable BoxScope.(Int) -> Unit = { nameLines ->
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 9.dp),
        ) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = nameLines, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            if (line != null) Text(line, color = lineColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { bar() }
    }
    when (LabArt.cards) {
        LabArt.WIDE -> Box(modifier.fillMaxWidth().aspectRatio(460f / 215f).clip(SimpleShape).background(Color(0xFF20242E)).labTile(onClick = onOpen)) {
            CatalogArt(candidates = item.wideArt(), title = item.title, modifier = Modifier.fillMaxSize(), aspectRatio = null)
            caption(if (line != null) 1 else 2)
        }
        LabArt.ICONS -> Column(
            modifier.fillMaxWidth().labTile(onClick = onOpen).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18)).background(Color(0xFF20242E))) {
                CatalogArt(candidates = item.tallArt(), title = item.title, modifier = Modifier.fillMaxSize(), aspectRatio = null)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { bar() }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.title, color = cs.onBackground, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 14.sp)
                if (line != null) Text(line, color = statusColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        else -> Box(modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(SimpleShape).background(Color(0xFF20242E)).labTile(onClick = onOpen)) {
            CatalogArt(candidates = item.tallArt(), title = item.title, modifier = Modifier.fillMaxSize(), aspectRatio = null)
            caption(2)
        }
    }
}
