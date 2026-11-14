package com.winlator.star

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.winlator.star.ui.deck.isXmbHome
import com.winlator.star.ui.screens.FileManagerScreen
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.WinlatorTheme
import java.io.File

/**
 * Themed host for the app's own File Manager in pick mode (issue #73). Every import call site — the
 * Compose screens and the legacy Java Activities/Fragments — launches this via ActivityResult and
 * reads the picked path back from the {@code selectedFile} extra. Downstream import code is
 * unchanged: callers wrap the path as {@code Uri.fromFile(File(path))}.
 *
 * Launch extras:
 *  - {@code extensions}       String[] of allowed lowercase extensions (no dot). Absent/empty = all.
 *  - {@code initialDirectory} start path. Absent falls back to the remembered dir, then Download.
 *  - {@code pickerTitle}      optional header title.
 *  - {@code containerDriveC}  optional abs path of the chosen container's C: drive; when present the
 *                             picker offers a "Drive C:" location (browse/pick a game living on C:).
 * Result: {@code selectedFile} (absolute path) on RESULT_OK.
 */
class FilePickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WinlatorTheme reads AppThemeState; init it in case MainActivity hasn't run yet.
        AppThemeState.init(this)

        val extensions = intent.getStringArrayExtra(EXTRA_EXTENSIONS)?.toList() ?: emptyList()
        val initialDir = intent.getStringExtra(EXTRA_INITIAL_DIRECTORY)?.let { File(it) }
        val title = intent.getStringExtra(EXTRA_PICKER_TITLE)
        val pickDir = intent.getBooleanExtra(EXTRA_PICK_DIRECTORY, false)
        // Optional: the chosen container's C: drive, so the picker can offer a "Drive C:" location
        // (add-a-game-from-C: flow). Absent for every other picker → C: option stays hidden.
        val driveCPath = intent.getStringExtra(EXTRA_CONTAINER_DRIVE_C)?.let { File(it) }
        // XMB mode only: the slim one-row toolbar of Tools > Files, and the dense rows / text / bars (see FileManagerRoot's dense).
        val xmb = isXmbHome(this)
        // XMB mode only: full screen, like the XMB home itself: both system bars hidden (a swipe brings them back for a moment) and the
        // window drawn right to the edges, around the camera cut-out.
        if (xmb) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        setContent {
            WinlatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().then(if (xmb) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FileManagerScreen(
                        pickMode = true,
                        pickDirMode = pickDir,
                        pickExtensions = extensions,
                        initialDir = initialDir,
                        pickerTitle = title,
                        driveCPath = driveCPath,
                        compactToolbar = xmb,
                        dense = xmb,
                        onPick = { file ->
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_FILE, file.absolutePath))
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_EXTENSIONS = "extensions"
        const val EXTRA_INITIAL_DIRECTORY = "initialDirectory"
        const val EXTRA_PICKER_TITLE = "pickerTitle"
        const val EXTRA_PICK_DIRECTORY = "pickDirectory"
        const val EXTRA_CONTAINER_DRIVE_C = "containerDriveC"
        const val EXTRA_SELECTED_FILE = "selectedFile"
    }
}
