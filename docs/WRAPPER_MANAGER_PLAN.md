# Wrapper Version Manager — Implementation Plan

Tracking issue: [#132](https://github.com/The412Banner/Bannerlator/issues/132)
Status: **planned, not started.** Reference studied: GunaCharanTeja/WinlatorMali (`bionic-mali-1.1`).

---

## Plain-language summary

Let users bring their own graphics **wrappers** (the layer that makes Windows games talk to the phone's GPU) instead of only the ones we bundle — and have each wrapper's settings appear automatically.

**Five steps, safe → ambitious:**

1. **Update the wrappers we already ship** *(simple, safe)* — an **Update** button per built-in wrapper (swap in a newer file) + **Reset** (revert to ours). Nothing else changes, so nothing existing can break. Delivers the core ask: newer wrappers **without an app update**.
2. **Add brand-new wrappers** *(medium)* — import / name / delete arbitrary wrappers, which then appear in the driver menu. Care needed: the menu grows/shrinks, and deleting a wrapper a game uses must auto-reset that game.
3. **Each wrapper's settings appear automatically** *(adaptable)* — the app **detects what settings a wrapper supports by scanning the wrapper file itself** (no cooperation from the wrapper author, because none of them ship a "menu card") and matches those against **a dictionary we maintain** to build proper toggles/sliders. Import a wrapper → its real settings appear.
4. **Wrapper Workbench — make/save/share your own** *(ambitious)* — save a tuned settings preset as a named wrapper, compose a wrapper from existing parts (ICD + BCn/compat layers), curate its settings UI. Repackaging + presets only — **no compiling** a wrapper binary on-device. See the Step 4 section below.
5. **Curated wrapper catalog** *(medium)* — browse + download wrappers from other projects (WinlatorMali, GameNative, Ludashi, WinNative) right in the manager, mirroring the ReShade catalog. Downloads run through the smart import pipeline. Credit + license every source. See the Step 5 section below.

**How it degrades (no menu card — which is every wrapper today):**
1. It runs on its own defaults (nothing breaks).
2. **Auto-detect** finds the setting *names* inside the wrapper file; **our dictionary** turns the common ones into proper controls with labels/ranges — zero author cooperation.
3. Names we don't recognize → a plain **"set your own value"** field.
4. For popular wrappers we can bundle a full settings definition ourselves.
5. A wrapper-shipped "menu card" is used if one ever exists — bonus, never required.
So the settings appear from what the wrapper *actually supports*, not from what it's *named* (the current name-gating weakness).

**Effort / risk:** Step 1 ≈ a day, low risk. Step 2 ≈ a few days, medium. Step 3 = the biggest piece (auto-detect scan + the dictionary + generic settings UI/emission); worth it once imported wrappers are in real use.

**Testing catch:** we can build/verify the app parts (import, menu, settings) ourselves; **"does an imported wrapper actually run a game" needs a Mali/Exynos community tester** (we have no such device).

**Suggested order:** Step 1 + Step 2 are **shipped** (import/update/delete, dynamic dropdown, cascade), plus an interim patch so imported wrappers show the integrated-wrapper option superset. Step 3 (auto-detect + dictionary) replaces that superset with a precise per-wrapper list — do it once imported wrappers are in real use.

---

## Reality check — what the reference (WinlatorMali) actually does

Issue #132 asks for free-form import/rename/delete. The referenced app's screen (`ManageGraphicsDriversFragment`) is **not** that — it's a **fixed-slot updater**: 6 hardcoded slots, each with Info / **Update** (replace with a file of the *exact same name*) / **Remove** (drop the override). Overrides live at `filesDir/graphics_driver/<name>.tzst` and win over the bundled asset. No arbitrary add, no rename. So the requester is describing a capability their reference doesn't have — our Step 1 mirrors what actually exists; Step 2 is the true free-form ask.

**Interop:** their wrapper `.tzst` files are the **same format as ours** (zstd tar → `usr/lib/libvulkan_wrapper.so` + `usr/share/vulkan/icd.d/wrapper_icd.aarch64.json`, extracted into the imagefs root). Their manager validates nothing inside the archive (only external filename + an optional `version.txt` for display). So we can accept their wrapper files directly — caveat: a third-party wrapper may expect runtime env (e.g. `GALLIUM_DRIVER=zink`) we don't set; label imports advanced/experimental.

---

## Technical plan (grounded anchors)

### Shared foundation
- **`WrapperManager.java`** (new, `contents/`) — mirror `AdrenotoolsManager`. Storage `filesDir/graphics_driver/<identifier>.tzst`. Methods: `listSlots()`, `installOverride(name, Uri)`, `removeOverride(name)`, `enumerateImported()`, `readVersionInfo(file)` (adopt WinlatorMali's `version.txt` = `version:` / `notes:` lines via `TarCompressorUtils.readTextFile`). Recommend shipping a `version.txt` inside our own wrapper tzsts so slots show real versions.
- **Extraction precedence** — `XServerDisplayActivity.extractGraphicsDriverFiles()`, insert ~L3265 **before** the `startsWith("wrapper-…")` chain: if `filesDir/graphics_driver/<graphicsDriver>.tzst` exists, extract it (File overload `TarCompressorUtils.java:197`) and skip the bundled chain; else fall through unchanged. Override must be a **self-contained** wrapper (the bundled chain is not 1:1 name→file — bcn/compat reuse the leegao/gamenative base).

### Step 1 — Slot Update (WinlatorMali parity)
- **UI:** clone `AdrenoToolsScreen.kt` → `WrapperManagerScreen.kt` (already has file-picker launcher + install/remove dialogs + list rows). Rows = wrapper slots; buttons Info / Update (SAF `ACTION_OPEN_DOCUMENT`) / Remove override / toolbar Reset All.
- **File filter:** add `WRAPPER = arrayOf("tzst")` in `InAppFilePicker.kt:25`.
- **Validation:** verify the imported tzst contains `usr/lib/libvulkan_wrapper.so` before accepting.
- **Nav (4 files, mirror AdrenoTools):** `Screen.kt` (new `Screen.Wrappers`), `AppNavGraph.kt`, `AppDrawer.kt`, `MainActivity.kt` menu-id→route.
- **No** dropdown / `parseIdentifier` / `Container` / cascade changes. That is the whole point of Step 1.
- Files: `WrapperManager.java` (new), `WrapperManagerScreen.kt` (new), `XServerDisplayActivity.java` (precedence), `InAppFilePicker.kt`, `Screen.kt`, `AppNavGraph.kt`, `AppDrawer.kt`, `MainActivity.kt`, strings.

### Step 2 — Free-form manager
Three net-new pieces:
1. **Dynamic dropdown** — replace the two `getStringArray(R.array.graphics_driver_entries)` reads (`ContainerDetailViewModel.kt:249` **and** `ShortcutsScreen.kt:3739`) with `bundled + WrapperManager.enumerateImported()`. Both must build the identical list or the display↔identifier round-trip resets an imported pick to entry[0].
2. **Identifier collisions** — imported names run through `StringUtils.parseIdentifier` (strips trailing `(...)`, collapses spaces/`+`). Persist a canonical `identifier` in a per-wrapper `meta.json`; reject/suffix collisions with bundled ids (`wrapper-leegao`) or other imports.
3. **Delete/rename cascade (net-new)** — sweep every Container (`graphicsDriver` field → `saveData()`) and Shortcut (`graphicsDriver` extra → `saveData()`) referencing the removed/renamed id; reset to `DEFAULT_GRAPHICS_DRIVER` (delete) or new id (rename). The existing `AdrenotoolsManager.reloadContainers` only rewrites the config `version` key, so this is new (but a close analog to copy).
- Storage migrates to `contents/wrappers/<id>/` (`wrapper.tzst` + `meta.json`). Import package = `.zip` carrying `meta.json` + `wrapper.tzst` (mirrors `AdrenotoolsManager.installDriver`).

### Step 3 — Per-wrapper settings via AUTO-DETECT + our own DICTIONARY (menu card optional)

**Reframe (user, 2026-07-19):** DON'T build this around a wrapper-shipped "menu card" — **no wrapper has ever shipped one**, and most never will. So the primary engine must need zero cooperation from wrapper authors. The card, if one ever appears, is a bonus on top — never a dependency.

**Why this is needed:** wrapper settings are gated by the driver *identifier* (e.g. `isGamenative = graphicsDriver == "wrapper-gamenative"` in `GraphicsDriverConfigDialog`). A user-imported wrapper has a *custom* name, so name-gating can't know its capabilities. (Interim patch already shipped: an imported wrapper is treated as the integrated-wrapper superset — `isImported` → show all gamenative-style options. Step 3 replaces that superset guess with a precise, per-wrapper list.)

**The engine (in priority order):**
1. **AUTO-DETECT the setting names from the wrapper binary.** On import, scan `usr/lib/libvulkan_wrapper.so` (and the bcn/compat `.so`s) for readable env-var-name strings matching the family vocabulary (`WRAPPER_*`, `ENABLE_*`, `BCN_*`, `COMPAT_*`, `MESA_VK_*`, `GALLIUM_*`, …). This is the **proven `strings`-on-binary trick** (used this session to find GameNative's `WRAPPER_DRIVER_ID`/`WRAPPER_SAFE_CREATE_DEVICE`). Store the detected key list in the wrapper's `.meta` at import time (so it's read once, off the launch path). Works on EVERY wrapper, no author involvement. Caveat: detection yields NAMES only, not types/ranges/labels — and may include a few false positives (label the panel "detected — advanced").
2. **MATCH detected names against a settings DICTIONARY we maintain.** A built-in table `key → { type: toggle|slider|dropdown|text, label, hint, default, min/max/step or choices }` covering the common Winlator-family vocabulary (`WRAPPER_VK_VERSION` = version string, `WRAPPER_EMULATE_BCN` = 0..3, `WRAPPER_BCN_ASTC` = toggle, present mode, extension blacklist, `COMPAT_*`, …). A matched key renders a **proper control**; the dictionary is OURS to grow (one line per new common setting → every wrapper that uses it gets a nice control). This is what turns raw detected names into a polished UI with zero author cooperation.
3. **UNKNOWN detected names →** a plain "set your own value" text field (still usable by power users).
4. **App-bundled cards for popular wrappers (optional polish):** for GameNative / Charan's Mali etc., WE can hand-write a full schema bundled in the app, keyed to the wrapper, to override/augment the auto-detected list for a perfect UI. No author needed.
5. **Wrapper-shipped card (optional bonus):** if a wrapper ever includes a settings schema (e.g. `settings.json` in the `.tzst`), use it verbatim. Never required.

**Plumbing (unchanged from before):**
- **Dynamic settings UI:** render controls from the resolved option list (auto-detect ∪ dictionary ∪ bundled-card) in `GraphicsDriverConfigDialog`. Store values as arbitrary keys in `graphicsDriverConfig` (already a `;`-separated k=v string — no storage change).
- **Generic env emission:** emit `key=value` from the resolved settings at launch (generalizes today's hardcoded per-var emission in `XServerDisplayActivity`). Unknown env vars are ignored by wrappers → safe.
- **For bundled wrappers:** keep today's precise curated gates (we already know their capabilities); auto-detect + dictionary is primarily for imports. (Could later migrate bundled ones to the dictionary too, to delete the hardcoded `isGamenative`/`isBcnLayer` gates.)

**Effort:** the biggest step. The dictionary + auto-detect scan + dynamic-UI/generic-emission refactor. Worth doing once imported wrappers are actually in use; the interim `isImported` superset covers the common case until then.

### Step 3 (expanded) — the "Smart Wrapper Manager" (user, 2026-07-19)
Goal: the manager must be **very smart** — auto-detect settings, options, GPU (Adreno/Mali/Xclipse/PowerVR), and show/create exactly the settings a wrapper *requires to work properly*, hiding what it doesn't have or can't use. This **replaces all name-gating** (`isGamenative`/`isBcnLayer` exact-name checks in `GraphicsDriverConfigDialog`) with data-driven capability detection. Four detection layers, run per wrapper:

1. **Setting support (env-var scan + dictionary)** — as above: scan the wrapper `.so`(s) for env-var-name strings, match our dictionary → proper controls; unknown → generic field.
2. **Capability detection (what the `.tzst` contains)** — inspect the archive at import (store results in `.meta`): `usr/lib/libvulkan_wrapper.so` → it's an ICD; `libbcn_layer.so` → show the BCn Layer Settings block; `libdxvk_mali_compat_layer.so` → show DX12/compat options (sparse, GameNative-engine, etc.). This is what makes an imported compat+bcn wrapper (e.g. "112") show the BCn + compat blocks WITHOUT a hardcoded name — it's detected from contents.
3. **GPU awareness (`GPUInformation`)** — read real vendor/model: Adreno/Qualcomm (0x5143), Mali (+ Valhall r32p1+ allowlist), Xclipse, PowerVR. Hide/grey options that are inert on this GPU WITH a reason (e.g. "BCn does nothing on Adreno — native BCn"; "DX12/compat needs Valhall Mali r32p1+"). Mirrors the existing XSDA activation gates (`activateBcnLayer = getVendorID != 0x5143`, `isCompatLayerSupportedGpu`).
4. **Intersection + honest emission** — show settings = (wrapper supports) ∩ (GPU can use); warn on mismatch; emit only env the wrapper+GPU will honor. No dead toggles shown, no live ones hidden.

**Branch unification is NOT required (revised 2026-07-20).** The Mali branch's compat/DX12 code is *hardcoded, name-gated* activation (emit specific env for the driver literally named `wrapper-compat-bcn`) — exactly the name-gating the smart manager replaces. A fully-generic manager (Layer 1 below) activates a compat/DX12 wrapper WITHOUT that hardcoded logic: an import's `.tzst` already extracts its layer `.so` + manifest into the prefix; capability/env-scan shows the settings; **generic emission** emits `ENABLE_DXVK_MALI_COMPAT_LAYER=1` + `COMPAT_*`/`WRAPPER_*` from the detected settings; the layer activates via `VK_LAYER_PATH` which **main already sets** (for vkBasalt/ReShade, same implicit-layer mechanism). So Layer 1 supersedes the merge. The ONLY reasons to actually merge: (a) ship the Mali branch's *bundled* "Wrapper + compat + bcn" dropdown entry as a first-class option in this build, or (b) reuse the **Valhall Mali DX12 GPU allowlist** (`isCompatLayerSupportedGpu`) — but (b) is a small data table that can just be **copied over** (few lines), not merged.

**Build order for the smart manager:** (a) capability-detect at import → `.meta` ✅ DONE; (b) replace name-gates in `GraphicsDriverConfigDialog` with capability + GPU checks ✅ DONE; (c) detail view + pre-import inspection ✅ DONE; (d) **Layer 1 — env-var scan + dictionary + dynamic settings UI + generic emission** (the current work); (e) optional: copy the Valhall DX12 GPU allowlist as data. Layer 1 is the largest single piece and the one that makes the manager truly generic (handles compat/DX12 and any future wrapper by detection, not hardcoded code).

### Step 3 — Detail / inspection UI (user, 2026-07-19)
Surface the detection results in the UI (this SITS ON TOP of the capability/GPU engine — build after it):
- **Per-wrapper detail** — each installed-wrapper card gets an **expandable box / detail view**: version+notes (from `version.txt`), detected capabilities (ICD / BCn layer / compat layer), the settings it supports, and GPU applicability ("BCn — Mali/non-Qualcomm only; inert on this Adreno").
- **Pre-import inspection page** — when the user picks a `.tzst` to import, show a **detail/preview screen BEFORE naming+adding**: what it is (ICD? layer bundle?), its detected settings/options, and **what may need to be created/added to use it properly** (e.g. "contains a BCn layer — will show BCn Layer Settings; requires a non-Adreno GPU to take effect", "detected settings: WRAPPER_VK_VERSION, WRAPPER_EMULATE_BCN, …", "unknown keys → add-your-own-value"). Then a Name field → Add. So the user decides with full info instead of importing blind.
- Both read the same capability/`.meta` + env-scan data the engine produces; no new detection logic, just presentation. Lives in `WrapperManagerScreen.kt` (cards + a new inspection dialog/screen in the import flow) — disjoint from the engine's files except reading `WrapperManager.capsFor()`.

## Step 4 — Wrapper Workbench / curation (user, 2026-07-20)
Turn the manager from an import/update tool into a **workbench**: make/save/share your own wrappers. Builds directly on Step 3 (the settings detection + generic emission are exactly what presets/compose reuse).

**❌ Hard limit — no compilation.** A wrapper's `libvulkan_wrapper.so` is compiled C (source + NDK). The app has no compiler, so a *brand-new wrapper binary from nothing is impossible on-device.* Everything below is **repackaging existing binaries + presets**, never compiling.

**✅ Three buildable forms:**
1. **Settings preset → named custom wrapper.** Take a wrapper (bundled or imported) + a tuned set of the Step-3 detected env-var values and **save it as a new named, selectable, shareable wrapper** = *a wrapper reference + a baked-in settings profile.* Storage: a new `.meta` variant (`baseWrapper=<id>` + the preset k=v) OR a thin `.tzst` that re-points to a base + carries a settings file; the launch path resolves base + applies the preset via the existing generic emission. Lowest-effort, highest-value; natural extension of Step 3.
2. **Compose from parts.** A picker to assemble a new `.tzst` from existing components: pick an ICD (`libvulkan_wrapper.so` from any wrapper) + optionally a BCn layer (`libbcn_layer.so`, e.g. the Fcharan fork) + a compat layer (`libdxvk_mali_compat_layer.so`) + manifests → tar+zstd in-app → name → add. This is exactly the manual `wrapper-compat-bcn.tzst` build (leegao ICD + bcn + compat) done in-UI. Uses `TarCompressorUtils.compress`; capability/env re-scan runs on the result like any import.
3. **Curate the settings UI ("our own card").** Override/augment what the manager shows for a wrapper — pin labels, hide noisy detected keys, add hints, set defaults — i.e. author an app-side settings card per wrapper (extends `WrapperSettingsDictionary` with a per-wrapper override map). Improves any wrapper's UI without touching its binary; also the mechanism to hand-write perfect cards for popular wrappers (GameNative, Charan's Mali).

**Editing bundled wrappers:** can't recompile the `.so`, but override (Update, shipped) + a settings preset ≈ "your own version" of a bundled wrapper.

**Effort/order:** (1) preset→named wrapper first (small, reuses Step 3 emission), (2) curate-card next (dictionary override), (3) compose-from-parts last (in-app repackaging UI — the biggest). Sharing (export a preset/composed wrapper `.tzst`) rides the existing `.tzst` format + community-config plumbing.

## Step 5 — Curated wrapper catalog (downloadable) (user, 2026-07-20)
Browse + download curated wrappers from other projects (WinlatorMali, GameNative, Steven's Ludashi, WinNative, …) inside the manager — mirroring the **ReShade catalog** exactly.

**Mirror the ReShade infra (already trusted):** ReShade effects come from `reshade.json` on `The412Banner/winlator-contents` (`ReshadeCatalog.URL = https://raw.githubusercontent.com/The412Banner/winlator-contents/main/reshade.json`); each entry has `id/name/description/author/license/url(.tzst release asset)/fileSize/checksum(MD5)/version`. `ReshadeCatalog` fetches+caches the JSON; `ReshadeDownloader` downloads the entry `url`, verifies MD5, extracts via `TarCompressorUtils`.

**App side (clone the two classes + a browser):**
- `WrapperCatalog` (clone `ReshadeCatalog`) → `wrappers.json` on the SAME `winlator-contents` repo. Entry adds `gpuTargets` (Mali/Exynos/Adreno/PowerVR/all) so the browser can flag applicability against the detected GPU.
- `WrapperCatalogDownloader` (clone `ReshadeDownloader`) → download the `.tzst`, verify checksum, then **feed it straight into `WrapperManager.importWrapper`** (File→Uri or a new File overload) so a downloaded wrapper runs through the SAME import pipeline → capability detection + env-scan + smart settings + inspection all apply for free. Land it as an import (with the catalog `name`).
- **Browser UI**: a "Download wrappers" section/tab in the manager (mirror the ReShade catalog browser) — cards with name/author/license/size/GPU-target + a Download button (progress); on this GPU, grey/note entries whose `gpuTargets` don't include it ("Mali-only — inert on Adreno").

**Catalog content (separate deploy, licensing-gated):** seed `wrappers.json` + upload the `.tzst` assets to a `winlator-contents` release. ⚠️ **Redistribution:** these are OTHER projects' binaries — each entry MUST carry accurate `author` + `license` (the entry model already has both). Open ones (GameNative, leegao upstream) → include with credit. Forks with murky terms (Fcharan/WinMali BCn fork — no source/vague notes) → get maintainer OK or LINK the source rather than re-host. Sources to pull from: WinlatorMali APK assets (extracted this session), GameNative (`wrapper-gamenative`), Steven's Ludashi + WinNative (pull from their APKs). Don't ship anything whose license we can't honor.

**Effort/order:** app-side catalog+downloader+browser is a near-clone of ReShade (small-medium). Catalog content is curation + a deploy to `winlator-contents` (license review per entry). The download→import reuse means zero new detection work.

## Step 6 — Beyond env vars: config-file drops + app-side hints (user, 2026-07-20)
**Goal:** close the two wiring gaps that keep "import any wrapper from any project and it just works" from being fully true. Today the manager is a *pure env-var* engine (detect key → control → store under raw key → XSDA emits `KEY=value`). That covers the large majority of Winlator-family knobs (they're all `getenv()`), but two classes of wrapper aren't reachable:

### 6a — Config-file mechanism (non-env-var settings)
Some wrappers read a **file**, not the environment: `dxvk.conf`, a `*.ini`, or knobs baked into the ICD JSON. Wire a generic "config file" path parallel to the env path:
- **Detection:** extend the import scan — when a wrapper archive contains a recognised config template (`dxvk.conf`, `*.conf`, `*.ini` under `usr/share` or next to the ICD), record it in `.meta` (`configFiles=`). Also allow the dictionary to mark a `SettingDef` as `target = ENV | CONFFILE(path,key)` so a curated control can write into a file instead of the environment.
- **Storage:** keep storing values in `graphicsDriverConfig` under a namespaced key (`conf:dxvk.conf#dxgi.maxFrameLatency`) so it round-trips through the existing per-game config with zero schema change.
- **Emission:** at launch, before the container starts, **materialise the file into the prefix** (write/patch `<prefix>/drive_c/.../dxvk.conf` or `$WINEPREFIX` root) from the stored `conf:*` values, layering over the wrapper's shipped template. Mirror the extraction-precedence idiom already in `extractGraphicsDriverFiles` (override file in `filesDir` wins). Anchor: `XServerDisplayActivity.extractGraphicsDriverFiles` / prefix setup.
- **UI:** these show in the SAME "Detected settings" list; a `CONFFILE` def just routes on save. A raw unknown `*.conf` with no dictionary entry → a "Edit <file>" free-text pane (power users), never silently ignored.

### 6b — Imports that carry app-side hints (a wrapper manifest)
Some wrappers need **app-side plumbing**, not just an env value — e.g. the Mali DX12 stack needs the GameNative engine + `WRAPPER_DRIVER_ID` spoof + a specific renderer; adrenotools driver pairing needs a driver `.so` alongside. Let an import **declare its needs** so the manager can honour or at least warn:
- **Manifest:** optional `wrapper.json` inside the `.tzst` (or a catalog-entry field), schema: `{ requires: { engine?: "gamenative", renderer?: "vulkan|vk-mali", driverId?: "24", minDriver?: "mali-r32p1", gpu?: ["mali","exynos"] }, recommends: { env: {K:V}, conf: {...} }, notes }`. Absent manifest → today's behaviour (pure detection), so nothing regresses.
- **On import/activate:** the inspection page renders `requires`/`recommends` as a checklist ("This wrapper wants: GameNative engine ✅ available · Mali GPU ❌ this is Adreno — inert"). One-tap "Apply recommended" seeds the `env`/`conf` defaults. Hard-incompatible (`gpu` mismatch) keeps the existing `Mali only` chip + inert warning.
- **On launch:** XSDA reads the manifest for the active wrapper and wires the app-side bits it owns (select renderer, set engine toggle, pass `driverId`) instead of relying on the user to have flipped three separate switches. Anchors: the existing `useGamenativeEngine`/`compatUseGamenative` + renderer selection in XSDA; `WrapperManager` `.meta` gains `manifest=` (verbatim JSON) captured at import.

**Effort/order:** 6b (manifest + checklist + launch wiring) is the higher-value half — it's what makes "import the Mali DX12 wrapper and it configures itself" real, and it reuses the engine/renderer plumbing that already exists. 6a (config-file drop) is a cleaner-but-smaller win, mostly for DXVK-conf-style knobs. Both are additive: no manifest / no conf template ⇒ exactly today's pure-env-var behaviour. Ship 6b first if the driver is Mali DX12 adoption; 6a first if DXVK-conf tuning is the ask.

**Risks:** (1) launch-time file materialisation must be idempotent + prefix-scoped (never leak across containers); (2) a manifest is author-supplied — treat `requires` as advisory, never auto-flip destructive app state without the "Apply recommended" tap; (3) keep the manifest OPTIONAL forever so the generic detection path stays the floor.

### ContentsManager alternative (considered, not chosen)
Adding a `CONTENT_TYPE_WRAPPER` to `ContentsManager`/`ContentProfile` would give enumerate/install/remove for free, but it copies files by manifest to explicit targets (not "extract a `.tzst` into imagefs root at launch") and has no container cascade — so a bespoke `WrapperManager` mirroring `AdrenotoolsManager` is lower-risk and idiom-matching. Keep ContentsManager in mind only if we later want remote-catalog/download parity.

### Risks (ranked)
1. Dynamic-dropdown drift (Step 2) — the two list builders must stay in lock-step.
2. Cascade correctness (Step 2) — a missed reference leaves a container pointing at a deleted wrapper.
3. Interop env contract (Step 1+) — imported third-party wrappers may need env we don't set; label experimental.
4. Extraction precedence subtlety — overrides must be self-contained.

### Key files
`contents/AdrenotoolsManager.java` · `ui/screens/AdrenoToolsScreen.kt` · `res/values/arrays.xml` (`graphics_driver_entries`) · `ui/screens/ContainerDetailViewModel.kt` · `ui/screens/ContainerDetailScreen.kt` · `ui/screens/ShortcutsScreen.kt` · `XServerDisplayActivity.java` (`extractGraphicsDriverFiles`) · `container/Container.java` · `core/StringUtils.java` · `core/TarCompressorUtils.java` · `util/InAppFilePicker.kt` · nav `ui/{Screen.kt,AppNavGraph.kt,AppDrawer.kt}` + `MainActivity.kt` · `assets/graphics_driver/wrapper-*.tzst`.
