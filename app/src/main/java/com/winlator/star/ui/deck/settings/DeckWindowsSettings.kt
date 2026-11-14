package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import com.winlator.star.ui.deck.deckShape
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.contents.ContentProfile
import com.winlator.star.core.ImageUtils
import com.winlator.star.core.WineThemeManager
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.ContentDownloadSheet
import com.winlator.star.ui.screens.DriveEntry
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// The Windows page's container-level settings that were in the classic container editor's General,
// Wine config and Drives tabs: the Wine / Proton layer (read-only once the container exists, as in
// the classic editor) and its downloads, the desktop background colour and wallpaper, the drive
// letters, and on All containers "Reset to app defaults". All of it is the container form
// (ContainerDetailViewModel): the form is the draft, so Save writes drives, theme and colour through
// the same applyFormTo the classic ✓ does. Two things happen on the press, as in the classic editor:
// a picked wallpaper image is written to its file at once (Save then stamps its time into the theme),
// and Reset forgets the defaults profile. The Win components tab's page (the DLL and codec rows with a search bar and
// "Show more") is WinComponentsPage.kt; this section only hosts it, so every editor that draws the sections has it.

internal object WindowsRows {

    private const val ID_BG = "windows.desktopBackground"
    private val IMAGE = WineThemeManager.BackgroundType.IMAGE.ordinal
    private val COLOR = WineThemeManager.BackgroundType.COLOR.ordinal

    /** ColorPickerView's palette, in its order. */
    private val PALETTE = listOf(
        0xff8f00 to L("Amber"), 0xd32f2f to L("Red"), 0x9575cd to L("Purple"), 0x2e7d32 to L("Green"),
        0x00838f to L("Teal"), 0x0277bd to L("Blue"), 0x607d8b to L("Blue grey"), 0x000000 to L("Black"),
    )
    private val HEX = Regex("""^#?[0-9A-Fa-f]{6}$""")

    private fun hex(color: Int) = String.format(Locale.ENGLISH, "#%06X", 0x00ffffff and color)

    fun defs(): List<SettingDef> = listOf(
        SettingDef(
            id = "windows.wineVersion", category = "windows", group = "Wine", level = SettingLevel.ADVANCED,
            label = L("Wine / Proton version"), hint = L("The Wine layer this container runs. Download more below."), tech = L("Wine Version"),
            control = { env, _ -> Control.Select((env.vm?.wineVersionEntries ?: emptyList()).map { Opt(it, it) }) },
            vm = VmBinding(get = { _, vm -> vm.selectedWineVersion }, set = { _, vm, v -> vm.onWineVersionChanged(v) }),
            onDefaults = false, showInGame = false,
            visible = { env, _ -> env.scope == EditorScope.CONTAINER },
            // As the classic editor: the layer is chosen when the container is created, and its prefix is built for it.
            disabled = { env, _ -> if (env.vm?.wineVersionEnabled == false) L("Fixed once the container exists: its Windows prefix was built for this layer.") else null },
            getMore = "wine",
        ),
        SettingDef(
            id = ID_BG, category = "windows", group = L("Windows desktop"), level = SettingLevel.ADVANCED,
            label = L("Desktop background"), tech = L("Background"),
            control = { _, _ -> Control.Segmented(listOf(Opt("$IMAGE", L("Image")), Opt("$COLOR", L("Solid colour")))) },
            vm = VmBinding(get = { _, vm -> vm.desktopBgTypeIndex.toString() }, set = { _, vm, v -> v.toIntOrNull()?.let { vm.desktopBgTypeIndex = it } }),
            showInGame = false,
        ),
        SettingDef(
            id = "windows.desktopColor", category = "windows", group = L("Windows desktop"), level = SettingLevel.ADVANCED,
            label = L("Background colour"), tech = L("Background Color"),
            control = { _, _ ->
                Control.Select(
                    options = PALETTE.map { (c, name) -> Opt(hex(c), "$name (${hex(c)})") },
                    customLabel = L("Other colour…"),
                    customHint = L("A colour as #RRGGBB, e.g. #1E88E5"),
                    validateCustom = { if (HEX.matches(it.trim())) null else L("Type six hex digits, e.g. #1E88E5.") },
                    normalizeCustom = { t -> "#" + t.trim().removePrefix("#").uppercase(Locale.ENGLISH) },
                )
            },
            vm = VmBinding(
                get = { _, vm -> hex(vm.desktopBgColorInt) },
                set = { _, vm, v -> runCatching { android.graphics.Color.parseColor(v) }.getOrNull()?.let { vm.desktopBgColorInt = it } },
            ),
            showInGame = false,
            visible = { _, values -> values(ID_BG) == "$COLOR" },
        ),
        SettingDef(
            id = "windows.wallpaperScope", category = "windows", group = L("Windows desktop"), level = SettingLevel.ADVANCED,
            label = L("Wallpaper image for"), hint = L("One image every container shares, or one for this container only."), tech = L("Apply wallpaper to"),
            control = { _, _ ->
                Control.Segmented(listOf(
                    Opt("${WineThemeManager.BackgroundScope.GLOBAL.ordinal}", L("All containers")),
                    Opt("${WineThemeManager.BackgroundScope.CONTAINER.ordinal}", L("This container")),
                ))
            },
            vm = VmBinding(
                get = { _, vm -> vm.desktopWallpaperScopeIndex.toString() },
                set = { _, vm, v -> v.toIntOrNull()?.let { vm.desktopWallpaperScopeIndex = it } },
            ),
            showInGame = false,
            visible = { _, values -> values(ID_BG) == "$IMAGE" },
        ),
    )
}

/**
 * The Windows page's sections for a container and All containers: the Wine download, the wallpaper
 * image, the drive list and Reset to app defaults. Nothing for a game.
 */
internal class DeckWindowsSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
    /** True in the XMB interface: the Win components tab's page. False (plain Deck): no such page, the components stay on Windows. */
    private val xmb: Boolean,
) : DeckSection {
    private val vm: ContainerDetailViewModel? = env.vm?.takeIf { env.scope == EditorScope.CONTAINER || env.scope == EditorScope.DEFAULTS }
    private val container get() = env.scope == EditorScope.CONTAINER

    var wineSheet by mutableStateOf(false)
    var confirmReset by mutableStateOf(false)
    /** The drive whose folder the picker is choosing; [pickDir] opens it. */
    var browseFor by mutableStateOf<Long?>(null)
    var pickImage by mutableStateOf(false)
    var pickImageSystem by mutableStateOf(false)
    /** Bumped when a wallpaper image is written, so its preview is read again. */
    var wallpaperStamp by mutableLongStateOf(0L)
        private set
    // A picker activity is open: coming back must not reload the form, or the pick lands on a form
    // that is thrown away (and a written wallpaper's new time would count as already saved).
    private var awaiting = false

    override val keepsForm: Boolean get() = awaiting

    override fun onReturn() { awaiting = false }

    fun startPicker() { awaiting = true }

    /**
     * A row's "Get more" (SettingDef.getMore, wired by the host through SettingsEnv.getMoreOpen): "wine" opens the
     * Wine / Proton download sheet. False for any other kind, or when this section has no container form.
     */
    fun openGetMore(kind: String): Boolean {
        if (kind != "wine" || vm == null) return false
        wineSheet = true
        return true
    }

    /**
     * The Win components tab's page (search, first five, Show more): its own source "wincomp", for a container, All
     * containers and a Wine game alike (a game has no container form here, so it comes before the early return).
     */
    private val winComponents = WinComponentsSection(env, source)

    override fun extras(): Map<String, CategoryExtra> {
        // XMB only. Lab lists the component rows itself (SimpleClassicEditor) and the plain Deck keeps them on Windows: no page.
        val components = if (xmb) winComponents.extras() else emptyMap<String, CategoryExtra>()
        if (vm == null || env.world != World.WINE) return components
        return components + mapOf("windows" to CategoryExtra(bottom = ::bottomItems))
    }

    private fun bottomItems(): List<PageExtra> = buildList {
        val vm = vm ?: return@buildList
        // The XMB editor reaches this sheet from "Get more" on the Wine / Proton version row ([openGetMore]); the plain Deck and
        // the Lab style have no such link, so they keep their own download action.
        if (container && !xmb) {
            addAll(actionItems("windows-wine", null, listOf(
                DeckAction("wine", Icons.Filled.CloudDownload, L("Download Wine and Proton versions"),
                    L("Layers from the catalog, or install one from a file. New containers can use them.")) { wineSheet = true },
            )))
        }
        if (vm.desktopBgTypeIndex == WineThemeManager.BackgroundType.IMAGE.ordinal) {
            add(PageExtra("windows-wallpaper-label") { GroupLabel(L("Wallpaper")) })
            add(PageExtra("windows-wallpaper") { GroupCard(first = true, last = true, highlight = false) { WallpaperLine(this@DeckWindowsSection, vm) } })
        }
        if (container) addAll(driveItems(vm))
        if (env.scope == EditorScope.DEFAULTS) {
            addAll(actionItems("windows-reset", L("Start over"), listOf(
                DeckAction("reset", Icons.Filled.RestartAlt, L("Reset to app defaults"),
                    L("Forgets this architecture's defaults profile, its \"Set as default\" picks and default layer.")) { confirmReset = true },
            )))
        }
    }

    private fun driveItems(vm: ContainerDetailViewModel): List<PageExtra> = buildList {
        add(PageExtra("windows-drives-label") { GroupLabel(L("Drives")) })
        val drives = vm.drives.toList()
        val dupes = vm.duplicateDriveLetters
        val full = drives.size >= vm.driveLetterOptions.size
        if (drives.isEmpty()) {
            add(PageExtra("windows-drives-none") {
                GroupCard(first = true, last = false, highlight = false) {
                    DeckLine(L("No drives"), L("Only C: (the Windows prefix) and Z: are there. Add a drive for a folder of games."))
                }
            })
        }
        drives.forEachIndexed { i, d ->
            add(PageExtra("windows-drive:${d.uid}") {
                GroupCard(first = i == 0, last = false, highlight = false) { DriveLine(this@DeckWindowsSection, vm, d, d.letter in dupes) }
            })
        }
        if (dupes.isNotEmpty()) {
            add(PageExtra("windows-drives-dupes") {
                GroupCard(first = false, last = false, highlight = false) {
                    DeckLine(L("Each drive needs its own letter"), L("Used twice: ") + dupes.sorted().joinToString(", ") { "$it:" } + L(". Save is refused until they differ."), error = true)
                }
            })
        }
        add(PageExtra("windows-drives-add") {
            GroupCard(first = false, last = true, highlight = false) {
                DeckActionRow(DeckAction("add", Icons.Filled.Add, L("Add a drive"),
                    if (full) L("Every letter from D: to Z: is in use.") else L("Takes the first free letter. Pick its folder with Browse."), enabled = !full) { vm.addDrive() })
            }
        })
    }

    /** Writes a picked image to the wallpaper file of the scope being edited, as the classic editor does at once. */
    fun applyWallpaper(uri: Uri) {
        val vm = vm ?: return
        val file = vm.wallpaperFileFor(WineThemeManager.BackgroundScope.values().getOrElse(vm.desktopWallpaperScopeIndex) { WineThemeManager.BackgroundScope.GLOBAL })
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = ImageUtils.getBitmapFromUri(env.context, uri, 1280) ?: return@runCatching false
                    file.parentFile?.mkdirs()
                    ImageUtils.save(bitmap, file, Bitmap.CompressFormat.PNG, 100)
                }.getOrDefault(false)
            }
            if (ok) wallpaperStamp = file.lastModified()
            else Toast.makeText(env.context, L("Couldn't read that image."), Toast.LENGTH_SHORT).show()
        }
    }

    fun resetDefaults() {
        confirmReset = false
        vm?.resetDefaults()
    }

    @Composable
    override fun Dialogs() {
        // The Win components popup and row help: also for a game, which has no container form below.
        if (xmb) winComponents.Dialogs()
        val vm = vm ?: return
        val context = LocalContext.current
        // The pickers are registered here, which is always composed, so a result is never lost to a scrolled-away row.
        val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uid = browseFor
            if (result.resultCode == Activity.RESULT_OK && uid != null) InAppFilePicker.pickedPath(result.data)?.let { vm.updateDrivePath(uid, it) }
            browseFor = null
        }
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { applyWallpaper(it) }
        }
        val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { applyWallpaper(it) } }
        LaunchedEffect(browseFor) {
            val uid = browseFor ?: return@LaunchedEffect
            val drive = vm.drives.firstOrNull { it.uid == uid } ?: run { browseFor = null; return@LaunchedEffect }
            startPicker()
            // The built-in picker, not SAF: it returns a real path, so an SD card folder is /storage/<uuid>/...
            dirPicker.launch(InAppFilePicker.buildDirIntent(context, "Select folder for drive ${drive.letter}:",
                drive.path.takeIf { it.isNotBlank() && File(it).isDirectory }))
        }
        LaunchedEffect(pickImage) {
            if (!pickImage) return@LaunchedEffect
            pickImage = false
            startPicker()
            imagePicker.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.IMAGES, L("Select wallpaper")))
        }
        LaunchedEffect(pickImageSystem) {
            if (!pickImageSystem) return@LaunchedEffect
            pickImageSystem = false
            startPicker()
            systemPicker.launch("image/*")
        }
        if (wineSheet) {
            ContentDownloadSheet(
                contentTypes = listOf(ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON),
                onDismiss = { wineSheet = false },
                onContentChanged = { vm.refreshWineVersions() },
                inUseKey = vm.selectedWineVersion,
            )
        }
        if (confirmReset) {
            OutlinedAlertDialog(
                onDismissRequest = { confirmReset = false },
                confirmButton = { TextButton(onClick = { resetDefaults() }) { Text(L("Reset")) } },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(L("Cancel")) } },
                title = { Text(L("Reset to app defaults?")) },
                text = {
                    Text(
                        L("The %1\$s defaults profile, ", if (vm.defaultsArch == com.winlator.star.core.NewContainerDefaults.ARCH_X86_64) "x86-64" else "ARM64EC") +
                            L("its \"Set as default\" picks and its default layer are forgotten now, and this page shows the app's own defaults. ") +
                            L("Unsaved changes here are lost. Existing containers keep their settings."),
                    )
                },
            )
        }
    }
}

/** One drive: its letter, its folder (typed or browsed) and Remove. A letter used twice is outlined red. */
@Composable
private fun DriveLine(section: DeckWindowsSection, vm: ContainerDetailViewModel, drive: DriveEntry, duplicate: Boolean) {
    val cs = MaterialTheme.colorScheme
    var letters by remember { mutableStateOf(false) }
    // Follow outside changes (Browse, Discard), but never overwrite what is being typed.
    var text by remember(drive.uid) { mutableStateOf(drive.path) }
    LaunchedEffect(drive.path) { if (drive.path != text) text = drive.path }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box {
            DeckSmallButton("${drive.letter}:" + if (duplicate) L(" (used twice)") else "") { letters = true }
            DropdownMenu(expanded = letters, onDismissRequest = { letters = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                vm.driveLetterOptions.forEach { opt ->
                    val l = opt.trimEnd(':')
                    DropdownMenuItem(
                        text = { Text(opt, color = if (l != drive.letter && vm.drives.any { it.letter == l }) cs.onSurfaceVariant else cs.onSurface) },
                        leadingIcon = {
                            if (l == drive.letter) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary)
                            else Box(Modifier.size(24.dp))
                        },
                        onClick = {
                            letters = false
                            if (l != drive.letter) vm.updateDriveLetter(drive.uid, l)
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t; vm.updateDrivePath(drive.uid, t) },
            singleLine = true,
            isError = duplicate,
            placeholder = { Text(L("Folder, e.g. /storage/emulated/0/Games")) },
            shape = deckShape(14.dp),
            modifier = Modifier.weight(1f).deckFocusRing(deckShape(14.dp), scaleTo = 1f),
        )
        DeckSmallButton(L("Browse"), Icons.Filled.FolderOpen) { section.browseFor = drive.uid }
        DeckSmallButton("", Icons.Filled.Delete) { vm.removeDrive(drive.uid) }
    }
}

/** The wallpaper image of the scope being edited, with its preview and the two pickers. */
@Composable
private fun WallpaperLine(section: DeckWindowsSection, vm: ContainerDetailViewModel) {
    val scope = WineThemeManager.BackgroundScope.values().getOrElse(vm.desktopWallpaperScopeIndex) { WineThemeManager.BackgroundScope.GLOBAL }
    val file = vm.wallpaperFileFor(scope)
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path, section.wallpaperStamp) {
        preview = withContext(Dispatchers.IO) {
            if (file.isFile) runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null
        }
    }
    DeckLine(
        label = if (preview != null) L("Wallpaper image") else L("No wallpaper image yet"),
        hint = (if (scope == WineThemeManager.BackgroundScope.GLOBAL) L("Shared by every container") else L("This container's own")) +
            L(". A new image is used from the next start once you save."),
        icon = Icons.Filled.Image,
    ) {
        preview?.let { img ->
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(deckShape(8.dp)))
        }
        DeckSmallButton(if (preview != null) L("Change") else L("Browse"), Icons.Filled.FolderOpen) { section.pickImage = true }
        DeckSmallButton(L("System picker"), Icons.Filled.PhotoLibrary) { section.pickImageSystem = true }
    }
}
