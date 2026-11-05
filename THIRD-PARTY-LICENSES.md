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
| [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) → [GameNative/lsfg-vk-android](https://github.com/GameNative/lsfg-vk-android) | **PancakeTAS** (upstream); Android port by **FrankBarretta** / **GameNative** | **GPL-3.0** ✔ | Vulkan frame-generation engine (second, user-selectable FG option). The bundled `liblsfg-vk.so` is the compiled layer from the GameNative Android fork, tag **v1.0.4-android** — a GPL-3.0 branch of PancakeTAS lsfg-vk (adds the Adreno gen8 mipmaps-replacement compute shader that fixes frame-gen producing no frames on Turnip a8xx) |
| [WinNative](https://github.com/WinNative-Emu/WinNative) — `wn-steam-client` Rust crate + `wnsteam` Kotlin facades | **WinNative-Emu** contributors | **GPL-3.0-or-later** ✔ | The native Steam CM engine `libblsteam.so` (`app/src/main/cpp/bl-steam-client/`, crate `bl-steam-client`) and the `com.winlator.star.store.blsteam` facades are a rebranded derivative of WinNative's `wnsteam` engine (upstream `a2020b7`). Provenance + the GPL §5 statement of changes: `app/src/main/cpp/bl-steam-client/NOTICE.md` |
| [gbe_fork](https://github.com/Detanup01/gbe_fork) / [Goldberg Steam Emu](https://mr_goldberg.gitlab.io/goldberg_emulator/) | **Detanup01** / **Mr_Goldberg** | **LGPL-3.0** ✔ | The **Goldberg auto-patch** (Regular / Experimental / ColdClient tiers) for offline/emulated play |
| [Pluvia](https://github.com/oxters168/Pluvia) | **oxters168** | **GPL-3.0** ✔ | Referenced (alongside GameNative) for Steam login/session patterns |

---

## 3. Permissive components (MIT / Zlib / Apache / LGPL)

| Component | Author | License | Used for |
|---|---|---|---|
| [bcn_layer](https://github.com/leegao/bcn_layer) + ASTC/ETC compute encoders | **leegao** | **MIT** ✔ | BCn texture-decompression Vulkan layer (Mali GPU compatibility) + real-time transcoders |
| [JavaSteam](https://github.com/Longi94/JavaSteam) + `javasteam-depotdownloader` | **Longi94** / **joshuatam** | **MIT** ✔ | Steam connection-manager client and the depot-download engine behind the Steam store |
| [vkBasalt](https://github.com/DadSchoorse/vkBasalt) | **DadSchoorse** | **Zlib** ✔ | The Vulkan post-processing layer (embeds the ReShade FX compiler) behind the **ReShade** feature; patched here for live on-device toggle/slider control |
| **win-fg** (Bannerlator's own frame-gen layer) | **Bannerlator** | clean-room; **MIT** deps only | The default FG engine. Written from the ground up: optical-flow front end adapts **AMD FidelityFX FSR3** (MIT — see §4), synthesis written from first principles. Bundles **no proprietary code or weights** |
| [Box64](https://github.com/ptitSeb/box64) (+ [Pipetto fix branch](https://github.com/Pipetto-crypto/box64)) | **ptitSeb** / Pipetto-crypto | MIT *(per upstream)* | x86_64 → ARM64 translation |
| FEXCore | FEX-Emu | MIT *(per upstream)* | x86/x64 translation (alternate) |
| Wine / Proton | WineHQ / Valve | LGPL-2.1+ *(per upstream)* | The Windows runtime |
| Mesa / Turnip ([Banners-Turnip](https://github.com/The412Banner/Banners-Turnip)) | Mesa / jacojayy (Timeline-Semaphore patches) | MIT *(per upstream)* | Adreno Vulkan driver |
| DXVK / [VEGAS DXVK](https://github.com/isygold/vegas-releases) | doitsujin / **isygold** | Zlib *(per upstream)* | D3D9/10/11 → Vulkan (VEGAS = Adreno-tuned DXVK fork) |
| VKD3D-Proton | Valve / Wine | LGPL-2.1 *(per upstream)* | D3D12 → Vulkan |
| DXVK `dxbc` compiler (vendored subset, `app/src/main/cpp/thirdparty/dxbc`) | doitsujin | Zlib *(per upstream)* | DXBC → SPIR-V translation of the user's own Lossless Scaling shaders for **LSFG Native** — nothing proprietary is bundled |
| Eden emulator — native LSFG present path (PR #4263, camillelavey) | Eden Team | GPL-3.0 *(per upstream)* | **LSFG Native**: PE-resource shader map, ALPHA/BETA/GAMMA/DELTA chain decomposition and pacer, ported into the compositor (WinNative's implementation used as design reference, no code) |
| [OpenXR-SDK](https://github.com/KhronosGroup/OpenXR-SDK) | Khronos | Apache-2.0 *(per upstream)* | XR support |
| [SDL3](https://github.com/libsdl-org/SDL) 3.4.16 (official Android release AAR, unmodified, `vendor/maven/org/libsdl/android/SDL3`) | **Sam Lantinga** and SDL contributors | **Zlib** ✔ | Optional **Steam Controller** support: SDL's HIDAPI Steam drivers read the 2015 / 2026 Steam Controller over Bluetooth LE or USB (only those drivers are enabled) |
| [Sora](https://github.com/sora-xor/sora-font) and [Nunito Sans](https://github.com/Fonthausen/NunitoSans) static fonts (`app/src/main/res/font/sora_*.ttf`, `nunito_sans_*.ttf`, from Google Fonts) | The Sora Project Authors (© 2019); The Nunito Sans Project Authors (© 2016) | **SIL Open Font License 1.1** ✔ | Headings and body text of the **Deck** interface style |

---

## 3b. Archive engines (Unpack and the file manager)

Ported from [BFE (Banners File Explorer)](https://github.com/The412Banner/BFE), The412Banner, GPL-3.0. Each tool is an executable in `app/src/main/jniLibs/arm64-v8a/` with its notice file at the repository root (`NOTICE_*.txt`).

| Component | Author | License | Used for |
|---|---|---|---|
| [7-Zip](https://www.7-zip.org) 24.08 (`lib7zz.so`) | Igor Pavlov | LGPL-2.1 + unRAR restriction | Extraction and archive browsing (`NOTICE_7ZIP.txt`, `License_7zip.txt`) |
| [innoextract](https://github.com/dscharrer/innoextract) 1.9 (`libinnoextract.so`) + Boost, zlib, bzip2, liblzma, GNU libiconv | Daniel Scharrer; Boost, Jean-loup Gailly & Mark Adler, Julian Seward, Lasse Collin, GNU | zlib; BSL-1.0, zlib, bzip2, 0BSD / public domain, LGPL-2.1+ | Inno Setup / GOG installers (`NOTICE_INNOEXTRACT.txt`) |
| [FreeArc unarc](https://github.com/mirror/freearc) 0.67 (`libunarc.so`, rebuilt `libunarcx.so`) | Bulat Ziganshin | GPL | FreeArc / ISDone repacks, incl. archives with extra layers (`NOTICE_UNARC.txt`, `NOTICE_UNARCX.txt`) |
| `libbfeext.so`, `libbfecut.so` (BFE `native/bfeext`, `native/bfecut`) | The412Banner | GPL-3.0-or-later | External FreeArc layers; unpack-and-delete-as-it-goes |
| [precomp](https://github.com/schnaader/precomp-cpp) 0.4.4 - 0.4.8 (`libprecomp044.so` … `libprecomp048.so`) | Christian Schneider (with preflate by Dirk Steinke, packJPG / packMP3 by Matthias Stirner) | Apache-2.0 (packJPG / packMP3: LGPL-3.0) | precomp layers in FreeArc archives (`NOTICE_PRECOMP.txt`) |
| [lunzip](https://www.nongnu.org/lzip/lunzip.html) 1.15 (`liblunzip.so`) | Antonio Diaz Diaz | GPL-2.0+ | lzip (`NOTICE_LUNZIP.txt`) |
| [lzop](https://www.lzop.org) 1.04 + LZO 2.10 (`liblzop.so`) | Markus F.X.J. Oberhumer | GPL-2.0+ | lzop (`NOTICE_LZOP.txt`) |
| [LZ4](https://github.com/lz4/lz4) 1.10.0 (`liblz4.so`) | Yann Collet | GPL-2.0+ (CLI), BSD-2 (library) | LZ4 (`NOTICE_LZ4.txt`) |
| [ecm-tools](https://github.com/alucryd/ecm-tools) 1.0.3 (`libunecm.so`) | Neill Corlett, alucryd | GPL-3.0+ | ECM CD images (`NOTICE_UNECM.txt`) |
| [unalz](https://github.com/kippler/unalz) 0.65 (`libunalz.so`) | kippler (+ Debian patches) | zlib | ALZ (`NOTICE_UNALZ.txt`) |
| UNACE 1.2b (`libunace.so`) | Marcel Lemke (+ Debian patches) | GPL-2.0+ | ACE 1.x (`NOTICE_UNACE.txt`) |
| [amiga-lzx](https://crates.io/crates/amiga-lzx) 0.1.5 (`liblzx.so`) | Gareth Davidson | WTFPL | Amiga LZX (`NOTICE_LZX.txt`) |
| EGG / AZO reader (in-app Kotlin) | after [EggDotNet](https://www.nuget.org/packages/EggDotNet) by Andrew Kolman (MIT) and ESTsoft's EGG specification; **xunazo** by kippler / Bandisoft (zlib), with Project Nayuki's Reference-arithmetic-coding (MIT) | MIT / zlib | ALZip EGG archives (`NOTICE_EGG.txt`) |
| OTA `payload.bin` reader (in-app Kotlin) | after AOSP update_engine `update_metadata.proto` | Apache-2.0 | Android OTA payloads (`NOTICE_OTA_PAYLOAD.txt`) |
| [XZ for Java](https://tukaani.org/xz/java.html) 1.9 | Lasse Collin and contributors | Public domain | FreeArc table of contents, EGG LZMA, OTA XZ |

---

## 4. Shaders & upscaling algorithms

| Component | Author(s) | License |
|---|---|---|
| ReShade `.fx` effects (bundled + catalog) | **crosire** ([reshade-shaders](https://github.com/crosire/reshade-shaders)), **prod80**, **luluco250** ([FXShaders](https://github.com/luluco250/FXShaders)), **fubax** | **MIT / CC0**, each under its own per-file header *(per upstream)* |
| AMD FidelityFX **FSR**, **CAS** & **FSR3 optical flow** *(the last adapted into win-fg's motion estimation)* | AMD | MIT *(per upstream)* |
| NVIDIA **Image Scaling (NIS)** | NVIDIA | MIT *(per upstream)* |
| Qualcomm **Snapdragon GSR (SGSR)** | Qualcomm | BSD-3-Clause *(per upstream)* |

**Lossless Scaling note:** `lsfg-vk` is a Vulkan-layer *reimplementation* of Lossless Scaling's frame generation. **No proprietary Lossless Scaling shaders are bundled.** Users supply their own `Lossless.dll` (from [Lossless Scaling](https://store.steampowered.com/app/993090/) by THS) via the in-app picker.

---

## 5. Build / library dependencies

Standard Gradle dependencies, each under its own license (Apache-2.0 unless noted): Jetpack Compose, AndroidX (appcompat, activity, lifecycle, navigation, preference, recyclerview, fragment), Google Material Components, [Coil](https://github.com/coil-kt/coil) (Apache-2.0), Retrofit / OkHttp / Okio (Apache-2.0), Gson (Apache-2.0), [zstd-jni](https://github.com/luben/zstd-jni) (BSD-2-Clause), Apache Commons Compress (Apache-2.0), `org.tukaani:xz` (public domain), Conscrypt (Apache-2.0), ZXing (Apache-2.0).

## 5b. Winlator X server GLX / Composite extensions (MIT)

The pure-Java X server's **`GLXExtension`** and **`XComposite`** extensions, and the associated GLX error classes (`GLXError`, `GLXBad{Context,Drawable,FBConfig,Pixmap,Window}`), are ported from **[Pipetto-crypto/winlator](https://github.com/Pipetto-crypto/winlator)** (branch `winlator_bionic`, `com.winlator.cmod.xserver`), original work **Copyright (c) 2023 BrunoSX and contributors**, under the **MIT License**. Adapted for `com.winlator.star`: package rename and MAJOR_OPCODE relocation (GLX `-105`->`-106`, Composite `-106`->`-107`, because `-105` is already taken by our RandrExtension) and GLX error base `+3`->`+4`. The `CreateContextAttribsARB` / `SetClientInfoARB` handling and the `GLXBadProfileARB` error come from the same repository (commit `60c291b9`, same copyright and license). They provide the server-side GLX fbconfig/context handshake a DRI-mode Mesa libGL needs on X11; rendered frames still travel our existing DRI3/Present path. Opt-in only: registered when a container or shortcut sets `BANNER_X11_GLX=1`.

---

## 6. Bannerlator's own contributions

The original engineering by **The412Banner** (the cross-store Download Manager, the four storefront integrations, the Steam session-hardening work, the DepotSizeResolver true-size install fix, the Goldberg auto-patch integration, the fullscreen Off/Fit/Stretch/Fill/Integer pipeline, the in-app File-Manager import picker, the DLC picker, per-game persistence, the wallpaper picker, the typed ReShade control tab, the BCn Mali integration, the theme system, and the CI/release infrastructure) is contributed under the project's **GPL-3.0** license.

---

*If you contributed and are not listed, or a license here is inaccurate, please open an issue or PR — this file is intended to be complete and correct.*
