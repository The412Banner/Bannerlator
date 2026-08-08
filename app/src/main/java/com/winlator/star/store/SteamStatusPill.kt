package com.winlator.star.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R

/**
 * Always-visible connection/login indicator that lives in the top header of every Steam screen.
 *
 * It is the honest replacement for the (cosmetic, never-updated) foreground-service notification:
 * while the app is foregrounded the Steam CM connection lives inside the SteamRepository singleton
 * in-process, so this pill reflects the real session state without depending on the notification.
 * Tapping it when not-online calls [SteamRepository.reconnectNow]. Every state change is logged to
 * the persistent steam_session.txt by the repository (see SteamRepository.setStatus).
 */
@Composable
fun SteamStatusPill(
    status: SteamRepository.SteamStatus,
    onReconnect: () -> Unit,
) {
    val (dot, tappable) = when (status) {
        SteamRepository.SteamStatus.ONLINE ->
            Color(0xFF3BA55D) to false
        SteamRepository.SteamStatus.CONNECTING ->
            Color(0xFFE0A82E) to false
        SteamRepository.SteamStatus.SIGNED_IN_ELSEWHERE ->
            Color(0xFFE07B2E) to true
        SteamRepository.SteamStatus.OFFLINE ->
            Color(0xFFCB4B4B) to true
        SteamRepository.SteamStatus.SIGNED_OUT ->
            Color(0xFF9AA0A6) to true
    }
    val label = when (status) {
        SteamRepository.SteamStatus.ONLINE ->
            stringResource(R.string.compose_steam_saves_connection_online)
        SteamRepository.SteamStatus.CONNECTING ->
            stringResource(R.string.compose_steam_saves_connection_connecting)
        SteamRepository.SteamStatus.SIGNED_IN_ELSEWHERE ->
            stringResource(R.string.compose_steam_saves_connection_signed_in_elsewhere)
        SteamRepository.SteamStatus.OFFLINE ->
            stringResource(R.string.compose_steam_saves_connection_offline)
        SteamRepository.SteamStatus.SIGNED_OUT ->
            stringResource(R.string.compose_steam_saves_connection_signed_out)
    }

    val base = Modifier
        .clip(RoundedCornerShape(50))
        .background(Color(0x22FFFFFF))
        .border(1.dp, dot.copy(alpha = 0.5f), RoundedCornerShape(50))
    val mod = if (tappable) base.clickable { onReconnect() } else base

    Row(
        modifier = mod.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (tappable) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.compose_steam_saves_reconnect_symbol),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
