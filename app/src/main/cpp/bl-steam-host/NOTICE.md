# bl-steam-host — notices

`bl-steam-host` (this directory) is licensed under the **GNU General Public License v3.0 or
later** (SPDX: `GPL-3.0-or-later`). Copyright (C) 2026 The412Banner (Bannerlator).

## Derived from WinNative (GPL-3.0)

The bring-up sequence, the environment-before-`dlopen` contract, the sibling preload order,
the `<HOME>/Steam/config` staging, the `Steam_CreateGlobalUser` →
`CLIENTENGINE_INTERFACE_VERSION005` → `IClientUser` refresh-token logon path, the callback
pump and the `Steam_LogOff` / `Steam_ReleaseUser` / `Steam_BReleaseSteamPipe` teardown are
modelled on WinNative's `wn-steam-bootstrap`
(`app/src/main/cpp/wn-steam-bootstrap/src/steam_bootstrap.cpp`, GPL-3.0). This host is a
standalone process (status over a loopback socket, credentials over scrubbed environment
variables) rather than a JNI module, and its vtable slot table was re-verified against the
exact Valve build it pins (see `client_iface.h`).

WinNative: https://github.com/ (see the project's own LICENSE) — GPL-3.0.

## What this does NOT contain

No Valve code or binaries. `libsteamclient.so`, `steamservice.so`, `libtier0_s.so`,
`libvstdlib_s.so` and `libsteamnetworkingsockets.so` are downloaded at runtime from Valve's
own client-update CDN (`client-update.steamstatic.com`, `steam_client_linuxarm64` manifest,
package `bins_androidarm64_linuxarm64`), verified against the manifest's `sha2`, and stored
in the app's private data. They are never part of the APK or of this repository. Their use is
governed by the Steam Subscriber Agreement.

The private interface slot numbers in `client_iface.h` were derived from the library's own
reflection strings (`InterfaceMapBase<IClientUser>` method-name runs), from the public
open-steamworks interface layouts, and from the GPL WinNative bootstrap; no Valve source was
used.
