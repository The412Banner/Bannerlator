/* Per-syscall latency under whatever runs this program (native, proot, ...).
 * Usage: sysbench <deep-file> [iterations]
 * Prints "name ns/op" lines. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e9 + t.tv_nsec;
}

#define BENCH(name, n, body)                                   \
  do {                                                         \
    for (int _w = 0; _w < (n) / 10 + 1; _w++) { body; }        \
    double _t0 = now();                                        \
    for (int _i = 0; _i < (n); _i++) { body; }                 \
    printf("%-28s %10.0f ns\n", name, (now() - _t0) / (n));    \
    fflush(stdout);                                            \
  } while (0)

static void *thread_fn(void *a) { return a; }

int main(int argc, char **argv) {
  const char *deep = argc > 1 ? argv[1] : "/usr/lib/python3.14/os.py";
  int n = argc > 2 ? atoi(argv[2]) : 20000;
  struct stat st;
  char buf[4096];
  int pfd[2];
  pipe(pfd);
  int fd = open(deep, O_RDONLY);
  if (fd < 0) { perror(deep); return 1; }
  int futexword = 0;
  char *slash = strrchr(deep, '/');
  char dir[1024];
  snprintf(dir, sizeof dir, "%.*s", (int)(slash - deep), deep);
  int dfd = open(dir, O_RDONLY | O_DIRECTORY);

  BENCH("getppid (untraced)", n, syscall(SYS_getppid));
  BENCH("futex wake (untraced)", n, syscall(SYS_futex, &futexword, FUTEX_WAKE_PRIVATE, 1, 0, 0, 0));
  BENCH("clock_gettime (vdso)", n, { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); });
  BENCH("fstat fd (untraced)", n, fstat(fd, &st));
  BENCH("pread 4k (untraced)", n, pread(fd, buf, sizeof buf, 0));
  BENCH("ioctl FIONREAD (untraced*)", n, { int v; ioctl(pfd[0], FIONREAD, &v); });
  BENCH("fstatat relative dirfd", n, fstatat(dfd, slash + 1, &st, 0));
  BENCH("stat deep abs path", n, stat(deep, &st));
  BENCH("lstat deep abs path", n, lstat(deep, &st));
  BENCH("statx deep abs path", n, { struct statx sx; statx(AT_FDCWD, deep, 0, STATX_BASIC_STATS, &sx); });
  BENCH("access deep abs path", n, access(deep, R_OK));
  BENCH("open+close deep abs", n, close(open(deep, O_RDONLY)));
  BENCH("stat ENOENT", n, stat("/usr/lib/does/not/exist.so", &st));
  BENCH("readlink /proc/self/exe", n, readlink("/proc/self/exe", buf, sizeof buf));
  BENCH("getcwd", n, getcwd(buf, sizeof buf));
  BENCH("uname", n, { struct utsname_dummy { char b[65 * 6]; } u; syscall(SYS_uname, &u); });
  BENCH("memfd_create+close", n / 4, close(memfd_create("x", MFD_CLOEXEC)));
  BENCH("brk(0) query", n, syscall(SYS_brk, 0));
  BENCH("mmap+munmap 64k", n, munmap(mmap(0, 65536, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0), 65536));
  BENCH("pthread create+join", n / 20, { pthread_t t; pthread_create(&t, 0, thread_fn, 0); pthread_join(t, 0); });
  BENCH("fork+_exit+wait", n / 100, { pid_t p = fork(); if (!p) _exit(0); waitpid(p, 0, 0); });
  if (argc > 3) {
    char *const av[] = {argv[3], NULL};
    BENCH("fork+execve true+wait", n / 200, { pid_t p = fork(); if (!p) { execv(argv[3], av); _exit(127); } waitpid(p, 0, 0); });
  }
  return 0;
}
