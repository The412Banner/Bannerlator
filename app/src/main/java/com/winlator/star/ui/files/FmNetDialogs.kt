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
                Heading("Game folder")
                if (req.levels.size > 1) {
                    req.levels.forEach { lv ->
                        Choice(req.level == lv, com.winlator.star.netdrive.NetFs.nameOf(lv).ifEmpty { req.drive.name }, lv.ifEmpty { req.drive.name }) {
                            if (req.level != lv) { req.level = lv; req.measure() }
                        }
                    }
                } else Text(req.name, fontSize = 14.sp)
                Text(addSizeText(req.size), color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                Heading("Copy to")
                targets.forEach { (label, dir) ->
                    Choice(req.target == dir, "$label · Winlator/Games", "${StringUtils.formatBytes(net.freeSpace(dir))} free") { req.target = dir }
                }
                val custom = req.target?.takeIf { t -> targets.none { it.second == t } }
                if (custom != null) Choice(true, custom.name.ifBlank { custom.absolutePath }, custom.absolutePath) { }
                TextButton(onClick = { chooseFolder("Copy the game to") { req.target = it } }) { Text("Choose a folder…") }
                if (containers.size > 1) {
                    Heading("Container")
                    containers.forEach { c -> Choice(req.container == c, c.name) { req.container = c } }
                }
                val t = req.target
                val sz = req.size
                if (t != null) {
                    Text("${t.absolutePath}/${req.name}", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    if (sz != null && sz > 0 && sz > net.freeSpace(t)) Text("Not enough free space there", color = cs.error, fontSize = 13.sp)
                }
                if (containers.isEmpty()) Text("No container yet: create one first", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { net.confirmAdd(req) }, enabled = req.target != null && req.container != null) { Text("Copy and add") } },
        dismissButton = { TextButton(onClick = { net.addReq = null }) { Text("Cancel") } },
    )
}

/** Classic's network dialogs (form, remove, new folder, rename, delete, add to Games), drawn from [FmNetOps]'s state. */
@Composable
internal fun FmNetDialogs(net: FmNetOps, containers: List<com.winlator.star.container.Container>, chooseFolder: (String, (java.io.File) -> Unit) -> Unit, clearSelections: () -> Unit) {
    net.addReq?.let { NetAddGameDialog(net, it, containers, chooseFolder) }
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
