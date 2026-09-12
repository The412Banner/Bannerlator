package com.winlator.star.perf

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Exact auto-revert registry for privileged sysfs writes.
 *
 * Contract (the crux of the root-tier safety design):
 *  - The FIRST time any node is about to be written this session, its CURRENT value is read and
 *    stored VERBATIM ([captureIfAbsent]). A node we never captured is a node we NEVER write on revert.
 *  - On revert, each captured value is written back exactly as read — never a guessed default
 *    ("performance" is NOT restored to "schedutil"; it's restored to whatever was actually there).
 *  - The snapshot + a dirty flag are persisted to disk the moment the first write happens, so a hard
 *    SIGKILL/OOM that skips normal cleanup is repaired on next launch ([onAppStartup]).
 *  - The dirty flag is cleared only after a clean revert.
 *
 * All privileged writes SHOULD go through [applyWrite] so capture-before-write is automatic.
 */
object PerfRevertRegistry {

    private const val TAG = "PerfRevert"
    private const val FILE_NAME = "perf_revert_snapshot.json"
    private const val KEY_DIRTY = "dirty"
    private const val KEY_NODES = "nodes"

    private const val PREFS = "perf_prefs"
    private const val KEY_HARNESS = "harness_proven"

    // path -> original value (verbatim, trimmed of the sysfs trailing newline).
    private val originals = LinkedHashMap<String, String>()
    private var snapshotFile: File? = null
    private var appContext: Context? = null
    private var hooksInstalled = false

    // Safety gate for the two DANGEROUS root toggles (thermal disable, fan max). Was FALSE until the
    // on-device crash-revert harness was proven — now DEVICE-VERIFIED (2026-07-27, Pocket FIT: all 3
    // revert paths proven incl. background revertAll), so it DEFAULTS TRUE. The real safety at runtime
    // is the exact snapshot-revert (device-agnostic: captures & restores whatever the node held) + the
    // always-on exit/background/crash revertAll + the temperature watchdog + the root grant disclaimer.
    // [setHarnessProven]/the pref can still force it false to re-gate if ever needed.
    private val _harnessProven = MutableStateFlow(true)
    val harnessProven: StateFlow<Boolean> = _harnessProven.asStateFlow()

    @Synchronized
    fun onAppStartup(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (snapshotFile == null) snapshotFile = File(ctx.filesDir, FILE_NAME)
        _harnessProven.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HARNESS, true) // default TRUE — harness device-verified 2026-07-27 (Pocket FIT)
        installSafetyNets()
        // The non-root KGSL turbo pin is a device-global driver property with no snapshot file — a
        // hard kill can leave it set. Clear it unconditionally rather than trusting it to have
        // unwound itself. Cheap (one ioctl) and a no-op off Adreno.
        PerfGpuTurbo.clearOnStartup()
        // Repair a snapshot left dirty by a hard kill last session, off the main thread (may need su).
        val file = snapshotFile ?: return
        if (fileLooksDirty(file)) {
            Thread({ restoreFromDiskIfDirty() }, "perf-revert-restore").apply { isDaemon = true }.start()
        }
    }

    /**
     * Capture-then-write. The single entry point privileged toggles should use: it snapshots the
     * node's original value on first touch, persists the dirty snapshot, then performs the guarded
     * write. Returns whether the write succeeded (false when root isn't granted).
     */
    @Synchronized
    fun applyWrite(path: String, value: String): Boolean {
        captureIfAbsent(path)
        return RootManager.writeNode(path, value)
    }

    /**
     * Lazily record [path]'s current value if we haven't already this session. Reads through the root
     * shell; a node we can't read is simply not captured (and therefore never written on revert).
     * Persisting + marking dirty happens here — i.e. "the moment the first write happens", since this
     * always immediately precedes the write in [applyWrite].
     */
    @Synchronized
    fun captureIfAbsent(path: String) {
        if (originals.containsKey(path)) return
        val current = RootManager.readNode(path) ?: run {
            Log.d(TAG, "capture skipped (unreadable): $path")
            return
        }
        originals[path] = current
        persist()
        Log.d(TAG, "captured $path = $current")
    }

    /**
     * Write every captured original back verbatim, then clear the snapshot + dirty flag. Nodes that
     * were never captured are never touched. Safe to call when nothing was captured (no-op).
     */
    @Synchronized
    fun revertAll() {
        // Non-root GPU turbo isn't a sysfs node so it has no snapshot entry — unwind it here so the
        // always-on exit/background/crash paths cover it too.
        PerfGpuTurbo.revert()
        if (originals.isEmpty()) {
            clearPersisted()
            return
        }
        Log.d(TAG, "reverting ${originals.size} node(s)")
        // Restore in reverse capture order so dependent knobs (e.g. min before max) unwind cleanly.
        for ((path, value) in originals.entries.reversed()) {
            RootManager.writeNode(path, value)
        }
        originals.clear()
        clearPersisted()
    }

    /**
     * Revert ONLY the given nodes (restore captured originals verbatim, then forget them). Used when a
     * single root toggle is turned OFF — we must not disturb nodes owned by other still-on toggles.
     * Nodes we never captured are skipped.
     */
    @Synchronized
    fun revertNodes(paths: Collection<String>) {
        val toRevert = paths.filter { originals.containsKey(it) }
        if (toRevert.isEmpty()) return
        // Reverse so co-dependent knobs (min before max) unwind cleanly, same as revertAll.
        for (path in toRevert.reversed()) {
            originals[path]?.let { RootManager.writeNode(path, it) }
            originals.remove(path)
        }
        if (originals.isEmpty()) clearPersisted() else persist()
    }

    /**
     * Flip the safety gate for the dangerous root toggles (thermal disable, fan max). Called ONLY by
     * the maintainer after the on-device crash-revert test passes — never auto-set. Persisted.
     */
    @Synchronized
    fun setHarnessProven(proven: Boolean) {
        _harnessProven.value = proven
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_HARNESS, proven)?.apply()
        Log.d(TAG, "harnessProven = $proven")
    }

    /** Startup repair path: reload a persisted dirty snapshot and revert it, acquiring root if needed. */
    @Synchronized
    fun restoreFromDiskIfDirty() {
        val file = snapshotFile ?: return
        if (!fileLooksDirty(file)) return
        val loaded = try {
            val json = JSONObject(file.readText())
            if (!json.optBoolean(KEY_DIRTY, false)) return
            val nodes = json.optJSONObject(KEY_NODES) ?: JSONObject()
            LinkedHashMap<String, String>().apply {
                nodes.keys().forEach { k -> put(k, nodes.getString(k)) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restore: failed to parse snapshot", t)
            return
        }
        if (loaded.isEmpty()) { clearPersisted(); return }
        if (!RootManager.ensureGrantedBlocking()) {
            Log.w(TAG, "restore: root unavailable, leaving snapshot dirty for next launch")
            return
        }
        Log.d(TAG, "restore: reverting ${loaded.size} node(s) from persisted snapshot")
        for ((path, value) in loaded.entries.reversed()) {
            RootManager.writeNode(path, value)
        }
        originals.clear()
        clearPersisted()
    }

    // ── persistence ────────────────────────────────────────────────────────────────────────────

    private fun persist() {
        val file = snapshotFile ?: return
        try {
            val nodes = JSONObject()
            for ((k, v) in originals) nodes.put(k, v)
            val json = JSONObject().put(KEY_DIRTY, true).put(KEY_NODES, nodes)
            file.writeText(json.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "persist failed", t)
        }
    }

    private fun clearPersisted() {
        val file = snapshotFile ?: return
        try { if (file.exists()) file.delete() } catch (_: Throwable) {}
    }

    private fun fileLooksDirty(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            JSONObject(file.readText()).optBoolean(KEY_DIRTY, false)
        } catch (_: Throwable) {
            false
        }
    }

    // ── crash / shutdown safety nets ─────────────────────────────────────────────────────────────

    private fun installSafetyNets() {
        if (hooksInstalled) return
        hooksInstalled = true

        // Chain, don't replace, any existing uncaught-exception handler.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { revertAll() } catch (t: Throwable) { Log.w(TAG, "crash revert failed", t) }
            previous?.uncaughtException(thread, throwable)
        }

        // Best-effort revert on VM shutdown (covers finish()/System.exit paths a SIGKILL skips).
        try {
            Runtime.getRuntime().addShutdownHook(Thread({
                try { revertAll() } catch (_: Throwable) {}
            }, "perf-revert-shutdown"))
        } catch (t: Throwable) {
            Log.w(TAG, "addShutdownHook failed", t)
        }
    }
}
