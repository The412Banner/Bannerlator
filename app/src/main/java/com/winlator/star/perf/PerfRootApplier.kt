package com.winlator.star.perf

import android.util.Log

/**
 * Applies (and reverts) the ROOT-tier performance toggles by writing real sysfs nodes through the
 * safety pipeline: every write is snapshot-first via [PerfRevertRegistry.applyWrite], so exit /
 * background / crash restores the captured original verbatim. Turning a single toggle OFF reverts
 * ONLY that toggle's nodes ([PerfRevertRegistry.revertNodes]) — other still-on toggles are untouched.
 *
 * Nothing here writes unless [RootManager] is GRANTED. The two dangerous toggles (thermal disable,
 * fan max) additionally no-op unless [PerfRevertRegistry.harnessProven] — belt-and-braces with the
 * disabled UI, so a broken revert can never cook a device before the on-device test signs off.
 *
 * Node paths come from [PerfNodeResolver] (SoC-aware), never hard-coded per vendor.
 */
object PerfRootApplier {

    private const val TAG = "PerfRootApplier"

    // Canonical keys — used as BOTH the shortcut-extra key (per-game override), the
    // PerformanceSettings global-default key, and the in-game drawer map key.
    const val KEY_CPU_GOVERNOR = "rootCpuGovernorPerf"
    const val KEY_CPU_FREQ_LOCK = "rootCpuFreqLockMax"
    const val KEY_CORES_ONLINE = "rootAllCoresOnline"

    /**
     * GPU max-clock pin. Historically root-only, hence the "root" prefix — the key name is FROZEN for
     * persistence compatibility (existing prefs, shortcut extras and shared community configs all
     * carry it) even though the toggle is no longer root-only. See [applyGpuMaxClockLock].
     */
    const val KEY_GPU_CLOCK_LOCK = "rootGpuMaxClockLock"
    const val KEY_THERMAL_DISABLE = "rootThermalDisable"
    const val KEY_FAN_MAX = "rootFanMax"

    /** Legacy preference key retained only so older installs can discard the unsafe setting. */
    const val KEY_AUTO_DEEP_CLEAN = "rootAutoDeepCleanOnLaunch"

    /** Every key this applier owns, in display order (includes the dual-tier GPU pin). */
    val ROOT_KEYS = listOf(
        KEY_CPU_GOVERNOR, KEY_CPU_FREQ_LOCK, KEY_CORES_ONLINE,
        KEY_GPU_CLOCK_LOCK, KEY_THERMAL_DISABLE, KEY_FAN_MAX,
    )

    /**
     * Keys that genuinely need root — i.e. what the UI shows under "Root performance controls". The
     * GPU pin is excluded: it now has a non-root path ([PerfGpuTurbo]) and is presented alongside the
     * other no-root-needed toggles.
     */
    val ROOT_ONLY_KEYS = ROOT_KEYS - KEY_GPU_CLOCK_LOCK

    /** Keys gated behind the safety harness (dangerous — can overheat if revert is broken). */
    val HARNESS_GATED = setOf(KEY_THERMAL_DISABLE, KEY_FAN_MAX)

    fun isHarnessGated(key: String): Boolean = key in HARNESS_GATED

    /**
     * Whether a key can do anything at all on this device with the privileges we currently hold.
     * Only the GPU pin can be useful without root, and only on Adreno.
     */
    fun isUsable(key: String): Boolean =
        if (key == KEY_GPU_CLOCK_LOCK) RootManager.isGranted || PerfGpuTurbo.isSupported
        else RootManager.isGranted

    // ── generic dispatch ─────────────────────────────────────────────────────────────────────────

    /** Apply or revert one toggle by key. No-ops when root isn't granted / harness-gated-and-unproven. */
    fun apply(key: String, on: Boolean) {
        when (key) {
            KEY_CPU_GOVERNOR -> applyCpuGovernorPerformance(on)
            KEY_CPU_FREQ_LOCK -> applyCpuFreqLockMax(on)
            KEY_CORES_ONLINE -> applyAllCoresOnline(on)
            KEY_GPU_CLOCK_LOCK -> applyGpuMaxClockLock(on)
            KEY_THERMAL_DISABLE -> applyThermalDisable(on)
            KEY_FAN_MAX -> applyFanMax(on)
            else -> Log.w(TAG, "apply: unknown key $key")
        }
    }

    private inline fun guarded(key: String, body: () -> Unit) {
        if (!RootManager.isGranted) { Log.d(TAG, "skip $key: root not granted"); return }
        if (isHarnessGated(key) && !PerfRevertRegistry.harnessProven.value) {
            Log.w(TAG, "skip $key: harness not proven"); return
        }
        try { body() } catch (t: Throwable) { Log.w(TAG, "apply $key failed", t) }
    }

    // ── CPU governor ─────────────────────────────────────────────────────────────────────────────
    fun applyCpuGovernorPerformance(on: Boolean) = guarded(KEY_CPU_GOVERNOR) {
        val nodes = PerfNodeResolver.cpuCores().mapNotNull { it.governor }
        if (on) nodes.forEach { PerfRevertRegistry.applyWrite(it, "performance") }
        else PerfRevertRegistry.revertNodes(nodes)
    }

    // ── CPU frequency lock (pin min to the node's max => runs at top clock) ────────────────────────
    fun applyCpuFreqLockMax(on: Boolean) = guarded(KEY_CPU_FREQ_LOCK) {
        val cores = PerfNodeResolver.cpuCores()
        if (on) {
            for (c in cores) {
                val minNode = c.minFreq ?: continue
                val maxNode = c.maxFreq ?: continue
                val maxVal = RootManager.readNode(maxNode) ?: continue
                PerfRevertRegistry.applyWrite(minNode, maxVal)
            }
        } else {
            PerfRevertRegistry.revertNodes(cores.mapNotNull { it.minFreq })
        }
    }

    // ── Keep all cores online ──────────────────────────────────────────────────────────────────────
    fun applyAllCoresOnline(on: Boolean) = guarded(KEY_CORES_ONLINE) {
        val nodes = PerfNodeResolver.cpuCores().mapNotNull { it.online }
        if (on) nodes.forEach { PerfRevertRegistry.applyWrite(it, "1") }
        else PerfRevertRegistry.revertNodes(nodes)
    }

    // ── GPU max-clock lock — DUAL TIER ─────────────────────────────────────────────────────────────
    // With root: pin via sysfs pwrlevel (+force_clk_on), snapshot-reverted like every other root node.
    // Without root: fall back to the KGSL turbo property ([PerfGpuTurbo]) — Adreno-only, but it means
    // this toggle finally works for users who never granted root. Only ONE path runs, so there is only
    // ever one thing to unwind.
    fun applyGpuMaxClockLock(on: Boolean) {
        if (!RootManager.isGranted) {
            // Non-root path. No harness gate — this can't disable thermal protection.
            try { PerfGpuTurbo.apply(on) } catch (t: Throwable) { Log.w(TAG, "turbo $on failed", t) }
            return
        }
        // Root granted: make sure the non-root pin isn't also left standing, then use sysfs.
        PerfGpuTurbo.revert()
        try {
            val g = PerfNodeResolver.gpu()
            val touched = listOfNotNull(g.minClock, g.forceClkOn)
            if (on) {
                g.maxClock?.let { maxNode ->
                    val maxVal = RootManager.readNode(maxNode)
                    if (maxVal != null && g.minClock != null) PerfRevertRegistry.applyWrite(g.minClock, maxVal)
                }
                g.forceClkOn?.let { PerfRevertRegistry.applyWrite(it, "1") }
            } else {
                PerfRevertRegistry.revertNodes(touched)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apply $KEY_GPU_CLOCK_LOCK failed", t)
        }
    }

    // ── Thermal disable (DANGEROUS, harness-gated) ─────────────────────────────────────────────────
    fun applyThermalDisable(on: Boolean) = guarded(KEY_THERMAL_DISABLE) {
        val nodes = PerfNodeResolver.thermalZoneModes()
        if (on) nodes.forEach { PerfRevertRegistry.applyWrite(it, "disabled") }
        else PerfRevertRegistry.revertNodes(nodes)
    }

    // ── Fan max (DANGEROUS, harness-gated) ─────────────────────────────────────────────────────────
    // Generic path: pwm* -> 255, *_enable -> 1 (manual), cur_state -> its max_state sibling. Some
    // handhelds (AYANEO EC, etc.) expose bespoke EC nodes instead — TODO: per-device fan profiles.
    fun applyFanMax(on: Boolean) = guarded(KEY_FAN_MAX) {
        val nodes = PerfNodeResolver.fanNodes()
        if (on) {
            for (node in nodes) PerfRevertRegistry.applyWrite(node, fanMaxValueFor(node))
        } else {
            PerfRevertRegistry.revertNodes(nodes)
        }
    }

    private fun fanMaxValueFor(node: String): String = when {
        node.endsWith("_enable") -> "1"                       // pwmN_enable: manual control
        node.contains("/pwm") -> "255"                        // pwmN: full duty
        node.endsWith("cur_state") -> readMaxState(node) ?: "1" // cooling device: its max_state
        node.endsWith("_target") -> "255"                     // fanN_target: best-effort
        else -> "1"
    }

    private fun readMaxState(curStatePath: String): String? {
        val maxStatePath = curStatePath.removeSuffix("cur_state") + "max_state"
        return RootManager.readNode(maxStatePath)
    }

    // ── TIER 1 — Drop file caches (one-shot; no revert needed) ─────────────────────────────────────
    // Writes /proc/sys/vm/drop_caches=3: drops reclaimable page/dentry/inode caches. This is LIGHT by
    // design — the kernel refills caches immediately, so almost no RAM appears "freed" to the user. It
    // does not close applications.
    fun freeMemoryNow(): Boolean {
        if (!RootManager.isGranted) return false
        return RootManager.writeNode("/proc/sys/vm/drop_caches", "3")
    }

    /**
     * Apply the resolved effective state of every toggle this applier owns, at game launch.
     * The GPU pin runs even without root (non-root turbo path); the rest need the grant.
     */
    fun applyEffective(effective: Map<String, Boolean>) {
        if (!RootManager.isGranted) {
            apply(KEY_GPU_CLOCK_LOCK, effective[KEY_GPU_CLOCK_LOCK] ?: false)
            return
        }
        for (key in ROOT_KEYS) apply(key, effective[key] ?: false)
    }
}
