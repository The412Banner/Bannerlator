package com.winlator.star.ui.screens

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.components.Component
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.components.ComponentInstallReturn
import com.winlator.star.components.ComponentInstaller
import com.winlator.star.components.DependencyDetector
import com.winlator.star.components.PrefixInstalledDetector
import com.winlator.star.container.Shortcut
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WinePath
import com.winlator.star.ui.components.DllOverrides
import com.winlator.star.ui.components.EnvVarType
import com.winlator.star.ui.components.KnownEnvVars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ── Win Components (ScWinComponentsTab + RecommendedComponentsSection) ─────────────────────────────

private val P2_WINCOMP_OPTIONS = listOf("Builtin (Wine)", "Native (Windows)")

internal fun xmbWinComponentsMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val res = ctx.resources
    val container = shortcut.container

    val labels = HashMap<String, String>()
    fun labelOf(key: String): String = labels.getOrPut(key) {
        val id = res.getIdentifier(key, "string", ctx.packageName)
        if (id != 0) res.getString(id) else key
    }
    // "key=index,…" exactly as the editor reads it (shortcut override → container).
    fun entries(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        runCatching {
            for (parts in KeyValueSet(shortcut.p2Ex("wincomponents", container.getWinComponents()))) {
                out.add(parts[0] to (parts[1].toIntOrNull() ?: 0))
            }
        }
        return out
    }
    fun select(key: String, index: Int) {
        val list = entries().map { if (it.first == key) it.first to index else it }
        p2Put(xmb, shortcut, "wincomponents", list.joinToString(",") { "${it.first}=${it.second}" })
    }

    // ── Recommended components: same detection, catalog, installed-state and install routing ──
    var recLoading by mutableStateOf(true)
    var recs by mutableStateOf<List<DependencyDetector.Recommendation>>(emptyList())
    var catalog by mutableStateOf<Map<String, Component>>(emptyMap())
    var installed by mutableStateOf<Set<String>>(emptySet())
    var installing by mutableStateOf<String?>(null)
    var progress by mutableStateOf(-1f)
    val installsPrefs = ctx.getSharedPreferences("component_installs", Context.MODE_PRIVATE)
    val installKey = "c${container.id}"
    fun markInstalled(name: String) {
        installed = installed + name
        installsPrefs.edit().putStringSet(installKey, installed).apply()
    }

    xmb.scope.launch {
        val gameExe = runCatching { WinePath.resolveAndroidPath(container, shortcut.path ?: "") }.getOrNull()
        val gameDir = gameExe?.parentFile
        val found = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    gameExe != null -> DependencyDetector.detectForExe(gameExe)
                    gameDir != null -> DependencyDetector.detect(gameDir)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }
        if (found.isEmpty()) { recs = emptyList(); recLoading = false; return@launch }
        val cat = withContext(Dispatchers.IO) {
            runCatching { ComponentCatalog.load() }.getOrDefault(emptyList())
        }.associateBy { it.name }
        catalog = cat
        val recorded = installsPrefs.getStringSet(installKey, emptySet())?.toSet() ?: emptySet()
        val detected = withContext(Dispatchers.IO) {
            runCatching { PrefixInstalledDetector.detect(container) }.getOrDefault(emptySet())
        }
        installed = recorded + detected
        recs = found.filter { cat.containsKey(it.componentName) }.distinctBy { it.componentName }
        recLoading = false
    }

    // Installer-based component (vcredist/.NET): a container session runs it; the app restarts after.
    fun runExecInstall(c: Component) {
        installing = c.name
        progress = -1f
        ComponentInstallReturn.set(ctx, container.id, shortcut.name)
        xmb.scope.launch {
            val r = withContext(Dispatchers.IO) {
                ComponentExecInstaller.startInstall(ctx, container, c) { }
            }
            installing = null
            when (r) {
                is ComponentExecInstaller.Result.Launched -> {}
                is ComponentExecInstaller.Result.Done -> { markInstalled(c.name); ComponentInstallReturn.clear(ctx) }
                is ComponentExecInstaller.Result.Error -> {
                    xmb.toast("Couldn't install ${c.name}: ${r.message}")
                    ComponentInstallReturn.clear(ctx)
                }
            }
        }
    }

    fun onInstallTap(c: Component) {
        if (installing != null) return
        if (!File(container.rootDir, ".wine").isDirectory) {
            xmb.toast("Launch the game once first, then install its components from the game's settings.")
            return
        }
        // Prefer the file-drop `_dll` variant when the catalog has one (no container session).
        val target = catalog["${c.name}_dll"] ?: c
        val reason = if (ComponentExecInstaller.handlesComponent(target)) ComponentExecInstaller.execBlockedReason(target)
                     else ComponentInstaller.blockedReason(target)
        if (reason != null) { xmb.toast(reason); return }
        when {
            ComponentExecInstaller.isExecComponent(target) -> xmb.confirm(
                XmbConfirm("Install ${c.name}", "Opens this game's container and runs the installer (shared by every game on it). Click through it, then close the container.", "Continue")
            ) { runExecInstall(target) }
            ComponentExecInstaller.handlesComponent(target) -> runExecInstall(target)
            else -> {
                installing = c.name // state keyed on the BASE name the row shows
                progress = 0f
                var lastPct = -1
                xmb.scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        ComponentInstaller.install(ctx, container, target) { f ->
                            val pct = (f * 100).toInt()
                            if (pct != lastPct) { lastPct = pct; p2OnMain(xmb) { progress = f } }
                        }
                    }
                    installing = null
                    if (err == null) { markInstalled(c.name); xmb.toast("${c.name} installed") }
                    else xmb.toast("Couldn't install ${c.name}: $err")
                }
            }
        }
    }

    return XmbMenu("Win Components", Icons.Filled.Widgets) {
        val rows = mutableListOf<XmbRow>()

        if (recLoading) {
            rows += XmbRow.Info("recLoading", "Looking for recommended components…", Icons.Filled.Search)
        } else if (recs.isNotEmpty()) {
            // BUNDLED = redist installers the game ships; SHIPPED = loose runtime DLLs (optional).
            val bundled = recs.filter { it.kind == DependencyDetector.Kind.BUNDLED }
            val shipped = recs.filter { it.kind == DependencyDetector.Kind.SHIPPED }
            fun group(key: String, title: String, sub: String, list: List<DependencyDetector.Recommendation>) {
                rows += XmbRow.Header(key, title)
                for (rec in list) {
                    val c = catalog[rec.componentName] ?: continue
                    val isInstalled = c.name in installed
                    val isInstalling = installing == c.name
                    rows += XmbRow.Action(
                        "rec:${c.name}", rec.label,
                        if (isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Download,
                        subtitle = sub,
                        value = when {
                            isInstalling -> if (progress >= 0f) "Installing ${(progress * 100).toInt()}%" else "Installing…"
                            isInstalled -> "Installed"
                            else -> "Install"
                        },
                        disabledReason = if (installing != null && !isInstalling) "Another component is installing" else null,
                    ) { onInstallTap(c) }
                }
            }
            when {
                bundled.isNotEmpty() && shipped.isNotEmpty() -> {
                    group("hRec", "Recommended", "Redistributable this game bundles", bundled)
                    group("hOpt", "Optional", "Ships with the game — install only if it misbehaves", shipped)
                }
                shipped.isNotEmpty() -> group("hOpt", "Optional components", "Ships with the game — install only if it misbehaves", shipped)
                else -> group("hRec", "Recommended components", "Installs into this game's container (shared by its games)", bundled)
            }
        }

        val all = entries().distinctBy { it.first }
        val directx = all.filter { it.first.startsWith("direct") }
        val general = all.filterNot { it.first.startsWith("direct") }
        fun addChoice(e: Pair<String, Int>) {
            rows += XmbRow.Choice(
                "wc:${e.first}", labelOf(e.first), Icons.Filled.Extension, P2_WINCOMP_OPTIONS,
                P2_WINCOMP_OPTIONS.getOrElse(e.second) { P2_WINCOMP_OPTIONS[0] },
            ) { v -> select(e.first, P2_WINCOMP_OPTIONS.indexOf(v).coerceAtLeast(0)) }
        }
        if (directx.isNotEmpty()) {
            rows += XmbRow.Header("hDirectX", "DirectX")
            directx.forEach { addChoice(it) }
        }
        if (general.isNotEmpty()) {
            rows += XmbRow.Header("hGeneral", "General")
            general.forEach { addChoice(it) }
        }
        if (all.isEmpty()) rows += XmbRow.Info("noComponents", "No Windows components configured", Icons.Filled.Info)
        rows
    }
}

// ── Env Vars (ScEnvVarsTab → EnvVarsEditor) ─────────────────────────────────────────────────────────
// Storage is the editor's: one space-separated "NAME=VALUE" string in the "envVars" extra (empty →
// cleared). Only the variable being edited changes, so values other rows manage (audio settings,
// BANNER_AUDIO_DIRECT_MIC) survive untouched.

private data class P2EnvRow(val name: String, val value: String)

/** Every edit re-reads the extra first, so Phase 1 rows that also write envVars never get clobbered. */
private class P2Env(val xmb: XmbScope, val shortcut: Shortcut) {
    fun raw(): String = shortcut.p2Ex("envVars", "")

    fun rows(): List<P2EnvRow> = raw().split(" ")
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else P2EnvRow(part.substring(0, i), part.substring(i + 1))
        }

    fun write(list: List<P2EnvRow>) {
        // Keep every NAMED row — "NAME=" (empty value) is valid and must persist.
        val rendered = list.filter { it.name.isNotBlank() }.joinToString(" ") { "${it.name}=${it.value}" }
        p2Put(xmb, shortcut, "envVars", rendered.ifEmpty { null })
    }

    fun indexOf(list: List<P2EnvRow>, name: String, occ: Int): Int {
        var n = 0
        list.forEachIndexed { i, r -> if (r.name == name) { if (n == occ) return i; n++ } }
        return -1
    }

    fun valueOf(name: String, occ: Int): String? = rows().let { l -> indexOf(l, name, occ).takeIf { it >= 0 }?.let { l[it].value } }

    /** The editor's setRow: values can't contain spaces (variables are space-separated). */
    fun setAt(name: String, occ: Int, value: String) {
        val list = rows().toMutableList()
        val i = indexOf(list, name, occ)
        if (i < 0) return
        list[i] = list[i].copy(value = value.trim().replace(" ", ""))
        write(list)
    }

    fun removeAt(name: String, occ: Int) {
        val list = rows().toMutableList()
        val i = indexOf(list, name, occ)
        if (i < 0) return
        list.removeAt(i)
        write(list)
    }

    /** The editor's putVar: empty removes, else update in place or append. */
    fun putVar(name: String, value: String) {
        val list = rows().toMutableList()
        val i = list.indexOfFirst { it.name == name }
        if (value.isEmpty()) { if (i >= 0) list.removeAt(i) }
        else if (i >= 0) list[i] = list[i].copy(value = value)
        else list.add(P2EnvRow(name, value))
        write(list)
    }

    fun add(name: String, value: String): Boolean {
        val list = rows().toMutableList()
        if (name.isEmpty() || list.any { it.name == name }) return false
        list.add(P2EnvRow(name, value))
        write(list)
        return true
    }

    private val helpCache = HashMap<String, String?>()
    /** env_var_help__<name>, looked up once per name (a missing string is remembered as null too). */
    fun help(name: String): String? {
        if (helpCache.containsKey(name)) return helpCache[name]
        val h = StringUtils.getString(xmb.context, "env_var_help__" + name.lowercase(Locale.ENGLISH))
        helpCache[name] = h
        return h
    }
}

/** Seed for a freshly added variable (EnvVarsEditor.defaultValueFor). */
private fun p2EnvDefault(name: String): String {
    val known = KnownEnvVars.find(name) ?: return ""
    known.defaultValue?.let { return it }
    return when (known.type) {
        EnvVarType.CHECKBOX -> known.options.getOrElse(0) { "0" }
        EnvVarType.SELECT -> known.options.firstOrNull() ?: ""
        else -> ""
    }
}

internal fun xmbEnvVarsMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val env = P2Env(xmb, shortcut)
    // WINEDLLOVERRIDES as it stood when the section opened — what a DLL toggle restores when switched off.
    val dllBaseline = DllOverrides.baselineOf(env.rows().firstOrNull { it.name == DllOverrides.VAR }?.value ?: "")
    var foundDlls by mutableStateOf<List<String>>(emptyList())
    xmb.scope.launch {
        val dir = runCatching { WinePath.resolveAndroidPath(shortcut.container, shortcut.path ?: "")?.parentFile }.getOrNull()
            ?: return@launch
        foundDlls = withContext(Dispatchers.IO) {
            runCatching {
                val present = dir.list()?.map { it.lowercase() }?.toSet() ?: emptySet()
                DllOverrides.PREFER_GAME_FOLDER.filter { "$it.dll" in present }
            }.getOrDefault(emptyList())
        }
    }
    fun overridesNow(): String = env.rows().firstOrNull { it.name == DllOverrides.VAR }?.value ?: ""

    return XmbMenu("Env Vars", Icons.Filled.Extension) {
        val rows = mutableListOf<XmbRow>()
        val list = env.rows()
        val overrides = list.firstOrNull { it.name == DllOverrides.VAR }?.value ?: ""
        // The master switch covers the DLLs detected next to the EXE, else the whole safe list.
        val masterScope = if (foundDlls.isNotEmpty()) foundDlls else DllOverrides.PREFER_GAME_FOLDER
        val allPreferred = DllOverrides.isEnabled(overrides, masterScope)
        val anyPreferred = masterScope.any { DllOverrides.isEnabled(overrides, it) }

        rows += XmbRow.Header("hCompat", "Compatibility")
        rows += XmbRow.Toggle(
            "preferDlls", "Prefer game-folder DLLs", Icons.Filled.Extension, allPreferred,
            subtitle = if (anyPreferred && !allPreferred) "Some game-folder DLLs on — see below"
                       else "Load the game's own " + DllOverrides.PREFER_GAME_FOLDER.joinToString(", ") + " instead of Wine's",
        ) { on ->
            val cur = overridesNow()
            env.putVar(DllOverrides.VAR, if (on) DllOverrides.enable(cur, masterScope) else DllOverrides.disable(cur, dllBaseline, masterScope))
        }
        foundDlls.forEach { dll ->
            rows += XmbRow.Toggle("dll:$dll", "$dll.dll", Icons.Filled.Extension, DllOverrides.isEnabled(overrides, dll),
                subtitle = "Found in the game folder") { on ->
                val cur = overridesNow()
                env.putVar(DllOverrides.VAR, if (on) DllOverrides.enable(cur, dll) else DllOverrides.disable(cur, dllBaseline, dll))
            }
        }

        rows += XmbRow.Header("hVars", "Environment variables")
        if (list.isEmpty()) rows += XmbRow.Info("noVars", "No environment variables set", Icons.Filled.Info)
        val seen = HashMap<String, Int>()
        list.forEach { r ->
            val occ = seen[r.name] ?: 0
            seen[r.name] = occ + 1
            rows += XmbRow.Link(
                if (occ == 0) "var:${r.name}" else "var:${r.name}#$occ", r.name, Icons.Filled.Tune,
                value = r.value.ifEmpty { "(empty)" },
            ) { p2EnvVarMenu(env, r.name, occ) }
        }
        rows += XmbRow.Link("add", "Add variable", Icons.Filled.Add, subtitle = "Pick a known variable or type your own") { p2EnvAddMenu(env) }
        val malformed = env.raw().split(" ").filter { it.isNotBlank() && it.indexOf('=') <= 0 }
        rows += XmbRow.Text(
            "raw", "Edit as text", Icons.Filled.ShortText, env.raw(),
            subtitle = if (malformed.isEmpty()) "NAME=VALUE, separated by spaces"
                       else "Ignored — needs NAME=VALUE: " + malformed.joinToString(", "),
        ) { v -> p2Put(xmb, shortcut, "envVars", v.ifEmpty { null }) }
        rows
    }
}

/** One variable, edited with the control its catalog type calls for, plus Remove. */
private fun p2EnvVarMenu(env: P2Env, name: String, occ: Int): XmbMenu {
    val xmb = env.xmb
    val known = KnownEnvVars.find(name)
    val type = known?.type ?: EnvVarType.TEXT
    return XmbMenu(name, Icons.Filled.Tune) body@{
        val rows = mutableListOf<XmbRow>()
        val cur = env.valueOf(name, occ)
        if (cur == null) {
            rows += XmbRow.Info("gone", "This variable was removed", Icons.Filled.Info)
            return@body rows
        }
        when (type) {
            EnvVarType.CHECKBOX -> {
                val offValue = known?.options?.getOrNull(0) ?: "0"
                val onValue = known?.options?.getOrNull(1) ?: "1"
                val checked = cur == onValue || cur == "1" || cur == "true"
                rows += XmbRow.Toggle("value", name, Icons.Filled.Tune, checked,
                    subtitle = "Stored as $name=${if (checked) onValue else offValue}") { on ->
                    env.setAt(name, occ, if (on) onValue else offValue)
                }
            }
            EnvVarType.SELECT -> {
                val presets = p2Unique(known?.options.orEmpty())
                val opts = if (cur.isNotEmpty() && cur !in presets) presets + cur else presets
                if (opts.isNotEmpty()) {
                    rows += XmbRow.Choice("preset", "Value", Icons.Filled.Tune, opts, cur, subtitle = "Presets — or type any value below") { v ->
                        env.setAt(name, occ, v)
                    }
                }
                rows += XmbRow.Text("custom", "Custom value", Icons.Filled.Edit, cur, subtitle = "Kept exactly as typed (no spaces)") { v ->
                    env.setAt(name, occ, v)
                }
            }
            EnvVarType.SELECT_MULTIPLE -> {
                val selected = cur.split(",").filter { it.isNotBlank() }
                rows += XmbRow.Header("hOptions", "Options")
                known?.options.orEmpty().distinct().forEach { option ->
                    rows += XmbRow.Toggle("opt:$option", option, Icons.Filled.Tune, option in selected) { on ->
                        val next = if (on) selected + option else selected - option
                        env.setAt(name, occ, next.joinToString(","))
                    }
                }
            }
            EnvVarType.NUMBER, EnvVarType.TEXT -> {
                rows += XmbRow.Text("value", "Value", Icons.Filled.Edit, cur, numeric = type == EnvVarType.NUMBER,
                    subtitle = "Spaces are dropped — variables are separated by spaces") { v -> env.setAt(name, occ, v) }
            }
        }
        env.help(name)?.let { rows.add(XmbRow.Info("help", it, Icons.Filled.Info)) }
        rows += XmbRow.Action("remove", "Remove variable", Icons.Filled.Delete, danger = true) {
            xmb.confirm(XmbConfirm("Remove variable", "Remove $name from this game?", "Remove", danger = true)) {
                env.removeAt(name, occ)
                xmb.pop()
            }
        }
        rows
    }
}

/** EnvVarsEditor's add-picker: a typed name (NAME or NAME=VALUE), or any catalog variable not yet set. */
private fun p2EnvAddMenu(env: P2Env): XmbMenu {
    val xmb = env.xmb
    fun addAndOpen(name: String, value: String) {
        if (name.isEmpty()) { xmb.toast("Enter a variable name"); return }
        if (!env.add(name, value)) { xmb.toast("“$name” is already in the list"); return }
        xmb.push(p2EnvVarMenu(env, name, 0))
    }
    return XmbMenu("Add variable", Icons.Filled.Add) {
        val rows = mutableListOf<XmbRow>()
        val existing = env.rows().map { it.name }.toSet()
        rows += XmbRow.Text("name", "Name", Icons.Filled.Add, "", subtitle = "Type NAME or NAME=VALUE",
            placeholder = "e.g. DXVK_FRAME_RATE") { raw ->
            val cleaned = raw.trim().replace(" ", "")
            val eq = cleaned.indexOf('=')
            val name = if (eq >= 0) cleaned.substring(0, eq) else cleaned
            val value = if (eq >= 0) cleaned.substring(eq + 1) else p2EnvDefault(name)
            addAndOpen(name, value)
        }
        val candidates = KnownEnvVars.names.filter { it !in existing }
        if (candidates.isNotEmpty()) rows += XmbRow.Header("hKnown", "Known variables")
        candidates.forEach { n ->
            rows += XmbRow.Action("k:$n", n, Icons.Filled.Tune, subtitle = env.help(n)) { addAndOpen(n, p2EnvDefault(n)) }
        }
        rows
    }
}
