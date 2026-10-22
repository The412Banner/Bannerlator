package com.winlator.star.store

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.star.R
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.theme.LocalAccentDim

/**
 * The "Change account" chooser: every saved Steam account (avatar, name, login, the active one
 * marked), tap one to switch to it WITHOUT its password, "Add account", and a per-row remove with a
 * confirm. A dialog like the other Steam settings sheets ([SteamSocialSettingsSheet]) — it works with a
 * D-pad as well as touch, and every row is at least 48dp tall.
 *
 * The switch itself is [SteamAccountStore.switchTo]; this composable only starts it, shows progress
 * (the sheet cannot be dismissed mid-switch) and hands the result to the host, which owns the
 * messaging and the navigation (login screen for an expired token).
 */
@Composable
fun SteamAccountsDialog(
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onSwitchResult: (SteamAccountStore.SwitchResult, SteamAccountStore.Account) -> Unit,
    onSignOutActive: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var accounts by remember { mutableStateOf(SteamAccountStore.list(ctx)) }
    val activeSid = remember { SteamAccountStore.activeSteamId(ctx) }
    var switchingTo by remember { mutableStateOf<Long?>(null) }
    var confirmRemove by remember { mutableStateOf<SteamAccountStore.Account?>(null) }
    val busy = switchingTo != null

    // A saved name can still be unknown (never fetched, or an old stand-in): ask the signed-in account's
    // profile once, and re-read the list when it lands (the profile refresh saves the real name first).
    val selfProfile by SteamFriendsStore.selfProfile.collectAsState()
    LaunchedEffect(Unit) {
        if (accounts.any { it.steamId64 == activeSid && !isRealSteamName(it.displayName) }) {
            SteamFriendsStore.refreshSelfProfile(force = true)
        }
    }
    LaunchedEffect(selfProfile) { accounts = SteamAccountStore.list(ctx) }

    OutlinedAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SwitchAccount, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.steam_accounts_title))
            }
        },
        text = {
            // One scrolling column holds the accounts AND the "Add account" row, so on a short (landscape /
            // handheld) screen "Add account" can always be scrolled to instead of being squeezed away.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                accounts.forEach { account ->
                    SteamAccountRow(
                        account = account,
                        isActive = account.steamId64 == activeSid,
                        busy = busy,
                        switching = switchingTo == account.steamId64,
                        onSwitch = {
                            switchingTo = account.steamId64
                            SteamAccountStore.switchTo(ctx, account.steamId64) { result, switched ->
                                switchingTo = null
                                onSwitchResult(result, switched ?: account)
                            }
                        },
                        onRemove = { confirmRemove = account },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SteamAccountActionRow(
                    label = stringResource(R.string.steam_account_add),
                    enabled = !busy,
                    onClick = onAddAccount,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.steam_account_close)) }
        },
    )

    confirmRemove?.let { account ->
        val isActive = account.steamId64 == activeSid
        OutlinedAlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.steam_account_remove_title)) },
            text = {
                Text(
                    stringResource(
                        if (isActive) R.string.steam_account_remove_active_body else R.string.steam_account_remove_body,
                        account.label,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        if (isActive) {
                            // The signed-in account: removing it IS the sign-out (ends the session).
                            onSignOutActive()
                        } else {
                            SteamAccountStore.remove(ctx, account.steamId64)
                            accounts = SteamAccountStore.list(ctx)
                            onMessage(ctx.getString(R.string.steam_account_removed, account.label))
                            if (accounts.isEmpty()) onDismiss()
                        }
                    },
                ) { Text(stringResource(R.string.steam_account_remove_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.steam_account_cancel)) }
            },
        )
    }
}

/** One saved account: avatar, name + login, an "Active" mark or progress, and a 48dp remove button. */
@Composable
private fun SteamAccountRow(
    account: SteamAccountStore.Account,
    isActive: Boolean,
    busy: Boolean,
    switching: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val subtitle = when {
        account.needsLogin -> stringResource(R.string.steam_account_expired)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .steamFocusRing(shape)
            .clip(shape)
            .background(if (isActive) LocalAccentDim.current else Color.Transparent)
            .clickable(enabled = !busy && !isActive, onClick = onSwitch)
            .heightIn(min = 56.dp)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SteamAccountAvatar(account)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account.needsLogin) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (switching) {
            CircularProgressIndicator(
                modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (isActive) {
            Text(
                text = stringResource(R.string.steam_account_active),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        IconButton(onClick = onRemove, enabled = !busy, modifier = Modifier.steamFocusRing(CircleShape)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.steam_account_remove_cd, account.label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Round avatar from the cached persona image, with the name's initial underneath while it loads. */
@Composable
private fun SteamAccountAvatar(account: SteamAccountStore.Account) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = account.label.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        if (account.avatarUrl.isNotEmpty()) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

/** The "Add account" row — same footprint as an account row. */
@Composable
private fun SteamAccountActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .steamFocusRing(shape)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The dialog for a SteamLite / Headless Steam launch held back by [SteamAccountStore.launchAccountCheck]:
 * [SteamAccountMismatchDialog] or [SteamNoOwnerDialog]. [onLaunchAnyway] and [onSwitchedLaunch] go on
 * with the launch, [onClose] cancels it.
 */
@Composable
fun SteamAccountLaunchDialog(
    check: SteamAccountStore.LaunchCheck.Warn,
    onLaunchAnyway: () -> Unit,
    onSwitchedLaunch: () -> Unit,
    onClose: () -> Unit,
) {
    when (check) {
        is SteamAccountStore.LaunchCheck.Mismatch ->
            SteamAccountMismatchDialog(check, onLaunchAnyway, onSwitchedLaunch, onClose)
        SteamAccountStore.LaunchCheck.NoOwner -> SteamNoOwnerDialog(onLaunchAnyway, onClose)
    }
}

/**
 * Shown before a launch when none of the saved accounts' owned-game lists has the game. Those lists can miss
 * games that are still playable (installed before accounts were tracked, family sharing, free licenses), so
 * "Launch anyway" goes on with the signed-in account; "Close" cancels.
 */
@Composable
private fun SteamNoOwnerDialog(onLaunchAnyway: () -> Unit, onClose: () -> Unit) {
    OutlinedAlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.steam_launch_no_owner_title))
            }
        },
        text = { Text(stringResource(R.string.steam_launch_no_owner_body)) },
        confirmButton = {
            TextButton(
                onClick = onLaunchAnyway,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.steam_launch_mismatch_launch_anyway)) }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.steam_account_close)) }
        },
    )
}

/**
 * Warning shown before a SteamLite / Headless Steam launch when the game belongs to a different saved
 * account than the one signed in. Choices, top to bottom:
 *  - "Switch account and launch" ([onSwitchedLaunch]): runs the same [SteamAccountStore.switchTo] as the
 *    Change account chooser (same refusals and messages) and calls [onSwitchedLaunch] once that account
 *    is active. A refusal or failure keeps this dialog open with the chooser's message and launches
 *    nothing; an expired saved sign-in opens the login screen pre-filled (as the chooser does) and
 *    closes this dialog without launching.
 *  - "Launch anyway" ([onLaunchAnyway]): this launch only, nothing is remembered.
 *  - "Close" ([onClose]): cancels the launch; also the back / outside-tap result (not while switching).
 */
@Composable
private fun SteamAccountMismatchDialog(
    mismatch: SteamAccountStore.LaunchCheck.Mismatch,
    onLaunchAnyway: () -> Unit,
    onSwitchedLaunch: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var switching by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val highlight = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    // The two names go in as markers first, so each can be styled wherever the sentence places it.
    val template = stringResource(R.string.steam_launch_mismatch_body, MARK_INSTALLER, MARK_ACTIVE)
    val body = buildAnnotatedString {
        for (c in template) {
            when (c) {
                MARK_INSTALLER_CHAR -> withStyle(highlight) { append(mismatch.ownerName) }
                MARK_ACTIVE_CHAR -> withStyle(highlight) { append(mismatch.activeName) }
                else -> append(c)
            }
        }
    }

    OutlinedAlertDialog(
        onDismissRequest = { if (!switching) onClose() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SwitchAccount, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.steam_launch_mismatch_title))
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = body)
                failure?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    enabled = !switching,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = {
                        failure = null
                        switching = true
                        SteamAccountStore.switchTo(ctx, mismatch.ownerSteamId) { result, account ->
                            switching = false
                            when (result) {
                                SteamAccountStore.SwitchResult.SWITCHED,
                                SteamAccountStore.SwitchResult.SWITCHED_CONNECTING,
                                SteamAccountStore.SwitchResult.ALREADY_ACTIVE -> onSwitchedLaunch()
                                SteamAccountStore.SwitchResult.NEEDS_LOGIN -> {
                                    // Expired / rejected saved sign-in: the login screen pre-filled with
                                    // that username, and nothing launches (same as the chooser).
                                    val user = account?.username
                                    SteamAccountStore.beginAddAccount(ctx) {
                                        runCatching {
                                            ctx.startActivity(
                                                Intent(ctx, SteamLoginActivity::class.java)
                                                    .putExtra(SteamLoginActivity.EXTRA_USERNAME, user),
                                            )
                                        }
                                    }
                                    onClose()
                                }
                                else -> failure = steamSwitchMessage(ctx, result, account)
                            }
                        }
                    },
                ) {
                    if (switching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.steam_launch_mismatch_switching))
                    } else {
                        Text(stringResource(R.string.steam_launch_mismatch_switch_launch))
                    }
                }
                TextButton(
                    enabled = !switching,
                    onClick = onLaunchAnyway,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.steam_launch_mismatch_launch_anyway)) }
                TextButton(
                    enabled = !switching,
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.steam_account_close)) }
            }
        },
    )
}

private const val MARK_INSTALLER_CHAR = '\u0001'
private const val MARK_ACTIVE_CHAR = '\u0002'
private const val MARK_INSTALLER = "\u0001"
private const val MARK_ACTIVE = "\u0002"

/** The user-facing sentence for a switch outcome (shared by the storefront host and the login screen). */
internal fun steamSwitchMessage(
    ctx: Context,
    result: SteamAccountStore.SwitchResult,
    account: SteamAccountStore.Account?,
): String {
    val name = account?.label.orEmpty()
    return when (result) {
        SteamAccountStore.SwitchResult.SWITCHED -> ctx.getString(R.string.steam_account_switched, name)
        SteamAccountStore.SwitchResult.SWITCHED_CONNECTING -> ctx.getString(R.string.steam_account_switched_connecting, name)
        SteamAccountStore.SwitchResult.SIGNED_IN_ELSEWHERE -> ctx.getString(R.string.steam_account_signed_in_elsewhere, name)
        SteamAccountStore.SwitchResult.NEEDS_LOGIN -> ctx.getString(R.string.steam_account_needs_login, name)
        SteamAccountStore.SwitchResult.ALREADY_ACTIVE -> ctx.getString(R.string.steam_account_already_active, name)
        SteamAccountStore.SwitchResult.DOWNLOAD_ACTIVE -> ctx.getString(R.string.steam_account_blocked_download)
        SteamAccountStore.SwitchResult.GAME_RUNNING -> ctx.getString(R.string.steam_account_blocked_game)
        SteamAccountStore.SwitchResult.NO_NETWORK -> ctx.getString(R.string.steam_account_no_network)
        SteamAccountStore.SwitchResult.BUSY -> ctx.getString(R.string.steam_account_busy)
        SteamAccountStore.SwitchResult.UNKNOWN_ACCOUNT,
        SteamAccountStore.SwitchResult.FAILED -> ctx.getString(R.string.steam_account_failed)
    }
}
