package com.winlator.star.sound

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import com.winlator.star.contents.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Installed UI sound packs, the user's choice, and loading the choice into [UiSounds].
 *
 * Packs live in `files/ui_sounds/<id>/` (the pack root: Steam-named sound files, optional pack.json)
 * with a `.bannerlator-pack` note (source, store id, name). The Steam Big Picture pack is our release
 * zip, checked by size and sha256; it is also the base every other pack falls back to.
 */
object UiSoundPacks {
    private const val TAG = "UiSounds"

    const val STEAM_ID = "steam-big-picture"
    private const val STEAM_NAME = "Steam Big Picture"
    private const val STEAM_URL =
        "https://github.com/The412Banner/winlator-contents/releases/download/Steam-ui-sound/Steam-BigPicture-sounds.zip"
    private const val STEAM_SHA256 = "07455cb31b28e153412be6cdc9553ee10e003125163ee2c32ae793a2aec9e164"
    private const val STEAM_SIZE = 4_715_638L

    const val PREF_ON = "ui_sounds"
    const val PREF_PACK = "ui_sounds_pack"
    const val PREF_VOLUME = "ui_sounds_volume"
    private const val DEFAULT_VOLUME = 60
    private const val NOTE = ".bannerlator-pack"

    enum class Source { STEAM, STORE, ZIP }

    class Installed(val id: String, val name: String, val author: String, val version: String, val source: Source, val storeId: String?)

    data class State(
        val on: Boolean = false,
        val volume: Int = DEFAULT_VOLUME,
        val selected: String = STEAM_ID,
        val packs: List<Installed> = emptyList(),
        /** Id (or store id) being installed, with its progress 0..1. */
        val busy: String? = null,
        val progress: Float = 0f,
        val error: String? = null,
    ) {
        val steamInstalled: Boolean get() = packs.any { it.id == STEAM_ID }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var appContext: Context? = null

    private fun root(ctx: Context) = File(ctx.filesDir, "ui_sounds")

    /** Reads the saved choice and loads it. Call once from Application.onCreate. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        scope.launch {
            val p = PreferenceManager.getDefaultSharedPreferences(ctx)
            _state.update {
                it.copy(
                    on = p.getBoolean(PREF_ON, false),
                    volume = p.getInt(PREF_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100),
                    selected = p.getString(PREF_PACK, STEAM_ID) ?: STEAM_ID,
                    packs = scan(ctx),
                )
            }
            apply()
            if (_state.value.on && !_state.value.steamInstalled && _state.value.selected == STEAM_ID) installSteam(ctx)
        }
    }

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_ON, on).apply()
        _state.update { it.copy(on = on, error = null) }
        scope.launch {
            apply()
            if (on && !_state.value.steamInstalled && _state.value.selected == STEAM_ID) installSteam(ctx)
        }
    }

    fun setVolume(ctx: Context, volume: Int) {
        val v = volume.coerceIn(0, 100)
        prefs(ctx).edit().putInt(PREF_VOLUME, v).apply()
        _state.update { it.copy(volume = v) }
        UiSounds.setVolume(v / 100f)
    }

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(PREF_PACK, id).apply()
        _state.update { it.copy(selected = id, error = null) }
        scope.launch {
            apply()
            if (id == STEAM_ID && !_state.value.steamInstalled) installSteam(ctx)
        }
    }

    /** Downloads and installs the Steam Big Picture pack from our release. */
    fun installSteam(ctx: Context) = install(ctx, STEAM_ID) { tmp, progress ->
        if (!Downloader.downloadFile(STEAM_URL, tmp, progress)) throw IOException("download failed")
        if (tmp.length() != STEAM_SIZE) throw IOException("size ${tmp.length()}, expected $STEAM_SIZE")
        val sha = sha256(tmp)
        if (sha != STEAM_SHA256) throw IOException("sha256 $sha, expected $STEAM_SHA256")
        Triple(STEAM_ID, Source.STEAM, null)
    }

    /** Downloads and installs a DeckThemes store pack, and selects it. */
    fun installStore(ctx: Context, item: DeckThemesStore.Item) = install(ctx, "store-" + item.id, select = true) { tmp, progress ->
        if (item.manifestVersion > UiSoundPack.MANIFEST_VERSION) throw IOException("this pack needs a newer Audio Loader")
        if (!Downloader.downloadFile(item.downloadUrl, tmp, progress)) throw IOException("download failed")
        Triple("store-" + item.id, Source.STORE, item.id)
    }

    /** Installs an Audio Loader pack zip the user picked, and selects it. */
    fun importZip(ctx: Context, uri: Uri) {
        val id = "zip-" + System.currentTimeMillis()
        install(ctx, id, select = true) { tmp, _ ->
            (ctx.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            Triple(id, Source.ZIP, null)
        }
    }

    fun delete(ctx: Context, id: String) {
        scope.launch {
            File(root(ctx), id).deleteRecursively()
            _state.update { s -> s.copy(packs = scan(ctx)) }
            apply()
        }
    }

    /** Plays [cue] from the current choice even while sounds are off (settings preview). */
    fun preview(cue: UiCue) = UiSounds.preview(cue)

    private fun install(
        ctx: Context,
        busyId: String,
        select: Boolean = false,
        fetch: (tmp: File, progress: Downloader.ProgressListener) -> Triple<String, Source, String?>,
    ) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busyId, progress = 0f, error = null) }
        scope.launch {
            val root = root(ctx).apply { mkdirs() }
            val tmp = File(root, ".download.zip")
            val work = File(root, ".unpack")
            try {
                tmp.delete()
                work.deleteRecursively()
                val (id, source, storeId) = fetch(tmp) { f -> _state.update { it.copy(progress = f.coerceIn(0f, 1f)) } }
                val packRoot = UiSoundPack.unpack(tmp, work)
                val pack = UiSoundPack.read(packRoot, id, STEAM_NAME) ?: throw IOException("no sound pack in the zip")
                if (pack.music) throw IOException("this is a music pack, not a sound pack")
                val dest = File(root, id)
                dest.deleteRecursively()
                if (!packRoot.renameTo(dest)) throw IOException("cannot move the pack into place")
                File(dest, NOTE).writeText(
                    JSONObject().put("source", source.name).put("storeId", storeId ?: "")
                        .put("name", if (source == Source.STEAM) STEAM_NAME else pack.name).toString(),
                )
                if (select) prefs(ctx).edit().putString(PREF_PACK, id).apply()
                _state.update { s -> s.copy(packs = scan(ctx), selected = if (select) id else s.selected) }
                Log.i(TAG, "installed sound pack $id (${pack.name})")
            } catch (e: Exception) {
                Log.w(TAG, "sound pack install failed ($busyId)", e)
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                tmp.delete()
                work.deleteRecursively()
                _state.update { it.copy(busy = null, progress = 0f) }
            }
            apply()
        }
    }

    private fun scan(ctx: Context): List<Installed> {
        val dirs = root(ctx).listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val note = runCatching { JSONObject(File(d, NOTE).readText()) }.getOrNull()
            val source = runCatching { Source.valueOf(note?.optString("source").orEmpty()) }.getOrNull()
                ?: if (d.name == STEAM_ID) Source.STEAM else Source.ZIP
            val pack = UiSoundPack.read(d, d.name, note?.optString("name")?.ifBlank { null } ?: d.name) ?: return@mapNotNull null
            Installed(
                id = d.name,
                name = if (source == Source.STEAM) STEAM_NAME else pack.name,
                author = pack.author,
                version = pack.version,
                source = source,
                storeId = note?.optString("storeId")?.ifBlank { null },
            )
        }.sortedWith(compareBy<Installed>({ it.id != STEAM_ID }, { it.name.lowercase() }))
    }

    /** Loads the current choice into [UiSounds], or unloads when off. */
    private fun apply() {
        val ctx = appContext ?: return
        val s = _state.value
        val root = root(ctx)
        val base = if (s.steamInstalled) UiSoundPack.read(File(root, STEAM_ID), STEAM_ID, STEAM_NAME) else null
        val chosen = if (s.selected == STEAM_ID) null else s.packs.firstOrNull { it.id == s.selected }
            ?.let { UiSoundPack.read(File(root, it.id), it.id, it.name) }
        val files = UiSoundBank.resolve(chosen, base)
        UiSounds.configure(s.on, files, (chosen?.id ?: "") + "+" + (base?.id ?: ""), s.volume / 100f)
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
