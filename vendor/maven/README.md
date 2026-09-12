# Vendored Maven artifacts

This is a small in-repo **Maven repository** holding third-party artifacts we don't
want to depend on an external server for. It's wired up in `settings.gradle`:

```groovy
maven {
    url = uri("${rootDir}/vendor/maven")
    content { includeGroup 'io.github.joshuatam' }   // only serves this group
}
```

Transitive dependencies of these artifacts (ktor, okhttp, protobuf, okio, coroutines,
kotlin-stdlib, …) are **not** vendored — they resolve from Maven Central as usual.
Only the artifacts below live here.

A second, `exclusiveContent` entry in `settings.gradle` serves `org.libsdl.android` from
this same directory (see the SDL3 section below).

## What's here

### `io.github.joshuatam:javasteam` + `:javasteam-depotdownloader` — `1.8.0.1-26-20260801.180149-1`

GameNative's fork of JavaSteam (`joshuatam/JavaSteam @ gamenative-latest`). Its
depot-downloader disk-spools download chunks to temp files instead of holding them in
RAM, which fixes the large-game download OOM crash (issue **#408 / #380** — HITMAN WoA
~87 GB OOM'd at every speed tier on the public `in.dragonbra:javasteam:1.8.0`).

- **Device-proven** by the #408 reporter (HITMAN 87 GB downloaded to completion at the
  blazing tier). Proven CI build: run `32710611783`.
- Originally published **only** as a mutable `-SNAPSHOT` on Sonatype's snapshot repo
  (`central.sonatype.com/repository/maven-snapshots/`). We first pinned to the exact
  timestamped build (`…-20260801.180149-1`, buildNumber 1 — the only build that exists),
  then vendored the files here so a Sonatype snapshot purge can never break our build.
- The files are mirrored **verbatim** from Sonatype, including the `.pom`, Gradle
  `.module`, `maven-metadata.xml`, and all `.md5/.sha1/.sha256/.sha512` sidecars, under
  the original `1.8.0.1-26-SNAPSHOT/` directory. Because the dependency is pinned to the
  timestamped version, Gradle resolves it as a fixed (non-changing) artifact.

## How to update (when we intentionally bump the engine)

1. Pick the new fork version and find its timestamped snapshot build via
   `…/io/github/joshuatam/<artifact>/<X>-SNAPSHOT/maven-metadata.xml`.
2. Mirror the full `<X>-SNAPSHOT/` directory for **both** artifacts here — every
   `.jar/.pom/.module/maven-metadata.xml` plus their `.md5/.sha1/.sha256/.sha512`.
3. Bump the two `io.github.joshuatam:…` versions in `app/build.gradle`.
4. Delete the old version's files (this is the only time we touch them — "saved forever
   until we need to update").
5. Push to a branch and let CI resolve+compile before merging.

Do **not** edit any file in this tree by hand — the checksums must match the bytes.

### `org.libsdl.android:SDL3` — `3.4.16`

The official SDL 3.4.16 Android release AAR, used for optional **Steam Controller** support
(SDL's HIDAPI Steam drivers read the 2015 and 2026 Steam Controllers over Bluetooth LE / USB;
see `SteamControllerBackend.java`). SDL publishes this AAR only as a GitHub release asset,
not to a Maven repository, so it is vendored here.

- `SDL3-3.4.16.aar` is **byte-identical** to the AAR inside the upstream release asset
  `SDL3-devel-3.4.16-android.zip` from
  <https://github.com/libsdl-org/SDL/releases/tag/release-3.4.16>
  (zip sha256 `e1da8d9298471ea4ab8c6d48a804c0ac5415f35f30fcf7eba61aa7357e165765`,
  AAR sha256 `03710fc7b49cc070551446841a843840fabf2aaaaa3043cd5739292a55e4e61c`,
  upstream git hash `fa2c02bb6e21974a89ea9824bc53c9932abe5f9c`).
- `SDL3-3.4.16.pom` is hand-written (packaging `aar`, no dependencies), since upstream has no POM.
- The AAR carries SDL's Java classes (`org.libsdl.app.*`) plus a prefab package; the app links
  `libsteamctrl.so` against `SDL3::SDL3` via prefab (`buildFeatures.prefab`), which is how
  `libSDL3.so` gets packaged. It is used as `implementation 'org.libsdl.android:SDL3:3.4.16@aar'`.
- License: zlib (`META-INF/LICENSE.txt` inside the AAR).

To update: download the new `SDL3-devel-<ver>-android.zip`, verify it against the release,
copy the AAR out unmodified into `org/libsdl/android/SDL3/<ver>/` with a matching `.pom` and
`.aar.sha256`, bump the version in `app/build.gradle`, delete the old directory, and let CI
build before merging.
