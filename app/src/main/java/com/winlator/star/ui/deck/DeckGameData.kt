package com.winlator.star.ui.deck

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

internal fun lastPlayedOf(s: Shortcut): Long = s.getExtra(EXTRA_LAST_PLAYED, "").toLongOrNull() ?: 0L

internal fun addedAtOf(s: Shortcut): Long = ShortcutOrder.parse(s.getExtra(ShortcutOrder.EXTRA_ADDED_AT, ""))

/** The recent-order key of one shortcut, for callers that don't hold its [PlayStats]. Sort descending. */
internal fun recentKeyOf(s: Shortcut): Long = ShortcutOrder.recentKey(lastPlayedOf(s), addedAtOf(s))

internal fun playtimeLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "Not played yet"
        minutes < 60L -> "${minutes.coerceAtLeast(1L)} min played"
        else -> "%.1f h played".format(ms / 3_600_000.0)
    }
}

/** Short playtime for a stat tile: "41.5 h", "25 min", or a dash. */
internal fun playtimeShort(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "—"
        minutes < 60L -> "${minutes.coerceAtLeast(1L)} min"
        else -> "%.1f h".format(ms / 3_600_000.0)
    }
}

/** "Today", "Yesterday", "3 days ago", "Sep 28"; null when the game has no recorded launch. */
internal fun lastPlayedLabel(context: Context, whenMs: Long): String? {
    if (whenMs <= 0L) return null
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(whenMs) -> "Today"
        DateUtils.isToday(whenMs + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        now - whenMs < 7 * DateUtils.DAY_IN_MILLIS -> "${(now - whenMs) / DateUtils.DAY_IN_MILLIS} days ago"
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
    "Steam" to { s: Shortcut -> isSteamOriginShortcut(s) && !LinuxShortcuts.isLinuxEntry(s) },
    "Epic Games" to { s: Shortcut -> s.getExtra("storeSource") == "epic" },
    "GOG" to { s: Shortcut -> isGogShortcut(s) },
    "Amazon Games" to { s: Shortcut -> isAmazonShortcut(s) },
    "Xbox" to { s: Shortcut -> isXboxShortcut(s) },
    "Android" to { s: Shortcut -> AndroidGames.isAndroidEntry(s) },
    "Added by me" to { s: Shortcut -> isCustomOriginShortcut(s) },
)

internal fun storeLabelOf(s: Shortcut): String = when {
    LinuxShortcuts.isLinuxEntry(s) -> "Linux"
    else -> STORE_SHELVES.firstOrNull { it.second(s) }?.first ?: "Added by me"
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
