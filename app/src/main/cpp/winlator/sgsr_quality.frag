#version 450

// =============================================================================
//  Snapdragon Game Super Resolution (SGSR) 1 - "Quality" variant (edge direction).
//
//  Ported from Qualcomm's reference shader:
//    SnapdragonGameStudios/snapdragon-gsr
//    sgsr/v1/include/glsl/sgsr1_shader_mobile_edge_direction.frag
//
//  Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
//  SPDX-License-Identifier: BSD-3-Clause
//
//  Same single pass, inputs and push constants as sgsr.frag (the mode-3 "SGSR"
//  shader, left untouched). Differences, both taken from the reference file:
//   - UseEdgeDirection: the Lanczos weights are stretched along the local edge
//     direction, so diagonal edges reconstruct cleaner ("minimal cost increase"
//     per Qualcomm).
//   - the reference's retuned contrast term: std = (10.14185 / sum)^2
//     (sgsr.frag keeps the older 2.181818 / sum).
//  Adapted for the Winlator native Vulkan compositor exactly like sgsr.frag:
//   - #version 450 (Vulkan GLSL), ViewportInfo via push constant
//   - specialised to OperationMode == 1 (RGBA); green channel is the edge luma
//   - reuses window.vert (full-screen quad over the destination rect)
// =============================================================================

layout(binding = 0) uniform sampler2D ps0;

layout(push_constant) uniform PC {
    vec4  ndc;          // quad NDC rect, consumed by window.vert (offset 0)
    vec4  ViewportInfo; // xy = 1/inputSize (texel size), zw = inputSize in pixels
    float EdgeSharpness; // driven by the "Sharpness" slider
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 out_Target0;

const float EdgeThreshold = 8.0 / 255.0;

float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

// data.x = std, data.yz = normalised edge direction
vec2 weightY(float dx, float dy, float c, vec3 data) {
    float std = data.x;
    vec2  dir = data.yz;
    float edgeDis = (dx * dir.y) + (dy * dir.x);
    float x = ((dx * dx) + (dy * dy))
            + ((edgeDis * edgeDis) * ((clamp((c * c) * std, 0.0, 1.0) * 0.7) - 1.0));
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

vec2 edgeDirection(vec4 left, vec4 right) {
    float RxLz = right.x - left.z;
    float RwLy = right.w - left.y;
    vec2 delta;
    delta.x = RxLz + RwLy;
    delta.y = RxLz - RwLy;
    float lengthInv = inversesqrt((delta.x * delta.x + 3.075740e-05) + (delta.y * delta.y));
    return delta * lengthInv;
}

void main() {
    vec4 ViewportInfo = pc.ViewportInfo;
    vec2 uv = fragTexCoord;

    vec4 color;
    color.xyz = textureLod(ps0, uv, 0.0).xyz;

    vec2 imgCoord = ((uv * ViewportInfo.zw) + vec2(-0.5, 0.5));
    vec2 imgCoordPixel = floor(imgCoord);
    vec2 coord = (imgCoordPixel * ViewportInfo.xy);
    vec2 pl = (imgCoord + (-imgCoordPixel));
    vec4 left = textureGather(ps0, coord, 1);

    float edgeVote = abs(left.z - left.y) + abs(color.y - left.y) + abs(color.y - left.z);
    if (edgeVote > EdgeThreshold) {
        coord.x += ViewportInfo.x;

        vec4 right = textureGather(ps0, coord + vec2(ViewportInfo.x, 0.0), 1);
        vec4 upDown;
        upDown.xy = textureGather(ps0, coord + vec2(0.0, -ViewportInfo.y), 1).wz;
        upDown.zw = textureGather(ps0, coord + vec2(0.0,  ViewportInfo.y), 1).yx;

        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left   = left   - vec4(mean);
        right  = right  - vec4(mean);
        upDown = upDown - vec4(mean);
        color.w = color.y - mean;

        float sum = (((((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w))
                   + (((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w)))
                   + (((abs(upDown.x) + abs(upDown.y)) + abs(upDown.z)) + abs(upDown.w)));
        float sumMean = 1.014185e+01 / sum;
        float std = sumMean * sumMean;

        vec3 data = vec3(std, edgeDirection(left, right));

        vec2 aWY  = weightY(pl.x,       pl.y + 1.0, upDown.x, data);
        aWY      += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, data);
        aWY      += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, data);
        aWY      += weightY(pl.x,       pl.y - 2.0, upDown.w, data);
        aWY      += weightY(pl.x + 1.0, pl.y - 1.0, left.x,   data);
        aWY      += weightY(pl.x,       pl.y - 1.0, left.y,   data);
        aWY      += weightY(pl.x,       pl.y,       left.z,   data);
        aWY      += weightY(pl.x + 1.0, pl.y,       left.w,   data);
        aWY      += weightY(pl.x - 1.0, pl.y - 1.0, right.x,  data);
        aWY      += weightY(pl.x - 2.0, pl.y - 1.0, right.y,  data);
        aWY      += weightY(pl.x - 2.0, pl.y,       right.z,  data);
        aWY      += weightY(pl.x - 1.0, pl.y,       right.w,  data);

        float finalY = aWY.y / aWY.x;

        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));
        finalY = clamp(pc.EdgeSharpness * finalY, minY, maxY);

        float deltaY = finalY - color.w;
        deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);

        color.x = clamp((color.x + deltaY), 0.0, 1.0);
        color.y = clamp((color.y + deltaY), 0.0, 1.0);
        color.z = clamp((color.z + deltaY), 0.0, 1.0);
    }

    color.w = 1.0; // alpha channel unused by the compositor blit
    out_Target0 = color;
}
