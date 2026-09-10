package com.winlator.star.renderer.effects;

import com.winlator.star.renderer.material.ScreenMaterial;
import com.winlator.star.renderer.material.ShaderMaterial;

// =============================================================================
//  Snapdragon Game Super Resolution (SGSR) 1.0 - spatial upscaler, mobile path.
//
//  Ported from Qualcomm's reference shader:
//    SnapdragonStudios/snapdragon-gsr  sgsr/v1/include/glsl/sgsr1_shader_mobile.frag
//  via the Winlator native Vulkan compositor port (app/src/main/cpp/winlator/sgsr.frag).
//
//  Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
//  SPDX-License-Identifier: BSD-3-Clause
//
//  GL EffectComposer port:
//   - GLSL ES 3.00 (#version 300 es) — the GL renderer's EGL context is ES 3.x.
//   - textureGather() needs ES 3.1; the GL context is only guaranteed ES 3.0, so the
//     component-1 (green) gathers are emulated with four texelFetch() reads in the exact
//     textureGather footprint/order (.x=(i0,j1) .y=(i1,j1) .z=(i1,j0) .w=(i0,j0)).
//   - the source is the EffectComposer low-res render target (input res = srcResolution);
//     EdgeSharpness is driven live by the drawer "Sharpness" slider via the `sharpness`
//     uniform (0..1).
//
//  Quality variant (scaling mode 8, `new SGSREffect(true)`): Qualcomm's
//  sgsr1_shader_mobile_edge_direction.frag path — Lanczos weights stretched along the
//  local edge direction plus the reference's retuned contrast term (10.14185 / sum)^2.
//  Mirrors app/src/main/cpp/winlator/sgsr_quality.frag. Mode 3 is unchanged.
// =============================================================================

public class SGSREffect extends Effect {

    private final boolean quality;

    public SGSREffect() {
        this(false);
    }

    public SGSREffect(boolean quality) {
        this.quality = quality;
    }

    @Override
    protected ShaderMaterial createMaterial() {
        return new SGSRMaterial(quality);
    }

    private static class SGSRMaterial extends ScreenMaterial {
        private final boolean quality;

        SGSRMaterial(boolean quality) {
            super();
            this.quality = quality;
            // resolution = output size, srcResolution = low-res input size, sharpness 0..1.
            setUniformNames("resolution", "screenTexture", "srcResolution", "sharpness");
        }

        @Override
        protected String getVertexShader() {
            // Must be #version 300 es to link with the 300 es fragment shader. The composer's
            // shared "position" quad maps [0,1] -> NDC; compileShaders() injects an (unused)
            // applyXForm() helper ahead of main(), which is valid in ES 3.00.
            return String.join("\n", new CharSequence[]{
                "#version 300 es",
                "layout(location = 0) in vec2 position;",
                "void main() {",
                "    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);",
                "}"
            });
        }

        // Mode 3 (fast): the original single-float weight, byte-identical to before.
        private static final String WEIGHT_Y_FAST = String.join("\n", new CharSequence[]{
            "vec2 weightY(float dx, float dy, float c, float std) {",
            "    float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);",
            "    float w = fastLanczos2(x);",
            "    return vec2(w, w * c);",
            "}"
        });

        // Mode 8 (quality): data.x = contrast term, data.yz = normalised edge direction.
        private static final String WEIGHT_Y_QUALITY = String.join("\n", new CharSequence[]{
            "vec2 weightY(float dx, float dy, float c, vec3 data) {",
            "    float std = data.x;",
            "    vec2  dir = data.yz;",
            "    float edgeDis = (dx * dir.y) + (dy * dir.x);",
            "    float x = ((dx * dx) + (dy * dy))",
            "            + ((edgeDis * edgeDis) * ((clamp((c * c) * std, 0.0, 1.0) * 0.7) - 1.0));",
            "    float w = fastLanczos2(x);",
            "    return vec2(w, w * c);",
            "}",
            "vec2 edgeDirection(vec4 left, vec4 right) {",
            "    float RxLz = right.x - left.z;",
            "    float RwLy = right.w - left.y;",
            "    vec2 delta = vec2(RxLz + RwLy, RxLz - RwLy);",
            "    float lengthInv = inversesqrt((delta.x * delta.x + 3.075740e-05) + (delta.y * delta.y));",
            "    return delta * lengthInv;",
            "}"
        });

        private static final String STD_FAST = "        float std = 2.181818 / sum;";

        private static final String STD_QUALITY = String.join("\n", new CharSequence[]{
            "        float sumMean = 1.014185e+01 / sum;",
            "        vec3 std = vec3(sumMean * sumMean, edgeDirection(left, right));"
        });

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                "#version 300 es",
                "precision highp float;",
                "uniform sampler2D screenTexture;",
                "uniform vec2 resolution;     // output size (px)",
                "uniform vec2 srcResolution;  // low-res input size (px)",
                "uniform float sharpness;     // 0..1 from the slider",
                "out vec4 fragColor;",

                "const float EdgeThreshold = 8.0 / 255.0;",

                "float fastLanczos2(float x) {",
                "    float wA = x - 4.0;",
                "    float wB = x * wA - wA;",
                "    wA *= wA;",
                "    return wB * wA;",
                "}",

                quality ? WEIGHT_Y_QUALITY : WEIGHT_Y_FAST,

                // textureGather(ps0, coord, 1) emulation (green channel) for ES 3.0.
                "vec4 gatherG(vec2 coord) {",
                "    ivec2 sz = ivec2(srcResolution) - ivec2(1);",
                "    vec2 f = coord * srcResolution - 0.5;",
                "    ivec2 i0 = ivec2(floor(f));",
                "    ivec2 i1 = i0 + ivec2(1);",
                "    float t01 = texelFetch(screenTexture, clamp(ivec2(i0.x, i1.y), ivec2(0), sz), 0).g;",
                "    float t11 = texelFetch(screenTexture, clamp(ivec2(i1.x, i1.y), ivec2(0), sz), 0).g;",
                "    float t10 = texelFetch(screenTexture, clamp(ivec2(i1.x, i0.y), ivec2(0), sz), 0).g;",
                "    float t00 = texelFetch(screenTexture, clamp(ivec2(i0.x, i0.y), ivec2(0), sz), 0).g;",
                "    return vec4(t01, t11, t10, t00);",
                "}",

                "void main() {",
                // Map the slider (0..1) to EdgeSharpness. 1.0 is the neutral floor (the natural
                // reconstruction with no extra edge boost; the spatial upscale still runs), so
                // slider 0 -> 1.0 = no visible extra sharpening. The slider-driven span is DOUBLED
                // (was *1.333): slider 100 -> 3.666, twice the previous applied amount.
                "    float EdgeSharpness = 1.0 + sharpness * 2.666;",
                "    vec4 ViewportInfo = vec4(1.0 / srcResolution, srcResolution);",
                "    vec2 uv = gl_FragCoord.xy / resolution;",

                "    vec4 color;",
                "    color.xyz = textureLod(screenTexture, uv, 0.0).xyz;",

                "    vec2 imgCoord = ((uv * ViewportInfo.zw) + vec2(-0.5, 0.5));",
                "    vec2 imgCoordPixel = floor(imgCoord);",
                "    vec2 coord = (imgCoordPixel * ViewportInfo.xy);",
                "    vec2 pl = (imgCoord + (-imgCoordPixel));",
                "    vec4 left = gatherG(coord);",

                "    float edgeVote = abs(left.z - left.y) + abs(color.y - left.y) + abs(color.y - left.z);",
                "    if (edgeVote > EdgeThreshold) {",
                "        coord.x += ViewportInfo.x;",

                "        vec4 right = gatherG(coord + vec2(ViewportInfo.x, 0.0));",
                "        vec4 upDown;",
                "        upDown.xy = gatherG(coord + vec2(0.0, -ViewportInfo.y)).wz;",
                "        upDown.zw = gatherG(coord + vec2(0.0,  ViewportInfo.y)).yx;",

                "        float mean = (left.y + left.z + right.x + right.w) * 0.25;",
                "        left   = left   - vec4(mean);",
                "        right  = right  - vec4(mean);",
                "        upDown = upDown - vec4(mean);",
                "        color.w = color.y - mean;",

                "        float sum = (((((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w))",
                "                   + (((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w)))",
                "                   + (((abs(upDown.x) + abs(upDown.y)) + abs(upDown.z)) + abs(upDown.w)));",
                quality ? STD_QUALITY : STD_FAST,

                "        vec2 aWY  = weightY(pl.x,       pl.y + 1.0, upDown.x, std);",
                "        aWY      += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);",
                "        aWY      += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);",
                "        aWY      += weightY(pl.x,       pl.y - 2.0, upDown.w, std);",
                "        aWY      += weightY(pl.x + 1.0, pl.y - 1.0, left.x,   std);",
                "        aWY      += weightY(pl.x,       pl.y - 1.0, left.y,   std);",
                "        aWY      += weightY(pl.x,       pl.y,       left.z,   std);",
                "        aWY      += weightY(pl.x + 1.0, pl.y,       left.w,   std);",
                "        aWY      += weightY(pl.x - 1.0, pl.y - 1.0, right.x,  std);",
                "        aWY      += weightY(pl.x - 2.0, pl.y - 1.0, right.y,  std);",
                "        aWY      += weightY(pl.x - 2.0, pl.y,       right.z,  std);",
                "        aWY      += weightY(pl.x - 1.0, pl.y,       right.w,  std);",

                "        float finalY = aWY.y / aWY.x;",

                "        float maxY = max(max(left.y, left.z), max(right.x, right.w));",
                "        float minY = min(min(left.y, left.z), min(right.x, right.w));",
                "        finalY = clamp(EdgeSharpness * finalY, minY, maxY);",

                "        float deltaY = finalY - color.w;",
                "        deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);",

                "        color.x = clamp((color.x + deltaY), 0.0, 1.0);",
                "        color.y = clamp((color.y + deltaY), 0.0, 1.0);",
                "        color.z = clamp((color.z + deltaY), 0.0, 1.0);",
                "    }",

                "    fragColor = vec4(color.xyz, 1.0);",
                "}"
            });
        }
    }
}
