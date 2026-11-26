package com.winlator.star.ui.screens

import androidx.compose.runtime.Composable
import com.winlator.star.ui.files.FileManagerRoot
import java.io.File

/**
 * The File Manager tab, the Deck Tools "Files" page, the Log Manager's folder view and the in-app picker
 * ([com.winlator.star.FilePickerActivity]). The screen itself lives in [com.winlator.star.ui.files].
 *
 * @param pickMode browse to pick a file (only files with [pickExtensions] are shown; empty = all).
 * @param pickDirMode browse to pick a folder ("Select" confirms the folder on screen).
 * @param initialDir where to open; null = Home (a picker resumes at the folder it was last used in).
 * @param driveCPath a container's C: drive the add-a-game flow already knows, offered as a Home tile.
 * @param compactToolbar the Deck shell's Tools > Files: one slim toolbar row (path and paste chip in it) instead of
 *   the tall pill, the paste bar and the path line. Every other host keeps the default (false).
 * @param dense the XMB mode's in-app picker: everything slimmer (rows ~40 dp, smaller icons and text, a slim toolbar and
 *   Select bar), so more rows fit on a landscape phone. Only [com.winlator.star.FilePickerActivity] sets it, in the XMB mode.
 */
@Composable
fun FileManagerScreen(
    pickMode: Boolean = false,
    pickDirMode: Boolean = false,
    pickExtensions: List<String> = emptyList(),
    initialDir: File? = null,
    pickerTitle: String? = null,
    driveCPath: File? = null,
    onPick: ((File) -> Unit)? = null,
    compactToolbar: Boolean = false,
    dense: Boolean = false,
) = FileManagerRoot(
    pickMode = pickMode,
    pickDirMode = pickDirMode,
    pickExtensions = pickExtensions,
    initialDir = initialDir,
    pickerTitle = pickerTitle,
    driveCPath = driveCPath,
    onPick = onPick,
    compactToolbar = compactToolbar,
    dense = dense,
)
