package com.winlator.star.linux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.winlator.star.xenvironment.EnvironmentComponent
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device's network link, published for the Linux runtime's processes.
 *
 * Android denies an app the rtnetlink dump behind getifaddrs() and if_nameindex(), the hardware
 * address ioctl and every table under /proc/net. Wine builds its adapter, address, route and
 * neighbour tables from those, so a game under the runtime saw no adapter, no address, no gateway
 * and no MAC - and one that checks its adapters before going online waited for good. The link the
 * app can see through ConnectivityManager is written here, kept current while the session runs,
 * and the session shim answers whatever the kernel refuses from it (tools/linuxfs/preload/netif.c).
 *
 * Alongside it goes etc/bannerlator-network.json, the NetworkManager view bannerlator-netmanager
 * gives Steam: transport, connectivity, metering and addresses always; Wi-Fi names, nearby access
 * points and scans only with the user's opt-in (WifiDiscovery), which Android gates behind Location.
 *
 * Shape follows WinNative's LinuxNetworkLinkComponent (maxjivi05, f76a5c4a), GPL-3.0; the network
 * state and Wi-Fi discovery are DroidDeck's (Droid-Deck/DroidDeck #150).
 */
class LinuxNetworkLinkComponent(
    context: Context,
    private val rootDir: File,
) : EnvironmentComponent() {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var currentProperties: LinkProperties? = null
    private var currentCapabilities: NetworkCapabilities? = null
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scanHandler = Handler(Looper.getMainLooper())
    @Volatile private var wifiReceiver: BroadcastReceiver? = null
    private var scanRequestStamp = 0L
    private var lastScanRequest = -SCAN_INTERVAL_MS
    @Volatile private var lastNamesAllowed = false
    // Once a second: the opt-in turned on or off mid-session (the app's switch, a revoked grant,
    // Location toggled), and the scans the netmanager asks for when Steam's page wants fresh ones.
    private val scanRequests = object : Runnable {
        override fun run() {
            if (canReadWifiNames() != lastNamesAllowed) {
                synchronized(lock) { writeNetworkState(currentProperties, currentCapabilities) }
                requestWifiScan()
            }
            val stamp = File(rootDir, SCAN_REQUEST_FILE).lastModified()
            if (stamp != 0L && stamp != scanRequestStamp) {
                scanRequestStamp = stamp
                requestWifiScan()
            }
            if (wifiReceiver != null) scanHandler.postDelayed(this, 1000)
        }
    }

    /** Called before the session starts, so its first process already sees the link. */
    fun publish() = synchronized(lock) {
        currentNetwork = connectivity.activeNetwork
        currentProperties = currentNetwork?.let(connectivity::getLinkProperties)
        currentCapabilities = currentNetwork?.let(connectivity::getNetworkCapabilities)
        write(currentProperties, currentCapabilities)
    }

    override fun start() {
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(lock) {
                currentNetwork = network
                currentProperties = null
                currentCapabilities = null
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
                if (network == currentNetwork) {
                    currentCapabilities = capabilities
                    if (currentProperties != null) write(currentProperties, capabilities)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = synchronized(lock) {
                if (network == currentNetwork) {
                    currentProperties = properties
                    write(properties, currentCapabilities)
                }
            }

            override fun onLost(network: Network) = synchronized(lock) {
                // A handover can report the old link lost after the new default arrived.
                if (network == currentNetwork) {
                    currentNetwork = null
                    currentProperties = null
                    currentCapabilities = null
                    write(null, null)
                }
            }
        }
        synchronized(lock) { callback = registered }
        connectivity.registerDefaultNetworkCallback(registered)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                synchronized(lock) { writeNetworkState(currentProperties, currentCapabilities) }
            }
        }
        wifiReceiver = receiver
        appContext.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        })
        scanRequestStamp = File(rootDir, SCAN_REQUEST_FILE).lastModified()
        scanHandler.post(scanRequests)
        scanHandler.post { requestWifiScan() }
    }

    override fun stop() {
        scanHandler.removeCallbacks(scanRequests)
        wifiReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Wi-Fi receiver was already gone", e)
            }
        }
        wifiReceiver = null
        val registered = synchronized(lock) { callback.also { callback = null } } ?: return
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Network callback was already gone", e)
        }
    }

    /** Opt-in on, a Location grant, and Location on - otherwise no name or scan leaves Android. */
    private fun canReadWifiNames(): Boolean = WifiDiscovery.available(appContext)

    @Suppress("DEPRECATION")
    private fun requestWifiScan() {
        val now = SystemClock.elapsedRealtime()
        // Android allows a foreground app four scans in two minutes; Steam's page asks more often.
        val wifi = wifiManager ?: return
        if (!canReadWifiNames() || !wifi.isWifiEnabled || now - lastScanRequest < SCAN_INTERVAL_MS) return
        lastScanRequest = now
        try {
            Log.i(TAG, "Wi-Fi scan requested: accepted=${wifi.startScan()}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi scan permission unavailable")
        }
        synchronized(lock) { writeNetworkState(currentProperties, currentCapabilities) }
    }

    private fun write(properties: LinkProperties?, capabilities: NetworkCapabilities?) {
        val file = File(rootDir, LINK_FILE)
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                val staged = File(file.path + ".staged")
                staged.writeText(describe(properties))
                if (!staged.renameTo(file)) throw IOException("Could not replace $file")
            } catch (e: IOException) {
                Log.w(TAG, "Could not publish the network link", e)
            }
            writeNetworkState(properties, capabilities)
        }
    }

    /** NetworkManager's view of Android, for bannerlator-netmanager. Kept apart from netif.c's format. */
    @Suppress("DEPRECATION")
    private fun writeNetworkState(properties: LinkProperties?, capabilities: NetworkCapabilities?) {
        val namesAllowed = canReadWifiNames()
        lastNamesAllowed = namesAllowed
        val wifi = wifiManager
        val scans = if (namesAllowed && wifi != null && wifi.isWifiEnabled) {
            try { wifi.scanResults.orEmpty() } catch (e: SecurityException) { emptyList() }
        } else emptyList()
        val transport = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) properties?.mtu ?: 0 else 0
        val state = JSONObject().apply {
            put("transport", transport)
            put("interface", properties?.interfaceName ?: "android")
            put("mtu", if (mtu > 0) mtu else DEFAULT_MTU)
            put("connected", properties != null && capabilities != null)
            put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("captivePortal", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            put("metered", capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) })
            put("wifiEnabled", wifiManager?.isWifiEnabled == true)
            put("scanAllowed", namesAllowed)
            put("lastScan", scans.maxOfOrNull { it.timestamp / 1000 } ?: -1L)
            put("accessPoints", JSONArray().apply {
                scans.filter { !it.SSID.isNullOrEmpty() }.forEach {
                    put(JSONObject().apply {
                        put("ssid", it.SSID)
                        put("bssid", it.BSSID)
                        put("strength", WifiManager.calculateSignalLevel(it.level, 101))
                        put("frequency", it.frequency)
                        put("capabilities", it.capabilities)
                        put("lastSeen", it.timestamp / 1_000_000)
                    })
                }
            })
            put("addresses", JSONArray().apply {
                properties?.linkAddresses.orEmpty().forEach {
                    put(JSONObject().put("address", it.address.hostAddress?.substringBefore('%')).put("prefix", it.prefixLength))
                }
            })
            put("gateways", JSONArray().apply {
                properties?.routes.orEmpty().filter { it.isDefaultRoute }.forEach {
                    it.gateway?.takeUnless { address -> address.isAnyLocalAddress }?.let { address ->
                        put(address.hostAddress?.substringBefore('%'))
                    }
                }
            })
            put("dns", JSONArray().apply {
                properties?.dnsServers.orEmpty().filter { !it.isLinkLocalAddress }.forEach {
                    put(it.hostAddress?.substringBefore('%'))
                }
            })
            if (transport == "wifi") {
                val connectionInfo = try { wifiManager?.connectionInfo } catch (e: SecurityException) { null }
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (capabilities?.transportInfo as? WifiInfo) ?: connectionInfo
                } else {
                    connectionInfo
                }
                if (info != null) {
                    // Android redacts the name without Location. The transport needs no permission;
                    // a hidden name simply shows as Wi-Fi in Steam.
                    if (namesAllowed) {
                        val namedInfo = connectionInfo ?: info
                        namedInfo.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }
                            ?.removeSurrounding("\"")?.let { put("ssid", it) }
                        namedInfo.bssid?.takeUnless { it == "02:00:00:00:00:00" }?.let { put("bssid", it) }
                    }
                    put("strength", WifiManager.calculateSignalLevel(info.rssi, 101))
                    put("frequency", info.frequency.coerceAtLeast(0))
                    put("bitrate", info.linkSpeed.coerceAtLeast(0) * 1000)
                }
            }
        }
        try {
            val file = File(rootDir, STATE_FILE)
            file.parentFile?.mkdirs()
            val staged = File(file.path + ".staged")
            staged.writeText(state.toString())
            if (!staged.renameTo(file)) throw IOException("Could not replace $file")
        } catch (e: IOException) {
            Log.w(TAG, "Could not publish the network state", e)
        }
    }

    private fun describe(properties: LinkProperties?): String {
        val addresses = properties?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address || it.address is Inet6Address }
        val name = properties?.interfaceName?.takeIf { addresses.isNotEmpty() } ?: OFFLINE_NAME
        val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) properties?.mtu ?: 0 else 0
        return buildString {
            append("if $name $LINK_INDEX ${if (mtu > 0) mtu else DEFAULT_MTU}\n")
            append("mac ${seededMac()}\n")
            if (addresses.isEmpty()) {
                append("addr $OFFLINE_ADDRESS\n")
                return@buildString
            }
            for (address in addresses) {
                append("addr ${address.address.hostAddress?.substringBefore('%')} ${address.prefixLength}\n")
            }
            for (family in listOf(Inet4Address::class.java, Inet6Address::class.java)) {
                val gateway = properties?.routes.orEmpty()
                    .firstOrNull { it.isDefaultRoute && family.isInstance(it.gateway) && !it.gateway!!.isAnyLocalAddress }
                    ?.gateway ?: continue
                append("gw ${gateway.hostAddress?.substringBefore('%')}\n")
            }
        }
    }

    /**
     * A stable, locally administered address for this device. Android hides the real one, and a
     * title should see the same hardware identity every session.
     */
    private fun seededMac(): String {
        val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "bannerlator"
        val bytes = java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        val b0 = (bytes[0].toInt() and 0xfc) or 0x02
        return listOf(b0, bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt(), bytes[4].toInt(), bytes[5].toInt())
            .joinToString(":") { "%02x".format(it and 0xff) }
    }

    companion object {
        private const val TAG = "LinuxNetworkLink"
        private const val LINK_FILE = "etc/bannerlator-net"
        private const val STATE_FILE = "etc/bannerlator-network.json"
        private const val SCAN_REQUEST_FILE = "etc/bannerlator-wifi-scan-request"
        private const val LINK_INDEX = 2
        private const val DEFAULT_MTU = 1500
        private const val OFFLINE_NAME = "eth0"
        private const val OFFLINE_ADDRESS = "10.0.0.2 24"
        private const val SCAN_INTERVAL_MS = 30_000L
    }
}
