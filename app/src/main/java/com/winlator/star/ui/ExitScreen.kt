package com.winlator.star.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas as NativeCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.winlator.star.R
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// The exit screen is drawn, not a bitmap, so it stays sharp at any size. Geometry is in the units of the
// 511 x 253 reference artwork (neon card: gamepad mark + "Bannerlator", "Exiting...", a glowing bar).
private const val ART_W = 511f
private const val ART_H = 253f

private const val PINK = 0xFFFF2BD6.toInt()
private const val VIOLET = 0xFF9A3BFF.toInt()
private const val BLUE = 0xFF3D6BFF.toInt()
private const val CYAN = 0xFF23D9FF.toInt()
private const val ICE = 0xFFCDEBFF.toInt()
private const val SKY = 0xFF79B4FF.toInt()

/**
 * Bar position for [elapsedMs] since the exit began. How long the Wine shutdown takes is not known, so the bar
 * runs quickly to about 90 % and then creeps towards 98 %; the screen goes away when the session closes.
 */
internal fun exitProgress(elapsedMs: Long): Float {
    val t = max(0L, elapsedMs) / 1000f
    val fast = 0.90f * (1f - exp(-t / 0.9f))
    val creep = 0.08f * (1f - exp(-t / 8f))
    return (fast + creep).coerceIn(0f, 0.98f)
}

/** Full-screen exit screen: the neon card centred on black, the bar filling while the session closes. */
@Composable
fun ExitScreen(message: String, since: Long) {
    val context = LocalContext.current
    val titleFace = remember { ResourcesCompat.getFont(context, R.font.sora_extrabold) ?: Typeface.DEFAULT_BOLD }
    val textFace = remember { ResourcesCompat.getFont(context, R.font.nunito_sans_semibold) ?: Typeface.DEFAULT }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val path = remember { Path() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val progress = exitProgress(now - (if (since > 0L) since else now))

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color(0xFF030307)),
        contentAlignment = Alignment.Center,
    ) {
        // About three quarters of the width in landscape, limited by the height; the artwork's shape.
        val cardWidth = min(maxWidth.value * 0.76f, maxHeight.value * 0.86f * ART_W / ART_H)
        Canvas(Modifier.width(cardWidth.dp).aspectRatio(ART_W / ART_H)) {
            val u = size.width / ART_W
            drawIntoCanvas { drawExitArt(it.nativeCanvas, u, paint, path, titleFace, textFace, message, progress) }
        }
    }
}

private fun hGradient(x0: Float, x1: Float, vararg colors: Int): Shader =
    LinearGradient(x0, 0f, if (x1 > x0) x1 else x0 + 1f, 0f, colors, null, Shader.TileMode.CLAMP)

private fun vGradient(y0: Float, y1: Float, vararg colors: Int): Shader =
    LinearGradient(0f, y0, 0f, if (y1 > y0) y1 else y0 + 1f, colors, null, Shader.TileMode.CLAMP)

/** Draws [block] twice: a soft blurred pass for the neon glow, then the crisp shape. */
private inline fun glow(p: Paint, radius: Float, glowAlpha: Int, block: () -> Unit) {
    if (radius > 0f) {
        p.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        p.alpha = glowAlpha
        block()
        p.maskFilter = null
    }
    p.alpha = 255
    block()
}

private fun drawExitArt(
    cv: NativeCanvas, u: Float, p: Paint, path: Path,
    titleFace: Typeface, textFace: Typeface, message: String, progress: Float,
) {
    fun s(v: Float) = v * u
    val w = s(ART_W)
    val h = s(ART_H)

    // --- Card ---
    val card = RectF(s(7f), s(7f), w - s(7f), h - s(7f))
    val radius = s(15f)
    p.reset(); p.isAntiAlias = true
    p.style = Paint.Style.FILL
    p.shader = vGradient(card.top, card.bottom, 0xFF0E0C1C.toInt(), 0xFF07070F.toInt(), 0xFF05050A.toInt())
    cv.drawRoundRect(card, radius, radius, p)

    // Thin frame, pink on the left to cyan on the right, with a soft glow.
    p.style = Paint.Style.STROKE
    p.shader = hGradient(card.left, card.right, PINK, VIOLET, BLUE, CYAN)
    p.strokeWidth = s(1.6f)
    glow(p, s(5f), 150) { cv.drawRoundRect(card, radius, radius, p) }

    // Corner brackets: thick chamfered strokes, pink corners on the left, cyan on the right.
    p.strokeWidth = s(3.6f)
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    val cut = s(13f); val longArm = s(96f); val shortArm = s(46f)
    fun bracket(x: Float, y: Float, dx: Float, dy: Float, color: Int) {
        path.reset()
        path.moveTo(x, y + dy * shortArm)
        path.lineTo(x, y + dy * cut)
        path.lineTo(x + dx * cut, y)
        path.lineTo(x + dx * longArm, y)
        p.shader = null
        p.color = color
        glow(p, s(6f), 200) { cv.drawPath(path, p) }
    }
    bracket(card.left, card.top, 1f, 1f, PINK)
    bracket(card.left, card.bottom, 1f, -1f, PINK)
    bracket(card.right, card.top, -1f, 1f, CYAN)
    bracket(card.right, card.bottom, -1f, -1f, CYAN)

    // --- Gamepad mark (left of the name) ---
    val cx = s(153f); val cy = s(105f)
    p.style = Paint.Style.FILL
    p.strokeCap = Paint.Cap.ROUND
    fun chevron(sign: Float, top: Int, bottom: Int) {
        path.reset()
        path.moveTo(cx + sign * s(24f), cy - s(32f))
        path.lineTo(cx + sign * s(49f), cy)
        path.lineTo(cx + sign * s(24f), cy + s(32f))
        path.lineTo(cx + sign * s(11f), cy + s(32f))
        path.lineTo(cx + sign * s(35f), cy)
        path.lineTo(cx + sign * s(11f), cy - s(32f))
        path.close()
        p.shader = vGradient(cy - s(32f), cy + s(32f), top, bottom)
        glow(p, s(5f), 170) { cv.drawPath(path, p) }
    }
    chevron(-1f, PINK, VIOLET)
    chevron(1f, ICE, SKY)
    // Controller body outline.
    p.style = Paint.Style.STROKE
    p.strokeWidth = s(3f)
    p.shader = hGradient(cx - s(28f), cx + s(28f), CYAN, BLUE)
    val pad = RectF(cx - s(28f), cy - s(15f), cx + s(28f), cy + s(15f))
    glow(p, s(4f), 170) { cv.drawRoundRect(pad, s(15f), s(15f), p) }
    // D-pad.
    p.shader = null
    p.color = CYAN
    p.strokeWidth = s(3.4f)
    glow(p, s(3f), 160) {
        cv.drawLine(cx - s(19.5f), cy, cx - s(6.5f), cy, p)
        cv.drawLine(cx - s(13f), cy - s(6.5f), cx - s(13f), cy + s(6.5f), p)
    }
    // Face buttons.
    p.color = PINK
    p.strokeWidth = s(1.7f)
    glow(p, s(2.5f), 160) {
        cv.drawCircle(cx + s(13f), cy - s(5.5f), s(2.6f), p)
        cv.drawCircle(cx + s(19f), cy, s(2.6f), p)
        cv.drawCircle(cx + s(13f), cy + s(5.5f), s(2.6f), p)
        cv.drawCircle(cx + s(7f), cy, s(2.6f), p)
    }

    // --- Name ---
    val name = "Bannerlator"
    p.style = Paint.Style.FILL
    p.typeface = titleFace
    p.textAlign = Paint.Align.LEFT
    p.textSize = s(40f)
    p.textSize *= s(214f) / max(1f, p.measureText(name))
    val nameX = s(212f)
    val nameW = p.measureText(name)
    val fm = p.fontMetrics
    val nameBase = cy - (fm.ascent + fm.descent) / 2f
    p.shader = hGradient(nameX, nameX + nameW, PINK, 0xFFC63BFF.toInt(), 0xFF6A5BFF.toInt(), 0xFF2F8CFF.toInt())
    glow(p, s(5f), 140) { cv.drawText(name, nameX, nameBase, p) }

    // --- Message ---
    p.shader = null
    p.color = 0xFFF1F3F8.toInt()
    p.typeface = textFace
    p.textAlign = Paint.Align.CENTER
    p.textSize = s(19f)
    val mfm = p.fontMetrics
    val msgBase = s(170f) - (mfm.ascent + mfm.descent) / 2f
    glow(p, s(4f), 90) { cv.drawText(message, w / 2f, msgBase, p) }

    // --- Bar: an outlined pill track with a glowing gradient fill inside ---
    val bw = s(236f); val bh = s(15f)
    val bx = (w - bw) / 2f; val by = s(205f) - bh / 2f
    val track = RectF(bx, by, bx + bw, by + bh)
    p.textAlign = Paint.Align.LEFT
    p.style = Paint.Style.FILL
    p.color = 0xE0080A17.toInt()
    cv.drawRoundRect(track, bh / 2f, bh / 2f, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = s(1.4f)
    p.shader = hGradient(bx, bx + bw, 0xFF6A4BFF.toInt(), BLUE, CYAN)
    glow(p, s(3.5f), 150) { cv.drawRoundRect(track, bh / 2f, bh / 2f, p) }

    val inset = s(3.2f)
    val innerH = bh - 2f * inset
    val fillW = max(innerH, (bw - 2f * inset) * progress.coerceIn(0f, 1f))
    val fill = RectF(bx + inset, by + inset, bx + inset + fillW, by + bh - inset)
    p.style = Paint.Style.FILL
    p.shader = hGradient(fill.left, fill.right, PINK, VIOLET, BLUE, CYAN)
    glow(p, s(5f), 190) { cv.drawRoundRect(fill, innerH / 2f, innerH / 2f, p) }
    // Gloss on the upper half of the fill.
    p.shader = vGradient(fill.top, fill.centerY(), 0x66FFFFFF, 0x00FFFFFF)
    cv.drawRoundRect(RectF(fill.left + innerH * 0.3f, fill.top + s(0.6f), fill.right - innerH * 0.3f, fill.centerY()),
        innerH / 4f, innerH / 4f, p)
    // Bright head at the leading edge.
    p.shader = null
    p.color = 0xFFB8F4FF.toInt()
    glow(p, s(5f), 200) { cv.drawCircle(fill.right - innerH / 2f, fill.centerY(), innerH * 0.32f, p) }
    p.reset()
}
