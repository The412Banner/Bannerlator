#!/usr/bin/env bash
# =============================================================================
#  Regenerate the committed SPIR-V headers (*_frag.h / *_vert.h) for the native
#  Vulkan compositor's post-process shaders.
#
#  The Android NDK build does NOT compile GLSL; instead every *.frag / *.vert in
#  this directory is pre-compiled to a SPIR-V uint32 array header that is #included
#  by VulkanRendererContext.cpp. Run this whenever you edit a shader, then commit
#  the regenerated *.h alongside the *.frag/*.vert source.
#
#  Requires glslangValidator on PATH (e.g. the Vulkan SDK or the termux package).
#
#  Usage:   ./gen_shaders.sh            # regenerate all shaders
#           ./gen_shaders.sh nis crt    # regenerate only the named shaders
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v glslangValidator >/dev/null 2>&1; then
    echo "error: glslangValidator not found on PATH" >&2
    exit 1
fi

gen() {
    local src="$1"          # e.g. nis.frag
    local base="${src%.*}"  # nis
    local ext="${src##*.}"  # frag | vert
    local vn="${base}_code"
    local out="${base}_${ext}.h"
    echo "  ${src} -> ${out}  (--vn ${vn})"
    glslangValidator -V "${src}" --vn "${vn}" -o "${out}"
}

shaders=()
if [ "$#" -gt 0 ]; then
    for name in "$@"; do
        for f in "${name}.frag" "${name}.vert"; do
            [ -f "$f" ] && shaders+=("$f")
        done
    done
else
    for f in *.frag *.vert; do
        [ -f "$f" ] && shaders+=("$f")
    done
fi

for s in "${shaders[@]}"; do
    gen "$s"
done

echo "done."
