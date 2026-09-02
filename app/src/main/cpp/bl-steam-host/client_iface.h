// bl-steam-host — private Steam client interface contract (slot table).
//
// SPDX-License-Identifier: GPL-3.0-or-later
// Modelled on WinNative's GPL wn-steam-bootstrap (steam_bootstrap.cpp) — see NOTICE.md.
//
// libsteamclient.so exposes its private IClient* interfaces through CreateInterface()
// with NO public header. The vtable slots below are pinned to one Valve client build and
// were verified against that exact binary (not copied blindly):
//
//   Valve steam_client_linuxarm64 manifest "version" "1788291500",
//   package bins_androidarm64_linuxarm64 (sha2 d8c1a969…ef7aa), androidarm64/libsteamclient.so
//   (37,517,612 B, BuildID 3888f819bfd3751b846b27bad1188c5482031668).
//
// Evidence, per interface:
//   • The library carries its own reflection tables — `InterfaceMapBase<IClientUser>` etc. —
//     a contiguous run of method-name strings in VTABLE ORDER (used by its IPC marshalling).
//     IClientUser's run starts "LogOn, InvalidateCredentials, LogOff, BLoggedOn, …" (file
//     offset 0x662e03) and lists BHasCachedCredentials at run index 48, SetLoginInformation 53,
//     SetLoginToken 55, BUpdateAppOwnershipTicket 68. The vtable has ONE leading local (non-IPC)
//     method, GetHSteamUser (public open-steamworks layout), so vtable slot = run index + 1:
//     49 / 54 / 56 / 69 — exactly the offsets 0x188 / 0x1B0 / 0x1C0 / 0x228 that WinNative's
//     Ghidra work, GameNative's libsteambootstrap.so (disassembled: `ldr x26,[x8,#432]`,
//     `ldr x25,[x8,#448]`, `ldr x9,[x8,#392]`, `ldr x26,[x8,#552]` right after
//     `ldr x8,[x8,#64]` = IClientEngine::GetIClientUser) and our own device-proven SteamLite
//     agent (steamclient64.dll, same client generation: 0x188 BHasCachedCredentials,
//     0x1C0 SetLoginToken) all use.
//   • IClientApps run (offset 0x66708b): GetAppData, SetLocalAppConfig, …, RequestAppInfoUpdate
//     at index 7 — no leading local slot (matches the public layout); GameNative's blob calls
//     `[x8,#56]` = slot 7 in its steam_prepare_app.
//   • IClientFriends run (offset 0x664ad4): GetPersonaName, SetPersonaName, SetPersonaNameSDK,
//     IsPersonaNameSet, GetPersonaState, SetPersonaState = index 5 (GameNative: `[x8,#40]`).
//   • IClientEngine: GetIClientUser = slot 8 (0x40) — WinNative, GameNative (`[x8,#64]`) and
//     our agent agree; GetIClientFriends = 13 (0x68) and GetIClientApps = 17 (0x88) from
//     GameNative's blob (its "interfaces resolved - IClientApps=%p IClientAppManager=%p
//     IClientUser=%p IClientFriends=%p" printf order maps x24←slot 17, x23←slot 43,
//     x25←slot 8, x26←slot 13).
//
// A different Valve build may move any of these. The host refuses to drive an unpinned
// build unless BL_STEAM_HOST_ALLOW_UNVERIFIED=1 is set, and it sanity-checks at runtime that
// every slot it is about to call points into libsteamclient.so's text (dladdr) before the
// first virtual call. A wrong slot crashes THIS process only — the host is a separate ELF for
// exactly that reason.
#pragma once

#include <cstdint>

namespace blhost {

struct SlotTable {
    const char* valveVersion;      // manifest "version" the table was verified against
    // IClientEngine (CLIENTENGINE_INTERFACE_VERSION005)
    int engineGetIClientUser;      // (HSteamUser, HSteamPipe, const char* version) → IClientUser*
    int engineGetIClientFriends;   // (HSteamUser, HSteamPipe, const char* version) → IClientFriends*
    int engineGetIClientApps;      // (HSteamUser, HSteamPipe, const char* version) → IClientApps*
    // IClientUser
    int userLogOn;                 // EResult LogOn(CSteamID)   — the actual logon trigger
    int userBLoggedOn;             // bool BLoggedOn()
    int userBHasCachedCredentials; // bool BHasCachedCredentials(const char* account)
    int userSetAccountNameForCachedCredentialLogin; // (const char* account, bool)
    int userSetLoginInformation;   // (const char* account, const char* password, bool remember)
    int userSetLoginToken;         // (const char* refreshToken, const char* account)
    int userBUpdateAppOwnershipTicket; // bool (AppId_t, bool onlyIfStale)
    // IClientApps
    int appsRequestAppInfoUpdate;  // bool (const AppId_t*, int count)
    // IClientFriends
    int friendsSetPersonaState;    // void (EPersonaState)
    // Highest slot the runtime sanity check must cover, per interface.
    int userSlotsToCheck;
};

// The only verified build so far. Add a row per verified Valve build; never edit a row in
// place without re-running the verification described above.
static constexpr SlotTable kSlotTables[] = {
    {
        "1788291500",
        /* engine */ 8, 13, 17,
        /* user   */ 1, 3, 49, 50, 54, 56, 69,
        /* apps   */ 7,
        /* friends*/ 5,
        /* check  */ 70,
    },
};

// Interface version strings (CreateInterface / Get* names). CLIENTUSER_… is what the
// public-SDK adapters pass as the third argument of GetIClientUser; the Android lib's
// GetIClientUser takes (user, pipe) — passing a valid string in the next register is harmless
// on AArch64 and keeps us safe if a build reads it.
static constexpr const char* kClientEngineVersion  = "CLIENTENGINE_INTERFACE_VERSION005";
static constexpr const char* kClientUserVersion    = "CLIENTUSER_INTERFACE_VERSION001";
static constexpr const char* kClientFriendsVersion = "CLIENTFRIENDS_INTERFACE_VERSION001";
static constexpr const char* kClientAppsVersion    = "CLIENTAPPS_INTERFACE_VERSION001";

// CallbackMsg_t as laid out by Steam_BGetCallback (public steam_api_internal.h).
struct CallbackMsg {
    int32_t  hSteamUser;
    int32_t  iCallback;
    uint8_t* pubParam;
    int32_t  cubParam;
};

// Callback ids we interpret (public isteamuser.h / isteamapps.h).
enum : int {
    kCbSteamServersConnected    = 101,
    kCbSteamServerConnectFailure = 102,   // { EResult m_eResult; bool m_bStillRetrying; }
    kCbSteamServersDisconnected = 113,    // { EResult m_eResult; }
    kCbAppInfoUpdateComplete    = 1005,   // AppInfoUpdateComplete_t (k_iSteamAppsCallbacks 1000 + 5)
};

}  // namespace blhost
