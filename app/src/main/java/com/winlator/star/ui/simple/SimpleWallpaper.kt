package com.winlator.star.ui.simple

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.winlator.star.container.Shortcut
import kotlin.math.ceil
import kotlin.math.max

/**
 * The Simple home's background. On a game: that game's cover, filling the screen and a little out of focus
 * ([hero] keeps it sharper, for the Focus layout where it is the main picture), fading from one game to the next.
 * Anywhere else (stores, manage, settings) [focused] is null and the background is a slowly drifting collage of
 * every game's cover. Modifier.blur needs Android 12; older devices get the same picture without the blur.
 */
@Composable
internal fun SimpleWallpaper(focused: Shortcut?, games: List<Shortcut>, hero: Boolean, modifier: Modifier = Modifier) {
    // A page's list arrives a moment after it opens: until then the last one shown, so the collage never starts empty.
    if (games.isNotEmpty()) SimpleWallpaperMemory.games = games
    @Suppress("NAME_SHADOWING")
    val games = games.ifEmpty { SimpleWallpaperMemory.games }
    val collageAlpha by animateFloatAsState(if (focused == null) 1f else 0f, tween(900), label = "simpleCollage")
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF05070B))) {
        if (collageAlpha > 0.01f) {
            SimpleCollage(games, Modifier.fillMaxSize().graphicsLayer { alpha = collageAlpha })
        }
        // Keyed by path: a list refresh rebuilds the Shortcut objects, and that must not restart the fade.
        val byPath = remember(games, focused) { (games + listOfNotNull(focused)).associateBy { it.file.path } }
        Crossfade(targetState = focused?.file?.path, animationSpec = tween(650), label = "simpleWall") { path ->
            val s = path?.let { byPath[it] }
            if (s != null) {
                val bmp = s.icon
                if (bmp != null) {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = if (hero) 0.9f else 0.75f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }
                            .blur(if (hero) 5.dp else 16.dp, BlurredEdgeTreatment.Rectangle),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(fallbackBrush(s.name)).graphicsLayer { alpha = 0.6f })
                }
            }
        }
        // Shade so text stays readable over any art.
        val shade = if (hero) {
            Brush.horizontalGradient(0f to Color(0xE6060810), 0.5f to Color(0x73060810), 1f to Color(0x0D060810))
        } else {
            Brush.verticalGradient(0f to Color(0x80050709), 0.35f to Color(0x1F050709), 1f to Color(0xE0050709))
        }
        Box(Modifier.fillMaxSize().background(shade))
        if (hero) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xF2060810))))
    }
}

private const val COLLAGE_LOOP_MS = 150_000L

/** The covers the collage last showed (see [SimpleWallpaper]). */
internal object SimpleWallpaperMemory {
    var games: List<Shortcut> = emptyList()
}

/**
 * Every game's cover in a tilted grid that drifts upward and loops. The rows repeat after one block, so moving by
 * exactly one block is seamless. With no covers at all it is a soft glow in the accent colour.
 */
@Composable
private fun SimpleCollage(games: List<Shortcut>, modifier: Modifier) {
    val images: List<ImageBitmap> = remember(games) {
        games.mapNotNull { it.icon }.distinct().take(48).map { it.asImageBitmap() }
    }
    if (images.isEmpty()) {
        val accent = MaterialTheme.colorScheme.primary
        Box(modifier.background(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent))))
        return
    }
    val transition = rememberInfiniteTransition(label = "simpleCollageDrift")
    // Where a shared clock has the drift now, so the collage on a page carries on from Home's instead of restarting.
    val phase = remember { (android.os.SystemClock.uptimeMillis() % COLLAGE_LOOP_MS) / COLLAGE_LOOP_MS.toFloat() }
    val tt by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(COLLAGE_LOOP_MS.toInt(), easing = LinearEasing)),
        label = "simpleCollageT",
    )
    val t = (tt + phase) % 1f
    Canvas(modifier = modifier.blur(1.5.dp, BlurredEdgeTreatment.Rectangle)) {
        val cols = 9
        val gap = 12.dp.toPx()
        val span = size.width * 1.32f
        val tileW = (span - gap * (cols - 1)) / cols
        val tileH = tileW * 4f / 3f
        val rowH = tileH + gap
        val needRows = (size.height * 1.6f / rowH).toInt() + 2
        val blockRows = max(needRows, ceil(images.size / cols.toFloat()).toInt())
        val blockH = blockRows * rowH
        rotate(-7f, pivot = center) {
            val x0 = -size.width * 0.16f
            val y0 = -size.height * 0.3f - t * blockH
            for (r in 0 until blockRows * 2) {
                val y = y0 + r * rowH
                if (y > size.height * 1.35f || y + tileH < -size.height * 0.35f) continue
                for (c in 0 until cols) {
                    val img = images[((r % blockRows) * cols + c * 5) % images.size]
                    drawCover(img, Offset(x0 + c * (tileW + gap), y), Size(tileW, tileH))
                }
            }
        }
        drawRect(Color.Black.copy(alpha = 0.56f))
    }
}

/** [img] centre-cropped into the [dst] box. */
private fun DrawScope.drawCover(img: ImageBitmap, topLeft: Offset, dst: Size) {
    val iw = img.width.toFloat()
    val ih = img.height.toFloat()
    if (iw <= 0f || ih <= 0f) return
    val target = dst.width / dst.height
    val sw: Float
    val sh: Float
    if (iw / ih > target) { sh = ih; sw = ih * target } else { sw = iw; sh = iw / target }
    drawImage(
        image = img,
        srcOffset = IntOffset(((iw - sw) / 2f).toInt(), ((ih - sh) / 2f).toInt()),
        srcSize = IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(dst.width.toInt(), dst.height.toInt()),
    )
}
