/* Cost of the syscall-interception mechanisms available to an Android app, measured on the
 * trapped call getppid() (the tracer/handler does no work, so this is pure mechanism cost).
 *
 *   mechbench filter <n_linear>        allowed syscall through a filter of n JEQs (linear, proot-style)
 *   mechbench tree                     allowed syscall through a binary-search filter (~150 entries)
 *   mechbench ptrace [spin] [regs]     seccomp RET_TRACE + ptrace tracer (proot's mechanism)
 *   mechbench sigsys                   seccomp RET_TRAP + in-process SIGSYS handler
 *   mechbench notif [sync] [spin]      seccomp RET_USER_NOTIF + supervisor thread
 *   mechbench openat2 <root> <path>    openat2(RESOLVE_IN_ROOT) vs openat on the host path
 * Env CPU_T / CPU_S pin the trapped thread / the tracer-supervisor to a cpu.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/openat2.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <linux/elf.h>

#ifndef SECCOMP_USER_NOTIF_FD_SYNC_WAKE_UP
#define SECCOMP_USER_NOTIF_FD_SYNC_WAKE_UP (1UL << 0)
#endif
#ifndef SECCOMP_IOCTL_NOTIF_SET_FLAGS
#define SECCOMP_IOCTL_NOTIF_SET_FLAGS SECCOMP_IOW(4, __u64)
#endif

#define TRAPPED SYS_getppid
static int N = 100000;

static double now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e9 + t.tv_nsec;
}

static void pin(const char *env) {
  const char *v = getenv(env);
  if (!v) return;
  cpu_set_t s;
  CPU_ZERO(&s);
  CPU_SET(atoi(v), &s);
  sched_setaffinity(0, sizeof s, &s);
}

static double time_trapped(void) {
  for (int i = 0; i < N / 10; i++) syscall(TRAPPED);
  double t0 = now();
  for (int i = 0; i < N; i++) syscall(TRAPPED);
  return (now() - t0) / N;
}

/* A filter: arch check, then `n` JEQs on numbers that never match (like proot's list of ~150
 * traced syscalls seen by an allowed syscall), then `action` for TRAPPED, else ALLOW. */
static int install(int n_linear, uint32_t action, unsigned flags) {
  struct sock_filter *f = calloc(n_linear + 16, sizeof *f);
  int k = 0;
  f[k++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
  f[k++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
  for (int i = 0; i < n_linear; i++)  /* numbers far above any real syscall */
    f[k++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 5000 + i, 0, 0);
  f[k++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, TRAPPED, 0, 1);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, action);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  struct sock_fprog p = {.len = k, .filter = f};
  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  return syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, flags, &p);
}

/* ~150 traced numbers as a balanced binary search (what libseccomp's optimize=2 emits). */
static int emit_tree(struct sock_filter *f, int *k, int lo, int hi) {
  if (lo > hi) { f[(*k)++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW); return 0; }
  int mid = (lo + hi) / 2;
  f[(*k)++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 5000 + mid, 0, 1);
  f[(*k)++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE);
  f[(*k)++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGT | BPF_K, 5000 + mid, 0, 1);
  int ja = (*k)++;
  int left = *k;
  emit_tree(f, k, lo, mid - 1);
  f[ja] = (struct sock_filter)BPF_STMT(BPF_JMP | BPF_JA, *k - left);
  emit_tree(f, k, mid + 1, hi);
  return 0;
}

static void *sigsys_unused;
static void on_sigsys(int sig, siginfo_t *si, void *ctx) {
  (void)sig; (void)si;
  ucontext_t *uc = ctx;
  uc->uc_mcontext.regs[0] = 4242; /* the emulated result */
}

struct notif_arg { int fd; int spin; };
static void *supervisor(void *a) {
  struct notif_arg *na = a;
  pin("CPU_S");
  struct seccomp_notif *req = calloc(1, 256);
  struct seccomp_notif_resp *resp = calloc(1, 256);
  for (;;) {
    memset(req, 0, sizeof *req);
    if (ioctl(na->fd, SECCOMP_IOCTL_NOTIF_RECV, req) < 0) {
      if (errno == EINTR) continue;
      return NULL;
    }
    resp->id = req->id;
    resp->val = 4242;
    resp->error = 0;
    resp->flags = 0;
    ioctl(na->fd, SECCOMP_IOCTL_NOTIF_SEND, resp);
  }
}

static int notif_fd;
static int notif_flags_sync;
static void *notified_worker(void *a) {
  (void)a;
  pin("CPU_T");
  notif_fd = install(0, SECCOMP_RET_USER_NOTIF, SECCOMP_FILTER_FLAG_NEW_LISTENER);
  return NULL;
}

int main(int argc, char **argv) {
  if (getenv("N")) N = atoi(getenv("N"));
  const char *mode = argc > 1 ? argv[1] : "filter";
  if (!strcmp(mode, "filter")) {
    pin("CPU_T");
    printf("no filter        getppid %6.0f ns\n", time_trapped());
    int n = argc > 2 ? atoi(argv[2]) : 150;
    if (install(n, SECCOMP_RET_ALLOW, 0) < 0) { perror("seccomp"); return 1; }
    printf("linear %4d JEQs getppid %6.0f ns\n", n, time_trapped());
    return 0;
  }
  if (!strcmp(mode, "tree")) {
    pin("CPU_T");
    printf("no filter        getppid %6.0f ns\n", time_trapped());
    struct sock_filter f[2048];
    int k = 0;
    f[k++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    emit_tree(f, &k, 0, 149);
    struct sock_fprog p = {.len = k, .filter = f};
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p) < 0) { perror("seccomp"); return 1; }
    printf("tree of 150 (%d insns) getppid %6.0f ns\n", k, time_trapped());
    return 0;
  }
  if (!strcmp(mode, "sigsys")) {
    pin("CPU_T");
    struct sigaction sa = {0};
    sa.sa_sigaction = on_sigsys;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSYS, &sa, NULL);
    if (install(0, SECCOMP_RET_TRAP, 0) < 0) { perror("seccomp"); return 1; }
    long r = syscall(TRAPPED);
    printf("sigsys trap (result %ld) %6.0f ns\n", r, time_trapped());
    (void)sigsys_unused;
    return 0;
  }
  if (!strcmp(mode, "notif")) {
    int sync = argc > 2 && strstr(argv[2], "sync");
    pthread_t w, s;
    pthread_create(&w, NULL, notified_worker, NULL);
    pthread_join(w, NULL);
    if (notif_fd < 0) { perror("NEW_LISTENER"); return 1; }
    if (sync) {
      if (ioctl(notif_fd, SECCOMP_IOCTL_NOTIF_SET_FLAGS, SECCOMP_USER_NOTIF_FD_SYNC_WAKE_UP) < 0) perror("SYNC_WAKE_UP");
      else printf("(sync wake-up on)\n");
    }
    /* The filter lives on thread w, which has exited; install again on a fresh worker that
     * does the timing. */
    struct notif_arg na = {.fd = -1};
    (void)na;
    printf("note: user-notif is measured in 'notif2'\n");
    return 0;
  }
  if (!strcmp(mode, "notif2")) {
    /* Supervisor = child thread (unfiltered), timed thread = main after installing. */
    int sync = argc > 2 && !strcmp(argv[2], "sync");
    pin("CPU_T");
    int fd = install(0, SECCOMP_RET_USER_NOTIF, SECCOMP_FILTER_FLAG_NEW_LISTENER);
    if (fd < 0) { perror("NEW_LISTENER"); return 1; }
    if (sync && ioctl(fd, SECCOMP_IOCTL_NOTIF_SET_FLAGS, SECCOMP_USER_NOTIF_FD_SYNC_WAKE_UP, 0) < 0) perror("SYNC_WAKE_UP");
    /* The supervisor thread is created AFTER the filter, so it inherits it - but it only
     * makes ioctl calls, which the filter allows. */
    static struct notif_arg na;
    na.fd = fd;
    pthread_t s;
    pthread_create(&s, NULL, supervisor, &na);
    long r = syscall(TRAPPED);
    printf("user_notif%s (result %ld) %6.0f ns\n", sync ? "+sync" : "", r, time_trapped());
    return 0;
  }
  if (!strcmp(mode, "ptrace")) {
    int spin = 0, regs = 0;
    for (int i = 2; i < argc; i++) { if (!strcmp(argv[i], "spin")) spin = 1; if (!strcmp(argv[i], "regs")) regs = 1; }
    pid_t c = fork();
    if (c == 0) {
      pin("CPU_T");
      ptrace(PTRACE_TRACEME, 0, 0, 0);
      raise(SIGSTOP);
      if (install(0, SECCOMP_RET_TRACE, 0) < 0) { perror("seccomp"); _exit(1); }
      printf("ptrace+seccomp%s%s %6.0f ns\n", spin ? " spin" : "", regs ? " +getregs+setregs" : "", time_trapped());
      fflush(stdout);
      _exit(0);
    }
    pin("CPU_S");
    int st;
    waitpid(c, &st, 0);
    ptrace(PTRACE_SETOPTIONS, c, 0, PTRACE_O_TRACESECCOMP | PTRACE_O_EXITKILL);
    ptrace(PTRACE_CONT, c, 0, 0);
    for (;;) {
      pid_t p;
      if (spin) { do p = waitpid(c, &st, WNOHANG | __WALL); while (p == 0); }
      else p = waitpid(c, &st, __WALL);
      if (p < 0 || WIFEXITED(st) || WIFSIGNALED(st)) break;
      int sig = 0;
      if ((st >> 8) == (SIGTRAP | (PTRACE_EVENT_SECCOMP << 8))) {
        if (regs) {
          struct user_regs { uint64_t r[31], sp, pc, pstate; } ur;
          struct iovec io = {&ur, sizeof ur};
          ptrace(PTRACE_GETREGSET, c, NT_PRSTATUS, &io);
          ptrace(PTRACE_SETREGSET, c, NT_PRSTATUS, &io);
        }
      } else if (WIFSTOPPED(st) && WSTOPSIG(st) != SIGTRAP) sig = WSTOPSIG(st);
      ptrace(PTRACE_CONT, c, 0, sig);
    }
    return 0;
  }
  if (!strcmp(mode, "openat2")) {
    const char *root = argv[2], *path = argv[3];
    char host[4096];
    snprintf(host, sizeof host, "%s%s", root, path);
    int rfd = open(root, O_PATH | O_DIRECTORY);
    struct open_how how = {.flags = O_RDONLY | O_CLOEXEC, .resolve = RESOLVE_IN_ROOT};
    long fd = syscall(SYS_openat2, rfd, path, &how, sizeof how);
    if (fd < 0) { perror("openat2 RESOLVE_IN_ROOT"); return 1; }
    close(fd);
    double t0 = now();
    for (int i = 0; i < N; i++) close(open(host, O_RDONLY | O_CLOEXEC));
    printf("openat host path        %6.0f ns\n", (now() - t0) / N);
    t0 = now();
    for (int i = 0; i < N; i++) close(syscall(SYS_openat2, rfd, path, &how, sizeof how));
    printf("openat2 RESOLVE_IN_ROOT %6.0f ns\n", (now() - t0) / N);
    struct stat st;
    t0 = now();
    for (int i = 0; i < N; i++) stat(host, &st);
    printf("stat host path          %6.0f ns\n", (now() - t0) / N);
    struct open_how ph = {.flags = O_PATH | O_CLOEXEC, .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS};
    t0 = now();
    for (int i = 0; i < N; i++) { int f = syscall(SYS_openat2, rfd, path, &ph, sizeof ph); fstat(f, &st); close(f); }
    printf("openat2(O_PATH)+fstat+close %6.0f ns\n", (now() - t0) / N);
    return 0;
  }
  fprintf(stderr, "unknown mode\n");
  return 2;
}
