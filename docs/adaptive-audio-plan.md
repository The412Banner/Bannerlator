# Adaptive AAudio sink — plan (branch `feat/pulseaudio-adaptive-aaudio-sink`)

## Goal
Kill audio crackling (buffer underruns) on speakers **and** headphones while keeping latency as low
as each device can sustain — automatically — with manual presets + fine-tuning for power users.

## Why crackle happens (the "bucket")
Audio plays out of a buffer ("bucket"). The guest game pours samples in; AAudio drains them to the
speaker. If the bucket runs dry for even a moment (the guest starves under box64/FEX + DXVK load) you
get a crackle/pop. Bucket size is the dial: bigger = no crackle but more latency; smaller = low
latency but underrun-prone. There are **two buckets in series**:
1. **Guest side** — Wine's winepulse, sized by env var `PULSE_LATENCY_MSEC` (easy, no rebuild).
2. **Sink side** — inside `module-aaudio-sink` (the AAudio output stream buffer). **Our shipped module
   is an OLD build locked to AAudio LOW_LATENCY (the smallest/FAST buffer) → crackle-prone**, and
   exposes no buffer knob (modargs: only `sink_name`/`sink_properties`/`rate`).

## Provenance (verified 2026-08-10)
- The module is **`brunodev85/pulseaudio-android`** → `pulseaudio-module/module-aaudio-sink.c`
  (LGPL-2.1, authors **Tom Yan, BrunoSX**). NOT written by GameNative or WinNative.
- It builds against **STOCK upstream PulseAudio 13.0** — submodule pins `pulseaudio/pulseaudio`
  @ `200618b32f0964a479d69c9b6e5073e6931c370a` ("build-sys: Add missing files to release tarballs").
  No PA source patches; all Android/bionic adaptation is via `ac_cv_*` configure env overrides in
  `main-build.sh`. **13.0 == our exact bundled version → ABI drop-in.**
- Bruno's CURRENT source already adds `volume` + `performance_mode` (0=NONE / 1=LOW_LATENCY /
  2=POWER_SAVING) modargs — our shipped `.so` predates them. GameNative uses `performance_mode`;
  WinNative patched further (`low_latency`, fragment/sample-rate/channels modargs).

## Design — our fork (beyond Bruno & WinNative)
Fork Bruno's `module-aaudio-sink.c` and add **adaptive, xrun-driven buffer sizing** (the "smart"
part nobody upstream has), plus explicit fine-tune modargs:

New modargs (on top of Bruno's `sink_name`/`sink_properties`/`rate`/`volume`/`performance_mode`):
- `adaptive=<0|1>` (default 1) — auto-grow the AAudio buffer on underruns.
- `buffer_frames=<n>` (default 0=auto) — initial buffer size in frames (0 → framesPerBurst×2).
- `max_buffer_frames=<n>` (default 0=capacity) — cap for adaptive growth.

Adaptive algorithm (canonical Google/Oboe pattern), in the AAudio data callback:
1. On open, read `framesPerBurst` + `bufferCapacity`; set initial `bufferSize = framesPerBurst×2`
   (or `buffer_frames`).
2. Each callback, read `AAudioStream_getXRunCount()`. If it increased since last check, grow:
   `bufferSize = min(bufferSize + framesPerBurst, min(max_buffer_frames?:capacity, capacity))`
   via `AAudioStream_setBufferSizeInFrames()`. Never shrink (monotonic — settles at the lowest
   crackle-free size for this device+load).

## Presets (Java → modargs + guest env), user-facing
- **Auto / Smart** (default): `performance_mode=1 adaptive=1` + `PULSE_LATENCY_MSEC≈100`. Starts
  tight, grows only as needed.
- **Low latency**: `performance_mode=1 adaptive=0` + `PULSE_LATENCY_MSEC=40`. Tight, may crackle.
- **Balanced**: `performance_mode=2 adaptive=1` + `PULSE_LATENCY_MSEC=100`.
- **Stable (no crackle)**: `performance_mode=0 adaptive=1` + `PULSE_LATENCY_MSEC=144`.
- **Custom (fine-tune)**: user sets `performance_mode` + `buffer_frames`/`max_buffer_frames` +
  `PULSE_LATENCY_MSEC` directly (env vars).

Env var contract (recognized in EnvVarsEditor + mapped in PulseAudioComponent):
- `PULSE_LATENCY_MSEC` (already recognized) — guest-side bucket; set a default.
- `BANNER_AUDIO_PRESET` = auto|low_latency|balanced|stable|custom
- `BANNER_AUDIO_PERF_MODE`, `BANNER_AUDIO_ADAPTIVE`, `BANNER_AUDIO_BUFFER_FRAMES`,
  `BANNER_AUDIO_MAX_BUFFER_FRAMES` (custom overrides).

## Build (3b — whole 13.0 stack, in CI)
Vendored under `native/pulseaudio-android/`. CI job `build-pulseaudio.yml`:
1. Container with NDK r26-ish (API 26), autotools, zstd.
2. Clone stock PA `200618b3` + libtool + libsndfile (as Bruno does).
3. `build-stack.sh` → daemon `libpulseaudio.so` + `libpulse/…common-13.0/…core-13.0/…sndfile/…ltdl`
   + `libprotocol-native.so` + `module-native-protocol-unix.so`.
4. `build-module.sh` → our enhanced `module-aaudio-sink.so`.
5. Package → new `pulseaudio.tzst` (+ the client `.so`s go to `app/src/main/jniLibs/arm64-v8a/`).

## Integration
- Replace `app/src/main/assets/pulseaudio.tzst` + the jniLibs `.so` set with CI output.
- `PulseAudioComponent.execPulseAudio()` `default.pa`: `load-module module-aaudio-sink <preset args>`.
- `nativeRecreateSink` (route-change fix): pass the same preset args on recovery loads.
- UI: audio preset dropdown + fine-tune fields (container editor + per-game), "?" help + glossary.

## Status
- [x] Recon + provenance + full source captured (this doc).
- [ ] Enhanced module .c (adaptive + modargs).
- [ ] Vendored build kit + CI cross-compile of the 13.0 stack (LONG POLE — needs CI iteration).
- [ ] App integration (bundle swap, default.pa, env vars, UI presets/fine-tune).
- [ ] Device-prove: crackle gone on speaker + headphones; latency acceptable; adaptive settles.

## Credits to carry into release notes
Module: **Tom Yan & BrunoSX** (`brunodev85/pulseaudio-android`, LGPL-2.1). Adaptive buffer sizing +
preset/fine-tune modargs + emulator integration are Bannerlator's own. WinNative referenced for the
extra-modarg approach.
