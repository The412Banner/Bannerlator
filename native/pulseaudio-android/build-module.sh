#!/usr/bin/env bash
# Compile our enhanced module-aaudio-sink.so against the PA 13.0 tree built by build-stack.sh.
# Adapted from brunodev85/pulseaudio-android pulseaudio-module/build.sh (single clang invocation).
set -euo pipefail

ARCH=arm64
BUILDCHAIN=aarch64-linux-android
API=26
BASE_DIR="$PWD"
ROOT_DIR="$BASE_DIR/root-$ARCH"
OUT="$BASE_DIR/output/$ARCH"
: "${NDK_PATH:?set NDK_PATH}"

PA_SRC="$(cat "$BASE_DIR/.pa_src_path")"
test -d "$PA_SRC" || { echo "PA source not found ($PA_SRC) — run build-stack.sh first"; exit 1; }

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN/${BUILDCHAIN}${API}-clang"

mkdir -p "$OUT/modules"
$CC -O2 -shared \
  -I"$PA_SRC/build-$ARCH" -I"$PA_SRC/src" -I"$ROOT_DIR/include" \
  -L"$ROOT_DIR/lib/pulseaudio" -L"$ROOT_DIR/lib" \
  -lpulsecore-13.0 -lpulsecommon-13.0 -lpulse -laaudio \
  -o "$OUT/modules/module-aaudio-sink.so" \
  "$BASE_DIR/pulseaudio-module/module-aaudio-sink.c"
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT/modules/module-aaudio-sink.so" || true
echo "module built -> $OUT/modules/module-aaudio-sink.so"; file "$OUT/modules/module-aaudio-sink.so"
