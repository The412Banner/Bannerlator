package com.winlator.star.ui.deck.settings

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.deck.deckInset
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// The classic game editor's "Select Icon" (General tab) on the Game page: the entry's icon with a
// preview, picked from the app's own file browser or the system picker. As in the classic editor the
// picked image is written to the entry's icon file (the PNG its .desktop Icon= line names) the moment it
// is picked, not on Save: the file is the entry's icon, nothing in the shortcut's extras changes, so
// there is nothing for the draft to hold. A Wine game and the Linux Steam client entry both have one;
// an Android game has none.

internal class DeckIconSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {

    /** What the preview shows: the entry's icon as loaded, then each picked image. */
    var icon by mutableStateOf<Bitmap?>(env.shortcut?.icon)
        private set

    /** Which picker the next Dialogs() pass launches (they are registered there, always composed). */
    var launch by mutableStateOf<Picker?>(null)

    enum class Picker { FILES, SYSTEM }

    private val applies: Boolean
        get() = env.scope == EditorScope.GAME && env.shortcut != null && (env.world == World.WINE || env.world == World.LINUX)

    /** The icon file to write, or null with the reason it can't be changed here. */
    private fun target(): Pair<File?, String?> {
        val s = env.shortcut ?: return null to "No entry."
        val f = s.iconFile ?: return null to "This entry has no icon file to write to."
        // A rename saved in this editor moves the .desktop and its icon; this one was read before it.
        if (!s.file.isFile) return null to "Close and reopen this game's settings to change the icon after renaming it."
        return f to null
    }

    override fun extras(): Map<String, CategoryExtra> =
        if (!applies) emptyMap() else mapOf("game" to CategoryExtra(bottom = ::items))

    private fun items(): List<PageExtra> {
        val (file, reason) = target()
        val ok = file != null
        return listOf(
            PageExtra("icon-label") { GroupLabel("Icon") },
            PageExtra("icon-preview") {
                GroupCard(first = true, last = false, highlight = false) {
                    DeckLine(
                        label = "This entry's icon",
                        hint = reason ?: "Shown in your library and on home-screen shortcuts. A new one is saved as soon as you pick it.",
                    ) { IconPreview(icon) }
                }
            },
            PageExtra("icon:files") {
                GroupCard(first = false, last = false, highlight = false) {
                    DeckActionRow(DeckAction("files", Icons.Filled.Folder, "Choose an image", "From your files (PNG, JPG, WebP, BMP or GIF).", enabled = ok) { launch = Picker.FILES })
                }
            },
            PageExtra("icon:system") {
                GroupCard(first = false, last = true, highlight = false) {
                    DeckActionRow(DeckAction("system", Icons.Filled.PhotoLibrary, "Pick with the system picker", "Android's own picker, e.g. for Photos or cloud storage.", enabled = ok) { launch = Picker.SYSTEM })
                }
            },
        )
    }

    /** Decodes the picked image and writes it as the entry's icon PNG, as the classic editor does. */
    fun apply(uri: Uri) {
        val file = target().first ?: return
        val ctx = env.context
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    bmp
                }.getOrNull()
            }
            if (bitmap == null) {
                Toast.makeText(ctx, "Couldn't read that image.", Toast.LENGTH_SHORT).show()
            } else {
                env.shortcut?.icon = bitmap
                icon = bitmap
            }
        }
    }

    @Composable
    override fun Dialogs() {
        if (!applies) return
        val ctx = env.context
        val system = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { apply(it) } }
        val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { apply(it) }
        }
        LaunchedEffect(launch) {
            when (launch) {
                Picker.FILES -> runCatching { files.launch(InAppFilePicker.buildIntent(ctx, InAppFilePicker.IMAGES, "Select icon image")) }
                Picker.SYSTEM -> runCatching { system.launch("image/*") }
                null -> Unit
            }
            launch = null
        }
    }
}

@Composable
private fun IconPreview(icon: Bitmap?) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp).clip(shape).background(deckInset()),
    ) {
        if (icon != null) {
            Image(bitmap = icon.asImageBitmap(), contentDescription = "Icon", modifier = Modifier.size(56.dp))
        } else {
            Icon(Icons.Filled.Image, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
    }
}
