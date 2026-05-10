package com.winlator.star.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.star.ui.theme.WinlatorTheme

/**
 * Attaches a full-screen Compose PreloaderOverlay to the decor view of any AppCompatActivity.
 * Call this once from onCreate() after setContentView().
 * Works in both Compose-hosted and traditional View-based activities.
 */
object PreloaderOverlayHelper {

    @JvmStatic
    fun attach(activity: AppCompatActivity) {
        val overlay = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinlatorTheme {
                    PreloaderOverlay()
                }
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        (activity.window.decorView as ViewGroup).addView(overlay, params)
    }
}
