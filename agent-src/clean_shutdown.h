#pragma once

#ifdef __cplusplus
extern "C" {
#endif

void wn_launcher_set_log_sink(void (*log_fn)(const char* line));

void wn_launcher_set_game_exe(const char* exeName);

void wn_launcher_arm_clean_shutdown(void* hSteamClient, int pipe, int user,
                                    const char* logPath);

void wn_launcher_set_cloud_context(void* engine, int hUser, int hPipe, unsigned int appId);

int wn_launcher_cloud_sync(void* engine, int hUser, int hPipe,
                           unsigned int appId, int cmd, int flags, int timeoutMs);

void wn_launcher_clean_shutdown_now(const char* reason);

void wn_launcher_wait_clean_shutdown(int maxMs);

// Optional: called (reason, exitCode) right before the shutdown paths that bypass
// main()'s return — the sentinel watcher's ExitProcess and the console-ctrl handler.
// NULL (default) = no-op.
void wn_launcher_set_exit_hook(void (*hook)(const char* reason, int code));

#ifdef __cplusplus
}

#include <string.h>

// Reduce an image name to the game's base: strip ".exe", then a trailing arch tag
// (_win64 / _win32 / _x64 / _x86 / _64 / _32 / 64 / 32). So cstrike.exe, cstrike_win64.exe and
// cstrike64.exe all collapse to "cstrike". Longest tags are checked first so "_win64" wins over "64".
static inline void wn_extract_game_base(const char* exe, char* out, size_t outsz) {
    size_t n = strlen(exe);
    if (n > 4 && _stricmp(exe + n - 4, ".exe") == 0) n -= 4;   // strip ".exe"
    static const char* const kArch[] = {
        "_win64", "_win32", "_x64", "_x86", "_64", "_32", "64", "32" };
    for (const char* a : kArch) {
        size_t alen = strlen(a);
        if (n > alen && _strnicmp(exe + n - alen, a, alen) == 0) { n -= alen; break; }
    }
    if (n >= outsz) n = outsz - 1;
    memcpy(out, exe, n);
    out[n] = '\0';
}

inline bool wn_game_image_matches(const char* procName, const char* gameExe) {
    if (!procName || !gameExe || !gameExe[0]) return false;
    if (_stricmp(procName, gameExe) == 0) return true;
    static const char* const kSteamlessSuffixes[] = { ".original.exe", ".unpacked.exe" };
    size_t glen = strlen(gameExe);
    for (const char* suf : kSteamlessSuffixes) {
        size_t slen = strlen(suf);
        if (glen > slen && _stricmp(gameExe + (glen - slen), suf) == 0) {
            char base[260];
            size_t blen = glen - slen;
            if (blen >= sizeof(base)) blen = sizeof(base) - 1;
            memcpy(base, gameExe, blen);
            base[blen] = '\0';
            return _stricmp(procName, base) == 0;
        }
    }
    // Source-engine (and similar) launcher hand-off, BOTH directions. Steam's LaunchApp launches
    // the game through its launcher <game>.exe, which hands off to the arch-specific child
    // <game>_win64.exe; a shortcut may name EITHER. If the resident watch tracks only one name it
    // misses the secure LaunchApp process, times out, and CreateProcess-launches an INSECURE copy
    // (VAC then refuses secure servers) — or logs off and orphans the game. Collapse both names to
    // the shared game base (cstrike.exe / cstrike_win64.exe -> "cstrike") and match on that, so the
    // agent adopts whatever Steam actually spawned and never falls back.
    char gb[260], pb[260];
    wn_extract_game_base(gameExe, gb, sizeof(gb));
    wn_extract_game_base(procName, pb, sizeof(pb));
    if (gb[0] && _stricmp(gb, pb) == 0) return true;
    return false;
}
#endif
