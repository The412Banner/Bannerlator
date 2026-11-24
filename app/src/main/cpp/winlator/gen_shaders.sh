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
#
#  The upscalers and CAS also get a half-precision build: the same source with
#  -DBL_HALF=1 -> <name>_half_frag.h (array <name>_half_code). The compositors
#  pick the plain or the half set at start-up (debug.bannerlator.upscale.shaders).
#  Names in HALF_ONLY are built half only.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v glslangValidator >/dev/null 2>&1; then
    echo "error: glslangValidator not found on PATH" >&2
    exit 1
fi

HALF=(sgsr sgsr_quality fsr_easu fsr_rcas cas_ffx nis_luma nis2)
HALF_ONLY=(cas_ffx nis_luma nis2)

in_list() {
    local x="$1"; shift
    for y in "$@"; do [ "$x" = "$y" ] && return 0; done
    return 1
}

gen() {
    local src="$1"          # e.g. nis.frag
    local base="${src%.*}"  # nis
    local ext="${src##*.}"  # frag | vert
    if ! in_list "${base}" "${HALF_ONLY[@]}"; then
        local vn="${base}_code"
        local out="${base}_${ext}.h"
        echo "  ${src} -> ${out}  (--vn ${vn})"
        glslangValidator -V "${src}" --vn "${vn}" -o "${out}"
    fi
    if in_list "${base}" "${HALF[@]}"; then
        local hvn="${base}_half_code"
        local hout="${base}_half_${ext}.h"
        echo "  ${src} -> ${hout}  (--vn ${hvn}, -DBL_HALF=1)"
        glslangValidator -V -DBL_HALF=1 "${src}" --vn "${hvn}" -o "${hout}"
    fi
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
