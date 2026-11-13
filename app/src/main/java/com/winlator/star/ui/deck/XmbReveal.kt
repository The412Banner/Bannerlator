package com.winlator.star.ui.deck

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import com.winlator.star.ui.screens.xmbRevealDelta
import kotlin.math.roundToInt

// Where the controller's highlight goes, the page follows it with room to spare: going down the item stops short of the bottom
// edge, going up short of the top one, so the next item (or the last) is in view before the cursor gets there. Compose's own
// follow-the-focus scroll reveals the least, which leaves the item exactly on the edge.
//
// Two ways in, one room ([XMB_REVEAL_ROOM_DP]):
//  - [xmbRevealRoom] for what scrolls with the focus: an item asks its scroll area to show it, and this makes the ask that much
//    bigger. The highlight ([xmbHighlight]) puts it on every stop under a highlight host (the XMB interface and the store
//    screens), the stores' own ring and tile on theirs; a verticalScroll area without such stops puts it on its content, after
//    the scroll modifier.
//  - [xmbReveal] for a page that keeps its own cursor and scrolls its list itself (Friends, Downloads); the All Games grid
//    takes the same room into [xmbRevealDelta].

/**
 * The room, in dp on the 412 dp phone scale ([ScreenScale.dp]), kept clear of the highlighted item at the top and at the bottom of
 * its scroll area.
 */
internal const val XMB_REVEAL_ROOM_DP = 58f

/** The most of a scroll area's height one side's room takes, so a short one still has space for the item between the two. */
internal const val XMB_REVEAL_MAX_SHARE = 0.25f

/** [roomPx] for a scroll area [viewportPx] tall: [roomPx], or the share of it that [XMB_REVEAL_MAX_SHARE] allows. */
internal fun xmbRevealRoomIn(roomPx: Float, viewportPx: Float): Float =
    minOf(roomPx, viewportPx * XMB_REVEAL_MAX_SHARE).coerceAtLeast(0f)

/** [rect], the item's bounds, grown by [roomPx] above and below: what the scroll area is asked to bring into view. */
internal fun xmbRevealRect(rect: Rect, roomPx: Float): Rect = Rect(rect.left, rect.top - roomPx, rect.right, rect.bottom + roomPx)

/** [delta] (px, positive = scroll forward) cut to 0 when the area cannot move that way: at its start or end the scroll just stops. */
internal fun xmbRevealStep(delta: Float, canScrollBackward: Boolean, canScrollForward: Boolean): Float = when {
    delta < 0f && !canScrollBackward -> 0f
    delta > 0f && !canScrollForward -> 0f
    else -> delta
}

/** True inside a scroll area that has its own [xmbRevealRoom] on its content: the stops in it leave the room to that. */
internal val LocalXmbRevealByContainer = staticCompositionLocalOf { false }

/** [XMB_REVEAL_ROOM_DP] in px at this screen's scale and density. */
@Composable
internal fun rememberXmbRevealRoomPx(): Float {
    val ss = rememberScreenScale()
    val density = LocalDensity.current
    return with(density) { ss.dp(XMB_REVEAL_ROOM_DP).toPx() }
}

@OptIn(ExperimentalFoundationApi::class)
private class XmbRevealResponder : BringIntoViewResponder {
    var roomPx = 0f
    var active: () -> Boolean = { true }

    override fun calculateRectForParent(localRect: Rect): Rect = if (active()) xmbRevealRect(localRect, roomPx) else localRect

    // The scroll area above scrolls, for the bigger rect asked of it.
    override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
}

/**
 * Makes every follow-the-focus scroll of what is inside this element leave [XMB_REVEAL_ROOM_DP] to spare above and below the focused
 * item. It must sit inside the scroll area: on a stop of a list, or after `verticalScroll` on a column's own modifier (a lazy
 * list's own modifier is outside its scrolling). [active] false: the plain scroll. Horizontal scrolling is not touched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.xmbRevealRoom(active: () -> Boolean = { true }): Modifier {
    val roomPx = rememberXmbRevealRoomPx()
    val responder = remember { XmbRevealResponder() }
    responder.roomPx = roomPx
    responder.active = active
    return this.bringIntoViewResponder(responder)
}

/**
 * Scrolls the list the least that brings the items [from]..[to] (a card, and its section's heading when it is the first) into view
 * with [roomPx] to spare above and below (see [xmbRevealRoom]); items that are not on screen are jumped to first, [from] the room
 * under the top. A group taller than the space left shows its top. At the start or the end of the list the scroll stops there.
 */
internal suspend fun LazyListState.xmbReveal(from: Int, to: Int, roomPx: Float) {
    val info = layoutInfo
    val height = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val room = xmbRevealRoomIn(roomPx, height)
    val shown = info.visibleItemsInfo
    val a = shown.firstOrNull { it.index == from }
    val b = shown.firstOrNull { it.index == to }
    if (a == null || b == null) {
        animateScrollToItem(from, (info.beforeContentPadding - room).roundToInt().coerceAtMost(0))
        return
    }
    val top = (a.offset - info.viewportStartOffset).toFloat()
    val bottom = (b.offset + b.size - info.viewportStartOffset).toFloat()
    val delta = xmbRevealStep(xmbRevealDelta(top, bottom, height, room, room), canScrollBackward, canScrollForward)
    if (delta != 0f) animateScrollBy(delta)
}
