# Third-Party Licenses & Attribution

**Bannerlator** is a derivative work distributed as a whole under the **GNU General Public License, version 3** (see [`LICENSE`](./LICENSE)).

It incorporates the third-party works listed below. **Each retains its own copyright and license**, reproduced or summarised here as required by those licenses. The combined work is licensed under **GPL-3.0** because it incorporates GPL-3.0-licensed components (notably **GameNative** and **lsfg-vk**); every other license listed here (MIT, LGPL-2.1/3.0, Zlib, BSD, Apache-2.0, CC0) is compatible with, and may be combined into, a GPL-3.0 work.

> **Why this file exists.** The upstream Winlator lineage this project descends from is **MIT © 2023 BrunoSX** (preserved in full below, as MIT requires). Bannerlator additionally incorporates GPL-3.0 code, so the *combined* distribution is GPL-3.0. This document reconciles both and records every author's notice.

GitHub-detected licenses were verified on **2026-07-07**. Licenses marked *"per upstream"* are the well-known licenses of those projects, stated from their upstream repositories rather than re-verified here.

---

## 1. Foundation — the Winlator / Star lineage (MIT © 2023 BrunoSX)

This codebase descends directly from the following, all of which carry the **same MIT license, `Copyright (c) 2023 BrunoSX`**:

| Project | Author | License |
|---|---|---|
| [Winlator](https://github.com/brunodev85/winlator) — Wine + Box64 + Turnip on Android (the foundation) | **brunodev85** (BrunoSX) | MIT © 2023 BrunoSX ¹ |
| [Winlator `cmod`](https://github.com/coffincolors/winlator) — `com.winlator.cmod` customization layer | **coffincolors** | MIT © 2023 BrunoSX |
| [Winlator Bionic](https://github.com/Pipetto-crypto/winlator) — the "Bionic" half of Star Bionic | **Pipetto-crypto** | MIT © 2023 BrunoSX |
| [Star](https://github.com/jacojayy/star) — the Star Bionic line this build continues | **jacojayy** / star-emu | MIT © 2023 BrunoSX |
| [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) — the Compose UI + Vulkan renderer path cherry-picked here | **StevenMXZ** | MIT © 2023 BrunoSX |
| [`input_controls`](https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/) profiles | **brunodev85** | MIT (part of Winlator) |

¹ *brunodev85's own repository has since been **relicensed to LGPL-2.1**. That relicensing does not retroactively affect the MIT-licensed code that the forks above (and therefore this project) took during the MIT era; an MIT grant, once given, is irrevocable for the code it was granted on.*

The full text of that MIT license, preserved verbatim:

```
MIT License

Copyright (c) 2023 BrunoSX

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. Copyleft components (GPL-3.0 / LGPL-3.0) — these make the combined work GPL-3.0

| Component | Author | License | Used for |
|---|---|---|---|
| [GameNative](https://github.com/utkarshdalal/GameNative) | **utkarshdalal** | **GPL-3.0** ✔ | Proton bionic translation; the `AHardwareBuffer` present path (GPUImage buffer locking + EGLImage sampling, DRI3 direct-scanout, Present FLIP/COPY branches, Native-Rendering scanout); the standalone **FPS limiter** (present-pacing `IdleNotify` throttle); Steam **session-hardening** patterns; the `DownloadSpeedConfig` cores×ratio model |
| [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) | **PancakeTAS** | **GPL-3.0** ✔ | Vulkan frame-generation engine (second, user-selectable FG option) |
| [gbe_fork](https://github.com/Detanup01/gbe_fork) / [Goldberg Steam Emu](https://mr_goldberg.gitlab.io/goldberg_emulator/) | **Detanup01** / **Mr_Goldberg** | **LGPL-3.0** ✔ | The **Goldberg auto-patch** (Regular / Experimental / ColdClient tiers) for offline/emulated play |
| [Pluvia](https://github.com/oxters168/Pluvia) | **oxters168** | **GPL-3.0** ✔ | Referenced (alongside GameNative) for Steam login/session patterns |

---

## 3. Permissive components (MIT / Zlib / Apache / LGPL)

| Component | Author | License | Used for |
|---|---|---|---|
| [lsfg-vk-android](https://github.com/FrankBarretta/lsfg-vk-android) | **FrankBarretta** | **MIT** ✔ | Android/bionic port of lsfg-vk (AHardwareBuffer path + pipeline-barrier shim) |
| [bcn_layer](https://github.com/leegao/bcn_layer) + ASTC/ETC compute encoders | **leegao** | **MIT** ✔ | BCn texture-decompression Vulkan layer (Mali GPU compatibility) + real-time transcoders |
| [JavaSteam](https://github.com/Longi94/JavaSteam) + `javasteam-depotdownloader` | **Longi94** / **joshuatam** | **MIT** ✔ | Steam connection-manager client and the depot-download engine behind the Steam store |
| [vkBasalt](https://github.com/DadSchoorse/vkBasalt) | **DadSchoorse** | **Zlib** ✔ | The Vulkan post-processing layer (embeds the ReShade FX compiler) behind the **ReShade** feature; patched here for live on-device toggle/slider control |
| [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) | **xXJSONDeruloXx** | *no standard `LICENSE` file* ² | The bionic Vulkan frame-generation layer (default FG engine); in-tree as a submodule **with the author's permission** |
| [Box64](https://github.com/ptitSeb/box64) (+ [Pipetto fix branch](https://github.com/Pipetto-crypto/box64)) | **ptitSeb** / Pipetto-crypto | MIT *(per upstream)* | x86_64 → ARM64 translation |
| FEXCore | FEX-Emu | MIT *(per upstream)* | x86/x64 translation (alternate) |
| Wine / Proton | WineHQ / Valve | LGPL-2.1+ *(per upstream)* | The Windows runtime |
| Mesa / Turnip ([Banners-Turnip](https://github.com/The412Banner/Banners-Turnip)) | Mesa / jacojayy (Timeline-Semaphore patches) | MIT *(per upstream)* | Adreno Vulkan driver |
| DXVK / [VEGAS DXVK](https://github.com/isygold/vegas-releases) | doitsujin / **isygold** | Zlib *(per upstream)* | D3D9/10/11 → Vulkan (VEGAS = Adreno-tuned DXVK fork) |
| VKD3D-Proton | Valve / Wine | LGPL-2.1 *(per upstream)* | D3D12 → Vulkan |
| [OpenXR-SDK](https://github.com/KhronosGroup/OpenXR-SDK) | Khronos | Apache-2.0 *(per upstream)* | XR support |

² *No SPDX-detectable `LICENSE` in the upstream repo as of 2026-07-07; included per the author's explicit permission. If/when the author publishes a license, record it here.*

---

## 4. Shaders & upscaling algorithms

| Component | Author(s) | License |
|---|---|---|
| ReShade `.fx` effects (bundled + catalog) | **crosire** ([reshade-shaders](https://github.com/crosire/reshade-shaders)), **prod80**, **luluco250** ([FXShaders](https://github.com/luluco250/FXShaders)), **fubax** | **MIT / CC0**, each under its own per-file header *(per upstream)* |
| AMD FidelityFX **FSR** & **CAS** | AMD | MIT *(per upstream)* |
| NVIDIA **Image Scaling (NIS)** | NVIDIA | MIT *(per upstream)* |
| Qualcomm **Snapdragon GSR (SGSR)** | Qualcomm | BSD-3-Clause *(per upstream)* |

**Lossless Scaling note:** `lsfg-vk` is a Vulkan-layer *reimplementation* of Lossless Scaling's frame generation. **No proprietary Lossless Scaling shaders are bundled.** Users supply their own `Lossless.dll` (from [Lossless Scaling](https://store.steampowered.com/app/993090/) by THS) via the in-app picker.

---

## 5. Build / library dependencies

Standard Gradle dependencies, each under its own license (Apache-2.0 unless noted): Jetpack Compose, AndroidX (appcompat, activity, lifecycle, navigation, preference, recyclerview, fragment), Google Material Components, [Coil](https://github.com/coil-kt/coil) (Apache-2.0), Retrofit / OkHttp / Okio (Apache-2.0), Gson (Apache-2.0), [zstd-jni](https://github.com/luben/zstd-jni) (BSD-2-Clause), Apache Commons Compress (Apache-2.0), `org.tukaani:xz` (public domain), Conscrypt (Apache-2.0), ZXing (Apache-2.0).

---

## 6. Bannerlator's own contributions

The original engineering by **The412Banner** (the cross-store Download Manager, the four storefront integrations, the Steam session-hardening work, the DepotSizeResolver true-size install fix, the Goldberg auto-patch integration, the fullscreen Off/Fit/Stretch/Fill/Integer pipeline, the in-app File-Manager import picker, the DLC picker, per-game persistence, the wallpaper picker, the typed ReShade control tab, the BCn Mali integration, the theme system, and the CI/release infrastructure) is contributed under the project's **GPL-3.0** license.

---

*If you contributed and are not listed, or a license here is inaccurate, please open an issue or PR — this file is intended to be complete and correct.*
