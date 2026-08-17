package com.winlator.star.communityconfigs

/** A canonical game ranked against a shortcut name, best first. */
data class GameCandidate(
    val game: CanonicalGame,
    val score: Double,
    val exact: Boolean,
)

/**
 * Fully offline name matcher — no per-lookup network calls. Normalizes a shortcut name
 * ("Crysis3Remastered" / "Crysis 3 Remastered") and the canonical names into token sets, then
 * ranks: exact-normalized > token-subset > best token overlap.
 */
object GameMatcher {

    // Edition / marketing suffix tokens stripped from BOTH sides so "Crysis 3 Remastered" and a
    // canonical "Crysis 3" still line up. Applied symmetrically → never hurts an exact match.
    private val NOISE_TOKENS = setOf(
        "the", "remastered", "remaster", "remake", "edition", "goty", "definitive", "deluxe",
        "complete", "enhanced", "ultimate", "hd", "gold", "collection", "anniversary",
        "directors", "cut", "repack", "reloaded", "codex", "steam",
    )

    /**
     * Splits a raw title into normalized tokens: lowercases, breaks camelCase / digit boundaries,
     * strips non-alphanumeric separators (underscores, punctuation) and drops edition-noise tokens.
     */
    fun tokenize(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // Insert spaces at camelCase and letter/digit boundaries: "Crysis3Remastered" → "Crysis 3 Remastered".
        val spaced = StringBuilder(raw.length * 2)
        val chars = raw.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            if (i > 0) {
                val prev = chars[i - 1]
                val boundary =
                    (Character.isUpperCase(c) && !Character.isUpperCase(prev)) ||
                    (Character.isDigit(c) != Character.isDigit(prev) &&
                        (Character.isLetterOrDigit(c) && Character.isLetterOrDigit(prev)))
                if (boundary) spaced.append(' ')
            }
            spaced.append(c)
        }
        return spaced.toString()
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in NOISE_TOKENS }
    }

    /** Ranked candidates for [query] against [games]; empty when nothing plausibly overlaps. */
    fun match(query: String, games: List<CanonicalGame>): List<GameCandidate> {
        val q = tokenize(query)
        if (q.isEmpty()) return emptyList()
        val qSet = q.toSet()

        val candidates = ArrayList<GameCandidate>()
        for (game in games) {
            val g = tokenize(game.name)
            if (g.isEmpty()) continue
            val gSet = g.toSet()

            val exact = qSet == gSet
            val inter = qSet.count { it in gSet }
            if (inter == 0 && !exact) continue

            // Score primarily by how fully the CANONICAL name is covered by the shortcut: a game
            // name fully contained in the shortcut (e.g. "Dirt 3" ⊆ "dirt 3 colin mcrae", or
            // "Crysis 3" ⊆ "crysis 3 remastered") is a strong match and must not be diluted by the
            // shortcut's extra subtitle tokens. Blend with query coverage; tiny +inter bonus breaks
            // ties toward more shared tokens (so "Dirt 3" beats a "3"-only name).
            val covGame = inter.toDouble() / gSet.size
            val covQuery = inter.toDouble() / qSet.size
            val score = if (exact) 1.0 else (0.7 * covGame + 0.3 * covQuery + 0.001 * inter)
            if (score >= MIN_SCORE) candidates.add(GameCandidate(game, score, exact))
        }

        return candidates.sortedWith(
            compareByDescending<GameCandidate> { it.score }
                .thenByDescending { it.game.configCount }
        )
    }

    /**
     * Free-text search over the whole database — the manual-pick fallback for when auto-match is
     * wrong or empty. Ranks exact > prefix > substring > token-overlap, breaking ties by config count.
     */
    fun search(query: String, games: List<CanonicalGame>, limit: Int = 40): List<CanonicalGame> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val qTokens = tokenize(query).toSet()
        return games.asSequence()
            .mapNotNull { g ->
                val name = g.name.lowercase()
                val gTokens = tokenize(g.name).toSet()
                val hits = qTokens.count { it in gTokens }
                val rank = when {
                    name == q -> 4.0
                    name.startsWith(q) -> 3.0
                    name.contains(q) -> 2.0
                    hits > 0 -> 1.0 + hits.toDouble() / (qTokens.size + 1)
                    else -> 0.0
                }
                if (rank <= 0.0) null else g to (rank + g.configCount * 1e-6)
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Reorders a game's device list so the ones matching the user's hardware surface first:
     * matched hardware first, then the rest — each group keeping index order.
     */
    fun rankDevices(devices: List<CanonicalDevice>, userSoc: String?, userGpu: String?): List<CanonicalDevice> {
        if (devices.isEmpty()) return devices
        val userToks = userTokens(userSoc, userGpu)
        if (userToks.isEmpty()) return devices
        // Stable sort: matching devices (rank 0) ahead of the rest (rank 1), original order kept within each.
        return devices.sortedBy { if (deviceOverlaps(it, userToks)) 0 else 1 }
    }

    /**
     * True when [device] loosely matches the user's hardware. Compares the user's SoC/GPU strings
     * against ALL of the device's identifying fields (model / gpu / soc), because BannerHub's
     * per-config "soc" is really a GPU-renderer string (e.g. "Adreno (TM) 750") that lands in
     * [CanonicalDevice.soc], while its underscored twin ("Adreno__TM__750") lands in
     * [CanonicalDevice.gpu] — matching only the same-named field misses on both axes. Exposed for the
     * catalog browser's "Matches my device" filter.
     */
    fun deviceMatchesUser(device: CanonicalDevice, userSoc: String?, userGpu: String?): Boolean =
        hardwareMatchesUser(userSoc, userGpu, listOf(device.soc, device.gpu, device.model))

    /**
     * True when any of the user's hardware tokens overlaps any of the supplied hardware [fields], all
     * normalized ([normHw]). The generic core [deviceMatchesUser] delegates to — pass a device's
     * soc/gpu/model, or an uploaded config's device/soc strings, and it matches the same way regardless
     * of which named field a renderer/SoC spelling happens to land in. Used by the "Matches my device"
     * filters over both the catalog device rows and the per-uploaded-config list.
     */
    fun hardwareMatchesUser(userSoc: String?, userGpu: String?, fields: List<String>): Boolean =
        tokensOverlap(userTokens(userSoc, userGpu), fields.map { normHw(it) }.filter { it.length >= 3 })

    /** Any user hardware token overlaps any of the device's fields (soc/gpu/model), all normalized. */
    private fun deviceOverlaps(device: CanonicalDevice, userToks: List<String>): Boolean =
        tokensOverlap(userToks, listOf(device.soc, device.gpu, device.model).map { normHw(it) }.filter { it.length >= 3 })

    /** Any user token overlaps any device token (both already normalized). */
    private fun tokensOverlap(userToks: List<String>, devToks: List<String>): Boolean {
        if (userToks.isEmpty() || devToks.isEmpty()) return false
        for (u in userToks) for (d in devToks) if (hwOverlap(u, d)) return true
        return false
    }

    private fun userTokens(userSoc: String?, userGpu: String?): List<String> =
        listOfNotNull(userSoc, userGpu).map { normHw(it) }.filter { it.length >= 3 }

    /**
     * Normalize a hardware string so renderer/SoC spellings line up: lowercase, punctuation and
     * underscores collapse to single spaces, and standalone trademark tokens (tm/r) are dropped. So
     * "Adreno (TM) 750", "Adreno__TM__750" and a full "Qualcomm, Adreno (TM) 750, OpenGL ES ..." all
     * reduce to something containing "adreno 750".
     */
    private fun normHw(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\b(tm|r)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Overlap of two normalized tokens: equal, or the shorter (>= 4 chars, to avoid junk) is a substring of the longer. */
    private fun hwOverlap(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= 4 && longer.contains(shorter)
    }

    private const val MIN_SCORE = 0.34
}
