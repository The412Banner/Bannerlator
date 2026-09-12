package com.winlator.star

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.winlator.star.ui.screens.UnpackArchiveScreen
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.WinlatorTheme

/**
 * Themed host for the File Manager's "Unpack Archive" flow ([UnpackArchiveScreen]). Launched from
 * the File Manager's ⋮ menu with the source archive's path, and reopened by the unpack notification.
 */
class UnpackArchiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeState.init(this)   // in case MainActivity hasn't run yet in this process

        val archivePath = intent.getStringExtra(EXTRA_ARCHIVE_PATH).orEmpty()
        setContent {
            WinlatorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UnpackArchiveScreen(archivePath = archivePath, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archivePath"

        fun intent(context: Context, archivePath: String): Intent =
            Intent(context, UnpackArchiveActivity::class.java)
                .putExtra(EXTRA_ARCHIVE_PATH, archivePath)
    }
}
