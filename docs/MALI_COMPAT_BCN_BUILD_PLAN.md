# Build Plan — "Wrapper + compat + bcn" Mali DX12 + BCn driver

**Status:** SCOPED (read-only), not built. Branch `feat/mali-ultimate-driver` off main `2db8d25c`.
**Verdict:** 🟢 GREEN with two caveats (GPU-floor gating + build-from-source binaries).
**Sources:** `leegao/compat_layer` @ `a32f0852`, `leegao/bcn_layer` @ `c4755eef` (Jul-10), local repo.

Distributable tester report: `docs/Bannerlator-Mali-DX12-BCn-Tester.html`.

---

## 1. Goal & architecture

Add a new, **opt-in** graphics-driver entry **"Wrapper + compat + bcn"** for Mali, alongside (not
replacing) the existing "Wrapper + bcn_layer". It composes three leegao components as one guest stack:

```
game → VKD3D/DXVK → [ bcn_layer ]  (BCn/BC1–7 texture transcode + FormatProperties spoof)
                  → [ compat_layer ] (DX12/VKD3D feature emulation → D3D feature level 12.0)
                  → wrapper-leegao ICD (Vk-on-Vk bridge to the host Mali blob)
```

Both `bcn_layer` and `compat_layer` are **GLOBAL implicit Vulkan layers**, activated purely by their
`enable_environment` var — the same delivery model already proven for `bcn_layer`.

**Composition is safe (verified in source):** the only hook overlap between the two layers is
`GetPhysicalDeviceFeatures2`, and both only **set feature bits to true** (idempotent/additive) —
`textureCompressionBC` ends up true either way. `compat_layer` does **not** hook
`GetPhysicalDeviceFormatProperties/2` (only `bcn_layer` does); no conflict, no suppression env needed.

---

## 2. Component provenance & build

Neither binary has a matching GitHub Release — **both are build-from-source** (or CI-artifact, pinned).

| Component | Source | Build | GPU/driver floor |
|---|---|---|---|
| `libdxvk_mali_compat_layer.so` (arm64) | `leegao/compat_layer` @ `a32f0852` | `build_android_arm64.sh` — NDK **27.0.12077973**, `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DFORCE_GLSL=OFF`. Output `build_arm64/libdxvk_mali_compat_layer.so`. | **Mali r32p1+, Valhall only** (stricter than bcn) |
| `libbcn_layer.so` (arm64) — bump to Jul-10 | `leegao/bcn_layer` @ `c4755eef` | `build_android_arm64.sh` + `build_slang_shaders.sh`, NDK 27, arm64-v8a, platform 26, c++_static | non-Qualcomm (as today) |

- **Ship 64-bit only.** The 32-bit compat manifest is irrelevant — DXVK/VKD3D run as translated x86 PE but issue Vulkan through the arm64 host loader.
- **No runtime profile assets.** compat's spoof profile is codegen'd at build time into `src/generated_spoofed_profile.hpp` (committed) and compiled into the `.so`; grep found **zero** runtime `.json`/`profiles.zip` reads. Packaging is just the `.so` + manifest, same footprint as bcn.
- **Pin + checksum** both outputs before bundling.

### compat_layer GPU floor (authoritative)
- **Supported (Valhall):** G57, G68, G77, G78, G310, G610, G710, G615, G715, G720, G925.
- **Excluded:** G51/G71/G31 + T830/T880 (no VK1.3); G52/G76 (Bifrost, no descriptor-indexing); G72 needs r44p1+; any driver `< r32p1`.

---

## 3. Env-var contract (compat_layer)

Grepped all `getenv` in compat `src/`:

| Var | Emit? | Notes |
|---|---|---|
| `ENABLE_DXVK_MALI_COMPAT_LAYER=1` | **YES — required** | The loader enable-gate; activates the layer. |
| `COMPAT_EMULATE_PUSH_DESCRIPTORS` | no | Auto: on iff driver lacks it. Leave unset. |
| `COMPAT_EMULATE_NULL_DESCRIPTORS` | no | Auto-detect. Leave unset. |
| `COMPAT_EMULATE_SPARSE_BINDING=1` | **opt-in** | Per-game toggle for D3D12 tiled-resource titles. Default OFF. |
| `COMPAT_FORCE_MASKING` | **do not set** | Could mask feature bits bcn/DXVK expect. |
| `COMPAT_SPARSE_COMMIT_BUDGET` / `COMPAT_PROFILE_TRANSFERS` / `COMPAT_SAMPLE_GPU_COUNTERS` / `COMPAT_LOG_LEVEL` | no | Advanced/debug only. |

**Net:** emit `ENABLE_DXVK_MALI_COMPAT_LAYER=1` always (under the gate), and
`COMPAT_EMULATE_SPARSE_BINDING=1` when the user opts in. Everything else auto-detects.

---

## 4. App-integration checklist (exact anchors)

Identifier derived by `StringUtils.parseIdentifier` for label `Wrapper + compat + bcn` →
**`wrapper-compat-bcn`** (verified no collision with `wrapper-bcn_layer` / `wrapper-leegao`).

1. **Dropdown** — `res/values/arrays.xml` `graphics_driver_entries` (after line 26): add
   `<item>Wrapper + compat + bcn</item>`.
2. **Extraction** — `XServerDisplayActivity.java` after line 3289 (end of the `startsWith` chain): new
   `else if (graphicsDriver.startsWith("wrapper-compat-bcn"))` extracting `graphics_driver/wrapper-leegao.tzst`
   (same base as bcn_layer branch).
3. **Gate flags** — `XServerDisplayActivity.java:3422`: widen `isBcnLayerDriver` to also
   `startsWith("wrapper-compat-bcn")`; add `boolean isCompatDriver = …startsWith("wrapper-compat-bcn")`.
4. **Compat env** — inside `if (activateBcnLayer) { … }` (the non-Qualcomm gate at `:3470`), before the
   closing `}` at `:3504`:
   ```java
   if (isCompatDriver) {
       envVars.put("ENABLE_DXVK_MALI_COMPAT_LAYER", "1");
       String compatSparse = graphicsDriverConfig.get("bcnCompatSparse");
       if ("1".equals(compatSparse)) envVars.put("COMPAT_EMULATE_SPARSE_BINDING", "1");
   }
   ```
5. **EXTRA_LIBS_VERSION** — `XServerDisplayActivity.java:270`: bump `2 → 3` (heal branch `:3338-3345`
   re-extracts `extra_libs.tzst` into existing containers; mandatory or the new layer never lands).
6. **extra_libs.tzst repack** — `app/src/main/assets/graphics_driver/extra_libs.tzst`: add
   `usr/lib/libdxvk_mali_compat_layer.so` + `usr/share/vulkan/implicit_layer.d/libdxvk_mali_compat_layer.json`
   (**no profile files**). Full re-tar of the `usr` tree (zstd CLI can't append into a frame):
   ```bash
   cd staging   # holds current tree + the two new members
   tar --numeric-owner --owner=0 --group=0 -cf - usr | zstd -19 -o ../extra_libs.tzst -f
   ```
   Verify with `tar --zstd -tvf` that all original 8 members + the 2 new ones are present.
7. **UI** — `ContainerDetailScreen.kt:1603`: widen `isBcnLayer` to also `== "wrapper-compat-bcn"` and add
   `isCompatDriver`; add `bcnCompatSparse` state (near `:1611-1616`); render an "Emulate sparse binding
   (DX12)" checkbox gated on `isCompatDriver` inside the BCn panel (`:1757-1824`).
8. **Serialize key — trailing-`;` cluster caveat** — `ContainerDetailScreen.kt`: insert
   `"bcnCompatSparse=${if (bcnCompatSparse) "1" else "0"};" +` **inside** the bcn cluster (after the
   `bcnDebugLog=…;` line ~`:1845`), NOT after `gpuName`/`fdDevFeatures` (asymmetric `;` there).
9. **Strings** — `strings.xml` (near `:670-680`): `bcn_compat_sparse` = "Emulate sparse binding (DX12)"
   + `bcn_compat_sparse_hint`. Mirror into 22 locales if the parity rule applies.
10. **Env forwarding — verified, no change.** `VK_LAYER_PATH` (`GuestProgramLauncherComponent.java:360`)
    already points at `implicit_layer.d/`, so both manifests are discovered. Env passthrough
    (`…putAll(envVars)` → `ProcessHelper.exec` → `pb.environment().putAll`) drops only MANGOHUD vars —
    `ENABLE_*`/`BCN_*`/`COMPAT_*` pass through untouched.

---

## 5. Layer ordering
Recommended (not required for correctness, but keeps the `FormatProperties3` answer deterministic for
DXVK): **bcn_layer on top (closest to app), compat_layer below (closest to driver).** Enforce via a
`vk_loader_settings.json` (loader ≥1.3.234) if we want determinism; otherwise the benign `Features2`
overlap makes order irrelevant. Do **not** set `COMPAT_FORCE_MASKING`.

---

## 6. Open decisions for the user

1. **bcn_layer Jul-10 bump touches the PROVEN path.** `bcn_layer.so` is shared by both "Wrapper +
   bcn_layer" and the new driver (one binary in `extra_libs.tzst`). Bumping to `c4755eef` (Hades/DXVK2
   `FormatProperties2/3` fix + GIPA-interception fix — recommended, and it directly serves this driver's
   purpose) means the existing proven driver also gets the new binary → **re-test the proven path on Mali
   too.** Alternative (messier): ship two bcn_layer binaries. Recommend: single bump + re-prove both.
2. **GPU-floor gating (caveat 1).** The `!= 0x5143` vendor gate is necessary but **not sufficient** — a
   Bifrost G52/G76 or sub-r32p1 device passes it but fails compat's floor. Decide: (a) detect Mali
   arch/driver version and gate driver visibility/activation, or (b) offer to all non-Qualcomm and rely
   on the tester-report compatibility list + the game simply not launching. Needs a GPUInformation
   capability check — scope at build time.
3. **Keep-both vs. graduate-to-one.** Plan ships as *add a new entry* (both coexist). If real-hardware
   testing shows the new driver is a clean superset, a later stable can consolidate to one by **aliasing
   `wrapper-bcn_layer` → `wrapper-compat-bcn`** so existing shortcuts/configs migrate. Not now.

---

## 7. Risks
- Both `.so`s are build-from-source/CI-artifact (no release) — plan a pinned, checksummed build step.
- `extra_libs.tzst` grows by one `.so` (~APK size bump) — acceptable, note at release.
- compat spoofs a "feature-level-12.0" device whenever active, so the lean "Wrapper + bcn_layer" remains
  the cleaner choice for non-DX12 use — reinforces keeping both.
- Entirely unproven on hardware — must be validated on a Mali r32p1+ device (kylinzang, G57) before merge.

## 8. Test plan
Stage a build, hand to @kylinzang (G57, r32p1) + other Valhall testers. Verify: (a) a DX12/VKD3D title
launches + renders (the new capability); (b) a BCn DX11 title still works (no regression from the second
layer / the bcn bump); (c) A/B vs "Wrapper + bcn_layer" on the same game; (d) capture Wine debug log with
the debug toggle on failure. Green CI ≠ works — hardware verdict gates merge.

## 9. File:line anchor summary
- `res/values/arrays.xml:26-27` — dropdown entry
- `core/StringUtils.java:31-33` — identifier rule → `wrapper-compat-bcn`
- `XServerDisplayActivity.java:270` — `EXTRA_LIBS_VERSION` 2→3
- `XServerDisplayActivity.java:3282-3289` — extraction chain (new `else if` after 3289)
- `XServerDisplayActivity.java:3338-3345` — extra_libs heal branch
- `XServerDisplayActivity.java:3422` — `isBcnLayerDriver` widen + `isCompatDriver`
- `XServerDisplayActivity.java:3470-3504` — non-Qualcomm gate; compat env before 3504
- `ContainerDetailScreen.kt:202` — label→identifier store
- `ContainerDetailScreen.kt:1603` — `isBcnLayer` widen + `isCompatDriver`
- `ContainerDetailScreen.kt:1611-1616` — `bcnCompatSparse` state
- `ContainerDetailScreen.kt:1757-1824` — BCn panel; compat toggle
- `ContainerDetailScreen.kt:1841-1845` — serialize cluster (insert here, trailing `;`)
- `strings.xml:670-680` — new strings
- `GuestProgramLauncherComponent.java:360` — `VK_LAYER_PATH` (no change)
- `assets/graphics_driver/extra_libs.tzst` — repack (+ compat .so/manifest)
- `assets/graphics_driver/wrapper-leegao.tzst` — ICD base (unchanged)
