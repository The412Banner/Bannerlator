package com.winlator.star.netdrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What the file manager's Network section shows, as Compose state: the saved drives, whether each one can be
 * reached right now, the shares found on this network and whether a search is running. Searches and reach checks
 * run here (not in a screen), so leaving the page doesn't cut one off; a network change clears both and, while a
 * Home page is showing, searches again.
 */
object NetHub {
    private const val RESCAN_AFTER_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var saved by mutableStateOf<List<NetDrive>>(emptyList())
        private set
    var found by mutableStateOf<List<NetScan.Found>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    /** False when there is no Wi-Fi / Ethernet to search on. */
    var onLan by mutableStateOf(true)
        private set
    /** Saved drive id -> reachable; missing = being checked. */
    val reach = mutableStateMapOf<String, Boolean>()

    /** A game being copied from a drive to this device ("Add to Games"); null when none. */
    class GameCopy(val name: String) {
        var label by mutableStateOf("")
        var progress by mutableStateOf<Float?>(null)
    }
    var copying by mutableStateOf<GameCopy?>(null)
        private set
    private var copyJob: Job? = null

    /**
     * Runs [block] as THE game copy, in the app's scope rather than a screen's, so leaving the file manager doesn't
     * stop it. One at a time: false when another is running.
     */
    fun startGameCopy(name: String, block: suspend (GameCopy) -> Unit): Boolean {
        if (copyJob?.isActive == true) return false
        val c = GameCopy(name)
        copying = c
        copyJob = scope.launch {
            try { block(c) } finally { copying = null }
        }
        return true
    }

    fun cancelGameCopy() {
        copyJob?.cancel()
    }

    /** A streamed game and the bytes its stand-in takes now. */
    class StreamInfo(val game: NetStream.Game, val bytes: Long)
    var streams by mutableStateOf<List<StreamInfo>>(emptyList())
        private set
    var streamsTotal by mutableStateOf(0L)
        private set
    private var streamsJob: Job? = null

    /** Re-reads the streamed games and how much each keeps (a walk of their stand-ins, off the main thread). */
    fun refreshStreams() {
        if (!::app.isInitialized || streamsJob?.isActive == true) return
        streamsJob = scope.launch {
            val list = NetStream.games(app).map { StreamInfo(it, NetStream.cachedBytes(it)) }
            streams = list
            streamsTotal = list.sumOf { it.bytes }
        }
    }

    private var loaded = false
    private var lastScan = 0L
    private var scanJob: Job? = null
    private var reachJob: Job? = null
    private var homeViews = 0
    private var watching = false
    private lateinit var app: Context

    /** A Home page with the Network section is showing ([homeShown]) or not ([homeHidden]). */
    fun homeShown(ctx: Context, force: Boolean = false) {
        app = ctx.applicationContext
        homeViews++
        watch()
        refresh(force)
    }

    fun homeHidden() {
        if (homeViews > 0) homeViews--
    }

    /** Reloads the saved list, rechecks reach and searches again when the last search is old (or [force]). */
    fun refresh(force: Boolean = false) {
        if (!::app.isInitialized) return
        NetEngine.refresh(app)
        if (!loaded) { saved = NetDriveStore.list(app); loaded = true }
        refreshStreams()
        if (NetEngine.state != NetEngine.State.Installed) return
        checkReach()
        val now = System.currentTimeMillis()
        if (force || now - lastScan > RESCAN_AFTER_MS) scan()
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        lastScan = System.currentTimeMillis()
        scanJob = scope.launch {
            onLan = NetScan.localNet(app) != null
            if (!onLan) { found = emptyList(); return@launch }
            scanning = true
            try {
                found = NetScan.scan(app) { list -> found = list }
            } finally {
                scanning = false
            }
        }
    }

    private fun checkReach() {
        if (reachJob?.isActive == true) return
        val drives = saved
        reachJob = scope.launch {
            coroutineScope {
                drives.map { d -> async { d.id to NetScan.reachable(d.host, d.realPort) } }.awaitAll()
            }.forEach { (id, ok) -> reach[id] = ok }
        }
    }

    /** Saves [d] (new, or edited when its id is already saved). */
    fun save(ctx: Context, d: NetDrive) {
        NetDriveStore.save(ctx, d)
        saved = NetDriveStore.list(ctx)
        reach[d.id] = true
    }

    fun remove(ctx: Context, id: String) {
        NetDriveStore.remove(ctx, id)
        saved = NetDriveStore.list(ctx)
        reach.remove(id)
    }

    /** A saved drive for this found server's share (or its top when [share] is null), if there is one. */
    fun savedFor(f: NetScan.Found, share: String? = null): NetDrive? =
        saved.firstOrNull { it.kind == NetKind.SMB && it.host.equals(f.host, true) && (share == null || it.folder.trim('/') == share) }

    private fun watch() {
        if (watching) return
        watching = true
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
            })
        }
    }

    private fun changed() {
        found = emptyList()
        reach.clear()
        lastScan = 0L
        // Connections rclone keeps to the old network are dead now.
        if (homeViews > 0) scope.launch { refresh() }
    }
}
