# Rust Amazon download engine — parity recon (`use_rust_amazon_engine`)

Scope rule: the Rust engine replaces ONLY the byte-fetching inner loop of
`AmazonDownloadManager.install()` (Step 4, the `MAX_PARALLEL` pool). Everything before it
(GetGameDownload, manifest fetch + parse, marker files, the first progress tick) and after it
(manifest cache, versionId pref, complete marker, exe discovery, registry/library updates) is
shared code and runs unchanged on both engines. The Java loop is byte-identical in the
`else` branch. Status legend: **1:1** = same observable behaviour; **DEVIATES** = documented
difference (listed in §9, all of them tied to the whole-body fetch core); **shared** = not
touched by the engine at all.

Branch `feat/rust-dl-amazon` (rebased onto `feat/rust-dl-core`); crate
`app/src/main/cpp/bl-steam-client/rust/` → `libblsteam.so`; adapter
`src/store_dl/amazon.rs` + `src/store_dl/amazon/jni.rs`; Kotlin facade
`store/blsteam/BlAmazonDownload.kt`; switch in `store/AmazonDownloadManager.java`.
Logcat proof: `adb logcat -s BL_AMAZON_DL` — first line `engine=rust`.

## 1. Entry points (shared, untouched)

| Caller | File:line | What it does around `install()` |
|---|---|---|
| Detail page install | `AmazonGameDetailActivity.kt:270-395` | `DownloadScope.io` coroutine; `installDir = filesDir/Amazon/<sanitized title>`; writes `amazon_dir_<pid>`; `StoreDownloadHooks.registerDownload(AMAZON, pid, …, supportsPause=false, installTotal=amazon_size_<pid>, cancel={cancelled.set(true)})`; progress → `StoreDownloadHooks.tick` + page label `"$pct%  (done / total)"` (file label ignored); after: `cancelled` → `onInstallCancelled()` (`markCancelled`), `!ok` → `onInstallError("Download failed")`, else exe scan (`AmazonLaunchHelper.collectExe/scoreExe`) → `amazon_exe_<pid>` → `onInstallComplete()` → `StoreDownloadHooks.markInstalled` |
| Store list install | `AmazonGamesActivity.kt:414-517` | same shape on `lifecycleScope`; `installDir = filesDir/imagefs/Amazon/<sanitized>`; progress label = the `currentFile` string from the manager (or "Downloading…"); `!ok` → `markFailed(…, "Download failed")`, cancel → `markCancelled` |
| DLC install | `AmazonGameDetailActivity.kt:600-668` | no-op progress, never cancels, no registry row |
| Size probe | `AmazonGameDetailActivity.kt:681` → `fetchInstallSizeBytes` | manifest-only, no file fetch — not an engine path |
| Library seed | `AmazonLibrarySync.kt:145` → `isInstalled(dir)` | reads the `.amazon_download_complete` marker |

The engine never sees a `Context`, the registry, the FGS notification, or prefs; those are
driven by the callers above through the SAME `ProgressCallback`/`CancelChecker` objects on
both engines.

## 2. Manifest source and format (shared, untouched)

- `AmazonApiClient.getGameDownload(accessToken, entitlementId)` (`AmazonApiClient.java:182`)
  = POST `https://gaming.amazon.com/api/distribution/v2/public`, `X-Amz-Target …GetGameDownload`,
  `x-amzn-token`, `Content-Encoding: amz-1.0` → `{downloadUrl, versionId}`.
- `downloadUrl` is a **signed CDN base URL with its auth in the query string**
  (`https://<cdn-host>/<path>?<signature…>`). `AmazonApiClient.appendPath(base, seg)`
  (`:257`) inserts the segment BEFORE the `?`, so every file URL keeps the signature. There is
  no expiry handling anywhere in the Java manager: if the signature expires mid-download the
  file GET returns non-2xx → `IOException("HTTP nnn")` → 3 retries → install fails. Rust
  inherits that exactly (the URL list is built once, in Java, before the engine starts).
- Manifest: `GET appendPath(downloadUrl, "manifest.proto")` via `AmazonApiClient.getBytes`
  (`:306`, header `x-amzn-token` + UA `nile/0.1 Amazon`), parsed by `AmazonManifest.parse`
  (`AmazonManifest.java:103`): 4-byte BE header size, `ManifestHeader` protobuf, LZMA/XZ body,
  `Manifest{ repeated Package{ name, repeated File{ path(\\), size, Hash{algorithm, value} } } }`.
  `ParsedManifest.allFiles` = every file of every package in manifest order;
  `totalInstallSize` = Σ size. **No install tags, languages, DLC filters or dependencies exist
  in this flow** — a DLC is a separate entitlement with its own manifest (§1 DLC row).
- Per-file wire object: `GET appendPath(downloadUrl, "files/" + sha256hex)` — the object is
  keyed by the file's SHA-256, delivered **whole, uncompressed, unencrypted**; no chunking,
  no Range requests, no auth header (signature is in the query), UA `nile/0.1 Amazon`,
  connect 30 s / read 120 s (`AmazonDownloadManager.java:323-326`).

## 3. Plan handed to the engine

Java builds the plan from `manifest.allFiles` and serialises it as a JSON array
(`AmazonDownloadManager.buildRustPlan`):

```json
[{"relPath":"Binaries/Win64/Game.exe","url":"https://…/files/<hex>?<sig>","size":123,"sha256hex":"<64 hex>"}]
```

- `relPath` = `ManifestFile.unixPath()` (backslashes → `/`).
- `url` = the exact string the Java loop would have opened (`appendPath(base, "files/"+hashHex)`).
- `sha256hex` = `hashHex()` when `hashAlgorithm == 0 && hashBytes.length > 0`, else `""`
  (Java skips verification in that case, `:267`) — Rust skips it on `""`.
- Manifest and auth stay in Java; the engine never talks to `gaming.amazon.com`.

## 4. Rules table

| rule | Java line(s) (`AmazonDownloadManager.java`) | Rust status |
|---|---|---|
| Pool size `MAX_PARALLEL = 8` fixed threads, one task per file, all files submitted up front, futures joined in manifest order | 45, 150-165 | **1:1** — `FetchOptions.max_workers = 8`, `per_host_cap = 8` (single CDN host; the core honours hosts × cap = 8), `stream = true` so 8 whole files stream at once regardless of size (`reserve` is ignored in stream mode — no byte-budget serialisation); items submitted in manifest order |
| Whole-file GET, no Range; body copied to the tmp in 64 KiB reads | 256, 323-346 | **1:1** — one `FetchItem` per file, `range = None`; stream mode appends each received piece to `<dest>.tmp` (`on_chunk`), so memory = pieces not yet written, like Java's 64 KiB buffer |
| UA `nile/0.1 Amazon`, no auth header on file GETs | 48, 326 | **1:1** — `FetchOptions.headers = [("User-Agent","nile/0.1 Amazon")]` |
| Timeouts connect 30 s / read 120 s | 324-325 | **≈** — stream mode: `timeout` (120 s) = response-headers deadline, then an idle deadline per received piece = Java's read timeout. A connect stall fails at 120 s instead of 30 s (then retries) |
| HTTP non-2xx → `IOException("HTTP n")` → retry | 328-333 | **1:1** — core classifies non-200 as a failed attempt |
| Resume-skip: `destFile.exists() && destFile.length() == file.size` → credit `size`, **no hash check on skip** | 250-253 | **1:1** — `amazon::is_present_and_complete` = `fs::metadata(dest).len() == size` (same `st_size` compare, same "no verify" rule); skipped bytes/files are credited into the first progress tick |
| Skip check runs inside the worker (so it happens even while other files download) | 250 | **≈** — Rust computes skips at plan time before the window starts; same result, earlier |
| `mkdirs` parent before each attempt | 260 | **1:1** — `create_dir_all(parent)` before writing tmp |
| Download to `<dest>.tmp` (fresh `FileOutputStream` per attempt = truncate), then SHA-256 the tmp file, compare to `hashBytes` | 247, 263-269, 336 | **1:1** — `on_chunk(offset == 0)` = `File::create` (truncate) of `<dest>.tmp`, SHA-256 accumulated per piece, compared in `on_finish`; a mismatch deletes the tmp (Java `:271`) |
| SHA-256 mismatch → delete tmp, sleep `1000 << (attempt-1)`, retry; give up after 3 | 269-277 | **DEVIATES (§9-C)** — mismatch returns `SinkError::Retry`, which the core counts as a failed attempt (rotate/backoff/≤5) |
| `MAX_RETRIES = 3`, backoff 1 s / 2 s (no sleep after the 3rd) for I/O errors | 46, 258, 295-302 | **DEVIATES (§9-C)** — core retry policy (≤5 attempts, its own backoff) |
| Rename: delete existing dest, `tmp.renameTo(dest)`; rename failure = file fails with NO retry | 280-284 | **1:1** — `remove_file(dest)` if present, `fs::rename`; failure → `SinkError::Fatal` |
| Any file failing aborts the whole install (`pool.shutdownNow()`), `IN_PROGRESS` marker deleted, `return false` | 167-186 | **1:1** — `FetchOutcome.error` → Java branch logs `A file failed — aborting download`, `deleteMarker(IN_PROGRESS)`, `return false` |
| Completed files are kept on failure/cancel (resume picks them up by size); every failed/cancelled attempt deletes its tmp | 271, 288, 293, 297 (`cleanupTmpFiles` is dead code) | **1:1** — same on-disk layout, same `.tmp` name, same markers; `AmazonSink::cleanup_partials` deletes any tmp whose stream never reached `on_finish` (max attempts / cancel / fatal); a Java-started download resumes on Rust and vice versa |
| Cancel checked per 64 KiB read; on cancel: `conn.disconnect()`, tmp deleted, task returns false → install returns false | 340-345, 286-289 | **≈** — `CancelChecker` polled every 100 ms by the facade → `nativeCancel` flips the core's `AtomicBool`; in-flight streams stop, the sink refuses further pieces, `cleanup_partials` deletes the partial tmps. Java's caller then does the same `cancelled.get()` → `markCancelled` dance on both engines |
| Pause | — (`supportsPause = false`) | **shared** — none on either engine |
| Progress: aggregate bytes counted per read; emitted when `dl - lastEmit >= 512 KiB` (CAS), speed sampled every ≥500 ms, label = `"<file>  <speed>"` | 47, 347-364 | **1:1 on cadence** — stream mode credits every written piece to progress; the Java branch keeps the SAME 512 KiB CAS gate + the same 500 ms speed sampler. Label = `"<speed>"` only (§9-B, cosmetic) |
| Retried attempts re-count their bytes (`totalDownloaded` is never rolled back) | 347 | **1:1** — stream mode: pieces of an attempt that later fails stay counted (`FetchOutcome.bytes_credited` doc) |
| First tick `onProgress(0, total, "Starting…")` | 136-138 | **shared** (before the switch) |
| Registry: `StoreDownloadHooks.tick(AMAZON, id, pct, installDone=dl, installTotal=total)` on every callback; FGS notification line from the same tick | callers (§1) | **shared** |
| Error surfaces: `Log.e(BH_AMAZON, …)` + `bh_amazon_debug.txt` (single write at the end with `FAIL: <path>` lines); callers show "Download failed" / `markFailed` | 44, 161, 169-175, 216-228 | **1:1** — Rust log lines are appended to the same `dbg` buffer (and to logcat `BL_AMAZON_DL`); the failing file is in `FetchOutcome.error` |
| Post-install: `cacheManifest` (`files/manifests/amazon/<pid>.proto`), `amazon_manifest_version_<pid>` pref, delete `IN_PROGRESS`, create `COMPLETE` marker, debug file, `Install complete` log | 188-205 | **shared** |
| Post-install (callers): exe scan + `amazon_exe_<pid>`, `markInstalled` → durable library row, `RESULT_REFRESH`; launch config = `fuel.json` read at LAUNCH time (`AmazonLaunchHelper.buildLaunchSpec`), FuelPump env from `buildFuelEnv` | §1 | **shared** |
| Install-state "DB" = `bh_amazon_prefs` keys (`AmazonInstallState`) + `.amazon_download_complete` marker | `AmazonInstallState.kt` | **shared** |
| Library sync (`AmazonLibrarySync`) reads the complete marker / exe existence | `AmazonLibrarySync.kt:145` | **shared** |

## 5. Threading

Java: caller coroutine (IO dispatcher) blocks in `install()`; 8 pool threads fetch; callbacks
fire on pool threads. Rust: the same caller thread blocks in
`BlAmazonDownload.runBlocking` (latch + 100 ms cancel poll); `nativeStart` spawns ONE
native thread which runs `fetch_core::run_fetch` (current-thread tokio runtime + process
pool); `onProgress`/`onLog`/`onComplete` are called from native threads attached as daemon
JNI threads. Nothing in the Java manager or its callers is thread-affine (all use atomics
and `runOnUiThread`/`withContext`), so this is transparent.

## 6. Cancel / on-disk state

| state | Java | Rust |
|---|---|---|
| in-flight file | `<dest>.tmp` partially written, deleted on cancel/failure | `<dest>.tmp` partially written (pieces appended as they arrive), deleted by `cleanup_partials` on cancel/failure |
| completed file | `<dest>` (renamed) | identical |
| markers | `.amazon_download_in_progress` created at start, deleted on failure/cancel/success; `.amazon_download_complete` on success | identical (shared code) |
| journal | none (size-based resume only) | none (same) |

## 7. Progress cadence and feeds

Both engines: `ProgressCallback.onProgress(bytesDone, totalInstallSize, label)`; callers
map it to `StoreDownloadHooks.tick` (registry row + shade notification) and their own
labels. Rust credits every received piece (stream mode) and keeps the Java 512 KiB / 500 ms
gates, so the registry and notification are updated at the same rhythm as today.

## 8. Log / proof

- `adb logcat -s BL_AMAZON_DL` → `engine=rust mode=stream plan=<n> files skip=<k> (<bytes>) fetch=<m> (<bytes>) hosts=1 workers=8`
- `fetch-start label=amazon … mode=stream` from the core
- `fetch-window … label=amazon …` lines from the core (same shape as Steam's `depot=` lines)
- final `summary bytes=<n> elapsed=<s> avg_mbps=<x> peak_mbps=<y> files=<done>/<total> items_ok= tmp_removed= cancelled= error=`
- the same lines are in `<externalFilesDir>/bh_amazon_debug.txt` (written once at the end,
  as today) prefixed `[rust] `.
- Java engine (flag OFF, or `libblsteam.so` not usable): `engine=java` line only.

## 9. Known deviations (morning items)

- **A. Memory vs concurrency — RESOLVED by stream mode.** `FetchOptions.stream = true`
  streams each body into `<dest>.tmp` piece by piece; 8 files of any size run at once like
  Java's pool and memory is bounded to the pieces not yet written (whole-body mode would have
  serialised files > 24 MiB behind the byte budget).
- **B. Label.** The progress label carries the speed only, not `"<file>  <speed>"` — the
  detail page never displayed the file name; the store-list card shows the speed instead of
  the file being written. Cosmetic.
- **C. Retry policy.** Core: ≤ `MAX_ITEM_ATTEMPTS` with its own backoff + host rotation;
  Java: 3 attempts, 1 s / 2 s. Outcome class is the same (transient error → retried; hard
  failure → whole install fails). Fix: `FetchOptions.max_attempts` / backoff override.
- **D. Timeout shape.** 120 s headers deadline + 120 s idle-per-piece vs 30 s connect + 120 s
  read: a dead connect is noticed later (then retried).
- **E. Cancel latency.** ≤100 ms poll vs per-read check; same on-disk result.
- **F. Skip timing.** Resume-skip is evaluated for all files before the window starts instead
  of inside each worker; same result, earlier.

Everything else in §4 is 1:1.

## 10. Improvements round 1 (2026-09-07, user go: "raise the ceilings")

Device evidence that motivated it: Dread Templar 4.2 GB — Rust 71.5 s (avg 470 Mbps, peak
579) vs Java 68.4 s, both 8 wide on the one a2z host (parity cap).

- **Ceiling from the Steam speed tier (Rust path only).** `AmazonDownloadManager.downloadAllRust`
  now passes `maxWorkers = DownloadSpeedConfig(DEFAULT_TIER).maxNetworkWindow.coerceIn(1,128)`
  = **32** (Fast tier) and `processWorkers = max(cores/2, cfg.maxDecompress.coerceIn(1,32))`
  into `BlAmazonDownload.runBlocking`. The Java fallback loop keeps `MAX_PARALLEL = 8` and
  every other count untouched.
- **Whole ceiling on the single host.** `amazon::per_host_cap_for(max_workers, distinct_hosts)`
  = `max(6, ceil(32 / 1))` = **32**, so the core's `hosts × per_host_cap` clamp no longer cuts
  the window; stream mode stays (no byte-budget serialisation, memory = unwritten pieces).
- **Duplicate log lines fixed.** Each engine line (`engine=rust …`, `fetch-start …`,
  `fetch-window …`, `summary …`) appeared twice in logcat: the native facade wrote it via
  `__android_log_write` AND forwarded it to the listener, where the Java manager wrote it
  again via `Log.i`. The native write is gone; the Java side is the single logcat + debug-file
  writer.
- Every §4 rule is unchanged (skip/resume, hash, `.tmp` layout, cancel, progress gates and
  strings, post-install). The core was not edited.

New log grammar on the `engine=rust` line:
`engine=rust mode=stream plan=<n> files skip=<k> (<bytes>) fetch=<m> (<bytes>) hosts=1 workers=32 per_host_cap=32 process=<p> dir=…`
followed by the core's `fetch-start label=amazon … ceiling=32 … per_host_cap=32 … mode=stream`.
