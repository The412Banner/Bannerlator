#!/usr/bin/env bash
# Cross-compile the PulseAudio 13.0 stack for Android arm64, adapted from BrunoSX's
# brunodev85/pulseaudio-android (stock upstream PA + ac_cv_* bionic overrides — no PA source patches).
# Uses RELEASE TARBALLS (pre-generated ./configure) so no bootstrap/autogen/help2man tooling is needed.
# Produces the exact 13.0 ABI we already bundle → drop-in.
#
# Inputs (env): NDK_PATH. Output: $OUT/  (client libs + daemon + core modules) for arm64.
set -euo pipefail

ARCH=arm64
BUILDCHAIN=aarch64-linux-android
API=26
PA_VER=13.0
LIBTOOL_VER=2.4.6
LIBSNDFILE_VER=1.0.31

BASE_DIR="$PWD"
SRC_DIR="$BASE_DIR/.src"
ROOT_DIR="$BASE_DIR/root-$ARCH"
OUT="$BASE_DIR/output/$ARCH"
: "${NDK_PATH:?set NDK_PATH to the Android NDK root}"

export PATH="$ROOT_DIR/bin:$PATH"
export PKG_CONFIG_PATH="$ROOT_DIR/lib/pkgconfig"
export CFLAGS="-O2 -I$ROOT_DIR/include"
export CPPFLAGS="-I$ROOT_DIR/include"
export LDFLAGS="-L$ROOT_DIR/lib"

# bionic quirk overrides (from Bruno's main-build.sh) so PA's configure accepts the NDK sysroot.
export ALLOW_UNRESOLVED_SYMBOLS=1
export ac_cv_func_mkfifo=no
export ac_cv_func_getuid=no
export ax_cv_PTHREAD_PRIO_INHERIT=no
export ac_cv_header_glob_h=no
export ac_cv_func_malloc_0_nonnull=yes
export ac_cv_func_realloc_0_nonnull=yes
export ac_cv_lib_ltdl_lt_dladvise_init=yes

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/${BUILDCHAIN}${API}-clang"
export CXX="$TOOLCHAIN/${BUILDCHAIN}${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" STRIP="$TOOLCHAIN/llvm-strip"
test -x "$CC" || { echo "CC not found: $CC"; ls "$TOOLCHAIN" | grep -i clang | head; exit 1; }

mkdir -p "$SRC_DIR" "$ROOT_DIR"
fetch() { # url dest
  echo "fetch $1"; curl -fsSL --retry 3 -o "$2" "$1"
}

# --- libltdl (runtime module-loader lib the PA daemon needs) — build only the libltdl subdir ---
if [ ! -e "$ROOT_DIR/lib/libltdl.so" ]; then
  cd "$SRC_DIR"
  [ -f "libtool-$LIBTOOL_VER.tar.gz" ] || fetch "https://ftp.gnu.org/gnu/libtool/libtool-$LIBTOOL_VER.tar.gz" "libtool-$LIBTOOL_VER.tar.gz"
  rm -rf "libtool-$LIBTOOL_VER"; tar xf "libtool-$LIBTOOL_VER.tar.gz"
  cd "libtool-$LIBTOOL_VER/libltdl"
  ./configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" --enable-shared --disable-static
  make -j"$(nproc)"; make install
fi

# --- libsndfile (PA optional dep; keep for parity with our shipped bundle) ---
if [ ! -e "$ROOT_DIR/lib/libsndfile.so" ]; then
  cd "$SRC_DIR"
  [ -f "libsndfile-$LIBSNDFILE_VER.tar.bz2" ] || fetch "https://github.com/libsndfile/libsndfile/releases/download/$LIBSNDFILE_VER/libsndfile-$LIBSNDFILE_VER.tar.bz2" "libsndfile-$LIBSNDFILE_VER.tar.bz2"
  rm -rf "libsndfile-$LIBSNDFILE_VER"; tar xf "libsndfile-$LIBSNDFILE_VER.tar.bz2"
  cd "libsndfile-$LIBSNDFILE_VER"
  ./configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" --disable-external-libs --disable-alsa --disable-sqlite --disable-static --enable-shared
  make -j"$(nproc)"; make install
fi

# --- PulseAudio 13.0 (stock upstream release tarball; has pre-generated configure) ---
cd "$SRC_DIR"
[ -f "pulseaudio-$PA_VER.tar.xz" ] || fetch "https://freedesktop.org/software/pulseaudio/releases/pulseaudio-$PA_VER.tar.xz" "pulseaudio-$PA_VER.tar.xz"
rm -rf "pulseaudio-$PA_VER"; tar xf "pulseaudio-$PA_VER.tar.xz"
cd "pulseaudio-$PA_VER"
rm -rf "build-$ARCH"; mkdir -p "build-$ARCH"; cd "build-$ARCH"
../configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" \
  --disable-static --enable-shared --disable-rpath --disable-nls --disable-x11 --disable-oss-wrapper \
  --disable-alsa --disable-esound --disable-waveout --disable-glib2 --disable-gtk3 --disable-gconf \
  --disable-avahi --disable-jack --disable-asyncns --disable-tcpwrap --disable-lirc --disable-dbus \
  --disable-bluez5 --disable-udev --disable-openssl --disable-manpages --disable-samplerate \
  --without-speex --with-database=simple --disable-orc --without-caps --without-fftw \
  --disable-systemd-daemon --disable-systemd-login --disable-systemd-journal --disable-webrtc-aec \
  --disable-tests --disable-neon-opt --disable-gsettings
make -j"$(nproc)"
make install

# --- collect the runtime set (mirrors Bruno's output layout) ---
rm -rf "$OUT"; mkdir -p "$OUT/modules"
cp -a "$ROOT_DIR/bin/pulseaudio"                               "$OUT/libpulseaudio.so"
cp -a "$ROOT_DIR"/lib/pulseaudio/libpulsecommon-*.so           "$OUT/"
cp -a "$ROOT_DIR"/lib/pulseaudio/libpulsecore-*.so             "$OUT/"
cp -a "$ROOT_DIR/lib/libpulse.so"                              "$OUT/libpulse.so"
cp -a "$ROOT_DIR/lib/libsndfile.so"                            "$OUT/libsndfile.so"
cp -a "$ROOT_DIR/lib/libltdl.so"                               "$OUT/libltdl.so"
cp -a "$ROOT_DIR"/lib/pulse-*/modules/libprotocol-native.so           "$OUT/modules/"
cp -a "$ROOT_DIR"/lib/pulse-*/modules/module-native-protocol-unix.so  "$OUT/modules/"
echo "stack built -> $OUT"; ls -la "$OUT" "$OUT/modules"
# expose the extracted PA source path for build-module.sh
echo "$SRC_DIR/pulseaudio-$PA_VER" > "$BASE_DIR/.pa_src_path"
