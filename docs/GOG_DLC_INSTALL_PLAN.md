# GOG DLC Install (gap #5) — Plan & Task List

**Branch:** `feat/gog-dlc-install` (off `main`)

**Goal:** Make owned GOG DLC actually **installable**, not just display-only. Today the GOG
detail page lists a game's owned DLC ("Owned") but installs nothing; the base install does not
reliably pull DLC either (see *What installs today*). This adds a **DLC selection UI** so the
user picks which owned DLC to install — **after** the base game (per-DLC "Install" buttons, the
robust core) with an optional **at-install picker** — and wires the download engine to pull just
that DLC's depots into the existing install dir.

**Source of gap:** memory `reference_gog_gamenative_gap_roadmap` item **#5** ("DLC actually
installed, ownership-gated, with a DLC selection UI"). GN implements this by resolving each
DLC's depots by `productId` from the base build manifest and downloading them with a
DLC-scoped secure link.

**Clean-room:** GN's GOG code is **GPL-3.0**. Everything below is reconstructed from the **live
GOG content-system v2 protocol** (probed read-only against real games, with the user's own token
where entitlement was needed — real shapes captured in this doc) and from **our own existing
gen2 download engine**. GN symbols are cited as **behavioural anchors by name only** (e.g. its
per-product depot filtering / DLC secure-link fetch) — no GN Kotlin is read or lifted. Do not
open GN source to implement this.

---

## In plain English (what this does for the user)
- A GOG game's detail page already shows the DLC you own. Today each row just says **"Owned"**
  and nothing installs. After this, each owned DLC gets an **Install** button (→ **Installed**),
  and it downloads that DLC's files straight into the game you already have — no re-downloading
  the base game.
- If you prefer, you can also tick which DLC to grab **at the same time** you install the base
  game (optional picker).

---

## Verified GOG protocol (real shapes captured 2026-08-22)

### 1. DLC lives in the SAME base build manifest, tagged by `productId`
There is **no separate DLC build/manifest**. The base game's gen2 build manifest (the one
`runGen2` already fetches + parses) lists **every** product in the tree under `products[]` and
tags **every** depot in `depots[]` with the `productId` it belongs to. DLC depots carry the
**DLC's own** productId.

Stellaris (base product `1508702879`), manifest top-level keys:
`[baseProductId, buildId, clientId, clientSecret, dependencies, depots, installDirectory,
offlineDepot, platform, products, scriptInterpreter, tags, version]` — note `baseProductId`.
```jsonc
"products": [
  { "productId": 1508702879, "name": "Stellaris" },                        // base
  { "productId": 1619776270, "name": "Stellaris: Anniversary Portraits" }, // DLC
  { "productId": 1122806862, "name": "Stellaris: Leviathans Story Pack" }, // DLC
  … 35 products total …
]
"depots": [
  { "productId": 1619776270, "languages": ["*"], "size": 104543,  "manifest": "062337ce…" },  // DLC depot
  { "productId": 1122806862, "languages": ["*"], "size": 59068563, "manifest": "fc37a0fa…" },  // DLC depot
  { "productId": 1508702879, "languages": ["*"], "size": 650680276,"manifest": "8012be43…" },  // base depot
  …
]
```
Cyberpunk 2077 (`1423049311`) and Divinity: Original Sin 2 (`1584823040`) match exactly: one
build manifest, `products[]` = base + DLC, each depot tagged by `productId`. **Consequence:**
"install DLC X" = collect the depots where `depot.productId == X`, resolve their per-depot file
manifests (same `…/content-system/v2/meta/<h>` path we already use), and download them.

### 2. Each product has its OWN CDN store namespace + secure link — this is the crux
`GET https://content-system.gog.com/products/<productId>/secure_link?_version=2&generation=2&path=/`
(**auth required**) returns a signed URL whose `path` is **per-product**:
```jsonc
// secure_link for ELDERBORN base (1732383191), HTTP 200:
"url_format": "{base_url}/token=nva={expires_at}~dirs={dirs}~token={token}{path}",
"parameters": { "base_url": "https://gog-cdn-fastly.gog.com",
                "path": "/content-system/v2/store/1732383191",   // ← per-product store path
                "token": "0a2af6…", "expires_at": 1787511612, "dirs": 4 }
```
Two hard facts proven on-device with the user's token:
- **Entitlement-gated (server-side ownership gate).** secure_link for an **unowned** product →
  **HTTP 403** `{"error":"invalid_licence","error_description":"User #… does not have the license
  for product #<id>."}` (tested: Stellaris base, Stellaris Utopia DLC, CP2077 Phantom Liberty
  DLC — all 403 for this account).
- **Token is strictly path-scoped to one product.** Fetched a real ELDERBORN chunk under its own
  store path → **HTTP 200**, 4 329 282 bytes. The **same** signed token with the store path
  swapped to another owned product (`1709371377`) → **HTTP 403**. So a base product's secure link
  **cannot** fetch DLC chunks.

**Therefore: to download a DLC's depots you MUST fetch a secure_link for the DLC's own
`productId`** (not the base's), and build chunk URLs under that DLC's store path
`/content-system/v2/store/<dlcProductId>`. Ownership is enforced twice: our owned-only
`gog_dlcs_<baseId>` list (UI), and the server 403 (authoritative fail-safe).

### 3. Chunk store + assembly are byte-identical to the base path we already ship
The per-DLC depot file manifest is the same shape as a base depot manifest; chunks resolve via
the same `buildCdnPath(hash)` fan-out and the same MD5-verified `assembleDepotFile` path. Nothing
new in the download engine — only *which product's secure link* signs the URLs.

### 4. DLC files interleave into the base install dir (own subfolders)
DLC depot item paths are relative to the game's `installDirectory`, each DLC under its own
subfolder, e.g. Stellaris:
```
dlc/dlc015_anniversary/dlc015.dlc
dlc/dlc015_anniversary/dlc015.zip
dlc/dlc012_leviathans/dlc012.dlc
```
So a DLC install writes into the **same** `gog_dir_<baseId>` install directory — no separate
target. The per-DLC subfolder pattern makes per-DLC **uninstall** feasible if we record each
DLC's written file list (see risks).

---

## What installs today (verified from `runGen2`)
`GogDownloadManager.runGen2` fetches **one** secure link for the **base** product
(`baseProductId`, `GogDownloadManager.java:340-347`) and then iterates **all** depots, filtering
only by **language** (`:293-333`) — it **never filters by `productId`**. Two consequences:

1. **DLC is not actually installed today in any controlled way.** The detail page's
   `GogDlcContent` even says *"DLC content is included in gen2 game installs"*
   (`GogGameDetailActivity.kt:1021`) — but that's only incidentally true, and only when it works.
2. **Latent base-install bug.** For a game whose base build manifest bundles DLC depots that pass
   the language filter (e.g. every Stellaris DLC depot is `languages: ["*"]` → `compatible=true`),
   `runGen2` tries to fetch those **DLC** chunks under the **base** store path. Because the base
   secure link is path-scoped to the base product (proven §2), those fetches return **403** →
   `assembleDepotFile` fails → `anyFailed=true` → **the whole install fails** with "one or more
   chunks failed to download". This bites whether or not the user owns the DLC (the manifest lists
   all products' depots regardless of ownership). Single-product titles (ELDERBORN, the two games
   installed on the test device) are unaffected, which is why it hasn't been caught.

**So the productId depot filter below is both the DLC-install mechanism AND a base-install
bug fix** — it must land together. It also means we do **not** need to "avoid double-downloading":
today's code that "accidentally pulls DLC depots via the language filter" doesn't succeed at
pulling them, it fails the install.

---

## Ownership gating — is `gog_dlcs_<baseId>` enough?
Yes for the UI, and the server backs it up:
- `GogGamesActivity` builds `gog_dlcs_<baseId>` from the **owned** library only — every product
  with `game_type=="dlc"` is mapped to its base via `required_game`/`requiredGames`
  (`GogGamesActivity.kt:351-352,427-447`), and only owned products are enumerated. So the list is
  already ownership-gated → correct source for the picker.
- The **authoritative** gate is the secure_link **403** (§2). Even if the library list were stale,
  a DLC the account doesn't actually own cannot be downloaded — it fails cleanly, not silently.
- No separate entitlement/licence call is needed. (Optional belt-and-suspenders:
  `GET https://embed.gog.com/user/data/games` returns `{"owned":[…ids…]}` — verified — could
  cross-check, but it's redundant with the 403.)

---

## Recommended UX (P1): after-install per-DLC "Install" buttons (+ optional at-install picker)

**Recommend: after-install per-DLC buttons as the robust P1 core; at-install picker as a thin,
optional add-on.** Justification:

- DLC lives in the **same** base build manifest and interleaves into the **same** install dir
  (§1, §4). So "install this DLC later" is simply *re-run the depot download filtered to that
  productId, with that DLC's secure link, into the existing dir* — a clean, isolated, restartable
  unit with its own progress and its own fail-soft boundary. One DLC failing never touches the
  base game or the other DLC.
- It's **idempotent for free**: `assembleDepotFile`'s per-file size+MD5 skip
  (`GogDownloadManager.java:405`) makes a re-run a cheap verify.
- It matches how the data is already staged: `gog_dlcs_<baseId>` is per-game owned DLC, and the
  detail page already renders a row per DLC (`GogDlcContent`, `:1004-1051`) — we're turning the
  static "Owned" tag into an **Install / Installing… / Installed** control.
- The **at-install picker** becomes trivial *once the per-DLC install method exists*: show a
  checkbox list of owned DLC before the base download; when the base completes, chain the selected
  DLCs through the same per-DLC method. It's additive, not a second engine. Recommend shipping it
  **only if cheap** in P1; otherwise defer to P2. (It cannot run truly concurrently with the base
  in one secure-link/progress bar — each product needs its own secure link — so "chain after base"
  is the clean model either way.)

Gate: the DLC Install button requires the **base game to be installed first** (`gog_dir_<baseId>`
+ `gog_exe_<baseId>` present). If not installed, the row shows "Install the game first" instead of
an enabled button.

---

## The mechanism to build

### New engine entry point (in `GogDownloadManager.java`)
`installDlc(ctx, GogGame baseGame, String dlcId, String dlcTitle, Callback cb)` — a sibling to
`startDownload`, running on its own background thread, returning a cancel `Runnable`:
1. **Reuse the base build-manifest fetch.** Same `builds?generation=2` → windows build →
   `meta_url` → `fetchBytes` → `decompressBytes` → `JSONObject manifest` that `runGen2` already
   does. (Refactor that "get build manifest" head into a small shared helper so base + DLC share
   it — don't duplicate the token/refresh/builds logic.)
2. **Filter depots to `depot.productId == dlcId`** (plus the existing language-compat check), and
   `parseDepotManifest` their per-depot manifests into `List<DepotFile>` — the same collect loop as
   `runGen2:293-333`, just with a productId predicate.
3. **Fetch the DLC's OWN secure link**: `secure_link` for **`dlcId`** (not base). 403 →
   surface a clean "You don't own this DLC (or the licence hasn't propagated yet)" error and stop.
   Build `cdnBase` under `/content-system/v2/store/<dlcId>` via the existing `parseCdnUrl`.
4. **Download + assemble into the base install dir** `GogInstallPath.getInstallDir(ctx,
   gog_dir_<baseId>)` using the existing parallel `assembleDepotFile` loop (MD5-verified, resume,
   disk guard). Reuse the CDN-refresh path against the **DLC** secure-link URL.
5. **Record idempotency + file list**: `gog_dlc_installed_<baseId>` (JSON set of installed dlcIds)
   and `gog_dlc_files_<baseId>_<dlcId>` (JSON array of written relative paths, for uninstall).
6. **Fail-soft**: a DLC failure never marks the base game broken; log to the GOG debug buffer.

### Base-install fix (in `runGen2`)
Restrict the base depot collection to `depot.productId == manifest.getString("baseProductId")`
(fall back to `game.gameId` if absent). This stops the base install from leaking into (and
failing on) DLC depots (see *What installs today*). Base install now installs exactly the base
product; DLC is added separately.

### UI (in `GogGameDetailActivity.kt`, `GogDlcContent` `:1004-1051`)
- Per owned-DLC row: **Install** button → **Installing… N%** → **Installed** (green). Drive it
  with the same `GogDownloadManager.Callback` shape the base install uses; a lightweight per-row
  progress state (or register into `StoreDownloadHooks` like the base download at `:401` so it
  shows in the cross-store Download Manager + shade notification — preferred for parity).
- Read installed state from `gog_dlc_installed_<baseId>` to render **Installed** on return.
- If base not installed → disabled row with "Install the game first".
- Replace the misleading *"DLC content is included in gen2 game installs."* copy.
- **Optional at-install picker** (only if cheap in P1): a checkbox list of owned DLC surfaced from
  `onInstallClicked` (`:379`) before `startInstall`; persist the selection and, in the base
  `onComplete` (`:428`), chain `installDlc` for each checked DLC.

---

## Files we'd touch (P1)
- `app/src/main/java/com/winlator/star/store/GogDownloadManager.java` — **base-depot productId
  filter in `runGen2`** (bug fix); refactor the build-manifest-fetch head into a shared helper;
  **new `installDlc(...)`** (productId-filtered depot collect → DLC secure link → assemble into base
  dir → markers/file-list). No new download engine — reuses `assembleDepotFile`, `parseDepotManifest`,
  `parseCdnUrl`, disk guard.
- `app/src/main/java/com/winlator/star/store/GogGameDetailActivity.kt` — `GogDlcContent` per-DLC
  Install/Installing/Installed control + progress + gating; optional at-install picker in the base
  install flow; drop the "included in gen2 installs" line.
- *(Optional)* `app/src/main/java/com/winlator/star/store/GogDlcInstaller.kt` — **new**, a thin
  orchestrator mirroring `GogRedistInstaller` (owned-list read, idempotency markers, progress
  plumbing) if the activity gets too heavy. Keep engine logic in `GogDownloadManager`.
- `app/src/main/java/com/winlator/star/store/GogGamesActivity.kt` — **only if** the at-install
  picker needs anything beyond the existing `gog_dlcs_<baseId>` it already writes; otherwise
  untouched.
- *(No secure-link rewrite, no new Wine/launch work — DLC is data in the same install dir.)*

---

## Keystone risks
1. **Partial DLC install.** A mid-download failure leaves some DLC files written but the DLC
   unmarked. Mitigation: write the `gog_dlc_installed` marker only on full success; the per-file
   size+MD5 skip makes a retry a cheap resume; the disk guard runs before writing.
2. **Verify/repair × DLC.** Base `verifyRepair` (`:102-111`) clears `_gog_manifest.json` and
   re-pulls **base** depot files — with the new productId filter it re-pulls only base files and
   leaves DLC files in place (good), but it also **won't re-verify** DLC files. Decide: either a
   parallel per-DLC verify (re-run `installDlc`, which is already a verify), or document that base
   verify is base-only. Ensure base verify does **not** delete DLC files.
3. **Idempotency / already-installed.** Gate the button on `gog_dlc_installed_<baseId>`; a re-tap
   should verify-and-skip via MD5, not blindly re-download. Show **Installed**, offer a "Verify"
   affordance rather than a second full pull.
4. **Uninstall-DLC scope.** Files interleave into the base dir. Full-game uninstall
   (`StoreUninstaller`/`confirmUninstall` `:504`) already removes everything incl. DLC — fine. **Per-DLC**
   uninstall needs the recorded `gog_dlc_files_<baseId>_<dlcId>` list to delete exactly that DLC's
   files (and prune its now-empty subfolder). Record the list at install; ship per-DLC uninstall in
   P1 tail or defer to P2 (full-uninstall-only until then). Must also drop the `gog_dlc_installed`
   entry on uninstall.
5. **secure_link 403 on a "supposedly owned" DLC.** Library ownership can lag a fresh purchase, or
   a bundle-granted DLC may not carry a standalone licence. Handle 403 as a clear per-DLC message,
   never as a base-game failure.
6. **Products with zero depots.** Some `products[]` entries are tools/merged content with **no**
   own depots (e.g. a "REDmod"/portrait entry whose data ships in the base). `installDlc` finding 0
   matching depots must resolve to "nothing to download — already included" and mark Installed, not
   error.
7. **DLC that itself declares dependencies/redists.** Rare, but a DLC product could list its own
   `dependencies`. Out of P1 scope (Stellaris/CP2077/DOS2 DLC don't) — note as open Q; the redist
   path (gap #3) is keyed on the base game today.
8. **Token expiry mid-DLC-download.** Reuse the base engine's CDN-refresh loop
   (`cdnRefreshCount`/`MAX_CDN_REFRESH`) but re-request the **DLC**'s secure link on refresh, not
   the base's.
9. **Progress/manager parity.** If DLC installs register into `StoreDownloadHooks`, use a
   composite id (e.g. `dlc:<baseId>:<dlcId>`) so a DLC download doesn't collide with the base game's
   card in the Download Manager.

---

## Open questions
- **At-install picker in P1 or P2?** Recommend after-install buttons for certain; fold the picker
  in only if it's a small delta on top of `installDlc`.
- **Per-DLC uninstall in P1 or P2?** File-list recording is cheap; the delete-and-prune UI is the
  cost. Lean: record in P1, expose uninstall in P1 tail if time, else P2.
- **Progress surface** for DLC — reuse `StoreDownloadHooks` (Download Manager + notification) vs a
  local per-row bar? Reuse preferred for parity; confirm the composite id doesn't confuse the
  registry.
- **Language selection for DLC** — mirror base (en-US / `*`)? Some DLC ship multi-language voice
  depots; the base language-compat check (`:296-305`) should apply unchanged.
- **DLC with own `dependencies`** — do we ever need to run redists for a DLC? (Assume no for P1.)
- **`clientId` for DLC cloud saves** — out of scope here (gap #2 handles saves), but note DLC
  installs shouldn't disturb `gog_client_id_<baseId>`.

---

## Task checklist (P1)
- [ ] `runGen2`: filter base depot collection to `depot.productId == baseProductId` (bug fix +
      prerequisite). Verify single-product titles (ELDERBORN) still install unchanged.
- [ ] Refactor the build-manifest-fetch head (`token → builds?generation=2 → windows build →
      meta_url → fetch → decompress → JSONObject`) into a shared helper for base + DLC.
- [ ] `GogDownloadManager.installDlc(ctx, baseGame, dlcId, dlcTitle, cb)`: productId-filtered depot
      collect → DLC secure_link (`/products/<dlcId>/secure_link`) → assemble into base install dir →
      markers.
- [ ] Idempotency: `gog_dlc_installed_<baseId>` (set) + `gog_dlc_files_<baseId>_<dlcId>` (paths).
- [ ] `GogDlcContent`: per-DLC Install / Installing…% / Installed control + gating on base install;
      drop the "included in gen2 installs" copy.
- [ ] (Optional) at-install picker: checkbox list before base download → chain `installDlc` on base
      `onComplete`.
- [ ] (Optional) per-DLC uninstall using the recorded file list; drop the installed marker.
- [ ] Fail-soft + debug logging to the GOG buffer (`bh_gog_debug.txt`): dlcId, depots matched,
      secure-link status (200/403), files assembled, markers written, idempotency skips.
- [ ] Self-review brace/compile-sanity (no local build).

## Build / verify (repo rules — NEVER build locally)
- [ ] Commit as The412Banner; push `feat/gog-dlc-install`.
- [ ] CI `build-artifacts.yml` on the branch; verify run headSha == pushed SHA; 3 flavors green.
- [ ] Stage the `pubg` artifact to `/sdcard/Download/` (cp only) for device test.
- [ ] Device test: needs an account that **owns a base game + at least one owned DLC** with
      a gen2 build (Stellaris, Cyberpunk 2077, or Divinity: Original Sin 2 are ideal — the test
      device's current account owns neither the DLC nor those bases, so a DLC-owning login or a
      granted DLC is required). Confirm: (a) base install now succeeds on a multi-product title
      (regression of the leak bug), (b) the owned DLC shows an Install button, (c) tapping it pulls
      only that DLC's files into the existing dir, (d) re-tap verifies-and-skips, (e) an unowned DLC
      (if surfaced) 403s cleanly. Read `bh_gog_debug.txt` to confirm depots-matched → secure-link
      200 → assemble → marker.
- [ ] No versionCode bump (feature build, not a release cut).

## Notes
- gen2-only. Do NOT touch the gen1/standalone-installer paths — classic installers bundle their
  own DLC and have no per-product secure link.
- Keep everything fail-soft: a DLC that won't download must never break the base game or block its
  launch.
- The base-depot productId filter is a **correctness fix** independent of the DLC feature — if the
  DLC UI slips, the filter should still ship.
</content>
</invoke>
