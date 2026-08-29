package com.winlator.star.ui.screens

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.winlator.star.container.Shortcut
import com.winlator.star.store.GoldbergMode
import com.winlator.star.store.SteamGameDetailActivity
import com.winlator.star.store.SteamPrefs
import java.io.File

// ── Where a shortcut comes from (drives which launch methods the popup offers) ────────────────────
private enum class GameSource { STEAM, EPIC, GOG, CUSTOM }

// The launch methods. STEAMLITE → "RealSteam", GOLDBERG → "Goldberg", RAW → "Raw" (the launchMode
// contract literals the launch pipeline + callers already understand).
private enum class LaunchMethod(val mode: String) { STEAMLITE("RealSteam"), GOLDBERG("Goldberg"), RAW("Raw") }

/**
 * Classify a shortcut's store source. Mirrors ShortcutsScreen's per-source gates so this popup offers
 * exactly the methods a source supports: Steam ([isSteamOriginShortcut]) gets all three, everything else
 * is Raw-only. GOG shortcuts are written UNTAGGED, so the load-bearing GOG signal is the `gog_games`
 * exec path (the `storeSource==gog` branch is forward-compat) — same rule as [isGogShortcut].
 */
private fun classifySource(shortcut: Shortcut): GameSource = when {
    isSteamOriginShortcut(shortcut) -> GameSource.STEAM
    shortcut.getExtra("storeSource") == "epic" -> GameSource.EPIC
    shortcut.getExtra("storeSource") == "gog" ||
        (shortcut.path?.contains("gog_games", ignoreCase = true) == true) -> GameSource.GOG
    else -> GameSource.CUSTOM
}

// Plain-terms help copy for the "?" bubbles (kept short: what it is + when to use it).
private const val HELP_LAUNCH_WITH =
    "How the game talks to Steam. SteamLite = real Steam (online, VAC, real achievements). Goldberg = " +
        "fake offline Steam for single-player. Raw = just run the .exe."
private const val HELP_STEAMLITE =
    "SteamLite — the REAL Steam client, signed into your account. Online on VAC servers, real " +
        "achievements & cloud saves. Needs internet + a game you own on Steam."
private const val HELP_GOLDBERG =
    "Goldberg — a stand-in, offline Steam. No login, lightweight; great for single-player. Achievements " +
        "emulated on-device. No online multiplayer or VAC. Steam-library games only."
private const val HELP_RAW =
    "Raw — launch the game's .exe directly, with no Steam layer. For DRM-free games or when you just " +
        "want the game to start."
private const val HELP_PASS =
    "For classic games (Half-Life 2, CS:S) that ignore a controller in Real-Steam mode. Hands the pad " +
        "straight to the game instead of Steam Input. SteamLite only."
private const val HELP_REMEMBER =
    "Saves this launch method for this game and skips the popup next time. You can change it later."
private const val HELP_DETAILS =
    "Opens the full Steam game page — achievements grid, DLC and cloud saves."
private const val HELP_GOLDBERG_MODE =
    "Regular suits most games. Experimental turns on newer features for games Regular can't run. " +
        "ColdClient runs the game's own launcher (heaviest). Try Regular first."

/**
 * The launch-method chooser popup — a COMPACT centered dialog that pops before a game launches. It is
 * both source-adaptive AND orientation-adaptive:
 *
 *  • **Source** — Steam gets all three methods (SteamLite / Goldberg / Raw), the Goldberg-mode selector,
 *    the SteamLite Controller-passthrough toggle, and a "Full details & achievements" link into
 *    [SteamGameDetailActivity]. Epic / GOG / Custom gray those out and offer only Raw.
 *  • **Orientation** — PORTRAIT is a compact vertical box (steamlite-launch-mockup.html); LANDSCAPE is a
 *    wide "cover-art hero" card with the art on the left and all controls on the right
 *    (steamlite-launch-landscape-mockup.html); the Goldberg selector switches from a vertical dropdown to
 *    a horizontal segmented outlined menu to fit the shorter height.
 *
 * This composable only REPORTS the choice back via [onLaunch]; the caller persists the shortcut extras
 * (`launchMode` / `launchModeRemembered` / `controllerPassthrough`), stages the picked component, and
 * launches. State is keyed on [shortcut] so reopening for a different game re-seeds from its saved choice.
 *
 * [onVerifyFiles] / [onUpdateFiles] are retained for caller compatibility (RealSteam maintenance now lives
 * on the full details page); the compact popup no longer surfaces the Verify/Update block.
 */
@Composable
fun LaunchMethodSheet(
    shortcut: Shortcut,
    onDismiss: () -> Unit,
    onLaunch: (mode: String, goldbergMode: GoldbergMode?, remember: Boolean, controllerPassthrough: Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onVerifyFiles: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onUpdateFiles: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    val source = remember(shortcut) { classifySource(shortcut) }
    val appId = remember(shortcut) { shortcut.getExtra("steamAppId", "").toIntOrNull() ?: 0 }
    val isSteam = source == GameSource.STEAM
    val hasDetails = isSteam && appId > 0

    val enabledMethods = remember(shortcut) {
        if (isSteam) listOf(LaunchMethod.STEAMLITE, LaunchMethod.GOLDBERG, LaunchMethod.RAW)
        else listOf(LaunchMethod.RAW)
    }

    SteamPrefs.init(context)
    var method by remember(shortcut) {
        mutableStateOf(
            when (shortcut.getExtra("launchMode", "")) {
                "Goldberg" -> LaunchMethod.GOLDBERG
                "Raw" -> LaunchMethod.RAW
                "RealSteam" -> LaunchMethod.STEAMLITE
                else -> if (isSteam) LaunchMethod.STEAMLITE else LaunchMethod.RAW
            }.let { if (it in enabledMethods) it else LaunchMethod.RAW },
        )
    }
    var goldbergMode by remember(shortcut) {
        mutableStateOf(SteamPrefs.getGoldbergMode(appId).let { if (it == GoldbergMode.OFF) GoldbergMode.REGULAR else it })
    }
    var rememberChoice by remember(shortcut) { mutableStateOf(shortcut.getExtra("launchModeRemembered", "") == "1") }
    var controllerPassthrough by remember(shortcut) { mutableStateOf(shortcut.getExtra("controllerPassthrough", "") == "1") }
    // The active "?" help bubble (null = none). Keyed on the shortcut so it resets per game.
    var helpText by remember(shortcut) { mutableStateOf<String?>(null) }
    val toggleHelp: (String) -> Unit = { helpText = if (helpText == it) null else it }

    val doLaunch: () -> Unit = {
        onLaunch(
            method.mode,
            if (method == LaunchMethod.GOLDBERG) goldbergMode else null,
            rememberChoice,
            if (method == LaunchMethod.STEAMLITE) controllerPassthrough else false,
        )
    }
    val openDetails: () -> Unit = {
        context.startActivity(
            Intent(context, SteamGameDetailActivity::class.java)
                .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, appId),
        )
        onDismiss()
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (landscape) {
            LandscapeCard(
                shortcut, source, appId, isSteam, hasDetails, enabledMethods, accent,
                method, { method = it }, goldbergMode, { goldbergMode = it },
                rememberChoice, { rememberChoice = it }, controllerPassthrough, { controllerPassthrough = it },
                helpText, toggleHelp, { helpText = null }, onDismiss, doLaunch, openDetails,
            )
        } else {
            PortraitCard(
                shortcut, source, appId, isSteam, hasDetails, enabledMethods, accent,
                method, { method = it }, goldbergMode, { goldbergMode = it },
                rememberChoice, { rememberChoice = it }, controllerPassthrough, { controllerPassthrough = it },
                helpText, toggleHelp, { helpText = null }, onDismiss, doLaunch, openDetails,
            )
        }
    }
}

// ── Portrait: compact vertical box ────────────────────────────────────────────────────────────────

@Composable
private fun PortraitCard(
    shortcut: Shortcut,
    source: GameSource,
    appId: Int,
    isSteam: Boolean,
    hasDetails: Boolean,
    enabledMethods: List<LaunchMethod>,
    accent: Color,
    method: LaunchMethod,
    onMethod: (LaunchMethod) -> Unit,
    goldbergMode: GoldbergMode,
    onGoldbergMode: (GoldbergMode) -> Unit,
    rememberChoice: Boolean,
    onRemember: (Boolean) -> Unit,
    passthrough: Boolean,
    onPassthrough: (Boolean) -> Unit,
    helpText: String?,
    toggleHelp: (String) -> Unit,
    dismissHelp: () -> Unit,
    onDismiss: () -> Unit,
    doLaunch: () -> Unit,
    openDetails: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.padding(horizontal = 20.dp).width(340.dp),
        shape = RoundedCornerShape(18.dp),
        color = cs.surface,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline),
        shadowElevation = 24.dp,
    ) {
        Box {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                // Header: cover + name + source subline + close ✕.
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GameCover(shortcut, accent, 42.dp, 56.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            shortcut.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            sourceSubline(source, appId),
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CloseButton(onDismiss)
                }

                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    MicroLabel("Launch with")
                    Spacer(Modifier.height(7.dp))
                    ChipsRow(method, enabledMethods, accent, compact = false, onMethod, toggleHelp)
                    Spacer(Modifier.height(9.dp))
                    MethodDesc(method, source)

                    AnimatedVisibility(visible = method == LaunchMethod.GOLDBERG) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            GoldbergDropdownPortrait(goldbergMode, accent, onGoldbergMode, toggleHelp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = cs.outline)
                    Spacer(Modifier.height(2.dp))
                    OptionsBlock(
                        shortcut, isSteam, hasDetails, passthrough, onPassthrough,
                        rememberChoice, onRemember, accent, toggleHelp, openDetails, compact = false,
                    )
                }

                HorizontalDivider(color = cs.outline)
                FooterRow(accent, onDismiss, doLaunch, topPad = 10.dp, bottomPad = 12.dp, startPad = 8.dp, endPad = 14.dp)
            }
            if (helpText != null) HelpTip(helpText, dismissHelp)
        }
    }
}

// ── Landscape: wide cover-art hero (art left, controls right) ─────────────────────────────────────

@Composable
private fun LandscapeCard(
    shortcut: Shortcut,
    source: GameSource,
    appId: Int,
    isSteam: Boolean,
    hasDetails: Boolean,
    enabledMethods: List<LaunchMethod>,
    accent: Color,
    method: LaunchMethod,
    onMethod: (LaunchMethod) -> Unit,
    goldbergMode: GoldbergMode,
    onGoldbergMode: (GoldbergMode) -> Unit,
    rememberChoice: Boolean,
    onRemember: (Boolean) -> Unit,
    passthrough: Boolean,
    onPassthrough: (Boolean) -> Unit,
    helpText: String?,
    toggleHelp: (String) -> Unit,
    dismissHelp: () -> Unit,
    onDismiss: () -> Unit,
    doLaunch: () -> Unit,
    openDetails: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.padding(16.dp).width(580.dp).height(332.dp),
        shape = RoundedCornerShape(18.dp),
        color = cs.surface,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline),
        shadowElevation = 26.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // Left: cover-art hero with name + source overlaid at the bottom of the art.
                ArtHero(shortcut, source, appId, accent, Modifier.fillMaxHeight().width(200.dp))

                // Right: all controls.
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 13.dp, top = 11.dp, end = 13.dp, bottom = 11.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CloseButton(onDismiss, size = 24.dp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MicroLabel("Launch with")
                        Spacer(Modifier.width(6.dp))
                        HelpDot(accent, highlighted = false, onClick = { toggleHelp(HELP_LAUNCH_WITH) })
                    }
                    Spacer(Modifier.height(6.dp))
                    ChipsRow(method, enabledMethods, accent, compact = true, onMethod, toggleHelp)
                    Spacer(Modifier.height(6.dp))
                    MethodDesc(method, source)

                    AnimatedVisibility(visible = method == LaunchMethod.GOLDBERG) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            GoldbergSegmentedLandscape(goldbergMode, accent, onGoldbergMode, toggleHelp)
                        }
                    }

                    // Options + footer pinned to the bottom of the controls column (margin-top:auto).
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = cs.outline)
                    OptionsBlock(
                        shortcut, isSteam, hasDetails, passthrough, onPassthrough,
                        rememberChoice, onRemember, accent, toggleHelp, openDetails, compact = true,
                    )
                    HorizontalDivider(color = cs.outline)
                    FooterRow(accent, onDismiss, doLaunch, topPad = 8.dp, bottomPad = 0.dp, startPad = 0.dp, endPad = 0.dp)
                }
            }
            if (helpText != null) HelpTip(helpText, dismissHelp)
        }
    }
}

// ── Shared content pieces ─────────────────────────────────────────────────────────────────────────

/** The 3-chip "Launch with" segmented selector. */
@Composable
private fun ChipsRow(
    method: LaunchMethod,
    enabledMethods: List<LaunchMethod>,
    accent: Color,
    compact: Boolean,
    onMethod: (LaunchMethod) -> Unit,
    toggleHelp: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)) {
        SegChip("🌐", "SteamLite", method == LaunchMethod.STEAMLITE, LaunchMethod.STEAMLITE in enabledMethods,
            accent, compact, { onMethod(LaunchMethod.STEAMLITE) }, { toggleHelp(HELP_STEAMLITE) })
        SegChip("🛡️", "Goldberg", method == LaunchMethod.GOLDBERG, LaunchMethod.GOLDBERG in enabledMethods,
            accent, compact, { onMethod(LaunchMethod.GOLDBERG) }, { toggleHelp(HELP_GOLDBERG) })
        SegChip("▶️", "Raw .exe", method == LaunchMethod.RAW, LaunchMethod.RAW in enabledMethods,
            accent, compact, { onMethod(LaunchMethod.RAW) }, { toggleHelp(HELP_RAW) })
    }
}

/** One chip: emoji + label + a corner "?" help bubble. Selected = accent tint; disabled = dimmed. */
@Composable
private fun RowScope.SegChip(
    emoji: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    compact: Boolean,
    onClick: () -> Unit,
    onHelp: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Box(Modifier.weight(1f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (compact) 9.dp else 11.dp))
                .background(if (selected) accent.copy(alpha = 0.14f) else cs.surfaceContainerHigh)
                .border(1.dp, if (selected) accent else cs.outline, RoundedCornerShape(if (compact) 9.dp else 11.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = if (compact) 6.dp else 8.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = if (compact) 13.sp else 16.sp, modifier = Modifier.alpha(alpha))
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
            Text(
                label,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = when {
                    !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f)
                    selected -> accent
                    else -> cs.onSurface
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Corner "?" — tappable even when the chip itself is disabled (explains why it's unavailable).
        HelpDot(
            accent = accent,
            highlighted = selected,
            onClick = onHelp,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
        )
    }
}

/** The one-line description that updates per selected method. */
@Composable
private fun MethodDesc(method: LaunchMethod, source: GameSource) {
    Text(
        methodDescription(method, source),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 15.sp,
    )
}

/** The option rows: Full details (Steam), Controller passthrough (Steam), Remember. */
@Composable
private fun ColumnScope.OptionsBlock(
    shortcut: Shortcut,
    isSteam: Boolean,
    hasDetails: Boolean,
    passthrough: Boolean,
    onPassthrough: (Boolean) -> Unit,
    rememberChoice: Boolean,
    onRemember: (Boolean) -> Unit,
    accent: Color,
    toggleHelp: (String) -> Unit,
    openDetails: () -> Unit,
    compact: Boolean,
) {
    if (hasDetails) {
        OptionRow(
            title = "Full details & achievements",
            badge = null,
            subtitle = if (compact) null else "Details, achievements, DLC and cloud saves.",
            accent = accent,
            compact = compact,
            onHelp = { toggleHelp(HELP_DETAILS) },
            onRowClick = openDetails,
            trailing = {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Open details", tint = accent, modifier = Modifier.size(20.dp))
            },
        )
    }
    if (isSteam) {
        OptionRow(
            title = "Controller passthrough",
            badge = "NEW",
            subtitle = if (compact) null else "Send the pad straight to the game. For classic games (HL2, CS:S).",
            accent = accent,
            compact = compact,
            onHelp = { toggleHelp(HELP_PASS) },
            trailing = { PillSwitch(passthrough, accent, onPassthrough) },
        )
    }
    OptionRow(
        title = "Remember my choice",
        badge = null,
        subtitle = if (compact) null else "Skip this popup next time for ${shortcut.name}.",
        accent = accent,
        compact = compact,
        onHelp = { toggleHelp(HELP_REMEMBER) },
        trailing = { PillSwitch(rememberChoice, accent, onRemember) },
    )
}

/** A title (+ optional badge / subtitle) with a "?" help bubble and a trailing control. The whole row is
 *  tappable when [onRowClick] is set (the "Full details" link). */
@Composable
private fun OptionRow(
    title: String,
    badge: String?,
    subtitle: String?,
    accent: Color,
    compact: Boolean,
    onHelp: () -> Unit,
    onRowClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onRowClick != null) Modifier.clickable(onClick = onRowClick) else Modifier)
            .padding(vertical = if (compact) 6.dp else 9.dp, horizontal = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, accent, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, lineHeight = 14.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        HelpDot(accent, highlighted = false, onClick = onHelp)
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** Cancel (text) · spacer · small accent "Launch ▶". */
@Composable
private fun FooterRow(
    accent: Color,
    onDismiss: () -> Unit,
    doLaunch: () -> Unit,
    topPad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    startPad: androidx.compose.ui.unit.Dp,
    endPad: androidx.compose.ui.unit.Dp,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = topPad, bottom = bottomPad, start = startPad, end = endPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text("Cancel", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = doLaunch,
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 9.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = cs.onPrimary),
        ) {
            Text("Launch", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Header / art ──────────────────────────────────────────────────────────────────────────────────

/** The compact poster cover (portrait header): the shortcut's own cover-art file / bitmap via Coil, else
 *  an initials placeholder. Reuses the same cover pipeline the grid tiles use, so it is source-agnostic. */
@Composable
private fun GameCover(shortcut: Shortcut, accent: Color, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    val model = rememberCoverModel(shortcut)
    Box(
        Modifier.size(width = w, height = h).clip(RoundedCornerShape(7.dp)).border(1.dp, cs.outline, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), cs.surfaceVariant))),
                contentAlignment = Alignment.Center,
            ) {
                Text(shortcut.name.take(2).uppercase(), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The landscape left pane: cover art filling the pane, a bottom fade, and the game name + source
 *  overlaid at the bottom-left of the art. */
@Composable
private fun ArtHero(shortcut: Shortcut, source: GameSource, appId: Int, accent: Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val model = rememberCoverModel(shortcut)
    Box(modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), cs.surfaceVariant)))) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(shortcut.name.take(1).uppercase(), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.14f))
            }
        }
        // Bottom fade so the caption stays legible over any art.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
            Text(
                shortcut.name,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(sourceSubline(source, appId), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** The shortcut's cover model for Coil: its custom cover-art file (the extra Steam/Epic/GOG imports write),
 *  else its decoded cover/icon bitmap, else null (→ initials placeholder). Source-agnostic. */
@Composable
private fun rememberCoverModel(shortcut: Shortcut): Any? = remember(shortcut) {
    val path = shortcut.customCoverArtPath
    when {
        !path.isNullOrEmpty() && File(path).exists() -> File(path)
        shortcut.coverArt != null -> shortcut.coverArt
        shortcut.icon != null -> shortcut.icon
        else -> null
    }
}

// ── Small shared widgets ──────────────────────────────────────────────────────────────────────────

@Composable
private fun CloseButton(onDismiss: () -> Unit, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).border(1.dp, cs.outline, RoundedCornerShape(8.dp)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
    }
}

/** The small circular "?" that opens a help bubble. */
@Composable
private fun HelpDot(accent: Color, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(16.dp).clip(CircleShape)
            .background(if (highlighted) accent.copy(alpha = 0.16f) else cs.surfaceVariant)
            .border(1.dp, if (highlighted) accent.copy(alpha = 0.55f) else cs.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = if (highlighted) accent else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

/** M3 Switch styled as the mockup's accent pill toggle. */
@Composable
private fun PillSwitch(checked: Boolean, accent: Color, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent, checkedBorderColor = accent),
    )
}

@Composable
private fun MicroLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.0.sp,
    )
}

/** The floating dark help tooltip (the mockup's `.tip`), pinned above the footer. Tap to dismiss. */
@Composable
private fun BoxScope.HelpTip(text: String?, onDismiss: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .padding(bottom = 48.dp)
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111114))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text.orEmpty(), style = MaterialTheme.typography.labelMedium, color = Color(0xFFECECF0), lineHeight = 15.sp)
    }
}

// ── Source-adaptive copy ──────────────────────────────────────────────────────────────────────────

private fun sourceSubline(source: GameSource, appId: Int): String = when (source) {
    GameSource.STEAM -> if (appId > 0) "Steam · App $appId" else "Steam"
    GameSource.EPIC -> "Epic · Raw launch"
    GameSource.GOG -> "GOG · DRM-free · Raw"
    GameSource.CUSTOM -> "Custom · Raw"
}

private fun methodDescription(method: LaunchMethod, source: GameSource): String = when (method) {
    LaunchMethod.STEAMLITE -> "Real Steam — VAC servers, real achievements & cloud saves."
    LaunchMethod.GOLDBERG -> "Offline emulator — no login, lightweight, single-player."
    LaunchMethod.RAW -> when (source) {
        GameSource.EPIC -> "Run the game's .exe directly."
        GameSource.GOG -> "Run the DRM-free .exe directly — no launcher."
        GameSource.CUSTOM -> "Run the .exe directly."
        GameSource.STEAM -> "Run the game's .exe directly — no Steam layer."
    }
}

// ── Goldberg sub-mode selectors (shared outlined-menu style: MenuStyle.kt) ────────────────────────

private data class GbOption(val mode: GoldbergMode, val name: String, val sub: String)

private val GOLDBERG_OPTIONS = listOf(
    GbOption(GoldbergMode.REGULAR, "Regular", "Standard emulation — best compatibility"),
    GbOption(GoldbergMode.EXPERIMENTAL, "Experimental", "Newer features — for games Regular can't run"),
    GbOption(GoldbergMode.COLDCLIENT, "ColdClient", "Runs the game's own launcher — heaviest option"),
)

/** Portrait: label + "?" and a vertical dropdown using the shared outlined-menu card + gray dividers. */
@Composable
private fun GoldbergDropdownPortrait(mode: GoldbergMode, accent: Color, onSelected: (GoldbergMode) -> Unit, toggleHelp: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val current = GOLDBERG_OPTIONS.firstOrNull { it.mode == mode } ?: GOLDBERG_OPTIONS.first()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("Goldberg mode")
            Spacer(Modifier.width(7.dp))
            HelpDot(accent, highlighted = false, onClick = { toggleHelp(HELP_GOLDBERG_MODE) })
        }
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh)
                    .border(1.dp, cs.outline, RoundedCornerShape(10.dp)).clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(current.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(current.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.outlinedMenuCard()) {
                GOLDBERG_OPTIONS.forEachIndexed { i, opt ->
                    if (i > 0) MenuItemDivider()
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(opt.name, style = MaterialTheme.typography.bodyMedium, color = if (opt.mode == mode) accent else cs.onSurface, fontWeight = FontWeight.SemiBold)
                                Text(opt.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        },
                        trailingIcon = { if (opt.mode == mode) Icon(Icons.Filled.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)) },
                        onClick = { onSelected(opt.mode); open = false },
                    )
                }
            }
        }
    }
}

/** Landscape: label + "?" and a horizontal segmented outlined menu (Regular | Experimental | ColdClient)
 *  with thin gray dividers between items — the shorter-height counterpart to the dropdown. */
@Composable
private fun GoldbergSegmentedLandscape(mode: GoldbergMode, accent: Color, onSelected: (GoldbergMode) -> Unit, toggleHelp: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("Goldberg mode")
            Spacer(Modifier.width(6.dp))
            HelpDot(accent, highlighted = false, onClick = { toggleHelp(HELP_GOLDBERG_MODE) })
        }
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(9.dp))
                .background(cs.surfaceContainerHigh).border(1.dp, cs.outline, RoundedCornerShape(9.dp)),
        ) {
            GOLDBERG_OPTIONS.forEachIndexed { i, opt ->
                if (i > 0) VerticalDivider(color = cs.outline.copy(alpha = 0.5f))
                val sel = opt.mode == mode
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (sel) accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { onSelected(opt.mode) }
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sel) accent else cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
