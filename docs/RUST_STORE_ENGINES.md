# Rust store download engines (Epic / GOG / Amazon) — shared core

Companion to `docs/STEAM_RUST_ENGINE_PLAN.md` (the Steam engine) and the per-store parity docs
`docs/RUST_EPIC_PARITY.md`, `docs/RUST_GOG_PARITY.md`, `docs/RUST_AMAZON_PARITY.md`.

## What it is

The Steam depot writer's B2b fetch layer (`depot_writer.rs`: adaptive window, per-host-capped
speed-ranked scheduler, in-flight byte budget, retry + host rotation, one current-thread tokio
runtime feeding a sync process pool) generalised into a store-agnostic module,
`app/src/main/cpp/bl-steam-client/rust/src/fetch_core.rs`, inside the existing `libblsteam.so`
crate. Each store gets a thin adapter under `src/store_dl/` that maps its own manifest to
`FetchItem`s and does inflate + hash + write in a `FetchSink`.

Rule of the exercise: the Rust engine replaces ONLY the byte-fetch inner loop of the store's Java
manager. Manifest download, plan (install tags, languages, DLC, dependencies), `DownloadRegistry`
rows, notifications, cancel/pause, resume/skip decisions and every post-install step stay on the
Java code paths, byte-identical with the flag OFF. The Steam engine is untouched: `fetch_core`
COPIES the window/scheduler types from `depot_writer.rs` (the tunables — `PER_HOST_CAP`,
`BOOTSTRAP_WINDOW`, `WINDOW_*`, `MAX_CHUNK_ATTEMPTS`, `retry_backoff_millis`,
`inflight_budget_bytes`, `budget_admits` — are imported from there, so both engines share one set
of numbers).

## Contract (`fetch_core.rs`)

```rust
pub struct FetchItem {
    pub id: u64,                    // adapter-defined (index into the adapter's own table)
    pub urls: Vec<String>,          // len == hosts.len() → urls[host_idx]; len == 1 → same url on every host
    pub reserve: u64,               // expected raw (wire) bytes for the in-flight byte budget; 0 → nominal 1 MiB
    pub range: Option<(u64, u64)>,  // inclusive HTTP Range; None = whole body
}
pub struct FetchOptions {
    pub max_workers: usize,         // window ceiling (tier); clamped to hosts × per_host_cap and 256
    pub per_host_cap: usize,        // 6 unless the store needs otherwise
    pub timeout: std::time::Duration,
    pub headers: Vec<(String, String)>, // set on EVERY request builder (override client defaults, e.g. User-Agent)
    pub ca_bundle_path: String,     // "" = platform roots
    pub process_workers: usize,     // sync process-pool threads
    pub label: String,              // log prefix, e.g. "epic app=Fortnite"
    pub stream: bool,               // false = whole-body → process(); true = pieces → on_chunk()/on_finish()
}
impl Default for FetchOptions { … }   // 8 / 6 / 60 s / no headers / "" / 2 / "" / false
pub enum SinkError { Retry(String), Fatal(String) }
pub trait FetchSink: Send + Sync {
    /// Whole-body mode. Returns bytes to credit to progress.
    fn process(&self, item: &FetchItem, body: Vec<u8>) -> Result<u64, SinkError>;
    /// Stream mode: in-order piece; offset == 0 marks the (re)start of an attempt → truncate.
    /// Each accepted piece credits data.len(). Default impl = Fatal("streaming not supported").
    fn on_chunk(&self, item: &FetchItem, offset: u64, data: &[u8]) -> Result<(), SinkError> { … }
    /// Stream mode: body ended after total_len bytes; finalize. Returns EXTRA bytes to credit (0 = none).
    fn on_finish(&self, item: &FetchItem, total_len: u64) -> Result<u64, SinkError> { … }
}
pub struct FetchOutcome { pub bytes_credited: u64, pub error: Option<String>, pub cancelled: bool, pub items_ok: u64 }
pub fn run_fetch(
    items: Vec<FetchItem>,
    hosts: &[String],
    opts: &FetchOptions,
    sink: &dyn FetchSink,
    cancel: &std::sync::atomic::AtomicBool,
    progress: &(dyn Fn(u64 /*bytes_credited so far*/, u64 /*items_ok so far*/) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> FetchOutcome;
```

Semantics:

- `run_fetch` is BLOCKING: it spawns its own current-thread tokio runtime (fetch driver) plus
  `process_workers` std threads (process pool) and returns when every item is processed, `cancel`
  is observed, or the first fatal error is recorded.
- A request is `GET url` with `opts.headers` applied on the request builder (so they override the
  client-level defaults — Amazon's `User-Agent: nile/0.1 Amazon` beats the Steam UA, GOG's
  `Authorization: Bearer …` rides along), plus `Range: bytes=a-b` when `item.range` is set.
  Accepted statuses: 200, and 206 when a range was requested. A range body must be exactly
  `b-a+1` bytes; otherwise the body must match `Content-Length` when the server sent one.
  Failures are classified like the Steam client (429 → rate-limited, 5xx → server fault, timeout,
  connect, other) and drive the window/scheduler the same way.
- `FetchSink::process` runs on a pool thread with the raw wire body. `Ok(n)` credits `n` bytes to
  progress (adapter's choice — typically decompressed bytes). `Err(SinkError::Retry(_))` counts as
  a failed attempt for that item (host cooled + demoted, exponential back-off, re-dispatch on a
  different host). `Err(SinkError::Fatal(_))` aborts the whole run.
- Each item gets at most 5 attempts (`MAX_ITEM_ATTEMPTS == MAX_CHUNK_ATTEMPTS`). After that the
  run fails with `"<label>: item <id> failed after 5 attempts: <msg>"`.
- `cancel` is polled every driver iteration and by the pool; on cancel in-flight requests are
  dropped (aborted), queued bodies are NOT written, and the outcome has `cancelled: true`.
- `progress(bytes_credited, items_ok)` fires from a pool thread after every successful `process`
  (cumulative totals). Throttle at the adapter/JNI level to whatever cadence the Java manager had.
- The adapter is responsible for resume/skip: `items` must already exclude items that are present
  and verified, computed exactly the way the Java manager does.
- `hosts` are distinct host keys (strings). They drive the per-host cap semaphores and the window
  ceiling (`hosts.len() × opts.per_host_cap`, honoured as passed: 1 host × cap 8 = ceiling 8);
  `urls[host_idx]` is the URL to use on that host.
- **Stream mode** (`opts.stream = true`, applies to every item of the run): the driver reads the
  response with `chunk()` and forwards pieces IN ORDER to the item's pool worker (an item's
  pieces always go to the same worker: `idx % process_workers`), which calls `on_chunk`, then
  `on_finish` when the body ends. Memory = pieces buffered-but-not-yet-written: each piece is
  added to the in-flight budget when it arrives and released when the pool consumes it, and a
  stream pauses reading while the budget is full — so 8 concurrent multi-GB streams stay bounded
  by the same budget. `timeout` is the response-headers deadline and then an IDLE deadline per
  piece (not a whole-transfer deadline). A failure mid-stream (network error, or `Retry` from
  `on_chunk`/`on_finish`) retries the WHOLE item on another host: the remaining pieces of the
  rejected attempt are dropped before they reach the sink, and the next attempt starts again at
  `offset == 0` (the sink truncates its `.tmp`). `progress` fires after every written piece and
  after `on_finish`; `bytes_credited` counts pieces as written (pieces of an attempt that later
  failed stay counted) plus `on_finish` extras.

Also on the core: `md5_small.rs` (`md5(&[u8]) -> [u8; 16]`, streaming `Md5 { update, finalize }`,
`md5_hex`, RFC 1321 vectors as tests) for GOG, and `store_dl/mod.rs` declaring
`pub mod amazon; pub mod epic; pub mod gog;`.

## How a store adapter plugs in

1. `src/store_dl/<store>.rs` (+ `src/store_dl/<store>/*.rs` as needed): parse the manifest the
   Java manager already downloaded, build the plan the same way, compute the resume/skip set the
   same way, emit `Vec<FetchItem>` (one per chunk / file / range), and implement `FetchSink` with
   one handle per output file + positioned writes in the Java layout.
2. JNI exports live inside the store module (`#[no_mangle] pub extern "system" fn
   Java_com_winlator_star_store_blsteam_Bl<Store>Download_native…`). Follow `jni.rs`'s pattern for
   `nativeDownloadApp`: `jlong` handle, listener `GlobalRef`, callbacks from the worker thread via
   an attached `JavaVM`, cancel = an `AtomicBool` flipped by `nativeCancel`. CI needs nothing new —
   the crate is built once and the `.so` copied into jniLibs.
3. Kotlin facade `blsteam/Bl<Store>Download.kt`; the switch inside the Java manager at the point
   where its fetch loop begins:

   ```java
   if (BlStoreEngineFlag.isEpicEnabled(ctx)) { /* Rust: plan → BlEpicDownload.run(...) with the SAME progress/cancel/error handlers */ }
   else { /* existing Java loop, byte-identical */ }
   ```

## Flags

`com.winlator.star.store.blsteam.BlStoreEngineFlag` (default SharedPreferences, DEFAULT = true):

| Key | Accessors |
|---|---|
| `use_rust_epic_engine` | `isEpicEnabled(ctx)` / `setEpicEnabled(ctx, on)` |
| `use_rust_gog_engine` | `isGogEnabled(ctx)` / `setGogEnabled(ctx, on)` |
| `use_rust_amazon_engine` | `isAmazonEnabled(ctx)` / `setAmazonEnabled(ctx, on)` |

Surfaced in Log Manager next to "Native Steam engine" as "Rust engine: Epic downloads" / "…GOG…" /
"…Amazon…". Unlike the Steam flag they are read at DOWNLOAD START, so a flip applies to the next
download of that store without a restart.

## Log line grammar (morning A/B — one grep per store)

Every line goes through the `log` callback (JNI → `android.util.Log` tag `BL_<STORE>_DL` + the
manager's debug file). The adapter prints `engine=rust` first; the core prints:

```
fetch-start label=<label> items=<n> bytes_reserved=<b> hosts=<h> ceiling=<max> budget=<MiB>MiB tier_max=<t> per_host_cap=<c> distinct_hosts=<d> window=<bootstrap> mode=body|stream
fetch-window label=<label> window=<w> (min=<m> max=<M>) in_flight=<i> last=<x>MB/s ewma=<x>MB/s best=<x>MB/s reason=<code> cooldown=<ms>ms err_rate=<p>% phase=slow-start|steady rtt=<ms>ms budget_stalls=<n> host_stalls=<n>
throughput label=<label> overall=<x>MB/s total=<x>MB elapsed=<s>s used=<x>/<y> servers: [host x.xMB/s xMB] …      (every 5 s)
fetch-end label=<label> items_ok=<n> bytes=<wire> credited=<sink> elapsed_ms=<ms> avg_mbps=<x> peak_mbps=<x> result=ok|cancelled|error [error=<msg>]
```

`fetch-window` carries the same fields as the Steam engine's `fetch-window depot=…` line
(`label=` replaces `depot=`), so the two engines' ramps are directly comparable. `MB/s` is MiB/s,
as in the Steam lines. `peak_mbps` is the highest 5-second throughput sample (or the average when
the run was shorter than one sample). Reason codes: `start`, `grow:slow-start`,
`grow:throughput-up`, `grow:probe`, `hold:plateau`, `hold:cooldown`, `hold:ceiling`, `hold:errors`,
`hold:throughput-down`, `shrink:429`, `shrink:timeout`, `shrink:reset`, `shrink:5xx`,
`shrink:err-rate`, `shrink:err-burst`.

## Tests

`cargo test --locked` (host, informational in CI) covers the window/budget arithmetic, per-host URL
selection, range validation, scheduler ranking/cooldown, window shrink rules, the MD5 vectors, and
an end-to-end `run_fetch` against a local `TcpListener` HTTP stub (plain bodies, a 206 range, a
503-then-200 host, a sink `Retry`, a default header echoed back, give-up after 5 attempts, and
cancel) plus a stream-mode run (300 KB body in ordered pieces, a range, a mid-stream `Retry`, a
per-request `User-Agent` override echoed back, per_host_cap 8 honoured).
