# Frame-generation remediation — bionic-fg removed, replaced by win-fg

## Why
The previously bundled **bionic-fg** Vulkan frame-generation layer (and the
GameScope `libGameScopeVK.so` it descended from) was found to embed compute
shaders with **model weights essentially identical to the proprietary fp16
Lossless Scaling frame-generation model**. Those weights are proprietary and
cannot be distributed. The upstream author took their project down and asked
downstream projects to remove it. This change does that.

## What was removed (this branch)
- `app/src/main/assets/bionic-fg/` — the traced `libbionic_fg.so` layer and its
  manifest (the binary that carried the proprietary-derived weights).
- The `app/src/main/cpp/bionic-fg` git submodule (pointed at our private fork).
- `.github/workflows/build-bionic-fg.yml` — the workflow that built the layer.
- UI exposure of the proprietary-derived models ("Traced graph", "V2 engine").
- `BIONIC_FG_*` environment wiring and the `.config/bionic-fg` config path.

On-device cleanup: `ImageFsInstaller.installWinFgLayer` now **deletes** any
previously-staged `libbionic_fg.so` + `VkLayer_BIONIC_framegen.json` from the
prefix, so devices upgrading from an older build stop carrying the old layer.

## What replaced it
**win-fg** (`The412Banner/win-fg`, v0.1) — a clean-room frame-generation layer:
- Optical flow = our MIT adaptation of AMD FidelityFX FSR3 optical flow (an
  algorithm, no learned weights).
- Synthesis = written from first principles (motion-compensated bidirectional
  warp + softmax-importance blend + forward/backward-consistency occlusion), from
  published math, not from any proprietary or traced source.
- No code from bionic-fg / lsfg-vk / GameScope. Full provenance lives in the
  win-fg repo's `docs/PROVENANCE.md`.

Bundled at `app/src/main/assets/win-fg/` (`libwin_fg.so` + manifest), staged by
`installWinFgLayer`, gated on `WIN_FG_ENABLE`.

## Not in this change (tracked separately)
- The proprietary-derived `.so` still exists in prior git history and in the
  already-published 2.9.9 release assets. Superseding/removing those is a
  separate follow-up (see project notes).
- win-fg's on-device frame *insertion* is still in bring-up; until then the
  layer loads and runs its pipeline but presents passthrough.
