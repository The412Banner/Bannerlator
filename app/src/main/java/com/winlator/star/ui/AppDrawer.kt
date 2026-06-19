package com.winlator.star.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R

private val PureBlack = Color(0xFF000000)
private val DarkSurface = Color(0xFF000000)
private val DimWhite = Color(0xFFEEEEEE)
private val MutedWhite = Color(0xFF999999)
private val GlowBlue = Color(0xFF0055FF)
private val PrimaryDim = Color(0xFF002277)

private fun iconFor(screen: Screen): Int = when (screen) {
    Screen.Containers    -> R.drawable.icon_menu_container
    Screen.Games         -> R.drawable.icon_menu_contents
    Screen.InputControls -> R.drawable.icon_gamepad
    Screen.AdrenoTools   -> R.drawable.icon_menu_gpu
    Screen.Saves         -> R.drawable.icon_save
    Screen.FileManager   -> R.drawable.icon_menu_file_manager
    Screen.Settings      -> R.drawable.icon_settings
    Screen.Appearance    -> R.drawable.icon_settings
    else                 -> R.drawable.icon_container
}

@Composable
fun AppDrawerContent(
    currentRoute: String,
    onNavigate: (Screen) -> Unit,
    onLaunchStore: (Screen) -> Unit,
    onAbout: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showHelp) {
        HelpSupportDialog(
            onDismiss = { showHelp = false },
            onOpenUrl = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(PureBlack)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))

        DrawerItem(Screen.Games,         currentRoute, onNavigate)
        DrawerItem(Screen.Containers,    currentRoute, onNavigate)
        DrawerItem(Screen.FileManager,   currentRoute, onNavigate)
        DrawerItem(Screen.Settings,      currentRoute, onNavigate)

        HorizontalDivider(color = GlowBlue, modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))

        DrawerItem(Screen.InputControls, currentRoute, onNavigate)
        DrawerItem(Screen.AdrenoTools,   currentRoute, onNavigate)

        HorizontalDivider(color = GlowBlue, modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))

        Screen.storeItems.forEach { screen ->
            DrawerStoreItem(screen, onLaunchStore)
        }

        HorizontalDivider(color = GlowBlue, modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))

        DrawerIconItem(
            label = "About",
            icon = Icons.Filled.Info,
            onClick = onAbout,
        )
        DrawerIconItem(
            label = "Help and Support",
            icon = Icons.Filled.HelpOutline,
            onClick = { showHelp = true },
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = GlowBlue, modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))
        StorageWidget()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StorageWidget() {
    var usedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        try {
            val path = Environment.getExternalStorageDirectory().path
            val stat = StatFs(path)
            val total = stat.totalBytes
            val available = stat.availableBytes
            usedBytes = total - available
            totalBytes = total
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A0A0A))
            .border(1.dp, Color(0xFF242424), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Storage",
                    color = DimWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (totalBytes > 0) {
                    Text(
                        text = "${formatBytes(usedBytes)} / ${formatBytes(totalBytes)}",
                        color = MutedWhite,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = GlowBlue,
                trackColor = Color(0xFF333333),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000_000L -> "%.1f TB".format(bytes / 1_000_000_000_000.0)
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

@Composable
private fun DrawerItem(screen: Screen, currentRoute: String, onNavigate: (Screen) -> Unit) {
    val selected = currentRoute == screen.route
    val bgBrush = if (selected)
        Brush.verticalGradient(listOf(PrimaryDim, GlowBlue.copy(alpha = 0.3f)))
    else
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    val borderColor = if (selected) GlowBlue.copy(alpha = 0.6f) else Color.Transparent
    val contentColor = if (selected) Color.White else DimWhite

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgBrush, RoundedCornerShape(10.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onNavigate(screen) },
    ) {
        if (selected) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(GlowBlue.copy(alpha = 0.15f), Color.Transparent),
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Icon(
                painter = painterResource(iconFor(screen)),
                contentDescription = null,
                tint = GlowBlue,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = screen.label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun DrawerStoreItem(screen: Screen, onLaunchStore: (Screen) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLaunchStore(screen) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Storefront,
            contentDescription = null,
            tint = GlowBlue,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = screen.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MutedWhite,
        )
    }
}

@Composable
private fun DrawerIconItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GlowBlue,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = DimWhite,
        )
    }
}

@Composable
private fun HelpSupportDialog(onDismiss: () -> Unit, onOpenUrl: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help & Support") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "For bug reports, feature requests, and support, visit the GitHub repository.",
                    color = MaterialTheme.colorScheme.onSurface
                )
                SupportLink(
                    label = "GitHub Repository",
                    url = "https://github.com/The412Banner/star-compose",
                    onOpenUrl = onOpenUrl
                )
                SupportLink(
                    label = "Report an Issue",
                    url = "https://github.com/The412Banner/star-compose/issues",
                    onOpenUrl = onOpenUrl
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SupportLink(label: String, url: String, onOpenUrl: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(url) }
            .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.OpenInNew,
            contentDescription = null,
            tint = GlowBlue,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = GlowBlue,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
