package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.container.Container
import com.winlator.star.core.FileUtils
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Classic's Storage Info in Lab's look: [container]'s Drive C, cache and total, and Clear cache (same steps as Classic). */
@Composable
internal fun LabStorageDialog(container: Container, onDismiss: () -> Unit) {
    var driveC by remember { mutableLongStateOf(0L) }
    var cache by remember { mutableLongStateOf(0L) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val done = remember { FocusRequester() }
    LaunchedEffect(container) {
        val root = container.getRootDir()
        launch(Dispatchers.IO) { FileUtils.getSizeAsync(File(root, ".wine/drive_c")) { size -> handler.post { driveC += size } } }
        launch(Dispatchers.IO) { FileUtils.getSizeAsync(File(root, ".cache")) { size -> handler.post { cache += size } } }
        delay(60)
        runCatching { done.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 480.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(container.name, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            SizeLine(L("Drive C"), driveC)
            SizeLine(L("Cache"), cache)
            SizeLine(L("Total"), driveC + cache, strong = true)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Clear cache")) {
                    FileUtils.clear(File(container.getRootDir(), ".cache"))
                    container.putExtra("desktopTheme", null)
                    container.saveData()
                    onDismiss()
                }
                LabButton(L("Done"), primary = true, requester = done, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun SizeLine(label: String, bytes: Long, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(StringUtils.formatBytes(bytes), color = SimpleStrong, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold, fontSize = if (strong) 17.sp else 15.sp)
    }
}
