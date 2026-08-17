# 🎨 Graphics wrappers & drivers — which one should I use?

*Covers the built-in **Graphics Driver** choices and every entry in the downloadable **Wrapper
Version Manager** catalog (18 wrappers, as shipped with 2.8).*

If you just want the answer, jump to **[Which one should I pick?](#which-one-should-i-pick)**.

---

## Contents

- [What a wrapper actually is](#what-a-wrapper-actually-is)
- [Which one should I pick?](#which-one-should-i-pick)
- [The built-in Graphics Driver options](#the-built-in-graphics-driver-options)
- [The downloadable catalog](#the-downloadable-catalog)
- [⚠️ Many of these are literally the same file](#-many-of-these-are-literally-the-same-file)
- [The BCn layer slot](#the-bcn-layer-slot)
- [Importing, updating and removing](#importing-updating-and-removing)
- [Troubleshooting](#troubleshooting)
- [Credits](#credits)

---

## What a wrapper actually is

Your games talk **DirectX**. Your phone speaks **Vulkan**. In between sits a stack:

```
Game (DirectX) → DXVK / VKD3D (→ Vulkan) → the WRAPPER → Android's Vulkan driver → GPU
```

The **wrapper** (a Vulkan ICD — "installable client driver") is the last translation step before your
device's real GPU driver. It smooths over the differences between what DXVK expects from a desktop
Vulkan driver and what an Android GPU driver actually provides.

Because different projects in the Winlator family patch different things, swapping the wrapper can
fix a black screen, a crash, or missing textures — which is why the catalog exists.

**This is separate from the *renderer*** (Vulkan / OpenGL / SurfaceFlinger / VirGL), which is about
how the finished image gets on screen. Different setting, different job.

---

## Which one should I pick?

**Start here.** The default is deliberately chosen to work; only change it if something's broken.

| Your GPU | Use this | Why |
|---|---|---|
| **Qualcomm Adreno** (Snapdragon) | **Wrapper** (default) + a **Turnip** driver (`turnip-sdk36`, or a newer one you install) | Adreno decodes BC textures in hardware. Nothing extra needed — and BCn emulation is *automatically disabled* on Adreno, because forcing it only costs performance. |
| **ARM Mali** / **Samsung Xclipse** / **PowerVR** | **Wrapper + bcn_layer** | These GPUs have **no hardware BC-texture support**. Without transcoding, many DirectX games crash, run out of VRAM, or show black/missing textures. |
| **Valhall-class Mali** wanting **DX12** | **Wrapper + compat + bcn** *(experimental)* | Adds leegao's DX12 compat layer on top. See the hardware list below — it's genuinely restricted. |
| Nothing renders at all | **VirGL** | OpenGL fallback. Slow, but a useful "does anything work?" test. |

**How do I know which GPU I have?** The in-game performance HUD can show your GPU model, and the
wrapper catalog automatically flags entries that don't apply to your device.

---

## The built-in Graphics Driver options

Set per container (and overridable per game) under **Graphics Driver**.

### 🟢 Wrapper *(default)*
The standard Bionic Vulkan wrapper. Works on everything; the right starting point for any device.

### 🟢 Turnip
Mesa's open-source **Adreno** driver, loaded via adrenotools — often a large win over the stock
Qualcomm driver for DXVK/VKD3D. Adreno only.

Bannerlator bundles Turnip builds (**`turnip-sdk36`** is the Adreno default), and you can **install
newer ones yourself** from the driver repos listed in-app under the Adrenotools driver picker —
including [Banners-Turnip](https://github.com/The412Banner/Banners-Turnip). If a game misbehaves on
the bundled build, trying a newer Turnip is usually the first thing worth doing on Adreno.

### 🟡 Wrapper + bcn_layer — *the Mali/Xclipse fix*
The leegao ICD **plus** leegao's BCn transcode layer, which decodes BC textures on the GPU at
runtime. This is what makes BC-texture games run on GPUs without hardware BC support.

Device-proven on **Mali-G57 (Helio G99)**, where *MiSide* went from crashing to rendering at ~34 fps
with zero buffer errors.

Comes with a **BCn Layer Settings** panel: force-decode, ETC2/ASTC transcode, image-view mode and
debug logging.

> **Automatically inert on Adreno.** Qualcomm GPUs (vendor `0x5143`) have native BC support, so
> Bannerlator forces BCn emulation **off** there. Before 2.6.1 it was emitted for every GPU, which
> made Adreno 7xx do a pointless per-texture transcode — a global slowdown that broke BC-heavy DX11
> games like *Skyrim AE*. Fixed; Mali / Xclipse / PowerVR behaviour was untouched.

### 🟡 Wrapper-gamenative — *experimental*
GameNative's wrapper with BCn baked into the wrapper itself rather than as a separate layer. In
practice this path is Adreno-oriented. Labelled experimental.

### 🔴 Wrapper + compat + bcn — *Mali DX12, experimental, opt-in*
The full Mali DX12 stack: leegao ICD + BCn layer + the **DXVK-Mali compat layer**, with a
**"Use GameNative engine (DX12)"** toggle. Reports D3D feature level 12.0.

**Hardware requirement — this one is genuinely restricted.** The compat layer needs a **Valhall
Mali at driver r32p1 or newer**. Because the runtime driver version can't be detected, Bannerlator
gates on the GPU model against an allowlist:

> **Mali-G57, G68, G77, G78, G310, G610, G615, G710, G715, G720, G925** and **Immortalis-G715, G720,
> G925**

Bifrost and Midgard Mali pass the "not Qualcomm" vendor check but fail the layer's own floor, so they
are excluded deliberately. **Adreno is hard-off.** BCn transcoding still works on any non-Qualcomm
Mali even outside this list — it's only the *DX12* half that's gated.

⚠️ **The DX12 half is not yet fully proven on hardware.** Treat it as a test path and report back
with logs. It does nothing unless you select it.

### ⚪ VirGL
OpenGL fallback for older titles and for diagnosing "nothing renders at all."

---

## The downloadable catalog

**Where:** any container's **Graphics Driver** row → the ☁ icon → browse, download, install.

Entries flagged **"Mali only"** won't do anything on your GPU — the flag is advisory and never blocks
a download. Installed entries are remembered across restarts and show **"Update available"** when a
newer build exists.

### Bannerlator's own builds

| Wrapper | Author | Targets |
|---|---|---|
| **Wrapper (default)** | Bruno / Winlator lineage + Bannerlator | All |
| **Wrapper (original)** | [Bruno (brunodev85)](https://github.com/brunodev85/winlator) | All |
| **Wrapper (legacy)** | [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator) (Winlator Bionic) | All |
| **leegao** | [leegao](https://github.com/leegao) + Bannerlator | All |
| **GameNative** | [GameNative](https://github.com/utkarshdalal/GameNative) | All |
| **BCn layer (leegao)** | [leegao](https://github.com/leegao/bcn_layer) | Mali · Xclipse · PowerVR |
| **Wrapper + compat + bcn (Mali DX12)** | leegao + Bannerlator | Mali · Xclipse *(Valhall r32p1+)* |

### From [WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali) — GunaCharanTeja / Charan

| Wrapper | Notes |
|---|---|
| **Wrapper** | Their default. **Same file** as Bannerlator's default. |
| **Wrapper v2** | **Same file** as the Bruno original. |
| **leegao** | Their own leegao build — genuinely differs from Bannerlator's and WinNative's. |
| **GameNative** | **Same file** as Bannerlator's GameNative. |
| **BCn layer (Fcharan fork)** | A **fork of leegao's BCn layer** by Fcharan / WinMali-Dev, tuned for performance/stability with extra ASTC-quality, staging-cache and queue-throttle controls. Worth trying if leegao's stock layer is unstable for you. |

### From [WinNative](https://github.com/WinNative-Emu/WinNative)

| Wrapper | Notes |
|---|---|
| **Wrapper** | **Same file** as the Bruno original. |
| **GameNative** | WinNative's own build — **differs** from Bannerlator's/WinlatorMali's. |
| **leegao** | WinNative's own build — **differs** from the others. |

### Upstream sources

| Wrapper | Author | Notes |
|---|---|---|
| **GameNative Wrapper** | [GameNative](https://github.com/utkarshdalal/GameNative) | **The canonical, newest build** (20260719, GameNative #1743): 32-bit game support, ICD-side push-descriptor and device-fault support. Newer than the July-5 build the forks bundle. |
| **Wrapper (Steven)** | [StevenMXZ](https://github.com/StevenMXZ/Winlator-Ludashi) (Winlator-Ludashi) | Ludashi's default. **Same file** as the Bannerlator/WinlatorMali default. |
| **Winlator Bionic Wrapper** | [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator) | The bionic upstream that Ludashi and other bionic forks descend from. **Same file** as Bannerlator's "legacy". |

---

## ⚠️ Many of these are literally the same file

This is the single most useful thing to know before you spend an evening testing wrappers.

Several catalog entries are **byte-identical** across projects — they're listed separately for
provenance and credit, not because they behave differently. Verified identical groups:

| These are all the same file | |
|---|---|
| Bannerlator **Wrapper (default)** = WinlatorMali **Wrapper** = Ludashi **Wrapper (Steven)** | |
| Bannerlator **Wrapper (original)** = WinlatorMali **Wrapper v2** = WinNative **Wrapper** | *(Bruno's original)* |
| Bannerlator **Wrapper (legacy)** = Pipetto **Winlator Bionic Wrapper** | |
| Bannerlator **GameNative** = WinlatorMali **GameNative** | |

**These genuinely differ and are worth testing against each other:**
- The three **leegao** builds — Bannerlator's, WinlatorMali's and WinNative's are all different.
- **WinNative's GameNative** vs the others.
- The **upstream GameNative Wrapper**, which is newer than every fork's bundled copy.
- **leegao's BCn layer** vs the **Fcharan fork**.

So: if switching from "WinlatorMali Wrapper" to "Bannerlator Wrapper" changed nothing, that's
expected — you loaded the same bytes.

---

## The BCn layer slot

The BCn layer is **not** a wrapper — it's a Vulkan *layer* that sits alongside one. It has its own
slot in the manager, and BCn entries (leegao's, or the Fcharan fork) import **into that slot**, not
over your wrapper.

That's why the manager shows it as "BCn layer" with its own settings rather than as an ICD. The
shared **"Extra libraries"** payload is likewise hidden from the manager — it isn't a wrapper either.

---

## Importing, updating and removing

Wrappers are `.tzst` archives. From the manager you can:

- **Import** any `.tzst` — from another project or your own build. It's validated on import and then
  appears in the Graphics Driver dropdown.
- **Download from the catalog** — installs through the exact same pipeline as a hand-picked file.
- **Update** — one tap when a newer catalog version exists. Works for **bundled** wrappers too, so a
  built-in can be refreshed without an app update.
- **Reset / Edit settings / Delete / Details** — from each entry's ⋮ menu.
- **Inspect before importing** — see what an archive actually is before naming it.

**Settings are auto-detected.** The manager reads what each wrapper genuinely supports (ground-truthed
against the binary's real environment-variable usage) and builds real toggles, sliders and dropdowns
with plain-English labels — filtering out driver internals and log noise rather than dumping every
variable at you.

Everything here is **app-side**: no ImageFS reinstall, and updated layers re-extract on next launch.

---

## Troubleshooting

**Black screen in a game.**
Try a different wrapper — this is the classic case the catalog exists for. On Adreno also try
switching Turnip build; on Mali make sure you're on **Wrapper + bcn_layer**.

**Missing / black textures, or crashes on texture load (Mali, Xclipse, PowerVR).**
You need BCn transcoding: **Wrapper + bcn_layer**. If it's unstable, try the **Fcharan fork** of the
BCn layer.

**Everything got slower after I enabled BCn on a Snapdragon.**
You don't need it — Adreno has native BC support, and Bannerlator disables BCn emulation on Qualcomm
automatically. Go back to the plain **Wrapper**.

**I picked "Wrapper + compat + bcn" and DX12 doesn't work.**
Check your GPU is on the Valhall allowlist above. Outside it, the DX12 half won't engage — though BCn
still will on any non-Qualcomm Mali. And remember this path is still experimental.

**I switched wrappers and nothing changed.**
Check the [same-file list](#-many-of-these-are-literally-the-same-file) — you may have swapped one
wrapper for a byte-identical copy of it.

**A wrapper shows "Unknown" version or the wrong settings.**
Use **Details** to inspect it. Bundled wrappers report real version labels and flag catalog updates.

---

## Credits

Every wrapper in the catalog belongs to its upstream author, and each is credited in-app:

- **[Bruno (brunodev85)](https://github.com/brunodev85/winlator)** — Winlator and the original wrapper
  the whole family descends from.
- **[Pipetto-crypto](https://github.com/Pipetto-crypto/winlator)** — Winlator Bionic, the bionic
  upstream behind Ludashi and other bionic forks.
- **[leegao](https://github.com/leegao)** — the [BCn decompression layer](https://github.com/leegao/bcn_layer)
  (shader-v3), the DX12 `compat_layer`, and the
  [bionic-vulkan-wrapper](https://github.com/leegao/bionic-vulkan-wrapper) (ETC2-Milestone-2) used as
  the base for the Mali BCn path.
- **[GameNative](https://github.com/utkarshdalal/GameNative)** — the GameNative wrapper (DX12 +
  integrated BCn).
- **[WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali)** (GunaCharanTeja / Charan) — the
  Wrapper Version Manager is modeled on their graphics-driver manager from
  [Bionic 1.1](https://github.com/GunaCharanTeja/WinlatorMali/releases/tag/bionic-mali-1.1), and
  several catalog wrappers come from WinlatorMali.
- **[WinNative](https://github.com/WinNative-Emu/WinNative)** — their own wrapper builds.
- **[StevenMXZ](https://github.com/StevenMXZ/Winlator-Ludashi)** — Winlator-Ludashi's wrapper.
- **Fcharan / WinMali-Dev** — the tuned fork of leegao's BCn layer.

The Wrapper Version Manager was requested in
[#132](https://github.com/The412Banner/Bannerlator/issues/132) by
[@6ui99uhkllj](https://github.com/6ui99uhkllj), and the Mali BCn work was driven end-to-end by
@kylinzang on Mali-G57 in [#70](https://github.com/The412Banner/Bannerlator/issues/70).
