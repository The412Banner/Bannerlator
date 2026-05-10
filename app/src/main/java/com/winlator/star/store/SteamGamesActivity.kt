package com.winlator.star.store

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Steam library screen — shows only type="game" entries.
 *
 * Each row shows the Steam library portrait art (600x900) loaded async,
 * falling back to the header image (header.jpg) if portrait isn't available.
 */
class SteamGamesActivity : Activity(), SteamRepository.SteamEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var games: List<SteamGame> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Defensive init: handle cold-start after process crash (Android restarts
        // the top activity directly, bypassing SteamMainActivity which normally
        // calls SteamPrefs.init + SteamForegroundService.start).
        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)
        setContentView(buildUI())
        SteamRepository.getInstance().addListener(this)
        loadGames()
        maybeAutoSync()
    }

    override fun onResume() {
        super.onResume()
        // Refresh list from cache when returning from detail screen (installed state may have changed)
        loadGames()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // SteamRepository.SteamEventListener
    // -------------------------------------------------------------------------

    override fun onEvent(event: String) {
        when {
            event.startsWith("LibraryProgress:") -> {
                val parts = event.split(":")
                val phase = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
                ui.post {
                    statusText.text = if (phase == 0)
                        "Syncing packages ($count)…"
                    else
                        "Fetching $count app records…"
                }
            }
            event.startsWith("LibrarySynced:") -> {
                // Reload from DB and derive count from what's actually showing —
                // the event count can be 0 if Steam returned empty "no change" buffers
                // for apps that haven't changed since last request.
                ui.post {
                    loadGames()
                    statusText.text = "${games.size} games in library"
                }
            }
            event == "LoggedOut" -> {
                ui.post { finish() }
            }
            event == "Disconnected" -> {
                // Transient disconnect — auto-reconnect is in progress.
                // Don't close the activity; just show status.
                ui.post { statusText.text = "Disconnected — reconnecting…" }
            }
            event == "Connected" -> {
                // After reconnect, retry sync if still empty.
                val repo = SteamRepository.getInstance()
                if (games.isEmpty() && repo.isLoggedIn) {
                    ui.post { statusText.text = "Reconnected — syncing library…" }
                    repo.syncLibrary()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Data — only show type="game" entries
    // -------------------------------------------------------------------------

    private fun loadGames() {
        val repo = try {
            SteamRepository.getInstance()
        } catch (e: IllegalStateException) {
            // Process was restarted without going through SteamMainActivity.
            startActivity(android.content.Intent(this, SteamMainActivity::class.java))
            finish()
            return
        }
        // Use in-memory cache — avoids a SQLite read on every resume/rotate.
        // Cache is invalidated on LibrarySynced, DownloadComplete, DownloadCancelled,
        // and markUninstalled(), so installed state is always current.
        val rows = repo.getCachedGameRows()
        games = rows
            .filter { it.type == "game" }
            .map { SteamGame.fromGameRow(it) }
            .sortedBy { it.name.lowercase() }
        if (games.isNotEmpty()) {
            statusText.text = "${games.size} games in library"
        }
        refreshList()
    }

    /**
     * Auto-sync rules:
     *  - Always sync if the library is empty (new login or wiped DB)
     *  - Otherwise sync only if the last sync was more than 4 hours ago
     *  - Never auto-sync if already syncing or not logged in
     */
    private fun maybeAutoSync() {
        val repo = SteamRepository.getInstance()
        if (!repo.isLoggedIn) return
        val staleThresholdSec = 4 * 60 * 60L  // 4 hours
        val elapsed = System.currentTimeMillis() / 1000L - repo.lastSyncTime
        if (games.isEmpty() || elapsed > staleThresholdSec) {
            statusText.text = if (games.isEmpty()) "Syncing library…" else "Refreshing library…"
            repo.syncLibrary()
        }
    }

    private fun refreshList() {
        val adapter = object : ArrayAdapter<SteamGame>(this, 0, games) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val game = getItem(pos)!!
                val row = (convertView as? LinearLayout) ?: buildRow()
                // Tag the row with appId so the async image loader can detect recycling
                row.tag = game.appId

                val artView       = row.getChildAt(0) as ImageView
                val infoView      = row.getChildAt(1) as LinearLayout
                val nameView      = infoView.getChildAt(0) as TextView
                val developerView = infoView.getChildAt(1) as TextView
                val genresView    = infoView.getChildAt(2) as TextView
                val sizeView      = infoView.getChildAt(3) as TextView
                val metaView      = infoView.getChildAt(4) as TextView
                val installedLabel = infoView.getChildAt(5) as TextView
                val uninstallBtn  = infoView.getChildAt(6) as Button

                nameView.text = game.name.ifEmpty { "App ${game.appId}" }

                developerView.text = game.developer
                developerView.visibility = if (game.developer.isNotEmpty()) View.VISIBLE else View.GONE

                genresView.text = game.genres
                genresView.visibility = if (game.genres.isNotEmpty()) View.VISIBLE else View.GONE

                val sizeLabel = fmtSize(game.sizeBytes)
                sizeView.text = sizeLabel
                sizeView.visibility = if (game.sizeBytes > 0) View.VISIBLE else View.GONE

                if (game.metacriticScore > 0) {
                    metaView.text = "Metacritic: ${game.metacriticScore}"
                    metaView.setTextColor(when {
                        game.metacriticScore >= 75 -> 0xFF4CAF50.toInt()  // green
                        game.metacriticScore >= 50 -> 0xFFFFC107.toInt()  // amber
                        else                       -> 0xFFF44336.toInt()  // red
                    })
                    metaView.visibility = View.VISIBLE
                } else {
                    metaView.visibility = View.GONE
                }

                val launchBtn = infoView.getChildAt(7) as Button

                // Installed indicator + action buttons
                if (game.isInstalled) {
                    installedLabel.visibility = View.VISIBLE
                    uninstallBtn.visibility   = View.VISIBLE
                    launchBtn.visibility      = View.VISIBLE
                    uninstallBtn.setOnClickListener {
                        val db = SteamRepository.getInstance().database
                        db.markUninstalled(game.appId)
                        if (game.installDir.isNotEmpty()) {
                            Thread { java.io.File(game.installDir).deleteRecursively() }.start()
                        }
                        loadGames()
                    }
                    launchBtn.setOnClickListener { launchInstalledGame(game) }
                } else {
                    installedLabel.visibility = View.GONE
                    uninstallBtn.visibility   = View.GONE
                    launchBtn.visibility      = View.GONE
                }

                // Reset art to placeholder then kick off async load
                artView.setImageResource(android.R.color.darker_gray)
                loadCoverArt(artView, game.appId)
                return row
            }
        }
        listView.adapter = adapter
        emptyText.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility  = if (games.isEmpty()) View.GONE   else View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Cover art loading
    // -------------------------------------------------------------------------

    private fun loadCoverArt(view: ImageView, appId: Int) {
        imageCache.get(appId)?.let { cached ->
            view.setImageBitmap(cached)
            return
        }
        imageExecutor.submit {
            // Try portrait art first (600x900), fall back to wide header
            val bmp = tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900.jpg")
                   ?: tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg")
            if (bmp != null) {
                imageCache.put(appId, bmp)
                ui.post {
                    // Only set if this view still shows the same appId (not recycled)
                    val parent = view.parent as? LinearLayout
                    if (parent?.tag == appId) view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun tryBitmap(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout    = 10_000
        conn.connect()
        if (conn.responseCode == 200)
            BitmapFactory.decodeStream(conn.inputStream)
        else null
    } catch (_: Exception) { null }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = Button(this).apply {
            text = "←"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Steam Library"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshBtn = Button(this).apply {
            text = "Refresh"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(BLUE)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { SteamRepository.getInstance().syncLibrary() }
        }
        val logoutBtn = Button(this).apply {
            text = "Logout"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFFB71C1C.toInt())
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SteamGamesActivity)
                    .setTitle("Sign out of Steam?")
                    .setMessage("Your saved login will be removed. You will need to sign in again.")
                    .setPositiveButton("Sign Out") { _, _ ->
                        Thread { SteamRepository.getInstance().logout() }.start()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        header.addView(backBtn)
        header.addView(title)
        header.addView(refreshBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(6) })
        header.addView(logoutBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(header)

        // Status bar
        statusText = TextView(this).apply {
            text = "Loading library…"
            textSize = 12f
            setTextColor(GRAY)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }
        root.addView(statusText)

        // Empty state
        emptyText = TextView(this).apply {
            text = "No games found.\nIf sync just finished, tap Refresh."
            textSize = 14f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
            visibility = View.GONE
        }
        root.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Game list
        listView = ListView(this).apply {
            setBackgroundColor(BG)
            divider = null
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, pos, _ ->
                val game = games.getOrNull(pos) ?: return@setOnItemClickListener
                startActivity(Intent(this@SteamGamesActivity, SteamGameDetailActivity::class.java)
                    .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, game.appId))
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    /** Build a card row: [portrait art | name / developer / genres / size / metacritic] */
    private fun buildRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(CARD_BG)
        setPadding(0, 0, 0, 0)

        // Portrait art thumbnail (approx 2:3 ratio)
        val artWidth  = dp(80)
        val artHeight = dp(140)
        val artView = ImageView(this@SteamGamesActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
        addView(artView, LinearLayout.LayoutParams(artWidth, artHeight))

        // Right side: name + metadata stack
        val infoLayout = LinearLayout(this@SteamGamesActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }

        fun smallText() = TextView(this@SteamGamesActivity).apply {
            textSize = 11f
            setTextColor(GRAY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }

        // child 0: game name
        val nameView = TextView(this@SteamGamesActivity).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // child 1: developer
        val developerView = smallText()
        // child 2: genres
        val genresView = smallText()
        // child 3: install size
        val sizeView = smallText()
        // child 4: metacritic score (color set dynamically)
        val metaView = smallText()

        // child 5: installed indicator
        val installedLabel = TextView(this@SteamGamesActivity).apply {
            text = "● Installed"
            textSize = 11f
            setTextColor(0xFF4CAF50.toInt())  // green
            setPadding(0, dp(3), 0, 0)
            visibility = View.GONE
        }

        // child 6: uninstall button
        val uninstallBtn = Button(this@SteamGamesActivity).apply {
            text = "Uninstall"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFFB71C1C.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
        }

        // child 7: launch button (choose container + add to shortcuts)
        val launchBtn = Button(this@SteamGamesActivity).apply {
            text = "Launch / Add to Shortcuts"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2E7D32.toInt())  // green
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
        }

        val wrapLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val actionLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply { topMargin = dp(3) }
        infoLayout.addView(nameView,       wrapLp)
        infoLayout.addView(developerView,  wrapLp)
        infoLayout.addView(genresView,     wrapLp)
        infoLayout.addView(sizeView,       wrapLp)
        infoLayout.addView(metaView,       wrapLp)
        infoLayout.addView(installedLabel, wrapLp)
        infoLayout.addView(uninstallBtn,   actionLp)
        infoLayout.addView(launchBtn,      actionLp)

        addView(infoLayout, LinearLayout.LayoutParams(0, artHeight, 1f))

        // Row bottom margin (acts as divider)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(2) }
    }

    // -------------------------------------------------------------------------
    // Launch helper — choose exe then pick container via StarLaunchBridge
    // -------------------------------------------------------------------------

    private fun launchInstalledGame(game: SteamGame) {
        if (game.installDir.isEmpty()) {
            Toast.makeText(this, "Install directory not set", Toast.LENGTH_SHORT).show()
            return
        }
        val installDir = java.io.File(game.installDir)
        Thread {
            val exeFiles = mutableListOf<java.io.File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                ui.post {
                    Toast.makeText(this, "No .exe found in install directory", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val lowerTitle = game.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            // Use Steam CDN portrait cover (600x900) — StarLaunchBridge downloads it
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${game.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                ui.post { StarLaunchBridge.addToLauncher(this, game.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }
            // Multiple exes — let user pick
            val candidates = exeFiles.map { it.absolutePath }
            val labels = candidates.map { path ->
                val f = java.io.File(path)
                val parent = f.parentFile
                if (parent != null) "${parent.name}/${f.name}" else f.name
            }.toTypedArray()
            ui.post {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Select executable for \"${game.name}\"")
                    .setItems(labels) { _, which ->
                        StarLaunchBridge.addToLauncher(this, game.name, candidates[which], coverUrl)
                    }
                    .setCancelable(true)
                    .show()
            }
        }.start()
    }


    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val BG      = Color.parseColor("#1B1B1B")
        private val CARD_BG = Color.parseColor("#252525")
        private val GRAY    = Color.parseColor("#AAAAAA")
        private val BLUE    = Color.parseColor("#4FC3F7")

        // Shared LRU image cache (4 MB cap) and fixed thread pool across instances
        private val imageCache = LruCache<Int, Bitmap>(4 * 1024 * 1024)
        private val imageExecutor = Executors.newFixedThreadPool(4)
    }
}
