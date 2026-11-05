package com.winlator.star.ui.files

// Ported from BFE (The412Banner/BFE, ui/docs/TextEditorPane.kt, v1.5.0).

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException

/**
 * A text file opened for editing: the whole text in memory (files up to [MAX_BYTES]), with undo /
 * redo and the line endings, encoding and byte-order mark it was read with, so saving changes only
 * what was edited.
 */
@Stable
internal class EditorState(text: String, val encoding: TextEncoding, private val crlf: Boolean) {
    var value by mutableStateOf(TextFieldValue(text))
        private set
    private var savedText by mutableStateOf(text)
    val dirty: Boolean get() = value.text != savedText

    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    // Bumped on every history change so the Undo / Redo buttons recompose.
    private var historyTick by mutableIntStateOf(0)
    val canUndo: Boolean get() = historyTick >= 0 && undo.isNotEmpty()
    val canRedo: Boolean get() = historyTick >= 0 && redo.isNotEmpty()
    private var lastEdit = 0L

    /** Typing within a short pause is one undo step; a pause or a line break starts a new one. */
    fun onChange(v: TextFieldValue) {
        if (v.text != value.text) {
            val now = SystemClock.uptimeMillis()
            if (undo.isEmpty() || now - lastEdit > STEP_MS || v.text.length - value.text.length != 1 || v.text.getOrNull(v.selection.start - 1) == '\n') {
                undo.addLast(value)
                if (undo.size > HISTORY) undo.removeFirst()
            }
            lastEdit = now
            redo.clear()
            historyTick++
        }
        value = v
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(value)
        value = prev
        lastEdit = 0L
        historyTick++
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(value)
        value = next
        lastEdit = 0L
        historyTick++
    }

    /** Types [s] over the selection (the symbol keys). */
    fun insert(s: String) {
        val t = value.text
        val a = value.selection.min
        val b = value.selection.max
        lastEdit = 0L
        onChange(TextFieldValue(t.substring(0, a) + s + t.substring(b), TextRange(a + s.length)))
    }

    /** 1-based line and column of the cursor. */
    fun cursor(): Pair<Int, Int> {
        val t = value.text
        val at = value.selection.start.coerceIn(0, t.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until at) if (t[i] == '\n') { line++; lineStart = i + 1 }
        return line to (at - lineStart + 1)
    }

    /** The text as the file's bytes: its encoding, its byte-order mark and its line endings. */
    fun encoded(): ByteArray {
        val t = if (crlf) value.text.replace("\n", "\r\n") else value.text
        val body = t.toByteArray(encoding.charset)
        val bom = when {
            encoding.bom == 0 -> ByteArray(0)
            encoding == TextEncoding.UTF8_BOM -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            encoding.bigEndian -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        }
        return bom + body
    }

    fun markSaved() { savedText = value.text }

    companion object {
        /** Bigger files stay view-only: the editor keeps the whole text in memory and lays it out. */
        const val MAX_BYTES = 4L * 1024 * 1024
        private const val STEP_MS = 800L
        private const val HISTORY = 300

        /** Reads [file] for editing with [encoding] (from the viewer's sniff). */
        fun open(file: File, encoding: TextEncoding): EditorState {
            if (file.length() > MAX_BYTES) throw IOException("Files over 4 MB open read-only.")
            val bytes = file.readBytes()
            val raw = String(bytes, encoding.bom, bytes.size - encoding.bom, encoding.charset)
            val crlf = raw.contains("\r\n")
            return EditorState(raw.replace("\r\n", "\n"), encoding, crlf)
        }
    }
}

/**
 * Writes the edited text back: into the file in place when Bannerlator has a real path to it, else through
 * the app that shared it (its content:// Uri). Throws with a reason when neither works.
 */
internal fun saveEdited(ctx: Context, state: EditorState, file: File, originalUri: Uri?, isCacheCopy: Boolean) {
    val bytes = state.encoded()
    if (isCacheCopy) {
        val uri = originalUri ?: throw IOException("This copy has nowhere to be saved back to.")
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The app that shared the file doesn't allow changes.")
        out.use { it.write(bytes) }
        file.writeBytes(bytes)
    } else {
        if (!file.canWrite()) throw IOException("Bannerlator can't write to this file.")
        file.outputStream().use { it.write(bytes) }
    }
    state.markSaved()
}

/**
 * Two-finger pinch changes the text size (in 10 % steps, within the viewer's limits); one finger
 * still scrolls and places the cursor. [onZoom] gets each new size while pinching.
 */
@Composable
internal fun Modifier.pinchTextZoom(percent: Int, onZoom: (Int) -> Unit): Modifier {
    val current by rememberUpdatedState(percent)
    val callback by rememberUpdatedState(onZoom)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var live = -1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (live < 0f) live = current.toFloat()
                    val z = event.calculateZoom()
                    if (z != 1f) {
                        live = (live * z).coerceIn(TextPrefs.MIN_ZOOM.toFloat(), TextPrefs.MAX_ZOOM.toFloat())
                        val stepped = (Math.round(live / 10f) * 10).coerceIn(TextPrefs.MIN_ZOOM, TextPrefs.MAX_ZOOM)
                        if (stepped != current) callback(stepped)
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** The symbols config files and scripts need most, one tap each, above the keyboard. */
private val SYMBOLS = listOf("⇥", "/", "\\", "|", "=", "-", "+", "*", "<", ">", "\"", "'", "$", "#", "~", "&", ";", ":", ".", ",", "_", "[", "]", "{", "}", "(", ")", "!", "?", "%", "@")

/**
 * The editor: line numbers (drawn only for the lines on screen), wrapped or one horizontal scroll,
 * pinch to zoom, code colouring, and a row of symbol keys that rides above the keyboard.
 */
@Composable
internal fun TextEditorPane(
    state: EditorState,
    zoomPercent: Int,
    onZoom: (Int) -> Unit,
    wrap: Boolean,
    lineNumbers: Boolean,
    modifier: Modifier = Modifier,
    lang: CodeLang = CodeLang.NONE,
) {
    val palette = rememberSyntaxPalette()
    val colouring = remember(lang, palette) { SyntaxTransformation(lang, palette) }
    val fontSize = (13f * zoomPercent / 100f).sp
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.35f, color = textColor)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    val text = state.value.text
    // Offsets where each logical line starts, for numbering the visible rows.
    val lineStarts = remember(text) {
        val out = ArrayList<Int>(64).apply { add(0) }
        text.forEachIndexed { i, c -> if (c == '\n') out.add(i + 1) }
        out.toIntArray()
    }
    val charW = remember(fontSize) { measurer.measure("0", style).size.width.coerceAtLeast(1) }
    val gutterDp = if (lineNumbers) with(density) { (charW * (lineStarts.size.toString().length + 1)).toDp() } + 8.dp else 12.dp
    val zoom by rememberUpdatedState(onZoom)

    Column(modifier.fillMaxSize().imePadding()) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewport = it.height }
                .pinchTextZoom(zoomPercent) { zoom(it) },
        ) {
            Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                // Gutter: numbers for the rows between the scroll position and the bottom of the view.
                Box(
                    Modifier.width(gutterDp).fillMaxHeight().drawBehind {
                        val l = layout ?: return@drawBehind
                        if (!lineNumbers || l.lineCount == 0) return@drawBehind
                        val top = vScroll.value.toFloat()
                        val first = l.getLineForVerticalPosition(top)
                        val last = l.getLineForVerticalPosition(top + viewport)
                        val numStyle = style.copy(color = muted)
                        for (row in first..last) {
                            val start = l.getLineStart(row)
                            val idx = lineStarts.binarySearch(start)
                            if (idx < 0) continue // a wrapped continuation row
                            val label = measurer.measure((idx + 1).toString(), numStyle)
                            drawText(label, topLeft = Offset(size.width - label.size.width - 8.dp.toPx(), l.getLineTop(row) + 8.dp.toPx()))
                        }
                    },
                )
                val field: @Composable (Modifier) -> Unit = { m ->
                    BasicTextField(
                        value = state.value,
                        onValueChange = { state.onChange(it) },
                        textStyle = style,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Ascii),
                        onTextLayout = { layout = it },
                        visualTransformation = colouring,
                        modifier = m.padding(top = 8.dp, bottom = 8.dp, end = 12.dp),
                    )
                }
                if (wrap) {
                    field(Modifier.weight(1f))
                } else {
                    Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { field(Modifier.widthIn(min = 200.dp)) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()),
        ) {
            SYMBOLS.forEach { s ->
                TextButton(onClick = { state.insert(if (s == "⇥") "\t" else s) }, modifier = Modifier.widthIn(min = 44.dp)) {
                    Text(s, fontSize = 17.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
