package com.winlator.star.ui.deck

import com.winlator.star.ui.simple.T
import com.winlator.star.ui.simple.L
import android.content.Context
import android.text.format.DateUtils
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.container.ShortcutOrder
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.ui.screens.EXTRA_LAST_PLAYED
import com.winlator.star.ui.screens.isAmazonShortcut
import com.winlator.star.ui.screens.isCustomOriginShortcut
import com.winlator.star.ui.screens.isGogShortcut
import com.winlator.star.ui.screens.isXboxShortcut
import com.winlator.star.ui.screens.isSteamOriginShortcut

/*
 * What the Deck pages show about a game, read from the same places the classic screens read it:
 * playtime and play count from the "playtime_stats" prefs XServerDisplayActivity writes, the last
 * launch from the shortcut's own Extra Data (written by the shared launch path).
 */

/**
 * Total playtime (ms), play count and last launch (epoch ms, 0 = never launched since it was tracked).
 * [addedAt] is when the shortcut was created (0 = before it was recorded). It only feeds [recentKey]:
 * [lastPlayed] stays the one thing that says a game was played.
 */
internal data class PlayStats(val ms: Long, val count: Int, val lastPlayed: Long, val addedAt: Long = 0L) {
    /** What the recent order sorts on, newest first: a new game counts as the latest played until it is really played. */
    val recentKey: Long get() = ShortcutOrder.recentKey(lastPlayed, addedAt)
}

internal fun readPlayStats(context: Context, shortcuts: List<Shortcut>): Map<String, PlayStats> {
    val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
    return shortcuts.associate { s ->
        s.file.path to PlayStats(
            ms = prefs.getLong("${s.name}_playtime", 0L),
            count = prefs.getInt("${s.name}_play_count", 0),
            lastPlayed = lastPlayedOf(s),
            addedAt = addedAtOf(s),
        )
    }
}

/** A game's saved FPS (XServerDisplayActivity.saveSessionFps): the last session's average and 1% low, and the averages of up to 24 sessions. */
internal data class FpsStats(val lastAvg: Float, val lastLow: Int, val lastAt: Long, val history: List<Int>)

internal fun readFpsStats(context: Context, s: Shortcut): FpsStats? {
    // Settings › Performance › Save FPS of each session off: the tiles show playtime instead.
    if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("save_session_fps", true)) return null
    val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
    val k = "${s.name}_fps"
    val avg = prefs.getFloat("${k}_last_avg", 0f)
    if (avg <= 0f) return null
    return FpsStats(
        lastAvg = avg,
        lastLow = prefs.getInt("${k}_last_low", 0),
        lastAt = prefs.getLong("${k}_last_at", 0L),
        history = prefs.getString("${k}_history", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
    )
}

internal fun lastPlayedOf(s: Shortcut): Long = s.getExtra(EXTRA_LAST_PLAYED, "").toLongOrNull() ?: 0L

internal fun addedAtOf(s: Shortcut): Long = ShortcutOrder.parse(s.getExtra(ShortcutOrder.EXTRA_ADDED_AT, ""))

/** The recent-order key of one shortcut, for callers that don't hold its [PlayStats]. Sort descending. */
internal fun recentKeyOf(s: Shortcut): Long = ShortcutOrder.recentKey(lastPlayedOf(s), addedAtOf(s))

internal fun playtimeLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> L("Not played yet")
        minutes < 60L -> L("%1\$s min played", minutes.coerceAtLeast(1L))
        else -> L("%1\$s h played", "%.1f".format(ms / 3_600_000.0))
    }
}

/** Short playtime for a stat tile: "41.5 h", "25 min", or a dash. */
internal fun playtimeShort(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "—"
        minutes < 60L -> L("%1\$s min", minutes.coerceAtLeast(1L))
        else -> L("%1\$s h", "%.1f".format(ms / 3_600_000.0))
    }
}

/** "Today", "Yesterday", "3 days ago", "Sep 28"; null when the game has no recorded launch. */
internal fun lastPlayedLabel(context: Context, whenMs: Long): String? {
    if (whenMs <= 0L) return null
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(whenMs) -> L("Today")
        DateUtils.isToday(whenMs + DateUtils.DAY_IN_MILLIS) -> L("Yesterday")
        now - whenMs < 7 * DateUtils.DAY_IN_MILLIS -> L("%1\$s days ago", (now - whenMs) / DateUtils.DAY_IN_MILLIS)
        else -> DateUtils.formatDateTime(context, whenMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

/**
 * "Continue playing" order: games with a recorded launch, newest first, then games that have
 * playtime but were last played before launches were recorded, by playtime. Never-played games
 * are left out.
 */
internal fun continuePlayingOrder(shortcuts: List<Shortcut>, stats: Map<String, PlayStats>): List<Shortcut> {
    val launched = shortcuts.filter { (stats[it.file.path]?.lastPlayed ?: 0L) > 0L }
        .sortedByDescending { stats[it.file.path]?.lastPlayed ?: 0L }
    val older = shortcuts.filter { (stats[it.file.path]?.lastPlayed ?: 0L) <= 0L && (stats[it.file.path]?.ms ?: 0L) > 0L }
        .sortedByDescending { stats[it.file.path]?.ms ?: 0L }
    return launched + older
}

/** Store shelves in the order the Stores tab lists them, then Android games. Linux runtime entries sit in no store shelf. */
internal val STORE_SHELVES: List<Pair<String, (Shortcut) -> Boolean>> = listOf(
    // Valve's Windows client added from "Add games" (checked first: it is no store game).
    com.winlator.star.store.WindowsSteamClient.BADGE_LABEL to { s: Shortcut -> com.winlator.star.store.WindowsSteamClient.isWindowsSteamShortcut(s) },
    "Steam" to { s: Shortcut -> isSteamOriginShortcut(s) && !LinuxShortcuts.isLinuxEntry(s) },
    "Epic Games" to { s: Shortcut -> s.getExtra("storeSource") == "epic" },
    "GOG" to { s: Shortcut -> isGogShortcut(s) },
    "Amazon Games" to { s: Shortcut -> isAmazonShortcut(s) },
    "Xbox" to { s: Shortcut -> isXboxShortcut(s) },
    "Android" to { s: Shortcut -> AndroidGames.isAndroidEntry(s) },
    T("Added by me") to { s: Shortcut -> isCustomOriginShortcut(s) },
)

internal fun storeLabelOf(s: Shortcut): String = when {
    LinuxShortcuts.isLinuxEntry(s) -> "Linux"
    // Store names stay as they are; "Added by me" is shown in Lab's language.
    else -> L(STORE_SHELVES.firstOrNull { it.second(s) }?.first ?: "Added by me")
}

/**
 * Per-game settings the game's settings dialog writes to the shortcut's Extra Data when a value differs from the
 * container's. Used only to say "N settings set just for this game" on the game page.
 */
private val OVERRIDE_KEYS = listOf(
    "graphicsDriver", "dxwrapper", "displayBackend", "renderer", "screenSize", "audioDriver", "emulator",
    "box64Version", "box64Preset", "fexcoreVersion", "fexcorePreset", "wincomponents", "envVars",
    "controlsProfile", "frameGenEngine", "fpsLimiterEnabled", "execArgs", "cpuList", "midiSoundFont",
    "lc_all", "startupSelection", "reshadeMode", "presentMode", "renderScale", "inputType",
)

internal fun overrideCount(s: Shortcut): Int = OVERRIDE_KEYS.count { s.getExtra(it, "").isNotBlank() }

/**
 * One line of the effective setup: container · graphics driver · DX wrapper · display backend.
 * An Android game has none of that; it gets "Android app" and its package.
 */
internal fun setupSummary(s: Shortcut): String {
    AndroidGames.packageOf(s)?.let { return "Android app · $it" }
    // The Linux Steam client has no Wine driver, DX wrapper or display backend of its own: gamescope on Wayland.
    if (LinuxShortcuts.isLinuxEntry(s)) {
        return listOfNotNull(
            "Linux runtime",
            s.getExtra("screenSize", "").takeIf { it.isNotBlank() },
            "gamescope on Wayland",
        ).joinToString(" · ")
    }
    val c = s.container
    val driver = s.getExtra("graphicsDriver", c.graphicsDriver)
    val dx = s.getExtra("dxwrapper", c.getDXWrapper())
    val backend = s.getExtra("displayBackend", c.displayBackend)
    return listOfNotNull(
        c.name.takeIf { it.isNotBlank() },
        driver.takeIf { it.isNotBlank() },
        dx.takeIf { it.isNotBlank() }?.uppercase(),
        if (backend == Container.DISPLAY_BACKEND_WAYLAND) "Wayland" else "X11",
    ).joinToString(" · ")
}

/** How a Steam game starts when its launch choice is remembered, for the "Starts with …" line. */
internal fun rememberedLaunchLabel(s: Shortcut): String? {
    if (s.getExtra("launchModeRemembered", "") != "1") return null
    return when (s.getExtra("launchMode", "")) {
        "RealSteam" -> "SteamLite (real Steam)"
        "Goldberg" -> "Goldberg (offline)"
        "Raw" -> "the game's .exe directly"
        "Stream" -> "Xbox Cloud Gaming"
        else -> null
    }
}
