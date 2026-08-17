package com.winlator.star.core.unpack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the extraction is in its lifecycle. */
enum class UnpackPhase { IDLE, LISTING, EXTRACTING, DONE, ERROR, CANCELLED }

/**
 * How many CPU threads to hand 7-Zip (`-mmt=`).
 *
 * Be honest in the UI: extraction is I/O-bound. Extra cores only help archives with many files
 * (7z / solid), and a single huge file won't parallelise at all — so more threads is not a speed
 * dial, it's a knob that occasionally helps.
 */
enum class PowerMode { AUTO, MAX, MANUAL }

/** The Read-buffer knob. Sizes the pipe drain in [SevenZip.extract]; a real FUSE-throughput lever. */
enum class ReadBuffer(val bytes: Int, val label: String) {
    KB256(256 * 1024, "256 KB"),
    MB1(1024 * 1024, "1 MB"),
    MB4(4 * 1024 * 1024, "4 MB"),
}

/**
 * The single, process-wide snapshot of the running (or last) extraction. The foreground service
 * writes it; the Compose screen and the notification read it. One job at a time, matching the app's
 * 1-at-a-time DownloadCoordinator.
 */
data class UnpackState(
    val phase: UnpackPhase = UnpackPhase.IDLE,
    val archivePath: String = "",
    val archiveName: String = "",
    val destPath: String = "",
    val archiveType: String? = null,
    val archiveSize: Long = 0L,
    val percent: Int = 0,
    val currentFile: String? = null,
    val filesExtracted: Int = 0,
    val bytesProcessed: Long = 0L,
    val speedBps: Long = 0L,
    val etaSeconds: Long = -1L,
    val elapsedMs: Long = 0L,
    val errorTail: String? = null,
    /** True when the source is an InnoSetup installer/repack (not a plain archive). */
    val isInno: Boolean = false,
    /** Which engine ran: "7z" | "inno" | "unarc". Lets the error UI tailor its guidance. */
    val engine: String = "7z",
) {
    val isRunning: Boolean get() = phase == UnpackPhase.LISTING || phase == UnpackPhase.EXTRACTING
}

/** Process-static holder so the service and Compose share one live [UnpackState] without binding. */
object UnpackManager {
    private val _state = MutableStateFlow(UnpackState())
    val state: StateFlow<UnpackState> = _state.asStateFlow()

    val current: UnpackState get() = _state.value

    fun set(next: UnpackState) { _state.value = next }
    fun update(block: (UnpackState) -> UnpackState) { _state.value = block(_state.value) }

    /** Clear a terminal result back to idle (so re-opening the screen doesn't show a stale banner). */
    fun clearIfTerminal() {
        val p = _state.value.phase
        if (p == UnpackPhase.DONE || p == UnpackPhase.ERROR || p == UnpackPhase.CANCELLED) {
            _state.value = UnpackState()
        }
    }

    /** Thread count for a chosen [mode]; [manual] is used only in MANUAL. */
    fun mmtFor(mode: PowerMode, manual: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return when (mode) {
            PowerMode.AUTO -> (cores / 2).coerceAtLeast(1)
            PowerMode.MAX -> cores
            PowerMode.MANUAL -> manual.coerceIn(1, cores)
        }
    }
}
