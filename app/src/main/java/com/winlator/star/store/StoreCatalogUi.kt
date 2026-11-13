package com.winlator.star.store

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.star.store.download.Store
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.labTile
import com.winlator.star.ui.deck.rememberScreenScale
import com.winlator.star.ui.deck.xmbCardFill
import com.winlator.star.ui.deck.xmbCardLine
import com.winlator.star.ui.deck.xmbChipOverArt
import com.winlator.star.ui.deck.xmbGlassCard
import com.winlator.star.ui.theme.StoreDiscountBg
import com.winlator.star.ui.theme.StoreDiscountInk
import com.winlator.star.ui.theme.StoreFreeGreen

/**
 * Store-AGNOSTIC storefront pieces: the catalog item model plus the card / row / hero / price-row /
 * action-button set, shared by the GOG and Epic storefronts.
 *
 * The Steam storefront keeps its own `StoreRailCard` & co. in [SteamStorefrontUi] because they are
 * bound to `SteamStoreCatalog.StoreItem` and the PICS art chain. These are the same designs re-cut
 * over [CatalogItem], which carries pre-resolved image URLs and PRE-FORMATTED price strings — every
 * store formats money differently (GOG hands us "$1.49", Epic hands us cents + a locale string), so
 * the UI never does currency maths.
 *
 * Colour rule is unchanged: everything reads [MaterialTheme.colorScheme] except the three
 * store-semantic tokens ([StoreDiscountInk] / [StoreDiscountBg] / [StoreFreeGreen]).
 */

// ── Model ─────────────────────────────────────────────────────────────────────────────────────

/** One catalog entry as the storefront cards draw it, whichever store produced it. */
data class CatalogItem(
    val store: Store,
    /** Store-native id: GOG product id, Epic offer id. */
    val id: String,
    val title: String,
    /** Wide (landscape) art for rails and heroes. */
    val imageUrl: String?,
    /** Tall (2:3) box art when the store publishes one; falls back to [imageUrl]. */
    val tallImageUrl: String? = null,
    /** Comma-joined genre / tag line under the title in result rows. */
    val tags: String = "",
    val isFree: Boolean = false,
    /** False when the endpoint gave no price at all — the price row then draws nothing. */
    val hasPrice: Boolean = false,
    /** Pre-formatted by the store ("$9.99"). */
    val finalPrice: String = "",
    val originalPrice: String = "",
    val discountPercent: Int = 0,
    /** The web product page — where "Get for free" / "View on <store>" land. */
    val storeUrl: String = "",
    val developer: String = "",
    val releaseDate: String = "",
    val description: String = "",
    /** Per-store identifiers the detail page needs (Epic namespace / slug, GOG slug …). */
    val extra: Map<String, String> = emptyMap(),
) {
    val isDiscounted: Boolean get() = discountPercent > 0 && originalPrice.isNotBlank()
}

/** What the action button on a catalog card should offer right now. */
sealed interface CatalogAction {
    /** Owned → open the store's own detail page (Download / Play live there). */
    data class Open(val installed: Boolean) : CatalogAction

    /** Free and not owned → claim it on the store's website (in-app WebView). */
    data object ClaimFree : CatalogAction

    /** Paid and not owned → view the store page. The app cannot buy anything. */
    data object ViewOnStore : CatalogAction

    /** Something is in flight for this title. */
    data object Working : CatalogAction
}

// ── Art ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Cover art with an ordered candidate chain and a themed placeholder underneath. Same contract as
 * Steam's `StoreCapsule`: never a spinner, never a broken image — each miss falls through to the
 * next URL and finally to the icon + title placeholder.
 */
@Composable
fun CatalogArt(
    candidates: List<String>,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    aspectRatio: Float? = 92f / 43f,
) {
    val urls = remember(candidates) { candidates.filter { it.isNotBlank() }.distinct() }
    var attempt by remember(urls) { mutableStateOf(0) }
    Box(
        modifier = modifier
            .then(if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (attempt < urls.size) {
            AsyncImage(
                model = urls[attempt],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { attempt += 1 },
            )
        }
    }
}

/** Wide art first, tall art as the fallback — for rails, rows and heroes. */
fun CatalogItem.wideArt(): List<String> = listOfNotNull(imageUrl, tallImageUrl)

/** Tall art first, wide as the fallback — for 2:3 library tiles. */
fun CatalogItem.tallArt(): List<String> = listOfNotNull(tallImageUrl, imageUrl)

/** The store's rating as a focused XMB cover shows it ("★ 4.3 / 5"), when the store gave one (GOG: 0-50). */
internal fun CatalogItem.xmbRating(): String? =
    extra["rating"]?.toIntOrNull()?.takeIf { it > 0 }?.let { "★ %.1f / 5".format(it / 10f) }

// ── Price ─────────────────────────────────────────────────────────────────────────────────────

/** "-50%  ~~$24.99~~  $12.49", or "Free", or nothing when the store gave no price. */
@Composable
fun CatalogPriceRow(item: CatalogItem, modifier: Modifier = Modifier) {
    when {
        item.isFree -> Text(
            text = "Free",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = StoreFreeGreen,
            modifier = modifier,
        )

        !item.hasPrice -> Unit

        else -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.isDiscounted) {
                Text(
                    text = "-${item.discountPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StoreDiscountInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(StoreDiscountBg)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Text(
                    text = item.originalPrice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                    maxLines = 1,
                )
            }
            Text(
                text = item.finalPrice,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

// ── Action button ─────────────────────────────────────────────────────────────────────────────

/**
 * The one action button every catalog card and library tile draws.
 *
 * - [CatalogAction.ClaimFree] → filled accent (the primary call to action).
 * - [CatalogAction.Open] → the raised container tier ("already yours"); Play when installed.
 * - [CatalogAction.ViewOnStore] → outlined but ENABLED: unlike Steam, GOG and Epic purchases go
 *   through the web page, so the honest thing is to open it rather than dead-end on "Not owned".
 */
@Composable
fun CatalogActionButton(
    action: CatalogAction,
    storeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(7.dp)
    val pad = if (compact) PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    else PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium

    when (action) {
        CatalogAction.ClaimFree -> Button(
            onClick = onClick,
            modifier = modifier.steamFocusRing(shape),
            shape = shape,
            contentPadding = pad,
        ) {
            Icon(Icons.Filled.Redeem, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Get for free", style = style, maxLines = 1)
        }

        CatalogAction.Working -> Button(
            onClick = {},
            enabled = false,
            modifier = modifier,
            shape = shape,
            contentPadding = pad,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Text("Working…", style = style, maxLines = 1)
        }

        is CatalogAction.Open -> Button(
            onClick = onClick,
            modifier = modifier.steamFocusRing(shape),
            shape = shape,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                if (action.installed) Icons.Filled.PlayArrow else Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (action.installed) "Play" else "Download", style = style, maxLines = 1)
        }

        CatalogAction.ViewOnStore -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.steamFocusRing(shape),
            shape = shape,
            contentPadding = pad,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("View on $storeLabel", style = style, maxLines = 1)
        }
    }
}

// ── Cards ─────────────────────────────────────────────────────────────────────────────────────

/** One rail card: wide art over title, price row, action. Same geometry as Steam's rail card. */
@Composable
fun CatalogRailCard(
    item: CatalogItem,
    action: CatalogAction,
    storeLabel: String,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One card in every interface: the art, the name, the price. A tap opens the game's page, which carries the action.
    Column(
        modifier = modifier
            .width(138.dp)
            .clip(SimpleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .labTile(onClick = onOpen),
    ) {
        CatalogArt(candidates = item.wideArt(), title = item.title, modifier = Modifier.fillMaxWidth())
        Text(item.title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp, end = 5.dp, top = 4.dp))
        CatalogPriceRow(item, modifier = Modifier.padding(start = 5.dp, end = 5.dp, bottom = 4.dp))
    }
}

/** One search-result row: thumb + title + tags + price on the left, action on the right. */
@Composable
fun CatalogResultRow(
    item: CatalogItem,
    action: CatalogAction,
    storeLabel: String,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SimpleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .labTile(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CatalogArt(candidates = item.wideArt(), title = item.title, modifier = Modifier.width(72.dp), shape = SimpleShape)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.tags.isNotBlank()) {
                Text(item.tags, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        CatalogPriceRow(item)
    }
}

/** The featured hero: full-bleed wide art with a bottom scrim carrying eyebrow / title / price. */
@Composable
fun CatalogHeroCard(
    item: CatalogItem,
    action: CatalogAction,
    storeLabel: String,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String = "FEATURED & RECOMMENDED",
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(SimpleShape)
            .labTile(onClick = onOpen),
    ) {
        CatalogArt(candidates = item.wideArt(), title = item.title, modifier = Modifier.fillMaxWidth().height(storeHeroHeight()), aspectRatio = null)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp),
        ) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CatalogPriceRow(item)
        }
    }
}

/**
 * A 2:3 library tile: tall box art over the title and a Play / Download button. Used by the GOG and
 * Epic Library tabs, whose stores publish portrait covers (SteamGridDB grids / DieselGameBoxTall).
 */
@Composable
fun CatalogLibraryTile(
    item: CatalogItem,
    installed: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Small text pill over the art's top-left corner ("Gen 2", "EOS"); null = none. */
    badgeText: String? = null,
    /** Shown in place of the Play / Download button while the game downloads ("Downloading 34%"); null = the button. */
    progressLabel: String? = null,
    /** The tile is just the 2:3 art: title and status sit on a fade at its bottom, no button (tap opens the game). */
    artOnly: Boolean = false,
) {
    // One card for every store and every interface; a download shows its own progress line.
    StoreLibraryCard(item = item, installed = installed, onOpen = onOpen, modifier = modifier, badge = badgeText)
}

// ── Rails / lists ─────────────────────────────────────────────────────────────────────────────

/** One horizontal rail (header + LazyRow of cards) as LazyListScope items. Empty list = no rail. */
fun LazyListScope.catalogRail(
    title: String,
    sub: String,
    games: List<CatalogItem>,
    storeLabel: String,
    actionFor: (CatalogItem) -> CatalogAction,
    onOpen: (CatalogItem) -> Unit,
    onAction: (CatalogItem) -> Unit,
) {
    if (games.isEmpty()) return
    item(key = "hdr_$title") {
        Spacer(Modifier.height(8.dp))
        StoreSectionHeader(title, sub)
    }
    item(key = "rail_$title") {
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            // XMB home: room above and below for a focused cover's pop.
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = if (LocalStoreXmb.current) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Keyed by store + id, and the list is de-duplicated upstream — a duplicate key inside a
            // LazyRow is a hard crash (the Steam storefront shipped exactly that once).
            items(games.distinctBy { it.id }, key = { "${it.store}_${it.id}" }) { g ->
                CatalogRailCard(
                    item = g,
                    action = actionFor(g),
                    storeLabel = storeLabel,
                    onOpen = { onOpen(g) },
                    onAction = { onAction(g) },
                )
            }
        }
    }
}

/** The vertical result list that replaces the rails while a query is active. */
@Composable
fun CatalogResults(
    results: List<CatalogItem>,
    searching: Boolean,
    query: String,
    wide: Boolean,
    storeLabel: String,
    emptyBody: String,
    actionFor: (CatalogItem) -> CatalogAction,
    onOpen: (CatalogItem) -> Unit,
    onAction: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(results) { results.distinctBy { it.id } }
    Column(modifier = modifier.fillMaxSize()) {
        StoreSectionHeader(
            title = if (searching && rows.isEmpty()) "Searching…"
            else "${rows.size} result${if (rows.size == 1) "" else "s"}",
            sub = "“$query”",
        )
        when {
            rows.isEmpty() && searching -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }

            rows.isEmpty() -> StoreNotice(title = "No matches", body = emptyBody)

            // XMB home: the results as a grid of portrait covers.
            LocalStoreXmb.current -> LazyVerticalGrid(
                columns = GridCells.Adaptive(rememberScreenScale().xmbCoverWidth()),
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(rows, key = { it.id }) { g ->
                    XmbStoreCover(
                        art = g.tallArt(), title = g.title, onClick = { onOpen(g) },
                        focusDetail = g.xmbRating(),
                        footer = { CatalogPriceRow(g) },
                    )
                }
            }

            wide -> LazyColumn(
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(rows.chunked(2), key = { it.first().id }) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        pair.forEach { g ->
                            CatalogResultRow(
                                item = g,
                                action = actionFor(g),
                                storeLabel = storeLabel,
                                onOpen = { onOpen(g) },
                                onAction = { onAction(g) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(rows, key = { it.id }) { g ->
                    CatalogResultRow(
                        item = g,
                        action = actionFor(g),
                        storeLabel = storeLabel,
                        onOpen = { onOpen(g) },
                        onAction = { onAction(g) },
                    )
                }
            }
        }
    }
}

/** The storefront search field — identical to Steam's, with a per-store placeholder. */
@Composable
fun CatalogSearchField(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    // The default is the full-width padded form every store tab uses; the Steam Library passes its
    // own so the field can sit beside the sort button.
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 6.dp),
) {
    // A slim field: the Lab's hard corners, 34dp tall, the accent line when focused.
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    // Controller: B closes the keyboard (a second B goes back as usual); Down / Up leave the field for what is
    // below / above it, closing the keyboard on the way.
    var keyboardUp by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusMover = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 13.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .height(34.dp)
            .clip(SimpleShape)
            .background(cs.onBackground.copy(alpha = 0.05f))
            .border(1.dp, if (focused) cs.primary else cs.outline.copy(alpha = 0.5f), SimpleShape)
            .onFocusChanged { focused = it.isFocused; keyboardUp = it.isFocused }
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                val down = k.action == android.view.KeyEvent.ACTION_DOWN
                when (k.keyCode) {
                    android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE ->
                        if (keyboardUp) { if (!down) { keyboard?.hide(); keyboardUp = false }; true } else false
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) { keyboard?.hide(); keyboardUp = false; focusMover.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (down) { keyboard?.hide(); keyboardUp = false; focusMover.moveFocus(androidx.compose.ui.focus.FocusDirection.Up) }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER ->
                        if (!keyboardUp) { if (!down) { keyboard?.show(); keyboardUp = true }; true } else false
                    else -> false
                }
            },
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(placeholder, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).labTile(onClick = onClear))
                }
            }
        },
    )
}

/** The Store tab is one column in both orientations (featured game, then the rails), as in the Lab. */
internal const val STORE_SPLIT = false

/** The featured card's height: 16:7 of the screen width, but never more than a third of the screen's height. */
@Composable
internal fun storeHeroHeight(): Dp {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return minOf(c.screenWidthDp * 7f / 16f, c.screenHeightDp * 0.34f).dp
}
