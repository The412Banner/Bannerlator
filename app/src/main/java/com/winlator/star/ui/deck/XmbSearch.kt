package com.winlator.star.ui.deck

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.Screen
import com.winlator.star.ui.screens.ShortcutsViewModel

// The XMB's Y search, a popup over the page that a controller drives from end to end. It opens typing: the field is focused and
// the XMB keyboard is up (B or its Search key puts it away; Search goes on into the results). The D-pad moves between the field and
// the results, which scroll with the focus; A opens the highlighted result, B backs out (the keyboard first, then the popup).
// Landscape puts the field on the left and the results on the right, portrait stacks them; either way the popup stays above the
// keyboard's panel while it is up.

/**
 * The popup's measurements in dp, kept apart from the composables so they can be tested. The window is what the popup's dialog
 * covers, which is the whole screen.
 */
internal object XmbSearchGeometry {
    /** The window is laid out side by side (field left, results right) when it is at least this much wider than it is tall. */
    private const val SIDE_BY_SIDE_RATIO = 1.2f

    /** The field's column takes this share of the popup's inner width beside the results. */
    private const val FIELD_COLUMN_SHARE = 0.38f

    fun sideBySide(windowWidth: Float, windowHeight: Float): Boolean = windowWidth >= windowHeight * SIDE_BY_SIDE_RATIO

    /** The popup's width: the window's less a [margin] on each side, no wider than [max]. */
    fun panelWidth(windowWidth: Float, margin: Float, max: Float): Float = (windowWidth - 2f * margin).coerceIn(0f, max)

    /** The popup's height: the window's less a [margin] above and below, or the part above the keyboard's panel while [keyboardUp]. */
    fun panelHeight(windowHeight: Float, margin: Float, keyboardUp: Boolean): Float {
        val room = if (keyboardUp) windowHeight * (1f - XmbKeyboardGeometry.PANEL_HEIGHT_SHARE) else windowHeight
        return (room - 2f * margin).coerceAtLeast(0f)
    }

    /** The width of the field's column in a popup [innerWidth] wide, held between [min] and [max] (min must not exceed max). */
    fun fieldColumnWidth(innerWidth: Float, min: Float, max: Float): Float = (innerWidth * FIELD_COLUMN_SHARE).coerceIn(min, max)
}

/** The XMB's search popup (Y, or the magnifier): the games and the app's screens and tools, as in [DeckSearchOverlay]. */
@Composable
internal fun XmbSearchPopup(
    onDismiss: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
    onLaunchStore: (Screen) -> Unit,
    onAbout: () -> Unit,
    vm: ShortcutsViewModel,
) {
    // The panel is frosted glass with its own opaque colour, so its text is the one the glass was tuned for, also over Home.
    XmbPanelTheme {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            DeckDialogBars()
            DeckDialogScaled {
                XmbSearchContent(onDismiss, onOpenGame, onOpenRoute, onLaunchStore, onAbout, vm)
            }
        }
    }
}

@Composable
private fun XmbSearchContent(
    onDismiss: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
    onLaunchStore: (Screen) -> Unit,
    onAbout: () -> Unit,
    vm: ShortcutsViewModel,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val stats = remember(shortcuts) { readPlayStats(context, shortcuts) }
    val games = remember(q, shortcuts) { searchGames(q, shortcuts, stats) }
    val places = remember(q) { searchPlaces(q) }
    // The popup opens typing; the field is the tap-to-edit one of the other XMB search bars, so the keyboard can be put away and called back.
    val edit = rememberXmbEdit()
    LaunchedEffect(edit) { edit.start() }
    val results = remember { FocusRequester() }
    val onSearch = {
        edit.stop()
        runCatching { results.requestFocus() }
        Unit
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            // B backs out on release, as the keyboard does; the keyboard's own B never gets here (its window takes it).
            .onKeyEvent { k ->
                val back = k.key == Key.ButtonB || k.key == Key.Escape
                if (back && k.type == KeyEventType.KeyUp) onDismiss()
                back
            }
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
    ) {
        val s = remember(maxWidth, maxHeight) { ScreenScale(maxWidth.value, maxHeight.value) }
        val gap = s.dp(14f)
        val panelWidth = XmbSearchGeometry.panelWidth(maxWidth.value, gap.value, s.dp(820f).value).dp
        val panelHeight = XmbSearchGeometry.panelHeight(maxHeight.value, gap.value, edit.editing).dp
        val panel = Modifier
            .padding(top = gap)
            .width(panelWidth)
            .height(panelHeight)
            .xmbPanel(deckShape(s.dp(22f)), cs.surfaceContainerHigh)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(gap)
        val openPlace = { d: SearchDestination -> openDestination(d, onOpenRoute, onLaunchStore, onAbout) }
        if (XmbSearchGeometry.sideBySide(maxWidth.value, maxHeight.value)) {
            val fieldWidth = XmbSearchGeometry.fieldColumnWidth((panelWidth - gap * 2).value, s.dp(220f).value, s.dp(360f).value).dp
            Row(panel, horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.width(fieldWidth).fillMaxHeight()) {
                    XmbSearchTitle(s)
                    Spacer(Modifier.height(gap))
                    XmbSearchBar(query, { query = it }, edit, onSearch, results, s, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    XmbSearchHints(s)
                }
                XmbSearchResults(query, games, places, stats, results, s, onOpenGame, openPlace, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(panel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XmbSearchTitle(s, Modifier.weight(1f))
                    XmbSearchHints(s)
                }
                Spacer(Modifier.height(gap))
                XmbSearchBar(query, { query = it }, edit, onSearch, results, s, Modifier.fillMaxWidth())
                Spacer(Modifier.height(gap))
                XmbSearchResults(query, games, places, stats, results, s, onOpenGame, openPlace, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun XmbSearchTitle(s: ScreenScale, modifier: Modifier = Modifier) {
    Text(
        "Search",
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = SoraFamily,
        fontWeight = FontWeight.Bold,
        fontSize = s.sp(18f),
        modifier = modifier,
    )
}

/**
 * The search field: D-pad focus (or a tap) lights it, A / Enter / a tap starts typing, and the keyboard's Search or B ends it.
 * Down and Right go on into the [results].
 */
@Composable
private fun XmbSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    edit: XmbEditState,
    onSearch: () -> Unit,
    results: FocusRequester,
    s: ScreenScale,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(s.dp(9f))
    val fill = xmbPopFill()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(s.dp(42f))
            .xmbHighlight(shape, wholeSubtree = true)
            .xmbEditStop(edit)
            .xmbPopCard(shape, fill, shadow = false)
            .border(1.dp, deckLine(), shape)
            .focusProperties {
                down = results
                right = results
            }
            .clickable { edit.start() }
            .padding(horizontal = s.dp(10f)),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(s.dp(18f)))
        Spacer(Modifier.width(s.dp(8f)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Games, screens and tools", fontSize = s.sp(13f), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = s.sp(13f)),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().xmbEditField(edit),
            )
            XmbEditTapCatcher(edit)
        }
    }
}

@Composable
private fun XmbSearchHints(s: ScreenScale) {
    Row(horizontalArrangement = Arrangement.spacedBy(s.dp(14f)), verticalAlignment = Alignment.CenterVertically) {
        XmbSearchHint("A", GlyphKind.A, "Open", s)
        XmbSearchHint("B", GlyphKind.B, "Back", s)
    }
}

@Composable
private fun XmbSearchHint(glyph: String, kind: GlyphKind, label: String, s: ScreenScale) {
    Row(horizontalArrangement = Arrangement.spacedBy(s.dp(6f)), verticalAlignment = Alignment.CenterVertically) {
        DeckGlyph(glyph, kind)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = s.sp(12f), maxLines = 1)
    }
}

/** The results, one focus group that [entry] enters at its first visible row; Compose's own focus scrolls the list to the row it reaches. */
@Composable
private fun XmbSearchResults(
    query: String,
    games: List<Shortcut>,
    places: List<SearchDestination>,
    stats: Map<String, PlayStats>,
    entry: FocusRequester,
    s: ScreenScale,
    onOpenGame: (String) -> Unit,
    onOpenPlace: (SearchDestination) -> Unit,
    modifier: Modifier,
) {
    val typed = query.trim()
    LazyColumn(
        modifier = modifier.focusRequester(entry).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(s.dp(2f)),
    ) {
        if (games.isNotEmpty()) {
            item(key = "h-games") {
                DeckSectionLabel(if (typed.isEmpty()) "Recently played" else "Games · ${games.size}")
            }
            items(games, key = { "g:" + it.file.path }) { game ->
                val subtitle = storeLabelOf(game) + " · " + playtimeLabel(stats[game.file.path]?.ms ?: 0L)
                XmbSearchRow(game.name, subtitle, Icons.Filled.SportsEsports, game, s) { onOpenGame(game.file.path) }
            }
        }
        if (places.isNotEmpty()) {
            item(key = "h-places") { DeckSectionLabel("Screens and tools · ${places.size}", Modifier.padding(top = s.dp(8f))) }
            items(places, key = { "d:" + it.title }) { d ->
                XmbSearchRow(d.title, d.path, d.icon, null, s) { onOpenPlace(d) }
            }
        }
        if (typed.isNotEmpty() && games.isEmpty() && places.isEmpty()) {
            item(key = "none") {
                Text(
                    "Nothing matches \"$typed\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = s.sp(13f),
                    modifier = Modifier.padding(s.dp(10f)),
                )
            }
        }
    }
}

/** One result: the gliding outline marks it while focused (a faint fill, no flood) and its title turns accent through the ink. */
@Composable
private fun XmbSearchRow(title: String, subtitle: String, icon: ImageVector, cover: Shortcut?, s: ScreenScale, onClick: () -> Unit) {
    val shape = deckShape(s.dp(12f))
    DeckInk { item, ink ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .deckFocusRingInk(shape, item, scaleTo = 1f)
                .clip(shape)
                // A on a controller arrives as ButtonA, which clickable does not take for a press (it knows Enter and the centre key).
                .onKeyEvent { k ->
                    val a = k.key == Key.ButtonA
                    if (a && k.type == KeyEventType.KeyDown && k.nativeKeyEvent.repeatCount == 0) onClick()
                    a
                }
                .clickable(onClick = onClick)
                .padding(horizontal = s.dp(10f), vertical = s.dp(5f)),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(s.dp(34f))
                    .clip(deckShape(s.dp(9f)))
                    .background(ink.primary.copy(alpha = 0.14f)),
            ) {
                val bmp = cover?.icon
                if (bmp != null) {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(icon, contentDescription = null, tint = ink.primary, modifier = Modifier.size(s.dp(20f)))
                }
            }
            Spacer(Modifier.width(s.dp(12f)))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dp(1f))) {
                Text(title, color = ink.onSurface, fontWeight = FontWeight.Bold, fontSize = s.sp(14f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = ink.onSurfaceVariant, fontSize = s.sp(11.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
