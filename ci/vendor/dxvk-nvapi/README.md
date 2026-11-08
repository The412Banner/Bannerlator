# dxvk-nvapi (our copy)

The two dlls of the release pinned in `app/src/main/assets/dxvk-nvapi/manifest.json`, kept here so the build
does not depend on GitHub's release downloads (`_build.yml` uses this copy when its sha256 matches the manifest,
else downloads the release).

- Project: https://github.com/jp7677/dxvk-nvapi (MIT; the license is in `app/src/main/assets/dxvk-nvapi/LICENSE`)
- `v0.9.2/nvapi64.dll` (x64) sha256 `1bcf9b68a53f35d94e9e7dfdb68244d74c6300f3979906009478c320a780e12e`
- `v0.9.2/nvapi.dll` (x32) sha256 `afb3bfad55c4f23acc7f7a2f09946f88c3da61bcf61c5f04771b400264876d19`

Taken 2026-11-08 from a CI build of Bannerlator (lab-fixes 082c2715, build-artifacts 42463721801) whose step had
downloaded the release tarball and checked both hashes. When the manifest is bumped to a new release, add that
release's dlls here as `<version>/` too.
