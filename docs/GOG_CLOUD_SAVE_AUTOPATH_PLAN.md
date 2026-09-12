# GOG Cloud-Save Auto-Path (gap #2, P1) — Plan

**Branch:** `feat/gog-cloud-save-autopath` (off main `86258436`)
**Goal:** Kill the manual "Browse" folder pick for GOG cloud saves — auto-resolve each
game's save directory inside its Wine container prefix, the same way Epic already does.
Makes the GOG Save Manager tab + detail-page Up/Down actually usable without the user
hand-pointing at a folder.
**Clean-room:** GN GOG code is GPL-3.0. Reimplement from the GOG cloud-storage protocol /
`goggame-*.info` semantics + our own `EpicCloudSavePaths.kt` pattern. Do NOT lift GN Kotlin.

## Scope — P1 ONLY (auto path-resolution + wiring)
IN:
- Resolve the local save directory automatically from the game's cloud-storage config.
- Wire the resolver into the existing manual Up/Down so a set folder is no longer required.
- Keep the manual "Browse" override as a fallback (mirrors Epic: manual pick wins).
OUT (defer to P2/P3, note in memory): bidirectional conflict resolution / newest-wins,
gzip+Etag, deletion tombstones, auto-triggers (post-install/pre-launch/post-exit). Transport
(`cloudstorage.gog.com/v1/{userId}/{clientId}`) already works — do NOT rewrite it.

## What we already have (reuse, don't rebuild)
- `GogCloudSaveManager.java` — transport up/down, `getOrFetchClientId`, `getGameScopedToken`,
  `listCloudFiles`/`putFile`/`getFile`. Works. It currently syncs a caller-supplied `localFolder`.
- `GogDownloadManager.getOrFetchClientId(ctx, gameId, token)` + `gog_client_id_<gameId>` cache.
- `GogInstallPath.getInstallDir(ctx, dir)` — the install dir under `imagefs/gog_games/…`.
- `gog_save_dir_<gameId>` — the current MANUAL folder pref (keep as override).
- **Reference pattern:** `EpicCloudSavePaths.kt` — `resolveContainer` (find the Wine container a
  game launches in) + `resolveSaveDirectory` (expand a token string into the prefix, case-insensitive
  on-disk walk, `..`/escape guard). GOG's resolver should mirror this structure.

## The resolver to build (new `GogCloudSavePaths.kt`, mirroring EpicCloudSavePaths)
1. **Get the save-location template for the game.** GOG's cloud-save location lives in the game's
   cloud-storage / remote-config metadata (keyed by clientId). Sources, in order of preference —
   the implementer must verify which is actually available on-device and pick the reliable one:
   - The installed `goggame-<clientId>.info` in the install dir (JSON) — inspect for a
     `cloudStorage`/`cloudSaves`/`savefiles`/`saveGame` location entry with a path template.
   - Failing that, `remote-config.gog.com` (or the content-system/products metadata) for the clientId.
   ⚠️ VERIFY the real field/endpoint on a real game (ELDERBORN is installed on-device, clientId in
   `gog_client_id_1732383191`) — do not invent a schema. If the `.info` carries the location, prefer
   it (no network). If NO cloud-save location metadata exists for a game, return null cleanly →
   UI shows "no cloud-save location for this title" (like Epic's no-support state), NOT a crash.
2. **Expand the template into the container prefix.** GOG templates use tokens like `<?INSTALL?>`
   (install dir), `%LOCALAPPDATA%`, `%APPDATA%`, `%USERPROFILE%`, `%DOCUMENTS%`, and `<?SAVE?>`.
   Map each into the resolved Wine container's `drive_c/users/<user>/…` (reuse the Epic token→prefix
   mapping + `SaveLocator`/`SteamCloudSavePaths` container resolution). Case-insensitive on-disk walk,
   `..`/prefix-escape guard — copy the hardened logic from `EpicCloudSavePaths.resolveSaveDirectory`.
3. **Container resolution for GOG.** GOG shortcuts are UNTAGGED (confirmed) and install under
   `imagefs/gog_games/<dir>` → the shortcut exec path contains `gog_games`. Resolve the container the
   game launches in by matching that install path (adapt `EpicCloudSavePaths.resolveContainer`, which
   itself adapts `SteamCloudSavePaths.resolveContainer`). If the game isn't attached to a container yet,
   return null → UI prompts to add it to a container first.

## Wiring (make Up/Down use the resolver)
- `GogGameDetailActivity` + `SteamSaveManagerActivity.GogSaveTab`: where they read
  `gog_save_dir_<gameId>` today, change to: **manual pref if set (Browse override), else auto-resolve
  via `GogCloudSavePaths`.** Mirror `GogCloudSaveManager.upload/downloadSaves(ctx, gameId, File(dir), cb)`
  call sites — just swap how `dir` is obtained. Persist the resolved path (or resolve on each sync).
- The GOG Save Manager tab row: when a folder auto-resolves, show `Auto: …/<tail>` and ENABLE Up/Down
  (today they're disabled until a manual folder is set). Mirror Epic's `EpicSaveRow`.

## Self-evidencing (device test needs this)
- Log the resolution to the existing GOG debug buffer (`bh_gog_debug.txt` via `debug(ctx, …)` in
  `GogCloudSaveManager`, or a new `GogCloudSavePaths` logger): the template found, its source
  (.info vs remote), the resolved container, the final expanded path, and null-reasons. The morning
  device test reads this to confirm the resolve on ELDERBORN.

## Build / verify (repo rules — NEVER build locally)
- Implement → self-review brace/compile-sanity → commit (The412Banner) → push → CI 3 flavors →
  verify headSha == pushed → stage pubg to `/sdcard/Download/` (cp only) → device test in the morning.
- Device test: ELDERBORN ▸ GOG detail page + GOG Save Manager tab → confirm a save folder AUTO-resolves
  (no Browse needed), Up pushes the resolved folder, Down restores it; read `bh_gog_debug.txt`.
- No versionCode bump.

## Notes / risks
- Keep the manual Browse override working (don't regress users who set one).
- If GOG exposes NO machine-readable save location for a title (some games genuinely lack cloud
  saves), degrade to the manual-pick UX for that game — don't block.
- Don't touch the gen1/standalone-installer or the download engine — this is cloud-save-path only.
