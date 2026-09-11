package com.winlator.star.ui.screens

import android.content.Context
import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.R
import com.winlator.star.container.Shortcut
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.VegasKeyCatalog
import com.winlator.star.contentdialog.VegasKeyKnowledge
import com.winlator.star.contentdialog.VegasTierPresets
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// ── DX wrapper configuration: DxvkConfigDialog (DXVK / VEGAS) or WineD3DConfigDialog ──────────────
// Both dialogs rebuild "dxwrapperConfig" on OK from the stored string (DXVKConfigDialog / WineD3D-
// ConfigDialog.parseConfig → KeyValueSet.put). Every change here does the same with the values the
// dialog would show, so a single change persists exactly what that dialog's OK would.

internal fun xmbDxConfigMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val c = shortcut.container
    val entries = ctx.resources.getStringArray(R.array.dxwrapper_entries).toList()
    val id = shortcut.p2Ex("dxwrapper", c.getDXWrapper())
    val w = StringUtils.parseIdentifier(entries.firstOrNull { StringUtils.parseIdentifier(it) == id } ?: entries.firstOrNull() ?: id)
    return if (w.contains("dxvk") || w.contains("vegas")) p2DxvkMenu(xmb, shortcut, w.contains("vegas")) else p2WineD3DMenu(xmb, shortcut)
}

// ── WineD3D ─────────────────────────────────────────────────────────────────────────────────────────

private data class P2D3dSel(
    val csmt: String, val gpuName: String, val ddra: String, val videoMem: String,
    val ssm: String, val orm: String, val renderer: String,
)

private fun p2WineD3DMenu(xmb: XmbScope, s: Shortcut): XmbMenu {
    val ctx = xmb.context
    val res = ctx.resources
    val c = s.container
    val onOff = listOf("Enabled", "Disabled")
    val ormOptions = listOf("fbo", "backbuffer")
    val rendOptions = listOf("gl", "vulkan", "gdi")
    val ddraEntries = res.getStringArray(R.array.ddrawrapper_entries).toList()
    val videoMemEntries = res.getStringArray(R.array.video_memory_size_entries).toList()
    var gpuNames by mutableStateOf<List<String>>(emptyList())
    xmb.scope.launch {
        gpuNames = withContext(Dispatchers.IO) {
            runCatching { com.winlator.star.contentdialog.WineD3DConfigDialog.loadGpuNames(ctx).toList() }.getOrDefault(emptyList())
        }
    }
    fun raw(): String = s.p2Ex("dxwrapperConfig", c.getDXWrapperConfig())
    fun sel(): P2D3dSel {
        val cfg = com.winlator.star.contentdialog.WineD3DConfigDialog.parseConfig(raw())
        return P2D3dSel(
            csmt = if (cfg.get("csmt") == "3") "Enabled" else "Disabled",
            gpuName = cfg.get("gpuName") ?: "",
            ddra = ddraEntries.firstOrNull { StringUtils.parseIdentifier(it) == cfg.get("ddrawrapper") } ?: ddraEntries.firstOrNull() ?: "",
            videoMem = videoMemEntries.firstOrNull { StringUtils.parseNumber(it) == cfg.get("videoMemorySize") } ?: videoMemEntries.firstOrNull() ?: "",
            ssm = if (cfg.get("strict_shader_math") == "1") "Enabled" else "Disabled",
            orm = (cfg.get("OffscreenRenderingMode") ?: "").ifEmpty { "fbo" },
            renderer = (cfg.get("renderer") ?: "").ifEmpty { "gl" },
        )
    }
    fun write(n: P2D3dSel) {
        val cfg = com.winlator.star.contentdialog.WineD3DConfigDialog.parseConfig(raw())
        cfg.put("csmt", if (n.csmt == "Enabled") "3" else "0")
        cfg.put("strict_shader_math", if (n.ssm == "Enabled") "1" else "0")
        cfg.put("OffscreenRenderingMode", n.orm)
        cfg.put("gpuName", n.gpuName)
        cfg.put("ddrawrapper", StringUtils.parseIdentifier(n.ddra))
        cfg.put("videoMemorySize", StringUtils.parseNumber(n.videoMem))
        cfg.put("renderer", n.renderer)
        p2Put(xmb, s, "dxwrapperConfig", cfg.toString())
    }
    fun withCur(opts: List<String>, cur: String) = if (cur.isNotEmpty() && cur !in opts) opts + cur else opts

    return XmbMenu("WineD3D ${ctx.getString(R.string.configuration)}", Icons.Filled.Tune) {
        val cur = sel()
        val rows = mutableListOf<XmbRow>()
        rows += XmbRow.Choice("csmt", "CSMT", Icons.Filled.Speed, onOff, cur.csmt) { v -> write(sel().copy(csmt = v)) }
        val names = withCur(gpuNames, cur.gpuName)
        if (names.isNotEmpty()) {
            rows += XmbRow.Choice("gpuName", ctx.getString(R.string.gpu_name), Icons.Filled.DeveloperBoard, p2Unique(names), cur.gpuName) { v ->
                write(sel().copy(gpuName = v))
            }
        } else rows += XmbRow.Info("gpuLoading", ctx.getString(R.string.gpu_name), Icons.Filled.DeveloperBoard, "Loading…")
        if (ddraEntries.isNotEmpty()) {
            rows += XmbRow.Choice("ddraw", "DDraw Wrapper", Icons.Filled.Layers, ddraEntries, cur.ddra) { v -> write(sel().copy(ddra = v)) }
        }
        if (videoMemEntries.isNotEmpty()) {
            rows += XmbRow.Choice("videoMem", ctx.getString(R.string.graphics_driver_max_device_memory), Icons.Filled.Memory,
                videoMemEntries, cur.videoMem) { v -> write(sel().copy(videoMem = v)) }
        }
        rows += XmbRow.Choice("ssm", "Strict Shader Math", Icons.Filled.Tune, onOff, cur.ssm) { v -> write(sel().copy(ssm = v)) }
        rows += XmbRow.Choice("orm", "Offscreen Rendering Mode", Icons.Filled.Image, withCur(ormOptions, cur.orm), cur.orm) { v -> write(sel().copy(orm = v)) }
        rows += XmbRow.Choice("renderer", "Renderer", Icons.Filled.Layers, withCur(rendOptions, cur.renderer), cur.renderer) { v -> write(sel().copy(renderer = v)) }
        rows
    }
}

// ── DXVK / VEGAS ────────────────────────────────────────────────────────────────────────────────────

private data class P2DxSel(
    val dxvk: String, val vkd3d: String, val framerate: String, val async: Boolean, val asyncCache: Boolean,
    val aniso: Int, val lod: Int, val level: String, val ddra: String, val d7vk: String, val configFile: String,
)

// Index-aligned with DXVKConfigDialog.ANISOTROPY_VALUES / LOD_BIAS_VALUES (the dialog's labels).
private val P2_ANISO_LABELS = listOf("Game default", "2x", "4x", "8x", "16x")
private val P2_LOD_LABELS = listOf(
    "Game default", "Auto (match scaling mode)", "Sharper (-0.25)", "Sharper (-0.5)", "Sharper (-0.75)", "Sharpest (-1.0)",
)

private fun p2DxvkMenu(xmb: XmbScope, s: Shortcut, isVegas: Boolean): XmbMenu {
    val ctx = xmb.context
    val res = ctx.resources
    val c = s.container
    val framerates = res.getStringArray(R.array.dxvk_framerate_entries).toList()
    val ddraEntries = res.getStringArray(R.array.ddrawrapper_entries).toList()
    val levels = DXVKConfigDialog.VKD3D_FEATURE_LEVEL.toList()
    // Mali "Wrapper + compat + bcn" relaxes the DXVK 2.x-with-VKD3D filter (#137), as in the editor.
    val relaxDxvkFilter = s.p2Ex("graphicsDriver", c.graphicsDriver) == "wrapper-compat-bcn"

    var loaded by mutableStateOf(false)
    var allDxvk by mutableStateOf<List<String>>(emptyList())
    var vkd3dList by mutableStateOf<List<String>>(emptyList())
    var d7vkList by mutableStateOf(listOf(DXVKConfigDialog.D7VK_BUNDLED))
    var busy by mutableStateOf(false)

    fun raw(): String = s.p2Ex("dxwrapperConfig", c.getDXWrapperConfig())

    /** What the dialog shows for the stored config (its init coercions). */
    fun selOf(cfg: KeyValueSet): P2DxSel {
        val storedVer = cfg.get("version") ?: ""
        val fr = cfg.get("framerate") ?: ""
        val ddraId = cfg.get("ddrawrapper") ?: ""
        val d7 = cfg.get("d7vkVersion") ?: ""
        val lvl = cfg.get("vkd3dLevel") ?: ""
        return P2DxSel(
            dxvk = allDxvk.firstOrNull { it == storedVer } ?: allDxvk.firstOrNull() ?: storedVer,
            vkd3d = (cfg.get("vkd3dVersion") ?: "").ifEmpty { "None" },
            framerate = framerates.firstOrNull { StringUtils.parseNumber(it) == fr } ?: framerates.firstOrNull() ?: "",
            async = cfg.get("async") == "1",
            asyncCache = cfg.get("asyncCache") == "1",
            aniso = DXVKConfigDialog.ANISOTROPY_VALUES.indexOf(cfg.get("anisotropy")).coerceAtLeast(0),
            lod = DXVKConfigDialog.LOD_BIAS_VALUES.indexOf(cfg.get("lodBias")).coerceAtLeast(0),
            level = levels.firstOrNull { it == lvl } ?: levels.firstOrNull() ?: "",
            ddra = ddraEntries.firstOrNull { StringUtils.parseIdentifier(it) == ddraId } ?: ddraEntries.firstOrNull() ?: "",
            d7vk = d7vkList.firstOrNull { it == d7 } ?: DXVKConfigDialog.D7VK_BUNDLED,
            configFile = cfg.get("dxvkConfigFile") ?: "",
        )
    }
    fun sel(): P2DxSel = selOf(DXVKConfigDialog.parseConfig(raw()))

    /** The dialog's OK: every key from the (coerced) selection, onto the stored KeyValueSet. */
    fun write(n: P2DxSel) {
        val type = DXVKConfigDialog.getDXVKType(n.dxvk)
        val cfg = DXVKConfigDialog.parseConfig(raw())
        cfg.put("version", n.dxvk)
        cfg.put("framerate", StringUtils.parseNumber(n.framerate))
        cfg.put("async", if (n.async && type != DXVKConfigDialog.DXVK_TYPE_NONE) "1" else "0")
        cfg.put("asyncCache", if (n.asyncCache && type == DXVKConfigDialog.DXVK_TYPE_GPLASYNC) "1" else "0")
        cfg.put("anisotropy", DXVKConfigDialog.ANISOTROPY_VALUES[n.aniso.coerceIn(0, DXVKConfigDialog.ANISOTROPY_VALUES.size - 1)])
        cfg.put("lodBias", DXVKConfigDialog.LOD_BIAS_VALUES[n.lod.coerceIn(0, DXVKConfigDialog.LOD_BIAS_VALUES.size - 1)])
        cfg.put("vkd3dVersion", n.vkd3d)
        cfg.put("vkd3dLevel", n.level)
        cfg.put("ddrawrapper", StringUtils.parseIdentifier(n.ddra))
        cfg.put("d7vkVersion", n.d7vk)
        cfg.put("dxvkConfigFile", n.configFile)
        p2Put(xmb, s, "dxwrapperConfig", cfg.toString())
    }

    val vegas = P2Vegas(xmb, pointer = { sel().configFile }, setPointer = { p -> write(sel().copy(configFile = p)) }, version = { sel().dxvk })

    fun load() {
        xmb.scope.launch {
            val lists: Triple<List<String>, List<String>, List<String>> = withContext(Dispatchers.IO) {
                val cm = ContentsManager(ctx)
                cm.syncContents()
                val arm = runCatching { WineInfo.fromIdentifier(ctx, cm, c.wineVersion).isArm64EC() }.getOrDefault(false)
                val versions: List<String> = runCatching {
                    (if (isVegas) DXVKConfigDialog.loadVegasVersionList(ctx, cm) else DXVKConfigDialog.loadDxvkVersionList(ctx, cm, arm)).toList()
                }.getOrDefault(emptyList())
                val vk: List<String> = runCatching { DXVKConfigDialog.loadVkd3dVersionList(ctx, cm).toList() }.getOrDefault(listOf("None"))
                val d7: List<String> = runCatching { DXVKConfigDialog.loadD7vkVersionList(ctx, cm).toList() }.getOrDefault(listOf(DXVKConfigDialog.D7VK_BUNDLED))
                if (isVegas) vegas.loadIo(cm)
                Triple(versions, vk, d7)
            }
            allDxvk = lists.first.distinct()
            vkd3dList = lists.second.distinct()
            d7vkList = lists.third.distinct()
            loaded = true
            if (isVegas) vegas.reloadText()
        }
    }
    load()

    fun deleteVegasBuild(ver: String) {
        busy = true
        xmb.scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val cm = ContentsManager(ctx)
                    cm.syncContents()
                    // Exact "vegas-<ver>" or a hash-suffixed variant (asset renames), as the dialog matches.
                    val profile = (cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS) ?: emptyList<ContentProfile>())
                        .firstOrNull { p -> val n = p.verName ?: ""; n == "vegas-$ver" || n.removePrefix("vegas-") == ver || n == ver }
                    if (profile != null) { cm.removeContent(profile); cm.syncContents(); "VEGAS version deleted" }
                    else "No installed VEGAS version to delete"
                }.getOrElse { "ERROR: Failed to delete — ${it.message}" }
            }
            busy = false
            xmb.toast(msg)
            val versions: List<String> = withContext(Dispatchers.IO) {
                runCatching { val cm = ContentsManager(ctx); cm.syncContents(); DXVKConfigDialog.loadVegasVersionList(ctx, cm).toList() }.getOrDefault(allDxvk)
            }
            allDxvk = versions.distinct()
            // The editor then selects the first remaining build; persist that so the game can launch it.
            if (sel().dxvk != (DXVKConfigDialog.parseConfig(raw()).get("version") ?: "")) write(sel())
        }
    }

    val title = if (isVegas) "VEGAS ${ctx.getString(R.string.configuration)}" else "DXVK ${ctx.getString(R.string.configuration)}"
    return XmbMenu(title, Icons.Filled.Tune) body@{
        val rows = mutableListOf<XmbRow>()
        if (!loaded) {
            rows += XmbRow.Info("loading", "Loading versions…", Icons.Filled.Info)
            return@body rows
        }
        val cur = sel()
        val filtered = if (cur.vkd3d != "None" && !relaxDxvkFilter) {
            // VKD3D-Proton needs DXVK 2.x's DXGI (#113): 1.x is hidden while VKD3D is on.
            allDxvk.filter { v -> val major = DXVKConfigDialog.tryGetMajor(v); major == null || major >= 2 }
        } else allDxvk
        val dxvkOpts = if (cur.dxvk.isNotEmpty() && cur.dxvk !in filtered) filtered + cur.dxvk else filtered
        val dxvkDisabled = if (cur.dxvk.isNotEmpty() && cur.dxvk !in filtered) setOf(cur.dxvk) else emptySet()
        val type = DXVKConfigDialog.getDXVKType(cur.dxvk)

        rows += XmbRow.Info("logNote", ctx.getString(R.string.vegas_config_loglevel_envvar_note), Icons.Filled.Warning)
        if (isVegas) {
            rows += XmbRow.Header("hVegas", "VEGAS version")
            if (filtered.isEmpty()) {
                rows += XmbRow.Info("noVegas", "No VEGAS build installed — download one from the container's DX wrapper settings", Icons.Filled.Warning)
            } else {
                rows += XmbRow.Choice("vegasVersion", "VEGAS version", Icons.Filled.Layers, dxvkOpts, cur.dxvk, disabledOptions = dxvkDisabled) { v ->
                    write(sel().copy(dxvk = v))
                }
                rows += XmbRow.Action("vegasDelete", "Delete this VEGAS build", Icons.Filled.Delete, danger = true,
                    subtitle = cur.dxvk, disabledReason = if (busy) "Working…" else null) {
                    val ver = sel().dxvk
                    xmb.confirm(XmbConfirm("Delete VEGAS build", "Remove the installed VEGAS $ver from this device?", "Delete", danger = true)) {
                        deleteVegasBuild(ver)
                    }
                }
            }
        }
        rows += XmbRow.Choice("vkd3d", ctx.getString(R.string.vkd3d_version), Icons.Filled.Layers,
            if (cur.vkd3d in vkd3dList) vkd3dList else vkd3dList + cur.vkd3d, cur.vkd3d) { v -> write(sel().copy(vkd3d = v)) }
        rows += XmbRow.Link("vkd3dDownload", "Download VKD3D versions", Icons.Filled.CloudDownload) {
            p2ContentDownloadMenu(xmb, "VKD3D downloads", ContentProfile.ContentType.CONTENT_TYPE_VKD3D) { load() }
        }
        if (!isVegas) {
            if (dxvkOpts.isNotEmpty()) {
                rows += XmbRow.Choice("dxvk", ctx.getString(R.string.dxvk_version), Icons.Filled.Layers, dxvkOpts, cur.dxvk,
                    subtitle = if (dxvkDisabled.isNotEmpty()) "DXVK 1.x can't back VKD3D — pick a 2.x build" else null,
                    disabledOptions = dxvkDisabled) { v -> write(sel().copy(dxvk = v)) }
            }
            rows += XmbRow.Link("dxvkDownload", "Download DXVK versions", Icons.Filled.CloudDownload) {
                p2ContentDownloadMenu(xmb, "DXVK downloads", ContentProfile.ContentType.CONTENT_TYPE_DXVK) { load() }
            }
        }
        if (type != DXVKConfigDialog.DXVK_TYPE_NONE) {
            rows += XmbRow.Toggle("async", "Async", Icons.Filled.Speed, cur.async) { on -> write(sel().copy(async = on)) }
        }
        if (type == DXVKConfigDialog.DXVK_TYPE_GPLASYNC) {
            rows += XmbRow.Toggle("asyncCache", "Async Cache", Icons.Filled.Speed, cur.asyncCache) { on -> write(sel().copy(asyncCache = on)) }
        }
        if (framerates.isNotEmpty()) {
            rows += XmbRow.Choice("framerate", ctx.getString(R.string.frame_rate), Icons.Filled.Speed, framerates, cur.framerate) { v ->
                write(sel().copy(framerate = v))
            }
        }

        rows += XmbRow.Header("hTexture", "Texture filtering")
        val texNote = "DirectX 9-11 games only. Applies the next time the game starts."
        rows += XmbRow.Choice("aniso", "Anisotropic filtering", Icons.Filled.Image, P2_ANISO_LABELS,
            P2_ANISO_LABELS.getOrElse(cur.aniso) { P2_ANISO_LABELS[0] }, subtitle = texNote) { v ->
            write(sel().copy(aniso = P2_ANISO_LABELS.indexOf(v).coerceAtLeast(0)))
        }
        rows += XmbRow.Choice("lodBias", "Texture sharpness", Icons.Filled.Image, P2_LOD_LABELS,
            P2_LOD_LABELS.getOrElse(cur.lod) { P2_LOD_LABELS[0] }, subtitle = texNote) { v ->
            write(sel().copy(lod = P2_LOD_LABELS.indexOf(v).coerceAtLeast(0)))
        }

        rows += XmbRow.Header("hApi", "API feature level")
        if (levels.isNotEmpty()) {
            rows += XmbRow.Choice("level", "VKD3D feature level", Icons.Filled.Layers, levels, cur.level) { v -> write(sel().copy(level = v)) }
        }
        if (ddraEntries.isNotEmpty()) {
            rows += XmbRow.Choice("ddraw", "DDraw Wrapper", Icons.Filled.Layers, ddraEntries, cur.ddra) { v -> write(sel().copy(ddra = v)) }
        }
        if (StringUtils.parseIdentifier(cur.ddra) == "d7vk") {
            rows += XmbRow.Choice("d7vk", "D7VK Version", Icons.Filled.Layers,
                if (cur.d7vk in d7vkList) d7vkList else d7vkList + cur.d7vk, cur.d7vk) { v -> write(sel().copy(d7vk = v)) }
            rows += XmbRow.Link("d7vkDownload", "Download D7VK versions", Icons.Filled.CloudDownload) {
                p2ContentDownloadMenu(xmb, "D7VK downloads", ContentProfile.ContentType.CONTENT_TYPE_D7VK) { load() }
            }
        }
        if (isVegas) vegas.rows(rows)
        rows
    }
}

// ── VEGAS config file (tier + config source + per-key editor) ───────────────────────────────────────
// Option B, as in the shortcut editor's dialog (no container root): the stored dxvkConfigFile IS the
// live file. A stock baseline stays read-only until the first edit, which writes the user's own copy to
// filesDir/vegas-defaults/configs/<tag>.user.conf and moves the pointer to it (persisted at once, like
// the container editor's onLivePointerChanged). The first edit after a selection keeps a ".bak" copy.

private class P2Vegas(
    val xmb: XmbScope,
    val pointer: () -> String,
    val setPointer: (String) -> Unit,
    val version: () -> String,
) {
    val ctx: Context = xmb.context
    var ready by mutableStateOf(false)
    var stock by mutableStateOf<List<DXVKConfigDialog.StockSource>>(emptyList())
    var customBase by mutableStateOf<List<String>>(emptyList())
    var vocab by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var baseline by mutableStateOf<Map<String, List<VegasKeyKnowledge.EditRow>>>(emptyMap())
    var text by mutableStateOf("")
    var missing by mutableStateOf(false)
    var pendingKey by mutableStateOf<String?>(null)
    var knowledge: VegasKeyKnowledge? = null
    var catalog: VegasKeyCatalog? = null
    var gpuModel: String? = null
    var detectedTier: Int? = null

    private val sidecarDir: File get() = File(ctx.filesDir, "vegas-defaults/configs")

    fun sidecarFor(src: DXVKConfigDialog.StockSource): File {
        val base = (src.tag?.takeIf { it.isNotBlank() } ?: (src.verName ?: "").removePrefix("vegas-").substringBefore(" ·"))
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(sidecarDir, "$base.user.conf")
    }

    /** The stock baseline a stored pointer belongs to — its parked file or the user's copy of it. */
    fun stockOf(path: String): DXVKConfigDialog.StockSource? =
        if (path.isEmpty()) null else stock.firstOrNull { it.file.absolutePath == path || sidecarFor(it).absolutePath == path }

    fun liveFile(): File? {
        val p = pointer()
        if (p.isEmpty()) return null
        val st = stockOf(p) ?: return File(p)
        val sc = sidecarFor(st)
        return if (sc.isFile) sc else st.file
    }

    /** Called on the IO thread by the menu's loader. */
    suspend fun loadIo(cm: ContentsManager) {
        val src: List<DXVKConfigDialog.StockSource> = runCatching { DXVKConfigDialog.loadVegasStockSources(ctx, cm).toList() }.getOrDefault(emptyList())
        val cfgsrc: List<String> = runCatching { DXVKConfigDialog.loadVegasConfigSourceList(ctx).toList() }.getOrDefault(emptyList())
        val prefs = ctx.getSharedPreferences("vegas_config_ui", Context.MODE_PRIVATE)
        // Autonomy tails persisted by the dialog's live checks (same prefs), so classification matches it.
        val k: VegasKeyKnowledge? = runCatching { DXVKConfigDialog.loadVegasKeyKnowledge(ctx) }.getOrNull()
        val saved: List<String> = prefs.getString("released_tail", null)?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        if (k != null && saved.isNotEmpty()) runCatching { k.mergeReleasedTail(saved) }
        val cat: VegasKeyCatalog? = runCatching { DXVKConfigDialog.loadVegasKeyCatalog(ctx) }.getOrNull()
        val tail: List<String> = prefs.getString("catalog_tail", null)?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        if (cat != null && tail.isNotEmpty()) runCatching { cat.mergeTailTags(tail) }
        val base = HashMap<String, List<VegasKeyKnowledge.EditRow>>()
        val values = HashMap<String, LinkedHashSet<String>>()
        for (st in src) {
            val t = runCatching { st.file.readText() }.getOrNull() ?: continue
            val rows: List<VegasKeyKnowledge.EditRow> = (k?.editRows(t, st.verName) ?: VegasKeyKnowledge.editRowsUnclassified(t)).toList()
            base[st.file.absolutePath] = rows
            for (r in rows) if (r.enabled && r.value.isNotEmpty()) values.getOrPut(r.key) { LinkedHashSet() }.add(r.value)
        }
        val model = graphicsProbeMutex.withLock { runCatching { VegasTierPresets.readGpuModel(ctx) }.getOrNull() }
        withContext(Dispatchers.Main) {
            knowledge = k
            catalog = cat
            gpuModel = model
            detectedTier = VegasTierPresets.classifyModel(model)
            stock = src
            customBase = cfgsrc.filter { it != "None" }
            baseline = base
            vocab = values.mapValues { it.value.toList() }
            ready = true
        }
    }

    fun reloadText() {
        val f = liveFile()
        xmb.scope.launch {
            val r: Pair<String, Boolean> = withContext(Dispatchers.IO) {
                if (f == null) "" to false
                else if (!f.isFile) "" to true
                else (runCatching { f.readText() }.getOrDefault("") to false)
            }
            text = r.first
            missing = r.second
        }
    }

    fun currentRows(): List<VegasKeyKnowledge.EditRow> =
        (knowledge?.editRows(text, version()) ?: VegasKeyKnowledge.editRowsUnclassified(text)).toList()

    private fun baselineRows(): List<VegasKeyKnowledge.EditRow> =
        stockOf(pointer())?.let { baseline[it.file.absolutePath] } ?: emptyList()

    fun isBooleanKey(key: String): Boolean {
        val v = vocab[key] ?: return false
        return v.isNotEmpty() && v.all { it == "0" || it == "1" }
    }

    /** §6a.6: a key from the other VEGAS line's schema can never apply to this stock build. */
    private fun blocked(key: String): Boolean {
        val tag = stockOf(pointer())?.tag ?: return false
        val cat = catalog ?: return false
        if (!cat.isWrongFamily(key, tag)) return false
        xmb.toast("$key belongs to another VEGAS schema — build $tag would ignore it")
        return true
    }

    /** The dialog's commitConfigWrite: back up once, write (stock → the user's own copy), move the pointer. */
    private fun commit(transform: (String) -> String?) {
        val target = liveFile() ?: return
        val st = stockOf(pointer())
        val sidecar = st?.let { sidecarFor(it) }
        val sidecarExists = sidecar?.isFile == true
        val toSidecar = st != null && !sidecarExists && sidecar != null
        xmb.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (!target.isFile) return@runCatching false
                    val next = transform(target.readText()) ?: return@runCatching false
                    val autoBak = File(target.absolutePath + ".bak")
                    if (!autoBak.isFile) runCatching { target.copyTo(autoBak) }
                    val out = if (toSidecar && sidecar != null) sidecar else target
                    out.parentFile?.mkdirs()
                    out.writeText(next)
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                val finalPath = if (toSidecar && sidecar != null) sidecar.absolutePath else target.absolutePath
                if (finalPath != pointer()) setPointer(finalPath) else { xmb.saved(); xmb.refresh() }
                reloadText()
            } else xmb.toast("Failed to update config file")
        }
    }

    fun applyToggle(key: String, value: String, enable: Boolean) {
        if (blocked(key)) return
        commit { t -> VegasKeyKnowledge.toggleLine(t, key, enable) ?: if (enable) VegasKeyKnowledge.setLine(t, key, value) else t }
    }

    fun applyValue(key: String, value: String) {
        if (blocked(key)) return
        commit { t -> VegasKeyKnowledge.setLine(t, key, value) }
    }

    fun applyDelete(key: String) {
        if (blocked(key)) return
        commit { t -> VegasKeyKnowledge.removeLine(t, key) }
    }

    fun rows(rows: MutableList<XmbRow>) {
        if (!ready) {
            rows += XmbRow.Info("vegasLoading", "Reading VEGAS configs…", Icons.Filled.Info)
            return
        }
        val live = liveFile()
        val cfgRows = if (live != null && !missing) currentRows() else emptyList()

        // Performance tier (FAQ #11) → vegas.forceTier in the live file.
        rows += XmbRow.Header("hTier", "Performance tier")
        val applied = cfgRows.firstOrNull { it.key == "vegas.forceTier" }?.value?.toIntOrNull()
        val autoLabel = "Auto" + (detectedTier?.let { " · T$it" } ?: "")
        val tierLabels = listOf(autoLabel) + VegasTierPresets.TIERS.map { it.label }
        val tierSel = if (applied == null || applied == 0) autoLabel else VegasTierPresets.TIERS.firstOrNull { it.number == applied }?.label ?: autoLabel
        val model = gpuModel
        rows += XmbRow.Choice("tier", "Performance tier", Icons.Filled.Speed, tierLabels, tierSel,
            subtitle = when {
                model == null -> "GPU model unreadable — tier is manual"
                detectedTier != null -> "$model · auto Tier $detectedTier"
                else -> "GPU: $model — no tier suggestion"
            },
            disabledReason = if (live == null || missing) "Pick a config file first — defaults has no file to write to" else null) { v ->
            if (v == autoLabel) { if (applied != null && applied != 0) applyValue("vegas.forceTier", "0") }
            else VegasTierPresets.TIERS.firstOrNull { it.label == v }?.let { applyValue("vegas.forceTier", it.number.toString()) }
        }
        applied?.takeIf { it != 0 }?.let { VegasTierPresets.PARAMS[it] }?.let { p ->
            rows.add(XmbRow.Info("tierParams",
                "Draw threshold ${p.drawThreshold} (D3D9 ${p.drawThresholdD3D9}) · HAAE ${p.haaePacing}ms · governor ${p.governorCap} · zero-init ${p.shaderZeroInit} · frame-gen ${p.frameGen}",
                Icons.Filled.Info))
        }
        if (model != null && !model.contains("adreno", ignoreCase = true)) {
            rows += XmbRow.Info("mali", "Mali: pair the 'Wrapper + compat + bcn' driver with the relaxed DXVK list in the driver settings.", Icons.Filled.Warning)
        }

        // Config source: defaults / a stock config / a custom file.
        rows += XmbRow.Header("hConfig", "Config file")
        val stored = pointer()
        val storedStock = stockOf(stored)
        val customs = customBase + (if (stored.isNotEmpty() && storedStock == null && stored !in customBase) listOf(stored) else emptyList())
        val kinds = ArrayList<Pair<String, String?>>() // label, pointer target (null = stock resolved at pick)
        kinds += "Use defaults (no config file)" to ""
        stock.forEach { kinds += "Stock · ${it.displayLabel()}" to null }
        customs.forEach { kinds += it to it }
        val labels = p2Unique(kinds.map { it.first })
        val selIdx = when {
            stored.isEmpty() -> 0
            storedStock != null -> 1 + stock.indexOf(storedStock)
            else -> 1 + stock.size + customs.indexOf(stored).coerceAtLeast(0)
        }.coerceIn(0, labels.size - 1)
        rows += XmbRow.Choice("configSource", "Config source", Icons.Filled.Description, labels, labels[selIdx],
            subtitle = live?.absolutePath ?: "VEGAS built-in defaults") { v ->
            val i = labels.indexOf(v)
            if (i >= 0) {
                val target = when {
                    i == 0 -> ""
                    i <= stock.size -> stock[i - 1].let { st -> sidecarFor(st).takeIf { it.isFile }?.absolutePath ?: st.file.absolutePath }
                    else -> kinds[i].second ?: ""
                }
                setPointer(target)
                reloadText()
            }
        }
        rows += XmbRow.Link("configBrowse", "Browse for a config file", Icons.Filled.FolderOpen, subtitle = "Any .conf — the built-in DXVK_CONFIG_FILE") {
            val ext = Environment.getExternalStorageDirectory()
            val roots = listOf("Download" to File(ext, "Download"), "Internal storage" to ext).filter { it.second.isDirectory }
            xmbFileBrowserMenu(xmb, "Select config file", ext, roots, { true }) { f ->
                // The browser has already closed its columns: we're back on the DX config column.
                if (f.absolutePath !in customBase) customBase = customBase + f.absolutePath
                setPointer(f.absolutePath)
                reloadText()
            }
        }
        if (live != null) {
            if (missing) {
                rows += XmbRow.Info("missing", "Not found: ${live.absolutePath}", Icons.Filled.Warning)
                rows += XmbRow.Action("useDefaults", "Use defaults instead", Icons.Filled.RestartAlt) { setPointer(""); reloadText() }
            } else {
                rows += XmbRow.Link("configKeys", "Config keys", Icons.Filled.Tune, value = "${cfgRows.size} keys",
                    subtitle = if (storedStock != null && !sidecarFor(storedStock).isFile) "Read-only until the first edit — changes create your own copy"
                               else "Toggle, change, add or remove keys") { keysMenu() }
            }
        }
    }

    fun keysMenu(): XmbMenu = XmbMenu("Config keys", Icons.Filled.Tune) {
        val rows = mutableListOf<XmbRow>()
        val live = liveFile()
        if (live == null || missing) {
            rows += XmbRow.Info("none", "No config file selected", Icons.Filled.Info)
        } else {
            val st = stockOf(pointer())
            val cfgRows = currentRows()
            if (st != null) {
                rows += XmbRow.Info("stockNote",
                    if (sidecarFor(st).isFile) "Your edits live in your own copy — the stock version stays pristine."
                    else "Read-only until the first edit — changes create your own copy.", Icons.Filled.Info)
            }
            val pk = pendingKey
            rows += XmbRow.Text("addKey", "Add key", Icons.Filled.Add, pk ?: "",
                subtitle = if (pk != null) "Now set its value below" else "Key name first, then its value",
                placeholder = "e.g. dxvk.maxFrameLatency") { k ->
                val key = k.trim()
                if (!VegasKeyKnowledge.isValidConfigKey(key)) xmb.toast("Not a valid config key — use a dotted name or ENV_STYLE caps")
                else pendingKey = key
            }
            if (pk != null) {
                rows += XmbRow.Text("addValue", "Value for $pk", Icons.Filled.Add, "", placeholder = "Value") { v ->
                    if (v.isNotBlank()) { applyValue(pk, v.trim()); pendingKey = null }
                }
            }
            rows += XmbRow.Header("hKeys", "Keys (${cfgRows.size})")
            val k = knowledge
            cfgRows.forEach { r ->
                val badge = k?.badgeFor(r.key, version()) ?: "unclassified"
                rows += XmbRow.Link("k:${r.key}", r.key, Icons.Filled.Tune,
                    value = if (r.enabled) r.value else "off · ${r.value}", subtitle = badge) { keyMenu(r.key) }
            }
            if (st != null) {
                val active = cfgRows.map { it.key }.toSet()
                val pending = baselineRows().filter { it.key !in active }
                if (pending.isNotEmpty()) rows += XmbRow.Header("hPending", "New in this baseline")
                pending.forEach { r ->
                    val gated = k != null && k.isGated(r.key, version())
                    rows += XmbRow.Action("pend:${r.key}", r.key, Icons.Filled.Add, value = r.value,
                        subtitle = "Missing — adds ${r.key} = ${r.value}",
                        disabledReason = if (gated) "Needs a newer build" else null) { applyToggle(r.key, r.value, true) }
                }
            }
        }
        rows
    }

    fun keyMenu(key: String): XmbMenu = XmbMenu(key, Icons.Filled.Tune) {
        val rows = mutableListOf<XmbRow>()
        val r = currentRows().firstOrNull { it.key == key }
        if (r == null) {
            rows += XmbRow.Info("gone", "This key is no longer in the file", Icons.Filled.Info)
        } else {
            val gated = knowledge?.isGated(key, version()) == true
            val gatedReason = if (gated) "Ineffective on this build" else null
            rows += XmbRow.Toggle("enabled", "Enabled", Icons.Filled.Tune, r.enabled, subtitle = "Off comments the line out") { on ->
                applyToggle(key, r.value, on)
            }
            if (isBooleanKey(key)) {
                rows += XmbRow.Info("value", "Value", Icons.Filled.Tune, r.value)
            } else {
                val opts = LinkedHashSet<String>()
                if (r.value.isNotEmpty()) opts.add(r.value)
                vocab[key].orEmpty().forEach { opts.add(it) }
                if (opts.isNotEmpty()) {
                    rows += XmbRow.Choice("value", "Value", Icons.Filled.Tune, opts.toList(), r.value,
                        subtitle = "Values used in stock configs", disabledReason = gatedReason) { v -> applyValue(key, v) }
                }
                val base = baselineRows().firstOrNull { it.key == key }
                if (base != null && (base.value != r.value || base.enabled != r.enabled)) {
                    rows += XmbRow.Action("resetStock", "Reset to stock (${base.value})", Icons.Filled.RestartAlt,
                        disabledReason = gatedReason) { applyValue(key, base.value) }
                }
                rows += XmbRow.Text("custom", "Custom value", Icons.Filled.Edit, r.value, disabledReason = gatedReason) { v ->
                    if (v.isNotBlank()) applyValue(key, v.trim())
                }
            }
            rows += XmbRow.Action("delete", "Remove key", Icons.Filled.Delete, danger = true, disabledReason = gatedReason) {
                xmb.confirm(XmbConfirm("Remove key", "Remove '$key'? Add it back with Add key.", "Remove", danger = true)) {
                    applyDelete(key)
                    xmb.pop()
                }
            }
        }
        rows
    }
}
