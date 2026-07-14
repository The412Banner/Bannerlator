package com.winlator.star.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.core.Failure
import com.winlator.star.core.Phase
import com.winlator.star.core.PreloaderState

/**
 * Full-screen dark overlay that mirrors the launch pipeline: a determinate step bar over the
 * measurable app-side setup, an indeterminate spinner for the guest-boot tail, not-frozen hints,
 * and a failure card. Shows/hides based on PreloaderState.ui.
 * Place at the top of the host Compose hierarchy so it covers everything.
 */
@Composable
fun PreloaderOverlay() {
    val state by PreloaderState.ui.collectAsState()
    val ui = state ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 8.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
            ) {
                // Icon (or neutral placeholder) — only for a real launch, not the bare spinner reuse.
                if (ui.title.isNotEmpty() || ui.icon != null) {
                    val icon = ui.icon
                    if (icon != null) {
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                if (ui.title.isNotEmpty()) {
                    Text(
                        text = "Launching ${ui.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                when (ui.phase) {
                    Phase.SETUP -> {
                        if (ui.stepLabel.isNotEmpty()) {
                            Text(
                                text = ui.stepLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (ui.stepTotal <= 0) 0f
                                else (ui.stepIndex.toFloat() / ui.stepTotal).coerceIn(0f, 1f)
                            },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            modifier = Modifier
                                .width(220.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${ui.stepIndex} / ${ui.stepTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Phase.GUEST -> {
                        if (ui.tailLabel.isNotEmpty()) {
                            Text(
                                text = ui.tailLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                    }

                    Phase.FAILED -> FailureCard(ui.failure)
                }

                // Not-frozen reassurance line (SETUP/GUEST only; the failure card is self-contained).
                if (ui.phase != Phase.FAILED) {
                    AnimatedVisibility(visible = ui.hint != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = ui.hint ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureCard(failure: Failure?) {
    failure ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(
                text = "Failed · ${failure.stage}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = failure.what,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!failure.detail.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = failure.detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (failure.loggingEnabled && !failure.logDir.isNullOrEmpty()) {
            Text(
                text = "Log saved to ${failure.logDir}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { PreloaderState.onOpenLog?.run() }) {
                    Text("Open log folder")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { PreloaderState.onClose?.run() }) {
                    Text("Close")
                }
            }
        } else {
            Text(
                text = "Enable logging in Settings → Logs and relaunch to capture the cause.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { PreloaderState.onClose?.run() }) {
                Text("Close")
            }
        }
    }
}
