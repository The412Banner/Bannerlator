package com.winlator.star.ui.screens

import android.content.Context
import com.winlator.star.container.VegasLiveCheck
import com.winlator.star.contentdialog.DXVKConfigDialog
import com.winlator.star.contentdialog.VegasKeyCatalog
import com.winlator.star.contentdialog.VegasKeyKnowledge
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.StringUtils
import org.json.JSONArray
import java.io.File

// The VEGAS config editor's steps without its UI: the lists it reads, where the live config file is, the writes,
// backups, the build delete, release notes, the catalog check and the typo help. Classic's DxvkConfigDialog and Lab's
// VEGAS editor (ui/simple/LabVegasEditor.kt) both run these, so both behave the same.

/** The vegas-releases feed the release notes and the new-build check read. */
internal const val VEGAS_RELEASES_FEED = "https://api.github.com/repos/isygold/vegas-releases/releases"

/** The editor's preferences: the fork filter and the feed-learned tails. */
internal const val VEGAS_CONFIG_PREFS = "vegas_config_ui"

/** Texture filtering labels, index-aligned with DXVKConfigDialog.ANISOTROPY_VALUES / LOD_BIAS_VALUES. */
internal val VEGAS_ANISOTROPY_LABELS = listOf("Game default", "2x", "4x", "8x", "16x")
internal val VEGAS_LOD_BIAS_LABELS = listOf(
    "Game default", "Auto (match scaling mode)", "Sharper (-0.25)", "Sharper (-0.5)",
    "Sharper (-0.75)", "Sharpest (-1.0)",
)

/** What the editor lists, read off the main thread. */
internal class VegasEditorLists(
    val versions: List<String>,
    val vkd3d: List<String>,
    val d7vk: List<String>,
    val configSources: List<String>,
    val stock: List<DXVKConfigDialog.StockSource>,
    val installedVegas: List<String>,
)

/** Reads the build, VKD3D, D7VK and config lists (blocking: call on Dispatchers.IO). */
internal fun loadVegasEditorLists(context: Context, isVegas: Boolean, isArm64EC: Boolean): VegasEditorLists {
    val cm = ContentsManager(context)
    cm.syncContents()
    val versions = if (isVegas)
        DXVKConfigDialog.loadVegasVersionList(context, cm)
    else
        DXVKConfigDialog.loadDxvkVersionList(context, cm, isArm64EC)
    val vkd3d = DXVKConfigDialog.loadVkd3dVersionList(context, cm)
    val d7vk = DXVKConfigDialog.loadD7vkVersionList(context, cm)
    val cfgsrc = DXVKConfigDialog.loadVegasConfigSourceList(context)
    val stock = if (isVegas) DXVKConfigDialog.loadVegasStockSources(context, cm) else listOf()
    val installed = if (isVegas) installedVegasVersions(cm) else listOf()
    return VegasEditorLists(versions, vkd3d, d7vk, cfgsrc, stock, installed)
}

/** The installed VEGAS builds again (blocking): versions, stock configs, installed verNames. */
internal fun loadVegasInstalled(context: Context): Triple<List<String>, List<DXVKConfigDialog.StockSource>, List<String>> {
    val cm = ContentsManager(context)
    cm.syncContents()
    val versions = DXVKConfigDialog.loadVegasVersionList(context, cm)
    val stock = DXVKConfigDialog.loadVegasStockSources(context, cm)
    return Triple(versions, stock, installedVegasVersions(cm))
}

private fun installedVegasVersions(cm: ContentsManager): List<String> =
    cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)
        ?.mapNotNull { it.verName }?.distinct().orEmpty()

/** The build list after an install from a file (blocking). */
internal fun reloadVegasVersions(context: Context, isVegas: Boolean, isArm64EC: Boolean): List<String> {
    val cm = ContentsManager(context)
    cm.syncContents()
    return if (isVegas)
        DXVKConfigDialog.loadVegasVersionList(context, cm)
    else
        DXVKConfigDialog.loadDxvkVersionList(context, cm, isArm64EC)
}

/** The source the stored config file points at: no file (defaults), a stock config, or a custom file. */
internal class VegasSourcePick(val useDefaults: Boolean, val stock: String?, val custom: String?, val customEntries: List<String>)

/**
 * Option B: the stored dxvkConfigFile IS the live file. A parked stock file -> that stock; the sidecar of a known stock
 * -> that stock baseline; anything else (custom path, incl. a legacy vegas/active.conf) -> custom, edited in place.
 */
internal fun vegasInitialSource(
    stored: String,
    stock: List<DXVKConfigDialog.StockSource>,
    cfgsrc: List<String>,
    containerRootDir: File?,
): VegasSourcePick {
    val stockMatch = stock.firstOrNull { it.file.absolutePath == stored }
    val sDir = containerRootDir?.let { File(File(it, "vegas"), "configs") }
    val sidecarMatch = if (stored.isNotEmpty() && sDir != null && sDir.isDirectory) {
        stock.firstOrNull { it.tag != null && File(sDir, it.tag + ".user.conf").absolutePath == stored }
    } else null
    val customBase = cfgsrc.filter { it != "None" }
    val entries = if (stored.isNotEmpty() && stored !in customBase) customBase + stored else customBase
    return when {
        stored.isEmpty() -> VegasSourcePick(true, null, null, entries)
        stockMatch != null -> VegasSourcePick(false, stockMatch.displayLabel(), null, entries)
        sidecarMatch != null -> VegasSourcePick(false, sidecarMatch.displayLabel(), null, entries)
        else -> VegasSourcePick(false, null, stored, entries)
    }
}

/** VKD3D-Proton needs DXVK 2.x's DXGI: the list is cut to 2.x+ while VKD3D is on, unless [relax] (Mali bcn driver). */
internal fun vegasFilterDxvk(all: List<String>, selectedVkd3d: String, relax: Boolean): List<String> =
    if (selectedVkd3d != "None" && !relax) {
        all.filter { v ->
            val major = DXVKConfigDialog.tryGetMajor(v)
            major == null || major >= 2
        }
    } else all

/** Re-applies the feed-discovered version tail persisted from prior sessions. */
internal fun vegasApplyReleasedTail(context: Context, k: VegasKeyKnowledge?) {
    val saved = context.getSharedPreferences(VEGAS_CONFIG_PREFS, Context.MODE_PRIVATE)
        .getString("released_tail", null)?.split("|")?.filter { it.isNotBlank() }
    if (!saved.isNullOrEmpty()) k?.mergeReleasedTail(saved)
}

/** Heals the catalog with the live tail: tags seen via the GitHub feed that are newer than the bundled catalog. */
internal fun vegasApplyCatalogTail(context: Context, cat: VegasKeyCatalog?) {
    if (cat == null) return
    val tail = context.getSharedPreferences(VEGAS_CONFIG_PREFS, Context.MODE_PRIVATE)
        .getString("catalog_tail", null)?.split("|")?.filter { it.isNotBlank() }
    if (!tail.isNullOrEmpty()) cat.mergeTailTags(tail)
}

internal fun vegasLoadKnowledge(context: Context): VegasKeyKnowledge? {
    val k: VegasKeyKnowledge? = DXVKConfigDialog.loadVegasKeyKnowledge(context)
    vegasApplyReleasedTail(context, k)
    return k
}

internal fun vegasLoadCatalog(context: Context): VegasKeyCatalog? {
    val cat: VegasKeyCatalog? = DXVKConfigDialog.loadVegasKeyCatalog(context)
    vegasApplyCatalogTail(context, cat)
    return cat
}

/** The stock source a picker label names (its verName or its display label). */
internal fun vegasStockFor(stock: List<DXVKConfigDialog.StockSource>, selected: String?): DXVKConfigDialog.StockSource? =
    stock.firstOrNull { it.verName == selected || it.displayLabel() == selected }

/** Coverage rule (stock only): the installed tag missing from the catalog (or none recorded) -> "catalog behind build". */
internal fun vegasCatalogBehind(catalog: VegasKeyCatalog?, selectedStock: String?, activeStockTag: String?): Boolean =
    catalog != null && selectedStock != null && (activeStockTag == null || !catalog.isCoveredOrTail(activeStockTag))

/** The sidecar name when the stock has no tag (no .provenance.json): its verName, stripped. */
internal fun vegasSidecarFallback(stock: List<DXVKConfigDialog.StockSource>, selected: String?): String? =
    vegasStockFor(stock, selected)?.verName?.removePrefix("vegas-")?.substringBefore(" ·")
        ?: selected?.removePrefix("vegas-")?.substringBefore(" ·")

internal fun vegasSidecarBase(activeStockTag: String?, fallback: String?): String? =
    (activeStockTag?.takeIf { it.isNotBlank() } ?: fallback)?.replace(Regex("[^A-Za-z0-9._-]"), "_")

/** The user's own copy of a stock config: inside the container (or the app's, for a game and the defaults). */
internal fun vegasSidecarPath(context: Context, containerRootDir: File?, base: String?): String? =
    if (base == null) null
    else {
        val dir = if (containerRootDir != null)
            File(File(containerRootDir, "vegas"), "configs")
        else
            File(context.filesDir, "vegas-defaults/configs")
        File(dir, base + ".user.conf").absolutePath
    }

/** Probes both the tag-based and the verName-based sidecar names, so an older tag-sidecar is still found. */
internal fun vegasSidecarExists(sidecarPath: String?, containerRootDir: File?, fallback: String?, base: String?): Boolean =
    if (sidecarPath != null && File(sidecarPath).isFile) true
    else if (containerRootDir != null && fallback != null) {
        val altBase = fallback.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (altBase != base) File(File(File(containerRootDir, "vegas"), "configs"), altBase + ".user.conf").isFile
        else false
    } else false

/** The sidecar that exists (tag-based first, then verName-based), else the intended one. */
internal fun vegasResolvedSidecar(sidecarPath: String?, containerRootDir: File?, fallback: String?): String? =
    if (sidecarPath != null && File(sidecarPath).isFile) sidecarPath
    else if (containerRootDir != null && fallback != null) {
        val alt = File(File(File(containerRootDir, "vegas"), "configs"), fallback.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".user.conf").absolutePath
        if (File(alt).isFile) alt else sidecarPath
    } else sidecarPath

/** The file a game reads: none (defaults), the user's sidecar, the parked stock file, or the custom file. */
internal fun vegasLiveFile(
    useDefaults: Boolean,
    selectedStock: String?,
    selectedCustom: String?,
    stockPath: String?,
    resolvedSidecar: String?,
    sidecarExists: Boolean,
): File? = when {
    useDefaults -> null
    selectedStock != null && sidecarExists && resolvedSidecar != null -> File(resolvedSidecar)
    selectedStock != null -> stockPath?.let { File(it) }
    selectedCustom != null -> File(selectedCustom)
    else -> null
}

/** The .bak-* copies beside [liveFile], newest first. */
internal fun vegasBackups(liveFile: File?): List<File> {
    val dir = liveFile?.parentFile
    val name = liveFile?.name
    return if (dir == null || name == null || !dir.isDirectory) emptyList()
    else dir.listFiles { f -> f.isFile && f.name.startsWith(name + ".bak") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/** The live file's text: empty for defaults or a file that is not there. */
internal fun vegasReadLive(useDefaults: Boolean, liveFile: File?): String =
    if (useDefaults || liveFile == null) ""
    else if (liveFile.isFile) runCatching { liveFile.readText() }.getOrDefault("")
    else ""

internal fun vegasRows(knowledge: VegasKeyKnowledge?, text: String, version: String): List<VegasKeyKnowledge.EditRow> =
    knowledge?.editRows(text, version) ?: VegasKeyKnowledge.editRowsUnclassified(text)

/** The values each key has across all installed stock configs (the value picker's choices). */
internal fun vegasStockKeyValues(
    stock: List<DXVKConfigDialog.StockSource>,
    knowledge: VegasKeyKnowledge?,
    version: String,
): Map<String, List<String>> {
    val m = mutableMapOf<String, MutableSet<String>>()
    for (src in stock) {
        val f = src.file
        if (!f.isFile) continue
        val rows = (knowledge?.editRows(runCatching { f.readText() }.getOrDefault(""), src.verName)
            ?: VegasKeyKnowledge.editRowsUnclassified(runCatching { f.readText() }.getOrDefault("")))
        for (r in rows) if (r.enabled && r.value.isNotEmpty()) m.getOrPut(r.key) { linkedSetOf() }.add(r.value)
    }
    return m.mapValues { it.value.toList() }
}

/** The selected stock baseline's rows (the value picker's reset target and the pending rows' source). */
internal fun vegasBaselineRows(
    selectedStock: String?,
    stock: List<DXVKConfigDialog.StockSource>,
    activeStockTag: String?,
    knowledge: VegasKeyKnowledge?,
    version: String,
): List<VegasKeyKnowledge.EditRow> {
    if (selectedStock == null) return emptyList()
    val f = stock.firstOrNull { it.tag == activeStockTag }?.file
    return if (f == null || !f.isFile) emptyList()
    else (knowledge?.editRows(runCatching { f.readText() }.getOrDefault(""), version)
        ?: VegasKeyKnowledge.editRowsUnclassified(runCatching { f.readText() }.getOrDefault("")))
}

/** Baseline keys absent from the active config (stock editor only): added on enable. */
internal fun vegasPendingRows(baseline: List<VegasKeyKnowledge.EditRow>, rows: List<VegasKeyKnowledge.EditRow>): List<VegasKeyKnowledge.EditRow> {
    val activeKeys = rows.map { it.key }.toSet()
    return baseline.filter { it.key !in activeKeys }
}

/** Keys whose active row differs from the selected baseline (value or comment state). */
internal fun vegasChangedKeys(rows: List<VegasKeyKnowledge.EditRow>, baseline: List<VegasKeyKnowledge.EditRow>, selectedStock: String?): Set<String> {
    if (selectedStock == null) return emptySet()
    val base = baseline.associateBy { it.key }
    return rows.filter { r ->
        val b = base[r.key]
        b != null && (b.value != r.value || b.enabled != r.enabled)
    }.map { it.key }.toSet()
}

/** A key whose stock values are only 0/1: switch-driven, no value picker. */
internal fun vegasIsBooleanKey(stockValues: Map<String, List<String>>, key: String): Boolean =
    stockValues[key]?.isNotEmpty() == true && stockValues[key]!!.all { it == "0" || it == "1" }

/** vegas.forceTier in the active config (3 high, 2 mid, 1 entry, 0 auto, null unset). */
internal fun vegasActiveForceTier(rows: List<VegasKeyKnowledge.EditRow>): Int? =
    rows.firstOrNull { it.key == "vegas.forceTier" }?.value?.toIntOrNull()

internal fun vegasSchemaName(s: VegasKeyCatalog.Schema?): String = when (s) {
    VegasKeyCatalog.Schema.SAREK -> "Sarek (dxvk.vegas.*)"
    VegasKeyCatalog.Schema.STAR -> "Star Engine (vegas.*)"
    null -> "unknown"
}

/** §6a.6: a key of the other line's schema can't apply to this build (stock configs only). */
internal fun vegasWrongSchema(isStockPath: Boolean, activeStockTag: String?, catalog: VegasKeyCatalog?, key: String): Boolean =
    isStockPath && activeStockTag != null && catalog != null && catalog.isWrongFamily(key, activeStockTag)

/** The schema-block explanation for [key]. */
internal fun vegasSchemaBlockText(key: String, catalog: VegasKeyCatalog?, activeStockTag: String?): String {
    val keyFam = catalog?.familyOf(key)
    val instFam = activeStockTag?.let { catalog?.schemaFamilyOf(it) }
    val prefix = when (keyFam) {
        VegasKeyCatalog.Schema.SAREK -> "dxvk.vegas."
        VegasKeyCatalog.Schema.STAR -> if (key == "dxvk.enableStarProfile") key else "vegas."
        null -> "?"
    }
    return "$key belongs to the ${vegasSchemaName(keyFam)} schema ($prefix…).\n\n" +
        "This build (${activeStockTag ?: "unknown"}) uses the ${vegasSchemaName(instFam)} schema — " +
        "the option cannot be applied and would be ignored."
}

/** Comment / uncomment [key]; an absent key is appended (enable) or left alone (disable). */
internal fun vegasToggleTransform(key: String, value: String, enable: Boolean): (String) -> String? = { text ->
    VegasKeyKnowledge.toggleLine(text, key, enable)
        ?: if (enable) VegasKeyKnowledge.setLine(text, key, value) else text
}

/**
 * One write to the live file (blocking). Capture-once backup: the auto slot <name>.bak is made only when it is not
 * there. A pristine stock baseline is never written: its first edit creates the sidecar, which then is the live file.
 */
internal fun vegasWriteLive(
    target: File,
    isStockPath: Boolean,
    sidecarExists: Boolean,
    effectiveSidecar: String?,
    transform: (String) -> String?,
): Boolean {
    if (!target.isFile) return false
    val text = runCatching { target.readText() }.getOrNull() ?: return false
    val next = transform(text) ?: return false
    val autoBak = File(target.absolutePath + ".bak")
    if (!autoBak.isFile) {
        runCatching { java.nio.file.Files.copy(target.toPath(), autoBak.toPath()) }
    }
    if (!target.parentFile.isDirectory) target.parentFile.mkdirs()
    return if (isStockPath && !sidecarExists && effectiveSidecar != null) {
        val s = File(effectiveSidecar)
        if (!s.parentFile.exists() && !s.parentFile.mkdirs()) return false
        if (!s.parentFile.isDirectory) return false
        runCatching { s.writeText(next) }.isSuccess
    } else {
        runCatching { target.writeText(next) }.isSuccess
    }
}

/** The file the game must read after a successful [vegasWriteLive]. */
internal fun vegasWrittenPath(target: File, isStockPath: Boolean, sidecarExists: Boolean, effectiveSidecar: String?): String =
    if (isStockPath && !sidecarExists && effectiveSidecar != null) effectiveSidecar else target.absolutePath

/** Restores [backup] into the live file (blocking); the auto slot is consumed, manual copies stay. */
internal fun vegasRestoreBackup(target: File, backup: File): Boolean {
    if (!target.isFile) return false
    val content = runCatching { backup.readText() }.getOrNull() ?: return false
    if (!runCatching { target.writeText(content) }.isSuccess) return false
    if (backup.name == target.name + ".bak") backup.delete()
    return true
}

/** "Backup now" (blocking): <name>.bak-manual, -2, -3 …, never touched automatically. */
internal fun vegasManualBackup(target: File): Boolean {
    if (!target.isFile) return false
    var bak = File(target.absolutePath + ".bak-manual")
    var n = 2
    while (bak.isFile) { bak = File(target.absolutePath + ".bak-manual-" + n); n++ }
    return runCatching { java.nio.file.Files.copy(target.toPath(), bak.toPath()) }.isSuccess
}

/**
 * Deletes the installed VEGAS build [selected] (blocking; throws on failure). Matches "vegas-<selected>" or any
 * hash-suffixed variant. The build list afterwards, or null when no such build is installed.
 */
internal fun vegasDeleteBuild(context: Context, selected: String): List<String>? {
    val cm = ContentsManager(context)
    cm.syncContents()
    val expectedName = "vegas-$selected"
    val profile = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VEGAS)
        .firstOrNull {
            it.verName == expectedName ||
                it.verName.removePrefix("vegas-") == selected ||
                it.verName == selected
        } ?: return null
    cm.removeContent(profile)
    cm.syncContents()
    return DXVKConfigDialog.loadVegasVersionList(context, cm)
}

/** The first 8 lines of [version]'s notes in the releases feed [body], or null. */
internal fun vegasParseReleaseNotes(body: String?, version: String): List<String>? = runCatching {
    val arr = JSONArray(body)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val tag = o.optString("tag_name", "").removePrefix("vegas-")
        val b = o.optString("body", "")
        tag to b
    }.firstOrNull { it.first == version }?.second
        ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(8)
}.getOrNull()

/** After a new-build check: newer tags (and the installed one) count as covered from now on. */
internal fun vegasHealCatalogTail(context: Context, report: VegasLiveCheck.Report, catalog: VegasKeyCatalog?) {
    if (!report.feedOk || report.newerTags.isEmpty()) return
    val prefs = context.getSharedPreferences(VEGAS_CONFIG_PREFS, Context.MODE_PRIVATE)
    val cur = prefs.getString("catalog_tail", "")?.split('|')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
    var added = false
    for (t in report.newerTags) if (t.isNotBlank() && cur.add(t)) added = true
    val installed = report.installedTag
    if (installed != null && installed.isNotBlank() && cur.add(installed)) added = true
    VegasKeyCatalog.capToMax(cur)
    if (added) {
        prefs.edit().putString("catalog_tail", cur.joinToString("|")).apply()
        catalog?.mergeTailTags(cur.toList())
    }
}

internal fun vegasLevenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
    }
    return dp[a.length][b.length]
}

/** A catalog key one or two letters away from [key], when [key] is in no build at all. */
internal fun vegasTypoFor(catalog: VegasKeyCatalog?, key: String, activeStockTag: String?): String? {
    if (catalog == null) return null
    val bucket = catalog.classify(key, activeStockTag)
    if (bucket != VegasKeyCatalog.Bucket.NOWHERE) return null
    var best: String? = null
    var bestDist = 3
    for (cand in catalog.allKeys()) {
        val d = vegasLevenshtein(key, cand)
        if (d < bestDist) { bestDist = d; best = cand }
    }
    return if (bestDist in 1..2) best else null
}

/** A key row's badge: its knowledge state, its catalog bucket (stock only) and "unverified" when the catalog is behind. */
internal fun vegasBadge(
    knowledge: VegasKeyKnowledge?,
    catalog: VegasKeyCatalog?,
    key: String,
    version: String,
    selectedStock: String?,
    activeStockTag: String?,
    catalogBehind: Boolean,
): String {
    val baseBadge = knowledge?.badgeFor(key, version) ?: "unclassified"
    val bucketPart = if (catalog != null && selectedStock != null)
        when (catalog.classify(key, activeStockTag)) {
            VegasKeyCatalog.Bucket.IN_BUILD -> ""
            VegasKeyCatalog.Bucket.OTHER_BUILD -> if (catalogBehind) "" else " · other"
            VegasKeyCatalog.Bucket.UPSTREAM -> " · upstream"
            VegasKeyCatalog.Bucket.NOWHERE -> " · new"
        }
    else ""
    val badgeRaw = baseBadge + bucketPart + if (catalogBehind) " · unverified" else ""
    return if (badgeRaw.length > 48) badgeRaw.take(45) + "…" else badgeRaw
}

/** The key reference's definition of [key], or "—". */
internal fun vegasKeyDefinition(key: String): String = KEY_DEFINITIONS[key] ?: "—"

/** The key reference's keys: the catalog's, or the definitions' when it is offline. */
internal fun vegasReferenceKeys(catalog: VegasKeyCatalog?): List<String> =
    catalog?.allKeys()?.toList() ?: KEY_DEFINITIONS.keys.toList()

/** The editor's result: [initialConfig] with every choice of the dialog written in. */
internal fun vegasBuildConfig(
    initialConfig: String,
    version: String,
    framerate: String,
    async: Boolean,
    asyncCache: Boolean,
    dxvkType: Int,
    anisotropyLabel: String,
    lodBiasLabel: String,
    vkd3d: String,
    featureLevel: String,
    ddraw: String,
    d7vk: String,
    livePath: String,
): String {
    val cfg = DXVKConfigDialog.parseConfig(initialConfig)
    cfg.put("version", version)
    cfg.put("framerate", StringUtils.parseNumber(framerate))
    cfg.put("async", if (async && dxvkType != DXVKConfigDialog.DXVK_TYPE_NONE) "1" else "0")
    cfg.put("asyncCache", if (asyncCache && dxvkType == DXVKConfigDialog.DXVK_TYPE_GPLASYNC) "1" else "0")
    cfg.put("anisotropy", DXVKConfigDialog.ANISOTROPY_VALUES[VEGAS_ANISOTROPY_LABELS.indexOf(anisotropyLabel).coerceAtLeast(0)])
    cfg.put("lodBias", DXVKConfigDialog.LOD_BIAS_VALUES[VEGAS_LOD_BIAS_LABELS.indexOf(lodBiasLabel).coerceAtLeast(0)])
    cfg.put("vkd3dVersion", vkd3d)
    cfg.put("vkd3dLevel", featureLevel)
    cfg.put("ddrawrapper", StringUtils.parseIdentifier(ddraw))
    cfg.put("d7vkVersion", d7vk)
    cfg.put("dxvkConfigFile", livePath)
    return cfg.toString()
}
