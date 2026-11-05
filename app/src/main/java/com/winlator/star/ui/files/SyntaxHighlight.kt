package com.winlator.star.ui.files

// Ported from BFE (The412Banner/BFE, ui/docs/SyntaxHighlight.kt, v1.5.0).

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Light, line-by-line code colouring for the text viewer and editor: comments, strings, numbers,
 * keywords, variables, keys and section headers for the formats people edit on a handheld — configs
 * (ini / cfg / conf / reg / properties / toml / yaml), scripts (sh / bat / ps1 / py / lua), JSON, XML /
 * HTML, C-like code, and smali (Dalvik assembly). Each line is coloured on its own (a block comment spanning lines only colours
 * its first line), which keeps it instant on big files.
 */
internal enum class CodeLang { INI, SHELL, BATCH, POWERSHELL, PYTHON, LUA, JSON, YAML, XML, CLIKE, SMALI, NONE }

internal object Syntax {
    private val byExt: Map<String, CodeLang> = buildMap {
        listOf("ini", "cfg", "conf", "config", "inf", "reg", "properties", "prop", "toml", "env", "desktop", "url").forEach { put(it, CodeLang.INI) }
        listOf("sh", "bash", "zsh", "ksh", "rc", "profile").forEach { put(it, CodeLang.SHELL) }
        listOf("bat", "cmd").forEach { put(it, CodeLang.BATCH) }
        listOf("ps1", "psm1").forEach { put(it, CodeLang.POWERSHELL) }
        listOf("py", "pyw").forEach { put(it, CodeLang.PYTHON) }
        put("lua", CodeLang.LUA)
        listOf("json", "jsonc", "json5", "webmanifest").forEach { put(it, CodeLang.JSON) }
        listOf("yml", "yaml").forEach { put(it, CodeLang.YAML) }
        listOf("xml", "html", "htm", "xhtml", "svg", "plist", "xaml", "csproj", "vcxproj", "manifest").forEach { put(it, CodeLang.XML) }
        listOf("c", "h", "cpp", "cc", "cxx", "hpp", "hh", "java", "kt", "kts", "js", "mjs", "ts", "tsx", "jsx", "cs", "go", "rs",
            "swift", "dart", "gradle", "glsl", "hlsl", "frag", "vert", "php", "css", "scss").forEach { put(it, CodeLang.CLIKE) }
        put("smali", CodeLang.SMALI)
    }

    /** The language for [name], else a guess from the first line (a shebang, an XML / JSON start). */
    fun detect(name: String, firstLine: String?): CodeLang {
        byExt[name.substringAfterLast('.', "").lowercase()]?.let { return it }
        val head = firstLine?.trimStart().orEmpty()
        return when {
            head.startsWith("#!") && ("python" in head) -> CodeLang.PYTHON
            head.startsWith("#!") -> CodeLang.SHELL
            head.startsWith(".class ") -> CodeLang.SMALI
            head.startsWith("<?xml") || head.startsWith("<!DOCTYPE", ignoreCase = true) || head.startsWith("<html", ignoreCase = true) -> CodeLang.XML
            head.startsWith("{") || head.startsWith("[{") || head.startsWith("[\"") -> CodeLang.JSON
            head.startsWith("[") && head.trimEnd().endsWith("]") -> CodeLang.INI
            else -> CodeLang.NONE
        }
    }

    private val SHELL_KW = setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function",
        "return", "exit", "export", "local", "readonly", "set", "unset", "shift", "source", "alias", "echo", "printf", "read", "cd", "exec",
        "eval", "trap", "break", "continue", "true", "false", "test", "select")
    private val BATCH_KW = setOf("echo", "set", "if", "else", "goto", "call", "exit", "for", "in", "do", "not", "exist", "defined", "errorlevel",
        "setlocal", "endlocal", "start", "pause", "cd", "pushd", "popd", "shift", "equ", "neq", "lss", "leq", "gtr", "geq", "copy", "del", "move", "mkdir", "rmdir")
    private val PS_KW = setOf("if", "else", "elseif", "foreach", "for", "while", "do", "until", "switch", "function", "param", "return", "begin",
        "process", "end", "try", "catch", "finally", "throw", "break", "continue", "in", "true", "false", "null")
    private val PY_KW = setOf("def", "class", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "return", "import", "from", "as",
        "with", "try", "except", "finally", "raise", "pass", "break", "continue", "lambda", "yield", "global", "nonlocal", "assert", "del",
        "True", "False", "None", "async", "await", "self", "print")
    private val LUA_KW = setOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil", "not",
        "or", "repeat", "return", "then", "true", "until", "while")
    private val C_KW = setOf("if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "goto", "struct", "class",
        "enum", "union", "typedef", "const", "static", "extern", "volatile", "inline", "void", "int", "long", "short", "char", "float", "double",
        "bool", "unsigned", "signed", "auto", "public", "private", "protected", "internal", "override", "virtual", "abstract", "final", "new",
        "delete", "this", "super", "null", "nullptr", "true", "false", "try", "catch", "finally", "throw", "throws", "import", "package",
        "namespace", "using", "include", "define", "ifdef", "ifndef", "endif", "pragma", "fun", "val", "var", "let", "function", "interface",
        "extends", "implements", "object", "when", "is", "as", "in", "out", "suspend", "data", "sealed", "companion", "fn", "pub", "impl",
        "mut", "match", "use", "mod", "crate", "self", "Self", "async", "await", "yield", "export", "from", "typeof", "instanceof", "undefined", "func", "go", "defer", "chan", "map", "type")
    private val YAML_KW = setOf("true", "false", "null", "yes", "no", "on", "off", "~")

    /** Colours one line of [lang]. */
    fun line(text: String, lang: CodeLang, p: SyntaxPalette): AnnotatedString {
        if (lang == CodeLang.NONE || text.isEmpty() || text.length > 4000) return AnnotatedString(text)
        val b = AnnotatedString.Builder(text)
        when (lang) {
            CodeLang.INI -> ini(text, b, p)
            CodeLang.SHELL -> code(text, b, p, SHELL_KW, hash = true, vars = '$')
            CodeLang.BATCH -> batch(text, b, p)
            CodeLang.POWERSHELL -> code(text, b, p, PS_KW, hash = true, vars = '$', ignoreCase = true)
            CodeLang.PYTHON -> code(text, b, p, PY_KW, hash = true)
            CodeLang.LUA -> code(text, b, p, LUA_KW, dashDash = true)
            CodeLang.CLIKE -> code(text, b, p, C_KW, slashes = true, hashDirective = true)
            CodeLang.JSON -> json(text, b, p)
            CodeLang.YAML -> yaml(text, b, p)
            CodeLang.XML -> xml(text, b, p)
            CodeLang.SMALI -> smali(text, b, p)
            CodeLang.NONE -> Unit
        }
        return b.toAnnotatedString()
    }

    private fun AnnotatedString.Builder.paint(style: SpanStyle, start: Int, end: Int) { if (end > start) addStyle(style, start, end) }

    private fun ini(t: String, b: AnnotatedString.Builder, p: SyntaxPalette) {
        val lead = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
        val c = t[lead]
        when {
            c == ';' || c == '#' || t.startsWith("//", lead) -> b.paint(p.comment, lead, t.length)
            c == '[' -> b.paint(p.section, lead, (t.indexOf(']', lead).takeIf { it >= 0 }?.plus(1)) ?: t.length)
            else -> {
                val eq = t.indexOfAny(charArrayOf('=', ':'), lead)
                if (eq > lead) {
                    b.paint(p.key, lead, eq)
                    b.paint(p.punct, eq, eq + 1)
                    val v = t.substring(eq + 1).trim()
                    val vs = t.indexOf(v, eq + 1).takeIf { v.isNotEmpty() } ?: return
                    when {
                        v.startsWith("\"") || v.startsWith("'") -> b.paint(p.string, vs, vs + v.length)
                        v.toDoubleOrNull() != null || v.startsWith("0x", true) -> b.paint(p.number, vs, vs + v.length)
                        v.lowercase() in setOf("true", "false", "yes", "no", "on", "off", "enabled", "disabled") -> b.paint(p.keyword, vs, vs + v.length)
                    }
                }
            }
        }
    }

    private fun batch(t: String, b: AnnotatedString.Builder, p: SyntaxPalette) {
        val lead = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
        if (t.startsWith("::", lead) || t.regionMatches(lead, "rem", 0, 3, ignoreCase = true) && (t.length == lead + 3 || t[lead + 3].isWhitespace())) {
            b.paint(p.comment, lead, t.length); return
        }
        if (t[lead] == ':') { b.paint(p.section, lead, t.length); return }
        code(t, b, p, BATCH_KW, ignoreCase = true)
        // %var%, %~1, !var!
        Regex("%[~\\w]+%?|![\\w]+!").findAll(t).forEach { b.paint(p.variable, it.range.first, it.range.last + 1) }
    }

    private fun json(t: String, b: AnnotatedString.Builder, p: SyntaxPalette) {
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c == '"' -> {
                    val end = stringEnd(t, i, '"')
                    // A string followed by ':' is a key.
                    var j = end
                    while (j < t.length && t[j].isWhitespace()) j++
                    b.paint(if (j < t.length && t[j] == ':') p.key else p.string, i, end)
                    i = end
                }
                c == '/' && t.startsWith("//", i) -> { b.paint(p.comment, i, t.length); return }
                c.isDigit() || (c == '-' && i + 1 < t.length && t[i + 1].isDigit()) -> {
                    val s = i; i++
                    while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '.' || t[i] == '+' || t[i] == '-')) i++
                    b.paint(p.number, s, i)
                }
                c.isLetter() -> {
                    val s = i
                    while (i < t.length && t[i].isLetter()) i++
                    if (t.substring(s, i) in setOf("true", "false", "null")) b.paint(p.keyword, s, i)
                }
                c in "{}[]:," -> { b.paint(p.punct, i, i + 1); i++ }
                else -> i++
            }
        }
    }

    private fun yaml(t: String, b: AnnotatedString.Builder, p: SyntaxPalette) {
        val lead = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
        if (t[lead] == '#') { b.paint(p.comment, lead, t.length); return }
        val keyStart = if (t.startsWith("- ", lead)) lead + 2 else lead
        val colon = t.indexOf(": ", keyStart).takeIf { it > 0 } ?: if (t.endsWith(":")) t.length - 1 else -1
        if (colon > keyStart && !t.substring(keyStart, colon).contains(' ')) b.paint(p.key, keyStart, colon)
        val hash = t.indexOf(" #")
        if (hash >= 0) b.paint(p.comment, hash + 1, t.length)
        val v = if (colon >= 0) t.substring(colon + 1, if (hash > colon) hash else t.length).trim() else ""
        if (v.isNotEmpty()) {
            val vs = t.indexOf(v, colon + 1)
            when {
                v.startsWith("\"") || v.startsWith("'") -> b.paint(p.string, vs, vs + v.length)
                v.toDoubleOrNull() != null -> b.paint(p.number, vs, vs + v.length)
                v.lowercase() in YAML_KW -> b.paint(p.keyword, vs, vs + v.length)
            }
        }
    }

    private fun xml(t: String, b: AnnotatedString.Builder, p: SyntaxPalette) {
        var i = 0
        while (i < t.length) {
            if (t.startsWith("<!--", i)) {
                val end = t.indexOf("-->", i + 4).let { if (it < 0) t.length else it + 3 }
                b.paint(p.comment, i, end); i = end; continue
            }
            if (t[i] == '<') {
                // The tag name, then its attributes and their quoted values.
                var j = i + 1
                if (j < t.length && (t[j] == '/' || t[j] == '?' || t[j] == '!')) j++
                val nameStart = j
                while (j < t.length && !t[j].isWhitespace() && t[j] != '>' && t[j] != '/') j++
                b.paint(p.punct, i, nameStart)
                b.paint(p.keyword, nameStart, j)
                while (j < t.length && t[j] != '>') {
                    when {
                        t[j] == '"' || t[j] == '\'' -> { val e = stringEnd(t, j, t[j]); b.paint(p.string, j, e); j = e }
                        t[j].isLetter() -> { val s = j; while (j < t.length && (t[j].isLetterOrDigit() || t[j] in ":-_.")) j++; b.paint(p.key, s, j) }
                        else -> j++
                    }
                }
                if (j < t.length) { b.paint(p.punct, j, j + 1); j++ }
                i = j; continue
            }
            if (t[i] == '&') {
                val e = t.indexOf(';', i).takeIf { it in (i + 1)..(i + 10) }
                if (e != null) { b.paint(p.number, i, e + 1); i = e + 1; continue }
            }
            i++
        }
    }

    /**
     * smali: `.directives` as keywords, the opcode that starts an instruction line, registers (v0, p1),
     * `:labels`, type descriptors (`Lcom/example/Foo;`), strings, numbers and `#` comments.
     */
    private fun smali(t: String, b: AnnotatedString.Builder, p: SyntaxPalette) {
        val lead = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
        var i = lead
        // The first word: a directive, a label, or an opcode (move-result-object, invoke-virtual/range …).
        when (t[lead]) {
            '.' -> { i = wordEnd(t, lead + 1); b.paint(p.keyword, lead, i) }
            ':' -> { i = wordEnd(t, lead + 1); b.paint(p.section, lead, i) }
            '#' -> { b.paint(p.comment, lead, t.length); return }
            else -> if (t[lead].isLetter()) {
                var j = lead
                while (j < t.length && (t[j].isLetterOrDigit() || t[j] in "-/_")) j++
                if (j == t.length || t[j].isWhitespace()) { b.paint(p.function, lead, j); i = j }
            }
        }
        while (i < t.length) {
            val c = t[i]
            when {
                c == '#' -> { b.paint(p.comment, i, t.length); return }
                c == '"' || c == '\'' -> { val e = stringEnd(t, i, c); b.paint(p.string, i, e); i = e }
                c == ':' && i + 1 < t.length && (t[i + 1].isLetterOrDigit() || t[i + 1] == '_') -> { val e = wordEnd(t, i + 1); b.paint(p.section, i, e); i = e }
                (c == 'v' || c == 'p') && (i == 0 || !t[i - 1].isLetterOrDigit()) && i + 1 < t.length && t[i + 1].isDigit() -> {
                    var e = i + 1
                    while (e < t.length && t[e].isDigit()) e++
                    if (e == t.length || !t[e].isLetterOrDigit()) b.paint(p.variable, i, e)
                    i = e
                }
                c == 'L' && (i == 0 || t[i - 1] in " \t,({[)>-:") -> {
                    val semi = t.indexOf(';', i)
                    if (semi > i && t.substring(i, semi).none { it.isWhitespace() || it == '"' }) { b.paint(p.key, i, semi + 1); i = semi + 1 } else i++
                }
                (c.isDigit() || (c == '-' && i + 1 < t.length && t[i + 1].isDigit())) && (i == 0 || !t[i - 1].isLetterOrDigit()) -> {
                    val s0 = i; i++
                    while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '.')) i++
                    b.paint(p.number, s0, i)
                }
                c == '.' && i + 1 < t.length && t[i + 1].isLetter() && (i == 0 || t[i - 1].isWhitespace()) -> { val e = wordEnd(t, i + 1); b.paint(p.keyword, i, e); i = e }
                else -> i++
            }
        }
    }

    private fun wordEnd(t: String, from: Int): Int {
        var j = from
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] in "-_$")) j++
        return j
    }

    /** Generic tokenizer: comments, strings, numbers, keywords and $variables. */
    private fun code(
        t: String, b: AnnotatedString.Builder, p: SyntaxPalette, keywords: Set<String>,
        hash: Boolean = false, slashes: Boolean = false, dashDash: Boolean = false, hashDirective: Boolean = false,
        vars: Char? = null, ignoreCase: Boolean = false,
    ) {
        var i = 0
        val lead = t.indexOfFirst { !it.isWhitespace() }
        if (hashDirective && lead >= 0 && t[lead] == '#') { b.paint(p.section, lead, t.length); return }
        while (i < t.length) {
            val c = t[i]
            when {
                hash && c == '#' && (i == 0 || t[i - 1].isWhitespace() || t[i - 1] == ';') && !(i == 0 && t.startsWith("#!")) -> { b.paint(p.comment, i, t.length); return }
                hash && i == 0 && t.startsWith("#!") -> { b.paint(p.section, 0, t.length); return }
                slashes && t.startsWith("//", i) -> { b.paint(p.comment, i, t.length); return }
                slashes && t.startsWith("/*", i) -> {
                    val end = t.indexOf("*/", i + 2).let { if (it < 0) t.length else it + 2 }
                    b.paint(p.comment, i, end); i = end
                }
                dashDash && t.startsWith("--", i) -> { b.paint(p.comment, i, t.length); return }
                c == '"' || c == '\'' || (slashes && c == '`') -> {
                    val end = stringEnd(t, i, c)
                    b.paint(p.string, i, end)
                    if (vars != null && c == '"') paintVars(t, i, end, vars, b, p)
                    i = end
                }
                vars != null && c == vars -> { val e = varEnd(t, i); b.paint(p.variable, i, e); i = e }
                c.isDigit() && (i == 0 || !t[i - 1].isLetterOrDigit() && t[i - 1] != '_') -> {
                    val s = i
                    while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '.' || t[i] == '_')) i++
                    b.paint(p.number, s, i)
                }
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_')) i++
                    val w = t.substring(s, i)
                    if ((if (ignoreCase) w.lowercase() else w) in keywords) b.paint(p.keyword, s, i)
                    else if (i < t.length && t[i] == '(') b.paint(p.function, s, i)
                }
                else -> i++
            }
        }
    }

    private fun paintVars(t: String, from: Int, to: Int, v: Char, b: AnnotatedString.Builder, p: SyntaxPalette) {
        var i = from
        while (i < to) {
            if (t[i] == v && (i == 0 || t[i - 1] != '\\')) { val e = varEnd(t, i).coerceAtMost(to); b.paint(p.variable, i, e); i = e } else i++
        }
    }

    // $name, ${...}, $1, $?, $(...) opener.
    private fun varEnd(t: String, i: Int): Int {
        var j = i + 1
        if (j >= t.length) return j
        if (t[j] == '{') return (t.indexOf('}', j).takeIf { it >= 0 } ?: (t.length - 1)) + 1
        if (t[j] == '(') return j + 1
        if (!t[j].isLetterOrDigit() && t[j] != '_') return j + 1
        while (j < t.length && (t[j].isLetterOrDigit() || t[j] == '_')) j++
        return j
    }

    private fun stringEnd(t: String, start: Int, q: Char): Int {
        var i = start + 1
        while (i < t.length) {
            if (t[i] == '\\') { i += 2; continue }
            if (t[i] == q) return i + 1
            i++
        }
        return t.length
    }
}

/** The colours code is drawn in, from the current theme. */
@Immutable
internal data class SyntaxPalette(
    val comment: SpanStyle, val string: SpanStyle, val number: SpanStyle, val keyword: SpanStyle,
    val key: SpanStyle, val section: SpanStyle, val variable: SpanStyle, val function: SpanStyle, val punct: SpanStyle,
)

@Composable
internal fun rememberSyntaxPalette(): SyntaxPalette {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    fun c(darkHex: Long, lightHex: Long) = Color(if (dark) darkHex else lightHex)
    return SyntaxPalette(
        comment = SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.75f), fontStyle = FontStyle.Italic),
        string = SpanStyle(color = c(0xFF9CCC65, 0xFF2E7D32)),
        number = SpanStyle(color = c(0xFFFFB74D, 0xFFB45309)),
        keyword = SpanStyle(color = c(0xFFCE93D8, 0xFF7B1FA2)),
        key = SpanStyle(color = c(0xFF81D4FA, 0xFF0277BD)),
        section = SpanStyle(color = c(0xFFFFD54F, 0xFF8D6E00)),
        variable = SpanStyle(color = c(0xFFF48FB1, 0xFFC2185B)),
        function = SpanStyle(color = c(0xFF80CBC4, 0xFF00796B)),
        punct = SpanStyle(color = cs.onSurfaceVariant),
    )
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The editor's colouring: the same per-line rules over the whole text, characters unchanged. */
internal class SyntaxTransformation(private val lang: CodeLang, private val palette: SyntaxPalette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (lang == CodeLang.NONE || raw.length > MAX_CHARS) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(raw)
        var start = 0
        while (start <= raw.length) {
            val nl = raw.indexOf('\n', start).let { if (it < 0) raw.length else it }
            val lineStyled = Syntax.line(raw.substring(start, nl), lang, palette)
            lineStyled.spanStyles.forEach { r -> b.addStyle(r.item, start + r.start, start + r.end) }
            start = nl + 1
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is SyntaxTransformation && other.lang == lang && other.palette == palette
    override fun hashCode() = lang.hashCode() * 31 + palette.hashCode()

    companion object {
        /** Beyond this the editor stays plain: re-colouring runs on every keystroke. */
        const val MAX_CHARS = 300_000
    }
}
