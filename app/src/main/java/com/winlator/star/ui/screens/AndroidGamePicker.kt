@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.winlator.star.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "+" → Add games → Add Android game: pick apps installed on this phone to list in the Games tab.
 * Same shape as the games-folder confirm screen (a wide dialog with a checked list, Add N / Cancel),
 * plus a search field and a Games / All apps filter. Nothing is written until Add; [adding] greys the
 * dialog while the caller writes the entries.
 */
@Composable
internal fun AndroidGamePickerDialog(
    alreadyAdded: Set<String>,
    adding: Boolean,
    onDismiss: () -> Unit,
    onAdd: (List<AndroidGames.InstalledApp>) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AndroidGames.InstalledApp>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AndroidGames.listInstalledApps(context.applicationContext) }
    }
    var query by remember { mutableStateOf("") }
    var gamesOnly by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val all = apps.orEmpty()
    val shown = remember(all, query, gamesOnly) {
        val q = query.trim()
        all.filter { app ->
            (!gamesOnly || app.isGame) &&
                (q.isEmpty() || app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
        }
    }
    val chosen = all.filter { it.packageName in selected }

    OutlinedAlertDialog(
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add Android game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search installed apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = gamesOnly, onClick = { gamesOnly = true }, label = { Text("Games") })
                    FilterChip(selected = !gamesOnly, onClick = { gamesOnly = false }, label = { Text("All apps") })
                }
                if (apps == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Reading installed apps…", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val n = shown.size
                    Text(
                        if (gamesOnly) "$n game${if (n == 1) "" else "s"} found (apps Android marks as games)"
                        else "$n app${if (n == 1) "" else "s"} installed",
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (shown.isEmpty() && gamesOnly && query.isBlank()) {
                        Text(
                            "Some games don't tell Android they are games — try All apps.",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            val added = app.packageName in alreadyAdded
                            AndroidAppRow(
                                app = app,
                                checked = app.packageName in selected,
                                alreadyAdded = added,
                                enabled = !added && !adding,
                                onToggle = {
                                    selected = if (app.packageName in selected) selected - app.packageName
                                    else selected + app.packageName
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty() && !adding, onClick = { onAdd(chosen) }) {
                Text(
                    when {
                        adding -> "Adding…"
                        chosen.isEmpty() -> "Add"
                        else -> "Add ${chosen.size}"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !adding, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AndroidAppRow(
    app: AndroidGames.InstalledApp,
    checked: Boolean,
    alreadyAdded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    // Off the main thread: an adaptive icon is rasterised on first use, then served from the cache.
    val icon by produceState<android.graphics.Bitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) { AndroidGames.iconBitmap(context.applicationContext, app.packageName, iconPx) }
    }
    val alpha = if (alreadyAdded) 0.45f else 1f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(enabled = enabled, onClick = onToggle),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .alpha(alpha),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = icon
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(SurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(app.label.take(1).uppercase(), color = OnSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).alpha(alpha)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.isGame) {
                        Spacer(Modifier.width(6.dp))
                        GameTag()
                    }
                }
                Text(
                    if (alreadyAdded) "Already added" else app.packageName,
                    color = OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Checkbox(checked = checked || alreadyAdded, onCheckedChange = { onToggle() }, enabled = enabled)
        }
    }
}

/** Small "GAME" marker on picker rows Android classes as games. */
@Composable
private fun GameTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1F1F1F))
            .border(1.dp, Color(0xFF2C3A31), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text("GAME", color = Color(0xFF9ADBB8), style = MaterialTheme.typography.labelSmall)
    }
}
