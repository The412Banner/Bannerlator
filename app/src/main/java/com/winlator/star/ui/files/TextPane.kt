package com.winlator.star.ui.files

// Ported from BFE (The412Banner/BFE, ui/docs/TextPane.kt, v1.5.0).

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Find-in-text state: the query, its hits and which one is current. */
@Stable
internal class TextFind {
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<TextMatch>>(emptyList())
    var current by mutableIntStateOf(0)
    var searching by mutableStateOf(false)

    val status: String
        get() = when {
            query.isEmpty() -> ""
            searching -> "…"
            matches.isEmpty() -> "0/0"
            else -> "${current + 1}/${matches.size}${if (matches.size >= LIMIT) "+" else ""}"
        }

    fun step(by: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(current + by, matches.size)
    }

    companion object {
        const val LIMIT = 10_000
    }
}

/**
 * A text file: monospace rows decoded only as they scroll into view, optional line numbers, wrapped or
 * one horizontal scroll for the whole file, find-in-text highlights. Multi-MB logs stay smooth: the
 * file is memory-mapped and indexed once ([TextDoc]). Code and config files are coloured by [lang].
 */
@Composable
internal fun TextPane(
    doc: TextDoc,
    zoomPercent: Int,
    wrap: Boolean,
    lineNumbers: Boolean,
    find: TextFind,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    lang: CodeLang = CodeLang.NONE,
) {
    val palette = rememberSyntaxPalette()
    val fontSize = (13f * zoomPercent / 100f).sp
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.35f, color = MaterialTheme.colorScheme.onSurface)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val hit = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val hitNow = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charW = remember(fontSize) { measurer.measure("0", style).size.width.coerceAtLeast(1) }
    val digits = doc.lineCount.toString().length
    val gutterDp = with(density) { (charW * (digits + 1)).toDp() } + 8.dp
    val byRow = remember(find.matches) { find.matches.groupBy { it.row } }
    val current = find.matches.getOrNull(find.current)

    // Search off the main thread; a new query cancels the last one.
    LaunchedEffect(doc, find.query) {
        val q = find.query
        if (q.isEmpty()) { find.matches = emptyList(); find.searching = false; return@LaunchedEffect }
        delay(220)
        find.searching = true
        val job = coroutineContext[Job]
        val found = withContext(Dispatchers.Default) { doc.find(q, limit = TextFind.LIMIT, cancelled = { job?.isActive == false }) }
        find.matches = found
        find.current = 0
        find.searching = false
    }
    LaunchedEffect(current) {
        current?.let { m -> runCatching { listState.scrollToItem((m.row - 3).coerceAtLeast(0)) } }
    }

    val content: @Composable (Modifier) -> Unit = { m ->
        SelectionContainer {
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), modifier = m) {
                items(doc.rowCount) { i ->
                    val text = remember(doc, i) { doc.row(i) }
                    // Continuation rows of a long line aren't a line start, so they stay plain.
                    val coloured = remember(doc, i, lang, palette) { if (doc.isContinuation(i)) AnnotatedString(text) else Syntax.line(text, lang, palette) }
                    val hits = byRow[i]
                    val shown: AnnotatedString = if (hits.isNullOrEmpty()) coloured else buildAnnotatedString {
                        append(coloured)
                        hits.forEach { h ->
                            val end = (h.start + h.length).coerceAtMost(text.length)
                            if (h.start < end) addStyle(SpanStyle(background = if (h == current) hitNow else hit), h.start, end)
                        }
                    }
                    Row(Modifier.padding(end = 12.dp)) {
                        // Each row is its own Text, and copying a selection joins them with nothing in
                        // between. This empty-sized line break, placed before every row that starts a
                        // new line, keeps the file's line breaks in the copied text.
                        if (i > 0 && !doc.isContinuation(i)) Text("\n", style = style, modifier = Modifier.size(0.dp))
                        if (lineNumbers) {
                            DisableSelection {
                                Text(
                                    if (doc.isContinuation(i)) "" else doc.lineNumber(i).toString(),
                                    style = style.copy(color = muted, textAlign = TextAlign.End),
                                    softWrap = false,
                                    modifier = Modifier.width(gutterDp).padding(end = 8.dp),
                                )
                            }
                        } else {
                            Box(Modifier.width(12.dp))
                        }
                        Text(shown, style = style, softWrap = wrap, modifier = if (wrap) Modifier.weight(1f) else Modifier)
                    }
                }
            }
        }
    }

    if (wrap) {
        content(modifier.fillMaxSize())
    } else {
        // One horizontal scroll for the whole file, as wide as its longest row.
        val widthDp = with(density) { (charW * (doc.longestRow + 2)).toDp() } + gutterDp + 24.dp
        Box(modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            content(Modifier.width(widthDp).fillMaxHeight())
        }
    }
}
