package com.winlator.star.store

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "What shape is this network?" — the NAT / UDP verdict shown on the SteamLite pre-flight and
 * written into `steamlite.txt`, so a user on a hotspot or VPN sees WHY a game's own online mode
 * (Brawlhalla's UDP/P2P backend → "Incorrect Version", P2P lobbies that never connect) fails on one
 * network and works on another, instead of guessing.
 *
 * The probe is the classic STUN test (RFC 5389 Binding Requests) from ONE local UDP socket to a
 * few public STUN servers ([SERVERS]), decoding XOR-MAPPED-ADDRESS from each reply:
 *  - every reply reports the SAME mapped port → endpoint-independent ("cone") mapping — the NAT
 *    reuses one public port for all destinations, which is what P2P / hole-punching needs
 *    ([Nat.OPEN_CONE]);
 *  - replies DISAGREE on the port → the NAT allocates a new public port per destination
 *    (symmetric); hole-punching can't predict it, so P2P titles fail ([Nat.SYMMETRIC]) — typical
 *    of carrier hotspots (CGNAT) and many VPNs;
 *  - no reply at all → UDP doesn't get out / back ([Nat.UDP_BLOCKED]);
 *  - nothing could be sent (no DNS, no IPv4 route) → [Nat.UNKNOWN].
 * The public IPv4 comes from the mapping itself, and [Result.ipv6] says whether the device holds a
 * global IPv6 address (a hint that native v6 P2P may work even when the v4 side is strict).
 *
 * Pure JVM sockets, bounded by [probe]'s timeout (DNS + sends + the receive loop all count against
 * the same deadline), never throws, and never touches the UI thread — callers run it on their own
 * worker ([SteamSessionManager.preflightAsync] runs it beside the session step; the exit-time
 * log collector re-uses the cached answer). The last result is cached for [CACHE_MS]
 * ([cached]) and dropped when the default network changes ([invalidate], hooked from
 * `SteamRepository`'s ConnectivityManager callback) so a Wi-Fi → hotspot swap re-tests.
 */
object NetworkProbe {

    private const val TAG = "BH_STEAM_NET"

    enum class Nat { OPEN_CONE, SYMMETRIC, UDP_BLOCKED, UNKNOWN }

    data class Result(
        val nat: Nat,
        /** Public IPv4 as seen by the STUN servers (from the mapping); null when there was no reply. */
        val publicIp: String?,
        /** True when the device holds a global (non link-local / ULA) IPv6 address. */
        val ipv6: Boolean,
        /** Wall-clock the probe took, ms. */
        val ms: Int,
        /** One-line technical detail (reply count, mapped ports) for logs. Never a full IP. */
        val detail: String,
    ) {
        /** Wall-clock ms the probe finished (cache age). */
        val at: Long = System.currentTimeMillis()

        /** The public IPv4 with its last two octets masked ("172.59.x.x"), or null. */
        val maskedIp: String? get() = publicIp?.let { maskIp(it) }

        /** The pre-flight / Settings verdict line. */
        fun verdict(): String = when (nat) {
            Nat.OPEN_CONE -> "Open NAT — online play should work" + (maskedIp?.let { " (IP $it)" } ?: "")
            Nat.SYMMETRIC -> "Strict NAT (symmetric) — some games' online modes won't connect"
            Nat.UDP_BLOCKED -> "UDP blocked — online play unlikely on this network"
            Nat.UNKNOWN -> "Couldn't check"
        }

        /** Strict / blocked = worth the user's attention (amber in the UI). */
        val isWarning: Boolean get() = nat == Nat.SYMMETRIC || nat == Nat.UDP_BLOCKED

        /** The `steamlite.txt` header line (after "Network: "). Masked IP only. */
        fun logLine(): String =
            "$nat public=${maskedIp ?: "-"} ipv6=${if (ipv6) "yes" else "no"} ($ms ms)" +
                (if (detail.isNotEmpty()) " — $detail" else "")
    }

    /** Public STUN servers; three so one being down or rate-limited can't decide the verdict. */
    private val SERVERS = listOf(
        "stun.l.google.com" to 19302,
        "stun1.l.google.com" to 19302,
        "stun.cloudflare.com" to 3478,
    )

    /** How long a verdict stays valid without a network change. */
    const val CACHE_MS = 10L * 60 * 1000
    /** An UNKNOWN (couldn't even send) is re-tried much sooner — it is usually a transient DNS blip. */
    private const val UNKNOWN_CACHE_MS = 60L * 1000
    private const val DEFAULT_TIMEOUT_MS = 2500
    /** STUN retransmit schedule (RFC 5389 RTO 500 ms, doubling); requests left unanswered are resent. */
    private val RESEND_AT_MS = intArrayOf(500, 1500)

    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val MAGIC_COOKIE = 0x2112A442
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020

    @Volatile private var last: Result? = null
    private val random = SecureRandom()

    /** The cached verdict when it is still fresh (see [CACHE_MS]), else null. */
    @JvmStatic
    fun cached(): Result? {
        val r = last ?: return null
        val ttl = if (r.nat == Nat.UNKNOWN) UNKNOWN_CACHE_MS else CACHE_MS
        return if (System.currentTimeMillis() - r.at < ttl) r else null
    }

    /** Drop the cached verdict (the default network changed). */
    @JvmStatic
    fun invalidate(reason: String) {
        if (last != null) Log.i(TAG, "cached verdict dropped ($reason)")
        last = null
    }

    /** The fresh cached verdict, or a new probe (bounded by [timeoutMs]). Worker threads only. */
    @JvmStatic
    @JvmOverloads
    fun probeCachedOrFresh(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result = cached() ?: probe(timeoutMs)

    /**
     * Run the STUN probe now and cache the answer. Blocks the CALLING thread (never the UI thread)
     * for at most ~[timeoutMs] plus a few ms of interface enumeration. Never throws.
     */
    @JvmStatic
    @JvmOverloads
    fun probe(timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs.coerceAtLeast(500)
        val ipv6 = hasGlobalIpv6()
        val result = try {
            stun(deadline, ipv6)
        } catch (t: Throwable) {
            Log.w(TAG, "probe errored", t)
            Result(Nat.UNKNOWN, null, ipv6, (System.currentTimeMillis() - start).toInt(),
                "${t.javaClass.simpleName}: ${t.message}")
        }
        Log.i(TAG, "verdict: ${result.logLine()}")
        last = result
        return result
    }

    // ── STUN ──────────────────────────────────────────────────────────────────────────────────

    private class Target(val index: Int, val host: String, val addr: InetAddress, val port: Int) {
        val txId = ByteArray(12).also { random.nextBytes(it) }
        @Volatile var mappedIp: String? = null
        @Volatile var mappedPort: Int = -1
        val answered: Boolean get() = mappedPort >= 0
    }

    private fun stun(deadline: Long, ipv6: Boolean): Result {
        val start = System.currentTimeMillis()
        fun elapsed() = (System.currentTimeMillis() - start).toInt()

        // DNS in parallel, bounded to the first half of the budget. IPv4 answers only: the mapping
        // we want is the v4 one (the v6 side has no NAT to speak of) and mixing families on one
        // socket would make the port comparison meaningless.
        val resolved = ConcurrentHashMap<Int, InetAddress>()
        val dnsLatch = CountDownLatch(SERVERS.size)
        SERVERS.forEachIndexed { i, (host, _) ->
            Thread({
                try {
                    InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.let { resolved[i] = it }
                } catch (t: Throwable) {
                    Log.d(TAG, "DNS $host: ${t.message}")
                } finally {
                    dnsLatch.countDown()
                }
            }, "net-probe-dns-$i").apply { isDaemon = true }.start()
        }
        dnsLatch.await(((deadline - System.currentTimeMillis()) / 2).coerceIn(50L, 1200L), TimeUnit.MILLISECONDS)
        val targets = SERVERS.mapIndexedNotNull { i, (host, port) -> resolved[i]?.let { Target(i, host, it, port) } }
        if (targets.isEmpty()) {
            return Result(Nat.UNKNOWN, null, ipv6, elapsed(), "no STUN server resolved (DNS / no IPv4 route)")
        }

        val socket = DatagramSocket()   // one local port for every request — that is the whole test
        val localPort = socket.localPort
        try {
            fun send(t: Target) {
                val req = bindingRequest(t.txId)
                socket.send(DatagramPacket(req, req.size, t.addr, t.port))
            }
            targets.forEach { t -> try { send(t) } catch (t2: Throwable) { Log.d(TAG, "send ${t.host}: ${t2.message}") } }

            val buf = ByteArray(1500)
            var resendIdx = 0
            while (targets.any { !it.answered }) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                // Resend the unanswered requests on the RFC schedule, then wait for the next event.
                val nextResend = if (resendIdx < RESEND_AT_MS.size) start + RESEND_AT_MS[resendIdx] else Long.MAX_VALUE
                if (now >= nextResend) {
                    targets.filter { !it.answered }.forEach { t -> try { send(t) } catch (_: Throwable) {} }
                    resendIdx++
                    continue
                }
                socket.soTimeout = (minOf(deadline, nextResend) - now).coerceAtLeast(20L).toInt()
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parseReply(buf, pkt.length, targets)
            }
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }

        val answered = targets.filter { it.answered }
        val ms = elapsed()
        if (answered.isEmpty()) {
            return Result(Nat.UDP_BLOCKED, null, ipv6, ms, "0/${targets.size} STUN replies from local port $localPort")
        }
        val ports = answered.map { it.mappedPort }.distinct()
        val ips = answered.mapNotNull { it.mappedIp }.distinct()
        val publicIp = ips.firstOrNull()
        val who = "${answered.size}/${targets.size} replies"
        return if (ports.size == 1 && ips.size <= 1) {
            val p = ports[0]
            Result(Nat.OPEN_CONE, publicIp, ipv6, ms,
                "$who, same mapped port $p" + (if (p == localPort) " (port preserved)" else " (local $localPort)"))
        } else {
            Result(Nat.SYMMETRIC, publicIp, ipv6, ms,
                "$who, mapped ports differ: " + answered.joinToString("/") { "${it.mappedPort}" } +
                    (if (ips.size > 1) " (${ips.size} public IPs)" else "") + " (local $localPort)")
        }
    }

    /** RFC 5389 §6: 20-byte header, no attributes. */
    private fun bindingRequest(txId: ByteArray): ByteArray {
        val b = ByteArray(20)
        b[0] = (BINDING_REQUEST shr 8).toByte(); b[1] = BINDING_REQUEST.toByte()
        b[2] = 0; b[3] = 0                                   // message length (no attributes)
        putInt(b, 4, MAGIC_COOKIE)
        System.arraycopy(txId, 0, b, 8, 12)
        return b
    }

    /** Match a Binding Success Response to one of our transactions and record its mapped address. */
    private fun parseReply(b: ByteArray, len: Int, targets: List<Target>) {
        if (len < 20) return
        val type = u16(b, 0)
        if (type != BINDING_SUCCESS) return
        if (getInt(b, 4) != MAGIC_COOKIE) return
        val target = targets.firstOrNull { t -> (0 until 12).all { b[8 + it] == t.txId[it] } } ?: return
        val bodyLen = u16(b, 2)
        var off = 20
        val end = minOf(len, 20 + bodyLen)
        var plainIp: String? = null
        var plainPort = -1
        while (off + 4 <= end) {
            val at = u16(b, off)
            val al = u16(b, off + 2)
            val v = off + 4
            if (v + al > end) break
            if ((at == ATTR_XOR_MAPPED_ADDRESS || at == ATTR_MAPPED_ADDRESS) && al >= 8) {
                val family = b[v + 1].toInt() and 0xff
                val xor = at == ATTR_XOR_MAPPED_ADDRESS
                var port = u16(b, v + 2)
                if (xor) port = port xor (MAGIC_COOKIE ushr 16)
                val ip: String? = when {
                    family == 0x01 && al >= 8 -> {
                        val a = ByteArray(4)
                        for (i in 0 until 4) {
                            a[i] = b[v + 4 + i]
                            if (xor) a[i] = (a[i].toInt() xor b[4 + i].toInt()).toByte()
                        }
                        "${a[0].toInt() and 0xff}.${a[1].toInt() and 0xff}.${a[2].toInt() and 0xff}.${a[3].toInt() and 0xff}"
                    }
                    family == 0x02 && al >= 20 -> {
                        // IPv6 mapping (XOR key = cookie + transaction id). Kept for completeness; the
                        // requests go out over IPv4 so this branch is not expected.
                        val a = ByteArray(16)
                        for (i in 0 until 16) {
                            a[i] = b[v + 4 + i]
                            if (xor) a[i] = (a[i].toInt() xor b[4 + i].toInt()).toByte()
                        }
                        try { InetAddress.getByAddress(a).hostAddress } catch (_: Throwable) { null }
                    }
                    else -> null
                }
                if (xor) {
                    target.mappedIp = ip
                    target.mappedPort = port
                    return
                }
                plainIp = ip; plainPort = port
            }
            off = v + ((al + 3) and 3.inv())   // attributes are padded to 4 bytes
        }
        // Old-style server that only sent MAPPED-ADDRESS.
        if (plainPort >= 0 && !target.answered) {
            target.mappedIp = plainIp
            target.mappedPort = plainPort
        }
    }

    // ── IPv6 ──────────────────────────────────────────────────────────────────────────────────

    /** True when any interface holds a global-scope IPv6 address (not link-local, loopback, site-local or ULA). */
    private fun hasGlobalIpv6(): Boolean = try {
        val ifs = NetworkInterface.getNetworkInterfaces()
        var found = false
        while (!found && ifs != null && ifs.hasMoreElements()) {
            val ni = ifs.nextElement()
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isSiteLocalAddress &&
                    !a.isAnyLocalAddress && !a.isMulticastAddress && (a.address[0].toInt() and 0xfe) != 0xfc
                ) { found = true; break }
            }
        }
        found
    } catch (t: Throwable) {
        Log.d(TAG, "ipv6 enumeration: ${t.message}")
        false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** "172.59.12.34" → "172.59.x.x"; anything that isn't dotted-quad is returned masked wholesale. */
    @JvmStatic
    fun maskIp(ip: String): String {
        val parts = ip.split('.')
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.x.x" else "x.x.x.x"
    }

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
    private fun getInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }
}
