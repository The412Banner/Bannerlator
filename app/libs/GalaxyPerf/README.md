# Samsung Galaxy Performance SDK (optional, not bundled)

The **Galaxy Performance** feature (`com.winlator.star.perf.galaxy.*`) lets Samsung Galaxy
devices control CPU / GPU / RAM-bus performance levels through Samsung's official
**Galaxy Performance SDK** — reducing heat, thermal throttling, and power draw during play.
It needs **no root**.

## This jar is intentionally NOT committed

The SDK (`perfsdk-v1.0.0.jar`, package `com.samsung.sdk.sperf`) is **proprietary to Samsung
Electronics** and is **not** covered by this project's GPL-3.0 license. Unlike the other jars
vendored under `app/libs/`, it is therefore **git-ignored** and must be supplied by the person
building the app, under Samsung's own SDK license.

Because the app loads the SDK purely by **reflection**, the project builds and runs perfectly
fine **without** this jar — the Galaxy Performance feature simply stays dormant (the UI hides
it and `GalaxyPerfManager.isSupported()` returns false). Drop the jar in and rebuild to enable
it on Samsung devices.

## How to enable it

1. Sign in to the Samsung Developer portal and download **Galaxy Performance SDK v1.0.0**:
   https://developer.samsung.com/galaxy-performance
   (Accepting Samsung's SDK License Agreement is part of the download.)
2. Place the jar here as:
   `app/libs/GalaxyPerf/perfsdk-v1.0.0.jar`
3. Rebuild. `app/build.gradle` picks the jar up automatically **only if it is present**
   (guarded `if (perfSdkJar.exists())`), so CI without the jar is unaffected.

The SDK requires the `android.permission.INTERNET` permission (already declared) for its
local socket to the on-device Samsung performance daemon.

## Redistribution

If you distribute a build that bundles this jar, ensure you have the right to redistribute the
Samsung SDK under the license you accepted. This repository does not redistribute it.
