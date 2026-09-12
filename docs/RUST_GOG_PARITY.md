# Rust GOG download engine — parity recon (`use_rust_gog_engine` ON)

Acceptance rule (shared brief, 2026-09-07): with the flag ON the Rust engine behaves EXACTLY like
`GogDownloadManager.java`'s download loop — same install tags, languages, DLC, dependencies,
resume/skip, retry, CDN choice, error semantics, cancel semantics, progress cadence,
notifications. With the flag OFF the Java loop runs byte-identical (the engine switch only
*wraps* it — the diff to `GogDownloadManager.java` is insertions-only). Status legend:
**1:1** = identical rule; **≈** = same observable outcome, different mechanism (documented
below); **Java** = stays on the Java code path on both engines (shared head/tail, or not ported
on purpose); **n/a** = no equivalent needed.

Branch `feat/rust-dl-gog` (off `main` `426a071b`, rebased onto `feat/rust-dl-core` for the shared
fetch core). Crate `app/src/main/cpp/bl-steam-client/rust/` → `libblsteam.so`:
`src/store_dl/gog.rs` (+ `gog/plan.rs`, `gog/engine.rs`, `gog/jni.rs`). Kotlin facade
`blsteam/BlGogDownload.kt`. Java switch in `store/GogDownloadManager.java`
(`useRustEngine`, `runRustChunkEngine`). Line numbers below are for the edited file on this branch.

---

## 0. TL;DR

- Java has **four** byte-fetch loops, all four go through the one Rust engine. Three share one
  chunk semantic (`fetchChunkVerified` + `assembleDepotFile`) → `KIND_GEN2_CHUNKS`:
  **gen2 base install** (`runGen2`, N-thread pool, largest-first), **gen2 DLC install**
  (`doInstallDlc`, fixed 8 threads, manifest order), **dependency redist assembly**
  (`assembleDependencyInstaller`, sequential, unauthenticated store, no link refresh).
  The fourth — **gen1 range downloads** (`runGen1`, one Range GET per file streamed to disk)
  → `KIND_GEN1_RANGES` on the core's stream mode (§4b).
- The engine is fed exactly what Java already holds after manifest + secure-link resolution:
  the inflated depot-manifest JSON strings (Java keeps them in `depotJsons` next to its own
  parse), the resolved CDN base, the install dir, the pool size. Token/builds/manifest head,
  base-product + language selection, secure-link auth, disk guard, markers, prefs, exe
  resolution, DLC markers, redist orchestration, registry/notification feeds: **all Java, untouched**.
- Chunk requests carry **no bearer token** (the secure link's query string is the auth) and
  `User-Agent: GOG Galaxy`; Java holds **one** CDN base per run → the engine has one host and
  passes `per_host_cap = max_workers` so the core's window ceiling equals Java's pool size.
- Secure-link refresh (cap 5, on HTTP 401/403/404/500) stays in Java: a Rust run that dies on
  one of those codes returns `linkExpiry = true`, the manager calls the SAME `tryRefreshCdn`
  (same counter, same cap) and re-runs the engine for the files not yet done.
- On-disk layout is identical (`<file>.bhtmp` staging, atomic rename, size+MD5-verified
  finals), so a download can be resumed by either engine.
- Proof the Rust engine ran: `adb logcat -s BL_GOG_DL` shows `engine=rust label=gog base=…`
  and a `summary bytes= … elapsed= avg_mbps= peak_mbps=` line; `bh_gog_debug.txt` shows
  `engine=rust` and the same lines.

---

## 1. Entry points and threading (Java, shared)

| Entry | Java | What happens | Rust status |
|---|---|---|---|
| `startDownload(ctx, game, cb)` | `:84` | spawns `gog-dl-<id>` thread → `doDownload`; returns cancel `Runnable` = `cancelled=true` + **delete the whole install dir** + `cb.onCancelled()` (`:93`-`:94`) | Java (shared) |
| `verifyRepair` | `:104` | deletes `_gog_manifest.json`, then `startDownload` (repair = the resume/skip rule re-pulls only failing files) | Java (shared) |
| `installDlc(ctx, base, dlcId, title, cb)` | `:130` | spawns `gog-dlc-<base>-<dlc>` → `doInstallDlc`; cancel `Runnable` = flag + `onCancelled()` (does NOT delete — DLC files interleave into the base dir) (`:137`) | Java (shared) |
| `assembleDependencyInstaller(json, storeBase, destDir, exeRelPath, cancelled, log)` | `:1368` | called from `GogDependencyRepository.downloadRedist` ← `GogRedistInstaller.installPrereqs` (bg thread `gog-redist-install`, add-to-container time) | Java (shared) + engine switch |
| `doDownload` | `:152` | token check/refresh (`bh_gog_prefs` `access_token`, `bh_gog_login_time`+`bh_gog_expires_in`), gen2 builds (public, then authed) → `runGen2`; `DISK_GUARD:` hard-stop; else gen1 → `runGen1`; `NO_CS_BUILDS` → `runInstaller`; every path ends in `writeDebug` | Java (shared) |
| Debug log file | `:241`-`:245` | `<externalFilesDir>/bh_gog_debug.txt` (falls back to `filesDir`), written once at the end from the `dbg` buffer; per-file `fileLog2` lines are appended to it | 1:1 — engine `onLog` lines go into the same `fileLog2` queue → same file; plus logcat tag `BL_GOG_DL` |
| Callers | `GogGamesActivity.startDownload` (`:681`), `GogGameDetailActivity.startInstall` (`:433`), `installDlc` (`:634`) | `StoreDownloadHooks.registerDownload` BEFORE calling the manager; `Callback.onProgress(msg, pct)` → `StoreDownloadHooks.tick(GOG, id, pct)` (pct only — single honest bar) + UI label; `onComplete` reads `gog_exe_`/`gog_dir_` → `markInstalled` (or `markFailed("No executable found")`); `onError` → `markFailed`; `onCancelled` → `markCancelled`; `onSelectExe` auto-picks first | Java (untouched) |

## 2. Gen2 head: build manifest → depot selection → secure link (Java, shared)

| Rule | Java line(s) | Rust status |
|---|---|---|
| `builds?generation=2` → first `os == "windows"` item → `link` (else `meta_url`) → fetch (bearer) → `decompressBytes` (gzip / zlib / raw) → JSON | `resolveGen2Manifest` `:286` | Java |
| `installDirectory` (default `game.title`), `clientId`/`clientSecret`, `depots[]`, `products[0].temp_executable`, `dependencies[]` + `scriptInterpreter` persisted to `gog_deps_<id>` (`persistDependencies` `:304`) | `:290`-`:313` | Java |
| `baseProductId` = manifest `baseProductId` → `products[0].productId` → `game.gameId` | `:325` | Java |
| **Depot selection (base install):** keep depot iff (`productId` empty OR `== baseProductId`) AND `languageCompatible` (no `languages` / `*` / `en-US` / `"en"` / `english`) | `:346`, `:353`, `languageCompatible` `:638` | Java — the engine only sees the manifests of the depots Java kept |
| **Depot selection (DLC install):** keep depot iff `productId == dlcProductId` AND `languageCompatible` | `doInstallDlc` `:696`-`:725` | Java |
| Depot manifest: `https://gog-cdn-fastly.gog.com/content-system/v2/meta/<h0:2>/<h2:4>/<h>` (no auth) → `decompressBytes` → `parseDepotManifest`; a failed meta fetch/decompress **skips that depot silently** | `:361`-`:380` | Java (the inflated string is also kept in `depotJsons` `:380` for the engine) |
| Empty file list → `"no depot files collected…"` | `:387` | Java |
| **Secure link:** `GET content-system.gog.com/products/<productId>/secure_link?_version=2&generation=2&path=/` with bearer; `parseCdnUrl` = `urls[0].url_format` with `{param}` substitution, `\/` unescaped, `/{path}` suffix stripped → ONE base URL (query string = the auth token) | `:393`-`:399`, `parseCdnUrl` `:1427` | Java; the engine receives the base verbatim |
| Base = `baseProductId` (base install) / `dlcProductId` (DLC — 403 ⇒ "You may not own this DLC…") / `…/v2/dependencies/store` (redists, no secure link at all) | `:393` / `:742` / `GogDependencyRepository.STORE_BASE` | Java |
| Install dir `filesDir/imagefs/gog_games/<installDirectory>`; `.gog_chunks` created then deleted (unused by either engine) | `:407`-`:409`, `:533` | Java |
| Disk guard: Σ `df.totalSize` vs `installPath.getUsableSpace()` → `cb.onError("Not enough free space…")` + `DISK_GUARD:` | `:418` | Java |
| Largest-first ordering (stable sort by `totalSize` desc) — base install only | `:432` | 1:1 — `build_plan(sort_largest_first = true)`; DLC/dependency pass `false` (Java does not sort there) |
| Pool size = `resolveDownloadThreads`: `bh_gog_prefs` `gog_dl_threads` ≥ 1 wins, else `clamp(cores*2, 6, 16)`; DLC = fixed 8; dependency = sequential (1) | `:258`, `:435`, `:797`, `:1368` | 1:1 — the same computed N is passed as `max_workers` AND `process_workers`; core `per_host_cap = N` so the window ceiling is N (see §4) |

## 3. Depot manifest → plan (`parseDepotManifest` ↔ `plan.rs`)

| Rule | Java line(s) | Rust status |
|---|---|---|
| root `depot.items[]`; missing → nothing added | `:1229` | 1:1 (`parse_depot_manifest`) |
| `path`: `\` → `/`, one leading `/` stripped; skip if empty or `chunks` absent/empty | `:1229`+7 | 1:1 |
| file `md5` kept when non-empty (else no whole-file check) | `:1229`+13 | 1:1 |
| chunk hash = `compressedMd5` if non-empty else `md5`; chunk with neither dropped | `:1229`+25 | 1:1 |
| `compressedSize` / `size` via `optLong(…, 0)` (numeric strings coerce) | `:1229`+20 | 1:1 (`opt_u64`) |
| `totalSize` = Σ chunk `size`; file added only if ≥1 chunk survived | `:1229`+32 | 1:1; plus per-chunk `offset` = prefix-sum (Rust writes positioned; Java appends) |
| A non-object entry throws → Java's catch-all stops parsing that manifest (keeps what was added) | `:1229` catch | ≈ Rust skips the bad entry and continues (strictly more lenient; GOG manifests never carry one) |
| CDN path `h[0:2]/h[2:4]/h`; chunk URL = base with path inserted BEFORE `?` | `buildCdnPath` `:1270`, `buildChunkUrl` `:1601` | 1:1 (`build_cdn_path`, `build_chunk_url`) |
| Unit tests | — | `plan.rs` tests on a synthetic manifest (coercion, path rules, hash fallback, drops, stable LPT order, URL builders) |

Both parsers run on the same strings; Java's `files.size()` (progress `total`) and the engine's
`files=` log line must agree — compare them in `bh_gog_debug.txt` on the first device run.

## 4. The fetch loop (what the engine replaces)

| Rule | Java line(s) | Rust status |
|---|---|---|
| Unit of parallelism: one pool task per file; chunks **sequential** inside a file; N concurrent HTTP requests total | `:465`-`:521` | ≈ unit = chunk; the core's adaptive window has ceiling `max_workers = N` (bootstraps at 8, grows/shrinks with throughput/errors), one host, `per_host_cap = N` so the clamp never lowers the ceiling below Java's N. Same order (largest file first, chunks in manifest order). |
| Task start: `if (cancelled || anyFailed) return` | `:469` | 1:1 — cancel stops scheduling immediately; a fatal file aborts the run |
| `parent.mkdirs()` | `:471` | 1:1 (`create_dir_all` on first chunk of the file) |
| **Resume/skip:** `fileVerified(out, totalSize, md5)` = exists ∧ non-empty ∧ (size unknown ∨ equal) ∧ (md5 unknown ∨ equal, case-insensitive); verified → `doneCount++`, `cb.onProgress("Verified…", pct)`, **no bytes credited** | `:475`, `fileVerified` `:1641` | 1:1 (`file_verified`) — same predicate, same event, no bytes; runs up front on `process_workers` threads instead of interleaved on the pool (§8) |
| Chunk fetch: `GET <chunkUrl>`, **no Authorization header**, `User-Agent: GOG Galaxy`, 30 s connect + 30 s read | `fetchBytesEx` `:1574`-`:1580`, `:1703` | ≈ `headers = [User-Agent: GOG Galaxy]`, no bearer, core request timeout 30 s (whole request, not per-read) |
| HTTP 401/403/404/500 → `tryRefreshCdn` (synchronized; only if the base is still the stale one; `cdnRefreshCount < 5`; re-GET secure link with bearer; `parseCdnUrl`) → retry same chunk, **not** a hard failure | `:1706`, `tryRefreshCdn` `:1662` | ≈ the core retries the item (≤5 attempts, backoff) then fails the run; the engine reports `linkExpiry = true` when the failing status is one of the four codes; `runRustChunkEngine` calls the SAME `tryRefreshCdn` (same counter, cap 5, same base-still-stale check) and re-runs for the remaining files (already-done paths passed as `skipPaths` → no re-hash, no re-report). Net: same refresh endpoint, same cap, same "not a hard failure" outcome; costs the core's retry budget (~15 s) before the refresh. Dependency path: cap 0 → fails like Java. |
| Other HTTP failure / connect error → `hardFail++`, ≤3 with `sleepBackoff` (1 s, 2 s, 4 s cap 8 s); `iterGuard` 8 | `:1713`, `:1651`, `:1699` | ≈ core policy: ≤5 attempts with its backoff (shared with Steam); both bounded, both end in the file → run failing |
| Verify compressed: `compressedSize` (when > 0) then `compressedMd5` (when set) | `:1717`-`:1731` | 1:1 (`GogSink::process`) — mismatch = retryable (`SinkError::Retry`) |
| Inflate: zlib iff first byte `0x78`; on non-zlib OR inflate error use the raw bytes ("stored" chunk) | `inflateZlib` `:1543`-`:1547`, `:1734` | 1:1 (`inflate_zlib` → `None` → raw) |
| Verify decompressed: `size` (when > 0) then `md5` (when set) | `:1737`-`:1749` | 1:1 — mismatch = retryable |
| Write: `.bhtmp` sibling, stale tmp deleted first, `FileOutputStream` append in manifest order | `assembleDepotFile` `:1319`-`:1330` | ≈ same `.bhtmp` path, stale tmp deleted, `create|truncate`; chunks `write_all_at(offset)` (positioned, may land out of order) — identical bytes once every chunk landed |
| Whole-file verify: `tmp.length() == totalSize` (when > 0), then streaming MD5 vs `df.md5` (when set); mismatch → log `FILE size mismatch file=… exp=… got=…` / `FILE md5 mismatch file=…`, delete tmp, file fails | `:1339`-`:1350` | 1:1 (`finalize_file`, same log strings) |
| Commit: delete existing final, `renameTo` | `:1354` | 1:1 (`remove_file` + `rename`) |
| File failed → `fileLog.add("FAIL file=…")`, `anyFailed = true`; other tasks stop at their next task boundary (a task mid-file finishes and renames its file) | `:484`-`:491` | ≈ fatal → the core aborts the run; every unfinished `.bhtmp` is deleted (`cleanup_partials`). Java may leave a few MORE completed files (in-flight tasks finish); both states resume identically. Java's tail then returns `"one or more chunks failed to download"` (`:524`) on both engines. |
| Success accounting: `doneCount++`, `totalBytes += df.totalSize` (decompressed), 500 ms speed window → `formatSpeed`, `cb.onProgress("Downloading: <basename>  <speed>", 15 + done/total*80)` | `:494`-`:509` | 1:1 — identical code in `runRustChunkEngine`'s listener (`:1832`-`:1851`), fed per file by the engine's `onProgress(…, file, fileBytes, verified=false)`; DLC variant without the speed suffix (`:826` ↔ `showSpeed=false`) |
| DLC `written` list gets verified AND downloaded paths | `:806`-`:826` | 1:1 (listener adds both) |
| Pool join: `f.get()` on every future; exception → `"parallel download error: …"` | `:521` | n/a (blocking call; a JNI start failure → `anyFailed`) |
| Tail: `cancelled` → `"cancelled"`; `anyFailed` → `"one or more chunks failed to download"`; else marker + prefs + exe | `:523`-`:571` | Java (shared) |

## 4b. Gen1 loop (`runGen1` ↔ `run_gen1` / `Gen1Sink`, core stream mode)

| Rule | Java line(s) | Rust status |
|---|---|---|
| Manifest: `depot[]` minus `support: true`; `files[]` → `path`, `url`, `offset` (0), `size` (0); dropped iff `size == 0` (the `null` checks never fire — `optString` returns "") | `:905`-`:922` | 1:1 (`parse_gen1_manifest`, tests) |
| Pool = `resolveDownloadThreads`; one task per file | `:947`-`:951` | ≈ same N as ceiling + `per_host_cap`; one host key (first file's URL) |
| Resume: `exists && length == size` → `doneG1++`, `"Resuming…"` at `15 + done/total*80`, no bytes | `:960`-`:966` | 1:1 (size-only, same string via `verifiedMsg`) |
| Attempt: `outFile.delete()`, Range `bytes=<offset>-<offset+size-1>`, no auth header, 30 s/30 s, body streamed to `FileOutputStream`; no size/hash check | `:968`-`:973`, `downloadRange` `:1449` | 1:1 semantics — `FetchItem.range`, stream mode (`on_chunk` at `offset == 0` deletes + recreates the file, pieces written at their offset, `on_finish` = success, no check); UA `GOG Galaxy` where Java sent the platform default |
| Retry: 3 attempts, 1 s / 2 s sleep; failure → `anyFailedG1` → `"one or more gen1 files failed to download"` | `:968`, `:994`-`:1002` | ≈ core ≤5 attempts + its back-off; failure → `anyFailed` → the same Java tail |
| Success: `doneG1++`, `totalBytesG1 += size`, 500 ms speed window, `"Downloading: <name>  <speed>"` | `:974`-`:990` | 1:1 (same listener code, `showSpeed = true`) |
| Partial file after a failed last attempt / cancel stays on disk (Java only deletes at the START of an attempt) | `:970` | 1:1 (no cleanup; a wrong-size partial fails the size-match resume; base cancel deletes the dir) |
| Tail: `cancelled` → `"cancelled"`; `anyFailedG1` → error; prefs `gog_dir_`; exe candidates | `:1012`-`:1029` | Java (shared) |

## 5. Cancel / pause semantics and on-disk state

| Rule | Java line(s) | Rust status |
|---|---|---|
| Pause: not supported (`supportsPause = false` in both callers) | callers | n/a |
| Cancel flag polled at task start and before every chunk; the task deletes its `.bhtmp` and returns | `:469`, `:1327`, `:1336` | ≈ `runRustChunkEngine` polls the same `AtomicBoolean` every 250 ms → `nativeCancel` → core stops scheduling/aborts in-flight; engine deletes every unfinished `.bhtmp` |
| Base install cancel `Runnable`: sets flag, **deletes the whole install dir**, `onCancelled()` (registry `markCancelled`) | `:93`-`:94` | Java (shared) — on-disk state after a base cancel = directory gone, on both engines |
| DLC cancel: flag only; completed DLC files stay, tmp deleted; no marker written | `:137`, `:847` | 1:1 |
| Dependency: `cancelled` polled; `assembleDependencyInstaller` returns null → redist skipped (fail-soft) | `:1368` | 1:1 (`anyFailed`/`cancelled` → null) |
| State left for resume on either engine: verified finals + no `.bhtmp` → next run re-verifies (size+MD5) and re-pulls only what fails | — | 1:1 (same layout) |

## 6. Progress cadence, registry, notification, error surfaces (Java, untouched)

| Rule | Java line(s) | Rust status |
|---|---|---|
| Pre-loop milestones: "Checking token…" 0, "Fetching builds…" 2, "Fetching manifest…" 5, "Reading depot manifests…" 10, "Fetching CDN link…" 15 | `doDownload`, `runGen2` | Java |
| Per-file events only (no per-chunk progress): pct = `15 + done/total*80` | `:477`, `:497` | 1:1 (§4) — cadence is per completed file on both engines |
| "Install complete!" 100 → prefs (`gog_dir_`, `gog_build_`, `gog_client_id_`, `gog_client_secret_`) → exe (`temp_executable` → single candidate → `onSelectExe`) → `cb.onComplete(exePath)` | `:527`-`:571` | Java |
| `Callback.onProgress` → `StoreDownloadHooks.tick(GOG, id, pct)` → `DownloadRegistry.update` + `DownloadForegroundService.setProgress` shade line | callers | Java |
| `onError(msg)` → `markFailed`; `onCancelled` → `markCancelled` (row removed); `onComplete` → `markInstalled(installPath, bytes = gog_size_)` or `markFailed("No executable found")` | callers | Java |
| Error strings: `"Download failed: <gen1 err>"`, `"Download error: <exception>"`, `"No builds available…"`, `"Not enough free space…"`, DLC `"DLC install failed"` / `"You may not own this DLC…"` | `doDownload`, `doInstallDlc` | Java — the engine never surfaces its own error text to the user; engine detail goes to `bh_gog_debug.txt` (`rust engine FAILED: …`) and logcat |
| `dbg` `engine=rust|java` line (base), `GogCloudSaveManager.debug(… engine=…)` (DLC), `log.add("engine=rust (dependency)")` | `:457`, `:788`, `:1387` | new, additive |

## 7. Post-install steps that MUST stay untouched (all Java, verified untouched — diff is insertions-only)

| Step | Java line(s) |
|---|---|
| `_gog_manifest.json` marker `{"gameId","installDir"}`; `.gog_chunks` deleted | `:527`-`:533` |
| `bh_gog_prefs`: `gog_dir_`, `gog_build_`, `gog_client_id_`, `gog_client_secret_` (cloud-save URLs read `gog_client_id_` via `getOrFetchClientId`), `gog_exe_` | `:539`-`:571` |
| `gog_deps_<id>` (`{"deps":[…],"scriptInterpreter":bool}`) → `GogRedistInstaller.promptAndInstall` at add-to-container | `persistDependencies` `:304` |
| DLC: `gog_dlc_installed_<base>_<dlc>` + `gog_dlc_files_<base>_<dlc>` written only on full success; "no own depots" DLC marked installed without download | `markDlcInstalled` `:850`, `:735` |
| Cloud-save auto-path wiring (`GogCloudSavePaths`, `gog_save_dir_`) — not touched by the download at all | — |
| Shortcut creation (`GogLaunchHelper.addToLauncher`) and install-state (`GogInstallState`, prefs-only) — caller side | callers |
| Registry durable library row (`markInstalled` persists INSTALLED) | `StoreDownloadHooks` |

## 8. Known ≈ items (same outcome, different mechanism) — the morning A/B list

1. **Verify-then-fetch ordering.** Java interleaves "Verified…" events with downloads (tasks
   check the file as they get scheduled); the engine runs the whole resume/skip pass first
   (parallel, `process_workers` threads), then fetches. Same events, same pct math, same
   final `doneCount`; only the timing of the "Verified…" lines differs.
2. **Retry budget.** Java: ≤3 hard failures per chunk (1/2/4 s), link-expiry codes exempt.
   Core: ≤5 attempts per item with the shared backoff; link expiry consumes those attempts
   before the Java-side refresh + re-run kicks in (§4).
3. **Concurrency shape.** Java: N files in flight, chunks sequential per file. Core: adaptive
   window of chunks (ceiling N) across files, byte budget. Order of chunk arrival differs;
   positioned writes make the file bytes identical.
4. **Failure fan-out.** Java lets in-flight tasks finish their current file after `anyFailed`;
   the core aborts immediately. Extra completed files on Java = fewer to re-pull next time;
   never a correctness difference.
5. **Timeout semantics.** Java 30 s connect + 30 s between reads; core 30 s per request.
6. **Manifest parse leniency** on a malformed entry (§3, never observed in GOG data).

## 9. Not ported (stays Java on both engines)

- **Installer fallback (`runInstaller`, `:1104`).** Single `.exe` stream with byte-level progress
  ("Downloading: <exe>  <speed>" per read); no chunk semantics, no resume, no retry. Left as is.
- Everything in §§1-2 and §§6-7.

## 10. Verification (device)

1. Flag ON (`use_rust_gog_engine`, default true): install ELDERBORN (small) then a DLC (XCOM 2 base
   filter title), then add-to-container with a redist. Expect in `logcat -s BL_GOG_DL`:
   `engine=rust label=gog base=<id> files=<n> chunks=<c> planned_bytes=<b> … workers=<N>`,
   `plan verified=… pending_files=… pending_chunks=…`, the core's `fetch-window` lines,
   `summary bytes=… elapsed=… avg_mbps=… peak_mbps=… success=true`.
2. `bh_gog_debug.txt`: `engine=rust`, `gen2 parallel download: <n> files, <N> threads` — the
   `<n>` must equal the engine's `files=`; `gen2 download complete: <n> files OK`.
3. Cancel mid-download: `summary … cancelled=true`, install dir gone (base) / no `.bhtmp` left (DLC).
4. Re-run (Verify/Repair): every file reports `Verified…`, `pending_chunks=0`, `nothing to fetch`.
5. Flag OFF: `engine=java` in `bh_gog_debug.txt`, no `BL_GOG_DL` lines — the loop is the old one.
6. Gen1 title (a build with `generation=1` only): `engine=rust kind=gen1 label=gog gen1=<id>`,
   `fetch-start … mode=stream`, `plan resumed=… pending_files=…`, per-file "Downloading:" lines.

## 11. Improvements round 1 (2026-09-07, user go: "raise the ceilings and fix the gog overhead")

Device evidence (Gunslugs 3, 552 files / 555 chunks, 72 MB compressed): Rust 8.1-10.2 s vs Java
~4.5 s with `fetch-window … window=16 in_flight=1 … budget_stalls=24`. Root cause: whole-body mode's
24 MiB in-flight byte budget plus largest-first ordering — a few multi-MB chunks filled the budget,
so 1-3 requests were actually in flight while the window said 16. Java streams 16 files straight
to disk with no byte budget.

What changed (Rust path only — the Java fallback keeps `resolveDownloadThreads` / 8 / 1):

| Item | Before | Now |
|---|---|---|
| gen2 chunk items (base, DLC, dependency) | body mode: whole chunk buffered, then verify → inflate → verify → positioned write | **stream mode** (`FetchOptions.stream = true`): pieces are inflated as they arrive straight into the chunk's byte range of the `.bhtmp` (`flate2::write::ZlibDecoder` over a positioned `RangeWriter`); compressed MD5 accumulates per piece; decompressed size + MD5 accumulate as output is written; `on_finish` checks in Java's order (compressed size → compressed MD5 → inflate end → size → MD5), mismatch = `SinkError::Retry` exactly where Java retried; `offset == 0` = attempt restart (fresh inflater/hashers, the range is simply re-written); first byte `!= 0x78` = stored chunk pass-through (Java's `inflated = raw`); a corrupt zlib body mid-stream = Retry (Java: null → raw → size mismatch → retry). No in-flight byte budget in stream mode. |
| ceiling (`max_workers`) | Java's pool size (`gog_dl_threads` / clamp(cores×2, 6, 16); DLC 8; dependency 1) | `DownloadSpeedConfig(DEFAULT_TIER).maxNetworkWindow` clamped 1..128 = **32** (Fast tier) for every gen2/gen1 run |
| `per_host_cap` | = Java's pool size | `max(6, ceil(max_workers / distinct_hosts))` computed in the adapter = **32** on GOG's single CDN host |
| `process_workers` | = Java's pool size | `max(Java pool size, 16, DownloadSpeedConfig.maxDecompress clamped 1..32)` ≥ **16**, because stream mode pins each item to one pool worker (`idx % process_workers`) |
| ordering | largest-first (base) | unchanged — big chunks start streaming first, tiny files fill the tail |
| everything else (skip/resume, `.bhtmp` + whole-file MD5 + rename, cancel, progress strings, secure-link refresh, post-install) | — | unchanged |

Log grammar to grep on device (`adb logcat -s BL_GOG_DL`, also in `bh_gog_debug.txt`):
- `rust engine concurrency: workers=32 process_workers=<P> (java loop would use <N>)`
- `engine=rust kind=gen2 mode=stream label=gog base=<id> files=… chunks=… planned_bytes=… workers=32 per_host_cap=32 process_workers=<P> …`
- core: `fetch-start … ceiling=32 … per_host_cap=32 … mode=stream`, `fetch-window … budget_stalls=0` (stream mode reserves nothing at dispatch)
- `summary bytes=… elapsed=… avg_mbps=… peak_mbps=… items_ok=… success=true`

Target: Gunslugs 3 ≤ Java's ~4.5 s and `budget_stalls=0`. If the per-worker channel pinning shows
up as the limiter (a worker's queue backing up while others idle), that is a core change
(work-stealing hand-off), not an adapter one.
