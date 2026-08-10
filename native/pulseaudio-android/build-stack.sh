#!/usr/bin/env bash
# Cross-compile the PulseAudio 13.0 stack for Android arm64, adapted from BrunoSX's
# brunodev85/pulseaudio-android main-build.sh (stock upstream PA + ac_cv_* bionic overrides — no PA
# source patches). Produces the exact 13.0 ABI we already bundle, so it's a drop-in.
#
# Inputs (env): NDK_PATH (NDK root), API level defaults 26. Runs on a Linux x86_64 host (CI).
# Output: $OUT/  (client libs + daemon + core modules) for arm64.
set -euo pipefail

ARCH=arm64
BUILDCHAIN=aarch64-linux-android
API=26
PA_COMMIT=200618b32f0964a479d69c9b6e5073e6931c370a   # stock pulseaudio/pulseaudio, v13.0 line
LIBSNDFILE_TAG=1.0.28
LIBTOOL_TAG=v2.4.6

BASE_DIR="$PWD"
SRC_DIR="$BASE_DIR/.src"
ROOT_DIR="$BASE_DIR/root-$ARCH"
OUT="$BASE_DIR/output/$ARCH"
: "${NDK_PATH:?set NDK_PATH to the Android NDK root}"

export PATH="$ROOT_DIR/bin:$PATH"
export PKG_CONFIG_PATH="$ROOT_DIR/lib/pkgconfig"
export ACLOCAL_PATH="$ROOT_DIR/share/aclocal"
export CFLAGS="-O2 -I$ROOT_DIR/include"

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

mkdir -p "$SRC_DIR" "$ROOT_DIR"

# --- libtool (for target libltdl.so + libtoolize used by PA bootstrap) ---
if [ ! -e "$ROOT_DIR/lib/libltdl.so" ]; then
  [ -d "$SRC_DIR/libtool" ] || git clone --depth 1 -b "$LIBTOOL_TAG" https://github.com/autotools-mirror/libtool.git "$SRC_DIR/libtool"
  pushd "$SRC_DIR/libtool"
  [ -e configure ] || ./bootstrap || ./autogen.sh || true
  mkdir -p build-$ARCH && cd build-$ARCH
  ../configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" HELP2MAN=/bin/true MAKEINFO=/bin/true
  make -j"$(nproc)" && make install
  popd
fi
export LIBTOOLIZE="$ROOT_DIR/bin/libtoolize"

# --- libsndfile (PA optional dep) ---
if [ ! -e "$ROOT_DIR/lib/libsndfile.so" ]; then
  [ -d "$SRC_DIR/libsndfile" ] || git clone --depth 1 -b "$LIBSNDFILE_TAG" https://github.com/libsndfile/libsndfile.git "$SRC_DIR/libsndfile"
  pushd "$SRC_DIR/libsndfile"
  [ -e configure ] || ./autogen.sh || true
  mkdir -p build-$ARCH && cd build-$ARCH
  ../configure --host=$BUILDCHAIN --prefix="$ROOT_DIR" --disable-external-libs --disable-alsa --disable-sqlite
  perl -pi -e 's/ examples / /g' Makefile || true
  make -j"$(nproc)" && make install
  popd
fi

# --- PulseAudio 13.0 (stock upstream, pinned) ---
if [ ! -d "$SRC_DIR/pulseaudio/.git" ]; then
  git clone https://github.com/pulseaudio/pulseaudio.git "$SRC_DIR/pulseaudio"
fi
pushd "$SRC_DIR/pulseaudio"
git fetch --all --tags --quiet || true
git checkout "$PA_COMMIT"
env NOCONFIGURE=1 bash -x ./bootstrap.sh
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
popd

# --- collect the runtime set (mirrors Bruno's output layout) ---
rm -rf "$OUT"; mkdir -p "$OUT/modules"
cp -a "$ROOT_DIR/bin/pulseaudio"                               "$OUT/libpulseaudio.so"
cp -a "$ROOT_DIR/lib/pulseaudio/libpulsecommon-13.0.so"        "$OUT/libpulsecommon-13.0.so"
cp -a "$ROOT_DIR/lib/pulseaudio/libpulsecore-13.0.so"          "$OUT/libpulsecore-13.0.so"
cp -a "$ROOT_DIR/lib/libpulse.so"                              "$OUT/libpulse.so"
cp -a "$ROOT_DIR/lib/libsndfile.so"                            "$OUT/libsndfile.so"
cp -a "$ROOT_DIR/lib/libltdl.so"                               "$OUT/libltdl.so"
cp -a "$ROOT_DIR/lib/pulse-13.0/modules/libprotocol-native.so" "$OUT/modules/libprotocol-native.so"
cp -a "$ROOT_DIR/lib/pulse-13.0/modules/module-native-protocol-unix.so" "$OUT/modules/module-native-protocol-unix.so"
echo "stack built -> $OUT"
ls -la "$OUT" "$OUT/modules"
