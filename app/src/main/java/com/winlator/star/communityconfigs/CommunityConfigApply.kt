package com.winlator.star.communityconfigs

import android.util.Log
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry

/**
 * PHASE 2 apply engine — translate a community config → SURGICALLY merge it into a game's shortcut
 * {@code [Extra Data]} (written per-field via {@code Shortcut.putExtra(name, value)}), never a
 * wholesale overwrite.
 *
 * Key rules (encoded as constants so they can't drift):
 *  - Almost everything is per-game overridable via [OVERRIDABLE_KEYS].
 *  - EXCEPTION: {@code wineVersion}/Proton is CONTAINER-ONLY — advisory only, never written here.
 *  - {@code dxwrapperConfig} ("k=v," list) and {@code graphicsDriverConfig} ("k=v;" list) get a
 *    SUB-FIELD merge: replace ONLY the config-named versions, PRESERVE every other sub-key the user
 *    already has (BCn settings, vulkanVersion, gpuName, HUD, fps, renderer, …).
 *  - Component versions are resolved against what's installed under the files/contents dirs,
 *    minor-version-aware; prefer an installed-compatible version, flag installs for material diffs.
 *
 * Reference translate+validate logic: tools/translate.py in The412Banner/bannerlator-game-configs.
 */
object CommunityConfigApply {

    private const val TAG = "CommunityConfigs"

    /**
     * Shortcut [Extra Data] keys a community config may set directly via putExtra(). Documentation only
     * — the apply loop writes every {@code config.scalars} entry unconditionally (it does NOT gate on
     * this list), so this is the authoritative inventory, not an enforced filter. The second group is
     * the additive {@code bl_ext} overlay ({@link ConfigExporter#BL_EXT_KEYS}) that lifts coverage from
     * the {@code pc_*}-only set to ~36/39.
     */
    val OVERRIDABLE_KEYS: List<String> = listOf(
        "dxwrapper", "dxwrapperConfig", "graphicsDriver", "graphicsDriverConfig",
        "emulator", "fexcoreVersion", "fexcorePreset", "box64Version", "box64Preset",
        "audioDriver", "cpuList", "envVars", "screenSize", "wincomponents", "renderer",
        "fpsLimiter", "frameGenEngine", "sfCompatMode", "inputType", "execArgs",
        // bl_ext overlay additions:
        "renderScale", "fullscreenMode", "sharpnessEffect", "sharpnessLevel", "sharpnessDenoise",
        "reshadeLoadout", "reshadeMode", "reshadeParams", "reshadeEffect", "startupSelection",
        "exclusiveXInput", "disableXinput", "simTouchScreen", "numControllers", "controlsProfile",
        "midiSoundFont", "lc_all", "autoCloseOnExit", "fpsLimiterEnabled",
    )

    /** Advisory only — surfaced to the user but NEVER written to the shortcut (container-scoped). */
    val ADVISORY_ONLY_KEYS: List<String> = listOf("wineVersion")

    /**
     * Historical documentation of the dxwrapper/graphicsDriver sub-keys a merge replaces. The
     * dxwrapperConfig merge is no longer limited to this set — a Bannerlator-origin config carries the
     * FULL dxwrapperConfig, so ANY of its sub-keys (async, vulkanVersion, and any other DXVK tuning knob)
     * may be written. It is still a preserve-others merge: only the sub-keys the config actually carries
     * are replaced; every sub-key the target already has and the config does not is preserved.
     */
    val MERGE_SUBKEYS: List<String> = listOf("version", "vkd3dVersion", "async", "graphics")

    /**
     * A component the config wants but that isn't installed AND is installable through the app's
     * single-type download sheet (DXVK / VKD3D / FEXCore). Carries enough to (a) open the right sheet
     * and (b) SURGICALLY write the resolved version sub-field back after the user installs a build.
     * Proton / Turnip are NOT here — they stay plain advisory strings (not installable via the sheet).
     *
     * @param type    which single-type download sheet to open.
     * @param label   the same human-readable advisory line shown when there's no button.
     * @param wanted  the version the config requested (re-resolved against installs after download).
     * @param current the shortcut's current sub-field value, or null when it has none.
     */
    data class MissingComponent(
        val type: ContentProfile.ContentType,
        val label: String,
        val wanted: String,
        val current: String?,
    )

    /**
     * A Turnip/adrenotools GPU driver the config wants but that isn't installed. Unlike
     * [MissingComponent] this has no [ContentProfile.ContentType] (there is no adrenotools content
     * type) — it is installed via the adrenotools driver-download subsystem (5 remote repos), not the
     * single-type content sheet. Only emitted on Adreno GPUs (an adrenotools driver is meaningless on
     * Mali/other); on non-Adreno the requested driver stays a plain advisory line.
     *
     * @param wanted  the driver the config requested, e.g. "Mesa Turnip v25.0.0" (matched against the
     *                remote driver catalog by mesa version; the installed driver id is what gets written).
     * @param current the shortcut's current graphicsDriverConfig version sub-field, or null when none.
     */
    data class MissingDriver(
        val wanted: String,
        val current: String?,
    )

    /**
     * The reviewable, human-readable outcome of an apply — what actually changed, what needs an
     * install (structured [missingComponents], each installable via the download sheet), and
     * advisory-only notes ([advisories]: Proton / Turnip, plain text, no button). [ok] is false only
     * on an outright fetch/translate failure; an apply that changed nothing but produced advisories is
     * still [ok].
     */
    data class ConfigApplyResult(
        val ok: Boolean,
        val message: String,
        val changed: List<String> = emptyList(),
        val missingComponents: List<MissingComponent> = emptyList(),
        val advisories: List<String> = emptyList(),
        val missingDrivers: List<MissingDriver> = emptyList(),
    )

    /**
     * Surgically merge [config] into [shortcut] and persist. Resolves component versions against
     * [installed]. Returns a summary of exactly what changed plus install/Proton advisories. Safe to
     * call off the main thread (blocking file write via {@code saveData()}).
     *
     * @param containerWineVersion the container's Proton/Wine version, for the container-only advisory.
     * @param isAdreno             whether the running GPU is Adreno; only then is an unresolved Turnip
     *                             driver offered as an installable [MissingDriver] (else plain advisory).
     */
    fun apply(
        shortcut: Shortcut,
        config: ShortcutConfig,
        installed: InstalledComponents,
        containerWineVersion: String?,
        isAdreno: Boolean,
    ): ConfigApplyResult {
        // Write-through: build() computes the diff and pushes each field via putExtra as it goes,
        // exactly as before; then persist once if anything actually changed.
        val result = build(shortcut, config, installed, containerWineVersion, isAdreno) { key, value ->
            shortcut.putExtra(key, value)
        }
        if (result.changed.isNotEmpty()) {
            try {
                shortcut.saveData()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist shortcut after apply", e)
                return result.copy(
                    ok = false,
                    message = "Failed to save shortcut: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
        return result
    }

    /**
     * Non-mutating twin of [apply]: computes the exact same diff ([ConfigApplyResult.changed],
     * [ConfigApplyResult.missingComponents], [ConfigApplyResult.missingDrivers],
     * [ConfigApplyResult.advisories]) WITHOUT writing anything to [shortcut] and WITHOUT persisting.
     * Its {@code changed} lines describe what WOULD change ("dxwrapperConfig.version -> 2.7"); the
     * shortcut is left untouched. Shares [build] with [apply] so the two can never drift — the only
     * difference is that here the commit sink is a no-op and there is no {@code saveData()}. Safe on
     * any thread (pure reads).
     */
    fun preview(
        shortcut: Shortcut,
        config: ShortcutConfig,
        installed: InstalledComponents,
        containerWineVersion: String?,
        isAdreno: Boolean,
    ): ConfigApplyResult =
        build(shortcut, config, installed, containerWineVersion, isAdreno) { _, _ -> }

    /**
     * Shared change-computation for [apply] and [preview]. Reads the shortcut's current values (each
     * field is read exactly once, before it is written, and no field is re-read after a write), decides
     * what would change, and routes every intended write through [commit] — a write-through putExtra for
     * [apply], a no-op for [preview]. Does NOT persist; the caller owns {@code saveData()}.
     */
    private fun build(
        shortcut: Shortcut,
        config: ShortcutConfig,
        installed: InstalledComponents,
        containerWineVersion: String?,
        isAdreno: Boolean,
        commit: (key: String, value: String) -> Unit,
    ): ConfigApplyResult {
        val changed = ArrayList<String>()
        val advisories = ArrayList<String>()
        val missing = ArrayList<MissingComponent>()
        val missingDrivers = ArrayList<MissingDriver>()

        // FEXCore is coupled to the emulator scalar: only switch to fexcore if a compatible build is
        // installed, otherwise we'd point the shortcut at a translator that isn't there.
        val wantedEmulator = config.scalars["emulator"]
        val wantedFex = config.scalars["fexcoreVersion"]
        var writeEmulatorFex = true
        if (wantedEmulator == "fexcore" && !wantedFex.isNullOrBlank()) {
            when (val r = installed.resolve("FEXCore", wantedFex, shortcut.getExtra("fexcoreVersion"))) {
                is InstalledComponents.Resolution.Match -> {
                    setScalar(shortcut, "emulator", "fexcore", changed, commit)
                    val token = r.token ?: wantedFex
                    setScalar(shortcut, "fexcoreVersion", token, changed, commit)
                }
                InstalledComponents.Resolution.Missing -> {
                    writeEmulatorFex = false
                    missing.add(
                        MissingComponent(
                            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                            "config uses FEXCore $wantedFex — not installed; install it to switch x86 translator.",
                            wantedFex,
                            shortcut.getExtra("fexcoreVersion").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }

        // Plain scalars — direct putExtra, preserving everything else. (emulator/fexcoreVersion handled above.)
        for ((key, value) in config.scalars) {
            if (key == "emulator" || key == "fexcoreVersion") {
                if (!writeEmulatorFex) continue
                if (key == "emulator" && value != "fexcore") setScalar(shortcut, key, value, changed, commit)
                continue
            }
            setScalar(shortcut, key, value, changed, commit)
        }

        // dxwrapperConfig — comma k=v list. version / vkd3dVersion take the component-resolution path
        // (they map to installed builds); every OTHER carried sub-key (async, vulkanVersion, …) is merged
        // directly. Still a preserve-others merge: sub-keys the config doesn't carry are left untouched.
        val dxwCurrent = currentOrDefault(shortcut, "dxwrapperConfig", shortcut.container?.getDXWrapperConfig())
        val dxwUpdates = LinkedHashMap<String, String>()
        config.dxwrapperConfig["version"]?.let { wanted ->
            resolveInto(
                "DXVK", "DXVK", wanted, subValue(dxwCurrent, ",", "version"), installed, advisories,
                missing, ContentProfile.ContentType.CONTENT_TYPE_DXVK,
            )?.let { dxwUpdates["version"] = it }
        }
        config.dxwrapperConfig["vkd3dVersion"]?.let { wanted ->
            resolveInto(
                "VKD3D", "VKD3D", wanted, subValue(dxwCurrent, ",", "vkd3dVersion"), installed, advisories,
                missing, ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
            )?.let { dxwUpdates["vkd3dVersion"] = it }
        }
        // Every other carried sub-key (async, vulkanVersion, and any DXVK tuning knob) — direct merge.
        for ((k, v) in config.dxwrapperConfig) {
            if (k == "version" || k == "vkd3dVersion") continue
            dxwUpdates[k] = v
        }
        if (dxwUpdates.isNotEmpty()) {
            val merged = mergeList(dxwCurrent, dxwUpdates, ",")
            if (merged != dxwCurrent) {
                commit("dxwrapperConfig", merged)
                dxwUpdates.forEach { (k, v) -> changed.add("dxwrapperConfig.$k → $v") }
            }
        }

        // graphicsDriverConfig — semicolon k=v list; merge ONLY the driver version, preserve the rest.
        // Turnip/adrenotools has no single-type content sheet: an unresolved driver becomes a structured
        // [MissingDriver] (installable via the adrenotools driver browser) ONLY on Adreno; on other GPUs
        // an adrenotools driver is meaningless, so it stays a plain advisory.
        val gdcCurrent = currentOrDefault(shortcut, "graphicsDriverConfig", shortcut.container?.getGraphicsDriverConfig())
        config.graphicsDriverConfig["version"]?.let { wanted ->
            val current = subValue(gdcCurrent, ";", "version")
            when (val r = installed.resolve("adrenotools", wanted, current)) {
                is InstalledComponents.Resolution.Match -> {
                    val token = r.token // null = already aligned; nothing to write.
                    if (token != null) {
                        val merged = mergeList(gdcCurrent, linkedMapOf("version" to token), ";")
                        if (merged != gdcCurrent) {
                            commit("graphicsDriverConfig", merged)
                            changed.add("graphicsDriverConfig.version → $token")
                        }
                    }
                }
                InstalledComponents.Resolution.Missing -> {
                    val have = current?.takeIf { it.isNotBlank() }
                    if (isAdreno) {
                        missingDrivers.add(MissingDriver(wanted, have))
                    } else {
                        val had = have?.let { " you have $it —" } ?: ""
                        advisories.add("config wants Turnip $wanted;$had install it to honor.")
                    }
                }
            }
        }

        // Proton / wineVersion — container-only, advisory only. Suppress the nudge when the container
        // ALREADY runs the same base Proton: the config's advisory value is normalized (e.g.
        // "proton-11.0-arm64ec"), so normalize the container's raw wineVersion the same way before
        // comparing — otherwise "proton-11.0-arm64ec" vs "Proton-11.0-1-arm64ec-4" reads as a mismatch
        // when it's the identical build (a config exported from this very container tripped exactly that).
        config.advisories["wineVersion"]?.let { proton ->
            val containerKey = containerWineVersion?.takeIf { it.isNotBlank() }?.let { ConfigTranslator.protonKey(it) }
            if (containerKey == null || !containerKey.equals(proton, ignoreCase = true)) {
                val have = containerWineVersion?.takeIf { it.isNotBlank() } ?: "your container's"
                advisories.add("config used $proton (container-only) — your container is $have; change it in the container editor if needed.")
            }
        }

        val message = when {
            changed.isEmpty() && advisories.isEmpty() && missing.isEmpty() && missingDrivers.isEmpty() ->
                "Already aligned — your shortcut matches this config; nothing to change."
            changed.isEmpty() ->
                "Nothing changed — this config needs components you don't have installed yet."
            else -> "Applied ${changed.size} change${if (changed.size == 1) "" else "s"} to \"${shortcut.name}\"."
        }
        return ConfigApplyResult(true, message, changed, missing, advisories, missingDrivers)
    }

    /**
     * Post-install fixup: after the user installs a build for a [mc], re-resolve it against [installed]
     * and, if it now resolves, SURGICALLY write the matching version sub-field into [shortcut] using the
     * same merge as [apply] (preserving every other sub-field). Returns true when the shortcut now
     * honors the requested component (whether that needed a write or it was already aligned), false when
     * it still isn't installed. Safe off the main thread (blocking saveData()).
     */
    fun applyResolvedComponent(
        shortcut: Shortcut,
        mc: MissingComponent,
        installed: InstalledComponents,
    ): Boolean {
        val type = installedType(mc.type) ?: return false
        val r = installed.resolve(type, mc.wanted, mc.current)
        if (r !is InstalledComponents.Resolution.Match) return false
        val token = r.token // null = already aligned; resolved, nothing to write.
        if (token != null) {
            when (mc.type) {
                ContentProfile.ContentType.CONTENT_TYPE_DXVK -> {
                    val cur = currentOrDefault(shortcut, "dxwrapperConfig", shortcut.container?.getDXWrapperConfig())
                    shortcut.putExtra("dxwrapperConfig", mergeList(cur, linkedMapOf("version" to token), ","))
                }
                ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> {
                    val cur = currentOrDefault(shortcut, "dxwrapperConfig", shortcut.container?.getDXWrapperConfig())
                    shortcut.putExtra("dxwrapperConfig", mergeList(cur, linkedMapOf("vkd3dVersion" to token), ","))
                }
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> {
                    shortcut.putExtra("emulator", "fexcore")
                    shortcut.putExtra("fexcoreVersion", token)
                }
                else -> return false
            }
            try {
                shortcut.saveData()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist shortcut after component install", e)
                return false
            }
        }
        return true
    }

    /**
     * Post-install fixup for a [MissingDriver]: SURGICALLY write the just-installed adrenotools
     * [driverId] into the shortcut's {@code graphicsDriverConfig} version sub-field (preserving every
     * other sub-field) and persist. Mirrors [applyResolvedComponent] but for the Turnip/adrenotools
     * driver — the installed driver id IS the version token. Returns true on a successful save. Safe
     * off the main thread (blocking saveData()).
     */
    fun applyResolvedDriver(shortcut: Shortcut, driverId: String): Boolean {
        if (driverId.isBlank()) return false
        val cur = currentOrDefault(shortcut, "graphicsDriverConfig", shortcut.container?.getGraphicsDriverConfig())
        shortcut.putExtra("graphicsDriverConfig", mergeList(cur, linkedMapOf("version" to driverId), ";"))
        return try {
            shortcut.saveData()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist shortcut after driver install", e)
            false
        }
    }

    /**
     * The smart shortlist for a [MissingDriver] over the flattened remote driver catalog (all 5 repos):
     *  - [exactMatches] is EVERY entry whose parsed version equals the wanted X.Y.Z, ordered by source
     *    name — different repos' builds of the same mesa version behave differently on-device, so each
     *    is offered as its own quick-install option (they are NOT collapsed to one).
     *  - [closest] is up to [limit] nearest OTHER versions by mesa-version distance.
     * Entries are deduped by identical downloadUrl only (a repo listing the same file twice), never by
     * version. When the wanted string has no parseable version, both lists are empty (Browse-all only).
     */
    data class DriverShortlist(
        val exactMatches: List<RemoteDriverEntry>,
        val closest: List<RemoteDriverEntry>,
    )

    /** Rank remote driver [entries] against the wanted Turnip version (e.g. "Mesa Turnip v25.0.0"). */
    fun rankDrivers(
        wanted: String,
        entries: List<RemoteDriverEntry>,
        limit: Int = 3,
    ): DriverShortlist {
        val wantKey = versionKey(wanted) ?: return DriverShortlist(emptyList(), emptyList())
        // Dedup identical files (same repo listing a build twice); same version across repos stays distinct.
        val deduped = entries.distinctBy { it.downloadUrl }
        val keyed = deduped.mapNotNull { e -> versionKey(e.displayName)?.let { e to it } }
        val exactMatches = keyed
            .filter { it.second.contentEquals(wantKey) }
            .map { it.first }
            .sortedBy { it.source }
        val exactUrls = exactMatches.map { it.downloadUrl }.toSet()
        val closest = keyed.asSequence()
            .filter { it.first.downloadUrl !in exactUrls }
            .map { it.first to versionDistance(wantKey, it.second) }
            .filter { it.second != Long.MAX_VALUE }
            .sortedWith(compareBy({ it.second }, { it.first.displayName }, { it.first.source }))
            .map { it.first }
            .take(limit)
            .toList()
        return DriverShortlist(exactMatches, closest)
    }

    /**
     * The smart shortlist for a [MissingComponent]: an [exact] downloadable profile (verName
     * normalizes-equal to the wanted version) when one exists, plus the [closest] few remaining
     * profiles ranked by version distance (same major preferred). Fed by the inline installer on the
     * "Config applied" screen so the user can install without opening the full version menu.
     */
    data class VersionShortlist(
        val exact: ContentProfile?,
        val closest: List<ContentProfile>,
    )

    /**
     * Rank downloadable [profiles] against the requested [wanted] version:
     *  - [VersionShortlist.exact] is the normalize-equal profile, if any.
     *  - [VersionShortlist.closest] is up to [limit] remaining profiles, nearest first by version
     *    distance, with the same major always ahead of a different major.
     *
     * Dotted versions (2.4.1) rank by major/minor/patch distance; date or build-style FEXCore
     * versions (Fex_20260428, FEX-2607) rank by the numeric build's absolute distance. When the
     * wanted version can't be parsed, [closest] is empty (caller falls back to "Browse all versions").
     */
    fun rankVersions(
        wanted: String,
        profiles: List<ContentProfile>,
        limit: Int = 3,
    ): VersionShortlist {
        if (profiles.isEmpty()) return VersionShortlist(null, emptyList())
        val wantNorm = normalizeVer(wanted)
        val exact = profiles.firstOrNull { normalizeVer(it.verName) == wantNorm }
        val wantKey = versionKey(wanted)
        val closest = profiles.asSequence()
            .filter { it !== exact }
            .mapNotNull { p -> versionKey(p.verName)?.let { p to versionDistance(wantKey, it) } }
            .filter { it.second != Long.MAX_VALUE }
            .sortedBy { it.second }
            .map { it.first }
            .take(limit)
            .toList()
        return VersionShortlist(exact, closest)
    }

    /** Loose equality for version strings: case-insensitive, whitespace-stripped, leading "v" dropped. */
    private fun normalizeVer(v: String): String =
        v.trim().lowercase().removePrefix("v").replace(" ", "")

    // Parsed comparison key. First element tags the format (0 = dotted x.y.z, 1 = single build/date
    // number); remaining elements are the numeric components. Null when nothing numeric parses.
    private fun versionKey(v: String): LongArray? {
        val dotted = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(v)
        if (dotted != null) {
            val maj = dotted.groupValues[1].toLongOrNull() ?: 0L
            val min = dotted.groupValues[2].toLongOrNull() ?: 0L
            val pat = dotted.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
            return longArrayOf(0L, maj, min, pat)
        }
        // FEX/GameHub date builds are tagged YYMM (year's last two digits + month). Reduce any date form
        // to that 4-digit tag first, so a config's full date ("Fex-20260103") ranks nearest the monthly
        // build ("2601") instead of being ~20 million apart from it as raw integers.
        InstalledComponents.fexBuildTag(v)?.toLongOrNull()?.let { return longArrayOf(1L, it) }
        val big = Regex("\\d+").findAll(v).map { it.value }.maxByOrNull { it.length }?.toLongOrNull()
            ?: return null
        return longArrayOf(1L, big)
    }

    // Distance between two version keys; MAX_VALUE when formats differ or either is unparseable.
    private fun versionDistance(a: LongArray?, b: LongArray?): Long {
        if (a == null || b == null || a[0] != b[0]) return Long.MAX_VALUE
        return if (a[0] == 0L) {
            // Weight major far above minor above patch so same-major always sorts ahead.
            Math.abs(a[1] - b[1]) * 1_000_000L + Math.abs(a[2] - b[2]) * 1_000L + Math.abs(a[3] - b[3])
        } else {
            Math.abs(a[1] - b[1])
        }
    }

    /** Public alias of [installedType] — lets the UI map a component type to its install-dir key. */
    fun installedTypeKey(type: ContentProfile.ContentType): String? = installedType(type)

    /** Map a sheet-installable [ContentProfile.ContentType] to its [InstalledComponents] type key. */
    private fun installedType(type: ContentProfile.ContentType): String? = when (type) {
        ContentProfile.ContentType.CONTENT_TYPE_DXVK -> "DXVK"
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D -> "VKD3D"
        ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> "FEXCore"
        else -> null
    }

    /**
     * Set a scalar extra, recording the change only when it actually differs from the current value.
     * The write is routed through [commit] so [preview] can compute the same diff without mutating.
     */
    private fun setScalar(
        shortcut: Shortcut,
        key: String,
        value: String,
        changed: MutableList<String>,
        commit: (String, String) -> Unit,
    ) {
        if (shortcut.getExtra(key) == value) return
        commit(key, value)
        changed.add("$key → $value")
    }

    /**
     * Resolve a requested component version; return the value to write into the sub-field, or null to
     * skip (either already aligned, or not installed → an advisory is added).
     */
    private fun resolveInto(
        label: String,
        installedType: String,
        wanted: String,
        current: String?,
        installed: InstalledComponents,
        advisories: MutableList<String>,
        missing: MutableList<MissingComponent>? = null,
        missingType: ContentProfile.ContentType? = null,
    ): String? = when (val r = installed.resolve(installedType, wanted, current)) {
        is InstalledComponents.Resolution.Match -> r.token // null = already aligned
        InstalledComponents.Resolution.Missing -> {
            val have = current?.takeIf { it.isNotBlank() }?.let { " you have $it —" } ?: ""
            val text = "config wants $label $wanted;$have install it to honor."
            // Sheet-installable types get a structured entry (→ Install button); anything without a
            // missingType falls through to a plain advisory line. (Turnip has its own MissingDriver path.)
            if (missing != null && missingType != null) {
                missing.add(MissingComponent(missingType, text, wanted, current))
            } else {
                advisories.add(text)
            }
            null
        }
    }

    /** Read a shortcut extra, falling back to the container default when the shortcut has no override. */
    private fun currentOrDefault(shortcut: Shortcut, key: String, default: String?): String {
        val v = shortcut.getExtra(key)
        return if (v.isNotBlank()) v else (default ?: "")
    }

    /** Value of one sub-key inside a delimited {@code k=v} list, or null when absent. */
    private fun subValue(list: String, delim: String, key: String): String? {
        if (list.isBlank()) return null
        for (part in list.split(delim)) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == key) return part.substring(eq + 1)
        }
        return null
    }

    /**
     * Merge [updates] into a delimited {@code k=v} list, REPLACING those keys in place and APPENDING
     * any new ones, while preserving order and every other sub-field the user already has.
     */
    private fun mergeList(list: String, updates: Map<String, String>, delim: String): String {
        val order = ArrayList<String>()
        val map = LinkedHashMap<String, String>()
        if (list.isNotBlank()) {
            for (part in list.split(delim)) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq < 0) { order.add(part); map[part] = " RAW"; continue }
                val k = part.substring(0, eq)
                order.add(k)
                map[k] = part.substring(eq + 1)
            }
        }
        for ((k, v) in updates) {
            if (!map.containsKey(k)) order.add(k)
            map[k] = v
        }
        return order.joinToString(delim) { k ->
            val v = map[k]
            if (v == " RAW") k else "$k=$v"
        }
    }
}
