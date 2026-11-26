/*
 * libhostname_fix.so — report the device's network address as the hostname on Android.
 *
 * Android's hostname is "localhost". Programs that find their own address with
 * getaddrinfo(gethostname()) (the Epic Online Services SDK's P2P stack does) get 127.0.0.1 and
 * bind their game sockets to loopback, so online multiplayer has no address to give a peer.
 *
 * How: interpose gethostname() via LD_PRELOAD. When, and only when, the real answer is
 * "localhost" or empty, return the device's IPv4 address instead, found by connecting a UDP
 * socket (sends no packets) and reading its local address. Any failure keeps the real answer.
 *
 * wineboot is left alone: it builds the Windows computer name from gethostname() at every Wine
 * start, and with an address that name would turn into its first number ("192") instead of
 * "LOCALHOST". Winsock's gethostname() reaches the interposer directly, so games still get it.
 *
 * Env:
 *   HOSTNAME_FIX_DISABLE=1  pass everything through untouched.
 *
 * gethostname() is ported from GameNative's gamenative_dns_v4mapped.c (PR #2009 by Cole
 * McCarren, commit 219cda9b, GPL-3.0).
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define REPORT_INTERVAL_SECONDS 30

typedef int (*gethostname_fn)(char *, size_t);

static gethostname_fn next_gethostname;
static pthread_once_t next_once = PTHREAD_ONCE_INIT;

static void resolve_next(void)
{
    next_gethostname = (gethostname_fn)dlsym(RTLD_NEXT, "gethostname");
}

static int is_wineboot(void)
{
    char comm[17] = {0};
    if (prctl(PR_GET_NAME, comm) != 0) return 0;
    return strncasecmp(comm, "wineboot", 8) == 0;
}

/* Programs poll gethostname(), so the line goes to stderr (the game log) at most every 30 s. */
static void report(const char *address, const char *was)
{
    static uint64_t last;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (last != 0 && (uint64_t)now.tv_sec < last + REPORT_INTERVAL_SECONDS) return;
    last = (uint64_t)now.tv_sec;
    dprintf(STDERR_FILENO, "[hostname_fix] gethostname -> %s (hostname was %s)\n", address, was);
}

int gethostname(char *name, size_t len)
{
    pthread_once(&next_once, resolve_next);
    if (!next_gethostname) {
        errno = ENOSYS;
        return -1;
    }

    int ret = next_gethostname(name, len);
    if (ret != 0) return ret;
    if (len == 0 || (name[0] != '\0' && strcmp(name, "localhost") != 0)) return 0;

    const char *off = getenv("HOSTNAME_FIX_DISABLE");
    if (off && *off == '1') return 0;
    if (is_wineboot()) return 0;

    /* The probe's failures must not leave an errno behind a successful return. */
    int saved_errno = errno;
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        errno = saved_errno;
        return 0;
    }

    struct sockaddr_in probe;
    socklen_t probe_len = sizeof(probe);
    memset(&probe, 0, sizeof(probe));
    probe.sin_family = AF_INET;
    probe.sin_port = htons(53);
    probe.sin_addr.s_addr = htonl(0x08080808); /* any routable destination; nothing is sent */
    int ok = connect(fd, (const struct sockaddr *)&probe, sizeof(probe)) == 0 &&
             getsockname(fd, (struct sockaddr *)&probe, &probe_len) == 0;
    close(fd);
    errno = saved_errno;
    if (!ok) return 0;

    if (probe.sin_addr.s_addr == 0 || (ntohl(probe.sin_addr.s_addr) >> 24) == 127) return 0;
    char address[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &probe.sin_addr, address, sizeof(address))) return 0;
    size_t n = strlen(address) + 1;
    if (n > len) return 0;

    report(address, name[0] ? name : "(empty)");
    memcpy(name, address, n);
    return 0;
}
