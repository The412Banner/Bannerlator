package com.winlator.star.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.winlator.star.FilePickerActivity
import java.io.File

/**
 * Helper for launching the app's built-in file picker — the real File Manager (FileManagerScreen)
 * hosted in pick mode by [FilePickerActivity] — instead of the system SAF picker. The app holds
 * MANAGE_EXTERNAL_STORAGE, so direct path access is reliable across OEM skins where SAF is slow or
 * silently fails (issue #73).
 *
 * The returned path is wrapped as a file:// Uri via [asUri] so existing importers keep reading it
 * through contentResolver.openInputStream(uri) with no downstream changes.
 */
object InAppFilePicker {

    // Common allowed-extension sets, shared across import call sites.
    val WCP = arrayOf("wcp", "tzst", "xz", "zst", "zip")           // content packs (proton/dxvk/box64/…)
    val ICP = arrayOf("icp")                                        // input control profiles
    val IMAGES = arrayOf("png", "jpg", "jpeg", "webp", "bmp", "gif") // wallpapers / custom icons
    val DLL = arrayOf("dll")                                        // e.g. Lossless.dll (frame gen)
    val DRIVER = arrayOf("zip", "adpkg")                            // AdrenoTools driver packages
    val SAVE = arrayOf("zip", "tzst", "zst", "xz", "tar")           // exported save archives
    val SF2 = arrayOf("sf2")                                        // MIDI sound fonts
    val SHORTCUT = arrayOf("exe", "desktop", "lnk")                 // shortcut import sources
    val JSON = arrayOf("json")                                      // community config files (import)

    /**
     * @param extensions allowed lowercase extensions without the dot (e.g. arrayOf("wcp","zst")).
     *                   Empty = all files selectable.
     * @param title      optional header title.
     * @param initialDir optional start directory; when null the picker resumes at the remembered
     *                   last directory, then Download, then storage root.
     */
    fun buildIntent(
        context: Context,
        extensions: Array<String> = emptyArray(),
        title: String? = null,
        initialDir: String? = null,
    ): Intent = Intent(context, FilePickerActivity::class.java).apply {
        putExtra(FilePickerActivity.EXTRA_EXTENSIONS, extensions)
        title?.let { putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, it) }
        initialDir?.let { putExtra(FilePickerActivity.EXTRA_INITIAL_DIRECTORY, it) }
    }

    /**
     * Build an intent that opens the File Manager in DIRECTORY-pick mode (issue #70): only folders
     * are listed and a "Select this folder" action returns the browsed directory's absolute path
     * (read back via [pickedPath]).
     */
    fun buildDirIntent(
        context: Context,
        title: String? = null,
        initialDir: String? = null,
    ): Intent = Intent(context, FilePickerActivity::class.java).apply {
        putExtra(FilePickerActivity.EXTRA_PICK_DIRECTORY, true)
        title?.let { putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, it) }
        initialDir?.let { putExtra(FilePickerActivity.EXTRA_INITIAL_DIRECTORY, it) }
    }

    /** Extract the picked path from an OK result's data intent, or null. */
    fun pickedPath(data: Intent?): String? =
        data?.getStringExtra(FilePickerActivity.EXTRA_SELECTED_FILE)

    /** Wrap a picked path as a file:// Uri that openInputStream() accepts. */
    fun asUri(path: String): Uri = Uri.fromFile(File(path))

    /** Convenience: OK-result data -> file:// Uri, or null. */
    fun pickedUri(data: Intent?): Uri? = pickedPath(data)?.let { asUri(it) }
}
