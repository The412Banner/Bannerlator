package com.winlator.star.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.container.Shortcut
import com.winlator.star.store.GoldbergComponent
import com.winlator.star.store.GoldbergMode
import com.winlator.star.store.SteamLiteComponent
import com.winlator.star.store.SteamPrefs

// Muted "steel" accent for the offline Goldberg path — the calm counterpart to the app's live-online
// primary accent used for the recommended SteamLite path. Fixed (not themed) so the two methods always
// read as "online = accent, offline = steel" regardless of the active theme preset.
private val SteelBlue = Color(0xFF5C7A94)
// Real-Steam "online" green — matches the Games-tab Steam connection pill.
private val OnlineGreen = Color(0xFF3FB950)

/**
 * The launch-method chooser for a Steam-origin game — a mini game-details bottom sheet that pops
 * before a Steam game launches (see the mockup Bannerlator-Launch-Options-Mockup.html). The user
 * picks how the game connects to Steam:
 *
 *  • **SteamLite** (recommended, default) — our own lightweight real Steam client + agent, downloaded
 *    on demand ([SteamLiteComponent]); logs into the real account and reaches VAC-secured servers.
 *  • **Goldberg** — the offline emulator ([GoldbergComponent]); no login, no online multiplayer,
 *    with a Regular / Experimental / ColdClient sub-mode.
 *
 * This composable only REPORTS the choice back via [onLaunch]; the caller persists the `launchMode`
 * (+ `launchModeRemembered`) shortcut extras, stages the picked component, and launches. State is keyed
 * on [shortcut] so reopening for a different game re-seeds from that game's saved choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchMethodSheet(
    shortcut: Shortcut,
    onDismiss: () -> Unit,
    onLaunch: (mode: String, goldbergMode: GoldbergMode?, remember: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val appId = remember(shortcut) { shortcut.getExtra("steamAppId", "").toIntOrNull() ?: 0 }

    // Cheap file-stat presence checks (drive the status rows + button label). Keyed on the shortcut so
    // reopening after a download re-reads "Installed".
    val steamLiteInstalled = remember(shortcut) { SteamLiteComponent.isInstalled(context) }
    val goldbergInstalled = remember(shortcut) { GoldbergComponent.isInstalled(context) }

    // Seed from any earlier choice on this shortcut; default to the recommended SteamLite path.
    val initialMode = remember(shortcut) { shortcut.getExtra("launchMode", "") }
    var steamLiteSelected by remember(shortcut) { mutableStateOf(initialMode != "Goldberg") }
    SteamPrefs.init(context)
    var goldbergMode by remember(shortcut) {
        mutableStateOf(SteamPrefs.getGoldbergMode(appId).let { if (it == GoldbergMode.OFF) GoldbergMode.REGULAR else it })
    }
    var rememberChoice by remember(shortcut) { mutableStateOf(shortcut.getExtra("launchModeRemembered", "") == "1") }
    var liteHelpOpen by remember(shortcut) { mutableStateOf(false) }
    var goldHelpOpen by remember(shortcut) { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
        contentColor = cs.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp),
        ) {
            // ── Mini game-details header: cover + name + Steam/AppID tags ──────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameCover(shortcut, cs.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        shortcut.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagPill("STEAM", cs.primary, filled = true)
                        if (appId > 0) TagPill("AppID $appId", cs.onSurfaceVariant, filled = false)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Divider(color = cs.outline)
            Spacer(Modifier.height(14.dp))

            SectionLabel("Choose launch method")
            Spacer(Modifier.height(11.dp))

            // ── SteamLite (recommended / online) ─────────────────────────────────────────────────
            MethodCard(
                selected = steamLiteSelected,
                accent = cs.primary,
                leadingIcon = Icons.Filled.Bolt,
                title = "SteamLite",
                pillText = "REAL STEAM · ONLINE",
                pillColor = OnlineGreen,
                recommended = true,
                helpOpen = liteHelpOpen,
                onHelpToggle = { liteHelpOpen = !liteHelpOpen },
                helpPanel = { SteamLiteHelp() },
                desc = "Runs the real Steam client and logs into your account — play online on " +
                    "VAC-secured servers, just like on PC.",
                onClick = { steamLiteSelected = true },
            ) {
                StatusRow(
                    accent = if (steamLiteInstalled) OnlineGreen else cs.primary,
                    icon = if (steamLiteInstalled) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload,
                    text = "SteamLite package · agent + Steam client · 18 MB",
                    state = if (steamLiteInstalled) "Installed" else "Downloads on launch",
                )
            }

            Spacer(Modifier.height(11.dp))

            // ── Goldberg (offline emulator) ──────────────────────────────────────────────────────
            MethodCard(
                selected = !steamLiteSelected,
                accent = SteelBlue,
                leadingIcon = Icons.Filled.Public,
                title = "Goldberg",
                pillText = "OFFLINE EMULATOR",
                pillColor = SteelBlue,
                recommended = false,
                helpOpen = goldHelpOpen,
                onHelpToggle = { goldHelpOpen = !goldHelpOpen },
                helpPanel = { GoldbergHelp() },
                desc = "Emulated Steam — no login, works fully offline. No online multiplayer or VAC.",
                onClick = { steamLiteSelected = false },
            ) {
                GoldbergModeDropdown(
                    mode = goldbergMode,
                    enabled = !steamLiteSelected,
                    accent = SteelBlue,
                    onSelected = { goldbergMode = it; steamLiteSelected = false },
                )
                Spacer(Modifier.height(10.dp))
                StatusRow(
                    accent = if (goldbergInstalled) OnlineGreen else SteelBlue,
                    icon = if (goldbergInstalled) Icons.Filled.CheckCircle else Icons.Filled.CloudDownload,
                    text = "Goldberg emulator · 34 MB",
                    state = if (goldbergInstalled) "Installed" else "Downloads on launch",
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Remember for this game ───────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rememberChoice,
                    onCheckedChange = { rememberChoice = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = cs.primary,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Remember for \"${shortcut.name}\" — skip this next time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── Primary launch button (label + icon reflect the pick + whether a download is due) ─
            val needsDownload =
                (steamLiteSelected && !steamLiteInstalled) || (!steamLiteSelected && !goldbergInstalled)
            val buttonColor = if (steamLiteSelected) cs.primary else SteelBlue
            val label = when {
                steamLiteSelected && !steamLiteInstalled -> "Download & Launch"
                steamLiteSelected -> "Launch"
                !goldbergInstalled -> "Download & Launch with Goldberg"
                else -> "Launch with Goldberg"
            }
            Button(
                onClick = {
                    onLaunch(
                        if (steamLiteSelected) "RealSteam" else "Goldberg",
                        if (steamLiteSelected) null else goldbergMode,
                        rememberChoice,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = Color.White),
            ) {
                Icon(
                    if (needsDownload) Icons.Filled.Download else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Header pieces ───────────────────────────────────────────────────────────────────────────────

/** The game's cover: existing shortcut art if present, otherwise a styled gradient placeholder with
 *  the game's initials — same local-art idiom as the grid tiles. */
@Composable
private fun GameCover(shortcut: Shortcut, accent: Color) {
    val cs = MaterialTheme.colorScheme
    val bmp = remember(shortcut) { shortcut.coverArt ?: shortcut.icon }
    Box(
        Modifier
            .size(width = 50.dp, height = 68.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(68.dp),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.55f), cs.surfaceVariant),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shortcut.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TagPill(text: String, color: Color, filled: Boolean) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (filled) cs.onPrimary else color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .then(
                if (filled) Modifier.background(color)
                else Modifier.border(1.dp, cs.outline, RoundedCornerShape(5.dp)),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
    )
}

// ── Method card ─────────────────────────────────────────────────────────────────────────────────

/** One selectable launch-method card. The whole card is tappable to select; the inline "?" toggles a
 *  plain-English help panel without changing the selection. [content] is the method-specific footer
 *  (status row, and for Goldberg the sub-mode dropdown). */
@Composable
private fun MethodCard(
    selected: Boolean,
    accent: Color,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    pillText: String,
    pillColor: Color,
    recommended: Boolean,
    helpOpen: Boolean,
    onHelpToggle: () -> Unit,
    helpPanel: @Composable () -> Unit,
    desc: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.10f) else cs.surfaceContainer,
        ),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else cs.outline),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            // Leading icon tile
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .then(
                        if (selected) Modifier.background(accent.copy(alpha = 0.16f))
                        else Modifier.border(1.dp, cs.outline, RoundedCornerShape(11.dp)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = if (selected) accent else cs.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Title line: name + "?" + pill + (recommended)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    HelpDot(selected = selected, accent = accent, onClick = onHelpToggle)
                    if (recommended) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "RECOMMENDED",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Pill(pillText, pillColor)

                // Inline help panel (accent-edged), toggled by the "?".
                AnimatedVisibility(visible = helpOpen) {
                    Column {
                        Spacer(Modifier.height(9.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surface)
                                .border(1.dp, cs.outline, RoundedCornerShape(10.dp))
                                .padding(11.dp),
                        ) { helpPanel() }
                    }
                }

                Spacer(Modifier.height(9.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                content()
            }
            Spacer(Modifier.width(8.dp))
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = accent, unselectedColor = cs.outline),
            )
        }
    }
}

/** The small circular "?" that toggles a card's help panel. */
@Composable
private fun HelpDot(selected: Boolean, accent: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(19.dp)
            .clip(CircleShape)
            .background(if (selected) accent.copy(alpha = 0.16f) else cs.surfaceVariant)
            .border(1.dp, if (selected) accent.copy(alpha = 0.55f) else cs.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "?",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else cs.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
    }
}

/** Small "component · size · state" footer row (needs-download amber-ish accent vs installed green). */
@Composable
private fun StatusRow(
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    state: String,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            state,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Goldberg sub-mode dropdown ──────────────────────────────────────────────────────────────────

private data class GbOption(val mode: GoldbergMode, val name: String, val sub: String)

private val GOLDBERG_OPTIONS = listOf(
    GbOption(GoldbergMode.REGULAR, "Regular", "Standard emulation — best compatibility"),
    GbOption(GoldbergMode.EXPERIMENTAL, "Experimental", "Newer features — for games Regular can't run"),
    GbOption(GoldbergMode.COLDCLIENT, "ColdClient", "Runs the game's own launcher — heaviest option"),
)

@Composable
private fun GoldbergModeDropdown(
    mode: GoldbergMode,
    enabled: Boolean,
    accent: Color,
    onSelected: (GoldbergMode) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val current = GOLDBERG_OPTIONS.firstOrNull { it.mode == mode } ?: GOLDBERG_OPTIONS.first()

    Column {
        Text(
            "GOLDBERG MODE",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, cs.outline, RoundedCornerShape(9.dp))
                    .clickable(enabled = enabled) { open = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(current.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.outlinedMenuCard(),
            ) {
                GOLDBERG_OPTIONS.forEachIndexed { i, opt ->
                    if (i > 0) MenuItemDivider()
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    opt.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (opt.mode == mode) accent else cs.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(opt.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        },
                        trailingIcon = {
                            if (opt.mode == mode) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = { onSelected(opt.mode); open = false },
                    )
                }
            }
        }
    }
}

// ── Help copy (plain English, from the mockup) ──────────────────────────────────────────────────

@Composable
private fun SteamLiteHelp() {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(
            "SteamLite is our own lightweight Steam client + launcher, in one download. It signs into " +
                "your real Steam account and runs the game the same way Steam does on a PC — so it " +
                "connects to Valve's servers.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HelpFact("✓", OnlineGreen, "Online multiplayer on VAC-secured servers, friends, achievements, Steam Cloud")
        HelpFact("✓", OnlineGreen, "One self-contained package — our agent + Steam client bundled together (~18 MB)")
        HelpFact("✗", MaterialTheme.colorScheme.error, "Needs internet + your Steam login. VAC-only titles — not kernel anti-cheat games")
    }
}

@Composable
private fun GoldbergHelp() {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(
            "Goldberg fakes a Steam client locally. Games that check for Steam will run without any " +
                "login — completely offline. Nothing talks to Valve's servers.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HelpFact("✓", OnlineGreen, "Works fully offline, no account needed; achievements + saves kept on-device")
        HelpFact("✗", cs.error, "No online multiplayer, no VAC servers, no friends")
        HelpFact("•", SteelBlue, "Regular is standard · Experimental for stubborn games · ColdClient runs the game's own launcher")
    }
}

@Composable
private fun HelpFact(mark: String, markColor: Color, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(mark, style = MaterialTheme.typography.bodySmall, color = markColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
