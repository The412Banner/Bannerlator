# Store "Media" tab — trailers + screenshots on the game detail pages

One shared tab for the four store detail pages (Steam, GOG, Epic, Amazon) and the store-only
catalog page: a row of trailer posters, a row of screenshot thumbnails, a full-screen swipe/zoom
viewer, and one video handoff. Each store only supplies the data; the UI, cache, viewer and
playback are shared.

## Shared

### Model — `store/download/StoreMediaTab.kt`

```kotlin
data class MediaImage(val thumb: String, val full: String)
sealed interface MediaVideo { val poster: String?; val title: String
  data class Direct(val url: String, override val poster: String?, override val title: String) : MediaVideo   // mp4 / webm
  data class YouTube(val id: String, override val poster: String?, override val title: String) : MediaVideo }
data class StoreMedia(val screenshots: List<MediaImage>, val videos: List<MediaVideo>) {
  val isEmpty: Boolean; val count: Int
  companion object { val EMPTY; const val MAX_SCREENSHOTS = 24 } }

@Composable fun MediaTab(media: StoreMedia?, loading: Boolean, storeLabel: String, onOpenVideo: (MediaVideo) -> Unit)
@Composable fun ScreenshotViewer(images: List<MediaImage>, startIndex: Int, onDismiss: () -> Unit)
```

- `MediaTab`: `media == null && loading` → spinner; null / empty → `StoreNotice("No media", "<storeLabel>
  published no videos or screenshots for this title.")`; otherwise a "Videos" strip (poster tile,
  play badge, caption; tap → `onOpenVideo`, long-press → `MediaPlayback.openExternal`) and a
  "Screenshots" strip (tap → viewer). Strips are full-bleed `LazyRow`s with 300x169 tiles and 16dp
  content padding — NOT inside a `StoreSection` card, because the card's 32dp inset leaves less than
  300dp on a 360dp-wide phone in portrait. Thumbs only in the strip; the full image only in the viewer.
  The viewer's open index is `rememberSaveable`, so it survives the rotation-recreate of the Steam/GOG
  activities.
- `ScreenshotViewer`: full-screen `Dialog(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)`
  whose window is stretched to `MATCH_PARENT` with the system bars hidden (swipe to peek), a
  `HorizontalPager` (`beyondBoundsPageCount = 1`, keyed by URL) of `AsyncImage(full, Fit)` pages with
  pinch zoom 1x–4x, pan while zoomed, double-tap 1x ↔ 2.5x at the tap point, and a "3 / 12" counter.
  Gesture rule: a single finger at 1x is not consumed (the pager swipes); two fingers, or one finger
  while zoomed, drive the page and are consumed; `userScrollEnabled` is off while zoomed. Fills the
  screen in both orientations.

### Playback — `store/MediaPlayback.kt`

`MediaPlayback.openVideo(ctx, video)` is what every page passes as `onOpenVideo`:

- `MediaVideo.Direct` → `MediaVideoActivity`: black stage, `VideoView` + platform `MediaController`
  (tap the video to toggle it) inside a 16:9 box that fits the width in portrait and the height in
  landscape, close/title/open-external bar over the top edge, buffering spinner. Manifest:
  `screenOrientation="sensor"` + `configChanges=…orientation|screenSize…`, so a rotation re-lays-out
  the same player instead of re-buffering; position is kept across pause/resume and in the saved
  state. A decoder error hands the URL to an external player and closes.
- `MediaVideo.YouTube` → `StoreWebActivity.intent(ctx, "https://www.youtube.com/embed/<id>?autoplay=1&playsinline=1&rel=0", title, allowAutoplay = true)`.
  The new `EXTRA_ALLOW_AUTOPLAY` flips `mediaPlaybackRequiresUserGesture` off; the WebView also
  implements `onShowCustomView`/`onHideCustomView` so the embed's fullscreen button works (back
  leaves fullscreen first).
- `MediaPlayback.openExternal(ctx, video)`: `vnd.youtube:<id>` → `youtube.com/watch` fallback, or
  `ACTION_VIEW video/mp4|webm` for direct streams.

### Cache — `store/StoreMediaCache.kt`

`StoreMediaCache.get(ctx, store, id): StoreMedia?` / `put(ctx, store, id, media, miss = false)`.
In-memory `ConcurrentHashMap` + prefs `store_media_cache`, key `"<STORE>:<id>"`, JSON
`{at, miss, shots:[{t,f}], videos:[{k:"d"|"y", u|id, p, n}]}`. TTL by what the fetch found: non-empty
7 days · genuinely empty 6 hours · `miss = true` (429 / transport / unparsable) 2 minutes. `get`
returns null only when nothing usable is cached (the page then fetches and `put`s); a fresh empty
entry comes back as `StoreMedia.EMPTY` so the page does not refetch. Amazon bypasses this (media
rides in its library cache).

Per page: `LaunchedEffect(id) { media = withContext(IO) { cache.get(...) ?: fetch().also { put } } }`,
`tabs` gets `"Media"` appended only when `media?.isEmpty == false` (existing indices unchanged), the
tab badge is `media.count`.

### Tests

`app/src/test/java/com/winlator/star/store/`: `SteamMediaParseTest` (screenshots + movies,
https rewrite, rendition order, `"data": []`, 429/garbage → null, 24 cap), `GogMediaParseTest`
(ggvgm/ggvgm_2x, YouTube-only, captions), `StoreMediaCacheCodecTest` (prefs JSON round trip).
The parsers are pure (no `android.util.Log` on the happy path) so they run on the JVM.

## Steam

- Source: `SteamStoreSearch.fetchMedia(appId, cc): StoreMedia?` →
  `store.steampowered.com/api/appdetails?appids=<id>&cc=<cc>&l=english&filters=screenshots,movies`
  (`cc` from `SteamRegion.storeCountryCode`). Three outcomes: non-empty media · `StoreMedia.EMPTY`
  (`success:false`, or the `"data": []` array Steam returns when the filter matched nothing) · `null`
  when the request could not tell (HTTP 429 = the ~200 req / 5 min storefront limiter, other non-2xx,
  transport, malformed body). `parseMedia(body, appId)` is the pure part.
- Screenshots: `path_thumbnail` (600x338) → strip, `path_full` → viewer, capped at 24. Movies:
  `mp4.480` by default (mobile-data friendly) → `mp4.max` → `webm.480` → `webm.max`; `name` (blank →
  "Trailer N"); `thumbnail` poster. Every URL is forced to https — the CDN hands out
  `http://video.akamai.steamstatic.com/…` and `network_security_config.xml` permits cleartext only to
  Steam CONTENT (depot) hosts.
- Page: `SteamGameDetailActivity` — `DetailTab.MEDIA("Media")` appended to the enum; the strip
  (`SteamDetailTabs(tabs = …)`) lists it only while `media?.isEmpty == false`, with a count badge;
  the loader sits beside the achievements/DLC loaders (`remember(appId)` state + `LaunchedEffect(appId)`,
  cache first); selection falls back to Details if the tab disappears. Body: `DetailTab.MEDIA ->
  MediaTab(media, mediaLoading, "Steam") { MediaPlayback.openVideo(context, it) }`.

## GOG

- Source: `GogStoreCatalog.product(id)` now requests `expand=description,screenshots,videos`;
  `ProductDetail` gains `media: StoreMedia` (the legacy `screenshots: List<String>` strip list is
  kept for callers). Screenshots: `formatted_images[formatter_name == "ggvgm"].image_url` (or the
  `formatter_template_url` at `ggvgm`) → thumb, the template at `ggvgm_2x` → full (thumb when there
  is no template); protocol-relative URLs absolutized. Videos: `videos[]{video_id, thumbnail_url,
  provider}` → `MediaVideo.YouTube` (non-YouTube providers skipped; missing poster →
  `img.youtube.com/vi/<id>/hqdefault.jpg`); GOG names none, so captions are "Trailer" / "Trailer N".
- Page: `GogGameDetailActivity` — `media`/`mediaLoading` activity state, `loadMedia()` on create
  (cache → `product(gameId)?.media`, `gameId` = product id; a null product is cached as a 2-minute
  miss), passed into `GogGameDetailScreen`; tabs `Details · DLC · Cloud saves [· Media]`, cloud-saves
  branch is now `2 ->`, `3 -> MediaTab(...)`, badge at index 3.
- Catalog page (`StoreCatalogDetailActivity`, unowned GOG/Epic titles): the old "Screenshots" tab is
  now "Media" and renders `MediaTab` from `ProductDetail.media` (GOG) or the offer's screenshot URLs
  wrapped as `MediaImage(url, url)` (Epic, until the Epic section below lands).

## Epic

(Owned by the Amazon/Epic branch — to be filled in.)

## Amazon

(Owned by the Amazon/Epic branch — to be filled in.)

## Landscape

All detail activities are sensor-orientation. Verified by reading the layout, not yet on device:
the strips are horizontal `LazyRow`s inside the scaffold's vertical scroll, so in landscape they
simply show more tiles; the viewer's window is `MATCH_PARENT` both ways with the pager page filling
it (`ContentScale.Fit`); the player's 16:9 box fits the height in landscape (controls, anchored to the
video's bottom edge, land at the bottom of the screen) and the width in portrait. Steam and GOG detail
activities recreate on rotation — the tab selection resets (pre-existing behaviour) but the viewer's
open index and the cached media repaint instantly.

## How to verify on device

- Steam: open any owned title with a store page (e.g. Team Fortress 2 / Half-Life 2 / Risk of Rain 2)
  → "Media" appears after the appdetails call; badge = screenshots + movies. A 429 shows no tab; reopen
  after two minutes. `logcat -s SteamStoreSearch` prints `fetchMedia(<id>): HTTP 429 (rate limited)`.
- GOG: open an owned title (Gunslugs 3 has screenshots + a trailer) → fourth tab "Media"; the trailer
  opens the YouTube embed with autoplay; long-press opens the YouTube app.
- Catalog: any GOG/Epic store card → "Media" tab.
