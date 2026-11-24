#version 450

// =============================================================================
//  AMD FidelityFX Super Resolution 1.0 - RCAS (Robust Contrast-Adaptive Sharpen).
//
//  Algorithm (FsrRcasF) ported verbatim from AMD's reference header:
//    GPUOpen-Effects/FidelityFX-FSR  ffx-fsr/ffx_fsr1.h
//
//  Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
//  SPDX-License-Identifier: MIT
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to deal
//  in the Software without restriction ... THE SOFTWARE IS PROVIDED "AS IS".
//  (Full MIT text retained from the source header.)
//
//  Adapted for the Winlator native Vulkan compositor:
//   - fp32-only path (the AF1/AU1 portability macros below replace ffx_a.h)
//   - the sharpness constant is computed CPU-side (FsrRcasCon) -> push const con
//   - input is the EASU output (already at output resolution), read 1:1 via
//     texelFetch with edge clamping for robustness
// =============================================================================

// ---- minimal ffx_a.h fp32 portability layer -------------------------------
#define AF1 float
#define AF2 vec2
#define AF3 vec3
#define AF4 vec4
#define AU2 uvec2
#define AU4 uvec4
#define ASU2 ivec2
#define AF1_(a) float(a)
#define AF1_AU1(x) uintBitsToFloat(uint(x))
float ARcpF1(float x)      { return 1.0 / x; }
float APrxMedRcpF1(float x){ return 1.0 / x; }
float ASatF1(float x)      { return clamp(x, 0.0, 1.0); }
float AMin3F1(float a, float b, float c) { return min(a, min(b, c)); }
float AMax3F1(float a, float b, float c) { return max(a, max(b, c)); }
#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))
// ---------------------------------------------------------------------------

// BL_HALF is set for the *_half_frag.h build (gen_shaders.sh): colour and
// weight maths in mediump (RelaxedPrecision, 16-bit on Adreno) as in AMD's
// FSR_EASU_H / FSR_RCAS_H paths; positions and texture coordinates stay highp.
#ifdef BL_HALF
precision mediump float;
#define BL_MP mediump
#else
#define BL_MP
#endif

layout(binding = 0) uniform BL_MP sampler2D InputTexture; // EASU output, output res

layout(push_constant) uniform PC {
    highp vec4  ndc;        // quad NDC rect, consumed by window.vert (offset 0)
    uvec4 con;        // con.x = bit-packed sharpness (FsrRcasCon)
    highp vec2  outputSize; // pass output size in pixels
} pc;

layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

AF4 FsrRcasLoadF(ASU2 p) {
    ivec2 sz = textureSize(InputTexture, 0);
    return texelFetch(InputTexture, clamp(p, ivec2(0), sz - ivec2(1)), 0);
}
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

void FsrRcasF(
 out AF1 pixR,
 out AF1 pixG,
 out AF1 pixB,
 AU2 ip,
 AU4 con){
  ASU2 sp=ASU2(ip);
  AF3 b=FsrRcasLoadF(sp+ASU2( 0,-1)).rgb;
  AF3 d=FsrRcasLoadF(sp+ASU2(-1, 0)).rgb;
  AF3 e=FsrRcasLoadF(sp).rgb;
  AF3 f=FsrRcasLoadF(sp+ASU2( 1, 0)).rgb;
  AF3 h=FsrRcasLoadF(sp+ASU2( 0, 1)).rgb;
  AF1 bR=b.r;
  AF1 bG=b.g;
  AF1 bB=b.b;
  AF1 dR=d.r;
  AF1 dG=d.g;
  AF1 dB=d.b;
  AF1 eR=e.r;
  AF1 eG=e.g;
  AF1 eB=e.b;
  AF1 fR=f.r;
  AF1 fG=f.g;
  AF1 fB=f.b;
  AF1 hR=h.r;
  AF1 hG=h.g;
  AF1 hB=h.b;
  FsrRcasInputF(bR,bG,bB);
  FsrRcasInputF(dR,dG,dB);
  FsrRcasInputF(eR,eG,eB);
  FsrRcasInputF(fR,fG,fB);
  FsrRcasInputF(hR,hG,hB);
  AF1 bL=bB*AF1_(0.5)+(bR*AF1_(0.5)+bG);
  AF1 dL=dB*AF1_(0.5)+(dR*AF1_(0.5)+dG);
  AF1 eL=eB*AF1_(0.5)+(eR*AF1_(0.5)+eG);
  AF1 fL=fB*AF1_(0.5)+(fR*AF1_(0.5)+fG);
  AF1 hL=hB*AF1_(0.5)+(hR*AF1_(0.5)+hG);
  AF1 nz=AF1_(0.25)*bL+AF1_(0.25)*dL+AF1_(0.25)*fL+AF1_(0.25)*hL-eL;
  nz=ASatF1(abs(nz)*APrxMedRcpF1(AMax3F1(AMax3F1(bL,dL,eL),fL,hL)-AMin3F1(AMin3F1(bL,dL,eL),fL,hL)));
  nz=AF1_(-0.5)*nz+AF1_(1.0);
  AF1 mn4R=min(AMin3F1(bR,dR,fR),hR);
  AF1 mn4G=min(AMin3F1(bG,dG,fG),hG);
  AF1 mn4B=min(AMin3F1(bB,dB,fB),hB);
  AF1 mx4R=max(AMax3F1(bR,dR,fR),hR);
  AF1 mx4G=max(AMax3F1(bG,dG,fG),hG);
  AF1 mx4B=max(AMax3F1(bB,dB,fB),hB);
  AF2 peakC=AF2(1.0,-1.0*4.0);
  AF1 hitMinR=min(mn4R,eR)*ARcpF1(AF1_(4.0)*mx4R);
  AF1 hitMinG=min(mn4G,eG)*ARcpF1(AF1_(4.0)*mx4G);
  AF1 hitMinB=min(mn4B,eB)*ARcpF1(AF1_(4.0)*mx4B);
  AF1 hitMaxR=(peakC.x-max(mx4R,eR))*ARcpF1(AF1_(4.0)*mn4R+peakC.y);
  AF1 hitMaxG=(peakC.x-max(mx4G,eG))*ARcpF1(AF1_(4.0)*mn4G+peakC.y);
  AF1 hitMaxB=(peakC.x-max(mx4B,eB))*ARcpF1(AF1_(4.0)*mn4B+peakC.y);
  AF1 lobeR=max(-hitMinR,hitMaxR);
  AF1 lobeG=max(-hitMinG,hitMaxG);
  AF1 lobeB=max(-hitMinB,hitMaxB);
  AF1 lobe=max(AF1_(-FSR_RCAS_LIMIT),min(AMax3F1(lobeR,lobeG,lobeB),AF1_(0.0)))*AF1_AU1(con.x);
  AF1 rcpL=APrxMedRcpF1(AF1_(4.0)*lobe+AF1_(1.0));
  pixR=(lobe*bR+lobe*dR+lobe*hR+lobe*fR+eR)*rcpL;
  pixG=(lobe*bG+lobe*dG+lobe*hG+lobe*fG+eG)*rcpL;
  pixB=(lobe*bB+lobe*dB+lobe*hB+lobe*fB+eB)*rcpL;
  return;}

void main() {
    uvec2 ip = uvec2(fragTexCoord * pc.outputSize);
    vec3 c;
    FsrRcasF(c.r, c.g, c.b, ip, pc.con);
    outColor = vec4(c, 1.0);
}
