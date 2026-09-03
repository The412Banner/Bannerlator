use crate::cdn_client::{CdnClient, CdnConnection};
use crate::content_manifest::{ChunkData, ContentManifest};
use crate::depot_chunk::process_depot_chunk;
use crate::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use std::fs::{self, File, OpenOptions};
use std::path::{Component, Path};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::channel;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const DEPOT_FILE_FLAG_EXECUTABLE: u32 = 32;
pub const DEPOT_FILE_FLAG_DIRECTORY: u32 = 64;
pub const DEPOT_FILE_FLAG_SYMLINK: u32 = 512;
pub const MAX_CHUNK_ATTEMPTS: u32 = 5;
pub const SLOW_CHUNK_ROTATE_THRESHOLD_SECS: u64 = 8;
pub const SLOW_CHUNK_ROTATE_CONSECUTIVE_LIMIT: u32 = 3;

/// Byte budget for the *compressed* chunk bytes buffered in the fetch→process channel.
///
/// The decouple channel is bounded by BYTES, not chunk count: a count cap makes in-flight memory
/// swing wildly with chunk size (a 4-chunk cap is ~4 MB for 1 MB chunks but ~120 MB for 30 MB
/// chunks). Budgeting bytes keeps the heap fixed regardless of game size (the #408 OOM class) while
/// giving the fetch pool bandwidth-delay-product headroom so the socket stays busy while process
/// workers decompress+write. Raw Steam chunks are ~1 MB, so ~24 MB ≈ ~24 chunks in flight.
///
/// Budget only the RAW bytes actually queued (incremented on ENQUEUE, decremented on WRITE-COMPLETE);
/// the decoded expansion is transient inside a process worker and never accumulates. See
/// [`budget_admits`] for the single-chunk deadlock guard.
pub const FETCH_INFLIGHT_BUDGET_BYTES: u64 = 24 * 1024 * 1024;

/// How often the parallel writer emits a per-server throughput line to the debug log.
const BANDWIDTH_LOG_INTERVAL_MS: u64 = 5_000;

/// Slack below which the free-space guard will not fail (guards against small statvfs imprecision).
const FREE_SPACE_MARGIN_BYTES: u64 = 64 * 1024 * 1024;

/// Sink for engine-side download diagnostics (throughput lines, fallocate fallback notice). Wired by
/// the JNI layer to `android_log("BL_STEAM_DL", …)`; `None` in tests. Must be `Sync` — it is called
/// from the fetch/process pools and the reporter thread.
pub type DepotLogCallback<'a> = &'a (dyn Fn(&str) + Sync);

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotWriteResult {
    pub files_written: u64,
    pub bytes_written: u64,
    pub resume_trust_safe: bool,
    pub error: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DepotFileAction {
    Directory { path: String },
    Symlink { path: String, target: String },
    Regular { path: String, size: u64, mode: u32 },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ChunkWriteJob {
    pub file_idx: u32,
    pub chunk_idx: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DepotWritePlan {
    pub total_bytes: u64,
    pub files_written: u64,
    pub actions: Vec<DepotFileAction>,
    pub chunk_jobs: Vec<ChunkWriteJob>,
    pub worker_count: u32,
}

pub type DepotChunkProgressCallback<'a> = &'a (dyn Fn(u64, u64, bool) + Sync);

#[derive(Clone, Copy)]
pub struct DepotWriteOptions<'a> {
    pub cdn_auth_token: &'a str,
    pub timeout: Duration,
    /// Fetch-pool size (network parallelism) = the tier's `maxDownloads`.
    pub max_workers: u32,
    /// Process-pool size (decrypt+decompress+write parallelism) = the tier's `maxDecompress`.
    /// Defaults to [`Self::max_workers`]'s default so existing callers/tests keep their behaviour.
    pub max_process_workers: u32,
    pub cancel: Option<&'a AtomicBool>,
    pub on_progress: Option<DepotChunkProgressCallback<'a>>,
    /// Diagnostics sink (throughput + fallocate fallback). `None` = silent (tests).
    pub log: Option<DepotLogCallback<'a>>,
}

impl Default for DepotWriteOptions<'_> {
    fn default() -> Self {
        Self {
            cdn_auth_token: "",
            timeout: CdnClient::default_timeout(),
            max_workers: 8,
            max_process_workers: 8,
            cancel: None,
            on_progress: None,
            log: None,
        }
    }
}

impl DepotWriteResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty()
    }

    pub fn success(files_written: u64, bytes_written: u64) -> Self {
        Self {
            files_written,
            bytes_written,
            resume_trust_safe: true,
            error: String::new(),
        }
    }

    pub fn fail(error: impl Into<String>, resume_trust_safe: bool) -> Self {
        Self {
            error: error.into(),
            resume_trust_safe,
            ..Default::default()
        }
    }
}

pub fn retry_backoff_millis(attempt: u32) -> u64 {
    if attempt == 0 {
        return 0;
    }
    (300u64 << (attempt - 1)).min(4000)
}

pub fn chunk_attempt_server_indices(
    start_server_index: usize,
    server_count: usize,
    attempts: u32,
) -> Vec<usize> {
    if server_count == 0 || attempts == 0 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(attempts as usize);
    let mut server_index = start_server_index % server_count;
    for attempt in 0..attempts {
        if attempt > 0 && server_count > 1 {
            server_index = (server_index + 1) % server_count;
        }
        out.push(server_index);
    }
    out
}

pub fn should_rotate_after_slow_chunks(consecutive_slow_chunks: u32, server_count: usize) -> bool {
    server_count > 1 && consecutive_slow_chunks >= SLOW_CHUNK_ROTATE_CONSECUTIVE_LIMIT
}

/// Byte-budget admission test for the fetch→process channel. Always admits when nothing is in
/// flight (`in_flight == 0`) so a single chunk LARGER than the whole budget can never deadlock — it
/// simply waits until the channel drains, then goes through alone.
#[inline]
pub fn budget_admits(in_flight: u64, raw_len: u64, budget: u64) -> bool {
    in_flight == 0 || in_flight.saturating_add(raw_len) <= budget
}

pub fn depot_adler_hash(data: &[u8]) -> u32 {
    const BLOCK: usize = 5552;
    let mut a = 0u32;
    let mut b = 0u32;
    for chunk in data.chunks(BLOCK) {
        for byte in chunk {
            a += *byte as u32;
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    a | (b << 16)
}

pub fn path_is_safe(rel: &str) -> bool {
    if rel.is_empty() || rel.starts_with('/') || rel.starts_with('\\') {
        return false;
    }
    let path = Path::new(rel);
    if path.is_absolute() {
        return false;
    }
    path.components().all(|component| {
        matches!(component, Component::Normal(_)) || matches!(component, Component::CurDir)
    }) && !path
        .components()
        .any(|component| matches!(component, Component::ParentDir | Component::Prefix(_)))
}

pub fn clamp_worker_count(max_workers: u32, outstanding_chunks: usize) -> u32 {
    if outstanding_chunks == 0 {
        return 0;
    }
    let requested = if max_workers == 0 { 1 } else { max_workers };
    requested.min(64).min(outstanding_chunks as u32)
}

pub fn plan_depot_write(
    manifest: &ContentManifest,
    depot_key: &[u8],
    server_count: usize,
    target_dir: &str,
    max_workers: u32,
) -> Result<DepotWritePlan, DepotWriteResult> {
    if manifest.metadata.filenames_encrypted {
        return Err(DepotWriteResult::fail(
            "write_depot: manifest filenames are still encrypted",
            false,
        ));
    }
    if depot_key.len() != 32 {
        return Err(DepotWriteResult::fail(
            "write_depot: bad depot key length",
            false,
        ));
    }
    if server_count == 0 {
        return Err(DepotWriteResult::fail("write_depot: no CDN servers", false));
    }

    let mut plan = DepotWritePlan {
        total_bytes: manifest.files.iter().map(|file| file.size).sum(),
        ..Default::default()
    };

    for (file_idx, file) in manifest.files.iter().enumerate() {
        if !path_is_safe(&file.filename) {
            return Err(DepotWriteResult::fail(
                format!("write_depot: unsafe path '{}'", file.filename),
                false,
            ));
        }
        let path = join_target_path(target_dir, &file.filename);
        if !file.linktarget.is_empty() {
            plan.actions.push(DepotFileAction::Symlink {
                path,
                target: file.linktarget.clone(),
            });
            plan.files_written += 1;
            continue;
        }
        if (file.flags & DEPOT_FILE_FLAG_DIRECTORY) != 0 {
            plan.actions.push(DepotFileAction::Directory { path });
            continue;
        }
        let mode = if (file.flags & DEPOT_FILE_FLAG_EXECUTABLE) != 0 {
            0o755
        } else {
            0o644
        };
        plan.actions.push(DepotFileAction::Regular {
            path,
            size: file.size,
            mode,
        });
        plan.files_written += 1;
        for chunk_idx in 0..file.chunks.len() {
            plan.chunk_jobs.push(ChunkWriteJob {
                file_idx: file_idx as u32,
                chunk_idx: chunk_idx as u32,
            });
        }
    }
    plan.worker_count = clamp_worker_count(max_workers, plan.chunk_jobs.len());
    Ok(plan)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Per-server throughput measurement (atomic counters only — never a mutex on the fetch hot path).
// ─────────────────────────────────────────────────────────────────────────────────────────────

/// Lightweight per-CDN-server byte/duration accounting. Fetch workers `record()` the compressed
/// bytes served by the winning server (a lock-free atomic add); the reporter thread reads snapshots
/// and emits `overall MB/s + per-server MB/s` to the debug log. Feeds the tester clean before/after
/// numbers per lever and future adaptive tuning.
pub struct BandwidthMeter {
    start: Instant,
    total_bytes: AtomicU64,
    per_server_bytes: Vec<AtomicU64>,
    hosts: Vec<String>,
}

impl BandwidthMeter {
    pub fn new(servers: &[CContentServerDirectoryServerInfo]) -> Self {
        Self {
            start: Instant::now(),
            total_bytes: AtomicU64::new(0),
            per_server_bytes: servers.iter().map(|_| AtomicU64::new(0)).collect(),
            hosts: servers
                .iter()
                .map(|s| {
                    if s.vhost.is_empty() {
                        s.host.clone()
                    } else {
                        s.vhost.clone()
                    }
                })
                .collect(),
        }
    }

    #[inline]
    pub fn record(&self, server_idx: usize, bytes: u64) {
        self.total_bytes.fetch_add(bytes, Ordering::Relaxed);
        if let Some(counter) = self.per_server_bytes.get(server_idx) {
            counter.fetch_add(bytes, Ordering::Relaxed);
        }
    }

    /// A one-line snapshot for the debug log: overall MB/s + the busiest servers' MB/s.
    pub fn summary_line(&self, depot_id: u32) -> String {
        let secs = self.start.elapsed().as_secs_f64().max(0.001);
        let total = self.total_bytes.load(Ordering::Relaxed);
        let mb = |b: u64| (b as f64) / (1024.0 * 1024.0);
        let overall_mbps = mb(total) / secs;

        let mut per: Vec<(usize, u64)> = self
            .per_server_bytes
            .iter()
            .enumerate()
            .map(|(i, c)| (i, c.load(Ordering::Relaxed)))
            .filter(|(_, b)| *b > 0)
            .collect();
        per.sort_by(|a, b| b.1.cmp(&a.1));

        let mut servers = String::new();
        for (i, bytes) in per.iter().take(4) {
            let host = self.hosts.get(*i).map(String::as_str).unwrap_or("?");
            servers.push_str(&format!(
                " [{host} {:.1}MB/s {:.0}MB]",
                mb(*bytes) / secs,
                mb(*bytes)
            ));
        }
        format!(
            "throughput depot={depot_id} overall={overall_mbps:.2}MB/s total={:.0}MB elapsed={secs:.0}s servers:{servers}",
            mb(total)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Open-file table: one persistent handle per file + positioned (pwrite) writes.
// ─────────────────────────────────────────────────────────────────────────────────────────────

struct SlotState {
    /// `Some` while the file is open; `None` for non-regular files and after finalize (fd closed).
    handle: Option<Arc<File>>,
    opened: bool,
}

struct FileSlot {
    state: Mutex<SlotState>,
    /// Chunk jobs for this file not yet completed (verified-skip OR written). Whoever decrements it
    /// to zero owns the exactly-once finalize (set_len + fsync + close).
    remaining: AtomicUsize,
    size: u64,
    /// On-disk length before this pass began (resume detection + free-space accounting).
    preexisting_len: u64,
    path: String,
    mode: u32,
    is_regular: bool,
}

/// Per-depot open-file table. Handles are opened lazily on first touch and closed as soon as the
/// last chunk of a file lands, so the peak open-fd count is bounded by the worker concurrency (not
/// the file count) — safe for many-thousand-file games. Writes use `pwrite` (positioned, no shared
/// seek cursor) so the work-stealing pool can write different chunks of the SAME file concurrently
/// without racing a cursor.
struct DepotFiles {
    slots: Vec<FileSlot>,
    fallocate_fallback_logged: AtomicBool,
    /// Sum of already-present bytes on disk (clamped to each file's size) — for the free-space guard.
    already_present_bytes: u64,
}

impl DepotFiles {
    fn prepare(manifest: &ContentManifest, target_dir: &str) -> Self {
        let mut slots = Vec::with_capacity(manifest.files.len());
        let mut already_present = 0u64;
        for file in &manifest.files {
            let is_regular =
                file.linktarget.is_empty() && (file.flags & DEPOT_FILE_FLAG_DIRECTORY) == 0;
            let path = join_target_path(target_dir, &file.filename);
            let preexisting = if is_regular {
                fs::metadata(&path).map(|m| m.len()).unwrap_or(0)
            } else {
                0
            };
            if is_regular {
                already_present += preexisting.min(file.size);
            }
            let mode = if (file.flags & DEPOT_FILE_FLAG_EXECUTABLE) != 0 {
                0o755
            } else {
                0o644
            };
            slots.push(FileSlot {
                state: Mutex::new(SlotState {
                    handle: None,
                    opened: false,
                }),
                remaining: AtomicUsize::new(if is_regular { file.chunks.len() } else { 0 }),
                size: file.size,
                preexisting_len: preexisting,
                path,
                mode,
                is_regular,
            });
        }
        Self {
            slots,
            fallocate_fallback_logged: AtomicBool::new(false),
            already_present_bytes: already_present,
        }
    }

    /// A file needs its on-disk chunks verified only if something is already there (resume/verify).
    /// A fresh file has nothing to verify and re-reading pre-allocated zeros would be wasted IO.
    fn needs_verify(&self, file_idx: usize) -> bool {
        self.slots
            .get(file_idx)
            .map(|s| s.preexisting_len > 0)
            .unwrap_or(false)
    }

    /// Get the shared handle for a file, opening (+ pre-allocating to the exact manifest size) on
    /// first touch. Thread-safe: the first caller opens, the rest clone the `Arc<File>`.
    fn acquire(&self, file_idx: usize, log: Option<DepotLogCallback>) -> Result<Arc<File>, String> {
        let slot = self
            .slots
            .get(file_idx)
            .ok_or_else(|| "write_depot: bad file index".to_string())?;
        let mut st = slot.state.lock().expect("slot state poisoned");
        if !st.opened {
            make_parent_dirs(Path::new(&slot.path))?;
            let file = OpenOptions::new()
                .create(true)
                .write(true)
                .read(true)
                .truncate(false)
                .open(&slot.path)
                .map_err(|err| format!("write_depot: open '{}': {err}", slot.path))?;
            set_file_mode(Path::new(&slot.path), slot.mode)?;
            // Pre-allocate real contiguous blocks to the EXACT manifest size — unless the file is
            // already at/over that size (resume), where we must not re-allocate or shrink here.
            if slot.size > 0 && slot.preexisting_len < slot.size {
                preallocate_file(&file, slot.size, &self.fallocate_fallback_logged, log)?;
            }
            st.handle = Some(Arc::new(file));
            st.opened = true;
        }
        st.handle
            .as_ref()
            .map(Arc::clone)
            .ok_or_else(|| format!("write_depot: handle already closed for '{}'", slot.path))
    }

    /// Mark one chunk of a file done. The worker that lands the LAST chunk (`remaining` reaches 0)
    /// sets the file to its exact size, fsyncs it, and closes the shared handle — exactly once,
    /// never per-worker/per-chunk.
    fn complete_chunk(&self, file_idx: usize, handle: &Arc<File>) -> Result<(), String> {
        let slot = self
            .slots
            .get(file_idx)
            .ok_or_else(|| "write_depot: bad file index".to_string())?;
        if slot.remaining.fetch_sub(1, Ordering::AcqRel) == 1 {
            handle
                .set_len(slot.size)
                .map_err(|err| format!("write_depot: final truncate '{}': {err}", slot.path))?;
            handle
                .sync_all()
                .map_err(|err| format!("write_depot: final sync '{}': {err}", slot.path))?;
            // Drop the table's strong ref; the fd closes once the caller's `handle` clone drops.
            slot.state.lock().expect("slot state poisoned").handle = None;
        }
        Ok(())
    }

    /// After the pools join: finalize any regular file the counter path missed — a straggler still
    /// holding an open handle (a safety net; on success this is a no-op) and 0-chunk regular files
    /// (which have no chunk jobs to trigger `complete_chunk`).
    fn finalize_remaining(&self) -> Result<(), String> {
        for slot in &self.slots {
            if !slot.is_regular {
                continue;
            }
            let mut st = slot.state.lock().expect("slot state poisoned");
            if let Some(handle) = st.handle.take() {
                handle.set_len(slot.size).map_err(|err| {
                    format!("write_depot: final truncate '{}': {err}", slot.path)
                })?;
                handle
                    .sync_all()
                    .map_err(|err| format!("write_depot: final sync '{}': {err}", slot.path))?;
            } else if !st.opened {
                // 0-chunk regular file (never touched by a worker): ensure it exists at exact size.
                let file = OpenOptions::new()
                    .create(true)
                    .write(true)
                    .truncate(false)
                    .open(&slot.path)
                    .map_err(|err| format!("write_depot: final open '{}': {err}", slot.path))?;
                file.set_len(slot.size).map_err(|err| {
                    format!("write_depot: final truncate '{}': {err}", slot.path)
                })?;
                file.sync_all()
                    .map_err(|err| format!("write_depot: final sync '{}': {err}", slot.path))?;
            }
        }
        Ok(())
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Depot write entry point.
// ─────────────────────────────────────────────────────────────────────────────────────────────

pub fn write_depot_sequential(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    target_dir: &str,
    options: DepotWriteOptions<'_>,
) -> DepotWriteResult {
    let plan = match plan_depot_write(
        manifest,
        depot_key,
        servers.len(),
        target_dir,
        options.max_workers,
    ) {
        Ok(plan) => plan,
        Err(error) => return error,
    };

    let files = DepotFiles::prepare(manifest, target_dir);

    // Free-space guard using the EXACT manifest sizes (ground truth), minus what is already on disk.
    // Conservative: only fail when statvfs succeeds with a sane non-zero figure and the deficit
    // clearly exceeds the margin — a wrong statvfs must never false-fail a valid download.
    let needed = plan.total_bytes.saturating_sub(files.already_present_bytes);
    if let Some(available) = available_space_bytes(target_dir) {
        if available > 0 && available.saturating_add(FREE_SPACE_MARGIN_BYTES) < needed {
            return DepotWriteResult::fail(
                format!(
                    "write_depot: not enough free space (need {} MiB, have {} MiB)",
                    needed / (1024 * 1024),
                    available / (1024 * 1024)
                ),
                false,
            );
        }
    }

    let layout = create_depot_layout(&plan);
    if !layout.ok() {
        return layout;
    }

    let meter = BandwidthMeter::new(servers);
    let depot_id = manifest.metadata.depot_id;

    let result = if plan.worker_count > 1 && plan.chunk_jobs.len() > 1 && !servers.is_empty() {
        write_depot_parallel(
            manifest, depot_key, cdn, servers, &plan, &options, &files, &meter,
        )
    } else {
        write_depot_single(
            manifest, depot_key, cdn, servers, &plan, &options, &files, &meter,
        )
    };

    if let Some(log) = options.log {
        log(&meter.summary_line(depot_id));
    }
    result
}

/// Single-connection path (1 worker or ≤1 chunk). No concurrency, so a plain positioned write is
/// race-free; still uses the held-handle table so it never re-opens per chunk.
#[allow(clippy::too_many_arguments)]
fn write_depot_single(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    plan: &DepotWritePlan,
    options: &DepotWriteOptions<'_>,
    files: &DepotFiles,
    meter: &BandwidthMeter,
) -> DepotWriteResult {
    let total_bytes = plan.total_bytes;
    let mut bytes_written = 0u64;
    let mut conn = cdn.open_connection();
    for (job_index, job) in plan.chunk_jobs.iter().enumerate() {
        if options
            .cancel
            .is_some_and(|cancel| cancel.load(Ordering::Relaxed))
        {
            return DepotWriteResult::fail("cancelled", true);
        }
        let file_idx = job.file_idx as usize;
        let file = match manifest.files.get(file_idx) {
            Some(file) => file,
            None => return DepotWriteResult::fail("bad file index", true),
        };
        let chunk = match file.chunks.get(job.chunk_idx as usize) {
            Some(chunk) => chunk,
            None => return DepotWriteResult::fail("bad chunk index", true),
        };
        let handle = match files.acquire(file_idx, options.log) {
            Ok(handle) => handle,
            Err(error) => return DepotWriteResult::fail(error, true),
        };
        if files.needs_verify(file_idx) && existing_chunk_matches(&handle, chunk) {
            bytes_written += chunk.cb_original as u64;
            if let Some(on_progress) = options.on_progress {
                on_progress(bytes_written, total_bytes, true);
            }
            if let Err(error) = files.complete_chunk(file_idx, &handle) {
                return DepotWriteResult::fail(error, true);
            }
            continue;
        }
        match fetch_process_write_chunk(
            cdn,
            Some(&mut conn),
            servers,
            manifest,
            file_idx,
            job.chunk_idx as usize,
            depot_key,
            &handle,
            options.cdn_auth_token,
            job_index % servers.len(),
            options.timeout,
            Some(meter),
        ) {
            Ok(bytes) => {
                bytes_written += bytes;
                if let Some(on_progress) = options.on_progress {
                    on_progress(bytes_written, total_bytes, false);
                }
                if let Err(error) = files.complete_chunk(file_idx, &handle) {
                    return DepotWriteResult::fail(error, true);
                }
            }
            Err(error) => return DepotWriteResult::fail(error, true),
        }
    }

    if let Err(error) = files.finalize_remaining() {
        return DepotWriteResult::fail(error, true);
    }

    DepotWriteResult {
        files_written: plan.files_written,
        bytes_written,
        resume_trust_safe: true,
        error: String::new(),
    }
}

/// Decoupled two-pool depot writer (JavaSteam parity).
///
/// A **fetch pool** (`max_workers`) work-steals chunk-job indices: a chunk already correct on disk
/// is counted as verifying and never enqueued; anything else is fetched RAW (encrypted) from the CDN
/// with the existing retry/rotation logic and handed to an unbounded channel gated by an in-flight
/// **byte budget** ([`FETCH_INFLIGHT_BUDGET_BYTES`]). A separate **process pool**
/// (`max_process_workers`) drains the channel and does decrypt+decompress+positioned-write, counting
/// bytes on the WRITE. Each file is written through ONE shared handle with `pwrite` (positioned, no
/// shared cursor) so multiple workers can write different chunks of the same file concurrently
/// without corruption; the handle is closed as its last chunk lands.
#[allow(clippy::too_many_arguments)]
fn write_depot_parallel(
    manifest: &ContentManifest,
    depot_key: &[u8],
    cdn: &CdnClient,
    servers: &[CContentServerDirectoryServerInfo],
    plan: &DepotWritePlan,
    options: &DepotWriteOptions<'_>,
    files: &DepotFiles,
    meter: &BandwidthMeter,
) -> DepotWriteResult {
    let total_bytes = plan.total_bytes;
    let bytes_written = AtomicU64::new(0);
    let in_flight = AtomicU64::new(0);
    let error_slot: Mutex<Option<String>> = Mutex::new(None);
    let next_index = AtomicUsize::new(0);
    let reporter_done = AtomicBool::new(false);
    let jobs = &plan.chunk_jobs;

    let fetch_count = (plan.worker_count as usize).max(1).min(jobs.len());
    let proc_count = (options.max_process_workers as usize).max(1).min(jobs.len());
    let budget = FETCH_INFLIGHT_BUDGET_BYTES;
    let cdn_auth_token = options.cdn_auth_token;
    let timeout = options.timeout;
    let cancel = options.cancel;
    let progress = options.on_progress;
    let log = options.log;
    let depot_id = manifest.metadata.depot_id;

    let scope_result = thread::scope(|scope| -> DepotWriteResult {
        let (tx, rx) = channel::<(ChunkWriteJob, Vec<u8>)>();
        // `rx` is created inside the scope, so it is not `'env` and cannot be borrowed by scoped
        // threads — share it as an owned `Arc` clone per process worker (as the pre-B2a code did).
        let rx = Arc::new(Mutex::new(rx));
        // Reference bindings so the `move` workers capture Copy references, not the owned locals.
        let bytes_written = &bytes_written;
        let in_flight = &in_flight;
        let error_slot = &error_slot;
        let next_index = &next_index;
        let reporter_done = &reporter_done;

        // Periodic throughput reporter — atomics only, never on the fetch hot path.
        let reporter = if log.is_some() {
            Some(scope.spawn(move || loop {
                let mut waited = 0u64;
                while waited < BANDWIDTH_LOG_INTERVAL_MS {
                    if reporter_done.load(Ordering::Relaxed) {
                        return;
                    }
                    thread::sleep(Duration::from_millis(100));
                    waited += 100;
                }
                if reporter_done.load(Ordering::Relaxed) {
                    return;
                }
                if let Some(log) = log {
                    log(&meter.summary_line(depot_id));
                }
            }))
        } else {
            None
        };

        // ── Fetch pool: skip already-correct chunks, fetch the rest RAW, hand off downstream. ──
        let mut fetch_handles = Vec::with_capacity(fetch_count);
        for worker_id in 0..fetch_count {
            let tx = tx.clone();
            fetch_handles.push(scope.spawn(move || {
                let mut conn = cdn.open_connection();
                let mut slow_chunks = 0u32;
                let mut worker_server_bias = worker_id % servers.len();
                loop {
                    if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
                        return;
                    }
                    if error_slot.lock().expect("err slot poisoned").is_some() {
                        return;
                    }
                    let idx = next_index.fetch_add(1, Ordering::Relaxed);
                    if idx >= jobs.len() {
                        return;
                    }
                    let job = jobs[idx];
                    let file_idx = job.file_idx as usize;
                    let file = match manifest.files.get(file_idx) {
                        Some(file) => file,
                        None => {
                            record_first_error(error_slot, "bad file index".to_string());
                            return;
                        }
                    };
                    let chunk = match file.chunks.get(job.chunk_idx as usize) {
                        Some(chunk) => chunk,
                        None => {
                            record_first_error(error_slot, "bad chunk index".to_string());
                            return;
                        }
                    };
                    // Resume/verify only: check on-disk content before fetching. Never for a fresh
                    // file (nothing there but pre-allocated zeros).
                    if files.needs_verify(file_idx) {
                        let handle = match files.acquire(file_idx, log) {
                            Ok(handle) => handle,
                            Err(error) => {
                                record_first_error(error_slot, error);
                                return;
                            }
                        };
                        if existing_chunk_matches(&handle, chunk) {
                            let total = bytes_written
                                .fetch_add(chunk.cb_original as u64, Ordering::Relaxed)
                                + chunk.cb_original as u64;
                            if let Some(cb) = progress {
                                cb(total, total_bytes, true);
                            }
                            if let Err(error) = files.complete_chunk(file_idx, &handle) {
                                record_first_error(error_slot, error);
                                return;
                            }
                            continue;
                        }
                    }
                    if should_rotate_after_slow_chunks(slow_chunks, servers.len()) {
                        worker_server_bias = (worker_server_bias + 1) % servers.len();
                        conn = cdn.open_connection();
                        slow_chunks = 0;
                    }
                    let start_server = (idx + worker_server_bias) % servers.len();
                    let started = Instant::now();
                    match fetch_raw_chunk(
                        cdn,
                        Some(&mut conn),
                        servers,
                        manifest,
                        file_idx,
                        job.chunk_idx as usize,
                        cdn_auth_token,
                        start_server,
                        timeout,
                        Some(meter),
                    ) {
                        Ok(raw) => {
                            if started.elapsed()
                                > Duration::from_secs(SLOW_CHUNK_ROTATE_THRESHOLD_SECS)
                                && servers.len() > 1
                            {
                                slow_chunks += 1;
                            } else {
                                slow_chunks = 0;
                            }
                            let raw_len = raw.len() as u64;
                            // Byte-budget backpressure on the RAW compressed bytes queued. Always
                            // admits when nothing is in flight so a chunk bigger than the whole
                            // budget can never deadlock. Bail promptly on cancel/error.
                            loop {
                                if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
                                    return;
                                }
                                if error_slot.lock().expect("err slot poisoned").is_some() {
                                    return;
                                }
                                if budget_admits(in_flight.load(Ordering::Relaxed), raw_len, budget) {
                                    break;
                                }
                                thread::sleep(Duration::from_millis(5));
                            }
                            in_flight.fetch_add(raw_len, Ordering::Relaxed);
                            if tx.send((job, raw)).is_err() {
                                return;
                            }
                        }
                        Err(error) => {
                            record_first_error(error_slot, error);
                            return;
                        }
                    }
                }
            }));
        }
        // Drop the template sender: once every fetch worker exits the channel disconnects, the
        // process pool drains what remains, then its blocked `recv()`s return `Err` and it exits.
        drop(tx);

        // ── Process pool: decrypt + decompress + positioned-write each fetched chunk. ──
        let mut proc_handles = Vec::with_capacity(proc_count);
        for _ in 0..proc_count {
            let rx = Arc::clone(&rx);
            proc_handles.push(scope.spawn(move || {
                loop {
                    if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
                        return;
                    }
                    if error_slot.lock().expect("err slot poisoned").is_some() {
                        return;
                    }
                    // Lock held only across the (fast) dequeue; the decode+write below runs unlocked.
                    let msg = {
                        let guard = rx.lock().expect("rx poisoned");
                        guard.recv()
                    };
                    let (job, raw) = match msg {
                        Ok(item) => item,
                        Err(_) => return,
                    };
                    let raw_len = raw.len() as u64;
                    let file_idx = job.file_idx as usize;
                    let file = match manifest.files.get(file_idx) {
                        Some(file) => file,
                        None => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, "bad file index".to_string());
                            return;
                        }
                    };
                    let chunk = match file.chunks.get(job.chunk_idx as usize) {
                        Some(chunk) => chunk,
                        None => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, "bad chunk index".to_string());
                            return;
                        }
                    };
                    let handle = match files.acquire(file_idx, log) {
                        Ok(handle) => handle,
                        Err(error) => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, error);
                            return;
                        }
                    };
                    match process_and_write_chunk(&handle, chunk, &raw, depot_key) {
                        Ok(bytes) => {
                            // Free the budget on WRITE-COMPLETE.
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            if let Err(error) = files.complete_chunk(file_idx, &handle) {
                                record_first_error(error_slot, error);
                                return;
                            }
                            let total = bytes_written.fetch_add(bytes, Ordering::Relaxed) + bytes;
                            if let Some(cb) = progress {
                                cb(total, total_bytes, false);
                            }
                        }
                        Err(error) => {
                            in_flight.fetch_sub(raw_len, Ordering::Relaxed);
                            record_first_error(error_slot, error);
                            return;
                        }
                    }
                }
            }));
        }

        for handle in fetch_handles {
            let _ = handle.join();
        }
        for handle in proc_handles {
            let _ = handle.join();
        }
        reporter_done.store(true, Ordering::Relaxed);
        if let Some(reporter) = reporter {
            let _ = reporter.join();
        }

        if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
            return DepotWriteResult::fail("cancelled", true);
        }
        if let Some(error) = error_slot.lock().expect("err slot poisoned").take() {
            return DepotWriteResult::fail(error, true);
        }
        DepotWriteResult {
            files_written: plan.files_written,
            bytes_written: bytes_written.load(Ordering::Relaxed),
            resume_trust_safe: true,
            error: String::new(),
        }
    });

    if !scope_result.ok() {
        return scope_result;
    }
    if let Err(error) = files.finalize_remaining() {
        return DepotWriteResult::fail(error, true);
    }
    scope_result
}

pub fn create_depot_layout(plan: &DepotWritePlan) -> DepotWriteResult {
    for action in &plan.actions {
        let result = match action {
            DepotFileAction::Directory { path } => create_directory(path),
            DepotFileAction::Symlink { path, target } => create_symlink(path, target),
            DepotFileAction::Regular { path, mode, .. } => create_regular_file(path, *mode),
        };
        if let Err(error) = result {
            return DepotWriteResult::fail(error, false);
        }
    }
    DepotWriteResult {
        files_written: plan.files_written,
        bytes_written: 0,
        resume_trust_safe: true,
        error: String::new(),
    }
}

/// Positioned write to an already-open file handle. Uses `pwrite` (no shared seek cursor) so
/// concurrent writers to different offsets of the same file never race.
pub fn write_chunk_at(file: &File, offset: u64, data: &[u8]) -> Result<u64, String> {
    pwrite_all_at(file, offset, data)
        .map_err(|err| format!("write_depot: write at offset {offset}: {err}"))?;
    Ok(data.len() as u64)
}

pub fn process_and_write_chunk(
    file: &File,
    chunk: &ChunkData,
    raw_chunk: &[u8],
    depot_key: &[u8],
) -> Result<u64, String> {
    let processed = process_depot_chunk(raw_chunk, depot_key, chunk.crc, chunk.cb_original);
    if !processed.ok() {
        return Err(format!("decode: {}", processed.error));
    }
    write_chunk_at(file, chunk.offset, &processed.data)
}

/// Record the FIRST error seen by either pool; later workers must not clobber it.
fn record_first_error(slot: &Mutex<Option<String>>, err: String) {
    let mut guard = slot.lock().expect("err slot poisoned");
    if guard.is_none() {
        *guard = Some(err);
    }
}

/// Fetch ONE raw (still-encrypted, still-compressed) chunk from the CDN — the network half of
/// [`fetch_process_write_chunk`], split out for the decoupled two-pool writer. Same retry/rotation
/// as the fused path: [`MAX_CHUNK_ATTEMPTS`] tries, server rotation via
/// [`chunk_attempt_server_indices`], [`retry_backoff_millis`] backoff, and a fresh connection on
/// each retry. On success the winning server's byte count is recorded to the bandwidth meter.
#[allow(clippy::too_many_arguments)]
pub fn fetch_raw_chunk(
    cdn: &CdnClient,
    mut conn: Option<&mut CdnConnection>,
    servers: &[CContentServerDirectoryServerInfo],
    manifest: &ContentManifest,
    file_idx: usize,
    chunk_idx: usize,
    cdn_auth_token: &str,
    start_server_index: usize,
    timeout: Duration,
    meter: Option<&BandwidthMeter>,
) -> Result<Vec<u8>, String> {
    if servers.is_empty() {
        return Err("write_depot: no CDN servers".to_string());
    }
    let file = manifest
        .files
        .get(file_idx)
        .ok_or_else(|| "write_depot: bad file index".to_string())?;
    let chunk = file
        .chunks
        .get(chunk_idx)
        .ok_or_else(|| "write_depot: bad chunk index".to_string())?;
    let mut last_error = String::new();
    for (attempt, server_idx) in
        chunk_attempt_server_indices(start_server_index, servers.len(), MAX_CHUNK_ATTEMPTS)
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
            if let Some(connection) = conn.as_deref_mut() {
                *connection = cdn.open_connection();
            }
        }
        let fetched = match conn.as_deref_mut() {
            Some(connection) => cdn.fetch_chunk_with_connection(
                connection,
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
            None => cdn.fetch_chunk(
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
        };
        if !fetched.ok() {
            last_error = fetched.error;
            continue;
        }
        if let Some(meter) = meter {
            meter.record(server_idx, fetched.data.len() as u64);
        }
        return Ok(fetched.data);
    }
    Err(format!(
        "write_depot: chunk for '{}' failed after {} attempts: {}",
        file.filename, MAX_CHUNK_ATTEMPTS, last_error
    ))
}

#[allow(clippy::too_many_arguments)]
pub fn fetch_process_write_chunk(
    cdn: &CdnClient,
    mut conn: Option<&mut CdnConnection>,
    servers: &[CContentServerDirectoryServerInfo],
    manifest: &ContentManifest,
    file_idx: usize,
    chunk_idx: usize,
    depot_key: &[u8],
    file_handle: &File,
    cdn_auth_token: &str,
    start_server_index: usize,
    timeout: Duration,
    meter: Option<&BandwidthMeter>,
) -> Result<u64, String> {
    if servers.is_empty() {
        return Err("write_depot: no CDN servers".to_string());
    }
    let file = manifest
        .files
        .get(file_idx)
        .ok_or_else(|| "write_depot: bad file index".to_string())?;
    let chunk = file
        .chunks
        .get(chunk_idx)
        .ok_or_else(|| "write_depot: bad chunk index".to_string())?;
    let mut last_error = String::new();
    for (attempt, server_idx) in
        chunk_attempt_server_indices(start_server_index, servers.len(), MAX_CHUNK_ATTEMPTS)
            .into_iter()
            .enumerate()
    {
        if attempt > 0 {
            thread::sleep(Duration::from_millis(retry_backoff_millis(attempt as u32)));
            if let Some(connection) = conn.as_deref_mut() {
                *connection = cdn.open_connection();
            }
        }
        let fetched = match conn.as_deref_mut() {
            Some(connection) => cdn.fetch_chunk_with_connection(
                connection,
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
            None => cdn.fetch_chunk(
                &servers[server_idx],
                manifest.metadata.depot_id,
                &chunk.sha,
                cdn_auth_token,
                timeout,
            ),
        };
        if !fetched.ok() {
            last_error = fetched.error;
            continue;
        }
        if let Some(meter) = meter {
            meter.record(server_idx, fetched.data.len() as u64);
        }
        match process_and_write_chunk(file_handle, chunk, &fetched.data, depot_key) {
            Ok(bytes) => return Ok(bytes),
            Err(error) => last_error = error,
        }
    }
    Err(format!(
        "write_depot: chunk for '{}' failed after {} attempts: {}",
        file.filename, MAX_CHUNK_ATTEMPTS, last_error
    ))
}

pub fn existing_chunk_matches(file: &File, chunk: &ChunkData) -> bool {
    if chunk.cb_original == 0 {
        return false;
    }
    let Ok(metadata) = file.metadata() else {
        return false;
    };
    let end = chunk.offset.saturating_add(chunk.cb_original as u64);
    if metadata.len() < end {
        return false;
    }
    let mut buf = vec![0u8; chunk.cb_original as usize];
    if pread_exact_at(file, chunk.offset, &mut buf).is_err() {
        return false;
    }
    depot_adler_hash(&buf) == chunk.crc
}

pub fn sync_file(path: impl AsRef<Path>) -> bool {
    OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .and_then(|file| file.sync_all())
        .is_ok()
}

pub fn finalize_regular_file(path: impl AsRef<Path>, size: u64) -> Result<(), String> {
    let path = path.as_ref();
    let file = OpenOptions::new()
        .write(true)
        .open(path)
        .map_err(|err| format!("write_depot: final open '{}': {err}", path.display()))?;
    file.set_len(size)
        .map_err(|err| format!("write_depot: final truncate '{}': {err}", path.display()))?;
    file.sync_all()
        .map_err(|err| format!("write_depot: final sync '{}': {err}", path.display()))
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Positioned IO + pre-allocation + free-space (platform helpers).
// ─────────────────────────────────────────────────────────────────────────────────────────────

#[cfg(unix)]
fn pwrite_all_at(file: &File, offset: u64, data: &[u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.write_all_at(data, offset)
}

#[cfg(not(unix))]
fn pwrite_all_at(file: &File, offset: u64, data: &[u8]) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    let mut done = 0usize;
    while done < data.len() {
        let n = file.seek_write(&data[done..], offset + done as u64)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "seek_write wrote 0",
            ));
        }
        done += n;
    }
    Ok(())
}

#[cfg(unix)]
fn pread_exact_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.read_exact_at(buf, offset)
}

#[cfg(not(unix))]
fn pread_exact_at(file: &File, offset: u64, buf: &mut [u8]) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    let mut done = 0usize;
    while done < buf.len() {
        let n = file.seek_read(&mut buf[done..], offset + done as u64)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "seek_read reached EOF",
            ));
        }
        done += n;
    }
    Ok(())
}

enum FallocOutcome {
    Ok,
    Unsupported,
    OtherErr,
}

/// Pre-allocate `file` to exactly `size` bytes. Prefers `fallocate` for real contiguous blocks;
/// on FUSE/sdcardfs (`/storage/emulated`, SD) that returns EOPNOTSUPP/ENOSYS, so we branch on the
/// errno and fall back to `set_len` (logging the fallback once per download, not per file). Never
/// hard-fails on the unsupported case.
fn preallocate_file(
    file: &File,
    size: u64,
    fallback_logged: &AtomicBool,
    log: Option<DepotLogCallback>,
) -> Result<(), String> {
    if size == 0 {
        return Ok(());
    }
    match try_fallocate(file, size) {
        FallocOutcome::Ok => Ok(()),
        FallocOutcome::Unsupported => {
            if !fallback_logged.swap(true, Ordering::Relaxed) {
                if let Some(log) = log {
                    log("fallocate unsupported on target filesystem (FUSE/sdcardfs?); using set_len fallback for this download");
                }
            }
            file.set_len(size)
                .map_err(|err| format!("write_depot: set_len fallback failed: {err}"))
        }
        FallocOutcome::OtherErr => {
            // Not the unsupported case (e.g. ENOSPC): still fall back to a sparse set_len so we do
            // not hard-fail here — a genuine out-of-space surfaces on the actual write, and the
            // free-space guard is the real gate.
            file.set_len(size)
                .map_err(|err| format!("write_depot: set_len fallback failed: {err}"))
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn try_fallocate(file: &File, size: u64) -> FallocOutcome {
    use std::os::unix::io::AsRawFd;
    // mode 0 = allocate blocks and extend the file to offset+len WITHOUT zeroing existing bytes,
    // so resumed content in [0, preexisting_len) is preserved and the tail reads as zeros.
    let rc = unsafe { libc::fallocate(file.as_raw_fd(), 0, 0, size as libc::off_t) };
    if rc == 0 {
        return FallocOutcome::Ok;
    }
    match std::io::Error::last_os_error().raw_os_error() {
        Some(errno) if errno == libc::EOPNOTSUPP || errno == libc::ENOSYS => {
            FallocOutcome::Unsupported
        }
        _ => FallocOutcome::OtherErr,
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn try_fallocate(_file: &File, _size: u64) -> FallocOutcome {
    // No fallocate on this platform (host dev on macOS/Windows): take the set_len path.
    FallocOutcome::Unsupported
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn available_space_bytes(path: &str) -> Option<u64> {
    use std::ffi::CString;
    let c_path = CString::new(path).ok()?;
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::statvfs(c_path.as_ptr(), &mut st) };
    if rc != 0 {
        return None;
    }
    // Space available to an unprivileged process = f_bavail * f_frsize.
    Some((st.f_bavail as u64).saturating_mul(st.f_frsize as u64))
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn available_space_bytes(_path: &str) -> Option<u64> {
    None
}

fn join_target_path(target_dir: &str, rel: &str) -> String {
    if target_dir.ends_with('/') || target_dir.ends_with('\\') {
        format!("{target_dir}{rel}")
    } else {
        format!("{target_dir}/{rel}")
    }
}

fn create_directory(path: &str) -> Result<(), String> {
    fs::create_dir_all(path).map_err(|err| format!("write_depot: mkdir '{path}': {err}"))
}

fn create_regular_file(path: &str, mode: u32) -> Result<(), String> {
    let path_ref = Path::new(path);
    make_parent_dirs(path_ref)?;
    OpenOptions::new()
        .create(true)
        .write(true)
        .read(true)
        .truncate(false)
        .open(path_ref)
        .map_err(|err| format!("write_depot: open '{path}': {err}"))?;
    set_file_mode(path_ref, mode)
}

fn create_symlink(path: &str, target: &str) -> Result<(), String> {
    let path_ref = Path::new(path);
    make_parent_dirs(path_ref)?;
    if path_ref.exists() {
        fs::remove_file(path_ref).map_err(|err| format!("write_depot: unlink '{path}': {err}"))?;
    }
    create_platform_symlink(target, path_ref)
        .map_err(|err| format!("write_depot: symlink '{path}': {err}"))
}

fn make_parent_dirs(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)
                .map_err(|err| format!("write_depot: mkdir '{}': {err}", parent.display()))?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn create_platform_symlink(target: &str, path: &Path) -> std::io::Result<()> {
    std::os::unix::fs::symlink(target, path)
}

#[cfg(windows)]
fn create_platform_symlink(target: &str, path: &Path) -> std::io::Result<()> {
    std::os::windows::fs::symlink_file(target, path)
}

#[cfg(unix)]
fn set_file_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    let permissions = fs::Permissions::from_mode(mode);
    fs::set_permissions(path, permissions)
        .map_err(|err| format!("write_depot: chmod '{}': {err}", path.display()))
}

#[cfg(not(unix))]
fn set_file_mode(_path: &Path, _mode: u32) -> Result<(), String> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block, AES_BLOCK_BYTES};
    use std::path::PathBuf;

    #[test]
    fn depot_adler_uses_steam_zero_seed() {
        assert_eq!(depot_adler_hash(b""), 0);
        assert_eq!(depot_adler_hash(b"abc"), 0x024a_0126);
    }

    #[test]
    fn rejects_paths_that_escape_target() {
        assert!(path_is_safe("a/b/file.txt"));
        assert!(path_is_safe("./a/file.txt"));
        assert!(!path_is_safe(""));
        assert!(!path_is_safe("../file.txt"));
        assert!(!path_is_safe("a/../file.txt"));
        assert!(!path_is_safe("/abs/file.txt"));
    }

    #[test]
    fn retry_backoff_matches_cpp_caps() {
        assert_eq!(retry_backoff_millis(1), 300);
        assert_eq!(retry_backoff_millis(2), 600);
        assert_eq!(retry_backoff_millis(5), 4000);
        assert_eq!(MAX_CHUNK_ATTEMPTS, 5);
        assert_eq!(SLOW_CHUNK_ROTATE_THRESHOLD_SECS, 8);
    }

    #[test]
    fn chunk_retry_rotates_across_servers_like_cpp_worker() {
        assert_eq!(chunk_attempt_server_indices(0, 3, 5), [0, 1, 2, 0, 1]);
        assert_eq!(chunk_attempt_server_indices(2, 3, 5), [2, 0, 1, 2, 0]);
        assert_eq!(chunk_attempt_server_indices(0, 1, 5), [0, 0, 0, 0, 0]);
        assert!(chunk_attempt_server_indices(0, 0, 5).is_empty());
        assert!(!should_rotate_after_slow_chunks(2, 3));
        assert!(should_rotate_after_slow_chunks(3, 3));
        assert!(!should_rotate_after_slow_chunks(3, 1));
    }

    #[test]
    fn byte_budget_always_admits_at_least_one_chunk() {
        // Deadlock guard: a chunk larger than the whole budget still admits when nothing is queued.
        assert!(budget_admits(0, 100 * 1024 * 1024, 24 * 1024 * 1024));
        // Normal admits while under budget.
        assert!(budget_admits(0, 1_000_000, 24 * 1024 * 1024));
        assert!(budget_admits(20 * 1024 * 1024, 1_000_000, 24 * 1024 * 1024));
        // Blocks when the addition would exceed budget and something is already queued.
        assert!(!budget_admits(1, 100 * 1024 * 1024, 24 * 1024 * 1024));
        assert!(!budget_admits(24 * 1024 * 1024, 1, 24 * 1024 * 1024));
    }

    #[test]
    fn depot_plan_validates_inputs_and_enumerates_actions() {
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "bin/game".into(),
                    size: 10,
                    flags: DEPOT_FILE_FLAG_EXECUTABLE,
                    chunks: vec![crate::content_manifest::ChunkData::default()],
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "link".into(),
                    linktarget: "bin/game".into(),
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };

        let plan = plan_depot_write(&manifest, &[7u8; 32], 2, "/target", 99).unwrap();
        assert_eq!(plan.total_bytes, 10);
        assert_eq!(plan.files_written, 2);
        assert_eq!(plan.worker_count, 1);
        assert_eq!(
            plan.actions,
            [
                DepotFileAction::Directory {
                    path: "/target/bin".into()
                },
                DepotFileAction::Regular {
                    path: "/target/bin/game".into(),
                    size: 10,
                    mode: 0o755
                },
                DepotFileAction::Symlink {
                    path: "/target/link".into(),
                    target: "bin/game".into()
                }
            ]
        );
        assert_eq!(
            plan.chunk_jobs,
            [ChunkWriteJob {
                file_idx: 1,
                chunk_idx: 0
            }]
        );
    }

    #[test]
    fn depot_plan_rejects_cpp_error_cases() {
        let mut manifest = ContentManifest {
            files: vec![crate::content_manifest::FileMapping {
                filename: "../escape".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: unsafe path '../escape'"
        );
        manifest.files[0].filename = "ok".into();
        manifest.metadata.filenames_encrypted = true;
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: manifest filenames are still encrypted"
        );
        manifest.metadata.filenames_encrypted = false;
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 31], 1, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: bad depot key length"
        );
        assert_eq!(
            plan_depot_write(&manifest, &[0u8; 32], 0, "/target", 8)
                .unwrap_err()
                .error,
            "write_depot: no CDN servers"
        );
    }

    #[test]
    fn worker_clamping_matches_cpp_limits() {
        assert_eq!(clamp_worker_count(0, 10), 1);
        assert_eq!(clamp_worker_count(128, 100), 64);
        assert_eq!(clamp_worker_count(8, 3), 3);
        assert_eq!(clamp_worker_count(8, 0), 0);
    }

    #[test]
    fn creates_layout_and_writes_chunks_at_offsets() {
        let dir = temp_dir("depot_writer_layout");
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "bin".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "bin/game.dat".into(),
                    size: 6,
                    chunks: vec![ChunkData {
                        offset: 2,
                        cb_original: 3,
                        crc: depot_adler_hash(b"abc"),
                        ..Default::default()
                    }],
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };
        let plan = plan_depot_write(&manifest, &[1u8; 32], 1, dir.to_str().unwrap(), 4).unwrap();
        let result = create_depot_layout(&plan);
        assert!(result.ok(), "{}", result.error);
        let path = dir.join("bin/game.dat");
        assert!(path.exists());

        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .unwrap();
        assert_eq!(write_chunk_at(&file, 2, b"abc").unwrap(), 3);
        assert!(existing_chunk_matches(&file, &manifest.files[1].chunks[0]));
        assert!(!existing_chunk_matches(
            &file,
            &ChunkData {
                offset: 2,
                cb_original: 3,
                crc: 1,
                ..Default::default()
            }
        ));
        drop(file);
        assert!(sync_file(&path));
        finalize_regular_file(&path, 6).unwrap();
        assert_eq!(fs::metadata(&path).unwrap().len(), 6);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn concurrent_pwrite_at_different_offsets_is_race_free() {
        // Multiple threads share ONE handle and write different chunks of the SAME file via
        // write_at (pwrite). No shared seek cursor ⇒ no corruption regardless of interleaving.
        let dir = temp_dir("depot_writer_pwrite_concurrency");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("data.bin");
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(&path)
            .unwrap();
        file.set_len(26).unwrap();
        let file = Arc::new(file);
        thread::scope(|scope| {
            for i in 0..26u64 {
                let file = Arc::clone(&file);
                scope.spawn(move || {
                    let byte = [b'a' + i as u8];
                    write_chunk_at(&file, i, &byte).unwrap();
                });
            }
        });
        let mut buf = vec![0u8; 26];
        pread_exact_at(&file, 0, &mut buf).unwrap();
        assert_eq!(&buf, b"abcdefghijklmnopqrstuvwxyz");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn process_and_write_chunk_decrypts_and_materializes_bytes() {
        let dir = temp_dir("depot_writer_process_chunk");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("content.bin");
        let key = [9u8; 32];
        let payload = b"materialized chunk";
        let raw = encrypted_stored_zip_chunk(&key, payload);
        let chunk = ChunkData {
            offset: 4,
            cb_original: payload.len() as u32,
            crc: depot_adler_hash(payload),
            ..Default::default()
        };

        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(&path)
            .unwrap();
        assert_eq!(
            process_and_write_chunk(&file, &chunk, &raw, &key).unwrap(),
            payload.len() as u64
        );
        assert!(existing_chunk_matches(&file, &chunk));
        let mut bytes = vec![0u8; 4 + payload.len()];
        pread_exact_at(&file, 0, &mut bytes).unwrap();
        assert_eq!(&bytes[4..], payload);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn preallocate_then_write_then_finalize_produces_exact_size() {
        let dir = temp_dir("depot_writer_prealloc");
        fs::create_dir_all(&dir).unwrap();
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![crate::content_manifest::FileMapping {
                filename: "data.bin".into(),
                size: 9,
                chunks: vec![ChunkData {
                    offset: 0,
                    cb_original: 3,
                    crc: depot_adler_hash(b"abc"),
                    ..Default::default()
                }],
                ..Default::default()
            }],
            signature: Vec::new(),
        };
        let files = DepotFiles::prepare(&manifest, dir.to_str().unwrap());
        assert_eq!(files.already_present_bytes, 0);
        let handle = files.acquire(0, None).unwrap();
        // Pre-allocated to the full manifest size even though only one 3-byte chunk exists.
        assert_eq!(handle.metadata().unwrap().len(), 9);
        write_chunk_at(&handle, 0, b"abc").unwrap();
        files.complete_chunk(0, &handle).unwrap();
        // complete_chunk trimmed the padding via set_len to the exact size.
        assert_eq!(fs::metadata(dir.join("data.bin")).unwrap().len(), 9);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn sequential_write_handles_layout_only_manifest() {
        let dir = temp_dir("depot_writer_sequential_layout");
        let manifest = ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![
                crate::content_manifest::FileMapping {
                    filename: "empty.bin".into(),
                    size: 5,
                    ..Default::default()
                },
                crate::content_manifest::FileMapping {
                    filename: "folder".into(),
                    flags: DEPOT_FILE_FLAG_DIRECTORY,
                    ..Default::default()
                },
            ],
            signature: Vec::new(),
        };
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions::default(),
        );
        assert!(result.ok(), "{}", result.error);
        assert_eq!(result.files_written, 1);
        assert_eq!(result.bytes_written, 0);
        assert_eq!(fs::metadata(dir.join("empty.bin")).unwrap().len(), 5);
        assert!(dir.join("folder").is_dir());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn default_options_carry_a_process_pool() {
        // The process-pool cap defaults to the fetch-pool cap so pre-2-pool callers/tests behave.
        assert_eq!(DepotWriteOptions::default().max_process_workers, 8);
        assert_eq!(DepotWriteOptions::default().max_workers, 8);
    }

    fn three_chunk_manifest() -> ContentManifest {
        ContentManifest {
            metadata: crate::content_manifest::Metadata {
                filenames_encrypted: false,
                depot_id: 7,
                ..Default::default()
            },
            files: vec![crate::content_manifest::FileMapping {
                filename: "data.bin".into(),
                size: 9,
                chunks: vec![
                    ChunkData {
                        offset: 0,
                        cb_original: 3,
                        crc: depot_adler_hash(b"abc"),
                        ..Default::default()
                    },
                    ChunkData {
                        offset: 3,
                        cb_original: 3,
                        crc: depot_adler_hash(b"def"),
                        ..Default::default()
                    },
                    ChunkData {
                        offset: 6,
                        cb_original: 3,
                        crc: depot_adler_hash(b"ghi"),
                        ..Default::default()
                    },
                ],
                ..Default::default()
            }],
            signature: Vec::new(),
        }
    }

    #[test]
    fn two_pool_writer_verifies_existing_chunks_and_finalizes() {
        // Multi-chunk + multi-worker forces the parallel two-pool path. Every chunk is already
        // correct on disk, so the FETCH pool counts each as verifying (never enqueues) and the
        // PROCESS pool drains an empty channel and exits cleanly — with no network at all.
        let dir = temp_dir("two_pool_existing");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("data.bin"), b"abcdefghi").unwrap();
        let manifest = three_chunk_manifest();
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };

        let verified = std::sync::atomic::AtomicU64::new(0);
        let progress = |done: u64, total: u64, verifying: bool| {
            assert_eq!(total, 9);
            if verifying {
                verified.fetch_add(1, Ordering::Relaxed);
            }
            assert!(done <= 9);
        };
        let progress_cb: DepotChunkProgressCallback = &progress;
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions {
                max_workers: 4,
                max_process_workers: 2,
                on_progress: Some(progress_cb),
                ..Default::default()
            },
        );

        assert!(result.ok(), "{}", result.error);
        assert_eq!(result.bytes_written, 9);
        assert_eq!(result.files_written, 1);
        // All three chunks were verified on disk, none fetched.
        assert_eq!(verified.load(Ordering::Relaxed), 3);
        assert_eq!(fs::metadata(dir.join("data.bin")).unwrap().len(), 9);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn two_pool_writer_respects_cancel() {
        // Cancel set before the pools start: both drain their loops immediately and the writer
        // reports the cancel verdict rather than a chunk error.
        let dir = temp_dir("two_pool_cancel");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("data.bin"), b"abcdefghi").unwrap();
        let manifest = three_chunk_manifest();
        let server = CContentServerDirectoryServerInfo {
            host: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        let cancel = AtomicBool::new(true);
        let result = write_depot_sequential(
            &manifest,
            &[3u8; 32],
            &CdnClient::new(""),
            &[server],
            dir.to_str().unwrap(),
            DepotWriteOptions {
                max_workers: 4,
                max_process_workers: 2,
                cancel: Some(&cancel),
                ..Default::default()
            },
        );
        assert!(!result.ok());
        assert_eq!(result.error, "cancelled");
        assert!(result.resume_trust_safe);
        let _ = fs::remove_dir_all(&dir);
    }

    fn temp_dir(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "blsteam_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    fn encrypted_stored_zip_chunk(key: &[u8; 32], payload: &[u8]) -> Vec<u8> {
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(b"x");
        zip.extend_from_slice(payload);

        let iv = [6u8; AES_BLOCK_BYTES];
        let wrapped = aes256_ecb_encrypt_block(key, &iv).unwrap();
        let body = aes256_cbc_encrypt(key, &iv, &zip).unwrap();
        let mut out = wrapped.to_vec();
        out.extend_from_slice(&body);
        out
    }
}
