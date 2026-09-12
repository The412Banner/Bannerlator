# Store "Media" tab — Amazon and Epic halves

Companion to `STORE_MEDIA_TAB.md` (shared model, `MediaTab`, `StoreMediaCache`, Steam, GOG).
Everything below was verified live on 2026-09-07.

## Epic

### How a library game finds its media
Library entries only carry `namespace` + `catalogItemId` + `appName`. The store offer is found with
the same GraphQL the storefront uses, filtered by sandbox:

```
POST https://store.epicgames.com/graphql   (browser User-Agent required)
Catalog.searchStore(namespace:$namespace, category:"games/edition/base", count:5, country, locale, allowCountries)
```

`EpicStoreCatalog.offerForLibraryGame(ns, catalogItemId)` picks the element whose
`items[].id == catalogItemId` (the store tab's ownership rule), else the first element.
`EpicStoreCatalog.libraryGameMedia(ns, catalogItemId): StoreMedia?` is the entry point the detail
page calls (blocking, IO thread, null on any failure = no tab).

### Two kinds of offers, two media sources
| offer | how to tell | screenshots | trailers |
|---|---|---|---|
| legacy (e.g. Control) | `productSlug` set (`"control"`), pageSlug without hex suffix | content API `pages[].data.gallery.galleryImages[].src` | content API `pages[].data.carousel.items[].video.recipes` |
| modern (e.g. Alone With You, Control Resonant) | `productSlug: null`, pageSlug like `alone-with-you-028a15` — the content API answers **404 "Page was not found"** | `keyImages[type=="featuredMedia"]` (also `*Screenshot*`) | `keyImages[type=="heroCarouselVideo"]` |

Legacy offers try the page first and fall back to keyImages; modern offers the other way round.

### Verified JSON paths
Content API — `GET https://store-content-ipv4.ak.epicgames.com/api/en-US/content/products/<slug>`:
- `pages[]._slug` — the `home` page is preferred (others are editions / DLC / season pass).
- `pages[].data.gallery.galleryImages[].src` — full-size screenshots (3840x2160 sources).
- `pages[].data.carousel.items[].image.src` — poster for that carousel item (may be empty).
- `pages[].data.carousel.items[].video.title` — trailer name (optional).
- `pages[].data.carousel.items[].video.recipes` — a JSON **string**:
  `{"en-US":[{"recipe":"video-fmp4","mediaRefId":"…"},{"recipe":"video-webm",…},{"recipe":"video-hls",…}]}`;
  the locale key varies per item (`de` seen), so `en-US` else the first locale; `video-fmp4`
  preferred, `video-webm` accepted, HLS never (VideoView cannot play it).

Resolving a legacy `mediaRefId`:
```
Media.getMediaRef(mediaRefId:"…"){ outputs{ key url contentType width height duration } }
```
→ `outputs[]` with `key` `high` (1920x1080) / `medium` (1280x720) / `low` (854x480) as
`https://media-cdn.epicgames.com/<id>/<id>-<key>.fmp4` (`video/mp4`), plus `audio` (m4a),
`thumbnail` (png) and `manifest` (DASH mpd). The app takes `low` → `medium` → `high` and the
`thumbnail` as poster when the carousel item has no image.

Resolving a modern `heroCarouselVideo` — `url` is `com.epicgames.video://<uuid>?cover=<url-encoded poster>`:
```
Video.fetchVideoByLocale(videoId:"<uuid>", locale:"en-US"){ recipe mediaRef{ outputs{ key url contentType } } }
```
→ a **list** of `{recipe: video-fmp4|video-webm|video-hls, mediaRef{outputs[…same shape…]}}`.
The `com.epicgames.video.qs://<uuid>` variant (seen on Control Resonant) is served by a different
backend: the same query returns a 500 (`Cannot read properties of null (reading 'en-US')`) and
`getMediaRef` 400 `media_not_found`; those trailers are skipped (screenshots still show).

All trailers of one page are resolved with ONE aliased POST
(`v0:Media{getMediaRef(…)} v1:Video{fetchVideoByLocale(…)} …`); a failed alias only drops that
trailer (`data.v<i>` null + an `errors[]` entry). Ids are validated against `[0-9a-fA-F-]` before
being embedded in the query.

Thumbnails: both `cdn1.epicgames.com` and `cdn2.unrealengine.com` honor `?resize=1&w=640`
(381 KB → 50 KB), used for the strip; the viewer loads the source URL.

Request budget per page open (then cached by `StoreMediaCache`): 1 search POST + at most 1 content
GET + 1 resolve POST. Caps: 24 screenshots, 4 trailers.

Fixtures: `app/src/test/java/com/winlator/star/store/EpicStoreMediaParserTest.kt`.

## Amazon

There is no media endpoint. `GetEntitlements` already returns everything under
`entitlements[].product.productDetail.details`:
- `screenshots[]` — image URLs
- `videos[]` — direct mp4 URLs
- `trailerImageUrl` — poster for the trailer (optional)

(the same fields nile reads; presence varies per title). `AmazonApiClient.parseMedia` stores them
on `AmazonGame.screenshots / videos / trailerImageUrl`; `AmazonLibrarySync.putMedia/readMedia`
round-trip them through `amazon_library_cache` from BOTH writers (`AmazonGamesActivity.saveCachedGames`
and `AmazonLibraryRepo.saveCache`); the detail page reads
`AmazonLibrarySync.cachedMedia(ctx, productId)` — offline, no request.

**Caveat — "sync the library to see media":** a cache written before this change carries no media.
The Media tab appears only after the Amazon library re-syncs (open the Amazon games screen /
pull to refresh; `AmazonLibraryRepo.sync` is throttled to 15 min unless forced).

Debug builds log the `details` key set once per process (`BH_AMAZON`:
`productDetail.details keys: …`) so the field names can be confirmed on a real account.

Fixtures: `app/src/test/java/com/winlator/star/store/AmazonMediaParserTest.kt`.

## Wiring (Phase B)
- `AmazonGameDetailActivity`: tabs `Details / DLC` + conditional `Media` at index 2; the former
  `else ->` DLC branch becomes `1 ->`.
- `EpicGameDetailActivity`: `Details / DLC / Cloud saves` + conditional `Media` at index 3, loaded by
  `LaunchedEffect(namespace, catalogItemId)` through `StoreMediaCache` (store key `epic`, id
  `catalogItemId`).
Both keep their tab indices stable (the Media tab is appended only when `media.isEmpty == false`).
