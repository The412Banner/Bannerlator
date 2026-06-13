package com.winlator.star.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R

private val PureBlack = Color(0xFF000000)
private val DarkSurface = Color(0xFF0D0D0D)
private val DimWhite = Color(0xFFE8E8E8)
private val MutedWhite = Color(0xFF999999)
private val GlowPurple = Color(0xFFBB86FC)
private val PrimaryDim = Color(0xFF482880)

private fun iconFor(screen: Screen): ImageVector = when (screen) {
    Screen.Containers    -> Icons.Filled.FolderOpen
    Screen.Games         -> Icons.Filled.OpenInNew
    Screen.InputControls -> Icons.Filled.SportsEsports
    Screen.AdrenoTools   -> Icons.Filled.Memory
    Screen.Saves         -> Icons.Filled.Save
    Screen.FileManager   -> Icons.Filled.FolderOpen
    Screen.Settings      -> Icons.Filled.Settings
    Screen.Appearance    -> Icons.Filled.Palette
    Screen.LsfgSettings  -> Icons.Filled.Settings
    else                 -> Icons.Filled.Storefront
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

        DrawerSection("Emulation")
        DrawerItem(Screen.Games,         currentRoute, onNavigate)
        DrawerItem(Screen.Containers,    currentRoute, onNavigate)
        DrawerItem(Screen.Settings,      currentRoute, onNavigate)

        HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))

        DrawerSection("Tools")
        DrawerItem(Screen.InputControls, currentRoute, onNavigate)
        DrawerItem(Screen.AdrenoTools,   currentRoute, onNavigate)
        DrawerItem(Screen.Saves,         currentRoute, onNavigate)
        DrawerItem(Screen.LsfgSettings,  currentRoute, onNavigate)

        HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))

        DrawerSection("Game Stores")
        Screen.storeItems.forEach { screen ->
            DrawerStoreItem(screen, onLaunchStore)
        }

        HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp))

        DrawerSection("About And Support")
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

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerSection(title: String) {
    Column(modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = DimWhite,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.3f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(GlowPurple, GlowPurple.copy(alpha = 0.1f))),
                    RoundedCornerShape(1.dp)
                )
        )
    }
}

@Composable
private fun DrawerItem(screen: Screen, currentRoute: String, onNavigate: (Screen) -> Unit) {
    val selected = currentRoute == screen.route
    val bgBrush = if (selected)
        Brush.verticalGradient(listOf(PrimaryDim, GlowPurple.copy(alpha = 0.3f)))
    else
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    val borderColor = if (selected) GlowPurple.copy(alpha = 0.6f) else Color.Transparent
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
                        colors = listOf(GlowPurple.copy(alpha = 0.15f), Color.Transparent),
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
                imageVector = iconFor(screen),
                contentDescription = null,
                tint = if (selected) Color.White else MutedWhite,
                modifier = Modifier.size(20.dp),
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
            tint = MutedWhite,
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
            tint = MutedWhite,
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
            tint = GlowPurple,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = GlowPurple,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
