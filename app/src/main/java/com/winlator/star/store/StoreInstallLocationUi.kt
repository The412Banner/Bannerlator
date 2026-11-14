package com.winlator.star.store

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Download to": Internal storage, each SD card / USB drive found, the user's own folders and "+ Custom folder…"
 * (the in-app file manager), with "Use as default". [onPick] gets the chosen place; the dialog closes itself.
 */
@Composable
fun StoreInstallLocationDialog(
    store: StoreInstallLocations.Store,
    onDismiss: () -> Unit,
    onPick: (StoreInstallLocations.Location) -> Unit,
) {
    val ctx = LocalContext.current
    var locations by remember { mutableStateOf<List<StoreInstallLocations.Location>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var defaultId by remember { mutableStateOf(StoreInstallLocations.ID_INTERNAL) }
    var makeDefault by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var wantId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        val list = withContext(Dispatchers.IO) { StoreInstallLocations.locations(ctx, store) }
        val def = withContext(Dispatchers.IO) { StoreInstallLocations.defaultId(ctx, store, list) }
        locations = list
        defaultId = def
        selected = wantId?.takeIf { id -> list.any { it.id == id } } ?: selected?.takeIf { id -> list.any { it.id == id } } ?: def
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(r.data) else null
        if (path != null) {
            val err = StoreInstallLocations.addCustom(ctx, path)
            if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
            else { wantId = StoreInstallLocations.customId(path); reload++ }
        }
    }
    val list = locations
    val chosen = list?.firstOrNull { it.id == selected }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = chosen != null && chosen.available,
                onClick = {
                    val c = chosen ?: return@TextButton
                    if (makeDefault || c.id == defaultId) StoreInstallLocations.setDefault(ctx, store, c.id)
                    onPick(c)
                },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Download to") },
        text = {
            if (list == null) {
                CircularProgressIndicator()
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    for (l in list) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = l.available) { selected = l.id },
                        ) {
                            RadioButton(selected = l.id == selected, enabled = l.available, onClick = { selected = l.id })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    l.label + if (l.id == defaultId) "  · Default" else "",
                                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (l.available) "${SteamSdInstall.fmtBytes(l.freeBytes)} free · ${l.base.path}" else "Not available",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    TextButton(onClick = { pick.launch(InAppFilePicker.buildDirIntent(ctx, "Choose a download folder")) }) {
                        Text("+ Custom folder…")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = selected != defaultId) { makeDefault = !makeDefault }.padding(top = 4.dp),
                    ) {
                        Checkbox(checked = makeDefault || selected == defaultId, enabled = selected != defaultId, onCheckedChange = { makeDefault = it })
                        Text("Use as default", fontSize = 14.sp)
                    }
                }
            }
        },
    )
}
