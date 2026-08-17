# pulseaudio-android — adaptive AAudio sink (vendored build)

Cross-builds the **PulseAudio 13.0** stack + our **adaptive `module-aaudio-sink`** for Android arm64.

## Provenance & credits
- The AAudio sink module originates from **[`brunodev85/pulseaudio-android`](https://github.com/brunodev85/pulseaudio-android)**,
  `pulseaudio-module/module-aaudio-sink.c` — **LGPL-2.1**, authored by **Tom Yan & BrunoSX**. This is
  the same module GameNative and WinNative bundle; nobody else wrote it.
- It builds against **stock upstream PulseAudio 13.0** (`pulseaudio/pulseaudio` @ `200618b3`) — no PA
  source patches; Android/bionic adaptation is entirely via the `ac_cv_*` configure overrides carried
  in `build-stack.sh`. 13.0 == the version Bannerlator already ships, so the output is ABI drop-in.
- **Bannerlator's addition** (`pulseaudio-module/module-aaudio-sink.c`, same LGPL-2.1): adaptive,
  xrun-driven buffer sizing + `adaptive`/`buffer_frames`/`max_buffer_frames` modargs. See
  `docs/adaptive-audio-plan.md`. The extra-modarg idea is inspired by WinNative; the adaptive logic is
  ours (no upstream fork has it).

## What it produces (arm64)
- Client libs → for `app/src/main/jniLibs/arm64-v8a/`: `libpulse.so`, `libpulsecommon-13.0.so`,
  `libpulsecore-13.0.so`, `libpulseaudio.so` (daemon), `libsndfile.so`, `libltdl.so`.
- Modules → for `app/src/main/assets/pulseaudio.tzst` (layout `modules/arm64/*.so`):
  `module-aaudio-sink.so` (ours), `module-native-protocol-unix.so`, `libprotocol-native.so`.

## Build
Runs in CI via `.github/workflows/build-pulseaudio.yml` (manual dispatch) — do not build locally.
`build-stack.sh` clones + cross-compiles PA 13.0 (+ libtool/libsndfile); `build-module.sh` compiles
our module against that tree. Pinned refs live at the top of `build-stack.sh`.

> Status: build pipeline is v1 and expected to need CI iteration (autotools/NDK quirks) before it
> produces clean artifacts. Once green, the integration step swaps the output into jniLibs + the tzst.
