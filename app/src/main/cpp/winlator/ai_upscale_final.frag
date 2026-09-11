#version 450

// AI Upscale (scaling modes 9 "AI" and 10 "AI HQ") - final pass.
//
// The compute pass (ai_upscale_fast/hq.comp) has already written, for every game pixel, the
// four sub-pixel luma residuals of a 2x enlargement: the detail a bilinear enlargement loses,
// as predicted by the network. This pass draws the game into the destination rect: a bilinear
// sample of the game frame, plus the residual field (bilinearly interpolated on the 2x grid)
// added equally to R, G and B - that changes brightness only, never the colour. At exactly 2x
// it reproduces the network's output; at other ratios it is the natural interpolation of it.
//
// Vertex stage: upscale.vert (reads the leading `ndc` push constant, fragTexCoord in [0,1]).

layout(set = 0, binding = 0) uniform sampler2D srcTex;   // game frame, bilinear + clamp
layout(set = 1, binding = 0) uniform sampler2D resTex;   // residuals, game res, texelFetch only

layout(push_constant) uniform PC {
    vec4  ndc;            // destination rect (read by the vertex stage)
    vec4  viewportInfo;   // xy = 1 / game size, zw = game size in pixels
    float detail;         // residual gain from the Detail slider (1 = as trained, 0 = off)
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

// Residual of one texel of the 2x grid; coordinates clamped (edge texels repeat).
float residualAt(ivec2 hr, ivec2 hrMax) {
    hr = clamp(hr, ivec2(0), hrMax);
    vec4 r = texelFetch(resTex, hr >> 1, 0);
    int k = (hr.y & 1) * 2 + (hr.x & 1);
    return k == 0 ? r.x : (k == 1 ? r.y : (k == 2 ? r.z : r.w));
}

void main() {
    vec2  size = pc.viewportInfo.zw;
    vec3  base = texture(srcTex, fragTexCoord).rgb;

    vec2  h  = fragTexCoord * size * 2.0 - 0.5;     // position on the 2x grid (texel centres = integers)
    vec2  f  = fract(h);
    ivec2 h0 = ivec2(floor(h));
    ivec2 hrMax = ivec2(size) * 2 - 1;
    float r00 = residualAt(h0,               hrMax);
    float r10 = residualAt(h0 + ivec2(1, 0), hrMax);
    float r01 = residualAt(h0 + ivec2(0, 1), hrMax);
    float r11 = residualAt(h0 + ivec2(1, 1), hrMax);
    float r   = mix(mix(r00, r10, f.x), mix(r01, r11, f.x), f.y);

    outColor = vec4(clamp(base + r * pc.detail, 0.0, 1.0), 1.0);
}
