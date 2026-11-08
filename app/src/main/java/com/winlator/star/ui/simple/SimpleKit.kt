package com.winlator.star.ui.simple

import com.winlator.star.ui.theme.AppThemeState

import android.content.Context
import android.os.BatteryManager
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.DeckGlyph
import com.winlator.star.ui.deck.GlyphKind
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.delay
import java.util.Date

// The Simple look: hard corners, layered shadows, light ink on the wallpaper. Colours that carry meaning (the
// accent, errors) come from the theme so the user's preset and custom accent apply here too.

internal val SimpleShape = RoundedCornerShape(3.dp)
internal val SimpleChipShape = RoundedCornerShape(2.dp)

// Lab (the Simple style's successor) has a dark and a light look (AppThemeState.labLight). These read it, so a composable
// that uses them recomposes when the look changes; the accent stays the theme's.
private val light: Boolean get() = AppThemeState.labLight
internal val SimpleInk: Color get() = if (light) Color(0xFF111722) else Color(0xFFEEF1F6)
/** Titles and focused text: white on the dark look, near-black on the light one. */
internal val SimpleStrong: Color get() = if (light) Color(0xFF0B0F17) else Color.White
internal val SimpleMuted: Color get() = if (light) Color(0xFF4A5566) else Color(0xFFA9B2C1)
internal val SimpleDim: Color get() = if (light) Color(0xFF8A94A5) else Color(0xFF6A7383)
internal val SimplePanelTop: Color get() = (if (light) Color(0xF2FFFFFF) else Color(0xF0171A23)).let { c -> LabArt.panelAlpha?.let { c.copy(alpha = it) } ?: c }
internal val SimplePanelBottom: Color get() = (if (light) Color(0xEEF6F8FB) else Color(0xF20E1016)).let { c -> LabArt.panelAlpha?.let { c.copy(alpha = it + 0.02f) } ?: c }
/** Over game art (Panels over art: Glass / Clear) the Lab panels let it through; Solid keeps them as they are. */
internal val SimpleLine: Color get() = if (light) Color(0x1F111722) else Color(0x1AFFFFFF)

/** The faint line between two options of a list: the row line colour, fading out at both ends. */
@Composable
internal fun SimpleRowDivider(modifier: Modifier = Modifier) {
    val line = SimpleLine
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, line, line, Color.Transparent))),
    )
}
/** A faint fill for cards on a panel. */
internal val SimpleCard: Color get() = if (light) Color(0x0D111722) else Color(0x0DFFFFFF)

internal val SimplePanelBrush: Brush get() = Brush.verticalGradient(listOf(SimplePanelTop, SimplePanelBottom))

/** Depth under a panel or tile: a soft drop shadow, deeper for things that float above the rest. */
internal fun Modifier.simpleDepth(high: Boolean = false, shape: Shape = SimpleShape): Modifier =
    this.shadow(if (high) 22.dp else 9.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)

/** What one controller press means on the Simple home. */
internal enum class SIn { UP, DOWN, LEFT, RIGHT, A, B, X, Y, LB, RB, LT, RT, START, SELECT }

/**
 * The Simple meaning of a key, or null for keys it leaves alone. A held direction repeats (Android sends repeats and
 * synthesizes D-pad keys from the left stick); every other button acts on its first press only.
 */
internal fun simpleInputOf(e: KeyEvent): SIn? {
    if (e.action != KeyEvent.ACTION_DOWN) return null
    val dir = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> SIn.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> SIn.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> SIn.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> SIn.RIGHT
        else -> null
    }
    if (dir != null) return dir
    if (e.repeatCount > 0) return null
    return when (e.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SIn.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> SIn.B
        KeyEvent.KEYCODE_BUTTON_X -> SIn.X
        KeyEvent.KEYCODE_BUTTON_Y -> SIn.Y
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_LEFT_BRACKET -> SIn.LB
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_RIGHT_BRACKET -> SIn.RB
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_PAGE_UP -> SIn.LT
        KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_PAGE_DOWN -> SIn.RT
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> SIn.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> SIn.SELECT
        else -> null
    }
}

/** A controller glyph by its label: A/B/X/Y round, bumpers and triggers as keycaps, anything else plain. */
@Composable
internal fun SimpleGlyph(label: String) {
    val kind = when (label) {
        "A" -> GlyphKind.A
        "B" -> GlyphKind.B
        "X" -> GlyphKind.X
        "Y" -> GlyphKind.Y
        "L1", "R1", "L2", "R2" -> GlyphKind.BUMPER
        else -> GlyphKind.PLAIN
    }
    DeckGlyph(label, kind)
}

/** The one line of button hints at the bottom of each layout. */
@Composable
internal fun SimpleLegend(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        items.forEach { (glyph, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SimpleGlyph(glyph)
                Text(label, color = SimpleMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

internal fun batteryPercent(context: Context): Int = runCatching {
    (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}.getOrDefault(-1)

/** Battery and clock, top right: the Simple home hides Android's status bar. */
@Composable
internal fun SimpleStatus(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val battery = remember(now) { batteryPercent(context) }
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(Date(now)) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (battery >= 0) Text("$battery%", color = SimpleInk.copy(alpha = 0.85f), fontSize = 13.sp)
        Text(time, color = SimpleInk.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "DR" for "DiRT Rally 2.0": the stand-in on a game without cover art. */
internal fun initialsOf(name: String): String =
    name.replace(Regex("[^A-Za-z0-9 ]"), "").split(' ').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

/** A colour pair from the name, so a game without art keeps the same look every time. */
internal fun fallbackBrush(name: String): Brush {
    val h = (name.hashCode() and 0x7fffffff) % 360
    val h2 = (h + 40) % 360
    return Brush.linearGradient(
        listOf(Color.hsv(h.toFloat(), 0.62f, 0.55f), Color.hsv(h2.toFloat(), 0.55f, 0.20f)),
    )
}

/** A game's cover cropped into [modifier]'s box; initials on a colour pair when it has none. */
@Composable
internal fun SimpleCover(
    s: Shortcut,
    modifier: Modifier = Modifier,
    title: Boolean = true,
    monoSize: TextUnit = 46.sp,
) {
    val bmp = s.icon
    Box(modifier = modifier.clip(SimpleShape).background(fallbackBrush(s.name))) {
        if (bmp != null) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(bitmap = img, contentDescription = s.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                initialsOf(s.name),
                color = Color.White.copy(alpha = 0.28f),
                fontFamily = SoraFamily,
                fontWeight = FontWeight.Black,
                fontSize = monoSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (title) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 9.dp),
            ) {
                Text(
                    s.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

/** A small hard-cornered fact: "Turnip r2 AIO", "DXVK", "Wayland". */
@Composable
internal fun SimpleChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .simpleDepth(shape = SimpleChipShape)
            .clip(SimpleChipShape)
            .background(Color(0xC20C0E13))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(text, color = SimpleInk.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1)
    }
}
