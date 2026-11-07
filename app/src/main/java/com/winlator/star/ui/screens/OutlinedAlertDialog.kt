package com.winlator.star.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.LocalDeckDensity
import com.winlator.star.ui.deck.LocalXmbHighlight
import com.winlator.star.ui.deck.XmbHighlightHost
import com.winlator.star.ui.deck.XmbInk
import com.winlator.star.ui.deck.rememberXmbHighlightHost
import com.winlator.star.ui.deck.rememberXmbHighlightItem
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.deck.xmbHighlight
import com.winlator.star.ui.deck.xmbHighlightOverlay
import com.winlator.star.ui.deck.xmbOnGlass
import com.winlator.star.ui.deck.xmbPanel

// Single source of truth for the app's AlertDialogs: a drop-in wrapper around the Material3
// slot-based AlertDialog that adds a 1dp outline around the whole popup box — matching the
// outline that Modifier.outlinedMenuCard() (MenuStyle.kt) puts on the app's menus. Signature
// mirrors androidx.compose.material3.AlertDialog exactly (compose-bom 2024.02.00 → material3
// 1.2.0), so every call site can swap AlertDialog( → OutlinedAlertDialog( unchanged. The
// border shares the dialog's shape so its corner radius follows the rounded dialog.
// The confirm slot also runs DeckDialogBars (inside the dialog's own window), so landscape Deck
// keeps Android's navigation bar hidden behind the popup, and every slot is drawn at the Deck size;
// both are no-ops in Classic.
//
// XMB interface: all the slots of one dialog share ONE gliding highlight (see XmbSharedHost), so the outline travels between the
// rows in the text and the confirm / dismiss buttons instead of fading out in one slot and in again in the next. The
// confirm / dismiss slots are one flat stop each (the whole button outlined); the rows in the text slot register themselves.
@Composable
internal fun OutlinedAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    // XMB glass pages: the stock container is replaced by a frosted panel of its own (see xmbPanel). A dialog that asks
    // for a colour of its own keeps it.
    val glass = xmbOnGlass() && containerColor == AlertDialogDefaults.containerColor
    val panel = if (glass) modifier.xmbPanel(shape, containerColor) else modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    // XMB interface only (null elsewhere: the slots are wrapped exactly as they always were).
    val shared = if (rememberXmbHome()) remember { XmbSharedHost() } else null
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            DeckDialogBars()
            DialogSlot(shared) { DialogButtonSlot(confirmButton) }
        },
        modifier = if (shared != null) panel.xmbSharedHost(shared) else panel,
        dismissButton = dismissButton?.let { { DialogSlot(shared) { DialogButtonSlot(it) } } },
        icon = icon?.let { { DialogSlot(shared, it) } },
        title = title?.let { { DialogSlot(shared, it) } },
        text = text?.let { { DialogSlot(shared, it) } },
        shape = shape,
        containerColor = if (glass) Color.Transparent else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}

/** Hands the dialog's one highlight host (made by [xmbSharedHost] on the panel, inside the dialog's window) to its slots. */
private class XmbSharedHost {
    var host: XmbHighlightHost? = null
}

/**
 * The panel's modifier in the XMB interface: makes the dialog window's highlight host and draws its outline over the whole
 * panel. A composed modifier runs inside the dialog's own composition (LocalView is the dialog's view, which a host must be made
 * for), and before the panel's content is composed, so the slots find [XmbSharedHost.host] set.
 */
private fun Modifier.xmbSharedHost(shared: XmbSharedHost): Modifier = composed {
    val host = rememberXmbHighlightHost()
    shared.host = host
    val overlay = Modifier.xmbHighlightOverlay(host)
    // The slots are drawn at the Deck density (DeckDialogScaled): the outline's corners are measured in it too.
    val slotDensity = LocalDeckDensity.current ?: LocalDensity.current
    SideEffect { host.density = slotDensity }
    this.then(overlay)
}

/** One slot at the Deck size, in the dialog's shared host (its own host if the shared one is not there, or outside XMB). */
@Composable
private fun DialogSlot(shared: XmbSharedHost?, content: @Composable () -> Unit) {
    val host = shared?.host
    if (host == null) {
        DeckDialogScaled(content = content)
    } else {
        DeckDialogScaled(host = false) {
            CompositionLocalProvider(LocalXmbHighlight provides host, content = content)
        }
    }
}

/** A confirm / dismiss slot: in the XMB interface the whole button is one highlight stop. Elsewhere just [content]. */
@Composable
private fun DialogButtonSlot(content: @Composable () -> Unit) {
    if (!rememberXmbHome()) {
        content()
        return
    }
    val item = rememberXmbHighlightItem()
    Box(Modifier.xmbHighlight(CircleShape, item, wholeSubtree = true)) {
        XmbInk(item) { content() }
    }
}
