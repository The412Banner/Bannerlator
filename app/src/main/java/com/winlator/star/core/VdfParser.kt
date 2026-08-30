package com.winlator.star.core

/**
 * Clean-room recursive parser for Valve's KeyValues / VDF text format.
 *
 * Written from Valve's documented KeyValues grammar (NOT ported from any GPL-3.0 interpreter):
 *   - a document is a sequence of `"key" "value"` and `"key" { … }` pairs;
 *   - keys and values may be double-quoted or bare (whitespace/brace delimited);
 *   - backslashes are kept LITERAL (Valve's non-escape mode, which is how it loads these files): a
 *     value like `%INSTALLDIR%\Foo.exe` keeps its single `\`. Only a doubled `\\` collapses to `\`
 *     and `\"` yields a literal quote — `\n`/`\t` are NOT special (they're path segments, not escapes);
 *   - `{ … }` opens a nested node; keys may repeat (Steam relies on ordered duplicate keys, e.g.
 *     several `"Run Process"` roots), so children are kept as an ordered list, not a map;
 *   - `//` starts a line comment;
 *   - a `[ … ]` platform conditional token (e.g. `[$WIN32]`) may trail a key or value and is skipped.
 *
 * Key lookups are case-insensitive to match Steam's own KeyValues behaviour (the installScript root
 * is spelled `"installscript"`, the step block `"Run Process"`, both case-insensitively). This parser
 * does not evaluate `#base`/`#include` directives — installScript.vdf never uses them.
 *
 * The typed reading of an installScript happens in
 * [com.winlator.star.store.steamscript.InstallScriptExecutor], which walks this tree directly.
 */
object VdfParser {

    /** A KeyValues node: an ordered list of (key -> String | VdfNode) entries, duplicates allowed. */
    class VdfNode {
        /** Ordered entries; each value is either a [String] leaf or a nested [VdfNode]. */
        val entries: MutableList<Pair<String, Any>> = ArrayList()

        fun add(key: String, value: Any) { entries.add(key to value) }

        /** First string leaf whose key matches [key] (case-insensitive), or null. */
        fun string(key: String): String? =
            entries.firstOrNull { it.first.equals(key, true) && it.second is String }?.second as String?

        /** First child node whose key matches [key] (case-insensitive), or null. */
        fun node(key: String): VdfNode? =
            entries.firstOrNull { it.first.equals(key, true) && it.second is VdfNode }?.second as VdfNode?

        /** All child nodes whose key matches [key] (case-insensitive), in document order. */
        fun nodes(key: String): List<VdfNode> =
            entries.filter { it.first.equals(key, true) && it.second is VdfNode }.map { it.second as VdfNode }

        /** Every string leaf as (name -> value), in document order. */
        fun stringEntries(): List<Pair<String, String>> =
            entries.filter { it.second is String }.map { it.first to it.second as String }

        /** Every child node as (key -> node), in document order. */
        fun childNodes(): List<Pair<String, VdfNode>> =
            entries.filter { it.second is VdfNode }.map { it.first to it.second as VdfNode }
    }

    private class Tokenizer(private val src: String) {
        private var pos = 0

        sealed class Token {
            data class Str(val text: String) : Token()
            object OpenBrace : Token()
            object CloseBrace : Token()
            object Conditional : Token()  // [$PLATFORM] — parsed and ignored
            object End : Token()
        }

        fun next(): Token {
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c == '/' && pos + 1 < src.length && src[pos + 1] == '/' -> {
                        // line comment
                        while (pos < src.length && src[pos] != '\n') pos++
                    }
                    c.isWhitespace() -> pos++
                    c == '{' -> { pos++; return Token.OpenBrace }
                    c == '}' -> { pos++; return Token.CloseBrace }
                    c == '[' -> { skipConditional(); return Token.Conditional }
                    c == '"' -> return Token.Str(readQuoted())
                    else -> return Token.Str(readBare())
                }
            }
            return Token.End
        }

        private fun skipConditional() {
            pos++ // consume '['
            while (pos < src.length && src[pos] != ']') pos++
            if (pos < src.length) pos++ // consume ']'
        }

        private fun readQuoted(): String {
            pos++ // consume opening quote
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos]
                if (c == '"') {
                    pos++ // consume closing quote
                    break
                } else if (c == '\\' && pos + 1 < src.length) {
                    // installScript.vdf values are file paths / registry paths written with LITERAL
                    // single backslashes (Valve loads these in its non-escape-sequence mode). So we must
                    // NOT turn `\U`/`\n`/`\t` into a control char — that would wreck every
                    // `%INSTALLDIR%\UbisoftConnectInstaller.exe`. Only collapse an explicit doubled
                    // `\\` -> `\` and an escaped quote `\"` -> `"`; keep every other backslash literal.
                    when (src[pos + 1]) {
                        '\\' -> { sb.append('\\'); pos += 2 }
                        '"' -> { sb.append('"'); pos += 2 }
                        else -> { sb.append(c); pos++ } // literal backslash; next char handled normally
                    }
                } else {
                    sb.append(c); pos++
                }
            }
            return sb.toString()
        }

        private fun readBare(): String {
            val start = pos
            while (pos < src.length) {
                val c = src[pos]
                if (c.isWhitespace() || c == '{' || c == '}' || c == '"') break
                if (c == '/' && pos + 1 < src.length && src[pos + 1] == '/') break
                pos++
            }
            return src.substring(start, pos)
        }
    }

    /**
     * Parses a full VDF document into a synthetic root node. Top-level `"key" { … }` pairs (such as
     * the single `"installscript"` root) become children of the returned node. Malformed fragments
     * are tolerated rather than throwing, so a partially-corrupt script still yields what parsed.
     */
    fun parse(text: String): VdfNode {
        val tok = Tokenizer(text)
        val root = VdfNode()
        parseInto(root, tok, topLevel = true)
        return root
    }

    private fun parseInto(node: VdfNode, tok: Tokenizer, topLevel: Boolean) {
        while (true) {
            var t = tok.next()
            // Skip stray conditionals sitting where a key is expected.
            while (t is Tokenizer.Token.Conditional) t = tok.next()
            when (t) {
                is Tokenizer.Token.End -> return
                is Tokenizer.Token.CloseBrace -> if (topLevel) continue else return
                is Tokenizer.Token.OpenBrace -> continue // unexpected brace with no key — skip
                is Tokenizer.Token.Conditional -> continue // already drained at line above; here only
                                                           // so the `when` is exhaustive over Token
                is Tokenizer.Token.Str -> {
                    val key = t.text
                    // Read the value token, skipping any trailing conditional after the key.
                    var v = tok.next()
                    while (v is Tokenizer.Token.Conditional) v = tok.next()
                    when (v) {
                        is Tokenizer.Token.OpenBrace -> {
                            val child = VdfNode()
                            parseInto(child, tok, topLevel = false)
                            node.add(key, child)
                        }
                        is Tokenizer.Token.Str -> {
                            node.add(key, v.text)
                            // A value-trailing conditional (e.g. `"k" "v" [$WIN32]`) is consumed and
                            // ignored lazily on the next iteration's key read, so nothing to do here.
                        }
                        is Tokenizer.Token.CloseBrace -> { node.add(key, ""); if (!topLevel) return }
                        is Tokenizer.Token.End -> { node.add(key, ""); return }
                        else -> { /* conditional already drained */ }
                    }
                }
            }
        }
    }
}
