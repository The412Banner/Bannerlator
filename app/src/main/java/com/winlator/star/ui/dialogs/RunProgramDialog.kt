package com.winlator.star.ui.dialogs

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.core.StorageRoots
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A place the in-game "Run a program" browser starts from. */
private data class RunPlace(val label: String, val dir: File, val icon: ImageVector)

/** Files Windows starts as programs; every other file opens with the program Windows has for its type. */
private val PROGRAM_EXTENSIONS = setOf("exe", "bat", "cmd", "msi", "lnk", "com")

/**
 * The in-game "Run a program" browser: a popup over the running game (it keeps running). It always opens on the
 * device's storage (internal storage, SD cards), then the container's C: drive and the game's folder; every file is
 * listed, and the one picked goes to [XServerDialogState.onRunBrowserPick]. Back goes up a folder, then to the list of
 * places, then closes.
 */
@Composable
fun RunProgramDialog(state: XServerDialogState) {
    val context = LocalContext.current
    val places = remember { runPlaces(context, state.runBrowserDriveC, state.runBrowserGameDir) }
    var place by remember { mutableStateOf<RunPlace?>(null) }
    var dir by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(dir) {
        val d = dir
        entries = if (d == null) emptyList() else withContext(Dispatchers.IO) { listFolder(d) }
    }

    val close = { state.dismiss() }
    val up: () -> Unit = {
        val d = dir
        val p = place
        when {
            d == null -> close()
            p == null || samePath(d, p.dir) || d.parentFile == null -> { dir = null; place = null }
            else -> dir = d.parentFile
        }
    }
    val pick: (File) -> Unit = { f ->
        val callback = state.onRunBrowserPick
        close()
        callback?.invoke(f.absolutePath)
    }

    OutlinedAlertDialog(
        onDismissRequest = up,
        modifier = Modifier.fillMaxWidth(0.72f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column {
                Text("Run a program")
                val d = dir
                val p = place
                if (d != null && p != null) {
                    val rel = d.absolutePath.removePrefix(p.dir.absolutePath).trimStart('/')
                    Text(
                        if (rel.isEmpty()) p.label else p.label + " / " + rel.replace("/", " / "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                val d = dir
                if (d == null) {
                    items(places, key = { it.dir.absolutePath + "|" + it.label }) { p ->
                        RunRow(p.icon, p.label, p.dir.absolutePath) { place = p; dir = p.dir }
                    }
                } else {
                    item(key = "..") { RunRow(Icons.AutoMirrored.Filled.ArrowBack, "Up", null, onClick = up) }
                    items(entries, key = { it.absolutePath }) { f ->
                        when {
                            f.isDirectory -> RunRow(Icons.Filled.Folder, f.name, null) { dir = f }
                            isProgram(f) -> RunRow(Icons.Filled.PlayArrow, f.name, null) { pick(f) }
                            else -> RunRow(Icons.Outlined.Description, f.name, null) { pick(f) }
                        }
                    }
                    if (entries.isEmpty()) item(key = "empty") {
                        Text(
                            "Nothing here",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

@Composable
private fun RunRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Internal storage and SD cards first, then the container's C: drive and the game's folder when known. */
private fun runPlaces(context: Context, driveC: String?, gameDir: String?): List<RunPlace> {
    val out = ArrayList<RunPlace>()
    @Suppress("DEPRECATION")
    out.add(RunPlace("Internal storage", Environment.getExternalStorageDirectory(), Icons.Filled.PhoneAndroid))
    runCatching { StorageRoots.list(context) }.getOrDefault(emptyList())
        .filter { it.removable }
        .forEach { out.add(RunPlace(it.label, it.dir, Icons.Filled.SdStorage)) }
    driveC?.let { File(it) }?.takeIf { it.isDirectory }?.let { out.add(RunPlace("C: drive", it, Icons.Filled.Computer)) }
    gameDir?.let { File(it) }?.takeIf { it.isDirectory }?.let { out.add(RunPlace("Game folder", it, Icons.Filled.SportsEsports)) }
    return out
}

/** Folders first, then files, each by name; hidden entries left out. */
private fun listFolder(dir: File): List<File> {
    val all = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
    val byName = compareBy<File> { it.name.lowercase() }
    return all.filter { it.isDirectory }.sortedWith(byName) + all.filter { !it.isDirectory }.sortedWith(byName)
}

private fun isProgram(f: File): Boolean = f.extension.lowercase() in PROGRAM_EXTENSIONS

private fun samePath(a: File, b: File): Boolean = a.absolutePath.trimEnd('/') == b.absolutePath.trimEnd('/')
