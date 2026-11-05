package com.winlator.star.core.unpack

/**
 * Sorts the layers of a FreeArc archive ([FreeArcInfo]) into what BFE can do with them:
 *  - [Kind.BUILTIN]: unarc decodes it itself (lzma, rep, tor, …).
 *  - [Kind.EXTERNAL]: a separate program in FreeArc's terms. BFE hands it to `libbfeext.so`, which looks
 *    at the layer's own data and runs the matching bundled decoder (precomp 0.4.4–0.4.8). Whether that
 *    works is only known once the layer's data is reached, so these are "BFE will try".
 *  - [Kind.MISSING]: known not to be decodable by BFE, with the reason - the archive can then only be
 *    installed by running its Setup.exe (Bannerlator / a PC).
 *
 * Names are matched loosely on purpose: archives name the same tool differently (srep, srep64, …).
 * Plain JVM, unit-tested.
 */
object RepackLayers {

    enum class Kind { BUILTIN, EXTERNAL, MISSING }

    data class Layer(val name: String, val kind: Kind, val note: String)

    data class Analysis(val layers: List<Layer>) {
        val missing: List<Layer> get() = layers.filter { it.kind == Kind.MISSING }
        val external: List<Layer> get() = layers.filter { it.kind == Kind.EXTERNAL }
        val needsExternal: Boolean get() = external.isNotEmpty()
        /** Every layer is something unarc decodes on its own. */
        val builtinOnly: Boolean get() = layers.all { it.kind == Kind.BUILTIN }
    }

    /** Decoded inside unarc (both bundled builds: LZMA, BCJ, Delta, REP, GRZip, Tornado, 4x4). */
    private val BUILTIN = setOf("storing", "crc", "fake", "lzma", "exe", "delta", "rep", "grzip", "tor", "4x4", "tempfile")

    /** FreeArc's own methods that BFE's unarc builds leave out (0.67 code with known 64-bit bugs). */
    private val FREEARC_UNBUILT = mapOf(
        "ppmd" to "PPMd", "lzp" to "LZP", "dict" to "Dict", "mm" to "MM (multimedia)", "tta" to "TTA (audio)",
        "dispack" to "DisPack", "dispack070" to "DisPack", "lz4" to "LZ4", "pmm" to "PPMonstr",
    )

    private val CLOSED_PRECOMP = Regex("""^precomp0?(3\d|40|41|42|43)$""")

    /** Builds the analysis for every distinct layer across the archive's blocks (first-seen order). */
    fun analyze(info: FreeArcInfo.Info): Analysis {
        if (info.headersEncrypted) {
            return Analysis(listOf(Layer("password", Kind.MISSING, "The archive is password-protected")))
        }
        val names = LinkedHashSet<String>()
        info.blocks.forEach { names += FreeArcInfo.methodNames(it.compressor) }
        return Analysis(names.map(::classify))
    }

    fun classify(name: String): Layer {
        val n = name.lowercase()
        return when {
            n in BUILTIN -> Layer(name, Kind.BUILTIN, "built into unarc")
            n in FREEARC_UNBUILT -> Layer(name, Kind.MISSING, "FreeArc ${FREEARC_UNBUILT[n]}, not in the bundled unarc build")
            listOf("aes", "blowfish", "twofish", "serpent").any { n.startsWith(it) } ->
                Layer(name, Kind.MISSING, "Encrypted - needs a password")
            "srep" in n -> Layer(name, Kind.MISSING, "SREP (SuperREP) - its licence doesn't allow it to be included")
            "lolz" in n -> Layer(name, Kind.MISSING, "lolz - closed source, Windows only")
            listOf("xtool", "oodle", "kraken", "mermaid", "selkie", "leviathan").any { it in n } ->
                Layer(name, Kind.MISSING, "xtool / Oodle - needs Epic's paid Oodle library")
            CLOSED_PRECOMP.matches(n) -> Layer(name, Kind.MISSING, "precomp 0.4.3 or older - never released as source code")
            "precomp" in n || n.startsWith("pcf") ->
                Layer(name, Kind.EXTERNAL, "precomp - 0.4.4 to 0.4.8 included")
            else -> Layer(name, Kind.EXTERNAL, "external layer - its data is checked and precomp tried")
        }
    }

    /** Temp-file names for external layers: FreeArc's default `$$arcdatafile$$.tmp` would be expanded by sh. */
    const val PACKED_FILE = "bfe-packed.tmp"
    const val DATA_FILE = "bfe-data.tmp"

    /**
     * The per-job `arc.ini` for unarc's `-cfg`: one `[External compressor:<name>]` section per external
     * layer, all run through [helperPath] (libbfeext.so). `header = 0` passes FreeArc's optional marker
     * byte through, and libbfeext handles it. One short section per name keeps every section well under
     * unarc's 512-character limit.
     */
    fun arcIni(externalNames: Collection<String>, helperPath: String): String = buildString {
        append("; Written by Bannerlator for one unpack job.\n")
        for (name in externalNames.distinct()) {
            require(name.none { it == ']' || it == '\n' || it == ',' }) { "bad layer name" }
            append("[External compressor:").append(name).append("]\n")
            append("header = 0\n")
            append("packedfile = ").append(PACKED_FILE).append('\n')
            append("datafile = ").append(DATA_FILE).append('\n')
            append("unpackcmd = ").append(helperPath).append(" {compressor} ").append(PACKED_FILE).append(' ').append(DATA_FILE).append("\n\n")
        }
    }

    private val QUOTED_LAYER = Regex("""layer '([^'\s]+)'""")

    /**
     * "BFE-EXT: …" lines from libbfeext in an engine's output, without the prefix and repeats. The quotes
     * around the layer name are dropped: the error log's scrubber blanks anything quoted, and a layer
     * name (srep64, precomp047) is no personal detail.
     */
    fun helperMessages(output: String): List<String> =
        output.lineSequence().map { it.trim() }.filter { it.startsWith("BFE-EXT:") }
            .map { QUOTED_LAYER.replace(it.removePrefix("BFE-EXT:").trim()) { m -> "layer ${m.groupValues[1]}" } }
            .distinct().toList()

    /** One compact line for logs: `precomp047=tries, lzma=built-in, srep=can't (SREP …)`. */
    fun summary(a: Analysis): String = a.layers.joinToString(", ") { l ->
        when (l.kind) {
            Kind.BUILTIN -> "${l.name}=built-in"
            Kind.EXTERNAL -> "${l.name}=tries"
            Kind.MISSING -> "${l.name}=can't (${l.note})"
        }
    }
}
