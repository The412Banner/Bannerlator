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
) = FileManagerRoot(
    pickMode = pickMode,
    pickDirMode = pickDirMode,
    pickExtensions = pickExtensions,
    initialDir = initialDir,
    pickerTitle = pickerTitle,
    driveCPath = driveCPath,
    onPick = onPick,
)
