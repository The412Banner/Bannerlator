package com.winlator.star.store

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.winlator.star.ui.theme.WinlatorTheme

class SteamMainActivity : ComponentActivity() {

    companion object {
        private const val REQ_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamPrefs.init(this)

        setContent {
            WinlatorTheme { SteamMainContent() }
        }

        if (needsNotificationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS,
            )
        } else {
            proceed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            proceed()
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    }

    private fun proceed() {
        SteamForegroundService.start(this)
        if (SteamPrefs.isLoggedIn) {
            startActivity(Intent(this, SteamGamesActivity::class.java))
        } else {
            startActivity(Intent(this, SteamLoginActivity::class.java))
        }
        finish()
    }
}

@Composable
private fun SteamMainContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1B)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color(0xFF0055FF))
    }
}
