package com.winlator.star.ui.components

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * An "add" button the user can long-press and slide along the bottom of the screen.
 *
 * The problem it solves: the button floats over the list, so on whichever card ends up beneath it
 * the play and overflow buttons are unreachable. There is no corner that is always safe — which
 * card sits under it depends on list length, view mode and screen size — so instead of guessing,
 * let the user move it out of their own way and remember where they put it.
 *
 * Horizontal only, pinned to the bottom. Free 2D placement would let someone strand it over the
 * header or lose it behind a dialog, and the bottom row is exactly the row that gets blocked.
 *
 * Behaviour lives here; the look does not. Games draws a bordered square and Containers a Material
 * FAB, so the caller passes its own styling through [buttonModifier] and its own icon as content.
 *
 * @param prefKey distinct per screen, so Games and Containers remember separate positions.
 */
@Composable
fun BoxScope.DraggableAddButton(
    prefKey: String,
    onClick: () -> Unit,
    buttonModifier: Modifier,
    outerPadding: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("fab_positions", Context.MODE_PRIVATE) }

    // Stored as a 0..1 fraction of the free travel rather than as pixels, so the same setting
    // survives a rotation and lands somewhere sensible on another screen size. Default 1f = hard
    // right, which is where the button has always been.
    var fraction by remember { mutableFloatStateOf(prefs.getFloat(prefKey, 1f).coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }

    // Measured on the parent, not the button: the button is translated, so its own bounds cannot
    // report how much room it has to travel in.
    var trackPx by remember { mutableFloatStateOf(0f) }
    var buttonPx by remember { mutableFloatStateOf(0f) }
    val travel = (trackPx - buttonPx).coerceAtLeast(0f)

    // fraction is 0 = start, 1 = end, but graphicsLayer's translationX is raw pixels and does
    // NOT mirror under RTL — while the layout position (start) does. Without flipping the sign,
    // an RTL locale laid the button out at the visual right edge and then translated it a further
    // full track-width to the right: completely off-screen at the default 1f (#200). Flipped,
    // 1f lands at the visual left — the same spot Alignment.BottomEnd used to give RTL users.
    val dirSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    val lift by animateFloatAsState(if (dragging) 1.12f else 1f, label = "fabLift")

    fun commit() {
        dragging = false
        prefs.edit().putFloat(prefKey, fraction).apply()
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(outerPadding)
            .onSizeChanged { trackPx = it.width.toFloat() },
    ) {
        Box(
            // Order is load-bearing. graphicsLayer must come BEFORE the caller's border/background,
            // because modifiers nest outside-in: a transform only moves what is nested inside it.
            // With it after, the border and fill drew at the layout position while the icon and the
            // touch target slid away — an empty square stuck at the left with its hit box somewhere
            // off to the right. onSizeChanged goes after buttonModifier so it measures the sized
            // button rather than the incoming constraints.
            modifier = Modifier
                .graphicsLayer { translationX = dirSign * travel * fraction }
                .scale(lift)
                .then(buttonModifier)
                .onSizeChanged { buttonPx = it.width.toFloat() }
                // ONE detector handling both tap and drag, deliberately.
                //
                // Two separate pointerInput blocks did not work: the later modifier in a chain is
                // the inner node and receives pointer events FIRST, so the tap detector — which
                // needs an onLongPress to avoid firing on a held press — consumed the long press
                // before the drag detector could claim it, and the button never picked up.
                //
                // Doing it in one gesture removes the ordering question entirely: wait for the
                // press, and whichever happens first (release, or the long-press timeout) decides
                // whether this was a tap or a pick-up.
                .pointerInput(travel, dirSign) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var up: PointerInputChange? = null
                        val releasedInTime = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            up = waitForUpOrCancellation()
                        } != null

                        if (releasedInTime) {
                            // Null means the gesture was cancelled rather than released — a scroll
                            // taking over, say — which is not a tap.
                            if (up != null) onClick()
                        } else {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag(down.id) { change ->
                                if (travel > 0f) {
                                    // positionChange().x is screen-relative too: in RTL a swipe
                                    // toward the end of the track is a negative x, so the same
                                    // sign flip keeps the button following the finger.
                                    fraction = (fraction + dirSign * change.positionChange().x / travel)
                                        .coerceIn(0f, 1f)
                                }
                                change.consume()
                            }
                            commit()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
