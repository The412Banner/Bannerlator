package com.winlator.star

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * The XMB and Deck layouts are always left-to-right, whatever the phone's language: their row positions use
 * Modifier.offset (which mirrors in a right-to-left layout), so on an Arabic or Hebrew phone the Home row
 * slid off-screen. Only the layout direction is forced. The language is the phone's own and untouched: the
 * process default locale and the resources' locale stay what the system set, so the Lab language, the store
 * languages and the Steam game language behave as before.
 *
 * Layers: `android:supportsRtl="false"` in the manifest (classic Views), [wrap] in the Application and in the
 * activities that host XMB/Deck screens (the Configuration Compose reads), and [ForceLtr] at the Compose theme
 * roots (so nothing mirrors even if a Configuration slips through).
 */
object AppLocale {
    /**
     * [base] with a left-to-right layout direction, for `attachBaseContext`. Falls back to [base] untouched if
     * the system refuses the configuration, so start-up can never fail here.
     *
     * The override is a fresh [Configuration] that sets only the layout direction: a copy of the whole base
     * configuration would pin orientation, screen size and UI mode of this moment as overrides, and the activities
     * that handle those changes themselves (the manifest's configChanges) would then keep reading the old values
     * from their resources. A new Configuration has every field "undefined" (unset), and Android merges only the
     * fields an override sets, so the locale and everything else keep following the system; `fontScale = 0` says
     * so explicitly for the font scale. [Locale.US] is only the left-to-right source for
     * [Configuration.setLayoutDirection], which sets the layout-direction bits and nothing else.
     */
    @JvmStatic
    fun wrap(base: Context): Context {
        return try {
            val config = Configuration()
            config.fontScale = 0f
            config.setLayoutDirection(Locale.US)
            base.createConfigurationContext(config)
        } catch (_: Throwable) {
            base
        }
    }
}

/** Runs [content] in a left-to-right layout direction (see [AppLocale]); dialogs and popups opened inside inherit it. */
@Composable
fun ForceLtr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
