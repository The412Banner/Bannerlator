#version 450

// =============================================================================
//  nis3.frag (feat/nis-rewrite): nis.frag's NVScaler maths with
//   - the 6x6 luma support read from the nis_luma.frag prepass (luma in alpha)
//     by 9 textureGather calls instead of 36 textureLod + getY per pixel;
//   - the two coefficient banks in a uniform buffer (set 1, nis_coef.h) instead
//     of const arrays: indexed by the per-pixel phase, the 384-entry const arrays
//     were copied into a per-pixel variable before every lookup (glslang
//     OpStore %indexable), several times per pixel.
//  Edge maps and filter maths are nis.frag's. Built half only (nis3_half_frag.h);
//  the old set keeps nis.frag.
// =============================================================================

// =============================================================================
//  NVIDIA Image Scaling (NIS) - NVScaler spatial upscaler + adaptive sharpen.
//
//  Ported from NVIDIA's reference NVScaler:
//    NVIDIAGameWorks/NVIDIAImageScaling  NIS/NIS_Scaler.h  (+ NIS_Config.h)
//
//  The MIT License (MIT)
//  Copyright (c) 2022 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
//  SPDX-License-Identifier: MIT
//
//  Adapted for the Winlator native Vulkan compositor:
//   - #version 450 (Vulkan GLSL), single-pass FRAGMENT shader (the reference is
//     an LDS-tiled compute shader). The block/LDS cooperative tile loads are
//     replaced with direct per-fragment texture() fetches of the 6x6 luma
//     support, and the edge map is computed inline from that support. The math
//     (edge detection + 6-tap directional scale/USM blend) is the reference's.
//   - The kPhaseCount x 6 scaler/USM coefficient banks (coef_scale, coef_usm,
//     fp32 path) are BAKED below as const arrays - no second descriptor binding
//     (the post dsLayout has ONE sampler at binding 0 shared by all pipelines).
//   - Config-UBO reads are replaced by push constants + baked SDR constants; the
//     sharpness-derived params are recomputed in-shader exactly as
//     NVScalerUpdateConfig does, driven by the existing "Sharpness" slider.
//   - fp32 only (NOT the fp16/bit-packed NIS_USE_HALF_PRECISION path) so there
//     are no bitwise/bvec ops - Adreno-compile-safe.
//   - HDR mode = None (SDR). The whole compositor chain is R8G8B8A8_UNORM and
//     values are display(gamma)-encoded here, matching NIS LDR expectations.
//   - Reuses upscale.vert: fragTexCoord spans [0,1] across the destination rect
//     (= normalised output position). With kScaleX = inputW/outputW, the source
//     sample position reduces to  srcX = fragTexCoord.x*inputW - 0.5  (so the
//     output viewport origin/size cancel out and are never needed here).
//
//  Conventions match sgsr.frag / cas.frag: combined-image-sampler at binding 0,
//  push-constant block leads with vec4 ndc (offset 0, consumed by upscale.vert),
//  then ViewportInfo (xy = 1/inputSize texel size, zw = inputSize px) + sharpness.
// =============================================================================

// BL_HALF is set for the *_half_frag.h build (gen_shaders.sh): colour and
// weight maths in mediump (RelaxedPrecision, 16-bit on Adreno) as NVIDIA's
// NIS_USE_HALF_PRECISION path (pixels, weights, coefficient tables); positions and texture coordinates stay highp.
#ifdef BL_HALF
precision mediump float;
#define BL_MP mediump
#else
#define BL_MP
#endif

layout(binding = 0) uniform BL_MP sampler2D InputTexture;

layout(push_constant) uniform PC {
    highp vec4  ndc;          // quad NDC rect, consumed by upscale.vert (offset 0)
    highp vec4  ViewportInfo; // xy = 1/inputSize (texel size), zw = inputSize in pixels
    float sharpness;    // "Sharpness" slider mapped to [0..1] (NIS sharpness)
} pc;

layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

// ---- SDR (HDR mode None) constants, from NVScalerUpdateConfig -----------------
const float kDetectRatio      = 2.0 * 1127.0 / 1024.0; // 2.201171875
const float kDetectThres      = 64.0 / 1024.0;         // 0.0625
const float kMinContrastRatio = 2.0;
const float kMaxContrastRatio = 10.0;
const float kRatioNorm        = 1.0 / (kMaxContrastRatio - kMinContrastRatio); // 0.125
const float kContrastBoost    = 1.0;
const float kEps              = 1.0 / 255.0;
const float kSharpStartY      = 0.45;
const float kSharpEndY        = 0.9;
const float kSharpScaleY      = 1.0 / (kSharpEndY - kSharpStartY); // 2.222...
const int   kPhaseCount       = 64;

// Sharpness-derived params (recomputed in main from pc.sharpness, read here).
float gSharpStrengthMin;
float gSharpStrengthScale;
float gSharpLimitMin;
float gSharpLimitScale;

// NVIDIA NIS coef_scale / coef_usm (64 phases x 6 taps, the values nis.frag bakes), filled by the
// compositor from nis_coef.h: two vec4 per phase (taps 0-3, taps 4-5 + 2 zeros), std140.
layout(std140, set = 1, binding = 0) uniform NisCoef {
    vec4 scale[128];
    vec4 usm[128];
} nisCoef;

float getY(vec3 rgb) {
    return 0.2126 * rgb.x + 0.7152 * rgb.y + 0.0722 * rgb.z;
}

// GetEdgeMap on a 3x3 luma window (pRC: R=row 0=top, C=col 0=left).
vec4 edgeAt(float p00, float p01, float p02,
            float p10, float p11, float p12,
            float p20, float p21, float p22) {
    float g_0   = abs(p00 + p01 + p02 - p20 - p21 - p22);
    float g_45  = abs(p10 + p00 + p01 - p21 - p22 - p12);
    float g_90  = abs(p00 + p10 + p20 - p02 - p12 - p22);
    float g_135 = abs(p10 + p20 + p21 - p01 - p02 - p12);

    float g_0_90_max   = max(g_0, g_90);
    float g_0_90_min   = min(g_0, g_90);
    float g_45_135_max = max(g_45, g_135);
    float g_45_135_min = min(g_45, g_135);

    if (g_0_90_max + g_45_135_max == 0.0) {
        return vec4(0.0);
    }

    float e_0_90   = min(g_0_90_max / (g_0_90_max + g_45_135_max), 1.0);
    float e_45_135 = 1.0 - e_0_90;

    bool c_0_90    = (g_0_90_max > (g_0_90_min * kDetectRatio)) && (g_0_90_max > kDetectThres) && (g_0_90_max > g_45_135_min);
    bool c_45_135  = (g_45_135_max > (g_45_135_min * kDetectRatio)) && (g_45_135_max > kDetectThres) && (g_45_135_max > g_0_90_min);
    bool c_g_0_90  = (g_0_90_max == g_0);
    bool c_g_45_135= (g_45_135_max == g_45);

    float f_e_0_90   = (c_0_90 && c_45_135) ? e_0_90 : 1.0;
    float f_e_45_135 = (c_0_90 && c_45_135) ? e_45_135 : 1.0;

    float w0   = (c_0_90 && c_g_0_90)     ? f_e_0_90   : 0.0;
    float w90  = (c_0_90 && !c_g_0_90)    ? f_e_0_90   : 0.0;
    float w45  = (c_45_135 && c_g_45_135) ? f_e_45_135 : 0.0;
    float w135 = (c_45_135 && !c_g_45_135)? f_e_45_135 : 0.0;

    return vec4(w0, w90, w45, w135);
}

vec4 getInterpEdgeMap(vec4 e00, vec4 e01, vec4 e10, vec4 e11, float fx, float fy) {
    vec4 h0 = mix(e00, e01, fx);
    vec4 h1 = mix(e10, e11, fx);
    return mix(h0, h1, fy);
}

float calcLTI(float p0, float p1, float p2, float p3, float p4, float p5, int phase_index) {
    bool selector = (phase_index <= kPhaseCount / 2);
    float sel = selector ? p0 : p3;
    float a_min = min(min(p1, p2), sel);
    float a_max = max(max(p1, p2), sel);
    sel = selector ? p2 : p5;
    float b_min = min(min(p3, p4), sel);
    float b_max = max(max(p3, p4), sel);

    float a_cont = a_max - a_min;
    float b_cont = b_max - b_min;

    float cont_ratio = max(a_cont, b_cont) / (min(a_cont, b_cont) + kEps);
    return (1.0 - clamp((cont_ratio - kMinContrastRatio) * kRatioNorm, 0.0, 1.0)) * kContrastBoost;
}

float evalPoly6(float pxl[6], int phase_int) {
    phase_int = clamp(phase_int, 0, 63);
    vec4 s0 = nisCoef.scale[phase_int * 2], s1 = nisCoef.scale[phase_int * 2 + 1];
    vec4 u0 = nisCoef.usm[phase_int * 2],   u1 = nisCoef.usm[phase_int * 2 + 1];
    float y = 0.0;
    y += s0.x * pxl[0]; y += s0.y * pxl[1]; y += s0.z * pxl[2];
    y += s0.w * pxl[3]; y += s1.x * pxl[4]; y += s1.y * pxl[5];
    float y_usm = 0.0;
    y_usm += u0.x * pxl[0]; y_usm += u0.y * pxl[1]; y_usm += u0.z * pxl[2];
    y_usm += u0.w * pxl[3]; y_usm += u1.x * pxl[4]; y_usm += u1.y * pxl[5];

    // piece-wise ramp based on luma (NIS_SCALE_FLOAT == 1)
    float y_scale = 1.0 - clamp((y - kSharpStartY) * kSharpScaleY, 0.0, 1.0);
    float y_sharpness = y_scale * gSharpStrengthScale + gSharpStrengthMin;
    y_usm *= y_sharpness;

    float y_sharpness_limit = (y_scale * gSharpLimitScale + gSharpLimitMin) * y;
    y_usm = min(y_sharpness_limit, max(-y_sharpness_limit, y_usm));
    y_usm *= calcLTI(pxl[0], pxl[1], pxl[2], pxl[3], pxl[4], pxl[5], phase_int);

    return y + y_usm;
}

float filterNormal(float p[6][6], int phase_x_int, int phase_y_int) {
    phase_x_int = clamp(phase_x_int, 0, 63);
    phase_y_int = clamp(phase_y_int, 0, 63);
    vec4 y0 = nisCoef.scale[phase_y_int * 2], y1 = nisCoef.scale[phase_y_int * 2 + 1];
    vec4 x0 = nisCoef.scale[phase_x_int * 2], x1 = nisCoef.scale[phase_x_int * 2 + 1];
    float cy[6] = float[6](y0.x, y0.y, y0.z, y0.w, y1.x, y1.y);
    float cx[6] = float[6](x0.x, x0.y, x0.z, x0.w, x1.x, x1.y);
    float h_acc = 0.0;
    for (int j = 0; j < 6; ++j) {
        float v_acc = 0.0;
        for (int i = 0; i < 6; ++i) {
            v_acc += p[i][j] * cy[i];
        }
        h_acc += v_acc * cx[j];
    }
    return h_acc;
}

float addDirFilters(float p[6][6], float phase_x_frac, float phase_y_frac,
                    int phase_x_frac_int, int phase_y_frac_int, vec4 w) {
    float f = 0.0;
    if (w.x > 0.0) {
        // 0 deg filter
        float interp0Deg[6];
        for (int i = 0; i < 6; ++i) {
            interp0Deg[i] = mix(p[i][2], p[i][3], phase_x_frac);
        }
        f += evalPoly6(interp0Deg, phase_y_frac_int) * w.x;
    }
    if (w.y > 0.0) {
        // 90 deg filter
        float interp90Deg[6];
        for (int i = 0; i < 6; ++i) {
            interp90Deg[i] = mix(p[2][i], p[3][i], phase_y_frac);
        }
        f += evalPoly6(interp90Deg, phase_x_frac_int) * w.y;
    }
    if (w.z > 0.0) {
        // 45 deg filter
        float pphase_b45 = 0.5 + 0.5 * (phase_x_frac - phase_y_frac);

        float temp_interp45Deg[7];
        temp_interp45Deg[1] = mix(p[2][1], p[1][2], pphase_b45);
        temp_interp45Deg[3] = mix(p[3][2], p[2][3], pphase_b45);
        temp_interp45Deg[5] = mix(p[4][3], p[3][4], pphase_b45);
        {
            pphase_b45 = pphase_b45 - 0.5;
            float a = (pphase_b45 >= 0.0) ? p[0][2] : p[2][0];
            float b = (pphase_b45 >= 0.0) ? p[1][3] : p[3][1];
            float c = (pphase_b45 >= 0.0) ? p[2][4] : p[4][2];
            float d = (pphase_b45 >= 0.0) ? p[3][5] : p[5][3];
            temp_interp45Deg[0] = mix(p[1][1], a, abs(pphase_b45));
            temp_interp45Deg[2] = mix(p[2][2], b, abs(pphase_b45));
            temp_interp45Deg[4] = mix(p[3][3], c, abs(pphase_b45));
            temp_interp45Deg[6] = mix(p[4][4], d, abs(pphase_b45));
        }

        float interp45Deg[6];
        float pphase_p45 = phase_x_frac + phase_y_frac;
        if (pphase_p45 >= 1.0) {
            for (int i = 0; i < 6; ++i) {
                interp45Deg[i] = temp_interp45Deg[i + 1];
            }
            pphase_p45 = pphase_p45 - 1.0;
        } else {
            for (int i = 0; i < 6; ++i) {
                interp45Deg[i] = temp_interp45Deg[i];
            }
        }

        f += evalPoly6(interp45Deg, int(pphase_p45 * 64.0)) * w.z;
    }
    if (w.w > 0.0) {
        // 135 deg filter
        float pphase_b135 = 0.5 * (phase_x_frac + phase_y_frac);

        float temp_interp135Deg[7];
        temp_interp135Deg[1] = mix(p[3][1], p[4][2], pphase_b135);
        temp_interp135Deg[3] = mix(p[2][2], p[3][3], pphase_b135);
        temp_interp135Deg[5] = mix(p[1][3], p[2][4], pphase_b135);
        {
            pphase_b135 = pphase_b135 - 0.5;
            float a = (pphase_b135 >= 0.0) ? p[5][2] : p[3][0];
            float b = (pphase_b135 >= 0.0) ? p[4][3] : p[2][1];
            float c = (pphase_b135 >= 0.0) ? p[3][4] : p[1][2];
            float d = (pphase_b135 >= 0.0) ? p[2][5] : p[0][3];
            temp_interp135Deg[0] = mix(p[4][1], a, abs(pphase_b135));
            temp_interp135Deg[2] = mix(p[3][2], b, abs(pphase_b135));
            temp_interp135Deg[4] = mix(p[2][3], c, abs(pphase_b135));
            temp_interp135Deg[6] = mix(p[1][4], d, abs(pphase_b135));
        }

        float interp135Deg[6];
        float pphase_p135 = 1.0 + (phase_x_frac - phase_y_frac);
        if (pphase_p135 >= 1.0) {
            for (int i = 0; i < 6; ++i) {
                interp135Deg[i] = temp_interp135Deg[i + 1];
            }
            pphase_p135 = pphase_p135 - 1.0;
        } else {
            for (int i = 0; i < 6; ++i) {
                interp135Deg[i] = temp_interp135Deg[i];
            }
        }

        f += evalPoly6(interp135Deg, int(pphase_p135 * 64.0)) * w.w;
    }
    return f;
}

void main() {
    // Sharpness-derived params - mirror NVScalerUpdateConfig (SDR path).
    float sharpness = clamp(pc.sharpness, 0.0, 1.0);
    float sharpen_slider = sharpness - 0.5;
    float MaxScale   = (sharpen_slider >= 0.0) ? 1.25 : 1.75;
    float MinScale   = (sharpen_slider >= 0.0) ? 1.25 : 1.0;
    float LimitScale = (sharpen_slider >= 0.0) ? 1.25 : 1.0;

    gSharpStrengthMin   = max(0.0, 0.4 + sharpen_slider * MinScale * 1.2);
    float sharpStrengthMax = 1.6 + sharpen_slider * MaxScale * 1.8;
    gSharpStrengthScale = sharpStrengthMax - gSharpStrengthMin;
    gSharpLimitMin      = max(0.1, 0.14 + sharpen_slider * LimitScale * 0.32);
    float sharpLimitMax = 0.5 + sharpen_slider * LimitScale * 0.6;
    gSharpLimitScale    = sharpLimitMax - gSharpLimitMin;

    highp vec2 inSize = pc.ViewportInfo.zw;
    highp vec2 invIn  = pc.ViewportInfo.xy;

    // Source sample position in input-pixel space (output viewport cancels out).
    highp float srcX = fragTexCoord.x * inSize.x - 0.5;
    highp float srcY = fragTexCoord.y * inSize.y - 0.5;
    highp float fsx = floor(srcX);
    highp float fsy = floor(srcY);
    highp float fx = srcX - fsx;
    highp float fy = srcY - fsy;
    int fx_int = int(fx * float(kPhaseCount));
    int fy_int = int(fy * float(kPhaseCount));

    // 6x6 luma support: p[i][j] = luma at source texel (fsx + j - 2, fsy + i - 2), from the
    // prepass alpha. A gather at the shared corner of texels (x, y)..(x + 1, y + 1) returns
    // .w = (x, y), .z = (x + 1, y), .x = (x, y + 1), .y = (x + 1, y + 1); block (bi, bj) starts
    // at x = fsx - 2 + 2 bj, y = fsy - 2 + 2 bi, so its corner is (fsx + 2 bj - 1, fsy + 2 bi - 1).
    // Clamp-to-edge clamps each gathered texel like the per-texel fetches did.
    float p[6][6];
    for (int bi = 0; bi < 3; ++bi) {
        for (int bj = 0; bj < 3; ++bj) {
            highp vec2 tc = vec2(fsx + float(2 * bj - 1), fsy + float(2 * bi - 1)) * invIn;
            vec4 g = textureGather(InputTexture, tc, 3);
            p[2 * bi][2 * bj]         = g.w;
            p[2 * bi][2 * bj + 1]     = g.z;
            p[2 * bi + 1][2 * bj]     = g.x;
            p[2 * bi + 1][2 * bj + 1] = g.y;
        }
    }

    // 2x2 edge map around the source pixel (3x3 luma window per corner).
    vec4 e00 = edgeAt(p[1][1], p[1][2], p[1][3],  p[2][1], p[2][2], p[2][3],  p[3][1], p[3][2], p[3][3]);
    vec4 e01 = edgeAt(p[1][2], p[1][3], p[1][4],  p[2][2], p[2][3], p[2][4],  p[3][2], p[3][3], p[3][4]);
    vec4 e10 = edgeAt(p[2][1], p[2][2], p[2][3],  p[3][1], p[3][2], p[3][3],  p[4][1], p[4][2], p[4][3]);
    vec4 e11 = edgeAt(p[2][2], p[2][3], p[2][4],  p[3][2], p[3][3], p[3][4],  p[4][2], p[4][3], p[4][4]);
    vec4 w = getInterpEdgeMap(e00, e01, e10, e11, fx, fy); // * NIS_SCALE_INT (==1)

    float baseWeight = 1.0 - w.x - w.y - w.z - w.w; // NIS_SCALE_FLOAT - sum
    float opY = filterNormal(p, fx_int, fy_int) * baseWeight;
    opY += addDirFilters(p, fx, fy, fx_int, fy_int, w);

    // Bilinear color tap from the prepass rgb (= the game image) + luma correction.
    vec4 op = texture(InputTexture, fragTexCoord);
    float y = getY(op.rgb);
    float corr = opY - y; // * (1/NIS_SCALE_FLOAT) == 1
    op.rgb += vec3(corr);

    outColor = vec4(clamp(op.rgb, 0.0, 1.0), 1.0);
}
