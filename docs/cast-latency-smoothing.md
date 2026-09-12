# Wireless-cast latency reduction & "smart server" streaming — deep dive

Scope: the **no-receiver-app** path (game → phone HLS server → Chromecast/Google TV Default Media
Receiver). Goal: get end-to-end latency as low as possible **without** a TV-side app, and add adaptive
"smart server" behaviour so the stream stays smooth under real Wi-Fi conditions.

## 1. Where the seconds actually go (latency budget)

End-to-end delay = sum of these stages:

| Stage | What it is | Typical cost | Lever? |
|---|---|---|---|
| Capture→encode | VirtualDisplay compositor → MediaCodec H.264 | ~1–2 frames (30–60 ms) | **YES** (low-latency encoder, no B-frames) |
| **Segmentation** | HLS can't serve a segment until it *closes* — so the newest content is always ≥ 1 segment old | **= segment duration** | **YES** (smaller segments) |
| **Player buffer** | Chromecast's HLS player pre-buffers segments before/while playing (spec suggests ~3) | **≈ 3 × segment duration** | Partly (segment size; the receiver's buffer policy is Google's) |
| LAN transfer | pull over Wi-Fi | ~ms on a healthy LAN | indirect (bitrate/ABR) |
| Decode→display | TV HW decoder + panel | ~1–2 frames | none (TV side) |

**The dominant term is segmentation + player buffer ≈ 4 × segment_duration.**
- 2 s segments (current) → ~8 s.
- 1 s segments → ~4 s.
- 0.5 s segments → ~2 s.

Everything else is tens of milliseconds. **So the #1 lever is segment size**, and the hard floor is the
Chromecast receiver's own buffering, which we can only influence, not set.

## 2. The hard ceiling for "no app on the TV"
The Default Media Receiver decides its own buffer depth; we can't make it tiny. Realistic best case for
plain HLS on Chromecast is **~2–4 s** (with 0.5–1 s segments + `streamType: LIVE`, which we already send —
LIVE makes it seek to the live edge and buffer less than VOD). Going below that needs either:
- **LL-HLS** (partial segments + blocking playlist reload) — *uncertain* the Default Media Receiver fully
  supports it; high implementation cost; treat as a research spike, not a plan.
- **A custom receiver app** (Tier 2) — we own the buffer → ~100 ms. This is the only true low-latency path
  and is the real prize; different track.

## 3. Phase A — cheap, high-impact wins (do right after 2b plays)

1. **Shrink segments to ~1 s** (then try 0.5 s). Set encoder `KEY_I_FRAME_INTERVAL` to match (keyframe per
   segment) and `TsSegmenter.TARGET_MS` to 1000 → 500. Roughly halves, then quarters, the baseline lag.
   Cost: more keyframes = higher bitrate for the same quality (keyframes are big). Budget for it (raise
   bitrate ~20–30 % when going to 0.5 s).
2. **Low-latency encoder config** (MediaCodec):
   - `KEY_LATENCY = 1` (API 30+) — encoder emits each frame ASAP, no lookahead.
   - `KEY_MAX_B_FRAMES = 0` / Baseline or Main profile — **no B-frames** (B-frames add reorder delay).
   - `KEY_PRIORITY = 0` (realtime), `KEY_BITRATE_MODE = CBR` for predictable pacing.
   Shaves encode to ~1 frame and removes reorder jitter.
3. **Fewer buffered segments**: keep the live window at 3 (min the player needs), start casting after the
   **first** segment (we already wait for 1). Don't over-buffer on our side.
4. **Wi-Fi lock**: hold a `WifiManager.WifiLock(WIFI_MODE_FULL_HIGH_PERF)` during a cast so the radio
   doesn't power-save mid-stream (a common source of periodic hitches / latency spikes).

Expected after Phase A: **~8 s → ~3–4 s** on this device, no TV app. This is the "watchable/casual" tier
done as well as it can be.

## 4. Phase B — the "smart server" (adaptive, self-tuning)

The phone is both the encoder AND the HTTP server, so it can *see* how the TV is consuming and react. This
is the "smart streaming" the user asked for. Concretely, a control loop:

**Signal (what we can measure, for free):**
- **Segment fetch cadence** — the HTTP server logs when the Chromecast requests each `segN.ts`. If it's
  fetching ~1 segment per segment-duration, it's keeping up. If requests fall behind (or it re-requests old
  segments / the playlist stalls), the network or decoder can't keep up → a stall/latency spike is coming.
- **Fetch throughput** — bytes/sec while serving a segment ≈ available LAN bandwidth headroom.
- **Encoder queue** — how fast we're producing vs the target.

**Actions (all cheap, no receiver needed):**
1. **Adaptive bitrate (ABR-lite)** — MediaCodec supports live bitrate change:
   `encoder.setParameters(Bundle{ KEY_VIDEO_BITRATE = newBps })`. If fetch cadence falls behind or
   throughput drops, **step the bitrate down** (e.g. 6→4→2.5 Mbps) to keep segments small enough to arrive
   on time; when healthy, step back up. This prevents the *rebuffer* (the worst latency event — a stall
   balloons delay by seconds) rather than lowering the floor. **Highest-value smart feature.**
2. **Adaptive segment duration** — shorter (0.5 s) when the LAN is healthy for low latency; longer (1–2 s)
   when struggling, to cut request overhead and add resilience. Hysteresis so it doesn't oscillate.
3. **Live-edge frame skipping** — if we ever fall behind realtime (encoder or network), request an
   on-demand keyframe (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) and drop the stale queued frames so we snap back
   to the live edge instead of accumulating delay. Keeps latency from drifting upward over a long session.
4. **Keyframe-aligned segments** (already) + **request-sync at each new segment** for clean, independently
   decodable boundaries (helps the receiver start fast and recover from loss).

**Design:** a small `CastController` state machine polling the `HttpFileServer`'s per-segment fetch
timestamps every ~1 s, driving `encoder.setParameters` + `TsSegmenter` target. Bounded, hysteretic,
logs its decisions. This is a moderate build (~150–250 lines) and is where "smart" lives.

## 5. Phase C — the real low latency (separate track)
Custom Android-TV **receiver app** with a ~1–2 frame jitter buffer, fed by a low-latency transport
(RTP/H.264 over UDP, or WebRTC). ~100 ms glass-to-glass. Reuses the exact capture engine + discovery +
dialog we already built; only the transport + receiver change. This is Tier 2 and the honest answer to
"close to lag-free wireless."

## 6. Recommended order
1. **Ship 2b** (any latency) — prove live plays at all.
2. **Phase A** — segment size + low-latency encoder + Wi-Fi lock → ~3–4 s. Quick, big win.
3. **Phase B.1** — adaptive bitrate from fetch cadence (anti-stall). The single best "smart" upgrade.
4. **Phase B.2/3** — adaptive segment size + live-edge skip, if we want to squeeze further.
5. **Phase C** — receiver app, when the user wants true low latency.

Latency is fundamentally **buffer, not encode** — so on the no-app path we minimize/adapt the buffer
(Phases A/B), and only the receiver app removes it (Phase C).
