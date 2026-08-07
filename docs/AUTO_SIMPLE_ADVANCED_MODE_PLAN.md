# Auto (Simple) Mode + Advanced Mode — implementation plan

**Status:** DESIGN / not started. Written 2026-08-03. Grounded in a four-agent recon of the current
working tree (`main` @ `04174ab5`, post-2.9.3). Everything below is design; no code has been written.

**Goal in one sentence:** make Bannerlator behave like GameHub for the everyday user — *it picks the
right graphics stack, drivers and per-game settings automatically* — **without giving up** the
modular, user-tunable pickers we have today, which stay one tap away in an **Advanced mode**.

**Core design principle — a decision layer, NOT a rewrite.** GameHub's "automatic" feel is not a
different runtime; it is a *curated decision layer* (a server-driven per-game "scheme" + a baked
imagefs) sitting on top of an otherwise ordinary Wine/DXVK/Turnip stack. We already own the stack and
most of the decision inputs. So **Simple mode is a smart-defaults layer that writes the exact same
`Container` / `Shortcut` config the existing pickers write** — Advanced mode simply exposes those
pickers. Nothing in the runtime, the container format, or the wrapper system is removed or replaced.

**Confidence legend:** **PROVEN** = verified in shipped code / on device · **EXISTS** = code is
present today and reusable as-is · **WOULD-WORK** = sound by construction, not yet built · **NET-NEW**
= must be written · **RISK** = identified hazard.

**Related docs (do not duplicate — cross-reference):**
- `WRAPPER_MANAGER_PLAN.md` — the slot/catalog system Phase 1 delivery models on.
- `SMART_GAME_IMPORT_PLAN.md` — the add-game scan/import flow Phase 2/3 hook into.
- `bannerhub_config_system_reference.md` + `community-configs-guide.md` — the per-game recommendation source (Phase 2).
- `graphics-wrappers-guide.md` — the user-facing wrapper explanation Simple mode hides.

---

## 1. Why GameHub feels automatic, and what we already have

GameHub gets "it just works" from exactly two knowledge bases:

| GameHub mechanism | Bannerlator equivalent (today) | State |
| --- | --- | --- |
| Device→stack baked into a curated **imagefs** | GPU-vendor branching already in code (`GPUInformation`, the `0x5143` gate, `isCompatLayerSupportedGpu` allowlist) | **EXISTS but hardcoded/scattered** — needs to become data |
| Game→config **server "scheme"** (`componentIds` + `componentDependencies`, 100% server-driven, zero local detection — see `reference_gamehub_dependency_scheme_mechanism`) | The **community-config** network (first-party worker + KV + `games_canonical.json`), Steam-appid-keyed, plus **local** `DependencyDetector` folder scanning | **EXISTS** — richer than GameHub's in some ways (local detection + community coverage) |

The gap is not capability — it is **orchestration + curation**: we have the pieces, but they are all
user-tap-driven and the device→stack knowledge is hardcoded in `if` branches instead of a catalog.

### The three device tiers already encoded in the runtime
Recon confirms the graphics runtime already classifies every GPU into three tiers — Phase 1 just
lifts this out of code into data:

- **Tier A — Qualcomm / Adreno** (`GPUInformation.getVendorID(...) == 0x5143`, or `isAdroGPU`): native
  BCn, default graphics driver `wrapper`, adrenotools driver `turnip-sdk36`, DXVK `2.3.1-arm64ec-gplasync`.
  (`ContainerDetailViewModel.defaultGraphicsDriverForNewContainer():1246`, `DefaultVersion.getDxvkDefault():26`.)
- **Tier B — Valhall Mali on the allowlist** (`GPUInformation.isCompatLayerSupportedGpu()`, G-numbers
  57/68/77/78/310/610/710/615/715/720/925): eligible for the leegao bcn_layer + compat_layer / GameNative
  DX12 path; DXVK `1.10.3`.
- **Tier C — other non-Qualcomm** (older Mali / Xclipse / PowerVR): default `wrapper-gamenative`,
  bcn_layer transcode **without** the compat layer.

---

## 2. Architecture — the smart-defaults layer

```
                       ┌─────────────────────────────────────────────┐
   user adds a game →  │  AUTO RESOLVER  (NET-NEW orchestration)      │
                       │                                              │
   device probe  ──────┼─► DeviceProfileEngine ──► device_profiles   │
   (GPUInformation)    │        (Phase 1)          .json  (NET-NEW)   │
                       │                                              │
   steamAppId ─────────┼─► CommunityConfigFetcher ─► per-game config  │
   (ExeShortcutImporter│        (Phase 2, EXISTS)   (community net)   │
                       │                                              │
   game folder ────────┼─► DependencyDetector ────► component list    │
   (exe/gameDir)       │        (Phase 3, EXISTS)   (redist scan)     │
                       └───────────────────┬──────────────────────────┘
                                           │  merge → resolved config
                                           ▼
                    writes the SAME Container/Shortcut config the pickers write
                    (Container.putExtra / graphicsDriverConfig / dxwrapperConfig)
                                           │
                    ┌──────────────────────┴───────────────────────┐
             Simple mode                                     Advanced mode
       hides pickers, shows                            today's full editor
       "Handled automatically ✓"                       (all tabs + dialogs)
```

**Precedence when sources disagree** (highest wins): explicit per-game user override → community
config (appid-matched) → device profile (tier default) → hardcoded `Container.DEFAULT_*`. This mirrors
the existing seed precedence in `loadContainerData()` (`seed = container ?: template ?: DEFAULT_*`).

**Key invariant:** the resolver only ever calls existing write paths — `Container.putExtra`,
`graphicsDriverConfig` (semicolon `k=v`), `dxwrapperConfig` (comma `k=v`), and
`CommunityConfigApply.apply(...)` (which already does surgical per-field `putExtra` merges). No new
persistence format. This is what makes the whole feature low-risk.

---

## 3. Phase 0 — Simple / Advanced mode switch  *(foundation, cheapest, ships value alone)*

**Deliverable:** a global `simple_mode` boolean (default **ON** for new installs, **OFF** for existing
users so nothing changes under them), and conditional UI that hides tuning surfaces in Simple mode.

**Grounding / how:**
- Storage: `PreferenceManager.getDefaultSharedPreferences(context)` — same store `SettingsScreen.kt`
  already uses (`:101`). Add `var simpleMode by remember { mutableStateOf(prefs.getBoolean("simple_mode", …)) }`
  + a `Checkbox` row + `editor.putBoolean("simple_mode", simpleMode)` in `saveSettings()` (`:187`).
  Pattern is identical to `use_dri3` (`:118`, `:803`) / `dark_mode` (`:110`).
- UI gating: `ContainerDetailScreen.kt` **already** conditions its tab list on a mode flag
  (`defaultsMode`, lines 120-123). Extend the same pattern: in Simple mode render only **GENERAL**
  (screen size + game/wine version + a read-only "Graphics: handled automatically ✓" card with a
  "Why?" expander) and hide **ADVANCED** (box64/FEXCore/CPU affinity/gyro, `:1396`), the **DXWrapper**
  picker (`:607`), the **Renderer** dropdown (`:627`), and the **GraphicsDriverConfigDialog** BCn
  toggles (`:1942`).
- The per-game shortcut editor (`ShortcutsScreen.kt` Win Components / graphics rows) gets the same
  gate.
- **"Switch to Advanced" affordance** on every hidden surface so it is always one tap away.

**Why it delivers alone:** the per-arch **New Container Defaults** already seed sane values
(`NewContainerDefaults` + `seedArchDependentDefaults()`), so even before any smart engine, Simple mode
= "sane defaults, hidden complexity" — already a GameHub-like experience for the common case.

**Effort:** ~2–3 days. **RISK:** low (pure UI gating + one pref). Main care: ensure a container
*created* in Simple mode is still fully editable if the user later flips to Advanced (it is — the
config is the same shape).

---

## 4. Phase 1 — Device Profile engine  *(the imagefs equivalent — biggest new lift)*

**Deliverable:** a curated **`device_profiles.json`** catalog + a `DeviceProfileEngine` that maps the
probed GPU to a known-good graphics stack, replacing the scattered hardcoded tier logic.

**Grounding / how:**
- Detection surface already complete (`GPUInformation`, 8 methods): `getVendorID` (0x5143),
  `isAdrenoGPU`, `getRenderer` + `extractModelName` (Adreno/Mali/Immortalis/Xclipse/PowerVR parsing),
  `isCompatLayerSupportedGpu` (Valhall allowlist), `getVulkanVersion`, `enumerateExtensions`,
  `isDriverSupported`. The engine is a *rules-over-data* reader on top of these — no new probing.
- **Catalog delivery — model on `WrapperCatalog`/`WrapperManager`** (recon §2): ship
  `assets/graphics_driver/bundled_device_profiles.json`, check the remote
  `winlator-contents/main/device_profiles.json` with `catalogVersion` diffing (same `{catalogId,
  catalogVersion}` "update available" mechanism as bundled wrappers), offline-cache in `filesDir`
  (same as `WrapperCatalog.loadCached` NETWORK→CACHE→NONE).
- **Output = a `graphicsDriver` id + a `graphicsDriverConfig` string + a `dxwrapper` id + adrenotools
  `version`** — exactly the fields `extractGraphicsDriverFiles()` reads. The engine writes them via the
  same seed path `defaultGraphicsDriverForNewContainer()` uses today (generalize `:1246` from a 2-way
  Adreno/else branch to a catalog lookup).

**`device_profiles.json` schema (NET-NEW, proposed):**
```json
{
  "schemaVersion": 1,
  "catalogId": "bannerlator-device-profiles",
  "catalogVersion": 3,
  "matchers": [
    { "id": "adreno",        "when": { "vendorId": "0x5143" },
      "profile": { "graphicsDriver": "wrapper", "version": "turnip-sdk36",
                   "dxwrapper": "dxvk+vkd3d", "dxvkVersion": "2.3.1-arm64ec-gplasync",
                   "graphicsDriverConfig": { "bcnEmulation": "none" } } },
    { "id": "mali-valhall",  "when": { "vendorNot": "0x5143", "modelAllowlist": "compatLayer" },
      "profile": { "graphicsDriver": "wrapper-leegao", "dxvkVersion": "1.10.3",
                   "graphicsDriverConfig": { "bcnTranscodeAstc": "1", "bcnTranscodeEtc2": "0" } } },
    { "id": "non-qualcomm",  "when": { "vendorNot": "0x5143" },
      "profile": { "graphicsDriver": "wrapper-gamenative", "dxvkVersion": "1.10.3",
                   "graphicsDriverConfig": { "bcnTranscodeAstc": "1" } } }
  ],
  "fallback": { "graphicsDriver": "wrapper", "dxwrapper": "dxvk+vkd3d" }
}
```
Matchers are evaluated top-down, first hit wins; `when` predicates map 1:1 to existing
`GPUInformation` calls (`vendorId`→`getVendorID`, `modelAllowlist:"compatLayer"`→`isCompatLayerSupportedGpu`).
Shipping this as **data** means a new GPU family or a Charan-style Mali tweak is a catalog push, not an
app release — the single biggest structural win, and the thing GameHub can only do by re-baking imagefs.

**Effort:** ~5–8 days (engine + catalog schema + delivery + porting the ~3 hardcoded tier sites onto
it). **RISK (medium):** the profile must never regress today's proven Adreno defaults — pin Tier A to
the exact current values and add a golden test asserting the engine's Adreno output ==
`defaultGraphicsDriverForNewContainer()` output. Curation burden is real but bounded: ~dozens of GPU
families, not thousands of games.

---

## 5. Phase 2 — Per-game auto-config  *(reuses the community-config network as the "scheme server")*

**Deliverable:** on add-game, automatically fetch and apply the best community config, keyed on Steam
appid, with the Phase 1 device profile as fallback.

**Grounding / how — almost entirely EXISTS:**
- Single add-game funnel: `ExeShortcutImporter.addToShortcuts(...):40` — already carries `steamAppId`
  and is the one seam all exe/folder-import paths converge on (`ShortcutsViewModel.importExe():1046`,
  `importScannedGames():998`). Hook the resolver **right after** it returns.
- Match + apply machinery is done: `ShortcutsViewModel.matchCommunityConfigs():209` (appid-first exact
  match against `CanonicalGame.identity`, fuzzy fallback via `GameMatcher`), `applyCommunityConfig():399`
  → `ConfigTranslator.translate()` (`pc_ls_*` → shortcut extras) → `CommunityConfigApply.apply()`
  (surgical per-field `putExtra`, non-mutating `preview()` available for a "here's what we'll set" card).
- Delivery/staleness model for the index already exists: `CommunityConfigRepository` (offline-first
  `games_canonical.json`, 24h staleness, `filesDir` cache).
- **Scope guard already built in:** `CommunityConfigApply` treats `wineVersion`/Proton as
  container-only + advisory and **never writes it** (`:48`, `:293`) — so per-game auto-apply cannot
  break the container's Wine version. Only `OVERRIDABLE_KEYS` are touched.

**NET-NEW:** just the *trigger* (call match→apply automatically in Simple mode instead of on a chip
tap) + a per-game record of "auto-applied vs user-touched" so re-resolves never clobber a manual edit.
Measured appid resolution coverage is **64–80%** (from the cross-use work); misses fall through to the
Phase 1 device profile, so there is always a working config.

**Effort:** ~3–4 days. **RISK (low–med):** community configs are crowd-sourced and vary in quality —
gate auto-apply on a minimum vote/score threshold from `/list`, and always keep "Reset to safe
defaults" (Phase 3) reachable.

---

## 6. Phase 3 — Auto-components + self-heal

**Deliverable (3a — auto-deps):** in Simple mode, detect and install a game's required
redistributables without the user choosing chips.

**Grounding / how:**
- Detection EXISTS and is substantial: `DependencyDetector.detectForExe(exe):86` /
  `detect(gameDir):105` — folder scan of `_CommonRedist`/`vcredist`/`dotnet`/`directx`/`physx`/`xact`,
  year→vcredist and DLL→component mapping, `INSTALL_ORDER`. `PrefixInstalledDetector` backfills
  already-installed state.
- Installers are headless-callable: `ComponentInstaller.install(context, container, component, onProgress):40`
  (silent file-drop) and `ComponentExecInstaller.startInstall(...):126` / `resume():136`
  (session-based, cross-restart plan persistence). Catalog via `ComponentCatalog.load()` (remote
  `components.json`).
- **HARD CONSTRAINTS (recon §4) that shape the policy:**
  1. **Prefix must exist** before install (`ComponentInstaller.kt:45`, `ComponentExecInstaller.kt:127`)
     — so auto-deps run *after* first prefix creation, not at add-game time.
  2. **`install_exe`/`install_msi` components are unavoidably interactive** (launch a container session,
     restart the app, need click-through; silent flags are deliberately stripped). Only file-drop
     components and their `<name>_dll` variants install silently in-process.
  - **Policy:** Simple mode **auto-installs silent (file-drop / `_dll`) components** transparently, and
    for exec components shows a single batched "these need a quick installer, run now?" prompt rather
    than per-component chips. This is honest about the one thing that genuinely can't be fully silent
    under the current architecture.
- **NET-NEW:** extract the install-routing logic currently duplicated in the Compose click handlers
  (`ComponentsSheet.kt:184-206`, `RecommendedComponentsSection.kt:171-193`) into one reusable non-UI
  `AutoComponentInstaller` function; add first-launch orchestration.

**Deliverable (3b — self-heal):** a one-tap **"Reset to safe defaults"** on any launch-failure card
(black screen / early exit) that rewrites the container to the most-compatible stack (`wrapper` +
`wined3d` or the Tier fallback). This is something GameHub *cannot* offer because it has no user-facing
knobs — a genuine advantage of keeping the modular architecture underneath.

**Effort:** 3a ~4–5 days, 3b ~2 days. **RISK (med):** the prefix-exists + interactive-installer
constraints mean 3a cannot be 100% silent for .NET/vcredist installer-type components — set
expectations in UI copy; do not promise "fully automatic" for those.

---

## 7. What exists vs what is net-new (reuse ledger)

| Capability | State | Anchor |
| --- | --- | --- |
| Global settings store + toggle pattern | **EXISTS** | `SettingsScreen.kt:101,187` |
| Mode-conditional editor tabs | **EXISTS** (`defaultsMode`) | `ContainerDetailScreen.kt:120-123` |
| Per-arch seeded defaults | **EXISTS** | `NewContainerDefaults.kt`, `seedArchDependentDefaults():635` |
| Full GPU detection surface | **EXISTS** | `GPUInformation.java` (8 methods) |
| Device→stack tier logic | **EXISTS but hardcoded** | `defaultGraphicsDriverForNewContainer():1246`, `getDxvkDefault():26` |
| Bundled+remote catalog w/ version diffing | **EXISTS** (pattern) | `WrapperCatalog.kt`, `WrapperManager.java:382` |
| Per-game config fetch/match/apply | **EXISTS** | `communityconfigs/*`, `ShortcutsViewModel.matchCommunityConfigs():209` |
| Appid-keyed matching | **EXISTS** | `CanonicalGame.kt`, `steamAppId` extra |
| Redist folder detection | **EXISTS** | `DependencyDetector.kt:86` |
| Headless component install | **EXISTS** | `ComponentInstaller.install():40`, `ComponentExecInstaller.startInstall():126` |
| Single add-game funnel carrying appid | **EXISTS** | `ExeShortcutImporter.addToShortcuts():40` |
| `simple_mode` pref + UI gating | **NET-NEW** | Phase 0 |
| `device_profiles.json` + `DeviceProfileEngine` | **NET-NEW** | Phase 1 |
| Auto-trigger of match/apply on add | **NET-NEW** | Phase 2 |
| Reusable `AutoComponentInstaller` + first-launch orchestration + self-heal | **NET-NEW** | Phase 3 |

The ledger is deliberately lopsided: the overwhelming majority is reuse. The four net-new items are
orchestration + one data catalog — no new runtime, no new persistence format.

---

## 8. Sequencing, risks, and the honest trade-offs

**Order:** Phase 0 → 1 → 2 → 3. Each ships value independently; Phase 0 alone already feels
GameHub-like for the common case because the seeded defaults are already sane.

**Total rough effort:** ~16–22 engineering-days across the four phases.

**Standing risks:**
- **RISK — Adreno regression (Phase 1).** The device profile must reproduce today's proven Adreno
  defaults byte-for-byte. Mitigation: pin Tier A + golden test vs current seed output.
- **RISK — curation burden.** Device profiles (small, ~dozens of families) are *ours* to maintain;
  per-game configs lean on the crowd (64–80% coverage). Neither is 100% day one → the Phase-1 fallback
  and the Phase-3 self-heal are load-bearing, not optional.
- **RISK — the "fully automatic" promise.** Interactive installer components and the prefix-exists
  constraint mean vcredist/.NET can't be fully silent under the current component architecture. UI copy
  must be honest ("we'll set it up; some installers need one tap").

**The philosophical win.** GameHub trades tunability for simplicity and can offer only one mode.
Bannerlator gets *both*: Simple mode borrows GameHub's "hide the complexity" instinct while the
swappable-slot architecture underneath keeps Advanced mode — and capabilities GameHub structurally
cannot match (per-device driver swaps, community configs, one-tap self-heal). The Mali-simplify branch
(two toggles replacing a combo picker) is a miniature of exactly this instinct; this plan generalizes
it to the whole app.
