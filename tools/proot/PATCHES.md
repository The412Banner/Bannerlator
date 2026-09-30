# proot patches carried by the app

Applied by `.github/workflows/build-proot.yml`, in order, on top of termux/proot `v5.1.107.92` -
the exact source the Termux binary in `tools/linuxfs/prebuilt/proot` was built from, and the one
tree of this fork known to hold a session on our devices (see that directory's README for why the
in-tree `app/src/main/cpp/proot` is not used). A patch that does not apply fails the build. The
result is the same self-contained `libproot.so` + `libproot-loader.so` pair as before; nothing in
the app changes, and `PROOT_NO_SECCOMP` ("proot without seccomp") is untouched: it stops proot
installing its filter, and everything below that touches seccomp sits behind that filter.

Ported from The412Banner/DroidDeck (`tools/proot/patches`, PRs #4 and #37), which carries them from
WinNative (maxjivi05: `feature/proot-enhancements` e7af0f24, then `main` 53836ca9). DroidDeck
builds them on `4dba3afb` (Termux's 5.1.107-70 package); ours is 65 commits later, so two were
re-taken on our tree - said in the patch header - and the other seven are byte-identical to
DroidDeck's.

- `0001-tracee-lookup-by-pid.patch` - tracees are hashed by pid, so each ptrace stop finds its
  tracee without walking one list entry per thread, and terminated tracees are swept only after a
  termination.
- `0002-canon-resolve-parent-at-once.patch` - a clean absolute guest path at least three
  directories deep under the rootfs binding alone has its parent opened once with `O_PATH` and
  taken as canonical when `/proc/self/fd` names the path proot would build, replacing an `lstat`
  per component. The final component of a call that does not follow it is no longer `lstat`ed.
  Extensions still see the parent's host path, and the fast path stays off while the f2fs
  workaround is active.
- `0003-clone3-flags-read-guard.patch` - a thread created with `clone3` keeps the flags it
  inherited when `struct clone_args` cannot be read, instead of being tracked as a fork.
- `0004-seccomp-filter-by-argument.patch` - the seccomp filter traces `prctl` only for
  `PR_SET_DUMPABLE`, `setrlimit` only for `RLIMIT_STACK` and `prlimit64` only when it sets a new
  `RLIMIT_STACK`, the only cases proot acts on; every other call runs without a stop. `uname` is
  traced by the core only on x86_64, the one arch it rewrites (`exit.c` guards it the same way on
  our tree); kompat still adds it for `--kernel-release`. **Adapted:** the `uname` hunk's context
  differs on v5.1.107.92 (`umount`/`umount2` are `FILTER_SYSEXIT` there, `unshare`/`setns`
  follow); the change itself is DroidDeck's.
- `0005-seccomp-skip-unneeded-sysexit.patch` - a seccomp stop fetches the registers once and
  looks the filter flags up by syscall number, instead of a `PTRACE_GETEVENTMSG` and a second
  register fetch. The flags table is built from proot's list merged with the enabled extensions'
  (fake_id0 for `-i`, kompat for `-k`), so it is exactly what the filter reports. `wait4`/`waitpid`
  left to the kernel and `accept`/`accept4` without a sockaddr skip their exit stop, unless an
  extension replaced the syscall (kompat turning `accept4` into `accept`).
- `0006-clone3-exit-signal.patch` - `clone3` flags and exit signal are read as the 64-bit fields
  of `struct clone_args`, so a `clone3` fork is told apart from a thread when proot decides how the
  child is traced.
- `0007-proc-self-thread-group.patch` - tracees track their thread group, `/proc/self` names it
  instead of the calling thread, and `/proc/thread-self` resolves to `/proc/<tgid>/task/<tid>`.
- `0008-fchmodat2-openat2.patch` - `fchmodat2` paths are translated (honouring
  `AT_SYMLINK_NOFOLLOW`), and syscall numbers past the end of a table are rejected instead of
  read. **Adapted:** DroidDeck's base has no `openat2`, so its patch answers it `ENOSYS` to make
  callers fall back to `openat`; v5.1.107.92 already rewrites `openat2` into `openat` with the
  `open_how` flags (termux/proot `114a7c69`, "tar on modern distros"), which is the better answer,
  so the `openat2` sysnum, filter entry and `ENOSYS` case are left out here and upstream's kept.
- `0009-tracee-relatives-sweep.patch` - tracees count their children, so a terminating thread
  with no children or ptracees no longer walks every tracee; the per-stop memory collector is
  emptied instead of freed and reallocated.

Ported from Droid-Deck/DroidDeck (org PRs #81 and #84), numbered as there (DroidDeck has no
0010). They are DroidDeck's own, measured there on an x86_64 host build and an SD 8 Gen 2 guest;
0011 is byte-identical to DroidDeck's, 0012 and 0013 were re-taken on our tree, said in the patch
header.

- `0011-kompat-utsname-only.patch` - `--kernel-release` loads kompat, whose filter traps `futex`,
  `fcntl`, `epoll_pwait`, `pselect6`, `pipe2`, `eventfd2`, `socket` and more, and which strips
  `AT_SYSINFO_EHDR` on every `execve`, so glibc runs without the vDSO. When the virtual release is
  not older than the real kernel and the hwcap is left alone, every one of those handlers is a
  no-op: kompat then traces only `uname`, `sethostname` and `setdomainname` and leaves the auxv as
  the kernel wrote it. The app passes no `-k` today, so this changes nothing until it does; it is
  carried to keep the set in step with DroidDeck.
- `0012-fake_id0-identity-only.patch` - `-i uid:gid` (the app passes its own uid for Xwayland's
  setgid/setuid before it runs xkbcomp) loads fake_id0, whose filter traps every
  `fstat`/`newfstatat`/`stat`, every `sendmsg` (all Wayland, X11, Chromium and PulseAudio traffic),
  `socket`, `getsockopt`, the `get*id` family and the chown/chmod family. When the ids given are
  the ones proot really has and are not 0, every one of those handlers is a no-op; fake_id0 then
  traces only the `set*id` family and the xattr permission fixups, and leaves set-user-ID bits
  alone on `execve` (Android mounts the app's data `nosuid`, so the kernel would not honour them
  either). DroidDeck measured `fstat` 41.7 -> 1.3 us and `sendmsg` 18.0 -> 2.5 us. **Adapted:**
  our fake_id0 `Config` already ends with `caps_active`/`keep_caps` (so the new field goes after
  them), and its list also traces `prctl`, which only mirrors `PR_SET_KEEPCAPS` for `caps_active`;
  that is never set in this mode, so `prctl` stays out of the short list.
- `0013-seccomp-ioctl-by-request-and-kernel-exit-stops.patch` - every `ioctl` stopped on entry and
  exit, which is every GPU submit and wait. The filter now traces only the requests enter.c and
  exit.c act on and allows the rest at once, with the ioctl block emitted first. The `faccessat2`
  exit stop (glibc's ENOSYS fallback on kernels before 5.8) and the `statx` one (emulation on
  kernels before 4.11) are dropped when the running kernel is newer. DroidDeck measured `ioctl`
  31.6 -> 0.5 us and `stat`/`statx`/`faccessat2` about 44 -> 28 us. **Adapted:** our enter.c also
  answers `SIOCGIFINDEX` itself (Android denies it; bubblewrap's loopback setup needs it), so that
  request is traced too, next to `TCSETSF`, the four termios2 requests and `FICLONE`. And since the
  `statx` exit stop is also where fake_id0 (as root) and link2symlink get `STATX_SYSCALL`, both now
  list `statx` themselves, so dropping it from the core list does not drop it for them.

## Checking a change to the set

The workflow dry-runs then applies each patch with GNU `patch -p1 -F0 --forward`, in order, into
the unzipped `proot-5.1.107.92` (not `git apply`: run inside a checkout it takes the paths as
relative to the checkout and "applies" nothing). To do the same by hand:

    curl -fsSLo proot.zip https://github.com/termux/proot/archive/v5.1.107.92.zip && unzip -q proot.zip
    for p in tools/proot/patches/*.patch; do patch -p1 -F0 --forward -d proot-5.1.107.92 < "$p" || break; done

`filtered_sysnum_flags` (0005) is the set's one global symbol: `llvm-nm libproot.so | grep
filtered_sysnum_flags` says whether a given binary carries it (Termux's shipped `proot` does not).
