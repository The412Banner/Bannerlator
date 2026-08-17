# Star-Compose — Progress Log

## 2026-07-11 — 👤 ACCOUNT SYSTEM (all 4 phases) — ✅ MERGED TO MAIN (`dfd24263`), NOT tagged

> Optional username+password account (isolated OUR worker/KV/R2) → cross-device recovery + identity + hosted avatar; accounts OPTIONAL (anon flows unchanged). Merged fast-forward (main tip `dfd24263`, +5 commits from `feat/account-system`). Worker `bannerhub-api/bannerhub-configs-worker.js` create/login/reset/avatar (+`/upload` session→registry) DEPLOYED + live-self-test all-pass (PBKDF2 100k ⚠️CF cap + HMAC AUTH_SECRET sessions + recovery-key, R2 bucket `bannerlator-avatars`); commits `91bc9f8`/`2d3bca5`/`2a0f860`. App: My-account sheet (globe 👤 + drawer synced via AccountUiBus; 🌐 globe stays globe, 👤+☰=avatar with versioned cache-bust URL), meta.uploader stamp, login-restore of My uploads (with delete/edit token). APKs `account-phase{2,3,4}-*`, final `bannerlator-account-phase4-dfd2426`. **▶️ NEXT: user device-tests account + community-sharing builds → cut `2.6-pre2`.** Full detail [[project_bannerlator_bannerhub_config_crossuse]] "👤 ACCOUNT SYSTEM".

## 2026-07-11 — 🔀 PR #84 (isygold) VEGAS config-dialog — REVIEW RESOLVED, READY TO MERGE, ⏸️ DEFERRED

> **`The412Banner/Bannerlator` #84 by isygold — "remove duplicate VEGAS DX wrapper entry + add delete/local-install/progress-bar to VEGAS config dialog". Base `main`, head `2c1ab8a`, +139/-21, 4 files, mergeable=CLEAN. Both of our review points are now RESOLVED and it re-verifies compile-clean against today's main. ⏸️ MERGE DEFERRED by user — pick back up AFTER the account-system feature + current community-game-configs work.**
>
> **Review loop closed.** Owner review posted (2026-07-11 14:45) raised: (#1 BLOCK) the DXVK_CONFIG_FILE guard also dropped `DXVK_FRAME_RATE` DXVK-wide, and (#3) `findActivity()!!` hard-assert. isygold replied 21:54 + fixed both in tip **`2c1ab8a`** ("separate DXVK_FRAME_RATE from config file guard + soft findActivity null check"). Diff confirms: `DXVK_FRAME_RATE` now `put` OUTSIDE the `!hasConfigFile` guard (only the inline `DXVK_CONFIG` vegas-defaults string is skipped for a custom `.conf` — deliberate, user's file owns those keys); `val activity = context.findActivity() ?: return`.
>
> **Re-verified compile-clean vs today's `origin/main`** (main moved since the Jul-8 PR; git-CLEAN only proves textual): all referenced symbols exist w/ matching sigs — `InstallFailedReason` = exactly the **8 values** the exhaustive `when` maps (no `else` → compiles); `Downloader.ProgressListener{onProgress(float)}`+3-arg `downloadFile`; `ContentsManager.getProfiles/removeContent/syncContents`; `DXVKConfigDialog.loadVegasVersionList/loadDxvkVersionList/parseConfig/setEnvVars`; `InAppFilePicker.WCP/buildIntent/pickedUri`; `ContentProfile.verName`+`CONTENT_TYPE_VEGAS`. New Kt adds no imports — all pre-exist in `ContainerDetailScreen.kt`; `LinearProgressIndicator` resolves via `material3.*` wildcard. **⚠️ NOT CI-compiled** (fork PR = no checks); static verification only — real compile fires on merge or via an in-repo staging branch.
>
> **Non-blocking minors left as-is** (fast-follow optional): install-launcher lacks the delete path's try/finally; install doesn't advance `selectedDxvk`; double `syncContents`; deprecated M3 `LinearProgressIndicator(progress=Float)`; orphaned literal-`VEGAS` containers show blank picker till re-saved (runtime unchanged).
>
> **▶️ WHEN RESUMED:** merge #84 → main (app-side only, folds into 2.6-preN) OR push head to an in-repo branch for a green CI build first, then merge; optionally sweep the 5 minors. Full detail: memory [[project_pr84_vegas_review]].

## 2026-07-11 — 🌐 ONLINE SHARING FEATURE-COMPLETE — ✅ MERGED TO MAIN (`6843009d`), NOT tagged

> **The whole "give back" half of community configs is built AND merged. ✅ MERGED TO MAIN (fast-forward, main tip `6843009d`, +8 commits from `feat/config-upload`). NOT tagged (still 2.6-preN). Main artifacts build dispatched: run `29166165248` (`release_number=1.0-test`). Latest APK `/sdcard/Download/bannerlator-my-uploads-v2-19e82c3-standard.apk`. User installed it, will device-test the newest UI (My-uploads v2 + 4b catalog visibility) after work. Full plan/arch: memory [[project_bannerlator_bannerhub_config_crossuse]].**
>
> **Asymmetric visibility (BannerHub NEVER sees Bannerlator configs; Bannerlator sees BOTH) — PROVEN.** Uploads → separate `bannerlator-game-configs` repo via worker `?ns=bannerlator`; BannerHub reads only its own repo (never passes ns).
>
> **✅ Step 1 — worker ns-routing DEPLOYED + live-verified.** `bannerhub-api/bannerhub-configs-worker.js` (additive; no-ns=BannerHub byte-identical), deployed via CF REST API (main_module=worker.js, keep_bindings retained CONFIG_KV/ADMIN_SECRET/AUTH_SECRET/GITHUB_TOKEN), backup in `bannerhub-api/.worker-backups/`, committed `b25242b`. Self-test proved isolation + GITHUB_TOKEN writes our repo. CF creds `/data/data/com.termux/files/home/cf-creds.txt` (⚠️ absolute path, `~` no-expand in bridge; never log/commit).
> **✅ Step 2 — upload action, DEVICE-VERIFIED.** `CommunityConfigWorker.upload/deleteUpload/describe(ns)`, `UploadedConfigsStore` (SP `banner_config_uploads` + reinstall-proof manifest `my-uploaded-configs.json` in Download/bannerlator/game-configs/), VM `uploadShortcutConfig`, "Upload to community" button + replace-with-warning. Real DiRT 3 upload → landed in our repo, invisible to BannerHub, true-replace proven.
> **✅ Step 4a — browse read-merge, DEVICE-VERIFIED.** `list(game,ns)`/`download(...,ns)`; `fetchGameConfigs` queries BOTH repos per folder + merges; per-shortcut sheet passes the shortcut's own folder name; `BANNERLATOR` badge on cards. Bug fixed same session: `CommunityConfigRef.ns` threaded through detail/apply download (was "Couldn't fetch"). Full loop proven: share→list→open→apply.
> **✅ Step 4b — catalog coverage (index + app).** INDEX (bgc-repo): `canonicalize.py` now ingests our own `configs/` uploads → folds into `games_canonical.json`, MERGED by appid (DiRT 3 upload consolidated under existing "Dirt 3" 44500 via alias `dirt_3_Colin_McRae→44500`, folders/devices/config_count merged; unresolved uploads → own `name:` entry). Pushed bgc-repo `8bf0e12`; `configs/README.md` added (`900f6b8`, keeps repo root a single `configs/` folder). APP: catalog **index Refresh** button (raw CDN `games_canonical.json?t=` — NO API limit), device-fallback `fetchForDevice` HARDENED to the worker (removed the last `api.github.com/contents` call → zero API-limit paths in the read path). Commits `5b8c157d`.
> **✅ AUTO-INDEX — uploads index in ~1min not 24h.** New 2nd workflow `bgc-repo/.github/workflows/trigger-on-upload.yml` (`721ad38`): on push to `configs/**` → `gh workflow run sync-from-bannerhub.yml` (workflow_dispatch fires with default GITHUB_TOKEN + actions:write, no PAT). Loop-safe (sync commits root files only, `[skip ci]`; trigger watches configs/** only). Daily cron stays as safety net.
> **✅ Step 3 — My uploads manager.** v1 (`e60a81c3`): "My uploads" list (from manifest, reinstall-proof) + delete + edit-description + polish (replace-Cancel no longer parks the coroutine; button "Preparing…"→"Uploading…"). v2 (`19e82c32`): **globe catalog-browser entry point** (👤 person icon; top bar now ↻ Refresh · 👤 My uploads · ✕ Close — upload/import icon REMOVED from globe) + **expandable-list + summary redesign** (header `Shared N · ↓total · ★total`; tap a row to expand → inline description `OutlinedTextField` prefilled from `/desc`, Save inline, Delete; no separate edit modal). Same view opens from globe 👤 AND per-game dialog.
>
> **▶️ NEXT:** user device-tests My-uploads v2 (globe 👤 → expand → inline edit) + 4b catalog visibility (globe → Refresh → DiRT 3 shows the config) + auto-trigger workflow (next real upload fires it). THEN cut **`2.6-pre2`** (versionCode bump + tag prerelease + `update.json`). Merge to main = DONE. Commit trail on branch: `8b0eea95`(2)→`571c65ee`(4a)→`945c092a`(ns-fix)→`aa5a600d`(docs)→`5b8c157d`(refresh+fallback)→`e60a81c3`(My-uploads v1)→`19e82c32`(My-uploads v2). 2.6 release notes drafted in topic file.

## 2026-07-11 — 🌐 STEP 3 ONLINE SHARING: worker ns-routing DEPLOYED+VERIFIED + client upload action BUILT

> **Online config sharing (the "give back" half). Asymmetric visibility: BannerHub users NEVER see Bannerlator configs; Bannerlator users see BOTH. Full plan + arch in memory [[project_bannerlator_bannerhub_config_crossuse]] "🚀 STEP 3 PLAN".**
>
> **✅✅ (1) WORKER ns-routing DEPLOYED + LIVE-VERIFIED.** Modified the EXISTING config worker `bannerhub-api/bannerhub-configs-worker.js` (additive; NOT a new worker) — every GitHub call resolves its repo from `?ns=`: no-ns → `bannerhub-game-configs` (byte-identical to before, BannerHub untouched), `?ns=bannerlator` → `bannerlator-game-configs`. `source:<sha>` records ns (delete/purge route correctly); list cache repo-namespaced. Deployed via CF REST API `PUT /workers/scripts/bannerhub-configs-worker` multipart (module worker **main_module=worker.js**, compat 2024-01-01, `CONFIG_KV` re-declared + `keep_bindings:["secret_text"]`). **Pre-deploy worker backed up in `bannerhub-api/.worker-backups/`.** Committed `bannerhub-api` `b25242b`. **LIVE-VERIFIED:** all 4 bindings intact (CONFIG_KV/ADMIN_SECRET/AUTH_SECRET/GITHUB_TOKEN); BannerHub unaffected (2125 games, Megabonk 7 cfgs); ns=bannerlator routes to our repo; **GITHUB_TOKEN CAN write bannerlator-game-configs** (self-test upload succeeded → no new secret needed); **ASYMMETRIC ISOLATION PROVEN** (self-test cfg via ns=bannerlator=1, invisible to no-ns=0); token-gated /delete works; self-test cleaned up. CF creds `/data/data/com.termux/files/home/cf-creds.txt` (Termux; ⚠️ `~` no-expand in bridge → absolute path; never logged/committed). Recipe [[reference_bannerhub_api_imagefs_and_worker_deploy]].
>
> **✅ (2) CLIENT UPLOAD ACTION BUILT + STAGED (device-verify pending).** Branch `feat/config-upload` off main `f440f3c8`, tip `8b0eea95`, CI run 29158038287 green (3 flavors), APK `/sdcard/Download/bannerlator-config-upload-8b0eea9-standard.apk`. `CommunityConfigWorker.upload/deleteUpload/describe(ns="bannerlator")`+`UploadResult`; new `UploadedConfigsStore.kt` (object, Context-per-call; write-through SharedPreferences `banner_config_uploads` + manifest `my-uploaded-configs.json` in Download/bannerlator/game-configs/ = reinstall-proof token backup, hydrates SP from manifest on fresh install; `add/remove/all/forGame`); `ShortcutsViewModel.uploadShortcutConfig(shortcut,onExisting,onResult)` (IO; parses upload_token/soc/device from meta; same-game→replace-flow parked on a CompletableDeferred until UI confirms → upload new, best-effort delete old, update store); "Upload to community" button in the per-game Community-configs dialog + replace-confirm AlertDialog + progress spinner + result toast. `describe` method in place, unused until step-3 My-uploads. **⚠️ minor: Cancel leaves the coroutine parked on the deferred (harmless, GC'd w/ ViewModel scope) — fold a complete-on-cancel fix into step 3.** Uploads OPEN (no gating); same-game-same-device REPLACES with warning.
>
> **▶️ NEXT:** (a) DEVICE-VERIFY step 2 — real upload → bridge-confirm lands in bannerlator-game-configs + invisible to BannerHub + manifest written + replace-warning fires; (b) merge `feat/config-upload`→main; (c) **step 3** = "My uploads" tab (list + live votes/downloads + delete + edit-description via `describe`) + hydrate-from-manifest on reinstall + the Cancel-coroutine fix; (d) fold `configs/` into the canonicalize index so uploaded games appear in browse + polish (app_source on cards, 409 already-voted, cache-refresh). Ship `2.6-pre2`.

## 2026-07-11 — 📤 Config export schema widened (bl_ext) + new save path + community/settings outlines — NOT MERGED

> **Branch `feat/config-export-schema` (off main `8b7526ca`), tip `e8df4a1a`. NOT merged, NOT tagged — still `2.6-preN`.** Latest staged APK `bannerlator-settings-outline-e8df4a1-standard.apk`. Two threads on this branch: (A) export-coverage widening, (B) round-2 UI outlines.
>
> **(A) EXPORT SCHEMA — coverage 8/39 → ~36/39.** Additive, BannerHub-SAFE `bl_ext` namespace: pc_* output byte-identical (BannerHub still reads our configs, component-install resolution unchanged, BannerHub-origin configs with no bl_ext translate exactly as before). `bl_ext` = one nested `settings.bl_ext` object holding the Bannerlator-only fields under their native shortcut-extra key names; `translate()` overlays it straight back onto scalars (round-trip = identity). Commits: `b6b6673d` (bl_ext ~28 scalar fields + async), `e9d63a2e` (widened to carry the FULL `dxwrapperConfig` comma-list — version/vkd3dVersion/async/vulkanVersion/any DXVK sub-key; apply keeps version+vkd3dVersion on the component-resolution path, merges every OTHER sub-key surgically). `graphicsDriverConfig` deliberately NOT widened (device-specific gpuName/BCn stay preserved-on-target). Files: `ConfigExporter.kt`, `ShortcutExporter.kt`, `ShortcutConfig.kt` (ConfigTranslator), `CommunityConfigApply.kt`, test `ConfigExporterTest.kt`. **✅ EXPORT half DEVICE-PROVEN** — `Megabonk-…-1783770615.json` carries a populated `bl_ext` (24 fields: screenSize/renderer/sfCompatMode/fullscreenMode/frameGenEngine/fpsLimiterEnabled/sharpness×3/reshadeMode+Effect/emulator/box64Version+Preset/fexcorePreset/cpuList/startupSelection/exclusiveXInput/simTouchScreen/numControllers/wincomponents/lc_all/autoCloseOnExit/async) — that export predates the full-dxwrapperConfig commit, so a FRESH export on `e8df4a1a` is needed to see `bl_ext.dxwrapperConfig`. **⚠️ IMPORT/apply round-trip NOT yet device-verified** (delete→re-add→import→diff `[Extra Data]`). **⚠️ Round-trip UNIT TEST written but NEVER EXECUTED** — no JVM test runner under proot (AAPT2 won't start), CI "artifacts only" compiles but doesn't run unit tests; proof is the device round-trip.
>
> **(A2) NEW EXPORT SAVE PATH** (`00450fd2`, `ShortcutsScreen.kt` `saveExportToDownloads`): configs now save to `Download/bannerlator/game-configs/` (both folders auto-created), not Downloads root. ✅ device-proven (file landed there). Share action still uses private cacheDir (unchanged).
>
> **(B) UI OUTLINES round 2** — community-config popups + settings sections now read as bordered cards. `28ecc9c5` (per-game AlertDialog surface dropped to surfaceContainerLow so its config-card outlines read), `d0c88d6f` (catalog-browser `Dialog`→`Surface` gets a 1dp outline, both orientation variants), `4358bce0` (per-game AlertDialog box gets a shape-matched `Modifier.border` 1dp outline — AlertDialog has no border param), `e8df4a1a` (every Settings section outlined via the shared `FieldSet` composable — one edit covers all sections). **✅ box outlines device-checked by user ("beautiful").**
>
> **▶️ NEXT:** (1) fresh export on e8df4a1a → confirm `bl_ext.dxwrapperConfig` full comma-list; (2) IMPORT round-trip device-verify (export→delete→re-add→import→bridge-diff `[Extra Data]` before/after, incl. full DXVK config); (3) then merge branch→main (artifacts-only, stays 2.6-preN) or roll into a `2.6-pre` cut; (4) step 3 = ONLINE upload (`POST /upload` app_source=bannerlator into `bannerlator-game-configs`). Contract: [[reference_bannerhub_config_worker_contract]]; full detail [[project_bannerlator_bannerhub_config_crossuse]].

## 2026-07-11 — 🎨 UI: flat menus → outlined-card style (download sheet + all app dropdowns) — MERGED TO MAIN

> **✅ MERGED TO MAIN (fast-forward, tip `d4c79808`). NOT tagged — artifacts-only build auto-fired on main push. Still `2.6-preN` (vc unchanged); folds into next `2.6-pre`.** Branch `feat/download-sheet-cards` (5 commits: `f9f16067`→`4f992cfc`→`5c635900`→`ee1189bd`→`d4c79808`). Device-verified across every menu.
>
> **What shipped — the whole "flat menu → file-manager card" pass:**
> 1. **Download sheet** (`ContentDownloadSheet.kt`, all components DXVK/VKD3D/FEXCore/Box64/WOWBox64/Wine/Proton): version rows → outlined `Card` (rounded + `outline` border + `surfaceContainer`, matches FileManager `FileCard`); install spinner → **content-card dialog** (`InstallCardState`/`InstallCardPhase` → Type/Version/Code/desc + live 0→100% `LinearProgressIndicator`), for BOTH local-file and catalog installs; long install-dialog titles wrap (`weight(1f)`+`maxLines=2`+ellipsis).
> 2. **Shared helper** `ui/screens/MenuStyle.kt` — `Modifier.outlinedMenuCard()` (`clip`+`background(surfaceContainer)`+`border(1.dp,outline)`) + `MenuItemDivider()` (`HorizontalDivider(outline.copy(alpha=.5f))`). Every restyled dropdown routes through it → guaranteed identical.
> 3. **All flat dropdowns restyled** via the helper: download-sheet folder menu (Browse/Pick-via-system); Containers ⚙ gear menu (`ContainersScreen.kt`); FileManager drive/storage picker (`FileManagerScreen.kt`); Games per-game ⋮ overflow (`ShortcutOverflowButton` in `ShortcutsScreen.kt`, 8 items); **all field-selector pickers** on container editor + shortcut editor via the two shared composables `LabeledDropdown` + `CompactDropdown` (`ContainerDetailScreen.kt`) — one edit each covers Renderer/Render-scale/Audio/Emulator/Fullscreen/Frame-Gen + DXVK/VKD3D/graphics-driver dialogs + external-controller screen.
>
> **M3 caveat (compose-bom 2024.02 → M3 1.2.0):** `DropdownMenu`/`ExposedDropdownMenu` have no `shape`/`border`/`containerColor` params → styled via `modifier` on menu content. Popup Surface keeps its own ~4dp corners under our 10dp bordered content (consistent across all menus; cosmetically fine). **▶️ NEXT:** fold into a `2.6-pre` cut, or sweep for any remaining flat dropdowns if more surface. Last staged APK `bannerlator-game-menu-pickers-d4c7980-standard.apk`.

## 2026-07-11 — 🧪 Proton 9 + FEX-unixlib: loader compiles on wine 9.0 (but no installable wcp yet)

> **Continuation of the P10/P11 FEX-unixlib work onto Proton 9.** Full memory: [[project_fexcore_unixlib_transition]].
> **✅ VERIFIED `proton_9.0` = latest Valve P9** (our branch == GameNative upstream, just past Valve final `proton-9.0-4`; no 9.0-5, line ended).
> **✅ Loader compiles on wine 9.0 arm64ec (feasibility proven).** Plain `proton_9.0` has no build tooling; arm64ec base = GameNative `proton_9.0_arm64ec_add_steam` (pipetto-crypto arm64ec wine-9 tree, 2026-07-08, **committed-to-tree, NO android/patches overlay**, different build system than 10/11). Brought into our fork as **`feat/p9-fexunixlib`** (`97c332ecfe`), re-anchored the loader source onto 9.0 ntdll (enum **1002** confirmed). Build run **29139177186** SUCCESS; artifact `arm64ec/ntdll/ntdll.so` **binary-verified** `nm load_unixlib_by_name` @0x61d78 + PREFIX/`/lib/wine`. ⇒ FEX-unixlib loader ports cleanly 4 major versions back.
> **⚠️ BLOCKER for shipping P9+unixlibs: NO installable `.wcp`.** That branch's only CI (`build-steam-targets.yml`) builds Steam components + (agent-added) ntdll/wow64 only — no `make install`/prefix/wcp. **▶️ NEXT (to make P9 installable): wire a full install+wcp-packaging step into the branch CI** (port 10/11 `build-step-arm64ec.sh --install`). Also: full P9 arm64ec ntdll never proven green before this, so treat runtime as untested. Nothing published, nothing on device.
>
> **⛔ UPDATE (2026-07-11): wired the packaging (commit `ff16570e99`, workflow `build-proton-p9-arm64ec.yml`, run 29145940665) — BUT found a DECISIVE deeper blocker.** GameNative's P9 arm64ec branch is **INCOMPLETE**: no `android/` dir exists yet `build-step-arm64ec.sh` `git apply`s from `./android/patches/` (all failed silently in the earlier "green" run → that green only meant lsteamclient + our loader compile, NOT a bootable build). The bionic arm64ec patch set (address-space/preloader/esync-fsync/thread-suspension/wow64-syscall/winex11-bionic) is in NO accessible repo; P10's set is NOT portable (57/70 fail vs wine-9). **⇒ any P9 .wcp we build now is under-patched near-stock wine-9 → NOT expected to BOOT on bionic. NOT device-ready.** The loader-compiles-on-9.0 result stands; the blocker is GameNative-branch incompleteness, not our unixlib work. **To get a bootable P9 arm64ec: find the wine-9 bionic patch set (pipetto-crypto origin?) or hand-rebase 57 P10 patches onto wine-9 (real effort). DECISION PENDING — may not be worth it (P9 niche; P10/11 cover most).**
>
> **✅✅ RESOLVED (2026-07-11) — pessimism was WRONG, we have a complete bootable-candidate P9 wcp.** Surveyed Pipetto-crypto: authoritative base = **`Pipetto-crypto/wine` @ `proton-9.0-arm64ec`** (Wine 9.0, bionic adaptations committed IN-TREE — virtual.c 251KB w/ FexStatsShm/kernel_writewatch, esync.c present, Android dnsapi/clipboard/browser/rawinput/iphlpapi/winex11). The "missing patches" were just the *overlay* dir; the functionality is in-source (P10 patches failed b/c pipetto did it differently in-tree, not because it's absent). Packaging run **29145940665** SUCCESS → **`proton-9.0-arm64ec.wcp`** COMPLETE (493 arm64ec + 492 i386 DLLs + 21 unix .so + prefixPack; 88MB = stripped wine-9), loader verified (`nm load_unixlib_by_name`), genuine bionic arm64ec (`bin/wine` interp linker64 NDK r27d). md5 `74eb999e…`, staged `/sdcard/Download/`. **▶️ NEXT = device boot test** (fresh arm64ec container + FEXCore + FEX -unix component; /proc/maps for libarm64ecfex.so). First bionic arm64ec Proton 9 we've built.

## 2026-07-10 (night) — 🍷 CHECKPOINT: Proton 10 FEX-unixlib + Coffin Colors controller-fix rebuild

> **Session on `The412Banner/proton-wine` (proton layers for Bannerlator). Resume tomorrow; also queued: the 10.0-4 shell32 copy bug.** Full memory: [[project_fexcore_unixlib_transition]], [[reference_coffincolors_proton10_controllerfix]], [[reference_bannerlator_repackage_wcp_proton]].
>
> **✅ DONE today:**
> 1. **Repacked 2 WinNative unixlib Proton wcps** (name-only + a NEW gotcha: `prefixPack.tzst`→must be XZ `.txz`, ContainerManager hardcodes it): `Proton-10-arm64ec-unix-FIXED.wcp`, `Proton-10.0-4-arm64ec-steam-unix-FIXED.wcp` (both on device `/sdcard/Download`).
> 2. **Ported FEX-unixlib loader (`MemoryWineLoadUnixLibByName`=1002 + `$PREFIX/lib/wine` search) to Proton 10** → branch `feat/p10-fexunixlib`, built (run 29133576088), **binary-verified** (`nm` `load_unixlib_by_name` in ntdll.so) + **DEVICE-VERIFIED** (`libarm64ecfex.so` mapped w/ r-xp = FIRST successful end-to-end unixlib load). Published pre-release **`build-p10-20260710-sdk28`** (arm64ec, prerelease so P11 stays Latest). **MERGED to `proton_10.0`** (merge `32ce676bc`; auto-fired sdk28+sdk35 runs CANCELLED, no unwanted publish). 2c variant NOT merged.
> 3. **Identified Nightlies `proton-10.0-arm64ec.wcp` (+`proton-10-arm64ec.wcp.xz`) = Coffin Colors' Proton 10 controller-fix = Valve Proton 10.0-2c base** (built 2025-07-28 = frozen July-22 wine tree; 2c cut July-24; airtight via the July22→29 commit gap). **Controller fix = ONE file: a patched `winebus.so` (Aug-8)** dropped on the 2c base.
> 4. **Rebuilding it exactly + unixlibs:** winebus.so is ABI-version-locked (2c `winebus.sys/unixlib.h` structs differ from 10.0-4 → can't swap into our 10.0-4 build). So re-ported the WHOLE GameNative bionic base onto 2c: branch **`feat/p10-2c-fexunixlib`** `276f94fc06e`, **66/66 patches apply clean** (re-ports: winex11 raw-xinput2, services.c re-authored env-whitelist keeping PROTON_*_HIDRAW+FEX_* passthrough, wineboot aarch64 branch, nsiproxy; winepulse dropped; 2 trivial rebases). versionName `10.0-2-arm64ec`.
>
> **✅ 2c BUILD GREEN + LOADER VERIFIED (2026-07-10 night):** run **29136936208** SUCCESS (build aarch64 ✓, release skipped). Artifact `proton-10.0-2-arm64ec.wcp` — `nm ntdll.so` confirms `load_unixlib_by_name` @0x5fbe8 + PREFIX/`/lib/wine` search strings; profile type Proton, versionName `10.0-2-arm64ec`, prefixPack.txz. So the risky re-port compiled clean w/ the unixlib loader. (⚠️ tar entries have NO `./` prefix — extract as `profile.json`/`lib/...`.) Local: `~/scratchpad/p10-2c-verify/`.
> **✅ WINEBUS SWAP + DELIVERED + DEVICE-CONFIRMED (2026-07-10 night):** swapped Coffin's `winebus.so` (md5 `3242ebe8…`) into the artifact (loader still intact), repacked → **`/sdcard/Download/proton-10.0-2-arm64ec-controllerfix-unixlib.wcp`** (md5 `770468b2…`, → dropdown `proton-10.0-2-arm64ec-2`). = Valve 10.0-2c + FEX-unixlib loader + Coffin controller winebus. **USER-CONFIRMED: installs + creates container + BOOTS.** (CI did NOT bake the controller fix — only Coffin's compiled winebus.so exists, not source → deliberate post-build binary swap.) ⏳ still to confirm at runtime: unixlib maps (`libarm64ecfex.so`) + controller live (container closed before probe; re-boot w/ controller+game). **⚠️ copy D→C bug reproduces on 2c too** → shell32 NULL-deref is version-independent across P10, NOT our build; clean captured repro deferred to tomorrow's shell32 session ([[project_bannerlator_wfm_shell32_copy_crash]]).
> **▶️ NEXT (tomorrow):** (a) 2c build green → download artifact, **swap in Coffin's `winebus.so`** (`~/scratchpad/whichp10/edit/lib/wine/aarch64-unix/winebus.so`), verify (nm loader + winebus=Coffin's), repack, stage to device → **boot + controller test**; if the 2c build succeeds it lives on its **OWN branch** (never merge to proton_10.0, user decision). (b) **10.0-4 shell32 copy-paste bug** — `wfm.exe` D:→C: copy crashes = NULL-deref in Wine `shell32`→`ucrtbase` wide-string path; **pre-existing Wine bug, NOT our unixlib build** (proven: our diff never touches shell32; crash frames have no FEX). Fix = guard shell32 copy NULL or wfm `pFrom/pTo` build. [[project_bannerlator_wfm_shell32_copy_crash]].

## 2026-07-10 — 🔜 Phase 3 (Community Configs SHARING) — local slice ✅ MERGED TO MAIN

> **✅ MERGED TO MAIN `c0b6e669` (2026-07-10, via `feat/config-export`, --no-ff; file list verified clean). NOT tagged/released — artifacts-only build run `29137673954`. Still `2.6-preN` material (vc unchanged at 42); cut a real `2.6-pre1` later. Device+runtime-verified end-to-end (export→import→running game). Continuing tomorrow: on-device sanity of the merged build, then online upload (step 3).**
> **(pre-merge branch was `feat/config-export` off main `d0284651`.) The "share your own configs" half committed to in the 2.5.2 release notes.**
> **Step 1 ✅ (CI-green, round-trip=IDENTITY):** `ConfigExporter` (pure JVM core = reverse of `ConfigTranslator`) + `ShortcutExporter` (adapter: shortcut `[Extra Data]`+container defaults → `{meta,settings,components}` pc_* JSON, `app_source="bannerlator"`) + round-trip unit test. `commit 8c0c5f7a`.
> **Step 2 ✅ (DEVICE-VERIFIED end-to-end incl. running game + hardest missing-components path):** LOCAL export/import — Share (FileProvider chooser / Save-to-Downloads) + Import (.json picker → translate → existing apply engine + smart-install result screen). Device round-trip: export Megabonk → file → delete game → re-add clean → import → surgical apply → **game launches & runs** (DXVK 2.6.2-1-gplasync + VKD3D 3.0.1 load, swapchain 1080p presenting, clean exit); + deleted-ALL-components path → "Needs a component" correctly flagged DXVK/VKD3D → smart-installed exact builds → ✓✓ → write-back confirmed on disk. `commit 54511137`.
> **Polish fixes on branch:** Proton "change it" advisory now **suppressed when the container already runs the same base Proton** (`ConfigTranslator.protonKey()` normalizes both sides; `commit 363be8da`, device-verified). Graphics **wrapper-selection capture** — config now round-trips the `graphicsDriver` scalar (`pc_ls_GRAPHICS_WRAPPER`, plain string) so a shared config reproduces the wrapper (`wrapper-bcn_layer` etc.), not just the driver version (`commit cd20341b`, CI + mirror-verified). **NOTE: earlier "Turnip fell back to stock" was a MISDIAGNOSIS** — the `Winlator_Renderer adrenotoolsHandle=0x0` line is the host compositor (stock by design); the guest game DOES load the A8XX driver from `graphicsDriverConfig.version`.
> **▶️ NEXT:** stage the wrapper-fix APK → verify on launch (capture early `GraphicsDriverExtraction` lines: wrapper + A8XX both load for the guest) → then MERGE `feat/config-export` → main + cut `2.6-pre1` (local sharing). Then **step 3 = online upload** (`?ns=bannerlator` into `bannerlator-game-configs`, "My uploads" describe/delete) as `2.6-pre2`. Full detail [[project_bannerlator_bannerhub_config_crossuse]].

## 2026-07-10 — 🚀 2.5.2 STABLE (Community Configs) — ✅ SHIPPED = LATEST

> **✅ SHIPPED: https://github.com/The412Banner/Bannerlator/releases/tag/2.5.2 — tag `2.5.2` @ `53f2197c`, release run 29128582611, prerelease=false, `Latest` (2.5.1 no longer latest), 4 assets (3 flavor APKs + update.json vc42/2.5.2 → 2.5.1 users offered it). Styled body set via `gh release edit --notes-file` after the build (short plain notes went to the workflow for update.json/backtick-safety).**
> **2.5.2 = the Community Configs release.** vc42 / `2.5.2`, cut from main (community-configs merged + version bump). Stable (prerelease=false, make_latest=true → update.json vc42 offered to 2.5.1 users), 3 flavors, via release.yml (`Nightly Manual Release Build`). Headline: **Community Configs** — browse/apply community per-game/per-device tuning configs in-app (catalog browser, per-uploaded-config cards w/ votes/downloads, Matches-my-device, one-tap surgical Apply, smart inline install of DXVK/VKD3D/FEXCore + Turnip drivers, config detail page w/ live upvotes/downloads/description/comments + upvote & comment). Also: **two new Proton 11.0-1 x86-64 compat layers** (`proton-11.0-1-x86_64-sdk28`/`-sdk35`) published to `winlator-contents` → downloadable in-app. App-side, NO imagefs reinstall. README + release notes updated. Full feature detail in the entry below + memory [[project_bannerlator_bannerhub_config_crossuse]]. **Release-body pattern: dispatch release.yml with SHORT plain-text release_notes (drives update.json, backtick-safe) → then `gh release edit` to set the full styled markdown body (matches prior releases; sidesteps the inline-`${{ }}` backtick trap).** Run id + final status appended after CI. Baseline was 2.5.1 (vc41).

## 2026-07-10 — 🌐 Community Configs: browse/apply BannerHub configs in-app (Phase 1 device-verified + Phase 2 CI-green)

> **✅ MERGED TO MAIN (2026-07-10, `4a8f7807`; tip now `694a58ed`). NOT tagged/released — next stable=2.6, everything=`2.6-preN` until told to cut.** Artifacts built on main (CI Build artifacts-only, dispatch `release_number=1.0-test` so `stage-apk` resolves the artifact name). Latest APK: `/sdcard/Download/bannerlator-fex-yymm-match-694a58e-standard.apk`. Full detail [[project_bannerlator_bannerhub_config_crossuse]] + backend contract [[reference_bannerhub_config_worker_contract]] (repo doc `docs/bannerhub_config_system_reference.md`).**
>
> **🔖 NEXT RELEASE NOTES (2.6-pre1 / 2.6) — MUST MENTION (user, 2026-07-10):**
> 1. **Community Configs** — browse/apply community game-configs in-app: catalog browser + per-uploaded-config cards (votes/downloads) + Matches-my-device, one-tap Apply (surgical merge), smart inline install of missing DXVK/VKD3D/FEXCore + Turnip drivers, config detail page with live upvotes/downloads/description/comments (+ upvote & comment). First-party worker-backed; BannerHub untouched.
> 2. **Two new Proton 11 x86-64 compatibility layers** now downloadable in-app from the `winlator-contents` catalog: **`proton-11.0-1-x86_64-sdk28`** (Android 9 / SDK 28) and **`proton-11.0-1-x86_64-sdk35`** (Android 15 / SDK 35) — both Proton 11.0-1, x86_64, via `The412Banner/winlator-contents` release `proton-11.0-1-x86_64`.
>
> **✅ DEVICE-VERIFIED (2026-07-10):** match/search, catalog browser, landscape two-column, Matches-my-device (matcher fix — normalize HW strings, match user SoC/GPU vs ALL device fields since config `soc`=GPU-renderer string), source label by app_source, per-uploaded-config cards, **multi-folder aggregation** (`fetchGameConfigs` queries ALL folders), **APPLY + component smart-install** (via the FEX flow below), and **Turnip GPU-driver smart-install + apply** (Adreno 5-repo → install → `graphicsDriverConfig.version` write-back — user confirmed "install and apply correctly").
>
> **✅ THREE FEX INSTALL FIXES — DEVICE-VERIFIED ("worked out perfect"):** (1) `c1b24b72` **already-installed** component (finishInstallContent `ERROR_EXIST`) → treat as SUCCESS + apply (was a bare "install failed"); also log real `InstallFailedReason` (was swallowed). (2) `f5a68928` **closest-build** install → apply the NEWEST-installed build of the type (`InstalledComponents.newestToken`) not the un-matchable wanted string — covers inline + Browse-all. (3) `694a58ed` **FEX YYMM matching** — GameHub/community label FEX builds as 4-digit `YYMM` (year last-two + month); a config's full date `Fex-20260103`→`2601` for both `InstalledComponents.resolve` (already-installed) and `versionKey` (smart-install ranking → `FEXCore-2601` at distance 0 vs 2607=6); month validated so a DXVK build code `1624` is never a false tag. Diagnosed on-device: valid ZSTD archive pulled, `finishInstallContent` ERROR_EXIST confirmed via profile.json compare.
>
> **BACKEND DISCOVERY (2026-07-10): the whole community-config system is FIRST-PARTY user-owned — one Cloudflare worker (`bannerhub-configs-worker.the412banner.workers.dev`, the SAME one Bannerlator already uses for /steam/search) + KV + the GitHub repo. Upvotes/comments/uploads/downloads all live there (NOT XiaoJi). `app_source` tags each upload (`bannerhub`/`bannerhub_lite`/ours→`bannerlator`) and `/admin/purge` deletes by it → Bannerlator reuses the SAME worker, no new backend/repo. Full endpoint+format contract in the repo doc.**
>
> **NEWEST (this session, all on the branch, CI-green, device-UNVERIFIED unless noted):** worker-wired detail page (live ★votes/↓downloads/description/comments + upvote + add-comment); whole-row-tappable **thin outlined cards** (match FileManager look) for game + config lists; **per-uploaded-config cards** (worker `/list`, sorted by votes, each `★votes ↓downloads · device · soc · date`; tap→chooser→apply/detail THAT file; offline fallback = per-device rows); **"Matches my device" toggle** on the config-list screen; **source label** by `app_source` (BannerHub / BannerHub Lite / Bannerlator). **DEVICE-VERIFIED fixes:** landscape two-column browser; **"Matches my device" matcher fix** (`GameMatcher` — normalize hardware strings, match user SoC/GPU against ALL device fields since config `soc` is a GPU-renderer string; user confirmed configs now show); source-label rename.
>
> **▶️ REMAINING PLAN (updated 2026-07-10 — CONSUME SIDE 100% DONE + VERIFIED). Only these 4 buckets remain; only ONE is a feature:**
> **✅ A DONE — worker social WRITES verified (2026-07-10):** HttpUtils.post correct (POST/json/UTF-8); worker round-trip proven via fabricated `__bannerlator_selftest__` target (non-polluting) — /vote→{success,votes}, re-vote→409 already_voted (dedup 24h/IP), /comment→success + GET /comments reads it back; device-side curl POST also succeeds. Final genuine confirm = user taps upvote/comment in-app (legit, low-risk). [Turnip + apply + component install + all 3 FEX fixes also ✅ verified.]
> **➡️ TRACK 3 (= "Phase 3") — THE ONE REMAINING FEATURE: uploads (the "give back" half).** Export a Bannerlator shortcut → `pc_*` config (reverse `ConfigTranslator`) → `POST /upload` with **`app_source="bannerlator"`** (+ describe/delete own uploads) + local export/import. Zero new backend (worker already does uploads).
> **B. Polish (small/optional, NOT a phase):** 409 already-voted handling (app `vote()` returns null on non-2xx → cross-app already-voted reads as failure); mark already-installed builds "Installed ✓" (not re-offered; harmless now); **Box64/WOWBox64 install gap** (smart-installer only auto-installs DXVK/VKD3D/FEXCore + Turnip — confirm box64/wowbox64 handled/advisory); app_source label on cards; browser drill-position; cache-refresh affordance (index=in-mem+disk+24h → needs force-stop).
> **D. Ship (release action, not build work):** cut **`2.6-pre1`** (versionCode bump + tag prerelease + `update.json`) — the consume side is ready to ship to the beta channel NOW.
> **E. Separate infra (not the feature):** CI **matrix** rework (prepare job + flavor matrix + `flavors: standard|all` input + `org.gradle.caching=true`).
> **Recommended order:** cut `2.6-pre1` (D) now → Track 3 as `2.6-pre2` (folding B in) → E anytime.
>
> ---
> _(original 2026-07-10 Community Configs entry — superseded above, kept for detail)_
>
> **The initiative:** consume the community BannerHub game-configs (per-game/per-device tuning) inside Bannerlator. Built a **separate isolated repo** `The412Banner/bannerlator-game-configs` (reads BannerHub read-only, writes only itself — zero upstream impact; PR #1 on the orig repo = fallback). Index: 2116 folders → **1261 canonical games merged by identity**, **config-coverage 92%** (namespaced key: numeric Steam appid | `name:<slug>` non-Steam like PES). HTML catalog + Artifact published.
>
> **In-app consumer (this branch):**
> - **Phase 1 — DEVICE-VERIFIED:** per-shortcut ⋮ → "Community configs" sheet — fuzzy-match shortcut name → canonical game, per-device configs (your-SoC ranked first), + a **manual search box** fallback. Screenshots confirm Crysis 3 + DiRT 3 match correctly.
> - **Phase 2 — CI-green, on-device UNVERIFIED:** **globe button in Games header** → `CommunityCatalogBrowser` (search · "Matches my device" + Steam/Title filters · Configs/Name/Devices sort · "Your device: <SoC>" chip) → game → per-device configs → **"Apply to game…"** → shortcut picker (**apply-any-to-any, warns on game mismatch**). Apply engine: `CommunityConfigFetcher` (GitHub contents-API → device-token filename match → raw) → `ConfigTranslator` (pc_ls_* → `[Extra Data]`, mirrors `bgc-repo/tools/translate.py`) → `InstalledComponents` (reads `filesDir/contents/{DXVK,VKD3D,Proton,FEXCore,adrenotools}`, minor-aware Match/Missing) → **SURGICAL sub-field merge** (only version/vkd3dVersion/async in dxwrapperConfig + driver version in graphicsDriverConfig; PRESERVES BCn/vulkanVersion/HUD/everything else) → `Shortcut.putExtra`. **wineVersion/Proton = advisory-only**. Per-shortcut sheet's Apply now runs the same engine. Export-ready via reversible `ShortcutConfig` (Phase 3 = export/import/contribute).
>
> **Bugs found/fixed on real device:** (1) **matcher scoring** rewritten coverage-based (`0.7·covGame + 0.3·covQuery + 0.001·inter`) — subset formula diluted short names, DiRT 3 lost to Fallout 3 / the DLC; now "Dirt 3" wins 0.82. (2) **index**: DiRT 3 → 44500 "Dirt 3" (delisted, hardcoded) + re-homed `Colin_Mcrae__Dirt_2`→`name:dirt-2` to kill the DiRT-Rally-2.0-DLC false-positive; validated ArmA2/CS/L4D2/Divinity-OS-EE/Walking-Dead-S2 aliases. (3) name-derivation must NOT stylize "DiRT" (breaks camelCase tokenizer→di+rt). (4) CI red once = `/*` inside a KDoc `{@code files/contents/*}` (Kotlin nests block comments→swallowed file); fixed `712dfaad`.
>
> **⚠️ Gotcha:** index cache is in-memory (`cachedGames`)+disk+24h → server fixes need app FORCE-STOP or 24h. **TODO: refresh affordance / ETag.**
>
>
> **Install flow (evolved through on-device testing, 2026-07-10):** (a) Apply screen's missing-component advisories (DXVK/VKD3D/FEXCore) got **Install buttons** → version-list picker → install → auto-apply → checkmark (CI 29097942831, APK `install-buttons`). (b) **Dialog-stacking fix** (CI 29099460009, APK `install-fix`): the download sheet (`ContentDownloadSheet` = ModalBottomSheet) rendered *behind* the community `Dialog` windows — user couldn't see it until closing them; fix gates ALL community dialog layers (browser / apply-picker / per-shortcut / result) on `installSheetFor==null`. (c) **Smart inline install** (branch `d08dabac`, CI 29101574597): Install now resolves the wanted version vs the catalog — **exact** → "Install `<ver>`?" confirm → **inline** download+install+auto-apply+checkmark (no menu); **no-exact** → ~3 closest versions + "Browse all" fallback. Proton/Turnip stay advisory-only.
> **Turnip/adrenotools GPU-driver smart-install (`9f7ed579`, CI 29105237641 green) — CI-green, on-device UNVERIFIED:** Turnip is no longer advisory-only on the Config-applied screen — it now installs like DXVK/VKD3D/FEXCore but routed through the adrenotools **5-repo** driver subsystem (NOT a `ContentProfile.ContentType` — the enum ends at VEGAS; drivers = `DriverSources.ALL` {Kimchi, Maxes MTR, Banners-Turnip, StevenMXZ, whitebelyash} + `RemoteDriverRepository` + `AdrenotoolsManager.installDriver`→driverId + existing `AdrenoDriverDownloadSheet`). The config's ask is `graphicsDriverConfig.version = "Mesa Turnip vX.Y.Z"` (from `ConfigTranslator.turnipVer()`). **Engine (CommunityConfigApply.kt):** new `MissingDriver(wanted,current)` + `missingDrivers` on `ConfigApplyResult`; `apply(...)` gains `isAdreno: Boolean` — unresolved driver → structured `MissingDriver` (Adreno) or plain advisory (Mali/other, so we never offer an Adreno driver off-Adreno); new `applyResolvedDriver(shortcut, driverId)` = surgical `;`-merge of the installed id into the version sub-field; new `rankDrivers(wanted, entries)`/`DriverShortlist` reusing existing `versionKey`/`versionDistance`. **Match:** parse `\d+\.\d+\.\d+` from wanted + each entry `displayName`; **`exactMatches` = EVERY entry at the same X.Y.Z across all 5 repos (not `firstOrNull`)** so repo-variants of one version each show as a distinct quick-install labeled `<source> · <displayName>` (user picks the build — repo builds of same mesa ver behave differently on-device); dedup by `downloadUrl` only; `closest` = 3 nearest other versions; then Browse-all fallback. **VM (ShortcutsViewModel.kt):** `isAdrenoGPU()` probe passed to `apply`; `fetchDriverShortlist(wanted,onResult)` fans out all 5 sources in parallel (`async`/`awaitAll`)→flatten→rank. **UI (ShortcutsScreen.kt):** `SmartDriverInstallRow` + "Needs a GPU driver" section; `driverSheetFor` state + combined `communityDialogsGated` flag now gates ALL four community dialog layers so the `AdrenoDriverDownloadSheet` ModalBottomSheet isn't drawn behind them. **▶️ DEVICE-VERIFY:** row lists all repo-variants of wanted ver distinctly → quick-install one → download+install+write-back `graphicsDriverConfig.version=<driverId>` (other sub-fields intact) → checkmark; Browse-all opens ON TOP; Mali stays advisory.
> **Landscape layout fix (`491688fa`, CI 29103436519 green) — ✅ DEVICE-VERIFIED ("looks great"):** `CommunityCatalogBrowser` + `CommunityDevicePanel` made responsive via `BoxWithConstraints` (`maxWidth >= 600.dp` = wide). Landscape = two-column `Row`: fixed 320dp scrollable controls/header LEFT + list at `weight(1f).fillMaxHeight()` RIGHT (game-list AND per-game detail views); portrait = unchanged single Column. Controls/list/header factored into local composables to avoid dup. Rotation-persist: `query`/`matchesMyDevice`/`storeFilter`/`sort` → `rememberSaveable`, `selectedGame` → `selectedIdentity:String?` re-resolved by identity. Per-shortcut sheet untouched.
> **▶️ NEXT:** on-device verify APPLY (DiRT 3/Crysis 3 → shortcut; surgical merge preserves settings; component resolution; FEX date-ver flags "install") + smart inline install (real download+install+auto-apply). Then refresh-affordance TODO, then Phase 3 export/import. NOT merged — awaiting on-device sign-off.

## 2026-07-08 — 🚧 2.6-pre: port GameNative #1620 (+#1644) — ASR SurfaceFlinger crash + BGRA→RGBA color fix

> **Branch `feat/asr-gn1620` off main `0ff4df95` (clean post-2.5 base). NOT yet CI-verified/device-tested. 2.6-preN material.**
>
> **What:** ported GN's consolidated ASR hardening PR #1620 (+#1644) — fences on CPU images (fixes the SurfaceFlinger CPU-image crash + tearing), `R8G8B8A8_UNORM` + GPU_FRAMEBUFFER/CPU_READ_OFTEN AHB flags, and a GPU BGRA→RGBA converter (GLES3.1 compute / GLES3.0 fragment fallback, dedicated EGL thread) that fixes ASR's swapped red/blue colors.
>
> **Renderer-split reconciliation (the delicate part):** GN split `GPUImage extends Texture` → new abstract `NativeTexture` base with two concretes — `GPUImage` (GL/Vulkan/DRI3/Present, our socket-present buffer) and new `AHBImage` (fenced 3-buffer CPU scanout swapchain). **We deliberately did NOT adopt GN's DRI3-→-AHBImage change:** socket/present buffers stay `GPUImage`, so every `(GPUImage)` cast in `VulkanRenderer` (:494/:540), `PresentExtension`, `DRI3Extension:156` is untouched → **Vulkan present path unchanged, GL unchanged.** Only ASR CPU chrome (gated on `Drawable.DRAWABLE_FOR_ASR`, default false) now uses AHBImage. Game frame under ASR = still GPUImage, presented direct; converter transiently imports it so BGRA→RGBA still applies.
>
> **Files:** native `cpp/asurfacerenderer/` += `blit_converter.{cpp,h}`, `ahbimage.c` (own `libahbimage.so`), rewritten `ASurfaceRendererContext.{cpp,h}` + `asurface_jni.cpp`, `GPU_CONVERTER_README.md`; CMake += blit_converter into `asurface_renderer` (+EGL/GLESv3) and new `ahbimage` lib. Java `com.winlator.star.renderer` += `NativeTexture`, `AHBImage`; `GPUImage` now `extends NativeTexture` (minimal); `ASurfaceRenderer` (AHBImage vs GPUImage routing + `setSfCompatMode` toggle, default true=convert + #1644 half-rate HUD tick); `xserver/Drawable` (ASR-mode → AHBImage, `instanceof GPUImage`→`NativeTexture`, additive/gated). Built from source (no prebuilt .so copied). Removed GN's unused `asurfacerenderer/drawable.c` (swap handled in the converter).
>
> **Color toggle hook (for UI wiring, TODO):** `ASurfaceRenderer.setSfCompatMode(bool)` (default true) → last arg of `nativeSetWindowBuffer` → `ASurfaceRendererContext::setWindowBuffer(...)`. true=converted RGBA (glitch-free), false=direct BGRA. Wire at `XServerDisplayActivity` :2268 `instanceof ASurfaceRenderer` block, mirroring `container.getRendererSwapRB()` pattern (:2216/:2249). Plan: relabel to "Color channels: Auto/RGBA/BGRA". Pair with SurfaceFlinger native-toggle grey-out.
>
> **Watch on device (all THREE renderers, not just ASR):** Vulkan present (the shared-GPUImage regression risk), ASR first-frame timing (`future.get()` synchronous per game frame), low-RAM AHBImage swapchain memory (4 AHBs/Drawable). Commits: `e58aa517`, `b4a7f7e1`, `f4084713`, + drawable.c removal.
>
> **✅ DEVICE TEST 2026-07-08 (user's Adreno 750, ASR renderer, AIO Graphics Test + D3D11 cube) — HEALTHY:** (1) ASR inits clean — libahbimage loads, AHBImage buffers w/ correct #1620 flags (CPU_READ_OFTEN/GPU_FRAMEBUFFER/COMPOSER_OVERLAY), format=5(BGRA). (2) **Vulkan-native cube colors CORRECT** (LunarG teal/grey, no R/B swap) → color fix validated on native path. (3) **DXVK D3D11 cube renders** (blue/magenta/green faces, red channel present → no gross swap; user didn't flag wrong colors). (4) **No hard crash** — app pid stayed alive throughout, only tombstone on device = unrelated `rs.media.module`; the SIG19 seen = normal drawer-pause, NOT a crash. (5) **FPS limiter:** works on DXVK path (54–61 around 60 cap, GPU 12% = throttling real) but LOOSE ±10%; NOT capped on native-Vulkan-no-DXVK (473) = pre-existing (limiter is DXVK-side), not a #1620 regression. **ONE flagged issue: D3D11 cube FAILED on FIRST launch, worked on retry** — likely DXVK cold shader-cache (`No state cache file found`) warmup, possibly the flagged ASR first-frame `future.get()` race; needs a clean repro to pin. No baseline for ASR-limiter behavior in 2.5 (user never used ASR past original impl). **VERDICT: port working on Adreno; no Vulkan/GL regression observed. Remaining: pin the first-launch DX11 failure; device-test needs Mali (kylinzang) + a real game; then UI wiring (Task3).**
>
> **✅ FOLLOW-UP (same session) — DiRT 3 crash + renderer-picker BOTH resolved as NON-bugs:** (a) **DiRT 3 "crash on ASR" = the device MediaProvider/FUSE bug, NOT us.** Log caught `vold: Sending Interrupt to pid <n> (dirt3_game.exe)` right after `FuseDaemon rs.media.module SIGABRT` (mediaprovider::fuse::NodeTracker::CheckTracked → pf_opendir). DiRT 3 streams `.nfs` assets off `/sdcard/Winlator/Games` (FUSE); heavy opendir trips the phone's MediaProvider daemon → `vold` tears down `/storage/emulated/0` + kills every referencing proc (also killed unrelated `com.community.oneroom` same instant). Renderer-independent; our process had NO tombstone (killed, not crashed). **Fix = move game to internal ext4 (C:) to bypass FUSE.** DiRT 3 later ran fine on ASR (intermittent). (b) **Renderer picker VERIFIED CORRECT (not a bug).** User saw container=OpenGL yet game ran SurfaceFlinger → suspected per-game override ignored. Live-tested via `/proc/<pid>/maps` + renderer-init logs: container=GL→ran ASR, container=ASR→ran ASR, **container=ASR + SHORTCUT=GL → ran GL** (positive proof: `GLThread` spawned, only libEGL/libGLESv2/3 mapped, zero libasurface_renderer/libahbimage/BlitConverter). ⇒ the DiRT 3 **shortcut** had renderer=surfaceflinger and was correctly **overriding the container** all along = per-game override working as designed. `resolvedRenderer()`→`shortcut.getExtra("renderer")`→`initRenderer()` chain + the `.desktop` first-`=`-split parser (isolates host `renderer=` from the one inside `dxwrapperConfig=`) all confirmed correct in code too. (c) **FPS-limiter jitter (54–61 @ cap 60) is NOT renderer-specific** — it's the standalone host pacer (paces X11 Present/IdleNotify, ABOVE all renderers, `applyFpsLimit()` no renderer branch), untouched by #1620; native-Vulkan-no-DXVK 473 = limiter is DXVK-side (pre-existing). **NET: #1620 device test PASSED on Adreno 750; renderer picker verified; no regressions. Next open = Task3 UI wiring (color toggle Auto/RGBA/BGRA + native-toggle grey-out) + Mali (kylinzang) verify.**
>
> **✅ Task3 DONE (color-toggle UI) — CI GREEN (run 28964403662), staged to device (`bannerlator-asr1620-colortoggle-*`).** Added per-container + per-game **"Correct SurfaceFlinger colours"** switch (default ON) wired to `ASurfaceRenderer.setSfCompatMode()` — new `Container.rendererSfCompatMode` (serialized in renderer-config, backward-compat=ON), read-only `resolvedSfCompatMode()`, launch call in ASR block, Compose switch in VulkanSettingsDialog + per-game row in ShortcutsScreen (shown when renderer=surfaceflinger), strings. **swapRB + all renderer/native code untouched** (GL/Vulkan can't regress). Commits `e36ab389` + follow-up. Tip after this checkpoint. **NOT yet device-verified that OFF-state flips colours (optional pre-merge check). Per user: skip Mali + versionCode bump; branch is merge-ready by their criteria — merge whenever.** **Side-investigations this session (all NON-bugs, informational): Crysis3 launch ~15s = CryEngine init under box64/FEX x86-translation (CPU-bound), NOT emulator/shaders/storage — warm relaunch identical; Mesa shader cache (9MB) DOES persist compiled shaders; DXVK `.dxvk-cache` "missing" = expected (GPL active) + gplasync persistent cache is opt-in via "Async Cache" toggle (asyncCache=0 default). Noted but unchased: possible `xuser` vs `xuser-1` prefix mismatch (shortcut path under xuser-1, WINEPREFIX/cache under xuser).**
>
> **✅ FOLLOW-UP FIX (color-toggle placement) — CI GREEN, staged, DEVICE-VERIFIED.** User caught it: the "Correct SurfaceFlinger colours" toggle was buried in the renderer-settings **gear**, and that gear only renders `if (selectedRenderer == "Vulkan")` (`ContainerDetailScreen.kt:544`) — so selecting SurfaceFlinger (the only renderer the toggle affects) made it **unreachable**. FIX (`df4a43a6`): moved the toggle **inline directly under the Renderer dropdown, shown only when SurfaceFlinger is selected** (mirrors the per-game shortcut editor), and removed it from the mislabeled "Vulkan Settings" dialog (dialog still round-trips the value via its config so Vulkan users hitting OK don't drop it — `sfCompatMode` var@335, output@404, preserve@249). Bound to `viewModel.rendererSfCompatMode` (var mutableStateOf(true), load@318/save@627,670). **Device-verified across all 3 renderers: OpenGL=no toggle/no gear ✓, SurfaceFlinger=inline toggle appears ✓, Vulkan=gear→dialog w/o SF-colour row ✓.** (Note: default is ON for new containers; user's "P11 Arm" container showed it OFF = stored from earlier poking, not a default bug.) **Branch `feat/asr-gn1620` tip `df4a43a6`, 10 commits ahead of main, CI green (run 28969989710), staged `bannerlator-asr1620-toggle-fix-df4a43a-standard.apk`. AWAITING USER BUILD REVIEW before merge. Per user: skip Mali + versionCode bump; merge-ready otherwise.**

## 2026-07-08 — 🧹 CHECKPOINT: post-2.5 branch cleanup (mali branches deleted, cloud-saves preserved)

> **2.5 is out and stable (see entry below). This checkpoint = branch housekeeping. main unchanged at `981cb657`; 2.5 release intact (tag `2.5`, Latest).**
>
> **Deleted (local + remote) — all content shipped in 2.5:** `release/2.5-mali` (the clean merge source), `feat/mali-bcn-v4` (mali-v4/v5/v6 pre-release branch), `feat/mali-bcn-layer`, `feat/wrapper-gamenative-bcn`, plus stale local `worktree-agent-*` leftovers.
>
> **Full merged-branch sweep (2nd pass):** deleted **42 fully-merged branches total** (2.4/2.5-era features: download-manager + all 4 storefront producers, dlc-picker/ownership, fullscreen, inapp-file-picker, filemanager-thumbnails, all reshade-* that shipped, ui-rebuild, theme-centralize-drawer, drawer-rebuild-p1, goldberg-patcher, steam-qr, store-log-redaction, magnifier/wallpaper/rail-scroll fixes, …). Each verified **0 unique commits vs main on BOTH local AND origin** (patch-id `git cherry`, not naive ancestry) before deletion. SHAs recorded for reversibility → `~/scratchpad/deleted_branches_20260708.txt`. Also removed 6 stale git **worktrees** (`wt-*`, `.claude/worktrees/agent-*`) that were pinning some merged branches — repo now has a single worktree (`main`).
>
> **KEPT (13 branches — unmerged/in-progress or explicit):** `main`; `feat/steam-cloud-saves` (re-anchored, above); `feat/save-backup-restore` (actionable ITER-2); and 10 with unique unmerged work — `feat/sgsr2-gate0-depth-receiver-stub`, `feat/bionic-fg-shader-pool`, `feat/depot-size-resolver` (origin ahead by 1), `feat/gl-scanout-overlay-fix`, `exp/gl-scanout-composer-overlay-ahb`, `exp/gl-scanout-prerotate-panelres`, `feat/reshade-mes-patch`, `spike/vkbasalt-reshade`, `test-lsfg-so-ludashi-swap`, `docs/reshade-step3-plan`. (`test` remains on remote — separate/unrelated history.)
>
> **Preserved first:** the shelved **Steam cloud-saves** foundation commit (`6fcf27e7`) turned out to live **only** on `feat/mali-bcn-v4` (the `feat/steam-cloud-saves` branch had drifted to a docs commit). Cherry-picked it cleanly onto main → **`feat/steam-cloud-saves` now = main + the cloud-saves foundation** (`6d86f277`), ready to resume in a future version.
>
> **Note:** the `mali-v5` / `mali-v6` pre-release **git tags are mislabeled** (they point at main commits, not the build branch — `release.yml`'s tag defaults to the default-branch HEAD). The pre-release **APKs are correct**; only the tags are cosmetic. For future pre-releases off a non-main branch, set `target_commitish`.
>
> **Kept:** `main`, `feat/steam-cloud-saves` (re-anchored), `feat/save-backup-restore`. Other merged 2.4-era feature branches remain and could be swept separately.

## 2026-07-08 — 🎉 RELEASE: Bannerlator 2.5 (stable, LATEST) — the Mali hardening release

> **2.5 SHIPPED STABLE — `versionName 2.5`, `versionCode 40`, tag `2.5` (make_latest=true → offered to 2.4 users via the in-app updater). Release run `28911581500` GREEN, 3 flavors + update.json. main tip `34a43924`.**
>
> **2.5 = the Mali support hardening release.** Makes BC-texture (BCn) games work on **Mali / Xclipse** GPUs, with a full sign-off on real Mali-G57 (Helio G99) hardware, plus an in-game logging overhaul. Entirely app-side — no ImageFS reinstall; existing containers pull the new driver assets automatically.
>
> **Shipped (all from #70, now closed):**
> - **`Wrapper + bcn_layer`** graphics driver — primary Mali BCn path (leegao **bcn_layer shader-v3**); transcodes BC textures on the GPU. Device-proven: *MiSide* went from crashing → ~34 fps, 0 buffer errors on Mali-G57.
> - **`Wrapper-gamenative`** — experimental (Adreno-only) secondary driver.
> - Wrapper bumped to **bionic-vulkan-wrapper ETC2-Milestone-2** (kills `wrapper_DestroyBuffer` spam).
> - **BCn Layer Settings** dialog (force-decode, ETC2/ASTC transcode, image-view mode, debug logging).
> - **In-game logging overhaul**: Copy-logs button (pinned on-screen; the mali-v6 layout fix), selectable log location (Settings › Logs), co-located DXVK/DXGI/VKD3D logs, scrollable Wine debug-channels dialog.
> - release.yml notes hardened against backticks. bcn_layer `.so` kept **unstripped by design** (ongoing Mali debug).
>
> **Merge hygiene:** merged via `release/2.5-mali` (clean branch) after excising a stray shelved Steam-cloud-saves commit (`6fcf27e7`) that had drifted onto the mali branch from an earlier shared-worktree session — caught by scanning the merge file list before pushing.
>
> **Credits:** **leegao** (bcn_layer shader-v3 + bionic-vulkan-wrapper ETC2-Milestone-2). Testers: **@kylinzang** (Mali-G57, #70 — drove the whole effort + full sign-off), **@rizky2-crypto** (Mali-G610, #30). Experimental gamenative driver base: **GameNative** (utkarshdalal).
>
> **Follow-ups:** "Import .so" self-update button → 2.6 (needs its own issue). rizky2 (#30, G610) still open. Shelved Steam cloud-saves parked off main. Release notes also footnote the new [Proton 11.0-1 bionic build](https://github.com/The412Banner/proton-wine/releases/tag/build-p11-20260707).

## 2026-07-06 — 🔧 CHECKPOINT: dev-environment tooling + bridge knowledge corrected (no repo code change)

> **Session tooling/infra work (lives in `~/.local/bin` + memory, not the app repo). Main unchanged at `34cf6249`. Mali v3 build re-staged & awaiting Mali testers.**
>
> **Root-bridge knowledge CORRECTED (was hobbling us):** live-tested the logcat-bridge daemon (`~/logcat-bridge/module/system/bin/logcat-bridge-handler`) — its `exec|sh` verb runs `/system/bin/sh -c "$cmd"` and PRESERVES the command, so **pipes / grep / redirects / `$?` DO work ON-DEVICE**. The long-standing "never pipe / bare-verbs-only" rule was WRONG (a local-shell-quoting artifact, not a daemon limit). Real rule: pass the whole remote command as ONE quoted string. The daemon already exposes full root + safe verbs (`tail`/`pkg` logcat, `tomb`, `ps`, `props`, `proc`, `cat`/`ls`/`sql`, binary `write` preserving uid:gid:mode) — **no module change needed**. (Shizuku/no-root backend = separate future idea for the non-root community; the module needs on-device dev for that.)
>
> **3 persistent CLI helpers added to `~/.local/bin` (on PATH, survive every session):**
> - **`bridge '<cmd>'`** — run a root command on the device; auto-syncs the token (kills boot-drift "auth failed"), pipes run on-device. Bare verbs (`bridge ping|tomb|ps|pkg <pkg> -d|cat …`) pass through.
> - **`ci-watch <branch>`** — dispatch "CI Build (artifacts only)" → auto-capture run id → watch → GREEN/RED + failing-log tail. **Refuses to build main/master implicitly** (footgun guard — a bare test-run accidentally dispatched+was cancelled `28838588161`, hence the guard).
> - **`stage-apk <run-id|branch> <label> [flavor]`** — download the test APK → stage `/sdcard/Download/bannerlator-<label>-<sha7>-<flavor>.apk` → **host+device sha256 match** via `bridge`. Live-proven: `stage-apk 28837058621 mali-v3` → match ✓.
> - Chain: `ci-watch <branch> && stage-apk <branch> <label>`. Env overrides BANNER_REPO/WF/DIR/STAGE_DIR. All recorded in [[reference_logcat_bridge_root_access]] + MEMORY always-hot.
>
> **Mali v3 status (unchanged, awaiting testers):** branch `feat/mali-bcn-layer` `e3f4f90b`, CI `28837058621` GREEN, staged `bannerlator-mali-v3-e3f4f90-standard.apk` (600,577,679 B, sha `184b71ad…`). @kylinzang (G57/MiSide) + @rizky2-crypto (G610/CODMW, #30) invited on #70.

## 2026-07-06 — 🟢 CHECKPOINT: Mali BCn layer DEVICE-PROVEN + shader-v3 swap done (branch `feat/mali-bcn-layer` `e3f4f90b`, CI green, in testing)

> **NOT on main — on branch `feat/mali-bcn-layer` (rebased onto 2.4 main `15c7186c` → shader-v3 swap `e3f4f90b`), CI `28837058621` GREEN. Test build handed to Mali testers on #70. Merges as 2.5-preN once confirmed.**
>
> **✅ Mali device-proof (issue #70):** @kylinzang tested our earlier "Wrapper + bcn_layer" build on **Helio G99 / Mali-G57 MC2** — **MiSide** (BCn Unity game, previously crashed / black-purple textures) now **runs + renders correctly**. First real Mali proof (we can't test on the user's Adreno). Perf heavy (16-24fps, GPU 98-100%) but playable. He flagged 3 bugs: (1) BCn debug-log toggle DEAD (shipped `.so` had no logger), (2) `wrapper_DestroyBuffer: null buffer` batches per scene-load (non-fatal), (3) `BCN_MAX_TEXTURE_SIZE` unverifiable. And requested leegao's **shader-v3** (~3.5× faster ASTC).
>
> **✅ shader-v3 swap (this session):** rebased branch onto 2.4 main (1 trivial import conflict), then:
> - Swapped `app/src/main/assets/graphics_driver/extra_libs.tzst` → `usr/lib/libbcn_layer.so` to leegao's **shader-v3** Release asset (arm64-v8a, NDK r29, kept **UNSTRIPPED** per user's debug-symbols call → tzst 18.8→29.8MB, **STRIP before merge**). Layer manifest identical, unchanged.
> - **Env-var reconcile:** kept `ENABLE_BCN_COMPUTE`/`BCN_COMPUTE_AUTO`/`BCN_TRANSCODE_TO_ETC2`/`BCN_TRANSCODE_TO_ASTC`/`BCN_COMPUTE_IMAGE_VIEW`; **dropped** `BCN_LF`/`BCN_LL` (v3 logs to **stderr**→Wine debug log) → replaced with `BCN_LAYER_LOG_LEVEL=info,error` (debug-log toggle now works); **dropped** `BCN_MAX_TEXTURE_SIZE` (removed upstream) → removed its dropdown + array + string. v3 ASTC needs `VK_KHR_8bit_storage`+`shaderInt8` (Valhall Mali have it; self-disables gracefully otherwise).
> - **✅ ADRENO-SAFE (confirmed, triple-gated):** bcn_layer block only runs on the "Wrapper + bcn_layer" driver + hardcoded `getVendorID() != 0x5143` (Qualcomm) gate + Vulkan-loader `enable_environment` → `ENABLE_BCN_COMPUTE` NEVER set on Adreno. Only cost to others = ~11MB APK from the unstripped `.so`.
>
> **Issues consolidated:** #70 = single Mali/BCn tracking issue (retitled; roadmap checklist). #54 closed into it; #53/#63/#64 already closed. #30 (@rizky2-crypto, Dimensity 8200 / **Mali-G610** / CODMW loading-screen crash) invited as 2nd Mali tester — kept OPEN until BCn confirmed to fix it. Both testers have the direct Actions build link (run `28837058621`).
>
> **CREDITS:** leegao already in README (line 382); **explicitly credit shader-v3 in the 2.5 release notes** (BCn ships in 2.5, not 2.4) — user asked. **NEXT:** await Mali test results (kylinzang before/after fps + working debug log; rizky2-crypto CODMW) → strip `.so` → merge 2.5-preN.

## 2026-07-06 — 🚀 RELEASE CHECKPOINT: BANNERLATOR 2.4 SHIPPED (stable, latest) — main `c4010ce0`, vc39

> **2.4 is LIVE and marked latest:** https://github.com/The412Banner/Bannerlator/releases/tag/2.4 — built by `release.yml` run `28834510336` (3 flavors standard/pubg/ludashi ~589 MB + `update.json` vc39 so the in-app updater offers it to everyone). versionCode 38→39, versionName 2.3→2.4. Release body = polished markdown (logo banner + shields.io badge chips + feature blocks + issue links + credits); README thoroughly updated (What's New in 2.4, 2.3 nested to history, Full Features refreshed, new "Community reports & requests" credits block). **NEXT stable = 2.5; anything built from here = 2.5-preN, vc40+ until told to cut.**
>
> **Everything in 2.4 (all merged to main this session, all device-proven):**
> - **Stage 2 fullscreen (#71)** — FILL (crop-to-fill, no bars/no distortion = `ViewTransformation.aspect = max(sx,sy)`) + INTEGER (`max(1,floor(min))`, pixel-perfect centered), on top of Stage-1 Off/Fit/Stretch. Centralized in `ViewTransformation.update(...,mode)` → all 3 renderers (GL/Vulkan/ASurface) + TouchpadView inverse-map; STRETCH stays the only renderer-special-cased mode. Live-recompute on toggle/setter. **5-button SEGMENTED drawer selector** (applies live, drawer stays open; new `onSetFullscreenMode` IntConsumer → `applyFullscreenMode()`). GL device-proven (Titanfall2 screenshots) + user-confirmed all fullscreen.
> - **In-app file picker (#73)** — reuse the REAL File Manager (`FileManagerScreen` pick-mode) via new themed `FilePickerActivity` for ALL imports (WCP/assets, ICP, wallpaper, icons, saves, settings, shortcuts, adrenotools, BigPicture); SAF kept as secondary "Pick via system…"; picked path wrapped `Uri.fromFile` so downstream import code unchanged; large imports show determinate **percent+ETA** (`ImportEtaTracker`, reuses download `formatEta`). New: `FilePickerActivity.kt`, `util/InAppFilePicker.kt`, `util/ImportEtaTracker.kt`. Device-proven.
> - **File Manager image thumbnails** — image files (jpg/jpeg/png/webp/bmp/gif) render real thumbnails (Coil, 36dp decode, cached, async, generic-icon fallback) instead of the generic file icon. Device-confirmed on the Select-wallpaper screen.
> - **Per-game persistence** — scaling mode (was session-only, reverted to base filter; now saved on pick Vulkan `onUpscalerApply`+GL `onGlUpscalerApply`, restored via `resolveScalingMode()`, restored preset≥3 forces Native OFF at seed), fullscreen mode, and FPS-HUD drag position (all 3 overlays fire `onMovedListener` → keys hudPosCV/CH/GH, one-shot layout-listener restore, clamped).
> - Earlier in the session (also 2.4): DLC picker + DepotSizeResolver true-size install fix + detail-page size breakdown + download ETA/speed; container wallpaper picker (#66); Vulkan magnifier cursor-follow + no-dim (#44); save backup/restore; help-button crash fix; list-view scrape cover.
>
> **Issues:** **#73 CLOSED** (shipped) + **#75 CLOSED** (Soft Stretch ≈ our Fill; upscalers already present; DLSS not feasible on Adreno/Mali — replied w/ real ReShade effect list) — both linked to the 2.4 release. Left open (not shipped): #74 (DRS/presets), #70+#54 (Mali/BCn), #68 (iQOO crash), #65/#61/#56/#30.
>
> **Credits handed out** (release notes + README): @kylinzang (#71, #73), @SombraShadow (#66), @abdogm (#44), @Devaspe (install-blocker), + upstream stack. Upstream-source triage done earlier (5 sources, nothing to adopt; GameNative Turnip driver eval DROPPED per user). 2.4 release-notes HTML preview artifact published + saved to device (`/sdcard/Download/bannerlator-2.4-release-notes.html` + `-release.md`).

## 2026-07-06 — ✅ EOD CHECKPOINT: DLC picker + download ETA/speed + fullscreen aspect-ratio ALL MERGED (main `c9d8df84`); #71 closed; Stage 2 deferred

> **main = `c9d8df84`, combined artifacts build `28822949633` GREEN. Still vc38 → next stable 2.4-preN (vc39+). No release cut.** Everything from this session is now on main:
> - **DLC picker** (`feat/dlc-picker` merged `37b0ac32`) — detail page "Choose DLC" → ModalBottomSheet of owned DLC checkboxes (scrollable + Done); uncheck → `SteamPrefs.excludedDlc` → dropped from the real download via explicit `AppItem.depot`/`manifest` list + sizes update live (`recomputeSizeDisplay`). Completion guard + progress denominators made exclusion-aware (else an opt-out DL would false-fail). **Device-proven** (JC3: all DLC unchecked → only 3 base depots fetched). Stage-2 unowned/locked rows DROPPED per user (keep list clean).
> - **Download ETA + speed** (`8b371817`) — EMA-smoothed compressed-byte rate → `etaSeconds=(dTotal-dDone)/rate`, shown on detail page, download-manager row, AND FGS notification (`… 45% · 12.4 MB/s · ~8 min left`). `formatEta`/`formatDownloadSpeed` in DownloadModels; `DownloadEntry.speedBps/etaSeconds`. Hidden when paused/queued. Green, not device-tested.
> - **Fullscreen aspect-ratio modes (issue #71)** (`c9d8df84`) — Off/Fit/Stretch replacing the stretch bool. `Container.fullscreenMode` int + JSON migration; all 3 renderers (GL/Vulkan/ASurface) branch on `isStretch()` (STRETCH→fill, OFF/FIT→ViewTransformation letterbox); TouchpadView letterbox mapping for FIT; per-container dropdown + per-game shortcut override + live drawer cycle **remembered per game**. **Device-proven on BOTH GL and Vulkan** (Titanfall 2 @1024×768 4:3 on 16:9 Pocket FIT: Off/Fit=pillarbox correct, Stretch=distorted wide). FILL/INTEGER enum-stubbed. Note: DXVK HUD stretches in Stretch mode = expected (baked into guest frame); app's own HUD is a host overlay and doesn't. **Issue #71 commented (commits+build+next-release+confirm-invite) and CLOSED.**
>
> **🔜 ONLY OPEN ITEM — Stage 2 fullscreen (FILL crop + INTEGER scaling): DEFERRED, build ONLY after the user is home on Wi-Fi** (he lost internet leaving work). FILL = ViewTransformation `max` instead of `min` (fill+crop, no distortion); INTEGER = floor(fit scale) (pixel-perfect, retro-only). Stage-1 plumbing done → incremental: VT math + each renderer's mode branch + touch mapping (fill crops / integer centers) + expose in the 2 dropdowns + drawer cycle. Will build, then ping kylinzang on #71 to test.

## 2026-07-06 — ✅ CHECKPOINT: DLC-ownership fix + size breakdown + magnifier no-dim MERGED (main `29d5006`); DLC picker on branch

> **main `29d5006`, CI `28806902047` (artifacts-only) GREEN. Still vc38; next stable = 2.4-preN (vc39+). No release cut.** Everything below merged this session on top of the DepotSizeResolver line:
> - **Detail-page size breakdown** — headline on-disk FOOTPRINT (block-rounded per-file `ceil(size/4096)*4096`; real measured `du` once installed) + `Download` (compressed) + `PICS estimate (Steam)` (labeled) + `Free space`/"won't fit". DB v4→v5 `real_disk_bytes`, v5→v6 reset (a v5 build skipped every file via `linkTarget "" != null` → footprint == content; fixed with `isNullOrEmpty()`). REALITY: for VPK-packed games (HL2) block slack is tiny (~5.6 MB) so footprint ≈ content; the extra ~2 GB "on disk" is install-time overhead (staging/prefix/emulator) NOT in the manifest — user accepted "best it's going to get".
> - **Magnifier whole-screen dim FIX** (`59e2e20`) — `MagnifierOverlay` is a Compose Dialog → `FLAG_DIM_BEHIND` dimmed the whole game surface. `clearFlags(FLAG_DIM_BEHIND)` + `setDimAmount(0f)` (mirrors PauseBoxOverlay). Renderer-independent → fixes GL AND Vulkan in one place.
> - **Unowned-DLC-depot fix** (`29d5006`) — an owned game's PICS depot list includes its DLC depots; selecting an UNOWNED one made the engine try a keyless depot → 0 bytes → false "incomplete" on the owned game (devaspe's **See No Evil 313830**: soundtrack DLC depot **320210** he doesn't own; the 131 MB game downloaded 100% but was rejected). Detection: NOT `config/dlcappid` (Steam doesn't tag it — verified: Just Cause 3's owned DLC depots had no dlcappid) but **`extended/listofdlc` + depot_id == DLC appId**. Skip DLC depot if `!licensed`; `pruneDepots` drops stale ones on re-sync. `getLicensedAppIds()` = ownership.
> - **"Includes DLC:" line** — owned DLC bundled with the game, shown on the detail page (DB v6→v7 `included_dlc`; `getIncludedDlcEntries`). Confirmed on device: JC3 shows its 11 owned DLC.
>
> **🔨 DLC PICKER — branch `feat/dlc-picker` head `bfd4847c`, CI `28810646828` GREEN, NOT merged.** Tap **"Choose DLC"** (OutlinedButton) → **ModalBottomSheet** (scrollable + nav-bar inset + "Done") of OWNED DLC checkboxes (default checked). Uncheck → `SteamPrefs.excludedDlc` → dropped from download via explicit `AppItem.depot`/`manifest` list + **sizes update live** (`recomputeSizeDisplay` sums depots minus excluded). **Stage 2 (unowned/locked rows) DROPPED per user** — owned-only, no clutter, no store-API name fetch. Device-UX-proven (button prominent, sheet scrolls); NOT yet proven that uncheck removes DLC from a real download → device-verify then merge.
>
> **NOT device-tested:** magnifier no-dim; See No Evil unowned-DLC fix (not owned on our account — logically certain from devaspe's log, needs library re-sync); picker actual opt-out.

## 2026-07-06 — ✅ MERGED TO MAIN: DepotSizeResolver — true sizes + executor fix + detail-page size breakdown (main `38cc00b`)

> **Fast-forwarded `feat/depot-size-resolver` onto main (`9f5bf74`→`38cc00b`), CI run `28797042366`. No release cut, versionCode still 38 (=2.3); next stable = 2.4-preN (vc39+).** Five commits, all device-proven except where noted:
> - **`26f22f5`** DepotSizeResolver — fetches TRUE depot sizes from the CDN manifest (no depot key/auth token) → the install-completion guard compares written bytes vs manifest-true instead of the over/under-reporting PICS estimate. Device-proven: two full downloads (Brawlhalla 291550, "the static speaks my name" 387860) completed, guard logged `Complete: 64.1 MB of 67.8 MB manifest-true (≥90%)`, zero false "incomplete".
> - **`d3fc902`** fix: don't `cdn.close()` the shared OkHttpClient — closing it shut down the dispatcher executor the real downloader reuses → every download hung at 0% with `executor rejected` (device-repro Brawlhalla; fixed + device-proven).
> - **`0b70e7b`** detail-page size breakdown: headline on-disk FOOTPRINT (block-rounded per-file estimate; real measured `du` once installed) + `Download` (compressed) + `PICS estimate (Steam)` (labeled) + `Free space`/"won't fit". DB **v4→v5** additive `real_disk_bytes`. Also adds `SteamDatabase.onDowngrade` (rebuild-not-throw) — fixes the v→older rollback crash that bricked the Steam screen.
> - **`4e153f9`** backfill footprint for games resolved pre-v5.
> - **`38cc00b`** fix: footprint skipped EVERY file — `FileData.linkTarget` is a protobuf string (`""`, never null) for regular files, so `!= null` skipped all → fell back to content size; use `isNullOrEmpty()`. DB **v5→v6** zeros the bad values to force recompute. Device-DB-proven (HL2 depot 234 3175→4096 rounded).
>
> **Footprint reality (user-accepted "best it's going to get"):** for HL2 the block-rounding adds only ~5.6 MB (HL2 packs assets into big .vpk files → ~2800 large files, minimal block slack) → headline stays ~8.4 GB. The ~10.6 GB seen on disk before = INSTALL-TIME OVERHEAD (download staging / Wine prefix / Goldberg emu) OUTSIDE the manifest, unpredictable pre-install. Pre-install shows honest content estimate; post-install shows real `du`. Meaningful gap only for many-small-file games.
>
> **STILL OPEN (not blocking merge):** the ORIGINAL Greyfox (appId **341310**, one word — NOT 313830=See No Evil) over-report install-blocker never reproduced on device. GameNative lead: their same-class bug (#928 Black Desert 791GB vs 93) is DEPOT OVER-INCLUSION (unlicensed region/platform + systemDefined depots), so Greyfox may be a depot-SELECTION issue, not per-depot sizing. Installed-`du` footprint path also not yet device-verified (HL2 not installed).

## 2026-07-06 — ✅ CHECKPOINT (pre-reboot): DepotSizeResolver green + INSTALLED + PARTIAL device-test; install-blocker not yet reproduced

> **Resolver APK `1f262e3` (CI `28784305760` GREEN) STAGED + INSTALLED + verified** (installed base.apk sha256 = `6f47720791a02e5164547e14d2677ea2c35922043ac544724ea8d70d496d10d4` = `bannerlator-depotsize-1f262e3-standard.apk`, 589,648,938 B). branch `feat/depot-size-resolver`, NOT merged, no version change.
> **✅ On-device findings (steam.db pulled to /sdcard/Download/steam_dump.db — sqlite3 absent on device, queried locally):**
> - **⭐ THE #1 RUNTIME UNKNOWN IS RESOLVED:** CDN manifest fetch works with **no depot key / no auth token** on real hardware. HL2 (appId 220) `depot_manifests.real_size_bytes` populated for all 4 depots after opening its detail page → `downloadManifestFuture` returns real sizes for real depots. Resolver functioning (login-gated, on library worker).
> - **HL2 real == PICS exactly** (sum 8,990,704,030 = both `size_bytes` and `real_size_bytes`). HL2's PICS was already accurate at CONTENT level; the "10.66 GB" was on-disk `du` footprint (block rounding), NOT manifest content. So HL2's detail number does NOT visibly change — only the `~` drops once resolved. Detail-page async behavior confirmed (user's 06:28 `~8.4 GB` = pre-resolve window; re-open drops the `~`).
> - **⚠️ NOT yet proven — the actual install-blocker fix.** HL2 = cosmetic under-report only. Reproduce the OVER-report failure with **appId `313830` / depot `313831`** (devaspe's game — appid visible in his Discord log screenshot: "=== Download complete: appId=313830 ===" → "INCOMPLETE: only 130.0 MB of 181.4 MB (<90%) — refusing to mark installed"). Own/install 313830 → confirm it marks **Installed**, not "Download incomplete."
> - Left `/sdcard/Download/steam_dump.db` on device (harmless).
> **🔁 USER REBOOTING DEVICE** → logcat-bridge token rotates; may need re-sync (`cp /data/data/com.termux/files/home/.logcat-bridge.token ~/.logcat-bridge.token` then `python3 ~/scratchpad/getlog.py ping`) before driving the device again.
> **NEXT:** post-reboot → reproduce 313830 install (fail→pass). If proven → rebase `feat/depot-size-resolver` onto current main + FF-merge (no release cut) + reply to devaspe. Detail: memory `project_bannerlator_true_size_resolver`.

## 2026-07-06 — 🔨 CHECKPOINT: DepotSizeResolver built (branch `feat/depot-size-resolver`); help-crash fix MERGED to main `cbfc0d6`

> **Help-button crash fix ✅ MERGED to main `cbfc0d6`** (rebased+FF, no version change). File Provider `?` NPE resolved (`null`→`View(context)`).
> **🔨 DepotSizeResolver BUILT — branch `feat/depot-size-resolver` off `cbfc0d6`, commit `1f262e3` (native-steam-engineer, lead-reviewed), CI `28784305760` building. NOT device-tested. No versionCode change.**
> **Why:** devaspe (Discord) hit a device-proven install-blocker — PICS OVER-reports a game's size (appId 313830/depot 313831: PICS 181.4 MB vs ~130 MB real content) → the false-complete guard `SteamDepotDownloader.kt` compares on-disk (130) vs PICS (181.4) at a 90% threshold → rejects a FULLY-DOWNLOADED game as "Download incomplete". Mirror of the HL2 UNDER-report (8.4→10.66, cosmetic, band-aided `b8e9e5b`).
> **Fix:** NEW `store/DepotSizeResolver.kt` fetches TRUE per-depot manifest sizes via base-JavaSteam CDN (`SteamContent.getManifestRequestCode` + `getServersForSteamPipe` + `cdn.Client.downloadManifestFuture`); manifest `totalUncompressedSize`/`totalCompressedSize` come from `ContentManifestMetadata` INDEPENDENT of filename decryption → **no depot key, no CDN auth token needed** (verified by decompilation; runtime-unverified for real depots = device-test only). The guard now compares vs the manifest-true total (2-tier; genuine depot-skip catch preserved — a SELECTED depot delivering 0 bytes still fails). ADDITIVE DB v3→4 (`ALTER ADD COLUMN`; pre-v3 keeps legacy recreate → **no library wipe** for current users). Strict CM-pump discipline (gated on `!isLoggedIn||isDownloadActive()`, single library worker, watchdog bumps `bumpPendingJobTimeouts(60s)`, degrade-never-throw). 5 files: `DepotSizeResolver.kt`(new), `SteamDatabase.java`, `SteamDepotDownloader.kt`, `SteamGameDetailActivity.kt`, `SteamRepository.java`.
> **LEAD decisions:** kept the relaxed unresolved-guard branch (dropping it reintroduces 313830 false-fail pre-resolve). Known-not-done: DL-card/library-seed download denominator still PICS (install bar + guard use real).
> **NEXT — device-test `1f262e3`:** (1) open 313830-type detail (resolver runs)→Install→marks Installed not "incomplete"; (2) HL2 detail shows real ~10.66GB (no `~`) + genuine skip still fails; (3) no ANR; (4) resolver skipped during active download; (5) offline/not-logged-in degrades to `~est`; (6) v3→v4 no library wipe. If proven → rebase onto current main + FF-merge, no release cut. Detail: memory `project_bannerlator_true_size_resolver`.

## 2026-07-06 — 🐞→✅ CHECKPOINT: File Provider help-button crash fixed (branch `fix/settings-help-crash`)

> **Morning device-test of the main APK (`636f0ea`) surfaced a crash.** Settings → Experimental → *Enable File Provider* **`?` help button crashes the app.** Root cause (from on-device crash buffer, 07-06 05:10:57, `com.winlator.banner`): `java.lang.NullPointerException: …View.getContext() on a null object reference at AppUtils.showPopupWindow(AppUtils.java:168) ← at SettingsScreen.kt:808`. `SettingsScreen.kt:808` called `AppUtils.showHelpBox(context, null, R.string.help_file_provider)` — **null anchor**; `showPopupWindow` immediately does `anchor.getContext()`. It was the ONLY 1 of the app's 9 Compose help buttons passing `null`; the other 8 (ContainerDetailScreen ×4, cds/payload ×4) pass `View(context)`.
> **Fix:** `null` → `android.view.View(context)` (fully-qualified, no import change), matching every other call site. The button itself is intentional — a help popup for the File Provider setting (`R.string.help_file_provider`); only the anchor was wrong.
> **Branch `fix/settings-help-crash`** off main `5a583bc`, commit **`26b364b`**, pushed. **CI `28780961908` building.** On green → stage APK + offer FF-merge to main. NOT device-tested yet.
> **State:** main = `5a583bc` (= `636f0ea` features + 2 docs commits). Combined main APK `bannerlator-main-636f0ea-standard.apk` (589,640,156 B, sha `c43660c2…`) staged. Everything since 2.3 still unreleased (vc38); next cut = 2.4-preN (vc39+).

## 2026-07-05 — 🌙 CHECKPOINT (end of day): ALL issue-session features MERGED to main `636f0ea`; main APK building; user testing in the morning

> **main = `636f0ea`.** Everything on main SINCE 2.3 (vc38, unreleased — versionCode still 38): Save Backup/Restore v1+per-game+caution (`bc7d4dc`/`a8ddf7d`/`b46f174`/`da62916`/`0c2930a`/`53f528a`, restore device-proven, per-game backup untested); Scrape-cover-in-list (`087a8ca`); Vulkan magnifier cursor-follow #44 (`36b1962`+`0df8984`, device-proven, **#44 CLOSED**); container wallpaper picker + per-container/global + symlink fix #66 (`2420dbe`+`db27d1c`+`636f0ea`, device-proven, **#66 CLOSED w/ commit links**). Both issues closed on GitHub, each ships in 2.4-preN.
> ✅ **Artifacts-only build of main `28763213229` (headSha `636f0ea`) GREEN → STAGED `/sdcard/Download/bannerlator-main-636f0ea-standard.apk`** (589,640,156 B, sha256 `c43660c2baa354c5154c8ff95fe7b69bc9eff63899e7c0adce24dfefdbc29601`) = combined "everything since 2.3" APK, ready for morning device-test.
> **🌙 User AFK (bed) — will device-test the new main APK in the MORNING.** ⚠️ Wallpaper migration note: existing containers show DEFAULT wallpaper until user re-picks the global wallpaper once. Morning test targets: (1) magnifier follows cursor windowed+fullscreen; (2) wallpaper global→relaunch all containers show it, per-container isolates; (3) save-backup per-game round-trip; (4) scrape-cover in list menu.
> **NEXT release cut = 2.4-preN, bump versionCode → 39+ (per versioning rule).** Not on main: Mali/BCn-layer (branch `feat/mali-bcn-layer`, handed to @kylinzang, awaiting Mali results).

## 2026-07-05 — ✅ CHECKPOINT: issues session — magnifier MERGED, scrape-cover MERGED, wallpaper in-flight

> **State of main = `0df8984`.** Merged to main THIS session (all clean FF, NO release cut, still vc37/2.2.2):
> - **`53f528a`** — Save Backup/Restore + "not foolproof" caution advisory (caution card made theme-fluid after a black-slab bug).
> - **`087a8ca`** — `fix(shortcuts)`: **Scrape cover** now in the LIST-view overflow menu too (was grid-only; shared `scrapeCoverFor()` lambda).
> - **`36b1962` + `0df8984`** — `fix(vulkan)`: **magnifier follows the cursor** (issue #44), fullscreen THEN windowed. GL applies cursor-follow with no fullscreen gate; Vulkan only had fullscreen, so windowed containers didn't track. Guest-space `magOff` composed with the scene transform. **BOTH device-proven on Adreno.** Issue #44 commented + CLOSED (ships in 2.4-preN). Known limitation left: nativeMode direct-scanout magnify (documented, not fixed). Detail: memory `project_bannerlator_vulkan_magnifier_cursor`.
>
> **🖼️ IN-FLIGHT — branch `fix/container-wallpaper-picker` (issue #66), rebased ONTO main `0df8984` (clean, no overlap w/ magnifier's VulkanRenderer.java):**
> - `e256b7c` picker (Image bg had no picker — Compose `GetContent()` + preview) — **device-proven** (user: "picker works").
> - `d106bf8` per-container vs global scope (`BackgroundScope{GLOBAL,CONTAINER}`, back-compat theme str, "Apply wallpaper to" dropdown).
> - `8973700` **the real bug**: "global" was NOT global. `ImageFs.CONFIG_PATH=/home/xuser/.config` and `home/xuser` is the PER-CONTAINER symlink → the wallpaper saved under whatever container was active during editing (device proof: only 2/3 containers had the file). Fix = **relocate sources to fixed non-symlinked `home/.wallpapers/`** + **launch-time staleness gate** (`wallpaperNeedsRegen`: regen bmp when source newer than `CACHE_PATH/wallpaper.bmp`) OR-ed into `XServerDisplayActivity:1857`. ⚠️ Migration: old wallpapers orphaned → containers show DEFAULT until user re-picks global once.
> - **CI `28762849516` (headSha `8973700`) building.** Scope + propagation NOT yet device-tested. On device-proof → already-rebased → clean FF to main (magnifier preserved as base) → close #66. Detail: memory `project_bannerlator_container_wallpaper`.
>
> **Staged APKs (all this session):** magnifier fullscreen `bannerlator-magnifier-cursor-36b1962`, magnifier windowed `bannerlator-magnifier-windowed-0df8984` (sha `a8709b6e…`), wallpaper `bannerlator-wallpaper-scope-c7ad800` (superseded by the 8973700 build once green).

> **🗄️ CHECKPOINT (2026-07-05) — branch `feat/save-backup-restore`: PER-GAME SAVE BACKUP built + CI-green + staged (NOT device-tested).**
> Iteration 2 on top of shipped v1 container-scoped Backup/Restore (`bc7d4dc` + phantom-shortcut fix `a8ddf7d`, restore device-proven).
> **What shipped this session (2 commits):**
> - **`b46f174`** `feat(save-backup): per-game save discovery, confirm checklist, and layout chooser` — implemented by android-app-engineer per spec `docs/spec_per_game_save_backup.md`. CI `28758354015` GREEN (~8m28s). APK `bannerlator-pergame-save-b46f174-standard.apk`.
> - **`da62916`** `fix(save-backup): relabel whole-container scope to "All game saves (whole profile)"` — user asked to disambiguate the scope label (was "This whole container", read like a full-prefix clone). CI **`28758786986` GREEN**. **APK STAGED** `/sdcard/Download/bannerlator-pergame-save-da62916-standard.apk` (589,632,642 B, sha256 `92c756df510938fec54c285dac2049ece45f7239d3e00b10e6e78a4f88f5b9c0`, matches artifact). **← current head, test this one.**
> **Feature (resolution = heuristic + confirm + remember, user-locked):** overflow → Backup/Restore save → Back up → **scope picker** {All game saves (whole profile) | A specific game ▸} → GamePicker (from `loadShortcuts`, cover art) → discovery spinner → **confirm checklist** (candidate save folders + per-folder sizes + manual-add + re-scan) → **format/layout chooser** {GameHub `/drive_c/users/steamuser/…` (default) | Winlator-native `drive_c/users/xuser/…`} → backup. Remembered games skip discovery via sidecar `<container>/app_data/save_maps.json` (keyed wmClass else lnk name).
> **Files:** NEW `core/SaveLocator.kt` (6 roots, depth 1&2 scan, scoring exact100/contains70/token≥50%→50/Levenshtein≥.85→40, keep ≥50, nested de-dupe, sizes, sidecar); EDIT `core/GameSaveBackup.kt` (`enum BackupLayout{GAMEHUB,WINLATOR}` + scoped `backup(…,roots,gameName,layout)`, `roots==null`=whole profile); EDIT `ui/screens/ContainersScreen.kt` (BackupScope/GamePicker/GameSaves/format states) + `ContainersViewModel.kt` (`shortcutsFor`); +25 strings.
> **Scope clarification (user Q):** "whole profile" backs up **`.wine/drive_c/users/xuser/**` only** (all game saves in the user profile) — NOT the full prefix/registry/Program-Files. Games that save into their install dir or registry are out-of-scope this iteration.
> **⚠️ 1 deviation:** manual "add folder…" uses an in-app folder browser rooted at xuser, NOT SAF `OpenDocumentTree` (SAF can't enumerate app-private internal storage); same canonical-prefix escape guard applies.
> **NEXT — user device-test (`da62916`):** per-game backup GameHub fmt → `unzip -l` roots `/drive_c/users/steamuser/…`, only that game's folders; Winlator fmt → `drive_c/users/xuser/…`; wipe save → restore → returns, others untouched; re-open → pre-ticked from sidecar. If proven → `2.4-preN` candidate (vc39+), NO stable cut. CI-GREEN ONLY, not device-proven.
>
> **⏸️ CURRENT STATE (2026-07-04) — on `feat/download-manager-stores`: Amazon wired into DL manager (delivered, awaiting device test) + ⬇ button on all store headers (delivered); NOW restyling all non-Steam store pages to follow the app theme.** Base: Download Manager v1 MERGED TO MAIN (`ef717fb`, fast-forward), all device-proven, still vc37/2.2.2, NO release cut.
> **🎨 THEME/RESTYLE JOB IN FLIGHT (user req): make EVERY non-Steam store screen follow the app theme + refresh the outdated detail pages to match Steam.** Problem: Amazon/Epic/GOG pages had 13–25 hardcoded `0xFF…` colors each (Steam detail=0, the reference) → ignored theme presets. Scope (confirmed): **(a) 3 DETAIL pages** → full Steam-layout restyle via NEW shared `StoreDetailComponents.kt` (StoreDetailHeader/StoreHero+gradient/InfoChip/StoreBadge/StoreProgressBar/StoreStatusText/StoreActionButton/StoreActionRow/StoreSection, all theme-token) + keep a SUBTLE per-store badge (Epic blue/GOG purple/Amazon orange via StoreStyle); **(b) 4 LIST pages** (Amazon/Epic/GOG games + Epic free-games) → de-hardcode literals → theme tokens (layout kept). Mapping keeps DEFAULT look byte-identical; preserves installed-green + store-brand accents + all logic/sections (Updates/DLC/CloudSaves). Steam NOT touched. **Progress: ✅✅ ALL 4 TRACKS DONE + COMMITTED `220b95e`. First build `28721491746` RED (GOG detail missing MaterialTheme import) → fixed `7ec2b50` → REBUILD `28721728991` GREEN → APK DELIVERED** `/sdcard/Download/bannerlator-store-restyle-7ec2b50-standard.apk` (589,556,080 B, sha256 `c31b8458e47583c2…381872`, verified vs CI, media-scanned); NOT yet installed — **⏳ PRE-TEST CHECKPOINT, user about to device-test. Nothing on this branch device-proven yet; NOT merged. Full pre-test resume anchor in memory `project_bannerlator_download_manager_stores`.** **Device-test:** change theme → all store list+detail recolor; 3 detail pages match Steam layout + store badge; actions still work; + the pending Amazon DL-manager end-to-end (⬇ button/badge/live rows/cancel/kill/uninstall) carries onto this build. Rendered UI fully theme-compliant (residual `0xFF` only on dead write-only color fields). On green → deliver APK → device-test theme switch across every store list+detail + eyeball the 3 restyled detail pages vs Steam.
> **→ NEW ACTIVE BRANCH `feat/download-manager-stores` (off main `ef717fb`):** route Amazon/GOG/Epic into the SAME `DownloadRegistry` as live producers, wired at the Activity callback seam (engines untouched). Order Amazon→GOG→Epic (lowest risk first). Recon plan done + saved to memory. **✅ Phase A (Amazon) BUILT + DELIVERED — commit `bc6d27c`, CI `28719688616` GREEN:** new `StoreDownloadHooks.kt` shim + `AmazonLibrarySync.kt` seeder + wired BOTH Amazon install entry points (detail + games-list) with real byte progress + `DownloadRegistry.init()`/seed in onCreate. **Standard APK DELIVERED** `/sdcard/Download/bannerlator-amazon-dlmgr-bc6d27c-standard.apk` (589,563,204 B, sha256 `d0ad8a2e094dcb94…c6448`, verified vs CI, media-scanned) — **⚠️ SUPERSEDED, do NOT test on `bc6d27c` (no ⬇ button on Amazon screens → can't open the DL manager from Amazon).** **➕ Then user req: ⬇ DownloadsButton + GLOBAL active-count badge added to ALL store headers** (Amazon/Epic/GOG games+detail + Epic free-games = 7 files), commit `8f01d81`, **CI `28720297597` GREEN → APK DELIVERED** `/sdcard/Download/bannerlator-stores-dlbtn-8f01d81-standard.apk` (589,563,301 B, sha256 `0f0367cd86507ced…04cb8fa`, verified vs CI, media-scanned); NOT yet installed. UI-only, badge global. **⏳ TEST ON THIS APK — user device-tests Amazon end-to-end:** ⬇ button + badge present on Amazon screens; (1) fresh install → LIVE row + byte bar on BOTH entry points (detail + games-list); (2) Cancel clears row; (3) INSTALLED survives app kill (Library seed); (4) Uninstall clears Library row. Then Phase B GOG. Parked: DepotSizeResolver true-size spec (memory).
> - **✅ Verified uninstall (spinner + confirmation) DEVICE-PROVEN** (`f561252`): blocking `UninstallProgressDialog` during the verified recursive delete, then a confirmation. Replaced the old fire-and-forget delete.
> - **🎉 SAME-SESSION UNINSTALL→REINSTALL WEDGE = FIXED (device-proven).** Uninstalled HL2 → immediately reinstalled same session → **10.65 GB → `is_installed=1`, NO wedge.** Cause of fix: the blocking spinner serializes delete-before-reinstall (= the reinstall-guard, for free) + cleared the orphaned `queued` row.
> - **🐞→✅ Black-box confirmation FIXED (`eb7dd55`) + DEVICE-PROVEN (user, latest screenshot).** System `Toast` rendered as an empty black box on this ROM (targetSDK 28) → replaced the 3 Steam uninstall Toasts with **`UninstallResultBar`** (themed auto-dismiss ~2.2 s snackbar bar). **Installed + confirmed on device: the bar shows real text, no black box.** All Steam-side uninstall feedback now works end-to-end.
> - **🐞→✅ Bug-1 (>100% size TEXT) FIXED (`b8e9e5b`).** The *percent* was already clamped everywhere; only the byte-count text could read done>total when PICS under-reports size (HL2 8.99 GB estimate vs 10.66 GB real). Fix: grow the install denominator when `installDone>iTotal`, mirroring the existing download-bar guard (`SteamDepotDownloader.kt:~510`); corrected total flows to both the detail page + DL card. **CI build `28717970189` GREEN → standard APK DELIVERED** `/sdcard/Download/bannerlator-size-text-fix-b8e9e5b-standard.apk` (589,559,803 B, sha256 `f773616a212b8b2e…0dc4d68`, size+sha verified vs CI, media-scanned). **✅ DEVICE-PROVEN:** full HL2 download to completion — total grew past the 8.4 GB estimate and the reported size tracked reality (no >100% byte-text overshoot). Evidence: HL2 install dir = 10 GB on disk. **→ ALL branch items device-proven; branch CLEAN, ready for the merge decision (user's call; verify fast-forward at merge; no release cut).**
> - **🐞 Bug-2 (DL card stale-100% on reused entry) — DEFERRED by user (not recurred since blocking-uninstall).** Root cause found: `SteamDepotDownloader.kt:396` gates the counter-reset on `get(dmKey)==null`, so reinstalling a previously-INSTALLED game keeps stale counters; fix if it resurfaces = gate reset on `attempt==0`.
> - Steam/Goldberg merged to main (`c89dc03`, no release, vc37/2.2.2). PARKED: Epic/GOG/Amazon registry wiring.
> **NEXT:** build `28717970189` green → deliver standard APK → user glances at a download's size text (never overshoots) → branch is clean → **merge decision (branch→main; check fast-forward vs merge commit).** No release cut planned. Detail below + memory `project_bannerlator_download_manager`.

---

## 2026-07-05 — ✅✅ Restore DEVICE-PROVEN + 🐞→✅ phantom-shortcut fix (rebuild in flight)

> **RESTORE PROVEN END-TO-END on device** (user, build `bc7d4dc`): restored the GameHub Titanfall 2 zip via ⋮→Backup/Restore save→Restore→GameHub backup→pick zip→confirm → launched TF2 → **campaign continued from the restored save.** On-disk: `xuser-1/.wine/drive_c/users/xuser/Documents/Respawn/Titanfall2/profile/savegames/savegame.sav` = 27,160,835 B — the steamuser→xuser remap landed it in the real profile the game reads. Backup direction still un-tested.
> **🐞→✅ Phantom "FlightCore" game card FIXED (`a8ddf7d`).** Faithful restore also wrote the zip's Desktop launcher shortcut `users/steamuser/Desktop/FlightCore.lnk` → remapped to `users/xuser/Desktop/FlightCore.lnk`; **`ContainerManager.loadShortcuts()` scans the container Desktop dir and auto-imports every `.lnk` as a game card** (ContainerManager.java:226) → a FlightCore card (TF2/Northstar mod launcher) appeared in Games on its own. Fix = new `GameSaveBackup.isFrontendShortcut()`: skip `proton_shortcuts/` + Desktop `*.lnk/*.desktop/*.url` on restore (never save data). Existing phantom cleaned on device via root bridge (rm'd the 2 Desktop files + proton_shortcuts on xuser-1; **bridge CAN write/delete /data/data as uid=0**; grid drops the card on next loadShortcuts refresh). Pushed; **rebuild `28757508619` in flight** → stage updated APK on green. No release cut (2.4-preN candidate).

## 2026-07-05 — ✅ Backup / Restore game save — BUILT GREEN + APK STAGED (checkpoint)

> **New feature: "Backup / Restore save" on the container overflow (⋮) menu.** Two-way, GameHub-compatible. Branch **`feat/save-backup-restore`** off post-2.3 main `f7905be`; committed The412Banner **`bc7d4dc`**, pushed. CI build **`28756937615` GREEN** (compiles clean). Standard APK STAGED **`/sdcard/Download/bannerlator-save-backup-restore-bc7d4dc-standard.apk`** (589,592,834 B, md5 `79a7a4938ddf4a1fcbf2063509633b8a`, bit-identical to CI). **⚠️ Code-proven only — NOT device-tested yet. No release cut** (2.4-preN candidate if proven, per versioning rule).
> **Why the remap matters (device-verified):** GameHub runs Proton as user `steamuser`; our containers run Wine as `xuser` (`…/imagefs/home/xuser-<id>/.wine/drive_c/users/{xuser,Public}` — no steamuser). A verbatim extract lands the save in a dead folder the game never reads → silent no-op. Engine translates both ways: **restore** `users/steamuser/…`→`users/xuser/…`; **backup** `users/xuser/…`→ zip `/drive_c/users/steamuser/…`.
> **GameHub zip format** (verified from `Titanfall® 2_1772195654303.zip`, 23.6 MB): drive_c snapshot, every entry rooted `/drive_c/...`; real save = `users/steamuser/Documents/Respawn/Titanfall2/profile/savegames/savegame.sav` (27 MB) + cfgs; plus incidental junk (VC redist Package Cache, Temp logs). No manifest/appid — game name only in the filename.
> **Impl — 2 files:** NEW `core/GameSaveBackup.kt` (threaded restore w/ Zip-Slip guard + backup zipping the xuser profile minus Temp/CrashDumps → `Downloads/Winlator/Backups/GameSaves/<name>_<millis>.zip`); MOD `ui/screens/ContainersScreen.kt` (SettingsBackupRestore menu item → SaveFlow: Fork → RestoreSource/BackupFormat → SAF `GetContent("application/zip")` picker + confirm → themed progress + reused `UninstallResultBar`). Locked design (user): restore FAITHFUL (all entries), OVERWRITE-with-warning; backup default = GameHub `steamuser` layout; "other sources/formats" stubbed for later.
> **Preview delivered:** interactive HTML mock `/sdcard/Download/bannerlator-restore-preview.html` + artifact `claude.ai/code/artifact/dfd01d27-1b4d-4564-9fde-8ebf5874e574`.
> **NEXT (device-test):** (a) ⋮→Backup/Restore save→Restore→GameHub backup→pick Titanfall zip→confirm→"Restored 43 files"→launch TF2, save loads (can root-verify `savegame.sav` under `users/xuser/Documents/Respawn/…` first); (b) Back up→GameHub-compatible .zip→appears in Downloads/Winlator/Backups/GameSaves. Watch: SAF picker may need `*/*` if it hides the zips (one-line change); storage-permission prompt on first backup. Memory: `project_bannerlator_save_backup_restore`.

## 2026-07-05 — 🤝 Mali/BCn-layer test build handed off to @kylinzang

> Mali/BCn-layer feature (branch `feat/mali-bcn-layer`, `79ee0ae`, CI run `28748542841` green, 3 flavors, artifacts expire 2026-07-12) **handed off for Mali device testing**: posted the build link + how-to (new "Wrapper + bcn_layer" driver + BCn Layer Settings mapped to his #54 env-var spec) on kylinzang's issue #54 → https://github.com/The412Banner/Bannerlator/issues/54#issuecomment-4887015807. Asked him to confirm the driver loads, a BC-texture game that crashed now runs, ETC2-vs-ASTC, and the debug log. **NOT merged, NOT in 2.3.** If it works → rebase onto post-2.3 main + bump `2.4-pre1`/vc39 (merge or public prerelease). Offered a prerelease APK if he can't pull CI artifacts. Awaiting his results — ball in his court.

## 2026-07-05 — ✅ STABLE RELEASE 2.3 (versionCode 38) — PUBLISHED + LATEST

> **✅ LIVE:** https://github.com/The412Banner/Bannerlator/releases/tag/2.3 — tag `2.3` (→ `4c50df4`; APKs built from `fa488af`, app code identical, the tag just sits one docs(log) commit ahead), prerelease:false, **make_latest:true → releases/latest resolves to 2.3**. Release run **`28749102771`** SUCCESS. Body overwritten with polished `release_2.3_body.md` via `gh release edit`. **update.json verified: versionCode 38 / versionName 2.3**, per-flavor APK map correct. Assets attached: `Bannerlator-2.3-standard.apk` (589,576,141 B), `-ludashi` (589,576,164 B), `-pubg` (589,576,122 B), update.json (885 B). All caches hit (warm build). No release_notes double-quote trap (notes were single-line, clean).
> Credits handed out (release body + README): JavaSteam (Longi94 + joshuatam), Goldberg/gbe_fork (Mr_Goldberg/Detanup01), Pluvia (oxters168), GameNative (expanded: session-hardening + speed tiers), The412Banner (2.3 original engineering); no AI credited — satisfies [[project_bannerlator_steam_branch_release_credits]].

## 2026-07-05 — 🚀 STABLE RELEASE 2.3 (versionCode 38) — CUTTING (superseded by PUBLISHED above)

> **The storefronts release.** Bump commit `fa488af` (versionCode 37→38, versionName 2.2.2→2.3), release.yml run **`28749102771`** RUNNING (make_prerelease=false → plain tag `2.3`, make_latest, all 3 flavors + update.json vc38). Changelog range `d837036..5d324a6` (2.2.2 was ReShade; ALL of this landed after it). **Entirely app-side — NO ImageFS reinstall** (verified: zero bundled-asset changes since 2.2.2).
> **Ships:** built-in **Steam store** (JavaSteam depot engine, username/password OR QR login, session hardening/CM-logoff recovery, GameNative-style 4-tier speed, status pill, depot OOM fix, "Log debug session" toggle); **Goldberg auto-patch** (Regular/Experimental/ColdClient, use-at-own-risk); **cross-store Download Manager** (Steam/Epic/GOG/Amazon, background downloads + shade notif via FGS, two-bar progress, single-source install-state, verified uninstall, Default-screen setting); **Epic + Amazon stores** added, **GOG** wired in, all non-Steam pages M3-restyled; **security hardening** (StoreLog.redactUrl scrubs signed URLs/OAuth codes/GOG client_secret+refresh_token/identity IDs from logcat + diagnostic files) + **third-party-login/use-at-own-risk/share-logs-carefully disclaimer**; **Steam QR re-enabled** + advisory.
> **README updated** (What's New 2.3, all-4-stores + DL Manager section, new 🔒 Security Hardening section, Disclaimer extended) + **credits** added JavaSteam (Longi94 + joshuatam depotdownloader), Goldberg/gbe_fork (Mr_Goldberg/Detanup01), Pluvia (oxters168); expanded GameNative (session-hardening + DownloadSpeedConfig speed tiers) + The412Banner (2.3 original engineering); no AI credited. Release body drafted `/home/claude-user/scratchpad/release_2.3_body.md`, updater note `release_2.3_notes.txt` (double-quote-safe).
> **PENDING on build green:** `gh release edit 2.3 --notes-file release_2.3_body.md --latest` (polished body) → verify tag `2.3` + update.json vc38 + 3 APKs + releases/latest→2.3 → record run/SHA/md5s → hand out credits (done in notes/README). **NOT on main:** Mali/BCn-layer (`feat/mali-bcn-layer`, awaiting Mali tester). QR is code-proven, verified-in-the-wild per user.

---

## 2026-07-05 — 🎮 Re-enable Steam QR sign-in + fallback advisory (BUILDING)

> **Branch `feat/reenable-steam-qr`** (off main `75ba43c`, commit `483b88c`, CI build `28747478670` running). QR login had been UI-gated OFF (`SteamLoginActivity.kt` `TextButton enabled=false`, "temporarily unavailable") over a concern that QR-originated sessions get dropped by the Steam CM after ~1h. **Verified the disable's stated precondition ("re-enable once the logoff-recovery path is device-proven") is now met at code level:** QR success calls `SteamQrAuthManager.saveSession(username, refreshToken)` — the SAME session shape as a password login — so it's recovered by the SAME path (`SteamRepository.onLoggedOff`/`reconnectNow`/`loginWithToken(username, refresh_token)`, bounded `MAX_LOGOFF_RECOVERY=3`/`MAX_RECONNECT_ATTEMPTS=5`). Recovery is refresh-token-based and agnostic to how you first authed → a QR session recovers like a password one.
> **Change = UI-only** (no auth/session logic touched): `SteamLoginActivity` button re-enabled + label back to "Sign in with QR Code"; NEW on-screen advisory on `QrLoginActivity` — "if downloads or your session keep dropping after signing in with QR, sign out and use Username + Password instead (the more reliable sign-in)". ⚠️ **code-proven, NOT yet device-proven for QR specifically** (recovery was device-proven on the password path; needs QR→wait ~1h/force-logoff→confirm reconnect on-device).
> **Also in flight:** main build `28747347502` (log-redaction complete build off `75ba43c`). **User decision: HOLD staging/merge until BOTH builds finish, then decide.** No release cut (vc37/2.2.2).

---

## 2026-07-05 — 🔒 Store logcat credential-leak audit + redaction fix (BUILT + STAGED)

> **✅ CI build `28746951190` GREEN** (~8m, cached; SHA `2a2c5a96`). **Standard APK STAGED** `/sdcard/Download/bannerlator-log-redaction-2a2c5a9-standard.apk` (589,575,792 B, sha256 `4a80c30f…941c992b`, bit-identical to CI, on-device). ONE combined build = 4-store Download Manager COMPLETE + log redaction. **⏳ Awaiting user device-test:** all 4 store logins still work + a game download still works (confirms redaction-only), then diff logcat before/after to see credentials/signed-URLs now redacted. Then merge `fix/store-log-redaction`→main (clean FF off `a0ef2ee`), no release cut (vc37/2.2.2).
> **UPDATE:** fix implemented (18 files + NEW `StoreLog.java`), diff reviewed = redaction-only (logins/downloads/token-refresh/cloud-save unaffected — real url/token/accountId/userId vars still flow to the network/login code; `CLOUD_SAVES_NOT_SUPPORTED` control flow preserved), grep-sweep clean. Committed The412Banner `2a2c5a96`. Bonus fix: closed a `GogLoginActivity` redirect-URL leak (auth code in query). Residual/optional follow-up: ~20 bare `Log.x(msg, throwable)` network-catch sites + `SteamRepository.testUrl` (lower risk, not done).
>
> **Branch `fix/store-log-redaction`** (off main `a0ef2ee`). User asked whether any of the 4 stores emit credentials/tokens/username/email to logcat. 4-agent parallel audit (read-only) → then a REDACTION-ONLY fix (log-string edits, ZERO behavior change: login/downloads/token-refresh/cloud-save untouched; only what's WRITTEN to the log changes).
> **Audit verdict:** across all stores, passwords/emails/usernames/access+refresh tokens are NEVER directly logged (WebView/OAuth logins; tokens in Authorization headers). No HTTP logging interceptor anywhere. **Steam = CLEAN** (`SteamLogRedactor` wired into logcat sinks + secrets registered pre-log + JavaSteam firehose gated behind off-by-default debug toggle; only 5 raw-Throwable logs bypass it = LOW hardening, kept per user). **Leaks (Amazon/Epic/GOG, no redactor/gating):** signed CDN/manifest URLs (auth in query) — Amazon `AmazonDownloadManager.java:112` unconditional every install, + error paths across Amazon/Epic/GOG; OAuth code via whole-page dump (`EpicLoginActivity.kt:71` HIGH, `AmazonLoginActivity.kt:67`); **GOG `GogTokenRefresh.java:78` can print `client_secret`+`refresh_token`** via the error URL (highest-value payload); identity ids (EpicAccountId, GOG userId); Amazon `credentials.json` echo via JSONException.
> **Fix:** new `StoreLog.redactUrl()` (strips query/userinfo → scheme+host+path); exception logs → class-name-only; page/redirect dumps → static strings; drop accountId/userId/body snippets; Steam Throwables → `SteamLogRedactor`. Impl by native-steam-engineer agent.
> **NEXT:** review diff (redaction-only) → commit (The412Banner) → push → CI `build-artifacts.yml` on the branch → verify green+sha → stage ONE combined standard APK to `/sdcard/Download/` (user HOLDING all staging until this build is ready) → device-diff logcat before/after → merge decision. No release cut (vc37/2.2.2). Main build `28746137017` GREEN + untouched.

---

## 2026-07-05 — 🎉 EPIC MERGED TO MAIN → Download Manager COMPLETE (all 4 stores)

> **✅ `feat/epic-download-producer` MERGED TO MAIN** (user-instructed) — clean fast-forward `17f58ae..0ab3475`, main now `0ab3475`. Carries Epic Phase C (`4cf2b8f`) + the GOG/Epic list-card cold-start install-state fix (`0ab3475`). **Cross-store Download Manager is now COMPLETE across all 4 stores (Steam/Amazon/GOG/Epic).** NO release cut — stays vc37/2.2.2 (a future stable still needs a monotonic versionCode bump). Pre-merge, verified install-state on device against disk-truth (per-store prefs + `steam.db` is_installed + on-disk dirs): Epic=Brawlhalla, GOG=ELDERBORN, Steam=HL2+FlatOut, Amazon=0 — matched the DL-manager Library exactly. Branch kept. Open follow-ups: DepotSizeResolver true-size; release credits at next stable.

---

## 2026-07-05 — 🐞→✅ Store-list cold-start install-state (GOG confirmed + Epic latent) FIXED

> **Device report (user, 6 screenshots on Epic build `4cf2b8f`): Epic Phase C WORKING end-to-end** — Brawlhalla shows on the detail bar (10%), the DL-manager card *with cover art* (44%), AND the FGS shade notification (59%). Epic inherited the shared StoreDownloadHooks→FGS→notif→DL-manager plumbing correctly. **🐞 BUG spotted: GOG Library LIST shows "Install" on an already-installed game** (ELDERBORN) while the GOG *detail page* and the *cross-store DL-manager* both correctly show it Installed.
> **Root cause:** the GOG list card is driven purely by the in-memory `downloadStates` map, only written by a LIVE download this session; it's NEVER seeded from disk-truth on cold start. `GogLibrarySync.seed()` seeds the DownloadRegistry (DL-manager) but not this Activity's local map → prior-session install = null entry → falls to "Install". Detail was correct because it reads `gog_exe_`/`gog_dir_` prefs directly.
> **Epic audit found the IDENTICAL latent bug** (not the assumed "already fine"): `EpicGame.isInstalled` is only ever set from cache/refresh-merge, never re-derived from `epic_exe_` on cold start (install-complete only updates the live map), so the old `?: GameDownloadState(installed = game.isInstalled)` fallback was always false.
> **Fix (mirror across both, disk-truth fallback):** new `GogInstallState.isInstalled(ctx,id)` = `gog_exe_ != null && gog_dir_ != null`; new `EpicInstallState.isInstalled(ctx,appName)` = `epic_exe_ != null` (exact records the detail pages read). List/grid/poster cards fall back to a synthesized installed state when the live map is empty. GOG **must** also set `buttonText="Add to Launcher"` (card reads `downloadState.buttonText` before the isInstalled fallback, ~line 1099); Epic drives label off its single `installed` field so no buttonText coupling. Uninstall→`purge` clears the keys → flips back to "Install". Files: `GogInstallState.kt`, `EpicInstallState.kt`, `GogGamesActivity.kt` (+LocalContext import, dead `val isInstalled=false` removed), `EpicGamesActivity.kt` (+LocalContext import, 3 fallback sites). Engine/DownloadRegistry/DL-manager/versionCode untouched. Known pre-existing edge (not introduced): install→detail-uninstall in the SAME session leaves a stale live-map entry that wins over disk until Activity recreate; cold-start (the target) fully covered.
> **NEXT:** commit (The412Banner) → push branch `feat/epic-download-producer` → CI build → deliver APK → user device-test: cold-restart GOG list shows ELDERBORN Installed + "Add to Launcher"; Epic list same for an installed Epic game; uninstall flips both back. On pass → merge Epic branch→main (clean FF) = Download Manager COMPLETE across all 4 stores.

---

## 2026-07-04 — ✅ GOG live-% device-proven → GOG merged to main; 🎮 Epic Phase C built + building

> **GOG live-% DEVICE-PROVEN** (user screenshot: GOG ELDERBORN detail shows "Downloading… 49%" under the bar, matches manager + notification). **→ GOG Phase B fully done + MERGED TO MAIN** (fast-forward `180c2c8..17f58ae` via push, non-disruptive to the Epic branch/build; NO release cut, still vc37/2.2.2). main now `17f58ae`.
> **🎮 EPIC PHASE C implemented + committed `4cf2b8f` (build `28728770633` running):** new `EpicInstallState`(purge) + `EpicLibrarySync`(seed+self-heal+cachedDetail); producer hooks both Epic entry points, DownloadScope.io+appContext (Amazon-shaped blocking install()), no-dialog completion, `observeRegistry()` live "$pct%" label, uninstall→purge+markUninstalled; DownloadManagerActivity last 2 `Store.EPIC` branches filled → **all 4 stores live producers, no stubs.** WEAK CANCEL (Epic engine has no checker): UI freezes immediately, transfer runs to completion then discarded (documented).
> **✅ Epic build `28728770633` GREEN 8m18s → APK DELIVERED** `/sdcard/Download/bannerlator-epic-4cf2b8f-standard.apk` (sha `4f3cae6b…fb0c1801`, bit-identical, on-device); ⏳ user device-testing all 4 stores. Then merge Epic branch (stacked on GOG) → main (clean FF) = Download Manager COMPLETE. 🗑️ Dropped (user 2026-07-05): vc38 pre-release, GOG installed-build cosmetic, dialog accent; Goldberg all tiers proven.

---

## 2026-07-04 — 🔧 Live percentage on GOG/Amazon detail pages during download

> Device-test of `2ab915c`: GOG all-works + uninstall→re-download→install works, BUT the detail page showed the bar+Cancel with **no percentage text** (DL manager + notification show "Downloading… 57%"). The detail-sync collector set the bar but never `progressLabel`. **Fix `c1be52b` (build `28728512650` running):** GOG+Amazon `observeRegistry()` collector now drives a live `progressLabel="Downloading… ${pct}%"` (Amazon adds "(done/total)" when it has bytes) + visible; GOG local onProgress label switched from engine-msg to "$pct%" (no flicker).
> **✅ live-% build `28728512650` GREEN 6m55s (warm) → APK DELIVERED** `/sdcard/Download/bannerlator-gog-livepct-c1be52b-standard.apk` (sha `22bc87cd…530f9cf3`, bit-identical, on-device); ⏳ user device-testing live-%. **✅ Epic Phase C BEGUN in parallel:** spec rescoped (live-% items 9-10), new branch `feat/epic-download-producer` off GOG-branch `17f58ae` (off GOG not main, to inherit its DownloadManagerActivity changes), Epic agent dispatched. Epic = last store (Amazon-shaped, weak cancel, launch already fixed). Merge Epic branch (=GOG+Epic) → main when both device-proven.

---

## 2026-07-04 — Goldberg risk warning + warm build confirmed

> Added a user-facing caveat to the Steam Emulator (Goldberg) section (`2ab915c`, error-color Text under the subtitle): "Please note: this is not a fix for all Steam games that require a Steam client to run. It is not a guaranteed fix-all — use at your own risk!"
> Detail-sync build `28727937666` (72dbed1) GREEN **8m14s** — first fully-warm build off main's populated caches (~16min→8m14s, ~48% faster; confirms the caching). Combined build `28728128024` (detail-sync + warning, sha 2ab915c) **GREEN 8m13s (warm) → APK DELIVERED** `/sdcard/Download/bannerlator-gog-warn-2ab915c-standard.apk` (sha `9a197a39…642c8b1ba`, bit-identical, on-device). ⏳ user device-testing GOG detail sync + Goldberg warning.

---

## 2026-07-04 — 🐞 GOG (+Amazon) detail page not synced with download-manager progress

> GOG device-test: card + notification work, but the **GOG detail page shows "Install" during a live download** (ELDERBORN: card 55%+Cancel, notif 58%, detail = Install). Cause: `GogGameDetailActivity.refreshActionState()` (and `AmazonGameDetailActivity.refreshActionState()` — same latent bug on main) read install PREFS only, never the DownloadRegistry → opening the detail mid-download (or list-started) shows Install, not progress+Cancel. +card showed "0 KB / 0 KB" (GOG pct-only).
> **Fix (dispatched):** both GOG+Amazon detail pages observe `DownloadRegistry.entries` for their game key → reflect DOWNLOADING as progress+Cancel (live pct), Cancel wired to the registry entry (works for list-started DLs); DL card suppresses the byte pair when `installTotal==0`. Epic folded into its Phase C spec (items 9-10).
> **✅ FIXED `72dbed1`, build `28727937666` running (warm off main caches):** GOG+Amazon detail added `observeRegistry()` collector (DownloadRegistry.entries → progress+Cancel live, Cancel routes to registry entry w/ recursion-guard); card gates bar+byte-text on `hasBytes` (fixes GOG 0KB + latent bar-stuck-at-0). NEXT: green → deliver `bannerlator-gog-detailsync-72dbed1-standard.apk` (watcher `bly53jgay`) → re-test GOG detail sync (list-started DL → open detail → live progress+Cancel; card shows just %). Epic Phase C spec ready (waits: GOG proven → merge → Epic).

---

## 2026-07-04 — 🎮 GOG Phase B implemented (producer wiring) + parallel cache-warm on main

> On `feat/gog-download-producer`. **GOG wired into the cross-store Download Manager** (`70ebfef`, mirrors Amazon; `GogDownloadManager.java` untouched): new `GogInstallState` (purge) + `GogLibrarySync` (seed + self-heal + cachedDetail); producer hooks in `GogGameDetailActivity` + `GogGamesActivity` (both download entry points); `DownloadManagerActivity` openDetail + purgeNativeInstall GOG branches. Cover `//`→https normalized. **Deviation (correct): no DownloadScope — GOG engine spawns its own thread, so FGS+appContext give background survival.** update-available=false (GOG check is network-only). Verified compile-critical refs exist + imports/Kotlin-Java direction clean.
> **Parallel:** cache-warming build `28727169152` on `main` (cold, populates main-scope caches for all future runs); GOG build `28727381558` dispatched alongside (cold this once, warm off main afterward).
> **NEXT:** builds green → deliver GOG APK → device-test → Phase C (Epic, last; also fix its LandscapeLauncher launch crash).
> **⏸️ CHECKPOINT (2026-07-04): main cache-warm build `28727169152` GREEN 16m54s (main-scope caches now populated — all future builds warm). GOG build `28727381558` GREEN 10m47s (faster than ~16min baseline — partial cache benefit) → APK DELIVERED `/sdcard/Download/bannerlator-gog-70ebfef-standard.apk` (sha `a021e232…d27f91dab`, bit-identical, on-device). ⏳ user device-testing GOG. GOG code committed `70ebfef` on `feat/gog-download-producer`.**
> **✅ EPIC PHASE C SCOPED + PREPPED AHEAD (spec `/home/claude-user/scratchpad/epic_phase_c_spec.md`):** pure producer wiring (Epic's launch crash already fixed in Phase A). Epic is Amazon-shaped (synchronous blocking `install():boolean` on lifecycleScope → needs DownloadScope move), single-bar, **WEAK cancel** (no cancel arg → best-effort only). Branch when greenlit: `feat/epic-download-producer` off main-after-GOG-merges. After Epic, all 4 stores are live producers (no stubs). Detail in memory `project_bannerlator_download_manager_stores`.

---

## 2026-07-04 — ✅ MERGED Amazon Phase A → main + cached both CI workflows + branched for GOG

> **Toast sweep DEVICE-PROVEN** (user "toast sweep good") → all of Amazon Phase A + shared infra is device-proven. **Merged `feat/download-manager-stores` → `main`** (merge `88e2360` + cleanup `180c2c8` removing the redundant `build-artifacts-fast.yml`). **No release cut** (stays vc37/2.2.2). Merged content: Amazon DownloadRegistry producer, non-Steam store restyle, ⬇ button, notification + background-download foreground service, install-state one-source-of-truth (uninstall purge + self-heal), launch/detail-routing/dialog fixes + Epic launch-crash fix, full black-box Toast sweep.
> **CI caching adopted:** folded into `build-artifacts.yml` (`6ffc374`) AND `release.yml` (Nightly Manual Release Build, `55f9caa`) — setup-java (drop apt JDK), setup-gradle dep+build cache, NDK+cmake cache, JavaSteam-JAR cache (keyed on upstream commit), LSFG `.so` cache. Validated ~28% faster warm (16min → 11m41s), green, all 3 valid APKs. First run on a new scope is cold, warm after.
> **➡️ New branch `feat/gog-download-producer`** off main `180c2c8` for **Phase B (GOG)** — spec ready at `/home/claude-user/scratchpad/gog_phase_b_spec.md`.

---

## 2026-07-04 — ⚙️ CI build-speed: cloned cached workflow under test (build-artifacts-fast.yml)

> User asked about speeding up the ~13-16 min CI build (zero caching today). Decided: **ALWAYS build all 3 flavors** (no standard-only), so speedups = caching + drop redundant JDK only. Built a PARALLEL cloned workflow to validate WITHOUT touching the primary `build-artifacts.yml`.
> **New `.github/workflows/build-artifacts-fast.yml`** (feature branch `8cae1e7`; registered dispatch-only on `main` `2df37ab` because `workflow_dispatch` requires the file on the default branch): same 3-flavor output/artifact names + adds `setup-java` (drop apt JDK), `setup-gradle` (dep+build cache), NDK+cmake cache (key=version), JavaSteam-JAR cache (key=upstream commit via `git ls-remote`), LSFG `.so` cache (key=source+script hash). Each cache guarded so a miss still builds.
> **Cache invalidation:** JavaSteam(upstream commit)/LSFG(source hash)/Gradle(build-file hash) auto-detect changes → rebuild+recache, else use cache; NDK version-pinned (re-downloads only when we bump the version). Est. warm build ~8-10 min (3× APK packaging is the uncacheable floor).
> **✅ MEASURED: baseline ~16min | cold `28726206582` 16m38s (green, fills caches) | warm `28726606412` 11m41s (green, caches hit) → ~28% faster (~4m40s). Both fast runs built all 3 valid APKs = cached path proven correct.** Remaining ~11.5min is dominated by the uncacheable 3× APK R8+packaging floor. Next (user's call): device-test a fast-build APK for parity, then adopt/merge the caching or keep as-is. See memory `project_bannerlator_ci_build_speedup`.

---

## 2026-07-04 — 🐞→✅ Black-box Toast on Amazon uninstall (device-test of 6e089ce)

> **`6e089ce` device-tested: all 5 launch/routing/dialog fixes WORK.** Last bug: uninstall shows an unreadable **black box** at the bottom (screenshot = Amazon detail page). Same ROM/targetSDK-28 issue Steam already fixed — system `Toast` renders as a black box; Steam uses the themed `UninstallResultBar` (`StoreUninstaller.kt:65`).
> **Fix (`4b8d28b`):** `AmazonGameDetailActivity` + `AmazonGamesActivity` — added `resultBarMsg` state + `UninstallResultBar` overlay; uninstall confirmations (both entry points) + the detail "No .exe found" launch message now render through the themed bar instead of Toast. Also refresh `loadUpdateStatus()` on uninstall so the stale "Installed: v…" line clears. CI `28725198746` running → deliver `bannerlator-toast-fix-4b8d28b`.
> **✅ SWEEP DONE (`71590ab`, CI `28725602812` GREEN → APK DELIVERED `/sdcard/Download/bannerlator-toast-sweep-71590ab-standard.apk`, sha `6479e7f3…48c6611`, bit-identical, on-device confirmed; ⏳ user device-testing):** 63 Toasts across 15 files converted — 58 Compose→`UninstallResultBar`; GogLogin(WebView)+FolderPickerActivity.java→themed `AlertDialog(StoreAlertDialogDark)`. Finding: `StarLaunchBridge.showToast()` was already a readable custom dark Toast (not black-box), left as-is. Verified no stray Toasts, bars wired, R/style refs resolve, braces balanced. Combined build (uninstall fix + sweep) → deliver `bannerlator-toast-sweep-71590ab`.
> **✅ GOG Phase B TEED UP** (spec ready `/home/claude-user/scratchpad/gog_phase_b_spec.md`) — mirror Amazon exactly; dispatch on user greenlight after they device-test `71590ab`. Then Phase C = Epic.

---

## 2026-07-04 — 🐞×4 Amazon launch/completion/routing/dialog (device-test of cceba57)

> **`cceba57` device-tested. ✅ WINS:** cover art on DL card, detail progress label "68% (2.7 GB / 3.9 GB)", **shade notification works** ("Downloading — Dread Templar — 82% (3.2 GB / 3.9 GB)"). **4 NEW bugs (crash captured via `getlog.py exec logcat -b crash`):**
> 1. **DL-manager cards don't open the store detail page** — `DownloadManagerActivity.openDetail(:186)` routes only STEAM, else no-op. Fix: route AMAZON → `AmazonGameDetailActivity`, hydrate extras (entitlement_id/dev/pub/product_sku) from `amazon_library_cache` by productId; GOG/Epic TODO.
> 2. **Launch from Amazon DETAIL page CRASHES** — `ActivityNotFoundException` at `pendingLaunchExe:480` (hardcodes stale `com.xj.landscape.launcher.ui.main.LandscapeLauncherMainActivity`, absent in `com.winlator.banner`). Fix: delete it, use `StarLaunchBridge.addToLauncher(this,title,exe,artUrl)` (the working list path).
> 3. **Container-picker dialog unthemed** ("old menu style" white) — `StarLaunchBridge.java:129` uses default-light `AlertDialog.Builder`. Fix: dark+pink themed dialog (shared infra → fixes all stores).
> 4. **Download hangs at 100% + auto-exe-picker on completion** — `AmazonGameDetailActivity:330-350` shows the exe picker on completion when >1 exe, gating `markInstalled`; if user isn't on the detail page the dialog queues on the stopped Activity → card stuck at 100%. Fix: completion auto-records best-scored exe + markInstalled (NO dialog, both entry points); move exe picker to the **Launch** flow (before the container picker).
>
> All 4 = one coherent Amazon completion→launch flow fix + DL routing + dialog theme. **✅ FIXED + COMMITTED `6e089ce`, CI `28724462856` running.** `openDetail`→AMAZON via new `AmazonLibrarySync.cachedDetail`; `AmazonGameDetailActivity` launch reworked to `StarLaunchBridge.addToLauncher` + completion auto-finalizes (no dialog); `AmazonGamesActivity` completion same; dark `StoreAlertDialogDark` picker. **+ fixed the IDENTICAL Epic detail-launch crash** (`EpicGameDetailActivity.kt:367`, same hardcoded `LandscapeLauncherMainActivity`) — Epic detail is reachable today; GOG detail was already correct. **Known nit (deferred):** dialog accent = legacy blue, not preset pink (framework dialog can't read the Compose preset). **CI `28724462856` GREEN → APK DELIVERED** `/sdcard/Download/bannerlator-launch-fixes-6e089ce-standard.apk` (589,562,460 B, sha256 `56dd4431f67335be…f155e9ad`, bit-identical to CI, on-device confirmed). ⏳ USER DEVICE-TESTING all 5. **🛑 HOLD Phase B (GOG) until user device-tests `6e089ce`.** ⚠️ Clarified (verified in code): Epic & GOG are NOT yet wired to the notification/DL-manager — only Amazon + Steam call `StoreDownloadHooks`; no Epic/GOG seeders. Their downloads today have no shade notif / no DL-manager card / no background survival; they inherit all of it once wired (Phase B GOG → Phase C Epic). Launch now works on all 4 stores.

---

## 2026-07-04 — 🐞→✅ Install-state = one source of truth (uninstall left Amazon list "Installed")

> **Device-test of `3ad879a` surfaced a bug:** user uninstalled Amazon "Dread Templar" from the DL manager. **Files WERE deleted** (device-verified: `/data/data/com.winlator.banner/files/Amazon/` empty), but the Amazon store list still showed "✓ Installed". **Cause:** cross-store uninstall cleared the registry row + Steam DB but never Amazon's native record; the Amazon list reads install-state solely from pref `amazon_exe_<id>` (`AmazonGamesActivity.kt:1014,1245`), which survived. Latent 2nd bug: `AmazonLibrarySync.seed()` treated `exe!=null` as installed → would resurrect a zombie INSTALLED row on next cold start.
>
> **User decisions (AskUserQuestion):** install-state = ONE source of truth across detail/card/list for ALL of {install-state, update-available, cover/metadata}; generalize seam for GOG/Epic.
>
> **Fix (native-steam-engineer) → committed `cceba57`, pushed, CI `28723445905` running:**
> - NEW `AmazonInstallState.purge(ctx,pid)` — single owner of Amazon's native record (clears `amazon_exe_`/`amazon_dir_`/`amazon_manifest_version_`/`amazon_size_`); called from ALL 3 uninstall paths (list, detail, cross-store Manager). Amazon-side so `download` pkg stays engine-free.
> - `DownloadManagerActivity.purgeNativeInstall(entry)` — generalized `when(store)` seam (AMAZON wired; GOG/EPIC TODO-stub; STEAM via DB); + seeds Amazon in `onCreate` so opening the Manager directly self-heals; + amber "● Installed — Update available" on the card.
> - `AmazonLibrarySync` **self-heal**: install-truth now requires bytes on disk (`isInstalled(dir) || (exe!=null && File(exe).exists())`), else purge prefs + `removeLibraryEntry`. `isActive` guard moved BEFORE the heal so in-flight downloads are never purged. → auto-fixes the user's current orphaned Dread Templar prefs on next launch.
> - `DownloadEntry.updateAvailable` (transient, not persisted) — amber card marker matching the store list's language; sourced from cached `versionId` `_UPDATE_AVAILABLE` suffix at seed; `markInstalled` clears it; Steam=false.
> - cover parity: seed cover = `artUrl.ifEmpty{heroUrl}` (matches list). Installed cards show no size line (Steam parity — flagged, can add if wanted).
>
> Compile-verified (`Store` imported, `isActive`/`get` exist, same-package helper). **CI `28723445905` GREEN (conclusion re-verified) → STANDARD APK DELIVERED** `/sdcard/Download/bannerlator-state-sync-cceba57-standard.apk` (589,560,894 B, sha256 `ff7b29e910452a4a…42efd0ab`, bit-identical to CI, on-device confirmed). **⏳ USER DEVICE-TESTING.** DEVICE-TEST: uninstall from Manager → Amazon list flips to NOT installed; force-close+reopen → uninstalled game stays gone AND current orphaned Dread Templar auto-heals; update-available amber marker on card; cover/title match detail.

---

## 2026-07-04 — 🔔 Cross-store download NOTIFICATION + background survival (device-test feedback on restyle build)

> **Restyle+Amazon build `7ec2b50` device-tested by user (3 screenshots, Dread Templar / Amazon).** ✅ Restyle solid (detail page matches Steam layout + orange Amazon badge); ✅ Amazon→DownloadRegistry end-to-end PROVEN: ⬇ header "1" badge lit on download start, Downloads&Library shows live Amazon row `20% (810.2 MB / 3.9 GB)` + Cancel, library seeded (FlatOut + HL2). **Gap the user flagged:** Amazon downloads don't appear in the **system notification shade like Steam does**, and (root-caused) don't survive backgrounding.
>
> **Diagnosis:** Steam downloads run *inside* `SteamForegroundService` + call `SteamForegroundService.setStatusText(...)` each tick → ongoing shade notification + process kept alive. Amazon ran on `AmazonGameDetailActivity`'s **`lifecycleScope`** (dies with the Activity) with progress only into the in-app registry — **no FGS, no notification**. Two gaps: notification AND process-liveness. Also the "level23" label leak = detail page showed the raw archive filename (`progressLabel = name`) instead of `$pct%`.
>
> **User decisions (AskUserQuestion):** (a) **generalize at the shared `StoreDownloadHooks` seam** so GOG/Epic inherit it; (b) bundle all 3 polish fixes (level23→%, add %/size to detail bar, fix blank cover art).
>
> **Built (native-steam-engineer) → committed `3ad879a`, pushed, CI `28722689070` dispatched/RUNNING:**
> - NEW `download/DownloadForegroundService.kt` — store-agnostic FGS, own "Downloads" channel (`downloads_channel`, IMPORTANCE_LOW), NOTIF_ID `9002` (≠ Steam's 9001), ongoing progress notif, tap→`DownloadManagerActivity`, `dataSync` type, `ConcurrentHashMap<key,Active(text,seq)>` source of truth (1→"Downloading", N→"N downloads"+most-recent line), self-stops when active set empties, sticky-restart-with-empty-map self-stops.
> - NEW `download/DownloadScope.kt` — `object DownloadScope { val io = CoroutineScope(SupervisorJob()+Dispatchers.IO) }` process-lifetime scope.
> - `StoreDownloadHooks.kt` — register/tick push `DownloadForegroundService.setProgress(key,line)`; markInstalled/Failed/Cancelled call `finish(key)`. Line built from registry entry via shared formatter.
> - `DownloadRegistry.kt` — `appContext()` accessor (app ctx captured in `init`, leak-safe).
> - `DownloadModels.kt` — promoted shared `formatDownloadSize(Long)` (manager's GB/MB/KB tiering) so card + detail label + notif read identically.
> - `AmazonGameDetailActivity.kt` — download moved `lifecycleScope`→`DownloadScope.io`, `applicationContext` into `AmazonDownloadManager.install`, UI writes guarded `!isDestroyed&&!isFinishing`, exe-picker auto-picks best-scored exe if Activity gone (BadToken guard), `onBackPressed` no longer aborts download, error Toast uses app ctx; polish 4a/4b (`"$pct%  (done / total)"`, "Downloading…" at 0%).
> - `DownloadManagerActivity.kt` — new `DownloadCoverArt` (Steam=appId loader, others=Coil URL, graceful placeholder) fixes blank Amazon cover (4c); `fmtSizeDm`→shared formatter.
> - `AndroidManifest.xml` — `FOREGROUND_SERVICE_DATA_SYNC` perm + `<service DownloadForegroundService dataSync>`.
>
> Compile-sane review passed (`DownloadRegistry.get` exists; Kotlin/Java call direction clean — Java engine never calls the Kotlin object). **Committed `3ad879a` as The412Banner, pushed. CI `28722689070` GREEN (conclusion re-verified) → standard APK DELIVERED** `/sdcard/Download/bannerlator-dl-notif-bg-3ad879a-standard.apk` (589,559,791 B, sha256 `e1910b4841d21e2d…bbf2a01c`, bit-identical to CI artifact, filesystem-visible; note: bridge has no `am` verb so no MediaStore scan — file is browsable directly like prior builds). **⏳ USER DEVICE-TESTING NOW.** NEXT: device-test the shade notification + background survival + cover + label polish (checklist below).
>
> **Device-test checklist:** (1) Amazon install → live ongoing shade notif "Downloading … — X% (…/…)", no sound; (2) tap notif → opens Downloads&Library; (3) Home mid-DL → keeps progressing; (4) Back off detail page mid-DL → continues (used to abort); (5) Cancel from Manager stops it + clears notif; (6) Cancel from detail page still works; (7) complete → INSTALLED row + notif auto-dismiss; (8) Amazon cover renders (no white box); (9) detail label = `20% (810.2 MB / 3.9 GB)`, never "level23"; (10) Steam notif still independent (channel 9001).

---

## 2026-07-04 — ✅ Black-box fix device-proven + 🐞→✅ Bug-1 (size-text >100%) fixed

> **Black-box fix (`eb7dd55`) DEVICE-PROVEN (user, latest screenshot):** installed the delivered APK, uninstalled a game, and the `UninstallResultBar` renders real text — no more empty black box. Last open verification on the branch is cleared; all Steam-side uninstall feedback works end-to-end.
>
> **Verified the two "known cosmetic bugs" against live code before touching anything:**
> - **Bug-1 (>100%):** the *percent* is already clamped everywhere — producer caps at 99 (`SteamDepotDownloader.kt:515`), both display surfaces `.coerceIn(0,100)` (`DownloadManagerActivity.kt:474`, `SteamGameDetailActivity.kt:284/285/754/755`), all since `ad4887f` (2026-07-01). The only live residual was the byte-count **text** reading done>total when PICS under-reports install size (`installTotal` self-corrected only when `!hasPicsSize`, `:503`).
> - **Bug-2 (reinstall stale-100%):** NOT fixed — root cause is the `get(dmKey)==null` gate at `SteamDepotDownloader.kt:396` (reinstall of an INSTALLED game keeps stale counters). **User deferred it** — hasn't recurred since the blocking-uninstall serialization; fix if it comes back = gate the reset on `attempt==0`.
>
> **Fix (`b8e9e5b`):** grow the install denominator when `installDone>iTotal`, mirroring the existing download-bar guard, so the corrected total flows to both the detail page (`emitProgress`) and the DL-manager card (registry). One file, +4/-3. Committed as The412Banner, pushed, **CI build `28717970189` dispatched** → deliver standard APK on green.
> **NEXT:** green build → APK to device → user eyeballs a download's size text → merge decision.

---

## 2026-07-04 — 🐞 DIAGNOSIS: uninstall→reinstall-same-session wedge + 🔨 uninstall feedback (toast + verify)

> **Test report (user):** uninstalled Half-Life 2, immediately reinstalled → **detail page stuck `Downloading… 0% (0 KB / 8.4 GB)`, DL-manager card showed `100% (8.4/8.4 GB)`, Steam Library stuck "Fetching app records (23/372)".** Landing-screen toggle confirmed working; couldn't test the two-bar progress (no DL started).
>
> **Investigation (screenshots `Screenshot_20260704-142841/142849/143107.png` + device logcat + pulled `steam.db`):**
> - `steam_games`: HL2 (220) `is_installed=0`, `install_dir=''` → **uninstall was clean.**
> - `steam_downloads`: HL2 `status='queued', bytes_downloaded=0, bytes_total=8990704030`, added 14:28 → **download queued but NEVER started** (0 bytes).
> - `imagefs/steam_games/` contains only `FlatOut/` → **no HL2 dir ever created** (never reached pre-allocation).
> - ActivityManager: no crash, no lmkd/OOM — stuck process pid 15684 was **externally force-stopped 14:30** ("from pid 19129", adj 50), relaunched 20905.
> - Release APK + debug toggle OFF ⇒ engine logged nothing to logcat, no `steam_debug.txt` → **cause of the CM stall not directly observable.**
>
> **Conclusions — two distinct bugs:**
> 1. **DL-manager "100%" = stale in-memory byte counters.** DB proves 0 bytes moved, yet the reused `DownloadEntry` kept the prior install's full `installDone/downloadDone` (the "start hook just flips state, keeps counters" behavior). Detail page renders live `DownloadProgress:` → correctly 0%. **Fix (planned, not yet done):** zero the byte counters when an entry transitions from terminal/INSTALLED back into DOWNLOADING.
> 2. **Real failure = Steam CM/netThread wedge on same-session uninstall→reinstall.** The queued download and the library PICS `ProductInfo` fetch both ride the shared CM callback thread; after the uninstall→immediate-requeue, neither progresses (download stuck `queued`, library stuck 23/372). Same class as the earlier PICS-netThread/LogonSessionReplaced saga, re-triggered by this specific flow.
>
> **Repro requested from user (to capture engine logs):** (1) fresh start, sign in, let Library fully load 372; (2) uninstall HL2, immediately reinstall **with "Log debug session" TICKED** → produces `steam_debug.txt`; (3) note if it sticks at queued + library sticks ~23/372; (4) **control:** same uninstall but **fully restart app first**, then reinstall — if that works, it's a same-session teardown-state bug (points fix at the uninstall path).
>
> **🔨 Uninstall feedback implemented (uncommitted) — the user-requested toast + verification.** Root problem: all 3 uninstall sites did `Thread { File(dir).deleteRecursively() }.start()` fire-and-forget + flipped DB/UI to "uninstalled" instantly + gave no confirmation — a multi-GB delete ran invisibly after the UI claimed done, and that async delete plausibly races an immediate reinstall (contributes to bug #2).
> - **NEW `StoreUninstaller.kt`** (pkg `com.winlator.star.store`): `run(installDir, mark, onResult)` runs `mark()` (store DB bookkeeping) + recursive delete off the UI thread, **verifies `!dir.exists()`**, posts `onResult(success)` to the main thread. Plus `@Composable UninstallProgressDialog(name)` = blocking, non-dismissable M3 spinner "Uninstalling <game>…".
> - **Wired into all 3 sites:** `SteamGamesActivity.onUninstall`, `SteamGameDetailActivity` (installed branch of onInstallClicked), `DownloadManagerActivity.uninstall` — each sets `uninstallingName` (shows the spinner), and on the verified callback clears it + **Toasts** `"<game> uninstalled"` or `"Couldn't fully remove <game>"`, then refreshes (`loadGames`/`loadGame`/`removeLibraryEntry`). Added `android.widget.Toast` import to `SteamGamesActivity` (others already had it). Same-package, no new deps.
> - **Deferred #3 (reinstall guard):** block re-queue of the same app until its delete completes — HOLD until the repro confirms the delete/reinstall race is the trigger.
> - **✅ COMMITTED `f561252` (The412Banner) + pushed `feat/download-manager`. CI Build `28716153990` GREEN → standard APK DELIVERED** `/sdcard/Download/bannerlator-verified-uninstall-f561252-standard.apk` (589,557,110 B, sha head `3d4ca6b6a12f`). **INSTALLED (device build now vc37/2.2.2 @ 15:06:45).**
> - **✅ FRESH-SESSION HL2 DOWNLOAD PROVEN (on the new build + clean-slate DB):** HL2 (220) downloaded to completion with NO wedge — status went `downloading` 19%→55%→66%→83%→98%→**`is_installed=1`**; `steam_downloads` row **cleaned up on completion (no orphan)**; on disk `Half-Life 2/` = **10 GB** real (`hl2.exe`, `hl2/`, `ep2/`, `episodic/`, `lostcoast/`, `bin/`, `platform/`); final `size_bytes=10,661,054,253`. Confirms the earlier wedge is specific to **same-session uninstall→reinstall**, not downloading in general.
> - **🎉 SAME-SESSION UNINSTALL→REINSTALL REPRO: WEDGE DID NOT REPRODUCE — effectively FIXED.** On the new build (`f561252`) + clean slate, with "Log debug session" TICKED: uninstalled HL2 (verified-uninstall spinner+toast fired) → **immediately reinstalled in the same session** → went `queued`→`downloading` (no stick) → 443MB→2.2GB→…→**`is_installed=1` @10.65 GB, full 10 GB on disk.** DL-manager card correctly showed live `5% (553 MB / 9.9 GB)` — **stale-100% bug (a) did NOT recur** (fresh entry on clean slate). **Likely cause of the fix:** the new **blocking `UninstallProgressDialog` serializes delete-before-reinstall** (user can't tap Install until the 10 GB delete is verified complete) → kills the teardown/re-queue race = the deferred #3 reinstall-guard achieved for free; plus the cleared orphaned `queued` row. Full 6 MB `steam_debug.txt` captured (toggle on) if ever needed. **Old wedge (0-byte queued + library stuck 23/372) = gone in this flow.**
> - **🐞→✅ BLACK-BOX BUG IDENTIFIED + FIXED (build running).** User confirmed the black box was at the **bottom/toast position** → the **system `Toast` renders as an empty black box on this ROM** (app targets SDK 28). Fixed `eb7dd55`: replaced the 3 Steam uninstall-result Toasts with **`UninstallResultBar`** — a themed, auto-dismissing (~2.2 s) snackbar-style bar (`Surface` `inverseSurface`/`inverseOnSurface`, non-interactive so it doesn't block touches) drawn inside the app's Compose theme; each Steam activity shows it via a new `uninstallResult` state. `UninstallProgressDialog` spinner unchanged (it worked). **Amazon/GOG/Epic uninstall confirmations still use Toast (same ROM issue) — deferred to the Epic/GOG/Amazon phase.** CI build `28717094726` running on `eb7dd55` (note: first dispatch `28717085524` was cancelled — dispatched before push, would've built old sha). On green → deliver APK.
> - **🐞 COSMETIC BUG (logged): download progress can OVERSHOOT >100%.** Mid-download HL2 read **114.4% (10,283,164,180 / 8,990,704,030 B)** — `bytes_downloaded` exceeded `bytes_total` because `bytes_total` is a depot-manifest ESTIMATE (8.99 GB) while the real install is 10.66 GB. Display-only (final state correct); affects the Steam detail two-bar progress AND the Download Manager card. Same family as the earlier depot-byte-log mis-count. **Fix later** (clamp % to ≤100, or use a better total): bundle with bug (a) the stale-100% counter fix.
>
> **🧹 DB CLEAN SLATE (done, per user) — for the repro.** Currently-installed device build is **vc37/2.2.2 = the `52e7e38` (two-bar+landing) APK** (installed 14:25; NOT yet the uninstall-feedback build). Verified installed games via `steam.db`: **FlatOut (6220) is the ONLY `is_installed=1`** (1.2 GB real on disk, `FlatOut.exe`/`data.bfs` present); HL/HL2/Portal2 all uninstalled during testing; DB knows 229 games. Found + removed **one orphaned `steam_downloads` row** (HL2 220 `status='queued', 0/8.99 GB` — leftover from the wedged reinstall, could have re-triggered the CM wedge on reconnect). **How:** force-stopped app → backed up (device `/data/local/tmp/steam.db.bak` + local `~/scratchpad/steam_before_clean.db`) → deleted only that row locally + vacuum → **overwrote the existing file in place** (preserves inode owner `u0_a493`/mode `660`/SELinux `app_data_file` context) → verified round-trip: `steam_downloads` empty, FlatOut still installed, HL2 `is_installed=0/install_dir=''`, 229 games, `integrity_check=ok`. App left force-stopped (user reopens normally). **So the repro now starts from a genuine clean slate: FlatOut only, zero pending/queued downloads.**

---

## 2026-07-04 — ⏭️ NEXT FEATURE: cross-store Download Manager (Steam-first, Compose M3) — UNBLOCKED, ready to start

> The gate is met (Steam work merged to main), so the **cross-store Download Manager** is now the active feature. **Design is locked** (spec + exact UI tokens + HTML preview already delivered — see memory `project_bannerlator_download_manager`). Template = BannerHub 3.8.0's `BhDownloadService`/`BhDownloadsActivity`/`⬇ badge`, ported to **Jetpack Compose M3 / WinlatorTheme**. v1 scope = **Downloads + Library** (active/paused downloads on top, persistent installed-game library w/ Launch/Uninstall below, Clear), Steam-first.
> **Phasing (agreed):** (0) ✅ finish+merge Steam — DONE. (1) build store-agnostic **`DownloadRegistry`** (observable StateFlow) + normalized **`DownloadEntry`**(store,id,name,cover,state,pct,installDone/Total,downloadDone/Total,pause?,cancel) = the `BhDownloadService` role. (2) route `SteamDepotDownloader` into it (already has the data via its listener; keep the `DownloadProgress:` event). (3) Compose **`DownloadsButton`** (M3 IconButton+Badge) + **`DownloadManagerScreen`** (Scaffold/TopAppBar "Downloads & Library", LazyColumn of cards matching the games/container card idiom, **two-bar byte progress** like the Steam detail page, store-colored badges, Clear). (4) wire ⬇ into Steam library+detail headers + tap-card→correct-store-detail routing. (5) LATER: Epic/GOG/Amazon report into the same registry (cancel-only where no pause). Each phase = own CI build + device test + memory/log checkpoint.
> **Start:** branch off `main` (e.g. `feat/download-manager`). No release tie-in.
>
> **✅ PHASE 1 BUILT (`2476995`, branch `feat/download-manager`):** store-agnostic data layer in new subpackage `com.winlator.star.store.download` (3 files, 368 lines) — `DownloadModels.kt` (`Store`/`DownloadState` enums, `DownloadEntry` w/ two byte pairs + transient pause/cancel lambdas + `key`/`isActive`, `LibraryEntry`), `DownloadRegistry.kt` (object: `entries`/`activeCount`/`library` StateFlows, `init`/`upsert`/`update`/`remove`/`clear`/`get`/`isActive`/lib ops, thread-safe CAS, INSTALLED-only durable `bh_library` persistence), `StoreStyle.kt` (store accent colors). Zero Steam imports; Phase 2/3 seams documented in KDoc. Compile-check CI `28713214632` running (no local build available). **NEXT = Phase 2: route `SteamDepotDownloader` into the registry.**
>
> **✅ PHASE 2 BUILT (`6826c93`):** Steam is now a live PRODUCER into `DownloadRegistry`. `SteamDepotDownloader.kt` gets additive hooks — start→`upsert(DOWNLOADING, pause/cancel from DownloadControl)`, progress→`update{copy(pct,bytes)}`, complete→`copy(INSTALLED, installPath)` (auto-persists to library), pause/cancel/fail→state transitions at existing finally points (fail centralized in `emitFailed`). NEW `SteamLibrarySync.kt` seeds `is_installed=1` games into the library; `DownloadRegistry.init` + seed wired in `SteamForegroundService.onStartCommand`. Existing `DownloadProgress:`/`DownloadComplete:` emits unchanged (detail page still works); registry still imports zero Steam types. **NEXT = Phase 3: Compose UI (⬇ badge + DownloadManagerScreen) → combined build → deliver.**
>
> **✅ PHASE 3 BUILT (`b25f891`) — v1 UI COMPLETE (Steam-first):** NEW `DownloadManagerActivity.kt` (screen + cards) + `download/DownloadsButton.kt` (⬇ badge). Screen = "Downloads & Library" Scaffold, LazyColumn of `entries` (`collectAsStateWithLifecycle`), Downloading/Library sections, cards matching `SteamGamesActivity.GameListItem` with two-bar byte progress; Cancel/Pause (active), Launch/Uninstall (installed, reusing the existing shortcuts + `markUninstalled` flows), tap→detail. ⬇ button added to `SteamGamesActivity` + `SteamGameDetailActivity` headers; `GameCoverArt` `private`→`internal`. **⚠️ Added dep `androidx.lifecycle:lifecycle-runtime-compose:2.7.0`** (combined build must confirm resolution). Not yet compiler/device-proven (no local builds).
>
> **🚧 v1 FEATURE-COMPLETE — combined build running.** All 3 phases on `feat/download-manager`; Phase 1 compile-check `28713214632` was GREEN; **combined CI build `28713882981` RUNNING** (Phase 2+3 + new lifecycle dep). On green → deliver standard APK to device Downloads + device-test (badge live-updates during a Steam DL, two-bar progress, Launch/Uninstall round-trip, Library seeded). If red → fix (likely the new dep/import) + rebuild before delivering. **Branch stays open; NO merge to main until device-tested + user sign-off.**
>
> **❌ combined build `28713882981` FAILED → ✅ fixed (`c826c79`) → rebuild `28714157849`.** Cause was NOT the new dep (`lifecycle-runtime-compose` resolved fine) — one missing import: `DownloadsButton.kt` (pkg `...store.download`) referenced `DownloadManagerActivity` (pkg `...store`) without importing it → Unresolved reference. Added the import; verified all other cross-package refs resolve (DownloadManagerActivity imports the .download classes; both Steam headers import DownloadsButton). Rebuild running → deliver on green.
>
> **✅ REBUILD `28714157849` GREEN → APK DELIVERED.** Standard flavor in device Downloads: `bannerlator-download-manager-v1-c826c79-standard.apk` (589 MB, sha `092494f5c8c1`). New dep `lifecycle-runtime-compose:2.7.0` resolves. **v1 Download Manager is now testable on device.** NEXT = device-test: ⬇ opens "Downloads & Library"; start a Steam DL → live badge + two-bar progress + Cancel/Pause → completes into Library w/ Launch/Uninstall; installed games seed into Library. **NO merge to main until device-tested + sign-off.**
>
> **✅✅ DEVICE-TESTED (user screenshots) — v1 WORKS END-TO-END:** ⬇ in Steam Library + detail headers; badge live-count "1" during DL; "Downloads & Library" screen renders (game-card idiom, Steam badges, covers, Downloading/Library sections); **FlatOut DL live 2%→15%→61%** w/ progress + Cancel/Pause; installed HL/HL2/Portal2 auto-seeded into Library w/ Launch/Uninstall; FGS notif "Steam — Downloading FlatOut — 61%". Minor cosmetic: two-bar reads as single bar on fresh DL (byte pairs close) — numbers correct, polish-only. **v1 DEVICE-PROVEN → awaiting user sign-off to MERGE `feat/download-manager`→main.** Then Epic/GOG/Amazon into same registry (later).
>
> **🔧 POST-TEST POLISH:** (1) **Two-bar progress made distinct** (`524d4e5`, build `28714838409`) — both bars were `primary` (solid vs 40% alpha) → blended; download fill now uses themeable `LocalAccentDim`, install stays `primary`, bar 6→8dp; both theme-aware. (2) **Theme Q verified:** `WinlatorTheme` observes `AppThemeState.colorScheme` live; ALL store screens (Steam/Epic/GOG/Amazon) + `DownloadManagerActivity` wrap in it → they honor theme presets; only store-brand badges + installed-green stay fixed by design. **Epic/GOG/Amazon wiring PARKED (user testing more first).** NEXT = build green → deliver → more testing → sign-off → merge. NO merge until sign-off.
>
> **➕ FEATURE (bundled on this branch per user, `52e7e38`): choose default landing screen.** New Settings option "Default Screen on Launch" (RadioButton **Game Shortcuts** / **Containers**) → pref `default_landing_screen` (default `"games"` = historical). `MainActivity.kt:162` startRoute fallback now reads it (only when no deep-link/menu/edit-controls override; Big Picture untouched). `SettingsScreen.kt`: state (near bigPictureMode) + save + UI before Big Picture Mode; added `RadioButton` import. Unrelated to DL manager but rides this branch → will merge to main together. **Two-bar-only build `28714838409` superseded → combined build `28715078107`** (two-bar distinctness + landing screen) running → deliver on green.

---

## 2026-07-04 — 🎉 MERGED TO MAIN: `feat/steam-goldberg-patcher` → main (fast-forward, NO release cut)

> All 3 merge-prep tasks done → **merged the whole Steam/Goldberg branch to `main`** via fast-forward (main was a strict ancestor). `main`: `cd7082c` → **`c89dc03`**. Pushed.
> - **What landed on main:** the entire multi-week arc — Steam store M3 restyle, Goldberg auto-patch, session-hardening saga, Batch 1/2/3 download fixes (library-sync batching, wakelock, the OOM fix), GameNative-style 4-tier download speed, the per-download debug-log toggle, redactor hardening + UI warning, Steam/Epic/GOG logcat PII cleanups, and the cosmetic depot-byte log fix.
> - **⚠️ NO release/pre-release cut (per user):** versionCode **stays 37**, versionName **2.2.2** (unchanged). vc38 pre-release deferred to a later, explicit decision.
> - **Artifact-only build on main dispatched:** run `28712845487` (per user — build only, no tag/release).
> - `feat/steam-goldberg-patcher` branch left in place (not deleted).
> **NEXT:** cross-store **Download Manager** feature ([[project_bannerlator_download_manager]]). When a release is eventually cut, **hand out credits** (upstream OSS: JavaSteam/GameNative/Pluvia/Goldberg-gbe_fork + our own work → GitHub release notes + repo credits).

---

## 2026-07-04 — 🔧 MERGE PREP #1: per-download "Log debug session" toggle (gate verbose diagnostics)

> First merge-prep task done (`89b90b8`). Verbose Steam logging (the ~33k-line `steam_debug.txt` firehose + JavaSteam `LogManager` bridge + engine `debug=true`) was always-on for every user; now gated behind **one switch**: `verbose = BuildConfig.DEBUG || debugLog`, where `debugLog` = a new **per-download checkbox** on the speed-picker dialog ("Log debug session", unchecked by default, not persisted).
> - **Off (release default):** `steam_debug.txt` never created, JS bridge not wired, engine `debug=false` → no firehose, no per-line file I/O during download.
> - **On (debug builds or ticked box):** full `steam_debug.txt` as before.
> - **Never silent:** `dlogError` now WARN-logs regardless, `emitFailed` ERROR-logs, `steam_session.txt` always-on → a failed DL always leaves a trace. Redactor untouched (verified strips tokens/user/email — the only "password" hits in the blazing log were HL asset filenames like `SteamPasswordDialog.res`).
> - `debugLog` threads through install/resume/buildControl/runInstall + retry, same path as `speedTier`. Files: `SteamDepotDownloader.kt`, `SteamGameDetailActivity.kt`. No gradle change (buildConfig already on). Session/login/wakelock unchanged.
> **Known small gaps (by design):** resume (no picker) runs with logging off unless debug build; "log location" UI shows "(not initialized)" when off.
>
> **🔒 SECURITY PASS (`c333008` + follow-up) — audit of the debug logs before shipping the toggle:**
> - **Redactor gap FOUND + FIXED:** the pattern backstops missed Steam's REAL tokens — Steam refresh/access tokens base64 their `{ ` prefix to **`eyA`**, but the JWT regex anchored on canonical `eyJ` (no match), and the 88-char long-token regex is broken by the JWT's dots (segments 43/25/86, all <88). So token safety rode ENTIRELY on exact-match registration timing, no net for an unregistered/mid-download-minted token. Fix: anchor JWT pattern on `ey` (catches `eyA`+`eyJ`); add SteamID64 redaction `76561\d{12}` (prefix-anchored, won't clobber ~19-digit manifest/gid or 40-hex chunk). Verified in python: catches real token + steamID, leaves manifest/chunk ids intact.
> - **Primary guarantee intact:** exact-match strip of username + refresh_token (`registerSecret` at connect 322-323 / login 1150-51,1265-66 / cleared on sign-out 1297); both `dlog` (steam_debug.txt) + `slog` (steam_session.txt) redact every line. Empirically both real logs scanned clean (0 email/user/steamID).
> - **UI warning added:** ticking "Log debug session" now shows red text — share the log only directly with the developer or someone you trust, not publicly, unless self-debugging.
> - **Logcat username leak fixed:** `SteamRepository` lines 500/678 (`Auto-login as`/`Logged in as <username>`) now wrapped in `SteamLogRedactor.redact()` (were raw → logcat only, not shared files, but closed anyway).
> - **Other-storefront logcat findings — now FIXED (all logcat-only, never in a shared file):** `EpicAuthClient.java:90` logged the raw token-endpoint HTTP **error-response body** (CORRECTION: earlier called a "token leak" — overstated; it only fires on error and success/token bodies are NOT logged, so it leaked error/correlation context, not credentials) → now logs status code only; `EpicLoginActivity.kt:92` logged Epic displayName → removed; `GogLoginActivity.kt:171` logged GOG username → removed. **Amazon audited clean** (only status strings + a value-less exception; never username/token). Steam `SteamRepository:500/678` already redacted above.
> CI: superseded `28711420383`+`28711598449`+`28711848519` (cancelled) → complete build **`28711995714` GREEN** (toggle + redactor hardening + warning + Steam/Epic/GOG logcat fixes). **Standard APK delivered to device Downloads: `bannerlator-steam-debug-toggle-301719f-standard.apk`** (589 MB, sha `77e3327af17d`). Not yet device-verified — optional spot-check: box off → no `steam_debug.txt`; box on → log + red warning shown.
> **✅ MERGE-PREP #2 DONE (`c0f8de5`):** reconciled main's 4 commits by merging `origin/main` into the branch. All 4 were **AMA bot** commits (PRs #60/#62 — ama-agent Q&A + no-preamble fixes) touching ONLY `.github/workflows/ama-answer.yml` + `.opencode/agent/ama-agent.md`; zero app code, zero conflicts. `origin/main` is now a strict ancestor of the branch → **branch→main merge will fast-forward.**
> **✅ MERGE-PREP #3 DONE (`e1acb56`):** fixed the cosmetic `Depot N complete: X KB` under-report. Root: `onDepotCompleted` printed the engine's per-depot callback args, which undercount (blazing HL1: depot 2 logged 47.5 MB for a ~575 MB depot). Now prints our OWN cumulative tracking (`installByDepot`/`downloadByDepot` from `onChunkCompleted` — accurate, drives the progress bar + DB) via `maxOf(engineArg, tracked)`; added an accurate grand-total line at `onDownloadCompleted`. Log-text only, no behavioral change, only shows when verbose logging on.
>
> **🎯 ALL 3 MERGE-PREP TASKS DONE. Branch is merge-ready** (`origin/main` is a strict ancestor → **branch→main fast-forwards**). **NEXT = the actual MERGE `feat/steam-goldberg-patcher` → main** (awaiting user go-ahead — consequential/outward). Consider **vc38 pre-release** at merge (branch stuck at vc37 == released 2.2.2). **THEN cross-store Download Manager.** At stable release hand out credits (upstream OSS + our own work → GitHub release notes + repo credits). NOTE: the delivered APK (`301719f`) predates #3 but #3 is log-cosmetic only, so it's behaviorally identical — no re-test needed.

---

## 2026-07-04 — ✅✅ BATCH 3 DEVICE-PROVEN: fresh FULL Half-Life download on BLAZING, zero OOM → merge-to-main gate MET

> Second device test — the heavy one. Uninstalled HL1 first (verified gone: no `steam_games/Half-Life` dir, `steam.db` appId 70 `is_installed=0`, `steam_downloads` empty), then fresh-downloaded Half-Life (appId 70) on **Blazing**. Evidence: `~/scratchpad/steam_debug_hl1_blazing.txt` (32,865 lines).
> - **Blazing tier confirmed:** `Constructing DepotDownloader(tier=32, cores=8, maxDownloads=19, maxDecompress=6, maxFileWrites=6)` — max concurrency = the real memory stress.
> - **Genuinely fresh (not resume/validate):** 0 `Resume seed`, **0 `Validating`**, **4,493 `Pre-allocating`** lines.
> - **ZERO OOM** — no `OutOfMemory`/`Failed to allocate`/`growth limit`/`Parent job is Cancelling`. On the heaviest tier. This is the proof.
> - **Full download verified on disk:** 4,492 `File done`, depot 1 → `pct=100%`, real full-size files (`valve/halflife.wad`=37.9 MB, `xeno.wad`=6.5 MB, `maps/`≈219 MB); `steam.db` now `is_installed=1`, size 603 MB. ~600 MB in **57 s** (~10.5 MB/s).
> - **⚠️ Cosmetic bug to clean up later:** the `Depot N complete: X KB uncompressed/Y compressed` summary line **under-reports** (showed Depot 1 = 31 KB while 4,492 files / ~600 MB actually landed). Harmless — download is correct — but the per-depot byte accounting is wrong; fix when we gate diagnostics.
> **→ MERGE-TO-MAIN GATE MET.** All 3 batches done + device-proven (fresh full DL, both Medium and Blazing tiers, no OOM). **NEXT: reconcile main's 4 commits + gate the verbose diagnostics behind a debug flag → MERGE `feat/steam-goldberg-patcher` to main → THEN cross-store Download Manager.** At the stable release, hand out credits (upstream OSS + our own work → GitHub release notes + repo credits).

---

## 2026-07-04 — ✅ DEVICE TEST PASSED: OOM gone, tiered speed confirmed (HL1) — heavier stress test still pending

> Installed `ad9a4bd` standard APK, ran a Steam download on device (`com.winlator.banner`). **Result: clean, no OOM.** Evidence pulled via root bridge → `~/scratchpad/steam_debug_hl2_tiered.txt` (4 MB / 32,685 lines) + `~/scratchpad/steam_session_hl2_tiered.txt`.
> - **OOM ELIMINATED:** zero `OutOfMemory` / `Failed to allocate` / `growth limit` / `Parent job is Cancelling` — the Batch-2 crash signatures are all absent.
> - **Tiered config fired correctly:** picked **Medium** → log `Constructing DepotDownloader(tier=16, cores=8, maxDownloads=9, maxDecompress=3, maxFileWrites=3, ...)` — exact `cores × ratio` math; `maxFileWrites=3` vs the old crash-causing 100.
> - **Clean completion:** `=== Download complete: appId=70 ===` → `getCompletion() returned` → `=== runInstall() finished ===`; no false-complete trip, wakelock acquired 11:21:06 → released 11:23:32, session stayed ONLINE (no `LogonSessionReplaced`/reconnect during DL). The "42 errors" grep = false positives (HL filenames `error.wav`/`failed.wav`/`containfail.wav`).
> **⚠️ Caveat — this was a LIGHT load:** appId **70 = Half-Life 1** (not HL2/220), and the log shows mostly a **validation pass of already-present files + a ~48 MB delta** (Depot 2 = 47.5 MB), done in ~2.5 min — lighter than the fresh ~8.4 GB HL2 download that originally OOM'd, and on **Medium** not **Fast/Blazing**. Proves the decompress/write pipeline completes with zero OOM, but not yet the sustained high-concurrency case.
> **NEXT (user will run):** one fresh LARGE download on a HIGH tier — HL2 (220) fresh, or delete+re-pull, on **Fast/Blazing** — watching peak RAM. If clean → Batch 3 device-PROVEN → merge-to-main gate.

---

## 2026-07-04 — ✅ OOM fix shipped + GameNative-style 4-tier download speed → standard APK delivered, awaiting device test

> Two commits on `feat/steam-goldberg-patcher`:
> - **`d02b0de` — the OOM fix (conservative):** capped `maxDecompress`/`maxFileWrites` at the call site + added `android:largeHeap="true"`. Root cause was NOT our engine — `app/libs/steam` is empty, we use the same maven `in.dragonbra:javasteam-depotdownloader:1.8.0` as GameNative; the bug was a **mislabeled ctor arg**: the 7th slot is `maxFileWrites` (not `progressUpdateInterval`, which is a hardcoded 500L inside the engine), and we passed **100** → ~100 concurrent write stages × multi-MB buffers → 256 MB heap blown ~15s in.
> - **`ad9a4bd` — upgraded to GameNative's tiered model:** new `DownloadSpeedConfig(tier)` mirroring GameNative — tiers **8/16/24/32 = Slow/Medium/Fast/Blazing** (default **Fast=24**), `{download,decompress}` ratios `.6/.2, 1.2/.4, 1.5/.5, 2.4/.8`, `maxDownloads`/`maxDecompress` derived as **cores × ratio** (adapts to device). `maxFileWrites = maxDecompress` (GameNative omits the arg and takes the engine default; our positional ctor requires it, so we bind it to decompress to keep peak live buffers ≈ decompress+filewrites bounded). Picker expanded **3→4 tiers** (`SteamGameDetailActivity` `DownloadSpeedPickerDialog`), `threads`→`speedTier` plumbed through install/resume/runInstall. Session/login/wakelock untouched; no dependency swap.
> **On an 8-core phone:** Slow 4/1/1, Medium 9/3/3, Fast 12/4/4, Blazing 19/6/6 — vs the old `maxFileWrites=100`. Now matches GameNative on downloads+decompress at every tier.
> **CI:** run `28710061779` GREEN. **Standard APK delivered** to device Downloads: `bannerlator-steam-speed-tiers-ad9a4bd-standard.apk` (589 MB, sha `ae8c962690…`).
> **NEXT = device-test HL2 (appId 220):** open normally → 4-tier picker (Fast default) → expect full ~8.4 GB, **no OOM** (~15s was the old death). Optionally stress **Blazing** watching peak RAM. **If clean → Batch 2/3 done → merge-to-main gate** (reconcile main's 4 commits + gate verbose diagnostics behind a debug flag). Then Download Manager.
> **📌 Release note:** at the stable release merging this branch, hand out credits — upstream OSS (JavaSteam, GameNative, Pluvia, Goldberg/gbe_fork) **+ our own original work** (session hardening, wakelock, library-sync batching, OOM fix, adapted speed tiers, Goldberg auto-patch, store restyle) — in **GitHub release notes + repo credits**. (User instruction 2026-07-04.)

---

## 2026-07-04 — ✅ OOM ROOT-CAUSED + FIX APPLIED (Option B) → committing for CI build

> **The batch-3 "add a Semaphore to DepotDownloader.kt" plan (entry below) was WRONG and is retracted.** native-steam-engineer investigation (compared us to GameNative, decompiled the shipped jar, `javap`'d the ctor):
> - **We do NOT vendor a DepotDownloader.** `app/libs/steam` is EMPTY (`.gitkeep` only — the "built-from-source JavaSteam JAR" note was stale). We pull the SAME maven engine GameNative uses: `in.dragonbra:javasteam[-depotdownloader]:1.8.0` (`app/build.gradle:197-198`). The `DepotChunk.kt:89`/`DepotDownloader.kt:1782` lines are inside that read-only jar, not our source. That engine ALREADY bounds its pipeline, uses ~1 MB chunk-sized buffers (not fixed 8 MB), and lazy-opens files.
> - **REAL cause = a mislabeled constructor arg at our call site** `SteamDepotDownloader.kt:359-369`. The 9-arg ctor is `(client, licenses, debug, useLanCache, maxDownloads, maxDecompress, maxFileWrites, androidEmulation, parentJob)`. We passed the 7th arg `100` commented `// progressUpdateInterval` — but that slot is **`maxFileWrites`** (progressUpdateInterval is a hardcoded 500L INSIDE the engine, not a param). So the write stage ran ~100 concurrent chunks each holding a multi-MB decompressed buffer, and `maxDecompress = threads` too → ~100 live buffers → 256 MB heap blown ~15s in. GameNative runs the identical engine with tiny caps → never OOMs.
> **✅ FIX APPLIED (Option B — call-site tuning, no engine/dependency change, zero contact with session-hardening/wakelock code):** (1) `SteamDepotDownloader.kt` — `maxDecompress = (cores/4).coerceIn(1,2)`, `maxFileWrites = 2` (were `threads` and `100`); `maxDownloads` stays `threads`. (2) `AndroidManifest.xml` — added `android:largeHeap="true"` (safety margin). Option A (swap to `io.github.joshuatam` `-SNAPSHOT` fork) rejected for the OOM: same engine family, non-reproducible dep + full session re-validation for ~zero payoff.
> **NEXT:** CI artifacts-only build → deliver APK → device-test HL2 (appId 220) opened NORMALLY → expect full ~8.4 GB, no OOM, correct Installed size. If clean → both batches done → reconcile main's 4 commits + gate verbose diagnostics behind debug flag → MERGE to main → THEN Download Manager. See [[project_bannerlator_steam_session_hardening]].

---

## 2026-07-04 — ⚠️ Batch 2 DEVICE-TESTED: session fix PROVEN, new OOM wall → Batch 3 queued for tomorrow

> **Batch 2 (`3e68a93`) device-tested — its own goals MET, but download now dies on a NEW cause: OutOfMemoryError.** Evidence: `~/scratchpad/steam_debug_batch2.txt` (5727 lines, pulled from device `.../files/steam_debug.txt`, written 00:51). App updated 00:39, test run 00:51 — **opened NORMALLY, no Force Stop.**
> **✅ What batch 2 fixed (both confirmed on-device):** (1) `WAKELOCK: acquired (partial, held=true)` at 00:51:19 — wakelock works. (2) **ZERO `LogonSessionReplaced`** — the OEM kill/restart/self-collision is GONE; app opened normally, no Force Stop needed (the whole point of batch 2). (3) Download got FURTHER than ever — actively pulling + **decompressing real depot chunks**, writing `valve/sound/holo/*.wav`. No CM timeout, no session death. So the 60s-AsyncJob/session/PICS-netThread saga is behind us.
> **❌ NEW failure — `OutOfMemoryError` at 00:51:34 (~15s in):** `Failed to allocate a 8388624 byte (8 MB) allocation ... target footprint 268435456, growth limit 268435456` (= **256 MB, DEFAULT heap → app has NO `android:largeHeap`**). Stack: `DepotChunk.process(DepotChunk.kt:89)` ← `DepotDownloader.processFileDecompress(DepotDownloader.kt:1782)`. The subsequent flood of `Parent job is Cancelling` chunk errors is the collapse, NOT the cause. **Root cause: the depot downloader fans out too many concurrent 8 MB chunk-decompress buffers while simultaneously PRE-ALLOCATING many files** (log shows a burst of `Pre-allocating`/`Allocating file` for the many small `holo/*.wav` at once) → all 8 MB buffers live together → 256 MB heap exhausted.
> **🛠️ BATCH 3 (prep for tomorrow — DO BOTH):** (1) **`android:largeHeap="true"`** in `AndroidManifest.xml` — raises heap ceiling to ~512 MB+ (safety margin; alone it may only delay OOM on a bigger depot). (2) **Bound decompress/pre-alloc concurrency** in JavaSteam `DepotDownloader` — cap in-flight 8 MB chunk buffers + file pre-allocations with a `Semaphore` around `processFileDecompress` / the chunk-processing flow (`DepotDownloader.kt:276`, `createChunkProcessingFlow`). This is the REAL fix. Verify JAR is our built-from-source JavaSteam (`app/libs/steam`, built in CI step) so the DepotDownloader change is buildable. **Owner: native-steam-engineer subagent.** After impl → commit on `feat/steam-goldberg-patcher` (The412Banner) → CI artifacts-only build → deliver APK → device-test HL2 (appId 220) opened NORMALLY → expect full ~8.4 GB, no OOM, correct Installed size. If clean → both batches done → reconcile main's 4 commits + gate verbose diagnostics behind debug flag → MERGE to main → THEN Download Manager feature.
> Batch 1 was already device-proven (full 10 GB HL2 E2E). See [[project_bannerlator_steam_session_hardening]], [[project_bannerlator_steam_download_login_guard]].

---

## 2026-07-04 — 📥 Download Manager: full spec locked + HTML preview delivered (impl deferred until Steam work merges)

> Cross-store in-app Download Manager, **Steam-first**, Jetpack **Compose M3 / WinlatorTheme**. Template = **BannerHub 3.8.0** (`BhDownloadService` + `BhDownloadsActivity` + ⬇ badge, source at `~/BannerHub/extension/`), ported to Compose. **Implementation begins ONLY AFTER the current Steam download work (Batch 1+2) is device-proven + merged** (user was explicit).
> **User decisions:** v1 = **Downloads + Library** (active DLs + persistent installed library w/ Launch/Uninstall + Clear); progress = **match the Steam detail page's two-bar byte progress**; other stores are ALREADY Compose M3 (verified) so **only the DL manager needs M3 — leave existing store screens as-is**; ⬇ badge (count of active, hidden at 0) in every store library+detail header; tap card → correct-store detail page.
> **Card idiom (must match games/container cards):** the `SteamGamesActivity` list card (L451, "Containers/Shortcuts list idiom") — `RoundedCornerShape(12) · surfaceVariant · 1dp outline · 60×80 rounded cover tile · bodyLarge title · Launch(filled)/Uninstall(outlined-error) buttons`. Cover = `library_600x900.jpg`. Two-bar progress from the detail page (solid install + lighter download + bytes).
> **Build phasing:** (1) store-agnostic `DownloadRegistry` (observable) + normalized `DownloadEntry`; (2) route SteamDepotDownloader in; (3) Compose `DownloadsButton` + `DownloadManagerScreen`; (4) wire ⬇ + routing into Steam headers; (5) later Epic/GOG/Amazon report into the same registry. Each step = own CI build + device test + memory/log checkpoint.
> **DELIVERED (design only):** faithful HTML mockup from the real theme tokens — artifact `claude.ai/code/artifact/42dcfc0e-284a-42e2-a77f-e71efaba2d95`; saved to device `/sdcard/Download/Bannerlator-DownloadManager-preview.html`; source `~/scratchpad/dl-manager-preview.html`. See memory `project_bannerlator_download_manager`.

---

## 2026-07-04 — ✅ Batch 2 build GREEN + APK delivered (CI 28694533713, SHA 3e68a93)

> CI `28694533713` SUCCESS on `3e68a93`. APK byte-verified (589,506,517 B) + scanned → `/sdcard/Download/Bannerlator-standard-batch2-28694533713.apk`. **Awaiting device test — the goal: open app NORMALLY (no Force Stop) → HL2 Install just works** (wakelock stops the kill/restart/self-collision). Verify `WAKELOCK: acquired/released` pairs, FGS "Downloading N%", stall auto-recovers via reconnectAndRelogin; + exe-picker scroll portrait/landscape + Launch HL2. If clean → both batches done → reconcile main's 4 commits + gate verbose diagnostics behind debug flag → MERGE to main.
> **NEW FEATURE SCOPED (post-merge): cross-store Download Manager (Steam-first), Compose M3.** Template = BannerHub 3.8.0 (`BhDownloadService`+`BhDownloadsActivity`+⬇badge). User decisions: v1 = **Downloads + Library** (active DLs + persistent installed library w/ Launch/Uninstall + Clear); progress = **match Steam detail's two-bar byte progress**; other stores already Compose M3 → **only the DL manager needs M3** (leave existing store screens as-is). Build store-agnostic unified registry now, wire Steam only in v1. Cards must match existing games/container card layout+theme. HTML preview requested next. See [[project_bannerlator_download_manager]].

---

## 2026-07-04 — ✅ Batch 2 committed, build started (CI 28694533713, SHA 3e68a93)

> **Commit `3e68a93`** on `feat/steam-goldberg-patcher`. Combined build **`28694533713`** in_progress on HEAD `3e68a93` (~16 min) — carries Batch 2 + the exe-picker responsive-scroll fix (picker-only build 28694272626 cancelled/superseded). Diff reviewed — clean + compile-correct (`row.name` in scope, interop verified).
> **What shipped:** (1) partial WAKE_LOCK held only while downloading (acquire at setDownloadActive(true), release in finally; ref-counted, 6h cap) — keeps the process alive vs the OEM killer so it can't churn into a 2nd process/self-collision. (2) `SteamRepository.reconnectAndRelogin(ms)` — tears the CM session down + rebuilds it; runInstall retry uses it when no depot progress (0%/60s appinfo-no-reply signature) or on a repeat attempt, else keeps lightweight ensureLoggedIn for the ~1h-logoff case. (3) FGS notification wired to real state via process-static `setStatusText` (Online/Connecting/… + 'Downloading N%' throttled; reverts on finish) — makes the FGS truthful + legitimately ongoing.
> **Deferred (documented in code):** dedicated `:steam` single-owner process.
> **Next (checkpoint 3 on build finish):** watch CI green → deliver APK → device-test: open app (NO Force Stop this time) → HL2 Install should just work; verify wakelock held/released log pairs, notification shows Downloading N%, and a stall auto-recovers via reconnectAndRelogin. Also confirm exe-picker scrolls portrait+landscape + Launch HL2.

---

## 2026-07-04 — 🛠️ Batch 2 lined up (session/process hardening) — native-steam agent implementing

> After merge-gate #1 (full HL2 download) proven, user approved Batch 2. A native-steam-engineer subagent is implementing it now (not yet committed/built).
> **Batch 2 (scoped pragmatic):** (1) **partial wakelock while downloading** (+ WAKE_LOCK perm) — root-cause fix for the OEM kill→restart churn that spawns a 2nd process and self-collides (LogonSessionReplaced); (2) **force reconnect+relogin before a retry** — new `SteamRepository.reconnectAndRelogin(ms)` (ensureLoggedIn no-ops when it *thinks* it's logged in, so a stale/masked session never recovered); retry now runs on a genuinely fresh session; (3) **wire the FGS notification to real state** (Online / Downloading N%) — the existing `updateNotification` was dead code; makes the FGS legitimately ongoing (less killable) + honest.
> **DEFERRED (documented follow-up, not in Batch 2):** the heavyweight dedicated `:steam` single-owner process refactor — with the wakelock stopping the churn, the practical collision should be gone; Force Stop stays the fallback.
> **Also queued polish (not started):** exe-picker should filter/rank OUT bin/*.exe SDK tools (surface hl2.exe); grow install denominator past the low PICS estimate.
> **In flight:** exe-picker responsive-height build CI `28694272626` (HEAD 20e08f1) — deliver when green so user can confirm picker scroll + Launch HL2.

---

## 2026-07-04 — 🎉 MERGE-GATE #1 MET: full HL2 download completed E2E (Batch 1 device-proven) + exe-picker scroll fix

> **THE download saga is FIXED.** After Force Stop → single process → HL2 Install on Batch 1 (`6b91f13`): library-sync pause worked, appInfo/depot-keys landed (no 60s timeout), all depots pulled real bytes. Log: `Total downloaded: 5,967,673,520 B (10,684,397,022 uncompressed) from 8 depots` → `Download complete`. **On-disk HL2 = 10 GB** (was 822 MB). UI "100% (8.5 GB / 8.4 GB) · Installed". False-complete guard correctly did NOT trip.
> **Minor/cosmetic:** (a) bar went past 100% — PICS `SizeOnDisk` 8.4 GB underestimates the real ~10 GB; polish = grow the install denominator past the PICS estimate. (b) depots: 221(main)/222(materials)/340(Lost Coast bonus) real; 233/234/380/389/420 = 0 B (other-OS/other-language, filtered).
> **NEW UI BUG FIXED (`c298534`→responsive `20e08f1`, CI `28694272626`; fixed-420dp build 28694211242 cancelled):** launch "Select executable" picker dumped HL2's dozens of `bin/*.exe` SDK tools in a non-scrollable Column → couldn't reach hl2.exe. Fixed both pickers with `heightIn(420dp)+verticalScroll` (scrolls portrait+landscape).
> **Follow-up polish (not done):** picker should filter/rank OUT the bin SDK tools (surface hl2.exe); install-denominator underestimate.
> **NEXT:** deliver `c298534` build → confirm picker scrolls + Launch HL2 → **BATCH 2** (single Steam session across the app's own processes; appInfo-no-reply→reconnect+retry; wakelock/keep-FGS-alive).

---

## 2026-07-04 — ✅ Batch 1 build GREEN + APK delivered (CI 28692248319, SHA 6b91f13)

> **CI `28692248319` SUCCESS** on `6b91f13`. APK byte-verified (589,504,606 B) + media-scanned to `/sdcard/Download/Bannerlator-standard-batch1-28692248319.apk`. Awaiting device test.
> **Test:** Force Stop app (self-collision is Batch 2, not yet fixed — swipe-close leaves the FGS process) → open → HL2 → Install. Capture `hl2_capture.py` still running. **Expect:** Install pauses the library sync → HL2 appInfo/depot-keys land fast (no 60s timeout) → real bytes → ~8.4 GB → correct Installed size → few/no CLEARTEXT errors. Success = merge-gate #1 satisfied → Batch 2.

---

## 2026-07-04 — ✅ Batch 1 committed, build started (CI 28692248319)

> **Commit `6b91f13`** on `feat/steam-goldberg-patcher`. CI build-artifacts.yml run **`28692248319`** in_progress on HEAD `6b91f13` (~16 min). Native-steam agent implemented; diff reviewed — clean + compile-correct.
> **What shipped:** (1) `SteamRepository.syncApps` → sequential batches of 25 (`requestNextAppBatch`/`finishAppSync`), PAUSE the app-sync while a download is active (`downloadActive`/`setDownloadActive`), resume on libraryWorker; queue confined to one thread; `LibraryProgress:2:processed:total`. (2) `SteamDepotDownloader`: `setDownloadActive(true/false)` around the CM work; false-complete guard — if `<90%` of PICS size on disk, refuse markInstalled, emit retryable failure (fixes 405 MB false-complete). (3) new `network_security_config.xml` cleartext-allow only steamcontent.com/steampipe.steamcontent.com + manifest ref. (4) `SteamGamesActivity` phase-2 progress label "Fetching app records (N/372)".
> **Next (checkpoint 3 on build finish):** watch CI green → deliver APK → device-test — open app, tap Install (should no longer need a warm library; the sync pauses for the download), expect depot keys to land + full ~8.4 GB + no CLEARTEXT spam + correct Installed size. Then Batch 2 (single-session, reconnect-retry, wakelock/FGS).

---

## 2026-07-04 — 🛠️ Building Batch 1 hardening (library-sync fix) — native-steam agent implementing, build pending

> **State:** user approved building the fixes in 2 batches. A native-steam-engineer subagent is IMPLEMENTING **Batch 1** right now (not yet committed/built). Branch `feat/steam-goldberg-patcher`.
> **Batch 1 (make a full download work + be correct):** (1) `SteamRepository.syncApps` refactor — ONE 372-app `picsGetProductInfo` → sequential batches of 25; add `downloadActive` flag + pause the app-sync while a download is active so the DL's `requestAppInfo(220)` gets a clear CM connection; (2) `SteamDepotDownloader.runInstall` sets `repo.setDownloadActive(true/false)` around the CM work; (3) resume/false-complete guard — don't `markInstalled` if `finalInstall < iTotal*0.90` (fixes the 405 MB-of-8.4 GB false "Installed"); (4) new `res/xml/network_security_config.xml` cleartext-allow only `steamcontent.com`/`steampipe.steamcontent.com` + manifest ref (kills 500+ `alibaba:80` errors).
> **Batch 2 (later, after Batch 1 proves a clean DL):** single Steam session across the app's own processes; appInfo-no-reply→reconnect+retry; wakelock + keep-FGS-alive.
> **Architecture note (told user):** bottleneck = the SINGLE shared Steam CM TCP connection, not threads/CPU. Can't use a 2nd thread (no 2nd pipe to Steam) or a 2nd process/connection (Steam = one session/account; 2nd logon = LogonSessionReplaced). Downloads: CM control phase (shared session — starved by the sync) vs CDN chunk bytes (already separate parallel HTTP). Fix = time-share the one CM pipe (batch+pause).
> **Next:** review agent diff → commit → dispatch CI → deliver APK → device-test. (User asked to checkpoint now / when build starts / when it finishes.)

---

## 2026-07-04 — 🗺️ PLAN: gated merge of `feat/steam-goldberg-patcher` → `main` (user-approved)

> **Goal:** consolidate the Steam + Goldberg work into `main` so it stops getting lost (the old `feat/steam-detail-revamp` Steam fixes were abandoned on a local branch and had to be re-derived when the store was rebuilt here). **But gate the merge on quality — do NOT merge the not-yet-completing download flow.**
> **Reassurance:** this branch is pushed to GitHub (35 commits ahead of main; main 4 ahead of branch), so the work is safe — merging is not required to preserve it. We can gate freely.
> **Gate sequence (in order, likely this session):**
> 1. **Prove ONE full download E2E on device** — pill+redaction build `28690582627` (HEAD `6cc4d28`): sign out of Steam elsewhere + set app protected/don't-optimize → HL2 (220) → Install → **100% + install**. The real gate — the headline feature has never once completed end-to-end.
> 2. **Land hardening** — `network_security_config.xml` cleartext for `steamcontent.com` CDN (kills the 500+ `alibaba:80` errors) + WAKE_LOCK + keep FGS alive during downloads (OEM process-kill half).
> 3. **Demote diagnostics behind a debug flag** — `wireJavaSteamLog()` (per-chunk JavaSteam bridge → steam_debug.txt) is too heavy for release; gate behind BuildConfig.DEBUG. **KEEP** the `bumpPendingJobTimeouts` 60s AsyncJob watchdog (a real fix).
> 4. **Reconcile main's 4 commits** into the branch (rebase/merge, resolve conflicts).
> 5. **Then merge.**
> **Also outstanding for a clean main:** Goldberg auto-patch only Regular tier device-proven (Experimental/ColdClient/Off-restore untested).
> **Immediate next action:** wait for build `28690582627` green → deliver APK → run gate #1.
> **UPDATE:** build `28690582627` **GREEN** (SHA `6cc4d28`). APK delivered + byte-verified (589,502,775 B) + media-scanned to `/sdcard/Download/Bannerlator-standard-pill-redact-28690582627.apk`. (PRoot session has DIRECT /sdcard access — `cp` to Download, no socket transfer needed.) Awaiting user: sign out of Steam elsewhere + protect app → install → HL2 download. Crash-proof capture = on-device `steam_debug.txt` + persistent `steam_session.txt` (+ will start a device-side `logcat -f /sdcard/...` before the tap); local streamed capture NOT used (dies with PRoot session).
> **🆕 GATE #1 RESULT (21:43): ENGINE + PILL PROVEN, but "complete" at 405 MB = stale-resume-state bug.** ✅ Pill `OFFLINE→CONNECTING→ONLINE` and stayed 🟢 Online through the WHOLE download (screenshot confirms); **ZERO LogonSessionReplaced during download** (signing out elsewhere worked); `steam_session.txt` clean + redacted. Download ran, `=== Download complete ===` 21:44:36. ❌ But UI shows "Installed · ~405.7 MB" while HL2 is 8.4 GB. On-disk `du`: HL2 = **822 MB** (`hl2/` depot 221 = 411 MB PARTIAL, `lostcoast/` depot 340 = 408 MB complete). Log: 7 of 8 depots (incl. main depot 221) reported `Downloaded 0 bytes … complete` and were SKIPPED. **Cause: polluted install dir — 411 MB attempt-1 leftover + persisted `.DepotDownloader` state fooled the resume check into treating depot 221 as already-complete.** Engine not at fault. **NEXT: Uninstall HL2 → Install fresh (clean dir) → expect ~8.4 GB.** **NEW HARDENING (merge-gate): fresh Install must hash-validate / wipe stale `.DepotDownloader` state, and not mark Installed unless bytes ≈ expected.** Also still: 270+ CLEARTEXT `alibaba:80` (net-sec-config). Run logs saved `~/scratchpad/hl2_run_20260703_2143_SUCCESS_partial.log`.
> **🔑 BREAKTHROUGH (21:52–21:59): the `LogonSessionReplaced` is the app COLLIDING WITH ITSELF, not the user's other devices.** After the clean uninstall, fresh DLs died at 0%: 21:52 (8-thread) appInfo no-reply → 60s watchdog → FAILED; 21:57 (4-thread, after force-close+reopen) login OK 21:57:12 → LogonSessionReplaced 21:57:15 → stuck. **logcat proof:** PID 28305 (OLD process from 21:50) STILL ALIVE doing "Library sync complete: 229 apps" at 21:57:15 while the NEW process (506) had just logged in → two app processes, two Steam sessions, self-kick. `ps` confirmed 28305 lingering; FGS restarted 4× (14086→19569→21841→28305). User confirmed nothing else of theirs is logged in — consistent. **CAUSE:** swipe-close kills the UI but `SteamForegroundService` (dataSync/START_STICKY/main proc/no wakelock) keeps the old process + Steam session alive; reopen spawns a 2nd process that double-logs-in → collision. The 15s self-replace guard MASKS it (pill stays 🟢 = false-online, DL hangs). Only success (21:43) was a clean single-process first-launch. **IMMEDIATE FIX = Settings→Apps→Bannerlator→FORCE STOP then open once + download (swipe-close insufficient).** **NEW HARDENING (top priority, merge-gate): (1) one Steam session per account across the app's own processes; (2) if appInfo no-reply within N s while ONLINE → force reconnect+retry (don't let the guard mask a real kick + hang); (3) wakelock to stop FGS kill/restart churn. Supersedes "account live elsewhere" as PRIMARY cause.**
> **🔑 CONFIRMED #2 BLOCKER (22:14, single clean process): LIBRARY SYNC STARVES THE DOWNLOAD (user's hypothesis, proven).** Self-collision fixed (LogonSessionReplaced:0) but HL2 install 22:14:41 → Blocking on getCompletion → 60s later CancellationException → FAILED, 0 keys. Screenshot shows "Fetching 372 app records…"; no "Library sync complete" (372-app sync not settling). Bulk library PICS sync monopolizes the single CM connection → download's requestAppInfo(220) gets no reply in 60s. 21:43 success worked because library was warm. **FIX (hardening #2, user-proposed): (1) pause/deprioritize bulk sync during a download + move PICS parse off pump/netThread; (2) LAZY per-game app-record fetch on detail-open/download (drop the upfront 372-app stampede).** Immediate workaround: open app → leave library untouched until "Fetching…" disappears → then download (warm=free connection).

---

## 2026-07-04 — 🔒 SECURITY: redact username/email/token from all shared diagnostic logs (commit `6cc4d28`, CI `28690582627`)

> **Why:** `steam_debug.txt` + `steam_session.txt` are shared for support, so they must NEVER carry a Steam username, email, or auth/refresh token — including lines forwarded from the bundled JavaSteam library (uncontrolled). Empirical scan of the real 9081-line capture already showed 0 tokens / 0 email / 0 steamID64 (the "token" hits were PICS access-token COUNTS + HL2 asset filenames `refreshlogin.res`/`steampassworddialog.res`), but this makes it a permanent guarantee.
> **Impl:** new `SteamLogRedactor.redact()`/`registerSecret()`/`clearSecrets()`, applied at the ONLY two file-write choke-points — `SteamDepotDownloader.dlog` (covers the JavaSteam bridge + `dlogError` stacks, all funnel through it) and `SteamRepository.slog`. Layer 1 = exact-match on registered secrets (username + refresh_token, registered in `initialize`/`saveSession`/`loginWithToken`, cleared on `logout`) — the only reliable way to strip a non-pattern Steam username. Layer 2 = pattern backstop: email→`[email]`, JWT `eyJ...`→`[token]` (Steam tokens are JWTs), `[A-Za-z0-9_-]{88,}`→`[token]` (bound kept high so it can't clobber 40-hex chunk ids / ~19-digit manifest gids the log needs).
> **Superseded** the pill-only build `28690390977` (cancelled). Branch `feat/steam-goldberg-patcher` HEAD `6cc4d28` = `940902d` (pill) + `6cc4d28` (redaction).
> **NEXT:** CI `28690582627` green → deliver APK → clean live device test (sign out elsewhere + protect app) watching the pill + `steam_session.txt`. Then (a) net-sec-config cleartext for `steamcontent.com` CDN, (b) WAKE_LOCK + keep FGS alive during downloads.

---

## 2026-07-04 — ✅ MANIFEST-HANG FIXED (device-proven); new dominant blocker = `LogonSessionReplaced` mid-download (account live elsewhere + OEM process-kill). Built in-app status pill + persistent session log.

> **Diagnostic-build device result (build `28688995408`, HEAD `7e73811`, HL2 appId 220, 20:49–20:52):**
> - **Attempt 1 (20:49:20): SUCCESS past the old failure.** `requestAppInfo(220)` returned in **~1.4s** (was the 10s `AsyncJobManager.cancelTimedOutJobs` CancellationException), depot keys 233/234/221/222/389/380 all OK, **real chunks downloaded — depot 221 → 4%, install 411 MB/8.4 GB, download 285 MB/4.5 GB, .vpk files written to disk.** The whole "stuck at 0% / manifest AsyncJob 10s timeout" saga is **RESOLVED** by the diag stack (watchdog 10s→60s + `4c49de5` pump-offload). Engine works.
> - **Attempt 2 (20:51:10, the one the user saw at 0%): killed by `LogonSessionReplaced`.** Started `loggedIn=true` so `runInstall` SKIPPED `ensureLoggedIn` (posted NO logon of its own) → appInfo OK 20:51:11 → depot key 233 OK 20:51:13 → **20:51:14.090 `handleLoggedOff got LogonSessionReplaced`** → session dead → CM jobs stalled 60s → **20:52:13 watchdog-extended job cancelled → Download FAILED.** The replace landed OUTSIDE `SELF_REPLACE_WINDOW_MS` (we posted no logon) so the self-replace guard didn't fire; fell to the terminal branch → `emit("LoggedOut")`, no recovery.
> - **Root cause (user-confirmed):** the Steam account **is signed in on another device** (real desktop/mobile Steam). Steam allows only ONE full PC-client session per account → the emulator's logon and the desktop client keep replacing each other (`LogonSessionReplaced`; same reason SteamCMD says "don't run while Steam is open"). Compounded by **OEM/AYANEO process-kill** — attempt-1's log ends abruptly mid-chunk at 20:50:40 (kill signature); app IS Doze-whitelisted but the FGS is `dataSync` with NO wakelock → still killable → restart → re-logon → collide again.
> - New minor issue: **576× CLEARTEXT errors** to `alibaba.cdn.steampipe.steamcontent.com:80` (Android net-sec-policy blocks plain-HTTP CDN); DepotDownloader retries the chunk on an HTTPS host and recovers — non-fatal but wasteful. Repo has no `network_security_config.xml`. (TODO: permit cleartext for `steamcontent.com` — depot chunks are hash-verified.)
> - UI 0% was the dead attempt-2 (never emitted progress); attempt-1 emitted 4% fine, so the progress plumbing works.
>
> **PRIMARY user action:** sign out of Steam on the OTHER device while downloading here + set the app "protected/don't-optimize" in OEM settings → the download should complete (engine proven to 4%).
>
> **SHIPPED THIS SESSION — in-app connection/login status pill (user's idea):** an always-visible indicator in the **top header of both Steam screens** (library + game detail), the honest replacement for the cosmetic never-updated FGS notification. While the app is foregrounded the CM connection lives in the `SteamRepository` singleton in-process, so the pill reflects real session state without depending on the notification. States: 🟢 Online / 🟡 Connecting… / 🟠 Signed in elsewhere (tap↻) / 🔴 Offline (tap↻) / ⚪ Signed out (tap↻); tap calls `reconnectNow()`. **Every transition is logged to a NEW persistent, append-only `steam_session.txt`** (survives across downloads, unlike the per-download `steam_debug.txt` which is truncated each install) AND mirrored into the active download log via `SteamDepotDownloader.mirrorSessionLine()`. On a genuine different-client replace we deliberately do NOT auto-reconnect (would start a logon tug-of-war with the live desktop) — pill shows "Signed in elsewhere", user taps once they've signed out there; the library no longer auto-`finish()`es on that replace so the pill stays tappable. Impl: `SteamRepository.java` `enum SteamStatus{CONNECTING,ONLINE,SIGNED_IN_ELSEWHERE,OFFLINE,SIGNED_OUT}` + `getStatus()`/`setStatus()`/`slog()`/`reconnectNow()`, `setStatus` wired into onConnected/onDisconnected/onLoggedOn(±)/onLoggedOff(4 branches)/loginWithToken/logout; new `SteamStatusPill.kt` composable; both activities add a `steamStatus` state + `SteamStatus:` event handler + pill in header. **NEXT: dispatch CI, deliver APK, device-test — verify pill tracks state live + `steam_session.txt` records the LogonSessionReplaced; then (a) add net-sec-config for cleartext CDN, (b) wakelock + FGS keep-alive during downloads.**
> **Everything under this line predates the pill work.**

---

## 2026-07-04 — ▶️ diagnostic build delivered to device; awaiting user HL2 download re-run (session may crash on app-open)

> **State:** CI `28688995408` **SUCCESS** (compiled clean — the Java→Kotlin-`internal` `getJobManager$javasteam()` call is valid). Branch `feat/steam-goldberg-patcher` HEAD `7e73811` (`e301ca4` JavaSteam LogListener + `7925db9` 60s AsyncJob-timeout watchdog). **APK copied to device:** `/sdcard/Download/Bannerlator-standard-diag-28688995408.apk` (589,497,172 B, byte-size verified vs artifact, media-scanned). Standard flavor = `com.winlator.banner`. User installs manually.
> **The pending test:** install APK → open app → HL2 (appId 220) → Download → **let it sit ≥60s** (do not cancel early).
> **⚠️ CRASH-RECOVERY RUNBOOK (user expects the PRoot session to die when the app opens):**
> 1. The local capture `~/scratchpad/steam.log` (steamwatch.py) **dies with the session** — do NOT rely on it. The **crash-proof source is the ON-DEVICE debug file:** `python3 ~/scratchpad/getlog.py exec cat /storage/emulated/0/Android/data/com.winlator.banner/files/steam_debug.txt`. (Root bridge = getlog daemon `127.0.0.1:8765`, token `~/.logcat-bridge.token`; if absent, `cp /data/data/com.termux/files/home/.logcat-bridge.token ~/.logcat-bridge.token`.)
> 2. This build's `steam_debug.txt` now carries `[JS/…]` JavaSteam-internal lines (TcpConnection send/recv, SteamApps.handleMsg, AsyncJobManager, manifest/CDN) + `bumpPendingJobTimeouts: raised N job(s) to 60000ms` watchdog lines — none of which existed before.
> 3. **Read the window between `onDownloadStarted` and the outcome:** reply/frame lands LATE (~10–60s) and bytes then flow = **transient netThread head-of-line block** behind the ~229-app PICS parse (cause #1 — and the download should actually proceed now) → real fix = serialize/chunk library PICS off the download's netThread (or gate `installApp` behind a library-sync drain). NOTHING inbound by 60s = **genuine no-reply** (session-not-ready / stale socket, cause #2/#3) → real fix = always `ensureSessionReady` before the first depot job (drop the `!isLoggedIn` short-circuit `SteamDepotDownloader.kt:179`).
> 4. **Do NOT** implement "give DepotDownloader its own dispatcher / don't block `.get()`" — that hypothesis is refuted (see entry below).
> **Everything under this line is the diagnosis history that produced this build.**

---

## 2026-07-03 — 🔬 Deadlock hypothesis REFUTED by code-level analysis; real cause = download's first CM AsyncJob gets no reply in 10s (leading: netThread head-of-line block by JavaSteam PICS parse). Diagnostic LogListener build incoming.

> **Correction of the entry below:** the "coroutine-dispatcher deadlock" call was WRONG. A native-steam-engineer pass over the *decompiled* JavaSteam 1.8.0 + depotdownloader 1.8.0 (the exact versions pinned in `app/build.gradle:197-198`) refuted it:
> - DepotDownloader runs on the **shared 64-thread `Dispatchers.IO`** (`CoroutineScope(Dispatchers.IO + SupervisorJob)`), not a private/limited dispatcher — one blocked `.get()` can't starve 64 threads, and the app never shrinks the pool. The failure path itself (`onDownloadFailed`) ran on `Dispatchers.IO` at T+10s, proving it wasn't saturated.
> - CM AsyncJob replies complete on the dedicated **`TcpConnection` netThread** (`SteamRepository.java:301` selects TCP; `TcpConnection.NetLoop` → `SteamClient.postCallback` → `jobManager.tryCompleteJob` at `SteamClient.java:387`), entirely off `Dispatchers.IO`/the pump.
> - **The "zero CM traffic in the 10s gap" was an ARTIFACT, not a symptom:** the app never registers a JavaSteam `LogListener`, so *all* JavaSteam internal logging (`TcpConnection` send/recv, `SteamApps.handleMsg`, `AsyncJobManager`, manifest-request-code, CDN) is fanned only to an empty `LOG_LISTENERS` and discarded. The silence tells us nothing. The `AsyncJobManager.cancelTimedOutJobs` cancellation actually *proves* the request path ran (the job was constructed/registered).
> **Real failure domain:** DepotDownloader's `Steam3Session` issues its first CM AsyncJob for app 220 (`picsGetAccessTokens`/`picsGetProductInfo`, `Steam3Session.java:594/622`; manifest code `:69`) and **no reply reaches the netThread within the 10 000 ms `AsyncJob` default** → `AsyncJobManager` timer cancels it → `CancellationException`. Ranked causes: (1) **netThread head-of-line blocking** — PICS product-info is parsed INLINE on the netThread (`SteamApps.java:441` `handleMsg`→`:447` `postCallback`); a ~229-app library-PICS response monopolizes that one thread so the download's app-info reply can't be read. **This is exactly why `4c49de5` didn't help — it moved the app's `onPICSProductInfo` off the *pump*, but the blocking parse is JavaSteam's `handleMsg` on the *netThread*, which `4c49de5` never touched.** Requires library sync to overlap the download. (2) session-not-ready race — `runInstall` skips `ensureLoggedIn` when `loggedIn==true` (`SteamDepotDownloader.kt:179`). (3) request written to a stale socket after a reconnect gen-swap.
> **Can't pick between them from the current capture because JavaSteam's own logs are discarded** → agent implemented (NOT committed by it) a diagnostic: `wireJavaSteamLog()` installs a `LogManager` `LogListener` forwarding `onLog`/`onError` into `steam_debug.txt`, called at top of `runInstall` (`SteamDepotDownloader.kt`, +LogListener/LogManager imports). Next device capture will show whether the app-info request is written to the socket, whether any inbound frame is read, and whether the netThread is mid-`handleMsg` — decisively separating not-sent vs sent-but-netThread-blocked vs sent-but-no-reply.
> **+ Bundled a CM AsyncJob timeout bump (10s→60s) as a discriminating test.** The 10s is JavaSteam's hard-coded `AsyncJob` default (`AsyncJob.java:34`) with NO Config/per-job/static knob; the only reachable lever (Java-only, `getJobManager$javasteam()` is Kotlin-`internal`) is the live job map `SteamClient.getJobManager$javasteam().getAsyncJobs()` → `AsyncJob.setTimeout()`. Because DepotDownloader creates jobs lazily per phase (appinfo→per-depot manifest/key/CDN), a one-shot bump misses later jobs → added `SteamRepository.bumpPendingJobTimeouts(ms)` (Java, iterates the map, bumps any job below target) + a download-scoped daemon `"SteamJobTimeoutWatchdog"` in `runInstall` polling it every 1s (matches AsyncJobManager's own 1s tick), stopped in `finally`. **Reads the two hypotheses cleanly:** reply lands late (e.g. 25s) = transient netThread head-of-line block (cause #1, and the download would then actually proceed); still nothing at 60s = genuine no-reply (session/transport, cause #2/#3). Diagnostic+mitigation, not a fix for a truly blocked netThread.
> **⏳ Next:** commit+push diagnostic+watchdog → CI build (supersedes `28688733869`) → device re-run HL2 (appId 220) → read the now-visible JavaSteam logs + whether the reply arrives inside the 60s window → apply the matching real fix (serialize/chunk CM PICS off the download's netThread path / always `ensureSessionReady` before first depot job). **Do NOT** apply a "own dispatcher / don't block .get()" fix — evidence says that's not the cause. See memory [[project_bannerlator_steam_session_hardening]].

---

## 2026-07-03 — ⛔ Device-test of `4c49de5` (PICS-off-pump): download STILL fails at 0% → [SUPERSEDED — this entry's deadlock conclusion is REFUTED above]

> **Test:** build `4c49de5` **confirmed installed** on device (`com.winlator.banner`, lastUpdateTime 19:07 EDT, 10 min after CI `28686413427` finished 18:57 EDT). Re-ran HL2 (appId 220) download at 19:24 EDT via root bridge, watched logcat (pid 27263, tag `SteamDepot`) + `steam_debug.txt`.
> **Result: identical failure.** Login solid — `connected=true, loggedIn=true`, **no `LogonSessionReplaced`**, PICS fine (`hasPicsSize=true`, size 8.99 GB known). But `onDownloadStarted` → **exactly 10.2s later** `=== Download FAILED ===`, `onDownloadFailed: null`, `java.util.concurrent.CancellationException` at `AsyncJobManager.cancelTimedOutJobs(AsyncJobManager.kt:111)` → `AsyncJobSingle.setFailed(:49)`. So **hardening #2 (`4c49de5`, PICS parse off pump) fixed a real *earlier* starvation but is NOT the download blocker.**
> **New root cause (from evidence, not inferred):** between `Blocking on getCompletion().get()...`/`onDownloadStarted` and the 10s failure there is **ZERO JavaSteam internal logging** — no CM traffic, no CDN server lookup, **no manifest request ever emitted**. The download coroutines never run at all. => **coroutine-dispatcher deadlock**: SteamDepot blocks the calling thread on `getCompletion().get()`, and the DepotDownloader's download work / manifest-AsyncJob completion is dispatched onto a thread that's now parked → nothing runs → the only thing that ever completes the future is AsyncJobManager's *separate* `TimerThread` cancelling the timed-out job at 10s, whose `CancellationException` unblocks `.get()`.
> **Evidence archived:** `~/scratchpad/steam_hl2_deadlock_20260703.log` + device `/sdcard/Download/steam_debug_hl2_20260703.txt`.
> **⏳ In progress:** `native-steam-engineer` agent tracing the exact `file:line` (SteamDepot `getCompletion().get()` vs the DepotDownloader `CoroutineDispatcher`; likely `SteamDepotDownloader.kt` + whatever logs "Blocking on getCompletion().get()") and drafting the minimal fix (dedicated thread for the blocking get / `await` instead of `.get()` / give DepotDownloader its own dispatcher). Fix NOT yet written/committed. See memory [[project_bannerlator_steam_session_hardening]].

---

## 2026-07-03 — 🐛→🔧 Steam download: login fix device-PROVEN; manifest AsyncJob times out at 0% → move PICS sync off the pump (hardening #2)

> **Device result of `c72d943` (login fix):** HL2 (appId 220) from build `28685150972` reached `connected=true, **loggedIn=true**` with **no `LogonSessionReplaced` teardown** — the single-flight logon + no-self-kill fix is device-proven, that failure class is closed.
> **But download still dies at 0%.** `steam_debug.txt`: `Blocking on getCompletion().get()` (18:23:15) → **~10.4s later `java.util.concurrent.CancellationException` at `AsyncJobManager.cancelTimedOutJobs(:111)`** — the depot **manifest-request AsyncJob timed out**; its reply was never dispatched in time. UI stuck on "Downloading… 0%".
> **Root cause (confirmed in source, not inferred):** `runWaitCallbacks` is posted to the single `SteamPump` HandlerThread (`SteamRepository.java:418`) — *every* callback, incl. the manifest AsyncJob reply, is delivered there. `onLicenseList` (:571) and `onPICSProductInfo` (:650) ran their heavy work **synchronously on the pump**: the SYNC_APPS branch loops ~229 apps parsing each PICS KeyValue tree + depot-selection filter + Room writes → blocks `runWaitCallbacks` for seconds → manifest reply undelivered → Timer-thread 10s watchdog cancels the job → CancellationException. This is exactly **hardening plan item #2**. BC SHA-1 fix already present (:77), so downloads would proceed if the reply just landed.
> **Fix (this session, `SteamRepository.java`, +106/−54):** added a dedicated single-thread `libraryWorker` executor (created in `startPump`, `shutdownNow` in `stopPump`, `runOnLibraryWorker` fallback for pre-start/teardown). Pump handlers now only marshal the callback payload (copy `getLicenseList()` / snapshot `pendingPackages`/`pendingApps` values into an `ArrayList`) and hand the DB + parse work to the worker via new `processPackages`/`processApps` methods. `syncLibrary()` re-sync also routed off the pump. `syncPhase` (volatile) is written on the worker before each `picsGetProductInfo` send and read on the pump only after a reply → happens-before holds, no phase race. JavaSteam send path verified off-pump-safe (`TcpConnection.send` guarded by `netLock`, matches GameNative). Compile-checked (javac -proc:none, zero structural errors); **NOT device-tested yet**.
> **⏳ Next (device):** install the new build, re-run HL2 (appId 220) download — expect the manifest AsyncJob no longer times out at 0% and bytes start flowing. Then remaining hardening items #1/#3/#4/#5. See memory [[project_bannerlator_steam_session_hardening]], [[project_bannerlator_steam_download_login_guard]].

---

## 2026-07-03 — 🐛→✅ Goldberg: back up + restore game-shipped steamclient dlls (found while prepping Experimental/ColdClient device test)

> **How it surfaced:** before touching the UI to test the Experimental/ColdClient tiers, inspected Portal 2 on device via the root bridge (`com.winlator.banner`, `imagefs/steam_games/Portal 2`). md5 **confirmed Regular is correctly applied** (in-place api dlls = the bundled Goldberg regular builds; `.bak` files = distinct pristine originals). But Portal 2 also ships its **own** `bin/steamclient.dll` (md5 `4505032f`, not Goldberg's `2983e67d`).
> **Bug:** `GoldbergPatcher.removeAddedFiles` deleted `steamclient.dll`/`steamclient64.dll` **purely by name** (they're in `ADDED_FILE_NAMES`), and `applyExperimental`/`applyColdClient` **overwrote** them with no backup — unlike api dlls, which shared-prep backs up. So any game that ships its own steamclient loses it: Experimental overwrites it unrecoverably, and **Off deletes it entirely instead of restoring pristine**. Running the Off test as-is would have corrupted the Portal 2 install we just proved works.
> **Fix (`6600914`):** new `backupIfOriginal(file)` mirrors the steam_api `.bak` rule — copies the first (pristine) copy to `<name>.bak` iff the file exists and no backup is there yet — and is called before every steamclient/loader overwrite in Experimental + ColdClient. `removeAddedFiles` now **restores from `.bak` if present** (keeping the `.bak` as the permanent source) and only deletes files with no original (loader exes, `GameOverlayRenderer`, `ColdClientLoader.ini`, our `steam_appid.txt`). Scope note: `bin/win64/` has no original `steamclient64.dll`, so Experimental genuinely *adds* it there (delete-on-restore stays correct); only the x86 `bin/steamclient.dll` collided. A `steam_appid.txt`-backup edit was reverted (games don't ship it → keep it always-added→deleted). Intermediate tier-switch stacking is transient and cleaned at Off.
> **CI:** artifacts-only build **`28684046577`** (`build-artifacts.yml`, ref `feat/steam-goldberg-patcher` tip `6600914`, label steam-logoff-fix).
> **⏳ Next (device, once the build installs):** run Regular→Experimental→ColdClient→Off on Portal 2, verifying each step by md5 against the captured fingerprints (esp. that Off restores `bin/steamclient.dll` to `4505032f` from its new `.bak`). Fingerprints + step-by-step in memory [[project-bannerlator-goldberg-autopatch]].

---

## 2026-07-03 — 🅿️ Steam login: QR path greyed out; Goldberg becomes the focus (Portal 2 patch-apply device-proven)

> **Decision (user):** username/password login is "working solid and the best" → **grey out the QR-code login for now** so we can push on Goldberg. The QR ~1h logoff-recovery work (Fix A/B, `4c6b202`+`6669771`) stays **in the code, untested/parked — not reverted**; we only disabled the UI entry point.
> **Change (`12166b3`, branch `feat/steam-goldberg-patcher`):** `SteamLoginActivity.kt` ~`:354` — the "Sign in with QR Code" `TextButton` is now `enabled = false`, relabeled **"Sign in with QR Code (temporarily unavailable)"**. UI-only, one-line re-enable. Username/password fields untouched (the primary path). ludashi Kotlin+Java compile GREEN.
> **CI:** artifacts-only build **`28681946617`** (`build-artifacts.yml`, ref `feat/steam-goldberg-patcher` tip `12166b3`, label `steam-logoff-fix`) — 3 flavors, no tag/release. User grabs the APK himself (no device push requested).
> **🎮 Goldberg milestone (device):** user **downloaded + installed Portal 2 (single-player Steam title) via the store, then applied the Goldberg patch successfully.** First real single-player validation of the **patch-apply** step — the exact gate Brawlhalla (online-only, Error 3003) could never clear. ⏳ still to confirm explicitly: patched Portal 2 **boots into gameplay** past the Steam check (apply succeeded; in-game boot not yet reported).
> **Net state:** QR >1h survival test **deprioritized/parked** (not abandoned; playbook preserved). Focus = Goldberg tier ladder + launch-proof. Still on `feat/steam-goldberg-patcher`, **NOT merged**.
>
> **✅✅ UPDATE (same day): Portal 2 patched → BOOTED INTO GAMEPLAY on device.** The full end-to-end Goldberg loop is now proven on real hardware: store download → shared prep → DLL swap (**Regular** tier) → launch past the Steam check → in-game. Clears the last unknown (Brawlhalla could never validate it — online-only Error 3003).
>
> **Where the Goldberg branch stands — code is FEATURE-COMPLETE, only validation + shipping remain** (no TODOs/FIXMEs across the 4 Goldberg files; all 3 tiers + `resolveLaunchExe` + restore + PE-arch-detect implemented, compiling green):
> 1. **Tier-ladder breadth (device):** only **Regular** proven. **Experimental** (adds `steamclient(64).dll`) and **Cold Client Loader** (restores orig api + `steamclient_experimental` loader + generated `ColdClientLoader.ini` + **shortcut Exec repoint** to `steamclient_loader_x64.exe`, wired at shortcut-add `SteamGameDetailActivity.kt:538`) are untested. ColdClient is riskiest — changes the launch command and asks the user to re-add the game to Shortcuts. Needs a title that fails Regular to exercise it.
> 2. **Restore/Off flow (device):** confirm Off restores pristine dlls from `.bak` (+ restores shortcut Exec after ColdClient) — the idempotent restore-then-apply golden rule, not yet round-tripped on device.
> 3. **Merge decision:** branch is **19 commits ahead of `origin/main`** and is a **SUPERSET** — Steam-store M3 restyle + Compose container-picker + dl size/progress dual-bar + Goldberg + parked/greyed QR logoff-recovery Fix A/B. Merging Goldberg = merging all of it. Cleanest = one **superset merge** once tiers proven (store rebuild is the substrate Goldberg sits on); alternative = cherry-pick Goldberg-only (fiddly, shared store files).
> 4. **Release/version:** branch `versionCode 37` == released 2.2.2 → **must bump to vc38+** (monotonic gotcha) before any release. Per the beta-channel strategy, first cut defaults to a **pre-release**.
> 5. **Catalog:** `goldberg.tzst` (`goldberg-v1`, MD5 `BC48B103AD3B067D3ED7CDFDAF728A4A`) already LIVE on winlator-contents — no re-cut needed unless the gbe_fork build changes.
>
> **Recommended next step:** device-test Experimental + ColdClient + Off/restore on a couple of titles → single **superset merge to main** + **vc38 pre-release**. QR path stays parked/greyed, untouched.

---

## 2026-07-02 — 🐛→✅ Steam: QR-login downloads die ~1h in — fixed (recover from involuntary CM logoff)

> **Symptom (user):** on the QR-login device, Steam depot downloads stop working ~1 hour after login; on a *different* device using **username/password** they run all day, session after session. (This device has only ever used QR — so the correlation is cross-device, not a clean same-device A/B.)
> **Investigation** = native-steam-engineer audit of our `SteamRepository`/`SteamDepotDownloader` vs **GameNative** (`utkarshdalal/GameNative`, `SteamService.kt`), the reference the user asked to compare against.
> **Root cause (code-certain):** `SteamRepository.onLoggedOff` (`:485`) was a **dead-end** — it set `loggedIn=false` and emitted `LoggedOut` with **no reconnect and no re-logon**, unlike `onDisconnected` (`:445`) which auto-recovers a socket drop. Depot downloads authenticate purely over the live CM session (manifest request codes, depot keys, CDN tokens — `SteamDepotDownloader.kt:256-371`), **not** a WebAPI bearer. So a clean mid-session CM **LoggedOff** (`EResult.Expired` ~1h into a QR-approved session; password sessions last longer or drop as a *recoverable* Disconnect) permanently stranded the session → in-flight download stalled → surfaced a bogus **"Unknown error."**
> **Not the cause (ruled out):** `getAccessToken()` (`:892`) returns the refresh token ("doubles as bearer") but has **zero callers** — dead code, not in the download path. Both auth managers mint **identical SteamClient-audience** tokens (`persistentSession=true`), so QR-vs-password is **which callback fires**, not a token-audience misconfig. Our `LogOnDetails.setAccessToken(refreshToken)` pattern **matches GameNative** — correct, not misused.
> **Verdict:** we were **MISSING recovery, not misusing an API.** GameNative's `onLoggedOff`→`reconnect()` (`SteamService.kt:3940-3970`) heals *both* login types with **no proactive token renewal** — pure reconnect+relogin from the stored refresh token.
>
> **Fix (branch `feat/steam-goldberg-patcher`, `4c6b202` + follow-up; ludashi Kotlin+Java compile GREEN):**
> - **A — `SteamRepository.java` (the fix):** `onLoggedOff` now recovers an involuntary logoff by forcing a reconnect+relogin. `forceReconnect` flag lets `onDisconnected` proceed even though a self-initiated `disconnect()` reports `userInitiated=true` (the gotcha). Bounded by `logoffRecoveryAttempts < MAX_LOGOFF_RECOVERY(3)` (reset on `LoggedOn`) so a dead token can't loop. `loggingOut` flag (set by `logout()`, cleared on login/`saveSession`) keeps an intentional sign-out from being "recovered." `LoggedInElsewhere`/`LogonSessionReplaced` treated as terminal.
> - **B — `SteamDepotDownloader.kt`:** `onDownloadFailed` now **defers** to the `finally` block, which awaits `ensureLoggedIn(30s)` and **retries the install once as a resume** (`attempt` param, `MAX_SESSION_RETRIES=2`) before surfacing failure — so a mid-download logoff reuses already-downloaded files instead of restarting. Plus `initDebugLog(truncate = attempt == 0)` so the retry **appends** rather than wiping `steam_debug.txt` — the failure+recovery narrative survives for on-device diagnosis.
> - **C/D deliberately deferred:** optional proactive `generateAccessTokenForApp(refreshToken, allowRenewal=true)` renewal, and deleting the dead `getAccessToken()` — GameNative proves A+B suffice.
>
> **CI:** artifacts-only build **`28625813606`** (`build-artifacts.yml`, ref `feat/steam-goldberg-patcher`, label `steam-logoff-fix`) — the installable APK with the fix.
> **Bridge note:** root bridge daemon (`127.0.0.1:8765`) was alive but this session's PRoot lacked the (boot-rotated) token + Termux-home client; recovered by writing the user-supplied token and speaking the raw `<token>\n<verb>\n` protocol directly (bare verbs `exec`/`ping`/`cat`, not the `--exec` client form). No historical logs survived to confirm the `EResult` (ring buffer aged out, no `steam_debug.txt`, app not running).
> **⏳ GATE (device):** user will download + test the new build. Confirm a fresh **QR login + large download survives past ~1h** — watch `steam_debug.txt` / logcat `SteamRepo` for `Involuntary logoff (Expired) → forcing reconnect+relogin` then a resumed completion. Fix is correct regardless of the exact EResult.

---

## 2026-07-01 — 🏁 RELEASED: Bannerlator 2.2.2 (stable, versionCode 37)

> **GitHub release `2.2.2`** — tag `2.2.2` at `97c0e44`, **prerelease=false / make_latest** (now the Latest release), 3 flavor APKs + `update.json` reporting **versionCode 37 / versionName 2.2.2**. Built by `release.yml` run **`28520346738`** (success, from the main vc37 commit). **vc37 > pre1 vc36 > stable-2.2 vc35** so the in-app updater offers 2.2.2 to both stable and beta users.
> **What shipped (the four areas previously staged on main since 2.2):** in-game ReShade Tier 1 (per-game multi-effect switching, on-demand catalog, typed live controls, pause-box fix); vkBasalt version-aware `.so` re-extraction (existing containers auto-refresh the ReShade layer on next launch); white-accent bundle (#46 control accent + #45 container-creation orphan-dir + white/dark app-accent contrast); FPS-limiter shortcut-persist (#46).
> **Release description** hand-set via `gh release edit --notes-file` to match the 2.2 layout (logo → What's New → Downloads → updating note → thanks) — the workflow's auto-changelog body was overwritten. **README** updated on main (`d837036`): new "What's New in 2.2.2" section (old 2.2 notes moved into a `<details>`), version line → 2.2.2/vc37, and the **ReShade multi-select flat-screen warning in 3 places** (What's New, ReShade feature bullet, and the troubleshooting callout): stacking too many effects can prevent a game from starting (flat/blank screen) → uncheck effects one at a time until it boots.
> **⚠️ Watch item:** the vkBasalt version-aware extraction (`f3a6340`) was CI-green but never independently device-proven — shipped on faith; watch for "existing container didn't pick up the new ReShade layer" reports.

---

## 2026-07-01 — 📦 RELEASE-CHANNEL STATE + artifacts-only test build of `main`

> **Artifacts-only build dispatched** — `build-artifacts.yml` run **`28518700499`**, ref `main` tip `20fd2da` (vc36/2.2.1), label `2.2.1-main-test`, 3 flavors (ludashi/pubg/standard), **NO tag, NO release**. User downloads the APKs to device-test the staged stack; then has "a few things to test and add" before cutting the next release.
> **Channel state (measured 2026-07-01):**
> - **Stable** = `2.2` / vc35 (all users).
> - **Beta (2.2.1-pre1)** = update.json **vc36** = **2.2 + in-game rail scroll ONLY.** ⚠️ CORRECTION: ReShade Tier 1 is **not** in the beta — it merged to main (`d166869`, 2026-07-01 00:19 EDT) ~4.5h *after* pre1 published (2026-06-30 19:45 EDT), and pre1 was built from the `fix/ingame-rail-scroll` branch before Tier 1 landed. (The git tag `2.2.1-pre1` sits loosely at a vc35 docs commit `9f51ed7`; trust the release's update.json vc36, not the tag.)
> - **Staged on `main` (vc36) but released to NOBODY — 4 areas:** (1) ReShade Tier 1 `d166869`; (2) vkBasalt version-aware `extra_libs`/`.so` re-extraction `f3a6340` (makes Tier 1 work for existing users — ships with #1; NOT device-proven, needs an old-container upgrade check); (3) white-accent bundle `0bfeebd` (#46 control accent + #45 container-creation + white/dark app-accent, device-proven); (4) FPS-limiter shortcut-persist `12a4fc8` (#46, device-proven). #46 + #45 both CLOSED.
> **⚠️ Monotonic gotcha:** `main` vc36 == pre1 vc36, so the next pre-release/stable **must bump to vc37+** or the updater won't offer it to pre1 testers.

---

## 2026-07-01 — 🏁 DEVICE-PROVEN + MERGED: #46 FPS-limiter-resets fix — persist in-game toggle to the owning shortcut

> **✅ DEVICE-PROVEN** (user: "installed and tested it, works and remembers now") **+ MERGED to `main`** in `12a4fc8` (`--no-ff` of `8476b60`, `71be697..12a4fc8`), branch `fix/fps-limiter-shortcut-persist` deleted. CI code build `3d59293` / run **`28516599378`** GREEN (13m). **Issue #46 FULLY CLOSED** (both halves — white virtual-control color + this FPS reset — resolved). Version stayed vc36/2.2.1 (accumulate on main, no bump). Fixes the diagnosis in the entry below.
> **Fix (mirror the ReShade Bug A owner-discriminator fix — write-target == read-source), all in `XServerDisplayActivity.java` (+29/−5):**
> 1. `onFpsLimitChange` (`:603-611`): when `shortcut != null`, write `fpsLimiterEnabled` (+`fpsLimiterValue` when > 0) back to the **shortcut** and `shortcut.saveData()` (`Shortcut.java:153/163`); else the container write as before.
> 2. New `resolvedFpsLimiterValue()` mirroring `resolvedFpsLimiterEnabled()` (parseInt with container fallback, null-safe pattern copied from `resolvedManualRefreshRate()`).
> 3. Used the resolver at **both** value read sites so value reads from the same owner it's written to: the drawer value seed (`:795`) and the launch-time `applyFpsLimit(... ? resolvedFpsLimiterValue() : 0)` (was `:2159`, which had paired the shortcut-aware enabled resolver with the raw `container.getFpsLimiterValue()`).
> **Scope:** container-only (no-shortcut) launches unchanged; `matchRefreshRate`/`manualRefreshRate`/`frameGen` untouched (not shortcut-stamped or not editable in-game). Backward-compatible: a shortcut with no `fpsLimiterValue` extra falls back to the container value.
> **Hygiene note:** an initial `git add -A` accidentally staged two `.claude/worktrees/` embedded-repo gitlinks; amended out and added `.gitignore` `.claude/worktrees/` (force-pushed `d5f2b22`→`3d59293`).
> **▶️ Device-retest gate (then comment + close #46):** launch a game via its shortcut → toggle the FPS limiter in-game (test both on→off and off→on, and a cap-value change) → quit → relaunch via the same shortcut → the limiter state + value hold. Confirm a container-only launch still persists, and that a second game's shortcut is unaffected (per-game isolation). No merge / no version bump without user go (accumulate on main per the beta-channel workflow).

---

## 2026-07-01 — 🔎 ROOT-CAUSE CONFIRMED (not yet coded): #46's 2nd complaint "FPS limit resets every time you close a game" = shortcut-vs-container owner mismatch

> **Status: DIAGNOSED, fix planned, NOT implemented/branched.** Code-traced, not device-repro'd yet. This is the open half of issue #46 (Noname267), the same class of bug as ReShade Tier-1 **Bug A** (write-target ≠ read-source across the shortcut/container owner boundary).
> **Trigger — hits ~EVERY shortcut-launched game:** `ShortcutsScreen.kt:1201` stamps an `fpsLimiterEnabled` extra onto every shortcut it saves (always `"1"`/`"0"`, never null), so every game launched from a shortcut/game-entry carries it.
> **The asymmetry:**
> - **READ (launch seed):** `resolvedFpsLimiterEnabled()` (`XServerDisplayActivity.java:3597-3602`) returns `shortcut.getExtra("fpsLimiterEnabled", <container default>)` when `shortcut != null` — i.e. reads the SHORTCUT.
> - **WRITE (in-game toggle):** `onFpsLimitChange` (`:603-611`) commits **only** to `container.setFpsLimiterEnabled(limOn)` / `container.setFpsLimiterValue(limitVal)` + `container.saveData()` — there is NO shortcut branch.
> **⇒** an in-game limiter on/off change is never written back to the owning shortcut, so the next launch re-seeds from the stale shortcut extra and the toggle reverts → "resets every time you close a game." (The *value* seed `:795` / write `:609` both use the container so `fpsLimiterValue` itself survives — but the on/off toggle reverting moots it.)
> **The "and others" part is mostly benign:** `matchRefreshRate` / `manualRefreshRate` are NOT stamped onto shortcuts by the editor, so their resolvers (`:3606`, `:3616`) fall back to the container and read==write (no bug). `frameGenEngine` IS stamped (`:1200`) and read shortcut-aware (`:3527`) but the engine isn't editable in-game (only FG on/off + multiplier are, and those persist container↔container). So the single real, reproducible offender is the **FPS-limiter enable toggle** — exactly the setting the reporter named.
> **FIX PLAN (mirror the ReShade Bug A fix — make write-target == read-source; `Shortcut.java:153/163` already exposes `putExtra()` + `saveData()`):**
> 1. In `onFpsLimitChange` (`:603-611`): if `shortcut != null`, write `fpsLimiterEnabled` (+`fpsLimiterValue`) back to the **shortcut** and `shortcut.saveData()`; else keep the current container write.
> 2. Add `resolvedFpsLimiterValue()` mirroring `resolvedFpsLimiterEnabled()` and use it at the value seed (`:795`) so the value is read from the same owner it's written to.
> **Proposed branch** `fix/fps-limiter-shortcut-persist`. **Device retest** = toggle the limiter in-game → quit → relaunch via that game's shortcut → limiter state holds (both on→off and off→on); confirm container-only (no-shortcut) launches still persist. Then comment + close #46. No merge/version bump without user go.

---

## 2026-07-01 — 🏁 MERGED TO MAIN: #46 white virtual-control accent + #45 container-creation orphan-dir + white/dark app-accent contrast

> **Merge `0bfeebd`** (`--no-ff`, `f3a6340..0bfeebd`), branch `fix/white-accent-and-container-creation` deleted (local + remote). Branch tip `116ef9e` CI-green (run **`28511850270`**, headSha-verified). This adds one commit beyond the earlier checkpoint — `116ef9e` "symmetric on-accent contrast": a **dark** custom accent previously fell through to the preset's baked `onPrimary` (itself dark on Monochrome/Phosphor/Royal Gold/Frost → dark-on-dark glyphs), now mirrors the light-accent guard (derive on-accent from luminance for ANY custom accent; built-in presets keep their designed `onPrimary` → default byte-identical). The white-app-accent follow-up is now **DEVICE-CONFIRMED** by the user ("works well").
> **Version left at vc36 / 2.2.1** (same as main's prior rail-scroll + vkBasalt pre-release). Per the opt-in beta-channel workflow, **NO release cut and NO versionCode bump** — the user chose to let fixes keep accumulating on `main` until there's enough for a pre-release (pre2, vc37) or a stable push. ⚠️ Testers already on vc36 won't be offered a new build until the versionCode bumps to 37+.
> **Issues closed out:** #45 commented + **CLOSED (completed)** pointing at fix `b69c0e7` / merge `0bfeebd`. #46 commented crediting the color fixes (`b69c0e7` + `b1b2cc7` + `116ef9e` / merge `0bfeebd`) but **LEFT OPEN** to track its second, unaddressed complaint — "limit fps and others reset every time you close a game" — for which the reporter was asked for repro steps (which setting, per-game vs container default, GL vs Vulkan). That FPS-reset bug was deliberately NOT in this branch (AMA bot's `state.reset()` theory is a misdiagnosis; real suspect = shortcut-vs-container owner mismatch, needs a repro).

---

## 2026-07-01 — Two user-reported bugs: white virtual-control accent (#46) + container creation bricked by orphan shortcut dir (#45), plus white-app-accent contrast follow-up — ✅ CODE DONE, CI building (branch `fix/white-accent-and-container-creation`, NOT merged) [SUPERSEDED — see MERGED entry above]

> **Source:** GitHub issues #46 ("custom colors… white doesn't apply to virtual controls, becomes blue") and #45 ("Add shortcut issue that can break container creation"). The AMA bot had diagnosed both but never pushed (no CI write creds); all three fixes below are independently code-verified against source, not taken on the bot's word.
> **Branch `fix/white-accent-and-container-creation`** off `main`. Commit 1 `b69c0e7` (#46 control + #45), CI run **`28506368680` ✅GREEN** (headSha == tip, 3 flavors). Commit 2 `b1b2cc7` (white-app-accent follow-up), CI run **`28508536976`** dispatched (headSha verified == tip; result pending at checkpoint). NOT merged, version un-bumped (35/2.2), no tag.
>
> **#46 — white virtual controls rendered as the default blue — FIXED (`ControlElement.java:728`).** The in-game GAMEHUB touch-control style gated its accent path on `boolean hasAccent = accent != -1`, but `resolveAccentColor()` → `InputControlsView.getAccentColor()` always returns a full-opacity ARGB (`0xff000000 | rgb`), so it can never legitimately be a `-1` "no accent" sentinel — **except pure white, which IS `0xFFFFFFFF` == `-1` as a signed int** → `hasAccent=false` → fell back to the hardcoded blue. Fix = `hasAccent = true` (accent is always live; the file's own comment already said "Never -1 now"). ✅ **DEVICE-CONFIRMED by user** (white controls now render white).
>
> **#45 — creating a container silently fails permanently after importing a shortcut for a deleted container — FIXED (`ContainerManager.java` + `ShortcutsViewModel.kt`).** Repro: create container → launch it (registers it in the Shortcuts screen's `ContainerManager`) → delete it → the `+` add-shortcut picker still lists the dead container → importing to it calls `getDesktopDir().mkdirs()` which **recreates** `xuser-<id>/` → the next `createContainer()` reuses that id, `mkdirs()` returns false (dir exists) → returns null → screen closes silently, and stays broken for ALL future creations. Fix, two parts: (1) `createContainer()` — before `mkdirs()`, if the target dir already exists, delete it when it's an orphan (no `.container` config) else bail as a real id collision; (2) `ShortcutsViewModel` — new `liveContainers()` filters `manager.getContainers()` to those whose `.container` file exists on disk, routed through `containers()`, `importShortcut()`, `cloneToContainer()`, `renameImportedShortcut()` so stale entries never appear in the picker or reach the filesystem (indices stay consistent because every call site uses the same filtered list). ✅ **DEVICE-CONFIRMED by user** (deleted container no longer shows in the add-game/shortcut picker).
>
> **Follow-up (user-found in the same test) — a WHITE APP THEME accent made on-accent buttons render solid white — FIXED (`ThemePreset.kt` + 3 call sites).** Separate path from the in-game control fix: out-of-game Compose UI. Root cause in `ThemePreset.toColorScheme()/toLightColorScheme()` — `val accent = accentOverride ?: primary` set `primary` to the custom white accent but kept the preset's baked `onPrimary` (white on the AMOLED base) → **white content on a now-white primary**. Fix = when a **light custom accent** is supplied (`accentOverride != null && accentOverride.luminance() > 0.5f`) derive a dark on-accent color for `onPrimary`/`onSecondary`; built-in presets and dark custom accents keep their designed `onPrimary`, so **default AMOLED (no override, onPrimary already white) stays byte-identical**. Also routed the hardcoded `Color.White` foregrounds on primary-backed buttons through `colorScheme.onPrimary`: the `+` FAB (`ContainersScreen.kt:185`), the container **Play** button (`:381`), the Saves `+` FAB (`SavesScreen.kt`), and the "NEW" badge (`AppDrawer.kt`) — the last two are the same bug and would also vanish under a white accent. ⏳ **NOT yet device-tested** (diagnosed from the user's description + source; device bridge was down this session — no adb/8765). New import `androidx.compose.ui.graphics.luminance`.
>
> **▶️ REMAINING:** device-retest the white-app-accent follow-up (set theme accent to white → `+`/Play/Saves-`+`/NEW badge show dark icon/text, not solid white; in-game white controls + #45 still hold); then merge decision. Per the opt-in beta-channel workflow these two user-reported fixes are a natural pre-release candidate once fully device-verified. No merge/tag/version-bump without explicit user go. **Not touched:** #46's second complaint ("FPS limit resets every time you close a game") — the AMA bot's `state.reset()` theory is a misdiagnosis (reset at `:506` runs BEFORE the container seed at `:794-795`; in-game changes already write back via `onFpsLimitChange`); real suspect is the same shortcut-vs-container owner mismatch as ReShade Bug A — needs a repro before coding, deliberately left out of this branch.

---

## 2026-07-01 — vkBasalt layer VERSION-AWARE EXTRACTION — existing containers get the updated `extra_libs.tzst` on app-update — ✅ CODE DONE (branch `fix/vkbasalt-version-aware-extraction`, NOT merged)

> **Why:** the layer-extraction gate in `XServerDisplayActivity.java` re-extracted `graphics_driver/extra_libs.tzst` (which carries the vkBasalt layer `libvkbasalt.so`) ONLY when the container was brand-new (`firstTimeBoot`) or the `.so` was TOTALLY ABSENT. It had no "installed `.so` is OUTDATED" case, so a user updating from 2.1.1 (old bundled `.so`) to 2.2.1 who relaunched an EXISTING container kept the stale shared-imagefs `.so` → the new Tier-1 ReShade per-effect features (and CAS/DLS sharpness) silently no-op'd. Required before the 2.2.1 stable cut.
> **Fix — a third trigger + a persisted version marker.** New constant `EXTRA_LIBS_VERSION = 2` (`XServerDisplayActivity.java:240`, near the other constants; MUST be bumped whenever `app/src/main/assets/graphics_driver/extra_libs.tzst` is repacked). Marker FILE (not SharedPreferences, so a reinstall-imagefs resets it consistently) at `imageFs.getLibDir()/.extra_libs_version` holding the int; missing/unparseable ⇒ `-1`. Restructured gate (`~:2976-2991`): `firstTimeBoot` still extracts BOTH `layers.tzst` + `extra_libs.tzst` then writes the marker; otherwise `!vkBasaltSo.exists() || installedVer != EXTRA_LIBS_VERSION` ⇒ extract `extra_libs.tzst` then write the marker. All three successful-extract paths converge the marker to `EXTRA_LIBS_VERSION` (extracts once per app-upgrade). Helpers `readExtraLibsVersion()`/`writeExtraLibsVersion()` (`~:2886`/`:2900`). Log.d mirrors existing style, names which trigger fired.
> **Existing installs have NO marker ⇒ `-1` ⇒ mismatch ⇒ re-extract on first launch after updating** — so every pre-existing container picks up the patched Tier-1 `libvkbasalt.so` (md5 `3129127c098dcaa7704cf264ef47f157`, 1852976 B — already the one in `extra_libs.tzst` on main).
> **DATA-SAFETY (unchanged):** extraction stays a pure additive per-entry overwrite to `imageFs.getRootDir()`; NO delete/clean step added; target unchanged. Verified `extra_libs.tzst` = ONLY `usr/lib/*.so` (libvkbasalt/libvulkan_freedreno/libbcn_layer) + `usr/share/vulkan/*` (icd.d + implicit_layer.d manifests) — NO home/drive_c/user data.
> **NOT bumped:** app versionCode/versionName stay 35/2.2 (the bump lives on another branch). `extra_libs.tzst` NOT modified. No merge/tag.
> **On-device verification recipe:** on a device that already has an EXISTING container built with the OLD `.so`, install the 2.2.1 build over it, relaunch that container (do NOT create a new one) → `imageFs` `usr/lib/libvkbasalt.so` md5 == `3129127c098dcaa7704cf264ef47f157`, `.extra_libs_version` == `2`, and a Tier-1 per-effect toggle (Solo bypass / per-effect enable) takes effect live.

---

## 2026-06-30 — In-game ReShade effect SWITCHING — Tier 1 (multi-effect loadout + per-effect enable gate) — 🏁 FULLY DEVICE-PROVEN + MERGED TO MAIN 2026-07-01 (merge `d166869`)

> 🏁 **STATUS 2026-07-01 — TIER 1 DONE, DEVICE-PROVEN END-TO-END (ludashi).** Gates 1–8 device-verified earlier; then two device-found bugs were fixed and both confirmed on-device on fix build **CI `28492221848`** (branch `feat/reshade-multi-effect-switch` tip **`82c6799`**, headSha `82c67995…` == branch tip, +82/−16 across 3 files, `extra_libs.tzst`/patched .so untouched = app-side only):
> - **BUG A — per-game persistence (commit `801fee9`): FIXED + user-confirmed.** In-game changes to loadout/order, Solo/Stack, per-effect enabled, sliders, and master on/off now persist to the game's **shortcut** and restore on cold relaunch. Root cause = persist path and read path used different owner discriminators (`applyReshadeLive` wrote to the shortcut whenever non-null, but `resolveReshade` reads the shortcut only when it *owns* reshade, else the container) → container-configured reshade launched via a shortcut reverted; master on/off was never persisted at all. Fix = one shared `shortcutOwnsReshade()` (write-target == read-source) + persisted `reshadeMasterEnabled`. Disk-flush verified: each in-game change-commit synchronously rewrites the shortcut `.desktop` (`Shortcut.saveData()` → `FileUtils.writeString`, `XServerDisplayActivity.java:1528`; container branch `container.saveData()` `:1534`), sliders debounced to release — so quitting/killing the game cannot lose changes.
> - **BUG B — pause box on the freeze (commit `82c6799`): FIXED + user-confirmed ("pause and resume now shows up and works correctly").** Root cause = z-order: `PauseBoxOverlay` was an inline `Box` inside the dialog-host ComposeView, which the game SurfaceView (Vulkan/GL + ASR scanout) composites above → hidden on the frozen frame. Fix = host the pill in `androidx.compose.ui.window.Dialog` (own top-level window above the game surface), no dim scrim, non-modal so the drawer stays usable during a Live-preview-OFF freeze; tap → resume.
> ✅ **MERGED to main 2026-07-01** (`d166869`, --no-ff of `feat/reshade-multi-effect-switch` tip `cd716cd`; pushed `9f51ed7..d166869`; version un-bumped 35/2.2, no tag/release). ▶️ **REMAINING (all user-gated / non-functional):** (2) version-aware-extraction fix (re-extract `extra_libs.tzst` when the bundled .so hash differs, so existing containers get the patched layer — needed before release); (3) codegen sweep to prune non-compiling catalog effects; (4) release notes (credits DadSchoorse / Pipetto / StevenMXZ); (5) Tier 2 (live add-from-catalog via on-device recompile). No merge/tag/version-bump without explicit user go.

**Goal (user):** select MULTIPLE ReShade effects per game and **toggle between them LIVE in-game** — Solo (A/B one at a time) or Stack (layered) — with auto-generated per-effect sliders. Built on top of the merged Step 3 stack. Baseline was single-effect only (`Container.getReshadeEffect()` one string, conf `effects=<reshade>:cas`). Two tiers agreed: **Tier 1 = pre-compiled loadout with an instant per-effect enable gate (this entry)**; Tier 2 = live "add from catalog" via on-device recompile (later).

- **LOCKED conf-key contract** (app emits / patch reads, exact): `effects = e1:e2:…:en:cas`; per effect `<ei> = <fxPath>`; uniforms `<ei>_<uniform>[_c] = value` (unchanged `formatUniformLine`); **NEW `<ei>_enabled = 0|1`** (default 1 = active); global master stays `enableOnLaunch`. effectKey = `name→[^A-Za-z0-9_]→_` lowercased.
- **Native patch** — branch `feat/reshade-mes-patch` (`83930e2`), vkBasalt CI `28488420505` ✅GREEN. Extended `patches/vkbasalt-reshade-livereload.patch` (+219/−12): each `ReshadeEffect` reads `<name>_enabled` on construct + on the existing present-hook mtime reload; a disabled effect does an **identity image-copy passthrough** (reuses the proven `TransferEffect` barrier/`vkCmdCopyImage`/layout path — input `PRESENT_SRC→TRANSFER_SRC`, output `UNDEFINED→TRANSFER_DST`, copy, both back to `PRESENT_SRC`) so the ping-pong chain stays valid and downstream/present see the pre-effect frame. Gate flip → `QueueWaitIdle` + re-record `commandBuffersEffect` (one-frame hitch **only** on toggle, never per-frame). Base `Effect::enabled=true` so CAS/builtins are never gated; global `presentEffect`/`enableOnLaunch` orthogonal. Stripped patched `libvkbasalt.so` md5 `3129127c098dcaa7704cf264ef47f157` (1852976 B).
- **App loadout** — branch `feat/reshade-multi-effect-switch`, app work commit `fc3ce45`. android-app-engineer added: `reshade/ReshadeLoadout.java` (parse/serialize + **migration** old single-effect → 1-entry Solo loadout, `paramsForEffect`, `enforceSolo`), `ui/ReshadeLoadoutItem.kt`, `ui/screens/ReshadeLoadoutEditor.kt` (`ReshadeLoadoutState` + editor: mode switch, high-count hint, per-effect typed controls); `Container.java` `reshadeLoadout`/`reshadeMode` (legacy getters kept); `XServerDisplayActivity` `writeVkBasaltConfig` rewritten to iterate the loadout (emits the contract incl. `<ei>_enabled` + colon-joined texture/include paths; live apply skips folder re-staging), `resolveReshade()` = shortcut owns reshade as a **unit** (loadout+mode+params) else container; `XServerDialogState.kt` new `ReshadeApplyCallback(masterEnabled, mode, items)`; `XServerDrawer.kt` ReShade tab = master toggle + Solo/Stack + per-effect radio(solo)/checkbox(stack) + collapsible typed controls + per-effect Reset; `ReshadeCatalogPicker.kt` now **multi-select** (download-on-demand preserved); `ContainerDetail*`/`ShortcutsScreen` wired. Reorder not implemented (order = selection order).
- ⚠️ **Process catch — a FALSE CI PASS was corrected.** The app agent's first "CI green `28489017067`" actually built headSha `9f51ed7` (branch **base**, no Tier-1 code): it committed `fc3ce45` to a stray **local `main`** and never pushed to the feature branch, and `main.yml` ("Any branch compilation") is **workflow_dispatch-only**, building whatever the ref tip is at dispatch. Fixed: moved `feat/reshade-multi-effect-switch` → `fc3ce45` (clean; its parent IS the base), deleted the stray `main`. **Rule reaffirmed: verify the CI run's `headSha` == the intended commit, and push to the ref BEFORE `gh workflow run main.yml --ref <branch>`.**
- **Integration + REAL CI green:** repacked the patched `.so` into `app/src/main/assets/graphics_driver/extra_libs.tzst` (14 entries preserved, `vkBasalt.json` manifest unchanged) + copied the updated patch → commit **`cd1187e`** (pushed); dispatched `main.yml --ref feat/reshade-multi-effect-switch` → run **`28489656349` ✅GREEN** (`build: success`, headSha `cd1187e` verified). So the loadout + enable-gate `.so` genuinely compile in CI (not the earlier wrong-SHA run).
- **✅ Pause/preview UX DONE + INTEGRATED** — branch `feat/reshade-pause-pulse` (`c5cc755`, off `cd1187e`), **CI `28490017156` ✅GREEN (headSha c5cc755 verified, all 3 flavors `assembleRelease`)**. Because it branched off `cd1187e`, that one green build = the ENTIRE Tier 1 stack (loadout + integrated enable-gate `.so` + pause). Cherry-picked onto the feature branch → tip **`ab70dca`** (`git diff c5cc755 HEAD` = only PROGRESS_LOG differs). Impl: `PresentExtension` `PresentListener` (one fire per real present); `pulseReshadePreview()` = register listener + SIGCONT → on the 2nd present (`RESHADE_PULSE_TARGET_PRESENTS=2`) SIGSTOP, `postDelayed` 80ms (`RESHADE_PULSE_FALLBACK_MS`) fallback if the game isn't presenting, `AtomicBoolean`/`reshadePulseInProgress` guard so refreeze fires once and serializes; `setPausedState(boolean)` = single source of truth (SIGSTOP/SIGCONT + mirrors drawer `setIsPaused` + dialog `setPaused`, clears `reshadePreviewPaused` on resume); `PauseBoxOverlay.kt` (centered pill, only the pill `clickable`) hosted in the existing full-size dialog-host ComposeView above the SurfaceView, tap→`onRequestResume`; `onResume`/`exit()` clear-and-resume so teardown can't hang on a suspended guest. "Live preview" toggle itself doesn't route through `apply()` so opening the tab never freezes. **3 flavor APKs downloaded from run 28490017156 → `/home/claude-user/scratchpad/reshade-tier1-apk/{standard,ludashi,pubg}-debug/` (standard = `com.winlator.banner`).**
- 🚨 **DEVICE-TEST SETUP GOTCHA + PRE-RELEASE FOLLOW-UP:** the layer re-extraction (`XServerDisplayActivity.java:2929-2937`) re-extracts `extra_libs.tzst` only on `firstTimeBoot` OR when `libvkbasalt.so` is **absent** — it does NOT replace an **out-of-date** .so. So containers that already have the OLD libvkbasalt.so keep it and silently ignore `<ei>_enabled` (per-effect toggle won't bypass; the multi-effect chain still compiles). ⇒ **DEVICE TEST ON A FRESH CONTAINER** (firstTimeBoot extracts the new md5 `3129127…` .so). **REQUIRED FIX before release** (affects all existing users): make extraction **version-aware** — re-extract when the bundled .so differs (store a version/hash marker or bump on appVersion change), else Tier 1 no-ops on every pre-existing container.
- **(orig plan) Pause/preview UX (co-designed with user):** a persisted **"Live preview"** toggle in the ReShade tab (default OFF). **OFF = freeze-frame + pulse** — enter preview-pause on the first committed change (effect toggle, or slider **release**) via SIGSTOP; each committed change SIGCONT → count **1–2 real presents** (via `PresentExtension`) → SIGSTOP, so the change renders and the native re-record hitch is hidden inside the pulse; a compact **center pause-box** overlay (tap = full resume) shown while frozen, also generalised to normal manual Pause; sliders pulse on release only (not per-tick). **ON = game keeps running** (continuous live slider preview + ~1-frame toggle blip, no box). Reuses `ProcessHelper.pause/resumeAllWineProcesses` (SIGSTOP/SIGCONT), `isPaused`, `onPauseResume`, `PresentExtension`.
  - *Why not "re-present one frozen frame forever" (user idea):* vkBasalt is a **passive** layer (runs only on the game's present); SIGSTOP freezes the presenting thread (may hold the `VkQueue` → external-sync/deadlock if we drive present from outside); and our host compositor re-blitting shows effects already **baked** into the handed-off buffer. A true frozen live-preview would need vkBasalt to **self-drive** a present loop on a cached pre-effect frame, or a **game-clock freeze** (intercept QPC/timeGetTime) — both heavy/fragile. The pulse is the safe ~95% (1–2 frames ≈ the same moment). Kept as a possible future enhancement if the pulse feels insufficient on-device.
- ▶️ **NEXT = DEVICE TEST** (the real gate; branch tip `ab70dca`, APK ready). On a **FRESH container** (see gotcha above), on a **Vulkan/DXVK** title: (1) pre-launch multi-select loadout in shortcut + container settings; (2) in-game ReShade tab lists the loadout, Solo=radio / Stack=checkbox toggle switches effects LIVE; (3) per-effect sliders live-tune; (4) Live-preview OFF → first change freezes + center pause-box, toggle/slider-release pulses 1–2 frames to reveal, tap box resumes; (5) Live-preview ON → game keeps running, continuous slider preview + ~1-frame toggle blip, no box; (6) master on/off; (7) migration: an existing single-effect profile loads as a 1-entry Solo loadout; (8) no teardown hang when exiting while frozen. Then: version-aware-extraction fix, merge decision, Tier 2 (live add-from-catalog + pause-assisted recompile). **No merge / no release / no version bump without user go.**

---

## 2026-06-30 — In-game drawer left icon-rail now scrolls on short screens + 2.2.1 pre-release 1

**Bug (Discord, "TAR - OnePlus 15 1TB - A840 gpu"):** in the in-game side menu the left vertical **icon rail** (Graphics / FPS / ReShade / Controls / Advanced … Task-Manager / Pause / **Exit**) didn't all fit; the user "can't go all the way down to close the game" — the bottom **Exit** button overflowed off-screen with no way to scroll.

**Cause:** the rail was a `fillMaxHeight` `Column` distributing icons with three `Spacer(Modifier.weight(1f))`. On a short drawer height the weight spacers collapse to 0 and the bottom group overflows, unreachable (no scroll). `XServerDrawer.kt:133`.

**Fix (`XServerDrawer.kt`):** wrapped the rail in `BoxWithConstraints` + `verticalScroll`. Content sits in a `Column` with `heightIn(min = maxHeight)` + `Arrangement.SpaceEvenly` over two group-columns (top tabs / bottom TM+Pause+Exit). `SpaceEvenly` reproduces the **exact** three-equal-gap distributed look when it fits, and **stacks + scrolls** when it doesn't, so Exit is always reachable. (`weight()` can't be used inside `verticalScroll` — infinite height — hence `SpaceEvenly`.) Added imports `BoxWithConstraints`, `heightIn`.

**Release:** branch `fix/ingame-rail-scroll`. Bumped `versionCode` 35→36, `versionName` "2.2"→"2.2.1" so the in-app updater flags it. Cutting **2.2.1 Pre-release 1** via `release.yml` (`make_prerelease=true`) — publishes signed release APKs + `update.json` as a GitHub **pre-release**; `make_latest=false` so the **stable `latest` channel is untouched**. Only users who enabled **Settings → "Include pre-releases"** (`update_include_prereleases`, default off) get offered it (by design). NOT merged to main; awaiting on-device confirmation that the rail scrolls + Exit reachable on the OnePlus 15. **✅ DEVICE-VERIFIED by an online user 2026-07-01; merged to main.**

---

## 2026-06-30 — Release 2.2 description rewritten + README accuracy pass + UI-rewrite explainer

**Docs/release only — no code.** Rewrote the **2.2 GitHub release** body (was the auto-generated commit-table) into the established release layout (centered logo → title → bold summary → `✨ What's New` emoji sections → Downloads table → updating note → thanks), covering the real 2.2 scope: themeable interface (app + in-game drawer), 9 new presets (16 total, AMOLED still default), per-game on-screen control colours, File Manager Favorites, rebuilt controller-binding screen, in-game Task Manager (New Task on Vulkan/Native + cards), consistency/readability. Updating note states 2.2 is **app-side only — no ImageFS reinstall**.

- **README:** the bump commit `2ccfda8` had already rewritten it accurately; verified preset names against `ThemePreset.kt` (16 named + Custom) and Favorites labels against `FileManagerScreen.kt:describeLocation` (Internal / SD card / Drive C: / Drive Z:). One fix pushed (`6355d0e`): controls toggle is labelled **"Follow app theme"** (`XServerDrawer.kt:1611`), README said "Follow theme".
- **NEW: "Under the hood — the UI rewrite" section added to the 2.2 release** at user request — what kind of Compose, what's converted, Compose-vs-XML/Java proportion. **Measured facts (for future reference):**
  - **Stack** = Jetpack Compose + **Material 3**, Kotlin, **single-Activity** `MainActivity` + **Navigation-Compose** (`compose-bom:2024.02.00`, `navigation-compose:2.7.6`, `activity-compose:1.8.2`), **Hilt**. ~**237 `@Composable`** across ~**62 Compose files** / **91 .kt** files (~**32k** Kotlin LOC).
  - **Out-of-game app = 100% Compose, no XML screens left.** All drawer destinations are Compose routes (`Screen.kt`): Containers, ContainerDetail, Games/Shortcuts, Contents, InputControls, AdrenoTools, Saves, FileManager, Settings, Appearance, Splash. All 4 storefronts (GOG/Epic/Amazon/Steam — main/login/QR/games/detail) are Compose `setContent {}`. **2.2 converted the last out-of-game holdout: `ExternalControllerBindingsActivity` → Compose.**
  - **In-game stays classic Java by design** = `XServerDisplayActivity` inflates `xserver_display_activity.xml` to host the native `SurfaceView`/`GLSurfaceView` (X11 + Vulkan/GL renderer + Wine draw there — can't live in a Compose tree). 2.2 made the in-game **drawer + dialogs Compose islands** via `ComposeView` (`XServerDrawer.kt`, `XServerDialogHost.kt`).
  - **Proportion honesty:** Java is larger by LOC (~**297 .java / ~53k LOC**) but it's the **emulation engine**, not screens — `xserver/` 7.4k, `renderer/` 5.3k, `core/` 5.2k, `inputcontrols/` 3.6k, store backends, box64/fexcore/winhandler/container/xenvironment/xconnector/alsa/midi/sysvshm. Remaining **legacy XML/Java UI** = perf HUD (`frame_rating`/`hud_*`), `BigPictureActivity`, `ControlsEditorActivity` (control-element editor), file/folder pickers (`ShortcutPicker`/`FolderPicker`/`CustomFilePicker`), native over-surface dialogs (`ContentDialog`/`DownloadProgressDialog`/`TaskManagerDialog.java`). Some XML now **orphaned** (e.g. `shortcut_settings_dialog.xml` — no longer referenced). **75 layout XML / ~5.4k LOC, shrinking.**
- Release: <https://github.com/The412Banner/Bannerlator/releases/tag/2.2>

---

## 2026-06-30 — UI rebuild MERGED to main + 9 new theme presets (artifacts build)

**The umbrella hold is collapsed.** User decided the rebuild is feature-complete enough to merge and apply small fixes forward, so `feat/ui-rebuild` was merged into `main` and a fresh batch of opt-in themes added.

- **Merge:** `feat/ui-rebuild` → `main` = merge commit `35c8a28`. Only `PROGRESS_LOG.md` conflicted (docs); resolved as a union. Code tree verified identical to the umbrella tip (no code conflicts). Brings in: drawers rebuild (P1), theme centralization, drawer dialogs (P2), app-screen colour sweep (P3), on-screen controls + per-profile custom control colour (P4a), legacy-XML accent (P4b), TM-cards, External Controller Bindings → Compose, and File Manager Favorites.
- **9 new theme presets** = commit `5d75439`: Midnight Cobalt, Phosphor, Carbon & Ember, Amethyst, Crimson, Synthwave, Royal Gold, Frost, Monochrome. All opt-in; **AMOLED stays the default**. Inserted *before* "Custom" in `themePresets` so existing saved preset indices 0..6 are unaffected; `AppThemeState.init` adds a one-time `preset_schema_v2` migration that remaps anyone on the old Custom slot (index 7) to the new `CUSTOM_PRESET_INDEX`. `onPrimary` forced dark on light/bright accents (phosphor/gold/frost/mono) for legible on-accent text. AppearanceScreen renders them automatically (`themePresets.chunked(4)`).
- **Build:** pushed to `origin/main` after a brief github outage; artifacts-only build (workflow_dispatch `main.yml`) run `28470653013` in progress. **No release, no version bump** (hard rule).
- ▶️ **GATE before any release:** consolidated on-device test of the whole merged stack — favorites, bindings-Compose, TM-cards, P4b, custom-control-colour sub-items (per-game persist/relaunch, out-of-game editor, back-compat), and the 9 new presets (each recolours app + drawer; AMOLED default unchanged; on-accent text legible). Nothing post-P4a is device-proven yet.

---

## 2026-06-30 — File Manager FAVORITES — CODE DONE + CI ✅GREEN (preview signed off)

**Status:** implemented on `feat/ui-rebuild` — commits `bd57830` (feature) + `34247eb` (Back closes Favorites first) + `c06a397` (toasts on add/remove). CI `28467705193` ✅GREEN (pre-toast); toast commit CI `28469094380` ✅GREEN (tip `c06a397`). NOT merged (umbrella hold). Decisions: both pin entry points + global/absolute-path scope. ▶️ At device-test gate.

- `app/src/main/java/com/winlator/star/util/FavoritesStore.kt` (new) — SharedPreferences, ordered JSON array of absolute paths (`list/isFavorite/add/remove/toggle`). Stores only the path; the card label is derived live.
- `FileManagerScreen.kt` — `describeLocation()`/`FavLocation`/`FavStorage`/`badgeColors`; ⭐ toggle in the path bar (this screen has no top bar — New-Folder is a bottom FAB); content-swap (favorites list REPLACES the file list, path shows "★ Favorites", drive chip dims); `FavoritesList`+`FavoriteCard` (FileItemRow card style + drive badge + container/source line + mono path line + unpin star); row ⋮ "Add to/Remove from Favorites"; "Pin current folder" header action; system Back closes favorites before navigating up. `favTick` drives recompute on pin/unpin.
- ▶️ NEXT: CI green → device test (dedicated list swap; full origin labels incl. container name; jump; both pin paths; persist across relaunch; dead-path drop; Back closes favorites; no FM regressions).

### (prior) DESIGN + HTML PREVIEW

A Discord user asked for favorite/bookmarked directories in the File Manager. After clarification the design is:
- A **⭐ star button** in the top bar that **toggles** a favorites strip directly under the path/drive bar (slides in/out; zero vertical cost when collapsed).
- Favorites render as File-Manager-style cards (`surfaceContainer` + outline), each with a **colour-coded LOCATION badge** showing where it lives — Internal (blue) / SD card (green) / `C:` + container name (amber) / `Z:` imagefs (purple). One-tap jump.
- **Pin** via each folder row's ⋮ menu ("Add to Favorites") + a "Pin current folder" header action; **unpin** via the card's filled ★. Empty-state prompt.
- Theme-follows-accent; badges keep semantic identity colours.

**Why it's cheap:** the "go there" mechanism already exists — `FileManagerScreen.kt:168 openDrive(File)` is a generic jump-to-any-dir. Favorites = persisted `(path)` entries fed into it. Generalize the existing `currentDriveLabel` (`:498-503`) into a `describeLocation(path)` helper for the badges (add container name for `C:` paths via `containers` at `:123` + `Container.getName()`). Pin item goes in the row ⋮ (`:779-807`). Persistence = SharedPreferences/DataStore string-set, global/absolute paths v1, dead paths filtered by the existing `exists()` pattern (`:358`). Pure app-layer (Compose + prefs).

**Open decisions (blocking Kotlin):** (1) container-drive badge text — letter / container name / both; (2) one vs both pin entry points; (3) scope global/absolute (recommended) vs per-container.

**Preview:** `bannerlator_fm_favorites_preview.html` (scratchpad + `~/Downloads`; not pushed to device — no adb this session).

---

## 2026-06-30 — In-game Task Manager rows as cards (match File Manager) + 2 P4 fixes bundled (CODE DONE + CI building)

**TL;DR:** Task Manager processes in the in-game drawer now render as cards like the app File Manager
rows (user request), and the two prior P4 device-test fixes ride along. Latest commit `3e94450` on
`feat/ui-rebuild`, CI `28462330620` building, at device gate. Not merged (umbrella hold).
- `XServerDrawer.kt`: `TmProcessRow` wrapped in a Card matching `FileManagerScreen.FileItemRow`
  (RoundedCornerShape 10dp, `surfaceContainer`, `outline` border, 3dp vertical margin); removed the
  single-surface column + inter-row dividers (cards self-space).
- Bundled (CI-green at `589566c`, not yet device-tested): controls-editor uses app theme accent in
  editMode; binding-spinner text luminance floor (never invisible).

---

## 2026-06-30 — P4 device-test fixes: controls-editor readability + binding text never invisible (CODE DONE + CI building)

**TL;DR:** Two bugs surfaced in device testing of P4b/custom-color, both fixed. Commit `589566c` on
`feat/ui-rebuild`, CI `28461375330` building, at device gate. Not merged (umbrella hold).
- **Controls editor** rendered the on-screen buttons/labels in the per-profile in-game *custom* accent
  (a dark custom colour → unreadable, and it ignored the app theme). Fix: `resolveBaseAccentArgb()` uses
  the app theme accent in `editMode` (the editor); in-game still honours the per-profile custom colour.
- **Binding-spinner text** (themed in P4b) could go invisible on the screen's black background under a
  dark accent — the original black-on-black bug. Fix: `AccentArrayAdapter` applies a luminance floor
  (0.18) → accent when legible, white when too dark; default blue + presets keep their colour.

---

## 2026-06-30 — UI rebuild Phase 4b: legacy XML surfaces follow runtime accent (CODE DONE + reviewed + CI building)

**TL;DR:** The remaining LIVE legacy `@color/colorPrimary` surfaces now follow the runtime theme accent
(fed at inflation via `AppThemeState.getCurrentAccentArgb()`); `colors.xml` keeps #0055FF as the static
fallback so the AMOLED default is unchanged. Dead/Compose-replaced layouts skipped. Commit `7ed3f10` on
`feat/ui-rebuild`, CI `28459471318` building, **at device gate**. Not merged (umbrella hold).

### What changed
- New `widget/AccentArrayAdapter` — re-applies the runtime accent to the controller-binding spinner item
  + dropdown TextViews (both binding-spinner activities point at it; they didn't share an adapter before).
- `ExternalControllerBindingsActivity`: toolbar header background → accent; type + binding spinners →
  AccentArrayAdapter (dropdown view resource preserved).
- `ControlsEditorActivity`: binding spinners → AccentArrayAdapter.
- `ContentDialog`: title icon tint + title text + bottom-bar label → accent; body `TVMessage` untouched.
- `InputControlsFragment` (+ layout id): the "External Controllers" section header → accent at runtime.
- Trash icon left as-is (styled `colorPrimaryDark` on a raster PNG, not `colorPrimary`).

### Device-test gate (pending)
Under a non-default preset: controller-bindings screen header bar + binding-spinner text recolor; native
ContentDialog prompts show accent title/icon/label (body stays readable); Input Controls "External
Controllers" header recolors. At AMOLED default everything stays #0055FF (unchanged).

---

## 2026-06-30 — Per-profile custom accent color for on-screen controls — ✅ CORE DEVICE-PROVEN

Commit `f6ea902` on `feat/ui-rebuild`, CI `28455766095` green. Device-proven (core) via user screenshot:
the in-game Controls tab shows the shared HSV picker ("Controls Accent", hex `#8F6A00`) and the on-screen
controls (A–F row + MRB/BKSP/SPACE/ENTER) render in that amber custom color while the app/drawer stay
green-themed — i.e. the controls are decoupled from the app theme, the override + live redraw work, and
the shared ColorPicker reuse works in-game. Not yet visually confirmed (wired, expected fine): per-game
persistence across relaunch, the out-of-game editor checkbox+swatch path, and old-profile back-compat.
Not merged (umbrella hold). Remaining rebuild work: P4b legacy XML, then single merge.

---

## 2026-06-30 — Per-profile custom accent color for on-screen controls (CODE DONE + reviewed + CI building)

**TL;DR:** Users can override the theme accent on the in-game touch controls with a **custom color saved
per control profile (= per game)**, so the same setup returns next launch. Follow-app-theme stays the
default. Commit `f6ea902` on `feat/ui-rebuild`, CI run `28455766095` building, **at the device gate**.
Not merged (umbrella hold).

### What changed (11 files)
- `ControlsProfile` gains `customAccentEnabled` + `customAccentColor`, serialized in `save()` (header,
  before the elements array). `InputControlsManager.loadProfile` parses them and replaces the brittle
  `fieldsRead==3` break with an explicit break at `elements`/`controllers` — robust to the optional
  fields and old profiles (which just default to follow-theme).
- `InputControlsView.resolveBaseAccentArgb()` = the active profile's custom color when it opted in,
  else the theme accent; the Phase-4a accent getters all derive from it (ControlElement inherits).
- Shared HSV picker extracted to `ui/components/ColorPicker.kt` (reused by Appearance, the in-game
  Controls tab, and a `ComposeView`-hosted `AlertDialog` for the legacy editor, with the three
  ViewTree owners wired so Compose runs outside the activity content view).
- In-game: `XServerDrawerState` + Controls-tab "Follow app theme" toggle + picker; `XServerDisplayActivity`
  seeds from the active profile and persists + redraws live on change / profile switch.
- Out-of-game: `InputControlsFragment` + layout get a Follow-theme checkbox + color swatch → shared picker.

### Device-test gate (pending)
Default = follow theme (unchanged); toggle off → pick color → controls recolor live; set on game A,
relaunch A → persists; game B keeps its own; toggle back on → returns to theme; editor picker persists;
old profiles still load.

---

## 2026-06-30 — UI rebuild Phase 4a: on-screen touch controls follow theme accent — ✅ DEVICE-PROVEN

Commit `df5ce64` on `feat/ui-rebuild`, CI `28453428988` green. Device-proven via user screenshot on a
green/Forest preset: the in-game GAMEHUB-style on-screen controls (A–F shoulder row + MRB/BKSP/SPACE/
ENTER keys) render in the theme accent (green) and the in-game Controls tab is themed to match —
confirming both the classic-path literal routing and the `resolveAccentColor()` stub-wiring (GAMEHUB
glass style) work on hardware. Not merged (umbrella hold). Next: per-profile custom control color
(in progress, same branch), then P4b legacy XML.

---

## 2026-06-30 — UI rebuild Phase 4a: on-screen touch controls follow theme accent (CODE DONE + CI building)

**TL;DR:** Phase 4 = the native/legacy surfaces that Compose theming doesn't reach, wired to the
accent via `AppThemeState.getCurrentAccentArgb()`. **P4a = the in-game on-screen touch controls** —
code-complete on `feat/ui-rebuild` (commit `df5ce64`), CI run `28453428988` building, **at the device
gate**. Not merged (umbrella hold). Next after device-proof = P4b (legacy XML).

### What changed (2 files)
- `widget/InputControlsView.java`: `getSecondaryColor()` now returns the live theme accent (keeping
  the overlay alpha) instead of hardcoded `#0277BD`. Added `getAccentColor()` (full-opacity accent) +
  `getAccentBrightColor()` (accent lerped 55% toward white, for the pressed highlight) + a small
  `lerpToWhite` helper. `getPrimaryColor()` (white idle controls) unchanged.
- `inputcontrols/ControlElement.java`: every `0xff0277bd` → `getAccentColor()` and every `0xff64ddff`
  → `getAccentBrightColor()` across the classic-style strokes, dpad/stick, and button-icon tints;
  collapsed the now-identical GAMEHUB/default icon-tint branch. **Key fix:** `resolveAccentColor()` was
  a `-1` stub (forcing the always-blue fallback) — wired it to `getAccentColor()`, so the GAMEHUB
  "glass" control style (fill/stroke/pressed/text/thumb) now follows the theme too.

### Deliberately out of scope
- **Perf HUD** (`PerfHudView` / `FrameRating*` / `HudMetrics`) — all colors are semantic (FPS
  thresholds + per-metric identity), left untouched (same reasoning as the P3 per-tech dots).
- Idle control tint (white), semantic reds/blacks, and `CPUListView`/`EnvVarsView` (already on the bridge).
- **P4b — legacy XML `@color/colorPrimary` surfaces** (binding spinners, content_dialog,
  input_controls_fragment, main_menu_header, …) — deferred to a separate follow-up; judgment-heavy and
  touches the intentional binding-spinner-blue fix.

### Device-test gate (pending)
Install (manual), launch a container, open the on-screen controls, apply Sunset → control accent
(selected/active/pressed strokes, dpad/stick, icon tints) should be orange not blue, in both the
classic and GAMEHUB visual styles; idle controls stay white; controls still register touch/press.

---

## 2026-06-30 — UI rebuild: Phase 3 DEVICE-PROVEN + Games-cards-match-Containers follow-up (device-proven)

**TL;DR:** Phase 3 (app-screen colour sweep) is now **device-proven** (all 4 checks pass), and a
small follow-up makes the **Games list cards look like the Containers cards** — also device-proven.
Still on the umbrella branch `feat/ui-rebuild` (no merge until the whole rebuild is done).
Next = Phase 4 (native/legacy surfaces via `getCurrentAccentArgb`).

### Phase 3 — app-screen colour sweep — ✅ DEVICE-PROVEN
Commits `8a97185` (sweep ~190 literals → theme tokens) + `b20a58d` (3b: elevated `surfaceContainer`
tokens to restore card depth). Device test on the ludashi build, all 4 pass: (a) AMOLED default card
depth restored, (b) Sunset recolors the whole app incl. headline FAB + renderer/DXVK chips, (c) wiring
intact, (d) semantic/per-tech colors kept.

### Follow-up — Games list cards match Containers cards — ✅ DEVICE-PROVEN
Commit `c6116f5`, CI `28451457959` green. User gripe: the Games list item was a flat edge-to-edge row
while the Containers entry is a floating card. Fix in `ShortcutsScreen.kt` — wrapped `ShortcutItemLayoutL`
in the same `Card` as `ContainersScreen` (rounded 12dp `surfaceVariant` panel, `outline` border, 16dp/6dp
outer margins, 12dp inner padding; `onRun` moved from `Row.clickable` → `Card` onClick) and removed the
inter-item `Divider`. Grid view unchanged (already a bordered tile). User installed manually + confirmed
"looks much better" from a screenshot = device-proven. Not merged (umbrella hold).

---

## 2026-06-30 — Theme + Drawer rebuild: Phase 3 CODE DONE + CI GREEN (at device gate); Phase 2 DEVICE-PROVEN

**TL;DR:** Catch-up checkpoint for Phases 2 & 3 of the UI rebuild. All work lives on the
umbrella branch **`feat/ui-rebuild`** (no merge to main until the whole rebuild is done).
**Phase 2 (drawer dialogs) is device-proven**; **Phase 3 (app-screen colour sweep) is code-complete
and CI-green, now at the device-test gate.**

### Phase 2 — drawer dialogs — ✅ DEVICE-PROVEN (2026-06-30, 2nd attempt)
Commit `33eeb6a` on `feat/ui-rebuild`, CI `28440636066` green. Driven on the ludashi build
(`com.ludashi.benchmark` code 34) via the root bridge, Sunset preset, Vulkan container. All four
checklist items passed, no regressions:
- **Recolor under preset** — in-game drawer, Task Manager tab, CPU/Memory sections, and the New
  Task dialog all themed orange under Sunset; selected rail tab = orange pill.
- **Task Manager MoreVert** — "Bring to Front" (FlipToFront icon, neutral) + "End Process"
  (X/Close icon, red error-tint, destructive); New Task… (accent) + Clear footer themed.
- **🎉 Group B win — New Task dialog VISIBLE on Vulkan AND works** — the old native
  `ContentDialog.prompt` was invisible on Vulkan/ASR; converted to a Compose `AlertDialog`
  (title, text field defaulting `taskmgr.exe`, Cancel/OK). Tapping OK → `winHandler.exec`
  launched the real Windows Task Manager (Running on guest). End-to-end proven.
- **Wiring intact** — dropdown opens, New Task exec spawns a process, Exit → clean Shutdown
  teardown → clean return to Games. No crash/hang.
- Capture note: a persistent detached `logcat` must write to `/data/local/tmp` (not `/sdcard`,
  which is namespace-isolated under magisk su) to survive the bridge connection dropping.

### Phase 3 — app-screen colour sweep — ✅ CODE DONE + CI GREEN, ▶️ AT DEVICE GATE
Two commits on `feat/ui-rebuild`, both CI-green (workflow_dispatch `main.yml`):
- **`8a97185` "phase 3" — the sweep** (CI `28447905004` ✅): ~190 hardcoded `Color(0x…)` sites
  across 13 in-scope Compose screens rerouted onto `MaterialTheme.colorScheme.*` /
  `LocalAccentDim.current`. Stores (`store/*`) and theme-definition files
  (Color/ThemePreset/AppThemeState/Theme) left untouched; colour-only.
  - **Headline fix:** `ShortcutsScreen` FAB(+), grid-tile gradient, scrape icon → `primary`;
    `SpecCardComponents` renderer + DXVK chips → `primary` (so they follow the accent).
  - **Kept semantic colours:** green success `4CAF50`, `installedBlue 4FC3F7`, amber `FFC107`,
    error reds, untrusted salmon, per-tech identity dots, contrast white/black.
- **`b20a58d` "phase 3b" — elevated surfaces (user-approved fix)** (CI `28449013883` ✅): phase-3
  had no token matching the bespoke navy card surfaces / dialog greys → they flattened to
  near-black at default. Fixed by adding Material3's built-in `surfaceContainer` family slots to
  `ThemePreset` (data-class fields + set in `toColorScheme`/`toLightColorScheme`; derived defaults
  `lerp(surface, onSurface, 0.05/0.09/0.14)` so every preset gets a recolouring elevation ramp).
  - **AMOLED override values:** `surfaceContainer=0xFF1A1A2E`, `surfaceContainerHigh=0xFF2A2A38`,
    `surfaceContainerHighest=0xFF38383F` (restores blue-on-black card depth).
  - Repointed Settings / InputControls / FileManager navy cards & buttons by original depth order,
    and themed the leftover dialog greys (`2A2A2A`→High, `333333` tracks→Highest, body text
    `CCCCCC/E0E0E0`→onSurface, `AAAAAA/B0BEC5`→onSurfaceVariant). `Theme.kt` DefaultColorScheme
    confirmed dead/unused.

### Two sanctioned default-look changes to eyeball on device
(User OK'd default changes for this rebuild.) (1) renderer/DXVK chips are now accent-blue at the
AMOLED default instead of teal/green — the requested "chips follow the theme"; (2) Settings/Input/
FileManager card depth — flattened by 3a, restored by 3b's elevated tokens → confirm it reads
~like the original at default and recolours under a preset.

### ▶️ Next
Device-test Phase 3 on the ludashi build (user installs the `ludashi-debug` artifact from CI run
`28449013883`): (a) AMOLED default — Settings/dialogs have raised card depth, nothing broken;
(b) apply Sunset → Games FAB + renderer/DXVK chips + Settings + InputControls + FileManager +
dialogs all recolour; (c) wiring intact; (d) green success buttons stayed green. Then Phase 4
(native/legacy via `getCurrentAccentArgb`) and Phase 5 (optional presets). Merge `feat/ui-rebuild`
→ main only when ALL phases are device-proven.

---

## 2026-06-30 — Theme + Drawer rebuild: Phase 1 DEVICE-PROVEN (all checklist items pass)

**TL;DR:** Phase 1 (themed icons + button restyle of BOTH drawers) is now **device-proven**.
On-device verification on the ludashi build (`com.ludashi.benchmark` code 34, branch
`feat/drawer-rebuild-p1` @ `f30db20`, CI `28434248077` green @ `f30db209`) passed all four
checklist items with **no wiring regressions**. At the merge decision; not yet merged.

### Device test results (release-device-engineer, root bridge)
- **(a) App drawer — PASS** — LIBRARY/SYSTEM/STORES section headers (STORES "· unchanged"
  subtitle), distinct gamepad Games icon, palette Appearance icon (distinct from Settings),
  "NEW" badge, accent glow bar on selected item.
- **(b) Appearance reachable + live recolor — PASS** — Appearance opens from the drawer;
  Sunset preset recolored the whole app AND the drawer live; restored to AMOLED default.
  (Also clears the previously-untested base build `96ed50e`: Appearance nav entry +
  PrimaryDim→accentDim fix both confirmed — scaling chips/borders recolored orange.)
- **(c) In-game drawer — PASS** — AIO Graphics Test container (OpenGL): Graphics rail shows a
  monitor/display icon as a filled accent pill; selected "Linear" scaling chip accent-filled
  with black text; whole in-game drawer themed orange under Sunset.
- **(d) Wiring intact — PASS, no regressions** — launch ×2, tab-switch, Task Manager (7 procs),
  End Process → real kill → clean Shutdown teardown to app, Bring-to-Front dispatched clean
  (visual no-op on single-window AIO = documented native-fullscreen visibility limit), Pause
  fired, Exit closed cleanly to Games.

### Decisions
- **"NEW" badge on Appearance: KEEP for this release** (clean accent pill, not noisy; treat
  as temporary — fine to drop later once Appearance is discovered).
- Out-of-P1-scope (NOT a regression): Games-screen FAB + renderer/DXVK chips stayed blue under
  Sunset — screen-level hardcoded literals = the deferred P3 336-literal sweep.

### Next
- **Merge decision** for `feat/drawer-rebuild-p1` (stacked off the unmerged base
  `feat/theme-centralize-drawer` @ `96ed50e` — merging P1 carries the base; both proven
  together). → then **Phase 2** (drawer dialogs, incl. native ContentDialog → Compose).

## 2026-06-30 — Theme + Drawer rebuild: plans reconciled, Phase 1 (drawer rebuild) building

**TL;DR:** Merged the recolor-only theme-centralization plan with the new drawer-rebuild
request into one plan (`docs/THEME_AND_DRAWER_REBUILD_PLAN.md`). User greenlit **Phase 1**
(themed icons + button restyle of BOTH drawers). Phase 1 is compiling on branch
`feat/drawer-rebuild-p1`. No device test yet — this is a checkpoint before that.

### Decisions
- **Default look may now CHANGE** for the drawers — user dropped the old byte-identical-to-2.1.1
  rule. The rebuilt drawer look ships as the new default. (Other phases' app-screen recolor stays
  visually conservative.)
- **Restyle depth only** — icons + button styling + accent-driven states. NO wiring / structure /
  tab-order / handler changes. End-Process / Bring-to-Front / Exit / Pause / launch / controller
  paths untouched. Stores excluded.
- **Typography ramp + light mode stay CUT** (keep close to original).

### Combined plan = 5 phases
- P0 foundation: Branch 1 in-game color centralization (device-proven) + follow-up `96ed50e`
  (PrimaryDim→LocalAccentDim + Appearance nav entry), CI `28431784626` GREEN — not device-tested
  in isolation; its only independent piece (Appearance nav entry) gets verified inside the P1 test.
- **P1 (BUILDING)** = drawer rebuild: `AppDrawer.kt` (centralize local consts → colorScheme/
  LocalAccentDim, add LIBRARY/SYSTEM/STORES section headers, fix icon gaps Games→distinct gamepad,
  Appearance→`icon_palette`) + `XServerDrawer.kt` (Graphics rail → display icon, scaling/frame-gen/
  toggle/HUD-chip buttons restyled to accent-fill states on the already-centralized colors) + new
  drawables. Branch `feat/drawer-rebuild-p1` stacked off `feat/theme-centralize-drawer` (build =
  base + P1). Worktree `/home/claude-user/wt-drawer-p1`.
- P2 drawer dialogs (incl. native ContentDialogs needing Compose conversion) · P3 app-screen color
  sweep (~336 literals, old Branch 2) · P4 native/legacy via `getCurrentAccentArgb()` · P5 optional
  Midnight Cobalt + Phosphor presets.

### Approved preview
`bannerlator_drawer_rebuild_preview.html` (scratchpad + ~/Downloads + device /sdcard/Download) —
both drawers, live preset + HSV switcher, Before/Rebuilt toggle. Signed off → became P1.

### Next
Agent finishes P1 → push → CI "Any branch compilation." green → **device test** (gate = looks right
+ wiring intact, NOT byte-identical). SAVE memory + this log + commit BEFORE that device test
(same-device OOM rule).

**UPDATE (2026-06-30, later):** P1 code DONE + **CI `28434248077` GREEN**. Branch
`feat/drawer-rebuild-p1` @ `f30db20`. 4 files: AppDrawer.kt + XServerDrawer.kt + new `icon_games.xml`
+ `icon_display.xml`. Wiring confirmed untouched (no handler/structure/order changes). Open question
for user: keep the additive "NEW" badge on Appearance? **Now AT the device-test gate** — checkpoint
re-flushed; user drives the install + test. Phase-completion checkpoint will follow once device-proven.

**⏸️ PAUSED (2026-06-30) — user lost Wi-Fi mid-session, resuming at work.** Nothing in flight; all
pushed. RESUME = device-test the `feat/drawer-rebuild-p1` build (CI `28434248077` green, ludashi
flavor) per the checklist above + decide the Appearance "NEW" badge; on pass → phase-completion
checkpoint → decide merge → Phase 2 (drawer dialogs). Full resume pointer in memory
`project_bannerlator_drawer_rebuild` (🔖 RESUME HERE at top).

---

## 2026-06-30 — Theme centralization: reroute hardcoded colors onto the live theme (branch 1 started)

**TL;DR:** Starting a refactor so the existing theme engine actually paints everywhere — a
preset/accent now recolors the **whole app AND the in-game drawer**, which it doesn't today.
**Hard constraint from the user: the DEFAULT look must stay byte-identical to 2.1.1 (AMOLED,
#0055FF on pure black).** New presets are opt-in; no surprise on update. Stores are out of scope —
only the out-of-game Compose UI and the in-game side drawer + its submenus.

### Why (recon on main 2.1.1)
- `ui/theme/Color.kt`: `Primary`/`GlowPurple`/`AccentBlue` are **static consts** — a custom accent
  never reaches code that uses them directly. Core bug.
- `ui/XServerDrawer.kt` (the in-game drawer, 6 tabs: Graphics/HUD/ReShade/Controls/Advanced/Task Mgr):
  wrapped in `WinlatorTheme` so it inherits fonts but reads `colorScheme` for **zero** colors — paints
  from 6 local hardcoded constants (PureBlack/DarkSurface/…) + the static Primary/GlowPurple. ~37 literal
  sites. So a red accent leaves the drawer blue-on-black.
- Out-of-game: **336+ hardcoded literals** bypass the theme (worst: SettingsScreen 77, ContentsScreen 26,
  InputControlsScreen 21).

### Scope (user-trimmed: "close to original, no extreme complications")
- **CUT** typography ramp (leave force-600) and light-mode revival (leave dead) — colors only.
- Default stays **AMOLED**; existing users keep their saved preset (`AppThemeState` already persists).

### Plan
- **Branch 1 (this one) = steps 0-2:** (0) kill the static Primary/GlowPurple/AccentBlue aliases →
  resolve from the live scheme; (1) make the **AMOLED** preset carry today's EXACT shades (incl. the
  drawer's #000/#0D0D0D/#1A1A1A) so centralization is a visual no-op; (2) move `XServerDrawer.kt` fully
  onto `MaterialTheme.colorScheme.*`. Delivers "in-game drawer follows theme."
- Branch 2 = step 3: sweep the 336+ out-of-game literals, screen by screen (Settings first).
- Branch 3 = step 4: add Midnight Cobalt + Phosphor as **optional** presets.
- **Verify gate (every branch):** on-device before/after screenshot diff proving the default look is
  unchanged. The only failure mode is shade drift.

### Status (updated 2026-06-30)
- Branch `feat/theme-centralize-drawer`. Step-2 edit DONE in `ui/XServerDrawer.kt` (color-only).
- **Diff reviewed = color-only, verified safe:** no function signatures, no `onClick`/`winHandler`/lambda/
  control-flow lines changed. Only color args swapped + 20 `val accent = MaterialTheme.colorScheme.primary`
  and 1 `val surface = ...colorScheme.surface` locals added (pure theme reads). The 3 static imports
  (`Primary`/`GlowPurple`/`PrimaryDim`) removed; `PrimaryDim` kept as a drawer-LOCAL `Color(0xFF002277)`.
- **Drift-safety honored:** every accent site resolves to `colorScheme.primary` (= #0055FF under default
  AMOLED → byte-identical); pure-black panel/rail bg → `colorScheme.surface` (= #000000 under AMOLED). All
  neutral greys (`DarkSurface`/`DimWhite`/`MutedWhite`/toggle-off/#1A1A1A dividers/#4CAF50 green) left local.
- **Known limitation (deliberate):** `PrimaryDim` sites (switch-ON track, AccentButton container,
  selected-tab gradient bottom, HudChip selected bg) do NOT recolor for a CUSTOM accent — they stay
  dark-blue. #002277 maps to no exact scheme slot, so routing it would drift the default. Revisit later if
  we want those to follow the accent (accept a tiny default shift).
- **Functional safety = the user's concern:** theme edit cannot alter wiring. App↔drawer, Wine/containers,
  controller input (legacy `InputControlsView.java`, NOT touched), and all drawer buttons keep their exact
  handlers. Verify gate = on-device functional pass (End Process / Bring to Front / Pause / Exit / Apply &
  Close / ReShade toggle / controller drives game) + before/after color diff. **User drives the device tests.**
- Committed + pushed branch `feat/theme-centralize-drawer`. CI APK build (main.yml,
  **run 28419854007**) **✅ GREEN** 2026-06-30 — all 3 flavors built clean (standard/pubg/ludashi-debug
  ~560 MB each). Color-only diff compiled with NO fixes needed. **APK READY for the morning device test**
  (download the `standard-debug` artifact from run 28419854007).
- **PLAN: user device-tests in the MORNING** (user went to bed 2026-06-30). Build is green; no overnight
  fix was needed. Green APK + test checklist are ready.
- ON-DEVICE CHECKLIST (user drives, I watch logcat): (1) launch game in a real container → open drawer;
  (2) wiring — Task Mgr End Process / Bring to Front / Pause-Resume / Exit-to-app / Controls Apply&Close /
  ReShade toggle / controller drives game; (3) app side — launch + edit a container/shortcut; (4) color —
  default AMOLED drawer unchanged, then custom accent → drawer highlights recolor. EXPECTED non-bug:
  switch-ON tracks + a couple selected-chip bgs stay dark-blue under custom accent (the PrimaryDim call).
- Preview mock: `bannerlator_theme_preview_v2.html` (~/Downloads + /sdcard/Download).

---

## 2026-06-29 — bionic-fg: upstream MERGED our compat PR #6; fork synced; branch-landscape mapped for later

**TL;DR:** Our Android wrapper-ICD compatibility fix was **merged into upstream bionic-fg**
(`xXJSONDeruloXx/bionic-fg` PR #6, squash commit `68497bf`). Synced our fork `main` to it
(clean fast-forward, identical). Audited what the shipped build contains vs every open branch/PR.
No code changes this entry — this is a checkpoint so the shader/model work can be picked up later.

### What merged (PR #6 — "Single-device mode + layer-dispatch routing for Android wrapper ICDs")
- Authored by The412Banner, +310/-22 / 7 files. Fixes the hang-at-first-interpolated-present on the
  Wine+DXVK -> Turnip `wrapper_icd` stack: (1) manifest `disable_environment`, (2) single-device mode
  (run gen on the app's OWN VkDevice -> kills the 2-device cross-instance deadlock), (3) dispatch
  routing via `memPropsFn` + bounded 250ms fence waits + optional `fps_limit`. Device-proven Adreno 750
  2x/3x/4x. Squash-merge `68497bf` rolled in the 4 layer-robustness refinements too.

### Build provenance (verified 2026-06-29)
- Bannerlator submodule still pinned to upstream base `4f71770`; CI (`build-bionic-fg.yml`) applies
  `patches/bionic-fg-bannerlator-fixes.patch` at build time. Verified: that patch applied to the base ==
  merged `68497bf` **byte-for-byte** (0-line diff). So our source == merged main.
- Cleanup available (NOT done): bump the submodule to `68497bf` and DELETE the now-redundant patch.
  Must be one combined change — bumping the pin without removing the patch breaks `git apply` (already
  applied) and fails the bionic-fg CI build.

### Branch / PR landscape vs the SHIPPED build (`68497bf`)
- `feat/toml-hot-reload` (upstream) — MERGED, already in our build.
- **`feat/shader-pool-gamescope-v2`** (our fork, tip `b0c2e5c`) — HIGHEST ROI. New = ~+22.7k lines
  `shaders_embedded.hpp` (FIXES the malformed `shader_02` we currently ship + pools GameScopeVK/V2
  shaders) + `model=2` "V2 engine". This is the open shader-pool thread.
- **`feat/fsr3-optical-flow-model`** (our fork, tip `603d26e`) — HIGHEST CEILING, heaviest. Superset of
  shader-pool + `model=3` AMD FidelityFX Optical Flow (4 new compute shaders, ~+24.8k embedded,
  `NOTICE_FIDELITYFX_OPTICALFLOW.md`, standalone `build-so.yml`). Needs on-device perf+visual validation.
- `fix/model1-remove-warpblend` (upstream, 1 commit) — cheap correctness fix (removes a model-1
  warp-blend stage that shouldn't exist). Not in our build.
- PR #5 `feat/future-refresh-pacing` (upstream, OPEN) — alt frame pacing, overlaps our `fps_limit`,
  unmerged + unproven on Turnip. Lowest confidence.

### Caveat for whoever resumes
All branches above pre-date the squash, so they sit on the un-squashed compat commits (same content,
different SHAs) and read "1 behind". To integrate cleanly: REBASE onto synced `main`/`68497bf`
(cherry-pick only the genuinely-new commits) so the compat changes don't reappear as conflicts.
**Recommended start = `feat/shader-pool-gamescope-v2`** (fixes a bug we currently ship + it's our own
half-done work), then FSR3 `model=3`. Save+commit before any same-device test.

---

## 2026-06-29 (cont.) — STEP 3 ReShade: P2 DEVICE-PROVEN + typed controls/tab/reset + on-demand download catalog LIVE

**TL;DR:** The in-game ReShade feature is now fully device-proven (P2 done). Added typed UI controls,
a dedicated ReShade drawer tab, and a Reset button. Switched the effect library from APK-bundled to
**on-demand download** — published a 100-effect catalog (`reshade.json` + `reshade-v1` release) on
`The412Banner/winlator-contents`; the app-side download UI is building.

### P2 device-proven (in-game ReShade)
- Live `.fx` compile, live on/off, and live per-uniform sliders all confirmed on hardware (Technicolor,
  ArcaneBloom over The Saboteur). The `formatUniformLine` `<effect>_<uniform>` key syntax is correct.

### `feat/reshade-typed-controls` (off `fix/reshade-live-toggle`) — CI `28411754406` GREEN
- `ReshadeManager.ParamType += COMBO, COLOR`; `reflectParams` parses `floatN`/`intN` + `ui_items`
  (`\0`-split), and now **skips `source=`-annotated uniforms** (engine semantics like timer/frametime —
  ArcaneBloom's `uTime`/`uFrameTime` were leaking in as dead sliders).
- Drawer renders by type: bool→toggle, combo/radio→dropdown, color→HSV picker (collapsed by default,
  tap swatch to expand), slider/drag→slider. Value transport stays float-based; color = `<u>_0.._N` keys.
- New **dedicated ReShade tab** (`TabType.RESHADE`, `icon_screen_effect`) — pulled the ReShade block out
  of the Graphics tab. **Reset** button re-seeds every param to its `.fx` default via `onReshadeApply`.
- Pre-launch editors (container + shortcut) render the same typed controls (color = R/G/B sliders there).
- Device-confirmed: toggle ("Use Limits"), dropdown ("Debug Options"), color picker, collapse, Reset.

### On-demand download catalog (replaces APK bundling)
- Decision: do NOT ship `.fx` in the APK. Host on `The412Banner/winlator-contents`; the pre-launch effect
  picker shows the full catalog GREYED, each row downloads-and-fills-in on tap.
- **Published + LIVE:** `reshade.json` on repo `main` (100 effects) + 100 per-effect `.tzst` as assets on
  release **`reshade-v1`**. License-safe set only (crosire/prod80/luluco250/fubax; AstrayFX excluded for
  license ambiguity, qUINT excluded). Built by `scratchpad/reshade_catalog.py` (include+texture closure;
  prod80 `PD80_NN_` prefixes stripped for clean ids; category-tagged).
- Schema: `{schemaVersion,category,release,mirrorBase,count,effects[{id,name,description,category,author,
  license,url,file_size,file_checksum(MD5),version}]}`. `id` = drop-in folder name; tzst extracts into
  `getExternalFilesDir/ReShade/<id>/`.
- App side building on `feat/reshade-download-catalog` (off `feat/reshade-typed-controls`).

### Credits (for the next stable's release notes)
- vkBasalt engine: **DadSchoorse** (original, our patched .so builds from this) + **Pipetto-crypto**
  (Winlator integration, commit `67b6dad`) + **StevenMXZ** (Winlator-Ludashi). Shader authors:
  crosire/ReShade, prod80, luluco250, Fubaxiusz.

### Still ahead
- Codegen sweep (verify which of the 100 actually compile on vkBasalt → prune/flag catalog).
- Tier-1 hardening: existing-container layer heal, GPU/Mali coverage, Vulkan-only boundary.
- Merge the ReShade stack to main + cut the stable (with credits). Depth effects = STEP 4.

---

## 2026-06-29 — ▶️ RESUME HERE: STEP 3 ReShade effects — BUILT end-to-end, one device test from done

**TL;DR:** In-game ReShade `.fx` effects via the bundled vkBasalt layer. App feature + a patched live-reload
vkBasalt layer are BOTH built and CI-green; integrated build is compiling. **Only the on-hardware device test
remains.** Design doc: `docs/RESHADE_STEP3_PLAN.md`.

### Branches (all pushed to origin)
- `feat/reshade-step3` — app-side feature (5 commits). CI `28401038692` ✅ GREEN.
- `feat/reshade-vkbasalt-build` — patched libvkbasalt.so source/patch/workflow. CI `28401364441` ✅ GREEN.
- **`feat/reshade-integrated`** — `step3` ⊕ `vkbasalt-build` + integration commit (patched .so repacked into
  `extra_libs.tzst` + uniform-key fix). **THIS is the branch to install/test.** Combined CI `28402115564`
  (workflow "CI Build (artifacts only)") — was IN PROGRESS at handoff; check it on resume.

### What was proven / built this session
1. **Spike DEVICE-PROVEN:** hardcoded sepia `.fx` compiled on-device (vkBasalt reshadefx→SPIR-V→Turnip) and
   applied to a live DXVK game (The Saboteur) → screen went sepia. ReShade `.fx` on Adreno = real. Blockers
   #1 (language) + #2 (Adreno compile) empirically dead. (Depth effects still STEP 4.)
2. **Two infra bugs found + fixed:** (a) `extra_libs.tzst` (carries libvkbasalt.so) only extracted on container
   `firstTimeBoot` → existing containers never got the layer (silent no-op). Fixed: extract whenever the .so is
   absent. (b) the SHIPPED libvkbasalt.so has its key-detection compiled out (`isKeyPressedX11`→return false) +
   no uniform-override path → its HOME toggle is dead for ALL inputs and it can't do live sliders. ⇒ any live
   control REQUIRES a rebuilt layer (the X11-Home-inject "free toggle" idea is DEAD — dropped).
3. **App-side Phase 1** (android-app-engineer): `reshade/ReshadeManager.java` (drop-in folder scan +
   `.fx` ui_* param regex reflection, scalar float/int/bool); `Container.java` persists `reshadeEffect`/
   `reshadeParams`; `XServerDisplayActivity.java` extraction-gate fix + `writeVkBasaltConfig()` (merged with CAS
   into one `effects = <effect>:cas` chain) + `applyReshadeLive()` seam; in-game drawer ReShade section
   (`XServerDialogState.kt`/`XServerDrawer.kt`); effect pickers in shortcut + container editors.
   Drop-in folder = `getExternalFilesDir(null)/ReShade/` (one subfolder per effect; copied into guest HOME
   `.config/vkBasalt/effects/<name>/` at launch for host-absolute paths). Gated to DXVK/VKD3D.
4. **Patched vkBasalt** (graphics-vulkan-engineer): `patches/vkbasalt-reshade-livereload.patch` vs upstream
   `DadSchoorse/vkBasalt@4f97f09` (submodule `app/src/main/cpp/vkbasalt`), built via new `build-vkbasalt.yml`
   (meson Android cross, NDK r27c, arm64-v8a/android-24, X11-free). **Part A** = live on/off (Config remembers
   opened path; present-hook mtime-watch re-reads conf → flips `presentEffect` from `enableOnLaunch`; lsfg-mirror,
   no swapchain recreate). **Part B** = live sliders (codegen uniforms-to-spec-constants=FALSE → ui_* uniforms in
   UBO; new `GenericUniform` + `ReshadeEffect::updateUniformsFromConfig()` overrides from conf key
   `"<effect>_<uniform>"` → next-frame memcpy, no recompile). CI-green + binary-symbol-verified.
5. **Integration:** patched .so (stripped 1.85MB) repacked into `extra_libs.tzst`; `formatUniformLine()` aligned
   to emit `<effectKey>_<uniform>` to match the patch's read key.

### ▶️ NEXT (on resume, when home / Wi-Fi back)
1. Check combined CI `28402115564` (branch `feat/reshade-integrated`); if green, grab the APK artifact.
2. **DEVICE TEST on The Saboteur (DXVK)** — the unproven gate (all green/binary-verified, Part A+B untested on
   hardware): install integrated APK → use a **FRESH container** (old `xuser-4` has `libvkbasalt.so.disabled`
   from the spike sepia-cleanup; extraction-heal/new container installs the patched .so) → pick a ReShade effect
   → verify (a) effect renders CORRECTLY [Part B UBO change didn't break render], (b) drawer ReShade on/off
   toggles LIVE [Part A], (c) sliders move the image LIVE [Part B + key syntax]. ⚠️ uniform-override key form is
   the most likely thing to need a tweak — centralized in `formatUniformLine()`.
3. If green on device → tidy (strip note, drop the throwaway `spike/vkbasalt-reshade`), then decide merge to main.
   Live SLIDERS depend entirely on the patched .so working; if Part B misbehaves on device, on/off (Part A) is the
   lower-risk fallback to ship first.

⚠️ Memory file `project_bannerlator_step3_shader_loader_reshade` has the full detail + every commit/run id.

---

## 2026-06-28 (s4) — ▶️ RESUME / CURRENT STATUS: VRR + manual picker built & verified; pacing tweak pending

**Where things stand on the graphics roadmap:**
- **STEP 1 (debanding + NIS): DONE — MERGED to main** (`feat/deband-nis` ff `71e2d27..4565b80`, branch deleted).
  Both device-proven on Vulkan via the new AIO torture cards (banding ramp + scaling combo). Not in a tagged
  release yet (per versioning rule — no stable cut without explicit say-so).
- **STEP 2 (VRR / refresh-rate matching): BUILT + DEVICE-VERIFIED**, branch `feat/vrr-refresh-rate` (off main,
  NOT merged). Took 3 fixes to get the panel to actually move: seamless→ALWAYS (`c29acc0`), capability gating
  (`83da657`), and the big one — window `preferredRefreshRate` was pinning the panel to max and out-voting our
  surface vote (`35dd636`). After that, all 4 states verified on-device (Vulkan, AYANEO 144Hz panel):
  cap+match→60, cap+nomatch→144, uncapped+match→144, incapable→greyed. Clean 60↔144 both directions.
- **Manual refresh-rate picker: BUILT** (`fa77da6`, CI `28333613335` GREEN) — unified 'Refresh rate' drawer
  control (Auto match-FPS + manual snap-to-supported-modes chips, auto-detected via getSupportedRefreshRates;
  Auto greys chips; whole group greys on incapable devices). Editor got a manual FilterChip row. Auto path
  byte-identical (reviewed). DEVICE-TEST OWED.

**Open issue — FPS oscillation (user-observed):** with limiter=60 + Auto-match ON, FPS swings 56↔64 every few
seconds. Two suspects: (1) **AYASpace system refresh control** — the test device is an AYANEO handheld whose
AYASpace overlay has its OWN 'Refresh Rate' control (Auto/144/120/90/60), was pinned to 144; its polling
service likely re-asserts 144 vs our VRR every few seconds (matches the timing). (2) **limiter/VSync beat** —
matching the panel to exactly the cap removes the 144Hz headroom that hid the limiter's pacing jitter (panel
likely 59.94 vs cap 60.0). 

**▶️ NEXT (test plan, before merge):** (1) DIAGNOSTIC — set AYASpace Refresh Rate→Auto, re-test: swing stops =
system contention (config fix, document it), persists = pacing beat. (2) Install build `28333613335`, verify
manual picker (Auto-off+pick 90/120 → dumpsys locks panel to it regardless of cap) + headroom check (manual
120 + cap 60 should be smooth) + auto-path 4/4 regression. (3) If pacing beat confirmed → add **cap-below-
refresh** fix (pace limiter to ~refresh−1 when Auto matching). (4) Then MERGE feat/vrr-refresh-rate (VRR +
manual picker + any pacing fix) to main → then STEP 3.

**Device-driving notes:** measure VRR while game is FOREGROUND (it releases the vote on background); 'go'
handshake + `sleep 12; dumpsys` works. Key greps: activeMode= , setFrameRate=/{10492, the per-layer
`Hz ... Always/OnlySeamless` vote line. Move-cursor-to-touchpoint is ON (tap absolute). Newest device
screenshots in /sdcard/Pictures/Screenshots/.

---

## 2026-06-28 (s4) — 🆕 Manual refresh-rate picker built (unified Auto + manual control)

On top of the verified VRR, added a unified "Refresh rate" drawer control: the match-refresh toggle is now
"Auto (match FPS)" + a chip row of the panel's supported rates (auto-detected via `getSupportedRefreshRates`).
Auto ON → chips greyed (VRR drives it); Auto OFF → pick a rate and the panel locks to it regardless of the FPS
cap; whole group greys on incapable devices. `applyVrr` extended with an additive manual branch (auto path
byte-identical, reviewed). New state manualRefreshRate/supportedRefreshRates/onManualRefreshChange + Container
`manualRefreshRate` extra + resolver + editor FilterChip row. Commit `fa77da6`, CI run `28333613335`.
Device-test owed: Auto-off + pick 90 → panel locks 90 regardless of cap; chips greyed when Auto on; auto path
4/4 regression. Then merge the whole `feat/vrr-refresh-rate` (VRR + manual picker) to main.

---

## 2026-06-28 (s4) — ✅✅ VRR device-test #3: WORKING (+ clear-path verified) — panel drops 144→60 to match the FPS cap

Build `28332650876` (seamless fix `c29acc0` + capability gating `83da657` + window-pin fix `35dd636`). On
Vulkan, FPS cap 60, Match-refresh ON, game foreground: **activeMode 144.00→60.00 Hz** — the panel physically
switched. Override `{10492, 60.00 Hz}`, both layer votes now `60 Hz SeamedAndSeamless` (game surface + window
pref agree). VRR is device-proven end-to-end. The `preferredRefreshRate` lever moves this panel, so both
auto-VRR and a future manual refresh-rate picker will work here.

**Before merge:** verify toggle-off/uncapped returns the panel to 144 (clear path); optional GL/ASR spot-check
(shared code, Vulkan proven). **Next feature (green-lit):** manual refresh-rate picker — one control with
'Auto (match FPS)' + manual snap-to-supported-modes (60/90/120/144 auto-detected via getSupportedModes); Auto
greys the slider; whole control greys on single-mode/pre-A11 devices. Same `preferredRefreshRate` lever.


**Clear-path verified (test #3b):** toggling "Match refresh rate to FPS" OFF returned the panel 144 Hz (activeMode 60→144, both votes restored to 144). So VRR does the full round trip — drops to the cap when on, restores max when off. Also verified the cap-dependency: limiter OFF + match ON (uncapped) → panel returns to 144 (VRR only acts while capping). All 4 states confirmed {ON+match→60, ON+nomatch→144, OFF+match→144, incapable→greyed}. **VRR comprehensively verified and ready to merge.**


---

## 2026-06-28 (s4) — 🐛➡️✅ VRR device-test #2: surface fix confirmed, fixed a 2nd blocker (window pins max refresh)

Re-tested with the seamless fix (`c29acc0`) + capability gating (`83da657`). Our game-surface vote is now correct —
`60.00 Hz Default SeamedAndSeamless` (the force-switch worked; was OnlySeamless). But the panel still held 144
because a 2nd layer voted 144: `XServerDisplayActivity.onCreate` pins `window.preferredRefreshRate = max` (144)
for smooth UI, and that window-level request out-votes the surface vote. **Fix `35dd636`:** new
`applyWindowPreferredRefreshRate(vrrRate)` (called from applyVrr) lowers the window preference to the matched
rate when VRR is capping, restores max otherwise. CI run `28332650876`. Retest: expect activeMode 144→60.
Lesson: an emulator that force-pins preferredRefreshRate to max blocks ANY app VRR vote — keep it in step.

---

## 2026-06-28 (s4) — 🐛➡️✅ VRR device-test #1: found + fixed the "panel won't drop" bug

Tested VRR on-device (AIO, OpenGL renderer, FPS cap 60, Match-refresh ON, game@60). Panel stayed at 144 Hz —
frameRateOverride showed our app un-throttled `{10492, 144}`. Root cause via dumpsys layer line
`60.00 Hz Default OnlySeamless`: our 60 Hz vote WAS placed correctly, but with **seamless-only** strategy, which
a peak-refresh 144 panel ignores. The code bug: `XServerView.applyFrameRateToSurface` only used the 3-arg
`setFrameRate(..., ONLY_IF_SEAMLESS)` when `FRAME_RATE_SEAMLESS_ONLY` was true, else fell through to the 2-arg
overload — which **also defaults to ONLY_IF_SEAMLESS**, so the "force" path was never taken.

**Fix `c29acc0`:** when SDK≥31, pass the strategy explicitly — `ONLY_IF_SEAMLESS` if seamless-only else
`CHANGE_FRAME_RATE_ALWAYS` (force the mode switch). CI run `28331250229`. Retest owed: reinstall, re-measure
(expect override→cap, activeMode drop). Notes: 40 fps is an awkward cap for a 144 panel (144/40=3.6) — use 60;
container was GL, also test Vulkan; if ALWAYS still won't drop it's device display policy, not our code.

**➕ Capability gating (`83da657`, CI `28332020195`):** the "Match refresh rate to FPS" toggle is now **greyed out** on devices that can't do VRR — `XServerView.isDisplayVrrCapable(display)` = SDK>=30 AND >1 distinct refresh rate among supported modes; gated in the in-game drawer (XServerDrawerState.vrrSupported, seeded at launch) + the ContainerDetail editor (probes the default display), with an "Unavailable on this display" hint. Single-mode/60Hz-only + pre-Android-11 → disabled. Build 28332020195 includes BOTH this AND the seamless-only fix `c29acc0`, so it supersedes 28331250229 — install THIS one for the retest. User's 144/120/90/60 panel = capable → toggle stays enabled.

**Same-device test protocol (1-thing-at-a-time):** VRR releases its vote on background (onStop→0), so measure
while the game is foreground — user stays in game, sends "go", switches back; I fire `sleep 12; dumpsys` to
capture with the vote reapplied. Confirm foreground via topResumedActivity.

---

## 2026-06-28 (s4) — 🆕 STEP 2: VRR / refresh-rate matching IMPLEMENTED (branch `feat/vrr-refresh-rate`, device-test owed)

Step 1 (debanding+NIS) merged to main earlier today; started Step 2 = make the panel refresh rate follow the
game FPS via `Surface.setFrameRate` votes (complementary to the FPS limiter: limiter=render rate, VRR=display
rate). One Surface-level vote on the parent SurfaceView covers all 3 host renderers (GL / Vulkan compositor /
SurfaceFlinger-ASR) since SF aggregates frame-rate votes over the layer subtree; existing Vulkan native child-SC
votes left intact.

5 commits on `feat/vrr-refresh-rate` (off main, pushed, NOT merged):
1. `4882473` XServerView.setDisplayFrameRate(float,int) — SDK_INT>=30 guard, picks active holder Surface,
   remembers last rate + re-asserts in surfaceChanged (added a holder callback to glSurfaceView which had none).
2. `4671c26` applyVrr()/reapplyVrr()/resolvedMatchRefreshRate() in XServerDisplayActivity — votes 0 when
   off/uncapped, `cap` normally, `cap×mult` in the lsfg-governs case; wired into applyFpsLimit + onStop(release)
   + onResume(reassert) + drawer onMatchRefreshChange.
3. `dcbefb5` Container `matchRefreshRate` extra (default ON) + shortcut resolver.
4. `2b603e5` drawer "Match refresh rate to FPS" toggle + XServerDrawerState flow.
5. `5ff90db` ContainerDetail editor switch + strings.

Reviewed rate logic + cross-layer contract names (sound; `getFrameGenMultiplier()` confirmed to exist).
CI run `28330068467` ✅GREEN (all flavors compile). No release/tag cut.

**▶️ DEVICE-TEST (user starting now):** `dumpsys SurfaceFlinger | grep -i frameRate` to confirm the panel takes
the vote. Verify: vote = cap when capped, cap×mult under lsfg, clears (0) when toggle-off/uncapped, re-asserts
bg→fg. **PREREQ: turn the FPS limiter ON + set a cap** (VRR only votes when capped; limiter is the existing
pre-feature, VRR just matches the panel to it). Needs a high-refresh panel (dumpsys shows the modes).
**Renderer priority:** Vulkan first (full stack, most likely to land) → OpenGL → SurfaceFlinger/ASR last + most
scrutiny (relies on SF aggregating the parent-Surface vote to the native child SC; if it doesn't land there →
do optional commit 6 = native ASurfaceTransaction_setFrameRate on the game child SC). Everyday use: Vulkan.
Risk: setFrameRate is a HINT (battery-saver may ignore).

---

## 2026-06-28 (s4) — ✅ VULKAN RETEST PASSED on new AIO torture cards → `feat/deband-nis` CLEARED TO MERGE

User built the AIO Graphics Test with the Banding scene + new "Scaling Tests >" sub-page (builder used the
exact scene ids from the brief: scaletest_combo/zoneplate/wedge/grid/checker/edges). Switched the AIO shortcut
to the **Vulkan** renderer (1280x720→1080p upscale) and drove it on-device:
- **Debanding (banding card, dark 0..16/255 ramp):** OFF = ~12 hard stair-step bands; ON = bands dissolved into
  fine dither grain. Max diff exactly 1/255, mean diff ~3x the smooth Space scene. The visual proof we couldn't
  get on Space/Nebula.
- **Scaling modes (combo card, grid patch sharpness std-dev):** none=linear 0.0590 (identical → None≡Linear
  label quirk), sgsr 0.0689, fsr 0.0693, **NIS 0.0757 (sharpest clean upscaler)**, nearest 0.0936 (blocky
  aliasing). All live-switch, all distinct; SGSR/FSR/NIS reconstruct cleanly above Linear.
- Host compositor confirmed Vulkan via drawer layout (CAS/Debanding). NOTE: AIO HUD "Renderer" line mislabels
  (showed "OpenGL" on the combo) — trust the container renderer, not that label.

**BOTH gate halves PASSED on Vulkan → `feat/deband-nis` is cleared to merge (awaiting user go-ahead).** Then
STEP 2 = VRR. Optional cleanup: None≡Linear label + CAS/Sharpen slider-snapping.

---

## 2026-06-28 — 🎨 `feat/deband-nis` FULLY DEVICE-PROVEN (NIS both renderers + Debanding) → MERGE-READY

**Branch `feat/deband-nis` (tip `cc3361f`, off main, PUSHED, NOT merged).** Detail memory =
`project_bannerlator_smooth_sharp_render_roadmap.md` (STEP 1). Device test driven via root bridge on the
SAME device (Adreno 750), AIO graphics test app, renderer = **OpenGL | DXVK**.

**NIS (NVIDIA Image Scaling, upscaler mode 7) — re-confirmed on GL (Space scene 720p→1080p):**
- ✅ **NO Adreno runtime-compile crash** selecting NIS on the GL renderer — the one big open risk (heavy
  unrolled NIS shader) is definitively cleared; scene keeps rendering. (Already proven on Vulkan in s2.)
- ✅ **Perf cost now measured** (was unbenchmarked): **290fps → 200fps (~31%)**, GPU load 72%→~86%. Still smooth.
- ✅ **Quality** (clean frozen-frame montage None/Nearest/Linear/NIS): NIS = crisp edges + detail preserved,
  distinct from blocky Nearest & soft Linear, no artifacts → NVScaler math correct. RMSE vs `none`: NIS 0.54%.
- ✅ **Sharpness slider** present, continuous, seeds 75, registers 0/100. Modulation confirmed qualitatively
  (clean pixel-delta elusive — the AIO Pause releases on a slider drag; first 4.9%/0.6% numbers were motion-contaminated, discarded).

**Debanding (terminal TPDF dither pass) — device-verified on GL Space scene (frozen-frame A/B):**
- ✅ Toggle works (drawer, below HDR); ON reveals "Dither strength" slider (seeds 100).
- ✅ **Max pixel diff = EXACTLY 1/255 (one 8-bit LSB)**, mean ~0.04/255 — textbook dither magnitude.
- ✅ **Mean brightness perfectly preserved** (0.282483→0.282532) — no bias/tint (hallmark of correct dither).
- ✅ Diff footprint = fine uniform noise across gradients (atmosphere/planet/moon), ~zero in black sky/saturated.
- ⚠️ **CAVEAT:** no dramatic "bands→smooth" before/after on Space — its gradients are dark/shallow + broken by
  stars/detail, so little gross banding to dramatize. Dither provably correct at LSB level; Space ≠ a showcase.
  **For a striking visual demo → AIO "Detailed Nebula" scene (next test).**

**🔎 SIDE-FINDING (cleanup candidate, unfixed):** in this build **None ≡ Linear EXACTLY** (RMSE 0) — base "None"
is doing bilinear; base-sampler labels (None/Linear/Nearest) worth a look. Pairs with the known CAS/Sharpen
slider-snapping inconsistency (GL 5-notch vs VK continuous).

**Debanding RE-TESTED on "Detailed Nebula" (s3, GL, frozen-frame A/B):** mechanism re-confirmed (game-area
max diff = exactly 1/255, mean-preserving); positive dither-footprint = fine stipple noise, ~8.8% of pixels in
the glow falloff nudged 1 LSB, densest at quantization boundaries. BUT even hard-amplified on the brightest
smooth ramp, OFF vs ON look identical — Nebula shows NO gross banding to dramatize either. CONCLUSION: AIO
test scenes render gradients clean enough that 8-bit banding is minimal in both Space & Nebula; debanding's
gain is real but sub-perceptual on THIS content (will matter on real games w/ heavy banding — fog/skyboxes/UE).
Build is dither-only on 8-bit chain (precision-bump step deferred). Note: whole-frame RMSE is meaningless here
(drawer changes between shots) — always crop to game area x>900.

**▶️ NEXT — ⛔ MERGE ON HOLD (user decision 2026-06-28):** do NOT merge `feat/deband-nis` yet. All prior
tests ran on AIO Space/Nebula scenes, which don't visibly band and barely separate upscalers (smooth content)
→ only proved debanding/scaling mathematically, not visually. GATE before merge: (1) AIO Graphics Test gets
its Banding scene (already built, commit `881f39e`, 1 past v1.6.0 — needs push+build+install) + new
Scaling-Test scenes (spec'd, not built — briefs in /sdcard/Download/SCALING_TESTS_BUILD_BRIEF.md +
BANDING_SCENE_FINDINGS.md) → (2) install new AIO binary → (3) **retest on the VULKAN renderer** with real
torture content (debanding on the dark 0..16/255 ramp, in-app dither OFF, scaling=None/1:1;
NIS/SGSR/FSR/FSR-Fit on zone-plate/wedge/grid at sub-native render-res). THEN merge if it passes → THEN STEP 2
= VRR. Optional: precision-bump (10-bit/R16F intermediate) would make debanding visibly stronger — revisit
only if a real game shows banding.

---

## 2026-06-28 — 🧩 BIONIC-FG: Track-3 FSR3 Optical Flow built + `.so` delivered for manual injection (NOT device-tested)

**Resume context for the bionic-fg frame-gen shader-pool / model-expansion effort.** Detail memory =
`project_bannerlator_bionic_fg_shader_pool.md`. Fork = `The412Banner/bionic-fg`, clone `/home/claude-user/bionic-fg-fork`.

**WHAT GOT DONE THIS SESSION:**
- ✅ **Track 3 = FSR3 Optical Flow added as runtime model 3.** Branch `feat/fsr3-optical-flow-model`
  (off `b0c2e5c`), commits `d6f4a09`(embed FSR3 OF SPIR-V idx66-69 + restore dropped `IsValidSpirv`) →
  `9f06376`(model-3 dispatch + clamp→3) → `5eb11a7`(GLSL src+docs) → `603d26e`(CI workflow). PUSHED, unmerged.
  Source = FidelityFX-SDK optical-flow passes (MIT), reimplemented subgroup-free GLSL for Turnip/no-DXC.
  15 OF passes → reuse model-0 warp/blend/synth back half. Models 0/1/2 byte-unchanged (additive).
- ✅ **Built `libbionic_fg.so` (arm64-v8a).** NEW self-build workflow in the fork `.github/workflows/build-so.yml`,
  CI run `28323607624` **GREEN** (NDK r26b 26.1.10909125 / android-26, matches app). No external patch needed —
  the single-device anti-deadlock fixes (fork commit `ac2f5c0`) are already an ANCESTOR of Track-3, so the
  source self-contains models 0/1/2/3 + single-device `create(device,…)` + manifest `disable_environment`.
- ✅ **Delivered to device for manual inject:** `/sdcard/Download/libbionic_fg.so` (6.3M, md5 `971e6aaa…`) +
  `/sdcard/Download/VkLayer_BIONIC_framegen.json`. User will inject + test LATER.

**🔑 CRITICAL ARCH FINDING (corrects prior records):** the app does NOT compile bionic-fg in its main NDK
build (no `add_subdirectory(bionic-fg)`); the layer ships as a **prebuilt asset**
`app/src/main/assets/bionic-fg/libbionic_fg.so`, built by the SEPARATE manual `build-bionic-fg.yml`. So the
recorded "model-2 app CI GREEN" only bundled the OLD prebuilt `.so` — **shipped layer = `9136405c` (Jun 21),
pre-model-1/2/3.** Models 1/2/3 + the shader_02 fix were NEVER in any shipped layer until this new `.so`.

**▶️ WHAT TO DO LATER (resume):**
1. **User injects + device-tests the new `.so`** — replace `<imagefs root>/usr/lib/libbionic_fg.so`,
   set `model = 3` in `…/home/<container>/.config/bionic-fg/conf.toml` (UI only writes 0/1), FG enabled.
   ⚠️ **CLOBBER CAVEAT:** `ImageFsInstaller.installBionicFgLayer` re-stages the OLD bundled asset whenever
   on-device size ≠ bundled size → inject right BEFORE launching or it reverts. Also worth testing model 2 (V2).
2. **Triage results:** FSR3 OF is compile-proven only — main risks = PERF (heavy SAD motion search; tunable
   search window `BR`/`SR` in `of3_flow.comp`) + visual correctness. shader_02 fix + model 2 also first-ever on device.
3. **If we want to ship for real** (not just inject): rebuild the bundled asset — either dispatch the app's
   `build-bionic-fg.yml` AFTER refreshing/removing the now-redundant 608-line `patches/bionic-fg-bannerlator-fixes.patch`
   (stale; fixes already in source), OR repoint that workflow to build the fork branch like `build-so.yml` does;
   then commit the new `.so` to `assets/`, bump versionCode (NOT a release per [[feedback_bannerlator_release_versioning_rule]]).
4. **Roadmap Tracks 4 & 5 BLOCKED on toolchains** (no sudo): Track 4 Lossless DXBC→SPIR-V needs `vkd3d-compiler`
   (meson/widl/spirv-headers all missing); Track 5 RIFE/IFRNet needs an `ncnn` build. Revisit when toolchains available.

---

## 2026-06-28 — 🛠️ BUILT: Debanding + NIS upscaler (branch `feat/deband-nis`, CI pending, NOT device-tested)

Implemented Track-1 step 1 from the master plan below. **Branch `feat/deband-nis` off main, 6 commits
`194b7b9`→`cc3e0e7` (+log `a53a226`), PUSHED, NOT merged, NOT device-tested.** Shaders compile clean
(glslangValidator), all cross-layer contracts reviewed+consistent. **CI ✅ GREEN — run `28319416413`
(build-artifacts.yml, all flavors; only a harmless Node-20 deprecation annotation).** First real compile of the
C++/JNI/Java/Kotlin passed. NEXT = on-device A/B (see ⚠️ below), then merge.

**DEBANDING** — terminal TPDF/IGN dither pass (float-only hash, Adreno-safe), `deband.frag`+header,
appended LAST as `FX_DEBAND`, registered in fxOn in BOTH planUpscaleFrame AND recordUpscalePasses
(known "scaling drops chain" bug avoided). `setDeband(bool,int strength)` JNI↔C++↔Java; GL `DebandEffect`
+ dedicated terminal slot in EffectComposer (render() + renderUpscaled()). strength 0..200 → /100 LSBs
(default 100 = ±1/255). Session-live (not persisted).

**NIS** — new upscaler mode int **7**. Faithful NVScaler port from authentic NVIDIA reference (MIT)
into single-pass `nis.frag` (edge map + 6-tap scale/USM + directional filters + CalcLTI + luma recolor);
**exact fp32 coef_scale/coef_usm baked as `const float[384]`** (transcribed programmatically from
NIS_Config.h, NOT hand-typed, first-row verified); fp32 path (no fp16/bitwise); NO 2nd descriptor binding.
Reuses existing sharpness slider. GL `NISEffect` + EffectComposer `case 7`. Engages only when render
res < display (like SGSR mode 3). Drawer: `7 to "NIS"` + mode-7 sharpness in both GL+Vulkan blocks.

**⚠️ Needs device-verification (agent's honest flags):** (1) NIS math not pixel-compared to a reference
NIS — A/B on the high-freq SPACE scene 720p→1080p, GL AND Vulkan. (2) GL NISEffect runtime-compiles a
heavy unrolled NVScaler + 2×384 const arrays on the Adreno GLES driver = the runtime-compile-crash class
the roadmap flags — DEVICE-TEST GL NIS specifically (Vulkan NIS is precompiled SPIR-V, safer). (3) NIS =
37 texture fetches/fragment, perf untested. (4) CI is the first real compile of the non-shader code.
Files: `cpp/winlator/{deband,nis}.frag`+headers+`gen_shaders.sh`, `VulkanRendererContext.{cpp,h}`,
`vulkan_jni.cpp`, `renderer/vulkan/VulkanRenderer.java`, `renderer/EffectComposer.java`,
`renderer/effects/{DebandEffect,NISEffect}.java`, `ui/XServerDrawer.kt`, `ui/XServerDialogState.kt`,
`XServerDisplayActivity.java`.

---

## 2026-06-28 — 🗺️ MASTER PLAN: Graphics smoothness/sharpness roadmap + App theming/icons

> **STATUS: RESEARCH + RECON + 1 HTML MOCKUP + CODE-GROUNDED PLANS ONLY. NOTHING CODED, NOTHING
> COMMITTED, NOTHING DEVICE-TESTED.** This entry consolidates a full session of exploration into one
> plan. Deep detail (every file:line touchpoint) lives in the two memory files:
> `project_bannerlator_smooth_sharp_render_roadmap.md` + `project_bannerlator_theming_icons.md`.

Two parallel tracks scoped this session. Recommended first build = **debanding + NIS** (Track 1, small/visible/low-risk).

---

### TRACK 1 — SMOOTHNESS + SHARPNESS RENDERING

**User goal:** keep games EXTREMELY SMOOTH, NO FPS loss (ideally gain), + clarity/sharpness.

**Core principle (the answer):** it's a STACK, not one effect →
**render a bit lower → spatial upscaler (sharp) → VRR refresh-match (smooth) → debanding (clarity).**
That gains real FPS, cuts heat/power, adds sharpness, zero added latency.
**Myth busted:** frame-gen does NOT give free FPS — it inflates the HUD number while costing GPU + latency
(a perceived-smoothness layer, optional cherry-on-top for single-player base≥45fps).

**Recommended build order:**
1. **Debanding + NIS** ← START HERE (ready, ~6 commits, low-risk)
2. **VRR / `setFrameRate`** (refresh-rate matching; biggest smoothness-per-effort; SurfaceFlinger path best home)
3. **Curated shader-loader platform** (force-multiplier; compile `.spv` OFFLINE in CI = Adreno-crash-safe; permissive-licensed shaders only)
4. **Moonshot: DXVK depth → true SGSR2 on TAA/FSR2 games** (XL, staged, multi-repo)
5. Keep frame-gen (`bionic-fg`) gated/off-by-default, labeled as a latency trade.
**DROP:** BFI, Anime4K-CNN, pixel-art scalers, heavy CRT (royale), NPU/AI super-res — each costs FPS or doesn't fit.

**Scorecard (vs goal):** SGSR1/NIS/FSR1 ★★★★★ · CAS / shader-loader-platform ★★★★ · 3D-LUT / panel-calibration / estimated-MV-temporal ★★★ · Anime4K(CNN heavy) / pixel-art / adaptive-sharpen(already have CAS+RCAS) / heavy-CRT ★★ · BFI ★½ skip.

**READY-TO-BUILD PLAN — Debanding + NIS (GL + native Vulkan; ASR path runs neither chain):**
Shared plumbing: Vk post shaders `cpp/winlator/*.frag` + committed `*_frag.h` SPIR-V (gen via
`glslangValidator -V x.frag --vn x_code -o x_frag.h`, NO CI compile step). `VulkanRendererContext.cpp`
`createPostPipelines`~:552 / `recordUpscalePasses`:1074 / `planUpscaleFrame`:1235 / locked effect-chain
:1204-1229 / SINGLE-sampler `createDSLayout`:402-407 (don't add a 2nd binding) / PC range 88B.
**Whole chain = R8G8B8A8_UNORM 8-bit** end-to-end. GL = `renderer/EffectComposer.java` + `renderer/effects/*.java`.
Drawer `ui/XServerDrawer.kt` (options :772-775, sharpness slider :815-838). Effects SESSION-LIVE (not persisted).
⚠️known bug: an effect silently no-ops under scaling unless added to fxOn in BOTH planUpscaleFrame:1250 AND recordUpscalePasses:1087.
- **Debanding:** new `deband.frag` = terminal dither (IGN/TPDF, float-only hash, ~1 LSB, display space, no texture taps, Adreno-safe). Last effect-chain entry `FX_DEBAND` (always→swapchain). Vk pipeline + push-const + `setDeband` JNI/Java. GL `DebandEffect` + dedicated TERMINAL slot in EffectComposer (GL has no fixed order). Toggle + optional strength slider. Optional SEPARATE gated commit: bump offscreenFmt→A2B10G10R10/R16F (then off↔swap cross-bind needs split pipelines) — ship dither-only first.
- **NIS:** upscaler mode int=7. new `nis.frag` single-pass NVScaler, BAKE coef tables as `const float[]` in shader (no 2nd descriptor binding; fp32 not fp16). Reuse sharpness slider. Vk pipeline + planUpscaleFrame mode-7 branch (like SGSR mode3) + recordUpscalePasses single-pass; no new JNI. GL `NISEffect` + setUpscaler `case 7`. Drawer add `7 to "NIS"`. MIT ©NVIDIA.
- **Commits:** 1)shaders+headers+gen_shaders.sh 2)NIS-Vk 3)deband-Vk+JNI 4)NIS-GL 5)deband-GL 6)drawer+wiring 7)opt fmt-bump 8)opt persist. **Device-test SPACE scene 720p→1080p, GL AND Vulkan, A/B, confirm no FPS drop + composes with upscalers.**

**STAGED MOONSHOT — SGSR2 (XL, MULTI-REPO):** VERDICT — shipping a patched DXVK is mechanically fine
(we build/ship own DXVK `.tzst` in `assets/dxwrapper/`, extract `XServerDisplayActivity.java:2582`). REAL WALL =
guest→host transport: today only 1 buffer (color) crosses via DRI3 1-AHB=1-FD (`DRI3Extension.java:141`
`modifiers==1255`); the SENDER of extra buffers lives in the guest Wine/WSI build (NOT this repo → wine-compat +
guest build). PREREQ A.0: add a sub-1.0 internal-res lever (renderScale only supersamples ≥1.0 today). **Stage A
depth** (med, useful alone → unlocks DoF/SSAO): patch DXVK to export chosen depth as **R32F** AHB (sidesteps Adreno
depth-AHB limits), new DRI3 `modifiers==1256`, depth import variant, prove via depth-grayscale DEBUG pass. **Stage B
SGSR2** on TAA/FSR2 games (they give MV+jitter free): new 2-pass-FS shaders, persistent history buffer, 4-binding
descriptor for SGSR2 only, per-title jitter profile, auto-fallback SGSR2→SGSR1→passthrough, Vulkan-compositor-only
(ASR can't). **Stage C generic = research wall** (jitter can't be generically injected) — spike only.
**Smallest prototype:** 1 FSR2/TAA game → DXVK export depth-only R32F → accept 1256 → grayscale debug overlay;
if frame-aligned depth correct, transport PROVEN, rest is shader math; else STOP.

**SurfaceFlinger/ASR renderer (the 3rd host renderer) findings:** it hands the guest buffer STRAIGHT to the system
compositor — NO programmable pass, so the upscaler/effect shaders CAN'T live there (by design = its speed/battery win).
What it CAN uniquely add: `setFrameRate`/VRR (best home for #2 above), present-fence timing into the HUD, scaling/aspect
geometry modes, true HDR10 passthrough (speculative), layer alpha/damage. Already wired: setBuffer/geometry/visibility/zorder.

---

### TRACK 2 — APP THEMING + BUTTON ICONS

**Deliverable in hand:** interactive HTML preview (live theme switch, dark/light, accent slider, typography
before/after, 4 screens with icon fixes, offline-safe inline SVG) at device
`/sdcard/Download/bannerlator_theme_icons_preview.html` (+ scratchpad + `~/Downloads/`).

**Finding:** the app ALREADY has a theme engine (`ui/theme/Theme.kt`/`ThemePreset.kt` = 8 presets + HSV picker
`AppearanceScreen.kt`) → this is POLISH not a rebuild. Brand accent `#0055FF` (`Color.kt:8`).
**3 weaknesses:** light mode is DEAD CODE (`toLightColorScheme` exists, `_isDarkMode` hardcoded true, no toggle);
no Material You; ~730 hardcoded color literals bypass the theme (custom accent only paints PART of app until centralized).

**Proposals:**
- 3 new themes (live in the HTML): **★Midnight Cobalt** (rec, evolves brand blue; primary `#2F6DFF`/accent `#6FA8FF`/bg `#0E1117`) · **Phosphor Terminal** (retro CRT amber+green, dark-only) · **Carbon & Ember** (graphite+orange `#FF7A33`). Rec: ship Midnight Cobalt default + keep AMOLED + add Phosphor preset.
- **Typography ramp** (high ROI, ~10 lines `Theme.kt`): app forces weight 600 on ALL text → give head700/body400/label500.
- Un-disable light mode (setter+toggle); optional Material You preset API31+.
- **Icon gaps:** store detail pages (`store/{Steam,Epic,Gog,Amazon}GameDetailActivity.kt`) are TEXT-ONLY buttons → add `Icons.Filled.{PlayArrow,Download,Update,Delete,InsertDriveFile,CloudUpload}`; text "←"→`ArrowBack`; Epic "✓ Installed"→`CheckCircle`; game-card placeholder `OpenInNew`→`SportsEsports`; magnifier "✕"→`Close`. `material-icons-extended` already a dep. **Highest-ROI = store action-button icons (pure additive).**

**3-bucket app-wide reach verdict:** (a) AUTO once colors centralized = all out-of-game Compose UI + in-game magnifier;
(b) small recolor fix = in-game DRAWER `XServerDrawer.kt` (wrapped in theme, inherits fonts, but ~40 hardcoded colors → stays blue under a custom accent);
(c) feed accent MANUALLY = FPS/perf HUD (`widget/FrameRating*.java`, `PerfHudView.java`), on-screen controls (`InputControlsView.java`), legacy XML editors — via existing bridge `AppThemeState.getCurrentAccentArgb()`.

**Theming sequencing:** 1) typography ramp 2) store-button icons + back-arrows 3) centralize ~730 colors per-screen 4) Midnight Cobalt + Phosphor 5) light-mode toggle + optional Material You.

---

### NEXT MOVE
Start coding **Track 1 → debanding + NIS** (commits 1-6, CI-green, then user device-tests on the SPACE scene before SGSR2 spike). Everything above is durable in the two memory files; this log entry is the master index.

---

## 2026-06-27 — 🏷️ 2.0 STABLE RELEASE CUT

Merged the #18 turnip-ICD branch to main, bumped to **2.0 (versionCode 32)**, rewrote the README
"What's New in 2.0" + feature sections, and cut the **2.0 stable release** (run `28309799973`,
prerelease:false + make_latest:true → now the Latest the in-app updater offers; 3 flavor APKs +
update.json attached). Closed PR #24 (not merged).

**Everything in 2.0 since 1.9.2:** OpenGL renderer upscaler parity (SGSR/FSR/Sharpen) · sharpness
sliders retuned (0=off→100=max, SGSR doubled, Sharpen 5-stop snap, inverted-CAS fix) · OpenGL
filter-mode + glBlitFramebuffer plumbing (P4) · **game + container card redesign (#19)** ·
**auto-close session on game exit** · magnifier fix on Vulkan (#22) · FEXCore "Performance (TSO)"
preset (#20) · **Android-10 direct-ICD turnip driver (#18)**.

README/release note imageFS-reinstall reminder added (new turnip driver lives in imageFS).
Pinged issue #18 reporter (SD845/A10) to verify the new `turnip-26.1.0` driver — issue left open
pending their confirmation (no A10 device locally).

---

## 2026-06-27 — #18 direct-ICD turnip path for Android <11 (built, CI-green, awaiting reporter A10 test)

Diagnosis: the reporter's A10/SD845 problem is the driver LOADING MECHANISM — Bannerlator loads
turnip via adrenotools (linkernsbypass hook, needs ~A11+); Winlator loads it as a plain system
Vulkan ICD (works on any Android). Also closed PR #24 (strlcpy/snprintf — original code already
safe).

Branch `feat/turnip-icd-direct-android10` off main (`f43a319`), commits `60038cf` + `971c415`,
CI `28309292459` ✅ green, **NOT merged, NOT device-tested** (no A10 hardware here). New driver
option `turnip-26.1.0` (Winlator's Mesa Turnip 26.1.0, ICD format) that installs the freedreno
ICD + .so into the guest, points `VK_ICD_FILENAMES` at it, and **skips the adrenotools env**
entirely — bypassing the A11+ hook. Picker filter special-cased to gate on `isAdrenoGPU()` (the
normal adrenotools probe is exactly what fails on A10). Adrenotools path untouched; default still
turnip-sdk36. Top residual risk: host-vs-guest path assumption (if proot-remapped, library_path
would need a guest path). NEXT: reporter verifies a CI build on their SD845 → then merge.

---

## 2026-06-27 — Implement open issues #22 (magnifier) + #20 (FEX Performance+TSO preset)

gl-upscaler-parity merged to main (`6d5f75b`). Branch `fix/issues-22-magnifier-20-fextso` off
main, CI build `28308479676` ✅ green. **User device-confirmed both work → MERGED to main (ff
`6d5f75b..ecb8646`, branch deleted, no tag); GitHub auto-closed #22 and #20.**

**#22 magnifier (`d7a736e`):** `showMagnifierOverlay()` cast the renderer to `GLRenderer` and
no-op'd the zoom callback for anything else → on the default Vulkan renderer the overlay opened
stuck at 100% with dead +/- buttons. Now uses the `HostRenderer` interface (get/setMagnifierZoom
implemented by all 3 renderers; Vulkan applies it live via `updateTransform`). The other
GLRenderer cast in `showScreenEffectsDialog` is correct (effects are GL-only) — left as-is.

**#20 FEX "Performance (TSO)" preset (`55fb879`):** fetched the issue screenshot — the reporter's
preset is Performance with **only `FEX_TSOENABLED=1`** (vector/halfbarrier/memcpy TSO stay off,
x87-reduced + multiblock on), the lightweight single-TSO-flag variant. Added `PERFORMANCE_TSO`
to `FEXCorePreset` + a `getEnvVars` block + `getPresets` entry + `performance_tso` string.
Additive, no DB migration; auto-appears in the spinner and Compose container/shortcut editors.

NEXT: CI green → device-test (#22 magnifier on a **Vulkan** container; #20 pick preset + launch a
TSO game) → merge to main.

---

## 2026-06-27 — Open-issue triage + scopes (#22 magnifier, #20 FEX TSO preset) — QUEUED

Scoped while the gl-upscaler-parity slider CI built. To be implemented on a **fresh branch off
main AFTER the gl-parity sliders device-test + merge** (user: "after we device test we will
tackle them both").

**#22 — magnifier doesn't work / zoom — ROOT CAUSE FOUND (high confidence):**
`XServerDisplayActivity.showMagnifierOverlay()` (~line 3339) casts the renderer to
**GLRenderer only** and the zoom callback early-returns when it's not GL. The **default
renderer is Vulkan**, so the Magnifier overlay opens, shows 100%, and the +/− buttons are dead.
Confirmed clean fix: `HostRenderer` interface already declares `get/setMagnifierZoom` (all 3
renderers implement it) and `VulkanRenderer.setMagnifierZoom` calls `updateTransform()` → zoom
applies live. **Fix = use the `HostRenderer` reference instead of the GLRenderer cast** (~3
lines, 1 file). Optional: `ASurfaceRenderer.setMagnifierZoom` lacks a redraw trigger (add
`updateScene()`). Device-test on Vulkan (can fold into the slider session).

**#20 — Add FEX "Performance + TSO" preset:** the built-in PERFORMANCE preset sets
`FEX_TSOENABLED=0`; many games need TSO. **Fix = add a new "Performance (TSO)" built-in preset**
(Performance base + the 4 TSO flags on: TSOENABLED/VECTORTSOENABLED/MEMCPYSETTSOENABLED/
HALFBARRIERTSOENABLED=1, X87REDUCEDPRECISION=1, MULTIBLOCK=1) across `FEXCorePreset.java` +
`FEXCorePresetManager.java` (getEnvVars + getPresets) + `strings.xml`. No DB migration (presets
stored by id). Verify exact flags vs the reporter's screenshot.

**Also noted:** #18 (bundle brunodev Winlator turnip 26.1.0 for A10/SD845 — packaging easy but
adrenotools load may need A11; needs an A10 device) and PR #24 (bounded strlcpy/snprintf in
android_sysvshm.c — review + merge candidate).

---

## 2026-06-27 — GL upscaler parity: device-test, inverted-slider fix, sharpness-range tuning

**Branch:** `feat/gl-upscaler-parity` (off main `ec3bcb0`). Phase 1 (SGSR/FSR/Sharpen on the
OpenGL EffectComposer + drawer Scaling-mode picker, commits `efd5f4f`→`327ab9d`) was already
CI-green (`28306036455`). This session = device-test + fixes. NOT merged.

**Device test (build 1.9.2 vc31, AIO-Graphics-Test-32bit = OpenGL + 1280x720 on 1080p panel,
DX11 SPACE scene, frozen-frame A/B via drawer Pause):** GL parity LIVE — all 6 scaling chips
produce DISTINCT output on the GL renderer (frozen-frame re-upscale works). RMSE vs None:
Linear 0% (≡None ⇒ default sampling IS bilinear) · Nearest 1.51% (blocky) · Sharpen 0.36% ·
SGSR 0.68% · FSR 0.79% (crispest). Cursor stays crisp under Nearest = PASS (host cursor
exempt from point-scale). Zoomed montages confirm visual distinctness.

**🐛 Bug found + fixed (`52c7092`):** the upscale "Sharpness" slider was INVERTED for the
Sharpen mode (AMD CAS) — raising it SOFTENED. Root cause = `FSREffect`'s level scale is
inverted (level 1 = CAS sharpness 0.90 = sharpest, level 5 = 0.12 = softest) but both CAS
call sites mapped slider straight onto level. Fix: `EffectComposer.buildPickerCas():339`
`level=(1-upscaleSharpness)*4+1` + `XServerDisplayActivity.onSgsrUpdate:2088`
`level=(100-sharpness)/25+1`. SGSR (EdgeSharpness=1+s*1.333) and FSR RCAS (stops=1-s) were
already correct → untouched. CI `28307366153` triggered then cancelled (superseded below).

**✅ Slider-effectiveness tuning DONE + committed (graphics-vulkan-engineer, GL + Vulkan):**
every sharpness slider now spans **0 = nothing (neutral, no sharpening; upscale still runs)
→ 100 = max**, ZERO shader recompiles (all host-side push-constant / pass-gating math).
Final effective values, slider 0/50/100:
- GL SGSR EdgeSharpness **1.0 / 2.33 / 3.67** (`1.0+s*2.666` — span doubled, neutral floor 1.0)
- GL FSR RCAS lobe scale **0.0 / 0.5 / 1.0** (`clamp(sharpness,0,1)` — 0 = true passthrough)
- GL Sharpen mode 6 (snapped {0,25,50,75,100}) **OFF / CAS 0.50 / CAS 0.90** (0 = no CAS pass)
- GL "Sharpen (CAS)" toggle (snapped) **OFF / 0.50 / 0.90**
- Vulkan SGSR edge **0.5 / 2.5 / 4.5** (`0.5+s01*4.0` — span doubled)
- Vulkan FSR/Sharpen RCAS con.x **0.0 / 0.5 / 1.0** (`upscaleSharpness01` linear, 0 = passthrough)
- Vulkan CAS toggle **OFF / 0.5 / 1.0** (`casOn = casEnabled && casSharpness>0`)
Snap = `XServerDrawer.kt` IntSlider `steps` override (5 stops only in mode 6 + always for the
Sharpen(CAS) toggle; SGSR/FSR/Vulkan-Sharpen stay continuous). No over-drive past RCAS/CAS
ceiling → no ringing. Commits `beebb17` (GL SGSR-double + FSR 0=neutral) · `fac47ed` (GL Sharpen
5-stop snap + 0=OFF) · `0718c39` (Vulkan mirror). CI build **`28307792454`** ✅ green all 3 flavors.

**✅ GL device-test PASSED (AIO space scene, OpenGL|DXVK Adreno 750, live drawer-open method):**
Sharpen slider **snaps to 0/25/50/75/100** (tap ~38% → snapped to 50). FSR slider **0 = true
passthrough** (crop png 218 KB, softer than None's 245 KB, no sharpening) → **100 = strongly
sharp** (420 KB, +92%). SGSR **0→100 = +18% png**, montage shows visibly crisper limb/coastlines
(doubled range). 0 = neutral confirmed. Vulkan mirror = code-verified (shared host logic) but
not device-tested this session (AIO shortcut is OpenGL). Note for next time: this AIO test
**exits the scene on BACK while paused**, so the frozen method was abandoned — open the drawer
once via BACK while running, then switch chips live without pause/BACK. NEXT: merge gl-parity to main.

**❌ Dropped per user:** a persisted per-shortcut "upscaling on/off" toggle. Clarified that
720p→1080p plain stretch is ~free (final-blit sampler); only opt-in SGSR/FSR cost GPU and are
already default-None + session-live. User said "leave the upscaling alone."

---

## 2026-06-27 — P4 "Lean GL path" (render-upgrades roadmap, final phase) — steps 1+3

**Status:** branch `feat/p4-lean-gl-1-3` off main `35fd80d` (pushed). 2 commits. CI
`28304623016` (`build-artifacts.yml`, all 3 flavors) running. Baseline main build
`28304103719` ✅ green (known-good fallback). NOT merged; device-test pending.

**Context:** Vulkan render-upgrades (P1 SGSR/FSR framework, P1b/1c CAS/HDR/sliders, P2 the
5 GL effects → Vulkan, + native-mutex) are all DONE + on main. P3 (ReShade `.fx` engine)
DROPPED. **P4 = the last phase**, and it targets the *Java GL renderer only*.

**Recon (graphics-vulkan-engineer, read-only):** the roadmap's framing — "reduce
GLSurfaceView overhead" — is mostly the wrong target. In the default config (DRI3 on) the
game frame is **already zero-copy** on GL via AHardwareBuffer→EGLImageKHR (`GPUImage`), so
there's no per-frame CPU upload to kill. EGL context is already GLES3 (`XServerView.java:89`)
despite `GLES20.*` calls → GLES3 APIs available today. Real gaps: `setFilterMode` is a dead
no-op on GL; no low-res→cheap-upscale path; full-frame `glTexSubImage2D` only on the
SHM/DRI3-off/cursor path; effect chain renders base into a full-res FBO even for 1 effect.
GameHub's `libxserver.so` is proprietary but its "direct scanout" rides the same AHB/EGLImage
primitives we already own → P4 = clean-room reimpl with in-tree CAS/SGSR/FSR, nothing
license-blocked. Ladder = 5 rungs; this batch = the low-risk 1–3.

**Implemented (this batch):**
- **Step 1 — `f7e0670` setFilterMode real on GL.** `GLRenderer.java`: new `windowTexFilter`
  field; `setFilterMode(int)` maps `2→GL_NEAREST else→GL_LINEAR` (matches Vulkan convention),
  applied in `renderDrawable` ONLY when `material == windowMaterial` so the **cursor stays
  LINEAR**. Launch hook `XServerDisplayActivity.java:1785`
  `renderer.setFilterMode(container.getRendererFilterMode())` gated `instanceof GLRenderer`
  (was dead/unreachable before — nothing called `HostRenderer.setFilterMode`; Vulkan drives
  filtering via `setUpscaler`). `getRendererFilterMode()` verified to exist (`Container.java:497`).
- **Step 2 — PBO async upload — ❌ DROPPED.** First CI failed to compile
  (`Texture.java:153 int cannot be converted to Buffer`): this project's compileSdk exposes
  only the `Buffer` overload of `GLES30.glTexSubImage2D`, not the int-offset (PBO) variant →
  a PBO can't feed the texture, so no benefit (`glTexImage2D`-with-offset would realloc every
  frame, slower than the sync path). Cleanly reverted (`Texture.java` diff vs main now empty).
  Deferred, not delivered. Lesson: don't assume Android GLES30 int-offset texSubImage exists.
- **Step 3 — `c085dd9` glBlitFramebuffer for trivial copy stage.** `EffectComposer.render()`:
  null-material (pure-copy) pass now goes through `blitReadBufferTo` (GLES30
  `glBlitFramebuffer`, COLOR_BUFFER_BIT, LINEAR, scissor disabled) instead of program-bind +
  textured quad. ALL real shader passes (Color/FXAA/Toon/CRT/NTSC/CAS) + the source-less
  `drawFrame` scene render are UNCHANGED — only the degenerate null-material branch changed
  (which also fixes an old clear-then-draw-nothing→black bug). Bit-identical with-effects output.

**Deferred:** Step 4 (low-res render-target → cheap upscale = the actual "GameHub feel" win,
higher risk: letterbox/scissor + keep cursor full-res) and Step 5 (drop GLSurfaceView for an
owned EGL/SurfaceView present thread). Step 2 (async CPU-path upload) would need a non-PBO
mechanism given this SDK's bindings.

**Confidence:** step 1 = would-work/needs-device-proof (sampler state only, no fast-path
regression); step 3 = would-work (bit-identical for shipped effects). No SDK in the agent env
→ correctness proven by CI compile + device test, not local build.

**Next:** CI green → device-test (GL renderer + Filter toggle nearest/linear; confirm cursor
stays sharp; effects still composite) → merge to main. No tag/release (artifacts only).

---

## 2026-06-27 — #19 follow-up: Layout L wired + A/L card chooser

**Status:** branch `fix/shortcut-name-overflow` (pushed). Commit `324bb4a` (L + chooser) +
audio-drop follow-up edit. Compile CI `28300618235` (`main.yml`) running on `324bb4a`;
audio-drop fix needs a re-run after its commit. NOT merged.

**Ask:** user said "wire up L so I can choose between the two" — make the list-view card style
user-selectable between the existing layout A and the chosen layout L.

**Implementation:**
- `ShortcutsViewModel.kt`: persisted `useLayoutL` pref (`shortcuts_prefs` / `list_card_layout_l`,
  default `false` = A) mirroring `isGridView`; `setUseLayoutL()`.
- `ShortcutsScreen.kt`:
  - Top-bar **A/L toggle** — an "A"/"L" Text `IconButton`, shown only in list view (hidden in
    grid). Keyed the top-bar-actions `LaunchedEffect` on `(isGridView, useLayoutL)` so the toggle
    reflects live state (was `Unit` → captured stale values; the existing grid icon only updated
    via content recompose).
  - List branch now picks `ShortcutItemLayoutL` vs `ShortcutItem` on `useLayoutL`.
  - New `ShortcutItemLayoutL`: same 48×64 poster cover; subtitle = `container · resolution`;
    PRIMARY `FlowRow` of bright `CompChip`s — renderer (`ChipRendColor`), DXVK, frame-gen
    (`ChipFgColor`, "Bionic-FG" / "LSFG-VK"); SECONDARY `FlowRow` of `SecondarySpec` (7dp colour
    dot + dimmed `OnSurfaceVariant` 10sp label) — driver, VKD3D, backend (`ChipCpuColor`).
  - Renderer / frame-gen / backend resolved via `shortcut.getExtra(key, container.getX())`
    (`getRenderer` / `getFrameGenEngine` / `getEmulator`); backend name via
    `R.array.emulator_entries` + `StringUtils.parseIdentifier`.
  - **Refactor:** extracted the shared ⋮ menu into `ShortcutOverflowButton` (own `menuExpanded`),
    now used by BOTH A and L so the menu can't drift.
- **Audio dropped** from L's secondary (follow-up edit) to match `docs/shortcut_card_L_final.html`
  — lets driver · VKD3D · backend sit on one row. First commit `324bb4a` wrongly included it.
- **Deferred:** backend preset suffix ("FEXCore · TSO" / "Box64 · Perf" in the mockup) — needs the
  async `Box64PresetManager` / `FEXCorePresetManager`, too heavy to resolve per list-card; backend
  shows the emulator name only for now.

**Next:** commit audio-drop fix → CI green → device-test (both layouts render + pref persists
across the toggle and app restart) → merge → close #19. ⚠️ save-before-device-test rule.

---

## 2026-06-27 — Issue #19 "Name of games is empty" + game-card redesign

**Status:** branch `fix/shortcut-name-overflow` (pushed, NOT merged). Layout A build CI `28299970224` running.

**Root cause (#19):** in `ShortcutsScreen.kt` list-mode `ShortcutItem`, the right-aligned
resolution+DXVK/VKD3D info column had no width bound. In a `Row`, unweighted children are
measured before the weighted name column gets the remainder, so a long version string (e.g.
a DXVK/VKD3D nightly with a commit id in its name) grew unbounded → collapsed the weighted
name column to 0 width ("name is empty") AND pushed the trailing ⋮ overflow menu off-screen.

**Iterations on the branch:**
- `1dd8d4d` interim: capped info column `widthIn(max=120dp)` + ellipsize. Device-tested by user
  → fixed the blank name but now TRUNCATED the component versions (not acceptable).
- `e496040` interim: split DXVK/VKD3D onto own lines, wrap to 2 lines, cap 140dp.
- `b598adb` (current tip) **Layout A redesign**: replaced the info column with a 3:4 poster
  cover (reuses `shortcut.icon`, same bitmap the grid uses) + name/container + graphics
  components as colour-coded chips on a wrapping `FlowRow` (`CompChip` helper + 4 chip colors,
  `ExperimentalLayoutApi`). Long version strings wrap to another chip line / grow the row
  taller instead of clipping. CI building (`28299970224`).

**Design exploration (HTML mockups, rendered via headless chromium, saved to /sdcard/Download):**
- `docs/shortcut_card_layouts.html` — 6 layouts A–F (poster, square-icon+chips, hero banner,
  16:9 spec grid, two-tier stat strip, current-for-comparison).
- `docs/shortcut_card_layouts_dense.html` — 6 denser layouts G–L that ALSO show renderer
  (OpenGL/Vulkan/SurfaceFlinger), frame-gen (off/bionic/lsfg), audio (ALSA/PulseAudio) and
  x86 backend (FEXCore/Box64/wowbox64 + box64/FEX preset). Real values pulled from arrays.xml
  + ShortcutsScreen.
- `docs/shortcut_card_L_final.html` — **user likes layout L** (bright primary chips
  renderer·DXVK·frame-gen + muted secondary line driver·VKD3D·backend), **audio dropped per
  user so the secondary line fits one row / card is shorter**. Resolution moves into subtitle.

**NEXT:** user wants to SEE the layout A build on device first, but LIKES L → likely wire L
(swap the FlowRow chip cloud for L's two-tier primary/secondary) on the same branch. L needs
3 more shortcut extras resolved in ShortcutItem: `renderer`, `frameGenEngine`, `emulator`
(+ box64Preset/fexcorePreset). Keys all confirmed present.

---

## (legacy) Star-Compose

**Repo:** https://github.com/The412Banner/star-compose (main branch)  
**Mirror:** https://github.com/kalteatz24/winlator-test (star-compose branch)  
**Local:** `/data/data/com.termux/files/home/winlator-test`  
**Always push to both remotes after every commit:**
```
git push star-compose star-compose:main
git push kalteatz24 star-compose:star-compose
```
**Then trigger CI:**
```
gh workflow run "Any branch compilation." --repo The412Banner/star-compose --ref main
```

---

## ⚠️ WORKFLOW RULE — save before device tests / heavy jobs

We **device-test on the same physical device that hosts the working session** (PRoot/Termux + the app under
test are on one device). A device test, app install, screenshot/diff batch, or large agent/workflow can OOM and
crash the session, losing any un-saved context. **Always flush memory + this progress log + commit BEFORE running
a device test or heavy/memory-load job, and update continuously — not just at the end.** Memory + this log are the
durable checkpoint; the live session is volatile.

---

## 2026-06-27 (latest) — 🚀 Release 1.9.2 (stable patch)

Opacity fix device-confirmed working by user → cut **1.9.2** stable patch. Bumped `app/build.gradle`
versionCode 30→31, versionName 1.9.1→1.9.2 (`84b6bc1`), pushed main, dispatched `release.yml`
(run `28295471682`) with tag `1.9.2`, title `Bannerlator 1.9.2`, make_prerelease=`false`
(→ prerelease:false + make_latest:true). Workflow builds all 3 release APKs + generates/attaches
update.json (vc31) so the in-app updater offers it to stable users.

**What 1.9.2 ships (since 1.9.1):** full Vulkan effect suite — P1 SGSR + FSR1 upscalers (`5f5a4a0`),
P1b sharpen + render-scale, P1c CAS + fake-HDR + sharpness sliders, P2 FXAA/Toon/Color/CRT/NTSC screen
effects; Native-Rendering ↔ presets mutex; Linear default scaling mode; on-screen-controls overlay-opacity
drop-shadow fix (`1d9439e`). Plain numeric tag = stable per the versioning hard rule (patch X.Y.Z allowed
on explicit user request).

**✅ PUBLISHED — release run `28295879200` succeeded.** 1.9.2 is **Latest**, prerelease=false, all 3 flavor
APKs + `update.json` (vc31) attached → in-app updater offers the OTA to 1.9.1 (vc30) installs. Release body
rewritten to the polished 1.9.1-style layout (logo → ✨What's New → 📥Downloads → 🙏Credits → changelog)
with a full **graphics credits table**: SGSR ([SnapdragonStudios/snapdragon-gsr](https://github.com/SnapdragonStudios/snapdragon-gsr), BSD-3),
FSR/Sharpen ([GPUOpen-Effects/FidelityFX-FSR](https://github.com/GPUOpen-Effects/FidelityFX-FSR), MIT),
CAS ([GPUOpen-Effects/FidelityFX-CAS](https://github.com/GPUOpen-Effects/FidelityFX-CAS), MIT),
FSR-Fit/compositor blueprint ([utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative), GPL-3.0 — approach reimplemented, not copied),
and HDR/FXAA/Toon/CRT/NTSC/Color (upstream Winlator-Ludashi GLES2 effects ported to Vulkan). Attributions
sourced from the bundled shader headers at `app/src/main/cpp/winlator/*.frag`, which retain their upstream
license text. ⚠️First run `28295471682` FAILED at the update.json step — `release_notes` had literal
double-quotes that broke the bash `NOTES="..."` assignment (exit 127); re-ran with shell-safe plain notes.

**📝 Release-copy accuracy pass (post-publish body edits, no rebuild).** User flagged the marketing line
("only Winlator fork with both real spatial upscalers and a complete post-processing chain on Vulkan —
previously all OpenGL-only"). Verified in source against the other forks and corrected it:
- **GameNative** = FSR1-only on the Vulkan compositor (the blueprint we built on).
- **WinNative** (`/home/claude-user/winnative`) = **SGSR-only** on its Vulkan compositor (`cpp/winlator/vk/shaders/sgsr1.frag`
  + `SGSRUpscaler.java`) **plus an effect chain broader than ours** (sharpen/CRT/HDR/NTSC+NTSC2/Toon/ColorAdjust/
  ColorGrade/ColorBlind/Vivid/Scanlines/Pixelate/Natural); its `fsr.glsl` is only in the cnc-ddraw wrapper, not the compositor.
- So "real upscaler on Vulkan" and "full effect chain on Vulkan" are **NOT** unique to us; only **both SGSR *and* FSR1
  together** on the default path is. Also the upscalers were brand-new, not "previously OpenGL-only" (only the effects were).
- Rewrote the 1.9.2 intro: dropped the superlative, credited GameNative as FSR-on-Vulkan pioneer, noted SGSR exists in
  other Pluvia forks, claimed only the verified differentiator (both upscalers together). Also split What's New so
  **Render scale (supersampling)** is labeled *set before launch* (container/shortcut) vs the drawer-live upscalers/effects.
  Applied via `gh release edit 1.9.2 --notes-file` (APKs/update.json/Latest unchanged).

---

## 2026-06-27 — Native-mutex merge + on-screen controls opacity shadow fix

**1. Native-Rendering ↔ presets mutex MERGED to main.** User device-tested the latest `feat/vulkan-native-mutex`
build and confirmed it good. Fast-forwarded `main` `506ac6a`→`1c9c576` (`3ed78bb` mutex + `1c9c576` toast
black-box fix + Linear default scaling mode), pushed `origin/main`, deleted the feature branch local + remote.
The full Vulkan graphics program (P1 / P1b / P1c CAS+HDR / P2 effects / native-mutex) is now all on main.

**2. On-screen controls opacity bug FIXED** — `app/.../inputcontrols/ControlElement.java`, commit `1d9439e` on
main, CI run `28294667670` ✅ GREEN (all 3 flavors). **Device-test PENDING.**
- *Symptom (user, device screenshots 100% vs 6%):* at low Overlay Opacity the A–F keyboard strip fades fully, but
  the 4 compact keys MRB/BKSP/SPACE/ENTER keep a solid blue filled square while only their label text fades.
- *Root cause (pulled both screenshots to confirm):* NOT the fill paint — the GameHub `fillColor` already tracks
  `gameHubDim`. It was the **drop shadow**: the BUTTON draw path calls `paint.setShadowLayer(..., 0x401C85FE)`
  (hardcoded blue, alpha `0x40`) before the fill, and that shadow alpha never scaled with opacity. At low opacity
  the fill/stroke/text vanish but the blue glow persists — on the compact `SQUARE` keys it reads as a solid blue
  background; on the wide `ROUND_RECT` pills (A–F) it smears out and looks invisible. That asymmetry = the bug.
- *Fix:* added `int shadowColor = Color.argb((int)(0x40*gameHubDim*effectiveOpacity),0x1C,0x85,0xFE)` and used it
  in both `setShadowLayer` calls (trigger + non-trigger BUTTON paths). 0% opacity now truly vanishes. Only the
  BUTTON case has a shadow; STICK/D_PAD/TRACKPAD/RANGE_BUTTON unaffected.
- *Next:* CI green → install → device-test opacity at low values across both key shapes.

---

## 2026-06-27 — Phase 2: remaining GL screen effects → Vulkan post chain

**Branch `feat/vulkan-effects-p2`** (off `main` `71dceca`), commit `5dfcdbf` + fix `77c6b76`. Builds on the
now-merged P1/P1c Vulkan post-process framework. NOT merged.

**Device test (space scene):** all 5 effects work individually — Color/Brightness (washes out at 95), Toon
(edge outlines + posterized), CRT (RGB chromatic-aberration on stars), NTSC (horizontal chroma bleed). NTSC+CRT
2-effect combo renders clean. **Bug found + fixed (`77c6b76`):** with a *scaling* mode (SGSR/FSR/Sharpen/downscale)
active, the screen effects were silently dropped (toggles on, image clean) — `recordUpscalePasses`' local `fxOn`
only checked `cas||hdr`, so the scale pass treated itself as final and skipped the chain. Now includes all 7 effects.
(This also fixes P1c CAS/HDR, which had the same gap on the scaling path.) **Fix rebuilt: branch tip `aed6cde`, CI build `28290066760` ✅ green (all 3 flavors).** **Fix device-verified on the
space scene:** SGSR + CRT now shows the CRT fringing/scanlines on the upscaled image (was dropped pre-fix), and
SGSR + NTSC + CRT (3-deep chain) renders both effects cleanly — no black screen/corruption. **Phase 2 is
device-proven** (Color/Toon/CRT/NTSC visually confirmed + the scaling-chain fix; FXAA wired, subtle by nature).
Branch tip `0593385`. **Merged to main (ff `eee9d57`), branch deleted; artifacts build `28291121833` ✅ green.**

**Phase 3 (ReShade-style `.fx` engine) DROPPED 2026-06-27.** The upscalers (SGSR/FSR/Sharpen/downscale) are
resolution-reconstruction passes wired into the compositor — not ReShade-style fixed-res filters, so they stay hardcoded
(the headline differentiator). The cosmetic effects (CAS/HDR/FXAA/Toon/Color/CRT/NTSC) are fully covered by the curated
hardcoded set with better perf/reliability on mobile/Turnip. A data-driven engine only pays off for user/community
extensibility without rebuilds, which isn't a goal. **Effects work is complete.** Remaining: P4 (lean native-GLES2 GL path)
+ the queued overlay-opacity button-fill bug. The Vulkan renderer now carries the full stack: SGSR/FSR/Sharpen upscalers +
CAS + fake-HDR + FXAA/Toon/Color/CRT/NTSC, all on the default path.

Ported the 5 remaining GL-only screen effects onto the **same** Vulkan post chain as composable controls,
at full GL parity:
- **Color** — Brightness / Contrast / Gamma sliders (replicates `ColorEffect.java`: brightness `clamp(s/100,-1,1)`,
  contrast `clamp(s/100,0,2)` so negative contrast is a no-op like GL, gamma `clamp(0.1,5)`; neutral 0/0/1 ⇒ pass skipped).
- **FXAA · Toon · CRT · NTSC** — toggles (GL shader math ported verbatim).

**Locked canonical chain order** (best results): `composite → scale (SGSR/FSR) → FXAA → Toon → Color → CAS → HDR
→ NTSC → CRT → swapchain` — AA first, stylize/grade the clean image, sharpen, bloom, then the output-medium
emulation last (NTSC analog signal, then the CRT tube). The fixed 2-effect chain from P1c was generalized to an
ordered 7-effect list, ping-ponging `fx1`/`fx2` (2 buffers suffice); the last active effect writes the swapchain,
earlier ones write `offscreenRenderPass` fx targets (auto-barriers). Engages even at scaling mode 0/1/2.

5 new shaders (`fxaa/toon/color/ntsc/crt.frag` + compiled `*_frag.h`), 10 new pipelines (Off/Swap per effect),
all PC structs ≤ 28 B (≤ the 88 B shared range, unchanged). Plumbing mirrors P1c: 5 JNI entry points,
`VulkanRenderer.setScreenEffects(b,c,g,fxaa,toon,crt,ntsc)`, `XServerDialogState.onVulkanScreenEffectsApply`,
a "Screen Effects" subsection in the Vulkan drawer block, and launch-seed + callback wiring in
`XServerDisplayActivity`. **No-op safety:** with zero new effects enabled, control flow is identical to current main
(no regression to shipped P1/P1c behavior). Touched: `{fxaa,toon,color,ntsc,crt}.frag`(+`.h`),
`VulkanRendererContext.cpp/.h`, `vulkan_jni.cpp`, `VulkanRenderer.java`, `XServerDialogState.kt`, `XServerDrawer.kt`,
`XServerDisplayActivity.java`. `docs/render_upgrades_report.html` already shows P2 in-progress + the locked chain order.

---

## 2026-06-27 — Vulkan CAS + fake-HDR + sharpness sliders (Phase 1c) + on-device upscaler proof

**Branch `feat/vulkan-cas-hdr`** (off `feat/vulkan-upscaler-sgsr-fsr` tip `80c6d56`), commit `4fecbc6` +
docs `181500c`. **CI build `28287630767` ✅ GREEN** (standard/ludashi/pubg). NOT merged. Device-test pending.

**On-device upscaler verification (the resume from the smooth-blob test).** Re-ran the frozen-frame A/B on the
**DX11 "space" scene** (textured planet + coastlines + dense starfield), 720p container → 1080p panel, build 1.9.1:
the scaling modes are now clearly and usefully distinct (RMSE vs None ≈ 6× the smooth blob's <0.4%):

| Mode | RMSE vs None | On screen |
|---|---|---|
| Nearest | 0% (≡ None) | hard stair-step jaggies on the planet limb (point) |
| Linear | 1.79% | jaggies smoothed but whole frame softened |
| **SGSR** | 1.75% | edges cleaned, stars/detail stay crisp — sweet spot |
| **FSR / FSR-Fit** | 1.82% | same family as SGSR |
| **Sharpen** | 2.46% | brighter/punchier (RCAS), keeps base jaggies |

The earlier "no visible difference" was **bad test content** (smooth SDF blob, no high-freq edges), not a bug —
spatial upscalers are an edge-cleanup whose effect grows with the upscale ratio. "More RMSE" ≠ "better"; fidelity to
a native render is the goal. This test motivated the P1c sharpness sliders (strength was locked at 0.25).

**Phase 1c — three new composable Vulkan post controls + one rename:**
- **CAS toggle + "CAS Sharpness" slider (0–100, default 60)** — the same AMD CAS the GL path uses (`cas.frag`
  ported from `FSREffect.java`), layered on top of any scaling mode, runs even at native res.
- **HDR toggle** — the same fake-HDR (`hdr.frag` ported from `HDREffect.java`, HDRPower 1.30, binary).
- **"Sharpness" slider** for scaling modes SGSR/FSR/FSR-Fit/Sharpen — unlocks the real upscaler sharpness
  (was hard-coded 0.25 RCAS stops; default slider 75 keeps 0.25). SGSR `EdgeSharpness` moved const→push-constant.
- **GL "SGSR" → "Sharpen (CAS)"** — the GL toggle was never SGSR; it's AMD CAS sharpening at native res. Label-only.

Pipeline: `recordUpscalePasses` rewritten to chain `composite → scale → CAS → HDR → swapchain`, with optional
`fx1`/`fx2` intermediates ping-ponged through `offscreenRenderPass` (auto-barriers via its baked subpass deps; no
hand-rolled `vkCmdPipelineBarrier`). Cross-binding the swapchain-render-pass scale pipelines into an
`offscreenRenderPass` fx target is legal — both passes use `VK_FORMAT_R8G8B8A8_UNORM` (format-compatible). All PC
structs ≤ 88-byte range. Touched: `cas/hdr/sgsr.frag` (+ compiled `*_frag.h`), `VulkanRendererContext.cpp/.h`,
`vulkan_jni.cpp`, `VulkanRenderer.java`, `XServerDialogState.kt`, `XServerDrawer.kt`, `XServerDisplayActivity.java`.
Drawer-only / session-live (no DB persist), like the scaling mode. `docs/render_upgrades_report.html` updated with
the device-test results + P1c.

---

## 2026-06-27 — Vulkan spatial upscalers + sharpen + supersampling (Phase 1/1b)

Branch `feat/vulkan-upscaler-sgsr-fsr` (NOT merged). First fork with real SGSR **and** FSR1 upscaling on the
default Vulkan renderer, plus native-res sharpen and supersampling — in one app. Full design/provenance log:
`docs/SGSR_HDR_VULKAN_PLAN.md`; per-renderer summary `docs/render_upgrades_report.html`.

**Built a Vulkan post-process framework** in `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (offscreen
composite target at game res → post/upscale pass → swapchain), then layered features on it:

- **Scaling mode** (in-game drawer, live, Vulkan-only): None / Linear / Nearest / **SGSR** / **FSR** / **FSR (Fit)** /
  **Sharpen**. Modes via `VulkanRenderer.setUpscaler(int)` 0–6. Upscalers engage only when the game renders below
  display res; **Sharpen (6)** runs FSR RCAS at any res incl. native.
- **Supersampling ("Render scale")** — pre-launch container + per-game-shortcut setting Off/1.25x/1.5x/2x (stored via
  `renderScale` extra, no DB migration). Launch multiplies the X11 render res (aspect-preserve, clamp 7680x4320, even
  dims); compositor runs a new Lanczos-2 `downscale.frag` via `setHqDownscale(true)`. DSR/OGSSAA-style.
- **Per-renderer Graphics tab** — shows ONLY the active renderer's controls (OpenGL→SGSR/HDR+ScreenEffects;
  Vulkan→Scaling mode; SurfaceFlinger→"no enhancements" note) instead of greying out the rest.

**Shaders bundled** (offline-compiled to `.spv` C-array headers, license headers retained): SGSR 1.0 (Qualcomm,
BSD-3), FSR1 EASU+RCAS (AMD, MIT), Lanczos downscale. Approach for FSR-in-compositor / FSR-Fit credited to GameNative.
**HDR deferred** (Android WSI rarely exposes an HDR10 surface; revisit later).

**Commits:** `5f5a4a0` native upscaler + drawer · `28ab22d` per-renderer tab · `c3cbe49` Phase 1b sharpen+supersampling
· docs `33ad5f4`. **CI:** Phase 1 GREEN (`28276691564`, `28277238762`); full Phase-1b build `28277821185` ✅ GREEN
(all 3 flavors). **DEVICE-UNTESTED** — next step is on-device: sub-native upscale modes, native-res sharpen, 1.5x
supersampling, and per-renderer tab. Then **Phase 2** = port GL effects (FXAA/CRT/Toon/NTSC/color) to Vulkan.

CI for this repo is MANUAL: `gh workflow run build-artifacts.yml --ref <branch>`.

---

## 2026-06-25 — 1.9 STABLE cut ✅ (SurfaceFlinger renderer + DXVK 3.0 / Vulkan 1.4)

Merged `feat/surfaceflinger-renderer` to main (ff `d915798`), bumped to **1.9 / versionCode 29** (`eb39c2b`),
and cut **1.9 stable** (release run `28215839109`, `make_latest`, `update.json` attached → in-app updater
offers it on the stable channel). User explicitly authorized promoting to stable ("release 1.9").

**Shipped in 1.9:**
- **SurfaceFlinger (ASR) renderer** — experimental third host renderer, opt-in behind a reboot-risk warning
  dialog, default off. Ported from GameNative PR #1582 (André Vito) on StevenMXZ's scanout work.
- **DXVK 3.0 / Vulkan 1.4** option in the Turnip/Wrapper Driver Configuration.
- **Fixes:** per-game DXVK/VKD3D download sheet no longer hides behind the settings dialog; perf HUD labels
  SurfaceFlinger correctly.

**No imagefs reinstall required** — the 1.9 diff is purely app-side (renderer engine, a bundled native lib,
an env-var option, UI). No `imagefs/`, `assets/`, or `imgVersion` change; existing containers are untouched.

Release description was rewritten to the 1.8 layout with credits to GameNative (André Vito) + StevenMXZ for ASR.

---

## 2026-06-25 — SurfaceFlinger (ASR) renderer Phase 1 ✅ WORKING + device-proven (branch `feat/surfaceflinger-renderer`)

Took the SurfaceFlinger renderer from "selectable skeleton" (Phase 0) to a working scene compositor
that renders real D3D games fullscreen via Android SurfaceFlinger — no GL/Vulkan compositor. Ported
from GameNative PR #1582 (André Vito, on StevenMX's scanout work), adapted to Bannerlator's X-server API.

**Build-up:** scene engine (`ASurfaceRenderer` implements `WindowManager.OnWindowModificationListener`
+ `Pointer.OnPointerMotionListener`; `updateScene` walks the window tree under XLock → one SurfaceControl
layer per window via `nativeRegisterWindowSC`/`nativeUpdateWindow` in a begin/apply transaction; frames
pushed via `nativeSetWindowBuffer`) + additive `PresentExtension` ASR branch (routes the game AHB to the
SC; Vulkan/GL paths untouched).

**The hard debugging (device, Adreno 750, GTA IV + AIO Graphics Test, DXVK 3.0 + VK 1.4):** game ran
(audio) but showed a small top-left window. On-device logging (filtered logcat to a file — wine logs flood
the buffer) proved the whole Java chain worked (8000+ `ASR_Present`/pushes with valid AHBs, SC registered,
visible). Two stacked root causes, both fixed:
- **`Drawable.DRAWABLE_ASR_MODE`** (`98861c8`): port GameNative's flag so every Drawable is backed by a
  composer-compatible `GPUImage` AHB at construction (`data = AHB mapped memory`) — required for
  SurfaceFlinger to scan out. Wired `setAsrMode(true/false)` per renderer in `XServerView.initRenderer`.
- **Geometry** (`bf292bf`): `computeWindowRect` used the normalized GL `sceneScaleX` (~1.0), pinning the
  game at native size in the corner. Map through `viewTransformation.aspect` (surface-px-per-X-px, e.g.
  1.5×) + letterbox offset instead → fills the surface.
- **HUD** (`bf292bf` + `c4f6e5f`): wired `ASurfaceRenderer.setHudFrameTick` (FPS was blank — the tick was
  Vulkan-only) + fixed the renderer label (`XServerDisplayActivity:1710` binary vulkan?:OpenGL → +SF case).

**✅ DEVICE-PROVEN** (build `28213017959`, screenshot-every-5s/60s): GTA IV menu renders FULLSCREEN under
ASR, HUD reads `SurfaceFlinger | DXVK | … FPS: 398 2.5ms`, stable. **✅ GL/Vulkan regression pass:** all
three renderers render GTA fullscreen with correct labels/FPS (Vulkan 300, OpenGL 295, SurfaceFlinger 398)
— additive edits don't disturb GL/Vulkan, global ASR flag clears correctly. **✅ Debug logging stripped**
(`bb64f2b`, clean build `28213752314`).

Branch tip `bb64f2b`, carries the merged Vulkan 1.4 commit. **NOT merged** — awaiting call: merge to main vs
Phase 2 polish first (CPU desktop chrome compositing, cursor, fps-limit tearing — none block game render).
Process note: always `git push` BEFORE dispatching a CI build (a build was once cut from the pre-push commit;
verify via `gh run view <id> --json headSha`). 1.9-pre prerelease when cut.

---

## 2026-06-25 — DXVK 3.0 Vulkan 1.4 option ✅ merged + SurfaceFlinger renderer Phase-0 spike 🚧

**Context:** DXVK 3.0 shipped (all 4 `.wcp` flavors on The412Banner/Nightlies). DXVK 3.0 **hard-requires
Vulkan 1.4** (mandatory bump from 2.x's 1.3 — verified vs the release notes). The Turnip/Wrapper Driver
Configuration "Vulkan Version" dropdown capped at 1.3, so the wrapper exported `WRAPPER_VK_VERSION=1.3.x`
and DXVK 3.0 refused to init even on a VK1.4-capable driver.

**Fix — Vulkan 1.4 option (`785fe2b`, branch `feat/vulkan-1.4-dxvk3`, CI `28205826581` ✅ → ff-merged to
main 2026-06-25).** One-line: added `<item>1.4</item>` to `arrays.xml` `vulkan_version_entries`. Default
kept **1.3** (safe; 1.3-only drivers/A6xx unaffected) — user picks 1.4 manually for DXVK 3.0. Value flows
generically: dialog → `graphicsDriverConfig` `vulkanVersion=` token → `XServerDisplayActivity:2149` appends
the driver patch → `WRAPPER_VK_VERSION` env. **Proved load-bearing at the binary level:** disassembled the
bundled `libvulkan_wrapper.so` — `wrapper_GetPhysicalDeviceProperties` does `getenv("WRAPPER_VK_VERSION")` →
`sscanf` → `VK_MAKE_API_VERSION` → `str` into `pProperties->apiVersion` (offset 0), the exact field DXVK 3.0
gates on. Caveat: override is unconditional (no clamp to real driver max) → on A6xx (Turnip caps at 1.3)
picking 1.4 would lie to DXVK = footgun; default-1.3 avoids it. All 4 Nightlies DXVK release bodies updated
with the VK1.4 note + "Current version: 3.0". Driver side: The412Banner/Banners-Turnip builds report
**Vulkan 1.4.354** (Mesa main, `TU_API_VERSION=VK_MAKE_VERSION(1,4,..)` for chip≥7); device Adreno 750 (A7xx)
gets the 1.4 path. DEVICE-UNTESTED end-to-end (DXVK 3.0 launch w/ 1.4 selected).

**SurfaceFlinger renderer (ASR) — Phase-0 spike (branch `feat/surfaceflinger-renderer`, commit `068c3a5`,
CI `28208898551`).** 3rd host renderer ported from GameNative PR #1582 (André Vito; built on StevenMX's
scanout work). Confirmed our `cpp/winlator/VulkanRendererScanout.cpp` is **byte-identical** to GameNative's —
Steven's scanout foundation already in-tree. Spike = compiles + selectable (NOT a working compositor):
native `cpp/asurfacerenderer/` (JNI repackaged to `com_winlator_star_renderer`) → `libasurface_renderer.so`
via main CMakeLists; skeleton `ASurfaceRenderer` implements `HostRenderer` + loads lib + creates/destroys the
SF context on the surface lifecycle (per-window scene compositing deferred to Phase 1); selection wired in
`XServerView.initRenderer(String)` + `XServerDisplayActivity` (API<29 → Vulkan fallback) + "SurfaceFlinger"
added to container + per-game renderer dropdowns. NOT merged; device-test pending. See
`reference_gamenative_surfaceflinger_renderer` memory for the full Phase-1 plan.

---

## 2026-06-25 — 1.8 STABLE cut ✅ (updater picker fix + in-app OTA proven on a real stable)

Closed out the 1.8 cycle. One code blocker remained from the updater work, then cut stable.

**Picker correctness fix (`f1729a7`, branch `fix/updater-picker-sort`, CI `28200393133` ✅ → ff-merged to
main, branch deleted).** GitHub's list-releases API does **not** return pure newest-first — it pins the
`make_latest` release to the top, then lists the rest by date. Confirmed live: the API returned
`[1.7 (latest, published 01:46), 1.8-pre2 (published 20:30), 1.8-pre1, 1.6…]`. `UpdateManager.pickNewestWithUpdateJson`
took the **first** array element carrying `update.json`, which worked only because 1.7 had none (skipped).
Once 1.8 stable carried `update.json` + `make_latest`, it would have **shadowed a newer 1.9-preN** in the
prerelease channel. Fix = parse all releases into a list, `sortWith(compareByDescending { optString("published_at","") })`
(ISO-8601 sorts lexicographically = chronologically), then walk for the first with `update.json`.

**1.8 stable cut (user explicit go-ahead — required by the hard rule).** Bumped `versionCode 27→28` +
`versionName "1.8"` (`376e5fd`), dispatched `release.yml` with `release_tag=1.8 release_number=1.8
make_prerelease=false` → workflow auto-sets `prerelease:false` + `make_latest:true`. Release run
`28201699881` ✅. Verified: **Bannerlator 1.8 = Latest**, 1.7 demoted; assets = 3 flavor APKs +
`update.json`; **`releases/latest/download/update.json` now resolves to vc28/1.8** (stable updater
baseline live). Release body rewritten to match the 1.7 layout (logo / tagline / What's-New sections /
downloads table / credits / collapsible changelog) — intentionally **no reinstall-imageFS warning** since
1.8 is app-side only (HUD + updater), nothing changed in imageFS.

**✅ In-app OTA proven on a real stable cut:** a device running **1.8-pre2 (vc27)** auto-updated in-app to
**1.8 stable (vc28)** — the full updater loop (detect → download correct flavor → install) confirmed on a
genuine stable transition, not just pre→pre. Main tip `376e5fd`.

**1.8 ships:** GameHub-style perf HUD (2nd selectable overlay + live swap) · in-app updater (auto-install
+ optional prerelease channel) · setup-screen branding fix · updater picker fix. Next cycle → 1.9-preN
prereleases until an explicit stable call.

---

## 2026-06-25 (later) — In-app updater + prerelease channel (✅ device-proven, shipping via 1.8-preN)

Built a GitHub-releases-based **in-app update system** (modelled on the BannersComponentInjector /
BannerHub updater). Merged to main; being device-tested via prereleases.

**Core — `core/UpdateManager.kt`:** fetches `releases/latest/download/update.json`, compares
`BuildConfig.VERSION_CODE` (the integer is the source of truth, NOT the tag string), caches to
`cacheDir` (offline-safe), picks the flavor APK by `BuildConfig.APPLICATION_ID`, downloads via the
existing `HttpUtils` (reuses `DownloadProgressDialog`) and installs through the existing
`com.winlator.star.tileprovider` FileProvider. Install-permission guarded (`REQUEST_INSTALL_PACKAGES`,
Android 8+). **UI lives in 3 places:** Settings → new "Updates" section (readout, Check, Download &
install, Notify toggle); About dialog (latest-version line + "Update now"); app-wide amber home banner
(honours notify + skip-version). Manifest got `REQUEST_INSTALL_PACKAGES` + an `external-cache-path`;
`release.yml` generates + attaches `update.json` per release.

**"Include pre-releases" toggle (Settings, default OFF):** OFF = stable path (`releases/latest` only ever
resolves to a non-prerelease). ON = `checkViaApi` → GitHub releases API (`?per_page=30`, prereleases
included) → newest release carrying an `update.json` → its own asset URLs. **Gotcha: api.github.com 403s
without a `User-Agent`** → added one to `HttpUtils`' string fetch. `release.yml` gained a
`make_prerelease` input (sets `prerelease` + inverts `make_latest`); `update.json` now attaches to EVERY
release so the toggle has data.

**Versioning rule established (hard rule):** stables = plain numeric tag (`1.8`,`1.9`),
`prerelease:false` + `make_latest:true` — the ONLY thing the default updater offers. Everything between
stables = `X.Y-preN` (`1.9-pre1`,`pre2`…), `prerelease:true`, no make_latest, until explicitly promoted.
`versionCode` ticks up on EVERY build.

**Branding fix (`fix/setup-splash-branding`, merged):** the shared `DownloadProgressDialog` (first-launch
imagefs setup + HttpUtils downloads incl. the update download) hardcoded **"Star Bionic"** + **"Bionic
V1.1"** — caught from a device screenshot (pulled via root bridge). Title → `@string/app_name`
(per-flavor), version → `BuildConfig.VERSION_NAME` (dynamic, new `@+id/TVVersion`). Also cleaned the
leftover "Star Bionic" in the unused `about_dialog.xml`.

**Build log:**

| Step | Commit / Tag | CI Run | Result |
|---|---|---|---|
| Updater core (Settings/About/banner) | `41d7c06` | `28193511129` | ✅ green |
| Include-prereleases toggle + UA fix | `19b7e36` | `28195066124` | ✅ green |
| Merge + bump → 1.8-pre1 (vc26) | `ca87892` | `28195824422` (release) | ✅ published (prerelease) |
| Setup-screen branding fix | `b11814c` | `28197124387` | ✅ green |
| Merge + bump → 1.8-pre2 (vc27) | `2b10f53` | `28197773910` (release) | ✅ published (prerelease) |

**✅ DEVICE-PROVEN:** on the installed vc25 build, toggling Include-prereleases ON surfaced 1.8-pre1, and
Update downloaded + installed + launched it end-to-end. 1.8-pre2 (vc27) cut to re-test + carry the
branding fix. Stable 1.7 users untouched throughout (`releases/latest` still 404s for update.json since
1.7 predates the feature; no pre is make_latest). Main tip `2b10f53`.

**🐛 KNOWN latent bug (NOT yet fixed):** GitHub `/releases` API is not reliably newest-first — it hoists
the `make_latest` (stable) release to the top. `pickNewestWithUpdateJson` takes the first array element
with an `update.json`, which works while only prereleases carry it, but once a **stable + a newer
prerelease coexist**, the older stable would win over the newer beta for toggle-on users. **Fix before
cutting 1.8 stable: sort releases by `published_at` (fallback `created_at`) DESC before scanning.**

---

## 2026-06-25 — GameHub HUD: device-test crash fix + full in-game drawer mirror (branch `feat/gamehub-perf-hud`)

Continued the GameHub HUD port from P0–P4 (entry below) into on-device testing. Two follow-ups, neither merged.

**1. First-launch crash FIXED (`4808d51`, build `28179250039` ✅ green).** Installing the correct P4
build and enabling the GameHub HUD crashed the container + app the moment the overlay first refreshed.
Logcat (pulled via the root bridge):
```
FATAL EXCEPTION: Thread-6
android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that
  created a view hierarchy can touch its views. Expected: main Calling: Thread-6
    at com.winlator.star.widget.PerfHudView.update(PerfHudView.java:350)
    at ...XServerDisplayActivity$6.onUpdateWindowContent(:798)   ← X-server epoll thread (PresentExtension)
```
Root cause: `PerfHudView.update()` runs on the X-server epoll thread and called `requestLayout()` /
`invalidate()` directly — neither is thread-safe. `FrameRating` never hit this because it marshals via
`post(this)`. Fix = `post(refreshOnUi)` where `refreshOnUi = () -> { requestLayout(); invalidate(); }`.
The other two view-touching methods (`applyConfig`, `setVertical`) already run on the UI thread
(`runOnUiThread` at `XServerDisplayActivity:561` / tap handler). **Gotcha for future HUD work: anything
reached from `onUpdateWindowContent` is on the epoll thread → only `post()` / `postInvalidate()`.**

**2. In-game side drawer now fully mirrors the container dialog (`7437c3d`).** User wanted the same
settings/toggles in the in-game HUD tab. Before, `XServerDrawer.kt` → `HudContent` only had the classic
subset (scale/opacity + 6 toggles) and its `buildConfig()` **omitted `hudStyle` and every gamehub-only
key** — so changing anything in-game while the GameHub HUD was active stripped `hudStyle=gamehub` from the
saved container config (persisted via `onFpsConfigApply` → `setFPSCounterConfig` + `saveData` at
`XServerDisplayActivity:567`), reverting to the classic HUD on next launch. Rewrote `HudContent` to mirror
`FpsCounterConfigDialog` exactly: GameHub-style switch, FPS-graph / Power / GPU-model / dual-battery
toggles, skin/color/outline 3-stop chips (new drawer-styled `HudChipRow`, no FilterChip import), opacity
slider, and the **identical key set** (emits both classic + gamehub metric key names) so the drawer and
the pre-launch dialog are interchangeable. Metric/skin/scale/opacity changes apply **live** via
`onFpsConfigApply` → `perfHud.applyConfig` (UI-thread safe). The classic↔gamehub **view swap** still
applies on next launch only (the view is chosen at launch; a caption notes this) — live view-swap is a
possible follow-up.

Branch tip `7437c3d`. Combined build CI `28181338752` (in progress at time of writing). **NOT merged.**
Next: device-test the `28181338752` build — (a) enable GameHub HUD (master **Show FPS** must also be on),
launch → confirm renders without crash + tap flips orientation + metrics live; (b) open in-game drawer
HUD tab → confirm all GameHub controls present, apply live, and no revert-to-classic → tune dims/colors →
merge to main.

## 2026-06-25 — GameHub-style performance HUD port (branch `feat/gamehub-perf-hud`) — P0–P4 coded, device-test pending

A second, **selectable** in-game performance HUD modeled on GameHub 6.0.9's overlay, alongside the
existing `FrameRating`. User scope: **full parity** (17 controls), **per-container**, **all 3 skins**.
Clean reimplementation (our own View + data we already collect) — no GameHub code/assets copied.

Recon: 3 Explore agents over the jadx decompile (`/home/claude-user/gamehub-6.0.9-jadx`, GameHub =
Compose-Multiplatform `com.xiaoji.egggame`, obfuscated). HUD = plain Canvas/Paint/Path (no Compose/GL/
native for visuals; ref legacy View `o6m.java`). Two layouts (horizontal pill / vertical list), FPS line
graph (last 50 samples, peak clamped ≥60, 30fps guide), Classic/Neon/Mono skins, color-intensity
(0.72/0.88/1.0), text outline (off/1.0dp/1.4dp), scale 0.6–1.4, opacity. Most metrics already collected
by `FrameRating`; FPS comes from our own frame counter (GameHub's `libxserver.so` shm not needed).

- **P0 plan** `docs/GAMEHUB_PERF_HUD_PORT_PLAN.md`.
- **P1+P2** new `widget/PerfHudView.java` — self-contained Canvas view: both layouts, per-field colors,
  FPS graph, all 3 skins, color-intensity, outline, scale, opacity; parses the `fpsCounterConfig`
  KeyValueSet; `update()` frame-tick mirrors `FrameRating`; tap-toggle + drag. Standalone compile CI
  `28175068799` ✅ green.
- **P3** new `widget/HudMetrics.java` — shared collector: GPU% / temp / RAM / power+charging ported from
  `FrameRating`, **plus** overall CPU usage % (`/proc/stat` delta) and the dual-battery power fix (sums
  `battery`+`bms`+`main` `current_now` with abs()).
- **P0 wiring** `XServerDisplayActivity.java` — when `hudStyle=gamehub`, creates a `PerfHudView`
  (WRAP_CONTENT params) instead of the two `FrameRating` views; handled at every HUD site (create /
  show-hide / frame-tick update / live applyConfig / `toggleFpsHudOrientation`→`perfHud.setVertical` /
  DX-API detect→`setEngineLabel` / `_MESA_DRV_GPU_NAME`→`setGpuModel`). Classic path unchanged.
- **P4 config UI** `ContainerDetailScreen.kt` `FpsCounterConfigDialog` — "GameHub-style HUD" switch
  (`hudStyle`) + 9 metric toggles + dual-battery + scale/opacity sliders + skin/color/outline 3-stop
  FilterChip selectors (`HudToggleRow`/`HudThreeStop`); toggles emitted under both classic + gamehub key
  names; bounded scroll via `heightIn(screenHeight*0.7)`.

Config keys (per-container `fpsCounterConfig`): `hudStyle=classic|gamehub`, `hudMode`, `showFPS`,
`showFPSGraph`, `showCPUUsage`(+`showCPULoad`), `showGPULoad`, `showRAM`, `showPower`,
`showTemp`(+`showBatteryTemp`), `showEngine`(+`showRenderer`), `showGpuModel`, `hudDualBattery`,
`hudSkin=classic|neon|mono`, `hudColor=soft|mid|vivid`, `hudOutline=off|soft|strong`, `hudScale` (50–150),
`hudOpacity` (0–100), `hudTransparency` (classic).

Branch tip `b2fc55e`. Full artifact build CI `28176206476` (in progress at time of writing). **NOT merged.**
Next: verify build green → device-test (enable "GameHub-style HUD" in a container's FPS settings, launch,
confirm render + tap-flip + live metrics) → tune dimensions/colors on device → merge to main. Caveats:
HUD dimensions are first-guess; CPU% is overall-device (not per-game); in-game `XServerDrawer.kt` config
not extended (container-detail dialog only; orientation tap still works in-game).

## 2026-06-25 — Two HUD fixes merged to main (`ac6abbb`)

- **Fake FPS offset removed** (`19c9982`): stripped a fudge offset from `FrameRating.java` +
  `FrameRatingHorizontal.java` so the on-screen FPS reads true when native rendering is on.
- **Vertical HUD tap area fixed** (`ac6abbb`): the vertical `FrameRating` was added to the root
  `FrameLayout` with no LayoutParams, inheriting FrameLayout's `MATCH_PARENT × MATCH_PARENT` default — so
  its tap-to-toggle hit area covered the whole screen (a tap far from the overlay flipped orientation).
  Fixed with explicit `WRAP_CONTENT` + top-left gravity. Both built green (`28172970834`) and
  fast-forward-merged to main; `fix/fps-hud-tap-area` deleted. No release cut (still versionCode 25).

## 2026-06-24 — Steam detail-page revamp — branch `feat/steam-detail-revamp` (stacked on launch branch)

User asked to modernize the Steam game detail page. Picked all of: stored-info rows, last-played,
real playtime hours, bigger sheets (DLC/branch/cloud/add-home), and more robust/fluid download buttons +
accurate progress bars.

- **Chunk 1 DONE** (`SteamGameDetailActivity.kt` `ca90b96`): renders **developer / genres /
  metacritic** (already stored in our GameRow but previously hidden) + a **"Last played"** row from the
  install-dir timestamp (`relativeTime()`); reworked the downloads UI — **animated rounded progress bar**
  in a card with an **indeterminate "Preparing…" phase** and a separate %/bytes line; new
  `DetailActionButton` (48dp, rounded, disabled dimming) + `DetailInfoRow`. Pure UI/Compose.
- **Chunk 2 — real playtime hours: ABANDONED + REVERTED** (`ee85bf9`). Tried owned-games via JavaSteam
  unified messages (`SteamOwnedGames.kt`) but the API fought compilation (needed protobuf-java for the
  proto `GeneratedMessage` supertype; then `Player.getOwnedGames` return type is neither `Future` nor
  `CompletionStage` — `.get()`/`.toCompletableFuture()`/`.result`/`.body` unresolved). User chose to drop
  playtime recording; timestamp-based "Last played" (chunk 1) stays. Removed the file + Playtime row +
  protobuf-java dep.
- **TODO (remaining follow-up):** **bigger sheets** — DLC/depot manager, beta branch picker,
  cloud-save export/import/sync, add-to-home-screen (port from ref4ik `SteamLibrarySheets.kt` /
  `SteamGameActions.kt`).

**RESUME SNAPSHOT (2026-06-24, for crash recovery):** Two stacked feature branches, NONE merged, all
device-test-pending:
  • `feat/steam-pluvia-launch` (off main) — Pluvia Phase 1 coldclient launch, steps 1-4, commits
    `ff13265`/`1c6839d`/`f0b6106`/`73834d6`, all compile-green. Drawer "Steam" = unchanged store; adds
    emulation launch.
  • `feat/steam-detail-revamp` (stacked on the launch branch) — tip `ee85bf9` — detail revamp chunk 1
    only (chunk-2 playtime reverted). CI `28143904501`.
NEXT WHEN RESUMING: (1) confirm CI `28143904501` green; (2) device-test on the test device — Steam
download → add a steam_api game with "Steam emulation" → confirm Goldberg launch; detail page shows
dev/genres/metacritic/last-played + fluid progress; (3) optionally build the bigger sheets;
(4) then decide merge order (launch branch → main first, then rebase revamp → main). ref4ik clone at
`/home/claude-user/scratchpad/ref4ik`. Full design = `docs/STEAM_PLUVIA_PORT_PLAN.md`.

**imageFS reinstall?** No — for the Steam coldclient + detail revamp, updating 1.7 → next release needs
NO imageFS reinstall. The coldclient loader is a separate bundled APK asset extracted at runtime into the
existing imageFS (not baked into `imagefs.txz`), and the detail revamp is app-only. (Only carryover: if a
user never reinstalled imageFS for 1.7's ffmpeg-8, that 1.7 recommendation still applies.)

## 2026-06-24 — Pluvia Steam: Phase 1 (Goldberg/coldclient launch) — branch `feat/steam-pluvia-launch`

Implementing the recommended **Option A** from `docs/STEAM_PLUVIA_PORT_PLAN.md` — **UPGRADE the
existing Steam store** (browse/login/download/UI stay ours, unchanged from 1.7), adding only the
Goldberg/coldclient **launch** so SteamAPI titles actually run. ⚠️ User confirmed scope 2026-06-24:
NOT a full replacement (drawer "Steam" still opens our existing store). All work on the branch; NOT
merged; device-test pending.

- **Step 1 (`ff13265`)** — bundled asset `experimental-drm.tzst` (coldclient loader x32/x64 +
  emulated steamclient DLLs + extra_dlls; PE → host-arch independent) + `SteamClientManager.kt`
  (extracts it into the imageFs Steam dir). Ported from REF4IK/winlator-ref4ik- (GPL-3.0).
- **Step 2 (`1c6839d`)** — `SteamLaunchUtils.kt`: self-contained **offline** Goldberg helpers
  (writeColdClientIni, generateInterfacesFile, writeOfflineSteamSettings, backupSteamclientFiles,
  putBackSteamDlls, setupLightweightSteamConfig, skipFirstTimeSteamSetup, ensureSteamappsCommonSymlink).
  No dependency on ref4ik's Room SteamService; account read from our `steam_prefs`.
- **Step 3 (`f0b6106`)** — launch glue: `prepareColdClientLaunch` + `writeGameSteamSettings` +
  `StarLaunchBridge.addSteamGameToLauncher`/`writeSteamShortcut` (container picker → activateContainer →
  prepare env → write `.desktop` `Exec=wine C:/Program Files (x86)/Steam/steamclient_loader_x64.exe`
  with `game_source=STEAM` Extra Data). Prefix model = `activateContainer` repoints the `home/xuser`
  symlink → active container; corrected ref4ik's `skipFirstTimeSteamSetup` to take `imageFs.rootDir`.
- **Step 4 (`73834d6`)** — Compose UI: `SteamGameDetailActivity` shows a Compose AlertDialog
  ("Steam emulation" vs "Run .exe directly") after exe resolution → routes to the coldclient path or
  legacy raw path; `SteamGamesActivity` defaults its add paths to the emulation route.

Compile CIs: steps 1+2 `28141425805` ✅ green; steps 3 `28141874404` / 4 `28142049412` ⏳ pending.
**Next:** device-test a steam_api title (download → add with Steam emulation → boots under Goldberg)
→ then merge to main. Possible follow-ups: preferred-container, PICS LaunchInfo exe detection, cloud saves.

## 2026-06-24 — 🚀 Release 1.7

Cut **Bannerlator 1.7** (`versionName 1.7`, `versionCode 25`, commit `30c869c`). Version bumped in
`app/build.gradle`; splash screen reads `BuildConfig.VERSION_NAME` so it shows "V 1.7" automatically
(no hardcoded version strings anywhere). README version line + "What's New in 1.7" updated (1.6 notes
demoted to "Previously in 1.6"). Release build = workflow "Nightly Manual Release Build" run
`28140854161` (tag `1.7`, builds standard/ludashi/pubg release APKs) — ✅ GREEN. **PUBLISHED**
https://github.com/The412Banner/Bannerlator/releases/tag/1.7 with full notes; 3 assets each ~588.7 MB
(`Bannerlator-1.7-standard.apk` 588704729 B / `-ludashi.apk` 588704765 B / `-pubg.apk` 588704647 B).
Everything merged to main since the 1.6 tag is in this release:

- **Steam store — downloads fixed**: login-race guard (`9f6197e`) + BouncyCastle SHA-1 provider
  registration (`63e4366`). ⚠️ download-only; raw `wine exe` launch still has no steam-emu (DRM games
  may not run — see `docs/STEAM_PLUVIA_PORT_PLAN.md`).
- **Components installer (new)**: in-container Wine-dependency installer (Phase 2 file-drop + Phase 3b
  execute engine), copy_dll glob + arch-targeting fixes, win7/winXP set_windows, persisted Installed
  status.
- **On-screen controls**: overlay-opacity slider moved to in-game side menu, live, true 0–100 %.
- **FPS overlay**: tap to toggle orientation, live D3D API label (VKD3D vs DXVK).
- **Vulkan**: Advanced Vulkan / Graphics Driver dialogs scrollable.
- **Video**: full ffmpeg-8 libs bundled for winedmo.

⚠️ DEVICE-TEST status: Steam download fix, Components installer, and overlay-opacity were CI-green but
device-test was still pending/partial at release time.

## 2026-06-24 (late 2) — Steam download fixes + Pluvia/GameNative Steam-store recon & plan

**Steam download bug (✅ MERGED to main `63e4366`/`9f6197e`; compile CI `28139917719` ✅ green;
device-test pending).** User's Steam game downloads failed "Download failed: Unknown error".
Two distinct bugs found from the on-device `steam_debug.txt`:
1. *Login race* — `runInstall` started while `connected=true` but `loggedIn=false` (Steam CM
   connections cycle; re-logon after reconnect is async, license cache masked it). Manifest job
   timed out → `CancellationException`. Fix = new `SteamRepository.ensureLoggedIn(timeoutMs)` guard
   in `runInstall` (re-logon from saved token, wait up to 15s).
2. *SHA-1/BC* (the real download-killer, seen after re-login) — JavaSteam `DepotManifest.serialize`
   calls `MessageDigest.getInstance("SHA-1","BC")`; Android's built-in "BC" provider has SHA-1
   stripped → `NoSuchAlgorithmException`. App bundled `bcprov-jdk15on` but never registered it.
   Fix = static initializer in `SteamRepository` that removes stock BC + installs the full
   `BouncyCastleProvider`. Device-test of FlatOut 2 pending; then merge to main.

**Recon + plan: "Pluvia Steam" to replace the current Steam store.** Researched GameNative
(utkarshdalal/GameNative, GPL-3.0; local at `/home/claude-user/GameNative/`) and Pluvia
(oxters168/Pluvia, original, stalled). Key finding: **REF4IK/winlator-ref4ik-** (`com.winlator.cmod`)
already ported the GameNative/Pluvia Steam module into a Winlator **Cmod** fork — same lineage as
us — on the **same `in.dragonbra:javasteam:1.8.0`** we ship. It's an *upgrade*, not greenfield:
our download already works (post-fix); the real prize is the **Goldberg/coldclient launch model**
(ref4ik `SteamGameLauncher.kt`) — our store launches raw `wine exe`, so DRM/steam_api titles fail.
Recommendation = **Option A incremental** (Phase 1: Goldberg launch + loader assets +
`game_source`/`app_id` shortcut extras; then preferred-container, PICS LaunchInfo exe detection,
optional cloud-saves/updates). Full file-level seam map + risks (GPL-3.0, Goldberg asset arch,
bionic = coldclient only, A:↔Z: drive, Room↔SQLite) → **`docs/STEAM_PLUVIA_PORT_PLAN.md`** (this
commit). NOT STARTED — for down the road.

## 2026-06-24 (late) — Merged the day's branches to main + new Components installer fix

Rolled the day's feature branches onto `main` (linear rebase/ff, branches deleted). `main` tip now
`0ea1a84`. The 10 commits that make up today (oldest→newest):

1. `445f963` Components Phase 3b — execute engine for installer-based components (.NET/vcredist)
2. `1955f43` Components Phase 3b — auto-close installer sessions + cleanup
3. `c4399ce` Components — install win7/winXP via pure `set_windows` instead of N/A
4. `ce6561d` imagefs — bundle full ffmpeg-8 libs for winedmo video decode *(was the leftover "PENDING #2")*
5. `fe8e74d` HUD — live D3D API label (VKD3D vs DXVK) + tap overlay to toggle FPS orientation
6. `19ec967` HUD — tap overlay to toggle orientation live; dropped the settings dropdown
7. `de71493` Vulkan — make Advanced Vulkan / Graphics Driver dialogs scrollable
8. `16dc463` docs — components N/A backfill + quartz device-test (this log)
9. `4b9b0ad` Controls — overlay-opacity slider moved into in-game side menu (live, true 0–100 %)
10. `0ea1a84` **Components fix (new today)** — see below

**`0ea1a84` Components installer — two bugs fixed** (branch `fix/components-copy-and-installed-persist`,
rebased+ff to main, deleted; CI `28137352729` ✅ green; **device-untested**):
- **`copy_dll` glob was broken.** `copyMatching` built its regex as
  `Regex.escape(pattern).replace("\\*",".*")`, but Kotlin's `Regex.escape` uses `Pattern.quote`
  (`\Q…\E`), so a literal `"*"` file_name pattern compiled to `^\Q*\E$` — matching a file *named* `*`
  (nothing). The `*` components (`atmlib`/`devenum` + the pre-baked win7-SP1 set) set their DLL
  override but **never copied the DLL**. Fix: `pattern.split("*").joinToString(".*"){Regex.escape(it)}`
  → proper glob semantics. (`ComponentInstaller.kt`)
- **"Installed" status didn't persist.** It lived in an in-memory `remember{}` set, so it reset on
  every sheet close/reopen. Now persisted per container in SharedPreferences `component_installs`
  (key `c<id>`): loaded on open, written on each successful install. (`ComponentsSheet.kt`)

**Main artifact build:** triggered run **`28138274652`** (CI Build, artifacts only, `main`).
**Next:** device-test the glob fix + persisted-installed status (root bridge) → then cut 1.6.

## 2026-06-24 — Components: backfilled 15 "N/A" components + device-tested registration

**Backfill (winlator-contents `1f6eb72`).** The catalog had **17 components stuck at N/A**
(`needs-upstream`/`pending-manual`) because their source files were never mirrored. User supplied
the missing Microsoft files (`windows6.1-kb976932` Win7-SP1 x64+x86, ~1.5 GB; `powershell-wrapper.zip`),
covering **15 of 17**.

- The app has **no runtime cab engine** (verified: `ComponentInstaller`/`ComponentExecInstaller`
  handle neither `cab_extract`/`get_from_cab` nor `register_dll`; Phase 3b = installer-exec, not cab).
  The 12 already-working cab components were **pre-baked build-side** — so I followed the same method.
- **Pre-baked** the DLLs with `cabextract` straight out of the SP1 packages (all validated PE/`MZ`),
  packaged each as `<name>__libs.tar.xz` (`win32/`+`win64/` layout, gdiplus = 1.1.7601.17514),
  uploaded all **15** to release `system-libraries-v1` (~10 MB total — not the 1.5 GB raw `.exe`s,
  which would never install).
- **Rewrote** each component in `components.json` to the proven file-drop pattern
  (`archive_extract` + `copy_dll`(+`override_dll`)) — exactly like `devenum`/`riched20`; dropped the
  unsupported `register_dll` (native override inherits Wine's builtin COM registration). PowerShell
  repackaged into the same convention (its `powershell_core` dep was already `ready`).
- Catalog tally now **ready 112 / N/A 2**. Still N/A: `art2k7min` (needs AccessRuntime2007.exe),
  `vbrun6` (needs VB6 SP6 runtime).
- **No app rebuild needed:** `ComponentCatalog` fetches the catalog live (no cache) from
  `raw.githubusercontent.com/.../main/components.json`; installed builds see the 15 within minutes.

**Device test — quartz registration CONFIRMED (root bridge, `com.winlator.banner`).** Premise held:
every container prefix already has builtin Wine quartz fully COM-registered (**48** `quartz.dll`
InprocServer32 refs, FilterGraph CLSID `{e436ebb3-…}` + DirectShow Filters category present). Did a
reversible end-to-end install on `xuser-2` "P11 ARM" (backups `*.bak-comp`): native MS `quartz.dll`
→ system32 (1,572,352 B) + syswow64 (1,328,128 B), both `MZ`; inserted `"quartz"="native,builtin"`;
**48 CLSIDs still resolve to quartz.dll (now the native DLL), FilterGraph CLSID intact**. Only the
runtime-load under Wine/arm64ec (launch a DirectShow/FMV title) is left for a user-side check.

## 2026-06-24 — In-game overlay-opacity (controls) reworked + moved to side menu

On-screen controls "overlay opacity not working" → fixed + relocated. Was: draw curve
`0.5+0.7*opacity` (dead top ~29 %, never faint), `setOverlayOpacity()` never `invalidate()`d, editor
hardcoded 0.6. Now **linear 0–100 %** (0 % = fully invisible; accent-stroke alpha floors scaled with
opacity), live `invalidate()`, and the slider **moved from the Input-Controls profile screen into the
in-game side menu (Controls tab)** so it tunes the visible overlay live (`XServerDrawerState`
`overlayOpacity` + `onOverlayOpacityChange`; activity applies + persists). DEFAULT 0.4→0.75 (matches
old look under the new mapping). Branch `feat/ingame-overlay-opacity` `d3f2a8b`, compile CI
`28135045851` ✅ green. Next: device-test → merge for the next release.

## 2026-06-23 — Components installer: catalog + mirror DONE (app side next)

Building a **Components installer** for container settings — browse + install Wine dependencies
(mono, gecko, dotnet, vcredist, d3dx, …) into a container's prefix, the same set BannerHub/GameHub
offer (Bottles "Type 6 — System Libraries", 114 components).

- **Mirrored** all components' binaries to a new release **`system-libraries-v1`** on
  `The412Banner/winlator-contents` — **92 assets**, deduped by URL (shared payloads like the Win7-SP1
  packages referenced once, not per-component). Each asset named after its component.
- **6 not mirrored** (manual re-source list at `/sdcard/Download/winlator-components-needed.txt`):
  the 3 huge **Win7-SP1 platform-update** packages (shared by 14 components → referenced upstream) and
  3 dead/timed-out sources (`art2k7min`, `powershell`, `vbrun6`).
- **`components.json` committed + live** on winlator-contents
  (`raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json`) — 114 components
  with full **Bottles-format install steps**, URLs rewritten to the mirror; `status` per component:
  **ready 97 / needs-upstream 14 / pending-manual 3**.
- **App side: Phase 2 + Phase 3a DONE & MERGED to main** (`4c732b8`, build `28072511822`).
  - **Phase 2** (`91ca6a3`): a "Components" browser in the Win Components tab (`ComponentsSheet`) +
    `ComponentCatalog` (reads the live components.json) + `ComponentInstaller` (file-drop Bottles
    steps → `system32`/`syswow64` + DLL overrides via `WineRegistryEditor`). **Device + root-verified:**
    installed `d3dcompiler_43`/`_47` — correct 64-bit→system32 / 32-bit→syswow64 + overrides set.
  - **Copy hardening** (`4c732b8`): `copy_dll` constrains source to the matching arch sub-tree.
  - **Phase 3a (pre-bake, no app change):** extracted the cab contents build-side with `cabextract`,
    hosted 12 components as `<name>__libs.tar.xz` on the `system-libraries-v1` release, and rewrote
    their catalog steps to file-drop. **22 components now installable** (10 file-drop + 12 pre-baked
    cab: d3dcompiler_42/46, xinput, xaudio2.7, msxml6, atmlib, riched20, vcredist6, winhttp, …).
  - The app reads components.json at runtime, so catalog updates are live without an app rebuild.
- **Still to do — Phase 3b:** the execute engine (`install_exe`/`install_msi` via launching the
  container session) for the +54 .NET / vcredist runtimes. Plan in memory `project_bannerlator_components_installer`.

---

## 1.6 RELEASE MANIFEST (in progress, since tag `1.5` / `dc74f67`) — NOT yet released

Everything queued for the next release:

**Merged to `main`** (device-confirmed):
1. On-screen dpad/stick multi-touch freeze fix (`fba6080`, merged `d1356d8`) — GitHub issue #5, reporter-confirmed.
2. In-app File Manager batch (`d086990`→`5521e0f`, +`ca26466`) — data-loss paste, silent Run, working dir, off-thread listing, copy-into-self guard, copy progress bar, PTR/scroll/file+exe icons, system-Back-up-one-dir, Run-executes-exe-in-container (`core/WinePath.kt`).
3. Per-game (shortcut) overrides for Renderer + Frame-Gen engine + FPS limiter (`08878be`).
4. Frame gen starts OFF in-game on every launch (`a669b8b`).

5. Standalone FPS limiter — guest-side X11 Present IdleNotify pacing (`bd990b2`) + lsfg≥2 guard (`4909549`); caps fps with Off / bionic-fg / lsfg-vk, both host renderers, live. ✅ merged to main (`a2ebd35`), GameNative credit (`0eadf16`).
6. Advanced Vulkan present settings now actually apply (native/presentMode/filter/swapRB) + renderer-dropdown label/gear fix. ✅ merged to main (`dcd9d47`).

**In progress (before 1.6, user's call)** — branch `feat/layer-download-menu`:
7. Compatibility-layer download menu rework — adrenotools-style cards, cloud opens the sheet directly, install-from-file in the sheet, Wine/Proton chips, in-use marker, byte-accurate install bar. See the dated section below.

Next: finish the download menu → merge → cut 1.6 (bump versionCode from 23 + splash).

---

## 2026-06-23 — Compatibility-layer download menu rework (branch `feat/layer-download-menu`, in progress)

Reworked the per-component download entry points into an adrenotools-style menu. The backend
(`ContentDownloadSheet` + `ContentsManager` + one remote `contents.json`) already covered all five
layers — the work is front-end consolidation + a real install bar. Confirmed design (HTML preview
first, then implemented):

- **Cloud icons replace the gears** on every layer (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore);
  the cloud opens the download sheet **directly**. "Install from file" moved **into** the sheet header.
- **Adrenotools-style rows** — flat rows, `Memory` icon, name + "In use"/"Installed"/desc subtitle,
  trailing `CloudDownload`; chips restyled to the adrenotools `SourceChip` look. **Wine/Proton chips**
  split the compatibility-layer sheet; the others are single-type.
- **In-use marker** for the container's current version (Wine/Box64/FEXCore). Author/size are NOT in
  the manifest (`ContentProfile` has only type/verName/verCode/desc) — would need a manifest extension
  or a HEAD request; deferred.
- **Two determinate 0→100 bars** — blue "Downloading" (byte-accurate) and green "Installing", now also
  **byte-accurate**: `TarCompressorUtils` got a `CountingInputStream` + `OnReadProgressListener` and an
  `extract(…, total, listener)` overload reporting `bytesRead/total` off the compressed stream
  (single-pass, denominator = downloaded .wcp size); `ContentsManager.extraContentFile` got a matching
  overload; the sheet feeds it monotonically (ignoring the brief XZ-probe before the ZSTD pass).

VEGAS and the adrenotools GPU-driver downloader are left untouched. Kept as a centered `Dialog` for now
(bottom-sheet vs centered to be decided on device). First impl device-tested ("looks good").

**Build status (2026-06-23):** UI restyle + cloud-direct + file-in-sheet + in-use = `f4e551e` (CI
`28056314348` ✅). Byte-accurate install bar = `f9485ef` (CI **`28057297317`** — final combined build).
**⏸️ Resume:** verify `28057297317` green → download standard APK → device-test (cloud opens sheet
directly, adrenotools cards, Wine/Proton chips, install-from-file, in-use marker, real install bar) →
decide bottom-sheet vs centered + whether to add author/size → merge to main → cut 1.6.

---

## 2026-06-23 — Standalone FPS limiter (guest-side IdleNotify pacing) DEVICE-CONFIRMED ✅

Commit `bd990b2`, branch `feat/standalone-fps-limiter`, CI `28043133606` ✅ green.

The reworked limiter — guest-side X11 Present `IdleNotify` pacing in `PresentExtension`
(GameNative/Ludashi-3.1 mechanism: delay IdleNotify → DXVK blocks waiting for a free
buffer → the GUEST throttles), decoupled from the frame-gen layers — **caps fps in all
three FG modes: Off / bionic-fg / lsfg-vk.** Engine-agnostic, all-API, live in-game toggle.
This succeeds where the earlier host-side nanosleep pacer (`f8d7598`) failed (that one only
dropped frames at the compositor; the guest ran full-speed).

**✅ Confirmed on BOTH host renderers** — all 3 modes (Off / bionic-fg / lsfg-vk) cap fps on
the OpenGL host renderer AND the Vulkan host renderer.

**lsfg-mult≥2 guard wired (commit `4909549`, CI `28046025979`).** `lsfgGovernsFps()` returns true
when engine=lsfg + FG enabled + multiplier≥2; `applyFpsLimit()` clamps to 0 in that case, and
`reapplyFpsLimit()` runs from the lsfg branch of `onBionicFgConfigChange` so the guard engages the
moment the multiplier crosses 2. Rationale: lsfg paces itself when multiplying — layering our
IdleNotify limiter on top double-paces the stream (clamps the panel to the limiter value, kills the
FG gain, wastes GPU). Unaffected: bionic-fg, Off, and lsfg at 1× still cap.
> ⚠️ **SUPPORT NOTE:** if users report "the FPS limiter doesn't work / no cap" on **lsfg-vk**, this
> guard is the intended cause — the limiter is deliberately disabled while lsfg-vk multiplies
> (mult≥2). Documented in-code at `lsfgGovernsFps()`. Not a bug.

Remaining: guard CI green → merge `feat/standalone-fps-limiter` → main for 1.6.

---

## 2026-06-22 — lsfg-vk live reload CONFIRMED ✅ + Off→passthrough fix + engine badge + Task-Manager-on-Vulkan bug (diagnosing)

Session driven by live device questions ("which FG engine is running right now?").

**1. lsfg-vk 3× + LIVE RELOAD confirmed working (supersedes the 2026-06-21 "no live reload" finding).**
Probed the running game (`DOOMBLADE.exe`) on device: `liblsfg-vk.so` mapped into the game proc, env
`ENABLE_LSFG=1` / `LSFG_PROCESS=bannerlator-lsfg` / `LSFG_CONFIG=…/home/xuser/.config/lsfg-vk/conf.toml`,
`Lossless.dll` present. Logcat showed `lsfg-vk: Rereading configuration, as it is no longer valid.` →
`Reloaded configuration … Multiplier: 3` → `lsfg-vk-framegen: Entering Device::Device` — i.e. the
mtime-watch → OUT_OF_DATE → swapchain-recreate reload mechanism (GameNative fork `.so`) DOES fire on our
DXVK→vkd3d→wrapper_icd→Turnip stack now. Panel present rate ~138–143 fps on a 144 Hz panel = base ~46 × 3.
DXVK HUD correctly shows the BASE rate (~46) because lsfg-vk inserts frames downstream of DXVK's counter
(HUD≠panel is expected, and is itself proof FG works). Two conf.toml files exist: live
`home/xuser/.config/lsfg-vk/conf.toml` (read by the layer) and a stale `home/xuser-1/.config/bionic-fg/conf.toml`
(ignored by lsfg; its `fps_limit` field isn't an lsfg option). Minor cleanup candidate.

**2. Off-bug found + fixed.** Installed APK (`7f7ffb5`) predated the Off fix (`80e238a`), so in-game "Off"
wrote `multiplier = 2` (`Math.max(2,0)`) → still 2× frame gen. `80e238a` writes `1` (true passthrough).
Built off-fix APK (run `27941385132`, label `1.3-lsfg-offfix`, ✅ green). PROVEN on device by live-editing
the running conf.toml `multiplier 2→1`: reload fired (`Rereading` → `Multiplier: 1`), FPS dropped to native
~21–27. So `multiplier=1` = genuine off.

**3. Engine badge in in-game FG drawer (commit `740e779`).** Per user, replaced the standalone
"Frame Generation (AI)" header with a title + engine badge row — `Frame Generation  [● bionic-fg]` (green
dot = layer running this session; swaps to `lsfg-vk`; "Off" when disabled). No double labeling (user picked
the "Engine badge" layout). Plumbed `XServerDrawerState.frameGenEngine` ← `container.getFrameGenEngine()`,
wired in `XServerDisplayActivity` next to the other FG drawer-state setters.

**4. Task Manager reports nothing on the Vulkan host renderer (SAME container works on OpenGL). Diagnosing.**
Game runs fine; `winhandler.exe` (the process-list backend) is alive; no app crash. The new off-fix build's
Task Manager refreshes on a render-independent 1s timer and STILL shows empty on Vulkan → ruled out the
UI-tick/copyArea theory. `setupTmCallbacks`/listener registration are NOT renderer-conditional in source, so
nothing intends to disable it on Vulkan. Added WinHandler diagnostic logging (commit `e75d1d4`, tag
`WinHandlerTM`): logs INIT handshake, each `listProcesses` send + sendPacket result, every received request
code, and `GET_PROCESS` replies. One Vulkan run with Task Manager open will split it: `GET_PROCESS` arriving
but UI empty → Compose/StateFlow update problem on Vulkan; no `recv` at all → guest not replying / INIT never
happened. NOT yet root-caused.

**Builds:** off-fix `27941385132` (`1.3-lsfg-offfix`) ✅; logging-only `27943043968` (`1.3-tmlog`) ✅;
combined `27943884565` (`1.3-tmlog-badge` = off-fix + WinHandler logging + engine badge) — in progress.
Branch `feature/lsfg-vk-engine` tip `740e779`, pushed, NOT merged.
**NEXT:** deliver combined APK to `/sdcard/Download` + arm `WinHandlerTM` logcat → user opens Task Manager on
Vulkan once → read logs to root-cause → fix → merge to main.

---

## 2026-06-21 (night) — lsfg-vk DEVICE TEST: works (2×) but live in-game reload does NOT on our stack ⏸️ RESUME

Installed the test APK (`Bannerlator-1.3-lsfg-vk-standard.apk`, testkey, updates over current), imported a
`Lossless.dll`, selected lsfg-vk in a container, launched DOOMBLADE.

- ✅ **lsfg-vk loads + runs on our Turnip/Proton stack** (GameNative fork `.so` `93fa20bb`). Log
  `/sdcard/Download/lsfgvk_ingame_test.txt`: `Loaded configuration for bannerlator-lsfg` / `Shaders extracted` /
  layers init / AHB + swapchain contexts. **2× frame gen confirmed** (DXVK 39.4 → overlay 78). Opt-in
  `ENABLE_LSFG` gate, conf.toml driving, and `LSFG_PROCESS=bannerlator-lsfg` all work.
- 🐞 **Bug found + fixed (commit `80e238a`):** the in-game "Off" = drawer multiplier 0 (frame-gen stays
  enabled), and the callback did `Math.max(2,0)=2` → forced 2× on Off. Fixed to `mult>=2 ? mult : 1`
  (passthrough). Only matters on relaunch though, because…
- ❌ **Live conf.toml reload does NOT fire on our stack (definitive).** Bypassed the app entirely: `sed`'d the
  running game's conf.toml to `multiplier=1`, confirmed mtime changed → **zero `Rereading configuration` lines,
  FPS stayed 2×** (capture fresh through 19:36, not a gap). Then a fullscreen toggle (swapchain recreate) →
  still no change. So GameNative's mechanism (layer returns `VK_ERROR_OUT_OF_DATE_KHR` on conf change → DXVK
  recreates swapchain → layer re-reads) is **not propagating through DXVK → vkd3d → wrapper_icd → Turnip**.

**Decision pending (A vs B):**
- **(A, recommended)** ship lsfg-vk as **launch-time** config: restore the per-container Multiplier + Flow
  control (was in `1997a55`, removed in `7f7ffb5`); in-game drawer hides/labels lsfg FG controls as
  "relaunch to apply"; bionic-fg keeps its working live in-game control.
- **(B)** deep-dive why OUT_OF_DATE doesn't recreate/reload here (instrument a debug layer; uncertain).

**Resume state:** branch `feature/lsfg-vk-engine` @ `80e238a` (pushed, NOT merged). Build works; engine
selector + gray-out + DLL picker + lsfg-vk 2× all functional. Only the lsfg live in-game tuning is the gap.

## 2026-06-21 (evening) — lsfg-vk as a SECOND, selectable frame-gen engine (recon → spike → integration → in-game live)

New feature on branch **`feature/lsfg-vk-engine`** (off `main`; NOT merged): add **lsfg-vk** (Lossless
Scaling FG, PancakeTAS lineage) alongside the existing **bionic-fg** so users pick the engine per
container. User supplies their own `Lossless.dll` (we bundle nothing proprietary).

**Recon (3-repo lineage):** lsfg-vk source = `FrankBarretta/lsfg-vk-android@b55b182` (Android AHB port);
built by `The412Banner/LLS` CI (NDK 27, `-DLSFGVK_ANDROID_WINE=ON`, 2-line color-fix patch). Ludashi-plus
itself has NO lsfg-vk (it dropped the feature). LLS run `25313482636` has a clean prebuilt artifact (NO
`libc++_shared` dep — the libc++ blocker was only the old dead APK `.so`).

**Device spike (✅ SUCCESS):** staged the LLS prebuilt `.so` + manifest + the user's `Lossless.dll` into a
container's imagefs, env `LSFG_LEGACY=1 LSFG_DLL_PATH=… LSFG_MULTIPLIER=2 BIONIC_FG_DISABLE=1`. After fixing
my guest-relative DLL path → full Android path (guest is NOT chrooted), **DOOMBLADE ran with lsfg-vk doing
2× frame gen** (DXVK 39.3 → overlay 79). DEFINITIVELY confirmed lsfg-vk (not bionic) via live `/proc/*/maps`:
`liblsfg-vk.so` mapped r-xp in the game procs, `libbionic_fg.so` mapped in zero. lsfg-vk = plain implicit
layer, NO wrapper-ICD hack needed (unlike bionic-fg). ⚠️ it HARD-EXITS (bricks the container) if it can't read
the DLL → the feature must gate on a valid DLL.

**In-game LIVE control (GameNative recon):** stock lsfg-vk reads config once. GameNative makes mult/flow apply
mid-game by rewriting `conf.toml` → their **forked layer** watches the file mtime in its present hook → returns
`VK_ERROR_OUT_OF_DATE_KHR` → game recreates swapchain → layer re-reads config (the ~100ms "pause" the user sees
= that rebuild). NO SIGSTOP, no app-side swapchain call. So we **re-vendored GameNative's fork** (`.so` md5
`93fa20bb`, has the `Rereading configuration` mtime-watch) and drive via **conf.toml**, not `LSFG_LEGACY` env.

**Integration (commits `a8974d9`,`1b96cb4`,`1997a55`,`7f7ffb5`):**
- Layer staged opt-in: `assets/lsfg-vk/{liblsfg-vk.so,manifest}` + `ImageFsInstaller.installLsfgVkLayer()`;
  added `enable_environment ENABLE_LSFG=1` to the manifest so the on-by-default upstream layer can't brick
  other containers (loads only when a container selects lsfg-vk).
- Launch wiring (`XServerDisplayActivity`): engine==lsfg → `ENABLE_LSFG=1` + `LSFG_CONFIG=<home>/.config/lsfg-vk/conf.toml`
  + `LSFG_PROCESS=bannerlator-lsfg` + `writeLsfgConfig()` ([global].dll + [[game]] mult/flow/present=fifo),
  gated on the imported DLL existing; else bionic-fg path unchanged. Mutual exclusion = one engine's enable env.
- Data model (`Container`): `getFrameGenEngine/setFrameGenEngine/isLsfgEngine` ("off"/"bionic"/"lsfg"; default
  migrates legacy `frameGenEnabled`).
- UI: container FG control = engine selector ONLY (Off/bionic-fg/lsfg-vk); **lsfg-vk grayed out until a
  `Lossless.dll` is imported** (`LabeledDropdown` gained `disabledOptions`). DLL picker at the bottom of
  Settings (SAF → **copies into `filesDir/lsfg-vk/Lossless.dll`**, loads from the copy).
- **Unified in-game control:** the single in-game multiplier toggle + flow slider drive WHICHEVER engine the
  container runs — `onBionicFgConfigChange` branches to `writeLsfgConfig` for lsfg (live reload via the fork's
  mtime-watch); drawer activated for lsfg containers. No per-container mult/flow control.

**APK signing:** all builds now signed with the AOSP **testkey v1+v2+v3** (`keystore/testkey.p12`, commit
`e09ac71`) so releases/updates install over previous installs (one-time uninstall on first testkey build).

**Build:** test APK run `27920491173` @ `7f7ffb5` (label `lsfg-vk-ui-test3`) IN PROGRESS. ⚠️ Dispatch-race
gotcha hit again — verify `git ls-remote` tip == local before `gh workflow run`. **STILL UNVERIFIED ON DEVICE:**
GameNative fork `.so` loading on our Turnip stack + the live conf.toml reload mid-game (the test APK verifies).

## 2026-06-21 (later) — sticky sync-failure force-disable fix + new `.so` `9136405c` STAGED/SWAPPED

Found (via Jason's PR #6 `layer.cpp:1093` follow-up) that the runtime auto-disable wasn't sticky:
`noteFenceTimeout` stored the kill in `conf.enabled`, which the hot-reload path overwrites wholesale
(`st.conf = newConf`) — so any conf.toml touch (notably the in-game flow slider) silently re-armed
framegen on an ICD path already proven sync-incompatible. **Fix:** new sticky `SwapState::framegenForceDisabled`
that hot-reload never clears; QueuePresent gate honours it; clean re-attempt point stays a swapchain
recreate. Fork `The412Banner/bionic-fg`@`bannerlator-android-wrapper-icd-fixes` commit `c861d8c`
(4th unpushed commit ahead of PR remote `ac2f5c0`). App branch `feature/bionic-fg-pr-followups`
`6807e83` (patch regen 597→608L, applies clean).

**Build gotcha logged:** first dispatch (run 27916226393) checked out the STALE tip `56c6735`
(dispatched a beat too fast after push → byte-identical `4b99b2d1`, no change). Re-dispatched
27916284710 on confirmed remote tip `6807e83` → **new `.so` md5 `9136405c`**, `will NOT re-enable`
log string confirmed compiled in. RULE: after `git push`, verify `git ls-remote` tip == local before
`gh workflow run`, or the run may use the old ref.

**Hot-swapped onto device** (no reinstall — pure layer-internal change, no conf/JNI/ABI surface):
`imagefs/usr/lib/libbionic_fg.so` 4b99b2d1→`9136405c` (owner u0_a484, chmod 600); backups `.bak_c8e4`
(shipped) + `.bak_prev` (4b99b2d1). App force-stopped. Staged `/sdcard/Download/libbionic_fg_9136405c.so`.

⚠️ **Test scope:** the sticky-disable only ENGAGES on a sync-incompatible ICD (6 consecutive fence
timeouts). On the known-good Proton 11 + Turnip path framegen never force-disables, so the fix is
logic-verified by code; on-device this run is a **regression check** = confirm 2× still works + flow
slider still hot-reloads cleanly (i.e. my change didn't break the happy path). ⏳ awaiting user launch;
logcat → `/sdcard/Download/bionicfg_sticky_disable_test.txt`.

---

## 2026-06-21 — PR #6 review-followup `.so` (`4b99b2d1`) DEVICE-CONFIRMED ✅

Hot-swapped the new layer (`libbionic_fg_4b99b2d1.so`, build run 27915620310, fork HEAD `5f4fc03`)
into the installed app's imagefs — **no reinstall** — and tested on device. **All green.**

- **Frame gen 2× confirmed** (game "FOLLOW MY LIGHT", Screenshot_20260621-155950): DXVK base HUD
  **29.9 fps** → Banner overlay **60 fps** = 2.00×. Adreno 750, GPU 58%.
- **FPS-limiter pacing confirmed** (Screenshot_20260621-160008): in-game FPS Limiter "Limit FPS" ON,
  Max FPS = 30 → overlay ≈ **60–63 fps** (cap × mult). The relaxed clamp + new variance-aware
  pacing path work.
- **New container:** ran on **Proton 11.0-5-arm64ec** + Mesa Turnip v26.2.0 + zink (prior proofs
  were on older containers). Layer loads clean: `VK_LAYER_BIONIC_framegen Device created` →
  `SwapchainState provisioned 1280x720` → `FramegenContext ready mult=2 model=0
  graph=model0-full-of-chain`; config hot-reload flow 0.90→1.00 live.
- **CPU-temp HUD fix holds** (79.7 / 86.6 °C real values).
- **No app crash.** No FATAL/SIGSEGV/tombstone for `com.winlator.banner` or `libbionic_fg`. Display
  went `committedState OFF` at 15:59:41 (backgrounded); the ANR storm after 16:01 is
  `com.qti.diagservices` = AYANEO firmware nvkeeper/diag bug, unrelated. The Claude session is what
  died (known device-launch issue).
- **Benign noise (cleanup candidate):** `failed to load layer libVkLayer_LSFGVK_frame_generation.so:
  libc++_shared.so not found` — that's the *old* LSFGVK-named layer in the APK `lib/arm64`, not our
  layer. Our `VK_LAYER_BIONIC_framegen` (imagefs/usr/lib) loads fine. Strip-or-bundle-libc++ before
  the PR push.

Log `/sdcard/Download/bionicfg_pr_followups_test.txt`; `.so` staged `/sdcard/Download/libbionic_fg_4b99b2d1.so`.

**NEXT (now unblocked):** post the 3 drafted replies to Jason (1093/1334/manifest:17) → push fork
`5f4fc03` to PR #6 branch `bannerlator-android-wrapper-icd-fixes` (one clean batch) → build APK from
`feature/bionic-fg-pr-followups` for the app-side niceties (next reinstall). GN #1443 verify deferred.

---

## 2026-06-20 — 1.3 shipped public + upstream PR review cycle (in progress)

**1.3 released (public).** Repo flipped public (secret-scanned first), `Bannerlator 1.3` release
created (run 27878418873) with all 3 flavors; release notes credit xXJSONDeruloXx with links to
[bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) and [PR #6](https://github.com/xXJSONDeruloXx/bionic-fg/pull/6).
README updated to 1.3 (fixed standard package id `com.winlator.banner`, version line, added the
Frame Generation feature section).

**Upstream PR #6 opened and reviewed.** Fork `The412Banner/bionic-fg` branch
`bannerlator-android-wrapper-icd-fixes`. Jason (xXJSONDeruloXx) left 9 inline review comments;
all 9 answered. He's constructive and wants it in with refinements. Work plan (layer fixes land on
the fork branch → update the PR; app-side conf-key changes land on a new Bannerlator branch
`feature/bionic-fg-pr-followups`):

- **A — separate copy vs generated fence-timeout counters.** Real bug he found: the shared counter
  reset on copy-fence success, so generated-frame timeouts could never reach the disable threshold.
  **Done** (fork commit `4c259f8`): split into `copyFenceTimeouts` / `genFenceTimeouts`, each reset
  only on its own fence type.
- **B — timeout rework + split the generated-frame deadline from the sync-incompatibility recovery
  path.** **Done** (fork commit `345e35e`): frame-budget-scaled sync timeout (4× base interval,
  clamped 200 ms–1 s, else 500 ms), disable threshold 2→6, and a separate shorter generated-frame
  cadence deadline (~2× the output interval) that just skips a late frame without counting it as a
  sync failure. A+B compile-validated via CI.
- **C — fps_limit ergonomics:** justify/relax the 10–200 clamp + add an `fps_limit_enabled` bool so
  the value is remembered across toggles. (layer + app) — *todo*
- **D — optional even-pacing** of generated presents to `1s/(base×mult)` behind an opt-in flag,
  off by default. (layer + app) — *todo*
- **E — document** ENABLE+DISABLE precedence in the manifest (disable wins). Trivial. — *todo*

**Device-creation regression (his main concern) — direction changed after his reply.** The JNI/native
path is unchanged (`Device::create()`, owned + destroyed); only the Vulkan-layer path uses
`Device::wrap()` of the app's device. Jason confirmed **GameNative also uses the layer path**, so our
change reaches it too. Arch root cause: standalone mode runs a second VkDevice and hands frames across
it via `VK_QUEUE_FAMILY_EXTERNAL` transfers, which need a shared queue/timeline; a wrapper ICD bridges
each guest device to Turnip as a separate host context, so that cross-device sync never completes →
hang. Single-device avoids it by running interpolation on the app's own device (intra-device sync).

**Plan:** likely **no flag** — single-device is probably the correct layer default for both stacks.
**Critical next step: verify single-device on GameNative [PR #1443](https://github.com/utkarshdalal/GameNative/pull/1443).**
If it's clean there, make single-device the layer default; only add an init-time `single_device`
launch arg (it can't hot-reload — device is created at init) if GN regresses. Fork commits A–B are
held locally and not yet pushed to PR #6; push as one batch after the GN question is settled and our
stack is re-tested.

---

## 2026-06-20 — bionic-fg FRAME GEN + FPS LIMITER: merged to main, version → 1.3

**FPS-limiter pacing device-confirmed (Phase 4 complete).** The deferred pacer (built into
the bionic-fg Vulkan layer, `.so` md5 `c8e4b188`) ran on device with `fps_limit=30`: base
DXVK frames locked at ~30 while the on-screen overlay stepped 60 → 90 → 122 as the in-game
FG selector went 2× → 3× → 4× (i.e. on-screen = limit × multiplier). Proven on the OpenGL
host renderer (log `bionicfg_fpslimit_test.txt` + 5 screenshots). The pacer sits at the top
of `BionicFG_QueuePresentKHR`, gated `!st.inPresent && fpsLimit>0`, so it throttles only the
app's real frames; generated frames (presented with `inPresent` true) bypass it. Verified the
pacer `.so` is bundled in the shipped APK asset (`assets/bionic-fg/libbionic_fg.so` md5
`c8e4b188`), not hand-staged.

**Merged to main (`ddf46fb`).** Merged `feature/bionic-fg-framegen` (HEAD `f39b96a`) into main.
One conflict in `.github/workflows/build-bionic-fg.yml` (both branches had it) resolved by
keeping the feature branch's version (the one with the patch-apply step). Stale
`BIONIC_FG_UPSTREAM_REPORT.md` working-tree edit reverted (falsely said single-device crashes;
run6 disproved it). Feature branch kept until the upstream PR is cut.

**CI: artifacts build now produces all 3 flavors (`eb30d1b`).** Previous standard-only build was
a workaround for an OOM (exit 143) caused by packaging the ~588MB APKs in parallel.
`build-artifacts.yml` now runs `assembleStandardDebug assembleLudashiDebug assemblePubgDebug
--no-parallel --max-workers=1` with a larger Gradle heap (serialized packaging) and requires
all 3 uploads. Run 27877129792 confirmed green with all 3 artifacts.

**Version relabel → 1.3 (`9ee5cb2`, `90ce00b`).** Fresh-install Android "All files access"
permission screen showed `1.4-marcescene` (the APK `versionName`) under "Bannerlator Bionic".
Fixed: `app/build.gradle` `versionName "1.4-marcescene"→"1.3"`, `versionCode 20→21`; splash
`SplashScreen.kt` "V 1.2"→"V 1.3" (color unchanged — stays grey `0xFFAAAAAA`); about/main
`MainActivity.kt` stray "V 1.0"→"V 1.3". Build run 27877738210 label `1.3` dispatched on main;
standard APK to be delivered to `/sdcard/Download/Bannerlator-1.3-standard.apk`.

---

## 2026-06-19 — Vulkan/DXVK/vkd3d BLACK-SCREEN FIX (✅ both renderers device-confirmed)

**Symptom:** native Vulkan + DXVK(d3d8-11) + vkd3d(d3d12) rendered BLACK at full FPS on BOTH
host renderers; OpenGL/DirectDraw/D3D7 fine.

**Root cause:** marcescence shipped the native scanout machinery but left the AHB (DRI3 modifier
1255) present path UNWIRED, and the GL renderer had NO AHB->GL (EGLImage) sampling at all
(GPUImage textureId==0 -> black). Confirmed via device logcat (tag "Dri3": modifier 1255 ->
AHB path taken; pixmaps imported fine -> not an import failure).

**Fix (commit `7d5c9f8`, build label "vkfix3" run 27848179202):** ported proven wiring from
GameNative (utkarshdalal/GameNative, local ~/GameNative):
- `renderer/GPUImage.java` + `cpp/winlator/gpu_image.c`: GPUImage(int socketFd) now locks
  (valid getStride + virtualData) and gained EGLImage support (createImageKHR =
  eglGetNativeClientBufferANDROID + eglCreateImageKHR + glEGLImageTargetTexture2DOES). AHB
  allocated BGRA_8888 (matches X depth-32 / GL_BGRA -> correct colors). unlock-before-release.
- `xserver/extensions/DRI3Extension.java`: setDirectScanout(true) + getStride() width.
- `xserver/extensions/PresentExtension.java`: 3-branch present (Vulkan native+scanout=FLIP /
  Vulkan=COPY via onUpdateWindowContentDirect / GL+SHM=copyArea); relaxed depth 24<->32.
- `XServerDisplayActivity.setupUI` + `VulkanRenderer.setInitialNativeMode`: wired the
  previously-dead Vulkan toggles (native / presentMode / filterMode / swapRB).

**Device results (vkfix3):** ✅ OpenGL host renderer (native Vulkan 1432fps + D3D12/vkd3d
1748fps, correct colors). ✅ Vulkan host renderer (native Vulkan 1449fps, correct colors).

APKs delivered: `/sdcard/Download/Bannerlator-vkfix3-standard.apk` (md5 eebfe339…),
`-pubg.apk` (md5 a7c0acb3…).

---

## 2026-06-19 (PM) — Native Rendering toggle: device-tested + HUD-freeze fix

User tested the previously-untested Native Rendering+ toggle on the Vulkan host renderer
(AIO Graphics Test, native-Vulkan cube). Two findings:

**1. Windowed content stretches/distorts — EXPECTED, not a bug.** With the graphics test in a
*window* (sub-screen), enabling Native Rendering blits the active swapchain straight to the full
device surface, stretched (LUNARG cube visibly squished). Direct scanout (`onUpdateWindowContent`
FLIP branch → `nativeScanoutSetBuffer`) has no aspect-correct dst path for sub-screen windows;
the aspect-preserving letterbox (`ViewTransformation`, `Math.min`-based) only applies on the
copyArea path. ✅ With the test app **maximized to fullscreen**, native rendering renders the cube
correctly proportioned (FPS still climbs 582→743→…). So for real fullscreen games — the actual use
case — native rendering is correct. Windowed-distortion is a known limitation, not release-blocking.

**2. Perf HUD freezes in Native Rendering — FIXED (commit `f724ec2`).** When Native Rendering was
on, the horizontal perf HUD bar (Vulkan|DXVK|CPU|GPU|…|FPS) froze — values stopped updating while
the game kept animating. Root cause: commit `779967a` wired `hudFrameTick` (which drives
`frameRatingHorizontal.update()`, `XServerDisplayActivity.java:1345`) only into
`onUpdateWindowContentDirect` (the COPY present path). Native rendering uses the FLIP/scanout path
(`PresentExtension.java:154` → `VulkanRenderer.onUpdateWindowContent`), which never called it. Fix:
added `if (hudFrameTick != null) hudFrameTick.accept(window.id);` in the scanout-delivered branch
of `onUpdateWindowContent` (`VulkanRenderer.java:495`), mirroring the COPY path — ticks once per
presented game frame in native mode.

**Build:** `build-artifacts.yml` run `27852720105` (artifacts-only, no release, APK label `hudfix`),
triggered off `main` @ `f724ec2`. ⏳ standard APK to be dropped in `/sdcard/Download/` when green;
HUD fix in native mode still ⏳ device-unconfirmed.

**Next:** device-confirm HUD ticks (and shows a sane FPS — native mode pauses X-side rendering) →
then cut a tagged release (pick a real version; vkfix3/hudfix are just build labels). Cleanup:
graphicsDriverConfig has two competing dialog formats writing the same field.

---

## 2026-06-19 (PM) — New neon gamepad launcher icon (corner-clip fix)

User supplied a new icon (neon gamepad + magenta chevron + white L-bracket + corner stars on
black, white rounded border) — `/storage/emulated/0/Download/ADM/file_…588.jpg`, 1254×1254 — and
reported the previously-installed icon had its **border corners clipped** by the launcher's
round/squircle mask (device screenshot 20260619-194013, drawer): that old icon was **full-bleed**
(art edge-to-edge) so adaptive masks cut the corners.

**Done (commit `19d62f8`, all 15 files = 5 densities × ic_launcher + ic_launcher_round + adaptive
foreground):**
- Legacy `ic_launcher.png` / `ic_launcher_round.png` (mdpi 48 … xxxhdpi 192) = full image, exact.
- Adaptive foreground (mdpi 108 … xxxhdpi 432) = full art fit into the **safe zone** (~66% of
  canvas, centered, transparent pad) so the launcher mask only ever trims the black margin — the
  white border + corner stars stay fully visible under ANY mask shape. Generated with ImageMagick.
- Adaptive background was already `@color/ic_launcher_background` = `#000000` (matches art bg) → no
  change needed; seamless (image black bg blends into adaptive black).
- No per-flavor icon overrides → shared `main/res` applies to all 3 flavors (standard/ludashi/pubg).
- User explicitly chose "full white border visible" over a bigger near-full-bleed (88%) variant.

**Build:** `build-artifacts.yml` run `27853329322` (artifacts-only, label `neonicon`, off `main` @
`19d62f8`). ✅ standard APK delivered `/sdcard/Download/Bannerlator-neonicon-standard.apk` (md5
`13056a0e2845f56ca34b00405abd3afb`). ⏳ icon device-unconfirmed (note: Android caches launcher icons
— reboot / clear launcher cache if old clipped icon persists).

---

## 2026-06-19 (PM) — 🏁 RELEASE 1.2 + README features/download button

**Released Bannerlator 1.2** (`release.yml`, run `27853787348`): tag `1.2`, marked **Latest**,
non-prerelease, 3 flavor APKs attached. Standard APK → `/sdcard/Download/Bannerlator-1.2-standard.apk`
(md5 `e5d5689ecf4b9b1a91596d70658a752f`). `release.yml` inputs = `release_tag` / `release_title` /
`release_number` / `release_notes` (publishes `make_latest:true`, supersedes 1.1).

1.2 changelog (commits since `1.1` tag): Vulkan/DXVK/vkd3d black-screen fix (`7d5c9f8` + lead-ups
`b7d4f3a`/`c4d252c`), Native Rendering+ toggle wired (`779967a`), HUD-freeze fix on FLIP path
(`f724ec2`), GL-only effects greyed out on Vulkan (`ba06bc3`/`df3a5c7`), DXVK/VKD3D/Vegas version-list
refresh (`00a2544`), new neon icon (`19d62f8`).

**Splash version** bumped `V 1.1` → `V 1.2` (`SplashScreen.kt:164`, commit `a598584`) BEFORE the
release so it shipped in 1.2.

**README** (commits `ae5d9b7` + `18eab3d`): added **✨ Full Features** section (7 grouped categories —
Windows compat / graphics layers / renderers / containers / games+input / UI+overlay / builds; every
item cross-checked against actual code, NO invented features like AI frame-gen which isn't in this
app); bumped Information-table version V 1.0→V 1.2; added a centered shields.io **Download button**
linking to `/releases/latest` + a "Download" entry in the nav row.

**GameNative render-fix credit** (commit `8792f6d`): expanded the README GameNative credit row to
state its rendering pipeline was the **reference used to fix/rewire the render options** (AHB present
path → Vulkan/DXVK/VKD3D on both renderers: GPUImage socket-buffer lock + EGLImage sampling, DRI3
direct-scanout, Present FLIP/COPY branches, Native Rendering+ scanout). Also appended a **Credits**
section to the **1.2 GitHub release notes** (`gh release edit 1.2`) crediting GameNative (utkarshdalal)
for the same, linking back to the README Credits.

**Next:** device-confirm HUD-tick + new icon on 1.2; cleanup graphicsDriverConfig's 2 competing
dialog formats.

---

## 2026-06-19 (PM) — bionic-fg frame generation: recon + branch `feature/bionic-fg-framegen`

New feature kicked off: integrate [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) (Android/
bionic Vulkan frame-generation layer, LSFG lineage — same engine GameHub ships as `libGameScopeVK.so`)
as **(a)** a per-container option and **(b)** a live in-game side-menu control.

**Author permission GRANTED** (xXJSONDeruloXx). Terms: (1) credit in README, (2) if source goes in
tree do it as a **git submodule** (his preference), (3) feedback/PRs welcome.

**Recon findings:**
- Guest Vulkan goes through a **wrapper ICD** (`wrapper_icd.aarch64.json` + `GALLIUM_DRIVER=zink` +
  `WRAPPER_*` at `XServerDisplayActivity.java:1823–1861`) bridging to the **Android bionic GPU driver**
  via **adrenotools** — exactly the context bionic-fg targets.
- Tree already has frame-gen groundwork: `app/src/main/cpp/lsfg-vk/` (stub CMakeLists, build excluded)
  + root `build-lsfg-android.sh`. bionic-fg = the bionic-targeted sibling.
- **All 3 CI workflows already use `submodules: recursive`** → adding the submodule needs NO CI change.
- In-game drawer (`XServerDrawerState.kt`) uses StateFlow+Runnable; Native Rendering toggle is a
  turnkey template for a Frame-Gen toggle. bionic-fg **hot-reloads its TOML** → in-game live control
  by rewriting the config (multiplier 0=off / 2–4× / model 0-1 / flow_scale).
- ⚠️ **Critical unknown:** does the wrapper expose a `VkSwapchainKHR` for the layer to hook, or does
  it AHB-export with no WSI swapchain? Resolve with a verification spike BEFORE building UI.

**Deliverable this session:** branch `feature/bionic-fg-framegen` created off `main`; full recon +
phased job task list written to **`BIONIC_FG_INTEGRATION_REPORT.md`** (Phase 0 honor-terms → 1 native
build → 2 spike/de-risk → 3 container setting → 4 in-game menu → 5 polish/release/give-back).

**Next on branch:** Phase 0 — add bionic-fg as a submodule under `app/src/main/cpp/bionic-fg` +
README credit; then the Phase 2 verification spike (gate the rest on it).

### ✅ Phase 0 DONE (2026-06-19) — author terms honored
- Added **bionic-fg as a git submodule** at `app/src/main/cpp/bionic-fg` (his preference), pinned at
  `4f71770`; new root `.gitmodules`. CI needs no change (all 3 workflows already `submodules: recursive`).
- **README credit**: added xXJSONDeruloXx / bionic-fg to the Credits table (frame-generation layer,
  in-tree as a submodule with permission) + a "Frame Generation (bionic-fg)" row in the upstream-stack
  table.
- ⚠️ Submodule has **no LICENSE** → carry to Phase 5.2 (ask author before bundling in a release).
- **Next:** Phase 2 verification spike — build `libbionic_fg.so`, hand-wire one container's env +
  `conf.toml`, launch a DXVK game, confirm via logcat whether the layer engages (wrapper exposes a
  `VkSwapchainKHR`?) BEFORE any UI work.

### ✅ Phase 1 DONE (2026-06-19) — native build
- `build-bionic-fg.yml` (standalone, NDK 26.1.10909125 + cmake 3.22.1, arm64-v8a/android-26). Run
  **27854824786 ✅** → artifact `bionic-fg-arm64` (1.65 MB) = `libbionic_fg.so` (ELF aarch64,
  Android 26, NDK r26b — matches our minSdk 26) + `VkLayer_BIONIC_framegen.json`. Workflow also added
  to `main` (dispatch-only/inert) since workflow_dispatch requires the file on the default branch.
- **Manifest insights (sharpen the spike):** layer is **IMPLICIT** (`enable_environment
  BIONIC_FG_ENABLE=1`); `library_path ../../../lib/libbionic_fg.so` → manifest goes in
  `…/share/vulkan/implicit_layer.d/`, .so in sibling `lib/`. Implicit layers are found via system
  dirs / `VK_ADD_IMPLICIT_LAYER_PATH` (NOT `VK_LAYER_PATH`, which is explicit-only). Hooks
  vkGetInstance/DeviceProcAddr → sits above the ICD.
- **Refined crux:** bionic `.so` CANNOT load in the glibc guest (box64/Wine) → must load **host-side**
  where the wrapper-ICD server runs Turnip via adrenotools. Spike must confirm (1) host loader honors
  the implicit layer, (2) a real `VkSwapchainKHR` exists to hook (vs AHB-export = nothing to
  intercept). Copy GameHub `libGameScopeVK` imagefs placement.
- Artifact staged for device spike: `/sdcard/Download/bionic-fg/{libbionic_fg.so,VkLayer_BIONIC_framegen.json}`.
- **Next:** Phase 2 spike (needs a device launch — log to crash-surviving `/sdcard/Download/*.txt`
  per the device-launch rule; hold the actual launch for the user).

### Phase 2 spike — runbook written + device recon (2026-06-19)
- **`BIONIC_FG_SPIKE_RUNBOOK.md`** written: full device steps (place `.so` in `imagefs/usr/lib`,
  manifest in `implicit_layer.d`, `BIONIC_FG_ENABLE=1` + `VK_LOADER_DEBUG=all` in container Env Vars,
  conf.toml at guest `$HOME/.config/bionic-fg/`, logcat→`/sdcard/Download/bionicfg_spike.txt`) +
  a decision table + cleanup.
- **Device recon (root bridge):** guest uses its **own glibc Khronos loader**
  `imagefs/usr/lib/libvulkan.so.1.4.315` and already loads **glibc** implicit layers — **MangoHud**
  (`VK_LAYER_MANGOHUD_overlay_aarch64`) + `libutil_layer` — from `usr/share/vulkan/implicit_layer.d/`.
  MangoHud's manifest is structurally identical to bionic-fg's (enable_environment, `../../../lib/…`,
  same proc-addr hooks) → **discovery works**.
- ⚠️ **KEY HYPOTHESIS:** our NDK/**bionic** `libbionic_fg.so` (links libandroid/liblog/Android
  libvulkan) **will not load in the glibc guest loader** → real path is a **glibc aarch64 build**
  (new Phase 1.5), mirroring how MangoHud + GameHub `libGameScopeVK` ship in imagefs. The spike's
  Test A is designed to confirm this fast (expect a `cannot open shared object`/`libandroid` load
  error), then pivot.
- Standard pkg confirmed `com.winlator.banner` (pubg `com.tencent.ig`); both installed.
- **Next (user):** run the spike launch per the runbook; report the log signals.

### Phase 2 spike ARMED on device (2026-06-19) — awaiting user launch
- Test workload = **DOOMBLADE** (user's choice; real DX11/DXVK game) in **container 2 "P10arm"**
  (`imagefs/home/xuser-2`, the ACTIVE container; arm64ec, DXVK 2.4.1+vkd3d, Turnip, FPS HUD on).
- Staged via root bridge: `libbionic_fg.so` → `imagefs/usr/lib/`, `VkLayer_BIONIC_framegen.json` →
  `imagefs/usr/share/vulkan/implicit_layer.d/`, `conf.toml` (multiplier=2) →
  `imagefs/home/xuser-2/.config/bionic-fg/`. All chown'd back to app uid `u0_a478`.
- Container 2 `.container` env vars **prepended** `BIONIC_FG_ENABLE=1 VK_LOADER_DEBUG=all`
  (backup at `.container.bak_bfg`).
- Logcat capture → `/sdcard/Download/bionicfg_spike.txt`.
- **REVERT if needed:** restore `imagefs/home/xuser-2/.container.bak_bfg`; rm the staged
  `.so`/manifest/`.config/bionic-fg`.
- ⚠️ Expectation: bionic `.so` likely fails to load in glibc guest loader (ABI) → then Phase 1.5
  glibc build. Spike confirms.

---

## How to Resume a Session

1. Read this file top to bottom
2. Find the **Current Job** section — it tells you exactly what to do next
3. Check the last commit hash matches what's on GitHub before continuing
4. Run CI after every commit. Do not continue to the next job until CI is green.

---

## Completed Work (Pre-Plan)

Full Jetpack Compose migration of all screens and dialogs is complete.  
See `COMPOSE_MIGRATION_REPORT.md` for the full record.

**Last migration commit:** `6dff28e`  
**Bug fixes after migration:**
- `85b1e57` — controller name text + drive letter dropdown fix
- `6537038` — External Controllers header text fix
- `3323810` — Customizable theme: 8 presets + HSV color picker (AppearanceScreen)
- `beee77b` — Appearance entry missing from nav drawer (AppDrawer hardcoded)

**Latest commit:** `beee77b`  
**Latest CI:** run `24568759383` — in progress at time of writing

---

## Feedback Fix Plan

Source: Developer feedback comparing v1.1 (old Java/XML) vs Compose version.  
8 issues identified. Listed in execution order (smallest/highest impact first).

---

### Job 1 — Help and Support (BROKEN)
**Status:** ✅ COMPLETE — commit `93d0326`, CI run `24569312463`  
**File:** `app/src/main/java/com/winlator/cmod/ui/AppDrawer.kt`  
**Problem:** `onClick = { /* TODO: open help URL or dialog */ }` — tapping does nothing  
**Fix:** Replace the TODO with a Compose `AlertDialog` containing:
- GitHub repo link: https://github.com/The412Banner/star-compose
- Issue tracker link
- A "Close" button
Or alternatively open a URL via `Intent(Intent.ACTION_VIEW, Uri.parse(url))`.  
**Effort:** 30 min  
**Commit message:** `fix: implement Help and Support dialog`

---

### Job 2 — About Dialog (MISSING CONTENT)
**Status:** ✅ COMPLETE — commit `d18cae6`, CI run `24569669122`  
**File:** `app/src/main/java/com/winlator/cmod/MainActivity.kt` — `AboutDialog()` at bottom of file  
**Problem:** Current dialog is 4 lines of plain text. Missing: app icon/logo, version name, Wine/Box64/FEX versions, credits list.  
**Fix:** Rebuild `AboutDialog()` as a proper Compose `Dialog` (not AlertDialog — needs more space) with:
- App icon (R.mipmap.ic_launcher_foreground)
- App name + version (read from `BuildConfig.VERSION_NAME` + `BuildConfig.VERSION_CODE`)
- Powered-by section: Wine, Box64, FEX-Emu, Turnip
- Credits section with contributor names
- Close button  
**Effort:** 45 min  
**Commit message:** `feat: rebuild About dialog with logo, version, credits`

---

### Job 3 — Container Creation Loading Indicator
**Status:** ✅ COMPLETE — commit `2e5f4a1`, CI run `24570142005`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailScreen.kt` — Save button / confirm action
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailViewModel.kt` — `saveContainer()` or equivalent
**Problem:** When user taps Save on a new container, it creates silently with no progress feedback. On slow devices this looks like a freeze.  
**Fix:**
1. Add `isCreating: StateFlow<Boolean>` to `ContainerDetailViewModel`
2. Set it true before container creation starts, false when done
3. In `ContainerDetailScreen`, show a full-screen semi-transparent overlay with `CircularProgressIndicator` + "Creating container…" text when `isCreating == true`
4. Disable the Save button while creating  
**Effort:** 45 min  
**Commit message:** `feat: add loading overlay during container creation`

---

### Job 4 — Settings Theme Mismatch (Dark Mode Toggle Broken)
**Status:** ✅ COMPLETE — commit `44a4bdb`, CI run `24571445525`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/theme/AppThemeState.kt`
- `app/src/main/java/com/winlator/cmod/ui/theme/ThemePreset.kt`
- `app/src/main/java/com/winlator/cmod/ui/theme/Theme.kt`
- `app/src/main/java/com/winlator/cmod/MainActivity.kt`
**Problem (two parts):**
1. `SettingsFragment` uses Light XML AppTheme while the rest of the app is dark Compose — mismatched look inside the Settings screen
2. The `dark_mode` SharedPreferences toggle in SettingsFragment has no effect on the Compose UI — `WinlatorTheme` always uses `darkColorScheme()`  
**Fix:**
1. Read `PreferenceManager.getDefaultSharedPreferences(this).getBoolean("dark_mode", false)` in `AppThemeState.init()` and store it as `isDarkMode: StateFlow<Boolean>`
2. Add a light variant to each `ThemePreset` (or use Material3 `lightColorScheme()` as the light base)
3. `AppThemeState.colorScheme` flow emits light or dark scheme based on `isDarkMode`
4. Register a `SharedPreferences.OnSharedPreferenceChangeListener` so toggling dark mode in Settings updates the flow in real time without restart
5. For SettingsFragment XML mismatch: set `android:theme="@style/Theme.AppCompat.DayNight"` on the fragment's parent or override the fragment background to match Compose surface color  
**Effort:** 1.5 hours  
**Commit message:** `fix: wire dark_mode preference to Compose theme + fix Settings appearance`

---

### Job 5 — Sort Shortcut List
**Status:** ✅ COMPLETE — commit `00dc6a5`, CI run `24571836336`  
**File:** `app/src/main/java/com/winlator/cmod/ui/screens/ShortcutsScreen.kt`  
**Problem:** No sort option — shortcuts always appear in filesystem order  
**Fix:**
1. Add a sort icon button in the top bar or a sort dropdown in the shortcuts screen
2. Sort options: Name A→Z, Name Z→A, Last Played, Container
3. Store selected sort in `ShortcutsViewModel` (persisted to SharedPreferences)
4. Apply sort to the `shortcuts` StateFlow before emitting  
**Effort:** 1 hour  
**Commit message:** `feat: add sort options to shortcuts list`

---

### Job 6 — Import/Export Container
**Status:** ✅ COMPLETE — commit `8477b65`, CI run `24572308670`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainersScreen.kt`
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainersViewModel.kt`
**Problem:** The old `ContainersFragment` had import/export container options. These are missing from the Compose version.  
**Fix:**
1. Add "Import Container" and "Export Container" options to the container long-press context menu (already has Duplicate/Delete)
2. Check original `ContainersFragment.java` (deleted) — refer to git history if needed, or find the logic in `ContainerManager.java`
3. Export: zip the container directory → write to Downloads or user-picked location via `ActivityResultContracts.CreateDocument`
4. Import: user picks a zip via `ActivityResultContracts.GetContent` → unzip to containers directory → reload list  
**Check ContainerManager.java for existing import/export methods first** — they likely already exist.  
**Effort:** 1.5 hours  
**Commit message:** `feat: add import/export container to containers screen`

---

### Job 7 — Add Shortcut from External Storage
**Status:** ✅ COMPLETE — commit `546d25e`, CI run `24577265773`  
**Files:** `ShortcutsViewModel.kt`, `ShortcutsScreen.kt`

---

### Job 8 — Shortcut List Layout Toggle (Grid / List)
**Status:** ✅ COMPLETE — commit `546d25e`, CI run `24577265773`  
**Files:** `ShortcutsViewModel.kt`, `ShortcutsScreen.kt`

---

## Execution Order

```
Job 1 → Job 2 → Job 3 → Job 4 → Job 5 → Job 6 → Job 7 → Job 8
```

Each job: implement → commit → push both remotes → trigger CI → wait for green → update this log → proceed.

---

## Build Log

| Job | Commit | CI Run | Result | Date |
|---|---|---|---|---|
| Pre-plan: Appearance drawer fix | `beee77b` | `24568759383` | ✅ green | 2026-04-17 |
| Job 1: Help and Support dialog | `93d0326` | `24569312463` | ✅ green | 2026-04-17 |
| Job 2: About dialog rebuild | `d18cae6` | `24569669122` | ✅ green | 2026-04-17 |
| Job 3: Container creation loading overlay | `2e5f4a1` | `24570142005` | ✅ green (fix: `67844d2`) | 2026-04-17 |
| Job 4: Dark mode pref + Settings theme fix | `44a4bdb` | `24571445525` | ✅ green | 2026-04-17 |
| Job 5: Sort shortcuts list | `00dc6a5` | `24571836336` | ✅ green | 2026-04-17 |
| Job 6: Import/Export container | `8477b65` | `24572308670` | ✅ green | 2026-04-17 |
| Job 7+8: Import shortcut + grid/list toggle | `546d25e` | `24577265773` | ✅ green | 2026-04-17 |

---

## Current Job

**→ ALL 8 JOBS COMPLETE** ✅

Last commit: `546d25e`  
Last CI: `24577265773` ✅ green

---

## 2026-06-28 — NIS device-test checkpoint (feat/deband-nis)

**Status:** Vulkan NIS ✅ device-proven by user ("it works on Vulkan"). OpenGL/GL NIS ⏳ device-test in progress (Adreno runtime-compile risk = the open question).

**NIS sharpness slider (resolved from committed source, this session):**
- CONTINUOUS on BOTH renderers — every value 0–100 is live (GL `XServerDrawer.kt:463` steps=-1 for mode 7; VK `:547` no steps arg). Not notched.
- GL & VK share the SAME NVIDIA `NVScalerUpdateConfig` constants (stock, untuned) → identical strength on both:
  - slider 0 ≈ OFF (max USM strength ~0.03, overshoot limit ±20%)
  - slider 50 = NVIDIA neutral default (1.6, ±50%)
  - slider 75 = our seeded default (~2.16, ±69%)
  - slider 100 = hard max (~2.73, ±87% — halo onset)
- Input is `clamp(sharpness,0,1)`; 100 cannot be exceeded without editing shader constants. Curve is piecewise-linear with one slope kink at 50 (steeper above), plus soft floors ~17 & ~37 at the low end.

**Side-finding (NOT NIS — cleanup candidate, UNFIXED):** CAS/Sharpen slider snapping is inconsistent across renderers.
- OpenGL: Scaling-mode "Sharpen" (mode 6, `glUpscaleSharpness` steps=3) + standalone "Sharpen (CAS)" toggle (`sgsrSharpness` steps=3) → snap to 5 notches {0,25,50,75,100}.
- Vulkan: "Sharpen" mode 6 (`upscaleSharpness` :547) + standalone "CAS" toggle (`casSharpness` :568) → continuous.
- Fix later: add `steps=3` to the two VK sliders, or drop `steps` on the two GL ones (lean continuous-everywhere; GL keeps notches only for the "stop 0 = OFF" guarantee).

**UPDATE 2026-06-28 (later):** ✅ OpenGL NIS DEVICE-PROVEN — user: "it worked like vulkan". Adreno GL shader-compile risk CLEARED. NIS now proven on BOTH renderers, matching looks. (Install: NIS vc32 needed `pm install -r -d` over the bionic-fg vc33 build via root bridge.) NIS feature merge-ready; debanding (other half of branch) may want a quick dark-gradient check before merge.

**RESUME POINT (2026-06-28, user driving home):** Step 1 NIS = DONE, device-proven both renderers. NEXT SESSION = **Step 2: VRR / refresh-rate matching** (full plan in roadmap memory file). Open optional threads to fold in: (a) debanding dark-gradient check + merge `feat/deband-nis` to main; (b) CAS/Sharpen snapping cleanup (GL 5-notch vs VK continuous). Branch tip `cc3361f`, pushed, unmerged.

---

## 2026-06-28 — AMA bot: fix question form + switch to auto-answer-every-issue

**Context:** User reported a friend submitted a test question but it never got answered — came in with no label, and manually adding `ama-request` after the fact did nothing.

**Root causes found (two):**
1. **Form template broken since day one.** `.github/ISSUE_TEMPLATE/ask-the-ai.yml` used the singular key `validation:` instead of GitHub's schema key `validations:`. GitHub strict-rejects invalid form templates → the `?template=ask-the-ai.yml` link silently fell back to the BLANK "Create new issue" page (no `Q:` title, "No labels") → submissions carried no `ama-request` → bot never fired. Fixed singular→plural (`62a223e`). Template now parses clean (verified with js-yaml).
2. **After-the-fact labeling is a no-op.** The `labeled` trigger only listens for the `question` label, not `ama-request` — so manually adding `ama-request` later never triggers anything. (Adding `question` does — used it to force-answer the friend's #34.)

**Decision (user chose): drop the fragile form/label dependency entirely → auto-answer EVERY new issue.** GitHub's new mobile issue-creation UI made the form/chooser unreliable across devices, so relying on it was the real friction.

**Workflow change (`035ef08`):**
- Trigger: job now runs on ANY `issues:[opened]` (skips bot-opened + already-`answered`); `labeled`+`question` kept as a maintainer force-rerun on older issues.
- Per-user daily counter rewritten to count `label:answered author:X created:>=dayAgo` with `>=` (new issues no longer carry `ama-request`, so the old counter would never fire).
- Now prepends the issue **Title** to the question (bug reports often put the ask in the title with an empty body).
- Rate limits unchanged: 5/user/day (maintainers exempt) + 200/month.
- Side effect (user accepted): bot now also answers bug reports / feature requests, not just questions.

**Docs (`a394c2b`):** README badge + 3-steps now point to plain `/issues/new` ("Open an issue"); maintainer notes drop the form-only/`ama-request` wording (only `answered`+`question` labels needed now).

**Proven:** plain unlabeled test issue #35 ("Native Rendering+") auto-answered in ~1–2 min with accurate citations (`XServerDisplayActivity.java:1936-2007`, `XServerDrawer.kt:633`) and got `answered`; closed as test. Also confirmed `OPENCODE_AUTH` secret is set and #34 answered correctly. No stale "No answer generated" comments remain on #32–#35.

**Commits:** form fix `62a223e`, workflow `035ef08`, README `a394c2b` — all on `main` via API.

---

## 2026-06-29 — 2.1 stable cut + GL Native Rendering P0 (scanout extraction)

### ✅ 2.1 STABLE released
Cut `2.1` (versionCode 33, plain tag, `prerelease:false`/`make_latest:true`, releases/latest→2.1). Bumped `app/build.gradle` first (`282a674`) since `release.yml` reads version FROM there. Successful run `28341709108`; 3 APKs + `update.json` vc33 attached. Shipped: **VRR / refresh-rate matching** (Auto match-FPS + manual 60/90/120/144 snap slider, all 3 renderers), **NIS** upscaler (NVScaler mode 7, Vulkan), **debanding** (Vulkan compositor, strength slider), **Task Manager Vulkan/ASR fix**, **install-progress 98%→100% fix**, AIO Graphics Test v1.6.1 bundled.
- ⚠️ **Release-notes trap (recurring):** first dispatch `28341637256` had a literal `"` in the notes → would break `release.yml`'s `NOTES="${{ inputs.release_notes }}"` bash line (the 1.9.2 failure). CANCELLED before the build finished (no tag leaked), re-dispatched with curly-quote-safe notes. **Lesson: pass release notes via a file / no straight `"`.** Final body replaced post-publish via `gh release edit --notes-file` for the clean 2.0-style layout.
- README "What's New in 2.0"→"in 2.1" rewritten + Full Features updated (NIS/deband/VRR) — `d019bc3`. Verified each feature is real in renderer code before writing (deband `setDeband`, NIS NVScaler mode 7, `matchRefreshRate`).

### ▶️ GL Native Rendering — P0 (renderer-neutral scanout extraction) — CI-GREEN, device-test owed
Goal: bring **Native Rendering** (direct scanout via `SurfaceControl`→HWC overlay) to the **OpenGL** renderer (Vulkan-only today). Plan committed `docs/GL_DIRECT_SCANOUT_PLAN.md` (`228319f`). Phased P0–P6.

**P0 (delegated to graphics-vulkan-engineer, branch `feat/p0-scanout-extract`, off main, pushed, NOT merged):** behavior-preserving extraction of the scanout impl into a standalone renderer-neutral `app/src/main/cpp/scanout/ScanoutContext.{h,cpp}` (zero Vulkan/GL/EGL). Methods moved (initScanout→initFromWindow, scanoutSetBuffer→setBuffer, applyScanoutBuffer→applyPendingCursor, dst/cursor setters) + state + SC_CREATE/ST_* macros + 9 dlsym fn ptrs. **Cursor threading split:** ScanoutContext gets pure setters + `applyPendingCursor()`; `needsRender`/`dirtyCV`/`cursorMoved` STAY in VulkanRendererContext, render loop calls `scanout.applyPendingCursor()` at `VulkanRendererContext.cpp:1485`. `VulkanRendererScanout.cpp` → thin forwarders. **`vulkan_jni.cpp` UNCHANGED → libvulkan_renderer ABI preserved.** Dropped 3 dead members; 1 log-only line touched. **CI run `28342956403` GREEN all 3 flavors.** CI-green only — NOT device-proven.

**RESUME POINT / NEXT ACTION (user is about to test):** **P0 GATE = Vulkan-native REGRESSION device test.** Install the `feat/p0-scanout-extract` build (CI `28342956403`, release_number `p0-scanout`), open a **VULKAN** container with **Native Rendering ON**, run a DXVK game (AIO DX11 SPACE scene = known-good). Confirm UNCHANGED vs before: presents correctly, colors right, cursor right (pos/hotspot, not double-drawn), no black screen, clean teardown on rotate/background. **This must pass before P1.** Then P1 = new `libdirect_scanout.so` + `DirectScanout.java` (dormant, no wiring). Project memory: `project_bannerlator_gl_native_rendering.md`.

**UPDATE — P0 device check + dumpsys SurfaceFlinger analysis (2026-06-29):** Tested the `p0-scanout` build on device "Pocket FIT" (Adreno 750 / SD8Gen3, Vulkan|DXVK), AIO DX11 cube test, Native Rendering OFF vs ON.
- **Renders correctly both ways** (colors/geometry/cursor fine, no black screen) → **P0 extraction looks behavior-preserving** (regression-clean; strict A/B vs pre-P0 2.1 Native-ON not run, but the feature works on the refactored build).
- **`dumpsys SurfaceFlinger` (root bridge, game in foreground) CONFIRMS overlay promotion WORKS:** `winlator_game_buf#66438` = **composition type=DEVICE (2)** = HWC hardware overlay (parent `winlator_game#66434` is the empty container SC). Game buffer `1280x702 BGRA_8888`, display controller SCALEs it ~1.5x→1080p **for free**. So direct scanout offloads the GPU from compositing, as intended. (3 layers total on DEVICE overlays; 128 idle/INVALID.)
- **User Q "why is FPS lower with Native ON?"** OFF=766fps/29% GPU/86°C/1.3ms vs ON=584fps/27% GPU/96°C/1.7ms. **Not a failure** — overlay is promoting. Direct scanout couples the guest to a real vsync-paced, triple-buffered overlay queue with buffer-release backpressure → the guest can't sprint ahead; the OFF path lets it race to 766 into Winlator's looser offscreen compositor, but **most of those frames are never displayed**. GPU load staying low = GPU offloaded (the win); CPU temp up = per-frame `ASurfaceTransaction`/fence cost. **Native Rendering is a latency/power feature, not a more-FPS feature**; an uncapped microbench is the worst way to measure it (cap to panel 60/120 and compare power/temp/latency at equal displayed FPS to see the benefit). Same backpressure tradeoff will apply to the GL port at P4.
- **NEXT: merge P0 → start P1** (`libdirect_scanout.so` = `scanout/directscanout_jni.cpp` + `ScanoutContext.cpp`, links log/android/dl/atomic only, NO vulkan; + `DirectScanout.java`; dormant, not wired).

**UPDATE — GL Native Rendering P0 merged, P1 done+merged, P2 in flight (2026-06-29):**
- **P0 MERGED** to main (merge `9575164`, no-ff, branch deleted) — renderer-neutral `ScanoutContext` extraction, Vulkan-native overlay device-confirmed (see above).
- **P1 DONE + CI-green + MERGED** (graphics-vulkan-engineer; CI `28344514069`; merge `51ccb3a`, branch deleted). New **`libdirect_scanout.so`** (CMake target `direct_scanout`: `scanout/directscanout_jni.cpp` + `ScanoutContext.cpp`, links **log/android/dl/atomic only — NO vulkan/adreno**; ScanoutContext.cpp object-duplicated across this + vulkan_renderer, intended). JNI = heap `ScanoutContext*` as jlong, 1:1 forwarders; cursor applied **inline** (GL model, no needsRender/dirtyCV). New **`DirectScanout.java`** — lifted child-SC builder/teardown/`applyScanoutSwapTransform`(R/B-swap)/`releaseScanoutSurfaces` from `VulkanRenderer.java:614-679`/`:168-300`, generalized `enable(SurfaceControl parent,...)` to take parent SC as arg. **VulkanRenderer.java byte-for-byte unchanged; nothing wires DirectScanout yet → behavior-neutral, no device test needed.**
- **P2 IN PROGRESS** (main session, one-liner): `XServerView.getSurfaceControl()` (`:153`) now returns `glSurfaceView.getSurfaceControl()` for GL too (was null; GLSurfaceView extends SurfaceView → inherits it, API29+). Vulkan return unchanged. **Behavior-neutral now** — only callers are `VulkanRenderer.java:173`/`:623` (Vulkan-only path); no GL path calls it until P3, so the "non-null SC on GL" device check lands at P3. Branch `feat/p2-gl-surfacecontrol`, CI run `28345217160` building, NOT merged.
- **NEXT: P2 CI-green → merge → P3** = GLRenderer scanout lifecycle (`nativeMode`/`setNativeMode`/`setInitialNativeMode`, `DirectScanout.enable/disable`, dst + cursor, implement GL `setRenderingEnabled`→`xServer`; toggle wiring §4 activity+drawer). **P3 = first GL device validation** (§3: non-null GL SC + cursor SC composites ABOVE GL content). P4 = per-frame game push (feature lights up). Delegate P3 to graphics-vulkan-engineer.

---

## 2026-06-29 — Controller bindings persistence + scrollable profiles (#37) MERGED

**✅ DEVICE-PROVEN (Odin 2) + MERGED to main** (merge `d3ee2d5`, no-ff).
- `2f5cf0a` persist controller bindings + harden profile import (issue #37)
- `4867789` make the Download Profiles list scrollable
- Touches: `Binding.java`, `ControlsProfile.java`, `InputControlsScreen.kt`.
- Build: manual "Any branch compilation" run `28365146389` GREEN (all 3 flavors). User device-tested → working.
- No release cut (still 2.1 stable). Branch `fix/odin2-controller-bindings` can be deleted.

---

## 2026-06-29 — GL Native Rendering P2 MERGED, P3 started

**P2 MERGED** to main (merge `460725f`, no-ff; branch `feat/p2-gl-surfacecontrol` deleted local+remote). One file: `XServerView.getSurfaceControl()` now also returns `glSurfaceView.getSurfaceControl()` (GLSurfaceView extends SurfaceView → inherits it, API29+). Behavior-neutral (only Vulkan path calls it today). CI run `28345217160` was green.

**P3 STARTED** — delegated to graphics-vulkan-engineer. Scope = GLRenderer scanout LIFECYCLE only (no per-frame push, that's P4): `nativeMode`/`setNativeMode`/`setInitialNativeMode`, `DirectScanout.enable/disable`, dst + cursor SCs, implement `setRenderingEnabled`→`xServer`, toggle wiring §4 (activity + drawer). Goal: enabling GL native builds the child SCs + shows cursor SC; game still GL-composited. **P3 = first real GL device validation** (cursor SC composites ABOVE GL content). Plan `docs/GL_DIRECT_SCANOUT_PLAN.md`.

**UPDATE — P3 CI GREEN, device-test owed (2026-06-29):** Branch `feat/p3-gl-scanout-lifecycle` (`f414e38`), CI run `28367896129` GREEN all 3 flavors (build 14m54s). +184 lines / 3 files: `GLRenderer.java` (lifecycle: setNativeMode/setInitialNativeMode/isNativeMode, enableScanout/disableScanout = game SC layer1 + cursor SC layer2 above GL, setRenderingEnabled→xServer, sendCursorToScanout skips GL cursor pass, updateScanoutDst, onSurfaceDestroyed/forceCleanup teardown), `XServerDisplayActivity.java` (toggle wiring in drawer onChange + launch path: setInitialNativeMode from container.isRendererNative() + setSwapRB), `XServerView.java`. NO per-frame push (P4). **NOT merged — P3 is the device gate.** Device-test (GL container, Native ON): cursor SC composites ABOVE GL content (not double-drawn, right pos/hotspot); `dumpsys SurfaceFlinger` shows child game+cursor SCs; rotate/app-switch/exit no leak/black; Native OFF = regression-clean. Merge only after device pass.

**UPDATE — P3 DEVICE-VERIFIED in-game (2026-06-29, device "Pocket FIT" Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube):** `dumpsys SurfaceFlinger` (game in fg, Native Rendering ON) confirms the P3 lifecycle BUILT the child SurfaceControls under the GLSurfaceView SC: `winlator_game#3181` + `winlator_game_buf#3185` + `winlator_cursor#3183` + `winlator_cursor_buf#3186`(16x16) all present. **Cursor SC is a separate composited layer** (winlator_cursor_buf, CLIENT, ROT_90, 1064,0-1080,16 = top-right) ABOVE the GL SurfaceView(#3165) → cursor-on-own-SC WORKS, no double-draw. **Game still GL-composited** (the GL SurfaceView carries the full game image; `winlator_game_buf` NOT in the active HWC composited set = game SC is bufferless, no per-frame push) = EXACTLY correct for P3. No overlay promotion of the GAME yet (that's P4). No black screen, renders fine. ✅ **P3 DEVICE GATE PASSED.**

**📌 User finding — "screen effects still work with Native Rendering ON" (screenshot: FXAA+CRT+Toon+NTSC all checked + Native toggle ON + effects visibly applied to the cube):** EXPECTED in P3, NOT a bug. P3 only moves the CURSOR to its own SC; the GAME still flows through the GL renderer → EffectComposer → onDrawFrame, so effects still apply. The "effects can't work with native rendering" end-state only kicks in at **P4** (game frame pushed straight to HWC overlay, bypassing GL entirely) + **P5** (grey-out the effect toggles when native on). So P3 = effects coexist with native; that flips at P4.

**▶️ NEXT: P3 passed device gate → MERGE `feat/p3-gl-scanout-lifecycle` → P4** (per-frame game push = feature lights up + effects-bypass behavior appears).

**UPDATE — P4 CI GREEN, device-test owed (2026-06-29):** Branch `feat/p4-gl-perframe-push` (`d44b560`), CI run `28369663007` GREEN all 3 flavors (artifacts `Bannerlator-p4-gl-perframe-{standard,ludashi,pubg}`). +70 lines, 3 files: **GLRenderer.java** new `presentScanout(Window,Drawable)` (lift of VulkanRenderer.onUpdateWindowContent AHB body: synchronized(content.renderLock), GPUImage g=content.getTexture(), ahbPtr=g.getHardwareBufferPtr(), fence=g.unlock(), scanout.present(ahbPtr,rx,ry,w,h,fence), g.lock(), refreshDataFromTexture(), first-delivery pause via xServer.setRenderingEnabled(false)+xRenderingPausedForScanout, hudFrameTick.accept) + `setHudFrameTick(IntConsumer)`. **PresentExtension.java** GL-native FLIP branch before final else (mirrors Vulkan isNative: content.setTexture(pixmap GPUImage)+setDirectScanout(true)+sendCompleteNotify FLIP+presentScanout+emitIdleNotify). **XServerDisplayActivity.java** glr.setHudFrameTick wired (updates frameRating/frameRatingHorizontal/perfHud on frameRatingWindowId). Reuses DirectScanout.present()/isGameFrameDelivered() from P1. Vulkan/ASR untouched. **NOT merged — P4 is the full-feature device gate.** Device-test (GL container, Native ON, DXVK game): game presents correct (no black, colors right=swapRB, letterbox); `dumpsys SurfaceFlinger` GAME layer on HWC overlay (composition type=DEVICE) GL layer skipped (= the win vs P3 CLIENT/GL); cursor correct not double-drawn; HUD ticks (not frozen); rotate/bg no leak; effects now silently stop applying (expected, P5 greys toggles). Merge after device pass.

**UPDATE — P5 CI GREEN (stacked on P4), device-test owed (2026-06-29):** Branch `feat/p5-gl-effect-exclusion` (`64b7cfb`) OFF `feat/p4-gl-perframe-push` (`d44b560`) — so ONE test build = P4 per-frame push + P5 grey-out. CI run `28373207641` GREEN all 3 flavors (`Bannerlator-...{standard,ludashi,pubg}` — note artifact name prefix per agent). +87 lines, 2 files: **XServerDisplayActivity.java** — `disableNativeRenderingForPreset()` got a GLRenderer arm (setNativeMode(false)); new `resetGlEffectsForNative(GLRenderer)` (Direction B, mirrors resetVulkanPresets: filter→1/setUpscaler(0), remove FSREffect+HDREffect, applyScreenEffects neutral, deband off, reset XServerDialogState flows glUpscalerMode/Sharpness/sgsr*/hdrEnabled/se*/deband* — touches EffectComposer+StateFlows only, never apply callbacks → no re-entry); Native toggle GL arm calls resetGlEffectsForNative on enable; Direction A wired into onGlUpscalerApply(mode≥3)/onSgsrUpdate(enabled||hdr)/onScreenEffectsApply(non-neutral)/GL onDebandApply(enabled) → each calls guarded disableNativeRenderingForPreset(). **XServerDrawer.kt** — GraphicsContent derives `glEnabled = !nativeRenderingEnabled` (existing reactive XServerDrawerState flow, no new flow) + dimmed glHeaderColor; passes glEnabled to UpscalerModeButtons/scaling+SGSR IntSliders/Sharpen(CAS)+HDR ToggleRows/DebandControls/Screen-Effects sliders/SeShaderToggles; added `enabled` param to IntSlider+DebandControls (Vulkan callers default true → unchanged). Vulkan/ASR + P3/P4 untouched. **NOT merged.** Device-test: GL+Native ON → all GL effect+scaling controls greyed/disabled (~0.4 alpha) + headers dim; toggle Native live un-greys instantly; Direction A (effect→native off+toast)/B (native→effects reset); Vulkan unchanged. ⚠️Deviation: grey-out applied to GL block only (Vulkan resets-on-enable but doesn't grey — plan phrasing "matching Vulkan greys" was inaccurate; followed explicit GL-grey requirement).

**▶️NEXT: device-test the combined P4+P5 build → if pass, merge `feat/p5-gl-effect-exclusion` to main (brings P4+P5 together) → P6 optional cleanup (fold Vulkan/ASR onto DirectScanout).**

**UPDATE — Combined P4+P5 DEVICE-TESTED (2026-06-29, Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube). Mixed result → overlay-promotion fix started.**
Driven via root bridge (toggle in-drawer, dumpsys OFF vs ON, drawer open vs closed):
- ✅ **P4 functional:** Native ON builds scanout SCs (winlator_SC_count 0→14): winlator_game/winlator_game_buf (game buffer 1280×702 ROT_90 → disp 0,0,1080,1920 fullscreen), winlator_cursor/winlator_cursor_buf (16×16). Game renders perfectly, colors correct (swapRB good), cursor on own SC. Toast "Native Rendering+ Enabled".
- ✅ **P5 grey-out:** toggling Native ON instantly dims+disables FXAA/CRT/Toon/NTSC + sliders (verified screenshot vs Native-OFF bright state).
- ❌ **THE BUG — no HWC overlay promotion:** Native OFF = GL SurfaceView is `composition type=DEVICE` (single fullscreen overlay, efficient). Native ON (even drawer CLOSED) = winlator_game_buf AND GL SurfaceView both **CLIENT** (GPU). Raw HWC = `composition: DEVICE/CLIENT` = SF requested overlay, HWC REJECTED → GPU fallback. So zero power/latency win; currently WORSE than native-off. Vulkan native promotes to DEVICE on same device/scene/scale → rejection is GL-specific.
- **Diagnosis:** gameSC IS opaque in both (DirectScanout.java:85-86 / VulkanRenderer.java:174-175) — NOT the cause. The game/cursor SCs are CHILDREN of the renderer's own SC (parent = xServerView.getSurfaceControl() = GLSurfaceView's SC). On GL the paused GLSurfaceView keeps its last opaque fullscreen buffer on the parent layer → SF can't drop it → overlay rejected. Classic hole-punch issue. Can't just hide the parent SC (children hide too).
- **▶️FIX STARTED** — branch `feat/gl-scanout-overlay-fix` off `feat/p5-gl-effect-exclusion` (graphics-vulkan-engineer, agentId a1c4b9fd82603ed55): investigate how Vulkan's base surface stays non-competing, then make the GL base layer transparent/hole-punched/bufferless when scanout active so SF can overlay-promote the opaque child game SC. Primary device gate = dumpsys winlator_game_buf = composition type=DEVICE, GL layer skipped.
- ⚠️ Native left toggled ON on the test container.

**UPDATE — GL overlay-promotion fix #1 (idle base layer) CI-GREEN, device-test owed (2026-06-29):** Branch `feat/gl-scanout-overlay-fix` (`b418e53` off P5 `64b7cfb`), CI run `28376490129` GREEN. +38 lines GLRenderer.java only. Fix = once first game frame reaches game SC, onDrawFrame renders ONE cleared frame (glClearColor 0,0,0,0) then early-returns every subsequent frame (no GL compositing of game/cursor); onPointerMove no longer requestRenders in native mode → base GLSurfaceView goes IDLE holding a single cleared buffer (mirrors Vulkan render-loop single clearing frame + ASR bufferless base). `xRenderingPausedForScanout` made volatile (X-thread write / GL-thread read). Theory: idle base lets SF occlusion-cull it so HWC promotes the opaque game SC to DEVICE. **CAVEAT: addresses BLOCKER #1 ONLY (competing base layer). Base still OPAQUE black (agent noted translucent-format fallback lever). Did NOT address BLOCKER #2 (SC-level ROT_90+scale that Adreno overlay pipes may reject) — libwinemu intel arrived after agent committed.** This build isolates hypothesis #1. **DEVICE GATE = dumpsys winlator_game_buf composition type CLIENT→DEVICE.** If DEVICE → #1 was the whole fix (don't touch orientation). If still CLIENT → blocker #2 confirmed → GameHub-style geometry rework (no SC-level rotate/scale, fit via setBuffersGeometry). ⚠️Agent also committed PROGRESS_LOG.md ON the branch (+36) → diverges from main, reconcile on merge.

**UPDATE — overlay-fix #1 (idle base) DEVICE-TESTED 2026-06-29: INSUFFICIENT, blocker #2 CONFIRMED.** GL container + Native ON + drawer closed, dumpsys: winlator_game_buf STILL = CLIENT (raw `composition: DEVICE/CLIENT` = HWC rejected overlay). Game renders fine / no black / no regression — but no DEVICE promotion. Game SC still carries `ROT_90` + source 1280×702 → disp 1080×1920 (rotate + 1.5× scale). So idle-base was necessary-but-not-sufficient. **Blocker #2 = Adreno HWC won't overlay a layer needing SIMULTANEOUS rotation + scaling** (matches libwinemu RE: GameHub does ZERO SC-level geometry, sizes via setBuffersGeometry+RGBA_8888). ▶️Agent a1c4b9fd82603ed55 resumed on same branch `feat/gl-scanout-overlay-fix` to do blocker #2: eliminate SC-level rotate+scale on the game SC (compare why Vulkan native IS overlay-eligible on this device but GL isn't). ⚠️Risky (orientation/fit). Device gate unchanged: winlator_game_buf CLIENT→DEVICE + game still correct.

**NOTE — interpreting "native ON feels different" (2026-06-29, important for the upcoming test):** User observes real latency/FPS difference Native ON vs OFF and asked if the overlay is already working. THREE distinct things change when Native turns on — don't conflate:
- **A. P5 toggles grey out** — pure UI, no perf impact unless an effect was active (confound: turning off an active effect changes FPS).
- **B. GL compositor pass is SKIPPED** — even with zero effects, Native-OFF does a per-frame GPU blit (game texture → GLSurfaceView + swap); Native-ON's `presentScanout` pushes the AHB straight to the game SC and idles the base, removing that GPU stage + changing present pacing (SurfaceControl queue vsync/backpressure). **This is a REAL, PARTIAL win and is what the user is measuring — it happens WHETHER OR NOT the overlay promotes.**
- **C. HWC overlay promotion (`DEVICE`)** — display controller does the final composite, GPU does nothing for it. **STILL NOT happening (dumpsys = CLIENT).** This is the big win blocker #2 targets.
Corrected an earlier overstatement ("pointless/regression"): B is a legitimate benefit on its own; C is the additional win still missing. **To verify C specifically: compare GPU load/power/temp at CAPPED equal FPS (e.g. lock 60) OFF vs ON — only C drops GPU load meaningfully; FPS alone is downstream of B and will differ regardless.** The `dumpsys` DEVICE-vs-CLIENT line is the authoritative overlay readout.

**TEST QUEUED (after blocker-#2 build finishes):** blocker #2 fix is building on `feat/gl-scanout-overlay-fix` (agent a1c4b9fd82603ed55, eliminate SC-level rotate+scale). When green → device-test the full thing: (1) dumpsys winlator_game_buf CLIENT→DEVICE (the gate), (2) game still correct orientation/fit/colors, (3) capped-FPS GPU/power A/B to quantify C, (4) cursor/HUD/lifecycle.

---

## ⏸️ RESUME-HERE CHECKPOINT (2026-06-29, before user device reboot)

**Active task: GL Native Rendering — overlay-promotion fix.** Session runs on the device → reboot kills it + the background CI watch (CI continues server-side).

**MERGED to main:** P0 (scanout extract) + P1 (libdirect_scanout) + P2 (GL getSurfaceControl) + P3 (GL scanout lifecycle). main tip = `7aaacaf` (docs only after the P3 merge `2fe3f10`).

**NOT merged — all on branch `feat/gl-scanout-overlay-fix` (tip `e036124`):** stacked P4 (`d44b560` per-frame push) → P5 (`64b7cfb` effect/scaling grey-out) → overlay-fix#1 (`b418e53` idle base) → overlay-fix#2 (`e036124` TRANSLUCENT base). ⚠️ Agent also committed PROGRESS_LOG.md ON this branch (+36) → diverges from main's PROGRESS_LOG; reconcile (keep main's) on eventual merge.

**Build in flight:** CI run **`28378890898`** for `e036124` (translucent base) — was BUILDING at checkpoint, status NOT yet confirmed. ON RESUME: `gh run view 28378890898 --json status,conclusion`; if green, artifacts = `Bannerlator-glscanout-overlayfix-{standard,ludashi,pubg}`.

**DEVICE-PROVEN SO FAR (Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube):** P4 functional (game flows through scanout, renders correct, swapRB ok, cursor own SC), P5 grey-out works. ❌ Overlay NOT promoting: every Native-ON test shows `winlator_game_buf = CLIENT` (raw `composition: DEVICE/CLIENT` = HWC rejected). fix#1 (idle OPAQUE base) tested INSUFFICIENT. fix#2 (translucent base) = current build, UNTESTED.

**KEY FINDING (agent, code-read):** the game-SC geometry (src 1280×702→dst fullscreen + ROT_90=global display orientation) is BYTE-FOR-BYTE the same as the Vulkan native path, which DOES promote to DEVICE on this device → so rotate+scale is NOT the GL-specific blocker (my earlier blocker-#2 hypothesis likely WRONG). The GL-specific difference = GLSurfaceView base is OPAQUE & composited (CLIENT) even when idle, starving the game overlay's HWC plane. fix#2 makes it TRANSLUCENT so SF can skip it (Vulkan base = idle swapchain, ASR = bufferless — both non-competing).

**NEXT ACTION ON RESUME:** 1) confirm CI `28378890898` green. 2) Device-test translucent build (GL container, Native ON, DX11 scene, drawer closed): **THE GATE = dumpsys `winlator_game_buf` composition type CLIENT→DEVICE** + game still correct (orientation/fit/colors/no-black). 3) If promoted → quantify the win: capped-60 GPU/power/temp A/B (off vs on). 4) If STILL CLIENT → deeper dig: why does identical-geometry Vulkan promote but GL not (plane budget? layer count? the extra activity/decor layers? try fewer layers / check HWC plane caps). 5) If promotes + correct → merge `feat/gl-scanout-overlay-fix` chain to main (reconcile PROGRESS_LOG), then P6 optional. If unfixable → gate GL-native experimental (plan §3 fallback).

**Interpretation reminder (A/B/C):** user feels a real latency/FPS diff Native ON vs OFF = the **B** win (GL compositor pass skipped) which happens regardless of overlay; **C** (the DEVICE overlay) is the missing big win. Verify C via capped-FPS GPU/power, NOT raw FPS. dumpsys DEVICE/CLIENT = authoritative.

**Bridge test recipe (from this session):** `getlog --exec` (PATH +=/data/data/com.termux/files/usr/bin). Graphics tab icon tap (70,138); Native Rendering toggle (765,968); close drawer keyevent 4; dumpsys → `/sdcard/Download/_x.txt`; check `grep -iE "HWC layers" -A18 | grep -iE "winlator_game|SurfaceView\[com.winlator|DEVICE|CLIENT"`; SCcount `grep -icE "winlator_game|winlator_cursor"` (0=native off, ~14=on). Foreground session: `monkey -p com.termux -c android.intent.category.LAUNCHER 1`. ⚠️ Native left toggled ON on test container (container 2 / the AIO test container).

---

## ❌ overlay-fix #2 (TRANSLUCENT base) DEVICE-TESTED 2026-06-29 — STILL CLIENT, base regressed. Translucent approach backfired.

Build `e036124` (CI `28378890898` green, manually installed by user). Device = Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube, drawer closed. Driven via root bridge (toggle 765,968; dumpsys OFF vs ON).

**THE GATE = FAIL.** dumpsys SurfaceFlinger composition (requested/actual):
- **Native OFF baseline:** base `SurfaceView[…XServerDisplayActivity]` = `composition: DEVICE/DEVICE` ✅ — ONE clean fullscreen HWC overlay, 0 scanout SCs. (FPS 750 / GPU 26% / CPU 90.7°C)
- **Native ON (fix #2):** SC count 0→14 (scanout built, native active, P5 grey-out confirmed, cube renders correct/right colors). BUT:
  - base `SurfaceView` = `composition: DEVICE/CLIENT` ❌ (was DEVICE/DEVICE off — **REGRESSED**, making it translucent did NOT get SF to cull it; it stays in the stack at z:0 and now forces GPU comp)
  - `winlator_game_buf#781` AHB = `DEVICE/CLIENT` ❌ — ROT_90, src 1280×702 → dst 1080×1920 (rotate + 1.5× scale)
  - `winlator_cursor_buf#782` AHB = `DEVICE/CLIENT` ❌
  - VRI + ScreenDecor also CLIENT → **EVERY layer GPU-composited.** (FPS 704 / GPU 28% / CPU 93.1°C = slightly WORSE than off)

**VERDICT:** translucent base = INSUFFICIENT and counterproductive. It didn't make SF skip the base (base still composited, just now blended/CLIENT instead of a clean opaque DEVICE overlay), and the whole frame fell to GPU. So the checkpoint's "opaque competing base is the GL-specific blocker" hypothesis is NOT confirmed by making it translucent — the base being *present at all* (not its opacity) plus the rotate+scale game buffer is what's blocking. **C (HWC overlay) still not happening; only the B win (skipped GL compositor pass) remains, and it's marginal here.**

**▶️ NEXT HYPOTHESES (not yet tried, ranked):**
1. **Make the base GLSurfaceView genuinely BUFFERLESS/absent when scanout active** (like ASR's bufferless base), not merely translucent — currently it's still a composited layer at z:0. If SF still can't drop it, the GLSurfaceView architecture itself may be the wall (can't host children AND vanish).
2. **Drop the SC-level SCALE on the game buffer** — set the game SC to display size via setBuffersGeometry/setGeometry so it carries ONLY ROT_90 (the global display transform), matching GameHub/libwinemu (zero SC-level geometry). The 1280×702→1080×1920 rotate+scale combo is a known Adreno overlay-rejection trigger. (Re-opens old "blocker #2"; checkpoint thought it was wrong but this run keeps it live.)
3. **DECISIVE DIAGNOSTIC: capture Vulkan-native ON dumpsys on the SAME device/scene** and diff layer-for-layer vs this GL one — Vulkan promotes to DEVICE here, so the diff (layer count? base swapchain state? is the Vulkan base even present? buffer geometry/transform?) points straight at the real GL-specific blocker instead of guessing.

⚠️ Container left with Native toggled ON after this test. fix #2 NOT merged (still on branch `feat/gl-scanout-overlay-fix` tip `e036124`).

---

## 🔬 ROOT-CAUSE DIAGNOSIS 2026-06-29 (graphics-vulkan-engineer code-read, agentId aa8c3b24c81f5e41c) — it's the GLSurfaceView base + child-parenting, NOT geometry/opacity.

Read both scanout paths (Vulkan works→DEVICE, GL fails→CLIENT) against the device evidence. Eliminations:
- **Geometry/SC-scale = RED HERRING.** Both renderers feed the SAME `ScanoutContext::setBuffer` (`ScanoutContext.cpp:173-211`) with transform arg **0** (identity); the ROT_90 you see in dumpsys is the global display orientation applied to EVERY layer (incl base), and the 1280×702→1080×1920 is the inherent src≠dst guest→display scale. This exact block runs on the Vulkan path that DOES promote on this device. So `setBuffersGeometry`/dropping the scale would change nothing GL-specific.
- **Parenting topology = IDENTICAL.** Both parent the game/cursor SCs under `xServerView.getSurfaceControl()` = their own base-view's SC (Vulkan `VulkanRenderer.java:623`, GL `DirectScanout.java:85-89`). Only variable = base VIEW TYPE: plain `SurfaceView` (Vulkan/ASR) vs `GLSurfaceView` (GL).
- **"Base still updating" = NOT it.** Both idle the base after scanout starts (Vulkan `VulkanRendererContext.cpp:1486-1499` renders ONE empty frame then `return`s forever — its plain-SurfaceView BufferQueue genuinely flatlines so SF can drop it; GL mirrors via `setRenderingEnabled(false)`+early-return `GLRenderer.java:262-285,387-395`).

**THE WALL:** `GLSurfaceView` OWNS/manages its own `EGLSurface` (`XServerView.java:102-111`, preserveEGLContextOnPause) → it ALWAYS holds its last fullscreen EGL buffer and can't be made bufferless/absent like a stopped Vulkan swapchain or ASR's bufferless host. AND because the game/cursor SCs are its CHILDREN, you can't hide/remove the base without hiding them (parent setVisibility(false) hides subtree). `setGlSurfaceTranslucent` only changes the holder format → turns the base into a BLENDED fullscreen layer at z=0 that HWC must client-composite → cascades the whole frame to CLIENT = exactly the fix-#2 regression. Matches the GameHub/libwinemu RE: GameHub promotes because it uses a **dedicated standalone surface with no competing base**.

**▶️ RECOMMENDED FIX (a), IMPLEMENT FIRST:** reparent the scanout SCs OFF the GLSurfaceView. Concrete lowest-risk form: add a **dedicated plain `SurfaceView`** sibling of `glSurfaceView` in the `XServerView` FrameLayout, parent game/cursor SCs under THAT SC, and set the GLSurfaceView GONE / its SC invisible while native active → single opaque fullscreen game SC over a clean base = the same topology that already promotes on the Native-OFF baseline AND the Vulkan path. Files: `XServerView.java` (`getSurfaceControl()` :183, add dedicated SurfaceView + base-hide hook by `setGlSurfaceTranslucent` :168), `GLRenderer.enableScanout/disableScanout` (:608/:646), `DirectScanout.enable` (parent arg already generalized). (Plan §3 Fallback-A via `getRootSurfaceControl()` API30+ also works but fussier on lifecycle — dedicated-SurfaceView is closer to the proven Vulkan path.)
- **(b) genuinely bufferless GL base** = SECOND/fallback — needs replacing GLSurfaceView with self-managed EGL-on-SurfaceView (big rewrite, deferred "drop GLSurfaceView" step). (a) reaches the same end-state without it.
- **(c) drop SC scale** = DO NOT — proven not the blocker (shared with working Vulkan path).

**Device tests now CONFIRMATORY, not exploratory:** Vulkan-ON would re-confirm the P0 result (already proven DEVICE on this device); GameHub-GL-ON would independently prove a GL-origin standalone overlay promotes here + hand us the target layer structure. Optional belt-and-suspenders before building fix (a).

---

## 🧪 GAMEHUB 5.3.5 NATIVE-RENDERING+ DEVICE-TESTED 2026-06-29 — ALSO fails to promote (DEVICE/CLIENT), BUT confounded by windowed-desktop mode.

Ran the upstream reference (GameHub 5.3.5, pkg `com.tencent.ig`/`com.xj.winemu.WineActivity` — basis of Banner Hub 3.8.0) on the SAME Adreno750/SD8Gen3, SAME AIO DX11 cube, DXVK, right-side drawer. "Native Rendering+" is a 3-way radio Auto/Disabled/**Force Enable**. dumpsys composition (requested/actual):
- **Native OFF (Disabled):** `SurfaceView[com.tencent.ig/…WineActivity]#5` = `DEVICE/DEVICE` ✅ (clean fullscreen overlay, RGBX_8888, transform 0, src 720×1280→dst 1080×1920) + VRI `DEVICE/DEVICE`. HUD "DXVK".
- **Native ON (Force Enable):** HUD flips to "DXVK+". ALL layers `DEVICE/CLIENT` ❌ (requested overlay, HWC REJECTED→GPU): base SurfaceView#5 (still PRESENT, RGBX), `AHardwareBuffer pid[27828]` z1 (game, RGBA_8888, transform **90**/ROT_90), `bbq-adapter#1` z2 (RGBA_8888, transform 90), VRI z3. **EXACT same failure shape as our GL path** (base stays present + ROT_90 game AHB + everything rejected to CLIENT).

**⚠️ CRITICAL CONFOUND — GameHub was running its Wine DESKTOP (windowed), NOT a borderless fullscreen game:** taskbar at bottom + title bar + window chrome; the game content layer is INSET (dst 61,2–1035,1919, not fullscreen). A non-fullscreen scene with competing chrome will be rejected for overlay promotion REGARDLESS of renderer. So this is NOT a clean apples-to-apples vs our fullscreen container, and does NOT cleanly serve as the "GL-origin overlay CAN promote here" reference we wanted.

**What it DOES tell us:**
1. GameHub's native path ALSO keeps its base SurfaceView present (doesn't vanish it) and ALSO applies ROT_90+scale on the game AHB — so the libwinemu-RE "zero SC-level geometry / standalone surface, no competing base" claim is NOT what this build does in desktop mode. (RE may describe fullscreen-game path or a different code branch.)
2. It does NOT refute the engineer's diagnosis for OUR GL-vs-Vulkan: that remains a CLEAN A/B (same container, same fullscreen config, same scene — only renderer differs; Vulkan promotes to DEVICE, GL doesn't). The GameHub non-promotion is explained by windowing.

**▶️ To make GameHub a decisive reference: re-run it with a BORDERLESS FULLSCREEN game (no taskbar/title bar).** If fullscreen GameHub promotes → confirms fullscreen+standalone-surface is the recipe (supports fix (a)). If even fullscreen GameHub stays CLIENT on this device → this Adreno750/Android build may be stingy about overlays generally (re-scope expectations). Meanwhile fix (a) is still well-founded on our own Vulkan A/B (the clean fullscreen proof that this geometry promotes on this device).
⚠️ Left GameHub with Native Rendering+ = Force Enable; may have toggled "RTS Touch Controls" on the Controls page (harmless, cosmetic).

---

## 🚨 GAMEHUB 5.3.5 FULLSCREEN Native-ON DEVICE-TESTED 2026-06-29 — STILL CLIENT even fullscreen+base-dropped. Blocker looks DEVICE-LEVEL (rotated non-UBWC buffer), not our GLSurfaceView.

Re-ran GameHub as a BORDERLESS FULLSCREEN game (no taskbar/title bar — confirmed via screenshot, cube fills screen + horizontal top HUD bar "DXVK+"), Native Rendering+ = Force Enable, drawer closed. SAME Adreno750/SD8Gen3.
- **The base WineActivity SurfaceView is GONE from the active HWC set** (in fullscreen GameHub DID drop its base — unlike windowed-desktop where it stayed). Game buffer is now z:0 (bottom).
- **Yet STILL no promotion — ALL `DEVICE/CLIENT`:** `AHardwareBuffer pid[32674]` z0 (game, RGBA_8888, **transform 90**), `bbq-adapter#1` z1 (RGBA_8888, **transform 90**), `VRI[WineActivity]` z2 (RGBA_8888_UBWC, transform 0). HWC requested overlay, rejected every layer to GPU.

**🔑 PATTERN across EVERY capture today (sharp):**
- Layers that PROMOTE (`DEVICE/DEVICE`): `RGBX/RGBA_8888_UBWC`, **transform 0**. (GameHub OFF baseline base SurfaceView; our Native-OFF GL base.)
- Layers that get REJECTED (`DEVICE/CLIENT`): `RGBA_8888` NON-UBWC, **transform 90** (ROT_90). (our GL game AHB; GameHub game AHB windowed AND fullscreen.)
→ Strongly suggests a **device/DPU limitation: this Adreno display controller won't HWC-overlay a ROTATED, non-UBWC AHardwareBuffer** (Adreno rotator typically requires UBWC; a transform-90 linear buffer is overlay-ineligible → forced CLIENT, and one ineligible layer drags the whole frame to CLIENT). The landscape-game→portrait-panel 90° rotation is the likely poison.

**⚠️ THIS WEAKENS "just do fix (a)":** fix (a) = reparent scanout SCs off the GLSurfaceView + drop the base. But GameHub fullscreen ALREADY effectively does that (base dropped, standalone game buffer) and STILL doesn't promote. So dropping the competing base is necessary-but-NOT-sufficient on this device — the rotated non-UBWC buffer itself is rejected.

**🎯 THE NOW-DECISIVE TEST (no longer redundant): capture OUR Bannerlator VULKAN native-ON game buffer's composition + TRANSFORM + FORMAT on this device.** Our P0 gate recorded Vulkan promotes to `DEVICE` (BGRA_8888) but did NOT record the transform. Two outcomes:
- If Vulkan promotes with transform 90 + non-UBWC → then GameHub/GL rejection is something else (layer count? a GameHub quirk?) and the rotation theory is wrong — re-examine.
- If Vulkan's promoting buffer is transform 0 (pre-rotated content) or UBWC → THAT is the recipe: the GL/native path must deliver a pre-rotated and/or UBWC buffer so the DPU can overlay it. Fix shifts from "reparent" to "fix the buffer orientation/format."
(Earlier I called the Vulkan capture redundant — this GameHub result makes it the key missing measurement.)

⚠️ Left GameHub fullscreen + Native+=Force Enable.

---

## 🚨🚨 BOMBSHELL 2026-06-29 — OUR VULKAN NATIVE ALSO DOES NOT PROMOTE ON THIS DEVICE. The whole "Vulkan promotes / GL doesn't" premise is FALSE. Blocker = the 90° rotation (landscape game → portrait panel), device-level, renderer-agnostic.

Captured OUR Bannerlator **Vulkan|DXVK** native-ON dumpsys (same Adreno750/SD8Gen3, same AIO cube, Native ON confirmed via screenshot — Renderer: Vulkan|DXVK, P5 grey-out active, 564fps/GPU20%):
- `SurfaceView[com.winlator.banner/…XServerDisplayActivity]#2` z0 = `DEVICE/CLIENT` ❌ (RGBA_8888_UBWC, **transform 90**)
- `AHardwareBuffer pid[5980]` z1 = `DEVICE/CLIENT` ❌ (BGRA_8888, **transform 90**) = the game buffer
- `VRI[XServerDisplayActivity]` z2 = `DEVICE/CLIENT` ❌ (RGBA_8888_UBWC, transform 0)
- HWC layers table shows winlator_game_buf actual = **CLIENT**; active `---------client target---------` = full GPU composition. **NO overlay.**

**⛔ This means our Vulkan native NEVER actually promoted on this device.** The P0 gate's "winlator_game_buf composition type=DEVICE = HWC overlay confirmed" was a **MISREAD of the REQUESTED composition type** (the HWC hint column says DEVICE = "SF asked for overlay") **not the ACTUAL** (post-validateDisplay = CLIENT = HWC rejected → GPU). Every native-render path requests DEVICE; this device rejects them all.

**🔑 NOW the picture is consistent across ALL FOUR captures today:**
| Path | game buf | result |
|---|---|---|
| Bannerlator Vulkan native ON | BGRA_8888, ROT_90 | DEVICE/**CLIENT** ❌ |
| Bannerlator GL native ON | BGRA/RGBA, ROT_90 | DEVICE/**CLIENT** ❌ |
| GameHub native ON (windowed) | RGBA_8888, ROT_90 | DEVICE/**CLIENT** ❌ |
| GameHub native ON (fullscreen, base dropped) | RGBA_8888, ROT_90 | DEVICE/**CLIENT** ❌ |
| (any renderer) Native OFF baseline | UBWC, **transform 0** | DEVICE/**DEVICE** ✅ |

**ROOT CAUSE = the 90° rotation.** This is a PORTRAIT-NATIVE panel (SF display 1080×1920); the game runs landscape and the direct-scanout buffer is handed to SurfaceFlinger with **transform=ROT_90** so the DPU must rotate it for display. This Adreno DPU/HWC will NOT take a rotated layer as an overlay (rotation on the overlay path is unsupported / disqualifying here) → falls back to GPU/CLIENT for the whole frame. In the Native-OFF baseline the app's own compositor bakes the rotation into a transform-0 UBWC surface, which DOES promote. So: rotation baked-in (OFF) = overlayable; rotation requested on the scanout layer (ON) = rejected.

## ⛳ STRATEGIC CONSEQUENCES (big)
1. **The "C win" (true HWC hardware overlay, GPU idle) is NOT achievable on this portrait device for landscape content — for ANY renderer.** Not a GL bug, not a Vulkan win. It's a display-rotation/DPU limitation.
2. **fix (a) / the whole GLSurfaceView-base theory is MOOT for overlay promotion** — Vulkan uses a plain SurfaceView and STILL doesn't promote. Dropping the GL base would not unlock the overlay. Stop the overlay-fix branch attempts (fix#1 idle, fix#2 translucent, proposed fix(a) reparent) — they chase an unattainable C on this device.
3. **What native rendering DOES deliver here = the "B win"** (skip the app's own compositor blit + change present pacing → lower latency / the real feel the user reports). That is renderer-agnostic and ALREADY delivered by P4/P5. So GL native = latency PARITY with Vulkan; both get B, neither gets C on this device.
4. **The C win likely WORKS on a LANDSCAPE-NATIVE panel** (game buffer arrives transform 0, no DPU rotation needed) — e.g. AYANEO/landscape handhelds. The feature isn't useless; its overlay benefit is display-orientation-dependent.

## ▶️ RECOMMENDED NEW DIRECTION
- **Re-scope native rendering = a latency/pacing feature (B), not an overlay feature (C)**, on portrait devices. Document that HWC-overlay promotion needs a transform-0 (landscape-native) path.
- **Salvage the GL work:** merge P4+P5 (the functional per-frame push + effect grey-out) as "GL native rendering (latency parity)", DROP the failed overlay-fix commits (#1 idle, #2 translucent) — they targeted C. Reconcile branch PROGRESS_LOG.
- **Two confirmations worth doing (cheap):** (1) capture native-ON on a **landscape-native device** (AYANEO) — if game buf = transform 0 → DEVICE, the rotation theory is proven and C works there. (2) optional: try forcing this container/display to landscape-native orientation and re-capture — if it promotes, we have a per-device path.
⚠️ Vulkan container left Native ON.

---

## 🔁 LANDSCAPE LONG-SHOT DEVICE-TESTED 2026-06-29 — DID NOT help; but revealed the ROT_90 is applied by the SCANOUT CODE, not system orientation.

User forced the whole Android system to landscape, ran AIO cube on GL|DXVK, Native ON, drawer closed. (Note: forcing orientation RESTARTS the XServerDisplayActivity → reloads container; came back OpenGL/Native-off, re-enabled Native.) dumpsys (composition | format | transform):
- base `SurfaceView[…XServerDisplayActivity]#2` z0 = DEVICE/**CLIENT** ❌, RGBA_8888_UBWC, **transform 0** (now 0 because the activity surface follows the landscape system)
- game `AHardwareBuffer pid[17795]` z1 = DEVICE/**CLIENT** ❌, BGRA_8888, **transform 90** ← STILL ROTATED even though system is landscape
- cursor `AHardwareBuffer pid[17527]` z2 = DEVICE/**CLIENT** ❌, RGBA_8888, transform 90
- VRI z3 = DEVICE/**CLIENT** ❌, UBWC, transform 0

**KEY NEW INSIGHT:** the base surface went transform 0 in landscape (follows system), but the **GAME scanout buffer is STILL transform 90**. So the 90° rotation on the game buffer is applied by the **native-rendering handoff itself** (ScanoutContext/DirectScanout sets the buffer's transform), NOT by the global display orientation as previously assumed. Forcing landscape therefore could NOT remove it → still a rotated layer in the stack → HWC still rejects the WHOLE frame to CLIENT (the transform-0 UBWC base + VRI get dragged to CLIENT too, exactly as the pattern predicts). The cube still displays CORRECTLY with ROT_90+landscape (so the rotation is currently part of producing the right image — naively removing it would likely break orientation).

**CONCLUSION: landscape long-shot = DEAD END on this device with current code.** The overlay still doesn't engage. The blocker (rotated game buffer, overlay-ineligible on this Adreno DPU) is confirmed and is baked into the scanout handoff.

**One UNTESTED lever this surfaces (speculative, code change):** make the scanout transform ORIENTATION-AWARE — deliver the game buffer at transform 0 when the display is genuinely landscape-native (and ensure buffer dims match), so no rotated layer is in the stack. MIGHT promote + still display correctly IF the guest render orientation is adjusted to match. Risky (orientation/fit), unproven, and GameHub doesn't do it. NOT recommended without the engineer validating it's even coherent. The safe path remains: re-scope native = latency (B) feature, salvage P4+P5, and confirm the true overlay win on a genuinely landscape-native panel where the buffer naturally needs no rotation.

---

## ✅ SALVAGE MERGE 2026-06-29 — P4+P5 landed on main as "GL Native Rendering (Low-Latency Mode)"; dead overlay-fix reverted.

Per user direction ("give me P4 and P5 with a solid explanation for the next release"), after today's findings (overlay/C-win unattainable on this portrait device for ANY renderer; native = latency/B-win feature):
- **Reverted `7aaacaf`** (overlay-fix #2 translucent base — was the ONLY overlay-fix code that had reached main; proven dead/counterproductive on device today). Revert `ee63ab1` (-43 lines, restores clean P3 base).
- **Cherry-picked P4 (`d44b560`→`fcaf104`)** per-frame game push + **P5 (`64b7cfb`→`ba0d35d`)** effect/scaling grey-out onto the clean base (authored against 2fe3f10 = post-revert state → applied with ZERO conflicts). Verified: GLRenderer.presentScanout present, XServerDrawer glEnabled present.
- main now = P0+P1+P2+P3+P4+P5, NO overlay-fix code. P4+P5 were device-proven FUNCTIONAL (native active, game renders correct, P5 grey-out works) — only the overlay promotion failed, which we're no longer claiming.
- **NOT cutting a release** (per versioning rule — no tag/make_latest without explicit say-so). Release notes prepared at `docs/release_notes/gl_native_rendering.md` (paste-ready, honest: latency feature, effects mutually exclusive, power/overlay win is device/orientation-dependent).
- Dropped (NOT merged, stay on branch `feat/gl-scanout-overlay-fix`): overlay-fix #1 idle base (`b418e53`), overlay-fix #2 translucent (`e036124`) — both chase the unattainable C-win.
- ▶️ NEXT: CI build to confirm green on main; (optional, when available) confirm the overlay/power win on a landscape-native device (AYANEO).

---

## 🔬 PRE-ROTATION FEASIBILITY (graphics-vulkan-engineer, 2026-06-29) — reframes "rotation is THE blocker" as CONFOUNDED; cheap diagnostic experiment identified.

**Key correction (to my own conclusion):** "transform-0 promotes / transform-90 rejects → rotation is the blocker" is CONFOUNDED. The only transform-0 layer ever seen promoting is the BASE SurfaceView, which differs from the rejected game AHB in FOUR ways at once:
| | promoting base | rejected game AHB |
|---|---|---|
| transform | 0 | 90 |
| tiling | UBWC | non-UBWC/linear |
| usage | composer/scanout-grade | lacks COMPOSER_OVERLAY |
| geometry | full-screen 1:1 | scaled 1280×702→1080×1920 |
Rotation is ONE suspect; **non-UBWC + missing COMPOSER_OVERLAY usage is an independent, very-likely-decisive co-blocker that pre-rotation would NOT fix** (and is renderer-agnostic → would explain why Vulkan also fails).

**Where ROT_90 comes from (Q1): NOT our code.** Every scanout `setGeometry` passes transform 0 (`ScanoutContext.cpp:196` game, `:256` cursor; ASR `:246,383`). No `ASurfaceTransaction_setBufferTransform` anywhere in the scanout path (symbol only loaded by ASR `ASurfaceRendererContext.cpp:120`, unused). The ROT_90 is **SurfaceFlinger folding the display/window orientation** onto the layer: a landscape container runs the activity landscape on a portrait-native 1080×1920 panel (`XServerDisplayActivity.java:1015-1019` only locks PORTRAIT for portrait containers), so SF must rotate the game child-SC's fixed landscape guest buffer 90° to reach the panel.

**Critical wrinkle:** the game scanout buffer is **GUEST-allocated (Mesa/turnip Android WSI export), received zero-copy over socket** (`DRI3Extension.java:154-156`→`GPUImage(fd)`→`AHardwareBuffer_recvHandleFromUnixSocket` `gpu_image.c:81`). The host `createHardwareBuffer` (`gpu_image.c:88-97`, lacks COMPOSER_OVERLAY, CPU_WRITE_OFTEN/BGRA→linear) is only the CPU/SHM fallback, NOT game frames. So we do NOT control the game buffer's tiling/usage/format host-side without COPYING.

**Pre-rotation verdict (Q2-Q4):** Option (a) — render the guest frame via a GL pass into a HOST AHB allocated COMPOSER_OVERLAY+UBWC-friendly at panel res — is feasible and fixes 3 of 4 differences at once (transform, usage/tiling, scale). BUT re-introduces a per-frame GPU blit → "zero-GPU" dream gone; remaining win = direct HWC scanout + lower latency, MODEST over today's GL compositor. Option (b) just-force-transform-0 = sideways image (we don't set ROT_90; forcing it 0 without rotating pixels mis-orients). Option (c) patch guest WSI to allocate scanout-grade buffers = no host copy but Mesa/turnip change (wine-compat), risky, leaves rotation. Honest promotion odds even with pre-rotation: MODERATE — usage/UBWC, the scale, BGRA, and plane budget could each independently block.

**▶️ RECOMMENDED EXPERIMENT A (cheapest, isolates the most-likely + unexamined blocker, host-only ~40-60 lines GL):** in `GLRenderer.presentScanout`, blit the guest GPUImage into a host AHB allocated `GPU_SAMPLED_IMAGE|COMPOSER_OVERLAY` RGBA_8888 (reuse cursor alloc pattern `ScanoutContext.cpp:279-285`), KEEPING transform 90 + scale, present THAT. dumpsys: if game flips CLIENT→DEVICE at transform 90 → rotation was a red herring, blocker was usage/UBWC (renderer-agnostic, ~done). If still CLIENT → rotation implicated → Experiment B (add 90° rotate + render at exact 1080×1920 + lock portrait for the probe, accept wrong cursor/HUD). 
Key files: `ScanoutContext.cpp:185-196,256,279-285`, `gpu_image.c:81,88-97`, `GPUImage.java:18-38`, `DRI3Extension.java:154-156`, `GLRenderer.java presentScanout ~:725`, `XServerDisplayActivity.java:1015-1019`.

**✅ CI GREEN on main (run `28388609799`) — P4+P5 salvage compiles clean in the fresh main combination (revert `ee63ab1` + P4 `fcaf104` + P5 `ba0d35d`). GL Native Rendering (Low-Latency Mode) is now build-verified on main. Release notes ready at docs/release_notes/gl_native_rendering.md; no release cut.**

**🧪 EXPERIMENT A BUILT (graphics-vulkan-engineer, 2026-06-29) — branch `exp/gl-scanout-composer-overlay-ahb` (`75115bb`, off main `9c7156c`, NOT merged). CI run `28390468273`.** GL native path blits the guest game AHB (one passthrough quad, SAME size/orientation/scale, NO pre-rotation) into a HOST AHB allocated `GPU_SAMPLED|GPU_FRAMEBUFFER|COMPOSER_OVERLAY` (BGRA_8888, swapRB still applies), presents THAT — isolates buffer-usage/UBWC hypothesis from ROT_90. Files: `gpu_image.c` createScanoutHardwareBuffer JNI; `GPUImage.java` scanout ctor; NEW `ScanoutBlitMaterial.java` (passthrough+V-flip); `GLRenderer.presentScanout` rewritten to marshal blit to GL thread (queueEvent+latch, epoll thread has no GL ctx) +ensureHostScanout/releaseHostScanout. Fallback: alloc/FBO/roundtrip fail or 3 timeouts → sticky-revert to direct guest present (never black-screens). +1 quad blit/frame. Caveat: samples guest tex post-unlock w/o write-fence → rare tearing on fast frames (irrelevant to signal). **DEVICE GATE: GL+Native ON, dumpsys winlator_game_buf CLIENT→DEVICE at transform 90? YES=usage/UBWC was blocker (rotation red herring, likely renderer-agnostic→explains Vulkan). STILL CLIENT=rotation implicated→Experiment B. Confirm cube renders correct (V-flip=1-line lever, doesn't affect reading).**

## 🧪 EXPERIMENT A DEVICE-TESTED 2026-06-29 — PROBE ENGAGED, but COMPOSER_OVERLAY usage RULED OUT as the blocker. Game still CLIENT.
GL container, Native ON, drawer closed, Adreno750. dumpsys: game `AHardwareBuffer` is now **pid 11930 (= the app process, `pidof com.winlator.banner`=11930 → host blit ENGAGED)**, buffer-cache `usage: 0xb00` (= COMPOSER_OVERLAY 0x800 + GPU_FRAMEBUFFER 0x200 + GPU_SAMPLED 0x100, no CPU flags) — vs the original guest buffer's 0x333. So the host AHB with COMPOSER_OVERLAY IS what's presented. **YET STILL `composition: DEVICE/CLIENT` (rejected), transform 90, BGRA_8888 non-UBWC (compressed:false), still scaled 1280×702→1080×1920.** Content rendered (not black; fallback did NOT trigger; no V-flip black-screen). 
**⇒ COMPOSER_OVERLAY usage flag is NOT the blocker (set it, still rejected). Engineer's leading hypothesis disproven.** Remaining co-varying suspects on the game layer vs the (would-promote) base: **(1) transform 90 (rotation), (2) non-UBWC/linear (base is RGBA_8888_UBWC), (3) the SC-level scale.** Note also: the base SurfaceView (UBWC/transform0) is ALSO DEVICE/CLIENT here while it was DEVICE/DEVICE in the OFF baseline → the rotated game layer poisons the whole stack to client (or plane budget). 
**▶️ Experiment B (the likely-decisive next probe): pre-rotate the blit + render the host AHB at exact panel res (1920×1080 so ROT_90→1080×1920 = NO SC scale), lock portrait for the probe. Neutralizes rotation AND scale at once, leaving only UBWC.** If B promotes → rotation/scale was it (path exists, w/ cursor/orientation plumbing cost). If B still CLIENT → it's UBWC (hard/maybe-impossible to force on a GL render-target AHB on Adreno) or fundamental → gate GL-native overlay unsupported on portrait; the clean overlay win stays the landscape-native-device path. Honest: diminishing returns; even B success = modest win (per-frame blit + plumbing); landscape-native handheld remains the clean payoff.

## 🧪 EXPERIMENT B BUILT (graphics-vulkan-engineer, 2026-06-29) — branch `exp/gl-scanout-prerotate-panelres` (`8b20b96`, stacked on Exp A `75115bb`, NOT merged). CI run `28392607613`.
Extends Exp A: host scanout AHB allocated at PANEL res (1080×1920, portrait-locked), blit ROTATES guest frame 90° into it (new `ScanoutBlitRot90Material`, UV transpose) so content is display-oriented, then presents at **transform 0, src==dst (no SC scale)** via setContainerSize/setDst(0,0,pw,ph). Activity portrait-locked during native (`setProbeOrientation(true)` in enableScanout, restore sensorLandscape on native-off; configChanges has orientation|screenSize → reconfigure in place, NO activity recreation). Keeps COMPOSER_OVERLAY usage + the sticky fallback (alloc/FBO/roundtrip fail or 3 timeouts → direct guest present, never black). Files: NEW `ScanoutBlitRot90Material.java`; `GLRenderer.java` (blitGuestIntoHostScanoutAndPresent → panel-res+rotate+transform0/src==dst, setProbeOrientation, portrait lock wiring). DIAGNOSTIC: cursor/HUD/input + image orientation intentionally WRONG — only the dumpsys reading + not-black matter. **DEVICE GATE: GL+Native ON, dumpsys winlator_game_buf = `DEVICE/DEVICE` at transform 0? DEVICE→rotation/scale was the blocker (path exists w/ cursor/orientation plumbing). STILL CLIENT→UBWC/compression or fundamental→gate GL-native overlay unsupported on portrait; landscape-native device = the win.** Confirm cube image present (rotated/odd = fine, just not black).

## 🟢🟢 EXPERIMENT B DEVICE-TESTED (2026-06-29, post-crash resume) — PROMOTES! TRUE HWC OVERLAY ON THE PORTRAIT DEVICE. The "C-win unattainable on portrait" bombshell is OVERTURNED.
Device "Pocket FIT" Adreno750/SD8Gen3, GL|DXVK, AIO DX11 cube, Native ON (game already foreground after the session crash; 14 scanout SCs alive; captured with drawer open — promotion holds with drawer up).

**DPU/SDM hardware composition pipe table (ground truth — the Snapdragon Display Engine's actual scanout plan, NOT a requested-hint column, so immune to the earlier requested-vs-actual misread):**
- idx0 base SurfaceView  RGBA_8888_UBWC  = SDE  (overlay)
- idx1 **GAME  BGRA_8888  = SDE pipe149, src 0 0 1080 1920 -> dst 0 0 1080 1920 (NO SCALE), Transform 0**
- idx2 cursor  RGBA_8888 16x16  = SDE
- idx3 VRI  RGBA_8888_UBWC  = SDE
- idx5 GPU_TARGET = **NO layers assigned = GPU does ZERO composition**

Raw HWC `layer:` list corroborates (game/cursor are named `AHardwareBuffer pid[15581]` = the app process = exp-B host-blit AHB, NOT `winlator_*` — that's why the first grep missed them):
- game  z1  `composition: DEVICE/DEVICE`  BGRA_8888  transform 0/0/0
- cursor z2 `composition: DEVICE/DEVICE`  RGBA_8888  transform 0/0/0
- base + VRI  `composition: DEVICE/DEVICE`  RGBA_8888_UBWC  transform 0/0/0
- count of `DEVICE/CLIENT` + `CLIENT/CLIENT` across the whole dump = **0**

Screenshot (scratchpad/eb1.png) = image produced, not black (Wine window chrome visible on the strip beside the open drawer; orientation/aspect intentionally wrong per the diagnostic design).

**VERDICT: the HWC-overlay blocker was ROTATION (transform 90) + SCALE (src != dst) TOGETHER — NOT UBWC, NOT GLSurfaceView, NOT portrait-orientation-per-se.** Pre-rotating the guest frame 90° into a panel-res (1080x1920) host AHB (so it's display-oriented = transform 0) + presenting src==dst (no SC-level scale) + portrait-locking the activity => this Adreno DPU accepts a **plain non-UBWC BGRA_8888** buffer on a hardware overlay pipe.

**Overturns:** (a) bombshell "C-win unattainable on this portrait device for ANY renderer" = FALSE — C IS attainable, you must hand the DPU a transform-0, unscaled buffer; (b) the "UBWC required" hypothesis (Exp A's last suspect) = FALSE (game promoted as plain BGRA_8888); (c) the landscape long-shot finding (ROT_90 applied by the scanout handoff, not the display orientation) = CONFIRMED as the cause, and Exp B's pre-rotation is the fix.

**Exp B is a DIAGNOSTIC THROWAWAY (branch `exp/gl-scanout-prerotate-panelres` `8b20b96`, do-NOT-merge as-is):** hardcoded 90° UV-transpose one direction; cursor/HUD/input/aspect intentionally wrong; extra full-frame GPU blit + GL-thread roundtrip per frame; portrait-lock hack.

**Next (decision pending with user) — productionize C-win on portrait vs. ship latency-only:** orientation-aware correct pre-rotation (all 4 rotations, right handedness/flip), fix cursor/HUD/input mapping under the rotated present, weigh the host-blit cost (extra blit + roundtrip + possible base double-buffer) against the overlay/GPU-idle win — vs. just keeping the already-merged latency-only P4+P5. Container left Native ON, exp-B build installed, Claude/Termux session brought back to foreground.

## ⏸️ PHASE 0 (GL native overlay portrait — power/perf A/B) STARTED then PAUSED by user (2026-06-29) — SETUP ONLY, NO NUMBERS. Resume when home.
User chose to run P0 (measure-first), then stopped it (about to lose Wi-Fi). Reached SETUP only; no OFF/ON numbers captured. Partial log: `scratchpad/p0_results.md`.

Resume state:
- Test container = `xuser-3` "P11 x86-64" (renderer=opengl, rendererNative=false). exp-B build (`exp/gl-scanout-prerotate-panelres` `8b20b96`) is the installed/active APK.
- **Container config WAS MODIFIED:** DXVK `dxwrapperConfig` framerate `0`->`60` (maxFrameRate, guest-side cap for matched-FPS A/B). **Backup at `<imagefs>/home/xuser-3/.container.p0bak`.** Keep the 60 cap to resume P0, or restore from backup to abandon.
- Termux/Claude session brought back to foreground.

Resume recipe: launch GL container xuser-3 -> AIO DX11 cube -> enable perf HUD -> State A Native OFF (dwell ~30s, 3+ samples) -> State B Native ON (toggle in drawer, dwell ~30s, same samples). Sample {`/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`, `gpuclk`, `/sys/class/power_supply/battery/current_now` uA, thermal_zone temp, displayed FPS}. Confirm overlay via dumpsys SDE pipe table (ON: game AHB DEVICE/DEVICE on SDE pipe + GPU_TARGET empty; OFF: base SurfaceView DEVICE/DEVICE, no game AHB). VERDICT: ON lower GPU% AND power at equal FPS -> portrait worth productionizing; flat/worse -> drop portrait, headline the free landscape-native win.


## 🔴 STEAM DOWNLOAD FIX (2026-07-03) — LogonSessionReplaced regression on rebuilt stack (#1+#2+#4). Branch `feat/steam-goldberg-patcher`.
**Symptom:** After a successful sign-in, Steam game downloads fail on the detail page: "Download failed: Steam session not ready — sign in again or retry in a moment" (device: HL2 appId 220, davidroethlein@comcast.net). Foreground-service notification stuck at "Connecting to Steam…".

**Evidence:**
- `steam_debug.txt`: `SteamClient: connected=true, loggedIn=false` → `Not logged in — waiting for session…` → `ensureLoggedIn → false` after 15s. File does NOT record WHY.
- logcat (SteamRepo) is where the reason lives:
  ```
  17:47:11.851 Connected to Steam CM / Auto-login as davidroethlein@comcast.net
  17:47:12.857 Logged in as davidroethlein@comcast.net        ← login SUCCEEDS
  17:47:12–17:47:17 Library sync (229 apps, depot filter) — ALL on pump thread 23943 (blocks callbacks ~5s)
  17:47:17.191 Library sync complete: 229 apps
  17:47:17.191 Logged off: LogonSessionReplaced               ← queued LoggedOff dispatched the instant pump freed
  ```

**Root cause (self-inflicted double-login):** The Steam stack was REBUILT and lost the old `feat/steam-detail-revamp` fixes (single-flight logon `ceeeeb5`, dead-token `e383393` — never merged into the goldberg line). So: TWO logons fire for the same account — foreground-service `onConnected` auto-login (SteamRepository.java:436) + interactive `SteamLoginActivity.kt:197` loginWithToken — neither guarded. The 2nd replaces the 1st; Steam sends `LoggedOff: LogonSessionReplaced` for the older session; `onLoggedOff` (:506) treats that as TERMINAL (emits LoggedOut, no recovery) and clobbers the good LoggedOn's `loggedIn=true` → stuck `connected=true, loggedIn=false` → every download "session not ready". The 5s library sync on the single pump thread delays the LoggedOff so it lands AFTER the good LoggedOn, guaranteeing the clobber.

**Fixes applied (#1+#2+#4):**
1. **Single-flight logon guard** — `loginWithToken` skips if already `loggedIn` or a logon is in flight (`loggingOn` AtomicBoolean + `logonStartedAt`; supersede only a stalled logon >LOGON_STALL_MS=12s so we can't lock out forever). Released in onLoggedOn/onLoggedOff/onDisconnected. Kills the double logon at the source.
2. **No self-kill on LogonSessionReplaced** — `onLoggedOff` treats a `LogonSessionReplaced` arriving within SELF_REPLACE_WINDOW_MS=15s of our own logon (`lastSelfLogonAt`) as self-inflicted → ignores it (does NOT clear loggedIn or emit LoggedOut; the newer session is live). Genuine/old replacement (real "logged in elsewhere") still surfaces LoggedOut as before.
3. **Log the reason into steam_debug.txt (#4)** — repo records `lastSessionStatus` (LoggedIn / LoginFailed:<r> / LoggedOff:<r>); SteamDepotDownloader dlogs it into steam_debug.txt when ensureLoggedIn fails, so the file the UI points to actually contains the cause next time.

**Deferred:** #3 (move library sync off the pump thread) and #5 (wire the "Connecting to Steam…" notification to real state — currently dead `updateNotification`). Separate follow-up.

**Status:** ✅ implemented + committed `c72d943` (The412Banner) + pushed `feat/steam-goldberg-patcher`. CI is `workflow_dispatch` (not on-push) → manually dispatched **CI Build (artifacts only) run `28685150972`** on sha c72d943 (building, ~16min). Key files: `SteamRepository.java` (single-flight guard `loggingOn`/`logonStartedAt`/`lastSelfLogonAt`, self-replace branch in onLoggedOn/onLoggedOff/onDisconnected, `getLastSessionStatus()`), `SteamDepotDownloader.kt` (dlog `lastSessionStatus` on ensureLoggedIn-fail).
**NEXT (device-test once green):** install APK, download HL2 (appId 220) end-to-end. Pass = NO `Logged off: LogonSessionReplaced` teardown in logcat + download proceeds past manifest. If it still fails, `steam_debug.txt` now prints `Session status at failure: <reason>`. Deferred #3 (library sync off pump thread) + #5 (FGS notification) remain.


## 🛡️ STEAM SESSION HARDENING PLAN (2026-07-03) — adopt 5 GameNative/Pluvia patterns
**Why:** ~7 distinct root causes for "downloads fail after login" in ~2wk = architectural, not one bug. Our rebuilt `SteamRepository` models session state as hand-flipped volatile booleans + runs heavy work on the callback pump → every new path is a new race. Today's fix (`c72d943`) plugs ONE hole; it does NOT give us the properties that make GameNative solid.

**Basis:** mapped GameNative/Pluvia `SteamService.kt` (~4481 lines, SAME JavaSteam lib) at `/data/data/com.termux/files/home/GameNative/…/service/SteamService.kt` vs our `SteamRepository.java`. Their robustness = derived login state + non-blocking pump + one bounded reconnect funnel + keep-alive/watchdog + dead-token clearing. We lost/never-had 4 of 5. We ARE ahead on one axis (download `ensureLoggedIn` gate — they have none; keep it). This is NOT the canceled full Pluvia port — it's grafting 5 patterns onto the existing stack.

**Plan (priority order):**
1. **Derive `isLoggedIn` from `steamClient` SteamID validity — delete the `loggedIn` volatile boolean.** *Highest ROI, small.* Ref GameNative SteamService.kt:425. Kills the "connected=true/loggedIn=false stuck forever" class outright.
2. **Move library/PICS sync OFF the pump thread** (was "deferred #3", now known core). onPICSProductInfo/onLicenseList do the 229-app filter synchronously on the pump HandlerThread → blocks runWaitCallbacks ~5s → delays callbacks/heartbeats (the timing amplifier behind today's clobber). GameNative re-dispatches all heavy handler work to `scope.launch` children (SteamService.kt:4050, 3737+).
3. **One reconnect funnel** — collapse our 3 overlapping recovery paths (onDisconnected + onLoggedOff + ensureLoggedIn, separate retry budgets) into a single cancel-and-replace job with exponential backoff (GameNative SteamService.kt:3654,3683; MAX 20, cap 60s, retryAttempt reset on connect).
4. **Re-add dead-token clearing** — lost from abandoned `e383393`. Clear creds on InvalidPassword/Expired/Revoked/AccessDenied → stop hammering a dead token (GameNative:2876,3792) + emit SessionInvalid → route to sign-in.
5. **Keep-alive ping + connect watchdog** — cheap, prevents idle CM drops (GameNative pingInterval 15s :3456 + post-connect BAD-CM watchdog :3563). We're TCP-only so add the watchdog; investigate a TCP heartbeat.

**Guardrails:** Do 1+2 first (biggest ROI). Do NOT start editing until today's `c72d943` (CI run `28685150972`) is DEVICE-CONFIRMED on HL2 — don't stack unproven changes. One item per commit, device-verify each. Branch `feat/steam-goldberg-patcher`. Also-deferred #5 = wire the dead `updateNotification` (cosmetic).
