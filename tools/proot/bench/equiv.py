import os, sys
paths = ["/etc/os-release", "/root/abs-link", "/root/to-bind", "/root/to-bind/inbind.txt", "/root/rel-to-bind",
         "/root/rel-to-bind/inbind.txt", "/root/dotdot", "/root/dotdot/libc.so.6", "/root/dangling", "/root/loop",
         "/usr/lib/../lib/libc.so.6", "/usr//lib/./libc.so.6", "/lib", "/lib/libc.so.6", "/bin/env", "/nope", "/nope/x",
         "/etc/os-release/x", "/mnt/droiddeck-sd", "/mnt/droiddeck-sd/inbind.txt", "/mnt/droiddeck-sd/../bannerlator",
         "/dev/null", "/dev/shm", "/dev/shm/inbind.txt", "/dev/random", "/proc/self/exe", "/proc/version", "/proc",
         "/sys/class/thermal/thermal_zone28/temp", "/sdcard", "/", "/tmp", "/root/in-root-abs-dir/os-release",
         "/data/local/tmp/dd/tmp/inbind.txt", "/data/local/tmp/dd/mini/etc/os-release", "/root/to-bind/missing/x", "/root/to-bind/inbind.txt/x", "/usr/lib/nope/deeper/x.so", "/mnt/droiddeck-sd/nope/x", "/root/dangling/x", "/root/abs-link/x", "/nope/a/b/c", "/sdcard/nope/x"]
rels = ["os-release", "inbind.txt", "../etc/os-release", "abs-link", "to-bind/inbind.txt", "rel-to-bind",
        "shm/inbind.txt", "null", "lib/libc.so.6", "mnt/droiddeck-sd/inbind.txt", "proc/version", "nope", "."]
def probe(p):
    out = []
    for name, fn in [("stat", lambda: (lambda s: (oct(s.st_mode), s.st_size if not os.path.isdir(p) else 0))(os.stat(p))),
                     ("lstat", lambda: oct(os.lstat(p).st_mode)),
                     ("readlink", lambda: os.readlink(p)),
                     ("read", lambda: open(p, "rb").read(12)),
                     ("list", lambda: sorted(x for x in os.listdir(p) if not x.startswith("proot-"))[:6]),
                     ("access", lambda: (os.access(p, os.R_OK), os.access(p, os.W_OK), os.access(p, os.X_OK)))]:
        try: out.append(f"{name}={fn()!r}")
        except OSError as e: out.append(f"{name}=E{e.errno}")
    return " ".join(out)
for p in paths: print(p, probe(p))
for cwd in ["/", "/root", "/etc", "/mnt/droiddeck-sd", "/dev", "/usr/lib", "/data/local/tmp/dd/tmp", "/root/to-bind", "/root/dotdot"]:
    try: os.chdir(cwd)
    except OSError as e: print("chdir", cwd, e.errno); continue
    print("== cwd", cwd, os.getcwd())
    for r in rels: print("  ", r, probe(r))
    d = os.open(".", os.O_RDONLY)
    for r in rels[:6]:
        try: print("   at", r, oct(os.stat(r, dir_fd=d).st_mode))
        except OSError as e: print("   at", r, "E", e.errno)
    os.close(d)
