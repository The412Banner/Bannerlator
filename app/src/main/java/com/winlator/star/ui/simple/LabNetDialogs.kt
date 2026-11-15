package com.winlator.star.ui.simple

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.star.netdrive.NetDrive
import com.winlator.star.netdrive.NetKind
import com.winlator.star.ui.files.FmItem
import com.winlator.star.ui.files.FmNetOps
import com.winlator.star.ui.files.NetForm
import com.winlator.star.ui.files.netProps

// Lab's network-drive dialogs: Classic's (ui/files/FmNetDialogs.kt) in the Lab look, on the same FmNetOps state.

/** The add / edit / log-in form, as Classic's: kind, name, address, port, share or folder, user, password (https for WebDAV). */
@Composable
internal fun LabNetDriveDialog(initial: NetDrive, isNew: Boolean, testing: Boolean, error: String?, onDismiss: () -> Unit, onSave: (NetDrive, Boolean) -> Unit) {
    val f = remember(initial.id) { NetForm(initial) }
    LabDialog(if (isNew) L("Add network drive") else L("Edit %1\$s", initial.name), onDismiss) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NetKind.values().forEach { k -> LabButton(k.short, primary = f.kind == k) { f.kind = k } }
        }
        LabTextField(f.name, { f.name = it }, L("Name"), placeholder = f.host.ifBlank { L("My NAS") })
        LabTextField(f.host, { f.host = it }, L("Address"), placeholder = "192.168.1.20 / nas.local")
        LabTextField(f.port, { f.port = it.filter(Char::isDigit).take(5) }, L("Port"), placeholder = (if (f.kind == NetKind.WEBDAV && f.https) 443 else f.kind.defaultPort).toString(), number = true)
        LabTextField(f.folder, { f.folder = it }, L(f.folderLabel), placeholder = L("Blank = the top"))
        LabTextField(f.user, { f.user = it }, L("User name"), placeholder = L(f.userHint))
        LabTextField(f.password, { f.password = it }, L("Password"), password = true)
        if (f.kind == NetKind.WEBDAV) LabToolRow(L("https"), toggle = f.https) { f.https = !f.https }
        when {
            testing -> LabBusyLine(L("Connecting…"))
            error != null -> LabDialogText(error, error = true)
        }
        LabDialogButtons {
            LabButton(L("Cancel")) { onDismiss() }
            if (error != null && !testing && f.valid) LabButton(L("Save anyway")) { onSave(f.toDrive(), true) }
            if (f.valid && !testing) LabButton(if (error != null) L("Try again") else L("Save"), primary = true) { onSave(f.toDrive(), false) }
        }
    }
}

/** A network file's or folder's properties, as Classic's. */
@Composable
internal fun LabNetProperties(item: FmItem.Net, onDismiss: () -> Unit) {
    LabDialog(item.name, onDismiss) {
        netProps(item).forEach { (k, v) -> LabPropLine(L(k), v) }
        LabDialogButtons { LabButton(L("Done"), primary = true) { onDismiss() } }
    }
}

/** Every network dialog FmNetOps can ask for, Lab-drawn. */
@Composable
internal fun LabNetDialogs(net: FmNetOps, clearSelections: () -> Unit) {
    net.editReq?.let { d ->
        LabNetDriveDialog(d, net.editIsNew, net.testing, net.testError, onDismiss = { net.editReq = null; net.testError = null }) { drive, anyway -> net.submit(drive, anyway) }
    }
    net.removeReq?.let { d ->
        SimpleActionSheet(
            L("Remove \"%1\$s\"?", d.name),
            listOf(
                MenuAction(L("Remove"), { net.remove(d) }, danger = true),
                MenuAction(L("Cancel"), { net.removeReq = null }),
            ),
            onDismiss = { net.removeReq = null },
            message = L("Only the saved drive is removed; nothing on it is deleted."),
        ) { it.run() }
    }
    net.newFolderIn?.let { loc ->
        LabTextPrompt(L("New folder"), "", L("Create"), onDismiss = { net.newFolderIn = null }, valid = ::labValidName) { net.newFolderIn = null; net.createFolder(loc, it) }
    }
    net.renameTarget?.let { item ->
        LabTextPrompt(L("Rename"), item.name, L("Rename"), onDismiss = { net.renameTarget = null }, valid = ::labValidName) { net.renameTarget = null; net.rename(item, it) }
    }
    if (net.deleteTargets.isNotEmpty()) {
        val victims = net.deleteTargets
        val names = victims.map { it.name }
        val body = buildString {
            append(L("This can't be undone."))
            if (names.size > 1) {
                names.take(5).forEach { append("\n• ").append(it) }
                if (names.size > 5) append("\n").append(L("…and %1\$s more", names.size - 5))
            }
        }
        SimpleActionSheet(
            if (names.size == 1) L("Delete \"%1\$s\"?", names[0]) else L("Delete %1\$s items?", names.size),
            listOf(
                MenuAction(L("Delete"), {
                    net.deleteTargets = emptyList()
                    clearSelections()
                    net.delete(victims)
                }, danger = true),
                MenuAction(L("Cancel"), { net.deleteTargets = emptyList() }),
            ),
            onDismiss = { net.deleteTargets = emptyList() },
            message = body,
        ) { it.run() }
    }
}

/** True while one of the network dialogs is up (Lab's page then leaves the controller focus alone). */
internal fun netDialogOpen(net: FmNetOps): Boolean =
    net.editReq != null || net.removeReq != null || net.newFolderIn != null || net.renameTarget != null || net.deleteTargets.isNotEmpty()
