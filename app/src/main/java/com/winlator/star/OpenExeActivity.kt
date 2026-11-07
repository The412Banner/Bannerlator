package com.winlator.star

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.preference.PreferenceManager
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.ExternalExeResolver
import com.winlator.star.core.GameIdentifier
import com.winlator.star.core.WinePath
import com.winlator.star.linux.LinuxSettings
import com.winlator.star.ui.deck.DeckTheme
import com.winlator.star.ui.screens.ExeQuickRun
import com.winlator.star.ui.screens.ExeShortcutImporter
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.ShortcutLaunchDialogs
import com.winlator.star.ui.screens.ShortcutLauncher
import com.winlator.star.ui.screens.isSteamOriginShortcut
import com.winlator.star.ui.screens.launchShortcutNow
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.WinlatorTheme
import com.winlator.star.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * "Open with → Bannerlator" for a Windows .exe tapped in any file manager (ACTION_VIEW / ACTION_SEND,
 * see the manifest's filters). A translucent window over the file manager that:
 *  1. turns the shared Uri back into the REAL file ([ExternalExeResolver]) - Wine runs the exe from its
 *     own folder, beside its DLLs and data, so it is never copied;
 *  2. checks it really is a Windows program (PE header), whatever the file manager called it;
 *  3. asks which Wine container (the Linux runtime's settings container and the Android-games home
 *     are never offered) and whether to run it once or add it to the library first;
 *  4. starts it through the normal session (XServerDisplayActivity), then gets out of the way.
 * Run once is the File Manager's own Run ([ExeQuickRun]); Add to library is the Games "+" importer.
 */
class OpenExeActivity : ComponentActivity() {
    // English and left-to-right whatever the phone's language (see AppLocale).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.winlator.star.AppLocale.wrap(newBase))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeState.init(this)   // in case MainActivity hasn't run yet in this process

        val uri = incomingUri(intent)
        setContent {
            WinlatorTheme {
                val uiStyle by AppThemeState.uiStyle.collectAsState()
                if (uiStyle == AppThemeState.UI_STYLE_DECK) {
                    DeckTheme { OpenExeFlow(uri, onDone = { finish() }) }
                } else {
                    OpenExeFlow(uri, onDone = { finish() })
                }
            }
        }
    }

    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        else -> intent.data
    }

    companion object {
        /** Default prefs key: the container id the user last asked us to remember for opened .exe files. */
        const val PREF_REMEMBERED_CONTAINER = "open_exe_container_id"
    }
}

private sealed interface OpenExeState {
    object Working : OpenExeState
    /** [allFilesAccess]: the fix is granting All files access, so the dialog offers that button. */
    data class Failed(val title: String, val message: String, val allFilesAccess: Boolean = false) : OpenExeState
    data class Choose(
        val exe: File,
        val containers: List<Container>,
        /** Container id → the library entry that already runs this exact file there (custom imports only). */
        val inLibrary: Map<Int, Shortcut>,
        val initialContainerId: Int,
    ) : OpenExeState
    data class Adding(val name: String) : OpenExeState
}

@Composable
private fun OpenExeFlow(uri: Uri?, onDone: () -> Unit) {
    val context = currentActivity()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OpenExeState>(OpenExeState.Working) }
    // A library game (Play from library, Add to library and run) is started without the Games tab's launch flow, so the
    // Ubisoft Connect setup question is asked here before it starts (launchWithUbisoftGate; fail-open, other games start at once).
    val launcher = remember(context) { ShortcutLauncher(context, context, scope) }

    LaunchedEffect(uri) {
        state = withContext(Dispatchers.IO) { prepare(context, uri) }
    }

    // Run once: the File Manager's throwaway shortcut, nothing written to the Games list.
    fun runOnce(exe: File, container: Container) {
        scope.launch {
            val shortcutFile = withContext(Dispatchers.IO) {
                runCatching { ExeQuickRun.writeRunShortcut(context, container, exe) }
            }.getOrElse { failure ->
                Toast.makeText(context, ExeQuickRun.failureMessage(exe, failure), Toast.LENGTH_LONG).show()
                onDone()
                return@launch
            }
            context.startActivity(ExeQuickRun.sessionIntent(context, container, shortcutFile))
            onDone()
        }
    }

    // Add to library, then play it from there (per-game settings, last played, TV launch all apply).
    // The importer renames the entry in the background once Steam knows the game's real name, and an
    // open session saves its in-game changes back to the file it started from - so wait for the rename
    // before launching. If it takes too long, run once instead; the library entry is there either way.
    fun addAndRun(exe: File, container: Container) {
        state = OpenExeState.Adding(exe.nameWithoutExtension)
        scope.launch {
            // Same identification as the Games "+" flow, so the entry gets Steam's name and art.
            val identity = withContext(Dispatchers.IO) { runCatching { GameIdentifier.identify(exe) }.getOrNull() }
            val displayName = identity?.name?.takeIf { it.isNotBlank() } ?: exe.nameWithoutExtension
            var writtenBase: String? = null
            var renamedBase: String? = null   // set on the main thread, before onFinished
            val finished = withTimeoutOrNull(ADD_WAIT_MS) {
                withContext(Dispatchers.IO) { suspendCancellableCoroutine<Boolean> { cont ->
                    val file = runCatching {
                        ExeShortcutImporter.addToShortcuts(
                            context, container, exe, displayName, identity?.appId,
                            onNameResolved = { _, newBase -> renamedBase = newBase },
                            onFinished = { if (cont.isActive) cont.resume(true) },
                        )
                    }.getOrNull()
                    if (file == null) {
                        cont.resume(false)
                    } else {
                        writtenBase = file.nameWithoutExtension
                    }
                } }
            }
            val desktop = (renamedBase ?: writtenBase)?.let { File(container.getDesktopDir(), "$it.desktop") }
            val shortcut = if (finished == true && desktop != null && desktop.isFile) {
                runCatching { Shortcut(container, desktop) }.getOrNull()
            } else null
            when {
                shortcut != null -> {
                    Toast.makeText(context, "Added \"${shortcut.name}\" to Shortcuts", Toast.LENGTH_SHORT).show()
                    state = OpenExeState.Working   // nothing over the setup question, if there is one
                    launcher.launchWithUbisoftGate(shortcut, onDone = onDone) { launchShortcutNow(context, shortcut) }
                }
                finished == false -> {
                    Toast.makeText(context, "Couldn't add ${exe.name} to Shortcuts", Toast.LENGTH_SHORT).show()
                    runOnce(exe, container)
                }
                else -> runOnce(exe, container)   // still naming it in the background: play it now regardless
            }
        }
    }

    when (val s = state) {
        OpenExeState.Working -> Unit   // resolving is quick; nothing over the file manager meanwhile
        is OpenExeState.Failed -> OutlinedAlertDialog(
            onDismissRequest = onDone,
            title = { Text(s.title) },
            text = { Text(s.message) },
            confirmButton = {
                if (s.allFilesAccess) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}")),
                            )
                        }
                        onDone()
                    }) { Text("Allow access") }
                } else {
                    TextButton(onClick = onDone) { Text("OK") }
                }
            },
            dismissButton = { if (s.allFilesAccess) TextButton(onClick = onDone) { Text("Cancel") } },
        )
        is OpenExeState.Choose -> ChooseDialog(
            choose = s,
            onCancel = onDone,
            onRunOnce = { container, keep ->
                rememberContainer(context, container, keep)
                runOnce(s.exe, container)
            },
            onAddAndRun = { container, keep ->
                rememberContainer(context, container, keep)
                addAndRun(s.exe, container)
            },
            onPlayFromLibrary = { shortcut, keep ->
                rememberContainer(context, shortcut.container, keep)
                state = OpenExeState.Working   // the choice is made; nothing over the setup question, if there is one
                launcher.launchWithUbisoftGate(shortcut, onDone = onDone) { launchShortcutNow(context, shortcut) }
            },
        )
        is OpenExeState.Adding -> OutlinedAlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Adding to your library") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text("Looking up \"${s.name}\" — the game starts in a moment.")
                }
            },
            confirmButton = {},
        )
    }
    // Only the Ubisoft Connect setup dialog of the gate above ever opens from here.
    ShortcutLaunchDialogs(launcher, onRemove = { false })
}

@Composable
private fun ChooseDialog(
    choose: OpenExeState.Choose,
    onCancel: () -> Unit,
    onRunOnce: (Container, Boolean) -> Unit,
    onAddAndRun: (Container, Boolean) -> Unit,
    onPlayFromLibrary: (Shortcut, Boolean) -> Unit,
) {
    var selectedId by remember(choose) { mutableStateOf(choose.initialContainerId) }
    var rememberChoice by remember(choose) { mutableStateOf(false) }
    val selected = choose.containers.firstOrNull { it.id == selectedId } ?: choose.containers.first()
    val libraryEntry = choose.inLibrary[selected.id]

    OutlinedAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(choose.exe.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    choose.exe.parent.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (choose.containers.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("Container", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    choose.containers.forEach { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = c.id == selected.id, role = Role.RadioButton) { selectedId = c.id }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(selected = c.id == selected.id, onClick = null)
                            Text(c.name, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = rememberChoice, role = Role.Checkbox) { rememberChoice = it }
                            .padding(vertical = 2.dp),
                    ) {
                        Checkbox(checked = rememberChoice, onCheckedChange = null)
                        Text("Use this container next time", fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (libraryEntry != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Already in your library as \"${libraryEntry.name}\".",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = { onRunOnce(selected, rememberChoice) }) { Text("Run once") }
            if (libraryEntry != null) {
                TextButton(onClick = { onPlayFromLibrary(libraryEntry, rememberChoice) }) { Text("Play from library") }
            } else {
                TextButton(onClick = { onAddAndRun(selected, rememberChoice) }) { Text("Add to library and run") }
            }
        },
    )
}

/** How long "Add to library and run" waits for the background naming before running once instead. */
private const val ADD_WAIT_MS = 20_000L

@Composable
private fun currentActivity(): ComponentActivity =
    androidx.compose.ui.platform.LocalContext.current as ComponentActivity

private fun rememberContainer(context: ComponentActivity, container: Container, keep: Boolean) {
    if (!keep) return
    PreferenceManager.getDefaultSharedPreferences(context).edit()
        .putInt(OpenExeActivity.PREF_REMEMBERED_CONTAINER, container.id)
        .apply()
}

/** Everything between the Uri and the choice dialog. BLOCKING - runs on Dispatchers.IO. */
private fun prepare(context: ComponentActivity, uri: Uri?): OpenExeState {
    if (uri == null) return OpenExeState.Failed("Nothing to open", "The other app didn't pass a file along.")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
        return OpenExeState.Failed(
            "Bannerlator needs All files access",
            "A game runs from its own folder, next to its DLLs and data, so Bannerlator has to be able to read that " +
                "folder. Allow All files access for Bannerlator, then open the .exe again.",
            allFilesAccess = true,
        )
    }

    val info = ExternalExeResolver.uriInfo(context, uri)
    val shownName = info.displayName ?: uri.lastPathSegment ?: "This file"
    // Read the header through the Uri first: a file that isn't a Windows program gets the right answer even
    // when its real path can't be found. null = the sender wouldn't let us read it (checked again on the path).
    val isPe = runCatching { context.contentResolver.openInputStream(uri)?.use { ExternalExeResolver.looksLikePe(it) } }.getOrNull()
    if (isPe == false) return notWindowsProgram(shownName)

    val exe = ExternalExeResolver.resolve(context, uri)
        ?: return OpenExeState.Failed(
            "Can't find the game's folder",
            "A game runs from its own folder, next to its DLLs and data, so Bannerlator needs the real location " +
                "of \"$shownName\" — and the app that shared it only passed a link that doesn't say where it is. " +
                "Open the .exe from a file manager that shares real paths (MiXplorer, CX File Explorer, Material Files, " +
                "Solid Explorer), or from Bannerlator's own File manager.",
        )
    if (isPe == null && !ExternalExeResolver.looksLikePe(exe)) return notWindowsProgram(exe.name)

    if (!ImageFs.find(context).isValid()) {
        return OpenExeState.Failed(
            "Bannerlator isn't set up yet",
            "Open Bannerlator once and let it finish setting up, then open the .exe again.",
        )
    }

    val manager = runCatching { ContainerManager(context) }.getOrNull()
    // Wine containers only: the Linux runtime's settings container (Steam for Linux) and the Android
    // games' home are not Wine prefixes and must never be offered for an .exe. getContainers() leaves
    // them out already; filtered again so that can never quietly change under this screen.
    val containers = manager?.containers.orEmpty()
        .filter { !LinuxSettings.isLinuxContainer(it) && !AndroidGames.isHome(it) }
    if (manager == null || containers.isEmpty()) {
        return OpenExeState.Failed(
            "No container yet",
            "Create a container in Bannerlator first, then open the .exe again.",
        )
    }

    // Is this exact file already a (custom) game in the library? Then offer to play that entry - its
    // per-game settings apply - instead of adding a duplicate. Steam games have their own launch flow.
    val exePath = exe.canonicalPath
    val inLibrary = HashMap<Int, Shortcut>()
    val ids = containers.map { it.id }.toSet()
    runCatching { manager.loadShortcuts() }.getOrNull().orEmpty().forEach { s ->
        if (s.container.id !in ids || s.container.id in inLibrary || isSteamOriginShortcut(s)) return@forEach
        val target = runCatching { WinePath.resolveAndroidPath(s.container, s.path)?.canonicalPath }.getOrNull()
        if (target == exePath) inLibrary[s.container.id] = s
    }

    val remembered = PreferenceManager.getDefaultSharedPreferences(context)
        .getInt(OpenExeActivity.PREF_REMEMBERED_CONTAINER, -1)
    val initial = containers.firstOrNull { it.id == remembered }?.id
        ?: inLibrary.keys.firstOrNull()
        ?: containers.first().id
    return OpenExeState.Choose(exe, containers, inLibrary, initial)
}

private fun notWindowsProgram(name: String) = OpenExeState.Failed(
    "Not a Windows program",
    "\"$name\" isn't a Windows program, so Bannerlator can't run it.",
)
