package com.winlator.star.ui.simple

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.ENTRY_CONTAINER
import com.winlator.star.ui.deck.ENTRY_PLAY
import com.winlator.star.ui.deck.NEEDS_INSTALL
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.gameMenuGroups
import com.winlator.star.ui.screens.GameMenuAction
import kotlinx.coroutines.delay

/**
 * Lab's game menu (long press, X or ⋯ on a game): the same actions as the Deck game menu (gameMenuGroups: what applies
 * to this game, Xbox games not downloaded yet, Android and Linux entries), drawn as a Lab panel. A side panel that slides
 * in from the right in landscape, a sheet that slides up in portrait; slightly see-through over a fading shade.
 */
@Composable
internal fun LabGameMenu(
    shortcut: Shortcut,
    subtitle: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAction: (GameMenuAction) -> Unit,
    onContainer: () -> Unit,
    /** Lab's Game details page (its tab: 0 Details, 1 Artwork) for Game details and Cover art; the classic dialogs when null. */
    onDetails: ((Int) -> Unit)? = null,
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    // Run the pick (or just close) once the panel has slid away.
    val pending = remember { arrayOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> Unit = { after -> pending[0] = after; shown.targetState = false }
    LaunchedEffect(shown.isIdle, shown.currentState, shown.targetState) {
        if (shown.isIdle && !shown.currentState && !shown.targetState) {
            onDismiss()
            pending[0]?.invoke()
        }
    }
    val groups = remember(shortcut) { gameMenuGroups(shortcut) }
    val notInstalled = remember(shortcut) { com.winlator.star.store.xbox.XboxCloudShortcuts.isNotInstalled(shortcut) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(140); runCatching { first.requestFocus() } }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = { close {} },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        BackHandler { close {} }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxHeight > maxWidth
            AnimatedVisibility(visibleState = shown, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
                Box(Modifier.fillMaxSize().background(Color(0x73000000)).simpleTap { close {} })
            }
            AnimatedVisibility(
                visibleState = shown,
                enter = if (portrait) slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it } else slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it },
                exit = if (portrait) slideOutVertically(tween(220, easing = FastOutLinearInEasing)) { it } else slideOutHorizontally(tween(220, easing = FastOutLinearInEasing)) { it },
                modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.CenterEnd),
            ) {
                Column(
                    modifier = Modifier
                        .then(if (portrait) Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.88f) else Modifier.width(420.dp).fillMaxHeight())
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), SimpleShape)
                        .border(1.dp, SimpleLine, SimpleShape)
                        .simpleTap { }
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SimpleCover(shortcut, Modifier.width(54.dp).height(72.dp), title = false)
                        Column(Modifier.weight(1f)) {
                            LabTileLabel(L("Game menu"))
                            Text(LabTitles.of(shortcut), color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp).background(SimpleCard, SimpleShape).labTile(onClick = { close {} }),
                        ) { Icon(Icons.Filled.Close, contentDescription = L("Close"), tint = SimpleInk, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        groups.forEachIndexed { gi, group ->
                            if (gi > 0) Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(SimpleLine))
                            group.forEachIndexed { i, (key, entry) ->
                                val blocked = notInstalled && (key == ENTRY_CONTAINER || entry.action in NEEDS_INSTALL)
                                val play = key == ENTRY_PLAY
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .then(if (play) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), SimpleShape) else Modifier)
                                        .labTile(
                                            onClick = {
                                                when {
                                                    blocked -> Unit
                                                    play -> close(onPlay)
                                                    key == ENTRY_CONTAINER -> close(onContainer)
                                                    entry.action != null -> {
                                                        val a = entry.action
                                                        val tab = when (a) { GameMenuAction.GAME_DETAILS -> 0; GameMenuAction.SCRAPE_COVER -> 1; else -> -1 }
                                                        if (onDetails != null && tab >= 0) close { onDetails(tab) } else close { onAction(a) }
                                                    }
                                                }
                                            },
                                            requester = if (gi == 0 && i == 0) first else null,
                                        )
                                        .then(if (blocked) Modifier.alpha(0.45f) else Modifier)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        if (blocked) L("%1\$s · download the game first", entry.title) else entry.title,
                                        color = when {
                                            entry.danger -> MaterialTheme.colorScheme.error
                                            play -> MaterialTheme.colorScheme.primary
                                            else -> SimpleStrong
                                        },
                                        fontSize = 15.sp,
                                        fontWeight = if (play) FontWeight.Bold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("›", color = SimpleMuted, fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
