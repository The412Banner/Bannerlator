package com.winlator.star.ui.deck.settings

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.FastOpenGL
import com.winlator.star.core.LinuxVulkanDriver
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.compositorDriverChoices
import com.winlator.star.ui.screens.isSteamOriginShortcut
import com.winlator.star.widget.XServerView
import java.io.File

// The Deck settings editor's data model. Every setting is declared once (SettingsRegistry) with the
// real storage it reads and writes: the container form (ContainerDetailViewModel, which also backs
// the All-containers defaults profile), a game's shortcut extras, or an app preference. The editor
// screen only ever talks to a SettingsSource, so the same rows serve all four scopes.

/**
 * How much the editor shows: Simple is the handful most people touch, Advanced more, Expert everything. The plain Deck, Lab and
 * Classic keep all three. The XMB editor has two: there [EXPERT] reads as ADVANCED ([effective], [parse]), folded where the
 * rows are filed into its tabs ([EditorLayout.apply]) and where its level choice is read and written.
 */
internal enum class SettingLevel(val label: String) {
    SIMPLE("Simple"), ADVANCED("Advanced"), EXPERT("Expert");

    /** XMB: this level with EXPERT folded into ADVANCED, the only two levels its editor works with. */
    val effective: SettingLevel get() = if (this == EXPERT) ADVANCED else this

    companion object {
        /** XMB: a stored level name; anything unknown (or absent) is SIMPLE, and the third level "EXPERT" is ADVANCED. */
        fun parse(s: String?): SettingLevel = when (s) {
            "ADVANCED", "EXPERT" -> ADVANCED
            else -> SIMPLE
        }
    }
}

/** What one editor edits. DEFAULTS is "All containers", i.e. the New Container Defaults profile. */
internal enum class EditorScope { DEFAULTS, CONTAINER, GAME, APP }

/**
 * Which runtime a game belongs to. The worlds never cross: a Wine row is never offered to the Linux
 * Steam client entry or an Android game, and a Linux runtime row only to the Linux entry. Containers,
 * All containers and app settings are always [WINE] here (their rows say otherwise where they must).
 */
internal enum class World { WINE, LINUX, ANDROID }

internal val WINE_ONLY: Set<World> = setOf(World.WINE)
internal val WINE_LINUX: Set<World> = setOf(World.WINE, World.LINUX)
internal val LINUX_ONLY: Set<World> = setOf(World.LINUX)
internal val ALL_WORLDS: Set<World> = setOf(World.WINE, World.LINUX, World.ANDROID)

internal class SettingsCategory(
    val id: String,
    val label: String,
    val desc: String,
    val icon: ImageVector,
    /** The group label over this page's hand-off rows. Null keeps "More settings" ("In the classic editor" on a page with no rows). */
    val actionsLabel: String? = null,
)

/** One choice of a segmented, chips or select control. A non-null [disabledReason] greys it out and says why. */
internal data class Opt(val value: String, val label: String, val disabledReason: String? = null)

internal sealed interface Control {
    /** On/off. Values are "1" and "0". */
    data object Toggle : Control
    class Segmented(val options: List<Opt>) : Control
    class Chips(val options: List<Opt>) : Control
    /**
     * A drop-down list. With [customLabel] set, a last entry lets the user type a value of their own;
     * [validateCustom] returns an error message, or null when the text is fine.
     */
    class Select(
        val options: List<Opt>,
        val customLabel: String? = null,
        val customHint: String? = null,
        val validateCustom: ((String) -> String?)? = null,
        val normalizeCustom: (String) -> String = { it.trim() },
    ) : Control
    /** [display] is what the row shows; [encode] is the stored form (e.g. "80" or "2.0"). */
    class Slider(
        val min: Float,
        val max: Float,
        val step: Float,
        val display: (Float) -> String,
        val encode: (Float) -> String,
    ) : Control
    class TextField(val placeholder: String = "", val validate: (String) -> String? = { null }) : Control
    /** A tick per CPU core. The value is the comma-separated list the container and shortcut already store. */
    data object Cores : Control
    /**
     * Items each switched on or off in a dialog (a driver's Vulkan extensions). The value is the
     * comma-separated list of the items switched OFF. [items] is null while they are still being read;
     * [waiting] and [empty] are what the row says then and when there are none.
     */
    class Checklist(val items: List<String>?, val title: String, val waiting: String, val empty: String) : Control
}

/**
 * Read/write access to a game's shortcut extras. [get] returns null when the key is absent; [put] with
 * null removes it. [container] is the game's container as saved, for the settings a game stores as a
 * whole string (Win Components, the driver config) and edits one part of.
 */
internal interface GameExtras {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    val container: Container
    val env: SettingsEnv
}

/** A setting's value on the container form. Used for both a real container and the All-containers profile. */
internal class VmBinding(
    val get: (SettingsEnv, ContainerDetailViewModel) -> String,
    val set: (SettingsEnv, ContainerDetailViewModel, String) -> Unit,
)

/** What an unset per-game value follows, which decides the row's source label. */
internal enum class Inherit { CONTAINER, APP, BUILT_IN, NONE }

/**
 * A setting's per-game storage. [read] returns null while the game has no value of its own, and the
 * launch path then uses the container's (or the app's) value. [keys] are every extra the setting
 * owns; Reset removes all of them.
 */
internal class GameBinding(
    val keys: List<String>,
    val read: (GameExtras) -> String?,
    val write: (GameExtras, String) -> Unit,
    val inherit: Inherit = Inherit.CONTAINER,
    /** The value an unset game follows when it is not the container form's value ([inherit] APP or BUILT_IN). */
    val inherited: ((SettingsEnv) -> String)? = null,
    /** What counts as "Default" for a container-inherited value that has no container-form binding. */
    val builtIn: String? = null,
    /** Reset for a setting that is one part of a larger extra; null removes every key in [keys]. */
    val reset: ((GameExtras) -> Unit)? = null,
)

/** A global app preference. Written the moment it changes. */
internal class AppBinding(
    val get: (Context) -> String,
    val set: (Context, String) -> Unit,
)

/** A confirmation asked before a value is taken, e.g. the SurfaceFlinger renderer's warning. */
internal class Confirm(val title: String, val body: String, val confirmLabel: String)

internal class SettingDef(
    val id: String,
    val category: String,
    val group: String,
    val label: String,
    val hint: String? = null,
    val help: String? = null,
    /** The old label or raw key, shown small from Advanced up. */
    val tech: String? = null,
    val level: SettingLevel,
    val control: (SettingsEnv, (String) -> String) -> Control,
    val vm: VmBinding? = null,
    val game: GameBinding? = null,
    val app: AppBinding? = null,
    /** Also on the All-containers profile (some container fields, like its name, are per container only). */
    val onDefaults: Boolean = true,
    /** Shown read-only in a game's editor when it has no per-game storage. */
    val showInGame: Boolean = true,
    /** Context gates and value conditions together. [values] reads another setting's current value by id. */
    val visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> true },
    /** Non-null greys the control out and shows the reason under it. */
    val disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    val confirm: ((String) -> Confirm?)? = null,
    /** The worlds the row belongs to (see [World]). Wine only unless it says otherwise. */
    val worlds: Set<World> = WINE_ONLY,
    /** A line under the row that says something about the current value, e.g. a driver warning. */
    val note: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    /**
     * Makes the row show a small "Get more" button that opens where more of what it lists can be downloaded
     * (a Wine version, a DXVK build...). The value is a kind name the host understands, passed to
     * [SettingsEnv.getMoreOpen]; the button is only drawn while the host has set that handler.
     */
    val getMore: String? = null,
) {
    /**
     * The same row in another category / group / level (and, for the XMB layout, another label / hint), every other field
     * carried over. The XMB editor's [EditorLayout] uses it to move rows into its tabs without touching the registries. A field
     * added to this class must be added here too, or a copied row silently loses it.
     */
    fun copyWith(
        category: String = this.category,
        group: String = this.group,
        level: SettingLevel = this.level,
        label: String = this.label,
        hint: String? = this.hint,
    ): SettingDef = SettingDef(
        id = id, category = category, group = group, label = label, hint = hint, help = help, tech = tech, level = level,
        control = control, vm = vm, game = game, app = app, onDefaults = onDefaults, showInGame = showInGame,
        visible = visible, disabled = disabled, confirm = confirm, worlds = worlds, note = note, getMore = getMore,
    )
}

/** A row that opens the classic editor (or another screen) for what the Deck editor does not edit itself. */
internal class Handoff(val label: String, val hint: String, val target: String, val worlds: Set<World> = WINE_ONLY)

/**
 * Everything a setting may look at to decide its options and whether it applies. Built once per
 * editor. [vm] is the container form: the game's container in a game editor, the container itself,
 * or the All-containers profile. It is null only for app settings.
 */
internal class SettingsEnv(
    val context: Context,
    val scope: EditorScope,
    val vm: ContainerDetailViewModel?,
    val shortcut: Shortcut?,
) {
    val isLinuxEntry: Boolean = shortcut?.let { com.winlator.star.linux.LinuxShortcuts.isLinuxEntry(it) } == true
    val isAndroid: Boolean = shortcut?.let { AndroidGames.isAndroidEntry(it) } == true
    val isSteam: Boolean = shortcut?.let { s -> runCatching { isSteamOriginShortcut(s) }.getOrDefault(false) } == true
    val isEpic: Boolean = shortcut?.getExtra("storeSource") == "epic"
    val isWindowsSteam: Boolean = shortcut?.let { com.winlator.star.store.WindowsSteamClient.isWindowsSteamShortcut(it) } == true
    /** An Xbox game whose install ships Microsoft's xgameruntime.dll (the "Xbox runtime" setting applies). */
    val xboxShipsRuntime: Boolean = shortcut?.takeIf { it.getExtra("storeSource") == "xbox" }?.getExtra("xboxProductId")
        ?.takeIf { it.isNotEmpty() }
        ?.let { id -> runCatching { com.winlator.star.store.xbox.XboxRuntime.shipsOwnRuntime(context, id) }.getOrDefault(false) } == true
    val world: World = when {
        isAndroid -> World.ANDROID
        isLinuxEntry -> World.LINUX
        else -> World.WINE
    }
    /**
     * What a row's "Get more" button runs (see [SettingDef.getMore]): opens the download sheet for that kind of
     * content. The host screen sets it while it builds this env (before the editor draws); null hides the button.
     */
    var getMoreOpen: ((String) -> Unit)? = null

    val arm64ec: Boolean get() = vm?.isArm64EC == true
    /** A Box64EC component is installed, so the x86-64 translator can be switched off FEXCore. */
    val box64ECInstalled: Boolean get() = vm?.box64ECVersionEntries?.isNotEmpty() == true

    /** Whether the container's Wine layer can drive Wayland. Always true on the defaults profile, which has no layer. */
    val waylandCapable: Boolean
        get() = scope == EditorScope.DEFAULTS || vm?.let { it.isWineWaylandCapable(it.selectedWineVersion) } == true

    private val display = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.display
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }.getOrNull()
    val panelRates: List<Int> = display?.let { runCatching { XServerView.getSupportedRefreshRates(it) }.getOrNull() } ?: emptyList()
    val vrrCapable: Boolean = display?.let { runCatching { XServerView.isDisplayVrrCapable(it) }.getOrDefault(false) } ?: false
    val lsfgDllAvailable: Boolean = File(context.filesDir, "lsfg-vk/Lossless.dll").isFile

    // Lists read from preferences and disk. Read once per editor, not on every recomposition.
    val fexPresets by lazy { runCatching { com.winlator.star.fexcore.FEXCorePresetManager.getPresets(context).toList() }.getOrDefault(emptyList()) }
    val box64Presets by lazy { runCatching { com.winlator.star.box64.Box64PresetManager.getPresets("box64", context).toList() }.getOrDefault(emptyList()) }
    val controlsProfiles by lazy {
        runCatching { com.winlator.star.inputcontrols.InputControlsManager(context).getProfiles(true).toList() }.getOrDefault(emptyList())
    }
    val rendererDrivers by lazy {
        runCatching { com.winlator.star.contents.AdrenotoolsManager(context).enumarateInstalledDrivers().toList() }.getOrDefault(emptyList())
    }
    /** The Linux runtime's draw drivers, value to label (the runtime's own first). Only read for the Linux entry. */
    val linuxDrawDrivers: List<Pair<String, String>> by lazy {
        runCatching { LinuxVulkanDriver.optionValues(context).map { it to LinuxVulkanDriver.optionLabel(context, it) } }
            .getOrDefault(listOf("" to LinuxVulkanDriver.optionLabel(context, "")))
    }

    /** The DX wrapper and GPU driver lists and probes of the Graphics page, read while that page is open. */
    val graphics: GraphicsLists by lazy { GraphicsLists(context) }

    /**
     * What the Wayland compositor driver picker offers (the same list as the classic editors), or null
     * while [loadAsync] is still probing the drivers.
     */
    var compositorChoices by mutableStateOf<List<String>?>(null)
        private set

    /** Probes that must run off the main thread. Called once per editor. */
    suspend fun loadAsync() {
        if (scope == EditorScope.APP) return
        compositorChoices = runCatching { compositorDriverChoices(context) }.getOrDefault(emptyList())
    }

    private val fastGlReasons = HashMap<String, String?>()

    /** Why Fast OpenGL can't run on X11 with this graphics driver, or null when it can. Cached per driver. */
    fun fastOpenGLReason(driverVersion: String): String? {
        if (fastGlReasons.containsKey(driverVersion)) return fastGlReasons[driverVersion]
        val reason = runCatching { FastOpenGL.unavailableReason(context, driverVersion) }.getOrNull()
        fastGlReasons[driverVersion] = reason
        return reason
    }

    /** The Wayland backend is in effect: a Linux entry always, otherwise the chosen backend on a capable layer. */
    fun wayland(values: (String) -> String): Boolean =
        isLinuxEntry || (values(ID_BACKEND) == Container.DISPLAY_BACKEND_WAYLAND && waylandCapable)

    companion object {
        const val ID_BACKEND = "display.backend"
    }
}
