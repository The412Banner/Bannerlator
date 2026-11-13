package com.winlator.star.store.download

import android.content.Intent
import com.winlator.star.store.DownloadManagerActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Reusable ⬇ toolbar action for a store header. Shows a live count badge of active
 * downloads (QUEUED/DOWNLOADING/PAUSED) collected from [DownloadRegistry.activeCount];
 * the badge hides entirely at 0. Tapping opens the cross-store [DownloadManagerActivity].
 *
 * Self-contained (grabs its own [LocalContext] + launches the Activity) so a header just
 * drops `DownloadsButton()` into its Row — matching the other single-line IconButtons in
 * the Steam headers.
 */
@Composable
fun DownloadsButton(modifier: Modifier = Modifier) {
    com.winlator.star.store.StoreGlassIconButton(
        onClick = rememberOpenDownloads(),
        modifier = modifier,
        ink = false,
    ) {
        DownloadsIcon(MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * The Downloads button of a game's page, drawn as the page's Settings button is ([StoreActionTile]) with the same [shape], [fill]
 * and [line]; [tint] colours the icon. Opens the same Downloads page as [DownloadsButton]. [entry] marks the button the page
 * focuses on open.
 */
@Composable
internal fun StoreDownloadsTile(
    shape: Shape,
    fill: Color,
    line: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
) {
    StoreActionTile(onClick = rememberOpenDownloads(), shape = shape, fill = fill, line = line, modifier = modifier, entry = entry) {
        DownloadsIcon(tint)
    }
}

@Composable
private fun rememberOpenDownloads(): () -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { ctx.startActivity(Intent(ctx, DownloadManagerActivity::class.java)) } }
}

/** The ⬇ icon with the count of the active downloads on it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsIcon(tint: Color) {
    val activeCount by DownloadRegistry.activeCount.collectAsStateWithLifecycle()
    BadgedBox(
        badge = {
            if (activeCount > 0) {
                Badge { Text(activeCount.toString()) }
            }
        },
    ) {
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = "Downloads & Library",
            tint = tint,
        )
    }
}
