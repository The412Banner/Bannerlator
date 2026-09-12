package com.winlator.star.ui.screens.contents

import com.winlator.star.contents.ContentProfile

/**
 * The component types the Contents hub can browse and install. Each maps to the underlying
 * [ContentProfile.ContentType] used by the `.wcp` install pipeline, except [GPU_DRIVERS] which
 * is installed through the adrenotools importer instead and therefore has no ContentType.
 *
 * The [key] strings match [RemoteSourceRepository]'s type keys (the display name it filters/caches
 * by). Wine/Proton are carried along so wcp catalogs that list them still resolve.
 */
object ContentsTypes {

    const val GPU_DRIVERS = RemoteSourceRepository.GPU_DRIVER_TYPE // "GPU Drivers"

    /** Display order for chips / folders. */
    val ALL: List<String> = listOf(
        "DXVK", "D7VK", "VKD3D", "Box64", "WOWBox64", "FEXCore", "Wine", "Proton", GPU_DRIVERS,
    )

    fun isDriver(type: String): Boolean = type.equals(GPU_DRIVERS, ignoreCase = true)

    /** Maps a browse type to its wcp ContentType, or null for GPU drivers / unknown. */
    fun contentTypeFor(type: String): ContentProfile.ContentType? {
        if (isDriver(type)) return null
        return ContentProfile.ContentType.getTypeByName(type)
    }

    /** Normalised key for cross-checking installed / saved status (case- and separator-insensitive). */
    fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}
