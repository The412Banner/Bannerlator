package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.winlator.star.R
import com.winlator.star.core.StringUtils
import com.winlator.star.core.unpack.Innoextract
import com.winlator.star.core.unpack.PowerMode
import com.winlator.star.core.unpack.ReadBuffer
import com.winlator.star.core.unpack.SevenZip
import com.winlator.star.core.unpack.Unarc
import com.winlator.star.core.unpack.UnpackManager
import com.winlator.star.core.unpack.UnpackPhase
import com.winlator.star.core.unpack.UnpackService
import kotlinx.coroutines.Dispatchers
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
    LaunchedEffect(archivePath) {
        typeLoading = true
        if (isInno) {
            innoClass = withContext(Dispatchers.IO) { SevenZip.classifyInno(context, archive) }
        } else {
            val info = withContext(Dispatchers.IO) { SevenZip.list(context, archive) }
            detectedType = info?.type
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
    val innoFreeArc = innoClass?.route == SevenZip.InnoRoute.FREEARC_NATIVE
    // The path actually handed to the service (its job key): FreeArc runs on the first Setup-*.bin
    // volume; everything else on `archive` (the resolved Setup.exe or the plain file).
    val jobArchive = if (innoFreeArc) (innoClass?.freeArcArchive ?: archive) else archive

    val running = state.isRunning && state.archivePath == jobArchive.absolutePath
    val engineMissing = !SevenZip.isAvailable(context)

    // For display + honest speed/ETA: the payload data. For InnoSetup that's the Setup-*.bin total,
    // not the tiny Setup.exe.
    val sourceSize = remember(archivePath) {
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
    val notAnArchive = !isInno && !typeLoading && detectedType == null
    // Are we still deciding what this file is? (InnoSetup pre-flight, or the plain content-sniff.)
    val checking = if (isInno) innoClassifying else typeLoading
    // The 7-Zip extract path is for plain (non-Inno) archives only, once the sniff confirms it opens.
    val sevenZipAllowed = !isInno && !typeLoading && detectedType != null
    val engineMissingInno = innoExtract && !Innoextract.isAvailable(context)

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
                Icon(
                    Icons.Filled.ArrowBack,
                    stringResource(R.string.compose_files_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(R.string.compose_files_unpack_archive_title),
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
                Text(stringResource(R.string.compose_files_source), style = sectionTitle())
                Spacer(Modifier.height(6.dp))
                Text(selected.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                val innoInstaller = stringResource(R.string.compose_files_inno_installer)
                val typeSeparator = stringResource(R.string.compose_files_type_separator)
                val commaSeparator = stringResource(R.string.compose_files_comma_separator)
                val typeText = when {
                    isInno && innoClassifying ->
                        stringResource(R.string.compose_files_inno_installer_checking)
                    isInno -> buildString {
                        append(innoInstaller)
                        innoClass?.compression?.let { append(typeSeparator).append(it) }
                        innoClass?.declaredSize?.let { append(commaSeparator).append(it) }
                    }
                    typeLoading -> stringResource(R.string.compose_files_reading)
                    detectedType != null -> detectedType.orEmpty()
                    else -> stringResource(R.string.compose_files_not_an_archive)
                }
                Text(
                    stringResource(
                        R.string.compose_files_size_and_type,
                        StringUtils.formatBytes(sourceSize),
                        typeText,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (innoExtract) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.compose_files_innoextract_source,
                            archive.name,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (innoFreeArc) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.compose_files_unarc_source,
                            jobArchive.name,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Destination applies to the 7-Zip, innoextract AND FreeArc/unarc paths; Power (below) is
            // 7-Zip-only. All hidden while still deciding, for the container-only route, and non-archives.
            if (sevenZipAllowed || innoExtract || innoFreeArc) {
            // ── Destination ──
            SectionCard {
                Text(stringResource(R.string.compose_files_extract_to), style = sectionTitle())
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
                                title = context.getString(
                                    R.string.compose_files_choose_extract_destination
                                ),
                                initialDir = archive.parent,
                            )
                        )
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.compose_files_change_folder),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Power (7-Zip only; innoextract has no thread/buffer knobs) ──
            if (sevenZipAllowed) {
            SectionCard {
                Text(stringResource(R.string.compose_files_power), style = sectionTitle())
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        PowerMode.AUTO to stringResource(R.string.compose_files_power_auto),
                        PowerMode.MAX to stringResource(R.string.compose_files_power_max),
                        PowerMode.MANUAL to stringResource(R.string.compose_files_power_manual),
                    )
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
                        pluralStringResource(
                            R.plurals.compose_files_cores,
                            cores,
                            manualCores,
                            cores,
                        ),
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
                    stringResource(R.string.compose_files_power_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )

                Spacer(Modifier.height(12.dp))

                // Read-buffer knob.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.compose_files_read_buffer),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
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
                    stringResource(R.string.compose_files_buffer_description),
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
                        stringResource(R.string.compose_files_all_files_permission),
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
                    }) { Text(stringResource(R.string.compose_files_grant_access)) }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (engineMissing && !isInno) {
                WarnCard {
                    Text(
                        stringResource(R.string.compose_files_7zip_unavailable),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Battery-optimisation exemption (recommended, non-blocking) ──
            if (!running && !ignoringBattery) {
                WarnCard {
                    Text(
                        stringResource(R.string.compose_files_battery_optimization),
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
                    }) { Text(stringResource(R.string.compose_files_allow_background)) }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Aggressive-OEM hint (one-time, dismissible) ──
            if (!oemHintDismissed) {
                SectionCard {
                    Text(
                        stringResource(R.string.compose_files_oem_battery_hint),
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
                        ) {
                            Text(
                                stringResource(R.string.compose_files_app_settings),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                oemHintDismissed = true
                                prefs.edit().putBoolean("unpackOemHintDismissed", true).apply()
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                stringResource(R.string.compose_files_got_it),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── One extraction at a time ──
            if (!running && otherJobRunning) {
                WarnCard {
                    Text(
                        stringResource(R.string.compose_files_other_unpack_running),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Still deciding what this file is (InnoSetup pre-flight, or plain content-sniff) ──
            if (!running && checking) {
                SectionCard {
                    Text(
                        stringResource(
                            if (isInno) {
                                R.string.compose_files_checking_repack
                            } else {
                                R.string.compose_files_checking_file
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Not a recognized archive (judged by content, not extension) ──
            if (!running && notAnArchive) {
                WarnCard {
                    Text(
                        stringResource(R.string.compose_files_nothing_to_unpack),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.compose_files_raw_data),
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── FreeArc repack → native unarc (primary) ──
            if (!running && innoFreeArc) {
                SectionCard {
                    Text(
                        stringResource(R.string.compose_files_freearc_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        UnpackManager.clearIfTerminal()
                        UnpackService.start(context, jobArchive.absolutePath, destPath, 1, buffer.bytes, true, sourceSize, "unarc")
                    },
                    enabled = !gatedByPermission && jobArchive.isFile && !otherJobRunning,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.compose_files_unpack_native),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
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
                WarnCard {
                    Text(
                        stringResource(R.string.compose_files_cannot_unpack_repack),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.compose_files_container_fallback,
                            comp,
                            archive.name,
                        ),
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
                            stringResource(
                                R.string.compose_files_innoextract_unavailable,
                                archive.name,
                            ),
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        RunSetupInContainer(exe = archive)
                    }
                } else {
                    Button(
                        onClick = {
                            UnpackManager.clearIfTerminal()
                            // isInno=true selects the innoextract engine in the service.
                            UnpackService.start(context, archive.absolutePath, destPath, 1, buffer.bytes, true, sourceSize)
                        },
                        enabled = !gatedByPermission && archive.isFile && !otherJobRunning,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.compose_files_unpack_innoextract),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // No dead-ends: also offer the container route (e.g. if innoextract fails at runtime).
                    Spacer(Modifier.height(8.dp))
                    RunSetupInContainer(exe = archive)
                }
            }

            // ── Extract button (plain, non-Inno archives) ──
            if (!running && sevenZipAllowed) {
                Button(
                    onClick = {
                        UnpackManager.clearIfTerminal()
                        val mmt = UnpackManager.mmtFor(powerMode, manualCores)
                        UnpackService.start(context, archive.absolutePath, destPath, mmt, buffer.bytes, false, sourceSize)
                    },
                    enabled = !gatedByPermission && !engineMissing && archive.isFile && !otherJobRunning,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Unarchive, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.compose_files_extract),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Progress ──
            if (running) {
                Spacer(Modifier.height(4.dp))
                SectionCard {
                    val listing = state.phase == UnpackPhase.LISTING
                    Text(
                        if (listing) {
                            stringResource(R.string.compose_files_reading_archive)
                        } else {
                            stringResource(
                                R.string.compose_files_extracting_percent,
                                state.percent,
                            )
                        },
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
                    val speedText = if (state.speedBps > 0) {
                        stringResource(
                            R.string.compose_files_speed,
                            StringUtils.formatBytes(state.speedBps),
                        )
                    } else {
                        null
                    }
                    val etaText = if (state.etaSeconds >= 0) {
                        stringResource(
                            R.string.compose_files_eta,
                            humanDuration(context, state.etaSeconds * 1000),
                        )
                    } else {
                        null
                    }
                    val extractedFilesText = if (state.filesExtracted > 0) {
                        pluralStringResource(
                            R.plurals.compose_files_extracted_files,
                            state.filesExtracted,
                            state.filesExtracted,
                        )
                    } else {
                        null
                    }
                    Text(
                        listOfNotNull(speedText, etaText, extractedFilesText).joinToString(
                            stringResource(R.string.compose_files_detail_separator)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    state.currentFile?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.compose_files_safe_to_leave),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedButton(
                            onClick = { UnpackService.cancel(context) },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Text(
                                stringResource(R.string.compose_files_cancel),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onClose) {
                            Text(stringResource(R.string.compose_files_minimize))
                        }
                    }
                }
            }

            // ── Terminal result ──
            if (state.archivePath == jobArchive.absolutePath && !running) {
                when (state.phase) {
                    UnpackPhase.DONE -> {
                        Spacer(Modifier.height(4.dp))
                        SectionCard {
                            Text(
                                stringResource(R.string.compose_files_done),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pluralStringResource(
                                    R.plurals.compose_files_unpack_done_summary,
                                    state.filesExtracted,
                                    state.filesExtracted,
                                    StringUtils.formatBytes(state.archiveSize),
                                    humanDuration(context, state.elapsedMs),
                                ),
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                            )
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
                                val freeArc = state.engine == "unarc"
                                Text(
                                    stringResource(
                                        if (freeArc) {
                                            R.string.compose_files_freearc_decode_failed
                                        } else {
                                            R.string.compose_files_inno_unpack_failed
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(
                                        if (freeArc) {
                                            R.string.compose_files_freearc_failure_fallback
                                        } else {
                                            R.string.compose_files_inno_failure_fallback
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                RunSetupInContainer(exe = archive)
                            } else {
                                Text(
                                    stringResource(R.string.compose_files_extraction_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
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
                            Text(
                                stringResource(R.string.compose_files_cancelled),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
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
            stringResource(R.string.compose_files_create_container_first, exe.name),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        )
        else -> Box {
            Button(onClick = { if (containers.size == 1) launch(containers.first()) else menu = true }) {
                Text(stringResource(R.string.compose_files_run_in_container, exe.name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                containers.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { menu = false; launch(c) })
                }
            }
        }
    }
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

private fun humanDuration(context: Context, ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> context.getString(
            R.string.compose_files_duration_hours_minutes,
            h,
            m,
        )
        m > 0 -> context.getString(
            R.string.compose_files_duration_minutes_seconds,
            m,
            s,
        )
        else -> context.getString(R.string.compose_files_duration_seconds, s)
    }
}
