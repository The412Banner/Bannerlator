package com.winlator.star.ui.deck

import android.view.KeyEvent
import com.winlator.star.FeatureFlags
import com.winlator.star.store.InGameFriendsSource
import com.winlator.star.ui.XServerDrawerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Deck in-game panels: the quick menu that slides in from the right and the full game menu on the left.
 * Only used while Appearance › Interface style is Deck; the Classic drawer never reads it.
 * XServerDisplayActivity owns the openers (Select + Start, Back, the four-finger tap, the corner button) and routes
 * controller input here while a panel is up. Every control inside calls the same XServerDrawerState / XServerDialogState
 * callbacks the Classic drawer uses, so nothing about how a setting applies or saves differs between the two styles.
 */
object DeckInGameState {

    enum class Panel { NONE, QUICK, MENU }

    /** Full-menu pages, in rail order. STEAM, TV and FRIENDS only show where they apply (see [DeckInGameMenu]). */
    enum class MenuTab { SCREEN, PERFORMANCE, EFFECTS, OVERLAY, CONTROLS, AUDIO, STEAM, TV, FRIENDS, TOOLS }

    /** Quick-menu pages. FRIENDS only shows while a live friends source exists. */
    enum class QuickTab { QUICK, TOOLS, FRIENDS, OPENERS }

    private val _panel = MutableStateFlow(Panel.NONE)
    val panel: StateFlow<Panel> = _panel

    private val _menuTab = MutableStateFlow(MenuTab.SCREEN)
    val menuTab: StateFlow<MenuTab> = _menuTab

    private val _quickTab = MutableStateFlow(QuickTab.QUICK)
    val quickTab: StateFlow<QuickTab> = _quickTab

    // Bumped to ask the open panel to put controller focus on its first control (a panel just opened, a page changed,
    // or a d-pad press arrived while nothing in the panel had focus).
    private val _focusRequest = MutableStateFlow(0)
    val focusRequest: StateFlow<Int> = _focusRequest

    // Seeded by the activity in setupUI, after the container and shortcut are resolved.
    private val _gameTitle = MutableStateFlow("")
    val gameTitle: StateFlow<String> = _gameTitle

    // Launched from a shortcut: the "per game" settings are written to it; without one they land on the container.
    private val _launchedFromShortcut = MutableStateFlow(false)
    val launchedFromShortcut: StateFlow<Boolean> = _launchedFromShortcut

    // The performance overlay config lives on the shortcut only when the shortcut already carries its own copy.
    private val _hudSavedToShortcut = MutableStateFlow(false)
    val hudSavedToShortcut: StateFlow<Boolean> = _hudSavedToShortcut

    // The optional corner button that opens the quick menu by touch. Off by default, one app-wide preference.
    private val _cornerButton = MutableStateFlow(false)
    val cornerButton: StateFlow<Boolean> = _cornerButton

    /** Fired with true when a panel opens from nothing and false when the last one closes. Always on the main thread. */
    @JvmField var onPanelOpenChanged: java.util.function.Consumer<Boolean>? = null

    /** Persists the corner-button preference; the activity writes it to the app's shared preferences. */
    @JvmField var onCornerButtonChange: java.util.function.Consumer<Boolean>? = null

    fun isOpen(): Boolean = _panel.value != Panel.NONE

    fun openQuick() = setPanel(Panel.QUICK)

    fun openMenu() = setPanel(Panel.MENU)

    fun openMenuAt(tab: MenuTab) {
        _menuTab.value = tab
        setPanel(Panel.MENU)
    }

    fun close() = setPanel(Panel.NONE)

    /** Select + Start: nothing open → quick menu, quick menu → full menu, full menu → closed. */
    fun chord() = setPanel(
        when (_panel.value) {
            Panel.NONE -> Panel.QUICK
            Panel.QUICK -> Panel.MENU
            Panel.MENU -> Panel.NONE
        }
    )

    /** X: swaps between the quick menu and the full menu. */
    fun swapPanels() {
        when (_panel.value) {
            Panel.QUICK -> setPanel(Panel.MENU)
            Panel.MENU -> setPanel(Panel.QUICK)
            Panel.NONE -> Unit
        }
    }

    fun selectMenuTab(tab: MenuTab) {
        _menuTab.value = tab
        requestFocus()
    }

    fun selectQuickTab(tab: QuickTab) {
        _quickTab.value = tab
        requestFocus()
    }

    /** L1 / R1: the open panel's previous / next page among [available] (the pages that apply this session). */
    fun cycleMenuTab(dir: Int, available: List<MenuTab>) {
        if (available.isEmpty()) return
        val i = available.indexOf(_menuTab.value).coerceAtLeast(0)
        selectMenuTab(available[(i + dir + available.size) % available.size])
    }

    fun cycleQuickTab(dir: Int, available: List<QuickTab>) {
        if (available.isEmpty()) return
        val i = available.indexOf(_quickTab.value).coerceAtLeast(0)
        selectQuickTab(available[(i + dir + available.size) % available.size])
    }

    fun requestFocus() { _focusRequest.value = _focusRequest.value + 1 }

    /** The full-menu pages that apply to this session, the same conditions the Classic rail uses for its tabs. */
    fun availableMenuTabs(linuxSteam: Boolean, tv: Boolean, friends: Boolean): List<MenuTab> = MenuTab.values().filter {
        when (it) {
            MenuTab.STEAM -> linuxSteam
            MenuTab.TV -> tv
            MenuTab.FRIENDS -> friends
            else -> true
        }
    }

    fun availableQuickTabs(friends: Boolean): List<QuickTab> = QuickTab.values().filter { it != QuickTab.FRIENDS || friends }

    private fun tvTabShown(): Boolean {
        val d = XServerDrawerState
        return FeatureFlags.TV_OUTPUT_ENABLED && (d.tvConnected.value || d.castSupported.value)
    }

    private fun friendsTabShown(): Boolean = InGameFriendsSource.state.value.tabVisible

    /**
     * The panel's own controller buttons while one is open (key-down only): L1 / R1 change page, X swaps the quick menu
     * and the full menu, B closes. Returns false for anything else, which the activity hands to the panel's focus.
     */
    fun handlePanelKey(keyCode: Int): Boolean {
        val p = _panel.value
        if (p == Panel.NONE) return false
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                val dir = if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                if (p == Panel.MENU) {
                    cycleMenuTab(dir, availableMenuTabs(XServerDrawerState.linuxSteamSession.value, tvTabShown(), friendsTabShown()))
                } else {
                    cycleQuickTab(dir, availableQuickTabs(friendsTabShown()))
                }
            }
            KeyEvent.KEYCODE_BUTTON_X -> swapPanels()
            KeyEvent.KEYCODE_BUTTON_B -> close()
            else -> return false
        }
        return true
    }

    fun setGameTitle(v: String) { _gameTitle.value = v }
    fun setLaunchedFromShortcut(v: Boolean) { _launchedFromShortcut.value = v }
    fun setHudSavedToShortcut(v: Boolean) { _hudSavedToShortcut.value = v }
    fun setCornerButton(v: Boolean) { _cornerButton.value = v }

    fun changeCornerButton(v: Boolean) {
        _cornerButton.value = v
        onCornerButtonChange?.accept(v)
    }

    private fun setPanel(p: Panel) {
        val was = _panel.value
        if (was == p) return
        _panel.value = p
        if (p != Panel.NONE) requestFocus()
        if ((was == Panel.NONE) != (p == Panel.NONE)) onPanelOpenChanged?.accept(p != Panel.NONE)
    }

    /** A new game session: forget the previous one's panel, pages and callbacks (the object outlives the activity). */
    fun reset() {
        _panel.value = Panel.NONE
        _menuTab.value = MenuTab.SCREEN
        _quickTab.value = QuickTab.QUICK
        _gameTitle.value = ""
        _launchedFromShortcut.value = false
        _hudSavedToShortcut.value = false
        onPanelOpenChanged = null
        onCornerButtonChange = null
    }
}
