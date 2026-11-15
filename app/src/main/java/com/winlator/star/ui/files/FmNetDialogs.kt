package com.winlator.star.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.core.StringUtils
import com.winlator.star.netdrive.NetDrive
import com.winlator.star.netdrive.NetKind
import com.winlator.star.ui.screens.OutlinedAlertDialog

/** The form's fields as typed (port as text), turned back into a drive by [toDrive]. */
internal class NetForm(d: NetDrive) {
    val id = d.id
    var kind by mutableStateOf(d.kind)
    var name by mutableStateOf(d.name)
    var host by mutableStateOf(d.host)
    var port by mutableStateOf(if (d.port > 0) d.port.toString() else "")
    var folder by mutableStateOf(d.folder)
    var user by mutableStateOf(d.user)
    var password by mutableStateOf(d.password)
    var https by mutableStateOf(d.https)

    val valid: Boolean
        get() = host.isNotBlank() && !host.contains('/') && !host.contains(' ') && (port.isBlank() || port.toIntOrNull()?.let { it in 1..65535 } == true) &&
            (kind != NetKind.SFTP || user.isNotBlank())

    fun toDrive() = NetDrive(
        id = id, kind = kind, name = name.trim(), host = host.trim(), port = port.trim().toIntOrNull() ?: 0,
        user = user.trim(), password = password, folder = folder.trim().trim('/'), https = https && kind == NetKind.WEBDAV,
    )

    /** What the user name field says when left blank. */
    val userHint: String get() = when (kind) {
        NetKind.SMB -> "Blank = guest"
        NetKind.FTP -> "Blank = anonymous"
        NetKind.WEBDAV -> "Blank = none"
        NetKind.SFTP -> "Needed"
    }

    val folderLabel: String get() = if (kind == NetKind.SMB) "Share / folder" else "Folder"
}

/** Classic's add / edit / log-in form for a network drive. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NetDriveDialog(initial: NetDrive, isNew: Boolean, testing: Boolean, error: String?, onDismiss: () -> Unit, onSave: (NetDrive, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val f = remember(initial.id) { NetForm(initial) }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add network drive" else "Edit ${initial.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth()) {
                    NetKind.values().forEach { k ->
                        FilterChip(selected = f.kind == k, onClick = { f.kind = k }, label = { Text(k.short, fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                NetField(f.name, { f.name = it }, "Name", placeholder = f.host.ifBlank { "My NAS" })
                NetField(f.host, { f.host = it }, "Address", placeholder = "192.168.1.20 or nas.local")
                NetField(f.port, { f.port = it.filter(Char::isDigit).take(5) }, "Port", placeholder = (if (f.kind == NetKind.WEBDAV && f.https) 443 else f.kind.defaultPort).toString(), number = true)
                NetField(f.folder, { f.folder = it }, f.folderLabel, placeholder = "Blank = the top")
                NetField(f.user, { f.user = it }, "User name", placeholder = f.userHint)
                NetField(f.password, { f.password = it }, "Password", password = true)
                if (f.kind == NetKind.WEBDAV) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("https", modifier = Modifier.weight(1f))
                        Switch(checked = f.https, onCheckedChange = { f.https = it })
                    }
                }
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = cs.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Row {
                if (error != null && !testing) TextButton(onClick = { onSave(f.toDrive(), true) }, enabled = f.valid) { Text("Save anyway") }
                TextButton(onClick = { onSave(f.toDrive(), false) }, enabled = f.valid && !testing) { Text(if (error != null) "Try again" else "Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NetField(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null, password: Boolean = false, number: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { p -> { Text(p) } },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = when {
            password -> KeyboardOptions(keyboardType = KeyboardType.Password)
            number -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** Properties of a file or folder on a network drive. */
@Composable
internal fun NetPropertiesDialog(item: FmItem.Net, onDismiss: () -> Unit) {
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((k, v) in netProps(item)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                        Text(v, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The lines both looks show for a network item. */
internal fun netProps(item: FmItem.Net): List<Pair<String, String>> = buildList {
    val d = item.drive
    add("Drive" to d.name)
    add("Server" to "${d.kind.short} · ${d.host}" + (if (d.port > 0) ":${d.port}" else ""))
    add("Path" to listOf(d.folder.trim('/'), item.path).filter { it.isNotEmpty() }.joinToString("/"))
    add("Type" to if (item.isDir) "Folder" else item.name.substringAfterLast('.', "").uppercase().ifBlank { "File" })
    if (!item.isDir) add("Size" to StringUtils.formatBytes(item.size))
    add("Modified" to fmDateTime(item.modified))
}

/** The size line of the Add to Games dialog. */
internal fun addSizeText(size: Long?): String = when {
    size == null -> "Measuring…"
    size < 0 -> "Size unknown"
    else -> StringUtils.formatBytes(size)
}

/** Classic's Add to Games: which folder is the game, where it is copied to, which container gets it. */
@Composable
internal fun NetAddGameDialog(net: FmNetOps, req: FmNetOps.AddReq, containers: List<com.winlator.star.container.Container>, chooseFolder: (String, (java.io.File) -> Unit) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val targets = remember { net.addTargets() }
    @Composable
    fun Choice(selected: Boolean, text: String, sub: String? = null, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    @Composable
    fun Heading(text: String) = Text(text, color = cs.primary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    OutlinedAlertDialog(
        onDismissRequest = { net.addReq = null },
        title = { Text("Add to Games") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Where the game goes comes first (it was below the folder levels, off screen: user 11-15).
                Heading("How")
                Choice(!req.stream, "Copy to this device", "Plays without the network") { req.stream = false }
                Choice(req.stream, "Stream from the network (test)", "Nothing copied first: parts are fetched as the game reads them; the drive must stay on") { req.stream = true }
                if (!req.stream) {
                    Heading("Copy to")
                    targets.forEach { (label, dir) ->
                        Choice(req.target == dir, "$label · Winlator/Games", "${StringUtils.formatBytes(net.freeSpace(dir))} free") { req.target = dir }
                    }
                    val custom = req.target?.takeIf { t -> targets.none { it.second == t } }
                    if (custom != null) Choice(true, custom.name.ifBlank { custom.absolutePath }, "${custom.absolutePath} · ${StringUtils.formatBytes(net.freeSpace(custom))} free") { }
                    TextButton(onClick = { chooseFolder("Copy the game to") { req.target = it } }) { Text("Choose a folder…") }
                } else {
                    Heading("Keep on this device")
                    com.winlator.star.netdrive.NetStream.Mode.values().forEach { m ->
                        Choice(req.streamMode == m, m.label, streamModeHint(m)) { req.streamMode = m }
                    }
                    Text("Kept in app storage (internal): ${StringUtils.formatBytes(net.appFree())} free", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Heading("Game folder")
                Choice(true, req.name, addSizeText(req.size) + if (req.levels.size > 1) (if (req.levelsOpen) " · tap to close" else " · tap to change") else "") {
                    if (req.levels.size > 1) req.levelsOpen = !req.levelsOpen
                }
                if (req.levelsOpen) {
                    req.levels.forEach { lv ->
                        Choice(req.level == lv, com.winlator.star.netdrive.NetFs.nameOf(lv).ifEmpty { req.drive.name }, lv.ifEmpty { req.drive.name }) {
                            if (req.level != lv) { req.level = lv; req.measure() }
                            req.levelsOpen = false
                        }
                    }
                }
                // Which exe Games starts: the best one is picked (the game over a launcher); the user can change it.
                Heading("Game program")
                val exes = req.exes
                val pick = req.exePick
                when {
                    exes == null -> Text("Looking for the game's program…", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    exes.isEmpty() || pick == null -> Text("No .exe found in this folder", color = cs.error, fontSize = 12.sp)
                    else -> {
                        val sz = exes.firstOrNull { it.first == pick }?.second ?: 0L
                        Choice(true, pick.substringAfterLast('/'), "$pick · ${StringUtils.formatBytes(sz)}" + if (exes.size > 1) (if (req.exesOpen) " · tap to close" else " · tap to change") else "") {
                            if (exes.size > 1) req.exesOpen = !req.exesOpen
                        }
                        if (req.exesOpen) exes.forEach { (rel, size) ->
                            Choice(rel == pick, rel.substringAfterLast('/'), "$rel · ${StringUtils.formatBytes(size)}") {
                                req.exePick = rel
                                req.exesOpen = false
                            }
                        }
                    }
                }
                if (containers.size > 1) {
                    Heading("Container")
                    containers.forEach { c -> Choice(req.container == c, c.name) { req.container = c } }
                }
                val t = req.target
                val sz = req.size
                if (t != null && !req.stream) {
                    Text("${t.absolutePath}/${req.name}", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    if (sz != null && sz > 0 && sz > net.freeSpace(t)) Text("Not enough free space there", color = cs.error, fontSize = 13.sp)
                }
                if (containers.isEmpty()) Text("No container yet: create one first", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { net.confirmAdd(req) }, enabled = (req.stream || req.target != null) && req.container != null) { Text(if (req.stream) "Add" else "Copy and add") } },
        dismissButton = { TextButton(onClick = { net.addReq = null }) { Text("Cancel") } },
    )
}

/** The second line of a "Keep on this device" choice. */
internal fun streamModeHint(m: com.winlator.star.netdrive.NetStream.Mode): String = when (m) {
    com.winlator.star.netdrive.NetStream.Mode.KEEP -> "What it loads stays, up to the size limit"
    com.winlator.star.netdrive.NetStream.Mode.STREAM -> "Emptied when the game closes"
    com.winlator.star.netdrive.NetStream.Mode.FULL -> "Fetch all of it now and keep it"
}

/** Classic's "Streamed games": space used and the limit, how each game is kept, empty / remove, free up space. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NetStreamsDialog(net: FmNetOps) {
    val cs = MaterialTheme.colorScheme
    val hub = com.winlator.star.netdrive.NetHub
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val limit = com.winlator.star.netdrive.NetStream.limit(ctx)
    @Composable
    fun Choice(selected: Boolean, text: String, sub: String? = null, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(text, fontSize = 14.sp)
                if (sub != null) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
    OutlinedAlertDialog(
        onDismissRequest = { net.streamsOpen = false },
        title = { Text("Streamed games") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${StringUtils.formatBytes(hub.streamsTotal)} used of ${net.limitLabel(limit)}", fontSize = 14.sp)
                Text("Size limit", color = cs.primary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    net.limitChoices.forEach { b ->
                        FilterChip(selected = limit == b, onClick = { net.setLimit(b) }, label = { Text(if (b <= 0) "None" else "${b shr 30} GB", fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                Text("Over the limit, the games played longest ago are emptied first.", color = cs.onSurfaceVariant, fontSize = 12.sp)
                hub.streams.forEach { info ->
                    val g = info.game
                    Text("${g.name} · ${StringUtils.formatBytes(info.bytes)}", color = cs.primary, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    com.winlator.star.netdrive.NetStream.Mode.values().forEach { m -> Choice(g.mode == m, m.label) { net.setStreamMode(g, m) } }
                    Row {
                        TextButton(onClick = { net.emptyStream(g) }) { Text("Empty now") }
                        TextButton(onClick = { net.removeStreamReq = g }) { Text("Remove", color = cs.error) }
                    }
                }
                if (hub.streams.isEmpty()) Text("No streamed games yet.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { net.freeUpStreams() }) { Text("Free up space") } },
        dismissButton = { TextButton(onClick = { net.streamsOpen = false }) { Text("Close") } },
    )
}

/** Classic's network dialogs (form, remove, new folder, rename, delete, add to Games), drawn from [FmNetOps]'s state. */
@Composable
internal fun FmNetDialogs(net: FmNetOps, containers: List<com.winlator.star.container.Container>, chooseFolder: (String, (java.io.File) -> Unit) -> Unit, clearSelections: () -> Unit) {
    net.addReq?.let { NetAddGameDialog(net, it, containers, chooseFolder) }
    if (net.streamsOpen) NetStreamsDialog(net)
    net.removeStreamReq?.let { g ->
        OutlinedAlertDialog(
            onDismissRequest = { net.removeStreamReq = null },
            title = { Text("Remove \"${g.name}\"?") },
            text = { Text("Removes it from Games and deletes what it keeps on this device. Nothing on the network drive is touched.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { net.removeStream(g) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { net.removeStreamReq = null }) { Text("Cancel") } },
        )
    }
    net.editReq?.let { d ->
        NetDriveDialog(d, net.editIsNew, net.testing, net.testError, onDismiss = { net.editReq = null; net.testError = null }) { drive, anyway -> net.submit(drive, anyway) }
    }
    net.removeReq?.let { d ->
        OutlinedAlertDialog(
            onDismissRequest = { net.removeReq = null },
            title = { Text("Remove \"${d.name}\"?") },
            text = { Text("Only the saved drive is removed; nothing on it is deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { net.remove(d) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { net.removeReq = null }) { Text("Cancel") } },
        )
    }
    net.newFolderIn?.let { loc -> NameDialog("New folder", "", "Create", { net.newFolderIn = null }) { net.newFolderIn = null; net.createFolder(loc, it) } }
    net.renameTarget?.let { it0 -> NameDialog("Rename", it0.name, "Rename", { net.renameTarget = null }) { net.renameTarget = null; net.rename(it0, it) } }
    if (net.deleteTargets.isNotEmpty()) {
        val victims = net.deleteTargets
        DeleteDialog(victims.map { it.name }, { net.deleteTargets = emptyList() }) {
            net.deleteTargets = emptyList()
            clearSelections()
            net.delete(victims)
        }
    }
}
