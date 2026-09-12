# GOG Download-Engine Performance (gap #10) — Plan & Task List

**Branch:** `feat/gog-dl-perf` (off `main`)

**Goal:** Speed up the gen2 GOG downloader (pre-allocation, tunable/finer-grained
parallelism, better scheduling, and — only if the data justifies it — cross-file shared-chunk
dedup) **without regressing the now-MD5-verified, device-proven download path** (ELDERBORN +
XCOM 2 base-filter, redist/DLC reuse).

**Source of gap:** memory `reference_gog_gamenative_gap_roadmap` item **#10**
("download-engine perf: pre-alloc, shared-chunk dedup, file ordering, tunable parallelism").
GN implements this with `RandomAccessFile.setLength` pre-alloc, a `chunkUsageCounts` shared-chunk
map (download a shared chunk once), least-shared-chunk-first file ordering, a
`DownloadSpeedConfig` for parallelism, chunk-cache-on-failure, and a stuck-detect that re-emits
missing chunks. We have a fixed 8-thread, per-file-independent, no-pre-alloc engine today.

**Clean-room:** GN's GOG code is **GPL-3.0**. Every GN symbol below (`chunkUsageCounts`,
`DownloadSpeedConfig`, `setLength` pre-alloc, least-shared-first ordering, stuck-detect
re-emit) is cited as a **behavioural anchor by name only** — no GN Kotlin is read or lifted.
The design here is reconstructed from **our own** `GogDownloadManager.java` and from **real GOG
manifest measurements** captured read-only for this doc (numbers below). Do not open GN source
to implement this.

---

## TL;DR (the honest version)

1. **The measured shared-chunk opportunity is small.** Across four real gen2 titles, redundant
   (re-downloaded) chunk bytes are **0.02%–2.13%** of the download. Cross-file dedup — the
   headline GN feature — is **low-value** for us and should be **deferred and gated**, not built
   in P1. See "Measured reality" below with the numbers.
2. **The real bottleneck is file-size skew, not duplication.** Concurrency today is **per-file**
   (8 threads, one `DepotFile` each; chunks serial within a file). A title that ships a few huge
   archive files gets throttled to ≤ (#big-files) effective threads with serial chunks inside —
   most threads idle. The structural win is **chunk-level parallelism**, which needs pre-alloc +
   offset writes.
3. **P1 = the low-risk, scheduling-only wins:** tunable thread count + largest-file-first
   ordering. Zero change to the proven `assembleDepotFile` / MD5 / resume / cancel path.
4. **P2 = the structural win, gated & additive:** a chunk-level parallel path
   (`RandomAccessFile.setLength` pre-alloc + offset writes) for large files only, added
   **alongside** the proven sequential assembler, never replacing it.
5. **Dedup + least-shared-first ordering = P3, gated on the sharing number** (which says: barely
   worth it). At most an in-flight coalesce of concurrent identical fetches, never an on-disk
   chunk cache.

---

## In plain English (what this does for the user)

- **Faster big downloads** — games that ship a handful of multi-GB archive files stop bottleneck-
  ing on one thread; all download threads stay busy (chunk-level parallelism).
- **No end-of-download straggler** — largest files start first, so the download doesn't finish
  with one lonely giant file crawling to 100% while every other thread sits idle.
- **A speed knob** — the fixed 8-thread limit becomes tunable (device/network heuristic + a
  setting), so fast connections aren't capped at a conservative default.
- **Same integrity guarantees** — every chunk and every whole file is still MD5-verified exactly
  as today; resume-skip, cancel, disk-guard, DLC/redist reuse all unchanged.

---

## Current-engine map (with line refs — `GogDownloadManager.java`)

Entry: `download()` → gen2 builds URL is **public** (`:178`, no auth), falls back to authed
(`:181`) → `runGen2()` (`:187`, `:256`).

**`runGen2` (`:256`–`536`):**
- Resolve build manifest via `resolveGen2Manifest` (`:264`, `:557`) → `depots` array (`:272`).
- Persist gap#3 deps (`persistDependencies`, `:282`) — unrelated to perf.
- Base-product depot filter (gap#5): skip depots whose `productId != baseProductId` (`:315`–`326`);
  single-product titles (no `productId`) always kept (`:320`).
- Per-depot: language filter (`languageCompatible`, `:328`/`:589`) → fetch depot meta from CDN
  (`:336` `.../content-system/v2/meta/<buildCdnPath(hash)>`) → `decompressBytes` (`:345`) →
  `parseDepotManifest` (`:354`/`:1164`) appends `DepotFile`s to a flat `List<DepotFile> files`.
- **`parseDepotManifest` (`:1164`–`1202`):** per file, per chunk builds a `ChunkRef(hash,
  compressedMd5, decMd5, compressedSize, decSize)` where `hash = compressedMd5 ?: md5` (`:1189`);
  `df.totalSize = Σ chunk.size` (decompressed, `:1194`/`:1196`). **No per-chunk offset is stored**
  and **no cross-file chunk index is built** — each file is fully independent.
- Secure link for `baseProductId` → `cdnBase` (`:367`–`377`, `parseCdnUrl` `:1336`).
- Install dir + `.gog_chunks` temp (`:380`–`384`).
- **Disk guard (`:388`–`399`):** `plannedBytes = Σ df.totalSize` (decompressed) vs
  `installPath.getUsableSpace()`; hard-stops with a `DISK_GUARD:` sentinel that suppresses the
  gen1 fallback (`:189`–`192`).
- **Parallel download (`:402`–`476`):**
  - `ExecutorService pool = Executors.newFixedThreadPool(8)` — **hardcoded 8** (`:417`).
    (Comments are stale: `:401` says "6 parallel threads", `:415`/`:476` log "8 threads".)
  - **One `pool.submit` task per `DepotFile`** (`:419`–`465`) — the unit of parallelism is a
    **whole file**, never a chunk.
  - Per task: `fileVerified(outFile, df.totalSize, df.md5)` resume-skip (`:427`/`:1550`) → else
    `assembleDepotFile(...)` (`:436`) → on failure sets `anyFailed` (`:443`), which every task
    checks up front (`:421`) so **one failed file aborts the whole install** (`:475`).
  - Progress/speed accounting is **per completed file** (`:446`–`462`) — coarse for a few big
    files.
- Success: manifest marker, delete `.gog_chunks`, persist dir/build/client, exe resolution
  (`:478`–`532`).

**`assembleDepotFile` (`:1249`–`1290`) — the shared, MD5-verified assembler:**
- Writes to `outFile + ".bhtmp"` (`:1254`), `parent.mkdirs()`, deletes any stale tmp (`:1257`).
- **Sequential** loop over `df.chunks` (`:1260`): `fetchChunkVerified(...)` (`:1263`) then
  `fos.write(inflated)` **in manifest order** into a plain `FileOutputStream` — **append-only, no
  pre-alloc, no seek/offset** (`:1259`/`:1267`).
- Whole-file verify: size (`:1274`) then MD5 (`:1280`) → `renameTo(outFile)` atomically (`:1289`).
  Any mismatch/cancel deletes the tmp and returns false (`:1272`–`1287`).
- **Shared by 3 callers** — game files (`runGen2` `:436`), DLC (`doInstallDlc` `:750`), and redist
  (`assembleDependencyInstaller` `:1303`/`:1320`). **This signature/behaviour is load-bearing for
  the DLC + redist paths — do not modify it in place.**

**`fetchChunkVerified` (`:1602`–`1664`):** per-chunk, end-to-end verified — compressed size
(`:1627`) + compressed MD5 (`:1634`) → `inflateZlib` (`:1643`, stored-chunk fallback `:1644`) →
decompressed size (`:1645`) + decompressed MD5 (`:1652`). Retries: bounded 3 hard failures with
`sleepBackoff` (`:1622`/`:1560`); on 401/403/404/500 refreshes the secure link via
`tryRefreshCdn` (`:1616`/`:1571`, cap 5) **without** counting as a hard failure. This is already a
per-chunk "stuck-detect + retry"; the gap is that a file that exhausts retries aborts the whole
install rather than being re-emitted.

**Where a shared chunk is re-downloaded today:** in `assembleDepotFile` (`:1260`–`1267`). Each
file's task fetches **every** `ChunkRef` in its own `df.chunks`. If the same `compressedMd5`
appears in file A's and file B's chunk lists, both tasks call `fetchChunkVerified` for it
independently — there is **no cross-file `compressedMd5` cache** anywhere. Two files sharing a
chunk that run concurrently even fetch it at the same time.

---

## Measured reality — is dedup worth it? (real data, captured read-only 2026-08-22)

Probed the live gen2 build manifests (public builds endpoint → depot metas → chunk lists),
counted every `compressedMd5` across all base-product, language-compatible files, and summed the
**redundant** (would-be-re-downloaded) compressed bytes.

| Title (product) | files | chunk refs | unique chunks | dup keys | redundant refs | redundant bytes | **% wasted** |
|---|---:|---:|---:|---:|---:|---:|---:|
| **XCOM 2** (`1482002159`) | 35,176 | 36,962 | 27,430 | 2,452 | 9,532 | 739 MB / 34.7 GB | **2.13%** |
| **ELDERBORN** (`1732383191`) | 381 | 776 | 729 | 7 | 47 | 7.8 MB / 2.8 GB | **0.28%** |
| **Witcher 2 EE** (`1207658930`) | 1,563 | 4,255 | 3,941 | 126 | 314 | 4.4 MB / 21.5 GB | **0.02%** |
| **Cyberpunk-tier** (`1495134320`) | 2,536 | 7,605 | 7,532 | 26 | 73 | 15 MB / 53.3 GB | **0.03%** |

**Verdict: chunk-sharing is real but marginal.** Even the worst case (XCOM 2) wastes only
**2.13%** of bytes; the typical case is **0.02–0.3%**. Two shapes of duplication exist and both
are low-value:
- **Many tiny repeats** (e.g. ELDERBORN's 77 KB chunk × 38, Witcher's 7 KB chunks × 13) — high
  ref-count, negligible bytes.
- **A few big repeats** (XCOM's ~5 MB chunks × 5–6) — these are the only meaningful bytes, and
  they concentrate in one atypically-duplicated title.

**Conclusion for the plan:** do **not** build the GN-style on-disk `chunkUsageCounts` cache in
P1/P2. The engineering cost (a concurrent, disk-backed chunk store; eviction; keeping it correct
under cancel/resume; interplay with the disk-guard) is high against a 0.02–2% payoff. Prioritise
**pre-alloc + chunk-level parallelism + scheduling**, which help **every** title regardless of
duplication. Revisit dedup only as an **in-flight coalesce** (below), and only after the chunk
queue exists.

---

## P1 — ship the low-risk, scheduling-only wins (recommended first slice)

Ranked by value / risk. **None of these touch `assembleDepotFile`, `fetchChunkVerified`, the MD5
guarantees, resume-skip, cancel, the disk-guard, or the DLC/redist callers.** They only change
*how many* tasks run and in *what order*.

### P1a — Tunable parallelism (was hardcoded 8) — **highest value / lowest risk**
- Replace `Executors.newFixedThreadPool(8)` (`:417`) with a resolved thread count:
  `int threads = resolveDownloadThreads(ctx)`.
- Heuristic default: `clamp(Runtime.getRuntime().availableProcessors() * 2, 6, 16)` — a phone with
  8 cores → ~16; low-core devices stay conservative. Cap it so we don't hammer the CDN or the
  device's I/O.
- Expose an override in GOG/download settings (`bh_gog_prefs` key `gog_dl_threads`, `0` = auto).
  This is our clean-room `DownloadSpeedConfig` equivalent — a plain int knob, data-only.
- Fix the stale log/comment strings (`:401`, `:415`, `:476`) to print the resolved count.
- **Risk:** minimal. More threads = more concurrent sockets/inflate; bounded by the clamp. No
  correctness surface touched.

### P1b — Largest-file-first scheduling — **real value / near-zero risk**
- Before the submit loop (`:419`), sort `files` by `df.totalSize` **descending** (LPT / longest-
  processing-time scheduling). Same set of tasks, same assembler, only submit order changes.
- **Why it matters (dedup-independent):** with per-file parallelism, the makespan is dominated by
  the biggest file finishing last. Starting big files first lets small files fill the tail and
  keeps all threads busy to the end — no lone-giant-file straggler.
- **Risk:** effectively zero — pure ordering. Progress % (`:429`/`:449`) is count-based so it may
  advance less linearly, but that's cosmetic. (Optional: switch progress to bytes-based so the
  bar tracks reality with skewed sizes.)

**P1 deliverable:** these two changes + doc/log fixes. Measurable speedup on archive-heavy titles
and fast connections, with the proven path byte-for-byte intact.

---

## P2 — chunk-level parallelism + pre-alloc (the structural win, additive & gated)

This is where `RandomAccessFile.setLength` pre-alloc actually pays off. Build it **alongside** the
sequential assembler, selected by a per-file threshold, so the proven path (and DLC/redist) is
never disturbed.

### Design
1. **Per-chunk offset.** In `parseDepotManifest` (`:1182`–`1195`) compute each chunk's decompressed
   offset as the running prefix-sum of preceding `chunk.size`, and store it on `ChunkRef` (new
   `final long offset`). Costs nothing for the sequential path (it ignores the field).
2. **New parallel assembler — `assembleDepotFileParallel(df, outFile, ...)`** (NEW method, does
   **not** replace `assembleDepotFile`):
   - Open `RandomAccessFile(tmp, "rw")`, `raf.setLength(df.totalSize)` — **pre-allocate** the file
     once (GN's `setLength` anchor).
   - Submit each chunk as a work item to the **shared** download pool (the same pool P1a sizes);
     each worker `fetchChunkVerified(...)` then writes at its offset via a positioned write
     (`FileChannel.write(ByteBuffer, offset)` — concurrent positioned writes are safe on distinct,
     non-overlapping ranges).
   - After all chunk workers for the file complete, run the **same** whole-file size+MD5 verify as
     `assembleDepotFile` (`:1274`–`1287`) and the atomic `renameTo` (`:1289`). The whole-file MD5
     is the backstop: any missing/hole chunk → wrong MD5 → tmp deleted, file retried.
3. **Selection / gating.** In `runGen2`, route each file:
   - small / few-chunk files (e.g. `df.chunks.size() <= K`, K ~ 8) → existing `assembleDepotFile`
     (proven, low overhead).
   - large multi-chunk files → `assembleDepotFileParallel`. This is where the win is: a single
     10-GB archive with 2000 chunks now saturates all N threads instead of one.
   - **Unit of work becomes the chunk for big files, the file for small files** — best of both.
     To avoid over-subscription, feed both routes from **one** bounded executor (submit chunk work
     items and small-file work items to the same `N`-thread pool via a shared `CompletionService`),
     rather than nesting a pool inside a pool.
4. **Failure handling (stuck-detect / re-emit anchor).** A chunk that exhausts
   `fetchChunkVerified`'s retries fails its file. Keep today's semantics (fail the file) but,
   optionally, **re-emit only the failed file's missing chunks once** before declaring
   `anyFailed` — a cheap, bounded version of GN's stuck-detect that avoids nuking a 50-GB install
   for one flaky chunk. Still fail-hard after the bounded retry.

### Keep `assembleDepotFile` untouched
DLC (`:750`) and redist (`assembleDependencyInstaller` `:1320`) keep calling the **unchanged**
sequential `assembleDepotFile`. The parallel path is opt-in for large game files only. This is the
core risk-mitigation: **add a method, don't mutate the shared one.**

---

## P3 — dedup + least-shared-first ordering (gated on the sharing number — data says skip)

Only if the measured payoff ever justifies it (it currently does not, 0.02–2.13%):
- **In-flight coalesce (the only defensible slice):** once P2's chunk queue exists, key in-flight
  chunk fetches by `compressedMd5` so two files needing the same chunk *at the same time* share
  one download (a `ConcurrentHashMap<compressedMd5, Future<byte[]>>`). This captures the *concurrent*
  duplicates with no on-disk cache and no eviction — cheap and safe. Bytes saved ≈ the fraction of
  dup refs that overlap in time (a subset of the ≤2% above).
- **On-disk `chunkUsageCounts` cache + least-shared-first ordering (GN's full design): NOT
  recommended.** The measurement doesn't support the complexity, and an on-disk chunk cache
  interacts badly with the disk-guard (extra transient bytes) and cancel/resume correctness.
- **Least-shared-chunk-first ordering** only helps a dedup cache warm up; without the cache it has
  no value, and P1b's largest-first ordering is the better dedup-independent choice. Do **not**
  adopt least-shared-first.

---

## Files we'd touch

**P1 (recommended now):**
- `app/src/main/java/com/winlator/star/store/GogDownloadManager.java` — `runGen2` only:
  `resolveDownloadThreads(ctx)` for the pool size (`:417`); sort `files` by `totalSize` desc before
  the submit loop (`:419`); fix stale thread-count log/comments (`:401`/`:415`/`:476`); optional
  bytes-based progress. A tiny `resolveDownloadThreads` helper + `bh_gog_prefs` `gog_dl_threads`.
- (Optional) the GOG/download settings UI for the thread knob.

**P2 (structural, additive):**
- `GogDownloadManager.java` — add `offset` to `ChunkRef` (`:1754`) + prefix-sum in
  `parseDepotManifest` (`:1182`); add `assembleDepotFileParallel` next to `assembleDepotFile`;
  route by chunk-count in `runGen2`; shared `CompletionService` over one bounded pool.
- **No change** to `assembleDepotFile` (`:1249`), `fetchChunkVerified` (`:1602`), the disk-guard
  (`:388`), or the DLC/redist callers.

**P3 (only if ever justified):** `assembleDepotFileParallel` gets an in-flight `compressedMd5`
coalesce map. No new files.

---

## Keystone risks

1. **Regressing the proven path (the #1 risk).** The download is device-proven (ELDERBORN, XCOM 2
   base-filter) and the MD5 work is landed. Mitigation: P1 is scheduling-only (no assembler
   change); P2 **adds** `assembleDepotFileParallel` and leaves `assembleDepotFile` — and therefore
   the DLC (`:750`) and redist (`:1320`) paths — byte-for-byte unchanged. Never mutate the shared
   assembler in place.
2. **Offset-write vs sequential correctness.** Positioned writes must go to **non-overlapping**
   ranges (guaranteed by prefix-sum offsets + exact `chunk.size`). A wrong offset or a missing
   chunk leaves a hole → the **existing whole-file MD5 verify catches it** and deletes the tmp. Do
   not relax the whole-file MD5 (`:1280`) — it is the backstop that makes chunk-parallel safe.
3. **Pre-alloc on low-space devices ↔ the disk-guard.** `RandomAccessFile.setLength` creates a
   file of `totalSize`; on ext4/f2fs it's typically **sparse** (no real block reservation), so it
   neither helps fragmentation much nor over-commits — but if the FS *does* allocate, pre-allocating
   **must be per-file at task start**, never all files up front, or we'd transiently reserve the
   whole install size on top of the disk-guard's headroom. The guard (`:388`, planned decompressed
   Σ vs usable) already gates the total; keep pre-alloc scoped to the `.bhtmp` of files actively
   being assembled so transient reservation ≈ (N threads × per-file size), not the whole install.
4. **Cancel safety.** Chunk-parallel `.bhtmp` has holes mid-flight; it must **never** be renamed
   until all chunks are written **and** whole-file MD5 passes (same rule as `:1272`–`1289`). On
   cancel, delete the `.bhtmp` (holes and all) — no corrupt file can survive because rename is
   gated on MD5.
5. **Thread over-subscription.** Chunk-level tasks explode the work-item count (XCOM: 37k chunks).
   Must feed **one** bounded `N`-thread pool via a shared queue/`CompletionService`, not a
   per-file nested pool. Sockets/inflate memory scale with `N`, not with the number of chunks.
6. **Progress accounting.** Per-file count-based % (`:429`/`:449`) gets lumpy with big files and
   with chunk-parallelism. Cosmetic, but switching to bytes-based progress makes the bar honest —
   fold into P1b.
7. **CDN politeness / rate.** Higher thread counts + chunk fan-out increase concurrent requests to
   `gog-cdn-fastly.gog.com`. Keep the clamp (P1a) conservative and reuse the existing secure-link
   refresh/backoff (`:1571`/`:1560`) unchanged so 429/expiry handling is inherited.
8. **DLC/redist untouched-ness must be verified.** `assembleDepotFile` is called by DLC and redist;
   a regression there breaks gap#3/#5. The whole design keeps that method frozen — but the device
   test must re-run a DLC install and a redist install to confirm no behavioural drift.

---

## Open questions

- **Thread heuristic ceiling** — is 16 too aggressive for our target devices' I/O, or should the
  auto-clamp be `[6, 12]`? Needs a quick device sweep on Wi-Fi vs LTE.
- **Big-file threshold K** — at what `chunks.size()` does `assembleDepotFileParallel` beat the
  sequential path (context-switch/positioned-write overhead vs saturation gain)? Probe on XCOM
  (35k tiny files → sequential wins) vs a few-big-archive title (parallel wins).
- **Bytes-based progress** — worth wiring the byte counters through both assemblers in P1, or defer
  to P2 when chunk-parallel makes it necessary?
- **In-flight coalesce (P3)** — is even the concurrent-overlap subset of the ≤2% dup bytes worth a
  `ConcurrentHashMap` fetch-join, or do we close dedup out entirely as "measured not worth it"?
- **fallocate vs setLength** — do we want a real `posix_fallocate`-style reservation (fail fast on
  ENOSPC) instead of sparse `setLength`, given the disk-guard already checks up front? Probably no
  — keep `setLength`, lean on the guard.

---

## Task checklist

**P1 (ship first):**
- [ ] `resolveDownloadThreads(ctx)` helper + `bh_gog_prefs` `gog_dl_threads` (0=auto,
      clamp `[6,16]` of `cores*2`); use it at `:417`.
- [ ] Sort `files` by `df.totalSize` desc before the submit loop (`:419`).
- [ ] Fix stale "6/8 threads" comment + log strings (`:401`/`:415`/`:476`) to the resolved count.
- [ ] (Optional) bytes-based progress in the file task (`:449`).
- [ ] Self-review: `assembleDepotFile`, `fetchChunkVerified`, disk-guard, DLC/redist paths
      **unchanged**.

**P2 (structural, gated):**
- [ ] Add `long offset` to `ChunkRef` (`:1754`) + prefix-sum in `parseDepotManifest` (`:1182`).
- [ ] New `assembleDepotFileParallel` (RAF `setLength` pre-alloc + positioned chunk writes +
      **same** whole-file size+MD5 + atomic rename). Do **not** modify `assembleDepotFile`.
- [ ] Route by chunk-count in `runGen2`; single bounded pool via shared `CompletionService`.
- [ ] Per-file pre-alloc at task start only (disk-guard interplay, risk #3).
- [ ] (Optional) bounded one-shot re-emit of a file's failed chunks before `anyFailed`.

**P3 (only if justified — data says skip the cache):**
- [ ] In-flight `compressedMd5` fetch-join coalesce inside `assembleDepotFileParallel`.
- [ ] Explicitly do **NOT** build the on-disk `chunkUsageCounts` cache or least-shared-first
      ordering (measured 0.02–2.13% payoff).

## Build / verify (repo rules — NEVER build locally)
- [ ] Commit as The412Banner; push `feat/gog-dl-perf`.
- [ ] CI `build-artifacts.yml` on the branch; verify run headSha == pushed SHA; 3 flavors green.
- [ ] Stage the `pubg` artifact to `/sdcard/Download/` (cp only) for device test.
- [ ] Device test: **XCOM 2** (35k files → thread-count + largest-first win) and a **few-big-archive
      title** (chunk-parallel win) for P2; time both vs the current build. Re-run an **ELDERBORN
      redist install** and a **DLC install** to prove `assembleDepotFile` reuse is unregressed.
      Read the GOG debug buffer for thread count, ordering, and per-file assembler route.
- [ ] No versionCode bump (feature build, not a release cut).

## Notes
- gen2-only. The gen1/standalone-installer path (`:204`, `newFixedThreadPool(8)` at `:889`) is out
  of scope for the chunk-parallel work; the P1a thread knob may apply there too if trivially safe.
- Keep everything fail-soft and MD5-gated: no perf change may ever let an unverified byte reach the
  final file. The whole-file MD5 + atomic rename is the invariant that must survive every slice.
