package com.winlator.star.store

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.winlator.star.store.blsteam.CaBundleExtractor
import java.io.File

/**
 * Supervisor for the app's genuine Steam session host — the `bl-steam-host` executable
 * (packaged as `libblsteamhost.so`, exec'd from `nativeLibraryDir` like the unpack tools) that
 * loads Valve's `androidarm64` `libsteamclient.so` ([SteamHostComponent]) in its OWN process,
 * logs the account in with the engine's refresh token and serves the `Steam3Master` /
 * `SteamClientService` loopback listeners for a `launchMode=AppSteam` game.
 *
 * Why a separate process: the client's private vtables can move with a Valve build; a bad slot
 * or a Valve-side abort then kills the host, never the app (GameNative runs its bootstrap the
 * same way). The host reports over the [SteamAgentChannel] the launch activity already owns
 * (`BL_AGENT_PORT`; events `started` / `logged_in` / `login_failed` / `appinfo` / `ownership` /
 * `host_ready` / `session_lost` / `shutdown`), so the launch overlay's reassurance lines and
 * failure cards work unchanged.
 *
 * Credentials go to the host as environment variables (never argv), which the host scrubs from
 * its environment block immediately and makes itself non-dumpable — nothing in this class logs
 * them (the token is a registered [SteamLogRedactor] secret regardless).
 *
 * One host at a time (the loopback ports are fixed). [start] reaps a stale host left by a crash
 * before launching a new one; [stop] sends SIGTERM (the host logs off cleanly) and falls back to
 * destroy.
 */
object SteamHost {

    private const val TAG = "BH_STEAMHOST"
    private const val BINARY = "libblsteamhost.so"
    private const val PID_PREFS = "steam_host"

    /** Everything the host needs for one launch. Built by [AppSteamLauncher]. */
    class Config(
        val appId: Int,
        /** Per-SteamID HOME for the client (`<HOME>/Steam/config/…` = its persistent state). */
        val home: File,
        /** Where the host's stdout goes (the per-launch host log, collected into the SteamLite bundle). */
        val logFile: File,
        val steam3Master: String,
        val steamClientService: String,
        /** Agent-channel port the host connects its status stream to (0 = none). */
        val agentPort: Int,
        /** SetPersonaState(Online) after logon — only when the user opted into friends/chat. */
        val persona: Boolean,
        /** `STEAM_BASE_FOLDER` / `BREAKPAD_DUMP_LOCATION` etc. passed through to the client. */
        val extraEnv: Map<String, String>,
    )

    @Volatile private var process: Process? = null
    @Volatile private var pid: Int = 0
    @Volatile var lastConfig: Config? = null
        private set

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BINARY)
    fun isAvailable(context: Context): Boolean = binary(context).isFile
    fun isAlive(): Boolean = process?.isAlive == true

    /** Per-SteamID state root: `{filesDir}/imagefs/.steamhost/<steamid64>/`. */
    fun stateDir(context: Context, steamId64: Long): File =
        File(context.filesDir, "imagefs/.steamhost/$steamId64")

    /**
     * Launch the host. MUST be called off the main thread. Returns false (with the reason logged)
     * when the binary / Valve client / credentials are missing or the process could not start.
     * The caller waits for the `logged_in` / `host_ready` events on its agent channel.
     */
    fun start(context: Context, cfg: Config, token: String, account: String, steamId64: Long): Boolean {
        val app = context.applicationContext
        val bin = binary(app)
        if (!bin.isFile) { Log.w(TAG, "host binary missing at ${bin.absolutePath}"); return false }
        val lib = SteamHostComponent.libSteamClient(app)
        if (!lib.isFile) { Log.w(TAG, "Valve client not installed (${lib.absolutePath})"); return false }
        if (token.isEmpty() || account.isEmpty() || steamId64 == 0L) { Log.w(TAG, "no credentials for the host"); return false }
        stop("restart")
        reapStale(app)

        cfg.home.mkdirs()
        cfg.logFile.parentFile?.mkdirs()
        cfg.logFile.delete()
        val cacert = runCatching { CaBundleExtractor.ensureBundle(app) }.getOrDefault(File(app.filesDir, "blsteam_cacert.pem").absolutePath)

        val pb = ProcessBuilder(bin.absolutePath)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfg.logFile))
        pb.directory(cfg.home)
        val env = pb.environment()
        // Start from a clean slate: nothing of the app process's own env (Wine-ish leftovers) leaks.
        env.clear()
        env["PATH"] = "/system/bin"
        env["HOME"] = cfg.home.absolutePath
        env["TMPDIR"] = File(app.filesDir, "imagefs/usr/tmp").absolutePath
        // libc++_shared.so next to the binary + Valve's siblings next to libsteamclient.so.
        env["LD_LIBRARY_PATH"] = bin.parentFile!!.absolutePath + ":" + lib.parentFile!!.absolutePath
        env["BL_STEAM_HOST_LIB"] = lib.absolutePath
        env["BL_STEAM_HOST_HOME"] = cfg.home.absolutePath
        env["BL_STEAM_HOST_CACERT"] = cacert
        env["BL_STEAM_HOST_LIB_VERSION"] = SteamHostComponent.installedVersion(app)
        env["BL_STEAM_HOST_APPID"] = cfg.appId.toString()
        env["BL_STEAM_HOST_PERSONA"] = if (cfg.persona) "1" else "0"
        env["Steam3Master"] = cfg.steam3Master
        env["SteamClientService"] = cfg.steamClientService
        if (cfg.agentPort > 0) env["BL_AGENT_PORT"] = cfg.agentPort.toString()
        // The client's own identity gate ("preallocated environment variable 'SteamUser' not
        // found") — same value the game gets.
        env["SteamUser"] = account
        env["SteamAppUser"] = account
        for ((k, v) in cfg.extraEnv) env[k] = v
        // Credentials last; the host scrubs them from its environment block on startup.
        env["BL_STEAM_TOKEN"] = token
        env["BL_STEAM_ACCOUNT"] = account
        env["BL_STEAM_STEAMID64"] = steamId64.toString()

        return try {
            val p = pb.start()
            process = p
            pid = pidOf(p) ?: 0
            lastConfig = cfg
            rememberPid(app, pid)
            Log.i(TAG, "host started (pid $pid, appId ${cfg.appId}, home ${cfg.home.absolutePath}, log ${cfg.logFile.name}, agentPort ${cfg.agentPort})")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "host start failed", t)
            process = null
            false
        }
    }

    /**
     * Stop the host: SIGTERM (clean `Steam_LogOff` → `ReleaseUser` → `ReleasePipe`), wait up to
     * [gracefulMs], then destroy. Idempotent; safe from any thread (blocks up to gracefulMs + 2 s).
     */
    @JvmOverloads
    fun stop(why: String, gracefulMs: Long = 6_000L) {
        val p = process ?: return
        process = null
        val id = pid
        pid = 0
        Log.i(TAG, "stopping host (pid $id, $why)")
        if (id > 0) runCatching { Os.kill(id, OsConstants.SIGTERM) }
        waitExit(p, gracefulMs)
        if (p.isAlive) {
            Log.w(TAG, "host did not exit in ${gracefulMs}ms — destroying")
            runCatching { p.destroy() }
            waitExit(p, 2_000L)
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
        Log.i(TAG, "host stopped (exit ${runCatching { p.exitValue() }.getOrDefault(-1)})")
    }

    /** Kill a host left behind by a crashed app process (its pid is remembered in prefs). */
    private fun reapStale(app: Context) {
        val prefs = app.getSharedPreferences(PID_PREFS, Context.MODE_PRIVATE)
        val stale = prefs.getInt("pid", 0)
        if (stale <= 0) return
        prefs.edit().remove("pid").apply()
        try {
            val cmdline = File("/proc/$stale/cmdline").takeIf { it.exists() }?.readText().orEmpty()
            if (cmdline.contains(BINARY)) {
                Log.w(TAG, "reaping stale host pid $stale")
                runCatching { Os.kill(stale, OsConstants.SIGTERM) }
                Thread.sleep(300)
                if (File("/proc/$stale").exists()) runCatching { Os.kill(stale, OsConstants.SIGKILL) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stale-host check failed", t)
        }
    }

    private fun rememberPid(app: Context, id: Int) {
        runCatching { app.getSharedPreferences(PID_PREFS, Context.MODE_PRIVATE).edit().putInt("pid", id).apply() }
    }

    private fun waitExit(p: Process, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    private fun pidOf(p: Process): Int? =
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
}
