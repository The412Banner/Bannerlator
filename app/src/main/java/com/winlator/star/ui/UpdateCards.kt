package com.winlator.star.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.BuildConfig
import com.winlator.star.core.StringUtils
import com.winlator.star.core.UpdateManager
import com.winlator.star.core.UpdateManager.DownloadState
import com.winlator.star.core.UpdateManager.UpdateInfo
import java.util.Locale

/*
 * The in-app update UI, three pieces that share one look (rounded, accent outline, raised card):
 *
 *  1. [UpdatePill]            — slides out of the right edge under the top bar when a newer
 *                                release is found. "Update available", Dismiss / View.
 *  2. [UpdateHighlightsDialog] — the quick highlights of the release (five short lines, not the
 *                                GitHub page). Later / Update now.
 *  3. [UpdateDownloadCard]     — progress with size, speed and time left, Cancel; Retry on failure.
 *                                Driven by [UpdateManager.download], hosted once by [UpdateHost].
 *
 * The download card replaces the first-launch setup screen that updates used to borrow from
 * HttpUtils.download(activity, …); UpdateManager no longer calls that overload at all.
 */

/** Green = install / safe action, the same convention as the Settings and About update buttons. */
private val UpdateGreen = Color(0xFF4CAF50)
private const val SLIDE_MS = 380

/** How long the pill takes to leave the screen; callers wait this long before opening the dialog. */
const val UPDATE_PILL_EXIT_MS = SLIDE_MS.toLong()

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ENGLISH, "%.0f KB", bytes / 1024.0)
}

@Composable
private fun accentBorder(width: Float = 1f) = BorderStroke(width.dp, MaterialTheme.colorScheme.primary)

// ── 1. Pill ────────────────────────────────────────────────────────────────────────────────

/**
 * Slide-in notice that a newer release exists. Place it top-end over the screen content; it
 * animates in from past the right edge and out the same way. [visible] is owned by the caller
 * so Dismiss and View can both slide it out before acting.
 */
@Composable
fun UpdatePill(
    info: UpdateInfo,
    visible: Boolean,
    onDismiss: () -> Unit,
    onView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(tween(SLIDE_MS)) { it + 48 } + fadeIn(tween(SLIDE_MS / 2)),
        exit = slideOutHorizontally(tween(SLIDE_MS)) { it + 48 } + fadeOut(tween(SLIDE_MS / 2)),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = accentBorder(1.5f),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.height(52.dp).padding(start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("Update available", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    val size = fmtSize(info.apkSize)
                    Text(
                        "V ${info.versionName}" + (if (size.isNotEmpty()) " · $size" else ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, maxLines = 1,
                    )
                }
                PillButton("Dismiss", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, onDismiss)
                PillButton("View", UpdateGreen, Color.White, onView)
            }
        }
    }
}

@Composable
private fun PillButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
        shape = RoundedCornerShape(15.dp),
    ) { Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
}

// ── 2. Highlights ──────────────────────────────────────────────────────────────────────────

/**
 * What's new, as the release's quick highlights (title + one sentence each, five at most).
 * Releases cut before highlights existed show their one-line notes instead.
 */
@Composable
fun UpdateHighlightsDialog(info: UpdateInfo, onLater: () -> Unit, onUpdate: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onLater) {
        UpdateCardSurface {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CardHeader(
                    title = "What's new in ${info.versionName}",
                    subtitle = listOf(
                        "V ${BuildConfig.VERSION_NAME} → V ${info.versionName}",
                        fmtSize(info.apkSize),
                        info.publishedAt,
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "HIGHLIGHTS", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (info.highlights.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        info.highlights.forEach { h ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier.padding(top = 5.dp).size(7.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                                Column {
                                    if (h.title.isNotEmpty()) {
                                        Text(h.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    if (h.text.isNotEmpty()) {
                                        Text(h.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        info.notes.ifBlank { "Bug fixes and improvements." },
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, lineHeight = 17.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your games, containers and settings are kept.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { (context as? Activity)?.let { UpdateManager.openReleasesPage(it) } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) { Text("Full notes ↗", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Later", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, onLater, Modifier.weight(1f))
                    CardButton("Update now", UpdateGreen, Color.White, onUpdate, Modifier.weight(1f))
                }
            }
        }
    }
}

// ── 3. Download ────────────────────────────────────────────────────────────────────────────

/**
 * Drop this once into the app shell. It renders the download card whenever an update download is
 * running or has failed, whichever button started it (pill, highlights, Settings or About).
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val state by UpdateManager.download.collectAsState()
    when (val s = state) {
        is DownloadState.Downloading -> UpdateDownloadCard(s)
        is DownloadState.Failed -> UpdateFailedCard(
            s,
            onRetry = {
                UpdateManager.clearDownloadState()
                (context as? Activity)?.let { UpdateManager.downloadAndInstall(it, s.info) {} }
            },
            onReleasePage = {
                UpdateManager.clearDownloadState()
                (context as? Activity)?.let { UpdateManager.openReleasesPage(it) }
            },
            onClose = { UpdateManager.clearDownloadState() },
        )
        else -> {}
    }
}

@Composable
private fun UpdateDownloadCard(s: DownloadState.Downloading) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        UpdateCardSurface {
            Column {
                CardHeader(title = "Updating to V ${s.info.versionName}", subtitle = "Downloading the update…")
                Spacer(Modifier.height(14.dp))
                val known = s.total > 0
                if (known) {
                    LinearProgressIndicator(
                        progress = { (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = UpdateGreen,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = UpdateGreen,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    val done = fmtSize(s.bytes).ifEmpty { "0 MB" }
                    val left = if (known) " of ${fmtSize(s.total)}" else ""
                    val speed = if (s.bytesPerSec > 0) " · ${fmtSize(s.bytesPerSec)}/s" else ""
                    Text(done + left + speed, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (known && s.bytesPerSec > 0) {
                        val remainMs = (s.total - s.bytes) * 1000 / s.bytesPerSec
                        Text("about ${StringUtils.humanDuration(remainMs)} left", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    CardButton("Cancel", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, { UpdateManager.cancelDownload() })
                }
            }
        }
    }
}

@Composable
private fun UpdateFailedCard(s: DownloadState.Failed, onRetry: () -> Unit, onReleasePage: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        UpdateCardSurface {
            Column {
                CardHeader(title = "Update to V ${s.info.versionName} failed", subtitle = s.reason, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Release page", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, onReleasePage, Modifier.weight(1f))
                    CardButton("Retry", UpdateGreen, Color.White, onRetry, Modifier.weight(1f))
                }
            }
        }
    }
}

// ── shared pieces ──────────────────────────────────────────────────────────────────────────

@Composable
private fun UpdateCardSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun CardHeader(title: String, subtitle: String, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(40.dp).background(tint.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SystemUpdate, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CardButton(label: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        modifier = modifier.heightIn(min = 36.dp).height(36.dp),
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
