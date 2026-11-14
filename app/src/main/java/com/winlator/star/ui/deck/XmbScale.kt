package com.winlator.star.ui.deck

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import com.winlator.star.ui.theme.AppThemeState
import kotlin.math.min
import kotlin.math.roundToInt

// The XMB interface style ignores the phone's Display size and Font size settings. It is drawn on a fixed scale of its own, so
// the app's 100 % Interface scale and 100 % Font size look the same share of the screen on every phone and tablet.

/** The screen's short side, in dp, at 100 % Interface scale. */
internal const val XMB_REFERENCE_SHORT_SIDE_DP = 393f

// The look the XMB was tuned at: 110 % Interface scale and 105 % Font size on the reference above. That is the XMB's 100 %, so its own
// sliders start at 100 % and these two carry the rest.
internal const val XMB_BASE_UI_BOOST = 1.10f
internal const val XMB_BASE_FONT_BOOST = 1.05f

/**
 * Pixels per dp of the reference scale: the display's short side ([shortSidePx]) is always [XMB_REFERENCE_SHORT_SIDE_DP]. The window's
 * dp ([xmbConfiguration]) are in these, so the sizes laid out from them (ScreenScale) hold their share of the screen.
 */
internal fun xmbBaseDensity(shortSidePx: Int): Float = shortSidePx / XMB_REFERENCE_SHORT_SIDE_DP

/** The XMB's density at its own Interface scale [uiScale] (a share of 100 %); the Deck style then draws [DECK_UI_SCALE] of it. */
internal fun xmbAppDensity(shortSidePx: Int, uiScale: Float): Float = xmbBaseDensity(shortSidePx) * XMB_BASE_UI_BOOST * uiScale

/** The XMB's text scale at its own Font size [fontScale] (a share of 100 %); the phone's Font size plays no part. */
internal fun xmbFontScale(fontScale: Float): Float = XMB_BASE_FONT_BOOST * fontScale

/** [systemDp], a size the phone reports in dp at [systemDensity], as dp at [baseDensity]. */
internal fun xmbDp(systemDp: Int, systemDensity: Float, baseDensity: Float): Int = (systemDp * systemDensity / baseDensity).roundToInt()

/**
 * The short side of the display in px, whatever the window or the orientation: the Display size setting changes the density, never
 * this, and the system bars and the camera hole are inside it.
 */
@Suppress("DEPRECATION")
internal fun xmbShortSidePx(context: Context): Int {
    val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windows.maximumWindowMetrics.bounds
        return min(bounds.width(), bounds.height())
    }
    val size = Point()
    windows.defaultDisplay.getRealSize(size)
    return min(size.x, size.y)
}

/**
 * [system] with its sizes in dp of the XMB ([xmbBaseDensity] of [shortSidePx]) instead of the phone's, and the XMB's text scale
 * [fontScale] instead of the phone's. Everything that lays out by the window's dp (the compact layouts, the shares of the screen)
 * then reads the same window whatever the Display size.
 */
internal fun xmbConfiguration(system: Configuration, shortSidePx: Int, fontScale: Float): Configuration {
    val base = xmbBaseDensity(shortSidePx)
    val systemDensity = system.densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()
    return Configuration(system).apply {
        screenWidthDp = xmbDp(system.screenWidthDp, systemDensity, base)
        screenHeightDp = xmbDp(system.screenHeightDp, systemDensity, base)
        smallestScreenWidthDp = xmbDp(system.smallestScreenWidthDp, systemDensity, base)
        densityDpi = (base * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        this.fontScale = fontScale
    }
}

/** The phone's configuration for [context] as the XMB sees it ([xmbConfiguration]) while the XMB is on, as it is otherwise. */
internal fun xmbConfiguration(context: Context): Configuration {
    val system = context.resources.configuration
    return if (isXmbHome(context)) xmbConfiguration(system, xmbShortSidePx(context), xmbFontScale(AppThemeState.xmbFontScale.value)) else system
}

/**
 * What the XMB is drawn at: [density] is the app's own (its Interface scale and Font size on the fixed scale, before the Deck
 * style's [DECK_UI_SCALE]) and [configuration] the window in dp of that scale.
 */
@Immutable
internal class XmbScale(val density: Density, val configuration: Configuration)

/**
 * Set by WinlatorTheme while the XMB is on, null otherwise. A dialog or popup window starts again from the phone's own density and
 * configuration, so the ones that scale themselves (DeckDialogScaled, DeckTheme) take them from here.
 */
internal val LocalXmbScale = staticCompositionLocalOf<XmbScale?> { null }

/**
 * [rememberXmbHome], but it follows the setting live (the Appearance page switches the style with no restart). The prefs stay the
 * truth, because a screen can be the first one in its process, before AppThemeState is set up.
 */
@Composable
internal fun rememberXmbStyle(): Boolean {
    val context = LocalContext.current
    val live by AppThemeState.deckXmbHome.collectAsState()
    return remember(context, live) { isXmbHome(context) }
}

/** The XMB's [XmbScale] for its own Interface scale [uiScale] and Font size [fontScale] (AppThemeState.xmbUiScale / xmbFontScale), or null while it is off. */
@Composable
internal fun rememberXmbScale(uiScale: Float, fontScale: Float): XmbScale? {
    val xmb = rememberXmbStyle()
    val context = LocalContext.current
    val system = LocalConfiguration.current
    return remember(xmb, context, system, uiScale, fontScale) {
        if (!xmb) return@remember null
        val shortSidePx = xmbShortSidePx(context)
        val text = xmbFontScale(fontScale)
        XmbScale(Density(xmbAppDensity(shortSidePx, uiScale), text), xmbConfiguration(system, shortSidePx, text))
    }
}
