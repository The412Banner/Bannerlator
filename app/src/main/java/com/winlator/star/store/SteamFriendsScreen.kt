package com.winlator.star.store

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

// Status-dot colours — recognisable Steam-ish presence palette, held explicitly so both themes match.
private val DotInGame = Color(0xFF90BA3C)
private val DotOnline = Color(0xFF57CBDE)
private val DotAway = Color(0xFFE0A82E)
private val DotOffline = Color(0xFF6D7883)

// List chrome — thin gray lines, pop-up-menu style, read as subtle grays on the dark surface.
private val RowDivider = Color(0x1AFFFFFF)      // between friends within a section
private val SectionDivider = Color(0x2EFFFFFF)  // between sections (In game / Online / Offline)
private val UnreadBg = Color(0xFFE53935)        // unread-count badge fill

private fun dotColor(p: SteamFriendsStore.Presence): Color = when (p) {
    SteamFriendsStore.Presence.IN_GAME -> DotInGame
    SteamFriendsStore.Presence.ONLINE -> DotOnline
    SteamFriendsStore.Presence.AWAY -> DotAway
    SteamFriendsStore.Presence.OFFLINE -> DotOffline
}

// Section order + labels, top to bottom.
private val PRESENCE_ORDER = listOf(
    SteamFriendsStore.Presence.IN_GAME to "In game",
    SteamFriendsStore.Presence.ONLINE to "Online",
    SteamFriendsStore.Presence.AWAY to "Away",
    SteamFriendsStore.Presence.OFFLINE to "Offline",
)

// ── Friends list ────────────────────────────────────────────────────────────────

@Composable
fun FriendsListScreen(
    available: Boolean,
    onBack: () -> Unit,
    onOpenChat: (SteamFriendsStore.SteamFriend) -> Unit,
) {
    val friends by SteamFriendsStore.friends.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var menuFriend by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header: back + title + live connection pill + refresh.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "Friends",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            SteamConnectionPill()
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add friend",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = { SteamFriendsStore.refresh() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (showAdd) {
            AddFriendDialog(
                onDismiss = { showAdd = false },
                onAdd = { SteamFriendsStore.addFriend(it); showAdd = false },
            )
        }
        menuFriend?.let { f ->
            FriendActionsDialog(
                friend = f,
                onDismiss = { menuFriend = null },
                onMessage = { menuFriend = null; onOpenChat(f) },
                onJoin = {
                    menuFriend = null
                    runCatching {
                        ctx.startActivity(
                            Intent(ctx, SteamGameDetailActivity::class.java)
                                .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, f.gameAppId),
                        )
                    }
                },
                onProfile = {
                    menuFriend = null
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://steamcommunity.com/profiles/${f.steamId}")),
                        )
                    }
                },
            )
        }

        when {
            !available -> ConnectState()
            friends.isEmpty() -> LoadingState()
            else -> {
                val grouped = friends.groupBy { it.presence }
                val unread by SteamFriendsStore.unread.collectAsState()
                // Per-section collapse (arrow folds a group away); persisted so it's remembered.
                val collapsedSections by SteamFriendsStore.collapsedSections.collectAsState()
                val listState = rememberLazyListState()
                // A freshly opened list always starts at the top.
                LaunchedEffect(Unit) { listState.scrollToItem(0) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    var firstSection = true
                    for ((presence, label) in PRESENCE_ORDER) {
                        val group = grouped[presence].orEmpty()
                        if (group.isEmpty()) continue
                        val isCollapsed = presence in collapsedSections
                        val showTop = !firstSection
                        firstSection = false
                        item(key = "hdr_$presence") {
                            SectionHeader(
                                label = label,
                                count = group.size,
                                collapsed = isCollapsed,
                                showTopDivider = showTop,
                                onToggle = { SteamFriendsStore.setSectionCollapsed(presence, !isCollapsed) },
                            )
                        }
                        if (!isCollapsed) {
                            itemsIndexed(group, key = { _, f -> f.steamId }) { index, friend ->
                                FriendRow(
                                    friend = friend,
                                    unread = unread[friend.steamId] ?: 0,
                                    onClick = { onOpenChat(friend) },
                                    onLongClick = { menuFriend = friend },
                                )
                                if (index < group.lastIndex) FriendDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendActionsDialog(
    friend: SteamFriendsStore.SteamFriend,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onJoin: () -> Unit,
    onProfile: () -> Unit,
) {
    val inGame = friend.presence == SteamFriendsStore.Presence.IN_GAME && friend.gameAppId != 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(friend.displayName) },
        text = {
            Column {
                TextButton(onClick = onMessage) { Text("Message") }
                if (inGame) {
                    TextButton(onClick = onJoin) { Text("Join ${friend.gameName ?: "game"}") }
                }
                TextButton(onClick = onProfile) { Text("View Steam profile") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddFriendDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a Steam friend") },
        text = {
            Column {
                Text(
                    text = "Enter a SteamID64 or an account name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("SteamID64 or name") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConnectState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Not connected to Steam",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Connect to Steam to see your friends and chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { runCatching { SteamRepository.getInstance().reconnectNow() } }) {
            Text("Connect")
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Loading friends…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    collapsed: Boolean,
    showTopDivider: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTopDivider) {
            HorizontalDivider(thickness = 1.dp, color = SectionDivider)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label — $count",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) "Expand $label" else "Collapse $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (collapsed) 180f else 0f),
            )
        }
    }
}

/** Thin gray line between friends within a section (pop-up-menu style, inset from the edges). */
@Composable
private fun FriendDivider() {
    HorizontalDivider(
        thickness = 0.7.dp,
        color = RowDivider,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Unread-count pill (e.g. a friend has sent messages you haven't opened). */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .widthIn(min = 20.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(UnreadBg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FriendRow(
    friend: SteamFriendsStore.SteamFriend,
    unread: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendAvatar(friend = friend, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = friend.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (friend.presence == SteamFriendsStore.Presence.IN_GAME) DotInGame
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            UnreadBadge(unread)
        }
    }
}

/** Circular avatar: Steam CDN image over an initials chip (initials show if the image is absent/fails),
 *  with a presence dot in the lower-right corner. */
@Composable
private fun FriendAvatar(friend: SteamFriendsStore.SteamFriend, size: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials(friend.displayName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            val url = friend.avatarUrl
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            }
        }
        // Presence dot, bottom-right, ringed by the background so it reads against the avatar.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.30f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dotColor(friend.presence)),
            )
        }
    }
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

// ── Chat ────────────────────────────────────────────────────────────────────────

@Composable
fun ChatScreen(friend: SteamFriendsStore.SteamFriend, onBack: () -> Unit) {
    val session by SteamFriendsStore.chat.collectAsState()
    // Only show messages once openChat has switched the shared flow to this friend.
    val messages = if (session.steamId == friend.steamId) session.messages else emptyList()

    LaunchedEffect(friend.steamId) { SteamFriendsStore.openChat(friend.steamId) }

    // Typing indicator: a friend is "typing" until their expiry passes; a 1s ticker re-evaluates.
    val typingMap by SteamFriendsStore.typing.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val isTyping = (typingMap[friend.steamId] ?: 0L) > nowMs
    val self by SteamFriendsStore.self.collectAsState()

    // Android photo picker (no runtime permission needed). The returned content Uri is read + uploaded
    // off the main thread; SteamFriendsStore.sendImage handles the token mint + community-host upload.
    val ctx = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val name = displayNameFromUri(ctx, uri)
            Thread {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        SteamFriendsStore.sendImage(friend.steamId, bytes, name)
                    }
                } catch (_: Throwable) {
                }
            }.start()
        }
    }

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Open at the first unread message (captured before openChat clears the count), else the bottom.
    // New messages arriving while viewing animate to the bottom.
    val unreadAtOpen = remember(friend.steamId) { SteamFriendsStore.unread.value[friend.steamId] ?: 0 }
    var didInitialScroll by remember(friend.steamId) { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!didInitialScroll) {
            val target = if (unreadAtOpen in 1..messages.size) messages.size - unreadAtOpen else messages.size - 1
            listState.scrollToItem(target.coerceIn(0, messages.size - 1))
            didInitialScroll = true
        } else {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header: back + avatar + name/status.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            FriendAvatar(friend = friend, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isTyping) "typing…" else friend.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isTyping -> MaterialTheme.colorScheme.primary
                        friend.presence == SteamFriendsStore.Presence.IN_GAME -> DotInGame
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Messages.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Text(
                    text = "No messages yet. Say hello!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(messages) { msg -> MessageBubble(msg, friend, self) }
                }
            }
        }

        // Composer (emoji quick-picker + input + send).
        var showEmoji by remember { mutableStateOf(false) }
        Column {
            if (showEmoji) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(EMOJIS) { e ->
                        Text(
                            text = e,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { draft += e }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showEmoji = !showEmoji }) {
                    Text("😊", fontSize = 20.sp)
                }
                IconButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = "Send image",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; if (it.isNotEmpty()) SteamFriendsStore.sendTyping(friend.steamId) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val body = draft.trim()
                        if (body.isNotEmpty()) {
                            SteamFriendsStore.sendMessage(friend.steamId, body)
                            draft = ""
                        }
                    }),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        val body = draft.trim()
                        if (body.isNotEmpty()) {
                            SteamFriendsStore.sendMessage(friend.steamId, body)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// A small, dependency-free set of common emojis for the composer quick-picker (the system keyboard's
// full emoji set still works too — this is just a fast tap-to-insert strip).
private val EMOJIS = listOf(
    "😀", "😂", "😁", "😊", "😍", "😎", "🤔", "😅", "😭", "😡",
    "👍", "👎", "👌", "🙏", "👏", "💪", "🔥", "🎉", "❤️", "💯",
    "🎮", "🕹️", "😴", "🤝", "👀", "✅", "❌", "⭐", "😢", "🤣",
)

@Composable
private fun MessageBubble(
    msg: SteamFriendsStore.ChatMessage,
    friend: SteamFriendsStore.SteamFriend,
    self: SteamFriendsStore.SteamFriend?,
) {
    val mine = msg.fromSelf
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Received: friend's avatar on the left, next to the bubble.
        if (!mine) {
            FriendAvatar(friend = friend, size = 30.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            // Sender name heading above each bubble ("You" for our own messages).
            Text(
                text = if (mine) (self?.displayName ?: "You") else friend.displayName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (mine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                val imageUrl = remember(msg.text) { imageUrlOrNull(msg.text) }
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Image",
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mine) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (msg.timestampSec > 0) {
                    Text(
                        text = timeLabel(msg.timestampSec),
                        fontSize = 10.sp,
                        color = (if (mine) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .align(if (mine) Alignment.End else Alignment.Start),
                    )
                }
            }
        }
        // Sent: our own avatar on the right, mirroring the friend's on the left.
        if (mine && self != null) {
            Spacer(Modifier.width(8.dp))
            FriendAvatar(friend = self, size = 30.dp)
        }
    }
}

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun timeLabel(sec: Long): String = TIME_FMT.format(Date(sec * 1000L))

/** Best-effort display name for a picked image [uri] (only used as Steam upload metadata). */
private fun displayNameFromUri(ctx: android.content.Context, uri: Uri): String {
    return try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() } else null
            } else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "image.jpg"
    } catch (_: Throwable) {
        "image.jpg"
    }
}

private val URL_RE = Regex("""https?://\S+""")

/** If the whole message is essentially one image URL, return it (render as an image); else null. */
private fun imageUrlOrNull(text: String): String? {
    val t = text.trim()
    val url = URL_RE.find(t)?.value ?: return null
    if (t != url) return null // mixed text + link stays text
    val bare = url.substringBefore('?').lowercase()
    val isImgExt = bare.endsWith(".jpg") || bare.endsWith(".jpeg") || bare.endsWith(".png") ||
        bare.endsWith(".gif") || bare.endsWith(".webp")
    val isSteamImg = url.contains("steamusercontent.com") || url.contains("steamcdn") || url.contains("/ugc/")
    return if (isImgExt || isSteamImg) url else null
}
