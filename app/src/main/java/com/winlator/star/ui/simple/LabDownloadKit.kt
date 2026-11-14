package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.contents.ContentsInstaller
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.installContent
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

// The pieces Lab's download pickers share (LabContentDownloads.kt, LabDownloads.kt): one list dialog with group
// headings, subtitles, switches and progress on the line, the install-from-a-file steps and the installed versions' info.

/**
 * One line of [LabDownloadList]. A [header] is a group heading (not focusable). A or a tap runs [onA], or opens
 * [actions] when there is no [onA]; X or a long press opens [actions].
 */
internal class DlLine(
    val key: String,
    val title: String,
    val sub: String? = null,
    val trailing: String? = null,
    val header: Boolean = false,
    /** An on/off line: a switch on the right. */
    val toggle: Boolean? = null,
    /** 0..1 while something runs for this line. */
    val progress: Float? = null,
    /** An action line (Install from a file, Cancel download, Retry) in the accent colour. */
    val accent: Boolean = false,
    val enabled: Boolean = true,
    val onA: (() -> Unit)? = null,
    val actions: List<MenuAction> = emptyList(),
)

/**
 * A Lab download picker: [title], an optional [message] under it, then [lines]. The first focusable line (or
 * [startKey]) takes the focus. B closes unless [blockDismiss] (a download that must finish first).
 */
@Composable
internal fun LabDownloadList(
    title: String,
    lines: List<DlLine>,
    onDismiss: () -> Unit,
    message: String? = null,
    startKey: String? = null,
    blockDismiss: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error
    var actionsFor by remember { mutableStateOf<DlLine?>(null) }
    var confirmFor by remember { mutableStateOf<MenuAction?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = requesters.getOrPut(k) { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focusable = lines.filter { !it.header }
    val target = (startKey?.takeIf { k -> focusable.any { it.key == k } } ?: focusable.firstOrNull()?.key)
    // Focus the first line when the dialog opens, and again when the focused line is gone (a list that loaded).
    val focusGone = focusedKey == null || focusable.none { it.key == focusedKey }
    LaunchedEffect(target, focusGone, actionsFor == null, confirmFor == null) {
        if (!focusGone || target == null || actionsFor != null || confirmFor != null) return@LaunchedEffect
        delay(60)
        runCatching { req(target).requestFocus() }
    }
    fun run(a: MenuAction) { if (a.confirm != null) confirmFor = a else a.run() }
    val listState = rememberLazyListState()
    Dialog(onDismissRequest = { if (!blockDismiss) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(min = 320.dp, max = 620.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            if (!message.isNullOrBlank()) Text(message, color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(lines) { l ->
                    if (l.header) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.title, color = SimpleMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            l.trailing?.let { Text(it, color = SimpleDim, fontSize = 12.sp) }
                        }
                        return@items
                    }
                    val open: () -> Unit = {
                        val f = l.onA
                        when {
                            !l.enabled -> {}
                            f != null -> { f(); l.toggle?.let { was -> com.winlator.star.sound.toggleSound(!was) } }
                            l.actions.isNotEmpty() -> actionsFor = l
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .labTile(
                                onClick = open,
                                onMenu = if (l.actions.isNotEmpty() && l.enabled) ({ actionsFor = l }) else null,
                                requester = req(l.key),
                                onFocus = { focusedKey = l.key },
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    l.title,
                                    color = when {
                                        !l.enabled -> SimpleDim
                                        l.accent -> accent
                                        else -> SimpleStrong
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = if (l.accent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                if (!l.sub.isNullOrBlank()) Text(l.sub, color = SimpleDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            l.trailing?.let {
                                Spacer(Modifier.width(10.dp))
                                Text(it, color = if (it == L("Failed")) red else SimpleMuted, fontSize = 13.sp, maxLines = 1)
                            }
                            l.toggle?.let { on -> Spacer(Modifier.width(10.dp)); LabSwitch(on) }
                            if (l.actions.isNotEmpty() && l.enabled && l.toggle == null) {
                                Spacer(Modifier.width(8.dp))
                                Text("⋯", color = SimpleMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        val p = l.progress
                        if (p != null) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x26FFFFFF))) {
                                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(accent))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                LabHint("A", L("Select"))
                if (focusable.any { it.actions.isNotEmpty() }) {
                    Spacer(Modifier.width(8.dp))
                    LabHint("X", L("Options"))
                }
                if (!blockDismiss) {
                    Spacer(Modifier.width(8.dp))
                    LabHint("B", L("Close"))
                }
            }
        }
    }
    actionsFor?.let { l ->
        SimpleActionSheet(l.title, l.actions, onDismiss = { actionsFor = null }) { a -> actionsFor = null; run(a) }
    }
    confirmFor?.let { a ->
        SimpleConfirm(a.label, a.confirm ?: "", danger = a.danger, onDismiss = { confirmFor = null }) { confirmFor = null; a.run() }
    }
}

/**
 * Install from a file, as Classic's download sheet does it: the in-app file manager or the system picker, then the
 * same install steps ([installContent] for a component, DriverInstaller through ContentsInstaller for a GPU driver).
 * Progress shows as [line]; Cancel stops a component install while it unpacks.
 */
@Stable
internal class LabFileInstall {
    var asking by mutableStateOf(false)
    /** The file being installed and how far it got, while it runs. */
    var name by mutableStateOf<String?>(null)
    var fraction by mutableStateOf(0f)
    var failed by mutableStateOf<String?>(null)
    internal var cancel: AtomicBoolean? = null
    internal var browse: () -> Unit = {}
    internal var system: () -> Unit = {}

    val running: Boolean get() = name != null

    /** The "Install from a file…" line, or the running install's line (A cancels it). */
    fun line(): DlLine {
        val n = name
        return if (n == null) DlLine("__file__", L("Install from a file…"), accent = true, onA = { asking = true })
        else DlLine(
            "__file__", L("Installing %1\$s", n), trailing = "${(fraction * 100).toInt()}%", progress = fraction,
            actions = if (cancel != null) listOf(MenuAction(L("Cancel install"), { cancel?.set(true); name = null; cancel = null }, danger = true)) else emptyList(),
        )
    }
}

/**
 * [LabFileInstall]'s launchers and its "Browse files / Pick via system…" choice. [driver] installs a GPU driver
 * (a .zip / .adpkg) instead of a component. [onInstalled] runs on the main thread after a good install.
 */
@Composable
internal fun rememberLabFileInstall(driver: Boolean = false, onInstalled: () -> Unit): LabFileInstall {
    val context = LocalContext.current
    val fi = remember { LabFileInstall() }
    val cm = remember { ContentsManager(context) }
    fun start(uri: Uri) {
        if (fi.running) return
        val fname = displayNameOf(context, uri)
        fi.failed = null
        fi.fraction = 0f
        fi.name = fname
        if (driver) {
            fi.cancel = null
            ContentsInstaller.installFromFile(context, ContentsTypes.GPU_DRIVERS, fname, uri) { ok ->
                (context as? Activity)?.runOnUiThread {
                    fi.name = null
                    if (ok) onInstalled() else fi.failed = L("Could not install %1\$s", fname)
                }
            }
            return
        }
        val cancel = AtomicBoolean(false)
        fi.cancel = cancel
        installContent(context, cm, uri, onProgress = { f, _ -> fi.fraction = f }, cancelled = cancel) { ok ->
            if (cancel.get()) return@installContent
            fi.name = null
            fi.cancel = null
            if (ok) {
                toast(context, L("Installed %1\$s", fname))
                onInstalled()
            } else fi.failed = L("Install failed.")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (result.data?.data ?: InAppFilePicker.pickedUri(result.data))?.let { start(it) }
    }
    fi.browse = {
        val exts = if (driver) InAppFilePicker.DRIVER else InAppFilePicker.WCP
        picker.launch(InAppFilePicker.buildIntent(context, exts, if (driver) L("Select driver") else L("Select content file")))
    }
    fi.system = {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" })
    }
    if (fi.asking) {
        SimpleActionSheet(
            title = L("Install from a file"),
            actions = listOf(
                MenuAction(L("Browse files"), { fi.browse() }),
                MenuAction(L("Pick via system…"), { fi.system() }),
            ),
            onDismiss = { fi.asking = false },
        ) { a -> fi.asking = false; a.run() }
    }
    fi.failed?.let { msg -> LabNote(L("Install failed"), msg) { fi.failed = null } }
    return fi
}

/** Classic's "Content Info" of an installed version, as text. */
internal fun contentInfoText(p: ContentProfile): String = buildString {
    append(L("Type")).append(": ").append(p.type.toString()).append('\n')
    append(L("Version")).append(": ").append(p.verName).append('\n')
    append(L("Code")).append(": ").append(p.verCode)
    if (!p.desc.isNullOrEmpty()) append("\n\n").append(p.desc)
}
