# GOG Room-backed Library Sync (gap #8) — Plan & Task List

**Branch:** `feat/gog-room-library` (off current `main` `5965b802`)

## 🔴 DECISIVE VERDICT: Room is NOT present anywhere in the app
This is the single biggest factor and it flips the roadmap's cost estimate.

- **No Room in the build.** `gradle/libs.versions.toml` has **zero** `room` / `ksp` / `kapt` /
  `sqlite` entries. `app/build.gradle` applies `kotlin-android` + `kotlin.plugin.compose` and
  uses `annotationProcessor` for exactly **one** thing — Glide's compiler
  (`com.github.bumptech.glide:compiler`). There is **no** `kotlin-kapt`, no `com.google.devtools.ksp`
  plugin, no `androidx.room:*` dependency.
- **No Room in the source.** Grep for `androidx.room` / `@Entity` / `@Dao` / `@Database` /
  `RoomDatabase` across all `.kt`/`.java` = **no matches**. There is not one DAO, entity, or
  database class in the entire app.
- **The whole app is prefs-backed.** Every store (GOG, Epic, Amazon, Steam-side) persists library
  + install state in `SharedPreferences` + JSON blobs. The closest thing to a "DB" is the
  cross-store `download` package's `DownloadRegistry`, which is itself prefs/JSON, not SQLite.

**Consequence for scope:** the roadmap grades #8 **"M (medium)"**, but that grade assumes Room is
already stood up (as it is in GameNative). It is **not**. A literal Room port therefore carries a
**structural tax the roadmap didn't price**: adding the KSP (or kapt) toolchain to the build,
introducing the app's first `RoomDatabase`, first entity/DAO, first schema-migration surface, and
first `Dispatchers.IO`-only query discipline — all for **one store's library screen**, in an app
where nothing else uses an ORM. **Honest re-grade: M→L for the literal Room version.**

Because of that, this plan splits the gap into **(A) the user-visible behaviors** — incremental,
throttled, filtered, cover-art — which are the actual prize and can ship **without** Room, and
**(B) the Room substrate**, an optional structural upgrade. The recommendation (below) is to ship
(A) first on the existing prefs cache made incremental, and treat (B) as a deliberate follow-up —
NOT to bolt Room onto the build for a single screen as step one.

**Clean-room note.** GN's GOG code is **GPL-3.0**. Everything here is reconstructed from the **live
GOG library/gamesdb protocol** (probed read-only, real shapes captured below) and our **own existing
GOG prefs flow**. GN symbols are cited as **behavioral anchors by name only** — `GOGGameDao`,
`GOGGame`, `refreshLibrary` (new-id diff), `SYNC_THROTTLE_MILLIS`, the secret/DLC/prime/size-0
exclusion filter, the gamesdb vertical-cover backfill. **No GN Kotlin is read or lifted.**

---

## In plain English (what this does for the user)
- **Fast library opens.** Today, opening the GOG library re-fetches **every owned game** from
  `api.gog.com/products/{id}` on a refresh (N HTTP calls + an SGDB cover lookup each). After the
  first sync, we should fetch **only newly-purchased** titles and reuse everything else.
- **Don't hammer on every open.** A 15-minute throttle so returning to the screen doesn't re-sync
  the whole account.
- **Cleaner list.** Hidden/secret products, DLC entries, and junk (size-0 / non-game) are filtered
  out of the main list instead of leaking in.
- **Proper vertical box-art.** Real GOG 2:3 covers backfilled from `gamesdb.gog.com` for the
  poster/grid views, instead of today's SGDB-or-icon best-effort.

---

## What we have today — current GOG library flow (the map)

### Storage: 100% prefs, file `bh_gog_prefs`
The GOG library and everything around it live in one `SharedPreferences` file. Key inventory
(verified by grep across `store/`):

| Key | Owner / writer | Readers | Role |
|---|---|---|---|
| `gog_library_cache` | `GogGamesActivity.saveCachedGames` (JSON array of the 7-field `GogGame`) | `GogGamesActivity.loadCachedGames`, **`GogLibrarySync.cachedDetail`**, `GogLibrarySync.seed` (meta map) | **The library cache — the thing #8 replaces.** |
| `gog_gen_<id>` | `fetchGame` | detail generation badge | per-game scalar |
| `gog_release_<id>` | `fetchGame` | detail page | per-game scalar |
| `gog_rating_<id>` | `fetchGame` | detail page | per-game scalar |
| `gog_size_<id>` | `fetchGame`, `startDownload` | install dialog, DL manager, seed | per-game scalar |
| `gog_dlcs_<baseId>` | `saveDlcBuffer` (JSON) | `GogGameDetailActivity` DLC section, gap#5 install | **entitlement data — NOT install state** |
| `gog_exe_<id>` / `gog_dir_<id>` | download engine | `GogInstallState.isInstalled`, detail, seed | **install truth** |
| `gog_deps_<id>` | `GogDownloadManager` (gap#3) | `GogRedistInstaller`, detail | redist deps |
| `gog_save_dir_<id>` | detail folder-picker | Save Manager, cloud save | **user data** |
| `access_token` / `refresh_token` / `bh_gog_login_time` / `bh_gog_expires_in` | login/refresh | sync | auth |

### The sync path (`GogGamesActivity.syncLibrary`, `Dispatchers.IO`)
1. Token check → `GogTokenRefresh.refresh` if expired.
2. `GET https://embed.gog.com/user/data/games` (Bearer) → `{ "owned": [<long ids>] }`. **This is the
   owned-id list — already in hand; it is the diff source #8 needs.** (One id, `1801418160`, is
   hard-skipped — a GOG "goodie".)
3. For **every** id: `fetchGame(id)` on a 5-thread pool — each does:
   - `GET api.gog.com/products/{id}?expand=downloads,description` (Bearer).
   - Filters inline: `is_secret==true` → drop; `game_type=="dlc"` → route to `gogDlcBuffer`
     (later flushed to `gog_dlcs_<baseId>`), return null. **(We ALREADY implement the secret+DLC
     half of GN's exclusion filter — it's just not DB-backed.)**
   - Cover = `sgdbFetchCover(title)` (SteamGridDB 600×900) → fallback product `images.icon` /
     `images.background`.
   - Generation = max of `content-system.gog.com/products/{id}/os/windows/builds?generation=2`
     `items[].generation`; persisted to `gog_gen_<id>`.
   - Writes `gog_release_/gog_rating_/gog_size_` scalars.
   - Returns a `GogGame(gameId, title, imageUrl, description, developer, category, generation)`.
4. `saveDlcBuffer()` + `saveCachedGames(games)` (serializes the whole list to `gog_library_cache`).
5. UI reads `allGames` (sorted), `applyFilter`.
6. **Cold open** (`onCreate`): `loadCachedGames()` renders instantly from the pref; then
   `startSync(cached.isEmpty())` — but a **manual refresh (`onRefresh`) re-syncs ALL ids**. There is
   **no incremental diff and no throttle** — every refresh is a full N-fetch.

### The readers that MUST NOT break (coexistence surface)
- **`GogLibrarySync.cachedDetail(ctx, gameId)`** — a **synchronous** prefs+JSON read of
  `gog_library_cache`, returning `DetailExtras`. Called by:
  - **`SteamSaveManagerActivity`** (GOG tab: `loadGogSaveStatuses`, cover lookup ~L197/L1193/L1218) —
    the Save Manager depends on this for name/cover.
  - **`DownloadManagerActivity`** (~L219) — hydrates DL-manager rows.
  These are the highest-risk readers: they call a cheap synchronous method, possibly on the main
  thread. Room queries can't run on the main thread without `allowMainThreadQueries()`.
- **`GogLibrarySync.seed`** — builds a `gameId → Meta(title, cover)` map from `gog_library_cache`
  to name/cover the cross-store Download Manager rows.
- **`GogGameDetailActivity`** — reads `gog_dlcs_<id>`, `gog_deps_<id>`, `gog_build_<id>`,
  `gog_save_dir_<id>`, `gog_gen_/release_/rating_`. **These are separate keys; #8 must not touch
  them.** Install truth stays owned by **`GogInstallState`** (single owner of `gog_exe_/gog_dir_/…`).

---

## Verified GOG protocol (real shapes captured 2026-08-22)

### 1. Owned-id list (token-gated) — the diff source
`GET https://embed.gog.com/user/data/games`, header `Authorization: Bearer <access_token>` →
```json
{ "owned": [1207664643, 1495134320, 1801418160, ...] }
```
Cheap, single call, returns the **complete** owned set. **Incremental diff = `owned` minus the ids
already stored.** (Needs the user's token — cannot be probed here without login.)

### 2. Product detail (token-gated) — fetch ONLY for new ids
`GET https://api.gog.com/products/{id}?expand=downloads,description` (Bearer). Fields already used:
`title.*`, `is_secret`, `game_type`, `images.{icon,background}`, `description.lead`,
`developers[].name`, `genres[].name`, `release_date`, `rating`, `required_game`/`requiredGames`
(DLC parent). (Token-gated.)

### 3. Generation (token-gated)
`GET https://content-system.gog.com/products/{id}/os/windows/builds?generation=2` (Bearer) →
`items[].generation`; take the max. (Token-gated.)

### 4. Vertical 2:3 cover — `gamesdb.gog.com`, **UNAUTHENTICATED** (probed live, real shapes)
Two hops (GN's `GogMapRepository` behavior):
- **Hop 1** `GET https://gamesdb.gog.com/platforms/gog/external_releases/{gogProductId}` →
  `{ "game_id": "51071842242777057", "external_id": "1207664643", "dlcs":[…] }`. Maps the GOG
  storefront product id → the internal **gamesdb** game id.
- **Hop 2** `GET https://gamesdb.gog.com/games/{game_id}` → an object whose keys include
  `vertical_cover`, `cover`, `logo`, `square_icon`, `background`. Each is:
  ```json
  "vertical_cover": { "url_format": "https://images.gog.com/86856f62…67b{formatter}.{ext}?namespace=gamesdb" }
  ```
  The source is already **2:3**. Build the final URL by substituting the templated tokens:
  ```
  url = url_format.replace("{formatter}", "").replace("{ext}", "webp")
  ```
  (`{formatter}` supports GOG size variants, e.g. `_glx_vertical_cover`; empty = full-res. `{ext}`
  = `webp`/`jpg`/`png`.) **Verified both hops return HTTP 200 unauthenticated** for product
  `1207664643` → gamesdb game `51071842242777057` → a real `images.gog.com` vertical cover URL.

**Cost note:** cover backfill is **2 extra unauth GETs per game** on top of the token'd product
fetch — so it belongs in the incremental path (only for **new** ids), and should be backfillable
lazily/idempotently (store the URL once; never re-hit gamesdb for a game we already covered).

---

## Recommended shape — behaviors first, Room as substrate (with a clear decision gate)

There are two honest ways to satisfy #8. Both deliver the same four behaviors; they differ only in
the storage substrate.

### Option A (RECOMMENDED for P1) — incremental sync on the **existing prefs cache**, no Room
Deliver the user-visible win without touching the build system:
- **Incremental:** diff `owned` (endpoint 1) against the ids already in `gog_library_cache`; call
  `fetchGame` **only for new ids**; merge into the cached list; write back.
- **Throttle:** store `gog_last_full_sync` (millis) in prefs; a non-forced open/refresh within
  15 min short-circuits (owned-id diff still runs — it's one cheap call — but per-game refetch is
  skipped for known ids). A forced refresh (`onRefresh` long-press or an explicit "Full re-sync")
  bypasses the throttle.
- **Filter:** we already drop secret + DLC; add the **prime/size-0/non-game** guard (see filter
  spec below) at merge time.
- **Cover-art:** backfill `vertical_cover` (endpoint 4) for new ids; store as a new field in the
  cached `GogGame` JSON (`verticalCover`); render it in poster/grid, fall back to the current
  SGDB/icon chain.
- **Removals:** ids in the cache but no longer in `owned` (returned/revoked) are pruned from the
  list (leave install-state prefs to `GogInstallState`).

This is **additive, ~zero-risk to the readers** (the `gog_library_cache` JSON stays the source of
truth, all `cachedDetail`/seed readers keep working untouched), and ships the whole prize. It is a
**genuinely medium** change — matching the roadmap's grade — because it reuses the existing sync
scaffold instead of standing up an ORM.

### Option B (the literal Room port) — Room as the write authority, prefs cache as a read-through mirror
If we want the actual DB the roadmap names (query power, indexed lookups, a real schema, future
Verify/Repair + recommendations tables), stand up Room **but keep `gog_library_cache` as a derived
read-through mirror** so no synchronous reader breaks:
- Room becomes the **source of truth** for sync (diff/throttle/filter/cover + `ownedIds()` query).
- On every sync, after writing the DB, **serialize the same array back into `gog_library_cache`**
  (identical JSON shape). `GogLibrarySync.cachedDetail`, `seed`, and the Save Manager keep reading
  the pref **unchanged** — they never learn Room exists. This sidesteps the main-thread-query
  problem entirely for the legacy synchronous readers.
- New code (the library screen) reads Room directly on `Dispatchers.IO`.
- Later (P2) migrate `cachedDetail`/seed to a Room query and retire the pref mirror.

**Recommendation:** **Ship Option A as P1** (the roadmap's medium, the user value, no build-system
risk). **Gate Option B** behind an explicit decision — it's worth doing only if we're about to build
more DB-shaped GOG features (Verify/Repair #9, recommendations #13) that would amortize the Room
tax. Doing Room *just* for this one screen is a poor trade. The rest of this doc specifies **both**
so either can be executed; the entity/DAO section is the Option-B substrate.

---

## Filter spec (shared by A and B) — GN's secret/DLC/prime/size-0 exclusion
Applied when deciding whether a fetched product enters the library list:
- `is_secret == true` → **drop** (already done).
- `game_type == "dlc"` → **route to `gog_dlcs_<baseId>` buffer, not the list** (already done).
- **prime / not-owned-as-game** — GN excludes entitlement-only / pre-order / unreleased rows. Map to:
  skip products with no Windows content **and** no standalone-installer download (i.e. nothing this
  app can ever install). ⚠️ **Caveat:** don't over-filter — our **standalone-installer fallback**
  (`runInstaller`, a feature GN lacks) means some "no content-system build" games ARE installable.
  Gate size-0/no-content exclusion on "no builds AND no `downloads.installers`", not on build
  presence alone, or we'd hide classic installer-only titles we actually support.
- `game_type` present and not `game`/`pack` → drop non-game types (packs kept).
- Keep the `1801418160` goodie hard-skip already in `syncLibrary`.

---

## Entity / DAO shape (Option B substrate)

**Scope discipline:** Room stores **library catalog metadata only** — the data behind the list/poster
screen and `cachedDetail`. It does **NOT** own install truth (`gog_exe_/gog_dir_` stay with
`GogInstallState`), DLC entitlement (`gog_dlcs_`), redist deps (`gog_deps_`), or the save folder
(`gog_save_dir_`). Those keep their prefs and their existing owners.

```kotlin
@Entity(tableName = "gog_games")
data class GogGameEntity(
    @PrimaryKey val gameId: String,     // GOG storefront product id (string, matches current key)
    val title: String,
    val imageUrl: String,               // current cover (SGDB/icon chain) — preserves today's behavior
    val verticalCover: String?,         // NEW: gamesdb 2:3 cover, null until backfilled
    val description: String,
    val developer: String,
    val category: String,
    val generation: Int,
    val releaseDate: String?,           // folds gog_release_ (mirror, not a move)
    val rating: Int,                    // folds gog_rating_
    val sizeBytes: Long,                // folds gog_size_ (mirror)
    val owned: Boolean,                 // in the last owned-set (for prune/soft-delete)
    val lastFetchedMillis: Long,        // per-game throttle / staleness
)
```

```kotlin
@Dao
interface GogGameDao {
    @Query("SELECT * FROM gog_games WHERE owned = 1 ORDER BY LOWER(title)")
    suspend fun getAllOwned(): List<GogGameEntity>

    @Query("SELECT gameId FROM gog_games")
    suspend fun knownIds(): List<String>          // the diff source vs `owned`

    @Query("SELECT * FROM gog_games WHERE gameId = :id")
    suspend fun getById(id: String): GogGameEntity?

    @Upsert
    suspend fun upsertAll(rows: List<GogGameEntity>)

    @Query("UPDATE gog_games SET verticalCover = :url WHERE gameId = :id")
    suspend fun setVerticalCover(id: String, url: String)

    @Query("UPDATE gog_games SET owned = 0 WHERE gameId IN (:ids)")
    suspend fun markUnowned(ids: List<String>)     // soft-delete revoked games

    @Query("SELECT COUNT(*) FROM gog_games")
    suspend fun count(): Int
}
```

```kotlin
@Database(entities = [GogGameEntity::class], version = 1, exportSchema = true)
abstract class GogDatabase : RoomDatabase() {
    abstract fun gogGameDao(): GogGameDao
    companion object { /* single INSTANCE, Room.databaseBuilder(...).build() */ }
}
```

**Sync bookkeeping** (throttle) stays a single prefs scalar `gog_last_full_sync` — it's one value,
not worth a table.

---

## Coexistence / migration strategy (don't break the readers)

**Guiding rule:** the `gog_library_cache` JSON pref is the **compat contract**. Whatever substrate
we pick, that pref keeps existing (Option A: it stays THE store; Option B: it becomes a
Room-written mirror). This keeps **every current reader working with zero edits**:
- `GogLibrarySync.cachedDetail` (Save Manager + DL manager) — unchanged, still a synchronous pref read.
- `GogLibrarySync.seed` meta map — unchanged.
- The `verticalCover` field is **added** to the cached JSON objects (readers that don't know it just
  ignore the extra key — `optString` is forgiving); `cachedDetail` can start returning it later.
- Per-id scalar prefs (`gog_gen_/release_/rating_/size_`) stay written exactly as now (their other
  writers/readers — `fetchGame`, detail page, DL manager — are untouched). Room, if used, **mirrors**
  them; it does not become their owner.
- `gog_dlcs_`, `gog_deps_`, `gog_save_dir_`, `gog_exe_/dir_` — **never touched by #8**.

**First-run migration (Option B only):** on first open after the DB lands, seed Room **from the
existing `gog_library_cache`** (parse the JSON array once → `upsertAll`) so the DB isn't empty and we
don't force a full re-sync. No destructive migration; the pref is the seed and then the mirror.

**Schema versioning (Option B):** `version = 1`, `exportSchema = true` (add the schema dir to
`build.gradle` `ksp { arg("room.schemaLocation", …) }`). Since the DB is seedable from prefs, early
schema bumps can use `fallbackToDestructiveMigration()` safely — a wipe just re-syncs from the
network/pref, losing nothing durable. Note this cushion in the code so a future author knows the DB
is a **cache**, not a system of record.

---

## Concrete files to touch

### Option A (P1, recommended)
- **`app/src/main/java/com/winlator/star/store/GogGamesActivity.kt`** — the core change:
  - `syncLibrary`: after the `owned` fetch, diff against `loadCachedGames()` ids; `fetchGame` **only
    new ids**; merge + prune unowned; write back via `saveCachedGames`.
  - Add `gog_last_full_sync` throttle around the per-game refetch; wire a forced-refresh path.
  - `fetchGame` (or a new `fetchVerticalCover`): backfill the gamesdb 2:3 cover for new ids.
  - Add `verticalCover` to `GogGame` serialization (`saveCachedGames`/`loadCachedGames`) and render
    it in `GameGridTile`/poster.
  - Add the prime/size-0/non-game filter at merge time.
- **`app/src/main/java/com/winlator/star/store/GogGame.java`** — add the `verticalCover` field
  (nullable) + accessor. (This is the only reason to edit the model; keep it additive.)
- **`app/src/main/java/com/winlator/star/store/GogLibrarySync.kt`** — optionally teach `cachedDetail`
  to surface `verticalCover` so the Save Manager/DL manager can show 2:3 art (additive, safe).

### Option B (additional, if the Room substrate is approved)
- `gradle/libs.versions.toml` + `app/build.gradle` — **new**: add the KSP plugin
  (`com.google.devtools.ksp`), `androidx.room:room-runtime`, `room-ktx`, and `room-compiler` (KSP).
  This is the build-system tax (the app's first ORM + annotation-processor-via-KSP; today only Glide
  uses `annotationProcessor`).
- `app/src/main/java/com/winlator/star/store/db/GogDatabase.kt`, `GogGameEntity.kt`,
  `GogGameDao.kt` — **new** (the substrate above).
- `GogGamesActivity.kt` — read/write Room instead of (or alongside) the pref; write the pref mirror
  after each sync.

---

## Keystone risks
1. **Room tax is real and one-screen-deep.** Standing up KSP + the first `RoomDatabase` for a single
   library screen is a structural change with build-time cost (KSP config, schema dir, CI) and no
   other consumer to amortize it. **Mitigation:** ship Option A first; only take Option B when a
   second DB-shaped feature (#9 Verify/Repair, #13 recommendations) is queued.
2. **Synchronous `cachedDetail` vs Room's main-thread ban.** The Save Manager reads `cachedDetail`
   synchronously (likely main thread). A naive Room migration that repoints it at a DAO would need
   `allowMainThreadQueries()` (bad) or an async rewrite of the caller (blast radius into the Save
   Manager). **Mitigation:** the read-through pref mirror — legacy readers never touch Room.
3. **First-run / cold cost is unchanged for a brand-new account.** Incremental only helps on the
   *second* sync; the very first sync of a large library is still N product fetches **plus** now
   2 gamesdb hops per game for covers. **Mitigation:** cover backfill must be lazy + idempotent
   (only new/uncovered ids), never re-hit gamesdb for a game we already covered; keep the existing
   5-thread pool; consider deferring cover backfill to a second pass so the list paints first.
4. **Token-gated endpoints.** The owned-id list, product detail, and builds endpoint all need the
   user's Bearer token (can't be regression-tested offline). gamesdb (covers) is unauth. The
   throttle/diff must survive a token refresh mid-sync (the existing refresh path already runs first).
5. **Keeping install/DLC/deps readers intact.** `GogInstallState` is the **single owner** of install
   truth; `gog_dlcs_`/`gog_deps_`/`gog_save_dir_` are separate concerns. #8 must treat all of these
   as read-only-not-even-read. Prune-on-unowned must **not** call `GogInstallState.purge` (a game
   removed from the *store list* may still be installed on disk).
6. **Over-filtering hides installable classics.** Our standalone-installer fallback means "no
   content-system build" ≠ "uninstallable". Gate the size-0/no-content exclusion on *also* having no
   `downloads.installers`, or we regress a class of games GN can't even do (risk called out in the
   filter spec).
7. **gamesdb mapping misses / drift.** Not every product resolves through
   `external_releases → games`; some return no vertical cover. Fall back to the existing SGDB/icon
   chain; store null and don't retry forever. gamesdb ids are internal and can change — always map
   via the product id, never cache the gamesdb game_id as a key.

---

## Open questions
- **Room or not?** The core decision. Recommendation: **Option A now**, Option B only when a second
  DB feature justifies the toolchain. Confirm before adding KSP to the build.
- **Throttle window** — GN uses 15 min. Keep 15, or shorter for a store where new purchases are
  common? Should the owned-id diff (cheap) run every open while only the per-game refetch is
  throttled? (Lean: yes — always diff, throttle the heavy refetch.)
- **Cover formatter** — full-res (`{formatter}=""`) vs a sized variant (`_glx_vertical_cover`) for
  the poster grid? Full-res is simplest but heavier; decide per the grid tile size.
- **Cover for existing installs** — backfill vertical covers for the games already in the cache on
  first run (nice art immediately), or only for newly-synced ids (cheaper)? A one-time lazy backfill
  pass is the middle ground.
- **Prune semantics** — hard-remove unowned games from the list, or keep + grey them (a game you
  refunded but still have installed)? (Lean: keep if installed-on-disk, drop from list otherwise.)
- **Should `verticalCover` flow into `DownloadEntry.cover`** (seed/DL manager) so the cross-store
  Manager shows 2:3 art too? Additive, but touches the seed's Meta map.

---

## Task checklist

### P1 — Option A (incremental + throttle + filter + cover on the prefs cache)
- [ ] `GogGamesActivity.syncLibrary`: diff `owned` vs cached ids → `fetchGame` **new ids only**;
      merge + prune unowned (do NOT purge install state).
- [ ] Add `gog_last_full_sync` throttle (15 min); always run the cheap owned-id diff, throttle the
      per-game refetch; forced-refresh bypass.
- [ ] Add the prime/size-0/non-game filter at merge (guarded so standalone-installer games survive).
- [ ] gamesdb 2:3 cover backfill (2-hop, unauth, lazy + idempotent) for new/uncovered ids.
- [ ] `GogGame.java` + `saveCachedGames`/`loadCachedGames`: add `verticalCover` (additive JSON key).
- [ ] Render `verticalCover` in poster/grid with SGDB/icon fallback; leave list-view unchanged.
- [ ] (Optional) `GogLibrarySync.cachedDetail` surfaces `verticalCover` for Save Manager / DL manager.
- [ ] Verify `cachedDetail`, `seed`, detail-page DLC/deps/save readers all still work (no key touched).
- [ ] Self-review brace/compile-sanity (no local build).

### P2 — Option B (Room substrate), only if approved
- [ ] Add KSP plugin + `androidx.room:{runtime,ktx,compiler}` to `libs.versions.toml` + `build.gradle`;
      configure `room.schemaLocation`.
- [ ] `store/db/`: `GogGameEntity`, `GogGameDao`, `GogDatabase` (version 1, `exportSchema=true`,
      `fallbackToDestructiveMigration` — DB is a cache).
- [ ] Seed Room from `gog_library_cache` on first run; write the pref mirror after every sync.
- [ ] Repoint the library screen at the DAO (IO dispatcher); keep the pref mirror for legacy readers.
- [ ] (P3) migrate `cachedDetail`/`seed` to Room queries; retire the pref mirror.

### Build / verify (repo rules — NEVER build locally)
- [ ] Commit as The412Banner; push `feat/gog-room-library`.
- [ ] CI on the branch; verify run headSha == pushed SHA; 3 flavors green.
- [ ] Stage the `pubg` artifact to `/sdcard/Download/` (cp only) for device test.
- [ ] Device test (needs a logged-in GOG account): first sync (full, covers backfill), then reopen
      (throttled, no re-fetch), then buy/add a game and refresh (only the new id fetched). Confirm the
      Save Manager GOG tab + a game detail page (DLC/deps/save folder) still render correctly.
- [ ] No versionCode bump (feature build, not a release cut).

## Notes
- Do NOT regress the gen1/standalone-installer path (`runInstaller`) — a game with no content-system
  build can still be installable; the filter must not hide it.
- `GogInstallState` stays the single owner of install truth; #8 reads none of `gog_exe_/dir_`,
  `gog_dlcs_`, `gog_deps_`, `gog_save_dir_`.
- Cover backfill is fail-soft: a gamesdb miss stores null and falls back to today's cover chain.
</content>
</invoke>
