#version 450

// =============================================================================
//  AMD FidelityFX Super Resolution 1.0 - EASU (Edge-Adaptive Spatial Upsampling).
//
//  Algorithm (FsrEasuF / FsrEasuTapF / FsrEasuSetF) ported verbatim from AMD's
//  reference header:  GPUOpen-Effects/FidelityFX-FSR  ffx-fsr/ffx_fsr1.h
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
//   - con0..con3 are computed CPU-side (FsrEasuCon) and delivered via push const
//   - output pixel position comes from window.vert's fragTexCoord * outputSize
//   - input sampled via textureGather on a CLAMP_TO_EDGE / linear sampler
// =============================================================================

// ---- minimal ffx_a.h fp32 portability layer -------------------------------
#define AF1 float
#define AF2 vec2
#define AF3 vec3
#define AF4 vec4
#define AU1 uint
#define AU2 uvec2
#define AU4 uvec4
#define ASU2 ivec2
#define AP1 bool
#define AF1_(a) float(a)
#define AF2_(a) vec2(AF1_(a))
#define AF3_(a) vec3(AF1_(a))
#define AF4_(a) vec4(AF1_(a))
#define AF2_AU2(x) uintBitsToFloat(uvec2(x))
#define AF1_AU1(x) uintBitsToFloat(uint(x))
float ARcpF1(float x)      { return 1.0 / x; }
float APrxLoRcpF1(float x) { return 1.0 / x; }
float APrxMedRcpF1(float x){ return 1.0 / x; }
float APrxLoRsqF1(float x) { return inversesqrt(x); }
float ASatF1(float x)      { return clamp(x, 0.0, 1.0); }
vec3  AMin3F3(vec3 a, vec3 b, vec3 c) { return min(a, min(b, c)); }
vec3  AMax3F3(vec3 a, vec3 b, vec3 c) { return max(a, max(b, c)); }
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
#ifdef BL_HALF
// 1/32768 is a 16-bit subnormal (flushed to 0 where denormals are not kept);
// 1/16384 is the smallest normal one.
#define BL_EASU_DIR_ZERO (1.0/16384.0)
#else
#define BL_EASU_DIR_ZERO (1.0/32768.0)
#endif

layout(binding = 0) uniform BL_MP sampler2D InputTexture;

layout(push_constant) uniform PC {
    highp vec4  ndc;        // quad NDC rect, consumed by window.vert (offset 0)
    uvec4 con0;
    uvec4 con1;
    uvec4 con2;
    uvec4 con3;
    highp vec2  outputSize; // EASU output (this pass's render-target) size in pixels
} pc;

layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

AF4 FsrEasuRF(highp AF2 p) { return textureGather(InputTexture, p, 0); }
AF4 FsrEasuGF(highp AF2 p) { return textureGather(InputTexture, p, 1); }
AF4 FsrEasuBF(highp AF2 p) { return textureGather(InputTexture, p, 2); }

void FsrEasuTapF(
 inout AF3 aC,
 inout AF1 aW,
 AF2 off,
 AF2 dir,
 AF2 len,
 AF1 lob,
 AF1 clp,
 AF3 c){
  AF2 v;
  v.x=(off.x*( dir.x))+(off.y*dir.y);
  v.y=(off.x*(-dir.y))+(off.y*dir.x);
  v*=len;
  AF1 d2=v.x*v.x+v.y*v.y;
  d2=min(d2,clp);
  AF1 wB=AF1_(2.0/5.0)*d2+AF1_(-1.0);
  AF1 wA=lob*d2+AF1_(-1.0);
  wB*=wB;
  wA*=wA;
  wB=AF1_(25.0/16.0)*wB+AF1_(-(25.0/16.0-1.0));
  AF1 w=wB*wA;
  aC+=c*w;aW+=w;}

void FsrEasuSetF(
 inout AF2 dir,
 inout AF1 len,
 AF2 pp,
 AP1 biS,AP1 biT,AP1 biU,AP1 biV,
 AF1 lA,AF1 lB,AF1 lC,AF1 lD,AF1 lE){
  AF1 w = AF1_(0.0);
  if(biS)w=(AF1_(1.0)-pp.x)*(AF1_(1.0)-pp.y);
  if(biT)w=           pp.x *(AF1_(1.0)-pp.y);
  if(biU)w=(AF1_(1.0)-pp.x)*           pp.y ;
  if(biV)w=           pp.x *           pp.y ;
  AF1 dc=lD-lC;
  AF1 cb=lC-lB;
  AF1 lenX=max(abs(dc),abs(cb));
  lenX=APrxLoRcpF1(lenX);
  AF1 dirX=lD-lB;
  dir.x+=dirX*w;
  lenX=ASatF1(abs(dirX)*lenX);
  lenX*=lenX;
  len+=lenX*w;
  AF1 ec=lE-lC;
  AF1 ca=lC-lA;
  AF1 lenY=max(abs(ec),abs(ca));
  lenY=APrxLoRcpF1(lenY);
  AF1 dirY=lE-lA;
  dir.y+=dirY*w;
  lenY=ASatF1(abs(dirY)*lenY);
  lenY*=lenY;
  len+=lenY*w;}

void FsrEasuF(
 out AF3 pix,
 AU2 ip,
 AU4 con0,
 AU4 con1,
 AU4 con2,
 AU4 con3){
  highp AF2 pp=AF2(ip)*AF2_AU2(con0.xy)+AF2_AU2(con0.zw);
  highp AF2 fp=floor(pp);
  pp-=fp;
  highp AF2 p0=fp*AF2_AU2(con1.xy)+AF2_AU2(con1.zw);
  highp AF2 p1=p0+AF2_AU2(con2.xy);
  highp AF2 p2=p0+AF2_AU2(con2.zw);
  highp AF2 p3=p0+AF2_AU2(con3.xy);
  AF4 bczzR=FsrEasuRF(p0);
  AF4 bczzG=FsrEasuGF(p0);
  AF4 bczzB=FsrEasuBF(p0);
  AF4 ijfeR=FsrEasuRF(p1);
  AF4 ijfeG=FsrEasuGF(p1);
  AF4 ijfeB=FsrEasuBF(p1);
  AF4 klhgR=FsrEasuRF(p2);
  AF4 klhgG=FsrEasuGF(p2);
  AF4 klhgB=FsrEasuBF(p2);
  AF4 zzonR=FsrEasuRF(p3);
  AF4 zzonG=FsrEasuGF(p3);
  AF4 zzonB=FsrEasuBF(p3);
  AF4 bczzL=bczzB*AF4_(0.5)+(bczzR*AF4_(0.5)+bczzG);
  AF4 ijfeL=ijfeB*AF4_(0.5)+(ijfeR*AF4_(0.5)+ijfeG);
  AF4 klhgL=klhgB*AF4_(0.5)+(klhgR*AF4_(0.5)+klhgG);
  AF4 zzonL=zzonB*AF4_(0.5)+(zzonR*AF4_(0.5)+zzonG);
  AF1 bL=bczzL.x;
  AF1 cL=bczzL.y;
  AF1 iL=ijfeL.x;
  AF1 jL=ijfeL.y;
  AF1 fL=ijfeL.z;
  AF1 eL=ijfeL.w;
  AF1 kL=klhgL.x;
  AF1 lL=klhgL.y;
  AF1 hL=klhgL.z;
  AF1 gL=klhgL.w;
  AF1 oL=zzonL.z;
  AF1 nL=zzonL.w;
  AF2 dir=AF2_(0.0);
  AF1 len=AF1_(0.0);
  FsrEasuSetF(dir,len,pp,true, false,false,false,bL,eL,fL,gL,jL);
  FsrEasuSetF(dir,len,pp,false,true ,false,false,cL,fL,gL,hL,kL);
  FsrEasuSetF(dir,len,pp,false,false,true ,false,fL,iL,jL,kL,nL);
  FsrEasuSetF(dir,len,pp,false,false,false,true ,gL,jL,kL,lL,oL);
  AF2 dir2=dir*dir;
  AF1 dirR=dir2.x+dir2.y;
  AP1 zro=dirR<AF1_(BL_EASU_DIR_ZERO);
  dirR=APrxLoRsqF1(dirR);
  dirR=zro?AF1_(1.0):dirR;
  dir.x=zro?AF1_(1.0):dir.x;
  dir*=AF2_(dirR);
  len=len*AF1_(0.5);
  len*=len;
  AF1 stretch=(dir.x*dir.x+dir.y*dir.y)*APrxLoRcpF1(max(abs(dir.x),abs(dir.y)));
  AF2 len2=AF2(AF1_(1.0)+(stretch-AF1_(1.0))*len,AF1_(1.0)+AF1_(-0.5)*len);
  AF1 lob=AF1_(0.5)+AF1_((1.0/4.0-0.04)-0.5)*len;
  AF1 clp=APrxLoRcpF1(lob);
  AF3 min4=min(AMin3F3(AF3(ijfeR.z,ijfeG.z,ijfeB.z),AF3(klhgR.w,klhgG.w,klhgB.w),AF3(ijfeR.y,ijfeG.y,ijfeB.y)),
               AF3(klhgR.x,klhgG.x,klhgB.x));
  AF3 max4=max(AMax3F3(AF3(ijfeR.z,ijfeG.z,ijfeB.z),AF3(klhgR.w,klhgG.w,klhgB.w),AF3(ijfeR.y,ijfeG.y,ijfeB.y)),
               AF3(klhgR.x,klhgG.x,klhgB.x));
  AF3 aC=AF3_(0.0);
  AF1 aW=AF1_(0.0);
  FsrEasuTapF(aC,aW,AF2( 0.0,-1.0)-pp,dir,len2,lob,clp,AF3(bczzR.x,bczzG.x,bczzB.x));
  FsrEasuTapF(aC,aW,AF2( 1.0,-1.0)-pp,dir,len2,lob,clp,AF3(bczzR.y,bczzG.y,bczzB.y));
  FsrEasuTapF(aC,aW,AF2(-1.0, 1.0)-pp,dir,len2,lob,clp,AF3(ijfeR.x,ijfeG.x,ijfeB.x));
  FsrEasuTapF(aC,aW,AF2( 0.0, 1.0)-pp,dir,len2,lob,clp,AF3(ijfeR.y,ijfeG.y,ijfeB.y));
  FsrEasuTapF(aC,aW,AF2( 0.0, 0.0)-pp,dir,len2,lob,clp,AF3(ijfeR.z,ijfeG.z,ijfeB.z));
  FsrEasuTapF(aC,aW,AF2(-1.0, 0.0)-pp,dir,len2,lob,clp,AF3(ijfeR.w,ijfeG.w,ijfeB.w));
  FsrEasuTapF(aC,aW,AF2( 1.0, 1.0)-pp,dir,len2,lob,clp,AF3(klhgR.x,klhgG.x,klhgB.x));
  FsrEasuTapF(aC,aW,AF2( 2.0, 1.0)-pp,dir,len2,lob,clp,AF3(klhgR.y,klhgG.y,klhgB.y));
  FsrEasuTapF(aC,aW,AF2( 2.0, 0.0)-pp,dir,len2,lob,clp,AF3(klhgR.z,klhgG.z,klhgB.z));
  FsrEasuTapF(aC,aW,AF2( 1.0, 0.0)-pp,dir,len2,lob,clp,AF3(klhgR.w,klhgG.w,klhgB.w));
  FsrEasuTapF(aC,aW,AF2( 1.0, 2.0)-pp,dir,len2,lob,clp,AF3(zzonR.z,zzonG.z,zzonB.z));
  FsrEasuTapF(aC,aW,AF2( 0.0, 2.0)-pp,dir,len2,lob,clp,AF3(zzonR.w,zzonG.w,zzonB.w));
  pix=min(max4,max(min4,aC*AF3_(ARcpF1(aW))));}

void main() {
    uvec2 ip = uvec2(fragTexCoord * pc.outputSize);
    vec3 c;
    FsrEasuF(c, ip, pc.con0, pc.con1, pc.con2, pc.con3);
    outColor = vec4(c, 1.0);
}
