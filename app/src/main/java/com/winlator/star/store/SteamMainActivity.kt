package com.winlator.star.store

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Entry point for the Steam store tab.
 *
 * On Android 13+ (API 33) POST_NOTIFICATIONS requires a runtime grant before
 * we can start the foreground service. We request it here and proceed in
 * onRequestPermissionsResult (or immediately if already granted / pre-API 33).
 *
 * The notification prompt appears at a natural checkpoint — after the user
 * consciously opened the Steam tab — rather than interrupting first-run setup.
 */
class SteamMainActivity : Activity() {

    companion object {
        private const val REQ_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamPrefs.init(this)

        if (needsNotificationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS,
            )
            // proceed in onRequestPermissionsResult
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
            // Proceed regardless of whether the user granted or denied —
            // the service will start but just won't show a persistent notification
            // if denied (Android silently suppresses it; no crash).
            proceed()
        }
    }

    // -------------------------------------------------------------------------

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
