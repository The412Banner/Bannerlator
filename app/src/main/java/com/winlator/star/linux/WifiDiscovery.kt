package com.winlator.star.linux

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.preference.PreferenceManager

/**
 * Optional Wi-Fi discovery for the Linux runtime: Android's Wi-Fi names and scan results, shown on
 * Steam's network page through bannerlator-netmanager. Android gates both behind Location, so this
 * is opt-in, asked for only from the Linux Steam entry's settings after an explanation. Location
 * is used for nothing else - no coordinates are ever read.
 *
 * App-wide rather than per entry: the grant is the app's, and the switch applies at once, a running
 * session included (LinuxNetworkLinkComponent re-checks it every second).
 *
 * Port of DroidDeck's core/WifiDiscovery (Droid-Deck/DroidDeck #150).
 */
object WifiDiscovery {
    const val PREF_ENABLED = "linux_wifi_discovery"
    const val PREF_ASKED = "linux_wifi_discovery_asked"

    val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    /** The user's choice. A Location grant alone never opts them in. */
    fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_ENABLED, on).apply()
    }

    /** Whether the system prompt was ever shown - with no rationale left, a denial is permanent. */
    fun asked(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ASKED, false)

    fun setAsked(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_ASKED, true).apply()
    }

    // targetSdk 28 accepts either grant for scan results and the connected SSID; FINE is only
    // required from targetSdk 29, NEARBY_WIFI_DEVICES only from targetSdk 33.
    fun permissionGranted(context: Context): Boolean = permissions.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun locationEnabled(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled == true

    fun available(context: Context): Boolean = enabled(context) && permissionGranted(context) && locationEnabled(context)
}
