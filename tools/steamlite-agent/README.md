# SteamLite agent (steam.exe) — corresponding source

GPL-3.0 clean-room Steam agent shipped inside `steamlite.tzst` (release `steamlite-v1`,
package version 7, agent "p6", 2026-09-06; this tree adds agent "p7" — Steam Input, `steam_input.h`, 2026-10-05, not yet in a hosted package). Derived from WinNative's
`wn-steam-launcher` (GPL-3.0) — see NOTICE.md for attribution and the change history.

Build (MinGW-w64, x86_64 PE; runs in-Wine under Proton arm64ec + FEX):

    x86_64-w64-mingw32-g++-posix -std=c++17 -O2 -s -static -static-libgcc -static-libstdc++ \
      -Wl,--subsystem,windows -I. -o steam.exe main.cpp clean_shutdown.cpp -ladvapi32 -lws2_32

Shipped binary: md5 ffb1f9429f5a51df45689175c1fa2a9c (1,198,080 bytes).
Only steam.exe is ours; the Valve client files in the package are not GPL (VALVE_COMPONENTS.txt).


Agent p7 (2026-10-05): `steam_input.h` — Steam Input activation for the launched app (WN_STEAM_INPUT=1 /
WN_STEAM_INPUT_VDF), header-only, inert without the env; see docs/STEAMLITE_STEAM_INPUT.md. CI builds this
tree with `.github/workflows/build-steamlite-agent.yml` (same build line).

Agent p8 (2026-11-11, test only, not in a hosted package): Steam Client Service start waits. When the
first `StartService` fails (1053: Valve's service hands over to its own updater and exits) or the service
ends up stopped, the agent now waits up to 20 s (`WN_STEAM_SERVICE_WAIT_MS`, 0 = previous behaviour) for
it to reach RUNNING, starting it again only if it stays stopped, before going on to `LaunchApp`. Without
`Steam\bin\steamservice.exe` in the prefix (packages up to v8) the service code is skipped as before.
