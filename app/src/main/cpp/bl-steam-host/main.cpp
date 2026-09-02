// bl-steam-host — the app's genuine Steam session host for launchMode=AppSteam.
//
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Copyright (C) 2026 The412Banner (Bannerlator).
// Modelled on WinNative's GPL-3.0 wn-steam-bootstrap (app/src/main/cpp/wn-steam-bootstrap/
// src/steam_bootstrap.cpp: env-before-dlopen contract, sibling preload, config staging,
// Steam_CreateGlobalUser → CLIENTENGINE_INTERFACE_VERSION005 → refresh-token logon, callback
// pump, LogOff/ReleaseUser/ReleasePipe teardown). See NOTICE.md.
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version
// 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY
// WARRANTY; see <https://www.gnu.org/licenses/>.
//
// What it does
// ------------
// A standalone arm64 ELF (packaged as libblsteamhost.so so the APK carries it, exec'd from
// nativeLibraryDir like libinnoextract.so) that loads Valve's own androidarm64
// libsteamclient.so — downloaded at runtime from Valve's client CDN by SteamHostComponent,
// never bundled — and logs the user's account into it with the SAME refresh token the app's
// Rust engine is signed in with. The library then serves the Steam3Master / SteamClientService
// loopback listeners (127.0.0.1:57343 / :57344) that Proton's lsteamclient bridge inside the
// Wine game connects to, so the game's genuine steam_api64.dll gets a real, logged-on client
// while the app's own session (store, friends, chat, drawer Friends tab) stays up. Two logons,
// one player: the engine never reports "playing" while this host runs.
//
// Crash isolation: a moved vtable slot or a Valve-side abort takes down this process, not the
// app. The app supervises it (SteamHost.kt) and reads its events over the agent channel.
//
// Contract (all via environment — never argv, so `ps` never shows a secret):
//   BL_STEAM_TOKEN / BL_STEAM_ACCOUNT / BL_STEAM_STEAMID64  credentials (scrubbed from the
//                                       process environment right after they are read)
//   BL_STEAM_HOST_LIB                    absolute path of libsteamclient.so (siblings beside it)
//   BL_STEAM_HOST_HOME                   HOME for the client (<HOME>/Steam/config/… is its state)
//   BL_STEAM_HOST_CACERT                 PEM bundle → STEAM_SSL_CERT_FILE
//   BL_STEAM_HOST_APPID                  the game (SteamAppId/SteamGameId + prepare-app), 0 = none
//   BL_STEAM_HOST_LIB_VERSION            Valve manifest version of the downloaded lib (slot pin)
//   BL_STEAM_HOST_ALLOW_UNVERIFIED=1     drive an unpinned build with the newest slot table
//   BL_STEAM_HOST_PERSONA=1              SetPersonaState(Online) after logon (opt-in social)
//   BL_STEAM_HOST_PREPARE=0              skip RequestAppInfoUpdate / ownership ticket warm-up
//   BL_STEAM_HOST_LOGON_SID=0            do not call IClientUser::LogOn(steamid) after SetLoginToken
//   BL_STEAM_HOST_LOGIN_TIMEOUT_MS       logon wait bound (default 45000)
//   BL_AGENT_PORT                        app-side loopback listener for the status channel
//   Steam3Master / SteamClientService    loopback endpoints (defaults 127.0.0.1:57343 / :57344)
//
// Status channel: newline-delimited JSON to 127.0.0.1:BL_AGENT_PORT, the same shapes the
// SteamLite agent emits (SteamAgentChannel): started, logged_in, login_failed{eresult,reason},
// appinfo{state}, ownership{ok}, host_ready, session_lost, status{...}, shutdown{reason,code}.
// Commands from the app: {"cmd":"status"}, {"cmd":"logoff"}.
//
// Exit codes: 0 clean, 2 login failed, 3 loopback ports busy, 4 lib missing/unloadable,
// 5 entry points missing, 6 pipe/user setup failed, 7 unverified build refused.

#include <android/log.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "client_iface.h"

extern char** environ;

namespace {

constexpr const char* kLogTag = "BlSteamHost";

// ── logging ─────────────────────────────────────────────────────────────────────────────────
// stdout (the app redirects it to a per-launch host log) + logcat. Never a token, never the
// account name (only its length) — the log is collected into the shareable SteamLite bundle.
std::mutex g_log_mu;

void logv(int prio, const char* fmt, va_list ap) {
    char buf[1024];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    struct timeval tv{};
    gettimeofday(&tv, nullptr);
    struct tm tm{};
    localtime_r(&tv.tv_sec, &tm);
    std::lock_guard<std::mutex> lk(g_log_mu);
    fprintf(stdout, "[%02d:%02d:%02d.%03ld] %s%s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
            tv.tv_usec / 1000, prio >= ANDROID_LOG_WARN ? (prio >= ANDROID_LOG_ERROR ? "ERR " : "WARN ") : "",
            buf);
    fflush(stdout);
    __android_log_write(prio, kLogTag, buf);
}
void LOGI(const char* fmt, ...) { va_list ap; va_start(ap, fmt); logv(ANDROID_LOG_INFO, fmt, ap); va_end(ap); }
void LOGW(const char* fmt, ...) { va_list ap; va_start(ap, fmt); logv(ANDROID_LOG_WARN, fmt, ap); va_end(ap); }
void LOGE(const char* fmt, ...) { va_list ap; va_start(ap, fmt); logv(ANDROID_LOG_ERROR, fmt, ap); va_end(ap); }

int64_t now_ms() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// ── env helpers ─────────────────────────────────────────────────────────────────────────────
std::string env_str(const char* key, const char* dflt = "") {
    const char* v = getenv(key);
    return v ? std::string(v) : std::string(dflt);
}
long env_long(const char* key, long dflt) {
    const char* v = getenv(key);
    if (!v || !*v) return dflt;
    char* end = nullptr;
    long r = strtol(v, &end, 10);
    return (end && *end == 0) ? r : dflt;
}
bool env_flag(const char* key, bool dflt) {
    const char* v = getenv(key);
    if (!v || !*v) return dflt;
    return !(v[0] == '0' || v[0] == 'n' || v[0] == 'N' || v[0] == 'f' || v[0] == 'F');
}

// Read a secret from the environment and scrub it: zero the bytes of the original
// "KEY=value" string in the process's initial environment block (what /proc/<pid>/environ
// exposes) and unsetenv it. Combined with PR_SET_DUMPABLE=0 below, no same-uid process — the
// Wine game included — can read the token back.
std::string take_secret_env(const char* key) {
    std::string out = env_str(key);
    size_t kl = strlen(key);
    for (char** e = environ; e && *e; ++e) {
        if (strncmp(*e, key, kl) == 0 && (*e)[kl] == '=') {
            volatile char* p = *e + kl + 1;
            while (*p) *p++ = 0;
        }
    }
    unsetenv(key);
    return out;
}

// ── filesystem helpers ──────────────────────────────────────────────────────────────────────
void mkdir_p(const std::string& path, mode_t mode) {
    std::string acc;
    acc.reserve(path.size());
    for (size_t i = 0; i <= path.size(); ++i) {
        if (i == path.size() || path[i] == '/') {
            if (!acc.empty() && mkdir(acc.c_str(), mode) != 0 && errno != EEXIST)
                LOGW("mkdir(%s) failed: %s", acc.c_str(), strerror(errno));
        }
        if (i < path.size()) acc.push_back(path[i]);
    }
}
bool file_exists(const std::string& p) { struct stat st{}; return stat(p.c_str(), &st) == 0; }
std::string dirname_of(const std::string& path) {
    auto slash = path.rfind('/');
    return slash == std::string::npos ? std::string(".") : path.substr(0, slash);
}

// libsteamclient.so stats <HOME>/Steam/config/{config,local}.vdf at pipe creation and bails
// silently when they are missing (WinNative finding). Our HOME is a per-SteamID directory
// the app owns, so whatever the client writes there — its cached credentials included — is
// this host's persistent state and later boots take the "cached creds" path on their own.
void stage_config_dir(const std::string& home) {
    const std::string cfg = home + "/Steam/config";
    mkdir_p(cfg, 0700);
    mkdir_p(home + "/Steam/logs", 0700);
    for (const char* name : {"config.vdf", "local.vdf"}) {
        std::string p = cfg + "/" + name;
        if (file_exists(p)) continue;
        int fd = open(p.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (fd < 0) LOGW("create %s failed: %s", p.c_str(), strerror(errno));
        else { close(fd); LOGI("staged empty %s", p.c_str()); }
    }
}

// ── loopback port probe ─────────────────────────────────────────────────────────────────────
// The genuine library binds Steam3Master / SteamClientService itself at module init; a stale
// host (or the engine's wine_bridge snoop listener, which must stay OFF in AppSteam mode) on
// those ports makes it fail late and confusingly. Probe first so the app gets a clear event.
int port_of(const std::string& hostport, int dflt) {
    auto c = hostport.rfind(':');
    if (c == std::string::npos) return dflt;
    long p = strtol(hostport.c_str() + c + 1, nullptr, 10);
    return (p > 0 && p < 65536) ? (int)p : dflt;
}
bool port_free(int port) {
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return true;
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bool ok = bind(s, (sockaddr*)&a, sizeof(a)) == 0;
    close(s);
    return ok;
}

// ── status channel (host → app, NDJSON over loopback) ───────────────────────────────────────
class StatusChannel {
public:
    bool connect_to(int port) {
        if (port <= 0) return false;
        int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (s < 0) return false;
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons((uint16_t)port);
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (connect(s, (sockaddr*)&a, sizeof(a)) != 0) {
            LOGW("status channel: connect(127.0.0.1:%d) failed: %s", port, strerror(errno));
            close(s);
            return false;
        }
        int one = 1;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        fd_ = s;
        LOGI("status channel: connected to 127.0.0.1:%d", port);
        return true;
    }
    bool connected() const { return fd_ >= 0; }
    void send_line(const std::string& json) {
        LOGI("ev %s", json.c_str());
        std::lock_guard<std::mutex> lk(mu_);
        if (fd_ < 0) return;
        std::string line = json + "\n";
        const char* p = line.data();
        size_t left = line.size();
        while (left > 0) {
            ssize_t n = send(fd_, p, left, MSG_NOSIGNAL);
            if (n <= 0) { close(fd_); fd_ = -1; return; }
            p += n; left -= (size_t)n;
        }
    }
    // Blocking read of one line (command from the app); empty on EOF/error.
    std::string read_line() {
        std::string line;
        char c;
        while (true) {
            int fd = fd_;
            if (fd < 0) return {};
            ssize_t n = recv(fd, &c, 1, 0);
            if (n <= 0) return {};
            if (c == '\n') return line;
            if (line.size() < 4096) line.push_back(c);
        }
    }
    void close_now() {
        std::lock_guard<std::mutex> lk(mu_);
        if (fd_ >= 0) { close(fd_); fd_ = -1; }
    }
private:
    std::mutex mu_;
    std::atomic<int> fd_{-1};
};

std::string json_esc(const std::string& s) {
    std::string o;
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default: if ((unsigned char)c < 0x20) o += '?'; else o += c;
        }
    }
    return o;
}

// ── the client session ──────────────────────────────────────────────────────────────────────
struct Client {
    void* lib = nullptr;
    void* (*CreateInterface)(const char*, int*) = nullptr;
    int   (*Steam_CreateGlobalUser)(int*) = nullptr;
    bool  (*Steam_BLoggedOn)(int, int) = nullptr;
    bool  (*Steam_BConnected)(int, int) = nullptr;
    void  (*Steam_LogOff)(int, int) = nullptr;
    void  (*Steam_ReleaseUser)(int, int) = nullptr;
    bool  (*Steam_BReleaseSteamPipe)(int) = nullptr;
    bool  (*Steam_BGetCallback)(int, void*) = nullptr;
    void  (*Steam_FreeLastCallback)(int) = nullptr;
    void  (*Breakpad_SteamSetAppID)(unsigned) = nullptr;

    int pipe = 0;
    int user = 0;
    void* engine = nullptr;
    void* iuser = nullptr;
    const blhost::SlotTable* slots = nullptr;

    template <typename Fn> Fn vslot(void* obj, int slot) const {
        void** vt = *reinterpret_cast<void***>(obj);
        return reinterpret_cast<Fn>(vt[slot]);
    }
    bool logged_on() const { return Steam_BLoggedOn && pipe && user && Steam_BLoggedOn(pipe, user); }
};

std::atomic<bool> g_stop{false};
std::atomic<int>  g_stop_signal{0};
std::atomic<bool> g_logoff_requested{false};
StatusChannel g_status;

void on_signal(int sig) {
    g_stop_signal.store(sig);
    g_stop.store(true);
}

// Drain every pending callback; returns the number drained. The interesting ids are logged
// (bounded) and the logon-relevant ones decoded for the caller.
struct CallbackSummary {
    bool connected = false;
    bool connect_failure = false;
    int  connect_failure_eresult = 0;
    bool connect_failure_retrying = false;
    bool disconnected = false;
    int  disconnected_eresult = 0;
    bool appinfo_complete = false;
    int  appinfo_eresult = 0;
};
int drain_callbacks(Client& c, CallbackSummary* out, int* log_budget) {
    if (!c.Steam_BGetCallback || !c.Steam_FreeLastCallback) return 0;
    int n = 0;
    blhost::CallbackMsg msg{};
    while (c.Steam_BGetCallback(c.pipe, &msg)) {
        ++n;
        if (log_budget && *log_budget > 0) {
            --*log_budget;
            LOGI("callback id=%d size=%d", msg.iCallback, msg.cubParam);
        }
        if (out) {
            switch (msg.iCallback) {
                case blhost::kCbSteamServersConnected:
                    out->connected = true;
                    break;
                case blhost::kCbSteamServerConnectFailure:
                    out->connect_failure = true;
                    if (msg.pubParam && msg.cubParam >= 5) {
                        out->connect_failure_eresult = *reinterpret_cast<int*>(msg.pubParam);
                        out->connect_failure_retrying = msg.pubParam[4] != 0;
                    }
                    break;
                case blhost::kCbSteamServersDisconnected:
                    out->disconnected = true;
                    if (msg.pubParam && msg.cubParam >= 4)
                        out->disconnected_eresult = *reinterpret_cast<int*>(msg.pubParam);
                    break;
                case blhost::kCbAppInfoUpdateComplete:
                    out->appinfo_complete = true;
                    if (msg.pubParam && msg.cubParam >= 4)
                        out->appinfo_eresult = *reinterpret_cast<int*>(msg.pubParam);
                    break;
                default:
                    break;
            }
        }
        c.Steam_FreeLastCallback(c.pipe);
    }
    return n;
}

const char* eresult_name(int e) {
    switch (e) {
        case 1: return "OK";
        case 2: return "Fail";
        case 3: return "NoConnection";
        case 5: return "InvalidPassword";
        case 6: return "LoggedInElsewhere";
        case 7: return "InvalidProtocolVer";
        case 15: return "AccessDenied";
        case 16: return "Timeout";
        case 17: return "Banned";
        case 18: return "AccountNotFound";
        case 20: return "ServiceUnavailable";
        case 21: return "NotLoggedOn";
        case 25: return "LimitExceeded";
        case 37: return "AccountLogonDenied";
        case 50: return "AccountLoginDeniedNeedTwoFactor";
        case 55: return "NoConnection(55)";
        case 63: return "AccountLoginDeniedThrottle";
        case 65: return "Expired";
        case 84: return "RateLimitExceeded";
        case 85: return "AccountLoginDeniedNeedTwoFactor";
        default: return "?";
    }
}

// Verify every vtable slot we will call resolves inside libsteamclient.so's mapping.
bool vtable_sane(void* obj, int slots_to_check, const char* what, const std::string& libPath) {
    if (!obj) return false;
    void** vt = *reinterpret_cast<void***>(obj);
    if (!vt) return false;
    std::string libBase = libPath.substr(libPath.rfind('/') + 1);
    for (int i = 0; i < slots_to_check; ++i) {
        void* fn = vt[i];
        if (!fn) { LOGE("%s: vtable slot %d is null", what, i); return false; }
        Dl_info di{};
        if (dladdr(fn, &di) == 0 || !di.dli_fname) {
            LOGE("%s: vtable slot %d (%p) is not inside any loaded object", what, i, fn);
            return false;
        }
        std::string fname = di.dli_fname;
        if (fname.size() < libBase.size() ||
            fname.compare(fname.size() - libBase.size(), libBase.size(), libBase) != 0) {
            LOGE("%s: vtable slot %d (%p) resolves into %s, not %s", what, i, fn, di.dli_fname, libBase.c_str());
            return false;
        }
    }
    LOGI("%s: vtable slots 0..%d all resolve into %s", what, slots_to_check - 1, libBase.c_str());
    return true;
}

void preload_siblings(const std::string& libDir) {
    // Order matters: tier0 (base) → vstdlib (needs tier0) → networking sockets → steamservice.
    for (const char* name : {"libtier0_s.so", "libvstdlib_s.so", "libsteamnetworkingsockets.so", "steamservice.so"}) {
        std::string p = libDir + "/" + name;
        if (access(p.c_str(), R_OK) != 0) { LOGW("preload skip: %s not present", p.c_str()); continue; }
        void* h = dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (h) LOGI("preload OK: %s", name);
        else LOGW("preload FAIL: %s — %s", name, dlerror());
    }
}

std::string mask_steamid(uint64_t sid) {
    char b[32];
    snprintf(b, sizeof(b), "%llu", (unsigned long long)sid);
    std::string s(b);
    if (s.size() > 6) s = s.substr(0, 3) + std::string(s.size() - 6, '*') + s.substr(s.size() - 3);
    return s;
}

}  // namespace

int main(int argc, char** argv) {
    (void)argc; (void)argv;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    // Not dumpable: /proc/<pid>/{environ,maps,mem} become root-only, so the game (same uid) can
    // never read the token out of this process.
    prctl(PR_SET_DUMPABLE, 0);

    struct sigaction sa{};
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGHUP, &sa, nullptr);
    signal(SIGPIPE, SIG_IGN);

    const int64_t t0 = now_ms();
    LOGI("bl-steam-host starting (pid %d)", (int)getpid());

    // ── 0. inputs ───────────────────────────────────────────────────────────────────────────
    const std::string token   = take_secret_env("BL_STEAM_TOKEN");
    const std::string account = take_secret_env("BL_STEAM_ACCOUNT");
    const std::string sidStr  = take_secret_env("BL_STEAM_STEAMID64");
    const uint64_t steamId64  = strtoull(sidStr.c_str(), nullptr, 10);

    const std::string libPath = env_str("BL_STEAM_HOST_LIB");
    const std::string home    = env_str("BL_STEAM_HOST_HOME");
    const std::string cacert  = env_str("BL_STEAM_HOST_CACERT");
    const std::string libVer  = env_str("BL_STEAM_HOST_LIB_VERSION");
    const unsigned appId      = (unsigned)env_long("BL_STEAM_HOST_APPID", 0);
    const int statusPort      = (int)env_long("BL_AGENT_PORT", 0);
    const bool persona        = env_flag("BL_STEAM_HOST_PERSONA", false);
    const bool prepare        = env_flag("BL_STEAM_HOST_PREPARE", true);
    const bool logonSid       = env_flag("BL_STEAM_HOST_LOGON_SID", true);
    const long loginTimeoutMs = env_long("BL_STEAM_HOST_LOGIN_TIMEOUT_MS", 45000);
    const std::string s3m     = env_str("Steam3Master", "127.0.0.1:57343");
    const std::string scs     = env_str("SteamClientService", "127.0.0.1:57344");

    g_status.connect_to(statusPort);
    {
        char b[256];
        snprintf(b, sizeof(b), "{\"ev\":\"started\",\"pid\":%d,\"appid\":%u,\"host\":\"bl-steam-host/1\",\"libver\":\"%s\"}",
                 (int)getpid(), appId, json_esc(libVer).c_str());
        g_status.send_line(b);
    }
    LOGI("inputs: lib=%s home=%s appId=%u libver=%s tokenLen=%zu accountLen=%zu steamId=%s status=%d",
         libPath.c_str(), home.c_str(), appId, libVer.c_str(), token.size(), account.size(),
         mask_steamid(steamId64).c_str(), statusPort);

    auto fail = [&](int code, const char* ev, const std::string& reason) {
        char b[512];
        snprintf(b, sizeof(b), "{\"ev\":\"%s\",\"reason\":\"%s\"}", ev, json_esc(reason).c_str());
        g_status.send_line(b);
        snprintf(b, sizeof(b), "{\"ev\":\"shutdown\",\"reason\":\"%s\",\"code\":%d}", json_esc(reason).c_str(), code);
        g_status.send_line(b);
        g_status.close_now();
        return code;
    };

    if (libPath.empty() || access(libPath.c_str(), R_OK) != 0)
        return fail(4, "host_failed", "libsteamclient.so missing at " + libPath);
    if (home.empty()) return fail(4, "host_failed", "BL_STEAM_HOST_HOME unset");
    if (token.empty() || account.empty() || steamId64 == 0)
        return fail(2, "login_failed", "no credentials (token/account/steamid) in the host environment");

    // ── 1. slot table for this Valve build ─────────────────────────────────────────────────
    const blhost::SlotTable* slots = nullptr;
    for (const auto& t : blhost::kSlotTables) if (libVer == t.valveVersion) slots = &t;
    if (!slots) {
        if (env_flag("BL_STEAM_HOST_ALLOW_UNVERIFIED", false)) {
            slots = &blhost::kSlotTables[sizeof(blhost::kSlotTables) / sizeof(blhost::kSlotTables[0]) - 1];
            LOGW("Valve build %s is NOT pinned — driving it with the %s slot table (BL_STEAM_HOST_ALLOW_UNVERIFIED=1)",
                 libVer.c_str(), slots->valveVersion);
        } else {
            return fail(7, "host_failed", "Valve client build " + libVer + " is not verified for this host (slot table pinned to " +
                                          std::string(blhost::kSlotTables[0].valveVersion) + ")");
        }
    } else {
        LOGI("Valve build %s: slot table verified", libVer.c_str());
    }

    // ── 2. ports must be free ──────────────────────────────────────────────────────────────
    {
        int p1 = port_of(s3m, 57343), p2 = port_of(scs, 57344);
        bool f1 = port_free(p1), f2 = port_free(p2);
        if (!f1 || !f2) {
            char b[160];
            snprintf(b, sizeof(b), "loopback port busy (Steam3Master %d free=%d, SteamClientService %d free=%d) — stale host or wine_bridge listener?",
                     p1, f1, p2, f2);
            return fail(3, "port_busy", b);
        }
        LOGI("loopback ports free: %d %d", p1, p2);
    }

    // ── 3. environment BEFORE dlopen (the lib reads these at module init) ──────────────────
    const std::string libDir = dirname_of(libPath);
    setenv("HOME", home.c_str(), 1);
    setenv("Steam3Master", s3m.c_str(), 1);
    setenv("SteamClientService", scs.c_str(), 1);
    setenv("LD_LIBRARY_PATH", libDir.c_str(), 1);
    if (!cacert.empty() && file_exists(cacert)) setenv("STEAM_SSL_CERT_FILE", cacert.c_str(), 1);
    else LOGW("STEAM_SSL_CERT_FILE not set (%s missing) — TLS logon may fail", cacert.c_str());
    if (appId > 0) {
        char b[16];
        snprintf(b, sizeof(b), "%u", appId);
        setenv("SteamAppId", b, 1);
        setenv("SteamGameId", b, 1);
    }
    // Bootstrap-gate handshake the client checks at init (GameNative/WinNative contract). The
    // app passes them through; make sure the defaults are there.
    setenv("_STEAM_SETENV_MANAGER", "1", 0);
    setenv("STEAMVIDEOTOKEN", "1", 0);
    setenv("SteamOS", "1", 0);
    setenv("ENABLE_VK_LAYER_VALVE_steam_overlay_1", "0", 0);
    if (!getenv("BREAKPAD_DUMP_LOCATION")) {
        std::string bp = home + "/breakpad";
        mkdir_p(bp, 0700);
        setenv("BREAKPAD_DUMP_LOCATION", bp.c_str(), 1);
    }
    if (!getenv("STEAM_BASE_FOLDER")) {
        std::string base = home + "/Steam";
        setenv("STEAM_BASE_FOLDER", base.c_str(), 1);
    }
    stage_config_dir(home);
    preload_siblings(libDir);

    // steamservice: WinNative starts its thread before the client loads; GameNative falls back
    // to the same call when InitIPC is absent ("in-process only"). Non-fatal either way.
    {
        typedef void* (*StartThreadFn)(const char*);
        auto start = reinterpret_cast<StartThreadFn>(dlsym(RTLD_DEFAULT, "SteamService_StartThread"));
        if (start) LOGI("SteamService_StartThread(\"SteamClientService\") -> %p", start("SteamClientService"));
        else LOGW("SteamService_StartThread not resolvable (steamservice.so preload failed?)");
    }

    // ── 4. load the client ─────────────────────────────────────────────────────────────────
    Client c;
    c.slots = slots;
    c.lib = dlopen(libPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!c.lib) return fail(4, "host_failed", std::string("dlopen failed: ") + (dlerror() ? dlerror() : "?"));
    LOGI("dlopen(libsteamclient.so) OK");

#define RESOLVE(name) c.name = reinterpret_cast<decltype(c.name)>(dlsym(c.lib, #name))
    RESOLVE(CreateInterface); RESOLVE(Steam_CreateGlobalUser); RESOLVE(Steam_BLoggedOn);
    RESOLVE(Steam_BConnected); RESOLVE(Steam_LogOff); RESOLVE(Steam_ReleaseUser);
    RESOLVE(Steam_BReleaseSteamPipe); RESOLVE(Steam_BGetCallback); RESOLVE(Steam_FreeLastCallback);
    RESOLVE(Breakpad_SteamSetAppID);
#undef RESOLVE
    if (!c.CreateInterface || !c.Steam_CreateGlobalUser || !c.Steam_BLoggedOn || !c.Steam_BGetCallback ||
        !c.Steam_FreeLastCallback)
        return fail(5, "host_failed", "required entry points missing from libsteamclient.so");
    LOGI("entry points resolved (CreateInterface, Steam_CreateGlobalUser, Steam_BLoggedOn, callbacks%s%s)",
         c.Steam_LogOff ? ", Steam_LogOff" : "", c.Steam_ReleaseUser ? ", Steam_ReleaseUser" : "");
    if (c.Breakpad_SteamSetAppID) c.Breakpad_SteamSetAppID(0);

    // ── 5. pipe + global user (Steam_CreateSteamPipe returns 0 on Android — helper-child model)
    {
        int pipe = 0;
        int user = c.Steam_CreateGlobalUser(&pipe);
        if (user == 0 || pipe == 0) {
            char b[96];
            snprintf(b, sizeof(b), "Steam_CreateGlobalUser failed (user=%d pipe=%d)", user, pipe);
            return fail(6, "host_failed", b);
        }
        c.pipe = pipe; c.user = user;
        LOGI("Steam_CreateGlobalUser OK pipe=%d user=%d", pipe, user);
    }

    // ── 6. private engine + user interface ─────────────────────────────────────────────────
    {
        int err = 0;
        c.engine = c.CreateInterface(blhost::kClientEngineVersion, &err);
        if (!c.engine || err != 0) {
            char b[96];
            snprintf(b, sizeof(b), "CreateInterface(%s) -> %p err=%d", blhost::kClientEngineVersion, c.engine, err);
            return fail(5, "host_failed", b);
        }
        if (!vtable_sane(c.engine, slots->engineGetIClientApps + 1, "IClientEngine", libPath))
            return fail(5, "host_failed", "IClientEngine vtable failed the sanity check");
        using GetIfaceFn = void* (*)(void*, int, int, const char*);
        c.iuser = c.vslot<GetIfaceFn>(c.engine, slots->engineGetIClientUser)(c.engine, c.user, c.pipe, blhost::kClientUserVersion);
        LOGI("IClientEngine.GetIClientUser(user=%d, pipe=%d) -> %p", c.user, c.pipe, c.iuser);
        if (!c.iuser || !vtable_sane(c.iuser, slots->userSlotsToCheck, "IClientUser", libPath))
            return fail(5, "host_failed", "IClientUser unavailable or failed the vtable sanity check");
    }

    // ── 7. logon ───────────────────────────────────────────────────────────────────────────
    int log_budget = 200;
    {
        using BoolStrFn   = bool (*)(void*, const char*);
        using StrBoolFn   = void (*)(void*, const char*, bool);
        using LoginInfoFn = void (*)(void*, const char*, const char*, bool);
        using TokenFn     = void (*)(void*, const char*, const char*);
        using LogOnFn     = int  (*)(void*, uint64_t);

        bool cached = c.vslot<BoolStrFn>(c.iuser, slots->userBHasCachedCredentials)(c.iuser, account.c_str());
        LOGI("IClientUser.BHasCachedCredentials(<account>) = %d", cached ? 1 : 0);

        bool auto_logged = false;
        if (cached) {
            // A previous boot of this host left cached credentials in our HOME: give the client a
            // moment to log on by itself (avoids re-presenting a token it already rotated).
            c.vslot<StrBoolFn>(c.iuser, slots->userSetAccountNameForCachedCredentialLogin)(c.iuser, account.c_str(), true);
            if (logonSid) {
                int r = c.vslot<LogOnFn>(c.iuser, slots->userLogOn)(c.iuser, steamId64);
                LOGI("IClientUser.LogOn(steamid) [cached-creds path] -> %d (%s)", r, eresult_name(r));
            }
            int64_t until = now_ms() + 4000;
            while (now_ms() < until && !g_stop.load()) {
                drain_callbacks(c, nullptr, &log_budget);
                if (c.logged_on()) { auto_logged = true; break; }
                usleep(100 * 1000);
            }
            LOGI("cached-credentials logon: %s", auto_logged ? "LOGGED ON" : "did not complete — using the refresh token");
        }
        if (!auto_logged) {
            c.vslot<LoginInfoFn>(c.iuser, slots->userSetLoginInformation)(c.iuser, account.c_str(), "", true);
            LOGI("IClientUser.SetLoginInformation(<account>, \"\", remember=1)");
            c.vslot<TokenFn>(c.iuser, slots->userSetLoginToken)(c.iuser, token.c_str(), account.c_str());
            LOGI("IClientUser.SetLoginToken(<%zu-byte token>, <account>)", token.size());
            if (logonSid) {
                int r = c.vslot<LogOnFn>(c.iuser, slots->userLogOn)(c.iuser, steamId64);
                LOGI("IClientUser.LogOn(steamid) -> %d (%s)", r, eresult_name(r));
            }
        }
    }

    // Poll until logged on, decoding the connection callbacks so a failure names its EResult.
    {
        const int64_t deadline = now_ms() + loginTimeoutMs;
        int last_eresult = 0;
        bool logged = false;
        bool announced_connected = false;
        while (now_ms() < deadline && !g_stop.load()) {
            CallbackSummary s;
            drain_callbacks(c, &s, &log_budget);
            if (s.connected && !announced_connected) { announced_connected = true; LOGI("SteamServersConnected"); }
            if (s.connect_failure) {
                last_eresult = s.connect_failure_eresult;
                LOGW("SteamServerConnectFailure EResult=%d (%s) retrying=%d", last_eresult,
                     eresult_name(last_eresult), s.connect_failure_retrying ? 1 : 0);
                // Auth-class failures never heal by waiting; network ones might.
                if (!s.connect_failure_retrying &&
                    (last_eresult == 5 || last_eresult == 15 || last_eresult == 17 || last_eresult == 18 ||
                     last_eresult == 37 || last_eresult == 50 || last_eresult == 63 || last_eresult == 65 ||
                     last_eresult == 85))
                    break;
            }
            if (s.disconnected) {
                last_eresult = s.disconnected_eresult;
                LOGW("SteamServersDisconnected EResult=%d (%s)", last_eresult, eresult_name(last_eresult));
            }
            if (c.logged_on()) { logged = true; break; }
            usleep(50 * 1000);
        }
        if (!logged) {
            char b[200];
            snprintf(b, sizeof(b), "{\"ev\":\"login_failed\",\"eresult\":%d,\"reason\":\"%s\"}", last_eresult,
                     g_stop.load() ? "stopped" : (last_eresult ? eresult_name(last_eresult) : "timeout"));
            g_status.send_line(b);
            LOGE("logon did not complete (eresult=%d, %lld ms)", last_eresult, (long long)(now_ms() - t0));
            if (c.Steam_LogOff) c.Steam_LogOff(c.pipe, c.user);
            if (c.Steam_ReleaseUser) c.Steam_ReleaseUser(c.pipe, c.user);
            if (c.Steam_BReleaseSteamPipe) c.Steam_BReleaseSteamPipe(c.pipe);
            snprintf(b, sizeof(b), "{\"ev\":\"shutdown\",\"reason\":\"login_failed\",\"code\":2}");
            g_status.send_line(b);
            return 2;
        }
        char b[160];
        snprintf(b, sizeof(b), "{\"ev\":\"logged_in\",\"steamid\":\"%s\",\"ms\":%lld}", mask_steamid(steamId64).c_str(),
                 (long long)(now_ms() - t0));
        g_status.send_line(b);
        LOGI("LOGGED ON after %lld ms", (long long)(now_ms() - t0));
    }

    // ── 8. prepare the app: app-info + ownership ticket warm-up (GameNative's steam_prepare_app)
    // The Wine-side bridge cannot drive these before the game asks for its ownership ticket, and
    // without them a launch can stall at "Validating Subscriptions". Every step is best-effort.
    if (appId > 0 && prepare && !g_stop.load()) {
        using GetIfaceFn = void* (*)(void*, int, int, const char*);
        using ReqAppInfoFn = bool (*)(void*, const unsigned*, int);
        using OwnershipFn = bool (*)(void*, unsigned, bool);
        void* apps = c.vslot<GetIfaceFn>(c.engine, slots->engineGetIClientApps)(c.engine, c.user, c.pipe, blhost::kClientAppsVersion);
        LOGI("IClientEngine.GetIClientApps -> %p", apps);
        std::string appinfo_state = "skipped";
        if (apps && vtable_sane(apps, slots->appsRequestAppInfoUpdate + 1, "IClientApps", libPath)) {
            unsigned ids[1] = {appId};
            bool req = c.vslot<ReqAppInfoFn>(apps, slots->appsRequestAppInfoUpdate)(apps, ids, 1);
            LOGI("IClientApps.RequestAppInfoUpdate(%u) -> %d", appId, req ? 1 : 0);
            int64_t until = now_ms() + 4000;
            bool complete = false;
            int eresult = 0;
            while (now_ms() < until && !g_stop.load()) {
                CallbackSummary s;
                drain_callbacks(c, &s, &log_budget);
                if (s.appinfo_complete) { complete = true; eresult = s.appinfo_eresult; break; }
                usleep(50 * 1000);
            }
            appinfo_state = complete ? "complete" : "timeout";
            LOGI("AppInfoUpdateComplete: %s (eresult=%d)", appinfo_state.c_str(), eresult);
        }
        {
            char b[128];
            snprintf(b, sizeof(b), "{\"ev\":\"appinfo\",\"state\":\"%s\"}", appinfo_state.c_str());
            g_status.send_line(b);
        }
        bool ticket = false;
        for (int attempt = 1; attempt <= 10 && !g_stop.load(); ++attempt) {
            ticket = c.vslot<OwnershipFn>(c.iuser, slots->userBUpdateAppOwnershipTicket)(c.iuser, appId, false);
            if (ticket) break;
            int64_t until = now_ms() + 300;
            while (now_ms() < until) { drain_callbacks(c, nullptr, &log_budget); usleep(50 * 1000); }
        }
        LOGI("IClientUser.BUpdateAppOwnershipTicket(%u) -> %d", appId, ticket ? 1 : 0);
        {
            char b[96];
            snprintf(b, sizeof(b), "{\"ev\":\"ownership\",\"appid\":%u,\"ok\":%s}", appId, ticket ? "true" : "false");
            g_status.send_line(b);
        }
    }
    if (persona && !g_stop.load()) {
        using GetIfaceFn = void* (*)(void*, int, int, const char*);
        using SetPersonaFn = void (*)(void*, int);
        void* friends = c.vslot<GetIfaceFn>(c.engine, slots->engineGetIClientFriends)(c.engine, c.user, c.pipe, blhost::kClientFriendsVersion);
        if (friends && vtable_sane(friends, slots->friendsSetPersonaState + 1, "IClientFriends", libPath)) {
            c.vslot<SetPersonaFn>(friends, slots->friendsSetPersonaState)(friends, 1);
            LOGI("IClientFriends.SetPersonaState(Online)");
        }
    }

    g_status.send_line("{\"ev\":\"host_ready\"}");
    LOGI("serving Steam3Master=%s SteamClientService=%s (ready after %lld ms)", s3m.c_str(), scs.c_str(),
         (long long)(now_ms() - t0));

    // ── 9. command reader (app → host) ─────────────────────────────────────────────────────
    std::thread reader([&]() {
        while (!g_stop.load()) {
            std::string line = g_status.read_line();
            if (line.empty()) {
                if (!g_status.connected()) { LOGI("status channel closed by the app"); return; }
                continue;
            }
            if (line.find("\"logoff\"") != std::string::npos) { LOGI("cmd: logoff"); g_logoff_requested.store(true); }
            else if (line.find("\"status\"") != std::string::npos) {
                char b[200];
                snprintf(b, sizeof(b), "{\"ev\":\"status\",\"logged_in\":%s,\"uptime_ms\":%lld,\"appid\":%u}",
                         c.logged_on() ? "true" : "false", (long long)(now_ms() - t0), appId);
                g_status.send_line(b);
            }
        }
    });

    // ── 10. session pump ───────────────────────────────────────────────────────────────────
    // The logon and every later Steam API round-trip the game makes are message-driven: the
    // library only advances while Steam_BGetCallback is drained. ~50 Hz like the real client.
    bool was_logged = true;
    int64_t last_state_log = now_ms();
    while (!g_stop.load() && !g_logoff_requested.load()) {
        CallbackSummary s;
        drain_callbacks(c, &s, &log_budget);
        bool now_logged = c.logged_on();
        if (was_logged && !now_logged) {
            char b[120];
            snprintf(b, sizeof(b), "{\"ev\":\"session_lost\",\"eresult\":%d}", s.disconnected_eresult);
            g_status.send_line(b);
        } else if (!was_logged && now_logged) {
            char b[120];
            snprintf(b, sizeof(b), "{\"ev\":\"logged_in\",\"steamid\":\"%s\",\"reconnect\":true}", mask_steamid(steamId64).c_str());
            g_status.send_line(b);
        }
        was_logged = now_logged;
        if (now_ms() - last_state_log > 60000) {
            last_state_log = now_ms();
            LOGI("alive: logged_on=%d uptime=%llds", now_logged ? 1 : 0, (long long)((now_ms() - t0) / 1000));
        }
        usleep(20 * 1000);
    }

    // ── 11. teardown: LogOff → ReleaseUser → ReleasePipe. Never dlclose (background threads).
    const char* why = g_logoff_requested.load() ? "logoff_requested"
                    : g_stop_signal.load() == SIGTERM ? "sigterm"
                    : g_stop_signal.load() ? "signal" : "stopped";
    LOGI("shutting down (%s)", why);
    if (c.Steam_LogOff) { c.Steam_LogOff(c.pipe, c.user); LOGI("Steam_LogOff"); }
    {
        // Let the logoff message leave the socket before the user is released.
        int64_t until = now_ms() + 500;
        while (now_ms() < until) { drain_callbacks(c, nullptr, &log_budget); usleep(20 * 1000); }
    }
    if (c.Steam_ReleaseUser) { c.Steam_ReleaseUser(c.pipe, c.user); LOGI("Steam_ReleaseUser"); }
    if (c.Steam_BReleaseSteamPipe) { bool ok = c.Steam_BReleaseSteamPipe(c.pipe); LOGI("Steam_BReleaseSteamPipe -> %d", ok ? 1 : 0); }
    {
        char b[120];
        snprintf(b, sizeof(b), "{\"ev\":\"shutdown\",\"reason\":\"%s\",\"code\":0}", why);
        g_status.send_line(b);
    }
    g_status.close_now();
    g_stop.store(true);
    if (reader.joinable()) reader.join();
    LOGI("bye (uptime %llds)", (long long)((now_ms() - t0) / 1000));
    // _exit: the client's own threads are still alive; a normal exit would run their static
    // destructors under them (the crash every embedded-Steam launcher surveyed avoids this way).
    fflush(stdout);
    _exit(0);
}
