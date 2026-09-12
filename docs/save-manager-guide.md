# Bannerlator Save Manager — Complete Guide

*One screen to protect and move your game saves so you never lose progress — whether the game is from Steam or a game you imported yourself.*

---

## Table of contents

1. [What the Save Manager is](#1-what-the-save-manager-is)
2. [How to open it](#2-how-to-open-it)
3. [The three tiers: Cloud, Library, Container](#3-the-three-tiers-cloud-library-container)
4. [Reading the status pills](#4-reading-the-status-pills)
5. [Syncing your saves](#5-syncing-your-saves)
6. [Auto-Collect: automatic backups on exit](#6-auto-collect-automatic-backups-on-exit)
7. [Backups before you uninstall](#7-backups-before-you-uninstall)
8. [Cloud honesty: when Steam Cloud isn't really there](#8-cloud-honesty-when-steam-cloud-isnt-really-there)
9. [The universal custom-game vault](#9-the-universal-custom-game-vault)
10. [Steam connection and the status badge](#10-steam-connection-and-the-status-badge)
11. [Where your saves physically live](#11-where-your-saves-physically-live)
12. [Safety guarantees](#12-safety-guarantees)
13. [FAQ and troubleshooting](#13-faq-and-troubleshooting)

---

## 1. What the Save Manager is

The Save Manager is a single screen that keeps your game saves safe and lets you move them between three places: **Steam Cloud**, a **local Library** on your device, and the **game's container** (where the game actually reads and writes while you play).

It works for two kinds of games:

- **Steam-library games** — installed through Bannerlator's built-in Steam store. These can sync with Steam Cloud and are also backed up locally.
- **Custom games** — anything you imported yourself (an `.exe` or a folder). These get their own local **vault** with backup and restore. No cloud is involved for custom games.

The whole point is simple: **your progress should survive**. It should survive closing a game, switching containers, uninstalling a game, and even removing a game entirely.

> ⚠️ **Third-party cloud sync.** Steam Cloud sync in Bannerlator is an independent, community implementation of Steam's save protocol — not an official Valve feature. It works well, but use it at your own risk. Your local backups never depend on it.

---

## 2. How to open it

There are several ways in, and they all lead to the same **Save Manager** screen:

- **Side menu → Library → "Save Manager."** Open the app's navigation drawer and tap **Save Manager** in the Library section. This is the main way in.
- **Steam store toolbar.** From the Steam games screen, tap the **Save Manager** icon in the toolbar.
- **A game's ⋮ menu (Games tab).** On a **Steam** game, the ⋮ menu has a **Cloud Saves** entry that opens the Save Manager already scrolled to and highlighting that game. On a **custom** game, the ⋮ menu instead shows **Back up saves** and **Restore saves** (local vault actions).
- **A game's detail page.** Each Steam game's detail page has a **Steam Cloud Saves** section with the same controls, plus an **Advanced** area for the individual steps.

When you open the Save Manager, it loads **instantly** — the status you see is read from a small saved record and a quick scan of your device, with **no network call**. To check the live cloud state, **pull down to refresh**.

> 💡 **Tip.** The list is sorted so the games that **need your attention** float to the top — things like "not backed up yet" or "cloud is ahead." Games that are already in sync sink to the bottom.

---

## 3. The three tiers: Cloud, Library, Container

Bannerlator manages your Steam saves across three layers. Think of them as three copies with different jobs:

| Tier | What it is | Job |
|------|-----------|-----|
| **Cloud** | Your saves stored on Steam's servers (Steam Cloud / UFS) | Off-device backup, shared across machines |
| **Library** | A managed folder on your device that Bannerlator owns | The canonical local copy — the safe middle ground |
| **Container** | The Wine "prefix" the game actually plays in | Where the game reads/writes live save files |

Saves move between adjacent tiers:

- **Cloud → Library** (Download) — pull your Steam Cloud saves onto the device.
- **Library → Container** (Apply) — put those saves into the game so it can load them.
- **Container → Library** (Collect) — grab the saves the game just wrote.
- **Library → Cloud** (Upload) — push your local saves up to Steam Cloud.

You rarely do these one at a time. The Save Manager gives you two **one-tap combos** that chain the steps for you:

- **⬇ Sync from Cloud** = Download **then** Apply (Cloud → Library → into your game).
- **⬆ Sync to Cloud** = Collect **then** Upload (your game → Library → Cloud).

If you want the individual steps, they live under **Advanced** on a game's detail page.

### Uploads are smart and additive

When you Sync to Cloud, Bannerlator does an **incremental** upload. It fetches the cloud file list first and compares each of your local files against the cloud copy using a **SHA-1 fingerprint** (a per-file checksum). A file is skipped only if an **identical** copy is already in the cloud; anything new or changed is uploaded. If it can't prove a file is identical, it uploads it — correctness over speed.

Uploads are also **strictly additive**: Bannerlator **never deletes anything from your Steam Cloud**. There is no code path that removes cloud files. An empty local folder is refused before an upload even starts, so "sync nothing" can never turn into "wipe the cloud."

---

## 4. Reading the status pills

Every game in the Steam list shows a colored **status pill** so you know at a glance where things stand:

| Pill | Meaning |
|------|---------|
| 🟢 **In sync** | Local and cloud match — nothing to do. |
| 🟠 **Not backed up** | You've played, but these saves were never backed up anywhere. Worth syncing. |
| 🟡 **Local ahead** | Your device has newer saves than the last sync — upload them. |
| 🔵 **Cloud ahead** | Steam Cloud has newer saves than your device — sync them down. |
| 🟠 **Never synced** | The cloud has saves, but you've never brought them down. |
| ⚪ **No cloud saves** | This game has no cloud saves stored. |
| ⚪ **Not set up** | The game isn't in a container yet, so there's nowhere to apply saves. |

The **"Not backed up"** state (internally `LOCAL_ONLY`) is important: it makes a **played-but-never-synced** game visible so you can back it up before it's too late. Previously such a game was invisible in the list until you manually collected it.

Under the name you'll also see a **"Downloaded … · Uploaded …"** line showing when each direction last happened, and the game's current **container**.

> 💡 **Tip.** If a game shows **Not set up**, its sync buttons are disabled. Tap the row to open the detail page and add the game to a container first — its saves then follow whichever container you pick.

---

## 5. Syncing your saves

On each row in the Steam list you get two quick buttons:

- **⬇ Sync from Cloud** — brings your cloud saves down and into the game.
- **⬆ Sync to Cloud** — collects your in-game saves and pushes them to the cloud.

While a sync runs, the row shows a live progress line ("Downloading… / Applying… / Uploading…") and a small spinner. When it finishes, the timestamps and pill update **immediately**.

A few sensible guardrails:

- **Sync from Cloud is disabled when the cloud has nothing** for that game — there'd be nothing to bring down, so the meaningful action (backing it up) stands out instead.
- **Both combos need a container.** If the game isn't set up in one, you get a single clear message: *"This game needs to be added to a container first."*
- The detail page may warn you if applying or uploading would overwrite **newer** saves on the other side, so you don't clobber recent progress by accident.

---

## 6. Auto-Collect: automatic backups on exit

You don't have to remember to back anything up. When you **close a game**, Bannerlator automatically snapshots its saves:

- **Steam-library games** → **Collected** into that game's local Library (Container → Library). This is a **local** snapshot only — **nothing is uploaded to Steam Cloud automatically**.
- **Custom games** → snapshotted into the local **vault** (see [section 9](#9-the-universal-custom-game-vault)).

This runs **after** the game has fully closed and flushed its files, and it's **bounded** (capped at a few seconds) and fully guarded so it can never hang or interfere with exiting a game.

### Turning Auto-Collect on or off

Both auto-backup behaviors are controlled from the **Settings cog** in the Save Manager header. Tap it to open **Save Manager settings**, with two switches (both **ON** by default):

- **Steam games: auto-collect on exit** — *"Snapshot Steam-library saves to your local Library when a game exits."*
- **Custom games: auto-back up on exit** — *"Snapshot custom-import saves to the local vault when a game exits."*

When you **turn a switch OFF**, Bannerlator asks you to confirm with a **Continue / Cancel** warning explaining that saves won't be captured automatically anymore (Cancel leaves it ON). When you **turn one ON**, you get a brief confirmation. The setting only changes when you actually confirm.

> 💡 **Tip.** Leaving these ON is the safest choice for almost everyone. Turn them off only if you specifically want to manage backups by hand.

---

## 7. Backups before you uninstall

Removing a Steam game from its detail page triggers a **Collect first**: Bannerlator snapshots the game's saves into your local Library **before** deleting the game's files. The Library lives on external storage and already survives uninstalling, so this simply makes sure it's **current** at the moment of removal.

This is best-effort and **never blocks removal** — if the collect can't run (for example, the game was never set up in a container), the uninstall proceeds anyway and the reason is logged.

---

## 8. Cloud honesty: when Steam Cloud isn't really there

Not every Steam game actually keeps saves in Steam Cloud. Some older titles will **accept** an upload — the transfer "succeeds" — but Steam stores nothing afterward. A naive tool would happily report *"Uploaded 17 files"* and leave you thinking you're backed up when you're not.

Bannerlator refuses to lie about this. It checks cloud support **two ways**:

1. **Before uploading (the UFS/PICS check).** It reads the game's declared cloud configuration from Steam's product info (`ufs/savefiles`). If the game declares **no** save-file patterns, it has no cloud store — Bannerlator stops and tells you plainly instead of pretending.

2. **After uploading (the empty-manifest check).** Even if a game *claims* cloud support, Bannerlator re-checks the cloud right after a successful upload. If it committed files but the cloud comes back **completely empty**, that's the fingerprint of a game that doesn't actually retain saves.

In either case you get an honest message like:

> *"This game doesn't keep Steam Cloud saves — your saves are backed up locally in the Library."*

**Your saves are never lost** — they stay safely in the local Library. Bannerlator also **remembers** a game that doesn't retain cloud saves, so it won't waste time re-attempting or ever falsely claim success again.

**A concrete example:** *FlatOut 2* declares cloud save files, so it passes the first check — but committing an upload leaves Steam's cloud empty. The post-upload check catches it and reports "backed up locally," while a genuinely cloud-backed game like *Half-Life 2* returns a real file list and syncs normally.

---

## 9. The universal custom-game vault

Games you imported yourself (an `.exe` or a folder) aren't part of Steam Cloud, so they get their own local **vault** — the same protection, minus the cloud.

Custom games appear under the **Custom** tab in the Save Manager. Each row shows the game's art, its container, and whether it's **"Backed up <time ago>"** or **"No backup yet."** Per row you get:

- **Back up** — pick a backup **format** (see below), then Bannerlator finds the game's save folders and zips them.
- **Restore** — choose **which container** to restore the latest snapshot into, and the files are written there. (Restore is disabled until a backup exists.)

The same **Back up saves** / **Restore saves** actions are also available from a custom game's **⋮ menu** on the Games tab — they route through the exact same backup engine, so both agree.

### Auto-snapshot on exit

When Auto-Collect is on, closing a custom game writes a single **`auto-latest.zip`** snapshot that is overwritten each time you exit. This snapshot lives independently of the container and install, so it **survives the shortcut — or even the whole game — being removed**.

### Backup format

When you back up manually you'll choose between two archive layouts:

- **GameHub-compatible `.zip`** (default) — for GameHub and other Proton-based tools.
- **Winlator-native `.zip`** — for sibling Winlator / WinNative / Bannerlator builds.

The difference is only in how the archive labels its internal user folder; Bannerlator translates it correctly on restore either way, so you can move saves between tools.

### Where custom backups go

Every custom backup — manual **and** auto — lives in one per-game folder:

```
Downloads/Bannerlator/game saves/<Game Name>/
```

Inside it:

- **`auto-latest.zip`** — the single overwrite-on-exit auto snapshot.
- **`<Game Name>_<timestamp>.zip`** — your manual backups (kept as history, one per backup).

> 💡 **Tip.** If Bannerlator can't identify a specific save folder for a custom game, a manual backup falls back to archiving the **whole container** and tells you it did — so you're still covered.

---

## 10. Steam connection and the status badge

The Save Manager header shows a live **Steam connection badge** whenever you're signed in to a Steam account:

- It reflects your real-time connection state.
- If you're **offline**, tap the badge to **reconnect**.

Because you can reach the Save Manager without going through the Steam store first, it **auto-connects** to Steam for you when you open it (as long as you're signed in). If you only use custom games and never signed in to Steam, no Steam service or badge appears at all.

**What happens offline:** local actions still work fully — the instant status, Auto-Collect, custom-game backup/restore, and everything in your Library and vault are all local. Only the cloud steps (Download / Upload, and the pull-to-refresh live diff) need a connection. If you're not signed in, cloud actions report *"Not signed in to Steam"* rather than failing silently.

---

## 11. Where your saves physically live

Everything the Save Manager creates is stored in plain, browsable folders on your device:

- **Steam local Library** (per game, keyed by Steam App ID):

  ```
  Bannerlator/SteamCloudSaves/<appId>/
  ```

- **Steam sync record** (the tiny status file the instant view reads):

  ```
  Bannerlator/SteamCloudSaves/_status.json
  ```

- **Custom-game vault** (per game, by name):

  ```
  Downloads/Bannerlator/game saves/<Game Name>/
  ```

Steam and custom saves are kept **completely separate** — Steam saves live under an App-ID folder, custom saves under a game-name folder, so they never overlap.

Inside a container, the actual live save files sit under the game's Wine user profile (for example `…/.wine/drive_c/users/xuser/…`) and the game's install directory. The Save Manager knows how to translate between Steam's cloud path layout and those container locations, and it only ever touches recognized save roots.

---

## 12. Safety guarantees

The Save Manager is deliberately conservative:

- **It never deletes from Steam Cloud.** Uploads are strictly additive; the "delete list" sent to Steam is always empty. An empty local folder is refused before an upload even begins.
- **Restore targets a container you pick.** Nothing is restored anywhere until you choose the destination.
- **It only touches recognized save locations.** Path translation rejects anything trying to escape its mapped folder (no "`..`" traversal), and files it can't map are skipped, never guessed.
- **Auto-backups can't break game-exit.** They run after the game closes, are time-bounded, and swallow their own errors.
- **Snapshots are written atomically.** A custom-game snapshot is written to a temporary file and renamed into place, so a killed process can never leave a half-written backup where your last good one was.
- **The instant view never hits the network.** Opening the screen is a local read; cloud calls only happen when you explicitly sync or pull-to-refresh.

---

## 13. FAQ and troubleshooting

**"My save didn't upload — it says 'backed up locally' instead."**
That game's Steam Cloud doesn't actually retain saves (see [section 8](#8-cloud-honesty-when-steam-cloud-isnt-really-there)). This isn't an error — your saves are safe in the local Library, and Bannerlator is just being honest that Steam won't keep them. Nothing more to do.

**"A game shows 'Not set up' and I can't sync it."**
The game isn't in a container yet, so there's nowhere to apply or collect saves. Open the game's detail page (tap the row) and add it to a container. Its saves will then follow that container and the sync buttons light up.

**"The cloud says empty even though I just uploaded."**
Same as the first question — the game accepted the upload but Steam stored nothing. Your local Library copy is the real backup. Bannerlator remembers this so it won't keep re-trying.

**"How do I restore a save into a different container?"**
Use the **Custom** tab (for imported games) — tap **Restore**, then pick the target container in the dialog. For Steam games, add the game to the container you want, then **⬇ Sync from Cloud** (or use **Apply** under Advanced) to place the saves there.

**"An older Steam game showed 'Not set up' even though it's installed."**
Very old shortcuts were created before Bannerlator tagged games with their Steam App ID. Auto-Collect now **derives** the App ID from the game's install folder, and the Save Manager initializes the Steam database when you open it — so these games are recognized correctly. If a game still looks wrong, pull-to-refresh.

**"I opened the Save Manager right after installing the app and everything said 'Not set up.'"**
That was a first-launch timing issue where the Steam database hadn't initialized yet. It now initializes before the list loads. If you ever see it, just pull-to-refresh.

**"My timestamps looked stale right after a sync."**
Fixed — the "Downloaded/Uploaded" time is now written to disk **before** the row refreshes, so it's current the instant a sync finishes.

**"Does turning off Auto-Collect delete anything?"**
No. It only stops **future** automatic snapshots on exit. Existing Library and vault backups are untouched. You can still back up manually anytime from a game's ⋮ menu or the Save Manager.

**"I removed a game — are its saves gone?"**
No. Steam-game Library folders and custom-game vault zips live on external storage and survive uninstalling (and removing the shortcut entirely). For Steam games, a fresh Collect runs right before uninstall so the Library is current.

**"Where can I see the raw files?"**
Steam saves: `Bannerlator/SteamCloudSaves/<appId>/`. Custom saves: `Downloads/Bannerlator/game saves/<Game Name>/`. See [section 11](#11-where-your-saves-physically-live).

---

*This guide reflects the **Save Manager v2** as shipped in the Bannerlator **2.9.3** release.*
