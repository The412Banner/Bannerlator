package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color as AColor
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.ControlsEditorActivity
import com.winlator.star.ExternalControllerBindingsActivity
import com.winlator.star.MainActivity
import com.winlator.star.contentdialog.ContentDialog
import com.winlator.star.core.AppUtils
import com.winlator.star.core.FileUtils
import com.winlator.star.core.HttpUtils
import com.winlator.star.inputcontrols.ControlsProfile
import com.winlator.star.inputcontrols.ExternalController
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.util.InAppFilePicker
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun InputControlsScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val manager = remember { InputControlsManager(context) }

    var profiles by remember { mutableStateOf(listOf<ControlsProfile>()) }
    var currentProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var selectedProfileIdx by remember { mutableStateOf(0) }
    var controllers by remember { mutableStateOf(listOf<ExternalController>()) }

    var showProfileDropdown by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var importProfileCallback by remember { mutableStateOf<((ControlsProfile) -> Unit)?>(null) }
    var promptCreateName by remember { mutableStateOf(false) }
    var promptRenameOldName by remember { mutableStateOf<String?>(null) }

    fun refreshProfiles() {
        profiles = manager.getProfiles()
        val idx = if (currentProfile != null) {
            val i = profiles.indexOf(currentProfile)
            if (i >= 0) i + 1 else 0
        } else 0
        selectedProfileIdx = idx
    }

    fun refreshControllers() {
        val connected = ExternalController.getControllers()
        val loaded = currentProfile?.loadControllers()?.toMutableList() ?: mutableListOf()
        for (c in connected) if (c !in loaded) loaded.add(c)
        controllers = loaded
    }

    fun loadProfile(position: Int) {
        currentProfile = if (position > 0 && position - 1 < profiles.size) profiles[position - 1] else null
        refreshControllers()
    }

    DisposableEffect(Unit) {
        refreshProfiles()
        refreshControllers()
        onDispose { }
    }

    // Shared import logic: read a control profile from any Uri (in-app file:// or SAF content://).
    fun importProfileFromUri(uri: Uri) {
        if (importProfileCallback != null) {
            try {
                val json = FileUtils.readString(context, uri)
                val imported = manager.importProfile(JSONObject(json))
                importProfileCallback!!(imported)
            } catch (_: Exception) {
                AppUtils.showToast(context, R.string.unable_to_import_profile)
            }
            importProfileCallback = null
        }
    }

    // System SAF picker (secondary).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importProfileFromUri(uri) }

    // Built-in in-app file picker (primary).
    val importInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            InAppFilePicker.pickedUri(result.data)?.let { importProfileFromUri(it) }
        }
    }

    if (promptCreateName) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { promptCreateName = false },
            title = { Text("Profile Name") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Enter profile name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        currentProfile = manager.createProfile(name)
                        refreshProfiles()
                        refreshControllers()
                        promptCreateName = false
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { promptCreateName = false }) { Text("Cancel") } }
        )
    }

    if (promptRenameOldName != null) {
        var name by remember { mutableStateOf(promptRenameOldName ?: "") }
        AlertDialog(
            onDismissRequest = { promptRenameOldName = null },
            title = { Text("Profile Name") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        currentProfile?.setName(name)
                        currentProfile?.save()
                        refreshProfiles()
                        promptRenameOldName = null
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { promptRenameOldName = null }) { Text("Cancel") } }
        )
    }

    if (showDownloadDialog) {
        var items by remember { mutableStateOf(listOf<String>()) }
        var selectedItems by remember { mutableStateOf(setOf<Int>()) }
        var isLoadingList by remember { mutableStateOf(true) }

        if (isLoadingList) {
            HttpUtils.download(
                "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/index.txt"
            ) { content ->
                (context as? Activity)?.runOnUiThread {
                    isLoadingList = false
                    if (content != null) items = content.split("\n").filter { it.isNotBlank() }
                    else AppUtils.showToast(context, R.string.unable_to_load_profile_list)
                }
            }
        }

        if (isLoadingList) {
            AlertDialog(
                onDismissRequest = { showDownloadDialog = false },
                title = { Text("Profiles") },
                text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel") } }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showDownloadDialog = false },
                title = { Text("Download Profiles") },
                text = {
                    // Scroll the profile list inside the dialog: the list can be long
                    // (dozens of .icp files), so without this the bottom rows overflow
                    // the dialog's bounded height and overlap the Cancel/Download bar —
                    // worst in landscape where there's even less vertical room.
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        items.forEachIndexed { i, item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedItems = if (i in selectedItems) selectedItems - i
                                    else selectedItems + i
                                }.padding(vertical = 2.dp)
                            ) {
                                androidx.compose.material3.Checkbox(checked = i in selectedItems, onCheckedChange = {
                                    selectedItems = if (it) selectedItems + i else selectedItems - i
                                })
                                Spacer(Modifier.width(8.dp))
                                Text(item, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (selectedItems.isNotEmpty()) {
                            isDownloading = true
                            showDownloadDialog = false
                            val positions = selectedItems.toList()
                            currentProfile = null
                            val processedCount = AtomicInteger()
                            for (position in positions) {
                                HttpUtils.download(
                                    "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/${items[position]}"
                                ) { content ->
                                    if (content != null) {
                                        try { manager.importProfile(JSONObject(content)) } catch (_: Exception) { }
                                    }
                                    if (processedCount.incrementAndGet() == positions.size) {
                                        (context as? Activity)?.runOnUiThread {
                                            isDownloading = false
                                            refreshProfiles()
                                            refreshControllers()
                                        }
                                    }
                                }
                            }
                        }
                    }) { Text("Download") }
                },
                dismissButton = { TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel") } }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Profile Section ─────────────────────────────────────────
        Text("Profile", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        FieldSet {
            Box {
                val displayText = if (selectedProfileIdx > 0 && selectedProfileIdx - 1 < profiles.size)
                    profiles[selectedProfileIdx - 1].getName() else "-- Select Profile --"
                Button(onClick = { showProfileDropdown = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(displayText, color = MaterialTheme.colorScheme.onBackground)
                }
                DropdownMenu(expanded = showProfileDropdown, onDismissRequest = { showProfileDropdown = false }) {
                    DropdownMenuItem(text = { Text("-- Select Profile --") }, onClick = {
                        selectedProfileIdx = 0; loadProfile(0); showProfileDropdown = false
                    })
                    profiles.forEachIndexed { i, p ->
                        DropdownMenuItem(text = { Text(p.getName()) }, onClick = {
                            selectedProfileIdx = i + 1; loadProfile(i + 1); showProfileDropdown = false
                        })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { promptCreateName = true }) { Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (currentProfile != null) promptRenameOldName = currentProfile?.getName()
                    else AppUtils.showToast(context, R.string.no_profile_selected)
                }) { Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (currentProfile != null) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_profile) {
                            currentProfile = manager.duplicateProfile(currentProfile!!)
                            refreshProfiles()
                            refreshControllers()
                        }
                    } else AppUtils.showToast(context, R.string.no_profile_selected)
                }) { Icon(Icons.Default.ContentCopy, "Duplicate", tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = {
                    if (currentProfile != null) {
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_profile) {
                            manager.removeProfile(currentProfile!!)
                            currentProfile = null
                            refreshProfiles()
                            refreshControllers()
                        }
                    } else AppUtils.showToast(context, R.string.no_profile_selected)
                }) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurface) }
            }
        }

        // Overlay opacity now lives in the in-game side menu (Controls tab) so it can be
        // tuned live against the visible overlay — see XServerDrawer.ControlsContent.

        // ── Import / Export ─────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    (context as? Activity)?.let { act ->
                        val builder = android.app.AlertDialog.Builder(act)
                        val options = arrayOf(
                            act.getString(R.string.open_file),
                            "Pick via system…",
                            act.getString(R.string.download_file)
                        )
                        builder.setItems(options) { _, which ->
                            val setCallback = {
                                importProfileCallback = { imported ->
                                    currentProfile = imported
                                    refreshProfiles()
                                    refreshControllers()
                                }
                            }
                            when (which) {
                                0 -> {
                                    setCallback()
                                    importInAppLauncher.launch(
                                        InAppFilePicker.buildIntent(act, InAppFilePicker.ICP, "Select control profile")
                                    )
                                }
                                1 -> {
                                    setCallback()
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                                2 -> showDownloadDialog = true
                            }
                        }
                        builder.show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f)
            ) { Text("Import Profile", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) }
            Button(
                onClick = {
                    if (currentProfile != null) {
                        val exported = manager.exportProfile(currentProfile!!)
                        if (exported != null) AppUtils.showToast(context,
                            "${context.getString(R.string.profile_exported_to)} ${exported.path}")
                    } else AppUtils.showToast(context, R.string.no_profile_selected)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f)
            ) { Text("Export Profile", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) }
        }

        // ── Controls Editor ─────────────────────────────────────────
        Button(
            onClick = {
                if (currentProfile != null) {
                    val intent = Intent(context, ControlsEditorActivity::class.java)
                    intent.putExtra("profile_id", currentProfile!!.id)
                    context.startActivity(intent)
                    (context as? Activity)?.overridePendingTransition(
                        com.winlator.star.R.anim.slide_in_up,
                        com.winlator.star.R.anim.slide_out_down
                    )
                } else AppUtils.showToast(context, R.string.no_profile_selected)
            },
            // intentional: success green signals the primary "go/edit" action; kept off-theme by design
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Controls Editor", color = Color.White) } // intentional: white kept for contrast on the green fill

        // ── External Controllers ────────────────────────────────────
        Text("External Controllers", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (controllers.isEmpty()) {
            Text("No items to display", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        } else {
            for (controller in controllers) {
                val bindingsCount = controller.getControllerBindingCount()
                // intentional: connected (green) / disconnected (red) are distinct status colors, kept off-theme
                val tintColor = if (controller.isConnected()) Color(0xFF4CAF50) else Color(0xFFE57373)
                val accentColor = AColor.parseColor("#4CAF50")

                Box(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).clickable {
                        if (currentProfile != null) {
                            val intent = Intent(context, ExternalControllerBindingsActivity::class.java)
                            intent.putExtra("profile_id", currentProfile!!.id)
                            intent.putExtra("controller_id", controller.getId())
                            context.startActivity(intent)
                            (context as? Activity)?.overridePendingTransition(
                                com.winlator.star.R.anim.slide_in_up,
                                com.winlator.star.R.anim.slide_out_down
                            )
                        } else AppUtils.showToast(context, R.string.no_profile_selected)
                    }.padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Gamepad, null, tint = tintColor, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(controller.getName(), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Text("$bindingsCount Bindings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        if (bindingsCount > 0) {
                            IconButton(onClick = {
                                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_controller) {
                                    currentProfile?.removeController(controller)
                                    currentProfile?.save()
                                    refreshControllers()
                                }
                            }) { Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FieldSet(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        content()
    }
}
