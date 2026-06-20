# RESUME — bionic-fg — Phase 3 DONE ✅ (2/3/4× device-confirmed) → Phase 4 CODE DONE, partial device test in progress

**Status 2026-06-20:** Phase 3 done; 2×/3×/4× all device-confirmed. **Phase 4 (in-game live controls) CODE COMPLETE — UNCOMMITTED → now building a standard APK to device-test the parts that DON'T need a new `.so`.**

## ⏳ DECISION 2026-06-20: partial device test with the EXISTING proven `.so` (md5 `23f5bfda`)
We split Phase 4 testing because the FPS limiter needs a bionic-fg `.so` rebuild (C++ change), but the rest doesn't. Building a **standard APK** (CI `build-artifacts.yml`, label **`phase4-fg-ui`**) from the feature branch with the **OLD `.so` unchanged** (submodule pointer NOT bumped; my `layer.cpp` `fps_limit` edits stay LOCAL/uncommitted in the submodule dir).

**TEST NOW (works with old `.so` — it already hot-reloads multiplier incl 0, + flow):**
1. In-game side menu → Graphics tab → **Frame Generation** selector = **Off / 2× / 3× / 4×** (Off=mult 0 → passthrough; 2/3/4 → HUD ratio changes live).
2. **Flow Scale** slider 0.2–1.0 (visible effect on motion).
3. CPU-temp HUD fix: in-game perf overlay CPU should now read a real **°C** (was `0.0°C`) — auto-discovers the CPU thermal zone.
4. Container settings: only an FG on/off switch (multiplier dropdown REMOVED) + a new **FPS Limiter** switch.

**DOES NOT work yet (needs new `.so` — follow-up):** the **FPS Limiter slider** (FPS tab) moves + writes `fps_limit` to conf.toml + persists, but the OLD layer ignores `fps_limit` → no actual capping. Expected.

## ➡️ FOLLOW-UP after this test confirms (the `.so` rebuild)
Build a new `libbionic_fg.so` WITH the `fps_limit` pacer (C++ edits already in `app/src/main/cpp/bionic-fg/src/vk_layer/layer.cpp`, uncommitted). CI `build-bionic-fg.yml` builds the PINNED submodule SHA → must commit submodule changes to a fork remote + bump pointer (his repo has NO license), OR point CI at the working tree, OR local NDK build. Then re-stage the new `.so` + re-test the FPS limiter (cap base 30 + FG 2× → HUD ~60). Also still pending: bundle `.so` into imagefs (Phase 5), strip CHK logging, commit stable keystore, upstream PR.

✅ Install note (UPDATED): the proven `.so` (md5 `23f5bfda`) + manifest are now **BUNDLED as APK assets** (`app/src/main/assets/bionic-fg/`) and **auto-staged into imagefs on extraction** by `ImageFsInstaller.installBionicFgLayer()` (called from both reinstall success blocks + the imagefs-valid branch of `installIfNeeded`, idempotent). **NO MORE manual `.so` staging** even after the debug-key wipe+reinstall. When the new fps_limit `.so` is built, just **swap `app/src/main/assets/bionic-fg/libbionic_fg.so`** and rebuild. Per [[feedback_save_before_device_launch]] still capture logcat to a crash-surviving `/sdcard/Download/*.txt`.

---

## ✅ Phase 3 result (device-confirmed 2026-06-20 ~07:46)

## ✅ Phase 3 result (device-confirmed 2026-06-20 ~07:46)
Per-container **Frame Generation (AI)** toggle works END-TO-END with NO manual `.container` hack — that was the whole point of Phase 3.
- Fresh install (key change → wipe & fresh imagefs), re-staged proven `.so` md5 `23f5bfda` into fresh imagefs, installed DOOMBLADE, flipped the toggle ON (mult 2×), launched.
- Log `/sdcard/Download/bionicfg_phase3_test.txt` (5.7MB) GREEN: `BIONIC_FG_ENABLE=1` in `ProcessHelper: env:` (UI injected it), AHB import ok, all SPIR-V, `FramegenContext ready 1280x720 mult=2 model=0`, `SwapchainState ready provisionedOutputs=3`, no hang/segfault from our layer.
- conf.toml `imagefs/home/xuser/.config/bionic-fg/conf.toml` = `# Written by Bannerlator …` / `multiplier=2` / `flow_scale=0.8` / `model=0` → `writeBionicFgConfig()` wrote it.
- **Visual 2× confirmed (screenshots 074357/074430/074508):** DXVK HUD **FPS 33.8** real + perf overlay **FPS 68** presented = **2.01× = mult=2 on screen.** Stack: DXVK 2.4.1 / D3D11 FL11_1 / Wrapper(Adreno 750) / driver 26.1.99.
- Benign noise: `libVkLayer_LSFGVK_frame_generation.so … libc++_shared.so not found` (old lsfg-vk stub, NOT ours); `com.qti.diagservices` ANR (broken Pocket FIT diag fw).

Phase 3 code = commit `9ccf22f` on `feature/bionic-fg-framegen` (Container.java extraData flags, XServerDisplayActivity env+writeBionicFgConfig, ContainerDetailScreen/ViewModel UI). Build run `27869509588`, standard APK md5 `df68351f296ebf0f4e9d96df1cb1b796`. Installed app key now `2091b4be…` (uid changed on reinstall).

## ➡️ Phase 4 — in-game drawer live control (NEXT)
Live frame-gen control from the in-game side menu, exploiting conf.toml HOT-RELOAD (layer re-stats mtime each present).
- Template = **Native Rendering toggle**: `ui/XServerDrawerState.kt` (StateFlow + Runnable), UI in `XServerDrawer.kt`, callbacks in `XServerDisplayActivity.java` (~:360).
- Add: FG on/off toggle + multiplier picker in the drawer → on change, rewrite `imagefs/home/xuser/.config/bionic-fg/conf.toml` → layer hot-reloads live (no relaunch). Toggling FG live = set/clear via conf.toml multiplier (0=off) since BIONIC_FG_ENABLE is launch-time env.

## Pending / polish (Phase 5)
- Verify **3×/4×** on device (built + supported, UNPROVEN on this wrapper stack; default 2× is proven).
- Strip CHK debug logging + the LSFGVK red-herring layer-load failure.
- **Bundle `.so`+manifest into imagefs/APK** so no hand-staging (currently re-staged from `/sdcard/Download/bionic-fg-staged/` after each wipe).
- Commit a **stable debug keystore** to avoid wipe-on-update (user declined before; re-offer if iterating heavily).
- Owe **xXJSONDeruloXx** the PR = our ~364-line single-device dispatch-fix patch (now Phase-3-proven). His repo has **NO LICENSE** yet → ask before bundling in a tagged release.
- `BIONIC_FG_UPSTREAM_REPORT.md` working-tree edit is STALE (says single-device crashes; run6+Phase3 prove it works) — fix in Phase 5 PR.

## ⚠️ Rules
- **Repo STILL PRIVATE** — no public push / release / upstream PR without explicit user OK.
- Save memory + progress log + this file BEFORE every device launch (launch can crash the session). Capture logcat to `/sdcard/Download/*.txt`.
- Update memory `project_bionic_fg_framegen.md` after each phase.
- Re-stage `.so` recipe (after any fresh imagefs): get uid via `pm list packages -U | grep com.winlator.banner`, then copy staged `.so`→`imagefs/usr/lib/`, `.json`→`imagefs/usr/share/vulkan/implicit_layer.d/`, chown uid:uid, chmod 644. Verify md5 `23f5bfda72e49366e2d443ffc4ae288c`.
