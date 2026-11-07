package com.winlator.star.ui.simple

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.deck.SoraFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

/** The device's live numbers the Lab frame and home show. Read every 20 s. */
internal class LabDeviceInfo(
    val battery: Int,
    val tempC: Int?,
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val diskUsedGb: Float,
    val diskTotalGb: Float,
    val time: String,
)

internal fun readLabDevice(context: Context): LabDeviceInfo {
    val battery = batteryPercent(context)
    val temp = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }?.let { (it / 10f).roundToInt() }
    }.getOrNull()
    val mem = ActivityManager.MemoryInfo()
    runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem) }
    val gb = 1024f * 1024f * 1024f
    val disk = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
    val total = disk?.totalBytes ?: 0L
    val free = disk?.availableBytes ?: 0L
    return LabDeviceInfo(
        battery = battery,
        tempC = temp,
        ramUsedGb = (mem.totalMem - mem.availMem) / gb,
        ramTotalGb = mem.totalMem / gb,
        diskUsedGb = (total - free) / gb,
        diskTotalGb = total / gb,
        time = android.text.format.DateFormat.getTimeFormat(context).format(Date()),
    )
}

@Composable
internal fun rememberLabDevice(): LabDeviceInfo? {
    val context = LocalContext.current
    var info by remember { mutableStateOf<LabDeviceInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            info = withContext(Dispatchers.IO) { runCatching { readLabDevice(context) }.getOrNull() }
            delay(20_000)
        }
    }
    return info
}

internal fun fmtGb(v: Float): String = if (v >= 100f) "${v.roundToInt()}" else String.format(java.util.Locale.ENGLISH, "%.1f", v)

/** A thin progress bar in the accent colour. */
@Composable
internal fun LabBar(fraction: Float, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.height(5.dp).background(SimpleLine, SimpleShape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(accent, SimpleShape))
    }
}

/** Landscape: the section rail down the left, icons with short labels. */
@Composable
internal fun LabRail(current: Int, onSection: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(SimplePanelBrush, RectangleShape)
            .drawBehind { drawRect(SimpleLineDraw, topLeft = androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), size = Size(1f, size.height)) }
            .padding(vertical = 14.dp),
    ) {
        LAB_SECTIONS.forEachIndexed { i, s ->
            val on = i == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .width(66.dp)
                    .then(if (on) Modifier.background(accent.copy(alpha = 0.16f), SimpleShape) else Modifier)
                    .labTile(onClick = { onSection(i) })
                    .padding(vertical = 7.dp),
            ) {
                Icon(s.icon, contentDescription = s.label, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(21.dp))
                Text(s.short, color = if (on) SimpleStrong else SimpleMuted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** Kept apart so the draw lambda reads it at draw time. */
private val SimpleLineDraw: Color get() = SimpleLine

/** The slim line over every Lab page: (portrait) the menu button, the name and device, then battery, temperature and clock. */
@Composable
internal fun LabTopLine(portrait: Boolean, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val dev = rememberLabDevice()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().height(48.dp).padding(horizontal = if (portrait) 12.dp else 18.dp),
    ) {
        if (portrait) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).labPanel().labTile(onClick = onMenu),
            ) { Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = SimpleInk, modifier = Modifier.size(22.dp)) }
        }
        Text("Bannerlator", color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
        Text(Build.MODEL ?: "", color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (dev != null) {
            if (dev.battery >= 0) Text("${dev.battery}%", color = SimpleMuted, fontSize = 13.sp)
            dev.tempC?.let { Text("$it°C", color = SimpleMuted, fontSize = 13.sp) }
            Text(dev.time, color = SimpleInk, fontSize = 13.sp)
        }
    }
}

/**
 * Portrait: the drawer. Its own window, so the controller stays in it until it closes (B, a tap outside, or a pick).
 * Device numbers on top, the sections, and any download in progress at the bottom.
 */
@Composable
internal fun LabDrawer(current: Int, onSection: (Int) -> Unit, onClose: () -> Unit) {
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val dev = rememberLabDevice()
        val downloads by ContentDownloadRegistry.states.collectAsState()
        val active = downloads.values.firstOrNull { !it.terminal }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        BackHandler { onClose() }
        Row(Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, SimpleLine)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 18.dp),
            ) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
                    Text("Bannerlator", color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(Build.MODEL ?: "", color = SimpleMuted, fontSize = 12.sp)
                    if (dev != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Storage", color = SimpleMuted, fontSize = 12.sp)
                            LabBar(if (dev.diskTotalGb > 0) dev.diskUsedGb / dev.diskTotalGb else 0f, Modifier.weight(1f))
                            Text("${fmtGb(dev.diskUsedGb)} / ${fmtGb(dev.diskTotalGb)} GB", color = SimpleMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                if (dev.battery >= 0) "${dev.battery}%" else null,
                                dev.tempC?.let { "$it°C" },
                                "RAM ${fmtGb(dev.ramUsedGb)} / ${fmtGb(dev.ramTotalGb)} GB",
                            ).joinToString("  ·  "),
                            color = SimpleMuted, fontSize = 12.sp,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SimpleLine))
                Spacer(Modifier.height(6.dp))
                LAB_SECTIONS.forEachIndexed { i, s ->
                    val on = i == current
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .then(if (on) Modifier.background(accent.copy(alpha = 0.16f), SimpleShape) else Modifier)
                            .labTile(onClick = { onSection(i) }, requester = if (on) first else null)
                            .padding(horizontal = 12.dp),
                    ) {
                        Icon(s.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(21.dp))
                        Text(s.label, color = if (on) SimpleStrong else SimpleInk, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
                if (active != null) {
                    Spacer(Modifier.height(14.dp))
                    Text("Downloading ${active.title} · ${(active.fraction * 100).roundToInt()}%", color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    Spacer(Modifier.height(6.dp))
                    LabBar(active.fraction, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x66000000)).simpleTap(onClose))
        }
    }
}
