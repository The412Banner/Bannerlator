package com.winlator.star.core.unpack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.winlator.star.UnpackArchiveActivity
import com.winlator.star.core.StringUtils
import java.io.File

/**
 * Foreground service that runs one archive extraction to completion, surviving the app going to
 * background for the hour-or-two a big repack takes. It drives [SevenZip.extract] on a worker
 * thread, mirrors progress into [UnpackManager] (which the Compose screen collects) and into an
 * ongoing notification with a Cancel action, and kills the 7zz process on cancel.
 *
 * Modelled on [com.winlator.star.store.download.DownloadForegroundService]; kept separate because a
 * download and an unpack can legitimately run at once and want distinct notifications.
 */
class UnpackService : Service() {

    companion object {
        private const val TAG = "UnpackService"
        private const val CHANNEL_ID = "unpack_channel"
        private const val NOTIFICATION_ID = 9003

        const val ACTION_START = "com.winlator.star.unpack.START"
        const val ACTION_CANCEL = "com.winlator.star.unpack.CANCEL"
        const val EXTRA_ARCHIVE = "archive"
        const val EXTRA_DEST = "dest"
        const val EXTRA_MMT = "mmt"
        const val EXTRA_BUFFER = "buffer"
        const val EXTRA_IS_INNO = "isInno"
        const val EXTRA_TOTAL_SIZE = "totalSize"
        const val EXTRA_ENGINE = "engine"   // "7z" | "inno" | "unarc"

        // Process-static so the notification's Cancel action can reach the running process even if
        // onStartCommand hasn't re-published `instance` yet.
        @Volatile private var proc: Process? = null
        @Volatile private var cancelled = false

        fun start(ctx: Context, archive: String, dest: String, mmt: Int, bufferBytes: Int, isInno: Boolean, totalSize: Long, engine: String = "7z") {
            val app = ctx.applicationContext
            val i = Intent(app, UnpackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ARCHIVE, archive)
                putExtra(EXTRA_DEST, dest)
                putExtra(EXTRA_MMT, mmt)
                putExtra(EXTRA_BUFFER, bufferBytes)
                putExtra(EXTRA_IS_INNO, isInno)
                putExtra(EXTRA_TOTAL_SIZE, totalSize)
                putExtra(EXTRA_ENGINE, engine)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
        }

        fun cancel(ctx: Context) {
            val app = ctx.applicationContext
            app.startService(Intent(app, UnpackService::class.java).apply { action = ACTION_CANCEL })
        }
    }

    // Held for the whole extraction so the CPU keeps running with the screen off — screen-off CPU
    // suspend is the main reason a long background extraction appears to "pause". Balanced by a
    // finally in the worker thread AND onDestroy, so it can never leak.
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bannerlator:unpack").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY restart re-delivers a null intent. The worker thread and the 7zz process died
        // with the old process (7-Zip extraction isn't resumable), and our process-static state reset
        // to IDLE, so there is nothing to resume — clear any stale notification and stop cleanly. The
        // wake lock + battery-optimisation exemption are what prevent this kill in the first place.
        if (intent == null || intent.action == null) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            stopNow()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_CANCEL -> {
                cancelled = true
                runCatching { proc?.destroy() }
                Log.i(TAG, "Cancel requested")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val archive = intent.getStringExtra(EXTRA_ARCHIVE)
                val dest = intent.getStringExtra(EXTRA_DEST)
                val mmt = intent.getIntExtra(EXTRA_MMT, 1)
                val buffer = intent.getIntExtra(EXTRA_BUFFER, ReadBuffer.MB1.bytes)
                val isInno = intent.getBooleanExtra(EXTRA_IS_INNO, false)
                val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, 0L)
                val engine = intent.getStringExtra(EXTRA_ENGINE) ?: "7z"
                if (archive == null || dest == null) { stopNow(); return START_NOT_STICKY }
                // Refuse a second concurrent job — one at a time, like the DownloadCoordinator.
                if (UnpackManager.current.isRunning) {
                    Log.w(TAG, "Unpack already running; ignoring start")
                    return START_NOT_STICKY
                }
                startForegroundCompat(buildNotification(UnpackManager.current.copy(
                    phase = UnpackPhase.LISTING, archiveName = File(archive).name,
                )))
                runExtraction(File(archive), File(dest), mmt, buffer, isInno, totalSize, engine)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun runExtraction(archive: File, destDir: File, mmt: Int, buffer: Int, isInno: Boolean, totalSize: Long, engine: String) {
        cancelled = false
        val ctx = applicationContext
        // Speed/ETA and the reported size track the DATA the engine reads. For an InnoSetup installer
        // that is the Setup-*.bin payload total (passed in), not the small Setup.exe.
        val dataSize = if (totalSize > 0) totalSize else archive.length()
        Thread {
            acquireWakeLock()
            try {
            val startMs = SystemClock.elapsedRealtime()

            UnpackManager.set(
                UnpackState(
                    phase = UnpackPhase.LISTING,
                    archivePath = archive.absolutePath,
                    archiveName = archive.name,
                    destPath = destDir.absolutePath,
                    archiveSize = dataSize,
                    isInno = isInno,
                    engine = engine,
                )
            )
            refresh()

            // engine: "unarc" = FreeArc native, "inno" = innoextract (standard-Inno/GOG), else 7-Zip.
            val info = if (isInno) null else SevenZip.list(ctx, archive)
            UnpackManager.update {
                it.copy(
                    phase = UnpackPhase.EXTRACTING,
                    archiveType = when {
                        engine == "unarc" -> "FreeArc repack"
                        isInno -> "InnoSetup installer"
                        else -> info?.type
                    },
                )
            }
            refresh()

            // Speed/ETA: the engine reports percent, not bytes, so processed-bytes = percent/100 *
            // source size — genuine read throughput of the source, smoothed with a light EMA.
            var lastTick = SystemClock.elapsedRealtime()
            var lastBytes = 0L
            var emaBps = 0L
            var files = 0
            val size = dataSize.coerceAtLeast(1L)

            fun pushProgress(percent: Int, currentFile: String?) {
                val now = SystemClock.elapsedRealtime()
                val bytes = (size * percent / 100).coerceIn(0, size)
                if (now - lastTick >= 500) {
                    val dt = (now - lastTick).coerceAtLeast(1)
                    val inst = ((bytes - lastBytes) * 1000 / dt).coerceAtLeast(0)
                    emaBps = if (emaBps == 0L) inst else (emaBps * 2 + inst) / 3
                    lastTick = now
                    lastBytes = bytes
                }
                val eta = if (emaBps > 0) (size - bytes) / emaBps else -1L
                UnpackManager.update {
                    it.copy(
                        phase = UnpackPhase.EXTRACTING, percent = percent,
                        currentFile = currentFile ?: it.currentFile,
                        bytesProcessed = bytes, speedBps = emaBps, etaSeconds = eta,
                        elapsedMs = now - startMs, filesExtracted = files,
                    )
                }
                refresh()
            }

            val result = runCatching {
                if (engine == "unarc") {
                    // FreeArc: unarc block-buffers stdout, so drive progress by POLLING the destination
                    // size against the total from `unarc l` (140 GB-class). Speed/ETA from the byte delta.
                    val unarcTotal = Unarc.list(ctx, archive)?.totalBytes?.takeIf { it > 0 } ?: dataSize
                    UnpackManager.update { it.copy(archiveSize = unarcTotal) }
                    val polling = java.util.concurrent.atomic.AtomicBoolean(true)
                    val poller = Thread {
                        var pLastB = 0L; var pLastT = SystemClock.elapsedRealtime(); var pEma = 0L
                        while (polling.get()) {
                            runCatching { Thread.sleep(2500) }
                            val (count, bytes) = runCatching {
                                var c = 0; var b = 0L
                                destDir.walkTopDown().forEach { if (it.isFile) { c++; b += it.length() } }
                                c to b
                            }.getOrDefault(0 to 0L)
                            val now = SystemClock.elapsedRealtime()
                            val dt = (now - pLastT).coerceAtLeast(1)
                            val inst = ((bytes - pLastB) * 1000 / dt).coerceAtLeast(0)
                            pEma = if (pEma == 0L) inst else (pEma * 2 + inst) / 3
                            pLastB = bytes; pLastT = now
                            val pct = (bytes * 100 / unarcTotal.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                            val eta = if (pEma > 0) (unarcTotal - bytes) / pEma else -1L
                            files = count
                            UnpackManager.update {
                                it.copy(
                                    phase = UnpackPhase.EXTRACTING, percent = pct,
                                    bytesProcessed = bytes, speedBps = pEma, etaSeconds = eta,
                                    elapsedMs = now - startMs, filesExtracted = count,
                                )
                            }
                            refresh()
                        }
                    }.also { it.name = "unarc-poller"; it.start() }
                    val r = Unarc.extract(
                        ctx, archive, destDir,
                        listener = { name -> UnpackManager.update { it.copy(currentFile = name) } },
                        onProcess = { proc = it },
                    )
                    polling.set(false)
                    runCatching { poller.join(3000) }
                    if (r.exitCode == 0) files = runCatching { destDir.walkTopDown().count { it.isFile } }.getOrDefault(files)
                    SevenZip.Result(if (r.exitCode == 0) 0 else 2, r.stderrTail)
                } else if (isInno) {
                    val r = Innoextract.extract(
                        ctx, archive, destDir,
                        listener = { percent -> pushProgress(percent, null) },
                        onProcess = { proc = it },
                    )
                    // innoextract has no per-file callback; count the extracted files for the summary.
                    if (r.exitCode == 0) files = runCatching { destDir.walk().count { it.isFile } }.getOrDefault(0)
                    // innoextract uses 0=ok; map any non-zero to an error code (2) for the terminal logic.
                    SevenZip.Result(if (r.exitCode == 0) 0 else 2, r.stderrTail)
                } else {
                    SevenZip.extract(
                        ctx, archive, destDir, mmt, buffer,
                        object : SevenZip.Listener {
                            override fun onProgress(percent: Int, currentFile: String?) = pushProgress(percent, currentFile)
                            override fun onFile(name: String) {
                                files++
                                UnpackManager.update { it.copy(currentFile = name, filesExtracted = files) }
                            }
                        },
                        onProcess = { proc = it },
                    )
                }
            }.getOrElse { SevenZip.Result(-1, it.message ?: "exec failed") }

            // Unwrap a single inner .tar (a .wcp/.tzst/.tar.gz decompresses to just its .tar) so one
            // action lands the real files. 7-Zip path only; best-effort; only when the first pass won.
            if (!isInno && !cancelled && result.exitCode <= 1) {
                UnpackManager.update { it.copy(currentFile = "Unpacking inner archive…", percent = 0) }
                refresh()
                runCatching {
                    SevenZip.unwrapSingleTar(
                        ctx, destDir, mmt, buffer,
                        object : SevenZip.Listener {
                            override fun onProgress(percent: Int, currentFile: String?) {
                                UnpackManager.update { it.copy(phase = UnpackPhase.EXTRACTING, percent = percent) }
                                refresh()
                            }
                            override fun onFile(name: String) {
                                files++
                                UnpackManager.update { it.copy(currentFile = name, filesExtracted = files) }
                            }
                        },
                        onProcess = { proc = it },
                    )
                }
            }

            proc = null
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val terminal = when {
                cancelled -> UnpackState(
                    phase = UnpackPhase.CANCELLED, archivePath = archive.absolutePath,
                    archiveName = archive.name, destPath = destDir.absolutePath, elapsedMs = elapsed,
                    filesExtracted = files, archiveSize = dataSize, isInno = isInno, engine = engine,
                )
                result.exitCode <= 1 -> UnpackManager.current.copy(
                    phase = UnpackPhase.DONE, percent = 100, elapsedMs = elapsed,
                    filesExtracted = files, speedBps = 0, etaSeconds = 0, currentFile = null,
                )
                else -> UnpackManager.current.copy(
                    phase = UnpackPhase.ERROR, elapsedMs = elapsed, filesExtracted = files,
                    errorTail = result.stderrTail.takeIf { it.isNotBlank() } ?: "7-Zip exit code ${result.exitCode}",
                )
            }
            UnpackManager.set(terminal)
            postTerminalNotification(terminal)
            stopForeground(Service.STOP_FOREGROUND_DETACH)
            } finally {
                // Balanced release on EVERY exit path (success, error, cancel, process death).
                releaseWakeLock()
                stopSelf()
            }
        }.also { it.name = "unpack-worker"; it.start() }
    }

    override fun onDestroy() {
        releaseWakeLock()   // backstop — the worker's finally already releases on normal exits
        super.onDestroy()
    }

    // ── Notification ──

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Unpacking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows archive extraction progress and keeps it running in the background"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(s: UnpackState): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            UnpackArchiveActivity.intent(this, s.archivePath.ifEmpty { s.archiveName }),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, UnpackService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when (s.phase) {
            UnpackPhase.LISTING -> "Reading ${s.archiveName}…"
            else -> buildString {
                append("${s.percent}%")
                if (s.speedBps > 0) append("  •  ${StringUtils.formatBytes(s.speedBps)}/s")
                if (s.etaSeconds >= 0) append("  •  ETA ${formatDuration(s.etaSeconds * 1000)}")
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Unpacking ${s.archiveName}")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, s.percent, s.phase == UnpackPhase.LISTING)
            .setContentIntent(tap)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun refresh() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(UnpackManager.current))
    }

    /** Replace the ongoing notification with a dismissible terminal one (done / error / cancelled). */
    private fun postTerminalNotification(s: UnpackState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val tap = PendingIntent.getActivity(
            this, 0,
            UnpackArchiveActivity.intent(this, s.archivePath.ifEmpty { s.archiveName }),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (s.phase) {
            UnpackPhase.DONE -> "Unpacked ${s.archiveName}" to
                "${s.filesExtracted} files • ${StringUtils.formatBytes(s.archiveSize)} in ${formatDuration(s.elapsedMs)}"
            UnpackPhase.CANCELLED -> "Unpack cancelled" to s.archiveName
            else -> "Unpack failed" to (s.errorTail?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: s.archiveName)
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopNow() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
