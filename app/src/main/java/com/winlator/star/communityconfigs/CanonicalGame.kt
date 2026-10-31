package com.winlator.star.communityconfigs

import org.json.JSONObject

/**
 * A device row inside a canonical game entry — one hardware target that has at least one
 * community config on file. Mirrors the compact keys used in {@code games_canonical.json}:
 * {@code m} = device model, {@code d} = GPU/driver, {@code s} = SoC.
 */
data class CanonicalDevice(
    val model: String,
    val gpu: String,
    val soc: String,
) {
    /** A single-line "Pixel 8 Pro · Adreno 750 · Tensor G3" style label for the UI. */
    val label: String
        get() = listOf(model, gpu, soc).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * One entry from {@code games_canonical.json}. The {@code identity} key is EITHER a numeric
 * Steam appid ("2096610") OR a non-Steam title key ("name:pes-2013"); see [isSteam].
 *
 * This is the read-only index model for Phase 1 (match + suggest). The individual configs the
 * [folders] point at are only fetched in Phase 2 (apply).
 */
data class CanonicalGame(
    val identity: String,
    val name: String,
    val folders: List<String>,
    val devices: List<CanonicalDevice>,
    val configCount: Int,
) {
    /** Numeric identity → Steam appid; a "name:" prefixed key → non-Steam title. */
    val isSteam: Boolean
        get() = identity.toLongOrNull() != null

    val steamAppId: String?
        get() = if (isSteam) identity else null

    /** Devices on file with an Arm Mali / Immortalis GPU (the GPU name can sit in any of the three fields). */
    val maliDeviceCount: Int
        get() = devices.count { d -> isMaliName(d.gpu) || isMaliName(d.soc) || isMaliName(d.model) }

    companion object {
        fun isMaliName(s: String?): Boolean =
            s != null && (s.contains("mali", ignoreCase = true) || s.contains("immortalis", ignoreCase = true))

        /**
         * The "N configs · M devices" line of the community lists. When the user's own GPU ([userGpu],
         * the renderer string the lists already carry) is a Mali, " · K Mali" is added for K > 0 Mali
         * devices on file; on any other GPU the line is unchanged.
         */
        fun countsLine(game: CanonicalGame, userGpu: String?): String {
            val cfgWord = if (game.configCount == 1) "config" else "configs"
            val devWord = if (game.devices.size == 1) "device" else "devices"
            val base = "${game.configCount} $cfgWord · ${game.devices.size} $devWord"
            if (!isMaliName(userGpu)) return base
            val mali = game.maliDeviceCount
            return if (mali > 0) "$base · $mali Mali" else base
        }

        /**
         * Parses the whole {@code games_canonical.json} object into a list of games. The file is a
         * map of {@code "<identity>" -> {name, folders, devices, config_count}}; malformed entries
         * are skipped rather than aborting the whole parse (the index is community-authored).
         */
        fun parseIndex(root: JSONObject): List<CanonicalGame> {
            val out = ArrayList<CanonicalGame>(root.length())
            val keys = root.keys()
            while (keys.hasNext()) {
                val identity = keys.next()
                val obj = root.optJSONObject(identity) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue

                val folders = ArrayList<String>()
                obj.optJSONArray("folders")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val f = arr.optString(i, "").trim()
                        if (f.isNotEmpty()) folders.add(f)
                    }
                }

                val devices = ArrayList<CanonicalDevice>()
                obj.optJSONArray("devices")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val d = arr.optJSONObject(i) ?: continue
                        devices.add(
                            CanonicalDevice(
                                model = d.optString("m", "").trim(),
                                gpu = d.optString("d", "").trim(),
                                soc = d.optString("s", "").trim(),
                            )
                        )
                    }
                }

                out.add(
                    CanonicalGame(
                        identity = identity,
                        name = name,
                        folders = folders,
                        devices = devices,
                        configCount = obj.optInt("config_count", devices.size),
                    )
                )
            }
            return out
        }
    }
}
