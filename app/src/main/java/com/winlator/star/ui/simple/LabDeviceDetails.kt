package com.winlator.star.ui.simple

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.deviceinfo.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The device's name on the Lab top line and in the drawer opens the device details (as DroidDeck's GPU card does):
// chip, GPU, CPU, memory, system, the phone's own Vulkan driver and the drivers installed here, with Copy for a bug report.

/** Whether the device details pop-up is open. */
internal object LabDeviceDetails {
    var open by mutableStateOf(false)
}

/** The device's name, a tile that opens the device details. */
@Composable
internal fun LabDeviceName(modifier: Modifier = Modifier) {
    Text(
        Build.MODEL ?: "", color = SimpleMuted, fontSize = 12.sp, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.labTile(onClick = { LabDeviceDetails.open = true }).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/** Shows the device details while [LabDeviceDetails.open]. */
@Composable
internal fun LabDeviceDetailsHost() {
    if (LabDeviceDetails.open) LabDeviceDetailsDialog(onDismiss = { LabDeviceDetails.open = false })
}

@Composable
private fun LabDeviceDetailsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var sections by remember { mutableStateOf<List<DeviceInfo.Section>?>(null) }
    var copied by remember { mutableStateOf(false) }
    val copy = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        sections = withContext(Dispatchers.IO) {
            runCatching { DeviceInfo.collect(context.applicationContext) }
                .onFailure { android.util.Log.w("LabDeviceDetails", "device details not read", it) }.getOrDefault(emptyList())
        }
    }
    val loaded = sections != null
    LaunchedEffect(loaded) {
        if (loaded) {
            delay(80)
            runCatching { copy.requestFocus() }
        }
    }
    LabDialog(Build.MODEL ?: L("Device"), onDismiss, wide = true) {
        val list = sections
        if (list == null) {
            Text(L("Reading the device…"), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        } else if (list.isEmpty()) {
            Text(L("The device details could not be read."), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (copied) L("Copied") else L("Copy these details into a bug report."),
                    color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                LabButton(L("Copy"), requester = copy) {
                    clipboard.setText(AnnotatedString(DeviceInfo.text(list)))
                    copied = true
                }
            }
            // Each section takes the controller's focus, so Up / Down scroll through them.
            list.forEach { section ->
                Column(Modifier.fillMaxWidth().labTile(onClick = {}).padding(horizontal = 6.dp, vertical = 6.dp)) {
                    LabToolHeading(section.title)
                    section.rows.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(label, color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.width(140.dp))
                            Text(value, color = SimpleStrong, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
