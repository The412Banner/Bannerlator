/*
 * Ported from WinNative (maxjivi05, feature/wayland-gamescope, 2026-09-18/19), GPL-3.0, with names
 * changed to this project's. The reasoning below is the original author's.
 *
 * A process status without the container's tracer in it.
 *
 * proot runs the session under ptrace, so every process here reads a TracerPid in its
 * /proc/<pid>/status. The Steam client takes that to mean a debugger is attached, and its arm64
 * build breaks into the supposed debugger with a bare "svc #0" after every failed assertion. That
 * is no breakpoint: the registers still hold the call Plat_IsInDebugSession() just made, close(),
 * with its own result, 1, as the argument - so each assertion closes descriptor 1. The number then
 * goes to whatever the client opens next, and its standard output is written into that: app
 * manifests and content chunks, its own connections, and the socket a game's Steam API talks
 * over, which is how a game that stayed silent while loading found its pipe dead.
 *
 * The tracer is part of the container, not something a program should react to, so the status
 * files are served with TracerPid 0.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define STATUS_MAX 16384
#define TRACER_FIELD "\nTracerPid:\t"

static const char *skip_digits(const char *s) {
  const char *end = s;
  while (isdigit((unsigned char)*end)) end++;
  return end == s ? NULL : end;
}

/* /proc/{self,thread-self,<pid>}[/task/<tid>]/status */
static int is_status_path(const char *path) {
  const char *rest;
  if (path == NULL || strncmp(path, "/proc/", 6) != 0) return 0;
  path += 6;
  if (strncmp(path, "self/", 5) == 0) rest = path + 4;
  else if (strncmp(path, "thread-self/", 12) == 0) rest = path + 11;
  else if ((rest = skip_digits(path)) == NULL) return 0;
  if (strncmp(rest, "/task/", 6) == 0 && (rest = skip_digits(rest + 6)) == NULL) return 0;
  return strcmp(rest, "/status") == 0;
}

/* Chromium stands its hang watchdogs down when it sees a tracer, and the client's web helper does
 * not come up within their patience here: it is left reading the truth. Chromium also traps when a
 * status descriptor it opened turns out not to be procfs. The x86-64 client under FEX
 * (BL_WEBHELPER_HIDE_TRACER=1, set by bannerlator-steam-x64) still needs its tier0 told: tier0
 * reads the status, sees the tracer, and breaks into the supposed debugger with int3 on the first
 * failed assertion - the web helper died of SIGTRAP a second after it started. There tier0's own
 * opens (by caller) get the copy and Chromium's the real file. */
static int is_web_helper(const void *caller) {
  if (strcmp(program_invocation_short_name, "steamwebhelper") != 0) return 0;
  const char *hide = getenv("BL_WEBHELPER_HIDE_TRACER");
  Dl_info info;
  if (hide && hide[0] == '1' && caller && dladdr(caller, &info) && info.dli_fname
      && strstr(info.dli_fname, "libtier0_s.so"))
    return 0;
  return 1;
}

static int write_all(int fd, const char *data, size_t len) {
  while (len > 0) {
    ssize_t n = write(fd, data, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    data += n;
    len -= (size_t)n;
  }
  return 0;
}

/* The descriptor to hand back for a read-only open of a status file, or -1 with errno untouched
 * when the path is something else or the copy cannot be made, and the real open should run. */
__attribute__((visibility("hidden"))) int bl_status_without_tracer(const char *path, int flags, const void *caller) {
  char text[STATUS_MAX];
  size_t len = 0;
  int saved = errno;
  int out = -1;

  if ((flags & O_ACCMODE) != O_RDONLY || !is_status_path(path) || is_web_helper(caller)) return -1;
  int in = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
  if (in < 0) goto done;
  for (;;) {
    ssize_t n = read(in, text + len, sizeof(text) - 1 - len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    len += (size_t)n;
  }
  close(in);
  text[len] = '\0';

  char *field = strstr(text, TRACER_FIELD);
  if (field == NULL) goto done;
  char *value = field + strlen(TRACER_FIELD);
  char *line_end = strchr(value, '\n');
  if (line_end == NULL) goto done;

  out = memfd_create("status", (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
  if (out < 0) goto done;
  if (write_all(out, text, (size_t)(value - text)) != 0 || write_all(out, "0", 1) != 0
      || write_all(out, line_end, len - (size_t)(line_end - text)) != 0
      || lseek(out, 0, SEEK_SET) != 0) {
    close(out);
    out = -1;
  }
done:
  errno = saved;
  return out;
}
