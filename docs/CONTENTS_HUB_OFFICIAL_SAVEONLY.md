# Contents hub: Official catalog as a repository + "Save archive only"

## What
1. **Official catalog in the Download tab.** The built-in `contents.json`
   (`ContentsManager.REMOTE_PROFILES`, ~175 entries of `{type, verName, verCode, remoteUrl}`) is now
   listed as the FIRST repository in the Contents hub, badged **Official** (same green tag the
   container/shortcut Compatibility Layer sheet uses for its Official group). Before, that catalog was
   reachable only from inside a game's/container's settings.
2. **Save archive only.** Every downloadable row in the hub (components AND GPU drivers, every
   repository, search results too) gets a disk icon next to *Download & Install*. It downloads the raw
   archive straight into the Contents **save location** (`<save location>/components/<Type>/`) with NO
   extract and NO install — the app's contents folder is untouched. The row then shows the existing
   **Saved** badge and My Files lists the file immediately, annotated *saved only, not installed*.
   Reinstall from My Files runs the normal offline install and clears that annotation.

## Why
Users asked to fetch (or just keep) components from the official catalog without opening a game's
settings, and to bank archives for offline use / sharing without installing them.

## Where
| Piece | File |
| --- | --- |
| Official default source (first), `RemoteSource.isOfficial`, `show_official` pref, custom-URL dedupe | `app/src/main/java/com/winlator/star/ui/screens/contents/RemoteSourceRepository.kt` |
| Pure `contents.json` parser (exact `type` match first; substring fallback for aliases) | `…/contents/WcpJsonCatalog.kt` + `app/src/test/java/com/winlator/star/ui/screens/contents/WcpJsonCatalogTest.kt` |
| `ContentsInstaller.saveOnly(...)` — same registry key, FGS bracket, shade line, popup and Cancel as `install` | `…/contents/ContentsInstaller.kt` |
| `ContentDownloadState.saveOnly` (popup/row read "Saving…"/"Saved to My Files") | `app/src/main/java/com/winlator/star/store/download/ContentDownloadController.kt`, `InstallProgressDialog.kt` |
| Library: `saveRaw(…, saveOnly)` moves the temp into place when possible, `saved_only_keys` mirror, `SavedFile.savedOnly`, `markInstalledFromSaved` | `…/contents/ComponentLibrary.kt` |
| Hub UI: Official badge on repo rows + detail header, disk action (tap / long-press re-download / already-saved toast), My Files note, settings toggle + save-location note | `…/contents/ContentsHubScreen.kt`, `ContentsHubViewModel.kt` |
| Sheet: skips the `isOfficial` source when building community groups (it already renders that catalog as Official); shares `SourceTagBadge` / `OfficialSourceColor` | `app/src/main/java/com/winlator/star/ui/screens/ContentDownloadSheet.kt` |

## Behaviour notes
- **Hiding Official.** Menu → *Hide default repository* (removed-defaults, as for any built-in) or
  Contents settings → *Show Official catalog in repositories* (default ON). Both affect the hub only;
  the container/shortcut sheet keeps its Official group regardless. *Restore default repositories*
  resets both.
- **Dedupe.** A custom source whose URL equals a visible built-in's URL is not listed (the built-in
  wins). If Official is hidden, such a custom entry shows again.
- **Keep raw archive** semantics for *install* are unchanged. Save-only and keep-raw file to the same
  folder; a keep-raw copy of a file previously "saved only" clears the annotation.
- **Already saved:** disk icon dimmed; tap → toast with the folder; long-press → re-download
  (overwrites the file).
- **Cancel:** popup Cancel cancels the job; the download loop now checks for cancellation per chunk
  (this also tightens Cancel for regular installs). Temp `.part` files are cleaned up.
- **Type matching (WCP_JSON).** The official catalog lists `Box64` next to `WOWBox64` and `DXVK` next to
  `D7VK`; the old substring match filed WOWBox64 packs under Box64. The parser now matches the entry's
  `type` field exactly when it can, falling back to the substring match (keeps the `fex` alias working).
  StevenMXZ's built-in entry lists `wowbox64` explicitly so its WOWBox64 packs stay visible under the
  correct chip.
- **Landscape.** The hub's existing `wide` path (rail · repo list · detail) is unchanged; the new
  action is a fixed-width square beside a weighted button (`IntrinsicSize.Min` row) so rows never
  wrap; badges sit beside ellipsised names; the settings dialog body scrolls.
