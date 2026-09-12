# GOG Reliability Bundle — Plan & Task List

**Branch:** `feat/gog-reliability-bundle` (off `main` `dc1cc225`)
**Goal:** Close the highest-value GOG-storefront reliability gaps vs GameNative so big
downloads actually finish, corruption is caught, and sign-in is hardened. Clean-room from
the GOG protocol — GN's GOG code is GPL-3.0, behavior/anchors only, no source lifted.
**Source of gaps:** memory `reference_gog_gamenative_gap_roadmap` (audited 2026-08-19,
re-verified vs GN live `12433d92` 2026-08-21 — GN GOG frozen, list current).

Scope = 4 items: **#1 MD5 integrity, #6 secure-link refresh, #11 disk-space guard,
#12 OAuth state**. Bigger rocks (cloud-save auto-path #2, redist/ISI #3, DLC install #5,
Room sync #8) are explicitly OUT of this bundle.

All GOG code lives in `app/src/main/java/com/winlator/star/store/Gog*.{java,kt}`.

---

## In plain English (what each item does for the user)
- **#1 Corruption check** — every downloaded file is verified intact; a bad download is
  caught and re-fetched instead of silently breaking the game.
- **#6 Link refresh** — download links that expire mid-download on big games are auto-
  refreshed so the download keeps going instead of failing.
- **#11 Disk guard** — warn/stop before a download if there isn't enough free space,
  instead of filling the disk and failing mid-write.
- **#12 Login hardening** — anti-tampering check on the GOG sign-in redirect.

---

## Task list

### #1 — File integrity (3-layer MD5)  ⭐ highest value; unlocks Verify/Repair (#9) for free
The chunk MD5s are **already parsed** (`GogDownloadManager.java:856-860`, `ChunkRef(md5)` —
prefers `compressedMd5`, falls back to `md5`). We simply never verify against them.
- [x] `ChunkRef` carry BOTH `compressedMd5` and (decompressed) `md5` + size, not just one.
- [x] Verify **compressed** chunk bytes MD5 on download, before inflate (`runGen2` chunk loop ~`:328-434`).
- [x] Verify **decompressed** chunk size + MD5 after inflate, before write.
- [x] On mismatch: discard chunk, re-fetch (bounded retries), then fail the file with a clear error.
- [x] Full-file MD5/size check after assembly where the manifest provides it. (Size always via summed
      chunk `size`; whole-file `md5` only when the file item carries one — degrades gracefully if absent.)
- [x] Resume logic: replace the current `exists && length>0` skip with a size+MD5 re-check
      (`fileVerified()`) so resume can detect a corrupt partial.
- [x] Surface a "Verify / Repair" entry point that clears markers and re-runs the download
      (`verifyRepair()`; the size+MD5 skip-logic then re-pulls only bad/missing chunks = repair). (Gap #9.)

### #6 — Secure-link refresh + basic CDN resilience mid-download
Today: single `parseCdnUrl` base (`:875`), no refresh — an expired `secure_link` fails the
chunk after 3 retries (`secureLinkJson = httpGet(...)` `:314`).
- [x] Detect expiry: on chunk HTTP 401/403/404/500, re-request the secure link, rebuild the
      CDN base, and re-queue the chunk (don't count it as a hard failure).
- [x] Preserve the `__token__`/query when appending the chunk path (append-before-query).
- [x] Cap refreshes to avoid an infinite loop; log each refresh to the debug buffer.
      (Cap = 5 global via `cdnRefreshCount`; refresh is single-flight under a lock on `cdnBaseRef`.)
- [ ] (Stretch, optional) HEAD-probe rank of multiple CDN base URLs if the secure-link
      response offers more than one. Skip if single-base. — NOT DONE (single-base; left as stretch).

### #11 — Free-space guard  (GREENFIELD — GN has no GOG guard either; use StatFs directly)
- [x] Sum the planned download size from the manifest (chunk/file sizes) before starting.
- [x] Check usable bytes on the install target via `StatFs`/`File.getUsableSpace()`.
- [x] If short, stop before writing and report the shortfall (needed vs available) via `Callback`.
      (Hard-stop via `DISK_GUARD` sentinel so it does NOT fall through to Gen1 for the same install.)
- [ ] (Optional) periodic mid-download check for very large installs. — NOT DONE (pre-flight only).

### #12 — OAuth CSRF `state` validation  (cheap hardening)
Today: `AUTH_URL` (`GogLoginActivity.kt:38-41`) uses `response_type=token`, no `state`.
- [x] Generate a random `state` (24 bytes SecureRandom, URL-safe Base64) per login; append `&state=`.
- [x] On the implicit redirect (`handleImplicitRedirect`), parse `state` from the fragment and
      reject if it's missing or doesn't match. ⚠️ DEVICE-VERIFY: depends on auth.gog.com echoing
      `state` in the fragment — one-line relax to mismatch-only if valid logins get rejected.
- [x] Keep `state` across `WebView` recreation (`onSaveInstanceState`/`KEY_STATE`).

---

## Build / verify (per repo rules — NEVER build locally)
- [ ] Implement all 4, self-review brace/compile-sanity.
- [ ] Commit as The412Banner; push `feat/gog-reliability-bundle`.
- [ ] CI `build-artifacts.yml` on the branch; verify run headSha == pushed SHA; 3 flavors green.
- [ ] Stage `pubg` artifact to `/sdcard/Download/` (cp only) for device test.
- [ ] Device test: a real gen2 GOG title — confirm corruption-catch (tamper a chunk),
      link-refresh on a long download, disk-guard on a near-full target, login still works.

## Notes / risks
- No versionCode bump (feature build, not a release cut).
- MD5: use `java.security.MessageDigest("MD5")` streaming, not full-buffer, to avoid OOM on
  large chunks/files.
- Keep the **standalone-installer fallback** path (`runInstaller`, gen1) untouched — MD5/#6
  changes are gen2-only; don't regress the classic-installer flow we do better than GN.
