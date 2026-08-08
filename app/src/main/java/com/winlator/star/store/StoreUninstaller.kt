package com.winlator.star.store

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R
import kotlinx.coroutines.delay
import java.io.File

/**
 * Shared uninstall worker + progress UI for the store screens.
 *
 * Historically each store screen fired `Thread { File(dir).deleteRecursively() }.start()`
 * fire-and-forget, flipped the DB/UI to "uninstalled" immediately, and gave the user no
 * confirmation — so a multi-GB delete was still running (invisibly) after the UI already
 * claimed the game was gone. [run] does the DB mark + recursive delete off the UI thread,
 * VERIFIES the install dir is actually gone, then reports success/failure back on the main
 * thread so the caller can dismiss its spinner and toast the real outcome.
 */
object StoreUninstaller {

    /**
     * @param installDir the game's install directory (may be null/empty → nothing to delete).
     * @param mark store-specific DB bookkeeping (e.g. markUninstalled); runs on the worker thread.
     * @param onResult invoked on the MAIN thread; `true` = dir fully removed (or nothing to remove).
     */
    fun run(installDir: String?, mark: () -> Unit, onResult: (Boolean) -> Unit) {
        Thread {
            mark()
            val dir = installDir?.takeIf { it.isNotEmpty() }?.let { File(it) }
            val ok = if (dir != null && dir.exists()) {
                dir.deleteRecursively()
                !dir.exists()
            } else {
                true
            }
            Handler(Looper.getMainLooper()).post { onResult(ok) }
        }.start()
    }
}

/**
 * Themed, auto-dismissing confirmation shown after an uninstall finishes.
 *
 * Replaces `Toast` — on this device's ROM (app targets SDK 28) system Toasts render as an
 * empty black box, so we draw our own snackbar-style bar inside the app's Compose theme.
 * Non-interactive (doesn't consume touches) and clears itself after ~2.2 s via [onTimeout].
 */
@Composable
internal fun UninstallResultBar(message: String, onTimeout: () -> Unit) {
    LaunchedEffect(message) {
        delay(2200)
        onTimeout()
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** Blocking, non-dismissable spinner shown while an uninstall is deleting files. */
@Composable
internal fun UninstallProgressDialog(gameName: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    text = stringResource(R.string.download_manager_uninstalling, gameName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}
