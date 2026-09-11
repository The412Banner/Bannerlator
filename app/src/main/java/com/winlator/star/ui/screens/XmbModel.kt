package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Nested XMB menus (Games tab, XMB view). A game's options open as further XMB columns instead of
// pop-up dialogs: Settings → section → setting → choices. This file is the MODEL only; rendering and
// input live in XmbMenuHost.kt.
//
// Contract for menu builders:
//  • A menu is an [XmbMenu] whose [XmbMenu.rows] lambda is re-evaluated on EVERY recomposition, so
//    conditional rows come and go by themselves. Read Compose state inside it (mutableStateOf in the
//    builder's closure) for async data, or call [XmbScope.refresh] after changing plain values such as
//    shortcut extras.
//  • Changes apply as you go (no OK/Cancel): a row's callback writes the value (e.g. putExtra +
//    saveData) and then calls xmb.saved() so the "✓ Saved" flash shows.
//  • Row keys must be unique within a menu and stable across rebuilds (selection is tracked by key).
//  • Anything blocking (disk, network) goes through xmb.scope + Dispatchers.IO; update state, not UI.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Keys a custom [XmbPanel] can receive (the host maps D-pad, controller buttons and keyboard). */
internal enum class XmbKey { Up, Down, Left, Right, A, B, L1, R1 }

/** L1/R1 cycling between sibling menus at the same depth (e.g. the Settings sections). */
internal class XmbSiblings(
    val index: Int,
    val count: Int,
    val make: (Int) -> XmbMenu,
    /** Key of the row in the PARENT menu that opens sibling n (so the parent's highlight follows). */
    val parentKey: (Int) -> String,
)

/** What a confirm step says. Shown as its own XMB column that opens on Cancel. */
internal data class XmbConfirm(val title: String, val message: String, val okLabel: String, val danger: Boolean = false)

/** A custom full-width panel in place of rows (e.g. a scrollable log). */
internal interface XmbPanel {
    @Composable
    fun Content(modifier: Modifier)
    /** Called for every key while this panel is on top. Return true if handled; B always goes back otherwise. */
    fun onKey(key: XmbKey): Boolean = false
}

internal class XmbMenu(
    val title: String,
    val icon: ImageVector,
    initialKey: String? = null,
    /** Custom panel instead of rows. */
    val panel: XmbPanel? = null,
    /** Called once when this menu is closed (back, popToRoot, or replaced by a sibling). */
    val onClose: (() -> Unit)? = null,
    val rows: XmbScope.() -> List<XmbRow> = { emptyList() },
) {
    var selKey by mutableStateOf(initialKey)
    var siblings: XmbSiblings? = null
}

/** Every row type. [subtitle] shows under the label on the highlighted row; [disabledReason] non-null
 *  greys the row out and is shown instead of the subtitle. */
internal sealed class XmbRow {
    abstract val key: String
    abstract val label: String

    class Header(override val key: String, override val label: String) : XmbRow()

    class Toggle(
        override val key: String, override val label: String, val icon: ImageVector,
        val value: Boolean, val subtitle: String? = null, val disabledReason: String? = null,
        val onChange: (Boolean) -> Unit,
    ) : XmbRow()

    /** A value picked from [options] (shown as a choices column). ◀▶ step through it in place. */
    class Choice(
        override val key: String, override val label: String, val icon: ImageVector,
        val options: List<String>, val selected: String,
        val subtitle: String? = null, val disabledReason: String? = null,
        /** Options shown but not pickable (e.g. DirectAudio on an unsupported layer). */
        val disabledOptions: Set<String> = emptySet(),
        /** Non-null → ask first (e.g. "SurfaceFlinger renderer — experimental"). */
        val confirm: ((String) -> XmbConfirm?)? = null,
        val onSelect: (String) -> Unit,
    ) : XmbRow()

    /** Typed with the on-screen keyboard. */
    class Text(
        override val key: String, override val label: String, val icon: ImageVector,
        val value: String, val subtitle: String? = null, val disabledReason: String? = null,
        val placeholder: String = "", val numeric: Boolean = false,
        val onCommit: (String) -> Unit,
    ) : XmbRow()

    class Slider(
        override val key: String, override val label: String, val icon: ImageVector,
        val value: Float, val min: Float, val max: Float, val step: Float,
        val format: (Float) -> String = { "%.1f".format(it) },
        val subtitle: String? = null, val disabledReason: String? = null,
        val onChange: (Float) -> Unit,
    ) : XmbRow()

    /** Opens a deeper column ("›"). [value] is an optional summary shown on the right. */
    class Link(
        override val key: String, override val label: String, val icon: ImageVector,
        val value: String? = null, val subtitle: String? = null, val disabledReason: String? = null,
        val open: () -> XmbMenu,
    ) : XmbRow()

    /** Runs something. [thumbnail] shows a small image instead of the icon (e.g. cover results). */
    class Action(
        override val key: String, override val label: String, val icon: ImageVector,
        val subtitle: String? = null, val value: String? = null, val danger: Boolean = false,
        val disabledReason: String? = null, val thumbnail: ImageBitmap? = null,
        val onClick: () -> Unit,
    ) : XmbRow()

    /** Read-only line. */
    class Info(
        override val key: String, override val label: String, val icon: ImageVector,
        val value: String = "", val subtitle: String? = null,
    ) : XmbRow()

    /** Leaves the XMB for a system screen ("↗"), e.g. Android's add-to-home prompt. */
    class External(
        override val key: String, override val label: String, val icon: ImageVector,
        val value: String? = null, val subtitle: String? = null,
        val onOpen: () -> Unit,
    ) : XmbRow()
}

/** What builders and row callbacks can do. */
internal interface XmbScope {
    val context: Context
    /** Lives as long as the Games tab; use withContext(Dispatchers.IO) for blocking work. */
    val scope: CoroutineScope
    fun push(menu: XmbMenu)
    fun pop()
    /** Back to the games bar (e.g. after Remove / Clone). */
    fun popToRoot()
    /** Re-read rows after changing non-Compose values (shortcut extras). */
    fun refresh()
    fun toast(message: String)
    /** Flash "✓ Saved" in the breadcrumb (call after writing a setting). */
    fun saved()
    /** Push a confirm column (opens on Cancel); [onOk] runs after it closes. */
    fun confirm(confirm: XmbConfirm, onOk: () -> Unit)
    /** The game list may be stale (rename, clone, remove, new exe): reload it. */
    fun reloadGames()
}
