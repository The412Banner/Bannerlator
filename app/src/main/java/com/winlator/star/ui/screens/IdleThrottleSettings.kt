package com.winlator.star.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.core.IdleThrottle

// The Classic editors' idle-thread throttle (core.IdleThrottle): one row in the Advanced tab of the container and the game
// settings, and its pop-up with a switch per graphics API (on = Wine may throttle that API's games).

/** "On for all", "Off for all" or "Off: DX7, OpenGL" for a stored value. */
@Composable
private fun idleThrottleSummary(stored: String): String {
    val off = IdleThrottle.parse(stored) ?: IdleThrottle.parse(IdleThrottle.DEFAULT)!!
    return when {
        off.isEmpty() -> stringResource(R.string.idle_throttle_on_all)
        off.size == IdleThrottle.APIS.size -> stringResource(R.string.idle_throttle_off_all)
        else -> stringResource(R.string.idle_throttle_off_list, IdleThrottle.offNames(stored))
    }
}

/**
 * The row: a read-only field with the value; a tap opens the switches, the "?" the explanation. [value] is the stored
 * form ("dx7", "none"); in a game editor [containerValue] is the container's, and a null [value] follows it.
 * [onChange] gets the new stored form, or null for "Use container setting".
 */
@Composable
internal fun IdleThrottleRow(
    value: String?,
    containerValue: String?,
    onChange: (String?) -> Unit,
    onHelp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val game = containerValue != null
    val shown = value ?: containerValue ?: IdleThrottle.DEFAULT
    val summary = idleThrottleSummary(shown)
    val text = if (game && value == null) stringResource(R.string.force_ssbs_container_default, summary) else summary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = text,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.idle_throttle)) },
                trailingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            // The field is read-only: the whole box opens the pop-up.
            Box(Modifier.matchParentSize().clickable { open = true })
        }
        IconButton(onClick = onHelp) {
            Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(18.dp))
        }
    }
    if (open) {
        IdleThrottleDialog(
            initial = shown,
            onUseContainer = if (game && value != null) ({ open = false; onChange(null) }) else null,
            onDismiss = { open = false },
            onSave = { next ->
                open = false
                // Saving what the game already follows keeps it following the container.
                if (!(game && value == null && next == containerValue)) onChange(next)
            },
        )
    }
}

/** A switch per API (on = throttled), "?" in the title, Save / Cancel, and "Use container setting" for a game with its own value. */
@Composable
internal fun IdleThrottleDialog(
    initial: String,
    onUseContainer: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var off by remember { mutableStateOf((IdleThrottle.parse(initial) ?: IdleThrottle.parse(IdleThrottle.DEFAULT)!!).toSet()) }
    var help by remember { mutableStateOf(false) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.idle_throttle), modifier = Modifier.weight(1f))
                IconButton(onClick = { help = true }) {
                    Icon(Icons.Default.Help, contentDescription = "What is this?", modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                IdleThrottle.APIS.forEach { api ->
                    val toggle = { off = if (api in off) off - api else off + api }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 2.dp),
                    ) {
                        Text(IdleThrottle.longName(api), modifier = Modifier.weight(1f))
                        Switch(checked = api !in off, onCheckedChange = { toggle() })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(IdleThrottle.join(off)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (onUseContainer != null) TextButton(onClick = onUseContainer) { Text(stringResource(R.string.idle_throttle_use_container)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    if (help) HelpDialog(R.string.help_idle_throttle) { help = false }
}
