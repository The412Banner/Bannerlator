package com.winlator.star.net

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * The floating in-game chat window. Rendered inside a **[Popup]** — a SEPARATE window — because a plain
 * composable in the over-the-game overlay layer is hidden BEHIND the game's fullscreen Vulkan/ASR
 * SurfaceView (a known z-order bug in this codebase; the same reason the working in-game menus are
 * Dialogs, not plain views). The Popup is `focusable = true` (so you can type) but NOT dismiss-on-
 * outside-click, which leaves it **non-modal**: touches outside the window fall through to the game, so
 * you keep playing. Three states from [LanChat.windowMode]: HIDDEN / BUBBLE / EXPANDED.
 *
 * Draggable by the title bar, resizable by the bottom-right corner, ~72% translucent. Position + size are
 * remembered while shown.
 */
@Composable
fun LanChatOverlay() {
    val mode by LanChat.windowMode.collectAsState()
    if (mode == LanChat.WindowMode.HIDDEN) return

    // Position + size, shared across BUBBLE <-> EXPANDED while the overlay is shown.
    var offX by remember { mutableStateOf(64f) }
    var offY by remember { mutableStateOf(140f) }
    var widthDp by remember { mutableStateOf(292f) }
    var listHeightDp by remember { mutableStateOf(196f) }
    val density = LocalDensity.current

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offX.roundToInt(), offY.roundToInt()),
        onDismissRequest = { /* controlled by the title-bar _ / X, never by outside touches */ },
        properties = PopupProperties(
            focusable = true,          // needed for the text field / soft keyboard
            dismissOnBackPress = false,
            dismissOnClickOutside = false,  // -> non-touch-modal: outside touches reach the game
            clippingEnabled = false,
        ),
    ) {
        val dragMove = Modifier.pointerInput(Unit) {
            detectDragGestures { change, d -> change.consume(); offX += d.x; offY += d.y }
        }
        when (mode) {
            LanChat.WindowMode.BUBBLE -> ChatBubble(dragMove)
            LanChat.WindowMode.EXPANDED -> ChatWindow(
                dragMove = dragMove,
                widthDp = widthDp,
                listHeightDp = listHeightDp,
                onResize = { dx, dy ->
                    widthDp = (widthDp + with(density) { dx.toDp().value }).coerceIn(244f, 460f)
                    listHeightDp = (listHeightDp + with(density) { dy.toDp().value }).coerceIn(120f, 420f)
                },
            )
            else -> Unit
        }
    }
}

@Composable
private fun ChatBubble(dragHandle: Modifier) {
    val unread by LanChat.unread.collectAsState()
    val connected by LanChat.connected.collectAsState()
    Box(
        Modifier
            .then(dragHandle)
            .size(52.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable { LanChat.expand() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Wifi, contentDescription = "Open chat",
            tint = if (connected) Color.White else Color.White.copy(alpha = 0.5f))
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).background(Color(0xFFE5484D), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 9) "9+" else unread.toString(),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChatWindow(dragMove: Modifier, widthDp: Float, listHeightDp: Float, onResize: (Float, Float) -> Unit) {
    val messages by LanChat.messages.collectAsState()
    val connected by LanChat.connected.collectAsState()
    val peer by LanChat.peerPresent.collectAsState()
    val peerTyping by LanChat.peerTyping.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, peerTyping) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    Box {
        Column(
            Modifier
                .width(widthDp.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
        ) {
            // ── Title bar = the DRAG HANDLE ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(dragMove)
                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(
                    if (connected && peer) Color(0xFF3FB950) else if (connected) Color(0xFFD29922) else Color(0xFF6E7681),
                    CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("LAN Chat", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { LanChat.minimize() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { LanChat.hide() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            // ── Messages ──
            if (messages.isEmpty()) {
                Text(
                    if (connected) "Say hi — messages send instantly, even while the game runs." else "Connecting…",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 12.5.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = listHeightDp.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(messages) { m -> ChatLine(m) }
                }
            }

            // ── Typing indicator ──
            if (peerTyping) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypingDots()
                }
            }

            // ── Input row ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 500) { input = it; LanChat.notifyTyping() } },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) { input = "" } }),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { send(input) { input = "" } }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                        tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f))
                }
            }
        }

        // ── Resize handle (bottom-right corner): drag to grow/shrink ──
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(22.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, d -> change.consume(); onResize(d.x, d.y) }
                },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(Modifier.padding(3.dp).size(11.dp).border(1.5.dp, Color.White.copy(alpha = 0.4f),
                RoundedCornerShape(bottomEnd = 5.dp)))
        }
    }
}

private inline fun send(text: String, clear: () -> Unit) {
    val t = text.trim()
    if (t.isEmpty()) return
    LanChat.sendMessage(t)
    clear()
}

@Composable
private fun ChatLine(m: LanChat.ChatMsg) {
    val label = if (m.mine) "You" else m.name.ifBlank { if (m.from == "host") "Host" else "Guest" }
    Column(Modifier.fillMaxWidth()) {
        // Sender name label, on the same side as the bubble.
        Text(
            label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            textAlign = if (m.mine) TextAlign.End else TextAlign.Start,
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                m.text, color = Color.White, fontSize = 13.sp,
                modifier = Modifier
                    .align(if (m.mine) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(
                        if (m.mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val name by LanChat.messages.collectAsState()  // not used; kept simple below
    val peerName = name.lastOrNull { !it.mine }?.name?.ifBlank { null }
    val t = rememberInfiniteTransition(label = "typing")
    Text((peerName ?: "Peer") + " is typing", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    Spacer(Modifier.width(5.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.padding(horizontal = 1.5.dp).size(4.dp).background(Color.White.copy(alpha = a), CircleShape))
        }
    }
}
