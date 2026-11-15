/*
 * libnetfs.so — stream a game's files from a network drive as Wine reads them (Bannerlator network games, test).
 *
 * A network game is a stand-in folder in app storage (files/netgames/<Game>): the real folders, and every file at
 * its real size as a SPARSE file (no space used until written). Bannerlator's rclone daemon serves the real files
 * from the NAS over HTTP on 127.0.0.1 (rcd --rc-serve, ranged GETs). This library, LD_PRELOADed into the game's
 * processes, fills in the parts of a stand-in file just before they are read:
 *
 *  - read / pread / readv / preadv / mmap on a file under BLNETFS_ROOT: every 8 MB chunk of the range that still
 *    has a hole (lseek SEEK_HOLE) is fetched with one ranged GET and written into the file, then the call goes on
 *    as normal. The data stays, so a chunk is fetched once, and processes share what was fetched (wineserver
 *    reads image headers from fds it got over a socket: fds are recognised by fstat + /proc/self/fd, not by open).
 *  - write / pwrite / writev on such a file: the whole file is fetched first, so nothing written is later
 *    overwritten by a fetch. Writes stay on the device (nothing is sent back to the NAS).
 *  - A fetch that fails is retried for about 15 s (Wi-Fi blips, a NAS waking up); then the call fails with EIO.
 *
 * Env (set by GuestProgramLauncherComponent for network games only; without them everything passes through):
 *   BLNETFS_ROOT    canonical path of the game's stand-in folder
 *   BLNETFS_CONF    a file only the app can read, with the lines "port=…", "prefix=…" (URL path of the game folder
 *                   on the daemon, "/%5B<remote>%5D/", already encoded) and "auth=Basic …". The remote carries the
 *                   drive's login, so it stays out of the environment (and out of logs that print it).
 *   (BLNETFS_PORT / BLNETFS_PREFIX / BLNETFS_AUTH in the env instead: for tests.)
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))
#define CHUNK ((off_t)8 << 20)
#define MAXFD 65536
#define RETRIES 6
/* One ranged GET through rclone's server runs at ~7 MB/s; several at once reach the NAS's own speed. */
#define PARTS 8
#define AHEAD 2
#define MAX_BACKGROUND 4

enum { FD_UNKNOWN = 0, FD_PLAIN = 1, FD_NET = 2, FD_COMPLETE = 3 };

typedef ssize_t (*read_fn)(int, void *, size_t);
typedef ssize_t (*pread_fn)(int, void *, size_t, off_t);
typedef ssize_t (*readv_fn)(int, const struct iovec *, int);
typedef ssize_t (*preadv_fn)(int, const struct iovec *, int, off_t);
typedef ssize_t (*write_fn)(int, const void *, size_t);
typedef ssize_t (*pwrite_fn)(int, const void *, size_t, off_t);
typedef ssize_t (*writev_fn)(int, const struct iovec *, int);
typedef void *(*mmap_fn)(void *, size_t, int, int, int, off_t);
typedef int (*close_fn)(int);
typedef int (*dup2_fn)(int, int);
typedef int (*dup3_fn)(int, int, int);

static struct {
    read_fn read;
    pread_fn pread, pread64;
    readv_fn readv;
    preadv_fn preadv, preadv64;
    write_fn write;
    pwrite_fn pwrite, pwrite64;
    writev_fn writev;
    mmap_fn mmap, mmap64;
    close_fn close;
    dup2_fn dup2;
    dup3_fn dup3;
} real;

static pthread_once_t once = PTHREAD_ONCE_INIT;
static int enabled;
static char root[PATH_MAX];
static size_t root_len;
static dev_t root_dev;
static int port;
static char prefix[8192];
static char auth[1024];

static unsigned char state[MAXFD];
static char *rel[MAXFD];
/* A second descriptor (O_RDWR, +1 so 0 = none) on the same file: hole checks and fetched data go through it, so
 * the game's own descriptor keeps its file position. */
static int helper[MAXFD];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static void say(const char *fmt, ...)
{
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof line - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof line - 2) n = (int)sizeof line - 2;
    line[n++] = '\n';
    if (real.write) real.write(2, line, (size_t)n);
}

static void init(void)
{
    real.read = (read_fn)dlsym(RTLD_NEXT, "read");
    real.pread = (pread_fn)dlsym(RTLD_NEXT, "pread");
    real.pread64 = (pread_fn)dlsym(RTLD_NEXT, "pread64");
    real.readv = (readv_fn)dlsym(RTLD_NEXT, "readv");
    real.preadv = (preadv_fn)dlsym(RTLD_NEXT, "preadv");
    real.preadv64 = (preadv_fn)dlsym(RTLD_NEXT, "preadv64");
    real.write = (write_fn)dlsym(RTLD_NEXT, "write");
    real.pwrite = (pwrite_fn)dlsym(RTLD_NEXT, "pwrite");
    real.pwrite64 = (pwrite_fn)dlsym(RTLD_NEXT, "pwrite64");
    real.writev = (writev_fn)dlsym(RTLD_NEXT, "writev");
    real.mmap = (mmap_fn)dlsym(RTLD_NEXT, "mmap");
    real.mmap64 = (mmap_fn)dlsym(RTLD_NEXT, "mmap64");
    real.close = (close_fn)dlsym(RTLD_NEXT, "close");
    real.dup2 = (dup2_fn)dlsym(RTLD_NEXT, "dup2");
    real.dup3 = (dup3_fn)dlsym(RTLD_NEXT, "dup3");
    if (!real.pread64) real.pread64 = real.pread;
    if (!real.preadv64) real.preadv64 = real.preadv;
    if (!real.pwrite64) real.pwrite64 = real.pwrite;
    if (!real.mmap64) real.mmap64 = real.mmap;

    const char *r = getenv("BLNETFS_ROOT"), *p = getenv("BLNETFS_PORT");
    const char *pre = getenv("BLNETFS_PREFIX"), *a = getenv("BLNETFS_AUTH");
    static char cport[32], cprefix[8192], cauth[1024];
    const char *conf = getenv("BLNETFS_CONF");
    if (conf && *conf) {
        FILE *f = fopen(conf, "re");
        if (f) {
            static char line[9000];
            while (fgets(line, sizeof line, f)) {
                size_t n = strlen(line);
                while (n && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
                if (!strncmp(line, "port=", 5) && n - 5 < sizeof cport) { strcpy(cport, line + 5); p = cport; }
                else if (!strncmp(line, "prefix=", 7) && n - 7 < sizeof cprefix) { strcpy(cprefix, line + 7); pre = cprefix; }
                else if (!strncmp(line, "auth=", 5) && n - 5 < sizeof cauth) { strcpy(cauth, line + 5); a = cauth; }
            }
            fclose(f);
        }
    }
    if (!r || !*r || !p || !pre || !a) return;
    struct stat sb;
    if (stat(r, &sb) != 0 || !S_ISDIR(sb.st_mode)) return;
    if (strlen(r) >= sizeof root || strlen(pre) >= sizeof prefix || strlen(a) >= sizeof auth) return;
    strcpy(root, r);
    root_len = strlen(root);
    while (root_len > 1 && root[root_len - 1] == '/') root[--root_len] = 0;
    root_dev = sb.st_dev;
    port = atoi(p);
    strcpy(prefix, pre);
    strcpy(auth, a);
    if (port <= 0 || port > 65535) return;
    enabled = 1;
    say("netfs: streaming %s from 127.0.0.1:%d", root, port);
}

static void fork_prepare(void);
static void fork_parent(void);
static void fork_child(void);

__attribute__((constructor)) static void ctor(void)
{
    pthread_once(&once, init);
    /* A fork while another thread holds the lock (or fetches a chunk) must not leave the child waiting forever. */
    pthread_atfork(fork_prepare, fork_parent, fork_child);
}

static inline void ready(void) { pthread_once(&once, init); }

/* What [fd] is: a stand-in file under the root (FD_NET), anything else (FD_PLAIN). Worked out once per descriptor. */
static int classify(int fd)
{
    if (!enabled || fd < 0 || fd >= MAXFD) return FD_PLAIN;
    int s = __atomic_load_n(&state[fd], __ATOMIC_ACQUIRE);
    if (s) return s;
    int ns = FD_PLAIN;
    char *r = NULL;
    struct stat sb;
    if (fstat(fd, &sb) == 0 && S_ISREG(sb.st_mode) && sb.st_dev == root_dev) {
        char link[48], buf[PATH_MAX];
        snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
        ssize_t n = readlink(link, buf, sizeof buf - 1);
        if (n > 0) {
            buf[n] = 0;
            if ((size_t)n > root_len + 1 && memcmp(buf, root, root_len) == 0 && buf[root_len] == '/') {
                r = strdup(buf + root_len + 1);
                if (r) ns = FD_NET;
            }
        }
    }
    pthread_mutex_lock(&lock);
    if (state[fd] == FD_UNKNOWN) {
        free(rel[fd]);
        rel[fd] = r;
        r = NULL;
        __atomic_store_n(&state[fd], (unsigned char)ns, __ATOMIC_RELEASE);
    }
    s = state[fd];
    pthread_mutex_unlock(&lock);
    free(r);
    return s;
}

/* [fd] is being closed or replaced: forget what it was. */
static void forget(int fd)
{
    if (fd < 0 || fd >= MAXFD) return;
    char *r;
    int h;
    pthread_mutex_lock(&lock);
    __atomic_store_n(&state[fd], FD_UNKNOWN, __ATOMIC_RELEASE);
    r = rel[fd];
    rel[fd] = NULL;
    h = helper[fd] - 1;
    helper[fd] = 0;
    if (h >= 0 && h < MAXFD) __atomic_store_n(&state[h], FD_UNKNOWN, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&lock);
    free(r);
    if (h >= 0 && real.close) real.close(h);
}

/* A copy of [fd]'s path below the root (caller frees), or NULL. */
static char *rel_of(int fd)
{
    pthread_mutex_lock(&lock);
    char *r = rel[fd] ? strdup(rel[fd]) : NULL;
    pthread_mutex_unlock(&lock);
    return r;
}

static int helper_of(int fd, const char *r)
{
    pthread_mutex_lock(&lock);
    int h = helper[fd] - 1;
    pthread_mutex_unlock(&lock);
    if (h >= 0) return h;
    char path[PATH_MAX];
    if (snprintf(path, sizeof path, "%s/%s", root, r) >= (int)sizeof path) return -1;
    int nh = open(path, O_RDWR | O_CLOEXEC);
    if (nh < 0) return -1;
    pthread_mutex_lock(&lock);
    if (helper[fd]) {
        h = helper[fd] - 1;
        pthread_mutex_unlock(&lock);
        real.close(nh);
        return h;
    }
    helper[fd] = nh + 1;
    if (nh < MAXFD) __atomic_store_n(&state[nh], FD_PLAIN, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&lock);
    return nh;
}

static size_t url_encode(const char *in, char *out, size_t cap)
{
    static const char hex[] = "0123456789ABCDEF";
    size_t o = 0;
    for (const unsigned char *p = (const unsigned char *)in; *p; p++) {
        int keep = (*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') ||
                   *p == '-' || *p == '.' || *p == '_' || *p == '~' || *p == '/';
        if (o + 4 >= cap) return 0;
        if (keep) out[o++] = (char)*p;
        else { out[o++] = '%'; out[o++] = hex[*p >> 4]; out[o++] = hex[*p & 15]; }
    }
    out[o] = 0;
    return o;
}

static int send_all(int s, const char *buf, size_t len)
{
    while (len) {
        ssize_t n = send(s, buf, len, MSG_NOSIGNAL);
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

/* One ranged GET of [start, end) of [r] into [h] at the same offsets. 0 when all of it arrived. */
static int fetch_once(const char *r, int h, off_t start, off_t end)
{
    char req[PATH_MAX * 3 + 9000];
    char path_enc[PATH_MAX * 3];
    if (!url_encode(r, path_enc, sizeof path_enc)) return -1;
    int n = snprintf(req, sizeof req,
                     "GET %s%s HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: %s\r\nRange: bytes=%lld-%lld\r\nConnection: close\r\n\r\n",
                     prefix, path_enc, auth, (long long)start, (long long)(end - 1));
    if (n <= 0 || n >= (int)sizeof req) return -1;

    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct timeval tv = { .tv_sec = 30, .tv_usec = 0 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int rc = -1;
    if (connect(s, (struct sockaddr *)&a, sizeof a) != 0 || send_all(s, req, (size_t)n) != 0) goto out;

    static __thread char buf[256 * 1024];
    size_t have = 0;
    char *body = NULL;
    while (!body) {
        if (have >= sizeof buf - 1) goto out;
        ssize_t k = recv(s, buf + have, sizeof buf - 1 - have, 0);
        if (k < 0 && errno == EINTR) continue;
        if (k <= 0) goto out;
        have += (size_t)k;
        buf[have] = 0;
        body = strstr(buf, "\r\n\r\n");
    }
    int status = 0;
    if (sscanf(buf, "HTTP/%*s %d", &status) != 1) goto out;
    /* 206 for the range; 200 means the whole file came, which is only right from offset 0. */
    if (!(status == 206 || (status == 200 && start == 0))) {
        say("netfs: %s: HTTP %d", r, status);
        goto out;
    }
    body += 4;
    off_t want = end - start, got = 0;
    size_t first = have - (size_t)(body - buf);
    if (first > (size_t)want) first = (size_t)want;
    if (first && real.pwrite(h, body, first, start) != (ssize_t)first) goto out;
    got = (off_t)first;
    while (got < want) {
        size_t cap = sizeof buf;
        if ((off_t)cap > want - got) cap = (size_t)(want - got);
        ssize_t k = recv(s, buf, cap, 0);
        if (k < 0 && errno == EINTR) continue;
        if (k <= 0) goto out;
        if (real.pwrite(h, buf, (size_t)k, start + got) != k) goto out;
        got += k;
    }
    rc = 0;
out:
    real.close(s);
    return rc;
}

static int fetch_retry(const char *r, int h, off_t start, off_t end)
{
    for (int i = 0; i < RETRIES; i++) {
        if (i) usleep((useconds_t)(250000u << (i - 1)));   /* 0.25, 0.5, 1, 2, 4 s */
        if (fetch_once(r, h, start, end) == 0) return 0;
    }
    return -1;
}

struct part { const char *r; int h; off_t start, end; int rc; };

static void *part_main(void *arg)
{
    struct part *p = arg;
    p->rc = fetch_retry(p->r, p->h, p->start, p->end);
    return NULL;
}

/* [start, end) of [r] into [h], as up to PARTS ranged GETs at once. */
static int fetch(const char *r, int h, off_t start, off_t end, int background)
{
    double t0 = now_ms();
    off_t len = end - start;
    int n = (int)((len + ((off_t)1 << 20) - 1) >> 20);
    if (n > PARTS) n = PARTS;
    if (n < 1) n = 1;
    off_t step = (len + n - 1) / n;
    step = (step + 4095) & ~(off_t)4095;
    struct part parts[PARTS];
    pthread_t tids[PARTS];
    int started[PARTS] = {0};
    int count = 0;
    for (off_t o = start; o < end && count < PARTS; o += step) {
        struct part *p = &parts[count];
        p->r = r; p->h = h; p->start = o; p->end = o + step < end ? o + step : end; p->rc = -1;
        started[count] = count > 0 && pthread_create(&tids[count], NULL, part_main, p) == 0;
        count++;
    }
    /* The first part runs on this thread (and any part whose thread didn't start). */
    for (int i = 0; i < count; i++) if (!started[i]) part_main(&parts[i]);
    int rc = 0;
    for (int i = 0; i < count; i++) {
        if (started[i]) pthread_join(tids[i], NULL);
        if (parts[i].rc != 0) rc = -1;
    }
    double ms = now_ms() - t0;
    if (rc == 0)
        say("netfs: %s%s %lld+%lld KB in %.0f ms (%.1f MB/s)", background ? "(ahead) " : "", r, (long long)(start >> 10),
            (long long)(len >> 10), ms, ms > 0 ? len / 1048.576 / ms : 0.0);
    else
        say("netfs: %s: couldn't fetch %lld-%lld", r, (long long)start, (long long)end);
    return rc;
}

/* Chunks being fetched right now, so a read and the read-ahead never fetch the same one twice. */
static struct { dev_t dev; ino_t ino; off_t chunk; int used; } fly[64];
static pthread_cond_t fly_cv = PTHREAD_COND_INITIALIZER;
static int background_running;

/* 1: the caller now owns fetching [chunk]. 0: someone else was fetching it and has finished (check again). */
static int claim(dev_t dev, ino_t ino, off_t chunk)
{
    pthread_mutex_lock(&lock);
    for (;;) {
        int busy = 0, free_slot = -1;
        for (int i = 0; i < 64; i++) {
            if (fly[i].used && fly[i].dev == dev && fly[i].ino == ino && fly[i].chunk == chunk) busy = 1;
            else if (!fly[i].used && free_slot < 0) free_slot = i;
        }
        if (busy) {
            pthread_cond_wait(&fly_cv, &lock);
            pthread_mutex_unlock(&lock);
            return 0;
        }
        if (free_slot >= 0) {
            fly[free_slot].dev = dev; fly[free_slot].ino = ino; fly[free_slot].chunk = chunk; fly[free_slot].used = 1;
        }
        pthread_mutex_unlock(&lock);
        return 1;
    }
}

static void unclaim(dev_t dev, ino_t ino, off_t chunk)
{
    pthread_mutex_lock(&lock);
    for (int i = 0; i < 64; i++)
        if (fly[i].used && fly[i].dev == dev && fly[i].ino == ino && fly[i].chunk == chunk) fly[i].used = 0;
    pthread_cond_broadcast(&fly_cv);
    pthread_mutex_unlock(&lock);
}

static void fork_prepare(void) { pthread_mutex_lock(&lock); }
static void fork_parent(void) { pthread_mutex_unlock(&lock); }
static void fork_child(void)
{
    /* Only the forking thread lives on: nothing is being fetched in the child. */
    memset(fly, 0, sizeof fly);
    background_running = 0;
    pthread_mutex_init(&lock, NULL);
    pthread_cond_init(&fly_cv, NULL);
}

static int present(int h, off_t c, off_t ce) { return lseek(h, c, SEEK_HOLE) >= ce; }

/* Fetches chunk [c, ce) unless it is there already or another thread has it. */
static int fill_chunk(const char *r, int h, dev_t dev, ino_t ino, off_t c, off_t ce, int background)
{
    while (!present(h, c, ce)) {
        if (!claim(dev, ino, c)) continue;
        int rc = present(h, c, ce) ? 0 : fetch(r, h, c, ce, background);
        unclaim(dev, ino, c);
        return rc;
    }
    return 0;
}

struct ahead { char *r; off_t from; int chunks; };

static void *ahead_main(void *arg)
{
    struct ahead *a = arg;
    char path[PATH_MAX];
    if (snprintf(path, sizeof path, "%s/%s", root, a->r) < (int)sizeof path) {
        int h = open(path, O_RDWR | O_CLOEXEC);
        if (h >= 0) {
            if (h < MAXFD) __atomic_store_n(&state[h], FD_PLAIN, __ATOMIC_RELEASE);
            struct stat sb;
            if (fstat(h, &sb) == 0) {
                for (int i = 0; i < a->chunks; i++) {
                    off_t c = a->from + (off_t)i * CHUNK;
                    if (c >= sb.st_size) break;
                    off_t ce = c + CHUNK < sb.st_size ? c + CHUNK : sb.st_size;
                    if (fill_chunk(a->r, h, sb.st_dev, sb.st_ino, c, ce, 1) != 0) break;
                }
            }
            if (h < MAXFD) __atomic_store_n(&state[h], FD_UNKNOWN, __ATOMIC_RELEASE);
            real.close(h);
        }
    }
    free(a->r);
    free(a);
    __atomic_sub_fetch(&background_running, 1, __ATOMIC_ACQ_REL);
    return NULL;
}

/* After a read that had to wait, the next chunks are fetched in the background (games mostly read on). */
static void read_ahead(const char *r, int h, off_t next, off_t size)
{
    if (next >= size || present(h, next, next + CHUNK < size ? next + CHUNK : size)) return;
    if (__atomic_add_fetch(&background_running, 1, __ATOMIC_ACQ_REL) > MAX_BACKGROUND) {
        __atomic_sub_fetch(&background_running, 1, __ATOMIC_ACQ_REL);
        return;
    }
    struct ahead *a = malloc(sizeof *a);
    char *copy = strdup(r);
    pthread_t t;
    pthread_attr_t at;
    if (!a || !copy) goto fail;
    a->r = copy; a->from = next; a->chunks = AHEAD;
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&t, &at, ahead_main, a) == 0) { pthread_attr_destroy(&at); return; }
    pthread_attr_destroy(&at);
fail:
    free(copy);
    free(a);
    __atomic_sub_fetch(&background_running, 1, __ATOMIC_ACQ_REL);
}

/* Makes sure [off, off+len) of [fd] is on the device (all of the file when [whole]). -1 + EIO when it can't be. */
static int ensure(int fd, off_t off, size_t len, int whole)
{
    if (classify(fd) != FD_NET) return 0;
    char *r = rel_of(fd);
    if (!r) return 0;
    int rc = 0;
    int h = helper_of(fd, r);
    struct stat sb;
    if (h < 0 || fstat(h, &sb) != 0) goto done;
    off_t size = sb.st_size;
    if (whole) { off = 0; len = (size_t)size; }
    if (size <= 0 || off >= size || len == 0) goto done;
    if (lseek(h, 0, SEEK_HOLE) >= size) {
        __atomic_store_n(&state[fd], FD_COMPLETE, __ATOMIC_RELEASE);
        goto done;
    }
    off_t end = off + (off_t)len;
    if (end > size || end < off) end = size;
    int waited = 0;
    off_t last = 0;
    for (off_t c = off / CHUNK * CHUNK; c < end; c += CHUNK) {
        off_t ce = c + CHUNK < size ? c + CHUNK : size;
        last = c;
        if (present(h, c, ce)) continue;
        waited = 1;
        if (fill_chunk(r, h, sb.st_dev, sb.st_ino, c, ce, 0) != 0) { rc = -1; break; }
    }
    if (rc == 0 && waited && !whole) read_ahead(r, h, last + CHUNK, size);
done:
    free(r);
    if (rc) errno = EIO;
    return rc;
}

static size_t iov_len(const struct iovec *iov, int cnt)
{
    size_t t = 0;
    for (int i = 0; i < cnt; i++) t += iov[i].iov_len;
    return t;
}

static inline int netfd(int fd) { return enabled && classify(fd) == FD_NET; }

EXPORT ssize_t read(int fd, void *buf, size_t count)
{
    ready();
    if (netfd(fd)) {
        off_t cur = lseek(fd, 0, SEEK_CUR);
        if (cur >= 0 && ensure(fd, cur, count, 0) != 0) return -1;
    }
    return real.read(fd, buf, count);
}

EXPORT ssize_t pread(int fd, void *buf, size_t count, off_t offset)
{
    ready();
    if (netfd(fd) && ensure(fd, offset, count, 0) != 0) return -1;
    return real.pread(fd, buf, count, offset);
}

EXPORT ssize_t pread64(int fd, void *buf, size_t count, off_t offset)
{
    ready();
    if (netfd(fd) && ensure(fd, offset, count, 0) != 0) return -1;
    return real.pread64(fd, buf, count, offset);
}

EXPORT ssize_t readv(int fd, const struct iovec *iov, int iovcnt)
{
    ready();
    if (netfd(fd)) {
        off_t cur = lseek(fd, 0, SEEK_CUR);
        if (cur >= 0 && ensure(fd, cur, iov_len(iov, iovcnt), 0) != 0) return -1;
    }
    return real.readv(fd, iov, iovcnt);
}

EXPORT ssize_t preadv(int fd, const struct iovec *iov, int iovcnt, off_t offset)
{
    ready();
    if (netfd(fd) && ensure(fd, offset, iov_len(iov, iovcnt), 0) != 0) return -1;
    return real.preadv(fd, iov, iovcnt, offset);
}

EXPORT ssize_t preadv64(int fd, const struct iovec *iov, int iovcnt, off_t offset)
{
    ready();
    if (netfd(fd) && ensure(fd, offset, iov_len(iov, iovcnt), 0) != 0) return -1;
    return real.preadv64(fd, iov, iovcnt, offset);
}

EXPORT ssize_t write(int fd, const void *buf, size_t count)
{
    ready();
    if (netfd(fd) && ensure(fd, 0, 0, 1) != 0) return -1;
    return real.write(fd, buf, count);
}

EXPORT ssize_t pwrite(int fd, const void *buf, size_t count, off_t offset)
{
    ready();
    if (netfd(fd) && ensure(fd, 0, 0, 1) != 0) return -1;
    return real.pwrite(fd, buf, count, offset);
}

EXPORT ssize_t pwrite64(int fd, const void *buf, size_t count, off_t offset)
{
    ready();
    if (netfd(fd) && ensure(fd, 0, 0, 1) != 0) return -1;
    return real.pwrite64(fd, buf, count, offset);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt)
{
    ready();
    if (netfd(fd) && ensure(fd, 0, 0, 1) != 0) return -1;
    return real.writev(fd, iov, iovcnt);
}

EXPORT void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    ready();
    if (!(flags & MAP_ANONYMOUS) && fd >= 0 && netfd(fd) && ensure(fd, offset, length, 0) != 0) return MAP_FAILED;
    return real.mmap(addr, length, prot, flags, fd, offset);
}

EXPORT void *mmap64(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    ready();
    if (!(flags & MAP_ANONYMOUS) && fd >= 0 && netfd(fd) && ensure(fd, offset, length, 0) != 0) return MAP_FAILED;
    return real.mmap64(addr, length, prot, flags, fd, offset);
}

EXPORT int close(int fd)
{
    ready();
    if (enabled) forget(fd);
    return real.close(fd);
}

EXPORT int dup2(int oldfd, int newfd)
{
    ready();
    if (enabled && oldfd != newfd) forget(newfd);
    return real.dup2(oldfd, newfd);
}

EXPORT int dup3(int oldfd, int newfd, int flags)
{
    ready();
    if (enabled && oldfd != newfd) forget(newfd);
    return real.dup3(oldfd, newfd, flags);
}
