package com.winlator.star.net

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The floating in-game chat window. Rendered INSIDE the existing over-the-game overlay layer
 * (XServerDialogHost) as a positioned Box — NOT a Dialog — so it is **non-modal**: only the window/bubble
 * itself consumes touches; every touch outside it falls through to the game, so you keep playing while
 * chatting. Three states from [LanChat.windowMode]:
 *   HIDDEN   → nothing on screen (chat still runs in the background; unread accrues).
 *   BUBBLE   → a small draggable dot with an unread badge; tap to expand.
 *   EXPANDED → the full translucent, draggable window with a `_` minimize + `X` close title bar.
 *
 * Position is remembered here and shared across minimize/expand. The window is deliberately compact and
 * semi-transparent so it floats over gameplay without hiding the track.
 */
@Composable
fun LanChatOverlay() {
    val mode by LanChat.windowMode.collectAsState()
    if (mode == LanChat.WindowMode.HIDDEN) return

    // Full-screen, NON-consuming container: no background, no pointer modifier -> empty space passes touches
    // through to the game. Only the positioned child below is interactive.
    Box(Modifier.fillMaxSize()) {
        // Shared position across BUBBLE <-> EXPANDED. Default: upper-right-ish, nudged in from the edge.
        var offX by remember { mutableStateOf(48f) }
        var offY by remember { mutableStateOf(120f) }
        val drag = Modifier.pointerInput(Unit) {
            detectDragGestures { change, d ->
                change.consume()
                offX += d.x
                offY += d.y
            }
        }
        Box(Modifier.offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }) {
            when (mode) {
                LanChat.WindowMode.BUBBLE -> ChatBubble(drag)
                LanChat.WindowMode.EXPANDED -> ChatWindow(drag)
                else -> Unit
            }
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
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable { LanChat.expand() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Wifi, contentDescription = "Open chat",
            tint = if (connected) Color.White else Color.White.copy(alpha = 0.5f))
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(Color(0xFFE5484D), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 9) "9+" else unread.toString(),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChatWindow(dragHandle: Modifier) {
    val messages by LanChat.messages.collectAsState()
    val connected by LanChat.connected.collectAsState()
    val peer by LanChat.peerPresent.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    Column(
        Modifier
            .width(288.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
    ) {
        // ── Title bar = the DRAG HANDLE (dragging here won't fight the list scroll / text field) ──
        Row(
            Modifier
                .fillMaxWidth()
                .then(dragHandle)
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
                if (connected) "Say hi — messages send instantly, even while the game runs."
                else "Connecting…",
                color = Color.White.copy(alpha = 0.65f), fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages) { m -> ChatLine(m) }
            }
        }

        // ── Input row ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 500) input = it },
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
}

private inline fun send(text: String, clear: () -> Unit) {
    val t = text.trim()
    if (t.isEmpty()) return
    LanChat.sendMessage(t)
    clear()
}

@Composable
private fun ChatLine(m: LanChat.ChatMsg) {
    val align = if (m.mine) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .align(if (m.mine) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(
                        if (m.mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else Color.Black.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                if (!m.mine) {
                    Text(m.name.ifBlank { if (m.from == "host") "Host" else "Guest" },
                        color = Color.White.copy(alpha = 0.7f), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(m.text, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
