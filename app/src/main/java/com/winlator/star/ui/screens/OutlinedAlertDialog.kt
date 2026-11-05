package com.winlator.star.ui.screens

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.deck.xmbOnGlow
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
    // XMB glow pages: the stock container is replaced by a frosted panel over its own glow (see xmbPanel). A dialog that asks
    // for a colour of its own keeps it.
    val glass = xmbOnGlow() && containerColor == AlertDialogDefaults.containerColor
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            DeckDialogBars()
            DeckDialogScaled(confirmButton)
        },
        modifier = if (glass) modifier.xmbPanel(shape, containerColor) else modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
        dismissButton = dismissButton?.let { { DeckDialogScaled(it) } },
        icon = icon?.let { { DeckDialogScaled(it) } },
        title = title?.let { { DeckDialogScaled(it) } },
        text = text?.let { { DeckDialogScaled(it) } },
        shape = shape,
        containerColor = if (glass) Color.Transparent else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}
