package com.winlator.star.sound

import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The loaded sounds of one pack setup: for each Steam sound name, the files that may play for it.
 * All work runs on one background thread; [play] can be called from any thread and returns at once.
 * A sound still loading when its cue comes plays once ready only if nothing else was asked for since.
 */
internal class UiSoundBank private constructor(val key: String, private val pool: SoundPool) {

    private val samplesByName = HashMap<String, IntArray>()
    private val nameBySample = HashMap<Int, String>()
    private val ready = HashSet<Int>()
    private var waiting: Pair<Int, Float>? = null
    @Volatile private var closed = false

    private fun loadAll(files: Map<String, List<File>>) {
        pool.setOnLoadCompleteListener { _, sample, status ->
            worker.execute {
                if (closed) return@execute
                if (status != 0) {
                    Log.w(TAG, "could not load ${nameBySample[sample]} (status $status)")
                    return@execute
                }
                ready += sample
                val w = waiting
                if (w != null && w.first == sample) {
                    waiting = null
                    pool.play(sample, w.second, w.second, 1, 0, 1f)
                }
            }
        }
        val byPath = HashMap<String, Int>()
        for ((name, list) in files) {
            val ids = list.mapNotNull { f ->
                byPath[f.path] ?: run {
                    if (f.length() !in 1..MAX_BYTES) return@run null
                    val id = runCatching { pool.load(f.path, 1) }.getOrDefault(0)
                    if (id > 0) {
                        byPath[f.path] = id
                        nameBySample[id] = f.name
                        id
                    } else {
                        null
                    }
                }
            }
            if (ids.isNotEmpty()) samplesByName[name] = ids.toIntArray()
        }
        Log.i(TAG, "loading ${byPath.size} files for ${samplesByName.size} Steam sounds ($key)")
    }

    fun play(cue: UiCue, volume: Float) {
        worker.execute {
            if (closed) return@execute
            val options = cue.files.firstNotNullOfOrNull { samplesByName[it] } ?: return@execute
            val sample = options[if (options.size == 1) 0 else Random.nextInt(options.size)]
            if (sample in ready) {
                waiting = null
                pool.play(sample, volume, volume, if (cue.isEvent) 2 else 1, 0, 1f)
            } else {
                waiting = sample to volume
            }
        }
    }

    fun close() {
        closed = true
        worker.execute { runCatching { pool.release() } }
    }

    companion object {
        private const val TAG = "UiSounds"
        private const val MAX_BYTES = 4L * 1024 * 1024
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-sounds").apply { isDaemon = true } }

        /** Steam sound names any cue can play. */
        val STEAM_NAMES: Set<String> = UiCue.entries.flatMap { it.files.asList() }.toSet()

        /**
         * Files per Steam sound: [pack]'s own when it has them, else [base]'s (the Steam pack), as on a
         * Steam Deck where sounds a pack does not replace keep Steam's sound.
         */
        fun resolve(pack: UiSoundPack?, base: UiSoundPack?): Map<String, List<File>> =
            STEAM_NAMES.associateWith { n -> pack?.filesFor(n).orEmpty().ifEmpty { base?.filesFor(n).orEmpty() } }
                .filterValues { it.isNotEmpty() }

        /** Opens a bank for [files] and starts loading them in the background; null when SoundPool is unavailable. */
        fun open(key: String, files: Map<String, List<File>>): UiSoundBank? {
            val pool = runCatching {
                SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .build()
            }.onFailure { Log.w(TAG, "SoundPool unavailable", it) }.getOrNull() ?: return null
            return UiSoundBank(key, pool).also { b -> worker.execute { b.loadAll(files) } }
        }
    }
}
