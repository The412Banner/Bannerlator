package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.winlator.star.core.StringUtils
import com.winlator.star.core.unpack.ExtraFormats
import com.winlator.star.core.unpack.Innoextract
import com.winlator.star.core.unpack.PowerMode
import com.winlator.star.core.unpack.ReadBuffer
import com.winlator.star.core.unpack.RepackLayers
import com.winlator.star.core.unpack.SevenZip
import com.winlator.star.core.unpack.SpaceSaver
import com.winlator.star.core.unpack.Unarc
import com.winlator.star.core.unpack.UnpackManager
import com.winlator.star.core.unpack.UnpackPhase
import com.winlator.star.core.unpack.UnpackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "Unpack Archive" screen: point the bundled 7-Zip engine at a disc image / archive and extract
 * it to a chosen folder, with a foreground service doing the work so it survives backgrounding.
 *
 * Reached from the File Manager's ⋮ menu (hosted by [com.winlator.star.UnpackArchiveActivity]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnpackArchiveScreen(
    archivePath: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val selected = remember(archivePath) { File(archivePath) }
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    val state by UnpackManager.state.collectAsState()

    // InnoSetup repack? Then 7-Zip must be pointed at the installer .exe (never a lone Setup-*.bin),
    // and the whole flow is "unpack game payload" rather than "extract archive".
    val innoTarget = remember(archivePath) { SevenZip.resolveInnoTarget(selected) }
    val isInno = innoTarget != null
    // What 7-Zip is actually run against: the installer .exe for InnoSetup, else the file itself.
    val archive = remember(archivePath) { innoTarget ?: selected }

    // A friendly default extract-folder name: the repack folder name for InnoSetup (so "Setup" never
    // becomes the folder), else the archive's base name.
    val defaultName = remember(archivePath) {
        if (isInno) archive.parentFile?.name?.takeIf { it.isNotBlank() } ?: "game"
        else SevenZip.suggestedTargetName(selected)
    }

    // Detected type comes from a quick `7zz l` (metadata only). Keyed on the archive so it reruns if
    // the screen is reused for a different one.
    var detectedType by remember(archivePath) { mutableStateOf<String?>(null) }
    var typeLoading by remember(archivePath) { mutableStateOf(true) }
    // InnoSetup classification: most modern repacks (FitGirl/DODI) are FreeArc-compressed, which
    // 7-Zip can't open — those must be installed by running Setup.exe in a container. Classify BEFORE
    // offering a doomed 7-Zip "unpack" action (Records.ini + a `7zz l` pre-flight, off the main thread).
    var innoClass by remember(archivePath) { mutableStateOf<SevenZip.InnoClassification?>(null) }
    // GOG DLC auto-batch: sibling setup_*.exe installers (base game first, then any DLC/extra setups)
    // in the same folder. Populated for the innoextract route only; size > 1 means DLC was found, which
    // reveals the "also extract DLC" toggle below. Default the toggle on — auto-batch is the ask.
    var dlcInstallers by remember(archivePath) { mutableStateOf<List<File>>(emptyList()) }
    var alsoExtractDlc by remember(archivePath) { mutableStateOf(true) }
    // lzip / lzop / LZ4 / ECM / ALZ / EGG / ACE / Amiga LZX / OTA payload: recognised by header and handled
    // by ExtraFormats instead of 7-Zip (which can't open them, so `7zz l` would say "nothing to unpack").
    var extraProbe by remember(archivePath) { mutableStateOf<ExtraFormats.Probe?>(null) }
    // OTA payload: the partitions to write out — every one that can be extracted, until the user narrows it.
    var chosenParts by remember(archivePath) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(archivePath) {
        typeLoading = true
        if (isInno) {
            innoClass = withContext(Dispatchers.IO) { SevenZip.classifyInno(context, archive) }
            if (innoClass?.route == SevenZip.InnoRoute.INNOEXTRACT) {
                dlcInstallers = withContext(Dispatchers.IO) { SevenZip.siblingInnoInstallers(archive) }
            }
        } else {
            val probe = withContext(Dispatchers.IO) { ExtraFormats.probe(context, archive) }
            if (probe != null) {
                extraProbe = probe
                detectedType = probe.typeLabel
                chosenParts = probe.partitions.filter { !it.incremental }.map { it.name }.toSet()
            } else {
                val info = withContext(Dispatchers.IO) { SevenZip.list(context, archive) }
                detectedType = info?.type
            }
        }
        typeLoading = false
    }

    // Destination defaults to a sibling folder (of the repack folder, for InnoSetup) named for the game.
    var destPath by remember(archivePath) {
        val base = if (isInno) archive.parentFile?.parentFile ?: archive.parentFile else selected.parentFile
        mutableStateOf(File(base, defaultName).absolutePath)
    }
    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            com.winlator.star.util.InAppFilePicker.pickedPath(result.data)?.let {
                // Land inside the chosen folder, in a subfolder named for the game, so the extract
                // never carpets someone's Games root with loose files.
                destPath = File(it, defaultName).absolutePath
            }
        }
    }

    var powerMode by remember { mutableStateOf(PowerMode.MAX) }
    var manualCores by remember { mutableStateOf(cores) }
    var buffer by remember { mutableStateOf(ReadBuffer.MB1) }
    var bufferMenu by remember { mutableStateOf(false) }

    // Direct java.io.File writes need All Files Access; a native process can't write through SAF, so
    // when the destination is on shared storage and access isn't granted we gate extraction and send
    // the user to grant it rather than ship a half-working SAF-for-native path.
    val hasAllFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    val destOnSharedStorage = destPath.startsWith("/storage/") && !destPath.startsWith(context.filesDir.absolutePath)
    val gatedByPermission = destOnSharedStorage && !hasAllFiles

    // InnoSetup routing (see classifyInno). While classifying we hold the action buttons.
    val innoClassifying = isInno && innoClass == null
    // FreeArc/ISDone repack → decode natively with unarc; standard-Inno/GOG → innoextract; neither
    // available (or srep) → run Setup.exe in a container.
    val innoContainerOnly = innoClass?.route == SevenZip.InnoRoute.CONTAINER_ONLY
    val innoExtract = innoClass?.route == SevenZip.InnoRoute.INNOEXTRACT
    val innoFreeArcBuiltin = innoClass?.route == SevenZip.InnoRoute.FREEARC_NATIVE
    // FreeArc with external layers (precomp, …): the layered unarc engine + libbfeext.
    val innoLayers = innoClass?.route == SevenZip.InnoRoute.FREEARC_LAYERS
    val innoFreeArc = innoFreeArcBuiltin || innoLayers
    val layerInfo = innoClass?.layers
    // The path actually handed to the service (its job key): FreeArc runs on the first Setup-*.bin
    // volume; everything else on `archive` (the resolved Setup.exe or the plain file).
    val jobArchive = if (innoFreeArc) (innoClass?.freeArcArchive ?: archive) else archive

    val running = state.isRunning && state.archivePath == jobArchive.absolutePath
    val engineMissing = !SevenZip.isAvailable(context)

    // For display + honest speed/ETA: the payload data. For InnoSetup that's the Setup-*.bin total,
    // not the tiny Setup.exe.
    // Bumped after "Put back what's left" (space-saving unpack) so the plan and sizes are re-read.
    var planTick by remember { mutableIntStateOf(0) }
    // Re-read after a job ends: a space-saving unpack shrinks or removes the archive.
    val sourceSize = remember(archivePath, state.phase, planTick) {
        if (isInno) {
            archive.parentFile?.listFiles()
                ?.filter { it.isFile && (it.extension.equals("bin", true) || it == archive) }
                ?.sumOf { it.length() } ?: archive.length()
        } else selected.length()
    }

    // Only one extraction at a time (matches the service's own guard).
    val otherJobRunning = state.isRunning && state.archivePath != jobArchive.absolutePath

    // Content pre-flight (not extension): a plain file is judged by whether `7zz l` could open it as
    // an archive / disc image. No recognisable container (e.g. raw .bin data) → nothing to unpack.
    // A plain program (`Type = PE`/ELF…) opens in 7-Zip too, but only as its code sections — not a payload.
    // ExtraFormats files are judged by their own probe instead (it explains anything it can't do).
    val isExtra = !isInno && extraProbe != null
    val extraProblem = extraProbe?.problem
    val extraAllowed = isExtra && !typeLoading && extraProblem == null
    val isPayload = extraProbe?.format == ExtraFormats.Format.PAYLOAD
    val isProgram = !isInno && !isExtra && !typeLoading && detectedType in SevenZip.EXECUTABLE_TYPES
    val notAnArchive = !isInno && !isExtra && !typeLoading && (detectedType == null || isProgram)
    // Are we still deciding what this file is? (InnoSetup pre-flight, or the plain content-sniff.)
    val checking = if (isInno) innoClassifying else typeLoading
    // The 7-Zip extract path is for plain (non-Inno) archives only, once the sniff confirms it opens.
    val sevenZipAllowed = !isInno && !isExtra && !typeLoading && detectedType != null && !isProgram
    val engineMissingInno = innoExtract && !Innoextract.isAvailable(context)

    val canUnarc = innoFreeArc && !gatedByPermission && jobArchive.isFile && !otherJobRunning
    val startUnarc = {
        UnpackManager.clearIfTerminal()
        UnpackService.start(context, jobArchive.absolutePath, destPath, 1, buffer.bytes, true, sourceSize,
            if (innoLayers) Unarc.ENGINE_LAYERS else Unarc.ENGINE)
    }
    val canSevenZip = sevenZipAllowed && !gatedByPermission && !engineMissing && archive.isFile && !otherJobRunning
    val startSevenZip = {
        UnpackManager.clearIfTerminal()
        val mmt = UnpackManager.mmtFor(powerMode, manualCores)
        UnpackService.start(context, archive.absolutePath, destPath, mmt, buffer.bytes, false, sourceSize)
    }
    val canExtra = extraAllowed && !gatedByPermission && archive.isFile && !otherJobRunning &&
        (!isPayload || chosenParts.isNotEmpty())
    val startExtra = {
        UnpackManager.clearIfTerminal()
        val parts = if (isPayload) extraProbe?.partitions?.map { it.name }?.filter { it in chosenParts } else null
        UnpackService.start(context, archive.absolutePath, destPath, 1, buffer.bytes, false, sourceSize, ExtraFormats.ENGINE, parts)
    }

    // ── Space-saving unpack ("unpack and delete the download as it goes", SpaceSaver / bfecut) ──
    // Plain archives and native FreeArc repacks on a real path, with All Files access. The engine's
    // plan decides the rest.
    val spaceTarget = if (innoFreeArcBuiltin) jobArchive else archive
    val spaceEngine = remember { SpaceSaver.isAvailable(context) }
    val spaceEligible = spaceEngine && (sevenZipAllowed || innoFreeArcBuiltin) && !gatedByPermission
    var spacePlan by remember(archivePath) { mutableStateOf<SpaceSaver.Plan?>(null) }
    var spaceMode by remember(archivePath) { mutableStateOf(false) }
    // ZIP/tar/RAR free space only once it runs out, unless this is ticked (7z/FreeArc free each block anyway).
    var spaceEager by remember(archivePath) { mutableStateOf(false) }
    var confirmSpace by remember { mutableStateOf(false) }
    var abandoning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(spaceEligible, destPath, state.phase, planTick) {
        if (!spaceEligible || state.isRunning) return@LaunchedEffect
        spacePlan = withContext(Dispatchers.IO) {
            if (spaceTarget.isFile) SpaceSaver.plan(context, spaceTarget, File(destPath)) else null
        }
        if (spacePlan?.let { it.usable && !it.resume } != true) spaceMode = false
    }
    val spaceResume = spacePlan?.let { it.supported && it.resume } == true
    val canSpace = spacePlan?.usable == true && spaceTarget.isFile && !otherJobRunning && !gatedByPermission
    val startSpace = {
        UnpackManager.clearIfTerminal()
        UnpackService.start(context, spaceTarget.absolutePath, destPath, 1, buffer.bytes, false, spaceTarget.length(), SpaceSaver.ENGINE,
            spaceEager = spaceEager && spacePlan?.eagerMatters == true)
    }
    val putBack: () -> Unit = {
        abandoning = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { SpaceSaver.abandon(context, spaceTarget, File(destPath)) }
            abandoning = false
            android.widget.Toast.makeText(
                context,
                if (r.exitCode == 0) "Put back: ${spaceTarget.name} holds the rest again" else SpaceSaver.explain(r.errorCode, r.message),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            planTick++
        }
    }

    // Battery-optimisation exemption, refreshed on resume so returning from Settings re-checks it.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val ignoringBattery = remember(resumeTick) {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    // One-time dismissible aggressive-OEM hint.
    val prefs = remember { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    var oemHintDismissed by remember { mutableStateOf(prefs.getBoolean("unpackOemHintDismissed", false)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Unpack Archive",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Source ──
            SectionCard {
                Text("Source", style = sectionTitle())
                Spacer(Modifier.height(6.dp))
                Text(selected.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                val typeText = when {
                    isInno && innoClassifying -> "InnoSetup installer • checking…"
                    isInno -> buildString {
                        append("InnoSetup installer")
                        innoClass?.compression?.let { append(" • ").append(it) }
                        innoClass?.declaredSize?.let { append(", ").append(it) }
                    }
                    typeLoading -> "reading…"
                    detectedType != null -> detectedType
                    else -> "not an archive"
                }
                Text(
                    "${StringUtils.formatBytes(sourceSize)}  •  $typeText",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (innoExtract) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "innoextract will unpack the game files from ${archive.name}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (innoFreeArc) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (innoLayers) "unarc will decode the FreeArc data in ${jobArchive.name}, with the bundled decoders for its extra layers."
                        else "unarc will decode the FreeArc data (${jobArchive.name} + its Setup-*.bin volumes) directly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (extraAllowed) extraProbe?.engineNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Destination applies to the 7-Zip, innoextract AND FreeArc/unarc paths; Power (below) is
            // 7-Zip-only. All hidden while still deciding, for the container-only route, and non-archives.
            if (sevenZipAllowed || innoExtract || innoFreeArc || extraAllowed) {
            // ── Destination ──
            SectionCard {
                Text("Extract to", style = sectionTitle())
                Spacer(Modifier.height(6.dp))
                Text(
                    destPath,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !running,
                    onClick = {
                        destPicker.launch(
                            com.winlator.star.util.InAppFilePicker.buildDirIntent(
                                context,
                                title = "Choose where to extract",
                                initialDir = archive.parent,
                            )
                        )
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Change folder", color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Power (7-Zip only; innoextract has no thread/buffer knobs) ──
            if (sevenZipAllowed) {
            SectionCard {
                Text("Power", style = sectionTitle())
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(PowerMode.AUTO to "Auto", PowerMode.MAX to "Max", PowerMode.MANUAL to "Manual")
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = powerMode == mode,
                            onClick = { if (!running) powerMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }
                if (powerMode == PowerMode.MANUAL) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$manualCores of $cores cores",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = manualCores.toFloat(),
                        onValueChange = { if (!running) manualCores = it.toInt().coerceIn(1, cores) },
                        valueRange = 1f..cores.toFloat(),
                        steps = (cores - 2).coerceAtLeast(0),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Extraction is limited by storage speed; extra cores only help archives with " +
                        "many files (7z/solid). A single huge file won't parallelize.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )

                Spacer(Modifier.height(12.dp))

                // Read-buffer knob.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read buffer", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(
                            enabled = !running,
                            onClick = { bufferMenu = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) { Text(buffer.label, color = MaterialTheme.colorScheme.onBackground) }
                        DropdownMenu(expanded = bufferMenu, onDismissRequest = { bufferMenu = false }) {
                            ReadBuffer.entries.forEach { b ->
                                DropdownMenuItem(text = { Text(b.label) }, onClick = { buffer = b; bufferMenu = false })
                            }
                        }
                    }
                }
                Text(
                    "Larger buffers can improve throughput reading from FUSE-backed storage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            } // end Power (7-Zip only)
            } // end Destination + Power block

            // ── Permission gate ──
            if (gatedByPermission && sevenZipAllowed) {
                WarnCard {
                    Text(
                        "All Files Access is off. Extraction writes directly to storage and can't use the " +
                            "slow SAF fallback for an 80 GB image, so grant access to continue.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }) { Text("Grant access") }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (engineMissing && !isInno) {
                WarnCard {
                    Text(
                        "The 7-Zip engine (lib7zz.so) isn't executable on this build. Extraction is unavailable.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Battery-optimisation exemption (recommended, non-blocking) ──
            if (!running && !ignoringBattery) {
                WarnCard {
                    Text(
                        "For a job this long, exempt the app from battery optimisation so the system " +
                            "doesn't pause or kill it in the background. Recommended for 80 GB extracts.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }) { Text("Allow background running") }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Aggressive-OEM hint (one-time, dismissible) ──
            if (!oemHintDismissed) {
                SectionCard {
                    Text(
                        "On HONOR/Huawei/Xiaomi/OPPO devices, also lock this app in Recents and set its " +
                            "battery to \"No restrictions\", or the system may still pause background extraction.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.parse("package:${context.packageName}"))
                                    )
                                }
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) { Text("App settings", color = MaterialTheme.colorScheme.onBackground) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                oemHintDismissed = true
                                prefs.edit().putBoolean("unpackOemHintDismissed", true).apply()
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) { Text("Got it", color = MaterialTheme.colorScheme.onBackground) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── One extraction at a time ──
            if (!running && otherJobRunning) {
                WarnCard {
                    Text(
                        "Another unpack is already in progress. Only one runs at a time — wait for it to " +
                            "finish (tap its progress pill to watch), then start this one.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Still deciding what this file is (InnoSetup pre-flight, or plain content-sniff) ──
            if (!running && checking) {
                SectionCard {
                    Text(
                        if (isInno) "Checking how this repack is compressed…" else "Checking this file…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── A format known but not unpackable this time (ACE 2.0, encrypted EGG, incremental OTA…) ──
            if (!running && isExtra && !typeLoading && extraProblem != null) {
                WarnCard {
                    Text("Can't extract this file", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(extraProblem, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Not a recognized archive (judged by content, not extension) ──
            if (!running && notAnArchive) {
                WarnCard {
                    Text("Nothing to unpack", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (isProgram) "This is a program, not an installer or self-extracting archive — " +
                            "there's no packed content inside it to unpack."
                        else "This file isn't a recognized archive or disc image — it looks like raw data, " +
                            "nothing to unpack.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── FreeArc repack → native unarc (primary) ──
            if (!running && innoFreeArc) {
                if (layerInfo != null && layerInfo.layers.isNotEmpty()) {
                    SectionCard {
                        Text("Layers in this archive", style = sectionTitle())
                        Spacer(Modifier.height(6.dp))
                        LayerList(layerInfo)
                        if (innoLayers) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Extra layers are decoded into a temporary folder next to the destination, so " +
                                    "leave free space of about twice the game's size until it finishes.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                SectionCard {
                    Text(
                        "FreeArc decompression is I/O-bound: unarc already auto-parallelizes (4x4), so the " +
                            "real limit is writing the game to your storage — a faster card / internal " +
                            "storage helps far more than anything else. There's no thread knob to tune.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SpaceSaverCard(spacePlan, spaceMode, { spaceMode = it }, spaceEager, { spaceEager = it }, abandoning,
                    onContinue = startSpace, canContinue = canSpace, onPutBack = putBack)
                if (!spaceResume) Button(
                    onClick = if (spaceMode) ({ confirmSpace = true }) else startUnarc,
                    enabled = if (spaceMode) canSpace else canUnarc,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (innoLayers) "Unpack game natively (unarc + layers)" else "Unpack game natively (unarc)",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                // No dead-ends: also offer the container route (e.g. srep, or if unarc fails at runtime).
                Spacer(Modifier.height(8.dp))
                RunSetupInContainer(exe = archive)
                Spacer(Modifier.height(12.dp))
            }

            // ── Can't unpack in-app (unarc unavailable / srep) → container-only route ──
            if (!running && innoContainerOnly) {
                val comp = innoClass?.compression ?: "FreeArc"
                val missing = layerInfo?.missing.orEmpty()
                WarnCard {
                    Text("Can't unpack this repack in-app", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (missing.isNotEmpty()) "This $comp archive uses a layer that can't be decoded in-app:"
                        else "This is a $comp repack whose data can't be decoded in-app.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                    if (layerInfo != null && layerInfo.layers.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LayerList(layerInfo)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Install it by running ${archive.name} inside a container, or unpack it on a PC.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    RunSetupInContainer(exe = archive)
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── InnoSetup / GOG → innoextract (primary) ──
            if (!running && innoExtract) {
                if (engineMissingInno) {
                    WarnCard {
                        Text(
                            "The innoextract engine isn't available on this build, so this GOG/InnoSetup " +
                                "installer can't be unpacked in-app. You can still install it by running " +
                                "${archive.name} in a container.",
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        RunSetupInContainer(exe = archive)
                    }
                } else {
                    val dlcCount = (dlcInstallers.size - 1).coerceAtLeast(0)
                    val batchDlc = alsoExtractDlc && dlcCount > 0
                    // DLC/extra setups found next to the base installer: offer to extract them all into
                    // the same game folder (base first, DLC overlaid), or untick for base-game only.
                    if (dlcCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Checkbox(checked = alsoExtractDlc, onCheckedChange = { alsoExtractDlc = it })
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Also extract $dlcCount DLC setup${if (dlcCount == 1) "" else "s"} found in this folder",
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            UnpackManager.clearIfTerminal()
                            // isInno=true selects the innoextract engine in the service.
                            if (batchDlc) {
                                UnpackService.startBatch(context, dlcInstallers.map { it.absolutePath }, destPath, sourceSize)
                            } else {
                                UnpackService.start(context, archive.absolutePath, destPath, 1, buffer.bytes, true, sourceSize)
                            }
                        },
                        enabled = !gatedByPermission && archive.isFile && !otherJobRunning,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (batchDlc) "Unpack game + $dlcCount DLC (innoextract)" else "Unpack game files (innoextract)",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // No dead-ends: also offer the container route (e.g. if innoextract fails at runtime).
                    Spacer(Modifier.height(8.dp))
                    RunSetupInContainer(exe = archive)
                }
            }

            // ── ExtraFormats (lzip/lzop/LZ4/ECM/ALZ/EGG/ACE/LZX/OTA payload) ──
            if (!running && extraAllowed) {
                val probe = extraProbe
                if (isPayload && probe != null) {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Partitions", style = sectionTitle(), modifier = Modifier.weight(1f))
                            val extractable = probe.partitions.filter { !it.incremental }.map { it.name }.toSet()
                            OutlinedButton(
                                onClick = { chosenParts = if (chosenParts.size == extractable.size) emptySet() else extractable },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Text(if (chosenParts.size == extractable.size) "None" else "All", color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        for (part in probe.partitions) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = part.name in chosenParts,
                                    enabled = !part.incremental,
                                    onCheckedChange = { on -> chosenParts = if (on) chosenParts + part.name else chosenParts - part.name },
                                )
                                Text(
                                    "${part.name}.img",
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (part.incremental) "incremental — needs the phone's image" else StringUtils.formatBytes(part.size),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = startExtra,
                    enabled = canExtra,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isPayload) "Extract ${chosenParts.size} partition image${if (chosenParts.size == 1) "" else "s"}" else "Extract",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Extract button (plain, non-Inno archives) ──
            if (!running && sevenZipAllowed) {
                SpaceSaverCard(spacePlan, spaceMode, { spaceMode = it }, spaceEager, { spaceEager = it }, abandoning,
                    onContinue = startSpace, canContinue = canSpace, onPutBack = putBack)
                if (!spaceResume) Button(
                    onClick = if (spaceMode) ({ confirmSpace = true }) else startSevenZip,
                    enabled = if (spaceMode) canSpace else canSevenZip,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (spaceMode) "Extract and delete the download" else "Extract", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (confirmSpace) {
                AlertDialog(
                    onDismissRequest = { confirmSpace = false },
                    title = { Text("Delete the download while unpacking?") },
                    text = {
                        Text(
                            "${spaceTarget.name} will be deleted bit by bit while it unpacks, and is gone at the end. " +
                                "If it stops (cancel, no space, phone restart), open it here again to continue" +
                                (if (spacePlan?.stream == true) "." else ", or to put what's left back as a smaller archive."),
                        )
                    },
                    confirmButton = { TextButton(onClick = { confirmSpace = false; startSpace() }) { Text("Unpack and delete") } },
                    dismissButton = { TextButton(onClick = { confirmSpace = false }) { Text("Keep it") } },
                )
            }

            // ── Progress ──
            if (running) {
                Spacer(Modifier.height(4.dp))
                SectionCard {
                    val listing = state.phase == UnpackPhase.LISTING
                    Text(
                        if (listing) "Reading archive…" else "Extracting — ${state.percent}%",
                        color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (listing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    } else {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            if (state.speedBps > 0) append("${StringUtils.formatBytes(state.speedBps)}/s")
                            if (state.etaSeconds >= 0) {
                                if (isNotEmpty()) append("  •  ")
                                append("ETA ${humanDuration(state.etaSeconds * 1000)}")
                            }
                            if (state.filesExtracted > 0) {
                                if (isNotEmpty()) append("  •  ")
                                append("${state.filesExtracted} files")
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    state.currentFile?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Safe to leave — this keeps running in the background. Reopen it from the progress " +
                            "pill or the notification.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedButton(
                            onClick = { UnpackService.cancel(context) },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onClose) { Text("Minimize") }
                    }
                }
            }

            // ── Terminal result ──
            if (state.archivePath == jobArchive.absolutePath && !running) {
                when (state.phase) {
                    UnpackPhase.DONE -> {
                        Spacer(Modifier.height(4.dp))
                        SectionCard {
                            Text("Done", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${state.filesExtracted} files • ${StringUtils.formatBytes(state.archiveSize)} in ${humanDuration(state.elapsedMs)}",
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                            )
                            if (state.engine == SpaceSaver.ENGINE) {
                                Spacer(Modifier.height(2.dp))
                                Text("The download was deleted as it unpacked.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(state.destPath, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    UnpackPhase.ERROR -> {
                        Spacer(Modifier.height(4.dp))
                        WarnCard {
                            if (state.isInno) {
                                // The honest fallback for any InnoSetup/repack failure is to run the
                                // real installer inside a container. FreeArc failures may be SREP.
                                val freeArc = state.engine == Unarc.ENGINE || state.engine == Unarc.ENGINE_LAYERS
                                Text(
                                    if (freeArc) "Couldn't decode this FreeArc repack" else "Couldn't unpack this InnoSetup repack",
                                    color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (freeArc)
                                        "It may use a layer this build can't decode. Install it by running " +
                                            "Setup.exe inside a Winlator container, or unpack it on a PC."
                                    else
                                        "This InnoSetup repack must be installed by running Setup.exe inside a Winlator container.",
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                RunSetupInContainer(exe = archive)
                            } else {
                                Text("Extraction failed", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            state.errorTail?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it.takeLast(600), color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    UnpackPhase.CANCELLED -> {
                        Spacer(Modifier.height(4.dp))
                        SectionCard {
                            Text("Cancelled", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * "Run Setup.exe in a container" fallback for InnoSetup repacks 7-Zip can't unpack. Picks the sole
 * container automatically; with several it offers a menu; with none it says so.
 */
@Composable
private fun RunSetupInContainer(exe: File) {
    val context = LocalContext.current
    val containers = remember { com.winlator.star.util.ContainerExeRunner.containers(context) }
    var menu by remember { mutableStateOf(false) }

    fun launch(container: com.winlator.star.container.Container) {
        val err = com.winlator.star.util.ContainerExeRunner.run(context, container, exe)
        if (err != null) android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
    }

    when {
        containers.isEmpty() -> Text(
            "Create a container first, then run ${exe.name} from the File Manager.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        )
        else -> Box {
            Button(onClick = { if (containers.size == 1) launch(containers.first()) else menu = true }) {
                Text("Run ${exe.name} in a container")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                containers.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { menu = false; launch(c) })
                }
            }
        }
    }
}

/** One line per FreeArc layer: ✓ decoded in unarc, ↻ the bundled decoders try it, ✕ can't be decoded. */
@Composable
private fun LayerList(analysis: RepackLayers.Analysis) {
    analysis.layers.forEach { l ->
        val (mark, color) = when (l.kind) {
            RepackLayers.Kind.BUILTIN -> "✓" to MaterialTheme.colorScheme.primary
            RepackLayers.Kind.EXTERNAL -> "↻" to MaterialTheme.colorScheme.tertiary
            RepackLayers.Kind.MISSING -> "✕" to MaterialTheme.colorScheme.error
        }
        Row(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(mark, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(18.dp))
            Text(
                "${l.name} — ${l.note}",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
            )
        }
    }
}

/**
 * The space-saving option: a switch while nothing has started; "Continue" / "Put back" once a run
 * stopped part-way (its journal sits in the destination). Hidden when the engine can't do this archive.
 */
@Composable
private fun SpaceSaverCard(
    plan: SpaceSaver.Plan?,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    eager: Boolean,
    onEager: (Boolean) -> Unit,
    abandoning: Boolean,
    onContinue: () -> Unit,
    canContinue: Boolean,
    onPutBack: () -> Unit,
) {
    if (plan == null) return
    val small = MaterialTheme.colorScheme.onSurfaceVariant
    if (plan.supported && plan.resume) {
        WarnCard {
            Text("Space-saving unpack stopped part-way", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (plan.stream) "Part of the download is already freed, so it can only be continued."
                else "${plan.done} of ${plan.pieces} pieces are unpacked and their part of the download is freed. " +
                    "Continue, or put what's left back as a smaller archive (the unpacked files stay).",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = onContinue, enabled = canContinue && !abandoning) { Text("Continue") }
                if (!plan.stream) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onPutBack, enabled = !abandoning) { Text(if (abandoning) "Putting back…" else "Put back what's left") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        return
    }
    if (!plan.supported) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Unpack and delete the download as it goes",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = on, onCheckedChange = onToggle, enabled = plan.usable)
        }
        Spacer(Modifier.height(4.dp))
        val need = if (plan.totalOut >= 0) StringUtils.formatBytes(plan.totalOut + SpaceSaver.KEEP_FREE) else null
        if (!plan.fitsNormal && need != null) {
            Text(
                "Not enough space to unpack normally: that needs about $need free (with 2 GB spare), ${StringUtils.formatBytes(plan.free)} is free.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            when {
                !plan.sameStorage -> plan.reason ?: "Only works when the files land on the same storage as the download."
                plan.stream -> "The download is read from the start and what has been read is freed, so it needs much less free space. The download is gone at the end."
                plan.shrinks && plan.format in setOf("7z", "freearc") ->
                    "The download is unpacked block by block and each block's space is freed as soon as it's unpacked, so it needs much less free space. The download is gone at the end."
                plan.shrinks -> "The download is unpacked piece by piece and, once space gets tight, the part already unpacked is freed, so it needs much less free space. The download is gone at the end."
                else -> "This archive is one solid block: it still needs the full space, and the download is only deleted at the end."
            },
            color = small, fontSize = 11.sp,
        )
        if (on && plan.usable && plan.eagerMatters) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = eager, onCheckedChange = onEager)
                Text(
                    "Delete as it goes even while there's room",
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (plan.sameStorage && !plan.fits) {
            Spacer(Modifier.height(4.dp))
            Text("Even this way it's ${StringUtils.formatBytes(plan.short)} short. Free some space first.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun sectionTitle() = MaterialTheme.typography.labelLarge.copy(
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), content = content)
    }
}

@Composable
private fun WarnCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

private fun humanDuration(ms: Long): String = StringUtils.humanDuration(ms)
