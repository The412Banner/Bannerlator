package com.winlator.star.store

/**
 * Depot-download concurrency derived from CPU cores × per-tier ratios.
 *
 * Faithful port of GameNative's `DownloadSpeedConfig` (which runs the *same*
 * `in.dragonbra:javasteam-depotdownloader` engine we do). The engine models a download as a
 * three-stage pipeline — network fetch → decompress → file write — each stage bounded
 * independently. Only the decompress and file-write stages hold large (~chunk-uncompressed,
 * up to several MB) buffers, so those are the heap drivers; network parallelism is cheap on heap.
 *
 * Tiers are keyed by an integer value so the value can travel through
 * [SteamDepotDownloader.installApp]/[SteamDepotDownloader.resumeApp] and the picker UI as a
 * single [Int]:
 *
 *   8  = Slow     (0.6 dl / 0.2 dc)
 *   16 = Medium   (1.2 dl / 0.4 dc)
 *   24 = Fast     (1.5 dl / 0.5 dc)   <-- default
 *   32 = Blazing  (2.4 dl / 0.8 dc)
 *
 * `maxDownloads = (cores * download).coerceAtLeast(1)`
 * `maxDecompress = (cores * decompress).coerceAtLeast(1)`
 *
 * The joshuatam fork engine disk-spools chunks and has no separate file-write stage, so
 * `maxFileWrites` is gone — these two caps are now purely throughput knobs, not heap bounds.
 */
class DownloadSpeedConfig(private val tier: Int) {

    private data class Ratios(val download: Double, val decompress: Double)

    companion object {
        const val TIER_SLOW = 8
        const val TIER_MEDIUM = 16
        const val TIER_FAST = 24
        const val TIER_BLAZING = 32

        /** App default = Fast (matches GameNative's default tier). */
        const val DEFAULT_TIER = TIER_FAST
    }

    private val ratios: Ratios
        get() = when (tier) {
            TIER_SLOW -> Ratios(download = 0.6, decompress = 0.2)
            TIER_MEDIUM -> Ratios(download = 1.2, decompress = 0.4)
            TIER_FAST -> Ratios(download = 1.5, decompress = 0.5)
            TIER_BLAZING -> Ratios(download = 2.4, decompress = 0.8)
            // Unknown/corrupt value → behave as the app default (Fast), not GameNative's Slow
            // fallback, so a stray tier never silently throttles the default experience.
            else -> Ratios(download = 1.5, decompress = 0.5)
        }

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    val maxDownloads: Int
        get() = (cpuCores * ratios.download).toInt().coerceAtLeast(1)

    val maxDecompress: Int
        get() = (cpuCores * ratios.decompress).toInt().coerceAtLeast(1)

    /**
     * B2b async-fetch adaptive-window **ceiling** = the max number of concurrent in-flight chunk
     * requests the tier permits. The Rust engine bootstraps far below this and ramps toward it ONLY
     * while measured throughput keeps rising and errors/timeouts stay low, clamped to
     * distinct-CDN-hosts × per-host-cap; a weak/thin connection naturally settles well below it and
     * never floods. Unlike [maxDownloads] (which was an OS-thread count, so it scaled with CPU cores
     * and was capped at 32), this is a per-tier network-parallelism ceiling independent of cores:
     * async requests are cheap, so a fast link can hold many more in flight than there are cores.
     *
     *   Slow    = 6    (deliberately gentle for weak connections)
     *   Medium  = 16
     *   Fast    = 32   (default)
     *   Blazing = 96
     *
     * The engine still hard-bounds RAW in-flight memory with its byte budget regardless of this
     * ceiling, so a high tier costs concurrency, not unbounded heap.
     */
    val maxNetworkWindow: Int
        get() = when (tier) {
            TIER_SLOW -> 6
            TIER_MEDIUM -> 16
            TIER_FAST -> 32
            TIER_BLAZING -> 96
            // Unknown/corrupt value → app default (Fast), matching the ratio fallback above.
            else -> 32
        }
}
