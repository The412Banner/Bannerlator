package com.winlator.star.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.star.core.PeIconExtractor
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.deck.xmbCardFill
import com.winlator.star.ui.deck.xmbCardLine
import com.winlator.star.ui.deck.xmbOnGlass
import com.winlator.star.ui.screens.outlinedMenuCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ── Look ──

/**
 * The file manager's backdrop: two soft glows of the theme colour over the background. On the XMB glass
 * pages the page already has its glass, so nothing is drawn there.
 */
@Composable
internal fun Modifier.fmBackdrop(): Modifier {
    if (xmbOnGlass()) return this
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    val a = cs.primary
    val b = cs.tertiary
    return this.drawBehind {
        drawRect(bg)
        val r = maxOf(size.width, size.height)
        drawCircle(
            Brush.radialGradient(listOf(a.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.12f, size.height * 0.08f), radius = r * 0.55f),
            radius = r * 0.55f, center = Offset(size.width * 0.12f, size.height * 0.08f),
        )
        drawCircle(
            Brush.radialGradient(listOf(b.copy(alpha = 0.11f), Color.Transparent), center = Offset(size.width * 0.92f, size.height * 0.9f), radius = r * 0.5f),
            radius = r * 0.5f, center = Offset(size.width * 0.92f, size.height * 0.9f),
        )
    }
}

/** A frosted card: glass on the XMB glass, else a see-through surface over the backdrop with a hairline. */
@Composable
internal fun Modifier.fmGlass(
    shape: Shape = RoundedCornerShape(14.dp),
    selected: Boolean = false,
    focused: Boolean = false,
    active: Boolean = true,
): Modifier {
    val cs = MaterialTheme.colorScheme
    val fill = when {
        selected -> cs.primary.copy(alpha = 0.22f)
        else -> xmbCardFill(cs.surfaceContainer.copy(alpha = if (active) 0.80f else 0.62f))
    }
    val line = when {
        focused -> cs.primary
        selected -> cs.primary.copy(alpha = 0.85f)
        else -> xmbCardLine(cs.outline.copy(alpha = 0.40f))
    }
    return this.clip(shape).background(fill).border(if (focused) 2.dp else 1.dp, line, shape)
}

// ── Menus ──

/** One entry of an item's ⋮ menu. */
internal class FmMenuEntry(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun FmMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<FmMenuEntry>) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.outlinedMenuCard()) {
        entries.forEach { e ->
            DropdownMenuItem(
                text = { Text(e.label, color = if (e.danger) cs.error else cs.onSurface) },
                leadingIcon = { Icon(e.icon, null, tint = if (e.danger) cs.error else cs.primary, modifier = Modifier.size(20.dp)) },
                onClick = { onDismiss(); e.onClick() },
            )
        }
    }
}

// ── Icons and thumbnails ──

@Composable
internal fun FmItemIcon(item: FmItem, isArchive: Boolean, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val file = (item as? FmItem.Disk)?.file
    val shape = RoundedCornerShape(size * 0.24f)
    if (file != null && !item.isDir && isImageName(item.name)) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape).background(cs.surfaceVariant),
        )
        return
    }
    var exeIcon by remember(item.key) { mutableStateOf<ImageBitmap?>(null) }
    if (file != null && item.extension == "exe") {
        LaunchedEffect(item.key) {
            exeIcon = withContext(Dispatchers.IO) { runCatching { PeIconExtractor.extract(file)?.asImageBitmap() }.getOrNull() }
        }
    }
    val bmp = exeIcon
    if (bmp != null) {
        Image(bmp, null, modifier = Modifier.size(size).clip(shape))
        return
    }
    val tint = tintFor(item, isArchive) ?: cs.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(shape).background(tint.copy(alpha = 0.16f)),
    ) {
        Icon(iconFor(item, isArchive), null, tint = tint, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun SelectionMark(checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) cs.primary else Color.Transparent)
            .border(2.dp, if (checked) cs.primary else cs.onSurfaceVariant, CircleShape),
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** The second line of a row: size or item count, then date and time. */
@Composable
internal fun detailFor(item: FmItem, pane: FmPane): String {
    val date = fmDateTime(item.modified)
    if (!item.isDir) return "${StringUtils.formatBytes(item.size)}  ·  $date"
    if (item is FmItem.Entry) return if (item.size > 0) "${StringUtils.formatBytes(item.size)}  ·  $date" else date
    val file = (item as FmItem.Disk).file
    val count = pane.childCounts[item.key]
    LaunchedEffect(item.key, pane.listingTick) {
        if (pane.childCounts[item.key] == null) {
            val n = withContext(Dispatchers.IO) { file.list()?.size ?: -1 }
            pane.childCounts[item.key] = n
        }
    }
    return when {
        count == null || count < 0 -> date
        else -> "${itemCountLabel(count)}  ·  $date"
    }
}

/** Gamepad on a focused row: X / Menu opens its menu, Select ticks it. */
private fun Modifier.fmItemKeys(onMenu: (() -> Unit)?, onToggle: (() -> Unit)?): Modifier = this.onPreviewKeyEvent { ev ->
    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (ev.key) {
        Key.ButtonX, Key.Menu -> { onMenu?.invoke(); onMenu != null }
        Key.ButtonSelect -> { onToggle?.invoke(); onToggle != null }
        else -> false
    }
}

// ── Rows and tiles ──

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FmRow(
    item: FmItem,
    pane: FmPane,
    compact: Boolean,
    isArchive: Boolean,
    subtitle: String?,
    menu: (() -> List<FmMenuEntry>)?,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = item.key in pane.selected
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (compact) 2.dp else 3.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fmGlass(RoundedCornerShape(if (compact) 10.dp else 14.dp), selected = selected, focused = focused)
                .onFocusChanged { focused = it.isFocused }
                .fmItemKeys(
                    onMenu = menu?.let { { menuOpen = true } },
                    onToggle = onLongPress?.let { { pane.toggleSelect(item) } },
                )
                .combinedClickable(
                    onClick = { if (pane.selectionMode) pane.toggleSelect(item) else onOpen() },
                    onLongClick = onLongPress?.let { lp -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); lp() } },
                )
                .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp),
        ) {
            if (pane.selectionMode) {
                SelectionMark(selected)
                Spacer(Modifier.width(10.dp))
            }
            FmItemIcon(item, isArchive, if (compact) 28.dp else 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = cs.onSurface,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(detailFor(item, pane), color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, color = cs.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (compact) {
                Text(
                    if (item.isDir) fmDate(item.modified) else StringUtils.formatBytes(item.size),
                    color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1,
                )
            }
            if (menu != null && !pane.selectionMode) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreVert, "More", tint = cs.primary)
                    }
                    if (menuOpen) FmMenu(expanded = true, onDismiss = { menuOpen = false }, entries = menu())
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FmTile(
    item: FmItem,
    pane: FmPane,
    isArchive: Boolean,
    menu: (() -> List<FmMenuEntry>)?,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = item.key in pane.selected
    Box(Modifier.padding(4.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .fmGlass(RoundedCornerShape(16.dp), selected = selected, focused = focused)
                .onFocusChanged { focused = it.isFocused }
                .fmItemKeys(
                    onMenu = menu?.let { { menuOpen = true } },
                    onToggle = onLongPress?.let { { pane.toggleSelect(item) } },
                )
                .combinedClickable(
                    onClick = { if (pane.selectionMode) pane.toggleSelect(item) else onOpen() },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        // A tile has no ⋮ button: a long press opens its menu (selection is in the menu).
                        if (pane.selectionMode) pane.toggleSelect(item) else if (menu != null) menuOpen = true else onLongPress?.invoke()
                    },
                )
                .padding(horizontal = 6.dp, vertical = 10.dp),
        ) {
            Box {
                FmItemIcon(item, isArchive, 52.dp)
                if (pane.selectionMode) Box(Modifier.align(Alignment.TopEnd)) { SelectionMark(selected) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.name, color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.height(32.dp),
            )
            Text(
                if (item.isDir) fmDate(item.modified) else StringUtils.formatBytes(item.size),
                color = cs.onSurfaceVariant, fontSize = 10.sp, maxLines = 1,
            )
        }
        if (menuOpen && menu != null) FmMenu(expanded = true, onDismiss = { menuOpen = false }, entries = menu())
    }
}

/** The ".." row at the top of a listing. */
@Composable
internal fun FmParentRow(compact: Boolean, onUp: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = if (compact) 2.dp else 3.dp)
            .fmGlass(RoundedCornerShape(if (compact) 10.dp else 14.dp), focused = focused)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onUp)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 12.dp),
    ) {
        Text("..", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Path ──

/** Clickable path segments; the last one is where the pane is. */
@Composable
internal fun FmCrumbs(segments: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(segments.size, segments.lastOrNull()?.first) { scroll.scrollTo(scroll.maxValue) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.horizontalScroll(scroll)) {
        segments.forEachIndexed { i, (label, go) ->
            val last = i == segments.lastIndex
            Text(
                label,
                color = if (last) cs.onSurface else cs.primary,
                fontSize = 13.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last, onClick = go)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (!last) Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Capsules (paste, progress) ──

@Composable
internal fun FmCapsule(
    icon: ImageVector,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    progress: Float? = null,
    showProgress: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .fmGlass(RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = cs.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold) }
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Close", tint = cs.onSurfaceVariant) }
            }
        }
        if (showProgress) {
            Spacer(Modifier.height(4.dp))
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            }
        }
    }
}

/** One action of the selection bar. */
internal class FmSelAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val danger: Boolean = false, val onClick: () -> Unit)

/** The selection bar: how many are selected and what can be done with them. */
@Composable
internal fun FmSelectionBar(count: Int, actions: List<FmSelAction>, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .fmGlass(RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Done", tint = cs.onSurfaceVariant) }
        Text("$count", color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            actions.forEach { a ->
                val tint = when {
                    !a.enabled -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                    a.danger -> cs.error
                    else -> cs.primary
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = a.enabled, onClick = a.onClick)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(a.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(a.label, color = tint, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A message in the middle of an empty or failed listing. */
@Composable
internal fun FmEmpty(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = cs.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

internal fun fileOf(item: FmItem): File? = (item as? FmItem.Disk)?.file
