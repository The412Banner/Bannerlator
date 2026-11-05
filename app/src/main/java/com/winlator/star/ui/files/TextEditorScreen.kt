package com.winlator.star.ui.files

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.core.StringUtils
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.outlinedMenuCard
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Text size, wrap and line numbers for the text viewer/editor, kept between files. */
internal object TextPrefs {
    const val MIN_ZOOM = 50
    const val MAX_ZOOM = 250
    private fun p(ctx: Context) = ctx.getSharedPreferences("bl_text_editor", Context.MODE_PRIVATE)
    fun zoom(ctx: Context): Int = p(ctx).getInt("zoom", 100).coerceIn(MIN_ZOOM, MAX_ZOOM)
    fun setZoom(ctx: Context, v: Int) = p(ctx).edit().putInt("zoom", v.coerceIn(MIN_ZOOM, MAX_ZOOM)).apply()
    fun wrap(ctx: Context): Boolean = p(ctx).getBoolean("wrap", true)
    fun setWrap(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("wrap", v).apply()
    fun lineNumbers(ctx: Context): Boolean = p(ctx).getBoolean("line_numbers", true)
    fun setLineNumbers(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("line_numbers", v).apply()
}

/** Extensions opened in the text viewer by a tap in the file manager. */
internal val TEXT_EXTENSIONS = setOf(
    "txt", "log", "cfg", "ini", "conf", "config", "inf", "reg", "properties", "prop", "toml", "env", "json", "jsonc",
    "xml", "yml", "yaml", "md", "csv", "tsv", "nfo", "lua", "py", "ps1", "cmd", "vdf", "acf", "desktop", "url", "lst",
    "list", "cue", "gitignore", "manifest", "rc", "diz", "srt", "ass", "sub",
)

internal fun isTextFile(file: File): Boolean = file.extension.lowercase() in TEXT_EXTENSIONS

/** Opens [file] in the text viewer/editor; [readOnly] for copies (a file shown from inside an archive). */
class TextEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeState.init(this)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
        val title = intent.getStringExtra(EXTRA_TITLE)
        setContent {
            WinlatorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TextEditorScreen(file = File(path), readOnly = readOnly, title = title, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_READ_ONLY = "readOnly"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, file: File, readOnly: Boolean = false, title: String? = null): Intent =
            Intent(context, TextEditorActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_READ_ONLY, readOnly)
                .apply { if (title != null) putExtra(EXTRA_TITLE, title) }
    }
}

private sealed class Loaded {
    object Loading : Loaded()
    class Failed(val message: String) : Loaded()
    class Ready(val doc: TextDoc) : Loaded()
}

/**
 * View, edit and save a text file. Opens in the viewer (big logs stay smooth: only the rows on screen are
 * decoded); Edit switches to the editor for files up to 4 MB, keeping the file's encoding, byte-order mark
 * and line endings when it saves.
 */
@Composable
fun TextEditorScreen(file: File, readOnly: Boolean = false, title: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var loaded by remember(file) { mutableStateOf<Loaded>(Loaded.Loading) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var showBinary by remember(file) { mutableStateOf(false) }
    var editor by remember(file) { mutableStateOf<EditorState?>(null) }
    var zoom by remember { mutableIntStateOf(TextPrefs.zoom(context)) }
    var wrap by remember { mutableStateOf(TextPrefs.wrap(context)) }
    var lineNumbers by remember { mutableStateOf(TextPrefs.lineNumbers(context)) }
    var findOpen by remember { mutableStateOf(false) }
    val find = remember(file) { TextFind() }
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(file, reloadTick) {
        loaded = Loaded.Loading
        loaded = withContext(Dispatchers.IO) {
            runCatching { Loaded.Ready(TextDoc.open(file)) as Loaded }
                .getOrElse { Loaded.Failed(it.message ?: "Couldn't read ${file.name}") }
        }
    }

    val doc = (loaded as? Loaded.Ready)?.doc
    val lang = remember(doc) { if (doc == null) CodeLang.NONE else Syntax.detect(file.name, runCatching { doc.row(0) }.getOrNull()) }
    val canEdit = !readOnly && doc != null && !doc.truncated && file.length() <= EditorState.MAX_BYTES
    val dirty = editor?.dirty == true

    fun save(then: (() -> Unit)? = null) {
        val st = editor ?: return
        saving = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { saveEdited(context, st, file, null, false) }.exceptionOrNull()
            }
            saving = false
            if (error != null) {
                Toast.makeText(context, error.message ?: "Couldn't save ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                then?.invoke()
            }
        }
    }

    fun leaveEditor() {
        editor = null
        reloadTick++
    }

    fun startEditing() {
        val d = doc ?: return
        scope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { EditorState.open(file, d.encoding) } }
            st.onSuccess { editor = it; findOpen = false }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't open ${file.name} for editing", Toast.LENGTH_LONG).show() }
        }
    }

    BackHandler {
        when {
            findOpen -> { findOpen = false; find.query = "" }
            dirty -> askLeave = true
            editor != null -> leaveEditor()
            else -> onClose()
        }
    }

    if (askLeave) {
        OutlinedAlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("Save changes?") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = { askLeave = false; save { leaveEditor() } }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askLeave = false; leaveEditor() }) { Text("Discard") }
                    TextButton(onClick = { askLeave = false }) { Text("Cancel") }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        // ── Top bar ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(onClick = {
                when {
                    dirty -> askLeave = true
                    editor != null -> leaveEditor()
                    else -> onClose()
                }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.primary) }
            Column(Modifier.weight(1f)) {
                Text(
                    (if (dirty) "• " else "") + (title ?: file.name),
                    color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val info = buildString {
                    if (doc != null) {
                        append(doc.encoding.label)
                        append(" · ").append(doc.lineCount).append(if (doc.lineCount == 1) " line" else " lines")
                        append(" · ").append(StringUtils.formatBytes(file.length()))
                        if (readOnly) append(" · read-only")
                        else if (doc.truncated || file.length() > EditorState.MAX_BYTES) append(" · view only")
                    }
                    editor?.let { st -> val (l, c) = st.cursor(); append(" · Ln $l, Col $c") }
                }
                if (info.isNotEmpty()) {
                    Text(info, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val st = editor
            if (st != null) {
                IconButton(onClick = { st.undo() }, enabled = st.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { st.redo() }, enabled = st.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
                if (saving) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { save() }, enabled = st.dirty) {
                        Icon(Icons.Filled.Save, "Save", tint = if (st.dirty) cs.primary else cs.onSurfaceVariant)
                    }
                }
            } else {
                IconButton(onClick = { findOpen = !findOpen; if (!findOpen) find.query = "" }, enabled = doc != null) {
                    Icon(Icons.Filled.Search, "Find")
                }
                if (canEdit) {
                    IconButton(onClick = { startEditing() }) { Icon(Icons.Filled.Edit, "Edit", tint = cs.primary) }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.outlinedMenuCard()) {
                    DropdownMenuItem(
                        text = { Text(if (wrap) "Don't wrap lines" else "Wrap lines") },
                        leadingIcon = { Icon(Icons.Filled.WrapText, null) },
                        onClick = { wrap = !wrap; TextPrefs.setWrap(context, wrap); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (lineNumbers) "Hide line numbers" else "Show line numbers") },
                        leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) },
                        onClick = { lineNumbers = !lineNumbers; TextPrefs.setLineNumbers(context, lineNumbers); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Larger text") },
                        leadingIcon = { Icon(Icons.Filled.TextIncrease, null) },
                        enabled = zoom < TextPrefs.MAX_ZOOM,
                        onClick = { zoom = (zoom + 10).coerceAtMost(TextPrefs.MAX_ZOOM); TextPrefs.setZoom(context, zoom) },
                    )
                    DropdownMenuItem(
                        text = { Text("Smaller text") },
                        leadingIcon = { Icon(Icons.Filled.TextDecrease, null) },
                        enabled = zoom > TextPrefs.MIN_ZOOM,
                        onClick = { zoom = (zoom - 10).coerceAtLeast(TextPrefs.MIN_ZOOM); TextPrefs.setZoom(context, zoom) },
                    )
                    if (editor != null) {
                        DropdownMenuItem(
                            text = { Text("View") },
                            leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                            onClick = { menuOpen = false; if (dirty) askLeave = true else leaveEditor() },
                        )
                    }
                }
            }
        }

        // ── Find bar (viewer) ──
        if (findOpen && editor == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                OutlinedTextField(
                    value = find.query,
                    onValueChange = { find.query = it },
                    singleLine = true,
                    placeholder = { Text("Find", fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { find.step(1) }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(find.status, color = cs.onSurfaceVariant, fontSize = 12.sp)
                IconButton(onClick = { find.step(-1) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
                IconButton(onClick = { find.step(1) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
                IconButton(onClick = { findOpen = false; find.query = "" }) { Icon(Icons.Filled.Close, "Close find") }
            }
        }
        HorizontalDivider(color = cs.outlineVariant)

        // ── Body ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val l = loaded) {
                Loaded.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Loaded.Failed -> Text(l.message, color = cs.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                is Loaded.Ready -> {
                    val st = editor
                    when {
                        st != null -> TextEditorPane(
                            state = st,
                            zoomPercent = zoom,
                            onZoom = { zoom = it; TextPrefs.setZoom(context, it) },
                            wrap = wrap,
                            lineNumbers = lineNumbers,
                            lang = lang,
                        )
                        l.doc.binary && !showBinary -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                        ) {
                            Text("This doesn't look like a text file.", color = cs.onSurface)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { showBinary = true }) { Text("Show it anyway") }
                        }
                        else -> TextPane(
                            doc = l.doc,
                            zoomPercent = zoom,
                            wrap = wrap,
                            lineNumbers = lineNumbers,
                            find = find,
                            listState = listState,
                            lang = lang,
                        )
                    }
                }
            }
        }
    }
}

/** Selects the next occurrence of [query] after the cursor in [state] (wraps around); false when there is none. */
internal fun EditorState.selectNext(query: String): Boolean {
    if (query.isEmpty()) return false
    val t = value.text
    var i = t.indexOf(query, value.selection.max, ignoreCase = true)
    if (i < 0) i = t.indexOf(query, 0, ignoreCase = true)
    if (i < 0) return false
    onChange(value.copy(selection = TextRange(i, i + query.length)))
    return true
}
