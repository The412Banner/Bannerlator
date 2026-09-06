package com.winlator.star.store.steamscript

import com.winlator.star.core.VdfParser
import com.winlator.star.core.VdfParser.VdfNode

/**
 * Typed view over a parsed Steam `installScript.vdf` (see [VdfParser]). Flattens the four sections
 * the executor acts on into plain data classes; token substitution and hive/WOW64 mapping are the
 * job of [InstallScriptTokens], and applying them is [InstallScriptExecutor]'s.
 *
 * Grammar handled (Valve KeyValues, clean-room):
 * ```
 * "InstallScript"
 * {
 *   "Registry" { "<HIVE_ROOT>" { "<subkey>"… { "string"|"dword"|… { "<name>" "<value>" } } } }
 *   "Copy Files" { "<group>" { "SrcFile 1" "<src>"  "DstFile 1" "<dst>" } }
 *   "Run Process" { "<name>" { "process 1" "<exe>"  "command 1" "<args>"  "HasRunKey" "<key>" … } }
 *   "Delete Files On Uninstall" { "File 1" "<path>" }
 * }
 * ```
 * Nested groups below a hive root are registry subkeys; a group named with a value-type keyword
 * (`string`, `dword`, `binary`, `expand_string`, `qword`, `multi_string`) is terminal and its leaves
 * are `name -> value`. A leaf named `(Default)` is the key's default value.
 */
class InstallScriptModel private constructor(
    val registryWrites: List<RegistryWrite>,
    val copyFiles: List<CopyFile>,
    val runProcesses: List<RunProcess>,
    val deleteOnUninstall: List<String>,
    val utf8RegistryStrings: Boolean,
) {

    /** A single registry value to write, still carrying raw tokens/hive root (unsubstituted). */
    data class RegistryWrite(
        val hiveRoot: String,   // e.g. HKEY_LOCAL_MACHINE_WOW64_32
        val keyPath: String,    // below the hive, `\`-separated (e.g. Software\EA Games\NFS)
        val name: String?,      // null == key default value ("(Default)")
        val type: String,       // string | expand_string | dword | qword | binary | multi_string
        val value: String,      // raw, token-substituted by the executor
    )

    /** A file copy: source (usually under %INSTALLDIR%) to destination (usually under %PROGRAMDATA%). */
    data class CopyFile(val src: String, val dst: String)

    /** A `Run Process` step — the exe + verbatim command line, plus its optional once-guard key. */
    data class RunProcess(
        val name: String,
        val process: String,        // exe path, token-bearing (e.g. %INSTALLDIR%\…\EAappInstaller.exe)
        val command: String,        // args/env, passed VERBATIM (do NOT strip silent flags)
        val hasRunKey: String?,     // full registry path whose presence means "already ran", or null
        val hasRunValue: String?,   // expected value at hasRunKey for the guard to hold
        val runType: String?,
    )

    val hasRegistry: Boolean get() = registryWrites.isNotEmpty()
    val hasCopyFiles: Boolean get() = copyFiles.isNotEmpty()
    val hasRunProcess: Boolean get() = runProcesses.isNotEmpty()

    companion object {
        private val TYPE_KEYWORDS = setOf(
            "string", "expand_string", "dword", "qword", "binary", "multi_string"
        )
        private const val DEFAULT_VALUE_NAME = "(Default)"

        /** The Steam language whose per-language registry block is applied (the app's Steam UI language). */
        const val DEFAULT_LANGUAGE = "english"

        /** Steam API language names that appear as per-language blocks inside installscript type nodes. */
        private val STEAM_LANGUAGES = setOf(
            "arabic", "bulgarian", "schinese", "tchinese", "czech", "danish", "dutch", "english", "finnish",
            "french", "german", "greek", "hungarian", "indonesian", "italian", "japanese", "koreana", "korean",
            "norwegian", "polish", "portuguese", "brazilian", "romanian", "russian", "spanish", "latam",
            "swedish", "thai", "turkish", "ukrainian", "vietnamese",
        )

        fun parse(text: String): InstallScriptModel = from(VdfParser.parse(text))

        /** Build the model from a parsed document root (the synthetic root returned by [VdfParser]). */
        fun from(documentRoot: VdfNode): InstallScriptModel {
            // The script is wrapped in a single "InstallScript" node; tolerate its absence.
            val root = documentRoot.node("InstallScript") ?: documentRoot

            val registry = ArrayList<RegistryWrite>()
            for (registryNode in root.nodes("Registry")) {
                for ((rawRoot, hiveNode) in registryNode.childNodes()) {
                    // Handle both spellings: a nested `HIVE { Software { … } }` tree, and the combined
                    // `HIVE\Software\…` group key some scripts use — split the hive token off the front.
                    val sep = rawRoot.indexOf('\\')
                    val hive = if (sep >= 0) rawRoot.substring(0, sep) else rawRoot
                    val initialPath = if (sep >= 0) rawRoot.substring(sep + 1) else ""
                    collectRegistry(hive, hiveNode, initialPath, registry)
                }
            }

            val copies = ArrayList<CopyFile>()
            for (copyNode in root.nodes("Copy Files")) {
                for ((_, group) in copyNode.childNodes()) copies += collectCopyGroup(group)
            }
            // Some scripts spell it "LocFiles" at the top level rather than nested under "Copy Files".
            for (loc in root.nodes("LocFiles")) copies += collectCopyGroup(loc)

            val runs = ArrayList<RunProcess>()
            for (runNode in root.nodes("Run Process")) {
                for ((name, group) in runNode.childNodes()) runs += parseRunProcess(name, group)
            }

            val deletes = ArrayList<String>()
            for (delNode in root.nodes("Delete Files On Uninstall")) {
                for ((_, v) in delNode.stringEntries()) deletes += v
            }

            val utf8 = (root.string("utf8_registry_strings") ?: "0").trim().let { it == "1" || it.equals("true", true) }

            return InstallScriptModel(registry, copies, runs, deletes, utf8)
        }

        /**
         * Depth-first walk accumulating the subkey path; type-keyword groups terminate into leaves.
         *
         * Steam semantics for a nested node INSIDE a type block (e.g. `"string" { "english" { "Locale"
         * "en_US" "DisplayName" "…" } }`): it is a per-language value set, NOT a subkey. The client picks
         * the block matching the user's Steam language and writes its entries as values on the SAME key
         * (device-proven with Need for Speed Payback: `EA Games\<game>` must carry `Locale` +
         * `DisplayName` directly, or EA Desktop rejects the entitlement). Blocks for other languages are
         * skipped. Nested nodes that are not language blocks are written as subkeys of the type's key.
         */
        private fun collectRegistry(
            hiveRoot: String, node: VdfNode, pathSoFar: String, out: MutableList<RegistryWrite>,
            language: String = DEFAULT_LANGUAGE,
        ) {
            for ((childKey, childNode) in node.childNodes()) {
                if (childKey.lowercase() in TYPE_KEYWORDS) {
                    val type = childKey.lowercase()
                    for ((name, value) in childNode.stringEntries()) {
                        val regName = if (name.equals(DEFAULT_VALUE_NAME, true)) null else name
                        out.add(RegistryWrite(hiveRoot, pathSoFar, regName, type, value))
                    }
                    for ((sub, subNode) in childNode.childNodes()) {
                        when {
                            sub.equals(language, true) -> {
                                // Twice: as values on the parent key (EA Desktop reads `Locale` +
                                // `DisplayName` there to accept the entitlement) AND as the real
                                // `<key>\<language>` subkey the Steam client also creates. EA's
                                // Origin-era activation (Activation64 / ActivationUI) reads DisplayName
                                // from that subkey; without it the dialog says "DisplayName field
                                // missing from registry", the licence never binds to the product and
                                // the sign-in comes back on every launch (Need for Speed Payback).
                                val langPath = if (pathSoFar.isEmpty()) sub else "$pathSoFar\\$sub"
                                for ((name, value) in subNode.stringEntries()) {
                                    val regName = if (name.equals(DEFAULT_VALUE_NAME, true)) null else name
                                    out.add(RegistryWrite(hiveRoot, pathSoFar, regName, type, value))
                                    out.add(RegistryWrite(hiveRoot, langPath, regName, type, value))
                                }
                            }
                            sub.lowercase() in STEAM_LANGUAGES -> { /* another language's block — skip */ }
                            else -> {
                                val nextPath = if (pathSoFar.isEmpty()) sub else "$pathSoFar\\$sub"
                                for ((name, value) in subNode.stringEntries()) {
                                    val regName = if (name.equals(DEFAULT_VALUE_NAME, true)) null else name
                                    out.add(RegistryWrite(hiveRoot, nextPath, regName, type, value))
                                }
                            }
                        }
                    }
                } else {
                    val nextPath = if (pathSoFar.isEmpty()) childKey else "$pathSoFar\\$childKey"
                    collectRegistry(hiveRoot, childNode, nextPath, out, language)
                }
            }
        }

        /** Pairs `SrcFile N`/`DstFile N` leaves by their numeric suffix within one copy group. */
        private fun collectCopyGroup(group: VdfNode): List<CopyFile> {
            val src = HashMap<String, String>()
            val dst = HashMap<String, String>()
            for ((name, value) in group.stringEntries()) {
                val lower = name.lowercase().replace(" ", "")
                when {
                    lower.startsWith("srcfile") -> src[lower.removePrefix("srcfile")] = value
                    lower.startsWith("dstfile") -> dst[lower.removePrefix("dstfile")] = value
                }
            }
            return src.keys.mapNotNull { k -> dst[k]?.let { CopyFile(src[k]!!, it) } }
        }

        private fun parseRunProcess(name: String, group: VdfNode): RunProcess {
            var process = ""
            var command = ""
            for ((k, v) in group.stringEntries()) {
                val lower = k.lowercase().replace(" ", "")
                when {
                    process.isEmpty() && lower.startsWith("process") -> process = v
                    command.isEmpty() && lower.startsWith("command") -> command = v
                }
            }
            // Steam uses both "HasRunKey"/"HasRunValue" and "HasRunStringKey"/"HasRunStringValue".
            val hasRunKey = group.string("HasRunKey") ?: group.string("HasRunStringKey")
            val hasRunValue = group.string("HasRunValue") ?: group.string("HasRunStringValue")
            return RunProcess(name, process, command, hasRunKey, hasRunValue, group.string("RunType"))
        }
    }
}
