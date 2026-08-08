package com.winlator.star.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R

/**
 * Informational dialog — a single "Got it" acknowledgement, NOT a hard gate. Used to explain what a
 * watchdog preset does and the actual °C it resolves to on this device.
 */
@Composable
fun PerfInfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                // Bounded + scrollable so long explainers (watchdog, the consolidated toggle list) fit.
                Text(
                    body, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.perf_got_it)) }
                }
            }
        }
    }
}

/**
 * Hard-gate disclaimer: a scrollable risk statement whose confirm button stays disabled until the
 * user has BOTH scrolled to the bottom AND ticked "I understand and accept all risk". Reused for the
 * root grant gate and the watchdog-disable gate.
 */
@Composable
fun PerfDisclaimerDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    var accepted by remember { mutableStateOf(false) }
    // Content fitting without a scrollbar (maxValue 0) counts as "seen in full"; otherwise require the
    // scroll to actually reach the bottom.
    val reachedBottom by remember {
        derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8 }
    }
    val canConfirm = reachedBottom && accepted

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)

                Text(
                    body,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(scroll)
                )

                if (!reachedBottom) {
                    Text(stringResource(R.string.perf_disclaimer_scroll_to_end),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = reachedBottom)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.perf_disclaimer_accept_risk),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                }

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (canConfirm) { onConfirm(); } }, enabled = canConfirm) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}
