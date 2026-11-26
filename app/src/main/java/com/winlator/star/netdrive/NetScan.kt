package com.winlator.star.netdrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Finds file shares on the home network, two ways at once:
 *  - announcements: servers that announce an SMB share over mDNS / Bonjour (Synology, QNAP, TrueNAS, macOS, Samba
 *    with Avahi) are found by name through Android's NsdManager;
 *  - a sweep: every address of this device's Wi-Fi / Ethernet subnet (at most 254 of them) is asked for the SMB port,
 *    which finds the servers that announce nothing (routers with a USB drive, Windows PCs, OpenWrt boxes).
 * Then each server found is asked for its shares as a guest: "open" = it lists them without a password.
 * Only on Wi-Fi or Ethernet, never over mobile data.
 */
object NetScan {
    private const val TAG = "BL_NETDRIVE"
    private const val SMB_PORT = 445

    /** One server: [open] true = guest listing works, false = it wants a login, null = it didn't say. */
    data class Found(val host: String, val name: String, val open: Boolean?, val shares: List<String>)

    /** This device's IPv4 address and prefix on Wi-Fi or Ethernet, or null when on neither. */
    fun localNet(ctx: Context): Pair<Inet4Address, Int>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (n in nets.distinct()) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
                ?: continue
            return (la.address as Inet4Address) to la.prefixLength
        }
        return null
    }

    /** True when [host]:[port] accepts a connection within [timeoutMs]. Blocking. */
    fun reachable(host: String, port: Int, timeoutMs: Int = 1_500): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    /** Runs both searches and the guest check; [onFound] gets the list as it grows. */
    suspend fun scan(ctx: Context, onFound: (List<Found>) -> Unit): List<Found> = coroutineScope {
        val (me, prefix) = localNet(ctx) ?: return@coroutineScope emptyList()
        val names = ConcurrentHashMap<String, String>()
        val mdns = async { runCatching { mdnsSmb(ctx, names) }.onFailure { Log.w(TAG, "mDNS: ${it.message}") } }
        val swept = sweep(me, prefix)
        mdns.await()
        val hosts = (swept + names.keys).filter { it != me.hostAddress }.distinct()
        val results = ConcurrentHashMap<String, Found>()
        fun publish() = onFound(results.values.sortedBy { it.name.lowercase() })
        hosts.map { h ->
            async(Dispatchers.IO) {
                val name = names[h] ?: reverseName(h) ?: h
                results[h] = Found(h, name, null, emptyList())
                publish()
                val guest = NetDrive(id = "found:$h", kind = NetKind.SMB, name = name, host = h)
                val outcome = withTimeoutOrNull(8_000) {
                    runCatching { NetFs.list(ctx, guest, "") }
                }
                results[h] = when {
                    outcome == null -> Found(h, name, null, emptyList())
                    outcome.isSuccess -> Found(h, name, true, outcome.getOrThrow().filter { it.isDir }.map { it.name })
                    (outcome.exceptionOrNull()?.message ?: "").startsWith("Wrong user") -> Found(h, name, false, emptyList())
                    else -> Found(h, name, false, emptyList())
                }
                publish()
            }
        }.awaitAll()
        results.values.sortedBy { it.name.lowercase() }
    }

    /** Every address of the subnet (capped to the /24 around [me]) that answers on the SMB port. */
    private suspend fun sweep(me: Inet4Address, prefix: Int): List<String> {
        val b = me.address
        val base = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
        val p = prefix.coerceIn(24, 30)
        val mask = (-1 shl (32 - p))
        val net = base and mask
        val count = (1 shl (32 - p)) - 2
        val pool = Executors.newFixedThreadPool(64)
        val dispatcher = pool.asCoroutineDispatcher()
        try {
            return coroutineScope {
                (1..count).map { i ->
                    async(dispatcher) {
                        val a = net + i
                        val ip = "${(a ushr 24) and 255}.${(a ushr 16) and 255}.${(a ushr 8) and 255}.${a and 255}"
                        if (a != base && reachable(ip, SMB_PORT, 400)) ip else null
                    }
                }.awaitAll().filterNotNull()
            }
        } finally {
            dispatcher.close()
        }
    }

    /** The host's DNS name (first label), e.g. "nighthawk-nas" from the router's DNS. */
    private suspend fun reverseName(ip: String): String? = withTimeoutOrNull(2_000) {
        withContext(Dispatchers.IO) {
            runCatching {
                val n = InetAddress.getByName(ip).canonicalHostName
                if (n == ip || n.isBlank()) null else n.substringBefore('.')
            }.getOrNull()
        }
    }

    /** Servers announcing an SMB share over mDNS: ip -> announced name. */
    private suspend fun mdnsSmb(ctx: Context, out: MutableMap<String, String>) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val seen = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(seen) { seen += info } }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            delay(2_500)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        // One resolve at a time (older Android refuses a second while one runs).
        for (info in synchronized(seen) { seen.toList() }) {
            val r = withTimeoutOrNull(2_000) { resolve(nsd, info) } ?: continue
            @Suppress("DEPRECATION")
            val ip = (r.host as? Inet4Address)?.hostAddress ?: continue
            out[ip] = r.serviceName
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { c ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { if (c.isActive) c.resume(null) }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (c.isActive) c.resume(serviceInfo) }
        })
    }
}
