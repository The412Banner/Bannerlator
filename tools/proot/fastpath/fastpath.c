/*
 * proot fast path: path syscalls answered inside the process, without a ptrace round trip.
 *
 * proot traps every path syscall into its tracer: 15-60 us a call on a Snapdragon, most of it
 * the two cross-core wake-ups between tracee and tracer. This library, preloaded into every guest
 * process, answers the common calls itself and issues the translated syscall from a trampoline
 * page at a fixed address, which proot's seccomp filter lets through without a stop
 * (PROOT_FASTPATH, tools/proot/patches/0014).
 *
 * It decides nothing proot would decide differently. The guest path is mapped literally - the
 * longest binding whose guest path is a prefix, else the rootfs - and the parent directory is
 * opened O_PATH and must come back from /proc/self/fd as exactly that host path, which proves no
 * component on the way was a symlink: proot would then have walked the same directories. The
 * last component is looked at without following it, and a symlink there goes to proot. So does
 * "..", a trailing "/", anything under /proc, any flag not handled here and any failure to
 * decide: the real libc call runs, proot traps it and answers as it always has.
 *
 * Only syscalls Android's app seccomp policy allows are used (openat, newfstatat, statx,
 * faccessat, readlinkat, close) - not openat2 or faccessat2, which it answers with SIGSYS.
 *
 * Verified parents are remembered for PROOT_FP_TTL_MS (default 2000; 0 = verify every call): a
 * parent directory replaced by a symlink within that window would be missed, the same trade the
 * session's pathcache.c makes for the Steam client.
 *
 * Config (the launcher sets it): PROOT_FP_ROOT=<host rootfs>, PROOT_FP_BINDS=<host:guest|...>;
 * PROOT_FP_KEY=<key>, with PROOT_FASTPATH=<key> in proot's own environment: nothing is answered
 * here unless the tracer is that proot (traced_by_match).
 * PROOT_FP_OFF=1 disables it; PROOT_FP_STATS=1 reports hits and misses at exit.
 *
 * Bannerlator's proot also emulates mount(2), umount(2) and pivot_root(2) by changing its
 * bindings at run time, which the mapping here cannot follow. After the first of those it sets a
 * flag in each process's trampoline page (FP_WITHDRAWN, tools/proot/patches/0015), checked before
 * every answer, and a process that sets up after an exec makes one traced call first so its page
 * is flagged before it is used: from then on every call goes to proot, as without this library.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

#define FP_STUB_ADDR 0xffff00000UL /* must match FASTPATH_STUB_ADDR in proot's seccomp.c */
#define FP_STUB_SIZE 4096UL
#define FP_WITHDRAWN 0x800UL /* proot's flag in the page; must match FASTPATH_WITHDRAWN (patches/0015) */
#define MAX_BINDS 64
#define FP_SLOW (-100000L)

typedef long (*stub_fn)(long nr, long a, long b, long c, long d, long e, long f);
static stub_fn fp_sys;
static int fp_on;

struct bind { char *host; size_t hlen; char *guest; size_t glen; int canon; };
static struct bind binds[MAX_BINDS];
static int nbinds;
static char root[PATH_MAX];
static size_t rootlen;
static long long ttl_ns = 2000000000LL;
static unsigned long hits, misses;

static long sc(long nr, long a, long b, long c, long d, long e) { return fp_sys(nr, a, b, c, d, e, 0); }
/* proot changed its bindings at run time (an emulated mount): nothing may be answered here. */
static inline int withdrawn(void) { return *(volatile const uint64_t *)(FP_STUB_ADDR + FP_WITHDRAWN) != 0; }
static void fp_init(void);
static pthread_once_t init_once = PTHREAD_ONCE_INIT;
/* Set up on the first call that could use it: a process that only execs on pays nothing. */
static inline int fp_ready(void) {
  pthread_once(&init_once, fp_init);
  return fp_on;
}

static int under(const char *path, const char *dir, size_t dlen) {
  if (dlen == 1 && dir[0] == '/') return path[0] == '/';
  return strncmp(path, dir, dlen) == 0 && (path[dlen] == 0 || path[dlen] == '/');
}

/* ---------------------------------------------------------------- setup */

static void stats(void) {
  char line[160];
  int n = snprintf(line, sizeof line, "fastpath[%d] %s: %lu fast, %lu to proot\n", getpid(),
                   program_invocation_short_name, hits, misses);
  if (write(2, line, n) < 0) {}
}

/* The canonical form of a host path (bindings may name /sdcard, /data/user/0, ...). */
static void canonical(char *path, size_t size) {
  long fd = sc(SYS_openat, AT_FDCWD, (long)path, O_PATH | O_CLOEXEC, 0, 0);
  if (fd < 0) return;
  char link[40], out[PATH_MAX];
  snprintf(link, sizeof link, "/proc/self/fd/%ld", fd);
  long n = sc(SYS_readlinkat, AT_FDCWD, (long)link, (long)out, sizeof out - 1, 0);
  sc(SYS_close, fd, 0, 0, 0, 0);
  if (n > 0 && (size_t)n < size) { out[n] = 0; memcpy(path, out, n + 1); }
}

static pthread_mutex_t cache_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t bind_lock;
static void after_fork(void) { pthread_mutex_init(&cache_lock, NULL); pthread_mutex_init(&bind_lock, NULL); }

/*
 * The process is traced by a proot that lets the trampoline through and was given the same
 * rootfs and bindings: its own environment carries PROOT_FASTPATH=<PROOT_FP_KEY> (the launcher
 * derives the key from them) and no PROOT_NO_SECCOMP. A process that inherited the variables but
 * runs under another proot, or none, must not translate paths itself: a proot that traps the
 * trampoline would translate the host path a second time.
 */
static int traced_by_match(const char *key) {
  static char buf[32768]; /* under init_once */
  char path[48], want[160];
  long fd = sc(SYS_openat, AT_FDCWD, (long)"/proc/self/status", O_RDONLY | O_CLOEXEC, 0, 0);
  if (fd < 0) return 0;
  long n = sc(SYS_read, fd, (long)buf, 4095, 0, 0);
  sc(SYS_close, fd, 0, 0, 0, 0);
  if (n <= 0) return 0;
  buf[n] = 0;
  const char *line = strstr(buf, "\nTracerPid:");
  if (!line) return 0;
  long tracer = strtol(line + 11, NULL, 10);
  if (tracer <= 0) return 0;
  snprintf(path, sizeof path, "/proc/%ld/environ", tracer);
  if (snprintf(want, sizeof want, "PROOT_FASTPATH=%s", key) >= (int)sizeof want) return 0;
  fd = sc(SYS_openat, AT_FDCWD, (long)path, O_RDONLY | O_CLOEXEC, 0, 0);
  if (fd < 0) return 0;
  size_t have = 0;
  for (long r; have < sizeof buf - 1 && (r = sc(SYS_read, fd, (long)buf + have, sizeof buf - 1 - have, 0, 0)) > 0;) have += r;
  sc(SYS_close, fd, 0, 0, 0, 0);
  buf[have] = 0;
  int found = 0;
  for (size_t i = 0; i < have; i += strlen(buf + i) + 1) {
    if (strcmp(buf + i, want) == 0) found = 1;
    if (strncmp(buf + i, "PROOT_NO_SECCOMP=", 17) == 0) return 0;
  }
  return found;
}

/*
 * FEX (the x86-64 Steam client's translator) loads this library into its own process. Its 32-bit
 * mode does not survive that: every i386 program died with SIGILL, FEX's fatal trap, at start. So
 * in FEX, only stay on when the program it is about to run (argv[1]) is a 64-bit ELF; a 32-bit
 * one, a script, or anything that cannot be read gets the real calls, as before.
 */
static int fex_runs_32bit(void) {
  if (strcmp(program_invocation_short_name, "FEX") != 0 && strcmp(program_invocation_short_name, "FEXInterpreter") != 0)
    return 0;
  char args[PATH_MAX + 64];
  long fd = syscall(SYS_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 1;
  long n = syscall(SYS_read, fd, args, sizeof args - 1);
  syscall(SYS_close, fd);
  if (n <= 0) return 1;
  args[n] = 0;
  const char *prog = args + strlen(args) + 1;
  if (prog >= args + n || !*prog) return 1;
  unsigned char ident[5];
  fd = syscall(SYS_openat, AT_FDCWD, prog, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 1;
  n = syscall(SYS_read, fd, ident, sizeof ident);
  syscall(SYS_close, fd);
  return !(n == 5 && memcmp(ident, "\x7f" "ELF", 4) == 0 && ident[4] == 2 /* ELFCLASS64 */);
}

static void fp_init(void) {
  const char *r = getenv("PROOT_FP_ROOT"), *b = getenv("PROOT_FP_BINDS"), *t = getenv("PROOT_FP_TTL_MS");
  const char *key = getenv("PROOT_FP_KEY");
  if (!r || !*r || !key || !*key || getenv("PROOT_FP_OFF")) return;
  if (fex_runs_32bit()) return;
  void *p = mmap((void *)FP_STUB_ADDR, FP_STUB_SIZE, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
  if (p != (void *)FP_STUB_ADDR) {
    if (p != MAP_FAILED) { munmap(p, FP_STUB_SIZE); return; }
    if (errno != EEXIST) return; /* mapped already: this library loaded twice */
  } else {
    static const uint32_t code[] = {
        0xAA0003E8, /* mov x8, x0 */  0xAA0103E0, /* mov x0, x1 */  0xAA0203E1, /* mov x1, x2 */
        0xAA0303E2, /* mov x2, x3 */  0xAA0403E3, /* mov x3, x4 */  0xAA0503E4, /* mov x4, x5 */
        0xAA0603E5, /* mov x5, x6 */  0xD4000001, /* svc #0 */      0xD65F03C0, /* ret */
    };
    memcpy(p, code, sizeof code);
    __builtin___clear_cache((char *)p, (char *)p + sizeof code);
    if (mprotect(p, FP_STUB_SIZE, PROT_READ | PROT_EXEC) != 0) { munmap(p, FP_STUB_SIZE); return; }
  }
  fp_sys = (stub_fn)FP_STUB_ADDR;
  if (!traced_by_match(key)) { fp_sys = NULL; return; }
  /* A traced call (not from the trampoline), so proot stops this process once before anything is
   * answered and flags the page if its bindings changed since it started (patches/0015). */
  char cwd[PATH_MAX];
  if (syscall(SYS_getcwd, cwd, sizeof cwd) < 0) {}
  if (withdrawn()) { fp_sys = NULL; return; }
  if (t) ttl_ns = atoll(t) * 1000000LL;
  snprintf(root, sizeof root, "%s", r);
  canonical(root, sizeof root);
  rootlen = strlen(root);
  for (const char *s = b; s && *s && nbinds < MAX_BINDS;) {
    const char *end = strchr(s, '|');
    size_t len = end ? (size_t)(end - s) : strlen(s);
    char spec[2 * PATH_MAX];
    if (len > 0 && len < sizeof spec) {
      memcpy(spec, s, len);
      spec[len] = 0;
      char *colon = strchr(spec, ':');
      if (colon) *colon = 0;
      struct bind *bd = &binds[nbinds];
      /* Canonicalised on first use: some (/sdcard) are FUSE, slow to look up at every exec.
       * Static storage: malloc here would grow the heap with brk(2), which proot traps. */
      static char hosts[MAX_BINDS][PATH_MAX], guests[MAX_BINDS][512];
      const char *g = colon ? colon + 1 : spec;
      if (strlen(g) >= sizeof guests[0]) { s = end ? end + 1 : NULL; continue; }
      bd->host = hosts[nbinds];
      bd->guest = guests[nbinds];
      snprintf(bd->guest, sizeof guests[0], "%s", g);
      snprintf(bd->host, PATH_MAX, "%.*s", PATH_MAX - 1, spec);
      bd->hlen = strlen(bd->host);
      bd->glen = strlen(bd->guest);
      if (bd->guest[0] == '/' && bd->glen > 1 && bd->hlen > 0) nbinds++;
    }
    s = end ? end + 1 : NULL;
  }
  pthread_atfork(NULL, NULL, after_fork); /* registered once per exec */
  fp_on = 1;
  if (getenv("PROOT_FP_STATS")) atexit(stats);
}

static pthread_mutex_t bind_lock = PTHREAD_MUTEX_INITIALIZER;
static const struct bind *canon(struct bind *bd) {
  if (__atomic_load_n(&bd->canon, __ATOMIC_ACQUIRE)) return bd;
  if (pthread_mutex_trylock(&bind_lock) != 0) return NULL;
  if (!bd->canon) {
    canonical(bd->host, PATH_MAX);
    bd->hlen = strlen(bd->host);
    __atomic_store_n(&bd->canon, 1, __ATOMIC_RELEASE);
  }
  pthread_mutex_unlock(&bind_lock);
  return bd;
}

/* ---------------------------------------------------------------- verified parents */

#define SLOTS 256
#define SLOT_PATH 240 /* longer directories are verified every time */
struct slot { long long stamp; unsigned hash; char path[SLOT_PATH]; };
static struct slot cache[SLOTS];

static long long now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC_COARSE, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static unsigned hash_of(const char *s) {
  unsigned h = 2166136261u;
  while (*s) h = (h ^ (unsigned char)*s++) * 16777619u;
  return h;
}

static int cached(const char *dir, unsigned h) {
  if (ttl_ns <= 0 || pthread_mutex_trylock(&cache_lock) != 0) return 0;
  struct slot *e = &cache[h % SLOTS];
  int hit = e->stamp && e->hash == h && now_ns() - e->stamp < ttl_ns && strcmp(e->path, dir) == 0;
  pthread_mutex_unlock(&cache_lock);
  return hit;
}

static void remember(const char *dir, unsigned h) {
  if (ttl_ns <= 0 || strlen(dir) >= SLOT_PATH || pthread_mutex_trylock(&cache_lock) != 0) return;
  struct slot *e = &cache[h % SLOTS];
  e->hash = h;
  snprintf(e->path, sizeof e->path, "%s", dir);
  e->stamp = now_ns();
  pthread_mutex_unlock(&cache_lock);
}

/* The host directory `dir` is reached by the kernel without crossing a symlink. */
static long canonical_dir(const char *dir) {
  unsigned h = hash_of(dir);
  if (cached(dir, h)) return 1;
  long fd = sc(SYS_openat, AT_FDCWD, (long)dir, O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0);
  if (fd < 0) return fd;
  char link[40], out[PATH_MAX];
  snprintf(link, sizeof link, "/proc/self/fd/%ld", fd);
  long n = sc(SYS_readlinkat, AT_FDCWD, (long)link, (long)out, sizeof out - 1, 0);
  sc(SYS_close, fd, 0, 0, 0, 0);
  if (n <= 0) return 0;
  out[n] = 0;
  if (strcmp(out, dir) != 0) return 0;
  remember(dir, h);
  return 1;
}

/* ---------------------------------------------------------------- guest -> host */

/* Guest paths never answered here: proot emulates /proc (self, exe, fd and cwd links). */
static int reserved(const char *g) {
  return under(g, "/proc", 5) || under(g, "/dev/fd", 7) || under(g, "/dev/stdin", 10)
      || under(g, "/dev/stdout", 11) || under(g, "/dev/stderr", 11);
}

/* The guest path of a host directory (the cwd, a dirfd): the longest host prefix wins. */
static int host_to_guest(const char *h, char *g, size_t size) {
  const struct bind *best = NULL;
  for (int i = 0; i < nbinds; i++)
    if (under(h, binds[i].host, binds[i].hlen) && (!best || binds[i].hlen > best->hlen)) best = &binds[i];
  if (under(h, root, rootlen) && (!best || rootlen >= best->hlen))
    return snprintf(g, size, "%s", h[rootlen] ? h + rootlen : "/") < (int)size ? 0 : -1;
  if (best) return snprintf(g, size, "%s%s", best->guest, h + best->hlen) < (int)size ? 0 : -1;
  return -1;
}

/*
 * The host path `path` (relative to dirfd, as the guest means it) names, whose parent directory
 * is verified; 0, or FP_SLOW when proot must decide.
 */
static long resolve(int dirfd, const char *path, char *host) {
  char guest[PATH_MAX], base[PATH_MAX];
  if (!path || !path[0] || !fp_ready() || withdrawn()) return FP_SLOW;
  size_t plen = strlen(path);
  if (path[plen - 1] == '/' && plen > 1) return FP_SLOW;
  if (path[0] == '/') {
    if (plen >= sizeof guest) return FP_SLOW;
    memcpy(guest, path, plen + 1);
  } else {
    char link[40], h[PATH_MAX];
    if (dirfd == AT_FDCWD) snprintf(link, sizeof link, "/proc/self/cwd");
    else snprintf(link, sizeof link, "/proc/self/fd/%d", dirfd);
    long n = sc(SYS_readlinkat, AT_FDCWD, (long)link, (long)h, sizeof h - 1, 0);
    if (n <= 0) return FP_SLOW;
    h[n] = 0;
    if (host_to_guest(h, base, sizeof base) < 0) return FP_SLOW;
    if (snprintf(guest, sizeof guest, "%s/%s", strcmp(base, "/") ? base : "", path) >= (int)sizeof guest) return FP_SLOW;
  }
  /* Normalise "//" and "/./"; ".." goes to proot (it is not lexical once symlinks are involved). */
  char norm[PATH_MAX];
  size_t o = 0;
  for (const char *c = guest; *c;) {
    while (*c == '/') c++;
    if (!*c) break;
    const char *e = strchrnul(c, '/');
    size_t len = e - c;
    if (len == 1 && c[0] == '.') { c = e; continue; }
    if (len == 2 && c[0] == '.' && c[1] == '.') return FP_SLOW;
    if (o + len + 2 >= sizeof norm) return FP_SLOW;
    norm[o++] = '/';
    memcpy(norm + o, c, len);
    o += len;
    c = e;
  }
  if (o == 0) norm[o++] = '/';
  norm[o] = 0;
  if (reserved(norm)) return FP_SLOW;
  struct bind *bd = NULL;
  for (int i = 0; i < nbinds; i++)
    if (under(norm, binds[i].guest, binds[i].glen) && (!bd || binds[i].glen > bd->glen)) bd = &binds[i];
  if (bd && !canon(bd)) return FP_SLOW;
  size_t base_len = bd ? bd->hlen : rootlen;
  int n = bd ? snprintf(host, PATH_MAX, "%s%s", bd->host, norm + bd->glen)
             : snprintf(host, PATH_MAX, "%s%s", root, strcmp(norm, "/") ? norm : "");
  if (n >= PATH_MAX) return FP_SLOW;
  char *slash = strrchr(host, '/');
  if (!slash || slash == host) return FP_SLOW;
  *slash = 0;
  long ok = canonical_dir(host);
  *slash = '/';
  if (ok == 1) return 0;
  if (ok != -ENOENT) return FP_SLOW;
  /* The parent is missing. The path is missing for proot too if the deepest directory that does
   * exist is canonical and the next component below it is not there at all - not even as a
   * symlink proot would follow. Only within the binding (or rootfs) the path was mapped into. */
  char probe[PATH_MAX];
  memcpy(probe, host, n + 1);
  for (char *cut = strrchr(probe, '/'); cut && (size_t)(cut - probe) > base_len; ) {
    *cut = 0;
    char *up = strrchr(probe, '/');
    if (!up || (size_t)(up - probe) < base_len) break;
    *up = 0;
    long c = canonical_dir(probe[0] ? probe : "/");
    *up = '/';
    if (c == 1) {
      struct stat st;
      if (sc(SYS_newfstatat, AT_FDCWD, (long)probe, (long)&st, AT_SYMLINK_NOFOLLOW, 0) == -ENOENT) return -ENOENT;
      return FP_SLOW;
    }
    if (c != -ENOENT) return FP_SLOW;
    cut = up;
  }
  return FP_SLOW;
}

/* The last component, not followed: 0 something that is not a symlink, 1 a symlink, <0 -errno. */
static long kind(const char *host, struct stat *st) {
  long r = sc(SYS_newfstatat, AT_FDCWD, (long)host, (long)st, AT_SYMLINK_NOFOLLOW, 0);
  if (r < 0) return r;
  return S_ISLNK(st->st_mode) ? 1 : 0;
}

static int ret(long r) {
  if (r < 0) { errno = (int)-r; return -1; }
  return (int)r;
}

#define REAL(name, type) \
  static __typeof__(type) real_##name; \
  if (!real_##name) real_##name = (type)dlsym(RTLD_NEXT, #name)

/* ---------------------------------------------------------------- open */

static long fp_open(int dirfd, const char *path, int flags, mode_t mode) {
  char host[PATH_MAX];
  if ((flags & __O_TMPFILE) == __O_TMPFILE) return FP_SLOW;
  long rs = resolve(dirfd, path, host);
  if (rs != 0) return rs;
  /* The last component is never followed here: a symlink there is proot's to resolve. */
  long fd = sc(SYS_openat, AT_FDCWD, (long)host, flags | O_NOFOLLOW, (flags & O_CREAT) ? mode : 0, 0);
  if (fd == -ELOOP && !(flags & O_NOFOLLOW)) return FP_SLOW;
  if (fd >= 0 && (flags & O_PATH) && !(flags & O_NOFOLLOW)) {
    struct stat st;
    if (sc(SYS_fstat, fd, (long)&st, 0, 0, 0) != 0 || S_ISLNK(st.st_mode)) {
      sc(SYS_close, fd, 0, 0, 0, 0);
      return FP_SLOW;
    }
  }
  return fd;
}

static int do_openat(int dirfd, const char *path, int flags, mode_t mode, int (*real)(int, const char *, int, ...)) {
  long r = fp_open(dirfd, path, flags, mode);
  if (r != FP_SLOW) { hits++; return ret(r); }
  misses++;
  return real(dirfd, path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
  REAL(openat, int (*)(int, const char *, int, ...));
  mode_t mode = 0;
  if (flags & (O_CREAT | __O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
  return do_openat(dirfd, path, flags, mode, real_openat);
}
int openat64(int dirfd, const char *path, int flags, ...) __attribute__((alias("openat")));

int open(const char *path, int flags, ...) {
  REAL(openat, int (*)(int, const char *, int, ...));
  mode_t mode = 0;
  if (flags & (O_CREAT | __O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
  return do_openat(AT_FDCWD, path, flags, mode, real_openat);
}
int open64(const char *path, int flags, ...) __attribute__((alias("open")));
int __open_2(const char *path, int flags) { return open(path, flags); }
int __open64_2(const char *path, int flags) { return open(path, flags); }
int __openat_2(int d, const char *path, int flags) { return openat(d, path, flags); }
int __openat64_2(int d, const char *path, int flags) { return openat(d, path, flags); }

/* ---------------------------------------------------------------- stat family */

static long fp_stat(int dirfd, const char *path, int flags, struct stat *st) {
  char host[PATH_MAX];
  if (flags & ~AT_SYMLINK_NOFOLLOW) return FP_SLOW;
  long rs = resolve(dirfd, path, host);
  if (rs != 0) return rs;
  long k = kind(host, st);
  if (k == 1 && !(flags & AT_SYMLINK_NOFOLLOW)) return FP_SLOW;
  return k < 0 ? k : 0;
}

static int stat_common(int dirfd, const char *path, struct stat *st, int flags,
                       int (*real)(int, const char *, struct stat *, int)) {
  long r = fp_stat(dirfd, path, flags, st);
  if (r != FP_SLOW) { hits++; return ret(r); }
  misses++;
  return real(dirfd, path, st, flags);
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
  REAL(fstatat, int (*)(int, const char *, struct stat *, int));
  return stat_common(dirfd, path, st, flags, real_fstatat);
}
int fstatat64(int d, const char *p, struct stat64 *st, int f) { return fstatat(d, p, (struct stat *)st, f); }
int stat(const char *path, struct stat *st) {
  REAL(fstatat, int (*)(int, const char *, struct stat *, int));
  return stat_common(AT_FDCWD, path, st, 0, real_fstatat);
}
int stat64(const char *p, struct stat64 *st) { return stat(p, (struct stat *)st); }
int lstat(const char *path, struct stat *st) {
  REAL(fstatat, int (*)(int, const char *, struct stat *, int));
  return stat_common(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW, real_fstatat);
}
int lstat64(const char *p, struct stat64 *st) { return lstat(p, (struct stat *)st); }

int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *out) {
  REAL(statx, int (*)(int, const char *, int, unsigned, struct statx *));
  char host[PATH_MAX];
  long rs = (flags & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_STATX_SYNC_TYPE)) ? FP_SLOW : resolve(dirfd, path, host);
  if (rs == -ENOENT) { hits++; return ret(rs); }
  if (rs == 0) {
    long r = sc(SYS_statx, AT_FDCWD, (long)host, flags | AT_SYMLINK_NOFOLLOW, mask | STATX_TYPE, (long)out);
    if (!(r == 0 && S_ISLNK(out->stx_mode) && !(flags & AT_SYMLINK_NOFOLLOW))) { hits++; return ret(r); }
  }
  misses++;
  return real_statx(dirfd, path, flags, mask, out);
}

/* ---------------------------------------------------------------- access */

static int access_common(int dirfd, const char *path, int mode, int flags) {
  REAL(faccessat, int (*)(int, const char *, int, int));
  char host[PATH_MAX];
  struct stat st;
  /* faccessat(2) has no flags: AT_EACCESS and AT_SYMLINK_NOFOLLOW stay with glibc and proot. */
  long rs = flags == 0 ? resolve(dirfd, path, host) : FP_SLOW;
  if (rs == -ENOENT) { hits++; return ret(rs); }
  if (rs == 0) {
    long k = kind(host, &st);
    if (k != 1) {
      hits++;
      if (k < 0) return ret(k);
      return ret(sc(SYS_faccessat, AT_FDCWD, (long)host, mode, 0, 0));
    }
  }
  misses++;
  return real_faccessat(dirfd, path, mode, flags);
}
int faccessat(int dirfd, const char *path, int mode, int flags) { return access_common(dirfd, path, mode, flags); }
int access(const char *path, int mode) { return access_common(AT_FDCWD, path, mode, 0); }

/* ---------------------------------------------------------------- readlink */

/* The link's own text needs no translation outside /proc: proot stores and returns it as is. */
static ssize_t readlink_common(int dirfd, const char *path, char *buf, size_t size) {
  REAL(readlinkat, ssize_t (*)(int, const char *, char *, size_t));
  char host[PATH_MAX];
  long rs = resolve(dirfd, path, host);
  if (rs == -ENOENT) { hits++; return ret(rs); }
  if (rs == 0) {
    hits++;
    long r = sc(SYS_readlinkat, AT_FDCWD, (long)host, (long)buf, size, 0);
    return r < 0 ? ret(r) : r;
  }
  misses++;
  return real_readlinkat(dirfd, path, buf, size);
}
ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) { return readlink_common(dirfd, path, buf, size); }
ssize_t readlink(const char *path, char *buf, size_t size) { return readlink_common(AT_FDCWD, path, buf, size); }

/* ---------------------------------------------------------------- libc calls that open internally */

DIR *opendir(const char *path) {
  REAL(opendir, DIR *(*)(const char *));
  long fd = fp_open(AT_FDCWD, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NONBLOCK, 0);
  if (fd == FP_SLOW) { misses++; return real_opendir(path); }
  hits++;
  if (fd < 0) { errno = (int)-fd; return NULL; }
  DIR *d = fdopendir((int)fd);
  if (!d) close((int)fd);
  return d;
}

static FILE *fopen_common(const char *path, const char *m, FILE *(*real)(const char *, const char *)) {
  int flags;
  switch (m[0]) {
  case 'r': flags = strchr(m, '+') ? O_RDWR : O_RDONLY; break;
  case 'w': flags = (strchr(m, '+') ? O_RDWR : O_WRONLY) | O_CREAT | O_TRUNC; break;
  case 'a': flags = (strchr(m, '+') ? O_RDWR : O_WRONLY) | O_CREAT | O_APPEND; break;
  default: return real(path, m);
  }
  if (strchr(m, 'e')) flags |= O_CLOEXEC;
  if (strchr(m, 'x')) flags |= O_EXCL;
  long fd = fp_open(AT_FDCWD, path, flags, 0666);
  if (fd == FP_SLOW) { misses++; return real(path, m); }
  hits++;
  if (fd < 0) { errno = (int)-fd; return NULL; }
  FILE *f = fdopen((int)fd, m);
  if (!f) close((int)fd);
  return f;
}
FILE *fopen(const char *path, const char *m) { REAL(fopen, FILE *(*)(const char *, const char *)); return fopen_common(path, m, real_fopen); }
FILE *fopen64(const char *path, const char *m) { REAL(fopen64, FILE *(*)(const char *, const char *)); return fopen_common(path, m, real_fopen64); }
