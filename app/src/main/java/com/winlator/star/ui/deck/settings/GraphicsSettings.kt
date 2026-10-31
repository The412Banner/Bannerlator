package com.winlator.star.ui.deck.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.WrapperManager
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.GpuSpoof
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.MaliPanvk
import com.winlator.star.core.WaylandAdapter
import com.winlator.star.core.WaylandGameDriver
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.EXPERT
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.ContentDownloadSheet
import com.winlator.star.ui.screens.DxvkConfigDialog
import com.winlator.star.ui.screens.StockConfigDownloadSheet
import com.winlator.star.ui.screens.VegasDownloadSheet
import com.winlator.star.ui.screens.WAYLAND_DRIVER_SETTINGS_HELP
import com.winlator.star.ui.screens.WAYLAND_PRESENT_MODES
import com.winlator.star.ui.screens.WrapperManagerDialog
import com.winlator.star.ui.screens.adrenodownload.AdrenoDriverDownloadSheet
import com.winlator.star.ui.screens.graphicsProbeMutex
import com.winlator.star.ui.screens.waylandAutoVariant
import com.winlator.star.ui.screens.withGraphicsDriverKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.winlator.star.contentdialog.WineD3DConfigDialog as WineD3DConfig

// The Graphics page's DirectX translation and GPU driver settings: every choice of the classic "DX
// Wrapper Config" dialog (DxvkConfigDialog for DXVK and VEGAS, WineD3DConfigDialog) and of the X11
// "Graphics driver configuration" dialog (GraphicsDriverConfigDialog), as ordinary rows. Both dialogs
// store one packed string, on the container and per game as a shortcut extra: "dxwrapperConfig"
// ("k=v,k=v", KeyValueSet) and "graphicsDriverConfig" ("k=v;k=v"). A row changes only its own key and
// keeps every other entry byte for byte, so the classic dialogs read what is written here and the
// other way round. A game keeps a string of its own only while it differs from its container's, as
// the Win Components rows do. An absent key reads as what the classic dialog shows for it.
//
// The Wayland driver gear (WaylandDriverSettingsDialog) is rows too: the Wayland game driver, then the
// gear's keys of the same driver config. GPU name, memory, present mode and the OneUI fix are the X11
// rows shown on both backends; the Mali (PanVK) switches are rows of their own (waylandDefs).
//
// What is not a setting sits beside the rows (DeckGraphicsState): the downloads of DXVK, VKD3D, D7VK
// and VEGAS builds, of stock VEGAS configs and of Turnip drivers, the wrapper manager, and the VEGAS
// config file editor. The lists those rows offer are read while the Graphics page is open (GraphicsLists).

internal object GraphicsRows {

    // The rows other code reads by id.
    const val ID_WRAPPER = "graphics.dxWrapper"
    const val ID_GFX_DRIVER = "graphics.driver"
    const val ID_DXVK = "graphics.dx.dxvkVersion"
    const val ID_VEGAS = "graphics.dx.vegasVersion"
    const val ID_VKD3D = "graphics.dx.vkd3dVersion"
    const val ID_DDRAW = "graphics.dx.ddrawWrapper"
    const val ID_VEGAS_CONFIG = "graphics.dx.vegasConfigFile"
    const val ID_DRIVER_VERSION = "graphics.gdc.version"
    const val ID_GMEM = "graphics.gdc.turnipGmem"
    const val ID_BCN = "graphics.gdc.bcnEmulation"
    const val ID_BCN_TYPE = "graphics.gdc.bcnEmulationType"
    /** The whole wrapper config, never shown: what the VEGAS config file editor reads and writes back. */
    const val ID_DX_CONFIG = "graphics.dx.config"

    // ── The two packed strings ──────────────────────────────────────────────────────────────────

    /** One packed settings string: its shortcut extra, its container-form field and its key=value syntax. */
    private class Packed(
        val extra: String,
        val ofVm: (ContainerDetailViewModel) -> String,
        val toVm: (ContainerDetailViewModel, String) -> Unit,
        val ofContainer: (Container) -> String?,
        /** The raw value of a key, or null when the key is absent. */
        val raw: (String?, String) -> String?,
        /** The config with each key set (a null value removes it); every other entry is kept as it is. */
        val with: (String, Map<String, String?>) -> String,
    ) {
        /** The game's string after [change], or null when that equals its container's (the game then follows it). */
        fun gameWith(e: GameExtras, change: (String) -> String): String? {
            val containerCfg = ofContainer(e.container) ?: ""
            val next = change(e.get(extra)?.takeIf { it.isNotEmpty() } ?: containerCfg)
            return next.takeIf { it != containerCfg }
        }
    }

    // An empty wrapper config reads as the default one, as DXVKConfigDialog.parseConfig does.
    private fun dxBase(config: String?): String = config?.takeIf { it.isNotEmpty() } ?: Container.DEFAULT_DXWRAPPERCONFIG

    private val DX_CFG = Packed(
        extra = "dxwrapperConfig",
        ofVm = { it.dxWrapperConfig },
        toVm = { vm, v -> vm.dxWrapperConfig = v },
        ofContainer = { it.getDXWrapperConfig() },
        raw = { config, key ->
            var found: String? = null
            for (kv in KeyValueSet(dxBase(config))) if (kv[0] == key) { found = kv[1]; break }
            found
        },
        with = { config, changes ->
            changes.entries.fold(dxBase(config)) { s, (k, v) ->
                if (v == null) s.split(",").filter { it.isNotEmpty() && it.substringBefore('=') != k }.joinToString(",")
                else KeyValueSet(s).apply { put(k, v) }.toString()
            }
        },
    )

    private val DRIVER_CFG = Packed(
        extra = "graphicsDriverConfig",
        ofVm = { it.graphicsDriverConfig },
        toVm = { vm, v -> vm.graphicsDriverConfig = v },
        ofContainer = { it.getGraphicsDriverConfig() },
        raw = { config, key -> config?.split(";")?.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "") },
        with = { config, changes ->
            val next = withGraphicsDriverKeys(config, changes.filterValues { it != null }.mapValues { it.value!! })
            val removed = changes.filterValues { it == null }.keys
            if (removed.isEmpty()) next else next.split(";").filter { it.substringBefore('=') !in removed }.joinToString(";")
        },
    )

    /**
     * One key of a packed string as a row. [decode] turns the stored text (null = absent) into the
     * row's value; [encode] turns a picked value back, given the key's stored text (null removes the
     * key); [also] names other keys the classic dialog changes with this one.
     */
    private fun packedRow(
        p: Packed,
        id: String,
        key: String,
        group: String,
        label: String,
        level: SettingLevel,
        control: (SettingsEnv, (String) -> String) -> Control,
        decode: (SettingsEnv, String?) -> String = { _, r -> r ?: "" },
        encode: (String, String?) -> String? = { v, _ -> v },
        also: (String, String) -> Map<String, String?> = { _, _ -> emptyMap() },
        hint: String? = null,
        help: String? = null,
        visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> true },
        disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
        note: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
        /** For a key whose shown value depends on other keys of the string: decodes from the whole config instead. */
        decodeConfig: ((SettingsEnv, String?) -> String)? = null,
    ): SettingDef {
        fun valueIn(env: SettingsEnv, config: String?) = decodeConfig?.invoke(env, config) ?: decode(env, p.raw(config, key))
        fun changed(config: String, v: String): String = p.with(config, also(v, config) + (key to encode(v, p.raw(config, key))))
        return SettingDef(
            id = id, category = "graphics", group = group, label = label, hint = hint, help = help,
            tech = "${p.extra} › $key", level = level, control = control,
            vm = VmBinding(
                get = { env, vm -> valueIn(env, p.ofVm(vm)) },
                set = { _, vm, v -> p.toVm(vm, changed(p.ofVm(vm), v)) },
            ),
            game = GameBinding(
                keys = listOf(p.extra),
                read = { e ->
                    e.get(p.extra)?.takeIf { it.isNotEmpty() }?.let { valueIn(e.env, it) }
                        ?.takeIf { it != valueIn(e.env, p.ofContainer(e.container)) }
                },
                write = { e, v -> e.put(p.extra, p.gameWith(e) { changed(it, v) }) },
                // Back to the container's text for this key (absent there: absent here too).
                reset = { e ->
                    if (e.get(p.extra) != null) {
                        val theirs = p.raw(p.ofContainer(e.container), key)
                        e.put(p.extra, p.gameWith(e) { cfg -> p.with(cfg, mapOf(key to theirs)) })
                    }
                },
            ),
            visible = visible, disabled = disabled, note = note,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun on(raw: String?) = if (raw == "1") "1" else "0"
    private fun seg(vararg o: Pair<String, String>) = Control.Segmented(o.map { Opt(it.first, it.second) })
    private fun toggle(): (SettingsEnv, (String) -> String) -> Control = { _, _ -> Control.Toggle }

    private fun wrapperOf(values: (String) -> String) = values(ID_WRAPPER)
    /** DXVK or VEGAS: the classic DxvkConfigDialog. WineD3D has a dialog of its own. */
    private fun dxvkLike(values: (String) -> String) = wrapperOf(values).let { it.contains("dxvk") || it.contains("vegas") }
    private fun vegas(values: (String) -> String) = wrapperOf(values).contains("vegas")
    private fun plainDxvk(values: (String) -> String) = dxvkLike(values) && !vegas(values)
    private fun wineD3D(values: (String) -> String) = wrapperOf(values).isNotEmpty() && !dxvkLike(values)
    /** The DXVK build in use: the VEGAS build when the wrapper is VEGAS (both are the "version" key). */
    private fun dxvkBuild(values: (String) -> String) = values(if (vegas(values)) ID_VEGAS else ID_DXVK)
    /** The X11 game driver: most of the driver config only reaches a Wine game on X11. */
    private fun x11(env: SettingsEnv, values: (String) -> String) = !env.isLinuxEntry && !env.wayland(values)
    /** A Wine game on Wayland: the keys of the Wayland driver gear apply (never the Linux Steam client entry). */
    private fun waylandGame(env: SettingsEnv, values: (String) -> String) = !env.isLinuxEntry && env.wayland(values)
    /** VKD3D-Proton needs DXVK 2.x's DXGI; only the Mali "Wrapper + compat + bcn" driver may pair it with 1.x (#137). */
    private fun relaxDxvk(values: (String) -> String) = values(ID_GFX_DRIVER) == "wrapper-compat-bcn"

    /** A version list as a select. What is stored is always listed, so the field never reads blank. */
    private fun versions(
        list: List<String>,
        current: String,
        loaded: Boolean,
        label: (String) -> String = { it.ifEmpty { "Not set" } },
        missing: (String) -> String = { "${label(it)} (not installed)" },
        disabledReason: (String) -> String? = { null },
    ): Control.Select {
        val shown = list.distinct().map { Opt(it, label(it), disabledReason(it)) }
        return Control.Select(
            if (shown.any { it.value == current }) shown
            else shown + Opt(current, if (loaded) missing(current) else label(current), disabledReason(current)),
        )
    }

    // ── VEGAS stock configs: the file the game reads is the user's own copy once one exists ──────

    /** Where the classic VEGAS dialog keeps a stock config's edited copy: in the container, else the app's. */
    private fun sidecarDir(env: SettingsEnv): File {
        val root = if (env.scope == EditorScope.CONTAINER) env.vm?.container?.rootDir else null
        return if (root != null) File(File(root, "vegas"), "configs") else File(env.context.filesDir, "vegas-defaults/configs")
    }

    private fun sidecars(env: SettingsEnv, src: DXVKConfigDialog.StockSource): List<File> =
        listOfNotNull(src.tag?.takeIf { it.isNotBlank() }, src.verName?.removePrefix("vegas-")?.substringBefore(" ·"))
            .map { File(sidecarDir(env), it.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".user.conf") }
            .distinct()

    private fun stockLive(env: SettingsEnv, src: DXVKConfigDialog.StockSource): File =
        sidecars(env, src).firstOrNull { it.isFile } ?: src.file

    private fun stockOf(env: SettingsEnv, path: String): DXVKConfigDialog.StockSource? =
        if (path.isEmpty()) null
        else env.graphics.vegasStock.firstOrNull { s -> s.file.absolutePath == path || sidecars(env, s).any { it.absolutePath == path } }

    // ── Driver capabilities (the classic dialog's #132 gates) ───────────────────────────────────

    private fun caps(env: SettingsEnv, values: (String) -> String) = env.graphics.caps(values(ID_GFX_DRIVER))
    private fun imported(env: SettingsEnv, values: (String) -> String) = env.graphics.isImported(values(ID_GFX_DRIVER))
    /** A wrapper ICD that honours WRAPPER_BCN_ASTC (any imported ICD, as the classic dialog assumes). */
    private fun integratedBcn(env: SettingsEnv, values: (String) -> String): Boolean {
        val c = caps(env, values) ?: return false
        return (c.hasIcd && c.hasBcnLayer) || (imported(env, values) && c.hasIcd)
    }
    /** A wrapper that carries the BCn layer as an implicit overlay: its own settings block. */
    private fun bcnLayerSettings(env: SettingsEnv, values: (String) -> String): Boolean {
        val c = caps(env, values) ?: return false
        return c.hasBcnLayer && (imported(env, values) || !c.hasIcd)
    }
    private fun bcnLayerDriver(values: (String) -> String) = values(ID_GFX_DRIVER) in setOf("wrapper-bcn_layer", "wrapper-compat-bcn")
    private fun adrenoNote(env: SettingsEnv): String? = if (env.graphics.qualcomm) "No effect on Adreno, which decodes BCn itself." else null

    // ── DX wrapper config (DxvkConfigDialog, WineD3DConfigDialog) ────────────────────────────────

    private val ANISOTROPY_LABELS = listOf("Game default", "2×", "4×", "8×", "16×")
    private val LOD_BIAS_LABELS = listOf("Game default", "Auto (match the scaling)", "Sharper (-0.25)", "Sharper (-0.5)", "Sharper (-0.75)", "Sharpest (-1.0)")

    /** Rows of the DX wrapper config, shown under the DirectX translation choice. */
    fun dxDefs(): List<SettingDef> = listOf(
        // DXVK and VKD3D
        packedRow(
            DX_CFG, ID_DXVK, "version", "DXVK and VKD3D", "DXVK version", ADVANCED,
            hint = "DirectX 8 to 11 through Vulkan. Builds named async or gplasync can compile shaders in the background.",
            control = { env, values ->
                val g = env.graphics
                val withVkd3d = values(ID_VKD3D) != "None" && !relaxDxvk(values)
                // VKD3D-Proton needs DXVK 2.x: the 1.x builds are left out while VKD3D is on (#113).
                val fits = { v: String -> !withVkd3d || (DXVKConfigDialog.tryGetMajor(v)?.let { it >= 2 } ?: true) }
                versions(
                    g.dxvk.filter(fits), values(ID_DXVK), g.loaded,
                    disabledReason = { v -> if (v in g.dxvk && !fits(v)) "DXVK 1.x can't run VKD3D: pick a 2.x build, or set VKD3D to None." else null },
                )
            },
            // The async switches only exist for the builds that have them, as the classic OK writes them.
            also = { v, cfg -> asyncFor(v, cfg) },
            visible = { _, values -> plainDxvk(values) },
        ),
        packedRow(
            DX_CFG, ID_VEGAS, "version", "DXVK and VKD3D", "VEGAS version", ADVANCED,
            hint = "The installed VEGAS builds, and the one bundled with the app.",
            control = { env, values -> versions(env.graphics.vegas, values(ID_VEGAS), env.graphics.loaded) },
            also = { v, cfg -> asyncFor(v, cfg) },
            visible = { _, values -> vegas(values) },
            note = { env, values -> if (env.graphics.loaded && env.graphics.vegas.isEmpty()) "No VEGAS build installed: download one below." else null },
        ),
        packedRow(
            DX_CFG, ID_VKD3D, "vkd3dVersion", "DXVK and VKD3D", "VKD3D version", ADVANCED,
            hint = "DirectX 12 through Vulkan. None turns DirectX 12 off.",
            control = { env, values -> versions(env.graphics.vkd3d, values(ID_VKD3D), env.graphics.loaded) },
            decode = { _, r -> r?.ifEmpty { null } ?: "None" },
            visible = { _, values -> dxvkLike(values) },
        ),
        packedRow(
            DX_CFG, "graphics.dx.async", "async", "DXVK and VKD3D", "Async shaders", ADVANCED,
            hint = "Compiles shaders in the background: less stutter, some objects may pop in.",
            control = toggle(), decode = { _, r -> on(r) },
            visible = { _, values -> dxvkLike(values) && DXVKConfigDialog.getDXVKType(dxvkBuild(values)) != DXVKConfigDialog.DXVK_TYPE_NONE },
        ),
        packedRow(
            DX_CFG, "graphics.dx.asyncCache", "asyncCache", "DXVK and VKD3D", "Async shader cache", ADVANCED,
            hint = "Keeps the compiled shaders for the next start (gplasync builds).",
            control = toggle(), decode = { _, r -> on(r) },
            visible = { _, values -> dxvkLike(values) && DXVKConfigDialog.getDXVKType(dxvkBuild(values)) == DXVKConfigDialog.DXVK_TYPE_GPLASYNC },
        ),
        packedRow(
            DX_CFG, "graphics.dx.framerate", "framerate", "DXVK and VKD3D", "DXVK frame rate cap", ADVANCED,
            hint = "DXVK's own limit (DXVK_FRAME_RATE). Off leaves pacing to the frame rate limiter.",
            control = { env, _ ->
                Control.Select(env.graphics.framerates.map { n -> Opt(n, if (n == "0") "Off" else "$n FPS") })
            },
            // The classic dialog shows (and saves) its first entry, Off, for anything it doesn't list.
            decode = { env, r -> r?.takeIf { it in env.graphics.framerates } ?: "0" },
            visible = { _, values -> dxvkLike(values) },
        ),
        packedRow(
            DX_CFG, "graphics.dx.vkd3dLevel", "vkd3dLevel", "DXVK and VKD3D", "DirectX 12 feature level", EXPERT,
            hint = "The feature level VKD3D reports. Lower it only for a game that refuses to start.",
            control = { _, _ -> Control.Select(DXVKConfigDialog.VKD3D_FEATURE_LEVEL.map { Opt(it, it.replace('_', '.')) }) },
            decode = { _, r -> r?.takeIf { it in DXVKConfigDialog.VKD3D_FEATURE_LEVEL } ?: DXVKConfigDialog.VKD3D_FEATURE_LEVEL.first() },
            visible = { _, values -> dxvkLike(values) },
        ),

        // Texture filtering (DXVK and VEGAS, DirectX 9 to 11)
        packedRow(
            DX_CFG, "graphics.dx.anisotropy", "anisotropy", "Texture filtering", "Anisotropic filtering", ADVANCED,
            hint = "Sharper textures at an angle. DirectX 9 to 11 games.",
            control = { _, _ -> Control.Segmented(DXVKConfigDialog.ANISOTROPY_VALUES.mapIndexed { i, v -> Opt(v, ANISOTROPY_LABELS[i]) }) },
            decode = { _, r -> r?.takeIf { it in DXVKConfigDialog.ANISOTROPY_VALUES } ?: "0" },
            visible = { _, values -> dxvkLike(values) },
        ),
        packedRow(
            DX_CFG, "graphics.dx.lodBias", "lodBias", "Texture filtering", "Texture sharpness", ADVANCED,
            hint = "A negative mip bias: sharper textures, some shimmer. Auto follows the scaling the game starts with.",
            control = { _, _ -> Control.Select(DXVKConfigDialog.LOD_BIAS_VALUES.mapIndexed { i, v -> Opt(v, LOD_BIAS_LABELS[i]) }) },
            decode = { _, r -> r?.takeIf { it in DXVKConfigDialog.LOD_BIAS_VALUES } ?: "0" },
            visible = { _, values -> dxvkLike(values) },
        ),

        // DirectDraw (both dialogs)
        packedRow(
            DX_CFG, ID_DDRAW, "ddrawrapper", "DirectDraw", "DirectDraw wrapper", ADVANCED,
            hint = "For DirectDraw and Direct3D 7 games. D7VK runs them on Vulkan.",
            control = { env, values ->
                val ids = env.graphics.ddraw.map { StringUtils.parseIdentifier(it) to it }
                val current = values(ID_DDRAW)
                Control.Select(ids.map { Opt(it.first, it.second) } + if (ids.none { it.first == current }) listOf(Opt(current, current)) else emptyList())
            },
            decode = { _, r -> r?.ifEmpty { null } ?: Container.DEFAULT_DDRAWRAPPER },
        ),
        packedRow(
            DX_CFG, "graphics.dx.d7vkVersion", "d7vkVersion", "DirectDraw", "D7VK version", ADVANCED,
            control = { env, values ->
                versions(env.graphics.d7vk, values("graphics.dx.d7vkVersion"), env.graphics.loaded,
                    label = { if (it == DXVKConfigDialog.D7VK_BUNDLED) "Bundled with the app" else it })
            },
            decode = { _, r -> r?.ifEmpty { null } ?: DXVKConfigDialog.D7VK_BUNDLED },
            visible = { _, values -> dxvkLike(values) && values(ID_DDRAW) == "d7vk" },
        ),

        // VEGAS config file: which one the game reads. Its keys are edited in the VEGAS config editor below the rows.
        packedRow(
            DX_CFG, ID_VEGAS_CONFIG, "dxvkConfigFile", "VEGAS config", "Config file", ADVANCED,
            hint = "Which VEGAS config the game reads. A stock config stays untouched: the first edit makes your own copy.",
            control = { env, values ->
                val current = values(ID_VEGAS_CONFIG)
                val opts = mutableListOf(Opt("", "VEGAS built-in defaults (no file)"))
                env.graphics.vegasStock.forEach { s ->
                    val live = stockLive(env, s)
                    opts += Opt(live.absolutePath, "Stock · ${s.displayLabel()}" + if (live != s.file) " (your copy)" else "")
                }
                env.graphics.vegasCustom.forEach { opts += Opt(it, it) }
                val shown = opts.distinctBy { it.value }
                Control.Select(
                    options = if (shown.any { it.value == current }) shown
                              else shown + Opt(current, stockOf(env, current)?.let { "Stock · ${it.displayLabel()}" } ?: current),
                    customLabel = "Another file…",
                    customHint = "The full path of a .conf file, e.g. /storage/emulated/0/Download/dxvk.conf",
                    validateCustom = { if (it.trim().startsWith("/")) null else "Type the full path, starting with /." },
                )
            },
            visible = { _, values -> vegas(values) },
            note = { _, values ->
                val p = values(ID_VEGAS_CONFIG)
                if (p.isNotEmpty() && !File(p).isFile) "Not found: $p" else null
            },
        ),

        // WineD3D (WineD3DConfigDialog)
        packedRow(
            DX_CFG, "graphics.wined3d.csmt", "csmt", "WineD3D", "Command stream (CSMT)", ADVANCED,
            hint = "Runs WineD3D's rendering on its own thread. Usually faster.",
            control = toggle(),
            decode = { _, r -> if (r == "3") "1" else "0" },
            encode = { v, _ -> if (v == "1") "3" else "0" },
            visible = { _, values -> wineD3D(values) },
        ),
        packedRow(
            DX_CFG, "graphics.wined3d.renderer", "renderer", "WineD3D", "WineD3D renderer", ADVANCED,
            control = { _, _ -> seg("gl" to "OpenGL", "vulkan" to "Vulkan", "gdi" to "GDI (software)") },
            decode = { _, r -> r?.ifEmpty { null } ?: "gl" },
            visible = { _, values -> wineD3D(values) },
        ),
        packedRow(
            DX_CFG, "graphics.wined3d.gpuName", "gpuName", "WineD3D", "GPU the game sees", ADVANCED,
            hint = "The graphics card WineD3D reports to the game.",
            control = { env, values ->
                versions(env.graphics.wined3dGpus, values("graphics.wined3d.gpuName"), env.graphics.loaded, missing = { it.ifEmpty { "Not set" } })
            },
            visible = { _, values -> wineD3D(values) },
        ),
        packedRow(
            DX_CFG, "graphics.wined3d.videoMemory", "videoMemorySize", "WineD3D", "Video memory", ADVANCED,
            hint = "How much graphics memory WineD3D reports.",
            control = { env, values ->
                val opts = env.graphics.videoMemory.map { Opt(StringUtils.parseNumber(it), it) }
                val current = values("graphics.wined3d.videoMemory")
                Control.Select(opts + if (opts.none { it.value == current }) listOf(Opt(current, "$current MB")) else emptyList())
            },
            decode = { env, r -> r?.ifEmpty { null } ?: env.graphics.videoMemory.firstOrNull()?.let { StringUtils.parseNumber(it) } ?: "" },
            visible = { _, values -> wineD3D(values) },
        ),
        packedRow(
            DX_CFG, "graphics.wined3d.strictShaderMath", "strict_shader_math", "WineD3D", "Strict shader math", EXPERT,
            control = toggle(), decode = { _, r -> on(r) },
            visible = { _, values -> wineD3D(values) },
        ),
        packedRow(
            DX_CFG, "graphics.wined3d.offscreen", "OffscreenRenderingMode", "WineD3D", "Offscreen rendering", EXPERT,
            control = { _, _ -> seg("fbo" to "FBO", "backbuffer" to "Back buffer") },
            decode = { _, r -> r?.ifEmpty { null } ?: "fbo" },
            visible = { _, values -> wineD3D(values) },
        ),

        // The whole string, for the VEGAS config file editor (DeckGraphicsState). Never a row of its own.
        SettingDef(
            id = ID_DX_CONFIG, category = "graphics", group = "VEGAS config", label = "DX wrapper config", level = EXPERT,
            control = { _, _ -> Control.TextField() },
            vm = VmBinding(get = { _, vm -> vm.dxWrapperConfig }, set = { _, vm, v -> vm.dxWrapperConfig = v }),
            game = GameBinding(
                keys = listOf(DX_CFG.extra),
                read = { e -> e.get(DX_CFG.extra)?.takeIf { it.isNotEmpty() && it != (e.container.getDXWrapperConfig() ?: "") } },
                write = { e, v -> e.put(DX_CFG.extra, v.takeIf { it != (e.container.getDXWrapperConfig() ?: "") }) },
            ),
            visible = { _, _ -> false },
        ),
    )

    /** The async switches a build can't use are written off, as the classic dialog's OK does. */
    private fun asyncFor(version: String, config: String): Map<String, String?> {
        val type = DXVKConfigDialog.getDXVKType(version)
        val out = HashMap<String, String?>()
        if (type == DXVKConfigDialog.DXVK_TYPE_NONE && DX_CFG.raw(config, "async") == "1") out["async"] = "0"
        if (type != DXVKConfigDialog.DXVK_TYPE_GPLASYNC && DX_CFG.raw(config, "asyncCache") == "1") out["asyncCache"] = "0"
        return out
    }

    // ── X11 driver config (GraphicsDriverConfigDialog) ──────────────────────────────────────────

    private val TURNIP_TOKENS = listOf("forcecb", "nocb", "sysmem", "deck_emu")

    private fun tokensOf(raw: String?): List<String> = (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** The token list with [token] on or off: the known ones in the classic order, any other kept after them. */
    private fun tokensWith(raw: String?, token: String, on: Boolean): String {
        val set = tokensOf(raw).toMutableSet().apply { if (on) add(token) else remove(token) }
        return (TURNIP_TOKENS.filter { it in set } + set.filter { it !in TURNIP_TOKENS }).joinToString(",")
    }

    /**
     * A key of the X11 driver configuration. With [wayland] the row also shows on Wayland: the Wayland
     * driver gear (WaylandDriverSettingsDialog) edits the same keys, so one row serves both backends.
     */
    private fun gdcRow(
        id: String, key: String, group: String, label: String, level: SettingLevel,
        control: (SettingsEnv, (String) -> String) -> Control,
        decode: (SettingsEnv, String?) -> String,
        hint: String? = null,
        encode: (String, String?) -> String? = { v, _ -> v },
        also: (String, String) -> Map<String, String?> = { _, _ -> emptyMap() },
        visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> true },
        disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
        note: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
        wayland: Boolean = false,
    ) = packedRow(
        DRIVER_CFG, id, key, group, label, level, control, decode, encode, also, hint, null,
        visible = { env, values -> (x11(env, values) || (wayland && waylandGame(env, values))) && visible(env, values) },
        disabled = disabled, note = note,
    )

    private fun gdcToggle(
        id: String, key: String, group: String, label: String, level: SettingLevel,
        hint: String? = null,
        default: Boolean = false,
        visible: (SettingsEnv, (String) -> String) -> Boolean = { _, _ -> true },
        note: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
        wayland: Boolean = false,
    ) = gdcRow(
        id, key, group, label, level, toggle(),
        decode = { _, r -> if (r == null) (if (default) "1" else "0") else on(r) },
        hint = hint, visible = visible, note = note, wayland = wayland,
    )

    private fun tokenRow(token: String, label: String, hint: String?, disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null }) = gdcRow(
        "graphics.gdc.tu.$token", "turnipTokens", "Turnip", label, EXPERT, toggle(),
        decode = { _, r -> if (token in tokensOf(r)) "1" else "0" },
        encode = { v, raw -> tokensWith(raw, token, v == "1") },
        hint = hint, disabled = disabled,
    )

    /** Rows of the X11 driver config, shown under the graphics driver choice. */
    fun driverDefs(): List<SettingDef> = listOf(
        gdcRow(
            ID_DRIVER_VERSION, "version", "GPU driver", "Driver version", ADVANCED,
            hint = "The Turnip or Adreno driver the wrapper loads. Import more in Contents › GPU drivers.",
            control = { env, values ->
                val g = env.graphics
                versions(
                    g.drivers + g.driversOther, values(ID_DRIVER_VERSION), g.driversLoaded,
                    label = { v ->
                        when {
                            v.isEmpty() -> "Not set (System)"
                            v in g.driversOther -> "$v (may not run on this GPU)"
                            else -> v
                        }
                    },
                )
            },
            decode = { _, r -> r ?: "" },
            // A new driver has other extensions: the classic dialog forgets the hidden ones with it.
            also = { v, cfg -> if (v != (DRIVER_CFG.raw(cfg, "version") ?: "")) mapOf("blacklistedExtensions" to "") else emptyMap() },
            note = { env, values ->
                val probe = env.graphics.extensions(values(ID_DRIVER_VERSION))
                when {
                    probe?.fellBack == true -> "This driver didn't load on this GPU: the system driver answered the check instead."
                    probe?.custom == true -> "A Qualcomm (Adreno) driver: it loads when the game starts, so its extensions aren't listed here."
                    else -> null
                }
            },
        ),
        gdcRow(
            "graphics.gdc.vulkanVersion", "vulkanVersion", "GPU driver", "Vulkan version", ADVANCED,
            hint = "The Vulkan version the driver reports to games.",
            control = { env, _ -> Control.Segmented(env.graphics.vulkanVersions.map { Opt(it, it) }) },
            decode = { _, r -> r?.ifEmpty { null } ?: "1.4" },
        ),
        gdcRow(
            "graphics.gdc.extensions", "blacklistedExtensions", "GPU driver", "Vulkan extensions", EXPERT,
            hint = "Hide extensions a game trips over. Changing the driver version shows them all again.",
            control = { env, values ->
                val probe = env.graphics.extensions(values(ID_DRIVER_VERSION))
                Control.Checklist(
                    items = probe?.list,
                    title = "Vulkan extensions",
                    waiting = "Reading the driver's extensions…",
                    empty = "None listed",
                )
            },
            decode = { _, r -> (r ?: "").split(",").filter { it.isNotEmpty() }.joinToString(",") },
            disabled = { env, values ->
                val probe = env.graphics.extensions(values(ID_DRIVER_VERSION))
                if (values(ID_DRIVER_VERSION).isEmpty()) "Pick a driver version first."
                else if (probe?.custom == true) "A Qualcomm driver's extensions are only known once the game starts."
                else null
            },
        ),
        gdcRow(
            "graphics.gdc.gpuName", "gpuName", "GPU driver", "GPU name games see", ADVANCED,
            hint = "Makes DirectX games see another graphics card. Device keeps the real one.",
            control = { env, values ->
                versions(env.graphics.gpuNames, values("graphics.gdc.gpuName"), env.graphics.loaded,
                    label = { if (it == GpuSpoof.DEVICE) "Device (the real GPU)" else it }, missing = { it })
            },
            decode = { _, r -> r?.ifEmpty { null } ?: GpuSpoof.DEVICE },
            note = { env, values -> runCatching { GpuSpoof.vendorWarning(env.context, values("graphics.gdc.gpuName")) }.getOrNull() },
            wayland = true,
        ),
        gdcRow(
            "graphics.gdc.maxDeviceMemory", "maxDeviceMemory", "GPU driver", "GPU memory games see", ADVANCED,
            hint = "What DirectX games are told the GPU's memory is (dxgi.maxDeviceMemory).",
            control = { env, values ->
                val opts = env.graphics.deviceMemory.map { e -> StringUtils.parseNumber(e).let { Opt(it, if (it == "0") "Default" else e) } }
                val current = values("graphics.gdc.maxDeviceMemory")
                Control.Select(opts + if (opts.none { it.value == current }) listOf(Opt(current, "$current MB")) else emptyList())
            },
            decode = { _, r -> r?.ifEmpty { null } ?: "0" },
            wayland = true,
        ),
        gdcRow(
            "graphics.gdc.presentMode", "presentMode", "Driver tuning", "Driver present mode", EXPERT,
            hint = "How the wrapper hands frames over. Not the screen renderer's setting.",
            control = { env, values ->
                if (!waylandGame(env, values)) Control.Segmented(env.graphics.presentModes.map { Opt(it, it.replaceFirstChar(Char::uppercase)) })
                else {
                    // Wayland offers mailbox and fifo only; a stored X11-only mode stays listed so it is kept for X11.
                    val stored = values("graphics.gdc.presentMode")
                    Control.Segmented(
                        WAYLAND_PRESENT_MODES.map { Opt(it, it.replaceFirstChar(Char::uppercase)) } +
                            if (stored in WAYLAND_PRESENT_MODES) emptyList() else listOf(Opt(stored, "${stored.replaceFirstChar(Char::uppercase)} (X11 only)")),
                    )
                }
            },
            decode = { _, r -> r?.ifEmpty { null } ?: "mailbox" },
            note = { env, values ->
                if (waylandGame(env, values)) "Wayland offers mailbox and fifo only: immediate needs tearing, which the compositor doesn't allow." else null
            },
            wayland = true,
        ),
        gdcRow(
            "graphics.gdc.resourceType", "resourceType", "Driver tuning", "Resource type", EXPERT,
            control = { env, _ -> Control.Segmented(env.graphics.resourceTypes.map { Opt(it, if (it == "auto") "Auto" else it.uppercase()) }) },
            decode = { _, r -> r?.ifEmpty { null } ?: "auto" },
        ),
        gdcToggle("graphics.gdc.syncFrame", "syncFrame", "Driver tuning", "Sync every frame", EXPERT,
            hint = "Waits for each frame to finish. Fixes flicker in a few games, costs speed."),
        gdcToggle("graphics.gdc.disablePresentWait", "disablePresentWait", "Driver tuning", "Disable present wait", EXPERT),
        gdcToggle("graphics.gdc.fdDevFeatures", "fdDevFeatures", "Driver tuning", "OneUI / HyperOS fix", ADVANCED,
            hint = "For Samsung and Xiaomi phones whose games show corrupt textures. Leave off otherwise.", wayland = true),

        // BCn textures
        gdcRow(
            ID_BCN, "bcnEmulation", "BCn textures", "BCn texture emulation", ADVANCED,
            hint = "Decodes the compressed textures Windows games use, for GPUs that can't.",
            control = { env, _ -> Control.Segmented(env.graphics.bcnEmulations.map { Opt(it, it.replaceFirstChar(Char::uppercase)) }) },
            decode = { _, r -> r?.ifEmpty { null } ?: "auto" },
            note = { env, _ ->
                (if (env.graphics.gpuModel.isNotEmpty()) "GPU: ${env.graphics.gpuModel}. " else "") +
                    (if (env.graphics.qualcomm) "Adreno decodes BCn itself: these apply to Mali and other GPUs." else "BCn and compat layers apply to Mali and other non-Qualcomm GPUs.")
            },
        ),
        gdcRow(
            ID_BCN_TYPE, "bcnEmulationType", "BCn textures", "Emulation method", EXPERT,
            control = { env, _ -> Control.Segmented(env.graphics.bcnTypes.map { Opt(it, it.replaceFirstChar(Char::uppercase)) }) },
            decode = { _, r -> r?.ifEmpty { null } ?: "software" },
        ),
        gdcRow(
            "graphics.gdc.bcnEmulationCache", "bcnEmulationCache", "BCn textures", "Cache decoded textures", EXPERT,
            control = { _, _ -> seg("0" to "Off", "1" to "On") },
            decode = { _, r -> on(r) },
        ),
        gdcToggle("graphics.gdc.bcnEmulationAstc", "bcnEmulationAstc", "BCn textures", "BCn to ASTC", EXPERT,
            visible = { env, values -> integratedBcn(env, values) }, note = { env, _ -> adrenoNote(env) }),
        gdcToggle("graphics.gdc.bcnTranscodeAstc", "bcnTranscodeAstc", "BCn textures", "Transcode BCn to ASTC", EXPERT,
            hint = "With the compute method: the decoder writes ASTC instead.",
            visible = { env, values ->
                bcnLayerSettings(env, values) ||
                    (!bcnLayerDriver(values) && values(ID_BCN_TYPE) == "compute" && values(ID_BCN) in setOf("auto", "full"))
            },
            note = { env, _ -> adrenoNote(env) }),
        gdcToggle("graphics.gdc.bcnLayerAuto", "bcnLayerAuto", "BCn layer", "Force decode on every GPU", EXPERT, default = true,
            visible = { env, values -> bcnLayerSettings(env, values) }, note = { env, _ -> adrenoNote(env) }),
        gdcToggle("graphics.gdc.bcnTranscodeEtc2", "bcnTranscodeEtc2", "BCn layer", "Transcode BCn to ETC2", EXPERT,
            visible = { env, values -> bcnLayerSettings(env, values) }),
        gdcToggle("graphics.gdc.bcnImageView", "bcnImageView", "BCn layer", "Storage image path", EXPERT, default = true,
            visible = { env, values -> bcnLayerSettings(env, values) }),
        gdcToggle("graphics.gdc.bcnDebugLog", "bcnDebugLog", "BCn layer", "BCn layer debug log", EXPERT,
            visible = { env, values -> bcnLayerSettings(env, values) }),
        gdcToggle("graphics.gdc.compatUseGamenative", "compatUseGamenative", "BCn layer", "Use the GameNative engine (DirectX 12)", EXPERT,
            hint = "Needs a Valhall Mali GPU.",
            visible = { env, values -> bcnLayerSettings(env, values) && values(ID_GFX_DRIVER) == "wrapper-compat-bcn" }),
        gdcToggle("graphics.gdc.bcnCompatSparse", "bcnCompatSparse", "BCn layer", "Emulate DirectX 12 sparse resources", EXPERT,
            visible = { env, values -> bcnLayerSettings(env, values) && values(ID_GFX_DRIVER) == "wrapper-compat-bcn" }),

        // Turnip (TU_DEBUG)
        gdcRow(
            ID_GMEM, "turnipGmem", "Turnip", "GMEM tiled rendering", ADVANCED,
            hint = "Auto uses it on Adreno 710, 720 and 722 only, and not with a driver built for them (such as " +
                "710-720-Test), which keeps sysmem. Change it only if a game misbehaves.",
            control = { _, _ -> seg("auto" to "Auto", "on" to "Force on", "off" to "Force off") },
            decode = { _, r -> r?.takeIf { it == "on" || it == "off" } ?: "auto" },
            // Force on overrides sysmem, so the classic dialog doesn't keep that token with it.
            also = { v, cfg ->
                val tokens = DRIVER_CFG.raw(cfg, "turnipTokens")
                if (v == "on" && "sysmem" in tokensOf(tokens)) mapOf("turnipTokens" to tokensWith(tokens, "sysmem", false)) else emptyMap()
            },
            // Wayland launches compose TU_DEBUG the same way (applyTurnipTuDebug), and the gear has it too.
            wayland = true,
        ),
        tokenRow("forcecb", "Force concurrent binning (forcecb)", null),
        tokenRow("nocb", "No concurrent binning (nocb)", null),
        tokenRow("sysmem", "Force sysmem rendering (sysmem)", "Bypasses GMEM.",
            disabled = { _, values -> if (values(ID_GMEM) == "on") "GMEM Force on overrides it." else null }),
        tokenRow("deck_emu", "Report a Steam Deck (deck_emu)", "Banners-Turnip drivers only; stock Turnip ignores it."),
    )

    // ── Wayland game driver and the Mali (PanVK) switches (WaylandDriverSettingsDialog) ──────────

    const val ID_WL_GAME_DRIVER = "graphics.wl.gameDriver"
    private const val ID_MALI_MODE = "graphics.wl.maliMode"
    private const val ID_MALI_SUBMIT = "graphics.wl.maliNoSubmitWaits"
    private const val ID_COMPOSITOR = "graphics.compositorDriver"

    /** Mali mode as the launch decides it: the stored switch, else on for a PanVK driver (MaliPanvk.modeOn). */
    private fun maliModeOn(env: SettingsEnv, config: String?): Boolean = when (DRIVER_CFG.raw(config, MaliPanvk.KEY_MODE)) {
        "1" -> true
        "0" -> false
        else -> env.graphics.looksLikePanvk(DRIVER_CFG.raw(config, "version") ?: "")
    }

    /** A Mali (PanVK) key of the driver config: Wayland, a Wine game, an Arm Mali GPU. [modeOnly] rows need Mali mode on. */
    private fun maliRow(
        id: String, key: String, label: String, level: SettingLevel,
        control: (SettingsEnv, (String) -> String) -> Control,
        decode: (SettingsEnv, String?) -> String = { _, r -> r ?: "" },
        decodeConfig: ((SettingsEnv, String?) -> String)? = null,
        encode: (String, String?) -> String? = { v, _ -> v },
        also: (String, String) -> Map<String, String?> = { _, _ -> emptyMap() },
        hint: String? = null,
        help: String? = null,
        modeOnly: Boolean = true,
        disabled: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
        note: (SettingsEnv, (String) -> String) -> String? = { _, _ -> null },
    ) = packedRow(
        DRIVER_CFG, id, key, if (level == ADVANCED) "Mali (PanVK)" else "Mali (PanVK) advanced", label, level, control,
        decode, encode, also, hint, help,
        visible = { env, values -> waylandGame(env, values) && env.graphics.mali && (!modeOnly || values(ID_MALI_MODE) == "1") },
        disabled = disabled, note = note, decodeConfig = decodeConfig,
    )

    /** A PANVK_*_MB field. Empty is Auto: stored empty and not exported, as the classic gear stores it. */
    private fun mbRow(id: String, key: String, label: String, hint: String, min: Int, max: Int) = maliRow(
        id, key, label, EXPERT,
        control = { _, _ ->
            Control.TextField("Auto / Default") { t ->
                if (t.isBlank() || MaliPanvk.validMb(t, min, max) != null) null else "Out of range ($min to $max): not used."
            }
        },
        encode = { v, _ -> MaliPanvk.validMb(v, min, max) ?: "" },
        hint = hint, modeOnly = false,
    )

    /** Rows of the Wayland driver gear, shown under the compositor driver on Wayland. */
    fun waylandDefs(): List<SettingDef> = listOf(
        SettingDef(
            id = ID_WL_GAME_DRIVER, category = "graphics", group = "GPU driver", level = ADVANCED,
            label = "Wayland game driver", hint = "The Vulkan driver the game itself renders on with Wayland.",
            help = WaylandGameDriver.HELP_TEXT + "\n\n" + WAYLAND_DRIVER_SETTINGS_HELP, tech = "waylandGameDriver",
            control = { env, values ->
                val g = env.graphics
                val current = values(ID_WL_GAME_DRIVER)
                val opts = g.waylandDrivers.map { Opt(it, g.waylandLabel(it)) }
                // What is stored is always listed (an import that is gone says so; launch uses Auto for it).
                Control.Select(if (opts.any { it.value == current }) opts else opts + Opt(current, g.waylandLabel(current)))
            },
            vm = VmBinding(
                get = { _, vm -> vm.waylandGameDriver.ifEmpty { Container.WAYLAND_GAME_DRIVER_AUTO } },
                set = { _, vm, v -> vm.waylandGameDriver = v },
            ),
            // Absent: the game follows its container's choice (WaylandGameDriver.effectiveChoice).
            game = GameBinding(
                keys = listOf("waylandGameDriver"),
                read = { e -> e.get("waylandGameDriver")?.takeIf { it.isNotEmpty() } },
                write = { e, v -> e.put("waylandGameDriver", v) },
            ),
            visible = { env, values -> waylandGame(env, values) },
            note = { env, values -> env.graphics.waylandDriveNote(values(ID_WL_GAME_DRIVER), values(ID_COMPOSITOR)) },
        ),

        maliRow(
            ID_MALI_MODE, MaliPanvk.KEY_MODE, "Mali (PanVK) mode", ADVANCED,
            hint = "PanVK tweaks in the Wayland adapter: hides present_id, present_wait and dynamic_rendering, plus two sync workarounds. On by default with a PanVK driver.",
            help = "For Arm Mali GPUs. Until it is switched, it follows the driver: on for a PanVK driver, off otherwise.",
            control = toggle(),
            decodeConfig = { env, cfg -> if (maliModeOn(env, cfg)) "1" else "0" },
            // As the classic gear: on sets BCn emulation to None, off gives back the stored choice.
            also = { v, cfg -> mapOf("bcnEmulation" to if (v == "1") "none" else (DRIVER_CFG.raw(cfg, "bcnEmulation")?.ifEmpty { null } ?: "auto")) },
            modeOnly = false,
        ),
        maliRow(
            "graphics.wl.bcnEmulation", "bcnEmulation", "BCn texture emulation", ADVANCED,
            control = { env, _ -> Control.Segmented(env.graphics.bcnEmulations.map { Opt(it, it.replaceFirstChar(Char::uppercase)) }) },
            // With no stored switch a PanVK driver runs with None, which is what the launch applies.
            decodeConfig = { env, cfg ->
                if (DRIVER_CFG.raw(cfg, MaliPanvk.KEY_MODE).isNullOrEmpty() && maliModeOn(env, cfg)) "none"
                else DRIVER_CFG.raw(cfg, "bcnEmulation")?.ifEmpty { null } ?: "auto"
            },
            // A pick stores the mode switch too, so the launch keeps this choice.
            also = { _, cfg -> if (DRIVER_CFG.raw(cfg, MaliPanvk.KEY_MODE).isNullOrEmpty()) mapOf(MaliPanvk.KEY_MODE to "1") else emptyMap() },
            note = { _, values ->
                if (values("graphics.wl.bcnEmulation") == "none") null
                else "Converting BCn textures to ASTC can freeze or reboot the phone with this driver; only try it if a game shows broken or missing textures."
            },
        ),
        maliRow(
            "graphics.wl.maliHideExts", MaliPanvk.KEY_HIDE_EXTS, "Hide present-wait / dynamic-rendering extensions", EXPERT,
            control = toggle(), decode = { _, r -> if (r == "0") "0" else "1" },
        ),
        maliRow(
            ID_MALI_SUBMIT, MaliPanvk.KEY_NO_SUBMIT_WAITS, "Skip GPU submit waits", EXPERT,
            hint = "For DXVK games: turn off if a DirectX 12 game misbehaves or you see tearing.",
            control = toggle(), decode = { _, r -> if (r == "0") "0" else "1" },
        ),
        maliRow(
            "graphics.wl.maliNoAcquireSignal", MaliPanvk.KEY_NO_ACQUIRE_SIGNAL, "Skip frame-acquire signal", EXPERT,
            control = toggle(), decode = { _, r -> if (r == "0") "0" else "1" },
            disabled = { _, values -> if (values(ID_MALI_SUBMIT) == "1") null else "Needs Skip GPU submit waits on." },
        ),
        mbRow("graphics.wl.panvkHeap", MaliPanvk.KEY_HEAP, "PANVK_HEAP_MB", "Default: a quarter of RAM, 1024 to 3072 MB.", MaliPanvk.HEAP_MIN, MaliPanvk.HEAP_MAX),
        mbRow("graphics.wl.panvkTilerHeap", MaliPanvk.KEY_TILER_HEAP, "PANVK_TILER_HEAP_MB", "Default: 512 MB.", MaliPanvk.TILER_HEAP_MIN, MaliPanvk.TILER_HEAP_MAX),
        mbRow("graphics.wl.panvkPolyHeap", MaliPanvk.KEY_POLY_HEAP, "PANVK_POLY_HEAP_MB", "Default: 16 MB.", MaliPanvk.POLY_HEAP_MIN, MaliPanvk.POLY_HEAP_MAX),
        maliRow(
            "graphics.wl.panvkAtomStride", MaliPanvk.KEY_ATOM_STRIDE, "PANVK_ATOM_STRIDE", EXPERT,
            hint = "Only if auto-detection fails ([KFAULT] in the log).",
            control = { _, _ -> Control.Segmented(listOf(Opt("", "Auto")) + MaliPanvk.ATOM_STRIDES.map { Opt(it, it) }) },
            decode = { _, r -> r?.takeIf { it in MaliPanvk.ATOM_STRIDES } ?: "" },
            modeOnly = false,
        ),
        maliRow(
            "graphics.wl.panvkTrace", MaliPanvk.KEY_TRACE, "PANVK_TRACE (driver tracing)", EXPERT,
            control = toggle(), decode = { _, r -> on(r) },
            // Off is stored empty, as the classic gear stores it.
            encode = { v, _ -> if (v == "1") "1" else "" },
            modeOnly = false,
        ),
    )
}

// ── Lists and probes ────────────────────────────────────────────────────────────────────────────

/** What a driver's in-app probe found: its extensions, or that it is a Qualcomm blob never probed in-process. */
internal class ExtensionsProbe(val list: List<String>, val custom: Boolean, val fellBack: Boolean)

/**
 * The lists the Graphics rows offer, read the way the classic dialogs read them: installed DXVK, VKD3D,
 * D7VK and VEGAS builds (ContentsManager), stock VEGAS configs, the GPU names, the driver versions this
 * GPU supports (a native probe, serialized on graphicsProbeMutex like every driver probe), a driver's
 * Vulkan extensions and a wrapper's capabilities. Nothing is read until the Graphics page asks.
 */
internal class GraphicsLists(private val context: Context) {
    private val res = context.resources
    val framerates: List<String> by lazy { res.getStringArray(R.array.dxvk_framerate_entries).map { StringUtils.parseNumber(it) } }
    val ddraw: List<String> by lazy { res.getStringArray(R.array.ddrawrapper_entries).toList() }
    val videoMemory: List<String> by lazy { res.getStringArray(R.array.video_memory_size_entries).toList() }
    val deviceMemory: List<String> by lazy { res.getStringArray(R.array.device_memory_entries).toList() }
    val vulkanVersions: List<String> by lazy { res.getStringArray(R.array.vulkan_version_entries).toList() }
    val presentModes: List<String> by lazy { res.getStringArray(R.array.present_mode_entries).toList() }
    val resourceTypes: List<String> by lazy { res.getStringArray(R.array.resource_type_entries).toList() }
    val bcnEmulations: List<String> by lazy { res.getStringArray(R.array.bcn_emulation_entries).toList() }
    val bcnTypes: List<String> by lazy { res.getStringArray(R.array.bcn_emulation_type_entries).toList() }

    var loaded by mutableStateOf(false)
        private set
    var dxvk by mutableStateOf<List<String>>(emptyList())
        private set
    var vegas by mutableStateOf<List<String>>(emptyList())
        private set
    var vkd3d by mutableStateOf(listOf("None"))
        private set
    var d7vk by mutableStateOf(listOf(DXVKConfigDialog.D7VK_BUNDLED))
        private set
    var vegasStock by mutableStateOf<List<DXVKConfigDialog.StockSource>>(emptyList())
        private set
    var vegasCustom by mutableStateOf<List<String>>(emptyList())
        private set
    var wined3dGpus by mutableStateOf<List<String>>(emptyList())
        private set
    var gpuNames by mutableStateOf(listOf(GpuSpoof.DEVICE))
        private set
    /** Bundled driver versions this GPU supports, then the imported ones. */
    var drivers by mutableStateOf<List<String>>(emptyList())
        private set
    /** Bundled versions this GPU may not run (the classic dialog's "Show incompatible drivers"). */
    var driversOther by mutableStateOf<List<String>>(emptyList())
        private set
    var driversLoaded by mutableStateOf(false)
        private set
    var qualcomm by mutableStateOf(false)
        private set
    var gpuModel by mutableStateOf("")
        private set
    /** An Arm Mali GPU: the Wayland driver gear's Mali (PanVK) switches apply. */
    var mali by mutableStateOf(false)
        private set
    /** The Wayland game driver choices (adapter, Auto, the bundled Turnips, each imported Linux ICD). */
    var waylandDrivers by mutableStateOf<List<String>>(emptyList())
        private set
    private var waylandLabels by mutableStateOf<Map<String, String>>(emptyMap())
    private var waylandAuto: String? = null
    // File reads behind the notes and the Mali mode default, cached per editor (main thread only).
    private val panvkByDriver = HashMap<String, Boolean>()
    private val driveNotes = HashMap<String, String?>()

    private val probes = mutableStateMapOf<String, ExtensionsProbe>()
    private val capsById = mutableStateMapOf<String, WrapperManager.WrapperCaps>()
    private val importedById = mutableStateMapOf<String, Boolean>()
    // What is being read right now (main thread only), so a second ask doesn't read it twice.
    private val pending = HashSet<String>()
    private var done = false

    fun extensions(version: String): ExtensionsProbe? = probes[version]
    fun caps(driver: String): WrapperManager.WrapperCaps? = capsById[driver]
    fun isImported(driver: String): Boolean = importedById[driver] == true

    /** A Wayland game driver choice as the classic pickers name it ("Auto (by GPU: …)", an import that is gone…). */
    fun waylandLabel(choice: String): String =
        waylandLabels[choice] ?: runCatching { WaylandGameDriver.optionLabel(context, choice, waylandAuto) }.getOrDefault(choice)

    /** Whether a driver id names a PanVK build (MaliPanvk.looksLikePanvk), for Mali mode's default. */
    fun looksLikePanvk(driver: String): Boolean =
        panvkByDriver.getOrPut(driver) { runCatching { MaliPanvk.looksLikePanvk(context, driver) }.getOrDefault(false) }

    /** What the Wayland game driver [choice] means with the compositor driver [compositor], as the classic editors say it. */
    fun waylandDriveNote(choice: String, compositor: String): String? = driveNotes.getOrPut("$choice|$compositor") {
        val blob = runCatching { WaylandAdapter.isProprietaryBlob(context, compositor) }.getOrDefault(false)
        val adapter = choice == Container.WAYLAND_GAME_DRIVER_ADAPTER
        when {
            blob -> "Qualcomm's own driver can't put Wayland frames on screen, so the screen uses a bundled Turnip" +
                if (adapter) " and the game renders on the bundled Wayland Turnip (the adapter needs a Turnip driver)." else "."
            runCatching { WaylandGameDriver.adapterActive(context, choice, compositor) }.getOrDefault(false) ->
                "The game renders on the graphics driver above, through the Wayland adapter."
            else -> null
        }
    }

    /** Reads every list once per editor. */
    suspend fun load(arm64ec: Boolean) {
        if (done || !pending.add("lists")) return
        try {
            reloadContents(arm64ec)
            val names = withContext(Dispatchers.IO) {
                runCatching { GpuSpoof.names(context).toList() }.getOrDefault(listOf(GpuSpoof.DEVICE)) to
                    runCatching { WineD3DConfig.loadGpuNames(context).toList() }.getOrDefault(emptyList())
            }
            gpuNames = names.first
            wined3dGpus = names.second
            reloadDrivers()
            reloadWaylandDrivers()
            done = true
        } finally {
            pending.remove("lists")
        }
    }

    /** The installed DXVK, VKD3D, D7VK and VEGAS builds and the stock VEGAS configs, e.g. after a download. */
    suspend fun reloadContents(arm64ec: Boolean) {
        class Contents(val dxvk: List<String>, val vegas: List<String>, val vkd3d: List<String>, val d7vk: List<String>,
                       val stock: List<DXVKConfigDialog.StockSource>, val custom: List<String>)
        val c = withContext(Dispatchers.IO) {
            val cm = ContentsManager(context)
            runCatching { cm.syncContents() }
            Contents(
                dxvk = runCatching { DXVKConfigDialog.loadDxvkVersionList(context, cm, arm64ec).toList() }.getOrDefault(emptyList()),
                vegas = runCatching { DXVKConfigDialog.loadVegasVersionList(context, cm).toList() }.getOrDefault(emptyList()),
                vkd3d = runCatching { DXVKConfigDialog.loadVkd3dVersionList(context, cm).toList() }.getOrDefault(listOf("None")),
                d7vk = runCatching { DXVKConfigDialog.loadD7vkVersionList(context, cm).toList() }.getOrDefault(listOf(DXVKConfigDialog.D7VK_BUNDLED)),
                stock = runCatching { DXVKConfigDialog.loadVegasStockSources(context, cm).toList() }.getOrDefault(emptyList()),
                custom = runCatching { DXVKConfigDialog.loadVegasConfigSourceList(context).filter { it != "None" } }.getOrDefault(emptyList()),
            )
        }
        dxvk = c.dxvk.distinct()
        vegas = c.vegas.distinct()
        vkd3d = c.vkd3d.distinct()
        d7vk = c.d7vk.distinct()
        vegasStock = c.stock
        vegasCustom = c.custom
        loaded = true
    }

    /** The driver versions (supported bundled + imported) and the GPU facts, e.g. after the wrapper manager. */
    suspend fun reloadDrivers() {
        val bundled = res.getStringArray(R.array.wrapper_graphics_driver_version_entries).toList()
        class Drivers(val supported: List<String>, val installed: List<String>, val qualcomm: Boolean, val model: String, val mali: Boolean)
        val d = withContext(Dispatchers.IO) {
            val installed = runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers().toList() }.getOrDefault(emptyList())
            graphicsProbeMutex.withLock {
                Drivers(
                    supported = bundled.filter { runCatching { GPUInformation.isDriverSupported(it, context) }.getOrDefault(false) },
                    installed = installed,
                    qualcomm = runCatching { GPUInformation.getVendorID(null, null) == 0x5143 }.getOrDefault(false),
                    model = runCatching { GPUInformation.extractModelName(GPUInformation.getRenderer(null, context)) ?: "" }.getOrDefault(""),
                    mali = runCatching { MaliPanvk.isMaliGpu() }.getOrDefault(false),
                )
            }
        }
        drivers = (d.supported + d.installed).distinct()
        driversOther = bundled.filter { it !in d.supported }
        qualcomm = d.qualcomm
        gpuModel = d.model
        mali = d.mali
        driversLoaded = true
        panvkByDriver.clear()
        driveNotes.clear()
    }

    /**
     * The Wayland game driver choices and their labels. "Auto" names the bundled Turnip it picks on this
     * GPU, a native probe that runs once (waylandAutoVariant, serialized on graphicsProbeMutex).
     */
    suspend fun reloadWaylandDrivers() {
        val auto = runCatching { waylandAutoVariant(context) }.getOrNull()
        val found = withContext(Dispatchers.IO) {
            val values = runCatching { WaylandGameDriver.optionValues(context).toList() }.getOrDefault(emptyList())
            values to values.associateWith { v -> runCatching { WaylandGameDriver.optionLabel(context, v, auto) }.getOrDefault(v) }
        }
        waylandAuto = auto
        waylandDrivers = found.first
        waylandLabels = found.second
    }

    /**
     * A driver's Vulkan extensions, as the classic dialog lists them. A Qualcomm blob (an imported
     * driver that is not a Mesa libvulkan_* build) is never loaded in-process: it can abort the app.
     */
    suspend fun probeExtensions(version: String) {
        if (version.isEmpty() || probes.containsKey(version) || !pending.add("ext:$version")) return
        try {
            probes[version] = probe(version)
        } finally {
            pending.remove("ext:$version")
        }
    }

    private suspend fun probe(version: String): ExtensionsProbe =
        withContext(Dispatchers.IO) {
            graphicsProbeMutex.withLock {
                val unsafe = runCatching {
                    val mgr = AdrenotoolsManager(context)
                    mgr.enumarateInstalledDrivers().any { it.equals(version, ignoreCase = true) } &&
                        !(mgr.getLibraryName(version) ?: "").startsWith("libvulkan", ignoreCase = true)
                }.getOrDefault(true)
                if (unsafe) ExtensionsProbe(emptyList(), custom = true, fellBack = false)
                else {
                    val exts = runCatching { GPUInformation.enumerateExtensions(version, context)?.toList() ?: emptyList() }.getOrDefault(emptyList())
                    ExtensionsProbe(exts.distinct(), custom = exts.isEmpty(), fellBack = runCatching { GPUInformation.driverLoadedFellBack() }.getOrDefault(false))
                }
            }
        }

    /** A wrapper's capabilities (an import's are read from its .meta), for the BCn rows. */
    suspend fun loadCaps(driver: String) {
        if (driver.isEmpty() || capsById.containsKey(driver) || !pending.add("caps:$driver")) return
        try {
            val found = withContext(Dispatchers.IO) {
                runCatching { WrapperManager(context).let { it.capsFor(driver) to it.isImported(driver) } }.getOrNull()
            } ?: return
            capsById[driver] = found.first
            importedById[driver] = found.second
        } finally {
            pending.remove("caps:$driver")
        }
    }

    /** A wrapper was imported or deleted: its capabilities are read again. */
    fun forgetCaps() {
        capsById.clear()
        importedById.clear()
    }
}

// ── Downloads and tools beside the rows ────────────────────────────────────────────────────────

/**
 * The Graphics page's actions for a Wine game, a container or All containers: download DXVK, VKD3D,
 * D7VK and VEGAS builds and stock VEGAS configs (the classic download sheets), manage graphics
 * wrappers, and edit the VEGAS config file. Nothing downloads until its row is pressed. The VEGAS
 * config editor is the classic one; its OK lands in this editor's draft like any change.
 */
internal class DeckGraphicsState(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) {
    private val byId = SettingsRegistry.defs.associateBy { it.id }

    /** A row's current value in this editor (the draft included), or "" where it doesn't apply. */
    fun value(id: String): String = byId[id]?.takeIf { source.shows(it) }?.let { source.value(it) } ?: ""

    var sheet by mutableStateOf<ContentProfile.ContentType?>(null)
    var vegasSheet by mutableStateOf(false)
    var stockSheet by mutableStateOf(false)
    var wrappers by mutableStateOf(false)
    var vegasEditor by mutableStateOf(false)
    /** The Turnip download sheet, for the Wayland compositor driver. */
    var turnipSheet by mutableStateOf(false)
    /** Bumped after a download, so an open VEGAS config editor reads its lists again. */
    var refreshKey by mutableIntStateOf(0)
        private set
    // The VEGAS editor closes for a download it starts and comes back when that closes, as in the classic editors.
    private var reopenEditor = false

    private fun wrapper() = value(GraphicsRows.ID_WRAPPER)
    private fun dxvkLike() = wrapper().let { it.contains("dxvk") || it.contains("vegas") }
    private fun vegas() = wrapper().contains("vegas")
    private fun x11() = !env.isLinuxEntry && !env.wayland(this::value)

    /**
     * Reads the lists, the driver's extensions and the wrapper's capabilities. Run in the editor's scope,
     * not the page's: the page item that asks can leave the screen before the reading is done.
     */
    fun probe(version: String, driver: String, x11: Boolean) {
        scope.launch {
            env.graphics.load(env.arm64ec)
            if (x11) {
                env.graphics.loadCaps(driver)
                env.graphics.probeExtensions(version)
            }
        }
    }

    /** Reads the installed builds again, e.g. after a download or coming back from another screen. */
    fun reloadContents() {
        scope.launch { env.graphics.reloadContents(env.arm64ec) }
        refreshKey++
    }

    fun closedSheet() {
        reloadContents()
        if (reopenEditor) {
            reopenEditor = false
            vegasEditor = true
        }
    }

    /** From the VEGAS editor: close it, run [open], and come back to it afterwards. */
    fun fromEditor(open: () -> Unit) {
        vegasEditor = false
        reopenEditor = true
        open()
    }

    /** A Turnip was installed: the compositor driver choices and the driver lists are read again. */
    fun turnipInstalled() {
        turnipSheet = false
        // Only a container form looks for a usable compositor driver again; a game's container form is never saved here.
        if (env.scope != EditorScope.GAME) env.vm?.onCompositorDriversChanged()
        scope.launch {
            env.loadAsync()
            env.graphics.reloadDrivers()
        }
    }

    fun wrappersClosed() {
        wrappers = false
        env.vm?.refreshGraphicsDriverEntries()
        env.graphics.forgetCaps()
        scope.launch { env.graphics.reloadDrivers() }
    }

    fun categoryExtra(): CategoryExtra = CategoryExtra(top = this::topItems, bottom = this::bottomItems)

    // Nothing to see: it reads the lists and probes the driver while the Graphics page is open.
    private fun topItems(): List<PageExtra> = listOf(PageExtra("graphics-probes") { GraphicsProbes(this) })

    private fun bottomItems(): List<PageExtra> {
        val actions = buildList {
            if (dxvkLike() && !vegas()) add(Action("dxvk", Icons.Filled.CloudDownload, "Download DXVK versions", "Builds from the catalog. Installed ones appear under DXVK version.") { sheet = ContentProfile.ContentType.CONTENT_TYPE_DXVK })
            if (vegas()) add(Action("vegas", Icons.Filled.CloudDownload, "Download VEGAS builds", "Releases of the VEGAS DXVK fork.") { vegasSheet = true })
            if (dxvkLike()) add(Action("vkd3d", Icons.Filled.CloudDownload, "Download VKD3D versions", "DirectX 12 builds from the catalog.") { sheet = ContentProfile.ContentType.CONTENT_TYPE_VKD3D })
            if (dxvkLike() && value(GraphicsRows.ID_DDRAW) == "d7vk") add(Action("d7vk", Icons.Filled.CloudDownload, "Download D7VK versions", "DirectDraw and Direct3D 7 on Vulkan.") { sheet = ContentProfile.ContentType.CONTENT_TYPE_D7VK })
            if (vegas()) add(Action("stock", Icons.Filled.CloudDownload, "Download stock VEGAS configs", "The config files VEGAS releases ship. They appear under Config file.") { stockSheet = true })
            if (vegas()) add(Action("vegasEditor", Icons.Filled.Description, "Edit the VEGAS config file", "Performance tier, each key of the file, backups, and deleting or installing VEGAS builds.") { vegasEditor = true })
            if (x11()) add(Action("wrappers", Icons.Filled.Inventory2, "Manage graphics wrappers", "Download, import or delete the wrappers listed under Graphics driver.") { wrappers = true })
            if (!env.isLinuxEntry && env.wayland(this@DeckGraphicsState::value)) add(Action("turnip", Icons.Filled.CloudDownload, "Download a Turnip driver", "Wayland needs an installed Turnip to put the game's frames on screen. New ones appear under Graphics driver.") { turnipSheet = true })
        }
        if (actions.isEmpty()) return emptyList()
        return listOf(PageExtra("graphics-tools-label") { GroupLabel("Downloads and tools") }) +
            actions.mapIndexed { i, a ->
                PageExtra("graphics-tool:${a.key}") {
                    GroupCard(first = i == 0, last = i == actions.lastIndex, highlight = false) { ActionRow(a) }
                }
            }
    }

    class Action(val key: String, val icon: ImageVector, val label: String, val hint: String, val onClick: () -> Unit)
}

/** Reads the lists once, and probes the driver version and wrapper being edited, while the Graphics page shows. */
@Composable
private fun GraphicsProbes(state: DeckGraphicsState) {
    val env = state.env
    val version = state.value(GraphicsRows.ID_DRIVER_VERSION)
    val driver = state.value(GraphicsRows.ID_GFX_DRIVER)
    val x11 = !env.isLinuxEntry && !env.wayland(state::value)
    LaunchedEffect(version, driver, x11) { state.probe(version, driver, x11) }
}

/** One action: the whole line is the focus target and A runs it. */
@Composable
private fun ActionRow(action: DeckGraphicsState.Action) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f)
            .clickable(onClick = action.onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Icon(action.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(action.label, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(action.hint, color = cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** The download sheets, the wrapper manager and the VEGAS config editor the page's actions open. */
@Composable
internal fun DeckGraphicsDialogs(state: DeckGraphicsState) {
    val env = state.env
    val source = state.source
    state.sheet?.let { type ->
        ContentDownloadSheet(
            contentType = type,
            onDismiss = { state.sheet = null; state.closedSheet() },
            onContentChanged = { state.reloadContents() },
            inUseKey = when (type) {
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> state.value(GraphicsRows.ID_VKD3D)
                ContentProfile.ContentType.CONTENT_TYPE_DXVK -> state.value(GraphicsRows.ID_DXVK)
                else -> null
            },
        )
    }
    if (state.vegasSheet) {
        VegasDownloadSheet(
            onDismiss = { state.vegasSheet = false; state.closedSheet() },
            onContentChanged = { state.reloadContents() },
        )
    }
    if (state.stockSheet) {
        StockConfigDownloadSheet(onDismiss = { state.stockSheet = false; state.closedSheet() })
    }
    if (state.turnipSheet) {
        AdrenoDriverDownloadSheet(
            onDismiss = { state.turnipSheet = false },
            onDriverInstalled = { state.turnipInstalled() },
        )
    }
    if (state.wrappers) {
        WrapperManagerDialog(onDismiss = { state.wrappersClosed() })
    }
    if (state.vegasEditor) {
        val raw = SettingsRegistry.defs.firstOrNull { it.id == GraphicsRows.ID_DX_CONFIG }
        if (raw == null) {
            state.vegasEditor = false
        } else {
            DxvkConfigDialog(
                isArm64EC = env.arm64ec,
                isVegas = true,
                // The container's own copies of stock configs live in it; a game's and the defaults' in the app's.
                containerRootDir = if (env.scope == EditorScope.CONTAINER) env.vm?.container?.rootDir else null,
                refreshKey = state.refreshKey,
                initialConfig = source.value(raw),
                onConfirm = { config ->
                    state.vegasEditor = false
                    source.set(raw, config)
                },
                onDismiss = { state.vegasEditor = false },
                onDownloadDxvk = { state.fromEditor { state.vegasSheet = true } },
                onDownloadVkd3d = { state.fromEditor { state.sheet = ContentProfile.ContentType.CONTENT_TYPE_VKD3D } },
                onDownloadD7vk = { state.fromEditor { state.sheet = ContentProfile.ContentType.CONTENT_TYPE_D7VK } },
                relaxDxvkFilter = state.value(GraphicsRows.ID_GFX_DRIVER) == "wrapper-compat-bcn",
                onOpenConfigDownload = { state.fromEditor { state.stockSheet = true } },
            )
        }
    }
}
