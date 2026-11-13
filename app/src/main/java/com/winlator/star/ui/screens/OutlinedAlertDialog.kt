package com.winlator.star.ui.screens

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
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
/** True inside a screen that wears the Lab look in every interface (the store screens): its dialogs and menus follow. */
internal val LocalLabLook = androidx.compose.runtime.staticCompositionLocalOf { false }

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
    // Lab: square Lab corners and line, Lab's title font, Lab's focus outline on the cards and buttons inside.
    val lab = com.winlator.star.ui.theme.AppThemeState.simpleLook || LocalLabLook.current
    if (lab) {
        val accent = MaterialTheme.colorScheme.primary
        val labShape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
        // The outline goes round the focused button itself, in its own pill shape (the slot is wider than the button).
        val ring: @Composable (@Composable () -> Unit) -> Unit = { slot ->
            androidx.compose.foundation.layout.Box(Modifier.labFocusedOutline(accent)) { slot() }
        }
        val labIndication = androidx.compose.runtime.remember(accent) { com.winlator.star.ui.simple.LabFocusIndication(accent) }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                DeckDialogBars()
                ring { DeckDialogScaled(confirmButton) }
            },
            modifier = modifier.border(1.dp, com.winlator.star.ui.simple.SimpleLine, labShape),
            dismissButton = dismissButton?.let { { ring { DeckDialogScaled(it) } } },
            icon = icon?.let { { DeckDialogScaled(it) } },
            title = title?.let {
                {
                    androidx.compose.material3.ProvideTextStyle(
                        androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = com.winlator.star.ui.deck.SoraFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    ) { DeckDialogScaled(it) }
                }
            },
            text = text?.let {
                { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides labIndication) { DeckDialogScaled(it) } }
            },
            shape = labShape,
            containerColor = if (containerColor == AlertDialogDefaults.containerColor) MaterialTheme.colorScheme.surface else containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
        return
    }
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

/** Draws an accent pill outline round whichever control inside has controller focus, fitted to that control's bounds. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.labFocusedOutline(accent: Color): Modifier {
    var self by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var focused by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null, androidx.compose.runtime.neverEqualPolicy())
    }
    return this
        .onPlaced { self = it }
        .onFocusedBoundsChanged { focused = it }
        .drawWithContent {
            drawContent()
            val s = self
            val f = focused
            if (s != null && f != null && s.isAttached && f.isAttached) {
                val r = s.localBoundingBoxOf(f, clipBounds = false)
                val gap = 2.dp.toPx()
                val h = r.height + gap * 2
                drawRoundRect(
                    color = accent,
                    topLeft = androidx.compose.ui.geometry.Offset(r.left - gap, r.top - gap),
                    size = androidx.compose.ui.geometry.Size(r.width + gap * 2, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                )
            }
        }
}
