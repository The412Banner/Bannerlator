package com.winlator.star.container

/**
 * Which Proton layers [ContainerLayerUpdater] treats as one update line: same [Family], same arch, same
 * Proton major. GE-Proton 11.0-9 -> 11.0-14 or Proton 11.0-2 -> 11.0-3 is an update; GE <-> Valve,
 * 10.x <-> 11.x and arm64ec <-> x86_64 are not. Layers of no known family (Wine layers, others' builds)
 * keep the old rule: identical version name only.
 */
object LayerLine {

    enum class Family { GE, DROIDDECK, CACHYOS, VALVE }

    private val ARCH_SUFFIXES = listOf("-arm64ec", "-x86_64", "-x86")

    // GE builds up to v14 describe themselves as "Proton 11.0-3 arm64ec (bionic) — Valve + … + GE-Proton11-3
    // game-fixes (…)"; no Valve build carries GE's game-fixes.
    private val GE_GAME_FIXES = Regex("""GE-Proton\d+-\d+(\.\d+)? game-fixes""")

    /** `arm64ec` / `x86_64` / `x86` from a line name's suffix, or "" when it carries none. */
    fun archOf(versionName: String?): String =
        ARCH_SUFFIXES.firstOrNull { versionName?.endsWith(it) == true }?.substring(1) ?: ""

    /** A catalog row's family from its display label (`GE-Proton-11.0-9-arm64ec (v16)`); null = none known. */
    fun familyOfLabel(label: String?): Family? = when {
        label == null -> null
        label.startsWith("DroidDeck-Proton") -> Family.DROIDDECK
        label.startsWith("GE-Proton") -> Family.GE
        label.startsWith("Proton-CachyOS") -> Family.CACHYOS
        label.startsWith("Proton-") -> Family.VALVE
        else -> null
    }

    /**
     * An installed layer's family from its profile description. "DroidDeck-Proton …", "GE-Proton …" and
     * "Proton-CachyOS …" name it; otherwise [catalogLabels] (the labels of the catalog rows with this layer's
     * versionName + verCode) decide when they agree; otherwise "Proton …" is Valve, or GE when it lists GE's
     * game-fixes. Null = unknown.
     */
    fun familyOfInstalled(desc: String?, catalogLabels: List<String?>): Family? {
        if (desc != null) {
            if (desc.startsWith("DroidDeck-Proton")) return Family.DROIDDECK
            if (desc.startsWith("GE-Proton")) return Family.GE
            if (desc.startsWith("Proton-CachyOS")) return Family.CACHYOS
        }
        familyOfLabels(catalogLabels)?.let { return it }
        if (desc?.startsWith("Proton ") == true) return if (GE_GAME_FIXES.containsMatchIn(desc)) Family.GE else Family.VALVE
        return null
    }

    /** The one family all known [labels] agree on; null when none is known or they disagree. */
    fun familyOfLabels(labels: List<String?>): Family? = labels.mapNotNull { familyOfLabel(it) }.distinct().singleOrNull()

    /**
     * Same line: both of a known family -> same family, arch and Proton major; both unknown -> identical
     * version name. One known and one unknown is never a line (it could be GE vs Valve under one name).
     */
    fun sameLine(curName: String?, curFamily: Family?, candName: String?, candFamily: Family?): Boolean {
        if (curName == null || candName == null) return false
        if (curFamily == null || candFamily == null) return curFamily == null && candFamily == null && curName == candName
        return curFamily == candFamily && archOf(curName) == archOf(candName) &&
            versionOrder(curName).firstOrNull() == versionOrder(candName).firstOrNull()
    }

    /** Newer by version (`11.0-11.1` > `11.0-9.1`), then by verCode; within one name, verCode only. */
    fun isNewer(curName: String?, curCode: Int, candName: String?, candCode: Int): Boolean {
        if (curName == candName) return candCode > curCode
        val c = VERSION_ORDER.compare(versionOrder(candName), versionOrder(curName))
        return c > 0 || (c == 0 && candCode > curCode)
    }

    /** `11.0-11.1-arm64ec` -> [11, 0, 11, 1]; non-numeric parts end the list. */
    fun versionOrder(versionName: String?): List<Int> {
        if (versionName == null) return emptyList()
        val arch = archOf(versionName)
        val base = if (arch.isEmpty()) versionName else versionName.removeSuffix("-$arch")
        return base.split('.', '-').map { it.toIntOrNull() }.takeWhile { it != null }.map { it!! }
    }

    val VERSION_ORDER = Comparator<List<Int>> { a, b ->
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return@Comparator d
        }
        0
    }
}
