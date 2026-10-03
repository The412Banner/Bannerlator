#!/system/bin/sh
# Runs a guest command on an adb-connected device the way a DroidDeck session runs proot (same
# options, a similar number of binds), as the shell user from /data/local/tmp/dd. Layout there:
#   mini/            a glibc rootfs subset (glibc, bash, coreutils, python) + the bench binaries
#   proot, loader    this repo's proot (tools/proot/build.sh)        -> variant "patched"
#   proot-vanilla    termux proot at source.env with no patches      -> variant "vanilla"
#   proot-fp         this repo's proot incl. 0014                    -> "fastpath" / "fpoff"
#   mini/usr/lib/libfastpath.so  tools/proot/fastpath/fastpath.c
# NOTE: adb shell is NOT under the app seccomp policy; see docs/development/proot-performance.md.
#
#   run-device.sh <patched|vanilla|noseccomp|bare|fastpath|fpoff|direct> cmd...
D=/data/local/tmp/dd
V=$1; shift
U=$(id -u)
REL="\\Linux\\DroidDeck\\$(uname -r)\\$(uname -v)\\aarch64\\localdomain\\-1\\"
BINDS="-b /dev -b /proc -b /sys -b /dev/urandom:/dev/random -b /proc/self/fd:/dev/fd -b /proc/self/fd/0:/dev/stdin -b /proc/self/fd/1:/dev/stdout -b /proc/self/fd/2:/dev/stderr -b $D/tmp:/dev/shm -b $D -b /sdcard -b /data/local/tmp/dd/mini/etc/os-release:/proc/version -b $D/mini/etc/os-release:/proc/loadavg -b $D/mini/etc/os-release:/proc/uptime -b /sys/class/thermal/thermal_zone0/temp:/sys/class/thermal/thermal_zone28/temp -b $D/tmp:/mnt/droiddeck-sd -b $D/tmp:/mnt/bannerlator"
export PROOT_TMP_DIR=$D/tmp
case $V in
  patched)   export PROOT_LOADER=$D/loader; exec $D/proot --kill-on-exit --kernel-release="$REL" -i $U:$U -r $D/mini -w /root $BINDS /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8 "$@" ;;
  noseccomp) export PROOT_LOADER=$D/loader PROOT_NO_SECCOMP=1; exec $D/proot --kill-on-exit --kernel-release="$REL" -i $U:$U -r $D/mini -w /root $BINDS /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8 "$@" ;;
  vanilla)   export PROOT_LOADER=$D/loader-vanilla; exec $D/proot-vanilla --kill-on-exit --kernel-release="$REL" -i $U:$U -r $D/mini -w /root $BINDS /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8 "$@" ;;
  bare)      export PROOT_LOADER=$D/loader; exec $D/proot --kill-on-exit -r $D/mini -w /root $BINDS /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8 "$@" ;;
  fastpath)  export PROOT_LOADER=$D/loader-fp PROOT_FASTPATH=bench; FPB=$(echo "$BINDS" | sed 's/-b //g; s/^ *//; s/ *$//; s/  */|/g'); exec $D/proot-fp --kill-on-exit --kernel-release="$REL" -i $U:$U -r $D/mini -w /root $BINDS /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8 PROOT_FP_ROOT=$D/mini "PROOT_FP_BINDS=$FPB" PROOT_FP_KEY=bench LD_PRELOAD=/usr/lib/libfastpath.so $FPEXTRA "$@" ;;
  fpoff)     export PROOT_LOADER=$D/loader-fp; exec $D/proot-fp --kill-on-exit --kernel-release="$REL" -i $U:$U -r $D/mini -w /root $BINDS /usr/bin/env -i HOME=/root PATH=/usr/bin LANG=C.UTF-8 "$@" ;;
  direct)    cd $D/mini/root; exec $D/mini/usr/lib/ld-linux-aarch64.so.1 --library-path $D/mini/usr/lib "$@" ;;
esac
