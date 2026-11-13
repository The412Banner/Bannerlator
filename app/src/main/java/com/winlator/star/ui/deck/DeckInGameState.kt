package com.winlator.star.ui.deck

import android.view.KeyEvent
import com.winlator.star.FeatureFlags
import com.winlator.star.store.InGameFriendsSource
import com.winlator.star.ui.XServerDrawerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What an in-game session runs, worked out once by XServerDisplayActivity.setupUI. The Linux Steam client and Wine never
 * share menu pieces: Wine tools stay out of the Linux sessions, the Steam client's pieces out of the Wine ones.
 */
enum class InGameSessionKind {
    WINE_X11, WINE_WAYLAND, LINUX_RUNTIME, LINUX_STEAM;

    val isWine: Boolean get() = this == WINE_X11 || this == WINE_WAYLAND
}

/**
 * The Deck in-game panels: the quick menu that slides in from the right and the full game menu on the left.
 * Only used while Appearance › Interface style is Deck; the Classic drawer never reads it.
 * XServerDisplayActivity owns the openers (Back, the Menu button, the four-finger tap, the corner button; never a pad
 * chord) and routes controller input here only while a panel is up. Every control inside calls the same
 * XServerDrawerState / XServerDialogState callbacks the Classic drawer uses, so nothing about how a setting applies or
 * saves differs between the two styles.
 */
object DeckInGameState {

    enum class Panel { NONE, QUICK, MENU }

    /** Full-menu pages, in rail order. STEAM, TV, FRIENDS and MODS only show where they apply (see [DeckInGameMenu]). */
    enum class MenuTab { SCREEN, PERFORMANCE, EFFECTS, OVERLAY, CONTROLS, AUDIO, STEAM, TV, FRIENDS, MODS, TOOLS }

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

    // Whether a control inside the panels holds Compose focus. The view keeps Android focus when the focused control is
    // removed (a page swap) or never got focus, so the activity checks this too before handing a d-pad press over.
    @Volatile private var controlFocused = false

    fun hasControlFocus(): Boolean = controlFocused

    internal fun setControlFocused(v: Boolean) { controlFocused = v }

    // Seeded by the activity in setupUI, after the container and shortcut are resolved.
    private val _sessionKind = MutableStateFlow(InGameSessionKind.WINE_X11)
    val sessionKind: StateFlow<InGameSessionKind> = _sessionKind

    private val _gameTitle = MutableStateFlow("")
    val gameTitle: StateFlow<String> = _gameTitle

    // The running game's shortcut (its .desktop path): Lab's in-game menu draws the game's art, logo and icon from it.
    private val _gameShortcutPath = MutableStateFlow<String?>(null)
    val gameShortcutPath: StateFlow<String?> = _gameShortcutPath

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

    // Lab: one in-game menu whose first page is Quick (the quick menu's controls), then the full menu's pages.
    private val _labQuick = MutableStateFlow(true)
    val labQuick: StateFlow<Boolean> = _labQuick
    // The XMB mode's drawer is Lab's menu in XMB dress (XmbInGameMenu), so it runs the same state machine. The two styles
    // never run together (AppThemeState keeps simpleHome and deckXmbHome apart).
    private val lab: Boolean get() = com.winlator.star.ui.theme.AppThemeState.simpleHome.value || xmbDrawer

    /** XMB mode: the in-game menu is the one glass drawer on the left (XmbInGameMenu) instead of the Deck's quick menu + game menu. */
    val xmbDrawer: Boolean get() = com.winlator.star.ui.theme.AppThemeState.deckXmbHome.value

    fun openQuick() {
        if (lab) _labQuick.value = true
        setPanel(Panel.QUICK)
    }

    fun openMenu() {
        if (lab) _labQuick.value = false
        setPanel(Panel.MENU)
    }

    fun openMenuAt(tab: MenuTab) {
        _menuTab.value = tab
        _labQuick.value = false
        setPanel(Panel.MENU)
    }

    /** Lab: the Quick page. */
    fun selectLabQuick() {
        _labQuick.value = true
        requestFocus()
    }

    /** Lab, L1 / R1: Quick, then the full menu's pages, round and round. */
    private fun cycleLab(dir: Int, available: List<MenuTab>) {
        val pages: List<MenuTab?> = listOf<MenuTab?>(null) + available
        val i = if (_labQuick.value) 0 else pages.indexOf(_menuTab.value).coerceAtLeast(0)
        val next = pages[(i + dir + pages.size) % pages.size]
        if (next == null) selectLabQuick() else selectMenuTab(next)
    }

    fun close() = setPanel(Panel.NONE)

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
        _labQuick.value = false
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

    /**
     * The full-menu pages that apply to this session: the Steam page only in the Linux Steam client, TV, Friends and Mods
     * as the Classic rail shows them (Mods while the game's EXE can take a mod, XServerDrawerState.mods).
     */
    fun availableMenuTabs(kind: InGameSessionKind, tv: Boolean, friends: Boolean, mods: Boolean = modsTabShown()): List<MenuTab> = MenuTab.values().filter {
        when (it) {
            MenuTab.STEAM -> kind == InGameSessionKind.LINUX_STEAM
            MenuTab.TV -> tv
            MenuTab.FRIENDS -> friends
            MenuTab.MODS -> mods
            else -> true
        }
    }

    fun availableQuickTabs(friends: Boolean): List<QuickTab> = QuickTab.values().filter { it != QuickTab.FRIENDS || friends }

    private fun tvTabShown(): Boolean {
        val d = XServerDrawerState
        return FeatureFlags.TV_OUTPUT_ENABLED && (d.tvConnected.value || d.castSupported.value)
    }

    private fun friendsTabShown(): Boolean = InGameFriendsSource.state.value.tabVisible

    private fun modsTabShown(): Boolean = XServerDrawerState.mods.value.isNotEmpty()

    /**
     * The panel's own controller buttons while one is open (key-down only): L1 / R1 change page, X swaps the quick menu
     * and the full menu, B closes (B never opens anything). Returns false for anything else, which the activity hands to
     * the panel's focus.
     */
    fun handlePanelKey(keyCode: Int): Boolean {
        val p = _panel.value
        if (p == Panel.NONE) return false
        if (lab) {
            // Lab's one menu: L1 / R1 step through Quick and the pages, X jumps between Quick and the last page, B closes.
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 ->
                    cycleLab(if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1, availableMenuTabs(_sessionKind.value, tvTabShown(), friendsTabShown()))
                KeyEvent.KEYCODE_BUTTON_X -> if (_labQuick.value) selectMenuTab(_menuTab.value) else selectLabQuick()
                KeyEvent.KEYCODE_BUTTON_B -> close()
                else -> return false
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                val dir = if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                if (p == Panel.MENU) {
                    cycleMenuTab(dir, availableMenuTabs(_sessionKind.value, tvTabShown(), friendsTabShown()))
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

    fun setSessionKind(v: InGameSessionKind) { _sessionKind.value = v }
    fun setGameTitle(v: String) { _gameTitle.value = v }
    fun setGameShortcutPath(v: String?) { _gameShortcutPath.value = v }
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
        controlFocused = false
        _menuTab.value = MenuTab.SCREEN
        _quickTab.value = QuickTab.QUICK
        _sessionKind.value = InGameSessionKind.WINE_X11
        _gameTitle.value = ""
        _gameShortcutPath.value = null
        _launchedFromShortcut.value = false
        _hudSavedToShortcut.value = false
        onPanelOpenChanged = null
        onCornerButtonChange = null
    }
}
