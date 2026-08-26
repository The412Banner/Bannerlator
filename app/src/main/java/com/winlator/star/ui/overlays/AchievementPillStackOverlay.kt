package com.winlator.star.ui.overlays

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDialogState.AchievementPill
import java.io.File
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------
// Achievement pill stack — gold cards that slide into the TOP-RIGHT when a Steam achievement is
// unlocked in-game (via AchievementWatcher → XServerDialogState.showAchievementToast). Unlike the
// single controller-status toast, these STACK: a burst renders as a Column of pills, each with its
// own ~4.5s lifecycle, and the stack collapses smoothly (animateItemPlacement) as each one leaves.
//
// WHY A Dialog WINDOW (verbatim from ControllerToastOverlay): the game renders into a SurfaceView the
// compositor stacks ABOVE the host ComposeView, so an inline Box would hide behind the game frame. A
// Dialog escapes into its own top-level window stacked above the game, made fully non-interactive
// (FLAG_NOT_TOUCHABLE + NOT_FOCUSABLE, no dim) so it never steals input — purely informational.
// ---------------------------------------------------------------------------------------------------

// Timing (ms) — a longer hold than the controller toast (~4.5s total lifecycle) per pill.
private const val PILL_FADE_IN_MS = 300
private const val PILL_HOLD_MS = 3800
private const val PILL_FADE_OUT_MS = 420

// Placement: top-right, tucked below the Fusion HUD like the controller toast, but a little lower so
// the two don't overlap when both fire on launch.
private const val PILL_TOP_OFFSET_DP = 64
private const val PILL_END_MARGIN_DP = 12
private const val PILL_WIDTH_DP = 288
private const val PILL_SCALE = 0.8f

// --- Palette --- deliberately GOLD-accented (not MaterialTheme.primary) so an achievement reads as
// an achievement under any app theme.
private val Gold        = Color(0xFFE8B652)
private val GoldGlow    = Color(0x59E8B652) // gold @ .35
private val GoldStroke  = Color(0x8CE8B652) // gold @ .55
private val CardTop     = Color(0xF7131A24) // rgba(19,26,36,.97) — matches the controller toast card
private val CardBottom  = Color(0xF70E141C) // rgba(14,19,27,.97)
private val Txt         = Color(0xFFF3E9D6)  // warm off-white, reads on the dark card
private val TxtDim      = Color(0xFFB9A98A)  // muted gold-grey for the description line
private val IconTile    = Color(0xFF0E141C)

private val EaseIn  = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f) // overshoot on entry
private val EaseOut = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

@Composable
fun AchievementPillStackOverlay(state: XServerDialogState) {
    val pills by state.achievementPills.collectAsState()
    if (pills.isEmpty()) return
    AchievementPillStack(pills) { id -> state.removeAchievementPill(id) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AchievementPillStack(pills: List<AchievementPill>, onFinished: (Long) -> Unit) {
    val density = LocalDensity.current
    // Bound the stack height so a long burst scrolls within the screen rather than measuring unbounded.
    val maxStackHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                // Fully pass-through: the pills never steal touch/keys from the game.
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                setDimAmount(0f)
                setGravity(Gravity.TOP or Gravity.END)
                val attrs = attributes
                attrs.x = with(density) { PILL_END_MARGIN_DP.dp.toPx() }.roundToInt()
                attrs.y = with(density) { PILL_TOP_OFFSET_DP.dp.toPx() }.roundToInt()
                attributes = attrs
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .width(PILL_WIDTH_DP.dp)
                .heightIn(max = maxStackHeight),
        ) {
            items(pills, key = { it.id }) { pill ->
                // animateItemPlacement collapses the stack smoothly when a pill above/below leaves.
                AchievementPillCard(
                    pill = pill,
                    onFinished = { onFinished(pill.id) },
                    modifier = Modifier.animateItemPlacement(),
                )
            }
        }
    }
}

@Composable
private fun AchievementPillCard(
    pill: AchievementPill,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Each pill owns its whole fade-in → hold → fade-out cycle, then reports done so the hub drops it
    // from the list (and the survivors slide up via animateItemPlacement). Only `alpha` is animated;
    // the slide + scale are derived from it in the graphicsLayer (same idiom as ControllerToastOverlay)
    // so the pill slides in from above and slides UP on exit without a second animation. Keyed on id
    // so the effect runs exactly once per pill.
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(pill.id) {
        alpha.animateTo(1f, tween(PILL_FADE_IN_MS, easing = EaseIn))
        kotlinx.coroutines.delay(PILL_HOLD_MS.toLong())
        alpha.animateTo(0f, tween(PILL_FADE_OUT_MS, easing = EaseOut))
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha.value
                // alpha 0 → 14dp above final; alpha 1 → settled. Slides down into place on entry,
                // up and away on exit.
                translationY = (1f - alpha.value) * -14.dp.toPx()
                val sc = (0.94f + 0.06f * alpha.value) * PILL_SCALE
                scaleX = sc; scaleY = sc
                transformOrigin = TransformOrigin(1f, 0f) // shrink toward the top-right corner
            }
    ) {
        PillBody(pill)
    }
}

@Composable
private fun PillBody(pill: AchievementPill) {
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                shadowElevation = 16.dp.toPx()
                shape = RoundedCornerShape(14.dp)
                clip = false
                ambientShadowColor = GoldGlow
                spotShadowColor = GoldGlow
            }
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
            .border(1.dp, GoldStroke, RoundedCornerShape(14.dp))
    ) {
        // 3px gold top-rail (gold → transparent).
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Brush.horizontalGradient(listOf(Gold, Color.Transparent), endX = 850f))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
            // Icon tile — the achievement's local icon, or a trophy placeholder.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(IconTile)
                    .border(1.dp, GoldStroke.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
            ) {
                val path = pill.iconPath
                if (path != null && path.isNotEmpty()) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "ACHIEVEMENT UNLOCKED",
                    color = Gold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = pill.name,
                    color = Txt,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val desc = pill.description
                if (!desc.isNullOrEmpty()) {
                    Text(
                        text = desc,
                        color = TxtDim,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
