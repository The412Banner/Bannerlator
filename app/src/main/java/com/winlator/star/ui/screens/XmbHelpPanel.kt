package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The XMB's "?" — a help_* string (the same text the pop-up editors' HelpDialog shows) as its own
// scrollable column. ▲▼ scroll, B goes back; touch drags it.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** A column showing [textResId] (a help_* CDATA string) under [title]. */
internal fun xmbHelpMenu(context: Context, scope: CoroutineScope, title: String, textResId: Int): XmbMenu =
    XmbMenu(title, Icons.Filled.HelpOutline, panel = XmbHelpPanel(htmlToAnnotated(context.getString(textResId)), scope))

private class XmbHelpPanel(
    private val text: AnnotatedString,
    private val scope: CoroutineScope,
) : XmbPanel {
    private val scroll = ScrollState(0)
    private var stepPx = 120f

    @Composable
    override fun Content(modifier: Modifier) {
        stepPx = with(LocalDensity.current) { 64.dp.toPx() }
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier
                .clip(shape)
                .background(Color(0xF20B0907))
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text,
                color = Color(0xFFE6E6E6), fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
            )
            Spacer(Modifier.height(6.dp))
            Text("▲▼ scroll · B back", color = Color(0xFF7A7A7A), fontSize = 10.5.sp)
        }
    }

    override fun onKey(key: XmbKey): Boolean = when (key) {
        XmbKey.Up -> { scope.launch { scroll.animateScrollBy(-stepPx) }; true }
        XmbKey.Down -> { scope.launch { scroll.animateScrollBy(stepPx) }; true }
        XmbKey.L1 -> { scope.launch { scroll.animateScrollBy(-stepPx * 4) }; true }
        XmbKey.R1 -> { scope.launch { scroll.animateScrollBy(stepPx * 4) }; true }
        else -> false
    }
}
