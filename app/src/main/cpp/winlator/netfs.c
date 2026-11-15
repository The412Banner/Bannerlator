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
 *  - It must come FIRST in LD_PRELOAD: libfakeinput.so's read() sends every read that isn't a controller straight to
 *    syscall(SYS_read), so a library after it never sees read() (11-15: Darksiders' and GTA V's Steam emulator
 *    settings, steam_api.ini, were read that way, came back empty, and steam_api.dll failed to start).
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
#include <dirent.h>
#include <signal.h>
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
/* Files up to this size are fetched whole the first time they are used. */
#define WHOLE_MAX ((off_t)1 << 30)
/* Files up to this size are fetched together with the other small files of their folder. */
#define SMALL_MAX ((off_t)1 << 20)
#define DIR_MAX_FILES 8192

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
typedef int (*fstat_fn)(int, struct stat *);
typedef int (*stat_fn)(const char *, struct stat *);
typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
typedef FILE *(*fopen_fn)(const char *, const char *);
typedef FILE *(*fdopen_fn)(int, const char *);
typedef FILE *(*freopen_fn)(const char *, const char *, FILE *);
typedef int (*fclose_fn)(FILE *);
typedef int (*closedir_fn)(DIR *);

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
    fstat_fn fstat;
    stat_fn stat, lstat;
    fstatat_fn fstatat;
    fopen_fn fopen, fopen64;
    fdopen_fn fdopen;
    freopen_fn freopen, freopen64;
    fclose_fn fclose;
    closedir_fn closedir;
} real;

static pthread_once_t once = PTHREAD_ONCE_INIT;
static int enabled;
static char root[PATH_MAX];
static size_t root_len;
static dev_t root_dev;
static int port;
static char prefix[8192];
static char auth[1024];
/* Where the "Loading from NAS" status line goes (from the config), or "" for none. */
static char status_path[PATH_MAX];

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

extern char **environ;

/* The next definitions of the hooked calls (libc's, or another preloaded library's). Safe at any time. */
static void resolve_real(void)
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
    real.fstat = (fstat_fn)dlsym(RTLD_NEXT, "fstat");
    real.stat = (stat_fn)dlsym(RTLD_NEXT, "stat");
    real.lstat = (stat_fn)dlsym(RTLD_NEXT, "lstat");
    real.fstatat = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    real.fopen = (fopen_fn)dlsym(RTLD_NEXT, "fopen");
    real.fopen64 = (fopen_fn)dlsym(RTLD_NEXT, "fopen64");
    real.fdopen = (fdopen_fn)dlsym(RTLD_NEXT, "fdopen");
    real.freopen = (freopen_fn)dlsym(RTLD_NEXT, "freopen");
    real.freopen64 = (freopen_fn)dlsym(RTLD_NEXT, "freopen64");
    real.fclose = (fclose_fn)dlsym(RTLD_NEXT, "fclose");
    real.closedir = (closedir_fn)dlsym(RTLD_NEXT, "closedir");
    if (!real.pread64) real.pread64 = real.pread;
    if (!real.preadv64) real.preadv64 = real.preadv;
    if (!real.pwrite64) real.pwrite64 = real.pwrite;
    if (!real.mmap64) real.mmap64 = real.mmap;
    if (!real.fopen64) real.fopen64 = real.fopen;
    if (!real.freopen64) real.freopen64 = real.freopen;
}

/*
 * Reads the settings. NOT from the library's constructor: on Android a preloaded library's constructor runs before
 * libc sets up the environment, so getenv() finds nothing there (11-15 device test: the library stayed off in every
 * game). It runs on the first hooked call made once the environment exists.
 */
static void init_env(void)
{
    const char *r = getenv("BLNETFS_ROOT"), *p = getenv("BLNETFS_PORT");
    const char *pre = getenv("BLNETFS_PREFIX"), *a = getenv("BLNETFS_AUTH");
    static char cport[32], cprefix[8192], cauth[1024];
    const char *conf = getenv("BLNETFS_CONF");
    if (conf && *conf) {
        /* Plain reads through libc's read (not stdio, whose reads would come back into these hooks). */
        static char text[12000];
        int f = open(conf, O_RDONLY | O_CLOEXEC);
        ssize_t got = 0;
        if (f >= 0) {
            while (got < (ssize_t)sizeof text - 1) {
                ssize_t k = real.read(f, text + got, sizeof text - 1 - (size_t)got);
                if (k < 0 && errno == EINTR) continue;
                if (k <= 0) break;
                got += k;
            }
            real.close(f);
        }
        text[got > 0 ? got : 0] = 0;
        for (char *line = text, *next; line && *line; line = next) {
            next = strchr(line, '\n');
            if (next) *next++ = 0;
            size_t n = strlen(line);
            while (n && line[n - 1] == '\r') line[--n] = 0;
            if (!strncmp(line, "port=", 5) && n - 5 < sizeof cport) { strcpy(cport, line + 5); p = cport; }
            else if (!strncmp(line, "prefix=", 7) && n - 7 < sizeof cprefix) { strcpy(cprefix, line + 7); pre = cprefix; }
            else if (!strncmp(line, "auth=", 5) && n - 5 < sizeof cauth) { strcpy(cauth, line + 5); a = cauth; }
            else if (!strncmp(line, "status=", 7) && n - 7 < sizeof status_path) strcpy(status_path, line + 7);
        }
    }
    if (!r || !*r || !p || !pre || !a) return;
    struct stat sb;
    if (real.stat(r, &sb) != 0 || !S_ISDIR(sb.st_mode)) return;
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

static int env_done;
static pthread_mutex_t env_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t env_owner;
static int env_busy;

__attribute__((constructor)) static void ctor(void)
{
    pthread_once(&once, resolve_real);
    /* A fork while another thread holds the lock (or fetches a chunk) must not leave the child waiting forever. */
    pthread_atfork(fork_prepare, fork_parent, fork_child);
}

/* Every hook starts here: the real calls are known; the settings are read once the environment exists. */
static inline void ready(void)
{
    pthread_once(&once, resolve_real);
    if (__atomic_load_n(&env_done, __ATOMIC_ACQUIRE)) return;
    if (!environ || !environ[0]) return;   /* too early: pass through, try again on the next call */
    if (env_busy && pthread_equal(env_owner, pthread_self())) return;   /* a call made while reading them */
    pthread_mutex_lock(&env_lock);
    if (!env_done) {
        env_owner = pthread_self();
        env_busy = 1;
        init_env();
        env_busy = 0;
        __atomic_store_n(&env_done, 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&env_lock);
}

/* What [fd] is: a stand-in file under the root (FD_NET), anything else (FD_PLAIN). Worked out once per descriptor. */
static int classify(int fd)
{
    if (!enabled || fd < 0 || fd >= MAXFD) return FD_PLAIN;
    int s = __atomic_load_n(&state[fd], __ATOMIC_ACQUIRE);
    if (s) return s;
    int ns = FD_PLAIN;
    char *r = NULL;
    struct stat sb;
    if (real.fstat(fd, &sb) == 0 && S_ISREG(sb.st_mode) && sb.st_dev == root_dev) {
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
    /* Heap, not stack: this runs on the helper's own threads, but keep their stacks small anyway. */
    enum { REQ_CAP = PATH_MAX * 3 + 9000, ENC_CAP = PATH_MAX * 3, BUF_CAP = 256 * 1024 };
    char *req = malloc(REQ_CAP), *path_enc = malloc(ENC_CAP), *buf = malloc(BUF_CAP);
    int s = -1, rc = -1;
    if (!req || !path_enc || !buf) goto out;
    if (!url_encode(r, path_enc, ENC_CAP)) goto out;
    int n = snprintf(req, REQ_CAP,
                     "GET %s%s HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: %s\r\nRange: bytes=%lld-%lld\r\nConnection: close\r\n\r\n",
                     prefix, path_enc, auth, (long long)start, (long long)(end - 1));
    if (n <= 0 || n >= REQ_CAP) goto out;

    s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) goto out;
    struct timeval tv = { .tv_sec = 30, .tv_usec = 0 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons((uint16_t)port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(s, (struct sockaddr *)&a, sizeof a) != 0 || send_all(s, req, (size_t)n) != 0) goto out;

    size_t have = 0;
    char *body = NULL;
    while (!body) {
        if (have >= BUF_CAP - 1) goto out;
        ssize_t k = recv(s, buf + have, BUF_CAP - 1 - have, 0);
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
        size_t cap = BUF_CAP;
        if ((off_t)cap > want - got) cap = (size_t)(want - got);
        ssize_t k = recv(s, buf, cap, 0);
        if (k < 0 && errno == EINTR) continue;
        if (k <= 0) goto out;
        if (real.pwrite(h, buf, (size_t)k, start + got) != k) goto out;
        got += k;
    }
    rc = 0;
out:
    if (s >= 0) real.close(s);
    free(req);
    free(path_enc);
    free(buf);
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

/*
 * Starts one of the helper's threads with every signal blocked and its own 256 KB stack. Wine sends signals to
 * threads it knows and its handlers expect a Wine thread; and the hooks are called on Wine's small unix-side stack,
 * so all network work runs here, never on the game's thread (11-15: Streets of Rage Remake crashed in the Wine heap
 * only while chunks were being fetched).
 */
static int spawn(pthread_t *t, int detached, void *(*fn)(void *), void *arg)
{
    pthread_attr_t at;
    sigset_t all, old;
    pthread_attr_init(&at);
    pthread_attr_setstacksize(&at, 256 * 1024);
    if (detached) pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    sigfillset(&all);
    pthread_sigmask(SIG_SETMASK, &all, &old);
    int rc = pthread_create(t, &at, fn, arg);
    pthread_sigmask(SIG_SETMASK, &old, NULL);
    pthread_attr_destroy(&at);
    return rc;
}

/* ── What the game is waiting for, for Bannerlator's "Loading from NAS" tag ──
 * One line "<done> <total> <MB/s> <file>" in the status file named by the config ("status="), rewritten at most
 * every 200 ms while a fetch the game waits for runs, and "<total> <total> …" when it ends (the tag then hides). */
static int status_fd = -1;
static long long status_last;
static pthread_mutex_t status_lock = PTHREAD_MUTEX_INITIALIZER;

static void status_write(const char *name, long long done, long long total, double mbps, int force)
{
    if (!status_path[0]) return;
    long long now = (long long)now_ms();
    if (!force && now - __atomic_load_n(&status_last, __ATOMIC_RELAXED) < 200) return;
    pthread_mutex_lock(&status_lock);
    if (force || now - status_last >= 200) {
        status_last = now;
        if (status_fd < 0) {
            status_fd = open(status_path, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
            if (status_fd >= 0 && status_fd < MAXFD) __atomic_store_n(&state[status_fd], FD_PLAIN, __ATOMIC_RELEASE);
        }
        if (status_fd >= 0) {
            const char *base = strrchr(name, '/');
            char line[600];
            int n = snprintf(line, sizeof line, "%lld %lld %.1f %s\n", done, total, mbps, base ? base + 1 : name);
            if (n > 0 && n < (int)sizeof line) {
                real.pwrite(status_fd, line, (size_t)n, 0);
                if (ftruncate(status_fd, n) != 0) { /* keep going: the line is still there */ }
            }
        }
    }
    pthread_mutex_unlock(&status_lock);
}

/* ── One fetch: ranges of a file, split into pieces, fetched by up to PARTS helper threads ── */
struct job {
    const char *r;
    int h;
    off_t (*ranges)[2];
    int nranges;
    off_t piece;
    int background;
    pthread_mutex_t m;
    int ri;
    off_t next;
    int failed;
    long long total, done;
    double t0;
};

static int job_take(struct job *j, off_t *s, off_t *e)
{
    int ok = 0;
    pthread_mutex_lock(&j->m);
    while (j->ri < j->nranges && j->next >= j->ranges[j->ri][1]) {
        j->ri++;
        if (j->ri < j->nranges) j->next = j->ranges[j->ri][0];
    }
    if (!j->failed && j->ri < j->nranges) {
        *s = j->next;
        *e = j->next + j->piece < j->ranges[j->ri][1] ? j->next + j->piece : j->ranges[j->ri][1];
        j->next = *e;
        ok = 1;
    }
    pthread_mutex_unlock(&j->m);
    return ok;
}

static void *job_main(void *arg)
{
    struct job *j = arg;
    off_t s, e;
    while (job_take(j, &s, &e)) {
        if (fetch_retry(j->r, j->h, s, e) != 0) {
            pthread_mutex_lock(&j->m);
            j->failed = 1;
            pthread_mutex_unlock(&j->m);
            break;
        }
        long long d = __atomic_add_fetch(&j->done, (long long)(e - s), __ATOMIC_ACQ_REL);
        if (!j->background) {
            double ms = now_ms() - j->t0;
            status_write(j->r, d, j->total, ms > 0 ? d / 1048.576 / ms : 0.0, 0);
        }
    }
    return NULL;
}

/* The [n] ranges of [r] into [h], in pieces of [piece] bytes, up to PARTS at once on helper threads (the calling
 * game thread only waits; a thread that won't start leaves its share to the others, or to this thread). */
static int fetch_ranges(const char *r, int h, off_t (*ranges)[2], int n, off_t piece, int background)
{
    struct job j;
    memset(&j, 0, sizeof j);
    j.r = r; j.h = h; j.ranges = ranges; j.nranges = n; j.piece = piece; j.background = background;
    pthread_mutex_init(&j.m, NULL);
    j.next = n > 0 ? ranges[0][0] : 0;
    j.t0 = now_ms();
    long long pieces = 0;
    for (int i = 0; i < n; i++) { j.total += ranges[i][1] - ranges[i][0]; pieces += (ranges[i][1] - ranges[i][0] + piece - 1) / piece; }
    if (j.total <= 0) { pthread_mutex_destroy(&j.m); return 0; }
    if (!background) status_write(r, 0, j.total, 0.0, 1);
    int workers = pieces < PARTS ? (int)pieces : PARTS;
    pthread_t tids[PARTS];
    int started = 0;
    for (int i = 0; i < workers; i++) if (spawn(&tids[started], 0, job_main, &j) == 0) started++;
    if (started == 0) job_main(&j);
    for (int i = 0; i < started; i++) pthread_join(tids[i], NULL);
    double ms = now_ms() - j.t0;
    int rc = j.failed || j.done < j.total ? -1 : 0;
    if (!background) status_write(r, j.total, j.total, 0.0, 1);
    if (rc == 0)
        say("netfs: %s%s %lld KB in %d piece%s, %.0f ms (%.1f MB/s)", background ? "(ahead) " : "", r, j.total >> 10,
            (int)pieces, pieces == 1 ? "" : "s", ms, ms > 0 ? j.total / 1048.576 / ms : 0.0);
    else
        say("netfs: %s: couldn't fetch (%lld of %lld KB arrived)", r, j.done >> 10, j.total >> 10);
    pthread_mutex_destroy(&j.m);
    return rc;
}

/* [start, end) of [r] into [h]: one chunk, as 1 MB pieces. */
static int fetch(const char *r, int h, off_t start, off_t end, int background)
{
    off_t range[1][2] = { { start, end } };
    return fetch_ranges(r, h, range, 1, (off_t)1 << 20, background);
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

#define WHOLE_KEY ((off_t)-1)

/* Puts back the file's date after fetched data was written (Bannerlator's cleanup empties only files whose date is
 * still the drive's; a file the game wrote to keeps its new date and is left alone). */
static void keep_date(int h, const struct stat *before)
{
    struct timespec ts[2] = { { 0, UTIME_OMIT }, before->st_mtim };
    futimens(h, ts);
}

/* Fetches chunk [c, ce) unless it is there already or another thread has it. */
static int fill_chunk(const char *r, int h, dev_t dev, ino_t ino, off_t c, off_t ce, int background)
{
    while (!present(h, c, ce)) {
        /* A whole-file fetch of this file is running: wait for it, then check again. */
        if (!claim(dev, ino, WHOLE_KEY)) continue;
        unclaim(dev, ino, WHOLE_KEY);
        if (!claim(dev, ino, c)) continue;
        struct stat before;
        int have = real.fstat(h, &before) == 0;
        int rc = present(h, c, ce) ? 0 : fetch(r, h, c, ce, background);
        if (rc == 0 && have) keep_date(h, &before);
        unclaim(dev, ino, c);
        return rc;
    }
    return 0;
}

/*
 * Every hole of the file, fetched as 4 MB pieces up to PARTS at once: the first time the game opens a file up to
 * WHOLE_MAX, all of it comes over before the game goes on (11-15: Streets of Rage Remake ran only with SorR.dat
 * whole; its other reads were read() calls that libfakeinput.so sent past this library, see the top).
 */
static int fill_whole(const char *r, int h, dev_t dev, ino_t ino, off_t size)
{
    for (;;) {
        if (lseek(h, 0, SEEK_HOLE) >= size) return 0;
        if (!claim(dev, ino, WHOLE_KEY)) continue;   /* another thread did it: check again */
        enum { MAXR = 4096 };
        off_t (*ranges)[2] = malloc(sizeof(off_t[2]) * MAXR);
        int n = 0, rc = -1;
        struct stat before;
        int have = real.fstat(h, &before) == 0;
        if (ranges) {
            off_t pos = 0;
            while (pos < size && n < MAXR) {
                off_t hole = lseek(h, pos, SEEK_HOLE);
                if (hole < 0 || hole >= size) break;
                off_t data = lseek(h, hole, SEEK_DATA);
                if (data < 0 || data > size) data = size;
                ranges[n][0] = hole; ranges[n][1] = data; n++;
                pos = data;
            }
            rc = n ? fetch_ranges(r, h, ranges, n, (off_t)4 << 20, 0) : 0;
            if (rc == 0 && n && have) keep_date(h, &before);
            free(ranges);
        }
        unclaim(dev, ino, WHOLE_KEY);
        return rc;
    }
}

/* ── A folder of small files, fetched together ── */
#define DIR_KEY ((off_t)-2)

struct dent { char *rel; off_t size; };
struct dirjob {
    struct dent *e;
    int n, next, failed;
    pthread_mutex_t m;
    long long total, done;
    double t0;
    char label[PATH_MAX];
};

static void *dirjob_main(void *arg)
{
    struct dirjob *j = arg;
    for (;;) {
        pthread_mutex_lock(&j->m);
        int i = j->next < j->n ? j->next++ : -1;
        pthread_mutex_unlock(&j->m);
        if (i < 0) break;
        char path[PATH_MAX];
        if (snprintf(path, sizeof path, "%s/%s", root, j->e[i].rel) >= (int)sizeof path) continue;
        int h = open(path, O_RDWR | O_CLOEXEC);
        if (h < 0) continue;
        if (h < MAXFD) __atomic_store_n(&state[h], FD_PLAIN, __ATOMIC_RELEASE);
        struct stat before;
        if (real.fstat(h, &before) == 0 && before.st_size > 0 && lseek(h, 0, SEEK_HOLE) < before.st_size) {
            if (fetch_retry(j->e[i].rel, h, 0, before.st_size) == 0) keep_date(h, &before);
            else __atomic_add_fetch(&j->failed, 1, __ATOMIC_ACQ_REL);
        }
        if (h < MAXFD) __atomic_store_n(&state[h], FD_UNKNOWN, __ATOMIC_RELEASE);
        real.close(h);
        long long d = __atomic_add_fetch(&j->done, (long long)j->e[i].size, __ATOMIC_ACQ_REL);
        double ms = now_ms() - j->t0;
        status_write(j->label, d, j->total, ms > 0 ? d / 1048.576 / ms : 0.0, 0);
    }
    return NULL;
}

/*
 * The first time the game opens a small file, every small file of its folder that still has holes comes over
 * together, up to PARTS at once: games open the files of a folder of small files one after the other, and each one
 * alone is a ~16 ms request (11-15: Streets of Rage Remake's ~480 palette files took ~8 s one by one).
 */
static void fill_dir(const char *rel)
{
    char dir_rel[PATH_MAX], dpath[PATH_MAX];
    const char *slash = strrchr(rel, '/');
    size_t dl = slash ? (size_t)(slash - rel) : 0;
    if (dl >= sizeof dir_rel) return;
    memcpy(dir_rel, rel, dl);
    dir_rel[dl] = 0;
    if (snprintf(dpath, sizeof dpath, dl ? "%s/%s" : "%s%s", root, dir_rel) >= (int)sizeof dpath) return;
    struct stat ds;
    if (real.stat(dpath, &ds) != 0 || !S_ISDIR(ds.st_mode)) return;
    if (!claim(ds.st_dev, ds.st_ino, DIR_KEY)) return;   /* another thread fetched this folder: the caller checks again */
    struct dirjob j;
    memset(&j, 0, sizeof j);
    j.e = calloc(DIR_MAX_FILES, sizeof *j.e);
    DIR *d = j.e ? opendir(dpath) : NULL;
    if (d) {
        struct dirent *de;
        while ((de = readdir(d)) && j.n < DIR_MAX_FILES) {
            if (de->d_name[0] == '.' && (!de->d_name[1] || (de->d_name[1] == '.' && !de->d_name[2]))) continue;
            char full[PATH_MAX];
            if (snprintf(full, sizeof full, "%s/%s", dpath, de->d_name) >= (int)sizeof full) continue;
            struct stat fs;
            if (real.lstat(full, &fs) != 0 || !S_ISREG(fs.st_mode) || fs.st_size <= 0 || fs.st_size > SMALL_MAX) continue;
            /* Already whole? st_blocks can't tell: f2fs counts a 4 KB block for a tiny file that is all hole. */
            int t = open(full, O_RDONLY | O_CLOEXEC);
            if (t < 0) continue;
            off_t hole = lseek(t, 0, SEEK_HOLE);
            real.close(t);
            if (hole >= fs.st_size) continue;
            char relp[PATH_MAX];
            if (snprintf(relp, sizeof relp, dl ? "%s/%s" : "%s%s", dir_rel, de->d_name) >= (int)sizeof relp) continue;
            j.e[j.n].rel = strdup(relp);
            j.e[j.n].size = fs.st_size;
            if (j.e[j.n].rel) { j.total += fs.st_size; j.n++; }
        }
        real.closedir(d);
    }
    if (j.n > 0) {
        pthread_mutex_init(&j.m, NULL);
        snprintf(j.label, sizeof j.label, "%s (%d files)", dl ? dir_rel : "game folder", j.n);
        j.t0 = now_ms();
        status_write(j.label, 0, j.total, 0.0, 1);
        int workers = j.n < PARTS ? j.n : PARTS, started = 0;
        pthread_t tids[PARTS];
        for (int i = 0; i < workers; i++) if (spawn(&tids[started], 0, dirjob_main, &j) == 0) started++;
        if (started == 0) dirjob_main(&j);
        for (int i = 0; i < started; i++) pthread_join(tids[i], NULL);
        double ms = now_ms() - j.t0;
        status_write(j.label, j.total, j.total, 0.0, 1);
        say("netfs: %s/ %d small files, %lld KB in %.0f ms%s", dl ? dir_rel : ".", j.n, j.total >> 10, ms, j.failed ? " (some failed)" : "");
        pthread_mutex_destroy(&j.m);
    }
    if (j.e) { for (int i = 0; i < j.n; i++) free(j.e[i].rel); free(j.e); }
    unclaim(ds.st_dev, ds.st_ino, DIR_KEY);
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
            if (real.fstat(h, &sb) == 0) {
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
    if (!a || !copy) goto fail;
    a->r = copy; a->from = next; a->chunks = AHEAD;
    if (spawn(&t, 1, ahead_main, a) == 0) return;
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
    if (h < 0 || real.fstat(h, &sb) != 0) goto done;
    off_t size = sb.st_size;
    if (size <= 0) goto done;
    if (lseek(h, 0, SEEK_HOLE) >= size) {
        __atomic_store_n(&state[fd], FD_COMPLETE, __ATOMIC_RELEASE);
        goto done;
    }
    /* A small file: its folder's small files come over together (then this one is whole, or fetched alone below). */
    if (size <= SMALL_MAX) {
        fill_dir(r);
        if (lseek(h, 0, SEEK_HOLE) >= size) {
            __atomic_store_n(&state[fd], FD_COMPLETE, __ATOMIC_RELEASE);
            goto done;
        }
    }
    /* Up to WHOLE_MAX, the whole file on its first use; bigger files stream in chunks (writes still fetch all). */
    if (whole || size <= WHOLE_MAX) {
        if (fill_whole(r, h, sb.st_dev, sb.st_ino, size) != 0) rc = -1;
        else __atomic_store_n(&state[fd], FD_COMPLETE, __ATOMIC_RELEASE);
        goto done;
    }
    if (off >= size || len == 0) goto done;
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

/*
 * stdio. A FILE's reads, and its fclose(), happen inside libc, past these hooks. So a stand-in file opened as a FILE
 * comes over when it is opened, and fclose()/closedir() forget the descriptor as close() does (a number left marked
 * would skip the next file that gets it).
 */
static FILE *stdio_opened(FILE *f, const char *mode)
{
    if (!f || !enabled) return f;
    int fd = fileno(f);
    if (fd < 0 || classify(fd) != FD_NET) return f;
    if (mode && strchr(mode, 'w')) return f;   /* emptied on open: nothing to fetch */
    int whole = mode && (strchr(mode, '+') || strchr(mode, 'a'));
    if (ensure(fd, 0, 1, whole) != 0) {
        int e = errno;
        forget(fd);
        real.fclose(f);
        errno = e;
        return NULL;
    }
    return f;
}

EXPORT FILE *fopen(const char *path, const char *mode)
{
    ready();
    FILE *f = real.fopen(path, mode);
    return stdio_opened(f, mode);
}

EXPORT FILE *fopen64(const char *path, const char *mode)
{
    ready();
    FILE *f = real.fopen64(path, mode);
    return stdio_opened(f, mode);
}

EXPORT FILE *fdopen(int fd, const char *mode)
{
    ready();
    FILE *f = real.fdopen(fd, mode);
    return stdio_opened(f, mode);
}

EXPORT FILE *freopen(const char *path, const char *mode, FILE *old)
{
    ready();
    if (enabled) forget(fileno(old));
    FILE *f = real.freopen(path, mode, old);
    return stdio_opened(f, mode);
}

EXPORT FILE *freopen64(const char *path, const char *mode, FILE *old)
{
    ready();
    if (enabled) forget(fileno(old));
    FILE *f = real.freopen64(path, mode, old);
    return stdio_opened(f, mode);
}

EXPORT int fclose(FILE *f)
{
    ready();
    if (enabled) forget(fileno(f));
    return real.fclose(f);
}

EXPORT int closedir(DIR *d)
{
    ready();
    if (enabled) forget(dirfd(d));
    return real.closedir(d);
}

/*
 * File info: a stand-in file reports the disk space of the whole file (st_blocks), as the real file would. Wine hands
 * st_blocks * 512 to Windows programs as the file's allocation size, and a stand-in that is mostly holes looked
 * almost empty on disk (11-15: Streets of Rage Remake took another start-up path with a mostly-empty SorR.dat, went
 * windowed and crashed in SDL; with the same file full of zeros it ran). Only regular files under the root that are
 * short of blocks are touched; everything else is passed on unchanged.
 */
static int under_root_path(int dirfd, const char *path)
{
    char link[48], buf[PATH_MAX];
    int f = openat(dirfd, path, O_PATH | O_CLOEXEC);
    if (f < 0) return 0;
    snprintf(link, sizeof link, "/proc/self/fd/%d", f);
    ssize_t n = readlink(link, buf, sizeof buf - 1);
    if (f < MAXFD) __atomic_store_n(&state[f], FD_UNKNOWN, __ATOMIC_RELEASE);
    real.close(f);
    if (n <= 0) return 0;
    buf[n] = 0;
    return (size_t)n > root_len + 1 && memcmp(buf, root, root_len) == 0 && buf[root_len] == '/';
}

static inline int short_of_blocks(const struct stat *sb)
{
    return enabled && S_ISREG(sb->st_mode) && sb->st_dev == root_dev && (off_t)sb->st_blocks * 512 < sb->st_size;
}

static inline void full_blocks(struct stat *sb) { sb->st_blocks = (sb->st_size + 4095) / 4096 * 8; }

EXPORT int fstat(int fd, struct stat *sb)
{
    ready();
    int rc = real.fstat(fd, sb);
    if (rc == 0 && short_of_blocks(sb) && classify(fd) >= FD_NET) full_blocks(sb);
    return rc;
}

EXPORT int stat(const char *path, struct stat *sb)
{
    ready();
    int rc = real.stat(path, sb);
    if (rc == 0 && short_of_blocks(sb) && under_root_path(AT_FDCWD, path)) full_blocks(sb);
    return rc;
}

EXPORT int lstat(const char *path, struct stat *sb)
{
    ready();
    int rc = real.lstat(path, sb);
    if (rc == 0 && short_of_blocks(sb) && under_root_path(AT_FDCWD, path)) full_blocks(sb);
    return rc;
}

EXPORT int fstatat(int dirfd, const char *path, struct stat *sb, int flags)
{
    ready();
    int rc = real.fstatat(dirfd, path, sb, flags);
    if (rc == 0 && short_of_blocks(sb)) {
        int under = (flags & AT_EMPTY_PATH) && (!path || !*path) ? classify(dirfd) >= FD_NET : under_root_path(dirfd, path);
        if (under) full_blocks(sb);
    }
    return rc;
}
