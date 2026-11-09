package com.winlator.star.ui.simple

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.DeckRoutes
import com.winlator.star.ui.deck.SoraFamily
import java.io.File
import kotlinx.coroutines.launch

/**
 * Lab's game art. App-wide choices (Settings › Appearance › Look), each game's own (Game details › Background and
 * Artwork, kept in the shortcut's Extra Data), the game whose art is on screen, and where its pictures come from: the
 * game's own pick first, then Steam's art for its linked Steam app, then its cover.
 */
internal object LabArt {
    const val OFF = "off"
    const val ART = "art"
    const val ART3D = "3d"
    const val CLIPS = "clips"
    const val CLIPS3D = "3dclips"
    val MODES = listOf(OFF to T("Off"), ART to T("Game art"), ART3D to T("3D art"), CLIPS to T("Art and previews"), CLIPS3D to T("3D art and previews"))
    fun has3d(m: String) = m == ART3D || m == CLIPS3D
    fun hasClips(m: String) = m == CLIPS || m == CLIPS3D

    const val SOLID = "solid"
    const val GLASS = "glass"
    const val CLEAR = "clear"
    val PANELS = listOf(SOLID to T("Solid"), GLASS to T("Glass"), CLEAR to T("Clear"))

    const val COVERS = "covers"
    const val WIDE = "wide"
    const val ICONS = "icons"
    val CARDS = listOf(COVERS to T("Covers"), WIDE to T("Wide covers"), ICONS to T("Icons"))
    val SIZES = listOf("s" to T("Small"), "m" to T("Medium"), "l" to T("Large"))

    // ── app-wide (Appearance) ──
    private var prefs: SharedPreferences? = null
    var mode by mutableStateOf(ART); private set
    var wifiOnly by mutableStateOf(true); private set
    var sound by mutableStateOf(false); private set
    var panels by mutableStateOf(GLASS); private set
    var grid by mutableStateOf(false); private set
    var cards by mutableStateOf(COVERS); private set
    var cardSize by mutableStateOf("m"); private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("lab_art", Context.MODE_PRIVATE)
        prefs = p
        mode = p.getString("mode", ART)?.takeIf { v -> MODES.any { it.first == v } } ?: ART
        wifiOnly = p.getBoolean("wifi_only", true)
        sound = p.getBoolean("sound", false)
        panels = p.getString("panels", GLASS)?.takeIf { v -> PANELS.any { it.first == v } } ?: GLASS
        grid = p.getBoolean("grid", false)
        cards = p.getString("cards", COVERS)?.takeIf { v -> CARDS.any { it.first == v } } ?: COVERS
        cardSize = p.getString("card_size", "m")?.takeIf { v -> SIZES.any { it.first == v } } ?: "m"
    }

    fun chooseMode(v: String) { mode = v; prefs?.edit()?.putString("mode", v)?.apply() }
    fun chooseWifiOnly(v: Boolean) { wifiOnly = v; prefs?.edit()?.putBoolean("wifi_only", v)?.apply() }
    fun chooseSound(v: Boolean) { sound = v; prefs?.edit()?.putBoolean("sound", v)?.apply() }
    fun choosePanels(v: String) { panels = v; prefs?.edit()?.putString("panels", v)?.apply() }
    fun chooseGrid(v: Boolean) { grid = v; prefs?.edit()?.putBoolean("grid", v)?.apply() }
    fun chooseCards(v: String) { cards = v; prefs?.edit()?.putString("cards", v)?.apply() }
    fun chooseCardSize(v: String) { cardSize = v; prefs?.edit()?.putString("card_size", v)?.apply() }

    fun label(list: List<Pair<String, String>>, v: String): String = L(list.firstOrNull { it.first == v }?.second ?: v)

    // ── what is on screen ──
    /** The game whose art the Lab pages show: Home's big tile, the cover the controller rests on in Library, the game page's. */
    var selected by mutableStateOf<Shortcut?>(null)

    /** Bumped when a game's own art settings change, so the pages and the backdrop read them again. */
    var tick by mutableIntStateOf(0)
    fun changed() { tick++ }

    /** The art on screen now (null: the Lab wallpaper). The Lab panels read it for how see-through they are. */
    var showing by mutableStateOf<Look?>(null)

    /** Panel alpha over the art: Solid keeps Lab's own. */
    val panelAlpha: Float?
        get() = when (showing?.panels) {
            GLASS -> 0.75f
            CLEAR -> 0.56f
            else -> null
        }

    /** One picture to try: a URL, a file or a bitmap, drawn blurred or not. */
    class Pic(val model: Any, val blur: Boolean = false)

    /** The art for one game, as the backdrop draws it. [pics] are tried in order until one loads. */
    class Look(val key: String, val pics: List<Pic>, val panels: String, val grid: Boolean, val parallax: Boolean, val mode: String)

    // ── each game's own (Extra Data) ──
    const val K_MODE = "labArtMode"
    const val K_PICTURE = "labArtPicture"
    const val K_PANELS = "labArtPanels"
    const val K_GRID = "labArtGrid"
    const val K_LOGO_POS = "labLogoPos"
    const val K_LOGO_SIZE = "labLogoSize"
    const val K_CLIP = "labClip"
    fun slotKey(slot: String) = "labArt_$slot"

    /** Saves one of a game's own settings ("" or null = back to automatic / the app setting). */
    fun putGame(s: Shortcut, key: String, value: String?) {
        runCatching {
            s.putExtra(key, value?.takeIf { it.isNotBlank() })
            s.saveData()
        }
        changed()
    }

    fun gameMode(s: Shortcut): String = s.getExtra(K_MODE, "").takeIf { v -> MODES.any { it.first == v } } ?: mode
    fun gamePanels(s: Shortcut): String = s.getExtra(K_PANELS, "").takeIf { v -> PANELS.any { it.first == v } } ?: panels
    fun gameGrid(s: Shortcut): Boolean = when (s.getExtra(K_GRID, "")) { "on" -> true; "off" -> false; else -> grid }
    /** The game's Steam app: its link (Game details), else one found by its name for art and previews only. */
    fun appId(s: Shortcut): Int? = s.getExtra("steamAppId", "").toIntOrNull()?.takeIf { it > 0 } ?: foundIds[s.name]

    /** Steam apps found by name for games without a link (never written to the game); found SteamGridDB heroes. */
    private val foundIds = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    private val foundHeroes = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val tried = HashSet<String>()
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * The art found for a game automatically: its Steam app by name when it has no link (the store's strict title
     * match, remembered by SteamStoreSearch) and SteamGridDB's sharpest background (3840x1240 first, not the blurred
     * style) by its Steam app or a game of the same title. Remembered on the device (prefs lab_art_found; a miss is
     * retried after a week), so SteamGridDB is asked once per game. Not used when the game has its own background.
     */
    fun resolve(context: Context, s: Shortcut) {
        if (pick(s, "bg") != null) return
        val name = s.name
        synchronized(tried) { if (!tried.add(name)) return }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("lab_art_found", Context.MODE_PRIVATE)
        prefs.getString("hero:$name", null)?.takeIf { it.startsWith("http") }?.let { foundHeroes[name] = it }
        val linked = s.getExtra("steamAppId", "").toIntOrNull()?.takeIf { it > 0 }
        io.launch {
            val id = linked ?: runCatching { com.winlator.star.store.SteamStoreSearch.findAppIdByName(app, name) }.getOrNull()
            val cached = prefs.getString("hero:$name", null)
            val now = System.currentTimeMillis()
            val hero = when {
                cached != null && cached.startsWith("http") -> cached
                cached != null && now - (cached.removePrefix("none:").toLongOrNull() ?: 0L) < 7L * 24 * 3600 * 1000 -> null
                else -> runCatching { labSgdbHero(app, id, name) }.getOrNull().also { prefs.edit().putString("hero:$name", it ?: "none:$now").apply() }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (linked == null && id != null) foundIds[name] = id
                if (hero != null) foundHeroes[name] = hero
                if ((linked == null && id != null) || hero != null) changed()
            }
        }
    }

    private const val STEAM = "https://shared.steamstatic.com/store_item_assets/steam/apps/"

    /** Steam's published picture for [slot] of [appId]. */
    fun steamUrl(appId: Int, slot: String): String? = when (slot) {
        "cover" -> "$STEAM$appId/library_600x900.jpg"
        "wide" -> "$STEAM$appId/header.jpg"
        "bg" -> "$STEAM$appId/library_hero.jpg"
        "logo" -> "$STEAM$appId/logo.png"
        else -> null
    }

    /** Steam's pictures for [slot], sharpest first: the background's double size (3840×1240) where Steam has one. */
    fun steamUrls(appId: Int, slot: String): List<String> =
        if (slot == "bg") listOf("$STEAM$appId/library_hero_2x.jpg", "$STEAM$appId/library_hero.jpg") else listOfNotNull(steamUrl(appId, slot))

    fun model(v: String): Any = if (v.startsWith("http")) v else File(v)

    /** The game's own pick for [slot]: a URL or file path, "none", or null (automatic). */
    fun pick(s: Shortcut, slot: String): String? = s.getExtra(slotKey(slot), "").takeIf { it.isNotBlank() }

    /** What [slot] shows, in the order to try: the game's pick, then Steam's. Empty for "none" or nothing known. */
    fun models(s: Shortcut, slot: String): List<Any> {
        val p = pick(s, slot)
        if (p == "none") return emptyList()
        val id = appId(s)
        if (slot == "bg") {
            // Sharpest first: Steam's double size, SteamGridDB's (3840x1240 when it has one), Steam's 1920x620.
            return listOfNotNull(p?.let(::model), id?.let { "$STEAM$it/library_hero_2x.jpg" }, foundHeroes[s.name], id?.let { "$STEAM$it/library_hero.jpg" })
        }
        return listOfNotNull(p?.let(::model)) + (if (id != null) steamUrls(id, slot) else emptyList())
    }

    /** The cover the app already shows (Simple cover's bitmap), as a picture model. */
    fun coverModel(s: Shortcut): Any? = pick(s, "cover")?.takeIf { it != "none" }?.let(::model) ?: s.icon

    fun hasLogo(s: Shortcut) = models(s, "logo").isNotEmpty()

    // ── Lab's in-game menu: its own background, logo and icon picks (Game details › Artwork › In-game menu) ──
    /** In-game slot → the app pages' slot it follows until it is set. */
    val IG_SLOTS = listOf("igbg" to "bg", "iglogo" to "logo", "igicon" to "icon")
    fun igBase(slot: String): String = IG_SLOTS.firstOrNull { it.first == slot }?.second ?: slot
    fun isIgSlot(slot: String) = IG_SLOTS.any { it.first == slot }

    /** The in-game menu's [base] picture ("logo", "icon"): its own pick first, then the app pages' (also when the pick does not load). */
    fun inGameModels(s: Shortcut, base: String): List<Any> {
        val p = pick(s, "ig$base")
        if (p == "none") return emptyList()
        return listOfNotNull(p?.let(::model)) + models(s, base)
    }

    /** The in-game menu's background: its own pick, else the app pages' (none where Game art background is off). */
    fun inGameBackground(s: Shortcut): List<Pic> {
        val p = pick(s, "igbg")
        if (p == "none") return emptyList()
        val app = look(s)?.pics.orEmpty()
        return if (p != null) listOf(Pic(model(p))) + app else app
    }

    /** The game's background as the backdrop draws it, or null when this game shows the Lab wallpaper. */
    fun look(s: Shortcut): Look? {
        val m = gameMode(s)
        if (m == OFF) return null
        val pic = s.getExtra(K_PICTURE, "").ifBlank { "auto" }
        if (pic == "lab") return null
        val cover = coverModel(s)?.let { Pic(it, blur = true) }
        val pics = when (pic) {
            "wide" -> models(s, "wide").map { Pic(it) } + listOfNotNull(cover)
            "blur" -> listOfNotNull(cover)
            else -> models(s, "bg").map { Pic(it) } + listOfNotNull(cover)
        }
        if (pics.isEmpty()) return null
        return Look(s.file.path + "|" + pic + "|" + pick(s, "bg") + "|" + pick(s, "wide"), pics, gamePanels(s), gameGrid(s), has3d(m), m)
    }
}

/** A picture from [pics], the next one when one does not load; nothing when none does. */
@Composable
internal fun LabPicture(pics: List<LabArt.Pic>, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, alignment: Alignment = Alignment.Center, onNone: (@Composable () -> Unit)? = null) {
    var i by remember(pics.map { it.model }) { mutableIntStateOf(0) }
    val p = pics.getOrNull(i)
    if (p == null) {
        onNone?.invoke()
        return
    }
    AsyncImage(
        model = p.model,
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier.then(if (p.blur) Modifier.blur(22.dp) else Modifier),
        onState = { if (it is AsyncImagePainter.State.Error) i++ },
    )
}

/**
 * Behind the Lab pages that belong to a game (Home, Library, the game page, Game details): the selected game's art in
 * place of the Lab wallpaper, cross-fading when the selection rests, drifting slowly, under a scrim so text stays
 * readable. The grid can run over it. Any other page fades back to the Lab wallpaper.
 */
@Composable
internal fun LabArtBackdrop(route: String?, modifier: Modifier = Modifier) {
    val onPage = route == DeckRoutes.HOME || route?.startsWith("lab_library") == true || route?.startsWith("lab_game") == true ||
        route?.startsWith(LabRoutes.DETAILS_PREFIX) == true
    val sel = LabArt.selected
    val tick = LabArt.tick
    val target = if (onPage && sel != null) remember(sel.file.path, tick, LabArt.mode, LabArt.panels, LabArt.grid) { LabArt.look(sel) } else null
    var shown by remember { mutableStateOf<LabArt.Look?>(null) }
    var last by remember { mutableStateOf<LabArt.Look?>(null) }
    LaunchedEffect(target?.key, target?.panels, target?.grid, target?.parallax, target == null) {
        // The selection rests a moment before the art follows (holding the D-pad does not flash every cover).
        if (target != null && shown != null) kotlinx.coroutines.delay(120)
        shown = target
        if (target != null) last = target
        LabArt.showing = target
    }
    val vis by animateFloatAsState(if (shown != null) 1f else 0f, tween(450), label = "labArtVis")
    val drift = rememberInfiniteTransition(label = "labArtDrift")
    val k by drift.animateFloat(1f, 1.04f, infiniteRepeatable(tween(42_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "labArtScale")
    val bg = MaterialTheme.colorScheme.background
    LabParallaxSensor(enabled = shown?.parallax == true)
    BoxWithConstraints(modifier) {
        val portrait = maxHeight > maxWidth
        val look = shown ?: last
        if (vis > 0.01f && look != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = vis }) {
                androidx.compose.animation.Crossfade(targetState = look, animationSpec = tween(450), label = "labArt") { l ->
                    Box(Modifier.fillMaxSize().graphicsLayer { val p = LabParallax.offset(l.parallax); val z = k * (if (l.parallax) 1.06f else 1f); scaleX = z; scaleY = z; translationX = -p.first * 30.dp.toPx(); translationY = -p.second * 22.dp.toPx(); rotationY = p.first * 4f; rotationX = -p.second * 3f }) {
                        LabPicture(l.pics, Modifier.fillMaxSize())
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        if (portrait) Brush.verticalGradient(0f to bg.copy(alpha = 0.55f), 0.22f to bg.copy(alpha = 0.38f), 0.6f to bg.copy(alpha = 0.45f), 1f to bg.copy(alpha = 0.8f))
                        else Brush.horizontalGradient(0f to bg.copy(alpha = 0.5f), 0.6f to bg.copy(alpha = 0.2f), 1f to bg.copy(alpha = 0.35f)),
                    ),
                )
                if (!portrait) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to bg.copy(alpha = 0.15f), 0.55f to bg.copy(alpha = 0.15f), 1f to bg.copy(alpha = 0.75f))))
                if (look.grid) Box(Modifier.fillMaxSize().graphicsLayer { val p = LabParallax.offset(look.parallax); translationX = p.first * 10.dp.toPx(); translationY = p.second * 8.dp.toPx() }) {
                    LabWallpaper(Modifier.fillMaxSize(), fill = false)
                }
            }
        }
    }
}

// ── cards (Appearance › Library cards, Card size) ──

/** The smallest card width per style and size, for the Library grid. */
internal fun labCardMin(style: String, size: String): Dp = when (style) {
    LabArt.WIDE -> when (size) { "s" -> 180.dp; "l" -> 340.dp; else -> 250.dp }
    LabArt.ICONS -> when (size) { "s" -> 80.dp; "l" -> 150.dp; else -> 110.dp }
    else -> when (size) { "s" -> 100.dp; "l" -> 190.dp; else -> 140.dp }
}

/** A game's wide cover (its pick, Steam's header, else the cover cropped), with its name over the bottom when [title]. */
@Composable
internal fun LabWideCard(s: Shortcut, modifier: Modifier, title: Boolean) {
    val tick = LabArt.tick
    val pics = remember(s.file.path, tick) { LabArt.models(s, "wide").map { LabArt.Pic(it) } }
    Box(modifier.clip(SimpleShape).background(Color(0xFF20242E))) {
        LabPicture(pics, Modifier.fillMaxSize(), onNone = { SimpleCover(s, Modifier.fillMaxSize(), title = false) })
        if (title) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 9.dp),
            ) { Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp) }
        }
    }
}

/** A game's icon: its pick, else its cover cropped square. */
@Composable
internal fun LabIcon(s: Shortcut, modifier: Modifier) {
    val tick = LabArt.tick
    val pics = remember(s.file.path, tick) { LabArt.models(s, "icon").map { LabArt.Pic(it) } }
    Box(modifier.clip(RoundedCornerShape(18)).background(Color(0xFF20242E))) {
        LabPicture(pics, Modifier.fillMaxSize(), onNone = { SimpleCover(s, Modifier.fillMaxSize(), title = false) })
    }
}

/** The in-game menu's icon: its own pick, else the app pages' icon, else the cover cropped square. */
@Composable
internal fun LabInGameIcon(s: Shortcut, modifier: Modifier) {
    val tick = LabArt.tick
    val pics = remember(s.file.path, tick) { LabArt.inGameModels(s, "icon").map { LabArt.Pic(it) } }
    Box(modifier.clip(RoundedCornerShape(18)).background(Color(0xFF20242E))) {
        LabPicture(pics, Modifier.fillMaxSize(), onNone = { SimpleCover(s, Modifier.fillMaxSize(), title = false) })
    }
}

/** An icon card: the icon and the name under it. */
@Composable
internal fun LabIconCard(s: Shortcut, modifier: Modifier, iconModifier: Modifier) {
    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabIcon(s, iconModifier)
        Text(s.name, color = SimpleInk, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

/** A Library grid card in the chosen style (the cover card is Simple's own). */
@Composable
internal fun LabGridCard(s: Shortcut, modifier: Modifier) {
    when (LabArt.cards) {
        LabArt.WIDE -> LabWideCard(s, modifier.aspectRatio(460f / 215f), title = true)
        LabArt.ICONS -> LabIconCard(s, modifier, Modifier.fillMaxWidth().aspectRatio(1f))
        else -> SimpleCover(s, modifier.aspectRatio(3f / 4f))
    }
}

// ── the logo in the name's place ──

/** Logo sizes per Adjust logo's size. */
internal fun labLogoScale(s: Shortcut): Float = when (s.getExtra(LabArt.K_LOGO_SIZE, "")) { "s" -> 0.75f; "l" -> 1.3f; else -> 1f }

/** Whether Adjust logo centres the logo (its centre spots), else it stays left. */
internal fun labLogoCentred(s: Shortcut): Boolean = s.getExtra(LabArt.K_LOGO_POS, "") in setOf("UpperCenter", "CenterCenter", "BottomCenter")

/**
 * The game's logo where its name goes (Home's big tile, the game page), [height] tall at medium size; the name as
 * text when the game has no logo or it does not load.
 */
@Composable
internal fun LabTitle(s: Shortcut, height: Dp, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val tick = LabArt.tick
    val pics = remember(s.file.path, tick) { LabArt.models(s, "logo").map { LabArt.Pic(it) } }
    val name: @Composable () -> Unit = {
        Text(s.name, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = fontSize, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
    if (pics.isEmpty()) { name(); return }
    val centred = labLogoCentred(s)
    LabPicture(
        pics,
        modifier.fillMaxWidth().height(height * labLogoScale(s)),
        contentScale = ContentScale.Fit,
        alignment = if (centred) Alignment.Center else Alignment.CenterStart,
        onNone = name,
    )
}
