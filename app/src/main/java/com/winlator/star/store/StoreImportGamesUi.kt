package com.winlator.star.store

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "+ Import game library" for Epic, GOG and Amazon: pick a folder → its game folders are matched to owned games →
 * the user ticks which to add (and picks the game for folders that did not match) → each is installed into its
 * folder with [StoreFolderImport.adopt] (the store's download keeps what is already there).
 */
@Composable
fun StoreImportGamesFlow(store: StoreInstallLocations.Store, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<StoreFolderImport.Result?>(null) }
    var asked by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(r.data) else null
        if (path == null) onClose() else picked = File(path)
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            pick.launch(InAppFilePicker.buildDirIntent(ctx, "Choose your games folder"))
        }
    }
    val dir = picked ?: return
    LaunchedEffect(dir) {
        result = withContext(Dispatchers.IO) { runCatching { StoreFolderImport.scan(ctx, store, dir) }.getOrNull() }
            ?: StoreFolderImport.Result(emptyList(), emptyList())
    }
    val r = result
    if (r == null) {
        OutlinedAlertDialog(
            onDismissRequest = onClose,
            confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            title = { Text("Import game library") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Looking for your games in ${dir.name}…")
                }
            },
        )
    } else {
        StoreImportList(store, dir, r, onClose)
    }
}

@Composable
private fun StoreImportList(store: StoreInstallLocations.Store, dir: File, r: StoreFolderImport.Result, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // Folder path → chosen game (matched ones start ticked), and which ones are ticked.
    val chosen = remember { mutableStateMapOf<String, StoreFolderImport.Game>().apply { r.found.forEach { f -> f.game?.let { put(f.folder.path, it) } } } }
    val ticked = remember { mutableStateMapOf<String, Boolean>().apply { r.found.forEach { f -> if (f.game != null) put(f.folder.path, true) } } }
    var choosingFor by remember { mutableStateOf<File?>(null) }
    var adding by remember { mutableStateOf(false) }
    val matched = r.found.filter { it.game != null }
    val unmatched = r.found.filter { it.game == null }
    val toAdd = r.found.filter { ticked[it.folder.path] == true && chosen[it.folder.path] != null }

    OutlinedAlertDialog(
        onDismissRequest = { if (!adding) onClose() },
        confirmButton = {
            TextButton(
                enabled = toAdd.isNotEmpty() && !adding,
                onClick = {
                    adding = true
                    val jobs = toAdd.map { it.folder to chosen.getValue(it.folder.path) }
                    Thread {
                        val errors = jobs.mapNotNull { (folder, game) ->
                            StoreFolderImport.adopt(ctx, store, game, folder)?.let { "${game.name}: $it" }
                        }.toMutableList()
                        val ok = jobs.size - errors.size
                        (ctx as? Activity)?.runOnUiThread {
                            val msg = buildString {
                                if (ok > 0) append("Adding $ok game${if (ok == 1) "" else "s"} — see Downloads.")
                                if (errors.isNotEmpty()) append((if (ok > 0) "\n" else "") + errors.joinToString("\n"))
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            onClose()
                        }
                    }.start()
                },
            ) { Text(if (adding) "Adding…" else "Add ${toAdd.size}") }
        },
        dismissButton = { TextButton(enabled = !adding, onClick = onClose) { Text("Cancel") } },
        title = { Text("Import game library") },
        text = {
            if (r.found.isEmpty()) {
                Text("No game folders found in ${dir.name}.")
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    if (matched.isNotEmpty()) item { StoreHeading("Found · ${matched.size}") }
                    items(matched, key = { "m" + it.folder.path }) { f -> StoreFolderRow(f.folder, chosen[f.folder.path], ticked[f.folder.path] == true, { ticked[f.folder.path] = it }, null) }
                    if (unmatched.isNotEmpty()) item { StoreHeading("Not matched · ${unmatched.size}") }
                    items(unmatched, key = { "u" + it.folder.path }) { f ->
                        StoreFolderRow(f.folder, chosen[f.folder.path], ticked[f.folder.path] == true, { ticked[f.folder.path] = it }) { choosingFor = f.folder }
                    }
                }
            }
        },
    )

    choosingFor?.let { folder ->
        val taken = chosen.filterKeys { it != folder.path }.values.map { it.id }.toSet()
        StoreGameChooser(r.games.filter { it.id !in taken }, folder.name, onDismiss = { choosingFor = null }) { g ->
            chosen[folder.path] = g
            ticked[folder.path] = true
            choosingFor = null
        }
    }
}

@Composable
private fun StoreHeading(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun StoreFolderRow(folder: File, game: StoreFolderImport.Game?, on: Boolean, onTick: (Boolean) -> Unit, onChoose: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (game == null) onChoose?.invoke() else onTick(!on) }
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = on && game != null, enabled = game != null, onCheckedChange = { onTick(it) })
        Column(Modifier.weight(1f)) {
            Text(game?.name ?: folder.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (game != null) folder.name else "Choose the game", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onChoose != null && game != null) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onChoose) { Text("Change") }
        }
    }
}

@Composable
private fun StoreGameChooser(games: List<StoreFolderImport.Game>, folderName: String, onDismiss: () -> Unit, onPick: (StoreFolderImport.Game) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, games) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) games else games.filter { g -> q.split(' ').filter { it.isNotBlank() }.all { g.name.lowercase().contains(it) } }
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(folderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search your games") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 6.dp)) {
                    items(shown, key = { it.id }) { g ->
                        Text(
                            g.name,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(g) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
    )
}
