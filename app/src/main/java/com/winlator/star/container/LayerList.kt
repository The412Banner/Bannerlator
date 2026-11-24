package com.winlator.star.container

import com.winlator.star.container.LayerLine.Family

/**
 * How the Wine / Proton pickers list layers (display only; no Android types). Proton and Wine are separate tabs; Proton
 * rows sit in one group per [LayerLine.Family]; one row stands for one layer (family, version, verCode) and shows its
 * arm64ec build, or with the x86_64 switch its x86_64 build; names are short ("GE-Proton 11.0-14"); older builds sit in
 * their own section. Families follow [LayerLine]: a catalog row by its label, an installed layer by its description,
 * then the catalog rows of its versionName + verCode.
 */
object LayerList {

    enum class Tab { PROTON, WINE }

    enum class Group(val title: String) {
        VALVE("Proton (Valve)"), GE("GE-Proton"), DROIDDECK("DroidDeck-Proton"), CACHYOS("CachyOS"), OTHER("Other"), WINE("Wine")
    }

    /** A download row's state: not installed, this build installed, or an older build of its line installed. */
    enum class Status { NEW, INSTALLED, UPDATE }

    const val ARM64EC = "arm64ec"
    const val X86_64 = "x86_64"

    /** Wine tab: the newest this many rows by version; the rest are older builds. */
    const val WINE_SHOWN = 4

    private val PROTON_GROUPS = listOf(Group.VALVE, Group.GE, Group.DROIDDECK, Group.CACHYOS, Group.OTHER)
    private val WINE_GROUPS = listOf(Group.WINE)

    /** A contents.json row. [id] is whatever the caller finds the row by again (its remoteUrl). */
    class CatalogRow(
        val id: String,
        val wine: Boolean,
        val label: String,
        val versionName: String?,
        val verCode: Int,
        val recommended: Boolean = false,
    )

    /** An installed layer: [entry] is its contents entry name (`Proton-11.0-14-arm64ec-17`), [versionName] its profile's. */
    class Installed(val entry: String, val wine: Boolean, val versionName: String, val verCode: Int, val desc: String?)

    data class Row(
        /** The catalog row's id (download list) or the entry name (installed picker). */
        val id: String,
        val group: Group,
        val name: String,
        val verCode: Int,
        val arch: String,
        /** Download list only. */
        val status: Status? = null,
        /** Download list: the entry name of the installed build this row is, when [status] is INSTALLED. */
        val installedEntry: String? = null,
        val recommended: Boolean = false,
        /** Installed picker: the container's layer. */
        val current: Boolean = false,
        /** Show an x86_64 tag: an x86_64 build among arm64ec ones (switch off, or an installed Proton build). */
        val archTag: Boolean = false,
    )

    class Section(val group: Group, val rows: List<Row>)

    class Page(val recommended: Row?, val sections: List<Section>, val older: List<Section>) {
        val olderCount: Int get() = older.sumOf { it.rows.size }
        val isEmpty: Boolean get() = sections.isEmpty() && older.isEmpty()
    }

    fun tabOf(wine: Boolean): Tab = if (wine) Tab.WINE else Tab.PROTON

    private val CODE_SUFFIX = Regex("""\s*\(v\d+\)\s*$""")
    private val LABEL_PREFIXES = listOf("DroidDeck-Proton-", "GE-Proton-", "Proton-CachyOS-", "Proton-")
    private val WINE_PREFIX = Regex("""^wine[-_ ]?""", RegexOption.IGNORE_CASE)
    private val WINE_REF = Regex("""[-_]ref$""", RegexOption.IGNORE_CASE)
    private val ARCH_TOKEN = Regex("""[-_](x86_64|arm64ec|x86)(?=$|[-_])""", RegexOption.IGNORE_CASE)
    private val DD_BASE = Regex("""\d+\.\d+-(\d.*)""")
    private val CACHY_DATE = Regex("""(\d+\.\d+)-(\d{4})(\d{2})(\d{2})""")

    /**
     * A catalog row's layer versionName: its `versionName` field, else its label without the family prefix and the
     * " (v17)" suffix (`Proton-11.0-3-arm64ec (v17)` -> `11.0-3-arm64ec`). Wine labels come back without the suffix only.
     */
    fun versionNameOf(row: CatalogRow): String {
        row.versionName?.takeIf { it.isNotBlank() }?.let { return it }
        val bare = row.label.replace(CODE_SUFFIX, "")
        if (row.wine) return bare
        val prefix = LABEL_PREFIXES.firstOrNull { bare.startsWith(it, ignoreCase = true) }
        return if (prefix == null) bare else bare.substring(prefix.length)
    }

    /** `arm64ec`, `x86_64` or "" from a layer name; Wine builds without one are the x86_64 reference builds. */
    fun archOf(name: String, wine: Boolean): String = when {
        name.contains(ARM64EC, ignoreCase = true) -> ARM64EC
        name.contains(X86_64, ignoreCase = true) -> X86_64
        wine -> X86_64
        else -> ""
    }

    /** The version without the arch (and for Wine without `wine-` and `-ref`): `11.0-14`, `9.22`. */
    fun baseOf(versionName: String, wine: Boolean): String {
        if (!wine) {
            val arch = LayerLine.archOf(versionName)
            return if (arch.isEmpty()) versionName else versionName.removeSuffix("-$arch")
        }
        return versionName.replace(WINE_PREFIX, "").replace(WINE_REF, "").replace(ARCH_TOKEN, "")
    }

    fun groupOf(family: Family?, wine: Boolean): Group = when {
        wine -> Group.WINE
        family == Family.VALVE -> Group.VALVE
        family == Family.GE -> Group.GE
        family == Family.DROIDDECK -> Group.DROIDDECK
        family == Family.CACHYOS -> Group.CACHYOS
        else -> Group.OTHER
    }

    /** The short name a row shows: "GE-Proton 11.0-14", "Proton 11.0-2", "DroidDeck-Proton 12.8", "CachyOS 11.0 (2026-10-05)", "Wine 9.22". */
    fun shortName(group: Group, base: String): String = when (group) {
        Group.VALVE, Group.OTHER -> "Proton $base"
        Group.GE -> "GE-Proton $base"
        Group.DROIDDECK -> "DroidDeck-Proton " + (DD_BASE.matchEntire(base)?.groupValues?.get(1) ?: base)
        Group.CACHYOS -> CACHY_DATE.matchEntire(base)?.groupValues?.let { "CachyOS ${it[1]} (${it[2]}-${it[3]}-${it[4]})" } ?: "CachyOS $base"
        Group.WINE -> "Wine $base"
    }

    fun familyOf(row: CatalogRow): Family? = if (row.wine) null else LayerLine.familyOfLabel(row.label)

    /** An installed layer's family: [LayerLine.familyOfInstalled] with the labels of the catalog rows it installs from. */
    fun familyOf(layer: Installed, catalog: List<CatalogRow>): Family? {
        if (layer.wine) return null
        val labels = catalog.filter { !it.wine && it.verCode == layer.verCode && versionNameOf(it) == layer.versionName }.map { it.label }
        return LayerLine.familyOfInstalled(layer.desc, labels)
    }

    private class Build(val row: CatalogRow, val family: Family?, val group: Group, val versionName: String, val base: String, val arch: String) {
        /** The name [LayerLine.sameLine] compares: Proton keeps its arch suffix, Wine goes by base + arch. */
        val line: String get() = if (row.wine) "$base-$arch" else versionName
    }

    private class Local(val layer: Installed, val family: Family?, val group: Group, val base: String, val arch: String) {
        val line: String get() = if (layer.wine) "$base-$arch" else layer.versionName
    }

    private fun build(row: CatalogRow): Build {
        val vn = versionNameOf(row)
        val family = familyOf(row)
        return Build(row, family, groupOf(family, row.wine), vn, baseOf(vn, row.wine), archOf(vn, row.wine))
    }

    private fun local(layer: Installed, catalog: List<CatalogRow>): Local {
        val family = familyOf(layer, catalog)
        return Local(layer, family, groupOf(family, layer.wine), baseOf(layer.versionName, layer.wine), archOf(layer.versionName, layer.wine))
    }

    private val ARCH_ORDER = listOf(ARM64EC, "", X86_64)

    private fun <T> newestFirst(version: (T) -> List<Int>, code: (T) -> Int): Comparator<T> =
        compareByDescending<T, List<Int>>(LayerLine.VERSION_ORDER) { version(it) }.thenByDescending { code(it) }

    /**
     * The download list's [tab]: one row per catalog layer with the build for the switch ([x86] = x86_64 builds only;
     * otherwise the arm64ec build, or the only build a layer has), grouped by family, newest first. Older builds: on the
     * Proton tab every row below the highest verCode of the Proton rows, on the Wine tab all but the newest [WINE_SHOWN].
     */
    fun downloadPage(tab: Tab, x86: Boolean, catalog: List<CatalogRow>, installed: List<Installed>): Page {
        val wine = tab == Tab.WINE
        val builds = catalog.filter { it.wine == wine }.map(::build)
        val locals = installed.filter { it.wine == wine }.map { local(it, catalog) }
        val layers = LinkedHashMap<String, MutableList<Build>>()
        for (b in builds) layers.getOrPut("${b.group}|${b.base}|${b.row.verCode}") { ArrayList() }.add(b)
        val picked = layers.values.mapNotNull { pick(it, x86) }
            .sortedWith(newestFirst<Build>({ LayerLine.versionOrder(it.line) }, { it.row.verCode }))
        val rows = picked.map { b -> b to rowOf(b, locals, archTag = !x86 && b.arch == X86_64) }
        val olderIds: Set<String> = if (wine) {
            rows.drop(WINE_SHOWN).map { it.second.id }.toSet()
        } else {
            val top = builds.maxOfOrNull { it.row.verCode } ?: 0
            rows.filter { it.first.row.verCode < top }.map { it.second.id }.toSet()
        }
        val groups = if (wine) WINE_GROUPS else PROTON_GROUPS
        fun sections(older: Boolean) = groups.mapNotNull { g ->
            val inGroup = rows.map { it.second }.filter { it.group == g && (it.id in olderIds) == older }
            if (inGroup.isEmpty()) null else Section(g, inGroup)
        }
        val main = sections(false)
        val old = sections(true)
        val rec = (main + old).asSequence().flatMap { it.rows.asSequence() }.firstOrNull { it.recommended }
        return Page(rec, main, old)
    }

    private fun pick(builds: List<Build>, x86: Boolean): Build? =
        if (x86) builds.firstOrNull { it.arch == X86_64 }
        else builds.firstOrNull { it.arch == ARM64EC } ?: builds.firstOrNull { it.arch != X86_64 } ?: builds.firstOrNull()

    private fun rowOf(b: Build, locals: List<Local>, archTag: Boolean): Row {
        val same = locals.firstOrNull { it.group == b.group && it.base == b.base && it.arch == b.arch && it.layer.verCode == b.row.verCode }
        val status = when {
            same != null -> Status.INSTALLED
            locals.any { LayerLine.sameLine(it.line, it.family, b.line, b.family) && LayerLine.isNewer(it.line, it.layer.verCode, b.line, b.row.verCode) } -> Status.UPDATE
            else -> Status.NEW
        }
        return Row(
            id = b.row.id, group = b.group, name = shortName(b.group, b.base), verCode = b.row.verCode, arch = b.arch,
            status = status, installedEntry = same?.layer?.entry, recommended = b.row.recommended, archTag = archTag,
        )
    }

    /**
     * The installed picker's [tab]: every installed layer, grouped by family, newest first, [current] marked, a star on
     * the layers the catalog recommends. No older section and no arch switch: x86_64 Proton builds carry an x86_64 tag.
     */
    fun installedPage(tab: Tab, installed: List<Installed>, catalog: List<CatalogRow>, current: String?): Page {
        val wine = tab == Tab.WINE
        val locals = installed.filter { it.wine == wine }.map { local(it, catalog) }
        val recommended = catalog.filter { it.recommended && it.wine == wine }.map(::build)
        val groups = if (wine) WINE_GROUPS else PROTON_GROUPS
        val sections = groups.mapNotNull { g ->
            val rows = locals.filter { it.group == g }
                .sortedWith(newestFirst<Local>({ LayerLine.versionOrder(it.line) }, { it.layer.verCode }).thenBy { ARCH_ORDER.indexOf(it.arch) })
                .map { l ->
                    Row(
                        id = l.layer.entry, group = g, name = shortName(g, l.base), verCode = l.layer.verCode, arch = l.arch,
                        recommended = recommended.any { r -> r.group == l.group && r.base == l.base && r.arch == l.arch && r.row.verCode == l.layer.verCode },
                        current = l.layer.entry == current,
                        archTag = !wine && l.arch == X86_64,
                    )
                }
            if (rows.isEmpty()) null else Section(g, rows)
        }
        return Page(null, sections, emptyList())
    }

    /** The tab an installed entry is on (the picker opens there). */
    fun tabOfEntry(entry: String?, installed: List<Installed>): Tab =
        tabOf(installed.firstOrNull { it.entry == entry }?.wine ?: entry?.startsWith("Wine-", ignoreCase = true) ?: false)

    /**
     * Installed layers as (entry, label) in the pickers' order: Proton groups, then Wine; label = short name + " · v17"
     * (+ " · x86_64"). For lists that show plain names (the Deck and XMB drop-downs).
     */
    fun installedChoices(installed: List<Installed>, catalog: List<CatalogRow>): List<Pair<String, String>> =
        Tab.entries.flatMap { t -> installedPage(t, installed, catalog, null).sections.flatMap { it.rows } }.map { it.id to labelOf(it) }

    /** "GE-Proton 11.0-14 · v17" (+ " · x86_64"). */
    fun labelOf(row: Row): String = buildString {
        append(row.name)
        if (row.verCode > 0) append(" · v").append(row.verCode)
        if (row.archTag) append(" · ").append(X86_64)
    }
}
