package com.winlator.star.perf

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The ONLY place in the app that invokes `su`. Every privileged sysfs write for the power-user
 * performance toggles goes through the single persistent libsu root shell held here; nothing else
 * anywhere should call [com.topjohnwu.superuser.Shell] directly.
 *
 * Phase 0 wiring: the manager probes availability/grant at startup, can fire the superuser prompt on
 * demand ([requestGrant]), and exposes a guarded [writeNode]/[readNode]. No user-facing root UI is
 * wired to it yet — that is a later phase. It is, however, fully callable.
 */
object RootManager {

    private const val TAG = "RootManager"
    private const val PREFS = "perf_prefs"
    // Persisted "the user granted us root at least once" flag. Shell.isAppGrantedRoot() returns null on
    // a cold start (no shell yet), so without this the section greys out every launch. On startup we
    // use this flag to SILENTLY re-open the shell (a remembered Magisk/KernelSU/APatch grant needs no
    // prompt). Cleared when a grant attempt is denied / root is found revoked.
    private const val KEY_GRANTED_ONCE = "root_granted_once"

    private var appContext: Context? = null

    enum class RootState {
        /** Not yet probed. */
        UNKNOWN,

        /** No `su` binary on this device — root is impossible, don't offer it. */
        UNAVAILABLE,

        /** `su` exists but this app has not been granted (or has not yet requested) root. */
        AVAILABLE_NOT_GRANTED,

        /** A root shell was obtained this session. */
        GRANTED,

        /** The user denied the superuser prompt. */
        DENIED,
    }

    init {
        // Keep libsu from creating the main shell as a root shell implicitly; we drive it explicitly.
        // MOUNT_MASTER so writes land in the global mount namespace (sysfs is global anyway, but this
        // avoids per-app-namespace surprises on some Magisk configs). Short timeout so a wedged su
        // prompt can't hang a caller forever.
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }

    private val _state = MutableStateFlow(RootState.UNKNOWN)
    val state: StateFlow<RootState> = _state.asStateFlow()

    val isGranted: Boolean get() = _state.value == RootState.GRANTED

    private val SU_CANDIDATES = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
    )

    private fun hasSuBinary(): Boolean =
        SU_CANDIDATES.any { File(it).exists() } ||
            (System.getenv("PATH")?.split(":")?.any { File(it, "su").exists() } ?: false)

    private fun grantedOnce(): Boolean =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_GRANTED_ONCE, false) ?: false

    private fun setGrantedOnce(v: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_GRANTED_ONCE, v)?.apply()
    }

    /**
     * Cheap, non-prompting probe. Re-run each session (from the Application). Never fires the
     * superuser dialog: it only inspects the su binary and libsu's cached grant knowledge.
     */
    fun probe() {
        val granted = Shell.isAppGrantedRoot() // may be null when unknown; never creates a shell
        _state.value = when {
            granted == true -> RootState.GRANTED
            !hasSuBinary() -> RootState.UNAVAILABLE
            else -> RootState.AVAILABLE_NOT_GRANTED
        }
        Log.d(TAG, "probe -> ${_state.value}")
    }

    /**
     * Triggers the persistent libsu root shell, firing the Magisk/KernelSU/APatch prompt if root has
     * not been granted yet. Suspends on IO; updates [state] to GRANTED or DENIED. Callable now, but
     * no UI is wired to it in this cut.
     */
    suspend fun requestGrant(): Boolean = withContext(Dispatchers.IO) {
        if (!hasSuBinary()) {
            _state.value = RootState.UNAVAILABLE
            return@withContext false
        }
        val root = try {
            Shell.getShell().isRoot // blocking; fires the prompt on first grant
        } catch (t: Throwable) {
            Log.w(TAG, "requestGrant failed", t)
            false
        }
        _state.value = if (root) RootState.GRANTED else RootState.DENIED
        setGrantedOnce(root) // remember a grant so we can silently re-acquire next launch; clear on deny
        Log.d(TAG, "requestGrant -> ${_state.value}")
        root
    }

    /**
     * Blocking grant used only by the revert registry's startup-restore thread: if a snapshot was
     * left dirty by a hard kill last session we HAD root then, so re-acquiring the shell (which
     * Magisk/KernelSU typically satisfy from the remembered grant, no prompt) is legitimate. Never
     * call this from the main thread. Returns whether root is now available.
     */
    fun ensureGrantedBlocking(): Boolean {
        if (isGranted) return true
        if (!hasSuBinary()) {
            _state.value = RootState.UNAVAILABLE
            return false
        }
        val root = try {
            Shell.getShell().isRoot
        } catch (t: Throwable) {
            Log.w(TAG, "ensureGrantedBlocking failed", t)
            false
        }
        _state.value = if (root) RootState.GRANTED else RootState.DENIED
        if (root) setGrantedOnce(true)
        return root
    }

    /**
     * Read a node's current contents through the root shell (used by the revert registry to capture
     * originals before the first write). Returns the trimmed value, or null when not granted / unreadable.
     */
    fun readNode(path: String): String? {
        if (!isGranted) return null
        return try {
            val res = Shell.cmd(PerfCmd.readCmd(path)).exec()
            if (res.isSuccess) PerfCmd.normalizeRead(res.out.joinToString("\n")) else null
        } catch (t: Throwable) {
            Log.w(TAG, "readNode($path) failed", t)
            null
        }
    }

    /**
     * Guarded privileged write. No-ops (and logs) unless root is [RootState.GRANTED]. The caller is
     * responsible for having snapshotted the original via [PerfRevertRegistry.captureIfAbsent] first;
     * this method does not snapshot on its own so that revert-time writes don't recurse.
     */
    fun writeNode(path: String, value: String): Boolean {
        if (!isGranted) {
            Log.d(TAG, "writeNode skipped (state=${_state.value}): $path=$value")
            return false
        }
        return try {
            val res = Shell.cmd(PerfCmd.writeCmd(path, value)).exec()
            if (!res.isSuccess) Log.w(TAG, "writeNode($path=$value) rc=${res.code} ${res.err}")
            res.isSuccess
        } catch (t: Throwable) {
            Log.w(TAG, "writeNode($path=$value) failed", t)
            false
        }
    }

    /**
     * Run a one-shot privileged command through the single root shell (e.g. `am kill-all`). Returns
     * whether it exited successfully. No-ops (returns false, logs) unless root is [RootState.GRANTED].
     *
     * Use this for fire-and-forget privileged ACTIONS that need no snapshot/revert — anything that
     * writes a sysfs node the safety pipeline must be able to unwind goes through [writeNode] instead,
     * so this never touches [PerfRevertRegistry].
     */
    fun runCommand(cmd: String): Boolean {
        if (!isGranted) {
            Log.d(TAG, "runCommand skipped (state=${_state.value}): $cmd")
            return false
        }
        return try {
            val res = Shell.cmd(cmd).exec()
            if (!res.isSuccess) Log.w(TAG, "runCommand($cmd) rc=${res.code} ${res.err}")
            res.isSuccess
        } catch (t: Throwable) {
            Log.w(TAG, "runCommand($cmd) failed", t)
            false
        }
    }

    /**
     * App-startup entry point. Probes root, then hands off to the revert registry so a snapshot left
     * dirty by a hard kill / OOM last session is restored before anything else touches a node.
     * Also chains the crash + shutdown safety nets. Safe to call more than once.
     */
    fun onAppStartup(context: Context) {
        try {
            appContext = context.applicationContext
            probe()
            PerfRevertRegistry.onAppStartup(context.applicationContext)
            // Silently re-acquire a REMEMBERED grant every launch (independent of the dirty-snapshot
            // restore path): if we were granted before, su exists, and the cheap probe didn't already
            // see GRANTED, re-open the shell off the main thread. A remembered Magisk/KernelSU/APatch
            // grant returns root with NO prompt, flipping _state to GRANTED silently — so the Root
            // section no longer greys out on every cold start. Never auto-acquire when the flag is
            // unset (we must never prompt a user who never granted). If root was actually revoked,
            // clear the flag so we stop trying.
            if (_state.value != RootState.GRANTED && grantedOnce() && hasSuBinary()) {
                Thread({
                    try {
                        val ok = ensureGrantedBlocking()
                        Log.d(TAG, "silent re-acquire (grantedOnce flag set): " +
                            if (ok) "GRANTED, no prompt" else "root revoked -> clearing flag")
                        if (!ok) setGrantedOnce(false)
                    } catch (t: Throwable) { Log.w(TAG, "silent re-acquire failed", t) }
                }, "root-silent-reacquire").apply { isDaemon = true }.start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onAppStartup failed", t)
        }
    }
}
