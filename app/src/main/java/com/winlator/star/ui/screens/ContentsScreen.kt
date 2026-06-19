package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.container.ContainerManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.ui.theme.Surface
import com.winlator.star.ui.theme.SurfaceVariant
import java.util.concurrent.Executors

// ---------------------------------------------------------------------------
// Dialog state — drives Compose dialogs in the install pipeline
// ---------------------------------------------------------------------------
private sealed class InstallDialogState {
    data class Info(
        val profile: ContentProfile,
        val onConfirm: () -> Unit,
        val onCancel: () -> Unit,
    ) : InstallDialogState()

    data class Untrusted(
        val files: List<ContentProfile.ContentFile>,
        val onConfirm: () -> Unit,
        val onCancel: () -> Unit,
    ) : InstallDialogState()

    data class Alert(
        val message: String,
        val onDismiss: () -> Unit,
    ) : InstallDialogState()
}

@Composable
fun ContentsScreen(vm: ContentsViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as Activity

    val filter by vm.filter.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val downloadingKeys by vm.downloadingKeys.collectAsState()

    LaunchedEffect(Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val url = prefs.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES)
            ?: ContentsManager.REMOTE_PROFILES
        vm.syncRemote(url)
    }

    DisposableEffect(Unit) {
        onDispose { context.cacheDir.listFiles()?.forEach { it.delete() } }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    var confirmInstallPrompt by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ContentProfile?>(null) }
    var showInfoFor by remember { mutableStateOf<ContentProfile?>(null) }
    var loadingText by remember { mutableStateOf<String?>(null) }
    var installDialog by remember { mutableStateOf<InstallDialogState?>(null) }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                launchInstall(
                    context, uri, vm,
                    onLoading = { text -> loadingText = text },
                    onDone   = { loadingText = null },
                    onDialog = { state -> installDialog = state },
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Filter chips ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentProfile.ContentType.values().forEach { type ->
                FilterChip(
                    selected = filter == type,
                    onClick = { vm.setFilter(type) },
                    label = { Text(type.toString()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }

        Divider(color = DividerColor)

        // ── Install button ────────────────────────────────────────────────────
        Button(
            onClick = { confirmInstallPrompt = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text("Install content from file")
        }

        Divider(color = DividerColor)

        // ── Content list ──────────────────────────────────────────────────────
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f, fill = false),
                contentAlignment = Alignment.Center,
            ) {
                Text("No content available.", color = OnSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(profiles, key = { ContentsViewModel.profileKey(it) }) { profile ->
                    val key = ContentsViewModel.profileKey(profile)
                    val isDownloading = key in downloadingKeys

                    ContentItem(
                        profile = profile,
                        isDownloading = isDownloading,
                        onDownload = {
                            vm.downloadRemote(profile, context.cacheDir) { uri ->
                                launchInstall(
                                    context, uri, vm,
                                    onLoading = { text -> loadingText = text },
                                    onDone   = { loadingText = null },
                                    onDialog = { state -> installDialog = state },
                                )
                            }
                        },
                        onInfo = { showInfoFor = profile },
                        onRemove = { confirmRemove = profile },
                    )
                    Divider(color = DividerColor)
                }
            }
        }
    }

    // ── Confirm: install from file ────────────────────────────────────────────
    if (confirmInstallPrompt) {
        AlertDialog(
            onDismissRequest = { confirmInstallPrompt = false },
            containerColor = Color(0xFF2A2A2A),
            title = { Text(context.getString(R.string.do_you_want_to_install_content), color = Color.White) },
            text = {
                Text(
                    context.getString(R.string.pls_make_sure_content_trustworthy) + "\n\n" +
                    context.getString(R.string.content_suffix_is_wcp_packed_xz_zst) + "\n" +
                    context.getString(R.string.get_more_contents_form_github),
                    color = Color(0xFFCCCCCC),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmInstallPrompt = false
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    filePicker.launch(intent)
                }) { Text(context.getString(android.R.string.ok), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstallPrompt = false }) {
                    Text(context.getString(android.R.string.cancel), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }

    // ── Installing content overlay ────────────────────────────────────────────
    loadingText?.let { msg ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Surface(
                color = Color(0xFF2A2A2A),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    CircularProgressIndicator(color = Color(0xFF0055FF))
                    Spacer(Modifier.height(16.dp))
                    Text(msg, color = Color.White)
                }
            }
        }
    }

    // ── Content info (local item) ─────────────────────────────────────────────
    showInfoFor?.let { profile ->
        ContentInfoDialog(
            profile = profile,
            onDismiss = { showInfoFor = null },
        )
    }

    // ── Install pipeline dialogs ──────────────────────────────────────────────
    when (val d = installDialog) {
        is InstallDialogState.Info -> ContentInfoDialog(
            profile = d.profile,
            confirmLabel = context.getString(R.string._continue),
            onConfirm = { installDialog = null; d.onConfirm() },
            onDismiss = { installDialog = null; d.onCancel() },
        )
        is InstallDialogState.Untrusted -> UntrustedDialog(
            files = d.files,
            onConfirm = { installDialog = null; d.onConfirm() },
            onDismiss = { installDialog = null; d.onCancel() },
        )
        is InstallDialogState.Alert -> AlertDialog(
            onDismissRequest = { installDialog = null; d.onDismiss() },
            containerColor = Color(0xFF2A2A2A),
            text = { Text(d.message, color = Color(0xFFCCCCCC)) },
            confirmButton = {
                TextButton(onClick = { installDialog = null; d.onDismiss() }) {
                    Text(context.getString(android.R.string.ok), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
        null -> Unit
    }

    // ── Confirm: remove ───────────────────────────────────────────────────────
    confirmRemove?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text(context.getString(R.string.do_you_want_to_remove_this_content), color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE ||
                        profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
                    ) {
                        val cm = ContainerManager(context)
                        val entryName = ContentsManager.getEntryName(profile)
                        val blocking = cm.getContainers().firstOrNull { it.wineVersion == entryName }
                        if (blocking != null) {
                            installDialog = InstallDialogState.Alert(
                                message = context.getString(
                                    R.string.unable_to_remove_content_since_container_using,
                                    blocking.name,
                                ),
                                onDismiss = {},
                            )
                            return@TextButton
                        }
                    }
                    vm.removeContent(profile)
                }) { Text(context.getString(android.R.string.ok), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(context.getString(android.R.string.cancel), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Reusable Compose dialogs
// ---------------------------------------------------------------------------

@Composable
private fun ContentInfoDialog(
    profile: ContentProfile,
    confirmLabel: String = "OK",
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A2A2A),
        title = { Text("Content Info", color = Color.White) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── Info section ──────────────────────────────────────────────
                SectionBox(header = "Info") {
                    InfoRow("Type",    profile.type.toString())
                    InfoRow("Version", profile.verName)
                    InfoRow("Code",    profile.verCode.toString())
                }

                // ── Description section ───────────────────────────────────────
                if (!profile.desc.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionBox(header = "Description") {
                        Text(
                            text = profile.desc,
                            color = Color(0xFFBBBBBB),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // ── Files section ─────────────────────────────────────────────
                if (!profile.fileList.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionBox(header = "Files") {
                        profile.fileList.forEach { file ->
                            Text(
                                text = "${file.source} → ${file.target}",
                                color = Color(0xFFCCCCCC),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.primary) }
        },
    )
}

@Composable
private fun UntrustedDialog(
    files: List<ContentProfile.ContentFile>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A2A2A),
        title = { Text("Warning", color = Color(0xFFFF8A80)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionBox(header = "Unverified Files", borderColor = Color(0xFFFF8A80)) {
                    Text(
                        "These files could not be verified. Continue only if you trust the source.",
                        color = Color(0xFFCCCCCC),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    files.forEach { file ->
                        Text(
                            text = "${file.source} → ${file.target}",
                            color = Color(0xFFFF8A80),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Continue", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.primary) }
        },
    )
}

@Composable
private fun SectionBox(
    header: String,
    borderColor: Color = Color(0xFF555555),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = header,
            color = Color(0xFFAAAAAA),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
        Text(value,      color = Color(0xFFE0E0E0), style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------
// Content list item
// ---------------------------------------------------------------------------
@Composable
private fun ContentItem(
    profile: ContentProfile,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isLocal = profile.remoteUrl == null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )

        Column(modifier = Modifier
            .weight(1f)
            .padding(horizontal = 12.dp)) {
            Text("Version: ${profile.verName}", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            Text("Code: ${profile.verCode}",    style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }

        if (!isLocal) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (isLocal) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = OnSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Info") },
                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { menuExpanded = false; onInfo() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuExpanded = false; onRemove() },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Install pipeline
// ---------------------------------------------------------------------------
private fun launchInstall(
    context: Context,
    uri: Uri,
    vm: ContentsViewModel,
    onLoading: (String?) -> Unit,
    onDone: () -> Unit,
    onDialog: (InstallDialogState) -> Unit,
) {
    val activity = context as Activity
    activity.runOnUiThread { onLoading(context.getString(R.string.installing_content)) }

    val callback = object : ContentsManager.OnInstallFinishedCallback {
        private var isExtracting = true

        override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
            val msgId = when (reason) {
                ContentsManager.InstallFailedReason.ERROR_BADTAR         -> R.string.file_cannot_be_recognied
                ContentsManager.InstallFailedReason.ERROR_NOPROFILE      -> R.string.profile_not_found_in_content
                ContentsManager.InstallFailedReason.ERROR_BADPROFILE     -> R.string.profile_cannot_be_recognized
                ContentsManager.InstallFailedReason.ERROR_EXIST          -> R.string.content_already_exist
                ContentsManager.InstallFailedReason.ERROR_MISSINGFILES   -> R.string.content_is_incomplete
                ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> R.string.content_cannot_be_trusted
                else                                                      -> R.string.unable_to_install_content
            }
            activity.runOnUiThread {
                onDone()
                onDialog(InstallDialogState.Alert(
                    message  = "${context.getString(R.string.install_failed)}: ${context.getString(msgId)}",
                    onDismiss = {},
                ))
            }
        }

        override fun onSucceed(profile: ContentProfile) {
            if (isExtracting) {
                val self = this
                activity.runOnUiThread {
                    onDialog(InstallDialogState.Info(
                        profile   = profile,
                        onConfirm = {
                            isExtracting = false
                            val untrusted = vm.manager.getUnTrustedContentFiles(profile)
                            if (untrusted.isNotEmpty()) {
                                onDialog(InstallDialogState.Untrusted(
                                    files     = untrusted,
                                    onConfirm = { vm.manager.finishInstallContent(profile, self) },
                                    onCancel  = { activity.runOnUiThread { onDone() } },
                                ))
                            } else {
                                vm.manager.finishInstallContent(profile, self)
                            }
                        },
                        onCancel = { activity.runOnUiThread { onDone() } },
                    ))
                }
            } else {
                activity.runOnUiThread {
                    onDone()
                    vm.manager.syncContents()
                    vm.setFilter(profile.type)
                    vm.refreshList()
                    onDialog(InstallDialogState.Alert(
                        message   = context.getString(R.string.content_installed_success),
                        onDismiss = {},
                    ))
                }
            }
        }
    }

    Executors.newSingleThreadExecutor().execute {
        vm.manager.extraContentFile(uri, callback)
    }
}
