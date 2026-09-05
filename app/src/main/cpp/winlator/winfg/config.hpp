// win-fg — runtime config: enable gate, model, multiplier, and the synthesis
// tuning parameters. Sourced from environment variables and an optional
// conf.toml (a config file, when present, wins over the env defaults).
#pragma once
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <fstream>
#include <sstream>

namespace winfg {

struct Config {
    bool     enabled     = false;  // gate; loader also honours the manifest enable var
    // GRANULAR PRESENT-PATH DEBUG TRACE. Default OFF. When ON, winfg_QueuePresentKHR
    // emits a per-frame, step-by-step trail (logcat tag "win-fg") so a freeze/
    // crash-on-enable leaves an obvious LAST line at the exact stage that hung or
    // faulted (acquire-spare, compute-submit, present-generated/real, fence wait,
    // fallback). Zero behaviour change and effectively zero cost when off — one
    // predicted-not-taken bool test per step, no logcat writes.
    // WIN_FG_DEBUG=1|0 / conf.toml debug=on|off.
    bool     debug       = false;
    // ── EVEN-CADENCE FRAME PACING ─────────────────────────────────────────────
    // Default ON. The 2x insert path presents the GENERATED frame then the REAL
    // frame back-to-back in one vkQueuePresentKHR hook call, so on Mailbox/
    // Immediate both land in ~one instant followed by a full frame-interval gap
    // until the next pair — uneven in time → judder ("slows down to catch up").
    // With pacing on, the layer measures the real-frame interval (EMA of a
    // monotonic clock) and holds the REAL present to its scheduled beat so the
    // generated frame lands near the temporal MIDPOINT between consecutive real
    // frames (even gen,real,gen,real cadence). Pure bounded CPU sleep — no GPU
    // wait added; if a frame is already behind schedule the wait is SKIPPED
    // (present immediately) so worst case is one un-paced pair, never a hitch.
    // FIFO self-paces so the waits collapse to ~0 there. Off ⇒ byte-identical
    // back-to-back behaviour. WIN_FG_PACING=on|off / conf.toml pacing=on|off.
    // ISOLATION BUILD: default flipped to OFF to A/B the AYANEO-750 post-setting-change
    // flicker regression against the pacing feature (everything else byte-identical).
    bool     pacing      = false;
    int      model       = 4;      // 3 = symmetric flow, 4 = bidir + occlusion gate
    int      multiplier  = 2;      // generated presents per real present + 1
    // EXTRA SWAPCHAIN IMAGE HEADROOM (device-freeze fix). On top of the +1 spare the
    // insert path needs, request this many ADDITIONAL images at swapchain creation:
    //   requested minImageCount = app_min + 1 (spare) + extra_images,
    // clamped to surfaceCaps.maxImageCount (0 = unlimited -> no clamp), never below
    // app_min+1. The extra headroom stops the guest's NEXT vkAcquireNextImageKHR from
    // starving while win-fg holds a spare + a real frame in flight to the AHB-backed
    // host compositor. Some SoCs (Adreno 840 / Turnip) recycle displayed AHBs too
    // slowly to tolerate the extra present with only the +1 spare and freeze on the
    // first inserted frame; the AYANEO (Adreno 750) recycles fast enough to tolerate
    // it. Default 2 (a few MB VRAM), applied on all devices. On a device that already
    // works this only grows the pool — no FPS/quality change; the pool-headroom guard
    // in QueuePresentKHR stays a no-op when headroom is adequate.
    // WIN_FG_EXTRA_IMAGES / conf.toml extra_images.
    int      extraImages = 2;
    // ── FLOW-RESOLUTION PERFORMANCE PRESET ────────────────────────────────────
    // Trades base-FPS-drop against optical-flow quality by selecting the FINEST
    // pyramid level the dense flow is solved at (FrameGen::kFlowFinest):
    //   0 = Quality     -> kFlowFinest = 1 (~1/2-res flow; best motion, biggest FG cost)
    //   1 = Balanced    -> kFlowFinest = 2 (~1/4-res; TODAY's behaviour)  [DEFAULT]
    //   2 = Performance -> kFlowFinest = 3 (~1/8-res; cheapest -> smallest base-FPS drop)
    // Out-of-range clamps to Balanced (1), so an unset/older conf.toml is byte-
    // identical to today. Changing this LIVE rebuilds the affected per-size flow
    // images (like a resize) inside the layer and resets the flow predictor, so a
    // preset change is fully hot — no FG toggle needed. See FrameGen::configure /
    // FrameGen::flowFinestForPreset. WIN_FG_PERF_PRESET / conf.toml perf_preset.
    int      perfPreset  = 1;      // 0 quality, 1 balanced (default), 2 performance
    float    flowScale   = 1.0f;   // scales solved flow magnitude
    // C1 GLOBAL-MOTION PRE-WARP. Estimate the per-frame camera affine (LK) and
    // remove it before the dense SAD flow search so the search only sees coherent
    // object-only residual motion (kills fast-pan "melt"). 0 = auto (engage only
    // when the LK solve is stable), 1 = on (force-engage once a solve exists),
    // 2 = off (never pre-warp; identity affine → pipeline == pre-C1 exactly).
    // WIN_FG_GM=auto|on|off  /  conf.toml global_motion=auto|on|off.
    int      gmMode      = 0;      // 0 auto, 1 on, 2 off
    // C2 FLOW REGULARIZATION (TV-L1 smoothness prior). After C1 removes the camera
    // motion, the dense flow (flowLvl_ at kFlowFinest, 1/4-res) is the OBJECT-only
    // residual; it still carries spurious per-block vectors. C2 runs N semi-implicit
    // TV-L1 denoising iterations on that field — an L1 data term + edge-aware Total-
    // Variation regularizer — to kill incoherent vectors while KEEPING true motion
    // discontinuities (object edges) sharp. Solved in-place; expand's median + C1
    // add-back consume the cleaned field unchanged. 0 iterations ⇒ byte-identical to
    // pre-C2. See shaders/of3_flowreg.comp.
    // WIN_FG_FLOWREG=auto|on|off  /  conf.toml flow_reg=auto|on|off.
    int      frMode      = 0;      // 0 auto (engaged), 1 on (engaged), 2 off
    int      frIters     = 4;      // TV-L1 iterations at kFlowFinest (0 ⇒ off)
    float    frLambda    = 2.0f;   // L1 data-fidelity weight (higher ⇒ trust SAD flow)
    float    frDt        = 0.25f;  // semi-implicit smoothing step (higher ⇒ smoother)
    float    frEdge      = 8.0f;   // luma-gradient edge sensitivity for the TV weight g
    float    frEps       = 0.05f;  // Charbonnier epsilon (px) for the TV diffusivity
    // synthesis (wfg_synth) tuning
    float    beta        = 8.0f;   // softmax sharpness on importance Z
    float    lambda      = 0.6f;   // FB-consistency vs photometric weight
    // Tightened 2026-08-17 to prioritise "no ghost" over max sharpness — the
    // remaining single-frame ghosts came from pixels that the previous defaults
    // still trusted with warp when they should have cross-faded. Raising both
    // pushes borderline pixels toward the safe cross-fade path (softer, but no
    // smear). Prewarp will lift the ceiling further; this is the "safety net"
    // knob change until then.
    // Walked back from (0.10, 9.0) tighten after user reported softening. The
    // real fix for the residual ghosts is the 3a-fallback path change in
    // layer.cpp (present real curr on fallback instead of blitting synth over);
    // the gate can stay closer to the original values now.
    float    epsilon     = 0.07f;  // disocclusion floor (0.05 -> 0.10 -> 0.07)
    float    photoScale  = 7.5f;   // photometric residual scale into Z (6.0 -> 9.0 -> 7.5)
    // HUD exclusion rect in pixel coords (x0,y0,x1,y1). Fragments inside are
    // passed through as the real current frame (no warp, no synth) so text /
    // overlays don't ghost. Disabled when x0 >= x1 or y0 >= y1 (the default).
    // Sourced from WIN_FG_HUD_RECT="x0,y0,x1,y1" env var or conf.toml
    // (hudRect="x0,y0,x1,y1"). Pattern is Isygold's Vegas DXVK framegen —
    // host knows where the HUD is, tell the shader to skip it.
    float    hudX0       = 0.0f;
    float    hudY0       = 0.0f;
    float    hudX1       = 0.0f;
    float    hudY1       = 0.0f;

    // ── TRAINING-DATA CAPTURE MODE (Route-B VFI dataset collection) ───────────
    // A dev-only gate. When OFF (default) the present path is byte-identical to a
    // build without this feature — a single bool test and nothing else. When ON,
    // every real present is downscaled on the GPU, read back async, and written to
    // disk as lossless QOI (motion-rich 256² triplet patches by default, or full
    // downscaled frames) plus a JSONL manifest, for offline (i-1,i+1)->i training.
    // NEVER captures win-fg's generated frames — capture reads the real swapchain
    // image at the TOP of QueuePresentKHR, before any interpolation.
    // WIN_FG_CAPTURE=on|off  /  conf.toml capture=on|off.
    bool     capture       = false;
    // Downscale target box (aspect-preserving, never upscales). WIN_FG_CAPTURE_W/H,
    // conf capture_width/capture_height.
    int      captureW      = 1280;
    int      captureH      = 720;
    // 0 = patch (self-contained aligned triplets, DEFAULT), 1 = frame (full
    // downscaled frames in order). WIN_FG_CAPTURE_MODE=patch|frame.
    int      capMode       = 0;
    int      capPatches    = 3;     // patches per triplet in patch mode (WIN_FG_CAPTURE_PATCHES)
    int      capPatchSize  = 256;   // patch edge in downscaled px (WIN_FG_CAPTURE_PATCH)
    // Skip a unit whose inter-frame motion (mean per-pixel luma abs-diff, 0..255)
    // is below this. WIN_FG_CAPTURE_MOTION / conf capture_motion.
    float    capMotion     = 2.0f;
    // Output dir; empty => $HOME/.cache/winfg-capture. WIN_FG_CAPTURE_DIR /
    // conf capture_dir. A per-run session subdir is created under it.
    std::string capDir;
    // Rolling-shard size cap (MiB). Output is a small number of large packed
    // .wfgcap containers, not thousands of loose files; a new shard opens when the
    // current one passes this. WIN_FG_CAPTURE_SHARD_MB / conf capture_shard_mb.
    int      capShardMB    = 1024;

    void sanitize() {
        if (model < 3) model = 3; if (model > 4) model = 4;
        if (multiplier < 2) multiplier = 2; if (multiplier > 4) multiplier = 4;
        if (extraImages < 0) extraImages = 0; if (extraImages > 8) extraImages = 8;
        if (perfPreset < 0 || perfPreset > 2) perfPreset = 1;   // clamp out-of-range -> Balanced
        if (gmMode < 0) gmMode = 0; if (gmMode > 2) gmMode = 2;
        if (frMode < 0) frMode = 0; if (frMode > 2) frMode = 2;
        if (frIters < 0) frIters = 0; if (frIters > 16) frIters = 16;
        if (frLambda < 0.0f) frLambda = 0.0f;
        if (frDt < 0.01f) frDt = 0.01f; if (frDt > 4.0f) frDt = 4.0f;
        if (frEdge < 0.0f) frEdge = 0.0f;
        if (frEps < 1e-3f) frEps = 1e-3f;
        if (flowScale < 0.05f) flowScale = 0.05f; if (flowScale > 4.0f) flowScale = 4.0f;
        if (beta < 0.0f) beta = 0.0f; if (lambda < 0.0f) lambda = 0.0f;
        // capture knobs
        if (captureW < 16) captureW = 16; if (captureW > 7680) captureW = 7680;
        if (captureH < 16) captureH = 16; if (captureH > 4320) captureH = 4320;
        if (capMode < 0) capMode = 0; if (capMode > 1) capMode = 1;
        if (capPatches < 1) capPatches = 1; if (capPatches > 16) capPatches = 16;
        if (capPatchSize < 16) capPatchSize = 16; if (capPatchSize > 1024) capPatchSize = 1024;
        if (capMotion < 0.0f) capMotion = 0.0f;
        if (capShardMB < 16) capShardMB = 16; if (capShardMB > 65536) capShardMB = 65536;
    }
};

static inline float envf(const char* k, float d) {
    const char* v = std::getenv(k); return v ? std::strtof(v, nullptr) : d;
}
static inline int envi(const char* k, int d) {
    const char* v = std::getenv(k); return v ? std::atoi(v) : d;
}

// "auto"/"on"/"off" (also true/false/on/off numerics) -> {0 auto, 1 on, 2 off}.
static inline int parse_tristate(const std::string& v, int dflt) {
    if (v.empty()) return dflt;
    if (v == "auto") return 0;
    if (v == "on"  || v == "1" || v == "force" || v == "true"  || v == "yes") return 1;
    if (v == "off" || v == "0" || v == "false" || v == "no")                  return 2;
    return dflt;
}

// "on"/"off"/"1"/"true"/... -> bool.
static inline bool parse_bool(const std::string& v, bool dflt) {
    int t = parse_tristate(v, dflt ? 1 : 2);
    return t == 1;
}
// "patch"|"frame" -> {0 patch, 1 frame}.
static inline int parse_capmode(const std::string& v, int dflt) {
    if (v == "patch" || v == "patches" || v == "0") return 0;
    if (v == "frame" || v == "frames"  || v == "1") return 1;
    return dflt;
}

// key=value TOML-lite reader (flat keys, '#' comments) — enough for our knobs.
static inline void apply_toml(Config& c, const std::string& path) {
    std::ifstream f(path);
    if (!f) return;
    std::string line;
    while (std::getline(f, line)) {
        auto h = line.find('#'); if (h != std::string::npos) line = line.substr(0, h);
        auto eq = line.find('='); if (eq == std::string::npos) continue;
        auto trim = [](std::string s){
            size_t a = s.find_first_not_of(" \t\r\n"); size_t b = s.find_last_not_of(" \t\r\n");
            return (a == std::string::npos) ? std::string() : s.substr(a, b - a + 1); };
        std::string k = trim(line.substr(0, eq)), v = trim(line.substr(eq + 1));
        if (k.empty() || v.empty()) continue;
        if      (k == "enabled")    c.enabled = (v == "1" || v == "true");
        else if (k == "debug")      c.debug = parse_bool(v, c.debug);
        else if (k == "pacing")     c.pacing = parse_bool(v, c.pacing);
        else if (k == "model")      c.model = std::atoi(v.c_str());
        else if (k == "multiplier") c.multiplier = std::atoi(v.c_str());
        else if (k == "extra_images") c.extraImages = std::atoi(v.c_str());
        else if (k == "perf_preset") c.perfPreset = std::atoi(v.c_str());
        else if (k == "global_motion") c.gmMode = parse_tristate(v, c.gmMode);
        else if (k == "flow_reg")   c.frMode = parse_tristate(v, c.frMode);
        else if (k == "fr_iters")   c.frIters = std::atoi(v.c_str());
        else if (k == "fr_lambda")  c.frLambda = std::strtof(v.c_str(), nullptr);
        else if (k == "fr_dt")      c.frDt = std::strtof(v.c_str(), nullptr);
        else if (k == "fr_edge")    c.frEdge = std::strtof(v.c_str(), nullptr);
        else if (k == "fr_eps")     c.frEps = std::strtof(v.c_str(), nullptr);
        else if (k == "flowScale")  c.flowScale = std::strtof(v.c_str(), nullptr);
        else if (k == "beta")       c.beta = std::strtof(v.c_str(), nullptr);
        else if (k == "lambda")     c.lambda = std::strtof(v.c_str(), nullptr);
        else if (k == "epsilon")    c.epsilon = std::strtof(v.c_str(), nullptr);
        else if (k == "photoScale") c.photoScale = std::strtof(v.c_str(), nullptr);
        // ── capture-mode knobs ────────────────────────────────────────────────
        else if (k == "capture")        c.capture = parse_bool(v, c.capture);
        else if (k == "capture_dir")    c.capDir = v;
        else if (k == "capture_width")  c.captureW = std::atoi(v.c_str());
        else if (k == "capture_height") c.captureH = std::atoi(v.c_str());
        else if (k == "capture_mode")   c.capMode = parse_capmode(v, c.capMode);
        else if (k == "capture_patches")    c.capPatches = std::atoi(v.c_str());
        else if (k == "capture_patch_size") c.capPatchSize = std::atoi(v.c_str());
        else if (k == "capture_motion")     c.capMotion = std::strtof(v.c_str(), nullptr);
        else if (k == "capture_shard_mb")   c.capShardMB = std::atoi(v.c_str());
        else if (k == "hudRect") {
            // "x0,y0,x1,y1" in pixel coords
            float r[4] = {0,0,0,0}; int n = 0;
            size_t start = 0; std::string s = v;
            for (; n < 4; ++n) {
                size_t comma = s.find(',', start);
                std::string tok = s.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
                r[n] = std::strtof(tok.c_str(), nullptr);
                if (comma == std::string::npos) break;
                start = comma + 1;
            }
            c.hudX0 = r[0]; c.hudY0 = r[1]; c.hudX1 = r[2]; c.hudY1 = r[3];
        }
    }
}

// Path of the conf.toml the app writes (used for hot-reload mtime checks).
static inline std::string conf_path() {
    if (const char* p = std::getenv("WIN_FG_CONF")) return p;
    const char* home = std::getenv("HOME");
    return home ? std::string(home) + "/.config/win-fg/conf.toml" : std::string();
}

// Resolve the capture output root: explicit override, else $HOME/.cache/winfg-capture,
// else a /tmp fallback so a HOME-less prefix still writes somewhere pullable.
static inline std::string capture_root(const Config& c) {
    if (!c.capDir.empty()) return c.capDir;
    if (const char* home = std::getenv("HOME")) return std::string(home) + "/.cache/winfg-capture";
    return std::string("/tmp/winfg-capture");
}

// env defaults first, then conf.toml overrides (file wins when present).
static inline Config load_config() {
    Config c;
    c.enabled    = envi("WIN_FG_ENABLE", 0) != 0;
    if (const char* d = std::getenv("WIN_FG_DEBUG")) c.debug = parse_bool(d, c.debug);
    if (const char* pc = std::getenv("WIN_FG_PACING")) c.pacing = parse_bool(pc, c.pacing);
    c.model      = envi("WIN_FG_MODEL", c.model);
    c.multiplier = envi("WIN_FG_MULT", c.multiplier);
    c.extraImages = envi("WIN_FG_EXTRA_IMAGES", c.extraImages);
    c.perfPreset  = envi("WIN_FG_PERF_PRESET", c.perfPreset);
    if (const char* g = std::getenv("WIN_FG_GM")) c.gmMode = parse_tristate(g, c.gmMode);
    if (const char* r = std::getenv("WIN_FG_FLOWREG")) c.frMode = parse_tristate(r, c.frMode);
    c.frIters    = envi("WIN_FG_FR_ITERS", c.frIters);
    c.frLambda   = envf("WIN_FG_FR_LAMBDA", c.frLambda);
    c.frDt       = envf("WIN_FG_FR_DT", c.frDt);
    c.frEdge     = envf("WIN_FG_FR_EDGE", c.frEdge);
    c.frEps      = envf("WIN_FG_FR_EPS", c.frEps);
    c.flowScale  = envf("WIN_FG_FLOWSCALE", c.flowScale);
    c.beta       = envf("WIN_FG_BETA", c.beta);
    c.lambda     = envf("WIN_FG_LAMBDA", c.lambda);
    c.epsilon    = envf("WIN_FG_EPSILON", c.epsilon);
    c.photoScale = envf("WIN_FG_PHOTOSCALE", c.photoScale);
    // ── capture-mode env (conf.toml still wins below) ─────────────────────────
    if (const char* cc = std::getenv("WIN_FG_CAPTURE")) c.capture = parse_bool(cc, c.capture);
    c.captureW     = envi("WIN_FG_CAPTURE_W", c.captureW);
    c.captureH     = envi("WIN_FG_CAPTURE_H", c.captureH);
    if (const char* cm = std::getenv("WIN_FG_CAPTURE_MODE")) c.capMode = parse_capmode(cm, c.capMode);
    c.capPatches   = envi("WIN_FG_CAPTURE_PATCHES", c.capPatches);
    c.capPatchSize = envi("WIN_FG_CAPTURE_PATCH", c.capPatchSize);
    c.capMotion    = envf("WIN_FG_CAPTURE_MOTION", c.capMotion);
    c.capShardMB   = envi("WIN_FG_CAPTURE_SHARD_MB", c.capShardMB);
    if (const char* cd = std::getenv("WIN_FG_CAPTURE_DIR")) c.capDir = cd;
    if (const char* h = std::getenv("WIN_FG_HUD_RECT")) {
        // "x0,y0,x1,y1" — pixel coords; disabled when x0>=x1 or y0>=y1
        float r[4] = {0,0,0,0}; int n = 0;
        std::string s = h; size_t start = 0;
        for (; n < 4; ++n) {
            size_t comma = s.find(',', start);
            r[n] = std::strtof(s.substr(start, comma == std::string::npos ? std::string::npos : comma - start).c_str(), nullptr);
            if (comma == std::string::npos) break;
            start = comma + 1;
        }
        c.hudX0 = r[0]; c.hudY0 = r[1]; c.hudX1 = r[2]; c.hudY1 = r[3];
    }
    const char* home = std::getenv("HOME");
    if (home) apply_toml(c, std::string(home) + "/.config/win-fg/conf.toml");
    if (const char* p = std::getenv("WIN_FG_CONF")) apply_toml(c, p);
    c.sanitize();
    return c;
}

// UBO layout — MUST match wfg_synth.comp binding 0.
// The vec4 hudRect is 16-byte aligned in std140, sits at offset 32 (after the
// 8 leading floats which naturally occupy 32B). Disabled when hudX0>=hudX1.
struct SynthUBO {
    float flowScale, alpha, beta, lambda, epsilon, photoScale, pad0, pad1;
    float hudX0, hudY0, hudX1, hudY1;
};
// UBO layout — MUST match of3_flow / of3_expand_m4 binding 0 (Model3UBO).
struct FlowUBO { float flowScale; uint32_t level; float occlLo; float occlHi; };

// C1 global-motion UBO — MUST match binding 1 in of3_gm_reduce / of3_gm_prewarp /
// of3_expand(_m4). std140: four vec4 = 64 bytes. The affine is in centered-UV
// space: P = [[p1,p3],[p2,p4]], translation (tx,ty); warp uvSrc = uv + P*(uv-0.5)
// + t maps a prev(template) coord to the curr(image) coord it samples (prev→curr).
//   lin[]  = running LK estimate (linearization point fed to of3_gm_reduce)
//   app[]  = APPLIED affine (of3_gm_prewarp + expand add-back); identity when
//            disengaged so the pipeline is byte-identical to pre-C1.
struct GMUBO {
    float lin[4];   // p1,p2,p3,p4
    float linT[4];  // tx,ty, engaged, pad
    float app[4];   // p1,p2,p3,p4
    float appT[4];  // tx,ty, pad, pad
};

// C2 flow-regularization UBO — MUST match binding 0 (vec4 params) in
// of3_flowreg.comp. std140: one vec4 = 16 bytes. See Config::fr* fields.
struct FlowRegUBO {
    float dt;         // params.x — semi-implicit smoothing step
    float lambda;     // params.y — L1 data-fidelity weight (θ = dt·lambda)
    float edgeAlpha;  // params.z — luma-gradient edge sensitivity for g
    float epsTV;      // params.w — Charbonnier epsilon (px) for the TV diffusivity
};

} // namespace winfg
