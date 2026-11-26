#version 450

// =============================================================================
//  AMD FidelityFX CAS (Contrast Adaptive Sharpening) - sharpen-only path.
//
//  Ported from AMD's reference header:
//    GPUOpen-Effects/FidelityFX-CAS  ffx-cas/ffx_cas.h  (v1.0.2, CAS 1.20190610)
//    CasSetup() sharpness term + CasFilter() with noScaling == true.
//
//  Copyright (c) 2017-2019 Advanced Micro Devices, Inc. All rights reserved.
//  SPDX-License-Identifier: MIT
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to
//  deal in the Software without restriction, including without limitation the
//  rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
//  sell copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
//  The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
//  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
//  IN THE SOFTWARE.
//
//  Adapted for the Bannerlator compositors (X11 VulkanRendererContext and the
//  Wayland effects chain), built half only (cas_ffx_half_frag.h):
//   - AMD's default build: CAS_BETTER_DIAGONALS, CAS_SLOW and CAS_GO_SLOWER not
//     defined -> the 5-tap "circle" (b d e f h) and the green channel's weight
//     for all channels. cas.frag (the previous shader) is the BETTER_DIAGONALS
//     + CAS_SLOW maths with 9 taps.
//   - APrxLoRcp / APrxLoSqrt / APrxMedRcp (bit-trick approximations for AMD
//     ALUs) are plain 1/x and sqrt here.
//   - Same push constants as cas.frag: ndc, input size, sharpness [0..1].
//     CasSetup's const1.x = -1 / lerp(8, 5, sharpness) is computed here.
//   - The input is the effect chain's previous target at the same size, so the
//     output pixel reads the input pixel 1:1 (texelFetch, edges clamped).
// =============================================================================

// BL_HALF is set for the *_half_frag.h build (gen_shaders.sh): colour and
// weight maths in mediump (RelaxedPrecision, 16-bit on Adreno) as in AMD's
// CasFilterH; positions stay highp.
#ifdef BL_HALF
precision mediump float;
#define BL_MP mediump
#else
#define BL_MP
#endif

layout(binding = 0) uniform BL_MP sampler2D InputTexture;

layout(push_constant) uniform PC {
    highp vec4  ndc;        // quad NDC rect, consumed by upscale.vert (offset 0)
    highp vec2  resolution; // input (= output) size in pixels
    float sharpness;        // CAS sharpness [0..1]
} pc;

layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

vec3 CasLoad(ivec2 p) {
    ivec2 sz = textureSize(InputTexture, 0);
    return texelFetch(InputTexture, clamp(p, ivec2(0), sz - ivec2(1)), 0).rgb;
}

float AMin3F1(float x, float y, float z) { return min(x, min(y, z)); }
float AMax3F1(float x, float y, float z) { return max(x, max(y, z)); }

void main() {
    ivec2 sp = ivec2(fragTexCoord * pc.resolution);

    // CasSetup: sharp = -1 / lerp(8.0, 5.0, saturate(sharpness)).
    float peak = -1.0 / mix(8.0, 5.0, clamp(pc.sharpness, 0.0, 1.0));

    //    b
    //  d e f
    //    h
    vec3 b = CasLoad(sp + ivec2( 0, -1));
    vec3 d = CasLoad(sp + ivec2(-1,  0));
    vec3 e = CasLoad(sp);
    vec3 f = CasLoad(sp + ivec2( 1,  0));
    vec3 h = CasLoad(sp + ivec2( 0,  1));

    // Soft min and max of the circle (green only: the weight below uses green).
    float mnG = AMin3F1(AMin3F1(d.g, e.g, f.g), b.g, h.g);
    float mxG = AMax3F1(AMax3F1(d.g, e.g, f.g), b.g, h.g);

    // Smooth minimum distance to signal limit divided by smooth max.
    // AMD's bit-trick reciprocal stays finite at 0; 1/0 here would give 0 * inf
    // on black, so the max is floored (below one 8-bit step; mn = 0 there anyway).
    float rcpMG = 1.0 / max(mxG, 1.0 / 1024.0);
    float ampG = clamp(min(mnG, 1.0 - mxG) * rcpMG, 0.0, 1.0);

    // Shaping amount of sharpening.
    ampG = sqrt(ampG);

    // Filter shape:  0 w 0 / w 1 w / 0 w 0
    float wG = ampG * peak;
    float rcpWeight = 1.0 / (1.0 + 4.0 * wG);
    vec3 pix = clamp((b * wG + d * wG + f * wG + h * wG + e) * rcpWeight, 0.0, 1.0);

    outColor = vec4(pix, 1.0);
}
