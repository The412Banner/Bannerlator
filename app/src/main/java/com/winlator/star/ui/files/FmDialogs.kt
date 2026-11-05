package com.winlator.star.ui.files

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.container.Container
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** What to do when a pasted item already exists. Overwrite and Merge run the same copy (files vs folders). */
enum class ConflictChoice { OVERWRITE, MERGE, KEEP_BOTH, SKIP }

/** A one-line name prompt (new folder, new file, rename, archive name). */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && !name.contains('/') && name != "." && name != ".."
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DeleteDialog(names: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (names.size == 1) "Delete \"${names[0]}\"?" else "Delete ${names.size} items?") },
        text = {
            Column {
                Text("This can't be undone.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                if (names.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    names.take(5).forEach { Text("• $it", color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (names.size > 5) Text("…and ${names.size - 5} more", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = cs.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks what to do with one existing item; [batch] shows "apply to all". */
@Composable
internal fun ConflictDialog(name: String, isDir: Boolean, batch: Boolean, onAnswer: (ConflictChoice?, Boolean) -> Unit) {
    var all by remember { mutableStateOf(false) }
    OutlinedAlertDialog(
        onDismissRequest = { onAnswer(null, false) },
        title = { Text("\"$name\" already exists") },
        text = {
            Column {
                if (batch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = all, onCheckedChange = { all = it })
                        Text("Apply to all", fontSize = 13.sp)
                    }
                }
                listOf(
                    (if (isDir) ConflictChoice.MERGE else ConflictChoice.OVERWRITE) to (if (isDir) "Merge" else "Overwrite"),
                    ConflictChoice.KEEP_BOTH to "Keep both",
                    ConflictChoice.SKIP to "Skip",
                ).forEach { (choice, label) ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { onAnswer(choice, all) }) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onAnswer(null, false) }) { Text("Cancel") } },
    )
}

@Composable
internal fun ContainerChoiceDialog(title: String, containers: List<Container>, onDismiss: () -> Unit, onPick: (Container) -> Unit) {
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                containers.forEach { c ->
                    Text(
                        c.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PasswordDialog(archiveName: String, wrong: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(archiveName) },
        text = {
            Column {
                if (wrong) Text("Wrong password", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { onConfirm(pw) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

enum class PackFormat(val label: String, val ext: String, val sevenZipType: String) {
    ZIP("ZIP", "zip", "zip"),
    SEVEN_Z("7z", "7z", "7z"),
    TAR_ZST("tar.zst", "tar.zst", "tar"),
}

/** Name and format for a new archive of the selection. */
@Composable
internal fun CompressDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (String, PackFormat) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf(PackFormat.ZIP) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    suffix = { Text(".${format.ext}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PackFormat.entries.filter { it != PackFormat.TAR_ZST }.forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { format = f },
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(f.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = { onConfirm(name.trim(), format) }) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Where to put entries taken out of an archive. */
internal enum class ExtractTarget { NEXT_TO_ARCHIVE, NEW_FOLDER, OTHER_PANE, CHOOSE }

@Composable
internal fun ExtractDialog(
    archiveName: String,
    count: Int,
    otherPane: File?,
    onDismiss: () -> Unit,
    onPick: (ExtractTarget) -> Unit,
) {
    val folderName = archiveName.substringBeforeLast('.').ifBlank { archiveName }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 0) "Extract all" else "Extract $count ${if (count == 1) "item" else "items"}") },
        text = {
            Column {
                @Composable
                fun option(label: String, target: ExtractTarget) {
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(target) }.padding(vertical = 10.dp),
                    )
                }
                option("Here, next to $archiveName", ExtractTarget.NEXT_TO_ARCHIVE)
                option("Into a new folder \"$folderName\"", ExtractTarget.NEW_FOLDER)
                if (otherPane != null) option("To the other pane (${otherPane.name.ifBlank { otherPane.absolutePath }})", ExtractTarget.OTHER_PANE)
                option("Choose a folder…", ExtractTarget.CHOOSE)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PropLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(84.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Properties of a real file or folder: sizes, dates, and the Windows Read-only / Hidden flags. */
@Composable
internal fun DiskPropertiesDialog(file: File, location: FavLocation?, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attrs by remember(file.absolutePath) { mutableStateOf<FileAttrState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tree by remember(file.absolutePath) { mutableStateOf<Pair<Long, Int>?>(null) }
    var created by remember(file.absolutePath) { mutableStateOf(0L) }
    LaunchedEffect(file.absolutePath) {
        attrs = withContext(Dispatchers.IO) { readFileAttrs(file) }
        created = withContext(Dispatchers.IO) {
            runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis() }.getOrDefault(0L)
        }
        if (file.isDirectory) tree = withContext(Dispatchers.IO) { treeSize(file) { !isActive } }
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name.ifBlank { file.absolutePath }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PropLine("Location", file.parent ?: "—")
                if (location != null && location.storage == FavStorage.CONTAINER) {
                    PropLine("In Windows", (location.containerName?.let { "$it · " } ?: "") + location.displayPath)
                }
                PropLine("Type", if (file.isDirectory) "Folder" else file.extension.uppercase().ifBlank { "File" }.let { if (it == "File") it else "$it file" })
                if (file.isDirectory) {
                    val t = tree
                    PropLine("Size", if (t == null) "Counting…" else "${StringUtils.formatBytes(t.first)} · ${t.second} files")
                } else {
                    PropLine("Size", "${StringUtils.formatBytes(file.length())} (${"%,d".format(file.length())} bytes)")
                }
                PropLine("Modified", fmDateTime(file.lastModified()))
                if (created > 0 && created != file.lastModified()) PropLine("Created", fmDateTime(created))
                PropLine("Access", buildString {
                    append(if (file.canRead()) "Read" else "No read")
                    append(", ")
                    append(if (file.canWrite()) "write" else "no write")
                })
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                val st = attrs
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Read-only", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = st?.readOnly == true,
                        enabled = st != null && !busy,
                        onCheckedChange = { want ->
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { setReadOnly(file, want) }
                                busy = false
                                if (ok) { attrs = attrs?.copy(readOnly = want); onChanged() }
                                else Toast.makeText(context, "Couldn't change Read-only", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Hidden", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = st?.hidden == true,
                        enabled = st != null && st.hiddenSupported && !busy,
                        onCheckedChange = { want ->
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { setHidden(file, want) }
                                busy = false
                                if (ok) { attrs = attrs?.copy(hidden = want); onChanged() }
                                else {
                                    attrs = attrs?.copy(hiddenSupported = false)
                                    Toast.makeText(context, "This storage can't keep the Hidden flag", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Properties of an entry inside an archive. */
@Composable
internal fun EntryPropertiesDialog(item: FmItem.Entry, onDismiss: () -> Unit) {
    val e = item.entry
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PropLine("Archive", item.archive.name)
                PropLine("Path", e.path)
                PropLine("Type", if (e.isDir) "Folder" else e.name.substringAfterLast('.', "").uppercase().ifBlank { "File" })
                PropLine("Size", StringUtils.formatBytes(item.size))
                if (e.packedSize >= 0 && !e.isDir) PropLine("Packed", StringUtils.formatBytes(e.packedSize))
                PropLine("Modified", fmDateTime(e.modified))
                e.method?.let { PropLine("Method", it) }
                e.crc?.let { PropLine("CRC", it) }
                if (e.encrypted) PropLine("Encrypted", "Yes")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Sort settings: folders and files each have their own order. */
@Composable
internal fun SortDialog(sort: FmSort, onDismiss: () -> Unit, onApply: (FmSort) -> Unit) {
    var s by remember { mutableStateOf(sort) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Folders", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                listOf(FmSortKey.NAME, FmSortKey.DATE).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { s = s.copy(folders = k) }) {
                        RadioButton(selected = s.folders == k, onClick = { s = s.copy(folders = k) })
                        Text(k.label())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { s = s.copy(foldersDesc = !s.foldersDesc) }) {
                    Checkbox(checked = s.foldersDesc, onCheckedChange = { s = s.copy(foldersDesc = it) })
                    Text("Reverse")
                }
                Spacer(Modifier.height(8.dp))
                Text("Files", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FmSortKey.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { s = s.copy(files = k) }) {
                        RadioButton(selected = s.files == k, onClick = { s = s.copy(files = k) })
                        Text(k.label())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { s = s.copy(filesDesc = !s.filesDesc) }) {
                    Checkbox(checked = s.filesDesc, onCheckedChange = { s = s.copy(filesDesc = it) })
                    Text("Reverse")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(s) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
