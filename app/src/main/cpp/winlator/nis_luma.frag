#version 450

// =============================================================================
//  NIS prepass for nis2.frag: the game image copied 1:1 with its NIS luma in
//  alpha (the compositor never uses the alpha of these targets). nis2.frag then
//  reads four luma texels per textureGather instead of computing luma for each
//  of 36 fetches per output pixel.
//
//  Runs at the game (input) resolution into a target of the same size, so the
//  output pixel reads its input pixel 1:1 (texelFetch at gl_FragCoord).
//  Luma weights = nis.frag getY() (NVIDIA NIS, SDR: BT.709).
// =============================================================================

#ifdef BL_HALF
precision mediump float;
#define BL_MP mediump
#else
#define BL_MP
#endif

layout(binding = 0) uniform BL_MP sampler2D InputTexture;

layout(push_constant) uniform PC {
    highp vec4 ndc;     // quad NDC rect, consumed by upscale.vert (offset 0); full target
} pc;

layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 c = texelFetch(InputTexture, ivec2(gl_FragCoord.xy), 0).rgb;
    outColor = vec4(c, 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b);
}
