package com.winlator.star.ui.simple

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.winlator.star.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Previews (Game art background › Art and previews): a game's trailer, muted unless Preview sound is on, looping
 * behind Home's big tile. The clip is the game's pick (Game details › Artwork › Preview clip: a Steam trailer or a
 * video file), else a gameplay trailer of its linked Steam app, else its first trailer. Steam's trailers are HLS
 * streams; what is played is kept in a 150 MB cache, so a clip seen once does not download again.
 */
@OptIn(UnstableApi::class)
internal object LabClips {
    private var cache: SimpleCache? = null
    private val auto = HashMap<Int, String?>()

    fun cache(context: Context): SimpleCache = synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "lab_clips"),
            LeastRecentlyUsedCacheEvictor(150L * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** The clip for [s] (a URL or a file path), or null. BLOCKING on the first look-up of a Steam app. */
    fun clipFor(s: Shortcut): String? {
        val pick = s.getExtra(LabArt.K_CLIP, "")
        if (pick == "none") return null
        if (pick.isNotBlank()) return pick
        val id = LabArt.appId(s) ?: return null
        synchronized(auto) { if (auto.containsKey(id)) return auto[id] }
        val list = labSteamTrailers(id)
        val url = (list.firstOrNull { it.title.contains("gameplay", ignoreCase = true) } ?: list.firstOrNull())?.url
        synchronized(auto) { auto[id] = url }
        return url
    }

    /** Whether a clip from the network may play now (Previews on Wi-Fi only). */
    fun networkOk(context: Context): Boolean {
        if (!LabArt.wifiOnly) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * The preview clip of [s], cropped to fill its box, fading in once its first frame is drawn. Starts a second after
 * the game is picked (moving along the library row does not start every trailer), skips a Steam trailer's opening
 * logos, loops, pauses while the app is in the background. Nothing is drawn when there is no clip or it cannot play.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun LabClipPlayer(s: Shortcut, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val muted = !LabArt.sound
    var shown by remember(s.file.path) { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    val player = remember {
        val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setUserAgent("Bannerlator").setAllowCrossProtocolRedirects(true))
        val cached = CacheDataSource.Factory().setCache(LabClips.cache(context)).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
            }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onVideoSizeChanged(v: VideoSize) { if (v.height > 0) ratio = v.width * v.pixelWidthHeightRatio / v.height }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { shown = false }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(muted) {
        player.volume = if (muted) 0f else 1f
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), !muted)
    }
    LaunchedEffect(s.file.path, LabArt.tick) {
        shown = false
        player.stop()
        player.clearMediaItems()
        delay(1000)
        val url = withContext(Dispatchers.IO) { runCatching { LabClips.clipFor(s) }.getOrNull() } ?: return@LaunchedEffect
        val remote = url.startsWith("http")
        if (remote && !LabClips.networkOk(context)) return@LaunchedEffect
        player.setMediaItem(if (remote) MediaItem.fromUri(url) else MediaItem.fromUri(android.net.Uri.fromFile(File(url))), if (remote) 8_000L else 0L)
        player.prepare()
        player.playWhenReady = true
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, player) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (player.mediaItemCount > 0) player.play()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(o)
        onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "labClip")
    AndroidView(
        factory = { ctx ->
            AspectRatioFrameLayout(ctx).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                val tv = TextureView(ctx)
                addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                player.setVideoTextureView(tv)
            }
        },
        update = { it.setAspectRatio(ratio) },
        modifier = modifier.graphicsLayer { alpha = a },
    )
}
