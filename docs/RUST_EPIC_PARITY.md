# Rust Epic engine — download-flow recon + parity (`use_rust_epic_engine`)

Acceptance rule (shared brief, 2026-09-07): with the flag ON the native fetch core replaces ONLY
the byte-fetching inner loop of `EpicDownloadManager.java`; every other step runs the exact
Java code it runs today, and the on-disk state after success / failure / cancel is the same on
either engine so a download can be resumed by whichever engine runs next. Nothing in this file
is an "improvement" — deviations are listed as deviations.

Branch `feat/rust-dl-epic` (rebased on `feat/rust-dl-core`); crate
`app/src/main/cpp/bl-steam-client/rust/src/store_dl/epic{.rs,/}`; Kotlin facade
`com.winlator.star.store.blsteam.BlEpicDownload`; switch in `EpicDownloadManager.install`.
Line numbers below are for `app/src/main/java/com/winlator/star/store/EpicDownloadManager.java`
on this branch. Status legend: **1:1** = same rule, same code path or a line-for-line port;
**Java** = the step still runs the Java code (untouched); **DEV** = documented deviation.

## 1. Entry points (all unchanged)

| Caller | Call | Tags | Cancel flag | Notes |
|---|---|---|---|---|
| `EpicGameDetailActivity.startInstallInternal` (`:382`) | `install(ctx, json, token, dir, tags, cancelled, cb)` | `EpicInstallTags.tagsForCurrentContainer` | yes | store install; registry row via `StoreDownloadHooks.registerDownload` (`:351`), `tick(pct)` per progress (`:393`), `markInstalled` / `markFailed` / `markCancelled` after |
| `EpicGamesActivity` (`:453`) | same | same | yes | list-row install; same hooks (`:405`, `:462`, `:475`, `:483`, `:516`) |
| `EpicGameDetailActivity.doVerifyRepair` (`:667`) | `install(ctx, json, token, dir, tags, cb)` | same | none | repair = install again; delta path re-downloads only bad files |
| `EpicGameDetailActivity.dlcInstall` (`:700`) | `install(ctx, json, token, dir, cb)` | `null` (all files) | none | DLC |
| `EpicOverlayManager.ensureOverlayInstalled` (`:116`) | `install(ctx, json, token, dir, null)` | `null` | none | EOS overlay component |

The engine switch sits inside `install(...)` so every caller gets it, including the no-cancel
(`cancelFlag == null`) ones — the facade accepts a null flag and never cancels, like Java.

## 2. The Java flow, step by step

1. **CDN list** — `parseCdnUrls(manifestApiJson)` (`:253`, impl `:674`): hand-rolled scan of
   the `"manifests"` array; each `"uri"` split at `/Builds` into `baseUrl` + `cloudDir`
   (query string stripped, last path segment = manifest filename dropped); entries whose base is
   not `http*` or contains `cloudflare.epicgamescdn.com` are **skipped** (`:701`, 403 on chunks);
   `queryParams` become `authParams` (`extractQueryParams` `:719`) and are used **only** for the
   manifest download, never for chunks. Empty list → `false` ("No CDN URLs").
2. **Manifest binary** — `downloadManifest` (`:269`, impl `:772`): filename from the first
   `uri`; tries `baseUrl + cloudDir + "/" + filename + authParams` on each CDN in order;
   `downloadBytes` (`:1129`; UA `UELauncher/11.0.1-…`, 30 s connect / 60 s read, HTTP 200 only);
   first body > 4 bytes wins. `null` → `false`.
3. **Manifest parse** — `parseManifest(bytes)` (`:281`, impl `:809`): magic `0x44BEC00C` →
   binary (header: headerSize, sizeUncompressed, sizeCompressed, 20-byte SHA-1 skipped, storedAs,
   version → `chunkDir` = ChunksV4 (≥15) / ChunksV3 (≥6) / ChunksV2 (≥3) / Chunks; body at
   `headerSize`, zlib-inflated when `storedAs & 1` and the inflated length must equal
   `sizeUncompressed`; ManifestMeta skipped by size; ChunkDataList = guid[4]×N, hash u64×N,
   SHA-1×N, groupNum u8×N, windowSize i32×N, fileSize i64×N, then jump to `cdlStart+cdlSize`;
   FileManifestList = filename FString×N, symlink FString×N, SHA-1×N, flags u8×N, install-tag
   lists (empty tags dropped), chunk-part lists (each part `structSize, guid[4], offset, size`,
   jump by structSize), jump to `fmlStart+fmlSize`; `uniqueChunks` = LinkedHashMap by GUID
   string (duplicate → last value, first position). Other magic → `parseJsonManifest` (`:937`;
   `ChunkHashList` / `DataGroupList` / `ChunkFilesizeList` / `FileManifestList`; `Long.parseLong(
   …,16)` hash + hex size, decimal group, `Offset`/`Size` decimal, no SHA-1s, `windowSize = 0`).
   Any exception → `null` → `false`.
4. **Dirs** — `installDir.mkdirs()`, `<installDir>/.chunks` mkdirs (`:298`).
5. **Install tags** — `resolveInstallFiles(manifest, installTags)` (`:305`, impl `:1387` /
   `:1372`): `null` → all files; empty → untagged files (or all, if no file is tagged);
   otherwise untagged + any file with ≥1 selected tag, falling back to untagged-only when nothing
   matched. Tags come from `EpicInstallTags.tagsForCurrentContainer` (first container's `lc_all`
   → device locale → English), unchanged.
6. **Delta / resume / verify** — for each selected file (`:315-340`): cancel check (→
   `CANCELLED during verify (n/m files hashed)`, `false`); `fileExistsWithCorrectHash(out,
   fileSize(), sha1)` (`:1423`: exists, `length()==Σparts`, manifest SHA-1 present and not
   all-zero, streamed SHA-1 equal) → skip, else pending; progress `"Verifying existing files… (n/m)"`
   at 0 % every 64 files. `pendingFiles.isEmpty()` → `deleteDir(.chunks)`, `"Complete"` 100 %,
   `true` (`:344`).
7. **Chunk plan** — `neededChunks = uniqueChunksForFiles(manifest, pendingFiles)` (`:354`, impl
   `:1400`: first-seen GUID order over pending files' parts, unknown GUIDs dropped);
   `totalBytes = Σ max(chunk.fileSize, 1)` (`:358`).
8. **Chunk pool** — **THIS is what the engine replaces** (`:406-466`):
   `Executors.newFixedThreadPool(8)`, one task per needed chunk in plan order. Task: cancel →
   return; `cachedFile = .chunks/<GUID>`; exists → skip fetch; else
   `downloadChunkStreaming` (`:1167`): `.part` deleted first (`:1175`); for each CDN in order:
   `GET baseUrl + cloudDir + "/" + chunkDir/%02d/%016X_GUID.chunk` (`:1177`, path `:98-100`;
   group folder DECIMAL), UA header, 30/60 s timeouts, non-200 → next CDN; read 41-byte header
   (`:1201`): magic `0xB1FE3AA2` else next CDN (`:1206`); `headerSize > 41` → skip the extra
   (`:1216`); payload = next `compressedSize` bytes, stream end just stops (`:1230`, `:1249`);
   `storedAs & 1` → zlib inflate streamed to `.part` (`:1220-1242`), else raw copy (`:1244`);
   SHA-1 of the DECOMPRESSED bytes when the manifest SHA-1 exists and is not all-zero
   (`:1187-1193`), mismatch → delete `.part`, next CDN (`:1257`); `renameTo(final)` fails →
   delete, next CDN (`:1265`); any exception → delete `.part`, next CDN (`:1276`); all CDNs
   failed → `false` (`:1281`) → task logs `FAIL chunk=GUID`, `failCount++` (`:418-419`) and the
   pool **keeps going** with the other chunks. On success (or cached): `completedBytes +=
   max(fileSize,1)`, `completedCount++`, `pct = done*80/total`, speed sampled at ≥500 ms
   (`:425-433`), `progress("Downloading chunks (n/N)  a / b MB  x MB/s", pct)`.
   Wait loop: `awaitTermination(250 ms)`; cancel seen → `shutdownNow()`, wait ≤5 s,
   `CANCELLED during chunk download (n/N chunks)`, `false` (`:447-458`); interrupted →
   `ERROR: chunk pool interrupted`, `false` (`:459-466`).
9. **Post-pool** (`:469-486`, shared by both engines): late cancel → `CANCELLED after chunk
   download`, `false`; `chunkLog` drained into the debug file; `failCount > 0` → `ERROR: N chunks
   failed`, `false`; else `chunksOK=n`.
10. **Assembly** (`:488-526`): per pending file (cancel → `CANCELLED during assembly (n/m files)`,
    `false`): `mkdirs`, progress `"Writing: <name>"` at `80 + done*20/total`, new
    `FileOutputStream` (truncate), for each part read `.chunks/<GUID>` fully and write
    `[offset, offset+size)`; a missing cache file → `ERROR: missing chunk`, `false`.
11. **Finish** (`:528-531`): `deleteDir(.chunks)`, `INSTALL COMPLETE`, `true`. Any exception
    anywhere → `EXCEPTION: …`, `false` (`:534`). Debug file: `writeDebug` (`:654`) writes the
    whole `dbg` buffer to `<externalFilesDir>/bh_epic_debug.txt` at every exit.

Post-install in the callers (unchanged, Java/Kotlin): `versionId` →
`epic_manifest_version_<app>` pref; `AmazonLaunchHelper.collectExe` + `scoreExe` → best exe →
`epic_exe_<app>`; `EpicEosDetector.scanAsync`; `StoreDownloadHooks.markInstalled(EPIC, app, dir,
epic_size)` (registry INSTALLED row + shade notification dropped); on cancel
`EpicCancelPolicy.consumeDeleteOnCancel` → `deleteDir(installDir)` + `EpicInstallState.purge`,
then `markCancelled`; on failure `markFailed(msg)`. Cloud saves (`EpicCloudSaveManager`), game
fixes (`EpicGameFixes`), overlay (`EpicOverlayManager`) and launch args are not part of the
download flow and are not touched.

## 3. Where the switch is and what crosses it

`install(...)` at `:379-406`: after the plan + totals exist and before the pool,
`if (BlStoreEngineFlag.isEpicEnabled(ctx))` → `runRustChunkPool(...)` (`:561`). Inputs handed to
the engine: the SAME `manifestBytes`, `installDirPath`, the CDN list as `baseUrl + cloudDir`
prefixes (cloudflare already skipped by Java), the pending files as indices into
`manifest.files` (identity map, `:585-592`), Java's `neededChunks.size()` and `totalBytes` for
the cross-check, the CA bundle path (`CaBundleExtractor.ensureBundle`), `maxWorkers = 8`,
`processWorkers = 2`, the caller's cancel flag (nullable), and a listener that drives the same
`completedBytes` / `completedCount` / speed counters and the same `progress(...)` message.

Engine side (`store_dl/epic/driver.rs`): re-parse manifest (`manifest.rs`, port of §2.3) →
`unique_chunks_for_files(pending)` + `Σ max(fileSize,1)` (`plan.rs`) → cross-check against
Java's count + bytes → **plan mismatch = "not started"** (Java pool runs; nothing fetched) →
cached-skip pass → `FetchItem` per remaining chunk, one URL per distinct CDN prefix →
`fetch_core::run_fetch` with a `FetchSink` that does header parse + inflate + SHA-1 + `.part` +
rename (`chunk.rs`, port of §2.8) → outcome. Java then runs §2.9-11 unchanged.

If the engine cannot start (library missing / `UnsatisfiedLinkError` / plan mismatch / any
throwable in the facade) Java logs `rust engine not started (…) -> Java chunk pool` and runs its
own pool — nothing has been fetched at that point, so this fallback cannot change on-disk state.

## 4. Parity table

| rule | Java line(s) | Rust engine status |
|---|---|---|
| manifest API JSON parse, CDN list, cloudflare skip, `authParams` | `:253`, `:674-717`, `:701` | Java (before the switch); prefixes handed over as `baseUrl+cloudDir` |
| manifest binary download (CDN order, auth params, 30/60 s, >4 bytes) | `:269`, `:772-807` | Java |
| binary manifest parse (header, chunkDir thresholds, zlib + size check, meta skip, CDL/FML layout, FString UTF-16LE/ASCII, dup-GUID last-value-first-position) | `:809-935`, `:1306` | 1:1 — `manifest.rs::parse_manifest` (Java parses first; Rust re-parses the same bytes; 8 unit tests incl. compressed/uncompressed, truncation, size mismatch, dup GUID) |
| JSON manifest parse (`ChunkHashList` etc., parse fallbacks, no SHA-1, `windowSize 0`) | `:937-1046` | 1:1 — `manifest.rs::parse_json_manifest` (2 tests) |
| `.chunks` cache dir under the install dir | `:298` | 1:1 — same dir; Rust `create_dir_all` is a no-op after Java's `mkdirs` |
| install-tag selection (`null`/empty/tags, required fallback) | `:305`, `:1372-1398` | 1:1 — `plan.rs::resolve_install_files` (tests); at runtime Java's selection is the input |
| delta / resume: size + full-file SHA-1 (all-zero = unverifiable), cancel during verify, progress every 64 files, nothing-to-do exit | `:315-352`, `:1423-1440` | Java (before the switch) |
| needed chunks = first-seen order over pending files' parts | `:354`, `:1400-1415` | 1:1 — `plan.rs::unique_chunks_for_files`, cross-checked by count |
| `totalBytes = Σ max(fileSize, 1)` | `:358` | 1:1 — `plan.rs::total_credit_bytes`, cross-checked by value |
| concurrency = fixed 8 | `:408` | was 1:1 in the parity build; since improvements round 1 (§6) the native path uses the speed-tier ceiling (32) split across the CDNs — the Java fallback pool keeps its fixed 8 |
| chunk skip when `.chunks/<GUID>` exists (any size), still credited to progress | `:414-415`, `:422-423` | 1:1 — `driver.rs` cached-skip pass, same credit, one progress callback per skipped chunk |
| chunk URL `prefix/chunkDir/%02d/%016X_GUID.chunk`, DECIMAL group | `:98-100`, `:1177` | 1:1 — `ChunkInfo::path` + `plan.rs::chunk_url` (tests) |
| no auth on chunk URLs, UA header | `:1177`, `:1183` | 1:1 — `FetchOptions.headers = [User-Agent]` |
| HTTP timeouts 30 s connect / 60 s read; non-200 → next CDN | `:1181-1184` | DEV — one core timeout (60 s) covers the request; non-200 handled by the core's failure classification |
| chunk header: 41 bytes, magic `0xB1FE3AA2`, `headerSize > 41` skip, payload = `compressedSize` bytes or what the stream has | `:1201-1216`, `:1230`, `:1249` | 1:1 — `chunk.rs::parse_chunk_header` / `chunk_payload` (tests) |
| zlib inflate (`storedAs & 1`) else raw copy; corrupt stream = failed attempt; early end = partial output | `:1220-1252` | 1:1 — flate2 `ZlibDecoder` streamed into the `.part` (tests for corrupt + truncated) |
| SHA-1 of DECOMPRESSED bytes when manifest SHA-1 present and not all-zero; mismatch → next CDN | `:1187-1193`, `:1257-1262` | 1:1 — `ChunkInfo::verifiable_sha1` + `write_verified_chunk` → `SinkError::Retry` |
| `.part` deleted before each attempt; verified → `renameTo(final)`; rename fail → next CDN; exception → delete `.part` | `:1175`, `:1265-1277` | 1:1 — same names, same order, `.part` never survives a failed attempt (tests) |
| per-attempt failure → try the next CDN, once each, in order | `:1176`, `:1268-1281` | DEV — the core rotates hosts on `Retry` with backoff, bounded by its attempt cap (5); Java made exactly one attempt per CDN (usually 2-3) with no backoff. More resilient, never less |
| one chunk exhausting all CDNs → `failCount++`, pool continues, install fails at the end | `:416-421`, `:479-483` | DEV — the core records the error when an item exhausts its `MAX_ITEM_ATTEMPTS` (5), stops scheduling and aborts in-flight requests (bodies already handed to the process pool are still verified + published); queued chunks are not fetched. Same terminal outcome (`ERROR: 1 chunks failed` → `false`, `markFailed("Download failed")`), less wasted transfer |
| progress per chunk: `completedBytes`, `completedCount`, `pct = done*80/total`, speed sample ≥500 ms, message `Downloading chunks (n/N)  a / b MB  speed` | `:422-441` | 1:1 — `onProgress` writes the SAME counters and runs the same lines (`:611-631`); `StoreDownloadHooks.tick(pct)` + `Log.i` cadence unchanged |
| cancel: no new chunk starts; in-flight interrupted; poll 250 ms; ≤5 s drain; `CANCELLED during chunk download (n/N chunks)`; `false` | `:412`, `:447-458` | 1:1 — facade polls the same flag every 250 ms → `nativeCancel` (AtomicBool → core stops scheduling, drops in-flight) → waits ≤5 s → Java writes the identical debug line and returns `false` |
| on-disk state after cancel: verified chunks under final names, inert `.part`s at most, no partial game files | `:1172-1176` | 1:1 — sink publishes only verified chunks; the cache is consumed by Java's assembly on the next run of either engine |
| late-cancel check, `chunkLog` drain, `failCount` exit, `chunksOK=` | `:469-486` | Java (after the switch) |
| assembly (truncate + sequential part writes from the cache, `Writing:` 80-100 %, missing chunk error) | `:488-526` | Java |
| `deleteDir(.chunks)`, `INSTALL COMPLETE`, exception → `false` | `:528-537` | Java |
| debug file `bh_epic_debug.txt` | `:654` | 1:1 + engine lines prefixed `[rust]` in the same buffer |
| no cancel flag (verify/repair, DLC, overlay) = never cancelled | `:208-236` | 1:1 — facade `cancel == null` → never polls |
| post-install: exe pick, `epic_exe_`/`epic_manifest_version_` prefs, EOS scan, `markInstalled`, cancel keep/delete, `markFailed` | callers (§1) | Java/Kotlin, untouched |
| `fetchInstallSizeBytes`, `verifyInstall` (no download) | `:1330-1368`, `:1459-1491` | Java, untouched (they never reach the pool) |

Things the engine deliberately does NOT do: no new resume format, no journal, no positioned
writes into game files, no per-file hashing, no host ranking beyond what the core does for every
store. Memory: Java streamed each chunk; the core hands the sink the whole compressed body
(Epic chunks are ≤1 MiB compressed), inflated straight into the `.part` — ≤ 8 bodies in flight
by the byte budget (`reserve = fileSize`).

## 5. Proof on device

logcat: `adb logcat -s BL_EPIC_DL` (or `bridge 'logcat -d -s BL_EPIC_DL'`). Expected sequence
per install: `engine=rust label="epic app=<dir>" …` → `plan chunk_dir=… chunks=N bytes=B hosts=H
workers=8 process_workers=2` → `skip cached=… to_fetch=…` → the core's `fetch-window label=…`
lines → `summary bytes= decompressed= skipped_chunks= elapsed= avg_mbps= peak_mbps= avg_MBps=`
→ `chunksOK=N`. A fallback shows `not started: plan: …` and Java's `BH_EPIC` line `Rust Epic
engine not started (…), using Java chunk pool`. The same lines appear prefixed `[rust]` in
`/sdcard/Android/data/<pkg>/files/bh_epic_debug.txt` next to Java's own lines
(`totalDownloadBytes=`, `chunksOK=`, `assembling N files`, `INSTALL COMPLETE`).

Java-vs-Rust A/B: same title, same `installTags`, compare `bh_epic_debug.txt` line sets — with
the engine on, the only extra lines are the `[rust] …` ones; every Java line (`delta:`,
`totalDownloadBytes=`, `chunksOK=`, `assembling`, `INSTALL COMPLETE`) is identical.

## 6. Improvements round 1 (2026-09-07, user go after the device-proven parity build)

Device evidence: Alone With You ran 12.4 s @ 24 MB/s on Rust vs 11.4 s @ 27.6 MB/s on Java —
both capped by the parity ceiling of 8. Change (Rust path only, `runRustChunkPool` in
`EpicDownloadManager.java`):

| knob | parity build | round 1 | where |
|---|---|---|---|
| window ceiling (`max_workers`) | 8 (Java pool width) | `DownloadSpeedConfig(DEFAULT_TIER).maxNetworkWindow` clamped 1..128 = **32** (Fast tier) | Java helper → JNI `maxWorkers` |
| `per_host_cap` | 8 (one CDN could carry all 8) | `max(6, ceil(32 / distinct CDNs))` = **11** with Epic's usual 3 CDNs, 16 with 2, 32 with 1 | `plan.rs::per_host_cap`, computed in the adapter, logged |
| `process_workers` | 2 | `max(2, cfg.maxDecompress clamped 1..32)` = cores × 0.5 (e.g. **4** on an 8-core SoC) | Java helper → JNI `processWorkers` |
| mode | whole-body | whole-body (chunks ≤ ~1 MiB compressed; the core's in-flight byte budget scales with the window) | — |

Everything else in §4 is unchanged: skip/resume, chunk verification, `.chunks` layout, cancel
poll/drain, progress strings and cadence, plan cross-check, fallback, post-install. The Java
fallback pool is byte-identical and still runs 8 threads.

Log grammar (logcat `BL_EPIC_DL`, mirrored as `[rust] …` in `bh_epic_debug.txt`):
`engine=rust label="epic app=<dir>" install_dir=… cdns=<n> hosts=<distinct> pending_files=<n>
workers=32 per_host_cap=<cap> process_workers=<n> manifest_bytes=<n>` → `plan chunk_dir=…
chunks=N bytes=B hosts=H workers=32 per_host_cap=<cap> process_workers=<n>` → `skip cached=…
to_fetch=…` → core `fetch-window label=… window=… (min=… max=…) in_flight=… budget_stalls=…
host_stalls=…` → `summary bytes= decompressed= skipped_chunks= elapsed= avg_mbps= peak_mbps=
avg_MBps=` → `chunksOK=N`. The `max=` on the `fetch-window` line should now read 32 (3 CDNs ×
11 = 33, clamped to the ceiling); `host_stalls` replacing `budget_stalls` as the binding
constraint is expected and not a regression.

### 6.1 CDN prefix normalization (round 2 device finding)

Alone With You on the round-1 build: 13.6 s, no gain; `fetch-window` shrank to 2 on
`shrink:err-burst` at t=0 (3 errors within 70 ms, `rtt=0ms`) and again every ~5 s (the core's
cooled-host re-probe), err_rate 2.5-4.9 %. Cause: `EpicApiClient.getManifestApiJson` re-serializes
the API response with Android `org.json`, which escapes `/` as `\/`; `parseCdnUrls` scans that
text literally, so Java's `baseUrl` is `https:\/\/egdownload.fastly-edge.com\` and `cloudDir`
is `/Builds\/Org\/…\/default\` (visible as-is in `bh_epic_debug.txt`'s `CDN:` lines). OkHttp maps
`\` to `/` and Java only ever used CDN[0], so it never showed. reqwest maps the same way but the
resulting `//` empty segments are rejected by CloudFront (`egs-cloudfront-chunks.epicgamescdn.com`)
with an immediate 4xx, while Fastly/Akamai normalize them — hence one bad host out of three,
demoted and re-probed every 5 s. Fix (adapter only, `plan.rs::normalize_prefix`, unit-tested):
`\/` → `/`, stray `\` dropped, trailing `/` trimmed, so the Rust URL is the one Java intended
(`https://host/Builds/Org/…/default/ChunksV4/NN/HASH_GUID.chunk`). The hosts are now logged one
per line (`host[i]=…`) after the `plan` line. The Java scanner itself is untouched (the Java pool
still works through OkHttp's tolerance). Chunk URLs remain unsigned, as in Java.
